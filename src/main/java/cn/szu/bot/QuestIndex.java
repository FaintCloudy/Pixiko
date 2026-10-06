package cn.szu.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 网页任务回执的**索引**：只记元信息（任务号、指令、开始时间、文字/图片条数、第一段文字压成的摘要、
 * 已读标记），**不存正文**——正文按任务号落在 {@code data/quests/&lt;number&gt;.json}
 * （见 {@link Bot} 的回执正文落盘），这里只负责列表与未读状态。
 *
 * <p>回执内容不再有"过期"概念：{@code expired} 永远为 false，列表与单条都以磁盘正文为准。
 * 只有被上限裁掉的**最旧**那批，才会连正文文件一起删。
 *
 * <p>索引落盘在 {@code data/quests.json}（{@link Json#atomicWrite}，UTF-8 无 BOM），
 * 最多留最新 {@value #MAX_ENTRIES} 条，超出的丢最旧的。
 * 磁盘读写失败只写日志：索引坏了也绝不能影响回执本身。
 *
 * <p>线程语义：网页轮询、指令线程与后台消息会并发进来，因此所有公开方法都 {@code synchronized}。
 * 写盘节流不在这里做（{@link Bot} 按 revision 与时间决定要不要调 {@link #save(Path)}）。
 */
public final class QuestIndex {

    /** 索引最多保留的条数：再多就把最旧的任务号（连同 data/quests/&lt;n&gt;.json 正文）挤掉。 */
    public static final int MAX_ENTRIES = 2000;
    /** 摘要最多取的字符数（第一段文字压成单行后）。 */
    public static final int SUMMARY_CHARS = 120;
    /** 落盘时间戳：UTC ISO-8601，固定毫秒 + Z（JS 的 new Date(...) 直接能解析）。 */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** 一条回执的索引项（正文不在里面）。 */
    public static final class Entry {
        final int number;
        /**
         * 这条回执属于哪个网页会话（{@code web} / {@code dev-xxxxxxxxxxxx} / 旧的 QQ 号）。
         *
         * <p>空串 = 不知道（本次改动之前写下的旧索引、或老版本的正文文件）。旧条目**不冒充**任何一个
         * 会话：带 scope 的列表（手机端）看不到它们，不带 scope 的列表（控制台）照旧全都能看到。
         */
        String scope = "";
        String command = "";
        long startedMillis;
        String summary = "";
        int texts;
        int images;
        boolean done;
        /** 有内容之后还没被打开过。 */
        boolean unread;
        /** 被打开过（/api/quest 取到，或 /api/quests/read）：之后不会再变回未读。 */
        boolean read;
        /** 这一轮刷新时内容还在内存里（false = 重启后或已从内存淘汰；只影响 busy，不影响可见性）。 */
        boolean alive;

        Entry(int number) { this.number = number; }
    }

    /** 任务号 → 条目，按登记顺序（新号在后面）。 */
    private final LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<>();
    /** 被上限裁掉、等着连正文文件一起删的任务号（{@link Bot} 用 {@link #drainTrimmed()} 取走）。 */
    private final List<Integer> trimmed = new ArrayList<>();
    /** 每次**实际变化**自增：写盘节流据此判断"内容真变了没有"。 */
    private long revision;

    /** 索引版本号：每有一次真实变化就 +1（只读、纯时间流逝不算）。 */
    public synchronized long revision() { return revision; }

    /** 索引里一共多少条。 */
    public synchronized int size() { return entries.size(); }

    /** 未读条数（顶层 unread）。 */
    public synchronized int unread() { return unread(null); }

    /** 某个会话的未读条数（{@code scope} 为空 = 所有会话，与 {@link #unread()} 同一个数）。 */
    public synchronized int unread(String scope) {
        int count = 0;
        for (Entry entry : entries.values()) if (entry.unread && visible(entry, scope)) count++;
        return count;
    }

    /** 最大任务号（没有就是 0）。 */
    public synchronized int latest() { return latest(null); }

    /** 某个会话里最大的任务号（{@code scope} 为空 = 所有会话）。 */
    public synchronized int latest(String scope) {
        int latest = 0;
        for (Entry entry : entries.values()) if (visible(entry, scope)) latest = Math.max(latest, entry.number);
        return latest;
    }

    /**
     * 一条索引项在"某个会话的视图"里可见吗。
     *
     * <p>{@code scope} 为空 = 不筛（控制台与老客户端的行为一字未改）；非空 = 只认这个会话自己的条目。
     * 空 scope 的旧条目不属于任何会话，因此**不出现**在任何带 scope 的视图里 —— 宁可少给，也不把
     * 控制台的回执塞进某台手机的列表（那正是用户报的「回执重复杂糅」）。
     */
    private static boolean visible(Entry entry, String scope) {
        if (scope == null || scope.isBlank()) return true;
        return scope.equals(entry.scope == null ? "" : entry.scope);
    }

    /** 开始一轮刷新：先全部当成"内容已不在内存"，再由 {@link #observe} 把活着的重新点亮（只影响 busy）。 */
    public synchronized void begin() {
        for (Entry entry : entries.values()) entry.alive = false;
    }

    /**
     * 用一条**还活着**（还在内存里）的回执刷新它的索引项：任务号、指令、开始时间、条数、摘要、是否跑完。
     *
     * <p>未读的判定只在这里发生：第一次出现文字（texts 非空）且这条从没被打开过，才记为未读；
     * 已读一旦确定就再也不会变回未读。
     */
    public synchronized void observe(Bot.WebCapture capture) {
        if (capture == null) return;
        Entry entry = entries.get(capture.number());
        boolean created = entry == null;
        if (created) {
            entry = new Entry(capture.number());
            entries.put(entry.number, entry);
        }
        String command = capture.command() == null ? "" : capture.command();
        // 回执归属：由创建它的那次 /api/command 的 scope 定下来，之后（含落盘重载）不再变。
        String scope = capture.scope() == null ? "" : capture.scope();
        int texts = capture.textCount();
        int images = capture.imageCount();
        String summary = summary(capture.firstText());
        boolean done = capture.done();
        boolean changed = created
                || !scope.equals(entry.scope)
                || !command.equals(entry.command)
                || entry.texts != texts
                || entry.images != images
                || !summary.equals(entry.summary)
                || entry.done != done;
        if (entry.startedMillis <= 0) entry.startedMillis = System.currentTimeMillis() - Math.max(0, capture.ageMillis());
        entry.scope = scope;
        entry.command = command;
        entry.texts = texts;
        entry.images = images;
        entry.summary = summary;
        entry.done = done;
        entry.alive = true;
        // 有内容才算"来了新回执"；打开过（read）之后不再回到未读。
        if (texts > 0 && !entry.read && !entry.unread) { entry.unread = true; changed = true; }
        if (changed) revision++;
        trim();
    }

    /** 把这些任务号标为已读（索引里没有的号忽略）；返回本次真正从"未读"变"已读"的条数。 */
    public synchronized int markRead(Collection<Integer> numbers) {
        if (numbers == null || numbers.isEmpty()) return 0;
        int marked = 0;
        for (Integer number : numbers) {
            if (number == null) continue;
            Entry entry = entries.get(number);
            if (entry == null) continue;
            boolean changed = entry.unread;
            if (entry.unread) { entry.unread = false; marked++; }
            if (!entry.read) { entry.read = true; changed = true; }
            if (changed) revision++;
        }
        return marked;
    }

    /** 全部标为已读；返回本次真正从"未读"变"已读"的条数。 */
    public synchronized int markAllRead() { return markAllRead(null); }

    /**
     * 某个会话里的全部标为已读（{@code scope} 为空 = 所有会话）。
     *
     * <p>为什么必须按会话：手机端的「全部已读」以前会把控制台的未读也一起清掉（那是另一个会话的状态）。
     */
    public synchronized int markAllRead(String scope) {
        int marked = 0;
        for (Entry entry : entries.values()) {
            if (!visible(entry, scope)) continue;
            boolean changed = entry.unread;
            if (entry.unread) { entry.unread = false; marked++; }
            if (!entry.read) { entry.read = true; changed = true; }
            if (changed) revision++;
        }
        return marked;
    }

    /** {@code /api/status} 里那一小块：未读条数与最大任务号。 */
    public synchronized JsonObject counts() { return counts(null); }

    /** 某个会话的 {@code /api/status} 那一小块（{@code scope} 为空 = 所有会话）。 */
    public synchronized JsonObject counts(String scope) {
        JsonObject result = new JsonObject();
        result.addProperty("unread", unread(scope));
        result.addProperty("latest", latest(scope));
        return result;
    }

    /**
     * 列表接口的返回：最新的在前，最多 {@code limit} 条（1..{@value #MAX_ENTRIES}），**不带任何正文数组**。
     *
     * @param limit          最多返回多少条（越界会被钳到 1..{@value #MAX_ENTRIES}）
     * @param retainedMinutes 回执正文保留多少分钟（顶层原样回报给网页；0 = 不过期）
     */
    public synchronized JsonObject json(int limit, int retainedMinutes) { return json(limit, retainedMinutes, null); }

    /**
     * 某个会话的列表（{@code scope} 为空 = 所有会话，与上面那个两参数版本一字不差）。
     *
     * <p>带 scope 时，{@code quests} 只含这个会话自己的条目，**{@code unread} / {@code latest} /
     * {@code total} 也一起按这个会话算** —— 否则手机端的列表与角标会互相打架（列表里没有那条，
     * 角标却按它计数）。顶层多一个 {@code scoped} 告诉客户端"这次真的按会话筛过了"。
     */
    public synchronized JsonObject json(int limit, int retainedMinutes, String scope) {
        int wanted = Math.max(1, Math.min(MAX_ENTRIES, limit));
        List<Entry> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparingInt((Entry entry) -> entry.number).reversed());
        JsonArray quests = new JsonArray();
        int unread = 0, latest = 0, total = 0;
        for (Entry entry : sorted) {
            if (!visible(entry, scope)) continue;
            total++;
            if (entry.unread) unread++;
            latest = Math.max(latest, entry.number);
            if (quests.size() < wanted) quests.add(item(entry));
        }
        JsonObject result = new JsonObject();
        result.add("quests", quests);
        result.addProperty("unread", unread);
        result.addProperty("latest", latest);
        result.addProperty("total", total);
        result.addProperty("retainedMinutes", retainedMinutes);
        result.addProperty("scoped", scope != null && !scope.isBlank());
        return result;
    }

    /**
     * 单条索引项（字段与列表里的一致）。
     *
     * <p>{@code /api/quest} 在磁盘上也找不到正文时用它兜底，这样 /quest#N 这种直接打开的链接
     * 至少还能看到摘要而不是一句冷冰冰的报错；找不到返回 null。
     */
    public synchronized JsonObject item(int number) { return item(number, null); }

    /**
     * 某个会话里的一条索引项（{@code scope} 为空 = 不筛）。
     *
     * <p>带 scope 时，别的会话的任务号一律返回 null —— 调用方（{@code /api/quest}）据此如实告诉
     * 这台手机"这条回执不属于这个会话"，而不是把控制台的回执正文端过去。
     */
    public synchronized JsonObject item(int number, String scope) {
        Entry entry = entries.get(number);
        return entry == null || !visible(entry, scope) ? null : item(entry);
    }

    /** 一条索引项 → 网页用的 JSON（一条恰好 11 个字段，不带任何正文数组）。 */
    private JsonObject item(Entry entry) {
        JsonObject item = new JsonObject();
        item.addProperty("number", entry.number);
        item.addProperty("command", entry.command);
        item.addProperty("startedAt", stamp(entry.startedMillis));
        item.addProperty("ageMillis", Math.max(0, System.currentTimeMillis() - entry.startedMillis));
        item.addProperty("done", entry.done);
        item.addProperty("busy", entry.alive && !entry.done);
        item.addProperty("unread", entry.unread);
        // 回执内容不再过期：正文落在 data/quests/<n>.json，任何时候都能读回来。
        item.addProperty("expired", false);
        item.addProperty("texts", entry.texts);
        item.addProperty("images", entry.images);
        item.addProperty("summary", entry.summary);
        return item;
    }

    /**
     * 读回 data/quests.json。读不到或读坏了都只写日志并留一个空索引——
     * 索引只是列表用的旁路数据，绝不能连累回执本身。
     */
    public synchronized void load(Path path) {
        entries.clear();
        if (path == null) return;
        try {
            if (!Files.isRegularFile(path)) return;
            JsonObject stored = Json.parse(Files.readString(path, StandardCharsets.UTF_8));
            JsonArray quests = stored.has("quests") && stored.get("quests").isJsonArray()
                    ? stored.getAsJsonArray("quests") : new JsonArray();
            for (JsonElement item : quests) {
                if (!item.isJsonObject()) continue;
                JsonObject node = item.getAsJsonObject();
                int number = Json.num(node, "number", 0);
                if (number <= 0 || entries.containsKey(number)) continue;
                Entry entry = new Entry(number);
                // 老索引里没有 scope（本次改动之前写下的）：留空串 = 不知道属于谁，
                // 只有不带 scope 的视图（控制台）看得到，绝不冒充某台手机的回执。
                entry.scope = Json.str(node, "scope", "");
                entry.command = Json.str(node, "command", "");
                entry.summary = Json.str(node, "summary", "");
                entry.texts = Math.max(0, Json.num(node, "texts", 0));
                entry.images = Math.max(0, Json.num(node, "images", 0));
                entry.done = Json.bool(node, "done", false);
                entry.unread = Json.bool(node, "unread", false);
                entry.read = Json.bool(node, "read", !entry.unread);
                entry.startedMillis = parseStamp(Json.str(node, "startedAt", ""));
                if (entry.startedMillis <= 0) entry.startedMillis = System.currentTimeMillis();
                entries.put(number, entry);
            }
            trim();
        } catch (Exception error) {
            entries.clear();
            Log.warn("网页回执索引读取失败（列表从空开始，回执本身不受影响）：" + Bot.error(error));
        }
    }

    /** 写回 data/quests.json（UTF-8 无 BOM 的原子写）；写失败只写日志。 */
    public synchronized void save(Path path) {
        if (path == null) return;
        try {
            List<Entry> sorted = new ArrayList<>(entries.values());
            sorted.sort(Comparator.comparingInt(entry -> entry.number));
            JsonArray quests = new JsonArray();
            for (Entry entry : sorted) {
                JsonObject node = new JsonObject();
                node.addProperty("number", entry.number);
                node.addProperty("scope", entry.scope == null ? "" : entry.scope);
                node.addProperty("command", entry.command);
                node.addProperty("startedAt", stamp(entry.startedMillis));
                node.addProperty("summary", entry.summary);
                node.addProperty("texts", entry.texts);
                node.addProperty("images", entry.images);
                node.addProperty("done", entry.done);
                node.addProperty("unread", entry.unread);
                node.addProperty("read", entry.read);
                quests.add(node);
            }
            JsonObject stored = new JsonObject();
            stored.addProperty("version", 1);
            stored.add("quests", quests);
            Json.atomicWrite(path, stored);
        } catch (Exception error) {
            Log.warn("网页回执索引写入失败（下次再试，回执本身不受影响）：" + Bot.error(error));
        }
    }

    /**
     * 超出上限就丢最旧的（按任务号最小者，任务号复用也能正确收尾）。
     * 被丢掉的任务号记在 {@link #trimmed} 里，由 {@link Bot} 取走后删掉对应的正文文件。
     */
    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            int oldest = Integer.MAX_VALUE;
            for (int number : entries.keySet()) oldest = Math.min(oldest, number);
            if (entries.remove(oldest) != null) trimmed.add(oldest);
        }
    }

    /**
     * 取走（并清空）最近被上限裁掉的任务号。
     *
     * <p>{@link Bot} 每次 {@code observe}/{@code load} 之后调它，把 {@code data/quests/<n>.json}
     * 一起删掉——索引都不留的号，正文文件也不该留成孤儿。
     */
    public synchronized List<Integer> drainTrimmed() {
        if (trimmed.isEmpty()) return List.of();
        List<Integer> drained = new ArrayList<>(trimmed);
        trimmed.clear();
        return drained;
    }

    /** 落盘时间戳：UTC ISO-8601，固定毫秒 + Z（JS 的 new Date(...) 直接能解析）。索引与回执正文共用。 */
    static String stamp(long millis) { return STAMP.format(Instant.ofEpochMilli(millis)); }

    /** 第一段文字压成单行后的前 {@value #SUMMARY_CHARS} 个字符；没有文字就是空串。 */
    static String summary(String text) {
        if (text == null) return "";
        String one = text.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]+", " ").replaceAll("\\s+", " ").strip();
        return one.length() > SUMMARY_CHARS ? one.substring(0, SUMMARY_CHARS) : one;
    }

    /** 解析 {@link #stamp(long)} 写出的时间戳（读不出来返回 0）。 */
    static long parseStamp(String value) {
        if (value == null || value.isBlank()) return 0;
        try { return Instant.parse(value.strip()).toEpochMilli(); }
        catch (Exception error) { return 0; }
    }
}

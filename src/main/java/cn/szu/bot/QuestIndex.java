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
 * 已读标记），**不存正文**——正文只活在内存里的 {@link Bot.WebCapture}，回执过期或重启之后，
 * 列表照样能说清楚"有过这么一条任务"，只是内容标记为已过期。
 *
 * <p>索引落盘在 {@code data/quests.json}（{@link Json#atomicWrite}，UTF-8 无 BOM），
 * 最多留最新 {@value #MAX_ENTRIES} 条，超出的丢最旧的。
 * 磁盘读写失败只写日志：索引坏了也绝不能影响回执本身。
 *
 * <p>线程语义：网页轮询、指令线程与后台消息会并发进来，因此所有公开方法都 {@code synchronized}。
 * 写盘节流不在这里做（{@link Bot} 按 revision 与时间决定要不要调 {@link #save(Path)}）。
 */
public final class QuestIndex {

    /** 索引最多保留的条数：再多就把最旧的任务号挤掉。 */
    public static final int MAX_ENTRIES = 200;
    /** 摘要最多取的字符数（第一段文字压成单行后）。 */
    public static final int SUMMARY_CHARS = 120;
    /** 落盘时间戳：UTC ISO-8601，固定毫秒 + Z（JS 的 new Date(...) 直接能解析）。 */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** 一条回执的索引项（正文不在里面）。 */
    public static final class Entry {
        final int number;
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
        /** 这一轮刷新时内容还在内存里（false = 重启后或超过保留时间的旧条目）。 */
        boolean alive;

        Entry(int number) { this.number = number; }
    }

    /** 任务号 → 条目，按登记顺序（新号在后面）。 */
    private final LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<>();
    /** 每次**实际变化**自增：写盘节流据此判断"内容真变了没有"。 */
    private long revision;

    /** 索引版本号：每有一次真实变化就 +1（只读、纯时间流逝不算）。 */
    public synchronized long revision() { return revision; }

    /** 索引里一共多少条。 */
    public synchronized int size() { return entries.size(); }

    /** 未读条数（顶层 unread）。 */
    public synchronized int unread() {
        int count = 0;
        for (Entry entry : entries.values()) if (entry.unread) count++;
        return count;
    }

    /** 最大任务号（没有就是 0）。 */
    public synchronized int latest() {
        int latest = 0;
        for (int number : entries.keySet()) latest = Math.max(latest, number);
        return latest;
    }

    /** 开始一轮刷新：先全部当成"内容已不在内存"，再由 {@link #observe} 把活着的重新点亮。 */
    public synchronized void begin() {
        for (Entry entry : entries.values()) entry.alive = false;
    }

    /**
     * 用一条**还活着**的回执刷新它的索引项：任务号、指令、开始时间、条数、摘要、是否跑完。
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
        int texts = capture.textCount();
        int images = capture.imageCount();
        String summary = summary(capture.firstText());
        boolean done = capture.done();
        boolean changed = created
                || !command.equals(entry.command)
                || entry.texts != texts
                || entry.images != images
                || !summary.equals(entry.summary)
                || entry.done != done;
        if (entry.startedMillis <= 0) entry.startedMillis = System.currentTimeMillis() - Math.max(0, capture.ageMillis());
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
    public synchronized int markAllRead() {
        return markRead(new ArrayList<>(entries.keySet()));
    }

    /** {@code /api/status} 里那一小块：未读条数与最大任务号。 */
    public synchronized JsonObject counts() {
        JsonObject result = new JsonObject();
        result.addProperty("unread", unread());
        result.addProperty("latest", latest());
        return result;
    }

    /**
     * 列表接口的返回：最新的在前，最多 {@code limit} 条（1..200），**不带任何正文数组**。
     *
     * @param limit          最多返回多少条（越界会被钳到 1..{@value #MAX_ENTRIES}）
     * @param retainedMinutes 回执在内存里保留多少分钟（顶层原样回报给网页）
     */
    public synchronized JsonObject json(int limit, int retainedMinutes) {
        int wanted = Math.max(1, Math.min(MAX_ENTRIES, limit));
        List<Entry> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparingInt((Entry entry) -> entry.number).reversed());
        JsonArray quests = new JsonArray();
        for (int index = 0; index < sorted.size() && index < wanted; index++) quests.add(item(sorted.get(index)));
        JsonObject result = new JsonObject();
        result.add("quests", quests);
        result.addProperty("unread", unread());
        result.addProperty("latest", latest());
        result.addProperty("total", entries.size());
        result.addProperty("retainedMinutes", retainedMinutes);
        return result;
    }

    /**
     * 单条索引项（字段与列表里的一致）。
     *
     * <p>{@code /api/quest} 在正文已经回收（重启或超过保留时间）时用它回一份"只剩摘要"的结果，
     * 这样 /quest#N 这种直接打开的链接也能看到摘要而不是一句冷冰冰的报错；找不到返回 null。
     */
    public synchronized JsonObject item(int number) {
        Entry entry = entries.get(number);
        return entry == null ? null : item(entry);
    }

    /** 一条索引项 → 网页用的 JSON（一条恰好 11 个字段，不带任何正文数组）。 */
    private JsonObject item(Entry entry) {
        JsonObject item = new JsonObject();
        item.addProperty("number", entry.number);
        item.addProperty("command", entry.command);
        item.addProperty("startedAt", STAMP.format(Instant.ofEpochMilli(entry.startedMillis)));
        item.addProperty("ageMillis", Math.max(0, System.currentTimeMillis() - entry.startedMillis));
        item.addProperty("done", entry.done);
        item.addProperty("busy", entry.alive && !entry.done);
        item.addProperty("unread", entry.unread);
        item.addProperty("expired", !entry.alive);
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
                node.addProperty("command", entry.command);
                node.addProperty("startedAt", STAMP.format(Instant.ofEpochMilli(entry.startedMillis)));
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

    /** 超出上限就丢最旧的（按任务号最小者，任务号复用也能正确收尾）。 */
    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            int oldest = Integer.MAX_VALUE;
            for (int number : entries.keySet()) oldest = Math.min(oldest, number);
            entries.remove(oldest);
        }
    }

    /** 第一段文字压成单行后的前 {@value #SUMMARY_CHARS} 个字符；没有文字就是空串。 */
    static String summary(String text) {
        if (text == null) return "";
        String one = text.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]+", " ").replaceAll("\\s+", " ").strip();
        return one.length() > SUMMARY_CHARS ? one.substring(0, SUMMARY_CHARS) : one;
    }

    private static long parseStamp(String value) {
        if (value == null || value.isBlank()) return 0;
        try { return Instant.parse(value.strip()).toEpochMilli(); }
        catch (Exception error) { return 0; }
    }
}

package cn.szu.bot.web;

import cn.szu.bot.Json;
import cn.szu.bot.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网页/手机会话的**事件队列**（服务端入队、前端出队渲染）—— 对话里"会出现在屏幕上的信息"的权威来源。
 *
 * <p>用户原话：「api 待获取的信息设置成一个队列，前端拿到信息就出队然后渲染」。于是这里把过去
 * 前端要靠 `/api/capture` + `/api/quest` + `/api/chat/log` 三路**轮询 + 自己缝**的东西，收敛成
 * 一条**单调递增的队列**：服务端产生信息就 append（入队），前端拿着自己的 {@code after} 取一批
 * （出队），渲染完再 ack。前端不再猜身份、不再做启发式缝合。
 *
 * <p><b>接口</b>（契约见 {@code WebApiController} 的 {@code /api/events} 与 {@code /api/events/ack}）：
 * <pre>
 *   POST /api/events      {"scope":"…","after":0,"limit":200}
 *     → {"scope","items":[{"seq","id","type","at",…}],"next","latest","hasMore","acked","trimmedUpTo"}
 *   POST /api/events/ack  {"scope":"…","seq":124}  → {"ok":true,"acked":124}
 * </pre>
 *
 * <p><b>事件类型</b>（服务端权威；前端对未知 type 必须忽略而不是崩）：
 * <ul>
 *   <li>{@code {"type":"message","id":"…","role":"user|bot","text":"…","images":[…],"quest":480,"index":3}}
 *       —— 对话里的一条（用户发的、bot 回的、回执里的一条都算）；</li>
 *   <li>{@code {"type":"patch","id":"patch:…","target":"<某条 message 的 id>","images":[…]}}
 *       —— <b>图片晚到</b>时不动原条目，前端按 {@code target} 就地补图（"先文字后图并进同一气泡"）。</li>
 * </ul>
 *
 * <p><b>幂等键</b>：每条事件都带一个稳定 {@code id}，同一个 id 只入队一次（记在内存里 + 落盘文件里）。
 * 回执正文用 {@code quest:<号>:<序号>}（与 {@link ChatLogStore} 的落盘键、前端的分组键同一个口径），
 * 纯图条目用 {@code quest:<号>:img:<下标>}，图片补丁用 {@code patch:<条目 id>:<图路径 hash>}，
 * 用户消息用 {@code msg:<uuid>}。
 *
 * <p><b>存储</b>：每个 scope 一份 {@code <root>/data/webui/<scope>-events.json}，UTF-8 无 BOM、纯 LF、
 * 原子写，结构 {@code {"version","scope","nextSeq","trimmedUpTo","savedAtMillis","seen":[…],"items":[…]}}；
 * ack 单独一份 {@code <scope>-events-ack.json}（每次 ack 只写这个小文件，不重写整条队列）。
 * <b>不设人为条数上限</b>：只有"已 ack 且超过 {@value #TRIM_THRESHOLD} 条"时，才从**最旧的一端**
 * 裁掉一段**连续的已 ack 前缀**（未 ack 的一条都不删），并把裁到的最大 seq 记进 {@code trimmedUpTo}
 * —— 前端看到自己的 {@code after} 小于它就说明"你落后太多，需要重新全量对账"。
 *
 * <p><b>绝不抛</b>（与 {@link ChatLogStore} 同规矩）：读不出来给空队列、写不进去只 warn，
 * 聊天与回执照常。
 */
public final class EventQueueStore {

    /** {@code POST /api/events} 不传 {@code limit} 时一批的条数。 */
    public static final int DEFAULT_LIMIT = 200;
    /** 一批最多这么多条（不是队列上限：前端靠 {@code hasMore} 连着取，直到 false）。 */
    public static final int MAX_LIMIT = 2000;
    /** 超过这么多条、且已经 ack 过，才滚动裁剪（"只在必要时"）。 */
    public static final int TRIM_THRESHOLD = 5000;
    /** 被裁掉的 id 最多记住这么多（只用于"同一条信息不再入队"的幂等判定）。 */
    private static final int SEEN_CAP = 4096;
    /** scope 净化后的长度上限。 */
    private static final int SCOPE_CAP = 64;
    /** 净化后为空的 scope 落在这个文件名上。 */
    private static final String DEFAULT_SCOPE = "default";

    /** 对话里的一条（用户发的、bot 回的、回执里的一条）。 */
    public static final String TYPE_MESSAGE = "message";
    /** 图片晚到：按 {@code target} 就地补进那一条，不改原条目。 */
    public static final String TYPE_PATCH = "patch";

    private final Path root;
    /** 入队/出队/ack 都串行：队列是"读-改-写"，同一时刻只能有一个写者。 */
    private final Object lock = new Object();
    /** 每个 scope 一份内存态（首次用到时从磁盘读回来）。 */
    private final Map<String, ScopeState> scopes = new ConcurrentHashMap<>();

    /** @param root 机器人根目录（{@code settings.root}），队列落在 {@code <root>/data/webui/} 下。 */
    public EventQueueStore(Path root) { this.root = root == null ? Path.of(".") : root; }

    // ---------------------------------------------------------------- 事件构造（入队的形状由这里定）

    /**
     * 一条 {@code message}：对话里的一条。
     *
     * @param id     稳定标识（例如 {@code quest:480:3}、{@code msg:<uuid>}）
     * @param role   {@code user} 或 {@code bot}（其余一律按 {@code bot}）
     * @param text   正文（可以是空串：纯图条目）
     * @param images 这条消息**产生时**就带着的图片路径（晚到的图走 {@link #patch}，不改这一条）
     * @param quest  回执号（≤0 表示不是回执，不带这个字段）
     * @param index  条内序号（回执里的第几条正文；≤0 表示不是回执正文）
     */
    public static JsonObject message(String id, String role, String text, List<String> images, int quest, int index) {
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("type", TYPE_MESSAGE);
        item.addProperty("at", System.currentTimeMillis());
        item.addProperty("role", "user".equals(role) ? "user" : "bot");
        item.addProperty("text", text == null ? "" : text);
        item.add("images", imageArray(images));
        if (quest > 0) {
            item.addProperty("quest", quest);
            item.addProperty("index", Math.max(0, index));
        }
        return item;
    }

    /** 用户消息（网页/手机发的一句话）：{@code id = "msg:<uuid>"}，调用方给 uuid。 */
    public static JsonObject userMessage(String uuid, String text, List<String> images) {
        return message("msg:" + uuid, "user", text, images, 0, 0);
    }

    /** bot 的自然回应（网页对话那条 reply）：{@code id = "msg:<uuid>"}。 */
    public static JsonObject botMessage(String uuid, String text, List<String> images) {
        return message("msg:" + uuid, "bot", text, images, 0, 0);
    }

    /** 一条 {@code patch}：图片晚到，按 {@code target}（某条 message 的 id）就地补图。 */
    public static JsonObject patch(String target, String image) {
        JsonObject item = new JsonObject();
        item.addProperty("id", patchId(target, image));
        item.addProperty("type", TYPE_PATCH);
        item.addProperty("at", System.currentTimeMillis());
        item.addProperty("target", target == null ? "" : target);
        JsonArray images = new JsonArray();
        if (image != null && !image.isBlank()) images.add(image);
        item.add("images", images);
        return item;
    }

    /** 图片补丁的幂等键：{@code patch:<条目 id>:<图路径 hash>}（同一张图对同一条只入队一次）。 */
    public static String patchId(String target, String image) {
        return "patch:" + (target == null ? "" : target) + ":" + shortHash(image == null ? "" : image);
    }

    /** 回执正文的幂等键（与 {@link ChatLogStore#receipt} 的落盘键一字不差）。 */
    public static String receiptKey(String number, int seq) { return "quest:" + number + ":" + seq; }

    private static JsonArray imageArray(List<String> images) {
        JsonArray array = new JsonArray();
        if (images == null) return array;
        for (String image : images) if (image != null && !image.isBlank() && !contains(array, image)) array.add(image);
        return array;
    }

    private static boolean contains(JsonArray array, String value) {
        for (JsonElement node : array) if (node.isJsonPrimitive() && value.equals(node.getAsString())) return true;
        return false;
    }

    /** 图路径的稳定短哈希（同一条路径永远同一个值；用于 patch 的幂等键）。 */
    static String shortHash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(16);
            for (int index = 0; index < 8; index++) out.append(String.format("%02x", bytes[index]));
            return out.toString();
        } catch (Exception error) {
            return Integer.toHexString(value.hashCode());
        }
    }

    // ---------------------------------------------------------------- 入队

    /**
     * 入队一条事件（幂等：同一个 {@code id} 只入队一次）。
     *
     * <p>{@code seq} 由队列自己发（严格递增，从 1 开始）；{@code at} 没给就补当前毫秒。
     * 缺 {@code id} 或 {@code type} 的条目一律拒收（返回 false），绝不把分不清身份的东西塞进队列。
     *
     * @return true = 真的入队了；false = 这个 id 已经在队列里（或条目不合法）
     */
    public boolean append(String scope, JsonObject item) {
        if (item == null) return false;
        String id = Json.str(item, "id", "");
        String type = Json.str(item, "type", "");
        if (id.isBlank() || type.isBlank()) return false;
        synchronized (lock) {
            ScopeState state = state(scope);
            return appendLocked(state, item);
        }
    }

    /**
     * 把一条回执的**全部**出站消息推导成队列条目 —— 与 {@link ChatLogStore#receipt} 同一套分组规则
     * （每条带正文的消息各占一条，条内序号 1、2、3…；图片并进它前面那条正文；整条回执还没有正文时
     * 纯图各占一条）。
     *
     * <p>每次都用**整份消息**重新推导，图片比正文晚到时只补一条 {@code patch}（目标 = 那条 message 的 id），
     * 原条目**一个字都不改**；重复调用、机器人重启、页面补拉都只入队一次（id 幂等）。
     *
     * @param number 回执号（任务号），字符串形态（与 {@code quest:<号>:<序号>} 的键一致）
     * @return 本次真正入队的条数（0 = 一条都不新）
     */
    public int appendReceipt(String scope, String number, List<JsonArray> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int quest = parseNumber(number);
        int added = 0;
        int seq = 0;
        String lastText = null;                       // 最近一条带正文的条目 id（图片的落点）
        synchronized (lock) {
            ScopeState state = state(scope);
            for (int index = 0; index < messages.size(); index++) {
                JsonArray group = messages.get(index);
                String text = "";
                List<String> images = new ArrayList<>();
                if (group != null) {
                    for (JsonElement node : group) {
                        if (node == null || !node.isJsonObject()) continue;
                        JsonObject segment = node.getAsJsonObject();
                        String type = Json.str(segment, "type", "");
                        if ("text".equals(type)) {
                            String value = Json.str(segment, "text", "");
                            if (!value.isBlank()) text = text.isEmpty() ? value : text + "\n" + value;
                        } else if ("image".equals(type)) {
                            String file = Json.str(segment, "file", "");
                            if (!file.isBlank() && !images.contains(file)) images.add(file);
                        }
                    }
                }
                if (!text.isBlank()) {
                    seq++;
                    String key = receiptKey(number, seq);
                    if (enqueueLocked(state, message(key, "bot", text, images, quest, seq))) added++;
                    for (String image : images) if (enqueueImageLocked(state, key, image)) added++;
                    lastText = key;
                } else if (!images.isEmpty()) {
                    if (lastText != null) {
                        // 图并进上一条正文：同一个气泡（"先文字后图"），只发 patch。
                        for (String image : images) if (enqueueImageLocked(state, lastText, image)) added++;
                    } else {
                        String key = "quest:" + number + ":img:" + index;
                        if (enqueueLocked(state, message(key, "bot", "", images, quest, 0))) added++;
                        for (String image : images) if (enqueueImageLocked(state, key, image)) added++;
                    }
                }
            }
            // 一条回执整批只落一次盘（不然一条多步回执会重写整份队列 N 次）。
            if (added > 0) { trim(state); save(state); }
        }
        return added;
    }

    /** 一批入队（一次落盘）：语义与逐条 {@link #append} 完全一致（同样按 id 幂等）。 */
    public int appendAll(String scope, List<JsonObject> items) {
        if (items == null || items.isEmpty()) return 0;
        int added = 0;
        synchronized (lock) {
            ScopeState state = state(scope);
            for (JsonObject item : items) if (enqueueLocked(state, item)) added++;
            if (added > 0) { trim(state); save(state); }
        }
        return added;
    }

    /** 图片晚到：给 {@code target} 补一条 patch（这条 message 已经带着这张图时什么都不做）。 */
    public boolean ensureImage(String scope, String target, String image) {
        if (target == null || target.isBlank() || image == null || image.isBlank()) return false;
        synchronized (lock) {
            ScopeState state = state(scope);
            if (!enqueueImageLocked(state, target, image)) return false;
            trim(state);
            save(state);
            return true;
        }
    }

    // ---------------------------------------------------------------- 出队与 ack

    /**
     * 出一批队（{@code POST /api/events}）：
     * {@code items} 严格按 {@code seq} 升序、只给 {@code seq > after} 的；{@code next} 是下次该带的
     * {@code after}（空批时等于请求里的 after）；{@code hasMore} 为 true 时前端接着取。
     *
     * <p>{@code latest} 是服务端高水位（最后发出去的 seq），{@code acked} 是这个 scope 已确认的 seq，
     * {@code trimmedUpTo} 是"已经被裁掉的那一段的末尾"——前端发现 {@code after < trimmedUpTo}
     * 就说明自己落后太多，应当丢弃本地增量、重新全量对账。
     */
    public JsonObject fetch(String scope, long after, int limit) {
        int wanted = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        long from = Math.max(0, after);
        synchronized (lock) {
            ScopeState state = state(scope);
            JsonArray items = new JsonArray();
            long next = from;
            boolean hasMore = false;
            for (Map.Entry<Long, JsonObject> entry : state.items.entrySet()) {
                if (entry.getKey() <= from) continue;
                if (items.size() >= wanted) { hasMore = true; break; }
                items.add(entry.getValue().deepCopy());
                next = entry.getKey();
            }
            JsonObject result = new JsonObject();
            // scope 原样回显（前端拿它核对"这是不是我那个会话的队列"）。
            result.addProperty("scope", scope == null ? DEFAULT_SCOPE : scope);
            result.add("items", items);
            result.addProperty("next", next);
            result.addProperty("latest", state.nextSeq - 1);
            result.addProperty("hasMore", hasMore);
            result.addProperty("acked", state.acked);
            result.addProperty("trimmedUpTo", state.trimmedUpTo);
            return result;
        }
    }

    /**
     * 确认出队（{@code POST /api/events/ack}）：**只推进、不回退**。
     *
     * <p>ack 是按 scope 存的（多客户端各自一个 scope，互不影响）；比 {@code latest} 还大的 seq 会被
     * 夹到 latest（ack 的意思是"这些我已经拿到了"，不是"以后的我都要"）。
     *
     * @return 这个 scope 当前的 acked（= 本次推进后的值，没推进就是原值）
     */
    public long ack(String scope, long seq) {
        long wanted = Math.max(0, seq);
        synchronized (lock) {
            ScopeState state = state(scope);
            wanted = Math.min(wanted, state.nextSeq - 1);
            if (wanted <= state.acked) return state.acked;      // 只进不退
            state.acked = wanted;
            boolean trimmed = trim(state);
            saveAck(state);
            if (trimmed) save(state);
            return state.acked;
        }
    }

    /** 这个 scope 当前的 acked（只读）。 */
    public long acked(String scope) {
        synchronized (lock) { return state(scope).acked; }
    }

    /** 这个 scope 队列里现在有多少条（测试与排查用）。 */
    public int size(String scope) {
        synchronized (lock) { return state(scope).items.size(); }
    }

    /** 这个 scope 文件里记的 {@code trimmedUpTo}（测试用）。 */
    public long trimmedUpTo(String scope) {
        synchronized (lock) { return state(scope).trimmedUpTo; }
    }

    /** 这个 scope 里某一条事件（按 id 找；没有给 null）。 */
    public JsonObject item(String scope, String id) {
        if (id == null || id.isBlank()) return null;
        synchronized (lock) {
            JsonObject item = state(scope).byId.get(id);
            return item == null ? null : item.deepCopy();
        }
    }

    // ---------------------------------------------------------------- 路径

    /** 某个 scope 的队列文件：{@code <root>/data/webui/<净化 scope>-events.json}。 */
    public static Path fileFor(Path root, String scope) {
        Path base = webuiDir(root);
        Path file = base.resolve(safeScope(scope) + "-events.json").normalize();
        Path parent = file.getParent();
        return base.equals(parent) ? file : base.resolve(DEFAULT_SCOPE + "-events.json");
    }

    /** 某个 scope 的 ack 文件：{@code <root>/data/webui/<净化 scope>-events-ack.json}。 */
    public static Path ackFileFor(Path root, String scope) {
        Path base = webuiDir(root);
        Path file = base.resolve(safeScope(scope) + "-events-ack.json").normalize();
        Path parent = file.getParent();
        return base.equals(parent) ? file : base.resolve(DEFAULT_SCOPE + "-events-ack.json");
    }

    private static Path webuiDir(Path root) {
        return (root == null ? Path.of(".") : root).toAbsolutePath().normalize().resolve("data").resolve("webui");
    }

    /** scope → 文件名安全串：只留 {@code [A-Za-z0-9_.-]}，其余换 {@code _}；上限 {@value #SCOPE_CAP}；空则 default。 */
    static String safeScope(String scope) {
        if (scope == null) return DEFAULT_SCOPE;
        StringBuilder out = new StringBuilder(Math.min(scope.length(), SCOPE_CAP));
        for (int index = 0; index < scope.length() && out.length() < SCOPE_CAP; index++) {
            char value = scope.charAt(index);
            boolean safe = (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9') || value == '_' || value == '.' || value == '-';
            out.append(safe ? value : '_');
        }
        String cleaned = out.toString();
        return cleaned.isEmpty() ? DEFAULT_SCOPE : cleaned;
    }

    // ---------------------------------------------------------------- 内部：内存态与入队细节

    /** 一个 scope 的队列内存态（首次用到时从磁盘读回来）。 */
    private static final class ScopeState {
        final String safe;
        /** 下一个要发的 seq（严格递增；latest = nextSeq - 1）。 */
        long nextSeq = 1;
        /** 这个 scope 已确认到哪一条（只进不退）。 */
        long acked;
        /** 已经被裁掉的那一段的末尾 seq（前端落后太多时靠它决定重新对账）。 */
        long trimmedUpTo;
        /** seq → 条目（插入顺序 = seq 顺序）。 */
        final LinkedHashMap<Long, JsonObject> items = new LinkedHashMap<>();
        /** id → 条目（{@link #messageHasImage} 与按下标取用；条目不多，多这一份索引换来 O(1)）。 */
        final LinkedHashMap<String, JsonObject> byId = new LinkedHashMap<>();
        /** 队列里现有条目的 id（幂等判定的第一层）。 */
        final LinkedHashSet<String> ids = new LinkedHashSet<>();
        /** 已经被裁掉的 id（幂等判定的第二层：裁掉之后同一条回执重放不会又冒出来）。 */
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        ScopeState(String safe) { this.safe = safe; }
    }

    /** 取（必要时从磁盘读回）一个 scope 的内存态。<b>调用方必须持有 {@link #lock}</b>。 */
    private ScopeState state(String scope) {
        String safe = safeScope(scope);
        ScopeState state = scopes.get(safe);
        if (state != null) return state;
        synchronized (lock) {
            state = scopes.get(safe);
            if (state == null) {
                state = load(safe);
                scopes.put(safe, state);
            }
            return state;
        }
    }

    /** 从磁盘读回一个 scope（文件不存在 = 空队列；坏数据只 warn，按空队列继续）。 */
    private ScopeState load(String safe) {
        ScopeState state = new ScopeState(safe);
        try {
            Path file = fileFor(root, safe);
            if (Files.isRegularFile(file)) {
                JsonObject stored = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                state.nextSeq = Math.max(1, Json.num(stored, "nextSeq", 1L));
                state.trimmedUpTo = Math.max(0, Json.num(stored, "trimmedUpTo", 0L));
                long latest = 0;
                if (stored.has("items") && stored.get("items").isJsonArray()) {
                    for (JsonElement node : stored.getAsJsonArray("items")) {
                        if (node == null || !node.isJsonObject()) continue;
                        JsonObject item = ordered(node.getAsJsonObject());
                        long seq = Json.num(item, "seq", 0L);
                        String id = text(item, "id");
                        if (seq <= 0 || id.isBlank()) continue;
                        state.items.put(seq, item);
                        state.byId.put(id, item);
                        state.ids.add(id);
                        latest = Math.max(latest, seq);
                    }
                }
                if (stored.has("seen") && stored.get("seen").isJsonArray()) {
                    for (JsonElement node : stored.getAsJsonArray("seen")) {
                        if (node != null && node.isJsonPrimitive()) state.seen.add(node.getAsString());
                    }
                }
                // 文件被手工改过时也绝不会撞号：nextSeq 一定大于所有已发出的 seq。
                state.nextSeq = Math.max(state.nextSeq, latest + 1);
            }
            Path ack = ackFileFor(root, safe);
            if (Files.isRegularFile(ack)) {
                state.acked = Math.max(0, Json.num(Json.parse(Files.readString(ack, StandardCharsets.UTF_8)), "acked", 0L));
            }
        } catch (Exception error) {
            Log.warn("WebUI 事件队列读取失败，按空队列继续：" + safe, error);
        }
        return state;
    }

    /** 入队一条（调用方持锁）：{@link #enqueueLocked} + 裁剪 + 落盘。 */
    private boolean appendLocked(ScopeState state, JsonObject item) {
        if (!enqueueLocked(state, item)) return false;
        trim(state);
        save(state);
        return true;
    }

    /** 只改内存的入队（调用方持锁）：id 幂等 + 发号。批量走它的调用方自己收口一次裁剪与落盘。 */
    private boolean enqueueLocked(ScopeState state, JsonObject item) {
        String id = text(item, "id");
        String type = text(item, "type");
        if (id.isBlank() || type.isBlank()) return false;
        if (state.ids.contains(id) || state.seen.contains(id)) return false;
        JsonObject stored = item.deepCopy();
        stored.addProperty("seq", state.nextSeq);
        if (!stored.has("at") || stored.get("at").isJsonNull()) stored.addProperty("at", System.currentTimeMillis());
        JsonObject ordered = ordered(stored);
        state.items.put(state.nextSeq, ordered);
        state.byId.put(id, ordered);
        state.ids.add(id);
        state.nextSeq++;
        return true;
    }

    /**
     * 图片补丁（调用方持锁）：这条 message 已经带着这张图（同时到的）就什么都不做，
     * 否则发一条 patch。id 稳定 → 同一条回执重放多少次都只有一条。
     */
    private boolean enqueueImageLocked(ScopeState state, String target, String image) {
        if (target == null || target.isBlank() || image == null || image.isBlank()) return false;
        if (messageHasImage(state, target, image)) return false;
        return enqueueLocked(state, patch(target, image));
    }

    /** 这条 message 是不是已经自带这张图（自带就别再发 patch，同一张图绝不重复入队）。 */
    private static boolean messageHasImage(ScopeState state, String id, String image) {
        JsonObject item = state.byId.get(id);
        return item != null && contains(imagesOf(item), image);
    }

    private static JsonArray imagesOf(JsonObject item) {
        return item.has("images") && item.get("images").isJsonArray() ? item.getAsJsonArray("images") : new JsonArray();
    }

    /**
     * 滚动裁剪（调用方持锁）：只有**超过 {@value #TRIM_THRESHOLD} 条**时才动，而且只从最旧的一端
     * 删**连续的已 ack 前缀** —— 未 ack 的一条都不删（队头那条还没 ack 就一条都不裁）。
     *
     * @return 真的裁掉了东西
     */
    private boolean trim(ScopeState state) {
        boolean changed = false;
        while (state.items.size() > TRIM_THRESHOLD) {
            Map.Entry<Long, JsonObject> head = state.items.entrySet().iterator().next();
            if (head.getKey() > state.acked) break;                 // 未 ack 的绝不删
            String id = Json.str(head.getValue(), "id", "");
            state.items.remove(head.getKey());
            state.byId.remove(id);
            state.ids.remove(id);
            if (!id.isBlank()) {
                state.seen.add(id);
                while (state.seen.size() > SEEN_CAP) state.seen.remove(state.seen.iterator().next());
            }
            state.trimmedUpTo = Math.max(state.trimmedUpTo, head.getKey());
            changed = true;
        }
        return changed;
    }

    /** 条目字段顺序统一：seq / id / type / at 打头，其余照原样跟在后面。 */
    private static JsonObject ordered(JsonObject raw) {
        JsonObject out = new JsonObject();
        out.addProperty("seq", Json.num(raw, "seq", 0L));
        out.addProperty("id", text(raw, "id"));
        out.addProperty("type", text(raw, "type"));
        out.addProperty("at", Json.num(raw, "at", System.currentTimeMillis()));
        for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (key.equals("seq") || key.equals("id") || key.equals("type") || key.equals("at")) continue;
            out.add(key, entry.getValue());
        }
        return out;
    }

    // ---------------------------------------------------------------- 内部：落盘

    /** 写整条队列（原子写、UTF-8 无 BOM、纯 LF）；写失败只 warn。 */
    private void save(ScopeState state) {
        try {
            JsonObject file = new JsonObject();
            file.addProperty("version", 1);
            file.addProperty("scope", state.safe);
            file.addProperty("nextSeq", state.nextSeq);
            file.addProperty("trimmedUpTo", state.trimmedUpTo);
            file.addProperty("savedAtMillis", System.currentTimeMillis());
            JsonArray seen = new JsonArray();
            for (String id : state.seen) seen.add(id);
            file.add("seen", seen);
            JsonArray items = new JsonArray();
            for (JsonObject item : state.items.values()) items.add(item);
            file.add("items", items);
            Json.atomicWriteText(fileFor(root, state.safe), Json.GSON.toJson(file) + "\n");
        } catch (Exception error) {
            Log.warn("WebUI 事件队列写入失败（队列内容仍在本进程内存里）：" + error, error);
        }
    }

    /** 写 ack（单独一份小文件：ack 频率高，不该重写整条队列）。 */
    private void saveAck(ScopeState state) {
        try {
            JsonObject file = new JsonObject();
            file.addProperty("version", 1);
            file.addProperty("scope", state.safe);
            file.addProperty("acked", state.acked);
            file.addProperty("savedAtMillis", System.currentTimeMillis());
            Json.atomicWriteText(ackFileFor(root, state.safe), Json.GSON.toJson(file) + "\n");
        } catch (Exception error) {
            Log.warn("WebUI 事件队列 ack 写入失败（重启后可能重放已确认的那一段）：" + error, error);
        }
    }

    private static int parseNumber(String number) {
        try { return Integer.parseInt(number == null ? "" : number.strip()); }
        catch (NumberFormatException ignored) { return 0; }
    }

    /** 取一个字符串字段：不是基本类型就给空串（**绝不抛** —— 队列不接受"分不清身份"的输入，也不为它崩）。 */
    static String text(JsonObject item, String key) {
        JsonElement node = item.get(key);
        if (node == null || !node.isJsonPrimitive()) return "";
        try { return node.getAsString(); } catch (RuntimeException ignored) { return ""; }
    }
}

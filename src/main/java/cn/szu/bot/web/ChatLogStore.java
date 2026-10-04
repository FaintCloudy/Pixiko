package cn.szu.bot.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import cn.szu.bot.Json;
import cn.szu.bot.Log;

/**
 * 对话页（控制台 {@code #chat-log}）的**完整正文**落盘：文字、图片路径、指令回执条目都在这里，
 * 与只给模型看的纯文字历史（{@code data/webui/<scope>-chat.json}，{@code WebApiController} 管）
 * 是两份互不影响的文件。
 *
 * <p>为什么要有它：纯文字历史里没有图片路径、也没有回执条目，浏览器 sessionStorage 里的渲染快照
 * 一换浏览器/一清缓存就没了，「切一下栏目回来回执和图全消失」的根因就在这里。正文落盘之后，
 * 打开对话页以**服务端这份为准**，本地快照只当秒开的缓存。
 *
 * <p>文件：{@code <root>/data/webui/<净化 scope>-chat-log.json}，UTF-8 无 BOM、纯 LF，
 * 结构 {@code {"version":1,"scope":"…","savedAtMillis":<long>,"entries":[…]}}。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li><b>绝不抛</b>：{@link #load} 读不出来给空数组（文件不存在静默、坏数据 warn），{@link #save} 写不进去只 warn —— 聊天照常；</li>
 *   <li><b>绝不半截</b>：写入走 {@link Json} 的「临时文件 + 原子替换 + 重试」，同一 scope 的写串行；</li>
 *   <li><b>绝不逃出目录</b>：scope 先净化成文件名安全字符，{@link #fileFor} 再兜一层包含性检查。</li>
 * </ul>
 *
 * <p>条目规范化与上限和前端 {@code app.js} 的 {@code chatEntryClean}/{@code chatSnapshotTrim} 同规则
 * （200 条、512KB、单条文字 20000 字、图片最多 60 张）。
 */
public final class ChatLogStore {
    /** 条目只留最近这么多条（与前端 {@code CHAT_LOG_LIMIT} 同值）。 */
    private static final int ENTRIES_CAP = 200;
    /** 序列化总长上限（与前端 {@code CHAT_LOG_BYTES} 同值）。 */
    private static final int BYTES_CAP = 512 * 1024;
    /** 单条文字上限：保留开头这么多字符，再加一行省略提示。 */
    private static final int TEXT_CAP = 20000;
    /** 单条最多这么多张图。 */
    private static final int IMAGES_CAP = 60;
    /** 单个图片路径上限（超出截断）。 */
    private static final int IMAGE_LENGTH_CAP = 1024;
    /** scope 净化后的长度上限。 */
    private static final int SCOPE_CAP = 64;
    /** 净化后为空的 scope 落在这个文件名上。 */
    private static final String DEFAULT_SCOPE = "default";
    /** 字节裁剪时给外层 {"version","scope","savedAtMillis"} 留的余量（估算用，宁多勿少）。 */
    private static final long STATE_OVERHEAD = 256L;

    private final Path root;
    /** 读/写都串行：写入很小、次数很少（一次对话落一次盘），所以不做按 scope 分锁，一个监视器足够。 */
    private final Object lock = new Object();

    /** @param root 机器人根目录（{@code settings.root}），正文落在 {@code <root>/data/webui/} 下。 */
    public ChatLogStore(Path root) { this.root = base(root); }

    /** 条目条数上限（测试引用）。 */
    public int cap() { return ENTRIES_CAP; }

    /** 序列化总长上限，单位字节（测试引用）。 */
    public int bytesCap() { return BYTES_CAP; }

    /**
     * 某个 scope 的正文文件路径。scope 先净化（只留 {@code [A-Za-z0-9_.-]}，其余换 {@code _}，
     * 上限 64，空则 {@code default}），再拼 {@code -chat-log.json}，最后兜一层「父目录必须正好是
     * data/webui」的检查 —— 所以 {@code "../../evil"} 这类输入也出不了 {@code data/webui}。
     */
    public static Path fileFor(Path root, String scope) {
        Path base = webuiDir(root);
        Path file = base.resolve(safeScope(scope) + "-chat-log.json").normalize();
        Path parent = file.getParent();
        return base.equals(parent) ? file : base.resolve(DEFAULT_SCOPE + "-chat-log.json");
    }

    /**
     * 读一份完整正文。<b>绝不抛</b>：给不出内容时一律空数组，调用方按「这段对话还没记录」处理即可。
     *
     * <p>**文件不存在是正常的**（新会话第一次打开对话栏），静默给空数组、不打日志；坏 JSON、
     * 结构不对（没有 entries / 不是数组）、读失败这些才是真异常，各 warn 一行。
     *
     * <p>成功时返回 {@code entries} 数组的**深拷贝**，改返回值不会影响下一次 load。
     */
    public JsonArray load(String scope) {
        Path file = fileFor(root, scope);
        synchronized (lock) {
            try {
                // 只有"这个文件根本不在"才静默；路径被占成目录之类会走到下面的读失败分支去 warn。
                if (!Files.exists(file)) return new JsonArray();
                JsonObject state = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                JsonElement entries = state.get("entries");
                if (entries == null || !entries.isJsonArray()) {
                    Log.warn("WebUI 对话正文里没有 entries 数组，按空对话继续：" + file.getFileName());
                    return new JsonArray();
                }
                return entries.getAsJsonArray().deepCopy();
            } catch (Exception error) {
                Log.warn("WebUI 对话正文读取失败，按空对话继续：" + file.getFileName(), error);
                return new JsonArray();
            }
        }
    }

    /**
     * 写一份完整正文。<b>绝不抛</b>（失败只 warn 一行，这条对话这一轮不入库，不影响聊天）。
     *
     * <p>逐条规范化：只留「是 JSON 对象、且至少有文字或有图」的条目（字符串/数字/null 这些、以及
     * 既没文字也没图的空气泡整条丢掉，而不是整份拒收）；
     * {@code role} 只认 {@code user}/{@code bot}/{@code sys}，其余按 {@code bot}；{@code text} 一律转字符串
     * （缺失或非基本类型＝空串），超过 {@value #TEXT_CAP} 字保留开头并加一行省略提示；{@code images}
     * 只认字符串元素（非字符串与空串丢掉），最多 {@value #IMAGES_CAP} 个、单个超 {@value #IMAGE_LENGTH_CAP}
     * 字截断，空数组不落字段（与前端条目形状一致）。
     *
     * <p>体积：只留最后 {@value #ENTRIES_CAP} 条，再按整份序列化长度从最旧的开始丢到
     * ≤ {@value #BYTES_CAP} 字节（至少留 1 条，别把唯一一条超长消息也丢了）。
     *
     * <p>并发：整段 serialize+trim+写盘都在 {@link #lock} 里，同一 scope 的写严格串行；文件本身用
     * {@link Json} 的原子写（临时文件 + ATOMIC_MOVE + 重试），读者永远看不到半截内容。
     */
    public void save(String scope, JsonArray entries) {
        synchronized (lock) {
            try {
                String safe = safeScope(scope);
                JsonArray kept = trim(normalize(entries));
                JsonObject state = state(safe, kept);
                // 上面的逐条估算是保守的，这里用整份真实长度收口：真超了就从最旧的接着丢。
                while (kept.size() > 1 && serializedBytes(state) > BYTES_CAP) {
                    kept.remove(0);
                    state = state(safe, kept);
                }
                // 用 atomicWrite 的文本版：对象版的收尾换行是 System.lineSeparator()（Windows 上就是 CRLF），
                // 而这份文件要求纯 LF，所以自己拼一个 "\n" 交给同一套原子写机制。
                Json.atomicWriteText(fileFor(root, safe), Json.GSON.toJson(state) + "\n");
            } catch (Exception error) {
                Log.warn("WebUI 对话正文写入失败，这一轮不入库：" + error, error);
            }
        }
    }

    // ---------------------------------------------------------------- 内部：路径与净化

    private static Path base(Path root) { return root == null ? Path.of(".") : root; }

    private static Path webuiDir(Path root) {
        return base(root).toAbsolutePath().normalize().resolve("data").resolve("webui");
    }

    /** scope → 文件名安全串：只留 {@code [A-Za-z0-9_.-]}，其余换 {@code _}；上限 {@value #SCOPE_CAP}；空则 default。 */
    private static String safeScope(String scope) {
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

    // ---------------------------------------------------------------- 内部：规范化与裁剪

    private static JsonObject state(String scope, JsonArray entries) {
        JsonObject state = new JsonObject();
        state.addProperty("version", 1);
        state.addProperty("scope", scope);
        state.addProperty("savedAtMillis", System.currentTimeMillis());
        state.add("entries", entries);
        return state;
    }

    /** 逐条规范化，只丢坏条目（非对象、或既没文字也没图的空条目），其余照留。 */
    private static JsonArray normalize(JsonArray raw) {
        JsonArray cleaned = new JsonArray();
        if (raw == null) return cleaned;
        for (JsonElement element : raw) {
            JsonObject entry = normalizeEntry(element);
            if (entry != null) cleaned.add(entry);
        }
        return cleaned;
    }

    /**
     * 一条条目 → 规范形状；返回 null 表示整条丢掉：非对象，或者既没有文字也没有图
     * （前端 {@code chatEntryClean} 同规则：空条目画出来就是一条空气泡）。字段顺序固定 role、text、images。
     */
    private static JsonObject normalizeEntry(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) return null;
        JsonObject source = raw.getAsJsonObject();
        JsonObject entry = new JsonObject();
        String role = asText(source.get("role"));
        entry.addProperty("role", "user".equals(role) || "sys".equals(role) ? role : "bot");
        String text = clipText(asText(source.get("text")));
        JsonArray images = normalizeImages(source.get("images"));
        if (text.isEmpty() && images.size() == 0) return null;
        entry.addProperty("text", text);
        if (images.size() > 0) entry.add("images", images);
        return entry;
    }

    /** 基本类型转字符串，缺失/null/对象/数组一律空串。 */
    private static String asText(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return "";
        try { return element.getAsString(); } catch (Exception error) { return ""; }
    }

    /** 超长文字保留开头 {@value #TEXT_CAP} 字，再补一行省略提示（尾部信息量最低，先丢）。 */
    private static String clipText(String text) {
        if (text.length() <= TEXT_CAP) return text;
        return text.substring(0, TEXT_CAP) + "\n…（本条共 " + text.length() + " 字，已截断，尾部未保存）";
    }

    /** 只留字符串元素（非字符串、空串丢掉），最多 {@value #IMAGES_CAP} 个，单个超 {@value #IMAGE_LENGTH_CAP} 字截断。 */
    private static JsonArray normalizeImages(JsonElement element) {
        JsonArray images = new JsonArray();
        if (element == null || !element.isJsonArray()) return images;
        for (JsonElement image : element.getAsJsonArray()) {
            if (images.size() >= IMAGES_CAP) break;
            if (image == null || !image.isJsonPrimitive() || !((JsonPrimitive) image).isString()) continue;
            String value = image.getAsString();
            if (value.isEmpty()) continue;
            images.add(value.length() > IMAGE_LENGTH_CAP ? value.substring(0, IMAGE_LENGTH_CAP) : value);
        }
        return images;
    }

    /** 只留最后 {@value #ENTRIES_CAP} 条；再按逐条字节数估算，从最旧的丢到估算长度进得去（真实长度由 save 收口）。 */
    private static JsonArray trim(JsonArray entries) {
        JsonArray kept = new JsonArray();
        for (int index = Math.max(0, entries.size() - ENTRIES_CAP); index < entries.size(); index++) kept.add(entries.get(index));
        if (kept.size() <= 1) return kept;
        long[] sizes = new long[kept.size()];
        long total = STATE_OVERHEAD;
        for (int index = 0; index < kept.size(); index++) {
            String json = Json.GSON.toJson(kept.get(index));
            int lines = 1;
            for (int at = 0; at < json.length(); at++) if (json.charAt(at) == '\n') lines++;
            // 单独序列化时没有数组缩进，进数组后每行多 4 个空格：宁多算，别让真实长度超出去。
            sizes[index] = json.getBytes(StandardCharsets.UTF_8).length + 4L * lines + 8L;
            total += sizes[index];
        }
        int first = 0;
        while (first < kept.size() - 1 && total > BYTES_CAP) total -= sizes[first++];
        JsonArray result = new JsonArray();
        for (int index = first; index < kept.size(); index++) result.add(kept.get(index));
        return result;
    }

    private static long serializedBytes(JsonElement element) {
        return Json.GSON.toJson(element).getBytes(StandardCharsets.UTF_8).length;
    }
}

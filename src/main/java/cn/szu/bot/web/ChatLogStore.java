package cn.szu.bot.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>两个写入者：{@link #save}（页面整组 push）与 {@link #append}（服务端把回执消息顺手落一份）。
 * 后者把幂等键（回执号 + 条内序号）记在同目录的 {@code <scope>-chat-log-appended.json} 里，
 * 所以**正文文件的结构一个字都没变**，页面那份 push 也照旧能用。
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
    /**
     * 幂等账本最多留这么多条键：比正文的 200 条窗口宽得多，所以窗口里还留着的条目，它的键一定还在账本里。
     * 超出就从最旧的开始丢（只可能让某条**已经滚出窗口又被重新追加**的极老回执多写一次，不会丢内容）。
     */
    private static final int LEDGER_CAP = 1024;

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
                write(safe, normalize(entries));
                recoverAppended(safe);
            } catch (Exception error) {
                Log.warn("WebUI 对话正文写入失败，这一轮不入库：" + error, error);
            }
        }
    }

    /**
     * 清空这个 scope 的正文（{@code /api/chat/reset} 用）：正文与**幂等账本一起清**。
     *
     * <p>为什么不能直接用 {@link #save} 写空数组：{@link #save} 之后会跑"把服务端 append 过、
     * 却被这次 push 覆盖掉的条目补回来"（{@link #recoverAppended}）——那是给"页面整组覆盖写"用的防护，
     * 用在"用户明确要清空"上就成了"清了又回来"。清空是明确语义，两样一起清才是对的行为。
     *
     * <p>绝不抛：清不掉只 warn（聊天与回执照常）。
     */
    public void clear(String scope) {
        synchronized (lock) {
            try {
                String safe = safeScope(scope);
                write(safe, new JsonArray());
                Files.deleteIfExists(ledgerFileFor(root, safe));
            } catch (Exception error) {
                Log.warn("WebUI 对话正文清空失败：" + error, error);
            }
        }
    }

    /**
     * 页面整组 push 之后的补救：**只把"日志末尾之后缺的那几条"补回来**（{@code /api/chat/log/save} 是整组覆盖写）。
     *
     * <p>为什么需要：服务端在两次 push 之间也会 append（回执消息、图片）。页面那一份是"上一次读到之后"的
     * 快照时，覆盖回去就会把服务端刚 append 的那几条抹掉。页面侧已经做了并集合并（见 {@code app.js} 的
     * {@code chatSaveLog}），这里是**服务端自己的第二道**，而且是**逐字节确定**的一对一比对，
     * 不靠下标、不靠正文前缀。
     *
     * <p><b>判据（关键，收紧过一次）</b>：账本（= 服务端追加顺序）里**最后一条还在推上来的正文里的**
     * 条目的下标记作 {@code lastMatchedAt}；只有它**之后**的账本条目才算"这次 push 抹掉的"，
     * 按追加顺序补回末尾。一条都对不上（{@code lastMatchedAt < 0}，页面把服务端追加过的条目全删了）
     * → 一条都不补。比对逐条用 {@link #hasSame}（完整形状、字段顺序统一，不靠下标、不靠前缀）。
     *
     * <p>这一条判据同时满足两个硬要求（它们是互相拉扯的，别只往一边收）：
     * <ul>
     *   <li><b>用户删掉的不复活</b>：页面删的是"中间/全部"。删中间时末尾那条追加条目还在，
     *       删掉的那些下标落在 {@code lastMatchedAt} 之前，一律不补；全删时一条都对不上
     *       （{@code lastMatchedAt < 0}），直接一条不补。老写法"正文里没有就补回末尾"正是把
     *       用户删掉的历史塞回对话末尾的元凶；</li>
     *   <li><b>真丢的补得回来</b>：页面读到服务端那份之后又 append 了新条目（并集之后、push 之前
     *       那一瞬间），页面手里那份就是"少了末尾 N 条"—— 末尾之前那条对得上，于是这 N 条被补回。</li>
     * </ul>
     *
     * <p>只补不删：页面自己的增删语义一个字没变（用户清空整段对话走 {@code /api/chat/reset}，
     * 那时账本也一起重来）。
     */
    private void recoverAppended(String safe) throws Exception {
        if (!Files.exists(ledgerFileFor(root, safe))) return;      // 这个 scope 从没追加过：连账本都不用读
        Map<String, Saved> keys = loadLedger(safe);
        if (keys.isEmpty()) return;
        JsonArray entries = load(safe);

        List<JsonObject> ledger = new ArrayList<>();          // 账本 = 服务端追加顺序（重复正文按出现次数各算一条）
        for (Saved saved : keys.values()) {
            JsonObject want = parseEntry(saved.entry());
            if (want != null) ledger.add(want);
        }
        if (ledger.isEmpty()) return;

        /* 账本里"最后一条还在推上来的正文里的"下标：它之后的账本条目才是"被这次 push 抹掉的尾巴"。
           一条都对不上 = 页面把服务端追加过的条目**全删了**（或整份换成了别的内容）→ 一条都不补。 */
        int lastMatchedAt = -1;
        for (int index = 0; index < ledger.size(); index++) {
            if (hasSame(entries, ledger.get(index))) lastMatchedAt = index;
        }
        if (lastMatchedAt < 0) return;
        List<JsonObject> lost = new ArrayList<>();
        for (int index = lastMatchedAt + 1; index < ledger.size(); index++) {
            JsonObject want = ledger.get(index);
            if (hasSame(entries, want)) continue;             // 正文别处已经有了这一条（窗口里还在）：不补
            lost.add(want);
        }
        if (lost.isEmpty()) return;
        for (JsonObject entry : lost) entries.add(entry);      // 按账本（= 追加）顺序补回末尾
        write(safe, entries);
        Log.info("WebUI 对话正文：页面整组 push 抹掉了末尾 " + lost.size() + " 条服务端追加过的条目，已按序补回（" + safe + "）。");
    }

    /** 这一条正文条目与账本里那一条是不是**同一形状**（role + 文字 + 图片，逐字节）。 */
    private static boolean sameShape(JsonElement entry, JsonObject wanted) {
        if (entry == null || !entry.isJsonObject()) return false;
        return Json.GSON.toJson(normalizeEntry(entry)).equals(Json.GSON.toJson(normalizeEntry(wanted)));
    }

    /**
     * 正文里还有没有"这一形状"的条目（{@link #sameShape} 的数组版）——形状比对只看
     * role + 文字 + 图片，字段顺序与裁剪口径先统一，所以"同一形状"就是"逐字节一样的那一条"。
     */
    private static boolean hasSame(JsonArray entries, JsonObject wanted) {
        for (int index = 0; index < entries.size(); index++) {
            if (sameShape(entries.get(index), wanted)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 追加（服务端权威写入之一）

    /**
     * 一条要落进对话正文的回执条目：{@code key} 是**幂等键**（{@code "quest:<回执号>:<条内序号>"}），
     * {@code text} 是这一条的正文字、{@code images} 是这一条的图片路径（可空）。
     *
     * <p>键是**稳定**的：同一条回执反复推导出来的第 N 条正文，键永远是 {@code quest:N:<条内序号>}，
     * 所以"图后到"时可以把同一条正文更新成带图的版本（{@link #append} 会就地替换，不会多出一个气泡）。
     */
    public record Append(String key, String text, List<String> images) { }

    /**
     * 服务端把回执消息**追加**进这个 scope 的对话正文（回执/图片的权威写入）。
     *
     * <p><b>为什么有它</b>：正文以前只由页面整组 push，回执消息只存在 {@code data/quests/*.json}，
     * 于是前端必须自己把两路"缝"起来 —— 缝错了就是「多条消息并成一个气泡」「回执重复」。
     * 现在服务端产生回执消息时顺手落一份，页面只读一个源就够。
     *
     * <p><b>幂等键 = 回执号 + 条内序号</b>（{@link Append#key()}）。同一批条目调多少次都只落一次：
     * 记在旁挂的 {@code <scope>-chat-log-appended.json} 里（**正文文件的结构一个字都没改**），
     * 所以机器人重启、页面补拉、重试都不会重复。
     *
     * <p>同一条目再来（例如图片后到、正文更长）时**就地更新那一条**而不是再 append 一条 ——
     * 这正是"先文字后图仍是同一个气泡"的服务端一半。
     *
     * <p><b>只追加、不删除</b>：只有跟 {@link #save} 一样的 200 条 / 512KB 滚动上限会把最旧的挤出窗口。
     * 与 {@link #save}（页面整组 push）共用一个监视器：append 是"读-改-写"，push 的整组覆盖发生在
     * 两次 append 之间时，下一次 append 会在**新内容**上继续追加，绝不回退到旧内容。
     *
     * <p><b>绝不抛</b>（和 {@link #save} 同规矩）：写不进去只 warn，聊天与回执照常。
     *
     * @return 真正改动了正文的条目数（0 = 全都已经落过、且一个字都没变）
     */
    public int append(String scope, List<Append> items) {
        if (items == null || items.isEmpty()) return 0;
        synchronized (lock) {
            try {
                String safe = safeScope(scope);
                JsonArray entries = load(safe);
                Map<String, Saved> keys = loadLedger(safe);
                int changed = 0;
                for (Append item : items) {
                    if (item == null) continue;
                    String key = item.key() == null ? "" : item.key();
                    String text = clipText(item.text() == null ? "" : item.text());
                    JsonArray images = normalizeImages(Json.GSON.toJsonTree(item.images() == null ? List.of() : item.images()));
                    if (text.isEmpty() && images.size() == 0) continue;
                    JsonObject fresh = entry(text, images);
                    Saved saved = key.isEmpty() ? null : keys.get(key);
                    if (saved != null) {
                        // 这一条已经落过：内容一样就什么都不做（幂等）；多了图/正文变长就**就地更新**。
                        int at = indexOfSame(entries, fresh);
                        if (at < 0) at = indexOfText(entries, saved.text());   // 窗口滚动后按账本当时的正文找回
                        if (at < 0) continue;                                  // 条目已经被 200 条窗口挤掉：不再补
                        if (sameEntry(entries.get(at).getAsJsonObject(), text, images)) continue;
                        entries.set(at, fresh);
                        keys.put(key, new Saved(key, text, Json.GSON.toJson(fresh)));
                        changed++;
                        continue;
                    }
                    // 这一条还没落过账。默认**直接追加**：幂等的唯一依据是"回执号 + 条内序号"这个键，
                    // 绝不按正文去重 —— 同一句话本来就可能出现两次（连点两次 .help 就是两条消息）。
                    //
                    // 唯一的例外：正文里已有一条**同一段正文、还没有图**的条目（页面整组 push 上来的那份），
                    // 而这次带图 —— 那就把图补进那一条（"先文字后图仍是同一个气泡"），不新开。
                    int existed = images.size() > 0 ? indexOfBareText(entries, text) : -1;
                    if (existed >= 0) {
                        entries.set(existed, fresh);
                        if (!key.isEmpty()) keys.put(key, new Saved(key, text, Json.GSON.toJson(fresh)));
                        changed++;
                        continue;
                    }
                    entries.add(fresh);
                    if (!key.isEmpty()) keys.put(key, new Saved(key, text, Json.GSON.toJson(fresh)));
                    changed++;
                }
                if (changed == 0) {
                    // 只认了账（没动正文）：也把账本落一次，免得下次再走一遍"正文里已经有了"的查找。
                    if (!keys.isEmpty()) writeLedger(safe, keys);
                    return 0;
                }
                write(safe, entries);
                writeLedger(safe, keys);
                return changed;
            } catch (Exception error) {
                Log.warn("WebUI 对话正文追加失败，这一条不入库（回执本身不受影响）：" + error, error);
                return 0;
            }
        }
    }

    /**
     * 把一条回执的**全部**出站消息（{@code messages}：{@code [[{type,text}|{type,file}],…]}，与
     * {@code data/quests/<n>.json} 里的 {@code messages} 同形）推导成要落的条目。
     *
     * <p>每次都用**整份消息**重新推导（不是只算新增的那几条）：图片可能比它前面那条正文晚到，
     * 只有从整份重推才能把图并回同一条正文上。条目带**稳定键**，{@link #append} 靠它做到不重复。
     *
     * <p>分组规则与前端 {@code mergeReceiptIntoChat} 冻结的口径一致：
     * <ul>
     *   <li>每个**带正文的消息**各占一条（条内序号 1、2、3…）；</li>
     *   <li>**图片并进上一条正文**（"先文字后图"仍是同一个气泡，绝不为了图新开一个气泡）；</li>
     *   <li>整条回执一条正文都还没有时，图片先各占一条只有图的条目（有正文之后它会被并进那条正文）。</li>
     * </ul>
     *
     * @param number 回执号（任务号）
     */
    public static List<Append> receipt(String number, List<JsonArray> messages) {
        // key → 这一条现在的样子（text + 它名下的图），LinkedHashMap 保持条目顺序稳定。
        Map<String, Append> out = new LinkedHashMap<>();
        if (messages == null) return new ArrayList<>();
        int seq = 0;
        String lastText = null;                 // 最近一条带正文的条目键（图片的落点）
        for (int index = 0; index < messages.size(); index++) {
            JsonArray group = messages.get(index);
            String text = "";
            List<String> images = new ArrayList<>();
            if (group != null) {
                for (JsonElement node : group) {
                    if (node == null || !node.isJsonObject()) continue;
                    JsonObject item = node.getAsJsonObject();
                    String type = Json.str(item, "type", "");
                    if ("text".equals(type)) {
                        String value = Json.str(item, "text", "");
                        if (!value.isBlank()) text = text.isEmpty() ? value : text + "\n" + value;
                    } else if ("image".equals(type)) {
                        String file = Json.str(item, "file", "");
                        if (!file.isBlank() && !images.contains(file)) images.add(file);
                    }
                }
            }
            if (!text.isBlank()) {
                seq++;
                String key = "quest:" + number + ":" + seq;
                out.put(key, new Append(key, text, images));
                lastText = key;
            } else if (!images.isEmpty()) {
                if (lastText != null) {
                    // 图并进上一条正文：同一个气泡（"先文字后图"），不新开条目。
                    Append target = out.get(lastText);
                    List<String> merged = new ArrayList<>(target.images());
                    for (String file : images) if (!merged.contains(file)) merged.add(file);
                    out.put(lastText, new Append(lastText, target.text(), merged));
                } else {
                    String key = "quest:" + number + ":img:" + index;
                    Append target = out.get(key);
                    List<String> merged = target == null ? new ArrayList<>() : new ArrayList<>(target.images());
                    for (String file : images) if (!merged.contains(file)) merged.add(file);
                    out.put(key, new Append(key, "", merged));
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    // ---------------------------------------------------------------- 内部：写盘与幂等账本

    /**
     * 写一份正文（调用方必须已经持有 {@link #lock}）：先按 200 条裁剪，再按整份序列化长度从最旧的丢到
     * ≤ {@value #BYTES_CAP} 字节（至少留 1 条，别把唯一一条超长消息也丢了）。
     *
     * <p>用 {@link Json#atomicWriteText} 的文本版：对象版的收尾换行是 {@code System.lineSeparator()}
     * （Windows 上就是 CRLF），而这份文件要求纯 LF，所以自己拼一个 {@code "\n"} 交给同一套原子写机制。
     */
    private void write(String safe, JsonArray normalized) throws Exception {
        JsonArray kept = trim(normalized);
        JsonObject state = state(safe, kept);
        // 上面的逐条估算是保守的，这里用整份真实长度收口：真超了就从最旧的接着丢。
        while (kept.size() > 1 && serializedBytes(state) > BYTES_CAP) {
            kept.remove(0);
            state = state(safe, kept);
        }
        Json.atomicWriteText(fileFor(root, safe), Json.GSON.toJson(state) + "\n");
    }

    /**
     * 幂等账本的落点：与正文**同目录、同 scope 前缀**的旁挂文件。
     *
     * <p>为什么不塞进正文 JSON：正文的结构是冻结的（{@code version/scope/savedAtMillis/entries}），
     * 而页面 {@code /api/chat/log/save} 是**整组覆盖写**——账本放在同一份文件里会被它顺手抹掉。
     * 旁挂文件与页面完全无关，页面怎么写都动不到它。
     */
    public static Path ledgerFileFor(Path root, String scope) {
        Path base = webuiDir(root);
        Path file = base.resolve(safeScope(scope) + "-chat-log-appended.json").normalize();
        Path parent = file.getParent();
        return base.equals(parent) ? file : base.resolve(DEFAULT_SCOPE + "-chat-log-appended.json");
    }

    /**
     * 账本里记住的一条：幂等键 {@code key}（回执号 + 条内序号）、它当时的正文 {@code text}
     * （只给人看/排查）、以及它落进正文时**完整形状**的 JSON {@code entry}
     * （{@link #recoverAppended} 靠它逐字节确定"这一条还在不在"）。
     */
    private record Saved(String key, String text, String entry) { }

    /**
     * 读幂等账本（**调用方持锁**）：给不出内容就给空表 —— 文件不存在是正常的
     * （这个 scope 还没有服务端追加过），坏数据只 warn 一行。
     *
     * <p>返回 {@code LinkedHashMap}（插入顺序 = 追加顺序），{@link #ledgerTrim} 靠它裁最旧的。
     */
    private Map<String, Saved> loadLedger(String scope) {
        Map<String, Saved> keys = new LinkedHashMap<>();
        Path file = ledgerFileFor(root, scope);
        try {
            if (!Files.exists(file)) return keys;
            JsonObject state = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            JsonElement items = state.get("items");
            if (items == null || !items.isJsonArray()) {
                Log.warn("WebUI 追加账本里没有 items 数组，按「没有追加过」继续：" + file.getFileName());
                return keys;
            }
            for (JsonElement node : items.getAsJsonArray()) {
                if (node == null || !node.isJsonObject()) continue;
                JsonObject item = node.getAsJsonObject();
                String key = asText(item.get("key"));
                if (key.isEmpty()) continue;
                String entry = item.has("entry") && item.get("entry").isJsonObject()
                        ? Json.GSON.toJson(item.get("entry")) : "";      // 老账本没有 entry：按"没有追加过"处理
                keys.put(key, new Saved(key, asText(item.get("text")), entry));
            }
        } catch (Exception error) {
            // 账本坏了只影响"会不会重复追加一次"：宁可多写一条，也不能因为账本读不出来就整条不落。
            Log.warn("WebUI 追加账本读取失败（当作没有追加过）：" + file.getFileName(), error);
        }
        return keys;
    }

    /**
     * 写幂等账本（**调用方持锁**）：与正文同目录的原子写，UTF-8 无 BOM、纯 LF。
     * 结构 {@code {"version":1,"scope":"…","savedAtMillis":<long>,"items":[{"key","text","entry"},…]}}，
     * 只留最近 {@value #LEDGER_CAP} 条键（比正文的 200 条窗口宽得多，窗口里还留着的条目键一定还在）。
     */
    private void writeLedger(String scope, Map<String, Saved> keys) {
        try {
            JsonArray items = ledgerTrim(keys);
            JsonObject state = new JsonObject();
            state.addProperty("version", 1);
            state.addProperty("scope", scope);
            state.addProperty("savedAtMillis", System.currentTimeMillis());
            state.add("items", items);
            Json.atomicWriteText(ledgerFileFor(root, scope), Json.GSON.toJson(state) + "\n");
        } catch (Exception error) {
            // 账本写失败：正文已经写好了，这一条照旧算落过（进程内不会再追加一次）；
            // 只在"重启后又收到同一条回执"这种极端情况下可能重复一次。
            Log.warn("WebUI 追加账本写入失败（正文已落盘，重启后同一条回执可能重复一次）：" + error, error);
        }
    }

    /** 账本只留最近 {@value #LEDGER_CAP} 条键（尾部 = 最新的）。 */
    private static JsonArray ledgerTrim(Map<String, Saved> keys) {
        List<Saved> all = new ArrayList<>(keys.values());
        JsonArray items = new JsonArray();
        for (int index = Math.max(0, all.size() - LEDGER_CAP); index < all.size(); index++) {
            Saved saved = all.get(index);
            JsonObject item = new JsonObject();
            item.addProperty("key", saved.key());
            item.addProperty("text", saved.text());
            JsonObject entry = parseEntry(saved.entry());
            if (entry != null) item.add("entry", entry);
            items.add(item);
        }
        return items;
    }

    /** 账本里那一段"完整形状的条目"JSON → 对象（读不出来给 null）。 */
    private static JsonObject parseEntry(String stored) {
        if (stored == null || stored.isBlank()) return null;
        try {
            JsonObject entry = Json.parse(stored);
            return entry.size() == 0 ? null : entry;
        } catch (Exception error) {
            return null;
        }
    }

    /** 一条规范形状的正文条目：固定字段顺序 role、text、images（空图不带字段）。 */
    private static JsonObject entry(String text, JsonArray images) {
        JsonObject value = new JsonObject();
        value.addProperty("role", "bot");
        value.addProperty("text", text);
        if (images != null && images.size() > 0) value.add("images", images);
        return value;
    }

    /** 这条正文条目和"文字 + 图片"完全一样吗（一样就不必再写盘）。 */
    private static boolean sameEntry(JsonObject entry, String text, JsonArray images) {
        if (entry == null) return false;
        if (!text.equals(asText(entry.get("text")))) return false;
        JsonArray mine = entry.has("images") && entry.get("images").isJsonArray()
                ? entry.getAsJsonArray("images") : new JsonArray();
        return Json.GSON.toJson(mine).equals(Json.GSON.toJson(images));
    }

    /** 正文里"文字就是这一段"的那一条（页面 push 上来的那份、或账本下标在窗口滚动后失效时，靠它找回来）。 */
    private static int indexOfText(JsonArray entries, String text) {
        for (int index = entries.size() - 1; index >= 0; index--) {
            JsonElement node = entries.get(index);
            if (node != null && node.isJsonObject() && text.equals(asText(node.getAsJsonObject().get("text")))) return index;
        }
        return -1;
    }

    /** 正文里"文字就是这一段、而且还没有图"的那一条（"先文字后图"要把图补进它，而不是新开一个气泡）。 */
    private static int indexOfBareText(JsonArray entries, String text) {
        for (int index = entries.size() - 1; index >= 0; index--) {
            JsonElement node = entries.get(index);
            if (node == null || !node.isJsonObject()) continue;
            JsonObject entry = node.getAsJsonObject();
            if (!text.equals(asText(entry.get("text")))) continue;
            JsonArray images = entry.has("images") && entry.get("images").isJsonArray()
                    ? entry.getAsJsonArray("images") : new JsonArray();
            if (images.size() == 0) return index;
        }
        return -1;
    }

    /** 正文里"逐字节一样"的那一条的下标（先规范化再比，字段顺序/裁剪口径都统一）。 */
    private static int indexOfSame(JsonArray entries, JsonObject wanted) {
        String target = Json.GSON.toJson(normalizeEntry(wanted));
        for (int index = 0; index < entries.size(); index++) {
            JsonElement node = entries.get(index);
            if (node != null && target.equals(Json.GSON.toJson(normalizeEntry(node)))) return index;
        }
        return -1;
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

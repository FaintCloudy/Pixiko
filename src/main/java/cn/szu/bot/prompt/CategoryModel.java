package cn.szu.bot.prompt;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import cn.szu.bot.Json;
import cn.szu.bot.Log;
import cn.szu.bot.Settings;
import cn.szu.bot.chat.DeepSeekPrompts;

/**
 * 复合短语的**兜底分类模型**（方案 B）：本地判不出来的复合短语（多词、本地归类为"其他"）问一次 DeepSeek，
 * 由它把短语切成"片段 → 类别"，程序再**只删属于目标类别的片段**，其余片段按原顺序拼回。
 *
 * <p>三条硬性质（用户要求，任何情况下都不许破）：
 * <ol>
 *   <li><b>只删目标片段</b>：模型给出的片段必须首尾相接连起来等于原短语、类别必须落在
 *       {@link TermCategories#ORDER} 里，否则整条短语按"判不出来"处理；模型参与的短语不再退回本地
 *       片段判定去删，免得又把复合短语的一部分草率吃掉。整条删除还要求本地也认定它整条属于目标类别。</li>
 *   <li><b>失败保守</b>：模型报错、超时、返回不是合法 JSON、片段拼不回原短语 → 这条短语**一个字都不动**
 *       （按未分类处理），只记 warn。</li>
 *   <li><b>缓存</b>：结果落盘 {@code data/category-model-cache.json}（UTF-8 无 BOM、纯 LF），键是规范化后的
 *       短语；同样的短语第二次**0 次**模型调用。</li>
 * </ol>
 *
 * <p><b>不设额度上限</b>：本地判不出来的短语有多少就处理多少。单次请求按
 * {@link #BATCH_CHARS} 字符 / {@link #BATCH_PHRASES} 条自动分批、一批一批依次发，短语总数不限。
 * （每批 12 条不是额度护栏，而是复用客户端 {@link DeepSeekPrompts#decompose} 一次最多回 12 个 parts 的
 * 传输限制——一批塞更多条，多出来的部分会被那个客户端直接截掉。）
 *
 * <p>开关默认<b>开启</b>；关闭时 {@link #resolve} 不发请求、不读缓存，一切走本地判定。
 * 模型调用可注入（{@link Caller}）：测试注入假 Caller，绝不联网络。
 */
public final class CategoryModel {
    /** 一次批量提问：生产环境走 DeepSeek（{@link #deepSeek}），测试注入假实现。 */
    public interface Caller {
        /** 返回一段 JSON 文本，形如 <code>{"phrases":[{"text":"原短语","segments":[{"text":"片段","category":"镜头"}]}]}</code>。 */
        String ask(List<String> phrases) throws Exception;
    }

    /** 模型给出的一个片段：文本 + 类别（模型给出的就是结论；采信与否由 {@link #valid} 的校验决定）。 */
    public record Segment(String text, String category) {}

    /** 缓存文件版本；结构：<code>{"version":1,"entries":{"规范化短语":[{"text":…,"category":…}]}}</code>。 */
    public static final int CACHE_VERSION = 1;
    public static final String CACHE_FILE = "data/category-model-cache.json";
    /** 单次请求的字符预算（自适应分批用）；短语总数不设上限。 */
    public static final int BATCH_CHARS = 1200;
    /** 单次请求的短语条数：取自复用客户端 decompose 的输出上限 12（传输限制，不是额度护栏）。 */
    public static final int BATCH_PHRASES = 12;
    /** 长到这个词数的从句，本地"整词命中"不足以断定它整条属于某一类，交给模型切片段。 */
    public static final int CLAUSE_WORDS = 5;

    private final Path root;
    private final Caller caller;
    /** 规范化短语 → 片段。 */
    private final Map<String, List<Segment>> cache = new LinkedHashMap<>();
    private boolean cacheLoaded;
    private volatile boolean enabled = true;
    private int lastCalls, lastPhrases, lastFallbacks, lastCachedHits;

    public CategoryModel(Path root, Caller caller) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.caller = caller;
    }

    /**
     * 生产环境的模型：复用项目既有的 DeepSeek 客户端（生图频道）与它的 {@code decompose} 通道，
     * 不新建 HTTP 客户端。默认开启，按会话的开关由调用方 {@link #setEnabled} 套上（见
     * {@link Settings#categoryModelEnabled}）。
     */
    public static CategoryModel deepSeek(Settings settings) {
        return new CategoryModel(settings.root, new DeepSeekCaller(settings));
    }

    /** 开或关；关闭时不发请求、不读缓存，一切走本地判定。 */
    public synchronized void setEnabled(boolean value) { enabled = value; }

    public synchronized boolean enabled() { return enabled; }

    /** 缓存文件位置（{@code <root>/data/category-model-cache.json}）。 */
    public Path cacheFile() { return root.resolve(CACHE_FILE); }

    /** 已缓存多少条短语；关闭时不读缓存（返回 0）。 */
    public synchronized int cachedCount() {
        if (!enabled) return 0;
        loadCache();
        return cache.size();
    }

    /**
     * 对给定的短语批量要"片段 → 类别"：先查缓存（命中不发请求），剩下的按 {@link #BATCH_CHARS}/
     * {@link #BATCH_PHRASES} 自动分批依次问模型。返回的键是调用方传进来的原写法；判不出来的短语
     * <b>不会</b>出现在返回值里（调用方据此保守处理）。短语总数不设上限。
     */
    public Map<String, List<Segment>> resolve(List<String> phrases) {
        Map<String, List<Segment>> resolved = new LinkedHashMap<>();
        List<String> wanted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String phrase : phrases == null ? List.<String>of() : phrases) {
            if (phrase == null || phrase.isBlank()) continue;
            String value = phrase.strip();
            if (seen.add(normalize(value))) wanted.add(value);
        }
        synchronized (this) {
            lastCalls = 0; lastPhrases = 0; lastFallbacks = 0; lastCachedHits = 0;
            // 关闭（或没有 Caller）时一条请求都不发、缓存也不读：调用方全部按本地保守处理。
            if (!enabled || caller == null) return resolved;
            if (wanted.isEmpty()) return resolved;
            loadCache();
            List<String> pending = new ArrayList<>();
            for (String phrase : wanted) {
                List<Segment> hit = cache.get(normalize(phrase));
                if (hit != null && valid(hit, phrase)) { resolved.put(phrase, hit); lastCachedHits++; }
                else pending.add(phrase);
            }
            boolean changed = false;
            for (List<String> batch : batches(pending)) {
                lastCalls++;
                lastPhrases += batch.size();
                Map<String, List<Segment>> parsed;
                try {
                    parsed = parse(caller.ask(batch));
                } catch (Exception error) {
                    lastFallbacks += batch.size();
                    Log.warn("复合短语分类模型调用失败，这批 " + batch.size() + " 条短语按未分类处理（一个字都不删）：" + reason(error));
                    continue;
                }
                for (String phrase : batch) {
                    List<Segment> segments = parsed.get(normalize(phrase));
                    if (segments == null || !valid(segments, phrase)) {
                        lastFallbacks++;
                        Log.warn("复合短语分类模型没给出可采信的切分（片段拼不回原短语或类别不合法），该短语按未分类处理（一个字都不删）：" + phrase);
                        continue;
                    }
                    cache.put(normalize(phrase), segments);
                    resolved.put(phrase, segments);
                    changed = true;
                }
            }
            if (changed) saveCache();
            return resolved;
        }
    }

    /**
     * 只删这条短语里属于 {@code categories} 的片段，其余片段按原顺序拼回并收拾格式；判不出来
     * （关闭 / 调用失败 / 片段不合法 / 没有目标片段）时<b>返回原样</b>。
     *
     * <p>整条删除（返回空串）只在本地也认定它整条属于目标类别时允许（{@code multiple views} 这类）：
     * 本地把一条复合短语判成"其他"时，不接受模型"整条都是镜头"的结论，免得又一次整条吃掉复合短语。
     */
    public String dropFragments(String term, Set<String> categories, PromptUsage usage) {
        if (term == null || term.isBlank() || categories == null || categories.isEmpty()) return term == null ? "" : term;
        return dropFragments(term, categories, usage, resolve(List.of(term.strip())).get(term.strip()));
    }

    /**
     * 同一件事，但片段判定由调用方**批量**取好（一条命令只发一批请求）：{@code segments} 为 null 或空表示
     * 这条短语判不出来，返回原样。批量调用方用这个重载，别在循环里逐条调 {@link #resolve}。
     */
    public static String dropFragments(String term, Set<String> categories, PromptUsage usage, List<Segment> segments) {
        if (term == null || term.isBlank() || categories == null || categories.isEmpty()) return term == null ? "" : term;
        if (segments == null || segments.isEmpty()) return term;
        List<String> pieces = new ArrayList<>();
        boolean removed = false;
        for (Segment segment : segments) {
            if (categories.contains(segment.category())) { removed = true; continue; }
            pieces.add(segment.text());
        }
        if (!removed) return term;
        if (pieces.isEmpty()) {
            // 这句话在模型眼里整条都是目标类别：**只有本地也把整条判成目标类别**（本地按片段摘掉目标类别后
            // 什么都不剩）时才允许整条删——multiple views、depth of field 这类本地就是整条；
            // 本地判不出来的复合短语（本地还能摘出别的片段、或本地根本不认）不许被模型整条吃掉。
            if (!TermCategories.withoutCategories(term, categories).isBlank()) {
                Log.warn("模型把整条复合短语判成目标类别（" + String.join("、", categories)
                        + "），本地判定不是整条，保守起见不整条删：" + term);
                return term;
            }
            return "";
        }
        String rebuilt = tidy(String.join(" ", pieces));
        return rebuilt.isBlank() ? term : rebuilt;
    }

    /** 开关 / 缓存条数 / 用量（只统计、不限制）：{@code enabled, cached, lastCalls, lastPhrases, …}。 */
    public JsonObject status() {
        JsonObject status = new JsonObject();
        synchronized (this) {
            status.addProperty("enabled", enabled);
            status.addProperty("cached", enabled ? cachedCount() : 0);
            status.addProperty("lastCalls", lastCalls);
            status.addProperty("lastPhrases", lastPhrases);
            status.addProperty("lastCachedHits", lastCachedHits);
            status.addProperty("lastFallbacks", lastFallbacks);
            status.addProperty("cacheFile", cacheFile().toString());
            status.addProperty("batchChars", BATCH_CHARS);
            status.addProperty("batchPhrases", BATCH_PHRASES);
            status.addProperty("limited", false);
        }
        return status;
    }

    /**
     * 这条词条本地判不出来、需要模型兜底吗？判据：
     * <ul>
     *   <li>不是 LoRA/嵌入标签，也不是单词条；</li>
     *   <li>本地分类给了"其他"——既可能是认不出的自定义词，也可能是把多个方面写在一句里的复合短语；</li>
     *   <li>或者它是一条**长从句**（≥ {@link #CLAUSE_WORDS} 个词）：本地只是"整词命中词表"，一条长从句里
     *       即便命中了镜头词，也不能断定整条都是镜头——{@code extreme close-up on their joined crotch as he
     *       penetrates her} 正是这种情况，整条删掉就是用户抱怨的"草率"。</li>
     * </ul>
     * 本地已能定类的 2–4 词短标签（night、depth of field、multiple views…）不问模型——本地就够准，问了只白花钱。
     */
    public static boolean needsModel(PromptUsage usage, String term) {
        if (term == null || term.isBlank() || TermCategories.isLoraOrEmbedding(term)) return false;
        List<String> tokens = words(term);
        if (tokens.size() < 2) return false;
        if (TermCategories.OTHER.equals(TermCategories.categoryOf(usage, term))) return true;
        return tokens.size() >= CLAUSE_WORDS;
    }

    /** 缓存键与"片段拼回原文"的比对口径：小写、下划线当空格、连续空白折叠。 */
    static String normalize(String value) {
        if (value == null) return "";
        return value.strip().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
    }

    /** 收拾格式：折叠连续空白、去掉首尾与多余的逗号/分号，不留前后空白。 */
    static String tidy(String value) {
        if (value == null) return "";
        String text = value.replaceAll("\\s+", " ").strip();
        text = text.replaceAll("^[,;\\s]+", "").replaceAll("[,;\\s]+$", "").strip();
        text = text.replaceAll("\\s*,\\s*", ", ").replaceAll("(?:,\\s*)+", ", ").strip();
        return text.replaceAll("^[,;\\s]+", "").replaceAll("[,;\\s]+$", "").strip();
    }

    /** 片段校验：非空、类别在既有类别集合里、首尾相接拼起来等于原短语（空格写法可以不同）。 */
    static boolean valid(List<Segment> segments, String phrase) {
        if (segments == null || segments.isEmpty()) return false;
        StringBuilder joined = new StringBuilder();
        for (Segment segment : segments) {
            if (segment == null || segment.text() == null || segment.text().isBlank()) return false;
            if (segment.category() == null || !TermCategories.ORDER.contains(segment.category())) return false;
            if (joined.length() > 0) joined.append(' ');
            joined.append(segment.text().strip());
        }
        return normalize(joined.toString()).equals(normalize(phrase));
    }

    /** 自适应分批：按字符预算与单次条数上限切；短语总数不设上限。 */
    public static List<List<String>> batches(List<String> phrases) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int length = 0;
        for (String phrase : phrases) {
            int size = phrase.length() + 1;
            if (!current.isEmpty() && (current.size() >= BATCH_PHRASES || length + size > BATCH_CHARS)) {
                batches.add(List.copyOf(current));
                current.clear();
                length = 0;
            }
            current.add(phrase);
            length += size;
        }
        if (!current.isEmpty()) batches.add(List.copyOf(current));
        return batches;
    }

    /** 解析模型的 JSON 答复；{@code phrases} 缺失或不是数组时按"判不出来"抛错（调用方保守处理）。 */
    public static Map<String, List<Segment>> parse(String raw) throws IOException {
        JsonObject object = object(raw);
        JsonElement items = object.get("phrases");
        if (items == null || !items.isJsonArray()) throw new IOException("回复里没有 phrases 数组");
        Map<String, List<Segment>> result = new LinkedHashMap<>();
        for (JsonElement item : items.getAsJsonArray()) {
            if (item == null || !item.isJsonObject()) continue;
            JsonObject entry = item.getAsJsonObject();
            String text = string(entry, "text");
            if (text.isBlank()) continue;
            JsonElement list = entry.get("segments");
            if (list == null || !list.isJsonArray()) continue;
            List<Segment> segments = new ArrayList<>();
            for (JsonElement piece : list.getAsJsonArray()) {
                if (piece == null || !piece.isJsonObject()) continue;
                JsonObject segment = piece.getAsJsonObject();
                segments.add(new Segment(string(segment, "text"), string(segment, "category")));
            }
            if (!segments.isEmpty()) result.put(normalize(text), List.copyOf(segments));
        }
        return result;
    }

    /**
     * 把复用客户端 {@code decompose} 的答复（{@code {"parts":["序号|片段=类别|片段=类别", …]}}）还原成
     * 规范 JSON：{@code {"phrases":[{"text":原短语,"segments":[{"text":片段,"category":类别}]}]}}。
     *
     * <p>序号对不上、格式不对、类别不合法或片段拼不回原短语的条目一律<b>不放进结果</b>——调用方按
     * "判不出来"保守处理（一个字都不删）。
     */
    public static String phrasesJson(List<String> phrases, List<String> parts) {
        List<String> values = phrases == null ? List.of() : phrases;
        Map<String, List<Segment>> answers = new LinkedHashMap<>();
        for (String part : parts == null ? List.<String>of() : parts) {
            if (part == null) continue;
            String text = part.strip();
            int bar = text.indexOf('|');
            if (bar <= 0) continue;
            String number = text.substring(0, bar).replaceAll("[^0-9]", "");
            if (number.isEmpty()) continue;
            int index;
            try { index = Integer.parseInt(number); } catch (NumberFormatException error) { continue; }
            if (index < 1 || index > values.size()) continue;
            String phrase = values.get(index - 1).strip();
            List<Segment> segments = new ArrayList<>();
            boolean broken = false;
            for (String piece : text.substring(bar + 1).split("\\|")) {
                String value = piece.strip();
                if (value.isEmpty()) continue;
                int at = value.lastIndexOf('=');
                if (at <= 0 || at == value.length() - 1) { broken = true; break; }
                segments.add(new Segment(value.substring(0, at).strip(), value.substring(at + 1).strip()));
            }
            if (broken || segments.isEmpty()) continue;
            answers.putIfAbsent(normalize(phrase), segments);
        }
        JsonArray list = new JsonArray();
        for (String phrase : values) {
            if (phrase == null || phrase.isBlank()) continue;
            List<Segment> segments = answers.get(normalize(phrase.strip()));
            if (segments == null || !valid(segments, phrase)) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("text", phrase.strip());
            JsonArray pieces = new JsonArray();
            for (Segment segment : segments) {
                JsonObject value = new JsonObject();
                value.addProperty("text", segment.text());
                value.addProperty("category", segment.category());
                pieces.add(value);
            }
            entry.add("segments", pieces);
            list.add(entry);
        }
        JsonObject object = new JsonObject();
        object.add("phrases", list);
        return Json.GSON.toJson(object);
    }

    /** 生产环境的 Caller：复用既有 DeepSeek 客户端（生图频道）的 decompose 通道，不新建 HTTP 客户端。 */
    private static final class DeepSeekCaller implements Caller {
        private final Settings settings;

        DeepSeekCaller(Settings settings) { this.settings = settings; }

        @Override public String ask(List<String> phrases) throws Exception {
            DeepSeekPrompts client = DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.IMAGE);
            List<String> parts = client.decompose(instruction(phrases), RULES);
            return phrasesJson(phrases, parts);
        }
    }

    /** 用户消息：每条短语一行、带序号，模型照序号回元素。 */
    public static String instruction(List<String> phrases) {
        StringBuilder text = new StringBuilder();
        int index = 1;
        for (String phrase : phrases) text.append(index++).append(". ").append(phrase.strip()).append('\n');
        return text.toString().strip();
    }

    /**
     * 系统提示词（复用客户端只允许自定义这一段）。类别名、类别含义与 {@link TermCategories#describe}
     * 给模型的说明保持同一套口径；输出格式受复用客户端固定信封 {@code {"parts":[…]} } 限制，
     * 所以每条短语写成一条 parts 元素：{@code 序号|片段=类别|片段=类别}。
     */
    public static final String RULES = """
        你在给 Stable Diffusion 提示词里的一条**复合短语**做片段切分：判断这条短语里"哪一段属于哪一类"，
        供程序按类别精确删除用。用户输入的每一行是一条短语，行首是序号。
        必须遵守：
        - 把每条短语**完整**切成若干片段，按原顺序首尾相接，拼起来必须与原文完全一致（只允许空格写法不同）。
          一个单词都不能改写、翻译、增删、合并、调序；不要把两条短语合成一条。
        - 每个片段标一个类别，只能从这些里选：人物、角色、作品、表情、动作、姿势、服装、物品、场景、环境、镜头、画面、其他。
          人物=角色身份与外貌特征；服装=衣服鞋袜配饰；动作/姿势=行为与体位；表情=表情情绪；
          场景/环境=地点、天气、时段、光线；物品=道具；画面=画质与画风；角色/作品=点名才用的角色与作品名；
          镜头=**取景与视角**（view、angle、shot、close-up、from behind、from above、full body、POV、upper body…）。
        - 只有**确实**属于某一类的片段才标成那一类，拿不准就标"其他"。
          绝不要把动作、人物、服装、场景、物品整段标成"镜头"——只有它真的是取景/视角时才标"镜头"。
        - 一条短语整条都是取景词时（multiple views、full body），整条标"镜头"，不要硬拆。
        - 每条输入短语都要有且只有一条 parts 元素，格式：序号|片段=类别|片段=类别
          例如第 2 条短语 standing sex from behind → "2|standing sex=动作|from behind=镜头"。
          序号就是输入里的序号；片段与类别之间用半角 =，各项之间用半角 |。
        - 不要输出解释、Markdown 或别的字段。
        只返回 JSON：{"parts":["1|multiple views=镜头","2|standing sex=动作|from behind=镜头"]}
        """;

    /** 容错地取出 JSON 对象：模型的答复可能带 Markdown 围栏或前后说明。 */
    private static JsonObject object(String raw) throws IOException {
        String cleaned = raw == null ? "" : raw.strip();
        if (cleaned.startsWith("```")) cleaned = cleaned.replaceFirst("(?s)^```[a-zA-Z]*\\s*", "").replaceFirst("(?s)\\s*```$", "").strip();
        try { return Json.parse(cleaned); } catch (Exception ignored) { }
        int start = cleaned.indexOf('{'), end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try { return Json.parse(cleaned.substring(start, end + 1)); } catch (Exception ignored) { }
        }
        throw new IOException("回复不是 JSON 对象");
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString().strip() : "";
    }

    private static List<String> words(String term) {
        List<String> result = new ArrayList<>();
        for (String token : PromptEditor.key(term == null ? "" : term).split("[^a-z0-9]+")) if (!token.isEmpty()) result.add(token);
        return result;
    }

    private static String reason(Exception error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** 读一遍缓存；损坏或版本不符时按空缓存继续（只记 warn，不影响本地判定）。 */
    private void loadCache() {
        if (cacheLoaded) return;
        cacheLoaded = true;
        Path file = cacheFile();
        if (!Files.isRegularFile(file)) return;
        try {
            JsonObject saved = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (saved.has("version") && saved.get("version").getAsInt() != CACHE_VERSION) {
                Log.warn("复合短语分类缓存版本不符（" + file + "），按空缓存继续；下次会重新问模型。");
                return;
            }
            for (Map.Entry<String, JsonElement> entry : Json.obj(saved, "entries").entrySet()) {
                List<Segment> segments = new ArrayList<>();
                if (entry.getValue().isJsonArray()) {
                    for (JsonElement piece : entry.getValue().getAsJsonArray()) {
                        if (piece == null || !piece.isJsonObject()) continue;
                        JsonObject item = piece.getAsJsonObject();
                        segments.add(new Segment(string(item, "text"), string(item, "category")));
                    }
                }
                if (!segments.isEmpty() && valid(segments, entry.getKey())) cache.put(normalize(entry.getKey()), List.copyOf(segments));
            }
        } catch (Exception error) {
            cache.clear();
            Log.warn("复合短语分类缓存读取失败（按空缓存继续，不影响本地判定）：" + reason(error));
        }
    }

    /** 落盘缓存：UTF-8 无 BOM、纯 LF（{@link Json#atomicWriteText} 原子替换，避免写坏正在读的缓存）。 */
    private void saveCache() {
        try {
            JsonObject entries = new JsonObject();
            for (Map.Entry<String, List<Segment>> item : cache.entrySet()) {
                JsonArray list = new JsonArray();
                for (Segment segment : item.getValue()) {
                    JsonObject value = new JsonObject();
                    value.addProperty("text", segment.text());
                    value.addProperty("category", segment.category());
                    list.add(value);
                }
                entries.add(item.getKey(), list);
            }
            JsonObject saved = new JsonObject();
            saved.addProperty("version", CACHE_VERSION);
            saved.add("entries", entries);
            Json.atomicWriteText(cacheFile(), Json.GSON.toJson(saved).replace("\r\n", "\n") + "\n");
        } catch (Exception error) {
            Log.warn("复合短语分类缓存写入失败（下次仍会重新问模型）：" + reason(error));
        }
    }
}

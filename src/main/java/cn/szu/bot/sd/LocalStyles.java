package cn.szu.bot.sd;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import cn.szu.bot.Json;
import cn.szu.bot.Log;

/**
 * 机器人自己的样式库（{@code data/local-styles.json}），也是**唯一**的样式来源。
 *
 * <p>样式完全独立在机器人这边：保存、改名、删除、载入都只读写这个文件，直接套用到个人提示词上，
 * 不依赖 WebUI 在线、也不会去改别的程序的样式文件。WebUI 里已有的预设样式可以用
 * ".style import webui" 一次性搬进来（之后机器人不再读 WebUI 的样式列表）。
 *
 * <p><b>分类</b>（{@code category} 字段）：用户手动的分类优先；没手动设过就走默认规则——
 * <ol>
 *   <li><b>LoRA 附带的展示图样式：一个 LoRA 一个分类</b>，分类名＝该 LoRA 的显示名。
 *       判据按可靠性排序：{@code model.lora}（生成展示图样式时写进去的 LoRA 名）→
 *       {@code data/civitai-style-links.json} 里映射到这条样式名的记录（键里带 LoRA 文件名）→
 *       样式名前缀（展示图样式名一律是 {@code <模型名> <图号>}，去掉尾号就是模型名）。
 *       显示名优先用 {@code data/civitai/*.json} 记录里的 {@code model_name}（人看的名字，与样式名前缀一致），
 *       查不到就用判据本身给出的名字。**判不出具体是哪个 LoRA 时退回 {@value #LORA_CATEGORY}**；</li>
 *   <li>不是 LoRA 附带样式就按其**归属栈**给分类（{@code Anima 栈} / {@code SDXL 栈} / {@code SD 1.5 栈} /
 *       {@code Flux 栈} / {@code Qwen 栈}，沿用 {@link StackClassifier}）；</li>
 *   <li>判不出归属栈的就是 {@value #OTHER_CATEGORY}。</li>
 * </ol>
 * 默认规则只在**显示与分组**时现算，不会因为读一次列表就往用户的数据文件里写东西；
 * 老文件没有 {@code category} 字段时一律当成"未手动分类"，既不报错也不重写。
 *
 * <p><b>老数据</b>：v1.0.12 把展示图样式一律写成 {@code category="LoRA 附带"}（那是**自动**分类，
 * 不是用户的选择），这里一律当成"没手动设过"，于是老样式自动落回各自的 LoRA 分类。
 * 分类用的外部文件（{@code civitai-style-links.json} 与 {@code data/civitai/*.json}）**只读，绝不改写**。
 */
public final class LocalStyles {
    private static final int VERSION = 1;
    /**
     * 判不出具体是哪个 LoRA 时的兜底分类名。v1.0.12 之前它是所有展示图样式的统一大类；
     * 现在只是一个兜底（每个能认出来的 LoRA 各成一个分类）。
     */
    public static final String LORA_CATEGORY = "LoRA 附带";
    /** 判不出归属栈时的默认分类。 */
    public static final String OTHER_CATEGORY = "未分类";
    /** 分类名的长度上限（回执与网页都按字符数算）。 */
    /** 分类名的长度上限（回执与网页都按字符数算）。 */
    /**
     * 手动分类名的长度上限。**必须容得下最长的 LoRA 分类名**：控制台是把样式行拖到分类组头上来换分类，
     * 组名就是 LoRA 名（本地见过的 LoRA 名有 60 多字的），上限太小会让"拖进那个分类"直接失败。
     */
    public static final int MAX_CATEGORY = 200;

    /** 分类种类（{@code /api/styles} 的 kind 与 categorySource 都用这一份词表）。 */
    public static final String KIND_LORA = "lora";
    public static final String KIND_STACK = "stack";
    public static final String KIND_MANUAL = "manual";
    public static final String KIND_NONE = "none";
    /** 「未分类」那一组的 key（没有细分，就是个固定串）。 */
    public static final String NONE_KEY = KIND_NONE;
    /** 归属栈分组的固定顺序（与 Forge 预设同序；不在表里的栈按名字排到后面去了）。 */
    private static final List<String> STACK_ORDER = List.of(
            StackClassifier.ANIMA, StackClassifier.XL, StackClassifier.SD, StackClassifier.FLUX, StackClassifier.QWEN);
    /** 展示图样式的名字：{@code <模型名> <图号>}（尾号是展示图编号）。 */
    private static final Pattern TRAILING_NUMBER = Pattern.compile("^(.+?)\\s+([0-9]+)$");

    /** 一条样式生效后的分类：{@code key} 前端拿来持久化折叠状态，{@code name} 是人看的名字。 */
    public record Classification(String key, String name, String kind) {
        public Classification {
            key = key == null || key.isBlank() ? NONE_KEY : key;
            name = name == null ? "" : name;
            kind = kind == null ? "" : kind;
        }
        /** 这是用户手动设的分类吗（手动分类名一模一样的自动分类是另一组，key 不同）。 */
        public boolean manual() { return KIND_MANUAL.equals(kind); }
    }

    /** 分类清单里的一组：key / 名字 / 种类 / 条数。 */
    public record Group(String key, String name, String kind, int count) {
        /** 前端靠它决定组头样式（LoRA 组可以显示 LoRA 名与「去 LoRA 页」入口）。 */
        public boolean lora() { return KIND_LORA.equals(kind); }
    }

    /**
     * 分类要用的**只读**外部数据：
     * <ul>
     *   <li>{@code showcases}：展示图样式名 → LoRA 标识（{@code civitai-style-links.json} 的键里那个文件名）；</li>
     *   <li>{@code displayNames}：LoRA 标识（小写）→ 显示名（{@code data/civitai/*.json} 的 {@code model_name}）。</li>
     * </ul>
     */
    public record LoraIndex(Map<String, String> showcases, Map<String, String> displayNames) {
        public static final LoraIndex EMPTY = new LoraIndex(Map.of(), Map.of());
        public LoraIndex {
            showcases = showcases == null ? Map.of() : Map.copyOf(showcases);
            displayNames = displayNames == null ? Map.of() : Map.copyOf(displayNames);
        }
        /** 这条样式名在展示图映射里吗（老样式没有 lora 标注时靠它认出是展示图样式）。 */
        public boolean showcase(String styleName) { return styleName != null && showcases.containsKey(styleName); }
        /** 展示图样式名 → LoRA 标识（没有就是空串）。 */
        public String loraOf(String styleName) {
            return styleName == null ? "" : showcases.getOrDefault(styleName, "");
        }
        /** LoRA 标识 → 人看的显示名；没有 Civitai 记录就用标识本身（绝不编造）。 */
        public String displayName(String id) {
            String value = id == null ? "" : id.strip();
            if (value.isEmpty()) return "";
            return displayNames.getOrDefault(value.toLowerCase(Locale.ROOT), value);
        }
    }

    /** 一个本机样式：保存时的正反向原文与更新时间，外加**保存时的模型参数**与**分类**（都可为空）。 */
    public record Style(String name, String positive, String negative, String updatedAt, JsonObject model, String category) {
        /** 不带分类的写法（老调用方、导入）保持原样可用：分类为空＝按默认规则归组。 */
        public Style(String name, String positive, String negative, String updatedAt, JsonObject model) {
            this(name, positive, negative, updatedAt, model, "");
        }
        /** 不带模型参数的写法（导入、老数据、纯文本样式）保持原样可用。 */
        public Style(String name, String positive, String negative, String updatedAt) {
            this(name, positive, negative, updatedAt, null, "");
        }
        public Style {
            String value = category == null ? "" : category.strip();
            // 老版本把展示图样式一律写成「LoRA 附带」——那是**自动**分类，不是用户的选择：
            // 一律当成"没手动设过"，这样老样式会按新规则回到各自 LoRA 的分类里。
            category = LORA_CATEGORY.equals(value) ? "" : value;
        }
        /** 有模型参数吗（底模/采样/步数/CFG/尺寸任意一项即可）。 */
        public boolean hasModel() { return model != null && model.size() > 0; }
        /** 用户**手动**设过分类吗（空＝没设过，显示时走默认规则）。 */
        public boolean hasCategory() { return !category.isBlank(); }
        /** 一行摘要，给回执与网页显示用；没有模型参数时返回空串。 */
        public String modelSummary() {
            if (!hasModel()) return "";
            List<String> parts = new ArrayList<>();
            String baseModel = Json.str(model, "baseModel", "");
            String checkpoint = Json.str(model, "checkpoint", "");
            // 底模是"这个样式属于哪个基础模型"的分类信息，来源也要写出来（推断的要显式标成推断）。
            if (!baseModel.isBlank()) parts.add("底模 " + baseModel + sourceNote(Json.str(model, "baseModelSource", "")));
            // 归属栈：载入样式时"栈对不对"比底模名字更关键（栈不对出全灰废图）。
            String stack = Json.str(model, "stack", "");
            if (stack.isBlank()) stack = StackClassifier.stackOf(baseModel, checkpoint);
            if (!stack.isBlank()) parts.add(StackClassifier.stackLabel(stack));
            // 检查点与底模是同一个东西时（按当前预设推断出来的那种）就不重复写一遍。
            if (!checkpoint.isBlank() && !checkpoint.equalsIgnoreCase(baseModel)
                    && !checkpoint.toLowerCase(java.util.Locale.ROOT).startsWith(baseModel.toLowerCase(java.util.Locale.ROOT) + "."))
                parts.add("检查点 " + checkpoint);
            String preset = Json.str(model, "forge_preset", "");
            if (!preset.isBlank()) parts.add("Forge 预设 " + preset);
            String sampler = Json.str(model, "sampler", "");
            if (!sampler.isBlank()) parts.add(sampler);
            String scheduler = Json.str(model, "scheduler", "");
            if (!scheduler.isBlank()) parts.add("调度器 " + scheduler);
            int steps = Json.num(model, "steps", 0);
            if (steps > 0) parts.add(steps + " 步");
            double cfg = Json.decimal(model, "cfg", 0);
            if (cfg > 0) parts.add("CFG " + trim(cfg));
            double distilled = Json.decimal(model, "distilledCfg", 0);
            if (distilled > 0) parts.add("Shift " + trim(distilled));
            int width = Json.num(model, "width", 0), height = Json.num(model, "height", 0);
            if (width > 0 && height > 0) parts.add(width + "×" + height + sizeNote(model));
            return String.join("，", parts);
        }
        /**
         * 尺寸来源的中文括号注。展示图样式记的 {@code width/height} 是**换算后的生成尺寸**，
         * 展示图自己的像素在 {@code previewWidth/previewHeight}：两者不同就说清是缩放来的，
         * 免得把"能出图的尺寸"当成"这张图本身多大"。
         */
        private static String sizeNote(JsonObject model) {
            int width = Json.num(model, "width", 0), height = Json.num(model, "height", 0);
            int previewWidth = Json.num(model, "previewWidth", 0), previewHeight = Json.num(model, "previewHeight", 0);
            if (previewWidth > 0 && previewHeight > 0 && (previewWidth != width || previewHeight != height))
                return "（由展示图 " + previewWidth + "×" + previewHeight + " 同比例缩放）";
            return "preview".equals(Json.str(model, "sizeSource", "")) ? "（展示图尺寸）" : "";
        }
        /** 底模来源的中文括号注（与 SdClient.BaseModel.sourceLabel 同一个词表）。 */
        private static String sourceNote(String source) {
            String label = StackClassifier.sourceLabel(source);
            return label.isBlank() ? "" : "（" + label + "）";
        }
    }
    /** 批量导入的结果：导入/覆盖了多少条、因为同名跳过多少条。 */
    public record ImportResult(int imported, int skipped) {}

    private final Path file;
    /** 机器人根目录：样式的展示图（data/style-previews）也挂在下面。 */
    private final Path root;
    /** 分类要用的只读数据（展示图映射 + LoRA 显示名）的缓存与它的文件指纹。 */
    private volatile LoraIndex index = LoraIndex.EMPTY;
    private volatile long indexStamp = Long.MIN_VALUE;

    public LocalStyles(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.file = this.root.resolve("data/local-styles.json");
    }

    /**
     * 分类要用的只读数据：
     * <ul>
     *   <li>{@code data/civitai-style-links.json}：展示图样式名 → {@code 模型号/版本号/LoRA 文件名/图号}；</li>
     *   <li>{@code data/civitai/*.json}：每个下载过的 LoRA 的记录，用来把 LoRA 文件名换成它的
     *       {@code model_name}（人看的显示名，与样式名前缀一致）。</li>
     * </ul>
     * 两个来源都没变就用缓存（网页面板会反复算分类）。**只读，绝不改写这些文件。**
     */
    public LoraIndex loraIndex() {
        Path links = root.resolve("data/civitai-style-links.json");
        Path records = root.resolve("data/civitai");
        long stamp = fileStamp(links) * 131L + directoryStamp(records) * 31L + 7L;
        if (stamp == indexStamp) return index;
        LoraIndex built = readIndex(links, records);
        index = built;
        indexStamp = stamp;
        return built;
    }

    private static LoraIndex readIndex(Path links, Path records) {
        Map<String, String> showcases = new LinkedHashMap<>();
        try {
            if (Files.isRegularFile(links)) {
                JsonObject data = Json.parse(Files.readString(links, StandardCharsets.UTF_8));
                for (String key : data.keySet()) {
                    JsonElement value = data.get(key);
                    if (value == null || !value.isJsonPrimitive()) continue;
                    String name = value.getAsString().strip();
                    String lora = loraOfLinkKey(key);
                    // 同一个样式名对应多条记录时以先出现的为准（老记录先写）；判不出 LoRA 文件名的键跳过。
                    if (!name.isEmpty() && !lora.isEmpty() && !showcases.containsKey(name)) showcases.put(name, lora);
                }
            }
        } catch (Exception error) {
            Log.warn("展示图样式映射读取失败（分类按默认规则算）：" + cn.szu.bot.Bot.error(error));
        }
        Map<String, String> displayNames = new LinkedHashMap<>();
        try {
            if (Files.isDirectory(records)) {
                List<Path> files;
                try (var stream = Files.list(records)) {
                    files = stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
                }
                for (Path record : files) {
                    try {
                        JsonObject data = Json.parse(Files.readString(record, StandardCharsets.UTF_8));
                        String name = clean(Json.str(data, "model_name", ""));
                        String stem = loraStem(Json.str(data, "path", ""));
                        if (stem.isEmpty()) stem = loraStem(Json.str(data, "original_filename", ""));
                        if (!name.isEmpty() && !stem.isEmpty())
                            displayNames.putIfAbsent(stem.toLowerCase(Locale.ROOT), name);
                    } catch (Exception ignored) { /* 单条记录读不出来不影响分类 */ }
                }
            }
        } catch (Exception error) {
            Log.warn("Civitai 记录读取失败（LoRA 分类用文件名显示）：" + cn.szu.bot.Bot.error(error));
        }
        return new LoraIndex(showcases, displayNames);
    }

    /**
     * 已知的「LoRA 附带」样式名（{@code data/civitai-style-links.json} 的值）。老样式里没有
     * {@code sizeSource}/{@code lora} 标注，靠这份映射也能认出是展示图样式——**只读它，不改它**。
     */
    public Set<String> loraShowcaseNames() { return loraIndex().showcases().keySet(); }

    /** 展示图映射的键 {@code 模型号/版本号/LoRA 文件名/图号} → LoRA 名（去扩展名）；键不像这个格式就是空串。 */
    static String loraOfLinkKey(String key) {
        String[] parts = String.valueOf(key == null ? "" : key).split("/");
        return parts.length >= 3 ? loraStem(parts[2]) : "";
    }

    /** 文件名（可带目录）→ 去目录、去 {@code .safetensors} 的 LoRA 名。 */
    static String loraStem(String path) {
        String text = String.valueOf(path == null ? "" : path).strip().replace('\\', '/');
        int slash = text.lastIndexOf('/');
        if (slash >= 0) text = text.substring(slash + 1);
        return text.replaceFirst("(?i)\\.safetensors$", "").strip();
    }

    /** 展示图样式的名字前缀：{@code DeepSeek 鲸鱼娘 … 1} → {@code DeepSeek 鲸鱼娘 …}；名字不像展示图样式就是空串。 */
    static String namePrefix(String name) {
        String text = String.valueOf(name == null ? "" : name).strip();
        var trailing = TRAILING_NUMBER.matcher(text);
        if (!trailing.matches()) return "";
        String prefix = trailing.group(1).strip();
        // 前缀只有一个字（"图 1"）不当成模型名：普通样式的名字不该被误认成 LoRA。
        return prefix.codePointCount(0, prefix.length()) < 2 ? "" : prefix;
    }

    /** 显示名/分类名里的控制字符清掉（Civitai 与文件名都可能带进来）。 */
    private static String clean(String value) {
        return String.valueOf(value == null ? "" : value).replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
    }

    private static long fileStamp(Path path) {
        try { return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() * 31L + Files.size(path) : 0L; }
        catch (Exception error) { return 0L; }
    }

    private static long directoryStamp(Path directory) {
        try {
            if (!Files.isDirectory(directory)) return 0L;
            long count;
            try (var stream = Files.list(directory)) { count = stream.count(); }
            return Files.getLastModifiedTime(directory).toMillis() * 31L + count;
        } catch (Exception error) { return 0L; }
    }

    /** 这条样式是不是「LoRA 附带」的展示图样式（只按样式自己记着的东西判，不看名字）。 */
    public static boolean loraShowcase(Style style) {
        JsonObject model = style == null ? null : style.model();
        if (model == null) return false;
        if ("preview".equals(Json.str(model, "sizeSource", ""))) return true;
        return !Json.str(model, "lora", "").isBlank();
    }

    /**
     * 这条样式有没有「它是 LoRA 展示图样式」的**实据**（仍不看名字）：{@code model.sizeSource=preview}、
     * {@code model.lora}、{@code model.previewImage}（只有展示图样式会记这个），或者样式名在展示图映射里。
     * 光"名字像"不算实据——普通样式名结尾也可能带数字（{@code 篠森よもぎ 2}），不能因此被分进 LoRA 分类。
     */
    public static boolean loraShowcaseEvidence(Style style, LoraIndex index) {
        if (loraShowcase(style)) return true;
        JsonObject model = style == null ? null : style.model();
        if (model != null && !Json.str(model, "previewImage", "").isBlank()) return true;
        return index != null && index.showcase(style == null ? "" : style.name());
    }

    /** 这条样式记着的归属栈（没有就按底模/检查点名现判；判不出来是空串）。 */
    public static String stackOf(Style style) {
        JsonObject model = style == null ? null : style.model();
        if (model == null) return "";
        String stack = Json.str(model, "stack", "");
        return stack.isBlank() ? StackClassifier.stackOf(Json.str(model, "baseModel", ""), Json.str(model, "checkpoint", "")) : stack;
    }

    /** 归属栈 → 分类（{@code stack:xl} / {@code SDXL 栈}；栈为空就是「未分类」）。 */
    public static Classification stackCategory(String stack) {
        String value = stack == null ? "" : stack.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return noneCategory();
        String label = StackClassifier.stackLabel(value);
        return new Classification(KIND_STACK + ":" + value, label.isBlank() ? OTHER_CATEGORY : label, KIND_STACK);
    }

    /** 一个 LoRA 的分类（key 前缀 {@code lora:}，名字就是 LoRA 的显示名）。 */
    public static Classification loraCategory(String name) {
        String value = clean(name);
        return value.isEmpty() ? loraCategory(LORA_CATEGORY)
                : new Classification(KIND_LORA + ":" + value, value, KIND_LORA);
    }

    /** 用户手动设的分类（key 前缀 {@code manual:}）。 */
    public static Classification manualCategory(String name) {
        String value = name == null ? "" : name.strip();
        return new Classification(KIND_MANUAL + ":" + value, value, KIND_MANUAL);
    }

    /** 「未分类」（key 固定 {@code none}，永远排在最后）。 */
    public static Classification noneCategory() { return new Classification(NONE_KEY, OTHER_CATEGORY, KIND_NONE); }

    /**
     * 这条展示图样式属于哪个 LoRA（显示名）；判不出具体 LoRA 就是空串。判据按可靠性排序：
     * <ol>
     *   <li>{@code model.lora}——生成展示图样式时写进去的 LoRA 名；</li>
     *   <li>{@code data/civitai-style-links.json} 里映射到这条样式名的记录（键里带 LoRA 文件名）；</li>
     *   <li>样式名前缀——展示图样式名一律是 {@code <模型名> <图号>}，去掉尾号就是模型名（LoRA 名或 model_name）。
     *       这一条只在**确认它确实是展示图样式**（见 {@link #loraShowcaseEvidence}）时才用，免得把普通样式
     *       按名字误分进 LoRA 分类。</li>
     * </ol>
     * 判出来的标识还会查一次 Civitai 记录里的 {@code model_name}：有就用那个更好看、也和样式名前缀一致的写法。
     *
     * @param index 只读外部数据（{@link #loraIndex()}）；可为 null（此时不查映射、不做显示名替换）
     */
    public static String loraName(Style style, LoraIndex index) {
        if (style == null) return "";
        JsonObject model = style.model();
        String id = clean(model == null ? "" : Json.str(model, "lora", ""));
        if (id.isEmpty() && index != null) id = index.loraOf(style.name());
        if (!id.isEmpty()) return index == null ? id : index.displayName(id);
        if (!loraShowcaseEvidence(style, index)) return "";
        String prefix = clean(namePrefix(style.name()));
        if (prefix.isEmpty()) return "";
        return index == null ? prefix : index.displayName(prefix);
    }

    /**
     * 生效的分类（用户手动设的优先，其次自动规则）。自动规则：
     * 认得出具体 LoRA → 该 LoRA 一个分类（分类名＝LoRA 显示名）；是展示图样式但认不出是哪个 LoRA →
     * {@value #LORA_CATEGORY}；否则按归属栈；都判不出就是 {@value #OTHER_CATEGORY}。
     */
    public static Classification classify(Style style, LoraIndex index) {
        if (style == null) return noneCategory();
        // 手动分类优先（老版本自动写下的「LoRA 附带」在 Style 里已经归一成"没设过"）。
        if (style.hasCategory()) return manualCategory(style.category());
        String lora = loraName(style, index);
        if (!lora.isEmpty()) return loraCategory(lora);
        if (loraShowcaseEvidence(style, index)) return loraCategory(LORA_CATEGORY);
        String stack = stackOf(style);
        return stack.isEmpty() ? noneCategory() : stackCategory(stack);
    }

    /** 生效的分类（用本机 {@code data/} 下的只读数据补齐老样式的判断）。 */
    public Classification classify(Style style) { return classify(style, loraIndex()); }

    /** 生效的分类名（人看的名字：LoRA 名 / {@code SDXL 栈} / 手动分类名 / {@value #OTHER_CATEGORY}）。 */
    public String categoryOf(Style style) { return classify(style).name(); }

    /**
     * 分类清单，顺序：<b>lora 组</b>（count 降序，同 count 按名字升序）→ <b>stack 组</b>（固定栈序）→
     * <b>manual 组</b>（count 降序，同 count 按名字升序）→ {@code none} 永远最后
     * （哪怕 0 条也给出这一组：前端要有「未分类」这个落脚点）。**只读地现算，不落盘。**
     */
    public synchronized List<Group> categoryGroups() {
        LoraIndex index = loraIndex();
        Map<String, Group> groups = new LinkedHashMap<>();
        int unclassified = 0;
        for (Style style : all()) {
            Classification classification = classify(style, index);
            if (NONE_KEY.equals(classification.key())) { unclassified++; continue; }
            Group group = new Group(classification.key(), classification.name(), classification.kind(), 1);
            groups.merge(classification.key(), group,
                    (left, right) -> new Group(left.key(), left.name(), left.kind(), left.count() + right.count()));
        }
        List<Group> lora = new ArrayList<>(), stack = new ArrayList<>(), manual = new ArrayList<>();
        for (Group group : groups.values()) {
            if (KIND_LORA.equals(group.kind())) lora.add(group);
            else if (KIND_STACK.equals(group.kind())) stack.add(group);
            else manual.add(group);
        }
        lora.sort(byCountThenName());
        manual.sort(byCountThenName());
        stack.sort(Comparator.comparingInt((Group group) -> stackRank(group.key())).thenComparing(Group::name));
        List<Group> result = new ArrayList<>(lora);
        result.addAll(stack);
        result.addAll(manual);
        result.add(new Group(NONE_KEY, OTHER_CATEGORY, KIND_NONE, unclassified));
        return List.copyOf(result);
    }

    private static Comparator<Group> byCountThenName() {
        return Comparator.comparingInt((Group group) -> -group.count())
                .thenComparing(Group::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Group::key);
    }

    /** 栈在固定顺序里的位置（不在表里的栈排到最后，内部再按名字）。 */
    private static int stackRank(String key) {
        String text = key == null ? "" : key;
        int colon = text.indexOf(':');
        int at = STACK_ORDER.indexOf(colon < 0 ? text : text.substring(colon + 1));
        return at < 0 ? STACK_ORDER.size() : at;
    }

    public synchronized List<String> names() {
        List<String> names = new ArrayList<>();
        for (Style style : all()) names.add(style.name());
        return List.copyOf(names);
    }

    /** 精确匹配优先，其次忽略大小写与首尾空格；找不到返回 null。 */
    public synchronized Style get(String name) {
        if (name == null || name.isBlank()) return null;
        String wanted = name.strip();
        for (Style style : all()) if (style.name().equals(wanted)) return style;
        for (Style style : all()) if (style.name().equalsIgnoreCase(wanted)) return style;
        return null;
    }

    public synchronized boolean contains(String name) { return get(name) != null; }

    public synchronized Style save(String name, String positive, String negative, boolean overwrite) throws IOException {
        return save(name, positive, negative, overwrite, null);
    }

    /** 不带分类的保存（网页「保存样式」、导入这类：分类留空，按默认规则归组）。 */
    public synchronized Style save(String name, String positive, String negative, boolean overwrite, JsonObject model) throws IOException {
        return save(name, positive, negative, overwrite, model, "");
    }

    /**
     * 保存样式（可带保存时的模型参数与分类）。
     *
     * <p>带参数的样式在使用时会把底模/采样/步数/CFG/尺寸一起套上去——Anima 这类「一栈一栈」的模型
     * 必须连参数一起记住，否则载入样式后拿旧栈的参数出图会出废图。
     *
     * <p><b>分类</b>：{@code category} 只用于**新建**的样式；覆盖已有样式时**保留它原来的分类**——
     * 用户手动改过的分类绝不被保存/同步悄悄改回去。展示图样式不要再传分类（留空即按"一个 LoRA 一个分类"
     * 的规则自动分组）。
     */
    public synchronized Style save(String name, String positive, String negative, boolean overwrite, JsonObject model,
                                  String category) throws IOException {
        String requested = SdClient.styleSaveName(name);
        List<Style> styles = new ArrayList<>(all());
        int at = indexOf(styles, requested);
        if (at >= 0 && !overwrite)
            throw new IOException("已有同名样式「" + styles.get(at).name() + "」；确认覆盖请用 .style overwrite " + styles.get(at).name()
                    + "（.style list 查看全部样式）");
        String kept = at >= 0 ? styles.get(at).category() : (category == null ? "" : categoryName(category));
        Style saved = new Style(at >= 0 ? styles.get(at).name() : requested,
                Objects.requireNonNullElse(positive, ""), Objects.requireNonNullElse(negative, ""), Instant.now().toString(),
                generationModel(model), kept);
        // 覆盖时保持原位置：编号与批量操作（#6-#9）依靠列表顺序稳定，改名/覆盖不该把后面的项整体挪位。
        if (at >= 0) styles.set(at, saved); else styles.add(saved);
        write(styles);
        return saved;
    }

    /**
     * 落盘前把样式记着的尺寸换成**能直接用于生成的**尺寸（展示图样式的关键一处）。
     *
     * <p>展示图的真实像素动辄 2400×3744，以前原样记进 {@code width/height}，载入时被
     * {@code validateSize}（64–2048 且 8 的倍数）拒掉，于是"尺寸不跟着样式走"：
     * <ul>
     *   <li>{@code width/height}：按 {@link SdClient#fitGenerationSize} 换算后的**生成尺寸**
     *       （本来就合法的值原样保留，用户特意设的 768×512 不会被改）；</li>
     *   <li>{@code previewWidth/previewHeight}：展示图的**真实像素**，只在 {@code sizeSource=preview}
     *       时记，留给界面显示"这张图本身多大"；{@code sizeSource} 一个字都不动，界面照旧标「展示图尺寸」。</li>
     * </ul>
     * 老文件里已经存了超限值也不用迁移：载入侧会现算（{@code SdClient.applyModelParams}）。
     *
     * @return 一份新的 model（不改调用方对象）；model 为 null 时返回 null
     */
    public static JsonObject generationModel(JsonObject model) {
        if (model == null) return null;
        JsonObject result = model.deepCopy();
        int width = Json.num(result, "width", 0), height = Json.num(result, "height", 0);
        if (width <= 0 || height <= 0) return result;
        int[] fitted = SdClient.fitGenerationSize(width, height);
        if (fitted == null) return result;
        if ("preview".equals(Json.str(result, "sizeSource", ""))) {
            // 真实像素只记一次：覆盖保存时进来的 model 可能已经带着换算过的值，
            // 不能被生成尺寸顶替掉（那样界面上就再也看不到展示图本身多大了）。
            if (!result.has("previewWidth")) result.addProperty("previewWidth", width);
            if (!result.has("previewHeight")) result.addProperty("previewHeight", height);
        }
        result.addProperty("width", fitted[0]);
        result.addProperty("height", fitted[1]);
        return result;
    }

    /**
     * 手动设置分类（{@code .style category} 与网页「改分类」都走这里）。
     * 空串＝清空手动分类，之后按默认规则归组；{@code updated_at} 不变（分类不是内容变更）。
     */
    public synchronized Style setCategory(String name, String category) throws IOException {
        Style source = get(name);
        if (source == null) throw new IOException("没有这个样式：" + name + "。用 .style list 查看全部样式。");
        String value = category == null || category.isBlank() ? "" : categoryName(category);
        List<Style> styles = new ArrayList<>(all());
        int at = indexOf(styles, source.name());
        if (at < 0) throw new IOException("没有这个样式：" + name + "。用 .style list 查看全部样式。");
        Style updated = new Style(source.name(), source.positive(), source.negative(), source.updatedAt(),
                source.hasModel() ? source.model().deepCopy() : null, value);
        styles.set(at, updated);
        write(styles);
        return updated;
    }

    /**
     * 分类名的规范化与校验：去首尾空格、去掉换行/控制字符，长度 1–{@value #MAX_CATEGORY}。
     * 空串由调用方解释成"清空手动分类"（这里直接放行）；老版本的自动分类名 {@value #LORA_CATEGORY}
     * 不是用户能选的分类，给这个值同样等于"回到自动规则"。
     */
    public static String categoryName(String category) throws IOException {
        String value = category == null ? "" : category.strip();
        if (value.isEmpty()) return "";
        if (value.equals("-") || value.equals("清除") || value.equals("清空")) return "";
        value = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        if (value.isEmpty()) return "";
        if (value.equals(LORA_CATEGORY)) return "";
        if (value.codePointCount(0, value.length()) > MAX_CATEGORY)
            throw new IOException("分类名最长 " + MAX_CATEGORY + " 个字：" + value);
        return value;
    }

    /** 这份 JSON 里的分类字段（老文件没有该字段＝空；**只读，不重写**）。 */
    public static String categoryOf(JsonObject styleItem) {
        return styleItem == null ? "" : Json.str(styleItem, "category", "").strip();
    }

    private static int indexOf(List<Style> styles, String name) {
        for (int index = 0; index < styles.size(); index++)
            if (styles.get(index).name().equalsIgnoreCase(name)) return index;
        return -1;
    }

    /** 3.0 显示成 "3"，3.5 保持 "3.5"（摘要里别出现 4.0 这种零头）。 */
    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * 批量导入（例如把 WebUI 里已有的预设样式搬进本机样式库）。同名时：overwrite 覆盖，否则跳过并计数。
     */
    public synchronized ImportResult importAll(List<Style> incoming, boolean overwrite) throws IOException {
        List<Style> styles = new ArrayList<>(all());
        int imported = 0, skipped = 0;
        for (Style style : incoming) {
            if (style == null || style.name() == null || style.name().isBlank()) continue;
            String requested;
            try { requested = SdClient.styleSaveName(style.name()); }
            catch (IOException invalid) { skipped++; continue; }
            Style existing = null;
            for (Style candidate : styles) if (candidate.name().equalsIgnoreCase(requested)) { existing = candidate; break; }
            if (existing != null && !overwrite) { skipped++; continue; }
            if (existing != null) styles.remove(existing);
            styles.add(new Style(existing != null ? existing.name() : requested,
                    Objects.requireNonNullElse(style.positive(), ""), Objects.requireNonNullElse(style.negative(), ""),
                    Instant.now().toString(),
                    style.hasModel() ? style.model().deepCopy()
                            : (existing != null && existing.hasModel() ? existing.model().deepCopy() : null),
                    // 覆盖导入时保留已有的手动分类；新导入的按默认规则归组。
                    existing != null ? existing.category() : style.category()));
            imported++;
        }
        if (imported > 0) write(styles);
        return new ImportResult(imported, skipped);
    }

    public synchronized Style rename(String oldName, String newName, boolean overwrite) throws IOException {
        Style source = get(oldName);
        if (source == null) throw new IOException("没有这个样式：" + oldName + "。用 .style list 查看全部样式。");
        String requested = SdClient.styleSaveName(newName);
        if (source.name().equalsIgnoreCase(requested)) return source;
        List<Style> styles = new ArrayList<>(all());
        int target = indexOf(styles, requested);
        if (target >= 0 && !overwrite)
            throw new IOException("已有同名样式「" + styles.get(target).name() + "」；加 overwrite 可以覆盖。");
        if (target >= 0) styles.remove(target);
        int at = indexOf(styles, source.name());
        if (at < 0) at = styles.size();
        // 原地改名：列表顺序保持，编号和 #6-#9 这类区间操作才不会错位。
        styles.set(at, new Style(requested, source.positive(), source.negative(), Instant.now().toString(),
                source.hasModel() ? source.model().deepCopy() : null, source.category()));
        write(styles);
        // 网页上那张展示图跟着改名走；覆盖同名样式时旧图先丢掉，别张冠李戴。
        if (target >= 0) StylePreviews.delete(root, requested);
        StylePreviews.rename(root, source.name(), requested);
        return styles.get(at);
    }

    public synchronized Style delete(String name) throws IOException {
        Style target = get(name);
        if (target == null) throw new IOException("没有这个样式：" + name + "。用 .style list 查看全部样式。");
        List<Style> styles = new ArrayList<>(all());
        styles.removeIf(style -> style.name().equals(target.name()));
        write(styles);
        StylePreviews.delete(root, target.name());
        return target;
    }

    public synchronized int count() { return all().size(); }

    /** 全部样式（只读快照）。 */
    public synchronized List<Style> styles() { return all(); }

    private List<Style> all() {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            JsonObject saved = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!saved.has("styles") || !saved.get("styles").isJsonArray()) return List.of();
            List<Style> styles = new ArrayList<>();
            for (JsonElement item : saved.getAsJsonArray("styles")) {
                if (item == null || !item.isJsonObject()) continue;
                JsonObject style = item.getAsJsonObject();
                String name = Json.str(style, "name", "").strip();
                if (name.isEmpty()) continue;
                styles.add(new Style(name, Json.str(style, "positive", ""), Json.str(style, "negative", ""),
                        Json.str(style, "updated_at", ""),
                        style.has("model") && style.get("model").isJsonObject() ? style.getAsJsonObject("model").deepCopy() : null,
                        // 老文件没有 category 字段：读成空（＝未手动分类），既不报错也不重写文件；
                        // 老版本自动写下的「LoRA 附带」在 Style 的构造里也被归一成空。
                        categoryOf(style)));
            }
            return List.copyOf(styles);
        } catch (Exception error) {
            Log.warn("本机样式读取失败，按空处理：" + cn.szu.bot.Bot.error(error));
            return List.of();
        }
    }

    private void write(List<Style> styles) throws IOException {
        JsonArray array = new JsonArray();
        for (Style style : styles) {
            JsonObject item = new JsonObject();
            item.addProperty("name", style.name());
            item.addProperty("positive", style.positive());
            item.addProperty("negative", style.negative());
            item.addProperty("updated_at", style.updatedAt());
            // 只写手动设过的分类：字段缺省＝未手动分类，老文件与"清空分类"都是这个状态。
            if (style.hasCategory()) item.addProperty("category", style.category());
            if (style.hasModel()) item.add("model", style.model().deepCopy());
            array.add(item);
        }
        JsonObject data = new JsonObject();
        data.addProperty("version", VERSION);
        data.add("styles", array);
        Json.atomicWrite(file, data);
    }
}

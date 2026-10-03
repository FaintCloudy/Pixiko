package cn.szu.bot.sd;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
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
 *   <li><b>LoRA 附带的展示图样式一律归到同一个大类</b> {@value #LORA_CATEGORY}
 *       （判据：样式里记着 {@code model.sizeSource=preview}／{@code model.lora}，
 *       或者它在 {@code data/civitai-style-links.json} 里——那是生成展示图样式时留下的映射）；</li>
 *   <li>其它样式按其**归属栈**给默认分类：{@code Anima} / {@code SDXL} / {@code SD 1.5} /
 *       {@code Flux} / {@code Qwen}；</li>
 *   <li>判不出归属栈的就是 {@value #OTHER_CATEGORY}。</li>
 * </ol>
 * 默认规则只在**显示与分组**时现算，不会因为读一次列表就往用户的数据文件里写东西；
 * 老文件没有 {@code category} 字段时一律当成"未手动分类"，既不报错也不重写。
 */
public final class LocalStyles {
    private static final int VERSION = 1;
    /** 「LoRA 附带」大类：下载/补展示图生成的展示图样式都归到这里（用户要求：同一个大类）。 */
    public static final String LORA_CATEGORY = "LoRA 附带";
    /** 判不出归属栈时的默认分类。 */
    public static final String OTHER_CATEGORY = "未分类";
    /** 分类名的长度上限（回执与网页都按字符数算）。 */
    public static final int MAX_CATEGORY = 40;
    /** 分类清单的固定顺序：LoRA 附带 → 各栈 → 未分类 → 其它自定义分类。 */
    private static final List<String> CATEGORY_ORDER = List.of(
            LORA_CATEGORY, "Anima", "SDXL", "SD 1.5", "Flux", "Qwen", OTHER_CATEGORY);

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
            category = category == null ? "" : category.strip();
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
            if (width > 0 && height > 0) parts.add(width + "×" + height + sizeNote(Json.str(model, "sizeSource", "")));
            return String.join("，", parts);
        }
        /** 尺寸来源的中文括号注（展示图尺寸跟预设/当前设置不是一回事，要看得出来）。 */
        private static String sizeNote(String source) {
            return "preview".equals(source) ? "（展示图尺寸）" : "";
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
    /** 「LoRA 附带」样式名的缓存（来自 data/civitai-style-links.json）与它的文件指纹。 */
    private volatile Set<String> showcaseNames = Set.of();
    private volatile long showcaseStamp = Long.MIN_VALUE;

    public LocalStyles(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.file = this.root.resolve("data/local-styles.json");
    }

    /**
     * 已知的「LoRA 附带」样式名：{@code data/civitai-style-links.json} 的值（生成展示图样式时留下的
     * {@code 模型/版本/文件/图号 → 样式名} 映射）。老样式里没有 {@code sizeSource}/{@code lora} 标注，
     * 靠这份映射也能正确归到「LoRA 附带」——**只读它，不改它**。
     */
    public Set<String> loraShowcaseNames() {
        Path links = root.resolve("data/civitai-style-links.json");
        try {
            if (!Files.isRegularFile(links)) return Set.of();
            long stamp = Files.getLastModifiedTime(links).toMillis() * 31L + Files.size(links);
            if (stamp == showcaseStamp) return showcaseNames;
            JsonObject data = Json.parse(Files.readString(links, StandardCharsets.UTF_8));
            Set<String> names = new LinkedHashSet<>();
            for (String key : data.keySet()) {
                JsonElement value = data.get(key);
                if (value != null && value.isJsonPrimitive()) {
                    String name = value.getAsString().strip();
                    if (!name.isEmpty()) names.add(name);
                }
            }
            showcaseNames = Set.copyOf(names);
            showcaseStamp = stamp;
            return showcaseNames;
        } catch (Exception error) {
            Log.warn("展示图样式映射读取失败（分类按默认规则算）：" + cn.szu.bot.Bot.error(error));
            return showcaseNames;
        }
    }

    /** 这条样式是不是「LoRA 附带」的展示图样式（只按样式自己记着的东西判，不看名字）。 */
    public static boolean loraShowcase(Style style) {
        JsonObject model = style == null ? null : style.model();
        if (model == null) return false;
        if ("preview".equals(Json.str(model, "sizeSource", ""))) return true;
        return !Json.str(model, "lora", "").isBlank();
    }

    /** 归属栈 → 默认分类名（Anima / SDXL / SD 1.5 / Flux / Qwen；判不出就是「未分类」）。 */
    public static String stackCategory(String stack) {
        return switch (stack == null ? "" : stack.strip().toLowerCase(java.util.Locale.ROOT)) {
            case StackClassifier.ANIMA -> "Anima";
            case StackClassifier.XL -> "SDXL";
            case StackClassifier.SD -> "SD 1.5";
            case StackClassifier.FLUX -> "Flux";
            case StackClassifier.QWEN -> "Qwen";
            default -> OTHER_CATEGORY;
        };
    }

    /** 这条样式记着的归属栈（没有就按底模/检查点名现判；判不出来是空串）。 */
    public static String stackOf(Style style) {
        JsonObject model = style == null ? null : style.model();
        if (model == null) return "";
        String stack = Json.str(model, "stack", "");
        return stack.isBlank() ? StackClassifier.stackOf(Json.str(model, "baseModel", ""), Json.str(model, "checkpoint", "")) : stack;
    }

    /**
     * 默认分类（用户没手动设过时用它）：LoRA 附带的展示图样式一律 {@value #LORA_CATEGORY}，
     * 其它按归属栈，判不出栈就是 {@value #OTHER_CATEGORY}。
     *
     * @param showcaseNames {@code data/civitai-style-links.json} 里的样式名（可为 null）
     */
    public static String defaultCategory(Style style, Set<String> showcaseNames) {
        if (style == null) return OTHER_CATEGORY;
        if (loraShowcase(style) || (showcaseNames != null && showcaseNames.contains(style.name()))) return LORA_CATEGORY;
        return stackCategory(stackOf(style));
    }

    /** 生效的分类：用户手动设的优先，其次默认规则。 */
    public static String categoryOf(Style style, Set<String> showcaseNames) {
        if (style != null && style.hasCategory()) return style.category();
        return defaultCategory(style, showcaseNames);
    }

    /** 生效的分类（用本机 {@code civitai-style-links.json} 补齐老样式的「LoRA 附带」判定）。 */
    public String categoryOf(Style style) { return categoryOf(style, loraShowcaseNames()); }

    /** 现有样式按分类的计数，顺序固定（LoRA 附带 → 各栈 → 未分类 → 自定义分类按名字）。 */
    public synchronized Map<String, Integer> categoryCounts() {
        Set<String> showcases = loraShowcaseNames();
        Map<String, Integer> raw = new LinkedHashMap<>();
        for (Style style : all()) raw.merge(categoryOf(style, showcases), 1, Integer::sum);
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String category : CATEGORY_ORDER) {
            Integer count = raw.remove(category);
            if (count != null) ordered.put(category, count);
        }
        raw.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
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
     * <p><b>分类</b>：{@code category} 只用于**新建**的样式（例如展示图样式一律「LoRA 附带」）；
     * 覆盖已有样式时**保留它原来的分类**——用户手动改过的分类绝不被保存/同步悄悄改回去。
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
                model == null ? null : model.deepCopy(), kept);
        // 覆盖时保持原位置：编号与批量操作（#6-#9）依靠列表顺序稳定，改名/覆盖不该把后面的项整体挪位。
        if (at >= 0) styles.set(at, saved); else styles.add(saved);
        write(styles);
        return saved;
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
     * 空串由调用方解释成"清空手动分类"（这里直接放行）。
     */
    public static String categoryName(String category) throws IOException {
        String value = category == null ? "" : category.strip();
        if (value.isEmpty()) return "";
        if (value.equals("-") || value.equals("清除") || value.equals("清空")) return "";
        value = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        if (value.isEmpty()) return "";
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
                        // 老文件没有 category 字段：读成空（＝未手动分类），既不报错也不重写文件。
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

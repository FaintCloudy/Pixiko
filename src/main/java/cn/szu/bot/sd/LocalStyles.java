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
 */
public final class LocalStyles {
    private static final int VERSION = 1;
    /** 一个本机样式：保存时的正反向原文与更新时间，外加**保存时的模型参数**（可为空）。 */
    public record Style(String name, String positive, String negative, String updatedAt, JsonObject model) {
        /** 不带模型参数的写法（导入、老数据、纯文本样式）保持原样可用。 */
        public Style(String name, String positive, String negative, String updatedAt) {
            this(name, positive, negative, updatedAt, null);
        }
        /** 有模型参数吗（底模/采样/步数/CFG/尺寸任意一项即可）。 */
        public boolean hasModel() { return model != null && model.size() > 0; }
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
            if (width > 0 && height > 0) parts.add(width + "×" + height);
            return String.join("，", parts);
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

    public LocalStyles(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.file = this.root.resolve("data/local-styles.json");
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

    /**
     * 保存样式（可带保存时的模型参数）。
     *
     * <p>带参数的样式在使用时会把底模/采样/步数/CFG/尺寸一起套上去——Anima 这类「一栈一栈」的模型
     * 必须连参数一起记住，否则载入样式后拿旧栈的参数出图会出废图。
     */
    public synchronized Style save(String name, String positive, String negative, boolean overwrite, JsonObject model) throws IOException {
        String requested = SdClient.styleSaveName(name);
        List<Style> styles = new ArrayList<>(all());
        int at = indexOf(styles, requested);
        if (at >= 0 && !overwrite)
            throw new IOException("已有同名样式「" + styles.get(at).name() + "」；确认覆盖请用 .style overwrite " + styles.get(at).name()
                    + "（.style list 查看全部样式）");
        Style saved = new Style(at >= 0 ? styles.get(at).name() : requested,
                Objects.requireNonNullElse(positive, ""), Objects.requireNonNullElse(negative, ""), Instant.now().toString(),
                model == null ? null : model.deepCopy());
        // 覆盖时保持原位置：编号与批量操作（#6-#9）依靠列表顺序稳定，改名/覆盖不该把后面的项整体挪位。
        if (at >= 0) styles.set(at, saved); else styles.add(saved);
        write(styles);
        return saved;
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
                            : (existing != null && existing.hasModel() ? existing.model().deepCopy() : null)));
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
                source.hasModel() ? source.model().deepCopy() : null));
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
                        style.has("model") && style.get("model").isJsonObject() ? style.getAsJsonObject("model").deepCopy() : null));
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
            if (style.hasModel()) item.add("model", style.model().deepCopy());
            array.add(item);
        }
        JsonObject data = new JsonObject();
        data.addProperty("version", VERSION);
        data.add("styles", array);
        Json.atomicWrite(file, data);
    }
}

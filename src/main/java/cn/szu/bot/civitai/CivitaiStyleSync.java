package cn.szu.bot.civitai;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import cn.szu.bot.Bot;
import cn.szu.bot.Json;
import cn.szu.bot.Main;
import cn.szu.bot.prompt.PromptEditor;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.Settings;

/** Match imported showcase styles by stored ownership or original content, then fix their local LoRA tag. */
public final class CivitaiStyleSync {
    public interface Store {
        List<SdClient.StylePrompt> list() throws Exception;
        void save(String name, String positive, String negative, boolean overwrite) throws Exception;
        /**
         * 连模型参数一起保存（v1.0.10 起展示图样式也要记住底模/采样方法/调度器/步数/CFG/Shift/尺寸）。
         * 老实现可以不覆盖这个方法：那就只是不存模型参数，样式文本照旧。
         */
        default void save(String name, String positive, String negative, boolean overwrite, JsonObject model) throws Exception {
            save(name, positive, negative, overwrite);
        }
        /**
         * 连模型参数与**分类**一起保存（v1.0.12 起展示图样式一律归「LoRA 附带」大类）。
         * 老实现可以不覆盖：那就没有分类，样式照旧可用（默认规则里还会按展示图映射兜底）。
         */
        default void save(String name, String positive, String negative, boolean overwrite, JsonObject model, String category) throws Exception {
            save(name, positive, negative, overwrite, model);
        }
        /** 这条样式现在记着的模型参数（没有就 null）；用来判断"内容没变但缺参数"要不要补写。 */
        default JsonObject model(String name) throws Exception { return null; }
    }
    /** 展示图样式统一的大类（与本机样式库的默认规则同一个词）。 */
    public static final String LORA_CATEGORY = cn.szu.bot.sd.LocalStyles.LORA_CATEGORY;
    /** 这几个键只是「LoRA 附带」的标注，不是模型参数：比"要不要补写"时忽略（否则永远算"变了"）。 */
    private static final Set<String> ANNOTATION_KEYS = Set.of("lora", "origin", "previewImage", "sizeSource");
    private static final Pattern LORA = Pattern.compile("<lora:[^<>]*>", Pattern.CASE_INSENSITIVE);
    /** 展示图样式写进机器人自己的样式库（data/local-styles.json），不再写 WebUI 的预设样式。 */
    private static Store store(Path root) {
        cn.szu.bot.sd.LocalStyles local = new cn.szu.bot.sd.LocalStyles(root);
        return new Store() {
            public List<SdClient.StylePrompt> list() {
                List<SdClient.StylePrompt> styles = new ArrayList<>();
                for (cn.szu.bot.sd.LocalStyles.Style style : local.styles())
                    styles.add(new SdClient.StylePrompt(style.name(), style.positive(), style.negative()));
                return styles;
            }
            public void save(String name, String positive, String negative, boolean overwrite) throws Exception {
                local.save(name, positive, negative, overwrite);
            }
            public void save(String name, String positive, String negative, boolean overwrite, JsonObject model) throws Exception {
                local.save(name, positive, negative, overwrite, model);
            }
            public void save(String name, String positive, String negative, boolean overwrite, JsonObject model, String category) throws Exception {
                local.save(name, positive, negative, overwrite, model, category);
            }
            public JsonObject model(String name) {
                cn.szu.bot.sd.LocalStyles.Style style = local.get(name);
                return style == null ? null : style.model();
            }
        };
    }
    public static String plain(String prompt) {
        String removed = LORA.matcher(prompt).replaceAll("");
        // Keep arbitrary Civitai wording/weights; only clean empty top-level comma items when parsable.
        try { return String.join(", ", PromptEditor.parts(removed)); }
        catch (IllegalArgumentException e) { return removed.strip().replaceAll("^[,，\\s]+|[,，\\s]+$", ""); }
    }
    public static String correct(String prompt, String tag) {
        if (!tag.matches("<lora:[^<>:\\r\\n]+:[0-9.]+>")) throw new IllegalArgumentException("未确认有效的本机 LoRA 标签。");
        String text = plain(prompt); return text.isBlank() ? tag : text + ", " + tag;
    }
    public static String prefix(String model) {
        String result = model.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ").strip();
        if (result.startsWith("#")) result = "模型 " + result;
        if (result.codePointCount(0, result.length()) > 180) result = result.substring(0, result.offsetByCodePoints(0, 180));
        return result;
    }
    public static String sync(Path root, CivitaiClient.DownloadedLora download, String tag, SdClient sd, boolean create) {
        return sync(root, download, tag, sd, create, null);
    }
    public static String sync(Path root, CivitaiClient.DownloadedLora download, String tag, SdClient sd, boolean create, CivitaiClient client) {
        return sync(root, download, tag, sd, create, client, null);
    }
    /**
     * @param model 这份样式要记下的模型参数（调用方已按优先级识别底模）；null 表示按
     *              {@code download.baseModel()} 现算一份。给了 sd 才算得出来，sd 为 null 就不记参数。
     */
    public static String sync(Path root, CivitaiClient.DownloadedLora download, String tag, SdClient sd, boolean create,
                              CivitaiClient client, JsonObject model) {
        JsonObject params = model != null ? model : styleModel(sd, download.baseModel(), SdClient.CIVITAI_SOURCE);
        return sync(root, download, tag, store(root), create, client, params);
    }
    /** 与上一版同一条路径，只是把计数也返回（「补展示图」要报补了几条尺寸）。 */
    public static Outcome run(Path root, CivitaiClient.DownloadedLora download, String tag, SdClient sd, boolean create,
                              CivitaiClient client, JsonObject model) {
        JsonObject params = model != null ? model : styleModel(sd, download.baseModel(), SdClient.CIVITAI_SOURCE);
        return run(root, download, tag, store(root), create, client, params);
    }
    /**
     * 展示图样式的模型参数：底模用这个 LoRA 的（Civitai 记录），采样方法/步数/CFG/Shift/尺寸用当前
     * Forge 预设栈（没有预设就用机器人当前设置）。算不出来就返回 null（样式只存提示词，照旧可用）。
     */
    private static JsonObject styleModel(SdClient sd, String baseModel, String source) {
        if (sd == null) return null;
        try {
            JsonObject model = sd.styleModelParams(baseModel, source);
            return model.size() == 0 ? null : model;
        } catch (Exception error) {
            cn.szu.bot.Log.warn("读取样式模型参数失败（样式只存提示词）：" + Bot.error(error));
            return null;
        }
    }
    public static String sync(Path root, CivitaiClient.DownloadedLora download, String tag, Store store, boolean create) {
        return sync(root, download, tag, store, create, null, null);
    }
    public static String sync(Path root, CivitaiClient.DownloadedLora download, String tag, Store store, boolean create, CivitaiClient client) {
        return sync(root, download, tag, store, create, client, null);
    }
    /** 展示图样式记的尺寸来源标记：宽高来自这张展示图**自己的像素**，不是预设/当前设置。 */
    public static final String PREVIEW_SIZE_SOURCE = "preview";
    /** 一次展示图样式同步的结果：各类计数 + 给用户看的回执（「补展示图」要报补了几条尺寸）。 */
    public record Outcome(int saved, int corrected, int reused, int skipped, int failed, int previews, int params,
                          int sized, String text) { }
    /**
     * @param model 每条展示图样式要一起写进样式库的模型参数（底模 + 采样方法/调度器/步数/CFG/Shift/尺寸）；
     *              null 表示这次不写参数（老调用方、离线计划）。
     */
    public static String sync(Path root, CivitaiClient.DownloadedLora download, String tag, Store store, boolean create,
                              CivitaiClient client, JsonObject model) {
        return run(root, download, tag, store, create, client, model).text();
    }
    /** 与 {@link #sync} 同一件事，只是把计数也一并返回（例如「补展示图」要报补了几条尺寸）。 */
    public static Outcome run(Path root, CivitaiClient.DownloadedLora download, String tag, Store store, boolean create,
                              CivitaiClient client, JsonObject model) {
        if (download.showcases().isEmpty()) return new Outcome(0, 0, 0, 0, 0, 0, 0, 0, "展示图样式：没有可用展示图元数据。");
        int saved = 0, corrected = 0, reused = 0, skipped = 0, failed = 0, previews = 0, params = 0, sized = 0;
        List<String> lines = new ArrayList<>();
        try {
            Path linksFile = root.resolve("data/civitai-style-links.json");
            JsonObject links = Files.exists(linksFile) ? Json.parse(Files.readString(linksFile)) : new JsonObject();
            Map<String, SdClient.StylePrompt> catalog = new LinkedHashMap<>();
            for (var style : store.list()) catalog.put(style.name(), style);
            Set<String> claimed = new HashSet<>();
            String prefix = prefix(download.modelName());
            if (prefix.isEmpty()) prefix = "模型" + download.modelId();
            String lora = loraStem(download.path());
            for (var image : download.showcases()) {
                if (!image.skippedReason().isEmpty()) { skipped++; continue; }
                String key = download.modelId() + "/" + download.versionId() + "/" + download.path().getFileName() + "/" + image.number();
                String name = create && links.has(key) ? links.get(key).getAsString() : null;
                if (name == null || !catalog.containsKey(name)) {
                    String base = prefix + " ";
                    var matches = catalog.values().stream().filter(value -> value.name().startsWith(base)
                            && value.name().substring(base.length()).matches("[0-9]+") && !claimed.contains(value.name())
                            && plain(value.positive()).equals(plain(image.positive())) && value.negative().equals(image.negative())).toList();
                    String preferred = prefix + " " + image.number();
                    name = matches.stream().anyMatch(value -> value.name().equals(preferred)) ? preferred
                            : matches.size() == 1 ? matches.get(0).name() : null;
                    if (name == null) {
                        if (!create) { skipped++; continue; }
                        int number = image.number(); name = prefix + " " + number;
                        while (catalog.containsKey(name) || claimed.contains(name)) name = prefix + " " + (++number);
                    }
                }
                claimed.add(name);
                try {
                    var old = catalog.get(name);
                    String positive = correct(old == null ? image.positive() : old.positive(), tag);
                    String negative = old == null ? image.negative() : old.negative();
                    JsonObject previous = old == null ? null : store.model(name);
                    // 展示图自己的像素尺寸：优先从刚抓到的字节读，其次读已经存下来的预览图。
                    byte[] cover = fetchCover(root, name, image, client);
                    if (cover != null) previews++;
                    int[] size = cover != null ? cn.szu.bot.sd.ImageSize.of(cover)
                            : cn.szu.bot.sd.ImageSize.of(cn.szu.bot.sd.StylePreviews.file(root, name));
                    // 这次没有新参数（老调用方/离线计划）就保留这条样式原来记着的，别把已有的覆盖成空。
                    JsonObject base = model != null ? model : previous;
                    JsonObject recorded = applyPreview(base, lora, size, previewPath(root, name));
                    boolean textSame = old != null && old.positive().equals(positive);
                    boolean sizeFilled = size != null && !sameSize(previous, size);
                    // 内容没变但缺模型参数（v1.0.10 之前存的展示图样式就是这样）也要补写一次，
                    // 否则"下载 LoRA 自动带上底模"对老样式永远不生效。
                    if (textSame && sameModel(previous, recorded)) reused++;
                    else {
                        if (old != null && !textSame) {
                            JsonObject backup = new JsonObject(); backup.addProperty("name", name);
                            backup.addProperty("positive", old.positive()); backup.addProperty("negative", old.negative());
                            backup.addProperty("replacement_tag", tag);
                            Json.atomicWrite(root.resolve("data/civitai-style-backups/" + UUID.randomUUID() + ".json"), backup);
                        }
                        // 展示图样式一律归到同一个大类「LoRA 附带」（用户手动改过的分类在 save 里会被保留）。
                        store.save(name, positive, negative, old != null, recorded, LORA_CATEGORY);
                        if (old == null) saved++;
                        else if (!textSame) corrected++;
                        else if (sizeFilled) sized++;
                        else params++;
                        catalog.put(name, new SdClient.StylePrompt(name, positive, negative));
                    }
                    if (create) { links.addProperty(key, name); Json.atomicWrite(linksFile, links); }
                    lines.add("展示图 " + image.number() + " → " + name + (size == null ? "" : "（" + size[0] + "×" + size[1] + "）"));
                } catch (Exception e) { failed++; lines.add("样式 " + name + " 处理失败：" + Bot.error(e)); }
            }
        } catch (Exception e) {
            return new Outcome(saved, corrected, reused, skipped, failed, previews, params, sized, "展示图样式处理失败：" + Bot.error(e));
        }
        String text = "展示图样式：新增 " + saved + "，修正 " + corrected + "，复用 " + reused + "，跳过 " + skipped + "，失败 " + failed
                + (params == 0 ? "" : "，补模型参数 " + params)
                + (sized == 0 ? "" : "，补展示图尺寸 " + sized)
                + (previews == 0 ? "" : "，预览图 " + previews)
                + (model == null || model.size() == 0 ? "" : "。\n模型参数：" + modelText(model))
                + "。\n本机标签：" + tag + (lines.isEmpty() ? "" : "\n" + String.join("\n", lines));
        return new Outcome(saved, corrected, reused, skipped, failed, previews, params, sized, text);
    }

    /** LoRA 文件名（去扩展名）：作为「LoRA 附带」的细分标签记进样式（大类仍是 {@link #LORA_CATEGORY}）。 */
    static String loraStem(Path path) {
        if (path == null || path.getFileName() == null) return "";
        return path.getFileName().toString().replaceFirst("(?i)\\.safetensors$", "").strip();
    }

    /** 预览图在机器人根目录下的相对路径（记进样式方便核对；图本身**不复制**）。读不到就是空串。 */
    static String previewPath(Path root, String name) {
        Path file = cn.szu.bot.sd.StylePreviews.file(root, name);
        if (file == null || !Files.isRegularFile(file)) return "";
        try {
            Path base = root.toAbsolutePath().normalize();
            Path absolute = file.toAbsolutePath().normalize();
            return (absolute.startsWith(base) ? base.relativize(absolute) : absolute).toString().replace('\\', '/');
        } catch (Exception error) { return ""; }
    }

    /**
     * 把「LoRA 附带」的标注与**展示图的实际尺寸**写进样式参数。
     *
     * <p>尺寸优先级：**展示图自己的像素**（{@code sizeSource=preview}）＞ 预设栈/机器人当前设置——
     * 后者已经在 {@code model} 里了，这里只在读得到展示图尺寸时覆盖；读不到就原样留着，
     * **绝不编一个尺寸**。{@code lora} / {@code previewImage} 只是标注，不参与"要不要补写"的比较。
     */
    public static JsonObject applyPreview(JsonObject model, String loraStem, int[] size, String previewPath) {
        JsonObject result = model == null ? new JsonObject() : model.deepCopy();
        if (loraStem != null && !loraStem.isBlank()) result.addProperty("lora", loraStem);
        if (previewPath != null && !previewPath.isBlank()) result.addProperty("previewImage", previewPath);
        if (size != null && size.length == 2 && size[0] > 0 && size[1] > 0) {
            result.addProperty("width", size[0]);
            result.addProperty("height", size[1]);
            result.addProperty("sizeSource", PREVIEW_SIZE_SOURCE);
        }
        return result;
    }

    /** 样式里记着的尺寸是不是就是这张展示图的尺寸。 */
    private static boolean sameSize(JsonObject previous, int[] size) {
        return previous != null && Json.num(previous, "width", 0) == size[0] && Json.num(previous, "height", 0) == size[1];
    }

    /** 两份模型参数是不是一样（有一边为空就按"空"比；只看键值，不比顺序；标注键不参与比较）。 */
    static boolean sameModel(JsonObject left, JsonObject right) {
        Set<String> keys = new LinkedHashSet<>();
        if (left != null) keys.addAll(left.keySet());
        if (right != null) keys.addAll(right.keySet());
        keys.removeAll(ANNOTATION_KEYS);
        for (String key : keys) if (!modelValue(left, key).equals(modelValue(right, key))) return false;
        return true;
    }

    private static String modelValue(JsonObject model, String key) {
        JsonElement value = model == null ? null : model.get(key);
        if (value == null || value.isJsonNull()) return "";
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    /** 回执里那一行"这次给样式记了什么参数"（底模在最前面，来源也带上）。 */
    static String modelText(JsonObject model) {
        List<String> parts = new ArrayList<>();
        for (String key : List.of("baseModel", "checkpoint")) {
            String value = Json.str(model, key, "");
            if (!value.isBlank()) parts.add((key.equals("baseModel") ? "底模 " : "检查点 ") + value);
        }
        String source = Json.str(model, "baseModelSource", "");
        if (!source.isBlank()) parts.add(source.equals(SdClient.PRESET_SOURCE) ? "按当前预设推断"
                : source.equals(SdClient.FORGE_SOURCE) ? "来自 Forge 元数据"
                : source.equals(SdClient.CIVITAI_SOURCE) ? "来自 Civitai 记录" : source);
        for (String key : List.of("sampler", "scheduler")) {
            String value = Json.str(model, key, "");
            if (!value.isBlank()) parts.add(value);
        }
        int steps = Json.num(model, "steps", 0);
        if (steps > 0) parts.add(steps + " 步");
        double cfg = Json.decimal(model, "cfg", 0);
        if (cfg > 0) parts.add("CFG " + (cfg == Math.rint(cfg) ? String.valueOf((long) cfg) : String.valueOf(cfg)));
        double distilled = Json.decimal(model, "distilledCfg", 0);
        if (distilled > 0) parts.add("Shift " + (distilled == Math.rint(distilled) ? String.valueOf((long) distilled) : String.valueOf(distilled)));
        int width = Json.num(model, "width", 0), height = Json.num(model, "height", 0);
        if (width > 0 && height > 0)
            parts.add(width + "×" + height + (PREVIEW_SIZE_SOURCE.equals(Json.str(model, "sizeSource", "")) ? "（展示图尺寸）" : ""));
        return String.join("，", parts);
    }

    /**
     * 拿这张展示图的字节：**已经有预览图就返回 null**（尺寸直接从那文件读，不必再抓一次）；
     * 没有就抓一张存成样式预览图并返回字节。抓不到也只是少一张图，绝不影响样式本身——
     * 所以这里吞掉异常、只记一条日志。
     */
    private static byte[] fetchCover(Path root, String name, CivitaiClient.ShowcasePrompt image, CivitaiClient client) {
        if (client == null || image.cover().isEmpty() || cn.szu.bot.sd.StylePreviews.has(root, name)) return null;
        try {
            byte[] bytes = client.cover(image.cover()).bytes();
            cn.szu.bot.sd.StylePreviews.save(root, name, bytes);
            return bytes;
        } catch (Exception error) {
            cn.szu.bot.Log.warn("样式预览图抓取失败（" + name + "）：" + Bot.error(error));
            return null;
        }
    }
    public static String migrate(Path root, SdClient sd) throws Exception {
        List<String> reports = new ArrayList<>(); Path directory = root.resolve("data/civitai");
        if (!Files.isDirectory(directory)) return "没有历史 Civitai 下载记录。";
        List<SdClient.StylePrompt> catalog = store(root).list();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonObject record = Json.parse(Files.readString(file));
                try {
                    String prefix = prefix(record.get("model_name").getAsString()) + " ";
                    List<CivitaiClient.ShowcasePrompt> images = new ArrayList<>();
                    for (var style : catalog) if (style.name().startsWith(prefix)
                            && style.name().substring(prefix.length()).matches("[0-9]+"))
                        images.add(new CivitaiClient.ShowcasePrompt(Integer.parseInt(style.name().substring(prefix.length())),
                                style.positive(), style.negative(), true, ""));
                    if (images.isEmpty()) continue;
                    Path path = Path.of(record.get("path").getAsString());
                    if (!Files.isRegularFile(path)) {
                        String filename = path.getFileName().toString();
                        List<SdClient.Lora> matches = sd.loras().stream().filter(lora -> Path.of(lora.path()).getFileName().toString().equalsIgnoreCase(filename)).toList();
                        if (matches.size() == 1) path = Path.of(matches.get(0).path());
                    }
                    var download = new CivitaiClient.DownloadedLora(record.get("model_name").getAsString(), "",
                            Json.str(record, "base_model", ""), List.of(), path, true,
                            record.get("model_id").getAsLong(), record.get("version_id").getAsLong(), images);
                    reports.add(download.modelName() + "\n" + sync(root, download, sd.resolvedLoraTag(path, 1), sd, false));
                } catch (Exception e) { reports.add(file.getFileName() + "：" + Bot.error(e)); }
            }
        }
        return String.join("\n\n", reports);
    }
    public static String syncLoaded(Path root, SdClient sd, SdClient.LoadedLora loaded) throws Exception {
        return syncLoaded(root, sd, loaded, null);
    }
    public static String syncLoaded(Path root, SdClient sd, SdClient.LoadedLora loaded, CivitaiClient client) throws Exception {
        Path directory = root.resolve("data/civitai"); List<String> reports = new ArrayList<>();
        if (!Files.isDirectory(directory)) return "";
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject record = Json.parse(Files.readString(file)); Path path = Path.of(record.get("path").getAsString());
                String filename = path.getFileName().toString();
                if (!filename.equalsIgnoreCase(loaded.name() + ".safetensors") || !record.has("showcase_prompts")) continue;
                var images = Arrays.asList(Json.GSON.fromJson(record.get("showcase_prompts"), CivitaiClient.ShowcasePrompt[].class));
                reports.add(sync(root, new CivitaiClient.DownloadedLora(record.get("model_name").getAsString(), "",
                        Json.str(record, "base_model", ""), List.of(), path, true,
                        record.get("model_id").getAsLong(), record.get("version_id").getAsLong(), images), loaded.tag(), sd, true, client));
            }
        }
        return String.join("\n", reports);
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("bot.home", ".")).toAbsolutePath();
        if (args.length == 2 && args[0].equals("--offline-plan")) {
            JsonObject input = Json.parse(Files.readString(Path.of(args[1])));
            List<SdClient.Lora> loras = Arrays.asList(Json.GSON.fromJson(input.get("loras"), SdClient.Lora[].class));
            Map<String, SdClient.StylePrompt> styles = new LinkedHashMap<>();
            for (var style : Json.GSON.fromJson(input.get("styles"), SdClient.StylePrompt[].class)) styles.put(style.name(), style);
            List<String> reports = new ArrayList<>();
            Store memory = new Store() {
                public List<SdClient.StylePrompt> list() { return List.copyOf(styles.values()); }
                public void save(String name, String positive, String negative, boolean overwrite) { styles.put(name, new SdClient.StylePrompt(name, positive, negative)); }
            };
            try (var files = Files.list(root.resolve("data/civitai"))) {
                for (Path recordFile : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    JsonObject record = Json.parse(Files.readString(recordFile)); String model = record.get("model_name").getAsString();
                    String prefix = prefix(model) + " "; List<CivitaiClient.ShowcasePrompt> images = new ArrayList<>();
                    for (var style : styles.values()) if (style.name().startsWith(prefix) && style.name().substring(prefix.length()).matches("[0-9]+"))
                        images.add(new CivitaiClient.ShowcasePrompt(Integer.parseInt(style.name().substring(prefix.length())), style.positive(), style.negative(), true, ""));
                    if (images.isEmpty()) continue;
                    Path path = Path.of(record.get("path").getAsString());
                    if (!Files.isRegularFile(path)) {
                        String name = path.getFileName().toString();
                        List<SdClient.Lora> matching = loras.stream().filter(l -> Path.of(l.path()).getFileName().toString().equalsIgnoreCase(name)).toList();
                        if (matching.size() == 1) path = Path.of(matching.get(0).path());
                    }
                    String tag = SdClient.resolvedLoraTag(path, loras, 1);
                    var download = new CivitaiClient.DownloadedLora(model, "", "", List.of(), path, true,
                            record.get("model_id").getAsLong(), record.get("version_id").getAsLong(), images);
                    reports.add(model + "\n" + sync(root, download, tag, memory, false));
                }
            }
            JsonObject output = new JsonObject(); output.add("styles", Json.GSON.toJsonTree(styles.values())); output.addProperty("report", String.join("\n\n", reports));
            Json.atomicWrite(Path.of(args[1] + ".result.json"), output); System.out.println(String.join("\n\n", reports)); return;
        }
        Settings settings = new Settings(root);
        String report = migrate(root, new SdClient(root, Json.obj(settings.snapshot(), "sd")));
        Path output = root.resolve("work/civitai-style-migration-report.txt"); Files.writeString(output, report);
        System.out.println(report);
    }
}

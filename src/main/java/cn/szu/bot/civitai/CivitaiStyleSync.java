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
    }
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
        return sync(root, download, tag, store(root), create, null);
    }
    /**
     * @param client 给了它才会顺手把展示图存成样式的预览图（控制台用）；测试/离线场景传 null，
     *               样式文本照旧同步，只是没有预览图。
     */
    public static String sync(Path root, CivitaiClient.DownloadedLora download, String tag, SdClient sd, boolean create, CivitaiClient client) {
        return sync(root, download, tag, store(root), create, client);
    }
    public static String sync(Path root, CivitaiClient.DownloadedLora download, String tag, Store store, boolean create) {
        return sync(root, download, tag, store, create, null);
    }
    public static String sync(Path root, CivitaiClient.DownloadedLora download, String tag, Store store, boolean create, CivitaiClient client) {
        if (download.showcases().isEmpty()) return "展示图样式：没有可用展示图元数据。";
        int saved = 0, corrected = 0, reused = 0, skipped = 0, failed = 0, previews = 0;
        List<String> lines = new ArrayList<>();
        try {
            Path linksFile = root.resolve("data/civitai-style-links.json");
            JsonObject links = Files.exists(linksFile) ? Json.parse(Files.readString(linksFile)) : new JsonObject();
            Map<String, SdClient.StylePrompt> catalog = new LinkedHashMap<>();
            for (var style : store.list()) catalog.put(style.name(), style);
            Set<String> claimed = new HashSet<>();
            String prefix = prefix(download.modelName());
            if (prefix.isEmpty()) prefix = "模型" + download.modelId();
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
                    if (old != null && old.positive().equals(positive)) reused++;
                    else {
                        if (old != null) {
                            JsonObject backup = new JsonObject(); backup.addProperty("name", name);
                            backup.addProperty("positive", old.positive()); backup.addProperty("negative", old.negative());
                            backup.addProperty("replacement_tag", tag);
                            Json.atomicWrite(root.resolve("data/civitai-style-backups/" + UUID.randomUUID() + ".json"), backup);
                        }
                        store.save(name, positive, negative, old != null);
                        if (old == null) saved++; else corrected++;
                        catalog.put(name, new SdClient.StylePrompt(name, positive, negative));
                    }
                    if (saveStylePreview(root, name, image, client)) previews++;
                    if (create) { links.addProperty(key, name); Json.atomicWrite(linksFile, links); }
                    lines.add("展示图 " + image.number() + " → " + name);
                } catch (Exception e) { failed++; lines.add("样式 " + name + " 处理失败：" + Bot.error(e)); }
            }
        } catch (Exception e) { return "展示图样式处理失败：" + Bot.error(e); }
        return "展示图样式：新增 " + saved + "，修正 " + corrected + "，复用 " + reused + "，跳过 " + skipped + "，失败 " + failed
                + (previews == 0 ? "" : "，预览图 " + previews)
                + "。\n本机标签：" + tag + (lines.isEmpty() ? "" : "\n" + String.join("\n", lines));
    }

    /**
     * 把这张展示图存成样式的预览图（<b>只有控制台会看它</b>）。已经有图就跳过，抓不到也只是少一张图，
     * 绝不影响样式本身——所以这里吞掉异常、只记一条日志。
     */
    private static boolean saveStylePreview(Path root, String name, CivitaiClient.ShowcasePrompt image, CivitaiClient client) {
        if (client == null || image.cover().isEmpty() || cn.szu.bot.sd.StylePreviews.has(root, name)) return false;
        try {
            cn.szu.bot.sd.StylePreviews.save(root, name, client.cover(image.cover()).bytes());
            return true;
        } catch (Exception error) {
            cn.szu.bot.Log.warn("样式预览图抓取失败（" + name + "）：" + Bot.error(error));
            return false;
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
                    var download = new CivitaiClient.DownloadedLora(record.get("model_name").getAsString(), "", "", List.of(), path, true,
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
                reports.add(sync(root, new CivitaiClient.DownloadedLora(record.get("model_name").getAsString(), "", "", List.of(), path, true,
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

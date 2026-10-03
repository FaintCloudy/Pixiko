package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.civitai.CivitaiStyleSync;
import cn.szu.bot.sd.ImageSize;
import cn.szu.bot.sd.LocalStyles;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.StylePreviews;

/**
 * 样式分类与展示图尺寸：默认分类规则（LoRA 附带样式一个 LoRA 一个分类、其余按归属栈）、
 * 手动/批量改分类与清空、老文件（没有 category 字段）兼容、展示图真实像素尺寸的读取与回退优先级，
 * 以及载入样式时把尺寸一起套回机器人设置。
 *
 * <p>尺寸那一组用的是**真实图片文件**：临时目录里现生成一张 96×64 的 PNG 当展示图。
 */
public final class StyleCategoryTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        try (Fixture f = new Fixture()) {
            defaultCategories(f);
            manualAndBatch(f);
            loraShowcaseCategory(f);
            previewSizes(f);
        }
        System.out.println("StyleCategoryTest: " + assertions + " assertions passed: 默认分类规则、一个 LoRA 一个分类、"
                + "手动/批量改分类与清空、老文件兼容、展示图真实尺寸读取与优先级、载入套用尺寸。");
    }

    /** 默认分类规则：LoRA 附带样式一个 LoRA 一个分类 → 归属栈（Anima/SDXL/SD 1.5/Flux/Qwen）→ 未分类；老文件不报错也不被重写。 */
    private static void defaultCategories(Fixture f) throws Exception {
        f.seed(List.of(
                style("Anima 风格", "a", model("baseModel", "Anima", "stack", "anima", "width", 1024, "height", 1024)),
                style("SDXL 风格", "b", model("baseModel", "NoobAI", "stack", "xl")),
                style("SD15 风格", "c", model("checkpoint", "foo_sd15.safetensors")),
                style("Flux 风格", "d", model("stack", "flux")),
                style("没底模 风格", "e", null)));
        Path file = f.root.resolve("data/local-styles.json");
        String before = Files.readString(file, StandardCharsets.UTF_8);
        LocalStyles styles = new LocalStyles(f.root);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Anima 风格", "Anima 栈");
        expected.put("SDXL 风格", "SDXL 栈");
        expected.put("SD15 风格", "SD 1.5 栈");
        expected.put("Flux 风格", "Flux 栈");
        expected.put("没底模 风格", LocalStyles.OTHER_CATEGORY);
        for (Map.Entry<String, String> entry : expected.entrySet())
            equal(entry.getValue(), styles.categoryOf(styles.get(entry.getKey())), entry.getKey() + " 的默认分类");
        // 分类清单顺序固定：各栈按固定栈序 → 未分类永远最后（这里没有 LoRA 附带样式与 Qwen）。
        List<String> names = new ArrayList<>();
        for (LocalStyles.Group group : styles.categoryGroups()) names.add(group.name());
        equal(List.of("Anima 栈", "SDXL 栈", "SD 1.5 栈", "Flux 栈", LocalStyles.OTHER_CATEGORY),
                names, "分类清单的顺序");
        equal(List.of("stack:anima", "stack:xl", "stack:sd", "stack:flux", LocalStyles.NONE_KEY),
                new ArrayList<>(groupKeys(styles)), "分类清单的 key");
        equal(before, Files.readString(file, StandardCharsets.UTF_8), "老文件（没有 category 字段）读多少次都不报错、不重写");
        check(!before.contains("category"), "老文件里本来就没有 category 字段：" + before.substring(0, Math.min(80, before.length())));
    }

    private static List<String> groupKeys(LocalStyles styles) {
        List<String> keys = new ArrayList<>();
        for (LocalStyles.Group group : styles.categoryGroups()) keys.add(group.key());
        return keys;
    }

    /** 手动改分类 / 只查不改 / 清空 / 批量（命令与网页接口两条路都要有）。 */
    private static void manualAndBatch(Fixture f) throws Exception {
        f.seed(List.of(
                style("甲", "1", model("stack", "anima")),
                style("乙", "2", model("stack", "xl")),
                style("丙", "3", null),
                style("丁", "4", null)));
        // 命令：改一条
        String changed = f.command(".style category 甲 我的分类");
        check(changed.contains("样式分类") && changed.contains("甲 → 我的分类"), "命令改分类的回执：" + changed);
        check(f.command(".style category 甲").contains("我的分类（手动设置）"), "只给名称时回报当前分类");
        equal("我的分类", f.categoryInFile("甲"), "分类写进了 data/local-styles.json");
        // #编号要能用（编号来自最近一次 .style list）
        f.command(".style list");
        check(f.command(".style list").contains("［我的分类］"), ".style list 里显示分类");
        check(f.command(".style category 甲").contains("我的分类"), "编号之外的名称查询");
        // 批量区间
        String batch = f.command(".style category #2-#3 批量分类");
        check(batch.contains("完成：2 项"), "区间批量改分类：" + batch);
        equal("批量分类", f.categoryInFile("乙"), "批量改分类命中了 #2");
        equal("批量分类", f.categoryInFile("丙"), "批量改分类命中了 #3");
        // 只查不改（不给分类名＝查询）
        String query = f.command(".style category #2");
        check(query.contains("批量分类（手动设置）"), "编号查询：" + query);
        // .style prompt 也显示分类
        check(f.command(".style prompt 乙").contains("分类：批量分类"), ".style prompt 显示分类");
        // 清空 → 回到默认规则
        String cleared = f.command(".style category 甲 -");
        check(cleared.contains("已清空手动分类"), "清空手动分类：" + cleared);
        equal("", f.categoryInFile("甲"), "清空后文件里不再有这一条的分类");
        equal("Anima 栈", f.categoryOf("甲"), "清空后按归属栈回到默认分类");
        // 网页接口：categories 清单 + 单条改分类 + 清空 + 批量
        JsonObject page = f.bot.webStyles();
        check(page.has("categories") && !page.getAsJsonArray("categories").isEmpty(), "/api/styles 返回 categories");
        JsonObject first = page.getAsJsonArray("styles").get(0).getAsJsonObject();
        check(first.has("category") && first.has("categoryAuto") && first.has("categoryKey") && first.has("categorySource"),
                "每条样式带 category/categoryKey/categorySource/categoryAuto");
        JsonObject firstGroup = page.getAsJsonArray("categories").get(0).getAsJsonObject();
        check(firstGroup.has("key") && firstGroup.has("name") && firstGroup.has("kind") && firstGroup.has("count") && firstGroup.has("lora"),
                "categories 每项带 key/name/kind/count/lora：" + firstGroup);
        JsonObject edited = f.bot.webStylesEdit("web", "category", "丁", null, false, false, "网页分类");
        check(edited.get("message").getAsString().contains("丁 → 网页分类"), "网页改分类的回执：" + edited.get("message").getAsString());
        equal("网页分类", f.categoryInFile("丁"), "网页改分类真的落盘");
        Set<String> names = new LinkedHashSet<>();
        for (JsonElement item : edited.getAsJsonArray("categories")) names.add(item.getAsJsonObject().get("name").getAsString());
        check(names.contains("网页分类") && names.contains("批量分类"), "分类清单带上自定义分类：" + names);
        JsonObject bulk = f.bot.webStylesEdit("web", "category", "#1-#2", null, false, false, "网页批量");
        check(bulk.get("message").getAsString().contains("完成：2 项"), "网页批量改分类：" + bulk.get("message").getAsString());
        equal("网页批量", f.categoryInFile("甲"), "网页批量命中了 #1");
        JsonObject reset = f.bot.webStylesEdit("web", "category", "丁", null, false, false, "-");
        check(reset.get("message").getAsString().contains("已清空手动分类"), "网页清空手动分类：" + reset.get("message").getAsString());
        equal("", f.categoryInFile("丁"), "网页清空后文件里没有分类字段");
        // 坏输入：不存在的样式、过长的分类名
        check(f.command(".style category 不存在 分类").contains("失败 1 项"), "不存在的样式要如实失败");
        check(f.command(".style category 甲 " + "长".repeat(LocalStyles.MAX_CATEGORY + 1)).contains("失败 1 项"), "过长的分类名要被拒");
        equal("", LocalStyles.categoryName("-"), "分类名 - 表示清空");
        equal("", LocalStyles.categoryName("  清除 "), "分类名「清除」表示清空");
        equal("", LocalStyles.categoryName(LocalStyles.LORA_CATEGORY), "老版本的自动分类名「LoRA 附带」不是手动分类名");
    }

    /** LoRA 附带样式：一个 LoRA 一个分类（判据 model.lora → 展示图映射 → 样式名前缀）；手动分类优先。 */
    private static void loraShowcaseCategory(Fixture f) throws Exception {
        // 生成展示图样式时留下的映射（老样式没有 sizeSource/lora 标注，靠它认出所属 LoRA）。
        JsonObject links = new JsonObject();
        links.addProperty("1/2/老模型.safetensors/1", "老模型 1");
        Json.atomicWrite(f.root.resolve("data/civitai-style-links.json"), links);
        f.seed(List.of(
                style("老模型 1", "legacy", null),
                style("新模型 1", "fresh", model("sizeSource", "preview", "lora", "新模型", "width", 96, "height", 64)),
                style("别的模型 1", "other", model("lora", "别的模型")),
                style("无名展示图", "anonymous", model("sizeSource", "preview")),
                style("展示图带尾号 3", "numbered", model("sizeSource", "preview")),
                style("普通 风格", "plain", model("stack", "sd"))));
        LocalStyles styles = new LocalStyles(f.root);
        equal("老模型", styles.categoryOf(styles.get("老模型 1")), "映射里的老展示图样式认到它自己的 LoRA");
        equal("lora:老模型", styles.classify(styles.get("老模型 1")).key(), "LoRA 分类的 key 前缀");
        equal("新模型", styles.categoryOf(styles.get("新模型 1")), "model.lora 给出所属 LoRA");
        equal("别的模型", styles.categoryOf(styles.get("别的模型 1")), "标了 model.lora 的样式认得出所属 LoRA");
        equal(LocalStyles.LORA_CATEGORY, styles.categoryOf(styles.get("无名展示图")), "认不出具体 LoRA 才退回「LoRA 附带」");
        equal("SD 1.5 栈", styles.categoryOf(styles.get("普通 风格")), "普通样式照旧按归属栈分类");
        // 排序：LoRA 组在最前、栈组在后、未分类最后。
        List<String> keys = groupKeys(styles);
        check(keys.indexOf("lora:老模型") >= 0 && keys.indexOf("stack:sd") > keys.indexOf("lora:老模型"),
                "LoRA 分类排在归属栈分类前面：" + keys);
        check(keys.indexOf(LocalStyles.NONE_KEY) == keys.size() - 1, "未分类排在最后：" + keys);
        // 手动分类优先：同步再写一次（save 带分类）也不能把用户设的分类改回去。
        styles.setCategory("新模型 1", "我的手动分类");
        styles.save("新模型 1", "fresh2", "", true, model("width", 96, "height", 64), LocalStyles.LORA_CATEGORY);
        equal("我的手动分类", styles.get("新模型 1").category(), "覆盖保存不会覆盖用户手动设的分类");
        equal("manual:我的手动分类", styles.classify(styles.get("新模型 1")).key(), "手动分类优先于自动分类");
        // 第二判据用不了（没有映射）时，仍能按样式名前缀判出 LoRA 名（前提是它确实是展示图样式）。
        equal("展示图带尾号", LocalStyles.loraName(styles.get("展示图带尾号 3"), LocalStyles.LoraIndex.EMPTY),
                "没有展示图映射时按样式名前缀判 LoRA");
        equal("", LocalStyles.loraName(styles.get("普通 风格"), LocalStyles.LoraIndex.EMPTY),
                "普通样式不会只按名字被认成 LoRA");
    }

    /** 展示图真实像素尺寸：PNG/JPEG/WebP 文件头、优先级（展示图 > 预设/当前设置）、补写回执、载入套用。 */
    private static void previewSizes(Fixture f) throws Exception {
        // 真实图片：临时的 96×64 PNG / JPEG。
        Path png = f.root.resolve("work/preview-96x64.png");
        Files.createDirectories(png.getParent());
        ImageIO.write(new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        equal(List.of(96, 64), size(ImageSize.of(png)), "真机 PNG 的像素尺寸（96×64）");
        equal(List.of(96, 64), size(ImageSize.of(Files.readAllBytes(png))), "同一张 PNG 的字节也能读出尺寸");
        Path jpeg = f.root.resolve("work/preview-96x64.jpg");
        ImageIO.write(new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB), "jpg", jpeg.toFile());
        equal(List.of(96, 64), size(ImageSize.of(jpeg)), "JPEG 的 SOF 段也能读出尺寸");
        byte[] webp = webpHeader(96, 64);
        equal(List.of(96, 64), size(ImageSize.of(webp)), "WebP 的 VP8X 头也能读出尺寸");
        equal(null, ImageSize.of("这不是图片，只是一行字".getBytes(StandardCharsets.UTF_8)), "不是图片就返回 null");
        equal(null, ImageSize.of(Arrays.copyOf(Files.readAllBytes(png), 20)), "被截断的 PNG 返回 null");
        equal(null, ImageSize.of(f.root.resolve("work/没有这个文件.png")), "文件不存在返回 null");

        // 展示图样式：宽高取**展示图自己的像素**，而参数里原本的预设尺寸（1024×1024）要被盖掉。
        byte[] cover = Files.readAllBytes(png);
        LocalStyles local = new LocalStyles(f.root);
        // 提示词已经带着本机标签（同步的判断是"内容没变但缺东西"），这样才走"补尺寸"那条分支。
        local.save("测试模型 1", "portrait, <lora:测试:1>", "bad", false,
                model("baseModel", "Anima", "stack", "anima", "width", 1024, "height", 1024));
        local.save("测试模型 2", "landscape, <lora:测试:1>", "bad", false,
                model("baseModel", "Anima", "stack", "anima", "width", 1024, "height", 1024));
        StylePreviews.save(f.root, "测试模型 1", cover);
        Path lora = f.root.resolve("work/shirohaANY-clothes.safetensors");
        Files.writeString(lora, "stub");
        CivitaiClient.DownloadedLora download = new CivitaiClient.DownloadedLora("测试模型", "v1", "Anima",
                List.of(), lora, true, 11, 22, List.of(
                        new CivitaiClient.ShowcasePrompt(1, "portrait", "bad", true, ""),
                        new CivitaiClient.ShowcasePrompt(2, "landscape", "bad", true, "")));
        JsonObject preset = model("baseModel", "Anima", "stack", "anima", "sampler", "ER SDE", "width", 1024, "height", 1024);
        CivitaiStyleSync.Outcome outcome = CivitaiStyleSync.run(f.root, download, "<lora:测试:1>", f.client, true, null, preset);
        check(outcome.text().contains("补展示图尺寸 1"), "回执要说明补了几条尺寸：" + outcome.text());
        equal(1, outcome.sized(), "只有缺尺寸的那一条算补尺寸");
        LocalStyles.Style sized = local.get("测试模型 1");
        equal(96L, (long) Json.num(sized.model(), "width", 0), "样式宽取展示图的 96，而不是预设的 1024");
        equal(64L, (long) Json.num(sized.model(), "height", 0), "样式高取展示图的 64");
        equal(CivitaiStyleSync.PREVIEW_SIZE_SOURCE, Json.str(sized.model(), "sizeSource", ""), "标出尺寸来源是展示图");
        check(Json.str(sized.model(), "previewImage", "").startsWith("data/style-previews/"), "记下展示图路径（不复制大图）：" + sized.model());
        equal("shirohaANY-clothes", Json.str(sized.model(), "lora", ""), "记下所属 LoRA 的细分标签");
        equal("shirohaANY-clothes", local.categoryOf(sized), "展示图样式归到所属 LoRA 的分类（一个 LoRA 一个分类）");
        equal("ER SDE", Json.str(sized.model(), "sampler", ""), "预设里的其它参数照旧保留");
        // 没有预览图的那条：回退到原来记着的尺寸（预设/当前设置），不编、不标来源。
        LocalStyles.Style fallback = local.get("测试模型 2");
        equal(1024L, (long) Json.num(fallback.model(), "width", 0), "读不到展示图就保留原尺寸（回退）");
        equal("", Json.str(fallback.model(), "sizeSource", ""), "没有展示图尺寸就不标来源");
        // 再同步一次是复用：尺寸不会被反复重写。
        CivitaiStyleSync.Outcome again = CivitaiStyleSync.run(f.root, download, "<lora:测试:1>", f.client, true, null, preset);
        check(again.text().contains("复用 2"), "尺寸已经记对之后重复同步是复用：" + again.text());
        equal(0, again.sized(), "复用时不报补尺寸");
        // 载入：尺寸随样式一起套回机器人设置。
        String loaded = f.command(".style load 测试模型 1");
        check(loaded.contains("96×64"), "载入回执照实说套了展示图尺寸：" + loaded);
        equal(96L, (long) f.client.settings().width(), "载入后机器人宽度＝展示图宽度");
        equal(64L, (long) f.client.settings().height(), "载入后机器人高度＝展示图高度");
    }

    // ---------------------------------------------------------------- 小工具

    private static List<Integer> size(int[] value) {
        return value == null ? null : List.of(value[0], value[1]);
    }

    /** 一份最小的 WebP（VP8X 扩展块）文件头：宽高各 24 位小端，存的是"尺寸-1"。 */
    private static byte[] webpHeader(int width, int height) {
        byte[] bytes = new byte[32];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        System.arraycopy("VP8X".getBytes(StandardCharsets.US_ASCII), 0, bytes, 12, 4);
        bytes[16] = 10;                                     // 块长度
        bytes[24] = (byte) ((width - 1) & 0xFF);
        bytes[25] = (byte) (((width - 1) >> 8) & 0xFF);
        bytes[26] = (byte) (((width - 1) >> 16) & 0xFF);
        bytes[27] = (byte) ((height - 1) & 0xFF);
        bytes[28] = (byte) (((height - 1) >> 8) & 0xFF);
        bytes[29] = (byte) (((height - 1) >> 16) & 0xFF);
        return bytes;
    }

    private static JsonObject style(String name, String positive, JsonObject model) {
        JsonObject item = new JsonObject();
        item.addProperty("name", name);
        item.addProperty("positive", positive);
        item.addProperty("negative", "");
        item.addProperty("updated_at", "2026-01-01T00:00:00Z");
        if (model != null) item.add("model", model);
        return item;
    }

    /** 键值对拼一份 model（数字按数字写，其余按字符串）。 */
    private static JsonObject model(Object... pairs) {
        JsonObject model = new JsonObject();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            String key = String.valueOf(pairs[index]);
            Object value = pairs[index + 1];
            if (value instanceof Number number) model.addProperty(key, number);
            else model.addProperty(key, String.valueOf(value));
        }
        return model;
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    /** 一个可用的机器人（真 SdClient + 桩 WebUI），样式库是临时目录里的真文件。 */
    private static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
        final SdClient client;
        final Bot bot;
        final BlockingQueue<String> replies = new LinkedBlockingQueue<>();

        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "style-category-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String route = exchange.getRequestURI().getPath();
                try (exchange) {
                    // 桩 WebUI：能读 options（机器人设置初始化要用），桥接一律 404（离线也要能用本机样式）。
                    String body = route.equals("/sdapi/v1/options") ? "{\"sd_model_checkpoint\":\"Model A [aaaa]\"}" : "{}";
                    int status = route.equals("/pixiko-bridge/v1/prompts") ? 404 : 200;
                    if (!route.equals("/sdapi/v1/options") && !route.equals("/pixiko-bridge/v1/prompts")) status = 404;
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception ignored) { /* 桩服务：读请求体失败也不影响测试 */ }
            });
            server.start();
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sd.addProperty("timeout_seconds", 2);
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            config.addProperty("owner_user_id", "123");
            Json.atomicWrite(root.resolve("config.json"), config);
            client = new SdClient(root, sd);
            bot = new Bot(new Settings(root), client, (event, segments) -> {
                replies.add(Bot.messageText(segments));
                return CompletableFuture.completedFuture(null);
            });
        }

        /** 直接写一份 data/local-styles.json（模拟用户的真实数据文件；没有 category 字段）。 */
        void seed(List<JsonObject> styles) throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("version", 1);
            JsonArray array = new JsonArray();
            styles.forEach(array::add);
            data.add("styles", array);
            Json.atomicWrite(root.resolve("data/local-styles.json"), data);
        }

        /** 文件里这条样式记着的分类（没有该字段＝空串）。 */
        String categoryInFile(String name) throws Exception {
            JsonObject data = Json.parse(Files.readString(root.resolve("data/local-styles.json"), StandardCharsets.UTF_8));
            for (JsonElement item : data.getAsJsonArray("styles")) {
                JsonObject style = item.getAsJsonObject();
                if (style.get("name").getAsString().equals(name)) return Json.str(style, "category", "");
            }
            throw new AssertionError("样式库里没有「" + name + "」");
        }

        String categoryOf(String name) {
            LocalStyles styles = new LocalStyles(root);
            return styles.categoryOf(styles.get(name));
        }

        String command(String text) throws Exception {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", "private");
            event.addProperty("self_id", 777);
            event.addProperty("user_id", 123);
            event.addProperty("message_id", System.nanoTime());
            event.add("message", Maps.text(text));
            replies.clear();
            bot.accept(event);
            String reply = replies.poll(7, TimeUnit.SECONDS);
            check(reply != null, "命令要有回执：" + text);
            return reply;
        }

        public void close() { bot.close(); server.stop(0); }
    }
}

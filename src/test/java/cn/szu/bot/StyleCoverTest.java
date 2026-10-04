package cn.szu.bot;

import com.google.gson.*;
import cn.szu.bot.sd.LocalStyles;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.StylePreviews;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 保存样式时的封面（控制台 /api/styles/edit 的 cover、/api/styles/cover）：
 * 空 cover＝最近一次生成的图；{@code -}/{@code none}/{@code 清除}/{@code 不设}＝本次不设、保留原封面；
 * 其它值＝{@code data/generated} 里的图片路径，越界必须报错且不写任何封面文件。
 *
 * <p>全程临时目录 + 桩 SD（配置里指一个没人监听的端口），不碰生产 data/、不碰 WebUI、不发 QQ 消息。
 */
public final class StyleCoverTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        defaultsToLatestGeneratedImage();
        noGeneratedImageMeansNoCover();
        embeddedWebuiImagesAreNotCovers();
        explicitPathUsesThatImage();
        pathsOutsideGeneratedAreRejected();
        noCoverMarkersKeepTheOldCover();
        webStyleCoverRoute();
        overwriteKeepsTheSameDefault();
        System.out.println("StyleCoverTest: " + assertions
                + " assertions passed: 保存样式默认用最近生成图当封面（跳过 data/generated/webui 的回执内嵌图）、-／none 不设、指定路径锁在 data/generated 内、/api/styles/cover 改封面.");
    }

    /** ① 空 cover：默认取最近一次生成的图（不是随便一张），封面就是那张图的字节。 */
    private static void defaultsToLatestGeneratedImage() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image("older.png", 64, (byte) 0x11, 600);          // 十分钟前的那张
            Path latest = f.image("latest.png", 64, (byte) 0x22, 0);
            byte[] newest = Files.readAllBytes(latest);
            JsonObject result = f.bot.webStylesEdit("web", "save", "最近图样式", "", false, false, "", "");
            String message = Json.str(result, "message", "");
            check(message.contains("样式已保存：最近图样式"), "保存回复照旧：" + message);
            check(message.contains("封面：最近生成的 latest.png"), "回复写明封面来自最近生成图：" + message);
            check(StylePreviews.has(f.root, "最近图样式"), "封面文件写出来了");
            check(Arrays.equals(newest, previewBytes(f.root, "最近图样式")), "封面就是最新的那张图的字节");
            Path served = f.bot.stylePreviewFile("最近图样式");
            check(served != null && Files.isRegularFile(served), "控制台 /api/style/preview 能读到这张封面");
            check(served != null && Arrays.equals(newest, Files.readAllBytes(served)), "/api/style/preview 读到的字节与生成图一致");
            equal(StylePreviews.file(f.root, "最近图样式").toAbsolutePath().normalize(),
                    served == null ? null : served.toAbsolutePath().normalize(), "预览图就在 data/style-previews 里");
            JsonObject item = styleItem(result, "最近图样式");
            check(item != null && item.get("preview").getAsBoolean(), "返回的样式列表里这条 preview=true：" + item);
            check(result.has("local") && result.has("library") && result.has("styles"), "保存照样返回刷新后的样式列表：" + result.keySet());
            JsonObject stored = storedStyle(f.root, "最近图样式");
            check(stored != null && !stored.has("previewImage") && !stored.has("sizeSource") && !stored.has("preview"),
                    "local-styles.json 里没有封面字段：" + stored);
            List<String> keys = stored == null ? List.of() : new ArrayList<>(stored.keySet());
            check(Set.of("name", "positive", "negative", "updated_at", "model", "category").containsAll(keys),
                    "样式记录没有多出封面相关的字段：" + keys);
            check(keys.containsAll(List.of("name", "positive", "negative", "updated_at")),
                    "样式记录的四个基本字段都在（封面是独立文件）：" + keys);
        }
    }

    /** ② 没有生成图（空目录）与超过 20MB 的图：不设封面、不抛异常，回复里说明。 */
    private static void noGeneratedImageMeansNoCover() throws Exception {
        try (Fixture f = new Fixture()) {
            JsonObject result = f.bot.webStylesEdit("web", "save", "无图样式", "", false, false, "", "");
            String message = Json.str(result, "message", "");
            check(message.contains("样式已保存：无图样式"), "没有生成图也能保存：" + message);
            check(message.contains("没有可用的最近生成图，本次未设封面"), "回复里说明没设封面：" + message);
            check(!StylePreviews.has(f.root, "无图样式"), "没写出封面文件");
            check(!Files.exists(StylePreviews.dir(f.root)), "连 data/style-previews 目录都没建");
            check(f.bot.stylePreviewFile("无图样式") == null, "控制台读不到封面（本来就没有）");
            JsonObject item = styleItem(result, "无图样式");
            check(item != null && !item.get("preview").getAsBoolean(), "列表里 preview=false：" + item);

            Path huge = f.image("huge.png", 20 * 1024 * 1024 + 1, (byte) 0x33, 0);
            equal(20L * 1024 * 1024 + 1, Files.size(huge), "这张图确实超过 20MB");
            JsonObject big = f.bot.webStylesEdit("web", "save", "大图样式", "", false, false, "", "");
            check(Json.str(big, "message", "").contains("没有可用的最近生成图，本次未设封面"),
                    "超过 20MB 只当没有可用生成图，不报错：" + big);
            check(!StylePreviews.has(f.root, "大图样式"), "超大图没有写进封面");
        }
    }

    /** ③ 回执内嵌图（data/generated/webui：地图、Civitai 封面这类）不算"最近一次生成的作品"。 */
    private static void embeddedWebuiImagesAreNotCovers() throws Exception {
        try (Fixture f = new Fixture()) {
            Path real = f.image("real.png", 96, (byte) 0x99, 300);
            byte[] wanted = Files.readAllBytes(real);
            // 更新的内嵌图：mtime 比真生成图新，默认封面必须跳过它、继续用真生成图那张。
            Path embedded = f.image("webui/map.png", 64, (byte) 0xAA, 0);
            check(Files.getLastModifiedTime(embedded).compareTo(Files.getLastModifiedTime(real)) > 0,
                    "内嵌图确实比真生成图新（否则这条用例白测）");
            JsonObject result = f.bot.webStylesEdit("web", "save", "跳过内嵌图", "", false, false, "", "");
            String message = Json.str(result, "message", "");
            check(message.contains("封面：最近生成的 real.png"), "默认封面跳过 data/generated/webui 里的内嵌图：" + message);
            check(Arrays.equals(wanted, previewBytes(f.root, "跳过内嵌图")), "用的是它之前那张真正的生成图");
            check(!Arrays.equals(Files.readAllBytes(embedded), previewBytes(f.root, "跳过内嵌图")), "没有把回执里的地图当成作品封面");
        }
        // 排除内嵌图之后没有可用图：按"没有可用的最近生成图"处理——不设封面、有说明、不报错。
        try (Fixture f = new Fixture()) {
            f.image("webui/cover.png", 64, (byte) 0xBB, 0);
            JsonObject result = f.bot.webStylesEdit("web", "save", "只有内嵌图", "", false, false, "", "");
            String message = Json.str(result, "message", "");
            check(message.contains("样式已保存：只有内嵌图"), "只有内嵌图也能保存：" + message);
            check(message.contains("没有可用的最近生成图，本次未设封面"), "排除后没有可用图就如实说明、不报错：" + message);
            check(!StylePreviews.has(f.root, "只有内嵌图"), "内嵌图不会被写进封面");
            check(!Files.exists(StylePreviews.dir(f.root)), "连 data/style-previews 目录都没建");
        }
    }

    /** ④ 指定 data/generated 里的某张图：相对路径、绝对路径、反斜杠都认，用的就是那张图。 */
    private static void explicitPathUsesThatImage() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] other = Files.readAllBytes(f.image("latest.png", 64, (byte) 0x22, 0));
            Path picked = f.image("picked.png", 96, (byte) 0x44, 300);
            byte[] wanted = Files.readAllBytes(picked);
            JsonObject result = f.bot.webStylesEdit("web", "save", "指定封面", "", false, false, "", "data/generated/picked.png");
            String message = Json.str(result, "message", "");
            check(message.contains("已记下封面：picked.png"), "回复带上封面来源：" + message);
            check(Arrays.equals(wanted, previewBytes(f.root, "指定封面")), "用的是指定的那张图");
            check(!Arrays.equals(other, previewBytes(f.root, "指定封面")), "没有误用最近一次生成的那张");
            JsonObject absolute = f.bot.webStylesEdit("web", "save", "绝对路径封面", "", false, false, "",
                    picked.toAbsolutePath().toString());
            check(Json.str(absolute, "message", "").contains("已记下封面：picked.png"), "绝对路径当封面也认：" + absolute);
            check(Arrays.equals(wanted, previewBytes(f.root, "绝对路径封面")), "绝对路径写的是同一张图");
            f.bot.webStylesEdit("web", "save", "反斜杠封面", "", false, false, "", "data\\generated\\picked.png");
            check(Arrays.equals(wanted, previewBytes(f.root, "反斜杠封面")), "反斜杠路径也认");
        }
    }

    /** ⑤ 只认 data/generated 目录树内的路径：越界就抛 IllegalArgumentException，且一个封面文件都不写。 */
    private static void pathsOutsideGeneratedAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image("latest.png", 64, (byte) 0x22, 0);
            Path outside = Files.write(f.root.resolve("outside.png"), new byte[64]);
            Files.createDirectories(f.root.resolve("data"));
            Path sibling = Files.write(f.root.resolve("data/outside.png"), new byte[64]);
            for (String cover : List.of("outside.png", "data/outside.png", "data/generated/../outside.png",
                    outside.toAbsolutePath().toString(), sibling.toAbsolutePath().toString(),
                    "data/generated/../../外面.png", "data/generated/没有这张图.png")) {
                rejected(() -> f.bot.webStylesEdit("web", "save", "越界样式", "", false, false, "", cover),
                        "data/generated", "越界的封面路径必须报错：" + cover);
                check(!StylePreviews.has(f.root, "越界样式"), "越界路径没有写出封面：" + cover);
                check(new LocalStyles(f.root).get("越界样式") == null, "越界路径不会留下半截样式：" + cover);
            }
            check(!Files.exists(StylePreviews.dir(f.root)), "整个过程一个封面文件都没写");
            check(Files.isRegularFile(outside) && Files.isRegularFile(sibling), "越界的图本身没被动过");
        }
    }

    /** ⑥ -／none／清除／不设（大小写、全角空格）：本次不设封面，原有封面保留、字节不变。 */
    private static void noCoverMarkersKeepTheOldCover() throws Exception {
        try (Fixture f = new Fixture()) {
            Path first = f.image("first.png", 64, (byte) 0x55, 600);
            byte[] original = Files.readAllBytes(first);
            String saved = Json.str(f.bot.webStylesEdit("web", "save", "不设封面样式", "", false, false, "", ""), "message", "");
            check(saved.contains("封面：最近生成的 first.png"), "先按默认设了一张封面：" + saved);
            f.image("second.png", 96, (byte) 0x66, 0);          // 更新的一张生成图（不该被用上）
            for (String marker : List.of("-", "none", "NONE", "\u3000清除\u3000", "不设", "off")) {
                JsonObject result = f.bot.webStylesEdit("web", "overwrite", "不设封面样式", "", true, false, "", marker);
                String message = Json.str(result, "message", "");
                check(message.contains("本次不设封面"), "标记 " + marker + " 表示本次不设封面：" + message);
                check(message.contains("保留原有的封面"), "标记 " + marker + " 保留原封面：" + message);
                check(Arrays.equals(original, previewBytes(f.root, "不设封面样式")), "标记 " + marker + " 之后原封面字节没变");
            }
            JsonObject bare = f.bot.webStylesEdit("web", "save", "本来就没封面", "", false, false, "", "none");
            String bareMessage = Json.str(bare, "message", "");
            check(bareMessage.contains("本次不设封面"), "没有封面时说一声就行：" + bareMessage);
            check(!bareMessage.contains("保留原有的封面"), "没有旧封面就不提保留：" + bareMessage);
            check(!StylePreviews.has(f.root, "本来就没封面"), "none 不会凭空写封面");
        }
    }

    /** ⑦ webStyleCover（控制台「用这张图当封面」）：正常改、样式名不存在、空路径、越界路径。 */
    private static void webStyleCoverRoute() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image("latest.png", 64, (byte) 0x22, 0);
            Path picked = f.image("cover-me.png", 96, (byte) 0x77, 300);
            byte[] wanted = Files.readAllBytes(picked);
            f.bot.webStylesEdit("web", "save", "样式甲", "", false, false, "", "none");
            check(!StylePreviews.has(f.root, "样式甲"), "先确认样式甲本来没有封面");
            check(new LocalStyles(f.root).get("样式甲") != null, "样式甲确实存下来了");

            JsonObject result = f.bot.webStyleCover("样式甲", "data/generated/cover-me.png");
            String message = Json.str(result, "message", "");
            check(message.contains("样式封面已更新：样式甲 ← cover-me.png"), "改封面的回复文案：" + message);
            check(Arrays.equals(wanted, previewBytes(f.root, "样式甲")), "封面写的就是这张图");
            JsonObject item = styleItem(result, "样式甲");
            check(item != null && item.get("preview").getAsBoolean(), "返回的列表里 preview=true：" + item);
            check(result.has("message") && result.has("styles"), "改封面同样返回刷新后的样式列表：" + result.keySet());

            IllegalArgumentException missing = rejected(() -> f.bot.webStyleCover("没有这个样式", "data/generated/cover-me.png"),
                    "没有这个样式", "样式名不存在必须报错");
            check(Bot.error(missing).contains("可用："), "报错里列出可用的样式名：" + Bot.error(missing));
            check(Bot.error(missing).contains("样式甲"), "可用的名字里有样式甲：" + Bot.error(missing));
            check(!StylePreviews.has(f.root, "没有这个样式"), "样式不存在时不会写出封面文件");

            rejected(() -> f.bot.webStyleCover("样式甲", ""), "请给一张封面图", "空路径要报错");
            Files.write(f.root.resolve("outside.png"), new byte[64]);
            rejected(() -> f.bot.webStyleCover("样式甲", "outside.png"), "data/generated", "越界路径要报错");
            check(Arrays.equals(wanted, previewBytes(f.root, "样式甲")), "报错之后原封面没被动过");
        }
    }

    /** ⑧ 覆盖保存走同一套默认逻辑：封面换成新的那张最近生成图，样式不会多出一条。 */
    private static void overwriteKeepsTheSameDefault() throws Exception {
        try (Fixture f = new Fixture()) {
            Path first = f.image("first.png", 64, (byte) 0x55, 600);
            byte[] before = Files.readAllBytes(first);
            f.bot.webStylesEdit("web", "save", "覆盖封面", "", false, false, "", "");
            check(Arrays.equals(before, previewBytes(f.root, "覆盖封面")), "先存一张：封面是 first.png");
            Path newer = f.image("newer.png", 96, (byte) 0x88, 0);
            byte[] wanted = Files.readAllBytes(newer);
            JsonObject again = f.bot.webStylesEdit("web", "overwrite", "覆盖封面", "", true, false, "", "");
            String message = Json.str(again, "message", "");
            check(message.contains("样式已覆盖保存：覆盖封面"), "覆盖保存的回复照旧：" + message);
            check(message.contains("封面：最近生成的 newer.png"), "覆盖时默认同样取最近生成图：" + message);
            check(Arrays.equals(wanted, previewBytes(f.root, "覆盖封面")), "封面换成了新的那张");
            equal(1, again.getAsJsonArray("styles").size(), "覆盖不会多出一条样式");
        }
    }

    @FunctionalInterface private interface Operation { Object run() throws Exception; }

    /** 必须抛 IllegalArgumentException（文案里带 fragment），否则算失败。 */
    private static IllegalArgumentException rejected(Operation operation, String fragment, String message) throws Exception {
        try { operation.run(); }
        catch (IllegalArgumentException expected) {
            String text = Bot.error(expected);
            check(fragment == null || text.contains(fragment), message + "（异常文案： " + text + "）");
            return expected;
        }
        throw new AssertionError(message + "：本来应该抛 IllegalArgumentException");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    private static byte[] previewBytes(Path root, String name) throws Exception {
        Path file = StylePreviews.file(root, name);
        return file != null && Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    }

    private static JsonObject styleItem(JsonObject result, String name) {
        for (JsonElement element : result.getAsJsonArray("styles"))
            if (element.getAsJsonObject().get("name").getAsString().equals(name)) return element.getAsJsonObject();
        return null;
    }

    /** local-styles.json 里那条样式记录本身（确认封面没被塞进样式存储）。 */
    private static JsonObject storedStyle(Path root, String name) throws Exception {
        JsonObject file = Json.parse(Files.readString(root.resolve("data/local-styles.json"), StandardCharsets.UTF_8));
        for (JsonElement element : file.getAsJsonArray("styles")) {
            JsonObject style = element.getAsJsonObject();
            if (style.get("name").getAsString().equals(name)) return style;
        }
        return null;
    }

    private static final class Fixture implements AutoCloseable {
        final Path root, generated;
        final Bot bot;

        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "style-cover-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            generated = Files.createDirectories(root.resolve("data/generated"));
            // 桩 SD：指一个没人监听的端口，一律连不上——保存样式/设封面都不该因此失败。
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:1");
            sd.addProperty("timeout_seconds", 1);
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            Json.atomicWrite(root.resolve("config.json"), config);
            bot = new Bot(new Settings(root), new SdClient(root, sd),
                    (event, segments) -> CompletableFuture.completedFuture(null));
        }

        /** 往 data/generated 放一张"生成图"（内容按 tag 区分），ageSeconds 用来排"最近一次"。 */
        Path image(String relative, int size, byte tag, long ageSeconds) throws Exception {
            Path path = generated.resolve(relative);
            Files.createDirectories(path.getParent());
            byte[] bytes = new byte[size];
            bytes[0] = (byte) 0x89; bytes[1] = 'P'; bytes[2] = 'N'; bytes[3] = 'G';
            bytes[4] = 13; bytes[5] = 10; bytes[6] = 26; bytes[7] = 10;
            Arrays.fill(bytes, 8, size, tag);
            Files.write(path, bytes);
            if (ageSeconds > 0) Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() - ageSeconds * 1000L));
            return path;
        }

        public void close() { bot.close(); }
    }
}

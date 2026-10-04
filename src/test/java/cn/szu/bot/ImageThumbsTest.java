package cn.szu.bot;

import cn.szu.bot.web.ImageThumbs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * 缩略图与路径归一化（{@link ImageThumbs}）的真跑单测：不连 QQ、不连 SD、只碰自己的临时目录。
 *
 * <p>覆盖：五种 {@code file://} 写法 + 绝对路径 + URL 编码 + 越界/{@code ..}/其它盘/非白名单扩展名、
 * {@code w} 的解析与边界夹取、长边 ≤ w 且绝不放大、带 alpha 走 PNG / 不带走 JPEG、
 * 第二次走缓存（缓存文件 mtime 不变 + 明显更快）、坏图退回原图（返回 null）、按 mtime 清缓存。
 */
public final class ImageThumbsTest {

    private static int checks;
    private static Path root;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "thumbs").toAbsolutePath();
        Files.createDirectories(work);
        root = Files.createTempDirectory(work, "case-");
        Files.createDirectories(root.resolve("data/generated"));

        paths();
        widths();
        noUpscale();
        alphaGoesPng();
        opaqueGoesJpeg();
        cacheHits();
        brokenSourceFallsBack();
        prune();

        System.out.println("ImageThumbsTest: " + checks + " assertions passed：路径归一化（file:/// 各种写法、"
                + "绝对路径、URL 编码、越界、.. 攻击、其它盘、非白名单扩展名）、w 解析与夹取、长边 ≤ w 且不放大、"
                + "alpha→PNG／不透明→JPEG、缓存命中（mtime 不变且更快）、坏图返回 null 退回原图、按 mtime 清缓存");
    }

    // ---------------------------------------------------------------- 路径归一化

    private static void paths() {
        String abs = root.toAbsolutePath().normalize().toString().replace('\\', '/');   // F:/…/case-xxx
        String png = abs + "/data/generated/a.png";
        String back = abs.replace('/', '\\') + "\\data\\generated\\a.png";

        check(ImageThumbs.resolveImage(root, "data/generated/a.png") != null, "相对路径 data/generated/a.png");
        check("data/generated/a.png".equals(ImageThumbs.relativeImagePath(root, "data/generated/a.png")),
                "相对路径归一化后就是它自己");
        check(ImageThumbs.resolveImage(root, "./data/generated/a.png") != null, "./ 前缀");
        check(ImageThumbs.resolveImage(root, png) != null, "绝对路径 F:/…/data/generated/a.png");
        check(ImageThumbs.resolveImage(root, back) != null, "绝对路径反斜杠 F:\\…\\data\\generated\\a.png");
        check(ImageThumbs.resolveImage(root, "file:///" + png) != null, "file:/// 三斜杠（存档里的真实形态）");
        check(ImageThumbs.resolveImage(root, "file://" + png) != null, "file:// 双斜杠");
        check(ImageThumbs.resolveImage(root, "file:/" + png) != null, "file:/ 单斜杠");
        check(ImageThumbs.resolveImage(root, "FILE:///" + png) != null, "大写 FILE:///");
        check(ImageThumbs.resolveImage(root, "File:///" + back) != null, "file:/// + 反斜杠");
        check(ImageThumbs.resolveImage(root, "file://localhost/" + png) != null, "file://localhost/F:/…");
        check(ImageThumbs.resolveImage(root, "/" + png) != null, "只有一个前导斜杠的盘符路径 /F:/…");

        String encoded = URLEncoder.encode("file:///" + png, StandardCharsets.UTF_8);
        check(encoded.contains("%3A") && ImageThumbs.resolveImage(root, encoded) != null,
                "URL 编码过的 file:///（" + encoded.substring(0, Math.min(28, encoded.length())) + "…）");
        check(ImageThumbs.resolveImage(root, URLEncoder.encode(encoded, StandardCharsets.UTF_8)) != null,
                "双重编码的 file:///");
        check(ImageThumbs.resolveImage(root, "data%2Fgenerated%2Fa.png") != null, "编码过的相对路径");

        check(ImageThumbs.resolveImage(root, null) == null, "null 被拒绝");
        check(ImageThumbs.resolveImage(root, "") == null, "空串被拒绝");
        check(ImageThumbs.resolveImage(root, "   ") == null, "全空白被拒绝");
        check(ImageThumbs.resolveImage(root, "data/generated/../../config.json") == null, ".. 穿越被拒绝");
        check(ImageThumbs.resolveImage(root, "../config.json") == null, "../ 被拒绝");
        check(ImageThumbs.resolveImage(root, "file:///" + abs + "/config.json") == null,
                "file:/// 指到 generated 之外仍被拒绝");
        check(ImageThumbs.resolveImage(root, "file:///" + abs + "/data/generated/../../../x.png") == null,
                "file:/// + .. 穿越被拒绝");
        check(ImageThumbs.resolveImage(root, "file:///" + abs + "/data/other/a.png") == null,
                "root 内但不在 generated 下被拒绝");
        String other = abs.startsWith("C") || abs.startsWith("c") ? "Z" : "C";
        check(ImageThumbs.resolveImage(root, "file:///" + other + ":/Windows/win.png") == null,
                "其它盘符（" + other + ":）被拒绝");
        check(ImageThumbs.resolveImage(root, "data/generated/evil.txt") == null, "非白名单扩展名 .txt 被拒绝");
        check(ImageThumbs.resolveImage(root, "data/generated/x.png.exe") == null, ".png.exe 被拒绝");
        check(ImageThumbs.resolveImage(root, "data/generated/noext") == null, "无扩展名被拒绝");
        check(ImageThumbs.resolveImage(root, "data/generated/") == null, "目录本身被拒绝");
        for (String name : new String[]{"a.png", "a.JPG", "a.jpeg", "a.WebP", "a.gif"})
            check(ImageThumbs.resolveImage(root, "data/generated/" + name) != null, "白名单扩展名放行 " + name);

        symlinkEscape();
    }

    /** 符号链接：data/generated 里指向外面的链接必须挡掉（Windows 上没权限建链接就跳过这条）。 */
    private static void symlinkEscape() {
        try {
            Path outside = root.resolve("data/outside.png");
            Files.write(outside, new byte[]{1, 2, 3});
            Path link = root.resolve("data/generated/link.png");
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, outside);
            check(ImageThumbs.resolveImage(root, "data/generated/link.png") == null, "符号链接指到 generated 之外被挡住");
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            System.out.println("ImageThumbsTest：跳过符号链接断言（本机建不了链接：" + error.getClass().getSimpleName() + "）");
        }
    }

    // ---------------------------------------------------------------- w 解析

    private static void widths() {
        check(ImageThumbs.parseWidth("320") == 320, "w=320");
        check(ImageThumbs.parseWidth(" 520 ") == 520, "w 两侧空白被忽略");
        check(ImageThumbs.parseWidth("32") == 32, "w 下边界 32");
        check(ImageThumbs.parseWidth("1600") == 1600, "w 上边界 1600");
        check(ImageThumbs.parseWidth("10") == 32, "w=10 夹到下边界 32");
        check(ImageThumbs.parseWidth("0") == 32, "w=0 夹到下边界 32");
        check(ImageThumbs.parseWidth("-40") == 32, "w=-40 夹到下边界 32");
        check(ImageThumbs.parseWidth("99999") == 1600, "w=99999 夹到上边界 1600");
        check(ImageThumbs.parseWidth(null) == null, "w 缺省当没传");
        check(ImageThumbs.parseWidth("") == null, "w 空串当没传");
        check(ImageThumbs.parseWidth("abc") == null, "w=abc 非法当没传");
        check(ImageThumbs.parseWidth("320.5") == null, "w=320.5 非整数当没传");
    }

    // ---------------------------------------------------------------- 缩放

    /** 长边 ≤ w 时直接回 null（调用方用原图字节）：绝不放大。 */
    private static void noUpscale() throws IOException {
        Path small = writePng("small.png", 200, 100, false);
        check(ImageThumbs.thumbnail(root, small, 1600) == null, "原图长边 200 ≤ w=1600：不放大，回 null");
        check(ImageThumbs.thumbnail(root, small, 200) == null, "原图长边 200 = w=200：不放大，回 null");
        ImageThumbs.Thumb thumb = ImageThumbs.thumbnail(root, small, 100);
        check(thumb != null, "w=100 小于长边，能生成缩略图");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(thumb.bytes()));
        check(image.getWidth() == 100 && image.getHeight() == 50,
                "等比缩到 100×50（实得 " + image.getWidth() + "×" + image.getHeight() + "）");
        check(Math.max(image.getWidth(), image.getHeight()) <= 100, "长边 ≤ w=100");
        check(thumb.contentType().equals("image/jpeg"), "不透明源图走 JPEG：" + thumb.contentType());
    }

    /** 带 alpha 的源图必须走 PNG（走 JPEG 会把透明区压成黑块）。 */
    private static void alphaGoesPng() throws IOException {
        Path alpha = writePng("alpha.png", 1200, 800, true);
        ImageThumbs.Thumb thumb = ImageThumbs.thumbnail(root, alpha, 320);
        check(thumb != null, "带 alpha 的大图能生成缩略图");
        check("image/png".equals(thumb.contentType()), "带 alpha 走 PNG：" + thumb.contentType());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(thumb.bytes()));
        check(Math.max(image.getWidth(), image.getHeight()) == 320,
                "长边正好 320（实得 " + image.getWidth() + "×" + image.getHeight() + "）");
        check(image.getColorModel().hasAlpha(), "缩略图仍然带 alpha 通道");
        check(thumb.bytes().length < Files.size(alpha),
                "缩略图比原图小（" + thumb.bytes().length + " < " + Files.size(alpha) + "）");
    }

    /** 不透明的源图走 JPEG（质量 0.85），1664×1216 这种真尺寸缩到 320 要明显更小。 */
    private static void opaqueGoesJpeg() throws IOException {
        Path big = writePng("big.png", 1664, 1216, false);
        long sourceBytes = Files.size(big);
        ImageThumbs.Thumb thumb = ImageThumbs.thumbnail(root, big, 320);
        check(thumb != null, "1664×1216 的不透明图能生成缩略图");
        check("image/jpeg".equals(thumb.contentType()), "不透明走 JPEG：" + thumb.contentType());
        check(thumb.bytes().length >= 3 && (thumb.bytes()[0] & 0xFF) == 0xFF
                && (thumb.bytes()[1] & 0xFF) == 0xD8, "JPEG 文件头正确");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(thumb.bytes()));
        check(image.getWidth() == 320 && image.getHeight() == 234,
                "1664×1216 等比缩到 320×234（实得 " + image.getWidth() + "×" + image.getHeight() + "）");
        check(thumb.bytes().length * 4 < sourceBytes,
                "缩略图远小于原图（" + thumb.bytes().length + " < " + sourceBytes + " / 4）");
    }

    /** 同一个 w 第二次必须走缓存：缓存文件 mtime 不变（说明没重做），而且明显更快（说明没再解码）。 */
    private static void cacheHits() throws Exception {
        Path big = root.resolve("data/generated/big.png");
        Path dir = ImageThumbs.cacheDir(root);
        deleteTree(dir);

        long start = System.nanoTime();
        ImageThumbs.Thumb first = ImageThumbs.thumbnail(root, big, 520);
        long coldMillis = (System.nanoTime() - start) / 1_000_000L;
        check(first != null, "冷启动能生成 w=520 的缩略图");

        List<Path> cached = listFiles(dir);
        check(cached.size() == 1, "冷启动后缓存目录里正好一个文件：" + cached);
        Path cacheFile = cached.get(0);
        check(cacheFile.getFileName().toString().matches("[0-9a-f]{16}\\.(png|jpg)"),
                "缓存文件名是 sha1 前 16 位 + 扩展名：" + cacheFile.getFileName());
        check(Files.size(cacheFile) == first.bytes().length, "缓存文件大小与返回字节一致");
        long stamp = Files.getLastModifiedTime(cacheFile).toMillis();

        start = System.nanoTime();
        ImageThumbs.Thumb second = ImageThumbs.thumbnail(root, big, 520);
        long warmMillis = (System.nanoTime() - start) / 1_000_000L;
        check(second != null, "第二次能取到缩略图");
        check(Arrays.equals(first.bytes(), second.bytes()), "两次字节完全一致");
        check(Files.getLastModifiedTime(cacheFile).toMillis() == stamp, "第二次没有重做缓存（mtime 不变）");
        check(listFiles(dir).size() == 1, "没有多出缓存文件");
        check(warmMillis < coldMillis, "命中缓存更快：首次 " + coldMillis + " ms → 第二次 " + warmMillis + " ms");

        // 源图变新（内容可能变了）→ 缓存作废并重做。
        Files.setLastModifiedTime(big, FileTime.fromMillis(System.currentTimeMillis() + 5000L));
        check(ImageThumbs.thumbnail(root, big, 520) != null, "源图变新后仍能取到缩略图");
        check(Files.getLastModifiedTime(cacheFile).toMillis() != stamp, "源图比缓存新时缓存被重做");

        // 不同 w 用不同的键，互不覆盖。
        check(ImageThumbs.thumbnail(root, big, 320) != null, "另一个 w 也能生成");
        check(listFiles(dir).size() == 2, "不同 w 各占一个缓存文件：" + listFiles(dir).size());
    }

    /** 坏图/读不出来 → 返回 null，调用方（serveImage）退回原图字节，绝不抛异常。 */
    private static void brokenSourceFallsBack() throws IOException {
        Path broken = root.resolve("data/generated/broken.png");
        Files.write(broken, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        check(ImageThumbs.thumbnail(root, broken, 320) == null, "只有 4 字节的坏 PNG 返回 null（退回原图字节）");

        Path garbage = root.resolve("data/generated/garbage.png");
        byte[] noise = new byte[64 * 1024];
        new Random(7).nextBytes(noise);
        Files.write(garbage, noise);
        check(ImageThumbs.thumbnail(root, garbage, 320) == null, "随机字节的假 PNG 返回 null");

        Path noDecoder = root.resolve("data/generated/fake.webp");
        Files.write(noDecoder, noise);
        check(ImageThumbs.thumbnail(root, noDecoder, 320) == null, "JDK 没有解码器的 webp 返回 null");

        check(ImageThumbs.thumbnail(root, root.resolve("data/generated/missing.png"), 320) == null,
                "文件不存在返回 null");
        check(ImageThumbs.thumbnail(root, garbage, 8) == null, "w 低于下边界时按 32 处理，坏图照样 null");
    }

    /** 清缓存：按 mtime 从最旧的开始删，删到阈值 90% 以内；正式路径的阈值是 300 MB。 */
    private static void prune() throws IOException {
        Path dir = ImageThumbs.cacheDir(root);
        deleteTree(dir);
        Files.createDirectories(dir);
        long base = 1_600_000_000_000L;
        List<Path> files = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            Path file = dir.resolve("prune" + index + ".bin");
            Files.write(file, new byte[1000]);
            Files.setLastModifiedTime(file, FileTime.fromMillis(base + index * 1000L));
            files.add(file);
        }
        check(ImageThumbs.cacheSize(root) == 5000, "清理前 5×1000 字节：" + ImageThumbs.cacheSize(root));
        long left = ImageThumbs.pruneCache(root, 3000);
        check(left <= 3000 * 9 / 10, "清理后低于阈值的 90%：" + left);
        check(!Files.exists(files.get(0)) && !Files.exists(files.get(1)), "最旧的两个被删掉");
        check(Files.exists(files.get(4)), "最新的那个保留下来");
        check(ImageThumbs.cacheSize(root) == left, "cacheSize 与返回值一致：" + left);
        check(ImageThumbs.pruneCache(root, 3000) == left, "已经低于阈值时不再删");
        check(ImageThumbs.CACHE_LIMIT_BYTES == 300L * 1024 * 1024, "正式阈值是 300 MB");
    }

    // ---------------------------------------------------------------- 工具

    private static Path writePng(String name, int width, int height, boolean alpha) throws IOException {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[width];
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = random.nextInt(256);
                int g = random.nextInt(256);
                int b = random.nextInt(256);
                // 带 alpha 的那张做成横向渐变，确保真的用到 alpha 通道。
                int a = alpha ? Math.max(0, Math.min(255, (x * 255) / Math.max(1, width - 1))) : 255;
                pixels[x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            image.setRGB(0, y, width, 1, pixels, 0, width);
        }
        Path file = root.resolve("data/generated/" + name);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static List<Path> listFiles(Path dir) {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;
        try (Stream<Path> walk = Files.walk(dir, 1)) {
            walk.filter(Files::isRegularFile).forEach(result::add);
        } catch (IOException ignored) { /* 当作空目录 */ }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
}

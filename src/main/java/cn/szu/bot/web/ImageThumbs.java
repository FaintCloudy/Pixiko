package cn.szu.bot.web;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 生成图的缩略图与图片路径归一化（网页图集的服务端那一半）。
 *
 * <p><b>为什么要有缩略图</b>：回执里的图集原来直接拉全尺寸原图——一张 1664×1216 的 PNG 约 2.5 MB，
 * 十张回执就是 25 MB，而页面上那些格子只有 110–260 CSS px。移动端一次回执要等十几秒，退出再进
 * （{@code max-age=60}）又得整片重拉。这里用 JDK 自带的 ImageIO 现场缩一张长边 ≤ w 的图，
 * 落盘缓存到 {@code <bot.home>/data/cache/thumbs}，第二次直接读缓存字节。
 *
 * <p><b>为什么路径要 tolerant</b>：存档（{@code data/quests/*.json}、{@code data/webui/web-chat-log.json}）
 * 里存的是 {@code file:///F:/Bot/data/generated/task-…/image-01.png} 这种 URI 形态，而接口只认
 * {@code data/generated/…} 相对路径，于是网页请求 400、移动端整屏图片都出不来。
 * {@link #resolveImage(Path, String)} / {@link #relativeImagePath(Path, String)} 把这些写法都收敛成
 * 同一条路径，并**始终**要求结果落在 {@code <root>/data/generated} 之内。
 *
 * <p>失败一律返回 {@code null}（调用方退回原图字节）：缩略图是加速手段，不该把图片接口打成 500。
 * 不引新依赖，只用 {@code javax.imageio} + {@code Graphics2D}。
 */
public final class ImageThumbs {

    private ImageThumbs() { }

    /** 缩略图合法宽度下限（越界按边界值处理）。 */
    public static final int MIN_WIDTH = 32;
    /** 缩略图合法宽度上限。 */
    public static final int MAX_WIDTH = 1600;
    /** 缩略图磁盘缓存目录的总大小上限。 */
    public static final long CACHE_LIMIT_BYTES = 300L * 1024L * 1024L;
    /** 缓存清理的触发口径：累计写入这么多字节就先量一次目录。 */
    private static final long PRUNE_CHECK_BYTES = 32L * 1024L * 1024L;
    /** 同上：累计写了这么多个缓存文件也量一次（小图靠这条兜住）。 */
    private static final int PRUNE_CHECK_WRITES = 64;
    /** 图片扩展名白名单（与原 serveImage 一字不差）。 */
    private static final String[] EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".gif"};
    /** 缓存命中/未命中都返回这组头：内容由文件名的时间戳 + uuid 保证不再变，退回 60 秒会让人退出再进重拉十几 MB。 */
    static final String IMAGE_CACHE_CONTROL = "private, max-age=86400";

    private static final AtomicLong BYTES_SINCE_CHECK = new AtomicLong();
    private static final AtomicInteger WRITES_SINCE_CHECK = new AtomicInteger();

    /** 缩略图（或退回用的原图）字节 + 与之匹配的 Content-Type。 */
    public record Thumb(byte[] bytes, String contentType) { }

    // ------------------------------------------------------------------ 宽度

    /**
     * 解析 {@code w}：非整数或空值返回 {@code null}（当作没传，回原图）；越界夹到 32–1600。
     */
    public static Integer parseWidth(String raw) {
        if (raw == null) return null;
        String text = raw.strip();
        if (text.isEmpty()) return null;
        int value;
        try { value = Integer.parseInt(text); }
        catch (NumberFormatException error) { return null; }
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, value));
    }

    // ------------------------------------------------------------------ 路径

    /**
     * 把请求里拿到的 {@code path} 归一化成磁盘路径；不合法（穿越、其它盘、非白名单扩展名）返回 {@code null}。
     *
     * <p>认得 {@code file:///F:/…}、{@code file://F:/…}、{@code file:/F:/…}（大小写、正反斜杠随便）、
     * {@code F:/…}／{@code F:\…} 绝对路径、以及 URL 编码过的写法；相对路径仍按原来的口径相对于 {@code root}。
     */
    public static Path resolveImage(Path root, String raw) {
        String relative = relativeImagePath(root, raw);
        if (relative == null) return null;
        return root.toAbsolutePath().normalize().resolve(relative).normalize();
    }

    /**
     * 归一化后的 {@code data/generated/…} 相对路径（用 {@code /} 分隔，供缓存键使用）；不合法返回 {@code null}。
     *
     * <p>安全底线：无论输入什么形态，最终一定要求 target 归一化后仍在 {@code <root>/data/generated} 之内
     * （{@code ..}、其它盘符、以及存在的符号链接都挡），扩展名白名单保持不变。
     */
    public static String relativeImagePath(Path root, String raw) {
        Path base = generatedDir(root);
        if (base == null) return null;
        String text = clean(raw);
        if (text == null) return null;
        Path target;
        try { target = root.toAbsolutePath().normalize().resolve(text).normalize(); }
        catch (InvalidPathException error) { return null; }
        if (!target.startsWith(base)) return null;
        if (target.equals(base)) return null;
        if (!insideRealPath(base, target)) return null;
        if (!isImageName(target.getFileName().toString())) return null;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        try { return normalizedRoot.relativize(target).toString().replace('\\', '/'); }
        catch (IllegalArgumentException error) { return null; }
    }

    /** 扩展名白名单（大小写不敏感）。 */
    public static boolean isImageName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) if (lower.endsWith(extension)) return true;
        return false;
    }

    /** {@code <root>/data/generated} 的归一化绝对路径。 */
    private static Path generatedDir(Path root) {
        if (root == null) return null;
        return root.toAbsolutePath().normalize().resolve("data").resolve("generated").normalize();
    }

    /**
     * 符号链接的第二道闸：文件存在时看真实路径是否仍在这棵子树里。词法检查（startsWith）挡不住
     * data/generated 里指向外面的链接，真路径比对能挡住；文件不存在时只做词法检查（后面还有 isRegularFile）。
     */
    private static boolean insideRealPath(Path base, Path target) {
        try {
            if (!Files.exists(target)) return true;
            Path realBase = base.toRealPath();
            return target.toRealPath().startsWith(realBase);
        } catch (IOException error) {
            return true;
        }
    }

    /** 去掉引号、必要时再解一次 URL 编码、统一斜杠、剥掉 {@code file:} 前缀。返回 {@code null} 表示空输入。 */
    private static String clean(String raw) {
        if (raw == null) return null;
        String text = raw.strip();
        if (text.length() >= 2
                && ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))))
            text = text.substring(1, text.length() - 1).strip();
        if (text.isEmpty()) return null;
        // request.getParameter 通常已经解过一遍；解出来仍是 %3A/%2F 的（双重编码）再解，直到解干净。
        for (int pass = 0; pass < 4 && looksEncoded(text); pass++) {
            String decoded = decode(text);
            if (decoded.equals(text)) break;
            text = decoded;
        }
        text = text.replace('\\', '/');
        text = stripFileScheme(text);
        // 「/F:/…」这种只有一个前导斜杠的盘符路径：去掉斜杠交给 Paths 认。
        if (text.length() >= 3 && text.charAt(0) == '/' && Character.isLetter(text.charAt(1)) && text.charAt(2) == ':')
            text = text.substring(1);
        return text.isEmpty() ? null : text;
    }

    /**
     * 只对确实像"被编码过的分隔符/百分号"的串解码，免得把文件名里正常的 {@code +} 弄坏。
     * {@code %25} 也算：双重编码的第一层就是它（{@code file%253A%252F%252F…}）。
     */
    private static boolean looksEncoded(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("%3a") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%25");
    }

    private static String decode(String text) {
        try { return URLDecoder.decode(text, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException error) { return text; }
    }

    /** {@code file:///F:/x}、{@code file://F:/x}、{@code file:/F:/x}、{@code file://localhost/F:/x} 都收敛到 {@code F:/x}。 */
    private static String stripFileScheme(String text) {
        if (!text.regionMatches(true, 0, "file:", 0, 5)) return text;
        String rest = text.substring(5);
        while (rest.startsWith("/")) rest = rest.substring(1);
        if (rest.regionMatches(true, 0, "localhost/", 0, 10)) rest = rest.substring(10);
        return rest;
    }

    // ------------------------------------------------------------------ 缩略图

    /** 缓存目录：{@code <root>/data/cache/thumbs}（刻意不放 data/generated，那里有"最近生成图"的扫描逻辑）。 */
    public static Path cacheDir(Path root) {
        return root.toAbsolutePath().normalize().resolve("data").resolve("cache").resolve("thumbs");
    }

    /**
     * 取（或生成）长边 ≤ {@code width} 的缩略图。
     *
     * <p>原图长边本来就 ≤ {@code width} 时返回 {@code null}（绝不放大，调用方回原图字节）；
     * 读不出来／格式不支持（例如 JDK 没有 webp 解码器）／写不出缓存也返回 {@code null}，绝不抛给上层。
     * 缩略图格式：源图带 alpha → PNG，否则 JPEG（质量 0.85）。缓存写在临时文件再原子 move。
     */
    public static Thumb thumbnail(Path root, Path source, int width) {
        if (root == null || source == null) return null;
        int wanted = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
        String key = cacheKey(root, source, wanted);
        if (key == null) return null;
        Path dir = cacheDir(root);
        try {
            // 1) 缓存命中：扩展名由源图是否带 alpha 决定，所以两种都探一下。
            long sourceTime = Files.getLastModifiedTime(source).toMillis();
            for (String extension : new String[]{".png", ".jpg"}) {
                Path cached = dir.resolve(key + extension);
                if (!Files.isRegularFile(cached)) continue;
                if (Files.getLastModifiedTime(cached).toMillis() < sourceTime) continue;
                byte[] bytes = Files.readAllBytes(cached);
                if (bytes.length == 0) continue;
                return new Thumb(bytes, ".png".equals(extension) ? "image/png" : "image/jpeg");
            }
            // 2) 生成
            BufferedImage image = readImage(source);
            if (image == null) return null;
            int sourceWidth = image.getWidth();
            int sourceHeight = image.getHeight();
            int longest = Math.max(sourceWidth, sourceHeight);
            if (longest <= wanted) return null;   // 不放大
            boolean alpha = image.getColorModel().hasAlpha();
            double scale = wanted / (double) longest;
            int widthPx = Math.max(1, (int) Math.round(sourceWidth * scale));
            int heightPx = Math.max(1, (int) Math.round(sourceHeight * scale));
            BufferedImage scaled = scale(image, widthPx, heightPx, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            byte[] bytes = encode(scaled, alpha);
            if (bytes == null || bytes.length == 0) return null;
            String extension = alpha ? ".png" : ".jpg";
            writeCache(root, dir.resolve(key + extension), bytes);
            return new Thumb(bytes, alpha ? "image/png" : "image/jpeg");
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    /** 缓存键：{@code sha1(相对路径 + "|" + w)} 的前 16 位十六进制。 */
    private static String cacheKey(Path root, Path source, int width) {
        try {
            String relative = root.toAbsolutePath().normalize().relativize(source.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((relative + "|" + width).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(16);
            for (int index = 0; index < 8; index++) builder.append(String.format("%02x", hash[index]));
            return builder.toString();
        } catch (NoSuchAlgorithmException | IllegalArgumentException error) {
            return null;
        }
    }

    /** 用内存流读第一帧（避免 ImageIO 默认在系统临时目录里再落一份缓存文件）。 */
    private static BufferedImage readImage(Path source) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            ImageInputStream stream = new MemoryCacheImageInputStream(input);
            try {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) return null;   // 格式不支持（例如 webp）：退回原图
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    return reader.read(0);
                } finally {
                    reader.dispose();
                }
            } finally {
                stream.close();
            }
        }
    }

    /** 先按比例分步减半再落到目标尺寸：一次缩太多（1664→320 是 5 倍）会明显发糊。 */
    private static BufferedImage scale(BufferedImage source, int width, int height, int type) {
        BufferedImage current = source;
        int currentWidth = source.getWidth();
        int currentHeight = source.getHeight();
        while (currentWidth > width * 2 && currentHeight > height * 2) {
            currentWidth = Math.max(width, currentWidth / 2);
            currentHeight = Math.max(height, currentHeight / 2);
            current = draw(current, currentWidth, currentHeight, type);
        }
        return draw(current, width, height, type);
    }

    private static BufferedImage draw(BufferedImage source, int width, int height, int type) {
        BufferedImage result = new BufferedImage(width, height, type);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static byte[] encode(BufferedImage image, boolean alpha) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(1 << 15);
        if (alpha) {
            if (!ImageIO.write(image, "png", output)) return null;
            return output.toByteArray();
        }
        writeJpeg(image, output);
        return output.toByteArray();
    }

    /** JPEG 质量 0.85（默认写出来偏大，图集要的是"明显更小"）。 */
    private static void writeJpeg(BufferedImage image, ByteArrayOutputStream output) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            if (!ImageIO.write(image, "jpg", output)) throw new IOException("没有可用的 JPEG 编码器。");
            return;
        }
        ImageWriter writer = writers.next();
        ImageOutputStream stream = ImageIO.createImageOutputStream(output);
        if (stream == null) {
            writer.dispose();
            if (!ImageIO.write(image, "jpg", output)) throw new IOException("没有可用的 JPEG 编码器。");
            return;
        }
        try (ImageOutputStream closeable = stream) {
            writer.setOutput(closeable);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(0.85f);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            closeable.flush();
        } finally {
            writer.dispose();
        }
    }

    /** 缓存写入是 best-effort：并发生成同一张图时谁先 move 成功谁算，失败的那次照样把字节返回给调用方。 */
    private static void writeCache(Path root, Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.write(temp, bytes);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            BYTES_SINCE_CHECK.addAndGet(bytes.length);
            if (WRITES_SINCE_CHECK.incrementAndGet() >= PRUNE_CHECK_WRITES
                    || BYTES_SINCE_CHECK.get() >= PRUNE_CHECK_BYTES) {
                WRITES_SINCE_CHECK.set(0);
                BYTES_SINCE_CHECK.set(0);
                pruneCache(root);
            }
        } catch (IOException ignored) {
            // 缓存写不进去只是慢一点，下次还会重算。
        }
    }

    /** 缓存目录当前总字节数（目录还不存在算 0）。 */
    public static long cacheSize(Path root) {
        Path dir = cacheDir(root);
        long total = 0;
        try (Stream<Path> walk = Files.walk(dir, 1)) {
            Iterator<Path> iterator = walk.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (!Files.isRegularFile(path)) continue;
                try { total += Files.size(path); } catch (IOException ignored) { /* 刚被删掉 */ }
            }
        } catch (IOException error) {
            return 0;
        }
        return total;
    }

    /**
     * 清缓存：目录总量超过 {@link #CACHE_LIMIT_BYTES}（约 300 MB）时按 mtime 从最旧的开始删，
     * 一直删到 90% 以内。没有"最近使用"的额外记账：mtime 就是写入时间，够用且简单。
     *
     * @return 清理之后的目录总字节数
     */
    public static long pruneCache(Path root) {
        return pruneCache(root, CACHE_LIMIT_BYTES);
    }

    /** 同上，阈值可换（单测用小阈值跑真删）；正式路径只走上面那个 300 MB。 */
    public static long pruneCache(Path root, long limitBytes) {
        Path dir = cacheDir(root);
        List<Path> files = new ArrayList<>();
        long total = 0;
        try (Stream<Path> walk = Files.walk(dir, 1)) {
            Iterator<Path> iterator = walk.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (!Files.isRegularFile(path)) continue;
                try {
                    total += Files.size(path);
                    files.add(path);
                } catch (IOException ignored) { /* 刚被别的线程删掉 */ }
            }
        } catch (IOException error) {
            return 0;
        }
        if (total <= limitBytes) return total;
        files.sort(Comparator.comparingLong(ImageThumbs::lastModified));
        long wanted = limitBytes * 9 / 10;
        for (Path path : files) {
            if (total <= wanted) break;
            long size;
            try { size = Files.size(path); } catch (IOException ignored) { continue; }
            try {
                if (Files.deleteIfExists(path)) total -= size;
            } catch (IOException ignored) { /* 正在被读的删不掉，留着下次 */ }
        }
        return total;
    }

    private static long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException error) { return Long.MAX_VALUE; }
    }
}

package cn.szu.bot.sd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 样式的展示图（<b>只给网页控制台看</b>）。
 *
 * <p>样式名是给人看的，可能带 {@code /}、空格、中文（比如
 * {@code 観月小鳥(mizuki kotori) 遊戯王ZEXAL / Tori Meadows Yu-Gi-Oh! ZEXAL 1}），
 * 直接拿来当文件名又长又危险，所以按名字的 SHA-256 前 16 位存成
 * {@code data/style-previews/<哈希>.png}——不需要额外的索引文件，改名时把文件跟着搬就行。
 *
 * <p>样式本身照旧存在 {@code data/local-styles.json}：展示图是纯网页装饰，
 * 丢了、删了都不影响样式能不能用（QQ 端也从来不看它）。
 */
public final class StylePreviews {

    private StylePreviews() { }

    public static Path dir(Path root) {
        return root.toAbsolutePath().normalize().resolve("data").resolve("style-previews");
    }

    public static Path file(Path root, String name) {
        return name == null || name.isBlank() ? null : dir(root).resolve(hash(name) + ".png");
    }

    public static boolean has(Path root, String name) {
        Path file = file(root, name);
        return file != null && Files.isRegularFile(file);
    }

    public static void save(Path root, String name, byte[] bytes) throws IOException {
        Path file = file(root, name);
        if (file == null || bytes == null || bytes.length < 32) return;
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** 样式改名后把图搬过去（否则新名字查不到、旧文件也永远没人删）。 */
    public static void rename(Path root, String from, String to) {
        Path source = file(root, from), target = file(root, to);
        if (source == null || target == null || !Files.isRegularFile(source)) return;
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { /* 搬不动就当没有：样式照旧可用 */ }
    }

    public static void delete(Path root, String name) {
        Path file = file(root, name);
        if (file == null) return;
        try { Files.deleteIfExists(file); } catch (Exception ignored) { }
    }

    /** 名字 → 文件名。16 个十六进制字符（64 bit）足够，也不会把目录塞满长名字。 */
    static String hash(String name) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception impossible) {
            // 没有 SHA-256 的 JVM 不存在的：退回 hashCode，至少还是确定性的。
            return Integer.toHexString(name.hashCode());
        }
    }
}

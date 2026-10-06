package cn.szu.bot.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * sha256 计算与比对。<b>纯 Java，不碰 android.*</b>，所以能在 JVM 单测里逐条断言 ——
 * 「下载完必须校验、不一致就不许安装」这个判断的正确性全靠这里。
 *
 * <p>两个刻意的设计：
 * <ul>
 *   <li>{@link #matches(String, String)} 是<b>字符串级</b>的比对，且显式校验长度必须为 64。
 *       这样 {@code null}、空串、短了一截的哈希、服务端多打的空格，全都算「不匹配」——
 *       判错的代价是「装上一个没校验过的包」，宁可保守。</li>
 *   <li>{@link #of(File)} 流式读取（8KB 一块），不把整个 APK 读进内存：APK 可能有几十 MB，
 *       低端机上一次性 {@code readAllBytes} 很容易 OOM。</li>
 * </ul>
 */
public final class Sha256 {

    /** sha256 十六进制串的长度。 */
    public static final int HEX_LENGTH = 64;

    private Sha256() { }

    /**
     * 计算文件的 sha256，返回<b>小写</b>十六进制串。
     *
     * @throws IOException 文件不存在 / 读不动 / 运行环境没有 SHA-256
     */
    public static String of(File file) throws IOException {
        if (file == null) throw new IOException("文件为空");
        if (!file.isFile()) throw new IOException("文件不存在：" + file.getAbsolutePath());
        MessageDigest digest = newDigest();
        try (InputStream in = new FileInputStream(file)) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) digest.update(chunk, 0, read);
        }
        return toHex(digest.digest());
    }

    /**
     * 比对「算出来的」和「服务端给的」哈希。任一为空、长度不是 64、或内容不同 → {@code false}。
     *
     * <p>用 {@link MessageDigest#isEqual} 而不是 {@code equals}：前者是定时安全（constant-time）比较，
     * 不会因为「前几位就对不上」而提前返回。这里的威胁模型很弱（哈希本来就是公开的），
     * 但用对的那一个没有任何代价。
     */
    public static boolean matches(String computedHex, String expectedHex) {
        String left = normalize(computedHex);
        String right = normalize(expectedHex);
        if (left.length() != HEX_LENGTH || right.length() != HEX_LENGTH) return false;
        return MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** 规范化一个十六进制哈希：去空白、转小写（方便比对与日志）。 */
    public static String normalize(String hex) {
        return hex == null ? "" : hex.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 这个字符串看起来像不像一个 sha256（64 位十六进制）。 */
    public static boolean looksLikeSha256(String hex) {
        String text = normalize(hex);
        if (text.length() != HEX_LENGTH) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean isHex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
            if (!isHex) return false;
        }
        return true;
    }

    private static MessageDigest newDigest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            // Android/JVM 一定带 SHA-256，真走到这里说明运行环境坏了。
            throw new IOException("运行环境没有 SHA-256 实现", error);
        }
    }

    /** 字节数组 → 小写十六进制（不用 HexFormat，minSdk 26 上 desugar 行为不值得赌）。 */
    public static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int v = value & 0xFF;
            if (v < 0x10) out.append('0');
            out.append(Integer.toHexString(v));
        }
        return out.toString();
    }
}

package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@link Sha256} 的断言。sha256 校验是「不许装错包」的唯一凭据，所以这里连边界都钉死：
 * 空文件、文件内容改一个字节、服务端给的哈希大小写、长度不对、非十六进制。
 */
public class Sha256Test {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** NIST 的公开测试向量：空串。 */
    @Test
    public void nistVectorEmptyString() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Sha256.toHex(digest("".getBytes(StandardCharsets.UTF_8))));
    }

    /** NIST 的公开测试向量：{@code "abc"}。 */
    @Test
    public void nistVectorAbc() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Sha256.toHex(digest("abc".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void hashesAFileAndDetectsAnyChange() throws Exception {
        File file = temporary.newFile("apk.bin");
        byte[] payload = fakeApkBytes(4096);
        write(file, payload);

        String actual = Sha256.of(file);
        assertEquals("文件哈希必须与独立算出来的结果一致", toHex(digest(payload)), actual);
        assertTrue(Sha256.matches(actual, toHex(digest(payload))));

        // 改一个字节 → 必须不匹配（这正是「下到一半就断了」要拦住的场景）。
        payload[2048] = (byte) (payload[2048] ^ 0xFF);
        write(file, payload);
        assertFalse("内容变了一个字节就不许匹配", Sha256.matches(Sha256.of(file), actual));
    }

    /** 截断文件（模拟下载中断）必须与完整文件的哈希不同。 */
    @Test
    public void truncatedDownloadNeverMatches() throws Exception {
        byte[] payload = fakeApkBytes(10_000);
        File full = temporary.newFile("full.apk");
        File half = temporary.newFile("half.apk");
        write(full, payload);
        write(half, java.util.Arrays.copyOf(payload, 5000));
        assertFalse(Sha256.matches(Sha256.of(half), Sha256.of(full)));
    }

    @Test
    public void comparisonIsCaseInsensitiveAndTrimTolerant() {
        String lower = "a4226e84d3d3f0e0b1c2a3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718";
        String upper = lower.toUpperCase(java.util.Locale.ROOT);
        assertTrue("服务端给大写也要认", Sha256.matches(lower, upper));
        assertTrue("服务端多打了空格也要认", Sha256.matches(lower, "  " + lower + "\n"));
    }

    @Test
    public void refusesToMatchOnAnythingButAWellFormedHash() {
        String good = "a4226e84d3d3f0e0b1c2a3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718";
        assertFalse("空串", Sha256.matches("", good));
        assertFalse("null", Sha256.matches(null, good));
        assertFalse("服务端没给", Sha256.matches(good, ""));
        assertFalse("短了（md5 长度）", Sha256.matches("a4226e84d3d3f0e0b1c2a3d4e5f60718", good));
        assertFalse("长了", Sha256.matches(good + "00", good));
        assertFalse("非十六进制字符", Sha256.matches(good.substring(0, 63) + "z", good));
        assertFalse("完全不同的哈希", Sha256.matches(good, good.replace('a', 'b')));
        assertTrue("正常的必须匹配", Sha256.matches(good, good));
    }

    @Test
    public void looksLikeSha256RejectsJunk() {
        assertTrue(Sha256.looksLikeSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        // 大写也要认（服务端可能给大写；normalize 之后再判）。
        assertTrue("大写十六进制必须被接受",
                Sha256.looksLikeSha256("0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertFalse(Sha256.looksLikeSha256(""));
        assertFalse(Sha256.looksLikeSha256(null));
        assertFalse(Sha256.looksLikeSha256("not-a-hash"));
        assertFalse("长度对但含非十六进制",
                Sha256.looksLikeSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeZ"));
    }

    @Test
    public void hexOutputIsAlwaysLowercaseAndPadded() {
        assertEquals("00", Sha256.toHex(new byte[]{0}));
        assertEquals("0f", Sha256.toHex(new byte[]{15}));
        assertEquals("ff", Sha256.toHex(new byte[]{(byte) 0xFF}));
        assertEquals("", Sha256.toHex(new byte[0]));
        assertNotEquals("0F", Sha256.toHex(new byte[]{15}));
    }

    // ---------------------------------------------------------------- 工具

    /** 造一段「像 APK」的字节流（ZIP 魔数开头 + 可复现的伪随机内容），避免各个测试各写一份。 */
    static byte[] fakeApkBytes(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = 'P';
        bytes[1] = 'K';
        bytes[2] = 3;
        bytes[3] = 4;
        for (int i = 4; i < size; i++) bytes[i] = (byte) ((i * 31 + 7) & 0xFF);
        return bytes;
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format("%02x", value));
        return out.toString();
    }
}

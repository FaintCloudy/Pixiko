package cn.szu.bot.sd;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 只读图片**文件头**取像素尺寸（PNG / JPEG / GIF / BMP / WebP），**不加载整张图**。
 *
 * <p>「LoRA 附带的展示图样式」要把展示图自己的宽高记进样式（{@code model.width/height}），
 * 而展示图可能有几 MB——为了两个整数去解码整张图不值得，也不该因为一张坏图把样式同步整个拖死。
 * 所以这里按格式直接读文件头那几个字节，读不出来就返回 {@code null}（调用方回退到预设/当前设置），
 * **绝不编一个尺寸出来**。
 *
 * <p>只有认不出的格式才退回 {@link ImageIO} 的 {@code ImageReader}（同样只读头、不解码像素），
 * 而且整段包在 try/catch 里：ImageIO 在某些构建上没有插件，读不了就当没有。
 */
public final class ImageSize {
    /** 只读文件开头这么多字节：够 PNG/GIF/BMP/WebP 的头，也够绝大多数 JPEG 的 SOF 段。 */
    private static final int HEAD_BYTES = 256 * 1024;

    private ImageSize() { }

    /** 文件的实际像素尺寸 {@code [宽, 高]}；读不出来（不存在、不是图片、头被截断）返回 null。 */
    public static int[] of(Path file) {
        if (file == null) return null;
        try {
            if (!Files.isRegularFile(file)) return null;
            try (InputStream input = Files.newInputStream(file)) {
                return of(input.readNBytes(HEAD_BYTES));
            }
        } catch (Exception error) {
            return null;
        }
    }

    /** 图片字节的实际像素尺寸 {@code [宽, 高]}；读不出来返回 null（只解析文件头，不解码像素）。 */
    public static int[] of(byte[] bytes) {
        if (bytes == null || bytes.length < 16) return null;
        int[] size = null;
        if (isPng(bytes)) size = png(bytes);
        else if (isGif(bytes)) size = gif(bytes);
        else if (isBmp(bytes)) size = bmp(bytes);
        else if (isJpeg(bytes)) size = jpeg(bytes);
        else if (isWebp(bytes)) size = webp(bytes);
        if (size == null) size = viaImageIo(bytes);
        return size != null && size[0] > 0 && size[1] > 0 ? size : null;
    }

    // ---------------------------------------------------------------- 各格式的文件头

    private static boolean isPng(byte[] bytes) {
        return bytes.length > 24 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
    }

    /** PNG：8 字节签名 + 4 字节长度 + "IHDR" + 宽(4) + 高(4)，全是大端。 */
    private static int[] png(byte[] bytes) {
        return new int[]{beInt(bytes, 16), beInt(bytes, 20)};
    }

    private static boolean isGif(byte[] bytes) {
        return bytes.length > 10 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8';
    }

    /** GIF：签名 6 字节 + 逻辑屏幕宽(2) + 高(2)，小端。 */
    private static int[] gif(byte[] bytes) {
        return new int[]{leShort(bytes, 6), leShort(bytes, 8)};
    }

    private static boolean isBmp(byte[] bytes) {
        return bytes.length > 26 && bytes[0] == 'B' && bytes[1] == 'M';
    }

    /** BMP：BITMAPINFOHEADER 的宽(4) / 高(4) 小端；高为负表示自上而下，取绝对值。 */
    private static int[] bmp(byte[] bytes) {
        return new int[]{leInt(bytes, 18), Math.abs(leInt(bytes, 22))};
    }

    private static boolean isJpeg(byte[] bytes) {
        return (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
    }

    /**
     * JPEG：从 SOI 开始逐段跳过（0xFF + 标记 + 2 字节大端段长），碰到 SOFn 就是帧头，
     * 帧头里偏移 +5 是高、+7 是宽。表段（DHT/JPG/DAC）不是帧头，要跳过。
     */
    private static int[] jpeg(byte[] bytes) {
        int at = 2;
        while (at + 9 < bytes.length) {
            if ((bytes[at] & 0xFF) != 0xFF) { at++; continue; }
            int marker = bytes[at + 1] & 0xFF;
            if (marker == 0xFF) { at++; continue; }
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD9) || marker == 0x01) { at += 2; continue; }
            int length = beShort(bytes, at + 2);
            if (length < 2) return null;
            boolean frame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (frame) {
                if (at + 9 >= bytes.length) return null;
                return new int[]{beShort(bytes, at + 7), beShort(bytes, at + 5)};
            }
            at += 2 + length;
        }
        return null;
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length > 30 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    /**
     * WebP 三种块各有各的写法：
     * <ul>
     *   <li>{@code VP8 }（有损）：帧头里同步码 {@code 9d 01 2a} 之后的 2+2 字节小端，各取低 14 位；</li>
     *   <li>{@code VP8L}（无损）：签名 {@code 0x2F} 之后的 4 字节里，宽-1 与高-1 各 14 位；</li>
     *   <li>{@code VP8X}（扩展）：画布宽-1 与高-1 各 24 位小端。</li>
     * </ul>
     */
    private static int[] webp(byte[] bytes) {
        String chunk = new String(bytes, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8 ": {
                if (bytes.length < 30) return null;
                if ((bytes[23] & 0xFF) != 0x9D || (bytes[24] & 0xFF) != 0x01 || (bytes[25] & 0xFF) != 0x2A) return null;
                return new int[]{leShort(bytes, 26) & 0x3FFF, leShort(bytes, 28) & 0x3FFF};
            }
            case "VP8L": {
                if (bytes.length < 25 || (bytes[20] & 0xFF) != 0x2F) return null;
                long bits = (bytes[21] & 0xFFL) | ((bytes[22] & 0xFFL) << 8) | ((bytes[23] & 0xFFL) << 16) | ((bytes[24] & 0xFFL) << 24);
                return new int[]{(int) (bits & 0x3FFF) + 1, (int) ((bits >> 14) & 0x3FFF) + 1};
            }
            case "VP8X": {
                if (bytes.length < 30) return null;
                return new int[]{le24(bytes, 24) + 1, le24(bytes, 27) + 1};
            }
            default:
                return null;
        }
    }

    /** 认不出的格式才问 ImageIO：只取 reader 的宽高，不 read 整张图。 */
    private static int[] viaImageIo(byte[] bytes) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (stream == null) return null;
            for (Iterator<ImageReader> readers = ImageIO.getImageReaders(stream); readers.hasNext(); ) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    return new int[]{reader.getWidth(0), reader.getHeight(0)};
                } finally {
                    reader.dispose();
                }
            }
        } catch (Exception | LinkageError error) {
            return null;    // 没有对应插件/无头环境：当作读不出来
        }
        return null;
    }

    // ---------------------------------------------------------------- 小端/大端小工具

    private static int beShort(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }

    private static int beInt(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16) | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }

    private static int leShort(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8);
    }

    private static int leInt(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8) | ((bytes[at + 2] & 0xFF) << 16) | ((bytes[at + 3] & 0xFF) << 24);
    }

    private static int le24(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8) | ((bytes[at + 2] & 0xFF) << 16);
    }
}

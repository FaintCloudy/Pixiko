package cn.szu.bot.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图片的下载与落盘。这是网页版<b>做不到</b>的部分（浏览器里只能右键另存/长按菜单），
 * 也是这个原生外壳存在的主要理由。
 *
 * <p>两条路径：
 * <ul>
 *   <li><b>Android 10+</b>：走 {@link MediaStore} 插进 {@code Pictures/Pixiko/}（分区存储，不需要任何权限）。
 *       写入期间置 {@code IS_PENDING=1}，写完清掉，这样相册不会看到写了一半的半张图。</li>
 *   <li><b>Android 9 及以下</b>：写公共 {@link Environment#DIRECTORY_PICTURES}/Pixiko，
 *       再用 {@link MediaScannerConnection} 通知媒体库（否则图在文件系统里但相册看不到）。</li>
 * </ul>
 *
 * <p>下载约束（防呆）：20s 超时、单张上限 40MB（先看 Content-Length，再看实际读到的字节）、
 * 只接受 {@code image/*}。整体是<b>全缓冲</b>的：先把字节读进内存确认没超限，再落盘，
 * 避免下到一半发现不是图片而留下垃圾文件。
 */
public final class ImageStore {

    public static final int TIMEOUT_MS = 20_000;
    public static final long MAX_BYTES = 40L * 1024 * 1024;

    /** 分享时缓存文件名递增，避免同名覆盖。 */
    private static final AtomicInteger SHARE_SEQ = new AtomicInteger(1);

    private ImageStore() { }

    /** 下载结果：要么拿到字节与 MIME，要么拿到一句人话错误。 */
    public static final class Download {
        public final byte[] bytes;
        public final String mime;
        public final String error;

        private Download(byte[] bytes, String mime, String error) {
            this.bytes = bytes;
            this.mime = mime;
            this.error = error;
        }

        static Download ok(byte[] bytes, String mime) { return new Download(bytes, mime, null); }

        static Download fail(String error) { return new Download(null, null, error); }

        public boolean ok() { return error == null && bytes != null && bytes.length > 0; }
    }

    /**
     * 把图片下载到内存。{@code url} 必须是绝对地址（相对地址由调用方用 {@link UrlHelper#absolutize} 拼好）。
     */
    public static Download download(String url) {
        if (url == null || url.isBlank()) return Download.fail("图片地址是空的。");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            // 不传 Authorization：/api/image 的令牌就在 URL 的 ?token= 里（网页就是这么给的）。
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return Download.fail("下载失败：HTTP " + code + "（图片可能已被清理，或令牌失效）。");
            }
            String mime = connection.getContentType();
            if (mime != null) {
                int semicolon = mime.indexOf(';');
                if (semicolon > 0) mime = mime.substring(0, semicolon);
                mime = mime.trim().toLowerCase(Locale.ROOT);
            }
            if (mime == null || mime.isEmpty()) mime = "image/png";
            if (!mime.startsWith("image/")) return Download.fail("这个地址返回的不是图片（" + mime + "）。");

            long declared = connection.getContentLengthLong();
            if (declared > MAX_BYTES) return Download.fail("图片太大（" + (declared / 1024 / 1024) + "MB，上限 40MB）。");

            InputStream stream = connection.getInputStream();
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(declared > 0 ? (int) Math.min(declared, 1 << 20) : 64 * 1024);
                byte[] chunk = new byte[32 * 1024];
                long total = 0;
                int read;
                while ((read = stream.read(chunk)) > 0) {
                    total += read;
                    if (total > MAX_BYTES) return Download.fail("图片超过 40MB，已中止。");
                    buffer.write(chunk, 0, read);
                }
                byte[] bytes = buffer.toByteArray();
                if (bytes.length == 0) return Download.fail("下载到 0 字节，图片可能已被清理。");
                return Download.ok(bytes, mime);
            } finally {
                Http.close(stream);
            }
        } catch (Exception error) {
            Log.w("图片下载失败", error);
            return Download.fail("下载失败：" + Log.describe(error));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 保存结果：成功给「存哪了」，失败给原因。 */
    public static final class SaveResult {
        public final boolean ok;
        public final String message;
        /** 落盘位置说明（成功时用），形如 {@code Pictures/Pixiko/xxx.png}。 */
        public final String location;

        private SaveResult(boolean ok, String message, String location) {
            this.ok = ok;
            this.message = message;
            this.location = location;
        }

        static SaveResult ok(String location) { return new SaveResult(true, "已保存到相册：" + location, location); }

        static SaveResult fail(String message) { return new SaveResult(false, message, null); }
    }

    /**
     * 保存到系统相册。调用方负责在主线程之外执行，并在主线程 Toast。
     *
     * @param context 任意 context（内部会用 application context）
     * @param url     图片绝对地址
     * @param hint    网页里 img 的 alt（拿来当文件名，可能为空）
     */
    public static SaveResult saveToGallery(Context context, String url, String hint) {
        Download download = download(url);
        if (!download.ok()) return SaveResult.fail(download.error);

        String name = buildFileName(hint, url, download.mime);
        Context app = context.getApplicationContext();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return saveViaMediaStore(app, name, download);
            }
            return saveViaPublicPictures(app, name, download);
        } catch (Exception error) {
            Log.w("保存图片失败", error);
            return SaveResult.fail("保存失败：" + Log.describe(error));
        }
    }

    /** Android 10+：MediaStore 分区存储，写 Pictures/Pixiko。 */
    private static SaveResult saveViaMediaStore(Context context, String name, Download download) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, download.mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Pixiko");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return SaveResult.fail("系统相册拒绝了这次保存（MediaStore.insert 返回空）。");
        boolean written = false;
        try {
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) return SaveResult.fail("打不开相册写入流。");
                out.write(download.bytes);
                out.flush();
                written = true;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        } finally {
            // 写失败要把占位记录删掉，否则相册里会留一条打不开的空图。
            if (!written) resolver.delete(uri, null, null);
        }
        return SaveResult.ok(Environment.DIRECTORY_PICTURES + "/Pixiko/" + name);
    }

    /** Android 9 及以下：直接写公共 Pictures 目录 + 通知媒体扫描。 */
    private static SaveResult saveViaPublicPictures(Context context, String name, Download download) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Pixiko");
        if (!dir.exists() && !dir.mkdirs()) return SaveResult.fail("建不了目录：" + dir.getAbsolutePath() + "（检查存储权限）。");
        File target = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(download.bytes);
            out.flush();
        }
        // 不扫描的话相册里看不见这张图（文件系统有、媒体库没有）。
        MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()},
                new String[]{download.mime}, null);
        return SaveResult.ok(Environment.DIRECTORY_PICTURES + "/Pixiko/" + name);
    }

    /**
     * 分享：把图片先落到 {@code cacheDir/share/}，再用 FileProvider 换成 content:// URI 走 ACTION_SEND。
     * 直接塞 file:// 会在 Android 7+ 触发 FileUriExposedException，必须走 FileProvider。
     *
     * <p>返回一句结果文案（成功给「已准备好，选个应用分享」）。<b>不在本方法里 Toast</b>：
     * 调用方（{@link PixikoBridge}）统一负责反馈，免得同时冒两条。
     */
    public static String share(Context context, String url, String hint, String chooserTitle) {
        Download download = download(url);
        Context app = context.getApplicationContext();
        if (!download.ok()) return download.error;
        try {
            File dir = new File(app.getCacheDir(), "share");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("建不了缓存目录");
            String name = "pixiko-share-" + SHARE_SEQ.getAndIncrement() + "-" + buildFileName(hint, url, download.mime);
            File file = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(download.bytes);
                out.flush();
            }
            Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(download.mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_TEXT, hint == null ? "" : hint);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, chooserTitle == null ? "分享图片" : chooserTitle);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(chooser);
            return "已准备好分享。";
        } catch (Exception error) {
            Log.w("分享图片失败", error);
            return "分享失败：" + Log.describe(error);
        }
    }

    /**
     * 拼一个像样的文件名：优先用网页给的 alt（回执里通常是文件短名），否则从 URL 的 path 参数抠，
     * 再否则用时间戳。扩展名一律按实际 MIME 决定，不能信 URL（/api/image 的地址结尾是 path 参数）。
     */
    public static String buildFileName(String hint, String url, String mime) {
        String base = sanitize(hint);
        if (base.isEmpty()) base = sanitize(lastPathSegment(url));
        if (base.isEmpty()) base = "pixiko-" + System.currentTimeMillis();
        // 去掉 hint 里可能带的扩展名，统一由 MIME 决定。
        int dot = base.lastIndexOf('.');
        if (dot > 0 && base.length() - dot <= 6) base = base.substring(0, dot);
        if (base.length() > 60) base = base.substring(0, 60);
        return base + extensionOf(mime);
    }

    private static String extensionOf(String mime) {
        String guess = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (guess != null && !guess.isBlank()) return "." + guess;
        if ("image/jpeg".equals(mime)) return ".jpg";
        if ("image/webp".equals(mime)) return ".webp";
        return ".png";
    }

    /** 从 {@code /api/image?token=…&path=…} 的 path 参数里取最后一段当候选文件名。 */
    private static String lastPathSegment(String url) {
        if (url == null) return "";
        try {
            String query = url.contains("?") ? url.substring(url.indexOf('?') + 1) : "";
            for (String part : query.split("&")) {
                int at = part.indexOf('=');
                if (at <= 0) continue;
                if (!part.substring(0, at).equals("path")) continue;
                String value = java.net.URLDecoder.decode(part.substring(at + 1), StandardCharsets.UTF_8.name());
                int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
                return slash >= 0 ? value.substring(slash + 1) : value;
            }
        } catch (Exception ignored) {
            // 解不出来就走后面的兜底。
        }
        String plain = UrlHelper.stripQuery(url);
        int slash = plain.lastIndexOf('/');
        return slash >= 0 ? plain.substring(slash + 1) : plain;
    }

    /**
     * 文件名净化：去掉路径分隔符与系统不接受的字符。
     * 中文/日文等非 ASCII 字母数字保留（相册里看起来才像人话）。
     */
    private static String sanitize(String text) {
        if (text == null) return "";
        String trimmed = text.strip();
        if (trimmed.isEmpty()) return "";
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            boolean bad = ch < 0x20 || "\\/:*?\"<>|".indexOf(ch) >= 0 || ch == '\n' || ch == '\r';
            if (bad) continue;
            out.append(ch == ' ' ? '_' : ch);
        }
        // 前导点会让文件在相册/文件管理里变成隐藏文件。
        while (out.length() > 0 && (out.charAt(0) == '.' || out.charAt(0) == '_')) out.deleteCharAt(0);
        return out.toString().strip();
    }
}

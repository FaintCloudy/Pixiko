package cn.szu.bot.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 自我更新的「下载 + 校验」这一段：把 APK 拉到 app 私有目录里，并证明它就是服务端说的那个包。
 *
 * <p><b>本类刻意不碰 android.*</b>（只用 {@link HttpURLConnection} 与 {@link File}），
 * 所以下载进度、断点续传、重试、以及最关键的 sha256 校验都能在 JVM 单测里对着本地假服务真跑一遍
 * （见 {@code app/src/test/…/SelfUpdateTest.java}）。真正的安装动作在 {@link UpdateInstaller}。
 *
 * <p><b>现实边界（必须如实说清）</b>：普通 app <b>无法静默自我替换</b>——静默安装需要 root 或
 * device-owner / 系统签名。所以本类做到的是「下载与校验全自动」，最后一步必须把包交给系统安装器，
 * 由用户在系统弹窗里点一次「安装」。这不是偷懒，是 Android 的安全模型。
 *
 * <p>四个关键实现点：
 * <ul>
 *   <li><b>断点续传</b>：先下到 {@code xxx.apk.part}，失败重试时带 {@code Range: bytes=N-} 接着下。
 *       服务端不支持 Range（回 200）时就truncate 重下，不硬凑。</li>
 *   <li><b>校验不过就地销毁</b>：sha256 不符时把 {@code .part} 删掉再重试；重试仍然不符就报错返回，
 *       <b>绝不</b>把没校验过的文件交给安装器。</li>
 *   <li><b>已完成就复用</b>：目标 APK 已存在且哈希对得上，直接返回它（用户上次下了没装 / 装了没成功，
 *       不必重下几十 MB）。</li>
 *   <li><b>并发保护</b>：同一目录下只允许一个下载在进行（{@code BUSY}），避免用户连点两次按钮
 *       把同一个 {@code .part} 写花。</li>
 * </ul>
 */
public final class SelfUpdate {

    /** 下载超时：连 8s、读 30s（内网很快，但别因为一次慢写就放弃）。 */
    public static final int CONNECT_TIMEOUT_MS = 8_000;
    public static final int READ_TIMEOUT_MS = 30_000;

    /** 最多尝试几次（含第一次）。断点续传让重试很便宜，所以给 3 次。 */
    public static final int MAX_ATTEMPTS = 3;

    /** APK 的 MIME（服务端契约里就是它；用来在下载前做一次便宜的「这是不是安装包」判断）。 */
    public static final String APK_MIME = "application/vnd.android.package-archive";

    /**
     * 两个 HTTP 状态码常量。
     *
     * <p>刻意写成本地字面量而不是 {@code HttpURLConnection.HTTP_PARTIAL_CONTENT}：
     * 后者在部分 Android SDK 版本里<b>没有</b>（编译期就找不到符号），写死 206/416 更稳，
     * 而且这两个数字本来就是 RFC 7233 定死的。
     */
    static final int HTTP_PARTIAL_CONTENT = 206;
    static final int HTTP_RANGE_NOT_SATISFIABLE = 416;

    /** 下载中的临时后缀（重试时按它的长度续传）。 */
    private static final String PART_SUFFIX = ".part";
    /** 校验过的成品后缀（{@code .apk} 结尾，FileProvider / 安装器都认）。 */
    private static final String VERIFIED_SUFFIX = ".verified.apk";

    /** 同一时刻只允许一个下载（进程内）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private SelfUpdate() { }

    /** 下载进度回调（在下载线程上调用，实现方自己切主线程）。 */
    public interface ProgressListener {
        /**
         * @param downloaded 已写入磁盘的字节数（含断点续传的前一段）
         * @param total      预期总字节数；{@code <= 0} 表示服务端没给 Content-Length（进度条转「不确定」）
         */
        void onProgress(long downloaded, long total);
    }

    /** 一次自我更新的结果。失败时 {@code message} 是可以直接显示给用户的一句人话。 */
    public static final class Result {
        public final boolean ok;
        /** 校验通过、可以交给安装器的 APK 文件（失败时为 null）。 */
        public final File apk;
        /** 实际算出来的 sha256（成功后可用于日志/回执）。 */
        public final String sha256;
        public final String message;

        private Result(boolean ok, File apk, String sha256, String message) {
            this.ok = ok;
            this.apk = apk;
            this.sha256 = sha256 == null ? "" : sha256;
            this.message = message == null ? "" : message;
        }

        static Result fail(String message) { return new Result(false, null, "", message); }
    }

    /**
     * 把 {@code info.apkUrl} 的 APK 下到 {@code dir} 并校验 {@code info.sha256}。
     *
     * <p><b>这是阻塞调用</b>：调用方自己放子线程（Android 上主线程不许做网络 IO）。
     *
     * @param dir      目标目录（Android 上传 {@code getCacheDir()/update}；单测传临时目录）
     * @param info     服务端给的更新信息（必须已有 apkUrl 与 sha256，见 {@link UpdateInfo#canInstall()}）
     * @param listener 进度回调，可为 null
     */
    public static Result download(UpdateInfo info, File dir, ProgressListener listener) {
        if (info == null) return Result.fail("没有可用的更新信息。");
        if (!info.hasDownload()) return Result.fail("服务端没有提供 APK 下载地址。");
        if (info.sha256.isEmpty()) return Result.fail("服务端没有提供 sha256，无法校验安装包，已拒绝下载。");
        if (!Sha256.looksLikeSha256(info.sha256)) {
            return Result.fail("服务端给的 sha256 不是 64 位十六进制（" + info.sha256.length() + " 位），无法校验，已拒绝下载。");
        }
        if (dir == null) return Result.fail("没有指定下载目录。");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return Result.fail("建不了下载目录：" + dir.getAbsolutePath());
        }

        if (!BUSY.compareAndSet(false, true)) return Result.fail("已经有一个下载在进行中，稍等一下。");
        try {
            return downloadLocked(info, dir, listener);
        } finally {
            BUSY.set(false);
        }
    }

    private static Result downloadLocked(UpdateInfo info, File dir, ProgressListener listener) {
        File part = new File(dir, targetName(info) + PART_SUFFIX);
        File done = new File(dir, targetName(info) + VERIFIED_SUFFIX);

        // 0) 上次已经下好且校验过的包还在：直接复用（用户上次可能只是没点安装）。
        if (done.isFile() && done.length() > 0) {
            try {
                String have = Sha256.of(done);
                if (Sha256.matches(have, info.sha256)) {
                    Log.i("更新包已在本地且 sha256 一致，复用：" + done.getName() + "（" + done.length() + " 字节）");
                    if (listener != null) listener.onProgress(done.length(), done.length());
                    return new Result(true, done, have, "已复用上次下载好的安装包。");
                }
                Log.w("本地已有更新包但 sha256 对不上（可能下了一半或服务端换了包），删掉重下");
                delete(done);
            } catch (IOException error) {
                Log.w("校验本地已有更新包失败，删掉重下", error);
                delete(done);
            }
        }

        String lastError = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long startAt = part.isFile() ? part.length() : 0L;
            if (startAt > 0) {
                Log.i("第 " + attempt + " 次尝试：从 " + startAt + " 字节处断点续传");
            }

            // ---- 第一步：只负责把字节写进 .part。失败时**保留** .part，下次从断点接着下。 ----
            long written;
            try {
                written = fetchToFile(info.apkUrl, part, startAt, info.sizeBytes, listener);
            } catch (IOException error) {
                lastError = Log.describe(error);
                Log.w("第 " + attempt + " 次下载失败（保留已下到的 " + (part.isFile() ? part.length() : 0)
                        + " 字节，下次续传）：" + lastError);
                if (attempt < MAX_ATTEMPTS) sleepQuietly(400L * attempt);
                continue;
            }

            // ---- 第二步：校验。这里是**唯一**能证明「下到的就是服务端说的那个包」的判据。 ----
            try {
                String computed = Sha256.of(part);
                if (!Sha256.matches(computed, info.sha256)) {
                    // 字节收全了（fetch 没报错）但内容不对 —— 这份数据是坏的，
                    // 留着只会让下次续传出更离谱的结果，所以删掉、下次从头下。
                    delete(part);
                    lastError = "sha256 校验不通过（算出来 " + head(computed, 16) + "…，服务端说 "
                            + head(info.sha256, 16) + "…，共 " + written + " 字节）";
                    Log.w("安装包 sha256 校验失败（第 " + attempt + " 次，已删除坏文件）：" + lastError);
                    continue;
                }

                // 校验通过：把 .part 改名成 .verified.apk。用 rename 而不是 copy，
                // 这样「存在的 .verified.apk ⇒ 哈希一定验过」这个不变量永远成立。
                delete(done);
                if (!part.renameTo(done)) {
                    // rename 失败（极少数 ROM 上跨挂载点）：退化成落一份新文件再删旧文件。
                    copy(part, done);
                    delete(part);
                }
                String finalHash = Sha256.of(done);
                if (!Sha256.matches(finalHash, info.sha256)) {
                    delete(done);
                    return Result.fail("安装包在校验后又被改动了，已删除，请重试。");
                }
                // 这里比大小才有意义：对不上说明服务端声明的 sizeBytes 与真实内容不一致。
                // sha256 已经一致 → 内容是可信的，所以只记一条日志，不因此判失败。
                if (info.sizeBytes > 0 && done.length() != info.sizeBytes) {
                    Log.w("注意：安装包大小与服务端声明的 sizeBytes 不一致（实际 " + done.length()
                            + "，声明 " + info.sizeBytes + "），但 sha256 一致，按可用处理");
                }
                Log.i("更新包下载并校验通过：" + done.getName() + "，" + done.length() + " 字节，sha256=" + finalHash);
                return new Result(true, done, finalHash, "下载完成，sha256 校验通过。");
            } catch (IOException error) {
                // 读文件/算哈希出错（磁盘满、文件被清掉…）：半成品也别留了。
                lastError = Log.describe(error);
                Log.w("第 " + attempt + " 次校验出错：" + lastError);
                delete(part);
                if (attempt < MAX_ATTEMPTS) sleepQuietly(400L * attempt);
            }
        }

        // 全试完了还是不行：把半成品删掉，别在用户手机里留垃圾。
        delete(part);
        return Result.fail("下载更新失败（已尝试 " + MAX_ATTEMPTS + " 次）：" + lastError);
    }

    /**
     * 真正的一次 HTTP 下载。返回最终文件长度。
     *
     * <p>Range 的三种应答都要处理对：
     * <ul>
     *   <li>{@code 206} —— 服务端支持续传，从 {@code startAt} 往后追加写；</li>
     *   <li>{@code 200} —— 服务端忽略了 Range（或我们本来就没要），<b>从头覆盖写</b>；</li>
     *   <li>{@code 416} —— 本地 .part 已经不小了（多半是上次下完但没验过），删掉让上层从头来。</li>
     * </ul>
     */
    private static long fetchToFile(String url, File part, long startAt, long expectedSize,
                                    ProgressListener listener) throws IOException {
        long resumeFrom = Math.max(0L, Math.min(startAt, part.isFile() ? part.length() : 0L));
        if (resumeFrom > 0 && expectedSize > 0 && resumeFrom > expectedSize) {
            // .part 比声明的还长：数据不可信，删掉重下。
            delete(part);
            resumeFrom = 0;
        }
        boolean rangeRequested = resumeFrom > 0;

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            // 更新接口按契约免令牌，所以这里不带 Authorization（见 UpdateChecker 里的说明）。
            connection.setRequestProperty("Accept", APK_MIME + ", application/octet-stream, */*");
            connection.setRequestProperty("User-Agent", userAgent());
            if (rangeRequested) connection.setRequestProperty("Range", "bytes=" + resumeFrom + "-");

            int code = connection.getResponseCode();
            if (code == HTTP_RANGE_NOT_SATISFIABLE) {   // 416
                delete(part);
                throw new IOException("服务端拒绝了断点续传（HTTP 416），已清掉半成品，请重试。");
            }
            if (code < 200 || code >= 300) {
                throw new IOException("下载失败：HTTP " + code);
            }

            boolean resumed = code == HTTP_PARTIAL_CONTENT;   // 206
            if (resumed) {
                long serverStart = parseContentRangeStart(connection.getHeaderField("Content-Range"));
                if (serverStart >= 0 && serverStart != resumeFrom) {
                    // 服务端给的起点和我们以为的不一样，这份数据不能要。
                    delete(part);
                    throw new IOException("服务端返回的 Content-Range 与请求的断点不一致，已重来。");
                }
            } else {
                // 200：服务端从头给，本地有半成品也得丢掉，否则拼出来一定是坏的。
                if (resumeFrom > 0) {
                    Log.w("服务端不支持断点续传（对 Range 回了 200），改为从头下载");
                    delete(part);
                }
                resumeFrom = 0;
            }

            verifyContentType(connection.getContentType());

            long remaining = connection.getContentLengthLong();
            // 算「总大小」给进度条用。两种应答语义不同：
            //   206 → Content-Length 是「这一段」的长度，总量 = 断点 + 这一段；
            //   200 → Content-Length 就是全文长度。
            // 服务端没给 Content-Length 时退回 sizeBytes；再没有就是 -1（进度条转不确定态）。
            long total = remaining > 0
                    ? (resumed ? resumeFrom + remaining : remaining)
                    : (expectedSize > 0 ? expectedSize : -1L);
            if (total <= 0 && expectedSize > 0) total = expectedSize;

            long written = resumeFrom;
            if (listener != null) listener.onProgress(written, total);

            try (OutputStream out = new FileOutputStream(part, resumed);
                 InputStream in = connection.getInputStream()) {
                byte[] chunk = new byte[64 * 1024];
                int read;
                long lastReported = written;
                while ((read = in.read(chunk)) > 0) {
                    out.write(chunk, 0, read);
                    written += read;
                    // 每 64KB 报一次进度：太密会把主线程刷爆，太疏进度条像卡住了。
                    if (listener != null && written - lastReported >= 64 * 1024) {
                        listener.onProgress(written, total);
                        lastReported = written;
                    }
                }
                out.flush();
            }
            if (listener != null) listener.onProgress(written, total);
            return written;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * {@code Content-Range: bytes 100-999/1000} → 100。解不出来返回 -1（那就只按写入口径判断，不硬失败）。
     */
    static long parseContentRangeStart(String header) {
        if (header == null) return -1;
        String text = header.trim();
        int space = text.indexOf(' ');
        if (space >= 0) text = text.substring(space + 1).trim();
        int dash = text.indexOf('-');
        if (dash <= 0) return -1;
        try {
            return Long.parseLong(text.substring(0, dash).trim());
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    /**
     * 内容类型粗检：只拦「明显不是安装包」的应答（例如服务端把 JSON 错误页回成了 200）。
     * 认不出来（null / 空 / octet-stream）就放行 —— 真正说话的是 sha256，不是 MIME。
     */
    private static void verifyContentType(String contentType) throws IOException {
        if (contentType == null) return;
        String mime = contentType;
        int semicolon = mime.indexOf(';');
        if (semicolon > 0) mime = mime.substring(0, semicolon);
        mime = mime.trim().toLowerCase(Locale.ROOT);
        if (mime.isEmpty()) return;
        if (mime.startsWith("text/") || mime.contains("json") || mime.contains("html")) {
            throw new IOException("这个地址返回的不是安装包（" + mime + "），可能服务端路径不对。");
        }
    }

    /** 目标文件名：带版本号与 versionCode，便于排查「装的是哪一版」。 */
    static String targetName(UpdateInfo info) {
        String version = info.version.isEmpty() ? "unknown" : info.version.replaceAll("[^0-9A-Za-z._-]", "_");
        return "pixiko-update-" + version + "-" + info.versionCode;
    }

    /** 清掉下载目录里的中间产物。升级成功后调用（新版本启动时），避免白白占着几十 MB。 */
    public static void cleanWorkFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(PART_SUFFIX) || name.endsWith(VERIFIED_SUFFIX)) {
                if (delete(file)) Log.d("已清理更新临时文件：" + name);
            }
        }
    }

    private static String userAgent() {
        // 不给 BuildConfig/系统版本，纯粹为了日志里能认出是谁在下载。
        return "PixikoAndroidUpdater/1.0";
    }

    private static boolean delete(File file) {
        if (file == null || !file.exists()) return false;
        return file.delete();
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new java.io.FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
            out.flush();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static String head(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}

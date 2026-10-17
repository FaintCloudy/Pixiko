package cn.szu.bot;

import cn.szu.bot.testsupport.TestEnvironment;
import cn.szu.bot.web.WebUiServer;
import com.google.gson.*;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 「实测请求/响应原文（含头）」的取证测试：起真的 WebUi 服务，渠道目录里放的是**测试现场造的两份假 APK**
 * （放在本次 run 的临时根 {@code work\tests\<run-id>\AppUpdateEvidenceTest\...} 里），不再硬链真机
 * {@code work/apk-lanip/release} 与 {@code android/dist}——那些是生产/真机文件，第 14 节要求测试不碰。
 *
 * <p>版本号同样不再硬编码：{@code versionName}/{@code versionCode} 从仓库的 {@code android/app/build.gradle}
 * 解析，假 APK 的文件名按解析结果生成，接口返回的字段再和解析值比对。这样发版升号（163 → 164…）不会让取证
 * 用例变红，也不会因为"本机没有那两份 APK"而跑不起来。
 *
 * <p>它把两个接口的完整请求行 + 响应头逐条打出来，方便写交付报告；断言只钉最关键的几条
 * （实测 sha256/字节数与磁盘一致、Range 的字节级切片、白名单拒绝），断言数计入自己的统计。
 */
public final class AppUpdateEvidenceTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final String TOKEN = "evidence-token-123456";
    /** 假 APK 的 {@code classes.dex} 里"烧"的地址：证明 notes 的地址确实是从 APK 字节里抠出来的。 */
    private static final String BAKED_ADDRESS = "10.20.30.40:8899";
    private static final int DEV_SIZE = 96 * 1024 + 13;
    private static final int RELEASE_SIZE = 128 * 1024 + 7;

    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "app-update-tests").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "evidence-");
        int port = freePort();
        try {
            // 版本号的唯一事实来源：仓库里的 build.gradle（解析，不硬编码）。
            Path repo = TestEnvironment.productionRoot();
            Path gradle = repo.resolve("android/app/build.gradle");
            check(Files.isRegularFile(gradle), "仓库里有 android/app/build.gradle：" + gradle);
            GradleVersion version = readGradleVersion(gradle);
            check(version.code() > 0 && !version.name().isBlank(),
                    "从 build.gradle 解析出版本号：versionName=" + version.name() + "，versionCode=" + version.code());
            String apkName = "pixiko-" + version.name() + "-debug.apk";
            // 被测服务只认自己根目录下的 android/app/build.gradle：把仓库那份原样放进测试根（只读源文件，
            // 不是生产数据），于是 versionCode 走"读 gradle"的正常路径，而不是兜底公式。
            Path rootGradle = root.resolve("android/app/build.gradle");
            Files.createDirectories(rootGradle.getParent());
            Files.copy(gradle, rootGradle, StandardCopyOption.REPLACE_EXISTING);

            // 现场造的两份假 APK（dev 是 zip，dex 里带烧入地址；release 是普通字节）。
            byte[] devBytes = writeZipApk(root.resolve("android/dist").resolve(apkName), BAKED_ADDRESS, DEV_SIZE);
            byte[] releaseBytes = writePlainApk(root.resolve("work/apk-lanip/release").resolve(apkName),
                    0xC3, RELEASE_SIZE);
            Path devFile = root.resolve("android/dist").resolve(apkName);
            Path releaseFile = root.resolve("work/apk-lanip/release").resolve(apkName);

            JsonObject config = new JsonObject();
            JsonObject webui = new JsonObject();
            webui.addProperty("enabled", true);
            webui.addProperty("host", "127.0.0.1");
            webui.addProperty("port", port);
            webui.addProperty("access_token", TOKEN);
            JsonObject appUpdate = new JsonObject();
            appUpdate.addProperty("remote_check", false);
            webui.add("app_update", appUpdate);
            config.add("webui", webui);
            Json.atomicWrite(root.resolve("config.json"), config);
            Files.createDirectories(root.resolve("data"));
            Files.writeString(root.resolve("data/deepseek-api-key.txt"), "test-key-not-real\n");

            long releaseSize = releaseBytes.length;
            long devSize = devBytes.length;
            String releaseSha = sha256(releaseBytes);
            String devSha = sha256(devBytes);
            System.out.println("FIXTURE dev     file: " + devFile + "  size=" + devSize + "  sha256=" + devSha);
            System.out.println("FIXTURE release file: " + releaseFile + "  size=" + releaseSize
                    + "  sha256=" + releaseSha);
            System.out.println("FIXTURE 版本号来自 " + gradle + "：versionName=" + version.name()
                    + " versionCode=" + version.code());
            System.out.println("FIXTURE dev APK 的 classes.dex 里烧入地址：" + BAKED_ADDRESS);

            Settings settings = new Settings(root);
            JsonObject sdConfig = new JsonObject();
            sdConfig.addProperty("base_url", "http://127.0.0.1:1");
            sdConfig.addProperty("timeout_seconds", 1);
            try (Bot bot = new Bot(settings, new cn.szu.bot.sd.SdClient(root, sdConfig),
                    (event, segments) -> CompletableFuture.completedFuture(null));
                 WebUiServer server = new WebUiServer(settings, bot)) {
                server.start();
                String base = "http://127.0.0.1:" + port;

                // ① dev（默认渠道，临时根 android/dist 里那份假 APK）——不带任何令牌
                HttpResponse<String> update = send(base, "POST", "/api/app/update", "{}", Map.of());
                dump("POST /api/app/update（无令牌，Host: 127.0.0.1:" + port + "）", update, false);
                JsonObject dev = Json.parse(update.body());
                equal(200, update.statusCode(), "无令牌 update 回 200");
                equal("dev", str(dev, "channel"), "默认渠道是 dev");
                equal(version.name(), str(dev, "version"), "version 来自假 APK 文件名（= build.gradle 的 versionName）");
                equal(devSize, dev.get("sizeBytes").getAsLong(), "dev 的 sizeBytes == 磁盘上假 APK 的实测字节数");
                equal(devSha, str(dev, "sha256"), "dev 的 sha256 == 磁盘上假 APK 的实测哈希");
                equal(base + "/api/app/apk", str(dev, "apkUrl"), "apkUrl 用请求的 Host 拼");
                equal(version.code(), dev.get("versionCode").getAsInt(),
                        "versionCode 读 build.gradle（" + version.code() + "，从文件解析后比对，不硬编码）");
                check(str(dev, "notes").contains("本机测试包"), "dev 的 notes 说明是测试包");
                check(str(dev, "notes").contains(BAKED_ADDRESS),
                        "dev 的 notes 带上了从 APK 字节里抠出的地址（" + BAKED_ADDRESS + "）：" + str(dev, "notes"));
                check(str(dev, "notes").matches("(?s).*\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+.*"),
                        "dev 的 notes 带上了 地址:端口：" + str(dev, "notes"));

                // ② Host 拼接的两种证据：① 真实请求里 apkUrl 就是「请求方连上来的地址:端口」；
                //    ② 换一个 Host 头的对照在 AppUpdateTest 里做（java.net.http 禁止设置 Host 头，
                //    那里直接用 AppUpdate.info 传两个不同的 Host 各断言一次）。
                equal(base + "/api/app/apk", str(dev, "apkUrl"),
                        "apkUrl == 请求方连上来的地址（不是写死的 IP）");
                check(!str(dev, "apkUrl").contains("0.0.0.0"), "apkUrl 里没有 0.0.0.0 这种点不开的值");

                // ③ GET /api/app/apk（无令牌）：整份字节 + 响应头
                HttpResponse<byte[]> apk = sendBytes(base, "/api/app/apk", null, Map.of());
                System.out.println();
                System.out.println("=== GET /api/app/apk（无令牌，整份）");
                System.out.println("< HTTP/1.1 " + apk.statusCode());
                apk.headers().map().forEach((name, values) ->
                        System.out.println("< " + name + ": " + String.join(", ", values)));
                System.out.println("< （字节体 " + apk.body().length + " 字节，前 16 字节 "
                        + hex(apk.body(), 16) + "）");
                equal(200, apk.statusCode(), "apk 整份下载回 200");
                equal(devSize, (long) apk.body().length, "字节数 == 假 APK 大小");
                equal(devSha, sha256(apk.body()), "下载字节的 sha256 == update 报的 sha256");
                equal("application/vnd.android.package-archive", header(apk, "Content-Type"), "Content-Type 正确");
                equal(String.valueOf(devSize), header(apk, "Content-Length"), "Content-Length 正确");
                equal("attachment; filename=\"" + apkName + "\"", header(apk, "Content-Disposition"),
                        "Content-Disposition 正确");
                equal(devSha, header(apk, "X-Pixiko-Sha256"), "X-Pixiko-Sha256 正确");
                equal("bytes", header(apk, "Accept-Ranges"), "Accept-Ranges: bytes");

                // ④ Range: bytes=0-99 → 206 + 100 字节 + Content-Range
                HttpResponse<byte[]> head = sendBytes(base, "/api/app/apk", "bytes=0-99", Map.of());
                System.out.println();
                System.out.println("=== GET /api/app/apk  Range: bytes=0-99");
                System.out.println("< HTTP/1.1 " + head.statusCode());
                head.headers().map().forEach((name, values) ->
                        System.out.println("< " + name + ": " + String.join(", ", values)));
                System.out.println("< " + hex(head.body(), head.body().length));
                equal(206, head.statusCode(), "Range 回 206");
                equal(100, head.body().length, "Range 正好 100 字节");
                equal("bytes 0-99/" + devSize, header(head, "Content-Range"), "Content-Range 正确");
                byte[] full = Files.readAllBytes(devFile);
                check(Arrays.equals(Arrays.copyOf(full, 100), head.body()), "前 100 字节与假 APK 逐字节相同");
                // 拼三段（含末尾）验证断点续传的拼接结果
                ByteArrayParts parts = new ByteArrayParts();
                for (String range : new String[]{"bytes=0-65535", "bytes=65536-", "bytes=-64"}) {
                    HttpResponse<byte[]> piece = sendBytes(base, "/api/app/apk", range, Map.of());
                    equal(206, piece.statusCode(), "拼接 Range " + range + " 回 206");
                    System.out.println("< 拼接段 " + range + " -> " + piece.body().length + " 字节，前 8 字节 "
                            + hex(piece.body(), 8) + "，Content-Range=" + header(piece, "Content-Range"));
                    parts.add(range, piece.body());
                }
                check(parts.matchesTail(full), "分段拼接（含后缀区间）与假 APK 的对应片段逐字节一致");
                HttpResponse<byte[]> tooBig = sendBytes(base, "/api/app/apk", "bytes=" + (devSize + 5) + "-", Map.of());
                equal(416, tooBig.statusCode(), "越界 Range 回 416");
                equal("bytes */" + devSize, header(tooBig, "Content-Range"), "416 带 bytes */总长");

                // ⑤ 安全：目录穿越 / 绝对路径 / 别的目录 / 备份包 / 不存在的版本全部 404
                System.out.println();
                System.out.println("=== 白名单（带 path= 的请求，全部应为 404）");
                for (String bad : new String[]{"../../config.json", "..\\..\\config.json", "config.json",
                        repo.toString().replace('\\', '/') + "/" + "work/apk-lanip/release/" + apkName,
                        repo.toString() + "\\android\\dist\\" + apkName,
                        "work/apk-lanip/lanip/" + apkName,
                        "pixiko-" + version.name() + "-debug-prev-20261005-040404.apk",
                        "pixiko-1.4.0-debug.apk", apkName + ".bak", "pixiko-DEBUG.apk"}) {
                    HttpResponse<byte[]> denied = sendBytes(base, "/api/app/apk?path="
                            + java.net.URLEncoder.encode(bad, StandardCharsets.UTF_8), null, Map.of());
                    System.out.println("< path=" + bad + " -> " + denied.statusCode());
                    equal(404, denied.statusCode(), "白名单外的 path 被拒绝：" + bad);
                }
                // 对照组：只写文件名（不带目录）是允许的
                HttpResponse<byte[]> allowed = sendBytes(base, "/api/app/apk?path="
                        + java.net.URLEncoder.encode(apkName, StandardCharsets.UTF_8), null, Map.of());
                equal(200, allowed.statusCode(), "只写合法文件名可以下载（对照组，说明上面的 404 不是「一律拒绝」）");

                // ⑥ release 渠道：同一台服务改配置立刻生效，字节换成发行包
                JsonObject toRelease = Json.parse(Files.readString(root.resolve("config.json")));
                JsonObject releaseSection = new JsonObject();
                releaseSection.addProperty("channel", "release");
                releaseSection.addProperty("remote_check", false);
                toRelease.getAsJsonObject("webui").add("app_update", releaseSection);
                Json.atomicWrite(root.resolve("config.json"), toRelease);
                settings.reload();
                JsonObject release = Json.parse(send(base, "POST", "/api/app/update", "{}", Map.of()).body());
                System.out.println();
                System.out.println("=== release 渠道（改配置后不重启）");
                System.out.println("< " + Json.GSON.toJson(release).replace("\n", " "));
                equal("release", str(release, "channel"), "渠道切到 release");
                equal(releaseSize, release.get("sizeBytes").getAsLong(), "release 的 sizeBytes 是发行包实测值");
                equal(releaseSha, str(release, "sha256"), "release 的 sha256 是发行包实测值");
                HttpResponse<byte[]> releaseApk = sendBytes(base, "/api/app/apk", null, Map.of());
                equal(releaseSha, sha256(releaseApk.body()), "release 下载字节的 sha256 == 报的 sha256");
                // 渠道隔离：release 渠道下 dev 目录那份（名字一样、内容不同）下不到；
                // 反过来「只写渠道目录名」是允许的（等价于当前那份，已由 ⑤ 的对照组覆盖）。
                HttpResponse<byte[]> crossChannel = sendBytes(base, "/api/app/apk?path=" + java.net.URLEncoder
                        .encode("android/dist/" + apkName, StandardCharsets.UTF_8), null, Map.of());
                System.out.println("< 跨渠道 path=android/dist/" + apkName + " -> " + crossChannel.statusCode());
                equal(404, crossChannel.statusCode(), "release 渠道下不许下 dev 渠道目录里的包");
                HttpResponse<byte[]> sameName = sendBytes(base, "/api/app/apk?path=" + java.net.URLEncoder
                        .encode(apkName, StandardCharsets.UTF_8), null, Map.of());
                equal(releaseSize, (long) sameName.body().length, "同名的当前渠道那份照常能下（内容=发行包）");

                // ⑦ 对照组：别的 /api/** 不带令牌仍然 401
                for (String path : new String[]{"/api/status", "/api/settings", "/api/definitely-not-a-route-xyz"}) {
                    HttpResponse<String> denied = send(base, "POST", path, "{}", Map.of());
                    System.out.println("< 对照组 " + path + "（无令牌）-> " + denied.statusCode());
                    equal(401, denied.statusCode(), "别的接口/路由无令牌仍然 401：" + path);
                }
            }
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
        System.out.println();
        System.out.println("AppUpdateEvidenceTest: " + assertions
                + " assertions passed（临时根里的假 APK 实测；版本号从 build.gradle 解析）");
    }

    // ------------------------------------------------------------------ 假 APK 夹具

    /**
     * 造一个**合法 zip** 的假 APK：{@code classes.dex} 里带 {@code 地址:端口}，用来验证
     * {@code AppUpdate.bakedAddress} 真的从包内字节里抠地址（不是拿配置里的值假装知道）。
     */
    private static byte[] writeZipApk(Path target, String address, int size) throws Exception {
        Files.createDirectories(target.getParent());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write("<?xml version=\"1.0\" encoding=\"utf-8\"?><manifest package=\"cn.szu.bot.test\"/>"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(fakeDex(address, size));
            zip.closeEntry();
        }
        byte[] bytes = buffer.toByteArray();
        Files.write(target, bytes);
        return bytes;
    }

    /**
     * 假 {@code classes.dex} 的字节：开头是 {@code BuildConfig} 里的烧入地址，后面是**不可压缩**的伪随机字节
     * （用固定种子的 LCG，所以内容确定、zip 不会被压成几百字节——Range 用例需要文件本身够大）。
     */
    private static byte[] fakeDex(String address, int size) {
        byte[] dex = new byte[Math.max(size, 512)];
        byte[] prefix = ("dex\n035\u0000PIXIKO_DEFAULT_HOST=" + address + "\u0000").getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(prefix, 0, dex, 0, Math.min(prefix.length, dex.length));
        long state = 0x2545F4914F6CDD1DL;
        for (int index = prefix.length; index < dex.length; index++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            dex[index] = (byte) (state >>> 56);
        }
        return dex;
    }

    /** 造一个内容确定但**不是 zip** 的假 APK（release 渠道只用到字节数/哈希）。 */
    private static byte[] writePlainApk(Path target, int seed, int size) throws Exception {
        Files.createDirectories(target.getParent());
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) bytes[index] = (byte) ((seed + index * 31) & 0xFF);
        Files.write(target, bytes);
        return bytes;
    }

    /** {@code android/app/build.gradle} 里的版本号（测试从文件解析，绝不硬编码）。 */
    private record GradleVersion(int code, String name) { }

    private static GradleVersion readGradleVersion(Path gradle) throws Exception {
        String text = Files.readString(gradle, StandardCharsets.UTF_8);
        java.util.regex.Matcher code = java.util.regex.Pattern
                .compile("(?m)^\\s*versionCode\\s+(\\d+)").matcher(text);
        java.util.regex.Matcher name = java.util.regex.Pattern
                .compile("(?m)^\\s*versionName\\s+[\"']([^\"']+)[\"']").matcher(text);
        if (!code.find()) throw new AssertionError("build.gradle 里没有 versionCode 行：" + gradle);
        if (!name.find()) throw new AssertionError("build.gradle 里没有 versionName 行：" + gradle);
        return new GradleVersion(Integer.parseInt(code.group(1)), name.group(1));
    }

    private static final class ByteArrayParts {
        private final Map<String, byte[]> parts = new LinkedHashMap<>();

        void add(String label, byte[] bytes) { parts.put(label, bytes); }

    /** 三段拼接后与整份文件的对应位置逐字节比较：前 64 KiB + 从 64 KiB 到末尾-64 + 最后 64。 */
    boolean matchesTail(byte[] full) {
        byte[] a = parts.get("bytes=0-65535");
        byte[] b = parts.get("bytes=65536-");
        byte[] c = parts.get("bytes=-64");
        if (a == null || b == null || c == null) return false;
        if (!diff("a", a, full, 0)) return false;
        if (!diff("b", b, full, 65536)) return false;
        if (!diff("c", c, full, full.length - 64)) return false;
        return true;
    }

    private static boolean diff(String label, byte[] piece, byte[] full, int offset) {
        for (int index = 0; index < piece.length; index++) {
            if (piece[index] == full[offset + index]) continue;
            System.out.println("< 不一致 " + label + " 段内 offset=" + index + " 全局=" + (offset + index)
                    + " 收到=" + String.format("%02x", piece[index])
                    + " 文件=" + String.format("%02x", full[offset + index]));
            return false;
        }
        return true;
    }
    }

    private static void dump(String label, HttpResponse<String> response, boolean body) {
        System.out.println();
        System.out.println("=== " + label);
        System.out.println("> Content-Type: application/json");
        System.out.println("> （无 Authorization / X-Webui-Token / token 查询串）");
        System.out.println("< HTTP/1.1 " + response.statusCode());
        response.headers().map().forEach((name, values) ->
                System.out.println("< " + name + ": " + String.join(", ", values)));
        if (body) System.out.println("< " + response.body());
        else System.out.println("< " + response.body().replace("\n", " "));
    }

    // ------------------------------------------------------------------ HTTP 小工具

    private static HttpResponse<String> send(String base, String method, String path, String body,
                                             Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        for (var entry : headers.entrySet()) builder.header(entry.getKey(), entry.getValue());
        if ("GET".equals(method)) builder.GET();
        else builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<byte[]> sendBytes(String base, String path, String range,
                                                  Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        for (var entry : headers.entrySet()) {
            if (!"Set-Path".equals(entry.getKey())) builder.header(entry.getKey(), entry.getValue());
        }
        if (range != null) builder.header("Range", range);
        return HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private static String hex(byte[] bytes, int count) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < Math.min(count, bytes.length); index++)
            text.append(String.format(Locale.ROOT, "%02x", bytes[index]));
        return text.toString();
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder text = new StringBuilder();
        for (byte value : digest.digest(bytes)) text.append(String.format(Locale.ROOT, "%02x", value));
        return text.toString();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static String str(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }
}

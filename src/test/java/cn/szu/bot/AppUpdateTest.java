package cn.szu.bot;

import cn.szu.bot.web.WebUiServer;
import com.google.gson.*;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安卓端自动更新（{@code POST /api/app/update} 与 {@code GET /api/app/apk}）的端到端测试。
 *
 * <p>起真的 WebUi 服务（回环 + 随机端口 + 网页令牌），两个渠道目录里放**临时造**的假 APK（绝不碰真机文件），
 * GitHub 远端用注入的本地桩（绝不联网）。覆盖：渠道开关（dev/release + 默认 dev）、契约字段与磁盘实测值一致、
 * {@code apkUrl} 用请求 Host 拼、没有包时 200 + 空 apkUrl、字节流与 Content-Type/Content-Length/
 * X-Pixiko-Sha256、Range（206 / 416 / 整段拼接）、安全白名单（目录穿越 / 绝对路径 / 另一渠道的包 /
 * 备份包）、免令牌白名单（别的 {@code /api/**} 仍要令牌、不存在路由仍 401）、远端查询的接入与失败兜底、
 * 以及 sha256 的 mtime 缓存。
 */
public final class AppUpdateTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final String TOKEN = "test-token-123456";
    private static final int DEV_SIZE = 256 * 1024 + 37;
    private static final int RELEASE_SIZE = 192 * 1024 + 11;
    private static final String DEV_VERSION = "1.7.0";
    private static final String RELEASE_VERSION = "1.6.0";

    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "app-update-tests").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        int port = freePort();
        AtomicInteger stubCalls = new AtomicInteger();
        try {
            JsonObject config = new JsonObject();
            JsonObject webui = new JsonObject();
            webui.addProperty("enabled", true);
            webui.addProperty("host", "127.0.0.1");
            webui.addProperty("port", port);
            webui.addProperty("access_token", TOKEN);
            // 服务端这一路把远端查询关掉（并留一个不可达的 github_api 双保险）：
            // version/releaseUrl 必须只由本机磁盘决定，测试绝不允许碰真网（远端接入由 ⑪ 的本地桩单独验）。
            JsonObject appUpdate = new JsonObject();
            appUpdate.addProperty("remote_check", false);
            appUpdate.addProperty("github_api", "http://127.0.0.1:1/releases/latest");
            // 渠道这一节故意不写 channel：断言「不写 channel 时默认 dev」。
            webui.add("app_update", appUpdate);
            config.add("webui", webui);
            Json.atomicWrite(root.resolve("config.json"), config);
            Files.createDirectories(root.resolve("data"));
            Files.writeString(root.resolve("data/deepseek-api-key.txt"), "test-key-not-real\n");
            // versionCode 的权威来源：android/app/build.gradle（与渠道目录里的包无关）。
            Path gradle = root.resolve("android/app");
            Files.createDirectories(gradle);
            Files.writeString(gradle.resolve("build.gradle"),
                    "android {\n    defaultConfig {\n        versionCode 170\n        versionName \"1.7.0\"\n    }\n}\n");

            byte[] devBytes = createApk(root, "android/dist", "pixiko-" + DEV_VERSION + "-debug.apk", 0x5A, DEV_SIZE);            byte[] releaseBytes = createApk(root, "work/apk-lanip/release",
                    "pixiko-" + RELEASE_VERSION + "-debug.apk", 0xC3, RELEASE_SIZE);
            // 渠道目录里的「备份包」与别的目录里的同名包：都不许被下发。
            createApk(root, "work/apk-lanip/release", "pixiko-1.6.0-debug-prev-20261005.apk", 0x11, 4096);
            createApk(root, "work/apk-lanip/lanip", "pixiko-1.6.0-debug.apk", 0x22, 4096);
            createApk(root, "android/dist", "pixiko-withhost-1.6.0-debug.apk", 0x33, 4096);

            String devSha = sha256(devBytes);
            String releaseSha = sha256(releaseBytes);

            Settings settings = new Settings(root);
            // SD 只是 Bot 的必填依赖：这个测试一次都不碰它（指向一个没人监听的回环端口，reachable=false）。
            JsonObject sdConfig = new JsonObject();
            sdConfig.addProperty("base_url", "http://127.0.0.1:1");
            sdConfig.addProperty("timeout_seconds", 1);
            cn.szu.bot.sd.SdClient sd = new cn.szu.bot.sd.SdClient(root, sdConfig);
            try (Bot bot = new Bot(settings, sd,
                    (event, segments) -> CompletableFuture.completedFuture(null));
                 WebUiServer server = new WebUiServer(settings, bot)) {
                server.start();
                String base = "http://127.0.0.1:" + port;

                // ① 契约字段 + dev 渠道（默认）
                JsonObject update = json(base, "POST", "/api/app/update", "{}", Map.of(), 200);
                equal(Set.of("version", "versionCode", "sizeBytes", "sha256", "apkUrl", "releaseUrl",
                                "publishedAt", "notes", "channel", "remoteVersion"), update.keySet(),
                        "update 的键就是契约那 8 个 + channel/remoteVersion");
                equal("dev", str(update, "channel"), "不配 channel 时默认 dev");
                equal(DEV_VERSION, str(update, "version"), "dev 渠道的版本来自文件名");
                equal(170, update.get("versionCode").getAsInt(), "versionCode 读 android/app/build.gradle");
                equal((long) DEV_SIZE, update.get("sizeBytes").getAsLong(), "sizeBytes 是磁盘实测字节数");
                equal(devSha, str(update, "sha256"), "sha256 是磁盘实测哈希");
                equal(base + "/api/app/apk", str(update, "apkUrl"), "apkUrl 用请求的 Host 拼");
                check(str(update, "publishedAt").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"),
                        "publishedAt 是 ISO-8601 秒级 UTC：" + str(update, "publishedAt"));
                long age = Math.abs(Duration.between(Instant.parse(str(update, "publishedAt")), Instant.now()).toSeconds());
                check(age < 600, "publishedAt 来自文件 mtime（" + age + " 秒前）");
                check(str(update, "releaseUrl").equals("https://github.com/FaintCloudy/Pixiko/releases/tag/v1.7.0"),
                        "releaseUrl 指向 GitHub release：" + str(update, "releaseUrl"));

                // ② 磁盘上的值真的对得上（测试自己再算一遍，不是信接口）
                Path devFile = root.resolve("android/dist/pixiko-" + DEV_VERSION + "-debug.apk");
                equal(Files.size(devFile), update.get("sizeBytes").getAsLong(), "sizeBytes == Files.size");
                equal(sha256(Files.readAllBytes(devFile)), str(update, "sha256"), "sha256 == 测试独立算的哈希");
                byte[] changed = devBytes.clone();
                changed[0] = (byte) (changed[0] ^ 0xFF);
                Files.write(devFile, changed);
                Files.setLastModifiedTime(devFile, java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() + 2000));
                check(!sha256(changed).equals(devSha), "改一个字节后哈希确实不同（前提成立）");
                JsonObject afterEdit = json(base, "POST", "/api/app/update", "{}", Map.of(), 200);
                equal(sha256(changed), str(afterEdit, "sha256"), "文件变了 sha256 跟着变（缓存按 mtime 失效）");
                Files.write(devFile, devBytes);
                Files.setLastModifiedTime(devFile, java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() + 4000));
                JsonObject restored = json(base, "POST", "/api/app/update", "{}", Map.of(), 200);
                equal(devSha, str(restored, "sha256"), "内容改回来哈希也回来（不是把旧值钉死）");

                // ③ 渠道说明不许静默：dev 的 notes 里必须写明是测试包
                check(str(restored, "notes").contains("本机测试包"),
                        "dev 渠道 notes 写明本机测试包：" + str(restored, "notes"));
                check(str(restored, "notes").contains("仅供内网测试"), "dev 渠道 notes 写明仅供内网测试");
                check(str(restored, "notes").contains("未烧入地址"),
                        "假 APK 里没有烧入地址时如实走兜底路径：" + str(restored, "notes"));
                check(str(restored, "notes").contains("机器人对外广播"),
                        "抠不到烧入地址时如实说明改用机器人广播地址：" + str(restored, "notes"));

                // ④ release 渠道：同一台服务、改配置立刻生效，字节与哈希换成 release 包
                setChannel(root, settings, "release");
                JsonObject release = json(base, "POST", "/api/app/update", "{}", Map.of(), 200);
                equal("release", str(release, "channel"), "配置成 release 后 channel=release");
                equal(RELEASE_VERSION, str(release, "version"), "release 渠道的版本来自 release 目录");
                equal((long) RELEASE_SIZE, release.get("sizeBytes").getAsLong(), "release 的 sizeBytes 是实测值");
                equal(releaseSha, str(release, "sha256"), "release 的 sha256 是实测值");
                check(!releaseSha.equals(devSha), "两个渠道的 APK 确实不同（哈希不同）");
                equal(170, release.get("versionCode").getAsInt(), "versionCode 仍以 build.gradle 为准");
                check(str(release, "notes").contains("发行包"), "release 渠道 notes 写明是发行包");
                check(str(release, "notes").contains("不含局域网地址"), "release 渠道 notes 说明不含局域网地址");
                check(!str(release, "notes").contains("本机测试包"), "release 渠道不再声称是测试包");

                // ⑤ apk 字节流：内容、头部、哈希三处对齐
                HttpResponse<byte[]> download = raw(base, "/api/app/apk", null, null);
                equal(200, download.statusCode(), "GET /api/app/apk 回 200");
                equal(releaseBytes.length, download.body().length, "整份下载的字节数与磁盘一致");
                equal("application/vnd.android.package-archive", header(download, "Content-Type"), "APK 的 Content-Type");
                equal(String.valueOf(RELEASE_SIZE), header(download, "Content-Length"), "Content-Length = sizeBytes");
                equal("attachment; filename=\"pixiko-" + RELEASE_VERSION + "-debug.apk\"",
                        header(download, "Content-Disposition"), "Content-Disposition 带文件名");
                equal(releaseSha, header(download, "X-Pixiko-Sha256"), "X-Pixiko-Sha256 = update 里的 sha256");
                equal("bytes", header(download, "Accept-Ranges"), "声明支持 Range");
                check(Arrays.equals(releaseBytes, download.body()), "下载到的字节与磁盘上的 release APK 完全一致");
                equal(sha256(download.body()), str(release, "sha256"), "下载字节的 sha256 == update 给的 sha256");

                // ⑥ Range：bytes=0-99 → 206 + 100 字节 + Content-Range
                HttpResponse<byte[]> first = raw(base, "/api/app/apk", null, "bytes=0-99");
                equal(206, first.statusCode(), "Range 请求回 206");
                equal(100, first.body().length, "bytes=0-99 正好 100 字节");
                equal("bytes 0-99/" + RELEASE_SIZE, header(first, "Content-Range"), "Content-Range 正确");
                equal("100", header(first, "Content-Length"), "Range 响应的 Content-Length 是片段长度");
                equal(releaseSha, header(first, "X-Pixiko-Sha256"), "Range 响应也带整份文件的 sha256");
                check(Arrays.equals(Arrays.copyOf(releaseBytes, 100), first.body()), "前 100 字节逐字节相同");

                // ⑦ 拼接 Range 结果 == 整文件（分三段取，逐段比字节）
                long[] starts = {0, 1000, RELEASE_SIZE - 7L};
                long[] lengths = {1000, RELEASE_SIZE - 1007L, 7};
                for (int index = 0; index < starts.length; index++) {
                    String range = "bytes=" + starts[index] + "-" + (starts[index] + lengths[index] - 1);
                    HttpResponse<byte[]> part = raw(base, "/api/app/apk", null, range);
                    equal(206, part.statusCode(), "分段 Range " + range + " 回 206");
                    equal(lengths[index], (long) part.body().length, "分段 Range " + range + " 长度正确");
                    byte[] expected = Arrays.copyOfRange(releaseBytes, (int) starts[index],
                            (int) (starts[index] + lengths[index]));
                    check(Arrays.equals(expected, part.body()), "分段 Range " + range + " 内容逐字节一致");
                }
                HttpResponse<byte[]> openEnded = raw(base, "/api/app/apk", null, "bytes=1000-");
                equal(206, openEnded.statusCode(), "bytes=1000- 回 206");
                equal((long) (RELEASE_SIZE - 1000), (long) openEnded.body().length, "bytes=1000- 一直到文件末尾");
                check(Arrays.equals(Arrays.copyOfRange(releaseBytes, 1000, RELEASE_SIZE), openEnded.body()),
                        "开放区间的内容逐字节一致");
                HttpResponse<byte[]> suffix = raw(base, "/api/app/apk", null, "bytes=-64");
                equal(206, suffix.statusCode(), "后缀 Range bytes=-64 回 206");
                equal(64, suffix.body().length, "后缀 Range 取最后 64 字节");
                check(Arrays.equals(Arrays.copyOfRange(releaseBytes, RELEASE_SIZE - 64, RELEASE_SIZE), suffix.body()),
                        "后缀 Range 的内容逐字节一致");
                HttpResponse<byte[]> past = raw(base, "/api/app/apk", null, "bytes=" + (RELEASE_SIZE + 10) + "-");
                equal(416, past.statusCode(), "越界 Range 回 416");
                equal("bytes */" + RELEASE_SIZE, header(past, "Content-Range"), "416 带 bytes */总长");
                HttpResponse<byte[]> bad = raw(base, "/api/app/apk", null, "bytes=abc-def");
                equal(200, bad.statusCode(), "认不出来的 Range 按 RFC 7233 忽略（整份 200）");
                equal(releaseBytes.length, bad.body().length, "认不出的 Range 回整份文件");

                // ⑧ 安全：另一个渠道的包、目录穿越、任意路径全部 404
                equal(404, raw(base, "/api/app/apk", "path=" + enc("pixiko-" + DEV_VERSION + "-debug.apk"), null)
                                .statusCode(),
                        "release 渠道下不许下 dev 渠道的同名包（只看当前渠道目录）");
                equal(404, raw(base, "/api/app/apk", "path=" + enc("work/apk-lanip/lanip/pixiko-1.6.0-debug.apk"), null)
                                .statusCode(),
                        "白名单目录之外的包（lanip/）被拒绝");
                equal(404, raw(base, "/api/app/apk", "path=" + enc("../../../etc/passwd"), null).statusCode(),
                        "目录穿越被拒绝");
                equal(404, raw(base, "/api/app/apk",
                        "path=" + enc("work/apk-lanip/release/../../config.json"), null).statusCode(),
                        "带 .. 的路径被拒绝");
                equal(404, raw(base, "/api/app/apk",
                        "path=" + enc("F:/Bot/work/apk-lanip/release/pixiko-1.6.0-debug.apk"), null).statusCode(),
                        "Windows 绝对路径被拒绝");
                equal(404, raw(base, "/api/app/apk", "path=" + enc("/api/app/apk"), null).statusCode(),
                        "前导斜杠的伪路径被拒绝");
                equal(200, raw(base, "/api/app/apk", "path=" + enc("release/pixiko-" + RELEASE_VERSION + "-debug.apk"),
                        null).statusCode(),
                        "只写渠道目录名 + 合法文件名是允许的（等价于默认那份）");
                equal(404, raw(base, "/api/app/apk",
                        "path=" + enc("work/apk-lanip/lanip/pixiko-" + RELEASE_VERSION + "-debug.apk"), null)
                                .statusCode(),
                        "路径里写了别的目录（lanip）→ 拒绝");
                equal(404, raw(base, "/api/app/apk",
                        "path=" + enc("notrelease/pixiko-" + RELEASE_VERSION + "-debug.apk"), null).statusCode(),
                        "目录名不是当前渠道目录名 → 拒绝（不是只看结尾）");
                equal(404, raw(base, "/api/app/apk",
                        "path=" + enc("pixiko-1.6.0-debug-prev-20261005.apk"), null).statusCode(),
                        "渠道目录里的备份包（-prev-）被拒绝");
                equal(404, raw(base, "/api/app/apk", "path=" + enc("pixiko-withhost-1.6.0-debug.apk"), null)
                                .statusCode(),
                        "名字不匹配的包（pixiko-withhost-…）被拒绝");
                equal(404, raw(base, "/api/app/apk", "path=" + enc("pixiko-1.6.0-DEBUG.apk"), null).statusCode(),
                        "后缀大小写不同也不认（模式严格）");
                equal(404, raw(base, "/api/app/apk", "path=" + enc("nope.apk"), null).statusCode(),
                        "随便一个文件名被拒绝");

                // ⑨ 免令牌白名单：这两条不要令牌，别的 /api/** 照旧 401
                JsonObject anonymousUpdate = json(base, "POST", "/api/app/update", "{}", Map.of(), 200);
                check(anonymousUpdate.has("version"), "不带令牌 POST /api/app/update 也能拿到 JSON");
                Map<String, String> wrongToken = Map.of("Authorization", "Bearer wrong-token");
                equal(str(release, "version"),
                        str(json(base, "POST", "/api/app/update", "{}", wrongToken, 200), "version"),
                        "带一个错令牌也不影响（这两条本来就免鉴权）");
                HttpResponse<byte[]> anonymousApk = raw(base, "/api/app/apk", null, null);
                equal(200, anonymousApk.statusCode(), "不带令牌 GET /api/app/apk 也能下到字节");
                check(Arrays.equals(releaseBytes, anonymousApk.body()), "不带令牌下到的字节一模一样");
                equal(401, raw(base, "GET", "/api/status", null, null, Map.of()).statusCode(),
                        "别的接口（/api/status）不带令牌仍然 401");
                equal(401, raw(base, "POST", "/api/settings", null, null, Map.of()).statusCode(),
                        "写接口（/api/settings）不带令牌仍然 401");
                equal(401, raw(base, "GET", "/api/definitely-not-a-route-xyz", null, null,
                        Map.<String, String>of()).statusCode(),
                        "不存在的路由不带令牌仍然是 401（过滤器在路由之前，行为没变）");
                equal(401, raw(base, "GET", "/api/app/updatex", null, null, Map.<String, String>of()).statusCode(),
                        "只差一个字母的邻居路由也要令牌（白名单是精确匹配）");
                equal(200, raw(base, "GET", "/api/status", null, null,
                        Map.of("Authorization", "Bearer " + TOKEN)).statusCode(),
                        "带正确令牌别的接口照旧 200");
                // 白名单收得很窄（路径 + 方法都要对上）：update 只放行 POST、apk 只放行 GET/HEAD，
                // 别的动词根本走不到控制器（401，而不是 405）——不存在「不带令牌就能戳到的新入口」。
                equal(401, raw(base, "GET", "/api/app/update", null, null, Map.of()).statusCode(),
                        "GET /api/app/update 不带令牌回 401（update 免鉴权只给 POST）");
                equal(401, raw(base, "POST", "/api/app/apk", null, null, Map.of()).statusCode(),
                        "POST /api/app/apk 不带令牌回 401（apk 免鉴权只给 GET/HEAD，安卓端探测时误用的正是 POST）");
                equal(405, raw(base, "GET", "/api/app/update", null, null,
                        Map.of("Authorization", "Bearer " + TOKEN)).statusCode(),
                        "带令牌用错方法（GET update）回 405，而不是 404/400");
                equal(405, raw(base, "POST", "/api/app/apk", null, null,
                        Map.of("Authorization", "Bearer " + TOKEN)).statusCode(),
                        "带令牌用错方法（POST apk）回 405");
                equal(200, raw(base, "GET", "/api/app/apk", null, null,
                        Map.of("Authorization", "Bearer " + TOKEN)).statusCode(),
                        "apk 带正确令牌当然也通（免鉴权不等于拒绝令牌）");

                // ⑩ 没有可用包：200 + 空 apkUrl + 说明（绝不 500），apk 回 404
                deleteTree(root.resolve("android/dist"));
                deleteTree(root.resolve("work/apk-lanip/release"));
                JsonObject missing = json(base, "POST", "/api/app/update", "{}", Map.of(), 200);
                check(true, "没有安装包时 update 仍然 200（不是 500）");
                equal("", str(missing, "apkUrl"), "没有安装包时 apkUrl 是空串");
                equal("", str(missing, "sha256"), "没有安装包时 sha256 是空串");
                equal(0L, missing.get("sizeBytes").getAsLong(), "没有安装包时 sizeBytes=0");
                equal("release", str(missing, "channel"), "没有安装包时 channel 该是什么还是什么");
                check(str(missing, "notes").contains("目前没有可下载的安装包"),
                        "没有安装包时 notes 说明原因：" + str(missing, "notes"));
                check(str(missing, "notes").contains("release"), "说明里带上当前渠道");
                equal(404, raw(base, "GET", "/api/app/apk", null, null, Map.<String, String>of()).statusCode(),
                        "没有安装包时 apk 回 404");
                check(json(base, "POST", "/api/app/update", "{}", Map.of(), 200).has("version"),
                        "没有安装包时依然给出回退版本号字段");

                // ⑪ 远端 GitHub 数据的接入（本地桩，证明字段真的从远端来，且失败不 500）
                stubCalls.set(0);
                var remoteFixture = newFixture(root, "dev", stubCalls, false);
                JsonObject withRemote = remoteFixture.info();
                equal(1, stubCalls.get(), "远端桩被调用一次（TTL 内不再查）");
                remoteFixture.info();
                equal(1, stubCalls.get(), "第二次请求不再查远端（缓存 5 分钟）");
                check(str(withRemote, "releaseUrl").endsWith("/releases/tag/v9.9.9"),
                        "releaseUrl 用远端 tag：" + str(withRemote, "releaseUrl"));
                check(str(withRemote, "notes").contains("远端正文"),
                        "notes 带上远端 release 正文：" + str(withRemote, "notes"));
                check(!str(withRemote, "notes").contains("远端已有更新的版本"),
                        "本机没有包时不谈『远端更新』（没有可比的本机版本）");
                equal("dev", str(withRemote, "channel"), "注入的配置里 channel=dev");
                equal("", str(withRemote, "apkUrl"), "本机没有包时 apkUrl 仍为空串（远端数据不伪造下载地址）");
                check(withRemote.has("version") && withRemote.has("versionCode"), "没有本机包也有完整契约字段");
                var brokenFixture = newFixture(root, "dev", stubCalls, true);
                JsonObject broken = brokenFixture.info();
                check(true, "远端查询抛异常时接口照旧 200（下面是它的字段）");
                equal("", str(broken, "sha256"), "远端炸了不影响本机字段（这里本机确实没有包）");
                check(str(broken, "notes").contains("目前没有可下载的安装包"), "远端炸了 notes 仍给出本机说明");

                // ⑫ sha256 缓存：同样的文件状态连问三次只算一次
                Files.createDirectories(root.resolve("android/dist"));
                byte[] again = createApk(root, "android/dist", "pixiko-" + DEV_VERSION + "-debug.apk", 0x5A, 4096);
                var cacheFixture = newFixture(root, "dev", stubCalls, false);
                cacheFixture.info();
                int firstCount = cacheFixture.hashComputations();
                cacheFixture.info();
                cacheFixture.info();
                equal(firstCount, cacheFixture.hashComputations(), "同样的文件状态连问三次只算一次 sha256");
                check(firstCount >= 1, "第一次确实算过 sha256（计数 " + firstCount + "）");
                JsonObject cached = cacheFixture.info();
                equal(sha256(again), str(cached, "sha256"), "缓存里的哈希依然正确");
                check(str(cached, "notes").contains("未烧入地址"),
                        "夹具的假 APK 同样走「未烧入地址」的兜底说明：" + str(cached, "notes"));
                check(str(cached, "notes").contains(":41234"),
                        "夹具配置里的广播端口进了渠道说明：" + str(cached, "notes"));
                check(str(cached, "notes").contains("远端已有更新的版本 v9.9.9"),
                        "本机有包时远端更新会加一句提示：" + str(cached, "notes"));
                equal("http://127.0.0.1:8787/api/app/apk", str(cached, "apkUrl"),
                        "Host 头缺失时 apkUrl 回退到 servlet 的 serverName:serverPort（不是写死的 IP，而是请求侧的）");
                equal("dev", str(cached, "channel"), "夹具的渠道是 dev");

                // ⑬ 纯函数：从字节里抠烧入地址 + notes 截断（真实发行包里的 16 KB 正文就靠它）
                equal("172.30.204.50:8787",
                        cn.szu.bot.web.AppUpdateFixture.firstHost("xx172.30.204.50:8787".getBytes(StandardCharsets.UTF_8)),
                        "能在字节里抠出 地址:端口（即使前面粘着别的字符）");
                equal("", cn.szu.bot.web.AppUpdateFixture
                                .firstHost("172.30.204.50".getBytes(StandardCharsets.UTF_8)),
                        "只有 IP 没有端口时不算（免得把版本号之类误认成地址）");
                equal("", cn.szu.bot.web.AppUpdateFixture.firstHost(new byte[]{0, 1, 2, 3}),
                        "没有可读字符串时回空串");
                equal("1.2.3.4:80", cn.szu.bot.web.AppUpdateFixture
                        .firstHost("a\u0000a\u00001.2.3.4:80\u0000b".getBytes(StandardCharsets.UTF_8)),
                        "NUL 分隔的 dex 字符串也能抠出来");
                String longNotes = "x".repeat(9000);
                check(cn.szu.bot.web.AppUpdateFixture.truncate(longNotes).length() < 9000,
                        "超长更新说明会被截断");
                check(cn.szu.bot.web.AppUpdateFixture.truncate(longNotes).contains("已截断"), "截断处有说明");
                equal("短说明", cn.szu.bot.web.AppUpdateFixture.truncate("短说明"), "短说明原样保留");

                // ⑭ versionCode 兜底口径：定宽字段（major/minor/patch 各占一位段），全程严格单调
                check(true, "—— ⑭ versionCode 兜底换算（定宽字段，与 gradle 同序） ——");
                equal(1006000, cn.szu.bot.web.AppUpdateFixture.versionCode("1.6.0"), "1.6.0 → 1006000");
                equal(1005030, cn.szu.bot.web.AppUpdateFixture.versionCode("1.5.3"), "1.5.3 → 1005030");
                equal(1004000, cn.szu.bot.web.AppUpdateFixture.versionCode("1.4.0"), "1.4.0 → 1004000");
                equal(1005000, cn.szu.bot.web.AppUpdateFixture.versionCode("1.5.0"), "1.5.0 → 1005000");
                equal(1007000, cn.szu.bot.web.AppUpdateFixture.versionCode("1.7.0"), "1.7.0 → 1007000");
                equal(2000000, cn.szu.bot.web.AppUpdateFixture.versionCode("2.0.0"), "2.0.0 → 2000000");
                equal(2000010, cn.szu.bot.web.AppUpdateFixture.versionCode("2.0.1"), "2.0.1 → 2000010");
                equal(0, cn.szu.bot.web.AppUpdateFixture.versionCode(""), "空版本号 → 0（不抛）");
                equal(1000000, cn.szu.bot.web.AppUpdateFixture.versionCode("1"), "只有 major 时也认（1 → 1000000）");
                // 两位 patch 的历史版本（1.0.10–1.0.18）与一位 patch 落在同一字段里，不能回退
                equal(1000090, cn.szu.bot.web.AppUpdateFixture.versionCode("1.0.9"), "1.0.9 → 1000090");
                equal(1000100, cn.szu.bot.web.AppUpdateFixture.versionCode("1.0.10"), "1.0.10 → 1000100");
                equal(1000110, cn.szu.bot.web.AppUpdateFixture.versionCode("1.0.11"), "1.0.11 → 1000110");
                equal(1000180, cn.szu.bot.web.AppUpdateFixture.versionCode("1.0.18"), "1.0.18 → 1000180");
                equal(1001000, cn.szu.bot.web.AppUpdateFixture.versionCode("1.1.0"), "1.1.0 → 1001000");
                check(cn.szu.bot.web.AppUpdateFixture.versionCode("1.0.10")
                                > cn.szu.bot.web.AppUpdateFixture.versionCode("1.0.9"),
                        "1.0.10 > 1.0.9（一位→两位 patch 的交接必须递增）");
                check(cn.szu.bot.web.AppUpdateFixture.versionCode("1.0.18")
                                > cn.szu.bot.web.AppUpdateFixture.versionCode("1.0.9"),
                        "1.0.18 > 1.0.9（两位 patch 不会被算小）");
                check(cn.szu.bot.web.AppUpdateFixture.versionCode("1.1.0")
                                > cn.szu.bot.web.AppUpdateFixture.versionCode("1.0.18"),
                        "1.1.0 > 1.0.18（跨 minor 也递增，1.0.x 历史交界不回退）");
                check(cn.szu.bot.web.AppUpdateFixture.versionCode("1.6.0")
                                > cn.szu.bot.web.AppUpdateFixture.versionCode("1.5.3"),
                        "1.6.0 > 1.5.3");
                check(cn.szu.bot.web.AppUpdateFixture.versionCode("1.5.3")
                                > cn.szu.bot.web.AppUpdateFixture.versionCode("1.5.0"),
                        "1.5.3 > 1.5.0");
                // 与 gradle 同序（不是同值）：一对一对比较比值关系
                for (String[] pair : new String[][]{{"1.4.0", "1.5.0"}, {"1.5.0", "1.5.3"}, {"1.5.3", "1.6.0"},
                        {"1.0.9", "1.0.10"}, {"1.0.18", "1.1.0"}}) {
                    check(cn.szu.bot.web.AppUpdateFixture.versionCode(pair[1])
                                    > cn.szu.bot.web.AppUpdateFixture.versionCode(pair[0]),
                            "与 gradle 同序：" + pair[1] + " > " + pair[0]);
                }
                // 单调性：1.0.0 → 1.9.99 全枚举（1000 个版本号），编号必须严格递增，一个平台期都不许有
                int previous = -1;
                int checked = 0;
                for (int minor = 0; minor <= 9; minor++) {
                    for (int patch = 0; patch <= 99; patch++) {
                        String version = "1." + minor + "." + patch;
                        int code = cn.szu.bot.web.AppUpdateFixture.versionCode(version);
                        if (previous >= 0) {
                            check(code > previous, "versionCode 单调递增：" + version + " → " + code
                                    + " 必须大于上一档 " + previous);
                        }
                        previous = code;
                        checked++;
                    }
                }
                equal(1000, checked, "枚举了 1.0.0–1.9.99 共 1000 个版本号做单调性检查，0 处回退");

                // ⑮ gradle 可读时永远以 gradle 为准；只有读不到才用兜底
                Path gradleRoot = Files.createTempDirectory(work, "gradle-");
                try {
                    Files.createDirectories(gradleRoot.resolve("android/dist"));
                    Path fakeApk = gradleRoot.resolve("android/dist/pixiko-1.6.0-debug.apk");
                    Files.write(fakeApk, new byte[]{1, 2, 3});
                    List<Path> candidates = List.of(fakeApk);
                    // 没有 build.gradle：用兜底
                    equal(1006000, cn.szu.bot.web.AppUpdateFixture.versionCodeViaGradle(gradleRoot, "1.6.0", candidates),
                            "读不到 build.gradle 时用兜底（1.6.0 → 1006000）");
                    // 有 build.gradle 且 versionCode=999：以 gradle 为准（证明不是「公式优先」）
                    Files.createDirectories(gradleRoot.resolve("android/app"));
                    Files.writeString(gradleRoot.resolve("android/app/build.gradle"),
                            "android {\n    defaultConfig {\n        versionCode 999\n        versionName \"1.6.0\"\n"
                                    + "    }\n}\n");
                    equal(999, cn.szu.bot.web.AppUpdateFixture.versionCodeViaGradle(gradleRoot, "1.6.0", candidates),
                            "gradle 可读时以 gradle 为准（999，而不是兜底的 1006000）");
                    // build.gradle 存在但没有 versionCode 行：退回兜底
                    Files.writeString(gradleRoot.resolve("android/app/build.gradle"),
                            "android {\n    defaultConfig {\n        versionName \"1.6.0\"\n    }\n}\n");
                    equal(1006000, cn.szu.bot.web.AppUpdateFixture.versionCodeViaGradle(gradleRoot, "1.6.0", candidates),
                            "build.gradle 里没有 versionCode 行时退回兜底");
                    // 坏文件（乱码/二进制）也不能抛，退回兜底
                    Files.write(gradleRoot.resolve("android/app/build.gradle"), new byte[]{(byte) 0xFF, 0, 1, 2});
                    equal(1005030, cn.szu.bot.web.AppUpdateFixture.versionCodeViaGradle(gradleRoot, "1.5.3", candidates),
                            "build.gradle 读坏了也不抛，退回兜底（1.5.3 → 1005030）");
                    // 真机那份 build.gradle（versionCode 160）优先：与真机 android/app/build.gradle 对齐
                    equal(160, cn.szu.bot.web.AppUpdateFixture.versionCodeViaGradle(
                                    Path.of("F:\\Bot"), "1.6.0",
                                    List.of(Path.of("F:\\Bot\\work\\apk-lanip\\release\\pixiko-1.6.0-debug.apk"))),
                            "真机 build.gradle 的 versionCode 160 被读到（gradle 永远优先）");
                } finally {
                    try (var walk = Files.walk(gradleRoot)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                            try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                        });
                    }
                }
            }
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
        System.out.println("AppUpdateTest: " + assertions + " assertions passed: "
                + "渠道开关（dev/release + 默认 dev）、契约字段与磁盘实测一致、apkUrl 按请求 Host 拼、"
                + "无包时 200 + 空 apkUrl、字节流与 Content-Type/Content-Length/X-Pixiko-Sha256、"
                + "Range（206/416/整段拼接）、安全白名单（穿越/绝对路径/另一渠道/备份包）、"
                + "免令牌白名单与其它接口仍 401、远端 GitHub 桩与失败兜底、sha256 mtime 缓存、"
                + "versionCode 兜底口径（定宽字段：1.6.0→1006000、1.5.3→1005030、1.4.0→1004000，"
                + "1.0.0–1.9.99 共 1000 个版本号全程严格递增；gradle 可读时永远以 gradle 为准）。");
    }

    // ------------------------------------------------------------------ 夹具

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    /** 造一个带确定内容的假 APK；返回它的字节。 */
    private static byte[] createApk(Path root, String directory, String name, int seed, int size) throws Exception {
        Path target = root.resolve(directory).resolve(name);
        Files.createDirectories(target.getParent());
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) bytes[index] = (byte) ((seed + index * 31) & 0xFF);
        Files.write(target, bytes);
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder text = new StringBuilder();
        for (byte value : digest.digest(bytes)) text.append(String.format(Locale.ROOT, "%02x", value));
        return text.toString();
    }

    private static void setChannel(Path root, Settings settings, String channel) throws Exception {
        JsonObject config = Json.parse(Files.readString(root.resolve("config.json")));
        JsonObject webui = config.getAsJsonObject("webui");
        JsonObject section = new JsonObject();
        section.addProperty("channel", channel);
        // 远端查询仍然关着：换渠道只应该换本机那份包，不该被网络影响。
        section.addProperty("remote_check", false);
        section.addProperty("github_api", "http://127.0.0.1:1/releases/latest");
        webui.add("app_update", section);
        Json.atomicWrite(root.resolve("config.json"), config);
        settings.reload();
    }

    /**
     * 包内装配的 {@code AppUpdate} 夹具（远端走本地桩，**不联网**）；{@code fail=true} 时桩抛异常，
     * 用来断言「远端炸了也不 500」。
     */
    private static cn.szu.bot.web.AppUpdateFixture newFixture(Path root, String channel, AtomicInteger calls,
                                                              boolean fail) {
        return cn.szu.bot.web.AppUpdateFixture.of(root, channel, calls, fail);
    }

    private static void deleteTree(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) return;
        try (var walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }

    // ------------------------------------------------------------------ HTTP 小工具

    private static JsonObject json(String base, String method, String path, String body,
                                   Map<String, String> headers, int expected) throws Exception {
        HttpResponse<String> response = send(base, method, path, body, headers);
        if (response.statusCode() != expected)
            throw new AssertionError(method + " " + path + " 期望 " + expected + "，实际 " + response.statusCode()
                    + "：" + response.body());
        return Json.parse(response.body());
    }

    /** GET + 可选 {@code path=} 查询串 + 可选 {@code Range} 头；响应体按字节收。 */
    private static HttpResponse<byte[]> raw(String base, String path, String query, String range) throws Exception {
        return raw(base, "GET", path, query, range, Map.of());
    }

    private static HttpResponse<byte[]> raw(String base, String method, String path, String query, String range,
                                            Map<String, String> headers) throws Exception {
        String url = base + path + (query == null || query.isEmpty() ? "" : "?" + query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        for (var entry : headers.entrySet()) builder.header(entry.getKey(), entry.getValue());
        if (range != null) builder.header("Range", range);
        if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8));
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

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

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    /** URL 查询串里编码一个值（path 参数里会有 / 与 ..）。 */
    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
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

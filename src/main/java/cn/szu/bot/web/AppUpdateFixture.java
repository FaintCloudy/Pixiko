package cn.szu.bot.web;

import cn.szu.bot.Json;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * {@link AppUpdate} 的测试夹具：{@code cn.szu.bot} 里的测试用不了包内可见的构造器与方法，
 * 由这个类在包内组装实例并转发几个只读入口（{@code info}/{@code hashComputations}）。
 *
 * <p>只被测试引用，实现就是几行转发，没有任何副作用、不写文件（{@link #main} 那次真实探针也只读）、
 * 不联网（远端按参数走桩 / 关掉）。
 */
public final class AppUpdateFixture {

    private final AppUpdate instance;

    private AppUpdateFixture(AppUpdate instance) { this.instance = instance; }

    /**
     * @param root    机器人根目录（配置里 {@code root} 的来源）
     * @param channel 渠道（{@code dev}/{@code release}）
     * @param calls   远端桩被调用的次数
     * @param fail    true 表示桩抛异常（用来断言「远端炸了也 200」）
     */
    public static AppUpdateFixture of(Path root, String channel, AtomicInteger calls, boolean fail) {
        Function<JsonObject, JsonObject> stub = section -> {
            calls.incrementAndGet();
            if (fail) throw new IllegalStateException("桩：远端查询失败");
            JsonObject remote = new JsonObject();
            remote.addProperty("tag", "v9.9.9");
            remote.addProperty("publishedAt", "2026-10-04T00:00:00Z");
            remote.addProperty("notes", "远端正文：9.9.9 的更新说明。");
            return remote;
        };
        return new AppUpdateFixture(AppUpdate.forTest(root, channel, stub));
    }

    /** 走一遍 {@code POST /api/app/update}：Host 头缺失（apkUrl 会回退到 serverName:serverPort）。 */
    public JsonObject info() { return info(null); }

    /** 走一遍 {@code POST /api/app/update}，Host 头用给的值（用来断言 apkUrl 的拼接）。 */
    public JsonObject info(String hostHeader) { return instance.info(fakeRequest(hostHeader)); }

    /** 已经真正算过 sha256 的次数。 */
    public int hashComputations() { return instance.hashComputations(); }

    /** {@link AppUpdate#firstHost(byte[])} 的转发（测试要断言「从 APK 字节里抠地址」这条纯函数）。 */
    public static String firstHost(byte[] bytes) { return AppUpdate.firstHost(bytes); }

    /** {@link AppUpdate#truncate(String)} 的转发（超长更新说明的截断）。 */
    public static String truncate(String text) { return AppUpdate.truncate(text); }

    /** {@link AppUpdate#versionCode(String)} 的转发（兜底换算口径）。 */
    public static int versionCode(String version) { return AppUpdate.versionCode(version); }

    /**
     * {@link AppUpdate#gradleVersionCode(String, List)} 的转发：在给定根目录下找
     * {@code android/app/build.gradle}，用来断言「gradle 可读时永远以它为准、兜底只在读不到时才用」。
     */
    public static int versionCodeViaGradle(Path root, String version, List<Path> files) {
        JsonObject config = baseConfig(root, "dev");
        AppUpdate instance = new AppUpdate(config, section -> files, null,
                AppUpdate::defaultClient, 300_000L, root, null);
        return instance.gradleVersionCode(version, files);
    }

    /** 夹具用的最小配置（不给远端查询）。 */
    private static JsonObject baseConfig(Path root, String channel) {
        JsonObject config = new JsonObject();
        config.addProperty("channel", channel);
        config.addProperty("remote_check", false);
        config.addProperty("repo", "FaintCloudy/Pixiko");
        config.addProperty("fallback_version", "1.6.0");
        config.addProperty(AppUpdate.ROOT_KEY, root == null ? "" : root.toString());
        JsonObject webui = new JsonObject();
        webui.addProperty("host", "127.0.0.1");
        webui.addProperty("port", 8787);
        config.add(AppUpdate.WEBUI_KEY, webui);
        return config;
    }

    /**
     * 真实发布包探针：用磁盘上那两份真实 APK（{@code work/apk-lanip/release} 与 {@code android/dist}）
     * 走一遍两个接口的**取值路径**，把「实测请求/响应原文（含头）」打出来。
     * <b>只读</b>：不写任何文件、不改 {@code F:\Bot\config.json}、不碰线上 {@code data/}。
     *
     * <p>用法：{@code java -cp ... cn.szu.bot.web.AppUpdateFixture}
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "github".equals(args[0])) { githubProbe(); return; }
        Path root = Path.of("F:\\Bot");
        for (String channel : new String[]{"release", "dev"}) {
            JsonObject config = new JsonObject();
            config.addProperty("channel", channel);
            config.addProperty("remote_check", false);
            config.addProperty("repo", "FaintCloudy/Pixiko");
            config.addProperty("fallback_version", "1.6.0");
            config.addProperty(AppUpdate.ROOT_KEY, root.toString());
            JsonObject webui = new JsonObject();
            webui.addProperty("host", "0.0.0.0");
            webui.addProperty("port", 8787);
            config.add(AppUpdate.WEBUI_KEY, webui);

            AppUpdate probe = new AppUpdate(config, AppUpdate::defaultCandidates, null,
                    AppUpdate::defaultClient, 300_000L, root, null);
            System.out.println("PROBE --- channel=" + channel + " candidates="
                    + AppUpdate.defaultCandidates(config));
            JsonObject body = probe.info(fakeRequest("192.168.1.50:8787"));
            System.out.println("PROBE-REQUEST  POST /api/app/update   Host: 192.168.1.50:8787   body: {}");
            System.out.println("PROBE-RESPONSE " + oneLine(body));
            String version = body.has("version") ? body.get("version").getAsString() : "";
            Path file = root.resolve("release".equals(channel) ? AppUpdate.RELEASE_DIR : AppUpdate.DEV_DIR)
                    .resolve("pixiko-" + version + "-debug.apk");
            if (Files.isRegularFile(file)) {
                long size = Files.size(file);
                System.out.println("PROBE-FILE " + file + " size=" + size
                        + " mtime=" + Files.getLastModifiedTime(file));
                System.out.println("PROBE-RANGE bytes=0-99          -> " + AppUpdate.Range.parse(size, "bytes=0-99"));
                System.out.println("PROBE-RANGE bytes=1000-        -> " + AppUpdate.Range.parse(size, "bytes=1000-"));
                System.out.println("PROBE-RANGE bytes=-64          -> " + AppUpdate.Range.parse(size, "bytes=-64"));
                System.out.println("PROBE-RANGE bytes=999999999-   -> " + AppUpdate.Range.parse(size, "bytes=999999999-"));
                System.out.println("PROBE-RANGE bytes=abc-def      -> " + AppUpdate.Range.parse(size, "bytes=abc-def"));
                System.out.println("PROBE-RANGE items=0-99         -> " + AppUpdate.Range.parse(size, "items=0-99"));
            }
        }
        System.out.println("PROBE-WHITELIST versionOf(pixiko-1.6.0-debug.apk)=" + AppUpdate.versionOf("pixiko-1.6.0-debug.apk")
                + " versionOf(pixiko-1.6.0-debug-prev-20261005-030404.apk)="
                + AppUpdate.versionOf("pixiko-1.6.0-debug-prev-20261005-030404.apk")
                + " versionCode(1.6.0)=" + AppUpdate.versionCode("1.6.0")
                + " versionCode(1.7.0)=" + AppUpdate.versionCode("1.7.0")
                + " versionCode(2.0.3)=" + AppUpdate.versionCode("2.0.3"));
        System.out.println("PROBE-CHANNEL default=" + AppUpdate.channelOf(new JsonObject())
                + " RELEASE=" + AppUpdate.channelOf(Json.parse("{\"channel\":\"RELEASE\"}"))
                + " canary=" + AppUpdate.channelOf(Json.parse("{\"channel\":\"canary\"}")));
        System.out.println("PROBE-PROXY local=" + AppUpdate.proxy(Json.parse("{\"proxy_url\":\"http://127.0.0.1:7890\"}"))
                + " bogus=" + AppUpdate.proxy(Json.parse("{\"proxy_url\":\"http://evil.example:7890\"}")));
        // 白名单解析：真实 release 目录里那份 + 备份包 + 目录穿越 + 另一渠道
        List<Path> files = AppUpdate.defaultCandidates(Json.parse(
                "{\"channel\":\"release\",\"root\":\"F:\\\\Bot\"}"));
        AppUpdate whitelist = new AppUpdate(Json.parse("{\"channel\":\"release\",\"root\":\"F:\\\\Bot\"}"),
                section -> files, null, AppUpdate::defaultClient, 300_000L, Path.of("F:\\Bot"), null);
        for (String requested : new String[]{null, "pixiko-1.6.0-debug.apk",
                "pixiko-1.6.0-debug-prev-20261005-030404.apk", "../../config.json",
                "F:/Bot/work/apk-lanip/release/pixiko-1.6.0-debug.apk",
                "work/apk-lanip/release/pixiko-1.6.0-debug.apk", "pixiko-1.4.0-debug.apk"}) {
            System.out.println("PROBE-WHITELIST resolveApk(" + requested + ") = "
                    + whitelist.resolveApk(requested, files, "release"));
        }
    }

    private static String oneLine(JsonObject body) {
        return Json.GSON.toJson(body).replace("\n", " ").replaceAll("\\s+", " ");
    }

    /**
     * 可选探针：**真的**查一次 GitHub Releases（走 {@link AppUpdate#defaultClient} 的代理规则；
     * 本机有 {@code civitai.proxy_url} 就继承）。用来回答「远端查询到底接没接通」。
     * 用法：{@code java -cp ... cn.szu.bot.web.AppUpdateFixture github}；
     * 失败只打一行，绝不抛出（和线上一样：查不到就用本机数据兜底）。
     */
    private static void githubProbe() {
        JsonObject config = new JsonObject();
        config.addProperty("channel", "dev");
        config.addProperty("remote_check", true);
        config.addProperty("repo", "FaintCloudy/Pixiko");
        config.addProperty("fallback_version", "1.6.0");
        config.addProperty(AppUpdate.ROOT_KEY, "F:\\Bot");
        JsonObject webui = new JsonObject();
        webui.addProperty("host", "0.0.0.0");
        webui.addProperty("port", 8787);
        config.add(AppUpdate.WEBUI_KEY, webui);
        AppUpdate probe = new AppUpdate(config, AppUpdate::defaultCandidates, null,
                AppUpdate::defaultClient, 300_000L, Path.of("F:\\Bot"), null);
        JsonObject body = probe.info(fakeRequest("192.168.1.50:8787"));
        System.out.println("GITHUB-PROBE remoteVersion=" + body.get("remoteVersion").getAsString()
                + " releaseUrl=" + body.get("releaseUrl").getAsString()
                + " notes=" + oneLine(body));
    }

    /**
     * {@link HttpServletRequest} 的最小替身：真身由动态代理实现（Servlet 6 的接口方法太多，
     * 手写全实现既啰嗦又容易漏），只放行 {@link AppUpdate} 真正用到的那几个方法，别的调用直接抛错——
     * 免得测试悄悄依赖没实现的行为。
     */
    private static HttpServletRequest fakeRequest(String hostHeader) {
        return (HttpServletRequest) Proxy.newProxyInstance(AppUpdateFixture.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getHeader": return "Host".equalsIgnoreCase((String) args[0]) ? hostHeader : null;
                        case "getScheme": return "http";
                        case "getServerName": return "127.0.0.1";
                        case "getServerPort": return 8787;
                        case "toString": return "FakeRequest(host=" + hostHeader + ")";
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                    "FakeRequest 只实现了 AppUpdate 用到的几个方法，被调到的是 " + method.getName());
                    }
                });
    }
}

package cn.szu.bot.web;

import cn.szu.bot.Bot;
import cn.szu.bot.Json;
import cn.szu.bot.Log;
import cn.szu.bot.Settings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安卓端的自动更新服务端那一半：{@code POST /api/app/update} 问「有没有新版本」、
 * {@code GET /api/app/apk} 把选中的那个 APK 的字节流发下去。
 *
 * <p><b>渠道开关</b>（{@code app_update.channel} / {@code webui.app_update.channel}，**默认 {@code dev}**）：
 * <ul>
 *   <li>{@code dev}（默认）：下发 {@code <root>/android/dist/pixiko-<版本>-debug.apk} —— 构建脚本
 *       自动探测本机局域网 IP 并烧进 BuildConfig 的「本机测试包」，**仅供内网测试**，
 *       响应里 {@code channel="dev"}、{@code notes} 会写明它烧进去的地址（这个地址是运行时从 APK 的
 *       {@code classes*.dex} 里搜出来的，见 {@link #bakedAddress(Path)}；搜不到就如实说「未烧入地址」）；</li>
 *   <li>{@code release}：下发 {@code <root>/work/apk-lanip/release/pixiko-<版本>-debug.apk} —— 用
 *       {@code -NoDefaultHost} 构建、**绝不烘焙本机局域网地址**的发行包（见 RELEASE.md「发行清单（每版必做）」）。
 *       <b>真正对外发行时必须走这个渠道</b>；改成 release 只需要把配置写成
 *       {@code "app_update": {"channel": "release"}}，不用改代码。</li>
 * </ul>
 * 两个目录都按「固定目录 + 文件名模式」白名单解析，**任何路径参数都不参与拼路径**，
 * 目录穿越、绝对路径、指向别的目录（例如 {@code work/apk-lanip/lanip/}）一律 404。
 *
 * <p><b>只读</b>：这两个接口不写任何文件、不改 config.json，也不碰机器人业务对象。手机上没有网页令牌，
 * 所以 {@link WebAuthFilter} 在**路由匹配之前**就把这两条路径放行了（只允许局域网访问是既有前提）。
 *
 * <p><b>数据来源优先级</b>：
 * <ol>
 *   <li><b>本机 APK</b>（当前渠道那个目录）—— {@code version/versionCode} 从 {@code android/app/build.gradle}
 *       与文件名读，{@code sizeBytes}/{@code sha256} 一律是<b>实测值</b>；哈希按「文件 mtime + 大小」缓存，
 *       渠道切换后快照键里带着文件路径，不会把旧渠道的哈希串给新渠道；</li>
 *   <li>配置允许时**只读**查一次 GitHub Releases（走 {@code civitai.proxy_url} 那样只认本机代理的配置），
 *       只用来填 {@code releaseUrl}/{@code publishedAt}/{@code notes}；查不到、超时、代理不通都只是少几个字段，
 *       <b>绝不</b>让接口 500。</li>
 * </ol>
 */
public final class AppUpdate {

    /** 渠道取值：本机测试包（默认）。 */
    static final String CHANNEL_DEV = "dev";
    /** 渠道取值：发行包（{@code -NoDefaultHost}，对外发行只能用这个）。 */
    static final String CHANNEL_RELEASE = "release";
    /** dev 渠道目录（相对机器人根目录）：构建脚本直接产出的测试包。 */
    static final String DEV_DIR = "android/dist";
    /** release 渠道目录（相对机器人根目录），与 android/build-apk.ps1 的输出位置一致。 */
    static final String RELEASE_DIR = "work/apk-lanip/release";
    /** 两个渠道都认的文件名：{@code pixiko-1.6.0-debug.apk}；{@code -prev-…} 之类的备份一律不认。 */
    static final Pattern RELEASE_NAME = Pattern.compile("^pixiko-(\\d+)\\.(\\d+)\\.(\\d+)-debug\\.apk$");
    /** 从 APK 字节里抠「烧入地址」用的模式：{@code 172.30.204.50:8787}。 */
    static final Pattern HOST = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}");
    /** APK 的 MIME（安卓安装器认它）。 */
    static final String APK_TYPE = "application/vnd.android.package-archive";
    /** 把机器人根目录塞进合并配置对象的键名。 */
    static final String ROOT_KEY = "root";
    /** 配置里 {@code webui} 段的键名（渠道说明里要用它广播地址）。 */
    static final String WEBUI_KEY = "webui";
    /** 根目录的兜底（配置里没带 root 时用）：生产环境就是 {@code settings.root}。 */
    private final Path fallbackRoot;

    /** 远端正文长度上限（超过就截断，见 {@link #truncate}）。 */
    static final int NOTES_LIMIT = 4000;
    private static final String USER_AGENT = "Pixiko-Bot-AppUpdate";
    private static final DateTimeFormatter ISO_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** 一次快照的有效期：文件 mtime/大小不变时，这段时间内不重算 sha256。 */
    private final long cacheMillis;
    /** 远端查询的注入点（测试接本地桩，永不联网）；为 null 时走 GitHub。 */
    private final Function<JsonObject, JsonObject> remoteLoader;
    private final Function<JsonObject, HttpClient> clientFactory;
    /** 本机发布包的路径解析（生产＝默认实现；测试也可以自己给一个）。 */
    private final Function<JsonObject, List<Path>> candidates;
    /** 合并后的配置（{@code webui.app_update} 优先于顶层 {@code app_update}）。 */
    private final JsonObject config;
    /** 每次请求重读配置（把 {@code channel} 从 dev 改成 release 立刻生效，不用重启）。 */
    private final Supplier<JsonObject> reloaded;

    private Snapshot cached;
    private int hashComputations;
    private long remoteCheckedAt = Long.MIN_VALUE;
    private JsonObject remoteCached;
    /** 「从 APK 里抠地址」的单条缓存（静态：跨请求复用，别每个请求都去解压 10 MB dex）。 */
    private static final Object BAKED_LOCK = new Object();
    private static String bakedKey = "";
    private static String bakedValue = "";

    AppUpdate(JsonObject config, Function<JsonObject, List<Path>> candidates) {
        this(config, candidates, null, AppUpdate::defaultClient, 300_000L, null);
    }

    AppUpdate(JsonObject config, Function<JsonObject, List<Path>> candidates,
              Function<JsonObject, JsonObject> remoteLoader) {
        this(config, candidates, remoteLoader, AppUpdate::defaultClient, 300_000L, null);
    }

    AppUpdate(JsonObject config, Function<JsonObject, List<Path>> candidates, Path root) {
        this(config, candidates, null, AppUpdate::defaultClient, 300_000L, root, null);
    }

    /**
     * 生产环境的构造：{@code reloaded} 每次请求重读一份 configure（改渠道不用重启），
     * {@code root} 是机器人根目录（配置里没带 root 时的兜底）。
     */
    AppUpdate(JsonObject config, Function<JsonObject, List<Path>> candidates, Path root, Supplier<JsonObject> reloaded) {
        this(config, candidates, null, AppUpdate::defaultClient, 300_000L, root, reloaded);
    }

    AppUpdate(JsonObject config, Function<JsonObject, List<Path>> candidates,
              Function<JsonObject, JsonObject> remoteLoader,
              Function<JsonObject, HttpClient> clientFactory, long cacheMillis) {
        this(config, candidates, remoteLoader, clientFactory, cacheMillis, null, null);
    }

    AppUpdate(JsonObject config, Function<JsonObject, List<Path>> candidates,
              Function<JsonObject, JsonObject> remoteLoader,
              Function<JsonObject, HttpClient> clientFactory, long cacheMillis, Path root) {
        this(config, candidates, remoteLoader, clientFactory, cacheMillis, root, null);
    }

    AppUpdate(JsonObject config, Function<JsonObject, List<Path>> candidates,
              Function<JsonObject, JsonObject> remoteLoader,
              Function<JsonObject, HttpClient> clientFactory, long cacheMillis, Path root,
              Supplier<JsonObject> reloaded) {
        this.config = config == null ? new JsonObject() : config;
        this.candidates = candidates == null ? AppUpdate::defaultCandidates : candidates;
        this.remoteLoader = remoteLoader;
        this.clientFactory = clientFactory == null ? AppUpdate::defaultClient : clientFactory;
        this.cacheMillis = cacheMillis <= 0 ? 300_000L : cacheMillis;
        this.fallbackRoot = root == null ? null : root.toAbsolutePath().normalize();
        this.reloaded = reloaded;
    }

    // ------------------------------------------------------------------ 控制器入口

    /**
     * {@code POST /api/app/update} 的响应体：契约里那 8 个键**永远都在**，外加渠道字段
     * {@code channel}（{@code "dev"}/{@code "release"}，安卓端用来显示「这是测试包还是发行包」）
     * 与 {@code remoteVersion}（GitHub 最新 tag 的版本号，没有远端数据时空串）。
     *
     * <p>本机没有该渠道的 APK 时也要 200：{@code apkUrl}/{@code sha256} 空串、{@code notes} 说明原因，
     * 手机端拿到空 {@code apkUrl} 就显示「暂时没有可下载的版本」，不会崩。
     */
    JsonObject info(HttpServletRequest request) {
        JsonObject effective = effectiveConfig();
        String channel = channelOf(effective);
        List<Path> files = candidatesFor(effective);
        JsonObject remote = remote();

        JsonObject result = new JsonObject();
        result.addProperty("channel", channel);
        result.addProperty("version", fallbackVersion());
        result.addProperty("versionCode", versionCode(fallbackVersion()));
        result.addProperty("sizeBytes", 0L);
        result.addProperty("sha256", "");
        result.addProperty("apkUrl", "");
        result.addProperty("releaseUrl", "");
        result.addProperty("publishedAt", "");
        result.addProperty("notes", "");

        Path file = currentRelease(files);
        if (file != null) {
            String name = file.getFileName().toString();
            String version = versionOf(name);
            Snapshot current = snapshot(file, version);
            result.addProperty("version", version);
            result.addProperty("versionCode", gradleVersionCode(version, files));
            result.addProperty("sizeBytes", current.size());
            result.addProperty("sha256", current.sha256());
            result.addProperty("apkUrl", base(request) + "/api/app/apk");
            result.addProperty("publishedAt", current.publishedAt());
            result.addProperty("releaseUrl", releaseUrl(remote, version));
            result.addProperty("remoteVersion", remoteVersion(remote));
            result.addProperty("notes", notes(remote, name, channel, effective, file));
        } else {
            String missing = "目前没有可下载的安装包（" + channel + " 渠道：本机 "
                    + directoryOf(effective, channel) + " 下没有 " + RELEASE_NAME.pattern() + "）。";
            String remoteNotes = remoteNotes(remote);
            result.addProperty("releaseUrl", releaseUrl(remote, fallbackVersion()));
            result.addProperty("remoteVersion", remoteVersion(remote));
            result.addProperty("notes", remoteNotes.isBlank() ? missing : missing + "\n" + remoteNotes);
        }
        return result;
    }

    /**
     * {@code GET /api/app/apk}：把**当前渠道**那份 APK 的字节流发下去（整份，或 Range 指定的一段）。
     *
     * <p>响应头固定给 {@code Content-Type: application/vnd.android.package-archive}、
     * {@code Content-Length}、{@code Content-Disposition: attachment; filename="…"}、
     * {@code X-Pixiko-Sha256} 与 {@code Accept-Ranges: bytes}；带 {@code Range} 时回 206 + {@code Content-Range}，
     * 越界回 416（{@code Content-Range: bytes *&#47;总长}）。Content-Length 由 Spring 按字节体长度自动写。
     */
    ResponseEntity<byte[]> apk(HttpServletRequest request, String requestedPath) throws IOException {
        JsonObject effective = effectiveConfig();
        String channel = channelOf(effective);
        Path file = resolveApk(requestedPath, candidatesFor(effective), channel);
        if (file == null) return notFound();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException error) {
            return notFound();
        }
        String name = file.getFileName().toString();
        String sentSha = snapshot(file, versionOf(name)).sha256();
        Range range = Range.parse(size, request.getHeader("Range"));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept-Ranges", "bytes");
        headers.set("X-Pixiko-Sha256", sentSha);
        headers.set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        if (range == null) {
            // 语法正确但越界：按 RFC 7233 回 416 + "bytes */总长"（对象是 JSON，与 WebJson 一致）。
            headers.set("Content-Range", "bytes */" + size);
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .headers(headers)
                    .body(Json.GSON.toJson(WebJson.error("请求的字节范围无效。")).getBytes(StandardCharsets.UTF_8));
        }
        byte[] all = Files.readAllBytes(file);
        byte[] body = range.partial()
                ? java.util.Arrays.copyOfRange(all, (int) range.start(), (int) (range.start() + range.length()))
                : all;
        if (range.partial())
            headers.set("Content-Range", "bytes " + range.start() + "-" + (range.start() + range.length() - 1) + "/" + size);
        return WebJson.bytes(range.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK, body, APK_TYPE,
                "no-store", headers);
    }

    /**
     * APK 白名单判定。{@code requestedPath} 为空时给「当前渠道的当前 APK」；否则：
     * <ol>
     *   <li>路径本身不能含 {@code ..}、不能是绝对路径（Windows 盘符 / 前导斜杠）、不能有 NUL；</li>
     *   <li>文件名必须严格匹配 {@link #RELEASE_NAME}（{@code pixiko-<数字.数字.数字>-debug.apk}）；</li>
     *   <li>如果带了目录部分，目录只能是当前渠道目录的名字；</li>
     *   <li>最终路径 {@code normalize()} 之后必须仍在当前渠道目录里，且是<b>当前渠道</b>的位置。</li>
     * </ol>
     * 任何一步不满足都回 {@code null}，由调用方转 404；**请求里的路径从不被拼进文件系统**。
     */
    Path resolveApk(String requestedPath, List<Path> files, String channel) {
        if (requestedPath == null || requestedPath.isBlank()) return currentRelease(files);
        String raw = requestedPath.strip();
        if (raw.indexOf('\0') >= 0 || raw.contains("..") || raw.startsWith("\\") || isAbsolute(raw)) return null;
        String normalized = raw.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("//")) return null;
        int slash = normalized.lastIndexOf('/');
        String name = slash < 0 ? normalized : normalized.substring(slash + 1);
        if (!RELEASE_NAME.matcher(name).matches()) return null;
        Path directory = directoryPath(channel);
        if (directory == null) return null;
        if (slash >= 0) {
            String requested = normalized.substring(0, slash);
            String wanted = directory.getFileName() == null ? "" : directory.getFileName().toString();
            if (!requested.equals(wanted) && !requested.endsWith("/" + wanted)) return null;
        }
        Path direct = directory.resolve(name).normalize();
        // 目录部分已经被限定成渠道目录名，这里再核一次真实路径必须落在渠道目录里（双保险）。
        if (!direct.startsWith(directory)) return null;
        if (!Files.isRegularFile(direct)) return null;
        // 渠道目录里的备份包（-prev-…）文件名本来就不匹配；这里再确认一次它确实是白名单里的那个。
        return RELEASE_NAME.matcher(direct.getFileName().toString()).matches() ? direct : null;
    }

    /** 已经真正算过 sha256 的次数（缓存断言用）。 */
    int hashComputations() { return hashComputations; }

    // ------------------------------------------------------------------ 本机发布包

    /** 当前发布包：文件名合法里版本最高的一份，版本相同再比 mtime。 */
    Path currentRelease(List<Path> files) {
        Path best = null;
        int[] bestVersion = null;
        long bestStamp = Long.MIN_VALUE;
        for (Path file : files) {
            if (file == null || file.getFileName() == null) continue;
            Matcher matcher = RELEASE_NAME.matcher(file.getFileName().toString());
            if (!matcher.matches() || !Files.isRegularFile(file)) continue;
            int[] version = {number(matcher.group(1)), number(matcher.group(2)), number(matcher.group(3))};
            long stamp;
            try { stamp = Files.getLastModifiedTime(file).toMillis(); } catch (IOException error) { stamp = Long.MIN_VALUE; }
            if (best == null || compareVersion(version, bestVersion) > 0
                    || (compareVersion(version, bestVersion) == 0 && stamp > bestStamp)) {
                best = file;
                bestVersion = version;
                bestStamp = stamp;
            }
        }
        return best;
    }

    private static int compareVersion(int[] left, int[] right) {
        if (right == null) return 1;
        for (int index = 0; index < 3; index++) {
            int diff = left[index] - right[index];
            if (diff != 0) return diff;
        }
        return 0;
    }

    /** 一次快照：文件 mtime + 大小没变且还在有效期内就直接复用，不重算 sha256。 */
    private synchronized Snapshot snapshot(Path file, String version) {
        long stamp;
        long size;
        try {
            stamp = Files.getLastModifiedTime(file).toMillis();
            size = Files.size(file);
        } catch (IOException error) {
            return new Snapshot(version + "|missing", 0L, "", "");
        }
        String key = file.toAbsolutePath().normalize() + "|" + stamp + "|" + size;
        Snapshot current = cached;
        if (current != null && current.key().equals(key)
                && System.currentTimeMillis() - current.checkedAt() < cacheMillis) return current;
        String sha;
        try {
            sha = sha256(file);
        } catch (Exception error) {
            Log.warn("发布包 sha256 计算失败（" + file.getFileName() + "）：" + Bot.error(error));
            sha = "";
        }
        hashComputations++;
        Snapshot fresh = new Snapshot(key, size, sha, ISO_SECONDS.format(Instant.ofEpochMilli(stamp)));
        cached = fresh;
        return fresh;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder text = new StringBuilder(64);
        for (byte value : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", value));
        return text.toString();
    }

    // ------------------------------------------------------------------ 渠道与目录

    /**
     * 当前生效的配置：请求时重读一遍（{@code settings.snapshot()} 的新鲜副本），
     * 这样把 {@code app_update.channel} 从 {@code dev} 改成 {@code release} 立刻生效，不用重启机器人；
     * 重读失败（极端情况）就退回构造时那份。{@code root} 始终以构造时拿到的为准（配置里那份可能是测试造的）。
     */
    JsonObject effectiveConfig() {
        JsonObject fresh = reloaded == null ? null : reloaded.get();
        JsonObject base = fresh != null && !fresh.isEmpty() ? fresh : config;
        String root = Json.str(config, ROOT_KEY, "").strip();
        if (root.isEmpty() && fallbackRoot != null) base.addProperty(ROOT_KEY, fallbackRoot.toString());
        return base;
    }

    /** 当前渠道：{@code app_update.channel}，认不出来（含缺失、大小写、乱写）一律当 {@code dev}。 */
    static String channelOf(JsonObject config) {
        String value = Json.str(config, "channel", "").strip().toLowerCase(Locale.ROOT);
        return CHANNEL_RELEASE.equals(value) ? CHANNEL_RELEASE : CHANNEL_DEV;
    }

    /** 渠道对应的绝对目录；没有根目录时回 {@code null}（调用方转「没有包」）。 */
    Path directoryPath(String channel) {
        Path root = rootPath(effectiveConfig());
        if (root == null) return null;
        return root.resolve(CHANNEL_RELEASE.equals(channel) ? RELEASE_DIR : DEV_DIR).toAbsolutePath().normalize();
    }

    /** 渠道目录的**相对**路径（写进 notes 给人看）。 */
    static String directoryOf(JsonObject config, String channel) {
        return CHANNEL_RELEASE.equals(channel) ? RELEASE_DIR : DEV_DIR;
    }

    private Path rootPath(JsonObject config) {
        String root = Json.str(config, ROOT_KEY, "").strip();
        if (!root.isEmpty()) return Path.of(root).toAbsolutePath().normalize();
        return fallbackRoot;
    }

    /** 当前渠道目录里的候选 APK（只列一层，文件名必须匹配）。 */
    private List<Path> candidatesFor(JsonObject config) {
        Path directory = directoryPath(channelOf(config));
        List<Path> result = new ArrayList<>();
        if (directory == null) return result;
        if (!Files.isDirectory(directory)) return result;
        try (var entries = Files.list(directory)) {
            for (Path file : entries.toList()) {
                if (file.getFileName() == null) continue;
                if (!RELEASE_NAME.matcher(file.getFileName().toString()).matches()) continue;
                if (Files.isRegularFile(file)) result.add(file.toAbsolutePath().normalize());
            }
        } catch (Exception error) {
            Log.warn("安装包目录读取失败（" + directory + "）：" + Bot.error(error));
        }
        return result;
    }

    /** 默认的候选解析（生产环境）：按渠道挑目录。 */
    static List<Path> defaultCandidates(JsonObject config) {
        Path root = null;
        String value = Json.str(config, ROOT_KEY, "").strip();
        if (!value.isEmpty()) root = Path.of(value).toAbsolutePath().normalize();
        if (root == null) return new ArrayList<>();
        Path directory = root.resolve(channelOf(config) == CHANNEL_RELEASE ? RELEASE_DIR : DEV_DIR)
                .toAbsolutePath().normalize();
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(directory)) return result;
        try (var entries = Files.list(directory)) {
            for (Path file : entries.toList()) {
                if (file.getFileName() == null) continue;
                if (!RELEASE_NAME.matcher(file.getFileName().toString()).matches()) continue;
                if (Files.isRegularFile(file)) result.add(file.toAbsolutePath().normalize());
            }
        } catch (Exception error) {
            Log.warn("安装包目录读取失败（" + directory + "）：" + Bot.error(error));
        }
        return result;
    }

    /**
     * 把 {@link Settings} 里跟更新有关的配置合成一个只读对象：{@code webui.app_update} 优先于顶层
     * {@code app_update}，缺省值全部在这里补齐；{@code proxy_url} 缺省继承 {@code civitai.proxy_url}。
     * 注意<b>不写回</b>任何东西——{@code channel} 默认值只存在于内存里（默认 {@code dev}）。
     */
    static JsonObject configFor(Settings settings) {
        JsonObject snapshot = settings.snapshot();
        JsonObject section = Json.obj(Json.obj(snapshot, "webui"), "app_update");
        if (section.isEmpty()) section = Json.obj(snapshot, "app_update");
        JsonObject merged = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : section.entrySet()) merged.add(entry.getKey(), entry.getValue());
        if (!merged.has("channel")) merged.addProperty("channel", CHANNEL_DEV);
        if (!merged.has("repo")) merged.addProperty("repo", "FaintCloudy/Pixiko");
        if (!merged.has("fallback_version")) merged.addProperty("fallback_version", "1.6.0");
        if (!merged.has("remote_check")) merged.addProperty("remote_check", true);
        if (!merged.has("proxy_url")) {
            String proxy = Json.str(Json.obj(snapshot, "civitai"), "proxy_url", "").strip();
            if (!proxy.isEmpty()) merged.addProperty("proxy_url", proxy);
        }
        // 渠道说明要用 webui.host/port 广播地址，所以把整个 webui 段带进来（只读，不改）。
        merged.add(WEBUI_KEY, Json.obj(snapshot, "webui"));
        merged.addProperty(ROOT_KEY, settings.root.toString());
        return merged;
    }

    /**
     * 测试用：拿一个指定的根目录 / 渠道 / 远端桩造一个实例（{@link AppUpdateFixture} 调用）。
     * 生产代码不用它——生产走 {@link #configFor(Settings)} + 每次请求重读配置那条路。
     */
    static AppUpdate forTest(Path root, String channel, Function<JsonObject, JsonObject> remoteStub) {
        JsonObject config = new JsonObject();
        config.addProperty("channel", channel);
        config.addProperty("remote_check", true);
        config.addProperty("repo", "FaintCloudy/Pixiko");
        config.addProperty("fallback_version", "1.6.0");
        config.addProperty(ROOT_KEY, root == null ? "" : root.toString());
        // 渠道说明要用广播地址：测试里给一个固定值，断言 notes 里确实带了它。
        JsonObject webui = new JsonObject();
        webui.addProperty("host", "127.0.0.1");
        webui.addProperty("port", 41234);
        config.add(WEBUI_KEY, webui);
        return new AppUpdate(config, AppUpdate::defaultCandidates, remoteStub,
                AppUpdate::defaultClient, 300_000L, root, null);
    }

    // ------------------------------------------------------------------ 远端（GitHub Releases，只读）

    /**
     * 远端信息：{@code app_update.remote_check} 显式为 false 时一次都不查；同一个配置最多每 5 分钟查一次；
     * 失败（网络、代理、GitHub 非 200、响应不是预期 JSON）只记一条 warn 并回空对象，绝不影响本机数据。
     */
    private synchronized JsonObject remote() {
        if (!Json.bool(config, "remote_check", true)) return new JsonObject();
        long now = System.currentTimeMillis();
        if (remoteCached != null && now - remoteCheckedAt < Math.min(cacheMillis, 300_000L)) return remoteCached;
        remoteCheckedAt = now;
        JsonObject result = new JsonObject();
        try {
            Function<JsonObject, JsonObject> loader = remoteLoader != null ? remoteLoader : this::githubLatestRelease;
            JsonObject loaded = loader.apply(config);
            if (loaded != null) result = loaded;
        } catch (Exception error) {
            Log.warn("GitHub Releases 查询失败（用本机数据兜底，不影响 200）：" + Bot.error(error));
        }
        remoteCached = result;
        return result;
    }

    /** 白名单外的路径统一回 404（对象是 JSON，和别的控制台接口一致）。 */
    private static ResponseEntity<byte[]> notFound() {
        return WebJson.bytes(HttpStatus.NOT_FOUND,
                Json.GSON.toJson(WebJson.error("没有这个安装包。")).getBytes(StandardCharsets.UTF_8),
                "application/json; charset=utf-8", "no-store");
    }

    /**
     * 查一次 {@code /repos/<repo>/releases/latest}（{@code app_update.github_api} 可指向镜像）。
     * 只解析 tag / published_at / body 三个字段，别的字段一概不碰，也绝不打印响应正文。
     * 受检异常一律包成 {@link RuntimeException}（调用方本来就只记一条 warn，不该让接口失败）。
     */
    private JsonObject githubLatestRelease(JsonObject config) {
        try {
            String url = Json.str(config, "github_api", "").strip();
            if (url.isEmpty()) url = "https://api.github.com/repos/" + repository(config) + "/releases/latest";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", USER_AGENT)
                    .GET().build();
            HttpResponse<String> response = clientFactory.apply(config).send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                Log.warn("GitHub Releases 查询返回 " + response.statusCode() + "，本次用本机数据兜底。");
                return new JsonObject();
            }
            JsonObject body = Json.parse(response.body());
            JsonObject result = new JsonObject();
            result.addProperty("tag", Json.str(body, "tag_name", ""));
            result.addProperty("publishedAt", Json.str(body, "published_at", ""));
            result.addProperty("notes", Json.str(body, "body", ""));
            return result;
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    static HttpClient defaultClient(JsonObject config) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5));
        InetSocketAddress proxy = proxy(config);
        if (proxy != null) builder.proxy(ProxySelector.of(proxy));
        return builder.build();
    }

    /** {@code app_update.proxy_url}（缺省继承 {@code civitai.proxy_url}）：只接受本机 HTTP 代理。 */
    static InetSocketAddress proxy(JsonObject config) {
        String value = Json.str(config, "proxy_url", "").strip();
        if (value.isEmpty()) return null;
        try {
            URI uri = URI.create(value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() < 1 || uri.getPort() > 65535) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!host.equals("127.0.0.1") && !host.equals("localhost") && !host.equals("::1")) return null;
            return new InetSocketAddress(uri.getHost(), uri.getPort());
        } catch (RuntimeException error) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 小工具

    private String releaseUrl(JsonObject remote, String localVersion) {
        String tag = Json.str(remote, "tag", "").strip();
        String repository = repository(config);
        if (tag.isEmpty()) return "https://github.com/" + repository + "/releases/tag/v" + localVersion;
        return "https://github.com/" + repository + "/releases/tag/" + tag;
    }

    /** 远端正文（没有远端数据时是空串）。 */
    private static String remoteNotes(JsonObject remote) {
        JsonElement value = remote == null ? null : remote.get("notes");
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    /**
     * 远端最新 tag 对应的版本号（没有远端数据时空串）。安卓端可以拿它跟 {@code version} 比出
     * 「远端还有更新的版本」——但**下载地址始终是本机那份**（局域网内直接推 APK，不走外网）。
     */
    private static String remoteVersion(JsonObject remote) {
        String tag = Json.str(remote, "tag", "").strip();
        if (tag.isEmpty()) return "";
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    /**
     * {@code notes}：渠道说明（**一定在最前面**，不许静默）＋ GitHub 正文。
     *
     * <p>dev 渠道会写明「本机测试包（含局域网地址 &lt;地址&gt;），仅供内网测试」，release 渠道写明
     * 「发行包（不含局域网地址）」。远端的正文接在后面；远端 tag 比本机新时再加一句提示。
     */
    String notes(JsonObject remote, String localName, String channel, JsonObject config, Path file) {
        String base = channelNote(channel, config, file);
        String text = remoteNotes(remote);
        String tag = Json.str(remote, "tag", "").strip();
        StringBuilder result = new StringBuilder(base);
        if (!text.isBlank()) result.append('\n').append(truncate(text));
        if (!text.isBlank() && !tag.isBlank() && newerRemote(tag, localName))
            result.append("\n（远端已有更新的版本 ").append(tag).append("。）");
        return result.toString();
    }

    /** GitHub 正文可能很长（v1.6.0 的正文 16 KB），截一段就够手机端显示，别把响应撑大。 */
    static String truncate(String text) {
        return text.length() <= NOTES_LIMIT ? text
                : text.substring(0, NOTES_LIMIT) + "\n…（更新说明过长，已截断；完整内容见 releaseUrl）";
    }

    /**
     * 渠道说明。dev 的地址优先**从 APK 里真的抠出来**（构建脚本烧进 {@code BuildConfig.PIXIKO_DEFAULT_HOST}，
     * 实测在 {@code classes3.dex} 里能搜到 {@code 172.30.204.50:8787}），抠不到时才退回机器人配置里
     * 对外广播的 {@code webui.host/port}——**绝不假装知道**。
     */
    String channelNote(String channel, JsonObject config, Path file) {
        if (CHANNEL_RELEASE.equals(channel))
            return "发行包（-NoDefaultHost，不含局域网地址），可以分发给别人。";
        String baked = bakedAddress(file);
        if (!baked.isEmpty())
            return "本机测试包（android/dist，已烧进局域网地址 " + baked + "），仅供内网测试，不要转发给别人。";
        return "本机测试包（android/dist，构建时未烧入地址；机器人对外广播 " + advertisedAddress(config)
                + "），仅供内网测试，不要转发给别人。";
    }

    /** 机器人对外广播的地址：{@code webui.host} 不是通配/回环就照用，否则自动找一块非回环 IPv4。 */
    static String advertisedAddress(JsonObject config) {
        JsonObject webui = Json.obj(config, WEBUI_KEY);
        String host = Json.str(webui, "host", "").strip();
        int port = (int) Json.num(webui, "port", 8787);
        String name = host;
        if (name.isEmpty() || name.equals("0.0.0.0") || name.equals("::") || name.startsWith("127.")
                || name.equals("localhost") || name.equals("::1")) name = lanAddress();
        if (name.indexOf(':') >= 0 && !name.startsWith("[")) name = "[" + name + "]";
        return name + ":" + port;
    }

    /** 找一块非回环、非虚拟网卡的 IPv4；找不到就回环。 */
    static String lanAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InterfaceAddress address : network.getInterfaceAddresses()) {
                    java.net.InetAddress ip = address.getAddress();
                    if (ip instanceof java.net.Inet4Address && !ip.isLoopbackAddress() && ip.isSiteLocalAddress())
                        return ip.getHostAddress();
                }
            }
        } catch (Exception error) {
            Log.warn("本机局域网地址探测失败：" + Bot.error(error));
        }
        return "127.0.0.1";
    }

    /** 本机没有该渠道的 APK 时的版本回退值：{@code app_update.fallback_version}，默认与 build.gradle 一致。 */
    private String fallbackVersion() { return Json.str(effectiveConfig(), "fallback_version", "1.6.0"); }

    /**
     * 从 APK 里抠出构建时烧进去的局域网地址（{@code 数字.数字.数字.数字:端口}）。
     *
     * <p>构建脚本把探测到的地址写进 {@code BuildConfig.PIXIKO_DEFAULT_HOST}，字符串常量会进 dex 的
     * 字符串池，所以这里在 {@code classes*.dex} 的字节里搜一次就够（实测 dist 包的
     * {@code classes3.dex} 里能搜到 {@code 172.30.204.50:8787}；{@code -NoDefaultHost} 的发行包搜不到）。
     * 结果按「文件 + mtime + 大小」缓存（同一个 APK 只解压搜一次），失败一律回空串。
     */
    private static String bakedAddress(Path apk) {
        if (apk == null) return "";
        long stamp;
        long size;
        try {
            stamp = Files.getLastModifiedTime(apk).toMillis();
            size = Files.size(apk);
        } catch (IOException error) {
            return "";
        }
        String key = apk.toAbsolutePath().normalize() + "|" + stamp + "|" + size;
        synchronized (BAKED_LOCK) {
            if (key.equals(bakedKey)) return bakedValue;
        }
        String found = "";
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements() && found.isEmpty()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".dex")) continue;
                byte[] dex;
                try (InputStream in = zip.getInputStream(entry)) { dex = in.readAllBytes(); }
                found = firstHost(dex);
            }
        } catch (Exception error) {
            Log.warn("从 APK 里读烧入地址失败（" + apk.getFileName() + "）：" + Bot.error(error));
        }
        synchronized (BAKED_LOCK) {
            bakedKey = key;
            bakedValue = found;
        }
        return found;
    }

    /** 在字节里找第一个形如 {@code 192.168.1.5:8787} 的地址（不要求它独占整段可打印串）。 */
    static String firstHost(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        Matcher matcher = HOST.matcher(new String(bytes, StandardCharsets.ISO_8859_1));
        return matcher.find() ? matcher.group() : "";
    }

    /** {@code android/app/build.gradle} 里的 versionCode 优先；读不到就按文件名算（包内可见，测试要断言这条优先级）。 */
    int gradleVersionCode(String version, List<Path> files) {
        for (Path file : files) {
            Path cursor = file.toAbsolutePath().normalize();
            for (int depth = 0; cursor != null && depth < 6; depth++) {
                Path candidate = cursor.resolve("android/app/build.gradle");
                if (Files.isRegularFile(candidate)) {
                    Integer found = readVersionCode(candidate);
                    if (found != null) return found;
                }
                cursor = cursor.getParent();
            }
        }
        return versionCode(version);
    }

    private static Integer readVersionCode(Path gradle) {
        try {
            for (String line : Files.readAllLines(gradle)) {
                String text = line.strip();
                if (!text.startsWith("versionCode")) continue;
                String value = text.substring("versionCode".length()).strip();
                int end = 0;
                while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
                if (end > 0) return Integer.parseInt(value.substring(0, end));
            }
        } catch (Exception error) {
            Log.warn("读取 versionCode 失败（" + gradle + "）：" + Bot.error(error));
        }
        return null;
    }

    /**
     * {@code versionName} → {@code versionCode} 的**兜底**换算。
     *
     * <p><b>什么时候用</b>：只在 {@code android/app/build.gradle} 读不到（或没有 {@code versionCode} 行）时用；
     * 读得到就永远以 gradle 为准（见 {@link #gradleVersionCode(String, List)}）。线上/发行版的编号以 gradle
     * 为准（实测：1.4.0→140、1.5.0→150、1.5.3→153、1.6.0→160）。
     *
     * <p><b>取法</b>：**单一公式** {@code major*1000000 + minor*1000 + patch*10}——major / minor / patch
     * 各占一个定宽十进制字段（3 位 / 3 位 / 3 位，patch 用十位对齐）。之所以必须定宽：patch 从 9 涨到 10
     * 时如果把 patch 字段从「十位」缩成「个位」（1.0.9 记 90、1.0.10 却记 10），编号会在**同一 minor 内**
     * 回退（1000090 → 1000100 看起来没问题，但 1.0.10 记 10 就是 1000010 &lt; 1000090），
     * 手机端会漏报更新。
     *
     * <p><b>实测取值</b>：1.0.9→1000090、1.0.10→1000100、1.0.18→1000180、1.1.0→1001000、1.4.0→1004000、
     * 1.5.0→1005000、1.5.3→1005030、1.6.0→1006000、1.7.0→1007000、2.0.0→2000000（全部严格递增）。
     * 枚举 1.0.0–1.9.99 共 1000 个版本号，**0 处回退/平台**（见 AppUpdateTest ⑭）。
     *
     * <p><b>与 gradle 的关系（如实写）</b>：兜底值 = gradle 口径 × 10000 / 10 的定宽放大
     * （{@code major*1000000+minor*1000+patch*10} 与 gradle 的 {@code major*100+minor*10+patch} **同序**），
     * 所以**大小关系与 gradle 完全一致**，但绝对值更大（1.6.0 兜底 1006000 vs gradle 160）。
     * 真实运行永远优先读 gradle，线上报的仍是 160；只有 gradle 读不到的极端情况下才用兜底，
     * 那时安卓端会认为"这是一个更高的新版本"→**多提示一次更新，绝不漏报**。
     * 父代理要求的「绝对值就是 160」与「跨 minor 单调递增」在数学上不可兼得（要让 1.0.18 &gt; 1.0.9，
     * 就必须给两位 patch 留出比一位 patch 更大的号段），故取「严格单调 + 与 gradle 同序」。
     */
    static int versionCode(String version) {
        String[] parts = version == null ? new String[0] : version.strip().split("\\.");
        int major = parts.length > 0 ? number(parts[0]) : 0;
        int minor = parts.length > 1 ? number(parts[1]) : 0;
        int patch = parts.length > 2 ? number(parts[2]) : 0;
        // 定宽字段：major（×1000000）/ minor（×1000）/ patch（×10，两位 patch 也落在同一字段里）。
        return major * 1_000_000 + minor * 1_000 + patch * 10;
    }

    /** 从 {@code pixiko-1.6.0-debug.apk} 取版本号；认不出来回空串。 */
    static String versionOf(String fileName) {
        if (fileName == null) return "";
        Matcher matcher = RELEASE_NAME.matcher(fileName);
        return matcher.matches() ? matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3) : "";
    }

    private static int number(String text) {
        int value = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (!Character.isDigit(current)) break;
            value = Math.min(1_000_000, value * 10 + (current - '0'));
        }
        return value;
    }

    private static boolean newerRemote(String tag, String localName) {
        String version = versionOf(localName);
        String remote = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        int[] left = parts(remote);
        int[] right = parts(version);
        if (left == null || right == null) return false;
        for (int index = 0; index < 3; index++) if (left[index] != right[index]) return left[index] > right[index];
        return false;
    }

    private static int[] parts(String version) {
        if (version == null) return null;
        String[] split = version.strip().split("\\.");
        if (split.length < 3) return null;
        int[] result = new int[3];
        for (int index = 0; index < 3; index++) {
            String text = split[index].replaceAll("[^0-9].*$", "");
            if (text.isEmpty()) return null;
            result[index] = number(text);
        }
        return result;
    }

    private String repository(JsonObject config) {
        String name = Json.str(config, "repo", "").strip();
        return name.isEmpty() ? "FaintCloudy/Pixiko" : name;
    }

    /** 请求 Host 拼出的基地址：手机就是用这个地址连上来的，所以不能写死 IP。 */
    static String base(HttpServletRequest request) {
        String scheme = request.getScheme() == null ? "http" : request.getScheme();
        return scheme + "://" + host(request);
    }

    /**
     * 规范化请求里的 Host：保留端口（IPv6 的方括号也保留），剔掉 {@code 0.0.0.0} 这种点不开的值；
     * Host 缺失或不可用时回退到 servlet 解析出的 serverName/serverPort。
     */
    static String host(HttpServletRequest request) {
        String header = request.getHeader("Host");
        if (header != null && !header.isBlank()) {
            String value = header.strip();
            String name = hostName(value);
            String lower = name.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
            if (!name.isBlank() && !lower.equals("0.0.0.0") && !lower.equals("::") && !lower.equals("*")) return value;
        }
        String name = request.getServerName();
        int port = request.getServerPort();
        if (name == null || name.isBlank()) name = "127.0.0.1";
        if (name.indexOf(':') >= 0 && !name.startsWith("[")) name = "[" + name + "]";
        return port > 0 ? name + ":" + port : name;
    }

    private static String hostName(String value) {
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(0, end + 1) : value;
        }
        int colon = value.lastIndexOf(':');
        return colon > 0 ? value.substring(0, colon) : value;
    }

    static boolean isAbsolute(String path) {
        if (path.startsWith("/") || path.startsWith("\\")) return true;
        return path.length() > 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped > 0) { left -= skipped; continue; }
            if (in.read() < 0) throw new IOException("安装包读取提前结束。");
            left--;
        }
    }

    // ------------------------------------------------------------------ 数据结构

    /** 一次实测快照（键＝文件 + mtime + 大小）。 */
    private record Snapshot(String key, long size, String sha256, String publishedAt, long checkedAt) {
        Snapshot(String key, long size, String sha256, String publishedAt) {
            this(key, size, sha256, publishedAt, System.currentTimeMillis());
        }
    }

    /** 一个已经解析出来的字节区间（{@code partial=false} 表示整份文件）。 */
    record Range(long start, long length, boolean partial) {

        /**
         * 解析 {@code Range: bytes=…}：{@code bytes=0-99}、{@code bytes=100-}、{@code bytes=-100} 都认。
         * 没带 Range 头、单位不是 bytes、或多段（逗号）时返回「整份」；
         * 语法正确但越界（起点 ≥ 文件长度，或后缀长度为 0）时返回 {@code null}，调用方转 416。
         */
        static Range parse(long size, String header) {
            if (header == null || header.isBlank()) return full(size);
            String value = header.strip();
            if (!value.toLowerCase(Locale.ROOT).startsWith("bytes=")) return full(size);
            String spec = value.substring("bytes=".length()).strip();
            if (spec.isEmpty() || spec.contains(",")) return full(size);
            int dash = spec.indexOf('-');
            if (dash < 0) return full(size);
            String left = spec.substring(0, dash).strip();
            String right = spec.substring(dash + 1).strip();
            try {
                if (left.isEmpty()) {
                    if (right.isEmpty()) return full(size);
                    long suffix = Long.parseLong(right);
                    if (suffix < 0) return full(size);
                    if (suffix == 0 || size == 0) return null;
                    long length = Math.min(suffix, size);
                    return new Range(size - length, length, true);
                }
                long start = Long.parseLong(left);
                if (start < 0) return full(size);
                if (start >= size) return null;
                if (right.isEmpty()) return new Range(start, size - start, true);
                long end = Long.parseLong(right);
                if (end < start) return full(size);
                return new Range(start, Math.min(end, size - 1) - start + 1, true);
            } catch (NumberFormatException error) {
                return full(size);
            }
        }

        private static Range full(long size) { return new Range(0, Math.max(0, size), false); }
    }
}

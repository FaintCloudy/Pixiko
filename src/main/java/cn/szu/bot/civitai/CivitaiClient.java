package cn.szu.bot.civitai;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.*;
import cn.szu.bot.Json;
import cn.szu.bot.Log;

/** Downloads only verified Civitai LoRA safetensors. No user URL is used as an arbitrary fetch target. */
public final class CivitaiClient {
    public record ShowcasePrompt(int number, String positive, String negative, boolean negativeProvided, String skippedReason) {}
    public record DownloadedLora(String modelName, String versionName, String baseModel, List<String> trainedWords,
                                 Path path, boolean reused, long modelId, long versionId, List<ShowcasePrompt> showcases) {
        public DownloadedLora { trainedWords = List.copyOf(trainedWords); showcases = List.copyOf(showcases); }
        public DownloadedLora(String modelName, String versionName, String baseModel, List<String> trainedWords,
                              Path path, boolean reused, long modelId, long versionId) {
            this(modelName, versionName, baseModel, trainedWords, path, reused, modelId, versionId, List.of());
        }
    }
    public record Response(int status, Map<String, List<String>> headers, InputStream body) implements AutoCloseable {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream()).findFirst().orElse("");
        }
        public void close() throws IOException { body.close(); }
    }
    @FunctionalInterface public interface Transport { Response get(URI uri, Map<String, String> headers, Duration timeout) throws Exception; }
    /** Live transfer meter: bytes received so far and the total size (-1 when unknown). */
    @FunctionalInterface public interface Progress { void update(long downloaded, long total); }
    @FunctionalInterface public interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    private record Link(long modelId, long versionId, Map<String, String> choices) {}
    private record Selection(JsonObject version, JsonObject file, long modelId, long versionId, String modelName) {}
    private record Destination(Path path, boolean reused) {}
    private static final Pattern MODEL = Pattern.compile("^/models/([1-9][0-9]*)(?:/[^/]*)?/?$");
    private static final Pattern VERSION = Pattern.compile("^/(?:api/v1/model-versions|api/download/models)/([1-9][0-9]*)/?$");
    private static final Set<String> FILTERS = Set.of("type", "format", "size", "fp", "fileId");
    private static final long JSON_LIMIT = 8L * 1024 * 1024, HEADER_LIMIT = 16L * 1024 * 1024;
    /** 网页卡片用的封面图上限（避免有人拿一个巨大的图当封面把内存吃满）。 */
    private static final long IMAGE_LIMIT = 12L * 1024 * 1024;
    private static final ScheduledExecutorService DEADLINES = Executors.newScheduledThreadPool(1, task -> {
        Thread thread = new Thread(task, "civitai-download-deadline"); thread.setDaemon(true); return thread;
    });
    /** Civitai 源站；镜像可通过 config.json 的 civitai.base_url 覆盖。 */
    public static final String DEFAULT_BASE_URL = "https://civitai.red";
    /** 官方站：没有登录 Cookie 时只能用它（公开 API，成人内容与部分模型不可见）。 */
    public static final String OFFICIAL_BASE_URL = "https://civitai.com";
    /** 官方域名白名单：配置的镜像、以及用户仍可能粘贴的 civitai.com/civitai.red 链接。 */
    private static final Set<String> OFFICIAL_HOSTS = Set.of("civitai.com", "civitai.red");
    /** 展示链接使用配置的镜像域名。 */
    private static volatile String displayBaseUrl = DEFAULT_BASE_URL;
    private final Path loraDir, manifests;
    private final String token;
    /** 浏览器登录会话（Cookie 头）；仅在官方域名上发送，绝不发给 CDN。 */
    private final String sessionCookie;
    /** Primary Civitai host used for API calls and generated links (configurable mirror). */
    private final String baseUrl;
    private final String baseHost;
    private final long maxBytes, timeoutNanos;
    private final boolean proxyConfigured;
    private final Transport transport;
    private final Resolver resolver;

    public CivitaiClient(Path root, JsonObject config) throws IOException {
        this(root, config, defaultTransport(config), InetAddress::getAllByName, System.getenv("CIVITAI_API_TOKEN"));
    }

    /** Injection is package-private and cannot be enabled through user configuration. */
    public CivitaiClient(Path root, JsonObject config, Transport transport, Resolver resolver, String environmentToken) throws IOException {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        String directory = Json.str(config, "lora_dir", "").strip();
        if (directory.isBlank()) throw new IOException("请先在 config.json 的 civitai.lora_dir 中配置本机 WebUI 的 models/Lora 目录。");
        try {
            Path configured = Path.of(directory);
            if (!configured.isAbsolute()) configured = absoluteRoot.resolve(configured);
            Files.createDirectories(configured);
            loraDir = configured.toRealPath();
            if (!Files.isDirectory(loraDir)) throw new IOException("LoRA 目标不是文件夹。");
            manifests = absoluteRoot.resolve("data/civitai");
            maxBytes = Math.multiplyExact(config.has("max_download_mb") ? config.get("max_download_mb").getAsBigDecimal().longValueExact() : 2048L, 1024L * 1024L);
            long seconds = config.has("timeout_seconds") ? config.get("timeout_seconds").getAsBigDecimal().longValueExact() : 1800L;
            timeoutNanos = Duration.ofSeconds(seconds).toNanos();
            if (maxBytes <= 0 || timeoutNanos <= 0) throw new IllegalArgumentException();
        } catch (Exception e) { throw new IOException("Civitai 目录或下载限制配置无效；max_download_mb 和 timeout_seconds 须为正整数。"); }
        String configuredToken = environmentToken != null && !environmentToken.isBlank() ? environmentToken : Json.str(config, "api_token", "");
        token = configuredToken.strip();
        if (token.chars().anyMatch(c -> c < 32 || c == 127)) throw new IOException("Civitai API Token 不能包含换行或控制字符。");
        sessionCookie = Json.str(config, "session_cookie", "").strip();
        if (sessionCookie.chars().anyMatch(c -> c < 32 || c == 127)) throw new IOException("Civitai 会话 Cookie 不能包含换行或控制字符。");
        this.transport = Objects.requireNonNull(transport);
        this.resolver = Objects.requireNonNull(resolver);
        proxyConfigured = proxyAddress(config) != null;
        String configuredBase = Json.str(config, "base_url", DEFAULT_BASE_URL).strip();
        if (configuredBase.endsWith("/")) configuredBase = configuredBase.substring(0, configuredBase.length() - 1);
        // 没有账号 Cookie 时镜像站打不开（要登录才有内容）：只对已知镜像做回退，其它自定义地址照旧。
        if (sessionCookie.isEmpty() && configuredBase.contains("civitai.red")) {
            Log.warn("没有 Civitai 登录 Cookie，本次改用 " + OFFICIAL_BASE_URL
                    + " 搜索与下载（内容受限，成人内容与部分模型不可见）；在网页控制台生成一次性登录链接即可恢复用 "
                    + configuredBase + "。");
            configuredBase = OFFICIAL_BASE_URL;
        }
        URI base;
        try { base = URI.create(configuredBase); } catch (Exception e) { throw new IOException("civitai.base_url 不是有效地址。"); }
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || base.getFragment() != null || (base.getPort() != -1 && base.getPort() != 443))
            throw new IOException("civitai.base_url 必须是有效的 HTTPS 地址（例如 https://civitai.red）。");
        baseUrl = configuredBase;
        baseHost = base.getHost().toLowerCase(Locale.ROOT);
        displayBaseUrl = configuredBase;
    }

    /**
     * 一条搜索结果。除了模型名/基础模型/封面，还带上网页卡片要显示的信息：
     * 下载数、训练词、文件大小、是否 NSFW（旧写法只给前 5 个字段，多出来的有默认值）。
     */
    public record SearchResult(long modelId, long versionId, String name, String baseModel, String cover,
                               long downloads, List<String> trainedWords, double sizeKb, boolean nsfw) {
        public SearchResult(long modelId, long versionId, String name, String baseModel, String cover) {
            this(modelId, versionId, name, baseModel, cover, 0, List.of(), 0, false);
        }
        public String url() { return displayBaseUrl + "/models/" + modelId + "?modelVersionId=" + versionId; }
    }
    /** 一张抓回来的封面图（网页通过 /api/civitai/thumb 代理，浏览器不直连图床）。 */
    public record Image(byte[] bytes, String contentType) { }
    public List<SearchResult> query(String words) throws Exception {
        if (words.isBlank() || words.length() > 200) throw new IllegalArgumentException("搜索词须为 1–200 个字符。");
        try {
            JsonObject response = api("/api/v1/models?types=LORA&limit=10&query=" + URLEncoder.encode(words, StandardCharsets.UTF_8),
                    System.nanoTime() + Math.min(timeoutNanos, Duration.ofSeconds(60).toNanos()));
            List<SearchResult> results = new ArrayList<>();
            JsonArray items = response.has("items") ? response.getAsJsonArray("items") : new JsonArray();
            for (JsonElement item : items) {
                JsonObject model = item.getAsJsonObject();
                if (!"LORA".equalsIgnoreCase(Json.str(model, "type", ""))) continue;
                JsonArray versions = model.getAsJsonArray("modelVersions");
                if (versions == null || versions.isEmpty()) continue;
                JsonObject version = versions.get(0).getAsJsonObject(); String cover = "";
                JsonArray images = version.getAsJsonArray("images");
                if (images != null) for (JsonElement image : images) {
                    JsonObject im = image.getAsJsonObject();
                    if (!Json.str(im, "type", "image").equals("image")) continue;
                    try {
                        URI uri = URI.create(Json.str(im, "url", ""));
                        if ("https".equalsIgnoreCase(uri.getScheme()) && ("image.civitai.com".equalsIgnoreCase(uri.getHost()) || ("image." + baseHost).equalsIgnoreCase(uri.getHost()))
                                && uri.getUserInfo() == null && uri.getPort() == -1) { cover = uri.toString(); break; }
                    } catch (IllegalArgumentException ignored) { }
                }
                results.add(new SearchResult(positiveLong(model, "id"), positiveLong(version, "id"),
                        Json.str(model, "name", "未命名"), Json.str(version, "baseModel", "未知"), cover,
                        Math.max(0, Json.num(Json.obj(model, "stats"), "downloadCount", 0L)),
                        trainedWords(version), fileSizeKb(version), Json.bool(model, "nsfw", false)));
                if (results.size() == 10) break;
            }
            return List.copyOf(results);
        } catch (Exception e) { throw new IOException("Civitai 搜索失败，请检查网络、访问权限或稍后重试。"); }
    }

    /** 版本自带的训练词（卡片上给一行提示，下载后也知道该用什么触发词）。 */
    private static List<String> trainedWords(JsonObject version) {
        List<String> words = new ArrayList<>();
        JsonArray values = version.getAsJsonArray("trainedWords");
        if (values != null) for (JsonElement value : values) {
            if (!value.isJsonPrimitive()) continue;
            String word = value.getAsString().strip();
            // 有些模型把整段示例 prompt 塞进训练词：卡片上只留一段，别把面板撑爆。
            if (word.length() > 120) word = word.substring(0, 120) + "…";
            if (!word.isEmpty() && words.size() < 8) words.add(word);
        }
        return List.copyOf(words);
    }

    /** 主文件大小（KB）：优先 .safetensors，取不到就 0。 */
    private static double fileSizeKb(JsonObject version) {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null) return 0;
        for (JsonElement item : files) {
            JsonObject file = item.getAsJsonObject();
            String name = Json.str(file, "name", "").toLowerCase(Locale.ROOT);
            if (!name.endsWith(".safetensors")) continue;
            double size = Json.num(file, "sizeKB", 0L);
            if (size > 0) return size;
        }
        return 0;
    }

    /**
     * 抓一张封面图：只允许 Civitai 自己的图床（否则这里就成了任意 URL 代理），
     * 走机器人自己的登录态与代理设置，网页只拿到代理地址。
     */
    public Image cover(String url) throws Exception {
        URI uri = safeUri(url);
        if (!coverHostAllowed(uri)) throw new IllegalArgumentException("只允许抓取 Civitai 图床的封面图。");
        long deadline = System.nanoTime() + Math.min(timeoutNanos, Duration.ofSeconds(30).toNanos());
        try (Response response = request(uri, deadline, false, Map.of("Accept", "image/*"))) {
            status(response.status(), "读取封面图");
            if (contentLength(response) > IMAGE_LIMIT) throw new IOException("封面图过大（超过 " + (IMAGE_LIMIT / 1024 / 1024) + " MB）。");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transfer(response.body(), output, null, IMAGE_LIMIT, deadline, null);
            byte[] bytes = output.toByteArray();
            if (bytes.length < 32) throw new IOException("封面图为空。");
            return new Image(bytes, imageType(bytes));
        }
    }

    /** 网页封面代理的准入判断：先看地址，不依赖配置是否完整（配置有问题也要给出明确的 400）。 */
    public static boolean coverHostAllowed(String url) {
        try { return coverHostAllowed(safeUri(url)); }
        catch (Exception error) { return false; }
    }
    private static boolean coverHostAllowed(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return OFFICIAL_HOSTS.contains(host) || host.endsWith(".civitai.com")
                || host.endsWith(".civitai.red") || host.endsWith(".civitai.net");
    }
    /** 按文件头认图片类型（不信任响应头）。 */
    private static String imageType(byte[] bytes) {
        if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length > 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        if (bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        return "image/jpeg";
    }

    public synchronized DownloadedLora download(String url, Consumer<String> progress) throws Exception {
        return download(url, progress, null);
    }
    /** meter receives live byte counts so a caller can report MiB/total/percent/ETA. */
    public synchronized DownloadedLora download(String url, Consumer<String> progress, Progress meter) throws Exception {
        try { return downloadVerified(url, progress, meter); }
        catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) message = "Civitai 下载失败，请检查配置及网络后重试。";
            message = message.replaceAll("(?i)https?://\\S+", "[下载地址已隐藏]");
            if (!token.isEmpty()) message = message.replace(token, "[令牌已隐藏]");
            // Do not attach transport causes: they can include signed CDN URLs.
            throw new IOException(message);
        }
    }

    private DownloadedLora downloadVerified(String url, Consumer<String> progress, Progress meter) throws Exception {
        Link link = parseLink(url);
        long deadline = System.nanoTime() + timeoutNanos;
        emit(progress, "正在读取 Civitai 模型与版本信息。");
        Selection selected = select(link, deadline);
        JsonObject file = selected.file(), version = selected.version();
        long fileId = positiveLong(file, "id");
        String hash = requiredString(Json.obj(file, "hashes"), "SHA256").toLowerCase(Locale.ROOT);
        if (!hash.matches("[a-f0-9]{64}")) throw new IOException("Civitai 文件缺少有效 SHA256，无法校验下载完整性。");
        JsonElement declaredSize = file.has("sizeKB") ? file.get("sizeKB") : file.get("sizeKb");
        if (declaredSize != null && !declaredSize.isJsonNull()) {
            try {
                if (declaredSize.getAsBigDecimal().signum() < 0) throw new IllegalArgumentException();
                if (declaredSize.getAsBigDecimal().multiply(java.math.BigDecimal.valueOf(1024)).compareTo(java.math.BigDecimal.valueOf(maxBytes)) > 0)
                    throw new IOException("该 LoRA 超过 civitai.max_download_mb 下载大小限制。");
            } catch (IOException e) { throw e; }
            catch (Exception e) { throw new IOException("Civitai 文件大小元数据无效。"); }
        }
        String originalName = requiredString(file, "name");
        String preferredName = safeFilename(originalName);
        String identity = "civitai_" + selected.modelId() + "_" + selected.versionId() + "_" + fileId;
        Destination destination = destination(preferredName, identity, hash, deadline);
        Path target = destination.path();
        String filename = target.getFileName().toString();
        emit(progress, "保存文件名：" + filename);
        List<String> words = new ArrayList<>();
        JsonElement trained = version.get("trainedWords");
        if (trained != null && trained.isJsonArray()) for (JsonElement word : trained.getAsJsonArray()) {
            if (word.isJsonPrimitive() && word.getAsJsonPrimitive().isString()) words.add(word.getAsString());
        }
        boolean reused = destination.reused();
        if (reused) {
            emit(progress, "已有同名文件的 SHA256 与 safetensors 结构校验通过。");
        } else {
            URI download = safeUri(requiredString(file, "downloadUrl"));
            Path part = Files.createTempFile(loraDir, ".civitai-", ".part");
            try {
                emit(progress, "正在下载 LoRA 文件。");
                downloadWithResume(download, part, hash, maxBytes, progress, meter);
                emit(progress, "下载完成，正在检查 safetensors 文件结构。");
                validateSafetensors(part);
                remaining(deadline);
                // Same-volume hard-link publication is atomic and fails if a
                // concurrent process creates the destination: never overwrite.
                try { Files.createLink(target, part); }
                catch (FileAlreadyExistsException e) { verifyExisting(target, hash, deadline); reused = true; }
                catch (UnsupportedOperationException e) { throw new IOException("LoRA 目录文件系统不支持安全原子发布，请使用本机 NTFS 目录。"); }
            } finally { Files.deleteIfExists(part); }
        }
        DownloadedLora result = new DownloadedLora(selected.modelName(), Json.str(version, "name", ""),
                Json.str(version, "baseModel", "未知"), words, target, reused, selected.modelId(), selected.versionId(), showcasePrompts(version));
        JsonObject manifest = new JsonObject();
        manifest.addProperty("model_id", result.modelId()); manifest.addProperty("version_id", result.versionId());
        manifest.addProperty("file_id", fileId); manifest.addProperty("model_name", result.modelName());
        manifest.addProperty("original_filename", originalName);
        manifest.addProperty("version_name", result.versionName()); manifest.addProperty("base_model", result.baseModel());
        manifest.add("trained_words", Json.GSON.toJsonTree(words)); manifest.addProperty("sha256", hash);
        manifest.add("showcase_prompts", Json.GSON.toJsonTree(result.showcases()));
        manifest.addProperty("path", target.toString()); manifest.addProperty("verified_at", Instant.now().toString());
        Json.atomicWrite(manifests.resolve(filename + ".json"), manifest);
        emit(progress, reused ? "已有 LoRA 文件校验通过，可直接使用。" : "LoRA 下载和校验成功。");
        return result;
    }

    /** Preserve the original basename, changing only names Windows cannot safely store. */
    public static String safeFilename(String original) {
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        String extension = name.substring(name.length() - ".safetensors".length());
        String stem = name.substring(0, name.length() - extension.length());
        stem = stem.replaceAll("[\\x00-\\x1f\\x7f<>:\"/\\\\|?*]", "_").replaceAll("[ .]+$", "");
        if (stem.isBlank()) stem = "model";
        if (stem.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?$")) stem = "_" + stem;
        // Leave room for the collision suffix and the manifest's .json extension.
        if (stem.length() > 150) {
            int end = Character.isHighSurrogate(stem.charAt(149)) ? 149 : 150;
            stem = stem.substring(0, end);
        }
        return stem + extension;
    }

    private Destination destination(String name, String identity, String hash, long deadline) throws Exception {
        String stem = name.substring(0, name.length() - ".safetensors".length());
        for (int index = 0; index < 100; index++) {
            remaining(deadline);
            String candidate = index == 0 ? name : stem + "__" + identity + (index == 1 ? "" : "_" + index) + ".safetensors";
            Path path = loraDir.resolve(candidate);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new Destination(path, false);
            if (matchesExisting(path, hash, deadline)) return new Destination(path, true);
        }
        throw new IOException("LoRA 同名文件冲突过多；不会覆盖，请检查目标目录。");
    }

    public static List<ShowcasePrompt> showcasePrompts(JsonObject version) {
        List<ShowcasePrompt> result = new ArrayList<>();
        JsonElement images = version.get("images");
        if (images == null || !images.isJsonArray()) return List.of();
        int index = 0;
        for (JsonElement image : images.getAsJsonArray()) {
            index++;
            JsonObject item = image.isJsonObject() ? image.getAsJsonObject() : new JsonObject();
            JsonObject meta = Json.obj(item, "meta");
            String positive = metadataText(meta, "prompt"), negative = metadataText(meta, "negativePrompt");
            if (negative == null) negative = metadataText(meta, "negative_prompt");
            String reason = "";
            if (positive == null && negative == null) reason = "未提供可读取的正反向提示词";
            else if ((positive == null || positive.isBlank()) && (negative == null || negative.isBlank())) reason = "正反向提示词均为空";
            result.add(new ShowcasePrompt(index, Objects.requireNonNullElse(positive, ""), Objects.requireNonNullElse(negative, ""), negative != null, reason));
        }
        return List.copyOf(result);
    }
    private static String metadataText(JsonObject meta, String key) {
        JsonElement value = meta.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private Selection select(Link link, long deadline) throws Exception {
        JsonObject model = null;
        if (link.modelId() > 0) {
            model = api("/api/v1/models/" + link.modelId(), deadline);
            if (positiveLong(model, "id") != link.modelId()) throw new IOException("Civitai 返回的模型 ID 与链接不一致。");
            requireLora(requiredString(model, "type"));
        }
        if (link.versionId() > 0) return version(link.modelId(), link.versionId(), model, link.choices(), deadline);
        JsonElement versions = model.get("modelVersions");
        if (versions != null && versions.isJsonArray()) for (JsonElement item : versions.getAsJsonArray()) {
            if (!item.isJsonObject()) throw new IOException("Civitai 模型版本列表格式无效。");
            JsonObject candidate = item.getAsJsonObject();
            if (eligibleFiles(candidate, link.choices()).isEmpty()) continue;
            return version(link.modelId(), positiveLong(candidate, "id"), model, link.choices(), deadline);
        }
        throw new IOException("该模型没有符合链接条件的可下载 Model safetensors 版本；不支持 Pickle 或其他文件类型。");
    }

    private Selection version(long expectedModel, long versionId, JsonObject parent, Map<String, String> choices, long deadline) throws Exception {
        JsonObject version = api("/api/v1/model-versions/" + versionId, deadline);
        long modelId = positiveLong(version, "modelId");
        if (positiveLong(version, "id") != versionId || (expectedModel > 0 && modelId != expectedModel))
            throw new IOException("Civitai 版本与链接指定的模型归属不一致，已停止下载。");
        JsonObject model = Json.obj(version, "model");
        if (!model.has("type")) {
            model = parent != null ? parent : api("/api/v1/models/" + modelId, deadline);
            if (positiveLong(model, "id") != modelId) throw new IOException("Civitai 返回的父模型 ID 与版本归属不一致。");
        }
        requireLora(requiredString(model, "type"));
        List<JsonObject> files = eligibleFiles(version, choices);
        if (files.isEmpty()) throw new IOException("指定版本没有符合链接条件的 Model safetensors 文件；不支持 Pickle 或非 LoRA 文件。");
        List<JsonObject> primary = files.stream().filter(f -> Json.bool(f, "primary", false)).toList();
        JsonObject file;
        if (primary.size() == 1) file = primary.get(0);
        else if (files.size() == 1) file = files.get(0);
        else throw new IOException("该版本有多个符合条件的文件，无法唯一确定；请使用带 format/size/fp 的具体下载链接。");
        for (String scan : List.of("pickleScanResult", "virusScanResult")) if ("Danger".equalsIgnoreCase(Json.str(file, scan, "")))
            throw new IOException("Civitai 已将该文件标记为危险，已停止下载。");
        return new Selection(version, file, modelId, versionId, Json.str(model, "name", parent == null ? "LoRA" : Json.str(parent, "name", "LoRA")));
    }

    private static List<JsonObject> eligibleFiles(JsonObject version, Map<String, String> choices) throws IOException {
        List<JsonObject> result = new ArrayList<>();
        JsonElement files = version.get("files");
        if (files == null || files.isJsonNull()) return result;
        if (!files.isJsonArray()) throw new IOException("Civitai 文件列表格式无效。");
        for (JsonElement item : files.getAsJsonArray()) {
            if (!item.isJsonObject()) throw new IOException("Civitai 文件元数据格式无效。");
            JsonObject file = item.getAsJsonObject(), metadata = Json.obj(file, "metadata");
            if (!"Model".equalsIgnoreCase(Json.str(file, "type", ""))
                    || !Json.str(file, "name", "").toLowerCase(Locale.ROOT).endsWith(".safetensors")
                    || !"SafeTensor".equalsIgnoreCase(Json.str(metadata, "format", ""))
                    || Json.str(file, "downloadUrl", "").isBlank()) continue;
            boolean matches = true;
            for (Map.Entry<String, String> filter : choices.entrySet()) {
                String actual = filter.getKey().equals("fileId") ? Long.toString(positiveLong(file, "id"))
                        : Json.str(filter.getKey().equals("type") ? file : metadata, filter.getKey(), "");
                if (!actual.equalsIgnoreCase(filter.getValue())) matches = false;
            }
            if (matches) result.add(file);
        }
        return result;
    }

    private static Link parseLink(String text) throws IOException {
        URI uri = safeUri(text == null ? "" : text.strip());
        if (!official(uri)) throw new IOException("只接受 Civitai 官方链接（civitai.red / civitai.com）的模型、模型版本或下载链接。");
        Map<String, String> query = new LinkedHashMap<>();
        try {
            if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                if (key.startsWith("utm_")) continue;
                if (!FILTERS.contains(key) && !key.equals("modelVersionId"))
                    throw new IOException("链接含不支持的参数；API Token 请写入本机配置，不要放在聊天链接中。");
                if (value.isBlank() || query.putIfAbsent(key, value) != null) throw new IOException("链接参数为空或重复，无法确定要下载的文件。");
            }
            Matcher model = MODEL.matcher(uri.getPath()), version = VERSION.matcher(uri.getPath());
            long modelId = 0, versionId = 0;
            if (model.matches()) modelId = Long.parseLong(model.group(1));
            else if (version.matches()) versionId = Long.parseLong(version.group(1));
            else throw new IOException("请提供 Civitai /models/ID、/api/v1/model-versions/ID 或 /api/download/models/ID 链接。");
            if (query.containsKey("modelVersionId")) {
                long selected = Long.parseLong(query.remove("modelVersionId"));
                if (selected <= 0 || (versionId > 0 && selected != versionId)) throw new IOException("链接中的 modelVersionId 与版本路径不一致。");
                versionId = selected;
            }
            if (query.containsKey("type") && !query.get("type").equalsIgnoreCase("Model")) throw new IOException("只支持 type=Model 的 LoRA 文件。");
            if (query.containsKey("format") && !query.get("format").equalsIgnoreCase("SafeTensor")) throw new IOException("只允许 SafeTensor 格式，不下载 Pickle 文件。");
            if (query.containsKey("fileId")) {
                long fileId = Long.parseLong(query.get("fileId"));
                if (fileId <= 0) throw new IOException("链接中的 fileId 必须是有效正整数。");
                query.put("fileId", Long.toString(fileId));
            }
            if (query.containsKey("fp") && !Set.of("fp16", "fp32", "bf16", "fp8").contains(query.get("fp").toLowerCase(Locale.ROOT)))
                throw new IOException("链接 fp 文件选择不受支持，请复制 Civitai 具体文件的下载链接。");
            if (query.containsKey("size") && !Set.of("full", "pruned").contains(query.get("size").toLowerCase(Locale.ROOT)))
                throw new IOException("链接 size 文件选择不受支持，请复制 Civitai 具体文件的下载链接。");
            return new Link(modelId, versionId, Map.copyOf(query));
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Civitai 链接中的模型、版本 ID 或参数无效。"); }
    }

    private JsonObject api(String path, long deadline) throws Exception {
        try (Response response = request(URI.create(baseUrl + path), deadline, true)) {
            status(response.status(), "读取模型信息");
            if (contentLength(response) > JSON_LIMIT) throw new IOException("Civitai 元数据响应过大。");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transfer(response.body(), output, null, JSON_LIMIT, deadline, null);
            try { return strictJson(output.toByteArray()).getAsJsonObject(); }
            catch (Exception e) { throw new IOException("Civitai 返回了无效元数据或网页，请检查网络、链接及 API 访问权限。"); }
        }
    }

    /**
     * Civitai 元数据里的下载地址仍指向 civitai.com；本站配置了镜像时，下载优先走镜像（civitai.red），
     * 失败再回退到原地址，这样在不改动官方链接语义的前提下把流量切到可用镜像。
     */
    private URI preferMirror(URI uri) {
        String host = uri.getHost();
        if (host == null || !official(uri)) return uri;
        if (host.equalsIgnoreCase(baseHost)) return uri;
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), baseHost, uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (Exception e) { return uri; }
    }

    private Response request(URI initial, long deadline, boolean metadata) throws Exception {
        return request(initial, deadline, metadata, Map.of());
    }

    private Response request(URI initial, long deadline, boolean metadata, Map<String, String> extra) throws Exception {
        URI mirrored = preferMirror(initial);
        if (!mirrored.equals(initial)) {
            try {
                Response response = follow(mirrored, deadline, metadata, extra);
                // 401/403 usually means the file itself needs a login, which the original host would refuse too.
                if (response.status() != 401 && response.status() != 403) return response;
                try (response) { }
                Log.warn("Civitai 镜像 " + baseHost + " 返回 HTTP " + response.status() + "，回退原地址重试");
            } catch (Exception mirrorFailure) {
                Log.warn("Civitai 镜像 " + baseHost + " 请求失败，回退原地址：" + mirrorFailure.getMessage());
            }
        }
        return follow(initial, deadline, metadata, extra);
    }

    private Response follow(URI initial, long deadline, boolean metadata) throws Exception {
        return follow(initial, deadline, metadata, Map.of());
    }

    private Response follow(URI initial, long deadline, boolean metadata, Map<String, String> extra) throws Exception {
        URI uri = initial;
        for (int hop = 0; hop <= 5; hop++) {
            validateTarget(uri);
            if (metadata && !officialApi(uri)) throw new IOException("Civitai 元数据跳转到了非官方 API，已停止。");
            if (!officialApi(uri) && !token.isEmpty() && (uri.toString().contains(token)
                    || URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8).contains(token)))
                throw new IOException("下载跳转包含账户令牌，已停止以避免泄漏。");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.putAll(extra);
            headers.put("Accept", metadata ? "application/json" : "application/octet-stream");
            headers.put("Accept-Encoding", "identity"); headers.put("User-Agent", "Pixiko-Civitai-Downloader/1.0");
            if (officialApi(uri) && !token.isEmpty()) headers.put("Authorization", "Bearer " + token);
            // 浏览器登录态只发给官方域名；下载 CDN 不携带，避免会话泄漏。
            if (!sessionCookie.isEmpty() && official(uri)) headers.put("Cookie", sessionCookie);
            Response response;
            try { response = transport.get(uri, Map.copyOf(headers), remaining(deadline)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Civitai 下载已中断。"); }
            catch (Exception e) { throw new IOException("Civitai 网络连接失败或请求超时；请检查网络及代理设置。"); }
            if (!Set.of(301, 302, 303, 307, 308).contains(response.status())) return response;
            try (response) {
                if (hop == 5) throw new IOException("Civitai 下载跳转次数过多。");
                String location = response.header("Location");
                if (location.isBlank()) throw new IOException("Civitai 下载跳转缺少目标地址。");
                try { uri = safeUri(uri.resolve(location).toString()); }
                catch (Exception e) { throw new IOException("Civitai 下载跳转地址无效。"); }
            }
        }
        throw new IOException("Civitai 下载跳转失败。");
    }

    private void validateTarget(URI uri) throws IOException {
        safeUri(uri.toString());
        if (proxyConfigured) {
            // A configured local HTTP proxy resolves the remote hostname itself
            // (including TUN fake-IP setups). Do not trust arbitrary redirect
            // domains when local DNS cannot independently validate that route.
            if (!trustedProxyHost(uri.getHost())) throw new IOException("代理模式拒绝非 Civitai 官方或已知下载 CDN 的跳转域名。");
            return;
        }
        try {
            InetAddress[] addresses = resolver.resolve(uri.getHost());
            if (addresses.length == 0) throw new IOException();
            for (InetAddress address : addresses) if (!publicAddress(address))
                throw new IOException("Civitai 下载目标解析到了本地、内网或保留地址，已停止。");
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Civitai 下载目标")) throw e;
            throw new IOException("无法安全解析 Civitai 下载目标地址。");
        }
    }

    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int a = b[0] & 255, c = b[1] & 255;
        if (b.length == 4) return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && c >= 64 && c <= 127) && !(a == 169 && c == 254)
                && !(a == 172 && c >= 16 && c <= 31)
                && !(a == 192 && (c == 168 || (c == 0 && (b[2] == 0 || b[2] == 2)) || (c == 88 && (b[2] & 255) == 99)))
                && !(a == 198 && (c == 18 || c == 19 || (c == 51 && (b[2] & 255) == 100)))
                && !(a == 203 && c == 0 && (b[2] & 255) == 113);
        return b.length == 16 && (a & 0xe0) == 0x20 && !(a == 0x20 && c == 0x02)
                && !(a == 0x20 && c == 0x01 && (((b[2] & 0xfe) == 0)
                    || ((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)))
                && !(a == 0x3f && c == 0xff && (b[2] & 0xf0) == 0);
    }

    private static URI safeUri(String text) throws IOException {
        try {
            URI uri = URI.create(text);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getHost().endsWith(".") || text.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException();
            return uri;
        } catch (Exception e) { throw new IOException("下载链接必须是有效 HTTPS 地址，且不能包含账号、片段或非标准端口。"); }
    }
    private static boolean official(URI uri) {
        String host = uri.getHost();
        if (host == null) return false;
        String name = host.toLowerCase(Locale.ROOT);
        return OFFICIAL_HOSTS.contains(name);
    }
    private static boolean officialApi(URI uri) { return official(uri) && uri.getPath().startsWith("/api/"); }
    public static boolean trustedProxyHost(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        return OFFICIAL_HOSTS.contains(name) || name.endsWith(".civitai.com") || name.endsWith(".civitai.red")
                || name.equals("civitai.net") || name.endsWith(".civitai.net")
                || name.matches("civitai-[a-z0-9-]+\\.5ac0637cfd0766c97916cefa3764fbdf\\.r2\\.cloudflarestorage\\.com");
    }
    private static void requireLora(String type) throws IOException {
        if (!type.equalsIgnoreCase("LORA") && !type.equalsIgnoreCase("LoCon")) throw new IOException("该模型不是 LoRA/LoCon，已拒绝下载。");
    }
    private static long positiveLong(JsonObject value, String key) throws IOException {
        try { long result = value.get(key).getAsBigDecimal().longValueExact(); if (result > 0) return result; }
        catch (Exception ignored) { }
        throw new IOException("Civitai 元数据缺少有效正整数字段：" + key);
    }
    private static String requiredString(JsonObject value, String key) throws IOException {
        JsonElement item = value.get(key);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().isBlank())
            throw new IOException("Civitai 元数据缺少有效字段：" + key);
        return item.getAsString();
    }
    private static void status(int status, String action) throws IOException {
        if (status >= 200 && status < 300) return;
        if (status == 401 || status == 403) throw new IOException("Civitai 访问被拒绝（HTTP " + status + "）：该文件需要登录后才能下载，匿名请求会被重定向到登录页。"
                + "请在 config.json 的 civitai.api_token 填入 Civitai API Key（或设置环境变量 CIVITAI_API_TOKEN），并确认账号有权下载该模型；"
                + "浏览器里点击能下载不代表 API 匿名可用。");
        if (status == 404 || status == 410) throw new IOException("Civitai 模型、版本或下载链接不存在、已下架或已失效（HTTP " + status + "）。");
        if (status == 429) throw new IOException("Civitai 请求过于频繁（HTTP 429），请稍后重试。");
        throw new IOException("Civitai " + action + "失败（HTTP " + status + "）。");
    }
    private static long contentLength(Response response) throws IOException {
        String length = response.header("Content-Length");
        if (length.isBlank()) return -1;
        try { long result = Long.parseLong(length); if (result >= 0) return result; }
        catch (Exception ignored) { }
        throw new IOException("Civitai 返回了无效文件长度。");
    }
    private static Duration remaining(long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IOException("Civitai 下载超过总时间限制，已停止并清理临时文件。");
        return Duration.ofNanos(nanos);
    }
    /**
     * Downloads to {@code part}, resuming after an interrupted transfer: the partial file is kept and the
     * next attempt asks for the remaining range (a server without range support restarts from zero). Large
     * LoRA files over a flaky link would otherwise fail outright after minutes of transfer.
     */
    private void downloadWithResume(URI download, Path part, String hash, long maxBytes,
                                    Consumer<String> progress, Progress meter) throws Exception {
        long total = -1;
        for (int attempt = 1; ; attempt++) {
            long deadline = System.nanoTime() + timeoutNanos;
            long already = Files.exists(part) ? Files.size(part) : 0;
            boolean resume = attempt > 1 && already > 0;
            try (Response response = request(download, deadline, false,
                    resume ? Map.of("Range", "bytes=" + already + "-") : Map.of())) {
                status(response.status(), "下载文件");
                String contentType = response.header("Content-Type").toLowerCase(Locale.ROOT);
                if (contentType.contains("text/") || contentType.contains("json") || contentType.contains("html"))
                    throw new IOException("Civitai 返回了网页或错误页面，未收到 LoRA 文件。");
                long declared = contentLength(response);
                boolean appended = resume && response.status() == 206;
                total = appended ? already + declared : declared;
                if (!appended) already = 0;
                if (total > maxBytes) throw new IOException("下载文件超过 civitai.max_download_mb 大小限制。");
                if (meter != null) meter.update(already, total);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                // A resumed file must be hashed from its first byte so the final digest still covers everything.
                if (appended) hashFile(part, digest);
                try (OutputStream output = Files.newOutputStream(part, appended
                        ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                    transfer(response.body(), output, digest, maxBytes, deadline, progress, meter, already, total);
                }
                long downloaded = Files.size(part);
                if (total >= 0 && downloaded != total) throw new IOException("LoRA 下载被截断，文件长度与服务器响应不符。");
                if (!hex(digest.digest()).equals(hash)) throw new IOException("LoRA SHA256 校验失败，已删除未完成文件。");
                if (meter != null) meter.update(downloaded, total);
                return;
            } catch (IOException failure) {
                String message = failure.getMessage() == null ? "" : failure.getMessage();
                // Only a broken transfer is worth resuming: configuration, redirect, auth, size and hash
                // problems will fail identically on a retry (and redirect loops must stay bounded).
                boolean transient_ = !(message.startsWith("下载文件超过") || message.contains("网页或错误页面")
                        || message.contains("SHA256 校验失败") || message.contains("超过总时间限制")
                        || message.contains("跳转") || message.contains("访问被拒绝")
                        || message.contains("内网") || message.contains("代理模式拒绝") || message.contains("无法安全解析")
                        || message.contains("元数据") || message.contains("不是 LoRA") || message.contains("归属"));
                if (!transient_ || attempt >= 3) throw failure;
                Log.warn("Civitai 下载中断，准备续传（第 " + attempt + " 次重试）：" + message);
                emit(progress, "下载中断，正在续传（第 " + attempt + " 次重试）…");
                Thread.sleep(1000L * attempt);
            }
        }
    }

    private static void hashFile(Path path, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            for (int read; (read = input.read(buffer)) >= 0; ) if (read > 0) digest.update(buffer, 0, read);
        }
    }
    private static long transfer(InputStream input, OutputStream output, MessageDigest digest, long limit,
                                 long deadline, Consumer<String> progress) throws IOException {
        return transfer(input, output, digest, limit, deadline, progress, null, 0, -1);
    }
    private static long transfer(InputStream input, OutputStream output, MessageDigest digest, long limit,
                                 long deadline, Consumer<String> progress, Progress meter, long base, long total) throws IOException {
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> alarm = DEADLINES.schedule(() -> {
            timedOut.set(true); try { input.close(); } catch (IOException ignored) { }
        }, remaining(deadline).toNanos(), TimeUnit.NANOSECONDS);
        long count = 0, reportedAt = System.nanoTime();
        try {
            byte[] bytes = new byte[64 * 1024];
            for (int read; (read = input.read(bytes)) >= 0;) {
                remaining(deadline);
                if (read == 0) continue;
                if (read > limit - count) throw new IOException("Civitai 响应超过下载大小限制，已停止并清理临时文件。");
                output.write(bytes, 0, read); if (digest != null) digest.update(bytes, 0, read); count += read;
                if (meter != null) meter.update(base + count, total);
                if (progress != null && System.nanoTime() - reportedAt > TimeUnit.SECONDS.toNanos(10)) {
                    emit(progress, "已下载 " + (base + count) / (1024 * 1024) + " MiB。"); reportedAt = System.nanoTime();
                }
            }
            remaining(deadline);
            return count;
        } catch (IOException e) {
            if (timedOut.get() || deadline - System.nanoTime() <= 0) throw new IOException("Civitai 下载超过总时间限制，已停止并清理临时文件。");
            if (e.getMessage() != null && e.getMessage().startsWith("Civitai 响应超过")) throw e;
            throw new IOException("Civitai 文件传输中断，已清理临时文件；请稍后重试。");
        } finally { alarm.cancel(false); }
    }

    private void verifyExisting(Path path, String expectedHash, long deadline) throws Exception {
        if (!matchesExisting(path, expectedHash, deadline))
            throw new IOException("已有同名 LoRA 文件不安全、过大或 SHA256 不匹配；不会覆盖，请人工检查。");
    }

    private boolean matchesExisting(Path path, String expectedHash, long deadline) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maxBytes)
            return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            transfer(input, OutputStream.nullOutputStream(), digest, maxBytes, deadline, null);
        }
        if (!hex(digest.digest()).equals(expectedHash)) return false;
        validateSafetensors(path);
        return true;
    }

    public static void validateSafetensors(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            ByteBuffer length = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, length);
            long headerLength = length.flip().getLong();
            if (headerLength < 2 || headerLength > HEADER_LIMIT || headerLength > size - 8) throw new IOException();
            byte[] header = new byte[(int) headerLength]; readFully(channel, ByteBuffer.wrap(header));
            if (header[0] != '{') throw new IOException();
            JsonObject object = strictJson(header).getAsJsonObject();
            long dataLength = size - 8 - headerLength;
            List<long[]> intervals = new ArrayList<>();
            int tensors = 0;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonObject tensor = entry.getValue().getAsJsonObject();
                if (entry.getKey().equals("__metadata__")) {
                    for (JsonElement value : tensor.asMap().values()) if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException();
                    continue;
                }
                tensors++;
                String dtype = requiredString(tensor, "dtype");
                int bits = switch (dtype) {
                    case "BOOL", "U8", "I8", "F8_E4M3", "F8_E5M2", "F8_E4M3FN", "F8_E5M2FNUZ", "F8_E8M0" -> 8;
                    case "U16", "I16", "F16", "BF16" -> 16;
                    case "U32", "I32", "F32" -> 32;
                    case "U64", "I64", "F64" -> 64;
                    case "F4", "F4_E2M1" -> 4;
                    default -> throw new IOException();
                };
                JsonArray shape = tensor.getAsJsonArray("shape"), offsets = tensor.getAsJsonArray("data_offsets");
                if (shape.size() > 64 || offsets.size() != 2) throw new IOException();
                long elements = 1;
                for (JsonElement dimension : shape) { long n = dimension.getAsBigDecimal().longValueExact(); if (n < 0) throw new IOException(); elements = Math.multiplyExact(elements, n); }
                long begin = offsets.get(0).getAsBigDecimal().longValueExact(), end = offsets.get(1).getAsBigDecimal().longValueExact();
                long bitLength = Math.multiplyExact(elements, bits);
                if (begin < 0 || end < begin || end > dataLength || bitLength % 8 != 0 || bitLength / 8 != end - begin) throw new IOException();
                if (end > begin) intervals.add(new long[]{begin, end});
            }
            if (tensors == 0 || dataLength == 0) throw new IOException();
            intervals.sort(Comparator.comparingLong(a -> a[0]));
            long end = 0;
            for (long[] interval : intervals) { if (interval[0] != end) throw new IOException(); end = interval[1]; }
            if (end != dataLength) throw new IOException();
        } catch (Exception e) { throw new IOException("文件不是有效 safetensors：头部、张量尺寸或数据长度校验失败。"); }
    }
    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new EOFException();
    }
    private static JsonElement strictJson(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = readJson(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException();
            return value;
        }
    }
    private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new IOException("JSON nesting limit.");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) { String key = reader.nextName(); if (object.has(key)) throw new IOException("Duplicate JSON key."); object.add(key, readJson(reader, depth + 1)); }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray(); reader.beginArray(); while (reader.hasNext()) array.add(readJson(reader, depth + 1)); reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Invalid JSON.");
        };
    }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private static void emit(Consumer<String> progress, String message) {
        if (progress != null) try { progress.accept(message); } catch (RuntimeException ignored) { }
    }
    private static InetSocketAddress proxyAddress(JsonObject config) throws IOException {
        String configured = Json.str(config, "proxy_url", "").strip();
        if (configured.isEmpty()) return null;
        try {
            URI proxy = URI.create(configured);
            if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null || proxy.getUserInfo() != null
                    || proxy.getQuery() != null || proxy.getFragment() != null || !Set.of("", "/").contains(proxy.getPath())
                    || proxy.getPort() < 1 || proxy.getPort() > 65535
                    || !Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(proxy.getHost().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();
            return new InetSocketAddress(proxy.getHost(), proxy.getPort());
        } catch (Exception e) { throw new IOException("civitai.proxy_url 仅支持明确的本机 HTTP 代理，例如 http://127.0.0.1:7890。"); }
    }
    private static Transport defaultTransport(JsonObject config) throws IOException {
        HttpClient.Builder builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20));
        InetSocketAddress proxy = proxyAddress(config);
        if (proxy != null) builder.proxy(ProxySelector.of(proxy));
        HttpClient client = builder.build();
        return (uri, headers, timeout) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).GET();
            headers.forEach(request::header);
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new Response(response.statusCode(), response.headers().map(), response.body());
        };
    }
}

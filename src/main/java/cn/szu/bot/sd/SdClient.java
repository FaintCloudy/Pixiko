package cn.szu.bot.sd;

import com.google.gson.*;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import cn.szu.bot.Bot;
import cn.szu.bot.Json;
import cn.szu.bot.Log;
import cn.szu.bot.prompt.PromptEditor;
import cn.szu.bot.Settings;

/** Stable Diffusion API client. Browser text is available only through the included bridge. */
public final class SdClient {
    public record Prompts(String positive, String negative, String source) {}
    public record SavedStyle(String name, boolean overwritten, String source) {}
    public record StylePrompt(String name, String positive, String negative) {}
    public record Lora(String name, String alias, String path) {}
    public record LoadedLora(String name, String tag, Prompts prompts) {}
    public record GenerationSettings(String samplerName, List<String> styles, int width, int height, String source) {
        public GenerationSettings {
            Objects.requireNonNull(samplerName);
            styles = List.copyOf(styles);
            Objects.requireNonNull(source);
        }
    }
    public record GenerationRequest(Prompts prompts, GenerationSettings settings, GenerationParameters parameters) {
        public GenerationRequest(Prompts prompts, GenerationSettings settings) { this(prompts, settings, null); }
        public GenerationRequest {
            Objects.requireNonNull(prompts);
            Objects.requireNonNull(settings);
        }
    }
    /**
     * SD WebUI 自己的生成进度（GET /sdapi/v1/progress）：网页控制台据此显示进度条，
     * 聊天里 /progress 也能问到"现在跑到第几步了"。
     * {@code reachable=false} 表示这次没读到 SD（没启动/地址不对），必须与"读到了但空闲"分开。
     */
    public record GenerationProgress(boolean reachable, boolean running, String job, double percent, int step, int steps, double etaSeconds) {
        static GenerationProgress idle() { return new GenerationProgress(true, false, "", 0, 0, 0, 0); }
        static GenerationProgress unreachable() { return new GenerationProgress(false, false, "", 0, 0, 0, 0); }
        /** 一行中文说明；空闲与连不上都给明确结论，不把"连不上"说成"空闲"。 */
        public String describe() {
            if (!reachable) return "读不到 SD 进度：SD WebUI 没有在跑或地址不对（生成队列状态仍然可用）";
            if (!running) return "SD 当前空闲（没有正在生成的图）";
            int percentInt = (int) Math.round(percent * 100);
            StringBuilder text = new StringBuilder("SD 正在生成");
            if (steps > 0) text.append("：步骤 ").append(step).append('/').append(steps);
            text.append("（").append(percentInt).append("%）");
            if (etaSeconds > 0 && etaSeconds < 3600) {
                long seconds = Math.round(etaSeconds);
                text.append("，预计还需 ").append(seconds >= 60 ? (seconds / 60) + " 分 " + (seconds % 60) + " 秒" : seconds + " 秒");
            }
            return text.toString();
        }
    }
    /** 进度查询单独用短超时：网页每 1.5 秒问一次，SD 卡住时不能把控制台一起拖住。 */
    private static final Duration PROGRESS_TIMEOUT = Duration.ofSeconds(4);

    private static final String BRIDGE = "/pixiko-bridge/v1/prompts";
    private static final String LOCAL_SOURCE = "本地持久化（未同步 WebUI 当前页面）";
    private static final String DEFAULT_SOURCE = "WebUI 启动默认值（未同步当前页面）";
    private final Path root, stateFile, settingsFile, latestFile, generatedRoot, promptPreviousFile;
    private final JsonObject config;
    private final String baseUrl, authorization;
    private final HttpClient http;
    private final Duration requestTimeout, generationTimeout;
    private Prompts cached;
    private GenerationSettings cachedSettings;
    private JsonElement revision;
    private boolean bridgeAvailable;
    private boolean settingsBridgeAvailable, settingsInitialized;
    private ImageOutbox imageOutbox;
    private GenerationParameters generationParameters;

    public SdClient(Path root, JsonObject config) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.config = config.deepCopy();
        this.stateFile = this.root.resolve("data/sd-state.json");
        this.settingsFile = this.root.resolve("data/sd-settings.json");
        this.latestFile = this.root.resolve("data/sd-latest.json");
        this.generatedRoot = this.root.resolve("data/generated");
        this.promptPreviousFile = this.root.resolve("data/sd-prompt-previous.json");
        String configuredUrl = Json.str(config, "base_url", "http://127.0.0.1:7860").replaceAll("/+$", "");
        URI uri;
        try { uri = URI.create(configuredUrl); }
        catch (IllegalArgumentException e) { throw new IOException("sd.base_url 不是有效地址。", e); }
        if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IOException("sd.base_url 必须是有效的 HTTP(S) 服务地址，账户密码请单独配置。");
        this.baseUrl = configuredUrl;
        String username = Json.str(config, "api_username", "");
        this.authorization = username.isEmpty() ? null : "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + Json.str(config, "api_password", "")).getBytes(StandardCharsets.UTF_8));
        int timeout = Math.max(1, Json.num(config, "timeout_seconds", 600));
        this.requestTimeout = Duration.ofSeconds(Math.min(timeout, 15));
        this.generationTimeout = Duration.ofSeconds(timeout);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(timeout, 5))).build();
        Files.createDirectories(stateFile.getParent());
        Path parameterFile = this.root.resolve("data/sd-parameters.json");
        generationParameters = Files.exists(parameterFile)
                ? GenerationParameters.read(Json.parse(Files.readString(parameterFile, StandardCharsets.UTF_8)))
                : new GenerationParameters(Json.num(config, "steps", 20), decimal("cfg_scale", 7.0), longNumber("seed", -1),
                        Json.str(config, "checkpoint", ""));
        if (Files.exists(stateFile)) {
            try {
                JsonObject saved = Json.parse(Files.readString(stateFile, StandardCharsets.UTF_8));
                cached = new Prompts(requireString(saved, "positive"), requireString(saved, "negative"), LOCAL_SOURCE);
            } catch (Exception e) {
                throw new IOException("无法读取 data/sd-state.json；请检查或恢复此配置文件。", e);
            }
        }
        if (Files.exists(settingsFile)) {
            try {
                cachedSettings = readSettings(Json.parse(Files.readString(settingsFile, StandardCharsets.UTF_8)), LOCAL_SOURCE);
            } catch (Exception e) {
                throw new IOException("无法读取 data/sd-settings.json；请检查或恢复此配置文件。", e);
            }
        }
    }

    /** Refresh from the bridge, falling back only when the bridge is absent or the server is offline. */
    public synchronized Prompts prompts() throws Exception {
        refresh(false);
        return cached;
    }

    public synchronized GenerationSettings settings() throws Exception {
        refresh(true);
        return cachedSettings;
    }

    /** Capture prompts and parameters from one bridge revision, before queuing a generation. */
    public synchronized GenerationRequest generationRequest() throws Exception {
        refresh(true);
        return new GenerationRequest(cached, cachedSettings, parameters());
    }

    /** Resolve the selected checkpoint at submission time, so queued tasks never follow later model switches. */
    public synchronized GenerationParameters parameters() throws Exception {
        GenerationParameters current = generationParameters;
        if (!current.checkpoint().isBlank()) return current;
        String model = "";
        try {
            JsonObject options = responseJson(request("/sdapi/v1/options", "GET", null, false, true), "读取当前基础模型");
            model = Json.str(options, "sd_model_checkpoint", "");
        } catch (BridgeUnavailable ignored) { /* Older/offline API: explicitly report that no model was captured. */ }
        return new GenerationParameters(current.steps(), current.cfgScale(), current.seed(), model);
    }

    public List<String> models() throws Exception {
        JsonArray catalog = responseArray(request("/sdapi/v1/sd-models", "GET", null, false, false), "读取基础模型列表");
        List<String> names = new ArrayList<>();
        for (JsonElement item : catalog) names.add(requireString(item.getAsJsonObject(), "title"));
        return List.copyOf(names);
    }

    private String canonicalModel(String requested) throws Exception {
        List<String> exact = models().stream().filter(name -> name.equalsIgnoreCase(requested)).toList();
        if (exact.size() != 1) throw new IOException("基础模型不存在或名称不唯一；请用 .model list 查看完整名称。");
        return exact.get(0);
    }

    public synchronized GenerationParameters setParameter(String field, String value) throws Exception {
        GenerationParameters old = generationParameters;
        GenerationParameters updated;
        try {
            updated = switch (field) {
                case "steps" -> new GenerationParameters(Integer.parseInt(value), old.cfgScale(), old.seed(), old.checkpoint());
                case "cfg" -> new GenerationParameters(old.steps(), Double.parseDouble(value), old.seed(), old.checkpoint());
                case "seed" -> new GenerationParameters(old.steps(), old.cfgScale(), Long.parseLong(value), old.checkpoint());
                case "model" -> new GenerationParameters(old.steps(), old.cfgScale(), old.seed(), canonicalModel(value));
                default -> throw new IOException("未知生成参数。");
            };
        } catch (NumberFormatException e) { throw new IOException("参数数值格式不正确。", e); }
        persistParameters(updated);
        return updated;
    }

    private void persistParameters(GenerationParameters updated) throws IOException {
        Json.atomicWrite(root.resolve("data/sd-parameters.json"), updated.json());
        generationParameters = updated;
    }

    public synchronized GenerationRequest loadPreset(GenerationPreset preset) throws Exception {
        GenerationParameters p = preset.parameters();
        String model = canonicalModel(p.checkpoint());
        if (!samplers().contains(preset.sampler())) throw new IOException("预设采样方法已不可用；请用 .sampler list 查看。");
        validateSize(preset.width(), preset.height());
        GenerationSettings basic = changeSettings(Set.of("sampler_name", "width", "height"), current ->
                new GenerationSettings(preset.sampler(), current.styles(), preset.width(), preset.height(), LOCAL_SOURCE));
        if (!basic.samplerName().equals(preset.sampler()) || basic.width() != preset.width() || basic.height() != preset.height())
            throw new IOException("WebUI 未确认预设中的尺寸或采样方法，请检查参数后重试。");
        try { persistParameters(new GenerationParameters(p.steps(), p.cfgScale(), p.seed(), model)); }
        catch (IOException e) { throw new IOException("尺寸和采样方法已更新，但其他参数保存失败；请检查磁盘并重新加载预设。", e); }
        return new GenerationRequest(cached, basic, generationParameters);
    }

    private void refresh(boolean needSettings) throws Exception {
        bridgeAvailable = false;
        settingsBridgeAvailable = false;
        settingsInitialized = false;
        revision = null;
        try {
            JsonObject state = responseJson(request(BRIDGE, "GET", null, false, true), "读取 WebUI 提示词");
            Prompts value = bridgePrompts(state);
            GenerationSettings parameters = bridgeSettings(state, value.source());
            if (needSettings && parameters == null) parameters = fallbackSettings();
            persist(value);
            if (parameters != null) persist(parameters);
            cached = value;
            if (parameters != null) cachedSettings = parameters;
            revision = state.get("revision").deepCopy();
            bridgeAvailable = true;
        } catch (BridgeUnavailable e) {
            if (cached == null) cached = initialDefaults();
            else cached = new Prompts(cached.positive(), cached.negative(), LOCAL_SOURCE);
            if (needSettings) cachedSettings = fallbackSettings();
            persist(cached);
            if (needSettings) persist(cachedSettings);
        }
    }

    public List<String> samplers() throws Exception {
        return samplerCatalog().stream().map(Sampler::name).toList();
    }

    public List<String> styles() throws Exception {
        JsonArray catalog = responseArray(request("/sdapi/v1/prompt-styles", "GET", null, false, false), "读取预设样式列表");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonElement item : catalog) {
            if (!item.isJsonObject()) throw new IOException("WebUI 预设样式列表格式无效。");
            String name = requireString(item.getAsJsonObject(), "name");
            if (name.isBlank()) throw new IOException("WebUI 返回了空的预设样式名称。");
            names.add(name);
        }
        return List.copyOf(names);
    }

    /** Read original template text, including placeholders, without changing the UI. */
    public List<StylePrompt> stylePrompts() throws Exception {
        JsonArray catalog = responseArray(request("/sdapi/v1/prompt-styles", "GET", null, false, false), "读取预设样式提示词");
        List<StylePrompt> result = new ArrayList<>();
        for (JsonElement item : catalog) {
            if (!item.isJsonObject()) throw new IOException("WebUI 预设样式列表格式无效。");
            JsonObject style = item.getAsJsonObject();
            String name = requireString(style, "name");
            if (name.isBlank()) throw new IOException("WebUI 返回了空的预设样式名称。");
            if (!style.has("prompt") || !style.has("negative_prompt"))
                throw new IOException("WebUI 未返回预设样式的完整正反向提示词。");
            // Native multi-CSV dividers have two null templates and are not styles.
            if (style.get("prompt").isJsonNull() && style.get("negative_prompt").isJsonNull()) continue;
            String positive = style.get("prompt").isJsonNull() ? "" : requireString(style, "prompt");
            String negative = style.get("negative_prompt").isJsonNull() ? "" : requireString(style, "negative_prompt");
            result.add(new StylePrompt(name, positive, negative));
        }
        return List.copyOf(result);
    }

    public StylePrompt stylePrompt(String name) throws Exception {
        if (name == null || name.isBlank()) throw new IOException("请提供预设样式名称；使用 .style list 查看可用名称。");
        List<StylePrompt> available = stylePrompts();
        List<StylePrompt> matches = available.stream().filter(style -> style.name().equals(name)).toList();
        if (matches.isEmpty()) matches = available.stream().filter(style -> style.name().equals(name.strip())).toList();
        if (matches.size() != 1)
            throw new IOException((matches.isEmpty() ? "未知预设样式或该项仅是分隔标题：" : "WebUI 返回了重名预设样式：")
                    + name + "。请使用 .style list 查看可用名称（区分大小写）。");
        return matches.get(0);
    }

    /** Atomically import both template texts and remove only this selected style. */
    public synchronized Prompts importStyle(String name, boolean append) throws Exception {
        StylePrompt template = stylePrompt(name);
        String positiveTemplate = importedStyleText(template.positive());
        String negativeTemplate = importedStyleText(template.negative());
        for (int attempt = 0; attempt < 3; attempt++) {
            bridgeAvailable = false;
            settingsBridgeAvailable = false;
            settingsInitialized = false;
            HttpResponse<String> response = request(BRIDGE, "GET", null, false, false);
            if (response.statusCode() == 404)
                throw new IOException("导入样式需要在线的新版本 WebUI 桥接，请安装并重载附带扩展后重试；当前提示词未修改。");
            JsonObject state = responseJson(response, "读取 WebUI 状态以导入样式");
            Prompts current = bridgePrompts(state);
            GenerationSettings parameters = bridgeSettings(state, current.source());
            if (parameters == null || !settingsBridgeAvailable || !settingsInitialized)
                throw new IOException("导入样式需要已同步生成参数的新版本 WebUI 桥接；请刷新并打开文生图页面后重试。");
            bridgeAvailable = true;
            String positive = append ? appendStyleText(current.positive(), positiveTemplate) : positiveTemplate;
            String negative = append ? appendStyleText(current.negative(), negativeTemplate) : negativeTemplate;
            List<String> selected = parameters.styles().stream().filter(style -> !style.equals(template.name())).toList();
            JsonObject payload = new JsonObject();
            payload.addProperty("positive", positive);
            payload.addProperty("negative", negative);
            payload.add("styles", Json.GSON.toJsonTree(selected));
            payload.add("expected_revision", state.get("revision").deepCopy());
            response = request(BRIDGE, "PUT", payload, false, false);
            if (response.statusCode() == 409) continue;
            state = responseJson(response, "导入 WebUI 样式提示词");
            Prompts confirmed = bridgePrompts(state);
            GenerationSettings confirmedSettings = bridgeSettings(state, confirmed.source());
            if (confirmedSettings == null || !confirmed.positive().equals(positive) || !confirmed.negative().equals(negative)
                    || !confirmedSettings.styles().equals(selected)
                    || !confirmedSettings.samplerName().equals(parameters.samplerName())
                    || confirmedSettings.width() != parameters.width() || confirmedSettings.height() != parameters.height())
                throw new IOException("WebUI 未确认完整的样式导入结果，请检查当前正反向提示词与样式选择。");
            rememberPrevious(current,confirmed);
            persist(confirmed);
            persist(confirmedSettings);
            cached = confirmed;
            cachedSettings = confirmedSettings;
            revision = state.get("revision").deepCopy();
            return confirmed;
        }
        throw new IOException("WebUI 提示词或样式选择正在被其他窗口修改，导入未确认成功，请稍后重试。");
    }

    /** Style template text merged into a personal prompt copy; performs no WebUI write. */
    public synchronized Prompts styleAsPrompts(String name, Prompts current, boolean append) throws Exception {
        return styleAsPrompts(stylePrompt(name), current, append);
    }

    /** Same merge from an already resolved template, so callers can report the canonical style name. */
    public Prompts styleAsPrompts(StylePrompt template, Prompts current, boolean append) {
        String positive = importedStyleText(template.positive());
        String negative = importedStyleText(template.negative());
        return new Prompts(
                append ? appendStyleText(current.positive(), positive) : positive,
                append ? appendStyleText(current.negative(), negative) : negative,
                UserPromptStore.PERSONAL_SOURCE);
    }

    private static String importedStyleText(String template) { return styleText(template); }

    /** 样式原文的清洗：WebUI 的 {prompt} 占位符换成空、去掉首尾逗号。 */
    public static String styleText(String template) {
        return template == null ? "" : template.replace("{prompt}", "").replaceAll("(?U)^[\\s,，]+|[\\s,，]+$", "");
    }

    private static String appendStyleText(String current, String addition) {
        if (addition.isEmpty()) return current;
        return current.isBlank() ? addition : current + ", " + addition;
    }

    /** The official catalog is read-only; listing does not refresh or load GPU weights. */
    public List<Lora> loras() throws Exception {
        JsonArray catalog = responseArray(request("/sdapi/v1/loras", "GET", null, false, false), "读取 LoRA 列表");
        List<Lora> result = new ArrayList<>();
        for (JsonElement item : catalog) {
            if (!item.isJsonObject()) throw new IOException("WebUI LoRA 列表格式无效。");
            JsonObject value = item.getAsJsonObject();
            String name = requireString(value, "name"), path = requireString(value, "path");
            String alias = value.has("alias") && !value.get("alias").isJsonNull() ? requireString(value, "alias") : name;
            if (name.isBlank() || path.isBlank()) throw new IOException("WebUI LoRA 列表包含空名称或路径。");
            result.add(new Lora(name, alias, path));
        }
        return List.copyOf(result);
    }

    /** 磁盘上的 LoRA 文件变了（删除/重命名/下载）之后刷新 WebUI 的 LoRA 目录。 */
    public synchronized List<Lora> refreshLoras() throws Exception { return refreshedLoras(); }

    /** Select an installed LoRA by filename/name or an unambiguous metadata alias. */
    public synchronized LoadedLora loadLora(String name, double weight) throws Exception {
        String requested = loraTagName(name);
        String formattedWeight = loraWeight(weight);
        List<Lora> catalog = refreshedLoras();
        List<Lora> matches = catalog.stream().filter(item -> item.name().equals(requested)).toList();
        if (matches.isEmpty()) matches = catalog.stream().filter(item -> item.name().equalsIgnoreCase(requested)).toList();
        if (matches.isEmpty()) matches = catalog.stream().filter(item -> loraFilename(item.path()).equalsIgnoreCase(requested)).toList();
        if (matches.isEmpty()) matches = catalog.stream().filter(item -> item.alias().equals(requested)).toList();
        if (matches.isEmpty()) matches = catalog.stream().filter(item -> item.alias().equalsIgnoreCase(requested)).toList();
        if (matches.size() != 1)
            throw new IOException((matches.isEmpty() ? "WebUI 未找到 LoRA：" : "LoRA 名称对应多个文件，无法安全选择：")
                    + requested + "。请用 .lora list 检查官方名称。");
        Lora selected = matches.get(0);
        return enableLora(selected, catalog, formattedWeight);
    }

    /** A downloaded file must match the exact local file reported by WebUI after refresh. */
    public synchronized LoadedLora loadLora(Path downloadedFile, double weight) throws Exception {
        String formattedWeight = loraWeight(weight);
        if (downloadedFile == null || !downloadedFile.isAbsolute())
            throw new IOException("下载的 LoRA 必须提供本机绝对文件路径。");
        Path actual = localLoraFile(downloadedFile);
        List<Lora> catalog = refreshedLoras();
        List<Lora> matches = new ArrayList<>();
        for (Lora lora : catalog) {
            try {
                Path registered = Path.of(lora.path());
                if (!registered.isAbsolute()) continue;
                Path normalized = registered.toAbsolutePath().normalize();
                if (sameLocalPath(normalized, actual) || (Files.isRegularFile(normalized)
                        && sameLocalPath(normalized.toRealPath(), actual))) matches.add(lora);
            } catch (InvalidPathException | IOException ignored) { /* Other stale catalog entries are not this download. */ }
        }
        if (matches.size() != 1)
            throw new IOException(matches.isEmpty()
                    ? "WebUI 刷新后尚未识别此下载文件；文件已保留，请检查 LoRA 目录后重试。"
                    : "WebUI 对此下载路径返回了多个 LoRA 条目；文件已保留，未启用以免选错模型。");
        return enableLora(matches.get(0), catalog, formattedWeight);
    }

    private List<Lora> refreshedLoras() throws Exception {
        // The official handler returns JSON null; a successful HTTP status is the acknowledgement.
        HttpResponse<String> refreshed = request("/sdapi/v1/refresh-loras", "POST", new JsonObject(), false, false);
        if (refreshed.statusCode() < 200 || refreshed.statusCode() >= 300) responseJson(refreshed, "刷新 LoRA 列表");
        return loras();
    }

    private record LoraIdentity(String canonical, String tag, Set<String> names) {}
    public synchronized String resolvedLoraTag(Path path, double weight) throws Exception {
        return resolvedLoraTag(path, refreshedLoras(), weight);
    }
    public static String resolvedLoraTag(Path path, List<Lora> catalog, double weight) throws Exception {
        Path actual = localLoraFile(path);
        List<Lora> matches = new ArrayList<>();
        for (Lora lora : catalog) {
            try { if (sameLocalPath(localLoraFile(Path.of(lora.path())), actual)) matches.add(lora); }
            catch (IOException | InvalidPathException ignored) {}
        }
        if (matches.size() != 1) throw new IOException("无法唯一确认本机 LoRA 文件对应的标签。");
        return loraIdentity(matches.get(0), catalog, loraWeight(weight)).tag();
    }
    private static LoraIdentity loraIdentity(Lora selected, List<Lora> catalog, String weight) throws Exception {
        String canonical = loraTagName(selected.name());
        if (!canonical.equals(selected.name())) throw new IOException("WebUI LoRA 官方名称带有首尾空白，无法安全构造标签。");
        // Canonical names are the actual filename stems used by the official loader.
        Path file;
        try { file = localLoraFile(Path.of(selected.path())); }
        catch (InvalidPathException e) { throw new IOException("WebUI 返回了无效的 LoRA 文件路径。", e); }
        String filename = file.getFileName().toString();
        int extension = filename.lastIndexOf('.');
        String stem = extension > 0 ? filename.substring(0, extension) : filename;
        if (!stem.equalsIgnoreCase(canonical))
            throw new IOException("WebUI LoRA 名称与真实文件名不一致，未启用以免选错模型。");
        if (catalog.stream().filter(item -> item.name().equalsIgnoreCase(canonical)).count() != 1)
            throw new IOException("WebUI 返回了重名 LoRA，无法保证标签对应此文件；未启用。");
        String tagName = canonical;
        // Civitai downloads have ID-based filenames. Use the complete WebUI metadata
        // alias when it identifies exactly this file, as the WebUI LoRA card does.
        String alias = selected.alias();
        try {
            if (loraTagName(alias).equals(alias) && !alias.equalsIgnoreCase("none")
                    && !alias.equalsIgnoreCase("Addams")
                    && catalog.stream().filter(item -> item.alias().equalsIgnoreCase(alias)
                            || item.name().equalsIgnoreCase(alias)).count() == 1)
                tagName = alias;
        } catch (IOException ignored) { /* Invalid aliases fall back to the verified filename stem. */ }
        String tag = "<lora:" + tagName + ":" + weight + ">";
        Set<String> existingNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        existingNames.add(canonical);
        if (!selected.alias().isBlank() && catalog.stream().filter(item -> item.alias().equalsIgnoreCase(selected.alias())
                || item.name().equalsIgnoreCase(selected.alias())).count() == 1) existingNames.add(selected.alias());

        return new LoraIdentity(canonical, tag, Set.copyOf(existingNames));
    }
    private LoadedLora enableLora(Lora selected, List<Lora> catalog, String weight) throws Exception {
        LoraIdentity identity = loraIdentity(selected, catalog, weight);
        String canonical = identity.canonical(), tag = identity.tag();
        Set<String> existingNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        existingNames.addAll(identity.names());

        for (int attempt = 0; attempt < 3; attempt++) {
            bridgeAvailable = false;
            HttpResponse<String> response = request(BRIDGE, "GET", null, false, false);
            if (response.statusCode() == 404)
                throw new IOException("LoRA 未启用：WebUI 桥接不可用，请安装并重载桥接扩展后重试；下载文件会保留。");
            JsonObject state = responseJson(response, "读取 WebUI 桥接提示词以启用 LoRA");
            Prompts current = bridgePrompts(state);
            bridgeAvailable = true;
            String positive = replaceLoraTag(current.positive(), existingNames, tag);
            JsonObject payload = new JsonObject();
            payload.addProperty("positive", positive);
            payload.add("expected_revision", state.get("revision").deepCopy());
            response = request(BRIDGE, "PUT", payload, false, false);
            if (response.statusCode() == 409) continue;
            state = responseJson(response, "启用 WebUI LoRA");
            Prompts confirmed = bridgePrompts(state);
            if (!confirmed.positive().equals(positive) || !confirmed.negative().equals(current.negative()))
                throw new IOException("WebUI 未确认完整的 LoRA 提示词更新，请检查当前提示词后重试。");
            GenerationSettings parameters = bridgeSettings(state, confirmed.source());
            if (parameters != null) { persist(parameters); cachedSettings = parameters; }
            rememberPrevious(current,confirmed);
            persist(confirmed);
            cached = confirmed;
            revision = state.get("revision").deepCopy();
            return new LoadedLora(canonical, tag, confirmed);
        }
        throw new IOException("WebUI 提示词正在被其他窗口修改，LoRA 未确认启用，请稍后重试。");
    }

    /** Inserts or updates one LoRA tag in an arbitrary positive prompt (used for personal prompt copies). */
    public static String applyLoraTag(String positive, String tag) {
        if (positive == null || tag == null) return positive;
        Matcher matcher = Pattern.compile("<lora:([^<>:\\r\\n]+):[^<>\\r\\n]*>", Pattern.CASE_INSENSITIVE).matcher(tag);
        if (!matcher.matches()) return positive;
        return replaceLoraTag(positive, Set.of(matcher.group(1)), tag);
    }
    private static String replaceLoraTag(String prompt, Set<String> names, String tag) {
        Matcher matcher = Pattern.compile("<lora:([^<>:\\r\\n]+):[^<>\\r\\n]*>", Pattern.CASE_INSENSITIVE).matcher(prompt);
        StringBuilder result = new StringBuilder();
        boolean replaced = false;
        while (matcher.find()) {
            if (!names.contains(matcher.group(1))) continue;
            matcher.appendReplacement(result, Matcher.quoteReplacement(replaced ? "" : tag));
            replaced = true;
        }
        matcher.appendTail(result);
        return replaced ? result.toString() : prompt.isBlank() ? tag : prompt + ", " + tag;
    }

    private static String loraTagName(String value) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("LoRA 名称不能为空。");
        if (value.codePoints().anyMatch(character -> Character.isISOControl(character) || character == 0x2028 || character == 0x2029
                || character == '<' || character == '>' || character == ':' || character == '/' || character == '\\'))
            throw new IOException("LoRA 名称不能包含路径分隔符、<>:、换行或其他控制字符。");
        return value.strip();
    }

    private static String loraWeight(double weight) throws IOException {
        if (!Double.isFinite(weight) || weight < 0 || weight > 2)
            throw new IOException("LoRA 权重必须是 0–2 之间的有限数字。");
        return java.math.BigDecimal.valueOf(weight == 0 ? 0 : weight).stripTrailingZeros().toPlainString();
    }

    private static String loraFilename(String path) {
        try { return Path.of(path).getFileName().toString(); }
        catch (InvalidPathException | NullPointerException e) { return ""; }
    }

    private static Path localLoraFile(Path path) throws IOException {
        if (!path.isAbsolute() || !Files.isRegularFile(path)) throw new IOException("LoRA 真实文件不存在或无法读取；文件未确认启用。");
        return path.toRealPath().normalize();
    }

    private static boolean sameLocalPath(Path left, Path right) {
        String first = left.toAbsolutePath().normalize().toString(), second = right.toAbsolutePath().normalize().toString();
        return File.separatorChar == '\\' ? first.equalsIgnoreCase(second) : first.equals(second);
    }

    /** Persist one captured prompt pair in the WebUI style database, without selecting or applying it. */
    public synchronized SavedStyle saveStyle(String name, boolean overwrite) throws Exception {
        String requested = styleSaveName(name);
        Prompts snapshot = prompts();
        return saveStylePair(requested, snapshot.positive(), snapshot.negative(), overwrite, snapshot.source());
    }
    /** Deletes one preset style from the WebUI style CSV; a missing endpoint (old extension) is explicit. */
    public synchronized void deleteStyle(String name) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("name", styleSaveName(name));
        HttpResponse<String> response = request("/pixiko-bridge/v1/styles/delete", "POST", payload, false, false);
        if (response.statusCode() == 404)
            throw new UnsupportedOperationException("WebUI 桥接没有样式删除接口（HTTP 404）。");
        if (response.statusCode() == 400) throw new IOException("样式删除被拒绝：请核对名称是否存在、是否只是分隔标题。");
        JsonObject result = responseJson(response, "删除 WebUI 预设样式");
        JsonElement confirmed = result.get("deleted");
        if (confirmed == null || !confirmed.isJsonPrimitive() || !confirmed.getAsBoolean())
            throw new IOException("WebUI 返回的删除确认无效，无法确认结果；请用 /style list 检查。");
    }
    /** Renames one preset style inside the WebUI style CSV; an old extension without the endpoint is explicit. */
    public synchronized SavedStyle renameStyle(String name, String newName, boolean overwrite) throws Exception {
        String requested = styleSaveName(name), target = styleSaveName(newName);
        JsonObject payload = new JsonObject();
        payload.addProperty("name", requested);
        payload.addProperty("new_name", target);
        payload.addProperty("overwrite", overwrite);
        HttpResponse<String> response = request("/pixiko-bridge/v1/styles/rename", "POST", payload, false, false);
        if (response.statusCode() == 404)
            throw new UnsupportedOperationException("WebUI 桥接没有样式改名接口（HTTP 404）。");
        if (response.statusCode() == 409)
            throw new IOException(overwrite ? "WebUI 样式在改名过程中被修改（HTTP 409），请稍后重试。"
                    : "目标样式名已存在（HTTP 409）；确认覆盖请用 /style rename overwrite " + requested + " " + target);
        if (response.statusCode() == 400) throw new IOException("样式改名被拒绝：请核对旧名称是否存在、名称是否合法。");
        JsonObject result = responseJson(response, "重命名 WebUI 预设样式");
        JsonElement confirmed = result.get("name");
        if (confirmed == null || !confirmed.isJsonPrimitive() || !target.equals(confirmed.getAsString()))
            throw new IOException("WebUI 返回的改名确认无效，无法确认结果；请用 /style list 检查。");
        return new SavedStyle(target, true, "样式重命名");
    }
    /** Saves supplied metadata without reading, changing or selecting the live prompt fields. */
    public synchronized SavedStyle saveStylePair(String name, String positive, String negative, boolean overwrite, String source) throws Exception {
        String requested = styleSaveName(name);
        JsonObject payload = new JsonObject();
        payload.addProperty("name", requested);
        payload.addProperty("positive", Objects.requireNonNull(positive));
        payload.addProperty("negative", Objects.requireNonNull(negative));
        payload.addProperty("overwrite", overwrite);
        HttpResponse<String> response = request("/pixiko-bridge/v1/styles", "POST", payload, false, false);
        if (response.statusCode() == 409) {
            throw new IOException(overwrite
                    ? "WebUI 样式文件同时被修改（HTTP 409），未确认覆盖成功，请稍后重试。"
                    : "WebUI 可能已有同名预设样式，或样式文件同时被修改（HTTP 409）。请先用 .style list 检查；确认覆盖时使用 .style overwrite " + requested);
        }
        if (response.statusCode() == 404)
            throw new IOException("WebUI 样式保存接口不存在（HTTP 404），请更新附带桥接扩展并重启 WebUI 后重试；样式未确认保存。");
        JsonObject result = responseJson(response, "保存 WebUI 预设样式");
        JsonElement confirmedName = result.get("name"), confirmedOverwrite = result.get("overwritten");
        if (confirmedName == null || !confirmedName.isJsonPrimitive() || !confirmedName.getAsJsonPrimitive().isString()
                || !requested.equals(confirmedName.getAsString()) || confirmedOverwrite == null
                || !confirmedOverwrite.isJsonPrimitive() || !confirmedOverwrite.getAsJsonPrimitive().isBoolean()
                || (!overwrite && confirmedOverwrite.getAsBoolean()))
            throw new IOException("WebUI 返回的样式保存确认无效，无法确认保存结果；请用 .style list 检查。");
        return new SavedStyle(requested, confirmedOverwrite.getAsBoolean(), source);
    }

    /** Validates a style name without touching the WebUI, so callers can fail fast before reading prompts. */
    public static String styleSaveName(String name) throws IOException {
        if (name == null || name.isBlank())
            throw new IOException("预设样式名称不能为空。用法：.style save <名称> 或 .style overwrite <名称>");
        if (name.codePoints().anyMatch(value -> Character.isISOControl(value) || value == 0x2028 || value == 0x2029))
            throw new IOException("预设样式名称不能包含换行、制表符或其他控制字符。");
        String requested = name.strip();
        if (requested.startsWith("#")) throw new IOException("预设样式名称不能以 # 开头。");
        if (requested.codePointCount(0, requested.length()) > 200)
            throw new IOException("预设样式名称不能超过 200 个字符。");
        return requested;
    }

    public synchronized GenerationSettings setSampler(String name) throws Exception {
        if (name == null || name.isBlank()) throw new IOException("请提供采样方法名称；使用 .sampler list 查看可用名称。");
        String requested = name.trim();
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        for (Sampler sampler : samplerCatalog()) {
            if (sampler.name().equalsIgnoreCase(requested)
                    || sampler.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(requested)))
                matches.add(sampler.name());
        }
        if (matches.size() != 1)
            throw new IOException((matches.isEmpty() ? "未知采样方法：" : "采样方法名称有歧义：") + requested
                    + "。使用 .sampler list 查看可用名称。");
        String canonical = matches.iterator().next();
        return changeSettings(Set.of("sampler_name"), current -> new GenerationSettings(canonical,
                current.styles(), current.width(), current.height(), LOCAL_SOURCE));
    }

    public synchronized GenerationSettings setStyles(List<String> names) throws Exception {
        if (names == null || names.stream().anyMatch(name -> name == null || name.isBlank()))
            throw new IOException("预设样式名称不能为空；使用 .style list 查看可用名称，.style clear 清空。");
        List<String> selected = List.copyOf(new LinkedHashSet<>(names));
        // Clearing all styles is also useful while offline and does not require a catalog.
        if (!selected.isEmpty()) {
            List<String> available = styles();
            for (String name : selected) if (!available.contains(name))
                throw new IOException("未知预设样式：" + name + "。使用 .style list 查看可用名称（区分大小写）。");
        }
        return changeSettings(Set.of("styles"), current -> new GenerationSettings(current.samplerName(),
                selected, current.width(), current.height(), LOCAL_SOURCE));
    }

    public synchronized GenerationSettings setSize(int width, int height) throws Exception {
        validateSize(width, height);
        return changeSettings(Set.of("width", "height"), current -> new GenerationSettings(current.samplerName(),
                current.styles(), width, height, LOCAL_SOURCE));
    }

    private GenerationSettings changeSettings(Set<String> fields,
            java.util.function.UnaryOperator<GenerationSettings> change) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            refresh(true);
            GenerationSettings updated = change.apply(cachedSettings);
            if (bridgeAvailable && settingsBridgeAvailable) {
                JsonObject payload = settingsJson(updated);
                if (settingsInitialized) {
                    for (String key : List.of("sampler_name", "styles", "width", "height"))
                        if (!fields.contains(key)) payload.remove(key);
                }
                payload.add("expected_revision", revision.deepCopy());
                HttpResponse<String> response = request(BRIDGE, "PUT", payload, false, false);
                if (response.statusCode() == 409) continue;
                JsonObject state = responseJson(response, "修改 WebUI 生成参数");
                Prompts freshPrompts = bridgePrompts(state);
                updated = bridgeSettings(state, freshPrompts.source());
                if (updated == null) throw new IOException("WebUI 未确认生成参数更新，请更新附带扩展并重试。");
                persist(freshPrompts);
                cached = freshPrompts;
                revision = state.get("revision").deepCopy();
            }
            persist(updated);
            cachedSettings = updated;
            return updated;
        }
        throw new IOException("WebUI 生成参数正在被其他窗口修改，请稍后重试。");
    }

    private record Sampler(String name, List<String> aliases) {}

    private List<Sampler> samplerCatalog() throws Exception {
        JsonArray catalog = responseArray(request("/sdapi/v1/samplers", "GET", null, false, false), "读取采样方法列表");
        List<Sampler> samplers = new ArrayList<>();
        for (JsonElement item : catalog) {
            if (!item.isJsonObject()) throw new IOException("WebUI 采样方法列表格式无效。");
            JsonObject sampler = item.getAsJsonObject();
            String name = requireString(sampler, "name");
            if (name.isBlank()) throw new IOException("WebUI 返回了空的采样方法名称。");
            List<String> aliases = sampler.has("aliases") && !sampler.get("aliases").isJsonNull()
                    ? stringList(sampler.get("aliases"), "aliases") : List.of();
            samplers.add(new Sampler(name, aliases));
        }
        if (samplers.isEmpty()) throw new IOException("WebUI 未返回可用采样方法。");
        return List.copyOf(samplers);
    }

    public List<Path> recentImages(int count) throws IOException {
        if (count < 1) throw new IllegalArgumentException("回溯数量须为正整数。");
        if (!Files.exists(generatedRoot)) return List.of();
        if (!generatedRoot.toRealPath().equals(generatedRoot.toAbsolutePath().normalize())) throw new IOException("历史图片目录不能是符号链接。");
        record Historical(Path path, java.nio.file.attribute.FileTime time) {}
        List<Historical> images = new ArrayList<>();
        try (var paths = Files.walk(generatedRoot)) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".png") && !name.endsWith(".jpg") && !name.endsWith(".jpeg")) continue;
                images.add(new Historical(path, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)));
            }
        }
        images.sort(Comparator.comparing(Historical::time).reversed().thenComparing(item -> item.path().toString()));
        return images.stream().limit(count).map(Historical::path).toList();
    }

    public record PromptChange(Prompts prompts, List<String> changed, List<String> unchanged) {}
    /** Restore the prompt pair saved immediately before the most recent confirmed bot-side prompt mutation. */
    public synchronized Prompts undoPrompts() throws Exception {
        if(!Files.isRegularFile(promptPreviousFile)) throw new IOException("没有可回退的上一次 prompt。");
        Prompts previous;
        try {
            JsonObject saved=Json.parse(Files.readString(promptPreviousFile,StandardCharsets.UTF_8));
            if(saved.get("version").getAsInt()!=1) throw new IOException("Unknown version");
            previous=new Prompts(requireString(saved,"positive"),requireString(saved,"negative"),LOCAL_SOURCE);
        } catch(Exception e) { throw new IOException("无法读取 data/sd-prompt-previous.json；当前 prompt 未修改。",e); }
        Prompts current=prompts();
        if(current.positive().equals(previous.positive()) && current.negative().equals(previous.negative()))
            throw new IOException("当前 prompt 已与上一次记录相同，没有可回退的变化。");
        return transformPrompts(ignored -> previous);
    }

    private void rememberPrevious(Prompts before, Prompts after) throws IOException {
        if(before.positive().equals(after.positive()) && before.negative().equals(after.negative())) return;
        JsonObject saved=new JsonObject();saved.addProperty("version",1);
        saved.addProperty("positive",before.positive());saved.addProperty("negative",before.negative());
        saved.addProperty("updated_at",Instant.now().toString());Json.atomicWrite(promptPreviousFile,saved);
    }

    /** CAS both prompt fields as one change; callers may journal ownership before the PUT. */
    public synchronized Prompts transformPrompts(java.util.function.UnaryOperator<Prompts> transform) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            Prompts before = prompts();
            Prompts proposed = transform.apply(before);
            Prompts confirmed = new Prompts(proposed.positive(), proposed.negative(), LOCAL_SOURCE);
            if (bridgeAvailable) {
                JsonObject payload = new JsonObject(); payload.addProperty("positive", proposed.positive());
                payload.addProperty("negative", proposed.negative()); payload.add("expected_revision", revision.deepCopy());
                HttpResponse<String> response = request(BRIDGE, "PUT", payload, false, false);
                if (response.statusCode() == 409) continue;
                JsonObject state = responseJson(response, "修改提示词集"); confirmed = bridgePrompts(state);
                if (!confirmed.positive().equals(proposed.positive()) || !confirmed.negative().equals(proposed.negative()))
                    throw new IOException("WebUI 未确认提示词集的完整更新，请检查当前提示词。");
                GenerationSettings parameters = bridgeSettings(state, confirmed.source());
                if (parameters != null) { persist(parameters); cachedSettings = parameters; }
                revision = state.get("revision").deepCopy();
            }
            rememberPrevious(before,confirmed);
            persist(confirmed); cached = confirmed; return confirmed;
        }
        throw new IOException("WebUI 提示词正在被其他窗口修改，请稍后重试。");
    }
    public synchronized Prompts change(boolean negative, String operation, String text) throws Exception {
        return changeDetailed(negative, operation, text).prompts();
    }
    public synchronized PromptChange changeDetailed(boolean negative, String operation, String text) throws Exception {
        Objects.requireNonNull(text, "提示词不能为 null");
        if (!Set.of("add", "remove", "edit").contains(operation))
            throw new IOException("未知提示词操作：" + operation);
        if (!operation.equals("edit") && text.isBlank())
            throw new IOException("add/remove 后必须提供提示词。");
        List<String> vocabulary = new ArrayList<>();
        if (operation.equals("add")) {
            try {
                for (StylePrompt style : stylePrompts()) {
                    try { vocabulary.addAll(PromptEditor.parts(importedStyleText(style.positive()))); vocabulary.addAll(PromptEditor.parts(importedStyleText(style.negative()))); }
                    catch (IllegalArgumentException ignored) { /* An unrelated malformed style is not a correction source. */ }
                }
            } catch (IOException ignored) { /* Prompt edits remain usable when the style catalog is unavailable. */ }
            Path dictionary = root.resolve("data/prompt-tags.txt");
            if (Files.isRegularFile(dictionary)) {
                if (Files.size(dictionary) > 16L * 1024 * 1024) throw new IOException("prompt-tags.txt 超过 16MB。");
                List<String> canonical = new ArrayList<>();
                for (String line : Files.readAllLines(dictionary, StandardCharsets.UTF_8))
                    if (!line.isBlank() && !line.strip().startsWith("#")) canonical.addAll(PromptEditor.parts(line));
                vocabulary.addAll(0, canonical);
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            Prompts current = prompts();
            String before = negative ? current.negative() : current.positive();
            PromptEditor.Result result = operation.equals("edit") ? new PromptEditor.Result(text, List.of(), List.of())
                    : PromptEditor.apply(before, operation, text, vocabulary);
            String after = result.text();
            Prompts updated = new Prompts(negative ? current.positive() : after,
                    negative ? after : current.negative(), LOCAL_SOURCE);
            if (bridgeAvailable) {
                JsonObject payload = new JsonObject();
                payload.addProperty("positive", updated.positive());
                payload.addProperty("negative", updated.negative());
                payload.add("expected_revision", revision.deepCopy());
                // A server disappearing during PUT is an error, never a silently successful local edit.
                HttpResponse<String> response = request(BRIDGE, "PUT", payload, false, false);
                if (response.statusCode() == 409) continue;
                JsonObject state = responseJson(response, "修改 WebUI 提示词");
                updated = bridgePrompts(state);
                if (!(negative ? updated.negative() : updated.positive()).equals(after)
                        || !(negative ? updated.positive() : updated.negative()).equals(negative ? current.positive() : current.negative()))
                    throw new IOException("WebUI 未确认提示词更新，未报告修改成功。");
                GenerationSettings parameters = bridgeSettings(state, updated.source());
                if (parameters != null) {
                    persist(parameters);
                    cachedSettings = parameters;
                }
                revision = state.get("revision").deepCopy();
            }
            rememberPrevious(current,updated);
            persist(updated);
            cached = updated;
            return new PromptChange(updated, result.changed(), result.unchanged());
        }
        throw new IOException("WebUI 提示词正在被其他窗口修改，请稍后重试。");
    }

    /** Generates one request; the latest manifest changes only after every returned image is validated. */
    public List<Path> generate(Prompts prompts) throws Exception {
        Objects.requireNonNull(prompts);
        return generate(new GenerationRequest(prompts, settings(), parameters()));
    }

    /** Generate exactly the previously captured values; later browser/chat edits cannot alter this request. */
    public List<Path> generate(GenerationRequest generation) throws Exception { return generate(generation, null); }
    public List<Path> generate(GenerationRequest generation, String taskId) throws Exception {
        if (taskId != null && !UUID.fromString(taskId).toString().equals(taskId)) throw new IllegalArgumentException("Invalid generation task ID");
        Objects.requireNonNull(generation);
        Prompts prompts = generation.prompts();
        GenerationSettings settings = generation.settings();
        validateSize(settings.width(), settings.height());
        if (settings.samplerName().isBlank()) throw new IOException("采样方法不能为空；请先使用 .sampler set 设置。");
        GenerationParameters parameters = generation.parameters() == null ? parameters() : generation.parameters();
        // Initialize/migrate delivery state before spending time on a generation.
        outbox();
        JsonObject payload = new JsonObject();
        payload.addProperty("prompt", prompts.positive());
        payload.addProperty("negative_prompt", prompts.negative());
        payload.addProperty("steps", parameters.steps());
        payload.addProperty("width", settings.width());
        payload.addProperty("height", settings.height());
        payload.addProperty("cfg_scale", parameters.cfgScale());
        payload.addProperty("sampler_name", settings.samplerName());
        payload.add("styles", Json.GSON.toJsonTree(settings.styles()));
        payload.addProperty("seed", parameters.seed());
        if (!parameters.checkpoint().isBlank()) {
            JsonObject overrides = new JsonObject(); overrides.addProperty("sd_model_checkpoint", canonicalModel(parameters.checkpoint()));
            payload.add("override_settings", overrides);
            payload.addProperty("override_settings_restore_afterwards", true);
        }
        payload.addProperty("batch_size", 1);
        payload.addProperty("n_iter", 1);
        payload.addProperty("send_images", true);
        payload.addProperty("save_images", false);
        JsonObject result = responseJson(request("/sdapi/v1/txt2img", "POST", payload, true, false), "生成图片");
        JsonElement images = result.get("images");
        if (images == null || !images.isJsonArray() || images.getAsJsonArray().isEmpty())
            throw new IOException("Stable Diffusion 未返回图片；请检查 WebUI 模型与控制台。");
        List<byte[]> decoded = new ArrayList<>();
        List<String> extensions = new ArrayList<>();
        for (JsonElement item : images.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString())
                throw new IOException("Stable Diffusion 返回了无效的图片数据。");
            String encoded = item.getAsString();
            if (encoded.startsWith("data:")) {
                int comma = encoded.indexOf(',');
                if (comma < 0 || !encoded.substring(0, comma).endsWith(";base64"))
                    throw new IOException("Stable Diffusion 返回了无效的图片 data URL。");
                encoded = encoded.substring(comma + 1);
            }
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(encoded.replaceAll("\\s", "")); }
            catch (IllegalArgumentException e) { throw new IOException("Stable Diffusion 图片 Base64 数据损坏。", e); }
            extensions.add(validateImage(bytes));
            decoded.add(bytes);
        }
        Files.createDirectories(generatedRoot);
        Path taskRoot = taskId == null ? generatedRoot : generatedRoot.resolve("task-" + taskId);
        Files.createDirectories(taskRoot);
        Path directory = taskRoot.resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
                + "-" + UUID.randomUUID());
        Files.createDirectory(directory);
        List<Path> saved = new ArrayList<>();
        boolean queued = false;
        try {
            for (int i = 0; i < decoded.size(); i++) {
                Path path = directory.resolve(String.format(Locale.ROOT, "image-%02d.%s", i + 1, extensions.get(i)));
                saved.add(path);
                Files.write(path, decoded.get(i), StandardOpenOption.CREATE_NEW);
            }
            JsonObject manifest = new JsonObject();
            JsonArray paths = new JsonArray();
            for (Path path : saved) paths.add(root.relativize(path).toString().replace('\\', '/'));
            manifest.add("images", paths);
            manifest.addProperty("created_at", Instant.now().toString());
            manifest.addProperty("positive", prompts.positive());
            manifest.addProperty("negative", prompts.negative());
            for (Map.Entry<String, JsonElement> entry : settingsJson(settings).entrySet())
                manifest.add(entry.getKey(), entry.getValue());
            manifest.addProperty("prompt_source", prompts.source());
            manifest.addProperty("settings_source", settings.source());
            synchronized (this) {
                // The durable delivery queue is the commit point for a complete
                // batch. Once committed, no later metadata failure may delete it.
                outbox().append(saved);
                queued = true;
                try { Json.atomicWrite(latestFile, manifest); }
                catch (IOException e) {
                    Log.warn("[SD] 图片已完整保存并加入待发送队列，但最新图片兼容记录写入失败：" + e.getMessage());
                }
            }
            return List.copyOf(saved);
        } catch (Exception e) {
            if (!queued) {
                for (Path path : saved) try { Files.deleteIfExists(path); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
                try { Files.deleteIfExists(directory); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            }
            throw e;
        }
    }

    private synchronized ImageOutbox outbox() throws IOException {
        if (imageOutbox == null) imageOutbox = new ImageOutbox(root);
        return imageOutbox;
    }

    /** Every complete generation remains pending until its individual send succeeds. */
    public synchronized List<Path> pendingImages() throws IOException { return outbox().pending(); }

    public synchronized void acknowledgeImage(Path path) throws IOException { outbox().acknowledge(path); }
    public synchronized void acknowledgeImages(List<Path> paths) throws IOException { outbox().acknowledgeAll(paths); }

    /** Latest successful API generation, restored from disk after a restart. */
    public synchronized List<Path> latestImages() throws Exception {
        if (!Files.exists(latestFile)) return List.of();
        try {
            JsonObject manifest = Json.parse(Files.readString(latestFile, StandardCharsets.UTF_8));
            JsonArray images = manifest.getAsJsonArray("images");
            if (images == null || images.isEmpty()) throw new IOException("最新图片记录为空。");
            Path allowed = generatedRoot.toRealPath();
            List<Path> result = new ArrayList<>();
            for (JsonElement image : images) {
                Path path = root.resolve(image.getAsString()).normalize();
                if (!path.startsWith(generatedRoot) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !path.toRealPath().startsWith(allowed))
                    throw new IOException("最新生成图片不存在或路径无效，请重新生成。");
                result.add(path);
            }
            return List.copyOf(result);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("无法读取 data/sd-latest.json，请重新生成图片。", e); }
    }

    private Prompts initialDefaults() throws Exception {
        try {
            JsonObject startup = responseJson(request("/config", "GET", null, false, true), "读取 WebUI 默认提示词");
            String positive = componentValue(startup, "txt2img_prompt");
            String negative = componentValue(startup, "txt2img_neg_prompt");
            if (positive != null || negative != null)
                return new Prompts(positive == null ? "" : positive, negative == null ? "" : negative, DEFAULT_SOURCE);
        } catch (BridgeUnavailable ignored) { /* New local state when the WebUI is unavailable. */ }
        return new Prompts("", "", LOCAL_SOURCE);
    }

    private GenerationSettings fallbackSettings() throws Exception {
        if (cachedSettings != null) return new GenerationSettings(cachedSettings.samplerName(), cachedSettings.styles(),
                cachedSettings.width(), cachedSettings.height(), LOCAL_SOURCE);
        String sampler = Json.str(config, "sampler_name", "Euler a");
        List<String> selected = config.has("styles") ? stringList(config.get("styles"), "styles") : List.of();
        int width = Json.num(config, "width", 512), height = Json.num(config, "height", 512);
        String source = LOCAL_SOURCE;
        try {
            JsonObject startup = responseJson(request("/config", "GET", null, false, true), "读取 WebUI 默认生成参数");
            JsonElement initialSampler = componentJsonValue(startup, "txt2img_sampling");
            JsonElement initialStyles = componentJsonValue(startup, "txt2img_styles");
            JsonElement initialWidth = componentJsonValue(startup, "txt2img_width");
            JsonElement initialHeight = componentJsonValue(startup, "txt2img_height");
            boolean fromWebUi = false;
            if (initialSampler != null && initialSampler.isJsonPrimitive() && initialSampler.getAsJsonPrimitive().isString()
                    && !initialSampler.getAsString().isBlank()) {
                sampler = initialSampler.getAsString(); fromWebUi = true;
            }
            if (initialStyles != null && !initialStyles.isJsonNull()) {
                selected = stringList(initialStyles, "txt2img_styles"); fromWebUi = true;
            }
            if (initialWidth != null && !initialWidth.isJsonNull()) {
                width = requireInteger(initialWidth, "txt2img_width"); fromWebUi = true;
            }
            if (initialHeight != null && !initialHeight.isJsonNull()) {
                height = requireInteger(initialHeight, "txt2img_height"); fromWebUi = true;
            }
            if (fromWebUi) source = DEFAULT_SOURCE;
        } catch (BridgeUnavailable ignored) { /* No bridge/config: use explicit local generation defaults. */ }
        validateSize(width, height);
        if (sampler.isBlank()) throw new IOException("默认采样方法不能为空，请检查 sd.sampler_name。");
        return new GenerationSettings(sampler, selected, width, height, source);
    }

    /** Gradio may contain duplicate extension IDs; the first real txt2img component takes priority. */
    private static JsonElement componentJsonValue(JsonElement value, String id) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (id.equals(Json.str(object, "elem_id", "")))
                return object.has("value") ? object.get("value") : JsonNull.INSTANCE;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement found = componentJsonValue(entry.getValue(), id);
                if (found != null) return found;
            }
        } else if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                JsonElement found = componentJsonValue(item, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String componentValue(JsonElement value, String id) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (id.equals(Json.str(object, "elem_id", ""))) {
                JsonElement text = object.get("value");
                return text != null && text.isJsonPrimitive() && text.getAsJsonPrimitive().isString() ? text.getAsString() : "";
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String found = componentValue(entry.getValue(), id);
                if (found != null) return found;
            }
        } else if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                String found = componentValue(item, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Prompts bridgePrompts(JsonObject state) throws IOException {
        JsonElement currentRevision = state.get("revision");
        if (currentRevision == null || !currentRevision.isJsonPrimitive())
            throw new IOException("WebUI 桥接响应缺少 revision，请更新附带扩展。");
        String source = Json.str(state, "source", "");
        if (!source.equals("webui-live") && !source.equals("webui-state"))
            throw new IOException("WebUI 桥接响应来源无效，请更新附带扩展。");
        return new Prompts(requireString(state, "positive"), requireString(state, "negative"),
                source.equals("webui-live") ? "WebUI 当前页面（实时同步）" : "WebUI 桥接状态（页面未实时连接）");
    }

    private GenerationSettings bridgeSettings(JsonObject state, String source) throws IOException {
        JsonElement initialized = state.get("settings_initialized");
        if (initialized == null) {
            settingsBridgeAvailable = false;
            settingsInitialized = false;
            return null; // Old prompt-only bridges remain usable with explicitly local settings.
        }
        if (!initialized.isJsonPrimitive() || !initialized.getAsJsonPrimitive().isBoolean())
            throw new IOException("WebUI 桥接响应 settings_initialized 字段无效，请更新附带扩展。");
        settingsBridgeAvailable = true;
        settingsInitialized = initialized.getAsBoolean();
        return settingsInitialized ? readSettings(state, source) : null;
    }

    private static GenerationSettings readSettings(JsonObject state, String source) throws IOException {
        String sampler = requireString(state, "sampler_name");
        if (sampler.isBlank()) throw new IOException("WebUI 生成参数缺少有效采样方法 sampler_name。");
        List<String> selected = stringList(state.get("styles"), "styles");
        int width = requireInteger(state.get("width"), "width");
        int height = requireInteger(state.get("height"), "height");
        validateSize(width, height);
        return new GenerationSettings(sampler, selected, width, height, source);
    }

    private static List<String> stringList(JsonElement value, String name) throws IOException {
        if (value == null || !value.isJsonArray()) throw new IOException("WebUI 响应缺少有效列表字段：" + name);
        List<String> values = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString())
                throw new IOException("WebUI 响应包含无效列表项：" + name);
            values.add(item.getAsString());
        }
        return List.copyOf(values);
    }

    private static int requireInteger(JsonElement value, String name) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IOException("WebUI 响应缺少有效整数：" + name);
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw new IOException("WebUI 响应包含无效整数：" + name, e); }
    }

    private static void validateSize(int width, int height) throws IOException {
        if (width < 64 || width > 2048 || height < 64 || height > 2048 || width % 8 != 0 || height % 8 != 0)
            throw new IOException("图片宽高必须为 64–2048 之间的 8 的倍数；例如 .size set 768 512。");
    }

    private static JsonObject settingsJson(GenerationSettings settings) {
        JsonObject state = new JsonObject();
        state.addProperty("sampler_name", settings.samplerName());
        state.add("styles", Json.GSON.toJsonTree(settings.styles()));
        state.addProperty("width", settings.width());
        state.addProperty("height", settings.height());
        return state;
    }

    private void persist(GenerationSettings settings) throws IOException {
        JsonObject state = settingsJson(settings);
        state.addProperty("source", settings.source());
        state.addProperty("updated_at", Instant.now().toString());
        Json.atomicWrite(settingsFile, state);
    }

    private void persist(Prompts prompts) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty("positive", prompts.positive());
        state.addProperty("negative", prompts.negative());
        state.addProperty("source", prompts.source());
        state.addProperty("updated_at", Instant.now().toString());
        Json.atomicWrite(stateFile, state);
    }

    private HttpResponse<String> request(String path, String method, JsonObject body,
                                          boolean generation, boolean allowAbsent) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(generation ? generationTimeout : requestTimeout)
                .header("Accept", "application/json").header("X-Pixiko-Bridge", "1");
        if (authorization != null) builder.header("Authorization", authorization);
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body), StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try { response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (HttpTimeoutException e) {
            throw new IOException(generation ? "Stable Diffusion 生成请求超时；服务端可能仍在生成，请检查 WebUI 后再重试。"
                    : "读取或更新 WebUI 超时；此次操作未确认成功，请稍后重试。", e);
        } catch (ConnectException e) {
            if (allowAbsent) throw new BridgeUnavailable();
            throw new IOException("无法连接 Stable Diffusion，请检查地址及 WebUI 是否已用 --api 启动。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stable Diffusion 请求已中断，服务端操作可能仍在继续。", e);
        } catch (IOException e) {
            throw new IOException("Stable Diffusion 连接中断；操作结果未确认，请检查 WebUI。", e);
        }
        if (response.statusCode() == 404 && allowAbsent) throw new BridgeUnavailable();
        return response;
    }

    /**
     * 读取 SD WebUI 的当前生成进度。只读、不刷新缓存、不动提示词；
     * 连不上/超时/接口不存在都返回 {@code reachable=false}（"读不到"，不是"空闲"），绝不把异常抛给控制台。
     */
    public GenerationProgress progress() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/sdapi/v1/progress?skip_current_image=true"))
                    .timeout(PROGRESS_TIMEOUT)
                    .header("Accept", "application/json").header("X-Pixiko-Bridge", "1");
            if (authorization != null) builder.header("Authorization", authorization);
            HttpResponse<String> response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) return GenerationProgress.unreachable();
            return parseProgress(responseJson(response, "读取生成进度"));
        } catch (Exception error) {
            return GenerationProgress.unreachable();
        }
    }
    /**
     * 解析 /sdapi/v1/progress 的返回。判断"在跑"必须看 state.job 与 job_count：
     * 空闲时 sampling_steps 仍会保留上一次的步数（实测 0/20），只看它会把空闲误判成生成中。
     */
    public static GenerationProgress parseProgress(JsonObject body) {
        JsonObject state = Json.obj(body, "state");
        String job = Json.str(state, "job", "").strip();
        int jobCount = state.has("job_count") && !state.get("job_count").isJsonNull() ? state.get("job_count").getAsInt() : 0;
        int step = state.has("sampling_step") && !state.get("sampling_step").isJsonNull() ? state.get("sampling_step").getAsInt() : 0;
        int steps = state.has("sampling_steps") && !state.get("sampling_steps").isJsonNull() ? state.get("sampling_steps").getAsInt() : 0;
        double progress = body.has("progress") && !body.get("progress").isJsonNull() ? body.get("progress").getAsDouble() : 0;
        double eta = body.has("eta_relative") && !body.get("eta_relative").isJsonNull() ? body.get("eta_relative").getAsDouble() : 0;
        boolean running = jobCount > 0 && !job.isBlank();
        if (!running) return GenerationProgress.idle();
        double percent = Double.isFinite(progress) ? progress : 0;
        if (steps > 0 && step > 0) percent = Math.max(percent, (double) step / steps);
        percent = Math.max(0, Math.min(1, percent));
        if (!Double.isFinite(eta) || eta < 0) eta = 0;
        return new GenerationProgress(true, true, job, percent, Math.max(0, step), Math.max(0, steps), eta);
    }

    /**
     * 快速探活：SD 的 HTTP 接口能不能连上（自启动据此判断"要不要拉起来"）。
     * 只打 /internal/ping，超时 3 秒，任何异常都当作"连不上"。
     */
    public boolean reachable() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/ping"))
                    .timeout(Duration.ofSeconds(3)).header("Accept", "text/plain");
            if (authorization != null) builder.header("Authorization", authorization);
            HttpResponse<String> response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception error) {
            return false;
        }
    }

    private static JsonObject responseJson(HttpResponse<String> response, String action) throws IOException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            if (status == 401) throw new IOException("WebUI 身份验证失败（HTTP 401），请检查 sd.api_username/api_password。");
            if (status == 403) throw new IOException("WebUI 拒绝访问（HTTP 403），请检查 API 权限和桥接扩展配置。");
            if (status == 404) throw new IOException("WebUI API 不存在（HTTP 404），请用 --api 启动 WebUI 并检查桥接扩展。");
            String detail = "";
            try {
                JsonObject error = Json.parse(response.body());
                JsonElement message = error.has("detail") ? error.get("detail") : error.get("error");
                if (message != null && message.isJsonPrimitive()) detail = message.getAsString().replaceAll("[\\r\\n]+", " ");
                if (detail.length() > 180) detail = detail.substring(0, 180) + "…";
            } catch (Exception ignored) { }
            throw new IOException(action + "失败（HTTP " + status + "）" + (detail.isBlank() ? "。" : "：" + detail));
        }
        try { return Json.parse(response.body()); }
        catch (Exception e) { throw new IOException(action + "失败：WebUI 返回的内容不是有效 JSON，请确认服务地址和 API。", e); }
    }

    private static JsonArray responseArray(HttpResponse<String> response, String action) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) responseJson(response, action);
        try { return JsonParser.parseString(response.body()).getAsJsonArray(); }
        catch (Exception e) { throw new IOException(action + "失败：WebUI 返回的内容不是有效 JSON 列表。", e); }
    }

    private static String requireString(JsonObject value, String name) throws IOException {
        JsonElement text = value.get(name);
        if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString())
            throw new IOException("提示词响应缺少有效字段：" + name);
        return text.getAsString();
    }

    private double decimal(String key, double fallback) {
        JsonElement value = config.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsDouble();
    }

    private long longNumber(String key, long fallback) {
        JsonElement value = config.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsLong();
    }

    private static String validateImage(byte[] bytes) throws IOException {
        String extension;
        if (bytes.length > 8 && (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) extension = "png";
        else if (bytes.length > 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) extension = "jpg";
        else throw new IOException("Stable Diffusion 返回的图片不是有效 PNG/JPEG。");
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            var image = ImageIO.read(input);
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1)
                throw new IOException("图片解码失败。");
        } catch (IOException | RuntimeException e) { throw new IOException("Stable Diffusion 返回的图片无法解码。", e); }
        return extension;
    }

    private static final class BridgeUnavailable extends IOException {}
}

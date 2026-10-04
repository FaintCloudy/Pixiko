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
    /** 底模的来源标记（与网页/回执里的 baseModelSource 字段同一个词表）。 */
    public static final String CIVITAI_SOURCE = StackClassifier.CIVITAI_SOURCE,
            FORGE_SOURCE = StackClassifier.FORGE_SOURCE, PRESET_SOURCE = "preset-inferred";
    /**
     * 一个 LoRA（或一个底模）的归属：底模名 + 它属于 Forge 的哪一栈。
     *
     * <p>底模 {@code name} 的来源 {@code source}：{@code safetensors-header}（文件头部 {@code __metadata__}
     * 声明的架构/底模，最可靠）→ {@code civitai}（Civitai 下载记录）→ {@code forge-metadata}
     * （Forge 的 LoRA 元数据）→ {@code safetensors-keys}（头部张量名结构）→ {@code preset-inferred}
     * （都没有，按当前 Forge 预设栈**推断**）。推断出来的必须标明：它是"当前这台机器在跑什么栈"，
     * 不是这个 LoRA 自己报的底模，更不许编一个名字出来。
     *
     * <p>{@code stack} 是 Forge 的预设栈（{@code anima}/{@code xl}/{@code sd}/{@code flux}/{@code qwen}…），
     * {@code stackSource} 是栈的判定来源（推断出来的记成 {@code inferred}）；{@code evidence} 是判据原文
     * （哪个键、什么值），回执与 {@code /lora detail} 直接给用户看，**不许编造**。
     */
    public record BaseModel(String name, String source, String stack, String stackSource, String evidence) {
        public static final BaseModel NONE = new BaseModel("", "", "", "", "");
        /** 老写法（只有底模名与来源）：栈留空，调用方用 {@link #withStack} 补。 */
        public BaseModel(String name, String source) { this(name, source, "", "", ""); }
        public BaseModel {
            name = name == null ? "" : name.strip();
            source = source == null ? "" : source.strip();
            stack = stack == null ? "" : stack.strip();
            stackSource = stackSource == null ? "" : stackSource.strip();
            evidence = evidence == null ? "" : evidence.strip();
        }
        /** 有没有真的识别（或推断）出底模。 */
        public boolean known() { return !name.isEmpty(); }
        /** 底模的分组键：同一个底模的不同写法算一组（未识别为空串，排最后）。 */
        public String groupKey() { return name.toLowerCase(java.util.Locale.ROOT); }
        /** 判出栈了吗（判出来才谈得上"属于哪一栈"）。 */
        public boolean stackKnown() { return !stack.isEmpty(); }
        /** 栈的中文说法（如 {@code SDXL 栈}）。 */
        public String stackLabel() { return StackClassifier.stackLabel(stack); }
        /** 栈来源的中文说法。 */
        public String stackSourceLabel() { return StackClassifier.sourceLabel(stackSource); }
        /** 回执/网页上的来源标注；推断出来的要显式写出来。 */
        public String sourceLabel() { return StackClassifier.sourceLabel(source); }
        /** 拼在底模名后面的那句"这是推断的"（识别出来的不加）。 */
        public String note() { return source.equals(PRESET_SOURCE) ? "（按当前预设推断）" : ""; }
        /** 补上栈的判定（底模名与来源不变）。 */
        public BaseModel withStack(String value, String valueSource, String why) {
            return new BaseModel(name, source, value, valueSource, why);
        }
        /** 栈判不出来时，把判不出来的原因也写进证据（用户问"为什么没判出来"要答得上）。 */
        public String stackNote() {
            if (stackKnown()) return "";
            return evidence.isBlank() ? "" : "（" + evidence + "）";
        }
    }
    /**
     * 一个基础模型的归属（网页「基础模型」下拉与 {@code .model list} 用）：
     * {@code title} 是 WebUI 的完整标题（带哈希），{@code name} 是去哈希的文件名。
     */
    public record ModelInfo(String title, String name, String path, String baseModel, String baseModelSource,
                            String stack, String stackSource, String preset, String evidence) {
        public boolean stackKnown() { return stack != null && !stack.isEmpty(); }
        public String stackLabel() { return StackClassifier.stackLabel(stack); }
        /** 下拉框里显示的一行：{@code waiIllustriousSDXL_v170.safetensors [SDXL 栈]}。 */
        public String label() {
            if (!stackKnown()) return name;
            return name + " [" + stackLabel() + (preset == null || preset.isEmpty() || preset.equalsIgnoreCase(stack) ? "" : " · 预设 " + preset) + "]";
        }
    }
    /**
     * 一次生成用的"界面侧"设置。
     *
     * @param scheduler    调度器：空串＝不发送，用 WebUI 当前值（Forge Neo 的 anima/flux 系列要它，
     *                     例如 Anima 配 {@code beta}／{@code simple}／{@code normal}）
     * @param distilledCfg 蒸馏 CFG（Forge 的 "Distilled CFG"／Shift，Anima 推荐 3）；0＝不发送
     */
    public record GenerationSettings(String samplerName, String scheduler, List<String> styles, int width, int height,
                                     double distilledCfg, String source, String vae) {
        public GenerationSettings {
            Objects.requireNonNull(samplerName);
            scheduler = scheduler == null ? "" : scheduler.strip();
            styles = List.copyOf(styles);
            if (!Double.isFinite(distilledCfg) || distilledCfg < 0) distilledCfg = 0;
            Objects.requireNonNull(source);
            // VAE 是这一份设置里唯一"存在 WebUI 侧"的项（sd_vae 是全局选项，不随请求发），
            // 空串＝没设过（老 sd-settings.json 没有这个字段时就是这样）。
            vae = vae == null ? "" : vae.strip();
        }
        /** 老写法：不带调度器与蒸馏 CFG。 */
        public GenerationSettings(String samplerName, List<String> styles, int width, int height, String source) {
            this(samplerName, "", styles, width, height, 0, source, "");
        }
        /** 不带 VAE 的七参写法（保留，内部调用与老测试都用它）。 */
        public GenerationSettings(String samplerName, String scheduler, List<String> styles, int width, int height,
                                  double distilledCfg, String source) {
            this(samplerName, scheduler, styles, width, height, distilledCfg, source, "");
        }
        /** 带 VAE 的简易写法（web/指令侧要一起给出 VAE 时用）。 */
        public GenerationSettings(String samplerName, List<String> styles, int width, int height, String source, String vae) {
            this(samplerName, "", styles, width, height, 0, source, vae);
        }
        public GenerationSettings withSampler(String value) { return new GenerationSettings(value, scheduler, styles, width, height, distilledCfg, source, vae); }
        public GenerationSettings withStyles(List<String> value) { return new GenerationSettings(samplerName, scheduler, value, width, height, distilledCfg, source, vae); }
        public GenerationSettings withSize(int w, int h) { return new GenerationSettings(samplerName, scheduler, styles, w, h, distilledCfg, source, vae); }
        public GenerationSettings withForge(String scheduler, double distilled) { return new GenerationSettings(samplerName, scheduler, styles, width, height, distilled, source, vae); }
        public GenerationSettings withSource(String value) { return new GenerationSettings(samplerName, scheduler, styles, width, height, distilledCfg, value, vae); }
        /** 换 VAE 记录（只改这一项；写 WebUI 由 {@link SdClient#setVae(String)} 负责）。 */
        public GenerationSettings withVae(String value) { return new GenerationSettings(samplerName, scheduler, styles, width, height, distilledCfg, source, value); }
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
    /**
     * Forge 的调度器与蒸馏 CFG（界面上的 Shift）。
     *
     * <p>为什么要单独存一份：WebUI 的桥接扩展不认识这两个字段，每次 {@code refresh()} 从桥接重建
     * {@code cachedSettings} 都会把它们冲掉。所以以这里的值为准，读设置与落盘时再合并回去。
     */
    private volatile String activeScheduler = "";
    private volatile double activeDistilledCfg;
    /**
     * 机器人自己记着的 VAE（{@code sd_vae}）。
     *
     * <p>为什么单独存一份、不放进 {@code cachedSettings}：{@code sd_vae} 是**WebUI 的全局选项**，
     * 桥接扩展不认这个字段，每次 {@code refresh()} 从桥接重建 {@code cachedSettings} 都会把它冲掉
     * （与调度器/蒸馏 CFG 完全同一个理由）。所以以这里的值为准，落盘时再合并回去。
     */
    private volatile String activeVae = "";
    private JsonElement revision;
    private boolean bridgeAvailable;
    private boolean settingsBridgeAvailable, settingsInitialized;
    private ImageOutbox imageOutbox;
    private GenerationParameters generationParameters;
    /** 上一次读 LoRA 列表时，Forge 元数据里各 LoRA 自报的底模（键是小写名字/别名/文件名）。 */
    private volatile Map<String, String> forgeLoraBases = Map.of();

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
                activeScheduler = cachedSettings.scheduler();
                activeDistilledCfg = cachedSettings.distilledCfg();
                // 老 sd-settings.json 没有 vae 字段：读回来是空串（＝没设过），完全照旧。
                activeVae = cachedSettings.vae();
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
        return cachedSettings.withForge(activeScheduler, activeDistilledCfg).withVae(activeVae);
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
            model = Json.str(options(), "sd_model_checkpoint", "");
        } catch (BridgeUnavailable ignored) { /* Older/offline API: explicitly report that no model was captured. */ }
        return new GenerationParameters(current.steps(), current.cfgScale(), current.seed(), model);
    }

    /** "跟随 WebUI 当前模型"的写法。 */
    public static boolean isAutoModel(String value) {
        String text = value == null ? "" : value.strip();
        return text.isEmpty() || text.equalsIgnoreCase("auto") || text.equals("跟随") || text.equals("自动");
    }

    public List<String> models() throws Exception {
        JsonArray catalog = responseArray(request("/sdapi/v1/sd-models", "GET", null, false, false), "读取基础模型列表");
        List<String> names = new ArrayList<>();
        for (JsonElement item : catalog) names.add(requireString(item.getAsJsonObject(), "title"));
        return List.copyOf(names);
    }

    // ---------------------------------------------------------------- Forge / Forge Neo

    /** 读一次 WebUI 选项（Forge 的预设、模块、当前模型都在这里）。 */
    private JsonObject options() throws Exception {
        return responseJson(request("/sdapi/v1/options", "GET", null, false, true), "读取 WebUI 选项");
    }

    /**
     * 当前 WebUI 是不是 Forge / Forge Neo。
     *
     * <p>判据有三条，取或：options 里有 {@code forge_preset}；**WebUI 目录下的 config.json 里有
     * {@code forge_preset}**；目录名里带 forge。第三条是必需的：这台 Forge Neo 的
     * {@code /sdapi/v1/options} 返回的是一份白名单，{@code forge_preset} / {@code forge_checkpoint_*} /
     * {@code <preset>_t2i_*} 这些键**根本不在响应里**（POST 它们还会 500 KeyError），
     * 但预设确实存在——就存在 Forge 自己的 {@code config.json} 里。所以预设一律以那份文件为准。
     */
    public synchronized boolean forge() {
        try { if (options().has("forge_preset")) return true; } catch (Exception ignored) { /* 读不到就看文件 */ }
        JsonObject saved = forgeConfig();
        if (saved.has("forge_preset")) return true;
        String directory = sdRoot();
        return !directory.isBlank() && directory.toLowerCase(java.util.Locale.ROOT).contains("forge");
    }

    /** Forge 自己的 config.json（在 {@code sd.root} 下）；读不到就返回空对象。 */
    private JsonObject forgeConfig() {
        String directory = sdRoot();
        if (directory.isBlank()) return new JsonObject();
        try {
            Path file = Path.of(directory).resolve("config.json");
            if (!Files.isRegularFile(file)) return new JsonObject();
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception error) { return new JsonObject(); }
    }

    /** 配置里的 SD 安装目录（可以为空：不装 Forge 也能只用 API）。 */
    private String sdRoot() { return Json.str(config, "root", "").strip(); }

    /** Forge 的预设列表：options 与 config.json 两处合起来，按名字排序。 */
    public synchronized List<String> forgePresets() throws Exception {
        java.util.TreeSet<String> presets = new java.util.TreeSet<>();
        try {
            for (String key : options().keySet())
                if (key.startsWith("forge_checkpoint_")) presets.add(key.substring("forge_checkpoint_".length()));
        } catch (Exception ignored) { /* 这台 Forge Neo 的 options 里没有，走 config.json */ }
        JsonObject saved = forgeConfig();
        for (String key : saved.keySet()) {
            if (key.startsWith("forge_checkpoint_")) presets.add(key.substring("forge_checkpoint_".length()));
            else if (key.endsWith("_t2i_sampler")) presets.add(key.substring(0, key.length() - "_t2i_sampler".length()));
        }
        return List.copyOf(presets);
    }

    /** 当前预设（不是 Forge 就是空串）。 */
    public synchronized String forgePreset() throws Exception {
        try {
            String active = Json.str(options(), "forge_preset", "");
            if (!active.isBlank()) return active;
        } catch (Exception ignored) { /* 同上 */ }
        return Json.str(forgeConfig(), "forge_preset", "");
    }

    /**
     * 读某个预设自己的推荐参数。Forge Neo 给每个预设都存了一套 {@code <preset>_t2i_*}：
     * Anima 是「ER SDE + beta + 32 步 + CFG 4 + Shift 3 + 它自己的尺寸」，Flux/Qwen 同理。
     * 先看 options（新一点的构建会把它们暴露出来），没有就用 Forge 的 config.json。
     */
    public synchronized JsonObject forgePresetDefaults(String preset) throws Exception {
        JsonObject options;
        try { options = options(); } catch (Exception error) { options = new JsonObject(); }
        JsonObject saved = forgeConfig();
        JsonObject result = new JsonObject();
        result.addProperty("preset", preset);
        result.addProperty("checkpoint", firstNonBlank(Json.str(options, "forge_checkpoint_" + preset, ""),
                Json.str(saved, "forge_checkpoint_" + preset, "")));
        JsonElement modules = options.has("forge_additional_modules_" + preset) ? options.get("forge_additional_modules_" + preset)
                : saved.get("forge_additional_modules_" + preset);
        result.add("modules", modules != null && modules.isJsonArray() ? modules.deepCopy() : new JsonArray());
        result.addProperty("sampler", firstNonBlank(Json.str(options, preset + "_t2i_sampler", ""), Json.str(saved, preset + "_t2i_sampler", "")));
        result.addProperty("scheduler", firstNonBlank(Json.str(options, preset + "_t2i_scheduler", ""), Json.str(saved, preset + "_t2i_scheduler", "")));
        result.addProperty("steps", pickNumber(options, saved, preset + "_t2i_step"));
        result.addProperty("cfg", pickDecimal(options, saved, preset + "_t2i_cfg"));
        result.addProperty("distilledCfg", pickDecimal(options, saved, preset + "_t2i_dcfg"));
        result.addProperty("width", pickNumber(options, saved, preset + "_t2i_width"));
        result.addProperty("height", pickNumber(options, saved, preset + "_t2i_height"));
        result.addProperty("active", preset.equalsIgnoreCase(forgePreset()));
        return result;
    }

    private static String firstNonBlank(String preferred, String fallback) { return preferred.isBlank() ? fallback : preferred; }

    /**
     * 每个 Forge 预设自己的检查点（preset → 文件）。这就是「归属」的权威依据：哪个文件是哪一栈的，
     * Forge 自己写在 {@code forge_checkpoint_<preset>} 里；options 与 Forge 的 config.json 合成一份。
     */
    public synchronized Map<String, String> forgePresetCheckpoints() {
        JsonObject options;
        try { options = options(); } catch (Exception error) { options = new JsonObject(); }
        JsonObject saved = forgeConfig();
        List<String> presets = new ArrayList<>();
        try { presets.addAll(forgePresets()); } catch (Exception ignored) { /* 读不到预设列表就看配置里的键 */ }
        for (String key : saved.keySet())
            if (key.startsWith("forge_checkpoint_")) presets.add(key.substring("forge_checkpoint_".length()));
        Map<String, String> result = new LinkedHashMap<>();
        for (String preset : presets) {
            if (preset == null || preset.isBlank() || result.containsKey(preset)) continue;
            String value = firstNonBlank(Json.str(options, "forge_checkpoint_" + preset, ""),
                    Json.str(saved, "forge_checkpoint_" + preset, "")).strip();
            if (!value.isEmpty()) result.put(preset, value);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 基础模型列表 + 每个模型属于哪一栈（网页「基础模型」下拉、{@code .model list} 与换底模防呆共用）。
     *
     * <p>判定顺序：文件头部声明的底模 → 头部张量名结构 → Forge 预设配置（哪个预设置的就是它，最直接的
     * "归属"）→ 文件名关键词（标成推断）。每一步都把判据（哪个键、什么值）带出来，绝不编造。
     */
    public synchronized List<ModelInfo> modelInfos() throws Exception {
        JsonArray catalog = responseArray(request("/sdapi/v1/sd-models", "GET", null, false, false), "读取基础模型列表");
        List<String> presets = List.of();
        Map<String, String> owners = Map.of();
        if (forge()) {
            try { presets = forgePresets(); } catch (Exception ignored) { /* 读不到就当没有预设 */ }
            owners = forgePresetCheckpoints();
        }
        List<ModelInfo> result = new ArrayList<>();
        for (JsonElement element : catalog) {
            JsonObject item = element.getAsJsonObject();
            String title = requireString(item, "title");
            result.add(modelInfo(title, Json.str(item, "filename", ""), presets, owners));
        }
        return List.copyOf(result);
    }

    private static ModelInfo modelInfo(String title, String path, List<String> presets, Map<String, String> owners) {
        String bare = StackClassifier.bareName(title);
        String name = bare.isEmpty() ? StackClassifier.bareName(path) : bare;
        Path file = readableFile(path);
        String owner = ownerPreset(owners, name, path);
        StackClassifier.Header declared = StackClassifier.declared(file);
        if (declared.known())
            return attributed(title, name, path, declared.baseModel(), declared.source(), declared.evidence(), owner, presets, owners);
        StackClassifier.Header structure = StackClassifier.structural(file);
        if (structure.known())
            return attributed(title, name, path, structure.baseModel(), structure.source(), structure.evidence(), owner, presets, owners);
        if (!owner.isEmpty()) {
            String stack = StackClassifier.stackOfPreset(owner);
            String inferred = StackClassifier.baseModelOfStack(stack);   // 只有栈、没有底模名：补规范底模名
            return new ModelInfo(title, name, path, inferred, inferred.isEmpty() ? "" : StackClassifier.INFERRED_SOURCE,
                    stack, StackClassifier.PRESET_SOURCE, owner,
                    "Forge 预设配置 forge_checkpoint_" + owner + "=" + owners.get(owner)
                            + (file == null ? "（模型文件读不到，只按配置判）" : "")
                            + (inferred.isEmpty() ? "" : "；" + StackClassifier.stackBaseModelEvidence(stack)));
        }
        String stack = StackClassifier.stackOf("", name);
        if (!stack.isEmpty()) {
            String inferred = StackClassifier.baseModelOfStack(stack);
            return new ModelInfo(title, name, path, inferred, inferred.isEmpty() ? "" : StackClassifier.INFERRED_SOURCE,
                    stack, StackClassifier.INFERRED_SOURCE, StackClassifier.presetFor(stack, presets),
                    "文件名关键词（推断，不是文件自述）"
                            + (inferred.isEmpty() ? "" : "；" + StackClassifier.stackBaseModelEvidence(stack)));
        }
        return new ModelInfo(title, name, path, "", "", "", "", "",
                file == null ? "读不到模型文件（路径不在本机），也没有可用的文件名线索" : "头部元数据、张量结构与文件名都没有可用的判定依据");
    }

    /**
     * 识别出来的底模 + 由它判出的栈；如果 Forge 预设配置也认这个文件，栈就以配置为准（那是"归属"本身）。
     * 底模名认不出**已知族**时（栈是按名字自成一族的 slug），先用文件名关键词、再退 Forge 预设配置，
     * 并且必须把来源标成推断——不许拿"底模有实据"去给一个猜出来的栈背书。
     *
     * <p>最后一步：底模名确实为空（占位值/没记底模），但栈已经判出来时，补该栈的规范底模名
     * （{@link StackClassifier#baseModelOfStack(String)}，来源标 {@code inferred}），别让这条记录
     * 在"按底模分组"里哪一组都不进。
     */
    private static ModelInfo attributed(String title, String name, String path, String baseModel, String source,
                                        String evidence, String owner, List<String> presets, Map<String, String> owners) {
        String canonical = canonicalBaseModel(baseModel);
        String stack = StackClassifier.stackOfBaseModel(canonical);
        String stackSource = stack.isEmpty() ? "" : (owner.isEmpty() ? source : StackClassifier.PRESET_SOURCE);
        if (!StackClassifier.knownStack(stack)) {
            String byName = StackClassifier.stackOf("", name);
            if (byName.isEmpty()) byName = StackClassifier.stackOf("", StackClassifier.bareName(path));
            if (!byName.isEmpty()) stack = byName;
            else if (!owner.isEmpty()) stack = StackClassifier.stackOfPreset(owner);
            stackSource = stack.isEmpty() ? "" : StackClassifier.INFERRED_SOURCE;
        }
        if (canonical.isEmpty()) {
            String inferred = StackClassifier.baseModelOfStack(stack);
            if (!inferred.isEmpty()) {
                canonical = inferred;
                source = StackClassifier.INFERRED_SOURCE;
                evidence = StackClassifier.stackBaseModelEvidence(stack);
            }
        }
        String preset = owner.isEmpty() ? StackClassifier.presetFor(stack, presets) : owner;
        String why = evidence + (owner.isEmpty() ? "" : "；Forge 预设配置把它当 " + owner + " 栈的检查点（forge_checkpoint_" + owner + "=" + owners.get(owner) + "）");
        return new ModelInfo(title, name, path, canonical, source, stack, stackSource, preset, why);
    }

    /** Forge 预设配置里哪个预设的检查点就是这个文件。 */
    private static String ownerPreset(Map<String, String> owners, String name, String path) {
        for (Map.Entry<String, String> entry : owners.entrySet()) {
            if (StackClassifier.sameModel(entry.getValue(), name) || StackClassifier.sameModel(entry.getValue(), path)) return entry.getKey();
        }
        return "";
    }

    /**
     * 找一个底模（用户填的名字/标题/路径）属于哪一栈。SD 列表里找不到就退回 Forge 预设配置与文件名关键词；
     * 一条实据都没有时返回 null——调用方保留原来的通用警告，**不硬猜**。
     */
    public synchronized ModelInfo checkpointInfo(String checkpoint) throws Exception {
        String requested = checkpoint == null ? "" : checkpoint.strip();
        String wanted = StackClassifier.bareName(requested);
        if (wanted.isEmpty()) return null;
        List<ModelInfo> models;
        try { models = modelInfos(); } catch (Exception error) { models = List.of(); }
        for (ModelInfo info : models) {
            if (info.name().equalsIgnoreCase(wanted) || info.title().equalsIgnoreCase(requested)
                    || StackClassifier.sameModel(info.name(), wanted) || StackClassifier.sameModel(info.path(), requested)) return info;
        }
        String owner = ownerPreset(forgePresetCheckpoints(), wanted, requested);
        if (!owner.isEmpty()) {
            String stack = StackClassifier.stackOfPreset(owner);
            String inferred = StackClassifier.baseModelOfStack(stack);
            return new ModelInfo(requested, wanted, "", inferred, inferred.isEmpty() ? "" : StackClassifier.INFERRED_SOURCE,
                    stack, StackClassifier.PRESET_SOURCE, owner,
                    "Forge 预设配置 forge_checkpoint_" + owner
                            + (inferred.isEmpty() ? "" : "；" + StackClassifier.stackBaseModelEvidence(stack)));
        }
        String stack = StackClassifier.stackOf("", wanted);
        if (stack.isEmpty()) return null;
        String inferred = StackClassifier.baseModelOfStack(stack);
        return new ModelInfo(requested, wanted, "", inferred, inferred.isEmpty() ? "" : StackClassifier.INFERRED_SOURCE,
                stack, StackClassifier.INFERRED_SOURCE, StackClassifier.presetFor(stack, forgePresets()),
                "文件名关键词（推断）" + (inferred.isEmpty() ? "" : "；" + StackClassifier.stackBaseModelEvidence(stack)));
    }

    /**
     * 切完之后回读一次：当前加载的底模是不是这个预设指定的那一个。
     * 预设没指定底模时返回 true（只认 forge_preset 的结果）；读不到就当作没换成功，让调用方走显式换底模那条路。
     */
    private boolean checkpointApplied(JsonObject defaults) {
        String wanted = Json.str(defaults, "checkpoint", "");
        if (wanted.isBlank()) return true;
        try {
            String current = Json.str(options(), "sd_model_checkpoint", "");
            if (current.isBlank()) return false;
            String base = wanted.replaceAll("(?i)\\.safetensors$", "").strip();
            return current.equalsIgnoreCase(wanted) || current.startsWith(wanted) || current.startsWith(base)
                    || current.toLowerCase(java.util.Locale.ROOT).startsWith(base.toLowerCase(java.util.Locale.ROOT));
        } catch (Exception error) { return false; }
    }

    private static long pickNumber(JsonObject options, JsonObject saved, String key) {
        if (options.has(key)) return Json.num(options, key, 0);
        return Json.num(saved, key, 0);
    }

    private static double pickDecimal(JsonObject options, JsonObject saved, String key) {
        if (options.has(key)) return Json.decimal(options, key, 0);
        return Json.decimal(saved, key, 0);
    }

    /**
     * 切 Forge 预设。
     *
     * <p>两条路：新构建支持 {@code POST /sdapi/v1/options {"forge_preset": …}}；有的构建不支持
     * （500 KeyError），于是走它 UI 内部同一条路——把这一栈的**检查点与额外模块**直接换掉
     * （{@code sd_model_checkpoint} 与 {@code forge_additional_modules} 都经实测可用），
     * 再加上把该栈的推荐参数采纳成机器人设置。效果与点 UI 里的预设一致：栈换过去了、参数也跟着换。
     *
     * <p><b>额外模块必须跟着预设走（2026-10 灰图事故的根因）</b>：原来只在预设自带**非空**模块时才写
     * {@code forge_additional_modules}，于是 {@code xl → anima → xl} 这一来一回把 anima 的
     * {@code qwen_image_vae} / {@code qwen_3_06b_base} 留在 SDXL 检查点上，出图全灰。现在：
     * <ul>
     *   <li>切预设时**一定**写一次 {@code forge_additional_modules}，空数组也写——那正是"清掉上一个栈的残留"；</li>
     *   <li>写完回读校验（按文件名比对），没确认就在 {@code applied} 里如实写"未确认"；</li>
     *   <li>返回值里带上 {@code modules}（这一栈应有的）与 {@code conflict}（{@link VaeGuard} 对切换结果的判定），
     *       调用方据此提示用户"这个预设自己就配错了"。</li>
     * </ul>
     *
     * @return 这一栈的推荐参数（含 {@code applied}／{@code modulesWritten}／{@code conflict} 字段说明实际做了什么）
     */
    public synchronized JsonObject setForgePreset(String requested) throws Exception {
        List<String> presets = forgePresets();
        if (presets.isEmpty()) throw new IOException("当前 WebUI 不是 Forge／Forge Neo（没有 forge_preset 这套预设）。");
        String wanted = requested == null ? "" : requested.strip();
        String preset = presets.stream().filter(name -> name.equalsIgnoreCase(wanted)).findFirst()
                .orElseThrow(() -> new IOException("未知预设：" + wanted + "；可用：" + String.join("、", presets) + "。"));
        JsonObject defaults = forgePresetDefaults(preset);
        List<String> modules = stringValues(defaults.get("modules"));
        List<String> applied = new ArrayList<>();
        boolean switched = false, presetAccepted = false, modulesWritten = false;
        // ① 预设 + 该预设的额外模块**一起**写：一次请求就把"栈"换干净（也顺带清掉上一个栈的残留）。
        JsonObject direct = new JsonObject();
        direct.addProperty("forge_preset", preset);
        direct.add("forge_additional_modules", modulesJson(modules));
        try {
            HttpResponse<String> response = request("/sdapi/v1/options", "POST", direct, false, false);
            if (ok(response.statusCode())) {
                presetAccepted = true;
                // 有的构建会**接受** forge_preset（HTTP 200）却要等下一次加载才真正换栈，
                // 所以不能只看状态码：切完必须回读当前底模，确认这一栈真的上去了。
                switched = checkpointApplied(defaults);
                if (switched) applied.add("forge_preset=" + preset);
                modulesWritten = modulesConfirmed(modules);
                if (modulesWritten) applied.add(modulesAppliedText(modules));
            }
        } catch (Exception ignored) { /* 这一版不接受 forge_preset（或模块字段），走下一条路 */ }
        // ② 只发 forge_preset 再试一次：有的构建认预设但不认 "模块" 这个字段，别让模块把预设一起带崩。
        if (!switched) {
            JsonObject solo = new JsonObject();
            solo.addProperty("forge_preset", preset);
            try {
                HttpResponse<String> response = request("/sdapi/v1/options", "POST", solo, false, false);
                if (ok(response.statusCode())) {
                    presetAccepted = true;
                    switched = checkpointApplied(defaults);
                    if (switched) applied.add("forge_preset=" + preset);
                }
            } catch (Exception ignored) { /* 依然不接受：走检查点那条路 */ }
        }
        // ③ 检查点路线（这台 Forge Neo 上验证过可用）：显式写 sd_model_checkpoint。
        if (!switched) {
            String checkpoint = Json.str(defaults, "checkpoint", "");
            if (checkpoint.isBlank())
                throw new IOException("预设「" + preset + "」里没有配置检查点；在 Forge 页面里给这一栈选一个底模再试。");
            JsonObject payload = new JsonObject();
            payload.addProperty("sd_model_checkpoint", checkpoint);
            HttpResponse<String> response = request("/sdapi/v1/options", "POST", payload, false, false);
            if (!ok(response.statusCode()))
                throw new IOException("切换底模失败：HTTP " + response.statusCode() + "。" + response.body());
            applied.add("底模=" + checkpoint);
        }
        // ④ 额外模块：无论走哪条路都要落到"这一栈自己的清单"，空清单也要写（清残留）。
        if (!modulesWritten) {
            JsonObject payload = new JsonObject();
            payload.add("forge_additional_modules", modulesJson(modules));
            // 这个构建认 forge_preset 才把它带上（有的构建对未知键 500，带上会把模块写入一起弄失败）。
            if (presetAccepted) payload.addProperty("forge_preset", preset);
            try {
                HttpResponse<String> response = request("/sdapi/v1/options", "POST", payload, false, false);
                modulesWritten = ok(response.statusCode()) && modulesConfirmed(modules);
                applied.add(modulesAppliedText(modules) + (modulesWritten ? "" : "（WebUI 未确认）"));
            } catch (Exception error) {
                applied.add(modulesAppliedText(modules) + "写入失败：" + Bot.error(error));
            }
        }
        JsonObject result = forgePresetDefaults(preset);
        // 切完立刻判一次：这个预设自带的东西和它自己的检查点是不是同一栈。**照配置如实写**（不偷偷过滤），
        // 但必须把"这个预设自己就配错了"讲出来——例如这台机器的 forge_additional_modules_sd 里存着
        // qwen_image_vae + qwen_3_06b_base，那切到 sd 预设照样会出灰图，得让调用方看得见。
        // VAE 不受切预设影响，用机器人自己记的那一份。
        VaeGuard.Conflict conflict = VaeGuard.check(new VaeGuard.Facts(
                Json.str(result, "checkpoint", ""), activeVae, modules));
        if (conflict.blocked())
            applied.add("⚠ 预设「" + preset + "」自带的额外模块与它的检查点不是一族：" + conflict.reason()
                    + "（切过去会出灰图，请用 .vae check／在 Forge 页面里改这一栈的 VAE 与文本编码器）");
        JsonArray done = new JsonArray();
        for (String item : applied) done.add(item);
        result.add("applied", done);
        result.addProperty("switched", true);
        result.addProperty("modulesWritten", modulesWritten);
        JsonObject conflictJson = new JsonObject();
        conflictJson.addProperty("level", conflict.level());
        conflictJson.addProperty("reason", conflict.reason());
        conflictJson.addProperty("suggestion", conflict.suggestion());
        JsonArray culprits = new JsonArray();
        for (String culprit : conflict.culprits()) culprits.add(culprit);
        conflictJson.add("culprits", culprits);
        result.add("conflict", conflictJson);
        return result;
    }

    private static boolean ok(int status) { return status >= 200 && status < 300; }

    /** 预设的模块清单 → JSON 数组（写 {@code forge_additional_modules} 用；空清单就是空数组）。 */
    private static JsonArray modulesJson(List<String> modules) {
        JsonArray array = new JsonArray();
        for (String module : modules) if (module != null && !module.isBlank()) array.add(module.strip());
        return array;
    }

    /**
     * 回读校验：WebUI 现在的 {@code forge_additional_modules} 是不是就是这一份（按 basename 比对，
     * 大小写不敏感、顺序不敏感——Forge 可能把路径写成相对/绝对两种形态）。
     */
    private boolean modulesConfirmed(List<String> wanted) {
        try {
            List<String> current = stringValues(options().get("forge_additional_modules"));
            return sameModuleNames(current, wanted);
        } catch (Exception error) { return false; }
    }

    private static boolean sameModuleNames(List<String> left, List<String> right) {
        java.util.TreeSet<String> a = new java.util.TreeSet<>(), b = new java.util.TreeSet<>();
        for (String item : left) if (item != null && !item.isBlank()) a.add(bareName(item).toLowerCase(java.util.Locale.ROOT));
        for (String item : right) if (item != null && !item.isBlank()) b.add(bareName(item).toLowerCase(java.util.Locale.ROOT));
        return a.equals(b);
    }

    /** {@code applied} 里那一行"额外模块变成了什么"（空清单要说清是"已清空"）。 */
    private static String modulesAppliedText(List<String> modules) {
        if (modules.isEmpty()) return "额外模块已清空（该预设不需要额外模块）";
        List<String> names = new ArrayList<>();
        for (String module : modules) names.add(bareName(module));
        return "额外模块 " + modules.size() + " 个：" + String.join("、", names);
    }

    /**
     * 当前生效的「模型参数」快照：底模、采样方法、调度器、步数、CFG、蒸馏 CFG（Shift）、尺寸，
     * 以及 Forge 当前的预设名（预设名从 Forge 自己的 config.json 读，不联网）。
     *
     * <p>**只读本地已知值、不发任何请求**：保存样式必须能在 WebUI 离线时完成（样式库是机器人自己的东西，
     * 原来就有「保存不碰 WebUI」这条约定）。采样方法/尺寸来自最近的设置缓存，底模/步数/CFG 来自持久化参数。
     */
    public synchronized JsonObject modelParams() {
        GenerationParameters parameters = generationParameters;
        GenerationSettings settings = cachedSettings;
        JsonObject result = new JsonObject();
        if (parameters != null) {
            if (parameters.checkpoint() != null && !parameters.checkpoint().isBlank())
                result.addProperty("checkpoint", parameters.checkpoint());
            result.addProperty("steps", parameters.steps());
            result.addProperty("cfg", parameters.cfgScale());
        }
        if (settings != null) {
            if (settings.samplerName() != null && !settings.samplerName().isBlank())
                result.addProperty("sampler", settings.samplerName());
            if (settings.scheduler() != null && !settings.scheduler().isBlank())
                result.addProperty("scheduler", settings.scheduler());
            result.addProperty("width", settings.width());
            result.addProperty("height", settings.height());
            result.addProperty("distilledCfg", settings.distilledCfg());
        }
        JsonObject saved = forgeConfig();
        String preset = Json.str(saved, "forge_preset", "");
        if (!preset.isBlank()) {
            result.addProperty("forge_preset", preset);
            if (!result.has("checkpoint")) {
                String checkpoint = Json.str(saved, "forge_checkpoint_" + preset, "");
                if (!checkpoint.isBlank()) result.addProperty("checkpoint", checkpoint);
            }
        }
        // 这一栈的 VAE 记录（`.vae set` 存的那一份）：**只读本地、不发请求**——保存样式必须能在
        // WebUI 离线时完成（与这个方法开头那句约定一致）。
        if (activeVae != null && !activeVae.isBlank()) result.addProperty("vae", activeVae);
        // 这个快照里**只有检查点名、没有底模名**（`.style save` 存的就是它）：把归属栈和该栈的规范底模名
        // 一起记下（来源标成推断：栈是拿检查点名/预设名判的），网页「按底模分组」才不会漏掉这条样式。
        String checkpoint = Json.str(result, "checkpoint", "");
        String stack = checkpoint.isBlank() ? "" : StackClassifier.stackOf("", StackClassifier.bareName(checkpoint));
        if (stack.isEmpty()) stack = StackClassifier.stackOfPreset(preset);
        if (!stack.isEmpty()) {
            String inferred = StackClassifier.baseModelOfStack(stack);
            if (!inferred.isEmpty()) {
                result.addProperty("baseModel", inferred);
                result.addProperty("baseModelSource", StackClassifier.INFERRED_SOURCE);
            }
            result.addProperty("stack", stack);
            result.addProperty("stackSource", StackClassifier.INFERRED_SOURCE);
        }
        return result;
    }

    /**
     * 载入样式时切换 Forge 预设的注入点：由 Bot 侧复用 {@code .model preset} 那条路（切栈 + 采纳该预设的
     * 推荐参数）。做成回调是为了让 {@link #applyModelParams} 不去反向调用 Bot 的命令层。
     */
    @FunctionalInterface
    public interface PresetSwitcher {
        /** 切到该预设并采纳它的推荐参数；返回「实际采纳了什么」（会写进回执）；切不动就抛异常。 */
        List<String> switchTo(String preset) throws Exception;
    }

    public synchronized List<String> applyModelParams(JsonObject model) { return applyModelParams(model, null); }

    /**
     * 把样式里记着的模型参数套回机器人设置（载入样式时用）。
     * 逐项容错：某一项现在不可用（例如那个底模被删了）就跳过并如实说，不影响提示词已经载入这件事。
     *
     * <p><b>顺序是有讲究的</b>：Forge／Forge Neo 下**预设决定栈**，栈不对的时候写另一个栈的检查点会出全灰
     * 废图，调度器与尺寸也都是这一栈的，所以先切预设（有切换器时），再写底模，然后才是采样方法 / 调度器 /
     * 步数 / CFG / 尺寸。老样式没有 {@code forge_preset} 字段时行为与以前完全一样（不切预设、不报错）。
     *
     * <p><b>尺寸</b>：样式里的 {@code width/height} 可能来自展示图（Civitai 预览图动辄 2400×3744），
     * 超出 64–2048 或不是 8 的倍数时按 {@link #fitGenerationSize} 同比例缩到合法值，并在回执里**如实写明**
     * 这是缩放后的尺寸——静默"保留原值"正是"尺寸不跟着样式走"的老毛病。
     *
     * @param switcher 预设切换器；null 表示没有可用的切换入口（老调用方、单元测试）
     * @return 「实际套上了什么」的中文说明；空列表表示这份参数里没有任何可用项
     */
    public synchronized List<String> applyModelParams(JsonObject model, PresetSwitcher switcher) {
        List<String> applied = new ArrayList<>();
        if (model == null || model.size() == 0) return applied;
        // ① 预设：Forge 下"预设"就是"栈"，先把它切对，后面的底模/调度器/尺寸才有意义。
        String preset = Json.str(model, "forge_preset", "");
        if (!preset.isBlank()) {
            try {
                if (!forge()) applied.add("预设 " + preset + " 未切换（当前 WebUI 不是 Forge／Forge Neo）");
                else {
                    String active = forgePreset();
                    if (preset.equalsIgnoreCase(active)) applied.add("预设 " + preset + "（已是当前预设，未重复切换）");
                    else if (switcher == null) applied.add("预设 " + preset + " 未切换（没有可用的切换入口）");
                    else {
                        List<String> adopted = switcher.switchTo(preset);
                        applied.add("预设 " + (active.isBlank() ? "（未知）" : active) + " → " + preset
                                + (adopted == null || adopted.isEmpty() ? "" : "（" + String.join("、", adopted) + "）"));
                    }
                }
            } catch (Exception error) {
                applied.add("预设 " + preset + " 未切换：" + Bot.error(error) + "（保留原值）");
            }
        }
        // ② 底模（此时栈已经对了，写检查点不会再出全灰废图）。
        String checkpoint = Json.str(model, "checkpoint", "");
        if (!checkpoint.isBlank()) {
            try { setParameter("model", checkpoint); applied.add("底模 " + checkpoint); }
            catch (Exception error) { applied.add("底模 " + checkpoint + " 不可用（保留原值）：" + Bot.error(error)); }
        }
        String sampler = Json.str(model, "sampler", "");
        if (!sampler.isBlank()) {
            try { setSampler(sampler); applied.add(sampler); }
            catch (Exception error) { applied.add("采样方法 " + sampler + " 不可用（保留原值）：" + Bot.error(error)); }
        }
        // ②' VAE（样式里记着 sd_vae 时套回去）：它是 WebUI 的全局选项，写入即生效。
        String vae = Json.str(model, "vae", "");
        if (!vae.isBlank()) {
            try { setVae(vae); applied.add("VAE " + vae); }
            catch (Exception error) { applied.add("VAE " + vae + " 不可用（保留原值）：" + Bot.error(error)); }
        }
        String scheduler = Json.str(model, "scheduler", "");
        double distilled = Json.decimal(model, "distilledCfg", 0);
        if (!scheduler.isBlank() || distilled > 0) {
            try { setForgeExtras(scheduler, distilled); applied.add("调度器 " + (scheduler.isBlank() ? "（不变）" : scheduler)); }
            catch (Exception error) { applied.add("调度器未能写入（保留原值）：" + Bot.error(error)); }
        }
        int steps = Json.num(model, "steps", 0);
        if (steps > 0) {
            try { setParameter("steps", String.valueOf(steps)); applied.add(steps + " 步"); }
            catch (Exception error) { applied.add("步数 " + steps + " 未能写入（保留原值）：" + Bot.error(error)); }
        }
        double cfg = Json.decimal(model, "cfg", 0);
        if (cfg > 0) {
            String cfgText = cfg == Math.rint(cfg) ? String.valueOf((long) cfg) : String.valueOf(cfg);
            try { setParameter("cfg", cfgText); applied.add("CFG " + cfgText); }
            catch (Exception error) { applied.add("CFG " + cfgText + " 未能写入（保留原值）：" + Bot.error(error)); }
        }
        // ④ 尺寸：样式里的尺寸常常来自展示图（2400×3744 这种），超限就同比例缩到合法值再套，
        //    并如实说明；合法值原样套用（用户特意设的 768×512 不会被顺手改掉）。
        int width = Json.num(model, "width", 0), height = Json.num(model, "height", 0);
        if (width > 0 && height > 0) {
            int[] fitted = fitGenerationSize(width, height);
            try {
                setSize(fitted[0], fitted[1]);
                applied.add(sizeAppliedText(width, height, fitted));
            } catch (Exception error) {
                // 套不上也要说清为什么：以前只写"未被接受（保留原值）"，用户没法判断该怎么办。
                applied.add("尺寸 " + width + "×" + height + " 未套用"
                        + (fitted[0] == width && fitted[1] == height ? "" : "（已同比例缩到 " + fitted[0] + "×" + fitted[1] + "，仍被拒绝）")
                        + "：" + Bot.error(error) + "（保留原值）");
            }
        }
        // 展示图样式可能只记下了底模：那个底模不在当前 Forge 预设栈里时就没有可加载的检查点
        // （写别的栈的检查点会出全灰废图），如实说一句，别让"样式带参数"看起来像没生效。
        String baseModel = Json.str(model, "baseModel", "");
        if (checkpoint.isBlank() && !baseModel.isBlank())
            applied.add("底模 " + baseModel + "（样式记录的底模，未自动切换）");
        return applied;
    }

    /**
     * 设置调度器与蒸馏 CFG（Forge 的 anima／flux 这类流匹配模型要用）。
     * 只写机器人自己的记录：桥接扩展不认这两个字段，而它们是**随请求发送**的，不需要和页面同步。
     */
    public synchronized GenerationSettings setForgeExtras(String scheduler, double distilled) throws Exception {
        refresh(true);
        GenerationSettings before = cachedSettings.withForge(activeScheduler, activeDistilledCfg);
        activeScheduler = scheduler == null ? "" : scheduler.strip();
        activeDistilledCfg = Double.isFinite(distilled) && distilled > 0 ? distilled : 0;
        GenerationSettings updated = cachedSettings.withForge(activeScheduler, activeDistilledCfg).withSource(LOCAL_SOURCE);
        persist(updated);
        cachedSettings = updated;
        logSettingsChange("调度器 / Shift", before, updated);
        return updated;
    }

    /** WebUI 可用的调度器（随模型/预设变：加载 Anima 后会多出 beta / turbo / flow_match 等）。 */
    public List<String> schedulers() throws Exception {
        JsonArray catalog = responseArray(request("/sdapi/v1/schedulers", "GET", null, false, false), "读取调度器列表");
        List<String> names = new ArrayList<>();
        for (JsonElement item : catalog) {
            if (!item.isJsonObject()) continue;
            String name = Json.str(item.getAsJsonObject(), "name", "");
            if (!name.isBlank()) names.add(name);
        }
        return List.copyOf(names);
    }

    /** Forge 的额外模块（VAE / 文本编码器）：Anima 要 qwen_image_vae 与 qwen_3_06b_base。 */
    public List<String> modules() throws Exception {
        List<String> names = new ArrayList<>();
        for (String[] entry : moduleEntries()) if (!entry[0].isBlank()) names.add(entry[0]);
        return List.copyOf(names);
    }

    /**
     * {@code /sdapi/v1/sd-modules} 的原始条目：{@code [model_name, filename]}。
     * 这台 Forge Neo 实测返回的就是这两个字段（VAE 与文本编码器混在一张表里），
     * 文件名用来判断这个模块到底是 VAE 还是文本编码器（见 {@link #vaeList()}）。
     */
    private List<String[]> moduleEntries() throws Exception {
        JsonArray catalog = responseArray(request("/sdapi/v1/sd-modules", "GET", null, false, false), "读取额外模块列表");
        List<String[]> entries = new ArrayList<>();
        for (JsonElement item : catalog) {
            if (!item.isJsonObject()) continue;
            JsonObject module = item.getAsJsonObject();
            entries.add(new String[] { Json.str(module, "model_name", ""), Json.str(module, "filename", "") });
        }
        return List.copyOf(entries);
    }

    // ---------------------------------------------------------------- VAE（sd_vae）与冲突防呆

    /** VAE 的"交给 WebUI 自己挑"写法（写进 sd_vae 的值就是这两个之一）。 */
    public static final String VAE_AUTOMATIC = "Automatic", VAE_NONE = "None";

    /**
     * 当前 {@code sd_vae}：**以 WebUI 的 options 为准**（那是真正生效的值）；读不到就返回机器人自己记着的值。
     *
     * <p>注意这里是"读"，不会去改机器人记的那一份：WebUI 里手工改成 Automatic 之后，
     * 这里要如实报 Automatic（权威在 WebUI），而机器人记的值只作为离线时的兜底。
     */
    public synchronized String vae() throws Exception {
        try {
            String current = Json.str(options(), "sd_vae", "").strip();
            if (!current.isBlank()) return current;
        } catch (Exception ignored) { /* 读不到就看机器人自己记的 */ }
        return activeVae;
    }

    /**
     * 写 VAE（{@code Automatic} / {@code None} / 具体文件名都支持），并落进机器人自己的设置。
     *
     * <p><b>非法名字一定先抛、绝不写入</b>：校验在发请求之前完成，报错里带上可用列表。
     * 名字的来源是 {@link #vaeList()}——Forge 的 VAE 下拉框用的就是**文件 basename**（见 Forge 的
     * {@code modules/sd_vae.py}：{@code vae_dict[os.path.basename(filepath)] = filepath}），
     * 所以 basename 与 {@code Automatic}／{@code None} 都收，路径形态也一并容忍（归一成 basename）。
     *
     * <p>写完能读到就回读一次，落盘的是**回读到的值**（WebUI 才是权威）；读不到就落盘请求值，
     * 并在 {@link #vaeSnapshot()} 里如实标出读不到状态。
     */
    public synchronized GenerationSettings setVae(String name) throws Exception {
        String requested = name == null ? "" : name.strip();
        if (requested.isEmpty())
            throw new IOException("请提供 VAE 名称（Automatic／None／具体文件名）；使用 .vae list 查看可用名称。");
        String canonical = canonicalVae(requested);
        JsonObject payload = new JsonObject();
        payload.addProperty("sd_vae", canonical);
        HttpResponse<String> response = request("/sdapi/v1/options", "POST", payload, false, false);
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("设置 VAE 失败：HTTP " + response.statusCode() + "。" + response.body());
        // 回读校验：有的构建会收下请求却不当真，回读到的值才是"实际生效"的那一个。
        String applied = canonical;
        try {
            String readback = Json.str(options(), "sd_vae", "").strip();
            if (!readback.isBlank()) applied = readback;
        } catch (Exception ignored) { /* 回读不到就按请求值落盘，快照里会如实说读不到 */ }
        GenerationSettings before = cachedSettings == null ? null : cachedSettings.withVae(activeVae);
        activeVae = applied;
        GenerationSettings updated = (cachedSettings == null ? fallbackSettings() : cachedSettings)
                .withVae(applied).withSource(LOCAL_SOURCE);
        persist(updated);
        cachedSettings = updated;
        logSettingsChange("VAE", before, updated);
        return updated;
    }

    /**
     * 可用 VAE 列表：**第一个一定是 {@code Automatic}**（第二个是 {@code None}）。
     *
     * <p>来源按可靠性排：
     * <ol>
     *   <li>{@code GET /sdapi/v1/sd-modules}——本机 Forge Neo 实测 200，条目形如
     *       {@code {"model_name":"animevae.pt","filename":"…\\models\\VAE\\sd1.5\\animevae.pt"}}；
     *       里面 VAE 与文本编码器是混着的，所以按文件名筛（名字含 vae／在 models/VAE 下才收）；</li>
     *   <li>options 里现成的线索：当前 {@code sd_vae} 自己，以及各预设 {@code forge_additional_modules_*} 里的 VAE；</li>
     *   <li>兜底：扫 {@code <sd.root>/models/VAE} 下的 VAE 文件（{@code .safetensors/.pt/.pth/.ckpt/.bin}），
     *       外加 {@code models/} 下 {@code *.vae.*} 这种命名（Forge 自己也认这种）。</li>
     * </ol>
     * 读不到任何来源时至少返回 {@code [Automatic, None]}——这样下拉框与 {@code .vae set} 仍然可用。
     */
    public synchronized List<String> vaeList() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            for (String[] entry : moduleEntries()) if (looksLikeVae(entry[0], entry[1])) names.add(bareName(entry[0]));
        } catch (Exception ignored) { /* 这个构建没有 sd-modules：走下面的来源 */ }
        try {
            JsonObject options = options();
            String current = Json.str(options, "sd_vae", "").strip();
            if (!current.isBlank() && !VaeGuard.automaticVae(current)) names.add(bareName(current));
            for (String key : options.keySet()) {
                if (!key.startsWith("forge_additional_modules")) continue;
                for (String module : stringValues(options.get(key))) if (looksLikeVae(module, module)) names.add(bareName(module));
            }
        } catch (Exception ignored) { /* 读不到 options 就只剩目录扫描 */ }
        for (String file : vaeFilesOnDisk()) names.add(bareName(file));
        if (!activeVae.isBlank() && !VaeGuard.automaticVae(activeVae)) names.add(bareName(activeVae));
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(VAE_AUTOMATIC);
        result.add(VAE_NONE);
        for (String name : names) if (!name.isBlank()) result.add(name);
        return List.copyOf(result);
    }

    /**
     * 某个 Forge 预设立场上的额外模块清单（**去目录的文件名**，与 {@link #vaeSnapshot()} 的
     * {@code modules} 同一形态，调用方可以直接比对"回读到的模块"与"该预设应有的模块"）。
     * 读不到（不是 Forge／SD 没在跑）返回空列表。
     */
    public synchronized List<String> presetModules(String preset) {
        String name = preset == null ? "" : preset.strip();
        if (name.isEmpty()) return List.of();
        try {
            JsonObject defaults = forgePresetDefaults(name);
            List<String> result = new ArrayList<>();
            for (String module : stringValues(defaults.get("modules"))) {
                String bare = bareName(module);
                result.add(bare.isEmpty() ? module.strip() : bare);
            }
            return List.copyOf(result);
        } catch (Exception ignored) { return List.of(); }
    }

    /**
     * 给网页／指令用的一次性"当前状态快照"：
     * {@code {vae, vaeAuto, modules, modulesRaw, checkpoint, preset, conflict{level,reason,suggestion,culprits}, choices}}。
     *
     * <p>读不到 SD 时不抛异常（页面要能照常渲染）：{@code reachable=false}、{@code errors} 里带上原因，
     * {@code vae} 退回机器人自己记的值，冲突判定按"能拿到的字段"照常给（多半是 WARN）。
     */
    public synchronized JsonObject vaeSnapshot() {
        JsonObject result = new JsonObject();
        List<String> errors = new ArrayList<>();
        String checkpoint = "";
        String vae = activeVae;
        List<String> modules = new ArrayList<>();
        boolean reachable = false;
        JsonObject options = null;
        try {
            options = options();
            reachable = true;
        } catch (Exception error) { errors.add("读取 WebUI 选项失败：" + Bot.error(error)); }
        if (options != null) {
            String live = Json.str(options, "sd_vae", "").strip();
            if (!live.isBlank()) vae = live;
            checkpoint = Json.str(options, "sd_model_checkpoint", "").strip();
            modules.addAll(stringValues(options.get("forge_additional_modules")));
        }
        List<VaeGuard.Reading> readings = checkpointReadings(options);
        if (checkpoint.isBlank() && !readings.isEmpty()) checkpoint = readings.get(0).checkpoint();
        result.addProperty("vae", vae);
        result.addProperty("vaeAuto", VaeGuard.automaticVae(vae));
        JsonArray raw = new JsonArray();
        JsonArray shown = new JsonArray();
        for (String module : modules) {
            if (module == null || module.isBlank()) continue;
            raw.add(module);
            String bare = bareName(module);
            shown.add(bare.isEmpty() ? module.strip() : bare);
        }
        result.add("modules", shown);
        result.add("modulesRaw", raw);
        result.addProperty("checkpoint", checkpoint);
        // 多源读数如实交回：网页／指令据此显示"options 读到什么、当前预设声明什么、机器人记着什么"，
        // 也解释了为什么有时只给 WARN（读数互相矛盾时不下结论）。
        JsonArray readingList = new JsonArray();
        for (VaeGuard.Reading reading : readings) {
            JsonObject item = new JsonObject();
            item.addProperty("source", reading.source());
            item.addProperty("checkpoint", bareName(reading.checkpoint()));
            readingList.add(item);
        }
        result.add("readings", readingList);
        try { result.addProperty("preset", forgePreset()); } catch (Exception ignored) { /* 不是 Forge 就没有预设名 */ }
        VaeGuard.Facts facts = new VaeGuard.Facts(checkpoint, vae, stringValues(result.get("modules")), readings);
        result.addProperty("readingsConsistent", !VaeGuard.inconsistent(facts));
        VaeGuard.Conflict conflict = VaeGuard.check(facts);
        JsonObject conflictJson = new JsonObject();
        conflictJson.addProperty("level", conflict.level());
        conflictJson.addProperty("reason", conflict.reason());
        conflictJson.addProperty("suggestion", conflict.suggestion());
        JsonArray culprits = new JsonArray();
        for (String culprit : conflict.culprits()) culprits.add(culprit);
        conflictJson.add("culprits", culprits);
        result.add("conflict", conflictJson);
        JsonArray choices = new JsonArray();
        for (String choice : vaeList()) choices.add(choice);
        result.add("choices", choices);
        result.addProperty("reachable", reachable);
        JsonArray errorList = new JsonArray();
        for (String error : errors) errorList.add(error);
        result.add("errors", errorList);
        return result;
    }

    /** 检查点 + 当前 sd_vae + 当前额外模块 + **多源读数** → {@link VaeGuard} 的输入（判定全在那边，纯函数）。 */
    public synchronized VaeGuard.Facts vaeFacts() {
        String checkpoint = "";
        String vae = activeVae;
        List<String> modules = List.of();
        JsonObject options = null;
        try {
            options = options();
            checkpoint = Json.str(options, "sd_model_checkpoint", "").strip();
            String live = Json.str(options, "sd_vae", "").strip();
            if (!live.isBlank()) vae = live;
            modules = stringValues(options.get("forge_additional_modules"));
        } catch (Exception ignored) { /* 离线：用机器人自己记的 VAE，检查点退回持久化参数 */ }
        List<VaeGuard.Reading> readings = checkpointReadings(options);
        if (checkpoint.isBlank() && !readings.isEmpty()) checkpoint = readings.get(0).checkpoint();
        return new VaeGuard.Facts(checkpoint, vae, modules, readings);
    }

    /**
     * **多源取检查点**（全部只读，绝不写）：这些来源互相矛盾时判定只到 WARN。
     *
     * <ol>
     *   <li>{@code options.sd_model_checkpoint}——"设置里的值"。**它可能还是上一次的**：Forge 切换模型
     *       期间/经 UI 切换后有一段时间读到的仍是旧值（2026-10 的误报就是这么来的：用户 UI 上是 anima，
     *       options 还写着 SDXL，于是"SDXL + qwen 的 VAE"被判成 BLOCK）；</li>
     *   <li>当前 {@code forge_preset} 声明的那一个（{@code forge_checkpoint_<preset>}，options 与
     *       Forge 自己的 config.json 两处合起来看）——预设是"用户选定的栈"，切预设时由 Forge 写入；</li>
     *   <li>机器人自己记着的底模（{@code data/sd-parameters.json}；空＝"跟随 WebUI 当前模型"，不算读数）；</li>
     *   <li>按 {@code options.sd_checkpoint_hash} 反查到的"**已加载**"检查点（{@code /sdapi/v1/sd-models}
     *       的标题带 {@code [哈希]}）——这一条最接近"真正在跑的模型"。</li>
     * </ol>
     * 读不到的来源直接不放进来（不编造）；全都读不到就是空列表，判定退回"单源"行为。
     */
    private List<VaeGuard.Reading> checkpointReadings(JsonObject options) {
        List<VaeGuard.Reading> readings = new ArrayList<>();
        if (options != null) {
            String live = Json.str(options, "sd_model_checkpoint", "").strip();
            if (!live.isBlank()) readings.add(new VaeGuard.Reading("options", live));
            String preset = firstNonBlank(Json.str(options, "forge_preset", "").strip(),
                    Json.str(forgeConfig(), "forge_preset", "").strip());
            if (!preset.isBlank()) {
                String expected = firstNonBlank(Json.str(options, "forge_checkpoint_" + preset, "").strip(),
                        Json.str(forgeConfig(), "forge_checkpoint_" + preset, "").strip());
                if (!expected.isBlank()) readings.add(new VaeGuard.Reading("Forge 预设 " + preset, expected));
            }
            String hash = Json.str(options, "sd_checkpoint_hash", "").strip();
            if (!hash.isBlank()) {
                String loaded = loadedCheckpoint(hash);
                if (!loaded.isBlank()) readings.add(new VaeGuard.Reading("已加载哈希", loaded));
            }
        }
        String remembered = generationParameters == null ? "" : generationParameters.checkpoint();
        if (remembered != null && !remembered.isBlank()) readings.add(new VaeGuard.Reading("机器人记录", remembered));
        return List.copyOf(readings);
    }

    /**
     * 用 {@code sd_checkpoint_hash} 反查"真正加载中的"检查点：{@code /sdapi/v1/sd-models} 的标题形如
     * {@code waiIllustriousSDXL_v170.safetensors [f116b0c78f]}，哈希前缀对得上就是它。
     * 只 GET 列表（不读模型文件头部），读不到就返回空串——少一条读数不影响判定。
     */
    private String loadedCheckpoint(String hash) {
        String wanted = hash.toLowerCase(java.util.Locale.ROOT).strip();
        if (wanted.length() < 8) return "";
        String prefix = "[" + wanted.substring(0, Math.min(10, wanted.length())) + "]";
        try {
            for (String title : models()) if (title.toLowerCase(java.util.Locale.ROOT).contains(prefix))
                return StackClassifier.bareName(title);
        } catch (Exception ignored) { /* 读不到模型列表就当没有这条读数 */ }
        return "";
    }

    /** 网页 JSON 里的字符串数组 → List（缺字段／非数组时为空）。 */
    private static List<String> stringValues(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray())
            if (item != null && item.isJsonPrimitive()) values.add(item.getAsString());
        return List.copyOf(values);
    }

    /**
     * 校验并归一 VAE 名字：{@code Automatic}／{@code None} 直接过；其余必须能在 {@link #vaeList()} 里对上
     * （大小写不敏感、路径形态归一成 basename）。对不上就抛异常并**把可用列表写进错误信息**。
     */
    private String canonicalVae(String requested) throws Exception {
        if (requested.equalsIgnoreCase(VAE_AUTOMATIC) || requested.equalsIgnoreCase("auto") || requested.equals("自动")
                || requested.equals("跟随"))
            return VAE_AUTOMATIC;
        if (requested.equalsIgnoreCase(VAE_NONE) || requested.equals("无") || requested.equals("不用")) return VAE_NONE;
        List<String> available = vaeList();
        String wanted = bareName(requested);
        for (String name : available)
            if (name.equalsIgnoreCase(requested) || name.equalsIgnoreCase(wanted)) return name;
        throw new IOException("未知 VAE：" + requested + "；可用：" + String.join("、", available)
                + "（用 .vae list 查看全部；Forge 的 VAE 下拉框用的是文件名本身）。");
    }

    /** 去目录、去 {@code [哈希]} 后缀（VAE 列表与比对都用 basename，Forge 的 sd_vae 也是 basename）。 */
    private static String bareName(String value) { return StackClassifier.bareName(value).strip(); }

    /**
     * 这一条额外模块是不是 VAE（{@code /sdapi/v1/sd-modules} 里 VAE 与文本编码器混在一张表里）。
     * 判据：名字里带 {@code vae}，或文件在 {@code models/VAE} 下（Flux 的 VAE 就叫 {@code ae.safetensors}，
     * 名字里没有 vae），或名字本身就是 {@code ae}。
     */
    private static boolean looksLikeVae(String name, String filename) {
        String lower = (name == null ? "" : name).toLowerCase(java.util.Locale.ROOT);
        String path = (filename == null ? "" : filename).toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
        if (lower.contains("vae") || path.contains("/vae/") || path.endsWith("/vae")) return true;
        String stem = lower.replaceAll("(?i)\\.(safetensors|pt|pth|ckpt|bin|sft|gguf)$", "");
        return stem.equals("ae") || stem.endsWith("/ae");
    }

    /**
     * 扫 {@code <sd.root>/models/VAE} 下的 VAE 文件（Forge 自己的 {@code refresh_vae_list()} 就是扫这里，
     * 文件名取 basename）；另外补上 {@code models/} 下 {@code *.vae.*} 这种命名。
     * 只读目录、不发请求；读不到返回空列表。
     */
    private List<String> vaeFilesOnDisk() {
        String directory = sdRoot();
        if (directory.isBlank()) return List.of();
        Path models;
        try { models = Path.of(directory).resolve("models"); }
        catch (Exception error) { return List.of(); }
        List<String> files = new ArrayList<>();
        try {
            Path vaeDir = models.resolve("VAE");
            if (Files.isDirectory(vaeDir)) {
                try (var paths = Files.walk(vaeDir, 4)) {
                    paths.filter(Files::isRegularFile).filter(SdClient::isVaeFile).limit(500)
                            .forEach(path -> files.add(path.getFileName().toString()));
                }
            }
            if (Files.isDirectory(models)) {
                try (var paths = Files.list(models)) {
                    paths.filter(Files::isRegularFile).limit(500)
                            .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains(".vae."))
                            .forEach(path -> files.add(path.getFileName().toString()));
                }
            }
        } catch (Exception ignored) { /* 目录不可读就当没有：列表至少有 Automatic/None */ }
        return List.copyOf(files);
    }

    private static boolean isVaeFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        for (String extension : List.of(".safetensors", ".pt", ".pth", ".ckpt", ".bin", ".sft", ".gguf"))
            if (name.endsWith(extension)) return true;
        return false;
    }

    private String canonicalModel(String requested) throws Exception {
        List<String> all = models();
        List<String> exact = all.stream().filter(name -> name.equalsIgnoreCase(requested)).toList();
        if (exact.size() == 1) return exact.get(0);
        // Forge 的预设里存的是**没有哈希后缀**的文件名（animaCatTower_v11-full.safetensors），
        // 而模型列表的标题带 " [哈希]"；去掉后缀再比一次，仍然要求唯一命中。
        String bare = bareModel(requested);
        List<String> loose = all.stream().filter(name -> bareModel(name).equalsIgnoreCase(bare)).toList();
        if (loose.size() == 1) return loose.get(0);
        throw new IOException("基础模型不存在或名称不唯一；请用 .model list 查看完整名称。");
    }

    /** 去掉标题里的 " [哈希]" 后缀。 */
    private static String bareModel(String name) {
        String value = name == null ? "" : name.strip();
        int mark = value.indexOf(" [");
        return mark > 0 ? value.substring(0, mark) : value;
    }

    public synchronized GenerationParameters setParameter(String field, String value) throws Exception {
        GenerationParameters old = generationParameters;
        GenerationParameters updated;
        try {
            updated = switch (field) {
                case "steps" -> new GenerationParameters(Integer.parseInt(value), old.cfgScale(), old.seed(), old.checkpoint());
                case "cfg" -> new GenerationParameters(old.steps(), Double.parseDouble(value), old.seed(), old.checkpoint());
                case "seed" -> new GenerationParameters(old.steps(), old.cfgScale(), Long.parseLong(value), old.checkpoint());
                // auto（跟随）：不固定底模，提交任务时读 WebUI 当前模型——Forge Neo 的预设栈就是这么用的，
                // 固定成某个检查点会盖掉预设（预设=anima 却加载 SDXL 的错配就是这么来的）。
                case "model" -> new GenerationParameters(old.steps(), old.cfgScale(), old.seed(),
                        isAutoModel(value) ? "" : canonicalModel(value));
                default -> throw new IOException("未知生成参数。");
            };
        } catch (NumberFormatException e) { throw new IOException("参数数值格式不正确。", e); }
        persistParameters(updated);
        logParametersChange(parameterLabel(field), old, updated);
        return updated;
    }

    /** 单个参数的中文名（日志里用）。 */
    private static String parameterLabel(String field) {
        return switch (field) {
            case "steps" -> "迭代步数";
            case "cfg" -> "CFG";
            case "seed" -> "种子";
            case "model" -> "底模";
            default -> field;
        };
    }

    private void persistParameters(GenerationParameters updated) throws IOException {
        Json.atomicWrite(root.resolve("data/sd-parameters.json"), updated.json());
        generationParameters = updated;
    }

    // ---------------------------------------------------------------- 参数改动留痕
    //
    // 「我的生成参数为什么莫名其妙被改了」必须能查。生成参数有三个来源：
    //   ① 采样方法/尺寸/预设样式：WebUI 页面（桥接）优先，网页端与指令改的是这一份；
    //   ② 步数/CFG/种子/底模：只存在机器人自己的 data/sd-parameters.json；
    //   ③ 预设与样式载入：一次改一整套。
    // 所以每个入口在真的改了值的时候都写一行「旧值 → 新值」，并标出是网页端还是 QQ 侧。

    /** 生成参数变更的一句话说明（两边一样就是空串）。只报人真正关心的四项。 */
    public static String describeSettingsChange(GenerationSettings before, GenerationSettings after) {
        if (before == null || after == null) return "";
        List<String> parts = new ArrayList<>();
        if (!Objects.equals(before.samplerName(), after.samplerName()))
            parts.add("采样方法 " + orBlank(before.samplerName()) + " → " + orBlank(after.samplerName()));
        if (before.width() != after.width() || before.height() != after.height())
            parts.add("尺寸 " + before.width() + "×" + before.height() + " → " + after.width() + "×" + after.height());
        if (!before.styles().equals(after.styles()))
            parts.add("预设样式 " + orNone(before.styles()) + " → " + orNone(after.styles()));
        if (!Objects.equals(before.scheduler(), after.scheduler()) || Double.compare(before.distilledCfg(), after.distilledCfg()) != 0)
            parts.add("调度器/Shift " + forgeSummary(before) + " → " + forgeSummary(after));
        if (!Objects.equals(before.vae(), after.vae()))
            parts.add("VAE " + orBlank(before.vae()) + " → " + orBlank(after.vae()));
        return String.join("；", parts);
    }

    /** 步数/CFG/种子/底模的变更说明（两边一样就是空串）。 */
    public static String describeParametersChange(GenerationParameters before, GenerationParameters after) {
        if (before == null || after == null) return "";
        List<String> parts = new ArrayList<>();
        if (before.steps() != after.steps()) parts.add("迭代步数 " + before.steps() + " → " + after.steps());
        if (Double.compare(before.cfgScale(), after.cfgScale()) != 0)
            parts.add("CFG " + orInteger(before.cfgScale()) + " → " + orInteger(after.cfgScale()));
        if (before.seed() != after.seed()) parts.add("种子 " + before.seed() + " → " + after.seed());
        if (!Objects.equals(before.checkpoint(), after.checkpoint()))
            parts.add("底模 " + orAuto(before.checkpoint()) + " → " + orAuto(after.checkpoint()));
        return String.join("；", parts);
    }

    /** 改参数的入口名（日志里"谁改的"）：网页端 / QQ 侧。 */
    private static String side() { return Log.webSide() ? "网页端" : "QQ 侧"; }

    /** 参数变更的统一日志：值真变了才打（面板反复失焦不该刷屏）。 */
    private static void logSettingsChange(String where, GenerationSettings before, GenerationSettings after) {
        String diff = describeSettingsChange(before, after);
        if (!diff.isBlank()) Log.info("生成参数变更（" + side() + "·" + where + "）：" + diff);
    }
    private static void logParametersChange(String where, GenerationParameters before, GenerationParameters after) {
        String diff = describeParametersChange(before, after);
        if (!diff.isBlank()) Log.info("生成参数变更（" + side() + "·" + where + "）：" + diff);
    }
    private static String orBlank(String value) { return value == null || value.isBlank() ? "（空）" : value.strip(); }
    private static String orAuto(String value) { return value == null || value.isBlank() ? "跟随 WebUI 当前模型" : value.strip(); }
    private static String orNone(List<String> names) { return names == null || names.isEmpty() ? "（无）" : String.join("、", names); }
    private static String orInteger(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value); }
    private static String forgeSummary(GenerationSettings settings) {
        String scheduler = settings.scheduler() == null || settings.scheduler().isBlank() ? "（默认）" : settings.scheduler();
        return settings.distilledCfg() > 0 ? scheduler + " / Shift " + orInteger(settings.distilledCfg()) : scheduler;
    }

    public synchronized GenerationRequest loadPreset(String name, GenerationPreset preset) throws Exception {
        String label = "预设 " + (name == null || name.isBlank() ? "（无名）" : name.strip());
        GenerationParameters beforeParameters = generationParameters;
        GenerationParameters p = preset.parameters();
        String model = canonicalModel(p.checkpoint());
        if (!samplers().contains(preset.sampler())) throw new IOException("预设采样方法已不可用；请用 .sampler list 查看。");
        validateSize(preset.width(), preset.height());
        GenerationSettings basic = changeSettings(label, Set.of("sampler_name", "width", "height"), current ->
                new GenerationSettings(preset.sampler(), current.styles(), preset.width(), preset.height(), LOCAL_SOURCE));
        if (!basic.samplerName().equals(preset.sampler()) || basic.width() != preset.width() || basic.height() != preset.height())
            throw new IOException("WebUI 未确认预设中的尺寸或采样方法，请检查参数后重试。");
        try { persistParameters(new GenerationParameters(p.steps(), p.cfgScale(), p.seed(), model)); }
        catch (IOException e) { throw new IOException("尺寸和采样方法已更新，但其他参数保存失败；请检查磁盘并重新加载预设。", e); }
        logParametersChange(label, beforeParameters, generationParameters);
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
            if (parameters != null) {
                // WebUI 页面优先：这里覆盖掉机器人自己的记录时**必须留痕**，否则就成了"参数莫名其妙被改"。
                GenerationSettings before = cachedSettings;
                if (before == null) Log.info("生成参数初始化（" + parameters.source() + "）："
                        + orBlank(parameters.samplerName()) + "，" + parameters.width() + "×" + parameters.height());
                else if (needSettings) logSettingsChange("跟随 WebUI 页面（" + parameters.source() + "）", before, parameters);
                cachedSettings = parameters;
            }
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
        Map<String, String> bases = new HashMap<>();
        for (JsonElement item : catalog) {
            if (!item.isJsonObject()) throw new IOException("WebUI LoRA 列表格式无效。");
            JsonObject value = item.getAsJsonObject();
            String name = requireString(value, "name"), path = requireString(value, "path");
            String alias = value.has("alias") && !value.get("alias").isJsonNull() ? requireString(value, "alias") : name;
            if (name.isBlank() || path.isBlank()) throw new IOException("WebUI LoRA 列表包含空名称或路径。");
            result.add(new Lora(name, alias, path));
            // 顺手记下每项在 Forge 元数据里自报的底模：底模识别要它，但不想为此再发一次请求。
            String base = metadataBaseModel(value.get("metadata"));
            if (!base.isBlank()) for (String key : loraKeys(name, alias, path)) bases.putIfAbsent(key, base);
        }
        forgeLoraBases = Map.copyOf(bases);
        return List.copyOf(result);
    }

    /**
     * Forge 的 LoRA 元数据里自报的底模。
     *
     * <p>实测（Forge Neo，{@code GET /sdapi/v1/loras}）：本机一个 LoRA 的元数据里有
     * {@code ss_base_model_version="anima"}；另一个没有这个键，只有 sd-scripts 的占位
     * {@code ss_sd_model_name="model.safetensors"}——后者是"训练时没记底模"，必须当成没有，
     * 拿它当底模就是编造。{@code modelspec.architecture} 是最后一条线索（如 stable-diffusion-v1/lora）。
     *
     * <p>**具体**值优先：{@code ss_sd_model_name="model.ckpt"} 这类**通用底模名**只说明哪个时代，
     * 不许挡住后面的 {@code ss_base_model_version} / {@code modelspec.architecture}；真的只有通用名时
     * 才返回它（调用方用 {@link StackClassifier#genericBaseModel(String)} 认出来并降级处理）。
     */
    static String metadataBaseModel(JsonElement metadata) {
        if (metadata == null || !metadata.isJsonObject()) return "";
        JsonObject value = metadata.getAsJsonObject();
        String generic = "";
        for (String key : List.of("ss_base_model_version", "ss_sd_model_name", "modelspec.architecture")) {
            String candidate = usableBaseModel(Json.str(value, key, ""));
            if (candidate.isEmpty()) continue;
            if (StackClassifier.genericBaseModel(candidate)) {
                if (generic.isEmpty()) generic = candidate;
                continue;
            }
            return candidate;
        }
        return generic;
    }

    /** 明显的占位值当成"没有底模"：识别不出来时宁可回退到预设栈，也不要编一个名字。 */
    static String usableBaseModel(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) return "";
        return switch (text.toLowerCase(java.util.Locale.ROOT)) {
            case "unknown", "未知", "none", "null", "n/a", "na", "-", "model", "model.safetensors",
                 "unknown.safetensors" -> "";
            default -> text;
        };
    }

    /** 各来源对同一个底模的写法不一样（Civitai 写 Anima，Forge 写 anima，架构串写 stable-diffusion-xl-v1-base）。 */
    static String canonicalBaseModel(String raw) { return StackClassifier.canonicalBaseModel(raw); }

    /** 上一次读 LoRA 列表时 Forge 元数据自报的底模（名字/别名/文件名都能查；查不到返回空串）。 */
    public String forgeLoraBaseModel(String loraName, String loraPath) {
        Map<String, String> bases = forgeLoraBases;
        if (bases.isEmpty()) return "";
        for (String key : loraKeys(loraName, "", loraPath)) {
            String value = bases.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    /** 一个 LoRA 的几种写法（名字/别名/文件名/文件名去扩展名），统一小写当键。 */
    private static List<String> loraKeys(String name, String alias, String path) {
        List<String> keys = new ArrayList<>();
        addLoraKey(keys, name);
        addLoraKey(keys, alias);
        String normalized = path == null ? "" : path.replace('\\', '/');
        String filename = normalized.isEmpty() ? "" : normalized.substring(normalized.lastIndexOf('/') + 1);
        addLoraKey(keys, filename);
        addLoraKey(keys, filename.replaceFirst("(?i)\\.safetensors$", ""));
        return keys;
    }

    private static void addLoraKey(List<String> keys, String value) {
        if (value == null) return;
        String key = value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
    }

    /**
     * 解析一个 LoRA 的底模与归属栈。
     *
     * <p>**底模优先级**（越靠前越硬；每一步都要求"能判出已知族"，判不出来的名字留到最后兜底）：
     * <ol>
     *   <li><b>Civitai 记录</b>：{@code data/civitai/<文件名>.json} 的 {@code base_model}（下载时由
     *       Civitai 的 {@code /api/v1/model-versions/<id>} 的 {@code baseModel} 落盘，见
     *       {@code CivitaiClient}）。那是**发布者自己填的**架构声明（{@code SD 1.5} / {@code Illustrious} /
     *       {@code NoobAI} / {@code Flux.1 D} …），比训练脚本名可靠，所以排在第一位；</li>
     *   <li><b>safetensors 头部自己声明的架构/底模</b>（{@code ss_base_model_version} /
     *       {@code modelspec.architecture} / {@code modelspec.importer}）→ {@code safetensors-header}；</li>
     *   <li><b>Forge 的 LoRA 元数据</b>（与头部同源，但它可能把某些键过滤掉了）→ {@code forge-metadata}；</li>
     *   <li><b>头部张量名结构与张量形状</b>→ {@code safetensors-keys}（{@code lora_te1_*}/{@code lora_te2_*}、
     *       {@code attn2} 的上下文维度 768/1024/2048、{@code conditioner.embedders.*}、{@code net.*}、
     *       {@code double_blocks.*} …）；</li>
     *   <li><b>训练时的通用底模名</b>（{@code ss_sd_model_name="model.ckpt"} / {@code "Anything-v5.0-…"}）：
     *       {@link StackClassifier#genericBaseModel(String)} 认得的，映射到它指的家族（SD1.5 → {@code sd} 栈），
     *       来源如实标 {@link StackClassifier#INFERRED_SOURCE}；</li>
     *   <li>具体但认不出族的名字（Civitai 的 {@code Other}、用户自训底模）：照旧用它自己的 slug 当栈；</li>
     *   <li>都没有：按当前 Forge 预设栈**推断**（{@code preset-inferred}）。</li>
     * </ol>
     *
     * <p>通用底模名**绝不再当栈名**：{@code model.ckpt} 以前会变成 {@code model-ckpt} 这种伪栈，
     * 现在它归 {@code sd} 栈，底模名显示成「SD 1.5（原底模名 model.ckpt）」（见 {@link #attribute}）。
     * 判据原文里照旧写出"哪个键、什么值"，用户问得出来源。
     *
     * @param civitaiBaseModel Civitai 记录里的 base_model（没有就空串）
     * @param presetFallback   预设栈兜底（一次列表里只读一遍，别每个 LoRA 都问一次 Forge）；null 表示现读
     */
    public synchronized BaseModel resolveBaseModel(String civitaiBaseModel, String loraName, String loraPath, BaseModel presetFallback) {
        Path file = readableFile(loraPath);
        // 文件头部先读一遍（有缓存）：Civitai 优先，但头部声明要写进判据，用户才看得出两边一致还是冲突。
        StackClassifier.Header declared = StackClassifier.declared(file);
        boolean declaredGeneric = StackClassifier.genericBaseModel(declared.baseModel());
        // 1) Civitai 记录：发布者自己填的 Base Model，比训练脚本名可靠，所以排在第一位。
        String recorded = usableBaseModel(civitaiBaseModel);
        if (familyKnown(recorded))
            return attribute(canonicalBaseModel(recorded), CIVITAI_SOURCE,
                    "Civitai 记录的 base_model=" + recorded.strip() + declaredNote(recorded, declared, declaredGeneric),
                    loraName, loraPath);
        // 2) 文件头部自己声明的架构/底模：具体值最经得起追问（哪个键、什么值都记下来）。
        if (declared.known() && !declaredGeneric)
            return attribute(declared.baseModel(), StackClassifier.HEADER_SOURCE, declared.evidence(), loraName, loraPath);
        // 3) Forge 的 LoRA 元数据（与头部同源，但它可能把某些键过滤掉了）。
        String metadata = usableBaseModel(forgeLoraBaseModel(loraName, loraPath));
        boolean metadataGeneric = StackClassifier.genericBaseModel(metadata);
        if (familyKnown(metadata) && !metadataGeneric)
            return attribute(canonicalBaseModel(metadata), FORGE_SOURCE, "Forge LoRA 元数据的底模=" + metadata.strip(), loraName, loraPath);
        // 4) 头部张量名结构与形状：通用底模名不可信时，这是最硬的实据（键名 + 维度说了架构）。
        String generic = firstGeneric(declared, recorded, metadata);
        StackClassifier.Header structure = StackClassifier.structural(file);
        if (structure.known())
            return attribute(structure.baseModel(), StackClassifier.KEYS_SOURCE,
                    withGenericNote(structure.evidence(), generic), loraName, loraPath, generic);
        // 5) 只剩通用底模名（model.ckpt / Anything-v5 …）：按 SD1.5 时代的命名归家族，来源如实标成推断。
        if (!generic.isEmpty())
            return attribute(StackClassifier.canonicalBaseModel(generic), StackClassifier.INFERRED_SOURCE,
                    genericEvidence(generic), loraName, loraPath, generic);
        // 6) 具体但认不出族的名字：照旧自成一族（栈名是它的 slug），来源照实写。
        if (!recorded.isEmpty())
            return attribute(canonicalBaseModel(recorded), CIVITAI_SOURCE,
                    "Civitai 记录的 base_model=" + recorded.strip(), loraName, loraPath);
        if (!metadata.isEmpty())
            return attribute(canonicalBaseModel(metadata), FORGE_SOURCE,
                    "Forge LoRA 元数据的底模=" + metadata.strip(), loraName, loraPath);
        // 7) 都读不到：按当前 Forge 预设栈推断（显式标成推断）。
        return presetFallback == null ? presetBaseModel() : presetFallback;
    }

    /** 这个名字能不能判出**已知族**（能，才算"具体底模"；判不出来只能当名字兜底）。 */
    private static boolean familyKnown(String baseModel) {
        String canonical = canonicalBaseModel(usableBaseModel(baseModel));
        return !canonical.isEmpty() && StackClassifier.knownStack(StackClassifier.stackOfBaseModel(canonical));
    }

    /** 三层来源里读到的第一个**通用底模名**（{@code model.ckpt} / {@code Anything-v5} …）；都没有就是空串。 */
    private static String firstGeneric(StackClassifier.Header declared, String recorded, String metadata) {
        if (StackClassifier.genericBaseModel(declared.baseModel())) return declared.baseModel();
        if (StackClassifier.genericBaseModel(recorded)) return recorded;
        return StackClassifier.genericBaseModel(metadata) ? metadata : "";
    }

    /**
     * Civitai 值当选时，把文件头部**另外**说的那些也写进判据：用户看的是一条结论，但依据必须能追问。
     * 头部说的是通用名（{@code model.ckpt}）时点明"不作为底模"；头部说的是别的栈时点明"以 Civitai 为准"。
     */
    private static String declaredNote(String recorded, StackClassifier.Header declared, boolean generic) {
        if (!declared.known()) return "";
        if (generic) return "；文件头部另有训练底模名 " + declared.baseModel() + "（通用名，不作为具体底模）";
        String mine = StackClassifier.stackOfBaseModel(canonicalBaseModel(recorded));
        String theirs = StackClassifier.stackOfBaseModel(declared.baseModel());
        if (!mine.isEmpty() && mine.equals(theirs))
            return "；文件头部声明的是 " + declared.baseModel() + "（同一栈：" + declared.evidence() + "）";
        return "；文件头部声明的是 " + declared.baseModel() + "（" + declared.evidence() + "，不同栈时以 Civitai 为准）";
    }

    /** 通用底模名不参与"具体底模"判定，但用户问起来要答得上：把为什么不算写进判据。 */
    private static String withGenericNote(String evidence, String generic) {
        return generic.isEmpty() ? evidence
                : evidence + "；另有通用底模名 " + generic + "（SD1.5 时代的常见命名，不作为具体底模，已按张量键判定）";
    }

    /** 只剩通用底模名时的判据原文：说清"这不是文件声明的架构，是按时代命名归的家族"。 */
    private static String genericEvidence(String generic) {
        return "头部/元数据里的训练底模名 " + generic + " 是通用名（SD1.5 时代的常见命名），"
                + "没有更硬的实据，按它归入 " + StackClassifier.canonicalBaseModel(generic);
    }

    /**
     * 识别出来的底模 + 由它判出的栈。
     *
     * <p>栈先由**底模名**判（底模是实据，栈也就是实据）；底模名认不出**已知族**时（栈是按名字自成一族的
     * slug），退回按 LoRA 名字/文件名关键词猜，并把栈的来源标成 {@code inferred}——**不许**拿"底模有实据"
     * 去给一个猜出来的栈背书；文件名也没有线索时保留 slug 栈，同样标成推断。
     *
     * <p>最后一步：底模名确实为空（占位值），但栈能从名字/文件名判出来时，补该栈的规范底模名
     * （来源 {@code inferred}），别让这条记录底模一栏永远空着。
     *
     * <p>{@code generic} 是这条记录里读到的**通用底模名**（{@code model.ckpt} /
     * {@code Anything-v5.0-PRT-RE.safetensors}）：它跟最终判出来的栈同族时，底模名显示成
     * 「{@code SD 1.5（原底模名 model.ckpt）}」——人看得懂，而且不会再有 {@code model-ckpt} 这种伪栈名。
     * 两边不同族（例如张量键判出 SDXL）时以硬证据为准，通用名只留在判据里。
     */
    private static BaseModel attribute(String baseModel, String source, String evidence, String name, String path) {
        return attribute(baseModel, source, evidence, name, path, "");
    }

    private static BaseModel attribute(String baseModel, String source, String evidence, String name, String path, String generic) {
        String canonical = canonicalBaseModel(baseModel);
        if (canonical.isEmpty()) {
            String stack = StackClassifier.stackOf("", name);
            if (stack.isEmpty()) stack = StackClassifier.stackOf("", StackClassifier.bareName(path));
            String inferred = StackClassifier.baseModelOfStack(stack);
            if (inferred.isEmpty()) return BaseModel.NONE;
            return new BaseModel(inferred, StackClassifier.INFERRED_SOURCE, stack, StackClassifier.INFERRED_SOURCE,
                    StackClassifier.stackBaseModelEvidence(stack));
        }
        String stack = StackClassifier.stackOfBaseModel(canonical);
        String stackSource = stack.isEmpty() ? "" : source;
        if (!StackClassifier.knownStack(stack)) {
            String byName = StackClassifier.stackOf("", name);
            if (byName.isEmpty()) byName = StackClassifier.stackOf("", StackClassifier.bareName(path));
            if (!byName.isEmpty()) stack = byName;
            stackSource = stack.isEmpty() ? "" : StackClassifier.INFERRED_SOURCE;
        }
        // 通用底模名与硬证据同族时，把"原底模名"并排显示出来（面板上是「底模 SD 1.5（原底模名 model.ckpt）」）。
        String shown = canonical;
        String label = StackClassifier.genericBaseModelLabel(generic);
        if (!label.isEmpty() && !stack.isEmpty()
                && stack.equals(StackClassifier.stackOfBaseModel(StackClassifier.canonicalBaseModel(generic)))) shown = label;
        return new BaseModel(shown, source, stack, stackSource, evidence);
    }

    /** 本机存在、可读的模型文件（这里只认绝对/相对路径直接指到的文件）。 */
    private static Path readableFile(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            Path file = Path.of(path.strip());
            return Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? file : null;
        } catch (Exception error) {
            return null;
        }
    }

    public synchronized BaseModel resolveBaseModel(String civitaiBaseModel, String loraName, String loraPath) {
        return resolveBaseModel(civitaiBaseModel, loraName, loraPath, null);
    }

    /**
     * 当前 Forge 预设栈的底模，作为"识别不出来"时的兜底（肯定标成推断）。没有预设就是"没有"。
     *
     * <p>预设**没配检查点**时就只剩一个栈、没有底模名：这时补该栈的规范底模名
     * （{@link StackClassifier#baseModelOfStack(String)}，判据写成"按 <栈> 推断底模"）；
     * 栈也认不出来才退回原样用预设名。
     */
    public synchronized BaseModel presetBaseModel() {
        JsonObject preset = activePresetDefaults();
        String presetName = Json.str(preset, "preset", "");
        String checkpoint = Json.str(preset, "checkpoint", "");
        if (firstNonBlank(checkpoint, presetName).strip().isEmpty()) return BaseModel.NONE;
        String stack = StackClassifier.stackOfPreset(presetName);
        String inferred = checkpoint.isBlank() ? StackClassifier.baseModelOfStack(stack) : "";
        String name = checkpoint.isBlank() ? (inferred.isEmpty() ? presetName : inferred) : checkpoint;
        if (name.isBlank()) return BaseModel.NONE;
        boolean fromStack = !inferred.isEmpty() && inferred.equals(name);
        return new BaseModel(name, fromStack ? StackClassifier.INFERRED_SOURCE : PRESET_SOURCE, stack,
                stack.isEmpty() ? "" : StackClassifier.INFERRED_SOURCE,
                fromStack ? StackClassifier.stackBaseModelEvidence(stack)
                        : "按当前 Forge 预设 " + presetName + " 推断（没有更硬的实据）");
    }

    /**
     * 当前 Forge 预设栈的推荐参数（preset/checkpoint/sampler/scheduler/steps/cfg/distilledCfg/width/height）。
     *
     * <p>以 {@code /sdapi/v1/options} 为准（实测这台 Forge Neo 的 options 里就有 {@code forge_preset}
     * 与 {@code <preset>_t2i_*}），SD 离线读不到时退回它自己的 {@code config.json}。只放进**真的给了值**的键：
     * Anima 的 {@code width/height} 是 0（意思是"用当前值"），调用方要能逐项回退到机器人当前设置。
     */
    public synchronized JsonObject activePresetDefaults() {
        JsonObject options;
        try { options = options(); } catch (Exception ignored) { options = new JsonObject(); }
        JsonObject saved = forgeConfig();
        String preset = firstNonBlank(Json.str(options, "forge_preset", ""), Json.str(saved, "forge_preset", "")).strip();
        JsonObject result = new JsonObject();
        if (preset.isEmpty()) return result;
        result.addProperty("preset", preset);
        addPresetValue(result, "checkpoint", options, saved, "forge_checkpoint_" + preset);
        addPresetValue(result, "sampler", options, saved, preset + "_t2i_sampler");
        addPresetValue(result, "scheduler", options, saved, preset + "_t2i_scheduler");
        addPresetValue(result, "steps", options, saved, preset + "_t2i_step");
        addPresetValue(result, "cfg", options, saved, preset + "_t2i_cfg");
        addPresetValue(result, "distilledCfg", options, saved, preset + "_t2i_dcfg");
        addPresetValue(result, "width", options, saved, preset + "_t2i_width");
        addPresetValue(result, "height", options, saved, preset + "_t2i_height");
        return result;
    }

    /** 预设里没配的键不要放进来（空串与数值 0 都算"没配"），让调用方逐项回退。 */
    private static void addPresetValue(JsonObject target, String targetKey, JsonObject primary, JsonObject secondary, String key) {
        JsonElement value = primary.has(key) ? primary.get(key) : secondary.get(key);
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString() && primitive.getAsString().isBlank()) return;
            if (primitive.isNumber() && primitive.getAsDouble() == 0) return;
        }
        target.add(targetKey, value.deepCopy());
    }

    /**
     * 一份样式的模型参数快照（写进 {@code data/local-styles.json} 的 model 字段）。
     *
     * <p>底模用调用方识别出来的那个（safetensors 头部 → Civitai 记录 → Forge 元数据）；识别不出来就按当前
     * Forge 预设栈推断，来源标成 {@code preset-inferred}——**绝不编造**底模名。同时记下**归属栈**
     * （{@code stack} / {@code stackSource}），载入样式时才能如实提示"这份样式属于别的栈"。
     * 采样方法/调度器/步数/CFG/Shift(蒸馏 CFG)/尺寸取当前预设栈的推荐参数，预设里没有的项再逐项回退到
     * 机器人当前设置。
     *
     * <p>只有和当前栈**对得上**的底模才写成 {@code checkpoint}：写别的栈的检查点，载入样式后出的是全灰废图。
     */
    public synchronized JsonObject styleModelParams(String baseModel, String baseModelSource) {
        JsonObject result = new JsonObject();
        JsonObject preset = activePresetDefaults();
        String presetName = Json.str(preset, "preset", "");
        String checkpoint = Json.str(preset, "checkpoint", "");
        String recorded = usableBaseModel(baseModel);
        if (!recorded.isEmpty()) {
            String canonical = canonicalBaseModel(recorded);
            String stack = StackClassifier.stackOf(canonical, checkpoint);
            result.addProperty("baseModel", canonical);
            result.addProperty("baseModelSource", baseModelSource == null ? "" : baseModelSource.strip());
            if (!stack.isEmpty()) {
                result.addProperty("stack", stack);
                // "自成一族"的 slug 栈是按名字归一出来的，不算看到了实据：来源标成推断。
                result.addProperty("stackSource", StackClassifier.observed(baseModelSource)
                        && StackClassifier.knownStack(stack) ? baseModelSource : StackClassifier.INFERRED_SOURCE);
            }
            if (matchesStack(recorded, presetName, checkpoint, stack)) result.addProperty("checkpoint", checkpoint);
        } else if (!checkpoint.isEmpty() || !presetName.isEmpty()) {
            String name = checkpoint.isEmpty() ? presetName : checkpoint;
            result.addProperty("baseModel", name);
            result.addProperty("baseModelSource", PRESET_SOURCE);
            String stack = StackClassifier.stackOfPreset(presetName);
            if (!stack.isEmpty()) {
                result.addProperty("stack", stack);
                result.addProperty("stackSource", StackClassifier.INFERRED_SOURCE);
            }
            if (!checkpoint.isEmpty()) result.addProperty("checkpoint", checkpoint);
        }
        if (!presetName.isEmpty()) result.addProperty("forge_preset", presetName);
        JsonObject current = modelParams();
        for (String key : List.of("sampler", "scheduler", "steps", "cfg", "distilledCfg", "width", "height")) {
            JsonElement value = preset.has(key) ? preset.get(key) : current.get(key);
            if (value == null || value.isJsonNull()) continue;
            result.add(key, integralValue(key, value));
        }
        return result;
    }

    /**
     * 整数语义的数值字段（步数/宽/高）落成整数：Forge 的配置里它们可能是 {@code 32.0}（double），
     * 原样写进样式就变成 {@code "steps": 32.0}，网页「查看原文」与列表里全是没意义的 {@code .0}。
     * 数值不变（{@code Json.num} 照旧读得出来），只是 JSON 里不再有零头；别的键原样返回。
     */
    private static JsonElement integralValue(String key, JsonElement value) {
        JsonElement copy = value.deepCopy();
        if (!Set.of("steps", "width", "height").contains(key)) return copy;
        if (!copy.isJsonPrimitive() || !copy.getAsJsonPrimitive().isNumber()) return copy;
        double number = copy.getAsDouble();
        return number == Math.rint(number) ? new JsonPrimitive((long) number) : copy;
    }

    /**
     * 这个底模是不是当前预设栈的那一个。
     *
     * <p>先按**归属栈**判（识别出 {@code stack} 就够硬：Anima 配 anima 栈、SDXL 配 xl 栈）；栈判不出来时
     * 退回按名字比：Civitai 写 "Anima"、Forge 的预设名是 "anima"、检查点文件名是
     * "animaCatTower_v11.safetensors"，三种写法都要能对上，对不上就不写检查点。
     */
    private static boolean matchesStack(String baseModel, String presetName, String checkpoint, String stack) {
        if (!stack.isEmpty() && StackClassifier.matchesPreset(stack, presetName)) return true;
        String wanted = usableBaseModel(baseModel).toLowerCase(java.util.Locale.ROOT);
        if (wanted.isEmpty()) return false;
        if (!presetName.isBlank() && presetName.strip().toLowerCase(java.util.Locale.ROOT).equals(wanted)) return true;
        if (checkpoint.isBlank()) return false;
        String file = bareModel(checkpoint).toLowerCase(java.util.Locale.ROOT);
        return !file.isEmpty() && (file.equals(wanted) || file.contains(wanted) || wanted.contains(file));
    }

    /**
     * 载入样式时，样式所属的栈与当前 Forge 栈不一致就如实说一句（**不偷偷切栈**）。
     *
     * <p>样式的底模是别的栈的：参数套了也没用（底模没跟着换），得用户明确 {@code .model preset <预设>}
     * 切过去才能出这个样式的图。判不出栈的样式返回空串（不硬猜）。
     */
    public synchronized String styleStackNotice(JsonObject model) {
        if (model == null) return "";
        try {
            String stack = usableBaseModel(Json.str(model, "stack", ""));
            if (stack.isEmpty()) stack = StackClassifier.stackOf(Json.str(model, "baseModel", ""), Json.str(model, "checkpoint", ""));
            if (stack.isEmpty()) return "";
            if (!forge()) return "";
            String active = forgePreset();
            if (active.isBlank() || StackClassifier.matchesPreset(stack, active)) return "";
            String owner = Json.str(model, "forge_preset", "");
            String preset = StackClassifier.presetFor(stack, forgePresets());
            if (preset.isEmpty()) preset = owner;
            String target = preset.isEmpty() ? stack : preset;
            return "⚠ 这份样式属于" + StackClassifier.stackLabel(stack) + "（预设 " + target + "），当前是「" + active
                    + "」栈：底模没有随之切换，要出这个样式的图请先 .model preset " + target
                    + "，或在 Forge 页面把 UI Preset 切到 " + target + "。";
        } catch (Exception error) {
            return "";      // 读不到 Forge 就不猜：宁可不说，也不编一句栈警告
        }
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
        return changeSettings("采样方法", Set.of("sampler_name"), current -> current.withSampler(canonical).withSource(LOCAL_SOURCE));
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
        return changeSettings("预设样式", Set.of("styles"), current -> current.withStyles(selected).withSource(LOCAL_SOURCE));
    }

    public synchronized GenerationSettings setSize(int width, int height) throws Exception {
        validateSize(width, height);
        return changeSettings("尺寸", Set.of("width", "height"), current -> current.withSize(width, height).withSource(LOCAL_SOURCE));
    }

    /**
     * 改采样方法/尺寸/预设样式（这三项与 WebUI 页面同步）。
     *
     * @param where 入口名，只进日志——"谁改的"必须留痕
     */
    private GenerationSettings changeSettings(String where, Set<String> fields,
            java.util.function.UnaryOperator<GenerationSettings> change) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            refresh(true);
            GenerationSettings before = cachedSettings;
            GenerationSettings updated = change.apply(cachedSettings);
            if (bridgeAvailable && settingsBridgeAvailable) {
                JsonObject payload = settingsJson(updated);
                // VAE 只写机器人自己的记录：桥接扩展不认 sd_vae，把 vae 混进 PUT 会污染
                // "只发改动字段"的语义（SdSettingsTest 就在盯 keySet），所以这里一定摘掉。
                payload.remove("vae");
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
            // 日志与"变更了什么"都用桥接那一份比较（before 也是桥接来的，两边都没有 vae 字段，
            // 不会因为合并 VAE 而每次改动都多报一行"VAE 变了"）。
            logSettingsChange(where, before, updated);
            // 桥接回读里没有 vae（它不在桥接的字段表里）：合并回机器人自己记的那一份，
            // 否则 setSampler/setSize 的返回值会把 VAE 显示成"没设过"。
            updated = updated.withVae(activeVae);
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
        // 调度器与蒸馏 CFG 以本地权威值为准（桥接刷新会把 settings 里那两个字段冲掉）。
        settings = settings.withForge(activeScheduler, activeDistilledCfg);
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
        // Forge Neo 的 anima / flux 这类流匹配模型靠这两个参数：调度器（beta/simple/normal）与
        // 蒸馏 CFG（界面上叫 Shift，Anima 推荐 3）。A1111 收下未知字段也不报错，所以只在设了时发送。
        if (!settings.scheduler().isBlank()) payload.addProperty("scheduler", settings.scheduler());
        if (settings.distilledCfg() > 0) payload.addProperty("distilled_cfg_scale", settings.distilledCfg());
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
        return new GenerationSettings(sampler, forgeScheduler(state), selected, width, height, forgeDistilledCfg(state), source,
                forgeVae(state));
    }

    /** VAE：没存过就是空串（＝没设过，老 sd-settings.json 读回来仍然兼容）。 */
    private static String forgeVae(JsonObject state) {
        JsonElement value = state.get("vae");
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    /** 调度器：没存过就是"不发送"（旧文件读回来仍然兼容）。 */
    private static String forgeScheduler(JsonObject state) {
        JsonElement value = state.get("scheduler");
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    private static double forgeDistilledCfg(JsonObject state) {
        JsonElement value = state.get("distilled_cfg");
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsDouble() : 0;
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

    /** 生成尺寸的下限（与 {@link #validateSize} 同一套规则）。 */
    public static final int MIN_GENERATION_SIZE = 64;
    /** 生成尺寸的上限（与 {@link #validateSize} 同一套规则）。 */
    public static final int MAX_GENERATION_SIZE = 2048;
    /** 生成尺寸必须是它的倍数（与 {@link #validateSize} 同一套规则）。 */
    public static final int GENERATION_SIZE_STEP = 8;
    /**
     * 样式里的尺寸要缩放时压到的**长边上限**：2048 是硬上限，但样式里的尺寸多半来自展示图
     * （Civitai 预览图动辄 2400×3744），贴着 2048 换算出来的尺寸既慢又紧贴校验边界；
     * 1536 是 SDXL 一类模型常用的工作尺寸，且长边落在 8 的倍数上，缩放后不会因为四舍五入再顶回上限。
     */
    public static final int STYLE_SIZE_LONG_SIDE = 1536;

    /** 尺寸在合法范围内吗（64–2048 且是 8 的倍数）。 */
    public static boolean validGenerationSize(int width, int height) {
        return width >= MIN_GENERATION_SIZE && width <= MAX_GENERATION_SIZE && width % GENERATION_SIZE_STEP == 0
                && height >= MIN_GENERATION_SIZE && height <= MAX_GENERATION_SIZE && height % GENERATION_SIZE_STEP == 0;
    }

    /**
     * 把样式里记着的宽高换算成**能真正用于生成的**宽高。规则（可解释、可测试）：
     * <ol>
     *   <li>两边都合法（64–2048 且 8 的倍数）：**原样返回**——用户特意设的 768×512 / 1024×1024 不许被动；</li>
     *   <li>两边都在 64–2048 之间、只是不是 8 的倍数：各自四舍五入到 8 的倍数（比例偏差 ≤0.6%）；</li>
     *   <li>有一边超出 2048（展示图尺寸就是这种）：整体等比缩放，长边压到 ≤{@value #STYLE_SIZE_LONG_SIDE}，
     *       再各自四舍五入到 8 的倍数；</li>
     *   <li>缩完有边小于 64：把比例整体放大到短边＝64（极小尺寸抬到能生成的下限）；</li>
     *   <li>放大后长边又超过 2048（极端比例，例如 100000×100）：以 2048 封顶，此时宽高比**保不住**，
     *       只能保证两边都在 64–2048 且是 8 的倍数——普通图片比例走不到这一步。</li>
     * </ol>
     * 除第 5 条的极端比例外，结果与输入同比例（偏差 ≤1%）。
     *
     * @return {@code {width, height}}；宽或高 ≤0（没有可用尺寸）时返回 null
     */
    public static int[] fitGenerationSize(int width, int height) {
        if (width <= 0 || height <= 0) return null;
        if (validGenerationSize(width, height)) return new int[]{width, height};
        if (width >= MIN_GENERATION_SIZE && width <= MAX_GENERATION_SIZE
                && height >= MIN_GENERATION_SIZE && height <= MAX_GENERATION_SIZE)
            return new int[]{snapGenerationSize(width), snapGenerationSize(height)};
        int longest = Math.max(width, height), shortest = Math.min(width, height);
        double scale = longest > STYLE_SIZE_LONG_SIDE ? (double) STYLE_SIZE_LONG_SIDE / longest : 1;
        if (shortest * scale < MIN_GENERATION_SIZE) scale = (double) MIN_GENERATION_SIZE / shortest;
        if (longest * scale > MAX_GENERATION_SIZE) scale = (double) MAX_GENERATION_SIZE / longest;
        return new int[]{snapGenerationSize((int) Math.round(width * scale)), snapGenerationSize((int) Math.round(height * scale))};
    }

    /** 四舍五入到 {@value #GENERATION_SIZE_STEP} 的倍数，再夹进 64–2048。 */
    private static int snapGenerationSize(int value) {
        int snapped = (int) Math.round(value / (double) GENERATION_SIZE_STEP) * GENERATION_SIZE_STEP;
        return Math.max(MIN_GENERATION_SIZE, Math.min(MAX_GENERATION_SIZE, snapped));
    }

    /**
     * 载入样式时那一条尺寸说明：原样套用就是「尺寸 1024×1024」，缩放过就写明原值与新值
     * （「尺寸 2400×3744 → 同比例缩到 984×1536」）——回执里必须看得出来这是换算过的。
     */
    public static String sizeAppliedText(int width, int height, int[] fitted) {
        if (fitted == null || (fitted[0] == width && fitted[1] == height)) return "尺寸 " + width + "×" + height;
        return "尺寸 " + width + "×" + height + " → 同比例缩到 " + fitted[0] + "×" + fitted[1];
    }

    private static JsonObject settingsJson(GenerationSettings settings) {
        JsonObject state = new JsonObject();
        state.addProperty("sampler_name", settings.samplerName());
        state.add("styles", Json.GSON.toJsonTree(settings.styles()));
        state.addProperty("width", settings.width());
        state.addProperty("height", settings.height());
        // 调度器与蒸馏 CFG 只在真的设了的时候落盘：老的 sd-settings.json 读回来仍是"不发送"。
        if (!settings.scheduler().isBlank()) state.addProperty("scheduler", settings.scheduler());
        if (settings.distilledCfg() > 0) state.addProperty("distilled_cfg", settings.distilledCfg());
        // VAE 同理：没设过就不写字段，老的 settings 文件读回来仍然是空串（老文件没有该字段也要能容忍）。
        if (!settings.vae().isBlank()) state.addProperty("vae", settings.vae());
        return state;
    }

    private void persist(GenerationSettings settings) throws IOException {
        JsonObject state = settingsJson(settings.withForge(activeScheduler, activeDistilledCfg).withVae(activeVae));
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

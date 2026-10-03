package cn.szu.bot.sd;

import com.google.gson.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import cn.szu.bot.Json;

/**
 * 底模**归属栈**的判定：一个底模（或一个 LoRA / 一份样式）属于 Forge 的哪一栈。
 *
 * <p>Forge／Forge Neo 把「底模 + VAE + 文本编码器」按预设分成一栈一栈（{@code anima} / {@code xl} /
 * {@code sd} / {@code flux} / {@code qwen} …）。只换底模不换栈会出全灰废图，所以「这个底模属于哪一栈」
 * 必须能自动判出来，而不是靠人去记。
 *
 * <p>判据按可靠性排序，全部来自**真实数据**，判不出来就返回空串（绝不编造）：
 * <ol>
 *   <li>{@code safetensors} 头部 {@code __metadata__} 里声明的架构/底模：{@code ss_base_model_version}
 *       （如 {@code anima}）、{@code modelspec.architecture}（如 {@code stable-diffusion-xl-v1-base}、
 *       {@code stable-diffusion-v1/lora}）、{@code ss_sd_model_name}（sd-scripts 的
 *       {@code model.safetensors} 是占位，不算）；</li>
 *   <li>头部里的**张量名结构**（同一份头部，只读前若干 KB）：SDXL 有第二个文本编码器
 *       {@code conditioner.embedders.1.*}，SD1.5 只有 {@code conditioner.embedders.0.transformer.*}，
 *       SD2 是 {@code conditioner.embedders.0.model.*}（OpenCLIP），Anima 是 {@code net.llm_adapter.*} /
 *       {@code net.blocks.*}，Flux 是 {@code double_blocks.* + single_blocks.*}，LoRA 侧则看
 *       {@code lora_te_*}（单文本编码器＝SD1.5）与 {@code lora_te1_*}/{@code lora_te2_*}（＝SDXL）；</li>
 *   <li>Forge 预设配置（{@code forge_checkpoint_<preset>}）：哪个预设置的就是这个文件，它就属于那一栈；</li>
 *   <li>文件名关键词兜底，结果必须标成推断。</li>
 * </ol>
 */
public final class StackClassifier {
    /** 五种标准栈，与 Forge 预设同名：切栈就是 {@code .model preset <栈名>}。 */
    public static final String ANIMA = "anima", XL = "xl", SD = "sd", FLUX = "flux", QWEN = "qwen";
    /** 判定来源（回执与网页 source 字段共用这一份词表）。 */
    public static final String HEADER_SOURCE = "safetensors-header";
    public static final String KEYS_SOURCE = "safetensors-keys";
    public static final String CIVITAI_SOURCE = "civitai";
    public static final String FORGE_SOURCE = "forge-metadata";
    public static final String PRESET_SOURCE = "forge-preset";
    public static final String INFERRED_SOURCE = "inferred";
    /** 头部 JSON 最多读这么多：真机最大的头部不到 400 KB，超出的当读坏了。 */
    private static final long MAX_HEADER_BYTES = 8L * 1024 * 1024;
    /** {@code __metadata__} 里按这个顺序找声明式底模。 */
    private static final List<String> METADATA_KEYS =
            List.of("ss_base_model_version", "modelspec.architecture", "ss_sd_model_name", "modelspec.importer");

    /** 一次头部读取的结论：{@code baseModel} 是规范化底模名，读不到就是空串。 */
    public record Header(String baseModel, String source, String evidence) {
        public static final Header NONE = new Header("", "", "");
        public Header {
            baseModel = baseModel == null ? "" : baseModel.strip();
            source = source == null ? "" : source.strip();
            evidence = evidence == null ? "" : evidence.strip();
        }
        public boolean known() { return !baseModel.isEmpty(); }
    }

    private StackClassifier() { }

    // ---------------------------------------------------------------- 对外判定

    /**
     * 读一个 {@code .safetensors} 文件的地模归属：头部 {@code __metadata__} 声明优先，其次张量名结构。
     * 只读文件开头的头部（8 字节长度 + N 字节 JSON），不加载权重；读不到返回空串。
     */
    public static String baseModelOf(Path safetensors) {
        Header declared = declared(safetensors);
        if (declared.known()) return declared.baseModel();
        return structural(safetensors).baseModel();
    }

    /** 头部 {@code __metadata__} 里**声明**的底模（最可靠的一条）。 */
    public static Header declared(Path safetensors) { return parse(safetensors).declared(); }

    /** 头部**张量名结构**推断出的底模（同一份头部；声明式元数据缺失时的第二条判据）。 */
    public static Header structural(Path safetensors) { return parse(safetensors).structural(); }

    /**
     * 底模名（或兜底的文件名）→ 栈名：{@code anima} / {@code xl} / {@code sd} / {@code flux} / {@code qwen}，
     * 判不出来是空串。关键词表覆盖 anima、illustrious、noobai、noob、pony、sdxl、xl、sd 1.5、sd1.5、
     * sd_v1、sd 2、flux、qwen。
     */
    public static String stackOf(String baseModel, String filename) {
        String stack = stackOfBaseModel(baseModel);
        return stack.isEmpty() ? stackOfFilename(filename) : stack;
    }

    /** 只按底模名判栈（识别出来的底模名优先用它，别被文件名带偏）。 */
    public static String stackOfBaseModel(String baseModel) {
        String key = normalize(baseModel);
        if (key.isEmpty()) return "";
        switch (key) {
            case "anima": return ANIMA;
            case "xl", "sdxl": return XL;
            case "sd", "sd15", "sd 1.5", "sd 1", "sd v1": return SD;
            case "sd21", "sd 2.1", "sd 2": return SD;
            case "flux", "flux.1": return FLUX;
            case "qwen", "qwen image": return QWEN;
            default: break;
        }
        if (key.contains("anima")) return ANIMA;
        if (key.contains("flux")) return FLUX;
        if (key.contains("qwen")) return QWEN;
        // SDXL 家族（Illustrious / NoobAI / Pony 都是 SDXL 架构，走的都是 xl 这一栈）
        if (key.contains("illustrious") || key.contains("noob") || key.contains("pony")
                || key.contains("sdxl") || key.contains("sd xl") || key.contains("stable diffusion xl")) return XL;
        if (key.contains("sd 1.5") || key.contains("sd1.5") || key.contains("sd15") || key.contains("sd v1")
                || key.contains("sd_v1") || key.contains("stable diffusion 1.5") || key.contains("stable diffusion v1")) return SD;
        if (key.contains("sd 2.1") || key.contains("sd2.1") || key.contains("sd21")
                || key.contains("sd 2") || key.contains("stable diffusion 2")) return SD;
        return "";
    }

    /** 只按文件名判栈（兜底；结果要标成推断）。 */
    public static String stackOfFilename(String filename) {
        String key = normalize(filename);
        if (key.isEmpty()) return "";
        if (key.contains("anima")) return ANIMA;
        if (key.contains("flux")) return FLUX;
        if (key.contains("qwen")) return QWEN;
        if (key.contains("illustrious") || key.contains("noobai") || key.contains("noob")
                || key.contains("pony") || key.contains("sdxl") || key.contains("sd xl")
                || key.contains("sd_xl") || key.contains(" xl")) return XL;
        if (key.contains("sd15") || key.contains("sd 1.5") || key.contains("sd1.5")
                || key.contains("sd v1") || key.contains("sd_v1")) return SD;
        if (key.contains("sd21") || key.contains("sd 2.1") || key.contains("sd 2")) return SD;
        return "";
    }

    /**
     * Forge 预设名 → 栈名：五种标准栈按同名/别名归一；其他预设（klein / lumina / zit / wan / ernie …）
     * 本机也有自己的一栈，原样当栈名返回，别硬塞进五种里。
     */
    public static String stackOfPreset(String preset) {
        String value = preset == null ? "" : preset.strip();
        if (value.isEmpty()) return "";
        String known = stackOfBaseModel(value);
        return known.isEmpty() ? value : known;
    }

    /** 栈 → 实际存在的预设名（没有同名/同义预设时返回空串，让调用方给出「在 Forge 页面切到 <栈>」的提示）。 */
    public static String presetFor(String stack, Collection<String> presets) {
        String wanted = stack == null ? "" : stack.strip();
        if (wanted.isEmpty() || presets == null || presets.isEmpty()) return "";
        List<String> candidates = new ArrayList<>();
        candidates.add(wanted);
        candidates.addAll(aliases(wanted));
        for (String candidate : candidates) {
            for (String preset : presets) {
                if (preset != null && preset.strip().equalsIgnoreCase(candidate)) return preset.strip();
            }
        }
        return "";
    }

    /** 这一栈在当前预设列表里是不是就是某个预设（切栈在 Forge 上＝切预设）。 */
    public static boolean matchesPreset(String stack, String preset) {
        String wanted = stack == null ? "" : stack.strip();
        String active = preset == null ? "" : preset.strip();
        if (wanted.isEmpty() || active.isEmpty()) return false;
        if (wanted.equalsIgnoreCase(active)) return true;
        return stackOfPreset(active).equalsIgnoreCase(wanted) || stackOfBaseModel(active).equalsIgnoreCase(wanted);
    }

    /** 栈的中文说法（组头与警告里用）。 */
    public static String stackLabel(String stack) {
        String value = stack == null ? "" : stack.strip();
        return switch (value.toLowerCase(Locale.ROOT)) {
            case ANIMA -> "Anima 栈";
            case XL -> "SDXL 栈";
            case SD -> "SD 1.5 栈";
            case FLUX -> "Flux 栈";
            case QWEN -> "Qwen 栈";
            default -> value.isEmpty() ? "" : value + " 栈";
        };
    }

    /** 判定来源的中文说法（与网页/回执同一个词表）。 */
    public static String sourceLabel(String source) {
        return switch (source == null ? "" : source) {
            case HEADER_SOURCE -> "safetensors 头部元数据";
            case KEYS_SOURCE -> "safetensors 张量结构";
            case CIVITAI_SOURCE -> "Civitai 记录";
            case FORGE_SOURCE -> "Forge 元数据";
            case PRESET_SOURCE -> "Forge 预设配置";
            case INFERRED_SOURCE -> "按文件名/当前预设推断";
            case "preset-inferred" -> "按当前预设推断";
            default -> source == null ? "" : source;
        };
    }

    /** 识别出来的来源算「看到了实据」，推断出来的要显式标成推断。 */
    public static boolean observed(String source) {
        return HEADER_SOURCE.equals(source) || KEYS_SOURCE.equals(source)
                || CIVITAI_SOURCE.equals(source) || FORGE_SOURCE.equals(source) || PRESET_SOURCE.equals(source);
    }

    /** 各来源对同一个底模的写法不一样（Civitai 写 Anima，Forge 写 anima，架构串写 stable-diffusion-xl-v1-base）。 */
    public static String canonicalBaseModel(String raw) {
        String value = SdClient.usableBaseModel(raw);
        if (value.isEmpty()) return "";
        String key = normalize(value);
        // 先按整词归一（Civitai/Forge 的常见写法），再按关键词族兜底（架构串）。
        switch (key) {
            case "anima": return "Anima";
            case "sd15", "sd 1.5", "sd 1", "sd v1", "stable diffusion 1.5", "stable diffusion v1": return "SD 1.5";
            case "sd21", "sd 2.1", "sd 2", "stable diffusion 2.1": return "SD 2.1";
            case "sdxl", "sd xl", "sdxl 1.0", "stable diffusion xl": return "SDXL";
            case "illustrious": return "Illustrious";
            case "noobai", "noobai xl": return "NoobAI";
            case "pony": return "Pony";
            case "flux", "flux.1", "flux 1": return "Flux";
            case "qwen", "qwen image": return "Qwen Image";
            default: break;
        }
        if (key.contains("anima")) return "Anima";
        if (key.contains("flux")) return "Flux";
        if (key.contains("qwen")) return "Qwen Image";
        if (key.contains("illustrious")) return "Illustrious";
        if (key.contains("noob")) return "NoobAI";
        if (key.contains("pony")) return "Pony";
        if (key.contains("sdxl") || key.contains("sd xl") || key.contains("stable diffusion xl")) return "SDXL";
        if (key.contains("sd 1.5") || key.contains("sd1.5") || key.contains("sd15") || key.contains("sd v1")
                || key.contains("stable diffusion 1.5") || key.contains("stable diffusion v1")) return "SD 1.5";
        if (key.contains("sd 2.1") || key.contains("sd2.1") || key.contains("sd21")
                || key.contains("sd 2") || key.contains("stable diffusion 2")) return "SD 2.1";
        return value;
    }

    /** 去目录、去 {@code [哈希]} 后缀，只留文件名（比对预设里的检查点名用）。 */
    public static String bareName(String value) {
        String text = value == null ? "" : value.strip().replace('\\', '/');
        int slash = text.lastIndexOf('/');
        if (slash >= 0) text = text.substring(slash + 1);
        int mark = text.indexOf(" [");
        if (mark > 0) text = text.substring(0, mark);
        return text.strip();
    }

    /** 两个写法的底模是不是同一个文件（去目录、去扩展名、忽略大小写；一边是另一边的短前缀时也算）。 */
    public static boolean sameModel(String left, String right) {
        String a = stem(left), b = stem(right);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        // 前缀只在前者够长时才认：名字太短（"sd"）会把别的模型（"sdxl"）误判成同一个。
        return shorter.length() >= 8 && longer.startsWith(shorter);
    }

    private static String stem(String value) {
        String text = bareName(value).toLowerCase(Locale.ROOT);
        return text.replaceFirst("\\.safetensors$", "").strip();
    }

    private static List<String> aliases(String stack) {
        return switch (stack.toLowerCase(Locale.ROOT)) {
            case XL -> List.of("sdxl", "sd_xl", "illustrious", "noobai", "noob", "pony");
            case SD -> List.of("sd15", "sd1.5", "sd_1.5", "sd-1.5", "v1", "sd1");
            case FLUX -> List.of("flux1", "flux.1");
            case QWEN -> List.of("qwen_image", "qwen-image", "qwenimage");
            default -> List.of();
        };
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.strip().toLowerCase(Locale.ROOT)
                .replace('_', ' ').replace('-', ' ').replace('/', ' ').replace('\\', ' ')
                .replaceAll("\\s+", " ").strip();
    }

    // ---------------------------------------------------------------- safetensors 头部

    /** 一次头部读取的全部结论（互相独立，调用方按优先级取用）。 */
    private record Parsed(Header declared, Header structural) {
        static final Parsed NONE = new Parsed(Header.NONE, Header.NONE);
    }

    /** 头部缓存：键含修改时间与大小，文件换了自动失效（网页面板会反复读同一批 LoRA）。 */
    private static final Map<String, Parsed> CACHE = new ConcurrentHashMap<>();

    private static Parsed parse(Path file) {
        if (file == null) return Parsed.NONE;
        try {
            Path absolute = file.toAbsolutePath().normalize();
            if (!Files.isRegularFile(absolute, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Parsed.NONE;
            String key = absolute + "|" + Files.size(absolute) + "|" + Files.getLastModifiedTime(absolute).toMillis();
            Parsed cached = CACHE.get(key);
            if (cached != null) return cached;
            Parsed parsed = read(absolute);
            if (CACHE.size() > 512) CACHE.clear();
            CACHE.put(key, parsed);
            return parsed;
        } catch (Exception error) {
            return Parsed.NONE;    // 读不到就当没有：绝不因为读文件失败而编一个底模
        }
    }

    private static Parsed read(Path file) {
        JsonObject header = header(file);
        if (header == null) return Parsed.NONE;
        return new Parsed(declaredIn(header), structuralIn(header));
    }

    /** 读文件开头的 safetensors 头部（8 字节小端长度 + N 字节 JSON），失败返回 null。 */
    private static JsonObject header(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] length = input.readNBytes(8);
            if (length.length < 8) return null;
            long size = 0;
            for (int index = 7; index >= 0; index--) size = (size << 8) | (length[index] & 0xFFL);
            if (size <= 2 || size > MAX_HEADER_BYTES) return null;
            byte[] body = input.readNBytes((int) size);
            if (body.length < (int) size) return null;      // 头部被截断：不是合法的 safetensors
            JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception error) {
            return null;    // 不是 safetensors（普通文件、文字占位、NFT 分片）都走这里
        }
    }

    /** {@code __metadata__} 里声明的底模。 */
    private static Header declaredIn(JsonObject header) {
        if (!header.has("__metadata__") || !header.get("__metadata__").isJsonObject()) return Header.NONE;
        JsonObject metadata = header.getAsJsonObject("__metadata__");
        for (String key : METADATA_KEYS) {
            String raw = Json.str(metadata, key, "");
            if (SdClient.usableBaseModel(raw).isEmpty()) continue;   // 占位值（model.safetensors）不算
            String canonical = canonicalBaseModel(raw);
            if (canonical.isEmpty()) continue;
            return new Header(canonical, HEADER_SOURCE, "__metadata__ " + key + "=" + raw.strip());
        }
        return Header.NONE;
    }

    /**
     * 张量名结构：同一份头部里的键名就够判架构（等于「不加载权重也知道这是什么模型」）。
     * 只看前缀，2 千多个键扫一遍是微秒级。
     */
    private static Header structuralIn(JsonObject header) {
        boolean embedder1 = false, embedder0Model = false, embedder0Transformer = false;
        boolean condModel = false, condTransformer = false;
        boolean llmAdapter = false, netBlocks = false, animaLoraBlocks = false;
        boolean doubleBlocks = false, singleBlocks = false, ldmUnet = false;
        boolean loraTe = false, loraTe1 = false, loraTe2 = false;
        for (String key : header.keySet()) {
            if (key.equals("__metadata__")) continue;
            if (key.startsWith("conditioner.embedders.1.")) embedder1 = true;
            else if (key.startsWith("conditioner.embedders.0.model.")) embedder0Model = true;
            else if (key.startsWith("conditioner.embedders.0.transformer.")) embedder0Transformer = true;
            else if (key.startsWith("cond_stage_model.model.")) condModel = true;
            else if (key.startsWith("cond_stage_model.transformer.")) condTransformer = true;
            else if (key.startsWith("net.llm_adapter.")) llmAdapter = true;
            else if (key.startsWith("net.blocks.")) netBlocks = true;
            else if (key.startsWith("diffusion_model.blocks.")) animaLoraBlocks = true;
            else if (key.startsWith("double_blocks.")) doubleBlocks = true;
            else if (key.startsWith("single_blocks.")) singleBlocks = true;
            else if (key.startsWith("model.diffusion_model.input_blocks.")) ldmUnet = true;
            else if (key.startsWith("lora_te2")) loraTe2 = true;
            else if (key.startsWith("lora_te1")) loraTe1 = true;
            else if (key.startsWith("lora_te_")) loraTe = true;
        }
        boolean sd2 = isV2(header);
        // 顺序＝专指度：Anima 与 Flux 的键名很独特，先判；再判文本编码器个数区分 SDXL / SD1.5 / SD2。
        if (llmAdapter || netBlocks || animaLoraBlocks) {
            List<String> signals = new ArrayList<>();
            if (llmAdapter) signals.add("net.llm_adapter.*");
            if (netBlocks) signals.add("net.blocks.*");
            if (animaLoraBlocks) signals.add("diffusion_model.blocks.*");
            return new Header("Anima", KEYS_SOURCE, "张量键 " + String.join("、", signals) + "（Anima／NextDiT 结构）");
        }
        if (doubleBlocks && singleBlocks)
            return new Header("Flux", KEYS_SOURCE, "张量键 double_blocks.* + single_blocks.*（Flux 结构）");
        if (embedder1 || loraTe2 || loraTe1) {
            String signals = embedder1 ? "conditioner.embedders.1.*（双文本编码器）"
                    : "lora_te1_*/lora_te2_*（LoRA 带两个文本编码器）";
            return new Header("SDXL", KEYS_SOURCE, "张量键 " + signals + " → SDXL 家族");
        }
        if (embedder0Model || condModel)
            return new Header("SD 2.1", KEYS_SOURCE, "张量键 " + (embedder0Model ? "conditioner.embedders.0.model.*" : "cond_stage_model.model.*")
                    + "（OpenCLIP 文本编码器）→ SD 2.x");
        if (embedder0Transformer || condTransformer)
            return new Header("SD 1.5", KEYS_SOURCE, "张量键 " + (embedder0Transformer ? "conditioner.embedders.0.transformer.*" : "cond_stage_model.transformer.*")
                    + "（单 CLIP 文本编码器）→ SD 1.x");
        if (loraTe)
            return sd2 ? new Header("SD 2.1", KEYS_SOURCE, "张量键 lora_te_* + __metadata__ ss_v2=True")
                    : new Header("SD 1.5", KEYS_SOURCE, "张量键 lora_te_*（单文本编码器）+ ss_v2=False");
        if (ldmUnet)
            return new Header("SD 1.5", KEYS_SOURCE, "张量键 model.diffusion_model.input_blocks.*（LDM UNet，无第二个文本编码器）");
        return Header.NONE;
    }

    /** sd-scripts 的 {@code ss_v2}（True 表示 SD2.x 底模）。 */
    private static boolean isV2(JsonObject header) {
        if (!header.has("__metadata__") || !header.get("__metadata__").isJsonObject()) return false;
        return "true".equalsIgnoreCase(Json.str(header.getAsJsonObject("__metadata__"), "ss_v2", ""));
    }
}

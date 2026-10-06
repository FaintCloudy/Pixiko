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
 * {@code sd} / {@code flux} / {@code qwen} / {@code krea} / {@code sd3} …）。只换底模不换栈会出全灰废图，
 * 所以「这个底模属于哪一栈」必须能自动判出来，而不是靠人去记。
 *
 * <p>判据按可靠性排序，全部来自**真实数据**（完整的多来源优先级见
 * {@code SdClient#resolveBaseModel}，那边还多一层"下载记录里的 Civitai {@code baseModel}"）：
 * <ol>
 *   <li>{@code safetensors} 头部 {@code __metadata__} 里**声明的架构/底模**：{@code ss_base_model_version}
 *       （如 {@code anima}、{@code krea}）、{@code modelspec.architecture}（如
 *       {@code stable-diffusion-xl-v1-base}、{@code stable-diffusion-v1/lora}），以及
 *       {@code ss_sd_model_name}（sd-scripts 记的**训练底模名**，见第 3 条）；</li>
 *   <li>头部里的**张量名结构与张量形状**（同一份头部，只读前若干 KB）：SDXL 有第二个文本编码器
 *       {@code conditioner.embedders.1.*}，SD1.5 只有 {@code conditioner.embedders.0.transformer.*}，
 *       SD2 是 {@code conditioner.embedders.0.model.*}（OpenCLIP），Anima 是 {@code net.llm_adapter.*} /
 *       {@code net.blocks.*}，Flux 是 {@code double_blocks.* + single_blocks.*}；LoRA 侧看
 *       {@code lora_te_*}（单文本编码器＝SD1.5）与 {@code lora_te1_*}/{@code lora_te2_*}（＝SDXL），
 *       另外直接看**交叉注意力的上下文维度** {@code attn2_to_k/to_v} 的 shape：768＝SD1.x、1024＝SD2.x、
 *       2048＝SDXL——只训了 UNet 的 LoRA 没有文本编码器键，只有这一条判得出来；</li>
 *   <li>**通用/占位底模名**（{@code model.ckpt}、{@code Anything-v5.0-PRT-RE.safetensors}、
 *       {@code v1-5-pruned-emaonly.ckpt} …，见 {@link #SD15_GENERIC}）：它们只说明"哪个时代的底模"，
 *       那就归到那个家族（stack {@code sd}）；**绝不再拿它当栈名**（以前会变成 {@code model-ckpt}、
 *       {@code anything-v5-0-prt-re-safetensors} 这种伪栈）；</li>
 *   <li>Forge 预设配置（{@code forge_checkpoint_<preset>}）：哪个预设置的就是这个文件，它就属于那一栈；</li>
 *   <li>底模名/文件名关键词兜底（{@link #FAMILIES}），结果必须标成推断。</li>
 * </ol>
 *
 * <p>栈的判定**不再是"五种里猜一个，其余返回空"**：底模名只要非空，认不出已知族也会用它自己的
 * slug 当栈名（见 {@link #stackOfBaseModel(String)}），调用方用 {@link #knownStack(String)} 区分
 * 「已知族」与「自成一族」，后者标成 {@link #INFERRED_SOURCE}。只有真的没有底模信息（空串、占位值）
 * 才返回空串——绝不无中生有。通用名是**例外**：它有家族信息（SD1.5 时代），所以映射到 {@code sd} 栈，
 * 不参与"自成一族"。
 */
public final class StackClassifier {
    /**
     * 已知的**底模族**栈 key，与 Forge 预设同名：切栈就是 {@code .model preset <栈名>}。
     *
     * <p>前五个是 Forge 自带预设；{@code krea} 是 Flux.1 Krea 自己的一栈（Civitai 写 {@code Flux.1 Krea}，
     * 名字里含 {@code flux}，判定必须排在 flux **前面**）；{@code sd3} 是 SD 3 / 3.5；其余各族
     * （Hunyuan / Wan / Chroma / Lumina / Kolors / PixArt / Playground / Stable Cascade / Z-Image /
     * Nitro-E / ODOR）各自成一栈，见 {@link #FAMILIES}。
     */
    public static final String ANIMA = "anima", XL = "xl", SD = "sd", FLUX = "flux", QWEN = "qwen";
    public static final String KREA = "krea", SD3 = "sd3";
    public static final String HUNYUAN = "hunyuan", WAN = "wan", CHROMA = "chroma", LUMINA = "lumina",
            KOLORS = "kolors", PIXART = "pixart", PLAYGROUND = "playground", CASCADE = "cascade",
            Z_IMAGE = "z-image", NITRO = "nitro", ODOR = "odor";
    /** 名字里一个字母数字都没有时用的兜底栈（slug 会是空串，但"有底模名"就得有一个栈名）。 */
    public static final String OTHER = "other";
    /** 判定来源（回执与网页 source 字段共用这一份词表）。 */
    public static final String HEADER_SOURCE = "safetensors-header";
    public static final String KEYS_SOURCE = "safetensors-keys";
    public static final String CIVITAI_SOURCE = "civitai";
    public static final String FORGE_SOURCE = "forge-metadata";
    public static final String PRESET_SOURCE = "forge-preset";
    public static final String INFERRED_SOURCE = "inferred";
    /** 头部 JSON 最多读这么多：真机最大的头部不到 400 KB，超出的当读坏了。 */
    private static final long MAX_HEADER_BYTES = 8L * 1024 * 1024;
    /**
     * {@code __metadata__} 里**声明架构/底模族**的键，按这个顺序找：这几个说的是"这是什么架构"，
     * 比"训练时用的底模文件名"硬。
     */
    private static final List<String> METADATA_KEYS =
            List.of("ss_base_model_version", "modelspec.architecture", "ss_sd_model_name", "modelspec.importer");

    /**
     * **通用底模名**（SD1.5 时代最常见的那些"俗名"）：它们指向**家族**，不是某一个具体检查点。
     *
     * <p>实测（本机 {@code F:\sd\sd-webui-forge-neo\models\Lora} 的 12 个 LoRA）：老 LoRA 的
     * {@code __metadata__.ss_sd_model_name} 大量是 {@code model.ckpt}（2023 年那批 sd-scripts 训练时
     * 底模就叫这个名字），社区底模则是 {@code Anything-v5.0-PRT-RE.safetensors} 这类写法。它们既不是
     * 已知族关键词、又不是 {@link SdClient#usableBaseModel(String)} 认的占位值，于是被
     * {@link #slug(String)} 做成了 {@code model-ckpt} / {@code anything-v5-0-prt-re-safetensors}
     * 这种**伪栈**——面板上"所有 model.ckpt 的 LoRA 各自成一栈"就是这么来的。
     *
     * <p>匹配规则：**词首**匹配（{@code anything v5.0 prt re.safetensors} 命中 {@code anything}，
     * {@code my anything model} 不命中），并且**名字里带 {@code xl} 的一律不认**
     * （{@code AnythingXL} / {@code CounterfeitXL} / {@code ponyXL} 都是 SDXL，绝不能落进 SD1.5）。
     * 已知族关键词（{@link #FAMILIES}）永远先判：这里只是"比 slug 强一点"的最后一级。
     *
     * <p>注意 {@code model} / {@code model.safetensors}（**裸名**，没有 {@code .ckpt}）不在这里：
     * 它们是 {@link SdClient#usableBaseModel(String)} 认的**占位值**（"底模没记下来"），
     * 归到"没有底模信息"；{@code model.ckpt} 才是 SD1.5 时代那个真实的检查点文件名。
     */
    private static final List<String> SD15_GENERIC = List.of(
            "model.ckpt", "v1 5", "v1.5", "sd v1 5", "sd v1.5",
            "anything", "counterfeit", "naifu", "abyssorangemix", "aom2", "aom3", "orangemix",
            "meinamix", "chilloutmix", "majicmix", "pastel mix", "perfectworld", "hassaku",
            "7th anime", "7th love", "waifu diffusion", "dreamlike", "deliberate", "berrymix",
            "dark sushi mix", "cetusmix", "kohaku", "mistoon", "anylora", "blue pencil",
            "cyberrealistic", "realistic vision", "dalcefo", "expmix", "kotosmix", "yabal",
            "elldreth", "holyxyz", "hoshimix", "bluemix", "brav6", "cutemix", "aingdiffusion");
    /** SD1.4 时代的通用名（同样是 sd 栈，只是规范名写 SD 1.4）。 */
    private static final List<String> SD14_GENERIC = List.of("v1 4", "v1.4");

    /** 一个底模族的定义：栈 key、中文标签、给外人看的规范名、判定关键词（子串）与整词。 */
    private record Family(String stack, String label, String canonical, List<String> keywords, List<String> tokens) {
        Family(String stack, String label, String canonical, List<String> keywords) {
            this(stack, label, canonical, keywords, List.of());
        }
    }

    /**
     * **底模族判定表**：顺序＝优先级，从上往下一个一个试，命中即返回。
     *
     * <p>关键词分两种：{@code keywords} 按**子串**匹配（illustrious / noob / sdxl 这类长词拼在文件名里
     * 也认，如 {@code waiIllustriousSDXL_v170.safetensors}）；{@code tokens} 按**整词**匹配
     * （{@code xl} / {@code sd} / {@code wan} 这类短词当子串会把 {@code wandering}、{@code waiXlabs}
     * 之类的名字带偏）。表里没有的族不硬塞：{@link #stackOfBaseModel(String)} 用名字自己的 slug 当栈名。
     */
    private static final List<Family> FAMILIES = List.of(
            // Krea 必须排在 Flux 前面：Flux.1 Krea 里含 flux，反了就把 Krea 判成 flux 栈。
            new Family(KREA, "Krea 栈", "Flux.1 Krea", List.of("krea")),
            new Family(ANIMA, "Anima 栈", "Anima", List.of("anima")),
            new Family(FLUX, "Flux 栈", "Flux", List.of("flux")),
            new Family(QWEN, "Qwen 栈", "Qwen Image", List.of("qwen")),
            // SD 3 / 3.5 是独立架构（MMDiT），不能混进 sd（SD1.x/SD2.x）那一栈。
            new Family(SD3, "SD3 栈", "SD 3.5", List.of("sd 3", "sd3", "stable diffusion 3")),
            // SDXL 家族：Pony（V6 及以前）/ Illustrious / NoobAI / Animagine XL / Nova Anime XL 都是 SDXL 架构。
            new Family(XL, "SDXL 栈", "SDXL",
                    List.of("illustrious", "noob", "pony", "sdxl", "sd xl", "stable diffusion xl"), List.of("xl")),
            // SD1.x / SD2.x 共用 sd 栈（Forge 的 sd 预设就是这两代的底模）。
            new Family(SD, "SD 1.5 栈", "SD 1.5",
                    List.of("sd 1", "sd1", "sd v1", "stable diffusion 1", "stable diffusion v1",
                            "sd 2", "sd2", "stable diffusion 2"), List.of("sd")),
            new Family(HUNYUAN, "Hunyuan 栈", "Hunyuan", List.of("hunyuan")),
            new Family(WAN, "Wan 栈", "Wan Video", List.of(), List.of("wan", "wan video")),
            new Family(CHROMA, "Chroma 栈", "Chroma", List.of("chroma")),
            new Family(LUMINA, "Lumina 栈", "Lumina", List.of("lumina")),
            new Family(KOLORS, "Kolors 栈", "Kolors", List.of("kolors")),
            new Family(PIXART, "PixArt 栈", "PixArt", List.of("pixart")),
            new Family(PLAYGROUND, "Playground 栈", "Playground", List.of("playground")),
            new Family(CASCADE, "Stable Cascade 栈", "Stable Cascade", List.of("cascade")),
            new Family(Z_IMAGE, "Z-Image 栈", "Z-Image", List.of("z image", "zimage")),
            new Family(NITRO, "Nitro 栈", "Nitro-E", List.of("nitro")),
            new Family(ODOR, "ODOR 栈", "ODOR", List.of("odor")));

    /**
     * 名字里含这些串的，架构**无法确证**：不许硬塞进 xl，走"自成一族"（栈名＝slug，如 {@code pony-v7}）。
     * Pony V6 及以前是 SDXL（归 xl），Pony V7 换了底模，不能再当 SDXL 用。
     */
    private static final List<String> UNCONFIRMED = List.of("pony v7", "ponyv7", "pony 7");

    /** 底模名的规范写法（**整词优先**，键是 {@link #normalize(String)} 之后的名字）。 */
    private static final Map<String, String> CANONICAL = canonicalTable();

    private static Map<String, String> canonicalTable() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("anima", "Anima");
        for (String name : List.of("krea", "flux krea", "flux 1 krea", "flux1 krea", "flux.1 krea",
                "flux 1 krea dev", "flux.1 krea dev", "flux1 krea dev")) table.put(name, "Flux.1 Krea");
        // Civitai 上另有一个 "Krea 2" 底模（同名族，仍是 krea 栈）。
        for (String name : List.of("krea 2", "krea2", "krea 2 turbo")) table.put(name, "Krea 2");
        for (String name : List.of("flux", "flux.1", "flux 1", "flux1", "flux 1 dev", "flux.1 dev", "flux1 dev",
                "flux 1 s", "flux.1 s", "flux1 s", "flux 1 d", "flux.1 d", "flux1 d",
                "flux 1 schnell", "flux.1 schnell", "flux1 schnell",
                "flux 1 kontext", "flux.1 kontext", "flux1 kontext", "flux kontext")) table.put(name, "Flux");
        for (String name : List.of("qwen", "qwen image", "qwen image edit", "qwen image edit 2509",
                "qwen image 2509", "qwen image edit plus")) table.put(name, "Qwen Image");
        for (String name : List.of("sd15", "sd 1.5", "sd 1", "sd1", "sd v1", "sd 1.5 lcm", "sd 1.5 hyper",
                "sd 1.5 dmd2", "sd 1.5 inpainting", "sd 1.5 base", "stable diffusion 1.5",
                "stable diffusion v1")) table.put(name, "SD 1.5");
        for (String name : List.of("sd14", "sd 1.4", "stable diffusion 1.4")) table.put(name, "SD 1.4");
        for (String name : List.of("sd21", "sd 2.1", "sd 2.1 768", "sd 2.1 unclip", "sd 2.1 base",
                "stable diffusion 2.1")) table.put(name, "SD 2.1");
        for (String name : List.of("sd20", "sd 2.0", "sd 2.0 768", "sd 2", "stable diffusion 2",
                "stable diffusion 2.0")) table.put(name, "SD 2.0");
        for (String name : List.of("sdxl", "sd xl", "xl", "sdxl 0.9", "sdxl 1.0", "sdxl base", "sdxl base 1.0",
                "sdxl refiner", "stable diffusion xl", "stable diffusion xl 1.0")) table.put(name, "SDXL");
        for (String name : List.of("illustrious", "illustrious xl")) table.put(name, "Illustrious");
        for (String name : List.of("noobai", "noobai xl", "noob ai")) table.put(name, "NoobAI");
        for (String name : List.of("pony", "pony v6", "pony v6 xl")) table.put(name, "Pony");
        for (String name : List.of("pony v7", "ponyv7", "pony 7")) table.put(name, "Pony V7");
        for (String name : List.of("animagine xl", "animagine xl 3.0", "animagine xl 3.1")) table.put(name, "Animagine XL");
        for (String name : List.of("nova anime xl", "nova anime xl 8.0")) table.put(name, "Nova Anime XL");
        for (String name : List.of("sd 3", "sd3", "stable diffusion 3")) table.put(name, "SD 3");
        for (String name : List.of("sd 3.5", "sd3.5", "sd35", "sd 3.5 large", "sd 3.5 large turbo",
                "sd 3.5 medium", "stable diffusion 3.5")) table.put(name, "SD 3.5");
        for (String name : List.of("hunyuan", "hunyuan 1", "hunyuan dit", "hunyuan video")) table.put(name, "Hunyuan");
        for (String name : List.of("wan", "wan video", "wan 2.1", "wan 2.2")) table.put(name, "Wan Video");
        table.put("chroma", "Chroma");
        table.put("lumina", "Lumina");
        table.put("kolors", "Kolors");
        for (String name : List.of("pixart", "pixart a", "pixart e", "pixart alpha", "pixart sigma")) table.put(name, "PixArt");
        for (String name : List.of("playground", "playground v2")) table.put(name, "Playground");
        for (String name : List.of("cascade", "stable cascade")) table.put(name, "Stable Cascade");
        for (String name : List.of("z image", "zimage", "z image turbo", "zimage turbo")) table.put(name, "Z-Image");
        for (String name : List.of("nitro", "nitro e", "nitro-e")) table.put(name, "Nitro-E");
        table.put("odor", "ODOR");
        return Map.copyOf(table);
    }

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
     * 读一个 {@code .safetensors} 文件的地模归属：头部 {@code __metadata__} 声明优先，其次张量名结构，
     * 最后才是**通用底模名**（{@code model.ckpt} 这种，映射到 SD1.5 家族）；读不到返回空串。
     * 只读文件开头的头部（8 字节长度 + N 字节 JSON），不加载权重。
     */
    public static String baseModelOf(Path safetensors) {
        Header declared = declared(safetensors);
        boolean generic = genericBaseModel(declared.baseModel());
        if (declared.known() && !generic) return canonicalBaseModel(declared.baseModel());
        // 通用底模名（model.ckpt）不可信：先看张量键与形状，再退回"按通用名归家族"。
        String structural = structural(safetensors).baseModel();
        if (!structural.isEmpty()) return structural;
        return declared.known() ? canonicalBaseModel(declared.baseModel()) : "";
    }

    /**
     * 头部 {@code __metadata__} 里**声明**的底模（最可靠的一条）。
     *
     * <p>只读到**通用底模名**（{@code model.ckpt} / {@code Anything-v5}）时**原样**返回那个名字
     * （不归一成家族），调用方用 {@link #genericBaseModel(String)} 认出来并降级处理；
     * 读到具体架构就返回规范名（{@code SDXL} / {@code Anima} …）。
     */
    public static Header declared(Path safetensors) { return parse(safetensors).declared(); }

    /**
     * 头部**张量名结构与张量形状**推断出的底模（同一份头部；声明式元数据缺失或只是通用名时的第二条判据）。
     */
    public static Header structural(Path safetensors) { return parse(safetensors).structural(); }

    /**
     * 底模名（或兜底的文件名）→ 栈名：{@code anima} / {@code xl} / {@code sd} / {@code flux} / {@code qwen} /
     * {@code krea} / {@code sd3} / {@code hunyuan} / {@code wan} / …（见 {@link #FAMILIES}）。
     * 底模名非空时**一定**有一个栈；只有真的没有底模信息（空串、占位值）时才空串。
     */
    public static String stackOf(String baseModel, String filename) {
        String stack = stackOfBaseModel(baseModel);
        return stack.isEmpty() ? stackOfFilename(filename) : stack;
    }

    /**
     * 只按底模名判栈（识别出来的底模名优先用它，别被文件名带偏）。任何**有内容**的底模名都会得到一个栈：
     * <ol>
     *   <li>已知族按 {@link #FAMILIES} 判：{@code krea} 排在 {@code flux} 前面，{@code Flux.1 Krea} 不会被
     *       判成 flux 栈；{@code SD 1.5} / {@code SDXL} / {@code SD 2.1} / {@code SD 3.5} 各归各的；</li>
     *   <li>**通用底模名**（{@code model.ckpt} / {@code Anything-v5} / {@code v1-5-pruned-emaonly} …）：
     *       归它指的那个家族——{@code sd} 栈（见 {@link #SD15_GENERIC}）——**不许**变成
     *       {@code model-ckpt} 这种伪栈；</li>
     *   <li>认不出族但名字非空：用**规范化 slug** 当栈名（{@code Foo BarXL v2} → {@code foo-barxl-v2}）——
     *       分不到已知族，也要有一个明确、可复现的栈名；{@link #knownStack(String)} 能认出这种"自成一族"，
     *       调用方据此把来源标成 {@link #INFERRED_SOURCE}；</li>
     *   <li>空串，以及 {@code model} / {@code model.safetensors} / {@code unknown} / {@code none}
     *       这类占位值：空串——那是"没有底模信息"，不是"认不出族"，绝不无中生有。</li>
     * </ol>
     */
    public static String stackOfBaseModel(String baseModel) {
        String value = baseModel == null ? "" : baseModel.strip();
        if (value.isEmpty() || SdClient.usableBaseModel(value).isEmpty()) return "";
        String known = stackOfFamily(value);
        if (!known.isEmpty()) return known;
        String generic = genericFamily(value);
        if (!generic.isEmpty()) return stackOfFamily(generic);
        String slug = slug(value);
        return slug.isEmpty() ? OTHER : slug;
    }

    /**
     * 只按文件名判栈（兜底；结果要标成推断）。关键词与底模名共用同一张表，但**没有 slug 兜底**：
     * 文件名认不出关键词就是空串（{@code model.safetensors} 这种占位不该变成一个栈）。
     */
    public static String stackOfFilename(String filename) {
        return filename == null ? "" : stackOfFamily(filename);
    }

    /**
     * 只认已知底模族（{@link #FAMILIES}），认不出来返回空串——**不编 slug**。
     * 归一化：小写，下划线/连字符/斜杠都当空格（{@code SD_XL} → {@code sd xl}）。
     */
    private static String stackOfFamily(String name) {
        String key = normalize(name);
        if (key.isEmpty() || unconfirmed(key)) return "";
        // 「animagine」里有「anima」子串（原关键词表就把它误判成 anima 栈了）：这两个 SDXL 族名先认掉。
        if (key.contains("animagine") || key.contains("nova anime")) return XL;
        for (Family family : FAMILIES) {
            for (String keyword : family.keywords()) if (key.contains(keyword)) return family.stack();
            for (String token : family.tokens()) if (wholeWord(key, token)) return family.stack();
        }
        return "";
    }

    // ---------------------------------------------------------------- 通用/占位底模名

    /**
     * 这个底模名是不是**通用名**（{@code model.ckpt} / {@code Anything-v5.0-PRT-RE.safetensors} /
     * {@code v1-5-pruned-emaonly.ckpt} …，见 {@link #SD15_GENERIC}）：它只说明"哪个时代的底模"。
     *
     * <p>调用方据此把通用名**降级**：先去看张量键/形状，只有实在没有别的实据时才用它兜底
     * （那时它指向的家族就是答案）。见 {@code SdClient#resolveBaseModel}。
     */
    public static boolean genericBaseModel(String value) {
        return !genericFamily(value).isEmpty();
    }

    /**
     * 通用底模名 → 它指向的**规范底模名**（就是家族名，如 {@code SD 1.5}）；不是通用名返回空串。
     */
    public static String genericBaseModelFamily(String value) {
        return genericFamily(value);
    }

    /**
     * 通用底模名给人看的写法：{@code SD 1.5（原底模名 model.ckpt）}——面板/回执上要让人看得懂，
     * 而不是甩一个 {@code model-ckpt} 伪栈名；不是通用名返回空串。
     *
     * <p>**读时重判**（存量样式里抄来的 {@code model.ckpt}）与 LoRA 侧用同一句：同一个东西措辞必须
     * 一样，否则界面上看起来像两码事；"这是存量记录里的通用名"这点写在 {@code evidence} 里（见
     * {@link #ofStored}），标签不再区分。
     */
    public static String genericBaseModelLabel(String value) {
        String family = genericFamily(value);
        String raw = value == null ? "" : value.strip();
        return family.isEmpty() || raw.isEmpty() ? "" : family + "（原底模名 " + raw + "）";
    }

    /** 占位值（{@code model} / {@code unknown} …）当成"没有底模"：读存量数据时与 LoRA 侧同一个口径。 */
    public static String usableName(String value) { return SdClient.usableBaseModel(value); }

    /**
     * 一条**存量记录**（样式/老存档里已经写死的 {@code baseModel} / {@code stack} / 来源 / 判据）
     * 在出口处重判后的形态：
     * <ul>
     *   <li>{@code baseModel}：给人看的底模名——通用名写成「{@code SD 1.5（原底模名 model.ckpt）}」
     *       （与 LoRA 侧**逐字相同**），具体名归一成规范名（{@code SDXL} / {@code Anima} …），
     *       认不出来的名字照旧原样给，**绝不编**；</li>
     *   <li>{@code stack}：归属栈（通用名归它指的家族栈，例如 {@code sd}）——**绝不再让
     *       {@code model-ckpt} 这种伪栈名漏出去**；</li>
     *   <li>{@code evidence}：判据原文（存量值没有时就按通用名现编一句，说清"这是按家族归的"）。</li>
     * </ul>
     * 与 LoRA 侧（{@code SdClient#resolveBaseModel}）共用 {@link #canonicalBaseModel(String)} /
     * {@link #stackOfBaseModel(String)} / {@link #genericBaseModelLabel(String)} 同一套判据，两边显示一致。
     */
    public record Stored(String baseModel, String stack, String stackSource, String evidence, boolean generic) {
    }

    /**
     * 把**存量**的底模/栈重判成出口形态（只读：不动数据文件，也不动判定表）。
     *
     * @param baseModel 存档里的底模名（可能是 {@code model.ckpt} 这种通用名，也可能是空）
     * @param stack     存档里的栈名（可能是 {@code model-ckpt} 这种伪栈名）
     * @param source    存档里的底模来源（没有就空串）
     * @param evidence  存档里的底模判据（没有就空串）
     * @param checkpoint 存档里的检查点名（只用来在底模/栈都空时兜底判一次栈，没有就空串）
     * @param field     判据无实据时写进 evidence 的字段名（如 {@code baseModel}），空串表示不写
     */
    public static Stored ofStored(String baseModel, String stack, String source, String evidence, String checkpoint, String field) {
        String storedStack = stack == null ? "" : stack.strip();
        String storedEvidence = evidence == null ? "" : evidence.strip();
        String name = usableName(baseModel);
        if (name.isEmpty()) {
            // 底模名是占位值/空：照旧只按检查点名判一次栈（老样式只有 checkpoint 的那种）；
            // 存档里只留了一个 slug 伪栈名（model-ckpt）时也在这里按名字重判一次。
            String fallback = stackOf("", checkpoint);
            String kept = fallback.isEmpty() ? recoverPseudoStack(storedStack) : fallback;
            return new Stored("", kept, source, storedEvidence, false);
        }
        String canonical = canonicalBaseModel(name);
        if (canonical.isEmpty()) canonical = name;          // 认不出来的名字照旧原样给，不编
        // 只有"通用名"才换成人话写法：具体名（SDXL / Anima / Illustrious …）一个字都不改。
        String label = genericBaseModelLabel(name);
        boolean generic = !label.isEmpty();
        if (!generic) label = canonical;
        // 通用名的栈必须按家族重判（model.ckpt → sd）；具体名沿用存档记着的栈，存档没记才补判一次
        // （底模名非空就一定判得出栈，补上没有坏处，也与 LoRA 侧一致）。
        String resolvedStack = storedStack.isEmpty() ? stackOfBaseModel(canonical)
                : (generic ? firstKnown(stackOfBaseModel(canonical), storedStack) : storedStack);
        // 底模名是空/占位、存档只留了一个 slug 栈（老数据里的 model-ckpt）时，照样按名字重判一次。
        if (resolvedStack.isEmpty() || !knownStack(resolvedStack)) resolvedStack = recoverPseudoStack(resolvedStack);
        String why = storedEvidence;
        if (why.isEmpty() && generic)
            why = "存量字段 " + (field == null || field.isBlank() ? "baseModel" : field) + "=" + name
                    + " 是通用底模名（SD1.5 时代的常见命名），按它归入 " + canonical + " 栈";
        return new Stored(label, resolvedStack, source, why, generic);
    }

    /** 通用的栈兜底：判出来的家族栈优先，其次存档里记着的那个。 */
    private static String firstKnown(String derived, String stored) {
        return derived == null || derived.isEmpty() ? (stored == null ? "" : stored) : derived;
    }

    /**
     * 存档里的栈名/judge 不出来了，就按这个名字重判一次：认出来换成家族栈，认不出**原样保留**
     * （"自成一族"的 slug 栈与真的没有栈都照旧）。
     */
    private static String recoverPseudoStack(String stack) {
        String recovered = pseudoStackOf(stack);
        return recovered.isEmpty() ? (stack == null ? "" : stack) : recovered;
    }

    /**
     * 存档里记着的栈名是个 slug 出来的伪栈名（{@code model-ckpt} 就是 {@code model.ckpt} 的 slug）时，
     * 拿它的原文重判一次，只认**通用底模名**这一种：判出来就换成它指的家族栈，认不出返回空串
     * （{@code foo-barxl-v2} 这种"自成一族"的栈名照旧保留，别乱改）。
     */
    private static String pseudoStackOf(String stack) {
        String value = stack == null ? "" : stack.strip();
        if (value.isEmpty() || knownStack(value)) return "";
        // 伪栈名是 slug 出来的：连字符当空格试一次，再把 {model/anything}-ckpt 这种写法还原成
        // {model/anything}.ckpt（{@link #SD15_GENERIC} 认的那种"SD1.5 时代俗名"）试一次。
        String dotted = value.replace("-ckpt", ".ckpt").replace("-safetensors", ".safetensors");
        for (String candidate : List.of(value, value.replace('-', ' '), dotted, dotted.replace('-', ' '))) {
            String canonical = canonicalBaseModel(candidate);
            if (canonical.isEmpty() || canonical.equals(value)) continue;   // 认不出族的名字照旧不动
            String derived = stackOfBaseModel(canonical);
            if (knownStack(derived)) return derived;
        }
        return "";
    }

    /**
     * 通用名的匹配：**词首**匹配（{@code anything v5.0 prt re.safetensors} 命中 {@code anything}），
     * 且名字里带 {@code xl} 的一律不认（{@code AnythingXL} 是 SDXL）。
     */
    private static String genericFamily(String value) {
        String key = normalize(value);
        if (key.isEmpty() || key.contains("xl")) return "";
        for (String stem : SD14_GENERIC) if (startsWithName(key, stem)) return "SD 1.4";
        for (String stem : SD15_GENERIC) if (startsWithName(key, stem)) return "SD 1.5";
        return "";
    }

    /**
     * 名字以这个词开头才算命中通用名：后面必须是名字结尾、非字母（{@code anything v5.0} /
     * {@code abyssorangemix3aom3} / {@code model.ckpt}），或者是版本号写法
     * （{@code AnythingV5} / {@code CounterfeitV3}）。{@code anythingelse} / {@code my anything model}
     * 都不算——宁可少认，也不要把别人的底模塞进 SD1.5。
     */
    private static boolean startsWithName(String key, String stem) {
        if (!key.startsWith(stem) || key.length() == stem.length()) return key.equals(stem);
        char next = key.charAt(stem.length());
        if (Character.isDigit(next)) return true;
        if (next == 'v' && stem.length() + 1 < key.length() && Character.isDigit(key.charAt(stem.length() + 1))) return true;
        return !Character.isLetter(next);
    }

    /**
     * 整词匹配：{@code wholeWord("wan 2.1 t2v", "wan")} 为真，{@code wholeWord("wandering", "wan")} 为假。
     * 短词还认"词后直接跟数字"的写法（{@code wan2.1} / {@code xl1.0} / {@code sd15}），那在文件名里很常见。
     */
    private static boolean wholeWord(String key, String phrase) {
        if ((" " + key + " ").contains(" " + phrase + " ")) return true;
        for (String token : key.split(" ")) {
            if (token.length() > phrase.length() && token.startsWith(phrase)
                    && Character.isDigit(token.charAt(phrase.length()))) return true;
        }
        return false;
    }

    /** 架构无法确证的名字（Pony V7）：不进任何已知族，走"自成一族"。 */
    private static boolean unconfirmed(String key) {
        for (String marker : UNCONFIRMED) if (key.contains(marker)) return true;
        return false;
    }

    /**
     * 未知底模名 → 干净的栈名（slug）：小写、非字母数字换成 {@code -}、压缩连续连字符、去掉首尾连字符。
     * {@code Foo BarXL v2} → {@code foo-barxl-v2}；汉字算字母，整串中文名原样保留。
     */
    public static String slug(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder();
        boolean dash = false;
        for (int index = 0; index < text.length(); index++) {
            char ch = Character.toLowerCase(text.charAt(index));
            if (Character.isLetterOrDigit(ch)) {
                out.append(ch);
                dash = false;
            } else if (out.length() > 0 && !dash) {
                out.append('-');
                dash = true;
            }
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '-') end--;
        return out.substring(0, end);
    }

    /** 这个栈 key 是不是认识的**底模族**；不是就是按名字 slug 出来的"自成一族"（来源该标成推断）。 */
    public static boolean knownStack(String stack) {
        String value = stack == null ? "" : stack.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return false;
        for (Family family : FAMILIES) if (family.stack().equals(value)) return true;
        return false;
    }

    /**
     * 栈 → 该栈的**规范底模名**：只有栈、没有底模名时用它回填（见 {@code SdClient} 判定链的最后一步）。
     *
     * <p>与 {@link #stackLabel(String)} 共用 {@link #FAMILIES} 同一张表：{@code xl} → {@code SDXL}、
     * {@code sd} → {@code SD 1.5}、{@code krea} → {@code Flux.1 Krea}、{@code sd3} → {@code SD 3.5} …
     * 补出来的名字必须能被 {@link #stackOfBaseModel(String)} 判回同一个栈（有往返断言兜着）。
     *
     * <p>未知 slug 栈（{@link #knownStack(String)} 为 false）返回空串：那种栈本身就是从"未知底模名"
     * 归一出来的，再回填会变成自我循环，宁可照旧显示"未识别"。
     */
    public static String baseModelOfStack(String stack) {
        String value = stack == null ? "" : stack.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        for (Family family : FAMILIES) if (family.stack().equals(value)) return family.canonical();
        return "";
    }

    /** "只有栈、没有底模名"时补出底模名的判据原文（回执与网页 evidence 共用这一句）。 */
    public static String stackBaseModelEvidence(String stack) {
        return stack == null || stack.isBlank() ? ""
                : "按 " + stackLabel(stack) + " 推断底模（没有记录具体底模）";
    }

    /**
     * Forge 预设名 → 栈名：已知底模族按同名/别名归一；其他预设（klein / zit / lumina / ernie …）
     * 本机也有自己的一栈，**原样**当栈名返回（这里不做 slug，否则就对不上预设名本身了），别硬塞进已知族。
     */
    public static String stackOfPreset(String preset) {
        String value = preset == null ? "" : preset.strip();
        if (value.isEmpty()) return "";
        String known = stackOfFamily(value);
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

    /** 栈的中文说法（组头与警告里用）。已知族用表里的标签；"自成一族"的 slug 栈显示成「<栈名> 栈」。 */
    public static String stackLabel(String stack) {
        String value = stack == null ? "" : stack.strip();
        if (value.isEmpty()) return "";
        String key = value.toLowerCase(Locale.ROOT);
        for (Family family : FAMILIES) if (family.stack().equals(key)) return family.label();
        return value + " 栈";
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

    /**
     * 各来源对同一个底模的写法不一样（Civitai 写 Anima，Forge 写 anima，架构串写 stable-diffusion-xl-v1-base）。
     * 先按**整词**归一（Civitai/Forge 的常见写法，见 {@link #CANONICAL}），再按关键词族兜底（架构串），
     * 最后把**通用底模名**（{@code model.ckpt} / {@code Anything-v5} …）归到它指的家族
     * （{@link #genericBaseModelFamily(String)}）——那是"哪个时代的底模"，不是"哪个具体检查点"。
     */
    public static String canonicalBaseModel(String raw) {
        String value = SdClient.usableBaseModel(raw);
        if (value.isEmpty()) return "";
        String key = normalize(value);
        String canonical = CANONICAL.get(key);
        if (canonical != null) return canonical;
        // 关键词族兜底：架构串（stable-diffusion-xl-v1-base）、带后缀的变体（SD 1.5 LCM / SDXL Turbo）走这里。
        if (key.contains("krea")) return "Flux.1 Krea";          // 必须排在 flux 前面
        // 「animagine」里有「anima」子串：SDXL 族名先认掉，别被 anima 抢先。
        if (key.contains("animagine")) return "Animagine XL";
        if (key.contains("nova anime")) return "Nova Anime XL";
        if (key.contains("anima")) return "Anima";
        if (key.contains("flux")) return "Flux";
        if (key.contains("qwen")) return "Qwen Image";
        if (key.contains("sd 3") || key.contains("sd3") || key.contains("stable diffusion 3"))
            return key.contains("3.5") ? "SD 3.5" : "SD 3";
        if (key.contains("illustrious")) return "Illustrious";
        if (key.contains("noob")) return "NoobAI";
        if (unconfirmed(key)) return "Pony V7";
        if (key.contains("pony")) return "Pony";
        if (key.contains("sdxl") || key.contains("sd xl") || key.contains("stable diffusion xl")) return "SDXL";
        if (key.contains("sd 1.4") || key.contains("sd1.4") || key.contains("stable diffusion 1.4")) return "SD 1.4";
        if (key.contains("sd 1.5") || key.contains("sd1.5") || key.contains("sd15") || key.contains("sd v1")
                || key.contains("stable diffusion 1.5") || key.contains("stable diffusion v1")) return "SD 1.5";
        if (key.contains("sd 2.1") || key.contains("sd2.1") || key.contains("sd21")
                || key.contains("stable diffusion 2.1")) return "SD 2.1";
        if (key.contains("sd 2") || key.contains("stable diffusion 2")) return "SD 2.0";
        if (key.contains("hunyuan")) return "Hunyuan";
        if (wholeWord(key, "wan")) return "Wan Video";
        if (key.contains("chroma")) return "Chroma";
        if (key.contains("lumina")) return "Lumina";
        if (key.contains("kolors")) return "Kolors";
        if (key.contains("pixart")) return "PixArt";
        if (key.contains("playground")) return "Playground";
        if (key.contains("cascade")) return "Stable Cascade";
        if (key.contains("z image") || key.contains("zimage")) return "Z-Image";
        if (key.contains("nitro")) return "Nitro-E";
        if (key.contains("odor")) return "ODOR";
        // 通用底模名（model.ckpt / Anything-v5 …）：说不出具体是哪个检查点，但说得出是哪个家族。
        String generic = genericFamily(value);
        if (!generic.isEmpty()) return generic;
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
            // Flux.1 Krea 在 Forge／Civitai 里的几种写法（预设名可能是 flux-krea / flux1_krea …）。
            case KREA -> List.of("flux_krea", "flux-krea", "flux1_krea", "flux1-krea", "flux.1 krea",
                    "flux.1_krea", "flux.1-krea", "flux1 krea", "krea-dev", "kreadev");
            case SD3 -> List.of("sd_3", "sd-3", "sd3.5", "sd_3.5", "sd-3.5", "sd35", "stable-diffusion-3");
            case HUNYUAN -> List.of("hunyuan_video", "hunyuan-video", "hunyuanvideo", "hunyuan_dit");
            case WAN -> List.of("wan_video", "wan-video", "wanvideo", "wan2.1", "wan_2.1");
            case Z_IMAGE -> List.of("z_image", "zimage", "z-image-turbo", "z_image_turbo");
            case CASCADE -> List.of("stable_cascade", "stable-cascade");
            case NITRO -> List.of("nitro_e", "nitro-e", "nitroe");
            case PLAYGROUND -> List.of("playground_v2", "playground-v2", "playgroundv2");
            case PIXART -> List.of("pixart_alpha", "pixart-alpha", "pixart_sigma", "pixart-sigma");
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

    /**
     * {@code __metadata__} 里声明的底模/架构。
     *
     * <p>按 {@link #METADATA_KEYS} 顺序找第一个**具体**的值（能归到已知族或至少是个确定名字的），
     * 顺手记下第一个**通用名**（{@code model.ckpt}）当兜底：通用名不是"具体底模"，不能挡住后面的
     * {@code ss_base_model_version} / {@code modelspec.architecture}。只读到通用名时**原样返回**
     * （不归一），让调用方先去看张量键、再拿它兜底——{@link #genericBaseModel(String)} 认得出来。
     */
    private static Header declaredIn(JsonObject header) {
        if (!header.has("__metadata__") || !header.get("__metadata__").isJsonObject()) return Header.NONE;
        JsonObject metadata = header.getAsJsonObject("__metadata__");
        Header generic = Header.NONE;
        for (String key : METADATA_KEYS) {
            String raw = Json.str(metadata, key, "");
            if (SdClient.usableBaseModel(raw).isEmpty()) continue;   // 占位值（model.safetensors）不算
            String evidence = "__metadata__ " + key + "=" + raw.strip();
            if (genericBaseModel(raw)) {
                if (!generic.known()) generic = new Header(raw.strip(), HEADER_SOURCE, evidence);
                continue;
            }
            String canonical = canonicalBaseModel(raw);
            if (canonical.isEmpty()) continue;
            return new Header(canonical, HEADER_SOURCE, evidence);
        }
        return generic;
    }

    /**
     * 张量名结构 + 张量形状：同一份头部里的键名与 shape 就够判架构（等于「不加载权重也知道这是什么模型」）。
     * 只看前缀与 shape，2 千多个键扫一遍是微秒级。
     *
     * <p>LoRA 侧新增一条最硬的判据：**交叉注意力的上下文维度**（{@code attn2_to_k/to_v} 的
     * {@code lora_down.weight} 是 {@code [rank, 上下文维度]}）——768＝SD1.x 的 CLIP-L、1024＝SD2.x 的
     * OpenCLIP-H、2048＝SDXL 的 OpenCLIP-bigG。只训了 UNet 的 LoRA（没有 {@code lora_te*} 键，
     * 例如本机的 {@code 莉贝尔noobXL-000042}）只有这一条判得出来。
     */
    private static Header structuralIn(JsonObject header) {
        boolean embedder1 = false, embedder0Model = false, embedder0Transformer = false;
        boolean condModel = false, condTransformer = false;
        boolean llmAdapter = false, netBlocks = false, animaLoraBlocks = false;
        boolean doubleBlocks = false, singleBlocks = false, ldmUnet = false;
        boolean loraTe = false, loraTe1 = false, loraTe2 = false;
        int attn2Context = 0, textHidden = 0;
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
            if (key.startsWith("lora_te_") && key.contains("mlp_fc1") && key.endsWith("lora_down.weight"))
                textHidden = Math.max(textHidden, lastDim(header, key));
            if ((key.contains("attn2_to_k") || key.contains("attn2_to_v")) && key.endsWith("lora_down.weight"))
                attn2Context = Math.max(attn2Context, lastDim(header, key));
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
        // 2048 只可能是 SDXL（OpenCLIP-bigG 的上下文维度）：unet-only 的 LoRA 也判得出来，排在文本编码器之前。
        if (attn2Context == 2048)
            return new Header("SDXL", KEYS_SOURCE,
                    "张量键 attn2_to_k/to_v 的上下文维度 2048（SDXL 的 OpenCLIP-bigG）→ SDXL 家族");
        if (embedder1 || loraTe2 || loraTe1) {
            String signals = embedder1 ? "conditioner.embedders.1.*（双文本编码器）"
                    : "lora_te1_*/lora_te2_*（LoRA 带两个文本编码器）";
            return new Header("SDXL", KEYS_SOURCE, "张量键 " + signals + " → SDXL 家族");
        }
        if (embedder0Model || condModel)
            return new Header("SD 2.1", KEYS_SOURCE, "张量键 " + (embedder0Model ? "conditioner.embedders.0.model.*" : "cond_stage_model.model.*")
                    + "（OpenCLIP 文本编码器）→ SD 2.x");
        if (attn2Context == 1024)
            return new Header("SD 2.1", KEYS_SOURCE,
                    "张量键 attn2_to_k/to_v 的上下文维度 1024（OpenCLIP-H）→ SD 2.x");
        if (embedder0Transformer || condTransformer)
            return new Header("SD 1.5", KEYS_SOURCE, "张量键 " + (embedder0Transformer ? "conditioner.embedders.0.transformer.*" : "cond_stage_model.transformer.*")
                    + "（单 CLIP 文本编码器）→ SD 1.x");
        if (loraTe) {
            String hidden = textHidden > 0 ? "，隐藏维度 " + textHidden : "";
            boolean v2 = sd2 || textHidden == 1024;
            return v2
                    ? new Header("SD 2.1", KEYS_SOURCE, "张量键 lora_te_*（单文本编码器" + hidden + "）"
                            + (sd2 ? " + __metadata__ ss_v2=True" : "（OpenCLIP-H 隐藏维度）") + " → SD 2.x")
                    : new Header("SD 1.5", KEYS_SOURCE, "张量键 lora_te_*（单文本编码器" + hidden + "）"
                            + (sd2 ? "" : " + ss_v2=False") + " → SD 1.x");
        }
        if (attn2Context == 768)
            return new Header("SD 1.5", KEYS_SOURCE,
                    "张量键 attn2_to_k/to_v 的上下文维度 768（CLIP-L）→ SD 1.x");
        if (ldmUnet)
            return new Header("SD 1.5", KEYS_SOURCE, "张量键 model.diffusion_model.input_blocks.*（LDM UNet，无第二个文本编码器）");
        return Header.NONE;
    }

    /** 头部里某个张量的最后一维（LoRA 的 {@code lora_down.weight} 就是 {@code [rank, 输入维度]}）；读不到返回 0。 */
    private static int lastDim(JsonObject header, String key) {
        JsonElement value = header.get(key);
        if (value == null || !value.isJsonObject()) return 0;
        JsonElement shape = value.getAsJsonObject().get("shape");
        if (shape == null || !shape.isJsonArray()) return 0;
        JsonArray array = shape.getAsJsonArray();
        if (array.size() == 0) return 0;
        JsonElement last = array.get(array.size() - 1);
        return last.isJsonPrimitive() && last.getAsJsonPrimitive().isNumber() ? last.getAsInt() : 0;
    }

    /** sd-scripts 的 {@code ss_v2}（True 表示 SD2.x 底模）。 */
    private static boolean isV2(JsonObject header) {
        if (!header.has("__metadata__") || !header.get("__metadata__").isJsonObject()) return false;
        return "true".equalsIgnoreCase(Json.str(header.getAsJsonObject("__metadata__"), "ss_v2", ""));
    }
}

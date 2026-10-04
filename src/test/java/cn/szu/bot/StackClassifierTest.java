package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import cn.szu.bot.sd.LocalStyles;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.StackClassifier;

/**
 * 底模**归属栈**的判定（全部跑在本机临时目录与桩 Forge 上，不联网、不碰真机模型）。
 *
 * <p>覆盖：safetensors 头部 {@code __metadata__} 声明、张量名结构（SDXL 双文本编码器 / Anima 的
 * {@code net.*} / Flux 的 {@code double_blocks} / LoRA 的 {@code lora_te_*}）、关键词兜底表、
 * 栈 → Forge 预设映射、{@code /model list} 与 {@code /api/loras} 的栈字段，
 * 以及"读不到就说读不到"（占位元数据、损坏头部、普通文件都不许编出底模）。
 */
public final class StackClassifierTest {
    private static int assertions;

    private static Path animaCheckpoint, sdxlCheckpoint, unknownCheckpoint;
    private static Path animaLora, sd15Lora, unknownLora;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "stack-classifier-tests").toAbsolutePath();
        Files.createDirectories(work);
        Path files = Files.createTempDirectory(work, "files-");
        try {
            headerFacts(files);
            keywordTables();
            baseModelFamilies();
            slugFallback();
            noBaseModelMeansNoStack();
            filenameFamilies();
            canonicalNames();
            kreaPreset();
            stackBaseModels();
            presetMapping();
            stackOnlyBaseModel(files);
            styleHistoryBackfill(files);
            try (Fixture fixture = new Fixture(files)) {
                modelAttribution(fixture);
                loraAttribution(fixture);
            }
        } finally {
            deleteTree(files);
        }
        System.out.println("StackClassifierTest: " + assertions + " assertions passed.");
    }

    // ---------------------------------------------------------------- 头部判据

    /** 头部 {@code __metadata__} 声明优先，其次张量名结构；读不到就是空串。 */
    private static void headerFacts(Path dir) throws Exception {
        Path anima = safetensors(dir, "anima-keys.safetensors", "{\"format\":\"pt\"}",
                List.of("net.llm_adapter.blocks.0.attn.qkv.weight", "net.blocks.0.attn.qkv.weight", "net.blocks.27.mlp.fc.weight"));
        equal("Anima", StackClassifier.baseModelOf(anima), "net.* 结构 → Anima");
        equal(StackClassifier.KEYS_SOURCE, StackClassifier.structural(anima).source(), "结构判出来的来源标记");
        check(StackClassifier.structural(anima).evidence().contains("net.llm_adapter"), "判据要点名是哪个键：" + StackClassifier.structural(anima).evidence());
        equal("", StackClassifier.declared(anima).baseModel(), "只有 format=pt 的头部没有声明式底模");

        Path animaMeta = safetensors(dir, "anima-meta.safetensors", "{\"ss_base_model_version\":\"anima\"}", List.of("net.blocks.0.x"));
        equal("Anima", StackClassifier.baseModelOf(animaMeta), "ss_base_model_version=anima → Anima");
        equal(StackClassifier.HEADER_SOURCE, StackClassifier.declared(animaMeta).source(), "声明式来源标记");
        check(StackClassifier.declared(animaMeta).evidence().contains("ss_base_model_version=anima"), "判据要写出键与值：" + StackClassifier.declared(animaMeta).evidence());

        Path sdxl = safetensors(dir, "sdxl-arch.safetensors", "{\"modelspec.architecture\":\"stable-diffusion-xl-v1-base\"}", List.of("model.diffusion_model.input_blocks.0.0.weight"));
        equal("SDXL", StackClassifier.baseModelOf(sdxl), "modelspec.architecture=stable-diffusion-xl-v1-base → SDXL");
        equal(StackClassifier.HEADER_SOURCE, StackClassifier.declared(sdxl).source(), "声明式元数据的来源标记");
        check(StackClassifier.declared(sdxl).evidence().contains("modelspec.architecture"), "判据要点名是哪个键：" + StackClassifier.declared(sdxl).evidence());

        Path sdxlKeys = safetensors(dir, "sdxl-keys.safetensors", "{\"format\":\"pt\"}",
                List.of("conditioner.embedders.0.transformer.text_model.x", "conditioner.embedders.1.model.transformer.y"));
        equal("SDXL", StackClassifier.baseModelOf(sdxlKeys), "conditioner.embedders.1（双文本编码器）→ SDXL");
        equal(StackClassifier.KEYS_SOURCE, StackClassifier.structural(sdxlKeys).source(), "结构判出来的来源标记");

        Path sd15 = safetensors(dir, "sd15.safetensors", "{\"format\":\"pt\"}",
                List.of("conditioner.embedders.0.transformer.text_model.embeddings.token_embedding.weight", "model.diffusion_model.input_blocks.0.0.weight"));
        equal("SD 1.5", StackClassifier.baseModelOf(sd15), "只有 conditioner.embedders.0.transformer → SD 1.x");

        Path sd2 = safetensors(dir, "sd2.safetensors", "{\"format\":\"pt\"}",
                List.of("conditioner.embedders.0.model.transformer.text_model.x"));
        equal("SD 2.1", StackClassifier.baseModelOf(sd2), "conditioner.embedders.0.model（OpenCLIP）→ SD 2.x");

        Path flux = safetensors(dir, "flux-keys.safetensors", "{\"format\":\"pt\"}",
                List.of("double_blocks.0.img_mod.lin.weight", "single_blocks.0.linear1.weight"));
        equal("Flux", StackClassifier.baseModelOf(flux), "double_blocks + single_blocks → Flux");

        Path fluxMeta = safetensors(dir, "flux-meta.safetensors", "{\"modelspec.architecture\":\"flux-1-dev\"}", List.of());
        equal("Flux", StackClassifier.baseModelOf(fluxMeta), "架构串里有 flux → Flux");

        Path qwen = safetensors(dir, "qwen.safetensors", "{\"modelspec.architecture\":\"qwen-image/lora\"}", List.of());
        equal("Qwen Image", StackClassifier.baseModelOf(qwen), "架构串里有 qwen → Qwen Image");

        // Krea：声明的 ss_base_model_version 就是 krea，必须归到 Flux.1 Krea，不能因为含 "flux" 落进 flux 栈。
        Path kreaMeta = safetensors(dir, "krea-meta.safetensors", "{\"ss_base_model_version\":\"krea\"}", List.of("double_blocks.0.x"));
        equal("Flux.1 Krea", StackClassifier.baseModelOf(kreaMeta), "ss_base_model_version=krea → Flux.1 Krea（不是 Flux）");
        equal("krea", StackClassifier.stackOf(StackClassifier.baseModelOf(kreaMeta), ""), "krea 声明 → krea 栈");
        Path kreaDevMeta = safetensors(dir, "krea-dev-meta.safetensors",
                "{\"ss_base_model_version\":\"Flux.1 Krea [dev]\"}", List.of());
        equal("Flux.1 Krea", StackClassifier.baseModelOf(kreaDevMeta), "Civitai 写法 Flux.1 Krea [dev] 归一成 Flux.1 Krea");
        equal("krea", StackClassifier.stackOf(StackClassifier.baseModelOf(kreaDevMeta), ""), "Flux.1 Krea 属于 krea 栈");

        Path loraSd15 = safetensors(dir, "lora-sd15.safetensors", "{\"ss_sd_model_name\":\"model.safetensors\",\"ss_v2\":\"False\"}",
                List.of("lora_te_text_model_encoder_layers_0_mlp_fc1.alpha", "lora_unet_down_blocks_0_attentions_0_to_q.lora_down.weight"));
        equal("SD 1.5", StackClassifier.baseModelOf(loraSd15), "LoRA 只有 lora_te_*（单文本编码器）+ ss_v2=False → SD 1.5");
        check(StackClassifier.structural(loraSd15).evidence().contains("lora_te_"), "LoRA 判据要点名 lora_te_*：" + StackClassifier.structural(loraSd15).evidence());

        Path loraSd2 = safetensors(dir, "lora-sd2.safetensors", "{\"ss_v2\":\"True\"}", List.of("lora_te_text_model_encoder_layers_0_mlp_fc1.alpha"));
        equal("SD 2.1", StackClassifier.baseModelOf(loraSd2), "同样的键 + ss_v2=True → SD 2.1");

        Path loraSdxl = safetensors(dir, "lora-sdxl.safetensors", "{\"format\":\"pt\"}",
                List.of("lora_te1_text_model_encoder_layers_0_mlp_fc1.alpha", "lora_te2_text_model_encoder_layers_0_mlp_fc1.alpha"));
        equal("SDXL", StackClassifier.baseModelOf(loraSdxl), "LoRA 带 lora_te1_*/lora_te2_* → SDXL");

        Path placeholder = safetensors(dir, "placeholder.safetensors", "{\"ss_sd_model_name\":\"model.safetensors\"}", List.of("lora_unet_x.lora_down.weight"));
        equal("", StackClassifier.baseModelOf(placeholder), "sd-scripts 的占位 model.safetensors 不算底模");

        Path unknown = safetensors(dir, "unknown.safetensors", "{\"format\":\"pt\"}", List.of("some.random.tensor", "another.tensor"));
        equal("", StackClassifier.baseModelOf(unknown), "认不出来就是空串，绝不编");

        Path text = dir.resolve("not-safetensors.safetensors");
        Files.writeString(text, "这不是 safetensors");
        equal("", StackClassifier.baseModelOf(text), "普通文本文件当读不到");

        Path truncated = dir.resolve("truncated.safetensors");
        try (OutputStream out = Files.newOutputStream(truncated)) {
            out.write(littleEndian(4096));
            out.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        equal("", StackClassifier.baseModelOf(truncated), "头部被截断（长度对不上）当读不到");
        equal("", StackClassifier.baseModelOf(null), "没有路径就是空串");
        equal("", StackClassifier.baseModelOf(dir.resolve("nope.safetensors")), "文件不存在就是空串");
    }

    // ---------------------------------------------------------------- 关键词表

    private static void keywordTables() {
        equal("xl", StackClassifier.stackOf("", "waiIllustriousSDXL_v170.safetensors"), "Illustrious/SDXL 文件名 → xl 栈");
        equal("xl", StackClassifier.stackOf("", "【noob】hans-bulldozer26.02.23.safetensors"), "noob 文件名 → xl 栈");
        equal("xl", StackClassifier.stackOf("NoobAI", ""), "NoobAI 底模 → xl 栈");
        equal("xl", StackClassifier.stackOf("Illustrious", ""), "Illustrious 底模 → xl 栈");
        equal("xl", StackClassifier.stackOf("Pony", ""), "Pony 底模 → xl 栈");
        equal("anima", StackClassifier.stackOf("", "animaCatTower_v11.safetensors"), "anima 文件名 → anima 栈");
        equal("anima", StackClassifier.stackOf("Anima", ""), "Anima 底模 → anima 栈");
        equal("flux", StackClassifier.stackOf("Flux", ""), "Flux 底模 → flux 栈");
        equal("qwen", StackClassifier.stackOf("Qwen Image", ""), "Qwen 底模 → qwen 栈");
        equal("sd", StackClassifier.stackOf("SD 1.5", ""), "SD 1.5 底模 → sd 栈");
        equal("sd", StackClassifier.stackOf("SD 1.5", "animaCatTower_v11.safetensors"), "认出来的底模名优先于文件名");
        equal("sd", StackClassifier.stackOf("", "sd_v1-5.safetensors"), "sd_v1 文件名 → sd 栈");
        equal("sd", StackClassifier.stackOf("", "foo_sd15_bar.safetensors"), "sd15 文件名 → sd 栈");
        equal("sd", StackClassifier.stackOf("SD 2.1", ""), "SD 2.x 也归 sd 栈");
        equal("", StackClassifier.stackOf("", "mystery-model.safetensors"), "没有关键词就是空串");
        equal("", StackClassifier.stackOf("", ""), "空输入就是空串");

        equal("SDXL", StackClassifier.canonicalBaseModel("stable-diffusion-xl-v1-base"), "架构串归一到 SDXL");
        equal("Anima", StackClassifier.canonicalBaseModel("anima-preview/lora"), "架构串归一到 Anima");
        equal("SD 1.5", StackClassifier.canonicalBaseModel("stable-diffusion-v1/lora"), "架构串归一到 SD 1.5");
        equal("SDXL", StackClassifier.canonicalBaseModel("SDXL 1.0"), "Civitai 的 SDXL 1.0 归一");
        equal("NoobAI", StackClassifier.canonicalBaseModel("NoobAI"), "NoobAI 保持自己的名字（栈仍是 xl）");
        equal("", StackClassifier.canonicalBaseModel("model.safetensors"), "占位值没有底模");

        equal("Anima 栈", StackClassifier.stackLabel("anima"), "栈的中文说法");
        equal("SDXL 栈", StackClassifier.stackLabel("xl"), "xl 显示成 SDXL 栈");
        equal("SD 1.5 栈", StackClassifier.stackLabel("sd"), "sd 显示成 SD 1.5 栈");
        equal("", StackClassifier.stackLabel(""), "没有栈就没有说法");
        equal("safetensors 头部元数据", StackClassifier.sourceLabel(StackClassifier.HEADER_SOURCE), "来源的中文说法");
        equal("Forge 预设配置", StackClassifier.sourceLabel(StackClassifier.PRESET_SOURCE), "预设配置来源的中文说法");
        check(StackClassifier.observed(StackClassifier.HEADER_SOURCE) && !StackClassifier.observed(StackClassifier.INFERRED_SOURCE),
                "识别出来的算实据，关键词推断的不算");
        check(StackClassifier.sameModel("animaCatTower_v11.safetensors", "animaCatTower_v11"), "同一个模型的不同写法算同一个");
        check(!StackClassifier.sameModel("sd", "sdxl"), "太短的前缀不算同一个文件（sd ≠ sdxl）");
        check(!StackClassifier.sameModel("a.safetensors", "b.safetensors"), "不同文件不是同一个");
    }

    // ---------------------------------------------------------------- 底模族表

    /**
     * 表驱动：**底模写法 → 归属栈**，覆盖 anima / xl / sd / flux / qwen / krea / sd3 与
     * hunyuan / wan / chroma / lumina / kolors / pixart / playground / cascade / z-image / nitro / odor。
     *
     * <p>每组都含大小写、下划线、连字符与带后缀（LCM / Turbo / Hyper / DMD2 / Inpainting / t2v …）的变体；
     * krea 一组专门盯"Flux.1 Krea 里含 flux"这个顺序坑。
     */
    private static void baseModelFamilies() {
        String[][] cases = {
                // ---- krea：Flux.1 Krea 里含 flux，判定顺序反了就会落进 flux 栈 ----
                {"Krea", "krea"}, {"krea", "krea"}, {"KREA", "krea"},
                {"Flux.1 Krea", "krea"}, {"flux krea", "krea"}, {"flux1 krea", "krea"},
                {"flux1_krea", "krea"}, {"flux.1 krea", "krea"}, {"flux-krea", "krea"},
                {"FLUX.1-Krea-dev", "krea"}, {"Flux.1 Krea [dev]", "krea"}, {"Flux.1 Krea-dev", "krea"},
                {"Krea 2", "krea"}, {"krea2", "krea"},
                // ---- anima ----
                {"Anima", "anima"}, {"anima", "anima"}, {"ANIMA", "anima"}, {"Anima Preview", "anima"},
                // ---- xl（SDXL 架构：SDXL 各变体 + Pony/Illustrious/NoobAI/Animagine/Nova Anime）----
                {"SDXL", "xl"}, {"SDXL 0.9", "xl"}, {"SDXL 1.0", "xl"}, {"sdxl_base_1.0", "xl"},
                {"SDXL 1.0 LCM", "xl"}, {"SDXL Turbo", "xl"}, {"SDXL Lightning", "xl"},
                {"SDXL Hyper", "xl"}, {"SDXL DMD2", "xl"}, {"SDXL Base 1.0", "xl"}, {"SDXL Refiner", "xl"},
                {"SD_XL", "xl"}, {"Stable Diffusion XL", "xl"}, {"Stable Diffusion XL 1.0", "xl"},
                {"Pony", "xl"}, {"Pony V6", "xl"}, {"Pony V6 XL", "xl"},
                {"Illustrious", "xl"}, {"Illustrious XL", "xl"}, {"Illustrious-XL-v1.0", "xl"},
                {"NoobAI", "xl"}, {"NoobAI XL", "xl"}, {"NoobAI-XL-v1.1", "xl"},
                {"Animagine XL", "xl"}, {"Animagine XL 3.1", "xl"}, {"Nova Anime XL", "xl"},
                // ---- sd（SD1.x / SD2.x 架构共用 sd 栈）----
                {"SD", "sd"}, {"SD 1", "sd"}, {"SD 1.4", "sd"}, {"SD 1.5", "sd"}, {"SD15", "sd"},
                {"sd_1.5", "sd"}, {"SD-1.5", "sd"}, {"SD 1.5 LCM", "sd"}, {"SD 1.5 Hyper", "sd"},
                {"SD 1.5 DMD2", "sd"}, {"SD 1.5 Inpainting", "sd"}, {"Stable Diffusion 1.5", "sd"},
                {"SD 2", "sd"}, {"SD 2.0", "sd"}, {"SD 2.0 768", "sd"}, {"SD 2.1", "sd"},
                {"SD 2.1 768", "sd"}, {"SD 2.1 Unclip", "sd"}, {"SD21", "sd"}, {"sd_v1-5", "sd"},
                // ---- flux（不含 Krea）----
                {"Flux", "flux"}, {"Flux.1", "flux"}, {"flux1", "flux"}, {"Flux.1 S", "flux"},
                {"Flux.1 D", "flux"}, {"Flux.1 dev", "flux"}, {"Flux.1 schnell", "flux"},
                {"Flux.1 Kontext", "flux"}, {"flux1-dev-fp8", "flux"}, {"flux-1-schnell", "flux"},
                {"Flux.2 Klein 4B", "flux"},
                // ---- qwen（Civitai 的枚举就是 "Qwen"，Forge 预设写 qwen）----
                {"Qwen", "qwen"}, {"Qwen Image", "qwen"}, {"Qwen Image Edit", "qwen"}, {"qwen-image", "qwen"},
                {"Qwen Image Edit 2509", "qwen"},
                // ---- sd3（SD 3 / 3.5 是独立架构，不能混进 sd）----
                {"SD 3", "sd3"}, {"sd3", "sd3"}, {"SD 3.5", "sd3"}, {"sd3.5", "sd3"}, {"SD35", "sd3"},
                {"SD 3.5 Large", "sd3"}, {"SD 3.5 Large Turbo", "sd3"}, {"SD 3.5 Medium", "sd3"},
                {"Stable Diffusion 3.5", "sd3"},
                // ---- 其它族：各自成一栈 ----
                {"Hunyuan 1", "hunyuan"}, {"Hunyuan Video", "hunyuan"}, {"hunyuan-dit", "hunyuan"},
                {"Wan Video", "wan"}, {"Wan 1.3B t2v", "wan"}, {"Wan 2.1 14B t2v", "wan"},
                {"wan2.1_t2v_14b", "wan"}, {"Wan Video 14B i2v 720p", "wan"},
                {"Wan Video 2.2 T2V-A14B", "wan"}, {"Chroma", "chroma"}, {"Chroma1-HD", "chroma"},
                {"Lumina", "lumina"}, {"Lumina Image 2.0", "lumina"}, {"Kolors", "kolors"},
                {"Kwai-Kolors", "kolors"}, {"PixArt a", "pixart"}, {"PixArt E", "pixart"},
                {"PixArt Sigma", "pixart"}, {"Playground v2", "playground"}, {"Playground v2.5", "playground"},
                {"Stable Cascade", "cascade"}, {"stable_cascade", "cascade"}, {"Z-Image", "z-image"},
                {"Z-Image Turbo", "z-image"}, {"z_image", "z-image"}, {"ZImageTurbo", "z-image"},
                {"ZImageBase", "z-image"}, {"Nitro-E", "nitro"},
                {"nitro_e", "nitro"}, {"ODOR", "odor"}, {"odor", "odor"},
        };
        Map<String, String> labels = Map.ofEntries(
                Map.entry("anima", "Anima 栈"), Map.entry("xl", "SDXL 栈"), Map.entry("sd", "SD 1.5 栈"),
                Map.entry("flux", "Flux 栈"), Map.entry("qwen", "Qwen 栈"), Map.entry("krea", "Krea 栈"),
                Map.entry("sd3", "SD3 栈"), Map.entry("hunyuan", "Hunyuan 栈"), Map.entry("wan", "Wan 栈"),
                Map.entry("chroma", "Chroma 栈"), Map.entry("lumina", "Lumina 栈"), Map.entry("kolors", "Kolors 栈"),
                Map.entry("pixart", "PixArt 栈"), Map.entry("playground", "Playground 栈"),
                Map.entry("cascade", "Stable Cascade 栈"), Map.entry("z-image", "Z-Image 栈"),
                Map.entry("nitro", "Nitro 栈"), Map.entry("odor", "ODOR 栈"));
        for (String[] item : cases) {
            String stack = StackClassifier.stackOfBaseModel(item[0]);
            equal(item[1], stack, "底模「" + item[0] + "」→ " + item[1] + " 栈");
            equal(labels.get(item[1]), StackClassifier.stackLabel(stack), "底模「" + item[0] + "」的栈标签");
            check(StackClassifier.knownStack(stack), "表里命中的都是已知族：「" + item[0] + "」→ " + stack);
        }
        check(cases.length >= 100, "族表用例数量：" + cases.length);
    }

    /** 认不出已知族的底模名：栈是它自己的 slug（非空、可复现），并且不属于任何已知族。 */
    private static void slugFallback() {
        String[][] cases = {
                {"Foo BarXL v2", "foo-barxl-v2"},
                {"My Custom Merge", "my-custom-merge"},
                {"Some--Model__v1", "some-model-v1"},
                {"---leading_and trailing---", "leading-and-trailing"},
                {"1234", "1234"},
                {"!!!", "other"},
                {"中文底模", "中文底模"},
                // Pony V7 换了底模，架构无法确证：不许硬塞进 xl，自成一族；Pony / Pony V6 仍然是 xl。
                {"Pony V7", "pony-v7"},
                {"PonyV7", "ponyv7"},
                // Civitai 的 "Other"（它自己的"没归类"桶）：不当成已知族，自成一族。
                {"Other", "other"},
        };
        for (String[] item : cases) {
            String stack = StackClassifier.stackOfBaseModel(item[0]);
            equal(item[1], stack, "「" + item[0] + "」自成一族 → " + item[1]);
            check(!stack.isEmpty(), "非空底模名必须有一个栈：「" + item[0] + "」");
            check(!StackClassifier.knownStack(stack), "自成一族的栈不算已知族：" + stack);
            equal(stack + " 栈", StackClassifier.stackLabel(stack), "自成一族的栈标签能看懂：" + stack);
        }
        equal("pony-v7", StackClassifier.stackOfBaseModel("Pony V7"), "Pony V7 不再判成 xl");
        equal("xl", StackClassifier.stackOfBaseModel("Pony V6"), "Pony V6 仍然是 xl");
        equal("foo-barxl-v2", StackClassifier.slug("Foo BarXL v2"), "slug 规则：小写 + 非字母数字换 - + 压缩");
        equal("a-b", StackClassifier.slug("--a--b--"), "slug 去首尾连字符");
        equal("", StackClassifier.slug("!!!"), "没有字母数字时 slug 是空串（调用方用 other 兜底）");
    }

    /** 真的没有底模信息才叫"未识别"：空串与占位值都返回空栈，绝不无中生有。 */
    private static void noBaseModelMeansNoStack() {
        for (String value : List.of("", "   ", "model.safetensors", "unknown", "未知", "none", "N/A", "-", "null")) {
            equal("", StackClassifier.stackOfBaseModel(value), "占位/空值没有栈：「" + value + "」");
            equal("", StackClassifier.stackOf(value, "mystery-model.safetensors"), "占位/空值没有栈（stackOf）：「" + value + "」");
        }
        for (String file : List.of("", "model.safetensors", "mystery-model.safetensors", "完全没有这个模型.safetensors")) {
            equal("", StackClassifier.stackOfFilename(file), "文件名认不出关键词就是空串：「" + file + "」");
            equal("", StackClassifier.stackOf("", file), "底模为空时文件名认不出就是空串：「" + file + "」");
        }
        equal("", StackClassifier.stackLabel(""), "没有栈就没有说法");
        equal("", StackClassifier.stackLabel(null), "空栈（null）也没有说法");
    }

    /** 文件名兜底：与底模名共用同一张族表（含 krea 在 flux 前），但没有 slug 兜底。 */
    private static void filenameFamilies() {
        String[][] cases = {
                {"animaCatTower_v11.safetensors", "anima"},
                {"waiIllustriousSDXL_v170.safetensors", "xl"},
                {"【noob】hans-bulldozer26.02.23.safetensors", "xl"},
                {"ponyDiffusionV6XL_v6StartWithThisOne.safetensors", "xl"},
                {"flux1-krea-dev.safetensors", "krea"},
                {"Flux.1 Krea Dev.safetensors", "krea"},
                {"flux1-dev-fp8.safetensors", "flux"},
                {"qwen_image_edit_2509.safetensors", "qwen"},
                {"sd3.5_large_turbo.safetensors", "sd3"},
                {"SD_3.5_Medium.safetensors", "sd3"},
                {"foo_sd15_bar.safetensors", "sd"},
                {"sd_v1-5.safetensors", "sd"},
                {"sd21_768.safetensors", "sd"},
                {"hunyuan_video_v1.safetensors", "hunyuan"},
                {"wan2.1_t2v_14b.safetensors", "wan"},
                {"chroma1_hd.safetensors", "chroma"},
                {"lumina_image_2.safetensors", "lumina"},
                {"kolors_v1.safetensors", "kolors"},
                {"pixart_sigma.safetensors", "pixart"},
                {"playground_v2-5.safetensors", "playground"},
                {"stable_cascade.safetensors", "cascade"},
                {"z-image-turbo.safetensors", "z-image"},
                {"nitro-e.safetensors", "nitro"},
                {"odor_v1.safetensors", "odor"},
        };
        for (String[] item : cases) {
            equal(item[1], StackClassifier.stackOf("", item[0]), "文件名「" + item[0] + "」→ " + item[1] + " 栈");
            equal(item[1], StackClassifier.stackOfFilename(item[0]), "文件名（stackOfFilename）「" + item[0] + "」");
        }
    }

    /** 各来源写法 → 给人的规范名（整词优先、关键词族兜底）。 */
    private static void canonicalNames() {
        String[][] cases = {
                {"krea", "Flux.1 Krea"},
                {"FLUX.1-Krea-dev", "Flux.1 Krea"},
                {"Flux.1 Krea [dev]", "Flux.1 Krea"},
                {"Krea 2", "Krea 2"},
                {"flux", "Flux"},
                {"flux-1-dev", "Flux"},
                {"flux.1-schnell", "Flux"},
                {"qwen-image", "Qwen Image"},
                {"Qwen Image Edit", "Qwen Image"},
                {"sd15", "SD 1.5"},
                {"SD 1.4", "SD 1.4"},
                {"SD 1.5 LCM", "SD 1.5"},
                {"SD 2.0 768", "SD 2.0"},
                {"SD 2.1 Unclip", "SD 2.1"},
                {"SDXL", "SDXL"},
                {"SDXL Turbo", "SDXL"},
                {"SDXL 1.0 LCM", "SDXL"},
                {"Stable Diffusion XL", "SDXL"},
                {"Pony V6", "Pony"},
                {"Pony V7", "Pony V7"},
                {"Illustrious XL", "Illustrious"},
                {"NoobAI XL", "NoobAI"},
                {"Animagine XL 3.1", "Animagine XL"},
                {"Nova Anime XL", "Nova Anime XL"},
                {"SD 3", "SD 3"},
                {"SD 3.5 Large Turbo", "SD 3.5"},
                {"Stable Diffusion 3.5", "SD 3.5"},
                {"Hunyuan Video", "Hunyuan"},
                {"Wan 2.1 14B t2v", "Wan Video"},
                {"Wan Video 14B t2v", "Wan Video"},
                {"Chroma1-HD", "Chroma"},
                {"Lumina Image 2.0", "Lumina"},
                {"Kolors", "Kolors"},
                {"PixArt Sigma", "PixArt"},
                {"Playground v2", "Playground"},
                {"Stable Cascade", "Stable Cascade"},
                {"Z-Image Turbo", "Z-Image"},
                {"ZImageTurbo", "Z-Image"},
                {"Nitro-E", "Nitro-E"},
                {"ODOR", "ODOR"},
                {"Flux.2 Klein 4B", "Flux"},
                {"Other", "Other"},
                {"stable-diffusion-xl-v1-base", "SDXL"},
                {"stable-diffusion-v1/lora", "SD 1.5"},
                {"稳定扩散未知模型", "稳定扩散未知模型"},
        };
        for (String[] item : cases) equal(item[1], StackClassifier.canonicalBaseModel(item[0]), "「" + item[0] + "」的规范名");
        equal("", StackClassifier.canonicalBaseModel("unknown"), "占位值没有规范名");
    }

    /** krea 栈 ↔ Forge 预设：预设名可能叫 krea / flux-krea / flux1_krea，顺序反了这里就会串栈。 */
    private static void kreaPreset() {
        List<String> presets = List.of("anima", "flux", "krea", "sd", "sd3", "xl");
        equal("krea", StackClassifier.presetFor("krea", presets), "krea 栈对应 krea 预设");
        equal("flux-krea", StackClassifier.presetFor("krea", List.of("flux-krea")), "别名 flux-krea 也算 krea 预设（返回实际预设名）");
        equal("flux1_krea", StackClassifier.presetFor("krea", List.of("flux1_krea")), "别名 flux1_krea 也算 krea 预设");
        equal("", StackClassifier.presetFor("krea", List.of("flux", "xl")), "只有 flux/xl 时不硬套成 krea 预设");
        equal("sd3", StackClassifier.presetFor("sd3", presets), "sd3 栈对应 sd3 预设");
        equal("sd-3.5", StackClassifier.presetFor("sd3", List.of("sd-3.5")), "sd3 的别名 sd-3.5");
        check(StackClassifier.matchesPreset("krea", "krea") && !StackClassifier.matchesPreset("krea", "flux"),
                "krea 栈与 krea 预设算同一栈、与 flux 不算");
        check(StackClassifier.matchesPreset("krea", "FLUX.1-Krea-dev"), "预设名写成 FLUX.1-Krea-dev 时也算 krea 栈");
        check(!StackClassifier.matchesPreset("flux", "flux1-krea"), "flux 栈不能匹配 krea 预设（顺序错就是这里出问题）");
        check(!StackClassifier.matchesPreset("krea", "flux"), "krea 栈不能匹配 flux 预设");
        equal("krea", StackClassifier.stackOfPreset("krea"), "预设名 krea → krea 栈");
        equal("sd3", StackClassifier.stackOfPreset("sd3"), "预设名 sd3 → sd3 栈");
        equal("krea", StackClassifier.stackOfBaseModel("Flux.1 Krea"), "Flux.1 Krea 底模 → krea 栈");
        equal("flux", StackClassifier.stackOfBaseModel("Flux.1 dev"), "Flux.1 dev 底模 → flux 栈");
        check(StackClassifier.knownStack("krea") && StackClassifier.knownStack("sd3")
                && StackClassifier.knownStack("hunyuan") && !StackClassifier.knownStack("pony-v7")
                && !StackClassifier.knownStack("") && !StackClassifier.knownStack(null),
                "knownStack 只认已知族");
    }

    /**
     * 栈 → 规范底模名（"只有栈、没有底模名"时回填用）：每个已知族都要有名字，而且要能**判回同一个栈**
     * （往返），未知 slug 栈与空栈一律不补——那种栈本来就是从"未知底模名"来的，回填会自我循环。
     */
    private static void stackBaseModels() {
        String[][] cases = {
                {StackClassifier.ANIMA, "Anima"}, {StackClassifier.XL, "SDXL"}, {StackClassifier.SD, "SD 1.5"},
                {StackClassifier.FLUX, "Flux"}, {StackClassifier.QWEN, "Qwen Image"},
                {StackClassifier.KREA, "Flux.1 Krea"}, {StackClassifier.SD3, "SD 3.5"},
                {StackClassifier.HUNYUAN, "Hunyuan"}, {StackClassifier.WAN, "Wan Video"},
                {StackClassifier.CHROMA, "Chroma"}, {StackClassifier.LUMINA, "Lumina"},
                {StackClassifier.KOLORS, "Kolors"}, {StackClassifier.PIXART, "PixArt"},
                {StackClassifier.PLAYGROUND, "Playground"}, {StackClassifier.CASCADE, "Stable Cascade"},
                {StackClassifier.Z_IMAGE, "Z-Image"}, {StackClassifier.NITRO, "Nitro-E"},
                {StackClassifier.ODOR, "ODOR"},
        };
        check(cases.length >= 18, "已知族个数：" + cases.length);
        for (String[] item : cases) {
            String stack = item[0], base = item[1];
            equal(base, StackClassifier.baseModelOfStack(stack), "栈 " + stack + " 的规范底模名");
            equal(stack, StackClassifier.stackOfBaseModel(base), "补出来的底模名要能判回同一个栈：" + base);
            check(StackClassifier.knownStack(stack), "表里的栈都是已知族：" + stack);
            String why = StackClassifier.stackBaseModelEvidence(stack);
            check(why.contains("推断底模") && why.contains(StackClassifier.stackLabel(stack)),
                    "判据要写清是按哪个栈推断的：" + why);
        }
        equal("", StackClassifier.baseModelOfStack("pony-v7"), "未知 slug 栈不补底模（Pony V7 自成一族）");
        equal("", StackClassifier.baseModelOfStack("foo-barxl-v2"), "未知 slug 栈不补底模");
        equal("", StackClassifier.baseModelOfStack(""), "空栈没有底模名");
        equal("", StackClassifier.baseModelOfStack(null), "空栈（null）没有底模名");
        equal("SDXL", StackClassifier.baseModelOfStack("XL"), "栈名大小写不敏感");
        equal("", StackClassifier.stackBaseModelEvidence(""), "没有栈就没有判据");
    }

    private static void presetMapping() {
        List<String> presets = List.of("anima", "flux", "qwen", "sd", "xl", "zit");
        equal("xl", StackClassifier.presetFor("xl", presets), "SDXL 栈对应 xl 预设");
        equal("sd", StackClassifier.presetFor("sd", presets), "sd 栈对应 sd 预设");
        equal("anima", StackClassifier.presetFor("anima", presets), "anima 栈对应 anima 预设");
        equal("flux", StackClassifier.presetFor("flux", presets), "flux 栈对应 flux 预设");
        equal("qwen", StackClassifier.presetFor("qwen", presets), "qwen 栈对应 qwen 预设");
        equal("zit", StackClassifier.presetFor("zit", presets), "非标准栈按同名预设");
        equal("", StackClassifier.presetFor("xl", List.of("anima")), "没有同名预设就给空串（让调用方提示去 Forge 页面切）");
        equal("", StackClassifier.presetFor("", presets), "空栈没有预设");
        equal("xl", StackClassifier.stackOfPreset("xl"), "预设名 xl → xl 栈");
        equal("sd", StackClassifier.stackOfPreset("sd"), "预设名 sd → sd 栈");
        equal("anima", StackClassifier.stackOfPreset("anima"), "预设名 anima → anima 栈");
        equal("qwen", StackClassifier.stackOfPreset("qwen"), "预设名 qwen → qwen 栈");
        equal("klein", StackClassifier.stackOfPreset("klein"), "本机其它预设（klein）原样当栈");
        equal("", StackClassifier.stackOfPreset(""), "空预设没有栈");
        check(StackClassifier.matchesPreset("xl", "xl") && !StackClassifier.matchesPreset("xl", "anima"),
                "xl 栈对 xl 预设算同一栈，对 anima 不算");
        check(StackClassifier.matchesPreset("sd", "sd") && StackClassifier.matchesPreset("anima", "ANIMA"),
                "栈与预设名比大小写不敏感");
        check(!StackClassifier.matchesPreset("", "anima"), "没有栈时不谈匹配");
    }

    // ---------------------------------------------------------------- 底模列表

    /** {@code /model list} 的数据来源：每个底模的栈、预设与判据。 */
    private static void modelAttribution(Fixture fixture) throws Exception {
        SdClient client = fixture.client();
        List<SdClient.ModelInfo> models = client.modelInfos();
        equal(3, models.size(), "三个基础模型");
        SdClient.ModelInfo anima = find(models, "animaCatTower_v11.safetensors");
        equal("Anima", anima.baseModel(), "Anima 底模名（张量结构判出来的）");
        equal("anima", anima.stack(), "animaCatTower → anima 栈");
        equal("anima", anima.preset(), "anima 栈对应 anima 预设");
        equal(StackClassifier.PRESET_SOURCE, anima.stackSource(), "Forge 预设配置认这个文件，栈就以配置为准");
        check(anima.evidence().contains("net.llm_adapter") && anima.evidence().contains("forge_checkpoint_anima"),
                "判据同时给出结构键与预设配置：" + anima.evidence());
        equal("animaCatTower_v11.safetensors [Anima 栈]", anima.label(), "下拉框标签带栈");

        SdClient.ModelInfo sdxl = find(models, "waiIllustriousSDXL_v170.safetensors");
        equal("SDXL", sdxl.baseModel(), "Illustrious 的底模名");
        equal("xl", sdxl.stack(), "waiIllustriousSDXL → xl 栈");
        equal("xl", sdxl.preset(), "xl 栈对应 xl 预设（不是当前 anima）");
        equal(StackClassifier.KEYS_SOURCE, sdxl.stackSource(), "判据来自文件头部的张量结构");
        equal("waiIllustriousSDXL_v170.safetensors [SDXL 栈]", sdxl.label(), "下拉框标签带栈");

        SdClient.ModelInfo unknown = find(models, "mysteryModel.safetensors");
        equal("", unknown.stack(), "认不出栈的模型不编栈");
        equal("mysteryModel.safetensors", unknown.label(), "认不出栈的下拉标签就是文件名");

        // 换底模防呆就是问这一条：这个底模属于哪一栈、要切到哪个预设。
        equal("xl", client.checkpointInfo("waiIllustriousSDXL_v170.safetensors [f116b0c78f]").stack(), "带哈希标题也能查到栈");
        equal("xl", client.checkpointInfo("waiIllustriousSDXL_v170").preset(), "不带扩展名也能查到预设");
        equal("anima", client.checkpointInfo("animaCatTower_v11.safetensors").stack(), "当前栈里的底模判成 anima");
        equal(null, client.checkpointInfo("完全没有这个模型.safetensors"), "查不到就返回 null（调用方保留通用警告）");
    }

    // ---------------------------------------------------------------- /api/loras

    /** {@code /api/loras} 每个 LoRA 的底模 + 归属栈，以及按栈分组。 */
    private static void loraAttribution(Fixture fixture) throws Exception {
        try (Bot bot = fixture.bot()) {
            JsonObject payload = bot.webLoras();
            JsonArray items = payload.getAsJsonArray("loras");
            equal(3, items.size(), "三个本机 LoRA");

            JsonObject anima = item(items, "DeepSeek_ZipZipPipe_style_anima2b");
            equal("Anima", Json.str(anima, "baseModel", ""), "头部 ss_base_model_version=anima → 底模 Anima");
            equal(StackClassifier.HEADER_SOURCE, Json.str(anima, "baseModelSource", ""), "底模来源＝safetensors 头部元数据");
            equal("anima", Json.str(anima, "stack", ""), "LoRA 的归属栈");
            equal("Anima 栈", Json.str(anima, "stackLabel", ""), "栈的中文标签");
            equal("anima", Json.str(anima, "preset", ""), "栈对应的 Forge 预设");
            equal("anima", Json.str(anima, "groupKey", ""), "面板按栈分组（groupKey 就是栈键）");
            check(Json.str(anima, "evidence", "").contains("ss_base_model_version=anima"), "判据跟着一起给：" + Json.str(anima, "evidence", ""));

            JsonObject sd15 = item(items, "shirohaANY-clothes");
            equal("SD 1.5", Json.str(sd15, "baseModel", ""), "只有 lora_te_* 的 LoRA → SD 1.5（占位 model.safetensors 不算）");
            equal(StackClassifier.KEYS_SOURCE, Json.str(sd15, "baseModelSource", ""), "底模来源＝张量结构");
            equal("sd", Json.str(sd15, "stack", ""), "SD 1.5 属于 sd 栈");
            equal("SD 1.5 栈", Json.str(sd15, "stackLabel", ""), "栈标签");
            equal("sd", Json.str(sd15, "preset", ""), "sd 栈对应 sd 预设");
            check(Json.str(sd15, "evidence", "").contains("lora_te_"), "判据要点名 lora_te_*：" + Json.str(sd15, "evidence", ""));

            JsonObject fallback = item(items, "unknown-lora");
            equal("animaCatTower_v11.safetensors", Json.str(fallback, "baseModel", ""), "什么都读不到时按当前预设栈兜底");
            equal("preset-inferred", Json.str(fallback, "baseModelSource", ""), "兜底的来源必须是推断");
            equal("anima", Json.str(fallback, "stack", ""), "兜底推断出来的栈");
            equal(StackClassifier.INFERRED_SOURCE, Json.str(fallback, "stackSource", ""), "推断出来的栈要标成推断");

            JsonArray groups = payload.getAsJsonArray("groups");
            equal(2, groups.size(), "按**栈**分组（anima 2 个 / sd 1 个）");
            JsonObject animaGroup = group(groups, "anima");
            equal("Anima 栈", Json.str(animaGroup, "label", ""), "组头是栈名（前端显示 Anima 栈（2））");
            equal(2, animaGroup.get("count").getAsInt(), "anima 栈两个 LoRA");
            equal("Anima", animaGroup.getAsJsonArray("baseModels").get(0).getAsString(), "组里保留底模名");
            JsonObject sdGroup = group(groups, "sd");
            equal("SD 1.5 栈", Json.str(sdGroup, "label", ""), "第二个组头是 SD 1.5 栈");
            equal("SD 1.5", sdGroup.getAsJsonArray("baseModels").get(0).getAsString(), "组里保留底模名");
        }
    }

    // ---------------------------------------------------------------- 只有栈、没有底模名

    /**
     * 老样式（存盘 {@code model} 里只有 checkpoint、没有 baseModel/stack 字段）的**读路径**回填：
     * 网页那一行补出规范底模名并进「按底模分组」，但**绝不改写存盘文件**（读侧只读）；
     * 检查点名判不出栈时照旧留空——那是"真的没有底模信息"，不填默认值。
     */
    private static void styleHistoryBackfill(Path files) throws Exception {
        try (Fixture fixture = new Fixture(files)) {
            Path stored = fixture.root.resolve("data/local-styles.json");
            JsonObject legacy = Json.parse("{\"checkpoint\":\"waiIllustriousSDXL_v170.safetensors [f116b0c78f]\","
                    + "\"forge_preset\":\"xl\",\"steps\":28,\"cfg\":5,\"sampler\":\"Euler a\",\"width\":1024,\"height\":1024}");
            JsonObject noClue = Json.parse("{\"checkpoint\":\"mystery.safetensors\",\"steps\":20,\"cfg\":7}");
            writeStyles(stored, legacyStyle("侧爱", legacy), legacyStyle("谜样式", noClue));
            byte[] before = Files.readAllBytes(stored);
            try (Bot bot = fixture.bot()) {
                JsonObject payload = bot.webStyles();
                // ① 老数据读路径：只有检查点名 → 按栈补出规范底模名。
                JsonObject old = item(payload.getAsJsonArray("styles"), "侧爱");
                equal("SDXL", Json.str(old, "baseModel", ""), "老样式读出来补上该栈的规范底模名");
                equal(StackClassifier.INFERRED_SOURCE, Json.str(old, "baseModelSource", ""), "补出来的底模来源＝推断");
                equal("xl", Json.str(old, "stack", ""), "老样式的栈由检查点名现算");
                equal("SDXL 栈", Json.str(old, "stackLabel", ""), "栈标签");
                equal(StackClassifier.INFERRED_SOURCE, Json.str(old, "stackSource", ""), "栈是推断出来的");
                equal("waiIllustriousSDXL_v170.safetensors [f116b0c78f]",
                        Json.str(old.getAsJsonObject("model"), "checkpoint", ""), "原样带出记着的检查点");
                JsonArray groups = payload.getAsJsonArray("baseModelGroups");
                equal(1, groups.size(), "只有一个底模组");
                equal("SDXL", Json.str(groups.get(0).getAsJsonObject(), "baseModel", ""), "老样式落进 SDXL 组");
                equal(1, groups.get(0).getAsJsonObject().get("count").getAsInt(), "SDXL 组的计数把它算进去了");

                // ② 反例：检查点名毫无线索 → 底模名照旧留空、不进任何组，也不抛异常。
                JsonObject mystery = item(payload.getAsJsonArray("styles"), "谜样式");
                equal("", Json.str(mystery, "baseModel", ""), "判不出栈就不编底模名");
                equal("", Json.str(mystery, "baseModelSource", ""), "没有底模也就没有来源");
                equal("", Json.str(mystery, "stack", ""), "判不出栈");
                equal("", Json.str(mystery, "stackLabel", ""), "没有栈就没有说法");
                equal("", Json.str(mystery, "stackSource", ""), "没有栈就没有栈来源");
            }
            // ③ 读侧不许写文件：跑完逐字节比对，并且字段级确认没有新增 baseModel/stack。
            byte[] after = Files.readAllBytes(stored);
            check(java.util.Arrays.equals(before, after), "读一遍 webStyles 不许改写 data/local-styles.json");
            JsonObject saved = Json.parse(new String(after, StandardCharsets.UTF_8));
            JsonObject savedModel = saved.getAsJsonArray("styles").get(0).getAsJsonObject().getAsJsonObject("model");
            check(!savedModel.has("baseModel") && !savedModel.has("stack") && !savedModel.has("baseModelSource"),
                    "存盘的老样式不许被回填字段：" + savedModel);
        }
    }

    /** 往夹具的临时根里写一份老格式的 {@code data/local-styles.json}（读侧回填用例专用）。 */
    private static void writeStyles(Path file, JsonObject... styles) throws IOException {
        JsonArray array = new JsonArray();
        for (JsonObject style : styles) array.add(style);
        JsonObject data = new JsonObject();
        data.addProperty("version", 1);
        data.add("styles", array);
        Json.atomicWrite(file, data);
    }

    /** 一条老格式样式：姓名 + 正反向原文 + model（老数据里 model 没有 baseModel/stack 字段）。 */
    private static JsonObject legacyStyle(String name, JsonObject model) {
        JsonObject style = new JsonObject();
        style.addProperty("name", name);
        style.addProperty("positive", "legacy positive");
        style.addProperty("negative", "");
        style.addProperty("updated_at", "");
        style.add("model", model);
        return style;
    }

    /**
     * SdClient：**底模名为空、但栈已经判出来**时补该栈的规范底模名（来源 {@code inferred}，判据写清
     * "按某栈推断底模"）；已经有底模名的路径一个字不改；未知 slug 栈不补。
     */
    private static void stackOnlyBaseModel(Path files) throws Exception {
        // 1) 检查点侧：文件名能判出栈、文件里没有底模声明 → 不许在"按底模分组"里哪一组都不进。
        try (Fixture fixture = new Fixture(files)) {
            SdClient client = fixture.client();
            SdClient.ModelInfo krea = client.checkpointInfo("krea-model-v1.safetensors");
            equal("Flux.1 Krea", krea.baseModel(), "只有栈没有底模名 → 补 krea 栈的规范底模名");
            equal(StackClassifier.INFERRED_SOURCE, krea.baseModelSource(), "补出来的底模来源必须是推断");
            equal("krea", krea.stack(), "归属栈不变");
            check(krea.evidence().contains("按 Krea 栈 推断底模"), "判据写清是按栈推断的：" + krea.evidence());

            SdClient.ModelInfo sd15 = client.checkpointInfo("sd15-model.safetensors");
            equal("SD 1.5", sd15.baseModel(), "只有栈没有底模名 → 补 sd 栈的规范底模名");
            equal("sd", sd15.stack(), "归属栈不变");

            // 2) 已经有底模名（张量结构判出来的）时不许覆盖。
            SdClient.ModelInfo sdxl = client.checkpointInfo("waiIllustriousSDXL_v170.safetensors [f116b0c78f]");
            equal("SDXL", sdxl.baseModel(), "有底模名时不覆盖");
            equal(StackClassifier.KEYS_SOURCE, sdxl.baseModelSource(), "有实据时来源仍是实据");
            check(!sdxl.evidence().contains("推断底模"), "有实据时不该出现「按栈推断」：" + sdxl.evidence());

            // 3) 认不出栈（文件名也没有线索）时照旧返回 null，不硬猜、不补。
            equal(null, client.checkpointInfo("完全没有这个模型.safetensors"), "判不出栈就不补底模（仍是 null）");
        }

        // 4) Forge 预设**没配检查点**：只剩栈，补出规范底模名（LoRA 侧同样受益）。
        try (Fixture fixture = new Fixture(files)) {
            fixture.presetCheckpoint = "";
            SdClient client = fixture.client();
            SdClient.BaseModel base = client.presetBaseModel();
            equal("Anima", base.name(), "预设没配检查点 → 补 anima 栈的规范底模名");
            equal(StackClassifier.INFERRED_SOURCE, base.source(), "补出来的底模来源必须是推断");
            equal("anima", base.stack(), "归属栈还在");
            check(base.evidence().contains("按 Anima 栈 推断底模"), "判据写清是按栈推断的：" + base.evidence());
            SdClient.BaseModel lora = client.resolveBaseModel("", "unknown-lora", unknownLora.toString());
            equal("Anima", lora.name(), "LoRA 三层都读不到时按栈补底模名");
            equal("anima", lora.stack(), "LoRA 的归属栈");
        }

        // 5) 未知 slug 栈不补：底模名本来就有（自成一族），原样保留。
        try (Fixture fixture = new Fixture(files)) {
            SdClient client = fixture.client();
            SdClient.BaseModel slug = client.resolveBaseModel("Foo BarXL v2", "unknown-lora", unknownLora.toString());
            equal("Foo BarXL v2", slug.name(), "有底模名时不覆盖（哪怕它的栈是 slug）");
            equal("foo-barxl-v2", slug.stack(), "认不出已知族时栈是 slug");
            equal(StackClassifier.INFERRED_SOURCE, slug.stackSource(), "slug 栈的来源标成推断");
        }

        // 6) 检查点列表：文件名关键词判出栈、文件里没有底模声明 → 列表里的底模名也不能空着。
        try (Fixture fixture = new Fixture(files)) {
            fixture.keywordCheckpoint = safetensors(files.resolve("checkpoints"), "hunyuanVideo_v1.safetensors",
                    "{\"format\":\"pt\"}", List.of("plain.tensor"));
            SdClient client = fixture.client();
            List<SdClient.ModelInfo> models = client.modelInfos();
            equal(4, models.size(), "加上这个关键词底模共四个");
            SdClient.ModelInfo hunyuan = find(models, "hunyuanVideo_v1.safetensors");
            equal("Hunyuan", hunyuan.baseModel(), "只有栈没有底模名 → 列表里补出规范底模名");
            equal(StackClassifier.INFERRED_SOURCE, hunyuan.baseModelSource(), "来源标成推断");
            equal("hunyuan", hunyuan.stack(), "归属栈");
            check(hunyuan.evidence().contains("按 Hunyuan 栈 推断底模"), "判据写清是按栈推断的：" + hunyuan.evidence());
        }

        // 7) 样式侧：`.style save` 记的就是 modelParams()，它现在带上归属栈与规范底模名，
        //    网页「按底模分组」据此归组（这里用同一个桩把保存 → 网页接口整条走一遍）。
        try (Fixture fixture = new Fixture(files)) {
            SdClient client = fixture.client();
            client.setParameter("model", "waiIllustriousSDXL_v170.safetensors");
            JsonObject model = client.modelParams();
            check(Json.str(model, "checkpoint", "").startsWith("waiIllustriousSDXL_v170.safetensors"),
                    "样式快照记下检查点：" + Json.str(model, "checkpoint", ""));
            equal("SDXL", Json.str(model, "baseModel", ""), "样式快照补出规范底模名");
            equal(StackClassifier.INFERRED_SOURCE, Json.str(model, "baseModelSource", ""), "底模来源标成推断");
            equal("xl", Json.str(model, "stack", ""), "样式快照带上归属栈");
            new LocalStyles(fixture.root).save("侧爱", "positive", "negative", true, model);
            try (Bot bot = fixture.bot()) {
                JsonObject payload = bot.webStyles();
                JsonObject row = item(payload.getAsJsonArray("styles"), "侧爱");
                equal("SDXL", Json.str(row, "baseModel", ""), "样式行带底模名");
                JsonArray groups = payload.getAsJsonArray("baseModelGroups");
                boolean found = false;
                for (JsonElement element : groups) {
                    JsonObject group = element.getAsJsonObject();
                    if (Json.str(group, "baseModel", "").equals("SDXL") && group.get("count").getAsInt() == 1) found = true;
                }
                check(found, "样式落进 SDXL 底模组：" + groups);
            }
        }
    }

    // ---------------------------------------------------------------- 桩

    /** 桩 Forge：三个底模（anima 结构 / SDXL 结构 / 认不出）+ 三个 LoRA（anima 元数据 / SD1.5 结构 / 全无）。 */
    private static final class Fixture implements AutoCloseable {
        private final Path root;
        private final HttpServer server;
        private final ExecutorService executor;
        private final JsonObject sdConfig = new JsonObject();
        /** 多挂一个"文件名能判出栈、文件里没有任何底模声明"的检查点（默认不挂，免得动到既有条数断言）。 */
        Path keywordCheckpoint;
        /** Forge 预设 anima 配的检查点；清空就是"只有栈、没有底模名"那种现场。 */
        String presetCheckpoint = "animaCatTower_v11.safetensors";

        Fixture(Path files) throws IOException {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "stack-classifier-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            Path checkpoints = Files.createDirectories(files.resolve("checkpoints"));
            Path loras = Files.createDirectories(files.resolve("loras"));
            animaCheckpoint = safetensors(checkpoints, "animaCatTower_v11.safetensors", "{\"format\":\"pt\"}",
                    List.of("net.llm_adapter.blocks.0.attn.qkv.weight", "net.blocks.0.mlp.fc.weight"));
            sdxlCheckpoint = safetensors(checkpoints, "waiIllustriousSDXL_v170.safetensors", "{\"format\":\"pt\"}",
                    List.of("conditioner.embedders.0.transformer.text_model.x", "conditioner.embedders.1.model.transformer.y"));
            unknownCheckpoint = safetensors(checkpoints, "mysteryModel.safetensors", "{\"format\":\"pt\"}", List.of("plain.tensor"));
            animaLora = safetensors(loras, "DeepSeek_ZipZipPipe_style_anima2b.safetensors",
                    "{\"ss_base_model_version\":\"anima\",\"modelspec.architecture\":\"anima-preview/lora\"}",
                    List.of("diffusion_model.blocks.0.attn.qkv.weight"));
            sd15Lora = safetensors(loras, "shirohaANY-clothes.safetensors",
                    "{\"ss_sd_model_name\":\"model.safetensors\",\"ss_v2\":\"False\"}",
                    List.of("lora_te_text_model_encoder_layers_0_mlp_fc1.alpha", "lora_unet_down_blocks_0_to_q.lora_down.weight"));
            unknownLora = safetensors(loras, "unknown-lora.safetensors", "{\"format\":\"pt\"}", List.of("plain.tensor"));

            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "stack-mock"); thread.setDaemon(true); return thread;
            });
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            sdConfig.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sdConfig.addProperty("timeout_seconds", 5);
            sdConfig.addProperty("cfg_scale", 7.5);
            JsonObject config = new JsonObject();
            config.add("sd", sdConfig);
            Json.atomicWrite(root.resolve("config.json"), config);
            Files.createDirectories(root.resolve("data"));
        }

        SdClient client() throws IOException { return new SdClient(root, sdConfig); }

        Bot bot() throws Exception {
            Settings settings = new Settings(root);
            Bot.Sender sender = new Bot.Sender() {
                @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
                    return CompletableFuture.completedFuture(new JsonObject());
                }
            };
            return new Bot(settings, client(), sender);
        }

        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                String body = switch (path) {
                    case "/pixiko-bridge/v1/prompts" -> Json.GSON.toJson(state());
                    case "/sdapi/v1/options" -> Json.GSON.toJson(options());
                    case "/sdapi/v1/sd-models" -> Json.GSON.toJson(models());
                    case "/sdapi/v1/loras" -> Json.GSON.toJson(loras());
                    case "/sdapi/v1/samplers" -> "[{\"name\":\"ER SDE\",\"aliases\":[]}]";
                    default -> "{}";
                };
                send(exchange, 200, body);
            } finally { exchange.close(); }
        }

        JsonObject options() {
            JsonObject options = new JsonObject();
            options.addProperty("sd_model_checkpoint", "animaCatTower_v11.safetensors [2d0343cd69]");
            options.addProperty("forge_preset", "anima");
            options.addProperty("forge_checkpoint_anima", presetCheckpoint);
            options.addProperty("anima_t2i_sampler", "ER SDE");
            options.addProperty("anima_t2i_scheduler", "Beta");
            options.addProperty("anima_t2i_step", 32);
            options.addProperty("anima_t2i_cfg", 4);
            // 真机上 options 里每个预设都有一份 forge_checkpoint_<preset>（只有配了检查点的那个非空），
            // 预设列表就是这么算出来的；这里照真机形状给全，别的栈只是没配检查点。
            options.addProperty("forge_checkpoint_xl", "");
            options.addProperty("forge_checkpoint_sd", "");
            options.addProperty("forge_checkpoint_flux", "");
            options.addProperty("forge_checkpoint_qwen", "");
            return options;
        }

        JsonArray models() {
            JsonArray list = new JsonArray();
            List<Path> files = new ArrayList<>(List.of(animaCheckpoint, sdxlCheckpoint, unknownCheckpoint));
            if (keywordCheckpoint != null) files.add(keywordCheckpoint);
            for (Path file : files) {
                JsonObject item = new JsonObject();
                item.addProperty("title", file.getFileName().toString() + " [abcd1234]");
                item.addProperty("model_name", file.getFileName().toString().replace(".safetensors", ""));
                item.addProperty("filename", file.toString());
                list.add(item);
            }
            return list;
        }

        JsonArray loras() {
            JsonArray list = new JsonArray();
            list.add(lora("DeepSeek_ZipZipPipe_style_anima2b", "anima2b", animaLora));
            list.add(lora("shirohaANY-clothes", "shirohaANY", sd15Lora));
            list.add(lora("unknown-lora", "unknown-lora", unknownLora));
            return list;
        }

        private static JsonObject lora(String name, String alias, Path file) {
            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            item.addProperty("alias", alias);
            item.addProperty("path", file.toString());
            return item;
        }

        JsonObject state() {
            JsonObject state = new JsonObject();
            state.addProperty("positive", "live positive");
            state.addProperty("negative", "live negative");
            state.addProperty("revision", 1);
            state.addProperty("source", "webui-live");
            state.addProperty("live", true);
            state.addProperty("settings_initialized", true);
            state.addProperty("sampler_name", "ER SDE");
            state.add("styles", new JsonArray());
            state.addProperty("width", 1024);
            state.addProperty("height", 1024);
            return state;
        }

        static void send(HttpExchange exchange, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        public void close() {
            server.stop(0);
            executor.shutdownNow();
            deleteTree(root);
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 造一个**最小合法**的 safetensors：8 字节小端头部长度 + 头部 JSON + 一小段"权重"。 */
    private static Path safetensors(Path dir, String name, String metadataJson, List<String> tensors) throws IOException {
        JsonObject header = new JsonObject();
        if (metadataJson != null && !metadataJson.isBlank()) header.add("__metadata__", JsonParser.parseString(metadataJson));
        for (String tensor : tensors) {
            JsonObject info = new JsonObject();
            info.addProperty("dtype", "F16");
            info.add("shape", JsonParser.parseString("[1]"));
            info.add("data_offsets", JsonParser.parseString("[0,2]"));
            header.add(tensor, info);
        }
        byte[] body = Json.GSON.toJson(header).getBytes(StandardCharsets.UTF_8);
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(littleEndian(body.length));
            out.write(body);
            out.write(new byte[]{0, 0});
        }
        return file;
    }

    private static byte[] littleEndian(long value) {
        byte[] bytes = new byte[8];
        for (int index = 0; index < 8; index++) bytes[index] = (byte) ((value >>> (8 * index)) & 0xFF);
        return bytes;
    }

    private static SdClient.ModelInfo find(List<SdClient.ModelInfo> models, String name) {
        for (SdClient.ModelInfo info : models) if (info.name().equals(name)) return info;
        throw new AssertionError("模型列表里没有 " + name + "：" + models);
    }

    private static JsonObject item(JsonArray items, String name) {
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            if (Json.str(item, "name", "").equals(name)) return item;
        }
        throw new AssertionError("LoRA 列表里没有 " + name + "：" + items);
    }

    private static JsonObject group(JsonArray groups, String key) {
        for (JsonElement element : groups) {
            JsonObject group = element.getAsJsonObject();
            if (Json.str(group, "key", "").equals(key)) return group;
        }
        throw new AssertionError("分组里没有「" + key + "」：" + groups);
    }

    private static void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        } catch (IOException ignored) { /* 临时目录清不掉不影响用例结论 */ }
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}

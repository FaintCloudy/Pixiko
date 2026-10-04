package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.civitai.CivitaiStyleSync;
import cn.szu.bot.sd.LocalStyles;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.StackClassifier;

/**
 * LoRA 的底模识别与展示图样式的模型参数（全部跑在本机桩服务上，不联网）。
 *
 * <p>覆盖：底模来源优先级（**Civitai 记录 → safetensors 头部声明 → Forge 元数据 → 张量键/形状 →
 * 通用底模名 → 当前预设栈**）、{@code model.ckpt} 这类**通用底模名**归 SD1.5 而不是自成一个
 * {@code model-ckpt} 伪栈、展示图样式写盘带 model、样式载入把参数套回机器人设置、
 * 一条实据都没有时**不编造**底模名。
 */
public final class LoraBaseModelTest {
    private static int assertions;
    private static final String LORA_PLACEHOLDER = "shirohaANY-clothes.safetensors", LORA_ANIMA = "鸣濑白羽.safetensors";

    /** 真机 {@code ss_sd_model_name} 里出现过的通用底模名（修 bug 的原始现场）。 */
    private static final List<String> GENERIC_NAMES = List.of(
            "model.ckpt", "model.ckpt.safetensors", "v1-5-pruned-emaonly.ckpt", "v1-5-pruned.ckpt",
            "Anything-v5.0-PRT-RE.safetensors", "AnythingV5.safetensors", "anything-v3.0.safetensors",
            "Counterfeit-V3.0_fp16.safetensors", "counterfeit_v2.5.safetensors", "naifu.safetensors",
            "AbyssOrangeMix3AOM3_aom3a1b.safetensors", "meinamix_v11.safetensors",
            "chilloutmix_NiPrunedFp32Fix.safetensors", "7th_anime_v3_A-fp16.safetensors");

    /** 伪栈名的形态（通用名被 slug 之后的样子）：任何返回值里都不许出现。 */
    private static final List<String> PSEUDO_STACKS = List.of(
            "model-ckpt", "v1-5-pruned-emaonly-ckpt", "v1-5-pruned-ckpt", "anything-v5-0-prt-re-safetensors",
            "anythingv5-safetensors", "anything-v3-0-safetensors", "counterfeit-v3-0-fp16-safetensors",
            "counterfeit-v2-5-safetensors", "naifu-safetensors", "abyssorangemix3aom3-aom3a1b-safetensors",
            "meinamix-v11-safetensors", "chilloutmix-niprunedfp32fix-safetensors", "7th-anime-v3-a-fp16-safetensors");

    /** LoRA 文件头里的张量（键 → shape）：形状是判族最硬的实据（attn2 的上下文维度）。 */
    private static final String SD15_TE_KEY = "lora_te_text_model_encoder_layers_0_mlp_fc1.lora_down.weight";
    private static final String SDXL_TE1_KEY = "lora_te1_text_model_encoder_layers_0_mlp_fc1.lora_down.weight";
    private static final String SDXL_TE2_KEY = "lora_te2_text_model_encoder_layers_0_mlp_fc1.lora_down.weight";
    /** 交叉注意力的 K 投影：{@code lora_down.weight} 的形状是 {@code [rank, 上下文维度]}。 */
    private static final String ATTN2_KEY = "lora_unet_down_blocks_0_attentions_0_transformer_blocks_0_attn2_to_k.lora_down.weight";
    private static final String ATTN2_INPUT_KEY = "lora_unet_input_blocks_0_attentions_0_transformer_blocks_0_attn2_to_k.lora_down.weight";

    public static void main(String[] args) throws Exception {
        baseModelPriority();
        genericBaseModelNames();
        genericNameStillSd15();
        civitaiBaseModelWins();
        tensorKeysDecide();
        evidenceConflicts();
        pseudoStackScan();
        showcaseStyleCarriesModel();
        styleLoadAppliesParams();
        unknownIsNotFabricated();
        System.out.println("LoraBaseModelTest: " + assertions + " assertions passed.");
    }

    /** Civitai 记录 > Forge 元数据 > 当前预设栈；来源标记要跟着走，写法不同的同一个底模要归成一组。 */
    private static void baseModelPriority() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SdClient client = fixture.client();
            equal(2, client.loras().size(), "读到两个 LoRA");
            String placeholder = fixture.loraDir.resolve(LORA_PLACEHOLDER).toString();
            String anima = fixture.loraDir.resolve(LORA_ANIMA).toString();
            equal("anima", client.forgeLoraBaseModel("鸣濑白羽", anima), "Forge 元数据的 ss_base_model_version（原样读出）");
            equal("", client.forgeLoraBaseModel("shirohaANY-clothes", placeholder),
                    "占位的 ss_sd_model_name=model.safetensors 不算底模");
            equal("", client.forgeLoraBaseModel("shirohaANY-clothes", ""), "路径为空时也查得到（按名字）");

            SdClient.BaseModel recorded = client.resolveBaseModel("NoobAI", "鸣濑白羽", anima);
            equal("NoobAI", recorded.name(), "有 Civitai 记录时以 Civitai 为准");
            equal("civitai", recorded.source(), "来源标记 civitai");
            SdClient.BaseModel metadata = client.resolveBaseModel("", "鸣濑白羽", anima);
            equal("Anima", metadata.name(), "没有 Civitai 记录时退回 Forge 元数据");
            equal("forge-metadata", metadata.source(), "来源标记 forge-metadata");
            equal(client.resolveBaseModel("Anima", "鸣濑白羽", anima).groupKey(), metadata.groupKey(),
                    "Civitai 的 Anima 与 Forge 的 anima 归成同一组");

            SdClient.BaseModel inferred = client.resolveBaseModel("未知", "shirohaANY-clothes", placeholder);
            equal("animaCatTower_v11.safetensors", inferred.name(), "两处都没有时按当前预设栈推断");
            equal("preset-inferred", inferred.source(), "来源标记 preset-inferred");
            check(inferred.note().contains("按当前预设推断"), "推断出来的必须标注：" + inferred.note());
            equal("按当前预设推断", inferred.sourceLabel(), "来源的中文说法");

            SdClient.BaseModel listed = client.resolveBaseModel("Anima", "鸣濑白羽", anima, inferred);
            equal("Anima", listed.name(), "显式传入的预设兜底不影响更高优先级的来源");
        }
    }

    // ---------------------------------------------------------------- 通用底模名（model.ckpt 这类）

    /**
     * ① **通用底模名**必须归它指的家族、栈必须是项目既有的那套（SD1.5 → {@code sd}），
     * **不许**冒出 {@code model-ckpt} / {@code anything-v5-0-prt-re-safetensors} 这种伪栈。
     *
     * <p>表里的写法都来自真实数据：本机 {@code models\Lora} 里 6 个 LoRA 的
     * {@code ss_sd_model_name} 就是 {@code model.ckpt}，{@code 莲} 是
     * {@code Anything-v5.0-PRT-RE.safetensors}。
     */
    private static void genericBaseModelNames() {
        String[][] cases = {
                {"model.ckpt", "SD 1.5", "sd"},
                {"model.ckpt.safetensors", "SD 1.5", "sd"},
                {"v1-5-pruned-emaonly.ckpt", "SD 1.5", "sd"},
                {"v1-5-pruned.ckpt", "SD 1.5", "sd"},
                {"sd-v1-5-pruned-emaonly.safetensors", "SD 1.5", "sd"},
                {"Anything-v5.0-PRT-RE.safetensors", "SD 1.5", "sd"},
                {"AnythingV5.safetensors", "SD 1.5", "sd"},
                {"anything-v3.0.safetensors", "SD 1.5", "sd"},
                {"Counterfeit-V3.0_fp16.safetensors", "SD 1.5", "sd"},
                {"counterfeit_v2.5.safetensors", "SD 1.5", "sd"},
                {"naifu.safetensors", "SD 1.5", "sd"},
                {"AbyssOrangeMix3AOM3_aom3a1b.safetensors", "SD 1.5", "sd"},
                {"meinamix_v11.safetensors", "SD 1.5", "sd"},
                {"chilloutmix_NiPrunedFp32Fix.safetensors", "SD 1.5", "sd"},
                {"7th_anime_v3_A-fp16.safetensors", "SD 1.5", "sd"},
                {"v1-4-pruned.ckpt", "SD 1.4", "sd"},
        };
        for (String[] item : cases) {
            check(StackClassifier.genericBaseModel(item[0]), "认得出这是通用底模名：「" + item[0] + "」");
            equal(item[1], StackClassifier.canonicalBaseModel(item[0]), "通用底模名「" + item[0] + "」的规范名");
            equal(item[2], StackClassifier.stackOfBaseModel(item[0]), "通用底模名「" + item[0] + "」归 " + item[2] + " 栈");
            equal(item[2], StackClassifier.stackOf(item[0], ""), "stackOf 走同一条判定：「" + item[0] + "」");
            check(StackClassifier.knownStack(StackClassifier.stackOfBaseModel(item[0])),
                    "通用底模名必须归**已知族**（不许自成一族）：「" + item[0] + "」");
            check(!StackClassifier.stackOfBaseModel(item[0]).equals(StackClassifier.slug(item[0])),
                    "栈名不是这个字面量的 slug：「" + item[0] + "」");
            String label = StackClassifier.genericBaseModelLabel(item[0]);
            check(label.startsWith(item[1] + "（原底模名 ") && label.endsWith("）"),
                    "给人的写法是「规范名（原底模名 …）」：" + label);
            check(label.contains(item[0]), "原底模名照旧看得到：" + label);
        }
        equal("SD 1.5", StackClassifier.genericBaseModelFamily("model.ckpt"), "通用名 → 家族");
        equal("", StackClassifier.genericBaseModelFamily("Illustrious"), "具体族名不是通用名");
        equal("", StackClassifier.genericBaseModelLabel("Illustrious"), "具体族名没有「原底模名」那一说");
        // 带 xl 的一律不算 SD1.5 通用名（AnythingXL / CounterfeitXL 都是 SDXL）；词中出现也不算。
        for (String name : List.of("AnythingXL_v1.safetensors", "AnythingV5XL.safetensors", "CounterfeitXL.safetensors",
                "anything xl base.safetensors", "my anything model.safetensors", "nothing-v1.safetensors")) {
            check(!StackClassifier.genericBaseModel(name), "不能当成 SD1.5 通用名：" + name);
        }
        // sd-scripts 的占位（model / model.safetensors）沿用既有行为：那是"没记底模"，不是 SD1.5。
        for (String placeholder : List.of("model", "model.safetensors", "unknown", "未知", "none", "-")) {
            check(!StackClassifier.genericBaseModel(placeholder), "占位值不是通用底模名：" + placeholder);
            equal("", StackClassifier.stackOfBaseModel(placeholder), "占位值仍然没有栈：" + placeholder);
            equal("", StackClassifier.canonicalBaseModel(placeholder), "占位值仍然没有规范名：" + placeholder);
            equal("", StackClassifier.genericBaseModelLabel(placeholder), "占位值没有「原底模名」写法：" + placeholder);
        }
        // 两个 XL 的常见写法仍然判 xl（回归重点，绝不能因为加了通用名表被带偏）。
        equal("xl", StackClassifier.stackOfBaseModel("sd_xl_base_1.0.safetensors"), "sd_xl_base_1.0 → xl 栈");
        equal("SDXL", StackClassifier.canonicalBaseModel("sd_xl_base_1.0.safetensors"), "sd_xl_base_1.0 的规范名");
        equal("xl", StackClassifier.stackOfBaseModel("ponyDiffusionV6XL_v6StartWithThisOne"), "ponyDiffusionV6XL → xl 栈");
        equal("xl", StackClassifier.stackOfBaseModel("waiIllustriousSDXL_v170.safetensors"), "waiIllustriousSDXL → xl 栈");
        equal("xl", StackClassifier.stackOfBaseModel("noobaiXLNAIXL_vPred10Version.safetensors"), "noobaiXL → xl 栈");
    }

    /**
     * ① 续：真机现场（{@code ss_sd_model_name=model.ckpt} 的那 6 个 LoRA + {@code 莲}）在**没有 Civitai
     * 记录**时的结果：家族 SD1.5、栈 {@code sd}、来源是张量键，底模名显示成「SD 1.5（原底模名 model.ckpt）」。
     */
    private static void genericNameStillSd15() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SdClient client = fixture.client();
            Map<String, String> sd15Keys = new LinkedHashMap<>();
            sd15Keys.put(SD15_TE_KEY, "[8, 768]");
            sd15Keys.put(ATTN2_KEY, "[8, 768]");
            int count = 0;
            for (String name : List.of("和泉妃爱", "姬野星奏", "春日野穹", "美亚", "锦亚澄", "风又音理")) {
                Path file = fixture.safetensors(name + ".safetensors", "{\"ss_sd_model_name\":\"model.ckpt\",\"ss_v2\":\"False\"}", sd15Keys);
                SdClient.BaseModel base = client.resolveBaseModel("", name, file.toString(), SdClient.BaseModel.NONE);
                equal("SD 1.5（原底模名 model.ckpt）", base.name(), "「" + name + "」的底模名是人看得懂的写法");
                equal("sd", base.stack(), "「" + name + "」归 sd 栈（不是 model-ckpt）");
                equal(StackClassifier.KEYS_SOURCE, base.source(), "「" + name + "」的底模来自张量键（通用名不可信）");
                equal("SD 1.5 栈", base.stackLabel(), "「" + name + "」的栈标签");
                check(base.evidence().contains("model.ckpt"), "判据里写明原底模名：" + base.evidence());
                check(base.evidence().contains("通用底模名"), "判据里说明那是通用名：" + base.evidence());
                count++;
            }
            equal(6, count, "六个真机同形（model.ckpt）的 LoRA 都跑过一遍");
            // 莲：Anything-v5.0（同样是 SD1.5 时代的通用名）。
            Path lian = fixture.safetensors("莲.safetensors", "{\"ss_sd_model_name\":\"Anything-v5.0-PRT-RE.safetensors\"}", sd15Keys);
            SdClient.BaseModel lianBase = client.resolveBaseModel("", "莲", lian.toString(), SdClient.BaseModel.NONE);
            equal("SD 1.5（原底模名 Anything-v5.0-PRT-RE.safetensors）", lianBase.name(), "莲 的底模名");
            equal("sd", lianBase.stack(), "莲 归 sd 栈");
            equal(StackClassifier.KEYS_SOURCE, lianBase.source(), "莲 的底模来自张量键");
            // 没有张量键、只剩通用名时：按 SD1.5 时代的命名归家族，来源如实标成推断。
            Path bare = fixture.safetensors("bare-model-ckpt.safetensors", "{\"ss_sd_model_name\":\"model.ckpt\"}", Map.of());
            SdClient.BaseModel bareBase = client.resolveBaseModel("", "bare-model-ckpt", bare.toString(), SdClient.BaseModel.NONE);
            equal("SD 1.5（原底模名 model.ckpt）", bareBase.name(), "只剩通用名时也归 SD1.5（不是 model-ckpt）");
            equal("sd", bareBase.stack(), "只剩通用名时归 sd 栈");
            equal(StackClassifier.INFERRED_SOURCE, bareBase.source(), "只剩通用名时来源必须是推断");
            check(bareBase.evidence().contains("通用名") && bareBase.evidence().contains("SD 1.5"),
                    "判据说清「按通用名归入 SD 1.5」：" + bareBase.evidence());
            equal("SD 1.5", StackClassifier.baseModelOf(bare), "baseModelOf 也归 SD 1.5");
            check(!StackClassifier.knownStack("model-ckpt"), "model-ckpt 这种伪栈名不是已知族");
            equal(StackClassifier.slug("model.ckpt"), "model-ckpt", "slug 本身没变（只是不再拿它当栈名）");
        }
    }

    /**
     * ② **Civitai 记录优先**（用户明确要求）：{@code data/civitai/<文件>.json} 的 {@code base_model}
     * 比 safetensors 里的训练底模名可靠，所以它压过头部声明与张量键；映射到项目既有的家族/栈。
     */
    private static void civitaiBaseModelWins() throws Exception {
        // Civitai 的枚举写法 → 家族/栈（映射表就是 StackClassifier 的规范名表）。
        String[][] civitai = {
                {"SD 1.5", "SD 1.5", "sd"}, {"SD 1.4", "SD 1.4", "sd"}, {"SD 2.1", "SD 2.1", "sd"},
                {"SDXL 1.0", "SDXL", "xl"}, {"SDXL 1.0 LCM", "SDXL", "xl"},
                {"Illustrious", "Illustrious", "xl"}, {"NoobAI", "NoobAI", "xl"}, {"Pony", "Pony", "xl"},
                {"Animagine XL", "Animagine XL", "xl"}, {"Flux.1 D", "Flux", "flux"},
                {"Flux.1 Krea", "Flux.1 Krea", "krea"}, {"Qwen", "Qwen Image", "qwen"},
                {"Anima", "Anima", "anima"}, {"SD 3.5", "SD 3.5", "sd3"}, {"Hunyuan 1", "Hunyuan", "hunyuan"},
                {"Wan Video 14B t2v", "Wan Video", "wan"}, {"Chroma", "Chroma", "chroma"},
                {"Lumina", "Lumina", "lumina"}, {"Kolors", "Kolors", "kolors"},
                {"PixArt Sigma", "PixArt", "pixart"}, {"Playground v2", "Playground", "playground"},
                {"Stable Cascade", "Stable Cascade", "cascade"}, {"Z-Image Turbo", "Z-Image", "z-image"},
                {"Nitro-E", "Nitro-E", "nitro"}, {"ODOR", "ODOR", "odor"},
        };
        for (String[] item : civitai) {
            equal(item[1], StackClassifier.canonicalBaseModel(item[0]), "Civitai 底模「" + item[0] + "」的规范名");
            equal(item[2], StackClassifier.stackOfBaseModel(item[0]), "Civitai 底模「" + item[0] + "」的栈");
        }
        try (Fixture fixture = new Fixture()) {
            SdClient client = fixture.client();
            Map<String, String> sd15Keys = new LinkedHashMap<>();
            sd15Keys.put(SD15_TE_KEY, "[8, 768]");
            sd15Keys.put(ATTN2_KEY, "[8, 768]");
            Map<String, String> xlKeys = new LinkedHashMap<>();
            xlKeys.put(SDXL_TE1_KEY, "[8, 768]");
            xlKeys.put(SDXL_TE2_KEY, "[8, 1280]");
            xlKeys.put(ATTN2_INPUT_KEY, "[8, 2048]");
            // 真机同形的 model.ckpt LoRA：Civitai 说 SD 1.5，头部说的是通用名 model.ckpt。
            Path hiyori = fixture.safetensors("和泉妃爱.safetensors", "{\"ss_sd_model_name\":\"model.ckpt\"}", sd15Keys);
            SdClient.BaseModel recorded = client.resolveBaseModel("SD 1.5", "和泉妃爱", hiyori.toString(), SdClient.BaseModel.NONE);
            equal("SD 1.5", recorded.name(), "Civitai 的 baseModel 直接用（不再显示 model.ckpt）");
            equal("civitai", recorded.source(), "来源标记 civitai");
            equal("sd", recorded.stack(), "Civitai 的 SD 1.5 → sd 栈");
            equal("civitai", recorded.stackSource(), "栈来源也是 civitai（不是推断）");
            check(recorded.evidence().contains("base_model=SD 1.5"), "判据写出 Civitai 的原值：" + recorded.evidence());
            check(recorded.evidence().contains("model.ckpt") && recorded.evidence().contains("通用名"),
                    "判据顺带点明头部那个通用名（用户问得出来源）：" + recorded.evidence());
            equal("sd 1.5", recorded.groupKey(), "分组键跟底模名走");

            // Civitai 压过头部声明（头部说 SDXL、Civitai 说 NoobAI 时以 Civitai 为准，同栈要写清）。
            Path kanbe = fixture.safetensors("Kanbe_Kotori_1_nai-000034.safetensors",
                    "{\"ss_base_model_version\":\"sdxl_base_v1-0\"}", xlKeys);
            SdClient.BaseModel conflicted = client.resolveBaseModel("NoobAI", "Kanbe_Kotori_1_nai-000034", kanbe.toString(), SdClient.BaseModel.NONE);
            equal("NoobAI", conflicted.name(), "Civitai 的 NoobAI 优先于头部的 sdxl_base_v1-0");
            equal("civitai", conflicted.source(), "来源仍是 civitai");
            equal("xl", conflicted.stack(), "NoobAI → xl 栈");
            check(conflicted.evidence().contains("同一栈"), "两边同栈时判据说明是同一栈：" + conflicted.evidence());
            Path ilLora = fixture.safetensors("ureshinosayumi_IL_v1.safetensors",
                    "{\"ss_base_model_version\":\"sdxl_base_v1-0\"}", xlKeys);
            SdClient.BaseModel il = client.resolveBaseModel("Illustrious", "ureshinosayumi_IL_v1", ilLora.toString(), SdClient.BaseModel.NONE);
            equal("Illustrious", il.name(), "IL 系 LoRA 的 Civitai 底模");
            equal("xl", il.stack(), "Illustrious → xl（回归重点）");
            Path noobai = fixture.safetensors("Naruse_Shiroha_-_Summer_pockets_IL.safetensors",
                    "{\"ss_base_model_version\":\"sdxl_base_v1-0\"}", xlKeys);
            equal("xl", client.resolveBaseModel("Illustrious", "Naruse_Shiroha_-_Summer_pockets_IL",
                    noobai.toString(), SdClient.BaseModel.NONE).stack(), "IL 系列仍然归 xl 栈");
            // Civitai 说的和头部/张量键不同族时：以 Civitai 为准，并把冲突写出来。
            Path clash = fixture.safetensors("clash-lora.safetensors", "{\"ss_base_model_version\":\"sdxl_base_v1-0\"}", xlKeys);
            SdClient.BaseModel clashBase = client.resolveBaseModel("SD 1.5", "clash-lora", clash.toString(), SdClient.BaseModel.NONE);
            equal("sd", clashBase.stack(), "Civitai 说 SD 1.5 → sd 栈（压过头部与张量键的 SDXL）");
            check(clashBase.evidence().contains("以 Civitai 为准"), "冲突写进判据：" + clashBase.evidence());
            // Civitai 的「Other」（认不出族）：不许挡住更硬的实据，留给后面的名字兜底。
            SdClient.BaseModel other = client.resolveBaseModel("Other", "clash-lora", clash.toString(), SdClient.BaseModel.NONE);
            equal("SDXL", other.name(), "Civitai 的 Other 认不出族时，退回头部声明的 SDXL");
            equal("xl", other.stack(), "栈来自头部声明");
            // 下载记录里没有 baseModel（老记录）时同样退回后面的判据。
            SdClient.BaseModel none = client.resolveBaseModel("", "clash-lora", clash.toString(), SdClient.BaseModel.NONE);
            equal("SDXL", none.name(), "没有 Civitai 值时退回头部声明");
            equal(StackClassifier.HEADER_SOURCE, none.source(), "来源标记 safetensors-header");
        }
    }

    /**
     * ③ 元数据缺失时靠**张量键与形状**判族：{@code attn2} 的上下文维度 768＝SD1.x / 1024＝SD2.x /
     * 2048＝SDXL（只训了 UNet 的 LoRA 没有文本编码器键，只有这一条判得出来）。
     */
    private static void tensorKeysDecide() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SdClient client = fixture.client();
            Map<String, String> sd15 = new LinkedHashMap<>();
            sd15.put(SD15_TE_KEY, "[8, 768]");
            sd15.put(ATTN2_KEY, "[8, 768]");
            Map<String, String> sd15UnetOnly = new LinkedHashMap<>();
            sd15UnetOnly.put(ATTN2_KEY, "[8, 768]");
            Map<String, String> sd2 = new LinkedHashMap<>();
            sd2.put("lora_te_text_model_encoder_layers_0_mlp_fc1.lora_down.weight", "[8, 1024]");
            Map<String, String> sd2UnetOnly = new LinkedHashMap<>();
            sd2UnetOnly.put(ATTN2_KEY, "[8, 1024]");
            Map<String, String> xl = new LinkedHashMap<>();
            xl.put(SDXL_TE1_KEY, "[8, 768]");
            xl.put(SDXL_TE2_KEY, "[8, 1280]");
            Map<String, String> xlUnetOnly = new LinkedHashMap<>();
            xlUnetOnly.put(ATTN2_INPUT_KEY, "[8, 2048]");
            Map<String, String> unknown = new LinkedHashMap<>();
            unknown.put("plain.tensor", "[1]");
            Map<String, String> placeholderUnet = new LinkedHashMap<>();
            placeholderUnet.put("lora_unet_x.lora_down.weight", "[1]");

            String none = "{\"format\":\"pt\"}";
            Path sd15File = fixture.safetensors("keys-sd15.safetensors", none, sd15);
            Path sd15Unet = fixture.safetensors("keys-sd15-unet.safetensors", none, sd15UnetOnly);
            Path sd2File = fixture.safetensors("keys-sd2.safetensors", "{\"ss_v2\":\"True\"}", sd2);
            Path sd2Unet = fixture.safetensors("keys-sd2-unet.safetensors", none, sd2UnetOnly);
            Path xlFile = fixture.safetensors("keys-sdxl.safetensors", none, xl);
            Path xlUnet = fixture.safetensors("keys-sdxl-unet.safetensors", none, xlUnetOnly);
            Path unknownFile = fixture.safetensors("keys-unknown.safetensors", none, unknown);
            Path placeholderFile = fixture.safetensors("keys-placeholder.safetensors", "{\"ss_sd_model_name\":\"model.safetensors\"}", placeholderUnet);

            equal("SD 1.5", StackClassifier.baseModelOf(sd15File), "lora_te_*（单文本编码器）→ SD 1.5");
            equal("SD 1.5", StackClassifier.baseModelOf(sd15Unet), "attn2 上下文维度 768 → SD 1.5（unet-only 也判得出来）");
            check(StackClassifier.structural(sd15Unet).evidence().contains("768"),
                    "判据写出维度：" + StackClassifier.structural(sd15Unet).evidence());
            equal("SD 2.1", StackClassifier.baseModelOf(sd2File), "lora_te_* + ss_v2=True → SD 2.1");
            equal("SD 2.1", StackClassifier.baseModelOf(sd2Unet), "attn2 上下文维度 1024 → SD 2.1（OpenCLIP-H）");
            equal("SDXL", StackClassifier.baseModelOf(xlFile), "lora_te1_*/lora_te2_* → SDXL");
            equal("SDXL", StackClassifier.baseModelOf(xlUnet), "attn2 上下文维度 2048 → SDXL（unet-only 也判得出来）");
            check(StackClassifier.structural(xlUnet).evidence().contains("2048"),
                    "判据写出维度：" + StackClassifier.structural(xlUnet).evidence());
            equal("", StackClassifier.baseModelOf(unknownFile), "认不出来就是空串，绝不编");
            equal("", StackClassifier.baseModelOf(placeholderFile), "只有 lora_unet_*（没有 attn2 形状）不算实据");
            equal("", StackClassifier.structural(placeholderFile).baseModel(), "结构判不出来就是空串");

            // 走完整的解析链（没有 Civitai、没有头部声明、没有 Forge 元数据）。
            for (Path file : List.of(sd15File, sd15Unet, sd2File, sd2Unet, xlFile, xlUnet)) {
                SdClient.BaseModel base = client.resolveBaseModel("", file.getFileName().toString(), file.toString(), SdClient.BaseModel.NONE);
                equal(StackClassifier.KEYS_SOURCE, base.source(), "「" + file.getFileName() + "」的底模来自张量键");
                equal(StackClassifier.KEYS_SOURCE, base.stackSource(), "「" + file.getFileName() + "」的栈来源也是实据");
            }
            equal("sd", client.resolveBaseModel("", "a", sd15Unet.toString(), SdClient.BaseModel.NONE).stack(), "768 → sd 栈");
            equal("sd", client.resolveBaseModel("", "a", sd2Unet.toString(), SdClient.BaseModel.NONE).stack(), "1024 → sd 栈");
            equal("xl", client.resolveBaseModel("", "a", xlUnet.toString(), SdClient.BaseModel.NONE).stack(), "2048 → xl 栈");
            // 关键回归：**SDXL 的 unet-only LoRA 绝不能被判成 SD1.5**。
            SdClient.BaseModel xlOnly = client.resolveBaseModel("", "unet-only-xl", xlUnet.toString(), SdClient.BaseModel.NONE);
            equal("SDXL", xlOnly.name(), "unet-only 的 SDXL LoRA 仍然是 SDXL（回归重点）");
            equal("xl", xlOnly.stack(), "unet-only 的 SDXL LoRA 仍然归 xl 栈");
            // 真机 莉贝尔noobXL 的形状：ss_base_model_version 缺失、只有 noobaiXL 训练名 + unet 键。
            Path libel = fixture.safetensors("莉贝尔noobXL-000042.safetensors",
                    "{\"ss_sd_model_name\":\"noobaiXLNAIXL_epsilonPred11Version.safetensors\"}", xlUnetOnly);
            SdClient.BaseModel libelBase = client.resolveBaseModel("", "莉贝尔noobXL-000042", libel.toString(), SdClient.BaseModel.NONE);
            equal("xl", libelBase.stack(), "莉贝尔noobXL 仍然归 xl 栈");
            equal("NoobAI", libelBase.name(), "noobaiXL 训练名归一成 NoobAI");
        }
    }

    /**
     * ④ 元数据与张量键**冲突**时按项目优先级（写清顺序）：
     * Civitai 记录 → 头部声明的具体底模/架构 → Forge 元数据 → 张量键/形状 → 通用底模名 → 预设栈。
     */
    private static void evidenceConflicts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SdClient client = fixture.client();
            Map<String, String> sd15 = new LinkedHashMap<>();
            sd15.put(SD15_TE_KEY, "[8, 768]");
            sd15.put(ATTN2_KEY, "[8, 768]");
            Map<String, String> xl = new LinkedHashMap<>();
            xl.put(SDXL_TE1_KEY, "[8, 768]");
            xl.put(SDXL_TE2_KEY, "[8, 1280]");
            xl.put(ATTN2_INPUT_KEY, "[8, 2048]");

            // 冲突 A：头部只给了通用名 model.ckpt，张量键是 SDXL 形态 → **张量键赢**（通用名不可信）。
            Path genericButXl = fixture.safetensors("conflict-generic-xl.safetensors",
                    "{\"ss_sd_model_name\":\"model.ckpt\"}", xl);
            SdClient.BaseModel a = client.resolveBaseModel("", "conflict-generic-xl", genericButXl.toString(), SdClient.BaseModel.NONE);
            equal("SDXL", a.name(), "冲突 A：通用名 vs 张量键 → 张量键赢");
            equal("xl", a.stack(), "冲突 A：归 xl 栈");
            equal(StackClassifier.KEYS_SOURCE, a.source(), "冲突 A：来源是张量键");
            check(a.evidence().contains("model.ckpt") && a.evidence().contains("通用底模名"),
                    "冲突 A：判据里保留通用名并说明不作为底模：" + a.evidence());
            check(!a.name().contains("原底模名"), "冲突 A：不同族时不并排显示原底模名：" + a.name());

            // 冲突 B：头部声明的具体架构 SDXL，张量键是 SD1.5 形态 → **头部声明赢**（声明比键更硬）。
            Path declaredXlKeysSd = fixture.safetensors("conflict-declared-xl.safetensors",
                    "{\"ss_base_model_version\":\"sdxl_base_v1-0\"}", sd15);
            SdClient.BaseModel b = client.resolveBaseModel("", "conflict-declared-xl", declaredXlKeysSd.toString(), SdClient.BaseModel.NONE);
            equal("SDXL", b.name(), "冲突 B：头部声明 vs 张量键 → 头部声明赢");
            equal("xl", b.stack(), "冲突 B：归 xl 栈");
            equal(StackClassifier.HEADER_SOURCE, b.source(), "冲突 B：来源是 safetensors 头部");

            // 冲突 C：Civitai 说 SD 1.5，头部声明 SDXL，张量键 SDXL → **Civitai 赢**（用户明确要求）。
            Path all = fixture.safetensors("conflict-civitai.safetensors",
                    "{\"ss_base_model_version\":\"sdxl_base_v1-0\"}", xl);
            SdClient.BaseModel c = client.resolveBaseModel("SD 1.5", "conflict-civitai", all.toString(), SdClient.BaseModel.NONE);
            equal("sd", c.stack(), "冲突 C：Civitai 压过头部与张量键");
            equal("civitai", c.source(), "冲突 C：来源是 civitai");
            check(c.evidence().contains("SDXL"), "冲突 C：判据里如实写出头部说的是什么：" + c.evidence());

            // 冲突 D：头部只说通用名，Forge 元数据说了具体架构 → Forge 元数据赢（都在张量键之前）。
            fixture.putForgeMetadata("conflict-forge-lora", "{\"ss_base_model_version\":\"anima\"}");
            client.loras();     // Forge 的元数据是读 LoRA 列表时顺手记下的（真机也是这条路径）
            Path forgeFile = fixture.safetensors("conflict-forge-lora.safetensors",
                    "{\"ss_sd_model_name\":\"model.ckpt\"}", Map.of());
            SdClient.BaseModel d = client.resolveBaseModel("", "conflict-forge-lora", forgeFile.toString(), SdClient.BaseModel.NONE);
            equal("Anima", d.name(), "冲突 D：Forge 元数据的具体值赢过头部的通用名");
            equal("anima", d.stack(), "冲突 D：归 anima 栈");
            equal(StackClassifier.FORGE_SOURCE, d.source(), "冲突 D：来源是 forge-metadata");

            // 冲突 E：只有通用名 + 没有别的实据 → 归 SD1.5，来源标推断（绝不编具体检查点）。
            Path onlyGeneric = fixture.safetensors("conflict-only-generic.safetensors",
                    "{\"ss_sd_model_name\":\"Anything-v5.0-PRT-RE.safetensors\"}", Map.of());
            SdClient.BaseModel e = client.resolveBaseModel("", "conflict-only-generic", onlyGeneric.toString(), SdClient.BaseModel.NONE);
            equal("SD 1.5（原底模名 Anything-v5.0-PRT-RE.safetensors）", e.name(), "冲突 E：只剩通用名 → 归 SD1.5");
            equal(StackClassifier.INFERRED_SOURCE, e.source(), "冲突 E：来源是推断");
            equal("sd", e.stack(), "冲突 E：sd 栈");
            equal(StackClassifier.INFERRED_SOURCE, e.stackSource(), "冲突 E：栈也是推断出来的");
        }
    }

    /** ⑤ 伪栈名（{@code model-ckpt} 这种）不许出现在**任何**返回值里（底模名/栈/分组键/判据/网页字段）。 */
    private static void pseudoStackScan() throws Exception {
        List<String> values = new ArrayList<>();
        try (Fixture fixture = new Fixture()) {
            SdClient client = fixture.client();
            Map<String, String> sd15 = new LinkedHashMap<>();
            sd15.put(SD15_TE_KEY, "[8, 768]");
            sd15.put(ATTN2_KEY, "[8, 768]");
            // 每个通用名都造一个真文件（同样的 SD1.5 键），把整条解析链的输出全收进来。
            for (int index = 0; index < GENERIC_NAMES.size(); index++) {
                String generic = GENERIC_NAMES.get(index);
                values.add(StackClassifier.stackOfBaseModel(generic));
                values.add(StackClassifier.canonicalBaseModel(generic));
                values.add(StackClassifier.stackOf(generic, "mystery-model.safetensors"));
                values.add(StackClassifier.stackLabel(StackClassifier.stackOfBaseModel(generic)));
                String name = "scan-" + index + "-lora";
                Path file = fixture.safetensors(name + ".safetensors", "{\"ss_sd_model_name\":\"" + generic + "\"}", sd15);
                SdClient.BaseModel base = client.resolveBaseModel("", name, file.toString(), SdClient.BaseModel.NONE);
                values.add(base.name());
                values.add(base.stack());
                values.add(base.stackLabel());
                values.add(base.stackSource());
                values.add(base.source());
                values.add(base.groupKey());
                values.add(base.evidence());
                values.add(base.note());
                // 只有通用名、连张量键都没有的那种（老文件可能就这样）。
                Path bare = fixture.safetensors("scan-bare-" + index + ".safetensors",
                        "{\"ss_sd_model_name\":\"" + generic + "\"}", Map.of());
                SdClient.BaseModel bareBase = client.resolveBaseModel("", "scan-bare-" + index, bare.toString(), SdClient.BaseModel.NONE);
                values.add(bareBase.name());
                values.add(bareBase.stack());
                values.add(bareBase.evidence());
                values.add(StackClassifier.baseModelOf(bare));
                values.add(StackClassifier.baseModelOf(file));
            }
            // 网页那条路（/api/loras 的每个字段）：Bot 读 data/civitai/<文件>.json，这里给真机同形的记录。
            fixture.writeConfig();
            String[] realWorld = {"和泉妃爱", "姬野星奏", "春日野穹", "美亚", "锦亚澄", "风又音理"};
            for (String name : realWorld) {
                Path file = fixture.safetensors(name + ".safetensors", "{\"ss_sd_model_name\":\"model.ckpt\"}", sd15);
                fixture.putLora(name, file);
                fixture.putCivitaiRecord(name + ".safetensors", "SD 1.5");
            }
            try (Bot bot = fixture.bot()) {
                JsonObject payload = bot.webLoras();
                for (JsonElement element : payload.getAsJsonArray("loras")) {
                    JsonObject item = element.getAsJsonObject();
                    for (String key : List.of("baseModel", "baseModelLabel", "baseModelGroupKey", "evidence",
                            "stack", "stackLabel", "stackSource", "preset", "groupKey")) {
                        values.add(Json.str(item, key, ""));
                    }
                }
                for (JsonElement element : payload.getAsJsonArray("groups")) {
                    JsonObject group = element.getAsJsonObject();
                    values.add(Json.str(group, "key", ""));
                    values.add(Json.str(group, "label", ""));
                    values.add(group.toString());
                }
            }
        }
        // 全量扫一遍：任何返回值里出现伪栈名的形态就是不合格（一次性报出所有违规，便于定位）。
        List<String> violations = new ArrayList<>();
        for (String value : values) {
            for (String pseudo : PSEUDO_STACKS) {
                if (value.contains(pseudo)) violations.add("「" + pseudo + "」出现在：" + value);
            }
        }
        check(violations.isEmpty(), "返回值里不许出现伪栈名：" + violations);
        check(values.size() >= 200, "扫描过的返回值条数：" + values.size());
        for (String name : GENERIC_NAMES) {
            String stack = StackClassifier.stackOfBaseModel(name);
            equal("sd", stack, "通用名「" + name + "」的栈就是项目既有的 sd");
            check(StackClassifier.knownStack(stack), "通用名的栈是已知族：" + name);
            equal("SD 1.5", StackClassifier.canonicalBaseModel(name), "通用名的规范名是 SD 1.5：" + name);
        }
    }

    /** 下载 LoRA 生成的展示图样式要连底模与采样参数一起写进 data/local-styles.json。 */
    private static void showcaseStyleCarriesModel() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SdClient client = fixture.client();
            client.settings();
            JsonObject model = client.styleModelParams("NoobAI", SdClient.CIVITAI_SOURCE);
            equal("NoobAI", Json.str(model, "baseModel", ""), "样式记下 LoRA 的底模");
            equal("civitai", Json.str(model, "baseModelSource", ""), "底模来源一起记下");
            equal("ER SDE", Json.str(model, "sampler", ""), "采样方法取当前预设栈");
            equal("Beta", Json.str(model, "scheduler", ""), "调度器取当前预设栈");
            equal(32L, (long) Json.num(model, "steps", 0), "步数取当前预设栈");
            equal(4.0, Json.decimal(model, "cfg", 0), "CFG 取当前预设栈");
            equal(3.0, Json.decimal(model, "distilledCfg", 0), "Shift（蒸馏 CFG）取当前预设栈");
            equal(1024L, (long) Json.num(model, "width", 0), "预设没配尺寸（0）时回退当前设置");
            equal(1024L, (long) Json.num(model, "height", 0), "预设没配尺寸（0）时回退当前设置");
            check(!model.has("checkpoint"), "底模不在当前 anima 栈里就不写检查点（写错栈出全灰废图）");

            Path file = fixture.loraDir.resolve(LORA_PLACEHOLDER);
            Files.writeString(file, "stub");
            CivitaiClient.DownloadedLora download = new CivitaiClient.DownloadedLora("测试模型", "v1", "NoobAI",
                    List.of(), file, true, 11, 22,
                    List.of(new CivitaiClient.ShowcasePrompt(1, "portrait, <lora:old:1>", "bad anatomy", true, "")));
            String report = CivitaiStyleSync.sync(fixture.root, download, "<lora:test:1>", client, true, null, model);
            check(report.contains("模型参数"), "回执要报出这次给样式记了什么参数：" + report);

            LocalStyles styles = new LocalStyles(fixture.root);
            LocalStyles.Style saved = styles.get("测试模型 1");
            check(saved != null && saved.hasModel(), "展示图样式落了盘并带 model：" + saved);
            JsonObject savedModel = saved.model();
            equal("NoobAI", Json.str(savedModel, "baseModel", ""), "写盘的样式带底模");
            equal("civitai", Json.str(savedModel, "baseModelSource", ""), "写盘的样式带底模来源");
            equal(32L, (long) Json.num(savedModel, "steps", 0), "写盘的样式带步数");
            equal(3.0, Json.decimal(savedModel, "distilledCfg", 0), "写盘的样式带 Shift");
            check(saved.modelSummary().contains("底模 NoobAI") && saved.modelSummary().contains("Civitai 记录"),
                    "样式摘要写明底模与来源：" + saved.modelSummary());

            String again = CivitaiStyleSync.sync(fixture.root, download, "<lora:test:1>", client, true, null, model);
            check(again.contains("复用 1"), "重复同步是复用而不是重写：" + again);
            // 老调用方不传 model 时，样式原来记着的参数不能被抹掉。
            String old = CivitaiStyleSync.sync(fixture.root, download, "<lora:test:1>", client, true, null, null);
            check(old.contains("复用 1"), "不传参数时同样算复用：" + old);
            equal("NoobAI", Json.str(styles.get("测试模型 1").model(), "baseModel", ""), "不传参数时保留原样式的底模");
        }
    }

    /** 载入样式时把记着的底模/采样方法/调度器/步数/CFG/Shift/尺寸套回机器人设置。 */
    private static void styleLoadAppliesParams() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SdClient client = fixture.client();
            client.settings();
            JsonObject model = client.styleModelParams("Anima", SdClient.CIVITAI_SOURCE);
            equal("animaCatTower_v11.safetensors", Json.str(model, "checkpoint", ""), "和当前栈对得上的底模才写成检查点");
            List<String> applied = client.applyModelParams(model);
            check(applied.stream().anyMatch(item -> item.contains("animaCatTower_v11")), "底模被套上：" + applied);
            check(applied.stream().anyMatch(item -> item.contains("ER SDE")), "采样方法被套上：" + applied);
            equal("ER SDE", client.settings().samplerName(), "采样方法套回机器人设置");
            equal(32, client.parameters().steps(), "步数套回机器人设置");
            equal(4.0, client.parameters().cfgScale(), "CFG 套回机器人设置");
            equal(3.0, client.settings().distilledCfg(), "Shift 套回机器人设置");
            equal("Beta", client.settings().scheduler(), "调度器套回机器人设置");
            equal(1024L, (long) client.settings().width(), "尺寸套回机器人设置");
        }
    }

    /** 什么来源都没有、也没有 Forge 预设栈时：如实说"未识别"，不许编一个底模名出来。 */
    private static void unknownIsNotFabricated() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.forge = false;
            SdClient client = fixture.client();
            SdClient.BaseModel nothing = client.resolveBaseModel("", "shirohaANY-clothes",
                    fixture.loraDir.resolve(LORA_PLACEHOLDER).toString());
            check(!nothing.known(), "三层都取不到就是没有底模，不能编：" + nothing.name());
            equal("", nothing.groupKey(), "未识别没有分组键");
            check(!client.resolveBaseModel("model.safetensors", "x", "").known(), "占位文件名也不能当底模");
            JsonObject model = client.styleModelParams("", "");
            check(!model.has("baseModel"), "没有预设栈时样式不写底模：" + model);
            check(!model.has("baseModelSource"), "来源也不写");
            // 采样参数仍然回退到机器人当前设置（第 4 条：预设取不到就用当前设置）。
            client.settings();
            equal(1024L, (long) Json.num(client.styleModelParams("", ""), "width", 0), "没有预设栈时尺寸用当前设置");
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    /** 造一个**最小合法**的 safetensors：8 字节小端头部长度 + 头部 JSON + 一小段"权重"，张量带 shape。 */
    private static Path safetensors(Path dir, String name, String metadataJson, Map<String, String> tensors) throws IOException {
        JsonObject header = new JsonObject();
        if (metadataJson != null && !metadataJson.isBlank())
            header.add("__metadata__", JsonParser.parseString(metadataJson));
        for (Map.Entry<String, String> tensor : tensors.entrySet()) {
            JsonObject info = new JsonObject();
            info.addProperty("dtype", "F16");
            info.add("shape", JsonParser.parseString(tensor.getValue()));
            info.add("data_offsets", JsonParser.parseString("[0,2]"));
            header.add(tensor.getKey(), info);
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

    /** 桩 Forge／WebUI：桥接状态、Forge 预设栈、LoRA 列表（带真实形状的 metadata）。 */
    private static final class Fixture implements AutoCloseable {
        final Path root, loraDir;
        final HttpServer server;
        final ExecutorService executor;
        final JsonObject configuration = new JsonObject();
        volatile boolean forge = true;
        volatile int revision = 1;
        volatile String positive = "live positive", negative = "live negative";
        volatile String sampler = "Euler a", scheduler = "";
        volatile double distilled;
        volatile int width = 1024, height = 1024;

        Fixture() throws IOException {
            Path work = Path.of("work", "lora-base-model-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            loraDir = Files.createDirectories(root.resolve("lora"));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "lora-mock"); thread.setDaemon(true); return thread;
            });
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            configuration.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            configuration.addProperty("timeout_seconds", 5);
            configuration.addProperty("cfg_scale", 7.5);
        }

        SdClient client() throws IOException { return new SdClient(root, configuration); }

        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/pixiko-bridge/v1/prompts")) {
                    if (exchange.getRequestMethod().equals("PUT")) {
                        JsonObject payload = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        positive = Json.str(payload, "positive", positive);
                        negative = Json.str(payload, "negative", negative);
                        if (payload.has("sampler_name")) sampler = Json.str(payload, "sampler_name", sampler);
                        if (payload.has("width")) width = Json.num(payload, "width", width);
                        if (payload.has("height")) height = Json.num(payload, "height", height);
                        if (payload.has("scheduler")) scheduler = Json.str(payload, "scheduler", scheduler);
                        if (payload.has("distilled_cfg")) distilled = Json.decimal(payload, "distilled_cfg", distilled);
                        revision++;
                    }
                    send(exchange, 200, Json.GSON.toJson(state()));
                } else if (path.equals("/sdapi/v1/options")) {
                    JsonObject options = new JsonObject();
                    options.addProperty("sd_model_checkpoint", "animaCatTower_v11.safetensors");
                    if (forge) {
                        options.addProperty("forge_preset", "anima");
                        options.addProperty("forge_checkpoint_anima", "animaCatTower_v11.safetensors");
                        options.addProperty("anima_t2i_sampler", "ER SDE");
                        options.addProperty("anima_t2i_scheduler", "Beta");
                        options.addProperty("anima_t2i_step", 32);
                        options.addProperty("anima_t2i_cfg", 4);
                        options.addProperty("anima_t2i_dcfg", 3);
                        // 这台 Forge Neo 的 Anima 栈里尺寸就是 0（表示"用当前值"）。
                        options.addProperty("anima_t2i_width", 0);
                        options.addProperty("anima_t2i_height", 0);
                    }
                    send(exchange, 200, Json.GSON.toJson(options));
                } else if (path.equals("/sdapi/v1/sd-models")) {
                    JsonArray list = new JsonArray();
                    JsonObject item = new JsonObject();
                    item.addProperty("title", "animaCatTower_v11.safetensors [0351429bd9]");
                    list.add(item);
                    send(exchange, 200, Json.GSON.toJson(list));
                } else if (path.equals("/sdapi/v1/samplers")) {
                    JsonArray list = new JsonArray();
                    for (String name : List.of("Euler a", "ER SDE")) {
                        JsonObject item = new JsonObject();
                        item.addProperty("name", name);
                        item.add("aliases", new JsonArray());
                        list.add(item);
                    }
                    send(exchange, 200, Json.GSON.toJson(list));
                } else if (path.equals("/sdapi/v1/loras")) {
                    send(exchange, 200, Json.GSON.toJson(loras()));
                } else send(exchange, 404, "{}");
            } finally { exchange.close(); }
        }

        /** 与真机同形：一条只有 sd-scripts 占位、一条有 ss_base_model_version；外加用例自己挂的。 */
        JsonArray loras() {
            JsonArray list = new JsonArray();
            JsonObject placeholder = new JsonObject();
            placeholder.addProperty("name", "shirohaANY-clothes");
            placeholder.addProperty("alias", "shirohaANY");
            placeholder.addProperty("path", loraDir.resolve(LORA_PLACEHOLDER).toString());
            JsonObject placeholderMeta = new JsonObject();
            placeholderMeta.addProperty("ss_sd_model_name", "model.safetensors");
            placeholderMeta.addProperty("ss_sd_model_hash", "886d2c88");
            placeholder.add("metadata", placeholderMeta);
            list.add(placeholder);
            JsonObject anima = new JsonObject();
            anima.addProperty("name", "鸣濑白羽");
            anima.addProperty("alias", "my_anima_lora");
            anima.addProperty("path", loraDir.resolve(LORA_ANIMA).toString());
            JsonObject animaMeta = new JsonObject();
            animaMeta.addProperty("ss_base_model_version", "anima");
            animaMeta.addProperty("modelspec.architecture", "stable-diffusion-v1/lora");
            anima.add("metadata", animaMeta);
            list.add(anima);
            for (JsonObject extra : extraLoras) list.add(extra);
            return list;
        }

        /** 用例自己挂到桩 Forge 的 LoRA 列表上的条目（默认空，既有条数断言不受影响）。 */
        final List<JsonObject> extraLoras = new ArrayList<>();
        final Map<String, String> forgeMetadata = new LinkedHashMap<>();

        /** 在夹具的 LoRA 目录里造一个**真**的 safetensors（键 → shape），并挂进桩 Forge 的列表。 */
        Path safetensors(String name, String metadataJson, Map<String, String> tensors) throws IOException {
            Path file = LoraBaseModelTest.safetensors(loraDir, name, metadataJson, tensors);
            putLora(name.substring(0, name.length() - ".safetensors".length()), file);
            return file;
        }

        /** 只挂进桩 Forge 的 LoRA 列表（不造文件；文件由用例自己造）。 */
        void putLora(String name, Path file) {
            for (JsonObject item : extraLoras) {
                if (name.equals(Json.str(item, "name", ""))) return;
            }
            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            item.addProperty("alias", name);
            item.addProperty("path", file == null ? loraDir.resolve(name + ".safetensors").toString() : file.toString());
            String metadata = forgeMetadata.get(name);
            if (metadata != null) item.add("metadata", JsonParser.parseString(metadata));
            extraLoras.add(item);
        }

        /** 桩 Forge 的 LoRA 元数据（{@code /sdapi/v1/loras} 的 metadata 字段）。 */
        void putForgeMetadata(String name, String metadataJson) {
            forgeMetadata.put(name, metadataJson);
            putLora(name, null);
            for (JsonObject item : extraLoras) {
                if (name.equals(Json.str(item, "name", ""))) item.add("metadata", JsonParser.parseString(metadataJson));
            }
        }

        /** 写一份 Civitai 下载记录（{@code data/civitai/<文件名>.json}，下载路径就是这么落的盘）。 */
        void putCivitaiRecord(String filename, String baseModel) throws IOException {
            JsonObject record = new JsonObject();
            record.addProperty("model_id", 1);
            record.addProperty("version_id", 2);
            record.addProperty("model_name", "测试模型");
            record.addProperty("version_name", "v1");
            record.addProperty("base_model", baseModel);
            record.addProperty("path", loraDir.resolve(filename).toString());
            Json.atomicWrite(Files.createDirectories(root.resolve("data/civitai")).resolve(filename + ".json"), record);
        }

        /** Bot 要读 {@code config.json}（网页接口那条路才走得到 Civitai 记录）。 */
        void writeConfig() throws IOException {
            JsonObject civitai = new JsonObject();
            civitai.addProperty("lora_dir", loraDir.toString());
            JsonObject config = new JsonObject();
            config.add("sd", configuration);
            config.add("civitai", civitai);
            Json.atomicWrite(root.resolve("config.json"), config);
            Files.createDirectories(root.resolve("data"));
        }

        Bot bot() throws Exception {
            writeConfig();
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

        JsonObject state() {
            JsonObject state = new JsonObject();
            state.addProperty("positive", positive);
            state.addProperty("negative", negative);
            state.addProperty("revision", revision);
            state.addProperty("source", "webui-live");
            state.addProperty("live", true);
            state.addProperty("settings_initialized", true);
            state.addProperty("sampler_name", sampler);
            state.add("styles", new JsonArray());
            state.addProperty("width", width);
            state.addProperty("height", height);
            if (!scheduler.isBlank()) state.addProperty("scheduler", scheduler);
            if (distilled > 0) state.addProperty("distilled_cfg", distilled);
            return state;
        }

        static void send(HttpExchange exchange, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        public void close() throws IOException {
            server.stop(0);
            executor.shutdownNow();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

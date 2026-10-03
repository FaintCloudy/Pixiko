package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
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
            presetMapping();
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

    // ---------------------------------------------------------------- 桩

    /** 桩 Forge：三个底模（anima 结构 / SDXL 结构 / 认不出）+ 三个 LoRA（anima 元数据 / SD1.5 结构 / 全无）。 */
    private static final class Fixture implements AutoCloseable {
        private final Path root;
        private final HttpServer server;
        private final ExecutorService executor;
        private final JsonObject sdConfig = new JsonObject();

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
            options.addProperty("forge_checkpoint_anima", "animaCatTower_v11.safetensors");
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
            for (Path file : List.of(animaCheckpoint, sdxlCheckpoint, unknownCheckpoint)) {
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

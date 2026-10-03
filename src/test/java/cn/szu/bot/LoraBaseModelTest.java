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

/**
 * LoRA 的底模识别与展示图样式的模型参数（全部跑在本机桩服务上，不联网）。
 *
 * <p>覆盖：底模来源优先级（Civitai 记录 → Forge 元数据 → 预设推断）、
 * 占位元数据不算底模、展示图样式写盘带 model、样式载入把参数套回机器人设置、
 * 三层都读不到时**不编造**底模名。
 */
public final class LoraBaseModelTest {
    private static int assertions;
    private static final String LORA_PLACEHOLDER = "shirohaANY-clothes.safetensors", LORA_ANIMA = "鸣濑白羽.safetensors";

    public static void main(String[] args) throws Exception {
        baseModelPriority();
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

        /** 与真机同形：一条只有 sd-scripts 占位、一条有 ss_base_model_version。 */
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
            return list;
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

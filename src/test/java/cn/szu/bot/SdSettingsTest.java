package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import cn.szu.bot.sd.GenerationParameters;
import cn.szu.bot.sd.SdClient;

/** Catalogs, live state, migration and generation are tested only against disposable loopback servers. */
public final class SdSettingsTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        liveSettingsAndPartialEdits();
        oldBridgeDefaultsAndPersistence();
        newBridgeInitializesFromSavedSettings();
        coherentGenerationSnapshot();
        conflictsAndFailedWrites();
        validationBeforeMutation();
        parameterChangesAreLogged();
        parametersAreForgeIndependent();
        offlineAndTimeout();
        System.out.println("SdSettingsTest: " + assertions + " assertions passed.");
    }

    /**
     * 用户口径（最高优先级）：「机器人的参数与 Forge 独立」——桥接只有一个方向，
     * 机器人 → Forge（出图前推送）；**禁止** Forge → 机器人（不再"跟随 WebUI 页面"采纳对方的值）。
     *
     * <p>事故（2026-10-07 真机日志）：04:07:00 用户改成 1600×1440 → 04:07:01 外部页面写回 960×1440
     * → 04:07:03 {@code refresh()} 把它顶回 → 任务按 960×1440 出图。旧方案用"来源优先级 + 时刻标记"
     * 只是缓解（页面实时连接时照样采纳）；现在改成**根本不读回来**，那套标记也一并删掉了。
     *
     * <p>规格里的 8 条定向断言就在这里，一条不多。
     */
    private static void parametersAreForgeIndependent() throws Exception {
        try (Fixture f = new Fixture()) {
            SdClient client = f.client();
            SdClient.GenerationSettings sized = client.setSize(832, 1216);
            SdClient.GenerationSettings picked = client.setSampler("DPM++ 2M");
            client.setParameter("steps", "33");
            client.setParameter("cfg", "7");
            SdClient.GenerationRequest captured = client.generationRequest();
            // ① 用户显式设的 832×1216，.size/.sampler 回执照旧，生成前解析就是它。
            check(sized.width() == 832 && sized.height() == 1216 && "DPM++ 2M".equals(picked.samplerName())
                            && captured.settings().width() == 832 && captured.settings().height() == 1216,
                    "① .size/.sampler receipts and the generation snapshot use the user's values: "
                            + captured.settings().width() + "×" + captured.settings().height());
            // ② 页面没实时连接时把它自己那份旧尺寸 PUT 回桥接：机器人参数不变，生成仍用 832×1216。
            f.live = false; f.width = 960; f.height = 1440; f.sampler = "DDIM"; f.revision++;
            SdClient.GenerationSettings stale = client.settings();
            SdClient.GenerationRequest afterStale = client.generationRequest();
            check(stale.width() == 832 && stale.height() == 1216
                            && afterStale.settings().width() == 832 && afterStale.settings().height() == 1216,
                    "② a stale page write-back cannot change the size: " + stale.width() + "×" + stale.height());
            // ③ 页面真在实时同步（心跳认证过）那份同样不采纳——比"来源优先级"那套彻底。
            f.live = true; f.width = 1216; f.height = 832; f.revision++;
            SdClient.GenerationSettings live = client.settings();
            check(live.width() == 832 && live.height() == 1216,
                    "③ a live page cannot change the size either: " + live.width() + "×" + live.height());
            // ④ 采样方法也只认本地那份（页面写回的 DDIM 不算数）。
            check("DPM++ 2M".equals(live.samplerName()), "④ the page cannot change the sampler: " + live.samplerName());
            // ⑤ 步数 / CFG 存在机器人自己的记录里（data/sd-parameters.json），页面怎么写都不动。
            GenerationParameters parameters = client.parameters();
            check(parameters.steps() == 33 && parameters.cfgScale() == 7,
                    "⑤ steps/CFG stay local: steps=" + parameters.steps() + " cfg=" + parameters.cfgScale());
            // ⑥ 出图前参数仍然**推**给 WebUI：txt2img 请求体里就是机器人这一份（推送这条保留）。
            client.generate(captured);
            check(f.lastGeneration.get("width").getAsInt() == 832 && f.lastGeneration.get("height").getAsInt() == 1216
                            && "DPM++ 2M".equals(f.lastGeneration.get("sampler_name").getAsString())
                            && f.lastGeneration.get("steps").getAsInt() == 33
                            && f.lastGeneration.get("cfg_scale").getAsDouble() == 7,
                    "⑥ generation still pushes the bot's parameters to WebUI: " + f.lastGeneration);
            // ⑦ 载入样式仍然套用样式尺寸（超限时按 fitGenerationSize 同比例缩）——机器人自己发起的行为。
            JsonObject styleModel = new JsonObject();
            styleModel.addProperty("width", 2400);
            styleModel.addProperty("height", 3744);
            List<String> applied = client.applyModelParams(styleModel);
            int[] fitted = SdClient.fitGenerationSize(2400, 3744);
            check(applied.stream().anyMatch(text -> text.contains("尺寸 2400×3744 → 同比例缩到"))
                            && client.settings().width() == fitted[0] && client.settings().height() == fitted[1],
                    "⑦ loading a style still applies its fitted size: " + applied);
            // ⑧ 底模 / VAE 的**只读观测**仍在（VAE 冲突防呆要用它）：不回读参数 ≠ 不读模型信息。
            f.optionsAvailable = true;
            String checkpoint = client.parameters().checkpoint();
            String vae = client.vae();
            check("animaCatTower_v11.safetensors [aaaa]".equals(checkpoint) && "Automatic".equals(vae),
                    "⑧ checkpoint/VAE remain read-only observable: " + checkpoint + " / " + vae);
        }
    }

    /**
     * 每次真的改了生成参数都要在日志里留下「谁改的 + 旧值 → 新值」。
     * 「我的参数为什么莫名其妙被改了」必须能查：网页端、指令、预设/样式载入、跟随 WebUI 页面，一个都不能静默。
     */
    private static void parameterChangesAreLogged() throws Exception {
        try (Fixture f = new Fixture()) {
            SdClient client = f.client();
            client.settings();
            String sampler = captureOutput(() -> client.setSampler("DPM++ 2M"));
            check(sampler.contains("生成参数变更") && sampler.contains("采样方法") && sampler.contains("Euler a → DPM++ 2M"),
                    "sampler change logged with old → new: " + sampler);
            check(sampler.contains("网页端") || sampler.contains("QQ 侧"), "log names the side that changed it");
            String size = captureOutput(() -> client.setSize(768, 512));
            check(size.contains("尺寸 832×1152 → 768×512"), "size change logged with both dimensions: " + size);
            String steps = captureOutput(() -> client.setParameter("steps", "33"));
            check(steps.contains("迭代步数") && steps.contains("→ 33"), "steps change logged: " + steps);
            String cfg = captureOutput(() -> client.setParameter("cfg", "7"));
            check(cfg.contains("CFG") && cfg.contains("7.5 → 7"), "cfg change logged: " + cfg);
            String model = captureOutput(() -> client.setParameter("model", "animaCatTower_v11.safetensors"));
            check(model.contains("底模") && model.contains("跟随 WebUI 当前模型"),
                    "checkpoint change logged from auto: " + model);
            String unchanged = captureOutput(() -> client.setSize(768, 512));
            check(unchanged.isBlank(), "same values leave no log line: " + unchanged);
            // 参数由机器人自己拥有：页面那份（DDIM/1024×1024）不采纳，也就不该留下"参数被改了"的日志。
            f.sampler = "DDIM"; f.width = 1024; f.height = 1024; f.revision++;
            SdClient.GenerationSettings[] held = new SdClient.GenerationSettings[1];
            String followed = captureOutput(() -> held[0] = client.settings());
            check(followed.isBlank() && held[0].width() == 768 && held[0].height() == 512
                            && "DPM++ 2M".equals(held[0].samplerName()),
                    "page-side parameter write-back is ignored silently: " + followed + " / "
                            + held[0].samplerName() + " " + held[0].width() + "×" + held[0].height());
        }
    }

    /** 只为了断言日志：把这一段的标准输出收下来（Log 在测试里只写控制台）。 */
    private static String captureOutput(ThrowingRunnable action) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try { action.run(); } finally { System.setOut(original); }
        return bytes.toString(StandardCharsets.UTF_8).strip();
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static void liveSettingsAndPartialEdits() throws Exception {
        try (Fixture f = new Fixture()) {
            SdClient client = f.client();
            SdClient.GenerationSettings initial = client.settings();
            equal("Euler a", initial.samplerName(), "read current sampler");
            equal(List.of("Cinematic"), initial.styles(), "read selected preset styles");
            equal(832, initial.width(), "read actual live width");
            equal(1152, initial.height(), "read actual live height");
            check(initial.source().contains("实时同步"), "live settings source explicit");
            equal(List.of("Euler a", "DPM++ 2M"), client.samplers(), "available sampler names");
            equal(List.of("Cinematic", "Soft, warm", "中文 风格"), client.styles(), "catalog retains spaces and commas");
            equal("DPM++ 2M", client.setSampler("K_DPMpp_2m").samplerName(), "aliases resolve case insensitively");
            equal(Set.of("sampler_name", "expected_revision"), f.lastPut.keySet(), "sampler sends only changed field");
            equal("live positive", f.positive, "sampler edit preserves positive prompt");
            equal("live negative", f.negative, "sampler edit preserves negative prompt");
            equal(832, f.width, "sampler edit preserves width");
            equal(List.of("Soft, warm", "中文 风格"),
                    client.setStyles(List.of("Soft, warm", "中文 风格")).styles(), "ordered multiselect retained");
            equal(Set.of("styles", "expected_revision"), f.lastPut.keySet(), "styles update remains partial");
            SdClient.GenerationSettings sized = client.setSize(768, 512);
            equal(768, sized.width(), "width changed");
            equal(512, sized.height(), "height changed");
            equal(Set.of("width", "height", "expected_revision"), f.lastPut.keySet(), "size updates both dimensions atomically");
            equal("DPM++ 2M", sized.samplerName(), "size preserves sampler");
            equal(List.of("Soft, warm", "中文 风格"), sized.styles(), "size preserves style order");
            client.change(false, "edit", "changed prompt");
            equal("DPM++ 2M", f.sampler, "legacy prompt edit preserves new parameters");
            equal(List.of(), client.setStyles(List.of()).styles(), "empty style list clears selection");
            f.live = false;
            // 参数不再跟随页面：来源标注只剩机器人本地那份（"页面未实时连接"只描述提示词那份）。
            check(client.settings().source().contains("未同步"), "closed browser does not relabel the bot's own settings");
            equal(0, f.configReads.get(), "initialized bridge never uses startup defaults");
        }
    }

    private static void oldBridgeDefaultsAndPersistence() throws Exception {
        try (Fixture f = new Fixture()) {
            f.oldBridge = true;
            SdClient client = f.client();
            SdClient.GenerationSettings defaults = client.settings();
            equal("DPM++ 2M", defaults.samplerName(), "startup sampler from actual txt2img component");
            equal(List.of("Soft, warm"), defaults.styles(), "startup selected styles");
            equal(640, defaults.width(), "first actual width wins over duplicate extension width");
            equal(768, defaults.height(), "first actual height wins over duplicate extension height");
            check(defaults.source().contains("启动默认值") && defaults.source().contains("未同步"), "old bridge fallback label");
            client.setSampler("Euler a");
            client.setSize(512, 1024);
            client.setStyles(List.of("中文 风格", "Cinematic"));
            equal(0, f.puts.get(), "old bridge never receives unsupported settings writes");
            JsonObject saved = Json.parse(Files.readString(f.root.resolve("data/sd-settings.json")));
            equal(1024, saved.get("height").getAsInt(), "settings persisted separately");
            equal("live positive", Json.parse(Files.readString(f.root.resolve("data/sd-state.json")))
                    .get("positive").getAsString(), "settings persistence preserves prompt file");
            f.startup = "{}";
            SdClient.GenerationSettings restarted = f.client().settings();
            equal("Euler a", restarted.samplerName(), "saved sampler survives restart");
            equal(List.of("中文 风格", "Cinematic"), restarted.styles(), "saved ordered style selection survives restart");
            equal(512, restarted.width(), "saved width survives restart");
            equal(1024, restarted.height(), "saved height survives restart");
            check(restarted.source().contains("本地持久化") && restarted.source().contains("未同步"), "unsynced saved state labeled");
            equal(1, f.configReads.get(), "saved settings take priority over startup config");
            f.bridgeStatus = 404;
            equal(512, f.client().generationRequest().settings().width(), "missing bridge still restores settings");
        }
    }

    private static void newBridgeInitializesFromSavedSettings() throws Exception {
        try (Fixture f = new Fixture()) {
            f.initialized = false;
            f.sampler = "";
            JsonObject saved = new JsonObject();
            saved.addProperty("sampler_name", "Euler a");
            saved.add("styles", Json.GSON.toJsonTree(List.of("中文 风格")));
            saved.addProperty("width", 1024);
            saved.addProperty("height", 768);
            Json.atomicWrite(f.root.resolve("data/sd-settings.json"), saved);
            SdClient client = f.client();
            check(client.generationRequest().settings().source().contains("未同步"), "uninitialized new bridge uses saved fallback");
            equal(0, f.puts.get(), "read alone never pushes settings into browser");
            client.setSize(640, 512);
            equal(Set.of("sampler_name", "styles", "width", "height", "expected_revision"), f.lastPut.keySet(),
                    "first mutation initializes full settings with expected revision");
            check(f.initialized, "new bridge initialized");
            equal("Euler a", f.sampler, "initializing preserves saved sampler");
            equal(List.of("中文 风格"), f.selectedStyles, "initializing preserves saved styles");
            equal("live positive", f.positive, "initializing settings preserves bridge prompt");
            equal(0, f.configReads.get(), "migration honors saved settings without reading defaults");
        }
    }

    private static void coherentGenerationSnapshot() throws Exception {
        try (Fixture f = new Fixture()) {
            SdClient client = f.client();
            int before = f.gets.get();
            SdClient.GenerationRequest snapshot = client.generationRequest();
            equal(1, f.gets.get() - before, "prompt and settings captured with one bridge GET");
            client.setSampler("DPM++ 2M");
            client.setStyles(List.of("Soft, warm", "中文 风格"));
            client.setSize(512, 512);
            client.change(false, "edit", "later prompt");
            before = f.gets.get();
            List<Path> images = client.generate(snapshot);
            equal(before, f.gets.get(), "generation never rereads mutable browser state");
            equal("live positive", f.lastGeneration.get("prompt").getAsString(), "generation keeps snapshotted prompt");
            equal("live negative", f.lastGeneration.get("negative_prompt").getAsString(), "generation keeps snapshotted negative");
            equal("Euler a", f.lastGeneration.get("sampler_name").getAsString(), "generation keeps snapshotted sampler");
            equal(832, f.lastGeneration.get("width").getAsInt(), "generation keeps snapshotted width");
            equal(1152, f.lastGeneration.get("height").getAsInt(), "generation keeps snapshotted height");
            equal(List.of("Cinematic"), strings(f.lastGeneration.get("styles")), "WebUI receives preset names separately");
            equal(7.5, f.lastGeneration.get("cfg_scale").getAsDouble(), "existing CFG unaffected");
            equal(9876543210L, f.lastGeneration.get("seed").getAsLong(), "existing long seed unaffected");
            JsonObject latest = Json.parse(Files.readString(f.root.resolve("data/sd-latest.json")));
            equal("Euler a", latest.get("sampler_name").getAsString(), "manifest records actual generation sampler");
            equal(List.of("Cinematic"), strings(latest.get("styles")), "manifest records actual generation styles");
            equal(832, latest.get("width").getAsInt(), "manifest records actual generation width");
            equal(images, f.client().latestImages(), "manifest image list remains restart compatible");
            List<String> mutable = new ArrayList<>(List.of("Cinematic"));
            SdClient.GenerationSettings copy = new SdClient.GenerationSettings("Euler a", mutable, 512, 512, "test");
            mutable.clear();
            equal(List.of("Cinematic"), copy.styles(), "generation settings defensively copy multiselect");
            client.generate(new SdClient.Prompts("provided prompt", "provided negative", "test"));
            equal("provided prompt", f.lastGeneration.get("prompt").getAsString(), "legacy overload honors provided prompt");
            equal("DPM++ 2M", f.lastGeneration.get("sampler_name").getAsString(), "legacy overload captures current sampler");
        }
    }

    private static void conflictsAndFailedWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            SdClient client = f.client();
            client.settings();
            for (int status : new int[] {401, 403, 500}) {
                f.bridgeStatus = status;
                expectFailure(() -> client.setSize(512, 512), "HTTP " + status, "GET failure stays failure");
                equal(832, persistedWidth(f), "failed GET does not persist attempted size");
            }
            f.bridgeStatus = 200;
            for (int status : new int[] {404, 500}) {
                f.putStatus = status;
                expectFailure(() -> client.setSize(512, 512), "HTTP " + status, "failed PUT never saves local success");
                equal(832, persistedWidth(f), "failed PUT preserves known settings");
            }
            f.putStatus = 200;
            f.conflictOnce = true;
            int before = f.puts.get();
            SdClient.GenerationSettings changed = client.setSize(640, 512);
            equal(2, f.puts.get() - before, "version conflict retries once");
            equal("Euler a", changed.samplerName(), "concurrent page sampler edit is not adopted (the bot keeps its own)");
            equal(List.of("Cinematic"), changed.styles(), "concurrent page style edit is not adopted (the bot keeps its own)");
            equal("concurrent positive", f.positive, "retry preserves concurrent prompt edit");
            f.alwaysConflict = true;
            before = f.puts.get();
            expectFailure(() -> client.setSize(768, 768), "其他窗口", "repeated conflicts bounded");
            equal(3, f.puts.get() - before, "three retries maximum");
            equal(640, persistedWidth(f), "failed conflict series preserves actual size");
        }
    }

    private static void validationBeforeMutation() throws Exception {
        try (Fixture f = new Fixture()) {
            SdClient client = f.client();
            for (int[] size : new int[][] {{0, 512}, {63, 512}, {65, 512}, {512, 2056}, {Integer.MAX_VALUE, 512}})
                expectFailure(() -> client.setSize(size[0], size[1]), "64–2048", "invalid dimensions rejected");
            equal(0, f.requests.get(), "invalid dimensions rejected before any HTTP");
            expectFailure(() -> client.generate(new SdClient.GenerationRequest(new SdClient.Prompts("", "", "test"),
                    new SdClient.GenerationSettings("Euler a", List.of(), 512, 2049, "test"))), "64–2048", "invalid request dimensions rejected");
            equal(0, f.requests.get(), "invalid generation rejected before any HTTP");
            expectFailure(() -> client.setSampler(" "), ".sampler list", "blank sampler rejected before HTTP");
            expectFailure(() -> client.setStyles(List.of("")), ".style list", "blank style rejected before HTTP");
            equal(0, f.requests.get(), "blank parameter values do not perform HTTP");
            expectFailure(() -> client.setSampler("No Such Sampler"), "未知采样方法", "unknown sampler rejected");
            expectFailure(() -> client.setStyles(List.of("cinematic")), "未知预设样式", "style names use exact catalog case");
            equal(0, f.gets.get(), "invalid catalog names never read or mutate bridge state");
            equal(0, f.puts.get(), "invalid parameters never mutate bridge");
            check(!Files.exists(f.root.resolve("data/sd-settings.json")), "invalid parameters never create persisted settings");
            f.ambiguousAlias = true;
            expectFailure(() -> client.setSampler("shared"), "歧义", "ambiguous sampler alias rejected");
            f.catalogStatus = 500;
            expectFailure(() -> client.setSampler("Euler a"), "HTTP 500", "catalog errors are explicit");
            f.catalogStatus = 200;
            f.width = 65;
            expectFailure(client::settings, "64–2048", "invalid bridge dimensions rejected");
            check(!Files.exists(f.root.resolve("data/sd-settings.json")), "malformed bridge state never persisted");
            f.width = 64;
            equal(64, client.setSize(64, 2048).width(), "lower width limit accepted");
            equal(2048, f.height, "upper height limit accepted");
        }
    }

    private static void offlineAndTimeout() throws Exception {
        try (Fixture f = new Fixture()) {
            f.oldBridge = true;
            SdClient client = f.client();
            client.settings();
            f.server.stop(0);
            equal(List.of(), client.setStyles(List.of()).styles(), "style clear works offline");
            check(client.setSize(768, 512).source().contains("未同步"), "offline size update labeled local");
            equal(768, f.client().settings().width(), "offline settings survive restart");
            expectFailure(() -> client.setSampler("Euler a"), "无法连接", "sampler change requires available catalog");
        }
        try (Fixture f = new Fixture()) {
            f.configuration.addProperty("timeout_seconds", 1);
            f.delayMillis = 1800;
            expectFailure(() -> f.client().settings(), "超时", "settings timeout cannot silently fall back");
            check(!Files.exists(f.root.resolve("data/sd-settings.json")), "timeout does not create local settings");
        }
        try (Fixture f = new Fixture()) {
            f.oldBridge = true;
            f.configStatus = 500;
            expectFailure(() -> f.client().settings(), "HTTP 500", "startup config error not silently ignored");
            check(!Files.exists(f.root.resolve("data/sd-settings.json")), "config error does not persist fake defaults");
        }
    }

    private static int persistedWidth(Fixture fixture) throws IOException {
        return Json.parse(Files.readString(fixture.root.resolve("data/sd-settings.json"))).get("width").getAsInt();
    }

    private static List<String> strings(JsonElement value) {
        List<String> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) result.add(item.getAsString());
        return result;
    }

    private interface Operation { Object run() throws Exception; }
    private static void expectFailure(Operation operation, String fragment, String message) throws Exception {
        try { operation.run(); throw new AssertionError(message + ": expected exception"); }
        catch (IOException expected) { check(expected.getMessage().contains(fragment), message + ": " + expected.getMessage()); }
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
        final ExecutorService executor;
        final JsonObject configuration = new JsonObject();
        final AtomicInteger requests = new AtomicInteger(), gets = new AtomicInteger(), puts = new AtomicInteger(), configReads = new AtomicInteger();
        volatile int bridgeStatus = 200, putStatus = 200, catalogStatus = 200, configStatus = 200, revision = 1;
        volatile int width = 832, height = 1152;
        volatile String positive = "live positive", negative = "live negative", sampler = "Euler a";
        volatile List<String> selectedStyles = List.of("Cinematic");
        volatile boolean oldBridge, initialized = true, live = true, conflictOnce, alwaysConflict, ambiguousAlias;
        /** 只有 ⑧（底模/VAE 只读观测）那一条打开：其余用例里 /sdapi/v1/options 照旧 404。 */
        volatile boolean optionsAvailable;
        volatile long delayMillis;
        volatile JsonObject lastPut, lastGeneration;
        volatile String startup = """
                {"components":[
                  {"props":{"elem_id":"txt2img_prompt","value":"startup positive"}},
                  {"props":{"elem_id":"txt2img_neg_prompt","value":"startup negative"}},
                  {"props":{"elem_id":"txt2img_sampling","value":"DPM++ 2M"}},
                  {"props":{"elem_id":"txt2img_styles","value":["Soft, warm"]}},
                  {"props":{"elem_id":"txt2img_width","value":640,"minimum":64}},
                  {"props":{"elem_id":"txt2img_height","value":768,"minimum":64}},
                  {"props":{"elem_id":"txt2img_width","value":0,"minimum":0}},
                  {"props":{"elem_id":"txt2img_height","value":0,"minimum":0}}
                ]}
                """;

        Fixture() throws IOException {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "sd-settings-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "sd-settings-mock"); thread.setDaemon(true); return thread;
            });
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            configuration.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            configuration.addProperty("timeout_seconds", 5);
            configuration.addProperty("cfg_scale", 7.5);
            configuration.addProperty("seed", 9876543210L);
        }

        SdClient client() throws IOException { return new SdClient(root, configuration); }

        void handle(HttpExchange exchange) throws IOException {
            try {
                requests.incrementAndGet();
                if (!"1".equals(exchange.getRequestHeaders().getFirst("X-Pixiko-Bridge"))) {
                    send(exchange, 403, "{}"); return;
                }
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/pixiko-bridge/v1/prompts")) {
                    if (exchange.getRequestMethod().equals("GET")) gets.incrementAndGet();
                    if (delayMillis > 0) Thread.sleep(delayMillis);
                    if (bridgeStatus != 200) { send(exchange, bridgeStatus, "{}"); return; }
                    if (exchange.getRequestMethod().equals("PUT")) {
                        puts.incrementAndGet();
                        lastPut = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        if (putStatus != 200) { send(exchange, putStatus, "{}"); return; }
                        if (conflictOnce || alwaysConflict) {
                            conflictOnce = false; positive = "concurrent positive"; sampler = "DPM++ 2M";
                            selectedStyles = List.of("中文 风格"); revision++;
                            send(exchange, 409, Json.GSON.toJson(state())); return;
                        }
                        if (lastPut.get("expected_revision").getAsInt() != revision) { send(exchange, 409, "{}"); return; }
                        if (!initialized && lastPut.has("width") && !lastPut.has("sampler_name")) { send(exchange, 422, "{}"); return; }
                        if (lastPut.has("positive")) positive = lastPut.get("positive").getAsString();
                        if (lastPut.has("negative")) negative = lastPut.get("negative").getAsString();
                        if (lastPut.has("sampler_name")) sampler = lastPut.get("sampler_name").getAsString();
                        if (lastPut.has("styles")) selectedStyles = strings(lastPut.get("styles"));
                        if (lastPut.has("width")) width = lastPut.get("width").getAsInt();
                        if (lastPut.has("height")) height = lastPut.get("height").getAsInt();
                        if (lastPut.has("sampler_name") && lastPut.has("styles") && lastPut.has("width") && lastPut.has("height"))
                            initialized = true;
                        revision++;
                    }
                    send(exchange, 200, Json.GSON.toJson(state()));
                } else if (path.equals("/config")) {
                    configReads.incrementAndGet(); send(exchange, configStatus, startup);
                } else if (path.equals("/sdapi/v1/samplers")) {
                    send(exchange, catalogStatus, ambiguousAlias
                            ? "[{\"name\":\"Euler a\",\"aliases\":[\"shared\"]},{\"name\":\"DPM++ 2M\",\"aliases\":[\"shared\"]}]"
                            : "[{\"name\":\"Euler a\",\"aliases\":[\"k_euler_a\"]},{\"name\":\"DPM++ 2M\",\"aliases\":[\"k_dpmpp_2m\"]}]");
                } else if (path.equals("/sdapi/v1/prompt-styles")) {
                    send(exchange, catalogStatus, "[{\"name\":\"Cinematic\"},{\"name\":\"Soft, warm\"},{\"name\":\"中文 风格\"}]");
                } else if (path.equals("/sdapi/v1/sd-models")) {
                    send(exchange, catalogStatus, "[{\"title\":\"animaCatTower_v11.safetensors [aaaa]\",\"model_name\":\"animaCatTower_v11\"}]");
                } else if (path.equals("/sdapi/v1/txt2img")) {
                    lastGeneration = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", bytes);
                    send(exchange, 200, "{\"images\":[\"" + Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\"]}");
                } else if (path.equals("/sdapi/v1/options") && optionsAvailable) {
                    // 只读观测：机器人从这里读"当前加载的底模 / VAE"（VAE 冲突防呆与界面显示要用），
                    // 但生成参数不从这里采纳（尺寸/采样方法/样式由机器人自己拥有）。
                    send(exchange, 200, "{\"sd_model_checkpoint\":\"animaCatTower_v11.safetensors [aaaa]\","
                            + "\"sd_vae\":\"Automatic\"}");
                } else send(exchange, 404, "{}");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }

        JsonObject state() {
            JsonObject state = new JsonObject();
            state.addProperty("positive", positive); state.addProperty("negative", negative);
            state.addProperty("revision", revision); state.addProperty("source", live ? "webui-live" : "webui-state");
            if (!oldBridge) {
                state.addProperty("sampler_name", sampler); state.add("styles", Json.GSON.toJsonTree(selectedStyles));
                state.addProperty("width", width); state.addProperty("height", height);
                state.addProperty("settings_initialized", initialized);
            }
            return state;
        }

        static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }

        public void close() throws IOException {
            server.stop(0); executor.shutdownNow();
            // This exact test-owned directory was generated above; never follow links outside it.
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

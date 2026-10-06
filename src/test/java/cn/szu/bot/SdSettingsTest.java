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
        staleSnapshotNeverOverridesExplicitLocalSize();
        offlineAndTimeout();
        System.out.println("SdSettingsTest: " + assertions + " assertions passed.");
    }

    /**
     * 「改了尺寸一生成又变回原尺寸」（2026-10-07 实测）：页面没实时连接时，桥接里那份旧快照会被
     * 那个页面反复 PUT 回桥接（实测 0.6 秒后回来），机器人下一次 refresh 就把用户刚改的尺寸顶回去。
     *
     * <p>判据是**来源优先级**：实时页面（浏览器心跳认证过的快照）＞ 机器人这边的用户显式写入
     * ＞ 页面未实时连接的桥接旧快照。载入样式/预设套用的尺寸同样受这条保护（它也走 setSize）。
     */
    private static void staleSnapshotNeverOverridesExplicitLocalSize() throws Exception {
        try (Fixture f = new Fixture()) {
            SdClient client = f.client();
            client.settings();
            // 页面已断开，用户仍在机器人这边显式改尺寸（控制台/指令/手机端都走这条）。
            f.live = false;
            equal(960, client.setSize(960, 1440).width(), "explicit local size applied");
            // 没通过心跳认证的旧页面把它自己那份旧尺寸 PUT 回桥接。
            f.width = 832; f.height = 1152; f.revision++;
            String kept = captureOutput(() -> {
                SdClient.GenerationSettings after = client.settings();
                equal(960, after.width(), "stale snapshot cannot override the explicit local width");
                equal(1440, after.height(), "stale snapshot cannot override the explicit local height");
                check(after.source().contains("本地显式参数"), "kept-local source is explicit: " + after.source());
                equal(960, client.generationRequest().settings().width(), "generation submits the user's width");
            });
            check(kept.contains("保留本地显式值") && kept.contains("尺寸 960×1440 → 832×1152"),
                    "ignored snapshot logged with old → new: " + kept);
            equal(960, persistedWidth(f), "the user's size is what gets persisted for a restart");
            equal(960, f.client().settings().width(), "restart keeps the explicit size against the stale snapshot");
            // 载入样式套用尺寸（既有行为）也要活得过下一次未实时快照。
            JsonObject styleModel = new JsonObject();
            styleModel.addProperty("width", 768);
            styleModel.addProperty("height", 512);
            List<String> applied = client.applyModelParams(styleModel);
            check(applied.stream().anyMatch(text -> text.contains("尺寸 768×512")), "style size applied: " + applied);
            f.width = 832; f.height = 1152; f.revision++;
            equal(768, client.settings().width(), "style-applied size survives a stale snapshot");
            // 合法的变换照旧：展示图那种超限尺寸仍按 fitGenerationSize 同比例缩到合法值再套用。
            JsonObject huge = new JsonObject();
            huge.addProperty("width", 2400);
            huge.addProperty("height", 3744);
            List<String> fitted = client.applyModelParams(huge);
            check(fitted.stream().anyMatch(text -> text.contains("尺寸 2400×3744 → 同比例缩到")),
                    "out-of-range style size still fitted: " + fitted);
            f.width = 832; f.height = 1152; f.revision++;
            equal(SdClient.fitGenerationSize(2400, 3744)[0], client.settings().width(),
                    "fitted style size survives a stale snapshot");
            // 页面真在实时同步（心跳认证）时页面优先：既有行为一个字都不改。
            f.live = true; f.width = 1216; f.height = 832; f.revision++;
            SdClient.GenerationSettings followed = client.settings();
            equal(1216, followed.width(), "a live page still wins");
            check(followed.source().contains("实时同步"), "live page labelled: " + followed.source());
            // 页面接管过之后本地那条"显式写入"记录作废：下一次未实时快照照旧跟随，不会永久粘住。
            f.live = false; f.width = 512; f.height = 512; f.revision++;
            equal(512, client.settings().width(), "after a live takeover the snapshot is followed again");
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
            // WebUI 页面把参数顶掉时同样留痕（这是"参数莫名其妙变了"最常见的一路）。
            f.sampler = "DDIM"; f.width = 1024; f.height = 1024; f.revision++;
            String followed = captureOutput(() -> client.settings());
            check(followed.contains("跟随 WebUI 页面") && followed.contains("DDIM") && followed.contains("1024×1024"),
                    "page override logged: " + followed);
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
            check(client.settings().source().contains("页面未实时连接"), "closed browser state labeled honestly");
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
            equal("DPM++ 2M", changed.samplerName(), "retry preserves concurrent sampler edit");
            equal(List.of("中文 风格"), changed.styles(), "retry preserves concurrent style edit");
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

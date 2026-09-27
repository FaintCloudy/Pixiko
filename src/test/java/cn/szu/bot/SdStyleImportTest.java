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
import cn.szu.bot.sd.SdClient;

/** Isolated HTTP and filesystem checks for original style text, imports, and whole-batch delivery. */
public final class SdStyleImportTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        originalStyleTextAndDividers();
        appendImportsBothFieldsWithoutRepeatingCurrentPrompt();
        emptyStyleTextAndInternalReplacementMode();
        concurrentImportMergesFreshState();
        strictBridgeAndMalformedResponses();
        generatedBatchesAccumulateUntilAcknowledged();
        invalidBatchNeverEnqueuesPartOfItsImages();
        queueCommitFailurePreservesPriorLatestAndQueue();
        latestManifestFailureCannotDeleteCommittedImages();
        System.out.println("SdStyleImportTest: " + assertions + " assertions passed.");
    }

    private static void originalStyleTextAndDividers() throws Exception {
        try (Fixture f = new Fixture()) {
            f.addStyle(" [青绿 风格] ", "  {prompt}, 青绿,\n细节  ", "{prompt}, blur");
            f.addStyle("empty", "", "");
            f.addStyle("---- CSV ----", null, null);
            SdClient client = f.client();
            List<SdClient.StylePrompt> styles = client.stylePrompts();
            equal(2, styles.size(), "unrelated native divider skipped");
            equal("  {prompt}, 青绿,\n细节  ", styles.get(0).positive(), "original positive kept exactly");
            equal("{prompt}, blur", styles.get(0).negative(), "original negative placeholder preserved for display");
            equal(" [青绿 风格] ", client.stylePrompt(" [青绿 风格] ").name(), "exact name including brackets and spaces");
            equal("", client.stylePrompt("empty").positive(), "empty template retained");
            expect(() -> client.stylePrompt("---- CSV ----"), "分隔标题", "divider cannot be imported");
            expect(() -> client.stylePrompt("missing"), "未知", "missing name explicit");
            expect(() -> client.stylePrompt("EMPTY"), "区分大小写", "exact catalog names are case-sensitive");
            equal(0, f.bridgeGets, "read-only template lookup never reads or writes current prompts");
            equal(0, f.puts, "template lookup does not write");
        }
    }

    private static void appendImportsBothFieldsWithoutRepeatingCurrentPrompt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.addStyle("[青绿 风格]", " , {prompt}, 青绿, 高清, ", "{prompt}, blur, ");
            f.selected = List.of("Keep first", "[青绿 风格]", "Keep last");
            SdClient client = f.client();
            SdClient.Prompts result = client.importStyle("[青绿 风格]", true);
            equal("current positive, 青绿, 高清", result.positive(), "style added to end, placeholder and edge commas removed");
            equal("current negative, blur", result.negative(), "negative style added to end");
            equal(List.of("Keep first", "Keep last"), f.selected, "only imported style deselected");
            equal(Set.of("positive", "negative", "styles", "expected_revision"), f.lastPut.keySet(), "all three changes share one CAS write");
            equal(1, f.puts, "one atomic import");
            check(!result.positive().contains("{prompt}"), "placeholder not turned into literal prompt text");
            equal(1, occurrences(result.positive(), "current positive"), "current prompt never duplicated inside template");
            SdClient.GenerationSettings settings = client.settings();
            equal("DDIM", settings.samplerName(), "sampler preserved");
            equal(896, settings.width(), "width preserved");
            equal(1152, settings.height(), "height preserved");
            equal(List.of("Keep first", "Keep last"), settings.styles(), "other style selections persist");
            JsonObject prompts = Json.parse(Files.readString(f.root.resolve("data/sd-state.json")));
            equal(result.positive(), prompts.get("positive").getAsString(), "positive persisted from confirmed response");
            JsonObject parameters = Json.parse(Files.readString(f.root.resolve("data/sd-settings.json")));
            equal(2, parameters.getAsJsonArray("styles").size(), "confirmed selected styles persisted");
        }
    }

    private static void emptyStyleTextAndInternalReplacementMode() throws Exception {
        try (Fixture f = new Fixture()) {
            f.addStyle("empty", "{prompt},  ", "");
            f.positive = "keep literal {prompt}"; f.negative = "keep negative";
            f.selected = List.of("empty", "other");
            SdClient client = f.client();
            SdClient.Prompts result = client.importStyle("empty", true);
            equal("keep literal {prompt}", result.positive(), "only template placeholder removed, existing prompt untouched");
            equal("keep negative", result.negative(), "empty addition does not add punctuation");
            equal(List.of("other"), f.selected, "empty imported template still removed from selection");
            f.addStyle("only positive", "{prompt}, new template", "");
            f.positive = " "; f.negative = "";
            result = client.importStyle("only positive", true);
            equal("new template", result.positive(), "blank current prompt accepts plain template without leading comma");
            equal("", result.negative(), "empty negative remains empty");
            f.positive = "old value"; f.negative = "old negative";
            result = client.importStyle("only positive", false);
            equal("new template", result.positive(), "internal replacement mode still removes placeholder");
            equal("", result.negative(), "replacement mode replaces both fields");
        }
    }

    private static void concurrentImportMergesFreshState() throws Exception {
        try (Fixture f = new Fixture()) {
            f.addStyle("watercolor", "{prompt}, painted", "{prompt}, blur");
            f.conflicts = 1;
            SdClient.Prompts result = f.client().importStyle("watercolor", true);
            equal("human positive 1, painted", result.positive(), "CAS retry appends to latest positive");
            equal("human negative 1, blur", result.negative(), "CAS retry appends to latest negative");
            equal(List.of("human style", "Keep last"), f.selected, "concurrent other style selections retained");
            equal(1024, f.width, "concurrent width retained");
            equal(2, f.bridgeGets, "retry reads fresh bridge state");
            equal(2, f.puts, "one conflict retried once");
        }
        try (Fixture f = new Fixture()) {
            f.addStyle("watercolor", "painted", "blur"); f.conflicts = 9;
            expect(() -> f.client().importStyle("watercolor", true), "其他窗口", "persistent CAS conflict is bounded");
            equal(3, f.puts, "only three CAS attempts");
            equal(3, f.bridgeGets, "each CAS attempt reads fresh state");
            check(!Files.exists(f.root.resolve("data/sd-state.json")), "failed CAS cannot persist claimed import");
        }
    }

    private static void strictBridgeAndMalformedResponses() throws Exception {
        try (Fixture f = new Fixture()) {
            f.addStyle("watercolor", "painted", "blur");
            SdClient client = f.client();
            for (int status : new int[] {404, 401, 403, 500}) {
                f.bridgeStatus = status;
                expect(() -> client.importStyle("watercolor", true), status == 404 ? "在线" : "HTTP " + status, "bridge errors never fall back");
            }
            f.bridgeStatus = 200; f.oldBridge = true;
            expect(() -> client.importStyle("watercolor", true), "新版本", "prompt-only bridge cannot atomically remove selected style");
            f.oldBridge = false; f.initialized = false;
            expect(() -> client.importStyle("watercolor", true), "文生图页面", "uninitialized settings cannot be guessed");
            equal(0, f.puts, "unsupported bridge never receives a partial import");
            equal(0, f.configReads, "strict import never reads startup defaults");
            f.initialized = true; f.ignoreStyles = true;
            expect(() -> client.importStyle("watercolor", true), "未确认", "response must confirm imported style deselection");
            check(!Files.exists(f.root.resolve("data/sd-state.json")), "unconfirmed result not persisted");
        }
        try (Fixture f = new Fixture()) {
            JsonObject incomplete = new JsonObject(); incomplete.addProperty("name", "broken"); f.styles.add(incomplete);
            expect(() -> f.client().stylePrompts(), "完整", "missing text fields not silently treated as empty templates");
            incomplete.addProperty("prompt", 12); incomplete.addProperty("negative_prompt", "");
            expect(() -> f.client().stylePrompts(), "prompt", "non-string template rejected");
        }
    }

    private static void generatedBatchesAccumulateUntilAcknowledged() throws Exception {
        try (Fixture f = new Fixture()) {
            SdClient client = f.client();
            f.images = List.of(image("png"), image("jpg"));
            List<Path> first = client.generate(generation());
            f.images = List.of(image("png"));
            List<Path> second = client.generate(generation());
            List<Path> all = new ArrayList<>(first); all.addAll(second);
            equal(all, client.pendingImages(), "complete generation batches accumulate in order");
            equal(second, client.latestImages(), "latestImages retains original most-recent-batch meaning");
            equal(all, f.client().pendingImages(), "pending batches survive restart");
            client.acknowledgeImage(first.get(0));
            equal(all.subList(1, all.size()), client.pendingImages(), "only successful individual image is consumed");
            check(Files.isRegularFile(first.get(0)), "acknowledgement never deletes generated image");
            client.acknowledgeImage(first.get(0));
            equal(all.subList(1, all.size()), client.pendingImages(), "duplicate acknowledgement is harmless");
            for (Path path : new ArrayList<>(client.pendingImages())) client.acknowledgeImage(path);
            equal(List.of(), f.client().pendingImages(), "consumed images do not remigrate from latest on restart");
            equal(second, f.client().latestImages(), "latest compatibility lookup still allows explicit old-style access");
        }
    }

    private static void invalidBatchNeverEnqueuesPartOfItsImages() throws Exception {
        try (Fixture f = new Fixture()) {
            SdClient client = f.client(); f.images = List.of(image("png"));
            List<Path> first = client.generate(generation());
            f.images = List.of(image("png"), Base64.getEncoder().encodeToString("not an image".getBytes(StandardCharsets.UTF_8)));
            expect(() -> client.generate(generation()), "PNG/JPEG", "invalid second image rejects entire generation batch");
            equal(first, client.pendingImages(), "no first image of failed batch added");
            equal(first, client.latestImages(), "failed batch does not replace latest");
            try (var paths = Files.list(f.root.resolve("data/generated"))) { equal(1L, paths.count(), "failed decoded batch creates no partial directory"); }
        }
    }

    private static void queueCommitFailurePreservesPriorLatestAndQueue() throws Exception {
        try (Fixture f = new Fixture()) {
            SdClient client = f.client(); f.images = List.of(image("png"));
            List<Path> first = client.generate(generation());
            Path queue = f.root.resolve("data/sd-outbox.json");
            Files.move(queue, f.root.resolve("data/outbox-before.json"));
            Files.createDirectory(queue); Files.writeString(queue.resolve("block"), "force commit failure");
            expect(() -> client.generate(generation()), null, "outbox atomic commit failure is a failed generation");
            equal(first, client.pendingImages(), "failed commit keeps previous in-memory pending queue");
            equal(first, client.latestImages(), "failed outbox commit never updates latest");
            try (var paths = Files.list(f.root.resolve("data/generated"))) { equal(1L, paths.count(), "uncommitted generation files removed"); }
        }
    }

    private static void latestManifestFailureCannotDeleteCommittedImages() throws Exception {
        try (Fixture f = new Fixture()) {
            SdClient client = f.client(); client.pendingImages();
            Path latest = f.root.resolve("data/sd-latest.json");
            Files.createDirectory(latest); Files.writeString(latest.resolve("block"), "force compatibility manifest failure");
            f.images = List.of(image("png"), image("jpg"));
            ByteArrayOutputStream warnings = new ByteArrayOutputStream();
            PrintStream original = System.err;
            List<Path> generated;
            try (PrintStream capture = new PrintStream(warnings, true, StandardCharsets.UTF_8)) {
                System.setErr(capture);
                try { generated = client.generate(generation()); }
                finally { System.setErr(original); }
            }
            equal(2, generated.size(), "committed generation succeeds despite compatibility manifest failure");
            equal(generated, client.pendingImages(), "all images remain in durable pending queue");
            equal(generated, f.client().pendingImages(), "restart recovers queue even if compatibility latest cannot be written");
            for (Path path : generated) check(Files.isRegularFile(path) && ImageIO.read(path.toFile()) != null, "no committed image deleted by catch cleanup");
            check(warnings.toString(StandardCharsets.UTF_8).contains("待发送队列"), "actual compatibility failure explicitly logged");
        }
    }

    private static SdClient.GenerationRequest generation() {
        return new SdClient.GenerationRequest(new SdClient.Prompts("generation +", "generation -", "fixture"),
                new SdClient.GenerationSettings("DDIM", List.of(), 512, 512, "fixture"));
    }
    private static String image(String format) throws IOException {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB); image.setRGB(1, 1, 0x8899aa);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, format, output);
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }
    private static int occurrences(String value, String fragment) { return value.split(java.util.regex.Pattern.quote(fragment), -1).length - 1; }
    private interface Operation { Object run() throws Exception; }
    private static void expect(Operation operation, String fragment, String label) throws Exception {
        try { operation.run(); throw new AssertionError(label + ": expected failure"); }
        catch (IOException error) { check(fragment == null || error.getMessage().contains(fragment), label + ": " + error.getMessage()); }
    }
    private static void equal(Object expected, Object actual, String label) { check(Objects.equals(expected, actual), label + ": expected=" + expected + ", actual=" + actual); }
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final JsonObject config = new JsonObject();
        final JsonArray styles = new JsonArray();
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool(runnable -> { Thread thread = new Thread(runnable, "style-import-fixture"); thread.setDaemon(true); return thread; });
        volatile String positive = "current positive", negative = "current negative";
        volatile List<String> selected = List.of("watercolor", "Keep last"), images = List.of();
        volatile int revision = 1, width = 896, conflicts, bridgeGets, puts, configReads, bridgeStatus = 200;
        volatile boolean oldBridge, initialized = true, ignoreStyles;
        volatile JsonObject lastPut;

        Fixture() throws IOException {
            Path work = Path.of(System.getProperty("bot.test.work", "work")).resolve("sd-style-import-tests").toAbsolutePath();
            Files.createDirectories(work); root = Files.createTempDirectory(work, "case-");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor); server.createContext("/", this::handle); server.start();
            config.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort()); config.addProperty("timeout_seconds", 3);
        }
        SdClient client() throws IOException { return new SdClient(root, config); }
        void addStyle(String name, String positive, String negative) {
            JsonObject value = new JsonObject(); value.addProperty("name", name); value.addProperty("prompt", positive);
            value.addProperty("negative_prompt", negative); styles.add(value);
        }
        JsonObject state() {
            JsonObject value = new JsonObject(); value.addProperty("positive", positive); value.addProperty("negative", negative);
            value.addProperty("revision", revision); value.addProperty("source", "webui-live"); value.addProperty("live", true);
            value.addProperty("initialized", true);
            if (!oldBridge) {
                value.addProperty("settings_initialized", initialized); value.addProperty("sampler_name", "DDIM");
                value.addProperty("width", width); value.addProperty("height", 1152); value.add("styles", Json.GSON.toJsonTree(selected));
            }
            return value;
        }
        void handle(HttpExchange exchange) throws IOException {
            try {
                String route = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
                if (!"1".equals(exchange.getRequestHeaders().getFirst("X-Pixiko-Bridge"))) { send(exchange, 403, "{}"); return; }
                if (route.equals("/sdapi/v1/prompt-styles")) send(exchange, 200, styles.toString());
                else if (route.equals("/pixiko-bridge/v1/prompts")) {
                    if (bridgeStatus != 200) { send(exchange, bridgeStatus, "{}"); return; }
                    if (method.equals("GET")) bridgeGets++;
                    else if (method.equals("PUT")) {
                        puts++; lastPut = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        if (conflicts > 0) {
                            conflicts--; positive = "human positive " + puts; negative = "human negative " + puts;
                            selected = List.of("human style", "watercolor", "Keep last"); width = 1024; revision++;
                            send(exchange, 409, Json.GSON.toJson(state())); return;
                        }
                        if (lastPut.get("expected_revision").getAsInt() != revision) { send(exchange, 409, Json.GSON.toJson(state())); return; }
                        positive = lastPut.get("positive").getAsString(); negative = lastPut.get("negative").getAsString();
                        if (!ignoreStyles) { List<String> next = new ArrayList<>(); lastPut.getAsJsonArray("styles").forEach(style -> next.add(style.getAsString())); selected = next; }
                        revision++;
                    }
                    send(exchange, 200, Json.GSON.toJson(state()));
                } else if (route.equals("/sdapi/v1/txt2img")) {
                    JsonObject value = new JsonObject(); value.add("images", Json.GSON.toJsonTree(images)); send(exchange, 200, Json.GSON.toJson(value));
                } else if (route.equals("/config")) { configReads++; send(exchange, 200, "{}"); }
                else send(exchange, 404, "{}");
            } finally { exchange.close(); }
        }
        static void send(HttpExchange exchange, int code, String body) throws IOException {
            byte[] content = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, content.length); exchange.getResponseBody().write(content);
        }
        public void close() throws IOException {
            server.stop(0); executor.shutdownNow();
            // This generated fixture directory is owned by one case; symbolic links are not followed.
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}

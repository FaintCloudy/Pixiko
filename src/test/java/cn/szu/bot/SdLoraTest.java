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

/** All catalogs, downloaded files and bridge writes are isolated loopback fixtures. */
public final class SdLoraTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        listIsReadOnlyAndLoadUsesCanonicalName();
        filenameAliasAndIdempotentTags();
        downloadsMatchExactPaths();
        civitaiUsesCompleteAliasAndReplacesPreviousFilenameTag();
        ambiguousCatalogNeverChoosesAnotherFile();
        refreshAndCatalogFailuresStopBeforeWriting();
        weightsAndTagInjectionAreRejected();
        bridgeFailureNeverFallsBackToLocal();
        compareAndSwapMergesLatestPromptsAndParameters();
        invalidAcknowledgementIsNotSuccess();
        System.out.println("SdLoraTest: " + assertions + " assertions passed.");
    }

    private static void listIsReadOnlyAndLoadUsesCanonicalName() throws Exception {
        try (Fixture f = new Fixture()) {
            f.add("校园 风格", "Metadata Alias", "first");
            SdClient client = f.client();
            List<SdClient.Lora> listed = client.loras();
            equal(1, listed.size(), "one official catalog entry");
            equal("校园 风格", listed.get(0).name(), "canonical Unicode name");
            equal("Metadata Alias", listed.get(0).alias(), "metadata alias retained");
            equal(0, f.refreshes, "listing never refreshes");
            equal(0, f.bridgeGets, "listing never touches bridge");
            SdClient.LoadedLora loaded = client.loadLora("校园 风格", 0.75);
            equal("校园 风格", loaded.name(), "loaded canonical name");
            equal("<lora:Metadata Alias:0.75>", loaded.tag(), "locale-independent tag");
            equal("base prompt, <lora:Metadata Alias:0.75>", loaded.prompts().positive(), "append to existing positive");
            equal("preserve negative", loaded.prompts().negative(), "negative preserved");
            equal(1, f.refreshes, "load refreshes exactly once");
            check(f.calls.indexOf("POST /sdapi/v1/refresh-loras") < f.calls.lastIndexOf("GET /sdapi/v1/loras"), "refresh occurs before selection");
            equal(Set.of("positive", "expected_revision"), f.lastPut.keySet(), "only positive and CAS revision sent");
            equal("DDIM", client.settings().samplerName(), "sampler remains");
            equal(List.of("Watercolor"), client.settings().styles(), "styles remain");
            equal(896, client.settings().width(), "width remains");
            equal(1152, client.settings().height(), "height remains");
            equal(0, f.generations, "load does not generate or use GPU");
        }
    }

    private static void filenameAliasAndIdempotentTags() throws Exception {
        try (Fixture f = new Fixture()) {
            f.add("校园 风格", "Friendly Alias", "models");
            f.add("other", "Other Alias", "models");
            f.positive = "prefix <lora:Friendly Alias:0.3>, middle <lora:校园 风格:1.2>, <lora:other:0.6>, suffix";
            SdClient client = f.client();
            SdClient.LoadedLora loaded = client.loadLora("校园 风格.safetensors", 1);
            equal("<lora:Friendly Alias:1>", loaded.tag(), "filename with extension accepted");
            equal(1, occurrences(loaded.prompts().positive(), "<lora:Friendly Alias:"), "duplicate canonical and alias tags coalesced");
            check(loaded.prompts().positive().contains("<lora:other:0.6>"), "other LoRA unchanged");
            check(loaded.prompts().positive().contains("prefix ") && loaded.prompts().positive().endsWith(", suffix"), "other prompt text preserved");
            String first = loaded.prompts().positive();
            equal(first, client.loadLora("Friendly Alias", 1).prompts().positive(), "same load is idempotent");
            equal(1, occurrences(client.loadLora("校园 风格", 0).prompts().positive(), "<lora:Friendly Alias:0>"), "existing weight updated to zero");
            check(client.loadLora("校园 风格", 2).prompts().positive().contains("<lora:Friendly Alias:2>"), "upper boundary weight supported");
            equal("preserve negative", f.negative, "negative survives all replacements");
        }
    }

    private static void downloadsMatchExactPaths() throws Exception {
        try (Fixture f = new Fixture()) {
            Path actual = f.add("Downloaded Model", "Unsafe:metadata<alias>", "download");
            f.add("unrelated", "Different", "download");
            Files.createDirectories(actual.getParent().resolve("child"));
            Path normalizedInput = actual.getParent().resolve("child").resolve("..").resolve(actual.getFileName());
            if (File.separatorChar == '\\') f.catalog.get(0).getAsJsonObject().addProperty("path", actual.toString().toUpperCase(Locale.ROOT));
            SdClient.LoadedLora loaded = f.client().loadLora(normalizedInput, 0.8);
            equal("Downloaded Model", loaded.name(), "normalized exact path selected");
            equal("<lora:Downloaded Model:0.8>", loaded.tag(), "unsafe metadata alias never enters tag");
            check(Files.isRegularFile(actual), "download kept after successful enable");
        }
        try (Fixture f = new Fixture()) {
            Path registered = f.add("same", "same", "registered");
            Path downloaded = f.root.resolve("not-registered/same.safetensors");
            Files.createDirectories(downloaded.getParent()); Files.writeString(downloaded, "downloaded fixture");
            expect(() -> f.client().loadLora(downloaded, 1), "尚未识别", "never select another file with same stem");
            equal(0, f.puts, "mismatched download cannot write prompt");
            check(Files.exists(downloaded) && Files.exists(registered), "failure retains both files");
            expect(() -> f.client().loadLora(Path.of("relative.safetensors"), 1), "绝对", "download path must be absolute");
        }
    }

    private static void civitaiUsesCompleteAliasAndReplacesPreviousFilenameTag() throws Exception {
        try (Fixture f = new Fixture()) {
            String name = "civitai_1461055_1652243_1552887";
            Path file = f.add(name, "Natsume_Ai_1_nai", "download");
            f.positive = "Natsume_Ai, <lora:" + name + ":0.8>";
            SdClient client = f.client();
            var loaded = client.loadLora(file, 1);
            equal("<lora:Natsume_Ai_1_nai:1>", loaded.tag(), "complete alias retains underscores and numeric suffix");
            equal("Natsume_Ai, <lora:Natsume_Ai_1_nai:1>", loaded.prompts().positive(), "ordinary trigger text preserved separately from full tag");
            equal(loaded.prompts().positive(), client.loadLora("Natsume_Ai_1_nai", 1).prompts().positive(), "alias reload does not duplicate tag");
            equal("<lora:Natsume_Ai_1_nai:0.6>", client.loadLora(name, 0.6).tag(), "filename selection also uses full alias");
        }
        for (String alias : List.of("none", "Addams", " bad ", "bad:alias")) {
            try (Fixture f = new Fixture()) {
                Path file = f.add("safe_filename", alias, "download");
                equal("<lora:safe_filename:1>", f.client().loadLora(file, 1).tag(), "unusable alias falls back to filename");
            }
        }
        try (Fixture f = new Fixture()) {
            Path file = f.add("first", "shared", "download");
            f.add("second", "shared", "download");
            equal("<lora:first:1>", f.client().loadLora(file, 1).tag(), "duplicate alias falls back to filename");
        }
        try (Fixture f = new Fixture()) {
            Path file = f.add("first", "second", "download");
            f.add("second", "other", "download");
            equal("<lora:first:1>", f.client().loadLora(file, 1).tag(), "alias collision with another filename falls back");
        }
    }

    private static void ambiguousCatalogNeverChoosesAnotherFile() throws Exception {
        try (Fixture f = new Fixture()) {
            Path first = f.add("same", "Alias", "one");
            f.add("same", "Other", "two");
            expect(() -> f.client().loadLora("same", 1), "多个文件", "duplicate canonical name rejected");
            expect(() -> f.client().loadLora(first, 1), "重名", "path selection still requires an unambiguous tag");
            equal(0, f.puts, "ambiguous catalog never writes");
        }
        try (Fixture f = new Fixture()) {
            f.add("one", "Alias", "one"); f.add("two", "Alias", "two");
            expect(() -> f.client().loadLora("Alias", 1), "多个文件", "duplicate alias rejected");
            f.positive = "keep <lora:Alias:0.4>";
            check(f.client().loadLora("one", 1).prompts().positive().contains("<lora:Alias:0.4>"), "ambiguous old alias tag is not replaced");
        }
    }

    private static void refreshAndCatalogFailuresStopBeforeWriting() throws Exception {
        try (Fixture f = new Fixture()) {
            f.add("valid", "Alias", "models");
            for (int status : new int[] {401, 403, 404, 500}) {
                f.refreshStatus = status;
                expect(() -> f.client().loadLora("valid", 1), "HTTP " + status, "refresh error propagated");
            }
            equal(0, f.catalogReads, "failed refresh never reads potentially stale catalog");
            equal(0, f.puts, "failed refresh never writes prompt");
            f.refreshStatus = 204;
            equal("valid", f.client().loadLora("valid", 1).name(), "empty 204 refresh response accepted");
        }
        try (Fixture f = new Fixture()) {
            f.add("valid", "Alias", "models");
            f.catalogStatus = 500;
            expect(() -> f.client().loadLora("valid", 1), "HTTP 500", "catalog failure propagated");
            f.catalogStatus = 200; f.catalogBody = "{}";
            expect(() -> f.client().loras(), "列表", "non-array catalog rejected");
            f.catalogBody = "[{\"name\":\"bad\",\"alias\":\"alias\"}]";
            expect(() -> f.client().loras(), "path", "missing official path rejected");
            equal(0, f.puts, "bad catalog never updates prompt");
        }
    }

    private static void weightsAndTagInjectionAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.add("valid", "Alias", "models");
            SdClient client = f.client();
            for (double weight : new double[] {-0.01, 2.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
                expect(() -> client.loadLora("valid", weight), "有限数字", "invalid weight rejected");
            for (String name : new String[] {"<lora:evil:1>", "bad:1", "x>suffix", "line\nbreak", "tab\tname", "line\u2028separator", "../valid"})
                expect(() -> client.loadLora(name, 1), "不能包含", "tag/path injection rejected");
            equal(0, f.refreshes, "invalid inputs fail before network side effects");
            f.catalog.get(0).getAsJsonObject().addProperty("name", "malicious:tag");
            expect(() -> client.loadLora("Alias", 1), "不能包含", "unsafe canonical name rejected even through safe alias");
            f.catalog.get(0).getAsJsonObject().addProperty("name", "wrong-stem");
            expect(() -> client.loadLora("Alias", 1), "不一致", "canonical name must match real file stem");
            equal(0, f.puts, "unsafe catalog cannot update prompts");
        }
    }

    private static void bridgeFailureNeverFallsBackToLocal() throws Exception {
        try (Fixture f = new Fixture()) {
            Path downloaded = f.add("valid", "Alias", "models");
            JsonObject local = new JsonObject(); local.addProperty("positive", "local original"); local.addProperty("negative", "local negative");
            Json.atomicWrite(f.root.resolve("data/sd-state.json"), local);
            SdClient client = f.client();
            for (int status : new int[] {404, 401, 403, 500}) {
                f.bridgeStatus = status;
                expect(() -> client.loadLora(downloaded, 1), status == 404 ? "桥接不可用" : "HTTP " + status, "bridge failure is an error");
                equal("local original", Json.parse(Files.readString(f.root.resolve("data/sd-state.json"))).get("positive").getAsString(), "failure never writes local LoRA fallback");
                check(Files.exists(downloaded), "download survives bridge failure");
            }
            equal(0, f.configReads, "strict LoRA operation never reads startup defaults");
            f.bridgeStatus = 200; f.putStatus = 404;
            expect(() -> client.loadLora(downloaded, 1), "HTTP 404", "bridge vanishing during PUT is failure");
            equal("local original", Json.parse(Files.readString(f.root.resolve("data/sd-state.json"))).get("positive").getAsString(), "failed PUT never changes local snapshot");
        }
    }

    private static void compareAndSwapMergesLatestPromptsAndParameters() throws Exception {
        try (Fixture f = new Fixture()) {
            f.add("valid", "Alias", "models");
            f.conflicts = 1;
            SdClient.LoadedLora loaded = f.client().loadLora("valid", 0.6);
            equal(2, f.puts, "one CAS conflict retried");
            equal(2, f.bridgeGets, "retry reads latest bridge revision");
            equal("human edit 1, <lora:Alias:0.6>", loaded.prompts().positive(), "retry recomputes tag from latest human prompt");
            equal("new negative 1", loaded.prompts().negative(), "latest concurrent negative remains");
            equal(1024, f.width, "concurrent width remains");
            equal(0, f.configReads, "CAS never uses defaults");
        }
        try (Fixture f = new Fixture()) {
            Path downloaded = f.add("valid", "Alias", "models");
            f.conflicts = 9;
            expect(() -> f.client().loadLora(downloaded, 1), "其他窗口", "CAS retry limit reported");
            equal(3, f.puts, "CAS attempts bounded to three");
            equal(3, f.bridgeGets, "each failed CAS gets a fresh revision");
            check(!f.positive.contains("<lora:"), "conflict does not claim prompt change");
            check(Files.exists(downloaded), "conflict keeps downloaded file");
        }
    }

    private static void invalidAcknowledgementIsNotSuccess() throws Exception {
        try (Fixture f = new Fixture()) {
            f.add("valid", "Alias", "models");
            f.ignorePut = true;
            expect(() -> f.client().loadLora("valid", 1), "未确认", "successful HTTP must confirm actual prompt edit");
            check(!Files.exists(f.root.resolve("data/sd-state.json")), "unconfirmed response never persists claimed enable");
        }
    }

    private static int occurrences(String value, String fragment) { return value.split(java.util.regex.Pattern.quote(fragment), -1).length - 1; }
    private interface Operation { Object run() throws Exception; }
    private static void expect(Operation action, String fragment, String label) throws Exception {
        try { action.run(); throw new AssertionError(label + ": no exception"); }
        catch (IOException error) { check(error.getMessage().contains(fragment), label + ": " + error.getMessage()); }
    }
    private static void equal(Object expected, Object actual, String label) { check(Objects.equals(expected, actual), label + ": expected=" + expected + ", actual=" + actual); }
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final JsonObject config = new JsonObject();
        final JsonArray catalog = new JsonArray();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool(runnable -> { Thread thread = new Thread(runnable, "lora-fixture"); thread.setDaemon(true); return thread; });
        volatile int refreshes, catalogReads, bridgeGets, puts, configReads, generations;
        volatile int refreshStatus = 200, catalogStatus = 200, bridgeStatus = 200, putStatus = 200;
        volatile int revision = 1, conflicts, width = 896;
        volatile boolean ignorePut;
        volatile String positive = "base prompt", negative = "preserve negative", catalogBody;
        volatile JsonObject lastPut;

        Fixture() throws IOException {
            Path work = Path.of(System.getProperty("bot.test.work", "work")).resolve("sd-lora-tests").toAbsolutePath();
            Files.createDirectories(work); root = Files.createTempDirectory(work, "case-");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor); server.createContext("/", this::handle); server.start();
            config.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            config.addProperty("timeout_seconds", 3);
        }

        Path add(String name, String alias, String folder) throws IOException {
            Path file = root.resolve(folder).resolve(name + ".safetensors");
            Files.createDirectories(file.getParent()); Files.writeString(file, "isolated mock LoRA");
            JsonObject item = new JsonObject(); item.addProperty("name", name); item.addProperty("alias", alias);
            item.addProperty("path", file.toString()); item.add("metadata", new JsonObject()); catalog.add(item);
            return file;
        }

        SdClient client() throws IOException { return new SdClient(root, config); }
        JsonObject state() {
            JsonObject value = new JsonObject(); value.addProperty("positive", positive); value.addProperty("negative", negative);
            value.addProperty("source", "webui-live"); value.addProperty("live", true); value.addProperty("revision", revision);
            value.addProperty("initialized", true); value.addProperty("settings_initialized", true);
            value.addProperty("sampler_name", "DDIM"); value.addProperty("width", width); value.addProperty("height", 1152);
            JsonArray styles = new JsonArray(); styles.add("Watercolor"); value.add("styles", styles); return value;
        }

        void handle(HttpExchange exchange) throws IOException {
            try {
                String route = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
                calls.add(method + " " + route);
                if (!"1".equals(exchange.getRequestHeaders().getFirst("X-Pixiko-Bridge"))) { send(exchange, 403, "{}"); return; }
                if (route.equals("/sdapi/v1/refresh-loras")) {
                    refreshes++;
                    send(exchange, method.equals("POST") ? refreshStatus : 405, "null");
                } else if (route.equals("/sdapi/v1/loras")) {
                    catalogReads++; send(exchange, catalogStatus, catalogBody == null ? Json.GSON.toJson(catalog) : catalogBody);
                } else if (route.equals("/pixiko-bridge/v1/prompts")) {
                    if (bridgeStatus != 200) { send(exchange, bridgeStatus, "{}"); return; }
                    if (method.equals("GET")) bridgeGets++;
                    else if (method.equals("PUT")) {
                        puts++; lastPut = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        if (putStatus != 200) { send(exchange, putStatus, "{}"); return; }
                        if (conflicts > 0) {
                            conflicts--; revision++; positive = "human edit " + puts; negative = "new negative " + puts; width = 1024;
                            send(exchange, 409, Json.GSON.toJson(state())); return;
                        }
                        if (lastPut.get("expected_revision").getAsInt() != revision) { send(exchange, 409, Json.GSON.toJson(state())); return; }
                        if (!ignorePut) positive = lastPut.get("positive").getAsString();
                        revision++;
                    }
                    send(exchange, 200, Json.GSON.toJson(state()));
                } else if (route.equals("/config")) { configReads++; send(exchange, 200, "{}"); }
                else if (route.equals("/sdapi/v1/txt2img")) { generations++; send(exchange, 500, "{}"); }
                else send(exchange, 404, "{}");
            } finally { exchange.close(); }
        }

        static void send(HttpExchange exchange, int code, String body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (code == 204) { exchange.sendResponseHeaders(code, -1); return; }
            byte[] content = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, content.length); exchange.getResponseBody().write(content);
        }

        public void close() throws IOException {
            server.stop(0); executor.shutdownNow();
            // Only this exact generated fixture directory is traversed; no symbolic links followed.
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

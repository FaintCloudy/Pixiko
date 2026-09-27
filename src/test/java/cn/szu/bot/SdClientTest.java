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

/** Run with java -ea; all HTTP traffic stays on ephemeral loopback mock servers. */
public final class SdClientTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        fallbackAndPersistence();
        bridgeEditsAndConflict();
        bridgeFailuresNeverBecomeLocalEdits();
        offlineFallback();
        generatedImagesAndManifest();
        invalidResponsesPreserveLatest();
        timeoutIsNotAnOfflineFallback();
        System.out.println("SdClientTest: " + assertions + " assertions passed.");
    }

    private static void fallbackAndPersistence() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.bridgeStatus = 404;
            SdClient client = fixture.client();
            SdClient.Prompts initial = client.prompts();
            equal("startup positive", initial.positive(), "read startup positive");
            equal("startup negative", initial.negative(), "read startup negative");
            check(initial.source().contains("启动默认值"), "default source is honest");
            equal("startup positive, 风景", client.change(false, "add", "风景").positive(), "append literal prompt");
            equal("startup negative, (bad:1.2)", client.change(true, "add", "(bad:1.2)").negative(), "negative append");
            equal("a.* / x a.*", client.change(false, "edit", "a.* / x a.*").positive(), "whole replacement");
            equal("", client.change(false, "remove", "a.*").positive(), "remove all literal matches, no regex");
            equal("", client.change(true, "edit", "").negative(), "empty edit clears negative");
            expectFailure(() -> client.change(false, "add", " "), "必须提供", "blank add rejected");
            SdClient restarted = fixture.client();
            equal("", restarted.prompts().positive(), "prompts persist across restart");
            equal("", restarted.prompts().negative(), "cleared negative persists");
            check(restarted.prompts().source().contains("未同步"), "local state source is honest");
            equal(1, fixture.configReads.get(), "startup defaults never overwrite persisted prompt");
        }
    }

    private static void bridgeEditsAndConflict() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.requireAuthorization = "Basic " + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
            fixture.configuration.addProperty("api_username", "user");
            fixture.configuration.addProperty("api_password", "secret");
            SdClient client = fixture.client();
            equal("live positive", client.prompts().positive(), "bridge overrides defaults");
            check(client.prompts().source().contains("实时同步"), "live label");
            fixture.conflictOnce = true;
            equal("external edit, tail", client.change(false, "add", "tail").positive(), "retry re-applies edit to fresh revision");
            equal(2, fixture.puts.get(), "conflict retried once");
            equal("external edit, tail", fixture.positive, "bridge receives updated positive");
            equal("", client.change(true, "edit", "").negative(), "live negative can be cleared");
            fixture.live = false;
            check(client.prompts().source().contains("页面未实时连接"), "stale browser source is explicit");
            fixture.bridgeStatus = 404;
            equal("external edit, tail", fixture.client().prompts().positive(), "bridge state retained offline on restart");
            equal(0, fixture.configReads.get(), "live bridge never reads startup config");
        }
    }

    private static void bridgeFailuresNeverBecomeLocalEdits() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SdClient client = fixture.client();
            client.prompts();
            for (int status : new int[] {401, 403, 500}) {
                fixture.bridgeStatus = status;
                expectFailure(() -> client.change(false, "edit", "must not save"), "HTTP " + status,
                        "GET " + status + " is an error");
                equal("live positive", persistedPositive(fixture.root), "GET failure cannot create successful local edit");
            }
            fixture.bridgeStatus = 200;
            fixture.putStatus = 500;
            expectFailure(() -> client.change(false, "edit", "must not save"), "HTTP 500", "PUT failure propagates");
            equal("live positive", persistedPositive(fixture.root), "PUT failure preserves last known prompt");
            fixture.putStatus = 404;
            expectFailure(() -> client.change(false, "edit", "must not save"), "HTTP 404", "bridge disappears mid-edit");
            fixture.putStatus = 200;
            fixture.alwaysConflict = true;
            int before = fixture.puts.get();
            expectFailure(() -> client.change(false, "add", "tail"), "其他窗口", "conflict bounded");
            equal(3, fixture.puts.get() - before, "three optimistic retries maximum");
            fixture.alwaysConflict = false;
            fixture.malformedBridge = true;
            expectFailure(client::prompts, "revision", "malformed bridge data does not fall back");
        }
    }

    private static void offlineFallback() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.server.stop(0);
            SdClient client = fixture.client();
            equal("", client.prompts().positive(), "offline fresh state starts empty");
            check(client.change(false, "add", "offline prompt").source().contains("未同步"), "offline edit labeled");
            equal("offline prompt", fixture.client().prompts().positive(), "offline prompt persists");
            expectFailure(() -> client.generate(client.prompts()), "无法连接", "offline generation errors");
        } finally { fixture.close(); }
    }

    private static void generatedImagesAndManifest() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SdClient client = fixture.client();
            equal(List.of(), client.latestImages(), "no latest images initially");
            fixture.images = List.of(image("png"), "data:image/jpeg;base64," + image("jpg"));
            List<Path> result = client.generate(new SdClient.Prompts("actual +", "actual -", "test"));
            equal(2, result.size(), "all API images returned");
            check(result.get(0).toString().endsWith(".png"), "PNG detected by content");
            check(result.get(1).toString().endsWith(".jpg"), "JPEG detected by content");
            check(Files.size(result.get(0)) > 0 && ImageIO.read(result.get(1).toFile()) != null, "valid files saved");
            equal("actual +", fixture.lastGeneration.get("prompt").getAsString(), "generation uses positive");
            equal("actual -", fixture.lastGeneration.get("negative_prompt").getAsString(), "generation uses negative");
            equal(7.5, fixture.lastGeneration.get("cfg_scale").getAsDouble(), "fractional CFG preserved");
            equal(9876543210L, fixture.lastGeneration.get("seed").getAsLong(), "64-bit seed preserved");
            equal(result, fixture.client().latestImages(), "latest image paths persist across restart");
            fixture.images = List.of(image("png"));
            List<Path> second = client.generate(new SdClient.Prompts("second", "", "test"));
            check(!second.get(0).getParent().equals(result.get(0).getParent()), "unique generation directory");
            equal(second, client.latestImages(), "latest points to newest successful request");
            JsonObject malicious = new JsonObject();
            JsonArray paths = new JsonArray();
            paths.add("../outside.png");
            malicious.add("images", paths);
            Json.atomicWrite(fixture.root.resolve("data/sd-latest.json"), malicious);
            expectFailure(client::latestImages, "路径无效", "manifest path traversal rejected");
        }
    }

    private static void invalidResponsesPreserveLatest() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SdClient client = fixture.client();
            SdClient.Prompts prompts = new SdClient.Prompts("+", "-", "test");
            fixture.images = List.of(image("png"));
            List<Path> baseline = client.generate(prompts);
            fixture.images = List.of(image("png"), Base64.getEncoder().encodeToString("not an image".getBytes(StandardCharsets.UTF_8)));
            expectFailure(() -> client.generate(prompts), "PNG/JPEG", "invalid second image rejects complete response");
            equal(baseline, client.latestImages(), "invalid image preserves latest generation");
            fixture.images = List.of("!invalid base64!");
            expectFailure(() -> client.generate(prompts), "Base64", "invalid base64 rejected");
            fixture.images = List.of();
            expectFailure(() -> client.generate(prompts), "未返回图片", "empty images rejected");
            fixture.generationStatus = 500;
            expectFailure(() -> client.generate(prompts), "HTTP 500", "API failure propagated");
            equal(baseline, client.latestImages(), "all failed generations preserve latest");
            try (var directories = Files.list(fixture.root.resolve("data/generated"))) {
                equal(1L, directories.count(), "malformed responses produce no partial generation directories");
            }
        }
    }

    private static void timeoutIsNotAnOfflineFallback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.configuration.addProperty("timeout_seconds", 1);
            fixture.delayMillis = 1800;
            expectFailure(() -> fixture.client().prompts(), "超时", "bridge timeout does not fall back");
            check(!Files.exists(fixture.root.resolve("data/sd-state.json")), "timeout cannot write empty local state");
        }
    }

    private static String persistedPositive(Path root) throws IOException {
        return Json.parse(Files.readString(root.resolve("data/sd-state.json"))).get("positive").getAsString();
    }

    private static String image(String format) throws IOException {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0x336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private interface Operation { Object run() throws Exception; }
    private static void expectFailure(Operation operation, String fragment, String message) throws Exception {
        try { operation.run(); throw new AssertionError(message + ": expected an exception"); }
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
        final AtomicInteger configReads = new AtomicInteger(), puts = new AtomicInteger();
        volatile int bridgeStatus = 200, putStatus = 200, generationStatus = 200, revision = 1;
        volatile String positive = "live positive", negative = "live negative", requireAuthorization;
        volatile boolean conflictOnce, alwaysConflict, malformedBridge, live = true;
        volatile long delayMillis;
        volatile List<String> images = List.of();
        volatile JsonObject lastGeneration;

        Fixture() throws IOException {
            Path work = Path.of("work", "sd-client-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "sd-mock"); thread.setDaemon(true); return thread;
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
                if (!"1".equals(exchange.getRequestHeaders().getFirst("X-Pixiko-Bridge"))) {
                    send(exchange, 403, "{}"); return;
                }
                if (requireAuthorization != null && !requireAuthorization.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    send(exchange, 401, "{}"); return;
                }
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/pixiko-bridge/v1/prompts")) {
                    if (delayMillis > 0) Thread.sleep(delayMillis);
                    if (bridgeStatus != 200) { send(exchange, bridgeStatus, "{\"detail\":\"mock bridge error\"}"); return; }
                    if (exchange.getRequestMethod().equals("PUT")) {
                        puts.incrementAndGet();
                        if (putStatus != 200) { send(exchange, putStatus, "{}"); return; }
                        JsonObject payload = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        if (conflictOnce || alwaysConflict) {
                            conflictOnce = false; positive = "external edit"; revision++;
                            send(exchange, 409, Json.GSON.toJson(state())); return;
                        }
                        if (payload.get("expected_revision").getAsInt() != revision) {
                            send(exchange, 409, Json.GSON.toJson(state())); return;
                        }
                        positive = payload.get("positive").getAsString();
                        negative = payload.get("negative").getAsString();
                        revision++;
                    }
                    send(exchange, 200, malformedBridge ? "{\"positive\":\"bad\"}" : Json.GSON.toJson(state()));
                } else if (path.equals("/config")) {
                    configReads.incrementAndGet();
                    send(exchange, 200, "{\"components\":[{\"props\":{\"elem_id\":\"txt2img_prompt\",\"value\":\"startup positive\"}},{\"props\":{\"elem_id\":\"txt2img_neg_prompt\",\"value\":\"startup negative\"}}]}");
                } else if (path.equals("/sdapi/v1/txt2img")) {
                    lastGeneration = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    JsonObject response = new JsonObject();
                    JsonArray array = new JsonArray();
                    for (String image : images) array.add(image);
                    response.add("images", array);
                    send(exchange, generationStatus, Json.GSON.toJson(response));
                } else send(exchange, 404, "{}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); exchange.close();
            } finally { exchange.close(); }
        }

        JsonObject state() {
            JsonObject state = new JsonObject();
            state.addProperty("positive", positive); state.addProperty("negative", negative);
            state.addProperty("revision", revision); state.addProperty("source", live ? "webui-live" : "webui-state");
            state.addProperty("live", live); return state;
        }

        static void send(HttpExchange exchange, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        public void close() throws IOException {
            server.stop(0); executor.shutdownNow();
            // Each case owns this exact generated directory; do not traverse links or external paths.
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

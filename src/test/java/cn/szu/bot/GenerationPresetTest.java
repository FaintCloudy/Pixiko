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
import cn.szu.bot.sd.GenerationPreset;
import cn.szu.bot.sd.SdClient;

/** Full command/SD integration against an isolated loopback API; no real generation or chat. */
public final class GenerationPresetTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        try (Fixture f = new Fixture()) {
            check(f.command("group", ".preset list").contains("（无）"), "empty catalog");
            check(f.command("private", ".steps set 32").contains("32"), "set steps");
            check(f.command("group", ".cfg set 5.5").contains("5.5"), "set CFG");
            check(f.command("private", ".seed set 12345").contains("12345"), "set seed");
            check(f.command("group", ".model list").contains("Model A [aaaa]"), "model catalog");
            check(f.command("group", ".settings").contains("迭代步数：32"), "settings reports effective extended parameters");
            check(f.command("group", ".preset save 人像 参数").contains("已保存"), "save parameter preset");
            GenerationPreset.Store store = new GenerationPreset.Store(f.root);
            GenerationPreset saved = store.get("人像 参数");
            check(saved.parameters().checkpoint().equals("Model A [aaaa]"), "captures current server checkpoint");
            check(saved.parameters().steps() == 32 && saved.parameters().seed() == 12345, "captures configured generation values");
            check(!saved.json().has("styles") && !saved.json().has("positive"), "parameter preset excludes prompt content/styles");
            check(f.command("private", ".preset save 人像 参数").contains("同名"), "no implicit overwrite");
            check(f.command("private", ".preset show 人像 参数").contains("512 × 512"), "show dimensions");
            SdClient.GenerationRequest before = f.sd.generationRequest();
            f.command("private", ".steps set 8"); f.command("private", ".cfg set 2");
            f.command("private", ".seed set -1"); f.command("private", ".size set 768 1024");
            f.command("private", ".sampler set Euler");
            check(f.command("private", ".model set Model B [bbbb]").contains("已保存"), "select different model");
            SdClient.GenerationRequest after = f.sd.generationRequest();
            check(f.command("group", ".preset load 人像 参数").contains("已加载"), "load preset");
            // 网页控制台一直发的是带引号的写法；引号不能被当成名称的一部分（曾经因此"参数预设不存在"）。
            check(f.command("private", ".preset load \"人像 参数\"").contains("已加载"), "load preset given with quotes");
            check(f.command("private", ".preset show \"人像 参数\"").contains("人像 参数"), "show preset given with quotes");
            check(f.command("private", ".preset load \"没有这个预设\"").contains("不存在"), "quoted missing preset still reports not found");
            check(f.sd.settings().width() == 512 && f.sd.settings().height() == 512, "dimensions restored");
            check(f.sd.settings().samplerName().equals("Euler a"), "sampler restored");
            check(f.sd.settings().styles().equals(List.of("existing style")), "prompt style selection preserved");
            check(f.sd.prompts().positive().equals("live prompt") && f.sd.prompts().negative().equals("live negative"), "prompt pair unchanged");
            check(f.sd.parameters().equals(saved.parameters()), "extended parameters restored");
            SdClient restarted = new SdClient(f.root, f.config);
            check(restarted.parameters().equals(saved.parameters()), "loaded settings survive restart");
            f.sd.generate(after);
            JsonObject payload = f.requests.poll(3, TimeUnit.SECONDS);
            check(payload != null && payload.get("steps").getAsInt() == 8 && payload.get("width").getAsInt() == 768, "queued request retains previous steps/dimensions");
            check(payload.get("cfg_scale").getAsDouble() == 2 && payload.get("seed").getAsLong() == -1, "queued CFG/seed unchanged by preset load");
            check(payload.getAsJsonObject("override_settings").get("sd_model_checkpoint").getAsString().equals("Model B [bbbb]"), "queued model captured");
            check(payload.get("override_settings_restore_afterwards").getAsBoolean(), "per-job model override restores server selection");
            f.sd.generate(before);
            payload = f.requests.poll(3, TimeUnit.SECONDS);
            check(payload.get("steps").getAsInt() == 32 && payload.getAsJsonObject("override_settings").get("sd_model_checkpoint").getAsString().equals("Model A [aaaa]"), "older snapshot remains intact");
            f.command("private", ".preset overwrite 人像 参数");
            check(f.command("group", ".preset list").contains("人像 参数"), "overwrite retained name");
            f.command("private", ".size set 768 768");
            f.availableA = false;
            check(f.command("private", ".preset load 人像 参数").contains("不存在"), "removed checkpoint prevents load");
            check(f.sd.settings().width() == 768, "failed validation does not change dimensions");
            try { f.sd.generate(before); throw new AssertionError("missing checkpoint must not fall back to another model"); }
            catch (IOException expected) { check(expected.getMessage().contains("基础模型"), "missing snapshot checkpoint explicit failure"); }
            check(f.requests.isEmpty(), "invalid model never reaches generation endpoint");
            for (String bad : List.of(".steps set 0", ".cfg set NaN", ".seed set -2", ".preset save", ".model set missing"))
                check(f.command("group", bad).contains("失败"), "invalid parameters rejected: " + bad);
            check(f.command("private", ".preset remove 人像 参数").contains("已删除"), "remove preset");
            check(new GenerationPreset.Store(f.root).names().isEmpty(), "removal persisted");
            Files.writeString(f.root.resolve("data/sd-presets.json"), "broken json");
            check(f.command("private", ".preset list").contains("无法读取"), "corrupt catalog reported");
            check(Files.readString(f.root.resolve("data/sd-presets.json")).equals("broken json"), "corrupt file not silently reset");
        }
        System.out.println("GenerationPresetTest: " + checks + " checks passed: CRUD, group/private, persistence, prompt isolation, snapshot model/steps/CFG/seed, failed validation.");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
        final JsonObject config = new JsonObject();
        final JsonObject state = Json.parse("{\"positive\":\"live prompt\",\"negative\":\"live negative\",\"source\":\"webui-live\",\"revision\":1,\"sampler_name\":\"Euler a\",\"styles\":[\"existing style\"],\"width\":512,\"height\":512,\"settings_initialized\":true}");
        final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonObject> requests = new LinkedBlockingQueue<>();
        final AtomicInteger ids = new AtomicInteger();
        final SdClient sd;
        final Bot bot;
        final String image;
        boolean availableA = true;
        boolean failAfterPromptPut, conflictNextPromptPut;
        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "preset-tests").toAbsolutePath(); Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
            image = Base64.getEncoder().encodeToString(bytes.toByteArray());
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.createContext("/", this::handle); server.start();
            config.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            JsonObject settings = new JsonObject(); settings.add("sd", config); settings.addProperty("owner_user_id", "2"); Json.atomicWrite(root.resolve("config.json"), settings);
            sd = new SdClient(root, config);
            bot = new Bot(new Settings(root), sd, (event, segments) -> { replies.add(Bot.messageText(segments)); return CompletableFuture.completedFuture(null); });
        }
        String command(String type, String text) throws Exception {
            JsonObject event = new JsonObject(); event.addProperty("post_type", "message"); event.addProperty("message_type", type);
            event.addProperty("self_id", 1); event.addProperty("user_id", 2); event.addProperty("group_id", 3);
            event.addProperty("message_id", ids.incrementAndGet()); event.addProperty("message", text); bot.accept(event);
            String result = replies.poll(3, TimeUnit.SECONDS); check(result != null, "reply for " + text); return result;
        }
        void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String route = exchange.getRequestURI().getPath(), result = "{}"; int status = 200;
                switch (route) {
                    case "/pixiko-bridge/v1/prompts" -> {
                        if (exchange.getRequestMethod().equals("PUT")) {
                            JsonObject update = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                            if (conflictNextPromptPut) {
                                conflictNextPromptPut = false; status = 409;
                                state.addProperty("positive", "concurrent, " + state.get("positive").getAsString());
                            } else {
                                for (String key : List.of("width", "height", "sampler_name", "styles", "positive", "negative"))
                                    if (update.has(key)) state.add(key, update.get(key));
                                if (failAfterPromptPut) { failAfterPromptPut = false; status = 500; }
                            }
                            state.addProperty("revision", state.get("revision").getAsInt() + 1);
                        }
                        result = state.toString();
                    }
                    case "/pixiko-bridge/v1/styles/rename" -> {
                        JsonObject update = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        String source = update.get("name").getAsString(), target = update.get("new_name").getAsString();
                        if (!"replace".equals(source)) { status = 400; result = "{}"; }
                        else {
                            state.add("styles", Json.GSON.toJsonTree(java.util.List.of()));
                            result = "{\"name\":\"" + target + "\",\"renamed\":true}";
                        }
                    }                    case "/sdapi/v1/options" -> result = "{\"sd_model_checkpoint\":\"Model A [aaaa]\"}";
                    case "/sdapi/v1/sd-models" -> result = availableA ? "[{\"title\":\"Model A [aaaa]\"},{\"title\":\"Model B [bbbb]\"}]" : "[{\"title\":\"Model B [bbbb]\"}]";
                    case "/sdapi/v1/samplers" -> result = "[{\"name\":\"Euler a\",\"aliases\":[]},{\"name\":\"Euler\",\"aliases\":[]}]";
                    case "/sdapi/v1/prompt-styles" -> result = "[{\"name\":\"replace\",\"prompt\":\"replacement, red hair\",\"negative_prompt\":\"replacement negative\"}]";
                    case "/sdapi/v1/loras" -> result = "[]";
                    case "/sdapi/v1/txt2img" -> {
                        requests.add(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                        result = "{\"images\":[\"" + image + "\"]}";
                    }
                    default -> status = 404;
                }
                byte[] bytes = result.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            }
        }
        public void close() throws IOException {
            bot.close(); server.stop(0);
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}

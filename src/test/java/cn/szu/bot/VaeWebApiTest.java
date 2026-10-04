package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.web.WebUiServer;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网页侧 VAE 接口（{@code /api/sd/vae*}）的测试：起真的 WebUi 服务（回环 + 随机端口 + 令牌），
 * SD 是一个本地假桩，绝不碰真机器人、真 SD、外网。
 */
public final class VaeWebApiTest {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String TOKEN = "test-token-123456";
    private static final String SDXL = "waiIllustriousSDXL_v170.safetensors";
    private static final String QWEN_VAE = "qwen_image_vae.safetensors";
    private static final String QWEN_ENCODER = "qwen_3_06b_base.safetensors";

    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "vae-web-tests").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        JsonObject options = new JsonObject();
        options.addProperty("sd_vae", "qwen_image_vae.safetensors");
        options.addProperty("sd_model_checkpoint", SDXL);
        JsonArray modules = new JsonArray();
        modules.add("F:/sd/models/VAE/qwen_image_vae.safetensors");
        modules.add("F:/sd/models/text_encoder/qwen_3_06b_base.safetensors");
        options.add("forge_additional_modules", modules);
        AtomicInteger optionPosts = new AtomicInteger();
        HttpServer sd = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sd.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/sdapi/v1/options")) {
                    synchronized (options) {
                        if (exchange.getRequestMethod().equals("POST")) {
                            optionPosts.incrementAndGet();
                            JsonObject body = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                            for (var entry : body.entrySet()) options.add(entry.getKey(), entry.getValue().deepCopy());
                        }
                        send(exchange, 200, options.toString());
                    }
                } else if (path.equals("/sdapi/v1/sd-modules")) {
                    JsonArray catalog = new JsonArray();
                    JsonObject entry = new JsonObject();
                    entry.addProperty("model_name", QWEN_VAE);
                    entry.addProperty("filename", "F:\\sd\\models\\VAE\\" + QWEN_VAE);
                    catalog.add(entry);
                    send(exchange, 200, catalog.toString());
                } else {
                    send(exchange, 404, "{}");
                }
            } finally { exchange.close(); }
        });
        sd.start();
        try {
            JsonObject sdConfig = new JsonObject();
            sdConfig.addProperty("base_url", "http://127.0.0.1:" + sd.getAddress().getPort());
            sdConfig.addProperty("timeout_seconds", 5);
            JsonObject config = new JsonObject();
            config.add("sd", sdConfig);
            JsonObject webui = new JsonObject();
            webui.addProperty("enabled", true);
            webui.addProperty("host", "127.0.0.1");
            webui.addProperty("port", 0);
            webui.addProperty("access_token", TOKEN);
            config.add("webui", webui);
            Json.atomicWrite(root.resolve("config.json"), config);
            Files.createDirectories(root.resolve("data"));
            Files.writeString(root.resolve("data/deepseek-api-key.txt"), "test-key-not-real\n");

            int port = freePort();
            Settings settings = new Settings(root);
            settings.webSetting("port", new JsonPrimitive(port));
            SdClient client = new SdClient(root, sdConfig);
            try (Bot bot = new Bot(settings, client,
                    (event, segments) -> CompletableFuture.completedFuture(null));
                 WebUiServer server = new WebUiServer(settings, bot)) {
                server.start();
                String base = "http://127.0.0.1:" + port;

                // ① POST /api/sd/vae：直接回 vaeSnapshot()
                JsonObject snapshot = post(base, "/api/sd/vae", new JsonObject());
                check(snapshot.has("vae") && snapshot.has("vaeAuto"), "vae 快照带 vae/vaeAuto：" + snapshot);
                equal(QWEN_VAE, snapshot.get("vae").getAsString(), "快照报当前 sd_vae");
                check(snapshot.has("modules") && snapshot.getAsJsonArray("modules").size() == 2, "快照带额外模块");
                equal(SDXL, snapshot.get("checkpoint").getAsString(), "快照带当前底模");
                JsonObject conflict = snapshot.getAsJsonObject("conflict");
                check(conflict != null, "快照带冲突判定");
                equal("block", conflict.get("level").getAsString(), "事故组合判成 block（小写，与 SdClient 一致）");
                check(conflict.get("reason").getAsString().contains("不是同一族"), "冲突原因：" + conflict.get("reason").getAsString());
                check(!conflict.get("suggestion").getAsString().isBlank(), "冲突建议非空");
                check(conflict.getAsJsonArray("culprits").size() >= 2, "冲突模块列表");
                JsonArray choices = snapshot.getAsJsonArray("choices");
                check(choices != null && choices.size() >= 2, "快照带 choices：" + choices);
                equal("Automatic", choices.get(0).getAsString(), "choices 第一个是 Automatic");
                equal("None", choices.get(1).getAsString(), "choices 第二个是 None");
                check(snapshot.has("reachable") && snapshot.get("reachable").getAsBoolean(), "快照标了 reachable=true");

                // ② /api/sd/vae/list
                JsonObject list = post(base, "/api/sd/vae/list", new JsonObject());
                check(list.has("choices"), "list 回 choices");
                check(list.getAsJsonArray("choices").size() >= 3, "list 含 Automatic/None/文件：" + list);
                equal("Automatic", list.getAsJsonArray("choices").get(0).getAsString(), "list 第一个是 Automatic");
                check(list.getAsJsonArray("choices").toString().contains(QWEN_VAE), "list 含本机 VAE 文件");
                JsonObject listByGet = get(base, "/api/sd/vae/list");
                equal(list.getAsJsonArray("choices").size(), listByGet.getAsJsonArray("choices").size(), "list 也接受 GET");

                // ③ POST /api/sd/vae/set：设置后回同一份快照 + message
                JsonObject setBody = new JsonObject();
                setBody.addProperty("name", "None");
                int before = optionPosts.get();
                JsonObject set = post(base, "/api/sd/vae/set", setBody);
                check(optionPosts.get() > before, "set 真的写了 WebUI options");
                equal("None", set.get("vae").getAsString(), "set 回读到的 vae");
                check(set.get("message").getAsString().contains("VAE 已设置为"), "set 带 message：" + set.get("message").getAsString());
                check(set.has("conflict") && set.has("choices"), "set 回的是同一份快照");

                // ④ 非法名字：400 + 可用列表
                JsonObject illegalBody = new JsonObject();
                illegalBody.addProperty("name", "nope.safetensors");
                HttpResponse<String> illegal = send(base, "/api/sd/vae/set", illegalBody.toString());
                equal(400, illegal.statusCode(), "非法名字回 400");
                check(illegal.body().contains("nope.safetensors"), "非法名字说明是哪一个：" + illegal.body());
                check(illegal.body().contains("Automatic"), "非法名字带上可用列表");

                // ⑤ POST /api/sd/vae/fix：清模块 + VAE 设回 Automatic，回 actions
                synchronized (options) { options.addProperty("sd_vae", QWEN_VAE); }
                JsonObject fix = post(base, "/api/sd/vae/fix", new JsonObject());
                check(fix.has("actions") && fix.getAsJsonArray("actions").size() >= 1, "fix 回 actions：" + fix);
                check(fix.get("message").getAsString().contains("已执行修复"), "fix 带 message：" + fix.get("message").getAsString());
                equal("Automatic", fix.get("vae").getAsString(), "fix 后 VAE 是 Automatic");
                check(fix.getAsJsonArray("actions").toString().contains("Automatic"), "actions 里说明了设回 Automatic");
                synchronized (options) { equal("Automatic", options.get("sd_vae").getAsString(), "options 里的 sd_vae 已改"); }
                check(fix.has("conflict") && fix.has("choices"), "fix 回同一份快照");

                // ⑥ 权限与其它 SD 接口一致：没令牌 401，有令牌才通
                HttpResponse<String> anonymous = anonymousPost(base, "/api/sd/vae");
                equal(401, anonymous.statusCode(), "无令牌访问 VAE 接口回 401");
                check(anonymous.body().contains("令牌"), "401 说明令牌问题：" + anonymous.body());
                equal(200, getStatus(base, "/api/sd/vae"), "有令牌 GET 也通（只读接口）");
            }
        } finally {
            sd.stop(0);
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
        System.out.println("VaeWebApiTest: " + assertions + " assertions passed: "
                + "POST /api/sd/vae（快照 + 冲突）、/api/sd/vae/list、/api/sd/vae/set（含非法名字 400）、"
                + "/api/sd/vae/fix（actions + 回读）、令牌鉴权与 GET。");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static JsonObject post(String base, String path, JsonObject body) throws Exception {
        HttpResponse<String> response = send(base, path, body.toString());
        if (response.statusCode() != 200)
            throw new AssertionError(path + " 期望 200，实际 " + response.statusCode() + "：" + response.body());
        return Json.parse(response.body());
    }

    private static JsonObject get(String base, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + TOKEN).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new AssertionError(path + " 期望 200，实际 " + response.statusCode() + "：" + response.body());
        return Json.parse(response.body());
    }

    private static int getStatus(String base, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + TOKEN).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).statusCode();
    }

    private static HttpResponse<String> send(String base, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json");
        builder.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 不带令牌的请求（鉴权用例）。 */
    private static HttpResponse<String> anonymousPost(String base, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String text) throws java.io.IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }
}

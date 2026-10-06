package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.web.ChatLogStore;
import cn.szu.bot.web.WebUiServer;

/**
 * 服务端把回执消息 append 进对话正文（{@code data/webui/<scope>-chat-log.json}）的端到端验证：
 *
 * <ol>
 *   <li><b>幂等</b>：同一条回执消息 append 两次 → 日志里只有 1 条（条数与内容都断言）；</li>
 *   <li><b>不丢</b>：一条多步回执（6 条正文 + 1 张图，图在第 5 条之后）走完 → 每条正文各 1 条、
 *       图挂在第 5 条上，并与 {@code /api/quest} 的 {@code texts}/{@code messages} 逐条对齐；</li>
 *   <li><b>并发</b>：页面整组 push（{@code /api/chat/log/save}）与服务端 append 交替 20 轮 → 不重复、不丢；</li>
 *   <li><b>真 HTTP</b>：{@code /api/command} 产生的回执真的能被 {@code /api/chat/log} 读到，
 *       覆盖写之后仍补得回来，{@code /api/chat/reset} 照旧清空。</li>
 * </ol>
 *
 * <p>全程只用临时根目录 + 回环地址上的桩（SD 是本地 HTTP 桩、DeepSeek 由注入的假客户端回答），
 * <b>不碰</b> {@code F:\Bot} 那份正在运行的账本、不碰真 SD、不碰真模型。
 */
public final class ReceiptChatLogTest {
    private static final String TOKEN = "test-token-123456";
    /** 临时设备 scope（{@code dev-} + 12 位十六进制）——绝不是任何真实设备的账本。 */
    private static final String SCOPE = "dev-8b1f4c2a9d37";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static int checks;

    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError("FAIL: " + what); }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "receipt-chatlog").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        HttpServer sd = sdStub();
        try {
            Files.createDirectories(root.resolve("data/generated"));
            Files.writeString(root.resolve("data/deepseek-api-key.txt"), "stub-key\n");
            JsonObject sdConfig = new JsonObject();
            sdConfig.addProperty("base_url", "http://127.0.0.1:" + sd.getAddress().getPort());
            Json.atomicWrite(root.resolve("config.json"), config(sdConfig));

            Settings settings = new Settings(root);
            int port = freePort();
            settings.webSetting("port", new JsonPrimitive(port));
            settings.webSetting("access_token", new JsonPrimitive(TOKEN));

            List<JsonArray> sent = new ArrayList<>();
            Bot.Sender sender = new Bot.Sender() {
                @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                    sent.add(segments.deepCopy());
                    return CompletableFuture.completedFuture(null);
                }
                @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
                    return CompletableFuture.completedFuture(new JsonObject());
                }
            };
            Bot.useImageClientForTests(stubPrompts(settings));
            try (Bot bot = new Bot(settings, new SdClient(root, sdConfig), sender)) {
                idempotentAppend(root);
                multiStepReceipt(root, bot);
                concurrentPushAndAppend(root);
                httpPath(root, bot);
            } finally {
                Bot.useImageClientForTests(null);
            }
        } finally {
            sd.stop(0);
            TestCleanup.awaitQuiet(root, 5000);
            TestCleanup.deleteQuietly(root);
        }
        System.out.println("ReceiptChatLogTest: " + checks + " assertions passed：同一条回执 append 两次只有 1 条、"
                + "多步回执（6 条正文 + 1 张图）逐条对齐且图在第 5 条上、页面 push 与服务端 append 交替 20 轮不丢不重、"
                + "真 HTTP 的 /api/chat/log 读得到服务端 append 的条目且覆盖写之后仍补得回来。");
    }

    // ---------------------------------------------------------------- 1) 幂等

    private static void idempotentAppend(Path root) {
        ChatLogStore store = new ChatLogStore(root);
        List<ChatLogStore.Append> one = List.of(new ChatLogStore.Append("quest:9001:1", "结算完成，共 1 张。", List.of()));
        check(store.append(SCOPE, one) == 1, "第一次 append 落 1 条");
        check(store.load(SCOPE).size() == 1, "第一次 append 之后是 1 条");
        check(store.append(SCOPE, one) == 0, "同一条再 append 一次：不写");
        check(store.load(SCOPE).size() == 1, "append 两次仍然只有 1 条：" + store.load(SCOPE).size());
        check("结算完成，共 1 张。".equals(store.load(SCOPE).get(0).getAsJsonObject().get("text").getAsString()),
                "留下的正是那一条正文");
        // 换一个 store 实例（等价于机器人重启）：账本在磁盘上，照样不重复
        check(new ChatLogStore(root).append(SCOPE, one) == 0, "重启后同一条 append 依旧不写");
        check(new ChatLogStore(root).load(SCOPE).size() == 1, "重启后仍然只有 1 条");
    }

    // ---------------------------------------------------------------- 2) 多步回执：不丢、逐条对齐、图在第 5 条上

    private static void multiStepReceipt(Path root, Bot bot) throws Exception {
        Path image = root.resolve("data/generated/pic.png");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        Files.write(image, bytes.toByteArray());
        String file = "data/generated/pic.png";

        // 一条"infix + gen"形状的多步回执：5 条正文 → 1 张图 → 第 6 条正文（领取）。
        Bot.WebCapture capture = new Bot.WebCapture("verify-" + System.nanoTime(), ".infix … ; .gen", root.resolve("data/generated"), SCOPE);
        for (String step : List.of("正在通过 DeepSeek 智能修改你个人的提示词。", "智能修改已应用，本次变化：", "画面检查：通过",
                "已加入生成队列，共 1 次生成。", "任务 #15 已完成：1/1。")) {
            capture.capture(Maps.text(step));
        }
        JsonObject segment = new JsonObject();
        segment.addProperty("type", "image");
        JsonObject data = new JsonObject();
        data.addProperty("file", file);
        segment.add("data", data);
        JsonArray segments = new JsonArray();
        segments.add(segment);
        capture.capture(segments);
        capture.capture(Maps.text("本次领取完成，共 1 张。"));
        capture.finish();
        bot.observeQuestForTests(capture);

        JsonObject quest = bot.webQuest(capture.number(), SCOPE);
        JsonArray texts = quest.getAsJsonArray("texts");
        JsonArray questImages = quest.getAsJsonArray("images");
        check(texts.size() == 6, "/api/quest 的 texts 是 6 条正文：" + texts.size());
        check(questImages.size() == 1, "/api/quest 的 images 是 1 张：" + questImages.size());
        check(quest.getAsJsonArray("messages").size() == 7, "/api/quest 的 messages 是 7 组（6 正文 + 1 图）");

        JsonArray log = new ChatLogStore(root).load(SCOPE);
        // 幂等用例已经留了 1 条（"结算完成，共 1 张。"），所以这里是 1 + 6 条。
        check(log.size() == 7, "对话正文里 1 条（上个用例）+ 6 条：" + log.size());
        for (int index = 0; index < 6; index++) {
            String want = texts.get(index).getAsString();
            String got = log.get(index + 1).getAsJsonObject().get("text").getAsString();
            check(want.equals(got), "第 " + (index + 1) + " 条正文与 /api/quest 逐条对齐：期望「" + want + "」实际「" + got + "」");
        }
        // 图在第 5 条正文上（"任务 #15 已完成"），第 6 条（领取）没有图
        JsonObject fifth = log.get(5).getAsJsonObject();
        check(fifth.getAsJsonArray("images") != null && fifth.getAsJsonArray("images").size() == 1
                        && file.equals(fifth.getAsJsonArray("images").get(0).getAsString()),
                "图挂在第 5 条正文上（先文字后图同一个气泡）：" + fifth);
        check(!log.get(6).getAsJsonObject().has("images"), "第 6 条（领取）没有图");
        check(!log.get(1).getAsJsonObject().has("images") && !log.get(2).getAsJsonObject().has("images")
                        && !log.get(3).getAsJsonObject().has("images") && !log.get(4).getAsJsonObject().has("images"),
                "前 4 条都没有图（图没有撒到别的条目上）");

        // 整条回执重放三次（等价于页面补拉 / 重试）：一条都不许重复
        for (int round = 0; round < 3; round++) bot.observeQuestForTests(capture);
        check(new ChatLogStore(root).load(SCOPE).size() == 7, "整条回执重放 3 次：条数仍是 7（" + new ChatLogStore(root).load(SCOPE).size() + "）");
        JsonObject again = bot.webQuest(capture.number(), SCOPE);
        check(again.getAsJsonArray("texts").size() == 6, "重放之后 /api/quest 的 texts 仍是 6 条");
    }

    // ---------------------------------------------------------------- 3) 并发：页面整组 push ↔ 服务端 append

    private static void concurrentPushAndAppend(Path root) throws Exception {
        ChatLogStore store = new ChatLogStore(root);
        int baseline = store.load(SCOPE).size();

        // ① 页面拿着一份**旧快照**（只有用户那条）整组 push：服务端 append 过的条目必须找回来
        JsonArray page = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("text", "页面自己的话");
        page.add(user);
        store.save(SCOPE, page);
        JsonArray afterPush = store.load(SCOPE);
        check(afterPush.size() == baseline + 1, "页面整组覆盖写之后：服务端 append 的条目一条没丢，另加了页面那条（"
                + afterPush.size() + "）：" + afterPush.size());

        // ② 交替并发：一个线程整组 push、另一个线程 append
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        final int rounds = 20;
        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (int round = 0; round < rounds; round++) {
                    JsonArray fresh = new JsonArray();
                    JsonObject line = new JsonObject();
                    line.addProperty("role", "user");
                    line.addProperty("text", "并发第 " + round + " 轮");
                    fresh.add(line);
                    store.save(SCOPE, fresh);
                }
                return null;
            } catch (Throwable error) { throw new RuntimeException(error); }
        }));
        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (int round = 0; round < rounds; round++) {
                    store.append(SCOPE, List.of(new ChatLogStore.Append("quest:91" + round + ":1", "并发回执 " + round, List.of())));
                }
                return null;
            } catch (Throwable error) { throw new RuntimeException(error); }
        }));
        start.countDown();
        boolean failed = false;
        for (Future<?> future : futures) {
            try { future.get(120, TimeUnit.SECONDS); } catch (Exception error) { failed = true; }
        }
        pool.shutdown();
        check(!failed, "push 与 append 交替 " + rounds + " 轮：两边都没抛");

        JsonArray raced = store.load(SCOPE);
        long receipts = 0;
        for (JsonElement node : raced)
            if (node.getAsJsonObject().get("text").getAsString().startsWith("并发回执 ")) receipts++;
        check(receipts >= 1, "交替之后服务端 append 的条目至少留下一条（不因为页面 push 全丢）：" + receipts);
        check(raced.size() <= 200, "交替之后条数仍在 200 条窗口内：" + raced.size());
        int before = raced.size();
        for (int round = 0; round < rounds; round++) {
            store.append(SCOPE, List.of(new ChatLogStore.Append("quest:91" + round + ":1", "并发回执 " + round, List.of())));
        }
        check(store.load(SCOPE).size() == before, "重放全部并发回执：条数不变（" + before + "）");
    }

    // ---------------------------------------------------------------- 4) 真 HTTP

    private static void httpPath(Path root, Bot bot) throws Exception {
        try (WebUiServer server = new WebUiServer(new Settings(root), bot)) {
            server.start();
            String base = "http://127.0.0.1:" + server.actualPort();
            JsonObject command = new JsonObject();
            command.addProperty("command", ".help");
            command.addProperty("scope", SCOPE);
            JsonObject accepted = post(base + "/api/command", command);
            check(accepted.has("quest"), "控制台指令被受理：" + accepted);
            int quest = accepted.get("quest").getAsInt();
            JsonArray questTexts = null;
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline) {
                JsonObject detail = post(base + "/api/quest", scoped("id", quest));
                if (detail.has("texts") && !detail.getAsJsonArray("texts").isEmpty()) { questTexts = detail.getAsJsonArray("texts"); break; }
                Thread.sleep(100);
            }
            check(questTexts != null && questTexts.size() > 0, "回执 #" + quest + " 有正文");

            JsonArray entries = post(base + "/api/chat/log", scoped(null, 0)).getAsJsonArray("entries");
            int matched = 0;
            for (JsonElement node : questTexts) {
                String want = node.getAsString();
                for (JsonElement entryNode : entries) {
                    JsonObject entry = entryNode.getAsJsonObject();
                    if ("bot".equals(Json.str(entry, "role", "")) && want.equals(Json.str(entry, "text", ""))) { matched++; break; }
                }
            }
            check(matched == questTexts.size(), "真回执的每条正文都在 /api/chat/log 里（" + matched + "/" + questTexts.size() + "）");

            // 页面整组 push 一份只有用户那条的旧快照：服务端 append 的条目必须还在
            JsonArray page = new JsonArray();
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("text", "页面旧快照");
            page.add(user);
            JsonObject save = new JsonObject();
            save.addProperty("scope", SCOPE);
            save.add("entries", page);
            post(base + "/api/chat/log/save", save);
            JsonArray after = post(base + "/api/chat/log", scoped(null, 0)).getAsJsonArray("entries");
            check(after.size() >= entries.size(), "整组覆盖写之后服务端 append 的条目仍被补回（" + after.size() + " ≥ " + entries.size() + "）");

            post(base + "/api/chat/reset", scoped(null, 0));
            check(post(base + "/api/chat/log", scoped(null, 0)).getAsJsonArray("entries").size() == 0,
                    "/api/chat/reset 照旧清空正文");
        }
    }

    // ---------------------------------------------------------------- 桩与工具

    /** 生图频道的假客户端：改写返回一段更长的提示词、画面检查直接"通过"。 */
    static DeepSeekPrompts stubPrompts(Settings settings) {
        return DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.IMAGE, (body, key, timeout) -> {
            String user = "";
            try {
                JsonArray messages = body.getAsJsonArray("messages");
                user = messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
            } catch (Exception ignored) { /* 桩里解析失败就当空输入 */ }
            String answer;
            if (user.contains("ok") && user.contains("conflicts")) {          // 画面检查那次调用
                answer = "{\"ok\":true,\"conflicts\":[],\"added\":[]}";
            } else {                                                          // 改写那次调用
                answer = "{\"positive\":\"stub, a girl, red ribbon, night sky, cinematic light, detailed\","
                        + "\"negative\":\"blur, lowres\"}";
            }
            JsonObject choice = new JsonObject();
            choice.addProperty("finish_reason", "stop");
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", answer);
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject completion = new JsonObject();
            completion.add("choices", choices);
            return new DeepSeekPrompts.Response(200, completion.toString());
        });
    }

    /** SD 的本机桩：够 Bot 起来、够 /api/status 与 .help 用。绝不连真的 Stable Diffusion。 */
    static HttpServer sdStub() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = "{}";
            if (path.equals("/pixiko-bridge/v1/prompts"))
                body = "{\"positive\":\"base prompt\",\"negative\":\"bad\",\"source\":\"webui-live\",\"revision\":1,"
                        + "\"sampler_name\":\"Euler a\",\"styles\":[],\"width\":832,\"height\":1152,\"settings_initialized\":true}";
            else if (path.equals("/sdapi/v1/prompt-styles")) body = "[]";
            else if (path.equals("/sdapi/v1/loras")) body = "[]";
            else if (path.equals("/sdapi/v1/samplers")) body = "[{\"name\":\"Euler a\",\"aliases\":[]}]";
            else if (path.equals("/sdapi/v1/options")) body = "{\"sd_model_checkpoint\":\"Stub Model [aaaa]\"}";
            else if (path.equals("/sdapi/v1/sd-models")) body = "[{\"title\":\"Stub Model [aaaa]\"}]";
            else if (path.equals("/sdapi/v1/progress")) body = "{\"progress\":0,\"state\":{\"job\":\"\",\"job_count\":0}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    static JsonObject config(JsonObject sdConfig) {
        JsonObject config = new JsonObject();
        config.add("sd", sdConfig);
        config.addProperty("owner_user_id", "10000001");
        return config;
    }

    private static JsonObject scoped(String key, int value) {
        JsonObject body = new JsonObject();
        body.addProperty("scope", SCOPE);
        if (key != null) body.addProperty(key, value);
        return body;
    }

    private static JsonObject post(String url, JsonObject body) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString(), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) throw new AssertionError("HTTP " + response.statusCode() + "：" + response.body());
        return Json.parse(response.body().isBlank() ? "{}" : response.body());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
}

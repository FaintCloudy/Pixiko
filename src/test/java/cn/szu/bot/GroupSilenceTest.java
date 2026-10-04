package cn.szu.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import cn.szu.bot.chat.ChatService;
import cn.szu.bot.sd.SdClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「群里没被点名就完全不回」这条契约的端到端回归：用真实的 {@link Bot} + 记录型 Sender，
 * 只用临时根目录，不碰真实 data，也不需要 QQ / 真实 SD / 真实 DeepSeek。
 *
 * <p>SD 与聊天模型都是 loopback HTTP mock：SD 侧桥接不可用（同"SD 没在跑"），
 * 聊天侧返回一份 join=true 的计划——第 ④ 个场景要靠它把用户拉进 30 分钟对话窗口。
 * mock 的 /chat/completions 请求数同时充当"有没有触发规划"的计数器：
 * 群聊没被点名的消息必须一次都不规划。
 */
public final class GroupSilenceTest {
    private static int assertions;
    /** 失效搜索结果引导文案里那句唯一的说明。 */
    private static final String GUIDANCE = "这次没有可用的 Civitai 搜索结果";
    /** 群聊里同一句话；群里没 @、没叫名字、也不在窗口里时必须一个字都不回。 */
    private static final String STALE = "下载 #3";

    public static void main(String[] args) throws Exception {
        try (Fixture f = new Fixture()) {
            silentWhenNotAddressed(f);
            answersWhenAddressed(f);
            answersInPrivate(f);
            answersInsideConversationWindow(f);
            silentWhileOthersChat(f);
        }
        System.out.println("GroupSilenceTest: " + assertions + " assertions passed（5 个场景）："
                + "①群聊未点名 0 出站/0 规划、②被 @ 或叫名字回引导、③私聊恒回、④窗口内未点名也回引导、⑤别人在聊仍 0 出站。");
    }

    /** ① 群聊 + 没 @ 没叫名字 + 「下载 #3」→ 0 次出站，而且完全不触发规划。 */
    private static void silentWhenNotAddressed(Fixture f) throws Exception {
        int before = f.llmRequests.get();
        f.bot.accept(f.groupEvent("800", "101", STALE));
        f.settle();
        equal(0, f.recorder.total(), "群聊没被点名：一条消息都不该发出");
        equal(before, f.llmRequests.get(), "群聊没被点名：不该触发规划（/chat/completions 调用次数不变）");
    }

    /** ② 群聊 + @ 机器人（at 段指向 self_id）或文本里叫了名字 → 收到那句引导（一次）。 */
    private static void answersWhenAddressed(Fixture f) throws Exception {
        int before = f.recorder.total(), planned = f.llmRequests.get();
        f.bot.accept(f.groupAtEvent("801", "102", STALE));
        f.awaitOutbound(before + 1, 5000);
        equal(before + 1, f.recorder.total(), "被 @ 之后应当恰好回一条");
        check(f.recorder.texts.get(f.recorder.texts.size() - 1).contains(GUIDANCE), "被 @ 时收到的是引导文案：" + f.recorder.texts);
        equal(planned, f.llmRequests.get(), "被 @ 的这句直接给引导，不该再调模型");

        f.bot.accept(f.groupEvent("802", "103", "小鸟，" + STALE));
        f.awaitOutbound(before + 2, 5000);
        equal(before + 2, f.recorder.total(), "叫了名字（小鸟）同样回一条");
        check(f.recorder.texts.get(f.recorder.texts.size() - 1).contains(GUIDANCE),
                "叫名字时收到的也是引导文案：" + f.recorder.texts);
        equal(planned, f.llmRequests.get(), "叫名字的这句同样不调模型");
    }

    /** ③ 私聊 + 同样的话 → 收到引导（私聊恒回）。 */
    private static void answersInPrivate(Fixture f) throws Exception {
        int before = f.recorder.total(), planned = f.llmRequests.get();
        f.bot.accept(f.privateEvent("104", STALE));
        f.awaitOutbound(before + 1, 5000);
        equal(before + 1, f.recorder.total(), "私聊应当回一条");
        check(f.recorder.texts.get(f.recorder.texts.size() - 1).contains(GUIDANCE), "私聊收到的也是引导文案：" + f.recorder.texts);
        equal(planned, f.llmRequests.get(), "私聊的引导同样不调模型");
    }

    /** ④ 群聊窗口内：先被 @ 一次让模型 join=true 进窗，之后不 @ 的「下载 #3」也要收到引导。 */
    private static void answersInsideConversationWindow(Fixture f) throws Exception {
        int before = f.recorder.total(), planned = f.llmRequests.get();
        JsonObject wake = f.groupEvent("803", "105", "小鸟，在吗");
        f.bot.accept(wake);
        f.awaitOutbound(before + 1, 5000);
        equal(before + 1, f.recorder.total(), "被点名先答一次（模型 join=true）");
        equal(planned + 1, f.llmRequests.get(), "被点名那条要走一次聊天规划");

        JsonObject followUp = f.groupEvent("803", "105", STALE);
        check(f.awaitWindow(followUp, STALE, 5000), "模型 join=true 之后，该用户应当进入 30 分钟对话窗口");

        f.bot.accept(followUp);
        f.awaitOutbound(before + 2, 5000);
        equal(before + 2, f.recorder.total(), "窗口内不再 @ 也应当回一条引导");
        check(f.recorder.texts.get(f.recorder.texts.size() - 1).contains(GUIDANCE),
                "窗口内未点名时收到的是引导文案：" + f.recorder.texts);
        equal(planned + 1, f.llmRequests.get(), "窗口内的这句由程序直接判定，不必再调模型");
    }

    /** ⑤ 群聊没 @ 但其他人在聊 → 依然是 0 次出站、0 次规划（不能只测一条消息）。 */
    private static void silentWhileOthersChat(Fixture f) throws Exception {
        int before = f.recorder.total(), planned = f.llmRequests.get();
        f.bot.accept(f.groupEvent("804", "106", STALE));
        f.bot.accept(f.groupEvent("804", "107", "今天中午吃什么好呢"));
        f.bot.accept(f.groupEvent("804", "106", "要不还是老地方"));
        f.bot.accept(f.groupEvent("804", "107", "下载 #2"));
        f.settle();
        equal(before, f.recorder.total(), "别人在群里聊天时，机器人一条消息都不该发出");
        equal(planned, f.llmRequests.get(), "别人在群里聊天时，一次规划都不该发生");
    }

    // ------------------------------------------------------------------ 夹具

    /** 记录型 Sender：把每一次 send / sendMap / sendRecord 记下来（文本也留正文）。 */
    private static final class Recorder implements Bot.Sender {
        final List<String> texts = new CopyOnWriteArrayList<>();
        final AtomicInteger sends = new AtomicInteger(), maps = new AtomicInteger(), records = new AtomicInteger();

        @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
            sends.incrementAndGet();
            texts.add(Bot.messageText(segments));
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> sendMap(JsonObject event, JsonArray segments) {
            maps.incrementAndGet();
            texts.add(Bot.messageText(segments));
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> sendRecord(JsonObject event, List<JsonArray> messages) {
            records.incrementAndGet();
            if (!messages.isEmpty()) texts.add(Bot.messageText(messages.get(0)));
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
            return CompletableFuture.failedFuture(new IOException("测试传输层不支持 " + action));
        }
        int total() { return sends.get() + maps.get() + records.get(); }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final Recorder recorder = new Recorder();
        final AtomicInteger llmRequests = new AtomicInteger();
        final Bot bot;
        private final HttpServer server;
        private final AtomicInteger ids = new AtomicInteger();

        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work")).toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            // SD：桥接不可用（等于"SD 没在跑"），生成接口永远给一张小 PNG，测试不依赖它。
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/ping", e -> { e.sendResponseHeaders(404, -1); e.close(); });
            server.createContext("/pixiko-bridge/v1/prompts", e -> { e.sendResponseHeaders(404, -1); e.close(); });
            server.createContext("/sdapi/v1/txt2img", e -> respond(e, 200, "{\"images\":[\"" + pngBase64() + "\"]}"));
            // 聊天模型：固定返回一份"进对话窗口"的计划。
            server.createContext("/chat/completions", e -> {
                llmRequests.incrementAndGet();
                try { e.getRequestBody().readAllBytes(); } catch (IOException ignored) { /* 正文只是计数 */ }
                respond(e, 200, planResponse());
            });
            server.start();
            int port = server.getAddress().getPort();
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + port);
            JsonObject chatApi = new JsonObject();
            chatApi.addProperty("api_base", "http://127.0.0.1:" + port);
            chatApi.addProperty("model", "test-model");
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            config.add("chat_api", chatApi);
            config.addProperty("owner_user_id", "10000");
            config.addProperty("gen_auto_get", false);
            Json.atomicWrite(root.resolve("config.json"), config);
            Files.createDirectories(root.resolve("data"));
            Files.writeString(root.resolve("data/deepseek-chat-api-key.txt"), "test-key", StandardCharsets.UTF_8);
            bot = new Bot(new Settings(root), new SdClient(root, sd), recorder);
        }

        /** 群聊文本消息。 */
        JsonObject groupEvent(String group, String user, String text) {
            JsonObject event = envelope("group", group, user);
            event.add("message", Maps.text(text));
            return event;
        }

        /** 群聊消息，正文前带一个指向机器人自己的 at 段（OneBot v11 的 message 数组写法）。 */
        JsonObject groupAtEvent(String group, String user, String text) {
            JsonObject event = envelope("group", group, user);
            JsonArray message = new JsonArray();
            JsonObject at = new JsonObject();
            at.addProperty("type", "at");
            JsonObject data = new JsonObject();
            data.addProperty("qq", "10000");
            at.add("data", data);
            message.add(at);
            for (JsonElement segment : Maps.text(text)) message.add(segment);
            event.add("message", message);
            return event;
        }

        JsonObject privateEvent(String user, String text) {
            JsonObject event = envelope("private", "", user);
            event.add("message", Maps.text(text));
            return event;
        }

        private JsonObject envelope(String type, String group, String user) {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", type);
            event.addProperty("self_id", "10000");
            if (!group.isEmpty()) event.addProperty("group_id", group);
            event.addProperty("user_id", user);
            event.addProperty("message_id", ids.incrementAndGet());
            return event;
        }

        /** 等出站条数达到 expected（聊天回复是异步的）。 */
        void awaitOutbound(int expected, long millis) throws Exception {
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                if (recorder.total() >= expected) return;
                Thread.sleep(10);
            }
            throw new AssertionError("等待第 " + expected + " 条出站消息超时（实际 " + recorder.total() + " 条）：" + recorder.texts);
        }

        /** 给异步链路一点时间：用来断言"确实什么都没发生"。 */
        void settle() throws InterruptedException { Thread.sleep(500); }

        /**
         * 等这个用户真的进入对话窗口（只读判定，见 {@link ChatService#addressedOrContinuing}）。
         * Bot 的聊天服务是私有的，测试用反射拿它来读这条闸门，不改任何状态。
         */
        boolean awaitWindow(JsonObject event, String text, long millis) throws Exception {
            Field field = Bot.class.getDeclaredField("chat");
            field.setAccessible(true);
            ChatService chat = (ChatService) field.get(bot);
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                if (chat.addressedOrContinuing(event, text)) return true;
                Thread.sleep(10);
            }
            return chat.addressedOrContinuing(event, text);
        }

        public void close() throws Exception {
            bot.close();
            server.stop(0);
            try (var walk = Files.walk(root)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    /** 聊天模型的固定回答：join=true，把被点名的人拉进 30 分钟对话窗口。 */
    private static String planResponse() {
        JsonObject plan = new JsonObject();
        plan.addProperty("reply", "嗯，在的哦。");
        plan.addProperty("execute", false);
        plan.add("commands", new JsonArray());
        plan.addProperty("search_query", "");
        plan.addProperty("interest", 90);
        plan.addProperty("join", true);
        plan.addProperty("join_reason", "对方在跟我说话");
        JsonObject message = new JsonObject();
        message.addProperty("content", plan.toString());
        JsonObject choice = new JsonObject();
        choice.addProperty("finish_reason", "stop");
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return response.toString();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static String pngBase64() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + "：expected=" + expected + ", actual=" + actual);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}

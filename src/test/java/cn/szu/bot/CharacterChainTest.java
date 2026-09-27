package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import cn.szu.bot.sd.SdClient;

/** The character-image chain: ask before downloading, and never download without the user's confirmation. */
public final class CharacterChainTest {
    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "character-chain-tests").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String route = exchange.getRequestURI().getPath();
            String result = "{}";
            int status = 200;
            try (exchange) {
                if (route.equals("/sdapi/v1/prompt-styles")) result = "[{\"name\":\"Amanogawa Saya (天之川沙夜) | NoobAI 5\",\"prompt\":\"saya base, military uniform\",\"negative_prompt\":\"bad\"}]";
                else if (route.equals("/sdapi/v1/loras")) result = "[]";
                else if (route.equals("/sdapi/v1/options")) result = "{\"sd_model_checkpoint\":\"Model A [aaaa]\"}";
                else if (route.equals("/pixiko-bridge/v1/prompts")) status = 404;
                else if (route.equals("/config")) result = "{\"components\":[]}";
                byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        JsonObject sd = new JsonObject();
        sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
        JsonObject config = new JsonObject();
        config.add("sd", sd);
        config.addProperty("owner_user_id", "123");
        Json.atomicWrite(root.resolve("config.json"), config);
        // 样式只属于机器人：/.char 只查本机样式库，先放一个与角色同名的样式。
        Files.createDirectories(root.resolve("data"));
        Json.atomicWrite(root.resolve("data/local-styles.json"), Json.parse(
                "{\"version\":1,\"styles\":[{\"name\":\"Amanogawa Saya (天之川沙夜) | NoobAI 5\","
                        + "\"positive\":\"saya base, military uniform\",\"negative\":\"bad\",\"updated_at\":\"\"}]}"));
        AtomicInteger downloads = new AtomicInteger();
        BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        Bot.LoraDownloader downloader = (url, stage, meter) -> {
            downloads.incrementAndGet();
            throw new IllegalStateException("下载不应在用户确认前发生");
        };
        try (Bot bot = new Bot(new Settings(root), new SdClient(root, sd), (event, segments) -> {
            replies.add(Bot.messageText(segments));
            return CompletableFuture.completedFuture(null);
        }, downloader)) {
            String asked = command(bot, replies, ".char 不存在的角色XYZ");
            check(asked.contains("需要我去 Civitai") && asked.contains("不存在的角色XYZ"), "the bot asks before downloading: " + asked);
            check(downloads.get() == 0, "asking must not download anything");

            String searched = command(bot, replies, ".char download");
            check(searched.contains("CivitaI 搜索") || searched.contains("Civitai 搜索"), "confirmation starts the search: " + searched);
            check(downloads.get() == 0, "a search is not a download");

            String again = command(bot, replies, ".char download");
            check(again.contains("还没有待处理的角色查询"), "a spent confirmation asks for the character again: " + again);

            command(bot, replies, ".char 锦亚澄");
            // A chosen candidate can be applied, adjusted and generated in one deterministic step.
            String listed = command(bot, replies, ".char 天之川沙夜");
            check(listed.contains("样式：Amanogawa Saya (天之川沙夜) | NoobAI 5") && listed.contains(".char apply #编号"), "candidates are tagged and the one-step form is offered: " + listed);
            String applied = command(bot, replies, ".char apply #1");
            check(applied.contains("开始按顺序执行") && applied.contains(".style load"), "the chain starts with the style base: " + applied);
            String cancelled = command(bot, replies, ".char cancel");
            check(cancelled.contains("不下载"), "cancelling is acknowledged: " + cancelled);
            String afterCancel = command(bot, replies, ".char download");
            check(afterCancel.contains("还没有待处理的角色查询") && downloads.get() == 0, "a cancelled chain cannot download: " + afterCancel);
        } finally {
            server.stop(0);
        }
        System.out.println("CharacterChainTest PASS: ask -> confirm -> search, cancel, and never download without confirmation");
    }

    private static String command(Bot bot, BlockingQueue<String> replies, String text) throws Exception {
        JsonObject event = new JsonObject();
        event.addProperty("post_type", "message");
        event.addProperty("message_type", "private");
        event.addProperty("self_id", 777);
        event.addProperty("user_id", 123);
        event.addProperty("message_id", System.nanoTime());
        event.add("message", Maps.text(text));
        replies.clear();
        bot.accept(event);
        String reply = replies.poll(7, TimeUnit.SECONDS);
        check(reply != null, "reply for " + text);
        // Let asynchronous follow-ups (for example a bounded LoRA search) finish before the next step.
        while (replies.poll(600, TimeUnit.MILLISECONDS) != null) { }
        return reply;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

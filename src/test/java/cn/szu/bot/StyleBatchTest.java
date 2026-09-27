package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import cn.szu.bot.sd.SdClient;

/** Numbered/range batch style rename and delete, reproducing the 2026-09-18 request. */
public final class StyleBatchTest {
    private static final List<String> STYLES = new ArrayList<>(List.of(
            "篠森よもぎ", "篠森よもぎ 2", "锦亚澄", "锦亚澄 2", "天之川沙夜", "待删除 A", "待删除 B"));

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "style-batch-tests").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String route = exchange.getRequestURI().getPath();
            String result = "{}";
            int status = 200;
            try (exchange) {
                if (route.equals("/sdapi/v1/prompt-styles")) {
                    JsonArray array = new JsonArray();
                    synchronized (STYLES) {
                        for (String name : STYLES) {
                            JsonObject item = new JsonObject();
                            item.addProperty("name", name);
                            item.addProperty("prompt", name + " prompt");
                            item.addProperty("negative_prompt", "bad");
                            array.add(item);
                        }
                    }
                    result = array.toString();
                } else if (route.equals("/pixiko-bridge/v1/styles/rename") || route.equals("/pixiko-bridge/v1/styles/delete")) {
                    JsonObject body = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    String name = body.get("name").getAsString();
                    synchronized (STYLES) {
                        int index = STYLES.indexOf(name);
                        if (index < 0) status = 400;
                        else if (route.endsWith("rename")) {
                            String target = body.get("new_name").getAsString();
                            STYLES.set(index, target);
                            result = "{\"name\":\"" + target + "\",\"renamed\":true}";
                        } else {
                            STYLES.remove(index);
                            result = "{\"name\":\"" + name + "\",\"deleted\":true}";
                        }
                    }
                } else if (route.equals("/pixiko-bridge/v1/prompts")) {
                    status = 404;
                } else if (route.equals("/sdapi/v1/options")) {
                    result = "{\"sd_model_checkpoint\":\"Model A [aaaa]\"}";
                } else if (route.equals("/config")) {
                    result = "{\"components\":[]}";
                }
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
        BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        try (Bot bot = new Bot(new Settings(root), new SdClient(root, sd), (event, segments) -> {
            replies.add(Bot.messageText(segments));
            return CompletableFuture.completedFuture(null);
        })) {
            // 样式现在只属于机器人：先把 WebUI 里的预设搬进本机样式库，再对这份库做批量改名/删除。
            check(command(bot, replies, ".style import webui").contains("导入 7 个"), "webui presets migrate into the bot library");
            command(bot, replies, ".style list");
            String renamed = command(bot, replies, ".style rename #1-#4 篠森よもぎ");
            check(renamed.contains("篠森よもぎ 1") && renamed.contains("篠森よもぎ 4"), "sequence rename: " + renamed);
            check(renamed.contains("完成：3 项") && renamed.contains("已跳过 1 项"), "already-correct names are skipped: " + renamed);
            command(bot, replies, ".style list");
            String deleted = command(bot, replies, ".style delete #6-#7");
            check(deleted.contains("完成：2 项"), "batch delete: " + deleted);
            List<String> local = localNames(root);
            check(!local.contains("待删除 A") && !local.contains("待删除 B"), "deleted styles are gone: " + local);
            check(local.contains("篠森よもぎ 4") && !local.contains("锦亚澄 2"), "renamed in place, others untouched: " + local);
            check(local.contains("天之川沙夜"), "unrelated style preserved: " + local);
            command(bot, replies, ".style list");
            String batch = command(bot, replies, ".batch .style rename #1 篠森よもぎ 甲 ; .style delete #2");
            StringBuilder merged = new StringBuilder(batch);
            for (String extra = replies.poll(300, TimeUnit.MILLISECONDS); extra != null; extra = replies.poll(300, TimeUnit.MILLISECONDS)) merged.append("\n").append(extra);
            batch = merged.toString();
            check(batch.contains("批量执行完成：2/2 条") && batch.contains("【1】") && batch.contains("【2】"), "batch merges both receipts into one message: " + batch);
            local = localNames(root);
            check(local.contains("篠森よもぎ 甲"), "batch rename applied: " + local);
            check(!local.contains("篠森よもぎ 2"), "batch delete applied: " + local);
            String stopped = command(bot, replies, ".batch .style delete #99 ; .style delete #1");
            check(stopped.contains("已在失败处停止"), "batch stops at the first failure: " + stopped);
            String nested = command(bot, replies, ".batch .batch .style list");
            check(nested.contains("不能再嵌套"), "nested batch is refused: " + nested);            String outOfRange = command(bot, replies, ".style delete #99");
            check(outOfRange.contains("编号无效"), "out-of-range numbering is refused: " + outOfRange);
        } finally {
            server.stop(0);
        }
        System.out.println("StyleBatchTest PASS: numbered and range batch rename/delete with unchanged neighbours");
    }

    /** 机器人样式库里的名称（data/local-styles.json）。 */
    private static List<String> localNames(Path root) throws Exception {
        Path file = root.resolve("data/local-styles.json");
        if (!Files.isRegularFile(file)) return List.of();
        JsonObject data = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        List<String> names = new ArrayList<>();
        for (JsonElement item : data.getAsJsonArray("styles")) names.add(item.getAsJsonObject().get("name").getAsString());
        return names;
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
        return reply;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

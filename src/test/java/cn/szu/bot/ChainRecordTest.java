package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;

/**
 * 自然语言被拆成多步执行时，各步回执要合成一条聊天记录发送（不再逐条刷屏）；单步请求仍是一条普通回复。
 */
public final class ChainRecordTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "chain-record").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        try {
            JsonObject sdConfig = new JsonObject();
            sdConfig.addProperty("base_url", "http://127.0.0.1:9/");
            JsonObject config = new JsonObject();
            config.add("sd", sdConfig);
            Json.atomicWrite(root.resolve("config.json"), config);
            List<String> plain = new CopyOnWriteArrayList<>();
            List<List<String>> records = new CopyOnWriteArrayList<>();
            Bot.Sender sender = new Bot.Sender() {
                @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                    plain.add(Bot.messageText(segments));
                    return CompletableFuture.completedFuture(null);
                }
                @Override public CompletableFuture<Void> sendRecord(JsonObject event, List<JsonArray> messages) {
                    List<String> texts = new ArrayList<>();
                    for (JsonArray message : messages) texts.add(Bot.messageText(message));
                    records.add(texts);
                    return CompletableFuture.completedFuture(null);
                }
                @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
                    return CompletableFuture.completedFuture(new JsonObject());
                }
            };
            try (Bot bot = new Bot(new Settings(root), new SdClient(root, sdConfig), sender)) {
                JsonObject event = new JsonObject();
                event.addProperty("post_type", "message"); event.addProperty("message_type", "private");
                event.addProperty("self_id", 1); event.addProperty("user_id", 456); event.addProperty("message_id", 1);

                // 两步链路：所有回执合成一条聊天记录。
                bot.executeChatCommands(event, List.of(".prompt set base, red hair", ".prompt add blue_eyes"), new JsonObject());
                check(records.size() == 1, "多步执行只发送一条聊天记录（实际 " + records.size() + " 条）");
                check(plain.isEmpty(), "多步执行不再逐条刷屏：" + plain);
                List<String> record = records.get(0);
                check(record.get(0).contains("多步执行完成：2/2 条") && record.get(0).contains("全部成功"),
                        "聊天记录首条是执行总结：" + record.get(0));
                check(record.size() >= 3 && record.get(1).startsWith("【1】.prompt set") && record.get(2).startsWith("【2】.prompt add"),
                        "每步各自一个消息节点：" + record);
                check(record.get(1).contains("正向 prompt 已更新") && record.get(2).contains("已添加：blue_eyes"),
                        "每步的回执跟着自己的命令：" + record);
                check(new UserPromptStore(root).prompts("456").positive().equals("base, red hair, blue_eyes"),
                        "两步都真的执行了：" + new UserPromptStore(root).prompts("456").positive());

                // 单步请求仍旧是一条普通回复，不额外套聊天记录。
                plain.clear(); records.clear();
                bot.executeChatCommands(event, List.of(".prompt remove blue_eyes"), new JsonObject());
                check(records.isEmpty() && plain.size() == 1 && plain.get(0).contains("已删除：blue_eyes"),
                        "单步请求保持普通回复：" + plain + " / 记录 " + records);

                // 中途被拒绝（这里是非 owner 用 .map set）时也合成一条记录，并如实说明后续步骤没执行。
                plain.clear(); records.clear();
                bot.executeChatCommands(event, List.of(".prompt set only", ".map set yh /nonexistent-path"), new JsonObject());
                check(records.size() == 1, "被拒绝的链路同样只发一条记录：" + records + " / 单条 " + plain);
                String failureText = String.join("\n", records.get(0));
                check(records.get(0).get(0).contains("其余步骤未执行"), "总结如实说明后续步骤未执行：" + records.get(0).get(0));
                check(failureText.contains("仅 owner"), "失败原因出现在同一条记录里：" + failureText);
                check(plain.isEmpty(), "被拒绝时不额外发单条消息：" + plain);
            }
            System.out.println("ChainRecordTest: " + checks + " assertions passed: 多步回执合成一条聊天记录");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
}

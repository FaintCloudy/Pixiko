package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;

/**
 * v2（词库约束版）链路的零件回归：DeepSeek 改写把"草地/喘气/行军"这类要求换成词库外的词时的补齐判定、
 * 词库外新词的过滤、以及仍然保留在代码里的 `.infix filter` 开关。
 *
 * <p>当前线上 `.infix` 走 v1 自由改写（默认不查词库，见 backup\infix-v2-strict-*），这些方法不再被主流程
 * 调用，但仍然逐条验证，方便后续把两版整合回来时不带病上线。
 */
public final class InfixBackfillTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "infix-backfill").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        try {
            Path dictionary = Path.of(".").toAbsolutePath().normalize();
            // 用户在群里真正说过的那句话：三个要求里只有一个（军装）曾被改写命中。
            Set<String> allowed = Bot.vocabulary(dictionary, new SdClient.Prompts("", "", "test"));
            String request = "将地点切换为草地，服饰切换为军装，女性喘着气在艰苦行军";
            List<String> missing = Bot.missingRequestedTags(request, "1girl, solo", allowed);
            check(missing.contains("grass"), "草地 → grass：" + missing);
            check(missing.contains("heavy_breathing"), "喘着气 → heavy_breathing：" + missing);
            check(missing.contains("walking"), "行军 → walking：" + missing);
            check(missing.contains("exhausted"), "艰苦 → exhausted：" + missing);
            check(missing.contains("military_uniform"), "军装 → military_uniform：" + missing);
            check(missing.size() == 5, "不多不少补 5 个词条：" + missing);
            check(Bot.missingRequestedTags(request, "1girl, solo, grass, heavy_breathing, walking, exhausted, military_uniform",
                    allowed).isEmpty(), "已经具备时不再重复补齐");
            // 被替换的对象不能又被补回来，否则与"换成"的要求自相矛盾。
            check(Bot.missingRequestedTags("把校服换成军装", "1girl, solo", allowed).equals(List.of("military_uniform")),
                    "把校服换成军装：只补 military_uniform，不补 school_uniform");
            check(Bot.missingRequestedTags("将森林改为草地", "1girl, solo", allowed).equals(List.of("grass")),
                    "将森林改为草地：只补 grass，不补 forest");
            check(Bot.missingRequestedTags("去掉森林", "1girl, solo", allowed).isEmpty(),
                    "去掉森林：不补 forest");
            check(Bot.missingRequestedTags("草地上穿军装奔跑", "1girl, solo", allowed).contains("grass")
                            && Bot.missingRequestedTags("草地上穿军装奔跑", "1girl, solo", allowed).contains("running"),
                    "普通添加语序不受替换规则影响");
            botLevelBackfill(root);
            filterSwitch(root);
            System.out.println("InfixBackfillTest: " + checks + " assertions passed: 词条补齐与替换识别（v2 零件）、消息段事件不再崩溃、词库约束开关（默认关闭）");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    /** 真实的群事件里 message 是消息段数组：补齐逻辑必须照常工作，而不是抛 UnsupportedOperationException。 */
    private static void botLevelBackfill(Path root) throws Exception {
        JsonObject sdConfig = new JsonObject();
        sdConfig.addProperty("base_url", "http://127.0.0.1:9/");
        JsonObject config = new JsonObject();
        config.add("sd", sdConfig);
        Json.atomicWrite(root.resolve("config.json"), config);
        Files.createDirectories(root.resolve("data"));
        Files.copy(Path.of("data/prompt-tags.txt"), root.resolve("data/prompt-tags.txt"));
        List<JsonArray> sent = new CopyOnWriteArrayList<>();
        Bot.Sender sender = new Bot.Sender() {
            @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                sent.add(segments.deepCopy());
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }
        };
        try (Bot bot = new Bot(new Settings(root), new SdClient(root, sdConfig), sender)) {
            String command = ".infix 将地点切换为草地，服饰切换为军装，女性喘着气在艰苦行军";
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message"); event.addProperty("message_type", "group");
            event.addProperty("self_id", 1); event.addProperty("group_id", 999); event.addProperty("user_id", 456);
            event.addProperty("message_id", 1);
            // Array-shaped message segments, exactly like QQ/NapCat sends them.
            JsonObject segment = new JsonObject(); segment.addProperty("type", "text");
            JsonObject data = new JsonObject(); data.addProperty("text", command); segment.add("data", data);
            JsonArray message = new JsonArray(); message.add(segment);
            event.add("message", message);
            bot.ensureRequestedTags(event, List.of(command));
            SdClient.Prompts prompts = new UserPromptStore(root).prompts("456");
            for (String tag : List.of("grass", "heavy_breathing", "walking", "exhausted", "military_uniform"))
                check(prompts.positive().contains(tag), "补齐后 prompt 含 " + tag + "：" + prompts.positive());
            check(sent.stream().anyMatch(segments -> Bot.messageText(segments).contains("已补充你要求的标准词条")),
                    "补齐结果在回执中说明");
            // 第二次补齐是空操作：不会重复追加，也不会再回复。
            sent.clear();
            bot.ensureRequestedTags(event, List.of(command));
            check(new UserPromptStore(root).prompts("456").positive().equals(prompts.positive()), "重复补齐不改变 prompt");
            check(sent.isEmpty(), "没有缺失时不回复");
            // ".infix filter on/off" 是 owner/admin 开关（默认关闭＝自由改写）；普通成员只能查询状态。
            sent.clear();
            bot.accept(groupEvent(".infix filter", "456"));
            check(last(sent).contains("标准词库约束（本会话）：关闭") && last(sent).contains("filter on"),
                    "成员可以查看约束状态：" + last(sent));
            sent.clear();
            bot.accept(groupEvent(".infix filter on", "456"));
            check(last(sent).contains("仅 owner 或 admin"), "成员不能改动词库约束：" + last(sent));
            sent.clear();
            bot.accept(groupEvent(".infix filter on", "10000001"));
            check(last(sent).contains("已开启") && new Settings(root).infixFilterEnabled("1:group:999"),
                    "owner 可以开启词库约束：" + last(sent));
            sent.clear();
            bot.accept(groupEvent(".infix filter off", "10000001"));
            check(last(sent).contains("已关闭") && !new Settings(root).infixFilterEnabled("1:group:999"),
                    "owner 可以重新关闭词库约束：" + last(sent));
        }
    }

    private static String last(List<JsonArray> sent) {
        return sent.isEmpty() ? "" : Bot.messageText(sent.get(sent.size() - 1));
    }

    /** 群消息事件，message 使用真实的消息段数组形状。 */
    static JsonObject groupEvent(String text, String userId) {
        JsonObject event = new JsonObject();
        event.addProperty("post_type", "message"); event.addProperty("message_type", "group");
        event.addProperty("self_id", 1); event.addProperty("group_id", 999); event.addProperty("user_id", userId);
        event.addProperty("message_id", Math.abs(text.hashCode()));
        JsonObject segment = new JsonObject(); segment.addProperty("type", "text");
        JsonObject data = new JsonObject(); data.addProperty("text", text); segment.add("data", data);
        JsonArray message = new JsonArray(); message.add(segment);
        event.add("message", message);
        return event;
    }

    /** 标准词库约束是可切换的会话开关（默认关闭＝自由改写），并且切换只写本会话、不动其他配置。 */
    private static void filterSwitch(Path root) throws Exception {
        Set<String> allowed = Set.of("1girl", "grass");
        cn.szu.bot.chat.DeepSeekPrompts.Result invented =
                new cn.szu.bot.chat.DeepSeekPrompts.Result("1girl, sunbeam_filter, 中文词", "");
        Bot.InfixFilter strict = Bot.filterInfixVocabulary(allowed, invented);
        check(strict.rejected().size() == 2 && !strict.result().positive().contains("sunbeam_filter"),
                "开启时词库外新词被忽略并列出：" + strict.rejected());
        Bot.InfixFilter relaxed = Bot.filterInfixVocabulary(allowed, invented, true);
        check(relaxed.result().positive().contains("sunbeam_filter") && relaxed.rejected().isEmpty(),
                "关闭时保留词库外英文词条：" + relaxed.result().positive());
        check(Bot.outsideDictionary(allowed, relaxed.result()).size() == 2,
                "关闭时仍会在回执中列出保留的词库外新词：" + Bot.outsideDictionary(allowed, relaxed.result()));
        String conversation = "bot:group:999";
        Settings settings = new Settings(root);
        check(!settings.infixFilterEnabled(conversation), "词库约束默认关闭（.infix 默认自由改写）");
        boolean sdKept = Files.readString(root.resolve("config.json")).contains("\"sd\"");
        settings.setInfixFilterEnabled(conversation, true);
        check(settings.infixFilterEnabled(conversation), "可以开启词库约束");
        JsonObject stored = Json.parse(Files.readString(root.resolve("config.json")));
        check(sdKept && stored.has("sd"), "切换词库约束不会丢掉其他配置");
        check(stored.getAsJsonObject("infix").getAsJsonArray("filter_enabled_conversations").size() == 1
                        && new Settings(root).infixFilterEnabled(conversation),
                "开启状态只针对本会话并已持久化");
        settings.setInfixFilterEnabled(conversation, false);
        check(!settings.infixFilterEnabled(conversation), "可以重新关闭");
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
}

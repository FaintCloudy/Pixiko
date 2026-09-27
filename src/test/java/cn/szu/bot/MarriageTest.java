package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 今日老婆 / 强娶 / 离婚 的规则：每群独立、每日一次、只能强娶未婚配者。 */
public final class MarriageTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path parent = Path.of(System.getProperty("bot.test.work", "work"), "marriage-tests");
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "case-");
        Marriage store = new Marriage(root);
        AtomicInteger failures = new AtomicInteger();
        try {
            store.recordDraw("g1", "100", "200");
            check(store.todayDraw("g1", "100").equals("200"), "今日老婆抽取被记录", failures);
            check(store.todayDraw("g2", "100").isEmpty(), "另一个群的数据互相独立", failures);

            check(store.forceMarry("g1", "100", "200").ok(), "首次强娶成功", failures);
            check(store.partnerOf("g1", "100").equals("200") && store.partnerOf("g1", "200").equals("100"),
                    "婚配关系双向可查", failures);
            Marriage.Outcome again = store.forceMarry("g1", "100", "300");
            check(!again.ok() && again.reason().contains("已经强娶过"), "同一人每天只能强娶一次：" + again.reason(), failures);
            Marriage.Outcome taken = store.forceMarry("g1", "300", "200");
            check(!taken.ok() && taken.reason().contains("已经和"), "只能强娶未婚配者：" + taken.reason(), failures);
            check(!store.forceMarry("g1", "300", "300").ok(), "不能强娶自己", failures);
            check(store.forceMarry("g2", "300", "200").ok(), "另一个群仍可正常强娶（数据独立）", failures);

            Marriage.Outcome divorce = store.divorce("g1", "100");
            check(divorce.ok() && store.partnerOf("g1", "100").isEmpty(), "离婚解除婚配", failures);
            Marriage.Outcome twice = store.divorce("g1", "100");
            check(!twice.ok() && twice.reason().contains("已经离过"), "每天只能离婚一次：" + twice.reason(), failures);
            check(!store.divorce("g1", "999").ok(), "没有婚配时报错而不是静默成功", failures);
            check(store.couples("g1").isEmpty() && store.couples("g2").size() == 1, "群之间的婚配列表互不影响", failures);
            check(Marriage.avatarUrl("100").contains("nk=100"), "头像地址带上 QQ 号", failures);
            check(Marriage.today().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"), "日期格式为本地日期", failures);
            botLevelChecks(root, failures);

            System.out.println("MarriageTest: " + checks + " assertions passed"
                    + (failures.get() == 0 ? "：每群独立、每日限制、只能强娶未婚配者" : "，失败 " + failures.get()));
            if (failures.get() > 0) System.exit(1);
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    /** 端到端：走真实的指令分发与回执拼装（只把 QQ 传输换成假实现）。 */
    private static void botLevelChecks(Path root, AtomicInteger failures) throws Exception {
        com.google.gson.JsonObject config = new com.google.gson.JsonObject();
        com.google.gson.JsonObject sdConfig = new com.google.gson.JsonObject();
        sdConfig.addProperty("base_url", "http://127.0.0.1:9/");
        config.add("sd", sdConfig);
        // 最短合法窗口（5 秒）：正常同意即时完成，过期用例只需等待 5 秒多。
        com.google.gson.JsonObject marriage = new com.google.gson.JsonObject();
        marriage.addProperty("proposal_seconds", 5);
        config.add("marriage", marriage);
        Json.atomicWrite(root.resolve("config.json"), config);
        JsonObject member = new JsonObject();
        member.addProperty("user_id", "222"); member.addProperty("nickname", "小明"); member.addProperty("card", "");
        JsonObject other = new JsonObject();
        other.addProperty("user_id", "444"); other.addProperty("nickname", "小红"); other.addProperty("card", "");
        JsonObject groupInfo = new JsonObject(); groupInfo.addProperty("group_name", "测试主群");
        java.util.List<JsonArray> sent = new java.util.ArrayList<>();
        Bot.Sender sender = new Bot.Sender() {
            @Override public java.util.concurrent.CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                sent.add(segments.deepCopy());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public java.util.concurrent.CompletableFuture<com.google.gson.JsonElement> callApi(String action, JsonObject params) {
                JsonElement data = switch (action) {
                    case "get_group_member_list" -> Json.GSON.toJsonTree(java.util.List.of(member));
                    case "get_group_info" -> groupInfo;
                    case "get_group_member_info" -> {
                        // Echo the requested account, so a lookup proves which member was resolved.
                        String qq = Json.str(params, "user_id", "");
                        JsonObject found = new JsonObject();
                        found.addProperty("user_id", qq);
                        found.addProperty("nickname", qq.equals("444") ? "小红" : qq.equals("222") ? "小明" : "群友" + qq);
                        found.addProperty("card", "");
                        yield found;
                    }
                    default -> new JsonObject();
                };
                return java.util.concurrent.CompletableFuture.completedFuture(data);
            }
        };
        try (Bot bot = new Bot(new Settings(root), new cn.szu.bot.sd.SdClient(root, sdConfig), sender)) {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message"); event.addProperty("message_type", "group");
            event.addProperty("self_id", 1); event.addProperty("group_id", 999); event.addProperty("user_id", 111);
            event.addProperty("message_id", 1); event.addProperty("message", ".jrlp");
            bot.accept(event);
            String joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
            check(joined.contains("测试主群"), "回执包含群名称：" + joined.substring(0, Math.min(120, joined.length())), failures);
            check(joined.contains("小明") && joined.contains("222"), "回执包含昵称与 QQ 号", failures);
            check(joined.contains("\"type\":\"image\"") && joined.contains("q1.qlogo.cn"), "回执包含头像图片段", failures);
            // 强娶后当天再次强娶必须被拒，且对象已婚配时也拒绝。
            sent.clear();
            JsonObject force = event.deepCopy();
            force.addProperty("message", ".强娶 222"); force.addProperty("message_id", 2);
            bot.accept(force);
            joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
            check(joined.contains("恭喜"), "强娶成功并给出回执：" + joined.substring(0, Math.min(120, joined.length())), failures);
            sent.clear();
            JsonObject again = event.deepCopy();
            again.addProperty("message", ".强娶 222"); again.addProperty("message_id", 3);
            bot.accept(again);
            joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
            check(joined.contains("强娶失败"), "重复强娶被拒绝：" + joined.substring(0, Math.min(120, joined.length())), failures);
            // @提及：at 段不会出现在纯文本里，目标必须直接从事件的消息段解析。
            sent.clear();
            JsonObject mentioned = event.deepCopy();
            mentioned.addProperty("user_id", 333); mentioned.addProperty("message_id", 4);
            mentioned.add("message", mentionMessage(".强娶 ", "444"));
            bot.accept(mentioned);
            joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
            check(joined.contains("恭喜") && joined.contains("小红") && joined.contains("444"),
                    "@提及也能强娶：" + joined.substring(0, Math.min(160, joined.length())), failures);
            sent.clear();
            JsonObject selfMention = event.deepCopy();
            selfMention.addProperty("user_id", 555); selfMention.addProperty("message_id", 5);
            selfMention.add("message", mentionMessage(".强娶 ", "1"));
            bot.accept(selfMention);
            joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
            check(joined.contains("用法") && !joined.contains("恭喜"), "@机器人自己不算目标：" + joined.substring(0, Math.min(120, joined.length())), failures);
            proposalChecks(bot, event, sent, root, failures);
        }
    }

    /**
     * 结婚：被邀请者必须在窗口内同意。这里把窗口配成 1 秒，既能测正常同意，也能在不等待 180 秒的
     * 前提下测过期失效。
     */
    private static void proposalChecks(Bot bot, JsonObject base, java.util.List<JsonArray> sent,
                                       Path root, AtomicInteger failures) throws Exception {
        String conversation = Bot.marriageKey(base);
        Marriage store = new Marriage(root);
        // 1) 求婚 → 被邀请者用自然语言"同意"（不带点）在窗口内接受。
        sent.clear();
        JsonObject propose = base.deepCopy();
        propose.addProperty("user_id", 555); propose.addProperty("message_id", 20);
        propose.add("message", mentionMessage(".结婚 ", "666"));
        bot.accept(propose);
        String joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
        check(joined.contains("求婚") && joined.contains("666") && joined.contains("秒内回复"),
                "求婚回执说明等待回应的秒数：" + joined.substring(0, Math.min(160, joined.length())), failures);
        check(store.proposalFor(conversation, "666") != null && store.proposalFor(conversation, "555") == null,
                "求婚记录按被邀请者保存", failures);
        sent.clear();
        JsonObject agree = base.deepCopy();
        agree.addProperty("user_id", 666); agree.addProperty("message_id", 21);
        agree.addProperty("message", "同意");
        bot.accept(agree);
        joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
        check(joined.contains("结婚") && joined.contains("555") && joined.contains("666"),
                "窗口内同意即结婚：" + joined.substring(0, Math.min(160, joined.length())), failures);
        check("555".equals(store.partnerOf(conversation, "666")) && "666".equals(store.partnerOf(conversation, "555")),
                "同群双方都能查到婚配关系（会话键不含 user_id）", failures);
        // 2) 超过窗口后同意必须失效。
        sent.clear();
        JsonObject late = base.deepCopy();
        late.addProperty("user_id", 777); late.addProperty("message_id", 22);
        late.addProperty("message", ".结婚 888");
        bot.accept(late);
        Thread.sleep(5200);
        sent.clear();
        JsonObject tooLate = base.deepCopy();
        tooLate.addProperty("user_id", 888); tooLate.addProperty("message_id", 23);
        tooLate.addProperty("message", ".同意");
        bot.accept(tooLate);
        joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
        check(joined.contains("失效") && store.partnerOf(conversation, "888").isEmpty(),
                "超过窗口的同意被拒绝：" + joined.substring(0, Math.min(160, joined.length())), failures);
        // 3) 拒绝后不会成婚，且不能再同意。
        sent.clear();
        JsonObject again = base.deepCopy();
        again.addProperty("user_id", 777); again.addProperty("message_id", 24);
        again.addProperty("message", ".结婚 888");
        bot.accept(again);
        sent.clear();
        JsonObject refuse = base.deepCopy();
        refuse.addProperty("user_id", 888); refuse.addProperty("message_id", 25);
        refuse.addProperty("message", ".拒绝");
        bot.accept(refuse);
        joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
        check(joined.contains("已拒绝") && store.partnerOf(conversation, "888").isEmpty(),
                "拒绝后不成婚：" + joined.substring(0, Math.min(160, joined.length())), failures);
        check(store.proposalFor(conversation, "888") == null, "拒绝后邀请被清除", failures);
        sent.clear();
        JsonObject nothing = base.deepCopy();
        nothing.addProperty("user_id", 888); nothing.addProperty("message_id", 26);
        nothing.addProperty("message", ".同意");
        bot.accept(nothing);
        joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
        check(joined.contains("同意失败") && joined.contains("没有等待"), "没有待回应求婚时同意失败：" + joined.substring(0, Math.min(120, joined.length())), failures);
        // 4) 已婚者不能求婚，也不能被求婚；自己不能向自己求婚。
        sent.clear();
        JsonObject married = base.deepCopy();
        married.addProperty("user_id", 555); married.addProperty("message_id", 27);
        married.addProperty("message", ".结婚 999");
        bot.accept(married);
        joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
        check(joined.contains("求婚失败") && joined.contains("先离婚"), "已婚者不能再求婚：" + joined.substring(0, Math.min(160, joined.length())), failures);
        sent.clear();
        JsonObject taken = base.deepCopy();
        taken.addProperty("user_id", 999); taken.addProperty("message_id", 28);
        taken.addProperty("message", ".结婚 222");
        bot.accept(taken);
        joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
        check(joined.contains("求婚失败") && joined.contains("已经和"), "不能向已婚者求婚：" + joined.substring(0, Math.min(160, joined.length())), failures);
        sent.clear();
        JsonObject self = base.deepCopy();
        self.addProperty("user_id", 999); self.addProperty("message_id", 29);
        self.addProperty("message", ".结婚 999");
        bot.accept(self);
        joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
        check(joined.contains("不能向自己求婚"), "不能向自己求婚：" + joined.substring(0, Math.min(120, joined.length())), failures);
        // 5) 没有参数的 .结婚 报告当前邀请状态（这里没有）。
        sent.clear();
        JsonObject status = base.deepCopy();
        status.addProperty("user_id", 999); status.addProperty("message_id", 30);
        status.addProperty("message", ".结婚");
        bot.accept(status);
        joined = sent.stream().map(JsonArray::toString).reduce("", (a, b) -> a + b);
        check(joined.contains("用法") && joined.contains("秒内回复 .同意"), "无参数时给出用法：" + joined.substring(0, Math.min(160, joined.length())), failures);
        // 6) 同一群里不同用户的会话键相同，私聊按用户各自独立。
        check("1:group:999".equals(conversation), "群会话键不含 user_id：" + conversation, failures);
        JsonObject privateEvent = base.deepCopy();
        privateEvent.addProperty("message_type", "private"); privateEvent.remove("group_id");
        privateEvent.addProperty("user_id", 777);
        check("1:private:777".equals(Bot.marriageKey(privateEvent)), "私聊按用户独立：" + Bot.marriageKey(privateEvent), failures);
    }

    /** One text segment plus one at segment, the shape NapCat sends for ".强娶 @某人". */
    private static JsonArray mentionMessage(String text, String qq) {
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        JsonObject textData = new JsonObject(); textData.addProperty("text", text); textPart.add("data", textData);
        JsonObject atPart = new JsonObject();
        atPart.addProperty("type", "at");
        JsonObject atData = new JsonObject(); atData.addProperty("qq", qq); atPart.add("data", atData);
        JsonArray message = new JsonArray(); message.add(textPart); message.add(atPart);
        return message;
    }

    private static void check(boolean condition, String detail, AtomicInteger failures) {
        checks++;
        if (!condition) { failures.incrementAndGet(); System.err.println("FAIL: " + detail); }
    }
}

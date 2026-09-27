package cn.szu.bot;

import cn.szu.bot.sd.SdClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 群禁言识别：被禁言（或全群禁言）期间不再尝试发送，避免"禁言后还硬发 → 一屏报错"。
 *
 * <p>状态机部分用可控时钟跑：通知（全员/自己/别人）、解除、到期、回查结果、长期禁言。
 * 机器人层用桩发送器跑完整链路：通知进来就静音、解除就恢复、发送失败后回查确认才静音。
 */
public final class MuteGuardTest {
    private static int checks;
    private static final AtomicInteger IDS = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "mute").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        try {
            guard();
            botLevel(root);
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
        System.out.println("MuteGuardTest: " + checks + " assertions passed：全员禁言/自己被禁言/别人禁言、解除与到期、"
                + "长期禁言、禁言期间不发送、解除后恢复、失败回查（不猜）、按群节流");
    }

    // ---------------------------------------------------------------- 状态机

    private static void guard() {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        MuteGuard guard = new MuteGuard(now::get);
        guard.selfId("10000");

        guard.observe(ban("999", "456", "ban", 600));
        check(!guard.muted("999"), "别人被禁言不影响机器人发送");

        guard.observe(ban("999", "10000", "ban", 600));
        check(guard.muted("999"), "机器人自己被禁言后进入静音");
        check(guard.describe("999").contains("机器人被禁言中"), "原因写清楚：" + guard.describe("999"));
        check(guard.describe("999").contains("还剩"), "带上剩余时间：" + guard.describe("999"));

        guard.observe(ban("999", "10000", "lift_ban", 0));
        check(!guard.muted("999"), "收到解除通知立刻恢复");

        guard.observe(ban("999", "0", "ban", 300));
        check(guard.muted("999") && guard.describe("999").contains("全群禁言中"), "全员禁言（user_id=0）同样算：" + guard.describe("999"));

        now.addAndGet(301_000);
        check(!guard.muted("999"), "时间到期自动恢复，不用等人解除");

        guard.observe(ban("999", "0", "ban", 0));
        check(guard.ban("999").until() - now.get() == MuteGuard.INDEFINITE_BAN_MILLIS, "时长为 0 按长期禁言记，靠解除通知与回查结束");
        guard.clear("999");
        check(!guard.muted("999"), "可以手动解除");
        check(guard.snapshot().isEmpty(), "解除后状态快照为空：" + guard.snapshot());

        guard.learned("999", now.get() + 120_000, "机器人被禁言中");
        check(guard.muted("999"), "回查确认禁言后进入静音");
        check(guard.snapshot().get(0).getAsJsonObject().get("remainingSeconds").getAsLong() == 120,
                "快照给出剩余秒数：" + guard.snapshot());
        guard.learned("999", 0, "");
        check(!guard.muted("999"), "回查显示已经能发了就恢复");

        guard.observe(ban("999", "0", "ban", 60));
        check(guard.skip("999", "消息：hi"), "禁言期间跳过发送");
        check(!guard.skip("777", "消息：hi"), "没禁言的群照常发送");
    }

    // ---------------------------------------------------------------- 机器人链路

    private static void botLevel(Path root) throws Exception {
        JsonObject sdConfig = new JsonObject();
        sdConfig.addProperty("base_url", "http://127.0.0.1:9/");
        JsonObject config = new JsonObject();
        config.add("sd", sdConfig);
        config.addProperty("owner_user_id", "10000");
        Json.atomicWrite(root.resolve("config.json"), config);

        List<JsonArray> sent = new CopyOnWriteArrayList<>();
        AtomicBoolean failNext = new AtomicBoolean(false);
        AtomicLong shutUp = new AtomicLong(0);
        AtomicInteger apiCalls = new AtomicInteger();
        Bot.Sender sender = new Bot.Sender() {
            @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                if (failNext.getAndSet(false))
                    return CompletableFuture.failedFuture(new java.io.IOException(
                            "QQ 发送失败（send_group_msg），retcode=1200：EventChecker Failed"));
                sent.add(segments.deepCopy());
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
                apiCalls.incrementAndGet();
                JsonObject result = new JsonObject();
                result.addProperty("group_id", 999);
                result.addProperty("user_id", 10000);
                result.addProperty("shut_up_timestamp", shutUp.get());
                return CompletableFuture.completedFuture(result);
            }
        };
        try (Bot bot = new Bot(new Settings(root), new SdClient(root, sdConfig), sender)) {
            // ① 禁言通知 → 连试都不试
            bot.accept(ban("999", "0", "ban", 600));
            check(bot.muteStatus().size() == 1, "状态里能看到禁言：" + bot.muteStatus());
            sent.clear();
            bot.accept(groupEvent(".help", "456"));
            check(sent.isEmpty(), "全群禁言期间不回消息（不再硬发）");

            // ② 解除通知 → 恢复
            bot.accept(ban("999", "0", "lift_ban", 0));
            sent.clear();
            bot.accept(groupEvent(".help", "456"));
            check(!sent.isEmpty(), "解除禁言后照常回复");

            // ③ 发送失败后回查：真的被禁言才静音（回查结果 shut_up_timestamp 在未来）
            shutUp.set(System.currentTimeMillis() / 1000 + 300);
            failNext.set(true);
            bot.accept(groupEvent(".help", "456"));
            waitFor(() -> bot.muteStatus().size() == 1, 2000);
            check(bot.muteStatus().size() == 1, "失败回查确认禁言后进入静音：" + bot.muteStatus());
            check(apiCalls.get() == 1, "只回查一次：" + apiCalls.get());
            sent.clear();
            bot.accept(groupEvent(".help", "456"));
            check(sent.isEmpty(), "回查确认之后不再尝试发送");

            // ④ 回查节流：一分钟内不再重复问 NapCat
            int before = apiCalls.get();
            bot.accept(ban("999", "0", "lift_ban", 0));
            failNext.set(true);
            bot.accept(groupEvent(".help", "456"));
            check(apiCalls.get() == before, "同一个群一分钟内不重复回查：" + apiCalls.get());

            // ⑤ 回查显示没被禁言 → 不静音（绝不因为一次失败就长期静音）
            shutUp.set(0);
            failNext.set(true);
            sent.clear();
            bot.accept(groupEvent(".help", "456", "888"));
            waitFor(() -> apiCalls.get() > before, 2000);
            check(bot.muteStatus().isEmpty(), "回查显示没禁言时不会静音：" + bot.muteStatus());
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long millis) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
    }

    /** OneBot v11 的群禁言通知。 */
    private static JsonObject ban(String group, String user, String subType, long duration) {
        JsonObject event = new JsonObject();
        event.addProperty("post_type", "notice");
        event.addProperty("notice_type", "group_ban");
        event.addProperty("sub_type", subType);
        event.addProperty("group_id", group);
        event.addProperty("user_id", user);
        event.addProperty("operator_id", "1");
        event.addProperty("duration", duration);
        event.addProperty("self_id", "10000");
        event.addProperty("time", System.currentTimeMillis() / 1000);
        return event;
    }

    private static JsonObject groupEvent(String text, String user) { return groupEvent(text, user, "999"); }

    private static JsonObject groupEvent(String text, String user, String group) {
        JsonObject event = new JsonObject();
        event.addProperty("post_type", "message");
        event.addProperty("message_type", "group");
        event.addProperty("self_id", "10000");
        event.addProperty("group_id", group);
        event.addProperty("user_id", user);
        event.addProperty("message_id", IDS.incrementAndGet());
        JsonObject segment = new JsonObject();
        segment.addProperty("type", "text");
        JsonObject data = new JsonObject();
        data.addProperty("text", text);
        segment.add("data", data);
        JsonArray message = new JsonArray();
        message.add(segment);
        event.add("message", message);
        return event;
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
}

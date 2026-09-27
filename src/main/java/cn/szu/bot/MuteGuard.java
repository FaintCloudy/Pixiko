package cn.szu.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 群禁言识别：机器人被禁言（或全群禁言）期间不再尝试发送，免得刷一屏注定失败的报错。
 *
 * <p>三个来源，按可靠度排序：
 * <ol>
 *   <li><b>群禁言通知</b>（OneBot v11 {@code notice_type=group_ban}）：最准，带时长。
 *       {@code user_id=0} 是全员禁言；{@code user_id} 等于机器人自己的 QQ 是机器人被单独禁言；
 *       {@code sub_type=lift_ban} 是解除。别人的禁言与机器人无关，直接忽略。</li>
 *   <li><b>发送失败后回查</b>：{@code get_group_member_info} 的 {@code shut_up_timestamp}
 *       （以及 {@code get_group_info} 里可能出现的全员禁言字段）。查到才封，查不到不猜。</li>
 *   <li>两个都拿不到时：失败照旧如实上报，<b>绝不因为一次失败就把这个群长期静音</b>。</li>
 * </ol>
 *
 * <p>解除途径同理有三条：收到 {@code lift_ban}、时间到期、禁言期间按分钟回查发现已经解除。
 * 所以即使漏收了解除通知，最多一分钟就会自己恢复。
 */
public final class MuteGuard {

    /** 回查也拿不到明确时间时，只静音这么久：够躲开这一阵连续报错，又不会误伤太久。 */
    static final long UNKNOWN_BAN_MILLIS = 60_000;
    /** 长期禁言（时长为 0）按这个时长记，靠回查与解除通知提前结束。 */
    static final long INDEFINITE_BAN_MILLIS = 6 * 60 * 60 * 1000L;
    /** 禁言期间每隔这么久回查一次，确认是不是已经解除（漏收通知也能自己恢复）。 */
    static final long RECHECK_MILLIS = 60_000;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 一个群的禁言状态：截止时间 + 人话原因。 */
    public record Ban(long until, String reason) { }

    private final Map<String, Ban> bans = new ConcurrentHashMap<>();
    /** 每个禁言窗口只把"跳过发送"的日志打一次，之后按条数汇总，避免自己刷屏。 */
    private final Map<String, AtomicInteger> skipped = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCheck = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private volatile String selfId = "";

    public MuteGuard() { this(System::currentTimeMillis); }
    /** 测试用：时间可以自己往前走。 */
    public MuteGuard(LongSupplier clock) { this.clock = clock; }

    /** 记下机器人的 QQ（探测"我被禁言"时要问的就是这个号）。 */
    public void selfId(String id) {
        if (id != null && !id.isBlank()) selfId = id.strip();
    }

    public String selfId() { return selfId; }

    /** 当前禁言状态；已经到期就顺手清掉。 */
    public Ban ban(String group) {
        if (group == null || group.isBlank()) return null;
        Ban current = bans.get(group);
        if (current == null) return null;
        if (current.until() <= clock.getAsLong()) { bans.remove(group); skipped.remove(group); return null; }
        return current;
    }

    public boolean muted(String group) { return ban(group) != null; }

    /** 禁言原因（没有则空串）。 */
    public String describe(String group) {
        Ban current = ban(group);
        if (current == null) return "";
        return current.reason() + "（到 " + CLOCK.format(Instant.ofEpochMilli(current.until()).atZone(ZoneId.systemDefault()))
                + "，还剩 " + Math.max(1, (current.until() - clock.getAsLong()) / 1000) + " 秒）";
    }

    /** 收到禁言通知就更新：这是最可靠的一条路。 */
    public void observe(JsonObject event) {
        if (!"notice".equals(Json.str(event, "post_type", ""))) return;
        if (!"group_ban".equals(Json.str(event, "notice_type", ""))) return;
        String group = Json.str(event, "group_id", "").strip();
        if (group.isEmpty()) return;
        boolean lifting = "lift_ban".equals(Json.str(event, "sub_type", ""));
        String target = Json.str(event, "user_id", "").strip();
        boolean everyone = target.isEmpty() || target.equals("0");
        String me = selfId;
        if (!everyone && (me.isEmpty() || !me.equals(target))) return;   // 别人被禁言，跟机器人无关
        if (lifting) {
            if (everyone || me.equals(target)) {
                if (bans.remove(group) != null) {
                    skipped.remove(group);
                    Log.info("群 " + group + " 禁言已解除，恢复发送。");
                }
            }
            return;
        }
        long millis = durationMillis(event);
        String reason = everyone ? "全群禁言中" : "机器人被禁言中";
        Ban previous = bans.get(group);
        bans.put(group, new Ban(clock.getAsLong() + millis, reason));
        skipped.remove(group);
        if (previous == null || previous.until() < clock.getAsLong())
            Log.warn("群 " + group + " 开始" + reason + "，到 " + CLOCK.format(Instant.ofEpochMilli(clock.getAsLong() + millis)
                    .atZone(ZoneId.systemDefault())) + " 为止不发送消息（期间收到的消息只记账，不尝试发送）。");
    }

    /** 回查确认后的结果（来自 get_group_member_info / get_group_info）。 */
    public void learned(String group, long untilMillis, String reason) {
        if (group == null || group.isBlank()) return;
        if (untilMillis <= clock.getAsLong()) {
            if (bans.remove(group) != null) Log.info("群 " + group + " 回查显示已解除禁言，恢复发送。");
            lastCheck.put(group, clock.getAsLong());
            return;
        }
        Ban previous = bans.put(group, new Ban(untilMillis, reason));
        lastCheck.put(group, clock.getAsLong());
        if (previous == null || previous.until() < clock.getAsLong())
            Log.warn("群 " + group + " 回查确认：" + reason + "，到 "
                    + CLOCK.format(Instant.ofEpochMilli(untilMillis).atZone(ZoneId.systemDefault())) + " 为止不发送消息。");
    }

    /** 一次发送失败之后，是否到了该回查的时间（避免每条都去问一次 NapCat）。 */
    public boolean shouldRecheck(String group) {
        Long last = lastCheck.get(group);
        return last == null || clock.getAsLong() - last >= RECHECK_MILLIS;
    }

    public void markChecked(String group) { lastCheck.put(group, clock.getAsLong()); }

    /**
     * 该不该跳过这次发送。跳过时只在这里记日志：第一次讲清楚，之后每 20 条汇总一次。
     *
     * @return true 表示禁言中，不要发送
     */
    public boolean skip(String group, String what) {
        if (!muted(group)) return false;
        AtomicInteger count = skipped.computeIfAbsent(group, key -> new AtomicInteger());
        int now = count.incrementAndGet();
        if (now == 1) Log.warn("群 " + group + " " + describe(group) + "：不发送这条" + what + "（禁言期间不再尝试发送）。");
        else if (now % 20 == 0) Log.warn("群 " + group + " " + describe(group) + "：已累计跳过 " + now + " 条消息。");
        return true;
    }

    /** 给网页看的只读状态。 */
    public JsonArray snapshot() {
        JsonArray result = new JsonArray();
        for (Map.Entry<String, Ban> entry : bans.entrySet()) {
            Ban current = ban(entry.getKey());
            if (current == null) continue;
            JsonObject item = new JsonObject();
            item.addProperty("group", entry.getKey());
            item.addProperty("reason", current.reason());
            item.addProperty("until", current.until());
            item.addProperty("untilText", CLOCK.format(Instant.ofEpochMilli(current.until()).atZone(ZoneId.systemDefault())));
            item.addProperty("remainingSeconds", Math.max(0, (current.until() - clock.getAsLong()) / 1000));
            result.add(item);
        }
        return result;
    }

    /** 解除某个群的静音（例如用户手动确认已经能发了）。 */
    public boolean clear(String group) {
        boolean removed = bans.remove(group) != null;
        skipped.remove(group);
        return removed;
    }

    /** 通知里的时长（秒）；0 或缺失按"长期"处理，靠解除通知与回查结束。 */
    static long durationMillis(JsonObject event) {
        long seconds = 0;
        JsonElement value = event.get("duration");
        if (value != null && value.isJsonPrimitive()) {
            try { seconds = value.getAsLong(); } catch (RuntimeException ignored) { seconds = 0; }
        }
        if (seconds <= 0) return INDEFINITE_BAN_MILLIS;
        return Math.min(seconds * 1000L, 24 * 60 * 60 * 1000L);
    }
}

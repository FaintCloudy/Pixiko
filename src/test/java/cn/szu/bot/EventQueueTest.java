package cn.szu.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import cn.szu.bot.sd.SdClient;
import cn.szu.bot.web.EventQueueStore;

/**
 * 事件队列（{@link EventQueueStore} + {@code Bot} 的入队落点）：服务端"入队"、前端"出队渲染"那条队列。
 *
 * <p>覆盖的用户要求（顺序与验收单一致）：
 * <ol>
 *   <li><b>入队幂等</b>：同一条回执重放 3 次 → 队列里只有一套条目（含经 {@code Bot.observeQuest} 的那条真路）；</li>
 *   <li><b>图片晚到</b>：先 message、后 patch → 1 条 message + 1 条 patch（target 指向它），图不重复入队；</li>
 *   <li><b>出队</b>：{@code after=0} 拿全部、{@code after=next} 再取为空且 {@code hasMore=false}、严格升序；</li>
 *   <li><b>ack 只进不退</b>（含"ack 比 latest 还大"夹到 latest）；</li>
 *   <li><b>多 scope 隔离</b>：A 的事件不会出现在 B 的队列里；</li>
 *   <li><b>裁剪</b>：{@code >5000} 条 + ack → 只裁已 ack 的、未 ack 一条不删、文件里有 trim 标记；</li>
 *   <li><b>持久化</b>：换一个 {@link EventQueueStore} 实例（等价于重启）→ nextSeq 与 items 连续、无重复无丢；</li>
 *   <li><b>未知 type</b>在服务端不报错（前端忽略未知 type 也不会崩）。</li>
 * </ol>
 *
 * <p>全程只用 {@code bot.test.work} 下的临时根目录与回环地址上的 SD 桩，<b>不碰</b> {@code F:\Bot}
 * 那台正在跑的机器人、不碰真 SD。
 */
public final class EventQueueTest {
    static int checks = 0;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError("FAIL: " + what); }

    /** 临时设备 scope（{@code dev-} + 12 位十六进制）—— 绝不是任何真实设备的账本。 */
    private static final String DEV_A = "dev-1a2b3c4d5e6f";
    private static final String DEV_B = "dev-9f8e7d6c5b4a";

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "event-queue").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        try {
            receiptIdempotent(root);
            lateImage(root);
            dequeue(root);
            ackMonotonic(root);
            scopeIsolation(root);
            trimming(root);
            persistence(root);
            unknownType(root);
            botWiring(root);
        } finally {
            TestCleanup.awaitQuiet(root, 5000);
            TestCleanup.deleteQuietly(root);
        }
        System.out.println("EventQueueTest: " + checks + " assertions passed：同一回执重放 3 次只有一套条目（含经 Bot.observeQuest 的真路）、"
                + "图片晚到只发 patch（target 指向原 message）且图不重复入队、出队严格升序且 after=next 再取为空、"
                + "ack 只进不退（超过 latest 夹到 latest）、多 scope 隔离、"
                + ">5000 条只裁已 ack 的连续前缀（未 ack 一条不删）且文件里有 trimmedUpTo、"
                + "换实例（模拟重启）后 nextSeq 与 items 连续无重复无丢、未知 type 不报错。");
    }

    // ---------------------------------------------------------------- 1) 入队幂等

    static void receiptIdempotent(Path root) {
        EventQueueStore store = new EventQueueStore(root);
        List<JsonArray> receipt = List.of(
                textGroup("正在通过 DeepSeek 智能修改你个人的提示词。"),
                textGroup("画面检查：通过"),
                textGroup("已加入生成队列，共 1 次生成。"));
        for (int round = 1; round <= 3; round++) store.appendReceipt(DEV_A, "15", receipt);
        JsonArray items = store.fetch(DEV_A, 0, 200).getAsJsonArray("items");
        check(items.size() == 3, "同一回执重放 3 次：队列里只有一套 3 条（实际 " + items.size() + "）");
        check("quest:15:1".equals(Json.str(items.get(0).getAsJsonObject(), "id", ""))
                        && "quest:15:3".equals(Json.str(items.get(2).getAsJsonObject(), "id", "")),
                "幂等键是 quest:<回执号>:<条内序号>：" + Json.str(items.get(0).getAsJsonObject(), "id", "") + " … "
                        + Json.str(items.get(2).getAsJsonObject(), "id", ""));
        check("message".equals(Json.str(items.get(0).getAsJsonObject(), "type", ""))
                        && "bot".equals(Json.str(items.get(0).getAsJsonObject(), "role", ""))
                        && Json.num(items.get(0).getAsJsonObject(), "at", 0L) > 0,
                "每项至少有 seq / id / type / at，回执正文的 role 是 bot");
    }

    // ---------------------------------------------------------------- 2) 图片晚到：先 message 后 patch

    static void lateImage(Path root) {
        EventQueueStore store = new EventQueueStore(root);
        // ① 先只有正文：入队一条 message（没有图）
        store.appendReceipt(DEV_B, "16", List.of(textGroup("图来啦，稍等。")));
        JsonObject first = store.fetch(DEV_B, 0, 200);
        check(first.getAsJsonArray("items").size() == 1
                        && first.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonArray("images").size() == 0,
                "先到的正文入队成 1 条没有图的 message");

        // ② 图后到：不改原条目，补一条 patch（target 指向那条 message）
        store.appendReceipt(DEV_B, "16", List.of(textGroup("图来啦，稍等。"), imageGroup("data/generated/late.png")));
        JsonObject page = store.fetch(DEV_B, 0, 200);
        JsonArray items = page.getAsJsonArray("items");
        check(items.size() == 2, "图后到：items 里是 1 条 message + 1 条 patch（实际 " + items.size() + " 条）");
        JsonObject patch = items.get(1).getAsJsonObject();
        check("patch".equals(Json.str(patch, "type", "")) && "quest:16:1".equals(Json.str(patch, "target", "")),
                "patch 的 target 指向那条 message：" + Json.str(patch, "target", ""));
        check(Json.str(patch, "id", "").startsWith("patch:quest:16:1:")
                        && "data/generated/late.png".equals(patch.getAsJsonArray("images").get(0).getAsString()),
                "patch 的 id = patch:<条目 id>:<图路径 hash>，图路径原样带着：" + Json.str(patch, "id", ""));
        check(items.get(0).getAsJsonObject().getAsJsonArray("images").size() == 0,
                "原条目一个字都没改（图不在 message 上，而在 patch 里）");

        // ③ 同一条回执再重放 3 次：图不重复入队
        for (int round = 1; round <= 3; round++) {
            store.appendReceipt(DEV_B, "16", List.of(textGroup("图来啦，稍等。"), imageGroup("data/generated/late.png")));
        }
        check(store.fetch(DEV_B, 0, 200).getAsJsonArray("items").size() == 2,
                "同一条回执重放 3 次：图不重复入队（仍然 2 条）");

        // ④ 图与正文**同一次**到：图并进那条 message，不另发 patch
        EventQueueStore fresh = new EventQueueStore(root);
        check(fresh.appendReceipt("dev-0000aaaa1111", "17",
                List.of(textImageGroup("同时到的图。", "data/generated/same.png"))) == 1,
                "图与正文同一次到：只入队 1 条");
        JsonArray same = fresh.fetch("dev-0000aaaa1111", 0, 200).getAsJsonArray("items");
        check(same.size() == 1 && same.get(0).getAsJsonObject().getAsJsonArray("images").size() == 1,
                "同一次到的图挂在 message 上（不是 patch）：" + same);
    }

    // ---------------------------------------------------------------- 3) 出队

    static void dequeue(Path root) {
        EventQueueStore store = new EventQueueStore(root);
        String scope = "dev-222233334444";
        List<JsonObject> batch = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            batch.add(EventQueueStore.botMessage("msg:" + scope + ":" + index, "第 " + index + " 条", List.of()));
        }
        check(store.appendAll(scope, batch) == 5, "先造 5 条");

        JsonObject all = store.fetch(scope, 0, 200);
        JsonArray items = all.getAsJsonArray("items");
        long previous = 0;
        boolean ascending = true;
        for (JsonElement node : items) {
            long seq = Json.num(node.getAsJsonObject(), "seq", 0L);
            if (seq <= previous) ascending = false;
            previous = seq;
        }
        check(items.size() == 5 && ascending, "after=0 拿全部且 seq 严格升序：" + items.size() + " 条");
        check(Json.num(all, "next", 0L) == 5 && !Json.bool(all, "hasMore", true) && Json.num(all, "latest", 0L) == 5,
                "取完一批：next=最后一条的 seq、hasMore=false、latest=5：" + all.get("next") + "/" + all.get("hasMore"));
        check(scope.equals(Json.str(all, "scope", "")), "响应里原样回显 scope");

        JsonObject first = store.fetch(scope, 0, 2);
        check(first.getAsJsonArray("items").size() == 2 && Json.num(first, "next", 0L) == 2 && Json.bool(first, "hasMore", true),
                "limit=2：一批 2 条、hasMore=true、next=2");
        JsonObject second = store.fetch(scope, Json.num(first, "next", 0L), 2);
        check(second.getAsJsonArray("items").size() == 2
                        && Json.num(second.getAsJsonArray("items").get(0).getAsJsonObject(), "seq", 0L) == 3,
                "带上一批的 next 接着取：从第 3 条开始");
        JsonObject third = store.fetch(scope, Json.num(second, "next", 0L), 2);
        check(third.getAsJsonArray("items").size() == 1 && !Json.bool(third, "hasMore", true),
                "最后一批 1 条、hasMore=false");
        JsonObject empty = store.fetch(scope, Json.num(third, "next", 0L), 200);
        check(empty.getAsJsonArray("items").size() == 0 && Json.num(empty, "next", 0L) == 5
                        && !Json.bool(empty, "hasMore", false),
                "after=next 再取：空批、next 仍是请求里的 after、hasMore=false");
        check(store.fetch(scope, 0, 0).getAsJsonArray("items").size() == 5, "limit 不传（0）= 默认 200：一次拿全部");
    }

    // ---------------------------------------------------------------- 4) ack 只进不退

    static void ackMonotonic(Path root) {
        EventQueueStore store = new EventQueueStore(root);
        String scope = "dev-333344445555";
        List<JsonObject> batch = new ArrayList<>();
        for (int index = 1; index <= 5; index++) batch.add(EventQueueStore.botMessage("msg:" + scope + ":" + index, "第 " + index + " 条", List.of()));
        store.appendAll(scope, batch);
        check(store.ack(scope, 3) == 3, "ack 3 → 3");
        check(store.ack(scope, 1) == 3, "再 ack 1（回退）→ 仍然是 3：只进不退");
        check(store.ack(scope, 999) == 5, "ack 比 latest 还大 → 夹到 latest 5（ack 的意思是「这些我拿到了」，不是「以后的我都要」）");
        check(store.ack(scope, 5) == 5 && store.acked(scope) == 5, "ack 5 → 5，且写进了这个 scope 的 ack 文件");
        check(Json.num(store.fetch(scope, 0, 200), "acked", 0L) == 5, "出队时也带上这个 scope 的 acked");
    }

    // ---------------------------------------------------------------- 5) 多 scope 隔离

    static void scopeIsolation(Path root) {
        EventQueueStore store = new EventQueueStore(root);
        String scopeA = "dev-aaaa00001111";
        String scopeB = "dev-bbbb00002222";
        store.append(scopeA, EventQueueStore.message("a-1", "user", "A 说的话", List.of(), 0, 0));
        store.append(scopeA, EventQueueStore.botMessage("a-2", "A 收到的回话", List.of()));
        store.append(scopeB, EventQueueStore.message("b-1", "user", "B 说的话", List.of(), 0, 0));

        JsonArray itemsB = store.fetch(scopeB, 0, 200).getAsJsonArray("items");
        Set<String> idsB = new LinkedHashSet<>();
        for (JsonElement node : itemsB) idsB.add(Json.str(node.getAsJsonObject(), "id", ""));
        check(itemsB.size() == 1 && idsB.contains("b-1"),
                "B 的队列里只有 B 自己的那条：" + idsB);
        check(!idsB.contains("a-1") && !idsB.contains("a-2"), "A 的事件不会出现在 B 的队列里");
        check(store.fetch(scopeA, 0, 200).getAsJsonArray("items").size() == 2, "A 的队列里是 A 自己的两条");

        store.ack(scopeA, 2);
        check(store.acked(scopeB) == 0 && store.acked(scopeA) == 2, "ack 按 scope 各存一份：ack A 不影响 B");
    }

    // ---------------------------------------------------------------- 6) 裁剪：只裁已 ack 的

    static void trimming(Path root) {
        int total = EventQueueStore.TRIM_THRESHOLD + 1;         // 5001 条
        // ① 全部 ack：超过 5000 的那一条（最旧的）才被裁，文件里留下 trimmedUpTo
        EventQueueStore store = new EventQueueStore(root);
        String scope = "dev-555566667777";
        fill(store, scope, total);
        check(store.size(scope) == total, "先造 " + total + " 条（一条都没裁：还没 ack）");
        check(store.acked(scope) == 0, "还没 ack");
        store.ack(scope, total);
        check(store.size(scope) == EventQueueStore.TRIM_THRESHOLD, "ack 之后裁到 5000 条（" + store.size(scope) + "）");
        check(store.trimmedUpTo(scope) == 1 && store.item(scope, id(scope, 1)) == null
                        && store.item(scope, id(scope, 2)) != null,
                "裁掉的是最旧那条已 ack 的（trimmedUpTo=1），第 2 条起都还在");
        JsonObject stored = read(EventQueueStore.fileFor(root, scope));
        check(stored.has("trimmedUpTo") && Json.num(stored, "trimmedUpTo", 0L) == 1
                        && Json.num(stored, "nextSeq", 0L) == total + 1,
                "文件里有 trim 标记 trimmedUpTo=" + Json.num(stored, "trimmedUpTo", 0L) + "，nextSeq 照旧往上走");

        // ② 只 ack 前 3000 条：只能裁已 ack 的连续前缀，未 ack 的 2001 条一条都不许删
        String half = "dev-777788889999";
        fill(store, half, total);
        store.ack(half, 3000);
        check(store.size(half) == EventQueueStore.TRIM_THRESHOLD, "只 ack 前 3000 条：裁到 5000 条（" + store.size(half) + "）");
        check(store.item(half, id(half, 3001)) != null && store.item(half, id(half, total)) != null,
                "未 ack 的那 2001 条一条不删（第 3001 条与最后一条都还在）");
        int remaining = 0;
        long after = 0;
        while (true) {
            JsonObject page = store.fetch(half, after, 2000);
            remaining += page.getAsJsonArray("items").size();
            after = Json.num(page, "next", 0L);
            if (!Json.bool(page, "hasMore", false)) break;
        }
        JsonObject head = store.fetch(half, 0, 1);
        check(remaining == EventQueueStore.TRIM_THRESHOLD
                        && Json.num(head.getAsJsonArray("items").get(0).getAsJsonObject(), "seq", 0L) == 2,
                "窗口里剩的正是 5000 条（未 ack 的 2001 条 + 已 ack 但还在窗口里的 2999 条），且只裁掉最旧的 seq 1："
                        + remaining);
    }

    /** 造 {@code count} 条（分批入队：一条一落盘会把磁盘写爆，队列本身支持整批一次落盘）。 */
    private static void fill(EventQueueStore store, String scope, int count) {
        List<JsonObject> batch = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            batch.add(EventQueueStore.message(id(scope, index), "bot", "第 " + index + " 条", List.of(), 0, 0));
            if (batch.size() >= 1000) { store.appendAll(scope, batch); batch.clear(); }
        }
        if (!batch.isEmpty()) store.appendAll(scope, batch);
    }

    private static String id(String scope, int index) { return "msg:" + scope + ":" + index; }

    // ---------------------------------------------------------------- 7) 持久化（换实例 = 模拟重启）

    static void persistence(Path root) {
        EventQueueStore store = new EventQueueStore(root);
        String scope = "dev-abcabcabcabc";
        List<JsonObject> batch = new ArrayList<>();
        for (int index = 1; index <= 4; index++) batch.add(EventQueueStore.message(id(scope, index), "bot", "第 " + index + " 条", List.of(), 0, 0));
        store.appendAll(scope, batch);
        store.ack(scope, 3);

        EventQueueStore restarted = new EventQueueStore(root);          // 新实例 = 模拟重启（不真重启机器人）
        JsonObject page = restarted.fetch(scope, 0, 200);
        JsonArray items = page.getAsJsonArray("items");
        Set<Long> seqs = new LinkedHashSet<>();
        boolean ascending = true;
        long previous = 0;
        for (JsonElement node : items) {
            long seq = Json.num(node.getAsJsonObject(), "seq", 0L);
            if (seq <= previous) ascending = false;
            previous = seq;
            seqs.add(seq);
        }
        check(items.size() == 4 && seqs.size() == 4 && ascending, "重启后 items 一条不丢、一条不重、seq 连续升序：" + seqs);
        check(Json.num(page, "latest", 0L) == 4 && Json.num(page, "acked", 0L) == 3, "重启后 latest=4、acked=3 都还在");
        check(Json.num(page, "next", 0L) == 4, "重启后出队从 after=0 拿到 next=4");

        check(restarted.append(scope, EventQueueStore.message(id(scope, 5), "bot", "重启后的第 5 条", List.of(), 0, 0)),
                "重启后接着入队一条");
        JsonObject after = restarted.fetch(scope, 4, 200);
        check(after.getAsJsonArray("items").size() == 1
                        && Json.num(after.getAsJsonArray("items").get(0).getAsJsonObject(), "seq", 0L) == 5,
                "新条目的 seq 接着 5 往下发，不与旧的撞号");
        check(restarted.size(scope) == 5 && Json.num(read(EventQueueStore.fileFor(root, scope)), "nextSeq", 0L) == 6,
                "文件里的 nextSeq 也往下走（6）");
    }

    // ---------------------------------------------------------------- 8) 未知 type

    static void unknownType(Path root) {
        EventQueueStore store = new EventQueueStore(root);
        String scope = "dev-deadbeef0000";
        JsonObject future = new JsonObject();
        future.addProperty("id", "typing:1");
        future.addProperty("type", "typing");                 // 以后才会有的类型
        future.addProperty("at", System.currentTimeMillis());
        future.addProperty("who", "bot");
        check(store.append(scope, future), "未知 type 照常入队（服务端不为难它）");
        JsonArray items = store.fetch(scope, 0, 200).getAsJsonArray("items");
        check(items.size() == 1 && "typing".equals(Json.str(items.get(0).getAsJsonObject(), "type", "")),
                "未知 type 原样出队（前端自己忽略它，不必服务端丢）");
        JsonObject broken = new JsonObject();
        broken.addProperty("text", "没有 id 和 type");
        check(!store.append(scope, broken) && store.size(scope) == 1, "缺 id/type 的条目拒收（分不清身份的东西不进队列）");
        check(store.fetch(scope, 0, 200).getAsJsonArray("items").size() == 1, "拒收之后队列没被写坏");
    }

    // ---------------------------------------------------------------- Bot：真实入队落点（observeQuest）

    static void botWiring(Path root) throws Exception {
        Path home = root.resolve("bot-home");
        Files.createDirectories(home.resolve("data/generated"));
        HttpServer sd = sdStub();
        try {
            JsonObject sdConfig = new JsonObject();
            sdConfig.addProperty("base_url", "http://127.0.0.1:" + sd.getAddress().getPort());
            Json.atomicWrite(home.resolve("config.json"), config(sdConfig));
            Settings settings = new Settings(home);
            Bot.Sender sender = new Bot.Sender() {
                @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
                    return CompletableFuture.completedFuture(new JsonObject());
                }
            };
            try (Bot bot = new Bot(settings, new SdClient(home, sdConfig), sender)) {
                String scope = DEV_A;
                Bot.WebCapture capture = new Bot.WebCapture("eq-" + System.nanoTime(), ".infix … ; .gen",
                        home.resolve("data/generated"), scope);
                capture.capture(Maps.text("正在通过 DeepSeek 智能修改你个人的提示词。"));
                capture.capture(Maps.text("任务 #15 已完成：1/1。"));
                for (int round = 1; round <= 3; round++) bot.observeQuestForTests(capture);

                JsonArray items = bot.webEvents(scope, 0, 200).getAsJsonArray("items");
                check(items.size() == 2, "同一条回执经 Bot.observeQuest 重放 3 次：队列里只有一套 2 条（实际 "
                        + items.size() + " 条）");
                check(("quest:" + capture.number() + ":1").equals(Json.str(items.get(0).getAsJsonObject(), "id", ""))
                                && Json.num(items.get(0).getAsJsonObject(), "quest", 0L) == capture.number()
                                && Json.num(items.get(0).getAsJsonObject(), "index", 0L) == 1,
                        "入队落点带上了回执号与条内序号（前端按 id 就能认出是哪一条回执的第几条）");

                // 图片晚到：再捕获一张图 → 只补一条 patch（target 指向第 2 条正文）
                JsonObject segment = new JsonObject();
                segment.addProperty("type", "image");
                JsonObject data = new JsonObject();
                data.addProperty("file", "data/generated/late.png");
                segment.add("data", data);
                JsonArray segments = new JsonArray();
                segments.add(segment);
                capture.capture(segments);
                capture.finish();
                bot.observeQuestForTests(capture);

                items = bot.webEvents(scope, 0, 200).getAsJsonArray("items");
                check(items.size() == 3
                                && "patch".equals(Json.str(items.get(2).getAsJsonObject(), "type", ""))
                                && ("quest:" + capture.number() + ":2").equals(Json.str(items.get(2).getAsJsonObject(), "target", "")),
                        "图后到：Bot 这一侧补的是 patch，target 指向第 2 条正文（原条目没改）");
                check("data/generated/late.png".equals(items.get(2).getAsJsonObject().getAsJsonArray("images").get(0).getAsString()),
                        "patch 带着那张图的路径");

                // 真 HTTP 走一遍（前端接的就是这两个接口）——放在 ack 之前，好让 HTTP 那一侧的 ack 真的推进。
                httpQueue(home, bot, scope);

                JsonObject acked = bot.webEventAck(scope, Json.num(items.get(2).getAsJsonObject(), "seq", 0L));
                check(Json.bool(acked, "ok", false) && Json.num(acked, "acked", 0L) == 3,
                        "/api/events/ack 的形状是 {ok:true,acked:N}：" + acked);
                check(bot.webEvents(scope, 0, 200).getAsJsonArray("items").size() == 3
                                && bot.webEvents(scope, 3, 200).getAsJsonArray("items").size() == 0,
                        "ack 之后照旧能从队列里取（ack 只是「我拿到了」，不是删）");
            }
        } finally {
            sd.stop(0);
        }
    }

    /** 真 HTTP：前端接的就是这两个接口，路由、scope、响应形状都按冻结的契约走一遍。 */
    private static void httpQueue(Path home, Bot bot, String scope) throws Exception {
        Settings settings = new Settings(home);
        settings.webSetting("port", new JsonPrimitive(freePort()));
        settings.webSetting("access_token", new JsonPrimitive(TOKEN));
        try (cn.szu.bot.web.WebUiServer server = new cn.szu.bot.web.WebUiServer(settings, bot)) {
            server.start();
            String base = "http://127.0.0.1:" + server.actualPort();
            JsonObject page = post(base + "/api/events", events(scope, 0, 200));
            check(scope.equals(Json.str(page, "scope", "")) && page.getAsJsonArray("items").size() == 3
                            && Json.num(page, "next", 0L) == 3 && !Json.bool(page, "hasMore", false)
                            && Json.num(page, "latest", 0L) == 3 && Json.num(page, "acked", 0L) == 0,
                    "POST /api/events 出的就是契约里那几个字段：" + page.keySet());
            long next = Json.num(page, "next", 0L);
            JsonObject empty = post(base + "/api/events", events(scope, next, 200));
            check(empty.getAsJsonArray("items").size() == 0 && Json.num(empty, "next", 0L) == next
                            && !Json.bool(empty, "hasMore", false),
                    "after=next 再取：空批、next 不变、hasMore=false");
            JsonObject other = post(base + "/api/events", events("dev-00000000ffff", 0, 200));
            check(other.getAsJsonArray("items").size() == 0, "别的 scope 取不到这个会话的事件（多 scope 隔离）");
            JsonObject acked = post(base + "/api/events/ack", ack(scope, 3));
            check(Json.bool(acked, "ok", false) && Json.num(acked, "acked", 0L) == 3,
                    "POST /api/events/ack → {ok:true,acked:3}：" + acked);
            JsonObject back = post(base + "/api/events/ack", ack(scope, 1));
            check(Json.num(back, "acked", 0L) == 3, "ack 回退不发散：再 ack 1 仍然是 3");
        }
    }

    // ---------------------------------------------------------------- 工具

    static JsonArray textGroup(String text) {
        JsonArray group = new JsonArray();
        JsonObject node = new JsonObject();
        node.addProperty("type", "text");
        node.addProperty("text", text);
        group.add(node);
        return group;
    }

    static JsonArray imageGroup(String file) {
        JsonArray group = new JsonArray();
        JsonObject node = new JsonObject();
        node.addProperty("type", "image");
        node.addProperty("file", file);
        group.add(node);
        return group;
    }

    static JsonArray textImageGroup(String text, String file) {
        JsonArray group = textGroup(text);
        group.add(imageGroup(file).get(0));
        return group;
    }

    static JsonObject read(Path file) {
        try { return Json.parse(Files.readString(file, StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new AssertionError("读不出来：" + file + "：" + error); }
    }

    /** SD 的本机桩：够 Bot 起来就行（绝不连真的 Stable Diffusion）。 */
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

    // ---------------------------------------------------------------- HTTP 工具

    private static final String TOKEN = "test-token-123456";
    private static final java.net.http.HttpClient HTTP =
            java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

    private static JsonObject events(String scope, long after, int limit) {
        JsonObject body = new JsonObject();
        body.addProperty("scope", scope);
        body.addProperty("after", after);
        body.addProperty("limit", limit);
        return body;
    }

    private static JsonObject ack(String scope, long seq) {
        JsonObject body = new JsonObject();
        body.addProperty("scope", scope);
        body.addProperty("seq", seq);
        return body;
    }

    private static JsonObject post(String url, JsonObject body) throws Exception {
        java.net.http.HttpResponse<String> response = HTTP.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(30))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString(), StandardCharsets.UTF_8))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new AssertionError("HTTP " + response.statusCode() + "：" + url + " → " + response.body());
        return Json.parse(response.body());
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
    }
}

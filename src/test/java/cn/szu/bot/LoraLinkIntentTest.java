package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.ChatService;
import cn.szu.bot.chat.DeepSeekPrompts;

/**
 * 修误判（只做 B）：**「消息里出现『下载』字样」本身不是指令**。
 *
 * <p>反例都钉在这里：
 * <ul>
 *   <li>「下载速度好慢」「这个模型不好下载」「下载完了吗」「一条条来」——没有下载目标（链接/#编号），
 *       一个字都不许执行，也不再被 {@link DeepSeekPrompts#requiresCommands} 判成「要执行却没给指令」
 *       而空转四次、最后回一句「没能处理完」；</li>
 *   <li>只贴链接、没有下载意图（单纯分享）——程序不擅自下载，也不多嘴问一句；</li>
 *   <li>非 Civitai 链接永远不算下载目标；</li>
 *   <li>真正的下载请求（有目标）照旧要真指令，回复里「下载」的说法只有 .lora download 才能兑现；
 *       权限不足时仍然拒绝并说明（沿用既有 admin_only 规则）。</li>
 * </ul>
 *
 * <p>只用假规划器/假执行器/假 SD 桩，绝不连网、绝不下载。本文件只验证「要不要执行」的判定，
 * 不实现任何链接抽取或自动排队下载。
 */
public final class LoraLinkIntentTest {
    private static int assertions;
    /** 用户贴给机器人的那串链接里的一条（测试里只当文本，不联网）。 */
    private static final String LINK = "https://civitai.com/models/1234?modelVersionId=5678";

    public static void main(String[] args) throws Exception {
        mentionsWithoutTargetAreNeverCommands();
        linkWithoutDownloadIntentNeverDownloads();
        downloadClaimsAndPermissions();
        System.out.println("LoraLinkIntentTest: " + assertions + " assertions passed: "
                + "「下载」没有目标不算指令（反例已钉死）、只贴链接不下载、非 Civitai 链接不算目标、"
                + "空头「下载」承诺与 admin_only 权限照旧拦下。");
    }

    /** 提到「下载」但没有任何可执行目标 → 既不是指令，也不空转重试。 */
    private static void mentionsWithoutTargetAreNeverCommands() throws Exception {
        for (String text : List.of("下载速度好慢", "这个模型不好下载", "下载完了吗", "今天下载量好大",
                "一条条来", "我这就下载，一条条来。")) {
            check(!DeepSeekPrompts.requiresCommands(text), "没有下载目标就不该被当成要执行：" + text);
            check(!DeepSeekPrompts.downloadTargeted(text), "没有目标就不是「要下载某个东西」：" + text);
        }
        // 预告"之后我会发链接让你下载"：只是在说要发，不是要现在下载（没有链接目标）。
        for (String text : List.of("之后我会发一连串的 lora 链接，你全都要下载", "以后有 Civitai 链接就下载",
                "等下我把链接发给你下载")) {
            check(!DeepSeekPrompts.downloadTargeted(text), "预告发链接不是下载请求：" + text);
            check(!DeepSeekPrompts.requiresCommands(text), "预告发链接不该被当成要执行：" + text);
        }
        // 链接与下载意图同时到齐才算数（这才是真的下载请求）。
        check(DeepSeekPrompts.downloadTargeted("这 8 个链接你全都要下载：" + LINK), "链接 + 都要下载才算数");
        // 有目标才算「要下载某个东西」：链接、#编号、第 N 个都认。
        check(DeepSeekPrompts.downloadTargeted("下载 " + LINK), "带链接的下载请求要算数");
        check(DeepSeekPrompts.downloadTargeted("把这几个链接都下载了 " + LINK), "链接 + 都要下载要算数");
        check(DeepSeekPrompts.downloadTargeted("下载 #3"), "#编号是下载目标");
        check(DeepSeekPrompts.downloadTargeted("下载第一个"), "第 N 个是下载目标");
        check(DeepSeekPrompts.requiresCommands("下载 " + LINK), "有目标的下载请求必须真出指令");
        check(DeepSeekPrompts.requiresCommands("把 " + LINK + " 下载了"), "有目标的下载请求必须真出指令");
        // 提问/过去式照旧优先放行，与「下载」这条无关。
        check(!DeepSeekPrompts.requiresCommands("怎么下载模型"), "问怎么做不算执行");
        check(!DeepSeekPrompts.requiresCommands("刚才下载过这个模型"), "过去式不算执行");

        // 端到端：规划器对「下载速度好慢」回了一句自然的聊天话、没有任何指令 → 一个字都不执行，
        // 也不该冒出「没能处理完 / 没有可执行的指令」这类重试话术。
        AtomicInteger planned = new AtomicInteger(), executed = new AtomicInteger();
        try (Harness h = new Harness((personality, history, message, choices, speaker) -> {
            planned.incrementAndGet();
            return new ChatActions.Plan("是有点慢，我这边也等半天。", List.of(), "", 90, true, true);
        }, (event, commands, choices) -> executed.addAndGet(commands.size()))) {
            h.talk("group", "1", "下载速度好慢");
            h.idle();
            check(planned.get() == 1, "聊天照旧被规划一次，但没有任何指令：" + planned.get());
            check(executed.get() == 0, "「下载速度好慢」不得执行任何指令");
            check(h.replies.size() == 1, "只回聊天那一句，不追加多余回执：" + h.replies);
            check(!h.joined().contains("没有可执行的指令") && !h.joined().contains("没能处理完"),
                    "不许出现「要求执行却没有指令」那套重试话术：" + h.joined());
        }
    }

    /** 有链接、没有下载意图 → 程序不擅自下载、不多嘴、也不执行任何指令。 */
    private static void linkWithoutDownloadIntentNeverDownloads() throws Exception {
        AtomicInteger planned = new AtomicInteger(), executed = new AtomicInteger();
        try (Harness h = new Harness((personality, history, message, choices, speaker) -> {
            planned.incrementAndGet();
            return new ChatActions.Plan("这个我看过，画风挺干净的。", List.of(), "", 90, true, true);
        }, (event, commands, choices) -> executed.addAndGet(commands.size()))) {
            // 单纯分享：只是让你看看
            h.talk("private", "1", "你看看这个 " + LINK);
            h.idle();
            // 只是评价
            h.talk("private", "1", "这个 LoRA 怎么样 " + LINK);
            h.idle();
            check(planned.get() == 2, "两条分享消息都照旧交给规划器（判定仍归它）：" + planned.get());
            check(executed.get() == 0, "没有下载意图时程序不得执行任何指令");
            check(h.replies.size() == 2, "不擅自下载、也不额外多嘴追问：" + h.replies);
            check(!h.joined().contains("下载"), "回复里不该冒出程序自己加的下载提示：" + h.joined());
        }
        // 非 Civitai 链接：即使说了「下载」也不算下载目标。
        check(!DeepSeekPrompts.downloadTargeted("下载 https://example.com/x"), "非 Civitai 链接不是下载目标");
        check(!DeepSeekPrompts.requiresCommands("下载 https://example.com/x"), "非 Civitai 链接不得触发下载判定");
        check(!DeepSeekPrompts.downloadTargeted("下载 https://example.com/x 这个模型"), "非 Civitai 链接不得触发下载判定");
        // 光贴一个非 Civitai 链接什么也不做。
        AtomicInteger otherPlanned = new AtomicInteger(), otherExecuted = new AtomicInteger();
        try (Harness h = new Harness((personality, history, message, choices, speaker) -> {
            otherPlanned.incrementAndGet();
            return new ChatActions.Plan("看到了。", List.of(), "", 90, true, true);
        }, (event, commands, choices) -> otherExecuted.addAndGet(commands.size()))) {
            h.talk("private", "9", "https://example.com/x 这个你看看");
            h.idle();
            check(otherExecuted.get() == 0, "非 Civitai 链接不得触发下载");
            check(otherPlanned.get() == 1 && h.replies.size() == 1, "照聊天处理：" + h.replies);
        }
    }

    /** 真正的下载请求、空头「下载」承诺、权限不足。 */
    private static void downloadClaimsAndPermissions() throws Exception {
        String ask = "帮我下载 " + LINK;
        // 空头承诺：用户真的要下载（有目标），回复说「这就下载」却没有任何 .lora download → 必须拦。
        check(ChatActions.promisesUnappliedEdit("好，我这就下载。", List.of(), ask),
                "只有下载承诺、没有任何指令要拦下");
        check(ChatActions.promisesUnappliedEdit("好，我这就下载。", List.of(".style delete #1"), ask),
                "别的管理类指令不能替「下载」兑现：" + List.of(".style delete #1"));
        check(!ChatActions.promisesUnappliedEdit("好，我这就下载。", List.of(".lora download " + LINK), ask),
                "真有 .lora download 就不拦");
        check(!ChatActions.promisesUnappliedEdit("好，这就打开列表看看。", List.of(), ask),
                "闲聊里没有声称下载，不拦");
        // 反例钉死：用户只是抱怨下载慢（没有目标），回复提到「下载」不算空头承诺（不触发重试、不加话术）。
        check(!ChatActions.promisesUnappliedEdit("下载是有点慢，我这边也等半天。", List.of(), "下载速度好慢"),
                "「下载速度好慢」这类闲聊里的下载不是承诺，不许拦");
        check(!ChatActions.promisesUnappliedEdit("下载速度好慢呀。", List.of(), "下载速度好慢"),
                "用户抱怨里的下载同样不是承诺");
        check(!ChatActions.promisesUnappliedEdit("这个模型不好下载。", List.of(), "这个模型不好下载"),
                "「不好下载」不是承诺");
        // 没有下载目标的请求（只贴了个普通链接让你看看）：回复里的「下载」同样不算承诺。
        check(!ChatActions.promisesUnappliedEdit("我看看要不要下载。", List.of(), "你看看这个 " + LINK),
                "单纯分享链接时回复里的「下载」不是承诺");
        // 别的管理类承诺照旧：声称删了却没有删除指令 → 拦。
        check(ChatActions.promisesUnappliedEdit("好，已经删掉了。", List.of(), "把第 12 项删掉"), "删除承诺照旧要拦");
        check(!ChatActions.promisesUnappliedEdit("好，已经删掉了。", List.of(".style delete #12-#16"), "把第 12 项删掉"),
                "有删除指令就兑现");

        // 权限不足（civitai.admin_only）：聊天里要下载也照样拒绝，并且一次都不许碰下载器。
        AtomicInteger downloads = new AtomicInteger();
        try (Harness h = new Harness((personality, history, message, choices, speaker) ->
                    new ChatActions.Plan("好，我去下载。", List.of(".lora download " + LINK), "", 90),
                (event, commands, choices) -> downloads.addAndGet(commands.size()), true)) {
            h.talk("private", "7", "帮我下载 " + LINK);
            h.idle();
            check(downloads.get() == 1, "聊天指令通道确实把这条下载交给执行层：" + downloads.get());
            check(!h.bot.loraProgress().get("busy").getAsBoolean(), "被拒绝的下载不得真的占用下载通道");
            check(h.downloaderCalls() == 0, "被拒绝的下载绝不许碰下载器");
        }
        // 同一条规则直接走指令通道：非管理员发 .lora download 被拒绝并说明，且绝不动下载器。
        try (Harness h = new Harness((personality, history, message, choices, speaker) ->
                    new ChatActions.Plan("嗯。", List.of(), "", 90),
                (event, commands, choices) -> { }, true)) {
            h.bot.accept(h.text("private", "7", ".lora download " + LINK));
            String denial = null;
            List<String> lines = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (denial == null && System.nanoTime() < deadline) {
                String next = h.outbox.poll(200, TimeUnit.MILLISECONDS);
                if (next != null) lines.add(next);
                if (next != null && next.contains("仅管理员")) denial = next;
            }
            check(denial != null, "非管理员发下载指令要被拒绝并说明：[" + String.join("|", lines) + "]");
            check(!h.bot.loraProgress().get("busy").getAsBoolean(), "被拒绝的下载不得启动任何任务");
            check(h.downloaderCalls() == 0, "被拒绝的下载绝不许碰下载器");
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    /**
     * 夹具：真 Bot + 假 SD 桩 + 假规划器 + 假执行器。SD 桩只回最小 JSON，不联网；
     * 注入的下载器一旦被调用就直接失败（本文件不实现自动下载）。
     */
    private static final class Harness implements AutoCloseable {
        final Path root;
        final com.sun.net.httpserver.HttpServer server;
        final Bot bot;
        final List<String> replies = new CopyOnWriteArrayList<>();
        final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();
        final AtomicInteger downloaderCalls = new AtomicInteger();

        int downloaderCalls() { return downloaderCalls.get(); }
        final ChatService service;
        private final AtomicInteger ids = new AtomicInteger();

        Harness(ChatService.Planner planner, ChatService.Actions actions) throws Exception {
            this(planner, actions, false);
        }

        Harness(ChatService.Planner planner, ChatService.Actions actions, boolean adminOnlyDownload) throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "lora-link-intent-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sd.addProperty("timeout_seconds", 2);
            JsonObject civitai = new JsonObject();
            civitai.addProperty("admin_only", adminOnlyDownload);
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            config.add("civitai", civitai);
            config.addProperty("owner_user_id", "1");
            Json.atomicWrite(root.resolve("config.json"), config);
            Settings settings = new Settings(root);
            settings.chatSetting("frequency", new JsonPrimitive(20));
            bot = new Bot(settings, new cn.szu.bot.sd.SdClient(root, sd),
                    record(),
                    new Bot.LoraDownloader() {
                        public cn.szu.bot.civitai.CivitaiClient.DownloadedLora download(String url, java.util.function.Consumer<String> stage, cn.szu.bot.civitai.CivitaiClient.Progress meter) {
                            downloaderCalls.incrementAndGet();
                            throw new AssertionError("本测试绝不允许真的下载：" + url);
                        }
                    });
            service = new ChatService(settings, record(), planner, actions, bot::selectionContext, System::nanoTime);
        }

        /** 出站消息同时落进"全部回执"列表与"可阻塞等待的下一条"队列。 */
        private Bot.Sender record() {
            return new Bot.Sender() {
                public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                    String text = Bot.messageText(segments);
                    replies.add(text);
                    outbox.add(text);
                    return CompletableFuture.completedFuture(null);
                }
                /** 合并转发（多步回执）也要能被测试看到，否则拒绝说明会"消失"在转发卡里。 */
                public CompletableFuture<Void> sendRecord(JsonObject event, List<JsonArray> messages) {
                    for (JsonArray message : messages) send(event, message);
                    return CompletableFuture.completedFuture(null);
                }
            };
        }

        /** 构造一条普通消息事件（私聊；指令走它）。 */
        JsonObject text(String type, String user, String text) {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", type);
            event.addProperty("self_id", "777");
            event.addProperty("user_id", user);
            if (type.equals("group")) event.addProperty("group_id", "42");
            event.addProperty("raw_message", text);
            event.addProperty("message", text);
            event.addProperty("message_id", ids.incrementAndGet());
            return event;
        }

        /** 发一条被点名的消息：群聊里带 @机器人 段，私聊直接算被点名。 */
        void talk(String type, String user, String text) {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", type);
            event.addProperty("self_id", "777");
            event.addProperty("user_id", user);
            if (type.equals("group")) {
                event.addProperty("group_id", "42");
                JsonArray segments = new JsonArray();
                JsonObject at = new JsonObject();
                at.addProperty("type", "at");
                JsonObject atData = new JsonObject();
                atData.addProperty("qq", "777");
                at.add("data", atData);
                segments.add(at);
                JsonObject body = new JsonObject();
                body.addProperty("type", "text");
                JsonObject bodyData = new JsonObject();
                bodyData.addProperty("text", " " + text);
                body.add("data", bodyData);
                segments.add(body);
                event.add("message", segments);
                event.addProperty("raw_message", "[CQ:at,qq=777] " + text);
            } else {
                event.addProperty("raw_message", text);
                event.addProperty("message", text);
            }
            event.addProperty("message_id", ids.incrementAndGet());
            service.accept(event, text);
        }

        void idle() throws Exception { ChatActionsTest.waitIdle(service); }

        String joined() { return String.join("\n", replies); }

        public void close() throws Exception {
            service.close();
            bot.close();
            server.stop(0);
            TestCleanup.awaitQuiet(root, 3000);
            TestCleanup.deleteQuietly(root);
        }
    }
}

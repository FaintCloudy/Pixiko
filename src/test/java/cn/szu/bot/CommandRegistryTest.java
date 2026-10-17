package cn.szu.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cn.szu.bot.application.command.CommandContext;
import cn.szu.bot.application.command.CommandDispatcher;
import cn.szu.bot.application.command.CommandRegistry;
import cn.szu.bot.application.command.CommandResult;
import cn.szu.bot.domain.CommandError;
import cn.szu.bot.domain.Scope;
import cn.szu.bot.domain.UserId;
import cn.szu.bot.sd.SdClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 指令注册表（{@code application/command}）搬迁的**行为基线**。
 *
 * <p>Phase 2 第 1 步只做两件事：把 {@code .help}/{@code .mode}/{@code .imgmode} 三条指令的**回执原文**
 * 钉死在这里（改动前先跑一遍记下来，改动后同一份断言必须逐字通过），并钉住"注册表未命中的指令仍然走
 * 原来的 {@code dispatch}"。这三条是唯一被搬进注册表的指令，其余几十条一条都不许变。
 *
 * <p>隔离：机器人本体是真的 {@link Bot}，但传输层是记录型 {@link Bot.Sender}（只把出站消息放进队列），
 * 根目录在 {@code -Dbot.test.work} 下的临时目录，SD 指向 {@code 127.0.0.1:0}（连不上就按连不上处理，
 * 本项目既有做法），绝不碰真 QQ、真 SD、生产 config.json 或 8787/3001/7860/7861。
 */
public final class CommandRegistryTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    /** 夹具里的 owner：假号码，只用来触发权限分支，不对应任何真人。 */
    private static final String OWNER = "123";
    private static int assertions;

    // ---------------------------------------------------------------- 回执基线（第一步：改动前记录）

    /** {@code .help} 回执的 SHA-256：逐字一致就等价于哈希一致（全文 8003 字符，另钉首尾行）。 */
    static final String HELP_SHA256 = "6ac38e89fcd542b48d14ac93cdaba856b94ac373e4ae16896c266e7fb4d6fc39";
    /** {@code .mode}（不带参数）回执原文（102 字符，改动前逐字记录）。 */
    static final String MODE_TEXT = "本会话的回执档位：调试（debug）\n"
            + "调试（默认，现状）：完整回执——出图参数、多步执行过程与领取计数都会发出来。\n"
            + "改法：.mode normal（常规/简洁）、.mode debug（调试/详细，默认）";
    /** {@code .imgmode}（不带参数）回执原文（137 字符，改动前逐字记录）。 */
    static final String IMGMODE_TEXT = "本会话的图片发送形式：自动（auto）\n"
            + "自动规则（默认）：一批多于一张时合成一条聊天记录（合并转发），单张保持普通发送。\n"
            + "改法：.imgmode record（聊天记录/合并转发/转发）、.imgmode single（普通/单张/逐张）、.imgmode auto（自动）";
    /** 未登记指令 {@code .admin}（不带参数）的回执原文：原有的"用法"报错，一字不差。 */
    static final String ADMIN_USAGE_TEXT = "操作失败：用法：.admin add <@成员|QQ号|群名片>、"
            + ".admin remove <@成员|QQ号|群名片>、.admin list";
    /** {@code .admin list} 回执原文（admin 名单为空时逐字稳定）。 */
    static final String ADMIN_LIST_TEXT = "owner：" + OWNER + "\nadmin（0）：（无）\n"
            + "admin 只能开关自己所在群聊的发言；其他管理指令仅 owner 可用。";
    /** {@code .get}（没有待领取图片）回执原文。 */
    static final String GET_TEXT = "暂无待领取图片，请先发送 .gen。";
    /** {@code .size} 在 SD 连不上时的回执原文（夹具固定指向 127.0.0.1:0，逐字稳定）。 */
    static final String SIZE_TEXT = "操作失败：Stable Diffusion 连接中断；操作结果未确认，请检查 WebUI。";
    /** 原来就不认的写法得到的回执：注册表必须同样不认（门不通过 → 回落老路）。 */
    static final String UNKNOWN_COMMAND_TEXT = "指令格式不正确，请发送 .help 查看用法。";

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"));
        Files.createDirectories(work);
        try (Fixture f = new Fixture(work)) {
            helpReceiptIsPinned(f);
            modeReceiptIsPinned(f);
            imgmodeReceiptIsPinned(f);
            unregisteredCommandsKeepTheLegacyPath(f);
        }
        registryHitCallsHandlerOnce();
        registryMissCallsFallback();
        gateMissFallsBack();
        handlerExceptionsPropagateUnchanged();
        contextComesFromTheEvent();
        resultShapesAreDistinct();
        registryRejectsBadNames();
        registryIsSafeForConcurrentReaders();
        check(assertions >= 50, "断言条数应 ≥ 50，实际 " + assertions);
        System.out.println("CommandRegistryTest: " + assertions + " assertions passed：.help/.mode/.imgmode 回执逐字与基线一致、"
                + "未登记指令（.admin/.get/.size）与原来不认的写法（.HELP/.modex）仍走原来的 dispatch、"
                + "注册表命中/未命中/门不通过、handler 异常原样冒泡、上下文归一、并发只读。");
    }

    // ---------------------------------------------------------------- ① 三条迁移指令的回执（基线）

    /** {@code .help}：回执必须是那一整份用法说明，一字不差（哈希锁定全文，首尾行防止哈希写错）。 */
    private static void helpReceiptIsPinned(Fixture f) throws Exception {
        String text = f.say("group", ".help").text();
        Evidence.print("help", text);
        check(text.equals(Bot.HELP), ".help 回执必须就是 Bot.HELP（publicCommands 幂等）");
        check(text.startsWith("神户小鸟 · Pixiko（SD 生图机器人）"), ".help 首行：" + firstLine(text));
        check(text.contains(".mode [normal|debug]"), ".help 必须仍列出 .mode 的用法");
        check(text.contains(".imgmode [record|single|auto]"), ".help 必须仍列出 .imgmode 的用法");
        check(text.stripTrailing().endsWith("留待下次 .get。"), ".help 末行：" + lastLine(text));
        check(HELP_SHA256.equals(Evidence.sha256(text)), ".help 回执哈希与基线不一致");
    }

    /** {@code .mode}（不带参数）：查看当前档位，默认 debug＝现状。 */
    private static void modeReceiptIsPinned(Fixture f) throws Exception {
        String text = f.say("group", ".mode").text();
        Evidence.print("mode", text);
        check(!text.isBlank() && text.contains("本会话的回执档位："), ".mode 回执：" + text);
        check(MODE_TEXT.equals(text), ".mode 回执与基线不一致：" + text);
    }

    /** {@code .imgmode}（不带参数）：查看当前发送形式，默认 auto＝现状。 */
    private static void imgmodeReceiptIsPinned(Fixture f) throws Exception {
        String text = f.say("group", ".imgmode").text();
        Evidence.print("imgmode", text);
        check(!text.isBlank() && text.contains("本会话的图片发送形式："), ".imgmode 回执：" + text);
        check(IMGMODE_TEXT.equals(text), ".imgmode 回执与基线不一致：" + text);
    }

    // ---------------------------------------------------------------- ② 未登记指令：仍然是原来的 dispatch

    /** 一条都没有登记进注册表的指令，回执必须和改动前一模一样。 */
    private static void unregisteredCommandsKeepTheLegacyPath(Fixture f) throws Exception {
        String usage = f.say("private", ".admin").text();
        Evidence.print("admin", usage);
        check(ADMIN_USAGE_TEXT.equals(usage), ".admin 的用法报错与基线不一致：" + usage);

        String list = f.say("private", ".admin list").text();
        Evidence.print("admin-list", list);
        check(ADMIN_LIST_TEXT.equals(list), ".admin list 回执与基线不一致：" + list);

        String get = f.say("private", ".get").text();
        Evidence.print("get", get);
        check(GET_TEXT.equals(get), ".get 回执与基线不一致：" + get);

        String size = f.say("private", ".size").text();
        Evidence.print("size", size);
        check(SIZE_TEXT.equals(size), ".size 回执与基线不一致：" + size);

        // 注册表的门必须与原来的判据一模一样：这两条本来就不认，现在也还是不认（回落老路）。
        String upper = f.say("private", ".HELP").text();
        Evidence.print("upper-help", upper);
        check(UNKNOWN_COMMAND_TEXT.equals(upper), ".HELP 仍然不认：" + upper);

        String lookalike = f.say("private", ".modex").text();
        Evidence.print("modex", lookalike);
        check(UNKNOWN_COMMAND_TEXT.equals(lookalike), ".modex 仍然不认：" + lookalike);
    }

    // ---------------------------------------------------------------- ③ 注册表/派发器本体

    /** 命中：handler 恰好调 1 次、fallback 一次都不调，handler 交给 dispatcher 的那句话由 replySink 发出。 */
    private static void registryHitCallsHandlerOnce() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        AtomicInteger handled = new AtomicInteger();
        List<String> spoken = new ArrayList<>();
        registry.register("demo", context -> { handled.incrementAndGet(); return CommandResult.text("你好"); });
        CommandDispatcher dispatcher = new CommandDispatcher(registry);
        AtomicInteger fallback = new AtomicInteger();
        CommandResult result = dispatcher.dispatch(contextOf("private", "/demo", spoken::add), () -> fallback.incrementAndGet());
        check(result.handled() && result.ok(), "命中注册表应返回 handled/ok：" + result);
        check(handled.get() == 1, "handler 应恰好被调用 1 次，实际 " + handled.get());
        check(fallback.get() == 0, "命中时绝不回落旧路径");
        check(spoken.equals(List.of("你好")), "handler 交出的 text 应由 dispatcher 发出，实际 " + spoken);
        check(dispatcher.handles(contextOf("private", "/demo", null)), "handles() 应认出这条指令");
    }

    /** 未命中：只调 fallback，返回 unhandled，且不产生任何回执。 */
    private static void registryMissCallsFallback() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        registry.register("demo", context -> CommandResult.replied());
        CommandDispatcher dispatcher = new CommandDispatcher(registry);
        AtomicInteger fallback = new AtomicInteger();
        List<String> spoken = new ArrayList<>();
        CommandResult result = dispatcher.dispatch(contextOf("private", "/size", spoken::add), () -> fallback.incrementAndGet());
        check(!result.handled() && result.equals(CommandResult.unhandled()), "未命中应返回 unhandled：" + result);
        check(fallback.get() == 1, "未命中应调用 fallback 恰好 1 次，实际 " + fallback.get());
        check(spoken.isEmpty(), "未命中时 dispatcher 不许自己发消息");
        check(registry.find("SIZE") == null, "查表大小写敏感：/SIZE 不是 /size");
    }

    /** 门不通过 = 没命中：{@code /mode fast} 这种写法仍然回落老路。 */
    private static void gateMissFallsBack() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        AtomicInteger handled = new AtomicInteger(), fallback = new AtomicInteger();
        registry.register("mode", context -> context.raw().equals("/mode"),
                context -> { handled.incrementAndGet(); return CommandResult.replied(); });
        CommandDispatcher dispatcher = new CommandDispatcher(registry);
        CommandResult miss = dispatcher.dispatch(contextOf("private", "/mode fast"), () -> fallback.incrementAndGet());
        check(!miss.handled() && handled.get() == 0 && fallback.get() == 1, "门不通过必须回落，不许调 handler");
        CommandResult hit = dispatcher.dispatch(contextOf("private", "/mode"), () -> fallback.incrementAndGet());
        check(hit.handled() && handled.get() == 1 && fallback.get() == 1, "门通过后只调 handler，不再回落");
        registry.register("boom", context -> { throw new IllegalStateException("gate 自己炸了"); },
                context -> CommandResult.replied());
        check(!dispatcher.handles(contextOf("private", "/boom")), "门自己抛异常按没命中处理，不改变指令走向");
    }

    /** handler 抛出的异常原样冒泡（同一个实例）；命中路径也绝不回落。 */
    private static void handlerExceptionsPropagateUnchanged() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        IllegalStateException boom = new IllegalStateException("boom");
        registry.register("explode", context -> { throw boom; });
        CommandDispatcher dispatcher = new CommandDispatcher(registry);
        AtomicInteger fallback = new AtomicInteger();
        try {
            dispatcher.dispatch(contextOf("private", "/explode"), () -> fallback.incrementAndGet());
            check(false, "handler 的异常必须冒泡");
        } catch (IllegalStateException thrown) {
            check(thrown == boom, "异常必须是原来那一个实例（不包一层）");
        }
        check(fallback.get() == 0, "命中后即使抛异常也不回落");
    }

    /** 上下文字段全部来自事件：会话/用户/参数/是否网页。 */
    private static void contextComesFromTheEvent() {
        CommandContext group = contextOf("group", "/mode normal");
        check(group.conversation().scope() == Scope.GROUP && group.conversation().id().equals("999"),
                "群会话：" + group.conversation());
        check(group.sender().equals(new UserId(OWNER)), "发送者：" + group.sender());
        check(group.command().equals("mode") && group.args().equals(List.of("normal")),
                "指令名与参数：" + group.command() + " " + group.args());
        check(!group.fromWeb(), "QQ 事件不是网页事件");
        CommandContext priv = contextOf("private", "/help");
        check(priv.conversation().scope() == Scope.PRIVATE && priv.command().equals("help") && priv.args().isEmpty(),
                "私聊会话：" + priv.conversation() + " " + priv.args());
        JsonObject web = contextOf("private", "/help").event();
        web.addProperty("webui", true);
        check(CommandContext.of(web, "/help", null).fromWeb(), "带 webui 属性的事件应识别为网页会话");
        check(CommandContext.of(web, "/help", null).conversation().scope() == Scope.WEB, "网页会话的 scope 是 WEB");
        check(CommandContext.of(null, "/help", null).conversation().unknown(), "null 事件归一成占位会话，不抛");
        try {
            group.args().add("x");
            check(false, "args 必须是只读副本");
        } catch (UnsupportedOperationException expected) {
            check(true, "args 只读");
        }
    }

    /** 三种结果形态互斥，且非法组合当场报错。 */
    private static void resultShapesAreDistinct() {
        check(!CommandResult.unhandled().handled() && CommandResult.unhandled().text() == null, "unhandled 不带 text");
        check(CommandResult.replied().ok() && CommandResult.replied().text() == null, "replied 表示已经回过了、不再发");
        check(CommandResult.text("hi").ok() && CommandResult.text("hi").text().equals("hi"), "text 形态");
        CommandError usage = CommandError.of(CommandError.Code.USAGE, "用法");
        check(CommandResult.failed(usage).error().equals(usage) && !CommandResult.failed(usage).ok(), "failed 形态");
        try {
            new CommandResult("x", true, usage);
            check(false, "text 与 error 不能同时存在");
        } catch (IllegalArgumentException expected) {
            check(true, "text 与 error 互斥");
        }
    }

    /** 名字写错当场报错；names() 是只读快照。 */
    private static void registryRejectsBadNames() {
        CommandRegistry registry = new CommandRegistry();
        for (String bad : List.of("", " ", "/help", ".help", "mode fast")) {
            try {
                registry.register(bad, context -> CommandResult.replied());
                check(false, "应拒绝非法指令名：" + bad);
            } catch (IllegalArgumentException expected) {
                check(true, "拒绝非法指令名：" + bad);
            }
        }
        check(registry.names().isEmpty() && registry.find("help") == null, "被拒绝的登记不能留下痕迹");
        registry.register("ok", context -> CommandResult.replied());
        check(registry.names().equals(java.util.Set.of("ok")), "names()：" + registry.names());
        try {
            registry.names().add("x");
            check(false, "names() 必须是只读快照");
        } catch (UnsupportedOperationException expected) {
            check(true, "names() 只读");
        }
    }

    /** 并发只读：多线程同时 find 不抛；register 之后新名字立刻可见。 */
    private static void registryIsSafeForConcurrentReaders() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        registry.register("base", context -> CommandResult.replied());
        CommandDispatcher dispatcher = new CommandDispatcher(registry);
        int threads = 8, rounds = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < threads; index++) {
                final int reader = index;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        CommandContext base = contextOf("private", "/base");
                        for (int round = 0; round < rounds; round++) {
                            if (registry.find("base") == null) throw new AssertionError("base 在并发读时消失了");
                            if (registry.find("never") != null) throw new AssertionError("未登记的名字查出了 handler");
                            registry.names();
                            dispatcher.handles(base);
                            if (reader == 0 && round == rounds / 2) registry.register("late", context -> CommandResult.replied());
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        check(failure.get() == null, "并发 find 不应抛异常：" + failure.get());
        check(registry.names().contains("late"), "register 之后再 find/names 立刻可见");
        check(registry.find("late") != null && registry.size() == 2, "注册表大小：" + registry.size());
    }

    /** 不经过 Bot 的上下文：只读事件、不抛。 */
    static CommandContext contextOf(String type, String raw) {
        return contextOf(type, raw, null);
    }

    static CommandContext contextOf(String type, String raw, Consumer<String> replySink) {
        JsonObject event = new JsonObject();
        event.addProperty("post_type", "message");
        event.addProperty("message_type", type);
        event.addProperty("user_id", OWNER);
        event.addProperty("self_id", 777);
        if (type.equals("group")) event.addProperty("group_id", 999);
        event.add("message", Maps.text(raw));
        return CommandContext.of(event, raw, replySink);
    }

    // ---------------------------------------------------------------- 夹具与工具

    /** 出站消息：文本与图片都走同一条路，这里只把 (event, segments) 抄一份放进队列。 */
    record Reply(JsonObject event, JsonArray segments) {
        String text() { return Bot.messageText(segments); }
    }

    /**
     * 一个真 Bot＋一个只记录出站消息的传输层＋一个临时根目录。根目录在 {@code -Dbot.test.work} 下，
     * SD 指向 {@code 127.0.0.1:0}（没有监听者，连不上就是连不上），全程不打开任何真实端口。
     */
    static final class Fixture implements AutoCloseable {
        final Path root;
        final Bot bot;
        final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();

        Fixture(Path work) throws Exception {
            root = Files.createTempDirectory(work, "command-registry-");
            JsonObject config = Json.parse("{\"owner_user_id\":\"" + OWNER + "\",\"admin_user_ids\":[],"
                    + "\"sd\":{\"base_url\":\"http://127.0.0.1:0\",\"timeout_seconds\":2}}");
            Json.atomicWrite(root.resolve("config.json"), config);
            bot = new Bot(new Settings(root), new SdClient(root, Json.obj(config, "sd")), new Bot.Sender() {
                public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                    replies.add(new Reply(event.deepCopy(), segments.deepCopy()));
                    return CompletableFuture.completedFuture(null);
                }
            });
        }

        /** 造一条 QQ 消息事件；message_id 每条都不同（重复事件会被机器人自己丢掉）。 */
        JsonObject event(String type, String text) {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", type);
            event.addProperty("user_id", OWNER);
            event.addProperty("self_id", 777);
            event.addProperty("message_id", IDS.incrementAndGet());
            if (type.equals("group")) event.addProperty("group_id", 999);
            event.add("message", Maps.text(text));
            return event;
        }

        /** 发一条指令，取它产生的那条回执正文。 */
        Reply say(String type, String text) throws Exception {
            bot.accept(event(type, text));
            Reply reply = replies.poll(7, TimeUnit.SECONDS);
            if (reply == null) throw new AssertionError("回执超时：" + text);
            return reply;
        }

        public void close() throws Exception { bot.close(); }
    }

    /** 回执原文的证据输出：基线跑与改动后跑各留一份，用来做逐字 diff（也防止只靠断言看漏）。 */
    static final class Evidence {
        static void print(String label, String text) {
            System.out.println("[receipt " + label + "] sha256=" + sha256(text) + " len=" + text.length());
            System.out.println("[receipt " + label + " begin]");
            System.out.println(text);
            System.out.println("[receipt " + label + " end]");
        }

        static String sha256(String text) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    static String lastLine(String text) {
        int start = text.lastIndexOf('\n');
        return start < 0 ? text : text.substring(start + 1);
    }

    static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}

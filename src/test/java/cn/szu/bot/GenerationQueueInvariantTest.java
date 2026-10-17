package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.imageio.ImageIO;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;

/**
 * 生成队列计数与状态机的不变量测试（Phase 1）。
 *
 * <p>只读用户/前端能看到的两样东西：{@code .gen status} 的那条状态行，和 {@code webTasks()} 的任务快照
 * （{@code /api/tasks} 就是这两样的输出）。不碰生产 {@code config.json}/{@code data}/{@code logs}，
 * 不连 8787/3001/7860/7861，不杀任何进程。
 *
 * <p>隔离：每个用例一个 {@code <bot.test.work>/tests/<uuid>/} 根目录，SD 桩用 {@code ServerSocket(0)}
 * 的临时端口，收尾先等目录安静再删。SD 请求由桩卡住，测试用 {@link Fixture#call()} 精确控制
 * 「第几张已经在下发」，于是计数能在确定的时刻被读出来。
 *
 * <p>用例 1–3 是 Phase 1 的特征测试：它们先按**修复前**的行为变绿（等待 -1 / 合计 -4 / 挂起虚高 2），
 * 修复后同一段操作序列按**新契约**断言（计数 ≥0、不虚高、只结算一次）。下面每个用例的注释里都记着
 * 旧行为是什么、为什么错。
 */
public final class GenerationQueueInvariantTest {
    private static int assertions;
    private record Text(JsonObject event, String text) {}
    private record Call(JsonObject payload, CompletableFuture<Integer> finish) {}
    /** 状态行里的三个计数：等待 / 挂起（只在 >0 时显示）/ 合计。 */
    private record Counts(BigInteger waiting, BigInteger suspended, BigInteger total) {
        boolean nonNegative() { return waiting.signum() >= 0 && suspended.signum() >= 0 && total.signum() >= 0; }
        @Override public String toString() { return "等待 " + waiting + "，挂起 " + suspended + "，合计 " + total; }
    }
    private static final Pattern COUNTS = Pattern.compile("等待 (-?\\d+) 个(?:（另有挂起 (-?\\d+) 个）)?，合计 (-?\\d+) 个");
    private static final Pattern FINISHED = Pattern.compile("(?m)^任务 #(\\d+)：");

    public static void main(String[] args) throws Exception {
        cancelAllAfterTheInFlightImage();
        holdThenCancelAll();
        firstOnHeldJob();
        cancelAllOnMixedQueue();
        concurrentCancelAllSubmitHoldResume();
        closeCancelsCleanly();
        settleIsIdempotent();
        webTaskSnapshotKeepsApiShape();
        System.out.println("GenerationQueueInvariantTest: " + assertions
                + " assertions passed: cancel-all / hold+cancel-all / 挂起任务置顶 / 混合队列 / 8 线程并发 / 停机 / 只结算一次 / /api/tasks 格式不变。");
    }

    /**
     * 用例 1：{@code .gen 2} 跑第 1 张时 {@code cancel all}，再让这一张结算。
     *
     * <p>CURRENT-BEHAVIOR / BUG（Phase 1 前）：cancel all 只把两个全局计数清零、不清 {@code job.remaining}，
     * 工作线程结算时又按 remaining 减一次，于是用户看到「等待 -1 个，合计 -1 个」。修复后：等待 0。
     */
    private static void cancelAllAfterTheInFlightImage() throws Exception {
        try (Fixture f = new Fixture()) {
            f.edit("A", 512, "Euler a", List.of());
            f.command("private", ".gen 2");
            Call first = f.call();
            equal(new Counts(BigInteger.ONE, BigInteger.ZERO, BigInteger.TWO), f.counts(), "第 1 张下发后：等待 1，合计 2");
            String all = f.bot.webTaskAction("cancel", "all").get("message").getAsString();
            check(all.contains("全部任务") && all.contains("共 1 次待生成"), "cancel all 回执不变：" + all);
            equal(new Counts(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE), f.counts(),
                    "cancel all 之后立刻：等待 0（这一张还在下发，合计含它）");
            first.finish().complete(200);
            f.awaitIdle();
            Counts after = f.counts();
            check(after.nonNegative(), "结算后计数非负（旧行为是等待 -1）：" + after);
            equal(BigInteger.ZERO, after.waiting(), "结算后等待计数回到 0（旧行为 -1）");
            equal(BigInteger.ZERO, after.total(), "结算后合计回到 0（旧行为 -1）");
            check(!f.statusText().contains(" -"), "状态行里没有负数：" + f.statusText());
        }
    }

    /**
     * 用例 2：挂起正在生成的任务后再 {@code cancel all}。
     *
     * <p>CURRENT-BEHAVIOR / BUG（Phase 1 前）：两个全局计数先被清零，结算时 {@code suspended} 与
     * {@code waiting} 各再减一次 → 两个池同时变负（等待 -2、挂起 -2，状态行不显示负的挂起，合计 -4 露馅）。
     * 修复后：两个池都回到 0，挂起也不再虚高。
     */
    private static void holdThenCancelAll() throws Exception {
        try (Fixture f = new Fixture()) {
            f.edit("A", 512, "Euler a", List.of());
            f.command("private", ".gen 3");
            Call first = f.call();
            check(f.bot.webTaskAction("hold", "1").get("message").getAsString().contains("已挂起"), "挂起在飞的任务");
            equal(new Counts(BigInteger.ZERO, BigInteger.TWO, BigInteger.valueOf(3)), f.counts(), "挂起后：等待 0，挂起 2，合计 3");
            String all = f.bot.webTaskAction("cancel", "all").get("message").getAsString();
            check(all.contains("共 2 次待生成"), "cancel all 回执里的 freed 不变：" + all);
            equal(new Counts(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE), f.counts(),
                    "cancel all 之后立刻：挂起的 2 次也结清，只剩在飞的这一张");
            first.finish().complete(200);
            f.awaitIdle();
            Counts after = f.counts();
            check(after.nonNegative(), "两个池都不为负（旧行为 等待 -2 / 挂起 -2）：" + after);
            equal(BigInteger.ZERO, after.waiting(), "结算后等待 0");
            equal(BigInteger.ZERO, after.suspended(), "结算后挂起 0");
            check(!f.statusText().contains("另有挂起"), "队列空了就不再显示挂起：" + f.statusText());
        }
    }

    /**
     * 用例 3：对挂起任务做 {@code first}（= 接着跑）。
     *
     * <p>CURRENT-BEHAVIOR / BUG（Phase 1 前）：{@code first} 只清 {@code suspended} 标志、不把 remaining
     * 从挂起池搬回等待池 → 挂起计数永久虚高 2，等待池被多扣成 -2（队列空了还写着「另有挂起 2 个」）。
     * 修复后：置顶就搬移计数，跑完两个池都是 0。
     */
    private static void firstOnHeldJob() throws Exception {
        try (Fixture f = new Fixture()) {
            f.edit("A", 512, "Euler a", List.of());
            f.command("private", ".gen 3");
            Call first = f.call();
            check(f.bot.webTaskAction("hold", "1").get("message").getAsString().contains("已挂起"), "先挂起");
            String promoted = f.bot.webTaskAction("first", "1").get("message").getAsString();
            check(promoted.contains("#1"), "置顶回执：" + promoted);
            equal(new Counts(BigInteger.TWO, BigInteger.ZERO, BigInteger.valueOf(3)), f.counts(),
                    "置顶后：剩余 2 次从挂起池搬回等待池（这一张仍在飞，合计 +1）");
            first.finish().complete(200);
            f.call().finish().complete(200);
            f.call().finish().complete(200);
            f.awaitIdle();
            check(f.bot.webTasks().isEmpty(), "队列排空：" + f.bot.webTasks());
            Counts after = f.counts();
            check(after.nonNegative(), "计数非负（旧行为 等待 -2 / 挂起 +2）：" + after);
            equal(BigInteger.ZERO, after.suspended(), "挂起计数不虚高（旧行为残留 2）");
            equal(BigInteger.ZERO, after.waiting(), "等待计数回到 0（旧行为 -2）");
        }
    }

    /**
     * 用例 4：等待中 + 运行中 + 挂起的混合队列，一次性 {@code cancel all}。
     * 断言等待/挂起/合计全部非负、每个任务的完成次数不超过总次数、最终队列空且计数为 0。
     */
    private static void cancelAllOnMixedQueue() throws Exception {
        try (Fixture f = new Fixture()) {
            f.edit("A", 512, "Euler a", List.of());
            f.command("private", ".gen 3");                       // #1：开始跑，剩 2
            Call first = f.call();
            f.command("private", ".gen 2");                       // #2：排队 2
            f.command("private", ".gen 2");                       // #3：排队 2
            check(f.bot.webTaskAction("hold", "3").get("message").getAsString().contains("已挂起"), "挂起 #3");
            equal(new Counts(BigInteger.valueOf(4), BigInteger.TWO, BigInteger.valueOf(7)), f.counts(),
                    "混合队列：等待 4（#1 剩 2 + #2 2），挂起 2，合计 7");
            String all = f.bot.webTaskAction("cancel", "all").get("message").getAsString();
            check(all.contains("3 个") && all.contains("共 6 次待生成"), "cancel all 统计三个任务、6 次：" + all);
            Counts mid = f.counts();
            equal(BigInteger.ZERO, mid.waiting(), "cancel all 立刻结清等待池：" + mid);
            equal(BigInteger.ZERO, mid.suspended(), "cancel all 立刻结清挂起池：" + mid);
            equal(BigInteger.ONE, mid.total(), "只剩在飞的那一张：" + mid);
            String midStatus = f.statusText();
            check(midStatus.contains("生成中（取消待生效）"), "在飞的任务标取消待生效：" + midStatus);
            first.finish().complete(200);
            f.awaitIdle();
            Counts after = f.counts();
            check(after.nonNegative(), "全部结算后计数非负：" + after);
            equal(0, after.waiting().signum() + after.suspended().signum() + after.total().signum(), "三个计数都是 0：" + after);
            check(f.bot.webTasks().isEmpty(), "队列排空：" + f.bot.webTasks());
            String status = f.statusText();
            check(!status.contains(" -"), "状态行里没有负数：" + status);
            // done/total 都是正数字段：完成次数不可能超过总次数。
            for (String pair : status.split("\\s+")) {
                if (!pair.matches("\\d+/\\d+")) continue;
                String[] parts = pair.split("/");
                check(Integer.parseInt(parts[0]) <= Integer.parseInt(parts[1]), "完成次数不超过总次数：" + pair + "（" + status + "）");
            }
            equal(3, finishedNumbers(status).size(), "三个任务都留下了完成记录：" + status);
        }
    }

    /**
     * 用例 5：8 个线程同时下单 / cancel all / hold / resume / first，外加一个线程把 SD 请求收掉。
     * 断言没有异常抛出、计数非负、队列排空后计数为 0、每个任务在"已完成"里只出现一次（只结算一次）。
     */
    private static void concurrentCancelAllSubmitHoldResume() throws Exception {
        try (Fixture f = new Fixture()) {
            f.edit("A", 512, "Euler a", List.of());
            ExecutorService pool = Executors.newFixedThreadPool(8, r -> { Thread t = new Thread(r, "invariant-op"); t.setDaemon(true); return t; });
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread completer = new Thread(() -> {
                while (running.get()) {
                    try {
                        Call call = f.calls.poll(50, TimeUnit.MILLISECONDS);
                        if (call != null) call.finish().complete(200);
                    } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
                    catch (RuntimeException ignored) { }
                }
            }, "invariant-completer");
            completer.setDaemon(true);
            completer.start();
            try {
                for (int index = 0; index < 8; index++) {
                    final int worker = index;
                    pool.execute(() -> {
                        for (int round = 0; round < 10 && running.get(); round++) {
                            try {
                                switch ((worker + round) % 5) {
                                    case 0, 1 -> f.bot.accept(event("private", ".gen 2"));
                                    case 2 -> f.bot.webTaskAction("cancel", "all");
                                    case 3 -> {
                                        String number = f.firstNumber();
                                        if (number != null) f.bot.webTaskAction("hold", number);
                                    }
                                    default -> {
                                        String number = f.firstNumber();
                                        if (number != null) {
                                            f.bot.webTaskAction("resume", number);
                                            f.bot.webTaskAction("first", number);
                                        }
                                    }
                                }
                            } catch (Throwable error) { failures.add(error); }
                        }
                    });
                }
                pool.shutdown();
                check(pool.awaitTermination(120, TimeUnit.SECONDS), "8 个线程都在预算内结束");
                f.awaitIdle();
            } finally {
                running.set(false);
                completer.join(TimeUnit.SECONDS.toMillis(5));
            }
            check(failures.isEmpty(), "并发操作没有异常：" + failures);
            Counts after = f.counts();
            check(after.nonNegative(), "并发之后计数非负：" + after);
            equal(BigInteger.ZERO, after.waiting(), "并发之后等待 0");
            equal(BigInteger.ZERO, after.suspended(), "并发之后挂起 0");
            equal(BigInteger.ZERO, after.total(), "并发之后合计 0");
            check(f.bot.webTasks().isEmpty(), "队列排空：" + f.bot.webTasks());
            String status = f.statusText();
            List<String> finished = finishedNumbers(status);
            // 同一个任务进两次"已完成"就说明被结算了两次（Phase 1 前 cancel all 与工作线程会双双结算）。
            equal(finished.size(), new HashSet<>(finished).size(), "没有任务被重复结算：" + finished + " / " + status);
            check(!status.contains(" -"), "状态行里没有负数：" + status);
        }
    }

    /** 用例 6：{@code close()} 时队列里还有任务（含在飞的一张）→ 计数非负、清零。 */
    private static void closeCancelsCleanly() throws Exception {
        Fixture f = new Fixture();
        try {
            f.edit("A", 512, "Euler a", List.of());
            f.command("private", ".gen 3");
            Call first = f.call();
            f.command("private", ".gen 2");
            check(f.counts().waiting().compareTo(BigInteger.TWO) >= 0, "停机前队列里还有等待：" + f.counts());
            f.bot.close();
            first.finish().complete(500);
            Counts after = f.counts();
            check(after.nonNegative(), "停机后计数非负：" + after);
            equal(BigInteger.ZERO, after.waiting(), "停机后等待 0");
            equal(BigInteger.ZERO, after.suspended(), "停机后挂起 0");
            check(!f.statusText().contains(" -"), "停机后状态行里没有负数：" + f.statusText());
        } finally { f.close(); }
    }

    /**
     * 用例 7：同一个任务只结算一次。自然结算之后再走一次私有的 {@code settleGenerationJob}，
     * 必须直接返回 null、不二次改计数、不二次留痕（"已完成"列表里该任务仍然只有一条）。
     */
    private static void settleIsIdempotent() throws Exception {
        try (Fixture f = new Fixture()) {
            f.edit("A", 512, "Euler a", List.of());
            f.command("private", ".gen 1");
            f.call().finish().complete(200);
            f.awaitIdle();
            equal(new Counts(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO), f.counts(), "一个任务跑完计数归零");
            equal(1, finishedNumbers(f.statusText()).size(), "完成记录一条");
            Field finished = Bot.class.getDeclaredField("finishedJobs");
            finished.setAccessible(true);
            Object job = ((Deque<?>) finished.get(f.bot)).peekFirst();
            check(job != null, "已完成列表里有任务 #1");
            Method settle = Bot.class.getDeclaredMethod("settleGenerationJob", Class.forName("cn.szu.bot.Bot$GenerationJob"), String.class);
            settle.setAccessible(true);
            Object again = settle.invoke(f.bot, job, "已完成");
            check(again == null, "二次结算直接返回 null（不再发回执）：" + again);
            equal(new Counts(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO), f.counts(), "二次结算不再改计数");
            equal(1, finishedNumbers(f.statusText()).size(), "二次结算不再留痕（完成记录仍是一条）");
        }
    }

    /**
     * 用例 8：{@code /api/tasks} 的输出格式（{@code webTasks()} 就是它的返回值）逐字不变——
     * 字段名、字段顺序、值类型、状态文案都必须和 Phase 1 之前一模一样，只让计数变正确。
     */
    private static void webTaskSnapshotKeepsApiShape() throws Exception {
        try (Fixture f = new Fixture()) {
            f.edit("A", 512, "Euler a", List.of());
            f.command("private", ".gen 2");
            f.call();
            f.command("private", ".gen 2");
            equal("[" + task(1, "1", true, false, false, "生成中") + "," + task(2, "2", false, false, false, "等待中") + "]",
                    f.bot.webTasks().toString(), "/api/tasks：运行中 + 等待中的任务卡格式不变");
            f.bot.webTaskAction("hold", "2");
            equal(task(2, "2", false, true, false, "已挂起"), taskJson(f, 1), "/api/tasks：挂起任务卡格式不变");
            f.bot.webTaskAction("hold", "1");
            equal(task(1, "1", true, true, false, "生成中（挂起待生效）"), taskJson(f, 0),
                    "/api/tasks：在飞且挂起待生效的格式不变");
            f.bot.webTaskAction("cancel", "all");
            // 字段名/顺序/类型/状态文案都不变；唯一变化是"已取消"的任务不再同时报 suspended=true——
            // 旧实现留着那个非法组合（挂起+取消同时为真），正是 Phase 1 要消掉的状态歧义。
            equal("[" + task(1, "1", true, false, true, "生成中（取消待生效）") + "]", f.bot.webTasks().toString(),
                    "/api/tasks：取消待生效的格式不变（被摘掉的排队任务不再出现在快照里）");
        }
    }

    /** 旧格式的一张任务卡（字段顺序与类型逐字一致）。 */
    private static String task(int slot, String number, boolean running, boolean suspended, boolean cancelled, String status) {
        return "{\"slot\":" + slot + ",\"number\":\"" + number + "\",\"done\":\"0\",\"total\":\"2\",\"images\":\"0\","
                + "\"failed\":\"0\",\"running\":" + running + ",\"suspended\":" + suspended + ",\"cancelled\":" + cancelled
                + ",\"percent\":0,\"status\":\"" + status + "\"}";
    }
    private static String taskJson(Fixture f, int index) {
        return f.bot.webTasks().get(index).getAsJsonObject().toString();
    }

    private static List<String> finishedNumbers(String status) {
        List<String> numbers = new ArrayList<>();
        Matcher matcher = FINISHED.matcher(status);
        while (matcher.find()) numbers.add(matcher.group(1));
        return numbers;
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }
    private static byte[] png(int color) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB); image.setRGB(0, 0, color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
    }
    private static final java.util.concurrent.atomic.AtomicInteger IDS = new java.util.concurrent.atomic.AtomicInteger();
    private static JsonObject event(String type, String command) {
        JsonObject event = new JsonObject(); event.addProperty("post_type", "message"); event.addProperty("message_type", type);
        event.addProperty("self_id", 777); event.addProperty("user_id", 456); event.addProperty("message_id", IDS.incrementAndGet());
        if (type.equals("group")) event.addProperty("group_id", 999);
        event.add("message", Maps.text(command)); return event;
    }

    /** 一个用例：独立临时根目录 + 独立端口的 SD 桩 + 真 Bot/SdClient。 */
    private static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
        final ExecutorService executor;
        final SdClient client;
        final JsonObject sdConfig = new JsonObject();
        final JsonObject state = Json.parse("{\"positive\":\"initial\",\"negative\":\"negative\",\"source\":\"webui-live\",\"revision\":1,\"sampler_name\":\"Euler a\",\"styles\":[],\"width\":512,\"height\":512,\"settings_initialized\":true}");
        final BlockingQueue<Text> texts = new LinkedBlockingQueue<>();
        final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
        final List<CompletableFuture<?>> outstanding = new CopyOnWriteArrayList<>();
        final String image;
        final Bot bot;
        Fixture() throws Exception {
            Path parent = Path.of(System.getProperty("bot.test.work", "work"), "tests").toAbsolutePath();
            Files.createDirectories(parent);
            root = parent.resolve(UUID.randomUUID().toString());
            Files.createDirectories(root);
            image = Base64.getEncoder().encodeToString(png(0x336699));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "invariant-sd-mock"); t.setDaemon(true); return t; });
            server.setExecutor(executor); server.createContext("/", this::handle); server.start();
            sdConfig.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort()); sdConfig.addProperty("timeout_seconds", 10);
            JsonObject cfg = new JsonObject(); cfg.add("sd", sdConfig); cfg.addProperty("gen_auto_get", false);
            Json.atomicWrite(root.resolve("config.json"), cfg);
            client = new SdClient(root, sdConfig);
            // 提示词是个人档：这些用例只测队列，给调用者一份自己的副本。
            UserPromptStore prompts = new UserPromptStore(root);
            for (String scope : List.of("456", Settings.DEFAULT_OWNER))
                prompts.seed(scope, new SdClient.Prompts("invariant test prompt", "invariant test negative", UserPromptStore.PERSONAL_SOURCE));
            bot = new Bot(new Settings(root), client, (event, segments) -> {
                if (segments.get(0).getAsJsonObject().get("type").getAsString().equals("image")) {
                    CompletableFuture<Void> ack = new CompletableFuture<>(); outstanding.add(ack); return ack;
                }
                texts.add(new Text(event.deepCopy(), Bot.messageText(segments)));
                return CompletableFuture.completedFuture(null);
            });
        }
        synchronized void edit(String prompt, int width, String sampler, List<String> styles) throws IOException {
            state.addProperty("positive", prompt); state.addProperty("negative", prompt + " negative"); state.addProperty("width", width);
            state.addProperty("sampler_name", sampler); state.add("styles", Json.GSON.toJsonTree(styles));
            state.addProperty("revision", state.get("revision").getAsInt() + 1);
            UserPromptStore prompts = new UserPromptStore(root);
            for (String scope : List.of("456", Settings.DEFAULT_OWNER))
                prompts.replace(scope, new SdClient.Prompts(prompt, prompt + " negative", UserPromptStore.PERSONAL_SOURCE));
        }
        String command(String type, String command) throws Exception {
            bot.accept(event(type, command)); Text text = text();
            equal(type, text.event().get("message_type").getAsString(), "文本回执仍是原来的会话：" + command);
            return text.text();
        }
        Text text() throws Exception { Text text = texts.poll(7, TimeUnit.SECONDS); check(text != null, "等一条文本回执"); return text; }
        Call call() throws Exception { Call call = calls.poll(7, TimeUnit.SECONDS); check(call != null, "等一次 SD 请求"); return call; }
        /** 队列里第一个任务的编号（并发用例里可能恰好没有任务）。 */
        String firstNumber() {
            JsonArray tasks = bot.webTasks();
            return tasks.isEmpty() ? null : tasks.get(0).getAsJsonObject().get("number").getAsString();
        }
        /** 只留这一次查询的回执：先抽干旧文本再查状态。 */
        String statusText() throws Exception {
            while (texts.poll() != null) { }
            return command("private", ".gen status");
        }
        Counts counts() throws Exception {
            String text = statusText();
            Matcher matcher = COUNTS.matcher(text);
            check(matcher.find(), "状态行里能找到三个计数：" + text);
            return new Counts(new BigInteger(matcher.group(1)),
                    new BigInteger(matcher.group(2) == null ? "0" : matcher.group(2)),
                    new BigInteger(matcher.group(3)));
        }
        void awaitIdle() throws Exception {
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline) {
                if (bot.webTasks().isEmpty() && statusText().contains("空闲")) return;
                Thread.sleep(50);
            }
            throw new AssertionError("队列没有在 20 秒内排空：" + statusText() + " / " + bot.webTasks());
        }
        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/pixiko-bridge/v1/prompts")) {
                    synchronized (this) { send(exchange, 200, state.toString()); }
                } else if (path.equals("/sdapi/v1/txt2img")) {
                    JsonObject payload = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    CompletableFuture<Integer> finish = new CompletableFuture<>(); outstanding.add(finish); calls.add(new Call(payload, finish));
                    int status;
                    try { status = finish.get(30, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); status = 500; }
                    catch (ExecutionException | TimeoutException e) { status = 500; }
                    send(exchange, status, status == 200 ? "{\"images\":[\"" + image + "\"]}" : "{\"detail\":\"mock generation failure\"}");
                } else if (path.equals("/sdapi/v1/interrupt")) {
                    // 真的接受中断：取消路径不必等 5 秒确认（桩里这一张由测试自己收尾）。
                    exchange.getRequestBody().readAllBytes();
                    send(exchange, 200, "{}");
                } else if (path.equals("/sdapi/v1/progress")) {
                    send(exchange, 200, "{\"progress\":0,\"eta_relative\":0,\"state\":{\"job\":\"\",\"job_count\":0,"
                            + "\"sampling_step\":0,\"sampling_steps\":0}}");
                } else send(exchange, 404, "{}");
            } finally { exchange.close(); }
        }
        static void send(HttpExchange exchange, int status, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }
        public void close() {
            try { bot.close(); } catch (RuntimeException ignored) { }
            outstanding.forEach(future -> future.cancel(true));
            server.stop(0); executor.shutdownNow();
            TestCleanup.awaitQuiet(root, 2000);
            TestCleanup.deleteQuietly(root);
        }
    }
}

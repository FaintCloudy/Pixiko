package cn.szu.bot;

import com.google.gson.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.chat.deepseek.DeepSeekError;
import cn.szu.bot.chat.deepseek.DeepSeekRequest;
import cn.szu.bot.chat.deepseek.DeepSeekResponse;
import cn.szu.bot.chat.deepseek.DeepSeekTransport;
import cn.szu.bot.chat.deepseek.HttpDeepSeekTransport;
import cn.szu.bot.chat.deepseek.RetryPolicy;
import cn.szu.bot.sd.SdClient;

/**
 * 传输层的验收：通道隔离、取消语义、重试策略、上限。全部走可注入的 transport 与假睡眠器，
 * 不打网络、不真等、不打印任何密钥。
 */
public final class DeepSeekTransportTest {

    /** 假密钥：任何断言失败信息里只能出现"有密钥"，不能出现它本身。 */
    private static final String KEY = "fixture-transport-key-never-log";
    private static final String KEY_FILE = "data/deepseek-api-key.txt";

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "deepseek-transport-tests").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve(KEY_FILE), KEY);
        Files.writeString(root.resolve("config.json"), "{}");
        try {
            reviewIsolation(root);
            reviewIsolatedInProductionWiring(root);
            cancellation(root);
            nonRetryable(root);
            retryable(root);
            caps(root);
            redaction(root);
        } finally {
            TestCleanup.deleteQuietly(root);
        }
        System.out.println("DeepSeekTransportTest PASS：REVIEW 走独立 transport、取消不清标记且不重试、"
                + "401/402/普通 4xx/JSON 错误一次即止、连接/超时/429/503 按退避重试、尝试数与总时长封顶、密钥不进任何输出。");
    }

    // ------------------------------------------------------------------ 通道隔离

    /** 改写（IMAGE/CHAT）与画面检查（REVIEW）是两条 transport：各自的计数绝不串。 */
    private static void reviewIsolation(Path root) throws Exception {
        AtomicInteger rewrites = new AtomicInteger();
        AtomicInteger reviews = new AtomicInteger();
        DeepSeekTransport rewrite = (body, key, timeout) -> {
            check(KEY.equals(key), "transport 拿到的是这条通道的密钥");
            rewrites.incrementAndGet();
            return completion("{\"positive\":\"red bicycle, rainy night\",\"negative\":\"blur\"}");
        };
        DeepSeekTransport review = (body, key, timeout) -> {
            reviews.incrementAndGet();
            return completion("{\"ok\":true,\"conflicts\":[],\"added\":[]}");
        };
        DeepSeekPrompts client = new DeepSeekPrompts(root, new JsonObject(), rewrite, review,
                query -> "", KEY_FILE, RetryPolicy.defaults().withSleeper(duration -> { }));
        client.generate("雨夜的自行车");
        check(rewrites.get() == 1 && reviews.get() == 0, "改写只落在它自己的 transport 上：" + rewrites.get() + "/" + reviews.get());
        DeepSeekPrompts.Review result = client.review(new SdClient.Prompts("red bicycle, rainy night", "blur", "user"));
        check(result.usable() && result.ok(), "画面检查拿到的是检查那条 transport 的响应");
        check(reviews.get() == 1 && rewrites.get() == 1,
                "画面检查只落在 REVIEW 那条上（改写=" + rewrites.get() + "，检查=" + reviews.get() + "）");
        client.generate("再改一次");
        check(rewrites.get() == 2 && reviews.get() == 1, "两条 transport 完全隔离：改写不会再打到检查那条上");
        // 再检查一次仍然只走检查那条。
        client.review(new SdClient.Prompts("night city", "", "user"));
        check(reviews.get() == 2 && rewrites.get() == 2, "检查请求始终只走 REVIEW 的 transport");
    }

    /** 生产装配（没有注入 transport）也必须是两条不同的 transport，各自一个 HttpClient。 */
    private static void reviewIsolatedInProductionWiring(Path root) throws Exception {
        DeepSeekPrompts production = DeepSeekPrompts.of(new Settings(root), DeepSeekPrompts.Channel.IMAGE);
        Object channelTransport = field(production, "transport");
        Object reviewTransport = field(production, "reviewTransport");
        check(channelTransport != null && reviewTransport != null, "生产装配下两条 transport 都建好了");
        check(channelTransport != reviewTransport, "画面检查不是改写那条 transport（同一条就说明隔离没生效）");
        check(channelTransport instanceof HttpDeepSeekTransport && reviewTransport instanceof HttpDeepSeekTransport,
                "两条都是 HTTP 传输层：既不是同一条，也不是同一个连接池");
        check(field(production, "call") != null, "重试策略随客户端一起装配");
    }

    // ------------------------------------------------------------------ 取消

    /** 传输层被中断：恢复标记、只调用一次、按取消上报，绝不"清掉标记重试一次"。 */
    private static void cancelDuringAttempt(Path root) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DeepSeekTransport interrupted = (body, key, timeout) -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new InterruptedException("cancelled");
        };
        DeepSeekPrompts client = client(root, interrupted, RetryPolicy.defaults().withSleeper(duration -> {
            throw new AssertionError("取消绝不允许进入重试等待");
        }));
        try {
            client.generate("雨夜的自行车");
            throw new AssertionError("中断必须按取消失败");
        } catch (DeepSeekError error) {
            check(error.kind() == DeepSeekError.Kind.CANCELLED, "中断按 CANCELLED 分类：" + error.kind());
            check(error.getMessage().contains("已取消"), "取消的错误文案保持既有语义：" + error.getMessage());
            check(!error.retryable(), "取消不可重试");
        }
        check(calls.get() == 1, "取消只调用一次 transport（绝不重试）：" + calls.get());
        check(Thread.currentThread().isInterrupted(), "中断标记必须仍然置位（绝不清除）");
        Thread.interrupted();
    }

    /** 线程带着中断标记进来：一个请求都不发（旧实现会清掉标记照样发）。 */
    private static void cancelBeforeAttempt(Path root) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DeepSeekTransport untouched = (body, key, timeout) -> {
            calls.incrementAndGet();
            return completion("{\"positive\":\"x\",\"negative\":\"y\"}");
        };
        DeepSeekPrompts client = client(root, untouched, RetryPolicy.defaults());
        Thread.currentThread().interrupt();
        try {
            client.generate("雨夜的自行车");
            throw new AssertionError("带中断标记时必须先判取消");
        } catch (DeepSeekError error) {
            check(error.kind() == DeepSeekError.Kind.CANCELLED, "带标记时直接按取消失败：" + error.kind());
        }
        check(calls.get() == 0, "带中断标记时一个请求都不发：" + calls.get());
        check(Thread.currentThread().isInterrupted(), "中断标记保持置位");
        Thread.interrupted();
    }

    private static void cancellation(Path root) throws Exception {
        cancelDuringAttempt(root);
        cancelBeforeAttempt(root);
    }

    // ------------------------------------------------------------------ 不可重试

    /** 401/402/400/404 与 JSON 解析错误：各只调用一次，且不许进入退避等待。 */
    private static void nonRetryable(Path root) throws Exception {
        for (int status : new int[]{401, 402, 400, 404}) {
            AtomicInteger calls = new AtomicInteger();
            DeepSeekPrompts client = client(root,
                    (body, key, timeout) -> { calls.incrementAndGet(); return DeepSeekResponse.of(status, ""); },
                    policy(3, noSleep("HTTP " + status + " 不应该重试")));
            try {
                client.generate("雨夜的自行车");
                throw new AssertionError("HTTP " + status + " 必须失败");
            } catch (DeepSeekError error) {
                check(error.status() == status, "错误带上 HTTP 状态码：" + error.status());
                check(!error.retryable(), "HTTP " + status + " 不可重试");
                check(error.kind() != DeepSeekError.Kind.CANCELLED, "HTTP " + status + " 不是取消");
            }
            check(calls.get() == 1, "HTTP " + status + " 只调用一次：" + calls.get());
        }
        AtomicInteger parseCalls = new AtomicInteger();
        DeepSeekPrompts parsing = client(root, (body, key, timeout) -> {
            parseCalls.incrementAndGet();
            throw new JsonSyntaxException("not json");
        }, policy(3, noSleep("JSON 解析错误不应该重试")));
        try {
            parsing.generate("雨夜的自行车");
            throw new AssertionError("JSON 解析错误必须失败");
        } catch (DeepSeekError error) {
            check(error.kind() == DeepSeekError.Kind.PROTOCOL && !error.retryable(),
                    "JSON 解析错误按 PROTOCOL、不重试：" + error.kind());
        }
        check(parseCalls.get() == 1, "JSON 解析错误只调用一次：" + parseCalls.get());
        // 边界：HTTP 200 但**模型输出**不是 JSON，属于"输出不合格"，由既有的 JSON 层容错重问（最多 3 次），
        // 不是传输层重试——这条边界刻意保持既有行为（DeepSeekPromptsTest 依赖它）。
        AtomicInteger badBodyCalls = new AtomicInteger();
        DeepSeekPrompts badBody = client(root, (body, key, timeout) -> {
            badBodyCalls.incrementAndGet();
            return completion("not json");
        }, policy(3, noSleep("模型输出不合格不应该在传输层重试")));
        try {
            badBody.generate("雨夜的自行车");
            throw new AssertionError("输出不是 JSON 必须失败");
        } catch (IOException expected) { /* 预期：改写如实失败，不改用户的提示词 */ }
        check(badBodyCalls.get() == 3, "200 但输出不是 JSON：只走既有的 JSON 层重问（3 次），不走传输层重试：" + badBodyCalls.get());
    }

    // ------------------------------------------------------------------ 可重试

    /** 连接错误、超时、429、503：按 3 次尝试 + 250ms/500ms 退避重试；中途成功就停。 */
    private static void retryable(Path root) throws Exception {
        List<String> failures = List.of("连接被拒", "Socket 超时", "HTTP 连接超时", "HTTP 429", "HTTP 503");
        for (String failure : failures) {
            AtomicInteger calls = new AtomicInteger();
            Recorder recorder = new Recorder();
            RetryPolicy policy = policy(3, recorder);
            DeepSeekPrompts client = client(root, (body, key, timeout) -> {
                calls.incrementAndGet();
                throw failureOf(failure);
            }, policy);
            try {
                client.generate("雨夜的自行车");
                throw new AssertionError(failure + " 必须失败");
            } catch (DeepSeekError error) {
                check(error.retryable(), failure + " 是可重试错误：" + error.kind());
            }
            check(calls.get() == 3, failure + " 按策略尝试 3 次：" + calls.get());
            check(recorder.sleeps.equals(List.of(Duration.ofMillis(250), Duration.ofMillis(500))),
                    failure + " 的退避序列（250ms → 500ms）：" + recorder.sleeps);
        }
        // 第二次就成功：只重试一次，且只等一次退避。
        AtomicInteger flakyCalls = new AtomicInteger();
        Recorder flakyRecorder = new Recorder();
        DeepSeekPrompts flaky = client(root, (body, key, timeout) -> {
            if (flakyCalls.incrementAndGet() == 1) throw new ConnectException("refused");
            return completion("{\"positive\":\"red bicycle\",\"negative\":\"blur\"}");
        }, policy(3, flakyRecorder));
        DeepSeekPrompts.Result recovered = flaky.generate("雨夜的自行车");
        check(recovered.positive().equals("red bicycle") && flakyCalls.get() == 2,
                "瞬时连接失败重试一次后成功：" + flakyCalls.get());
        check(flakyRecorder.sleeps.equals(List.of(Duration.ofMillis(250))), "只等了一次退避：" + flakyRecorder.sleeps);
        // 429 的错误文案保持既有语义（Bot 侧按文案判断能不能重规划）。
        DeepSeekPrompts throttled = client(root, (body, key, timeout) -> DeepSeekResponse.of(429, ""), policy(1, new Recorder()));
        try {
            throttled.generate("雨夜的自行车");
            throw new AssertionError("429 必须失败");
        } catch (DeepSeekError error) {
            check(error.getMessage().contains("HTTP 429") && error.kind() == DeepSeekError.Kind.RATE_LIMIT,
                    "429 的分类与文案保持既有语义：" + error.getMessage());
        }
    }

    // ------------------------------------------------------------------ 上限

    /** 永远失败时尝试次数 == 策略上限；总时长上限到了也不再重试；退避有封顶。 */
    private static void caps(Path root) throws Exception {
        for (int maxAttempts : new int[]{1, 2, 4}) {
            AtomicInteger calls = new AtomicInteger();
            DeepSeekPrompts client = client(root, (body, key, timeout) -> {
                calls.incrementAndGet();
                return DeepSeekResponse.of(503, "");
            }, policy(maxAttempts, new Recorder()));
            try {
                client.generate("雨夜的自行车");
                throw new AssertionError("永远失败必须失败");
            } catch (DeepSeekError error) {
                check(error.kind() == DeepSeekError.Kind.SERVER, "503 按 SERVER 分类");
            }
            check(calls.get() == maxAttempts, "尝试次数等于策略上限 " + maxAttempts + "：" + calls.get());
        }
        // 退避翻倍但封顶 maxDelay。
        Recorder capped = new Recorder();
        RetryPolicy cappedPolicy = new RetryPolicy(4, Duration.ofMillis(1000), Duration.ofMillis(1200),
                Duration.ofMinutes(5), capped);
        AtomicInteger cappedCalls = new AtomicInteger();
        DeepSeekPrompts cappedClient = client(root, (body, key, timeout) -> {
            cappedCalls.incrementAndGet();
            throw new ConnectException("refused");
        }, cappedPolicy);
        try {
            cappedClient.generate("雨夜的自行车");
            throw new AssertionError("永远失败必须失败");
        } catch (DeepSeekError expected) { /* 预期 */ }
        check(cappedCalls.get() == 4, "尝试 4 次：" + cappedCalls.get());
        check(capped.sleeps.equals(List.of(Duration.ofMillis(1000), Duration.ofMillis(1200), Duration.ofMillis(1200))),
                "退避翻倍但封顶 1200ms：" + capped.sleeps);
        // 总时长上限：预算为 0 时第一次失败就不再重试。
        AtomicInteger budgetCalls = new AtomicInteger();
        Recorder budgetRecorder = new Recorder();
        RetryPolicy noBudget = new RetryPolicy(5, Duration.ofMillis(250), Duration.ofMillis(2000), 0.0,
                Duration.ZERO, budgetRecorder, () -> 0.5);
        DeepSeekPrompts budgetClient = client(root, (body, key, timeout) -> {
            budgetCalls.incrementAndGet();
            throw new ConnectException("refused");
        }, noBudget);
        try {
            budgetClient.generate("雨夜的自行车");
            throw new AssertionError("永远失败必须失败");
        } catch (DeepSeekError expected) { /* 预期 */ }
        check(budgetCalls.get() == 1 && budgetRecorder.sleeps.isEmpty(),
                "总时长上限为 0 时立刻放弃（调用 " + budgetCalls.get() + " 次，等待 " + budgetRecorder.sleeps + "）");
    }

    // ------------------------------------------------------------------ 密钥不外泄

    /** 请求对象/错误分类都不带密钥，错误链里也没有可以被原样打印的 cause。 */
    private static void redaction(Path root) throws Exception {
        DeepSeekRequest request = new DeepSeekRequest(null, java.net.URI.create("https://api.deepseek.com/chat/completions"),
                new JsonObject(), KEY, Duration.ofSeconds(30));
        check(!request.toString().contains(KEY) && request.toString().contains("已隐藏"),
                "请求的 toString 不含密钥：" + request.toString());
        check(request.channel() != null && request.bodyCopy() != request.body(), "请求带通道标识且请求体是副本");
        DeepSeekPrompts client = client(root, (body, key, timeout) -> {
            throw new IOException(KEY);
        }, policy(3, new Recorder()));
        try {
            client.generate("雨夜的自行车");
            throw new AssertionError("失败必须抛出");
        } catch (DeepSeekError error) {
            check(!String.valueOf(error.getMessage()).contains(KEY) && !error.toString().contains(KEY),
                    "错误文案与异常文本都不含密钥：" + error.getMessage());
            check(error.getCause() == null, "错误不挂原始异常（原始异常的 message 可能夹带密钥/请求内容）");
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static DeepSeekPrompts client(Path root, DeepSeekTransport transport, RetryPolicy policy) {
        return new DeepSeekPrompts(root, new JsonObject(), transport, transport, query -> "", KEY_FILE, policy);
    }

    /** 固定退避序列的策略：250ms → 500ms（无抖动，随机源固定）。 */
    private static RetryPolicy policy(int maxAttempts, RetryPolicy.Sleeper sleeper) {
        return new RetryPolicy(maxAttempts, Duration.ofMillis(250), Duration.ofSeconds(2), 0.0,
                Duration.ofMinutes(5), sleeper, () -> 0.5);
    }

    private static RetryPolicy.Sleeper noSleep(String why) {
        return duration -> { throw new AssertionError(why + "（实际等了 " + duration.toMillis() + " ms）"); };
    }

    private static Exception failureOf(String failure) {
        return switch (failure) {
            case "连接被拒" -> new ConnectException("connection refused");
            case "Socket 超时" -> new SocketTimeoutException("read timed out");
            case "HTTP 连接超时" -> new HttpConnectTimeoutException("connect timed out");
            case "HTTP 429" -> DeepSeekError.ofStatus(429);
            case "HTTP 503" -> DeepSeekError.ofStatus(503);
            default -> new IllegalStateException("未知的故障注入：" + failure);
        };
    }

    private static DeepSeekResponse completion(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.addProperty("finish_reason", "stop");
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("id", "test");
        envelope.addProperty("object", "chat.completion");
        envelope.add("choices", choices);
        return DeepSeekResponse.of(200, envelope.toString());
    }

    private static Object field(Object target, String name) throws Exception {
        Field found = target.getClass().getDeclaredField(name);
        found.setAccessible(true);
        return found.get(target);
    }

    /** 记录每次退避等待的假睡眠器。 */
    private static final class Recorder implements RetryPolicy.Sleeper {
        private final List<Duration> sleeps = new ArrayList<>();
        @Override public void sleep(Duration duration) { sleeps.add(duration); }
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}

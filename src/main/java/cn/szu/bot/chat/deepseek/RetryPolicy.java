package cn.szu.bot.chat.deepseek;

import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.DoubleSupplier;

/**
 * 重试策略：**哪些失败值得重发一次**、隔多久重发、最多重发几次、总共最多耗多久。
 *
 * <h2>重试什么</h2>
 * <ul>
 *   <li>连接层错误（{@link ConnectException}/{@link IOException} 等）：连接被复用到已经关闭的套接字时
 *       的瞬时故障，重发一次就好；</li>
 *   <li>超时（{@link HttpTimeoutException}/{@link SocketTimeoutException}/{@link TimeoutException}、
 *       以及 HTTP 408）；</li>
 *   <li>HTTP 429（限流）；</li>
 *   <li>HTTP 500/502/503/504（服务端瞬时故障）。</li>
 * </ul>
 *
 * <h2>不重试什么</h2>
 * <ul>
 *   <li>JSON 解析错误（{@link JsonParseException}）——重发同样的内容只会得到同样的坏 JSON
 *       （模型输出不合格的重问是**另一层**策略，见 {@code DeepSeekPrompts.request/review}）；</li>
 *   <li>业务校验错误（字段结构痕迹、ok 缺失、输出被截断……）同理；</li>
 *   <li>401（密钥）、402（余额）、普通 4xx（400/403/404…）、501/505 这类 5xx——重发没有意义；</li>
 *   <li>线程中断/取消（{@link DeepSeekError.Kind#CANCELLED}）——绝不清标记、绝不重试。</li>
 * </ul>
 *
 * <h2>退避参数</h2>
 * 指数退避 + 抖动：第 n 次失败后的等待是 {@code baseDelay * 2^(n-1)}，封顶 {@code maxDelay}，
 * 再乘一个 {@code 1 ± jitterRatio} 的随机因子（默认 ±25%，防止多条通道同时重试形成尖峰）。
 * 另外有两道上限：{@code maxAttempts}（总共最多发几次）与 {@code maxElapsed}（一次逻辑调用最多耗多久），
 * 任一超限都立刻如实上报最后一个错误，绝不再等。
 *
 * <p>睡眠器（{@link Sleeper}）可注入：测试用假的睡眠器断言退避序列，生产用 {@link Thread#sleep}。
 */
public final class RetryPolicy {

    /** 可注入的睡眠器；用 {@code InterruptedException} 表示线程被取消。 */
    @FunctionalInterface public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Sleeper REAL_SLEEPER = duration -> {
        long millis = duration.toMillis();
        if (millis > 0) Thread.sleep(millis);
    };

    /** 默认总共最多发 3 次（首发给一次，重试最多两次）。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 默认首次退避 300ms，之后翻倍。 */
    public static final Duration DEFAULT_BASE_DELAY = Duration.ofMillis(300);
    /** 单次退避最长 3s（再多等不如直接如实报错）。 */
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(3);
    /** 默认抖动 ±25%。 */
    public static final double DEFAULT_JITTER_RATIO = 0.25;
    /** 一次逻辑调用（含所有重试与等待）最长 20s。 */
    public static final Duration DEFAULT_MAX_ELAPSED = Duration.ofSeconds(20);

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Duration maxElapsed;
    private final double jitterRatio;
    private final Sleeper sleeper;
    private final DoubleSupplier random;

    /**
     * @param maxAttempts 总共最多发几次（含首发），至少 1
     * @param sleeper     实际等待用的睡眠器
     * @param random      {@code [0,1)} 上的随机源，抖动用（测试可固定成 0.5 得到"无抖动"的确定序列）
     */
    public RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, double jitterRatio,
                       Duration maxElapsed, Sleeper sleeper, DoubleSupplier random) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelay = clamp(baseDelay, DEFAULT_BASE_DELAY);
        this.maxDelay = clamp(maxDelay, DEFAULT_MAX_DELAY);
        this.jitterRatio = Math.min(1.0, Math.max(0.0, jitterRatio));
        this.maxElapsed = clamp(maxElapsed, DEFAULT_MAX_ELAPSED);
        this.sleeper = sleeper == null ? REAL_SLEEPER : sleeper;
        this.random = random == null ? Math::random : random;
    }

    /** 不抖动的确定策略（测试与"精确断言退避序列"用）。 */
    public RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, Duration maxElapsed, Sleeper sleeper) {
        this(maxAttempts, baseDelay, maxDelay, 0.0, maxElapsed, sleeper, () -> 0.5);
    }

    /** 生产默认：3 次、300ms 起、封顶 3s、±25% 抖动、总时长 20s。 */
    public static RetryPolicy defaults() {
        return new RetryPolicy(DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_DELAY, DEFAULT_MAX_DELAY, DEFAULT_JITTER_RATIO,
                DEFAULT_MAX_ELAPSED, REAL_SLEEPER, Math::random);
    }

    /** 换一个睡眠器，其余参数不变（测试注入假睡眠器用）。 */
    public RetryPolicy withSleeper(Sleeper sleeper) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, jitterRatio, maxElapsed, sleeper, random);
    }

    /** 固定随机源，其余参数不变（退避序列可复现）。 */
    public RetryPolicy withRandom(DoubleSupplier random) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, jitterRatio, maxElapsed, sleeper, random);
    }

    public int maxAttempts() { return maxAttempts; }

    public Duration baseDelay() { return baseDelay; }

    public Duration maxDelay() { return maxDelay; }

    public Duration maxElapsed() { return maxElapsed; }

    public double jitterRatio() { return jitterRatio; }

    /** 这一条失败值不值得重发：只看 {@link DeepSeekError#retryable()}（分类在 {@link #classify}/{@link DeepSeekError#ofStatus}）。 */
    public boolean retryable(DeepSeekError error) {
        return error != null && error.retryable();
    }

    /** 还有时间再试一次吗（不再看尝试数，那个由 {@link #maxAttempts()} 判）。 */
    public boolean withinBudget(Duration elapsed) {
        return elapsed == null || elapsed.compareTo(maxElapsed) < 0;
    }

    /**
     * 第 {@code failedAttempt} 次失败后的等待时长（{@code failedAttempt} 从 1 开始）：
     * {@code min(maxDelay, baseDelay * 2^(n-1))} 再乘 {@code 1 ± jitterRatio}，结果同样封顶 {@code maxDelay}。
     */
    public Duration backoff(int failedAttempt) {
        long delay = Math.max(0, baseDelay.toMillis());
        long cap = Math.max(0, maxDelay.toMillis());
        for (int step = 1; step < failedAttempt && delay < cap; step++) delay = Math.min(cap, delay * 2);
        delay = Math.min(cap, delay);
        double factor = 1 + (random.getAsDouble() * 2 - 1) * jitterRatio;
        long jittered = Math.max(0, Math.round(delay * factor));
        return Duration.ofMillis(Math.min(cap, jittered));
    }

    /**
     * 按策略等待；线程在这期间被中断 → **恢复标记**并抛 {@link DeepSeekError.Kind#CANCELLED}，
     * 绝不清掉标记继续重试。
     */
    public void pause(Duration delay) throws DeepSeekError {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            if (Thread.currentThread().isInterrupted()) throw DeepSeekError.cancelled();
            return;
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw DeepSeekError.cancelled();
        }
    }

    /**
     * 把传输层抛出的异常翻译成分类结果。
     *
     * <p>顺序有讲究：先看"线程是不是已经被中断"（那是取消，不是网络故障），再看超时的几种具体类型
     * （它们都是 {@link IOException} 的子类），最后才把剩下的 {@link IOException} 当连接层故障。
     * 不认识的异常一律当 {@code PROTOCOL} 且**不重试**——那更可能是代码缺陷，重发只会掩盖它。
     */
    public static DeepSeekError classify(Throwable error) {
        if (error instanceof DeepSeekError known) return known;
        if (error instanceof InterruptedException) return DeepSeekError.cancelled();
        // 中断标记已置位时，任何 IO 异常都按取消处理：带标记重发请求没有任何意义。
        if (Thread.currentThread().isInterrupted()) return DeepSeekError.cancelled();
        if (error instanceof HttpTimeoutException || error instanceof SocketTimeoutException
                || error instanceof TimeoutException || error instanceof InterruptedIOException)
            return new DeepSeekError(DeepSeekError.Kind.TIMEOUT, "DeepSeek 请求超时。", true);
        if (error instanceof JsonParseException)
            return new DeepSeekError(DeepSeekError.Kind.PROTOCOL, "DeepSeek 返回的内容不是有效 JSON。", false);
        if (error instanceof IOException)
            return new DeepSeekError(DeepSeekError.Kind.NETWORK, DeepSeekError.NETWORK_MESSAGE, true);
        return new DeepSeekError(DeepSeekError.Kind.PROTOCOL, "DeepSeek 请求未完成，请稍后重试。", false);
    }

    private static Duration clamp(Duration value, Duration fallback) {
        return value == null || value.isNegative() ? fallback : value;
    }
}

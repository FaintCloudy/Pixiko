package cn.szu.bot.chat.deepseek;

import cn.szu.bot.Log;
import java.time.Duration;

/**
 * 一次逻辑上的 DeepSeek 调用：发请求、校验、按 {@link RetryPolicy} 决定重发还是如实上报。
 *
 * <h2>取消语义（铁律）</h2>
 * 线程中断是**用户/调用方的意思**，不是"上一次调用留下的残留"：
 * <ul>
 *   <li>发请求前发现中断标记 → 一次都不发，直接 {@link DeepSeekError.Kind#CANCELLED}；</li>
 *   <li>传输层抛 {@link InterruptedException} → **恢复标记**、立刻取消，绝不重试；</li>
 *   <li>退避等待期间被中断 → 同上（见 {@link RetryPolicy#pause}）；</li>
 *   <li>任何情况下都**不清除**中断标记，也不再"清掉标记重发一次"。</li>
 * </ul>
 *
 * <h2>重试语义</h2>
 * 只有 {@link DeepSeekError#retryable()} 为真的失败才会重发（连接错误、超时、408/429、500/502/503/504），
 * 且同时受 {@code maxAttempts} 与 {@code maxElapsed} 两道上限约束；JSON/业务校验、401/402/普通 4xx 一次即止。
 * 每次重试都按 {@link RetryPolicy#backoff} 指数退避 + 抖动。
 *
 * <p>TODO(Bot)：本类不再替调用方清中断标记。Bot 侧若有"取消后继续复用同一条线程发 DeepSeek 请求"的路径
 * （例如取消 SD 时 {@code shutdownNow} 打断了正在做改写/检查的线程后，这条线程又被拿去做别的事），
 * 必须由 Bot 自己决定是否恢复这条线程的可用性，而不是指望传输层把标记抹掉；否则那条线程上的下一次
 * DeepSeek 调用会如实按"已取消"失败。这里只写 TODO，不改 Bot.java。
 */
public final class DeepSeekCall {

    /** 每次拿到响应后的校验：正常返回表示可用，抛 {@link DeepSeekError} 表示这次请求按错误处理（可能触发重试）。 */
    @FunctionalInterface public interface Check {
        void check(DeepSeekResponse response, long elapsedMillis) throws DeepSeekError;
    }

    private final RetryPolicy policy;

    public DeepSeekCall(RetryPolicy policy) {
        this.policy = policy == null ? RetryPolicy.defaults() : policy;
    }

    /** 本调用用的重试策略（日志/测试用）。 */
    public RetryPolicy policy() { return policy; }

    /**
     * 发一次逻辑请求并返回**通过校验**的响应（状态码 200，由 {@code check} 判定）。
     *
     * @throws DeepSeekError 不可重试、或重试用尽、或已取消/超出总时长上限时抛出（文案沿用既有语义）
     */
    public DeepSeekResponse execute(DeepSeekTransport transport, DeepSeekRequest request, Check check) throws DeepSeekError {
        long started = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            // 中断标记只恢复、不清除：带标记就立刻按取消处理，一个请求都不发。
            if (Thread.currentThread().isInterrupted()) {
                Log.warn("DeepSeek 请求已取消（线程带着中断标记，按取消处理，不再发送）");
                throw DeepSeekError.cancelled();
            }
            long attemptStarted = System.nanoTime();
            try {
                DeepSeekResponse response = transport.post(request);
                check.check(response, milliseconds(attemptStarted));
                return response;
            } catch (DeepSeekError error) {
                retryOrThrow(error, attempt, started);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Log.warn("DeepSeek 请求已取消（已恢复中断标记，不再重试）");
                throw DeepSeekError.cancelled();
            } catch (Exception failed) {
                retryOrThrow(RetryPolicy.classify(failed), attempt, started);
            }
        }
    }

    /** 还能重试就退避等待后返回（由调用方的循环重发），否则抛出这个错误。 */
    private void retryOrThrow(DeepSeekError error, int attempt, long started) throws DeepSeekError {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        if (!policy.retryable(error) || attempt >= policy.maxAttempts() || !policy.withinBudget(elapsed)) throw error;
        Duration delay = policy.backoff(attempt);
        Log.warn("DeepSeek 请求失败（" + error.kind() + "，" + error.getMessage() + "），"
                + "第 " + attempt + " 次重试，等待 " + delay.toMillis() + " ms");
        policy.pause(delay);
    }

    private static long milliseconds(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1_000_000.0);
    }
}

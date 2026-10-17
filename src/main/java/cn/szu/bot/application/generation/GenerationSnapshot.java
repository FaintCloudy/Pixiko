package cn.szu.bot.application.generation;

import java.math.BigInteger;
import java.util.Objects;

/**
 * 生成队列计数的只读快照：状态行（{@code .gen status}）与 {@code /api/tasks} 的唯一读数口径。
 *
 * <p>三个数都以「次」为单位：{@code waiting} 是排队中（含正在生成的任务后面还没开始的次数），
 * {@code suspended} 是挂起的次数，{@code running} 是正在 SD 里跑的那一张（0 或 1）。
 *
 * <p>不变量：三个数都必须 ≥ 0。负值只可能来自绕开唯一转换入口的计数点，属于内部错误——
 * 出口处直接记日志并抛错，**不**静默 clamp（clamp 会把 bug 藏起来，用户仍然看到错的数）。
 */
public record GenerationSnapshot(BigInteger waiting, BigInteger suspended, BigInteger running) {
    public GenerationSnapshot {
        Objects.requireNonNull(waiting, "waiting");
        Objects.requireNonNull(suspended, "suspended");
        Objects.requireNonNull(running, "running");
    }

    public static GenerationSnapshot of(BigInteger waiting, BigInteger suspended, boolean running) {
        return new GenerationSnapshot(waiting, suspended, running ? BigInteger.ONE : BigInteger.ZERO);
    }

    /** 状态行上的「合计 N 个」。 */
    public BigInteger total() { return waiting.add(suspended).add(running); }

    /** 三个数都非负（不变量成立）。 */
    public boolean consistent() {
        return waiting.signum() >= 0 && suspended.signum() >= 0 && running.signum() >= 0;
    }

    @Override public String toString() {
        return "等待 " + waiting + "，挂起 " + suspended + "，运行 " + running + "，合计 " + total();
    }
}

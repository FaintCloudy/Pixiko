package cn.szu.bot.chat.deepseek;

import java.io.IOException;

/**
 * 一次 DeepSeek 调用失败的**分类结果**：既有的中文错误文案（调用方与回执一直依赖它）原样保留，
 * 另外带上"是哪一类失败"与"能不能重试"，重试决策只认这两个字段，不再看异常类型/字符串。
 *
 * <p>继承 {@link IOException} 是刻意的：既有代码到处 {@code catch (IOException)} 并按文案判断
 * （例如 {@code DeepSeekPrompts.retryableChatFailure}），换一个父类会改变这些调用点的行为。
 *
 * <p>**不挂 cause**：传输层异常的 message 里可能夹带请求内容甚至密钥（测试就专门验证过这一点），
 * 而异常链会被日志与回执原样打出来；分类信息足够了，原始异常只在 RetryPolicy 里读类型名。
 */
public final class DeepSeekError extends IOException {

    /**
     * 失败分类：
     * <ul>
     *   <li>{@code AUTH} 密钥无效（401）；{@code QUOTA} 余额不足（402）；</li>
     *   <li>{@code BAD_REQUEST} 其它 4xx（请求本身有问题，重发也一样）；</li>
     *   <li>{@code RATE_LIMIT} 限流（429）；{@code SERVER} 服务端错误（只有 500/502/503/504 可重试）；</li>
     *   <li>{@code NETWORK} 连接层失败；{@code TIMEOUT} 超时（含 408）；</li>
     *   <li>{@code CANCELLED} 线程中断/用户取消；{@code PROTOCOL} JSON 解析或业务校验失败。</li>
     * </ul>
     */
    public enum Kind { AUTH, QUOTA, BAD_REQUEST, RATE_LIMIT, SERVER, NETWORK, TIMEOUT, CANCELLED, PROTOCOL }

    /** 网络层失败（重试用尽后）的文案：与既有实现的措辞保持一致。 */
    public static final String NETWORK_MESSAGE = "DeepSeek 请求未完成，请检查网络或稍后重试。";

    private final Kind kind;
    private final boolean retryable;
    private final int status;

    public DeepSeekError(Kind kind, String message, boolean retryable) { this(kind, message, retryable, 0); }

    /**
     * @param status HTTP 状态码；{@code 0} 表示这条失败没有状态码（网络/超时/取消/解析）。
     */
    public DeepSeekError(Kind kind, String message, boolean retryable, int status) {
        super(message);
        this.kind = kind;
        this.retryable = retryable;
        this.status = status;
    }

    public Kind kind() { return kind; }

    /** 是不是"下一个请求有可能成功"的失败；重试策略只认这个（见 {@link RetryPolicy#retryable}）。 */
    public boolean retryable() { return retryable; }

    public int status() { return status; }

    /** 线程被中断：恢复标记、**不重试**、按取消如实上报（文案沿用既有语义）。 */
    public static DeepSeekError cancelled() {
        return new DeepSeekError(Kind.CANCELLED, "DeepSeek 请求已取消。", false);
    }

    /**
     * HTTP 状态码 → 分类 + 既有文案。
     *
     * <p>可重试的只有 408（超时）、429（限流）与 500/502/503/504（服务端瞬时故障）；
     * 401/402/普通 4xx/其它 5xx（例如 501、505）都**不重试**——它们重发一百次也还是同样的结果。
     */
    public static DeepSeekError ofStatus(int status) {
        Kind kind;
        boolean retryable;
        if (status == 401) { kind = Kind.AUTH; retryable = false; }
        else if (status == 402) { kind = Kind.QUOTA; retryable = false; }
        else if (status == 408) { kind = Kind.TIMEOUT; retryable = true; }
        else if (status == 429) { kind = Kind.RATE_LIMIT; retryable = true; }
        else if (status == 500 || status == 502 || status == 503 || status == 504) { kind = Kind.SERVER; retryable = true; }
        else if (status >= 500) { kind = Kind.SERVER; retryable = false; }
        else if (status >= 400) { kind = Kind.BAD_REQUEST; retryable = false; }
        else { kind = Kind.PROTOCOL; retryable = false; }
        return new DeepSeekError(kind, messageOf(status), retryable, status);
    }

    private static String messageOf(int status) {
        return switch (status) {
            case 401 -> "DeepSeek 密钥验证失败（HTTP 401），请检查密钥。";
            case 402 -> "DeepSeek 账户余额不足（HTTP 402）。";
            case 429 -> "DeepSeek 请求过于频繁（HTTP 429），请稍后重试。";
            default -> "DeepSeek API 请求失败（HTTP " + status + "）。";
        };
    }
}

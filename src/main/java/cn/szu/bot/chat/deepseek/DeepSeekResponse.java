package cn.szu.bot.chat.deepseek;

/**
 * 一次 DeepSeek HTTP 调用的结果：状态码 + 原始响应体。
 *
 * <p>刻意做成**接口**而不是 record：{@code DeepSeekPrompts.Response} 是既有的公开类型（测试与调用方
 * 都在 new 它、读它），让它直接实现这个接口就能在不改任何既有调用点的前提下把传输层抽出来；
 * 传输层的生产实现用 {@link #of(int, String)} 返回一个只装这两个字段的实现。
 */
public interface DeepSeekResponse {

    int status();

    String body();

    /** 只有 HTTP 200 才算成功（与既有实现一致：其余状态码一律按错误分类，见 {@link DeepSeekError#ofStatus}）。 */
    default boolean ok() { return status() == 200; }

    /** 只装状态码与响应体的最小实现。 */
    static DeepSeekResponse of(int status, String body) { return new Plain(status, body); }

    /** 默认实现：没有额外语义。 */
    record Plain(int status, String body) implements DeepSeekResponse { }
}

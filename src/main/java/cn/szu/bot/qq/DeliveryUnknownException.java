package cn.szu.bot.qq;

import java.io.IOException;

/**
 * 传输层**不能确定**这次发送到底发出去了没有（API 响应超时、连接断开）。
 *
 * <p>为什么需要单独一种异常：调用方过去只能看到一句 IOException，于是把"超时"当成"没发出去"。
 * 一次 /lora query 的 10 条结果（每条带一张远程封面）走合并转发时，NapCat 下载并上传 10 张图要
 * 30 秒以上，超过配置里的普通 API 超时（{@code timeout_seconds = 30}）就报超时；调用方随即逐条
 * 又发了一遍，用户看到同一批结果出现两遍（见 bot-stdout.log 2026-10-05 07:44:36 → 07:45:06）。
 *
 * <p>所以"结果未知"必须与"确定被拒"分开：**确定被拒**（NapCat 回了 retcode）可以放心逐条兜底，
 * **结果未知**只许如实说明「可能已发出，未自动重发」，绝不重发。宁可少发一次，也不许同一批出现两遍。
 *
 * <p>本类也顺带承载这次事故的另一半修法：带图的合并转发必须用图片上传的等待预算
 * （见 {@link QqClient#deliveryTimeoutSeconds(int, int, int)}），这样"该等的时候愿意等"，
 * 大多数超时根本不会发生。
 */
public final class DeliveryUnknownException extends IOException {
    private static final long serialVersionUID = 1L;
    /** 文案里用来标记"结果未知"的说法：调用方与测试都按它认这一种失败。 */
    public static final String UNKNOWN_MARKER = "结果未知";
    /** 文案里用来标记"没有自动重发"的说法。 */
    public static final String NO_RETRY_MARKER = "未自动重发";

    private DeliveryUnknownException(String message) { super(message); }

    /** API 响应超时：请求已经发出去，QQ 侧可能已经处理完了。 */
    public static DeliveryUnknownException timeout() {
        return new DeliveryUnknownException("QQ API 响应超时；发送" + UNKNOWN_MARKER + "，未自动重发");
    }

    /** 连接在发送途中断开：同样无法确认。 */
    public static DeliveryUnknownException disconnected() {
        return new DeliveryUnknownException("QQ 连接断开；发送结果可能未知，未自动重发");
    }

    /** QQ 把它转成异步处理：没有最终 ACK，也算"确认不了"。 */
    public static DeliveryUnknownException async() {
        return new DeliveryUnknownException("QQ 已转为异步处理，无法确认消息发送结果；未自动重发");
    }
}

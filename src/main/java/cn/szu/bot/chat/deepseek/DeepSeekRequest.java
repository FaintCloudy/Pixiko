package cn.szu.bot.chat.deepseek;

import com.google.gson.JsonObject;
import java.net.URI;
import java.time.Duration;

/**
 * 一次逻辑上的 DeepSeek 请求：哪条通道、发到哪、请求体、密钥、**单次**超时。
 *
 * <p>密钥只活在这个对象里：{@link #toString()} 一律隐去，也绝不出现在日志、异常消息、回执或存档里
 * （Debugger/日志里打印请求时拿到的就是这个 toString）。
 */
public record DeepSeekRequest(DeepSeekChannel channel, URI endpoint, JsonObject body, String key, Duration timeout) {

    public DeepSeekRequest {
        channel = channel == null ? DeepSeekChannel.IMAGE : channel;
        body = body == null ? new JsonObject() : body;
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(120) : timeout;
    }

    /** 请求体的独立副本（重试都基于同一份内容，但调用方不该拿到可变引用）。 */
    public JsonObject bodyCopy() { return body.deepCopy(); }

    /** 绝不包含密钥。 */
    @Override public String toString() {
        return "DeepSeekRequest[" + channel + " " + endpoint + " timeout=" + timeout.toMillis() + "ms key=<已隐藏>]";
    }
}

package cn.szu.bot.chat.deepseek;

import com.google.gson.JsonObject;
import java.time.Duration;

/**
 * 可注入的 DeepSeek 传输层：只负责"把一份 JSON 发出去、拿回状态码与响应体"，不解析、不重试、不判断。
 *
 * <p>方法签名与既有的 {@code DeepSeekPrompts.Transport} 完全一致（请求体 / 密钥 / 超时），所以既有的
 * 注入点与测试 lambda 一个字都不用改；重试、分类、取消都在 {@link DeepSeekCall} + {@link RetryPolicy} 里。
 *
 * <p>地址（endpoint）由具体实现绑定在实例上（见 {@link HttpDeepSeekTransport}）：生图/聊天共用一个
 * {@link java.net.http.HttpClient}，画面检查用**另一个**，见 {@code DeepSeekPrompts.REVIEW_HTTP}。
 */
@FunctionalInterface
public interface DeepSeekTransport {

    DeepSeekResponse post(JsonObject body, String key, Duration timeout) throws Exception;

    /**
     * 用 {@link DeepSeekRequest} 发一次：默认只把请求体、密钥、超时转给上面那个方法（endpoint 由实例绑定，
     * 这里不另传）。密钥不会被打印。
     */
    default DeepSeekResponse post(DeepSeekRequest request) throws Exception {
        return post(request.body(), request.key(), request.timeout());
    }
}

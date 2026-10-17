package cn.szu.bot.chat.deepseek;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 生产用的传输层：一个实例 = 一个地址 + **一个** {@link HttpClient}（也就是一个连接池）。
 *
 * <p>连接池是隔离的载体：生图/聊天共用一个实例，画面检查用另一个实例（另一个 HttpClient），
 * 两条通道的连接不会互相污染（成因见 {@code DeepSeekPrompts.REVIEW_HTTP} 的注释）。想让某条通道
 * 彻底独立，只需要给它单独 new 一个本类实例 + 一个新的 HttpClient。
 *
 * <p>本类不打印任何东西：密钥只进 Authorization 头，连异常都只向上抛（由上层分类，不带原文）。
 */
public final class HttpDeepSeekTransport implements DeepSeekTransport {

    private final URI endpoint;
    private final HttpClient client;

    public HttpDeepSeekTransport(URI endpoint, HttpClient client) {
        if (endpoint == null) throw new IllegalArgumentException("DeepSeek 地址不能为空。");
        if (client == null) throw new IllegalArgumentException("DeepSeek HttpClient 不能为空。");
        this.endpoint = endpoint;
        this.client = client;
    }

    /** 该实例绑定的地址（日志/诊断用；不含密钥）。 */
    public URI endpoint() { return endpoint; }

    @Override public DeepSeekResponse post(JsonObject body, String key, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return DeepSeekResponse.of(response.statusCode(), response.body());
    }
}

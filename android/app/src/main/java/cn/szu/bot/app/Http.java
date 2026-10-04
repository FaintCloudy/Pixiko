package cn.szu.bot.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极简 HTTP 客户端：只用 {@link HttpURLConnection}，不引入 OkHttp/Retrofit。
 *
 * <p>只服务四个场景：{@code GET /healthz}（免令牌探活）、{@code POST /api/status}（验令牌）、
 * 下载图片字节、以及读一小段响应体看错误信息。所以 API 刻意做得很窄。
 *
 * <p>所有方法都会把「返回值」与「异常」分清：探活失败是正常结果（返回 0），
 * 只有真出错才抛。
 */
public final class Http {

    /** 探活/验令牌这类小请求的超时。 */
    public static final int SHORT_TIMEOUT_MS = 4000;

    private Http() { }

    /** 一次 HTTP 调用的结果：状态码 + 已读出来的响应体（可能被截断）。 */
    public static final class Result {
        public final int code;
        public final String body;

        Result(int code, String body) { this.code = code; this.body = body; }

        public boolean ok() { return code >= 200 && code < 300; }
    }

    /** GET，带上可选的 Bearer 令牌；超时用 {@code timeoutMs}。 */
    public static Result get(String url, String bearer, int timeoutMs) throws IOException {
        return call("GET", url, bearer, null, timeoutMs, 8 * 1024);
    }

    /** POST，带 JSON 体（可为空）与可选的 Bearer 令牌。 */
    public static Result postJson(String url, String bearer, String json, int timeoutMs) throws IOException {
        return call("POST", url, bearer, json == null ? "" : json, timeoutMs, 8 * 1024);
    }

    private static Result call(String method, String url, String bearer, String body, int timeoutMs, int readLimit)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (bearer != null && !bearer.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = null;
            try {
                stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = readText(stream, readLimit);
                return new Result(code, text);
            } finally {
                close(stream);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * 只做「服务在不在」的探活：连得上、HTTP 通了就算在（不校验状态码与内容）。
     * 返回状态码；连不上返回 0（这是正常结果，不是错误）。
     */
    public static int reachable(String url, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            // 一定要把流读掉/关掉：有些实现不消费响应体时不释放连接，扫描时会把连接池耗干。
            InputStream stream = null;
            try {
                stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                drain(stream, 512);
            } finally {
                close(stream);
            }
            return code;
        } catch (IOException error) {
            return 0;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 读一小段响应体当文本（超过上限就截断，避免被人塞一个巨大错误页撑爆内存）。 */
    public static String readText(InputStream stream, int limit) throws IOException {
        if (stream == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int read;
        while (total < limit && (read = stream.read(chunk)) > 0) {
            int take = Math.min(read, limit - total);
            buffer.write(chunk, 0, take);
            total += take;
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    /** 丢掉响应体但保持连接可复用。 */
    public static void drain(InputStream stream, int limit) throws IOException {
        if (stream == null) return;
        byte[] chunk = new byte[2048];
        int total = 0;
        while (total < limit && stream.read(chunk) > 0) total += chunk.length;
    }

    public static void close(InputStream stream) {
        if (stream == null) return;
        try { stream.close(); } catch (IOException ignored) { }
    }
}

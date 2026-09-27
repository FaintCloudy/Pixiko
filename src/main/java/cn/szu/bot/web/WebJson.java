package cn.szu.bot.web;

import cn.szu.bot.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 网页层的响应小工具：统一 JSON 结构、错误体与 {@code Cache-Control}。
 *
 * <p>JSON 一直用机器人自己的 Gson（{@link Json}），不引 Jackson：接口请求体按字符串读进来再解析，
 * 返回值也是字符串，行为与迁移前完全一致。
 */
final class WebJson {

    private WebJson() { }

    static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("error", message == null ? "未知错误" : message);
        return result;
    }

    /** JSON 响应体（接口统一 no-store：控制台是就地改的，别让浏览器缓存旧数据）。 */
    static ResponseEntity<String> of(HttpStatus status, JsonElement body) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Json.GSON.toJson(body));
    }

    static ResponseEntity<String> ok(JsonElement body) { return of(HttpStatus.OK, body); }

    /** 直接把 JSON 写进 Servlet 响应（过滤器里用，那时还没进控制器）。 */
    static void write(HttpServletResponse response, int status, JsonElement body) throws IOException {
        byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    /** 静态文件 / 页面：按类型给 Content-Type，控制台资源一律 no-store。 */
    static ResponseEntity<byte[]> bytes(HttpStatus status, byte[] body, String contentType, String cacheControl) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                .body(body);
    }

    static String contentTypeOf(String name) {
        return name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".json") ? "application/json; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".ico") ? "image/x-icon"
                : name.endsWith(".png") ? MediaType.IMAGE_PNG_VALUE
                : name.endsWith(".jpg") || name.endsWith(".jpeg") ? MediaType.IMAGE_JPEG_VALUE
                : name.endsWith(".webp") ? "image/webp"
                : name.endsWith(".gif") ? MediaType.IMAGE_GIF_VALUE
                : "text/plain; charset=utf-8";
    }
}

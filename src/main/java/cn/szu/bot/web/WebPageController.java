package cn.szu.bot.web;

import cn.szu.bot.Bot;
import cn.szu.bot.Json;
import cn.szu.bot.Log;
import cn.szu.bot.Settings;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 页面与静态资源：<b>每个栏目一个独立页面</b>。
 *
 * <p>{@code /}、{@code /gen}、{@code /prompt}… 各自返回一份 HTML：公共外壳（顶栏、页签、页脚、
 * 弹窗与查看器）加上<b>只属于该栏目</b>的面板标记，页面上不会出现别的栏目的 DOM，前端也只加载
 * 这个栏目要用的数据（见 {@link WebPages}）。静态文件（app.js / app.css / 图标）仍从 {@code webui/}
 * 目录按需读取、一律 {@code no-store}，改完刷新即可，不用重新打包。
 */
@RestController
public class WebPageController {

    /** 栏目页：路径 → 面板 id。chat 用根路径，其余一个栏目一个路径。 */
    static final Map<String, String> PAGES = new LinkedHashMap<>();
    static {
        PAGES.put("/", "chat");
        PAGES.put("/gen", "gen");
        PAGES.put("/prompt", "prompt");
        PAGES.put("/styles", "styles");
        PAGES.put("/loras", "loras");
        PAGES.put("/functions", "functions");
        PAGES.put("/chatcfg", "chatcfg");
        PAGES.put("/system", "system");
        PAGES.put("/setup", "setup");
        PAGES.put("/logs", "logs");
        PAGES.put("/help", "help");
    }

    private final Settings settings;
    private final Bot bot;
    private final Path webRoot;

    public WebPageController(Settings settings, Bot bot) {
        this.settings = settings;
        this.bot = bot;
        // 页面资源放在机器人目录的 webui/ 下，方便直接改不用重新打包；找不到时回退到进程工作目录。
        Path configured = settings.root.toAbsolutePath().normalize().resolve("webui");
        this.webRoot = Files.isDirectory(configured) ? configured : Path.of("webui").toAbsolutePath().normalize();
    }

    /** 免令牌健康检查（浏览器/脚本用它判断服务在不在）。 */
    @RequestMapping(value = "/healthz", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> healthz() {
        return WebJson.ok(Json.parse("{\"ok\":true}"));
    }

    /** 一次性令牌回收 Cookie：不要求访问令牌，但令牌必须有效（见 {@link Bot#civitaiSaveCookie}）。 */
    @RequestMapping(value = "/civitai-cookie", method = {RequestMethod.POST, RequestMethod.OPTIONS})
    public ResponseEntity<String> civitaiCookie(HttpServletRequest request, @RequestBody(required = false) String raw) {
        if ("OPTIONS".equals(request.getMethod()))
            return ResponseEntity.noContent()
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Access-Control-Allow-Headers", "Content-Type")
                    .build();
        try {
            JsonObject body = WebPages.parseBody(raw);
            JsonObject result = bot.civitaiSaveCookie(Json.str(body, "token", ""), Json.str(body, "cookie", ""));
            return ResponseEntity.ok()
                    .header("Access-Control-Allow-Origin", "*")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                    .body(Json.GSON.toJson(result));
        } catch (IllegalArgumentException error) {
            return WebJson.of(HttpStatus.BAD_REQUEST, WebJson.error(error.getMessage()));
        } catch (Exception error) {
            Log.warn("Civitai Cookie 保存失败：" + Bot.error(error));
            return WebJson.of(HttpStatus.INTERNAL_SERVER_ERROR, WebJson.error(Bot.error(error)));
        }
    }

    /** 栏目页：公共外壳 + 该栏目自己的面板。 */
    @RequestMapping(value = {"/", "/gen", "/prompt", "/styles", "/loras", "/functions", "/chatcfg", "/system", "/setup", "/logs", "/help"},
            method = RequestMethod.GET)
    public ResponseEntity<byte[]> page(HttpServletRequest request) {
        return renderPage(request.getRequestURI());
    }

    private ResponseEntity<byte[]> renderPage(String path) {
        String panel = PAGES.getOrDefault(path, "chat");
        try {
            String html = WebPages.render(webRoot, panel);
            return WebJson.bytes(HttpStatus.OK, html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8", "no-store");
        } catch (IOException error) {
            Log.warn("WebUI 页面渲染失败：" + Bot.error(error));
            return WebJson.bytes(HttpStatus.INTERNAL_SERVER_ERROR, ("页面渲染失败：" + Bot.error(error)).getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8", "no-store");
        }
    }

    /** 其它路径：{@code /index.html} 当对话页，其余如实 404（不返回 Boot 默认错误页）。 */
    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<?> fallback(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.equals("/index.html")) return renderPage("/");
        return WebJson.of(HttpStatus.NOT_FOUND, WebJson.error("未找到 " + path));
    }

    /** 静态资源：app.js / app.css / 图标。目录穿越一律 404。 */
    @RequestMapping(value = {"/app.js", "/app.css", "/favicon.ico", "/favicon-32.png",
            "/apple-touch-icon.png", "/icon-192.png", "/icon-512.png"},
            method = RequestMethod.GET)
    public ResponseEntity<byte[]> asset(HttpServletRequest request) {
        String relative = request.getRequestURI().substring(1);
        if (relative.contains("..") || relative.startsWith("/") || relative.isBlank()) {
            return WebJson.bytes(HttpStatus.NOT_FOUND, "未找到。".getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", "no-store");
        }
        Path target = webRoot.resolve(relative).normalize();
        if (!target.startsWith(webRoot) || !Files.isRegularFile(target)) {
            return WebJson.bytes(HttpStatus.NOT_FOUND, ("未找到 " + request.getRequestURI()).getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8", "no-store");
        }
        try {
            String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
            return WebJson.bytes(HttpStatus.OK, Files.readAllBytes(target), WebJson.contentTypeOf(name), "no-store");
        } catch (IOException error) {
            return WebJson.bytes(HttpStatus.INTERNAL_SERVER_ERROR, ("读取失败：" + Bot.error(error)).getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8", "no-store");
        }
    }
}

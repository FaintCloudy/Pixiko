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
 * 弹窗与查看器，以及固定在右侧的<b>全局对话栏</b>）加上<b>只属于该栏目</b>的面板标记，左栏里不会出现
 * 别的栏目的 DOM，前端也只加载这个栏目要用的数据（见 {@link WebPages}）。静态文件（app.js / app.css /
 * 图标）仍从 {@code webui/} 目录按需读取、一律 {@code no-store}，改完刷新即可，不用重新打包。
 */
@RestController
public class WebPageController {

    /**
     * 栏目页：路径 → 面板 id。
     *
     * <p>对话栏已经全局化（它是外壳的一部分，固定在每个页面的右 1/3，见 {@code webui/index.html}），
     * 因此它不再是一个栏目、也没有自己的面板 id：首页与 {@code /chat} 都渲染出图页（右侧照样有对话栏）。
     */
    static final Map<String, String> PAGES = new LinkedHashMap<>();
    static {
        PAGES.put("/", "gen");
        // 旧链接别名：对话以前是首页栏目，收藏/书签里的 /chat 不能 404。
        // 现在 /chat 与 / 渲染同一页（出图 + 右侧对话栏），对话栏本来就在。
        PAGES.put("/chat", "gen");
        PAGES.put("/gen", "gen");
        PAGES.put("/prompt", "prompt");
        PAGES.put("/styles", "styles");
        PAGES.put("/loras", "loras");
        PAGES.put("/functions", "functions");
        PAGES.put("/chatcfg", "chatcfg");
        PAGES.put("/system", "system");
        PAGES.put("/quest", "quest");
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

    /**
     * 栏目页：公共外壳 + 该栏目自己的面板。
     *
     * <p>注意：这里的路径列表是编译期常量，**新增栏目要同时改这里和上面的 PAGES**
     * （只加 PAGES 会 404：PAGES 负责"路径→面板"，这里负责放行路由）。
     */
    @RequestMapping(value = {"/", "/chat", "/gen", "/prompt", "/styles", "/loras", "/functions", "/chatcfg", "/system",
            "/quest", "/setup", "/logs", "/help"}, method = RequestMethod.GET)
    public ResponseEntity<byte[]> page(HttpServletRequest request) {
        return renderPage(request.getRequestURI());
    }

    private ResponseEntity<byte[]> renderPage(String path) {
        // 容忍尾部斜杠：/quest/ 与 /quest 是同一个页面（任务回执链接以前写成 /quest/#N，落地成 /quest/ 就 404）。
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        // 兜底用 gen：不存在的路径走上面的 404 分支，这里只是保证永远不会落到一个不存在的面板名上。
        String panel = PAGES.getOrDefault(path, "gen");
        try {
            String html = WebPages.render(webRoot, panel);
            return WebJson.bytes(HttpStatus.OK, html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8", "no-store");
        } catch (IOException error) {
            Log.warn("WebUI 页面渲染失败：" + Bot.error(error));
            return WebJson.bytes(HttpStatus.INTERNAL_SERVER_ERROR, ("页面渲染失败：" + Bot.error(error)).getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8", "no-store");
        }
    }

    /**
     * 其它路径：{@code /index.html} 当首页（＝出图页，右侧对话栏一直在），其余如实 404（不返回 Boot 默认错误页）。
     */
    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<?> fallback(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.equals("/index.html")) return renderPage("/");
        // 尾部斜杠当成同一个页面：老的 /quest/ 链接（以及用户手敲的 /gen/ 之类）不该 404。
        if (path.length() > 1 && path.endsWith("/") && PAGES.containsKey(path.substring(0, path.length() - 1)))
            return renderPage(path.substring(0, path.length() - 1));
        return WebJson.of(HttpStatus.NOT_FOUND, WebJson.error("未找到 " + path));
    }

    /**
     * Android 外壳的**网页预览**：把 app 那套原生外壳（标题栏 / 菜单 / 服务器设置 / 错误页 /
     * 长按图片的原生菜单）在浏览器里演一遍，中间用同源 iframe 装真正的控制台。
     *
     * <p>为什么必须由机器人自己伺服：同源才能把令牌写进 iframe 的 localStorage、才能往 iframe 里
     * 注入与 APK 里逐字相同的那段长按脚本（跨源会被浏览器拦住）。页面文件是 {@code webui/android-preview.html}，
     * 改完刷新即可，不用重新打包。
     */
    @RequestMapping(value = {"/android", "/android/"}, method = RequestMethod.GET)
    public ResponseEntity<byte[]> androidPreview() {
        Path target = webRoot.resolve("android-preview.html").normalize();
        if (!target.startsWith(webRoot) || !Files.isRegularFile(target)) {
            return WebJson.bytes(HttpStatus.NOT_FOUND,
                    "没找到 webui/android-preview.html：这是 Android 外壳的网页预览页，随 webui/ 目录一起分发。"
                            .getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", "no-store");
        }
        try {
            return WebJson.bytes(HttpStatus.OK, Files.readAllBytes(target), "text/html; charset=utf-8", "no-store");
        } catch (IOException error) {
            Log.warn("Android 预览页读取失败：" + Bot.error(error));
            return WebJson.bytes(HttpStatus.INTERNAL_SERVER_ERROR, ("读取失败：" + Bot.error(error)).getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8", "no-store");
        }
    }

    /**
     * 手机端 App 界面（**按 app 习惯重写的一套移动 UI**，与桌面控制台各写各的，复用同一套 {@code /api}
     * 接口）。路由 {@code /m}，静态资源在 {@code webui/m/} 下（{@code /m/app.css}、{@code /m/app.js}、
     * {@code /m/screen-*.js}…）。Android 外壳默认就装它（{@code http://<地址>:<端口>/m}）。
     */
    @RequestMapping(value = {"/m", "/m/"}, method = RequestMethod.GET)
    public ResponseEntity<byte[]> mobileApp() {
        return mobileFile("index.html", true);
    }

    /** 手机端界面的静态资源：{@code /m/**}。目录穿越一律 404。 */
    @RequestMapping(value = "/m/**", method = RequestMethod.GET)
    public ResponseEntity<byte[]> mobileAsset(HttpServletRequest request) {
        String path = request.getRequestURI();
        String relative = path.startsWith("/m/") ? path.substring("/m/".length()) : "";
        return mobileFile(relative, false);
    }

    /** 手机端界面的一个文件（{@code relative} 相对 {@code webui/m/}）；找不到时给一句人话说明。 */
    private ResponseEntity<byte[]> mobileFile(String relative, boolean html) {
        Path base = webRoot.resolve("m").normalize();
        Path target = base.resolve(relative == null ? "" : relative).normalize();
        if (relative == null || relative.isBlank() || relative.contains("..") || !target.startsWith(base)
                || !Files.isRegularFile(target)) {
            return WebJson.bytes(HttpStatus.NOT_FOUND,
                    ("没找到手机端界面文件：webui/m/" + (relative == null ? "" : relative)
                            + "（这是给 Android App 用的移动 UI，随 webui/m/ 目录一起分发）").getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8", "no-store");
        }
        try {
            String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
            String type = name.endsWith(".html") ? "text/html; charset=utf-8" : WebJson.contentTypeOf(name);
            return WebJson.bytes(HttpStatus.OK, Files.readAllBytes(target), type, "no-store");
        } catch (IOException error) {
            Log.warn("手机端界面文件读取失败（" + relative + "）：" + Bot.error(error));
            return WebJson.bytes(HttpStatus.INTERNAL_SERVER_ERROR, ("读取失败：" + Bot.error(error)).getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8", "no-store");
        }
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

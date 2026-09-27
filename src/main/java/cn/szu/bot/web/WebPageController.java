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

    /** Civitai 一次性登录页 + 书签小工具回填（不带访问令牌，靠一次性令牌鉴权）。 */
    @RequestMapping(value = "/civitai-login", method = RequestMethod.GET)
    public ResponseEntity<byte[]> civitaiLogin(HttpServletRequest request) {
        String token = request.getParameter("token") == null ? "" : request.getParameter("token");
        if (!bot.civitaiLinkUsable(token)) {
            String body = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<title>Pixiko · 链接已失效</title></head><body style=\"background:#0b1220;color:#e9eff8;"
                    + "font:15px/1.6 sans-serif;padding:32px\"><h1>链接已失效</h1>"
                    + "<p>一次性登录链接已过期或用过。请回到网页控制台「系统 → Civitai 账号」重新生成。</p>"
                    + "</body></html>";
            return WebJson.bytes(HttpStatus.GONE, body.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8", "no-store");
        }
        JsonObject status = bot.civitaiStatus();
        String host = status.get("host").getAsString();
        String page = """
            <!DOCTYPE html>
            <html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Pixiko · Civitai 登录</title>
            <style>
            body{margin:0;background:#0b1220;color:#e9eff8;font:15px/1.6 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif}
            main{max-width:760px;margin:0 auto;padding:28px 18px 60px}
            h1{font-size:20px;margin:0 0 6px}
            a{color:#5aa2ff}
            .card{background:rgba(23,33,51,.82);border:1px solid rgba(120,150,200,.18);border-radius:20px;padding:16px;margin:14px 0}
            textarea{width:100%%;min-height:120px;background:rgba(9,15,27,.6);color:inherit;border:1px solid rgba(120,150,200,.18);border-radius:14px;padding:10px;font:13px/1.5 ui-monospace,Consolas,monospace}
            button{background:linear-gradient(135deg,#5aa2ff,#b98bff);color:#05101d;border:0;border-radius:14px;padding:11px 18px;font-weight:650;cursor:pointer}
            code{background:rgba(120,160,220,.14);padding:1px 5px;border-radius:6px}
            .muted{color:#8ea2bf;font-size:13px}
            .ok{color:#4fd7a8}.bad{color:#ff7d8a}
            .bookmarklet{display:inline-block;background:#17213a;border:1px solid rgba(120,150,200,.28);border-radius:12px;padding:8px 12px;color:#e9eff8;text-decoration:none;font-weight:600}
            </style></head><body><main>
            <h1>Civitai 登录（一次性链接）</h1>
            <p class="muted">当前站点：<code>%s</code>　令牌 %d 分钟内有效，用掉即作废。</p>
            <div class="card">
              <b>第一步：登录 Civitai</b>
              <p><a href="%s" target="_blank" rel="noreferrer">在新标签页打开 %s 并登录</a></p>
              <p class="muted">没有账号 Cookie 时，机器人只能用 <code>civitai.com</code> 搜索和下载，成人内容与部分模型不可见。</p>
            </div>
            <div class="card">
              <b>第二步：把 Cookie 交回来（两种任选）</b>
              <p>① 把下面这个按钮拖到书签栏（或右键「收藏」），<b>回到刚登录的 civitai 页面</b>再点它，就会自动回填：</p>
              <p><a class="bookmarklet" href="%s">回填到 Pixiko</a></p>
              <p class="muted">书签小工具读取 <code>document.cookie</code> 发给本机机器人；在控制台这一页点会提示"先打开 civitai 页面"，那是正常的。</p>
              <p>② 或者手动：在 Civitai 页按 F12 → Network → 任选一个请求 → 复制请求头里的 <code>cookie</code> 整段，粘到下面。</p>
              <textarea id="cookie" placeholder="粘贴浏览器的 Cookie 里 civitai 相关的那几条 name=value"></textarea>
              <p><button id="save">保存 Cookie</button> <span id="result"></span></p>
            </div>
            <p class="muted">保存成功后可以直接关掉本页；机器人下次搜索/下载就会用这个账号。</p>
            <script>
            const TOKEN = %s;
            const ENDPOINT = "/civitai-cookie";
            async function send(cookie) {
              const result = document.getElementById('result');
              result.textContent = '正在保存…'; result.className = '';
              try {
                const response = await fetch(ENDPOINT, { method: 'POST', headers: {'Content-Type': 'text/plain'},
                  body: JSON.stringify({ token: TOKEN, cookie }) });
                const data = await response.json();
                if (data.ok) { result.textContent = '已保存（' + (data.cookieHint || '') + '）'; result.className = 'ok'; }
                else { result.textContent = data.error || '保存失败'; result.className = 'bad'; }
              } catch (error) { result.textContent = '保存失败：' + error.message; result.className = 'bad'; }
            }
            document.getElementById('save').onclick = () => send(document.getElementById('cookie').value);
            if (location.hash.startsWith('#cookie=')) send(decodeURIComponent(location.hash.slice(8)));
            </script>
            </main></body></html>
            """.formatted(host, Bot.CIVITAI_LINK_MINUTES, host, host,
                          bookmarklet(token, settings.webPort()), host, Json.GSON.toJson(token));
        return WebJson.bytes(HttpStatus.OK, page.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8", "no-store");
    }

    /**
     * 书签小工具：在 Civitai 页面上把 document.cookie 发回本机。用 http://127.0.0.1 是因为浏览器把回环地址
     * 当作可信来源，https 页面调用它不会被混合内容拦掉；跨域简单请求（text/plain）也不会触发预检。
     */
    private static String bookmarklet(String token, int port) {
        return "javascript:(function(){"
                // 在控制台自己的页面上点这个书签时，document.cookie 里没有 Civitai 的任何东西：
                // 直接报"没收到 Cookie"会让人以为坏了，这里先拦下来讲清楚该在哪点。
                + "if(!document.cookie){alert('这个书签要在大站页面上点：先打开 civitai.red 并登录，再点它');return;}"
                + "fetch('http://127.0.0.1:" + port
                + "/civitai-cookie',{method:'POST',headers:{'Content-Type':'text/plain'},"
                + "body:JSON.stringify({token:" + Json.GSON.toJson(token) + ",cookie:document.cookie})})"
                + ".then(r=>r.json()).then(d=>alert(d.ok?'Pixiko：Cookie 已保存':'Pixiko：'+(d.error||'保存失败')))"
                + ".catch(e=>alert('Pixiko：保存失败 '+e));})()";
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
    @RequestMapping(value = {"/", "/gen", "/prompt", "/styles", "/loras", "/functions", "/chatcfg", "/system", "/logs", "/help"},
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

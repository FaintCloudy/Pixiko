package cn.szu.bot.web;

import cn.szu.bot.Bot;
import cn.szu.bot.Log;
import cn.szu.bot.Settings;
import cn.szu.bot.WebSetup;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 访问令牌校验：{@code /api/**} 必须带 {@code Authorization: Bearer <令牌>}、{@code X-Webui-Token} 或
 * {@code ?token=}；失败次数过多会被短暂冷却。页面与静态资源不校验（登录页要能先打开，
 * Civitai 一次性登录页有自己的令牌）。
 *
 * <p>例外只有三个，都写死在 {@link #shouldNotFilter} 里（**过滤器在路由匹配之前跑**，所以免令牌必须
 * 在这里放行，控制器里再写也没用）：
 * <ul>
 *   <li><b>首次配置</b>：还没配好 DeepSeek 密钥时，{@code /api/config/**} 对本机（回环地址）免令牌。
 *       否则第一次运行会死锁在"要有令牌才能配置、要配置才有令牌"上；非本机访问仍然要令牌，
 *       避免局域网里有人替你把机器人配成他的。</li>
 *   <li><b>安卓端自动更新</b>：{@code POST /api/app/update} 与 {@code GET /api/app/apk}
 *       （见 {@link AppUpdate}）——手机里没有、也不该存网页令牌。这两个接口是只读的（不写文件、
 *       不改 config），只允许局域网访问是既有前提。**别的 {@code /api/**} 一律照旧要令牌，
 *       包括不存在的路径**（所以"未知接口不带令牌"仍然是 401，不是 404）。</li>
 * </ul>
 */
@Component
public class WebAuthFilter extends OncePerRequestFilter {

    private final Settings settings;
    private final Map<Long, String[]> failedAttempts = new ConcurrentHashMap<>();

    public WebAuthFilter(Settings settings) { this.settings = settings; }

    /** 只有 /api 接口需要令牌；安卓端自动更新的两条接口在这里就放行（过滤器在路由之前）。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) return true;
        // 只放行这两条：POST /api/app/update（问版本）与 GET /api/app/apk（下发布包）。
        // 方法也要对上，免得有人拿它们当别的动词的口子。
        return appUpdateBypass(path, request.getMethod());
    }

    /**
     * 安卓端免令牌白名单：**两条接口都要 GET/POST 对上方法**才算（就这两条，别的 {@code /api/**} 一个字都不放宽）。
     *
     * <p>放行这两条的原因：手机端没有网页令牌。两条都是只读接口（{@link AppUpdate} 不写任何文件、
     * 不改 config.json），而且只允许局域网访问是既有前提。
     *
     * <p>方法也要对上：{@code POST /api/app/apk} 拿不到字节流（契约里它只认 GET），所以它**照旧要令牌**——
     * 少一个「不带令牌就能戳到的入口」。安卓端探测时误用过 POST，需要它返回 405 的话把 {@code "POST"}
     * 加进下面就一行（那等于允许无凭据请求走到控制器拿 405），这里按「白名单越窄越好」从严。
     */
    private static boolean appUpdateBypass(String path, String method) {
        if (path.equals("/api/app/update")) return "POST".equals(method);
        if (path.equals("/api/app/apk")) return "GET".equals(method) || "HEAD".equals(method);
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (setupBypass(request)) { chain.doFilter(request, response); return; }
        String expected;
        try { expected = settings.webToken(); }
        catch (IOException error) { expected = ""; Log.warn("WebUI 令牌读取失败：" + Bot.error(error)); }
        if (expected.isBlank()) {
            WebJson.write(response, 503, WebJson.error("WebUI 未配置访问令牌，请在 config.json 设置 webui.access_token。"));
            return;
        }
        String provided = provided(request);
        if (provided != null && constantTimeEquals(expected, provided)) { chain.doFilter(request, response); return; }
        String client = String.valueOf(request.getRemoteAddr());
        String[] state = failedAttempts.computeIfAbsent(System.nanoTime() % 1024, key -> new String[]{"0", "0"});
        long now = System.currentTimeMillis();
        if (Long.parseLong(state[1]) > now) {
            WebJson.write(response, 429, WebJson.error("尝试次数过多，请稍后再试。"));
            return;
        }
        int failures = Integer.parseInt(state[0]) + 1;
        state[0] = String.valueOf(failures);
        if (failures >= 10) { state[0] = "0"; state[1] = String.valueOf(now + 30_000); }
        Log.warn("WebUI 鉴权失败（" + client + "，第 " + failures + " 次）");
        WebJson.write(response, 401, WebJson.error("访问令牌不正确。"));
    }

    /**
     * 首次配置期间，本机访问 {@code /api/config/**} 免令牌：第一次运行还没有 DeepSeek 密钥，
     * 而这些接口正是用来填密钥的。非回环地址不放行（局域网里的别人不该能替你配置）。
     */
    private boolean setupBypass(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/config/")) return false;
        if (!isLoopback(request.getRemoteAddr())) return false;
        try { return WebSetup.open(settings); }
        catch (RuntimeException error) { return false; }
    }

    private static boolean isLoopback(String address) {
        if (address == null) return false;
        String value = address.strip();
        return value.equals("127.0.0.1") || value.equals("::1") || value.equals("0:0:0:0:0:0:0:1")
                || value.startsWith("127.");
    }

    /** 三种带令牌的方式：Bearer 头、X-Webui-Token 头、?token= 查询串（<img src> 只能带查询串）。 */
    private static String provided(HttpServletRequest request) {
        String provided = null;
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) provided = header.substring(7).strip();
        if (provided == null) {
            String query = request.getQueryString();
            if (query != null) for (String part : query.split("&")) {
                int at = part.indexOf('=');
                if (at > 0 && part.substring(0, at).equals("token"))
                    provided = URLDecoder.decode(part.substring(at + 1), StandardCharsets.UTF_8);
            }
        }
        if (request.getHeader("X-Webui-Token") != null) provided = request.getHeader("X-Webui-Token").strip();
        return provided;
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}

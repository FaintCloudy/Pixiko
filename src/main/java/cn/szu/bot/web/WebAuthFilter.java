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
 * <p>唯一的例外是<b>首次配置</b>：还没配好 DeepSeek 密钥时，{@code /api/config/**} 对本机（回环地址）
 * 免令牌。否则第一次运行会死锁在"要有令牌才能配置、要配置才有令牌"上；非本机访问仍然要令牌，
 * 避免局域网里有人替你把机器人配成他的。
 */
@Component
public class WebAuthFilter extends OncePerRequestFilter {

    private final Settings settings;
    private final Map<Long, String[]> failedAttempts = new ConcurrentHashMap<>();

    public WebAuthFilter(Settings settings) { this.settings = settings; }

    /** 只有 /api 接口需要令牌。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
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

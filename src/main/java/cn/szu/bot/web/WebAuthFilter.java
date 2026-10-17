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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

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
 *
 * <p><b>失败限流</b>（见 {@link Throttle}）：同一个客户端每失败一次记一次，连续
 * {@value Throttle#MAX_FAILURES} 次后冷却 30 秒（{@value Throttle#COOLDOWN_MILLIS} 毫秒），冷却期间一律 429；
 * 登录成功当场清掉这个客户端的失败记录。限流的键是**直接连接地址**
 * （{@link HttpServletRequest#getRemoteAddr()}）。只有配置显式打开 {@code webui.trust_forwarded_for}
 * **且**直接连接地址在本机或 {@code webui.trusted_proxies} 白名单里时，才读 {@code X-Forwarded-For}，
 * 并取**最右边那一跳不受信的地址**（真正的客户端）；每一跳都做长度与格式校验，有一跳不像地址就整条头
 * 都不信、退回直接连接地址——默认不许信任任意来源，也不许拿伪造的头去挤别人的计数桶。
 *
 * <p>之前的实现用 {@code System.nanoTime() % 1024} 当计数键：同一个客户端攒不住次数、不同客户端互相污染，
 * 而且状态是共享的 {@code String[]}、没有 TTL、没有容量上限、成功后也不清理。
 */
@Component
public class WebAuthFilter extends OncePerRequestFilter {

    /** 放行。 */
    public static final int ALLOW = 200;
    /** 令牌不对：与改造前的 401 + {@code {"error":"访问令牌不正确。"}} 一致。 */
    public static final int UNAUTHORIZED = 401;
    /** 失败次数过多、还在冷却里：与改造前的 429 + {@code {"error":"尝试次数过多，请稍后再试。"}} 一致。 */
    public static final int THROTTLED = 429;

    /** 一个客户端地址（含 X-Forwarded-For 的一跳）最多认多少字符；超长的不拿来当键。 */
    static final int MAX_ADDRESS_LENGTH = 64;
    /** {@code X-Forwarded-For} 整个头最多认多少字符（防伪造、防内存放大）。 */
    static final int MAX_FORWARDED_LENGTH = 256;
    /** {@code X-Forwarded-For} 最多认几跳。 */
    static final int MAX_FORWARDED_HOPS = 8;
    /** 地址认不出来时用的键（不会与任何真实地址冲突）。 */
    public static final String UNKNOWN_CLIENT = "unknown";

    private final Settings settings;
    private final Throttle throttle = new Throttle();

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
        String client = clientKey(request);
        if (setupBypass(request, client)) { chain.doFilter(request, response); return; }
        String expected;
        try { expected = settings.webToken(); }
        catch (IOException error) { expected = ""; Log.warn("WebUI 令牌读取失败：" + Bot.error(error)); }
        if (expected.isBlank()) {
            WebJson.write(response, 503, WebJson.error("WebUI 未配置访问令牌，请在 config.json 设置 webui.access_token。"));
            return;
        }
        int decision = decide(expected, provided(request), client, throttle);
        if (decision == ALLOW) { chain.doFilter(request, response); return; }
        WebJson.write(response, decision, WebJson.error(decision == THROTTLED
                ? "尝试次数过多，请稍后再试。" : "访问令牌不正确。"));
    }

    /**
     * 一次鉴权判定（纯逻辑，测试直接驱动）：令牌对就放行，并**清掉这个客户端的失败记录**；
     * 不对就记一次失败——已经冷却中的回 {@link #THROTTLED}，否则回 {@link #UNAUTHORIZED}。
     *
     * <p>顺序与改造前一致：冷却中的请求**不计数、也不延长冷却**；触发冷却的那第 10 次本身照旧回 401，
     * 下一次才 429。
     */
    public static int decide(String expected, String provided, String client, Throttle throttle) {
        if (provided != null && expected != null && !expected.isBlank() && constantTimeEquals(expected, provided)) {
            throttle.succeeded(client);
            return ALLOW;
        }
        Throttle.Outcome outcome = throttle.fail(client);
        if (outcome.blocked()) return THROTTLED;
        Log.warn("WebUI 鉴权失败（" + client + "，第 " + outcome.failures() + " 次）");
        return UNAUTHORIZED;
    }

    /**
     * 限流键：默认就是直接连接地址。只有 {@code webui.trust_forwarded_for} 打开、且直接连接地址是本机
     * （回环）或 {@code webui.trusted_proxies} 里列出的代理时，才看 {@code X-Forwarded-For}。
     */
    private String clientKey(HttpServletRequest request) {
        boolean trust = settings.webTrustForwardedFor();
        List<String> proxies = trust ? settings.webTrustedProxies() : List.of();
        return clientKey(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), trust, proxies);
    }

    /**
     * {@link #clientKey(HttpServletRequest)} 的纯函数版本（测试直接驱动，也方便一眼看清信任策略）。
     *
     * @param remoteAddr   直接连接地址（{@code request.getRemoteAddr()}）
     * @param forwardedFor {@code X-Forwarded-For} 原值，可为 null
     * @param trust        是否允许看这个头（{@code webui.trust_forwarded_for}）
     * @param trustedProxies 受信代理白名单（{@code webui.trusted_proxies}，精确匹配；回环地址永远受信）
     */
    public static String clientKey(String remoteAddr, String forwardedFor, boolean trust, List<String> trustedProxies) {
        String direct = address(remoteAddr);
        // 默认（trust=false）一个字节的 X-Forwarded-For 都不看：谁都能往这个头里写字，采信就等于让人自选身份。
        if (!trust || !trustedProxy(direct, trustedProxies)) return direct;
        if (forwardedFor == null) return direct;
        String header = forwardedFor.strip();
        if (header.isEmpty() || header.length() > MAX_FORWARDED_LENGTH) return direct;
        String[] hops = header.split(",", -1);
        if (hops.length > MAX_FORWARDED_HOPS) return direct;
        // 从右往左：跳过受信代理，遇到第一条不受信的地址就是真正的客户端（"最后一跳可信值"）。
        for (int index = hops.length - 1; index >= 0; index--) {
            String hop = hops[index].strip();
            // 有一跳不像地址就整条头都不信：伪造的头不该创造出假客户端，更不该把别人挤进同一个桶。
            if (!isAddress(hop)) return direct;
            if (!trustedProxy(hop, trustedProxies)) return hop;
        }
        return direct;
    }

    /**
     * 首次配置期间，本机访问 {@code /api/config/**} 免令牌：第一次运行还没有 DeepSeek 密钥，
     * 而这些接口正是用来填密钥的。非回环地址不放行（局域网里的别人不该能替你配置）。
     *
     * <p>用的是上面那个限流键（{@code client}）而不是裸的 {@code remoteAddr}：反向代理后面
     * {@code remoteAddr} 永远是代理自己，那样等于把"本机"免令牌开给所有经代理进来的人。
     * 信任开关默认关着，所以这里的行为与改造前完全一致。
     */
    private boolean setupBypass(HttpServletRequest request, String client) {
        if (!request.getRequestURI().startsWith("/api/config/")) return false;
        if (!isLoopback(client)) return false;
        try { return WebSetup.open(settings); }
        catch (RuntimeException error) { return false; }
    }

    /** 直接连接地址：合法的 IPv4/IPv6 原样当键；不像地址的（很少见）截断并洗成可安全当键的字符。 */
    static String address(String raw) {
        if (raw == null) return UNKNOWN_CLIENT;
        String value = raw.strip();
        if (value.isEmpty()) return UNKNOWN_CLIENT;
        if (isAddress(value)) return value;
        return sanitize(value);
    }

    /** 地址形状校验：严格 IPv4 或结构合法的 IPv6（不含空白、逗号、引号，长度有上限）。 */
    static boolean isAddress(String value) {
        return ipv4(value) != null || isIpv6(value);
    }

    /** 回环：IPv4 的 127.0.0.0/8、IPv6 的 ::1，以及双栈套接字把 IPv4 客户端报成的 {@code ::ffff:127.x.y.z}。 */
    static boolean isLoopback(String value) {
        if (value == null || value.isEmpty()) return false;
        int[] octets = ipv4(value);
        if (octets != null) return octets[0] == 127;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("::1") || lower.equals("0:0:0:0:0:0:0:1")) return true;
        int mapped = lower.startsWith("::ffff:") ? 7 : lower.startsWith("0:0:0:0:0:0:ffff:") ? 18 : -1;
        if (mapped < 0) return false;
        int[] tail = ipv4(lower.substring(mapped));
        return tail != null && tail[0] == 127;
    }

    /** 受信代理：回环地址永远受信；别的必须**精确**出现在白名单里（不做前缀匹配，免得被 127.0.0.1.evil 之类骗过）。 */
    private static boolean trustedProxy(String address, List<String> trustedProxies) {
        if (address.isEmpty() || UNKNOWN_CLIENT.equals(address)) return false;
        if (isLoopback(address)) return true;
        if (trustedProxies == null) return false;
        for (String entry : trustedProxies) if (entry != null && entry.strip().equals(address)) return true;
        return false;
    }

    /** 严格 IPv4：四段十进制、每段 0–255；不是这种形态就返回 null（"看着像"不算）。 */
    static int[] ipv4(String value) {
        if (value == null || value.length() < 7 || value.length() > 15) return null;
        int[] octets = new int[4];
        int index = 0, digits = 0, number = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                if (++digits > 3) return null;
                number = number * 10 + (c - '0');
                if (number > 255) return null;
            } else if (c == '.' && digits > 0 && index < 3) {
                octets[index++] = number; number = 0; digits = 0;
            } else return null;
        }
        if (digits == 0 || index != 3) return null;
        octets[3] = number;
        return octets;
    }

    /** IPv6 结构校验：至多一处 {@code ::}、每组最多 4 个十六进制位、组数对得上（允许末尾嵌 IPv4）。 */
    static boolean isIpv6(String value) {
        if (value == null || value.length() < 2 || value.length() > 45 || value.indexOf(':') < 0) return false;
        String text = value;
        int tail = text.lastIndexOf(':');
        if (text.indexOf('.') >= 0) {
            // ::ffff:192.168.0.1 这种尾巴：换成两个占位组，下面只数十六进制组。
            if (tail < 0 || ipv4(text.substring(tail + 1)) == null) return false;
            text = text.substring(0, tail + 1) + "0:0";
        }
        int first = text.indexOf("::");
        if (first >= 0 && first != text.lastIndexOf("::")) return false;
        int groups = 0;
        for (String piece : text.split(":", -1)) {
            if (piece.isEmpty()) continue;
            if (piece.length() > 4) return false;
            for (int i = 0; i < piece.length(); i++) if (Character.digit(piece.charAt(i), 16) < 0) return false;
            groups++;
        }
        return first >= 0 ? groups <= 7 : groups == 8;
    }

    /** 认不出来的地址洗成只含 {@code [0-9A-Fa-f:.]} 的键：不留空白/换行/引号，长度也截断。 */
    private static String sanitize(String value) {
        int length = Math.min(value.length(), MAX_ADDRESS_LENGTH);
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == '.' || c == ':' || c == '%';
            builder.append(allowed ? c : '_');
        }
        return builder.toString();
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

    /**
     * 失败计数/冷却表：**不可变状态 + 原子更新 + TTL + 容量上限**。
     *
     * <ul>
     *   <li>状态是 {@link FailState} record，每次失败换一条新的；更新全部在
     *       {@link ConcurrentHashMap#compute} 里完成，同一客户端的并发失败不会丢更新。</li>
     *   <li>TTL：{@value #WINDOW_MILLIS} 毫秒内算一个计数窗口（{@value #WINDOW_MILLIS} 毫秒没再失败，
     *       计数从头开始），过期条目在失败路径上顺手清掉。</li>
     *   <li>容量：最多 {@value #MAX_CLIENTS} 个客户端，超了按"最久没有失败"淘汰，表不会无限长。</li>
     * </ul>
     *
     * <p>公开只是为了让测试能注入可控时钟、直接驱动它；生产路径只有 {@link WebAuthFilter} 在用。
     */
    public static final class Throttle {

        /** 连续失败到这个次数就冷却（与改造前一致：第 10 次本身照旧回 401，同时进入冷却）。 */
        public static final int MAX_FAILURES = 10;
        /** 冷却时长，与改造前一致的 30 秒。 */
        public static final long COOLDOWN_MILLIS = 30_000L;
        /** 计数窗口：这么久没再失败，就当成新的一轮（改造前没有 TTL，失败次数会一直攒着）。 */
        public static final long WINDOW_MILLIS = 15 * 60_000L;
        /** 表的最大条目数（改造前是 1024 个 nanoTime 桶，且从不清理）。 */
        public static final int MAX_CLIENTS = 4096;

        /** 每这么多次失败顺手清一遍过期条目（2 的幂，取模写成位与，溢出也不会失灵）。 */
        private static final int SWEEP_EVERY = 256;

        /** 一个客户端的失败状态。**不可变**：读者看不到改了一半的对象。 */
        public record FailState(int failures, long firstAt, long lastAt, long blockedUntil) {
            public FailState {
                if (failures < 0) throw new IllegalArgumentException("failures 不能为负");
            }
        }

        /** 一次失败的结果：{@code blocked=true} 表示这次该回 429；{@code failures} 是这次之后的累计次数。 */
        public record Outcome(boolean blocked, int failures) { }

        private final ConcurrentHashMap<String, FailState> states = new ConcurrentHashMap<>();
        private final LongSupplier clock;
        private final int maxFailures;
        private final long cooldownMillis;
        private final long windowMillis;
        private final int maxClients;
        private final AtomicInteger activity = new AtomicInteger();

        public Throttle() { this(System::currentTimeMillis); }

        public Throttle(LongSupplier clock) { this(clock, MAX_FAILURES, COOLDOWN_MILLIS, WINDOW_MILLIS, MAX_CLIENTS); }

        public Throttle(LongSupplier clock, int maxFailures, long cooldownMillis, long windowMillis, int maxClients) {
            if (clock == null) throw new IllegalArgumentException("时钟不能为空");
            if (maxFailures < 1 || cooldownMillis < 1 || windowMillis < 1 || maxClients < 1)
                throw new IllegalArgumentException("限流参数不合法");
            this.clock = clock;
            this.maxFailures = maxFailures;
            this.cooldownMillis = cooldownMillis;
            this.windowMillis = windowMillis;
            this.maxClients = maxClients;
        }

        /**
         * 记一次失败。读—判断—写**全在一次 {@code compute} 里**完成，所以同一客户端的并发失败是原子累计的；
         * 冷却中的请求不计数、也不延长冷却（与改造前一致）。
         */
        public Outcome fail(String client) {
            String key = client == null || client.isBlank() ? UNKNOWN_CLIENT : client;
            long now = clock.getAsLong();
            AtomicReference<Outcome> outcome = new AtomicReference<>();
            states.compute(key, (ignored, previous) -> {
                if (previous != null && now < previous.blockedUntil()) {
                    outcome.set(new Outcome(true, 0));      // 还在冷却：状态原样留着
                    return previous;
                }
                FailState next = advance(previous, now);
                outcome.set(new Outcome(false, next.failures() == 0 ? maxFailures : next.failures()));
                return next;
            });
            maintain(now);
            return outcome.get();
        }

        /** 这个客户端登录成功：整条删掉，下次失败从 1 重新数。 */
        public void succeeded(String client) {
            if (client != null) states.remove(client);
        }

        /** 表里现在有多少个客户端（测试/观测用）。 */
        public int size() { return states.size(); }

        /** 这个客户端现在的失败次数；没有记录、已经过了窗口、或在冷却里（计数已清零）都算 0。 */
        public int failures(String client) {
            FailState state = client == null ? null : states.get(client);
            if (state == null) return 0;
            if (clock.getAsLong() - state.lastAt() >= windowMillis) return 0;
            return state.failures();
        }

        /** 这个客户端此刻是否在冷却里。 */
        public boolean blocked(String client) {
            FailState state = client == null ? null : states.get(client);
            return state != null && state.blockedUntil() > clock.getAsLong();
        }

        /** 读旧状态、造新状态（不改入参，也不碰表）。 */
        private FailState advance(FailState previous, long now) {
            if (previous != null
                    && (now - previous.lastAt() >= windowMillis
                    || (previous.blockedUntil() != 0 && now >= previous.blockedUntil()))) {
                // 窗口过了 / 冷却结束了：计数从头开始（旧实现也是触顶即清零）。
                previous = null;
            }
            int failures = (previous == null ? 0 : previous.failures()) + 1;
            long firstAt = previous == null ? now : previous.firstAt();
            long blockedUntil = 0;
            if (failures >= maxFailures) { failures = 0; blockedUntil = now + cooldownMillis; }
            return new FailState(failures, firstAt, now, blockedUntil);
        }

        /** 表维护：超容量就淘汰最旧的；另外每隔一段顺手清一次过期条目（都在失败路径上做）。 */
        private void maintain(long now) {
            if (states.size() > maxClients) evict();
            if ((activity.incrementAndGet() & (SWEEP_EVERY - 1)) == 0) sweep(now);
        }

        /** 按"最久没有失败"淘汰，直到回到上限以内（CAS 删除：刚好又失败过一次的条目不会被误删）。 */
        private void evict() {
            int excess = states.size() - maxClients;
            if (excess <= 0) return;
            List<Map.Entry<String, FailState>> entries = new java.util.ArrayList<>(states.entrySet());
            entries.sort(java.util.Comparator.comparingLong(entry -> entry.getValue().lastAt()));
            for (Map.Entry<String, FailState> entry : entries) {
                if (excess <= 0) break;
                if (states.remove(entry.getKey(), entry.getValue())) excess--;
            }
        }

        /** 清掉过了窗口、又不在冷却里的条目。 */
        private void sweep(long now) {
            for (Map.Entry<String, FailState> entry : states.entrySet()) {
                FailState state = entry.getValue();
                if (state.blockedUntil() <= now && now - state.lastAt() >= windowMillis)
                    states.remove(entry.getKey(), state);
            }
        }
    }
}

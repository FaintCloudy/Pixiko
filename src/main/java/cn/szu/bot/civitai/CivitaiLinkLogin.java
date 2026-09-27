package cn.szu.bot.civitai;

import cn.szu.bot.civitai.CivitaiClient.Response;
import cn.szu.bot.civitai.CivitaiClient.Transport;
import com.google.gson.JsonObject;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Civitai 一次性登录链接 → 会话 Cookie（设计见 {@code docs/MIGRATION.md}「Civitai 账号」一节）。
 *
 * <p>用户在 Civitai 请求登录邮件后，拿到的是 {@code https://auth.civitai.com/login/email/verify?token=…}：
 * 这条链接只能用一次，正常情况下由浏览器点开，它会逐跳跳到站点首页并一路落下会话 Cookie。这里由机器人
 * 替浏览器走一遍——<b>最多 {@value #MAX_HOPS} 跳</b>跟着 {@code Location} 走，逐跳收 {@code Set-Cookie}，
 * 只保留 civitai 域上的条目（丢弃 {@code oauth_bridge}，同名 Cookie 以最后一跳为准），拼成一份交给调用方。
 *
 * <p><b>链接里的一次性令牌与收下来的 Cookie 都不进日志</b>：调用方只记录脱敏后的 {@code cookieHint}。
 * 代理规则与搜索/下载完全一致（{@code civitai.proxy_url}，只认本机 HTTP 代理）。
 *
 * <p>HTTP 走 {@link CivitaiClient.Transport}，测试可以注入假传输整链验证（与 {@code CivitaiClientTest} 同一套做法）。
 */
public final class CivitaiLinkLogin {

    private static final int MAX_HOPS = 10;
    /** 认得的 civitai 域名：登录链从 auth.civitai.com 出发，落到 civitai.com 或镜像 civitai.red。 */
    private static final Set<String> CIVITAI_HOSTS = Set.of("civitai.com", "civitai.red");
    /** OAuth 桥接用的临时 Cookie，留着会把后续 API 调用带偏。 */
    private static final Set<String> DROPPED = Set.of("oauth_bridge");

    private CivitaiLinkLogin() { }

    /** 跟随结果：拼好的 Cookie、最后一跳地址、实际跳数。 */
    public record Result(String cookie, String finalUrl, int hops) { }

    /** 走完整条登录链（真实网络）。 */
    public static Result follow(JsonObject config, String link) throws Exception {
        return follow(config, link, defaultTransport(config));
    }

    /**
     * 走完整条登录链。
     *
     * @throws IllegalArgumentException 链接不是 civitai 的 https 链接、跳数超限或代理配置非法
     */
    public static Result follow(JsonObject config, String link, Transport transport) throws Exception {
        URI current = loginUri(link);
        Map<String, String> jar = new LinkedHashMap<>();
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "text/html,application/xhtml+xml");
            headers.put("User-Agent", "Pixiko/1.0 (civitai-login)");
            // 浏览器会把前面几跳落下的 Cookie 带下去，授权链要靠它认出同一次登录。
            if (!jar.isEmpty()) headers.put("Cookie", join(jar));
            int status;
            Map<String, java.util.List<String>> responseHeaders;
            try (Response response = transport.get(current, headers, Duration.ofSeconds(30))) {
                status = response.status();
                responseHeaders = response.headers();
                collect(responseHeaders, current, jar);
            }
            String location = first(responseHeaders, "location");
            if (location.isBlank() || status / 100 != 3) return new Result(join(jar), current.toString(), hop);
            URI next = current.resolve(location);
            if (!"https".equalsIgnoreCase(next.getScheme())) return new Result(join(jar), current.toString(), hop);
            current = next;
        }
        throw new IllegalArgumentException("登录链接跳转次数过多（超过 " + MAX_HOPS + " 跳）。请重新在 Civitai 申请一封登录邮件。");
    }

    /** 带 Cookie 打一次 LoRA 列表接口：200 说明这份会话能用。 */
    public static boolean verify(JsonObject config, String cookie, String baseUrl) {
        try {
            return verify(config, cookie, baseUrl, defaultTransport(config));
        } catch (Exception error) {
            return false;
        }
    }

    /** 同上，可注入传输。 */
    public static boolean verify(JsonObject config, String cookie, String baseUrl, Transport transport) throws Exception {
        String base = String.valueOf(baseUrl == null ? "" : baseUrl).strip();
        if (base.isEmpty()) base = CivitaiClient.DEFAULT_BASE_URL;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        try (Response response = transport.get(URI.create(base + "/api/v1/models?limit=1&types=LORA"),
                Map.of("Cookie", cookie), Duration.ofSeconds(30))) {
            return response.status() == 200;
        }
    }

    /** 只接受 civitai 的 https 链接：这条链接由机器人替用户打开，不能变成任意地址的抓取器。 */
    private static URI loginUri(String link) {
        String text = String.valueOf(link == null ? "" : link).strip();
        if (text.isEmpty()) throw new IllegalArgumentException("请粘贴邮件里的一次性登录链接。");
        URI uri;
        try { uri = URI.create(text); }
        catch (Exception error) { throw new IllegalArgumentException("这不是一条完整的链接。"); }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !civitaiHost(host))
            throw new IllegalArgumentException("只接受 civitai 的 https 登录链接（形如 https://auth.civitai.com/login/email/verify?token=…）。");
        return uri;
    }

    /** 真实网络：与 CivitaiClient 同一套代理规则（只认本机 HTTP 代理）。 */
    private static Transport defaultTransport(JsonObject config) throws Exception {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20));
        InetSocketAddress proxy = CivitaiClient.proxyAddress(config);
        if (proxy != null) builder.proxy(ProxySelector.of(proxy));
        HttpClient client = builder.build();
        return (uri, headers, timeout) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).GET();
            headers.forEach(request::header);
            HttpResponse<java.io.InputStream> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new Response(response.statusCode(), response.headers().map(), response.body());
        };
    }

    private static boolean civitaiHost(String host) {
        return CIVITAI_HOSTS.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    /** 收下这一跳的 Set-Cookie：丢掉 oauth_bridge、已过期和非 civitai 域的条目。 */
    private static void collect(Map<String, java.util.List<String>> headers, URI hop, Map<String, String> jar) {
        for (String header : firstAll(headers, "set-cookie")) {
            String pair = header.split(";", 2)[0].strip();
            int at = pair.indexOf('=');
            if (at <= 0) continue;
            String name = pair.substring(0, at).strip(), value = pair.substring(at + 1).strip();
            if (name.isEmpty() || value.isEmpty()) continue;
            if (DROPPED.contains(name.toLowerCase(Locale.ROOT))) continue;
            if (header.toLowerCase(Locale.ROOT).contains("max-age=0")) continue;
            if (!civitaiDomain(header, hop)) continue;
            jar.put(name, value);
        }
    }

    /** Cookie 归属的域：有 Domain 属性用它，没有就当落在当前这一跳的主机上。 */
    private static boolean civitaiDomain(String header, URI hop) {
        String host = hop.getHost() == null ? "" : hop.getHost().toLowerCase(Locale.ROOT);
        for (String attribute : header.split(";")) {
            String part = attribute.strip();
            if (part.regionMatches(true, 0, "domain=", 0, 7)) host = part.substring(7).strip().toLowerCase(Locale.ROOT);
        }
        while (host.startsWith(".")) host = host.substring(1);
        return civitaiHost(host);
    }

    private static java.util.List<String> firstAll(Map<String, java.util.List<String>> headers, String name) {
        for (Map.Entry<String, java.util.List<String>> entry : headers.entrySet())
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        return java.util.List.of();
    }

    private static String first(Map<String, java.util.List<String>> headers, String name) {
        java.util.List<String> values = firstAll(headers, name);
        return values.isEmpty() ? "" : String.valueOf(values.get(0));
    }

    private static String join(Map<String, String> jar) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : jar.entrySet()) {
            if (text.length() > 0) text.append("; ");
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.toString();
    }
}

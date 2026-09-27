package cn.szu.bot;

import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.civitai.CivitaiLinkLogin;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Civitai 一次性登录链接的整链行为（设计见 {@code docs/MIGRATION.md}「Civitai 账号」）。
 *
 * <p>全部走注入的假传输、不碰真实网络：这条链的域名必须落在 civitai 上（这也是一条安全约束），
 * 本地假 HTTP 服务端模拟不了，所以按 {@code CivitaiClientTest} 的方式注入 {@link CivitaiClient.Transport}。
 */
public final class CivitaiLinkTest {

    private static int assertions;

    private static final String LINK = "https://auth.civitai.com/login/email/verify?token=one-time-secret";

    /** 一条完整的登录链：verify → authorize → oauth/authorize → callback → post-login → 首页。 */
    private static final Map<String, Hop> CHAIN = Map.of(
            "/login/email/verify", new Hop(302, "https://auth.civitai.com/authorize?state=x",
                    List.of("civ-auth=one; Domain=.civitai.com; Path=/")),
            "/authorize", new Hop(302, "https://auth.civitai.com/oauth/authorize?client=web",
                    List.of("oauth_bridge=temporary; Domain=.civitai.com; Path=/")),
            "/oauth/authorize", new Hop(302, "https://civitai.com/api/auth/callback/civitai?code=y", List.of()),
            "/api/auth/callback/civitai", new Hop(302, "https://civitai.com/api/auth/post-login",
                    List.of("civ-session=s3cret; Domain=.civitai.com; Path=/; HttpOnly",
                            "civ-device=dev42; Domain=.civitai.com; Path=/",
                            "third_party=tracker; Domain=.doubleclick.net; Path=/")),
            "/api/auth/post-login", new Hop(302, "https://civitai.com/",
                    List.of("civ-session=; Domain=.civitai.com; Path=/; Max-Age=0")),
            "/", new Hop(200, "", List.of()));

    public static void main(String[] args) throws Exception {
        wholeChainKeepsSessionCookie();
        cookieJarIsCarriedAcrossHops();
        onlyCivitaiHttpsLinksAreAccepted();
        tooManyRedirectsAreRejected();
        nonLoopbackProxyIsRejected();
        verifyReportsWhetherTheCookieWorks();
        System.out.println("CivitaiLinkTest: " + assertions + " assertions passed.");
    }

    private static void wholeChainKeepsSessionCookie() throws Exception {
        List<URI> hops = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        CivitaiLinkLogin.Result result = CivitaiLinkLogin.follow(new JsonObject(), LINK, transport(hops, sent, CHAIN));
        check(result.cookie().equals("civ-auth=one; civ-session=s3cret; civ-device=dev42"),
                "收下 civitai 域的会话 Cookie，丢掉 oauth_bridge / 第三方域 / Max-Age=0：" + result.cookie());
        check(result.hops() == 5, "跳数按实际计（verify→authorize→oauth→callback→post-login→首页）：" + result.hops());
        check(result.finalUrl().equals("https://civitai.com/"), "最后一跳落在站点首页：" + result.finalUrl());
        check(hops.size() == 6, "整条链一共请求了 6 次：" + hops.size());
    }

    private static void cookieJarIsCarriedAcrossHops() throws Exception {
        List<String> sent = new ArrayList<>();
        CivitaiLinkLogin.follow(new JsonObject(), LINK, transport(new ArrayList<>(), sent, CHAIN));
        check(sent.get(0).isEmpty(), "第一跳不该带 Cookie");
        check(sent.get(1).contains("civ-auth=one"), "第二跳带上上一跳落下的 Cookie：" + sent.get(1));
        check(sent.get(4).contains("civ-session=s3cret"), "回跳站点时带上会话 Cookie：" + sent.get(4));
        check(!sent.get(4).contains("oauth_bridge"), "oauth_bridge 不会被带下去：" + sent.get(4));
    }

    private static void onlyCivitaiHttpsLinksAreAccepted() throws Exception {
        rejected("http://auth.civitai.com/login/email/verify?token=x", "不是 https");
        rejected("https://evil.example.com/login/email/verify?token=x", "不是 civitai 域名");
        rejected("https://civitai.com.evil.example.com/x", "后缀伪装成 civitai");
        rejected("", "空链接");
        rejected("not a url", "不是链接");
    }

    private static void tooManyRedirectsAreRejected() throws Exception {
        Map<String, Hop> loop = Map.of("/loop", new Hop(302, "https://auth.civitai.com/loop",
                List.of("a=b; Domain=.civitai.com; Path=/")));
        try {
            CivitaiLinkLogin.follow(new JsonObject(), "https://auth.civitai.com/loop",
                    transport(new ArrayList<>(), new ArrayList<>(), loop));
            check(false, "跳数超限应当报错");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("跳转次数过多"), "跳数超限的提示：" + expected.getMessage());
        }
    }

    private static void nonLoopbackProxyIsRejected() throws Exception {
        JsonObject config = new JsonObject();
        config.addProperty("proxy_url", "http://10.0.0.5:7890");
        try {
            CivitaiLinkLogin.follow(config, LINK);
            check(false, "非本机代理应当报错");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("仅支持明确的本机 HTTP 代理"), "代理规则的提示：" + expected.getMessage());
        }
    }

    private static void verifyReportsWhetherTheCookieWorks() throws Exception {
        List<String> sentCookies = new ArrayList<>();
        List<URI> seen = new ArrayList<>();
        CivitaiClient.Transport accepted = (uri, headers, timeout) -> {
            seen.add(uri);
            sentCookies.add(headers.getOrDefault("Cookie", ""));
            return response(200, Map.of(), new byte[0]);
        };
        check(CivitaiLinkLogin.verify(new JsonObject(), "civ-session=s3cret", "https://civitai.red/", accepted),
                "200 说明这份会话可用");
        check(seen.get(0).toString().equals("https://civitai.red/api/v1/models?limit=1&types=LORA"),
                "验证打的地址：" + seen.get(0));
        check(sentCookies.get(0).equals("civ-session=s3cret"), "验证带上刚收下的 Cookie：" + sentCookies.get(0));
        CivitaiClient.Transport denied = (uri, headers, timeout) -> response(403, Map.of(), new byte[0]);
        check(!CivitaiLinkLogin.verify(new JsonObject(), "civ-session=stale", "https://civitai.red", denied),
                "403 说明这份会话不可用");
    }

    private static void rejected(String link, String why) throws Exception {
        try {
            CivitaiLinkLogin.follow(new JsonObject(), link, transport(new ArrayList<>(), new ArrayList<>(), CHAIN));
            check(false, "应当拒绝这样的链接（" + why + "）：" + link);
        } catch (IllegalArgumentException expected) {
            check(true, "");
        }
    }

    private static CivitaiClient.Transport transport(List<URI> hops, List<String> sentCookies, Map<String, Hop> chain) {
        return (uri, headers, timeout) -> {
            hops.add(uri);
            sentCookies.add(headers.getOrDefault("Cookie", ""));
            Hop hop = chain.get(uri.getPath());
            if (hop == null) return response(404, Map.of(), new byte[0]);
            Map<String, List<String>> response = new LinkedHashMap<>();
            if (!hop.setCookies().isEmpty()) response.put("Set-Cookie", hop.setCookies());
            if (!hop.location().isEmpty()) response.put("Location", List.of(hop.location()));
            return response(hop.status(), response, new byte[0]);
        };
    }

    private static CivitaiClient.Response response(int status, Map<String, List<String>> headers, byte[] bytes) {
        return new CivitaiClient.Response(status, headers, new ByteArrayInputStream(bytes));
    }

    private record Hop(int status, String location, List<String> setCookies) { }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}

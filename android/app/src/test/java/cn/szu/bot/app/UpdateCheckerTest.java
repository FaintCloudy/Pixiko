package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
/**
 * 「检查更新」这一步：URL 怎么拼、状态码怎么解释、以及<b>真的发一次 POST</b> 之后
 * {@link UpdateInfo} 能不能从响应体里解析出来。
 *
 * <p>分两层断言，是因为这两层会坏的原因不一样：
 * <ul>
 *   <li>{@link #interpretMapsStatusCodesToActionableMessages()} —— 纯逻辑层。用假的 {@link UpdateChecker.Request}
 *       喂状态码，不起服务器就能把所有分支钉死（网络环境里最难复现的就是这些分支）；</li>
 *   <li>{@link #reallyPostsToFakeServerAndParsesContractJson()} —— 真的 HTTP 层。本地假服务按契约回 JSON，
 *       验证「请求方法/路径/body/令牌头」与「解析」串起来是通的。</li>
 * </ul>
 */
public class UpdateCheckerTest {

    /** 把 Log 出口换成内存记录器（否则走到 Log 的那几条分支会抛 not mocked）。 */
    @Rule
    public final CapturedLogRule logs = new CapturedLogRule();

    // ---------------------------------------------------------------- URL 拼装

    @Test
    public void buildsUpdateUrlFromWhateverTheUserTyped() {
        // 用户在设置里可能填成这几种写法，都应该拼出同一个接口地址（端口默认 8787）。
        assertEquals("http://192.168.1.5:8787/api/app/update", UpdateChecker.updateUrl("192.168.1.5"));
        assertEquals("http://192.168.1.5:8787/api/app/update", UpdateChecker.updateUrl("192.168.1.5:8787"));
        assertEquals("http://192.168.1.5:8787/api/app/update", UpdateChecker.updateUrl("http://192.168.1.5:8787/"));
        assertEquals("https://bot.example.com/api/app/update", UpdateChecker.updateUrl("https://bot.example.com"));
        // 部署在子路径下的情况：接口也要落在子路径下（而不是跑到站点根去）。
        assertEquals("http://192.168.1.5:9000/sub/api/app/update", UpdateChecker.updateUrl("192.168.1.5:9000/sub/"));
        assertNull("空地址不该拼出个假 URL", UpdateChecker.updateUrl(""));
        assertNull(UpdateChecker.updateUrl(null));
    }

    // ---------------------------------------------------------------- 纯逻辑层：状态码 → 结论

    @Test
    public void interpretMapsStatusCodesToActionableMessages() {
        UpdateChecker.Outcome notFound = UpdateChecker.interpret(404, "not found", "192.168.1.5:8787");
        assertFalse(notFound.ok);
        assertTrue("404 要说「服务端还没这个接口」：" + notFound.message, notFound.message.contains("404"));
        assertTrue(notFound.message.contains("/api/app/update"));

        UpdateChecker.Outcome unauthorized = UpdateChecker.interpret(401, "", "h");
        assertFalse(unauthorized.ok);
        assertTrue("401 要指向「服务端把接口挡在令牌后面」：" + unauthorized.message,
                unauthorized.message.contains("令牌") && unauthorized.message.contains("白名单"));

        assertFalse(UpdateChecker.interpret(403, "", "h").ok);
        assertTrue(UpdateChecker.interpret(405, "", "h").message.contains("405"));
        assertTrue(UpdateChecker.interpret(500, "boom", "h").message.contains("500"));
        assertTrue("200 但不是 JSON：要提示地址可能指错了地方",
                UpdateChecker.interpret(200, "<html>hi</html>", "h").message.contains("看不懂"));
    }

    @Test
    public void interpretAcceptsAValidPayload() {
        UpdateChecker.Outcome outcome = UpdateChecker.interpret(200,
                "{\"version\":\"1.7.0\",\"versionCode\":170,\"apkUrl\":\"http://h/api/app/apk\","
                        + "\"sha256\":\"aa\",\"channel\":\"dev\"}",
                "192.168.1.5:8787");
        assertTrue(outcome.ok);
        assertNotNull(outcome.info);
        assertEquals(170, outcome.info.versionCode);
        assertTrue(outcome.message.isEmpty());
    }

    // ---------------------------------------------------------------- 带令牌重试的分支

    @Test
    public void retriesOnceWithTokenWhenServerSays401AndTokenIsAvailable() {
        List<String> bearers = new ArrayList<>();
        UpdateChecker.Request request = (url, bearer) -> {
            bearers.add(bearer == null ? "" : bearer);
            if (bearers.size() == 1) return new Http.Result(401, "unauthorized");
            return new Http.Result(200, "{\"version\":\"1.7.0\",\"versionCode\":170}");
        };

        UpdateChecker.Outcome outcome = UpdateChecker.check("192.168.1.5:8787", "secret-token", request);

        assertTrue("带上已存的令牌重试一次应该能成功", outcome.ok);
        assertEquals("第一次必须不带令牌（契约是免令牌）", "", bearers.get(0));
        assertEquals("第二次才带令牌", "secret-token", bearers.get(1));
        assertEquals(2, bearers.size());
    }

    @Test
    public void doesNotRetryWithoutAToken() {
        List<String> bearers = new ArrayList<>();
        UpdateChecker.Request request = (url, bearer) -> {
            bearers.add(bearer == null ? "" : bearer);
            return new Http.Result(401, "");
        };

        UpdateChecker.Outcome outcome = UpdateChecker.check("192.168.1.5:8787", "", request);

        assertFalse(outcome.ok);
        assertEquals("没令牌就别白发第二次", 1, bearers.size());
        assertTrue(outcome.message.contains("白名单"));
    }

    @Test
    public void reportsUnreachableServerAsAHumanSentence() {
        UpdateChecker.Request request = (url, bearer) -> {
            throw new IOException("connect timed out");
        };
        UpdateChecker.Outcome outcome = UpdateChecker.check("192.168.1.5:8787", "", request);
        assertFalse(outcome.ok);
        assertTrue(outcome.message.contains("连不上"));
        assertTrue(outcome.message.contains("192.168.1.5:8787"));
    }

    @Test
    public void badAddressIsRejectedBeforeAnyRequest() {
        UpdateChecker.Request request = (url, bearer) -> {
            throw new AssertionError("地址不对时不该发请求");
        };
        UpdateChecker.Outcome outcome = UpdateChecker.check("", "", request);
        assertFalse(outcome.ok);
        assertTrue(outcome.message.contains("服务器地址"));
    }

    // ---------------------------------------------------------------- 真 HTTP 层

    private FakeHttpServer server;

    private void startServer(int status, String responseBody) throws IOException {
        logs.install();
        server = new FakeHttpServer();
        server.serve("/api/app/update", (request, response) -> {
            response.status(status);
            response.setBody(responseBody.getBytes(StandardCharsets.UTF_8));
            response.set("Content-Type", "application/json");
        });
    }

    @After
    public void stopServer() {
        if (server != null) server.close();
    }

    @Test
    public void reallyPostsToFakeServerAndParsesContractJson() throws Exception {
        startServer(200, "{\"version\":\"1.7.0\",\"versionCode\":170,\"sizeBytes\":6224071,"
                + "\"sha256\":\"a4226e84d3d3f0e0b1c2a3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718\","
                + "\"apkUrl\":\"http://127.0.0.1:1/api/app/apk\",\"releaseUrl\":\"https://example.com\","
                + "\"publishedAt\":\"2026-10-07T12:00:00Z\",\"notes\":\"测试说明\",\"channel\":\"dev\","
                + "\"channelNote\":\"本机测试包\"}");

        UpdateChecker.Outcome outcome = UpdateChecker.check(server.baseUrl(), "", null);

        assertTrue("真发一次 POST 之后必须能解析出更新信息：" + outcome.message, outcome.ok);
        assertNotNull(outcome.info);
        List<FakeHttpServer.Request> requests = server.requests();
        assertEquals(1, requests.size());
        assertEquals("POST", requests.get(0).method);
        assertEquals("/api/app/update", requests.get(0).path);
        assertEquals("body 必须是契约里的空对象 {}", "{}", requests.get(0).body);
        assertEquals("契约是免令牌 → 不许带 Authorization", null, requests.get(0).header("Authorization"));
        assertEquals(170, outcome.info.versionCode);
        assertEquals(6224071L, outcome.info.sizeBytes);
        assertEquals("dev", outcome.info.channel);
        assertTrue(outcome.info.canInstall());
        System.out.println("[UpdateCheckerTest] 真请求：" + requests.get(0)
                + " → versionCode=" + outcome.info.versionCode
                + " channel=" + outcome.info.channel
                + " sizeBytes=" + outcome.info.sizeBytes);
    }

    @Test
    public void emptyApkUrlFromRealServerMeansNoPackage() throws Exception {
        startServer(200, "{\"version\":\"1.7.0\",\"versionCode\":170,\"apkUrl\":\"\",\"sha256\":\"\"}");

        UpdateChecker.Outcome outcome = UpdateChecker.check(server.baseUrl(), "", null);

        assertTrue(outcome.ok);
        assertTrue("versionCode 更大 → 有新版本", outcome.info.isNewerThan(160));
        assertFalse("但 apkUrl 为空 → 不能下载", outcome.info.canInstall());
        assertTrue(outcome.info.blockReason().contains("没有提供 APK 下载地址"));
    }

    @Test
    public void realHttp404IsReportedAsMissingEndpoint() throws Exception {
        startServer(404, "{\"error\":\"not found\"}");

        UpdateChecker.Outcome outcome = UpdateChecker.check(server.baseUrl(), "", null);

        assertFalse(outcome.ok);
        assertTrue(outcome.message.contains("404"));
    }

    /** 线上实测的形状：服务端把 /api/** 挡在全局令牌过滤器后面（401）。 */
    @Test
    public void realHttp401WithoutTokenPointsAtTheServerSideWhitelist() throws Exception {
        startServer(401, "");

        UpdateChecker.Outcome outcome = UpdateChecker.check(server.baseUrl(), "", null);

        assertFalse(outcome.ok);
        assertTrue("要指出是服务端的白名单问题：" + outcome.message, outcome.message.contains("白名单"));
        assertEquals("没令牌 → 只发一次", 1, server.requests().size());
    }

    /** 用户手工填过令牌时，401 会用令牌再试一次（兼容白名单还没落地的服务端）。 */
    @Test
    public void realHttp401WithTokenRetriesOnceAndSucceeds() throws Exception {
        logs.install();
        server = new FakeHttpServer();
        // 第一次 401、第二次 200：模拟「白名单还没落地，但用户填过令牌」。
        final int[] hits = {0};
        server.serve("/api/app/update", (request, response) -> {
            hits[0]++;
            if (hits[0] == 1) {
                response.status(401);
                response.setBody(new byte[0]);
                return;
            }
            response.status(200);
            response.setBody("{\"version\":\"1.7.0\",\"versionCode\":170}".getBytes(StandardCharsets.UTF_8));
        });

        UpdateChecker.Outcome outcome = UpdateChecker.check(server.baseUrl(), "secret-token", null);

        assertTrue("第二次应该成功：" + outcome.message, outcome.ok);
        List<FakeHttpServer.Request> requests = server.requests();
        assertEquals(2, requests.size());
        assertEquals("第一次不带令牌", null, requests.get(0).header("Authorization"));
        assertEquals("第二次带令牌", "Bearer secret-token", requests.get(1).header("Authorization"));
        System.out.println("[UpdateCheckerTest] 401 重试：" + requests.get(0) + " → " + requests.get(1));

        // 顺带的安全回归：打了这么多日志，任何一行都不许出现令牌明文。
        // （项目约定见 Log 的类注释：「任何日志都不许带令牌」。）
        logs.dump("401 重试期间的日志");
        for (String line : logs.lines()) {
            assertFalse("日志里不许出现令牌明文：" + line, line.contains("secret-token"));
        }
    }

    @Test
    public void downloadLogsNeverContainTheApkUrlToken() {
        // 假服务在 apkUrl 里塞一个“令牌样”的查询串，走完全流程后断言日志里没有它。
        String secret = "t0ken-in-url-should-not-be-logged";
        logs.install();
        UpdateChecker.Outcome outcome = UpdateChecker.check("192.168.1.5:8787", secret, (url, bearer) -> {
            throw new IOException("unreachable");
        });
        assertFalse(outcome.ok);
        logs.dump("连不上时的日志");
        for (String line : logs.lines()) {
            assertFalse("日志里不许出现令牌明文：" + line, line.contains(secret));
        }
    }
}

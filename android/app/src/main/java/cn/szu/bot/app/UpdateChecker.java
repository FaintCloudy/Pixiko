package cn.szu.bot.app;

import java.io.IOException;

/**
 * 「问服务端有没有新版本」这一步：{@code POST <服务器地址>/api/app/update}，body 就是 {@code {}}。
 *
 * <h2>令牌：不带</h2>
 * <p>按服务端契约，这个接口<b>免令牌</b>。所以请求里<b>刻意不加</b> {@code Authorization} ——
 * 更新检查发生在「用户还没登录/令牌还没填」的时候也应该能工作。
 *
 * <p>但线上实测发现：机器人对整个 {@code /api/**} 套了全局令牌过滤器，鉴权发生在路由匹配<b>之前</b>
 * （连不存在的路由都回 401）。所以这里做了一个<b>很小的</b>兼容：既然 app 本来就知道当前服务器的令牌
 * （{@link ServerConfig#token}，用户自己填的），那就带上它再试一次一个 401 的应答。
 * 这不是「改用令牌」，契约路径（不带令牌）永远先试；带令牌只是让「服务端白名单还没落地」时也能用。
 * 一旦服务端把这两个路径加进免鉴权白名单，第一条路径就直接成功了。
 *
 * <h2>可测性</h2>
 * <p>「拿到 HTTP 应答之后怎么解析」是纯逻辑，抽成 {@link #interpret(int, String, String)}：
 * 单测可以直接喂状态码 + JSON 字符串，不必起服务器、也不必碰 android.*。
 */
public final class UpdateChecker {

    /** 更新接口的路径（服务端定死的，改名就对不上）。 */
    public static final String PATH_UPDATE = "/api/app/update";

    /** 检查更新的超时：内网很快，但不能让「服务端没响应」把检查线程挂太久。 */
    public static final int TIMEOUT_MS = 8_000;

    private UpdateChecker() { }

    /** 一次检查的结果。 */
    public static final class Outcome {
        /** 检查本身成功（拿到并解析出了更新信息）。注意：这不等于「有新版本」。 */
        public final boolean ok;
        /** 服务端给的更新信息（失败时为 null）。 */
        public final UpdateInfo info;
        /** 可直接显示给用户的一句人话。 */
        public final String message;

        private Outcome(boolean ok, UpdateInfo info, String message) {
            this.ok = ok;
            this.info = info;
            this.message = message == null ? "" : message;
        }

        static Outcome ok(UpdateInfo info) { return new Outcome(true, info, ""); }

        static Outcome fail(String message) { return new Outcome(false, null, message); }
    }

    /**
     * 发请求的抽象：真实实现用 {@link HttpURLConnection}，单测可以直接塞一个假的返回。
     * 这样「状态码 → 结论」这段逻辑不用起服务器也能断言。
     */
    public interface Request {
        /** 返回状态码与响应体；网络异常往上抛。 */
        Http.Result send(String url, String bearer) throws IOException;
    }

    /** 检查更新（阻塞）。调用方放子线程。 */
    public static Outcome check(String base, String token, Request request) {
        String normalized = UrlHelper.normalizeBase(base);
        if (normalized == null) {
            return Outcome.fail("服务器地址还没配好，先去「服务器设置」填一个地址。");
        }
        String url = UrlHelper.join(normalized, PATH_UPDATE);
        Request sender = request == null ? new HttpRequest() : request;

        Http.Result first;
        try {
            // 契约路径：不带任何令牌。
            first = sender.send(url, "");
        } catch (Exception error) {
            Log.w("检查更新失败（请求发不出去）", error);
            return Outcome.fail("检查更新失败：连不上 " + UrlHelper.hostOf(normalized) + "（" + Log.describe(error) + "）。");
        }

        if (first.code == 401 || first.code == 403) {
            // 服务端把接口挡在令牌后面了。带令牌再试一次（见类注释）；没令牌就只能如实报错。
            String trimmed = token == null ? "" : token.trim();
            if (trimmed.isEmpty()) {
                return Outcome.fail("服务端要求令牌（HTTP " + first.code + "），但更新接口按契约应该免令牌。"
                        + "需要服务端把 " + PATH_UPDATE + " 加进免鉴权白名单。");
            }
            Log.w("更新接口返回 " + first.code + "（契约是免令牌），带本机已存令牌重试一次");
            try {
                first = sender.send(url, trimmed);
            } catch (Exception error) {
                return Outcome.fail("检查更新失败：" + Log.describe(error));
            }
        }
        return interpret(first.code, first.body, UrlHelper.hostOf(normalized));
    }

    /**
     * 「状态码 + 响应体 → 结论」。纯函数，单测的主战场。
     *
     * <p>分类刻意分得细，因为不同原因的下一步动作完全不同：
     * 404 是「服务端还没实现/路径不对」，401 是「被令牌挡了」，非 JSON 是「这端口上跑的不是 Pixiko」。
     */
    public static Outcome interpret(int code, String body, String hostLabel) {
        if (code == 404) {
            return Outcome.fail("服务端还没有更新接口（HTTP 404）。机器人可能不是新版，或 " + PATH_UPDATE
                    + " 不在这个地址下。");
        }
        if (code == 401 || code == 403) {
            return Outcome.fail("服务端把更新接口挡在令牌后面了（HTTP " + code + "）；需要它把 " + PATH_UPDATE
                    + " 加进免鉴权白名单。");
        }
        if (code == 405) {
            return Outcome.fail("服务端的更新接口不接受 POST（HTTP 405），和契约不一致。");
        }
        if (code < 200 || code >= 300) {
            return Outcome.fail("检查更新失败：服务端返回 HTTP " + code + "。");
        }
        UpdateInfo info = UpdateInfo.parse(body);
        if (info == null) {
            return Outcome.fail("服务端返回的内容看不懂（不是更新信息 JSON）。检查一下地址是不是指到了 Pixiko 控制台。");
        }
        return Outcome.ok(info);
    }

    /** 真实实现：{@link Http#postJson} 够用（body 固定 {@code {}}，读 8KB 足够装下 notes）。 */
    public static final class HttpRequest implements Request {
        @Override
        public Http.Result send(String url, String bearer) throws IOException {
            return Http.postJson(url, bearer == null || bearer.isEmpty() ? null : bearer, "{}", TIMEOUT_MS);
        }
    }

    /**
     * 检查更新用的绝对地址（给日志用，也方便单测断言「拼出来的 URL 对不对」）。
     */
    public static String updateUrl(String base) {
        String normalized = UrlHelper.normalizeBase(base);
        return normalized == null ? null : UrlHelper.join(normalized, PATH_UPDATE);
    }
}

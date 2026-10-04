package cn.szu.bot.app;

import org.json.JSONObject;

/**
 * 连接自检：把「连不上」和「令牌不对」两件事分开报。
 *
 * <p>顺序是刻意的：
 * <ol>
 *   <li>先 GET {@code /healthz}——它<b>免令牌</b>（见 WebPageController.healthz），
 *       通了只说明「服务在」，不说明令牌对；</li>
 *   <li>再 POST {@code /api/status} 带 {@code Authorization: Bearer …}——这一步才验令牌；
 *       401 就是令牌不对，503 是服务端压根没配令牌。</li>
 * </ol>
 * 分成两步而不是只做第二步，是为了避免「令牌错」被误报成「服务器不在」。
 */
public final class Probe {

    /** 自检结论：给 UI 用的中文消息 + 机器可判的类别。 */
    public static final class Outcome {
        public enum Kind { OK, UNREACHABLE, TOKEN, SERVER_ERROR, BAD_ADDRESS }

        public final Kind kind;
        public final String message;
        /** 服务端返回的错误文本（可能为空）。 */
        public final String detail;

        Outcome(Kind kind, String message, String detail) {
            this.kind = kind;
            this.message = message;
            this.detail = detail == null ? "" : detail;
        }

        public boolean ok() { return kind == Kind.OK; }
    }

    private Probe() { }

    /** 完整自检：探活 + 验令牌。任意网络 IO 异常都在内部转成 Outcome，不往上抛。 */
    public static Outcome test(String base, String token) {
        String normalized = UrlHelper.normalizeBase(base);
        if (normalized == null) return new Outcome(Outcome.Kind.BAD_ADDRESS, "地址看起来不对，检查一下主机和端口。", "");

        String healthUrl = UrlHelper.join(normalized, "/healthz");
        int health;
        try {
            health = Http.reachable(healthUrl, Http.SHORT_TIMEOUT_MS);
        } catch (RuntimeException error) {
            Log.w("探活异常", error);
            health = 0;
        }
        if (health == 0) {
            return new Outcome(Outcome.Kind.UNREACHABLE,
                    "连不上 " + UrlHelper.hostOf(normalized) + "。手机和服务端要在同一个局域网，端口要写对。",
                    healthUrl);
        }
        if (health >= 400) {
            return new Outcome(Outcome.Kind.SERVER_ERROR,
                    "服务有响应但 /healthz 返回 HTTP " + health + "，可能这个端口上跑的不是 Pixiko 控制台。", healthUrl);
        }

        if (token == null || token.trim().isEmpty()) {
            return new Outcome(Outcome.Kind.TOKEN, "服务在，但还没填访问令牌。", "填写 config.json → webui.access_token");
        }

        try {
            Http.Result status = Http.postJson(UrlHelper.join(normalized, "/api/status"), token.trim(), "{}", Http.SHORT_TIMEOUT_MS);
            if (status.ok()) {
                return new Outcome(Outcome.Kind.OK, "连接正常，令牌有效。", "");
            }
            if (status.code == 401) {
                return new Outcome(Outcome.Kind.TOKEN, "服务在，但访问令牌不对。", serverError(status.body));
            }
            if (status.code == 429) {
                return new Outcome(Outcome.Kind.TOKEN, "尝试次数过多，服务端临时冷却了 30 秒，稍后再试。", serverError(status.body));
            }
            if (status.code == 503) {
                return new Outcome(Outcome.Kind.SERVER_ERROR, "服务端还没配置访问令牌（config.json → webui.access_token）。", serverError(status.body));
            }
            return new Outcome(Outcome.Kind.SERVER_ERROR, "服务在，但 /api/status 返回 HTTP " + status.code + "。", serverError(status.body));
        } catch (Exception error) {
            Log.w("验令牌失败", error);
            return new Outcome(Outcome.Kind.UNREACHABLE, "服务在，但验令牌的请求失败了：" + Log.describe(error), healthUrl);
        }
    }

    /** 从 {"error":"…"} 里抠出服务端给的中文错误，抠不到就原样截断。 */
    public static String serverError(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JSONObject object = new JSONObject(body);
            String error = object.optString("error", "");
            if (!error.isBlank()) return error;
        } catch (Exception ignored) {
            // 不是 JSON 就走下面的截断逻辑。
        }
        String text = body.strip().replaceAll("\\s+", " ");
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }
}

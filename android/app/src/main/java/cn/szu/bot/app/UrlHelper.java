package cn.szu.bot.app;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 地址规范化与拼装。用户会敲出各种写法，这里全部收口到一种：
 *
 * <pre>
 *   192.168.1.5                 -> http://192.168.1.5:8787
 *   192.168.1.5:9000            -> http://192.168.1.5:9000
 *   http://192.168.1.5:8787/    -> http://192.168.1.5:8787
 *   https://bot.example.com     -> https://bot.example.com      （给了 https 就不再补端口）
 *   http://host:port/sub        -> http://host:port/sub          （保留子路径，去掉尾斜杠）
 * </pre>
 *
 * <p>规则：默认补 {@code http://}、默认端口 {@code 8787}（http 且没写端口时）。
 * 局域网里机器人是 http，所以默认 http 而不是 https。
 */
public final class UrlHelper {

    /** 与 config.json → webui.port 的默认值保持一致。 */
    public static final int DEFAULT_PORT = 8787;

    /**
     * 手机端界面（{@code webui/m/}，由机器人伺服在 {@code /m}，{@code /m/} 同）。
     * 这是 Android 外壳<b>默认</b>打开的入口，见 {@code MainActivity.uiPath()}。
     */
    public static final String PATH_MOBILE = "/m";

    /**
     * 完整网页控制台（{@code webui/}，伺服在 {@code /}）。保留入口，用户可随时从菜单切回。
     */
    public static final String PATH_CONSOLE = "/";

    private UrlHelper() { }

    /**
     * 规范化用户输入。返回 {@code null} 表示输入实在没法当成地址（空、或 scheme 不是 http/https）。
     */
    public static String normalizeBase(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;
        // 去掉用户从浏览器地址栏复制来的尾巴。
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);

        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            // 形如 "user@host" 之类不处理；只当它是裸 host[:port][/path]。
            text = "http://" + text;
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException error) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        String host = uri.getHost();
        if (host == null || host.isBlank()) return null;

        int port = uri.getPort();
        StringBuilder out = new StringBuilder();
        out.append(scheme).append("://");
        // IPv6 字面量要放回方括号里，否则拼出来不是合法地址。
        if (host.contains(":")) out.append('[').append(host).append(']'); else out.append(host);
        if (port > 0) out.append(':').append(port);
        else if (scheme.equals("http")) out.append(':').append(DEFAULT_PORT);

        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            out.append(path);
        }
        return out.toString();
    }

    /** 基地址 + 相对路径（相对路径以 / 开头）。 */
    public static String join(String base, String path) {
        if (base == null) return path;
        String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (path == null || path.isEmpty()) return left + "/";
        return path.startsWith("/") ? left + path : left + "/" + path;
    }

    /** 主机名（拿不到就原样返回去掉 scheme 的部分），用于列表显示。 */
    public static String hostOf(String base) {
        if (base == null || base.isBlank()) return "(未设置)";
        try {
            URI uri = new URI(base);
            String host = uri.getHost();
            if (host == null) return base;
            return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        } catch (URISyntaxException error) {
            return base;
        }
    }

    /**
     * 从 {@code http://<ip>:8787/healthz} 这类地址里抠出主机（扫描结果展示用）。
     */
    public static String hostOnly(String url) {
        try {
            URI uri = new URI(url);
            return uri.getHost() == null ? url : uri.getHost();
        } catch (URISyntaxException error) {
            return url;
        }
    }

    /**
     * 把 WebView 里拿到的图片地址解析成可下载的绝对地址。
     *
     * <p>网页里的 &lt;img&gt; 有两类：{@code /api/image?token=…&amp;path=…}（相对，带令牌查询串）
     * 与图床的 {@code https://image.civitai.com/…}（绝对）。相对地址拼上当前基地址即可，
     * <b>令牌就留在查询串里</b>——服务端 {@code /api/**} 的令牌三种给法里查询串是唯一对
     * &lt;img&gt; 有效的，我们照抄浏览器行为，不去改写它。
     */
    public static String absolutize(String base, String src) {
        if (src == null || src.isBlank()) return null;
        String text = src.trim();
        if (text.startsWith("//")) return "https:" + text;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return text;
        if (lower.startsWith("blob:") || lower.startsWith("data:")) return null;   // 这两种 HttpURLConnection 下不了
        if (base == null) return null;
        return join(base, text);
    }

    /** 去掉查询串（只用于日志/文件名，不用于请求）。 */
    public static String stripQuery(String url) {
        if (url == null) return "";
        int at = url.indexOf('?');
        return at < 0 ? url : url.substring(0, at);
    }
}

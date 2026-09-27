package cn.szu.bot.web;

import cn.szu.bot.Bot;
import cn.szu.bot.Log;
import cn.szu.bot.Settings;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 网页控制台的门面：起停内嵌的 Spring Boot（Tomcat）应用。
 *
 * <p>迁移到 Spring Boot 之后，HTTP 由 Tomcat 提供、接口在 {@link WebApiController}、页面在
 * {@link WebPageController}、令牌校验在 {@link WebAuthFilter}。这个类只是保留原来的
 * 「构造 → {@link #start()} → {@link #close()}」用法，机器人主流程与测试都不用改。
 */
public final class WebUiServer implements AutoCloseable {

    private final Settings settings;
    private final Bot bot;
    private ConfigurableApplicationContext context;

    public WebUiServer(Settings settings, Bot bot) {
        this.settings = settings;
        this.bot = bot;
    }

    public void start() throws IOException {
        String host = settings.webHost();
        int port = settings.webPort();
        context = WebUiApplication.start(settings, bot, host, port);
        String token;
        try { token = settings.webToken(); }
        catch (IOException error) { token = ""; Log.warn("WebUI 令牌读取失败：" + Bot.error(error)); }
        Log.info("WebUI 已启动：http://" + displayHost() + ":" + actualPort()
                + "（访问令牌 " + (token.isBlank() ? "未配置" : "已配置") + "，见 config.json 的 webui.access_token）");
    }

    /** 实际监听端口（配置成 0 时由操作系统分配，比如测试里）。 */
    public int actualPort() {
        if (context == null) return settings.webPort();
        try {
            String value = context.getEnvironment().getProperty("local.server.port");
            if (value != null && !value.isBlank()) return Integer.parseInt(value.strip());
        } catch (Exception ignored) { /* 拿不到就用配置值 */ }
        return settings.webPort();
    }

    @Override public void close() {
        if (context != null) {
            context.close();
            context = null;
        }
        Log.info("WebUI 已停止。");
    }

    private String displayHost() {
        String host = settings.webHost();
        if ("0.0.0.0".equals(host) || "::".equals(host)) {
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    for (InterfaceAddress address : interfaces.nextElement().getInterfaceAddresses()) {
                        InetAddress ip = address.getAddress();
                        if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && ip.isSiteLocalAddress())
                            return ip.getHostAddress();
                    }
                }
            } catch (Exception ignored) { }
            return "127.0.0.1";
        }
        return host;
    }
}

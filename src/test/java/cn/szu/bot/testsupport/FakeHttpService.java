package cn.szu.bot.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 假服务的最小 HTTP 外壳：**只绑 {@code 127.0.0.1}**、端口由操作系统分配（{@code bind 0}），
 * 启动后用 {@link #port()} 读实际监听端口再注入测试配置。
 *
 * <p>这是第 4、5、6 条的工具落点：不探测空闲端口（那有竞争窗口），直接绑 0；不碰生产端口；
 * 不对外网监听。假 SD / 假 DeepSeek / 假 Civitai 都建在它上面，各自一个独立实例，互不共享端口。
 */
public final class FakeHttpService implements AutoCloseable {

    /** 一条固定响应。 */
    public record Reply(int status, String contentType, String body) {
        public static Reply json(String body) { return new Reply(200, "application/json; charset=utf-8", body); }
        public static Reply text(String body) { return new Reply(200, "text/plain; charset=utf-8", body); }
        public static Reply missing() { return new Reply(404, "application/json; charset=utf-8", "{\"detail\":\"not found\"}"); }
    }

    private final String label;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Reply> routes = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    public FakeHttpService(String label) throws IOException {
        this.label = label;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-http-" + label);
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /** 注册一条路由；{@code pathPrefix} 命中最长的那条（不命中就 404）。 */
    public FakeHttpService route(String pathPrefix, Reply reply) {
        routes.put(pathPrefix, reply);
        return this;
    }

    /** 实际监听端口（绑 0 之后由操作系统给的那个）。 */
    public int port() { return server.getAddress().getPort(); }

    /** {@code http://127.0.0.1:<实际端口>}。 */
    public String baseUrl() { return "http://127.0.0.1:" + port(); }

    public String label() { return label; }

    /** 收到过的请求行：{@code "GET /sdapi/v1/sd-models"}。 */
    public List<String> requests() { return List.copyOf(requests); }

    /** 收到过的请求体（POST 才有）。 */
    public List<String> bodies() { return List.copyOf(bodies); }

    /** 命中次数（没有就 0）。 */
    public int hits(String pathPrefix) {
        int count = 0;
        for (String request : requests) if (request.contains(" " + pathPrefix)) count++;
        return count;
    }

    public Map<String, Reply> routes() { return new LinkedHashMap<>(routes); }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        requests.add(method + " " + path);
        String body;
        try (InputStream input = exchange.getRequestBody()) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!body.isEmpty()) bodies.add(body);
        Reply reply = match(path);
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        exchange.close();
    }

    private Reply match(String path) {
        Reply best = null;
        int bestLength = -1;
        for (Map.Entry<String, Reply> entry : routes.entrySet()) {
            String prefix = entry.getKey();
            if (path.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)) && prefix.length() > bestLength) {
                best = entry.getValue();
                bestLength = prefix.length();
            }
        }
        return best == null ? Reply.missing() : best;
    }

    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
        requests.clear();
        bodies.clear();
    }

    /** 便于报告：{@code 假SD=127.0.0.1:53211}。 */
    @Override public String toString() { return label + "=127.0.0.1:" + port(); }

    /** 便利方法：把所有已注册路由的路径列出来（调试用）。 */
    public List<String> routePaths() { return new ArrayList<>(routes.keySet()); }
}

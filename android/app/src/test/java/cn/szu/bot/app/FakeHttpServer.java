package cn.szu.bot.app;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试用的<b>本地假服务</b>（不引入任何依赖，只用 {@link ServerSocket} 手写 HTTP/1.1 应答）。
 *
 * <p>为什么不用 {@code com.sun.net.httpserver}：那个包在 <b>JDK 9+ 的模块里</b>默认不作为
 * 编译期可见的 API，AGP 给单测编译用的 classpath 上看不到它（实测报「程序包 com.sun.net.httpserver 不存在」）。
 * 手写一个最小的 HTTP 应答没有多少行，好处是<b>测试跑在哪个 JDK 上都一样</b>，也不需要额外的测试依赖。
 *
 * <p>它对每个连接只处理一个请求（正好是 {@link java.net.HttpURLConnection} 的用法），支持：
 * <ul>
 *   <li>{@code Range: bytes=N-} → {@code 206} + {@code Content-Range}；越界 → {@code 416}；</li>
 *   <li>{@code Content-Length} / {@code Content-Type} / 自定义头 {@code X-Pixiko-Sha256}（服务端契约里有它）；</li>
 *   <li>{@code truncateAt} &gt; 0 时<b>声明完整长度却只写一部分然后掐断连接</b>，
 *       用来逼出客户端的断点续传（这就是真实「网络断了」在 HTTP 层面的样子）。</li>
 * </ul>
 */
final class FakeHttpServer implements AutoCloseable {

    /** 一次收到的请求。 */
    static final class Request {
        String method = "";
        String path = "";
        /** 头名一律小写，取值去空白。 */
        final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        String body = "";

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** {@code Range: bytes=N-} 里的 N；没带 Range 返回 -1。 */
        int rangeStart() {
            String range = header("Range");
            if (range == null || !range.startsWith("bytes=")) return -1;
            String text = range.substring("bytes=".length()).trim();
            int dash = text.indexOf('-');
            try {
                return Integer.parseInt(dash > 0 ? text.substring(0, dash) : text);
            } catch (NumberFormatException error) {
                return -1;
            }
        }

        @Override public String toString() {
            return method + " " + path + " Range=" + header("Range")
                    + " Authorization=" + (header("Authorization") == null ? "(无)" : "(有)")
                    + " body=" + body;
        }
    }

    /** 每条路由的应答内容。 */
    interface Handler {
        void handle(Request request, Response response) throws IOException;
    }

    /** 应答。默认 200 + 完整 body；测试可以改状态码/头/length/截断。 */
    static final class Response {
        byte[] body = new byte[0];
        int status = 200;
        /** 为 true 时按 body 长度算 Content-Length；也可以用 {@link #declaredLength} 显式指定。 */
        long declaredLength = -1;
        /** &gt;0 时只写这么多字节然后掐断连接（Content-Length 仍然是声明的完整长度）。 */
        int truncateAt = -1;
        final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();

        void setBody(byte[] bytes) { this.body = bytes == null ? new byte[0] : bytes; }

        void set(String name, String value) { headers.put(name, value); }

        void status(int code) { this.status = code; }
    }

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    private volatile Handler handler;
    private volatile byte[] payload = new byte[0];
    private volatile String payloadSha256 = "";
    private volatile String contentType = "application/vnd.android.package-archive";
    private volatile int truncateFirstResponseAt = -1;
    private final AtomicBoolean truncatedOnce = new AtomicBoolean(false);

    FakeHttpServer() throws IOException {
        // 只监听回环、端口交给系统分配：绝不碰线上 8787，也不会和别的测试撞端口。
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        acceptThread = new Thread(this::acceptLoop, "fake-http-server");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** 假服务的基地址，形如 {@code http://127.0.0.1:53124}。 */
    String baseUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
    }

    /** 按「APK 契约」伺服 payload：支持 Range，带 Content-Type / X-Pixiko-Sha256 / Content-Length。 */
    void serveApkContract(byte[] apkBytes, String path) {
        this.payload = apkBytes;
        this.payloadSha256 = sha256(apkBytes);
        this.handler = (request, response) -> {
            int start = Math.max(0, request.rangeStart());
            if (start >= payload.length) {
                response.status(416);
                response.setBody(new byte[0]);
                return;
            }
            int length = payload.length - start;
            byte[] slice = new byte[length];
            System.arraycopy(payload, start, slice, 0, length);
            response.setBody(slice);
            response.set("Content-Type", contentType);
            response.set("X-Pixiko-Sha256", payloadSha256);
            if (start > 0) {
                response.status(206);
                response.set("Content-Range",
                        "bytes " + start + "-" + (payload.length - 1) + "/" + payload.length);
            } else {
                response.status(200);
            }
            // 「第一次应答掐断」：只有第一条请求会短读，之后正常伺服完整数据。
            // 这样客户端必须靠**续传**才能把包拼完 —— 也就是这条测试要证明的事。
            if (truncateFirstResponseAt > 0 && !truncatedOnce.getAndSet(true)) {
                response.truncateAt = truncateFirstResponseAt;
            }
        };
        this.path = path;
    }

    private volatile String path = "/api/app/apk";

    /** 换一个任意的应答处理器（给「检查更新」的 JSON 用）。 */
    void serve(String path, Handler newHandler) {
        this.path = path;
        this.handler = newHandler;
    }

    /**
     * 让<b>第一条</b>应答只写这么多字节然后掐断（之后正常伺服完整数据）。
     * 这是「网络下到一半断了、重试时接着下」的最小可复现形状。
     */
    void truncateFirstResponseAt(int bytes) {
        this.truncateFirstResponseAt = bytes;
        this.truncatedOnce.set(false);
    }

    void contentType(String value) {
        this.contentType = value;
    }

    String payloadSha256() {
        return payloadSha256;
    }

    /** 收到过的请求（按顺序）。 */
    List<Request> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    void resetRequests() {
        requests.clear();
    }

    private void acceptLoop() {
        while (running.get()) {
            try (Socket socket = serverSocket.accept()) {
                handleConnection(socket);
            } catch (IOException error) {
                if (running.get()) {
                    // 单条连接出错不该让整个假服务倒下。
                    System.out.println("[FakeHttpServer] 连接出错（已忽略）：" + error);
                }
            }
        }
    }

    private void handleConnection(Socket socket) throws IOException {
        socket.setSoTimeout(5000);
        InputStream in = socket.getInputStream();
        Request request = new Request();

        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return;
        String[] parts = requestLine.split(" ");
        request.method = parts.length > 0 ? parts[0] : "";
        request.path = parts.length > 1 ? parts[1] : "";

        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                request.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        String lengthHeader = request.header("Content-Length");
        if (lengthHeader != null) {
            int length = Integer.parseInt(lengthHeader.trim());
            byte[] buffer = new byte[length];
            int read = 0;
            while (read < length) {
                int got = in.read(buffer, read, length - read);
                if (got < 0) break;
                read += got;
            }
            request.body = new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
        requests.add(request);

        Handler active = handler;
        if (active == null || !request.path.equals(path)) {
            writeAll(socket, 404, "{\"error\":\"no route\"}".getBytes(StandardCharsets.UTF_8),
                    "application/json", null, -1);
            return;
        }
        Response response = new Response();
        active.handle(request, response);
        writeAll(socket, response.status, response.body, null, response, response.truncateAt);
    }

    private void writeAll(Socket socket, int status, byte[] body, String forcedContentType,
                          Response response, int truncateAt) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        head.append("Connection: close\r\n");
        if (response != null) {
            for (java.util.Map.Entry<String, String> entry : response.headers.entrySet()) {
                head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        if (forcedContentType != null) head.append("Content-Type: ").append(forcedContentType).append("\r\n");

        long declared = response != null && response.declaredLength >= 0 ? response.declaredLength : body.length;
        head.append("Content-Length: ").append(declared).append("\r\n\r\n");

        OutputStream raw = socket.getOutputStream();
        BufferedOutputStream out = new BufferedOutputStream(raw);
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        if (truncateAt > 0 && truncateAt < body.length) {
            // 模拟「下到一半连接被掐断」。
            //
            // 关键是必须发 RST（SO_LINGER=0 + close），不能只是「少写几个字节然后正常关」——
            // 后者对 HttpURLConnection 来说和「Content-Length 没写对但是传输正常结束」无法区分，
            // 客户端会把短读当成完整的 body（实测就是这样：第一段 60000 字节被当成完整响应收下）。
            // 只有 RST 才会让客户端在读体的过程中抛 IOException。
            //
            // 顺序上先 flush head + 前 truncateAt 字节，**再**开 SO_LINGER：
            // 这样状态行和响应头已经在内核发送缓冲里发出去了，客户端读得到状态码
            // （早先的写法把 SO_LINGER 设在写之前，RST 把状态行一起冲掉了，
            //  客户端只看到「HTTP -1」这种没用的错误）。
            out.write(body, 0, truncateAt);
            out.flush();
            try {
                socket.setSoLinger(true, 0);
            } catch (IOException ignored) {
                // 设不上就退化成正常关闭，测试会以另一种方式失败，不影响生产代码结论。
            }
            return;
        }
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            default: return "Status";
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int read;
        while ((read = in.read()) >= 0) {
            if (read == '\n') break;
            if (read != '\r') buffer.write(read);
        }
        if (read < 0 && buffer.size() == 0) return null;
        return buffer.toString(StandardCharsets.US_ASCII.name());
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte value : digest) out.append(String.format("%02x", value));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // 关不掉就算了，测试进程结束时会一起没。
        }
    }
}

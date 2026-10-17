package cn.szu.bot.testsupport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 假 OneBot / NapCat：最小 RFC 6455 服务端，**只绑 {@code 127.0.0.1}**、端口由操作系统分配。
 *
 * <p>测试里机器人的 {@code QqClient} 连的就是它——永远连不到真 NapCat，也就不会给真群发消息（第 8 条）。
 * 端口一律 {@code new ServerSocket(0, …, 127.0.0.1)}，启动后 {@link #port()} 读实际值（第 5、6 条）。
 *
 * <p>能力够用就好：握手、收客户端帧（支持分片）、往下发事件帧、收 API 动作并回 {@code echo}。
 * 写法与 {@code QqClientTest} 里的 MockServer 一致（那边是一份内联实现）。
 */
public final class FakeOneBot implements AutoCloseable {

    private final ServerSocket server;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "fake-onebot");
        thread.setDaemon(true);
        return thread;
    });
    private final BlockingQueue<Connection> pending = new LinkedBlockingQueue<>();
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public FakeOneBot() throws IOException {
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        executor.execute(() -> {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    Connection connection;
                    try {
                        connection = new Connection(socket);
                    } catch (Exception badHandshake) {
                        // 有人只是开个 TCP 连接就断（比如端口可达性探测）：丢掉这条，继续 accept。
                        try { socket.close(); } catch (IOException ignored) { }
                        continue;
                    }
                    connections.add(connection);
                    pending.add(connection);
                    executor.execute(connection::readLoop);
                } catch (Exception error) {
                    if (!closed) throw new RuntimeException(error);
                }
            }
        });
    }

    public int port() { return server.getLocalPort(); }

    /** {@code ws://127.0.0.1:<实际端口>}。 */
    public String url() { return "ws://127.0.0.1:" + port(); }

    /** 等一个客户端连上来（超时抛 {@link AssertionError}）。 */
    public Connection connection() throws Exception {
        Connection connection = pending.poll(10, TimeUnit.SECONDS);
        if (connection == null) throw new AssertionError("假 OneBot：10 秒内没有客户端连上来");
        return connection;
    }

    public List<Connection> all() { return List.copyOf(connections); }

    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        for (Connection connection : connections) connection.closeQuietly();
        executor.shutdownNow();
        pending.clear();
    }

    @Override public String toString() { return "假OneBot=127.0.0.1:" + port(); }

    /** 一条 WebSocket 连接：收客户端 API 动作、往客户端发事件。 */
    public static final class Connection implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final String headers;
        private final BlockingQueue<JsonObject> requests = new LinkedBlockingQueue<>();

        Connection(Socket socket) throws Exception {
            this.socket = socket;
            socket.setSoTimeout(20000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4 && head.size() < 16384) {
                int value = in.read();
                if (value < 0) throw new EOFException();
                head.write(value);
                state = (state == 0 || state == 2) && value == '\r' ? state + 1
                        : (state == 1 || state == 3) && value == '\n' ? state + 1 : 0;
            }
            headers = head.toString(StandardCharsets.US_ASCII);
            String key = Arrays.stream(headers.split("\r\n"))
                    .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))
                    .findFirst().orElseThrow().split(":", 2)[1].trim();
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        /** 握手请求头（断言 Authorization 之类用）。 */
        public String headers() { return headers; }

        /** 下一个 API 动作（机器人发来的 send_group_msg 之类）。 */
        public JsonObject request() throws Exception {
            JsonObject request = requests.poll(5, TimeUnit.SECONDS);
            if (request == null) throw new AssertionError("假 OneBot：5 秒内没有收到任何 API 动作");
            return request;
        }

        /** 回一个 {@code {status,retcode,echo}}。 */
        public void respond(JsonObject request, String status, int retcode) throws IOException {
            JsonObject response = new JsonObject();
            response.add("echo", request.get("echo"));
            response.addProperty("status", status);
            response.addProperty("retcode", retcode);
            send(response.toString());
        }

        /** 往机器人那边推一条事件（比如一条群消息）。 */
        public void send(String text) throws IOException { frame(1, true, text.getBytes(StandardCharsets.UTF_8)); }

        /** 故意分片，验证客户端拼接。 */
        public void fragment(String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            int middle = bytes.length / 2;
            frame(1, false, Arrays.copyOfRange(bytes, 0, middle));
            frame(0, true, Arrays.copyOfRange(bytes, middle, bytes.length));
        }

        public synchronized void frame(int opcode, boolean last, byte[] bytes) throws IOException {
            out.write((last ? 128 : 0) | opcode);
            if (bytes.length < 126) out.write(bytes.length);
            else if (bytes.length <= 65535) { out.write(126); out.write(bytes.length >>> 8); out.write(bytes.length); }
            else { out.write(127); for (int shift = 56; shift >= 0; shift -= 8) out.write((int) ((long) bytes.length >>> shift)); }
            out.write(bytes);
            out.flush();
        }

        void readLoop() {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            try {
                while (!socket.isClosed()) {
                    int first = in.read();
                    if (first < 0) return;
                    int second = in.read();
                    if (second < 0) return;
                    long length = second & 127;
                    if (length == 126) length = ((long) readByte() << 8) | readByte();
                    else if (length == 127) { length = 0; for (int i = 0; i < 8; i++) length = (length << 8) | readByte(); }
                    if (length < 0 || length > 64L * 1024 * 1024) throw new IOException("帧长度非法");
                    byte[] mask = (second & 128) != 0 ? in.readNBytes(4) : new byte[0];
                    byte[] bytes = in.readNBytes((int) length);
                    if (bytes.length != length) return;
                    if (mask.length > 0) for (int i = 0; i < bytes.length; i++) bytes[i] ^= mask[i % 4];
                    int opcode = first & 15;
                    if (opcode == 8) return;
                    if (opcode == 9) { frame(10, true, bytes); continue; }
                    if (opcode == 1 || opcode == 0) {
                        text.write(bytes);
                        if ((first & 128) != 0) {
                            requests.add(JsonParser.parseString(text.toString(StandardCharsets.UTF_8)).getAsJsonObject());
                            text.reset();
                        }
                    }
                }
            } catch (IOException ignored) { /* 客户端断开 */ }
        }

        private int readByte() throws IOException {
            int value = in.read();
            if (value < 0) throw new EOFException();
            return value;
        }

        void closeQuietly() { try { socket.close(); } catch (IOException ignored) { } }

        @Override public void close() { closeQuietly(); }
    }
}

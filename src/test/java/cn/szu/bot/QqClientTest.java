package cn.szu.bot;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import cn.szu.bot.qq.QqClient;

/** Offline integration test: a minimal RFC 6455 server, never a real QQ connection. */
public final class QqClientTest {
    public static void main(String[] args) throws Exception {
        try (MockServer server = new MockServer()) {
            JsonObject config = new JsonObject();
            config.addProperty("ws_url", "ws://127.0.0.1:" + server.port());
            config.addProperty("access_token", "offline-test-token");
            config.addProperty("api_timeout_seconds", 1);
            config.addProperty("reconnect_seconds", 1);
            config.addProperty("bot_name", "测试小鸟");
            BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
            try (QqClient client = new QqClient(config)) {
                check(!client.isConnected(), "must initially be disconnected");
                client.start(events::add);
                Connection first = server.connection();
                await(client::isConnected);
                check(first.headers.contains("Authorization: Bearer offline-test-token"), "authentication header missing");

                JsonObject group = event("group", 123456789012345L, 6789L);
                first.fragment(group.toString());
                JsonObject received = events.poll(3, TimeUnit.SECONDS);
                check(received != null && received.get("group_id").getAsLong() == 123456789012345L, "fragmented event lost");

                JsonObject own = event("private", 0, 999);
                first.send(own.toString());
                // 群禁言通知必须送到机器人：它据此决定"还能不能发消息"，不能再当噪声丢掉。
                first.send("{\"post_type\":\"notice\",\"notice_type\":\"group_ban\",\"sub_type\":\"ban\","
                        + "\"group_id\":123,\"user_id\":0,\"duration\":60,\"self_id\":999}");
                first.send("not JSON");
                JsonObject privateEvent = event("private", 4455, 2233);
                first.send(privateEvent.toString());
                JsonObject noticeReceived = events.poll(3, TimeUnit.SECONDS);
                check(noticeReceived != null && "notice".equals(noticeReceived.get("post_type").getAsString())
                                && "group_ban".equals(noticeReceived.get("notice_type").getAsString()),
                        "群禁言通知要送到机器人：" + noticeReceived);
                JsonObject privateReceived = events.poll(3, TimeUnit.SECONDS);
                check(privateReceived != null && privateReceived.get("user_id").getAsLong() == 2233, "self/notice filters failed");
                check(events.isEmpty(), "unexpected event delivered");

                JsonArray message = JsonParser.parseString("[{\"type\":\"text\",\"data\":{\"text\":\"offline test\"}}]").getAsJsonArray();
                message = Maps.text("line1\nline2\nline3\nline4\nline5\nline6\nline7");
                CompletableFuture<Void> groupResult = client.send(group, message);
                JsonObject groupRequest = first.request();
                check("send_group_forward_msg".equals(groupRequest.get("action").getAsString()), "group action incorrect");
                check(groupRequest.getAsJsonObject("params").get("group_id").getAsLong() == 123456789012345L, "group ID truncated");
                JsonObject groupParams = groupRequest.getAsJsonObject("params");
                check(!groupParams.has("message"), "plain-message field must not be used");
                JsonObject node = groupParams.getAsJsonArray("messages").get(0).getAsJsonObject();
                check(node.get("type").getAsString().equals("node"), "response must be a forward node");
                check(node.getAsJsonObject("data").get("uin").getAsLong() == 999, "forward author must be the bot, not the caller");
                check("测试小鸟".equals(node.getAsJsonObject("data").get("name").getAsString()), "forward author name must come from the configured bot_name");
                check(node.getAsJsonObject("data").getAsJsonArray("content").equals(message), "text content changed");
                check(!groupResult.isDone(), "send completed before API acknowledgement");
                first.send("{\"status\":\"ok\",\"retcode\":0,\"echo\":\"unmatched\"}");
                first.respond(groupRequest, "ok", 0);
                groupResult.get(3, TimeUnit.SECONDS);
                for (JsonObject target : List.of(group, privateEvent)) {
                    for (String shortText : List.of("one line", "1\r\n2\r\n3\r\n4\r\n5\r\n6")) {
                        JsonArray shortSegments = Maps.text(shortText);
                        CompletableFuture<Void> shortResult = client.send(target, shortSegments);
                        JsonObject shortRequest = first.request();
                        check(shortRequest.get("action").getAsString().equals("send_" + target.get("message_type").getAsString() + "_msg"), "under seven lines should remain plain");
                        check(shortRequest.getAsJsonObject("params").getAsJsonArray("message").equals(shortSegments), "short message changed");
                        first.respond(shortRequest, "ok", 0); shortResult.get(3, TimeUnit.SECONDS);
                    }
                }

                String longText = "x".repeat(2999) + "😀" + "尾部".repeat(2000);
                JsonArray mixed = Maps.text(longText);
                JsonObject imageData = new JsonObject(); imageData.addProperty("file", "base64://aW1hZ2U=");
                JsonObject image = new JsonObject(); image.addProperty("type", "image"); image.add("data", imageData);
                mixed.add(image);
                CompletableFuture<Void> mixedResult = client.send(privateEvent, mixed);
                JsonObject mixedRequest = first.request();
                check(mixedRequest.get("action").getAsString().equals("send_private_forward_msg"), "mixed private reply must be folded");
                JsonArray nodes = mixedRequest.getAsJsonObject("params").getAsJsonArray("messages");
                check(nodes.size() == 4, "long text and image must share one forward card");
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < nodes.size() - 1; i++) {
                    String part = nodes.get(i).getAsJsonObject().getAsJsonObject("data").getAsJsonArray("content")
                            .get(0).getAsJsonObject().getAsJsonObject("data").get("text").getAsString();
                    check(part.length() <= 3000 && !Character.isHighSurrogate(part.charAt(part.length() - 1)), "Unicode split is unsafe");
                    joined.append(part);
                }
                check(joined.toString().equals(longText), "long text lost content");
                check(nodes.get(3).getAsJsonObject().getAsJsonObject("data").getAsJsonArray("content").get(0).equals(image), "image payload changed");
                check(!mixedResult.isDone(), "forward image must await successful ACK");
                first.respond(mixedRequest, "ok", 0);
                mixedResult.get(3, TimeUnit.SECONDS);
                for (JsonObject destination : List.of(group, privateEvent)) {
                    JsonArray batch = new JsonArray();
                    for (int n = 0; n < 24; n++) {
                        JsonObject local = image.deepCopy();
                        local.getAsJsonObject("data").addProperty("file", "file:///tmp/pixiko/data/generated/image%20" + n + ".png");
                        batch.add(local);
                    }
                    CompletableFuture<Void> sentBatch = client.send(destination, batch);
                    JsonObject batchRequest = first.request();
                    check(batchRequest.get("action").getAsString().endsWith("_forward_msg"), "multi-image batch is one forward request");
                    JsonArray batchNodes = batchRequest.getAsJsonObject("params").getAsJsonArray("messages");
                    check(batchNodes.size() == 24, "all images share a single forward card");
                    for (int n = 0; n < 24; n++) check(batchNodes.get(n).getAsJsonObject().getAsJsonObject("data")
                            .getAsJsonArray("content").get(0).equals(batch.get(n)), "local file reference and order preserved");
                    // The normal timeout is one second; image upload receives its own longer budget.
                    Thread.sleep(1200);
                    check(!sentBatch.isDone(), "image ACK does not expire at ordinary text timeout");
                    first.respond(batchRequest, "ok", 0); sentBatch.get(3, TimeUnit.SECONDS);
                }
                JsonObject invalidAuthor = group.deepCopy(); invalidAuthor.remove("self_id");
                expectFailure(client.send(invalidAuthor, message), "self_id");
                for (JsonObject mapEvent : List.of(group, privateEvent)) {
                    JsonArray mapSegments = new JsonArray(); mapSegments.add(image.deepCopy());
                    CompletableFuture<Void> mapResult = client.sendMap(mapEvent, mapSegments);
                    JsonObject mapRequest = first.request();
                    String mapAction = mapEvent.get("message_type").getAsString().equals("group") ? "send_group_msg" : "send_private_msg";
                    check(mapRequest.get("action").getAsString().equals(mapAction), "maps must remain ordinary image messages");
                    check(!mapRequest.getAsJsonObject("params").has("messages") && mapRequest.getAsJsonObject("params").getAsJsonArray("message").equals(mapSegments), "map image must not be folded");
                    check(!mapResult.isDone(), "map send still requires ACK");
                    first.respond(mapRequest, "ok", 0);
                    mapResult.get(3, TimeUnit.SECONDS);
                }

                List<CompletableFuture<Void>> burst = new ArrayList<>();
                for (int i = 0; i < 12; i++) burst.add(client.send(group, message));
                Set<String> echoes = new HashSet<>();
                for (int i = 0; i < 12; i++) {
                    JsonObject request = first.request();
                    check(echoes.add(request.get("echo").getAsString()), "duplicate echo");
                    first.respond(request, "ok", 0);
                }
                CompletableFuture.allOf(burst.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);

                for (int i = 0; i < 10; i++) {
                    JsonObject ordered = group.deepCopy();
                    ordered.addProperty("message_id", i);
                    first.send(ordered.toString());
                }
                for (int i = 0; i < 10; i++) {
                    JsonObject ordered = events.poll(3, TimeUnit.SECONDS);
                    check(ordered != null && ordered.get("message_id").getAsInt() == i, "event dispatch order changed");
                }

                // A rejected chat record is re-sent as ordinary text so a long reply is never silently lost.
                CompletableFuture<Void> privateResult = client.send(privateEvent, message);
                JsonObject privateRequest = first.request();
                check("send_private_forward_msg".equals(privateRequest.get("action").getAsString()), "private action incorrect");
                JsonObject params = privateRequest.getAsJsonObject("params");
                check(params.get("user_id").getAsLong() == 2233 && params.get("group_id").getAsLong() == 4455, "temporary private session IDs missing");
                first.respond(privateRequest, "failed", 1200);
                JsonObject fallbackRequest = first.request();
                check("send_private_msg".equals(fallbackRequest.get("action").getAsString()), "rejected chat record must fall back to a plain message");
                JsonObject fallbackParams = fallbackRequest.getAsJsonObject("params");
                check(fallbackParams.get("user_id").getAsLong() == 2233 && fallbackParams.get("group_id").getAsLong() == 4455, "fallback must keep the session");
                check(!fallbackParams.has("messages") && fallbackParams.getAsJsonArray("message").equals(message), "fallback must resend the original text as a plain message: " + fallbackParams + " vs " + message);
                first.respond(fallbackRequest, "ok", 0);
                privateResult.get(3, TimeUnit.SECONDS);

                CompletableFuture<Void> rejectedGroup = client.send(group, message);
                JsonObject rejectedGroupRequest = first.request();
                check("send_group_forward_msg".equals(rejectedGroupRequest.get("action").getAsString()), "group chat record action incorrect");
                first.respond(rejectedGroupRequest, "failed", 1200);
                JsonObject groupFallback = first.request();
                check("send_group_msg".equals(groupFallback.get("action").getAsString()), "group fallback action incorrect");
                first.respond(groupFallback, "ok", 0);
                rejectedGroup.get(3, TimeUnit.SECONDS);

                // Messages that contain images keep the documented rule: report the failure, never re-send.
                CompletableFuture<Void> rejectedMixed = client.send(privateEvent, mixed);
                JsonObject rejectedMixedRequest = first.request();
                check(rejectedMixedRequest.get("action").getAsString().endsWith("_forward_msg"), "mixed reply must be folded");
                first.respond(rejectedMixedRequest, "failed", 100);
                try { rejectedMixed.get(4, TimeUnit.SECONDS); throw new AssertionError("expected a reported rejection"); }
                catch (ExecutionException ex) {
                    String failure = ex.getCause().getMessage();
                    check(failure.contains("send_private_forward_msg") && failure.contains("retcode=100"),
                            "diagnostics must name the failed API and retcode: " + failure);
                }

                // The QQ implementation's own reason is surfaced for diagnosis, with credentials redacted.
                JsonObject failedPacket = JsonParser.parseString("{\"status\":\"failed\",\"retcode\":1200,"
                        + "\"message\":\"富媒体消息发送失败\",\"wording\":\"access_token=secret-value\"}").getAsJsonObject();
                String detail = QqClient.failureDetail(failedPacket);
                check(detail.contains("富媒体消息发送失败"), "the implementation reason must be surfaced: " + detail);
                check(detail.contains("（凭据已隐藏）") && !detail.contains("secret-value"), "credentials must be redacted: " + detail);
                check(QqClient.failureDetail(new JsonObject()).isEmpty(), "a missing reason stays empty");

                CompletableFuture<Void> asyncResult = client.send(privateEvent, message);
                first.respond(first.request(), "async", 1);
                expectFailure(asyncResult, "异步");

                CompletableFuture<Void> timedOut = client.send(group, message);
                first.request();
                expectFailure(timedOut, "超时");

                CompletableFuture<Void> interrupted = client.send(group, message);
                first.request();
                first.close();
                expectFailure(interrupted, "断开");
                Connection second = server.connection();
                await(client::isConnected);
                CompletableFuture<Void> reconnected = client.send(group, message);
                JsonObject afterReconnect = second.request();
                second.respond(afterReconnect, "ok", 0);
                reconnected.get(3, TimeUnit.SECONDS);

                // A malicious oversized event is bounded and the connection is replaced.
                second.send("x".repeat(2 * 1024 * 1024 + 1));
                Connection third = server.connection();
                await(client::isConnected);
                check(third != second, "oversized input did not reconnect");
                client.close();
                check(!client.isConnected(), "close did not disconnect");
                expectFailure(client.send(group, message), "未连接");
            }
        }
        System.out.println("QqClientTest: PASS (framing, IDs, authentication, filtering, acknowledgements, rejection, async, timeout, reconnect, size limit, close)");
    }

    private static JsonObject event(String type, long group, long user) {
        JsonObject event = new JsonObject();
        event.addProperty("post_type", "message");
        event.addProperty("message_type", type);
        event.addProperty("user_id", user);
        event.addProperty("self_id", 999);
        event.addProperty("message_id", 123);
        if (group > 0) event.addProperty("group_id", group);
        event.addProperty("raw_message", ".help");
        return event;
    }
    private static void expectFailure(CompletableFuture<Void> future, String text) throws Exception {
        try { future.get(4, TimeUnit.SECONDS); throw new AssertionError("expected failure containing: " + text); }
        catch (ExecutionException ex) { check(ex.getCause().getMessage().contains(text), "unexpected failure: " + ex.getCause()); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        check(condition.getAsBoolean(), "condition timed out");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class MockServer implements AutoCloseable {
        final ServerSocket server;
        final BlockingQueue<Connection> connections = new LinkedBlockingQueue<>();
        final List<Connection> all = new CopyOnWriteArrayList<>();
        final ExecutorService executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "mock-qq"); t.setDaemon(true); return t; });
        volatile boolean closed;
        MockServer() throws IOException {
            server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            executor.execute(() -> {
                while (!closed) {
                    try {
                        Connection connection = new Connection(server.accept());
                        all.add(connection);
                        connections.add(connection);
                        executor.execute(connection::readLoop);
                    } catch (Exception ex) { if (!closed) throw new RuntimeException(ex); }
                }
            });
        }
        int port() { return server.getLocalPort(); }
        Connection connection() throws Exception {
            Connection connection = connections.poll(5, TimeUnit.SECONDS);
            if (connection == null) throw new AssertionError("WebSocket connection timed out");
            return connection;
        }
        public void close() throws IOException {
            closed = true;
            server.close();
            for (Connection connection : all) connection.close();
            executor.shutdownNow();
        }
    }

    private static final class Connection implements AutoCloseable {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        final String headers;
        final BlockingQueue<JsonObject> requests = new LinkedBlockingQueue<>();
        Connection(Socket socket) throws Exception {
            this.socket = socket;
            socket.setSoTimeout(15000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4 && head.size() < 16384) {
                int b = in.read();
                if (b < 0) throw new EOFException();
                head.write(b);
                state = (state == 0 || state == 2) && b == '\r' ? state + 1
                        : (state == 1 || state == 3) && b == '\n' ? state + 1 : 0;
            }
            headers = head.toString(StandardCharsets.US_ASCII);
            String key = Arrays.stream(headers.split("\r\n"))
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))
                    .findFirst().orElseThrow().split(":", 2)[1].trim();
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        JsonObject request() throws Exception {
            JsonObject request = requests.poll(3, TimeUnit.SECONDS);
            if (request == null) throw new AssertionError("No API action received");
            return request;
        }
        void respond(JsonObject request, String status, int retcode) throws IOException {
            JsonObject response = new JsonObject();
            response.add("echo", request.get("echo"));
            response.addProperty("status", status);
            response.addProperty("retcode", retcode);
            send(response.toString());
        }
        void send(String text) throws IOException { frame(1, true, text.getBytes(StandardCharsets.UTF_8)); }
        void fragment(String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            int middle = bytes.length / 2;
            frame(1, false, Arrays.copyOfRange(bytes, 0, middle));
            frame(0, true, Arrays.copyOfRange(bytes, middle, bytes.length));
        }
        synchronized void frame(int opcode, boolean last, byte[] bytes) throws IOException {
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
                    if (length < 0 || length > 64 * 1024 * 1024) throw new IOException("invalid frame size");
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
            } catch (IOException ignored) { }
        }
        int readByte() throws IOException { int value = in.read(); if (value < 0) throw new EOFException(); return value; }
        public void close() throws IOException { socket.close(); }
    }
}

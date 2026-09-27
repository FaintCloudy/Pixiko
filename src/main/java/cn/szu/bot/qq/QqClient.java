package cn.szu.bot.qq;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import cn.szu.bot.Json;
import cn.szu.bot.Log;
import cn.szu.bot.Maps;

/** QQ (NapCat) forward WebSocket client: API acknowledgements and messages share '/'. */
public final class QqClient implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final int MAX_TEXT_CHARS = 2 * 1024 * 1024;
    private static final long MAX_QUEUED_CHARS = 64L * 1024 * 1024;
    private static final int MAX_PENDING = 64;
    private final URI uri;
    private final String token;
    private final int timeoutSeconds;
    private final int imageTimeoutSeconds;
    private final int reconnectSeconds;
    /** Author name shown on forward-message cards, persisted in config.json as bot_name. */
    private final String forwardName;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemon("pixiko-timer"));
    private final ThreadPoolExecutor events = singleQueue("pixiko-events", 128);
    private final ThreadPoolExecutor outgoing = singleQueue("pixiko-send", MAX_PENDING);
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Semaphore pendingSlots = new Semaphore(MAX_PENDING);
    private final AtomicLong queuedChars = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private volatile WebSocket socket;
    private volatile boolean connectionFailureLogged;
    private volatile Consumer<JsonObject> handler;
    private final Object lifecycle = new Object();

    public QqClient(JsonObject root) {
        JsonObject config = root.has("qq") && root.get("qq").isJsonObject()
                ? root.getAsJsonObject("qq") : root;
        uri = URI.create(string(config, "ws_url", "ws://127.0.0.1:3001"));
        if (!List.of("ws", "wss").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("qq.ws_url 必须是有效的 ws:// 或 wss:// 地址");
        }
        token = string(config, "access_token", "");
        timeoutSeconds = integer(config, "api_timeout_seconds", integer(config, "timeout_seconds", 30));
        imageTimeoutSeconds = integer(config, "image_api_timeout_seconds", 600);
        reconnectSeconds = integer(config, "reconnect_seconds", 5);
        String name = string(root, "bot_name", "神户小鸟").strip();
        forwardName = name.isEmpty() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl) ? "神户小鸟" : name;
        if (timeoutSeconds < 1 || timeoutSeconds > 300 || reconnectSeconds < 1 || reconnectSeconds > 300) {
            throw new IllegalArgumentException("QQ 超时和重连间隔须为 1–300 秒");
        }
        if (imageTimeoutSeconds < 1 || imageTimeoutSeconds > 7200)
            throw new IllegalArgumentException("QQ 图片超时须为 1–7200 秒");
    }

    public void start(Consumer<JsonObject> handler) {
        if (handler == null) throw new IllegalArgumentException("handler is required");
        synchronized (lifecycle) {
            if (closed.get()) throw new IllegalStateException("QQ client is closed");
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("QQ client already started");
            this.handler = handler;
            // One fixed timer and one in-flight handshake prevent overlapping reconnect loops.
            scheduler.scheduleWithFixedDelay(this::connectIfNeeded, 0, reconnectSeconds, TimeUnit.SECONDS);
        }
    }

    public boolean isConnected() {
        WebSocket ws = socket;
        return !closed.get() && ws != null && !ws.isInputClosed() && !ws.isOutputClosed();
    }

    /** Completes only when QQ acknowledges a successful synchronous API action. */
    public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
        return send(event, segments, true);
    }

    /** One chat record holding several messages; each entry becomes its own node. */
    public CompletableFuture<Void> sendRecord(JsonObject event, java.util.List<JsonArray> messages) {
        if (messages == null || messages.isEmpty()) return failed("聊天记录不能为空");
        if (messages.size() == 1) return send(event, messages.get(0));
        JsonArray combined = new JsonArray();
        for (JsonArray message : messages) for (JsonElement segment : message) combined.add(segment.deepCopy());
        WebSocket ws = socket;
        if (!isConnected() || ws == null) return failed("QQ 尚未连接，消息未发送");
        String type = string(event, "message_type", "");
        JsonObject params = new JsonObject();
        String action;
        try {
            if ("group".equals(type)) { action = "send_group_forward_msg"; params.add("group_id", id(event, "group_id")); }
            else if ("private".equals(type)) {
                action = "send_private_forward_msg"; params.add("user_id", id(event, "user_id"));
                if (event.has("group_id") && !event.get("group_id").isJsonNull()) params.add("group_id", id(event, "group_id"));
            } else return failed("仅支持群聊或私聊事件");
            JsonArray nodes = new JsonArray();
            JsonElement self = id(event, "self_id");
            int index = 0;
            for (JsonArray message : messages) {
                // Keep every receipt its own node, even when a transport would otherwise re-split the text.
                JsonArray content = new JsonArray();
                for (JsonElement segment : message) content.add(segment.deepCopy());
                nodes.add(forwardNode(self, content));
            }
            params.add("messages", nodes);
        } catch (RuntimeException error) { return failed("聊天记录缺少有效的会话 ID 或消息内容"); }
        return submit(ws, action, params, timeoutSeconds, action.replace("_forward_msg", "_msg"), List.of());
    }

    public CompletableFuture<Void> sendMap(JsonObject event, JsonArray segments) {
        return send(event, segments, false);
    }

    private CompletableFuture<Void> send(JsonObject event, JsonArray segments, boolean folded) {
        if (event == null || segments == null) return failed("消息事件和消息段不能为空");
        if (folded && segments.size() == 1 && segments.get(0).isJsonObject()) {
            JsonObject only = segments.get(0).getAsJsonObject();
            if ("text".equals(string(only, "type", ""))) {
                String text = string(Json.obj(only, "data"), "text", "");
                if (text.split("\\R", -1).length < 7) folded = false;
            }
        }
        WebSocket ws = socket;
        if (!isConnected() || ws == null) return failed("QQ 尚未连接，消息未发送");
        String type = string(event, "message_type", "");
        JsonObject params = new JsonObject();
        String action;
        try {
            if ("group".equals(type)) {
                action = folded ? "send_group_forward_msg" : "send_group_msg";
                params.add("group_id", id(event, "group_id"));
            } else if ("private".equals(type)) {
                action = folded ? "send_private_forward_msg" : "send_private_msg";
                params.add("user_id", id(event, "user_id"));
                // Some implementations use this for temporary private sessions originating in a group.
                if (event.has("group_id") && !event.get("group_id").isJsonNull()) {
                    params.add("group_id", id(event, "group_id"));
                }
            } else {
                return failed("仅支持群聊或私聊事件");
            }
        } catch (RuntimeException ex) {
            return failed("消息事件缺少有效的会话 ID");
        }
        try {
            if (folded) params.add("messages", forwardNodes(event, segments));
            else params.add("message", segments.deepCopy());
        }
        catch (RuntimeException ex) { return failed("聊天记录缺少有效的机器人 self_id 或消息内容"); }
        boolean images = false;
        for (JsonElement segment : segments) if (segment.isJsonObject()
                && "image".equals(string(segment.getAsJsonObject(), "type", ""))) images = true;
        final int responseTimeout = images ? Math.max(timeoutSeconds, imageTimeoutSeconds) : timeoutSeconds;
        return submit(ws, action, params, responseTimeout, folded ? action.replace("_forward_msg", "_msg") : null,
                folded ? plainFallbacks(event, segments) : List.of());
    }

    /**
     * Queues one API call. A folded chat record that the QQ implementation explicitly rejects is retried
     * as ordinary messages when it is text-only, so a long command reply is never silently lost. Results that
     * are merely unknown (timeout, disconnect) are never retried.
     */
    private CompletableFuture<Void> submit(WebSocket ws, String action, JsonObject params, int responseTimeout,
                                          String fallbackAction, List<JsonObject> fallbackParams) {
        String echo = UUID.randomUUID().toString();
        JsonObject request = new JsonObject();
        request.addProperty("action", action);
        request.add("params", params);
        request.addProperty("echo", echo);
        String payload = GSON.toJson(request);
        if (payload.length() > MAX_QUEUED_CHARS || !pendingSlots.tryAcquire()) return failed("QQ 发送队列已满或消息过大");
        if (queuedChars.addAndGet(payload.length()) > MAX_QUEUED_CHARS) {
            queuedChars.addAndGet(-payload.length());
            pendingSlots.release();
            return failed("QQ 发送队列已满");
        }
        Pending call = new Pending(ws, action);
        call.fallbackAction = fallbackAction;
        call.fallbackParams = fallbackParams;
        pending.put(echo, call);
        call.result.whenComplete((ignored, error) -> {
            if (pending.remove(echo, call)) pendingSlots.release();
            ScheduledFuture<?> timer = call.timer;
            if (timer != null) timer.cancel(false);
        });
        try {
            outgoing.execute(() -> {
                try {
                    if (call.result.isDone()) return;
                    if (closed.get() || ws != socket) throw new IOException("QQ 连接已断开，消息未重试");
                    // JDK WebSocket permits only one pending text send at a time.
                    ws.sendText(payload, true).get(timeoutSeconds, TimeUnit.SECONDS);
                    // Upload/ACK time starts after dispatch, not while waiting behind other sends.
                    call.timer = scheduler.schedule(() -> call.completeFailure(
                            new TimeoutException("QQ API 响应超时；发送结果未知，未自动重试")), responseTimeout, TimeUnit.SECONDS);
                    if (call.result.isDone()) call.timer.cancel(false);
                } catch (Exception ex) {
                    call.completeFailure(new IOException("QQ 发送失败；结果可能未知，未自动重试", ex));
                    if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                    // A stalled socket send otherwise leaves the JDK socket unable to send again.
                    if (ws == socket) {
                        ws.abort();
                        disconnected(ws);
                    }
                } finally {
                    queuedChars.addAndGet(-payload.length());
                }
            });
        } catch (RejectedExecutionException ex) {
            queuedChars.addAndGet(-payload.length());
            call.completeFailure(new IOException("QQ 客户端已关闭或发送队列已满"));
        }
        return call.result;
    }

    /**
     * QQ API passthrough for read-only lookups (member lists and similar). Completes with the raw
     * response {@code data} element; a rejected request surfaces the implementation's own reason.
     */
    public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
        if (action == null || action.isBlank()) return CompletableFuture.failedFuture(new IOException("QQ API 名称不能为空"));
        WebSocket ws = socket;
        if (!isConnected() || ws == null) return CompletableFuture.failedFuture(new IOException("QQ 尚未连接，查询未发送"));
        String echo = UUID.randomUUID().toString();
        JsonObject request = new JsonObject();
        request.addProperty("action", action);
        request.add("params", params == null ? new JsonObject() : params);
        request.addProperty("echo", echo);
        String payload = GSON.toJson(request);
        if (payload.length() > MAX_QUEUED_CHARS || !pendingSlots.tryAcquire())
            return CompletableFuture.failedFuture(new IOException("QQ 发送队列已满或请求过大"));
        if (queuedChars.addAndGet(payload.length()) > MAX_QUEUED_CHARS) {
            queuedChars.addAndGet(-payload.length());
            pendingSlots.release();
            return CompletableFuture.failedFuture(new IOException("QQ 发送队列已满"));
        }
        Pending call = new Pending(ws, action);
        call.data = new CompletableFuture<>();
        pending.put(echo, call);
        call.result.whenComplete((ignored, error) -> {
            if (pending.remove(echo, call)) pendingSlots.release();
            ScheduledFuture<?> timer = call.timer;
            if (timer != null) timer.cancel(false);
        });
        try {
            outgoing.execute(() -> {
                try {
                    if (call.result.isDone()) return;
                    if (closed.get() || ws != socket) throw new IOException("QQ 连接已断开，查询未发送");
                    ws.sendText(payload, true).get(timeoutSeconds, TimeUnit.SECONDS);
                    call.timer = scheduler.schedule(() -> call.completeFailure(
                            new TimeoutException("QQ API 响应超时；查询结果未知")), timeoutSeconds, TimeUnit.SECONDS);
                    if (call.result.isDone()) call.timer.cancel(false);
                } catch (Exception ex) {
                    call.completeFailure(new IOException("QQ 查询失败（" + action + "）：" + ex.getClass().getSimpleName(), ex));
                    if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                    if (ws == socket) { ws.abort(); disconnected(ws); }
                } finally {
                    queuedChars.addAndGet(-payload.length());
                }
            });
        } catch (RejectedExecutionException ex) {
            queuedChars.addAndGet(-payload.length());
            call.completeFailure(new IOException("QQ 客户端已关闭或发送队列已满"));
        }
        return call.data;
    }

    /** Sends fallback plain messages strictly in order, so a long text keeps its sequence. */
    private CompletableFuture<Void> sendPlain(String action, List<JsonObject> messages) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (JsonObject params : messages) {
            chain = chain.thenComposeAsync(ignored -> {
                WebSocket ws = socket;
                if (!isConnected() || ws == null) return failed("QQ 尚未连接，消息未发送");
                return submit(ws, action, params, timeoutSeconds, null, List.of());
            });
        }
        return chain;
    }

    /** Plain-message parameters for a folded text-only message; messages with images are never re-sent. */
    private static List<JsonObject> plainFallbacks(JsonObject event, JsonArray segments) {
        for (JsonElement value : segments)
            if (!value.isJsonObject() || !"text".equals(string(value.getAsJsonObject(), "type", ""))) return List.of();
        List<JsonObject> messages = new ArrayList<>();
        try {
            String type = string(event, "message_type", "");
            boolean group = "group".equals(type);
            for (JsonArray chunk : plainChunks(segments)) {
                JsonObject params = new JsonObject();
                if (group) params.add("group_id", id(event, "group_id"));
                else {
                    params.add("user_id", id(event, "user_id"));
                    if (event.has("group_id") && !event.get("group_id").isJsonNull()) params.add("group_id", id(event, "group_id"));
                }
                params.add("message", chunk);
                messages.add(params);
            }
        } catch (RuntimeException ex) { return List.of(); }
        return List.copyOf(messages);
    }

    private static List<JsonArray> plainChunks(JsonArray segments) {
        List<JsonArray> chunks = new ArrayList<>();
        for (JsonElement value : segments) {
            String text = value.getAsJsonObject().getAsJsonObject("data").get("text").getAsString();
            for (int offset = 0; offset < text.length();) {
                int end = Math.min(offset + 3000, text.length());
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                chunks.add(Maps.text(text.substring(offset, end)));
                offset = end;
            }
        }
        return chunks;
    }

    private JsonArray forwardNodes(JsonObject event, JsonArray segments) {
        JsonElement self = id(event, "self_id");
        if (segments.isEmpty()) throw new IllegalArgumentException("Empty message");
        JsonArray nodes = new JsonArray();
        for (JsonElement value : segments) {
            JsonObject segment = value.getAsJsonObject();
            if ("text".equals(string(segment, "type", ""))) {
                String text = segment.getAsJsonObject("data").get("text").getAsString();
                for (int offset = 0; offset < text.length();) {
                    int end = Math.min(offset + 3000, text.length());
                    if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                    nodes.add(forwardNode(self, Maps.text(text.substring(offset, end))));
                    offset = end;
                }
            } else {
                JsonArray content = new JsonArray();
                content.add(segment.deepCopy());
                nodes.add(forwardNode(self, content));
            }
        }
        if (nodes.isEmpty()) throw new IllegalArgumentException("Empty message");
        return nodes;
    }

    JsonObject forwardNode(JsonElement self, JsonArray content) {
        JsonObject data = new JsonObject();
        data.add("uin", self.deepCopy());
        data.addProperty("name", forwardName);
        data.add("content", content);
        JsonObject node = new JsonObject();
        node.addProperty("type", "node");
        node.add("data", data);
        return node;
    }

    private void connectIfNeeded() {
        if (closed.get() || socket != null || !connecting.compareAndSet(false, true)) return;
        try {
            WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
            if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
            builder.buildAsync(uri, new Listener()).whenComplete((ws, error) -> {
                connecting.set(false);
                if (error != null && !closed.get()) {
                    // Do not log the URI, headers, or exception message: they can contain secrets.
                    if (!connectionFailureLogged) {
                        connectionFailureLogged = true;
                        Log.warn("QQ 连接失败，将每 " + reconnectSeconds + " 秒重连。请检查地址、令牌和 QQ 服务。");
                    }
                }
                if (ws != null && closed.get()) ws.abort();
            });
        } catch (RuntimeException ex) {
            connecting.set(false);
            if (!closed.get() && !connectionFailureLogged) {
                connectionFailureLogged = true;
                Log.warn("QQ 连接配置错误，将稍后重试。");
            }
        }
    }

    private void disconnected(WebSocket ws) {
        boolean current;
        synchronized (lifecycle) {
            current = socket == ws;
            if (current) socket = null;
        }
        if (current && !closed.get()) Log.warn("QQ 连接已断开，将自动重连；发送中的消息结果未知，不自动重试。");
        pending.values().forEach(call -> {
            if (call.socket == ws) call.completeFailure(
                    new IOException("QQ 连接断开；发送结果可能未知，未自动重试"));
        });
    }

    private void receive(String text) {
        JsonObject packet;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) return;
            packet = parsed.getAsJsonObject();
            if (packet.has("echo") && packet.get("echo").isJsonPrimitive()) {
                Pending call = pending.get(packet.get("echo").getAsString());
                if (call == null) return;
                String status = string(packet, "status", "");
                int retcode = integer(packet, "retcode", Integer.MIN_VALUE);
                if ("ok".equals(status) && retcode == 0) {
                    call.completeSuccess(packet.get("data"));
                } else if ("async".equals(status)) {
                    call.completeFailure(new IOException("QQ 已转为异步处理，无法确认消息发送结果；未自动重试"));
                } else {
                    // NapCat puts the real reason (for example why an image upload failed) in message/wording.
                    String detail = failureDetail(packet);
                    IOException failure = new IOException("QQ 发送失败（" + call.action + "），retcode=" + retcode
                            + (detail.isEmpty() ? "" : "：" + detail));
                    if (call.fallbackAction != null && call.fallbackParams != null && !call.fallbackParams.isEmpty() && !closed.get()) {
                        Log.warn("聊天记录被拒绝（" + call.action + "，retcode=" + retcode + "），改用普通消息重发："
                                + (detail.isEmpty() ? "未提供原因" : detail));
                        sendPlain(call.fallbackAction, call.fallbackParams).whenComplete((ignored, error) -> {
                            if (error == null) {
                                Log.info("普通消息重发成功（" + call.fallbackParams.size() + " 条）。");
                                call.completeSuccess(null);
                            } else call.completeFailure(failure);
                        });
                    } else call.completeFailure(failure);
                }
                return;
            }
            String postType = string(packet, "post_type", "");
            // 通知事件（群禁言之类）也要送到机器人：它决定"现在能不能发消息"，不能在这里被丢掉。
            if (!"message".equals(postType) && !"notice".equals(postType)) return;
            if ("message".equals(postType)) {
                String type = string(packet, "message_type", "");
                if (!"group".equals(type) && !"private".equals(type)) return;
                String self = string(packet, "self_id", "");
                if (!self.isEmpty() && self.equals(string(packet, "user_id", ""))) return;
            }
        } catch (RuntimeException ex) {
            Log.warn("忽略无法解析的 QQ 消息。");
            return;
        }
        try {
            events.execute(() -> {
                if (closed.get()) return;
                try { handler.accept(packet); }
                catch (RuntimeException ex) { Log.error("QQ 消息处理失败", ex); }
            });
        } catch (RejectedExecutionException ex) {
            if (!closed.get()) Log.warn("QQ 消息处理队列已满，已忽略该事件。");
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        WebSocket ws;
        synchronized (lifecycle) {
            ws = socket;
            socket = null;
        }
        if (ws != null) ws.abort();
        pending.values().forEach(call -> call.completeFailure(new IOException("QQ 客户端已关闭")));
        scheduler.shutdownNow();
        outgoing.shutdownNow();
        events.shutdownNow();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket ws) {
            synchronized (lifecycle) {
                if (closed.get() || socket != null) { ws.abort(); return; }
                socket = ws;
            }
            connectionFailureLogged = false;
            Log.info("QQ 连接已建立。" + uri.getHost() + ":" + (uri.getPort() >= 0 ? uri.getPort() : 80));
            ws.request(1);
        }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (buffer.length() + (long) data.length() > MAX_TEXT_CHARS) {
                buffer.setLength(0);
                ws.abort();
                disconnected(ws);
                return CompletableFuture.completedFuture(null);
            }
            buffer.append(data);
            if (last) {
                String complete = buffer.toString();
                buffer.setLength(0);
                receive(complete);
            }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            disconnected(ws);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket ws, Throwable error) { disconnected(ws); }
    }

    private static final class Pending {
        final WebSocket socket;
        final String action;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        /** Non-null for API passthrough calls, which complete with the raw response `data`. */
        CompletableFuture<JsonElement> data;
        volatile ScheduledFuture<?> timer;
        volatile String fallbackAction;
        volatile List<JsonObject> fallbackParams;
        Pending(WebSocket socket, String action) { this.socket = socket; this.action = action; }
        void completeSuccess(JsonElement value) {
            if (data != null) data.complete(value == null || value.isJsonNull() ? JsonNull.INSTANCE : value);
            result.complete(null);
        }
        void completeFailure(Throwable failure) {
            if (data != null) data.completeExceptionally(failure);
            result.completeExceptionally(failure);
        }
    }

    /** Reasons reported by the QQ implementation, bounded and stripped of credentials. */
    public static String failureDetail(JsonObject packet) {
        StringBuilder detail = new StringBuilder();
        for (String key : new String[]{"message", "wording", "msg"}) {
            JsonElement value = packet.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
            String text = value.getAsString().replaceAll("[\\p{Cntrl}\\p{Cf}]+", " ").replaceAll("\\s+", " ").strip();
            if (text.isEmpty()) continue;
            if (detail.length() > 0) detail.append(" / ");
            detail.append(text);
            if (detail.length() > 200) break;
        }
        String text = detail.length() > 200 ? detail.substring(0, 200) + "…" : detail.toString();
        return text.replaceAll("(?i)(access[_-]?token|token|authorization)(\\s*[:=]\\s*)[^\\s,;&\"']+", "$1$2（凭据已隐藏）");
    }

    private static JsonElement id(JsonObject event, String key) {
        JsonElement value = event.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsString().matches("[1-9][0-9]{0,19}")) {
            throw new IllegalArgumentException("Invalid ID");
        }
        return value.deepCopy();
    }
    private static CompletableFuture<Void> failed(String message) { return CompletableFuture.failedFuture(new IOException(message)); }
    private static String string(JsonObject obj, String key, String fallback) {
        JsonElement value = obj.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsString();
    }
    private static int integer(JsonObject obj, String key, int fallback) {
        try { return obj.has(key) ? obj.get(key).getAsInt() : fallback; }
        catch (RuntimeException ex) { return fallback; }
    }
    private static ThreadFactory daemon(String name) {
        return runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
    }
    private static ThreadPoolExecutor singleQueue(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), daemon(name), new ThreadPoolExecutor.AbortPolicy());
    }
}

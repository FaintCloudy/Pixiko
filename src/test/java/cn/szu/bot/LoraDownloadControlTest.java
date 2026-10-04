package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.civitai.DownloadControl;
import cn.szu.bot.sd.SdClient;

/**
 * LoRA 下载的暂停/取消：走**真的下载链路**（{@link CivitaiClient} 的流式拷贝 + 注入的假下载源）
 * 加上假 SD（本机 HttpServer），不连 civitai.com、不连 WebUI、不下载任何真模型。
 *
 * <p>下载源按 64 KB 一块、每块 40 ms 吐出 2 MiB 的有效 safetensors，所以"中途暂停/取消"有真实的
 * 时间窗可以打；SD 桩的 {@code /sdapi/v1/loras} 像真 WebUI 一样**按磁盘扫描**，取消后目录里没有文件，
 * 列表里自然就没有它。
 */
public final class LoraDownloadControlTest {
    /** 线上走镜像站（civitai.red，带登录 Cookie），测试照这个来：不假设一定是 civitai.com。 */
    private static final String HOST = "https://civitai.red";
    private static final String URL = HOST + "/models/10/control?modelVersionId=20";
    private static final String STEM = "Control_Test_Lora";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        cancelMidDownloadDeletesPartialFile();
        pauseFreezesProgressAndResumeFinishes();
        cancelWinsOverPause();
        endpointsAreIdempotentWithoutJob();
        legacyProgressFieldsAndBusyGuard();
        cancelDuringLoadPhaseSaysSoHonestly();
        qqCommandsExposeCancelPauseResume();
        System.out.println("LoraDownloadControlTest: " + assertions + " assertions passed:"
                + " 下载中途取消（半截文件已删/active=false/文案已取消/列表里没有它）、"
                + " 暂停冻结进度+resume 续完、暂停中 cancel 立刻生效、无任务时三接口幂等、"
                + " 并发第二个下载仍被拒 + /api/lora/progress 老字段仍在、"
                + " 加载阶段取消如实说「无法取消」、QQ 侧 .lora cancel/pause/resume 与 status 文案。");
    }

    /** 下载中途取消：半截文件删掉、active=false、文案"已取消"、LoRA 列表里没有它。 */
    private static void cancelMidDownloadDeletesPartialFile() throws Exception {
        try (Fixture f = new Fixture()) {
            JsonObject started = f.bot.webLoraDownload(URL, 1.0, "");
            check(Json.bool(started, "started", false), "cancel: download accepted");
            equal(true, Json.bool(started, "active", false), "cancel: started response reports active=true");
            equal(true, Json.bool(started, "cancellable", false), "cancel: started response reports cancellable=true");
            check(f.awaitBytes(8_000), "cancel: real transfer is running (metered, done > 0)");
            System.out.println("[evidence] 取消前 LoRA 目录=" + f.loraFiles()
                    + "，已下载=" + Json.num(f.bot.loraProgress(), "done", 0L) + " 字节 / "
                    + Json.num(f.bot.loraProgress(), "total", 0L) + " 字节");

            JsonObject cancelled = f.bot.webLoraCancel("");
            equal(false, Json.bool(cancelled, "active", true), "cancel: response active=false");
            equal(true, Json.bool(cancelled, "cancelled", false), "cancel: response cancelled=true");
            equal(false, Json.bool(cancelled, "paused", true), "cancel: response paused=false");
            check(Json.str(cancelled, "message", "").contains("已取消"),
                    "cancel: message says 已取消: " + Json.str(cancelled, "message", ""));
            check(Json.str(cancelled, "stage", "").contains("已取消"),
                    "cancel: stage says 已取消: " + Json.str(cancelled, "stage", ""));

            JsonObject after = f.bot.loraProgress();
            equal(false, Json.bool(after, "active", true), "cancel: progress active=false afterwards");
            equal(false, Json.bool(after, "cancellable", true), "cancel: progress cancellable=false afterwards");
            equal(false, Json.bool(after, "busy", true), "cancel: legacy busy=false afterwards");
            check(Json.str(after, "stage", "").contains("已取消"), "cancel: final stage stays 已取消");
            for (String key : List.of("busy", "downloading", "stage", "metered", "active", "cancellable", "paused"))
                check(after.has(key), "cancel: progress keeps field " + key);

            check(f.partFiles().isEmpty(), "cancel: no half .part file left in the LoRA directory: " + f.partFiles());
            check(f.loraFiles().isEmpty(), "cancel: LoRA directory holds no half model: " + f.loraFiles());
            System.out.println("[evidence] 取消后 LoRA 目录=" + f.loraFiles() + "，.part 文件=" + f.partFiles()
                    + "，目录=" + f.loraDir + "，LoRA 列表=" + f.client.loras());
            check(!Files.exists(f.target), "cancel: target file was never published");
            check(f.client.loras().stream().noneMatch(lora -> lora.name().equalsIgnoreCase(STEM)),
                    "cancel: cancelled download is not in the LoRA list: " + f.client.loras());
        }
    }

    /** 暂停后进度不再前进（~1s 对比 done 不变、paused=true），resume 后继续直到完成。 */
    private static void pauseFreezesProgressAndResumeFinishes() throws Exception {
        try (Fixture f = new Fixture()) {
            check(Json.bool(f.bot.webLoraDownload(URL, 1.0, ""), "started", false), "pause: download accepted");
            check(f.awaitBytes(8_000), "pause: real transfer is running (done > 0)");

            JsonObject paused = f.bot.webLoraPause("");
            equal(true, Json.bool(paused, "paused", false), "pause: response paused=true");
            equal(true, Json.bool(paused, "active", false), "pause: paused task is still active");
            check(Json.str(paused, "message", "").contains("暂停"),
                    "pause: message mentions 暂停: " + Json.str(paused, "message", ""));
            equal(true, Json.bool(f.bot.loraProgress(), "paused", true), "pause: /api/lora/progress paused=true");

            Thread.sleep(400);   // 让"已经读出来"的那一块落盘并上报完，再取基准
            long frozen = Json.num(f.bot.loraProgress(), "done", -1L);
            check(frozen > 0, "pause: some bytes were already transferred: " + frozen);
            check(Json.bool(f.bot.loraProgress(), "cancellable", false), "pause: paused download is still cancellable");
            Thread.sleep(1_000);
            JsonObject duringPause = f.bot.loraProgress();
            equal(frozen, Json.num(duringPause, "done", -1L), "pause: done does not advance over ~1s");
            System.out.println("[evidence] 暂停后 done=" + frozen + " → 1 秒后 done=" + Json.num(duringPause, "done", -1L)
                    + "，paused=" + Json.bool(duringPause, "paused", false));
            equal(true, Json.bool(duringPause, "paused", true), "pause: still paused after ~1s");
            equal(true, Json.bool(duringPause, "metered", false), "pause: still metered (progress card keeps its data)");

            JsonObject resumed = f.bot.webLoraResume("");
            equal(false, Json.bool(resumed, "paused", true), "resume: response paused=false");
            check(Json.str(resumed, "message", "").contains("继续"),
                    "resume: message mentions 继续: " + Json.str(resumed, "message", ""));
            check(f.awaitBytesBeyond(frozen, 6_000), "resume: progress moves again after resume");
            check(f.awaitIdle(25_000), "resume: download finishes (active=false)");
            check(Arrays.equals(f.bytes, Files.readAllBytes(f.target)), "resume: published file matches the source bytes exactly");
            check(f.partFiles().isEmpty(), "resume: no .part left after a completed download");
            equal(false, Json.bool(f.bot.loraProgress(), "active", true), "resume: progress active=false after finishing");
            equal(false, Json.bool(f.bot.loraProgress(), "cancellable", true), "resume: progress cancellable=false after finishing");
        }
    }

    /** 暂停状态下 cancel 立刻生效（不卡在暂停里）。 */
    private static void cancelWinsOverPause() throws Exception {
        try (Fixture f = new Fixture()) {
            check(Json.bool(f.bot.webLoraDownload(URL, 1.0, ""), "started", false), "pause+cancel: download accepted");
            check(f.awaitBytes(8_000), "pause+cancel: real transfer is running");
            f.bot.webLoraPause("");
            equal(true, Json.bool(f.bot.loraProgress(), "paused", true), "pause+cancel: download is paused");

            long started = System.nanoTime();
            JsonObject cancelled = f.bot.webLoraCancel("");
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            equal(true, Json.bool(cancelled, "cancelled", false), "pause+cancel: cancel takes effect while paused");
            equal(false, Json.bool(cancelled, "active", true), "pause+cancel: response active=false");
            equal(false, Json.bool(cancelled, "paused", true), "pause+cancel: response paused=false");
            check(Json.str(cancelled, "message", "").contains("已取消"),
                    "pause+cancel: message says 已取消: " + Json.str(cancelled, "message", ""));
            check(elapsed < 3_000, "pause+cancel: cancel returns promptly instead of hanging in the pause: " + elapsed + "ms");
            check(f.partFiles().isEmpty(), "pause+cancel: no half .part file left: " + f.partFiles());
            check(f.loraFiles().isEmpty(), "pause+cancel: LoRA directory holds no half model: " + f.loraFiles());
        }
    }

    /** 没有任务时三个接口都幂等且不抛错（控制台路由对这三条都回 200 + message）。 */
    private static void endpointsAreIdempotentWithoutJob() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String action : List.of("cancel", "pause", "resume")) {
                JsonObject result = switch (action) {
                    case "cancel" -> f.bot.webLoraCancel("");
                    case "pause" -> f.bot.webLoraPause("");
                    default -> f.bot.webLoraResume("");
                };
                equal(false, Json.bool(result, "active", true), action + " with no job: active=false (idempotent)");
                equal(false, Json.bool(result, "cancellable", true), action + " with no job: cancellable=false");
                equal(false, Json.bool(result, "paused", true), action + " with no job: paused=false");
                check(!Json.str(result, "message", "").isBlank(),
                        action + " with no job: gives a human message: " + Json.str(result, "message", ""));
                for (String key : List.of("busy", "downloading", "stage", "metered", "active", "cancellable", "paused"))
                    check(result.has(key), action + " with no job: response keeps field " + key);
            }
            // 再各来一遍：重复调用也不抛错（幂等，不是"第二次就报错"）。
            f.bot.webLoraCancel(""); f.bot.webLoraPause(""); f.bot.webLoraResume("");
            check(true, "idle control endpoints can be called repeatedly without errors");

            JsonObject idle = f.bot.loraProgress();
            equal(false, Json.bool(idle, "busy", true), "idle progress keeps legacy busy=false");
            equal(false, Json.bool(idle, "downloading", true), "idle progress keeps legacy downloading=false");
            equal(false, Json.bool(idle, "metered", true), "idle progress keeps legacy metered=false");
            check(idle.has("stage"), "idle progress keeps legacy stage field");
        }
    }

    /** 既有行为不变：并发第二个下载仍被拒（原文案），进度 JSON 的老字段仍在。 */
    private static void legacyProgressFieldsAndBusyGuard() throws Exception {
        try (Fixture f = new Fixture()) {
            check(Json.bool(f.bot.webLoraDownload(URL, 2.0, ""), "started", false), "busy guard: first download accepted");
            check(f.awaitBytes(8_000), "busy guard: first download is transferring");

            JsonObject second = f.bot.webLoraDownload(URL, 1.0, "");
            equal(false, Json.bool(second, "started", true), "busy guard: second concurrent download rejected");
            check(Json.str(second, "error", "").contains("已有 LoRA 下载或加载操作正在处理"),
                    "busy guard: unchanged rejection message: " + Json.str(second, "error", ""));

            JsonObject progress = f.bot.loraProgress();
            for (String key : List.of("busy", "downloading", "stage", "metered", "done", "total", "percent", "speed",
                    "active", "cancellable", "paused"))
                check(progress.has(key), "running progress exposes field " + key);
            equal(true, Json.bool(progress, "busy", false), "running progress busy=true");
            equal(true, Json.bool(progress, "downloading", false), "running progress downloading=true");
            equal(true, Json.bool(progress, "metered", false), "running progress metered=true");
            equal(true, Json.bool(progress, "active", false), "running progress active=true");
            equal(true, Json.bool(progress, "cancellable", false), "running progress cancellable=true");
            equal(false, Json.bool(progress, "paused", true), "running progress paused=false");
            check(progress.get("total").getAsLong() == f.bytes.length, "running progress total = source size");
            double percent = progress.get("percent").getAsDouble();
            check(percent > 0 && percent <= 100, "running progress percent within (0,100]: " + percent);

            equal(true, Json.bool(f.bot.webLoraCancel(""), "cancelled", false), "busy guard: cleanup cancel worked");
        }
    }

    /**
     * 取消发生在"下载完、正在加载/抓展示图"的阶段：界面要如实说"这个阶段取消不了"，
     * 而不是假装取消、更不能把已经完整下载并校验通过的模型删掉。
     */
    private static void cancelDuringLoadPhaseSaysSoHonestly() throws Exception {
        try (Fixture f = new Fixture()) {
            f.holdLoadPhase();
            check(Json.bool(f.bot.webLoraDownload(URL, 1.0, ""), "started", false), "load phase: download accepted");
            check(f.awaitBytes(8_000), "load phase: transfer is running");
            check(f.awaitLoadPhase(15_000), "load phase: download finished and the load/showcase stage is running");

            JsonObject progress = f.bot.loraProgress();
            equal(true, Json.bool(progress, "active", false), "load phase: task is still active");
            equal(false, Json.bool(progress, "cancellable", true), "load phase: not cancellable anymore");
            equal(false, Json.bool(progress, "paused", true), "load phase: not paused");

            JsonObject cancelled = f.bot.webLoraCancel("");
            equal(false, Json.bool(cancelled, "cancelled", true), "load phase: cancel did not take effect");
            equal(true, Json.bool(cancelled, "active", false), "load phase: response still active=true");
            check(Json.str(cancelled, "message", "").contains("无法取消"),
                    "load phase: message says it cannot be cancelled: " + Json.str(cancelled, "message", ""));
            check(Files.exists(f.target), "load phase: the fully downloaded model file is kept");
            check(f.partFiles().isEmpty(), "load phase: no .part file");

            f.releaseLoadPhase();
            check(f.awaitIdle(20_000), "load phase: task finishes after the load gate opens");
            check(Arrays.equals(f.bytes, Files.readAllBytes(f.target)), "load phase: kept file is the exact source bytes");
        }
    }

    /** QQ 侧：.lora cancel/pause/resume 存在而且是幂等的；.lora status 把新命令列出来。 */
    private static void qqCommandsExposeCancelPauseResume() throws Exception {
        try (Fixture f = new Fixture()) {
            String status = f.command(".lora status");
            for (String command : List.of(".lora cancel", ".lora pause", ".lora resume"))
                check(status.contains(command), "QQ .lora status lists " + command + ": " + status);
            for (String action : List.of("cancel", "pause", "resume")) {
                String reply = f.command(".lora " + action);
                check(reply.contains("LoRA 下载："), "QQ .lora " + action + " answers: " + reply);
                check(reply.contains("没有") || reply.contains("无需"),
                        "QQ .lora " + action + " with no job is idempotent and explains itself: " + reply);
            }
            check(!f.bot.webStatus().toString().isEmpty(), "QQ control commands leave the bot healthy");        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
    private static byte[] tensor(int dataSize) {
        JsonObject header = new JsonObject();
        JsonObject metadata = new JsonObject(); metadata.addProperty("name", "control-test"); header.add("__metadata__", metadata);
        JsonObject weight = new JsonObject();
        weight.addProperty("dtype", "F32");
        JsonArray shape = new JsonArray(); shape.add(dataSize / 4); weight.add("shape", shape);
        JsonArray offsets = new JsonArray(); offsets.add(0); offsets.add(dataSize); weight.add("data_offsets", offsets);
        header.add("lora_unet.weight", weight);
        byte[] text = Json.GSON.toJson(header).getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + text.length + dataSize).order(ByteOrder.LITTLE_ENDIAN).putLong(text.length).put(text).put(new byte[dataSize]).array();
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** 假下载源：按块慢慢吐字节，好让"中途暂停/取消"有真实的窗口可以打。 */
    private static final class SlowStream extends InputStream {
        private final byte[] data;
        private final Fixture fixture;
        private int position;
        private volatile boolean closed;
        SlowStream(byte[] data, Fixture fixture) { this.data = data; this.fixture = fixture; }
        public int read() throws IOException {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count <= 0 ? -1 : single[0] & 0xFF;
        }
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (position >= data.length) return -1;
            if (closed) throw new IOException("stream closed");
            try { Thread.sleep(fixture.chunkDelayMillis); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            if (closed) throw new IOException("stream closed");
            int count = Math.min(Math.min(length, 64 * 1024), data.length - position);
            System.arraycopy(data, position, buffer, offset, count);
            position += count;
            return count;
        }
        public void close() { closed = true; }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root, loraDir, target;
        final byte[] bytes = tensor(2 * 1024 * 1024);          // 32 块 × 64 KB
        final HttpServer server;
        final ExecutorService executor;
        final SdClient client;
        final Bot bot;
        final JsonObject state = Json.parse("{\"positive\":\"initial +\",\"negative\":\"initial -\","
                + "\"source\":\"webui-live\",\"revision\":1,\"sampler_name\":\"Euler a\",\"styles\":[],\"width\":1664,\"height\":1216}");
        volatile int chunkDelayMillis = 40;
        /** QQ 回执收集（.lora status/cancel/pause/resume 的文字）。 */
        final BlockingQueue<JsonArray> replies = new LinkedBlockingQueue<>();
        final java.util.concurrent.atomic.AtomicInteger messageIds = new java.util.concurrent.atomic.AtomicInteger();
        /** 把"加载/抓展示图"阶段人为按住，好断言这个阶段取消不了。 */
        volatile CountDownLatch loadGate = new CountDownLatch(0);

        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "lora-download-control-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            loraDir = Files.createDirectories(root.resolve("models/Lora"));
            target = loraDir.resolve(STEM + ".safetensors");

            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(task -> { Thread thread = new Thread(task, "lora-control-sd-stub"); thread.setDaemon(true); return thread; });
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();

            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sd.addProperty("timeout_seconds", 5);
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            config.addProperty("gen_auto_get", false);
            JsonObject civitai = new JsonObject();
            civitai.addProperty("admin_only", false);
            // 故意**不给** config.json 配 civitai.lora_dir：Bot 自己的 Civitai 客户端就构造不出来
            // （civitaiClientOrNull() = null），加载阶段绝不会去连真 Civitai；下载用的是下面注入 transport 的实例。
            config.add("civitai", civitai);
            Json.atomicWrite(root.resolve("config.json"), config);

            client = new SdClient(root, sd);
            Bot.Sender sender = (event, segments) -> { replies.add(segments.deepCopy()); return CompletableFuture.completedFuture(null); };
            bot = new Bot(new Settings(root), client, sender, new Bot.LoraDownloader() {
                public CivitaiClient.DownloadedLora download(String url, java.util.function.Consumer<String> stage,
                                                             CivitaiClient.Progress meter) throws Exception {
                    return civitai().download(url, stage, meter);
                }
                @Override public CivitaiClient.DownloadedLora download(String url, java.util.function.Consumer<String> stage,
                                                                       CivitaiClient.Progress meter, DownloadControl control) throws Exception {
                    return civitai().download(url, stage, meter, control);
                }
            });
        }

        /** 下载用的 Civitai 客户端：元数据与文件全部由注入的 transport 提供，DNS 也是假的。 */
        CivitaiClient civitai() throws IOException {
            JsonObject config = new JsonObject();
            config.addProperty("lora_dir", loraDir.toString());
            config.addProperty("max_download_mb", 8);
            config.addProperty("timeout_seconds", 60);
            config.addProperty("base_url", HOST);
            config.addProperty("session_cookie", "test-session");   // 有线上的登录态才会用镜像站
            return new CivitaiClient(root, config, this::transport,
                    host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")}, "test-token");
        }

        CivitaiClient.Response transport(URI uri, Map<String, String> headers, Duration timeout) throws Exception {
            String path = uri.getPath();
            if (path.equals("/api/v1/model-versions/20")) return json(version());
            if (path.equals("/api/v1/models/10")) return json(model());
            if (path.startsWith("/api/download/models/20")) {
                Map<String, List<String>> responseHeaders = new HashMap<>();
                responseHeaders.put("Content-Type", List.of("application/octet-stream"));
                responseHeaders.put("Content-Length", List.of(Integer.toString(bytes.length)));
                return new CivitaiClient.Response(200, responseHeaders, new SlowStream(bytes, this));
            }
            return json(new JsonObject());
        }
        private CivitaiClient.Response json(JsonObject body) {
            byte[] text = body.toString().getBytes(StandardCharsets.UTF_8);
            return new CivitaiClient.Response(200, Map.of("Content-Type", List.of("application/json")), new ByteArrayInputStream(text));
        }
        private JsonObject model() {
            JsonObject model = new JsonObject();
            model.addProperty("id", 10); model.addProperty("name", "控制测试 LoRA"); model.addProperty("type", "LORA");
            return model;
        }
        private JsonObject version() throws Exception {
            JsonObject version = new JsonObject();
            version.addProperty("id", 20); version.addProperty("modelId", 10);
            version.addProperty("name", "v1"); version.addProperty("baseModel", "SD 1.5");
            version.add("trainedWords", Json.GSON.toJsonTree(List.of("control trigger")));
            JsonObject parent = new JsonObject(); parent.addProperty("type", "LORA"); parent.addProperty("name", "控制测试 LoRA");
            version.add("model", parent);
            JsonObject file = new JsonObject();
            file.addProperty("id", 30); file.addProperty("name", STEM + ".safetensors"); file.addProperty("type", "Model");
            file.addProperty("primary", true); file.addProperty("sizeKB", bytes.length / 1024.0);
            file.addProperty("downloadUrl", HOST + "/api/download/models/20");
            JsonObject metadata = new JsonObject();
            metadata.addProperty("format", "SafeTensor"); metadata.addProperty("fp", "fp16"); metadata.addProperty("size", "full");
            file.add("metadata", metadata);
            JsonObject hashes = new JsonObject(); hashes.addProperty("SHA256", sha256(bytes)); file.add("hashes", hashes);
            JsonArray files = new JsonArray(); files.add(file); version.add("files", files);
            return version;
        }

        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/sdapi/v1/refresh-loras")) { awaitLoadGate(); send(exchange, 200, "null"); }
                else if (path.equals("/sdapi/v1/loras")) { awaitLoadGate(); send(exchange, 200, catalog().toString()); }
                else if (path.equals("/sdapi/v1/options")) send(exchange, 200, "{}");
                else if (path.equals("/pixiko-bridge/v1/prompts")) send(exchange, 200, state.toString());
                else if (path.equals("/sdapi/v1/prompt-styles")) send(exchange, 200, "[]");
                else if (path.equals("/pixiko-bridge/v1/styles")) {
                    exchange.getRequestBody().readAllBytes();
                    send(exchange, 200, "{\"name\":\"stub\",\"overwritten\":false}");
                } else send(exchange, 404, "{}");
            } finally { exchange.close(); }
        }
        private void awaitLoadGate() {
            try { loadGate.await(15, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        /** 把加载阶段按住（refresh-loras / loras 都停住）。 */
        void holdLoadPhase() { loadGate = new CountDownLatch(1); }
        void releaseLoadPhase() { loadGate.countDown(); }
        /** 等"下载完、正在加载/抓展示图"：stage 已经是"文件已保存…"。 */
        boolean awaitLoadPhase(long millis) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (System.nanoTime() < deadline) {
                JsonObject progress = bot.loraProgress();
                if (Json.bool(progress, "active", false) && !Json.bool(progress, "cancellable", true)
                        && Json.str(progress, "stage", "").contains("文件已保存")) return true;
                Thread.sleep(20);
            }
            return false;
        }
        /** 喂一条 QQ 私聊指令（.lora status / cancel / pause / resume），返回回执文字。 */
        String command(String text) throws Exception {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message"); event.addProperty("message_type", "private");
            event.addProperty("user_id", 456); event.addProperty("self_id", 777);
            event.addProperty("message_id", messageIds.incrementAndGet());
            event.add("message", Maps.text(text));
            bot.accept(event);
            JsonArray reply = replies.poll(5, TimeUnit.SECONDS);
            check(reply != null, "expected a QQ reply for " + text);
            return Bot.messageText(reply);
        }
        /** 真 WebUI 的 /loras 也是"扫磁盘"：目录里没有文件，列表里就没有它。 */
        JsonArray catalog() {
            JsonArray list = new JsonArray();
            try (var files = Files.list(loraDir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".safetensors")) continue;
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", name.substring(0, name.length() - ".safetensors".length()));
                    entry.addProperty("alias", "");
                    entry.addProperty("path", file.toAbsolutePath().toString());
                    list.add(entry);
                }
            } catch (IOException ignored) { /* 目录读不到就当空列表 */ }
            return list;
        }
        private static void send(HttpExchange exchange, int status, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        List<String> loraFiles() throws IOException {
            try (var files = Files.list(loraDir)) {
                return files.map(file -> file.getFileName().toString()).sorted().toList();
            }
        }
        List<String> partFiles() throws IOException {
            try (var files = Files.list(loraDir)) {
                return files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".part")).sorted().toList();
            }
        }
        /** 等真下载跑起来：metered 且已经有字节落地。 */
        boolean awaitBytes(long millis) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (System.nanoTime() < deadline) {
                JsonObject progress = bot.loraProgress();
                if (Json.bool(progress, "metered", false) && Json.num(progress, "done", 0L) > 0) return true;
                Thread.sleep(20);
            }
            return false;
        }
        boolean awaitBytesBeyond(long mark, long millis) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (System.nanoTime() < deadline) {
                if (Json.num(bot.loraProgress(), "done", 0L) > mark) return true;
                Thread.sleep(20);
            }
            return false;
        }
        boolean awaitIdle(long millis) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (System.nanoTime() < deadline) {
                if (!Json.bool(bot.loraProgress(), "active", true)) return true;
                Thread.sleep(20);
            }
            return false;
        }
        public void close() {
            releaseLoadPhase();
            try { bot.webLoraCancel(""); } catch (Exception ignored) { /* 收尾就够 */ }
            bot.close();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}

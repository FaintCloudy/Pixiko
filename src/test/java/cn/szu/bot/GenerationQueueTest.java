package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.sd.ImageOutbox;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;

/** Real Bot/SdClient/outbox integration using only isolated local files and an ephemeral SD stub. */
public final class GenerationQueueTest {
    private static int assertions;
    private static final AtomicInteger IDS = new AtomicInteger();
    private record Text(JsonObject event, String text) {}
    private record Image(JsonObject event, JsonArray segments, CompletableFuture<Void> ack) {}
    private record Call(JsonObject payload, CompletableFuture<Integer> finish) {}

    public static void main(String[] args) throws Exception {
        historyDelivery();
        splitDelivery();
        autoDelivery();
        queueControls();
        atomicFifoQueueWithCapturedSettings();
        claimSnapshotAndAcknowledgement();
        failedSendAndRestart();
        failedDiskAcknowledgementStopsDelivery();
        largeOutboxIsStreamedPerImage();
        countsAndShutdown();
        System.out.println("GenerationQueueTest: " + assertions + " assertions passed: atomic FIFO, snapshots, single GPU, failure continuation, persistent ACK drain, exclusive claims, large batches, shutdown.");
    }

    private static void historyDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Path> paths = f.seed(5, 0);
            for (int i=0;i<paths.size();i++) Files.setLastModifiedTime(paths.get(i), java.nio.file.attribute.FileTime.fromMillis(1000L*(i+1)));
            equal(List.of(paths.get(4),paths.get(3),paths.get(2)), f.client.recentImages(3), "newest files selected");
            f.client.acknowledgeImages(paths); equal(0,f.client.pendingImages().size(),"history already claimed");
            f.command("private", ".imgcnt 2"); f.bot.accept(event("private", ".rg 3"));
            Image first=f.image(); equal(2,first.segments().size(),"history first chunk"); first.ack().complete(null);
            Image second=f.image(); equal(1,second.segments().size(),"history last chunk"); second.ack().complete(null);
            check(f.text().text().contains("回溯完成，共 3 张"),"history completion");
            equal(0,f.client.pendingImages().size(),"history never requeues acknowledged images");
            new ImageOutbox(f.root).append(paths); // SdClient cached outbox intentionally checked through a fresh client.
            SdClient fresh=f.restarted(); equal(5,fresh.pendingImages().size(),"pending history retained");
            equal(5,fresh.recentImages(100).size(),"larger count returns available history");
            check(f.command("private", ".rg 0").contains("正整数"),"invalid history count");
        }
    }
    private static void splitDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            check(f.command("group", ".imgcnt 2").contains("2 张"), "set image limit");
            equal(2, new Settings(f.root).imageCount(), "limit persists");
            List<Path> paths = f.seed(5, 0); f.bot.accept(event("group", ".get"));
            Image first = f.image(); equal(2, first.segments().size(), "first chunk capped"); first.ack().complete(null);
            Image second = f.image(); equal(2, second.segments().size(), "second chunk capped");
            equal(3, f.client.pendingImages().size(), "ACK removes only successful chunk");
            second.ack().completeExceptionally(new IOException("chunk failure"));
            check(f.text().text().contains("已确认领取 2 张"), "failure reports partial success");
            equal(paths.subList(2,5), f.client.pendingImages(), "remaining chunks persist");
            f.bot.accept(event("group", ".get")); Image retry=f.image(); retry.ack().complete(null);
            Image last=f.image(); equal(1,last.segments().size(),"last partial chunk"); last.ack().complete(null); f.text();
            equal(0,f.client.pendingImages().size(),"retry drains remaining");
        }
    }
    private static void autoDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            check(f.command("group", ".gen toggle").contains("开启"), "toggle on");
            check(new Settings(f.root).autoGet(), "toggle persists");
            f.holdGenerations = true; f.edit("auto", 512, "Euler a", List.of());
            f.command("group", ".gen 2"); Call first = f.call();
            f.command("private", ".gen");
            first.finish().complete(200); Call second = f.call();
            check(f.texts.isEmpty(), "no per-image success message");
            check(f.command("group", ".gen status").contains("1/2"), "task progress visible");
            check(f.images.isEmpty(), "no early delivery while queue busy");
            f.bot.accept(event("group", ".get")); Image preview = f.image();
            equal(1, preview.segments().size(), "preview partial task");
            second.finish().complete(200); f.text(); Call third = f.call();
            preview.ack().complete(null); check(f.text().text().contains("仅预览"), "preview completion");
            equal(2, f.client.pendingImages().size(), "preview preserves images even when task finishes during upload");
            Image group = f.image(); equal("group", Json.str(group.event(), "message_type", ""), "group destination");
            equal(2, group.segments().size(), "one gen command per card");
            equal(2, f.client.pendingImages().size(), "first task sent while second still running");
            equal(1, Bot.imageBatches(f.restarted().pendingImages(), 300).size(), "command grouping survives restart");
            equal(2, Bot.imageBatches(f.restarted().pendingImages(), 1).size(), "command images split by limit");
            group.ack().complete(null); f.text();
            third.finish().complete(200); f.text();
            Image personal = f.image(); equal("private", Json.str(personal.event(), "message_type", ""), "private destination");
            equal(1, personal.segments().size(), "private isolated");
            personal.ack().completeExceptionally(new IOException("simulated auto failure")); f.text();
            equal(1, f.client.pendingImages().size(), "failed automatic delivery retained");
            check(f.command("private", ".gen toggle").contains("关闭"), "toggle off");
            check(!new Settings(f.root).autoGet(), "off persists");
        }
    }

    /** 挂起 / 继续 / 置顶 / 取消：直接查队列状态（网页接口是同步快照，不受回执排队影响）。 */
    private static void queueControls() throws Exception {
        try (Fixture f = new Fixture()) {
            f.holdGenerations = true;
            f.edit("A", 512, "Euler a", List.of());
            f.command("private", ".gen 3");          // 任务 #1（3 次，挂起后还有剩的）
            Call first = f.call();
            f.command("private", ".gen 2");          // 任务 #2
            check(f.bot.webTasks().size() == 2, "队列里有两个任务：" + f.bot.webTasks());
            String held = f.bot.webTaskAction("hold", "1").get("message").getAsString();
            check(held.contains("已挂起"), "挂起正在生成的任务：" + held);
            JsonObject job1 = f.bot.webTasks().get(0).getAsJsonObject();
            check(job1.get("suspended").getAsBoolean() && job1.get("status").getAsString().contains("挂起"),
                    "挂起状态写进队列快照：" + job1);
            first.finish().complete(200);
            Call second = f.call();
            check(second != null, "挂起后队列继续跑下一个任务");
            String promoted = f.bot.webTaskAction("first", "2").get("message").getAsString();
            check(promoted.contains("#2"), "置顶有明确回执：" + promoted);
            String cancelled = f.bot.webTaskAction("cancel", "2").get("message").getAsString();
            check(cancelled.contains("已取消"), "取消正在生成的任务：" + cancelled);
            second.finish().complete(200);
            String resumed = f.bot.webTaskAction("resume", "1").get("message").getAsString();
            check(resumed.contains("已继续"), "继续挂起的任务：" + resumed);
            Call restarted = f.call();
            check(restarted != null, "继续后重新开始生成");
            restarted.finish().complete(200);
            Call restartedAgain = f.call();
            check(restartedAgain != null, "挂起的剩余次数继续跑完");
            restartedAgain.finish().complete(200);
            long deadline = System.currentTimeMillis() + 5000;
            while (!f.bot.webTasks().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
            check(f.bot.webTasks().isEmpty(), "队列最终排空：" + f.bot.webTasks());
            f.command("private", ".gen 2");
            Call pending = f.call();
            String all = f.bot.webTaskAction("cancel", "all").get("message").getAsString();
            check(all.contains("全部任务"), "取消整个队列：" + all);
            pending.finish().complete(200);
        }
    }

    private static void atomicFifoQueueWithCapturedSettings() throws Exception {        try (Fixture f = new Fixture()) {
            f.holdGenerations = true;
            f.edit("A", 512, "Euler a", List.of());
            check(f.command("group", ".gen 21").contains("共 21 次生成"), "single batch exceeds old per-command and queue caps");
            Call first = f.call();
            check(f.command("private", ".gen status").contains("运行中，等待 20 个"), "running count correct");
            f.edit("B", 768, "DPM++ 2M", List.of("样式 B"));
            check(f.command("private", ".gen 3").contains("共 3 次生成"), "queue accepts subsequent batch beyond twenty");
            equal(2, f.promptReads.get(), "one immutable snapshot per submission");
            f.edit("later", 256, "other", List.of("later"));
            for (int index = 0; index < 24; index++) {
                Call call = index == 0 ? first : f.call();
                String expected = index < 21 ? "A" : "B";
                equal(expected, call.payload().get("prompt").getAsString(), "FIFO prompt snapshot");
                equal(expected + " negative", call.payload().get("negative_prompt").getAsString(), "negative snapshot");
                equal(index < 21 ? 512 : 768, call.payload().get("width").getAsInt(), "dimension snapshot");
                equal(index < 21 ? "Euler a" : "DPM++ 2M", call.payload().get("sampler_name").getAsString(), "sampler snapshot");
                equal(index < 21 ? new JsonArray() : JsonParser.parseString("[\"样式 B\"]"), call.payload().get("styles"), "style snapshot");
                call.finish().complete(index == 2 ? 500 : 200);
                if (index == 20 || index == 23) {
                    Text notice = f.text(); check(notice.text().contains("已完成"), "one completion per command");
                    equal(index == 20 ? "group" : "private", notice.event().get("message_type").getAsString(), "original conversation retained");
                }

            }
            equal(1, f.peakApiCalls.get(), "one GPU request at a time");
            equal(23, f.client.pendingImages().size(), "failure does not discard later tasks");
            equal(f.client.pendingImages(), f.restarted().pendingImages(), "outbox survives restart");
            check(f.command("private", ".gen status").contains("空闲，等待 0 个"), "queue idle");
        }
    }

    private static void claimSnapshotAndAcknowledgement() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Path> seeded = f.seed(2, 0);
            f.bot.accept(event("group", ".get"));
            Image batch = f.image();
            equal("group", batch.event().get("message_type").getAsString(), "original group");
            equal(2, batch.segments().size(), "both images in one send");
            for (int i = 0; i < 2; i++) equal(seeded.get(i).toUri().toASCIIString(),
                    batch.segments().get(i).getAsJsonObject().getAsJsonObject("data").get("file").getAsString(), "local file reference");
            equal(seeded, f.client.pendingImages(), "no removal before whole batch ACK");
            check(f.command("private", ".get").contains("正在领取"), "exclusive claim");
            check(f.command("private", ".help").contains(".get"), "help responsive during upload");
            List<Path> newImages = f.client.generate(f.client.generationRequest());
            batch.ack().complete(null);
            check(f.text().text().contains("本次领取完成，共 2 张"), "single card completed");
            check(f.images.poll(100, TimeUnit.MILLISECONDS) == null, "no extra forward card");
            equal(newImages, f.restarted().pendingImages(), "new generations retained for next claim");
            f.bot.accept(event("private", ".get")); f.image().ack().complete(null);
            check(f.text().text().contains("共 1 张"), "next claim");
            equal(List.of(), f.restarted().pendingImages(), "whole-batch ACK persists");
            check(f.command("private", ".get").contains("暂无待领取"), "no repeated delivery");
        }
    }

    private static void failedSendAndRestart() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Path> seeded = f.seed(3, 0);
            f.bot.accept(event("private", ".get"));
            Image batch = f.image();
            equal(3, batch.segments().size(), "one request with all images");
            batch.ack().completeExceptionally(new IOException("mock send denied"));
            String failure = f.text().text();
            check(failure.contains("已确认领取 0 张") && failure.contains("mock send denied"), "failed batch report");
            check(f.images.poll(100, TimeUnit.MILLISECONDS) == null, "no automatic duplicate send");
            equal(seeded, f.restarted().pendingImages(), "entire unconfirmed batch retained");
            f.bot.close(); f.bot = f.newBot(f.restarted());
            f.bot.accept(event("group", ".get")); f.image().ack().complete(null);
            check(f.text().text().contains("共 3 张"), "retry all retained images");
            equal(List.of(), f.restarted().pendingImages(), "retry ACK persists");
        }
    }

    private static void failedDiskAcknowledgementStopsDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Path> seeded = f.seed(2, 0);
            f.bot.accept(event("private", ".get"));
            Image first = f.image();
            Path outbox = f.root.resolve("data/sd-outbox.json"), backup = f.root.resolve("data/outbox-test-backup.json");
            Files.move(outbox, backup);
            Files.createDirectory(outbox);
            Path obstruction = outbox.resolve("hold"); Files.writeString(obstruction, "isolated test");
            try {
                first.ack().complete(null);
                String failure = f.text().text();
                check(failure.contains("图片已发送并收到确认") && failure.contains("领取记录保存失败") && failure.contains("可能重发"), "disk ACK failure clearly explains confirmed send and retry risk");
                check(f.images.poll(100, TimeUnit.MILLISECONDS) == null, "disk failure stops later sends");
                equal(seeded, f.client.pendingImages(), "failed disk ACK preserves in-memory queue");
            } finally {
                Files.delete(obstruction); Files.delete(outbox); Files.move(backup, outbox);
            }
            equal(seeded, f.restarted().pendingImages(), "original persisted queue is intact after failed ACK");
        }
    }

    private static void largeOutboxIsStreamedPerImage() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Path> seeded = f.seed(18, 6L * 1024 * 1024);
            f.bot.accept(event("private", ".get"));
            Image batch = f.image();
            equal(18, batch.segments().size(), "all eighteen images in one batch");
            check(batch.segments().toString().length() < 10000, "over 100MB of images use small local-reference JSON");
            check(f.images.isEmpty(), "one pending forward card only");
            batch.ack().complete(null);
            check(f.text().text().contains("共 18 张"), "large batch complete");
            equal(List.of(), f.restarted().pendingImages(), "large batch ACK persisted");
        }
        try (Fixture f = new Fixture()) {
            List<Path> seeded = f.seed(2, 0); Files.delete(seeded.get(1));
            check(f.command("private", ".get").contains("不存在"), "missing file blocks the entire card");
            check(f.images.isEmpty(), "no partial send");
            equal(seeded, f.restarted().pendingImages(), "missing file retained for repair");
        }
    }

    private static void countsAndShutdown() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String command : List.of(".gen 0", ".gen -1", ".gen 1.5", ".gen NaN", ".gen 1 2")) {
                String response = f.command("private", command);
                check(response.contains("次数") || response.contains("指令格式不正确"), "bad generation count rejected: " + command);
            }
            equal(0, f.promptReads.get(), "invalid counts never capture prompts or create jobs");
            f.holdGenerations = true;
            check(f.command("private", ".gen 1000000000000000000000000000000").contains("已加入生成队列"), "arbitrary positive count represented lazily");
            Call current = f.call();
            check(f.command("private", ".gen status").contains("等待 999999999999999999999999999999 个"), "no integer overflow or eagerly allocated task objects");
            f.bot.close();
            current.finish().complete(500);
            check(f.text().text().contains("生成失败"), "running request cancellation reported");
            check(f.command("private", ".gen status").contains("等待 0 个"), "close discards unstarted jobs");
            check(f.command("private", ".gen").contains("正在关闭"), "closed bot rejects new work clearly");
            check(f.calls.poll(100, TimeUnit.MILLISECONDS) == null, "queued jobs never start after close");
        }
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }
    private static byte[] png(int color) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB); image.setRGB(0, 0, color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
    }
    private static JsonObject event(String type, String command) {
        JsonObject event = new JsonObject(); event.addProperty("post_type", "message"); event.addProperty("message_type", type);
        event.addProperty("self_id", 777); event.addProperty("user_id", 456); event.addProperty("message_id", IDS.incrementAndGet());
        if (type.equals("group")) event.addProperty("group_id", 999);
        event.add("message", Maps.text(command)); return event;
    }
    private static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
        final ExecutorService executor;
        final SdClient client;
        final JsonObject sdConfig = new JsonObject();
        final JsonObject state = Json.parse("{\"positive\":\"initial\",\"negative\":\"negative\",\"source\":\"webui-live\",\"revision\":1,\"sampler_name\":\"Euler a\",\"styles\":[],\"width\":512,\"height\":512,\"settings_initialized\":true}");
        final BlockingQueue<Text> texts = new LinkedBlockingQueue<>();
        final BlockingQueue<Image> images = new LinkedBlockingQueue<>();
        final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
        final List<CompletableFuture<?>> outstanding = new CopyOnWriteArrayList<>();
        final AtomicInteger promptReads = new AtomicInteger(), activeApiCalls = new AtomicInteger(), peakApiCalls = new AtomicInteger();
        final String image;
        Bot bot;
        volatile boolean holdGenerations;
        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "generation-queue-tests").toAbsolutePath();
            Files.createDirectories(work); root = Files.createTempDirectory(work, "case-");
            image = Base64.getEncoder().encodeToString(png(0x336699));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "queue-sd-mock"); t.setDaemon(true); return t; });
            server.setExecutor(executor); server.createContext("/", this::handle); server.start();
            sdConfig.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort()); sdConfig.addProperty("timeout_seconds", 10);
            JsonObject cfg = new JsonObject(); cfg.add("sd", sdConfig); cfg.addProperty("gen_auto_get", false); Json.atomicWrite(root.resolve("config.json"), cfg);
            client = new SdClient(root, sdConfig);
            // Prompts are personal now; these queue tests exercise generation, so give the callers a copy.
            UserPromptStore prompts = new UserPromptStore(root);
            for (String scope : List.of("456", Settings.DEFAULT_OWNER))
                prompts.seed(scope, new SdClient.Prompts("queue test prompt", "queue test negative", UserPromptStore.PERSONAL_SOURCE));
            bot = newBot(client);
            // The startup seed reads the page once; the assertions below count only per-submission reads.
            promptReads.set(0);
        }
        Bot newBot(SdClient sd) throws IOException {
            return new Bot(new Settings(root), sd, (event, segments) -> {
                JsonObject segment = segments.get(0).getAsJsonObject();
                if (segment.get("type").getAsString().equals("image")) {
                    CompletableFuture<Void> ack = new CompletableFuture<>(); outstanding.add(ack);
                    images.add(new Image(event.deepCopy(), segments.deepCopy(), ack)); return ack;
                }
                texts.add(new Text(event.deepCopy(), Bot.messageText(segments))); return CompletableFuture.completedFuture(null);
            });
        }
        SdClient restarted() throws IOException { return new SdClient(root, sdConfig); }
        synchronized void edit(String prompt, int width, String sampler, List<String> styles) throws IOException {
            state.addProperty("positive", prompt); state.addProperty("negative", prompt + " negative"); state.addProperty("width", width);
            state.addProperty("sampler_name", sampler); state.add("styles", Json.GSON.toJsonTree(styles)); state.addProperty("revision", state.get("revision").getAsInt() + 1);
            // Prompts are personal now: these queue tests drive generation, so keep the callers' copies in step.
            UserPromptStore prompts = new UserPromptStore(root);
            for (String scope : List.of("456", Settings.DEFAULT_OWNER))
                prompts.replace(scope, new SdClient.Prompts(prompt, prompt + " negative", UserPromptStore.PERSONAL_SOURCE));
        }
        List<Path> seed(int count, long length) throws Exception {
            List<Path> paths = new ArrayList<>(); Path directory = root.resolve("data/generated/seed"); Files.createDirectories(directory);
            for (int index = 0; index < count; index++) {
                Path path = directory.resolve(String.format(Locale.ROOT, "%02d.png", index)); Files.write(path, png(0x111111 * (index + 1)));
                if (length > 0) try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) { file.setLength(length); }
                paths.add(path);
            }
            new ImageOutbox(root).append(paths); return List.copyOf(paths);
        }
        String command(String type, String command) throws Exception {
            bot.accept(event(type, command)); Text text = text();
            equal(type, text.event().get("message_type").getAsString(), "text retains conversation type: " + command); return text.text();
        }
        Text text() throws Exception { Text text = texts.poll(7, TimeUnit.SECONDS); check(text != null, "expected text response"); return text; }
        Image image() throws Exception { Image image = images.poll(7, TimeUnit.SECONDS); check(image != null, "expected image dispatch"); return image; }
        Call call() throws Exception { Call call = calls.poll(7, TimeUnit.SECONDS); check(call != null, "expected SD request"); return call; }
        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/pixiko-bridge/v1/prompts")) {
                    promptReads.incrementAndGet();
                    synchronized (this) { send(exchange, 200, state.toString()); }
                } else if (path.equals("/sdapi/v1/txt2img")) {
                    int active = activeApiCalls.incrementAndGet(); peakApiCalls.accumulateAndGet(active, Math::max);
                    try {
                        JsonObject payload = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        CompletableFuture<Integer> finish = new CompletableFuture<>(); outstanding.add(finish); calls.add(new Call(payload, finish));
                        int status = 200;
                        if (holdGenerations) {
                            try { status = finish.get(10, TimeUnit.SECONDS); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); status = 500; }
                            catch (ExecutionException | TimeoutException e) { status = 500; }
                        }
                        send(exchange, status, status == 200 ? "{\"images\":[\"" + image + "\"]}" : "{\"detail\":\"mock generation failure\"}");
                    } finally { activeApiCalls.decrementAndGet(); }
                } else send(exchange, 404, "{}");
            } finally { exchange.close(); }
        }
        static void send(HttpExchange exchange, int status, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }
        public void close() {
            bot.close(); outstanding.forEach(future -> future.cancel(true)); server.stop(0); executor.shutdownNow();
        }
    }
}

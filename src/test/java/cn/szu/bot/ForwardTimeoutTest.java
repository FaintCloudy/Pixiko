package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.qq.DeliveryUnknownException;
import cn.szu.bot.qq.QqClient;
import cn.szu.bot.sd.ImageOutbox;
import cn.szu.bot.sd.SdClient;

/**
 * 「带图的合并转发」超时预算 + 发送结果不确定时**绝不重发**（同一批结果不许出现两遍）。
 *
 * <p>线上事故（bot-stdout.log 2026-10-05 07:44:36 → 07:45:06）：一次 /lora query 把 10 条结果
 * （每条都带一张 Civitai 远程封面）合成一条合并转发，而 {@code sendRecord} 用的是普通 API 超时
 * （配置里 {@code timeout_seconds = 30}），NapCat 下载并上传 10 张图要 30 秒以上 → 超时 →
 * 调用方把失败当成"没发出去"→ 又逐条发了 10 条 → 用户看到同一批结果两遍 + 一条失败通知。
 *
 * <p>这个测试钉住两件事：
 * <ol>
 *   <li><b>该等的时候愿意等</b>：带图的合并转发用图片上传预算（{@code image_api_timeout_seconds}），
 *       普通短请求继续用 {@code timeout_seconds}，两者不许被一起调大。</li>
 *   <li><b>不确定就不重发</b>：传输层报"超时/结果未知"时，逐条重发次数必须是 0，
 *       只发一条说明「可能已发出，未自动重发」；只有**确定被拒**（retcode）才逐条兜底，且一条不丢、顺序不变。</li>
 * </ol>
 *
 * <p>全程只用假 Sender（记录型 + 可编程失败）与假传输层，不发任何 QQ 消息、不下载模型、不碰真实 data。
 */
public final class ForwardTimeoutTest {
    private static int assertions;
    /** 超时预算函数的哨兵：证明带图那一档确实落在图片预算上，而不是普通档。 */
    private static final int SHORT = 30;
    private static final int LONG_IMAGE_BUDGET = 600;
    private static final int COUNT = 3;
    private static final AtomicInteger IDS = new AtomicInteger(1);

    public static void main(String[] args) throws Exception {
        imageForwardUsesImageBudget();
        ordinaryRequestsKeepTheirOwnTimeout();
        imageDetectionCoversRecordNodes();
        unknownDeliveryNeverResendsSearchResults();
        definiteFailureStillFallsBackOneByOne();
        unknownDeliveryNeverResendsImageAlbum();
        definiteFailureStillFallsBackForImageAlbum();
        noticeIsHonestAndSingle();
        check(assertions >= 60, "断言条数应 ≥ 60，实际 " + assertions);
        System.out.println("ForwardTimeoutTest: " + assertions + " assertions passed: 带图合并转发的超时预算"
                + "（图片预算 ≥ image_api_timeout_seconds、普通请求仍用 timeout_seconds、节点里的图片也算）、"
                + "结果不确定时逐条重发 = 0 且只发一条如实的「可能已发出，未自动重发」、"
                + "确定被拒时逐条兜底一条不丢顺序不变（搜索结果与图片领取两条路）。");
    }

    // ---------------------------------------------------------------- ①②③ 超时预算

    /** ① 带图的合并转发用图片预算：= max(普通超时, 图片超时)，绝不再用 30 秒那种普通档。 */
    private static void imageForwardUsesImageBudget() {
        equal(LONG_IMAGE_BUDGET, QqClient.deliveryTimeoutSeconds(1, SHORT, LONG_IMAGE_BUDGET),
                "1 张图的合并转发要拿满图片预算");
        equal(LONG_IMAGE_BUDGET, QqClient.deliveryTimeoutSeconds(10, SHORT, LONG_IMAGE_BUDGET),
                "10 张（线上那次）也要拿满图片预算");
        equal(LONG_IMAGE_BUDGET, QqClient.deliveryTimeoutSeconds(50, SHORT, LONG_IMAGE_BUDGET),
                "50 张也不许回落到普通档");
        check(QqClient.deliveryTimeoutSeconds(10, SHORT, LONG_IMAGE_BUDGET) >= LONG_IMAGE_BUDGET,
                "带图合并转发的超时必须 ≥ image_api_timeout_seconds");
        check(QqClient.deliveryTimeoutSeconds(10, SHORT, LONG_IMAGE_BUDGET) > SHORT,
                "带图合并转发的超时必须严格大于普通请求超时（否则又是一次 30 秒超时）");
        equal(7200, QqClient.deliveryTimeoutSeconds(10, SHORT, 7200), "图片预算拉满到配置上限也照用");
        equal(SHORT, QqClient.deliveryTimeoutSeconds(0, SHORT, LONG_IMAGE_BUDGET), "没有图片就不借用图片预算");
        equal(10, QqClient.deliveryTimeoutSeconds(0, 10, 10), "普通档本来就等于图片档时照旧");
        equal(601, QqClient.deliveryTimeoutSeconds(3, 601, 600),
                "普通超时比图片预算还大时，至少取两者较大者（不会把已有的等待变短）");
        check(QqClient.deliveryTimeoutSeconds(1, 1, 600) >= 600, "图片预算下界就是 image_api_timeout_seconds");
    }

    /** ② 普通短请求仍旧用 timeout_seconds：不许因为改了带图路径就被一起调大。 */
    private static void ordinaryRequestsKeepTheirOwnTimeout() {
        for (int shortTimeout : new int[]{1, 5, 30, 300}) {
            equal(shortTimeout, QqClient.deliveryTimeoutSeconds(0, shortTimeout, LONG_IMAGE_BUDGET),
                    "普通请求（无图）继续用 timeout_seconds=" + shortTimeout + "，不受图片超时影响");
        }
        check(!QqClient.eventHasImage(Maps.text("普通短回复")), "纯文本消息不算带图");
        check(!QqClient.eventHasImage(new JsonArray()), "空消息不算带图");
        check(QqClient.eventHasImage(segmentsOf(image())), "单张图片算带图（沿用既有行为）");
        JsonArray album = new JsonArray();
        for (int index = 0; index < 5; index++) album.add(image().deepCopy());
        check(QqClient.eventHasImage(album), "多张图集算带图");
    }

    /** ③ 图片检测必须看得到合并转发节点里的图片（sendRecord 的形态与 send 不同）。 */
    private static void imageDetectionCoversRecordNodes() {
        // sendRecord 的入参是"每条消息的内容"，节点外壳由传输层自己套（内容的数组 = 节点的内容）。
        List<JsonArray> record = new ArrayList<>();
        record.add(Maps.text("第一条"));
        record.add(Maps.text("第二条"));
        record.add(segmentsOf(image()));
        JsonArray payload = forwardNodes(record);
        check(QqClient.eventHasImage(payload), "合并转发里有一个带图节点就算带图");
        List<JsonArray> textOnly = new ArrayList<>();
        textOnly.add(Maps.text("第一条"));
        textOnly.add(Maps.text("第二条"));
        check(!QqClient.eventHasImage(forwardNodes(textOnly)), "全是纯文本节点的合并转发不算带图（继续用普通超时）");
        JsonArray plain = new JsonArray();
        plain.add(JsonParser.parseString("\"not an object\""));
        plain.add(JsonParser.parseString("42"));
        check(!QqClient.eventHasImage(plain), "坏形状的段（不是对象）不能炸也不能算带图");
        check(!QqClient.eventHasImage(null), "null 消息不算带图");
        check(!QqClient.eventHasImage(JsonNull.INSTANCE), "JsonNull 不算带图");
        JsonObject marker = new JsonObject();
        marker.addProperty("type", "image");
        JsonArray markerOnly = new JsonArray(); markerOnly.add(marker);
        check(QqClient.eventHasImage(markerOnly), "只有 type 也认（线上 NapCat 的段就带 data.file）");
        List<JsonArray> empty = new ArrayList<>();
        check(!QqClient.eventHasImage(forwardNodes(empty)), "空节点列表不算带图");
    }

    /** 复刻 sendRecord 套节点的方式（测试里只关心节点里有没有图）。 */
    private static JsonArray forwardNodes(List<JsonArray> messages) {
        JsonArray nodes = new JsonArray();
        for (JsonArray content : messages) {
            JsonObject data = new JsonObject();
            data.addProperty("uin", 1);
            data.addProperty("name", "测试小鸟");
            data.add("content", content.deepCopy());
            JsonObject node = new JsonObject();
            node.addProperty("type", "node");
            node.add("data", data);
            nodes.add(node);
        }
        return nodes;
    }

    // ---------------------------------------------------------------- ④⑤ 搜索结果

    /** ④ 搜索结果的合并转发「超时/结果未知」：逐条重发 = 0，只发一条如实的说明。 */
    private static void unknownDeliveryNeverResendsSearchResults() throws Exception {
        RecordingSender sender = new RecordingSender();
        sender.recordFailure = DeliveryUnknownException.timeout();
        try (Fixture fixture = new Fixture(sender)) {
            CivitaiClient.SearchPage page = page();
            String log = captureError(() -> fixture.bot.sendLoraSearchResults(fixture.event, page));
            equal(1, sender.records.get(), "合并转发只尝试一次");
            equal(0, sender.resultSends(), "结果不确定时逐条重发次数必须是 0（核心诉求：同一批不许出现两遍）");
            equal(COUNT, sender.nodes.get(0).size(), "那一次合并转发确实带了 " + COUNT + " 个节点");
            String notice = sender.noticeText();
            check(notice.contains("合并转发失败") && notice.contains("可能已发出") && notice.contains("未自动重发"),
                    "说明要写明「可能已发出，未自动重发」：" + notice);
            check(notice.contains(".lora query"), "还要告诉用户怎么再看一次：" + notice);
            equal(1, sender.notices().size(), "只发一条说明，不补发结果");
            check(log.contains("WARN") && log.contains("结果未知") && log.contains("未自动重发"),
                    "日志要如实记下这次不确定：" + log.strip());
            check(!log.contains("已改为逐条发送"), "不确定时不许留下「已改为逐条发送」这种话：" + log.strip());
        }
    }

    /** ⑤ 传输层确定拒绝（retcode）：照旧逐条兜底，一条不丢、顺序不变、编号连续。 */
    private static void definiteFailureStillFallsBackOneByOne() throws Exception {
        RecordingSender sender = new RecordingSender();
        sender.recordFailure = new IOException("QQ 发送失败（send_group_forward_msg），retcode=1200：富媒体消息发送失败");
        try (Fixture fixture = new Fixture(sender)) {
            CivitaiClient.SearchPage page = page();
            String log = captureError(() -> fixture.bot.sendLoraSearchResults(fixture.event, page));
            equal(1, sender.records.get(), "合并转发只尝试一次（不重试）");
            equal(COUNT, sender.resultSends(), "确定失败必须逐条兜底，一条不丢");
            List<String> ids = new ArrayList<>();
            for (String text : sender.resultTexts()) ids.add(text.substring(0, text.indexOf(' ')));
            equal(List.of("#1", "#2", "#3"), ids, "逐条兜底的顺序与编号与原批次一致");
            check(sender.noticeText().contains("逐条发送"), "如实告诉用户已逐条发送：" + sender.noticeText());
            check(log.contains("已改为逐条发送"), "确定失败仍要留一行 warn 日志：" + log.strip());
        }
    }

    // ---------------------------------------------------------------- ⑥⑦ 图片领取（真实 .get 路径）

    /** ⑥ 一次 .get 领 3 张图，合并转发"超时/结果未知"：一张都不许重发。 */
    private static void unknownDeliveryNeverResendsImageAlbum() throws Exception {
        RecordingSender sender = new RecordingSender();
        try (Fixture fixture = new Fixture(sender)) {
            fixture.seed(3);
            fixture.command(".imgmode record");
            sender.recordFailure = DeliveryUnknownException.timeout();
            String log = captureError(() -> {
                fixture.command(".get");
                check(sender.recordAttempt.await(10, TimeUnit.SECONDS), "合并转发确实被尝试了一次");
                fixture.awaitText("合并转发失败", 5000);
                // 多等一会儿：如果代码真的会重发，这段时间足够它把 3 张再发一遍。
                fixture.awaitPending(3);
                Thread.sleep(400);
            });
            equal(1, sender.records.get(), "图片批次只尝试一次合并转发");
            equal(COUNT, sender.nodes.get(0).size(), "这条聊天记录里是 " + COUNT + " 张图（每张一个节点）");
            equal(0, sender.imageSends.get(), "结果不确定时一张都不许重发（同一批图片不许出现两遍）");
            equal(0, sender.mapped.get(), "也不许改用 sendMap 重发");
            String notice = sender.lastSendNotice();
            check(notice.contains("可能已发出") && notice.contains("未自动重发"),
                    "图片批次的说明同样要写明可能已发出：" + notice);
            check(log.contains("WARN") && log.contains("结果未知") && log.contains("未自动重发"),
                    "日志要如实记下：" + log.strip());
            equal(0, fixture.pending(), "这次领取按「结果未知＝可能已发出」收尾：不留着让 /get 再发一遍"
                    + "（宁可少发一次，也不许同一批出现两遍）");
        }
    }

    /** ⑦ 图片批次被确定拒绝：照旧逐张兜底，张数与顺序不变，并且照常 acknowledge。 */
    private static void definiteFailureStillFallsBackForImageAlbum() throws Exception {
        RecordingSender sender = new RecordingSender();
        try (Fixture fixture = new Fixture(sender)) {
            List<Path> images = fixture.seed(3);
            fixture.command(".imgmode record");
            sender.recordFailure = new IOException("QQ 发送失败（send_group_forward_msg），retcode=1200");
            String log = captureError(() -> {
                fixture.command(".get");
                check(sender.recordAttempt.await(10, TimeUnit.SECONDS), "合并转发确实被尝试了一次");
                fixture.awaitPending(0);
            });
            equal(1, sender.records.get(), "只尝试一次合并转发");
            equal(3, sender.sentFiles().size(), "确定被拒时逐张兜底，一张不丢（一条普通消息里 3 张图）");
            equal(senderImageFiles(images), sender.sentFiles(), "兜底发送的文件顺序与批次一致");
            check(log.contains("已改为普通发送 3 张"), "日志沿用原有的兜底话术：" + log.strip());
            equal(0, fixture.pending(), "图确实发出去了，照常 acknowledge（不会因为改了不确定分支就漏领）");
        }
    }

    // ---------------------------------------------------------------- ⑧ 说明与判定本身

    /** ⑧ 两种不确定原因都算"结果未知"，确定被拒与其它异常都不算，且文案自己就说清楚了。 */
    private static void noticeIsHonestAndSingle() {
        DeliveryUnknownException timeout = DeliveryUnknownException.timeout();
        DeliveryUnknownException dropped = DeliveryUnknownException.disconnected();
        check(Bot.deliveryUnknown(timeout), "超时算不确定");
        check(Bot.deliveryUnknown(dropped), "断开算不确定");
        check(Bot.deliveryUnknown(new CompletionException(dropped)), "包在 CompletionException 里也算不确定");
        check(Bot.deliveryUnknown(new ExecutionException(dropped)), "包在 ExecutionException 里也算不确定");
        check(Bot.deliveryUnknown(new IOException("外层包装", dropped)), "被别的异常套住也算不确定");
        check(!Bot.deliveryUnknown(new IOException("QQ 发送失败（send_group_forward_msg），retcode=1200")),
                "确定被拒不算不确定（要继续逐条兜底）");
        check(!Bot.deliveryUnknown(null), "没有失败就不算不确定");
        check(!Bot.deliveryUnknown(new RuntimeException("其他异常")), "不相干的异常不算不确定（保持既有兜底）");
        check(timeout.getMessage().contains("结果未知") && timeout.getMessage().contains("未自动重发"),
                "不确定异常自己就说清楚结果未知：" + timeout.getMessage());
        check(dropped.getMessage().contains("结果可能未知") && dropped.getMessage().contains("未自动重发"),
                "断开那条也说清楚：" + dropped.getMessage());
    }

    // ---------------------------------------------------------------- 假传输层

    /** 可编程失败的记录型传输层：sendRecord / send / sendMap 的次数、节点、图片段、文件都记下来。 */
    private static class RecordingSender implements Bot.Sender {
        final AtomicInteger records = new AtomicInteger();
        final AtomicInteger textSends = new AtomicInteger();
        final AtomicInteger imageSends = new AtomicInteger();
        final AtomicInteger mapped = new AtomicInteger();
        final List<List<JsonArray>> nodes = new CopyOnWriteArrayList<>();
        final List<String> texts = new CopyOnWriteArrayList<>();
        final List<String> files = new CopyOnWriteArrayList<>();
        final CountDownLatch recordAttempt = new CountDownLatch(1);
        /** null = 成功；否则以这个异常失败（用来区分"确定被拒"与"结果未知"）。 */
        volatile Throwable recordFailure;

        @Override public CompletableFuture<Void> sendRecord(JsonObject event, List<JsonArray> messages) {
            recordAttempt.countDown();
            records.incrementAndGet();
            List<JsonArray> copy = new ArrayList<>(messages.size());
            for (JsonArray node : messages) copy.add(node.deepCopy());
            nodes.add(List.copyOf(copy));
            Throwable failure = recordFailure;
            return failure == null ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(failure);
        }
        @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
            texts.add(Bot.messageText(segments));
            boolean images = false;
            for (JsonElement segment : segments) if (segment.isJsonObject()
                    && "image".equals(segment.getAsJsonObject().get("type").getAsString())) {
                images = true;
                files.add(segment.getAsJsonObject().getAsJsonObject("data").get("file").getAsString());
            }
            if (images) imageSends.incrementAndGet(); else textSends.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> sendMap(JsonObject event, JsonArray segments) {
            mapped.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        /** 一条搜索结果的出站消息以"#编号 "开头；用它把结果与说明性回执分开。 */
        List<String> resultTexts() {
            List<String> results = new ArrayList<>();
            for (String text : texts) if (text.matches("(?s)^#[0-9]+ .*")) results.add(text);
            return results;
        }
        int resultSends() { return resultTexts().size(); }
        List<String> notices() {
            List<String> notices = new ArrayList<>();
            for (String text : texts) if (!text.matches("(?s)^#[0-9]+ .*")) notices.add(text);
            return notices;
        }
        String noticeText() { return notices().isEmpty() ? "" : notices().get(0); }
        /** 只说"发送结果"的那些回执（把 .imgmode 之类的设置回执排除掉）。 */
        List<String> sendNotices() {
            List<String> notices = new ArrayList<>();
            for (String text : notices()) if (text.startsWith("合并转发")) notices.add(text);
            return notices;
        }
        String lastSendNotice() {
            List<String> notices = sendNotices();
            return notices.isEmpty() ? "" : notices.get(notices.size() - 1);
        }
        List<String> sentFiles() { return List.copyOf(files); }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final Bot bot;
        final RecordingSender sender;
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "forward-timeout-stub"); thread.setDaemon(true); return thread;
        });
        final JsonObject event = Json.parse("{\"post_type\":\"message\",\"self_id\":\"10000\",\"message_type\":\"group\","
                + "\"group_id\":\"999\",\"user_id\":\"456\",\"message_id\":1,\"raw_message\":\"x\"}");

        Fixture(RecordingSender sender) throws Exception {
            this.sender = sender;
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "forward-timeout-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            JsonObject civitai = new JsonObject();
            civitai.addProperty("lora_dir", root.resolve("loras").toString().replace('\\', '/'));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", ForwardTimeoutTest::stubReply);
            server.start();
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sd.addProperty("timeout_seconds", 3);
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            config.add("civitai", civitai);
            config.addProperty("owner_user_id", "10000");
            config.addProperty("gen_auto_get", false);
            Json.atomicWrite(root.resolve("config.json"), config);
            bot = new Bot(new Settings(root), new SdClient(root, sd), sender);
        }

        /** 真实的 2×2 PNG 写进 data/generated/task-…/ 并进入待领取队列（与线上 /gen 之后一模一样）。 */
        List<Path> seed(int count) throws Exception {
            Path directory = root.resolve("data/generated").resolve("task-00000000-0000-0000-0000-000000000001");
            Files.createDirectories(directory);
            List<Path> paths = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                Path path = directory.resolve(String.format(Locale.ROOT, "%02d.png", index));
                Files.write(path, png());
                paths.add(path);
            }
            new ImageOutbox(root).append(paths);
            return List.copyOf(paths);
        }

        /** 走真实指令入口（同一个 message_id 只处理一次，所以每条指令都要换一个）。 */
        void command(String text) throws Exception {
            JsonObject packet = event.deepCopy();
            packet.addProperty("message_id", IDS.incrementAndGet());
            packet.add("message", Maps.text(text));
            packet.addProperty("raw_message", text);
            bot.accept(packet);
        }

        int pending() throws IOException { return new SdClient(root, new JsonObject()).pendingImages().size(); }

        void awaitPending(int expected) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (pending() != expected && System.nanoTime() < end) Thread.sleep(20);
        }

        /** 发送是异步的：等到出现一条以 prefix 开头的文本（提醒可能还没轮到），超时就返回现状。 */
        void awaitText(String prefix, long millis) throws Exception {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (senderTexts().stream().noneMatch(text -> text.startsWith(prefix)) && System.nanoTime() < end)
                Thread.sleep(20);
        }

        List<String> senderTexts() { return List.copyOf(sender.texts); }

        public void close() {
            bot.close();
            server.stop(0);
            executor.shutdownNow();
            TestCleanup.awaitQuiet(root, 5000);
            TestCleanup.deleteQuietly(root);
        }
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }

    private static List<String> senderImageFiles(List<Path> images) {
        List<String> uris = new ArrayList<>();
        try { for (Path path : images) uris.add(path.toRealPath().toUri().toASCIIString()); }
        catch (IOException failure) { throw new AssertionError(failure); }
        return uris;
    }

    private static void stubReply(HttpExchange exchange) throws IOException {
        try {
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
    }

    private static JsonArray segmentsOf(JsonObject segment) {
        JsonArray array = new JsonArray();
        array.add(segment);
        return array;
    }

    private static JsonObject image() {
        JsonObject data = new JsonObject();
        data.addProperty("file", "https://image.civitai.com/x/0.jpeg");
        JsonObject image = new JsonObject();
        image.addProperty("type", "image");
        image.add("data", data);
        return image;
    }

    private static CivitaiClient.SearchPage page() {
        List<CivitaiClient.SearchResult> results = new ArrayList<>();
        for (int index = 0; index < COUNT; index++) results.add(new CivitaiClient.SearchResult(100 + index, 1000 + index,
                "模型 " + (index + 1), "Illustrious", "https://image.civitai.com/x/" + index + ".jpeg",
                12345 + index, List.of("trigger one"), 1536.5, false, 4321 + index, "2026-06-03T12:00:00.000Z"));
        return new CivitaiClient.SearchPage(results, "gpt", 1, 10, false, -1, false);
    }

    /** 跑一段调用，并把 System.err 抓回来（Log.warn 走 stderr，正好是这次要断言的日志）。 */
    private static String captureError(ThrowingRunnable action) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try { action.run(); }
        finally { System.setErr(original); }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }
}

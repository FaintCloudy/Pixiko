package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.sd.SdClient;

/**
 * QQ 端 {@code .lora query/search} 的结果发送方式：一次搜索**合并成一条聊天记录**（每条结果一个节点），
 * 传输层不支持合并转发时保持逐条发送并如实说明，合并转发失败时回退成逐条发送——结果一条都不能丢。
 *
 * <p>全程用手造的 {@link CivitaiClient.SearchPage} 与假传输层，不连 Civitai、不发 QQ 消息。
 * 节点正文（用户手机上看的那段文字）单独断言：编号、模型名（超长也不截断）、底模、下载量、
 * 触发词、大小、页面地址，逐行纯文本而不是 JSON/表格。
 */
public final class LoraSearchRecordTest {
    private static int assertions;
    private static final int COUNT = 3;
    /** 144 个字的名字：任何"省略号截断"都会在这里露馅。 */
    private static final String LONG_NAME = "超长模型名字".repeat(24);

    public static void main(String[] args) throws Exception {
        oneRecordPerSearchWithOneNodePerResult();
        nodeTextIsCompleteAndHumanReadable();
        unsupportedTransportSendsOneByOneWithReason();
        failedRecordFallsBackWithoutLosingResults();
        numberingMatchesDownloadCommand();
        System.out.println("LoraSearchRecordTest: " + assertions + " assertions passed: one sendRecord per search, node count and full node text, "
                + "per-item fallback without support and after failure, numbering matches .lora download #N.");
    }

    /** ① 一次搜索只调用一次 sendRecord（节点数 = 结果数），绝不退化成 N 次普通发送。 */
    private static void oneRecordPerSearchWithOneNodePerResult() throws Exception {
        RecordingSender sender = new RecordingSender();
        try (Fixture fixture = new Fixture(sender)) {
            CivitaiClient.SearchPage page = page();
            fixture.bot.sendLoraSearchResults(fixture.event, page);
            check(sender.supportsRecord(), "假传输层确实会发合并转发");
            equal(1, sender.records.get(), "一次搜索只调用一次 sendRecord");
            equal(COUNT, sender.nodes.get(0).size(), "节点数 = 结果数");
            equal(0, sender.resultSends(), "合并转发成功时不再逐条普通发送");
            equal(0, sender.notices().size(), "正常合并转发不需要额外的发送方式说明");
        }
    }

    /** ② 每个节点含编号与完整模型名，正文是人可读的纯文本（含底模/下载量/触发词/大小/页面地址）。 */
    private static void nodeTextIsCompleteAndHumanReadable() throws Exception {
        RecordingSender sender = new RecordingSender();
        try (Fixture fixture = new Fixture(sender)) {
            CivitaiClient.SearchPage page = page();
            fixture.bot.sendLoraSearchResults(fixture.event, page);
            List<JsonArray> record = sender.nodes.get(0);
            equal(COUNT, record.size(), "节点数 = 结果数");
            for (int at = 0; at < COUNT; at++) {
                String text = Bot.messageText(record.get(at));
                CivitaiClient.SearchResult result = page.results().get(at);
                check(text.startsWith("#" + (at + 1) + " "), "第 " + (at + 1) + " 个节点以本页编号开头：" + text);
                check(text.contains(result.name()), "节点里有模型名：" + text);
                check(text.contains("底模：Illustrious"), "节点里有底模：" + text);
                check(text.contains("下载量：" + String.format(Locale.ROOT, "%,d", result.downloads())), "节点里有下载量：" + text);
                check(text.contains("触发词（2 条）：trigger one、trigger two"), "节点里有触发词：" + text);
                check(text.contains(result.url()), "节点里有该结果的页面地址：" + text);
                check(!text.contains("…") && !text.contains("|") && !text.startsWith("{") && !text.contains("\":"),
                        "节点是逐行纯文本，不是 JSON/表格，也没有截断：" + text);
            }
            check(LONG_NAME.codePointCount(0, LONG_NAME.length()) > 100, "夹具的超长名确实超过 100 字：" + LONG_NAME.length());
            check(Bot.messageText(record.get(0)).contains(LONG_NAME), "超长模型名完整保留，不截断");
            check(Bot.messageText(record.get(0)).contains("大小：1.5 MB"), "大小按 MB 写：" + Bot.messageText(record.get(0)));
            check(!Bot.messageText(record.get(0)).contains("（NSFW 标记）"), "非 NSFW 结果不贴标记");
            check(Bot.messageText(record.get(1)).contains("（NSFW 标记）"), "NSFW 结果如实标记：" + Bot.messageText(record.get(1)));
            // 封面：有封面的节点 = 正文 + 封面图；没有封面的节点只有正文，不假装有图。
            equal(2, record.get(0).size(), "有封面的节点 = 正文 + 封面");
            equal("image", record.get(0).get(1).getAsJsonObject().get("type").getAsString(), "节点第二段是图片");
            equal(page.results().get(0).cover(), Json.str(Json.obj(record.get(0).get(1).getAsJsonObject(), "data"), "file", ""),
                    "节点里的封面就是该结果的封面地址");
            equal(1, record.get(2).size(), "没有封面的节点只有正文");
            equal("1.5 MB", Bot.loraSearchSize(1536.5), "大小换算成 MB");
            equal("512 KB", Bot.loraSearchSize(512.4), "小于 1 MB 写 KB");
            equal("", Bot.loraSearchSize(0), "大小未知就不写这一行");
        }
    }

    /** ③ supportsRecord()=false：保持逐条发送，并如实说一句为什么。 */
    private static void unsupportedTransportSendsOneByOneWithReason() throws Exception {
        PlainSender sender = new PlainSender();
        try (Fixture fixture = new Fixture(sender)) {
            CivitaiClient.SearchPage page = page();
            fixture.bot.sendLoraSearchResults(fixture.event, page);
            check(!sender.supportsRecord(), "假传输层确实没有合并转发能力");
            equal(COUNT, sender.resultSends(), "结果逐条发送，一条不少");
            List<String> results = sender.resultTexts();
            for (int at = 0; at < COUNT; at++) {
                int index = at;
                check(results.stream().anyMatch(text -> text.startsWith("#" + (index + 1) + " ")
                                && text.contains(page.results().get(index).name())),
                        "逐条发送的第 " + (index + 1) + " 条带编号与模型名：" + results);
            }
            String notice = sender.noticeText();
            check(notice.contains("不支持合并转发") && notice.contains("已逐条发送"),
                    "如实说明这个传输层不支持合并转发、已逐条发送：" + notice);
        }
    }

    /** ④ sendRecord 抛错：回退逐条发送、结果一个不少、留一行 warn 日志。 */
    private static void failedRecordFallsBackWithoutLosingResults() throws Exception {
        RecordingSender sender = new RecordingSender();
        sender.failRecords = true;
        try (Fixture fixture = new Fixture(sender)) {
            CivitaiClient.SearchPage page = page();
            PrintStream original = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            try { fixture.bot.sendLoraSearchResults(fixture.event, page); }
            finally { System.setErr(original); }
            equal(1, sender.records.get(), "合并转发只尝试一次（不重试）");
            equal(COUNT, sender.resultSends(), "回退后每条结果都发出去了");
            List<String> results = sender.resultTexts();
            for (int at = 0; at < COUNT; at++) {
                int index = at;
                check(results.stream().anyMatch(text -> text.startsWith("#" + (index + 1) + " ")
                                && text.contains(page.results().get(index).name())
                                && text.contains(page.results().get(index).url())),
                        "回退里第 " + (index + 1) + " 条（编号 + 名字 + 地址）不能丢：" + results);
            }
            String notice = sender.noticeText();
            check(notice.contains("合并转发失败") && notice.contains("已逐条发送"),
                    "回退要如实告诉用户：" + notice);
            String log = captured.toString(StandardCharsets.UTF_8);
            check(log.contains("WARN") && log.contains("合并转发失败"),
                    "回退要留一行 warn 日志：" + log.strip());
        }
    }

    /** ⑤ 合并记录里的编号与 {@code .lora download #N} 仍然是同一份本页列表。 */
    private static void numberingMatchesDownloadCommand() throws Exception {
        RecordingSender sender = new RecordingSender();
        CapturingDownloader downloader = new CapturingDownloader();
        try (Fixture fixture = new Fixture(sender, downloader)) {
            CivitaiClient.SearchPage page = page();
            fixture.bot.registerLoraSearch(fixture.event, page);
            fixture.bot.sendLoraSearchResults(fixture.event, page);
            List<JsonArray> record = sender.nodes.get(0);
            for (int at = 0; at < COUNT; at++) {
                String first = Bot.messageText(record.get(at)).split("\n", 2)[0];
                equal("#" + (at + 1) + " " + page.results().get(at).name(), first, "节点编号与结果顺序一致");
            }
            JsonObject command = fixture.event.deepCopy();
            command.addProperty("raw_message", ".lora download #2");
            command.addProperty("message_id", 99);
            fixture.bot.accept(command);
            check(downloader.started.await(5, TimeUnit.SECONDS), ".lora download #2 能开始下载");
            equal(page.results().get(1).url(), downloader.url.get(),
                    "#2 仍然指本页第 2 条，与合并记录里的 #2 是同一条");
        }
    }

    private static CivitaiClient.SearchPage page() {
        List<CivitaiClient.SearchResult> results = new ArrayList<>();
        for (int index = 0; index < COUNT; index++) results.add(result(index));
        return new CivitaiClient.SearchPage(results, "tsumugi", 1, 10, false, -1, false);
    }

    /** 第 1 条超长名、第 2 条 NSFW、第 3 条没有封面。 */
    private static CivitaiClient.SearchResult result(int index) {
        return new CivitaiClient.SearchResult(100 + index, 1000 + index,
                index == 0 ? LONG_NAME : "模型 " + (index + 1), "Illustrious",
                index == 2 ? "" : "https://image.civitai.com/x/" + index + ".jpeg",
                12345 + index, List.of("trigger one", "trigger two"), 1536.5, index == 1);
    }

    /** 一条结果的出站消息以"#编号 "开头；用它把结果发送与说明性回执分开计数。 */
    private static boolean isResult(String text) { return text.matches("(?s)^#[0-9]+ .*"); }

    /** 只实现 send 的传输层：supportsRecord() 为 false。 */
    private static class PlainSender implements Bot.Sender {
        final List<String> texts = new CopyOnWriteArrayList<>();
        @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
            texts.add(Bot.messageText(segments));
            return CompletableFuture.completedFuture(null);
        }
        List<String> resultTexts() {
            List<String> results = new ArrayList<>();
            for (String text : texts) if (isResult(text)) results.add(text);
            return results;
        }
        int resultSends() { return resultTexts().size(); }
        List<String> notices() {
            List<String> notices = new ArrayList<>();
            for (String text : texts) if (!isResult(text)) notices.add(text);
            return notices;
        }
        String noticeText() { return notices().isEmpty() ? "" : notices().get(0); }
    }

    /** 会发合并转发的传输层；failRecords 时按"节点格式被拒"失败。 */
    private static final class RecordingSender extends PlainSender {
        final AtomicInteger records = new AtomicInteger();
        final List<List<JsonArray>> nodes = new CopyOnWriteArrayList<>();
        volatile boolean failRecords;
        @Override public CompletableFuture<Void> sendRecord(JsonObject event, List<JsonArray> messages) {
            records.incrementAndGet();
            List<JsonArray> record = new ArrayList<>(messages.size());
            for (JsonArray node : messages) record.add(node.deepCopy());
            nodes.add(List.copyOf(record));
            if (failRecords) return CompletableFuture.failedFuture(new IOException("模拟 send_group_forward_msg 被拒 retcode=1200"));
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 假下载器：只记录被下载的地址，然后失败（不碰网络）。 */
    private static final class CapturingDownloader implements Bot.LoraDownloader {
        final AtomicReference<String> url = new AtomicReference<>();
        final CountDownLatch started = new CountDownLatch(1);
        @Override public CivitaiClient.DownloadedLora download(String link, Consumer<String> stage, CivitaiClient.Progress meter) throws Exception {
            url.set(link);
            started.countDown();
            throw new IOException("测试拦截下载：不真的访问 Civitai");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final Bot bot;
        final CapturingDownloader downloader;
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "lora-search-record-stub"); thread.setDaemon(true); return thread;
        });
        final JsonObject event = Json.parse("{\"post_type\":\"message\",\"self_id\":1,\"message_type\":\"group\",\"group_id\":2,\"user_id\":3,\"raw_message\":\"x\"}");

        Fixture(Bot.Sender sender) throws Exception { this(sender, null); }

        Fixture(Bot.Sender sender, CapturingDownloader downloader) throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "lora-search-record-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            JsonObject civitai = new JsonObject();
            civitai.addProperty("lora_dir", root.resolve("loras").toString().replace('\\', '/'));
            // 本地 SD 桩：探活走 /internal/ping（404 = 连不上，不学启动参数），提示词走桥接接口。
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", LoraSearchRecordTest::stubReply);
            server.start();
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sd.addProperty("timeout_seconds", 3);
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            config.add("civitai", civitai);
            config.addProperty("gen_auto_get", false);
            Json.atomicWrite(root.resolve("config.json"), config);
            this.downloader = downloader;
            Settings settings = new Settings(root);
            SdClient client = new SdClient(root, sd);
            bot = downloader == null ? new Bot(settings, client, sender) : new Bot(settings, client, sender, downloader);
        }

        public void close() {
            bot.close();
            server.stop(0);
            executor.shutdownNow();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException ignored) { }
        }
    }

    private static void stubReply(HttpExchange exchange) throws IOException {
        try {
            boolean prompts = exchange.getRequestURI().getPath().equals("/pixiko-bridge/v1/prompts");
            String body = prompts
                    ? "{\"positive\":\"stub +\",\"negative\":\"stub -\",\"source\":\"webui-live\",\"revision\":1,"
                            + "\"sampler_name\":\"Euler a\",\"styles\":[],\"width\":512,\"height\":512,\"settings_initialized\":true}"
                    : "{}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(prompts ? 200 : 404, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }
}

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
import java.util.function.Consumer;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;

/** LoRA commands use an injected fake downloader and a local SD stub; never real Civitai, WebUI, or QQ. */
public final class LoraCommandsTest {
    private static final String URL = "https://civitai.com/models/42?modelVersionId=43&token=url-secret";
    private static final String NAME = "测试 LoRA 2";
    private static final AtomicInteger IDS = new AtomicInteger();
    private static int assertions;
    private record Reply(JsonObject event, JsonArray segments) { String text() { return Bot.messageText(segments); } }

    public static void main(String[] args) throws Exception {
        showcaseStylesSavedDuringDownload();
        groupAndPrivateCommands();
        downloadNeverBlocksCommandsAndHasNoQueue();
        failuresKeepFileAndAllowRetry();
        permissionsValidationAndLazyConfiguration();
        deletingLocalLoras();
        capturedGenerationIsUnchanged();
        closeInterruptsWorker();
        System.out.println("LoraCommandsTest: " + assertions + " assertions passed: download/load/list/delete/status, group/private, bounded worker, redaction, retry, permissions, snapshot, shutdown.");
    }

    private static void showcaseStylesSavedDuringDownload() throws Exception {
        try (Fixture f = new Fixture(false, false)) {
            f.downloader.showcases = List.of(new CivitaiClient.ShowcasePrompt(1, "example +", "example -", true, ""));
            for (String type : List.of("group", "private")) {
                f.command(type, ".lora download " + URL);
                String report = f.take(type).text();
                check(report.contains("已加载") && report.contains("测试模型 1"), type + " automatic model load and showcase save both reported");
                // 展示图样式现在写进机器人自己的样式库（data/local-styles.json），不再写 WebUI 预设样式。
                JsonObject showcase = f.localStyle("测试模型 1");
                check(showcase != null && showcase.get("positive").getAsString().startsWith("example +, <lora:"),
                        "saved showcase with verified local LoRA tag: " + showcase);
                check(showcase.get("negative").getAsString().equals("example -"), "saved exact showcase negative");
                check(f.savedStyles.isEmpty(), "showcase import never writes WebUI preset styles: " + f.savedStyles);
                check(!f.stateCopy().get("positive").getAsString().contains("example +"), "showcase import does not replace bot prompt");
            }
        }
    }

    private static void groupAndPrivateCommands() throws Exception {
        try (Fixture f = new Fixture(false, false)) {
            for (String type : List.of("group", "private")) {
                String help = f.command(type, ".help").text();
                for (String command : List.of(".lora download", ".lora status", ".lora list", ".lora load"))
                    check(help.contains(command), type + " help includes " + command);
                check(f.command(type, ".lora status").text().contains("LoRA"), type + " status always available");
                f.setPositive("initial +");
                int refreshes = f.refreshes.get();
                check(f.command(type, ".lora download " + URL).text().contains("开始下载"), type + " immediate download acknowledgement");
                Reply success = f.take(type);
                check(success.text().contains("下载成功") && success.text().contains("已加载：" + NAME), type + " download automatically loads correct file");
                check(success.text().contains("<lora:中文别名" + ":1>"), type + " default weight is one");
                check(success.text().contains("SD 1.5") && success.text().contains("trigger words"), type + " base model and optional trigger words shown");
                check(success.text().contains("Civitai 返回的触发词（原文，未自动添加）：\n"), type + " trigger words distinguished from activation tag");
                check(f.refreshes.get() > refreshes, type + " WebUI catalog refreshed");
                String positive = f.stateCopy().get("positive").getAsString();
                check(positive.contains("<lora:中文别名" + ":1>") && !positive.contains("trigger words"), type + " only tag added, no automatic trigger words");
                check(f.command(type, ".lora status").text().contains("下载成功"), type + " completed status retained");
                check(f.command(type, ".lora list").text().contains("正在读取"), type + " list acknowledged");
                check(f.take(type).text().contains(NAME), type + " local list returned");
                check(f.command(type, ".lora load \"" + NAME + "\" 0.8").text().contains("正在刷新"), type + " quoted full local name accepted");
                check(f.take(type).text().contains("<lora:中文别名" + ":0.8>"), type + " supplied weight loaded");
                f.command(type, ".lora load " + NAME + ".safetensors 2");
                check(f.take(type).text().contains("<lora:中文别名" + ":2>"), type + " filename with extension and spaces loads");
                f.command(type, ".lora load \"" + NAME + "\" 0");
                check(f.take(type).text().contains("<lora:中文别名" + ":0>"), type + " zero endpoint accepted");
            }
            f.downloader.reused = true;
            f.command("private", ".lora download " + URL + " 0.5");
            check(f.take("private").text().contains("本地文件已复用"), "existing download reuse reported");
        }
    }

    private static void downloadNeverBlocksCommandsAndHasNoQueue() throws Exception {
        try (Fixture f = new Fixture(false, false)) {
            f.downloader.block = true;
            f.setPositive("initial +");
            f.downloader.stage = "读取 https://civitai.com/api?token=url-secret api_token=cfg-secret";
            long started = System.nanoTime();
            check(f.command("group", ".lora download " + URL).text().contains("开始下载"), "download immediately returns an acknowledgement");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "download I/O is not run on accept thread");
            check(f.downloader.entered.await(3, TimeUnit.SECONDS), "fake download enters worker");
            check(f.command("private", ".help").text().contains(".lora download"), "help remains responsive during blocked download");
            Reply caption = f.command("private", ".yh");
            check(caption.text().contains("地图"), "map caption is sent while the download is blocked");
            Reply map = f.take("private");
            equal("image", map.segments().get(0).getAsJsonObject().get("type").getAsString(), "maps remain responsive during blocked download");
            check(f.command("private", ".prompt").text().contains("initial +"), "other SD commands remain responsive during download");
            String status = f.command("private", ".lora status").text();
            check(!status.contains("https://") && !status.contains("url-secret") && !status.contains("cfg-secret"), "status redacts URLs and credentials");
            for (String command : List.of(".lora download " + URL, ".lora load \"" + NAME + "\"", ".lora list"))
                check(f.command("private", command).text().contains("正在处理"), "concurrent LoRA requests are rejected instead of queued");
            equal(1, f.downloader.calls.get(), "only accepted download reaches downloader");
            f.downloader.release.countDown();
            check(f.take("group").text().contains("下载成功"), "download completes in original group");
            check(f.replies.poll(100, TimeUnit.MILLISECONDS) == null, "busy requests never execute later");
        }
    }

    private static void failuresKeepFileAndAllowRetry() throws Exception {
        try (Fixture f = new Fixture(false, false)) {
            f.refreshStatus = 500;
            f.command("private", ".lora download " + URL + " 0.6");
            String failure = f.take("private").text();
            check(failure.contains("文件已保存") && failure.contains("自动加载失败"), "load failure distinguishes successful file download");
            check(failure.contains(".lora load \"" + NAME + ".safetensors\" 0.6"), "load failure provides exact filename retry");
            check(!failure.contains("已加载：") && Files.exists(f.file), "failed load never claims enabled and keeps file");
            check(!f.stateCopy().get("positive").getAsString().contains("<lora:"), "failed refresh does not modify prompt");
            f.refreshStatus = 200;
            f.command("private", ".lora load \"" + NAME + ".safetensors\" 0.6");
            check(f.take("private").text().contains("<lora:中文别名" + ":0.6>"), "manual retry works after failure");
            f.downloader.failure = new IOException("失败 https://civitai.com/api?token=url-secret api_token=cfg-secret token=raw-secret");
            f.command("group", ".lora download " + URL);
            String downloadError = f.take("group").text();
            check(downloadError.contains("下载失败") && !downloadError.contains("url-secret") && !downloadError.contains("cfg-secret")
                    && !downloadError.contains("raw-secret"), "download errors are explicit and redacted");
            check(!f.command("group", ".lora status").text().contains("raw-secret"), "failed status remains redacted");
            f.downloader.failure = null;
            f.command("private", ".lora download " + URL);
            check(f.take("private").text().contains("下载成功"), "busy flag resets after exception");
            f.catalogStatus = 500;
            f.command("private", ".lora list");
            check(f.take("private").text().contains("操作失败"), "list errors are reported asynchronously");
            f.catalogStatus = 200;
            f.command("private", ".lora list");
            check(f.take("private").text().contains(NAME), "busy flag resets after failed list");
        }
    }

    /** 删除本机 LoRA：按 #编号/名称都能删，只删配置目录里的文件，找不到时给出明确提示。 */
    private static void deletingLocalLoras() throws Exception {
        try (Fixture f = new Fixture(false, false)) {
            new Settings(f.root).civitaiSetting("lora_dir", new JsonPrimitive(f.file.getParent().toString().replace('\\', '/')));
            f.command("private", ".lora list");
            check(f.take("private").text().contains(NAME), "列表里能看到待删除的 LoRA");
            String deleted = f.command("private", ".lora delete #1").text();
            check(deleted.contains("已从磁盘删除") && deleted.contains(NAME), "按 #编号 删除并说明结果：" + deleted);
            check(!Files.exists(f.file), "LoRA 文件真的从磁盘上删掉了");
            check(f.command("private", ".lora delete " + NAME).text().contains("找不到"), "删过的文件再删会明确报找不到");
            check(f.command("private", ".lora delete ../evil").text().contains("找不到"), "带路径分隔符的名字不会越界删除");
            check(f.command("private", ".lora delete").text().startsWith("操作失败："), "缺少名称时给出用法");
        }
    }

    private static void permissionsValidationAndLazyConfiguration() throws Exception {
        try (Fixture f = new Fixture(false, false)) {
            for (String command : List.of(".lora", ".lora download", ".lora load", ".lora unknown",
                    ".lora download " + URL + " NaN", ".lora download " + URL + " Infinity",
                    ".lora download " + URL + " -1", ".lora download " + URL + " 2.1",
                    ".lora download " + URL + " abc", ".lora download " + URL + " 1 extra",
                    ".lora load \"" + NAME + "\" NaN", ".lora load \"" + NAME + "\" 3",
                    ".lora load \"" + NAME, ".lora load \"\""))
                check(f.command("private", command).text().startsWith("操作失败："), "invalid LoRA command rejected: " + command);
            equal(0, f.downloader.calls.get(), "invalid commands never download");
        }
        try (Fixture f = new Fixture(true, false)) {
            for (String type : List.of("group", "private"))
                for (String command : List.of(".lora load \"" + NAME + "\"", ".lora download " + URL))
                    check(f.command(type, command).text().contains("仅管理员"), "optional admin-only policy covers " + command);
            equal(0, f.downloader.calls.get(), "unauthorized users cannot start downloads");
            check(f.command("private", ".lora status").text().contains("尚未下载"), "read-only status remains available under admin-only");
            f.command("private", ".lora list");
            check(f.take("private").text().contains(NAME), "read-only list remains available under admin-only");
            check(f.command("private", ".lora status", 123).text().contains("尚未下载"), "configured administrator can use LoRA commands");
            f.command("private", ".lora download " + URL, 123);
            check(f.take("private").text().contains("下载成功"), "administrator can download when restricted");
        }
        try (Fixture f = new Fixture(false, true)) {
            check(f.command("private", ".help").text().contains(".lora"), "missing Civitai configuration does not break bot startup/help");
            f.command("private", ".lora download " + URL);
            String error = f.take("private").text();
            check(error.contains("lora_dir") && error.contains("配置"), "missing LoRA directory explains local configuration");
        }
    }

    private static void capturedGenerationIsUnchanged() throws Exception {
        try (Fixture f = new Fixture(false, false)) {
            f.blockGeneration = true;
            f.setPositive("initial +");
            f.command("group", ".gen");
            JsonObject payload = f.generations.poll(5, TimeUnit.SECONDS);
            check(payload != null && payload.get("prompt").getAsString().equals("initial +"), "generation captures prompt before LoRA change");
            f.command("private", ".lora download " + URL);
            check(f.take("private").text().contains("下载成功"), "LoRA can download/load while another image generates");
            check(f.stateCopy().get("positive").getAsString().contains("<lora:"), "future prompt receives LoRA tag");
            equal("initial +", payload.get("prompt").getAsString(), "accepted generation stays unchanged");
            f.generationRelease.countDown();
            check(f.take("group").text().contains("生成成功"), "generation completes in original group");
        }
    }

    private static void closeInterruptsWorker() throws Exception {
        try (Fixture f = new Fixture(false, false)) {
            f.downloader.block = true;
            f.command("private", ".lora download " + URL);
            check(f.downloader.entered.await(3, TimeUnit.SECONDS), "worker active before close");
            f.bot.close();
            check(f.downloader.interrupted.await(3, TimeUnit.SECONDS), "close interrupts download worker");
            check(f.take("private").text().contains("下载失败"), "interrupted download never reports success");
            check(f.command("private", ".lora download " + URL).text().contains("正在关闭"), "closed bot rejects new LoRA work");
            equal(1, f.downloader.calls.get(), "no worker restarted after close");
        }
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    private static final class FakeDownloader implements Bot.LoraDownloader {
        final Path path;
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        volatile boolean block, reused;
        volatile String stage = "正在下载 LoRA";
        volatile Exception failure;
        volatile List<CivitaiClient.ShowcasePrompt> showcases = List.of();
        FakeDownloader(Path path) { this.path = path; }
        public CivitaiClient.DownloadedLora download(String url, Consumer<String> progress, CivitaiClient.Progress meter) throws Exception {
            calls.incrementAndGet(); progress.accept(stage); entered.countDown();
            if (block) {
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("fake downloader timed out"); }
                catch (InterruptedException e) { interrupted.countDown(); throw e; }
            }
            if (failure != null) throw failure;
            return new CivitaiClient.DownloadedLora("测试模型", "版本一", "SD 1.5", List.of("trigger words", "人物"), path, reused, 42, 43, showcases);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Map<String, JsonObject> savedStyles = new ConcurrentHashMap<>();
        /** 机器人样式库里某个样式的原始 JSON（没有则返回 null）。 */
        JsonObject localStyle(String name) {
            Path file = root.resolve("data/local-styles.json");
            if (!Files.isRegularFile(file)) return null;
            try {
                JsonObject data = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                for (JsonElement item : data.getAsJsonArray("styles"))
                    if (item.getAsJsonObject().get("name").getAsString().equals(name)) return item.getAsJsonObject();
            } catch (Exception error) { throw new AssertionError("读取本机样式失败：" + error); }
            return null;
        }
        final Path root, file;
        final HttpServer server;
        final ExecutorService executor;
        final SdClient client;
        final Bot bot;
        final FakeDownloader downloader;
        final JsonObject state = Json.parse("{\"positive\":\"initial +\",\"negative\":\"initial -\",\"source\":\"webui-live\",\"revision\":1,\"sampler_name\":\"Euler a\",\"styles\":[],\"width\":512,\"height\":512,\"settings_initialized\":true}");
        final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonObject> generations = new LinkedBlockingQueue<>();
        final AtomicInteger refreshes = new AtomicInteger();
        final CountDownLatch generationRelease = new CountDownLatch(1);
        final String image;
        volatile int refreshStatus = 200, catalogStatus = 200;
        volatile boolean blockGeneration;

        Fixture(boolean adminOnly, boolean defaultDownloaderWithoutConfiguration) throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "lora-command-tests").toAbsolutePath();
            Files.createDirectories(work); root = Files.createTempDirectory(work, "case-");
            file = root.resolve("models/LoRA/" + NAME + ".safetensors"); Files.createDirectories(file.getParent()); Files.write(file, new byte[]{1, 2, 3});
            downloader = new FakeDownloader(file);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
            image = Base64.getEncoder().encodeToString(bytes.toByteArray());
            Files.createDirectories(root.resolve("maps/yh")); Files.write(root.resolve("maps/yh/map.png"), bytes.toByteArray());
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "lora-command-mock"); t.setDaemon(true); return t; });
            server.setExecutor(executor); server.createContext("/", this::handle); server.start();
            JsonObject sd = new JsonObject(); sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort()); sd.addProperty("timeout_seconds", 3);
            JsonObject config = new JsonObject(); config.add("sd", sd);
            JsonArray admins = new JsonArray(); admins.add(123); config.add("admin_user_ids", admins);
            JsonObject civitai = new JsonObject(); civitai.addProperty("admin_only", adminOnly); civitai.addProperty("api_token", "cfg-secret");
            config.add("civitai", civitai); config.addProperty("gen_auto_get", false); Json.atomicWrite(root.resolve("config.json"), config);
            client = new SdClient(root, sd);
            Bot.Sender sender = (event, segments) -> {
                replies.add(new Reply(event.deepCopy(), segments.deepCopy())); return CompletableFuture.completedFuture(null);
            };
            bot = defaultDownloaderWithoutConfiguration ? new Bot(new Settings(root), client, sender)
                    : new Bot(new Settings(root), client, sender, downloader);
        }
        synchronized JsonObject stateCopy() { return state.deepCopy(); }
        synchronized void setPositive(String text) throws IOException {
            state.addProperty("positive", text); state.addProperty("revision", state.get("revision").getAsInt() + 1);
            // Prompts are personal now; keep the callers' copies in step with the shared page state.
            UserPromptStore prompts = new UserPromptStore(root);
            for (String scope : List.of("456", Settings.DEFAULT_OWNER))
                prompts.replace(scope, new SdClient.Prompts(text, "initial -", UserPromptStore.PERSONAL_SOURCE));
        }
        Reply command(String type, String command) throws Exception { return command(type, command, 456); }
        Reply command(String type, String command, int user) throws Exception {
            JsonObject event = new JsonObject(); event.addProperty("post_type", "message"); event.addProperty("message_type", type);
            event.addProperty("user_id", user); event.addProperty("self_id", 777); event.addProperty("message_id", IDS.incrementAndGet());
            if (type.equals("group")) event.addProperty("group_id", 999);
            event.add("message", Maps.text(command)); bot.accept(event); return take(type);
        }
        Reply take(String type) throws Exception {
            Reply reply = replies.poll(5, TimeUnit.SECONDS); check(reply != null, "expected reply for " + type);
            equal(type, reply.event().get("message_type").getAsString(), "reply retains group/private context");
            if (type.equals("group")) equal(999, reply.event().get("group_id").getAsInt(), "reply retains group id");
            return reply;
        }
        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/pixiko-bridge/v1/prompts")) {
                    synchronized (this) {
                        if (exchange.getRequestMethod().equals("PUT")) {
                            JsonObject body = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                            if (body.get("expected_revision").getAsInt() != state.get("revision").getAsInt()) { send(exchange, 409, state.toString()); return; }
                            for (String key : List.of("positive", "negative", "styles", "sampler_name", "width", "height"))
                                if (body.has(key)) state.add(key, body.get(key).deepCopy());
                            state.addProperty("revision", state.get("revision").getAsInt() + 1);
                        }
                        send(exchange, 200, state.toString());
                    }
                } else if (path.equals("/sdapi/v1/refresh-loras")) {
                    refreshes.incrementAndGet(); send(exchange, refreshStatus, "null");
                } else if (path.equals("/sdapi/v1/loras")) {
                    JsonArray list = new JsonArray(); JsonObject entry = new JsonObject(); entry.addProperty("name", NAME);
                    entry.addProperty("alias", "中文别名"); entry.addProperty("path", file.toString()); list.add(entry);
                    send(exchange, catalogStatus, list.toString());
                } else if (path.equals("/sdapi/v1/prompt-styles")) {
                    JsonArray list = new JsonArray();
                    savedStyles.forEach((name, stored) -> {
                        JsonObject style = new JsonObject(); style.addProperty("name", name);
                        style.add("prompt", stored.get("positive")); style.add("negative_prompt", stored.get("negative")); list.add(style);
                    });
                    send(exchange, 200, list.toString());
                } else if (path.equals("/pixiko-bridge/v1/styles")) {
                    JsonObject body = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    String name = body.get("name").getAsString();
                    if (savedStyles.putIfAbsent(name, body) != null) { send(exchange, 409, "{}"); return; }
                    JsonObject response = new JsonObject(); response.addProperty("name", name); response.addProperty("overwritten", false);
                    send(exchange, 200, response.toString());
                } else if (path.equals("/sdapi/v1/txt2img")) {
                    generations.add(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    if (blockGeneration) try { generationRelease.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    send(exchange, 200, "{\"images\":[\"" + image + "\"]}");
                } else send(exchange, 404, "{}");
            } finally { exchange.close(); }
        }
        static void send(HttpExchange exchange, int status, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }
        public void close() {
            downloader.release.countDown(); generationRelease.countDown(); bot.close(); server.stop(0); executor.shutdownNow();
        }
    }
}

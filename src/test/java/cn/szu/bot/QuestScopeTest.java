package cn.szu.bot;

import cn.szu.bot.sd.SdClient;
import cn.szu.bot.web.WebUiServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

/**
 * 回执的**会话归属**（scope）：索引级的过滤语义 + Bot 三个接口的过滤行为。
 *
 * <p>背景：回执/任务类接口以前完全"不看 scope"，于是任何一台设备都能在列表与详情里看到
 * 控制台（{@code web}）与别的设备的回执与图片 —— 用户报的「回执重复杂糅」。现在每条回执在创建时
 * 记住自己的 scope，列表/单条/角标都能按它筛；**不传 scope 的调用（桌面控制台）行为一字未改**。
 *
 * <p>全程只连回环地址、只用临时根目录，不碰 QQ、不碰真实 SD、不碰线上运行目录。
 */
public final class QuestScopeTest {

    private static final String WEB = "web";
    private static final String DEV = "dev-0123456789ab";
    private static final String TOKEN = "test-token-123456";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "quest-scope").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        try {
            indexSemantics(root.resolve("index"));
            botFilter(root.resolve("bot"));
            httpScopeFilter(root.resolve("http"));
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
        System.out.println("QuestScopeTest: " + checks + " assertions passed："
                + "索引按 scope 过滤（列表/未读/最新/单条/全部已读）、不带 scope 时行为不变、"
                + "scope 落盘重载仍在、Bot 的 /api/quests 与 /api/quest 与 /api/status 按会话筛、"
                + "老回执（无归属）不冒充任何会话");
    }

    // ------------------------------------------------------------------ 索引层（纯语义）

    private static void indexSemantics(Path parent) throws Exception {
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "index-");
        Files.createDirectories(root.resolve("data/generated"));
        QuestIndex index = new QuestIndex();

        Bot.WebCapture web = capture("capture-web", ".help", root, WEB);
        Bot.WebCapture dev = capture("capture-dev", ".gen 1", root, DEV);
        Bot.WebCapture legacy = capture("capture-legacy", ".style list", root);      // 老构造器：归属未知

        index.observe(web);
        index.observe(dev);
        index.observe(legacy);

        JsonObject all = index.json(50, 0);
        check(all.getAsJsonArray("quests").size() == 3 && all.get("total").getAsInt() == 3,
                "不带 scope 的列表照旧是全部 3 条：" + all.get("total"));
        check(!all.get("scoped").getAsBoolean(), "不带 scope 时 scoped=false（客户端据此决定要不要自己兜底）");

        JsonObject devView = index.json(50, 0, DEV);
        check(devView.getAsJsonArray("quests").size() == 1 && devView.get("total").getAsInt() == 1,
                "带 scope 的列表只有这个会话的 1 条：" + devView);
        check(entry(devView, 0).get("number").getAsInt() == dev.number(), "留下的正是这个会话那条");
        check(devView.get("scoped").getAsBoolean(), "带 scope 时 scoped=true");

        JsonObject webView = index.json(50, 0, WEB);
        check(webView.getAsJsonArray("quests").size() == 1
                        && entry(webView, 0).get("number").getAsInt() == web.number(),
                "web 会话只看得到自己那条（旧的无归属条目不出现）：" + webView);

        check(index.item(web.number(), DEV) == null, "别的会话的号取单条索引返回 null");
        check(index.item(legacy.number(), DEV) == null, "无归属的老条目不冒充任何会话");
        check(index.item(legacy.number(), null) != null, "无归属的老条目在不带 scope 的视图里照旧在");
        check(index.latest(DEV) == dev.number() && index.latest() == legacy.number(),
                "latest 也按会话算（不带 scope 时取全部里最大）");
        check(index.counts(DEV).get("unread").getAsInt() == 1 && index.unread() == 3,
                "角标按会话算：dev 1 条未读、全部 3 条未读");

        check(index.markAllRead(DEV) == 1 && index.unread(DEV) == 0 && index.unread() == 2,
                "「全部已读」只清这个会话：dev 清了 1 条，web 与老条目仍各 1 条未读");

        Path file = root.resolve("data/quests.json");
        index.save(file);
        QuestIndex reloaded = new QuestIndex();
        reloaded.load(file);
        JsonObject afterReload = reloaded.json(50, 0, DEV);
        check(afterReload.getAsJsonArray("quests").size() == 1
                        && entry(afterReload, 0).get("number").getAsInt() == dev.number(),
                "归属落盘：重启后按会话筛的结果不变：" + afterReload);
        check(reloaded.unread(DEV) == 0 && reloaded.unread() == 2, "已读状态也照旧");
    }

    // ------------------------------------------------------------------ Bot 三个接口

    private static void botFilter(Path root) throws Exception {
        Files.createDirectories(root.resolve("data/generated"));        JsonObject config = new JsonObject();
        JsonObject sd = new JsonObject();
        sd.addProperty("base_url", "http://127.0.0.1:1");
        config.add("sd", sd);
        Files.createDirectories(root.resolve("data"));
        Json.atomicWrite(root.resolve("config.json"), config);
        Files.writeString(root.resolve("data/deepseek-api-key.txt"), "test-key-not-real\n");

        Settings settings = new Settings(root);
        SdClient client = new SdClient(root, sd);
        Bot.Sender sender = new Bot.Sender() {
            @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }
        };
        int webNumber, devNumber;
        try (Bot bot = new Bot(settings, client, sender)) {
            webNumber = run(bot, WEB, ".help");
            devNumber = run(bot, DEV, ".help");

            JsonObject devList = bot.webQuests(50, DEV);
            check(devList.get("total").getAsInt() == 1 && devList.get("latest").getAsInt() == devNumber,
                    "手机端（带 scope）的列表只有自己那一条：" + devList);
            check(bot.webQuests(50).get("total").getAsInt() == 2,
                    "控制台（不带 scope）的列表一条不少：" + bot.webQuests(50).get("total"));

            JsonObject foreign = bot.webQuest(webNumber, DEV);
            check(String.valueOf(foreign.get("error")).contains("不属于这个会话") && !foreign.has("texts"),
                    "跨会话取单条：如实拒绝，一个字正文都不给：" + foreign);
            JsonObject mine = bot.webQuest(devNumber, DEV);
            check(mine.has("texts") && !mine.get("texts").getAsJsonArray().isEmpty(),
                    "自己那条照旧能取到完整正文：" + mine.get("quest"));
            JsonObject consoleRead = bot.webQuest(devNumber);
            check(consoleRead.has("texts"), "控制台（不带 scope）照旧能打开任何一条：" + consoleRead.get("quest"));

            check(bot.webStatus(DEV).getAsJsonObject("quests").get("latest").getAsInt() == devNumber
                            && bot.webStatus().getAsJsonObject("quests").get("latest").getAsInt() == devNumber,
                    "/api/status 的 quests：带 scope 时只算这个会话");
        }

        // 重启：归属从磁盘读回来，跨会话依旧互不可见
        try (Bot second = new Bot(settings, client, sender)) {
            JsonObject devList = second.webQuests(50, DEV);
            check(devList.get("total").getAsInt() == 1 && devList.get("latest").getAsInt() == devNumber,
                    "重启后手机端列表依旧只有自己那条：" + devList);
            check(second.webQuests(50).get("total").getAsInt() == 2, "重启后控制台依旧看得到两条");
            JsonObject foreign = second.webQuest(webNumber, DEV);
            check(String.valueOf(foreign.get("error")).contains("不属于这个会话"),
                    "重启后跨会话取单条依旧被拒：" + foreign.get("error"));
            JsonObject mine = second.webQuest(devNumber, DEV);
            check(mine.has("texts") && mine.get("fromDisk").getAsBoolean(),
                    "重启后自己那条从磁盘读回正文：" + mine.get("quest"));
        }
    }

    /** 用某个 scope 跑一条真指令，返回它的任务号（等它落到索引里）。 */
    private static int run(Bot bot, String scope, String command) throws Exception {
        Bot.WebCapture capture = bot.webCommand(scope, java.util.List.of(command));
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            Bot.WebCapture live = bot.webCapture(capture.id());
            if (live != null && live.done() && live.textCount() > 0) break;
            Thread.sleep(50);
        }
        bot.webQuests(50);            // 触发 observeQuests：把还活着的回执刷进索引
        return capture.number();
    }

    // ------------------------------------------------------------------ 走真实 HTTP（控制器那一层）

    /**
     * 手机端走的是真 HTTP：`/api/command`、`/api/quests`、`/api/quest`、`/api/status`、`/api/quests/read`
     * 都带 {@code body.scope="dev-…"}，控制台那几条**不带** scope。这里逐一断言两条路的差别。
     */
    private static void httpScopeFilter(Path root) throws Exception {
        Files.createDirectories(root.resolve("data/generated"));
        Files.createDirectories(root.resolve("data"));
        HttpServer stub = sdStub();
        int port = freePort();
        JsonObject config = new JsonObject();
        JsonObject sd = new JsonObject();
        sd.addProperty("base_url", "http://127.0.0.1:" + stub.getAddress().getPort());
        config.add("sd", sd);
        JsonObject webui = new JsonObject();
        webui.addProperty("enabled", true);
        webui.addProperty("host", "127.0.0.1");
        webui.addProperty("port", port);
        webui.addProperty("access_token", TOKEN);
        config.add("webui", webui);
        Json.atomicWrite(root.resolve("config.json"), config);
        Files.writeString(root.resolve("data/deepseek-api-key.txt"), "test-key-not-real\n");
        /* 盘上先放一条**本次改动之前写的**老回执：索引里没有 scope 字段（线上 478 条就是这样）。
           它必须只出现在"不带 scope"的视图里 —— 老条目不属于任何会话，不许冒充某台手机的回执。 */
        JsonObject legacyItem = new JsonObject();
        legacyItem.addProperty("number", 1);
        legacyItem.addProperty("command", ".legacy");
        legacyItem.addProperty("startedAt", QuestIndex.stamp(System.currentTimeMillis() - 3_600_000));
        legacyItem.addProperty("summary", "老回执：索引里没有归属字段");
        legacyItem.addProperty("texts", 1);
        legacyItem.addProperty("images", 0);
        legacyItem.addProperty("done", true);
        legacyItem.addProperty("unread", false);
        legacyItem.addProperty("read", true);
        JsonArray legacyQuests = new JsonArray();
        legacyQuests.add(legacyItem);
        JsonObject legacyIndex = new JsonObject();
        legacyIndex.addProperty("version", 1);
        legacyIndex.add("quests", legacyQuests);
        Json.atomicWrite(root.resolve("data/quests.json"), legacyIndex);

        Settings settings = new Settings(root);
        settings.webSetting("port", new com.google.gson.JsonPrimitive(port));
        SdClient client = new SdClient(root, sd);
        Bot.Sender sender = new Bot.Sender() {
            @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }
        };
        try (Bot bot = new Bot(settings, client, sender)) {
            try (WebUiServer server = new WebUiServer(settings, bot)) {
                server.start();
                String base = "http://127.0.0.1:" + port;
                int consoleNumber = command(base, ".help", null);        // 控制台：不带 scope
                int phoneNumber = command(base, ".help", DEV);           // 手机端：带自己的 scope
                waitEntry(base, consoleNumber);
                waitEntry(base, phoneNumber);

                JsonObject phoneList = post(base, "/api/quests", scoped(DEV, "limit", 20));
                check(phoneList.get("scoped").getAsBoolean() && phoneList.get("total").getAsInt() == 1
                                && entry(phoneList, 0).get("number").getAsInt() == phoneNumber,
                        "POST /api/quests {scope:dev-…} 只回这个会话那一条（老的无归属条目不出现）：" + phoneList);

                JsonObject consoleList = get(base, "/api/quests?limit=20");
                check(!consoleList.get("scoped").getAsBoolean() && consoleList.get("total").getAsInt() == 3,
                        "GET /api/quests（控制台不传 scope）照旧三条都在（含老的无归属那条）：" + consoleList);
                check(consoleList.get("latest").getAsInt() == Math.max(consoleNumber, phoneNumber),
                        "控制台的 latest 照旧是全局最大号");

                // 键名兼容：GET 查询串 ?scope= 与 POST body {scope} 必须等价（有人手敲链接/脚本都会用）
                JsonObject phoneByQuery = get(base, "/api/quests?limit=20&scope=" + DEV);
                check(phoneByQuery.get("total").getAsInt() == phoneList.get("total").getAsInt()
                                && phoneByQuery.get("scoped").getAsBoolean(),
                        "GET ?scope= 与 POST {scope} 等价：" + phoneByQuery);
                JsonObject webByQuery = get(base, "/api/quests?limit=20&scope=" + WEB);
                JsonObject webByBody = post(base, "/api/quests", scoped(WEB, "limit", 20));
                check(webByQuery.get("total").getAsInt() == 1 && webByBody.get("total").getAsInt() == 1
                                && entry(webByBody, 0).get("number").getAsInt() == consoleNumber,
                        "web 会话只看得到自己那条（老条目与手机那条都不出现）：" + webByBody);
                System.out.println("  [对照] 同一份索引：不带 scope → total=" + consoleList.get("total").getAsInt()
                        + "（含老的无归属 #1）；scope=" + DEV + " → total=" + phoneList.get("total").getAsInt()
                        + "；scope=" + WEB + " → total=" + webByBody.get("total").getAsInt());

                JsonObject foreign = post(base, "/api/quest", scoped(DEV, "id", consoleNumber));
                JsonObject consoleOpen = null;
                JsonObject mine = null;
                JsonObject phoneStatus = post(base, "/api/status", scoped(DEV, null, 0));
                check(phoneStatus.getAsJsonObject("quests").get("unread").getAsInt() == 1
                                && phoneStatus.getAsJsonObject("quests").get("latest").getAsInt() == phoneNumber,
                        "POST /api/status {scope:dev-…} 的角标只算这个会话：" + phoneStatus.getAsJsonObject("quests"));
                JsonObject consoleStatus = get(base, "/api/status");
                check(consoleStatus.getAsJsonObject("quests").get("unread").getAsInt() == 2
                                && consoleStatus.getAsJsonObject("quests").get("latest").getAsInt()
                                == Math.max(consoleNumber, phoneNumber),
                        "控制台（不传 scope）的角标照旧数全部（两条未读、latest 是全局最大）："
                                + consoleStatus.getAsJsonObject("quests"));

                JsonObject marked = post(base, "/api/quests/read", scoped(DEV, "all", true));
                check(marked.get("unread").getAsInt() == 0 && marked.get("marked").getAsInt() == 1,
                        "手机端「全部已读」只清自己那一条：" + marked);
                check(get(base, "/api/status").getAsJsonObject("quests").get("unread").getAsInt() == 1,
                        "控制台那条的未读没被手机端清掉（不跨会话写别人的状态）");

                foreign = post(base, "/api/quest", scoped(DEV, "id", consoleNumber));
                check(String.valueOf(foreign.get("error")).contains("不属于这个会话") && !foreign.has("texts"),
                        "POST /api/quest {id=控制台那条, scope:dev-…} 被如实拒绝：" + foreign);
                mine = post(base, "/api/quest", scoped(DEV, "id", phoneNumber));
                check(mine.has("texts") && !mine.get("texts").getAsJsonArray().isEmpty(),
                        "手机端打开自己那条照旧完整：" + mine.get("quest"));
                consoleOpen = post(base, "/api/quest", body("id", phoneNumber));
                check(consoleOpen.has("texts"), "控制台（不传 scope）照旧能打开任何一条：" + consoleOpen.get("quest"));
            }
        } finally {
            stub.stop(0);
        }
    }

    /** 一条指令（带/不带 scope 两种）。 */
    private static int command(String base, String command, String scope) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("command", command);
        if (scope != null) request.addProperty("scope", scope);
        JsonObject result = post(base, "/api/command", request);
        if (!result.has("quest")) throw new AssertionError("指令没有回执号：" + result);
        return result.get("quest").getAsInt();
    }

    /** 带 scope 的请求体：{@code scoped(DEV,"limit",20)} / {@code scoped(DEV,"id",5)} / {@code scoped(DEV,"all",true)}。 */
    private static JsonObject scoped(String scope, String key, Object value) {
        JsonObject body = new JsonObject();
        body.addProperty("scope", scope);
        if (key != null) {
            if (value instanceof Boolean bool) body.addProperty(key, bool);
            else if (value instanceof Number number) body.addProperty(key, number);
            else body.addProperty(key, String.valueOf(value));
        }
        return body;
    }

    private static JsonObject body(String key, int value) {
        JsonObject body = new JsonObject();
        body.addProperty(key, value);
        return body;
    }

    private static void waitEntry(String base, int number) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            JsonObject list = get(base, "/api/quests?limit=200");
            for (JsonElement node : list.getAsJsonArray("quests")) {
                JsonObject item = node.getAsJsonObject();
                if (item.get("number").getAsInt() == number && item.get("texts").getAsInt() > 0
                        && item.get("done").getAsBoolean()) return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("回执 #" + number + " 一直没有内容");
    }

    private static JsonObject get(String base, String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", "Bearer " + TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) throw new AssertionError("HTTP " + response.statusCode() + "：" + response.body());
        return Json.parse(response.body().isBlank() ? "{}" : response.body());
    }

    private static JsonObject post(String base, String path, JsonObject body) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString(), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) throw new AssertionError("HTTP " + response.statusCode() + "：" + response.body());
        return Json.parse(response.body().isBlank() ? "{}" : response.body());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    /** SD 的本机桩：够 Bot 启动、{@code .help} 与 {@code /api/status} 用，绝不连真的 Stable Diffusion。 */
    private static HttpServer sdStub() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = "{}";
            if (path.equals("/pixiko-bridge/v1/prompts"))
                body = "{\"positive\":\"base prompt\",\"negative\":\"bad\",\"source\":\"webui-live\",\"revision\":1,"
                        + "\"sampler_name\":\"Euler a\",\"styles\":[],\"width\":832,\"height\":1152,\"settings_initialized\":true}";
            else if (path.equals("/sdapi/v1/prompt-styles")) body = "[]";
            else if (path.equals("/sdapi/v1/loras")) body = "[]";
            else if (path.equals("/sdapi/v1/samplers")) body = "[{\"name\":\"Euler a\",\"aliases\":[]}]";
            else if (path.equals("/sdapi/v1/options")) body = "{\"sd_model_checkpoint\":\"Model A [aaaa]\"}";
            else if (path.equals("/sdapi/v1/sd-models")) body = "[{\"title\":\"Model A [aaaa]\"}]";
            else if (path.equals("/sdapi/v1/progress")) body = "{\"progress\":0,\"state\":{\"job\":\"\",\"job_count\":0}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    /** 建一条带 scope、且有文字的回执（索引层测试用，不经过指令通道）。 */
    private static Bot.WebCapture capture(String id, String command, Path root, String scope) {        Bot.WebCapture capture = new Bot.WebCapture(id, command, root.resolve("data/generated"), scope);
        capture.capture(Maps.text(command + " 的输出"));
        capture.finish();
        return capture;
    }

    private static Bot.WebCapture capture(String id, String command, Path root) {
        Bot.WebCapture capture = new Bot.WebCapture(id, command, root.resolve("data/generated"));
        capture.capture(Maps.text(command + " 的输出"));
        capture.finish();
        return capture;
    }

    private static JsonObject entry(JsonObject list, int index) {
        return list.getAsJsonArray("quests").get(index).getAsJsonObject();
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError("断言失败：" + detail);
    }
}

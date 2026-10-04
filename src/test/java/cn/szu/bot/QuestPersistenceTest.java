package cn.szu.bot;

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
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import cn.szu.bot.sd.SdClient;
import cn.szu.bot.web.WebUiServer;

/**
 * 网页任务回执**不再过期**：正文落盘（{@code data/quests/<number>.json}）、重启后读时回填、
 * 内存按条数淘汰但正文仍在磁盘上、坏/缺文件与写盘失败都只写日志。
 *
 * <p>覆盖：跑真指令产生回执 → 正文文件存在、UTF-8 无 BOM、字段与契约一致、图片只写引用；
 * 关掉 Bot 再用同一个根起一个（内存里没有 capture）→ {@code /api/quest?id=N} 仍给完整
 * texts/messages/images（{@code busy=false}、{@code expired=false}、{@code fromDisk=true}）；
 * {@code /api/quests} 每条 {@code expired} 都是 false、{@code retainedMinutes} 是 0；
 * 内存上限调小后淘汰的回执照样从磁盘读回；删掉/写坏正文文件只当作"没有正文"；
 * 把 {@code data/quests} 建成普通文件让落盘必失败，回执与列表接口照常可用。
 *
 * <p>全程只连回环地址（SD 是本机桩），不碰 QQ、不碰真实 SD，更不碰线上运行目录：
 * 所有读写都在 {@code Files.createTempDirectory} 出来的临时根目录里。
 */
public final class QuestPersistenceTest {
    private static final String TOKEN = "test-token-123456";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** 落盘格式的契约字段：一个都不能多、一个都不能少。 */
    private static final Set<String> BODY_KEYS = Set.of("version", "number", "command", "startedAt", "done",
            "texts", "images", "messages");
    private static final Bot.Sender SENDER = new Bot.Sender() {
        @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
            return CompletableFuture.completedFuture(new JsonObject());
        }
    };
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "quest-persistence").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        Path brokenRoot = Files.createTempDirectory(work, "case-");
        Path cappedRoot = Files.createTempDirectory(work, "case-");
        HttpServer stub = sdStub();
        try {
            Log.init(root);   // 日志落进临时根：下面要断言"写盘失败只写日志"
            int sdPort = stub.getAddress().getPort();
            int highest = persistAndRestart(root, sdPort);
            numberSequenceFromDisk(root, sdPort, highest);
            indexCapDeletesBodies(cappedRoot, sdPort);
            writeFailure(brokenRoot, sdPort);
        } finally {
            stub.stop(0);
            remove(root);
            remove(brokenRoot);
            remove(cappedRoot);
        }
        System.out.println("QuestPersistenceTest: " + checks + " assertions passed：正文落盘（UTF-8 无 BOM、"
                + "契约 8 字段、图片只写引用）、关停重启后 /api/quest 从磁盘回填完整正文（fromDisk/expired/busy）、"
                + "/api/quests 的 expired 恒为 false 且 retainedMinutes=0、内存按条数淘汰后仍能读回、"
                + "缺/坏正文文件只说'不在了'、索引上限裁掉的最旧那条连正文一起删、"
                + "写盘失败只写日志不影响 /api/quests、索引丢了按磁盘最大号发号");
    }

    // ------------------------------------------------------------------ 落盘 + 重启回填 + 内存淘汰

    /** @return 磁盘上出现过的最大任务号（给后面的发号用例用）。 */
    private static int persistAndRestart(Path root, int sdPort) throws Exception {
        int port = freePort();
        Settings settings = prepare(root, sdPort, port);
        SdClient client = new SdClient(root, sdJson(sdPort));
        int first, second;

        // ---------- 第一个 Bot：真指令产生回执，正文落盘 ----------
        try (Bot bot = new Bot(settings, client, SENDER)) {
            try (WebUiServer server = new WebUiServer(settings, bot)) {
                server.start();
                String base = url(port);

                // ① 一条真指令：正文落在 data/quests/<N>.json
                first = command(base, ".help");
                waitEntry(base, first);
                Path body = root.resolve("data/quests/" + first + ".json");
                check(awaitFile(body), "回执 #" + first + " 的正文落在 data/quests/" + first + ".json");
                byte[] bytes = Files.readAllBytes(body);
                check(!(bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                                && (bytes[2] & 0xFF) == 0xBF), "正文文件是 UTF-8 无 BOM");
                JsonObject stored = Json.parse(Files.readString(body, StandardCharsets.UTF_8));
                check(stored.keySet().equals(BODY_KEYS), "正文就是契约里的 8 个字段：" + stored.keySet());
                check(stored.get("version").getAsInt() == 1 && stored.get("number").getAsInt() == first
                                && stored.get("done").getAsBoolean(), "version/number/done 如实写入：" + stored.keySet());
                check(stored.get("command").getAsString().equals(".help"), "command 一并落盘：" + stored.get("command"));
                check(stored.get("startedAt").getAsString()
                                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                        "startedAt 是 UTC ISO-8601 带毫秒与 Z：" + stored.get("startedAt"));
                java.time.Instant.parse(stored.get("startedAt").getAsString());
                check(stored.getAsJsonArray("texts").size() >= 1 && !stored.getAsJsonArray("messages").isEmpty(),
                        "正文含 texts 与 messages：" + stored.getAsJsonArray("texts").size() + "/"
                                + stored.getAsJsonArray("messages").size());
                check(stored.getAsJsonArray("images").isEmpty(), "这条没有图片：images 是空数组");
                String firstText = stored.getAsJsonArray("texts").get(0).getAsString();
                check(firstText.contains("Pixiko"), "落盘的就是指令回执的文字：" + firstText);
                check(messagesContain(stored, firstText), "messages 里按顺序存着同一段文字");

                // ② 图片只写引用：给下一条回执补一张图，/api/quest 首读时正文再写一次
                JsonObject accepted = post(base, "/api/command", body("command", ".help"));
                String id = accepted.get("id").getAsString();
                second = accepted.get("quest").getAsInt();
                Bot.WebCapture live = bot.webCapture(id);
                check(live != null && live.number() == second, "拿到刚受理的那条回执对象");
                live.capture(image("data/generated/sample.png"));
                JsonObject read = post(base, "/api/quest", body("id", second));
                check(read.getAsJsonArray("images").size() == 1 && read.has("messages")
                                && !read.has("fromDisk"), "/api/quest 给内存里的完整正文，且不标 fromDisk");
                Path secondBody = root.resolve("data/quests/" + second + ".json");
                check(awaitFile(secondBody), "正在跑的回执首读时也落了盘：" + secondBody.getFileName());
                JsonObject storedSecond = Json.parse(Files.readString(secondBody, StandardCharsets.UTF_8));
                String file = storedSecond.getAsJsonArray("images").get(0).getAsJsonObject().get("file").getAsString();
                check(file.equals("data/generated/sample.png"), "images 里写的是 file 引用：" + file);
                check(Files.readString(secondBody, StandardCharsets.UTF_8).indexOf("base64") < 0,
                        "正文里没有图片字节（不写 base64）");
                check(messagesContain(storedSecond, "data/generated/sample.png"),
                        "messages 里也留着同一条图片引用");
            }
        }

        // ---------- 第二个 Bot：内存里没有 capture，正文从磁盘读回来 ----------
        try (Bot secondBot = new Bot(settings, client, SENDER)) {
            try (WebUiServer server = new WebUiServer(settings, secondBot)) {
                server.start();
                String base = url(port);

                // ③ 读时回填：完整 texts/messages/images
                JsonObject disk = post(base, "/api/quest", body("id", second));
                check(disk.get("quest").getAsInt() == second && disk.has("texts") && disk.has("images")
                                && disk.has("messages"), "重启后 /api/quest 仍给完整正文：" + disk.keySet());
                check(disk.get("fromDisk").getAsBoolean(), "重启后读出来的是磁盘副本：fromDisk=true");
                check(!disk.get("busy").getAsBoolean() && !disk.get("expired").getAsBoolean(),
                        "磁盘回填的 busy=false、expired=false");
                check(disk.has("id") && disk.has("command") && disk.has("done") && disk.has("closed")
                                && disk.has("ageMillis"), "字段名与 WebCapture.json() 一致，一个不少：" + disk.keySet());
                check(disk.get("command").getAsString().equals(".help"), "指令也跟着回来：" + disk.get("command"));
                check(disk.get("done").getAsBoolean() && disk.get("ageMillis").getAsLong() >= 0,
                        "done=true、ageMillis 是毫秒数：" + disk.get("ageMillis"));
                check(disk.getAsJsonArray("images").size() == 1
                                && disk.getAsJsonArray("images").get(0).getAsJsonObject().get("file").getAsString()
                                .equals("data/generated/sample.png"), "图片引用也在：" + disk.get("images"));
                check(!disk.getAsJsonArray("texts").isEmpty() && !disk.getAsJsonArray("messages").isEmpty(),
                        "文字与分组视图都在：" + disk.getAsJsonArray("texts").size() + "/"
                                + disk.getAsJsonArray("messages").size());
                check(disk.get("latest").getAsInt() >= first && disk.get("latest").getAsInt() > 0,
                        "latest 不为 0（内存空了也从索引来）：" + disk.get("latest"));

                // ④ 列表：expired 恒为 false、retainedMinutes 恒为 0
                JsonObject list = get(base, "/api/quests?limit=500");
                check(list.get("retainedMinutes").getAsInt() == 0, "retainedMinutes = 0（不过期）：" + list);
                check(list.get("total").getAsInt() >= 2 && list.get("latest").getAsInt() == second,
                        "重启后列表里两条都在：" + list.get("total"));
                boolean anyExpired = false, anyDiskBusy = false;
                for (JsonElement node : list.getAsJsonArray("quests")) {
                    JsonObject item = node.getAsJsonObject();
                    anyExpired |= item.get("expired").getAsBoolean();
                    anyDiskBusy |= item.get("busy").getAsBoolean();
                }
                check(!anyExpired && !anyDiskBusy, "列表里每一条 expired 都是 false、busy 都是 false");
                JsonObject newest = entry(get(base, "/api/quests?limit=1"), 0);
                check(!newest.get("expired").getAsBoolean() && !newest.get("busy").getAsBoolean(),
                        "limit=1 的最新那条同样 expired=false、busy=false：" + newest);

                // ⑤ 内存淘汰：上限调到 1，被淘汰的回执照样从磁盘读回完整正文
                secondBot.webCaptureLimit = 1;
                Bot.WebCapture dirty = secondBot.webCommand(settings.webScope(), java.util.List.of(".help"));
                awaitDone(dirty);
                Path dirtyBody = root.resolve("data/quests/" + dirty.number() + ".json");
                check(awaitFile(dirtyBody), "跑完时已经落过一次盘：#" + dirty.number());
                dirty.capture(image("data/generated/evicted.png"));   // 跑完之后才来的图：还只在内存里
                check(!Files.readString(dirtyBody, StandardCharsets.UTF_8).contains("evicted.png"),
                        "后补的图片引用此刻还没落盘（下面靠淘汰补齐）");
                secondBot.webCommand(settings.webScope(), java.util.List.of(".help"));
                secondBot.latestQuest();                              // 触发 pruneWebCaptures()
                JsonObject evicted = post(base, "/api/quest", body("id", dirty.number()));
                check(evicted.get("fromDisk").getAsBoolean() && !evicted.get("expired").getAsBoolean(),
                        "被淘汰的回执从磁盘读回（fromDisk=true）：#" + dirty.number());
                check(evicted.getAsJsonArray("images").size() == 1
                                && evicted.getAsJsonArray("images").get(0).getAsJsonObject().get("file").getAsString()
                                .equals("data/generated/evicted.png"),
                        "淘汰前一定先落盘：后补的图片引用也在：" + evicted.get("images"));
                JsonObject older = post(base, "/api/quest", body("id", second));
                check(older.get("fromDisk").getAsBoolean() && !older.getAsJsonArray("texts").isEmpty(),
                        "更早的回执被淘汰后照样读得回：" + older.get("quest"));
                JsonObject fresh = post(base, "/api/quest", body("id", 0));
                check(!fresh.has("fromDisk") && !fresh.has("error"), "最新一条还在内存里（不带错误）：" + fresh.keySet());
                secondBot.webCaptureLimit = Bot.DEFAULT_WEB_CAPTURE_LIMIT;

                // ⑥ 正文文件被删 / 被写坏：当作"没有正文"，说"不在了"，绝不抛异常
                Files.delete(root.resolve("data/quests/" + second + ".json"));
                JsonObject gone = post(base, "/api/quest", body("id", second));
                check(String.valueOf(gone.get("error")).contains("不在了"), "正文文件删掉后如实说「不在了」：" + gone);
                check(!gone.get("expired").getAsBoolean(), "缺正文也不再有过期语义：expired=false");
                int brokenNumber = second + 9000;
                Files.writeString(root.resolve("data/quests/" + brokenNumber + ".json"), "{这不是 JSON", StandardCharsets.UTF_8);
                JsonObject broken = post(base, "/api/quest", body("id", brokenNumber));
                check(String.valueOf(broken.get("error")).contains("不在了") && !broken.has("texts"),
                        "正文文件坏了也当作没有正文（只写日志）：" + broken);
            }
        }
        return Math.max(first, second);
    }

    // ------------------------------------------------------------------ 索引丢了也要接着磁盘最大号发号

    private static void numberSequenceFromDisk(Path root, int sdPort, int atLeast) throws Exception {
        Files.deleteIfExists(root.resolve("data/quests.json"));
        Settings settings = new Settings(root);
        SdClient client = new SdClient(root, sdJson(sdPort));
        int onDisk = maxBodyNumber(root);
        check(onDisk >= atLeast, "磁盘上的正文文件还在：" + onDisk + " >= " + atLeast);
        try (Bot bot = new Bot(settings, client, SENDER)) {
            check(bot.latestQuestBodyNumber() == onDisk, "启动时扫到磁盘最大号：" + bot.latestQuestBodyNumber());
            check(bot.webQuests(50).get("total").getAsInt() == 0, "索引文件没了列表从空开始（正文不受影响）");
            int next = bot.webCommand(settings.webScope(), java.util.List.of(".help")).number();
            check(next > onDisk, "新任务号接着磁盘最大号往后发：" + next + " > " + onDisk);
            check(bot.webQuest(next).has("texts"), "刚发出的回执照常能取到：" + next);
        }
    }

    // ------------------------------------------------------------------ 写盘失败只写日志

    private static void writeFailure(Path root, int sdPort) throws Exception {
        // data/quests 建成一个普通文件：正文一定写不进去（createDirectories 会失败）。
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/quests"), "not a directory", StandardCharsets.UTF_8);
        Log.init(root);
        int port = freePort();
        Settings settings = prepare(root, sdPort, port);
        SdClient client = new SdClient(root, sdJson(sdPort));
        try (Bot bot = new Bot(settings, client, SENDER)) {
            try (WebUiServer server = new WebUiServer(settings, bot)) {
                server.start();
                String base = url(port);
                int number = command(base, ".help");
                waitEntry(base, number);
                JsonObject list = get(base, "/api/quests?limit=50");
                check(list.get("total").getAsInt() >= 1 && list.get("retainedMinutes").getAsInt() == 0,
                        "写盘失败不影响 /api/quests：" + list.get("total"));
                check(entry(list, 0).get("number").getAsInt() == number
                                && !entry(list, 0).get("expired").getAsBoolean(), "写盘失败也不影响列表内容：" + entry(list, 0));
                JsonObject quest = post(base, "/api/quest", body("id", number));
                check(quest.getAsJsonArray("texts").size() >= 1 && !quest.has("fromDisk"),
                        "写盘失败不影响回执本身（内存里照常给）：" + quest.keySet());
                check(Files.isRegularFile(root.resolve("data/quests")),
                        "data/quests 还是那个普通文件（没有被换成目录）");
                check(logContains(root, "回执正文写入失败"), "写盘失败只写一条 WARN 日志");
            }
        }
    }

    // ------------------------------------------------------------------ 索引上限：裁掉的最旧那条连正文一起删

    /**
     * 索引超过上限时只裁最旧的一批，并且**同时删掉**它们的 {@code data/quests/<n>.json}；
     * 上限以内的正文文件一个都不动。
     */
    private static void indexCapDeletesBodies(Path root, int sdPort) throws Exception {
        Files.createDirectories(root.resolve("data/quests"));
        JsonArray quests = new JsonArray();
        for (int number = 1; number <= QuestIndex.MAX_ENTRIES + 1; number++) {
            JsonObject node = new JsonObject();
            node.addProperty("number", number);
            node.addProperty("command", ".help #" + number);
            node.addProperty("startedAt", QuestIndex.stamp(System.currentTimeMillis() - 60000L));
            node.addProperty("summary", "第 " + number + " 条摘要");
            node.addProperty("texts", 1);
            node.addProperty("images", 0);
            node.addProperty("done", true);
            node.addProperty("unread", false);
            node.addProperty("read", true);
            quests.add(node);
        }
        JsonObject stored = new JsonObject();
        stored.addProperty("version", 1);
        stored.add("quests", quests);
        Json.atomicWrite(root.resolve("data/quests.json"), stored);
        // 最旧那条（会被裁掉）与最新那条（会留下）各放一份正文，看看谁被删。
        Files.writeString(root.resolve("data/quests/1.json"), bodyFile(1, "最旧的正文"), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/quests/" + QuestIndex.MAX_ENTRIES + ".json"),
                bodyFile(QuestIndex.MAX_ENTRIES, "留下来的正文"), StandardCharsets.UTF_8);

        Settings settings = prepare(root, sdPort, freePort());
        SdClient client = new SdClient(root, sdJson(sdPort));
        try (Bot bot = new Bot(settings, client, SENDER)) {
            check(!Files.exists(root.resolve("data/quests/1.json")),
                    "索引裁掉的最旧那条：正文文件一起删掉（不留孤儿）");
            check(Files.isRegularFile(root.resolve("data/quests/" + QuestIndex.MAX_ENTRIES + ".json")),
                    "上限以内的正文文件一个都不动");
            check(bot.webQuests(5000).get("total").getAsInt() == QuestIndex.MAX_ENTRIES,
                    "索引上限 " + QuestIndex.MAX_ENTRIES + " 条：" + bot.webQuests(5000).get("total"));
            check(String.valueOf(bot.webQuest(1).get("error")).contains("不在了"),
                    "被裁掉的任务号如实说「不在了」：" + bot.webQuest(1));
            JsonObject kept = bot.webQuest(QuestIndex.MAX_ENTRIES);
            check(kept.get("fromDisk").getAsBoolean() && !kept.getAsJsonArray("texts").isEmpty(),
                    "留下来的任务号照样从磁盘读到完整正文：" + kept.keySet());
        }
    }

    /** 手写一份符合落盘格式的回执正文。 */
    private static String bodyFile(int number, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("version", 1);
        body.addProperty("number", number);
        body.addProperty("command", ".help #" + number);
        body.addProperty("startedAt", QuestIndex.stamp(System.currentTimeMillis() - 60000L));
        body.addProperty("done", true);
        JsonArray texts = new JsonArray();
        texts.add(text);
        body.add("texts", texts);
        body.add("images", new JsonArray());
        JsonObject piece = new JsonObject();
        piece.addProperty("type", "text");
        piece.addProperty("text", text);
        JsonArray group = new JsonArray();
        group.add(piece);
        JsonArray messages = new JsonArray();
        messages.add(group);
        body.add("messages", messages);
        return Json.GSON.toJson(body);
    }

    // ------------------------------------------------------------------ 小工具

    /** 临时根 + config.json + 网页端口；绝不碰真实运行目录。 */
    private static Settings prepare(Path root, int sdPort, int port) throws Exception {
        Files.createDirectories(root.resolve("data/generated"));
        JsonObject config = new JsonObject();
        config.add("sd", sdJson(sdPort));
        JsonObject progen = new JsonObject();
        progen.addProperty("api_base", "http://127.0.0.1:" + sdPort);
        config.add("progen", progen);
        JsonObject webui = new JsonObject();
        webui.addProperty("enabled", true);
        webui.addProperty("host", "127.0.0.1");
        webui.addProperty("port", 0);
        webui.addProperty("access_token", TOKEN);
        config.add("webui", webui);
        Json.atomicWrite(root.resolve("config.json"), config);
        Files.writeString(root.resolve("data/deepseek-api-key.txt"), "test-key-not-real\n");
        Settings settings = new Settings(root);
        settings.webSetting("port", new com.google.gson.JsonPrimitive(port));
        return settings;
    }

    private static JsonObject sdJson(int sdPort) {
        JsonObject sd = new JsonObject();
        sd.addProperty("base_url", "http://127.0.0.1:" + sdPort);
        return sd;
    }

    private static String url(int port) { return "http://127.0.0.1:" + port; }

    /** 一条指令：返回它的任务号。 */
    private static int command(String base, String command) throws Exception {
        JsonObject result = post(base, "/api/command", body("command", command));
        if (!result.has("quest")) throw new AssertionError("指令没有回执号：" + result);
        return result.get("quest").getAsInt();
    }

    /** 等这条回执的摘要里有内容且已跑完（走列表接口，不会像 /api/quest 那样把它标成已读）。 */
    private static JsonObject waitEntry(String base, int number) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        JsonObject last = null;
        while (System.currentTimeMillis() < deadline) {
            JsonObject list = get(base, "/api/quests?limit=500");
            last = null;
            for (JsonElement node : list.getAsJsonArray("quests")) {
                if (node.getAsJsonObject().get("number").getAsInt() == number) { last = node.getAsJsonObject(); break; }
            }
            if (last != null && last.get("texts").getAsInt() > 0 && last.get("done").getAsBoolean()) return last;
            Thread.sleep(100);
        }
        throw new AssertionError("回执 #" + number + " 一直没有内容：" + last);
    }

    /** 等这条回执的指令执行体跑完（之后 webIO 那次收尾落盘也要写完，否则不返回）。 */
    private static void awaitDone(Bot.WebCapture capture) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline && !capture.done()) Thread.sleep(50);
        if (!capture.done()) throw new AssertionError("回执 #" + capture.number() + " 一直没跑完");
        Thread.sleep(300);   // 收尾那次落盘写完再返回：之后补的图片才是"只在内存里"
    }

    /** 等正文文件出现（正文写入发生在索引更新之后，可能刚好差一瞬）。 */
    private static boolean awaitFile(Path file) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(file)) return true;
            Thread.sleep(50);
        }
        return Files.isRegularFile(file);
    }

    /** messages（分组数组）里有没有这一条文字/图片引用。 */
    private static boolean messagesContain(JsonObject stored, String text) {
        for (JsonElement group : stored.getAsJsonArray("messages")) {
            if (!group.isJsonArray()) continue;
            for (JsonElement piece : group.getAsJsonArray()) {
                if (!piece.isJsonObject()) continue;
                JsonObject node = piece.getAsJsonObject();
                if (node.has("text") && text.equals(node.get("text").getAsString())) return true;
                if (node.has("file") && text.equals(node.get("file").getAsString())) return true;
            }
        }
        return false;
    }

    /** 临时根 logs/*.log 里有没有这条 WARN（{@link Log#init} 已经把日志指向这里）。 */
    private static boolean logContains(Path root, String text) throws Exception {
        Path logs = root.resolve("logs");
        if (!Files.isDirectory(logs)) return false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (var files = Files.list(logs)) {
                for (Path file : files.toList()) {
                    if (Files.readString(file, StandardCharsets.UTF_8).contains(text)) return true;
                }
            }
            Thread.sleep(100);
        }
        return false;
    }

    /** 磁盘上 data/quests/<n>.json 里的最大任务号。 */
    private static int maxBodyNumber(Path root) throws Exception {
        int latest = 0;
        Path dir = root.resolve("data/quests");
        if (!Files.isDirectory(dir)) return 0;
        try (var files = Files.list(dir)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".json")) continue;
                try { latest = Math.max(latest, Integer.parseInt(name.substring(0, name.length() - 5))); }
                catch (NumberFormatException ignored) { /* 不是任务号的文件忽略 */ }
            }
        }
        return latest;
    }

    private static JsonObject entry(JsonObject list, int index) {
        return list.getAsJsonArray("quests").get(index).getAsJsonObject();
    }

    private static JsonObject body(String key, String value) {
        JsonObject body = new JsonObject();
        body.addProperty(key, value);
        return body;
    }

    private static JsonObject body(String key, int value) {
        JsonObject body = new JsonObject();
        body.addProperty(key, value);
        return body;
    }

    private static JsonArray image(String file) {
        JsonObject data = new JsonObject();
        data.addProperty("file", file);
        JsonObject segment = new JsonObject();
        segment.addProperty("type", "image");
        segment.add("data", data);
        JsonArray segments = new JsonArray();
        segments.add(segment);
        return segments;
    }

    private static JsonObject get(String base, String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", "Bearer " + TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) throw new AssertionError("HTTP " + response.statusCode() + "：" + response.body());
        return Json.parse(response.body().isBlank() ? "{}" : response.body());
    }

    /** POST 一个 JSON 请求；HTTP 4xx/5xx 直接算失败（回执接口用 error 字段表达"不在了"，不是 HTTP 错误）。 */
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

    private static void remove(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { /* 临时目录删不掉不影响结果 */ }
    }

    /** SD 的本机桩：够 Bot 启动与 .help 这类本机指令用，绝不连真的 Stable Diffusion。 */
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

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
}

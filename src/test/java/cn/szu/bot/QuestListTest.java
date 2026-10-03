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
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import cn.szu.bot.sd.SdClient;
import cn.szu.bot.web.WebUiServer;

/**
 * 网页「任务回执列表」：摘要索引（{@code GET /api/quests}）、未读标记（{@code POST /api/quests/read}）、
 * 用 {@code /api/quest} 打开一条就变已读、{@code /api/status} 里的 {@code quests} 小块，
 * 以及索引落盘（data/quests.json）后重启还在与 200 条上限。
 *
 * <p>全程只连回环地址（SD 是本机桩），不碰 QQ、不碰真实 SD，更不碰线上运行目录：
 * 所有读写都在 {@code Files.createTempDirectory} 出来的临时根目录里。
 */
public final class QuestListTest {
    private static final String TOKEN = "test-token-123456";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Set<String> ENTRY_KEYS = Set.of("number", "command", "startedAt", "ageMillis", "done",
            "busy", "unread", "expired", "texts", "images", "summary");
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "quest-list").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        HttpServer stub = sdStub();
        try {
            indexSemantics(root);
            endToEnd(root, stub.getAddress().getPort());
        } finally {
            stub.stop(0);
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
        System.out.println("QuestListTest: " + checks + " assertions passed：列表降序与 limit 钳制、摘要单行截 120 字、"
                + "有内容才算未读且已读不回退、markRead/markAllRead//api/quest 打开即已读、落盘重载后未读还在、"
                + "200 条上限丢最旧、/api/quests 契约字段、/api/status 的 quests");
    }

    // ------------------------------------------------------------------ 索引本身（不跑指令，纯语义）

    private static void indexSemantics(Path parent) throws Exception {
        Path root = Files.createTempDirectory(parent, "index-");
        Path file = root.resolve("data/quests.json");
        QuestIndex index = new QuestIndex();

        // ① 还没有内容的回执也进索引，但不算未读
        Bot.WebCapture first = new Bot.WebCapture("capture-a", ".style list", root.resolve("data/generated"));
        index.observe(first);
        JsonObject list = index.json(50, 30);
        check(list.getAsJsonArray("quests").size() == 1, "没有内容的回执也进索引");
        check(list.get("unread").getAsInt() == 0 && list.get("latest").getAsInt() == first.number()
                        && list.get("total").getAsInt() == 1 && list.get("retainedMinutes").getAsInt() == 30,
                "顶层 unread/latest/total/retainedMinutes 如实回报：" + list);
        JsonObject item = entry(list, 0);
        check(!item.get("unread").getAsBoolean(), "还没有文字就不算未读");
        check(item.get("texts").getAsInt() == 0 && item.get("images").getAsInt() == 0
                        && item.get("summary").getAsString().isEmpty(), "没有文字时条数 0、摘要空：" + item);

        // ② 第一段文字进来才算未读，摘要取第一段、压成单行、截 120 字
        String longText = "样式列表（共 19 个）：\n" + "甲".repeat(400) + "\n第三行";
        first.capture(Maps.text(longText));
        first.capture(image("data/generated/sample.png"));
        first.finish();
        index.observe(first);
        list = index.json(50, 30);
        item = entry(list, 0);
        String summary = item.get("summary").getAsString();
        check(list.get("unread").getAsInt() == 1 && item.get("unread").getAsBoolean(), "第一次有文字就记为未读");
        check(summary.startsWith("样式列表（共 19 个）： 甲"), "摘要取第一段文字并压成单行：" + summary);
        check(summary.length() == 120, "摘要正好截到 120 个字符（实际 " + summary.length() + "）");
        check(!summary.contains("\n") && !summary.contains("\r"), "摘要里没有换行");
        check(item.get("texts").getAsInt() == 1 && item.get("images").getAsInt() == 1
                        && item.get("done").getAsBoolean() && !item.get("busy").getAsBoolean()
                        && !item.get("expired").getAsBoolean(), "条数、跑完、还在内存里都如实回报：" + item);
        check(item.get("startedAt").getAsString().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                "startedAt 是 UTC ISO-8601 带毫秒与 Z：" + item.get("startedAt"));
        Instant.parse(item.get("startedAt").getAsString());
        check(item.get("ageMillis").getAsLong() >= 0, "ageMillis 是毫秒数：" + item.get("ageMillis"));
        check(item.keySet().equals(ENTRY_KEYS), "每条只有契约里的 11 个字段：" + item.keySet());
        check(!list.has("texts") && !list.has("messages") && !item.has("messages"),
                "列表里一条正文数组都没有");

        // ③ 第二段文字不改摘要，也不把已读变回未读
        index.markRead(java.util.List.of(first.number()));
        check(index.unread() == 0, "标记已读后未读归零");
        check(index.markRead(java.util.List.of(first.number())) == 0, "已读的再标一次不计入 marked");
        check(index.markRead(java.util.List.of(first.number() + 777)) == 0, "索引里没有的号忽略");
        first.capture(Maps.text("第二段文字"));
        index.observe(first);
        item = entry(index.json(50, 30), 0);
        check(item.get("texts").getAsInt() == 2 && item.get("summary").getAsString().equals(summary),
                "摘要永远取第一段文字：" + item.get("summary"));
        check(!item.get("unread").getAsBoolean(), "已读之后再来文字也不会变回未读");

        // ④ 降序 + limit 钳制（1..200）
        Bot.WebCapture second = new Bot.WebCapture("capture-b", ".rg 1girl", root.resolve("data/generated"));
        second.capture(Maps.text("出图任务已提交"));
        index.observe(second);
        list = index.json(50, 30);
        check(list.getAsJsonArray("quests").size() == 2
                        && entry(list, 0).get("number").getAsInt() == second.number()
                        && entry(list, 1).get("number").getAsInt() == first.number(),
                "最新的一条排在最前面（number 降序）：" + list);
        check(list.get("unread").getAsInt() == 1, "只有新的那条未读：" + list.get("unread"));
        for (int limit : new int[]{0, -9, 1}) {
            JsonObject clamped = index.json(limit, 30);
            check(clamped.getAsJsonArray("quests").size() == 1,
                    "limit=" + limit + " 钳到 1 条（实际 " + clamped.getAsJsonArray("quests").size() + "）");
            check(entry(clamped, 0).get("number").getAsInt() == second.number(), "limit 小的时候留的是最新的");
        }
        check(index.json(1000, 30).getAsJsonArray("quests").size() == 2, "超大 limit 也把现有的给全");
        check(index.markAllRead() == 1 && index.unread() == 0, "markAllRead 只数真正从没读变已读的那一条");

        // ⑤ 落盘 → 重载（未读状态、摘要、已读都不丢），文件是 UTF-8 无 BOM
        Bot.WebCapture third = new Bot.WebCapture("capture-c", ".help", root.resolve("data/generated"));
        third.capture(Maps.text("帮助：.style list"));
        third.finish();
        index.observe(third);
        index.save(file);
        check(Files.isRegularFile(file), "索引落在 data/quests.json");
        byte[] bytes = Files.readAllBytes(file);
        check(!(bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF),
                "落盘文件是 UTF-8 无 BOM");
        QuestIndex reloaded = new QuestIndex();
        reloaded.load(file);
        JsonObject after = reloaded.json(50, 30);
        check(after.get("total").getAsInt() == 3 && after.get("latest").getAsInt() == third.number(),
                "重载后条数与最大号都还在：" + after);
        check(after.get("unread").getAsInt() == 1 && entry(after, 0).get("unread").getAsBoolean(),
                "重载后未读状态还在：" + after.get("unread"));
        check(!entry(after, 1).get("unread").getAsBoolean() && !entry(after, 2).get("unread").getAsBoolean(),
                "已读的那几条重载后不会变回未读");
        check(entry(after, 0).get("summary").getAsString().equals("帮助：.style list")
                        && entry(after, 0).get("done").getAsBoolean(), "摘要与 done 也一起持久化：" + entry(after, 0));
        boolean allExpired = true, anyBusy = false;
        for (JsonElement node : after.getAsJsonArray("quests")) {
            allExpired &= node.getAsJsonObject().get("expired").getAsBoolean();
            anyBusy |= node.getAsJsonObject().get("busy").getAsBoolean();
        }
        check(allExpired && !anyBusy, "磁盘读回来的条目内容都不在内存里：expired=true、busy=false");

        // ⑥ 超过 200 条丢最旧的
        QuestIndex many = new QuestIndex();
        java.util.List<Bot.WebCapture> captures = new java.util.ArrayList<>();
        for (int i = 0; i < 250; i++) {
            Bot.WebCapture capture = new Bot.WebCapture("bulk-" + i, ".help #" + i, root.resolve("data/generated"));
            capture.capture(Maps.text("第 " + i + " 条回执"));
            capture.finish();
            captures.add(capture);
            many.observe(capture);
        }
        JsonObject capped = many.json(500, 30);
        check(capped.getAsJsonArray("quests").size() == 200 && capped.get("total").getAsInt() == 200,
                "索引最多留 200 条：" + capped.get("total"));
        check(entry(capped, 0).get("number").getAsInt() == captures.get(249).number()
                        && entry(capped, 199).get("number").getAsInt() == captures.get(50).number(),
                "超出上限时丢的是最旧的 50 条");
        Path bulkFile = root.resolve("data/bulk-quests.json");
        many.save(bulkFile);
        QuestIndex bulkReloaded = new QuestIndex();
        bulkReloaded.load(bulkFile);
        check(bulkReloaded.json(500, 30).get("total").getAsInt() == 200, "重载后仍然是 200 条");

        // ⑦ 读坏了当空索引，绝不抛异常
        Path broken = root.resolve("data/broken-quests.json");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "{这不是 JSON", StandardCharsets.UTF_8);
        QuestIndex brokenIndex = new QuestIndex();
        brokenIndex.load(broken);
        check(brokenIndex.json(50, 30).get("total").getAsInt() == 0, "索引读坏了就当空索引（只写日志）");
    }

    // ------------------------------------------------------------------ 真实 Bot + 网页接口

    private static void endToEnd(Path root, int sdPort) throws Exception {
        Files.createDirectories(root.resolve("data/generated"));
        int port = freePort();
        JsonObject config = new JsonObject();
        JsonObject sd = new JsonObject();
        sd.addProperty("base_url", "http://127.0.0.1:" + sdPort);
        config.add("sd", sd);
        JsonObject progen = new JsonObject();
        progen.addProperty("api_base", "http://127.0.0.1:" + sdPort);
        config.add("progen", progen);
        JsonObject webui = new JsonObject();
        webui.addProperty("enabled", true);
        webui.addProperty("host", "127.0.0.1");
        webui.addProperty("port", 0);
        webui.addProperty("access_token", TOKEN);
        config.add("webui", webui);
        Files.createDirectories(root.resolve("data"));
        Json.atomicWrite(root.resolve("config.json"), config);
        Files.writeString(root.resolve("data/deepseek-api-key.txt"), "test-key-not-real\n");

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

        int fourth;
        try (Bot bot = new Bot(settings, client, sender)) {
            try (WebUiServer server = new WebUiServer(settings, bot)) {
                server.start();
                String base = "http://127.0.0.1:" + port;

                JsonObject empty = get(base, "/api/quests");
                check(empty.getAsJsonArray("quests").isEmpty() && empty.get("unread").getAsInt() == 0
                                && empty.get("latest").getAsInt() == 0 && empty.get("total").getAsInt() == 0,
                        "还没有任务时列表是空的、计数都是 0：" + empty);
                check(empty.get("retainedMinutes").getAsInt() == 30, "retainedMinutes = 30");

                // ① 一条真指令：有内容才未读
                int first = command(base, ".help");
                JsonObject firstEntry = waitEntry(base, first);
                JsonObject list = get(base, "/api/quests");
                JsonObject item = entry(list, 0);
                check(item.get("number").getAsInt() == first && item.get("command").getAsString().equals(".help"),
                        "列表里是刚才那条指令：" + item);
                check(item.get("unread").getAsBoolean() && !item.get("expired").getAsBoolean()
                                && item.get("texts").getAsInt() >= 1 && item.get("images").getAsInt() == 0
                                && !item.get("summary").getAsString().isBlank() && !item.get("busy").getAsBoolean(),
                        "有内容的第一条就是未读、内容还在内存里：" + firstEntry);
                check(item.keySet().equals(ENTRY_KEYS), "每条只有契约里的 11 个字段：" + item.keySet());
                check(list.get("unread").getAsInt() == 1 && list.get("latest").getAsInt() == first
                                && list.get("total").getAsInt() == 1,
                        "顶层未读/最新/总数正确：" + list);
                check(!item.has("messages") && item.get("texts").isJsonPrimitive(),
                        "列表里 texts 是条数而不是正文数组：" + item);

                JsonObject state = get(base, "/api/status");
                check(state.has("quests") && state.getAsJsonObject("quests").get("unread").getAsInt() == 1
                                && state.getAsJsonObject("quests").get("latest").getAsInt() == first,
                        "/api/status 里有 quests.unread/latest：" + state.get("quests"));

                // ② 用 /api/quest 打开 → 变已读，而且不会自己变回未读
                JsonObject opened = post(base, "/api/quest", body("id", first));
                check(opened.get("quest").getAsInt() == first && opened.has("texts"), "/api/quest 照旧能取到回执");
                list = get(base, "/api/quests");
                check(list.get("unread").getAsInt() == 0 && !entry(list, 0).get("unread").getAsBoolean(),
                        "/api/quest 打开过就变成已读：" + list);

                // ③ /api/quests/read：不存在的号忽略；标一条；重复标不计
                JsonObject marked = post(base, "/api/quests/read", numbers(first + 5000));
                check(marked.get("marked").getAsInt() == 0 && marked.get("unread").getAsInt() == 0,
                        "不存在的任务号忽略，marked=0：" + marked);
                int second = command(base, ".help");
                waitEntry(base, second);
                check(get(base, "/api/quests").get("unread").getAsInt() == 1, "新回执又算未读");
                marked = post(base, "/api/quests/read", numbers(second));
                check(marked.get("marked").getAsInt() == 1 && marked.get("unread").getAsInt() == 0,
                        "按任务号标一条：marked=1：" + marked);
                marked = post(base, "/api/quests/read", numbers(second));
                check(marked.get("marked").getAsInt() == 0, "已经读过的再标一次不计入 marked：" + marked);
                check(Files.isRegularFile(root.resolve("data/quests.json")), "标记已读后索引已经落盘");

                // ④ all=true 全部标为已读
                int third = command(base, ".help");
                waitEntry(base, third);
                check(get(base, "/api/quests").get("unread").getAsInt() == 1, "第三条未读");
                marked = post(base, "/api/quests/read", body("all", true));
                check(marked.get("marked").getAsInt() == 1 && marked.get("unread").getAsInt() == 0,
                        "all=true 把剩下的未读全标掉：" + marked);

                // ⑤ 降序与 limit 钳制（1..200）
                list = get(base, "/api/quests");
                check(list.get("total").getAsInt() == 3 && list.getAsJsonArray("quests").size() == 3,
                        "三条回执都在列表里：" + list.get("total"));
                int previous = Integer.MAX_VALUE;
                for (JsonElement node : list.getAsJsonArray("quests")) {
                    int number = node.getAsJsonObject().get("number").getAsInt();
                    check(number < previous, "列表按任务号降序：" + number + " < " + previous);
                    previous = number;
                }
                check(entry(get(base, "/api/quests?limit=1"), 0).get("number").getAsInt() == third, "limit=1 只给最新的那条");
                check(get(base, "/api/quests?limit=0").getAsJsonArray("quests").size() == 1, "limit=0 钳到 1");
                check(get(base, "/api/quests?limit=-9").getAsJsonArray("quests").size() == 1, "负数 limit 钳到 1");
                check(get(base, "/api/quests?limit=1000").getAsJsonArray("quests").size() == 3, "超大 limit 不会报错");
                check(get(base, "/api/quests?limit=abc").getAsJsonArray("quests").size() == 3, "认不出的 limit 用默认 50");
                check(status(base, "/api/quests/read") == 400, "只读的已读标记接口不允许 GET：" + status(base, "/api/quests/read"));

                // ⑥ 留一条未读，关停后新起一个 Bot 从同一个根读回来
                fourth = command(base, ".help");
                waitEntry(base, fourth);
                check(get(base, "/api/quests").get("unread").getAsInt() == 1, "关停前留一条未读");
            }
        }

        try (Bot second = new Bot(settings, client, sender)) {
            JsonObject list = second.webQuests(50);
            check(list.get("total").getAsInt() == 4 && list.get("latest").getAsInt() == fourth,
                    "重启后索引里的 4 条都还在，latest 还在：" + list);
            check(list.get("unread").getAsInt() == 1, "重启后未读状态还在：" + list.get("unread"));
            boolean allExpired = true;
            for (JsonElement node : list.getAsJsonArray("quests")) {
                JsonObject entry = node.getAsJsonObject();
                allExpired &= entry.get("expired").getAsBoolean();
                check(!entry.get("busy").getAsBoolean(), "重启后没有内容在内存里，busy=false");
            }
            check(allExpired, "重启后每条都标成内容已过期：expired=true");
            JsonObject counts = second.webStatus().getAsJsonObject("quests");
            check(counts.get("unread").getAsInt() == 1 && counts.get("latest").getAsInt() == fourth,
                    "重启后 /api/status 的 quests 也从索引来：" + counts);
            JsonObject marked = second.webMarkQuestsRead(null, true);
            check(marked.get("marked").getAsInt() == 1 && marked.get("unread").getAsInt() == 0,
                    "重启后也能把剩下的标为已读：" + marked);
            // 正文过期之后直接打开（/quest#N）：照样给摘要 + 一句"已过期"，而不是冷冰冰一句报错。
            JsonObject stale = second.webQuest(fourth);
            check(stale.get("expired").getAsBoolean() && !stale.get("summary").getAsString().isBlank()
                            && String.valueOf(stale.get("error")).contains("过期")
                            && stale.get("unread").getAsBoolean() == false,
                    "过期回执按摘要返回（expired=true + summary + 已过期说明）：" + stale);
            JsonObject unknown = second.webQuest(fourth + 5000);
            check(!unknown.has("summary") && String.valueOf(unknown.get("error")).contains("不在了"),
                    "索引里没有的号仍然如实说'不在了'：" + unknown);
        }
    }

    // ------------------------------------------------------------------ 小工具

    /** 一条指令：返回它的任务号。 */
    private static int command(String base, String command) throws Exception {
        JsonObject result = post(base, "/api/command", body("command", command));
        if (!result.has("quest")) throw new AssertionError("指令没有回执号：" + result);
        return result.get("quest").getAsInt();
    }

    /**
     * 等这条回执的摘要里有内容（走列表接口，不会像 /api/quest 那样把它标成已读）。
     */
    private static JsonObject waitEntry(String base, int number) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        JsonObject last = null;
        while (System.currentTimeMillis() < deadline) {
            JsonObject list = get(base, "/api/quests?limit=200");
            last = null;
            for (JsonElement node : list.getAsJsonArray("quests")) {
                if (node.getAsJsonObject().get("number").getAsInt() == number) { last = node.getAsJsonObject(); break; }
            }
            if (last != null && last.get("texts").getAsInt() > 0 && last.get("done").getAsBoolean()) return last;
            Thread.sleep(100);
        }
        throw new AssertionError("回执 #" + number + " 一直没有内容：" + last);
    }

    private static JsonObject entry(JsonObject list, int index) {
        return list.getAsJsonArray("quests").get(index).getAsJsonObject();
    }

    private static JsonObject body(String key, String value) {
        JsonObject body = new JsonObject();
        body.addProperty(key, value);
        return body;
    }

    private static JsonObject body(String key, boolean value) {
        JsonObject body = new JsonObject();
        body.addProperty(key, value);
        return body;
    }

    private static JsonObject body(String key, int value) {
        JsonObject body = new JsonObject();
        body.addProperty(key, value);
        return body;
    }

    private static JsonObject numbers(int... values) {
        JsonArray array = new JsonArray();
        for (int value : values) array.add(value);
        JsonObject body = new JsonObject();
        body.add("numbers", array);
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

    private static int status(String base, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", "Bearer " + TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).statusCode();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
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

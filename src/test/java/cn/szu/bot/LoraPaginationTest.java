package cn.szu.bot;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.sd.SdClient;

/**
 * Civitai 搜索翻页：分页协议、页码解析、本页编号语义、越界报错。
 *
 * <p>桩响应模拟真机的形状（实测 2026-10：带 {@code query} 时 {@code page} 会被服务端拒绝，
 * 只能用 {@code cursor}，而且关键词搜索的 cursor 就是偏移量），因此桩**只认 cursor**：
 * 只要实现里给 query 搜索塞了 {@code page=}，桩就照真机那样返回 400，测试会立刻变红。
 */
public final class LoraPaginationTest {
    private static final String API = "https://civitai.com";
    /** 桩里一共这么多条结果（够翻出一页不足 10 条的末页）。 */
    private static final int TOTAL = 13;
    private static int assertions;
    private static List<String> urls;

    public static void main(String[] args) throws Exception {
        paginatedSearch();
        pageSizeIsConfigurable();
        searchArgumentParsing();
        pageReceiptsAndOutOfRange();
        currentPageNumbering();
        System.out.println("LoraPaginationTest: " + assertions + " assertions passed: cursor paging, hasMore, configurable page size, page arguments, out-of-range message, per-page numbering.");
    }

    /** 桩：只认 cursor（偏移量）与 limit；出现 page 参数照真机的错拒掉。 */
    private static CivitaiClient stub(Path root, JsonObject config) throws IOException {
        urls = Collections.synchronizedList(new ArrayList<>());
        return new CivitaiClient(root, config, (uri, headers, timeout) -> {
            urls.add(uri.toString());
            String raw = uri.getRawQuery() == null ? "" : uri.getRawQuery();
            if (raw.contains("page=")) {
                String error = "{\"error\":\"Cannot use page param with query search. Use cursor-based pagination.\"}";
                return new CivitaiClient.Response(400, Map.of(), new ByteArrayInputStream(error.getBytes(StandardCharsets.UTF_8)));
            }
            int offset = 0, limit = 10;
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length != 2) continue;
                if (parts[0].equals("cursor")) offset = Integer.parseInt(parts[1]);
                if (parts[0].equals("limit")) limit = Integer.parseInt(parts[1]);
            }
            JsonArray items = new JsonArray();
            for (int index = offset; index < Math.min(offset + limit, TOTAL); index++) items.add(item(index));
            JsonObject metadata = new JsonObject();
            // 真机：非空页永远带 nextCursor（= 偏移量 + limit），空页的 metadata 是空的。
            if (!items.isEmpty()) metadata.addProperty("nextCursor", String.valueOf(offset + limit));
            JsonObject body = new JsonObject();
            body.add("items", items);
            body.add("metadata", metadata);
            return new CivitaiClient.Response(200, Map.of("Content-Type", List.of("application/json")),
                    new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)));
        }, host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")}, "");
    }

    /** 一条搜索结果（形状与真机一致：modelVersions[0].images[0].url 是 Civitai 图床）。 */
    private static JsonObject item(int index) {
        JsonObject image = new JsonObject();
        image.addProperty("url", "https://image.civitai.com/x/" + index + ".jpeg");
        JsonArray images = new JsonArray();
        images.add(image);
        JsonObject version = new JsonObject();
        version.addProperty("id", 1000 + index);
        version.addProperty("baseModel", "Illustrious");
        version.add("images", images);
        JsonArray versions = new JsonArray();
        versions.add(version);
        JsonObject model = new JsonObject();
        model.addProperty("id", 100 + index);
        model.addProperty("name", "模型 " + index);
        model.addProperty("type", "LORA");
        model.add("modelVersions", versions);
        return model;
    }

    private static void paginatedSearch() throws Exception {
        Path root = tempRoot("pagination-");
        try {
            JsonObject config = new JsonObject();
            config.addProperty("lora_dir", root.resolve("loras").toString());
            config.addProperty("base_url", API);
            CivitaiClient client = stub(root, config);
            CivitaiClient.SearchPage first = client.search("tsumugi", 1);
            equal(1, first.page(), "第一页页码是 1");
            equal(10, first.count(), "第一页 10 条");
            check(first.hasMore(), "第 1 页后面还有内容（探针命中第 11 条）：" + first);
            check(!first.totalPagesKnown() && first.totalPages() == -1, "Civitai 关键词搜索不返回总数，如实标成未知：" + first);
            equal(2, first.nextPage(), "下一页页码");
            equal("模型 0", first.results().get(0).name(), "第一页第一条");

            CivitaiClient.SearchPage second = client.search("tsumugi", 2);
            equal(2, second.page(), "第二页页码是 2");
            equal(3, second.count(), "第二页只剩 3 条");
            check(!second.hasMore(), "第 2 页之后没有了（探针 offset=20 为空）：" + second);
            equal("模型 10", second.results().get(0).name(), "第二页第一条来自偏移量 10");
            equal("模型 12", second.results().get(2).name(), "第二页最后一条");

            CivitaiClient.SearchPage beyond = client.search("tsumugi", 3);
            equal(3, beyond.page(), "越界页仍如实报出请求的页码");
            equal(0, beyond.count(), "越界页没有结果");
            check(!beyond.hasMore(), "越界页没有下一页");

            check(urls.stream().anyMatch(url -> url.contains("cursor=10")), "第 2 页用 cursor=10（偏移量）请求：" + urls);
            check(urls.stream().noneMatch(url -> url.contains("page=")), "任何时候都不给 query 搜索带 page 参数（真机会 400）：" + urls);
            check(urls.stream().anyMatch(url -> url.contains("limit=1&query=")), "hasMore 用 limit=1 的探针请求判定：" + urls);
        } finally { delete(root); }
    }

    /** 每页条数来自 config.json，且有上限、乱填不崩。 */
    private static void pageSizeIsConfigurable() throws Exception {
        Path root = tempRoot("pagesize-");
        try {
            equal(10, CivitaiClient.DEFAULT_PAGE_SIZE, "默认每页 10 条");
            equal(10, stub(root, config(root, null)).pageSize(), "默认每页条数");
            equal(10, stub(root, config(root, null)).search("tsumugi", 1).pageSize(), "返回里带上每页条数");

            CivitaiClient three = stub(root, config(root, 3));
            equal(3, three.pageSize(), "civitai.search_page_size 生效");
            CivitaiClient.SearchPage page = three.search("tsumugi", 2);
            equal(3, page.count(), "每页 3 条时第 2 页 3 条");
            equal("模型 3", page.results().get(0).name(), "偏移量按每页条数换算");
            check(page.hasMore(), "还有第 3 页");
            check(urls.stream().anyMatch(url -> url.contains("cursor=3")), "第 2 页的偏移量 = (2-1)*3：" + urls);

            equal(CivitaiClient.MAX_PAGE_SIZE, stub(root, config(root, 999)).pageSize(), "每页条数有上限，不能一次拉几百条");
            equal(CivitaiClient.DEFAULT_PAGE_SIZE, stub(root, config(root, "十")).pageSize(), "乱填每页条数退回默认值而不是崩掉");
            equal(1, stub(root, config(root, 0)).pageSize(), "每页条数最小 1 条");

            expect(() -> stub(root, config(root, null)).search("tsumugi", 0), "页码从 1 开始", "第 0 页被拒");
            expect(() -> stub(root, config(root, null)).search("", 1), "搜索词", "空搜索词被拒");
        } finally { delete(root); }
    }

    private static JsonObject config(Path root, Object pageSize) {
        JsonObject config = new JsonObject();
        config.addProperty("lora_dir", root.resolve("loras").toString());
        config.addProperty("base_url", API);
        if (pageSize instanceof Integer value) config.addProperty("search_page_size", value);
        if (pageSize instanceof String value) config.addProperty("search_page_size", value);
        return config;
    }

    /** `.lora search <词> [页码]` 的解析规则（含关键词本身以数字结尾时的引号写法）。 */
    private static void searchArgumentParsing() {
        Bot.LoraSearchQuery plain = Bot.parseLoraSearch("tsumugi");
        equal("tsumugi", plain.words(), "没有页码时关键词照旧");
        equal(1, plain.page(), "没有页码时默认第 1 页");
        Bot.LoraSearchQuery paged = Bot.parseLoraSearch("summer pockets tsumugi 3");
        equal("summer pockets tsumugi", paged.words(), "页码从结尾取，关键词可以带空格");
        equal(3, paged.page(), "页码解析");
        Bot.LoraSearchQuery quoted = Bot.parseLoraSearch("\"milf 2\"");
        equal("milf 2", quoted.words(), "整段引号包起来=关键词（不会被当成页码）");
        equal(1, quoted.page(), "引号写法只看第 1 页");
        Bot.LoraSearchQuery numeric = Bot.parseLoraSearch("milf 2");
        equal("milf", numeric.words(), "没有引号时结尾数字按页码解释（帮助里写清了这个规则）");
        equal(2, numeric.page(), "结尾数字当页码");
        expect(() -> Bot.parseLoraSearch("tsumugi 0"), "页码从 1 开始", "第 0 页被拒");
        expect(() -> Bot.parseLoraSearch("   "), "用法", "空参数给出用法");
        expect(() -> Bot.parseLoraSearch(""), "用法", "缺参数给出用法");
        equal("tsumugi", Bot.quoteLoraSearchWords("tsumugi"), "普通关键词不用引号");
        equal("\"summer pockets\"", Bot.quoteLoraSearchWords("summer pockets"), "含空格的关键词加引号（否则页码会被当搜索词）");
        equal("\"milf 2\"", Bot.quoteLoraSearchWords("milf 2"), "以数字结尾的关键词也加引号");
    }

    /** 回执里的「第 X 页 / 下一页」与越界文案。 */
    private static void pageReceiptsAndOutOfRange() {
        var second = new CivitaiClient.SearchPage(List.of(result(1), result(2)), "summer pockets tsumugi", 2, 10, true, -1, false);
        String lines = Bot.searchPageLines(second);
        check(lines.contains("第 2 页"), "回执写清第几页：" + lines);
        check(!lines.contains("/"), "总数未知时不编成「第 2/? 页」：" + lines);
        check(lines.contains("本次返回 2 项（每页 10 条）"), "回执写清本页条数与每页条数：" + lines);
        check(lines.contains("本页编号：.lora download #N 指本页第 N 条"), "回执明确本页编号语义：" + lines);
        check(lines.contains("下一页：.lora search \"summer pockets tsumugi\" 3"), "下一页命令带引号、页码是 3：" + lines);

        var last = new CivitaiClient.SearchPage(List.of(result(1)), "tsumugi", 4, 10, false, -1, false);
        check(Bot.searchPageLines(last).contains("这已经是最后一页"), "末页不再提示下一页：" + Bot.searchPageLines(last));

        var known = new CivitaiClient.SearchPage(List.of(result(1)), "tsumugi", 2, 10, true, 5, true);
        check(Bot.searchPageLines(known).contains("第 2/5 页"), "知道总页数时写成 第 X/Y 页：" + Bot.searchPageLines(known));

        String beyond = Bot.searchPageOutOfRange(new CivitaiClient.SearchPage(List.of(), "milf 2", 9, 10, false, -1, false));
        check(beyond.contains("第 9 页没有内容"), "越界页如实说第几页没有内容：" + beyond);
        check(beyond.contains("每页 10 条"), "越界页说明每页条数，便于推算：" + beyond);
        check(beyond.contains("回到第一页：.lora search \"milf 2\" 1"), "越界页给出回第一页的写法：" + beyond);
        check(beyond.contains("编号没有被改动"), "越界翻页不动用户手上的本页编号：" + beyond);

        JsonObject body = new JsonObject();
        equal(1, Bot.webSearchPage(body), "网页请求体没有 page 时按第 1 页");
        body.addProperty("page", "3");
        equal(3, Bot.webSearchPage(body), "网页 page 参数支持字符串写法");
        body.addProperty("page", "abc");
        expect(() -> Bot.webSearchPage(body), "page 必须是正整数", "网页乱填 page 要报错");
    }

    /** 本页编号语义：`#N` 指当前这一页的第 N 条（不是全部结果的第 N 条）。 */
    private static void currentPageNumbering() throws Exception {
        Path root = tempRoot("numbering-");
        try {
            JsonObject civitai = new JsonObject();
            civitai.addProperty("lora_dir", root.resolve("loras").toString());
            civitai.addProperty("base_url", API);
            JsonObject config = new JsonObject();
            config.add("civitai", civitai);
            Json.atomicWrite(root.resolve("config.json"), config);
            AtomicReference<String> downloaded = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            List<String> replies = new CopyOnWriteArrayList<>();
            Settings settings = new Settings(root);
            try (Bot bot = new Bot(settings, new SdClient(root, new JsonObject()), (event, segments) -> {
                replies.add(Bot.messageText(segments)); return CompletableFuture.completedFuture(null);
            }, (url, stage, meter) -> { downloaded.set(url); started.countDown(); throw new IOException("test download intercepted"); })) {
                JsonObject event = Json.parse("{\"post_type\":\"message\",\"self_id\":1,\"message_type\":\"group\",\"group_id\":2,\"user_id\":3,\"raw_message\":\"x\"}");
                List<CivitaiClient.SearchResult> page2 = List.of(result(11), result(12));
                bot.registerLoraSearch(event, new CivitaiClient.SearchPage(page2, "tsumugi", 2, 10, true, -1, false));

                JsonObject context = bot.selectionContext(event);
                JsonObject list = context.getAsJsonObject("last_list");
                equal("civitai", list.get("kind").getAsString(), "刚搜索过：编号列表是 Civitai 结果");
                equal(2, list.getAsJsonArray("items").size(), "列表只放本页条目（不是全部结果）");
                check(list.getAsJsonArray("items").get(0).getAsString().startsWith("#1 "), "本页编号从 1 开始：" + list);
                JsonObject info = context.getAsJsonObject("civitai_page");
                equal(2, info.get("page").getAsInt(), "上下文里带上页码");
                check(info.get("hint").getAsString().contains("本页编号"), "上下文明确 #N 是本页编号：" + info);
                equal(page2.get(0).url(), context.getAsJsonArray("civitai").get(0).getAsString(), "规划层看到的 Civitai 列表就是本页");

                JsonObject command = Json.parse("{\"post_type\":\"message\",\"self_id\":1,\"message_type\":\"group\",\"group_id\":2,\"user_id\":3,\"raw_message\":\".lora download #1\"}");
                bot.accept(command);
                check(started.await(5, TimeUnit.SECONDS), "本页 #1 能开始下载");
                equal(page2.get(0).url(), downloaded.get(), "#1 = 本页第 1 条（不是全部结果的第 1 条）");

                downloaded.set(null);
                JsonObject over = Json.parse("{\"post_type\":\"message\",\"self_id\":1,\"message_type\":\"group\",\"group_id\":2,\"user_id\":3,\"raw_message\":\".lora download #3\"}");
                bot.accept(over);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                String errorText = replies.stream().filter(text -> text.contains("本页")).findFirst().orElse(null);
                while (errorText == null && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                    errorText = replies.stream().filter(text -> text.contains("本页")).findFirst().orElse(null);
                }
                check(errorText != null && errorText.contains("只有 2 项"), "越界的本页编号如实说本页只有几项：" + replies);
                check(downloaded.get() == null, "越界编号不会下载任何东西");
            }
        } finally { delete(root); }
    }

    private static CivitaiClient.SearchResult result(int index) {
        return new CivitaiClient.SearchResult(100 + index, 1000 + index, "模型 " + index, "Illustrious",
                "https://image.civitai.com/x/" + index + ".jpeg", 5, List.of("word"), 10.0, false);
    }

    private static Path tempRoot(String prefix) throws IOException {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "lora-pagination-tests").toAbsolutePath();
        Files.createDirectories(work);
        return Files.createTempDirectory(work, prefix);
    }

    private static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }

    private interface Operation { void run() throws Exception; }
    private static void expect(Operation operation, String fragment, String message) {
        assertions++;
        try { operation.run(); throw new AssertionError(message + "：预期抛异常"); }
        catch (AssertionError failed) { throw failed; }
        catch (Exception expected) {
            if (!String.valueOf(expected.getMessage()).contains(fragment))
                throw new AssertionError(message + "：报错文案里没有「" + fragment + "」：" + expected.getMessage());
        }
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}

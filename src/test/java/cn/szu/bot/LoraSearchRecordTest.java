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
 * QQ 端 {@code .lora query/search} 的结果发送方式**与合并转发节点的版式**：
 * 一次搜索合并成一条聊天记录（每条结果一个节点），传输层不支持合并转发时保持逐条发送并如实说明，
 * 合并转发失败时回退成逐条发送——结果一条都不能丢。
 *
 * <p>节点正文的版式规则（一个结果一个节点）：首行是「#序号 名称」，名称/底模/触发词**原样、不截断**；
 * 数字栏「下载量｜点赞｜大小｜发布」有的才写、空值整行不写；触发词一条一行、过长的按它自己的逗号换行；
 * 页面地址单独成行；NSFW 有自己的一行「标记」；正文里不出现内部键名（modelId/versionId/nsfw…）与裸 JSON。
 *
 * <p>全程用手造的 {@link CivitaiClient.SearchPage} 与假传输层，不连 Civitai、不发 QQ 消息。
 */
public final class LoraSearchRecordTest {
    private static int assertions;
    private static final int COUNT = 3;
    /** 144 个字的名字：任何"省略号截断"都会在这里露馅。 */
    private static final String LONG_NAME = "超长模型名字".repeat(24);
    /** Civitai 真给过的超长触发词（{@code .lora query chatgpt龙娘} 第 6 条，126 字、末尾是 purple shoes,）：被省略号吃过一次。 */
    private static final String LONG_WORD = "purple_hair, short_hair, hair_horns, braided_hair_rings, hoodie, hood_down, "
            + "white_hoodie, zipper, purple shorts, purple shoes,";
    /** 一个"没有任何可断点"的超长触发词：只能整行给出，一个字都不许切。 */
    private static final String UNBREAKABLE_WORD = "y".repeat(300);
    /** 每个节点里都不许出现的东西：内部键名、裸 JSON 碎片、省略号（项目硬规矩）。 */
    private static final List<String> FORBIDDEN = List.of(
            "modelId", "versionId", "trainedWords", "baseModel", "sizeKb", "downloadCount", "thumbsUp", "publishedAt",
            "cover", "nsfw", "{", "}", "[", "]", "\"", "\\", "…");
    /** 正文里允许出现的字段名（每个最多一行）。 */
    private static final List<String> LABELS = List.of("底模：", "下载量：", "点赞：", "大小：", "发布：", "触发词（", "标记：", "页面：");

    public static void main(String[] args) throws Exception {
        oneRecordPerSearchWithOneNodePerResult();
        nodeTextIsCompleteAndHumanReadable();
        nodeLayoutIsStructuredFieldByField();
        emptyFieldsNeverOccupyALine();
        internalKeysAndRawJsonNeverAppear();
        longNamesAndTriggerWordsAreNeverCut();
        oldMessyShapesNeverComeBack();
        unsupportedTransportSendsOneByOneWithReason();
        failedRecordFallsBackWithoutLosingResults();
        textFallbackKeepsTheSameLayoutWithoutLosingAnything();
        numberingMatchesDownloadCommand();
        nodeCountFollowsResultCountAndThePageCap();
        check(assertions >= 80, "断言条数应 ≥ 80，实际 " + assertions);
        System.out.println("LoraSearchRecordTest: " + assertions + " assertions passed: one sendRecord per search, "
                + "node count = result count, per-node structured layout (first line/one line per field/no empty field/"
                + "no internal key/no raw JSON/url on its own line/trigger words verbatim & never cut), "
                + "per-item fallback without support and after failure (same layout), numbering matches .lora download #N.");
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

    /** ② 每个节点的正文：完整的模型名（超长也不截断）、底模、数字栏、触发词、页面地址，逐字段对上。 */
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
                equal(Bot.loraSearchNodeText(at + 1, result), text, "节点正文就是 loraSearchNodeText（第 " + (at + 1) + " 条）");
                check(text.startsWith("#" + (at + 1) + " "), "第 " + (at + 1) + " 个节点以本页编号开头：" + text);
                check(text.contains(result.name()), "节点里有模型名：" + text);
                check(text.contains("底模：Illustrious"), "节点里有底模：" + text);
                check(text.contains("下载量：" + String.format(Locale.ROOT, "%,d", result.downloads())), "节点里有下载量：" + text);
                check(text.contains("点赞：" + String.format(Locale.ROOT, "%,d", result.likes())), "节点里有点赞：" + text);
                check(text.contains("大小：1.5 MB"), "大小按 MB 写：" + text);
                check(text.contains("发布：2026-06-03"), "节点里有版本发布日期：" + text);
                check(text.contains("触发词（2 条）：\ntrigger one\ntrigger two"), "触发词一条一行（不再用顿号拼成一行）：" + text);
                check(text.contains(result.url()), "节点里有该结果的页面地址：" + text);
                check(!text.contains("…") && !text.contains("|") && !text.startsWith("{") && !text.contains("\":"),
                        "节点是逐行纯文本，不是 JSON/表格，也没有截断：" + text);
            }
            check(LONG_NAME.codePointCount(0, LONG_NAME.length()) > 100, "夹具的超长名确实超过 100 字：" + LONG_NAME.length());
            equal("#1 " + LONG_NAME, Bot.loraSearchNodeText(1, page.results().get(0)).split("\n", 2)[0], "超长模型名完整保留、首行就是它");
            check(!Bot.messageText(record.get(0)).contains("（NSFW 标记）"), "非 NSFW 结果不贴标记");
            check(Bot.messageText(record.get(1)).contains("标记：NSFW（成人内容）"), "NSFW 结果如实标记、单独一行：" + Bot.messageText(record.get(1)));
            check(!Bot.messageText(record.get(1)).contains("底模：Illustrious（NSFW 标记）"), "NSFW 标记不再贴在底模行尾");
            // 封面：有封面的节点 = 正文 + 封面图；没有封面的节点只有正文，不假装有图、也不留空行。
            equal(2, record.get(0).size(), "有封面的节点 = 正文 + 封面");
            equal("image", record.get(0).get(1).getAsJsonObject().get("type").getAsString(), "节点第二段是图片");
            equal(page.results().get(0).cover(), Json.str(Json.obj(record.get(0).get(1).getAsJsonObject(), "data"), "file", ""),
                    "节点里的封面就是该结果的封面地址");
            equal(1, record.get(2).size(), "没有封面的节点只有正文");
            equal("1.5 MB", Bot.loraSearchSize(1536.5), "大小换算成 MB");
            equal("512 KB", Bot.loraSearchSize(512.4), "小于 1 MB 写 KB");
            equal("2.0 GB", Bot.loraSearchSize(2 * 1024 * 1024 + 512), "大于 1 GB 写 GB");
            equal("", Bot.loraSearchSize(0), "大小未知就不写这一项");
        }
    }

    /** ③ 逐字段的结构化断言：首行、字段行数、数字栏、触发词组、页面行（对全部夹具结果逐个跑一遍）。 */
    private static void nodeLayoutIsStructuredFieldByField() {
        List<CivitaiClient.SearchResult> results = layoutResults();
        for (int at = 0; at < results.size(); at++) {
            CivitaiClient.SearchResult result = results.get(at);
            int number = at + 1;
            NodeView node = NodeView.of(Bot.loraSearchNodeText(number, result));
            String label = "夹具第 " + number + " 个节点";
            equal("#" + number + " " + Bot.loraSearchName(result.name()), node.first(), label + "：首行是「#序号 名称」");
            check(node.lines().size() >= 2, label + "：不只有首行");
            for (int line = 0; line < node.lines().size(); line++) {
                check(!node.lines().get(line).isBlank(), label + "：第 " + (line + 1) + " 行不是空行");
                equal(node.lines().get(line).strip(), node.lines().get(line), label + "：第 " + (line + 1) + " 行没有首尾空白");
            }
            for (String name : LABELS) check(node.count(name) <= 1, label + "：字段「" + name + "」最多一行，实际 " + node.count(name));
            equal(1, node.count("页面："), label + "：页面只出现一次");
            equal("页面：" + result.url(), node.lines().get(node.lines().size() - 1), label + "：最后一行是页面地址");
            equal(1, occurrences(node.text(), result.url()), label + "：地址整段只出现一次（不重复显示）");
            check(!node.first().contains(result.url()), label + "：地址不挤在首行里（单独成行、可复制）");
            assertMetrics(node, result, label);
            assertTriggerBlock(node, result, label);
        }
    }

    /** ④ 空字段不占行：没有底模/下载量/点赞/大小/发布/触发词就没有那一行，也不写"未标注"。 */
    private static void emptyFieldsNeverOccupyALine() {
        List<CivitaiClient.SearchResult> results = layoutResults();
        for (int at = 0; at < results.size(); at++) {
            CivitaiClient.SearchResult result = results.get(at);
            NodeView node = NodeView.of(Bot.loraSearchNodeText(at + 1, result));
            String label = "夹具第 " + (at + 1) + " 个节点";
            equal(!Bot.loraSearchBaseModel(result.baseModel()).isEmpty(), node.has("底模："), label + "：底模有没有与数据一致");
            equal(result.downloads() > 0, node.has("下载量："), label + "：下载量为 0 就不占行");
            equal(result.likes() > 0, node.has("点赞："), label + "：点赞为 0 就不占行");
            equal(!Bot.loraSearchSize(result.sizeKb()).isEmpty(), node.has("大小："), label + "：大小未知就不占行");
            equal(!Bot.loraSearchDate(result.publishedAt()).isEmpty(), node.has("发布："), label + "：日期拿不到就不占行");
            equal(!Bot.loraSearchWords(result.trainedWords()).isEmpty(), node.has("触发词（"), label + "：没有触发词就没有触发词行");
            equal(result.nsfw(), node.has("标记：NSFW（成人内容）"), label + "：NSFW 标记有值才有一行");
            check(!node.text().contains("底模：未标注") && !node.text().contains("底模：未知"), label + "：不写「未标注/未知」占位");
            check(!node.text().contains("底模：\n"), label + "：底模后面不是空的");
            check(!node.text().contains("下载量：\n") && !node.text().contains("点赞：\n"), label + "：数字栏不出现空值");
            check(!node.text().contains("触发词（0 条）"), label + "：没有触发词时不写空触发词组");
            check(!node.text().contains("页面：\n"), label + "：页面后面一定有地址");
            check(!node.text().endsWith("\n"), label + "：结尾不留空行");
        }
        // 空白触发词（Civitai 偶尔给空串/纯空格）按"没有"处理，不占行。
        CivitaiClient.SearchResult blank = new CivitaiClient.SearchResult(9, 9, "空白触发词", "", "",
                0, List.of("   ", ""), 0, false, 0, "");
        NodeView node = NodeView.of(Bot.loraSearchNodeText(1, blank));
        equal(2, node.lines().size(), "触发词只有空白项时节点只剩首行与页面：" + node.text());
        equal(0, Bot.loraSearchWords(List.of("  ", "")).size(), "空白触发词被丢掉");
    }

    /** ⑤ 节点里不出现内部键名、不出现裸 JSON/CSS 式子、不出现省略号。 */
    private static void internalKeysAndRawJsonNeverAppear() {
        List<CivitaiClient.SearchResult> results = layoutResults();
        for (int at = 0; at < results.size(); at++) {
            String text = Bot.loraSearchNodeText(at + 1, results.get(at));
            String label = "夹具第 " + (at + 1) + " 个节点";
            for (String forbidden : FORBIDDEN) check(!text.contains(forbidden), label + "：不出现「" + forbidden + "」：" + text);
            check(!text.contains("\t"), label + "：没有制表符（手机上看就是错位）");
            // 页面地址自己带 ?modelVersionId=…，所以"key=value"的检查跳过页面那一行。
            for (String line : NodeView.of(text).lines()) {
                if (line.startsWith("页面：")) continue;
                check(!line.matches(".*[A-Za-z_]+\\s*=.*"), label + "：这一行没有 key=value 这种内部字段写法：" + line);
            }
            int http = occurrences(text, "http");
            equal(1, http, label + "：整段只有页面地址那一个 http（第 " + http + " 处）");
            for (String line : NodeView.of(text).lines())
                if (line.contains("http")) equal("页面：" + results.get(at).url(), line, label + "：带地址的那一行整行就是页面行");
        }
    }

    /** ⑥ 名称与触发词原样、不截断：超长名、超长词、含中文与特殊字符的词都逐字出现。 */
    private static void longNamesAndTriggerWordsAreNeverCut() {
        List<CivitaiClient.SearchResult> results = layoutResults();
        for (int at = 0; at < results.size(); at++) {
            CivitaiClient.SearchResult result = results.get(at);
            String text = Bot.loraSearchNodeText(at + 1, result);
            String label = "夹具第 " + (at + 1) + " 个节点";
            for (String word : Bot.loraSearchWords(result.trainedWords()))
                equal(0, occurrences(word, "…"), label + "：触发词原文没有省略号：" + word);
            check(!text.contains("…"), label + "：节点里没有任何省略号");
            // 把换行去掉之后，每个触发词都必须逐字出现在正文里（换行只是排版，绝不改字）。
            String flat = text.replace("\n", "").replace(" ", "");
            for (String word : Bot.loraSearchWords(result.trainedWords()))
                check(flat.contains(word.replace("\n", "").replace(" ", "")), label + "：触发词一字不少：" + word);
            if (!result.trainedWords().isEmpty()) check(text.contains(Bot.loraSearchName(result.name())), label + "：名字完整出现");
        }
        CivitaiClient.SearchResult longName = new CivitaiClient.SearchResult(1, 1, LONG_NAME, "Illustrious",
                "", 1, List.of(), 0, false, 0, "");
        String text = Bot.loraSearchNodeText(1, longName);
        equal(144, LONG_NAME.codePointCount(0, LONG_NAME.length()), "超长名确实是 144 字");
        check(text.contains(LONG_NAME), "144 字的名字一字不少：" + text.split("\n", 2)[0].length());
        CivitaiClient.SearchResult longWord = new CivitaiClient.SearchResult(2, 2, "超长触发词", "Illustrious",
                "", 1, List.of(LONG_WORD), 0, false, 0, "");
        String trigger = Bot.loraSearchNodeText(2, longWord);
        check(trigger.contains(LONG_WORD.substring(0, 100)), "126 字的触发词前半段原样保留");
        check(trigger.contains("purple shoes,"), "126 字的触发词末尾 purple shoes, 没有被省略号吃掉");
        check(trigger.contains("purple shorts,"), "126 字的触发词在它自己的逗号处换行");
        check(!trigger.contains("…"), "长触发词不出现省略号");
        CivitaiClient.SearchResult unbreakable = new CivitaiClient.SearchResult(3, 3, "没有断点的词", "Illustrious",
                "", 1, List.of(UNBREAKABLE_WORD), 0, false, 0, "");
        String whole = Bot.loraSearchNodeText(3, unbreakable);
        check(whole.contains(UNBREAKABLE_WORD), "300 字且没有可断点的触发词整行给出（不硬切）");
        equal(1, Bot.loraSearchTriggerLines(UNBREAKABLE_WORD).size(), "没有可断点 → 仍然只有一行");
        equal(2, Bot.loraSearchTriggerLines(LONG_WORD).size(), "126 字的词按逗号断成两行");
        for (String line : Bot.loraSearchTriggerLines(LONG_WORD))
            check(line.codePointCount(0, line.length()) <= Bot.LORA_SEARCH_WORD_LINE_CHARS, "断出来的行不超过软上限：" + line.length());
    }

    /** ⑦ 旧版式里"一团糟"的那些形态逐个断言不再复现（每条都对应现状还原里的一条）。 */
    private static void oldMessyShapesNeverComeBack() {
        List<CivitaiClient.SearchResult> results = layoutResults();
        for (int at = 0; at < results.size(); at++) {
            CivitaiClient.SearchResult result = results.get(at);
            String text = Bot.loraSearchNodeText(at + 1, result);
            String label = "夹具第 " + (at + 1) + " 个节点";
            check(!text.contains("（NSFW 标记）"), label + "：不把 NSFW 标记贴在底模行尾");
            List<String> words = Bot.loraSearchWords(result.trainedWords());
            if (words.size() > 1) check(!text.contains(String.join("、", words)), label + "：旧版「词1、词2」的写法不复现");
            check(!text.contains(",、") && !text.contains("、,"), label + "：英文逗号不再紧挨顿号");
            boolean inTriggerBlock = false;
            for (String line : NodeView.of(text).lines()) {
                if (line.startsWith("触发词（")) { inTriggerBlock = true; continue; }
                if (line.startsWith("页面：")) inTriggerBlock = false;
                if (inTriggerBlock) check(!line.contains("、"), label + "：触发词正文里不再用顿号做分隔：" + line);
            }
            check(!text.contains("底模：未标注"), label + "：空底模不再写「未标注」占一行");
            check(!text.contains("页面：") || text.contains("页面：" + result.url()), label + "：页面行就是完整地址");
            int pageLine = text.indexOf("页面：" + result.url());
            equal(text.length() - ("页面：" + result.url()).length(), pageLine, label + "：页面是整段最后一行");
            for (String line : NodeView.of(text).lines())
                check(!line.contains("｜｜") && !line.startsWith("｜") && !line.endsWith("｜"),
                        label + "：数字栏不出现空段/重复竖线：" + line);
        }
        equal("下载量：12｜大小：512 KB", Bot.loraSearchMetrics(layoutResults().get(6)),
                "只有下载量与大小时数字栏就是这两项（空值不占位）");
        equal("", Bot.loraSearchMetrics(layoutResults().get(7)), "什么都没有时数字栏是空串（整行不写）");
        List<String> ten = Bot.loraSearchWords(layoutResults().get(8).trainedWords());
        equal(10, ten.size(), "旧版「最多留 8 条」的上限不再存在（这里 10 条一条不少）");
        String tenText = Bot.loraSearchNodeText(9, layoutResults().get(8));
        for (String word : ten) check(tenText.contains("\n" + word + "\n") || tenText.endsWith("\n" + word + "\n"), "十条触发词逐条在正文里：" + word);
        equal("2026-06-03", Bot.loraSearchDate("2026-06-03T12:00:00.000Z"), "ISO 时间取日期");
        equal("", Bot.loraSearchDate("not-a-date"), "日期形状不对就整项不写");
        equal("", Bot.loraSearchDate(null), "没有日期就不写");
        equal("", Bot.loraSearchBaseModel("未知"), "解析层占位「未知」按没有处理");
        equal("", Bot.loraSearchBaseModel("  "), "空白底模按没有处理");
        equal("Illustrious", Bot.loraSearchBaseModel(" Illustrious "), "底模原文保留（只去首尾空白）");
        equal("名字里有 换行", Bot.loraSearchName("名字里有\n换行"), "名字里的换行压成空格（否则能伪装成字段行）");
        equal("未命名", Bot.loraSearchName("   "), "没有名字就用解析层同一个词「未命名」");
    }

    /** ⑧ supportsRecord()=false：保持逐条发送，并如实说一句为什么。 */
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
                check(results.stream().anyMatch(text -> text.equals(Bot.loraSearchNodeText(index + 1, page.results().get(index)))),
                        "逐条发送的第 " + (index + 1) + " 条与合并版版式完全一致：" + results);
            }
            String notice = sender.noticeText();
            check(notice.contains("不支持合并转发") && notice.contains("已逐条发送"),
                    "如实说明这个传输层不支持合并转发、已逐条发送：" + notice);
        }
    }

    /** ⑨ sendRecord 抛错：回退逐条发送、结果一个不少、排版不变、留一行 warn 日志。 */
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

    /** ⑩ 逐条发送中途失败（常见：封面发不出去）：余下条目改成一条文字，编号不变、**版式与合并版一致**。 */
    private static void textFallbackKeepsTheSameLayoutWithoutLosingAnything() throws Exception {
        PlainSender sender = new PlainSender();
        sender.failWhenTextStartsWith = "#2 ";
        try (Fixture fixture = new Fixture(sender)) {
            CivitaiClient.SearchPage page = page();
            fixture.bot.sendLoraSearchResults(fixture.event, page);
            String notice = sender.notices().stream().filter(text -> text.contains("封面发送失败")).findFirst().orElse("");
            check(notice.startsWith("封面发送失败，余下条目以文字给出"), "第 2 条发不出去时如实说明并给出余下条目：" + notice);
            check(notice.contains(Bot.loraSearchNodeText(2, page.results().get(1))), "文字兜底里第 2 条用的是同一个版式");
            check(notice.contains(Bot.loraSearchNodeText(3, page.results().get(2))), "文字兜底里第 3 条用的是同一个版式");
            check(notice.contains("页面：" + page.results().get(1).url()) && notice.contains("页面：" + page.results().get(2).url()),
                    "文字兜底里每条都有可复制的页面地址：" + notice);
            check(!notice.contains("#1 "), "已经从第 2 条起给文字，不回退已经发出去的第 1 条");
            equal(1, sender.resultSends(), "第 2 条失败后不再逐条重试发送");
        }
    }

    /** ⑪ 合并记录里的编号与 {@code .lora download #N} 仍然是同一份本页列表。 */
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

    /** ⑫ 节点数 = 结果条数（页头/页脚不做成额外节点），并且一页 50 条（既有上限）就是 50 个节点。 */
    private static void nodeCountFollowsResultCountAndThePageCap() throws Exception {
        for (int count : new int[]{1, 2, 50}) {
            List<CivitaiClient.SearchResult> results = new ArrayList<>();
            for (int index = 0; index < count; index++) results.add(result(index % COUNT));
            CivitaiClient.SearchPage page = new CivitaiClient.SearchPage(results, "tsumugi", 1, 50, false, -1, false);
            RecordingSender sender = new RecordingSender();
            try (Fixture fixture = new Fixture(sender)) {
                fixture.bot.sendLoraSearchResults(fixture.event, page);
                equal(1, sender.records.get(), count + " 条结果只发一条聊天记录");
                equal(count, sender.nodes.get(0).size(), count + " 条结果 = " + count + " 个节点（页头/页脚不占节点）");
                for (int at = 0; at < count; at++)
                    check(Bot.messageText(sender.nodes.get(0).get(at)).startsWith("#" + (at + 1) + " "),
                            "第 " + (at + 1) + " 个节点编号连续");
            }
        }
    }

    /** 数字栏：有的才写、顺序固定、每段都是「中文名：值」、竖线只出现在这一行。 */
    private static void assertMetrics(NodeView node, CivitaiClient.SearchResult result, String label) {
        String metrics = metricsLine(node);
        boolean expected = result.downloads() > 0 || result.likes() > 0
                || !Bot.loraSearchSize(result.sizeKb()).isEmpty() || !Bot.loraSearchDate(result.publishedAt()).isEmpty();
        equal(expected, !metrics.isEmpty(), label + "：数字栏有没有与数据对得上");
        for (int at = 1; at < node.lines().size(); at++) {
            String line = node.lines().get(at);
            if (line.contains("｜")) equal(metrics, line, label + "：全角竖线只出现在数字栏（第 " + (at + 1) + " 行）");
        }
        if (metrics.isEmpty()) return;
        for (String part : metrics.split("｜", -1))
            check(part.matches("^(下载量|点赞|大小|发布)：\\S.*$"), label + "：数字栏每段都是「中文名：值」——" + part);
        if (result.downloads() > 0) check(metrics.contains("下载量：" + Bot.loraSearchCount(result.downloads())), label + "：下载量按千分位写");
        if (result.likes() > 0) check(metrics.contains("点赞：" + Bot.loraSearchCount(result.likes())), label + "：点赞按千分位写");
        int last = -1;
        for (String key : List.of("下载量：", "点赞：", "大小：", "发布：")) {
            int at = metrics.indexOf(key);
            if (at < 0) continue;
            check(at > last, label + "：数字栏顺序固定为 下载量 → 点赞 → 大小 → 发布");
            last = at;
        }
        check(!metrics.startsWith("｜") && !metrics.endsWith("｜") && !metrics.contains("｜｜"), label + "：数字栏没有空段");
    }

    /** 触发词组：条数写在组头（= Civitai 给的元素数），正文按元素逐行给出、一字不改。 */
    private static void assertTriggerBlock(NodeView node, CivitaiClient.SearchResult result, String label) {
        List<String> words = Bot.loraSearchWords(result.trainedWords());
        if (words.isEmpty()) {
            check(!node.has("触发词（"), label + "：没有触发词就没有触发词组");
            return;
        }
        check(node.has("触发词（" + words.size() + " 条）："), label + "：组头的条数就是 Civitai 给的条数：" + words.size());
        int header = -1;
        for (int at = 0; at < node.lines().size(); at++) if (node.lines().get(at).startsWith("触发词（")) header = at;
        int expectedLines = 0;
        for (String word : words) expectedLines += Bot.loraSearchTriggerLines(word).size();
        equal(expectedLines, node.lines().size() - header - 1 - 1, label + "：触发词正文行数 = 每个词自己占的行数（后面只剩页面行）");
        List<String> body = node.lines().subList(header + 1, node.lines().size() - 1);
        List<String> flat = new ArrayList<>();
        for (String word : words) flat.addAll(Bot.loraSearchTriggerLines(word));
        equal(flat, body, label + "：触发词正文逐行与原文一对一对上（不合并、不截断）");
    }

    /** 数字栏那一行（没有就返回空串）。 */
    private static String metricsLine(NodeView node) {
        for (String line : node.lines())
            if (line.startsWith("下载量：") || line.startsWith("点赞：") || line.startsWith("大小：") || line.startsWith("发布：")) return line;
        return "";
    }

    /** 节点正文的结构化视图：整段文本 + 逐行；字段既可能是整行，也可能是数字栏里的一段。 */
    private record NodeView(String text, List<String> lines) {
        static NodeView of(String text) { return new NodeView(text, List.of(text.split("\n", -1))); }
        String first() { return lines.get(0); }
        /** 这一项在不在正文里（只认字段位置：行首，或数字栏里竖线分开的一段）。 */
        boolean has(String label) { return count(label) > 0; }
        int count(String label) {
            int total = 0;
            for (String line : lines) {
                if (isMetrics(line)) {
                    for (String part : line.split("｜", -1)) if (part.startsWith(label)) total++;
                } else if (line.startsWith(label)) total++;
            }
            return total;
        }
        /** 这一行是不是"数字栏"（有的才写的那几个数字字段共用一行）。 */
        private static boolean isMetrics(String line) {
            return line.startsWith("下载量：") || line.startsWith("点赞：") || line.startsWith("大小：") || line.startsWith("发布：");
        }
    }

    /** needle 在 text 里出现了几次。 */
    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    /** 传输层测试用的 3 条结果：第 1 条超长名、第 2 条 NSFW、第 3 条没有封面。 */
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
                12345 + index, List.of("trigger one", "trigger two"), 1536.5, index == 1,
                4321 + index, "2026-06-03T12:00:00.000Z");
    }

    /**
     * 版式夹具（9 条，覆盖需求里的每一种数据形状）：
     * ① 字段齐全 + 超长名 ② 几乎全空 + NSFW + 底模空串 ③ 解析层占位"未知" + 空白触发词 + 坏日期 + 没封面
     * ④ 超长触发词 + 含特殊字符与中文的名 + GB 级大小 ⑤ 名字/触发词含换行 ⑥ 300 字无断点的触发词
     * ⑦ 只有下载量与大小 ⑧ 只有底模与触发词 ⑨ 10 条触发词（旧版"最多 8 条"的回归）。
     */
    private static List<CivitaiClient.SearchResult> layoutResults() {
        List<CivitaiClient.SearchResult> results = new ArrayList<>();
        results.add(new CivitaiClient.SearchResult(101, 201, LONG_NAME, "Illustrious",
                "https://image.civitai.com/x/0.jpeg", 123456, List.of("trigger one", "trigger two"), 1536.5, false,
                4321, "2026-06-03T12:00:00.000Z"));
        results.add(new CivitaiClient.SearchResult(102, 202, "只有标记的模型", "",
                "https://image.civitai.com/x/1.jpeg", 0, List.of(), 0, true, 0, ""));
        results.add(new CivitaiClient.SearchResult(103, 203, "底模未知、没有封面", "未知", "",
                0, List.of("   ", ""), 0, false, 0, "not-a-date"));
        results.add(new CivitaiClient.SearchResult(104, 204, "《特殊》名字｜带竖线 & 表情😀", "Krea 2", "",
                7, List.of(LONG_WORD), 2 * 1024 * 1024 + 512, false, 3, "2026-08-16"));
        results.add(new CivitaiClient.SearchResult(105, 205, "名字里有\n换行", "Anima", "",
                987654321L, List.of("first line\nsecond line"), 999.4, false, 12, "2025-11-17T14:51:51.596Z"));
        results.add(new CivitaiClient.SearchResult(106, 206, "没有断点的超长触发词", "Flux.1 D",
                "https://image.civitai.com/x/5.jpeg", 5, List.of(UNBREAKABLE_WORD), 1.0, false, 1, ""));
        results.add(new CivitaiClient.SearchResult(107, 207, "只有下载量与大小", "Illustrious", "",
                12, List.of(), 512.4, false, 0, ""));
        results.add(new CivitaiClient.SearchResult(108, 208, "只有底模与触发词", "Illustrious", "",
                0, List.of("only word"), 0, false, 0, ""));
        results.add(new CivitaiClient.SearchResult(109, 209, "十条触发词", "Illustrious", "",
                0, List.of("w1", "w2", "w3", "w4", "w5", "w6", "w7", "w8", "w9", "w10"), 0, false, 0, ""));
        return results;
    }

    /** 只实现 send 的传输层：supportsRecord() 为 false；可以让指定开头的消息发送失败。 */
    private static class PlainSender implements Bot.Sender {
        final List<String> texts = new CopyOnWriteArrayList<>();
        volatile String failWhenTextStartsWith;
        @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
            String text = Bot.messageText(segments);
            String fail = failWhenTextStartsWith;
            // 发失败的那条不记进 texts：texts 只装"真的发出去了"的消息（回退逻辑要按它断言）。
            if (fail != null && text.startsWith(fail))
                return CompletableFuture.failedFuture(new IOException("模拟封面发送失败 retcode=1200"));
            texts.add(text);
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

    /** 一条结果的出站消息以"#编号 "开头；用它把结果发送与说明性回执分开计数。 */
    private static boolean isResult(String text) { return text.matches("(?s)^#[0-9]+ .*"); }

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

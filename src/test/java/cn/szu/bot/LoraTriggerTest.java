package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.civitai.LoraTriggers;
import cn.szu.bot.sd.SdClient;

/**
 * LoRA 触发词（Civitai trained words）的保留、查询与网页接口：
 *
 * <ul>
 *   <li>下载成功那一刻就写进 {@code data/lora-triggers.json}（和是否加载成功无关，加载失败也留）；</li>
 *   <li>重建 Bot 后读得回来（耐久；UTF-8 无 BOM、纯 LF）；</li>
 *   <li>{@code .lora triggers <名字|#编号>} 原样列出全部触发词（含 300 字长词、含逗号与前后空格的词），
 *       不截断、不省略号；查不到时如实说"没有记下"，绝不编；</li>
 *   <li>{@code /api/loras} 每个条目的 {@code trainedWords} 与存储一致；</li>
 *   <li>管理员可 {@code .lora triggers set} 手动补录，非管理员被拒；</li>
 *   <li>存储坏 JSON / 缺字段 / 条目类型不对都不崩，按"没有记录"处理，且写入前先备份原件、不覆盖已有条目；</li>
 *   <li>Civitai 没给触发词时存**空数组**（不是 null），回执如实说明；</li>
 *   <li>历史 LoRA 按本机 Civitai 下载记录<a>只读</a>补齐：不联网、不下载、不改 LoRA 文件与下载记录。</li>
 * </ul>
 *
 * <p>全程用手造下载器与本地 SD 桩，不连 Civitai、不发 QQ 消息。
 */
public final class LoraTriggerTest {
    private static final String NAME = "测试 LoRA 2";
    private static final String FILE = NAME + ".safetensors";
    private static final String OTHER = "未记录LoRA";
    private static final String URL = "https://civitai.com/models/42?modelVersionId=43";
    private static final String PAGE = "civitai.com/models/1599346?modelVersionId=1809851";
    private static final String BASE_MODEL = "SDXL 1.0";
    /** 一条触发词就是 300 字：任何"省略号截断"都会在这里露馅。 */
    private static final String LONG_WORD = "长触发词" + "abcdefghij".repeat(30);
    private static final List<String> WORDS = List.of(
            LONG_WORD,
            "MiXeD Case Word",
            "Kanbe Kotori,twin braids,hair flower,",
            "  前后有空格的词  ");
    private static final AtomicInteger IDS = new AtomicInteger();
    private static int assertions;

    private record Reply(JsonObject event, JsonArray segments) { String text() { return Bot.messageText(segments); } }

    public static void main(String[] args) throws Exception {
        downloadPersistsTriggersVerbatim();
        triggersSurviveLoadFailure();
        triggersAreDurableAcrossRestart();
        queryByNumberAndNameListsEverythingWithoutTruncating();
        unknownAndMissingAreHonest();
        triggersWorkWhenSdCatalogIsDown();
        manualSetRequiresAdminAndWrites();
        webApiExposesTriggers();
        brokenStoreIsToleratedAndNeverOverwritten();
        emptyTrainedWordsStoredAsArray();
        manifestBackfillIsReadOnlyAndDurable();
        System.out.println("LoraTriggerTest: " + assertions + " assertions passed: 下载即持久化（加载失败也留）、"
                + "重建后读得回、原样不截断查询、查不到如实说、/api/loras trainedWords、管理员手动补录与权限、"
                + "坏存储不崩不覆盖、Civitai 没给存空数组、下载记录只读补齐。");
    }

    /** ① 下载成功 → 存储里有 trainedWords，顺序/大小写/空格原样；同时核对存储格式与来源字段。 */
    private static void downloadPersistsTriggersVerbatim() throws Exception {
        try (Fixture fixture = new Fixture()) {
            check(fixture.command("private", ".lora download " + URL).text().contains("开始下载"), "下载先回一条开始消息");
            String receipt = fixture.take("private").text();
            check(receipt.contains("下载成功"), "下载成功回执：" + receipt);
            check(receipt.contains("触发词已记下：" + WORDS.size() + " 条"), "回执说触发词已记下：" + receipt);
            check(receipt.contains(".lora triggers"), "回执给出随时可查的入口：" + receipt);
            check(receipt.contains(".lora triggers \"测试 LoRA 2\" 可随时查看"),
                    "名字含空格时给的是可直接复制的带引号写法：" + receipt);
            check(receipt.split("\n").length <= 4, "回执仍然简短（≤4 行）：" + receipt);
            check(!receipt.contains(LONG_WORD), "回执不贴触发词原文（原文留给 .lora triggers）");

            Path store = new LoraTriggers(fixture.root).path();
            equal(fixture.root.resolve("data/lora-triggers.json"), store, "存储路径固定为 data/lora-triggers.json");
            check(Files.isRegularFile(store), "下载成功那一刻就落盘了：" + store);
            byte[] raw = Files.readAllBytes(store);
            check(!(raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF),
                    "存储是 UTF-8 无 BOM");
            String text = new String(raw, StandardCharsets.UTF_8);
            check(!text.contains("\r"), "存储是纯 LF（没有 CRLF）");
            JsonObject data = Json.parse(text);
            equal(1, data.get("version").getAsInt(), "格式版本 = 1");
            JsonObject row = data.getAsJsonObject("triggers").getAsJsonObject(NAME);
            check(row != null, "键就是本机 LoRA 文件名去掉 .safetensors：" + NAME);
            JsonArray words = row.getAsJsonArray("trainedWords");
            equal(WORDS.size(), words.size(), "触发词条数");
            for (int index = 0; index < WORDS.size(); index++)
                equal(WORDS.get(index), words.get(index).getAsString(),
                        "第 " + (index + 1) + " 个触发词原样（顺序/大小写/空格都不动）");
            equal(BASE_MODEL, row.get("baseModel").getAsString(), "baseModel 记的是 Civitai 的 Base Model 原样值");
            equal(LoraTriggers.SOURCE_DOWNLOAD, row.get("source").getAsString(), "来源标成 Civitai 下载");
            equal(1599346L, row.get("civitaiModelId").getAsLong(), "模型 id 落盘");
            equal(1809851L, row.get("civitaiVersionId").getAsLong(), "版本 id 落盘");
            equal("https://" + PAGE, row.get("sourceUrl").getAsString(), "页面地址落盘");
            check(!row.get("recordedAt").getAsString().isBlank(), "记录时间落盘：" + row.get("recordedAt"));
            check(row.has("stack"), "另存了本机栈字段（参考用，不影响底模）");

            LoraTriggers.Trigger stored = new LoraTriggers(fixture.root).get(NAME);
            check(stored != null, "按名字读得回来");
            equal(WORDS, stored.trainedWords(), "读回来的列表与原样完全一致（含长词、含逗号、含前后空格）");
            check(stored.hasPage(), "有 Civitai 页面地址");
            equal(PAGE, stored.pageAddress(), "回执里的地址不带协议（免得被凭据过滤器整段吞掉）");
            equal(BASE_MODEL, stored.baseModel(), "读回来的底模还是 Civitai 那个值");
            equal("civitai-download", stored.source(), "读回来的来源");
        }
    }

    /** ② 加载/确认本机标签失败（或用户干脆不加载）也照样留触发词。 */
    private static void triggersSurviveLoadFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.refreshStatus = 500;
            fixture.command("private", ".lora download " + URL);
            String receipt = fixture.take("private").text();
            check(receipt.contains("刷新/确认本机标签失败"), "加载阶段失败被如实报出来：" + receipt);
            check(receipt.contains("触发词已记下：" + WORDS.size() + " 条"), "加载失败也留下触发词：" + receipt);
            check(receipt.contains(".lora triggers"), "失败回执也给查询入口：" + receipt);
            LoraTriggers.Trigger stored = new LoraTriggers(fixture.root).get(NAME);
            check(stored != null, "加载失败后存储里仍然有记录");
            equal(WORDS, stored.trainedWords(), "加载失败不影响触发词内容与顺序");
            check(fixture.command("private", ".lora triggers " + NAME).text().contains(LONG_WORD),
                    "加载失败后仍然查得到全部触发词");
        }
    }

    /** ③ 重建 Bot（新进程等价物）后触发词读得回来：耐久。 */
    private static void triggersAreDurableAcrossRestart() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.command("private", ".lora download " + URL);
            check(fixture.take("private").text().contains("下载成功"), "第一次下载成功");
            fixture.reopen();
            Reply reply = fixture.command("private", ".lora triggers " + NAME);
            for (String word : WORDS) check(reply.text().contains(word), "重建 Bot 后第 " + WORDS.indexOf(word) + " 个词还在：" + reply.text());
            check(reply.text().contains("触发词 " + WORDS.size() + " 条"), "重建后条数不变");
            equal(WORDS, new LoraTriggers(fixture.root).get(NAME).trainedWords(), "重建后直接读存储也一致");
            // 重新下载同一个 LoRA：记录被更新而不是丢掉别的字段。
            fixture.command("private", ".lora download " + URL);
            check(fixture.take("private").text().contains("下载成功"), "二次下载成功");
            equal(WORDS, new LoraTriggers(fixture.root).get(NAME).trainedWords(), "二次下载后记录仍原样");
            equal(BASE_MODEL, new LoraTriggers(fixture.root).get(NAME).baseModel(), "二次下载后底模仍在");
        }
    }

    /** ④ `.lora triggers #N` 与按名字/别名/宽松名查询：全部触发词原样列出，不截断。 */
    private static void queryByNumberAndNameListsEverythingWithoutTruncating() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.command("private", ".lora download " + URL);
            fixture.take("private");
            for (String query : List.of("#1", NAME, "中文别名", "测试LoRA2", "测试 LoRA 2.safetensors")) {
                String reply = fixture.command("private", ".lora triggers " + query).text();
                check(reply.contains("触发词 " + WORDS.size() + " 条"), query + " 查得到记录：" + reply);
                for (int index = 0; index < WORDS.size(); index++)
                    check(reply.contains((index + 1) + ". " + WORDS.get(index)),
                            query + " 原样列出第 " + (index + 1) + " 个触发词（不截断）");
                check(reply.contains("来源：Civitai 下载时记录"), query + " 附来源：" + reply);
                check(reply.contains(PAGE), query + " 附 Civitai 页面地址");
                check(reply.contains("Civitai 底模 " + BASE_MODEL), query + " 附底模");
                check(reply.contains("记录时间"), query + " 附记录时间");
                check(!reply.contains("…"), query + " 回执里没有省略号截断");
                check(reply.contains("合并写法（可直接粘贴）："), query + " 额外给一行可直接粘贴的合并写法");
            }
            // .lora list 里每条带一行触发词摘要，并指向 .lora triggers。
            fixture.command("private", ".lora list");
            String list = fixture.take("private").text();
            check(list.contains("触发词 " + WORDS.size() + " 条（.lora triggers #1 看全部）"), "list 摘要带条数与编号：" + list);
            check(list.contains("触发词明细：.lora triggers"), "list 末尾给出触发词入口：" + list);
            // .lora detail 也带一行摘要。
            fixture.command("private", ".lora detail " + NAME);
            String detail = fixture.take("private").text();
            check(detail.contains("触发词：" + WORDS.size() + " 条（.lora triggers "), "detail 里有一行触发词摘要：" + detail);
            // .help 只多一行。
            String help = fixture.command("private", ".help").text();
            check(help.contains(".lora triggers <名称|#编号>"), ".help 里有 .lora triggers 一行");
            int helpLines = 0;
            for (String line : help.split("\n")) if (line.contains(".lora triggers")) helpLines++;
            equal(1, helpLines, ".help 里 .lora triggers 只占一行");
        }
    }

    /** ⑤ 查不到（没有记录 / 名字不存在 / 编号越界）时如实说明，绝不编触发词。 */
    private static void unknownAndMissingAreHonest() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String missing = fixture.command("private", ".lora triggers " + OTHER).text();
            check(missing.contains("这个 LoRA 没有记下触发词（可能是本地导入、或下载时 Civitai 没给）。"),
                    "没有记录时如实说明：" + missing);
            check(missing.contains(".lora triggers set"), "顺带给出手动补录入口：" + missing);
            check(!missing.contains("合并写法") && !missing.contains("触发词 1 条"), "没有记录时不编任何触发词");
            String unknown = fixture.command("private", ".lora triggers 根本没有这个模型").text();
            check(unknown.startsWith("操作失败："), "名字不存在时明确报错：" + unknown);
            check(unknown.contains("找不到") && unknown.contains("候选项"), "找不到时列出候选项：" + unknown);
            check(unknown.contains(NAME), "候选项里能看到真实 LoRA 名");
            check(!unknown.contains(LONG_WORD), "找不到时不会把别人的触发词贴出来");
            String usage = fixture.command("private", ".lora triggers").text();
            check(usage.startsWith("操作失败：") && usage.contains("用法：.lora triggers"), "缺参数时给出用法：" + usage);
            String badNumber = fixture.command("private", ".lora triggers #99").text();
            check(badNumber.startsWith("操作失败：") && badNumber.contains("编号无效"), "#编号越界时如实报错：" + badNumber);
            String fuzzy = fixture.command("private", ".lora triggers 未记录").text();
            check(fuzzy.contains("没有记下触发词"), "部分名字（唯一命中）也能定位：" + fuzzy);
        }
    }

    /** ⑥ WebUI 列表读不到（SD 挂了/正在重启）时，按本机目录兜底查询触发词。 */
    private static void triggersWorkWhenSdCatalogIsDown() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.command("private", ".lora download " + URL);
            fixture.take("private");
            fixture.catalogStatus = 500;
            String reply = fixture.command("private", ".lora triggers " + NAME).text();
            check(reply.contains("触发词 " + WORDS.size() + " 条"), "SD 列表读不到也能查到触发词：" + reply);
            check(reply.contains(LONG_WORD), "SD 列表读不到时触发词仍然完整");
            String unknown = fixture.command("private", ".lora triggers 没有的模型").text();
            check(unknown.contains("候选项") && unknown.contains(FILE), "SD 列表读不到时候选项来自本机目录：" + unknown);
        }
    }

    /** ⑦ 手动补录：仅管理员；写进同一份存储、可读回；不碰 LoRA 文件。 */
    private static void manualSetRequiresAdminAndWrites() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] before = Files.readAllBytes(fixture.file);
            String denied = fixture.command("private", ".lora triggers set \"" + NAME + "\" 词A, 词B", 456).text();
            check(denied.startsWith("操作失败：") && denied.contains("仅管理员"), "非管理员被拒：" + denied);
            check(!Files.exists(new LoraTriggers(fixture.root).path()), "被拒时不写存储文件");

            String written = fixture.command("private", ".lora triggers set \"" + NAME + "\" 词A, 词B", 123).text();
            check(written.contains("已写入触发词记录：" + NAME), "管理员补录成功：" + written);
            check(written.contains("存储：" + new LoraTriggers(fixture.root).path()), "回执给出存储路径：" + written);
            LoraTriggers.Trigger record = new LoraTriggers(fixture.root).get(NAME);
            check(record != null, "补录后读得回来");
            equal(List.of("词A", "词B"), record.trainedWords(), "补录的词按给的顺序写进同一份存储");
            equal(LoraTriggers.SOURCE_MANUAL, record.source(), "此前没有记录 → 来源是手动补录");
            check(Arrays.equals(before, Files.readAllBytes(fixture.file)), "手动补录不改动 LoRA 文件");

            String read = fixture.command("private", ".lora triggers " + NAME).text();
            check(read.contains("1. 词A") && read.contains("2. 词B"), "补录后查询列出这两条：" + read);
            check(read.contains("来源：手动补录"), "补录来源如实写：" + read);
            check(read.contains("合并写法（可直接粘贴）：词A, 词B"), "补录后也给合并写法：" + read);

            // 修改已有记录：来源变成"手动补录/修改"。
            fixture.command("private", ".lora triggers set \"" + NAME + "\" 新词", 123);
            equal(List.of("新词"), new LoraTriggers(fixture.root).get(NAME).trainedWords(), "再次补录覆盖词表");
            equal(LoraTriggers.SOURCE_MANUAL_EDIT, new LoraTriggers(fixture.root).get(NAME).source(), "修改已有记录时来源标注为手动补录/修改");

            // 补录一个没有下载记录的 LoRA：只写触发词，页面地址如实留空。
            String manualReceipt = fixture.command("private", ".lora triggers set " + OTHER + " 自记词1、自记词2", 123).text();
            check(manualReceipt.contains("已写入触发词记录：" + OTHER + "（2 条）"), "按完整名字补录成功：" + manualReceipt);
            check(manualReceipt.contains(".lora triggers " + OTHER), "名字没有空格时不给多余的引号：" + manualReceipt);
            LoraTriggers.Trigger manual = new LoraTriggers(fixture.root).get(OTHER);
            check(manual != null && manual.count() == 2, "没有下载记录的 LoRA 也能补录：" + manual);
            equal(List.of("自记词1", "自记词2"), manual.trainedWords(), "顿号分隔也认");
            check(!manual.hasPage() && manual.sourceUrl().isBlank(), "补录不编造页面地址");
            String noPage = fixture.command("private", ".lora triggers " + OTHER).text();
            check(noPage.contains("没有 Civitai 页面地址"), "没有来源时如实说没有页面地址：" + noPage);

            // 用法校验。
            String noArgs = fixture.command("private", ".lora triggers set", 123).text();
            check(noArgs.startsWith("操作失败：") && noArgs.contains("用法：.lora triggers set"), "缺参数给出用法：" + noArgs);
            String partial = fixture.command("private", ".lora triggers set " + NAME, 123).text();
            check(partial.startsWith("操作失败：") && partial.contains("候选项") && partial.contains("双引号"),
                    "写数据时必须写完整名字：名字含空格要加双引号，否则列出候选项：" + partial);
            String badQuote = fixture.command("private", ".lora triggers set \"未闭合 " + NAME, 123).text();
            check(badQuote.startsWith("操作失败：") && badQuote.contains("引号"), "引号不配对时明确报错：" + badQuote);
            check(fixture.command("private", ".lora triggers set", 456).text().contains("仅管理员"), "权限先于参数校验");
        }
    }

    /** ⑧ `/api/loras`（Bot.webLoras()）每个条目都带 trainedWords / sourceUrl / triggerRecordedAt。 */
    private static void webApiExposesTriggers() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.command("private", ".lora download " + URL);
            fixture.take("private");
            JsonObject payload = fixture.bot.webLoras();
            JsonArray items = payload.getAsJsonArray("loras");
            equal(2, items.size(), "本机有两个 LoRA");
            JsonObject recorded = items.get(0).getAsJsonObject();
            equal(NAME, recorded.get("name").getAsString(), "第一个是本机已记录的那个");
            check(recorded.has("trainedWords") && recorded.get("trainedWords").isJsonArray(), "条目带 trainedWords 数组：" + recorded);
            JsonArray words = recorded.getAsJsonArray("trainedWords");
            equal(WORDS.size(), words.size(), "trainedWords 条数与存储一致");
            for (int index = 0; index < WORDS.size(); index++)
                equal(WORDS.get(index), words.get(index).getAsString(), "trainedWords[" + index + "] 与存储逐字一致");
            equal(WORDS.size(), recorded.get("triggerCount").getAsInt(), "triggerCount 与数组长度一致");
            check(recorded.has("sourceUrl") && !recorded.get("sourceUrl").isJsonNull(), "条目带 sourceUrl：" + recorded);
            equal("https://" + PAGE, recorded.get("sourceUrl").getAsString(), "sourceUrl 就是存储里的页面地址");
            check(recorded.has("triggerRecordedAt") && !recorded.get("triggerRecordedAt").isJsonNull(), "条目带 triggerRecordedAt");
            equal(new LoraTriggers(fixture.root).get(NAME).recordedAt(), recorded.get("triggerRecordedAt").getAsString(),
                    "triggerRecordedAt 与存储一致");
            equal(LoraTriggers.SOURCE_DOWNLOAD, recorded.get("triggerSource").getAsString(), "条目带来源标记");

            JsonObject missing = items.get(1).getAsJsonObject();
            equal(OTHER, missing.get("name").getAsString(), "第二个是没有记录的 LoRA");
            check(missing.has("trainedWords") && missing.get("trainedWords").isJsonArray(), "没有记录时也是数组而不是 null");
            equal(0, missing.getAsJsonArray("trainedWords").size(), "没有记录 → 空数组");
            check(missing.get("sourceUrl").isJsonNull(), "没有来源 → sourceUrl 是 null");
            check(missing.get("triggerRecordedAt").isJsonNull(), "没有记录 → triggerRecordedAt 是 null");
            equal("", missing.get("triggerSource").getAsString(), "没有记录 → 来源标记是空串");
            // 与 /api/loras 路由同源：控制器把这个对象原样序列化给网页。
            check(payload.has("status") && payload.has("directory"), "/api/loras 的既有字段一个不少：" + payload.keySet());
        }
    }

    /** ⑨ 坏 JSON / 缺字段 / 条目类型不对：不崩、按"没有记录"处理、写入前备份原件且不丢已有条目。 */
    private static void brokenStoreIsToleratedAndNeverOverwritten() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path store = new LoraTriggers(fixture.root).path();
            Files.createDirectories(store.getParent());
            // ① 好条目 + 三条坏条目（我们的 LoRA 那条正好是坏的）。
            JsonObject triggers = new JsonObject();
            triggers.add(NAME, new JsonPrimitive("不是对象"));
            JsonObject missingWords = new JsonObject();
            missingWords.addProperty("loraName", "缺字段");
            triggers.add("缺字段", missingWords);
            JsonObject nullWords = new JsonObject();
            nullWords.addProperty("loraName", "空词表");
            nullWords.add("trainedWords", JsonNull.INSTANCE);
            triggers.add("空词表", nullWords);
            triggers.add("好条目A", entry("好条目A", List.of("A1", "A2")));
            triggers.add("好条目B", entry("好条目B", List.of("B1")));
            JsonObject data = new JsonObject();
            data.addProperty("version", 1);
            data.add("triggers", triggers);
            Json.atomicWriteText(store, Json.GSON.toJson(data) + "\n");

            LoraTriggers reader = new LoraTriggers(fixture.root);
            check(reader.get(NAME) == null, "条目类型不对 → 按没有记录处理");
            check(reader.get("缺字段") == null, "缺 trainedWords → 按没有记录处理");
            check(reader.get("空词表") == null, "trainedWords 为 null → 按没有记录处理");
            check(reader.get("好条目A") != null && reader.get("好条目B") != null, "好条目照旧读得出来");
            equal(3, reader.skippedEntries(), "三条坏条目被跳过（并记了日志）");
            check(!reader.corrupt(), "只是个别条目坏，不算整个文件坏");
            String honest = fixture.command("private", ".lora triggers " + NAME).text();
            check(honest.contains("没有记下触发词"), "坏条目下查询不崩、如实说没有记录：" + honest);
            check(!honest.contains(LONG_WORD), "坏条目下不会编触发词");

            // 写入一条新记录：已有好条目必须还在。
            reader.setWords("新 LoRA", "新LoRA.safetensors", List.of("新词"));
            LoraTriggers after = new LoraTriggers(fixture.root);
            check(after.get("好条目A") != null && after.get("好条目B") != null, "写入不覆盖已有条目");
            equal(List.of("A1", "A2"), after.get("好条目A").trainedWords(), "已有条目的词表也没有被动过");
            equal(List.of("新词"), after.get("新 LoRA").trainedWords(), "新记录写进去了");

            // ② 整个文件不是 JSON：按没有记录处理；写入前把原件备份下来。
            Files.writeString(store, "{ 这不是 JSON", StandardCharsets.UTF_8);
            LoraTriggers broken = new LoraTriggers(fixture.root);
            check(broken.all().isEmpty(), "坏 JSON 按没有记录处理（不崩）");
            check(broken.corrupt(), "坏 JSON 被识别为坏文件（最近一次读取的结果）");
            check(broken.get(NAME) == null, "坏 JSON 下查不到记录");
            equal(0, broken.skippedEntries(), "整份坏文件不算条目跳过");
            check(fixture.command("private", ".lora triggers " + NAME).text().contains("没有记下触发词"), "坏存储下查询不崩、如实说没有记录");
            broken.put(new LoraTriggers.Trigger(NAME, FILE, List.of("修复后的词"), "", 0, 0, "", "", "", LoraTriggers.SOURCE_MANUAL));
            List<Path> backups = backups(store);
            equal(1, backups.size(), "坏原件被另存了一份（不是静默覆盖）");
            equal("{ 这不是 JSON", Files.readString(backups.get(0), StandardCharsets.UTF_8), "备份里就是原始字节");
            equal(List.of("修复后的词"), new LoraTriggers(fixture.root).get(NAME).trainedWords(), "重建后新记录读得回来");

            // ③ triggers 不是对象 / 缺 triggers 字段。
            JsonObject arrayTriggers = new JsonObject();
            arrayTriggers.addProperty("version", 1);
            arrayTriggers.add("triggers", new JsonArray());
            Json.atomicWriteText(store, Json.GSON.toJson(arrayTriggers) + "\n");
            LoraTriggers arrayStore = new LoraTriggers(fixture.root);
            check(arrayStore.all().isEmpty() && arrayStore.corrupt(), "triggers 不是对象 → 按没有记录处理");
            JsonObject noWrapper = new JsonObject();
            noWrapper.addProperty("version", 1);
            Json.atomicWriteText(store, Json.GSON.toJson(noWrapper) + "\n");
            LoraTriggers wrapperless = new LoraTriggers(fixture.root);
            check(wrapperless.all().isEmpty() && wrapperless.corrupt(), "缺 triggers 字段 → 按没有记录处理");
            equal(1, backups(store).size(), "只读路径不会再造备份");
            check(fixture.command("private", ".lora triggers " + OTHER).text().contains("没有记下触发词"), "缺字段的存储下查询照旧不崩");
        }
    }

    /** ⑩ Civitai 没给触发词 → 存空数组（不是 null），回执如实说明。 */
    private static void emptyTrainedWordsStoredAsArray() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.downloader.words = List.of();
            fixture.command("private", ".lora download " + URL);
            String receipt = fixture.take("private").text();
            check(receipt.contains("触发词已记下：0 条"), "没给触发词也留一条记录：" + receipt);
            check(receipt.contains("没给"), "回执如实说 Civitai 没给：" + receipt);
            check(receipt.contains(".lora triggers"), "0 条也给查询入口：" + receipt);
            JsonObject row = Json.parse(Files.readString(new LoraTriggers(fixture.root).path(), StandardCharsets.UTF_8))
                    .getAsJsonObject("triggers").getAsJsonObject(NAME);
            check(row.get("trainedWords").isJsonArray(), "存的是 JSON 数组：" + row);
            equal(0, row.getAsJsonArray("trainedWords").size(), "空数组（不是 null，也不会崩）");
            LoraTriggers.Trigger record = new LoraTriggers(fixture.root).get(NAME);
            check(record != null, "0 条也是一条记录");
            equal(0, record.count(), "0 条");
            equal(BASE_MODEL, record.baseModel(), "0 条时底模照旧记下来");
            String reply = fixture.command("private", ".lora triggers " + NAME).text();
            check(reply.contains("触发词：0 条"), "查询如实说 0 条：" + reply);
            check(!reply.contains("合并写法"), "0 条时不给合并写法");
            fixture.command("private", ".lora list");
            check(fixture.take("private").text().contains("触发词 0 条（下载时 Civitai 没给）"), "list 里也如实说 0 条");
        }
    }

    /** ⑪ 历史 LoRA：按本机 Civitai 下载记录只读补齐（不联网、不下载、不改文件）。 */
    private static void manifestBackfillIsReadOnlyAndDurable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path manifests = fixture.root.resolve("data/civitai");
            Files.createDirectories(manifests);
            JsonArray words = new JsonArray();
            words.add("Shiroha");
            words.add("Naruse Shiroha,white hair,");
            JsonObject record = new JsonObject();
            record.addProperty("model_id", 1241445);
            record.addProperty("version_id", 1399163);
            record.addProperty("model_name", "Naruse Shiroha - Summer pockets IL");
            record.addProperty("version_name", "V1");
            record.addProperty("base_model", "Illustrious");
            record.add("trained_words", words);
            record.addProperty("verified_at", "2026-10-03T20:10:34.290488700Z");
            Path manifest = manifests.resolve(OTHER + ".safetensors.json");
            Json.atomicWrite(manifest, record);
            String manifestText = Files.readString(manifest, StandardCharsets.UTF_8);

            // 查询路径：只读回落，用完不写盘。
            String reply = fixture.command("private", ".lora triggers " + OTHER).text();
            check(reply.contains("触发词 2 条"), "下载记录里的触发词查得到：" + reply);
            check(reply.contains("1. Shiroha") && reply.contains("2. Naruse Shiroha,white hair,"), "原样列出下载记录里的词");
            check(reply.contains("来源：从本机 Civitai 下载记录补齐"), "来源如实标注：" + reply);
            check(reply.contains("Civitai 底模 Illustrious"), "底模用下载记录里的 Civitai 值：" + reply);
            check(reply.contains(PAGE.replace("1599346", "1241445").replace("1809851", "1399163")), "页面地址按记录的 id 拼：" + reply);
            check(!Files.exists(new LoraTriggers(fixture.root).path()), "只读回落不写存储文件");

            // 补齐：写进存储，且第二次补齐不重复写、不覆盖。
            List<LoraTriggers.Trigger> added = new LoraTriggers(fixture.root)
                    .backfill(List.of(OTHER + ".safetensors", "根本没有这个.safetensors"), "https://civitai.com");
            equal(1, added.size(), "只补齐有下载记录的那一个");
            equal(LoraTriggers.SOURCE_MANIFEST, added.get(0).source(), "补齐记录的来源");
            equal("2026-10-03T20:10:34.290488700Z", added.get(0).recordedAt(), "记录时间取下载记录里的时间（不假装是刚刚记的）");
            LoraTriggers stored = new LoraTriggers(fixture.root);
            check(stored.get(OTHER) != null, "补齐后存储里有记录");
            equal(2, stored.get(OTHER).count(), "补齐的词数与下载记录一致");
            equal(0, stored.backfill(List.of(OTHER + ".safetensors"), "https://civitai.com").size(), "二次补齐不重复写");
            equal("Shiroha", stored.get(OTHER).trainedWords().get(0), "补齐后顺序不变");
            // 只读：LoRA 文件与下载记录都没被碰过。
            check(Arrays.equals(new byte[]{4, 5, 6}, Files.readAllBytes(fixture.other)), "补齐不改 LoRA 文件");
            equal(manifestText, Files.readString(manifest, StandardCharsets.UTF_8), "补齐不改 Civitai 下载记录");

            // 没有记录、也没有来源信息的 LoRA：留空，并在日志里说明。
            fixture.command("private", ".lora download " + URL);
            fixture.take("private");
            LoraTriggers fresh = new LoraTriggers(fixture.root);
            equal(0, fresh.backfill(List.of("根本没有这个.safetensors"), "https://civitai.com").size(), "没有下载记录的留空，不猜");
            check(fresh.get("根本没有这个") == null, "补不齐的不建条目");
        }
    }

    // ---- 夹具 ----

    /** 一条合法的存储条目（坏条目测试里当对照组用）。 */
    private static JsonObject entry(String name, List<String> words) {
        JsonObject row = new JsonObject();
        row.addProperty("loraName", name);
        row.addProperty("fileName", name + ".safetensors");
        JsonArray array = new JsonArray();
        for (String word : words) array.add(word);
        row.add("trainedWords", array);
        row.addProperty("sourceUrl", "");
        row.add("civitaiModelId", JsonNull.INSTANCE);
        row.add("civitaiVersionId", JsonNull.INSTANCE);
        row.addProperty("baseModel", "");
        row.addProperty("stack", "");
        row.addProperty("recordedAt", "2026-01-01T00:00:00Z");
        row.addProperty("source", LoraTriggers.SOURCE_MANUAL);
        return row;
    }

    /** 存储旁边被备份下来的坏原件。 */
    private static List<Path> backups(Path store) throws IOException {
        try (var files = Files.list(store.getParent())) {
            return files.filter(path -> path.getFileName().toString().startsWith(store.getFileName() + ".corrupt-")).toList();
        }
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    /** 假下载器：不碰网络，直接给一份 DownloadedLora（触发词可配）。 */
    private static final class FakeDownloader implements Bot.LoraDownloader {
        volatile List<String> words = WORDS;
        volatile String baseModel = BASE_MODEL;
        volatile Path path;
        volatile Exception failure;
        public CivitaiClient.DownloadedLora download(String url, Consumer<String> progress, CivitaiClient.Progress meter) throws Exception {
            if (failure != null) throw failure;
            progress.accept("正在下载 LoRA（测试桩）");
            return new CivitaiClient.DownloadedLora("测试模型", "版本一", baseModel, words, path, false, 1599346, 1809851,
                    List.of(new CivitaiClient.ShowcasePrompt(1, "example +", "example -", true, "")));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root, file, other;
        final HttpServer server;
        final ExecutorService executor;
        final SdClient client;
        final FakeDownloader downloader = new FakeDownloader();
        final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
        final AtomicInteger refreshes = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile int refreshStatus = 200, catalogStatus = 200;
        volatile Bot bot;
        private final JsonObject state = Json.parse("{\"positive\":\"initial +\",\"negative\":\"initial -\","
                + "\"source\":\"webui-live\",\"revision\":1,\"sampler_name\":\"Euler a\",\"styles\":[],\"width\":512,\"height\":512,"
                + "\"settings_initialized\":true}");

        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "lora-trigger-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            file = root.resolve("models/LoRA/" + FILE);
            other = root.resolve("models/LoRA/" + OTHER + ".safetensors");
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[]{1, 2, 3});
            Files.write(other, new byte[]{4, 5, 6});
            downloader.path = file;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(task -> { Thread thread = new Thread(task, "lora-trigger-stub"); thread.setDaemon(true); return thread; });
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sd.addProperty("timeout_seconds", 3);
            JsonObject civitai = new JsonObject();
            civitai.addProperty("lora_dir", file.getParent().toString().replace('\\', '/'));
            civitai.addProperty("base_url", "https://civitai.com");
            JsonArray admins = new JsonArray();
            admins.add(123);
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            config.add("civitai", civitai);
            config.add("admin_user_ids", admins);
            config.addProperty("gen_auto_get", false);
            Json.atomicWrite(root.resolve("config.json"), config);
            client = new SdClient(root, sd);
            bot = newBot();
        }

        Bot newBot() throws IOException {
            Bot.Sender sender = (event, segments) -> {
                replies.add(new Reply(event.deepCopy(), segments.deepCopy()));
                return CompletableFuture.completedFuture(null);
            };
            return new Bot(new Settings(root), client, sender, downloader);
        }

        /** 等价于"重启机器人"：关掉当前 Bot，用同一份数据目录重新构造一个。 */
        void reopen() throws Exception {
            bot.close();
            bot = newBot();
        }

        Reply command(String type, String text) throws Exception { return command(type, text, 456); }

        Reply command(String type, String text, int user) throws Exception {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", type);
            event.addProperty("user_id", user);
            event.addProperty("self_id", 777);
            event.addProperty("message_id", IDS.incrementAndGet());
            if (type.equals("group")) event.addProperty("group_id", 999);
            event.add("message", Maps.text(text));
            bot.accept(event);
            return take(type);
        }

        Reply take(String type) throws Exception {
            Reply reply = replies.poll(5, TimeUnit.SECONDS);
            check(reply != null, "等待 " + type + " 回执超时");
            return reply;
        }

        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { bot.close(); } catch (Exception ignored) { }
            server.stop(0);
            executor.shutdownNow();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException ignored) { }
        }

        /** 本地 SD 桩：只回答机器人用得到的那几个接口，其余 404（= 连不上/不支持）。 */
        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/pixiko-bridge/v1/prompts")) {
                    send(exchange, 200, state.toString());
                } else if (path.equals("/sdapi/v1/refresh-loras")) {
                    send(exchange, refreshStatus, "null");
                } else if (path.equals("/sdapi/v1/loras")) {
                    if (catalogStatus != 200) { send(exchange, catalogStatus, "{}"); return; }
                    JsonArray list = new JsonArray();
                    JsonObject first = new JsonObject();
                    first.addProperty("name", NAME);
                    first.addProperty("alias", "中文别名");
                    first.addProperty("path", file.toString());
                    list.add(first);
                    JsonObject second = new JsonObject();
                    second.addProperty("name", OTHER);
                    second.addProperty("alias", OTHER);
                    second.addProperty("path", other.toString());
                    list.add(second);
                    send(exchange, 200, list.toString());
                } else if (path.equals("/sdapi/v1/prompt-styles")) {
                    send(exchange, 200, "[]");
                } else {
                    send(exchange, 404, "{}");
                }
            } finally {
                exchange.close();
            }
        }
    }

    private static void send(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}

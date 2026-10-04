package cn.szu.bot;

import com.google.gson.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import cn.szu.bot.prompt.CategoryModel;
import cn.szu.bot.sd.UserPromptStore;

/**
 * 方案 B（复合短语的 DeepSeek 兜底 + 缓存）的验收：
 *
 * <ol>
 *   <li>缓存：第一次 1 次调用、第二次 0 次；缓存文件落盘可读回（UTF-8 无 BOM、纯 LF、带 version/entries）；</li>
 *   <li>用户点名的 4 条复合短语在假 Caller 下 {@code /prompt drop 镜头} 的**精确字符串**对照
 *       （{@code multiple views} 整条删、其余三条只摘镜头片段），以及 LoRA 标签原样保留；</li>
 *   <li>三种失败（调用抛异常 / 返回乱 JSON / 片段拼不回原短语）→ **一个字都不删**，且日志有 warn；</li>
 *   <li>不设额度上限：20 条与 60 条未解析短语全部送到模型（自动分批），每条都拿到分类结果；</li>
 *   <li>开关 off：0 次调用、不读缓存、行为退回本地判定；</li>
 *   <li>生产 Caller 的编码与校验（parts → 规范 phrases JSON）、整条删的护栏、缓存损坏与版本不符。</li>
 * </ol>
 *
 * <p>全程用假 Caller，不发任何网络请求，也不碰线上 data/。
 */
public final class CategoryModelTest {
    private static int checks;

    private static final String VIEWS = "multiple views";
    private static final String STANDING = "standing sex from behind";
    private static final String PANTIES = "male pulling her panties aside from behind";
    private static final String CLOSEUP = "extreme close-up on their joined crotch as he penetrates her";
    private static final String CLOSEUP_KEPT = "on their joined crotch as he penetrates her";

    public static void main(String[] args) throws Exception {
        cacheRoundTrip();
        userPhrasesDrop();
        failureModes();
        volumeWithoutLimits();
        switchOff();
        encoderAndGuards();
        cacheRobustness();
        commandSurface();
        keepStaysLocal();
        System.out.println("CategoryModelTest: " + checks + " checks passed：缓存、用户 4 条精确对照、三种失败兜底、"
                + "不限量自动分批、开关 off 走本地、编码与整条删护栏、指令面与按会话持久化");
    }

    /**
     * 指令面：{@code /prompt category status|model on|off}（按会话持久化到 config.json）与
     * {@code /help} 里的一行；生产链路真的调不通 DeepSeek 时同样一个字都不删。
     */
    private static void commandSurface() throws Exception {
        try (GenerationPresetTest.Fixture f = new GenerationPresetTest.Fixture()) {
            check(f.command("private", ".help").contains("prompt category [status|model on|off]"), "帮助里有一行 prompt category（公开指令按惯例显示成 .prompt）");
            check(f.command("private", ".prompt category status").contains("复合短语兜底模型"), "status 给出复合短语兜底模型的状态");
            check(f.command("private", ".prompt category status").contains("开启"), "默认开启");
            check(f.command("private", ".prompt category status").contains("短语总数不限"), "status 说明不设额度上限");
            check(f.command("private", ".prompt category model on").contains("已开启"), "model on 的回执");
            check(new Settings(f.root).categoryModelEnabled("2"), "开启状态落进 config.json");
            String off = f.command("private", ".prompt category model off");
            check(off.contains("已关闭") && off.contains("本地判定"), "model off 的回执说明走本地：" + off);
            check(!new Settings(f.root).categoryModelEnabled("2"), "关闭状态落进 config.json");
            check(Files.readString(f.root.resolve("config.json"), StandardCharsets.UTF_8).contains("category_model"),
                    "开关落在 category_model 段（与 infix 同一套写法）");
            check(f.command("private", ".prompt category status").contains("关闭"), "status 读回关闭状态");
            check(f.command("private", ".prompt category 胡说").contains("用法"), "认不出的参数给出用法");
            String refused = f.command("private", ".promptR category status");
            check(refused.contains("正向 prompt"), "反向 prompt 上的 category 子命令被拒：" + refused);

            // 默认开启 + 生产链路打不通（该临时根没有 DeepSeek 密钥）：
            // 交给模型的长从句一个字都不动（保守优先），本地能判的照常处理。
            f.command("private", ".prompt category model on");
            f.command("private", ".prompt set 1girl, " + PANTIES + ", standing sex from behind, multiple views");
            String reply = f.command("private", ".prompt drop 镜头");
            String after = new UserPromptStore(f.root).prompts("2").positive();
            check(after.contains("1girl"), "非目标词条一个字都不动：" + after);
            check(after.contains(PANTIES), "调用打不通时交给模型的长从句一个字都不动（保守优先）：" + after);
            check(after.contains("standing sex"), "本地能判的复合短语照常只摘镜头片段：" + after);
            check(!after.contains("multiple views"), "本地能定类的 multiple views 照常整条删：" + after);
            check(reply.contains("multiple views") && reply.contains("删除"), "回执列出真正删掉的 multiple views：" + reply);
            check(reply.contains(PANTIES), "回执如实保留模型没判出来的长从句：" + reply);

            // keep（只保留某几类）完全走本地：不问模型，结果与纯本地一致。
            f.command("private", ".prompt set 1girl, multiple views, standing sex from behind");
            f.command("private", ".prompt keep 人物");
            check(new UserPromptStore(f.root).prompts("2").positive().equals("1girl"),
                    "keep 人物 只留下 1girl：" + new UserPromptStore(f.root).prompts("2").positive());
        }
    }

    /** keep 只走本地：即使开了模型也不发请求。 */
    private static void keepStaysLocal() throws Exception {
        Path root = fixture();
        try {
            FakeCaller caller = new FakeCaller();
            CategoryModel model = new CategoryModel(root, caller);
            String base = "1girl, " + VIEWS + ", " + STANDING;
            Bot.CategorySurgery keep = new Bot.CategorySurgery(Set.of("人物"), Set.of(), true);
            String withModel = Bot.applyCategorySurgery(base, keep, null, new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), model);
            String localOnly = Bot.applyCategorySurgery(base, keep, null, new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), null);
            check(withModel.equals(localOnly), "keep 的结果与纯本地一致：[" + withModel + "] / [" + localOnly + "]");
            check(caller.calls() == 0, "keep 不问模型：" + caller.calls());
            check(withModel.equals("1girl"), "keep 人物 只留下 1girl：[" + withModel + "]");
        } finally {
            TestCleanup.deleteQuietly(root);
        }
    }

    /** 缓存：第一次 1 次调用、第二次 0 次；文件落盘、无 BOM、纯 LF、带 version/entries。 */
    private static void cacheRoundTrip() throws Exception {
        Path root = fixture();
        try {
            FakeCaller caller = new FakeCaller()
                    .answer(VIEWS, VIEWS, "镜头")
                    .answer(STANDING, "standing sex", "动作", "from behind", "镜头");
            CategoryModel model = new CategoryModel(root, caller);
            Map<String, List<CategoryModel.Segment>> first = model.resolve(List.of(STANDING, VIEWS));
            check(first.size() == 2, "两条短语都拿到片段：" + first.keySet());
            check(caller.calls() == 1, "第一次调用模型 1 次，实际 " + caller.calls());
            check(caller.sent().size() == 2, "两条一次批量带去：" + caller.sent());
            check(model.status().get("cached").getAsInt() == 2, "两条都进了缓存");
            check(model.status().get("lastCalls").getAsInt() == 1, "status 报出本次调用 1 次");
            check(model.status().get("lastCachedHits").getAsInt() == 0, "第一次没有缓存命中");
            check(model.status().get("lastFallbacks").getAsInt() == 0, "第一次没有兜底");
            check(model.status().get("limited").getAsBoolean() == false, "实现不设额度上限");

            Path file = model.cacheFile();
            check(Files.isRegularFile(file), "缓存落盘：" + file);
            check(file.getFileName().toString().equals("category-model-cache.json") && file.getParent().getFileName().toString().equals("data"),
                    "缓存位置是 data/category-model-cache.json：" + file);
            String text = Files.readString(file, StandardCharsets.UTF_8);
            check(!text.startsWith("\uFEFF"), "缓存没有 BOM");
            check(!text.contains("\r"), "缓存是纯 LF");
            JsonObject saved = Json.parse(text);
            check(saved.get("version").getAsInt() == CategoryModel.CACHE_VERSION, "缓存带 version");
            check(saved.getAsJsonObject("entries").has("standing sex from behind"), "缓存键是规范化后的短语");
            check(saved.getAsJsonObject("entries").getAsJsonArray("standing sex from behind").size() == 2, "缓存片段完整");

            // 第二次运行：新实例读同一个缓存文件，0 次模型调用。
            FakeCaller again = new FakeCaller();
            CategoryModel second = new CategoryModel(root, again);
            Map<String, List<CategoryModel.Segment>> reread = second.resolve(List.of(STANDING));
            check(again.calls() == 0, "第二次运行 0 次模型调用，实际 " + again.calls());
            check(reread.get(STANDING).equals(List.of(new CategoryModel.Segment("standing sex", "动作"),
                    new CategoryModel.Segment("from behind", "镜头"))), "缓存读回来的片段一致：" + reread.get(STANDING));
            check(second.status().get("lastCachedHits").getAsInt() == 1, "status 报出缓存命中");
            check(second.status().get("lastPhrases").getAsInt() == 0, "缓存命中时没有发送任何短语");
            check(second.cachedCount() == 2, "缓存条数可读：" + second.cachedCount());
        } finally {
            TestCleanup.deleteQuietly(root);
        }
    }

    /** 用户那 4 条：/prompt drop 镜头 之后字符串精确相等，格式干净，LoRA 保留。 */
    private static void userPhrasesDrop() throws Exception {
        Path root = fixture();
        try {
            FakeCaller caller = new FakeCaller()
                    .answer(VIEWS, VIEWS, "镜头")
                    .answer(STANDING, "standing sex", "动作", "from behind", "镜头")
                    .answer(PANTIES, "male pulling her panties aside", "动作", "from behind", "镜头")
                    .answer(CLOSEUP, "extreme close-up", "镜头", CLOSEUP_KEPT, "动作");
            CategoryModel model = new CategoryModel(root, caller);
            Bot.CategorySurgery drop = new Bot.CategorySurgery(Set.of(), Set.of("镜头"), false);

            String before = "1girl, solo, " + VIEWS + ", " + STANDING + ", " + PANTIES + ", " + CLOSEUP + ", <lora:test_lora:1>";
            String expected = "1girl, solo, standing sex, male pulling her panties aside, " + CLOSEUP_KEPT + ", <lora:test_lora:1>";
            List<String> kept = new ArrayList<>(), removed = new ArrayList<>(), tags = new ArrayList<>();
            String after = Bot.applyCategorySurgery(before, drop, null, kept, removed, tags, model);

            check(after.equals(expected), "只摘掉镜头片段、其余一字不改：\n实际 " + after + "\n期望 " + expected);
            check(caller.calls() == 1, "一条命令只问一次模型（批量），实际 " + caller.calls());
            // 路由：送到模型的每一条都必须是"本地判不出来"的短语；本地能定类的短标签一条都不许发。
            // （本地分类器由方案 A 负责并在持续收紧，所以这里断言的是"该发的都发了、不该发的都没发"，
            //  而不是一个写死的条数——standing sex from behind 本地一旦能判，就不该再花模型的钱。）
            for (String sent : caller.sent())
                check(CategoryModel.needsModel(null, sent), "只送本地判不出来的短语，实际送了：" + sent);
            for (String phrase : List.of(STANDING, PANTIES, CLOSEUP))
                if (CategoryModel.needsModel(null, phrase)) check(caller.asked(phrase), "本地判不出来的短语必须送到模型：" + phrase);
            check(caller.asked(CLOSEUP), "长从句 extreme close-up… 必须问模型（本地只命中镜头词，不足以整条定类）");
            check(!caller.asked(VIEWS), "multiple views 本地就认定整条是镜头，不问模型");
            check(caller.sent().size() >= 1 && caller.sent().size() <= 3, "送去模型的短语不超过本地判不出来的那些：" + caller.sent());
            check(after.contains("1girl") && after.contains("solo"), "非目标词条一个字都不动：" + after);
            check(tags.equals(List.of("<lora:test_lora:1>")), "LoRA 标签原样保留：" + tags);
            check(!after.contains("from behind"), "from behind 被摘掉");
            check(!after.contains("extreme close-up"), "取景那段 extreme close-up 被摘掉");
            check(!after.contains(VIEWS), "multiple views 整条删掉");
            check(!kept.isEmpty() && !removed.isEmpty(), "回执明细同时记了保留与删除：" + kept + " / " + removed);
            check(removed.stream().anyMatch(line -> line.contains(CLOSEUP) && line.contains("模型判定")),
                    "回执说明长从句是模型判定后只删镜头片段：" + removed);
            check(!after.contains("  "), "没有双重空格：" + after);
            check(!after.contains(",,") && !after.contains(" ,") && !after.startsWith(",") && !after.endsWith(","),
                    "没有多余的逗号：" + after);

            // 逐条精确对照（缓存命中，不再调用模型）。
            String[] pairs = {VIEWS, "", STANDING, "standing sex", PANTIES, "male pulling her panties aside",
                    CLOSEUP, CLOSEUP_KEPT};
            for (int index = 0; index + 1 < pairs.length; index += 2) {
                String single = Bot.applyCategorySurgery(pairs[index], drop, null, new ArrayList<>(), new ArrayList<>(),
                        new ArrayList<>(), model);
                check(single.equals(pairs[index + 1]), "对照 " + pairs[index] + " → [" + single + "]（期望 [" + pairs[index + 1] + "]）");
            }
            check(caller.calls() == 1, "逐条对照全部命中缓存，仍然只有 1 次调用：" + caller.calls());
        } finally {
            TestCleanup.deleteQuietly(root);
        }
    }

    /**
     * 三种失败兜底：调用抛异常 / 返回乱 JSON / 片段拼不回原短语 → **交给模型的这些短语一个字都不删**，日志有 warn。
     *
     * <p>用两条<b>一定</b>会走模型的长从句（≥5 词，本地"整词命中"不足以整条定类）：失败时它们必须原样保留，
     * 而不是退回本地的部分片段判定去删。
     */
    private static void failureModes() throws Exception {
        check(CategoryModel.needsModel(null, PANTIES) && CategoryModel.needsModel(null, CLOSEUP),
                "这两条长从句一定走模型（本地判不出来）");
        for (String mode : List.of("throw", "garbage", "broken")) {
            Path root = fixture();
            try {
                FakeCaller caller = new FakeCaller().failure(mode).answer(PANTIES, "male pulling her panties aside", "动作", "from behind", "镜头");
                CategoryModel model = new CategoryModel(root, caller);
                String before = "1girl, solo, " + PANTIES + ", " + CLOSEUP;
                List<String> removed = new ArrayList<>(), kept = new ArrayList<>();
                String[] after = new String[1];
                String warnings = captureErr(() -> after[0] = Bot.applyCategorySurgery(before,
                        new Bot.CategorySurgery(Set.of(), Set.of("镜头"), false), null, kept, removed, new ArrayList<>(), model));
                check(after[0] != null && after[0].equals(before), "[" + mode + "] 失败时一个字都不删：[" + after[0] + "]");
                check(removed.isEmpty(), "[" + mode + "] 失败时不把任何词条记成删除：" + removed);
                check(warnings.contains("[WARN]") && warnings.contains("模型"), "[" + mode + "] 日志里有 warn：" + warnings.strip());
                check(caller.calls() == 1, "[" + mode + "] 只问一次就收敛（不重试打转）：" + caller.calls());
                check(model.status().get("lastFallbacks").getAsInt() == 2, "[" + mode + "] 两条都算兜底：" + model.status().get("lastFallbacks"));
                check(model.cachedCount() == 0, "[" + mode + "] 失败的结果不进缓存");
                check(!Files.exists(model.cacheFile()), "[" + mode + "] 没有可采信的片段就不写缓存");
            } finally {
                TestCleanup.deleteQuietly(root);
            }
        }
    }

    /** 不设额度上限：20 条与 60 条未解析短语全部送到模型（自动分批），每条都拿到分类结果。 */
    private static void volumeWithoutLimits() throws Exception {
        Path root = fixture();
        try {
            FakeCaller caller = new FakeCaller();
            CategoryModel model = new CategoryModel(root, caller);
            List<String> twenty = new ArrayList<>();
            for (int index = 1; index <= 20; index++) twenty.add("phrase number " + index + " standing");
            Map<String, List<CategoryModel.Segment>> resolved = model.resolve(twenty);
            check(resolved.size() == 20, "20 条全部拿到片段：" + resolved.size());
            check(caller.sent().size() == 20, "20 条在请求里一条都不少（跨批次合并计数 20）：" + caller.sent().size());
            check(new HashSet<>(caller.sent()).size() == 20, "20 条互不重复地送到了模型");
            check(caller.requests.size() >= 2, "20 条超过单次请求上限时自动分批：" + caller.requests.size() + " 批");
            check(caller.calls() == caller.requests.size(), "一批一次调用：" + caller.calls());
            check(caller.requests.stream().allMatch(batch -> batch.size() <= CategoryModel.BATCH_PHRASES),
                    "每批不超过复用客户端的传输上限 " + CategoryModel.BATCH_PHRASES + " 条");
            check(caller.requests.stream().allMatch(batch -> String.join("", batch).length() <= CategoryModel.BATCH_CHARS + 64),
                    "每批不超过字符预算 " + CategoryModel.BATCH_CHARS);
            check(model.status().get("lastPhrases").getAsInt() == 20, "status 报出本次发送 20 条");
            check(model.status().get("lastFallbacks").getAsInt() == 0, "没有一条被上限挡成保守处理");
            int classified = 0;
            for (String phrase : twenty) if (resolved.containsKey(phrase)) classified++;
            check(classified == 20, "20 条每条都有分类结果：" + classified);
            check(caller.calls() == 2, "20 条分成 2 批（12 + 8）：" + caller.calls());
        } finally {
            TestCleanup.deleteQuietly(root);
        }

        Path bulkRoot = fixture();
        try {
            FakeCaller caller = new FakeCaller();
            CategoryModel model = new CategoryModel(bulkRoot, caller);
            List<String> sixty = new ArrayList<>();
            for (int index = 1; index <= 60; index++) sixty.add("bulk phrase " + index + " standing sex");
            Map<String, List<CategoryModel.Segment>> resolved = model.resolve(sixty);
            check(resolved.size() == 60, "60 条全部拿到片段：" + resolved.size());
            check(caller.sent().size() == 60, "60 条全部出现在发出的请求里（跨批次合并计数 60）：" + caller.sent().size());
            check(new HashSet<>(caller.sent()).equals(new HashSet<>(sixty)), "请求里的短语集合与 60 条完全一致");
            check(caller.requests.size() >= 5, "60 条自动分成多批：" + caller.requests.size() + " 批");
            check(caller.requests.stream().allMatch(batch -> batch.size() <= CategoryModel.BATCH_PHRASES), "每批 ≤ 12 条");
            check(model.status().get("lastFallbacks").getAsInt() == 0, "60 条没有一条退化成保守处理");
            int modified = 0;
            for (String phrase : sixty) if (!model.dropFragments(phrase, Set.of("镜头"), null).equals(phrase)) modified++;
            check(modified == 60, "60 条每条都能按片段删目标词：" + modified);
            check(caller.calls() == caller.requests.size(), "60 条的调用次数等于批数（缓存之后不再增加）：" + caller.calls());
            int callsAfter = caller.calls();
            check(model.dropFragments(sixty.get(0), Set.of("镜头"), null).equals("bulk phrase 1 standing"), "逐条再删仍按缓存结果");
            check(caller.calls() == callsAfter, "缓存命中时不再调用模型");
            check(model.cachedCount() == 60, "60 条都写进了缓存：" + model.cachedCount());
        } finally {
            TestCleanup.deleteQuietly(bulkRoot);
        }
    }

    /** 开关：off 时 0 次调用、不读缓存，行为退回本地判定。 */
    private static void switchOff() throws Exception {
        Path root = fixture();
        try {
            FakeCaller caller = new FakeCaller().answer(STANDING, "standing sex", "动作", "from behind", "镜头");
            CategoryModel warm = new CategoryModel(root, caller);
            warm.resolve(List.of(STANDING));
            check(Files.isRegularFile(warm.cacheFile()), "缓存文件已经写出来了");
            check(caller.calls() == 1, "预热调用 1 次");

            CategoryModel off = new CategoryModel(root, caller);
            off.setEnabled(false);
            check(!off.enabled(), "enabled() 报出关闭");
            Map<String, List<CategoryModel.Segment>> none = off.resolve(List.of(STANDING, PANTIES));
            check(none.isEmpty(), "关闭时不给出任何模型判定：" + none);
            check(caller.calls() == 1, "关闭时 0 次调用（调用次数没变）：" + caller.calls());
            check(off.status().get("cached").getAsInt() == 0, "关闭时不读缓存");
            check(off.status().get("enabled").getAsBoolean() == false, "status 报出关闭");
            check(off.status().get("lastPhrases").getAsInt() == 0, "关闭时一条短语都不发");
            check(off.dropFragments(STANDING, Set.of("镜头"), null).equals(STANDING), "关闭时单条也不动");

            Bot.CategorySurgery drop = new Bot.CategorySurgery(Set.of(), Set.of("镜头"), false);
            String base = "1girl, " + STANDING + ", " + VIEWS;
            String withOff = Bot.applyCategorySurgery(base, drop, null, new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), off);
            String localOnly = Bot.applyCategorySurgery(base, drop, null, new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), null);
            check(withOff.equals(localOnly), "关闭时的结果与纯本地一致：[" + withOff + "] / [" + localOnly + "]");
            check(caller.calls() == 1, "关闭时筛选过程一个请求都没发：" + caller.calls());
        } finally {
            TestCleanup.deleteQuietly(root);
        }
    }

    /** 生产 Caller 的编码与校验、路由判据、整条删的护栏、格式收拾。 */
    private static void encoderAndGuards() throws Exception {
        List<String> phrases = List.of(STANDING, VIEWS);
        String json = CategoryModel.phrasesJson(phrases, List.of("2|" + VIEWS + "=镜头", "1|standing sex=动作|from behind=镜头"));
        Map<String, List<CategoryModel.Segment>> parsed = CategoryModel.parse(json);
        check(parsed.size() == 2, "两条都编码成了规范 phrases JSON：" + json);
        check(parsed.get(STANDING).equals(List.of(new CategoryModel.Segment("standing sex", "动作"),
                new CategoryModel.Segment("from behind", "镜头"))), "编码回来的片段正确：" + parsed.get(STANDING));
        check(parsed.get(VIEWS).equals(List.of(new CategoryModel.Segment(VIEWS, "镜头"))), "整条镜头的短语编码正确");
        check(CategoryModel.parse(CategoryModel.phrasesJson(List.of(STANDING), List.of("1|standing sexx=动作"))).isEmpty(),
                "片段拼不回原短语的条目被丢掉（退回本地）");
        check(CategoryModel.parse(CategoryModel.phrasesJson(List.of(STANDING), List.of("1|standing sex=发型|from behind=镜头"))).isEmpty(),
                "类别不在既有类别集合里的条目被丢掉");
        check(CategoryModel.parse(CategoryModel.phrasesJson(List.of(STANDING), List.of("9|standing sex=动作|from behind=镜头"))).isEmpty(),
                "序号对不上的条目被丢掉");
        check(CategoryModel.parse(CategoryModel.phrasesJson(List.of(STANDING), List.of("1|standing sex 动作|from behind=镜头"))).isEmpty(),
                "格式不对（缺 =）的条目被丢掉");
        check(CategoryModel.parse("```json\n" + json + "\n```").size() == 2, "带 Markdown 围栏也能解析");
        check(CategoryModel.parse("说明一下：" + json + " 就这样").size() == 2, "前后带废话也能解析");
        try {
            CategoryModel.parse("这不是 JSON，只是一段废话");
            check(false, "不是 JSON 时应当抛错");
        } catch (IOException expected) {
            check(true, "不是 JSON 时抛错，调用方保守处理");
        }
        try {
            CategoryModel.parse("{\"parts\":[]}");
            check(false, "缺少 phrases 数组时应当抛错");
        } catch (IOException expected) {
            check(true, "缺少 phrases 数组时抛错，调用方保守处理");
        }
        check(CategoryModel.instruction(List.of(STANDING, VIEWS)).equals("1. " + STANDING + "\n2. " + VIEWS),
                "用户消息按序号逐行给短语：" + CategoryModel.instruction(List.of(STANDING, VIEWS)));
        check(CategoryModel.RULES.contains("parts") && CategoryModel.RULES.contains("镜头") && CategoryModel.RULES.contains("拼起来必须与原文完全一致"),
                "提示词要求只返回 JSON、限定类别、要求片段拼回原文");

        // 路由判据：本地能定类的短词条不问模型，判不出来的复合短语与长从句才问。
        // （本地分类器的具体输出由方案 A 负责并还在收紧，所以这里只断言本类保证的性质与用户点名的关键短语。）
        check(!CategoryModel.needsModel(null, VIEWS), "multiple views 本地整条就是镜头 → 不问模型");
        check(!CategoryModel.needsModel(null, "night"), "单词条不问模型");
        check(!CategoryModel.needsModel(null, "<lora:test_lora:1>"), "LoRA 标签任何筛选都不动，也不问模型");
        check(CategoryModel.needsModel(null, "holding glowing crystal from above"), "多个方面混在一起的短语 → 问模型");
        check(CategoryModel.needsModel(null, CLOSEUP), "长从句本地不该整条定类 → 问模型");
        check(CategoryModel.needsModel(null, PANTIES), "长从句本地不该整条定类 → 问模型");
        check(CategoryModel.batches(List.of(STANDING, PANTIES)).size() == 1, "两条短语一批");
        check(CategoryModel.batches(new ArrayList<>(Collections.nCopies(25, "x y"))).size() == 3, "25 条按 12 条一批切成 3 批");

        // 整条删的护栏：本地判成"其他"的复合短语，模型说"整条都是镜头"也不许整条吃掉。
        Path root = fixture();
        try {
            FakeCaller caller = new FakeCaller().answer(STANDING, STANDING, "镜头").answer(VIEWS, VIEWS, "镜头");
            CategoryModel model = new CategoryModel(root, caller);
            String[] verdict = new String[1];
            String warnings = captureErr(() -> verdict[0] = model.dropFragments(STANDING, Set.of("镜头"), null));
            check(verdict[0].equals(STANDING), "本地判不出来的复合短语不许被整条吃掉：" + verdict[0]);
            check(warnings.contains("不整条删"), "拒绝整条删时记 warn：" + warnings.strip());
            check(model.dropFragments(VIEWS, Set.of("镜头"), null).equals(""), "本地也是镜头的整条短语才整条删");
            check(model.dropFragments(STANDING, Set.of("姿势"), null).equals(STANDING), "没有目标类别的片段时一个字都不改");

            FakeCaller messy = new FakeCaller().answer("1girl solo portrait", " 1girl solo ", "人物", " portrait ", "镜头");
            CategoryModel messyModel = new CategoryModel(root, messy);
            String cleaned = messyModel.dropFragments("1girl solo portrait", Set.of("镜头"), null);
            check(cleaned.equals("1girl solo"), "拼回后没有双空格、没有首尾空白：[" + cleaned + "]");
        } finally {
            TestCleanup.deleteQuietly(root);
        }
    }

    /** 缓存健壮性：损坏的缓存只记 warn 并按空缓存继续；版本不符时旧缓存不被当成命中。 */
    private static void cacheRobustness() throws Exception {
        Path root = fixture();
        try {
            Files.writeString(root.resolve("data/category-model-cache.json"), "{ 这不是 JSON", StandardCharsets.UTF_8);
            FakeCaller caller = new FakeCaller().answer(STANDING, "standing sex", "动作", "from behind", "镜头");
            CategoryModel model = new CategoryModel(root, caller);
            String[] result = new String[1];
            String warnings = captureErr(() -> result[0] = model.dropFragments(STANDING, Set.of("镜头"), null));
            check(result[0].equals("standing sex"), "缓存损坏时照常问模型并工作：" + result[0]);
            check(warnings.contains("缓存读取失败"), "缓存损坏时记 warn：" + warnings.strip());
            check(caller.calls() == 1, "缓存损坏后重新问一次：" + caller.calls());
            JsonObject rewritten = Json.parse(Files.readString(model.cacheFile(), StandardCharsets.UTF_8));
            check(rewritten.get("version").getAsInt() == CategoryModel.CACHE_VERSION
                    && rewritten.getAsJsonObject("entries").size() == 1, "损坏的缓存被重写成可读结构");

            Files.writeString(model.cacheFile(), "{\"version\":99,\"entries\":{\"" + STANDING
                    + "\":[{\"text\":\"standing sex\",\"category\":\"动作\"},{\"text\":\"from behind\",\"category\":\"镜头\"}]}}",
                    StandardCharsets.UTF_8);
            FakeCaller later = new FakeCaller();
            CategoryModel versioned = new CategoryModel(root, later);
            Map<String, List<CategoryModel.Segment>> fromModel = versioned.resolve(List.of(STANDING));
            check(later.calls() == 1, "版本不符时旧缓存不被当成命中，重新问模型：" + later.calls());
            check(fromModel.containsKey(STANDING), "版本不符后仍能拿到结果");
            check(versioned.cachedCount() == 1, "版本不符后按新结果重建缓存：" + versioned.cachedCount());
        } finally {
            TestCleanup.deleteQuietly(root);
        }
    }

    private static Path fixture() throws IOException {
        Path root = Files.createTempDirectory("category-model-");
        Files.createDirectories(root.resolve("data"));
        return root;
    }

    /** 把 stderr 抓下来看 warn（Log.warn 写的就是当时的 System.err）；调用方用 RuntimeException 包检查异常。 */
    private static String captureErr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** 假 Caller：只按给定答案回答（绝不联网），记录每次请求里的短语，可切换成三种失败模式。 */
    private static final class FakeCaller implements CategoryModel.Caller {
        private final Map<String, List<CategoryModel.Segment>> answers = new LinkedHashMap<>();
        private final List<List<String>> requests = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private String mode = "answer";

        FakeCaller answer(String phrase, String... pieces) {
            List<CategoryModel.Segment> segments = new ArrayList<>();
            for (int index = 0; index + 1 < pieces.length; index += 2)
                segments.add(new CategoryModel.Segment(pieces[index], pieces[index + 1]));
            answers.put(key(phrase), List.copyOf(segments));
            return this;
        }

        FakeCaller failure(String value) { mode = value; return this; }

        int calls() { return calls.get(); }

        List<String> sent() {
            List<String> all = new ArrayList<>();
            for (List<String> batch : requests) all.addAll(batch);
            return all;
        }

        boolean asked(String phrase) {
            for (String sent : sent()) if (key(sent).equals(key(phrase))) return true;
            return false;
        }

        @Override public String ask(List<String> phrases) throws Exception {
            calls.incrementAndGet();
            requests.add(List.copyOf(phrases));
            if (mode.equals("throw")) throw new IOException("模拟网络失败");
            if (mode.equals("garbage")) return "模型今天不想干活，这一段是废话，不是 JSON。";
            JsonArray list = new JsonArray();
            for (String phrase : phrases) {
                List<CategoryModel.Segment> segments = answers.get(key(phrase));
                if (segments == null) {
                    // 没准备的短语：末词算镜头，其余算动作，保证每条都有可观察的"片段 → 类别"。
                    List<String> words = List.of(phrase.strip().split("\\s+"));
                    segments = words.size() < 2
                            ? List.of(new CategoryModel.Segment(phrase, "动作"))
                            : List.of(new CategoryModel.Segment(String.join(" ", words.subList(0, words.size() - 1)), "动作"),
                                       new CategoryModel.Segment(words.get(words.size() - 1), "镜头"));
                }
                if (mode.equals("broken")) segments = List.of(new CategoryModel.Segment(phrase + " extra", "其他"));
                JsonObject entry = new JsonObject();
                entry.addProperty("text", phrase);
                JsonArray pieces = new JsonArray();
                for (CategoryModel.Segment segment : segments) {
                    JsonObject value = new JsonObject();
                    value.addProperty("text", segment.text());
                    value.addProperty("category", segment.category());
                    pieces.add(value);
                }
                entry.add("segments", pieces);
                list.add(entry);
            }
            JsonObject object = new JsonObject();
            object.add("phrases", list);
            return Json.GSON.toJson(object);
        }
    }

    private static String key(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}

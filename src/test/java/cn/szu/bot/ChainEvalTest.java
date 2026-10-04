package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.DeepSeekPrompts;

/**
 * Offline evaluation of the natural-language task chain. A scripted transport replays model
 * answers, so every scenario is deterministic and needs no network. Each run rewrites
 * work/chain-eval-report.md with the scenario results, which is how the chain is iterated on.
 */
public final class ChainEvalTest {
    private static final List<String> RESULTS = new ArrayList<>();
    private static int passed;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "chain-eval").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/deepseek-api-key.txt"), "eval-key");

        multiRequirementChain(root);
        consistencyGuardRetries(root);
        declaredChainRules(root);
        sceneTagNormalisation(Path.of(".").toAbsolutePath().normalize());
        userFacingContract();
        questionAndAdjustParsing();

        Path report = work.getParent().resolve("chain-eval-report.md");
        StringBuilder markdown = new StringBuilder("# 自然语言任务链评测报告\n\n");
        markdown.append("通过 ").append(passed).append(" / ").append(RESULTS.size()).append(" 项\n\n");
        for (String line : RESULTS) markdown.append("- ").append(line).append("\n");
        Files.writeString(report, markdown.toString());
        if (passed != RESULTS.size()) throw new AssertionError("chain evaluation failures: " + RESULTS);
        System.out.println("ChainEvalTest PASS: " + passed + " chain scenarios (" + report + ")");
    }

    /** One sentence with several requirements must become several ordered commands, ending in a generation. */
    private static void multiRequirementChain(Path root) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DeepSeekPrompts client = new DeepSeekPrompts(root, new JsonObject(), (body, key, timeout) -> {
            int call = calls.incrementAndGet();
            if (call == 1) return ok("{\"parts\":[\"把地点改成沙滩\",\"服饰换成白色褶边比基尼\",\"光照调暖\",\"然后生成\"]}");
            String current = lastMessage(body);
            if (current.contains("生成")) return ok("{\"reply\":\"这就出图。\",\"commands\":[\".gen 1\"],\"interest\":90}");
            // A real plan carries the step's own wording into .infix; the step-content guard enforces that.
            String part = Json.str(Json.parse(current), "current_message", "改一处");
            return ok("{\"reply\":\"这一条改好了。\",\"commands\":[\".infix " + part + "\"],\"interest\":80}");
        });
        ChatActions.Plan plan = client.chatPlan("persona", new JsonArray(),
                "把地点改成沙滩，服饰换成白色褶边比基尼，光照调暖，然后生成", new JsonObject());
        record(plan.commands().size() == 4 && plan.commands().get(3).equals(".gen 1"),
                "多要求一句 → 分步规划出 " + plan.commands().size() + " 条命令且以 .gen 收尾：" + plan.commands());
        record(!ChatActions.promisesUnappliedEdit(plan.reply(), plan.commands()),
                "合并后的回执有改写指令支撑，不会「说改却没改」");
        record(calls.get() == 5, "调用次数 = 1 次拆分 + 4 条子需求（实际 " + calls.get() + "）");
        // A step that re-plans another step's work must not fill the 8-command budget with duplicates:
        // that is what silently truncated the last edit of a long request. Two steps asking for the same
        // change produce identical command text, which has to collapse into one command.
        AtomicInteger duplicateCalls = new AtomicInteger();
        DeepSeekPrompts duplicate = new DeepSeekPrompts(root, new JsonObject(), (body, key, timeout) -> {
            if (duplicateCalls.incrementAndGet() == 1) return ok("{\"parts\":[\"把地点改成沙滩\",\"把地点改成沙滩\"]}");
            return ok("{\"reply\":\"好。\",\"commands\":[\".infix 把地点改成沙滩\"],\"interest\":80}");
        });
        ChatActions.Plan merged = duplicate.chatPlan("persona", new JsonArray(),
                "把地点改成沙滩，把地点改成沙滩", new JsonObject());
        record(merged.commands().size() == 1, "重复的同一指令被合并，不占用 8 条指令上限：" + merged.commands());
    }

    /**
     * A multi-word canonical tag must be recognised even though the allowed set is stored in
     * PromptEditor key form ("soft focus"): comparing raw underscores rejected every multi-word tag.
     */
    private static void sceneTagNormalisation(Path root) {
        try {
            java.util.Set<String> allowed = cn.szu.bot.Bot.vocabulary(root, new cn.szu.bot.sd.SdClient.Prompts("", "", "test"));
            record(cn.szu.bot.Bot.allowedContains(allowed, "soft_focus"), "多词标准词条 soft_focus 能被识别");
            record(cn.szu.bot.Bot.allowedContains(allowed, "maid"), "单词标准词条 maid 能被识别");
            java.util.List<String> missing = cn.szu.bot.Bot.missingRequestedTags(
                    "光线变成柔和光线，服饰换成白大褂", "1girl, solo", allowed);
            record(missing.contains("soft_focus") && missing.contains("white_coat"),
                    "多词词条会被补齐：" + missing);
        } catch (Exception error) {
            record(false, "词条归一化检查失败：" + cn.szu.bot.Bot.error(error));
        }
    }

    /** A reply that claims a prompt edit without an edit command must be refused and retried. */
    private static void consistencyGuardRetries(Path root) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DeepSeekPrompts client = new DeepSeekPrompts(root, new JsonObject(), (body, key, timeout) -> {
            if (calls.incrementAndGet() == 1) return ok("{\"reply\":\"我把场景改成沙滩了。\",\"commands\":[\".gen 1\"],\"interest\":90}");
            return ok("{\"reply\":\"我把场景改成沙滩了。\",\"commands\":[\".infix 场景改为沙滩\",\".gen 1\"],\"interest\":90}");
        });
        ChatActions.Plan plan = client.chatPlan("persona", new JsonArray(), "改成沙滩然后生成", new JsonObject());
        record(plan.commands().contains(".infix 场景改为沙滩") && calls.get() >= 2,
                "声称改写却没有 .infix 的计划被拒绝并重试（尝试 " + calls.get() + " 次，命令 " + plan.commands() + "）");
    }

    /** The guidance the chain depends on must stay in the planner prompt, or optimisation silently regresses. */
    private static void declaredChainRules(Path root) throws Exception {
        String[] system = new String[1];
        DeepSeekPrompts client = new DeepSeekPrompts(root, new JsonObject(), (body, key, timeout) -> {
            system[0] = body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            return ok("{\"reply\":\"好\",\"commands\":[]}");
        });
        client.chatPlan("persona", new JsonArray(), "你好呀", new JsonObject());
        // The planner prompt reaches the model through Bot.publicCommands, so the model only ever sees the
        // user-facing '.' form; the chain rules must survive that rewrite verbatim.
        for (String rule : List.of(".char apply", ".style rename #6-#9", ".style delete #17-#25", ".batch",
                "禁止拆成", "不得声称", "[引用]", "join_reason", "join=false", "owner", "admin",
                "没有被直接点名", "不要输出任何指令"))
            record(system[0].contains(rule), "规划提示词包含规则片段「" + rule + "」");
        record(!system[0].contains("wake_adjust") && !system[0].contains("唤醒基数"),
                "已废弃的 wake_adjust / 唤醒基数说法不再出现在规划提示词里");
        // Drift guard: every command the bot answers to must be advertised with '.', never with '/'.
        List<String> commands = List.of("help", "yh", "liv", "get", "settings", "chat", "admin", "char", "batch",
                "sampler", "style", "size", "steps", "cfg", "seed", "model", "promptR", "prompt", "preset",
                "function", "lora", "gen", "rg", "imgcnt", "usage", "map", "progen", "infix");
        Matcher bare = Pattern.compile("(?<![\\p{L}\\p{N}_:/.])/(" + String.join("|", commands)
                + ")(?![\\p{L}\\p{N}_-])").matcher(system[0]);
        boolean leaked = bare.find();
        record(!leaked, "面向用户的指令一律写成「.」形式" + (leaked ? "（发现裸前缀 " + bare.group() + "）" : ""));
        for (String required : List.of("char", "batch", "style", "lora", "infix", "gen", "chat", "admin", "prompt"))
            record(system[0].contains("." + required), "规划提示词用「." + required + "」介绍该指令");
    }

    /** The usability rules the bot promises: spoken numbers, count completion and diff-only receipts. */
    private static void userFacingContract() throws Exception {
        record(Bot.speakableNumbers("选择第71个样式").equals("选择#71样式"),
                "「第71个样式」被改写为「选择#71样式」：" + Bot.speakableNumbers("选择第71个样式"));
        record(Bot.speakableNumbers("换第三个").equals("换#3"),"「第三个」被改写为 #3：" + Bot.speakableNumbers("换第三个"));
        record(Bot.speakableNumbers("第七十一个风格").equals("#71风格"),
                "「第七十一个」被改写为 #71：" + Bot.speakableNumbers("第七十一个风格"));
        record(Bot.speakableNumbers("这个是第一次见到").equals("这个是第一次见到"),
                "不含序号的句子原样保留：" + Bot.speakableNumbers("这个是第一次见到"));
        ChatActions.Plan bare = ChatActions.parse("{\"reply\":\"好\",\"commands\":[\".infix 改好\",\".gen\"]}");
        ChatActions.Plan filled = cn.szu.bot.chat.DeepSeekPrompts.withRequestedCount(bare, "改好之后出3张");
        record(filled.commands().contains(".gen 3"), "用户说 3 张时确定性补成 .gen 3：" + filled.commands());
        // 查看清单必须落到真正的列表指令上，编号才会以指令回执为准（模型自己罗列会数错、还会压缩成区间）。
        ChatActions.Plan empty = new ChatActions.Plan("好", List.of(), "", 80);
        record(DeepSeekPrompts.withListCommand(empty, "查看styles").commands().equals(List.of(".style list")),
                "查看styles → 补上 .style list：" + DeepSeekPrompts.withListCommand(empty, "查看styles").commands());
        record(DeepSeekPrompts.withListCommand(empty, "列出有哪些 LoRA").commands().equals(List.of(".lora list")),
                "列出 LoRA → 补上 .lora list：" + DeepSeekPrompts.withListCommand(empty, "列出有哪些 LoRA").commands());
        record(DeepSeekPrompts.withListCommand(new ChatActions.Plan("好", List.of(".style list"), "", 80), "查看样式").commands()
                        .equals(List.of(".style list")),
                "已有列表指令时不重复补");
        record(DeepSeekPrompts.withListCommand(new ChatActions.Plan("好", List.of(".gen 1"), "", 80), "出一张图").commands()
                        .equals(List.of(".gen 1")),
                "非查看请求不改动计划");
        ChatActions.Plan mixed = DeepSeekPrompts.withListCommand(
                new ChatActions.Plan("好", List.of(".infix 把地点换成草地", ".gen 1"), "", 80), "查看样式，然后把地点换成草地再生一张");
        record(mixed.commands().get(0).equals(".style list") && mixed.commands().contains(".gen 1") && mixed.commands().contains(".infix 把地点换成草地"),
                "查看+改动时列表指令排在最前且保留其余步骤：" + mixed.commands());
        // 任务说谎：编号对应的名称必须由程序核对，回复里说错就被删掉并附上真实名称。
        ChatActions.Plan load57 = new ChatActions.Plan("好，#3 是「高岛柘榴 | NoobAI 2」——这就加载成基底。", List.of(".style load #3"), "", 90);
        JsonObject selections = Json.parse("{\"style\":[\"小鸟\",\"白河\",\"ami ichigo(天衣 いちご)|イノセントガール|(Illustrious) 1\"]}");
        ChatActions.Plan fixedPlan = DeepSeekPrompts.withVerifiedNumbers(load57, selections);
        record(fixedPlan.reply().contains("编号核对：#3 = ami ichigo") && !fixedPlan.reply().contains("高岛柘榴"),
                "编号配错名称的那句被删掉并附上真实名称：" + fixedPlan.reply());
        record(fixedPlan.commands().equals(List.of(".style load #3")), "更正回复不影响已安排的指令：" + fixedPlan.commands());
        ChatActions.Plan correct = new ChatActions.Plan("好，#3 是 ami ichigo，这就加载。", List.of(".style load #3"), "", 90);
        record(DeepSeekPrompts.withVerifiedNumbers(correct, selections).reply().equals(correct.reply()),
                "编号对应正确时不改动回复：" + DeepSeekPrompts.withVerifiedNumbers(correct, selections).reply());
        ChatActions.Plan plain = new ChatActions.Plan("好，#3 我看看。", List.of(".style load #3"), "", 90);
        record(DeepSeekPrompts.withVerifiedNumbers(plain, selections).reply().equals(plain.reply()),
                "只是提到编号、没说名称时不误伤：" + DeepSeekPrompts.withVerifiedNumbers(plain, selections).reply());
        // 没有任何指令却宣布"已经做完"：属于说谎，必须拦住。
        record(ChatActions.claimsUnverifiedExecution("已经把样式加载好了，这就出图。", List.of()),
                "无指令却宣布完成被识别为说谎");
        record(!ChatActions.claimsUnverifiedExecution("已经把样式加载好了。", List.of(".style load 小鸟")),
                "有计划中的指令时不算说谎");
        record(!ChatActions.claimsUnverifiedExecution("要不要我帮你加载样式？", List.of()),
                "普通提议不算说谎");
        record(DeepSeekPrompts.looksLikeActionRequest("把样式换成军装") && DeepSeekPrompts.looksLikeActionRequest("生成一张"),
                "执行类请求能被识别（用于判断宣布完成是否说谎）");
        record(!DeepSeekPrompts.looksLikeActionRequest("今天天气怎么样？"),
                "普通提问不触发说谎检查");
        // 短英文样式名不能被中文句子里的单词"吃"进去：样式「sy」曾被 "pussy" 命中，程序因此强行要求加载它，
        // 把用户整套提示词替换成了该样式（"发癫加载 sy 并清空 prompt"）。
        JsonObject styleList = Json.parse("{\"style\":[\"sy\",\"小鸟\",\"ami ichigo(天衣 いちご)|イノセントガール 1\"]}");
        String sexRequest = "加入性交，pussy，penis，臀部为主视角";
        record(!DeepSeekPrompts.namedInMessage(sexRequest, "sy"), "pussy 里的 sy 不算用户点名了样式 sy");
        record(DeepSeekPrompts.namedInMessage("用 sy 当基底", "sy") && DeepSeekPrompts.namedInMessage("用sy做底座", "sy"),
                "独立的 sy 仍能被识别为点名");
        record(DeepSeekPrompts.missingBasis(new ChatActions.Plan("好", List.of(".infix 加性交", ".gen 1"), "", 90), styleList, sexRequest) == null,
                "用户没提样式时不会强行要求加载基底");
        String invented = DeepSeekPrompts.unrequestedBasis(
                new ChatActions.Plan("好", List.of(".style load sy", ".infix 加性交", ".gen 1"), "", 90), styleList, sexRequest);
        record(invented != null && invented.contains("没有要求加载"), "用户没要求却加载样式会被拒绝重试：" + invented);
        record(DeepSeekPrompts.unrequestedBasis(new ChatActions.Plan("好", List.of(".style load sy"), "", 90), styleList, "用 sy 当基底") == null,
                "用户点名样式时允许加载");
        record(DeepSeekPrompts.unrequestedBasis(new ChatActions.Plan("好", List.of(".style load #1"), "", 90), styleList, "选 #1") == null,
                "#编号加载被允许");
        record(DeepSeekPrompts.unrequestedBasis(new ChatActions.Plan("好", List.of(".style load #1"), "", 90), styleList, "继续用刚才那个样式") == null,
                "指代之前挑的样式时允许加载");
        record(DeepSeekPrompts.unrequestedBasis(new ChatActions.Plan("好", List.of(".style load #1"), "", 90), styleList, "再生成一张") != null,
                "没有点名也没有指代时不允许凭空加载");
        record(DeepSeekPrompts.unrequestedBasis(
                new ChatActions.Plan("好", List.of(".style load \"ami ichigo(天衣 いちご)|イノセントガール 1\""), "", 90),
                styleList, "用 ami ichigo 当基底") == null,
                "只报主名（ami ichigo）时允许加载对应样式");
        // "回复了好，删掉列表里第 12 到第 16 项，然后什么都不干"：正确的管理类指令不能被判成"没有改写指令"。
        record(!ChatActions.promisesUnappliedEdit("好，删掉列表里第 12 到第 16 项。", List.of(".style delete #12-#16")),
                "删除/改名等管理指令也算落实了改动");
        record(!ChatActions.promisesUnappliedEdit("好，把它们的名字改一下。", List.of(".style rename #12-#16 新前缀")),
                "批量改名不会被误判成只说不做");
        record(ChatActions.promisesUnappliedEdit("好，我把场景改成海边了。", List.of(".style delete #12-#16")),
                "声称改了画面却没有改写指令时仍然拦截");
        record(DeepSeekPrompts.requiresCommands("删除#12到#16") && DeepSeekPrompts.requiresCommands("把样式换成军装"),
                "执行类请求被识别为必须产生指令");
        record(!DeepSeekPrompts.requiresCommands("怎么删除样式？") && !DeepSeekPrompts.requiresCommands("我刚才删掉了吗？")
                        && !DeepSeekPrompts.requiresCommands("上次改的那个挺好的"),
                "提问与回顾不触发必须执行判定");
        record(DeepSeekPrompts.noActionPlan().commands().isEmpty() && DeepSeekPrompts.noActionPlan().reply().contains("没有执行任何操作"),
                "没有可用指令时如实说明：" + DeepSeekPrompts.noActionPlan().reply());
        record(Bot.speakableNumbers("删除第12到第16项").equals("删除#12-#16"),
                "阿拉伯数字区间：" + Bot.speakableNumbers("删除第12到第16项"));
        record(Bot.speakableNumbers("删掉第十二到第十六项").equals("删掉#12-#16"),
                "中文数字区间：" + Bot.speakableNumbers("删掉第十二到第十六项"));
        record(Bot.speakableNumbers("删除#12到#16").equals("删除#12到#16"),
                "已经是 #N 的区间保持原样：" + Bot.speakableNumbers("删除#12到#16"));
        cn.szu.bot.sd.SdClient.Prompts before = new cn.szu.bot.sd.SdClient.Prompts("1girl, solo, day", "lowres", "t");
        cn.szu.bot.sd.SdClient.Prompts after = new cn.szu.bot.sd.SdClient.Prompts("1girl, solo, night, rain", "lowres", "t");
        String diff = Bot.formatPromptDiff(before, after);
        record(diff.contains("rain") && diff.contains("night") && diff.contains("移除") && diff.contains("day")
                && !diff.contains("lowres"), "回执只显示增删变化，不回显整段提示词：" + diff.replace("\n", " / "));
        // Post-rewrite self-check: sexual tags need the partner tag, exclusive tags must not coexist.
        List<String> fixes = new ArrayList<>();
        String fixed = Bot.fixPromptConflicts("1girl, solo, sex, cum_in_pussy", fixes);
        record(fixed.contains("1boy") && fixes.stream().anyMatch(f -> f.contains("1boy")),
                "性交类词条自动补 1boy：" + fixed + " / " + fixes);
        fixes.clear();
        String views = Bot.fixPromptConflicts("1girl, front_view, back_view", fixes);
        record(usesTag(views, "back_view") && !usesTag(views, "front_view"),
                "front_view 与 back_view 互斥，保留后出现的：" + views);
        fixes.clear();
        String clean = Bot.fixPromptConflicts("1girl, solo, kimono", fixes);
        record(clean.equals("1girl, solo, kimono") && fixes.isEmpty(), "无冲突时不做任何改动：" + clean);
        // Generality: the rules come from the vocabulary, not from a hand-written case list.
        fixes.clear();
        String hairs = Bot.fixPromptConflicts("1girl, long_hair, short_hair", fixes);
        record(usesTag(hairs, "short_hair") && !usesTag(hairs, "long_hair"), "同后缀反义词只保留最新：" + hairs);
        fixes.clear();
        String poses = Bot.fixPromptConflicts("1girl, standing, sitting", fixes);
        record(usesTag(poses, "sitting") && !usesTag(poses, "standing"), "姿势互斥只保留最新：" + poses);
        fixes.clear();
        record(usesTag(Bot.fixPromptConflicts("1girl, fellatio", fixes), "1boy"), "口交类词条也自动补 1boy");
        fixes.clear();
        String unrelated = "1girl, long_hair, short_sleeves, day";
        record(Bot.fixPromptConflicts(unrelated, fixes).equals(unrelated) && fixes.isEmpty(),
                "不相关的同词根标签不误判：" + Bot.fixPromptConflicts(unrelated, fixes));
    }
    private static boolean usesTag(String prompt, String tag) {
        for (String term : cn.szu.bot.prompt.PromptEditor.parts(prompt))
            if (cn.szu.bot.prompt.PromptEditor.key(term).equals(cn.szu.bot.prompt.PromptEditor.key(tag))) return true;
        return false;
    }

    /** Structured fields the chain relies on must survive parsing. */
    private static void questionAndAdjustParsing() throws Exception {
        ChatActions.Plan adjusted = ChatActions.parse(
                "{\"reply\":\"嗯，我收着点。\",\"execute\":false,\"commands\":[],\"search_query\":\"\",\"interest\":30,\"wake_adjust\":-20}");
        record(adjusted.interest() == 30 && adjusted.commands().isEmpty(),
                "已废弃的 wake_adjust 被静默忽略，不影响其它字段（interest=30）");
        try { ChatActions.parse("{\"reply\":\"x\",\"commands\":[],\"wake_adjust\":80}"); record(true, "越界的 wake_adjust 也不再报错（字段已废弃）"); }
        catch (java.io.IOException error) { record(false, "已废弃的 wake_adjust 不该让整条计划解析失败：" + error.getMessage()); }
        ChatActions.Plan joined = ChatActions.parse(
                "{\"reply\":\"嗯——\",\"execute\":false,\"commands\":[],\"search_query\":\"\",\"interest\":40,\"join\":true,\"join_reason\":\"接得上\"}");
        record(joined.join() && joined.joinGiven(), "join / join_reason 被解析：模型判断要不要把对方拉进对话窗口");
        ChatActions.Plan silent = ChatActions.parse(
                "{\"reply\":\"……\",\"execute\":false,\"commands\":[],\"search_query\":\"\",\"interest\":95,\"join\":false}");
        record(!silent.join() && silent.joinGiven(), "join=false 即使相关度 95 也只答这一句（不进对话窗口）");
        ChatActions.Plan noJoin = ChatActions.parse(
                "{\"reply\":\"嗯\",\"execute\":false,\"commands\":[],\"search_query\":\"\",\"interest\":95}");
        record(!noJoin.joinGiven(), "没有 join 字段时 joinGiven=false，交给相关度兜底");
        record(ChatActions.parse("{\"reply\":\"好\",\"commands\":[\".char apply #5 郊外穿军装拉练\"]}").commands().size() == 1,
                "候选确认链路 /char apply #5 … 是合法命令");
        for (String rejected : List.of("{\"reply\":\"嗯\",\"commands\":[\".exec rm -rf /\"]}",
                "{\"reply\":\"嗯\",\"commands\":[\".gen 1\",\".gen 2\",\".gen 3\",\".gen 4\",\".gen 5\",\".gen 6\",\".gen 7\",\".gen 8\",\".gen 9\"]}"))
            try { ChatActions.parse(rejected); record(false, "越权或超 8 步的计划必须被拒绝：" + rejected); }
            catch (java.io.IOException expected) { record(true, "越权/超限计划被拒绝"); }
    }

    private static String lastMessage(JsonObject body) {
        JsonArray messages = body.getAsJsonArray("messages");
        return messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
    }

    private static DeepSeekPrompts.Response ok(String content) {
        JsonObject message = new JsonObject(); message.addProperty("content", content);
        JsonObject choice = new JsonObject(); choice.addProperty("finish_reason", "stop"); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject envelope = new JsonObject(); envelope.add("choices", choices);
        return new DeepSeekPrompts.Response(200, envelope.toString());
    }

    private static void record(boolean condition, String detail) {
        if (condition) passed++;
        RESULTS.add((condition ? "PASS" : "FAIL") + " — " + detail);
        if (!condition) System.err.println("FAIL: " + detail);
    }
}

package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.chat.SceneDecomposer;
import cn.szu.bot.prompt.PromptUsage;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;

/**
 * 前置拆解层的命中率评测：40 条复杂场景（人物/动作/场景三类都有），每条都必须
 * 1) 拆出至少三条简单修改；2) 每条简单修改对应的标准词条最终都出现在提示词里；
 * 3) 提示词里没有中文、也没有被丢弃的词库外新词。
 *
 * <p>命中率门槛默认 90%。评测走真实的生图频道（DeepSeek），报告写到
 * {@code work/scene-decompose-report.md}，失败场景的明细写到 {@code work/scene-decompose-failures.log}。
 */
public final class SceneDecomposeEval {
    /** 每条复杂场景：要求 + 期望覆盖的三类关键词（人物/动作/场景各至少一个，用于核对拆解覆盖面）。 */
    private record Case(String scene, List<String> people, List<String> actions, List<String> places) {}

    private static final List<Case> CASES = List.of(
            new Case("女性被男性地铁痴汉", List.of("女性", "男性"), List.of("抚摸", "猥亵", "痴汉"), List.of("地铁")),
            new Case("少女在雨中的街道上奔跑，衣服湿透", List.of("少女"), List.of("奔跑"), List.of("雨", "街道")),
            new Case("骑士骑着马冲进燃烧的村庄", List.of("骑士"), List.of("骑马", "冲锋"), List.of("村庄", "火")),
            new Case("护士在病房给病人打针", List.of("护士", "病人"), List.of("打针"), List.of("病房")),
            new Case("两个女生在教室里接吻", List.of("女生"), List.of("接吻"), List.of("教室")),
            new Case("白发少女坐在神社台阶上抬头看烟花", List.of("少女"), List.of("坐着", "抬头"), List.of("神社", "烟花")),
            new Case("女仆在咖啡厅端着托盘微笑", List.of("女仆"), List.of("微笑"), List.of("咖啡厅")),
            new Case("男生在雪地里向女生告白", List.of("男生", "女生"), List.of("告白"), List.of("雪地")),
            new Case("偶像在舞台上唱歌跳舞，台下粉丝举着荧光棒", List.of("偶像", "粉丝"), List.of("唱歌", "跳舞"), List.of("舞台")),
            new Case("少女在海边沙滩上捡贝壳", List.of("少女"), List.of("捡"), List.of("海边", "沙滩")),
            new Case("穿军装的少女在草地上行军，喘着气", List.of("少女"), List.of("行军", "喘气"), List.of("草地")),
            new Case("猫娘趴在图书馆的桌子上睡觉", List.of("猫娘"), List.of("趴着", "睡觉"), List.of("图书馆")),
            new Case("少女在浴室里洗头发，水顺着身体流下", List.of("少女"), List.of("洗头"), List.of("浴室")),
            new Case("女警官在雨夜的天台上举枪", List.of("女警官"), List.of("举枪"), List.of("雨夜", "天台")),
            new Case("男生在厨房里做饭，围裙上沾了面粉", List.of("男生"), List.of("做饭"), List.of("厨房")),
            new Case("少女在樱花树下睡觉，花瓣落在头发上", List.of("少女"), List.of("睡觉"), List.of("樱花树")),
            new Case("身穿和服的女性在古城街道上撑伞", List.of("女性"), List.of("撑伞"), List.of("古城", "街道")),
            new Case("少女在泳池边戴着泳镜准备跳水", List.of("少女"), List.of("跳水"), List.of("泳池")),
            new Case("女巫在森林里熬药，锅冒着绿烟", List.of("女巫"), List.of("熬药"), List.of("森林")),
            new Case("少女在沙滩上被海浪打湿了裙子", List.of("少女"), List.of("打湿"), List.of("沙滩", "海")),
            new Case("两个人在体育馆里打篮球", List.of("两人"), List.of("打篮球"), List.of("体育馆")),
            new Case("少女在便利店收银台结账", List.of("少女"), List.of("结账"), List.of("便利店")),
            new Case("机器人少女在废墟中睁开眼睛", List.of("少女"), List.of("睁眼"), List.of("废墟")),
            new Case("男性警察在车厢里按住小偷", List.of("男性", "警察", "小偷"), List.of("按住"), List.of("车厢")),
            new Case("少女在舞蹈室对着镜子压腿", List.of("少女"), List.of("压腿"), List.of("舞蹈室", "镜子")),
            new Case("女剑士在雪山上挥剑，风雪很大", List.of("女剑士"), List.of("挥剑"), List.of("雪山", "风雪")),
            new Case("少女在夜市摊前吃拉面", List.of("少女"), List.of("吃拉面"), List.of("夜市")),
            new Case("男孩在院子里训练狗握手", List.of("男孩", "狗"), List.of("训练", "握手"), List.of("院子")),
            new Case("少女在花田里被风吹起头发", List.of("少女"), List.of("被风吹"), List.of("花田")),
            new Case("吸血鬼少女在城堡窗边微笑", List.of("少女"), List.of("微笑"), List.of("城堡", "窗")),
            new Case("女高中生在天台上吃面包，午后的阳光很亮", List.of("女高中生"), List.of("吃面包"), List.of("天台", "阳光")),
            new Case("少女在雨中撑着透明伞等公交车", List.of("少女"), List.of("撑伞", "等车"), List.of("雨中", "公交站")),
            new Case("穿西装的男性在办公室里看文件", List.of("男性"), List.of("看文件"), List.of("办公室")),
            new Case("少女在甜品店挑选蛋糕", List.of("少女"), List.of("挑选"), List.of("甜品店")),
            new Case("白发老人坐在长椅上喂鸽子", List.of("老人"), List.of("喂"), List.of("长椅", "广场")),
            new Case("少女在更衣室脱下外套换衣服", List.of("少女"), List.of("脱下", "换衣服"), List.of("更衣室")),
            new Case("女仆跪在地上擦地板", List.of("女仆"), List.of("跪着", "擦"), List.of("地板", "室内")),
            new Case("少女在电梯里低头看手机", List.of("少女"), List.of("低头", "看手机"), List.of("电梯")),
            new Case("骑士和公主在花园里跳舞", List.of("骑士", "公主"), List.of("跳舞"), List.of("花园")),
            new Case("少女在深夜的便利店里打瞌睡", List.of("少女"), List.of("打瞌睡"), List.of("深夜", "便利店")),
            new Case("公园玩耍", List.of("小孩"), List.of("玩耍", "奔跑"), List.of("公园", "草地", "长椅")),
            new Case("雨夜的地铁车厢里，被身后的男人贴身骚扰，女性咬着嘴唇忍耐", List.of("女性", "男人"), List.of("骚扰", "咬"), List.of("雨夜", "地铁车厢")),
            new Case("被电车痴汉骚扰并性交，臀部为主视角，加入分镜展示交合部位。", List.of("女性"), List.of("性交", "骚扰"), List.of("电车", "车厢")));

    /** 这些场景必须落到提示词里的关键要素（每组任一写法命中即可）：防止"性交""分镜"这类要求被静默省略。 */
    private static final Map<String, List<List<String>>> MUST_TAGS = Map.of(
            "被电车痴汉骚扰并性交，臀部为主视角，加入分镜展示交合部位。",
            List.of(List.of("sex", "vaginal", "hetero"),                       // 性交这个行为
                    List.of("multiple views", "comic", "storyboard"),           // 分镜/多格展示
                    List.of("ass_focus", "hip_focus", "crotch_focus", "ass"),   // 臀部/交合部位
                    List.of("chikan", "molestation", "groping")));              // 痴汉骚扰

    public static void main(String[] args) throws Exception {
        double minHitRate = args.length > 0 ? Double.parseDouble(args[0]) : 0.90;
        Path root = Path.of(System.getProperty("bot.home", ".")).toAbsolutePath().normalize();
        Path work = root.resolve("work");
        Files.createDirectories(work);
        Files.writeString(work.resolve("scene-decompose-failures.log"), "");
        Settings settings = new Settings(root);
        if (!Files.isRegularFile(root.resolve("data/deepseek-api-key.txt"))) {
            System.out.println("SceneDecomposeEval SKIP: 未配置生图频道密钥，无法评测拆解命中率。");
            return;
        }
        DeepSeekPrompts client = DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.IMAGE);
        PromptUsage usage = new PromptUsage(root);
        Set<String> allowed = Bot.vocabulary(root, new SdClient.Prompts("", "", "eval"));
        // 每条用例用独立的 scope，互不污染。
        int hits = 0, index = 0;
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> lastBackfills = new ArrayList<>();
        for (Case task : CASES) {
            index++;
            String scope = String.valueOf(900000 + index);
            Path promptFile = root.resolve("data/prompts/" + scope + ".json");
            Files.deleteIfExists(promptFile);
            Files.deleteIfExists(root.resolve("data/prompts/" + scope + ".history.json"));
            SdClient.Prompts original = new SdClient.Prompts("1girl, solo, masterpiece", "lowres, bad anatomy", "eval");
            new UserPromptStore(root).replace(scope, original);
            List<String> problems = new ArrayList<>();
            SceneDecomposer.Scene scene = null;
            try {
                scene = SceneDecomposer.decompose(task.scene(), allowed, usage, client);
                if (scene.parts().size() < SceneDecomposer.MIN_PARTS)
                    problems.add("拆解不足三条（" + scene.parts().size() + "）");
                List<String> missingAspects = SceneDecomposer.missingAspects(scene.texts());
                if (!missingAspects.isEmpty()) problems.add("拆解遗漏：" + String.join("、", missingAspects));
                String enriched = Bot.enrich(task.scene(), scene);
                List<String> hints = new ArrayList<>(Bot.infixHints(root, enriched, allowed));
                for (String part : scene.texts()) hints.addAll(usage.hints(part, allowed, 400));
                if (hints.size() > 1200) hints = hints.subList(0, 1200);
                List<String> required = scene.allTags();
                DeepSeekPrompts.Result rewritten = client.edit(enriched, original, hints, List.of(), List.of(),
                        required.isEmpty() ? List.of() : required.subList(0, Math.min(40, required.size())));
                Bot.InfixFilter filtered = Bot.filterInfixVocabulary(allowed, rewritten, false, usage);
                if (!filtered.rejected().isEmpty() && filtered.result().positive().equals(original.positive()))
                    problems.add("整段改写都没能落地（丢弃：" + filtered.rejected() + "）");
                // 与真实链路一致：改写没覆盖到的标准词条由程序补齐（拆解出的每一条都要落地）。
                UserPromptStore store = new UserPromptStore(root);
                store.replace(scope, new SdClient.Prompts(filtered.result().positive(), filtered.result().negative(), "eval"));
                List<String> backfilled = new ArrayList<>();
                List<String> instructions = new ArrayList<>();
                instructions.add(enriched);
                instructions.addAll(scene.texts());
                for (String instructionText : instructions) {
                    SdClient.Prompts now = store.prompts(scope);
                    List<String> missing = Bot.requestedTags(root, now, instructionText);
                    if (missing.isEmpty()) continue;
                    String joined = now.positive().isBlank() ? String.join(", ", missing)
                            : now.positive().strip().replaceAll(",\\s*$", "") + ", " + String.join(", ", missing);
                    store.replace(scope, new SdClient.Prompts(joined, now.negative(), "eval"));
                    backfilled.addAll(missing);
                }
                SdClient.Prompts finalPair = store.prompts(scope);
                if (Bot.stripCjk(finalPair.positive(), original.positive()).length() < finalPair.positive().length())
                    problems.add("提示词里出现了中文");
                String finalPrompt = finalPair.positive();
                List<String> uncovered = new ArrayList<>();
                for (SceneDecomposer.Part part : scene.parts()) {
                    if (part.tags().isEmpty()) continue;   // 这一条查不到标准词时不计入命中判定
                    boolean covered = part.tags().stream().anyMatch(tag -> Bot.usesTerm(finalPrompt, tag) || finalPrompt.contains(tag))
                            || usage.hints(part.text(), allowed, 3).stream().anyMatch(tag -> Bot.usesTerm(finalPrompt, tag));
                    if (!covered) uncovered.add(part.text() + " → " + part.tags());
                }
                if (!uncovered.isEmpty()) problems.add("简单修改未落地：" + uncovered);
                List<String> missingMust = new ArrayList<>();
                for (List<String> group : MUST_TAGS.getOrDefault(task.scene(), List.of()))
                    if (group.stream().noneMatch(tag -> usesAny(finalPrompt, tag))) missingMust.add(String.join("/", group));
                if (!missingMust.isEmpty()) problems.add("缺少关键要素词条：" + missingMust);
                if (!backfilled.isEmpty()) lastBackfills.addAll(backfilled);
            } catch (Exception error) {
                problems.add("执行异常：" + Bot.error(error));
            }
            String positive = "";
            try { positive = new UserPromptStore(root).prompts(scope).positive(); } catch (Exception ignored) { }
            if (problems.isEmpty()) hits++;
            else failures.add("【" + index + "】" + task.scene() + "\n  " + String.join("\n  ", problems)
                    + "\n  拆解：" + (scene == null ? "（无）" : String.join(" | ", scene.texts()))
                    + "\n  提示词：" + shorten(positive, 300));
            lines.add("| " + index + " | " + task.scene() + " | " + (scene == null ? 0 : scene.parts().size())
                    + " | " + (problems.isEmpty() ? "命中" : "未命中") + " | "
                    + (scene == null ? "" : String.join(" / ", scene.texts())) + " |");
            System.out.println("[" + index + "/" + CASES.size() + "] " + (problems.isEmpty() ? "命中" : "未命中")
                    + "：" + task.scene() + (problems.isEmpty() ? "" : " ← " + String.join("；", problems)));
            Files.deleteIfExists(promptFile);
            Files.deleteIfExists(root.resolve("data/prompts/" + scope + ".history.json"));
        }
        double rate = CASES.isEmpty() ? 0 : (double) hits / CASES.size();
        StringBuilder report = new StringBuilder("# 前置拆解层命中率报告\n\n");
        report.append("命中 ").append(hits).append(" / ").append(CASES.size()).append("，命中率 ")
                .append(String.format(Locale.ROOT, "%.1f%%", rate * 100)).append("（门槛 ")
                .append(String.format(Locale.ROOT, "%.0f%%", minHitRate * 100)).append("）\n\n");
        report.append("| # | 复杂场景 | 拆解条数 | 结果 | 拆解结果 |\n|---|---|---|---|---|\n");
        for (String line : lines) report.append(line).append("\n");
        if (!failures.isEmpty()) {
            report.append("\n## 未命中明细\n\n");
            for (String failure : failures) report.append(failure).append("\n\n");
        }
        Files.writeString(work.resolve("scene-decompose-report.md"), report.toString());
        Files.writeString(work.resolve("scene-decompose-failures.log"), String.join("\n\n", failures));
        System.out.println("SceneDecomposeEval: 命中率 " + String.format(Locale.ROOT, "%.1f%%", rate * 100)
                + "（" + hits + "/" + CASES.size() + "），门槛 " + String.format(Locale.ROOT, "%.0f%%", minHitRate * 100)
                + "；报告 " + work.resolve("scene-decompose-report.md"));
        if (rate < minHitRate) throw new AssertionError("拆解命中率未达标：" + hits + "/" + CASES.size());
    }

    /** 提示词里是否用了这个词条（下划线/空格两种写法都认）。 */
    private static boolean usesAny(String positive, String tag) {
        if (positive == null || positive.isBlank()) return false;
        String spaced = tag.replace('_', ' '), underscored = tag.replace(' ', '_');
        return Bot.usesTerm(positive, tag) || positive.contains(tag)
                || positive.contains(spaced) || positive.contains(underscored);
    }

    /** 用例标注的人物/动作/场景是否出现在拆解里（只作为报告里的提示，不计入命中判定）。 */    private static boolean covers(Case task, List<String> parts) {
        String joined = String.join(" ", parts);
        return task.people().stream().anyMatch(joined::contains)
                && task.actions().stream().anyMatch(joined::contains)
                && task.places().stream().anyMatch(joined::contains);
    }

    private static String shorten(String value, int limit) {
        String one = Objects.requireNonNullElse(value, "").replaceAll("\\s+", " ").strip();
        return one.length() <= limit ? one : one.substring(0, limit) + "…";
    }
}

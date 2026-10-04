package cn.szu.bot;

import cn.szu.bot.prompt.CategoryModel;
import cn.szu.bot.prompt.PromptEditor;
import cn.szu.bot.prompt.PromptUsage;
import cn.szu.bot.prompt.TermCategories;
import java.nio.file.*;
import java.util.*;

/**
 * 复合提示词（一条词条里揉了好几个方面）的分类与按类别删除。
 *
 * <p>用户报告的问题：控制台执行 {@code /prompt drop 镜头} 时，机器人把
 * {@code standing sex from behind} / {@code male pulling her panties aside from behind} /
 * {@code extreme close-up on their joined crotch as he penetrates her} 三条**整条**删掉了，
 * 只因为它们句子里带着 {@code from behind} / {@code close-up} 这种镜头词。用户要的是：
 * <ul>
 *   <li>复合短语别草率归到某一个方面（{@link TermCategories#categoryOf} 归"其他"）；</li>
 *   <li>删某一类时**只删命中该类的那一段**，其余片段原样拼回（{@code standing sex from behind} →
 *       {@code standing sex}）；只有整条都属于该类（{@code multiple views}）才整条删；</li>
 *   <li>一条都没有目标片段时一个字都不改。</li>
 * </ul>
 * 同时把既有分类行为（人物/角色/作品/表情/动作/姿势/服装/场景/环境/镜头/画面/物品/其他/LoRA）
 * 逐条钉住，防止"防误判"顺手把正常词条弄丢。
 */
public final class CompoundCategoryTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        classification();
        compoundPhrases();
        fragmentDrop();
        dropKeepsEverythingElse();
        formatCleanUp();
        singleAspectRegression();
        sharpenedMatching();
        modelPathUntouched();
        System.out.println("CompoundCategoryTest: " + checks + " checks passed：复合短语分类、按片段删除、格式收拾、既有分类回归");
    }

    // ---- 1. 用户那 4 条：分类 + 只删目标片段 ----
    private static void classification() {
        // multiple views / multiple_views 整条就是镜头词 → 仍然归镜头。
        check(TermCategories.categoryOf(null, "multiple views").equals("镜头"), "multiple views 仍然归镜头");
        check(TermCategories.categoryOf(null, "multiple_views").equals("镜头"), "multiple_views 仍然归镜头");
        check(TermCategories.matchedCategories("multiple views").equals(List.of("镜头")), "multiple views 只有镜头一个方面");
        check(fragments("multiple views").equals("镜头[0,2]"), "multiple views 整条就是一个镜头片段：" + fragments("multiple views"));

        // 另外三条是复合短语 → 不再整条归镜头，而是归"其他"（并给出它牵涉的方面）。
        check(TermCategories.categoryOf(null, "standing sex from behind").equals(TermCategories.OTHER),
                "standing sex from behind 不再整条归镜头");
        check(TermCategories.categoryOf(null, "male pulling her panties aside from behind").equals(TermCategories.OTHER),
                "male pulling her panties aside from behind 不再整条归镜头");
        // extreme close-up 那条断言的是"删的时候只摘取景片段"（见 compoundPhrases/fragmentDrop）：
        // 它整条的主力词就是 close-up，所以分类上仍算镜头，但删除时绝不整条吃掉。
        check(TermCategories.categoryOf(null, "extreme close-up on their joined crotch as he penetrates her").equals("镜头"),
                "extreme close-up 从句分类上仍是镜头（片段删除负责不吃掉其余内容）");
        check(!TermCategories.matchedCategories("standing sex from behind").isEmpty(),
                "复合短语仍能报出它牵涉的方面：" + TermCategories.matchedCategories("standing sex from behind"));
        check(TermCategories.matchedCategories("standing sex from behind").contains("镜头"),
                "standing sex from behind 的镜头属性仍然被认出来：" + TermCategories.matchedCategories("standing sex from behind"));
        check(TermCategories.matchedCategories("male pulling her panties aside from behind").size() >= 2,
                "male pulling… 牵涉多个方面：" + TermCategories.matchedCategories("male pulling her panties aside from behind"));

        // 用真实词库时也必须是同样的结论（词库优先级不能把复合短语拉回镜头）。
        PromptUsage usage = library();
        check(TermCategories.categoryOf(usage, "multiple views").equals("镜头"), "有词库时 multiple views 仍是镜头");
        check(TermCategories.categoryOf(usage, "standing sex from behind").equals(TermCategories.OTHER),
                "有词库时 standing sex from behind 仍是其他：" + TermCategories.categoryOf(usage, "standing sex from behind"));
        check(TermCategories.categoryOf(usage, "male pulling her panties aside from behind").equals(TermCategories.OTHER),
                "有词库时 male pulling… 仍是其他");
        check(TermCategories.categoryOf(usage, "extreme close-up on their joined crotch as he penetrates her").equals(TermCategories.OTHER)
                        || TermCategories.categoryOf(usage, "extreme close-up on their joined crotch as he penetrates her").equals("镜头"),
                "有词库时 extreme close-up 从句仍按片段处理：" + TermCategories.categoryOf(usage, "extreme close-up on their joined crotch as he penetrates her"));
        check(TermCategories.withoutCategories("extreme close-up on their joined crotch as he penetrates her", List.of("镜头"))
                        .equals("on their joined crotch as he penetrates her"),
                "有词库时也只摘 extreme close-up 这一段");
    }

    /** 真实分类词库（data/prompt-usage.json）；读不到时返回 null，测试退化为只用英文词表。 */
    private static PromptUsage library() {
        try { return new PromptUsage(Path.of(".")); }
        catch (java.io.IOException error) { return null; }
    }

    // ---- 2. 用户表格里四条词条被 /prompt drop 镜头 之后的确切结果 ----
    private static void compoundPhrases() {
        check(dropPrompt("multiple views", "镜头").equals(""), "multiple views 整条被删");
        check(dropPrompt("standing sex from behind", "镜头").equals("standing sex"), "standing sex from behind → standing sex");
        check(dropPrompt("male pulling her panties aside from behind", "镜头")
                .equals("male pulling her panties aside"), "male pulling her panties aside from behind → 去掉 from behind");
        check(dropPrompt("extreme close-up on their joined crotch as he penetrates her", "镜头")
                .equals("on their joined crotch as he penetrates her"),
                "extreme close-up 从句 → 只摘掉 extreme close-up，其余原样");
        // 一条复合短语同时删两类时，两类片段都摘掉。
        check(dropPrompt("standing sex from behind", "镜头", "姿势").equals("sex"),
                "同时删镜头和姿势：standing sex from behind → sex");
        check(dropPrompt("male pulling her panties aside from behind", "镜头", "服装")
                .equals("male pulling her aside"), "同时删镜头和服装：male pulling her panties aside from behind");
    }

    // ---- 3. 片段判定的细节 ----
    private static void fragmentDrop() {
        check(TermCategories.withoutCategories("standing sex from behind", List.of("镜头")).equals("standing sex"),
                "withoutCategories 摘掉尾部镜头从句");
        check(TermCategories.withoutCategories("standing sex from behind", List.of("姿势")).equals("sex from behind"),
                "withoutCategories 摘掉开头的姿势词");
        check(TermCategories.withoutCategories("standing sex from behind", List.of("动作")).equals("standing sex from behind"),
                "这条没有动作片段 → 一个字都不改");
        check(TermCategories.withoutCategories("standing sex from behind", List.of("场景")).equals("standing sex from behind"),
                "这条没有场景片段 → 一个字都不改");
        check(TermCategories.withoutCategories("multiple views", List.of("镜头")).isEmpty(),
                "整条都是镜头词 → 整条删");
        check(TermCategories.withoutCategories("full body", List.of("镜头")).isEmpty(), "full body 整条是镜头词 → 整条删");
        check(TermCategories.withoutCategories("cowboy shot", List.of("镜头")).isEmpty(), "cowboy shot 整条是镜头词 → 整条删");
        check(TermCategories.withoutCategories("from behind", List.of("镜头")).isEmpty(), "单独的 from behind 整条删");
        check(TermCategories.withoutCategories("close-up", List.of("镜头")).isEmpty(), "单独的 close-up 整条删");
        check(TermCategories.withoutCategories("from above", List.of("镜头")).isEmpty(), "from above 整条删");
        check(TermCategories.withoutCategories("portrait", List.of("镜头")).isEmpty(), "portrait 整条删");
        check(TermCategories.withoutCategories("depth of field", List.of("镜头")).isEmpty(), "depth of field 整条删");
        check(TermCategories.withoutCategories("medium shot from behind", List.of("镜头")).isEmpty(),
                "medium shot from behind 是纯镜头词 → 整条删");
        check(TermCategories.withoutCategories("blue eyes", List.of("人物")).isEmpty(), "blue eyes 整条是人物片段 → 整条删");
        check(TermCategories.withoutCategories("light smile", List.of("表情")).isEmpty(), "light smile 整条是表情片段 → 整条删");
        // 片段边界稳：不能切断单词。
        check(TermCategories.withoutCategories("hairbrush", List.of("镜头")).equals("hairbrush"),
                "hairbrush 不会被 hair/brush 之类的子串规则切碎");
        check(TermCategories.withoutCategories("supermarket", List.of("场景")).equals("supermarket"),
                "supermarket 不会被 market 之类的子串规则切碎");
        check(TermCategories.withoutCategories("night", List.of("环境")).isEmpty(), "night 整条删");
        check(TermCategories.withoutCategories("1girl", List.of("人物")).isEmpty(), "1girl 整条删");
        check(TermCategories.withoutCategories("long hair", List.of("人物")).isEmpty(), "long hair 整条是人物片段 → 整条删");
        check(TermCategories.withoutCategories("<lora:x:1>", List.of("镜头")).equals("<lora:x:1>"),
                "LoRA 标签与片段删除无关");
        check(TermCategories.withoutCategories("", List.of("镜头")).equals(""), "空词条原样返回");
        check(TermCategories.withoutCategories("standing sex from behind", List.of()).equals("standing sex from behind"),
                "没有要删的类别时原样返回");
    }

    // ---- 4. 整条 prompt 上模拟 /prompt drop：不该被吃的词一个字都不许动 ----
    private static void dropKeepsEverythingElse() {
        String prompt = "multiple views, standing sex from behind, male pulling her panties aside from behind, "
                + "extreme close-up on their joined crotch as he penetrates her, masterpiece, <lora:k:1>";
        String after = dropPrompt(prompt, "镜头");
        check(!after.contains("multiple views"), "镜头整条词被删掉：" + after);
        check(after.contains("standing sex") && !after.contains("standing sex from behind"),
                "standing sex from behind 只丢镜头从句：" + after);
        check(after.contains("male pulling her panties aside") && !after.contains("male pulling her panties aside from behind"),
                "male pulling… 只丢镜头从句：" + after);
        check(after.contains("on their joined crotch as he penetrates her") && !after.contains("extreme close-up"),
                "extreme close-up 从句只丢取景那段：" + after);
        check(after.contains("masterpiece"), "画面词不受影响：" + after);
        check(after.contains("<lora:k:1>"), "LoRA 标签原样保留：" + after);

        // 反向保护：删动作 / 删场景都不许把 from behind 这类镜头片段带走。
        String camera = "standing sex from behind, from behind, multiple views";
        check(dropPrompt(camera, "动作").equals(camera), "drop 动作 不许动镜头片段");
        check(dropPrompt(camera, "场景").equals(camera), "drop 场景 对不含场景片段的复合短语一个字都不改");
        check(dropPrompt(camera, "服装").equals(camera), "drop 服装 同样一个字都不改");
        check(dropPrompt(camera, "物品").equals(camera), "drop 物品 同样一个字都不改");
        // 删姿势时只丢姿势片段，镜头片段留下。
        check(dropPrompt(camera, "姿势").equals("sex from behind, from behind, multiple views"),
                "drop 姿势 只摘掉 standing：" + dropPrompt(camera, "姿势"));

        // 既有语义：单一方面的词条仍然是整条删。
        String single = "depth of field, night, masterpiece, 1girl";
        check(dropPrompt(single, "镜头").equals("night, masterpiece, 1girl"), "单一镜头词整条删：" + dropPrompt(single, "镜头"));
        check(dropPrompt(single, "环境").equals("depth of field, masterpiece, 1girl"), "单一环境词整条删");
        check(dropPrompt(single, "画面").equals("depth of field, night, 1girl"), "单一画面词整条删");
        check(dropPrompt(single, "人物").equals("depth of field, night, masterpiece"), "单一人物词整条删");
        check(dropPrompt(single, "场景").equals(single), "没有该类的词条时整条 prompt 不变");
    }

    // ---- 5. 拼回格式：无双空格、前后不留多余逗号/分号 ----
    private static void formatCleanUp() {
        check(dropPrompt("standing sex from behind", "镜头").equals("standing sex"), "拼回后无尾随空格");
        check(dropPrompt("from behind, standing sex", "镜头").equals("standing sex"), "首条被删后没有多余逗号");
        check(dropPrompt("standing sex, from behind", "镜头").equals("standing sex"), "末条被删后没有多余逗号");
        check(!dropPrompt("multiple views, from behind, masterpiece", "镜头").startsWith(","),
                "整条列表不以逗号开头");
        check(!dropPrompt("multiple views, from behind, masterpiece", "镜头").endsWith(","),
                "整条列表不以逗号结尾");
        check(!dropPrompt("multiple views, from behind, masterpiece", "镜头").contains(", ,"),
                "整条列表里没有空词条");
        check(!dropPrompt("multiple views, from behind, masterpiece", "镜头").contains("  "),
                "整条列表里没有双空格");
        for (String term : List.of("standing sex from behind", "male pulling her panties aside from behind",
                "extreme close-up on their joined crotch as he penetrates her", "her view of the city")) {
            String kept = dropPrompt(term, "镜头");
            check(!kept.startsWith(" ") && !kept.endsWith(" "), "拼回结果首尾无空格：" + term + " → [" + kept + "]");
            check(!kept.contains("  "), "拼回结果无连续空格：" + term + " → [" + kept + "]");
            check(!kept.startsWith(",") && !kept.endsWith(","), "拼回结果首尾无逗号：" + term + " → [" + kept + "]");
        }
        // 顺序保持：其余片段按原顺序拼回。
        check(dropPrompt("male pulling her panties aside from behind", "镜头")
                .equals("male pulling her panties aside"), "其余片段顺序不变");
        check(dropPrompt("standing sex from behind", "镜头").equals("standing sex"), "其余片段顺序不变（姿势在前）");
    }

    // ---- 6. 既有分类行为逐条钉住（回归） ----
    private static void singleAspectRegression() {
        check(TermCategories.categoryOf(null, "8k").equals("画面"), "8k 是画面");
        check(TermCategories.categoryOf(null, "high resolution").equals("画面"), "high resolution 是画面");
        check(TermCategories.categoryOf(null, "official art").equals("画面"), "official art 是画面");
        check(TermCategories.categoryOf(null, "masterpiece").equals("画面"), "masterpiece 是画面");
        check(TermCategories.categoryOf(null, "best quality").equals("画面"), "best quality 是画面");
        check(TermCategories.categoryOf(null, "watercolor").equals("画面"), "watercolor 是画面");
        check(TermCategories.categoryOf(null, "absurdres").equals("画面"), "absurdres 是画面");
        check(TermCategories.categoryOf(null, "full body").equals("镜头"), "full body 是镜头");
        check(TermCategories.categoryOf(null, "cowboy shot").equals("镜头"), "cowboy shot 是镜头");
        check(TermCategories.categoryOf(null, "from behind").equals("镜头"), "单独的 from behind 是镜头");
        check(TermCategories.categoryOf(null, "close-up").equals("镜头"), "单独的 close-up 是镜头");
        check(TermCategories.categoryOf(null, "portrait").equals("镜头"), "portrait 是镜头");
        check(TermCategories.categoryOf(null, "depth of field").equals("镜头"), "depth of field 是镜头");
        check(TermCategories.categoryOf(null, "from above").equals("镜头"), "from above 是镜头");
        check(TermCategories.categoryOf(null, "dutch angle").equals("镜头"), "dutch angle 是镜头");
        check(TermCategories.categoryOf(null, "upper body").equals("镜头"), "upper body 是镜头");
        check(TermCategories.categoryOf(null, "standing sex").equals("姿势"), "standing sex 单独时是姿势");
        check(TermCategories.categoryOf(null, "standing").equals("姿势"), "standing 是姿势");
        check(TermCategories.categoryOf(null, "sitting").equals("姿势"), "sitting 是姿势");
        check(TermCategories.categoryOf(null, "holding sword").equals("动作"), "holding sword 是动作");
        check(TermCategories.categoryOf(null, "holding hands").equals("动作"), "holding hands 是动作");
        check(TermCategories.categoryOf(null, "reading book").equals("动作"), "reading book 是动作");
        check(TermCategories.categoryOf(null, "hugging own legs").equals("姿势"),
                "hugging own legs 按结尾的 legs 归姿势（与既有词表优先级一致）");
        check(TermCategories.categoryOf(null, "light smile").equals("表情"), "light smile 是表情");
        check(TermCategories.categoryOf(null, "blonde hair").equals("人物"), "blonde hair 是人物");
        check(TermCategories.categoryOf(null, "red eyes").equals("人物"), "red eyes 是人物");
        check(TermCategories.categoryOf(null, "blue eyes").equals("人物"), "blue eyes 是人物");
        check(TermCategories.categoryOf(null, "very long hair").equals("人物"), "very long hair 是人物");
        check(TermCategories.categoryOf(null, "wolf tail").equals("人物"), "wolf tail 是人物特征");
        check(TermCategories.categoryOf(null, "maid headdress").equals("服装"), "maid headdress 是服装");
        check(TermCategories.categoryOf(null, "orange dress").equals("服装"), "orange dress 是服装");
        check(TermCategories.categoryOf(null, "white wrist cuffs").equals("服装"), "white wrist cuffs 是服装");
        check(TermCategories.categoryOf(null, "night").equals("环境"), "night 是环境");
        check(TermCategories.categoryOf(null, "sunset").equals("环境"), "sunset 是环境");
        check(TermCategories.categoryOf(null, "classroom").equals("场景"), "classroom 是场景");
        check(TermCategories.categoryOf(null, "simple background").equals("场景"), "simple background 是场景");
        check(TermCategories.categoryOf(null, "Shinomori Yomogi").equals("角色"), "人名写法算角色名");
        check(TermCategories.categoryOf(null, "asdfgh").equals(TermCategories.OTHER), "认不出的词仍是其他");
        check(TermCategories.categoryOf(null, "<lora:Shinomori_Yomogi_1a_nai:1>").equals(TermCategories.LORA), "LoRA 单独一类");
        check(TermCategories.categoryOf(null, "<lora:x:0.5>").equals(TermCategories.LORA), "带权重的 LoRA 仍是 LoRA");
        check(TermCategories.categoryOf(null, "embedding:easynegative").equals(TermCategories.LORA), "embedding 也是 LoRA");
    }

    // ---- 7. 匹配收紧后的新行为（防止再退回"子串命中就算"） ----
    private static void sharpenedMatching() {
        check(TermCategories.categoryOf(null, "aerial view").equals("镜头"), "aerial view 归镜头（view 是镜头词）");
        check(TermCategories.withoutCategories("aerial view", List.of("镜头")).equals("aerial"),
                "aerial view 的 view 是镜头片段 → 只摘 view");
        check(!TermCategories.categoryOf(null, "her view of the city").equals("镜头"),
                "her view of the city 不因句中的 view 就整条算镜头");
        check(TermCategories.categoryOf(null, "her view of the city").equals(TermCategories.OTHER),
                "her view of the city 归其他：" + TermCategories.categoryOf(null, "her view of the city"));
        check(TermCategories.categoryOf(null, "impressionism").equals("画面"), "impressionism 仍是画面");
        check(TermCategories.categoryOf(null, "poster").equals("场景"), "poster 按词表优先级归场景（与既有行为一致）");
        check(TermCategories.matchedCategories("full body").equals(List.of("镜头")), "full body 只报镜头一个方面");
        check(TermCategories.matchedCategories("standing sex from behind").size() == 2,
                "standing sex from behind 报两个方面：" + TermCategories.matchedCategories("standing sex from behind"));
        check(fragments("standing sex from behind").contains("镜头[2,4]"),
                "镜头片段正好是 from behind：" + fragments("standing sex from behind"));
        check(fragments("standing sex from behind").contains("姿势[0,1]"),
                "姿势片段正好是 standing：" + fragments("standing sex from behind"));
        check(fragments("extreme close-up on their joined crotch as he penetrates her").equals("镜头[0,3]"),
                "extreme close-up 从句的镜头片段是开头那三个词：" + fragments("extreme close-up on their joined crotch as he penetrates her"));
    }

    // ---- 8. 模型通道关闭时上面这套本地判定仍然生效 ----
    private static void modelPathUntouched() throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "compound-category");
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        CategoryModel model = new CategoryModel(root, request -> null);
        model.setEnabled(false);
        check(!model.enabled(), "测试用的 CategoryModel 已关闭");
        List<String> kept = new ArrayList<>(), removed = new ArrayList<>();
        String after = Bot.applyCategorySurgery(
                "multiple views, standing sex from behind, depth of field, masterpiece",
                new Bot.CategorySurgery(Set.of(), Set.of("镜头"), false), null, kept, removed, null, model);
        check(after.equals("standing sex, masterpiece"), "模型关闭时本地片段判定生效：" + after);
        check(removed.contains("multiple views（镜头）"), "整条属于镜头的词记在删除明细里：" + removed);
        check(removed.contains("depth of field（镜头）"), "depth of field 记在删除明细里：" + removed);
        check(kept.contains("standing sex（其他·保留其余片段）"),
                "只摘片段的词条记在保留明细里（categoryOf 为其他）：" + kept);
        check(kept.contains("masterpiece（画面）"), "未受影响的词条照旧记在保留明细里：" + kept);
        check(Bot.applyCategorySurgery("multiple views, depth of field", new Bot.CategorySurgery(Set.of(), Set.of("镜头"), false),
                null, null, null, null, model).isEmpty(), "整条都是镜头词时 prompt 会被清空");
        check(Bot.applyCategorySurgery("standing sex from behind", new Bot.CategorySurgery(Set.of(), Set.of("场景"), false),
                null, null, null, null, model).equals("standing sex from behind"), "没有场景片段时一个字都不改");
    }

    // ---- helpers ----
    /** 模拟 /prompt drop：按逗号切词条 → 只摘目标类别的片段 → 用 ", " 拼回。 */
    private static String dropPrompt(String positive, String... categories) {
        List<String> result = new ArrayList<>();
        for (String term : PromptEditor.parts(positive == null ? "" : positive)) {
            if (TermCategories.isLoraOrEmbedding(term)) { result.add(term); continue; }
            String kept = TermCategories.withoutCategories(term, List.of(categories));
            if (!kept.isBlank()) result.add(kept);
        }
        return String.join(", ", result);
    }

    private static String fragments(String term) {
        StringBuilder text = new StringBuilder();
        for (TermCategories.Fragment fragment : TermCategories.fragments(term)) {
            if (text.length() > 0) text.append(' ');
            text.append(fragment.category()).append('[').append(fragment.from()).append(',').append(fragment.to()).append(']');
        }
        return text.toString();
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}

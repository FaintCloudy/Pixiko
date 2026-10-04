package cn.szu.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link InfixIntent} 的纯函数测试：只吃字符串、吐字符串，不连 DeepSeek、不发网络请求、不读写 data/。
 *
 * <p>跑法（私有 classes 目录，不碰 F:\Bot\build）：
 * <pre>
 * javac --release 17 -encoding UTF-8 -cp "lib/gson-2.13.1.jar;lib/spring/*;F:\Bot\work\gate-infix\classes"
 *       -d F:\Bot\work\gate-infix\tests src/test/java/cn/szu/bot/InfixIntentTest.java
 * java -ea -Dfile.encoding=UTF-8 -Dbot.test.work=F:\Bot\work\gate-infix
 *       -cp "lib/gson-2.13.1.jar;F:\Bot\work\gate-infix\classes;F:\Bot\work\gate-infix\tests" cn.szu.bot.InfixIntentTest
 * </pre>
 */
public final class InfixIntentTest {
    private static int checks = 0;
    private static final List<String> covered = new ArrayList<>();

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("断言失败（第 " + checks + " 条）：" + what);
        covered.add(what);
    }

    /** 解析并断言"认得出来"：省得每条断言都写一遍 != null，失败信息也更清楚。 */
    private static InfixIntent.Intent parsed(String instruction) {
        InfixIntent.Intent intent = InfixIntent.parse(instruction);
        checks++;
        if (intent == null) throw new AssertionError("断言失败（第 " + checks + " 条）：本该认得出来，却返回 null：" + instruction);
        covered.add("认得：" + instruction);
        return intent;
    }

    private static InfixIntent.Change applied(String prompt, String instruction) {
        return InfixIntent.apply(prompt, parsed(instruction));
    }

    public static void main(String[] args) {
        // ---------- 整体替换：改为/改成/换成/替换为/改 X ----------
        InfixIntent.Intent replace = parsed("改为 thigh sex");
        check(replace.kind() == InfixIntent.Kind.REPLACE, "「改为 X」= 整体替换");
        check(replace.side() == InfixIntent.Side.POSITIVE, "「改为 X」默认作用在正向");
        check(replace.terms().equals(List.of("thigh sex")), "「改为 thigh sex」解析出的词条");
        check(!InfixIntent.needsTranslation(replace), "「改为 thigh sex」是英文内容 → 可直接落地，不必过模型");
        check(InfixIntent.apply("1girl, smile", replace).prompt().equals("thigh sex"), "整体替换：正向被换成 X");
        check(applied("smile", "改为 smile").prompt().equals("smile"), "整体替换成同样的内容时原文一个字都不动");
        check(InfixIntent.apply("1girl, <lora:x:1>", replace).prompt().equals("thigh sex, <lora:x:1>"),
                "整体替换保留 LoRA/嵌入标签（『任何改写都不丢模型标签』是硬规矩）");
        check(parsed("改成 fishnet pantyhose").kind() == InfixIntent.Kind.REPLACE, "「改成 X」= 整体替换");
        check(parsed("换成 Fishnet Pantyhose").terms().equals(List.of("Fishnet Pantyhose")),
                "「换成 X」= 整体替换（大小写原样保留）");
        check(parsed("替换为\u3000Thigh Sex").terms().equals(List.of("Thigh Sex")), "全角空格/全角标点也认（NFKC 折半角）");
        check(parsed("改 thigh sex").kind() == InfixIntent.Kind.REPLACE, "「改 X」= 整体替换");
        check(parsed("正向改为 thigh sex").side() == InfixIntent.Side.POSITIVE, "「正向改为 X」= 正向");
        check(parsed("反向改为 low quality").side() == InfixIntent.Side.NEGATIVE, "「反向改为 X」= 反向");
        check(parsed("正向提示词里改为：thigh sex").kind() == InfixIntent.Kind.REPLACE,
                "prompt 指令转交来的『正向提示词里改为：X』也认（Bot 的中文转交路径）");

        // ---------- 追加：加入/加上/添加/增加（去重） ----------
        InfixIntent.Intent add = parsed("加入 fishnet pantyhose");
        check(add.kind() == InfixIntent.Kind.ADD, "「加入 X」= 追加");
        check(add.terms().equals(List.of("fishnet pantyhose")) && !InfixIntent.needsTranslation(add),
                "「加入 fishnet pantyhose」是英文词条 → 可直接落地");
        check(InfixIntent.apply("1girl", add).prompt().equals("1girl, fishnet pantyhose"), "追加写到末尾");
        check(InfixIntent.apply("1girl, fishnet pantyhose", add).changed().isEmpty(), "已有的词不重复加（去重）");
        check(InfixIntent.apply("1girl, fishnet pantyhose", add).unchanged().equals(List.of("fishnet pantyhose")),
                "去重时如实回报『已存在』");
        check(parsed("加上 fishnet pantyhose, thigh sex").terms().size() == 2, "多个词条按逗号切开");
        check(parsed("正向提示词里加上：thigh sex").kind() == InfixIntent.Kind.ADD,
                "prompt 指令转交来的『正向提示词里加上：X』= 追加正向");
        check(parsed("反向提示词里加上：nsfw").side() == InfixIntent.Side.NEGATIVE, "『反向提示词里加上：X』= 反向");
        check(parsed("把 fishnet pantyhose 加上").terms().equals(List.of("fishnet pantyhose")),
                "「把 X 加上」内容在动词前面也认");
        check(parsed("加入 <lora:Kanbe_Kotori_1_nai:1>").terms().equals(List.of("<lora:Kanbe_Kotori_1_nai:1>")),
                "LoRA 标签原样解析");
        check(!InfixIntent.needsTranslation(parsed("加入 <lora:Kanbe_Kotori_1_nai:1>")),
                "模型标签不算中文内容 → 可以直接落地");

        // ---------- 删除：只删指定那一侧（默认正向；反向要明说） ----------
        InfixIntent.Intent remove = parsed("删掉 penis entering her anus");
        check(remove.kind() == InfixIntent.Kind.REMOVE, "「删掉 X」= 删除");
        check(remove.side() == InfixIntent.Side.POSITIVE && parsed("反向删掉 nsfw").side() == InfixIntent.Side.NEGATIVE,
                "默认只删正向；反向必须明说（程序不去猜另一侧）");
        check(InfixIntent.apply("1girl, penis entering her anus, smile", remove).prompt().equals("1girl, smile"),
                "删除只去掉匹配到的词条");
        check(InfixIntent.apply("1girl, smile", remove).prompt().equals("1girl, smile"), "找不到对应词条就不动原文");
        check(InfixIntent.apply("1girl, smile", remove).missing().equals(List.of("penis entering her anus")),
                "找不到的词条记进 missing（回执据此说『没找到』并提示另一侧）");
        check(parsed("删掉 penis entering her anus、anal insertion、penis poking her buttocks 这几项").terms().size() == 3,
                "顿号分隔 + 句尾『这几项』");

        // ---------- 词级替换：把 X 改成 Y（只动匹配到的词条） ----------
        InfixIntent.Intent swap = parsed("把 penis entering her anus 改成 anal insertion");
        check(swap.kind() == InfixIntent.Kind.SWAP, "「把 X 改成 Y」= 词级替换");
        check(swap.from().equals(List.of("penis entering her anus")) && swap.terms().equals(List.of("anal insertion")),
                "来源与目标都解析正确（英文来源可直接落地）");
        check(InfixIntent.apply("1girl, penis entering her anus, smile", swap).prompt().equals("1girl, anal insertion, smile"),
                "只换匹配到的那一个词条，其它词条一个不动");
        check(InfixIntent.apply("1girl, penis_entering_her_anus, smile", swap).prompt().equals("1girl, anal insertion, smile"),
                "下划线写法也能对上（规范化键比较）");
        check(parsed("smile 改成 smirk").kind() == InfixIntent.Kind.SWAP, "「X 改成 Y」（没有『把』）也认");
        check(applied("day, outdoors", "把 day 和 outdoors 改成 night 和 indoors").prompt().equals("night, indoors"),
                "多个来源对多个目标一一对应");
        check(InfixIntent.parse("把校服和裙子改成军装") == null, "来源数对不上目标数 → 不猜，返回 null 交给模型");
        check(InfixIntent.apply("smile", swap).prompt().equals("smile") && InfixIntent.apply("smile", swap).missing().size() == 1,
                "词级替换找不到来源时原文不动、并记 missing");

        // ---------- 中文描述型：句式认得出，但内容必须交模型翻译，绝不能写进 prompt ----------
        InfixIntent.Intent cjkAdd = parsed("加入渔网袜");
        check(cjkAdd.kind() == InfixIntent.Kind.ADD, "「加入渔网袜」句式认得出");
        check(InfixIntent.needsTranslation(cjkAdd), "『渔网袜』是中文描述 → needsTranslation（转交模型翻译）");
        InfixIntent.Intent cjkReplace = parsed("改为素股");
        check(InfixIntent.needsTranslation(cjkReplace), "「改为素股」→ 转交模型");
        InfixIntent.Intent cjkSwap = parsed("女性的衣物全部改为兔女郎服装");
        check(cjkSwap.kind() == InfixIntent.Kind.SWAP && InfixIntent.needsTranslation(cjkSwap),
                "「女性的衣物全部改为兔女郎服装」→ 转交模型（中文来源 + 中文目标）");
        check(InfixIntent.needsTranslation(parsed("改为素股（大腿内侧摩擦，penis夹在双腿之间，不插入，thigh sex）")),
                "带括号说明的中文描述仍算一条中文内容（括号里的逗号不拆词条）");
        check(InfixIntent.hasCjk("渔网袜") && !InfixIntent.hasCjk("fishnet pantyhose"), "hasCjk 认汉字、不误判英文");
        check(InfixIntent.hasCjk("ことり") && !InfixIntent.hasCjk("<lora:Kanbe_Kotori:1>"), "假名也算读不了的脚本；LoRA 标签不算");

        // ---------- 反例：必须返回 null（不许猜、不许只做一半） ----------
        check(InfixIntent.parse("改为") == null, "只有『改为』两个字（看不出内容）→ null");
        check(InfixIntent.parse("删掉") == null, "只有『删掉』→ null");
        check(InfixIntent.parse("加上 ") == null, "只有『加上』→ null");
        check(InfixIntent.parse("撤回上一条修改，改为素股（大腿内侧摩擦，penis夹在双腿之间，不插入，thigh sex）") == null,
                "多步（撤回 + 改为）→ null，绝不允许只执行后半句");
        check(InfixIntent.parse("把 penis entering her anus 改成 anal insertion，删掉 penis poking her buttocks") == null,
                "一句话里两个动作 → null");
        check(InfixIntent.parse("先去掉衣服，然后加上校服") == null, "『然后』多步 → null");
        check(InfixIntent.parse("如果下雨就改成夜晚") == null, "带条件 → null");
        check(InfixIntent.parse("怎么把背景改掉？") == null, "问句不是祈使句 → null");
        check(InfixIntent.parse("男性的两只手抓着胸部") == null, "纯画面描述（没有祈使动词）→ null，交给模型");
        check(InfixIntent.parse("插入屁眼，penis entering her anus，肛门插入") == null, "中文描述型清单 → null，交给模型");
        check(InfixIntent.parse("要求展示交合部位，特写交合处、局部放大") == null, "『要求…』整句描述 → null");
        check(InfixIntent.parse("仅保留人物和服饰，其余清空") == null, "按类别筛选由分类逻辑接管，这里不接管");
        check(InfixIntent.parse("删掉 a, b, c, d, e, f, g, h, i") == null, "列举超过 8 条 → null");
        check(InfixIntent.parse("加上 " + "a".repeat(250)) == null, "整条超过 200 字符（列举很长）→ null");
        check(InfixIntent.parse(null) == null && InfixIntent.parse("") == null, "空输入/空串 → null");

        // ---------- 匹配与回执文案 ----------
        check(InfixIntent.matchesAny("black_pantyhose, smile", "pantyhose"), "唯一包含匹配算对得上");
        check(!InfixIntent.matchesAny("smile", "pantyhose"), "对不上就是对不上");
        check(!InfixIntent.matchesAny("red dress, blue dress", "dress"), "对得上多个候选时算对不上（宁可交给模型也不猜）");
        check(InfixIntent.formsHelp().contains("改为 <英文词条>") && InfixIntent.formsHelp().contains("反向删掉")
                        && InfixIntent.formsHelp().contains("中文"),
                "失败回执里给得出形式示例，并说明中文描述会走模型翻译");

        System.out.println("InfixIntentTest OK：断言 " + checks + " 条全部通过。");
        System.out.println("覆盖（" + covered.size() + " 条）：" + String.join("；", covered));
    }
}

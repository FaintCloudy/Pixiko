package cn.szu.bot;

import java.nio.file.*;
import java.util.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.DeepSeekPrompts;

/**
 * 中文不得进 SD 提示词：计划提示词规则 + 计划级兜底（用户实测 bug）。
 *
 * <p>实测 bug：用户说「通过反向提示词禁止不存在的手。然后加入 from above；女性的胸部衣物被扯开，生成。」
 * 模型把 `.promptR add 不存在的手` 写进计划——中文原样进了反向提示词，SD 只认英文词条，等于乱码。
 * 契约：**中文一律不做词条替换、不查词库**，含汉字的 `.prompt/.promptR add|set` 整条换成 .infix
 * （`.infix 正向|反向提示词里加上：<中文原话>`，set 用「改为」），交给改写模型在上下文里处理。
 *
 * <p>本套件不需要词库数据，也不写任何文件。
 */
public final class ChineseTagPlanTest {
    static int checks = 0;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError("FAIL: " + what); }

    static ChatActions.Plan plan(List<String> commands) {
        return new ChatActions.Plan("好，这就照办。", commands, "", 90, false, true);
    }
    /** 指令里有没有汉字。 */
    static boolean hasHan(String text) {
        return text != null && text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }
    /** 有没有"含汉字的 .prompt/.promptR add|set"——这正是要根治的形态。 */
    static boolean chineseInPromptValue(List<String> commands) {
        for (String command : commands) {
            String text = command == null ? "" : command.strip();
            if (text.matches("(?is)^[./]promptR?\\s+(add|set)\\s+.*") && hasHan(text)) return true;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        // ① 模型自己计划了 `.promptR add 不存在的手`（用户实测 bug）→ 整条换成带"反向"的 .infix
        // 注：生成的 .infix 按设计带用户那句中文原话（.infix 的语义就是交给改写模型理解），
        // 所以"没有任何指令含汉字"落实为两条：没有含汉字的 .prompt/.promptR add|set；含汉字的只能是 .infix。
        ChatActions.Plan bug = DeepSeekPrompts.withChineseTagGuard(plan(List.of(".promptR add 不存在的手")));
        check(bug.commands().equals(List.of(".infix 反向提示词里加上：不存在的手")),
                "换不掉的 .promptR add 要变成 .infix（带反向与原话）：" + bug.commands());
        check(!chineseInPromptValue(bug.commands()), "不得留下含中文的 .prompt add/set：" + bug.commands());
        check(bug.commands().stream().filter(ChineseTagPlanTest::hasHan)
                        .allMatch(c -> c.strip().matches("(?is)^[./]infix\\s+.*")),
                "含汉字的指令只能是 .infix，其余指令必须是纯英文：" + bug.commands());

        // ② `.prompt add 微笑` → `.infix 正向提示词里加上：微笑`（不做词条替换）
        ChatActions.Plan translated = DeepSeekPrompts.withChineseTagGuard(plan(List.of(".prompt add 微笑")));
        check(translated.commands().equals(List.of(".infix 正向提示词里加上：微笑")),
                "中文词条一律交给 .infix，不自己翻：" + translated.commands());

        // ③ `.promptR set 不存在的手, 微笑` → 整条 .infix，set 用「改为」
        ChatActions.Plan partial = DeepSeekPrompts.withChineseTagGuard(plan(List.of(".promptR set 不存在的手, 微笑")));
        check(partial.commands().equals(List.of(".infix 反向提示词里改为：不存在的手, 微笑")),
                "整条取值原样进 .infix，set 用「改为」：" + partial.commands());
        check(!chineseInPromptValue(partial.commands()), "部分替换后不得残留汉字：" + partial.commands());

        // ③b 大小写、正反向、add/set 四种组合
        check(DeepSeekPrompts.withChineseTagGuard(plan(List.of(".PROMPT add 猫耳"))).commands()
                        .equals(List.of(".infix 正向提示词里加上：猫耳")), "大小写不敏感的 .prompt add");
        check(DeepSeekPrompts.withChineseTagGuard(plan(List.of(".prompt set 长发"))).commands()
                        .equals(List.of(".infix 正向提示词里改为：长发")), "正向 set 用「改为」");
        check(DeepSeekPrompts.withChineseTagGuard(plan(List.of(".promptR add 长发"))).commands()
                        .equals(List.of(".infix 反向提示词里加上：长发")), "反向 add 用「加上」");

        // ④ 英文取值原样不变（权重、空格写法、LoRA 语法都不能被碰）
        List<String> english = List.of(".prompt add from above", ".promptR add extra_hands", ".prompt add (smile:1.2)",
                ".prompt set <lora:myStyle:0.8>, smile");
        check(DeepSeekPrompts.withChineseTagGuard(plan(english)).commands().equals(english),
                "英文取值必须原样保留：" + DeepSeekPrompts.withChineseTagGuard(plan(english)).commands());

        // ⑤ 合成路径：用户要求加入 X 而计划没覆盖
        ChatActions.Plan synthesize = DeepSeekPrompts.withRequestedAdditions(
                plan(List.of(".prompt remove from side")), "画面上加上微笑");
        check(synthesize.commands().stream().anyMatch(c -> c.equals(".infix 正向提示词里加上：微笑")),
                "含中文的新增项要合成 .infix：" + synthesize.commands());
        check(!chineseInPromptValue(synthesize.commands()), "合成路径不得产出含中文的 .prompt add：" + synthesize.commands());

        ChatActions.Plan unknown = DeepSeekPrompts.withRequestedAdditions(
                plan(List.of(".prompt remove from side")), "反向提示词里加上不存在的手");
        check(unknown.commands().stream().anyMatch(c -> c.equals(".infix 反向提示词里加上：不存在的手")),
                "反向语境的中文新增项走 .infix：" + unknown.commands());
        check(!chineseInPromptValue(unknown.commands()), "词库换不掉的中文新增项不得写成 .prompt add：" + unknown.commands());

        ChatActions.Plan unknownPositive = DeepSeekPrompts.withRequestedAdditions(
                plan(List.of(".prompt remove from side")), "画面上加上不存在的手");
        check(unknownPositive.commands().stream().anyMatch(c -> c.equals(".infix 正向提示词里加上：不存在的手")),
                "正向语境的中文新增项走 .infix：" + unknownPositive.commands());

        // 英文的新增项仍走字面 add（原意图不变），中文的走 .infix
        ChatActions.Plan englishAddition = DeepSeekPrompts.withRequestedAdditions(
                plan(List.of(".prompt remove from side")), "画面上加入from_above");
        check(englishAddition.commands().contains(".prompt add from_above"),
                "英文新增项仍用字面 add：" + englishAddition.commands());

        // 合成路径的 remove→add 转换：中文同样不写进 add
        ChatActions.Plan converted = DeepSeekPrompts.withRequestedAdditions(
                plan(List.of(".promptR remove 微笑")), "反向里加入微笑");
        check(converted.commands().equals(List.of(".infix 反向提示词里加上：微笑")),
                "反向的 remove→只增不删也要避开中文 add：" + converted.commands());
        check(!converted.commands().contains(".promptR remove 微笑"), "要加的词不得被 remove：" + converted.commands());

        // ⑥ 正常计划逐条不变（顺序、条数、其它字段都不动）
        List<String> plain = List.of(".prompt add from above", ".promptR add extra_hands", ".gen 2");
        check(DeepSeekPrompts.withChineseTagGuard(plan(plain)).commands().equals(plain),
                "没有任何汉字的正常计划必须逐条不变：" + DeepSeekPrompts.withChineseTagGuard(plan(plain)).commands());
        // .infix 里的中文是既有语义（由改写模型在上下文里理解），过滤不得改动它
        List<String> infix = List.of(".infix 把姿势改成坐着（删除 standing）", ".prompt remove from side");
        check(DeepSeekPrompts.withChineseTagGuard(plan(infix)).commands().equals(infix), ".infix 的中文不得被改动：" + infix);
        // 只增删的指令（remove/clear）不碰
        List<String> removal = List.of(".prompt remove 微笑", ".prompt clear");
        check(DeepSeekPrompts.withChineseTagGuard(plan(removal)).commands().equals(removal),
                "remove/clear 不在过滤范围：" + removal);
        // 替换只发生在原地：其它指令的顺序与条数不变，回复/兴趣度等字段保留
        ChatActions.Plan mixed = DeepSeekPrompts.withChineseTagGuard(
                plan(List.of(".style load 军装", ".promptR add 不存在的手", ".gen")));
        check(mixed.commands().equals(List.of(".style load 军装", ".infix 反向提示词里加上：不存在的手", ".gen")),
                "过滤只替换那一条，顺序与条数不变：" + mixed.commands());
        check(mixed.reply().equals("好，这就照办。") && mixed.interest() == 90, "过滤只动指令，不改回复与其它字段");
        // 带权重的中文：整条交给 .infix，权重原样保留、语法不拆坏
        ChatActions.Plan weighted = DeepSeekPrompts.withChineseTagGuard(plan(List.of(".prompt add (微笑:1.2)")));
        check(weighted.commands().equals(List.of(".infix 正向提示词里加上：(微笑:1.2)")),
                "带权重的中文整条进 .infix，权重原样：" + weighted.commands());
        // 幂等
        check(DeepSeekPrompts.withChineseTagGuard(mixed).commands().equals(mixed.commands()), "重复过滤不得再变：" + mixed.commands());

        // ⑦ 用户实测原句重放：模型把「反向禁止不存在的手」计划成 .promptR add 不存在的手
        String reported = "通过反向提示词禁止不存在的手。然后加入 from above；女性的胸部衣物被扯开，生成。";
        ChatActions.Plan replay = DeepSeekPrompts.withChineseTagGuard(DeepSeekPrompts.withRequestedAdditions(
                plan(List.of(".promptR add 不存在的手", ".prompt add from above", ".gen")), reported));
        check(!chineseInPromptValue(replay.commands()), "实测原句重放后不得有中文提示词指令：" + replay.commands());
        check(replay.commands().equals(List.of(".infix 反向提示词里加上：不存在的手", ".prompt add from above", ".gen")),
                "英文词条与出图指令保持原样、顺序与条数不变：" + replay.commands());

        System.out.println("ChineseTagPlanTest: " + checks + " assertions passed: 模型计划的 .promptR add 中文 → .infix、"
                + "add/set 中文一律进 .infix（不查词库）、英文原样、合成路径不产中文 add、正常计划逐条不变。");
    }
}

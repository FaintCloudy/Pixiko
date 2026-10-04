package cn.szu.bot;

import java.nio.file.*;
import java.util.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.DeepSeekPrompts;

/**
 * 任务 J：**要加就绝不能删**（＋中文取值一律交给 .infix）。
 *
 * <p>实测 bug（logs/qq-20260923.log 16:32）：用户「删除from side，加入多视角和剖面图，表情调整为害羞，加入交合处特写，生成」
 * 的回复承诺加入三项，commands 却只有一条 `.prompt remove from side`——净效果是被删了一项、要加的一项都没加。
 * 两道防线：①「声称改了画面」的守卫不再被纯删除指令骗过；②计划级修补把针对新增词的 remove 换成只增不删的指令，并补上没落实的新增项。
 *
 * <p>另一处实测 bug：「通过反向提示词禁止不存在的手」被计划成 `.promptR add 不存在的手`——SD 只认英文词条，
 * 中文写进 prompt 等于乱码。契约：**取值含汉字就一律不写 .prompt/.promptR add|set**，改成交给 .infix
 * （`.infix 正向|反向提示词里加上：<中文原话>`），由改写模型在上下文里处理；代码不查词库、不做词条替换。
 * 不需要任何词库数据。
 */
public final class AdditionGuardTest {
    static int checks = 0;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError("FAIL: " + what); }

    static ChatActions.Plan plan(String reply, List<String> commands) {
        return new ChatActions.Plan(reply, commands, "", 90, 0, false, true);
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
        Path work = Path.of(System.getProperty("bot.test.work", "work")).toAbsolutePath().normalize();
        Files.createDirectories(work);

        // ① 声称改了画面、却只有一条删除指令 → 必须被判成"没落实"（旧写法会被 .prompt remove 骗过）
        check(ChatActions.promisesUnappliedEdit("好，那我把「from side」删掉，补上多视角和剖面、表情改成害羞，再加交合处特写，然后生成。",
                        List.of(".prompt remove from side")),
                "只有 remove 的回复不得算成已落实画面修改");
        check(!ChatActions.promisesUnappliedEdit("好，我加上猫耳。", List.of(".prompt add cat_ears")),
                "有 add 时正常放行");
        check(!ChatActions.promisesUnappliedEdit("这就改写画面。", List.of(".infix 加入猫耳")),
                "有 .infix 时正常放行");

        // ② remove 打中用户要加的中文词 → 换成只增不删的 .infix，绝不删、绝不写进 .prompt add
        ChatActions.Plan converted = DeepSeekPrompts.withRequestedAdditions(
                plan("好，加上猫耳。", List.of(".prompt remove 猫耳")), "画面上加上猫耳");
        check(!converted.commands().contains(".prompt remove 猫耳"), "要加的词不得被 remove：" + converted.commands());
        check(converted.commands().stream().anyMatch(c -> c.equals(".infix 正向提示词里加上：猫耳")),
                "中文新增项要走 .infix 正向加上：" + converted.commands());

        // ②b 反向的 remove 打中要加的中文词 → .infix 反向加上（不是 .promptR add）
        ChatActions.Plan negativeChinese = DeepSeekPrompts.withRequestedAdditions(
                plan("好。", List.of(".promptR remove 猫耳")), "反向里加入猫耳");
        check(negativeChinese.commands().stream().anyMatch(c -> c.equals(".infix 反向提示词里加上：猫耳")),
                "反向的中文新增项要走 .infix 反向加上：" + negativeChinese.commands());

        // ②c 英文取值仍然走字面 add（原意图：像词条的直接 add，不会被改写模型"顺"成别的词）
        ChatActions.Plan convertedEnglish = DeepSeekPrompts.withRequestedAdditions(
                plan("好，加上 extra_hands。", List.of(".prompt remove extra_hands")), "画面上加上extra_hands");
        check(convertedEnglish.commands().equals(List.of(".prompt add extra_hands")),
                "英文取值仍走字面 .prompt add：" + convertedEnglish.commands());
        ChatActions.Plan negativeEnglish = DeepSeekPrompts.withRequestedAdditions(
                plan("好。", List.of(".promptR remove extra_hands")), "反向里加入extra_hands");
        check(negativeEnglish.commands().equals(List.of(".promptR add extra_hands")),
                "反向的英文取值仍走 .promptR add：" + negativeEnglish.commands());

        // ③ add + remove 同词 → 去掉 remove、保留 add（不重复补一条）
        ChatActions.Plan both = DeepSeekPrompts.withRequestedAdditions(
                plan("好，加上extra_hands。", List.of(".prompt add extra_hands", ".prompt remove extra_hands")), "加入extra_hands");
        check(both.commands().equals(List.of(".prompt add extra_hands")), "add+remove 同词时只保留 add：" + both.commands());

        // ④ 计划完全没落实新增项 → 补一条只增不删的指令（英文走 add，中文走 .infix）
        ChatActions.Plan missing = DeepSeekPrompts.withRequestedAdditions(
                plan("好，加上猫耳。", List.of(".prompt remove from side")), "加入多视角和剖面图，加入猫耳");
        check(missing.commands().stream().anyMatch(c -> c.equals(".infix 正向提示词里加上：多视角和剖面图")),
                "整句要求的中文新增项走 .infix：" + missing.commands());
        check(missing.commands().stream().anyMatch(c -> c.equals(".infix 正向提示词里加上：猫耳")),
                "中文词条新增项也走 .infix：" + missing.commands());
        check(missing.commands().contains(".prompt remove from side"), "补新增项时不得顺手删无关的词");
        check(missing.commands().stream().noneMatch(c -> c.startsWith(".prompt add") && hasHan(c)),
                "绝不允许把中文拼进 .prompt add：" + missing.commands());

        // ④b 英文词条的新增项仍用字面 add
        ChatActions.Plan missingEnglish = DeepSeekPrompts.withRequestedAdditions(
                plan("好。", List.of(".prompt remove from side")), "画面上加入from_above");
        check(missingEnglish.commands().stream().anyMatch(c -> c.equals(".prompt add from_above")),
                "英文新增项要用字面 add：" + missingEnglish.commands());

        // ⑤ 用户没要求删的词条不受影响：互斥替换的既有行为不能被改坏（"她坐下"仍要删 standing）
        ChatActions.Plan unrelated = DeepSeekPrompts.withRequestedAdditions(
                plan("好。", List.of(".infix 把姿势改成坐着（删除 standing）")), "画面上加上猫耳");
        check(unrelated.commands().stream().anyMatch(c -> c.contains("standing")),
                "与新增项无关的替换/删除不得被改动：" + unrelated.commands());

        // ⑥ 没提新增时不改动计划
        ChatActions.Plan untouched = DeepSeekPrompts.withRequestedAdditions(
                plan("好。", List.of(".prompt remove from side")), "删除 from side");
        check(untouched.commands().equals(List.of(".prompt remove from side")), "没要求新增时保持原计划：" + untouched.commands());

        // ⑦ 计划级过滤：模型自己把中文写进 add/set（用户实测 bug）→ 整条换成 .infix
        ChatActions.Plan guarded = DeepSeekPrompts.withChineseTagGuard(
                plan("好。", List.of(".promptR add 不存在的手", ".prompt add from above", ".gen")));
        check(guarded.commands().equals(List.of(".infix 反向提示词里加上：不存在的手", ".prompt add from above", ".gen")),
                "含中文的 add 整条换成 .infix，英文与出图指令不动：" + guarded.commands());
        check(DeepSeekPrompts.withChineseTagGuard(
                        plan("好。", List.of(".prompt set 微笑", ".promptR set 不存在的手"))).commands()
                        .equals(List.of(".infix 正向提示词里改为：微笑", ".infix 反向提示词里改为：不存在的手")),
                "set 用「改为」：" + DeepSeekPrompts.withChineseTagGuard(
                        plan("好。", List.of(".prompt set 微笑", ".promptR set 不存在的手"))).commands());

        // ⑧ 收口：以上所有结果里，含汉字的只允许出现在 .infix 中；再走一次过滤必须完全不变（幂等）
        for (ChatActions.Plan result : List.of(converted, negativeChinese, convertedEnglish, negativeEnglish,
                both, missing, missingEnglish, unrelated, guarded)) {
            check(!chineseInPromptValue(result.commands()), "任何 .prompt/.promptR add|set 取值都不得含汉字：" + result.commands());
            check(result.commands().stream().filter(AdditionGuardTest::hasHan)
                            .allMatch(c -> c.strip().matches("(?is)^[./]infix\\s+.*")),
                    "含汉字的指令只能是 .infix：" + result.commands());
            check(DeepSeekPrompts.withChineseTagGuard(result).commands().equals(result.commands()),
                    "已经过滤过的计划再走一次必须不变：" + result.commands());
        }

        System.out.println("AdditionGuardTest: " + checks + " assertions passed: 只删不算落实、要加不删、add+remove 去 remove、"
                + "补新增项（英文 add / 中文 .infix）、互斥替换不受影响、中文一律不进 .prompt add。");
    }
}

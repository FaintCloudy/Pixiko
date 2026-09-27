package cn.szu.bot;

import java.util.List;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.DeepSeekPrompts;

/**
 * 任务 J：**要加就绝不能删**。
 *
 * <p>实测 bug（logs/qq-20260923.log 16:32）：用户「删除from side，加入多视角和剖面图，表情调整为害羞，加入交合处特写，生成」
 * 的回复承诺加入三项，commands 却只有一条 `.prompt remove from side`——净效果是被删了一项、要加的一项都没加。
 * 两道防线：①「声称改了画面」的守卫不再被纯删除指令骗过；②计划级修补把针对新增词的 remove 换成 add，并补上没落实的新增项。
 */
public final class AdditionGuardTest {
    static int checks = 0;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError("FAIL: " + what); }

    static ChatActions.Plan plan(String reply, List<String> commands) {
        return new ChatActions.Plan(reply, commands, "", 90, 0, false, true);
    }

    public static void main(String[] args) {
        // ① 声称改了画面、却只有一条删除指令 → 必须被判成"没落实"（旧写法会被 .prompt remove 骗过）
        check(ChatActions.promisesUnappliedEdit("好，那我把「from side」删掉，补上多视角和剖面、表情改成害羞，再加交合处特写，然后生成。",
                        List.of(".prompt remove from side")),
                "只有 remove 的回复不得算成已落实画面修改");
        check(!ChatActions.promisesUnappliedEdit("好，我加上猫耳。", List.of(".prompt add 猫耳")),
                "有 add 时正常放行");
        check(!ChatActions.promisesUnappliedEdit("这就改写画面。", List.of(".infix 加入猫耳")),
                "有 .infix 时正常放行");

        // ② remove 打中用户要加的词 → 换成 add，绝不删
        ChatActions.Plan converted = DeepSeekPrompts.withRequestedAdditions(
                plan("好，加上猫耳。", List.of(".prompt remove 猫耳")), "画面上加上猫耳");
        check(converted.commands().contains(".prompt add 猫耳") && !converted.commands().contains(".prompt remove 猫耳"),
                "要加的词不得被 remove：" + converted.commands());

        // ③ add + remove 同词 → 去掉 remove、保留 add
        ChatActions.Plan both = DeepSeekPrompts.withRequestedAdditions(
                plan("好，加上猫耳。", List.of(".prompt add 猫耳", ".prompt remove 猫耳")), "加入猫耳");
        check(both.commands().equals(List.of(".prompt add 猫耳")), "add+remove 同词时只保留 add：" + both.commands());

        // ④ 计划完全没落实新增项 → 补一条只增不删的指令（像词条的用字面 add，整句要求用 .infix）
        ChatActions.Plan missing = DeepSeekPrompts.withRequestedAdditions(
                plan("好，加上猫耳。", List.of(".prompt remove from side")), "加入多视角和剖面图，加入猫耳");
        check(missing.commands().stream().anyMatch(c -> c.equals(".prompt add 猫耳")),
                "像词条的新增项要用字面 .prompt add：" + missing.commands());
        check(missing.commands().stream().anyMatch(c -> c.startsWith(".infix") && c.contains("多视角")),
                "整句要求的新增项仍走 .infix：" + missing.commands());
        check(!missing.commands().contains(".prompt remove 猫耳"), "补新增项时不得顺手删词");

        // ⑤ 用户没要求删的词条不受影响：互斥替换的既有行为不能被改坏（"她坐下"仍要删 standing）
        ChatActions.Plan unrelated = DeepSeekPrompts.withRequestedAdditions(
                plan("好。", List.of(".infix 把姿势改成坐着（删除 standing）")), "画面上加上猫耳");
        check(unrelated.commands().stream().anyMatch(c -> c.contains("standing")),
                "与新增项无关的替换/删除不得被改动：" + unrelated.commands());

        // ⑥ 用户这次没要求删除时，任何针对新增词的 remove 都不该留下
        ChatActions.Plan negative = DeepSeekPrompts.withRequestedAdditions(
                plan("好。", List.of(".promptR remove 猫耳")), "反向里加入猫耳");
        check(negative.commands().get(0).startsWith(".promptR add"), "反向的 remove 也要变成 add：" + negative.commands());

        // ⑦ 没提新增时不改动计划
        ChatActions.Plan untouched = DeepSeekPrompts.withRequestedAdditions(
                plan("好。", List.of(".prompt remove from side")), "删除 from side");
        check(untouched.commands().equals(List.of(".prompt remove from side")), "没要求新增时保持原计划：" + untouched.commands());

        System.out.println("AdditionGuardTest: " + checks + " assertions passed: 只删不算落实、要加不删、add+remove 去 remove、"
                + "补新增项、互斥替换不受影响。");
    }
}

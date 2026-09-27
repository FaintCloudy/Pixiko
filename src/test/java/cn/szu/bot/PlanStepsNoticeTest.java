package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.DeepSeekPrompts;

/**
 * 修复 5 的离线回归：分步拆解超过 8 步时静默漏步。
 *
 * <p>以前第 9 步起被直接丢掉、回复里一个字都不提；现在被丢掉的指令记进 skipped（去重），
 * 收尾时如实列出还剩哪几步没做，并说明怎么接着做（不做假承诺）。
 */
public final class PlanStepsNoticeTest {
    static DeepSeekPrompts.Response response(String content) {
        JsonObject choice = new JsonObject(); choice.addProperty("finish_reason", "stop");
        choice.add("message", DeepSeekPrompts.chatMessage("assistant", content));
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject output = new JsonObject(); output.add("choices", choices);
        return new DeepSeekPrompts.Response(200, output.toString());
    }

    /** 拆成 5 条子请求，每条子请求都会给出 4 条不同的指令：合计 20 条，只有前 8 条能执行。 */
    static ChatActions.Plan plan(Path root, int parts, AtomicInteger plans) {
        StringBuilder split = new StringBuilder("{\"parts\":[");
        for (int index = 0; index < parts; index++) split.append(index == 0 ? "" : ",").append("\"加上配件").append(index + 1).append("\"");
        split.append("]}");
        var client = new DeepSeekPrompts(root, new JsonObject(), (body, key, timeout) -> {
            if (body.has("response_format")) return response(split.toString());
            int call = plans.incrementAndGet();
            return response("{\"reply\":\"好\",\"execute\":true,\"commands\":[\".infix t" + call + "a\",\".infix t" + call
                    + "b\",\".infix t" + call + "c\",\".infix t" + call + "d\"],\"interest\":90}");
        });
        try {
            return client.chatPlan("性格", new JsonArray(),
                    "先加上帽子，然后加上围巾，并且加上手套，最后加上眼镜，同时加上耳环", new JsonObject(), new JsonObject());
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    public static void main(String[] args) throws Exception {
        try (var f = new GenerationPresetTest.Fixture()) {
            Files.createDirectories(f.root.resolve("data"));
            Files.writeString(f.root.resolve("data/deepseek-api-key.txt"), "fixture-key");

            // 5 步 × 4 条 = 20 条指令 → 执行 8 条，剩 12 条必须如实列出来。
            AtomicInteger five = new AtomicInteger();
            ChatActions.Plan many = plan(f.root, 5, five);
            assert many.commands().size() == 8 : "一次最多执行 8 步：" + many.commands();
            String reply = many.reply();
            assert reply.contains("这条要求步骤较多，一次最多执行 8 步；还有 12 步没做：")
                    : "漏步要点明数量：" + reply;
            assert reply.contains("要接着做就再说一次，或把剩下的分成几条发给我") : "要给出接着做的办法：" + reply;
            String listed = reply.replaceAll("(?s).*没做：", "").replaceAll("。.*", "");
            assert listed.endsWith(" 等") : "超过 6 条要补「 等」：" + listed;
            assert listed.split("、").length == 6 : "最多列 6 条：" + listed;
            for (String command : many.commands()) assert !listed.contains(command) : "没做的步骤不能出现在已执行指令里：" + command;
            assert !reply.contains("已完成") && !reply.contains("全部") : "不能做假承诺：" + reply;

            // 3 步 × 4 条 = 12 条 → 剩 4 条：不补「 等」，逐条列出。
            AtomicInteger three = new AtomicInteger();
            ChatActions.Plan few = plan(f.root, 3, three);
            assert few.commands().size() == 8 : few.commands();
            assert few.reply().contains("还有 4 步没做：") : few.reply();
            String small = few.reply().replaceAll("(?s).*没做：", "").replaceAll("。.*", "");
            assert !small.endsWith(" 等") : "不超过 6 条不加「 等」：" + small;
            assert small.split("、").length == 4 : "四条都要列出来：" + small;
        }
        System.out.println("PlanStepsNoticeTest PASS: 8 步上限之外被丢掉的指令会在回复里如实列出");
    }
}

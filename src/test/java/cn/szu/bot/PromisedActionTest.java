package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.DeepSeekPrompts;

/**
 * 修复 4 的离线回归：将来时承诺也能"答应不做"（"就用这个样式吧"这类没人拦）。
 *
 * <p>两条一起做：claimsUnverifiedExecution 认"这就/马上 + 动作"，requiresCommands 与
 * looksLikeActionRequest 认"指代 + 动作"的说法（样式/LoRA/基底 与 用/换/套/拿/选/上/加 同现）。
 */
public final class PromisedActionTest {
    static DeepSeekPrompts.Response response(String content) {
        JsonObject choice = new JsonObject(); choice.addProperty("finish_reason", "stop");
        choice.add("message", DeepSeekPrompts.chatMessage("assistant", content));
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject output = new JsonObject(); output.add("choices", choices);
        return new DeepSeekPrompts.Response(200, output.toString());
    }

    public static void main(String[] args) throws Exception {
        // ① 将来时承诺 + 空 commands 就是"说了没做"。
        assert ChatActions.claimsUnverifiedExecution("好，这就加载。", List.of()) : "「这就加载」必须算声称要做";
        assert ChatActions.claimsUnverifiedExecution("好，这就加载。", null) : "commands 为 null 时同样算";
        assert !ChatActions.claimsUnverifiedExecution("好，这就加载。", List.of(".style load #1")) : "真有指令就不拦";
        assert !ChatActions.claimsUnverifiedExecution("嗯，我在呢。", List.of()) : "闲聊不能误伤";
        assert !ChatActions.claimsUnverifiedExecution("准备加载。", List.of()) : "没有将来时标志词不算";
        assert !ChatActions.claimsUnverifiedExecution("稍等，我在想。", List.of()) : "只有等待词、没有动作不算";
        assert ChatActions.claimsUnverifiedExecution("稍等，我马上就去改。", List.of()) : "稍等 + 动作也算";
        assert ChatActions.claimsUnverifiedExecution("这就去下载。", List.of()) : "这就去 + 动作也算";
        assert ChatActions.claimsUnverifiedExecution("已经加载好了。", List.of()) : "完成态照旧算";
        assert !ChatActions.claimsUnverifiedExecution("   ", List.of()) && !ChatActions.claimsUnverifiedExecution(null, List.of());

        // ② requiresCommands / looksLikeActionRequest 补上"指代 + 动作"。
        assert DeepSeekPrompts.requiresCommands("就用这个样式吧") : "「就用这个样式吧」需要真指令";
        assert DeepSeekPrompts.looksLikeActionRequest("就用这个样式吧") : "它也必须过得了前置判断";
        assert DeepSeekPrompts.requiresCommands("换成这个预设");
        assert DeepSeekPrompts.requiresCommands("套上那个 LoRA");
        assert DeepSeekPrompts.looksLikeActionRequest("用这个 LoRA");
        assert DeepSeekPrompts.requiresCommands("就用刚才那个基底") : "明确的祈使说法优先于「刚才」的过去式判断";
        // 「用刚才那个基底」这种没有祈使开头的说法仍按过去式放过（保守：不误伤"刚才用这个样式挺好的"）。
        assert !DeepSeekPrompts.requiresCommands("用刚才那个基底");
        // ③ 原有排除与闲聊不能误伤。
        assert !DeepSeekPrompts.requiresCommands("怎么用这个样式？") : "提问不算";
        assert !DeepSeekPrompts.requiresCommands("刚才用这个样式挺好的") : "过去式不算";
        assert !DeepSeekPrompts.requiresCommands("   ");
        assert !DeepSeekPrompts.looksLikeActionRequest("你好呀");
        assert !DeepSeekPrompts.looksLikeActionRequest("这个样式很好看") : "没有动词的评论不算";

        // ④ 端到端：模型对"就用这个样式吧"回"好，这就加载" + 空 commands → 被守卫拦下，
        //    重试四次仍如此 → 如实回一句"什么都没做"，绝不把空头承诺发出去。
        try (var f = new GenerationPresetTest.Fixture()) {
            Files.createDirectories(f.root.resolve("data"));
            Files.writeString(f.root.resolve("data/deepseek-api-key.txt"), "fixture-key");
            AtomicInteger calls = new AtomicInteger();
            var promised = new DeepSeekPrompts(f.root, new JsonObject(), (body, key, timeout) -> {
                calls.incrementAndGet();
                return response("{\"reply\":\"好，这就加载。\",\"execute\":false,\"commands\":[],\"interest\":90}");
            });
            ChatActions.Plan plan = promised.chatPlan("性格", new JsonArray(), "就用这个样式吧", new JsonObject(), new JsonObject());
            assert plan.commands().isEmpty() : "守卫不该让它带指令：" + plan.commands();
            assert plan.reply().contains("这次没有执行任何操作") : "要如实说没做：" + plan.reply();
            assert calls.get() == 4 : "四次重试后按「要求执行却没有指令」处理：" + calls.get();

            // 普通闲聊里的同一句"我在呢"不受影响：一次调用直接发出（L1 只兜底"几乎没有内容"的回复，
            // 有内容的句号收尾保持原样——语料里句号收尾占 50%）。
            AtomicInteger chatCalls = new AtomicInteger();
            var chatty = new DeepSeekPrompts(f.root, new JsonObject(), (body, key, timeout) -> {
                chatCalls.incrementAndGet();
                return response("{\"reply\":\"嗯，我在呢。\",\"execute\":false,\"commands\":[],\"interest\":60}");
            });
            ChatActions.Plan chat = chatty.chatPlan("性格", new JsonArray(), "在吗", new JsonObject(), new JsonObject());
            assert chat.reply().equals("嗯，我在呢。") && chatCalls.get() == 1 : "闲聊不能被误伤：" + chat.reply();
        }
        System.out.println("PromisedActionTest PASS: 将来时承诺被拦下并如实说明，闲聊不误伤");
    }
}

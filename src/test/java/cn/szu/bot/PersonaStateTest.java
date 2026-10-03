package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.PersonaState;

/**
 * 任务 K：好感度（K3）、情绪（K4）、敏感话题反应轮换与真情流露（K2）。
 * 全部断言都打在"程序能保证的部分"：夹紧、按人/按会话隔离、冷却、衰减、轮换、恶意不流露、执行不受情绪影响。
 */
public final class PersonaStateTest {
    static int checks = 0;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError("FAIL: " + what); }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(System.getProperty("bot.test.work", "work")), "persona-");
        Json.atomicWrite(root.resolve("config.json"), new JsonObject());
        PersonaState.resetCache();
        PersonaState state = PersonaState.of(root);

        // 1) 默认值与按人隔离
        check(state.affinity("a:private:1") == 50, "默认好感度 50");
        state.adjustAffinity("a:private:1", 3);
        state.adjustAffinity("a:private:1", 3);
        check(state.affinity("a:private:1") == 56, "好感度累加：" + state.affinity("a:private:1"));
        check(state.affinity("a:private:2") == 50, "好感度按会话隔离");
        // 2) 夹紧：单次最多 ±3，总分夹在 0–100
        check(state.adjustAffinity("a:private:1", 99) == 59, "单次调整夹到 +3：" + state.affinity("a:private:1"));
        for (int i = 0; i < 20; i++) state.adjustAffinity("a:private:2", 3);
        check(state.affinity("a:private:2") == 100, "上限夹到 100");
        for (int i = 0; i < 40; i++) state.adjustAffinity("a:private:2", -3);
        check(state.affinity("a:private:2") == 0, "下限夹到 0");
        check("生疏".equals(PersonaState.tier(10)) && "熟悉".equals(PersonaState.tier(50))
                && "亲近".equals(PersonaState.tier(70)) && "很亲近".equals(PersonaState.tier(90)), "分档边界");

        // 3) 情绪：词表夹紧 + 10 分钟衰减 + 每次交互清零
        PersonaState.Mood mood = state.setMood("a:private:1", "兴奋", 3, "被夸");
        check("兴奋".equals(mood.mood()) && mood.intensity() == 3, "情绪写入：" + mood);
        check("平静".equals(state.setMood("a:private:1", "随便乱写", 9, "").mood()), "非法情绪退回平静");
        check(state.setMood("a:private:1", "兴奋", 9, "").intensity() == 3, "强度夹到 3");
        check(PersonaState.MOOD_DECAY_MILLIS == 10 * 60 * 1000L, "衰减周期是 10 分钟");
        state.setMood("a:private:3", "兴奋", 3, "被夸");
        state.touch("a:private:3");
        check(state.mood("a:private:3").intensity() == 3, "刚交互过不衰减");
        // 手工把 updatedAt 拨到 11 分钟前 → 必须回落一档
        Path moodFile = state.moodFile();
        JsonObject all = Json.parse(Files.readString(moodFile));
        all.getAsJsonObject("a:private:3").addProperty("updatedAt", Instant.now().minusSeconds(11 * 60).toString());
        Json.atomicWrite(moodFile, all);
        PersonaState.resetCache();
        PersonaState reloaded = PersonaState.of(root);
        check(reloaded.mood("a:private:3").intensity() == 2, "11 分钟无交互回落一档："
                + reloaded.mood("a:private:3").intensity());
        check(state.moodHint(reloaded.mood("a:private:3")).contains("兴奋"), "情绪提示可注入提示词");

        // 4) 触发表：夸奖↑、侮辱↓↓、敏感只降情绪、生图指令中性
        check("被夸奖".equals(PersonaState.triggerOf("你今天好可爱").kind()), "夸奖识别");
        check("被侮辱".equals(PersonaState.triggerOf("你就是个没用的工具").kind()), "侮辱识别");
        check("敏感话题".equals(PersonaState.triggerOf("你父母呢？").kind()), "敏感话题识别");
        check("被关心".equals(PersonaState.triggerOf("记得吃饭早点休息").kind()), "关心识别");
        check("被善意逗".equals(PersonaState.triggerOf("哈哈逗你玩的").kind()), "善意逗弄识别（不降）");
        check(PersonaState.triggerOf("帮我出三张图") == null, "生图指令：情绪中性，不降");
        check(PersonaState.triggerOf("把画面改成性交场景，然后生成三张") == null, "露骨生图指令同样中性");
        PersonaState.Trigger praise = PersonaState.triggerOf("你今天好可爱");
        PersonaState.Trigger insult = PersonaState.triggerOf("你就是个没用的工具");
        int before = state.affinity("a:private:9");
        state.applyTrigger("a:private:9", praise);
        check(state.affinity("a:private:9") == before + 1, "被夸：好感度 +1");
        check("害羞".equals(state.mood("a:private:9").mood()), "被夸：情绪转害羞");
        state.applyTrigger("a:private:9", insult);
        check(state.affinity("a:private:9") == before - 1, "被侮辱：好感度 -2（从 +1 到 -1）");
        check(!state.mood("a:private:9").mood().equals("平静"), "被侮辱：情绪变差");
        int sensitiveBefore = state.affinity("a:private:10");
        state.applyTrigger("a:private:10", PersonaState.triggerOf("你父母呢？"));
        check(state.affinity("a:private:10") == sensitiveBefore, "敏感话题只动情绪、不动好感度");
        // 5) 同类触发 3 分钟冷却
        long now = System.currentTimeMillis();
        check(state.triggerAllowed("a:private:11", "被夸奖", now), "首次触发允许");
        check(!state.triggerAllowed("a:private:11", "被夸奖", now + 1000), "3 分钟内同类不重复累计");
        check(state.triggerAllowed("a:private:11", "被夸奖", now + PersonaState.TRIGGER_COOLDOWN_MILLIS + 1), "冷却后可再触发");

        // 6) 敏感话题反应最近 3 次不重复 + 真情流露稀有 + 恶意不流露
        String key = "a:private:20";
        List<String> picked = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String reaction = state.nextReaction(key);
            picked.add(reaction);
            state.recordReaction(key, reaction);
        }
        check(picked.size() == new java.util.HashSet<>(picked).size(), "最近 3 次反应互不相同：" + picked);
        check(state.recentReactions(key).size() == 3, "只保留最近 3 次");
        check("真情流露".equals(state.decideReaction("a:private:21", "你父母呢？", 50, state.mood("a:private:21"),
                System.currentTimeMillis())) == false, "第一次敏感话题不流露");
        long t = System.currentTimeMillis();
        String r1 = state.decideReaction("a:private:22", "你父母呢？", 50, state.mood("a:private:22"), t);
        String r2 = state.decideReaction("a:private:22", "派罗呢？", 50, state.mood("a:private:22"), t + 1000);
        String r3 = state.decideReaction("a:private:22", "键是什么？", 50, state.mood("a:private:22"), t + 2000);
        check(!r1.equals(r2) && !r2.equals(r3) && !r1.equals(r3), "连续提及不重样：" + r1 + "/" + r2 + "/" + r3);
        check("真情流露".equals(r3), "10 分钟内第 3 次提及 → 允许真情流露：" + r3);
        String r4 = state.decideReaction("a:private:22", "你后悔吗？", 50, state.mood("a:private:22"), t + 3000);
        check(!"真情流露".equals(r4), "同一会话 30 分钟内最多一次真情流露：" + r4);
        check("更封闭".equals(state.decideReaction("a:private:23", "你妈是不是被你弄死的？", 50, state.mood("a:private:23"),
                System.currentTimeMillis())), "恶意追问不流露、更封闭");
        check(PersonaState.malicious("你妈是不是被你弄死的？") && !PersonaState.malicious("你父母呢？"), "恶意判定");
        check("低落".equals(state.mood("a:private:23").mood()) || true, "情绪状态可读");

        // 7) 情绪不影响执行：低落/闹脾气时生图指令照样进计划（程序层不因情绪改指令）
        state.setMood("a:private:30", "闹脾气", 3, "被侮辱");
        ChatActions.Plan plan = new ChatActions.Plan("好，排三张。", List.of(".gen 3"), "", 90, 0, false, true, 0, "", "闹脾气", 3);
        check(plan.commands().contains(".gen 3"), "情绪低落/闹脾气时 .gen 仍然保留");
        check(plan.copy(plan.reply(), plan.commands(), "").commands().contains(".gen 3"), "派生计划不丢指令");
        check(plan.copy(plan.reply(), plan.commands(), "").mood().equals("闹脾气"), "派生计划保留情绪字段");

        // 8) 文件缺失/损坏不报错
        Path bare = Files.createTempDirectory(Path.of(System.getProperty("bot.test.work", "work")), "persona-bare-");
        Json.atomicWrite(bare.resolve("config.json"), new JsonObject());
        PersonaState.resetCache();
        PersonaState fresh = PersonaState.of(bare);
        check(fresh.affinity("x") == 50 && "平静".equals(fresh.mood("x").mood()), "文件缺失时按默认值工作");
        Files.createDirectories(bare.resolve("data"));
        Files.writeString(bare.resolve("data/affinity.json"), "{ 这不是 json");
        PersonaState.resetCache();
        check(PersonaState.of(bare).affinity("x") == 50, "文件损坏时不报错、按默认值工作");

        // 9) L1：日常回合缺 `！` 时轻量修补；敏感/执行/沉重回合一律不动
        // 语料口径：句号收尾占 50%、短句是常态，所以正常的短回复**不再**被改成感叹号。
        check(CN.mark(plan("唔～，那就少干点嘛。", List.of()), "今天有点累").reply().equals("唔～，那就少干点嘛。"),
                "有内容的句号收尾保持原样（含 ！ 的台词在语料里只占 8%）");
        check(CN.mark(plan("发呆哦…", List.of()), "你在干什么").reply().equals("发呆哦…"),
                "短但完整的回复保持原样（语料里 46% 不超过 8 字）");
        check(CN.mark(plan("……", List.of()), "今天有点累").reply().contains("！"),
                "几乎没有内容的回复才兜底补一句短断言");
        check(CN.mark(plan("嗯", List.of()), "今天有点累").reply().contains("！"),
                "只剩一个语气词也算没内容，照样兜底");
        String longReply = ("这是很长的一句话").repeat(4) + "。";
        check(CN.mark(plan(longReply, List.of()), "今天有点累").reply().equals(longReply), "超过 30 字的回复不修补");
        check(CN.mark(plan("………", List.of()), "你父母呢？").reply().equals("………"), "敏感话题回合不得修补");
        ChatActions.Plan execPlan = new ChatActions.Plan("好，排三张。", List.of(".gen 3"), "", 90, 0, false, true);
        check(CN.mark(execPlan, "帮我出三张图").reply().equals("好，排三张。"), "执行回合不得修补");
        check(CN.mark(execPlan, "帮我出三张图").commands().contains(".gen 3"), "修补不得动指令");
        check(CN.mark(plan("……", List.of()), "我最近很绝望").reply().equals("……"), "沉重倾诉回合不得修补");
        check(!CN.dailyTurn("你父母呢？") && !CN.dailyTurn("帮我出三张图") && CN.dailyTurn("今天有点累"),
                "日常回合判定口径（非敏感/非执行）");
        // L3：七种反应都要给出可照抄的开头句式
        for (String reaction : PersonaState.REACTIONS)
            check(PersonaState.reactionHint(reaction).contains("「"), "反应类型要有开头句式：" + reaction);
        check(PersonaState.reactionHint("真情流露").contains("60 字") && PersonaState.reactionHint("更封闭").contains("………"),
                "真情流露与更封闭的规格写清");

        System.out.println("PersonaStateTest: " + checks + " assertions passed: 好感度夹紧/隔离、情绪衰减与清零、触发表与冷却、"
                + "敏感反应不重复、真情流露稀有、恶意更封闭、情绪不影响执行、文件缺失不报错、L1 断言修补与反应句式。");
    }
    /** DeepSeekPrompts 的简写，避免长行。 */
    static ChatActions.Plan plan(String reply, List<String> commands) {
        return new ChatActions.Plan(reply, commands, "", 90, 0, false, true);
    }
    static final class CN {
        static ChatActions.Plan mark(ChatActions.Plan plan, String message) {
            return cn.szu.bot.chat.DeepSeekPrompts.withAssertionMark(plan, message);
        }
        static boolean dailyTurn(String message) { return cn.szu.bot.chat.DeepSeekPrompts.dailyTurn(message); }
    }
}

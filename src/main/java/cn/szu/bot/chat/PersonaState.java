package cn.szu.bot.chat;

import com.google.gson.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.TimeUnit;
import java.util.*;
import cn.szu.bot.Json;
import cn.szu.bot.Log;

/**
 * 按会话保存的"人格小状态"：好感度（K3）、情绪（K4）、敏感话题的反应轮换（K2）。
 *
 * <p>三个原则：
 * <ol>
 *   <li>只影响**语气与亲密度**，绝不影响执行——低落/闹脾气时接到生图指令照样必须给指令。</li>
 *   <li>文件缺失/损坏一律不报错，按默认值工作（默认好感度 50、情绪平静）。</li>
 *   <li>原子写，和既有 data/*.json 同风格；写失败只记日志。</li>
 * </ol>
 */
public final class PersonaState {
    /** 情绪词表：模型只能给这几个之一，其余夹回默认。 */
    public static final List<String> MOODS = List.of("元气", "平静", "困", "害羞", "别扭", "低落", "兴奋", "闹脾气");
    /** 敏感话题：这些话题上的回避方式要按 K2 轮换，不能每次同一个模板。 */
    public static final List<String> SENSITIVE = List.of(
            "父母", "父亲", "母亲", "派罗", "狗", "魔物", "键", "键丝带", "德鲁伊", "工房",
            "欲望", "是不是魔物", "半魔物", "死", "毒药", "其他线路", "琪比摩斯");
    /** 敏感话题的备选反应类型（每次抽一个，最近 3 次不得重复）。 */
    public static final List<String> REACTIONS = List.of(
            "含糊带过", "硬转话题", "用玩笑挡", "短促反问", "只给省略号", "漏一句真心再收回", "正面说一点点");
    /** 情绪 10 分钟无交互回落一档（每次交互都会把计时清零，靠 {@link #touch}）。 */
    public static final long MOOD_DECAY_MILLIS = TimeUnit.MINUTES.toMillis(10);
    /** 好感度衰减：24 小时不聊回落 1 点（最多回落到 50 以下不再降）。 */
    public static final long AFFINITY_DECAY_MILLIS = TimeUnit.HOURS.toMillis(24);

    private static volatile PersonaState cached;
    private static volatile Path cachedRoot;

    private final Path root, affinityFile, moodFile;
    private JsonObject affinity = new JsonObject(), mood = new JsonObject();

    private PersonaState(Path root) {
        this.root = root;
        this.affinityFile = root.resolve("data/affinity.json");
        this.moodFile = root.resolve("data/mood.json");
        this.affinity = read(affinityFile);
        this.mood = read(moodFile);
    }
    /** 按 bot.home 取单例（文件改动不需要重新载入：所有写入都经过这里）。 */
    public static PersonaState of(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        PersonaState state = cached;
        if (state == null || !normalized.equals(cachedRoot)) {
            state = new PersonaState(normalized);
            cached = state; cachedRoot = normalized;
        }
        return state;
    }
    /** 测试用：丢掉单例，强制重新读文件。 */
    public static synchronized void resetCache() { cached = null; cachedRoot = null; }

    private static JsonObject read(Path file) {
        try {
            if (!Files.isRegularFile(file)) return new JsonObject();
            JsonObject parsed = Json.parse(Files.readString(file));
            return parsed == null ? new JsonObject() : parsed;
        } catch (Exception error) {
            Log.warn("读取 " + file.getFileName() + " 失败，按默认值工作：" + cn.szu.bot.Bot.error(error));
            return new JsonObject();
        }
    }
    private synchronized void write(Path file, JsonObject data) {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Json.atomicWrite(file, data);
        } catch (Exception error) {
            Log.warn("写入 " + file.getFileName() + " 失败：" + cn.szu.bot.Bot.error(error));
        }
    }
    static String now() { return Instant.now().toString(); }

    // ---------------- 好感度（K3） ----------------
    private JsonObject entry(JsonObject data, String conversation, boolean create) {
        JsonElement value = data.get(conversation);
        if (value != null && value.isJsonObject()) return value.getAsJsonObject();
        if (!create) return null;
        JsonObject fresh = new JsonObject();
        data.add(conversation, fresh);
        return fresh;
    }
    /** 该会话当前好感度（0–100，默认 50）；顺带做长期不聊的缓慢回落。 */
    public synchronized int affinity(String conversation) {
        JsonObject row = entry(affinity, conversation, false);
        if (row == null) return 50;
        int score = clampScore(Json.num(row, "score", 50));
        String updatedAt = Json.str(row, "updatedAt", "");
        try {
            if (!updatedAt.isBlank()) {
                long idle = Duration.between(Instant.parse(updatedAt), Instant.now()).toMillis();
                int steps = (int) (idle / AFFINITY_DECAY_MILLIS);
                if (steps > 0 && score > 50) {
                    score = Math.max(50, score - steps);
                    row.addProperty("score", score);
                    row.addProperty("updatedAt", now());
                    write(affinityFile, affinity);
                }
            }
        } catch (Exception ignored) { }
        return score;
    }
    /** 调整好感度：夹到 0–100，返回新值。 */
    public synchronized int adjustAffinity(String conversation, int delta) {
        if (delta == 0) return affinity(conversation);
        JsonObject row = entry(affinity, conversation, true);
        int score = clampScore(Json.num(row, "score", 50) + Math.max(-3, Math.min(3, delta)));
        row.addProperty("score", score);
        row.addProperty("updatedAt", now());
        write(affinityFile, affinity);
        return score;
    }
    static int clampScore(int value) { return Math.max(0, Math.min(100, value)); }
    /** 分档：生疏 <35 / 熟悉 35–64 / 亲近 65–84 / 很亲近 ≥85。 */
    public static String tier(int score) {
        if (score < 35) return "生疏";
        if (score < 65) return "熟悉";
        if (score < 85) return "亲近";
        return "很亲近";
    }
    /** 分档对应的相处分寸（注入提示词用），只谈语气与可透露程度，不谈执行。 */
    public static String tierHint(int score) {
        if (score < 35) return "生疏：客气、话少一点，不主动开玩笑，敏感话题只含糊带过，不主动接近。";
        if (score < 65) return "熟悉：正常闲聊、能接玩笑，偶尔吐槽；敏感话题最多硬转话题或一句玩笑。";
        if (score < 85) return "亲近：可以主动多说一句、拿自己的小事打趣，敏感话题允许漏一句真心再收回。";
        return "很亲近：可以更放松、更爱闹，敏感话题允许正面说一点点（仍然克制、不倒苦水）。";
    }

    // ---------------- 情绪（K4） ----------------
    /** 取当前情绪（含 30 分钟无交互回落一档）。 */
    public synchronized Mood mood(String conversation) {
        JsonObject row = entry(mood, conversation, false);
        if (row == null) return new Mood("平静", 1, "", "");
        String name = Json.str(row, "mood", "平静");
        if (!MOODS.contains(name)) name = "平静";
        int intensity = Math.max(1, Math.min(3, Json.num(row, "intensity", 1)));
        String reason = Json.str(row, "reason", "");
        String updatedAt = Json.str(row, "updatedAt", "");
        try {
            if (!updatedAt.isBlank()) {
                long idle = Duration.between(Instant.parse(updatedAt), Instant.now()).toMillis();
                int steps = (int) (idle / MOOD_DECAY_MILLIS);
                if (steps > 0) {
                    int next = Math.max(1, intensity - steps);
                    if (next != intensity || !"平静".equals(name)) {
                        name = next <= 1 ? "平静" : name;
                        intensity = next;
                        row.addProperty("mood", name);
                        row.addProperty("intensity", intensity);
                        row.addProperty("updatedAt", now());
                        write(moodFile, mood);
                    }
                }
            }
        } catch (Exception ignored) { }
        return new Mood(name, intensity, reason, updatedAt);
    }
    /** 设置情绪：名字不在词表里就退回"平静"。 */
    public synchronized Mood setMood(String conversation, String name, int intensity, String reason) {
        String safeName = name == null || !MOODS.contains(name.strip()) ? "平静" : name.strip();
        JsonObject row = entry(mood, conversation, true);
        row.addProperty("mood", safeName);
        row.addProperty("intensity", Math.max(1, Math.min(3, intensity)));
        row.addProperty("reason", reason == null ? "" : reason.strip());
        row.addProperty("updatedAt", now());
        write(moodFile, mood);
        return new Mood(safeName, Math.max(1, Math.min(3, intensity)), reason == null ? "" : reason, now());
    }
    /**
     * 每次交互都调用：把"距上次交互"的计时清零（情绪本身不变）。
     * 衰减只看 updatedAt，所以只要连着聊就不会回落；停 10 分钟（{@link #MOOD_DECAY_MILLIS}）才回落一档。
     */
    public synchronized void touch(String conversation) {
        JsonObject row = entry(mood, conversation, false);
        if (row == null) return;
        row.addProperty("updatedAt", now());
        write(moodFile, mood);
    }
    /** 情绪对语气的提示（注入提示词）。 */
    public static String moodHint(Mood mood) {
        String base = switch (mood.mood()) {
            case "元气", "兴奋" -> "语气轻快、句末多用～或！、噱头多一点、响应快。";
            case "困" -> "语气慢、可以带省略号与拖长音～，句子更短，别硬撑长篇。";
            case "害羞" -> "先愣一下、否认或打岔，句子短，容易「…唔」「诶——？」。";
            case "别扭" -> "嘴硬、反嘴一句再漏半句真心，不要温柔鼓励式长句。";
            case "低落" -> "话少、语气淡，省略号多一点，但仍然**照做该做的事**（生图指令照样给全指令）。";
            case "闹脾气" -> "短促、带点脾气，可以用短断言（「不行哟！」），但**不许拒绝执行**，也不许骂人。";
            default -> "平稳、随和的日常语气。";
        };
        return "当前情绪：" + mood.mood() + "（强度 " + mood.intensity() + "）——" + base;
    }
    public record Mood(String mood, int intensity, String reason, String updatedAt) {}

    /** 真情流露档：稀有（同会话 30 分钟最多一次）。 */
    public static final long HEARTFELT_COOLDOWN_MILLIS = TimeUnit.MINUTES.toMillis(30);
    /** 敏感话题在 10 分钟内被第 3 次及以后提及 → 允许一次真情流露。 */
    public static final long SENSITIVE_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(10);
    /** 恶意追问（拿她父母/派罗/她的存在挖苦）→ 不流露，反而更封闭。 */
    private static final java.util.regex.Pattern MALICIOUS = java.util.regex.Pattern.compile(
            "(?s).*(你妈是不是你|你父母是不是你|被你弄死|被你害死|你就是个怪物|你才是魔物|你是个怪物|活该|自作自受|"
            + "你害死|你杀了|恶心死了|变态|有病吧你|你算什么).*");
    public static boolean malicious(String message) {
        return message != null && MALICIOUS.matcher(message).matches();
    }
    /** 这轮该用哪种反应：恶意追问 → 更封闭；反复提及/低落/亲近+认真关心 → 允许一次真情流露；否则按最近 3 次不重复轮换。 */
    public synchronized String decideReaction(String conversation, String message, int score, Mood mood, long nowMillis) {
        JsonObject row = entry(affinity, conversation, true);
        if (malicious(message)) { recordSensitiveTouch(row, nowMillis); return "更封闭"; }
        recordSensitiveTouch(row, nowMillis);
        int touches = recentTouches(row, nowMillis);
        boolean heartfeltAllowed = nowMillis - Json.num(row, "last_heartfelt", 0L) >= HEARTFELT_COOLDOWN_MILLIS;
        boolean lowMood = "低落".equals(mood.mood()) || "闹脾气".equals(mood.mood()) || "困".equals(mood.mood());
        boolean trusted = score >= 65;
        if (heartfeltAllowed && (touches >= 3 || lowMood || trusted)) {
            row.addProperty("last_heartfelt", nowMillis);
            write(affinityFile, affinity);
            recordReaction(conversation, "真情流露");
            return "真情流露";
        }
        String picked = nextReaction(conversation);
        // 选完立刻记账：这样连续几次敏感话题不会给出同一个回避模板。
        recordReaction(conversation, picked);
        return picked;
    }
    /** 记录一次敏感话题接触（只留窗口内的时刻）。 */
    private void recordSensitiveTouch(JsonObject row, long nowMillis) {
        JsonArray kept = new JsonArray();
        JsonElement value = row.get("sensitive_touches");
        if (value != null && value.isJsonArray())
            for (JsonElement item : value.getAsJsonArray())
                if (item != null && item.isJsonPrimitive() && nowMillis - item.getAsLong() <= SENSITIVE_WINDOW_MILLIS) kept.add(item.getAsLong());
        kept.add(nowMillis);
        row.add("sensitive_touches", kept);
        write(affinityFile, affinity);
    }
    private static int recentTouches(JsonObject row, long nowMillis) {
        JsonElement value = row.get("sensitive_touches");
        if (value == null || !value.isJsonArray()) return 0;
        int count = 0;
        for (JsonElement item : value.getAsJsonArray())
            if (item != null && item.isJsonPrimitive() && nowMillis - item.getAsLong() <= SENSITIVE_WINDOW_MILLIS) count++;
        return count;
    }

    /** 触发类型 + 它对情绪与好感度的作用（K4 触发表）。 */
    public record Trigger(String kind, int moodDelta, int affinityDelta, String mood) {}
    /** 同一触发 3 分钟内不重复累计，避免一句话抖到上限/下限。 */
    public static final long TRIGGER_COOLDOWN_MILLIS = TimeUnit.MINUTES.toMillis(3);

    private static final java.util.regex.Pattern INSULT = java.util.regex.Pattern.compile(
            "(?s).*(笨蛋|蠢|废物|没用|没用的工具|你就是个工具|只是个工具|机器人而已|滚|恶心|丑|胖|肥|平胸|飞机场|"
            + "去死|烦人|闭嘴|垃圾|讨厌你|低能|智障).*");
    private static final java.util.regex.Pattern PRAISE = java.util.regex.Pattern.compile(
            "(?s).*(可爱|好可爱|真可爱|厉害|好厉害|真厉害|做得好|干得好|真棒|好棒|了不起|漂亮|好看|温柔|可靠).*");
    private static final java.util.regex.Pattern CARE = java.util.regex.Pattern.compile(
            "(?s).*(吃饭|吃了吗|休息|歇会|歇歇|别熬夜|早点睡|累不累|别硬撑|多穿|注意身体|喝点水|辛苦了).*");
    private static final java.util.regex.Pattern THANKS = java.util.regex.Pattern.compile(
            "(?s).*(谢谢|多谢|感谢|你真好|麻烦你了|还记得|你说得对).*");
    private static final java.util.regex.Pattern COMMAND = java.util.regex.Pattern.compile(
            "(?s).*(生成|出图|画一张|来一张|画 ?[0-9一二三四五六七八九十两]+ ?张|\\.gen|\\.infix|提示词|样式|LoRA|尺寸|步数|CFG|种子|采样器|加载).*");
    private static final java.util.regex.Pattern TEASE = java.util.regex.Pattern.compile(
            "(?s).*(哈哈|嘿嘿|逗你|开玩笑|骗你的|随便说说|这么较真|你急什么|小气).*");
    /**
     * 按 K4 触发表判断这条消息属于哪一类；null 表示中性（不改状态）。
     * **被下生图指令是工作：情绪中性、不降**——这条优先级最高。
     */
    public static Trigger triggerOf(String message) {
        if (message == null || message.isBlank()) return null;
        if (INSULT.matcher(message).matches()) return new Trigger("被侮辱", -2, -2, "别扭");
        if (COMMAND.matcher(message).matches()) return null;
        if (sensitive(message)) return new Trigger("敏感话题", -2, 0, "低落");
        if (PRAISE.matcher(message).matches()) return new Trigger("被夸奖", 2, 1, "害羞");
        if (CARE.matcher(message).matches()) return new Trigger("被关心", 1, 1, "平静");
        if (THANKS.matcher(message).matches()) return new Trigger("被感谢/被认真对待", 1, 1, "元气");
        if (TEASE.matcher(message).matches()) return new Trigger("被善意逗", 1, 0, "元气");
        return null;
    }
    /** 触发冷却：同一类 3 分钟内只累计一次。 */
    public synchronized boolean triggerAllowed(String conversation, String kind, long nowMillis) {
        JsonObject row = entry(affinity, conversation, true);
        JsonObject stamps = row.has("last_triggers") && row.get("last_triggers").isJsonObject()
                ? row.getAsJsonObject("last_triggers") : new JsonObject();
        long last = Json.num(stamps, kind, 0L);
        if (last > 0 && nowMillis - last < TRIGGER_COOLDOWN_MILLIS) return false;
        stamps.addProperty(kind, nowMillis);
        row.add("last_triggers", stamps);
        write(affinityFile, affinity);
        return true;
    }
    /** 落一次触发：情绪与好感度同时变（好感度夹在 0–100、强度夹在 1–3）。 */
    public synchronized void applyTrigger(String conversation, Trigger trigger) {
        if (trigger == null) return;
        int score = clampScore(affinity(conversation) + trigger.affinityDelta());
        JsonObject row = entry(affinity, conversation, true);
        row.addProperty("score", score);
        row.addProperty("updatedAt", now());
        write(affinityFile, affinity);
        if (trigger.moodDelta() != 0) {
            Mood current = mood(conversation);
            int next = Math.max(1, Math.min(3, current.intensity() + trigger.moodDelta()));
            String name = trigger.moodDelta() < 0 ? trigger.mood()
                    : (current.mood().equals("平静") ? trigger.mood() : current.mood());
            setMood(conversation, name, next, trigger.kind());
        }
    }

    // ---------------- 敏感话题反应轮换（K2） ----------------
    /** 该消息是不是敏感话题。 */
    public static boolean sensitive(String message) {
        if (message == null || message.isBlank()) return false;
        for (String word : SENSITIVE) if (message.contains(word)) return true;
        return false;
    }
    /** 挑一个"最近 3 次没用过"的反应类型（全用过时回到最少使用的那一个）。 */
    public synchronized String nextReaction(String conversation) {
        JsonObject row = entry(affinity, conversation, true);
        List<String> recent = recentReactions(row);
        for (String reaction : REACTIONS) if (!recent.contains(reaction)) return reaction;
        // 都出现过：用最久没用过的那个（recent 是最近在前的顺序）。
        String last = recent.isEmpty() ? "" : recent.get(recent.size() - 1);
        return REACTIONS.contains(last) ? REACTIONS.get((REACTIONS.indexOf(last) + 1) % REACTIONS.size()) : REACTIONS.get(0);
    }
    /** 记录这次用了哪种反应（只保留最近 3 次）。 */
    public synchronized void recordReaction(String conversation, String reaction) {
        if (reaction == null || reaction.isBlank()) return;
        JsonObject row = entry(affinity, conversation, true);
        List<String> recent = recentReactions(row);
        recent.add(0, reaction);
        while (recent.size() > 3) recent.remove(recent.size() - 1);
        JsonArray array = new JsonArray();
        for (String item : recent) array.add(item);
        row.add("recent_reactions", array);
        write(affinityFile, affinity);
    }
    /** 最近 3 次用过的反应（最近在前）。 */
    public synchronized List<String> recentReactions(String conversation) {
        return recentReactions(entry(affinity, conversation, false));
    }
    private static List<String> recentReactions(JsonObject row) {
        List<String> result = new ArrayList<>();
        if (row == null) return result;
        JsonElement value = row.get("recent_reactions");
        if (value == null || !value.isJsonArray()) return result;
        for (JsonElement item : value.getAsJsonArray())
            if (item != null && item.isJsonPrimitive()) result.add(item.getAsString());
        return result;
    }
    /**
     * 反应类型的**开头句式范例**（L3）：只给"说明"不够——实测模型会把所有回避都写成「………」，
     * 给出可直接照抄的起头句，差异才看得出来。
     */
    public static String reactionHint(String reaction) {
        return switch (reaction == null ? "" : reaction) {
            case "含糊带过" -> "这次用含糊带过，**开头就照这个句式起**：「…嗯——」「是这样啊。」——一句半截的应和就停住。";
            case "硬转话题" -> "这次直接岔开，**开头照这个句式起**：「对了，土有点干了。」「说起来特卖今天就到。」——拐到她自己的小东西上。";
            case "用玩笑挡" -> "这次用一个小噱头挡，**开头照这个句式起**：「因为我叫小鸟嘛。」「这个嘛——不告诉你了。」——不解释笑点。";
            case "短促反问" -> "这次只用一句短反问，**照这个句式起**：「诶——？」「什么？」——把话头推回去，别的什么都不说。";
            case "只给省略号" -> "这次只给省略号：「………」——一个字都不多说。";
            case "漏一句真心再收回" -> "这次**开头漏一句真心再立刻收回**：「…其实我也想过。……算了，当我没说。」——只一句，然后拉回轻快。";
            case "正面说一点点" -> "这次可以正面说一点点，**照这个句式起**：「……嗯，是有一点。」——一句就停，不展开、不倒苦水。";
            case "真情流露" -> "这次允许一次**真情流露**（稀有档）：整条 ≤60 字、2～3 个短句、断句碎、`……` 与 `——` 交织、"
                    + "允许一点重复；**开头照这个句式起**：「……其实我一直——算了。」「…不要问了…」——只漏一句真心就立刻收回，"
                    + "**不倾倒剧情**、不解释设定、不写长篇独白，内容只能来自你既有的事实；说完把气氛拉回轻快（「……啊，没什么。」）。";
            case "更封闭" -> "这次对方是恶意追问：**开头只给**「………」或一句冷的短句（「……你想说什么。」），更短、更戒备，不流露、不解释、不争吵。";
            default -> "";
        };
    }
    /** 文件路径（测试与报告用）。 */
    public Path affinityFile() { return affinityFile; }
    public Path moodFile() { return moodFile; }
    public Path root() { return root; }
}

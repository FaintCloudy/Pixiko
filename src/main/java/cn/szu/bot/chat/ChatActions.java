package cn.szu.bot.chat;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import cn.szu.bot.Bot;
import cn.szu.bot.Settings;

/** Model output is data; only the existing bot command language can be dispatched. */
public final class ChatActions {
    /**
     * interest 是模型对"这条消息与本会话话题/设定有多相关"的 0–100 判断，现在只用于"要不要继续这次对话"的门槛与日志；
     * join 是模型对"被点名/被回复之后，要不要把这个人拉进 30 分钟对话窗口（继续聊下去）"的判断
     * （join=true 才进入对话窗口；join=false 表示只答这一句，之后不 @ 不再回），joinGiven 表示模型确实给了这个字段
     * （没给时上层退回按 interest 门槛的保守兜底，绝不掷随机数）。
     */
    public record Plan(String reply, List<String> commands, String searchQuery, int interest,
                       boolean join, boolean joinGiven, int affinityDelta, String affinityReason,
                       String mood, int moodIntensity) {
        public Plan(String reply,List<String> commands) { this(reply,commands,"",100); }
        public Plan(String reply,List<String> commands,String searchQuery) { this(reply,commands,searchQuery,100); }
        public Plan(String reply,List<String> commands,String searchQuery,int interest) {
            this(reply,commands,searchQuery,interest,false,false);
        }
        public Plan(String reply,List<String> commands,String searchQuery,int interest,
                    boolean join, boolean joinGiven) {
            this(reply,commands,searchQuery,interest,join,joinGiven,0,"","",0);
        }
        public Plan { commands = List.copyOf(commands); searchQuery=searchQuery==null ? "" : searchQuery.strip();
                interest=Math.max(0,Math.min(100,interest));
                affinityDelta=Math.max(-3,Math.min(3,affinityDelta));
                affinityReason=affinityReason==null ? "" : affinityReason.strip();
                mood=mood==null ? "" : mood.strip(); moodIntensity=Math.max(0,Math.min(3,moodIntensity)); }
        /** 只换回复/指令/检索词，保留 join 与好感度/情绪这些"人格状态"字段（K3/K4 不能被派生计划丢掉）。 */
        public Plan copy(String newReply, List<String> newCommands, String newQuery) {
            return new Plan(newReply, newCommands, newQuery, interest, join, joinGiven,
                    affinityDelta, affinityReason, mood, moodIntensity);
        }
    }
    private static final Pattern COMMAND = Pattern.compile(
        "^[./](?:help|yh|liv|get|settings|chat|admin|char|batch|jrlp|强娶|离婚|sampler|style|size|steps|cfg|seed|model|promptR|prompt|preset|function|lora|gen|rg|imgcnt|imgmode|mode|vae|usage|map|progen|infix|progress|进度|sd|affinity)(?:\\s+[\\s\\S]*)?$",
        Pattern.CASE_INSENSITIVE);
    /**
     * Models slip a zero-width space, a word joiner or a full-width separator in front of a command,
     * and sometimes echo the「[引用]」marker from current_message. All of those are the same command.
     */
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200F\\u2060\\uFEFF\\u00A0\\u180E\\u202A-\\u202E]");
    static String sanitizeCommand(String raw) {
        if (raw == null) return null;
        String text = INVISIBLE.matcher(raw).replaceAll("").strip();
        text = text.replaceFirst("^[\\[【]\\s*引用\\s*[\\]】]\\s*", "");
        if (text.startsWith("．")) text = "." + text.substring(1);
        else if (text.startsWith("／")) text = "/" + text.substring(1);
        return text.strip();
    }
    public static void validate(List<String> commands) {
        if (commands.size() > 8) throw new IllegalArgumentException("一次聊天最多安排 8 个指令，请拆分要求。");
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            if (command == null || command.length() > 22000 || !command.equals(command.strip())
                    || command.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')
                    || !COMMAND.matcher(command).matches())
                throw new IllegalArgumentException("聊天返回了不支持的指令，未执行。");
        }
    }
    /** 回复里声称"改了画面/提示词"的说法。 */
    private static final java.util.regex.Pattern PICTURE_CLAIM = java.util.regex.Pattern.compile(
        "改成|改为|换成|换了|替换|调整为|调成|加上|加个|修改了|已经改|背景|场景|服装|服饰|衣着|光照|光线|姿态|姿势");
    /** 回复里声称"删了/改了名/保存了/加载了"的说法：这类请求由管理类指令落实，不需要 .infix。 */
    private static final java.util.regex.Pattern MANAGEMENT_CLAIM = java.util.regex.Pattern.compile(
        "删掉|删除|去掉|移除|改名|重命名|清空|保存|加载|载入|下载");
    /**
     * 真正会改动画面/提示词**内容**的指令（新增或改写）。
     *
     * <p>必须把 `.prompt remove/clear/drop` 排除在外：实测发生过「回复说要加入多视角、剖面图、交合处特写，
     * commands 里却只有一条 `.prompt remove from side`」——旧的写法把任何 `.prompt …` 都当成"改画面的指令"，
     * 于是"声称改了画面"的守卫被一条删除指令骗过，用户的净效果是**被删了一项、要加的一项都没加**。
     */
    private static final java.util.regex.Pattern PICTURE_COMMAND = java.util.regex.Pattern.compile(
        "(?is)^[./]infix(?:\\s|$)"
        + "|(?is)^[./]prompt(?:R)?\\s+(?:add|set)(?:\\s|$)"
        + "|(?is)^[./]style\\s+load(?:\\s|$)|(?is)^[./]lora\\s+load(?:\\s|$)");
    /** 只删不增的指令：`要加就绝不能删`的修补会针对它们下手。 */
    static boolean removalCommand(String command) {
        if (command == null) return false;
        String text = command.strip();
        return text.matches("(?is)^[./]prompt(?:R)?\\s+(?:remove|clear|drop)(?:\\s+.*)?$");
    }
    /** 落实"删除/改名/保存/下载"这类请求的指令。 */
    private static final java.util.regex.Pattern MANAGEMENT_COMMAND = java.util.regex.Pattern.compile(
        "(?is)^[./](?:style|lora|function)\\s+(?:delete|remove|rename|save|overwrite|clear)(?:\\s|$)"
        + "|(?is)^[./]lora\\s+download(?:\\s|$)"
        + "|(?is)^[./](?:size|steps|cfg|seed|sampler|model|preset|imgcnt)\\s+(?:set|clear)(?:\\s|$)");
    /** 真的会下载东西的指令：只有它才能兑现回复里那句"下载了"。 */
    private static final java.util.regex.Pattern DOWNLOAD_COMMAND = java.util.regex.Pattern.compile(
        "(?is)^[./]lora\\s+download(?:\\s|$)");
    /** 回复正文里声称"下载"的说法。 */
    private static final java.util.regex.Pattern DOWNLOAD_CLAIM = java.util.regex.Pattern.compile("下载");
    /**
     * True when the reply claims something the plan does not actually do. A picture claim needs a picture
     * command (.infix / .prompt / style or LoRA load); a "deleted / renamed / saved" claim is satisfied by a
     * management command, so "好，删掉列表里第 12 到第 16 项" with .style delete #12-#16 is honest, while a
     * claimed scene change with only a delete is a lie.
     *
     * <p><b>"下载"要单独判（修误判）</b>：以前只要有任意管理类指令（例如一条无关的 .style delete）就
     * 算"下载"这句兑现了；更糟的是用户只是抱怨"下载速度好慢"，回复照抄一句"下载"也要被当成"声称做了事"。
     * 现在分两条硬判据：① 声称"下载"必须有 {@code .lora download}；② 只有用户这次**真的给了下载目标**
     * （链接或 #编号，见 {@code DeepSeekPrompts.requiresCommands}）时，这句"下载"才算承诺——没有目标的
     * 闲聊里提到"下载"不是指令，也不该被当成空头承诺去重试。
     */
    private static final java.util.regex.Pattern REBUKE = java.util.regex.Pattern.compile(
        "不正经|禁止|免谈|别闹|不行|不可以|不许|NG|拒绝|适可而止|注意分寸");
    /** A refusal or rebuke: correct when the bot is addressed, but it must never be used to chime in. */
    public static boolean isRebuke(String reply) { return reply != null && REBUKE.matcher(reply).find(); }
    public static boolean promisesUnappliedEdit(String reply, List<String> commands) {
        return promisesUnappliedEdit(reply, commands, reply);
    }
    /**
     * 同 {@link #promisesUnappliedEdit(String, List)}，但"这次请求到底要什么"由 {@code request} 给出
     * （模型回复不能自证：回复里的"下载"只有用户**这次真的给了下载目标**时才算承诺）。
     */
    public static boolean promisesUnappliedEdit(String reply, List<String> commands, String request) {
        if (reply == null || reply.isBlank()) return false;
        boolean picture = PICTURE_CLAIM.matcher(reply).find(), management = MANAGEMENT_CLAIM.matcher(reply).find();
        if (!picture && !management) return false;
        boolean download = DOWNLOAD_CLAIM.matcher(reply).find();
        // 没有下载目标的闲聊（「下载速度好慢」「这个模型不好下载」）：回复里的"下载"不是承诺，不拦、不重试。
        if (download && !DeepSeekPrompts.downloadTargeted(request)) return false;
        boolean pictureCommand = false, managementCommand = false, downloadCommand = false;
        for (String command : commands) {
            String text = command == null ? "" : command.strip();
            if (PICTURE_COMMAND.matcher(text).find()) pictureCommand = true;
            if (MANAGEMENT_COMMAND.matcher(text).find()) managementCommand = true;
            if (DOWNLOAD_COMMAND.matcher(text).find()) downloadCommand = true;
        }
        if (picture && !pictureCommand) return true;
        // 声称"下载"只有 .lora download 兑现，别的管理类指令（删除/改名/保存）都不算。
        if (download) return !downloadCommand;
        return management && !managementCommand && !pictureCommand;
    }
    private static final java.util.regex.Pattern DONE_CLAIM = java.util.regex.Pattern.compile(
        "已(?:经)?(?:加载|载入|保存|删除|移除|改名|生成|出图|做成|完成|换好|改好|做好|设置好|处理完)"
        + "|加载好了|载入好了|改好了|换好了|删掉了|保存好了|出好了|搞定了");
    /**
     * 修 bug 4（对齐 TS 版"将来时承诺也能答应不做"）：将来时承诺 + 动作。
     * 请求里没有 requiresCommands 的动词时（例如"就用这个样式吧"），模型回一句"好，这就加载"却给出
     * commands=[]，DONE_CLAIM 只认完成态，三道守卫全不命中，一句空话就这样发出去了。
     * 现在"这就/马上/稍等 + 动作"也算成"声称要做但什么都没做"。
     */
    private static final java.util.regex.Pattern FUTURE_CLAIM = java.util.regex.Pattern.compile(
        "(?:这就|马上|立刻|我这就|这就去|稍等|稍后)[^。！？\\n]{0,12}(?:加载|载入|应用|生成|出图|改|换|删|下载|设置|保存|查询|查看)");
    /**
     * True when the reply announces a finished action while the plan contains no command at all: nothing was
     * or will be executed, so the sentence is simply untrue. The plan is retried instead of being sent.
     */
    public static boolean claimsUnverifiedExecution(String reply, List<String> commands) {
        if (reply == null || reply.isBlank()) return false;
        if (commands != null && !commands.isEmpty()) return false;
        return DONE_CLAIM.matcher(reply).find() || FUTURE_CLAIM.matcher(reply).find();
    }
    public static boolean asynchronous(String command) {
        return command.matches("(?is)^[./](?:progen|infix)(?:\\s+.*)?$")
                || command.matches("(?is)^[./]chat\\s+infix(?:\\s+.*)?$")
                || command.matches("(?is)^[./]lora\\s+(?:query|list|load|download)(?:\\s+.*)?$");
    }
    public static Plan parse(String content) throws IOException {
        try {
            // Models occasionally wrap the object in a Markdown fence or chatty text; strip a fence first.
            String cleaned = content == null ? "" : content.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceFirst("(?s)^```[a-zA-Z]*\\s*", "").replaceFirst("(?s)\\s*```$", "").strip();
            }
            JsonObject output = new GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(cleaned, JsonObject.class);
            if (output == null || !output.has("reply") || !output.get("reply").isJsonPrimitive()
                    || !output.get("reply").getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            String reply = output.get("reply").getAsString().strip();
            if (reply.isBlank() || reply.length() > 4000) throw new IllegalArgumentException();
            JsonArray items = output.getAsJsonArray("commands");
            if (items == null) items = new JsonArray();
            List<String> commands = new ArrayList<>();
            for (JsonElement item : items) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
                commands.add(sanitizeCommand(item.getAsString()));
            }
            // "execute" may be omitted: having commands is what implies an action.
            boolean execute = !commands.isEmpty();
            if (output.has("execute") && !output.get("execute").isJsonNull()) {
                if (!output.get("execute").isJsonPrimitive() || !output.get("execute").getAsJsonPrimitive().isBoolean())
                    throw new IllegalArgumentException();
                execute = output.get("execute").getAsBoolean();
            }
            if (execute && commands.isEmpty()) execute = false;   // nothing to run: treat as plain chat
            String searchQuery="";
            if(output.has("search_query") && !output.get("search_query").isJsonNull()) {
                if(!output.get("search_query").isJsonPrimitive() || !output.get("search_query").getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
                searchQuery=output.get("search_query").getAsString().strip();
                if(searchQuery.length()>300) throw new IllegalArgumentException();
            }
            if(!searchQuery.isEmpty() && !commands.isEmpty()) throw new IllegalArgumentException();
            int interest=100;
            if(output.has("interest") && !output.get("interest").isJsonNull()) {
                if(!output.get("interest").isJsonPrimitive() || !output.get("interest").getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException();
                interest=output.get("interest").getAsInt();
                if(interest<0 || interest>100) throw new IllegalArgumentException();
            }
            // 是否延续这次对话由模型按上下文判断（被点名/被回复之后要不要进 30 分钟对话窗口）；
            // 老提示词没给这个字段时 joinGiven=false，上层走保守兜底。
            // wake_adjust 是已彻底废弃的旧字段：模型若仍返回它，这里不解析、不报错，一律静默忽略。
            boolean join=false, joinGiven=false;
            if(output.has("join") && !output.get("join").isJsonNull()) {
                if(!output.get("join").isJsonPrimitive() || !output.get("join").getAsJsonPrimitive().isBoolean())
                    throw new IllegalArgumentException();
                join=output.get("join").getAsBoolean(); joinGiven=true;
            }
            validate(commands);
            // K3/K4：好感度调整（夹到 −3..+3）与情绪；非法值一律夹紧而不是整条计划作废。
            int affinityDelta=0;
            if(output.has("affinity_delta") && !output.get("affinity_delta").isJsonNull()
                    && output.get("affinity_delta").isJsonPrimitive() && output.get("affinity_delta").getAsJsonPrimitive().isNumber())
                affinityDelta=output.get("affinity_delta").getAsInt();
            String affinityReason="";
            if(output.has("affinity_reason") && !output.get("affinity_reason").isJsonNull()
                    && output.get("affinity_reason").isJsonPrimitive()) affinityReason=output.get("affinity_reason").getAsString();
            String mood="";
            if(output.has("mood") && !output.get("mood").isJsonNull() && output.get("mood").isJsonPrimitive())
                mood=output.get("mood").getAsString();
            int moodIntensity=0;
            if(output.has("mood_intensity") && !output.get("mood_intensity").isJsonNull()
                    && output.get("mood_intensity").isJsonPrimitive() && output.get("mood_intensity").getAsJsonPrimitive().isNumber())
                moodIntensity=output.get("mood_intensity").getAsInt();
            return new Plan(reply, commands, searchQuery, interest, join, joinGiven,
                    affinityDelta, affinityReason, mood, moodIntensity);
        } catch (Exception e) { throw new IOException("DeepSeek 返回的聊天操作无效，本次未执行指令。"); }
    }
}

package cn.szu.bot.chat;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import cn.szu.bot.Bot;
import cn.szu.bot.Json;
import cn.szu.bot.Log;
import cn.szu.bot.Maps;
import cn.szu.bot.Settings;

/** Conversation-local context, bounded work, a rolling minute speech budget and an interest-based reply gate. */
public final class ChatService implements AutoCloseable {
    public interface Generator { String reply(String personality, JsonArray history, String message) throws Exception; }
    public interface Planner {
        ChatActions.Plan plan(String personality, JsonArray history, String message, JsonObject choices, JsonObject speaker) throws Exception;
        /**
         * 旁听（只为拿 join）的那次调用：默认与 {@link #plan} 相同；真实实现只给极简语料提示，
         * 不注入整段对白——否则群里每条无关消息都要多花一大笔 token。
         */
        default ChatActions.Plan planBrief(String personality, JsonArray history, String message, JsonObject choices, JsonObject speaker) throws Exception {
            return plan(personality, history, message, choices, speaker);
        }
    }
    public interface Actions { void execute(JsonObject event, List<String> commands, JsonObject choices) throws Exception; }
    public interface Context { JsonObject choices(JsonObject event); }
    private record Input(JsonObject event, String text, long revision, boolean respond, boolean explicit, boolean continuation, boolean candidate) {}
    private static final class Session {
        final String key;
        final JsonArray history = new JsonArray();
        final Deque<Long> requested = new ArrayDeque<>(), spoken = new ArrayDeque<>();
        final Deque<Input> pending = new ArrayDeque<>();
        final LinkedHashMap<String,Long> activeUsers = new LinkedHashMap<>(16, .75f, true);
        boolean busy;
        int drift;
        long lastChime;
        /** The last unsolicited aside we sent and who it answered: it must not become a thread by itself. */
        String lastChimeReply;
        String chimeTarget;
        long lastMessage;
        Session(String key) { this.key = key; }
    }
    private final Settings settings; private final Bot.Sender sender; private final Planner planner; private final Actions actions; private final Context context; private final LongSupplier clock;
    private final Map<String,Session> sessions = new LinkedHashMap<>(32, .75f, true);
    /** Dedicated serial chat worker. SD generation has its own executor in Bot. */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), task -> { Thread thread=new Thread(task,"pixiko-chat"); thread.setDaemon(true); return thread; });
    private boolean closed; private long revision;
    public ChatService(Settings settings, Bot.Sender sender, Actions actions, Context context) {
        // The conversation channel only: its own model, thinking switch and API key (chat_api), separate
        // from the image-prompt channel (progen), so ".chat" never touches prompt rewriting or generation.
        this(settings, sender, new Planner() {
            @Override public ChatActions.Plan plan(String personality, JsonArray history, String message, JsonObject choices, JsonObject speaker) throws Exception {
                // 真正要生成回复的那一次：注入整段原作对白（≤2000 字）。
                return DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.CHAT)
                        .chatPlan(personality, history, message, choices, speaker,
                                KotoriCorpus.referenceFor(settings, message, history == null ? 0 : history.size() / 2));
            }
            @Override public ChatActions.Plan planBrief(String personality, JsonArray history, String message, JsonObject choices, JsonObject speaker) throws Exception {
                // 旁听只为拿 join：只给极简味道提示，不注入整段。
                return DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.CHAT)
                        .chatPlan(personality, history, message, choices, speaker, KotoriCorpus.briefFor(settings, message));
            }
        }, actions, context, System::nanoTime);
    }
    public ChatService(Settings settings, Bot.Sender sender, Generator generator, LongSupplier clock) {
        this(settings, sender, (personality, history, message, choices, speaker) -> new ChatActions.Plan(generator.reply(personality, history, message), List.of()),
                (event, commands, choices) -> {}, event -> new JsonObject(), clock);
    }
    public ChatService(Settings settings, Bot.Sender sender, Planner planner, Actions actions, Context context, LongSupplier clock) {
        this.settings=settings;this.sender=sender;this.planner=planner;this.actions=actions;this.context=context;this.clock=clock;
    }
    /** Stable per-conversation identity shared by the chat switch and the chat sessions. */
    public static String conversationKey(JsonObject event) {
        boolean group="group".equals(Json.str(event,"message_type",""));
        return Json.str(event,"self_id","") + ":" + Json.str(event,"message_type","") + ":"
                + Json.str(event, group ? "group_id" : "user_id", "");
    }
    /** Invalidates every session: the global switch or the personality changed. */
    public synchronized void changed(boolean clearHistory) {
        revision++;
        for (Session session : sessions.values()) {
            session.pending.clear();
            session.requested.clear();
            session.activeUsers.clear();
            if (clearHistory) while (!session.history.isEmpty()) session.history.remove(0);
        }
    }
    /** Invalidates one conversation only; other sessions keep their queued work. */
    public synchronized void changed(String conversationKey, boolean clearHistory) {
        Session session=sessions.get(conversationKey);
        if (session==null) return;
        session.pending.clear(); session.requested.clear(); session.spoken.clear(); session.activeUsers.clear();
        if (clearHistory) while (!session.history.isEmpty()) session.history.remove(0);
    }
    private void expire(Deque<Long> times, long now) { while (!times.isEmpty() && now-times.peekFirst() >= TimeUnit.MINUTES.toNanos(1)) times.removeFirst(); }
    private String user(JsonObject event) { return Json.str(event,"user_id",""); }
    public static JsonObject speaker(JsonObject event) {
        JsonObject result=new JsonObject();String id=Json.str(event,"user_id","");result.addProperty("id",id);
        JsonObject sender=Json.obj(event,"sender");String name=Json.str(sender,"card","").strip();
        if(name.isBlank()) name=Json.str(sender,"nickname","").strip();
        name=name.replaceAll("[\\p{Cntrl}\\p{Cf}]"," ").replaceAll("\\s+"," ").strip();
        if(name.length()>100) name=name.substring(0,100);
        if(!name.isBlank()) result.addProperty("display_name",name);
        return result;
    }
    public static String userMessage(JsonObject event,String text) {
        JsonObject message=new JsonObject();message.add("speaker",speaker(event));message.addProperty("message",text);return message.toString();
    }
    /** The enforced role of one speaker: owner, admin or an ordinary user. */
    public String roleOf(JsonObject event) {
        String user=Json.str(event,"user_id","");
        return settings.isOwner(user) ? "owner" : settings.isAdmin(user) ? "admin" : "user";
    }
    /** Speaker identity plus the role the program enforces, so tone can adapt without overreaching. */
    private JsonObject speakerWithRole(JsonObject event) {
        JsonObject result=speaker(event);
        result.addProperty("role",roleOf(event));
        return result;
    }
    /** History entries carry the role too, so the model knows who it agreed with earlier. */
    private String historyMessage(JsonObject event,String text) {
        JsonObject message=new JsonObject();message.add("speaker",speakerWithRole(event));message.addProperty("message",text);
        return message.toString();
    }
    public static boolean addressed(JsonObject event, String text) {
        if (!"group".equals(Json.str(event,"message_type",""))) return true;
        if (text.contains("小鸟") || text.contains("小鳥") || text.contains("ことり")
                || java.util.regex.Pattern.compile("(?i)(?<![a-z])kotori(?![a-z])").matcher(text).find()) return true;
        String self=Json.str(event,"self_id",""); if(self.isBlank()) return false;
        JsonElement message=event.get("message");
        if(message!=null && message.isJsonArray()) {
            for(JsonElement item:message.getAsJsonArray()) if(item.isJsonObject()) {
                JsonObject segment=item.getAsJsonObject();
                if("at".equals(Json.str(segment,"type","")) && self.equals(Json.str(Json.obj(segment,"data"),"qq",""))) return true;
            }
            return false;
        }
        String raw=message!=null && message.isJsonPrimitive() ? message.getAsString() : Json.str(event,"raw_message","");
        return java.util.regex.Pattern.compile("\\[CQ:at,qq="+java.util.regex.Pattern.quote(self)+"(?:,[^\\]]*)?\\]").matcher(raw).find();
    }
    /**
     * Character-bigram overlap between the new message and the last few user messages. Below the threshold
     * the subject has visibly changed, so the old window no longer applies.
     */
    static boolean topicShifted(Session session, String text) {
        if (session == null || text == null || text.strip().length() < 6) return false;
        java.util.Set<String> fresh = bigrams(text);
        if (fresh.isEmpty()) return false;
        java.util.Set<String> recent = new java.util.LinkedHashSet<>();
        int seen = 0;
        for (int index = session.history.size() - 1; index >= 0 && seen < 4; index--) {
            JsonElement element = session.history.get(index);
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            if (!"user".equals(Json.str(entry, "role", ""))) continue;
            recent.addAll(bigrams(Json.str(entry, "content", "")));
            seen++;
        }
        if (recent.isEmpty()) return false;
        int shared = 0;
        for (String gram : fresh) if (recent.contains(gram)) shared++;
        double overlap = (double) shared / fresh.size();
        return overlap < 0.05;
    }
    /** True when a message clearly picks up the subject of our last sentence (shared wording). */
    static boolean overlaps(String previous, String text) {
        if (previous == null || previous.isBlank() || text == null || text.isBlank()) return false;
        java.util.Set<String> before = bigrams(previous), after = bigrams(text);
        if (before.isEmpty() || after.isEmpty()) return false;
        int shared = 0;
        for (String gram : after) if (before.contains(gram)) shared++;
        return (double) shared / after.size() >= 0.08;
    }
    private static java.util.Set<String> bigrams(String text) {
        java.util.Set<String> grams = new java.util.LinkedHashSet<>();
        String clean = text.replaceAll("\\[引用\\][^\\n]*", "").replaceAll("\\s+", "");
        for (int index = 0; index + 1 < clean.length(); index++) grams.add(clean.substring(index, index + 2));
        return grams;
    }
    public synchronized void accept(JsonObject event, String text) {
        if (closed || text.isBlank() || text.length()>8000) return;
        String key=conversationKey(event);
        if (!settings.chatEnabled(key)) { Log.info("聊天未启用（"+key+"），忽略消息："+Log.text(text)); return; }
        if (settings.chatFrequency() <= 0) { Log.info("聊天已设为静默（frequency=0），忽略消息："+Log.text(text)); return; }
        boolean group="group".equals(Json.str(event,"message_type",""));
        // 机器自己发出的消息（上/下线播报、回执、图片说明…）绝不能当成用户消息再规划一遍：
        // 实测播报里含「.help」会被当成指令，机器人于是在群里自己回自己。
        String senderId=user(event), selfId=Json.str(event,"self_id","");
        if (!senderId.isBlank() && !selfId.isBlank() && senderId.equals(selfId)) {
            Log.info("忽略自己发出的消息（"+conversationKey(event)+"）："+Log.text(text)); return;
        }
        Session session=sessions.get(key); long now=clock.getAsLong(); String user=user(event);
        boolean explicit=addressed(event,text);
        long window=TimeUnit.SECONDS.toNanos(settings.chatContextSeconds());
        // An aside we volunteered does not start a conversation: unless the person picks up our subject (or
        // someone addresses us), leave the window on the very next message instead of pretending to be in it.
        if (session != null && session.lastChimeReply != null) {
            boolean answered = explicit || (user.equals(session.chimeTarget) && overlaps(session.lastChimeReply, text));
            if (!answered) {
                session.activeUsers.clear();
                Log.info("插话后未获回应（" + key + "）：本条与刚才的话题无关，立即离开对话窗口");
            }
            session.lastChimeReply = null;
            session.chimeTarget = null;
        }
        boolean continuation=group && session!=null && session.activeUsers.containsKey(user)
                && now-session.activeUsers.get(user)<window;
        if(session!=null) session.lastMessage=now;
        boolean respond=!group || explicit || continuation;
        if(session==null) {
            if(sessions.size()>=256) {
                var iterator=sessions.entrySet().iterator(); boolean removed=false;
                while(iterator.hasNext()) { Session candidate=iterator.next().getValue(); if(!candidate.busy && candidate.pending.isEmpty()) { iterator.remove(); removed=true; break; } }
                if(!removed) return;
            }
            session=new Session(key); sessions.put(key,session);
        }
        if(respond) {
            // 被点名/续话的消息登记为对话对象；被动旁听的普通群消息不登记，
            // 否则会把别人的对话当成自己进行中的对话（那一条由规划后的 join 判定再决定要不要插话）。
            if(explicit || continuation) session.activeUsers.put(user,now);
            while(session.activeUsers.size()>64) session.activeUsers.remove(session.activeUsers.keySet().iterator().next());
            expire(session.requested,now); expire(session.spoken,now);
            if(session.requested.size()>=settings.chatFrequency() || session.spoken.size()>=settings.chatFrequency()) respond=false;
        }
        // N1（owner 2026-09-27 折中）：窗口外、未被点名的消息**允许规划**，但只允许"自然接一句"：
        //   · 模型给出 join=true 且话题接得上（相关度下限、冷却、每分钟上限、第三人称门槛都照旧）
        //   · **一旦计划里出现任何 commands → 一律不回复、不执行**（防陌生人用自然语言驱动提示词）
        // 优先级：被点名 > 窗口内连续对话（可执行）> 窗口外 join=true 且无指令（只回一句）> 静默。
        boolean candidate=!respond;
        if(candidate) Log.info("聊天旁听（窗口外，交给模型判断要不要自然接一句）（"+key+"，用户 "+user+"）："+Log.text(text));
        if(session.pending.size()>=64) { if(candidate) return; }
        else if(candidate) { /* 旁听消息不占每分钟发言额度 */ }
        if(!candidate) session.requested.addLast(now);
        session.pending.addLast(new Input(event.deepCopy(),text,revision,respond,explicit,continuation,candidate));
        String role=respond ? (explicit ? "显式唤醒" : "窗口续期") : "普通群消息旁听";
        Log.info("聊天入队（"+key+"，"+role+"，用户 "+user+"，队列 "+session.pending.size()+"）："+Log.text(text));
        if(session.busy) { Log.info("聊天排队（"+key+"）：当前会话正忙，已加入等待队列 "+session.pending.size()+" 条"); return; }
        session.busy=true; Session target=session;
        try { executor.execute(() -> process(target)); }
        catch(RejectedExecutionException e) { target.busy=false; target.pending.clear(); }
    }
    private void process(Session target) {
        while(true) {
            Input input; String personality; JsonArray history; JsonObject choices;
            synchronized(this) {
                input=target.pending.pollFirst();
                if(input==null || closed) { target.busy=false; return; }
                if(input.revision()!=revision || !settings.chatEnabled(target.key)) continue;
                personality=settings.chatPersonality(); history=target.history.deepCopy(); choices=context.choices(input.event());
            }
            boolean sendStarted=false; String attempted="";
            try {
                ChatActions.Plan plan;
                // 修 bug 2（对齐 TS 版）：整句是"编号指代 + 出图动作"（"选择第二个然后生成"）时，
                // 编号后面那截是动作、不是名字：先切成"载入 #N + .gen"两步，再走原来的单步解析。
                ChatActions.Plan chained=Bot.numberedFollowUpPlan(choices,input.text());
                // 整句只是"第二个/#2"这类指代时，编号属于哪份列表、该用哪条指令由程序判定：
                // 模型经常看着多份列表挑错（把预设的"第一个"当成本地 LoRA），这里不再给它猜的机会。
                Bot.ShownSelection direct=chained!=null ? null : Bot.resolveShownSelection(choices,input.text());
                if(chained!=null) {
                    Log.info("编号指代 + 后续出图，拼成一条链路（"+target.key+"）："+chained.commands());
                    plan=chained;
                } else if(direct!=null && direct.clarification()) {
                    // 编号与名字对不上（或名字有歧义）：程序直接问清楚，绝不执行。
                    Log.info("编号与用户说法对不上，改为澄清（"+target.key+"）");
                    plan=new ChatActions.Plan(direct.label(),List.of(),"",100,0);
                } else if(direct!=null) {
                    Log.info("按最近展示的编号列表直接执行（"+target.key+"）："+direct.command());
                    plan=new ChatActions.Plan("好，就按你刚看过的列表来："+direct.label(),List.of(direct.command()),"",100,0);
                } else {
                // K2/K3/K4：把此刻的好感度分档、情绪与"这轮该用哪种敏感话题反应"作为状态提示一并交给规划层；
                // 它们只影响语气与亲密度，绝不改变"要不要执行"。
                String stateHint=personaHint(target.key,input.text());
                String voice=stateHint.isBlank() ? personality : personality+"\n\n"+stateHint;
                plan=input.candidate()
                        ? planner.planBrief(voice,history,input.text(),choices,speakerWithRole(input.event()))
                        : planner.plan(voice,history,input.text(),choices,speakerWithRole(input.event()));
                if(plan.commands().isEmpty()) {
                    // 计划空了但用户明显在指代刚看过的编号列表：把那份列表再强调一次，避免"刚搜完就反问"。
                    String nudge=Bot.selectionNudge(choices,input.text());
                    if(nudge!=null) {
                        try {
                            ChatActions.Plan retry=planner.plan(personality,history,input.text()+"\n"+nudge,choices,speakerWithRole(input.event()));
                            if(!retry.commands().isEmpty()) plan=retry;
                            else Log.info("补充规划后仍没有指令（"+target.key+"），按普通回复处理");
                        } catch(Exception error) { Log.warn("按编号列表补充规划失败，沿用第一次结果："+Bot.error(error)); }
                    }
                }
                if(plan.commands().isEmpty()) {
                    // 兜底：整句只是"第二个/#2"这类指代时，编号属于哪份列表、该用哪条指令由程序判定，不再问模型。
                    Bot.ShownSelection resolved=Bot.resolveShownSelection(choices,input.text());
                    if(resolved!=null && resolved.clarification()) {
                        plan=new ChatActions.Plan(resolved.label(),List.of(),"",100,0);
                    } else if(resolved!=null) {
                        Log.info("按最近展示的编号列表直接执行（"+target.key+"）："+resolved.command());
                        plan=new ChatActions.Plan("好，就按你刚看过的列表来："+resolved.label(),List.of(resolved.command()),"",100,0);
                    }
                }
                }
                ChatActions.validate(plan.commands()); String answer=attempted=Bot.publicCommands(plan.reply());
                // N1：旁听（窗口外）消息即使计划里有 commands 也不算 forced——它只能"自然接一句"，绝不能执行。
                boolean forced=input.explicit() || (!plan.commands().isEmpty() && !input.candidate());
                // 窗口内的续话：进行中的对话不受插话判定影响，否则降噪会打断自己的连续回答。
                boolean continuing=input.continuation() && plan.interest()>=50;
                boolean onTopic=plan.interest()>=30;
                // 主动插话：不再掷随机数、也不再用"基数"。由模型读上下文判断现在接话自不自然（join）；
                // 模型没给 join 字段时（老提示词/解析失败）退回按相关度门槛的保守兜底，绝不退回随机概率。
                boolean muted=settings.chatBaseProbability()<=0;
                boolean wantsJoin=plan.joinGiven() ? plan.join() : plan.interest()>=settings.chatChimeHighInterest();
                String joinReason=plan.joinGiven() ? "模型判断" : "模型未给 join，按相关度门槛兜底";
                // 本会话已有进行中的对话（正在跟别人说话）时绝不抢话：这是硬闸门，join 也压不过它。
                boolean engaged=false;
                long engagedWindow=TimeUnit.SECONDS.toNanos(settings.chatContextSeconds());
                synchronized(this) {
                    long current=clock.getAsLong();
                    for (long active : target.activeUsers.values()) if (current-active < engagedWindow) { engaged=true; break; }
                }
                boolean chime=!forced && !continuing && !muted && wantsJoin && !engaged;
                boolean volunteered=chime;
                // 被点名、带指令、窗口内的高相关续话、以及模型判定"该接"的插话才回复；
                // 低相关的续话不回复（这正是不再需要随机数之后的"相关度闸门"）。
                // N1：窗口外的消息只允许"自然接一句"——计划里一旦有 commands，就既不回复也不执行。
                if(input.candidate() && !plan.commands().isEmpty()) {
                    Log.info("不回复（"+target.key+"）：窗口外的消息带了指令（"+plan.commands().size()
                            +" 条），按规则不执行也不回复；消息已计入上下文");
                    synchronized(this) { if(input.revision()==revision && !closed) appendUser(target,input); }
                    continue;
                }
                boolean answerable=forced || continuing || chime;
                if(input.candidate() && !chime && !forced && !continuing && engaged) {
                    // 本会话已有进行中的对话：不要抢话（这是硬闸门，join 也压不过它）。
                    Log.info("跳过插话（"+target.key+"）：本会话已有进行中的对话");
                }
                if(answerable && chime) {
                    // 即使模型说"可以接"，也还要过防扰民的闸门：相关度门槛、拒绝类回复、冷却时间。
                    long now=clock.getAsLong();
                    boolean rebuke=ChatActions.isRebuke(plan.reply());
                    // Someone else's two-person exchange may only be joined when the topic is clearly about the bot.
                    boolean thirdParty=Json.bool(input.event(),"chat_third_party",false);
                    int floor=thirdParty ? settings.chatChimeHighInterest() : settings.chatBaseMinInterest();
                    boolean onTopicEnough=plan.interest()>=floor && !rebuke;
                    boolean cooled=now-target.lastChime>=TimeUnit.SECONDS.toNanos(settings.chatChimeCooldownSeconds());
                    if(!onTopicEnough || !cooled) {
                        Log.info("放弃插话（"+target.key+"）：相关度 "+plan.interest()+"，"
                                +(onTopicEnough?"冷却中":(rebuke?"拒绝类回复不用于插话":(thirdParty?"这是别人之间的对话，相关度未达 "+floor:"低于插话相关度下限 "+floor))));
                        synchronized(this) { if(input.revision()==revision && !closed) appendUser(target,input); }
                        continue;
                    }
                    synchronized(this) { target.lastChime=now; }
                }
                if(!forced && !onTopic) {
                    // A clearly different subject ends the window immediately; otherwise it takes two low
                    // relevance messages, so a single off-hand remark does not break the thread.
                    boolean shifted=topicShifted(target,input.text());
                    target.drift += shifted ? 2 : 1;
                    if(target.drift>=2) {
                        synchronized(this) { target.activeUsers.remove(user(input.event())); }
                        Log.info("话题"+(shifted?"明显转变，已提前结束对话窗口":"已抽身")+"（"+target.key+"）："
                                +(shifted?"本条与近期内容几乎没有重合":"连续 "+target.drift+" 条低相关消息")
                                +"，需要重新唤名或高相关度才会继续");
                    }
                } else if(onTopic) target.drift=0;
                if(!answerable) {
                    Log.info("聊天判定（"+target.key+"）：相关度 "+plan.interest()+"，主动插话不接（"
                            +(muted?"主动插话已关闭":joinReason+" join=false")+"），本次不回复；消息已计入上下文");
                    synchronized(this) { if(input.revision()==revision && !closed) appendUser(target,input); }
                    continue;
                }
                Log.info("聊天判定（"+target.key+"）："+(forced ? (input.explicit() ? "显式唤醒，直接回复" : "需执行指令，直接回复")
                        : "相关度 "+plan.interest()+"，"+joinReason+" join=true，接话")
                        +(plan.commands().isEmpty() ? "" : "，指令 "+plan.commands().size()+" 条"));
                CompletableFuture<Void> sent;
                synchronized(this) {
                    long time=clock.getAsLong(); expire(target.spoken,time);
                    if(closed || !settings.chatEnabled(target.key) || input.revision()!=revision || target.spoken.size()>=settings.chatFrequency()) continue;
                    target.spoken.addLast(time); sendStarted=true; sent=sender.send(input.event(),Maps.text(answer));
                }
                sent.get();
                if (!plan.commands().isEmpty()) {
                    synchronized(this) { if(closed || !settings.chatEnabled(target.key) || input.revision()!=revision) continue; }
                    try { actions.execute(input.event(), plan.commands(), choices); }
                    catch(Exception e) { sender.send(input.event(), Maps.text("聊天指令未全部完成：" + Bot.error(e))).get(); }
                }
                synchronized(this) {
                    if(input.revision()!=revision || closed) continue;
                    target.history.add(DeepSeekPrompts.chatMessage("user",historyMessage(input.event(),input.text()))); target.history.add(DeepSeekPrompts.chatMessage("assistant",answer));
                    // 主动插的那一句要记下来：下一条消息如果没人接这个话题，就立刻退出对话窗口，
                    // 不让它自己变成一段持续对话。
                    if(volunteered) {
                        target.lastChimeReply = answer;
                        target.chimeTarget = user(input.event());
                    }
                    // A completed reply is activity too: the follow-up window starts again when the user can actually answer.
                    // Only a real exchange extends the follow-up window; a volunteered aside must not hijack it.
                    if(input.explicit() || (input.continuation() && onTopic)) target.activeUsers.put(user(input.event()),clock.getAsLong());
                    trim(target.history);
                }
                Log.info("聊天回复（"+target.key+"）："+Log.text(answer));
                applyPersonaState(target.key,input.text(),plan);
                if(plan.wakeAdjust()!=0) {
                    // 唤醒基数已废弃：模型仍可能带着这个字段，这里只记一行日志，绝不写回配置、也不影响判定。
                    Log.info("模型给了已废弃的 wake_adjust="+plan.wakeAdjust()+"（"+target.key+"）：已忽略，"
                            +"是否插话现在由上下文判断");
                }
            } catch(Exception e) {
                if(e instanceof InterruptedException) Thread.currentThread().interrupt();
                boolean sendFailed; boolean notify;
                synchronized(this) {
                    sendFailed=sendStarted;
                    notify=!sendStarted && input.revision()==revision && !closed && settings.chatEnabled(target.key);
                    if(input.revision()==revision && !closed) appendUser(target,input);
                }
                if(sendFailed) {
                    // Without this the failed content is invisible, which hides QQ-side rejections.
                    Log.warn("聊天回复未能发送（"+target.key+"，"+attempted.length()+" 字符）："+Log.text(attempted));
                    sendFailureNotice(input.event(),e,attempted);
                }
                else if(notify) {
                    String notice="小鸟这边刚才没能处理完这条消息，可能是服务繁忙或响应超时。请稍后再说一次。";
                    try {
                        sender.send(input.event(),Maps.text(notice)).get();
                        synchronized(this) {
                            if(input.revision()==revision && !closed) {
                                target.history.add(DeepSeekPrompts.chatMessage("assistant",notice));
                                target.activeUsers.put(user(input.event()),clock.getAsLong());trim(target.history);
                            }
                        }
                    } catch(Exception ignored) { /* A failed/unknown QQ send is never retried here. */ }
                }
                Log.error("聊天回复未完成（"+target.key+"，用户 "+user(input.event())+"）", e);
            }
        }
    }
    /**
     * K2/K3/K4 的状态提示：好感度分档 + 当前情绪 + 这轮敏感话题该用哪种反应（最近 3 次不重复）。
     * 只写"怎么说"，不写"做不做"——执行层不受这里影响。
     */
    public String personaHint(String conversation, String message) {
        try {
            PersonaState state=PersonaState.of(settings.root);
            int score=state.affinity(conversation);
            PersonaState.Mood mood=state.mood(conversation);
            StringBuilder hint=new StringBuilder("【此刻的状态（只影响语气与亲密度，绝不影响是否执行）】\n");
            hint.append("好感度 ").append(score).append("（").append(PersonaState.tier(score)).append("）：")
                .append(PersonaState.tierHint(score)).append("\n");
            hint.append(PersonaState.moodHint(mood)).append("\n");
            if (PersonaState.sensitive(message)) {
                String reaction=state.decideReaction(conversation,message,score,mood,System.currentTimeMillis());
                hint.append("这轮碰到敏感话题：只用这一种反应——").append(reaction).append("。")
                    .append(PersonaState.reactionHint(reaction))
                    .append("（最近用过的反应：").append(String.join("、", state.recentReactions(conversation))).append("）\n");
            }
            return hint.toString();
        } catch (Exception error) {
            Log.warn("人格状态提示生成失败，本次按默认语气：" + Bot.error(error));
            return "";
        }
    }
    /** 回复发出后落状态：敏感话题的反应记账 + 好感度/情绪变化（模型给的 + 程序规则，带冷却）。 */
    public void applyPersonaState(String conversation, String message, ChatActions.Plan plan) {
        try {
            PersonaState state=PersonaState.of(settings.root);
            // 每次交互都把情绪衰减计时清零：连着聊不会衰减，停 10 分钟才回落一档。
            state.touch(conversation);
            PersonaState.Trigger rule=PersonaState.triggerOf(message);
            long now=System.currentTimeMillis();
            if (plan!=null && plan.moodIntensity()>0 && !plan.mood().isBlank()
                    && state.triggerAllowed(conversation,"model:"+plan.mood(),now))
                state.setMood(conversation,plan.mood(),plan.moodIntensity(),plan.affinityReason());
            if (rule!=null && state.triggerAllowed(conversation,rule.kind(),now)) {
                state.applyTrigger(conversation,rule);
                Log.info("人格状态（"+conversation+"）：触发 "+rule.kind()+"，好感度 "
                        +state.affinity(conversation)+"，情绪 "+state.mood(conversation).mood());
            } else if (plan!=null && plan.affinityDelta()!=0) {
                int updated=state.adjustAffinity(conversation,plan.affinityDelta());
                Log.info("人格状态（"+conversation+"）：模型好感度 "+plan.affinityDelta()+"（"+plan.affinityReason()+"）→ "+updated);
            }
        } catch (Exception error) {
            Log.warn("人格状态落账失败（不影响本次回复）："+Bot.error(error));
        }
    }
    /**
     * A chat reply whose delivery failed is reported to the same conversation as a short plain message.
     * The notice is sent directly (never through the planner) and its own failure is only logged.
     */
    private void sendFailureNotice(JsonObject event, Throwable failure, String attempted) {
        String notice="聊天回复发送失败："+Bot.error(failure).replaceAll("\\R+"," ").strip();
        if(attempted!=null && !attempted.isBlank())
            notice+="\n原回复摘要（"+attempted.length()+" 字符）："+Log.text(attempted);
        try {
            sender.send(event,Maps.text(Bot.publicCommands(notice))).exceptionally(retry -> {
                Log.warn("聊天失败提示也未能发送：" + Bot.error(retry)); return null; });
        } catch(Exception ignored) { /* Nothing else can be reported here. */ }
    }
    private void appendUser(Session target,Input input) {
        target.history.add(DeepSeekPrompts.chatMessage("user",historyMessage(input.event(),input.text())));trim(target.history);
    }
    private static void trim(JsonArray history) {
        while(history.size()>24 || history.toString().length()>20000) history.remove(0);
        while(!history.isEmpty() && "assistant".equals(Json.str(history.get(0).getAsJsonObject(),"role",""))) history.remove(0);
    }
    public synchronized void close() { closed=true; revision++; executor.shutdownNow(); sessions.clear(); }
}

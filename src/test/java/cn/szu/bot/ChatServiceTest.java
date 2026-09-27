package cn.szu.bot;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.ChatService;
import cn.szu.bot.chat.DeepSeekPrompts;
public final class ChatServiceTest {
 static JsonObject event(String type,String id) { JsonObject e=new JsonObject();e.addProperty("message_type",type);e.addProperty(type.equals("group")?"group_id":"user_id",id);if(type.equals("group"))e.addProperty("user_id","sender");e.addProperty("self_id","bot");e.addProperty("raw_message","[CQ:at,qq=bot] hello");JsonObject s=new JsonObject();s.addProperty("nickname",type.equals("group")?"sender":id);e.add("sender",s);return e; }
 static JsonObject ordinary(String group,String user,String text) { JsonObject e=event("group",group);e.addProperty("user_id",user);e.getAsJsonObject("sender").addProperty("nickname",user);e.addProperty("raw_message",text);e.addProperty("message",text);return e; }
 static String speaker(JsonArray history,int index) { return Json.parse(history.get(index).getAsJsonObject().get("content").getAsString()).getAsJsonObject("speaker").get("id").getAsString(); }
 static void idle(ChatService service) throws Exception {
  var f=ChatService.class.getDeclaredField("executor");f.setAccessible(true);var ex=(ThreadPoolExecutor)f.get(service);
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int stable=0;
  while(stable<3) { if(System.nanoTime()>end) throw new AssertionError("worker stuck");
   if(ex.getActiveCount()==0 && ex.getQueue().isEmpty()) stable++; else stable=0;Thread.sleep(5); }
 }
 public static void main(String[] args) throws Exception {
  JsonObject group=event("group","test");group.addProperty("raw_message","ordinary");
  assert !ChatService.addressed(group,"ordinary");
  assert ChatService.addressed(group,"小鸟，早上好");assert ChatService.addressed(group,"神戸小鳥");
  assert ChatService.addressed(group,"Kotori hello");assert !ChatService.addressed(group,"kotoring");
  group.addProperty("raw_message","[CQ:at,qq=all] hi");assert !ChatService.addressed(group,"hi");
  group.addProperty("raw_message","[CQ:at,qq=someone] hi");assert !ChatService.addressed(group,"hi");
  group.addProperty("raw_message","[CQ:at,qq=bot] hi");assert ChatService.addressed(group,"hi");
  group.add("message",JsonParser.parseString("[{\"type\":\"at\",\"data\":{\"qq\":\"bot\"}}]"));assert ChatService.addressed(group,"hi");
  group.add("message",JsonParser.parseString("[{\"type\":\"text\",\"data\":{\"text\":\"[CQ:at,qq=bot]\"}}]"));assert !ChatService.addressed(group,"hi");
  assert ChatService.addressed(event("private","test"),"ordinary");
  Path root=Files.createTempDirectory(Path.of(System.getProperty("bot.test.work","work")),"chat-test-");Json.atomicWrite(root.resolve("config.json"),new JsonObject());
  Settings settings=new Settings(root);assert settings.chatEnabled();assert settings.chatFrequency()==6;settings.chatSetting("topic_gap_seconds",new JsonPrimitive(3600));
  settings.chatSetting("frequency",new JsonPrimitive(2));assert new Settings(root).chatFrequency()==2;
  AtomicLong now=new AtomicLong();List<String> replies=new CopyOnWriteArrayList<>();List<JsonArray> histories=new CopyOnWriteArrayList<>();
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},(p,h,m)->{histories.add(h);return "reply "+m;},now::get)) {
   service.accept(event("group","A"),"one");idle(service);service.accept(event("group","A"),"two");idle(service);
   assert replies.size()==2;assert histories.get(1).size()==2;
   service.accept(event("group","A"),"limited");idle(service);assert replies.size()==2;
   service.accept(event("private","A"),"private");idle(service);assert replies.size()==3;assert histories.get(histories.size()-1).isEmpty() : "私聊会话的历史从空开始";
   now.addAndGet(TimeUnit.MINUTES.toNanos(1));service.accept(event("group","A"),"later");idle(service);assert replies.size()==4;
   settings.chatSetting("enabled",new JsonPrimitive(false));service.changed(false);service.accept(event("group","A"),"off");idle(service);assert replies.size()==4;
  }
  settings.chatSetting("enabled",new JsonPrimitive(true));CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  try(ChatService service=new ChatService(settings,(e,segs)->{throw new AssertionError("disabled pending reply sent");},(p,h,m)->{entered.countDown();release.await();return "stale";},now::get)) {
   service.accept(event("group","B"),"pending");assert entered.await(3,TimeUnit.SECONDS);
   settings.chatSetting("enabled",new JsonPrimitive(false));service.changed(false);release.countDown();idle(service);
  }
  settings.chatSetting("enabled",new JsonPrimitive(true));settings.chatSetting("frequency",new JsonPrimitive(10));replies.clear();
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m)->{throw new java.io.IOException("simulated timeout");},now::get)) {
   service.accept(event("group","timeout"),"小鸟，能听见吗");idle(service);
   assert replies.size()==1 && replies.get(0).contains("服务繁忙或响应超时") : "planner timeout must notify instead of silently dropping";
  }
  settings.chatSetting("enabled",new JsonPrimitive(true));settings.chatSetting("frequency",new JsonPrimitive(10));
  AtomicInteger calls=new AtomicInteger();CountDownLatch firstEntered=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
  settings.chatSetting("topic_gap_seconds",new JsonPrimitive(3600));replies.clear();histories.clear();
  Map<String,JsonArray> snapshots=new ConcurrentHashMap<>();
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{histories.add(h);snapshots.put(m,h);
              if(calls.incrementAndGet()==1){firstEntered.countDown();releaseFirst.await();}
              // 旁听消息一律 join=false：模型判断"这句话不是对我说的"。
              return new ChatActions.Plan("reply "+m,List.of(),"",100,0,false,true);},
          (e,c,o)->{},e->new JsonObject(),now::get)) {
   var f=ChatService.class.getDeclaredField("executor");f.setAccessible(true);assert ((ThreadPoolExecutor)f.get(service)).getMaximumPoolSize()==1;
   // N1：窗口外的旁听消息允许规划（模型据此决定要不要自然接一句），但绝不执行它的指令。
   service.accept(ordinary("C","bob","我喜欢红色"),"我喜欢红色");
   assert firstEntered.await(3,TimeUnit.SECONDS) : "窗口外的旁听消息允许规划";
   JsonObject first=event("group","C");first.addProperty("user_id","alice");service.accept(first,"小鸟小姐，接着聊");
   service.accept(ordinary("C","alice","上一句后面的补充"),"上一句后面的补充");
   service.accept(ordinary("C","bob","别人的普通消息"),"别人的普通消息");
   now.addAndGet(TimeUnit.MINUTES.toNanos(31));releaseFirst.countDown();idle(service);
   assert replies.size()==2 && replies.stream().noneMatch(r -> r.contains("我喜欢红色")) && replies.stream().noneMatch(r -> r.contains("别人的普通消息"))
           : "busy follow-up must queue, unrelated user must stay silent: " + replies;
   JsonArray context=snapshots.get("小鸟小姐，接着聊");
   assert context!=null && context.size()==1 && speaker(context,0).equals("bob") : "passive preceding speaker context";
   JsonArray queued=snapshots.get("上一句后面的补充");
   boolean hasBob=false, hasAlice=false;
   if(queued!=null) for(JsonElement item:queued) {
       JsonObject entry=item.getAsJsonObject();
       if(!"user".equals(Json.str(entry,"role",""))) continue;
       String id=Json.parse(entry.get("content").getAsString()).getAsJsonObject("speaker").get("id").getAsString();
       if("bob".equals(id)) hasBob=true;
       if("alice".equals(id)) hasAlice=true;
   }
   assert hasBob && hasAlice : "queued follow-up must preserve speaker identity（bob=" + hasBob + " alice=" + hasAlice + "）";
   service.accept(ordinary("C","alice","回复后继续"),"回复后继续");idle(service);assert replies.size()==3 : "successful reply renews activation";
   JsonArray renewed=snapshots.get("回复后继续");
   boolean bobInContext=false;
   if(renewed!=null) for(JsonElement item:renewed) {
       JsonObject entry=item.getAsJsonObject();
       if(!"user".equals(Json.str(entry,"role",""))) continue;
       String id=Json.parse(entry.get("content").getAsString()).getAsJsonObject("speaker").get("id").getAsString();
       if("bob".equals(id)) bobInContext=true;
   }
   // 决定 3 之后旁听消息不再规划，但仍会作为上下文留在历史里。
   assert bobInContext : "别人（旁听）的发言必须留作上下文：" + renewed;
   now.addAndGet(TimeUnit.MINUTES.toNanos(31));service.accept(ordinary("C","alice","长时间无互动"),"长时间无互动");idle(service);
   assert replies.size()==3 : "rolling inactivity expiry";
  }
  CountDownLatch burstEntered=new CountDownLatch(1),burstRelease=new CountDownLatch(1);AtomicInteger burstCalls=new AtomicInteger();replies.clear();
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},(p,h,m)->{
   if(burstCalls.incrementAndGet()==1){burstEntered.countDown();burstRelease.await();}return "reply "+m;
  },now::get)) {
   service.accept(event("private","burst"),"burst 1");assert burstEntered.await(3,TimeUnit.SECONDS);
   for(int i=2;i<=10;i++) service.accept(event("private","burst"),"burst "+i);
   burstRelease.countDown();idle(service);
   assert burstCalls.get()==10 && replies.size()==10 : "ten rapid messages must all drain through the serial chat worker";
  }
  // Topic interest gates follow-up replies: explicit address and concrete commands always answer.
  settings.chatSetting("enabled",new JsonPrimitive(true));settings.chatSetting("frequency",new JsonPrimitive(10));
  AtomicInteger planned=new AtomicInteger();replies.clear();
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{planned.incrementAndGet();return new ChatActions.Plan("低相关度回应",List.of(),"",10);},(e,c,o)->{},e->new JsonObject(),now::get)) {
   JsonObject wake=event("group","D");wake.addProperty("user_id","alice");
   service.accept(wake,"小鸟，在吗");idle(service);
   assert replies.size()==1 && planned.get()==1 : "explicit address must always answer";
   service.accept(ordinary("D","alice","顺便说一句无关的话"),"顺便说一句无关的话");idle(service);
   assert planned.get()==2 && replies.size()==1 : "low interest must skip the follow-up reply";
   service.accept(wake,"小鸟，再问一次");idle(service);
   assert replies.size()==2 : "explicit address answers regardless of interest";
   service.accept(ordinary("D","alice","窗口内的普通消息"),"窗口内的普通消息");idle(service);
   assert replies.size()==2 : "continuation still needs the interest gate to pass";
  }
  replies.clear();AtomicInteger plannedCommands=new AtomicInteger();
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{plannedCommands.incrementAndGet();return new ChatActions.Plan("这就去改",List.of(".size set 768 512"),"",0);},(e,c,o)->{},e->new JsonObject(),now::get)) {
   JsonObject wake=event("group","E");wake.addProperty("user_id","bob");
   service.accept(wake,"小鸟，把尺寸改成 768×512");idle(service);
   assert plannedCommands.get()==1 && replies.size()>=1 : "requested commands must always be answered";
  }
  replies.clear();AtomicLong nowHigh=new AtomicLong(now.get());
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->new ChatActions.Plan("高相关度回应",List.of(),"",100),(e,c,o)->{},e->new JsonObject(),nowHigh::get)) {
   JsonObject wake=event("group","F");wake.addProperty("user_id","carol");
   service.accept(wake,"小鸟，在吗");idle(service);service.accept(ordinary("F","carol","窗口内的高相关度消息"),"窗口内的高相关度消息");idle(service);
   assert replies.size()==2 : "a high-interest continuation must answer";
  }
  replies.clear();AtomicLong nowSend=new AtomicLong(now.get());
  try(ChatService service=new ChatService(settings,(e,segs)->{String text=Bot.messageText(segs);
            if(text.equals("正常回复")) return CompletableFuture.failedFuture(new java.io.IOException("模拟发送失败"));
            replies.add(text);return CompletableFuture.completedFuture(null);},
          (p,h,m)->"正常回复",nowSend::get)) {
   service.accept(event("private","send-failure"),"看看");idle(service);
   assert replies.size()==1 && replies.get(0).contains("聊天回复发送失败") && replies.get(0).contains("模拟发送失败")
           : "a failed chat reply must report the error to the conversation: " + replies;
   assert replies.get(0).contains("原回复摘要") && replies.get(0).contains("正常回复")
           : "the notice must summarise the reply that could not be sent: " + replies.get(0);
   assert replies.get(0).split("\\R",-1).length<=2 : "the notice stays a short plain message";
  }
  replies.clear();AtomicLong nowRole=new AtomicLong(now.get());
  java.util.concurrent.atomic.AtomicReference<JsonObject> seenSpeaker=new java.util.concurrent.atomic.AtomicReference<>();
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{seenSpeaker.set(s);return new ChatActions.Plan("好",List.of());},(e,c,o)->{},e->new JsonObject(),nowRole::get)) {
   service.accept(event("private",Settings.DEFAULT_OWNER),"在吗");idle(service);
   assert "owner".equals(seenSpeaker.get().get("role").getAsString()) : "the configured owner must be labelled: " + seenSpeaker.get();
   service.accept(event("private","456"),"在吗");idle(service);
   assert "user".equals(seenSpeaker.get().get("role").getAsString()) : "everyone else is a plain user: " + seenSpeaker.get();
  }
  // 决定 3（owner 2026-09-27）取代了 join 时代的"窗口外主动插话"行为：
  // 窗口外未点名的消息不再规划，因此这里不再保留 join/冷却/总静音/第三人称那些用例；
  // 总静音开关与显式唤醒的覆盖见下面「决定 3」一节与该文件其余用例。
  // 机器自己发出的消息（含 .help 的播报）绝不能再被当成用户消息规划一遍。
  replies.clear();histories.clear();
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m)->{histories.add(h);return "reply "+m;},now::get)) {
   JsonObject own=event("group","Z");own.addProperty("user_id",Json.str(own,"self_id",""));
   service.accept(own,"我上线啦，随时可以叫我。发送 .help 查看指令。");idle(service);
   assert replies.isEmpty() && histories.isEmpty() : "机器人自己的消息必须被忽略：" + replies;
  }
  // N1（owner 2026-09-27 折中）：窗口外允许"自然接一句"，但绝不执行指令。
  Path gateRoot=Files.createTempDirectory(Path.of(System.getProperty("bot.test.work","work")),"chat-gate-");
  Json.atomicWrite(gateRoot.resolve("config.json"),new JsonObject());
  Settings gateSettings=new Settings(gateRoot);
  gateSettings.chatSetting("enabled",new JsonPrimitive(true));gateSettings.chatSetting("frequency",new JsonPrimitive(10));
  gateSettings.chatSetting("topic_gap_seconds",new JsonPrimitive(3600));
  gateSettings.chatSetting("chime_cooldown_seconds",new JsonPrimitive(0));
  replies.clear();histories.clear();AtomicInteger gateCalls=new AtomicInteger();AtomicInteger gateExecuted=new AtomicInteger();
  // ① 窗口外 join=true 且**无指令** → 只回一句、不执行
  try(ChatService service=new ChatService(gateSettings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{gateCalls.incrementAndGet();return new ChatActions.Plan("嗯——接得上呀。",List.of(),"",90,0,true,true);},
          (e,commands,o)->gateExecuted.addAndGet(commands.size()),e->new JsonObject(),now::get)) {
   service.accept(ordinary("W","zed","今天天气不错"),"今天天气不错");idle(service);
   assert replies.size()==1 && gateExecuted.get()==0 : "窗口外 join=true 且无指令时只自然接一句：" + replies;
  }
  // ② 窗口外 join=true 但**计划含指令** → 不回复、不执行（关键用例）
  replies.clear();gateCalls.set(0);gateExecuted.set(0);
  try(ChatService service=new ChatService(gateSettings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{gateCalls.incrementAndGet();return new ChatActions.Plan("好，这就改。",List.of(".size set 896 512"),"",90,0,true,true);},
          (e,commands,o)->gateExecuted.addAndGet(commands.size()),e->new JsonObject(),now::get)) {
   service.accept(ordinary("X","zed","把尺寸改成 896 512"),"把尺寸改成 896 512");idle(service);
   assert gateCalls.get()==1 : "窗口外的消息允许规划";
   assert replies.isEmpty() && gateExecuted.get()==0
           : "窗口外带指令的计划不得回复、不得执行：" + replies + " executed=" + gateExecuted.get();
  }
  // ③ 窗口外 join=false → 静默
  replies.clear();gateCalls.set(0);
  try(ChatService service=new ChatService(gateSettings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{gateCalls.incrementAndGet();return new ChatActions.Plan("……",List.of(),"",90,0,false,true);},
          (e,commands,o)->gateExecuted.addAndGet(commands.size()),e->new JsonObject(),now::get)) {
   service.accept(ordinary("Y","zed","哈哈哈哈"),"哈哈哈哈");idle(service);
   assert replies.isEmpty() : "窗口外 join=false 必须静默：" + replies;
  }
  // ④ 相关度下限仍然拦住（join=true 但 interest 低于门槛）
  replies.clear();
  try(ChatService service=new ChatService(gateSettings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->new ChatActions.Plan("嗯。",List.of(),"",5,0,true,true),
          (e,commands,o)->gateExecuted.addAndGet(commands.size()),e->new JsonObject(),now::get)) {
   service.accept(ordinary("Z","zed","随便说点什么"),"随便说点什么");idle(service);
   assert replies.isEmpty() : "相关度低于下限时不得插话：" + replies;
  }
  // ⑤ 被点名 / 窗口内 continuation 仍可执行
  replies.clear();gateCalls.set(0);gateExecuted.set(0);
  try(ChatService service=new ChatService(gateSettings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{gateCalls.incrementAndGet();return new ChatActions.Plan("好，这就改。",List.of(".size set 896 512"),"",90,0,false,true);},
          (e,commands,o)->gateExecuted.addAndGet(commands.size()),e->new JsonObject(),now::get)) {
   JsonObject call=event("group","V");call.addProperty("user_id","zed");
   service.accept(call,"小鸟，把尺寸改成 896 512");idle(service);
   assert replies.size()==1 && gateExecuted.get()==1 : "被点名必回并执行：" + replies;
   service.accept(ordinary("V","zed","再把高度改成 640"),"再把高度改成 640");idle(service);
   assert replies.size()==2 && gateExecuted.get()==2 : "窗口内连续对话照旧回复并执行：" + replies;
   int gateBefore=cn.szu.bot.chat.PersonaState.of(gateRoot).affinity("bot:group:V");
   service.accept(ordinary("V","zed","谢谢你，你真可靠"),"谢谢你，你真可靠");idle(service);
   assert cn.szu.bot.chat.PersonaState.of(gateRoot).affinity("bot:group:V")>gateBefore
           : "窗口内的好感度照常更新：" + gateBefore;
  }
  // Drifting away from the woken topic ends the thread instead of answering every unrelated line.
  settings.chatSetting("enabled",new JsonPrimitive(true));settings.setChatBaseProbability(1);
  replies.clear();AtomicLong nowDrift=new AtomicLong(now.get());AtomicInteger driftCalls=new AtomicInteger();
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{int call=driftCalls.incrementAndGet();return new ChatActions.Plan("回应",List.of(),"",call==1?90:5);},
          (e,c,o)->{},e->new JsonObject(),nowDrift::get)) {
   JsonObject wake=event("group","H");wake.addProperty("user_id","erin");
   service.accept(wake,"小鸟，在吗");idle(service);
   assert replies.size()==1 : "an explicit wake answers";
   service.accept(ordinary("H","erin","完全无关的第一句"),"完全无关的第一句");idle(service);
   service.accept(ordinary("H","erin","完全无关的第二句"),"完全无关的第二句");idle(service);
   assert replies.size()==1 : "after two unrelated lines the bot leaves the thread instead of replying";
   service.accept(ordinary("H","erin","完全无关的第三句"),"完全无关的第三句");idle(service);
   assert replies.size()==1 : "after drifting away no unrelated line is answered deliberately";
  }  try(var f=new GenerationPresetTest.Fixture()) {
   assert f.command("private",".chat personality 温柔简洁").contains("温柔简洁");
   assert f.command("private",".chat add 喜欢照料花草").contains("已追加");
   String frequencyReply=f.command("group",".chat frequency 3");assert frequencyReply.contains("3 次") : frequencyReply;
   assert f.command("private",".chat toggle").contains("本会话聊天已关闭");
   assert new Settings(f.root).chatEnabled() : "per-conversation toggle must keep the global switch on";
   assert !new Settings(f.root).chatEnabled("1:private:2") : "the toggled conversation must be off";
   assert new Settings(f.root).chatEnabled("1:group:3") : "other conversations must stay on";
   assert f.command("private",".chat toggle").contains("本会话聊天已开启");
   assert new Settings(f.root).chatEnabled("1:private:2") : "toggling twice restores the conversation";
   assert f.command("group",".chat global off").contains("全局聊天开关已关闭");
   assert !new Settings(f.root).chatEnabled() && !new Settings(f.root).chatEnabled("1:group:3") : "global off overrides sessions";
   assert f.command("group",".chat global on").contains("全局聊天开关已开启");
   assert new Settings(f.root).chatEnabled("1:group:3") && new Settings(f.root).chatEnabled("1:private:2");
   assert f.command("private",".chat").contains("本会话 开启") && f.command("private",".chat").contains("主动插话总开关");
   assert f.command("private",".chat wake 50").contains("唤醒基数已移除") : "已移除的 /chat wake 必须给出用法提示而不是生效";
   assert f.command("private",".chat base 0").contains("主动插话总开关已设为 0") : "/chat base 0 是插话总静音开关";
   assert new Settings(f.root).chatChimeMuted() : "base 0 之后必须处于静音状态";
   assert new Settings(f.root).chatPersonality().equals("温柔简洁\n喜欢照料花草");
   assert f.command("private",".chat frequency -1").contains("非负整数");
   String help=f.command("private",".help");assert help.contains(".chat add <内容>") && !help.contains("/chat");
   assert Bot.internalCommand(".gen 2").equals("/gen 2");
   assert Bot.publicCommands("发送 /help，链接 https://civitai.com/models/1").equals("发送 .help，链接 https://civitai.com/models/1");
  }
  Files.createDirectories(root.resolve("data"));Files.writeString(root.resolve("data/deepseek-api-key.txt"),"test-only");
  var client=new DeepSeekPrompts(root,new JsonObject(),(body,key,timeout)->{
   assert !body.has("response_format");assert body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("温柔");
   return new DeepSeekPrompts.Response(200,"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"你好\"}}]}");
  });assert client.chat("温柔",new JsonArray(),"你好").equals("你好");
  System.out.println("ChatServiceTest PASS: persistent commands, defaults, rolling rate, isolated history, pending cancellation, standard chat API.");
 }
}

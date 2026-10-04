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
  Settings settings=new Settings(root);assert settings.chatEnabled();assert settings.chatFrequency()==6;
  assert settings.chatContextSeconds()==1800 : "对话窗口仍是 30 分钟（chat.context_seconds）："+settings.chatContextSeconds();
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
  replies.clear();histories.clear();
  Map<String,JsonArray> snapshots=new ConcurrentHashMap<>();
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{histories.add(h);snapshots.put(m,h);
              if(calls.incrementAndGet()==2){firstEntered.countDown();releaseFirst.await();}
              // 被点名时模型判定这段对话值得继续：join=true 把对方拉进 30 分钟对话窗口。
              return new ChatActions.Plan("reply "+m,List.of(),"",100,true,true);},
          (e,c,o)->{},e->new JsonObject(),now::get)) {
   var f=ChatService.class.getDeclaredField("executor");f.setAccessible(true);assert ((ThreadPoolExecutor)f.get(service)).getMaximumPoolSize()==1;
   // 新契约：主动插话已删除——窗口外、没被 @ 也没叫名字的消息不回复、不规划、不进历史；
   // 被点名一定回复一次，之后由模型计划里的 join 决定要不要把对方拉进 30 分钟对话窗口。
   JsonObject first=event("group","C");first.addProperty("user_id","alice");
   service.accept(first,"小鸟，在吗");idle(service);
   assert replies.size()==1 && calls.get()==1 : "被点名必答一次：" + replies;
   service.accept(first,"小鸟小姐，接着聊");
   assert firstEntered.await(3,TimeUnit.SECONDS) : "第二条被点名的消息进入规划并占住 worker";
   service.accept(ordinary("C","alice","上一句后面的补充"),"上一句后面的补充");
   service.accept(ordinary("C","bob","别人的普通消息"),"别人的普通消息");
   now.addAndGet(TimeUnit.MINUTES.toNanos(31));releaseFirst.countDown();idle(service);
   assert replies.size()==3 && replies.stream().noneMatch(r -> r.contains("别人的普通消息"))
           : "忙时窗口内续话排队，窗口外未点名的消息绝不出声：" + replies;
   assert calls.get()==3 : "窗口外未点名的消息一次都不该规划（实际 " + calls.get() + " 次）";
   JsonArray queued=snapshots.get("上一句后面的补充");
   boolean hasBob=false, hasAlice=false;
   if(queued!=null) for(JsonElement item:queued) {
       JsonObject entry=item.getAsJsonObject();
       if(!"user".equals(Json.str(entry,"role",""))) continue;
       String id=Json.parse(entry.get("content").getAsString()).getAsJsonObject("speaker").get("id").getAsString();
       if("bob".equals(id)) hasBob=true;
       if("alice".equals(id)) hasAlice=true;
   }
   assert hasAlice && !hasBob : "排队续话保留说话人身份，未点名的旁听发言不进历史（alice=" + hasAlice + " bob=" + hasBob + "）";
   service.accept(ordinary("C","alice","回复后继续"),"回复后继续");idle(service);assert replies.size()==4 : "成功回复续期对话窗口";
   now.addAndGet(TimeUnit.MINUTES.toNanos(31));service.accept(ordinary("C","alice","长时间无互动"),"长时间无互动");idle(service);
   assert replies.size()==4 : "rolling inactivity expiry";
   assert calls.get()==4 : "离开对话窗口后不再规划（实际 " + calls.get() + " 次）";
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
          (p,h,m,o,s)->{boolean first=planned.incrementAndGet()==1;
              // 被点名那次模型说 join=true（进窗）；窗口内的续话相关度只有 10，低于 50 的续话门槛。
              return new ChatActions.Plan("低相关度回应",List.of(),"",first?90:10,first,first);},(e,c,o)->{},e->new JsonObject(),now::get)) {
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
          (p,h,m,o,s)->new ChatActions.Plan("高相关度回应",List.of(),"",100,true,true),(e,c,o)->{},e->new JsonObject(),nowHigh::get)) {
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
  // 新契约（owner 2026-10-04）：主动插话彻底删除——窗口外未点名的群消息不回复、不规划、不进历史，
  // 因此这里不再保留 join/冷却/总静音/第三人称那些旁听用例；被点名必答与 join 决定的对话窗口见下节。
  // 机器自己发出的消息（含 .help 的播报）绝不能再被当成用户消息规划一遍。
  replies.clear();histories.clear();
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m)->{histories.add(h);return "reply "+m;},now::get)) {
   JsonObject own=event("group","Z");own.addProperty("user_id",Json.str(own,"self_id",""));
   service.accept(own,"我上线啦，随时可以叫我。发送 .help 查看指令。");idle(service);
   assert replies.isEmpty() && histories.isEmpty() : "机器人自己的消息必须被忽略：" + replies;
  }
  // 新契约（本次改造的核心验收）：主动插话彻底删除——窗口外未被 @/没叫名字的群消息不回复、不规划、不进历史；
  // 被 @/叫名字一定回复一次，是否把对方拉进 30 分钟对话窗口由模型计划里的 join 决定。
  Path gateRoot=Files.createTempDirectory(Path.of(System.getProperty("bot.test.work","work")),"chat-gate-");
  Json.atomicWrite(gateRoot.resolve("config.json"),new JsonObject());
  Settings gateSettings=new Settings(gateRoot);
  gateSettings.chatSetting("enabled",new JsonPrimitive(true));gateSettings.chatSetting("frequency",new JsonPrimitive(10));
  assert gateSettings.chatContextSeconds()==1800 : "对话窗口仍是 30 分钟";
  replies.clear();histories.clear();AtomicInteger gateCalls=new AtomicInteger();AtomicInteger gateExecuted=new AtomicInteger();
  // ① 窗口外、未被 @ 的普通群消息 → 规划器一次都没被调用、没有任何出站消息、历史里没有这条。
  try(ChatService service=new ChatService(gateSettings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{histories.add(h);gateCalls.incrementAndGet();return new ChatActions.Plan("我不该被叫到。",List.of(),"",90,true,true);},
          (e,commands,o)->gateExecuted.addAndGet(commands.size()),e->new JsonObject(),now::get)) {
   service.accept(ordinary("W","zed","今天天气不错"),"今天天气不错");idle(service);
   assert gateCalls.get()==0 : "窗口外未被点名的群消息不得调用规划器（实际 "+gateCalls.get()+" 次）";
   assert replies.isEmpty() : "窗口外未被点名的群消息不得回复：" + replies;
   assert gateExecuted.get()==0 : "窗口外未被点名的群消息不得执行任何指令";
   JsonObject talk=event("group","W");talk.addProperty("user_id","zed");
   service.accept(talk,"小鸟，在吗");idle(service);
   assert gateCalls.get()==1 && replies.size()==1 : "被点名一定回复一次：" + replies;
   assert histories.size()==1 && !histories.get(0).toString().contains("今天天气不错")
           : "未被点名的旁听消息不得进入对话历史：" + histories.get(0);
  }
  // ② 被 @ 且模型 join=true → 回复一次并进入窗口；随后同一用户不 @ 的消息也会被回复（continuation）。
  replies.clear();histories.clear();gateCalls.set(0);gateExecuted.set(0);
  try(ChatService service=new ChatService(gateSettings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{histories.add(h);gateCalls.incrementAndGet();return new ChatActions.Plan("回复 "+m,List.of(),"",90,true,true);},
          (e,commands,o)->gateExecuted.addAndGet(commands.size()),e->new JsonObject(),now::get)) {
   JsonObject joined=event("group","X");joined.addProperty("user_id","zed");
   service.accept(joined,"小鸟，在吗");idle(service);
   assert replies.size()==1 && gateCalls.get()==1 : "被点名必回一次：" + replies;
   service.accept(ordinary("X","zed","不点名接着说"),"不点名接着说");idle(service);
   assert replies.size()==2 && gateCalls.get()==2 : "join=true 后同一用户不 @ 也要回复（continuation）：" + replies;
  }
  // ③ 被 @ 但模型 join=false → 只回这一句；随后同一用户不 @ 的消息既不回复也不再规划。
  replies.clear();histories.clear();gateCalls.set(0);gateExecuted.set(0);
  try(ChatService service=new ChatService(gateSettings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{histories.add(h);gateCalls.incrementAndGet();return new ChatActions.Plan("只答这一句。",List.of(),"",90,false,true);},
          (e,commands,o)->gateExecuted.addAndGet(commands.size()),e->new JsonObject(),now::get)) {
   JsonObject once=event("group","Y");once.addProperty("user_id","zed");
   service.accept(once,"小鸟，在吗");idle(service);
   assert replies.size()==1 && gateCalls.get()==1 : "被点名必回一次：" + replies;
   service.accept(ordinary("Y","zed","不点名再说一句"),"不点名再说一句");idle(service);
   assert replies.size()==1 : "join=false 只回这一句，不得进入对话窗口：" + replies;
   assert gateCalls.get()==1 : "join=false 后同一用户不 @ 的消息不该再规划（实际 "+gateCalls.get()+" 次）";
   service.accept(once,"小鸟，再问一句");idle(service);
   assert !histories.get(histories.size()-1).toString().contains("不点名再说一句")
           : "join=false 后未被点名的消息不得进入对话历史：" + histories.get(histories.size()-1);
  }
  // ④ 计划不含 join（joinGiven=false）且 interest=90 → 按"续话门槛"兜底算进窗：随后不 @ 的消息照旧回复。
  replies.clear();histories.clear();gateCalls.set(0);gateExecuted.set(0);
  try(ChatService service=new ChatService(gateSettings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{histories.add(h);gateCalls.incrementAndGet();return new ChatActions.Plan("兜底进窗。",List.of(),"",90);},
          (e,commands,o)->gateExecuted.addAndGet(commands.size()),e->new JsonObject(),now::get)) {
   JsonObject high=event("group","P");high.addProperty("user_id","zed");
   service.accept(high,"小鸟，在吗");idle(service);
   assert replies.size()==1 && gateCalls.get()==1 : "被点名必回一次：" + replies;
   service.accept(ordinary("P","zed","不点名接着聊"),"不点名接着聊");idle(service);
   assert replies.size()==2 && gateCalls.get()==2
           : "joinGiven=false 且 interest=90 时按 interest>=50 兜底进窗，continuation 必须回复：" + replies;
  }
  // ⑤ 计划不含 join（joinGiven=false）且 interest=10 → 兜底算不进窗：随后不 @ 的消息不回复也不再规划。
  replies.clear();histories.clear();gateCalls.set(0);gateExecuted.set(0);
  try(ChatService service=new ChatService(gateSettings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{histories.add(h);gateCalls.incrementAndGet();return new ChatActions.Plan("兜底只答这一句。",List.of(),"",10);},
          (e,commands,o)->gateExecuted.addAndGet(commands.size()),e->new JsonObject(),now::get)) {
   JsonObject low=event("group","Q");low.addProperty("user_id","zed");
   service.accept(low,"小鸟，在吗");idle(service);
   assert replies.size()==1 && gateCalls.get()==1 : "被点名必回一次（相关度低也照答）：" + replies;
   service.accept(ordinary("Q","zed","不点名再说一句"),"不点名再说一句");idle(service);
   assert replies.size()==1 : "joinGiven=false 且 interest=10 时兜底不进窗，不得继续回复：" + replies;
   assert gateCalls.get()==1 : "兜底不进窗后同一用户不 @ 的消息不该再规划（实际 "+gateCalls.get()+" 次）";
   service.accept(low,"小鸟，再问一句");idle(service);
   assert !histories.get(histories.size()-1).toString().contains("不点名再说一句")
           : "兜底不进窗时未被点名的消息不得进入对话历史：" + histories.get(histories.size()-1);
  }
  // ⑥ 被点名必答并执行；join=true 进窗后，同一用户不 @ 的续话照旧回复并执行
  replies.clear();gateCalls.set(0);gateExecuted.set(0);
  try(ChatService service=new ChatService(gateSettings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{gateCalls.incrementAndGet();return new ChatActions.Plan("好，这就改。",List.of(".size set 896 512"),"",90,true,true);},
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
  settings.chatSetting("enabled",new JsonPrimitive(true));
  replies.clear();AtomicLong nowDrift=new AtomicLong(now.get());AtomicInteger driftCalls=new AtomicInteger();
  try(ChatService service=new ChatService(settings,(e,segs)->{replies.add(Bot.messageText(segs));return CompletableFuture.completedFuture(null);},
          (p,h,m,o,s)->{boolean first=driftCalls.incrementAndGet()==1;return new ChatActions.Plan("回应",List.of(),"",first?90:5,first,first);},
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
   assert f.command("private",".chat").contains("本会话 开启") && !f.command("private",".chat").contains("主动插话总开关");
   String wakeUsage=f.command("private",".chat wake 50");
   assert wakeUsage.contains("用法：.chat") && !wakeUsage.contains("唤醒基数") : "已删除的 /chat wake 必须被拒绝并给出用法提示：" + wakeUsage;
   String baseUsage=f.command("private",".chat base 0");
   assert baseUsage.contains("用法：.chat") && !baseUsage.contains("主动插话总开关") : "已删除的 /chat base 必须被拒绝：" + baseUsage;
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

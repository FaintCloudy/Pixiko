package cn.szu.bot;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.ChatService;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.sd.UserPromptStore;

public final class ChatActionsTest {
    static JsonObject event(String type, String user) {
        JsonObject event = new JsonObject(); event.addProperty("post_type", "message"); event.addProperty("message_type", type);
        event.addProperty("self_id", "1"); event.addProperty("user_id", user); event.addProperty("group_id", "3");
        event.addProperty("message_id", "origin"); event.addProperty("message", "小鸟，把尺寸调为768×512"); return event;
    }
    static DeepSeekPrompts.Response response(String content) {
        JsonObject choice=new JsonObject();choice.addProperty("finish_reason","stop");
        choice.add("message", DeepSeekPrompts.chatMessage("assistant",content)); JsonArray choices=new JsonArray();choices.add(choice);
        JsonObject output=new JsonObject();output.add("choices",choices);return new DeepSeekPrompts.Response(200,output.toString());
    }
    static void waitIdle(ChatService service) throws Exception {
        var field=ChatService.class.getDeclaredField("executor");field.setAccessible(true); var executor=(ThreadPoolExecutor) field.get(service);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int stable=0;
        while(stable<3) { if(System.nanoTime()>deadline)throw new AssertionError("Chat stuck");
            if(executor.getActiveCount()==0 && executor.getQueue().isEmpty())stable++;else stable=0;Thread.sleep(5); }
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0 && args[0].equals("live")) { live();return; }
        var plain=ChatActions.parse("{\"reply\":\"嗯，今天过得怎样？\",\"execute\":false,\"commands\":[]}"); assert plain.commands().isEmpty();
        var command=ChatActions.parse("{\"reply\":\"我去改一下。\",\"execute\":true,\"commands\":[\".size set 768 512\"]}");assert command.commands().size()==1;
        var undo=ChatActions.parse("{\"reply\":\"我把提示词回退一下。\",\"execute\":true,\"commands\":[\".prompt undo\"]}");assert undo.commands().equals(List.of(".prompt undo"));
        var progress=ChatActions.parse("{\"reply\":\"我看一下进度。\",\"execute\":false,\"commands\":[\".progress\"]}");assert progress.commands().equals(List.of(".progress")) : "/progress 是允许的指令";
        for(String invalid:List.of("{}", "{\"reply\":\"hi\",\"execute\":true,\"commands\":[\"/exec cmd\"]}")) {
            try { ChatActions.parse(invalid);throw new AssertionError("Invalid plan accepted"); } catch(IOException expected) {}
        }
        // Tolerated shapes: commands imply an action, and an empty command list is plain chat.
        assert ChatActions.parse("{\"reply\":\"hi\",\"execute\":false,\"commands\":[\".gen\"]}").commands().equals(List.of(".gen"));
        assert ChatActions.parse("{\"reply\":\"hi\",\"execute\":true,\"commands\":[]}").commands().isEmpty();
        assert ChatActions.parse("{\"reply\":\"hi\",\"commands\":[\".gen\"]}").commands().equals(List.of(".gen"));
        assert ChatActions.parse("```json\n{\"reply\":\"hi\",\"commands\":[]}\n```").commands().isEmpty() : "a fenced JSON reply is accepted";
        // A reply that claims an edit but carries no edit command must be refused, or the image uses the old prompt.
        assert ChatActions.promisesUnappliedEdit("好，场景改成沙滩、服饰换成白色比基尼了", List.of(".gen")) : "unapplied edit claim detected";
        assert !ChatActions.promisesUnappliedEdit("好，场景改成沙滩了", List.of(".infix 把场景改成沙滩", ".gen")) : "an infix command backs the claim";
        assert !ChatActions.promisesUnappliedEdit("嗯，今天过得怎样？", List.of()) : "plain chat makes no claim";
        var workflow=ChatActions.parse("{\"reply\":\"按顺序处理。\",\"execute\":true,\"commands\":[\".lora load exact_name\",\".infix change scene\",\".size set 768 512\",\".gen 2\"],\"search_query\":\"\"}");
        assert workflow.commands().size()==4 && workflow.commands().get(3).equals(".gen 2");
        var adjusted=ChatActions.parse("{\"reply\":\"好\",\"execute\":false,\"commands\":[],\"search_query\":\"\",\"interest\":30,\"wake_adjust\":-20}");
        // wake_adjust 字段已废弃：模型仍返回它时必须被静默忽略——解析照常成功，也不影响其它字段。
        assert adjusted.interest()==30 && adjusted.commands().isEmpty() : "已废弃的 wake_adjust 不得影响其它字段的解析";
        assert ChatActions.parse("{\"reply\":\"好\",\"execute\":false,\"commands\":[],\"search_query\":\"\"}").interest()==100 : "没有 wake_adjust 时按默认值解析";
        try { ChatActions.parse("{\"reply\":\"x\",\"execute\":false,\"commands\":[],\"search_query\":\"\",\"wake_adjust\":80}"); }
        catch(IOException error) { throw new AssertionError("已废弃的 wake_adjust 不该让整条计划解析失败", error); }
        var search=ChatActions.parse("{\"reply\":\"我先查证。\",\"execute\":false,\"commands\":[],\"search_query\":\"current topic\"}");
        assert search.searchQuery().equals("current topic");
        try { ChatActions.parse("{\"reply\":\"bad\",\"execute\":true,\"commands\":[\".gen\"],\"search_query\":\"topic\"}");throw new AssertionError("search and command accepted"); }
        catch(IOException expected) {}
        try(var f=new GenerationPresetTest.Fixture()) {
            JsonObject e=event("private","2");
            f.bot.executeChatCommands(e,List.of(".size set 768 512", ".steps set 25"),new JsonObject());
            assert f.sd.settings().width()==768 && f.sd.parameters().steps()==25;f.replies.clear();
            f.command("private",".style import webui");f.command("private",".style list");JsonObject choices=f.bot.selectionContext(e);
            f.bot.executeChatCommands(e,List.of(".style load #1"),choices);
            assert new UserPromptStore(f.root).prompts("2").positive().contains("replacement"):new UserPromptStore(f.root).prompts("2").positive();f.replies.clear();
            f.bot.executeChatCommands(e,List.of(".style set wrong", ".steps set 80"),new JsonObject());
            assert f.sd.parameters().steps()==25 : "failed command must stop following edits";f.replies.clear();
            f.command("private",".style");
            // 查看状态不再改动 #编号 上下文（这正是"编号混乱"的来源）；让编号真正失效的是列表本身变化。
            f.command("private",".prompt set base, red hair");
            JsonObject promptChoices=f.bot.selectionContext(e);
            f.command("private",".prompt add extra");
            try { f.bot.executeChatCommands(e,List.of(".prompt remove #2"),promptChoices);throw new AssertionError("stale selection accepted"); } catch(IllegalArgumentException expected) {}
            f.bot.executeChatCommands(e,List.of(".map set yh unknown", ".steps set 80"),new JsonObject());
            assert f.sd.parameters().steps()==25 : "admin denial must stop following edits";f.replies.clear();
            Settings settings=new Settings(f.root);settings.chatSetting("frequency",new JsonPrimitive(10));
            AtomicInteger planned=new AtomicInteger();
            try(ChatService service=new ChatService(settings, (ev,segments)->{f.replies.add(Bot.messageText(segments));return CompletableFuture.completedFuture(null);},
                    (personality,history,message,options,speaker)->{planned.incrementAndGet();
                        // 群里被点名才可能被规划；窗口外未点名的消息根本不会走到规划器。
                        return new ChatActions.Plan("尺寸我去改一下。",List.of(".size set 896 512"),"",90,true,true);},
                    f.bot::executeChatCommands,f.bot::selectionContext,System::nanoTime)) {
                JsonObject group=event("group","2"); group.addProperty("message","ordinary");
                service.accept(group,"ordinary");waitIdle(service);
                // 主动插话已删除：没被 @ / 没叫名字的群消息不回复、不规划、不执行任何指令。
                assert planned.get()==0 : "窗口外未被点名的群消息不得调用规划器（实际 "+planned.get()+" 次）";
                assert f.replies.isEmpty() && f.sd.settings().width()!=896 : "未被点名的群消息不得回复、不得执行指令";
                group.addProperty("message","小鸟，把尺寸改成896×512");
                service.accept(group,"小鸟，把尺寸改成896×512");waitIdle(service);
                assert f.sd.settings().width()==896 && planned.get()==1 : "被点名时才执行";
            }
            f.replies.clear();
            f.command("private",".prompt set base, red hair, blue eyes");
            f.command("private",".promptR set blur, bad anatomy");
            assert f.command("private",".prompt").contains("#2 red hair");
            assert f.command("private",".prompt remove #2").contains("已删除：red hair");
            assert new UserPromptStore(f.root).prompts("2").positive().equals("base, blue eyes");
            assert f.command("private",".promptR remove #1").contains("已删除：blur");
            assert new UserPromptStore(f.root).prompts("2").negative().equals("bad anatomy");
            f.command("private",".prompt set base, blue eyed girl");
            // 编号 #3 已经不存在（列表被改小）：报"已变化"或"编号无效"都算对，关键是别删错词条。
            String stale = f.command("private",".prompt remove #3");
            assert stale.contains("已变化") || stale.contains("编号无效") : "stale prompt number must fail: " + stale;
            assert new UserPromptStore(f.root).prompts("2").positive().equals("base, blue eyed girl");
            assert f.command("group",".prompt remove #2").contains("编号无效");
            f.command("private",".preset save numbered preset");f.command("private",".preset list");
            assert f.command("private",".preset remove #1").contains("已删除");
            f.command("private",".function save numbered function");f.command("private",".function list");
            assert f.command("private",".function load #1").contains("已加载");
            assert f.command("private",".function remove #1").contains("已移出");
            Files.writeString(f.root.resolve("data/deepseek-api-key.txt"),"fixture-key");
            var client=new DeepSeekPrompts(f.root,new JsonObject(),(body,key,timeout)->{
                assert !body.has("response_format");
                JsonObject input=Json.parse(body.getAsJsonArray("messages").get(body.getAsJsonArray("messages").size()-1).getAsJsonObject().get("content").getAsString());
                assert input.get("current_message").getAsString().contains("尺寸");assert input.has("selections");
                assert input.has("current_speaker");
                assert input.get("required_output").getAsString().contains("commands");
                assert body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("不同 id 必须视为不同的人");
                assert body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("直接把完整修改要求原意放入 .infix");
                assert body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("使用且只使用 .prompt undo");
                assert body.getAsJsonObject("thinking").get("type").getAsString().equals("disabled");
                assert body.get("reasoning_effort").getAsString().equals("none");
                assert !body.has("max_tokens") : "默认不设输出上限";
                assert !body.toString().contains("fixture-key");
                return response("{\"reply\":\"我去改一下。\",\"execute\":true,\"commands\":[\".size set 768 512\"]}");
            });
            assert client.chatPlan("小鸟小姐",new JsonArray(),"修改尺寸",new JsonObject()).commands().equals(List.of(".size set 768 512"));
            f.replies.clear();
            f.bot.executeChatCommands(e,List.of(".lora list",".size set 640 512"),new JsonObject());
            long workflowDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(f.sd.settings().width()!=640 && System.nanoTime()<workflowDeadline) Thread.sleep(10);
            assert f.sd.settings().width()==640 : "successful async step must release the next workflow step";
            f.replies.clear();
            f.bot.executeChatCommands(e,List.of(".infix 改成夜景",".size set 704 512"),new JsonObject());
            String workflowReply="";
            workflowDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<workflowDeadline && !workflowReply.contains("多步骤指令已停止")) {
                String part=f.replies.poll(100,TimeUnit.MILLISECONDS);if(part!=null)workflowReply+=part;
            }
            assert workflowReply.contains("多步骤指令已停止") && f.sd.settings().width()==640 : "failed async step must stop all remaining commands";
        }
        listContext();
        selectionNumbers();
        additionAudit();
        categorySurgery();
        loraRemovalByRequest();
        System.out.println("ChatActionsTest PASS: plans, dispatch, ordering, permission, failed-step stop, group trigger, stale indices, "
                + "prompt/function/preset numbered remove, last-list numbering, quoted-number guards, LoRA removal by explicit request.");
    }
    /**
     * 真机复现：用户说「删除saku kanb」（两个 LoRA 各取一半名字），改写真的把它们删掉之后，
     * "改写不许丢 LoRA 标签"的安全网**不能**再把它们加回来（以前会，等于没听用户的）。
     */
    private static void loraRemovalByRequest() throws Exception {
        List<String> tags = List.of("<lora:Kanbe_Kotori_1_nai:1>", "<lora:Sakuraba_Victoria_Ruri_1_nai:1>");
        Set<String> removed = Bot.lorasRequestedRemoved("删除saku kanb", tags);
        assert removed.size() == 2 : "两个 LoRA 都要算作用户要求删除：" + removed;
        assert Bot.lorasRequestedRemoved("把 kanbe 去掉", tags).equals(Set.of(tags.get(0))) : "只点名一个就只删一个";
        assert Bot.lorasRequestedRemoved("删除 <lora:Sakuraba_Victoria_Ruri_1_nai:1>", tags).equals(Set.of(tags.get(1)))
                : "写全名也算";
        assert Bot.lorasRequestedRemoved("加一个 kanbe 的 lora", tags).isEmpty() : "加/改不是删除";
        assert Bot.lorasRequestedRemoved("不要动 LoRA，把画面改成夜景", tags).isEmpty()
                : "只提 LoRA 两个字、没提模型名，不算删除（否则会误删）";
        assert Bot.lorasRequestedRemoved("换成水彩风格", tags).isEmpty() : "没点名具体 LoRA 就不动它";

        try (var f = new GenerationPresetTest.Fixture()) {
            JsonObject event = event("private", "2");
            event.addProperty("raw_message", "删除saku kanb");
            f.command("private", ".prompt set 1girl, <lora:Kanbe_Kotori_1_nai:1>, <lora:Sakuraba_Victoria_Ruri_1_nai:1>");
            // 模拟"改写真的删掉了这两个标签"
            f.command("private", ".prompt remove <lora:Kanbe_Kotori_1_nai:1>");
            f.command("private", ".prompt remove <lora:Sakuraba_Victoria_Ruri_1_nai:1>");
            f.replies.clear();
            Set<String> asked = Bot.lorasRequestedRemoved(event.get("raw_message").getAsString(), tags);
            f.bot.restoreLoraTags(event, tags, asked);
            String after = new UserPromptStore(f.root).prompts("2").positive();
            assert after.equals("1girl") : "用户要求删掉的 LoRA 不该被安全网加回来：" + after;
            assert f.replies.isEmpty() : "也不该回一条『已恢复』：" + f.replies;
            // 反过来：用户没提删除时，安全网照旧补回来（这是它本来要防的"改写静默丢标签"）
            f.command("private", ".prompt set 1girl");
            f.replies.clear();
            f.bot.restoreLoraTags(event, tags, Set.of());
            String restored = new UserPromptStore(f.root).prompts("2").positive();
            assert restored.contains(tags.get(0)) && restored.contains(tags.get(1)) : "没要求删除时仍然要恢复：" + restored;
            assert !f.replies.isEmpty() : "恢复时要给出回执";
        }
    }

    /** 按类别筛选词条："保留环境、人物、服饰，去除其他词条"必须由程序判定分类，而不是交给模型随机删。 */
    private static void categorySurgery() throws Exception {
        Path root = Path.of(System.getProperty("bot.home", "."));
        if (!Files.isRegularFile(root.resolve("data/prompt-zh-tags.json"))) return;
        cn.szu.bot.prompt.PromptUsage usage = new cn.szu.bot.prompt.PromptUsage(root);
        Bot.CategorySurgery keep = Bot.parseCategorySurgery("保留环境，人物，服饰，去除其他词条");
        assert keep != null && keep.keepOnly() : "整句是类别筛选要求";
        assert keep.keep().contains("环境") && keep.keep().contains("人物") && keep.keep().contains("服装") : keep;
        String base = "1girl, solo, masterpiece, classroom, school_uniform, long_hair, smile, sitting, holding_book, sunset, window, <lora:test_lora:1>";
        List<String> kept = new ArrayList<>(), removed = new ArrayList<>(), prot = new ArrayList<>();
        String out = Bot.applyCategorySurgery(base, keep, usage, kept, removed, prot);
        assert out.contains("classroom") && out.contains("sunset") && out.contains("window") : out;
        assert out.contains("school_uniform") && out.contains("1girl") : out;
        assert !out.contains("smile") && !out.contains("sitting") && !out.contains("holding_book") : out;
        assert out.contains("<lora:test_lora:1>") && prot.size() == 1 : "LoRA 标签始终保留：" + out;
        assert removed.stream().anyMatch(line -> line.contains("smile") && line.contains("表情")) : removed;
        // 只删某一类时，其他类别与词库外的词条都不动。
        Bot.CategorySurgery drop = Bot.parseCategorySurgery("删除表情和动作词条");
        assert drop != null && !drop.keepOnly() && drop.remove().contains("表情") && drop.remove().contains("动作") : drop;
        String dropped = Bot.applyCategorySurgery(base, drop, usage, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        assert dropped.contains("masterpiece") && dropped.contains("sunset") && !dropped.contains("smile") : dropped;
        // 正常画面要求不能被当成筛选。
        assert Bot.parseCategorySurgery("少女在教室里微笑") == null : "普通要求不触发类别筛选";
        assert Bot.parseCategorySurgery("保留环境，然后加一个女孩") == null : "混着画面内容的要求交给模型";
    }
    /**
     * 依据审查：新加的词条必须能说清来源（拆解某条 / 词条候选 / 你的措辞对应），
     * 说不出来的一律移除并列出——这就是"非拆解步的神秘词条"的拦截点。
     */
    private static void additionAudit() throws Exception {
        Path root = Path.of(System.getProperty("bot.home", "."));
        if (!Files.isRegularFile(root.resolve("data/prompt-zh-tags.json"))) return;
        cn.szu.bot.prompt.PromptUsage usage = new cn.szu.bot.prompt.PromptUsage(root);
        Set<String> allowed = Bot.vocabulary(root, new cn.szu.bot.sd.SdClient.Prompts("", "", "audit"));
        cn.szu.bot.chat.SceneDecomposer.Scene scene = new cn.szu.bot.chat.SceneDecomposer.Scene("测试",
                List.of(new cn.szu.bot.chat.SceneDecomposer.Part("女性趴卧在男性身上", List.of("lying", "on_stomach"))), true);
        List<String> reasons = new ArrayList<>(), removed = new ArrayList<>();
        // 词条来源只有三种：拆解某条、词条候选/扩写、你的措辞对应；其余（flats/girl_on_top/settlement）算无法解释。
        String result = Bot.auditAdditions(
                "1girl, lying, on_stomach, ass_focus, girl_on_top, flats, settlement",
                "1girl", usage, allowed, List.of("女性趴卧在男性身上", "臀部特写"), scene,
                List.of("lying"), List.of("on_stomach"), reasons, removed);
        assert result.contains("lying") && result.contains("on_stomach") && result.contains("ass_focus") : result;
        assert !result.contains("girl_on_top") && !result.contains("flats") && !result.contains("settlement") : result;
        assert reasons.size() == 3 : reasons;
        assert reasons.stream().anyMatch(line -> line.startsWith("lying") && line.contains("拆解") || line.startsWith("lying") && line.contains("候选")) : reasons;
        assert reasons.stream().anyMatch(line -> line.startsWith("on_stomach") && line.contains("场景扩写")) : reasons;
        assert reasons.stream().anyMatch(line -> line.startsWith("ass_focus") && line.contains("你的措辞")) : reasons;
        assert removed.stream().anyMatch(line -> line.contains("flats")) : removed;
        assert removed.stream().anyMatch(line -> line.contains("settlement")) : removed;
        // 用户原有的词条不动，即使它没有任何依据。
        List<String> kept = new ArrayList<>(), dropped = new ArrayList<>();
        String untouched = Bot.auditAdditions("1girl, masterpiece", "1girl, masterpiece", usage, allowed,
                List.of("测试"), scene, List.of(), List.of(), kept, dropped);
        assert untouched.equals("1girl, masterpiece") && dropped.isEmpty() : untouched + " / " + dropped;
    }
    /**
     * 回归：用户说"选择第六十五个"，而引用消息里带着旧列表（第 20 项是别的样式）。
     * 守卫只能看本次请求，且计划里的编号必须用用户给的那个数字。
     */
    private static void selectionNumbers() throws Exception {
        try (var f = new GenerationPresetTest.Fixture()) {
            JsonObject selections = Json.parse("{\"style\":[\"草莓\",\"小鸟\",\"小鸟sex\",\"白河\"]}");
            String composed = "[引用] 样式列表（◆ 本机 1 个）：#1 ◆ 草莓 #2 ○ 小鸟 #3 ○ 小鸟sex #4 ○ 白河\n"
                    + Bot.REQUEST_MARK + "选择#65，然后清空所有视角词，环境词，infix电车痴汉被性骚扰，性交，臀部为主要视角，生成。";
            assert DeepSeekPrompts.currentRequest(composed).startsWith("选择#65") : "只取本次请求正文";
            // 引用里的旧编号不能当成本次要求：加载 #65 是合法的。
            ChatActions.Plan right = new ChatActions.Plan("好，先载入那份样式。", List.of(".style list", ".style load #65", ".infix 电车痴汉", ".gen"), "", 90);
            assert DeepSeekPrompts.mismatchedSelection(right, composed) == null : DeepSeekPrompts.mismatchedSelection(right, composed);
            // 沿用引用/历史里的旧编号则必须拒绝。
            ChatActions.Plan wrong = new ChatActions.Plan("好，先载入那份样式。", List.of(".style list", ".style load #20", ".infix 电车痴汉", ".gen"), "", 90);
            String rejected = DeepSeekPrompts.mismatchedSelection(wrong, composed);
            assert rejected != null && rejected.contains("#65") && rejected.contains("#20") : rejected;
            // 没有编号句子的普通请求不受影响。
            ChatActions.Plan plain = new ChatActions.Plan("好。", List.of(".style load #2"), "", 90);
            assert DeepSeekPrompts.mismatchedSelection(plain, "把第二个样式载进来") == null : "没有 #编号 时不该触发编号守卫";
            assert DeepSeekPrompts.mismatchedSelection(plain, Bot.REQUEST_MARK + "把 #2 样式载进来") == null : "用户给的编号要放行";
            // 引用消息不能驱动基底守卫（引用里出现样式名不算用户这次指名）。
            JsonObject quotedSelections = Json.parse("{\"style\":[\"草莓\",\"小鸟沙滩侧卧\"]}");
            ChatActions.Plan load65 = new ChatActions.Plan("好。", List.of(".style load #65"), "", 90);
            String viaQuote = "[引用] 当前载入样式：小鸟沙滩侧卧（WebUI 样式）\n" + Bot.REQUEST_MARK + "选择#65";
            assert DeepSeekPrompts.missingBasis(load65, quotedSelections, viaQuote) == null
                    : "引用里的样式名不该被当成这次点名的基底：" + DeepSeekPrompts.missingBasis(load65, quotedSelections, viaQuote);
            // 拆解层：清理要求不算画面要素，也不该报"查不到词条"。
            assert cn.szu.bot.chat.SceneDecomposer.isCleanup("删除所有视角词")
                    && cn.szu.bot.chat.SceneDecomposer.isCleanup("清空所有环境词")
                    && !cn.szu.bot.chat.SceneDecomposer.isCleanup("以臀部为主要视角") : "清理要求识别";
            // 搜索结果失效后说"下载第一个"：必须如实说明，不能改派给本机 LoRA 列表去加载。
            JsonObject afterRestart = event("private", "90");
            String guidance = f.bot.staleDownloadGuidance(afterRestart, "下载第一个");
            assert guidance != null && guidance.contains(".lora query") && guidance.contains(".lora load") : guidance;
            assert f.bot.staleDownloadGuidance(afterRestart, "加载第一个") == null : "加载不触发搜索结果提示";
            f.bot.registerLoraSearch(afterRestart, List.of(new cn.szu.bot.civitai.CivitaiClient.SearchResult(11, 12, "森野精华", "Illustrious", "")));
            assert f.bot.staleDownloadGuidance(afterRestart, "下载第一个") == null : "有搜索结果时正常下载";
            // "下载第 N 个"不会顺手预取本机 LoRA 列表（否则编号会被解释成本机列表的第 N 项）。
            JsonObject downloadEvent = event("private", "91");
            f.bot.primeNumberedSelections(downloadEvent, "下载#1");
            JsonObject downloadContext = f.bot.selectionContext(downloadEvent);
            assert !downloadContext.has("lora") : "下载指代不能抓本机 LoRA 列表：" + downloadContext;
        }
    }
    /**
     * 编号列表的上下文：用户刚看过的那份列表才是"第 N 个 / #N"的解释。
     * 这条回归覆盖"刚搜完 Civitai，机器人却按本地 LoRA 列表解释 #2，最后要求澄清并列出本地 LoRA"。
     */
    private static void listContext() throws Exception {
        try (var f = new GenerationPresetTest.Fixture()) {
            JsonObject owner = event("private", "2");
            // 1) 看过样式列表：#编号 的上下文里有 last_list，且给出对应动作。
            f.command("private", ".style import webui");f.command("private", ".style list");
            JsonObject styleContext = f.bot.selectionContext(owner);
            assert styleContext.has("last_list") : "a shown list must be reported to the planner";
            JsonObject styleList = styleContext.getAsJsonObject("last_list");
            assert styleList.get("kind").getAsString().equals("style") : styleList;
            assert styleList.get("action").getAsString().equals(".style load #N") : styleList;
            assert styleList.getAsJsonArray("items").get(0).getAsString().startsWith("#1 ") : styleList;
            assert styleList.get("title").getAsString().contains("样式") : styleList;

            // 2) 刚搜过 Civitai：#2 属于搜索结果，程序不能顺手预取本地 LoRA/样式列表。
            JsonObject searcher = event("private", "78");
            f.bot.registerLoraSearch(searcher, List.of(
                    new cn.szu.bot.civitai.CivitaiClient.SearchResult(11, 12, "森野精华", "Illustrious", ""),
                    new cn.szu.bot.civitai.CivitaiClient.SearchResult(21, 22, "森野 精华 v2", "Pony", "")));
            f.bot.primeNumberedSelections(searcher, "#2");
            JsonObject searchContext = f.bot.selectionContext(searcher);
            assert !searchContext.has("lora") && !searchContext.has("style")
                    : "刚看过 Civitai 搜索结果时不能再抓本地 LoRA/样式列表：" + searchContext;
            JsonObject searchList = searchContext.getAsJsonObject("last_list");
            assert searchList.get("kind").getAsString().equals("civitai") : searchList;
            assert searchList.get("action").getAsString().equals(".lora download #N") : searchList;
            assert searchList.getAsJsonArray("items").get(1).getAsString().equals("#2 森野 精华 v2（基础模型：Pony）") : searchList;
            // 模型没给出指令时的兜底提示：必须点名这份列表和对应动作。
            String nudge = Bot.selectionNudge(searchContext, "第二个");
            assert nudge != null && nudge.contains("Civitai") && nudge.contains(".lora download #N") : nudge;
            assert nudge.contains("#2 森野 精华 v2") : nudge;
            assert Bot.selectionNudge(searchContext, "你好呀") == null : "普通闲聊不该触发编号兜底";
            assert Bot.selectionNudge(new JsonObject(), "#2") == null : "没有列表时不能触发编号兜底";
            // 整句只是指代时，由程序把编号翻成指令：列表种类与动作都来自 last_list。
            Bot.ShownSelection picked = Bot.resolveShownSelection(searchContext, "第二个");
            assert picked != null && picked.command().equals(".lora download #2") : picked;
            assert picked.label().contains("森野 精华 v2") : picked;
            assert Bot.resolveShownSelection(searchContext, "就选 #2 吧").command().equals(".lora download #2") : "口语化指代也要认";
            Bot.ShownSelection overflow = Bot.resolveShownSelection(searchContext, "#9");
            assert overflow != null && overflow.clarification() && overflow.label().contains("只有 2 项") : "超出列表长度的编号要如实说明：" + overflow;
            assert Bot.resolveShownSelection(searchContext, "第二个有什么特点") == null : "带别的内容就交给模型";
            // 名字指代：唯一命中由程序定编号；与编号对不上就拒绝执行（实测模型会编造"#62=某名字"）。
            JsonObject named = event("private", "87");
            f.bot.numbered(named, "style", List.of("草莓", "sy", "sysex", "白河沙滩", "白河2", "sy军营"),
                    List.of("草莓", "sy", "sysex", "白河沙滩", "白河2", "sy军营"));
            Bot.ShownSelection byName = Bot.resolveShownSelection(f.bot.selectionContext(named), "选择sy军");
            assert byName != null && byName.command().equals(".style load #6") : "名字唯一命中时由程序定编号：" + byName;
            assert byName.label().equals("sy军营") : byName;
            Bot.ShownSelection both = Bot.resolveShownSelection(f.bot.selectionContext(named), "#6 sy军营");
            assert both != null && both.command().equals(".style load #6") : "编号与名字一致时照常执行：" + both;
            Bot.ShownSelection mismatch = Bot.resolveShownSelection(f.bot.selectionContext(named), "#2 sy军营");
            assert mismatch != null && mismatch.clarification() : "编号与名字对不上必须拒绝执行：" + mismatch;
            assert mismatch.label().contains("#2") && mismatch.label().contains("sy") && mismatch.label().contains("没有被改动") : mismatch.label();
            Bot.ShownSelection ambiguous = Bot.resolveShownSelection(f.bot.selectionContext(named), "用白河");
            assert ambiguous != null && ambiguous.clarification() && ambiguous.label().contains("#4") && ambiguous.label().contains("#5")
                    : "名字有歧义要列出候选编号：" + ambiguous;
            assert Bot.resolveShownSelection(f.bot.selectionContext(named), "白河沙滩怎么样") == null : "没有挑选动词时交给模型";
            JsonObject presetEvent = event("private", "82");
            f.bot.numbered(presetEvent, "preset", List.of("竖图参数", "横图参数"), List.of("竖图参数", "横图参数"));
            Bot.ShownSelection preset = Bot.resolveShownSelection(f.bot.selectionContext(presetEvent), "第一个");
            assert preset != null && preset.command().equals(".preset load #1") : preset;
            JsonObject samplerEvent = event("private", "83");
            f.bot.numbered(samplerEvent, "sampler", List.of("Euler a", "Euler"), List.of("Euler a", "Euler"));
            Bot.ShownSelection sampler = Bot.resolveShownSelection(f.bot.selectionContext(samplerEvent), "选第 2 个");
            assert sampler != null && sampler.command().equals(".sampler set #2") : sampler;
            JsonObject modelEvent = event("private", "84");
            f.bot.numbered(modelEvent, "model", List.of("Model A [aaaa]", "Model B [bbbb]"), List.of("Model A [aaaa]", "Model B [bbbb]"));
            assert Bot.resolveShownSelection(f.bot.selectionContext(modelEvent), "#1").command().equals(".model set #1") : "基础模型编号";
            JsonObject charEvent = event("private", "85");
            f.bot.numbered(charEvent, "char", List.of("lora|Amanogawa_Saya_2_nai", "style|replace"), List.of("LoRA：Saya", "样式：replace"));
            assert Bot.resolveShownSelection(f.bot.selectionContext(charEvent), "第二个").command().equals(".char apply #2") : "角色候选编号";
            // 纯编号指代直接由程序执行：规划模型即使挑错列表也不该被采信。
            JsonObject shortcut = event("private", "86");
            f.bot.numbered(shortcut, "preset", List.of("竖图参数", "横图参数"), List.of("竖图参数", "横图参数"));
            AtomicInteger planned = new AtomicInteger();
            try (ChatService service = new ChatService(new Settings(f.root),
                    (ev, segments) -> { f.replies.add(Bot.messageText(segments)); return CompletableFuture.completedFuture(null); },
                    (personality, history, message, choices, speaker) -> { planned.incrementAndGet(); return new ChatActions.Plan("嗯。", List.of(".style list"), "", 90); },
                    f.bot::executeChatCommands, f.bot::selectionContext, System::nanoTime)) {
                service.accept(shortcut, "第一个");
                waitIdle(service);
            }
            assert planned.get() == 0 : "整句只是编号指代时不该再调用规划模型";
            String shortcutReply = "";
            for (String part = f.replies.poll(); part != null; part = f.replies.poll()) shortcutReply += part + "\n";
            assert shortcutReply.contains("竖图参数") : "回执与回复都要点明指的那一项：" + shortcutReply;
            // 这条计划必须通过两道一致性校验：它是"下载搜索结果的第 2 项"，不是"加载本地 LoRA"。
            ChatActions.Plan plan = new ChatActions.Plan("好，下载第二个。", List.of(".lora download #2"), "", 90);
            assert DeepSeekPrompts.missingBasis(plan, searchContext, "第二个") == null
                    : DeepSeekPrompts.missingBasis(plan, searchContext, "第二个");
            assert DeepSeekPrompts.unrequestedBasis(plan, searchContext, "第二个") == null
                    : DeepSeekPrompts.unrequestedBasis(plan, searchContext, "第二个");
            // 3) 用户明确点名另一种列表时，才允许换成那一份（这里点的是样式列表）。
            JsonObject local = event("private", "79");
            f.bot.registerLoraSearch(local, List.of(new cn.szu.bot.civitai.CivitaiClient.SearchResult(11, 12, "森野精华", "Illustrious", "")));
            f.bot.primeNumberedSelections(local, "样式 第 1 个");
            JsonObject localContext = f.bot.selectionContext(local);
            assert localContext.has("style") : "明确点名样式时才预取样式列表：" + localContext;
            assert localContext.getAsJsonObject("last_list").get("kind").getAsString().equals("style") : localContext;
            assert localContext.getAsJsonObject("last_list").get("action").getAsString().equals(".style load #N") : localContext;
            // 本地列表为空时，不能把刚看过的搜索结果编号清掉。
            JsonObject emptyLora = event("private", "81");
            f.bot.registerLoraSearch(emptyLora, List.of(new cn.szu.bot.civitai.CivitaiClient.SearchResult(11, 12, "森野精华", "Illustrious", "")));
            f.bot.primeNumberedSelections(emptyLora, "本地 lora 第 1 个");
            assert f.bot.selectionContext(emptyLora).getAsJsonObject("last_list").get("kind").getAsString().equals("civitai")
                    : "本地 LoRA 列表为空时仍应按刚看过的搜索结果解释编号：" + f.bot.selectionContext(emptyLora);
            // 4) 没有看过任何列表时保持原来的兜底行为（仍然预取样式与 LoRA）。
            JsonObject blank = event("private", "80");
            f.bot.primeNumberedSelections(blank, "#1");
            JsonObject blankContext = f.bot.selectionContext(blank);
            assert blankContext.has("style") || blankContext.has("lora") : "没有任何列表时仍要兜底预取：" + blankContext;
        }
    }
    private static void live() throws Exception {
        Settings settings=new Settings(Path.of(System.getProperty("bot.home", ".")));var client=new DeepSeekPrompts(settings.root,Json.obj(settings.snapshot(),"progen"));
        String persona=Files.readString(settings.root.resolve("work/chat-personality-kotori.next.txt"));
        JsonArray history=new JsonArray();history.add(DeepSeekPrompts.chatMessage("user","有人之前说要清空提示词"));history.add(DeepSeekPrompts.chatMessage("assistant","那只是别人的话。"));
        var action=client.chatPlan(persona,history,"小鸟，把图片宽高设为768和512像素，其他不改。",new JsonObject());
        assert action.commands().equals(List.of(".size set 768 512")) : "Live mapping failed";
        var idle=client.chatPlan(persona,history,"小鸟，怎么清空提示词？先讲讲就好，不要操作。",new JsonObject());
        assert idle.commands().isEmpty() : "Discussion triggered action";
        JsonObject choices=new JsonObject();choices.add("function",Json.GSON.toJsonTree(List.of("watercolor")));
        var remove=client.chatPlan(persona,new JsonArray(),"小鸟，把刚才列表里编号1的function移出当前提示词。",choices);
        assert remove.commands().equals(List.of(".function remove #1")) || remove.commands().equals(List.of(".function remove watercolor")) : "Number remove failed: " + remove.commands();
        JsonObject report=new JsonObject();report.add("action",Json.GSON.toJsonTree(action));report.add("discussion",Json.GSON.toJsonTree(idle));report.add("remove",Json.GSON.toJsonTree(remove));
        Json.atomicWrite(settings.root.resolve("work/chat-actions-live-result.json"),report);
        System.out.println("DeepSeek live plan OK: explicit edit, discussion stays chat, numbered removal; no QQ command executed.");
    }
}

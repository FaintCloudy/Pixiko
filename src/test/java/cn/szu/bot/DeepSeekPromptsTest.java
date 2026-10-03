package cn.szu.bot;
import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;
public final class DeepSeekPromptsTest {
    public static void main(String[] args) throws Exception {
        try (var f = new GenerationPresetTest.Fixture()) {
            assert f.command("private",".infix filter").contains("标准词库约束（本会话）：关闭") : "the vocabulary constraint is off by default (free rewrite)";
            String filterScope = f.command("private",".infix filter");
            assert filterScope.contains("filter on") && filterScope.contains("data/prompt-tags.txt") : "the constraint can be switched per conversation: " + filterScope;
            // The switch belongs to owner/admin and only affects this conversation.
            assert f.command("private",".infix filter on").contains("标准词库约束已开启") : "owner can switch the constraint on";
            assert new Settings(f.root).infixFilterEnabled("1:private:2") : "the switch persists";
            String closed = f.command("private",".infix filter");
            assert closed.contains("标准词库约束（本会话）：开启") && closed.contains("filter off") : "enabled state is reported with a way back: " + closed;
            assert f.command("private",".infix filter off").contains("标准词库约束已关闭") : "owner can switch it back off";
            assert !new Settings(f.root).infixFilterEnabled("1:private:2") : "disabled state persists";
            assert f.command("private",".infix filter 随便").contains("用法：.infix filter") : "an unknown argument is rejected";
            f.command("private", ".prompt set original positive"); f.command("private", ".promptR set original negative");
            for (String command : List.of(".prompt set", ".promptR set", ".prompt edit", ".prompt edit new", ".promptR edit", ".prompt clear unexpected"))
                f.command("group", command);
            assert new UserPromptStore(f.root).prompts("2").positive().equals("original positive") && new UserPromptStore(f.root).prompts("2").negative().equals("original negative") : "invalid commands must never clear prompts";
            f.command("private", ".prompt clear"); assert new UserPromptStore(f.root).prompts("2").positive().isEmpty(); assert !new UserPromptStore(f.root).prompts("2").negative().isEmpty();
            f.command("private", ".promptR clear"); assert new UserPromptStore(f.root).prompts("2").negative().isEmpty();
            assert f.command("private", "/generate 1").contains("格式不正确");
            for (String type : List.of("group", "private")) {
                assert f.command(type, ".progen 雨夜的自行车").contains("正在通过 DeepSeek");
                String failure = f.replies.poll(3, java.util.concurrent.TimeUnit.SECONDS);
                assert failure != null && failure.contains("未配置") : "async error routed to caller";
            }
            // /chat infix rewrites the setup prompt through the same bounded async worker.
            String personalityBefore=new Settings(f.root).chatPersonality();
            assert f.command("private", ".chat infix").contains("用法：.chat infix");
            assert f.command("private", ".chat infix 更活泼一些，少一点安慰模板").contains("正在通过 DeepSeek");
            String chatInfixFailure = f.replies.poll(3, java.util.concurrent.TimeUnit.SECONDS);
            assert chatInfixFailure != null && chatInfixFailure.contains("未配置") : "chat infix error routed to caller";
            assert new Settings(f.root).chatPersonality().equals(personalityBefore) : "failed edit must not touch the personality";
            String fakeKey = "fixture-secret-never-log"; Files.writeString(f.root.resolve("data/deepseek-api-key.txt"), fakeKey);
            var client = new DeepSeekPrompts(f.root, new JsonObject(), (body, key, timeout) -> {
                assert key.equals(fakeKey); assert body.get("model").getAsString().equals("deepseek-flash");
                assert body.getAsJsonObject("thinking").get("type").getAsString().equals("disabled");
                assert body.get("reasoning_effort").getAsString().equals("none");
                assert body.getAsJsonArray("messages").size() == 2;
                assert body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString().equals("雨夜的自行车");
                assert !body.toString().contains(fakeKey) && !body.toString().contains("original positive");
                return response("stop", "{\"positive\":\"red bicycle, rainy night\",\"negative\":\"blur\"}");
            });
            assert client.generate("雨夜的自行车").positive().equals("red bicycle, rainy night");
            // Personality editing returns the whole setup prompt and is applied with a compare-and-set guard.
            f.command("private",".chat personality 温柔简洁");
            var personaCalls=new java.util.concurrent.atomic.AtomicInteger();
            var personaClient=new DeepSeekPrompts(f.root,new JsonObject(),(body,key,timeout)->{
                assert !body.has("max_tokens") : "默认不设输出上限";
                String rules=body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
                assert rules.contains("不泄露密钥") && rules.contains("personality");
                JsonObject input=Json.parse(body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
                assert input.get("instruction").getAsString().equals("更活泼");
                assert input.get("personality").getAsString().equals("温柔简洁");
                personaCalls.incrementAndGet();
                return response("stop","{\"personality\":\"温柔简洁，也更活泼\"}");
            });
            assert personaClient.editPersonality("更活泼","温柔简洁").equals("温柔简洁，也更活泼");
            assert Bot.applyChatPersonality(new Settings(f.root),"温柔简洁","温柔简洁，也更活泼").equals("温柔简洁，也更活泼");
            assert new Settings(f.root).chatPersonality().equals("温柔简洁，也更活泼");
            try { Bot.applyChatPersonality(new Settings(f.root),"过期设定","不应写入"); throw new AssertionError("stale personality accepted"); }
            catch (IllegalStateException expected) { assert new Settings(f.root).chatPersonality().equals("温柔简洁，也更活泼"); }
            try { personaClient.editPersonality("更活泼",""); throw new AssertionError("blank personality accepted"); }
            catch (IOException expected) { assert expected.getMessage().contains("为空"); }
            assert personaCalls.get()==1 : "only the valid request reaches the API";
            var chatCalls=new java.util.concurrent.atomic.AtomicInteger();
            var chatClient=new DeepSeekPrompts(f.root,new JsonObject(),(body,key,timeout)->{
                assert !body.has("response_format") : "chat planner must avoid DeepSeek's blank JSON-mode responses";
                boolean constrained=false;
                for (JsonElement message : body.getAsJsonArray("messages")) {
                    String content=message.getAsJsonObject().get("content").getAsString();
                    if (content.contains("required_output") && content.contains("commands")) { constrained=true; break; }
                }
                assert constrained : "the request must state the required JSON shape";
                if(chatCalls.incrementAndGet()==1) return response("stop","   ");
                return response("stop","{\"reply\":\"收到\",\"execute\":false,\"commands\":[]}");
            });
            // L1 日常兜底只兜"几乎没有内容"的回复：有内容的短句保持原样（语料里句号收尾占 50%、
            // 46% 的台词不超过 8 字，含 ！ 的只有 8%），纯省略号/单个语气词才补一句短断言。
            assert chatClient.chatPlan("personality",new JsonArray(),"hello",new JsonObject()).reply().equals("收到")
                    : "有内容的短回复不该被改成感叹号";
            assert chatCalls.get()==2 : "invalid planner response should be retried";
            // Two invalid planner replies degrade to a plain chat answer instead of dropping the turn.
            // Stepwise planning engages only for multi-clause requests, so simple turns stay single-call.
            assert DeepSeekPrompts.needsSplit("把地点改成沙滩，服饰换成白色褶边比基尼，然后生成") : "a multi-clause request is split";
            assert DeepSeekPrompts.needsSplit("先加载第五个样式然后按拉练调整再生成") : "sequential wording is split";
            assert !DeepSeekPrompts.needsSplit("你好呀") : "a short greeting is not split";
            assert !DeepSeekPrompts.needsSplit("把光照调暖一点") : "a single short instruction is not split";            var degradedCalls=new java.util.concurrent.atomic.AtomicInteger();
            var degradedClient=new DeepSeekPrompts(f.root,new JsonObject(),(body,key,timeout)->{
                int call=degradedCalls.incrementAndGet();
                if(call<=2) return response("stop","{}");
                return response("stop","降级回答");
            });
            var degraded=degradedClient.chatPlan("personality",new JsonArray(),"hello",new JsonObject());
            // 降级回复是有内容的短句，L1 不再改它（只有纯省略号/单个语气词才兜底）。
            assert degraded.reply().equals("降级回答") && degraded.commands().isEmpty() : "invalid plans must degrade to a plain reply";
            assert degraded.interest()==100 : "a degraded reply is always sent";
            assert degradedCalls.get()==5 : "four planner attempts plus one plain fallback: " + degradedCalls.get();
            var researchCalls=new java.util.concurrent.atomic.AtomicInteger();
            var researchClient=new DeepSeekPrompts(f.root,new JsonObject(),(body,key,timeout)->{
                JsonObject input=Json.parse(body.getAsJsonArray("messages").get(body.getAsJsonArray("messages").size()-1).getAsJsonObject().get("content").getAsString());
                if(researchCalls.incrementAndGet()==1) {
                    assert !input.has("web_research");
                    return response("stop","{\"reply\":\"先查证\",\"execute\":false,\"commands\":[],\"search_query\":\"latest release\"}");
                }
                assert input.get("web_research").getAsString().contains("https://example.com/source");
                assert body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("减少玩笑");
                return response("stop","{\"reply\":\"认真回答 https://example.com/source\",\"execute\":false,\"commands\":[],\"search_query\":\"\"}");
            },query->{assert query.equals("latest release");return "[1] source\nURL: https://example.com/source\n摘要: verified";});
            var researched=researchClient.chatPlan("personality",new JsonArray(),"最新版本是什么",new JsonObject());
            assert researched.reply().contains("example.com/source") && researchCalls.get()==2;
            var authCalls=new java.util.concurrent.atomic.AtomicInteger();
            try {
                new DeepSeekPrompts(f.root,new JsonObject(),(body,key,timeout)->{
                    authCalls.incrementAndGet();return new DeepSeekPrompts.Response(401,"");
                }).chatPlan("personality",new JsonArray(),"hello",new JsonObject());
                throw new AssertionError("401 accepted");
            } catch(IOException expected) { assert authCalls.get()==1 : "authentication failures must not be retried"; }
            f.command("private", ".prompt set example");
            f.command("private", ".function save Example function");
            assert f.command("private", ".function list").contains("#1 Example function");
            assert f.command("private", ".function prompt #1").contains("example");
            // 编号不再要求用户先查询：机器人会按需拉取当前列表，因此别的会话也能解析 #1。
            assert f.command("group", ".function prompt #1").contains("example");
            assert f.command("private", ".function load #1").contains("已加载");
            assert f.command("private", ".function active").contains("#1");
            assert f.command("private", ".function remove #1").contains("已移出");
            assert f.command("private", ".function prompt #999").contains("编号无效");
            // 样式只有机器人这一份：先 .style import webui 把 WebUI 里的预设搬进来，再按 #编号使用。
            assert f.command("private", ".style import webui").contains("导入 1 个") : "webui presets migrate into the bot library";
            assert f.command("private", ".style list").contains("#1 replace") : "style list shows the migrated style";
            assert !f.command("private", ".style list").contains("（WebUI）") : "style list has no WebUI section";
            assert f.command("private", ".style prompt #1").contains("replacement");
            assert f.command("private", ".style load #1").contains("已用样式「replace」替换") : "loading a style writes its text into the prompt";
            assert f.command("private", ".sampler list").contains("#1 Euler a");
            assert f.command("private", ".sampler set #2").contains("Euler");
            assert f.command("private", ".model list").contains("#1 Model A");
            assert f.command("private", ".model set #2").contains("Model B");
            f.command("private", ".preset save Numbered preset");
            assert f.command("private", ".preset list").contains("#1 Numbered preset");
            assert f.command("private", ".preset load #1").contains("已加载");
            var before = new UserPromptStore(f.root).prompts("2");
            Files.createDirectories(f.root.resolve("data"));
            Files.writeString(f.root.resolve("data/prompt-tags.txt"),"night\nblur\nlarge_breasts\n");
            var editing = new DeepSeekPrompts(f.root, new JsonObject(), (body,key,timeout) -> {
                String rules=body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
                // 默认（自由改写）链路不再强制词库：要求模型按自己的判断改，并允许自然语言短语。
                assert !rules.contains("canonical Danbooru") && !rules.contains("allowed_tags_hint")
                        : "the free-form rewrite must not send the vocabulary constraint";
                assert rules.contains("natural language") : "the free-form rewrite allows natural language: " + rules;
                JsonObject input = Json.parse(body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
                assert input.get("positive").getAsString().equals(before.positive());
                assert input.get("instruction").getAsString().equals("改成夜晚");
                assert !input.has("allowed_tags_hint") && !input.has("rejected_terms") : "optional hint fields stay absent";
                return response("stop", "{\"positive\":\"night, large_breasts\",\"negative\":\"blur\"}");
            });
            // First-pass hints are copied verbatim, and a rejection drives exactly one bounded rewrite.
            var hinted = new DeepSeekPrompts(f.root, new JsonObject(), (body,key,timeout) -> {
                String rules=body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
                assert rules.contains("allowed_tags_hint") && rules.contains("copied verbatim") : "hint rules must be sent with hints";
                JsonObject input=Json.parse(body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
                assert input.getAsJsonArray("allowed_tags_hint").get(0).getAsString().equals("night");
                assert input.getAsJsonArray("rejected_terms").get(0).getAsString().equals("invented_phrase");
                return response("stop","{\"positive\":\"night, large_breasts\",\"negative\":\"blur\"}");
            });
            assert hinted.edit("改成夜晚", before, List.of("night","blur"), List.of("invented_phrase")).positive().equals("night, large_breasts");
            var edited = editing.edit("改成夜晚", before);
            // Chinese meaning matches from the local dictionary become first-pass hints, filtered by the vocabulary.
            Json.atomicWrite(f.root.resolve("data/prompt-usage.json"), Json.parse("""
                {"version":1,"source":"fixture","categories":[{"name":"场景","children":[],"tags":[
                {"prompt":"night","meaning":"夜晚"},{"prompt":"night_sky","meaning":"夜晚的天空"},
                {"prompt":"invented_phrase_x","meaning":"夜晚的发明词"},{"prompt":"blur","meaning":"模糊"}]}]}"""));
            var allowed=Bot.vocabulary(f.root, before);
            var hints=Bot.infixHints(f.root,"把白天改成夜晚",allowed);
            assert hints.contains("night") : "Chinese meaning match must yield the canonical tag: " + hints;
            assert !hints.contains("invented_phrase_x") : "hints outside the vocabulary must be dropped: " + hints;
            assert hints.size()<=160 : "hint lists must stay bounded";
            assert Bot.filterInfixVocabulary(f.root, before, edited).rejected().isEmpty();
            Bot.applyPersonalInfix(new UserPromptStore(f.root), "2", before, edited);
            assert new UserPromptStore(f.root).prompts("2").positive().equals(edited.positive());
            var afterEdit=new UserPromptStore(f.root).prompts("2");
            var partial=Bot.filterInfixVocabulary(f.root, afterEdit,new DeepSeekPrompts.Result("night, invented_phrase, (large_breasts:1.2)","blur, another_fake"));
            assert partial.result().positive().equals("night, (large_breasts:1.2)") && partial.result().negative().equals("blur");
            assert partial.rejected().equals(List.of("正向：invented_phrase","反向：another_fake"));
            // 纯出图请求（用户实测："生成。"被模型漏掉、四次重试都是空 commands，最后回"没有解析出可执行的指令"）。
            // 现在由程序直接给出 .gen，不再经过模型。
            assert DeepSeekPrompts.pureGenerationPlan("生成。").commands().equals(List.of(".gen"));
            assert DeepSeekPrompts.pureGenerationPlan("生成").commands().equals(List.of(".gen"));
            assert DeepSeekPrompts.pureGenerationPlan("出图").commands().equals(List.of(".gen"));
            assert DeepSeekPrompts.pureGenerationPlan("再来一张").commands().equals(List.of(".gen"));
            assert DeepSeekPrompts.pureGenerationPlan("继续生成").commands().equals(List.of(".gen"));
            assert DeepSeekPrompts.pureGenerationPlan("帮我生成").commands().equals(List.of(".gen"));
            assert DeepSeekPrompts.pureGenerationPlan("生成三张").commands().equals(List.of(".gen 3"));
            assert DeepSeekPrompts.pureGenerationPlan("生成 10 张").commands().equals(List.of(".gen 10"));
            assert DeepSeekPrompts.pureGenerationPlan("画两张").commands().equals(List.of(".gen 2"));
            assert DeepSeekPrompts.pureGenerationPlan("生成三张，地点改成海边") == null : "混合要求仍然交给模型";
            assert DeepSeekPrompts.pureGenerationPlan("怎么生成") == null : "提问不是出图请求";
            assert DeepSeekPrompts.pureGenerationPlan("先不要生成") == null : "否定不算出图请求";
            assert DeepSeekPrompts.pureGenerationPlan("生成失败了吗") == null : "追问不算出图请求";
            assert DeepSeekPrompts.chineseCount("三") == 3 && DeepSeekPrompts.chineseCount("十") == 10
                    && DeepSeekPrompts.chineseCount("十五") == 15 && DeepSeekPrompts.chineseCount("二十") == 20
                    && DeepSeekPrompts.chineseCount("二十三") == 23 && DeepSeekPrompts.chineseCount("12") == 12
                    && DeepSeekPrompts.chineseCount("十几") == -1 : "中文数量词解析";
            // 端到端：chatPlan 命中纯出图句式时一次模型都不调。
            java.util.concurrent.atomic.AtomicInteger planCalls = new java.util.concurrent.atomic.AtomicInteger();
            var directPlanner = new DeepSeekPrompts(f.root, new JsonObject(), (body,key,timeout) -> {
                planCalls.incrementAndGet();
                return response("stop", "{\"reply\":\"好\",\"execute\":true,\"commands\":[\".gen\"],\"search_query\":\"\",\"interest\":90}");
            });
            var directPlan = directPlanner.chatPlan("性格", new JsonArray(), "生成三张", new JsonObject(), new JsonObject());
            assert planCalls.get() == 0 : "纯出图请求不应该调用模型";
            assert directPlan.commands().equals(List.of(".gen 3")) : "直接出图计划：" + directPlan.commands();
            try { Bot.applyPersonalInfix(new UserPromptStore(f.root), "2", before, new DeepSeekPrompts.Result("stale", "")); throw new AssertionError("stale update accepted"); }
            catch (IllegalStateException expected) { assert new UserPromptStore(f.root).prompts("2").positive().equals(edited.positive()); }
            assert Files.isRegularFile(f.root.resolve("data/prompts/2.history.json")) : "undo history is per user";
            assert f.command("private",".prompt undo").contains("已回退到上一次 prompt");
            assert new UserPromptStore(f.root).prompts("2").positive().equals(before.positive()) && new UserPromptStore(f.root).prompts("2").negative().equals(before.negative());
            // 多步回退：每次修改压入一步，可以连续回退；身份相同的历史会被跳过，不会来回互换。
            f.command("private",".prompt add first_marker");
            f.command("private",".prompt add second_marker");
            String firstUndo = f.command("private",".prompt undo");
            assert firstUndo.contains("已回退到上一次 prompt") && firstUndo.contains("还可回退") : "undo reports the remaining depth: " + firstUndo;
            assert new UserPromptStore(f.root).prompts("2").positive().contains("first_marker")
                    && !new UserPromptStore(f.root).prompts("2").positive().contains("second_marker") : "first undo removes only the newest edit";
            String secondUndo = f.command("private",".prompt undo");
            assert secondUndo.contains("已回退到上一次 prompt") : "a second undo walks further back: " + secondUndo;
            assert !new UserPromptStore(f.root).prompts("2").positive().contains("first_marker") : "second undo removed the earlier edit";
            // 历史是有限的：一直回退最终会明确报告"没有可回退"，而不是来回互换。
            int undos = 0;
            while (undos < UserPromptStore.MAX_HISTORY + 2 && !f.command("private",".prompt undo").contains("没有可回退")) undos++;
            assert undos >= 1 : "history keeps unwinding until it runs out（实际再多回退 " + undos + " 步）";
            assert f.command("private",".prompt undo").contains("没有可回退") : "an exhausted history is reported";
            // 栈式计数（独立会话，不受上面历史影响）：三步修改 → 连续回退，剩余步数递减到 0。
            UserPromptStore stack = new UserPromptStore(f.root);
            stack.replace("depth_test", new SdClient.Prompts("one", "n1", "t"));
            stack.replace("depth_test", new SdClient.Prompts("two", "n2", "t"));
            stack.replace("depth_test", new SdClient.Prompts("three", "n3", "t"));
            assert stack.undo("depth_test").remaining() == 2 && stack.prompts("depth_test").positive().equals("two") : "first undo reports two steps left";
            assert stack.undo("depth_test").remaining() == 1 && stack.prompts("depth_test").positive().equals("one") : "second undo reports one step left";
            assert stack.undo("depth_test").remaining() == 0 && stack.prompts("depth_test").positive().isEmpty() : "third undo reaches the original empty prompt";
            try { stack.undo("depth_test"); throw new AssertionError("undo past the beginning accepted"); }
            catch (java.io.IOException expected) { assert expected.getMessage().contains("没有可回退"); }
            assert f.command("private",".promptR undo").contains("请使用 .prompt undo");
            assert new Settings(f.root).imageCount() == 300;
            assert Bot.imageBatches(List.of(Path.of("a/1.png"),Path.of("a/2.png"),Path.of("b/1.png")),300).size()==1;
            for (int status : new int[]{401,402,429,500}) {
                try { new DeepSeekPrompts(f.root, new JsonObject(), (b,k,t) -> new DeepSeekPrompts.Response(status, fakeKey)).generate("scene"); throw new AssertionError(); }
                catch (IOException e) { assert e.getMessage().contains("HTTP " + status) && !e.toString().contains(fakeKey); }
            }
            for (String output : List.of("not json", "{}", "{\"positive\":1,\"negative\":\"x\"}")) {
                try { new DeepSeekPrompts(f.root, new JsonObject(), (b,k,t)->response("stop",output)).generate("scene"); throw new AssertionError(); }
                catch (IOException expected) { assert !expected.toString().contains(fakeKey); }
            }
            try { new DeepSeekPrompts(f.root, new JsonObject(), (b,k,t)->response("length","{\"positive\":\"x\",\"negative\":\"y\"}")).generate("scene"); throw new AssertionError(); }
            catch (IOException expected) {}
            try { new DeepSeekPrompts(f.root, new JsonObject(), (b,k,t)->{throw new IOException(fakeKey);}).generate("scene"); throw new AssertionError(); }
            catch (IOException expected) { assert !expected.toString().contains(fakeKey) && expected.getCause() == null; }
        }
        System.out.println("DeepSeekPromptsTest PASS: command renames, explicit clear, group/private async reply, JSON validation, errors, credential redaction.");
    }
    private static DeepSeekPrompts.Response response(String reason,String output) {
        JsonObject message=new JsonObject();message.addProperty("content",output);
        JsonObject choice=new JsonObject();choice.addProperty("finish_reason",reason);choice.add("message",message);
        JsonArray choices=new JsonArray();choices.add(choice);JsonObject root=new JsonObject();root.add("choices",choices);
        return new DeepSeekPrompts.Response(200,root.toString());
    }
}

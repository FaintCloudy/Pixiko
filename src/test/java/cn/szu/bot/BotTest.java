package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;

/** Command integration tests use an isolated SD HTTP stub and never contact QQ or real SD. */
public final class BotTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    record Reply(JsonObject event, JsonArray segments) { String text() { return Bot.messageText(segments); } }
    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work")); Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "bot-integration-");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB), "png", bytes);
        String image = Base64.getEncoder().encodeToString(bytes.toByteArray());
        BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
        BlockingQueue<JsonObject> requests = new LinkedBlockingQueue<>();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pixiko-bridge/v1/prompts", e -> { e.sendResponseHeaders(404, -1); e.close(); });
        server.createContext("/config", e -> {
            byte[] response = "{\"components\":[{\"props\":{\"elem_id\":\"txt2img_prompt\",\"value\":\"initial\"}},{\"props\":{\"elem_id\":\"txt2img_neg_prompt\",\"value\":\"bad\"}}]}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,response.length); e.getResponseBody().write(response); e.close();
        });
        server.createContext("/sdapi/v1/prompt-styles", e -> {
            byte[] response = "[{\"name\":\"小鸟风格\",\"prompt\":\"kotori, red ribbon\",\"negative_prompt\":\"blur\"},{\"name\":\"unrelated\",\"prompt\":\"x\",\"negative_prompt\":\"y\"}]".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,response.length); e.getResponseBody().write(response); e.close();
        });        server.createContext("/sdapi/v1/txt2img", e -> {
            requests.add(Json.parse(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            byte[] response = ("{\"images\":[\"" + image + "\"]}").getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,response.length); e.getResponseBody().write(response); e.close();
        });
        server.start();
        JsonObject cfg = Json.parse("{\"owner_user_id\":\"123\",\"admin_user_ids\":[],\"maps\":{\"yh\":\"maps/yh\",\"liv\":\"maps/liv\"},\"sd\":{}}");
        cfg.getAsJsonObject("sd").addProperty("base_url", "http://127.0.0.1:"+server.getAddress().getPort());
        cfg.addProperty("gen_auto_get", false); Json.atomicWrite(root.resolve("config.json"),cfg);
        Files.createDirectories(root.resolve("maps/yh")); Files.createDirectories(root.resolve("maps/liv"));
        // 样式只属于机器人：/.char 只查本机样式库，所以先往 data/local-styles.json 放一个样式。
        Files.createDirectories(root.resolve("data"));
        Json.atomicWrite(root.resolve("data/local-styles.json"), Json.parse(
                "{\"version\":1,\"styles\":[{\"name\":\"小鸟风格\",\"positive\":\"kotori, red ribbon\",\"negative\":\"blur\",\"updated_at\":\"\"}]}"));
        AtomicInteger mapSends = new AtomicInteger();
        AtomicBoolean failNextReply = new AtomicBoolean();
        try (Bot bot = new Bot(new Settings(root), new SdClient(root, Json.obj(cfg,"sd")), new Bot.Sender() {
            public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                if (failNextReply.getAndSet(false))
                    return CompletableFuture.failedFuture(new java.io.IOException("模拟发送失败 retcode=1200"));
                replies.add(new Reply(event.deepCopy(),segments.deepCopy())); return CompletableFuture.completedFuture(null);
            }
            public CompletableFuture<Void> sendMap(JsonObject event, JsonArray segments) {
                mapSends.incrementAndGet();
                return send(event, segments);
            }
            public CompletableFuture<com.google.gson.JsonElement> callApi(String action, JsonObject params) {
                if (!"get_group_member_list".equals(action))
                    return CompletableFuture.failedFuture(new java.io.IOException("unsupported action"));
                return CompletableFuture.completedFuture(JsonParser.parseString(
                        "[{\"user_id\":111,\"card\":\"小明\",\"nickname\":\"ming\"},"
                        + "{\"user_id\":222,\"card\":\"甲乙A\",\"nickname\":\"a\"},"
                        + "{\"user_id\":333,\"card\":\"甲乙B\",\"nickname\":\"b\"}]"));
            }
        })) {
            assert Bot.messageText(new JsonPrimitive("[CQ:at,qq=777] .prompt set [cat] &amp; dog")).equals(".prompt set [cat] & dog");
            assert Bot.splitCommands(".help").equals(List.of(".help"));
            assert Bot.splitCommands(".size set 768 512\n.gen 2").equals(List.of("/size set 768 512","/gen 2")) : "one message may carry several commands";
            assert Bot.splitCommands("  .help  \n\n.prompt  ").equals(List.of("/help","/prompt")) : "blank lines and padding are ignored";
            assert Bot.splitCommands(".prompt set a,\nb").equals(List.of(".prompt set a,\nb")) : "a multi-line argument stays one command";
            assert Bot.splitCommands(".prompt set a\n随机文字").equals(List.of(".prompt set a\n随机文字")) : "a non-command line keeps the message whole";
            bot.accept(event("group",".help")); Reply help=take(replies);
            for (String cmd : new String[]{".yh",".liv",".promptR remove",".prompt set",".gen",".get",".help"}) assert help.text().contains(cmd):cmd;
            bot.accept(event("private",".yh")); assert take(replies).text().contains("还没有地图");
            Files.write(root.resolve("maps/yh/map.png"),bytes.toByteArray());
            Files.write(root.resolve("maps/liv/map.png"),bytes.toByteArray());
            for (String type : List.of("group","private")) for (String cmd : List.of(".yh",".liv")) {
                bot.accept(event(type,cmd));
                // 先回一句说明（网页控制台只看图片时无从判断成功与否），再把图片作为单独一条发出去。
                Reply caption=take(replies);
                assert caption.text().contains("地图") : "map reply explains what is sent: " + caption.text();
                Reply picture=take(replies);
                assert picture.event().get("message_type").getAsString().equals(type);
                assert picture.segments().get(0).getAsJsonObject().get("type").getAsString().equals("image");
            }
            assert mapSends.get() == 4 : "Both maps in group/private must use plain image delivery";
            bot.accept(event("private",".prompt")); assert take(replies).text().contains("initial");
            String[][] cases = {{".prompt add [cat]","initial, [cat]"},{".promptR add blur","bad, blur"},
                {".prompt remove [cat]","initial"},{".promptR remove blur","bad"},
                {".prompt set fresh\nline","fresh\nline"},{".promptR set sharp","sharp"}};
            for (String[] c : cases) { bot.accept(event("private",c[0])); assert take(replies).text().contains(c[1]):c[0]; }
            // Prompts are private per user: they persist locally and are never shared.
            assert new UserPromptStore(root).prompts("123").positive().equals("fresh\nline")
                    && new UserPromptStore(root).prompts("123").negative().equals("sharp") : "the personal prompt must be persisted";
            JsonObject otherPrompt=event("private",".prompt"); otherPrompt.addProperty("user_id",456);
            bot.accept(otherPrompt);
            // A member who never set a prompt inherits the shared WebUI page prompt instead of being told to
            // set one up first, so ordinary members can generate right away. The copy is theirs from then on.
            Reply inherited=take(replies);
            assert inherited.text().contains("initial") : "another user inherits the shared page prompt";
            assert !new UserPromptStore(root).prompts("456").positive().contains("fresh") : "inherited copy never borrows another user's edit";
            JsonObject otherSet=event("private",".prompt set other, prompt"); otherSet.addProperty("user_id",456);
            bot.accept(otherSet); assert take(replies).text().contains("other, prompt");
            assert new UserPromptStore(root).prompts("456").positive().equals("other, prompt");
            bot.accept(event("private",".prompt"));
            assert take(replies).text().contains("fresh\nline") : "one user's edit must not touch another user's prompt";
            assert !new UserPromptStore(root).prompts("456").positive().contains("fresh") : "prompts must not leak between users";
            bot.accept(event("private",".prompt add")); assert take(replies).text().contains("请提供");
            bot.accept(event("private",".get")); assert take(replies).text().contains("暂无待领取");
            bot.accept(event("group",".gen")); assert take(replies).text().contains("开始生成");
            JsonObject request=requests.poll(5,TimeUnit.SECONDS); assert request!=null;
            assert request.get("prompt").getAsString().equals("fresh\nline"); assert request.get("negative_prompt").getAsString().equals("sharp");
            bot.accept(event("private",".gen")); assert take(replies).text().contains("已加入生成队列");
            release.countDown(); Reply success=take(replies); assert success.text().contains("生成成功"); assert success.event().get("message_type").getAsString().equals("group");
            Reply secondSuccess=take(replies); assert secondSuccess.text().contains("生成成功"); assert secondSuccess.event().get("message_type").getAsString().equals("private");
            assert requests.poll(5,TimeUnit.SECONDS)!=null;
            assert new SdClient(root,Json.obj(cfg,"sd")).pendingImages().size()==2;
            bot.accept(event("private",".get")); Reply allImages = take(replies);
            assert allImages.segments().size() == 1;
            assert take(replies).segments().size() == 1;
            assert allImages.segments().get(0).getAsJsonObject().get("type").getAsString().equals("image");
            assert take(replies).text().contains("本次领取完成");
            assert mapSends.get() == 4 : "Generated images must use folded delivery, not map exemption";
            assert new SdClient(root,Json.obj(cfg,"sd")).pendingImages().isEmpty();
            bot.accept(event("private",".get")); assert take(replies).text().contains("暂无待领取");
            Path custom=root.resolve("custom maps"); Files.createDirectories(custom);
            bot.accept(event("private",".map set yh \""+custom.toAbsolutePath()+"\"")); assert take(replies).text().contains("已保存");
            assert new Settings(root).mapPath("yh").equals(custom.toAbsolutePath());
            JsonObject denied=event("group",".map set yh maps/yh"); denied.addProperty("user_id",456); bot.accept(denied); assert take(replies).text().contains("仅 owner");
            // One owner rules the bot; only the owner appoints admins.
            assert Bot.mentionedUser(Json.parse("{\"self_id\":\"1\",\"message\":[{\"type\":\"at\",\"data\":{\"qq\":\"456\"}}]}")).equals("456");
            assert Bot.mentionedUser(Json.parse("{\"self_id\":\"1\",\"message\":[{\"type\":\"at\",\"data\":{\"qq\":\"1\"}}]}"))==null : "self mentions are ignored";
            assert Bot.quotedText(Json.parse("{\"message\":[{\"type\":\"reply\",\"data\":{\"id\":\"7\",\"text\":\"被引用的原文\"}}]}"), null).equals("被引用的原文") : "an embedded quoted body is used";
            assert Bot.quotedText(Json.parse("{\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"hi\"}}]}"), null).isEmpty() : "no reply segment means no quote";
            Bot.Sender quotedLookup = new Bot.Sender() {
                public CompletableFuture<Void> send(JsonObject event, JsonArray segments) { return CompletableFuture.completedFuture(null); }
                public CompletableFuture<com.google.gson.JsonElement> callApi(String action, JsonObject params) {
                    if (!"get_msg".equals(action)) return CompletableFuture.failedFuture(new java.io.IOException("unsupported"));
                    return CompletableFuture.completedFuture(JsonParser.parseString("{\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"从 get_msg 取回的原文\"}}]}"));
                }
            };
            assert Bot.quotedText(Json.parse("{\"message\":[{\"type\":\"reply\",\"data\":{\"id\":\"9\"}}]}"), quotedLookup)
                    .equals("从 get_msg 取回的原文") : "the quoted body is fetched with get_msg";
            assert Bot.aimedAtAnother(Json.parse("{\"self_id\":\"1\",\"message\":[{\"type\":\"at\",\"data\":{\"qq\":\"456\"}}]}")) : "an at aimed at another member is detected";
            assert !Bot.aimedAtAnother(Json.parse("{\"self_id\":\"456\",\"message\":[{\"type\":\"at\",\"data\":{\"qq\":\"456\"}}]}")) : "an at aimed at the bot itself is not";
            assert !Bot.aimedAtAnother(Json.parse("{\"self_id\":\"1\",\"raw_message\":\"[CQ:at,qq=all] hi\"}")) : "at-all is not a member";
            assert Bot.mentionedUser(Json.parse("{\"self_id\":\"1\",\"raw_message\":\"[CQ:at,qq=789] hi\"}")).equals("789");
            assert Bot.mentionedUser(Json.parse("{\"self_id\":\"1\",\"message\":[{\"type\":\"at\",\"data\":{\"qq\":\"all\"}}]}"))==null : "at-all is not a target";
            assert new Settings(root).ownerId().equals("123") : "the configured owner is authoritative";
            assert new Settings(root).isOwner("123") && !new Settings(root).isOwner("456");
            bot.accept(event("private",".admin add 456")); assert take(replies).text().contains("已添加 admin：456");
            assert new Settings(root).adminIds().contains("456");
            JsonObject nonOwner=event("private",".admin add 789"); nonOwner.addProperty("user_id",456);
            bot.accept(nonOwner); assert take(replies).text().contains("仅 owner") : "admins cannot appoint admins";
            JsonObject adminList=event("private",".admin list"); adminList.addProperty("user_id",456);
            bot.accept(adminList);
            String adminListReply=take(replies).text();
            assert adminListReply.contains("owner：123") && adminListReply.contains("admin（1）") : adminListReply;
            JsonObject removeByAdmin=event("private",".admin remove 456"); removeByAdmin.addProperty("user_id",456);
            bot.accept(removeByAdmin); assert take(replies).text().contains("仅 owner");
            bot.accept(event("private",".admin remove 456")); assert take(replies).text().contains("已移除 admin：456");
            assert !new Settings(root).adminIds().contains("456");
            // A group member name resolves through the QQ member list; ambiguity is refused.
            bot.accept(event("group",".admin add 小明")); assert take(replies).text().contains("已添加 admin：111");
            bot.accept(event("group",".admin add 甲乙")); assert take(replies).text().contains("匹配到多人");
            bot.accept(event("private",".admin add 123")); assert take(replies).text().contains("无需加入 admin");
            bot.accept(event("private",".char 小鸟"));
            String characterReply=take(replies).text();
            assert characterReply.contains("样式：小鸟风格") && characterReply.contains(".char apply #编号") : characterReply;
            bot.accept(event("private",".char 完全不存在的角色"));
            assert take(replies).text().contains("没有找到") : "an unknown character is reported clearly";            JsonObject duplicate=event("private",".help"); bot.accept(duplicate); take(replies); bot.accept(duplicate); assert replies.poll(100,TimeUnit.MILLISECONDS)==null;
            JsonObject self=event("private",".help"); self.addProperty("user_id",777); bot.accept(self); assert replies.poll(100,TimeUnit.MILLISECONDS)==null;
            bot.accept(event("private",".promptR clear")); assert take(replies).text().contains("（空）");
            // Several consecutive commands in one message run in order, each with its own receipt.
            bot.accept(event("private",".size set 768 512\n.imgcnt 7\n.settings"));
            assert take(replies).text().contains("768") : "first command applied";
            assert take(replies).text().contains("上限：7") : "second command applied";
            String combined=take(replies).text();
            assert combined.contains("768") && combined.contains("512") : "third command observes the earlier ones: "+combined;
            assert new Settings(root).imageCount()==7;
            // A failing step stops the remaining commands instead of half-applying them.
            bot.accept(event("private",".size set 1 1\n.imgcnt 9"));
            String stopped=take(replies).text();
            assert stopped.contains("第 1 条指令失败") && stopped.contains("后续指令未执行") : stopped;
            assert replies.poll(300,TimeUnit.MILLISECONDS)==null : "no receipt may be produced after the failing step";
            assert new Settings(root).imageCount()==7 : "commands after a failure must not run";
            bot.accept(event("private",".prompt set line1,\nline2"));
            assert take(replies).text().contains("line1,\nline2") : "a multi-line argument is still one command";
            bot.accept(event("private","/unknown")); assert take(replies).text().contains("格式不正确");
            // A reply that cannot be delivered is reported to the same conversation instead of only to the log.
            failNextReply.set(true);
            bot.accept(event("private",".size"));
            String deliveryFailure = take(replies).text();
            assert deliveryFailure.contains("回复发送失败") && deliveryFailure.contains("模拟发送失败 retcode=1200") : deliveryFailure;
            assert deliveryFailure.contains("原回复摘要") && deliveryFailure.split("\\R",-1).length<=2 : "the notice stays a short plain message: " + deliveryFailure;
            assert replies.poll(300,TimeUnit.MILLISECONDS)==null : "a failed reply must not be delivered twice";
        } finally { release.countDown(); server.stop(0); }
        System.out.println("BotTest PASS: maps, all commands, group/private, persistence, FIFO generation, acknowledged pending images, permissions, deduplication");
    }
    static Reply take(BlockingQueue<Reply> q) throws Exception { Reply r=q.poll(7,TimeUnit.SECONDS); if(r==null)throw new AssertionError("Reply timeout"); return r; }
    static JsonObject event(String type,String text) {
        JsonObject e=new JsonObject(); e.addProperty("post_type","message"); e.addProperty("message_type",type);
        e.addProperty("user_id",123); e.addProperty("self_id",777); e.addProperty("message_id",IDS.incrementAndGet());
        if(type.equals("group")) e.addProperty("group_id",999); e.add("message",Maps.text(text)); return e;
    }
}

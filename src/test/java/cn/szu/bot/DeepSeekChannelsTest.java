package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.chat.DeepSeekPrompts.Channel;

/**
 * 聊天频道与生图频道必须彼此独立：各自读取自己的配置段（chat_api / progen）与自己的密钥文件，
 * 且 .chat 开关只影响聊天频道，.infix/.gen/.progen 等生图指令在任何情况下都照常工作。
 */
public final class DeepSeekChannelsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "deepseek-channels").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        Files.createDirectories(root.resolve("data"));
        try {
            channelKeysAreSeparate(root);
            chatSwitchDoesNotTouchImageChannel();
            System.out.println("DeepSeekChannelsTest: " + checks + " assertions passed: 频道配置/密钥分离、.chat 开关只影响聊天频道");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    /** 两个频道各自读自己的密钥文件与配置段，互不干扰。 */
    private static void channelKeysAreSeparate(Path root) throws Exception {
        Files.writeString(root.resolve("data/deepseek-api-key.txt"), "image-channel-key");
        Files.writeString(root.resolve("data/deepseek-chat-api-key.txt"), "chat-channel-key");
        JsonObject image = new JsonObject();
        image.addProperty("model", "deepseek-flash"); image.addProperty("max_tokens", 0);
        JsonObject chat = new JsonObject();
        chat.addProperty("model", "deepseek-v4-pro"); chat.addProperty("max_tokens", 0);
        JsonObject config = new JsonObject(); config.add("progen", image); config.add("chat_api", chat);
        Json.atomicWrite(root.resolve("config.json"), config);
        Settings settings = new Settings(root);

        String[] used = new String[2];
        String[] model = new String[2];
        DeepSeekPrompts imageClient = DeepSeekPrompts.of(settings, Channel.IMAGE, (body, key, timeout) -> {
            used[0] = key; model[0] = Json.str(body, "model", "");
            return ok("{\"positive\":\"grass, military uniform\",\"negative\":\"blur\"}");
        });
        DeepSeekPrompts chatClient = DeepSeekPrompts.of(settings, Channel.CHAT, (body, key, timeout) -> {
            used[1] = key; model[1] = Json.str(body, "model", "");
            return ok("\"在的，怎么了？\"");
        });

        check(imageClient.generate("草地上穿军装").positive().contains("grass"), "生图频道可用");
        check(chatClient.chat("性格", new JsonArray(), "在吗").contains("在的"), "聊天频道可用");
        check("image-channel-key".equals(used[0]), "生图频道用 data/deepseek-api-key.txt：" + used[0]);
        check("chat-channel-key".equals(used[1]), "聊天频道用 data/deepseek-chat-api-key.txt：" + used[1]);
        check("deepseek-flash".equals(model[0]), "生图频道用自己的模型：" + model[0]);
        check("deepseek-v4-pro".equals(model[1]), "聊天频道用自己的模型：" + model[1]);
        check(!Channel.CHAT.section().equals(Channel.IMAGE.section()) && !Channel.CHAT.keyFile().equals(Channel.IMAGE.keyFile()),
                "两个频道的配置段与密钥文件互不相同");
        // 只改聊天频道的模型：生图频道的配置段不受影响。
        settings.chatApiSetting("model", new JsonPrimitive("deepseek-chat-only"));
        Settings reloaded = new Settings(root);
        check("deepseek-chat-only".equals(Json.str(DeepSeekPrompts.sectionOf(reloaded, Channel.CHAT), "model", "")),
                "聊天频道模型已保存");
        check("deepseek-flash".equals(Json.str(DeepSeekPrompts.sectionOf(reloaded, Channel.IMAGE), "model", "")),
                "生图频道模型不受影响");
        // 独立密钥缺失时才回退到共享密钥，并且只回退、不覆盖。
        Files.delete(root.resolve("data/deepseek-chat-api-key.txt"));
        String[] fallback = new String[1];
        DeepSeekPrompts fallbackClient = DeepSeekPrompts.of(new Settings(root), Channel.CHAT, (body, key, timeout) -> {
            fallback[0] = key; return ok("\"回退\"");
        });
        fallbackClient.chat("性格", new JsonArray(), "在吗");
        check("image-channel-key".equals(fallback[0]), "缺少聊天频道密钥时暂用共享密钥：" + fallback[0]);
        check(!Files.exists(root.resolve("data/deepseek-api-key.txt")) || Files.readString(root.resolve("data/deepseek-api-key.txt")).equals("image-channel-key"),
                "回退不会改写共享密钥文件");
    }

    /** .chat 开关只控制聊天频道：关掉聊天后生图链路的指令依旧执行。 */
    private static void chatSwitchDoesNotTouchImageChannel() throws Exception {
        try (var fixture = new GenerationPresetTest.Fixture()) {
            String info = fixture.command("private", ".chat");
            check(info.contains("聊天频道") && info.contains("生图频道") && info.contains("只控制聊天频道"),
                    ".chat 说明两条通道与开关范围：" + info);
            check(info.contains("data/deepseek-chat-api-key.txt") && info.contains("data/deepseek-api-key.txt"),
                    ".chat 列出两条通道的密钥文件：" + info);
            String model = fixture.command("private", ".chat model deepseek-v4-pro");
            check(model.contains("生图频道仍为 deepseek-flash"), "改聊天频道模型时说明生图频道不变：" + model);
            Settings settings = new Settings(fixture.root);
            check("deepseek-v4-pro".equals(Json.str(DeepSeekPrompts.sectionOf(settings, Channel.CHAT), "model", "")),
                    "聊天频道模型已写入 chat_api");
            check("deepseek-flash".equals(Json.str(DeepSeekPrompts.sectionOf(settings, Channel.IMAGE), "model", "deepseek-flash")),
                    "生图频道模型保持默认");
            // 关掉本会话聊天后：聊天消息不再产生回复，生图指令照常。
            fixture.command("private", ".chat toggle");
            check(!new Settings(fixture.root).chatEnabled("1:private:2"), "本会话聊天已关闭");
            JsonObject chatEvent = new JsonObject();
            chatEvent.addProperty("post_type", "message"); chatEvent.addProperty("message_type", "private");
            chatEvent.addProperty("self_id", 1); chatEvent.addProperty("user_id", 2);
            chatEvent.addProperty("message_id", 9001); chatEvent.addProperty("message", "在吗，随便聊两句");
            fixture.bot.accept(chatEvent);
            check(fixture.replies.poll(1, java.util.concurrent.TimeUnit.SECONDS) == null, "聊天已关闭时不起对话");
            fixture.command("private", ".prompt set 1girl, solo, forest");
            Files.createDirectories(fixture.root.resolve("data"));
            Files.copy(Path.of("data/prompt-tags.txt"), fixture.root.resolve("data/prompt-tags.txt"));
            String infix = fixture.command("private", ".infix 把地点换成草地");
            check(infix.contains("正在通过 DeepSeek"), "聊天关闭时 .infix 仍然执行：" + infix);
            // 生图链路只可能因为生图频道自己的密钥缺失而停下，绝不会变成“聊天未启用”。
            String async = fixture.replies.poll(10, java.util.concurrent.TimeUnit.SECONDS);
            check(async != null && async.contains("deepseek-api-key.txt") && !async.contains("deepseek-chat-api-key.txt") && !async.contains("聊天未启用"),
                    ".infix 走生图频道（配置段 progen）的密钥与报错：" + async);
            String gen = fixture.command("private", ".gen 1");
            check(gen.contains("已加入生成队列") || gen.contains("开始生成"), "聊天关闭时 .gen 仍然执行：" + gen);
            JsonObject request = fixture.requests.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            check(request != null && request.get("prompt").getAsString().contains("1girl"),
                    "生成请求带着当前提示词提交：" + (request == null ? "无" : request.get("prompt").getAsString()));
        }
    }

    private static DeepSeekPrompts.Response ok(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant"); message.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0); choice.addProperty("finish_reason", "stop"); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("id", "test"); envelope.addProperty("object", "chat.completion"); envelope.add("choices", choices);
        return new DeepSeekPrompts.Response(200, envelope.toString());
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
}

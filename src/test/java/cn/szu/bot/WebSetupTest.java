package cn.szu.bot;

import cn.szu.bot.chat.DeepSeekPrompts;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 网页端配置（首次配置 + 日后修改）：状态、字段级写入、密钥只进不出、校验、清空。
 *
 * <p>这一层是纯逻辑，不起 HTTP；接口与免令牌规则在 {@code WebUiTest} 里端到端跑。
 */
public final class WebSetupTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "websetup").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        try {
            run(root);
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
        System.out.println("WebSetupTest: " + checks + " assertions passed：首次配置状态、掩码与密钥不外发、字段级写入、"
                + "留空不改、清空密钥、地址与令牌校验");
    }

    private static void run(Path root) throws Exception {
        JsonObject config = new JsonObject();
        config.addProperty("bot_name", "神户小鸟");
        config.addProperty("owner_user_id", "");
        JsonObject webui = new JsonObject();
        webui.addProperty("host", "0.0.0.0");
        webui.addProperty("port", 8787);
        webui.addProperty("access_token", "initial-token-123456");
        config.add("webui", webui);
        JsonObject qq = new JsonObject();
        qq.addProperty("ws_url", "ws://127.0.0.1:3001");
        config.add("qq", qq);
        JsonObject sd = new JsonObject();
        sd.addProperty("base_url", "http://127.0.0.1:7860");
        config.add("sd", sd);
        Json.atomicWrite(root.resolve("config.json"), config);
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/deepseek-api-key.txt"), "sk-image-1234567890\n", StandardCharsets.UTF_8);

        Settings settings = new Settings(root);
        check(WebSetup.open(settings), "缺聊天频道密钥时算作首次配置");

        // ① 读：掩码、缺什么、首次配置期间才回显令牌
        JsonObject state = WebSetup.state(settings);
        check(state.get("needed").getAsBoolean(), "state.needed = true");
        JsonObject channels = state.getAsJsonObject("values").getAsJsonObject("channels");
        JsonObject image = channels.getAsJsonObject("image"), chat = channels.getAsJsonObject("chat");
        check(image.get("keySet").getAsBoolean(), "生图频道密钥已配置");
        check(image.get("keyMasked").getAsString().startsWith("sk-i")
                        && !image.get("keyMasked").getAsString().contains("1234567890"),
                "密钥只给掩码：" + image.get("keyMasked").getAsString());
        check(!state.toString().contains("sk-image-1234567890"), "整个 state 里都不出现密钥原文");
        check(!chat.get("keySet").getAsBoolean(), "聊天频道密钥未配置");
        check(image.get("effective").getAsString().equals(DeepSeekPrompts.DEFAULT_API),
                "没配 api_base 时生效地址是官方默认：" + image.get("effective").getAsString());
        check(state.get("missingText").getAsString().contains("聊天频道"),
                "缺什么写清楚：" + state.get("missingText").getAsString());
        check(state.getAsJsonObject("values").has("webui_access_token"),
                "首次配置期间回显访问令牌（本机免令牌，否则进不去）");

        // ② 写：一次把首次配置需要的都填上
        JsonObject applied = WebSetup.apply(settings, Json.parse("""
                {"bot_name":"小鸟","owner_user_id":"10001",
                 "sd_base_url":"http://127.0.0.1:7865","qq_ws_url":"ws://127.0.0.1:3002",
                 "channels":{"image":{"base":"https://gateway.example.com/v1","key":"sk-newimage-abcdef"},
                             "chat":{"base":"https://gateway.example.com/v1","key":"sk-newchat-abcdef"}}}"""));
        check(!applied.get("needed").getAsBoolean(), "两条密钥都填好后不再是首次配置");
        check(applied.get("notice").getAsString().contains("已保存"), "回执说明保存了：" + applied.get("notice").getAsString());
        check(!applied.toString().contains("sk-newimage-abcdef") && !applied.toString().contains("sk-newchat-abcdef"),
                "保存回执里也不出现密钥原文");
        JsonObject saved = read(root);
        check(saved.get("owner_user_id").getAsString().equals("10001"), "owner QQ 写进 config.json");
        check(saved.get("bot_name").getAsString().equals("小鸟"), "机器人名字写进 config.json");
        check(Files.readString(root.resolve("data/deepseek-chat-api-key.txt"), StandardCharsets.UTF_8).strip()
                .equals("sk-newchat-abcdef"), "聊天密钥写进自己的文件");
        check(Files.readString(root.resolve("data/deepseek-api-key.txt"), StandardCharsets.UTF_8).strip()
                .equals("sk-newimage-abcdef"), "生图密钥覆盖了原文件");
        check(Json.str(Json.obj(saved, "chat_api"), "api_base", "").equals("https://gateway.example.com/v1"),
                "聊天频道地址写进 config.json");
        check(DeepSeekPrompts.apiUrl(Json.obj(saved, "chat_api")).equals("https://gateway.example.com/v1/chat/completions"),
                "地址会补齐成完整 completions URL");
        check(Json.str(Json.obj(saved, "sd"), "base_url", "").equals("http://127.0.0.1:7865"), "SD 地址写进 config.json");
        check(Json.str(Json.obj(saved, "qq"), "ws_url", "").equals("ws://127.0.0.1:3002"), "NapCat 地址写进 config.json");
        check(!WebSetup.state(settings).getAsJsonObject("values").has("webui_access_token"),
                "配置完成后不再回显访问令牌");

        // ③ 只改传来的字段
        WebSetup.apply(settings, Json.parse("{\"channels\":{\"image\":{\"base\":\"\"}}}"));
        saved = read(root);
        check(Json.str(Json.obj(saved, "progen"), "api_base", "").isEmpty(), "地址留空 = 回到官方默认");
        check(saved.get("owner_user_id").getAsString().equals("10001"), "没传的字段保持原样");
        check(Files.readString(root.resolve("data/deepseek-api-key.txt"), StandardCharsets.UTF_8).strip()
                .equals("sk-newimage-abcdef"), "没传 key 时密钥文件不动");
        JsonObject blank = WebSetup.apply(settings, Json.parse("{\"channels\":{\"chat\":{\"key\":\"\"}}}"));
        check(Files.isRegularFile(root.resolve("data/deepseek-chat-api-key.txt")), "key 传空字符串表示不改");
        check(blank.get("changed").getAsJsonArray().isEmpty(), "没有实际改动时 changed 为空");
        check(blank.get("notice").getAsString().equals("没有改动。"), "没改动就如实说没有改动");

        // ④ 令牌：太短不接受
        WebSetup.apply(settings, Json.parse("{\"webui_access_token\":\"new-token-123456\"}"));
        check(new Settings(root).webToken().equals("new-token-123456"), "访问令牌能改，改完立刻生效");

        // ⑤ 清空密钥 → 回到首次配置
        WebSetup.apply(settings, Json.parse("{\"channels\":{\"chat\":{\"clearKey\":true}}}"));
        check(!Files.exists(root.resolve("data/deepseek-chat-api-key.txt")), "clearKey 真的删掉密钥文件");
        check(WebSetup.open(settings), "清空密钥后重新算作首次配置");

        // ⑥ 校验：坏输入一律拒绝，且不落盘
        rejects(settings, "{\"owner_user_id\":\"abc\"}", "owner QQ 要是数字");
        rejects(settings, "{\"owner_user_id\":\"0123\"}", "owner QQ 首位不能是 0");
        rejects(settings, "{\"sd_base_url\":\"127.0.0.1:7860\"}", "SD 地址要带 http://");
        rejects(settings, "{\"qq_ws_url\":\"http://127.0.0.1:3001\"}", "NapCat 地址要带 ws://");
        rejects(settings, "{\"channels\":{\"image\":{\"base\":\"ftp://x\"}}}", "通道地址要带 http://");
        rejects(settings, "{\"channels\":{\"image\":{\"key\":\"sk-a\\u0000b\"}}}", "密钥里有控制字符要拒绝");
        rejects(settings, "{\"webui_access_token\":\"short\"}", "访问令牌至少 8 位");
        check(Json.str(read(root), "bot_name", "").equals("小鸟"), "被拒绝的请求不会改动配置");
        try {
            WebSetup.test(settings, "nope");
            throw new AssertionError("未知通道名不该被接受");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("image"), "未知通道名报错说明可用值：" + expected.getMessage());
        }
    }

    private static void rejects(Settings settings, String body, String why) {
        try {
            WebSetup.apply(settings, Json.parse(body));
            throw new AssertionError("本该拒绝：" + why + "（" + body + "）");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage() != null && !expected.getMessage().isBlank(), "拒绝原因说人话：" + expected.getMessage());
        } catch (Exception other) {
            throw new AssertionError("拒绝时抛了别的异常：" + other, other);
        }
    }

    private static JsonObject read(Path root) throws Exception {
        return Json.parse(Files.readString(root.resolve("config.json"), StandardCharsets.UTF_8));
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
}

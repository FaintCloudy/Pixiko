package cn.szu.bot;

import cn.szu.bot.chat.DeepSeekPrompts;
import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 网页端配置：<b>首次配置</b>（缺 DeepSeek 密钥）与<b>日后修改</b>都走这里。
 *
 * <p>与命令行向导 {@link Setup} 的关系：命令行那份仍然是 <code>run.bat --setup</code> 的入口，
 * 两份共用同一套规则（密钥掩码 {@link Setup#mask}、网页令牌长度、只写这两类文件）。
 *
 * <p>安全约定：
 * <ul>
 *   <li>密钥<b>只进不出</b>：读接口只给「是否已配置」与掩码，任何响应里都不出现密钥原文；</li>
 *   <li>首次配置期间接口免令牌，但只对本机（回环）开放，见
 *       {@code cn.szu.bot.web.WebAuthFilter}；配置完成后与其它接口一样要令牌。</li>
 * </ul>
 */
public final class WebSetup {

    private WebSetup() { }

    /** 是否还处于「首次配置」状态：缺任一条通道的密钥，或还没有网页令牌。 */
    public static boolean open(Settings settings) {
        try { return Setup.needed(settings); } catch (Exception error) { return false; }
    }

    /** 读接口：当前值 + 掩码后的密钥 + 还缺什么。 */
    public static JsonObject state(Settings settings) throws IOException {
        JsonObject config = settings.snapshot();
        boolean needed = open(settings);

        JsonArray missing = new JsonArray();
        for (DeepSeekPrompts.Channel channel : DeepSeekPrompts.Channel.values())
            if (keyOf(settings.root, channel).isBlank()) missing.add(channel.label() + "密钥");
        if (settings.webToken().isBlank()) missing.add("网页访问令牌");

        JsonObject result = new JsonObject();
        result.addProperty("needed", needed);
        result.add("missing", missing);
        result.addProperty("missingText", missing.isEmpty() ? "" : "还差：" + String.join("、", strings(missing)) + "。");

        JsonObject values = new JsonObject();
        values.addProperty("bot_name", Json.str(config, "bot_name", Settings.DEFAULT_BOT_NAME));
        values.addProperty("owner_user_id", Json.str(config, "owner_user_id", ""));
        JsonObject sd = Json.obj(config, "sd"), qq = Json.obj(config, "qq"), webui = Json.obj(config, "webui");
        values.addProperty("sd_base_url", Json.str(sd, "base_url", ""));
        values.addProperty("sd_root", Json.str(sd, "root", ""));
        values.addProperty("qq_ws_url", Json.str(qq, "ws_url", ""));
        values.addProperty("qq_token_set", !Json.str(qq, "access_token", "").isBlank());
        values.addProperty("webui_host", Json.str(webui, "host", "0.0.0.0"));
        values.addProperty("webui_port", Json.num(webui, "port", 8787));
        // 令牌只在首次配置期间回显（那时本机免令牌，用户还没法从别处拿到它）；配置完成后不再外发。
        values.addProperty("webui_token_set", !settings.webToken().isBlank());
        if (needed) values.addProperty("webui_access_token", settings.webToken());

        JsonObject channels = new JsonObject();
        for (DeepSeekPrompts.Channel channel : DeepSeekPrompts.Channel.values()) {
            JsonObject section = Json.obj(config, channel.section());
            String key = keyOf(settings.root, channel);
            JsonObject item = new JsonObject();
            item.addProperty("section", channel.section());
            item.addProperty("label", channel.label());
            item.addProperty("base", Json.str(section, "api_base", ""));
            item.addProperty("effective", DeepSeekPrompts.apiUrl(section));
            item.addProperty("official", DeepSeekPrompts.DEFAULT_API);
            item.addProperty("model", Json.str(section, "model", "deepseek-flash"));
            item.addProperty("keyFile", channel.keyFile());
            item.addProperty("keySet", !key.isBlank());
            item.addProperty("keyMasked", key.isBlank() ? "" : Setup.mask(key));
            channels.add(nameOf(channel), item);
        }
        values.add("channels", channels);
        result.add("values", values);
        return result;
    }

    /**
     * 写接口：只改传来的字段，其余保持原样。密钥文件与 config.json 一起落盘，随后
     * {@link Settings#reload()}，所以 DeepSeek 的地址与密钥改完立刻生效（SD / NapCat 地址要重启才生效）。
     */
    public static JsonObject apply(Settings settings, JsonObject body) throws IOException {
        JsonObject next = settings.snapshot();
        List<String> changed = new ArrayList<>();

        if (body.has("bot_name")) {
            String name = Json.str(body, "bot_name", "").strip();
            if (!name.isEmpty()) {
                next.addProperty("bot_name", name);
                changed.add("机器人名字 → " + name);
            }
        }
        if (body.has("owner_user_id")) {
            String owner = Json.str(body, "owner_user_id", "").strip();
            if (!owner.isEmpty() && !owner.matches("[1-9][0-9]{0,19}"))
                throw new IllegalArgumentException("owner QQ 只能是 1–20 位数字。");
            next.addProperty("owner_user_id", owner);
            changed.add(owner.isEmpty() ? "已清空 owner QQ" : "owner QQ → " + owner);
        }
        if (body.has("sd_base_url")) {
            String url = httpUrl(Json.str(body, "sd_base_url", ""), "Stable Diffusion 地址");
            section(next, "sd").addProperty("base_url", url);
            changed.add("Stable Diffusion 地址 → " + (url.isEmpty() ? "（默认）" : url) + "（重启后生效）");
        }
        if (body.has("sd_root")) {
            section(next, "sd").addProperty("root", Json.str(body, "sd_root", "").strip());
            changed.add("Stable Diffusion 目录已更新（重启后生效）");
        }
        if (body.has("qq_ws_url")) {
            String url = Json.str(body, "qq_ws_url", "").strip();
            if (!url.isEmpty() && !url.matches("(?i)^wss?://\\S+$"))
                throw new IllegalArgumentException("NapCat 地址要以 ws:// 或 wss:// 开头。");
            section(next, "qq").addProperty("ws_url", url);
            changed.add("NapCat 地址 → " + (url.isEmpty() ? "（默认）" : url) + "（重启后生效）");
        }
        if (body.has("qq_access_token")) {
            // 空字符串表示"不动"：想清掉就用 clearQqToken。
            String token = Json.str(body, "qq_access_token", "").strip();
            if (!token.isEmpty()) {
                section(next, "qq").addProperty("access_token", token);
                changed.add("NapCat 令牌已更新（重启后生效）");
            }
        }
        if (Json.bool(body, "clearQqToken", false)) {
            section(next, "qq").addProperty("access_token", "");
            changed.add("已清空 NapCat 令牌");
        }
        if (body.has("webui_access_token")) {
            String token = Json.str(body, "webui_access_token", "").strip();
            if (!token.isEmpty()) {
                if (token.length() < 8) throw new IllegalArgumentException("访问令牌至少 8 位。");
                section(next, "webui").addProperty("access_token", token);
                changed.add("网页访问令牌已更新");
            }
        }

        JsonObject channels = body.has("channels") && body.get("channels").isJsonObject()
                ? body.getAsJsonObject("channels") : new JsonObject();
        JsonObject keys = new JsonObject();
        for (DeepSeekPrompts.Channel channel : DeepSeekPrompts.Channel.values()) {
            String name = nameOf(channel);
            JsonObject section = section(next, channel.section());
            JsonObject patch = channels.has(name) && channels.get(name).isJsonObject()
                    ? channels.getAsJsonObject(name) : new JsonObject();
            boolean touched = false;
            if (patch.has("base")) {
                String url = httpUrl(Json.str(patch, "base", ""), channel.label() + " 地址");
                section.addProperty("api_base", url);
                changed.add(channel.label() + "地址 → " + (url.isEmpty() ? "官方默认" : url));
                touched = true;
            }
            if (patch.has("model")) {
                String model = Json.str(patch, "model", "").strip();
                if (!model.isEmpty()) {
                    section.addProperty("model", model);
                    changed.add(channel.label() + "模型 → " + model);
                    touched = true;
                }
            }
            if (patch.has("clearKey") && patch.get("clearKey").getAsBoolean()) {
                Files.deleteIfExists(settings.root.resolve(channel.keyFile()));
                changed.add(channel.label() + "密钥已清空");
                touched = true;
            }
            if (patch.has("key")) {
                String key = Json.str(patch, "key", "").strip();
                if (!key.isEmpty()) {
                    if (key.codePoints().anyMatch(Character::isISOControl))
                        throw new IllegalArgumentException(channel.label() + "密钥里有控制字符，请重新复制。");
                    keys.addProperty(channel.keyFile(), key);
                    changed.add(channel.label() + "密钥已更新（" + Setup.mask(key) + "）");
                    touched = true;
                }
            }
            if (touched) next.add(channel.section(), section);
        }

        Json.atomicWrite(settings.root.resolve("config.json"), next);
        for (String file : keys.keySet()) writeKey(settings.root.resolve(file), keys.get(file).getAsString());
        settings.reload();

        JsonObject result = state(settings);
        JsonArray list = new JsonArray();
        for (String item : changed) list.add(item);
        result.add("changed", list);
        result.addProperty("notice", changed.isEmpty() ? "没有改动。" : "已保存：" + String.join("；", changed) + "。");
        return result;
    }

    /** 测试一条通道：用当前地址与密钥发一条最小请求。 */
    public static JsonObject test(Settings settings, String channel) throws IOException {
        DeepSeekPrompts.Channel target = channelOf(channel);
        JsonObject result = DeepSeekPrompts.of(settings, target).verify();
        result.addProperty("channel", nameOf(target));
        result.addProperty("label", target.label());
        return result;
    }

    // ---------------------------------------------------------------- 内部

    private static String nameOf(DeepSeekPrompts.Channel channel) {
        return channel == DeepSeekPrompts.Channel.IMAGE ? "image" : "chat";
    }

    private static DeepSeekPrompts.Channel channelOf(String name) {
        String value = name == null ? "" : name.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "image", "progen", "生图", "生图频道", "绘图" -> DeepSeekPrompts.Channel.IMAGE;
            case "chat", "chat_api", "聊天", "聊天频道", "对话" -> DeepSeekPrompts.Channel.CHAT;
            default -> throw new IllegalArgumentException("通道只能是 image（生图）或 chat（聊天）。");
        };
    }

    private static JsonObject section(JsonObject config, String name) {
        JsonObject section = Json.obj(config, name);
        config.add(name, section);
        return section;
    }

    private static String keyOf(Path root, DeepSeekPrompts.Channel channel) {
        try {
            Path path = root.resolve(channel.keyFile());
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8).strip() : "";
        } catch (Exception error) { return ""; }
    }

    private static void writeKey(Path path, String key) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, key.strip() + System.lineSeparator(), StandardCharsets.UTF_8);
        Log.info("网页端已写入密钥文件：" + path.getFileName() + "（只写不回显）");
    }

    /** 空字符串合法：表示"用官方默认地址"。 */
    private static String httpUrl(String value, String label) {
        String url = value == null ? "" : value.strip();
        if (url.isEmpty()) return "";
        if (!url.matches("(?i)^https?://\\S+$")) throw new IllegalArgumentException(label + "要以 http:// 或 https:// 开头。");
        return url;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement item : array) values.add(item.getAsString());
        return values;
    }
}

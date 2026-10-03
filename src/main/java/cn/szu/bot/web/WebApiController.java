package cn.szu.bot.web;

import cn.szu.bot.Bot;
import cn.szu.bot.Json;
import cn.szu.bot.Log;
import cn.szu.bot.Settings;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.civitai.CivitaiClient;
import com.google.gson.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 网页控制台的全部 {@code /api/**} 接口（等价于迁移前 WebUiServer 里的 {@code route()}）。
 *
 * <p>写操作一律走机器人自己的对象（{@link Bot}）与指令通道，权限、守卫、回执与 QQ 侧完全一致；
 * 纯本机的面板操作走各自的内部接口。请求/响应都用 Gson 字符串，行为与迁移前一致。
 */
@RestController
public class WebApiController {

    private final Settings settings;
    private final Bot bot;

    public WebApiController(Settings settings, Bot bot) {
        this.settings = settings;
        this.bot = bot;
    }

    @RequestMapping(value = "/api/**", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<?> api(HttpServletRequest request, @RequestBody(required = false) String raw) {
        Log.markWeb();
        String path = request.getRequestURI();
        try {
            JsonObject body = parseBody(raw);
            String scope = Json.str(body, "scope", settings.webScope());
            return route(request, path, body, scope);
        } catch (IllegalArgumentException error) {
            return WebJson.of(HttpStatus.BAD_REQUEST, WebJson.error(error.getMessage()));
        } catch (IllegalStateException error) {
            return WebJson.of(HttpStatus.CONFLICT, WebJson.error(error.getMessage()));
        } catch (Exception error) {
            Log.warn("WebUI 请求失败（" + path + "）：" + Bot.error(error));
            return WebJson.of(HttpStatus.INTERNAL_SERVER_ERROR, WebJson.error(Bot.error(error)));
        } finally {
            Log.clearWeb();
        }
    }

    private ResponseEntity<?> route(HttpServletRequest request, String path, JsonObject body, String scope) throws Exception {
        String method = request.getMethod();
        switch (path) {
            case "/api/status": return WebJson.ok(bot.webStatus());
            case "/api/functions": return WebJson.ok(bot.webFunctions(scope));
            case "/api/presets": return WebJson.ok(bot.webPresets());
            case "/api/options": return WebJson.ok(bot.webOptions());
            case "/api/usage": return WebJson.ok(bot.webUsage(Json.str(body, "query", "")));
            case "/api/tags": {
                // 提示词输入框的 tab 补全：给定正在敲的词，回标准词条 + 中文 + 分类 + 热度。
                requirePost(method);
                return WebJson.ok(bot.webTagSuggest(Json.str(body, "query", ""), Json.num(body, "limit", 20)));
            }
            case "/api/help": {
                JsonObject result = new JsonObject();
                result.addProperty("help", Bot.HELP);
                return WebJson.ok(result);
            }
            case "/api/prompt": return WebJson.ok(bot.webPrompt(scope));
            case "/api/meanings": {
                requirePost(method);
                // 词库里查不到的中文释义问一次 DeepSeek（learn=false 时只查词库与缓存，自检用）。
                JsonArray terms = body.has("terms") && body.get("terms").isJsonArray() ? body.getAsJsonArray("terms") : new JsonArray();
                List<String> values = new ArrayList<>();
                for (JsonElement term : terms) if (term.isJsonPrimitive()) values.add(term.getAsString());
                return WebJson.ok(bot.webMeanings(values, Json.bool(body, "learn", true)));
            }
            case "/api/prompt/edit": {
                requirePost(method);
                return WebJson.ok(bot.webPromptEdit(scope, Json.str(body, "side", "positive"),
                        Json.str(body, "action", ""), Json.str(body, "value", "")));
            }
            case "/api/functions/edit": {
                requirePost(method);
                return WebJson.ok(bot.webFunctionsEdit(scope, Json.str(body, "action", ""),
                        Json.str(body, "name", ""), Json.str(body, "newName", ""), Json.bool(body, "overwrite", false)));
            }
            case "/api/styles/edit": {
                requirePost(method);
                // category 只给 action=category 用（改分类）；其余动作忽略它。
                return WebJson.ok(bot.webStylesEdit(scope, Json.str(body, "action", ""),
                        Json.str(body, "name", ""), Json.str(body, "newName", ""),
                        Json.bool(body, "overwrite", false), Json.bool(body, "noLora", false),
                        Json.str(body, "category", "")));
            }
            case "/api/presets/edit": {
                requirePost(method);
                return WebJson.ok(bot.webPresetsEdit(Json.str(body, "action", ""),
                        Json.str(body, "name", ""), Json.bool(body, "overwrite", false)));
            }
            case "/api/styles": return WebJson.ok(bot.webStyles());
            case "/api/loras": return WebJson.ok(bot.webLoras());
            // 控制台自己的 LoRA 接口：不走指令通道，也不等下载跑完（进度由下面这条轮询）。
            case "/api/lora/download": {
                requirePost(method);
                // 权重是真小数（0.8 这种），不能用取整的那两个 num()。
                JsonElement weight = body.get("weight");
                return loraJob(bot.webLoraDownload(Json.str(body, "url", ""),
                        weight == null || weight.isJsonNull() ? 1.0 : weight.getAsDouble(), scope));
            }
            case "/api/lora/cover": {
                requirePost(method);
                return loraJob(bot.webLoraCover(Json.str(body, "name", ""), scope));
            }
            // 下载进度单独一条轻接口：面板每秒轮询，不能顺带把 SD 的 LoRA 列表也拉一遍。
            case "/api/lora/progress": return WebJson.ok(bot.loraProgress());
            case "/api/lora/preview": return serveLoraPreview(loraNameQuery(request, body));
            // 样式的预览图（只给网页看）：和 LoRA 展示图一样，<img> 只能把令牌挂查询串上。
            case "/api/style/preview": return serveStylePreview(loraNameQuery(request, body));
            case "/api/images": {
                JsonObject result = new JsonObject();
                result.add("images", bot.webImages(Json.num(body, "limit", 60)));
                return WebJson.ok(result);
            }
            case "/api/logs": {
                // 日志分两路：all（全部）/ qq（QQ 侧）/ web（网页侧）；网页日志面板可以随意切换。
                JsonObject result = new JsonObject();
                result.add("lines", logTail(Json.str(body, "source", "all"), Json.num(body, "lines", 200)));
                result.addProperty("source", Json.str(body, "source", "all"));
                return WebJson.ok(result);
            }
            case "/api/command": {
                String command = Json.str(body, "command", "").strip();
                if (command.isBlank()) throw new IllegalArgumentException("指令不能为空。");
                List<String> commands = new ArrayList<>();
                // 控制台不用加点和斜杠：help / gen 2 / style list 都直接认。
                for (String line : command.split("\\R")) { String part = Bot.consoleCommand(line); if (!part.isEmpty()) commands.add(part); }
                if (commands.size() > 20) throw new IllegalArgumentException("一次最多 20 条指令。");
                Bot.WebCapture capture = bot.webCommand(scope, commands);
                return WebJson.of(HttpStatus.ACCEPTED, capture.json(bot.webBusy()));
            }
            // 任务回执（/quest/#22）：按任务号取那一条，实时返回指令结果与图片。
            case "/api/quest": return WebJson.ok(bot.webQuest(Json.num(body, "id", 0)));
            case "/api/capture": {
                Bot.WebCapture capture = bot.webCapture(Json.str(body, "id", ""));
                if (capture == null) throw new IllegalArgumentException("回执已结束或不存在。");
                return WebJson.ok(capture.json(bot.webBusy()));
            }
            case "/api/capture/close": {
                bot.webClose(Json.str(body, "id", ""));
                return WebJson.ok(Json.parse("{\"closed\":true}"));
            }
            case "/api/chat": {
                String message = Json.str(body, "message", "").strip();
                if (message.isBlank()) throw new IllegalArgumentException("消息不能为空。");
                JsonArray history = chatHistory(scope);
                boolean execute = Json.bool(body, "execute", true);
                JsonObject result = bot.webChat(scope, message, history, execute);
                rememberChat(scope, message, Json.str(result, "reply", ""));
                return WebJson.ok(result);
            }
            case "/api/chat/history": return WebJson.ok(chatHistory(scope));
            case "/api/chat/reset": {
                writeChat(scope, new JsonArray());
                return WebJson.ok(new JsonObject());
            }
            case "/api/settings": {
                requirePost(method);
                applySetting(body);
                return WebJson.ok(bot.webStatus());
            }
            case "/api/generation": {
                // 「生成参数」卡：没有「应用」按钮，网页每次改动都直接发到这里立即生效。
                requirePost(method);
                return WebJson.ok(bot.webGeneration(body));
            }
            case "/api/civitai/link": {
                // 粘贴邮件里的一次性登录链接：机器人替浏览器走完整条跳转链并保存会话 Cookie。
                requirePost(method);
                return WebJson.ok(bot.civitaiSaveFromLink(Json.str(body, "link", "")));
            }
            case "/api/civitai/cookie": {
                requirePost(method);
                String cookie = Json.str(body, "cookie", "");
                if (cookie.isBlank()) throw new IllegalArgumentException("请把浏览器里的 Cookie 粘进来。");
                return WebJson.ok(bot.civitaiSaveCookie(Json.str(body, "token", ""), cookie));
            }
            case "/api/civitai/cookie/clear": return WebJson.ok(bot.civitaiClearCookie());
            case "/api/civitai/status": return WebJson.ok(bot.civitaiStatus());
            case "/api/civitai/search": {
                // 「Civitai 搜索」卡片：返回结构化结果，网页自己渲染图片+信息，不发消息。
                requirePost(method);
                return WebJson.ok(bot.webCivitaiSearch(scope, Json.str(body, "query", "")));
            }
            case "/api/civitai/thumb": {
                // 封面图代理（<img src> 只能带查询串）：机器人带登录态取图，浏览器不直连图床。
                String url = Json.str(body, "url", "");
                if (url.isBlank()) url = request.getParameter("url") == null ? "" : request.getParameter("url");
                try {
                    CivitaiClient.Image image = bot.civitaiCover(url);
                    return WebJson.bytes(HttpStatus.OK, image.bytes(), image.contentType(), "private, max-age=3600");
                } catch (java.io.IOException error) {
                    // 抓不到封面不该让整页 500：网页会把这张图换成占位。
                    Log.warn("Civitai 封面抓取失败：" + error.getMessage());
                    return WebJson.of(HttpStatus.BAD_GATEWAY, WebJson.error("封面抓取失败：" + error.getMessage()));
                }
            }
            case "/api/tasks": return WebJson.ok(bot.webTasks());
            case "/api/progress": {
                // 生成进度直接问 SD WebUI（/sdapi/v1/progress），网页在生成期间每 1.5 秒轮询一次。
                return WebJson.ok(bot.webProgress());
            }
            case "/api/sd/status": return WebJson.ok(bot.sdStatus());
            // Forge／Forge Neo 的预设栈（底模 + VAE + 文本编码器）：列出与切换；不是 Forge 时 forge=false。
            case "/api/sd/presets": return WebJson.ok(bot.webForgePresets());
            case "/api/sd/preset": {
                requirePost(method);
                return WebJson.ok(bot.webSetForgePreset(Json.str(body, "name", "")));
            }
            case "/api/sd/start": {
                // 「启动 SD」按钮：拉起 SD 要等模型加载（几十秒到几分钟），后台跑，网页轮询状态即可。
                requirePost(method);
                bot.startSdInBackground();
                JsonObject result = new JsonObject();
                result.addProperty("started", true);
                result.add("sd", bot.sdStatus());
                return WebJson.of(HttpStatus.ACCEPTED, result);
            }
            case "/api/tasks/action": {
                requirePost(method);
                return WebJson.ok(bot.webTaskAction(Json.str(body, "action", ""), Json.str(body, "number", "")));
            }
            case "/api/image": return serveImage(imageQuery(request, body));
            default: return WebJson.of(HttpStatus.NOT_FOUND, WebJson.error("未知接口：" + path));
        }
    }

    /**
     * LoRA 任务接口的状态码：启动成功 202（前端开始轮询进度），已经有任务在跑 409（不排队），
     * 参数不对 400——都由机器人那边的一句话说明原因，网页直接弹出来。
     */
    private static ResponseEntity<?> loraJob(JsonObject result) {
        return WebJson.of(Json.bool(result, "started", false) ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT, result);
    }

    private static void requirePost(String method) {
        if (!"POST".equals(method)) throw new IllegalArgumentException("请使用 POST。");
    }

    private static JsonObject parseBody(String raw) {
        if (raw == null) return new JsonObject();
        if (raw.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024) throw new IllegalArgumentException("请求体过大。");
        String text = raw.strip();
        if (text.isEmpty()) return new JsonObject();
        try { return Json.parse(text); }
        catch (Exception error) { throw new IllegalArgumentException("请求体不是合法 JSON。"); }
    }

    /** 本机访问时用的主机名：优先回环地址，避免给出 0.0.0.0 这种点不开的链接。 */
    static String localHost(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host != null && !host.isBlank()) {
            int colon = host.lastIndexOf(':');
            String name = colon > 0 ? host.substring(0, colon) : host;
            if (!name.isBlank() && !name.equals("0.0.0.0") && !name.equals("::") && !name.equals("[::]")) return name;
        }
        return "127.0.0.1";
    }

    /** 图片路径可以放在 JSON 体里，也可以放在查询串里（<img src> 只能带查询串）。 */
    static String imageQuery(HttpServletRequest request, JsonObject body) {
        String path = Json.str(body, "path", "");
        if (!path.isBlank()) return path;
        String value = request.getParameter("path");
        return value == null ? "" : value;
    }

    /** LoRA 名称同理：<img src="/api/lora/preview?name=..."> 只能走查询串。 */
    static String loraNameQuery(HttpServletRequest request, JsonObject body) {
        String name = Json.str(body, "name", "");
        if (!name.isBlank()) return name;
        String value = request.getParameter("name");
        return value == null ? "" : value;
    }

    /** 本机 LoRA 的展示图（civitai.lora_dir 里的 <模型名>.preview.png），路径校验在 Bot 里做。 */
    private ResponseEntity<?> serveLoraPreview(String name) throws IOException {
        Path file = bot.loraPreviewFile(name);
        if (file == null) return WebJson.of(HttpStatus.NOT_FOUND, WebJson.error("这个 LoRA 还没有展示图。"));
        return WebJson.bytes(HttpStatus.OK, Files.readAllBytes(file), WebJson.contentTypeOf("x.png"), "private, max-age=300");
    }

    /** 样式的预览图（data/style-previews 里按名字哈希存的那张）。 */
    private ResponseEntity<?> serveStylePreview(String name) throws IOException {
        Path file = bot.stylePreviewFile(name);
        if (file == null) return WebJson.of(HttpStatus.NOT_FOUND, WebJson.error("这个样式还没有预览图。"));
        return WebJson.bytes(HttpStatus.OK, Files.readAllBytes(file), WebJson.contentTypeOf("x.png"), "private, max-age=300");
    }

    /** 只允许读取机器人目录内的 data/generated 图片，杜绝路径穿越。 */
    private ResponseEntity<?> serveImage(String relative) throws IOException {
        Path root = settings.root.toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root.resolve("data").resolve("generated")) || !Files.isRegularFile(target))
            return WebJson.of(HttpStatus.NOT_FOUND, WebJson.error("图片不存在。"));
        String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp") || name.endsWith(".gif")))
            return WebJson.of(HttpStatus.FORBIDDEN, WebJson.error("不支持的文件类型。"));
        return WebJson.bytes(HttpStatus.OK, Files.readAllBytes(target), WebJson.contentTypeOf(name), "private, max-age=60");
    }

    /** 读取当天日志的最后若干行（网页日志面板）：source 取 all / qq / web。 */
    private JsonArray logTail(String source, int lines) {
        JsonArray result = new JsonArray();
        int wanted = Math.max(1, Math.min(2000, lines));
        String prefix = switch (source == null ? "all" : source.strip().toLowerCase(Locale.ROOT)) {
            case "qq" -> "qq-";
            case "web", "webui" -> "web-";
            default -> "bot-";
        };
        try {
            String stamp = java.time.LocalDate.now().toString().replace("-", "");
            Path file = settings.root.toAbsolutePath().normalize().resolve("logs/" + prefix + stamp + ".log");
            if (!Files.isRegularFile(file)) return result;
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - wanted);
            for (int index = from; index < all.size(); index++) result.add(all.get(index));
        } catch (Exception error) { Log.warn("WebUI 日志读取失败：" + Bot.error(error)); }
        return result;
    }

    /** 网页可改的设置：聊天开关/频率/性格、生图参数、词库约束、聊天频道模型、访问令牌。 */
    private void applySetting(JsonObject body) throws Exception {
        String key = Json.str(body, "key", "");
        String value = Json.str(body, "value", "");
        switch (key) {
            case "chatGlobal" -> settings.setChatEnabled(parseBoolean(value, "聊天全局开关"));
            case "chatFrequency" -> {
                int frequency;
                try { frequency = Integer.parseInt(value.strip()); } catch (NumberFormatException e) { throw new IllegalArgumentException("频率须为非负整数。"); }
                if (frequency < 0 || frequency > 600) throw new IllegalArgumentException("频率须为 0–600。");
                settings.chatSetting("frequency", new JsonPrimitive(frequency));
            }
            case "personality" -> {
                if (value.length() > 20000) throw new IllegalArgumentException("性格设定超过 20000 字符。");
                // 有人设文件（data/chat-personality-kotori.txt）时就写那个文件：它才是随仓库同步的生效人设。
                settings.setChatPersonality(value);
            }
            case "chatModel" -> {
                if (value.isBlank() || value.length() > 100 || value.codePoints().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("模型名称须为 1–100 个字符。");
                settings.chatApiSetting("model", new JsonPrimitive(value.strip()));
            }
            case "imageModel" -> {
                if (value.isBlank() || value.length() > 100 || value.codePoints().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("模型名称须为 1–100 个字符。");
                settings.progenSetting("model", new JsonPrimitive(value.strip()));
            }
            case "infixFilter" -> settings.setInfixFilterEnabled(Bot.webConversationKey(Json.str(body, "scope", settings.webScope())), parseBoolean(value, "词库约束"));
            case "chatThinking" -> settings.chatApiSetting("thinking", new JsonPrimitive(parseBoolean(value, "聊天思考模式")));
            case "imageThinking" -> settings.progenSetting("thinking", new JsonPrimitive(parseBoolean(value, "生图思考模式")));
            case "sdAutoStart" -> settings.sdSetting("auto_start", new JsonPrimitive(parseBoolean(value, "SD 自动启动")));
            case "sdStartOnBoot" -> settings.sdSetting("start_on_boot", new JsonPrimitive(parseBoolean(value, "开机自启动 SD")));
            case "notice" -> settings.setStartupNoticeEnabled(parseBoolean(value, "上线播报"));
            case "logMirror" -> settings.setLogMirrorEnabled(parseBoolean(value, "日志镜像"));
            case "autoGet" -> {
                // 自动领取是本机开关：网页勾选框直接改它，不再走 `.gen toggle` 指令。
                boolean wanted = parseBoolean(value, "自动领取");
                if (wanted != settings.autoGet()) settings.toggleAutoGet();
            }
            case "token" -> {
                if (value.strip().length() < 8) throw new IllegalArgumentException("访问令牌至少 8 个字符。");
                settings.webSetting("access_token", new JsonPrimitive(value.strip()));
                Log.info("WebUI 访问令牌已更新。");
            }
            default -> throw new IllegalArgumentException("不支持的设置项：" + key);
        }
        Log.info("WebUI 设置已更新：" + key);
    }

    private static boolean parseBoolean(String value, String label) {
        if (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true") || value.equals("1") || value.equals("开")) return true;
        if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false") || value.equals("0") || value.equals("关")) return false;
        throw new IllegalArgumentException(label + " 只能是 on/off。");
    }

    // ---------------------------------------------------------------- 聊天历史（按 scope 持久化）

    private Path chatFile(String scope) { return settings.root.toAbsolutePath().normalize().resolve("data/webui").resolve(scope + "-chat.json"); }

    JsonArray chatHistory(String scope) {
        try {
            Path file = chatFile(scope);
            if (!Files.isRegularFile(file)) return new JsonArray();
            JsonArray stored = Json.parse(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray("history");
            return stored == null ? new JsonArray() : stored.deepCopy();
        } catch (Exception error) { Log.warn("WebUI 聊天历史读取失败：" + Bot.error(error)); return new JsonArray(); }
    }

    private void rememberChat(String scope, String message, String reply) {
        try {
            JsonArray history = chatHistory(scope);
            history.add(DeepSeekPrompts.chatMessage("user", message));
            if (!reply.isBlank()) history.add(DeepSeekPrompts.chatMessage("assistant", reply));
            while (history.size() > 40) history.remove(0);
            writeChat(scope, history);
        } catch (Exception error) { Log.warn("WebUI 聊天历史保存失败：" + Bot.error(error)); }
    }

    private void writeChat(String scope, JsonArray history) {
        try {
            JsonObject state = new JsonObject();
            state.addProperty("version", 1); state.addProperty("scope", scope); state.add("history", history);
            Json.atomicWrite(chatFile(scope), state);
        } catch (Exception error) { Log.warn("WebUI 聊天历史写入失败：" + Bot.error(error)); }
    }
}

package cn.szu.bot.web;

import cn.szu.bot.Bot;
import cn.szu.bot.Json;
import cn.szu.bot.Log;
import cn.szu.bot.Settings;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.civitai.CivitaiClient;
import com.google.gson.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    /** 对话页正文的持久化存档（完整正文：文字 + 图片路径 + 指令回执）；纯文字那份 LLM 历史仍走 chatFile。 */
    private final ChatLogStore chatLog;
    /**
     * 安卓端自动更新（问版本 + 下 APK）：只读、免令牌（放行在 {@link WebAuthFilter#shouldNotFilter}，
     * 那里在路由匹配之前跑），哈希缓存挂在它身上。渠道开关见 {@link AppUpdate} 的类注释。
     */
    private final AppUpdate appUpdate;

    public WebApiController(Settings settings, Bot bot) {
        this.settings = settings;
        this.bot = bot;
        this.chatLog = new ChatLogStore(settings.root);
        // 每次请求重读配置：把 app_update.channel 从 dev 改成 release 立刻生效，不用重启机器人。
        this.appUpdate = new AppUpdate(AppUpdate.configFor(settings), AppUpdate::defaultCandidates,
                settings.root, () -> AppUpdate.configFor(settings));
    }

    @RequestMapping(value = "/api/**", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<?> api(HttpServletRequest request, @RequestBody(required = false) String raw) {
        Log.markWeb();
        String path = request.getRequestURI();
        try {
            JsonObject body = parseBody(raw);
            String scope = resolveScope(Json.str(body, "scope", settings.webScope()));
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

    /**
     * 服务端给请求定的 scope（「这是谁的对话」）。请求体里给了 {@code scope} 就用它，否则用控制台默认的
     * {@link Settings#webScope()}（{@code "web"}）。
     *
     * <p>这里多一道<b>非常窄</b>的净化，只做一件事：把明显不是"一个 scope"的输入挡回去，退回默认。
     * 为什么需要：scope 同时是服务端两处存储的键 ——
     * {@code ChatLogStore.safeScope} 只留 {@code [A-Za-z0-9_.-]}（其余换 {@code _}、上限 64），
     * {@code UserPromptStore.scopeOf} 只放行 {@code [A-Za-z][A-Za-z0-9_-]{0,31}}，<b>其余一律塌成
     * {@code "default"}</b>。于是像 {@code "device A"} 这种带空格的 scope 会先被换成 {@code device_A}
     * （聊天正文）又被换成 {@code default}（个人提示词）—— 两条路走散，不同设备可能因此挤进同一份提示词。
     * 与其让"每台设备一段自己的对话"在某个角落被悄悄合并，不如在这里就把它退回默认值：
     * 合法形状（Android 外壳给的 {@code dev-<12 位十六进制>}、控制台的 {@code web}、旧的 QQ 号）全都原样通过。
     *
     * <p>这<b>不是</b>权限或安全边界（WebUI 是单用户的，令牌就是全部授权），只是"别把存储键搞散"的一致性守卫。
     */
    static String resolveScope(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) return "web";
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '.';
            if (!ok) return "web";
        }
        return value.length() > 64 ? "web" : value;
    }

    private ResponseEntity<?> route(HttpServletRequest request, String path, JsonObject body, String scope) throws Exception {
        String method = request.getMethod();
        switch (path) {
            case "/api/status": return WebJson.ok(bot.webStatus(questScope(request, body)));
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
                // category 只给 action=category 用（改分类）；cover 只给 save/overwrite 用（封面图：
                // 空串＝默认取最近一次的生成图，- / none / 清除＝本次不设封面）。其余动作忽略这两个字段。
                return WebJson.ok(bot.webStylesEdit(scope, Json.str(body, "action", ""),
                        Json.str(body, "name", ""), Json.str(body, "newName", ""),
                        Json.bool(body, "overwrite", false), Json.bool(body, "noLora", false),
                        Json.str(body, "category", ""), Json.str(body, "cover", "")));
            }
            case "/api/styles/cover": {
                requirePost(method);
                // 单独换某条样式的封面（必须是 data/generated 里的图片）。
                return WebJson.ok(bot.webStyleCover(Json.str(body, "name", ""), Json.str(body, "path", "")));
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
            // 取消/暂停/继续当前下载：权限与 /api/lora/download 完全相同（同一个 scope 与 admin 规则）。
            // 三条都是幂等的：没有任务在跑也回 200 + 一句 message，不报错。
            case "/api/lora/cancel": {
                requirePost(method);
                return WebJson.ok(bot.webLoraCancel(scope));
            }
            case "/api/lora/pause": {
                requirePost(method);
                return WebJson.ok(bot.webLoraPause(scope));
            }
            case "/api/lora/resume": {
                requirePost(method);
                return WebJson.ok(bot.webLoraResume(scope));
            }
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
                // `files` 是 logs/ 目录的**真实文件清单**（控制台「日志文件」下拉用它；只读、只列不读内容）。
                JsonObject result = new JsonObject();
                result.add("lines", logTail(Json.str(body, "source", "all"), Json.num(body, "lines", 200)));
                result.addProperty("source", Json.str(body, "source", "all"));
                result.add("files", logFiles());
                return WebJson.ok(result);
            }
            case "/api/logs/tail": return logTailBytes(Json.str(body, "name", ""), Json.num(body, "offset", -1),
                    body.has("maxBytes") ? Integer.valueOf(Json.num(body, "maxBytes", 0)) : null);
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
            // scope：带上就只认这个会话的回执（手机端每次都带），不带就是老行为（控制台）。
            case "/api/quest": return WebJson.ok(bot.webQuest(Json.num(body, "id", 0), questScope(request, body)));
            // 任务回执列表（摘要 + 未读标记）：正文仍然只有 /api/quest 才回，列表里一条正文都不带。
            // 不传 scope = 不过滤（控制台一字未改）；传了 = 只看这个会话自己的回执（手机端）。
            case "/api/quests": return WebJson.ok(bot.webQuests(questLimit(request, body), questScope(request, body)));
            case "/api/quests/read": {
                requirePost(method);
                // numbers 是任务号数组（不存在的忽略），all=true 表示全部标为已读。
                JsonArray items = body.has("numbers") && body.get("numbers").isJsonArray()
                        ? body.getAsJsonArray("numbers") : new JsonArray();
                Set<Integer> numbers = new LinkedHashSet<>();
                for (JsonElement item : items) {
                    try { numbers.add(item.getAsInt()); } catch (RuntimeException ignored) { /* 非数字的号忽略 */ }
                }
                return WebJson.ok(bot.webMarkQuestsRead(numbers, Json.bool(body, "all", false), questScope(request, body)));
            }
            case "/api/capture": {
                Bot.WebCapture capture = bot.webCapture(Json.str(body, "id", ""));
                if (capture == null) throw new IllegalArgumentException("回执已结束或不存在。");
                return WebJson.ok(capture.json(bot.webBusy()));
            }
            case "/api/capture/close": {
                bot.webClose(Json.str(body, "id", ""));
                return WebJson.ok(Json.parse("{\"closed\":true}"));
            }
            // 事件队列：**前端取信息的主路**（服务端入队、前端拿到就出队渲染）。旧的 /api/capture 轮询
            // 一个都没删（历史与回执列表还要用），但队列是新的一等公民，前端不必再猜身份去缝回执。
            // 契约（POST，body 带 scope）：{"scope","after","limit"} → {"scope","items","next","latest",
            // "hasMore","acked","trimmedUpTo"}；items 按 seq 严格升序，hasMore 为 true 就接着取。
            case "/api/events": {
                requirePost(method);
                long after = longParam(body, "after", 0L);
                int limit = (int) Math.min(Integer.MAX_VALUE, Math.max(0, longParam(body, "limit", 0L)));
                return WebJson.ok(bot.webEvents(scope, after, limit));
            }
            // 确认出队：{"scope","seq"} → {"ok":true,"acked":N}。只推进、不回退，按 scope 各存一份
            // （控制台一个、每台手机一个，互不影响）。
            case "/api/events/ack": {
                requirePost(method);
                return WebJson.ok(bot.webEventAck(scope, longParam(body, "seq", 0L)));
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
            // 对话页正文的持久化存档：控制台打开对话栏读它（服务端为准），有新内容再回写。
            // 与上面那份只给模型用的纯文字历史（chatHistory）各存各的，互不影响。
            case "/api/chat/log": {
                requirePost(method);
                JsonArray entries = chatLog.load(scope);
                JsonObject result = new JsonObject();
                result.add("entries", entries);
                result.addProperty("count", entries.size());
                return WebJson.ok(result);
            }
            case "/api/chat/log/save": {
                requirePost(method);
                JsonElement stored = body.get("entries");
                if (stored == null || !stored.isJsonArray()) throw new IllegalArgumentException("entries 必须是数组。");
                chatLog.save(scope, stored.getAsJsonArray());
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("count", chatLog.load(scope).size());
                return WebJson.ok(result);
            }
            case "/api/chat/reset": {
                writeChat(scope, new JsonArray());
                // 「清空对话」要连服务端那份完整正文一起清，不然刷新页面历史又回来了。
                // 走 clear（而不是 save 空数组）：连幂等账本一起清，服务端不会再"补回"刚清掉的那几条。
                chatLog.clear(scope);
                return WebJson.ok(new JsonObject());
            }
            case "/api/settings": {
                requirePost(method);
                applySetting(body);
                return WebJson.ok(bot.webStatus());
            }
            // infix 档位按钮（界面代理绑定这个接口）：global（默认，一条指令只改一次）/ parts（旧的分组多步）。
            // 非法 mode 一律 400 且**不改**已存值；scope 省略时用当前网页会话。
            case "/api/infix/mode": {
                requirePost(method);
                return WebJson.ok(bot.setInfixMode(Json.str(body, "scope", settings.webScope()), Json.str(body, "mode", "")));
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
                // body.page 是页码（从 1 开始），网页的「上一页/下一页」就带它。
                requirePost(method);
                return WebJson.ok(bot.webCivitaiSearch(scope, Json.str(body, "query", ""), cn.szu.bot.Bot.webSearchPage(body)));
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
            // VAE：查看当前值 + 冲突判定（与其它 SD 接口同一权限）。
            case "/api/sd/vae": return WebJson.ok(bot.webVae());
            case "/api/sd/vae/list": return WebJson.ok(bot.webVaeList());
            case "/api/sd/vae/set": {
                requirePost(method);
                return WebJson.ok(bot.webVaeSet(Json.str(body, "name", "")));
            }
            // 一键修复：清掉冲突的额外模块 + 把 VAE 设回 Automatic，回同一份快照 + actions。
            case "/api/sd/vae/fix": {
                requirePost(method);
                return WebJson.ok(bot.webVaeFix());
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
            case "/api/image": return serveImage(imageQuery(request, body), imageWidth(request, body));
            // 安卓端自动更新（只读、**不需要令牌**：放行在 WebAuthFilter，过滤器在路由匹配之前跑）。
            // POST /api/app/update 问「有没有新版本」，GET /api/app/apk 下当前渠道那份 APK 的字节流。
            // 契约固定：apkUrl 用请求的 Host 拼，不下发任何凭据字段；方法不对回 405，不静默。
            case "/api/app/update": {
                // 契约是 POST。别的控制台接口用 requirePost（400），这里明确回 405：安卓端能一眼分清
                // 「方法用错」和「参数用错」，也符合 HTTP 语义。
                if (!"POST".equals(method))
                    return WebJson.of(HttpStatus.METHOD_NOT_ALLOWED, WebJson.error("请使用 POST。"));
                return WebJson.ok(appUpdate.info(request));
            }
            case "/api/app/apk": {
                // 契约是 GET（安卓端探测时误用过 POST）：方法不对明确回 405，不静默。
                if (!"GET".equals(method) && !"HEAD".equals(method))
                    return WebJson.of(HttpStatus.METHOD_NOT_ALLOWED, WebJson.error("请使用 GET。"));
                return appUpdate.apk(request, imageQuery(request, body));
            }
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

    /**
     * 队列参数（{@code after} / {@code limit} / {@code seq}）：必须是整数，认不出来就用默认值。
     * 不整份 400：前端一次手误不该让队列停摆（下一个数字类型的字段就能继续）。
     */
    static long longParam(JsonObject body, String key, long fallback) {
        if (body == null || !body.has(key) || body.get(key).isJsonNull()) return fallback;
        try { return body.get(key).getAsLong(); } catch (RuntimeException ignored) { return fallback; }
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

    /** 缩略图宽度：body 与查询串都认；非整数当没传，越界由 ImageThumbs 夹到 32–1600。 */
    static Integer imageWidth(HttpServletRequest request, JsonObject body) {
        if (body.has("w") && !body.get("w").isJsonNull()) {
            try { return ImageThumbs.parseWidth(body.get("w").getAsString()); }
            catch (RuntimeException ignored) { /* 不是标量就当没传 */ }
        }
        return ImageThumbs.parseWidth(request.getParameter("w"));
    }

    /** LoRA 名称同理：<img src="/api/lora/preview?name=..."> 只能走查询串。 */
    static String loraNameQuery(HttpServletRequest request, JsonObject body) {
        String name = Json.str(body, "name", "");
        if (!name.isBlank()) return name;
        String value = request.getParameter("name");
        return value == null ? "" : value;
    }

    /** 回执列表的 limit：body 与查询串都认（GET /api/quests?limit=50），认不出的值用默认 50。 */
    static int questLimit(HttpServletRequest request, JsonObject body) {
        if (body.has("limit") && !body.get("limit").isJsonNull()) {
            try { return body.get("limit").getAsInt(); } catch (RuntimeException ignored) { return 50; }
        }
        String value = request.getParameter("limit");
        if (value == null || value.isBlank()) return 50;
        try { return Integer.parseInt(value.strip()); } catch (NumberFormatException ignored) { return 50; }
    }

    /**
     * 回执/任务类接口的"会话过滤"参数：**请求里没给 scope 就返回 {@code null}（= 不筛）**。
     *
     * <p>为什么不直接用 {@link #api} 算好的那个 scope：它省略时会被填成控制台的
     * {@link Settings#webScope()}（{@code "web"}），拿它去筛等于"控制台只看得到 web 会话的回执"——
     * 桌面控制台（{@code webui/app.js} 的 api() 只发自己的 body、从不补 scope）会突然少掉一大半回执。
     * 这里坚持「不传 = 保持现状」，控制台一条不少；手机端每次请求都带自己的 {@code dev-…}，于是只看得到自己的。
     *
     * <p>body 与查询串都认（{@code POST {scope}} 是手机端的写法，{@code GET ?scope=} 也照收）。
     */
    static String questScope(HttpServletRequest request, JsonObject body) {
        String value = body == null ? "" : Json.str(body, "scope", "");
        if (value.isBlank()) {
            String query = request.getParameter("scope");
            value = query == null ? "" : query;
        }
        value = value.strip();
        // resolveScope 只做"存储键形状"的统一（非法形状退回 web），与 /api/** 其它接口同一个口径。
        return value.isEmpty() ? null : resolveScope(value);
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

    /**
     * 只允许读取机器人目录内的 data/generated 图片，杜绝路径穿越。
     *
     * <p>路径形态交给 {@link ImageThumbs#resolveImage(Path, String)}：相对路径、{@code file:///F:/…}、
     * 绝对路径、URL 编码写法都认（存档里存的就是 file URI 形态，以前一律 400，移动端整屏图都出不来）。
     * 给了 {@code w} 就尽量回缩略图；缩略图任何一步失败都退回原图字节，绝不 500。
     */
    private ResponseEntity<?> serveImage(String raw, Integer width) throws IOException {
        Path root = settings.root.toAbsolutePath().normalize();
        Path target = ImageThumbs.resolveImage(root, raw);
        if (target == null) return WebJson.of(HttpStatus.FORBIDDEN, WebJson.error("图片路径不合法或不是支持的图片类型。"));
        if (!Files.isRegularFile(target)) return WebJson.of(HttpStatus.NOT_FOUND, WebJson.error("图片不存在。"));
        if (width != null) {
            ImageThumbs.Thumb thumb = ImageThumbs.thumbnail(root, target, width);
            if (thumb != null) return WebJson.bytes(HttpStatus.OK, thumb.bytes(), thumb.contentType(), ImageThumbs.IMAGE_CACHE_CONTROL);
        }
        String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
        return WebJson.bytes(HttpStatus.OK, Files.readAllBytes(target), WebJson.contentTypeOf(name),
                ImageThumbs.IMAGE_CACHE_CONTROL);
    }

    /**
     * {@code logs/} 目录下的**真实文件清单**（只读）：名字 / 大小 / 修改时间。
     *
     * <p>控制台「日志文件」下拉就靠它 —— 所以列的是目录里**实际有什么**（{@code bot-*.log}、
     * {@code qq-*.log}、{@code web-*.log}、{@code prompt-rewrite.log}、{@code prompt-review.log}、
     * {@code bot-stdout.log}、{@code sd-autostart.log} …），不是写死的一份名单。
     * 目录不存在或读不动就回空数组（面板显示"还没有日志文件"），绝不 500。
     */
    private JsonArray logFiles() {
        JsonArray result = new JsonArray();
        Path dir = logsDir();
        try (java.util.stream.Stream<Path> stream = Files.isDirectory(dir) ? Files.list(dir) : null) {
            if (stream == null) return result;
            List<Path> files = stream.filter(Files::isRegularFile)
                    .sorted(java.util.Comparator.comparingLong((Path file) -> lastModified(file)).reversed())
                    .toList();
            for (Path file : files) {
                JsonObject item = new JsonObject();
                item.addProperty("name", file.getFileName().toString());
                item.addProperty("size", sizeOf(file));
                item.addProperty("modified", java.time.Instant.ofEpochMilli(lastModified(file)).toString());
                result.add(item);
            }
        } catch (Exception error) {
            Log.warn("WebUI 日志文件清单读取失败：" + Bot.error(error));
        }
        return result;
    }

    /**
     * 读一个日志文件的字节（**只读**，UTF-8 解码）：{@code offset} 起、最多 {@code maxBytes} 字节。
     *
     * <p>规矩（用户要求）：
     *   <ul>
     *     <li>文件名只允许 {@code logs/} 目录里的**单层**普通文件：{@code ..}、绝对路径、子目录、符号链接
     *         一律 403（{@code normalize + startsWith + 父目录必须就是 logs/}）；</li>
     *     <li>{@code offset < 0}（不传）→ 取**尾部**：{@code maxBytes} 给了就取最后这么多字节，
     *         没给就按 {@value #LOG_TAIL_DEFAULT_BYTES} 字节兜底（大文件不要一次全读进页面）；</li>
     *     <li>{@code offset >= 0} → 从该字节起取（增量跟随就靠它）；{@code maxBytes} 不传 = 取到结尾；</li>
     *     <li>UTF-8 解码**保留半截多字节字符**（从 {@code offset} 往前后各让几个字节，≤4 字节，
     *         不改变总长度）；否则按字节切会让中文变乱码；</li>
     *     <li>**不设人为的条数/长度上限**（用户明确讨厌那个）。</li>
     *   </ul>
     */
    private ResponseEntity<?> logTailBytes(String rawName, int offset, Integer maxBytes) {
        Path dir = logsDir();
        Path file;
        try {
            file = logFileIn(dir, rawName);
        } catch (IllegalArgumentException error) {
            return WebJson.of(HttpStatus.FORBIDDEN, WebJson.error(error.getMessage()));
        }
        if (file == null || !Files.isRegularFile(file)) {
            return WebJson.of(HttpStatus.NOT_FOUND, WebJson.error("日志文件不存在：" + rawName));
        }
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            long size = channel.size();
            long start;
            int want;
            if (offset >= 0) {
                start = Math.min(offset, size);
                want = maxBytes == null ? (int) Math.min(Integer.MAX_VALUE, size - start)
                        : Math.max(1, Math.min(maxBytes, (int) Math.min(Integer.MAX_VALUE, size - start)));
            } else if (maxBytes != null) {
                want = Math.max(1, maxBytes);
                start = Math.max(0, size - want);
            } else {
                want = LOG_TAIL_DEFAULT_BYTES;
                start = Math.max(0, size - want);
            }
            byte[] bytes = new byte[want];
            int read = 0;
            while (read < want) {
                int count = channel.read(java.nio.ByteBuffer.wrap(bytes, read, want - read), start + read);
                if (count <= 0) break;
                read += count;
            }
            // 半截多字节字符：往前让到首字节，再往后让到完整序列的末尾（都是纯字节运算）。
            int from = 0;
            while (from < read && (bytes[from] & 0x80) != 0 && (bytes[from] & 0xC0) != 0xC0) from++;
            int to = read;
            while (to > from && (bytes[to - 1] & 0xC0) == 0x80) to--;
            String text = new String(bytes, from, Math.max(0, to - from), StandardCharsets.UTF_8);
            JsonObject result = new JsonObject();
            result.addProperty("name", file.getFileName().toString());
            result.addProperty("size", size);
            result.addProperty("offset", start + from);
            result.addProperty("text", text);
            result.addProperty("truncated", start + from > 0);
            return WebJson.ok(result);
        } catch (Exception error) {
            Log.warn("WebUI 日志读取失败（" + rawName + "）：" + Bot.error(error));
            return WebJson.of(HttpStatus.INTERNAL_SERVER_ERROR, WebJson.error("日志读取失败。"));
        }
    }

    /** 一个日志文件名 → logs/ 目录里的真实路径；非法（穿越/绝对路径/子目录）抛 {@link IllegalArgumentException}。 */
    private Path logFileIn(Path dir, String rawName) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty()) throw new IllegalArgumentException("日志名不能为空。");
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.startsWith("~")
                || name.indexOf(':') >= 0 || !name.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("日志名不合法（只允许 logs/ 目录里的文件名）：" + name);
        }
        Path target = dir.resolve(name).normalize();
        // 双重保险：解析后必须仍在 logs/ 内、且父目录就是 logs/（挡掉符号链接指出去的写法）。
        if (!target.startsWith(dir) || !dir.equals(target.getParent())) {
            throw new IllegalArgumentException("日志名不合法（只允许 logs/ 目录里的文件名）：" + name);
        }
        return target;
    }

    private Path logsDir() { return settings.root.toAbsolutePath().normalize().resolve("logs"); }

    private static long lastModified(Path file) {
        try { return Files.getLastModifiedTime(file).toMillis(); } catch (Exception error) { return 0L; }
    }

    private static long sizeOf(Path file) {
        try { return Files.size(file); } catch (Exception error) { return 0L; }
    }

    /** {@code /api/logs/tail} 不传 {@code maxBytes} 且不传 {@code offset} 时的尾部字节数（大文件别整读）。 */
    private static final int LOG_TAIL_DEFAULT_BYTES = 256 * 1024;

    /** 读取当天日志的最后若干行（网页日志面板）：source 取 all / qq / web。 */
    private JsonArray logTail(String source, int lines) {        JsonArray result = new JsonArray();
        int wanted = Math.max(1, Math.min(2000, lines));
        String prefix = switch (source == null ? "all" : source.strip().toLowerCase(Locale.ROOT)) {
            case "qq" -> "qq-";
            case "web", "webui" -> "web-";
            default -> "bot-";
        };
        try {
            String stamp = java.time.LocalDate.now().toString().replace("-", "");
            Path file = settings.root.toAbsolutePath().normalize().resolve("logs/" + prefix + stamp + ".log");
            if (!Files.isRegularFile(file)) {
                // 今天的文件还没出现（例如机器人在零点前启动、当天还一条都没写）时回退到最近一份，
                // 免得「全部日志」在那个窗口里是空白。命名与挑选规则都在 Log 里（同一处口径）。
                file = Log.newestDailyFile(settings.root.toAbsolutePath().normalize().resolve("logs"), prefix, file);
                if (file == null) return result;
            }
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

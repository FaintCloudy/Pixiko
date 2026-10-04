package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;
import cn.szu.bot.web.WebUiServer;

/**
 * 网页控制台：独立端口、令牌鉴权、静态页面、结构化数据，以及"指令走机器人通道"的写操作。
 * 全程只连回环地址，不接触 QQ 与真实 Stable Diffusion（SD 用本机桩）。
 */
public final class WebUiTest {
    private static int checks;
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "webui").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        HttpServer sd = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicInteger sdWidth = new java.util.concurrent.atomic.AtomicInteger(512);
        java.util.concurrent.atomic.AtomicInteger sdHeight = new java.util.concurrent.atomic.AtomicInteger(512);
        java.util.concurrent.atomic.AtomicInteger sdRevision = new java.util.concurrent.atomic.AtomicInteger(1);
        java.util.concurrent.atomic.AtomicReference<String> sdSampler = new java.util.concurrent.atomic.AtomicReference<>("Euler a");
        sd.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            // 桥的桩要和真的桥一样确认参数修改：网页「改完即生效」要靠读回新值来判断成功。
            if (path.equals("/pixiko-bridge/v1/prompts") && "PUT".equals(exchange.getRequestMethod())) {
                JsonObject update = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (update.has("width")) sdWidth.set(update.get("width").getAsInt());
                if (update.has("height")) sdHeight.set(update.get("height").getAsInt());
                if (update.has("sampler_name")) sdSampler.set(update.get("sampler_name").getAsString());
                sdRevision.incrementAndGet();
            }
            String body = "{}";
            if (path.equals("/pixiko-bridge/v1/prompts"))
                body = "{\"positive\":\"base prompt\",\"negative\":\"bad\",\"source\":\"webui-live\",\"revision\":" + sdRevision.get()
                        + ",\"sampler_name\":\"" + sdSampler.get() + "\",\"styles\":[],\"width\":" + sdWidth.get()
                        + ",\"height\":" + sdHeight.get() + ",\"settings_initialized\":true}";
            else if (path.equals("/sdapi/v1/prompt-styles"))
                body = "[{\"name\":\"小鸟风格\",\"prompt\":\"kotori, red ribbon\",\"negative_prompt\":\"blur\"},"
                        + "{\"name\":\"军装少女\",\"prompt\":\"military uniform, girl\",\"negative_prompt\":\"blur\"}]";
            else if (path.equals("/sdapi/v1/loras")) body = "[{\"name\":\"kotori.safetensors\",\"alias\":\"kotori\",\"path\":\"x\"}]";
            else if (path.equals("/sdapi/v1/samplers")) body = "[{\"name\":\"Euler a\",\"aliases\":[]}]";
            else if (path.equals("/sdapi/v1/progress"))
                body = "{\"progress\":0.45,\"eta_relative\":12.5,\"state\":{\"job\":\"scripts_txt2img\",\"job_count\":1,"
                        + "\"sampling_step\":9,\"sampling_steps\":20},\"current_image\":null}";
            else if (path.equals("/sdapi/v1/options")) body = "{\"sd_model_checkpoint\":\"Model A [aaaa]\"}";
            else if (path.equals("/sdapi/v1/sd-models")) body = "[{\"title\":\"Model A [aaaa]\"}]";
            else if (path.endsWith("/chat/completions")) {
                // DeepSeek 桩：把请求里的词条逐个翻成「释义·<词条>」，用来验证"词库查不到就问模型"这条路。
                JsonObject meanings = new JsonObject();
                try {
                    JsonObject request = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    JsonArray messages = request.getAsJsonArray("messages");
                    String user = messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
                    for (JsonElement term : JsonParser.parseString(user).getAsJsonArray())
                        meanings.addProperty(term.getAsString(), "释义·" + term.getAsString());
                } catch (Exception ignored) { /* 桩里解析失败就当没词条 */ }
                JsonObject answer = new JsonObject();
                answer.addProperty("content", Json.GSON.toJson(Json.parse("{\"meanings\":" + meanings + "}")));
                answer.addProperty("role", "assistant");
                JsonObject choice = new JsonObject();
                choice.addProperty("finish_reason", "stop");
                choice.add("message", answer);
                JsonArray choices = new JsonArray(); choices.add(choice);
                JsonObject completion = new JsonObject(); completion.add("choices", choices);
                body = completion.toString();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        sd.start();
        try {
            JsonObject sdConfig = new JsonObject();
            sdConfig.addProperty("base_url", "http://127.0.0.1:" + sd.getAddress().getPort());
            JsonObject config = new JsonObject();
            config.add("sd", sdConfig);
            JsonObject progen = new JsonObject();
            // 生图/提示词频道指向同一个本地桩：词条释义兜底那条路要能真的走通（不碰外网）。
            progen.addProperty("api_base", "http://127.0.0.1:" + sd.getAddress().getPort());
            config.add("progen", progen);
            JsonObject webui = new JsonObject();
            webui.addProperty("enabled", true);
            webui.addProperty("host", "127.0.0.1");
            webui.addProperty("port", 0);   // 由测试改成空闲端口
            webui.addProperty("access_token", "test-token-123456");
            config.add("webui", webui);
            Json.atomicWrite(root.resolve("config.json"), config);
            Files.createDirectories(root.resolve("data/generated"));
            Files.write(root.resolve("data/generated/sample.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            Files.writeString(root.resolve("data/deepseek-api-key.txt"), "test-key-not-real\n");
            Files.writeString(root.resolve("data/prompt-usage.json"), """
                {"version":1,"source":"fixture","categories":[{"name":"场景","children":[],"tags":[
                {"prompt":"night","meaning":"夜晚"},{"prompt":"grass","meaning":"草地"}]}]}""");

            int port = freePort();
            Settings settings = new Settings(root);
            settings.webSetting("port", new JsonPrimitive(port));
            SdClient client = new SdClient(root, sdConfig);
            List<JsonArray> sent = new CopyOnWriteArrayList<>();
            Bot.Sender sender = new Bot.Sender() {
                @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                    sent.add(segments.deepCopy());
                    return CompletableFuture.completedFuture(null);
                }
                @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
                    return CompletableFuture.completedFuture(new JsonObject());
                }
            };
            try (Bot bot = new Bot(settings, client, sender)) {
                try (WebUiServer server = new WebUiServer(settings, bot)) {
                    server.start();
                    String base = "http://127.0.0.1:" + port;
                    authentication(base);
                    staticFiles(base);
                    agentRail(base);
                    statusAndLists(base, root);
                    promptEditing(base, root);
                    internalPanelEndpoints(base, root);
                    generationPanel(base, root);
                    generationProgress(base);
                    consoleCommands(base, root);
                    imageGuard(base, root);
                    configEndpoints(base, root, sd.getAddress().getPort());
                    System.out.println("WebUiTest: " + checks + " assertions passed：鉴权、静态页、全局对话栏、状态、列表、提示词编辑、内部面板接口、生成参数即时生效、控制台并发指令、图片路径校验、网页端配置");
                }
            }
        } finally {
            sd.stop(0);
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static JsonObject get(String base, String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Json.parse(response.body().isBlank() ? "{}" : response.body());
    }

    private static int status(String base, String path, String token, JsonObject body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString(), StandardCharsets.UTF_8));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).statusCode();
    }

    private static JsonObject post(String base, String path, String token, JsonObject body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString(), StandardCharsets.UTF_8));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) throw new AssertionError("HTTP " + response.statusCode() + "：" + response.body());
        return Json.parse(response.body().isBlank() ? "{}" : response.body());
    }

    /** 没有令牌时所有 /api 都必须拒绝，健康检查与静态页不受影响。 */
    private static void authentication(String base) throws Exception {
        check(status(base, "/api/status", null, null) == 401, "无令牌访问 API 被拒绝");
        check(status(base, "/api/status", "wrong-token", null) == 401, "错误令牌被拒绝");
        check(status(base, "/api/status", "test-token-123456", null) == 200, "正确令牌放行");
        check(status(base, "/healthz", null, null) == 200, "健康检查不需要令牌");
        JsonObject status = get(base, "/api/status", "test-token-123456");
        check(status.has("chatChannel") && status.has("imageChannel"), "状态里包含两条 DeepSeek 通道");
        check(status.get("chatChannel").getAsJsonObject().get("keyFile").getAsString().contains("deepseek-chat-api-key"),
                "聊天频道指向独立密钥文件");
        // L4：网页控制台要能只读看到好感度与情绪。
        check(status.has("affinity") && status.getAsJsonObject("affinity").has("score")
                && status.getAsJsonObject("affinity").has("tier"), "状态里包含好感度 score/tier");
        check(status.has("mood") && status.getAsJsonObject("mood").has("mood")
                && status.getAsJsonObject("mood").has("intensity"), "状态里包含情绪 mood/intensity");
    }

    private static void staticFiles(String base) throws Exception {
        HttpResponse<String> index = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(index.statusCode() == 200 && index.body().contains("神户小鸟") && index.body().contains("Pixiko"),
                "首页可访问，标题是项目名 Pixiko、品牌位是机器人名");
        check(index.body().contains("viewport"), "页面包含移动端 viewport");
        // 每个栏目是一个独立页面：首页（＝出图页）只有出图面板，其它栏目的 DOM 不在这一页上；
        // 对话栏已经全局化，它是外壳的一部分（见 agentRail），不再是栏目、也不在左栏里。
        check(index.body().contains("id=\"panel-gen\"") && !index.body().contains("id=\"panel-styles\""),
                "首页只有出图栏目自己的面板（不是把所有栏目塞进一页）");
        check(countOf(index.body(), "role=\"tab\"") == 11 && index.body().contains("href=\"/gen\"")
                        && index.body().contains("href=\"/styles\"") && index.body().contains("href=\"/quest\"")
                        && index.body().contains("href=\"/setup\""),
                "页签是十一个真链接（每个栏目一个 URL，含 Setup；对话不再占页签）");
        check(index.body().contains("id=\"viewer\"") && index.body().contains("id=\"viewer-image\"")
                        && index.body().contains("id=\"viewer-stage\""),
                "页面带图片查看器（点图放大，不再跳新标签页）");
        check(index.body().contains("aurora") || index.body().contains("gradient") || index.body().contains("card"),
                "页面使用卡片式布局");
        HttpResponse<String> css = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/app.css")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(css.statusCode() == 200 && css.body().contains("transition") && css.body().contains("linear-gradient"),
                "样式表包含渐变与过渡动画");
        check(css.body().contains("[hidden] { display: none !important; }"), "隐藏元素不会被作者样式覆盖（登录页 bug 的根因）");
        check(css.body().contains("appearance: none") && css.body().contains("data:image/svg+xml"),
                "下拉框用自定义的双向箭头（不用系统默认样式）");
        check(css.body().contains("body.console-full") && css.body().contains(".prompt-line"),
                "控制台全屏模式与提示符的样式都在 CSS 里");
        check(css.body().contains(".terminal-tail { height: min(68vh, 620px); }"),
                "提示符下面那片空白的高度写死在 CSS 里（把提示符托到屏幕上方三分之一处）");
        check(css.body().contains("font: 15px/1.72") && css.body().contains("font: 15px/1.6"),
                "控制台正文与提示符都是 15px（日志/回执看着不费劲）");
        check(css.body().contains(".receipt img.receipt-image") && css.body().contains("max-width: min(100%, 700px)"),
                "回执栏（面板底部）的图片最大给到 700px");
        check(css.body().contains("max-width: min(100%, 260px)") && css.body().contains(".msg .gallery-grid img"),
                "对话页单张图收到与回执页一致的 260px，图集格另有一段显式规则钉住（不被带成 260px）");
        check(css.body().contains("img.civitai-cover") && css.body().contains("aspect-ratio: 832 / 1216"),
                "LoRA 搜索结果的封面按 832:1216 竖版显示（不再裁成正方形）");
        check(css.body().contains("::-webkit-scrollbar") && css.body().contains("scrollbar-color"),
                "滚动条统一美化（webkit + Firefox）");
        check(css.body().contains(".dialog-pre"), "信息框里有展示原文的区域");
        check(css.body().contains("body.viewer-open") && css.body().contains(".viewer img"),
                "图片查看器：打开时锁滚动、图片按视口缩放");
        check(css.body().contains("a.image-link") && !css.body().contains("a.image-link { display: inline-block"),
                "图片链接的 display 交给各容器（网格卡片不能被顶成行内块）");
        // 站点图标：personal-blog\misc\kotori.jpg 转出来的一套（work/make-favicon.py）。
        check(index.body().contains("/favicon.ico") && index.body().contains("apple-touch-icon"),
                "页面引用了站点图标（favicon.ico + apple-touch-icon）");
        HttpResponse<byte[]> icon = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/favicon.ico")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        check(icon.statusCode() == 200 && icon.body().length > 1000
                        && icon.body()[0] == 0 && icon.body()[1] == 0 && icon.body()[2] == 1 && icon.body()[3] == 0,
                "favicon.ico 是真的 ICO 文件（" + icon.body().length + " 字节）");
        check(String.valueOf(icon.headers().firstValue("Content-Type").orElse("")).startsWith("image/x-icon"),
                "favicon.ico 的 Content-Type 是 image/x-icon：" + icon.headers().firstValue("Content-Type").orElse(""));
        HttpResponse<byte[]> touch = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/apple-touch-icon.png")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        check(touch.statusCode() == 200 && touch.body().length > 1000, "apple-touch-icon.png 可访问（" + touch.body().length + " 字节）");
        HttpResponse<String> script = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/app.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(script.statusCode() == 200 && script.body().contains("pollCapture"), "前端脚本可访问");
        check(script.body().contains("askDialog") && script.body().contains("askConfirm"),
                "确认框用同风格 UI 实现（弹出时背景压暗）");
        check(!script.body().contains("confirm('") && !script.body().contains("prompt('"),
                "代码里没有残留的浏览器自带 confirm/prompt");
        check(script.body().contains("/api/generation"), "生成参数改完直接发到 /api/generation 立即生效");
        check(script.body().contains("function openViewer") && script.body().contains("function closeViewer")
                        && script.body().contains("function toggleViewerScale"),
                "图片查看器支持放大、适应屏幕/1:1 切换与关闭");
        check(script.body().contains("function imageNode") && countOf(script.body(), "imageNode(") >= 5,
                "所有出图位置（对话、回执、网格、控制台）都走同一个图片节点");
        check(!script.body().contains("function receiptGroup") && script.body().contains("function appendCaptureGroup"),
                "面板底部不再渲染回执卡（对话仍按出站消息分条渲染）");
        check(script.body().contains("function chatAppendImages")
                        && script.body().substring(script.body().indexOf("async function pollCapture"),
                                script.body().indexOf("async function runCommands")).contains("chatAppendImages(id, files)"),
                "对话里一次发送的多张图合成一条图集（连续图片组攒起来交给 chatAppendImages，含文字的组仍各自成条）");
        check(script.body().contains("chatImageRun: null")
                        && countOf(script.body(), "state.chatImageRun = null") >= 3
                        && script.body().contains("chatAppendImages(capture.id || '', missing, pictureBase)"),
                "图集游标只在运行时用：清空对话 / 铺回历史时作废，回执补漏也并进同一张图集");
        check(!script.body().contains("（一张图片）"),
                "对话里的图片条目不再有「（一张图片）」占位文字（只发图的那条只有图）");
        check(script.body().contains("chat-gallery-entry") && css.body().contains(".messages .msg.chat-gallery-entry"),
                "带图集的气泡有确定宽度（chat-gallery-entry + align-self:stretch），网格才能排成一行多格");
        // 对话栏全局化（外壳）+ 服务端正文存档：右栏在每条路由都在，历史照常铺；存档走 /api/chat/log（读）
        // 与 /api/chat/log/save（整份覆盖写），本地变化后防抖 800ms 推一次，PAGE 兜底不再是 'chat'。
        check(script.body().contains("'/api/chat/log'") && script.body().contains("'/api/chat/log/save'")
                        && script.body().contains("CHAT_LOG_PUSH_DELAY = 800")
                        && script.body().contains("const PAGE = window.PIXIKO_PAGE || 'gen';"),
                "对话正文存档接在 /api/chat/log 与 /api/chat/log/save 上（防抖 800ms），PAGE 兜底是出图页");
        check(!script.body().contains("PAGE === 'chat'")
                        && countOf(script.body(), "await loadChatHistory()") == 1
                        && script.body().contains("await chatArchiveMerge(log, saved)"),
                "前端不再有 'chat' 栏目语义：loadChatHistory 在 loadPage 里每个栏目都调（含服务端存档的合并）");
        check(css.body().contains(".agent-rail #chat-log") && !css.body().contains("#panel-chat")
                        && css.body().contains("--rail-w-fixed: calc((min(var(--rail-page) - 2 * var(--rail-pad), 100% - 2 * var(--rail-pad)) - var(--rail-gap)) / 3);")
                        && css.body().contains("margin-right: calc(var(--rail-w-flow) + var(--rail-gap));")
                        && css.body().contains("height: min(60vh, 520px)")
                        && css.body().contains("body.console-full { --rail-page: 100%; --rail-pad: 0px; --rail-stick-top: 0px; }")
                        && css.body().contains(".shell .terminal { width: auto; margin-left: 0; }"),
                "右栏样式挂在 .agent-rail 上（旧的 #panel-chat 死规则已迁走）：宽屏固定右栏宽 = (内容宽-间距)/3、"
                        + "左栏留白对称、窄屏单列 60vh/520px、console-full 仍保持两栏");
        check(script.body().contains("openViewer(src, caption)") && script.body().contains("点击放大（Esc 关闭）"),
                "点图直接调查看器，链接上只留提示不再跳转");
        check(script.body().contains("applyGeneration(true)"), "点「开始生成」前先把面板里没提交的改动发出去");
        check(script.body().contains("function questCloud") && script.body().contains("function loadQuest")
                        && script.body().contains("function renderQuest") && script.body().contains("/api/quest")
                        && script.body().contains("'/quest#' + number"),
                "任务信息云带 /quest#N 链接（不是 /quest/#N：那会落进 /quest/ 而 404），回执页实时轮询 /api/quest");
        check(css.body().contains(".quest-clouds") && css.body().contains(".quest-cloud")
                        && css.body().contains(".quest-step"),
                "信息云与回执页的样式都在");
        check(script.body().contains("/api/lora/download") && !script.body().contains("weight.value"),
                "LoRA 下载走控制台自己的接口（不是指令通道），也不带权重输入框");
        check(css.body().contains("img.row-cover") && css.body().contains(".lora-progress-fill")
                        && css.body().contains("@keyframes lora-slide"),
                "列表小封面与下载进度条的样式都在（进度条有不确定态动画）");
        // LoRA 那块只在 /loras 这一页里（每页只留自己栏目的 DOM），所以单独取一次。
        HttpResponse<String> lorasPage = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/loras")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(lorasPage.statusCode() == 200 && lorasPage.body().contains("id=\"lora-progress\"")
                        && lorasPage.body().contains("id=\"lora-progress-fill\"")
                        && lorasPage.body().contains("id=\"lora-cover-btn\""),
                "LoRA 面板带下载进度条与「补抓展示图」按钮");
        check(lorasPage.body().contains("id=\"lora-list\"") && !lorasPage.body().contains("id=\"panel-chat\""),
                "/loras 只渲染 LoRA 这一块");
        check(script.body().contains("function watchLoraProgress") && script.body().contains("/api/lora/progress")
                        && script.body().contains("setInterval(tick, 1000)"),
                "下载进度每秒轮询一次（实时显示字节/百分比）");
        check(script.body().contains("function renderLoraProgress") && script.body().contains("indeterminate")
                        && script.body().contains("data.metered"),
                "没字节数可报时进度条走不确定态，不显示成 0%");
        check(script.body().contains("function loraPreviewUrl") && script.body().contains("'/api/lora/preview?token='")
                        && script.body().contains("function previewThumb"),
                "本机 LoRA 列表用 /api/lora/preview 显示真的展示图（<img> 只能把令牌挂查询串上）");
        check(script.body().contains("function stylePreviewUrl") && script.body().contains("'/api/style/preview?token='")
                        && script.body().contains("previewThumb(item.preview ? stylePreviewUrl"),
                "样式列表也带预览图（同样走查询串上的令牌）");
        check(script.body().contains("startLoraJob('/api/lora/cover'") && script.body().contains("function describeEta"),
                "「补抓展示图」按钮走 /api/lora/cover（顺带把 ETA 转成人话）");
        check(script.body().contains("(失败|错误|不正确|无效|超时|拒绝|找不到)[:：]") && script.body().contains("function runInfo"),
                "成功类回执不再往面板底部堆卡片（错误与图片仍然保留），要看内容走对话框");
        check(script.body().contains("function updateQueueSummary") && script.body().contains("updateQueueSummary(queue)")
                        && script.body().contains("updateQueueSummary(status.generation?.status)"),
                "任务队列摘要同时由状态接口与进度轮询刷新（1.5 秒一次）");
        check(script.body().contains("function growTerminalTail") && script.body().contains("function resetTerminalTail")
                        && script.body().contains("addEventListener('wheel'"),
                "滚到底时提示符下面的空白自动增长（可以无限往下滑，把提示符一直往上顶）");
        // Civitai 搜索改成卡片（内部接口返回结构化结果），不再像 QQ 那样发一条消息/回执。
        check(script.body().contains("/api/civitai/search") && script.body().contains("function renderCivitai")
                        && script.body().contains("/api/civitai/thumb")
                        && !script.body().contains("'.lora query '"),
                "Civitai 搜索走内部接口并把封面+信息渲染在卡片里（不发消息）");
        check(script.body().contains("body.scrollTop + body.clientHeight < body.scrollHeight - 4"),
                "只有真的滚到边界才补空白（程序自己的自动滚动不会触发）");
        check(script.body().contains("body.insertBefore(node, prompt)")
                        && script.body().contains("querySelectorAll(':scope > .line')"),
                "日志插在提示符之前、行数只按日志行算（提示符与底部空白不会被挤掉）");
        check(script.body().contains("async function waitCaptureSettled")
                        && script.body().contains("await waitCaptureSettled(capture && capture.id)")
                        && script.body().lastIndexOf("await loadPrompt().catch(() => {})")
                                > script.body().indexOf("await waitCaptureSettled(capture && capture.id)"),
                "智能改写跑完（等 DeepSeek）再刷新正反向提示词，不刷旧内容");
        check(script.body().contains("/运行中/.test(queue)"),
                "队列是否为空看状态词：队列文字空闲时也有内容，不然会一直显示「排队中」");
        // 样式与 LoRA 的行内操作里都要有「改名」按钮（LoRA 以前只能点名字改，不好发现）。
        check(countOf(script.body(), "actionButton('改名'") >= 2,
                "样式与 LoRA 的每一行都有「改名」按钮：" + countOf(script.body(), "actionButton('改名'"));
        check(script.body().contains("const prefix = original.slice"),
                "就地改名后把名字写回标签（不能只 commit，否则行里一直留着输入框）");
        // 长列表（样式/LoRA）必须增量更新：整表 innerHTML='' 重画会让删除时整页"晃"。
        check(script.body().contains("function syncRows"), "列表增量同步（删除只摘掉那一行）");
        check(script.body().contains("const styleRows = new Map()") && script.body().contains("const loraRows = new Map()"),
                "样式与 LoRA 列表都按名称复用已有的行");
        check(css.body().contains(".list li.fresh { animation"), "只有新出现的行才播放入场动画（其余行不再重放）");
        HttpResponse<String> escape = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/../config.json")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        // Tomcat 会先把带 .. 的路径挡掉（400），能走到控制器的也会因为不在白名单里而 404：都不是 200、也拿不到文件内容。
        check(escape.statusCode() != 200 && !escape.body().contains("access_token"),
                "静态文件不能穿越到工作目录之外（" + escape.statusCode() + "）");
        HttpResponse<String> unknown = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/nope.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(unknown.statusCode() == 404 && unknown.body().contains("error"), "未知静态路径返回 404 JSON：" + unknown.statusCode());
    }

    /**
     * 对话栏全局化：对话不再是栏目页，而是外壳的一部分——左 2/3 是栏目、右 1/3 固定是对话，
     * 所以每条路由（含首页＝出图页）的正文里都带同一套 #chat-* 控件，而且都不再有 panel-chat。
     */
    private static void agentRail(String base) throws Exception {
        String[] routes = {"/", "/chat", "/gen", "/styles", "/logs"};
        String home = null, alias = null;
        for (String route : routes) {
            HttpResponse<String> page = HTTP.send(HttpRequest.newBuilder(URI.create(base + route)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            check(page.statusCode() == 200, route + " 页面可访问（" + page.statusCode() + "）");
            // 右栏（对话）在每个栏目都在：三个关键控件一个都不少。
            check(page.body().contains("id=\"chat-log\"") && page.body().contains("id=\"chat-form\"")
                            && page.body().contains("id=\"chat-send\""),
                    route + " 页面带着全局对话栏（chat-log / chat-form / chat-send）");
            // 对话不再是栏目：任何一页都不再有它的面板与面板块标记。
            check(!page.body().contains("id=\"panel-chat\"") && !page.body().contains("<!--#panel:chat-->"),
                    route + " 页面上对话不是栏目（没有 panel-chat）");
            // 页签里也没有对话入口，其余页签照旧。
            check(!page.body().contains("data-tab=\"chat\"") && page.body().contains("data-tab=\"gen\"")
                            && page.body().contains("data-tab=\"styles\""),
                    route + " 页签里没有对话入口，其余页签仍在");
            // 两栏骨架：左 workspace（栏目）、右 agent-rail（对话）。
            check(page.body().contains("id=\"workspace\"") && page.body().contains("id=\"agent-rail\""),
                    route + " 页面是「左栏目 + 右对话栏」的两栏骨架");
            // 对话栏在 app.js 之前解析：脚本跑起来时它的 DOM 已经在了。
            check(page.body().indexOf("id=\"chat-log\"") < page.body().indexOf("/app.js"),
                    route + " 的对话栏在 app.js 之前");
            if (route.equals("/")) home = page.body();
            if (route.equals("/chat")) alias = page.body();
        }
        // 出图页才是首页：/ 与 /chat 注入的页面标记都是 gen（对话栏不参与栏目切换）。
        check(home != null && home.contains("window.PIXIKO_PAGE = \"gen\""), "首页注入的面板标记是 gen（出图页）");
        check(alias != null && alias.contains("window.PIXIKO_PAGE = \"gen\""), "/chat 注入的面板标记也是 gen");
        check(home != null && home.contains("class=\"tab active\" data-tab=\"gen\""), "首页的当前页签是「出图」");
        // 页面标记只出现一次，而且真的紧贴在 app.js 的 <script> 之前：脚本标签带 ?v= 查询串也要认出来，
        // 不能再落到 </head> 兜底（兜底留着救"脚本标签形式又变了"的情况）。
        check(home != null && countOf(home, "window.PIXIKO_PAGE") == 1
                        && java.util.regex.Pattern.compile("<script>window\\.PIXIKO_PAGE = \"gen\";</script>\\s*<script src=\"/app\\.js")
                                .matcher(home).find(),
                "首页的 window.PIXIKO_PAGE 只出现一次，且紧贴在 app.js 之前（不是 </head> 兜底）");
        check(alias != null && countOf(alias, "window.PIXIKO_PAGE") == 1
                        && java.util.regex.Pattern.compile("<script>window\\.PIXIKO_PAGE = \"gen\";</script>\\s*<script src=\"/app\\.js")
                                .matcher(alias).find(),
                "/chat 的 window.PIXIKO_PAGE 只出现一次，且紧贴在 app.js 之前");
        // 旧链接别名：/chat 与 / 渲染同一页（对话栏是外壳，两条路径都是出图页）。
        check(home != null && home.equals(alias), "/chat 与 / 渲染的是同一页（旧链接不 404、也不换成别的栏目）");
        HttpResponse<String> slash = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/chat/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(slash.statusCode() == 200, "/chat/（尾斜杠）也返回 200：" + slash.statusCode());
        check(slash.body().equals(alias), "/chat/ 与 /chat 同构（尾斜杠不 404）");
    }

    private static void statusAndLists(String base, Path root) throws Exception {
        String token = "test-token-123456";
        JsonObject status = get(base, "/api/status", token);
        // 网页与 QQ 独立：scope 是自己的 "web"，状态里不再出现 owner QQ / admin 名单 / 地图。
        check(status.get("scope").getAsString().equals(Settings.WEB_SCOPE),
                "网页使用自己独立的提示词归属：" + status.get("scope").getAsString());
        check(!status.has("ownerId") && !status.has("admins") && !status.has("maps"),
                "状态里不再包含 QQ 侧身份信息：" + status.keySet());
        check(status.has("civitai") && !status.getAsJsonObject("civitai").get("hasCookie").getAsBoolean(),
                "状态里带 Civitai 账号情况（未配置）：" + status.get("civitai"));
        JsonObject styles = get(base, "/api/styles", token);
        // 样式只有机器人这一份：接口列表来自 data/local-styles.json（这个用例里是空的）。
        check(styles.getAsJsonArray("styles").isEmpty() && styles.get("library").getAsInt() == 0,
                "样式接口只返回机器人样式库：" + styles);
        check(styles.has("local") && !styles.has("loaded") && !styles.has("pageSelected"),
                "样式接口只给样式库，不带 WebUI 页面勾选、也不带「当前载入」状态：" + styles);
        // 机器人自己的样式会出现在同一个列表里。
        Files.createDirectories(root.resolve("data"));
        Json.atomicWrite(root.resolve("data/local-styles.json"), Json.parse(
                "{\"version\":1,\"styles\":[{\"name\":\"小鸟风格\",\"positive\":\"kotori\",\"negative\":\"blur\",\"updated_at\":\"\"}]}"));
        JsonObject withLocal = get(base, "/api/styles", token);
        check(withLocal.getAsJsonArray("styles").size() == 1, "本机样式出现在网页列表：" + withLocal);
        check(withLocal.getAsJsonArray("styles").get(0).getAsJsonObject().get("number").getAsInt() == 1, "样式带 1 起编号");
        check(withLocal.getAsJsonArray("styles").get(0).getAsJsonObject().get("name").getAsString().equals("小鸟风格"), "样式名称正确");
        check(withLocal.getAsJsonArray("styles").get(0).getAsJsonObject().get("source").getAsString().equals("local"), "样式标注为机器人样式");
        // 样式的原文随列表返回：网页「查看原文」直接弹信息框，不用再走指令通道。
        check(withLocal.getAsJsonArray("styles").get(0).getAsJsonObject().has("positive")
                        && withLocal.getAsJsonArray("styles").get(0).getAsJsonObject().has("negative"),
                "样式列表带正反向原文：" + withLocal.getAsJsonArray("styles").get(0));
        // 样式的预览图（只给网页看）：每项带 preview 标志，没有图就是 false。
        check(withLocal.getAsJsonArray("styles").get(0).getAsJsonObject().has("preview")
                        && !withLocal.getAsJsonArray("styles").get(0).getAsJsonObject().get("preview").getAsBoolean(),
                "样式列表带 preview 标志（这个用例没放图，必须是 false）：" + withLocal.getAsJsonArray("styles").get(0));
        JsonObject missingStylePreview = get(base, "/api/style/preview?name=小鸟风格", token);
        check(missingStylePreview.has("error"), "样式没有预览图时接口给明确错误：" + missingStylePreview);
        JsonObject loras = get(base, "/api/loras", token);
        check(loras.getAsJsonArray("loras").size() == 1 && loras.getAsJsonArray("loras").get(0).getAsJsonObject().get("name").getAsString().equals("kotori.safetensors"),
                "LoRA 列表正确");
        check(loras.getAsJsonArray("loras").get(0).getAsJsonObject().has("preview"),
                "每项都带 preview 标志（前端据此显示缩略图或「无图」）：" + loras.getAsJsonArray("loras").get(0));
        check(!loras.getAsJsonArray("loras").get(0).getAsJsonObject().get("preview").getAsBoolean(),
                "这个临时目录里没有 .preview.png，preview 必须是 false 而不是靠猜");
        // 底模：这个桩既没有 Civitai 记录、Forge 元数据里也没有底模、更没有 Forge 预设栈，
        // 所以必须如实给空值 + 空分组（前端据此归到「未识别底模」），不许编一个名字。
        check(loras.getAsJsonArray("loras").get(0).getAsJsonObject().has("baseModel")
                        && loras.getAsJsonArray("loras").get(0).getAsJsonObject().get("baseModel").getAsString().isEmpty(),
                "每项都带 baseModel 字段（这里识别不出来，必须是空串）：" + loras.getAsJsonArray("loras").get(0));
        check(loras.getAsJsonArray("loras").get(0).getAsJsonObject().has("baseModelSource")
                        && loras.getAsJsonArray("loras").get(0).getAsJsonObject().has("groupKey"),
                "每项都带 baseModelSource 与 groupKey：" + loras.getAsJsonArray("loras").get(0));
        // 归属栈：这个桩既没有 Forge 预设、文件也不存在，栈必须如实给空串（前端归到「未识别栈」）。
        JsonObject onlyLora = loras.getAsJsonArray("loras").get(0).getAsJsonObject();
        check(onlyLora.has("stack") && onlyLora.get("stack").getAsString().isEmpty()
                        && onlyLora.has("stackLabel") && onlyLora.get("stackLabel").getAsString().isEmpty()
                        && onlyLora.has("stackSource") && onlyLora.has("preset") && onlyLora.has("baseModelGroupKey"),
                "每项都带 stack/stackLabel/stackSource/preset（判不出来必须是空串）：" + onlyLora);
        check(!onlyLora.has("evidence") || onlyLora.get("evidence").getAsString().isEmpty(),
                "判不出栈时不给编造的判据：" + onlyLora);
        check(loras.has("groups") && loras.getAsJsonArray("groups").size() == 1,
                "按底模分组的结果给在 groups 里（未识别的也算一组）：" + loras);
        JsonObject onlyGroup = loras.getAsJsonArray("groups").get(0).getAsJsonObject();
        check(onlyGroup.get("key").getAsString().isEmpty() && onlyGroup.get("count").getAsInt() == 1
                        && onlyGroup.getAsJsonArray("names").get(0).getAsString().equals("kotori.safetensors"),
                "未识别底模的组键是空串、成员是这个 LoRA：" + onlyGroup);
        check(onlyGroup.has("stack") && onlyGroup.has("label") && onlyGroup.has("baseModels"),
                "分组按栈给：key/stack/label/baseModels 都在（这里栈是空的）：" + onlyGroup);
        // 「基础模型」下拉的数据来源：每项带栈（这个桩没有 Forge，栈只能是空串，前端退回只显示名字）。
        JsonObject optionData = get(base, "/api/options", token);
        check(optionData.has("modelOptions") && optionData.getAsJsonArray("modelOptions").size() == 1,
                "/api/options 里带基础模型的归属信息：" + optionData);
        JsonObject onlyModel = optionData.getAsJsonArray("modelOptions").get(0).getAsJsonObject();
        check(onlyModel.has("title") && onlyModel.has("stack") && onlyModel.has("stackLabel")
                        && onlyModel.has("preset") && onlyModel.has("stackSource") && onlyModel.has("label")
                        && onlyModel.get("stack").getAsString().isEmpty(),
                "每个基础模型都带 stack/stackLabel/preset/stackSource（判不出来是空串）：" + onlyModel);
        JsonObject loraProgress = get(base, "/api/lora/progress", token);
        check(loraProgress.has("busy") && loraProgress.has("downloading") && loraProgress.has("metered")
                        && loraProgress.has("stage"),
                "进度接口给出 busy/downloading/metered/stage：" + loraProgress);
        check(!loraProgress.get("metered").getAsBoolean(), "没在下载时不能报字节进度");
        JsonObject missingPreview = get(base, "/api/lora/preview?name=nope", token);
        check(missingPreview.has("error"), "没有展示图时接口给明确错误而不是空图：" + missingPreview);
        // 控制台自己的 LoRA 写接口：缺参数要报明确错误（400），不能 500，也不能悄悄变成一次真下载。
        JsonObject blankDownload = postRaw(base, "/api/lora/download", token, new JsonObject());
        check(blankDownload.has("error") && blankDownload.get("error").getAsString().contains("链接"),
                "下载接口缺链接时给明确错误：" + blankDownload);
        JsonObject coverStatus = postRaw(base, "/api/lora/cover", token, new JsonObject());
        check(coverStatus.has("error") || coverStatus.has("busy"),
                "补展示图接口要么启动任务、要么说明原因（这里没配 lora_dir）：" + coverStatus);
        JsonObject logs = get(base, "/api/logs", token);
        check(logs.has("lines"), "日志接口可用");
        // SD 自启动：状态接口给"是否在跑 + 开关 + 启动入口"，网页据此显示卡片。
        JsonObject sd = get(base, "/api/sd/status", token);
        check(sd.has("reachable") && sd.has("autoStart") && sd.has("launcher") && sd.has("text"),
                "SD 状态接口给出运行状态与自启动开关：" + sd.keySet());
        check(sd.has("available"), "SD 状态接口说明有没有可用的启动入口：" + sd);
        // 系统页签要能显示四条本机接口地址（两条 DeepSeek、SD、NapCat），但不含任何密钥。
        JsonObject endpoints = get(base, "/api/status", token).getAsJsonObject("endpoints");
        check(endpoints != null && endpoints.has("chatApi") && endpoints.has("imageApi")
                && endpoints.has("sd") && endpoints.has("napcat"), "状态里带四条接口地址：" + endpoints);
        check(endpoints.get("chatApiUrl").getAsString().endsWith("/chat/completions"),
                "DeepSeek 地址给出完整的 completions URL：" + endpoints.get("chatApiUrl"));
        JsonObject help = get(base, "/api/help", token);
        check(help.get("help").getAsString().contains(".style list"), "帮助内容可读");
        // 每个指令域都有结构化数据支撑 UI：提示词集、参数预设、下拉选项、分类浏览。
        JsonObject functions = get(base, "/api/functions", token);
        check(functions.has("functions") && functions.has("active"), "提示词集接口返回列表与已加载项");
        JsonObject presets = get(base, "/api/presets", token);
        check(presets.has("presets") && presets.has("current"), "参数预设接口返回列表与当前参数");
        JsonObject options = get(base, "/api/options", token);
        check(options.getAsJsonArray("samplers").size() >= 1 && options.getAsJsonArray("models").size() >= 1, "下拉选项包含采样器与模型");
        JsonObject usage = post(base, "/api/usage", token, body(null));
        check(usage.has("text") && usage.has("choices"), "提示词分类浏览可用");
        // 每栏目一个页面：控件出现在它该在的那一页上，而不是所有页都堆在一起。
        HttpResponse<String> page = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, String[]> expected = new LinkedHashMap<>();
        // 首页＝出图页：对话栏已经全局化（见 agentRail），它不再是栏目，首页的面板就是 gen。
        expected.put("/", new String[]{"panel-gen", "gen-btn", "chat-log", "chat-send", "chat-reset"});
        expected.put("/gen", new String[]{"panel-gen", "set-width", "set-sampler", "gen-applied", "gen-progress-bar",
                "preset-save", "infix-filter", "task-list", "task-summary", "image-grid", "image-note"});
        expected.put("/prompt", new String[]{"panel-prompt", "prompt-positive", "prompt-add-btn", "usage-body", "undo-btn",
                "data-tag-complete"});
        expected.put("/styles", new String[]{"panel-styles", "style-batch-delete", "style-list", "style-save"});
        expected.put("/loras", new String[]{"panel-loras", "lora-count", "lora-filter", "lora-query", "civitai-grid"});
        expected.put("/functions", new String[]{"panel-functions", "function-list", "function-save"});
        expected.put("/chatcfg", new String[]{"panel-chatcfg", "apply-frequency", "set-personality", "set-chat-global"});
        expected.put("/system", new String[]{"panel-system", "sd-start-btn", "sd-auto-start", "sd-detail", "endpoint-list"});
        expected.put("/setup", new String[]{"panel-setup", "setup-owner", "setup-save", "setup-reload",
                "civitai-link", "civitai-link-save", "civitai-clear-btn"});
        expected.put("/logs", new String[]{"panel-logs", "terminal-body", "terminal-input", "terminal-fullscreen",
                "terminal-clear", "terminal-tail", "logs-source"});
        expected.put("/help", new String[]{"panel-help", "help-body"});
        for (Map.Entry<String, String[]> entry : expected.entrySet()) {
            HttpResponse<String> column = HTTP.send(HttpRequest.newBuilder(URI.create(base + entry.getKey())).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            check(column.statusCode() == 200, entry.getKey() + " 页面可访问（" + column.statusCode() + "）");
            for (String anchor : entry.getValue())
                check(column.body().contains(anchor), entry.getKey() + " 页面包含控件 " + anchor);
            check(column.body().contains("window.PIXIKO_PAGE"), entry.getKey() + " 页面带页面标记（前端据此只加载本栏目数据）");
            // 首页渲染的是出图页（对话栏是全局外壳、不占页签），所以它的当前页签是 gen。
            String tab = entry.getKey().equals("/") ? "gen" : entry.getKey().substring(1);
            check(column.body().contains("class=\"tab active\" data-tab=\"" + tab + "\""),
                    entry.getKey() + " 页面的当前页签是高亮的");
            // 面板自己也必须 active：.panel 默认 display:none，漏了 active 整页尺寸都是 0（终端布局会塌）。
            check(java.util.regex.Pattern
                            .compile("class=\"panel[^\"]*active[^\"]*\" id=\"panel-" + tab + "\"")
                            .matcher(column.body()).find(),
                    entry.getKey() + " 页面的面板是 active（不是 display:none）");
        }
        // 每个页面只包含自己的那一个面板（其它栏目的 DOM 不参与）。对话栏已经全局化、不再是面板，
        // 所以这里没有 panel-chat；首页与 /gen 是同一次渲染（都是出图页），panel-gen 出现在这两条路径上。
        for (String other : new String[]{"panel-gen", "panel-prompt", "panel-styles", "panel-loras",
                "panel-functions", "panel-chatcfg", "panel-system", "panel-setup", "panel-logs", "panel-help"}) {
            int seen = 0;
            for (String path : expected.keySet()) {
                HttpResponse<String> column = HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (column.body().contains("id=\"" + other + "\"")) seen++;
            }
            check(seen == (other.equals("panel-gen") ? 2 : 1),
                    "面板 " + other + " 只出现在自己的页面上（实际 " + seen + " 页）");
        }
        check(page.body().contains("id=\"viewer\"") && page.body().contains("id=\"viewer-image\"")
                        && page.body().contains("id=\"viewer-stage\""),
                "页面带图片查看器（点图放大，不再跳新标签页）");
        check(page.body().contains("overlay") && page.body().contains("dialog-ok") && page.body().contains("dialog-pre"),
                "页面带同风格确认框/信息框（每个页面都有外壳）");
        check(page.body().contains("console-full") == false, "全屏样式在 CSS 里（页面本身只带类名）");
        check(!page.body().contains("terminal-prompt"), "提示符与日志同流（不再有独立的指令框）");
        // 提示符跟在日志流末尾，它后面再留一片空白：日志把它压到底部时，这片空白把它托上来（不贴死底边）。
        HttpResponse<String> logsPage = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/logs")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(logsPage.body().contains("id=\"terminal-tail\"")
                        && logsPage.body().indexOf("id=\"terminal-form\"") < logsPage.body().indexOf("id=\"terminal-tail\""),
                "提示符后面留了一片底部空白（提示符不会被压到屏幕底边）");
        check(page.body().contains("Copyright") && page.body().contains("Powered by"),
                "页脚有版权与 powered by");
        // 智能改写是"出图前改提示词"的操作，归在出图面板里（不在提示词面板）。
        HttpResponse<String> genPage = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/gen")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(genPage.body().contains("id=\"infix-filter\"") && !genPage.body().contains("id=\"prompt-positive\""),
                "智能改写 .infix 在「出图」页面里（提示词页面上没有它）");
        // 任务只归「任务队列」卡片：出图页面不再重复一份队列文字。
        check(genPage.body().contains("id=\"task-summary\"") && !genPage.body().contains("gen-summary"),
                "队列摘要只在「任务队列」卡片里（出图页面不再显示当前任务）");
        check(page.body().contains("loriko"), "页脚署名作者 loriko");
        for (String gone : new String[]{"apply-size", "apply-sampler", "apply-model", "apply-steps", "apply-cfg", "apply-seed", "apply-imgcnt"})
            check(!genPage.body().contains(gone), "生成参数不再有「应用」按钮：" + gone);
        for (String gone : new String[]{"admin-add", "map-yh-set", "char-apply-btn", "jrlp-btn", "set-wake", "set-base"})
            check(!genPage.body().contains(gone), "QQ 专属控件已从网页面板移除：" + gone);
    }

    /**
     * 纯本机的面板操作走内部接口（不再拼 `.prompt remove …` 丢进指令通道）：
     * 提示词增删回退、提示词集、样式库、参数预设——一次往返拿回刷新后的整份面板数据。
     */
    private static void internalPanelEndpoints(String base, Path root) throws Exception {
        String token = "test-token-123456";
        // 提示词：整段替换 → 加词条 → 删词条 → 回退（每一步都同步返回最新的词条列表）
        JsonObject set = post(base, "/api/prompt/edit", token, panel("side", "positive", "action", "set", "value", "网页端, 红发"));
        check(set.get("positive").getAsString().equals("网页端, 红发"), "内部接口整段替换 prompt：" + set.get("positive"));
        check(set.get("message").getAsString().contains("整段替换"), "内部接口带上人话回执：" + set.get("message"));
        JsonObject add = post(base, "/api/prompt/edit", token, panel("side", "positive", "action", "add", "value", "微笑"));
        check(add.get("positive").getAsString().contains("微笑"), "内部接口加词条：" + add.get("positive"));
        check(add.getAsJsonArray("positiveItems").size() == add.getAsJsonArray("positiveTerms").size(),
                "返回的词条条目跟着刷新：" + add.getAsJsonArray("positiveItems").size());
        JsonObject remove = post(base, "/api/prompt/edit", token, panel("side", "positive", "action", "remove", "value", "微笑"));
        check(!remove.get("positive").getAsString().contains("微笑"), "内部接口按词条原文删：" + remove.get("positive"));
        check(remove.get("message").getAsString().contains("已删除"), "删除回执说明删掉了什么：" + remove.get("message"));
        JsonObject undo = post(base, "/api/prompt/edit", token, panel("side", "positive", "action", "undo"));
        check(undo.get("positive").getAsString().contains("微笑"), "内部接口可以回退：" + undo.get("positive"));
        JsonObject byNumber = post(base, "/api/prompt/edit", token, panel("side", "positive", "action", "remove", "value", "#1"));
        check(byNumber.get("message").getAsString().contains("已删除"), "按 #编号 也能删（内部解析实时列表）：" + byNumber.get("message"));
        // 反向 prompt 是另一份
        JsonObject negative = post(base, "/api/prompt/edit", token, panel("side", "negative", "action", "add", "value", "blur"));
        check(negative.get("negative").getAsString().contains("blur") && negative.get("positive").getAsString().contains("红发"),
                "正反向各改各的：" + negative.get("negative"));
        check(!new UserPromptStore(root).prompts(Settings.WEB_SCOPE).positive().isBlank(), "改动落在网页自己那份 prompt 上");

        // 提示词集：保存 → 加载 → 移出 → 改名 → 删除
        JsonObject saved = post(base, "/api/functions/edit", token, panel("action", "save", "name", "探针集"));
        check(saved.get("message").getAsString().contains("已保存"), "内部接口保存提示词集：" + saved.get("message"));
        JsonObject loaded = post(base, "/api/functions/edit", token, panel("action", "load", "name", "探针集"));
        check(loaded.getAsJsonArray("active").toString().contains("探针集"), "加载后 active 列表更新：" + loaded.getAsJsonArray("active"));
        JsonObject removed = post(base, "/api/functions/edit", token, panel("action", "remove", "name", "探针集"));
        check(!removed.getAsJsonArray("active").toString().contains("探针集"), "移出后 active 列表更新：" + removed.getAsJsonArray("active"));
        JsonObject renamed = post(base, "/api/functions/edit", token, panel("action", "rename", "name", "探针集", "newName", "探针集2"));
        check(renamed.get("message").getAsString().contains("探针集2"), "内部接口给提示词集改名：" + renamed.get("message"));
        // 正在使用的提示词集：网页上按了确认就直接删（内部先移出，不让用户卡在"请先 remove"）
        JsonObject reloaded = post(base, "/api/functions/edit", token, panel("action", "load", "name", "探针集2"));
        check(reloaded.getAsJsonArray("active").toString().contains("探针集2"), "删除前先加载它：" + reloaded.getAsJsonArray("active"));
        JsonObject deleted = post(base, "/api/functions/edit", token, panel("action", "delete", "name", "探针集2"));
        check(!names(deleted.getAsJsonArray("functions")).contains("探针集2") && deleted.getAsJsonArray("active").isEmpty(),
                "使用中的提示词集也能删掉（先移出再删）：" + deleted.get("message"));

        // 样式库：保存 → 改名 → 载入到个人 prompt → 删除（列表里可能已经有别的用例建的样式，按名字判断）
        JsonObject styleSaved = post(base, "/api/styles/edit", token, panel("action", "save", "name", "探针样式"));
        check(names(styleSaved.getAsJsonArray("styles")).contains("探针样式"), "内部接口保存样式：" + names(styleSaved.getAsJsonArray("styles")));
        JsonObject styleRenamed = post(base, "/api/styles/edit", token, panel("action", "rename", "name", "探针样式", "newName", "探针样式2"));
        check(names(styleRenamed.getAsJsonArray("styles")).contains("探针样式2")
                        && !names(styleRenamed.getAsJsonArray("styles")).contains("探针样式"),
                "内部接口给样式改名：" + names(styleRenamed.getAsJsonArray("styles")));
        JsonObject styleLoad = post(base, "/api/styles/edit", token, panel("action", "load", "name", "探针样式2"));
        check(styleLoad.get("message").getAsString().contains("探针样式2"), "内部接口载入样式：" + styleLoad.get("message"));
        JsonObject styleDeleted = post(base, "/api/styles/edit", token, panel("action", "delete", "name", "探针样式2"));
        check(!names(styleDeleted.getAsJsonArray("styles")).contains("探针样式2"), "内部接口删除样式：" + names(styleDeleted.getAsJsonArray("styles")));

        // 参数预设：保存 → 删除
        JsonObject presetSaved = post(base, "/api/presets/edit", token, panel("action", "save", "name", "探针预设"));
        check(names(presetSaved.getAsJsonArray("presets")).contains("探针预设"), "内部接口保存预设：" + names(presetSaved.getAsJsonArray("presets")));
        JsonObject presetRemoved = post(base, "/api/presets/edit", token, panel("action", "remove", "name", "探针预设"));
        check(!names(presetRemoved.getAsJsonArray("presets")).contains("探针预设"), "内部接口删除预设：" + names(presetRemoved.getAsJsonArray("presets")));

        // 词条释义：词库里有的直接用；查不到的（renderPrompt 里那批）问一次 DeepSeek，并把结果缓存下来
        // 这个用例的临时目录里没有内置中文词库，所以两个词条都要走"问模型"那条路（桩会把它们翻出来）。
        JsonObject learned = post(base, "/api/meanings", token, meanings("night", "grass"));
        check(learned.getAsJsonObject("meanings").has("night") && learned.getAsJsonObject("meanings").has("grass"),
                "词库查不到时问 DeepSeek 并拿到释义：" + learned.getAsJsonObject("meanings"));
        check(learned.get("asked").getAsInt() >= 2, "回执说明确实问过模型：" + learned.get("asked"));
        check(Files.isRegularFile(root.resolve("data/prompt-meanings.json")), "学到的释义落盘缓存（下次不再问）");
        // 第二次同一个词条：直接读缓存，不再问模型
        JsonObject cached = post(base, "/api/meanings", token, meanings("night"));
        check("释义·night".equals(cached.getAsJsonObject("meanings").get("night").getAsString()),
                "第二次直接读缓存的释义：" + cached.getAsJsonObject("meanings"));
        check(cached.get("asked").getAsInt() == 0, "缓存命中时不再问模型：" + cached.get("asked"));
        JsonObject skip = post(base, "/api/meanings", token, panel("learn", "false", "terms", "x"));
        check(skip.get("asked").getAsInt() == 0, "learn=false 时只查词库与缓存，不问模型：" + skip.get("asked"));

        // Civitai 搜索：结果是结构化数据（网页自己渲染成卡片），空搜索词与非法封面地址都被挡住。
        JsonObject emptySearch = postRaw(base, "/api/civitai/search", token, panel("query", ""));
        check(emptySearch.has("error") && emptySearch.get("error").getAsString().contains("搜索词"),
                "空搜索词被拒（不发网络请求）：" + emptySearch);
        JsonObject badCover = postRaw(base, "/api/civitai/thumb", token, panel("url", "https://example.com/x.jpg"));
        check(badCover.has("error") && badCover.get("error").getAsString().contains("Civitai"),
                "封面代理只允许 Civitai 图床：" + badCover);
        JsonObject noCover = postRaw(base, "/api/civitai/thumb", token, panel("url", ""));
        check(noCover.has("error"), "缺少封面地址被拒：" + noCover);
        JsonObject options = post(base, "/api/settings", token, panel("key", "autoGet", "value", "off"));
        check(!options.get("autoGet").getAsBoolean(), "自动领取开关走设置接口：" + options.get("autoGet"));
        post(base, "/api/settings", token, panel("key", "autoGet", "value", "on"));
    }

    /** /api/meanings 的请求体：一批词条。 */
    private static JsonObject meanings(String... terms) {
        JsonObject body = new JsonObject();
        JsonArray list = new JsonArray();
        for (String term : terms) list.add(term);
        body.add("terms", list);
        return body;
    }

    /** 内部接口的请求体：键值对按 k1,v1,k2,v2… 传。 */
    private static JsonObject panel(String... pairs) {
        JsonObject body = new JsonObject();
        for (int index = 0; index + 1 < pairs.length; index += 2) body.addProperty(pairs[index], pairs[index + 1]);
        return body;
    }

    /** 列表里的 name 字段（样式/提示词集/预设都用它，按名字判断，不依赖"列表里只有我这一条"）。 */
    private static List<String> names(JsonArray items) {
        List<String> result = new ArrayList<>();
        for (JsonElement item : items) result.add(item.getAsJsonObject().get("name").getAsString());
        return result;
    }

    /** 网页编辑提示词：命令真的执行、回执能取回、回退可用，而且写的是网页自己的那份提示词。 */
    private static void promptEditing(String base, Path root) throws Exception {
        String token = "test-token-123456";
        JsonObject capture = post(base, "/api/command", token, body(".prompt set console prompt, red hair"));
        String id = capture.get("id").getAsString();
        check(capture.has("quest") && capture.get("quest").getAsInt() > 0,
                "每条任务回执都带任务号（/quest/#N 用它定位）：" + capture.get("quest"));
        int quest = capture.get("quest").getAsInt();
        String texts = waitCapture(base, token, id);
        check(texts.contains("正向 prompt 已更新"), "指令回执能取回：" + texts);
        JsonObject questQuery = new JsonObject();
        questQuery.addProperty("id", quest);
        JsonObject byNumber = post(base, "/api/quest", token, questQuery);
        check(byNumber.has("id") && byNumber.get("id").getAsString().equals(id)
                        && byNumber.get("quest").getAsInt() == quest
                        && byNumber.has("messages") && byNumber.has("done"),
                "按任务号能取到同一条回执：" + byNumber);
        check(byNumber.get("closed").getAsBoolean(), "这条回执已经被 close 关掉（waitCapture 会关），但依然查得到");
        JsonObject latest = post(base, "/api/quest", token, new JsonObject());
        check(latest.get("quest").getAsInt() == quest, "不带任务号时给最新一条：" + latest.get("quest"));
        JsonObject outdated = new JsonObject();
        outdated.addProperty("id", 999999);
        JsonObject expired = postRaw(base, "/api/quest", token, outdated);
        check(expired.has("error") && expired.get("error").getAsString().contains("999999"),
                "过期的任务号要明确指出，不假装还在跑：" + expired);
        JsonObject prompt = post(base, "/api/prompt", token, body(null));
        check(prompt.get("positive").getAsString().equals("console prompt, red hair"), "提示词已写入：" + prompt.get("positive").getAsString());
        // 词条带中文释义（词库里查得到才有），网页的提示词面板按词条原文移除。
        JsonArray items = prompt.getAsJsonArray("positiveItems");
        check(items != null && items.size() == prompt.getAsJsonArray("positiveTerms").size(),
                "词条列表带结构化条目：" + items);
        check(items.get(0).getAsJsonObject().has("number") && items.get(0).getAsJsonObject().has("term")
                        && items.get(0).getAsJsonObject().has("meaning"),
                "每个词条给编号、原文与释义字段：" + items.get(0));
        check(prompt.get("canUndo").getAsInt() >= 1, "回退历史已记录");
        // 网页的修改落在自己的 scope 上，不会动 QQ 侧那份提示词。
        check(prompt.get("scope").getAsString().equals(Settings.WEB_SCOPE), "网页 scope 独立：" + prompt.get("scope"));

        JsonObject undo = post(base, "/api/command", token, body(".prompt undo"));
        String undone = waitCapture(base, token, undo.get("id").getAsString());
        check(undone.contains("已回退到上一次 prompt"), "网页可以回退：" + undone);
        JsonObject after = post(base, "/api/prompt", token, body(null));
        check(!after.get("positive").getAsString().equals("console prompt, red hair"), "回退真的生效：" + after.get("positive").getAsString());

        // 网页控制台已经用访问令牌鉴权过，因此不再要求 QQ 的 owner/admin 身份：
        // 这里用一个不存在的 QQ 号派发的网页事件也应当照常执行（旧行为会报「仅 owner 可用」）。
        JsonObject noQqIdentity = post(base, "/api/command", token, body(".prompt add console extra"));
        String appended = waitCapture(base, token, noQqIdentity.get("id").getAsString());
        check(appended.contains("已添加") && !appended.contains("仅 owner"),
                "网页指令不需要 QQ owner 身份：" + appended);

        // 回归：网页轮询回执、从不主动关闭；跑完之后下一条指令必须照样受理。
        // 曾经因为"跑完的收集器仍算进行中"，整个控制台永久 409「上一条指令还在执行」。
        JsonObject firstCommand = post(base, "/api/command", token, body(".help"));
        pollCapture(base, token, firstCommand.get("id").getAsString());
        JsonObject secondCommand = postRaw(base, "/api/command", token, body(".style list"));
        check(secondCommand.has("id"), "连续指令不会再被 409 挡住：" + secondCommand);
        String secondTexts = waitCapture(base, token, secondCommand.get("id").getAsString());
        check(secondTexts.contains("样式列表"), "第二条指令的回执正常：" + secondTexts);
        JsonObject thirdCommand = postRaw(base, "/api/command", token, body(".settings"));
        check(thirdCommand.has("id"), "第三条指令仍然受理：" + thirdCommand);
        waitCapture(base, token, thirdCommand.get("id").getAsString());
    }

    /** 控制台指令：编号按实时列表解析（不必先 .style list），并且可以并发异步执行。 */
    private static void consoleCommands(String base, Path root) throws Exception {
        String token = "test-token-123456";
        // 回归：控制台的 #编号 来自 /api/styles，从没跑过 .style list，
        // 以前会被"查询编号在等待聊天回复时已变化"直接拒绝，点"载入"永远失败。
        JsonObject load = post(base, "/api/command", token, body(".style load #1"));
        String loaded = waitCapture(base, token, load.get("id").getAsString());
        check(!loaded.contains("编号在等待聊天回复时已变化"), "控制台按 #编号 载入不再被编号守卫挡住：" + loaded);
        check(loaded.contains("已用样式"), "按实时列表解析编号并真的载入了样式：" + loaded);
        check(new UserPromptStore(root).prompts(Settings.WEB_SCOPE).positive().contains("kotori"),
                "样式文本写进了网页自己的提示词：" + new UserPromptStore(root).prompts(Settings.WEB_SCOPE).positive());

        // 并发：两条指令同时发出去，各自拿到独立回执，谁也不等谁（以前第二条会收到 409「上一条指令还在执行」）。
        JsonObject first = post(base, "/api/command", token, body(".style list"));
        JsonObject second = post(base, "/api/command", token, body(".sampler list"));
        check(!first.get("id").getAsString().equals(second.get("id").getAsString()), "并发指令各自拿到独立回执 id");
        String firstText = waitCapture(base, token, first.get("id").getAsString());
        String secondText = waitCapture(base, token, second.get("id").getAsString());
        check(firstText.contains("样式列表"), "并发第一条回执正常：" + firstText);
        check(secondText.contains("Euler a"), "并发第二条回执正常：" + secondText);

        // 控制台可以不加点和斜杠：真实 CLI 手感（help / style list / progress 都直接认）。
        JsonObject bare = post(base, "/api/command", token, body("style list"));
        String bareText = waitCapture(base, token, bare.get("id").getAsString());
        check(bareText.contains("样式列表"), "无前缀指令也执行：" + bareText);
        JsonObject bareUnknown = postRaw(base, "/api/command", token, body("这不是指令"));
        check(bareUnknown.has("id") || bareUnknown.has("error"), "认不出的裸词不会被硬塞成指令：" + bareUnknown);

        // 并发的**异步**指令：异步回执（LoRA 列表在工作线程上返回）必须回到发起它的那条指令，
        // 不能因为"最新的一条是别的指令"就串过去。
        JsonObject loraList = post(base, "/api/command", token, body(".lora list"));
        JsonObject settings = post(base, "/api/command", token, body(".settings"));
        String loraText = waitCapture(base, token, loraList.get("id").getAsString());
        String settingsText = waitCapture(base, token, settings.get("id").getAsString());
        check(loraText.contains("本地 LoRA"), "异步 LoRA 列表回执回到自己的指令：" + loraText);
        check(!settingsText.contains("正在读取 WebUI 本地 LoRA 列表"), "设置回执里没有混入别的指令的异步回执：" + settingsText);
        check(settingsText.contains("步数"), "设置回执本身正常：" + settingsText);

        // 回执要告诉前端"指令执行体跑完了"：控制台终端靠 done 判断可以收工，
        // 只看 busy 会在指令刚受理、回执还空着的一瞬间就停止跟随（`.rg`/`.gen` 的图就打不出来）。
        String freshId = post(base, "/api/command", token, body(".style list")).get("id").getAsString();
        JsonObject payload = null;
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            payload = post(base, "/api/capture", token, captureBody(freshId));
            if (payload.get("done").getAsBoolean() && !payload.getAsJsonArray("texts").isEmpty()) break;
            Thread.sleep(120);
        }
        check(payload != null && payload.get("done").getAsBoolean(), "回执带 done 标记（前端据此收工）：" + payload);
        check(payload.has("images") && payload.has("ageMillis"), "回执带图片列表与存活时长：" + payload.keySet());
        // 分组视图：一条出站消息一组，组内 text/image 保序——前端据此做「一条一项、图文同条」。
        check(payload.has("messages") && payload.getAsJsonArray("messages").size() >= 1,
                "回执按出站消息分组（messages）：" + payload.get("messages"));
        JsonArray firstGroup = payload.getAsJsonArray("messages").get(0).getAsJsonArray();
        check(firstGroup.size() >= 1 && firstGroup.get(0).getAsJsonObject().has("type"),
                "分组里每个片段都带 type（text/image）：" + firstGroup);
        post(base, "/api/capture/close", token, captureBody(freshId));
    }

    /** SD 生成进度：空闲判定必须看 job/job_count（空闲时 sampling_steps 仍保留上次的步数）。 */
    private static void generationProgress(String base) throws Exception {
        String token = "test-token-123456";
        SdClient.GenerationProgress idle = SdClient.parseProgress(Json.parse("{\"progress\":0.0,\"eta_relative\":0.0,\"state\":"
                + "{\"job\":\"\",\"job_count\":0,\"sampling_step\":0,\"sampling_steps\":20}}"));
        check(!idle.running(), "空闲时不算生成中（job 为空、job_count=0）：" + idle);
        check(idle.describe().contains("空闲"), "空闲时给出明确结论：" + idle.describe());
        SdClient.GenerationProgress running = SdClient.parseProgress(Json.parse("{\"progress\":0.45,\"eta_relative\":12.5,\"state\":"
                + "{\"job\":\"scripts_txt2img\",\"job_count\":1,\"sampling_step\":9,\"sampling_steps\":20}}"));
        check(running.running() && running.step() == 9 && running.steps() == 20 && Math.round(running.percent() * 100) == 45,
                "生成中的进度解析正确：" + running);
        check(running.describe().contains("步骤 9/20") && running.describe().contains("45%"), "进度说明可读：" + running.describe());
        // 步骤比 progress 更靠前时以步骤为准（进度接口的 progress 有时落后一两步）。
        SdClient.GenerationProgress stepAhead = SdClient.parseProgress(Json.parse("{\"progress\":0.05,\"eta_relative\":30,\"state\":"
                + "{\"job\":\"scripts_txt2img\",\"job_count\":1,\"sampling_step\":10,\"sampling_steps\":20}}"));
        check(Math.round(stepAhead.percent() * 100) == 50, "步骤与百分比取较大者：" + stepAhead.percent());

        JsonObject live = post(base, "/api/progress", token, body(null));
        check(live.get("running").getAsBoolean() && live.get("percent").getAsInt() == 45, "网页进度接口读到 SD 的进度：" + live);
        check(live.get("reachable").getAsBoolean(), "读得到 SD 时 reachable=true：" + live);
        check(live.get("text").getAsString().contains("步骤 9/20"), "网页进度接口带一行说明：" + live.get("text"));
        check(live.has("queue"), "进度接口同时给出机器人队列状态：" + live.keySet());
    }

    private static JsonObject body(String command, String scope) {
        JsonObject body = new JsonObject();
        if (command != null) body.addProperty("command", command);
        if (scope != null) body.addProperty("scope", scope);
        return body;
    }

    private static JsonObject body(String command) { return body(command, null); }

    /** 轮询回执直到安静下来。 */
    private static String waitCapture(String base, String token, String id) throws Exception {
        String texts = pollCapture(base, token, id);
        post(base, "/api/capture/close", token, captureBody(id));
        return texts;
    }

    /** 轮询回执，但**不关闭**——网页端的真实行为（它只轮询）。等到出现内容并连续两次安静为止。 */
    private static String pollCapture(String base, String token, String id) throws Exception {
        StringBuilder texts = new StringBuilder();
        long deadline = System.currentTimeMillis() + 15000;
        int quiet = 0;
        while (System.currentTimeMillis() < deadline) {
            JsonObject capture = post(base, "/api/capture", token, captureBody(id));
            JsonArray list = capture.getAsJsonArray("texts");
            texts.setLength(0);
            for (JsonElement item : list) texts.append(item.getAsString()).append("\n");
            if (texts.length() > 0 && !capture.get("busy").getAsBoolean()) {
                if (++quiet >= 2) break;
            } else {
                quiet = 0;
            }
            Thread.sleep(120);
        }
        return texts.toString();
    }

    private static JsonObject captureBody(String id) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        return body;
    }

    /** 「生成参数」卡没有「应用」按钮：网页改完立刻发到 /api/generation，改的就是之后出图用的值。 */
    private static void generationPanel(String base, Path root) throws Exception {
        String token = "test-token-123456";
        JsonObject before = get(base, "/api/status", token).getAsJsonObject("generation");
        check(before.get("width").getAsInt() == 512 && before.get("height").getAsInt() == 512,
                "初始尺寸来自 WebUI：" + before);

        JsonObject size = new JsonObject();
        size.addProperty("width", 768);
        size.addProperty("height", 512);
        JsonObject applied = post(base, "/api/generation", token, size);
        check(applied.getAsJsonObject("generation").get("width").getAsInt() == 768,
                "改完立即生效（尺寸）：" + applied.get("generation"));
        check(applied.get("message").getAsString().contains("768"), "回执说明改了什么：" + applied.get("message"));
        check(applied.getAsJsonArray("changed").size() == 1, "只报告这次真的改动的字段：" + applied.get("changed"));
        check(get(base, "/api/status", token).getAsJsonObject("generation").get("width").getAsInt() == 768,
                "状态里读到的已经是新尺寸");

        JsonObject tuned = new JsonObject();
        tuned.addProperty("steps", 34);
        tuned.addProperty("cfg", 5.5);
        tuned.addProperty("seed", 12345);
        JsonObject generation = post(base, "/api/generation", token, tuned).getAsJsonObject("generation");
        check(generation.get("steps").getAsInt() == 34 && generation.get("cfg").getAsDouble() == 5.5
                && generation.get("seed").getAsLong() == 12345, "步数/CFG/种子立即生效：" + generation);

        JsonObject count = new JsonObject();
        count.addProperty("imageCount", 7);
        check(post(base, "/api/generation", token, count).get("imageCount").getAsInt() == 7, "图片上限立即生效");
        // 空值表示"这次不动它"；非法尺寸必须报错且不改动已有设置。
        JsonObject keep = new JsonObject();
        keep.addProperty("sampler", "");
        JsonObject kept = post(base, "/api/generation", token, keep);
        check(kept.getAsJsonObject("generation").get("sampler").getAsString().equals("Euler a"),
                "空字段被忽略，采样方法保持不动：" + kept.getAsJsonObject("generation").get("sampler"));
        JsonObject bad = new JsonObject();
        bad.addProperty("width", 100);
        JsonObject failure = postRaw(base, "/api/generation", token, bad);
        check(failure.has("error"), "非法尺寸被拒绝：" + failure);
        check(get(base, "/api/status", token).getAsJsonObject("generation").get("width").getAsInt() == 768,
                "被拒绝之后尺寸仍是上一次生效的值");
    }

    /** 图片接口只允许 data/generated 下的真实图片；列表要列得出已领取的历史图片（刷新/重启后仍在）。 */
    private static void imageGuard(String base, Path root) throws Exception {
        String token = "test-token-123456";
        Files.writeString(root.resolve("data/generated/evil.txt"), "not an image");
        JsonObject images = post(base, "/api/images", token, body(null));
        JsonArray listed = images.getAsJsonArray("images");
        // sample.png 是直接落盘的（没进待领取队列）：以前"只列待领取"会让它在刷新后整片消失。
        JsonObject sample = null;
        for (JsonElement item : listed)
            if (item.getAsJsonObject().get("path").getAsString().equals("data/generated/sample.png")) sample = item.getAsJsonObject();
        check(sample != null, "已领取的历史图片仍然出现在列表里：" + listed);
        check(!sample.get("pending").getAsBoolean(), "不在待领取队列里的图片标 pending=false：" + sample);
        check(sample.get("name").getAsString().equals("sample.png") && sample.get("size").getAsLong() > 0,
                "列表项带文件名与体积：" + sample);
        HttpResponse<String> ok = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/image?token=" + token + "&path=data/generated/sample.png")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(ok.statusCode() == 200, "data/generated 下的图片可以读取（" + ok.statusCode() + "）");
        check(postRaw(base, "/api/image", token, path("../../config.json")).has("error"), "路径穿越被拒绝");
        check(postRaw(base, "/api/image", token, path("config.json")).has("error"), "工作目录里非缓存图片不可读");
        check(postRaw(base, "/api/image", token, path("data/generated/evil.txt")).has("error"), "非图片扩展名被拒绝");
        check(postRaw(base, "/api/image", token, path("data/generated/missing.png")).has("error"), "不存在的图片返回错误");
    }

    /**
     * 网页端配置：首次配置期间本机免令牌、配置完成后恢复要令牌；两条通道的地址与密钥能改、能测、能清空，
     * 而且任何响应里都不出现密钥原文。测试 root 只放了生图密钥，所以起点正好是"还没配置完"。
     */
    private static void configEndpoints(String base, Path root, int sdPort) throws Exception {
        String token = "test-token-123456";
        JsonObject open = get(base, "/api/config/state", null);
        check(open.get("needed").getAsBoolean(), "缺聊天频道密钥时算首次配置");
        JsonObject channels = open.getAsJsonObject("values").getAsJsonObject("channels");
        check(channels.getAsJsonObject("image").get("keySet").getAsBoolean(), "生图频道密钥已配置");
        check(!channels.getAsJsonObject("chat").get("keySet").getAsBoolean(), "聊天频道密钥未配置");
        check(!open.toString().contains("test-key-not-real"), "首次配置读接口不回显密钥原文");
        check(open.getAsJsonObject("values").getAsJsonObject("channels").getAsJsonObject("chat")
                .get("effective").getAsString().endsWith("/chat/completions"), "接口给出补齐后的完整地址");

        HttpResponse<String> page = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/setup")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(page.statusCode() == 200 && page.body().contains("panel-setup"),
                "Setup 栏目（首次配置入口）免令牌可打开");

        // 首次配置：不带令牌就能填密钥与地址（本机回环）
        JsonObject filled = post(base, "/api/config/apply", null, Json.parse(
                "{\"owner_user_id\":\"10001\",\"channels\":{\"chat\":{\"base\":\"http://127.0.0.1:" + sdPort
                        + "/v1\",\"key\":\"sk-chat-test-abcdef\"}}}"));
        check(!filled.get("needed").getAsBoolean(), "两条密钥齐了就不再是首次配置");
        check(Files.readString(root.resolve("data/deepseek-chat-api-key.txt"), StandardCharsets.UTF_8).strip()
                .equals("sk-chat-test-abcdef"), "聊天密钥写进自己的文件");
        check(Json.str(Json.parse(Files.readString(root.resolve("config.json"), StandardCharsets.UTF_8)), "owner_user_id", "")
                .equals("10001"), "owner QQ 落盘");

        // 配置完成后：同样的接口恢复要令牌
        check(status(base, "/api/config/state", null, null) == 401, "配置完成后配置接口要令牌");
        check(status(base, "/api/config/state", token, null) == 200, "带令牌可以读配置");
        JsonObject state = get(base, "/api/config/state", token);
        check(!state.toString().contains("sk-chat-test-abcdef"), "带令牌读配置也不回显密钥原文");
        check(state.getAsJsonObject("values").getAsJsonObject("channels").getAsJsonObject("chat")
                .get("keyMasked").getAsString().contains("…"), "密钥只给掩码");

        // 测试连接：真的发一条请求给本机桩（桩对所有 /chat/completions 回 200）
        JsonObject tested = post(base, "/api/config/test", token, panel("channel", "chat"));
        check(tested.get("ok").getAsBoolean(), "测试连接成功：" + tested.get("message").getAsString());
        check(tested.get("url").getAsString().equals("http://127.0.0.1:" + sdPort + "/v1/chat/completions"),
                "测试用的是补齐后的地址：" + tested.get("url").getAsString());

        // 清空密钥 → 重新回到首次配置（本机又可以免令牌进来重填），随后还原测试数据
        JsonObject cleared = post(base, "/api/config/apply", token, Json.parse("{\"channels\":{\"chat\":{\"clearKey\":true}}}"));
        check(cleared.get("needed").getAsBoolean(), "清空聊天密钥后重新算首次配置");
        post(base, "/api/config/apply", null, Json.parse("{\"channels\":{\"chat\":{\"key\":\"sk-chat-test-abcdef\"}}}"));

        // 坏输入：报 400，且不落盘
        check(status(base, "/api/config/apply", token, Json.parse("{\"owner_user_id\":\"abc\"}")) == 400, "非法 owner 报 400");
        check(status(base, "/api/config/test", token, panel("channel", "nope")) == 400, "未知通道报 400");
        check(Json.str(Json.parse(Files.readString(root.resolve("config.json"), StandardCharsets.UTF_8)), "owner_user_id", "")
                .equals("10001"), "被拒绝的请求不改配置");
    }

    /** 与 post 相同，但把 4xx/5xx 的 JSON 也返回，便于断言错误内容。 */
    private static JsonObject postRaw(String base, String path, String token, JsonObject body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString(), StandardCharsets.UTF_8));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Json.parse(response.body().isBlank() ? "{}" : response.body());
    }

    private static JsonObject path(String value) {
        JsonObject body = new JsonObject();
        body.addProperty("path", value);
        return body;
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }

    /** 统计子串出现次数（用来断言"每个出图位置都走了同一个图片节点"）。 */
    private static int countOf(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) found++;
        return found;
    }
}

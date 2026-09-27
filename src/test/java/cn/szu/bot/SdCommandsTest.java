package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;

/** New commands use an ephemeral loopback SD stub and never contact QQ or real Stable Diffusion. */
public final class SdCommandsTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static int assertions;
    private static final String SAMPLER = "DPM++ 2M 中文";
    private static final String STYLE_A = "写实 风格", STYLE_B = "Anime soft";
    private record Reply(JsonObject event, JsonArray segments) { String text() { return Bot.messageText(segments); } }
    @FunctionalInterface private interface Operation { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        commandRoutingAndPersistence();
        invalidCommandsDoNotChangeSettings();
        generationUsesAcceptedSnapshot();
        System.out.println("SdCommandsTest: " + assertions + " assertions passed: settings, samplers, styles, size, group/private, persistence, snapshot, help.");
    }

    private static void commandRoutingAndPersistence() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String type : List.of("group", "private")) {
                String help = f.command(type, ".help");
                for (String command : List.of(".yh", ".liv", ".prompt", ".promptR", ".prompt add",
                        ".promptR add", ".prompt remove", ".promptR remove", ".prompt set", ".promptR set",
                        ".settings", ".sampler", ".sampler list", ".sampler set", ".style",
                        ".style list", ".style save", ".style overwrite",
                        ".style prompt", ".style load", ".size", ".size set", ".gen [次数]", ".gen status", ".get", ".map path", ".map set yh", ".map set liv", ".help"))
                    check(help.contains(command), type + " help includes " + command);
                String all = f.command(type, ".settings");
                check(all.contains("采样方法") && all.contains("图片尺寸") && all.contains("来源"), type + " settings reads all fields and source");
                check(!all.contains("当前样式"), type + " settings no longer reports a loaded style");
                check(f.command(type, ".sampler list").contains(SAMPLER), type + " sampler catalog includes full Chinese/space name");
                check(f.command(type, ".sampler set " + SAMPLER).contains("已更新并保存"), type + " sampler set succeeds for non-admin");
                check(f.command(type, ".sampler").contains(SAMPLER), type + " sampler read reflects change");
                // 样式只有机器人这一份：group 这一轮开始时库是空的，用 .style import webui 把 WebUI 预设样式搬进来。
                String initialList = f.command(type, ".style list");
                check(initialList.contains("样式列表"), type + " style list renders the library: " + initialList);
                if (type.equals("group")) check(initialList.contains("还没有样式"), "empty style library is explicit: " + initialList);
                String imported = f.command(type, ".style import webui");
                check(imported.contains("样式库") && imported.matches("(?s).*导入 \\d+ 个.*"),
                        type + " webui presets migrate into the bot library: " + imported);
                String available = f.command(type, ".style list");
                check(available.contains(STYLE_A) && available.contains(STYLE_B), type + " style catalog lists the migrated styles");
                check(f.command(type, ".style set " + STYLE_A).contains("用法"), type + " removed style set rejected");
                check(!help.contains(".style set"), "removed command absent from help");
                String selected = f.command(type, ".style");
                check(selected.contains("样式库") && selected.contains(STYLE_A), type + " status lists the library: " + selected);
                SdClient.Prompts beforeStyle = new UserPromptStore(f.root).prompts("456");
                String original = f.command(type, ".style prompt " + STYLE_A);
                // 导入时按 WebUI 语义清掉 {prompt} 占位符：库里存的就是可直接使用的原文。
                check(original.contains("样式：" + STYLE_A) && original.contains("style negative") && !original.contains("{prompt}"),
                        type + " inspect both original template texts: " + original);
                equal(beforeStyle, new UserPromptStore(f.root).prompts("456"), type + " viewing a style does not edit prompts");
                check(f.command(type, ".style load " + STYLE_A).contains("替换"), type + " load replaces the personal prompt");
                // 顺便把 WebUI 页面勾选设成另外两项：机器人任何样式操作都不该改动它。
                f.client.setStyles(List.of(STYLE_B, STYLE_A));
                SdClient.Prompts afterStyle = new UserPromptStore(f.root).prompts("456");
                // WebUI 的 {prompt} 占位符在载入时按原语义换成空，不会把占位符写进提示词。
                check(!afterStyle.positive().contains("{prompt}"), type + " placeholder never reaches the prompt: " + afterStyle.positive());
                check(afterStyle.negative().equals("style negative"), type + " negative replaced by the style text");
                // 载入样式完全不碰 WebUI 页面的勾选。
                equal(List.of(STYLE_B, STYLE_A), f.client.settings().styles(), type + " personal style load leaves the WebUI selection untouched");
                String catalogAfterLoad = f.command(type, ".style list");
                check(!catalogAfterLoad.contains("★") && catalogAfterLoad.contains(STYLE_A),
                        type + " catalog never marks a loaded style any more: " + catalogAfterLoad);
                String viewAfterLoad = f.command(type, ".style");
                check(viewAfterLoad.contains("样式库") && !viewAfterLoad.contains("载入的样式"),
                        type + " view lists the library without a loaded-style state: " + viewAfterLoad);
                check(f.command(type, ".style prompt").contains("用法"),
                        type + " inspecting a style now requires a name");
                // 查看不再抢走 #编号 上下文：编号始终按 .style list 的顺序，两次查看结果一致。
                f.command(type, ".style");
                String firstByNumber = f.command(type, ".style prompt #1");
                check(firstByNumber.contains("样式：") && firstByNumber.equals(f.command(type, ".style prompt #1")),
                        type + " status view does not renumber the catalog: " + firstByNumber);
                check(f.command(type, ".style prompt " + STYLE_A).contains("样式：" + STYLE_A),
                        type + " a style can still be inspected by name");
                // 载入样式只是把文本写进个人 prompt：没有"当前载入样式"状态，重复载入就是普通的再次替换。
                check(f.command(type, ".style load " + STYLE_A).contains("本次变化"),
                        type + " repeated load is a normal replacement");
                check(!new UserPromptStore(f.root).prompts("456").positive().contains("★"),
                        type + " loading a style never writes a marker into the prompt");
                check(f.command(type, ".style clear").contains("用法"), type + " clear command is gone");
                equal(List.of(STYLE_B, STYLE_A), f.client.settings().styles(), type + " style commands never touch the WebUI selection");
                // 载入后手改 prompt：不会再被样式"恢复"覆盖，prompt 文本就是唯一事实。
                f.command(type, ".prompt add after_load_marker");
                check(new UserPromptStore(f.root).prompts("456").positive().contains("after_load_marker"),
                        type + " a prompt edit after loading a style survives");
                check(new UserPromptStore(f.root).prompts("456").positive().contains(SdClient.styleText("style {prompt}")),
                        type + " the rest of the style text is still there");
                f.command(type, ".prompt remove after_load_marker");
                // 存样式只写机器人这边，与 WebUI 完全无关。
                check(f.command(type, ".style save 本机样式A").contains("样式已保存"), type + " local save succeeds");
                check(f.command(type, ".style save 本机样式A").contains("已有同名样式"), type + " duplicate local save needs overwrite");
                check(f.command(type, ".style overwrite 本机样式A").contains("样式已覆盖保存"), type + " local overwrite succeeds");
                String mixed = f.command(type, ".style list");
                check(mixed.contains("本机样式A") && mixed.contains(STYLE_A), type + " local and imported styles share one list: " + mixed);
                equal(List.of(STYLE_B, STYLE_A), f.client.settings().styles(), type + " saving a local style never touches the WebUI selection");
                check(f.command(type, ".style rename 本机样式A 本机样式B").contains("本机样式A → 本机样式B"), type + " local rename");
                check(f.command(type, ".style list").contains("本机样式B"), type + " renamed local style is listed");
                check(f.command(type, ".style delete 本机样式B").contains("本机样式B"), type + " local delete works without the bridge");
                check(f.command(type, ".style delete " + STYLE_A).contains(STYLE_A), type + " imported style is deleted from the bot library");
                String afterDelete = f.command(type, ".style list");
                check(!afterDelete.contains(STYLE_A) && afterDelete.contains(STYLE_B),
                        type + " deleted style is gone from the library: " + afterDelete);
                check(f.command(type, ".style export " + STYLE_B).contains("用法"), type + " export command is gone");
                check(f.command(type, ".size set 768 512").contains("768 × 512 像素"), type + " width and height updated");
                check(f.command(type, ".size").contains("768 × 512 像素"), type + " dimensions readable");
                check(f.command(type, ".size set 512x768").contains("512 × 768 像素"), type + " WxH supported");
                check(f.command(type, ".size set 768 × 512").contains("768 × 512 像素"), type + " multiplication symbol supported");
                check(f.command(type, ".size set 64 2048").contains("64 × 2048 像素"), type + " size endpoints accepted");
            }
            f.command("private", ".size set 768 512");
            f.client.setStyles(List.of(STYLE_A, STYLE_B));
            f.bridgeAvailable = false;
            SdClient restarted = new SdClient(f.root, f.sdConfig);
            SdClient.GenerationSettings saved = restarted.settings();
            equal(SAMPLER, saved.samplerName(), "sampler persists across restart without bridge");
            equal(List.of(STYLE_A, STYLE_B), saved.styles(), "styles persist across restart without bridge");
            equal(768, saved.width(), "width persists"); equal(512, saved.height(), "height persists");
            check(saved.source().contains("未同步"), "fallback source does not claim a live page");
        }
    }

    private static void invalidCommandsDoNotChangeSettings() throws Exception {
        try (Fixture f = new Fixture()) {
            SdClient.GenerationSettings initial = f.client.settings();
            for (String type : List.of("group", "private")) {
                for (String command : List.of(".settings extra", ".sampler set", ".sampler list extra", ".sampler wrong",
                        ".style set", ".style load", ".style clear extra", ".style wrong", ".size set", ".size list",
                        ".size set 512", ".size set 512 512 extra"))
                    rejected(f, type, command, "用法");
                for (String command : List.of(".sampler set 不存在的采样器", ".style set 不存在的样式",
                        ".style prompt 不存在的样式", ".style load 不存在的样式",
                        ".style set [\"" + STYLE_A + "\",\"不存在的样式\"]", ".size set 0 512", ".size set -8 512",
                        ".size set 63 512", ".size set 513 512", ".size set 2056 512"))
                    rejected(f, type, command, "操作失败");
                for (String command : List.of(".size set no 512", ".size set 512.0 512", ".size set 99999999999999 512"))
                    rejected(f, type, command, "整数");
                for (String command : List.of(".style set [broken", ".style set [\"\"]", ".style set [\" \" ]",
                        ".style set [12]", ".style set [null]", ".style set [{\"name\":\"x\"}]",
                        ".style set ['" + STYLE_A + "']", ".style set [\"" + STYLE_A + "\"] trailing"))
                    rejected(f, type, command, "用法");
                SdClient.GenerationSettings after = f.client.settings();
                equal(initial.samplerName(), after.samplerName(), type + " invalid commands preserve sampler");
                equal(initial.styles(), after.styles(), type + " invalid commands preserve styles");
                equal(initial.width(), after.width(), type + " invalid commands preserve width");
                equal(initial.height(), after.height(), type + " invalid commands preserve height");
            }
            f.availableStyles = List.of();
            check(f.command("private", ".style import webui").contains("导入 0 个"), "empty webui catalog imports nothing");
            check(f.command("private", ".style clear").contains("用法"), "the bot-side style marker (and its clear command) is gone");
        }
    }

    private static void generationUsesAcceptedSnapshot() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String type : List.of("group", "private")) {
                f.command(type, ".sampler set " + SAMPLER);
                f.client.setStyles(List.of(STYLE_A, STYLE_B));
                f.command(type, ".size set 768 512");
                f.command(type, ".style import webui");
                f.command(type, ".style load " + STYLE_A);
                check(!f.command(type, ".settings").contains("当前样式"), type + " settings hides the retired loaded-style field");
                f.command(type, ".prompt set captured +");
                f.command(type, ".promptR set captured -");
                // Change state synchronously during the start reply, before the worker is even submitted.
                // This catches implementations that read settings lazily inside generate instead of snapshotting accept.
                f.onGenerationStart.set(() -> {
                    f.client.setSampler("Euler a"); f.client.setStyles(List.of()); f.client.setSize(512, 768);
                    f.client.change(false, "edit", "later +"); f.client.change(true, "edit", "later -");
                });
                String start = f.command(type, ".gen");
                check(start.contains("开始生成") && start.contains(SAMPLER) && start.contains("768 × 512"), type + " start message shows accepted settings");
                JsonObject payload = f.generations.poll(5, TimeUnit.SECONDS);
                check(payload != null, type + " generation called mock API");
                equal("captured +", payload.get("prompt").getAsString(), type + " generation snapshots positive");
                equal("captured -", payload.get("negative_prompt").getAsString(), type + " generation snapshots negative");
                equal(SAMPLER, payload.get("sampler_name").getAsString(), type + " generation snapshots sampler");
                equal(JsonParser.parseString("[\"" + STYLE_A + "\",\"" + STYLE_B + "\"]"), payload.get("styles"), type + " generation snapshots ordered styles");
                equal(768, payload.get("width").getAsInt(), type + " generation snapshots width");
                equal(512, payload.get("height").getAsInt(), type + " generation snapshots height");
                Reply done = take(f.replies);
                check(done.text().contains("生成成功"), type + " completion notification");
                equal(type, done.event().get("message_type").getAsString(), type + " completion routed to originating conversation");
                check(f.command(type, ".settings").contains("512 × 768"), type + " subsequent reads show later state");
            }
        }
    }

    private static void rejected(Fixture f, String type, String command, String fragment) throws Exception {
        String response = f.command(type, command);
        check(response.startsWith("操作失败：") && response.contains(fragment), type + " rejects " + command + ": " + response);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static Reply take(BlockingQueue<Reply> replies) throws Exception {
        Reply reply = replies.poll(7, TimeUnit.SECONDS);
        if (reply == null) throw new AssertionError("Reply timeout");
        return reply;
    }
    private static JsonObject event(String type, String command) {
        JsonObject event = new JsonObject();
        event.addProperty("post_type", "message"); event.addProperty("message_type", type);
        event.addProperty("user_id", 456); event.addProperty("self_id", 777); event.addProperty("message_id", IDS.incrementAndGet());
        if (type.equals("group")) event.addProperty("group_id", 999);
        event.add("message", Maps.text(command)); return event;
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
        final ExecutorService executor;
        final JsonObject sdConfig = new JsonObject(), state = new JsonObject();
        final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonObject> generations = new LinkedBlockingQueue<>();
        final AtomicReference<Operation> onGenerationStart = new AtomicReference<>();
        final SdClient client;
        final Bot bot;
        final String image;
        volatile boolean bridgeAvailable = true;
        volatile List<String> availableStyles = List.of(STYLE_A, STYLE_B);

        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "sd-command-tests").toAbsolutePath();
            Files.createDirectories(work); root = Files.createTempDirectory(work, "case-");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
            image = Base64.getEncoder().encodeToString(bytes.toByteArray());
            state.addProperty("positive", "initial +"); state.addProperty("negative", "initial -");
            state.addProperty("sampler_name", "Euler a"); state.add("styles", new JsonArray());
            state.addProperty("width", 512); state.addProperty("height", 512);
            state.addProperty("revision", 1); state.addProperty("source", "webui-live");
            state.addProperty("settings_initialized", true); state.addProperty("live", true);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "sd-commands-mock"); thread.setDaemon(true); return thread;
            });
            server.setExecutor(executor); server.createContext("/", this::handle); server.start();
            sdConfig.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sdConfig.addProperty("timeout_seconds", 5);
            JsonObject config = new JsonObject(); config.add("sd", sdConfig.deepCopy());
            JsonArray admins = new JsonArray(); admins.add(123); config.add("admin_user_ids", admins);
            config.addProperty("gen_auto_get", false); Json.atomicWrite(root.resolve("config.json"), config);
            client = new SdClient(root, sdConfig);
            bot = new Bot(new Settings(root), client, (event, segments) -> {
                replies.add(new Reply(event.deepCopy(), segments.deepCopy()));
                if (Bot.messageText(segments).contains("开始生成图片")) {
                    Operation operation = onGenerationStart.getAndSet(null);
                    if (operation != null) {
                        try { operation.run(); }
                        catch (Exception e) { throw new CompletionException(e); }
                    }
                }
                return CompletableFuture.completedFuture(null);
            });
        }

        String command(String type, String command) throws Exception {
            bot.accept(event(type, command));
            Reply reply = take(replies);
            equal(type, reply.event().get("message_type").getAsString(), command + " retains conversation type");
            equal(456, reply.event().get("user_id").getAsInt(), command + " retains sender");
            if (type.equals("group")) equal(999, reply.event().get("group_id").getAsInt(), command + " retains group");
            return reply.text();
        }

        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/pixiko-bridge/v1/prompts")) {
                    if (!bridgeAvailable) { send(exchange, 404, "{}"); return; }
                    synchronized (state) {
                        if (exchange.getRequestMethod().equals("PUT")) {
                            JsonObject body = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                            if (body.get("expected_revision").getAsInt() != state.get("revision").getAsInt()) {
                                send(exchange, 409, state.toString()); return;
                            }
                            for (String key : List.of("positive", "negative", "sampler_name", "styles", "width", "height"))
                                if (body.has(key)) state.add(key, body.get(key).deepCopy());
                            state.addProperty("revision", state.get("revision").getAsInt() + 1);
                        }
                        send(exchange, 200, state.toString());
                    }
                } else if (path.equals("/sdapi/v1/samplers")) {
                    JsonArray values = new JsonArray();
                    for (String name : List.of("Euler a", SAMPLER)) { JsonObject value = new JsonObject(); value.addProperty("name", name); values.add(value); }
                    send(exchange, 200, values.toString());
                } else if (path.equals("/sdapi/v1/prompt-styles")) {
                    JsonArray values = new JsonArray();
                    for (String name : availableStyles) {
                        JsonObject value = new JsonObject(); value.addProperty("name", name);
                        value.addProperty("prompt", "style {prompt}"); value.addProperty("negative_prompt", "style negative"); values.add(value);
                    }
                    send(exchange, 200, values.toString());
                } else if (path.equals("/sdapi/v1/txt2img")) {
                    generations.add(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    send(exchange, 200, "{\"images\":[\"" + image + "\"]}");
                } else send(exchange, 404, "{}");
            } finally { exchange.close(); }
        }

        static void send(HttpExchange exchange, int status, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }

        public void close() {
            bot.close(); server.stop(0); executor.shutdownNow();
            // Keep only this isolated fixture beneath the workspace work/ tree for troubleshooting.
        }
    }
}

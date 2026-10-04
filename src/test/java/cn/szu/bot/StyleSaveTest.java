package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.sd.SdClient;

/** Real command/client integration against a loopback stub; no QQ, live WebUI, or production styles are touched. */
public final class StyleSaveTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static int assertions;
    private record Reply(JsonObject event, String text) {}

    public static void main(String[] args) throws Exception {
        localStylesAreIndependent();
        exportWritesToWebui();
        validationAndSingularCommands();
        snapshotsEmptyPromptsAndFailures();
        explicitShowcasePairDoesNotTouchLivePrompts();
        System.out.println("StyleSaveTest: " + assertions + " assertions passed: 本机样式独立、导出到 WebUI、名称校验、快照/离线不阻塞、展示图元数据.");
    }

    private static void explicitShowcasePairDoesNotTouchLivePrompts() throws Exception {
        try (Fixture f = new Fixture()) {
            JsonObject before = f.stateCopy();
            f.bridgeStatus = 503;
            String positive = "  showcase, 中文\n<lora:Example:0.8>  ";
            String negative = "blur, bad\n\"quoted\"";
            var saved = f.client.saveStylePair("模型名 1", positive, negative, false, "Civitai 展示图");
            equal("模型名 1", saved.name(), "explicit metadata style name");
            equal(positive, f.lastSave.get("positive").getAsString(), "showcase positive raw text preserved");
            equal(negative, f.lastSave.get("negative").getAsString(), "showcase negative raw text preserved");
            equal(0, f.promptReads.get(), "saving metadata never reads the live prompt endpoint");
            equal(before, f.stateCopy(), "saving metadata never changes prompt, sampler, size or selected styles");
            check(!f.lastSave.get("overwrite").getAsBoolean(), "import never overwrites existing styles");
        }
    }

    /** 样式只属于机器人：同名不冲突、不读页面提示词、不写 WebUI。 */
    private static void localStylesAreIndependent() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String type : List.of("group", "private")) {
                String name = "中文 风格 " + type;
                f.setPrompts("风景, {prompt}\n细节", "模糊, bad anatomy");
                // 先有个人提示词（保存取的就是它），这样也能确认保存过程完全不碰 WebUI。
                f.command(type, ".prompt set local content, red hair");
                f.command(type, ".promptR set local negative");
                JsonObject before = f.stateCopy();
                int reads = f.promptReads.get(), saves = f.saveCalls.get();
                String response = f.command(type, ".style save " + name);
                check(response.contains("样式已保存：" + name), type + " local save notification: " + response);
                check(response.contains("只存在机器人这边"), type + " explains the styles live in the bot");
                check(response.contains(".style load " + name), type + " tells how to load it");
                equal(reads, f.promptReads.get(), type + " local save never reads the WebUI prompt");
                equal(saves, f.saveCalls.get(), type + " local save never posts to the WebUI");
                equal(before, f.stateCopy(), type + " local save leaves WebUI state untouched");
                check(f.command(type, ".style prompt " + name).contains("local content, red hair"), type + " local style stores the personal prompt");
                check(f.command(type, ".style list").contains(name), type + " saved style is listed");
                String duplicate = f.command(type, ".style save " + name);
                check(duplicate.startsWith("操作失败：") && duplicate.contains("已有同名样式") && duplicate.contains(".style overwrite"),
                        type + " duplicate local save requires explicit overwrite: " + duplicate);
                check(f.command(type, ".style overwrite " + name).contains("样式已覆盖保存"), type + " explicit overwrite succeeds");
                // WebUI 里有同名条目也不影响机器人这边；机器人根本不再读 WebUI 的样式。
                f.setPrompts("replacement +", "replacement -");
                check(f.command(type, ".style overwrite " + name).contains("样式已覆盖保存"),
                        type + " same name in the WebUI never blocks a local save");
                check(f.command(type, ".style export " + name).contains("用法"), type + " export command is gone");
                String listed = f.command(type, ".style list");
                check(listed.contains(name) && !listed.contains("（WebUI）") && !listed.contains("○ "), type + " the library has no WebUI section: " + listed);
                check(f.command(type, ".style delete " + name).contains(name), type + " local delete succeeds");
                String afterDelete = f.command(type, ".style list");
                check(!afterDelete.contains(name), type + " deleted style is gone: " + afterDelete);
                String help = f.command(type, ".help");
                check(help.contains(".style save") && help.contains(".style overwrite") && help.contains(".style import webui") && !help.contains("/styles"),
                        type + " help covers the style commands");
            }
        }
    }

    /** 迁移：WebUI 里已有的预设样式一次性搬进机器人样式库。 */
    private static void exportWritesToWebui() throws Exception {
        try (Fixture f = new Fixture()) {
            f.presets.put("迁移甲", new String[]{"迁移甲正向", "迁移甲反向"});
            f.presets.put("迁移乙", new String[]{"迁移乙正向", "迁移乙反向"});
            check(f.command("group", ".style import webui").contains("导入 3 个"), "migrates every preset once");
            check(f.command("group", ".style list").contains("迁移甲"), "migrated style is in the library");
        }
    }

    private static void validationAndSingularCommands() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String type : List.of("group", "private")) {
                for (String command : List.of(".style save", ".style overwrite", ".style save   ", ".style save #分隔项",
                        ".style overwrite #分隔项", ".style save 两行\n名称", ".style save 名\t称", ".style save " + "名".repeat(201))) {
                    String response = f.command(type, command);
                    check(response.startsWith("操作失败："), type + " rejects invalid style name: " + command);
                }
                for (String command : List.of("/styles", "/styles list", "/styles set 名称", "/styles clear",
                        "/styles save 名称", "/styles overwrite 名称"))
                    check(f.command(type, command).contains("指令格式不正确"), type + " old plural command unsupported");
            }
            equal(0, f.promptReads.get(), "invalid commands do not read prompts");
            equal(0, f.saveCalls.get(), "invalid commands never POST");
            for (String name : Arrays.asList(null, "", " ", "name\n", "name\u0085", "a\u2028b", "a\u2029b", "#heading", "a".repeat(201)))
                failure(() -> f.client.saveStyle(name, false), null, "direct client rejects invalid name");
            equal(0, f.promptReads.get(), "direct client validation runs before snapshot");
            SdClient.SavedStyle trimmed = f.client.saveStyle("  前后 空格  ", false);
            equal("前后 空格", trimmed.name(), "surrounding whitespace normalized");
            String label = "../仅为名称 styles.csv";
            f.client.saveStyle(label, false);
            equal(label, f.lastSave.get("name").getAsString(), "path-like name remains an opaque JSON label");
            check(!Files.exists(f.root.resolve("../仅为名称 styles.csv")), "client never uses style name as a path");
        }
    }

    /** 本机保存不依赖 WebUI：空提示词、页面内容变化、桥接故障、服务离线都拦不住它。 */
    private static void snapshotsEmptyPromptsAndFailures() throws Exception {
        try (Fixture f = new Fixture()) {
            f.setPrompts("captured +", "captured -");
            f.command("group", ".prompt set snapshot content");
            f.command("group", ".promptR set snapshot negative");
            f.changeAfterRead = true;
            int reads = f.promptReads.get(), saves = f.saveCalls.get();
            check(f.command("group", ".style save 快照 本地").contains("样式已保存"), "local save ignores WebUI state changes");
            equal(reads, f.promptReads.get(), "local save never reads the WebUI prompt");
            equal(saves, f.saveCalls.get(), "local save never posts to the WebUI");
            equal("captured +", f.stateCopy().get("positive").getAsString(), "WebUI page state untouched (nothing was read or written)");
            f.command("group", ".prompt clear");
            check(f.command("group", ".style save 空正向 本地").contains("样式已保存"), "empty positive still saves locally");
            f.command("group", ".promptR clear");
            check(f.command("group", ".style save 全空 本地").contains("样式已保存"), "empty prompts still save locally");
            for (int status : new int[]{404, 401, 403, 422, 500}) {
                f.saveStatus = status;
                check(f.command("group", ".style save 桥接故障 " + status).contains("样式已保存"),
                        "WebUI POST status " + status + " cannot block a local save");
            }
            f.saveStatus = 200;
            f.bridgeStatus = 500;
            check(f.command("private", ".style save 读取失败 本地").contains("样式已保存"), "bridge read error cannot block a local save");
            f.bridgeStatus = 200;
            f.server.stop(0);
            check(f.command("private", ".style save 服务离线 本地").contains("样式已保存"), "offline WebUI cannot block a local save");
            check(f.command("private", ".style list").contains("服务离线 本地"), "local styles are listed without any WebUI access");
            // 导入需要 WebUI：离线时如实失败，但机器人自己的样式照常可用。
            String offline = f.command("private", ".style import webui");
            check(offline.startsWith("操作失败："), "offline import reports failure: " + offline);
            check(f.command("private", ".style load 服务离线 本地").contains("替换"), "local style still loads offline");
        }
    }

    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void failure(Operation operation, String fragment, String message) throws Exception {
        try { operation.run(); throw new AssertionError(message + ": expected failure"); }
        catch (IOException expected) { check(fragment == null || expected.getMessage().contains(fragment), message + ": " + expected.getMessage()); }
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
        final ExecutorService executor;
        final SdClient client;
        final Bot bot;
        final JsonObject state = Json.parse("{\"positive\":\"initial +\",\"negative\":\"initial -\",\"source\":\"webui-live\",\"revision\":1,\"sampler_name\":\"Euler a\",\"styles\":[\"existing selected\"],\"width\":512,\"height\":512,\"settings_initialized\":true}");
        final Map<String, JsonObject> saved = new ConcurrentHashMap<>();
        /** WebUI 侧的预设样式（导入源）：名称 → {正向, 反向}。 */
        final Map<String, String[]> presets = new ConcurrentHashMap<>(Map.of("existing selected", new String[]{"style {prompt}", "style negative"}));
        final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
        final AtomicInteger promptReads = new AtomicInteger(), saveCalls = new AtomicInteger();
        volatile int bridgeStatus = 200, saveStatus = 200;
        volatile boolean changeAfterRead;
        volatile JsonObject lastSave;
        volatile String forcedResponse;

        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "style-save-tests").toAbsolutePath();
            Files.createDirectories(work); root = Files.createTempDirectory(work, "case-");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "style-save-mock"); t.setDaemon(true); return t; });
            server.setExecutor(executor); server.createContext("/", this::handle); server.start();
            JsonObject sd = new JsonObject(); sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort()); sd.addProperty("timeout_seconds", 2);
            JsonObject config = new JsonObject(); config.add("sd", sd);
            Json.atomicWrite(root.resolve("config.json"), config);
            client = new SdClient(root, sd);
            bot = new Bot(new Settings(root), client, (event, segments) -> {
                replies.add(new Reply(event.deepCopy(), Bot.messageText(segments)));
                return CompletableFuture.completedFuture(null);
            });
            // The startup personal-prompt seed reads the page once; counters below track command reads only.
            promptReads.set(0);
        }
        synchronized JsonObject stateCopy() { return state.deepCopy(); }
        synchronized void setPrompts(String positive, String negative) {
            state.addProperty("positive", positive); state.addProperty("negative", negative);
            state.addProperty("revision", state.get("revision").getAsInt() + 1);
        }
        String command(String type, String command) throws Exception {
            JsonObject event = new JsonObject(); event.addProperty("post_type", "message"); event.addProperty("message_type", type);
            event.addProperty("user_id", 456); event.addProperty("self_id", 777); event.addProperty("message_id", IDS.incrementAndGet());
            if (type.equals("group")) event.addProperty("group_id", 999);
            event.add("message", Maps.text(command)); bot.accept(event);
            Reply reply = replies.poll(5, TimeUnit.SECONDS); check(reply != null, "command replies: " + command);
            equal(type, reply.event().get("message_type").getAsString(), "response retains group/private context");
            if (type.equals("group")) equal(999, reply.event().get("group_id").getAsInt(), "response retains group id");
            return reply.text();
        }
        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/pixiko-bridge/v1/prompts")) {
                    if (exchange.getRequestMethod().equals("GET")) promptReads.incrementAndGet();
                    if (bridgeStatus != 200) { send(exchange, bridgeStatus, "{}"); return; }
                    synchronized (this) {
                        if (exchange.getRequestMethod().equals("PUT")) {
                            JsonObject body = body(exchange);
                            for (String key : List.of("positive", "negative", "styles", "sampler_name", "width", "height"))
                                if (body.has(key)) state.add(key, body.get(key).deepCopy());
                            state.addProperty("revision", state.get("revision").getAsInt() + 1);
                        }
                        JsonObject snapshot = stateCopy();
                        if (changeAfterRead) { changeAfterRead = false; setPrompts("later +", "later -"); }
                        send(exchange, 200, snapshot.toString());
                    }
                } else if (path.equals("/pixiko-bridge/v1/styles")) {
                    saveCalls.incrementAndGet(); lastSave = body(exchange);
                    if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, "{}"); return; }
                    if (saveStatus != 200) { send(exchange, saveStatus, "{\"detail\":\"mock save failure\"}"); return; }
                    if (forcedResponse != null) { send(exchange, 200, forcedResponse); return; }
                    String name = lastSave.get("name").getAsString();
                    boolean exists = saved.containsKey(name);
                    if (exists && !lastSave.get("overwrite").getAsBoolean()) { send(exchange, 409, "{}"); return; }
                    saved.put(name, lastSave.deepCopy());
                    JsonObject result = new JsonObject(); result.addProperty("name", name); result.addProperty("overwritten", exists);
                    send(exchange, 200, result.toString());
                } else if (path.equals("/sdapi/v1/prompt-styles")) {
                    // 完整条目：导入（.style import webui）需要 name/prompt/negative_prompt 三件套。
                    JsonArray catalog = new JsonArray();
                    for (String name : presets.keySet()) {
                        JsonObject entry = new JsonObject(); entry.addProperty("name", name);
                        entry.addProperty("prompt", presets.get(name)[0]);
                        entry.addProperty("negative_prompt", presets.get(name)[1]);
                        catalog.add(entry);
                    }
                    send(exchange, 200, catalog.toString());
                } else send(exchange, 404, "{}");
            } finally { exchange.close(); }
        }
        static JsonObject body(HttpExchange exchange) throws IOException { return Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); }
        static void send(HttpExchange exchange, int status, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }
        public void close() { bot.close(); server.stop(0); executor.shutdownNow(); }
    }
}

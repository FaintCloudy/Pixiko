package cn.szu.bot;

import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import cn.szu.bot.qq.QqClient;
import cn.szu.bot.sd.SdClient;

public final class Main {
    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) { Log.error("启动失败", e); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("bot.home", ".")).toAbsolutePath().normalize();
        seedFirstRunConfig(root);
        Settings settings = new Settings(root);
        if (args.length > 0 && args[0].equals("--set-map")) {
            if (args.length != 3) throw new IllegalArgumentException("用法：run.bat --set-map yh|liv \"图片或文件夹路径\"");
            Files.createDirectories(root.resolve("data"));
            try (FileChannel file = FileChannel.open(root.resolve("data/bot.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = file.tryLock()) {
                if (lock == null) throw new IllegalStateException("请先停止机器人再使用 --set-map；运行时可使用管理员 .map set 指令。");
                settings = new Settings(root);
                settings.setMapPath(args[1], args[2]);
                System.out.println("地图路径已保存：" + settings.mapPath(args[1])); return;
            }
        }
        if (args.length > 0 && args[0].equals("--help")) { System.out.println(Bot.HELP); return; }
        Log.init(root);
        if (args.length > 0 && args[0].equals("--setup")) {
            // 配置向导要独占 config.json，所以先确认没有实例在跑。
            Files.createDirectories(root.resolve("data"));
            try (FileChannel file = FileChannel.open(root.resolve("data/bot.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = file.tryLock()) {
                if (lock == null) throw new IllegalStateException("请先停止机器人（stop-bot.ps1）再运行 --setup。");
                settings = new Settings(root);
                new Setup(root, settings).run();
            }
            return;
        }
        JsonObject config = settings.snapshot();
        if (args.length > 0 && args[0].equals("--check")) { check(config, new SdClient(root, Json.obj(config, "sd"))); return; }
        if (args.length != 0) throw new IllegalArgumentException("未知启动参数。支持 --help、--check、--setup、--set-map。");
        Files.createDirectories(root.resolve("data"));
        try (FileChannel lockFile = FileChannel.open(root.resolve("data/bot.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = lockFile.tryLock()) {
            if (lock == null) throw new IllegalStateException("此目录已有机器人运行，请先关闭旧实例。");
            settings = new Settings(root);
            // 首次配置放在网页端（见 openSetupPage）：命令行向导只在显式 run.bat --setup 时出现，
            // 否则第一次运行会停在这个窗口等输入，而用户很可能根本没在看它。
            boolean setupNeeded = Setup.needed(settings);
            settings.botName();
            config = settings.snapshot();
            SdClient sd = new SdClient(root, Json.obj(config, "sd"));
            final JsonObject noticeConfig = config;   // 捕获给停机钩子使用（lambda 需要 final）
            final Settings noticeSettings = settings;
            try (QqClient transport = new QqClient(config); Bot bot = new Bot(settings, sd, new Bot.Sender() {
                public java.util.concurrent.CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                    return transport.send(event, segments);
                }
                public java.util.concurrent.CompletableFuture<Void> sendMap(JsonObject event, JsonArray segments) {
                    return transport.sendMap(event, segments);
                }
                public java.util.concurrent.CompletableFuture<Void> sendRecord(JsonObject event, java.util.List<JsonArray> messages) {
                    return transport.sendRecord(event, messages);
                }                public java.util.concurrent.CompletableFuture<com.google.gson.JsonElement> callApi(String action, JsonObject params) {
                    return transport.callApi(action, params);
                }
            })) {
                CountDownLatch stop = new CountDownLatch(1);
                // 网页控制台每次启动都拉起：同一个进程、独立端口，读写同一份数据，也是唯一的配置入口。
                java.util.concurrent.atomic.AtomicReference<cn.szu.bot.web.WebUiServer> web =
                        new java.util.concurrent.atomic.AtomicReference<>();
                try { web.set(new cn.szu.bot.web.WebUiServer(noticeSettings, bot)); web.get().start(); }
                catch (Exception webError) { Log.error("Web 管理界面启动失败（QQ 侧不受影响）：" + Bot.error(webError)); }
                if (setupNeeded) openSetupPage(web.get());
                Thread hook = new Thread(() -> {
                    Log.info("收到退出信号，正在关闭机器人。");
                    if (web.get() != null) web.get().close();
                    bot.close();
                    announce(transport, noticeConfig, false, noticeSettings);   // 下线播报要在关闭传输之前发出
                    transport.close();
                    stop.countDown();
                }, "bot-shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                Log.info(settings.botName() + " 机器人已启动。群聊和私聊发送 .help 查看指令。按 Ctrl+C 退出。");
                Log.info("粤海地图：" + settings.mapPath("yh") + "；丽湖地图：" + settings.mapPath("liv"));
                Log.info("聊天：全局 " + (settings.chatEnabled() ? "开启" : "关闭") + "，每会话每分钟上限 " + settings.chatFrequency()
                        + " 次；群里被 @ 或叫名字才回复（回复后 30 分钟内可继续对话）。");
                bot.startSdOnBoot();   // sd.start_on_boot：需要时顺带把 SD WebUI 拉起来（默认关）
                transport.start(bot::accept);
                announce(transport, noticeConfig, true, noticeSettings);
                stop.await();
            }
        }
    }
    /**
     * 首次配置：打印配置页地址，并尽量直接打开浏览器。
     *
     * <p>首次配置期间 {@code /api/config/**} 对本机免令牌（见 WebAuthFilter），所以用户不用先知道令牌；
     * 配好之后这些接口就和别的接口一样要令牌了。
     */
    private static void openSetupPage(cn.szu.bot.web.WebUiServer web) {
        if (web == null) {
            Log.warn("网页控制台没起来，首次配置请用 run.bat --setup 在命令行完成。");
            return;
        }
        String url = "http://127.0.0.1:" + web.actualPort() + "/setup";
        Log.info("首次配置在网页端进行：" + url + "（缺 DeepSeek 密钥；本机访问免令牌，配置完就恢复要令牌）");
        try {
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception error) {
            Log.warn("没能自动打开浏览器，请手动访问：" + url);
        }
    }
    /**
     * 第一次运行：还没有 config.json 时，拿随包分发的 config.example.json 起一份，
     * 好让配置向导（以及 run.bat --help 之类）能跑起来；示例也缺就写一个空对象，其余全靠各项默认值。
     * 已经有 config.json 时一个字都不动。
     */
    private static void seedFirstRunConfig(Path root) throws IOException {
        Path config = root.resolve("config.json");
        if (Files.exists(config)) return;
        Path example = root.resolve("config.example.json");
        if (Files.isRegularFile(example)) {
            try {
                Json.parse(Files.readString(example, StandardCharsets.UTF_8));   // 示例文件坏了就别照抄
                Files.copy(example, config);
                Log.info("第一次运行：还没有 config.json，已按 config.example.json 生成一份（内容随时可改）。");
                return;
            } catch (RuntimeException | IOException broken) {
                Log.warn("config.example.json 不可用，改用空配置启动：" + Bot.error(broken));
            }
        }
        Files.writeString(config, "{}" + System.lineSeparator(), StandardCharsets.UTF_8);
        Log.info("第一次运行：已生成空的 config.json，全部使用内置默认值（之后可用 run.bat --setup 补配置）。");
    }
    /**
     * Announces the bot in the main group after a successful start, so a restart is visible in chat.
     * Configured through startup_group_id / startup_notice in config.json.
     */
    /**
     * Announces online/offline in the main group. Exactly one message per event: an accepted API call means
     * it was sent, so only a failed call is retried, and only while the websocket is still connecting.
     */
    private static void announce(QqClient transport, JsonObject config, boolean online, Settings settings) {
        if (settings != null && !settings.startupNoticeEnabled()) {
            Log.info("上/下线播报已关闭（/chat notice on 可开启），本次不发送。");
            return;
        }
        String group = Json.str(config, "startup_group_id", "").strip();
        if (group.isBlank() || !group.matches("[1-9][0-9]{0,19}")) return;
        String text = online ? Json.str(config, "startup_notice", "我上线啦，随时可以叫我。发送 .help 查看指令。")
                : Json.str(config, "shutdown_notice", "我先下线啦，稍后再见。");
        JsonObject params = new JsonObject();
        params.addProperty("group_id", group);
        params.addProperty("message", text);
        if (!online) {
            String failure = sendOnce(transport, params);
            if (failure == null) Log.info("下线消息已发送到群 " + group + "。");
            else Log.warn("下线消息未发送：" + failure);
            return;
        }
        Thread announcer = new Thread(() -> {
            for (int attempt = 1; attempt <= 30; attempt++) {
                String failure = sendOnce(transport, params);
                if (failure == null) { Log.info("上线消息已发送到群 " + group + "。"); return; }
                // Only a not-yet-connected socket is worth waiting for; never resend after an accepted call.
                if (!failure.contains("尚未连接")) { Log.warn("上线消息未发送：" + failure); return; }
                try { Thread.sleep(2000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
            Log.warn("上线消息发送失败：连接长时间不可用（群 " + group + "）。");
        }, "startup-notice");
        announcer.setDaemon(true);
        announcer.start();
    }

    /** null when the API accepted the message; otherwise the failure reason. */
    private static String sendOnce(QqClient transport, JsonObject params) {
        try {
            transport.callApi("send_group_msg", params).get(15, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        } catch (Exception error) {
            String message = Bot.error(error);
            return message == null || message.isBlank() ? "未知原因" : message;
        }
    }
    private static void check(JsonObject config, SdClient sd) {
        JsonObject qq = config.has("qq") ? Json.obj(config, "qq") : config;
        try {
            URI uri = URI.create(Json.str(qq, "ws_url", "ws://127.0.0.1:3001"));
            int port = uri.getPort() >= 0 ? uri.getPort() : (uri.getScheme().equals("wss") ? 443 : 80);
            try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(uri.getHost(), port), 3000); }
            Log.info("QQ 服务端口可达（登录、Token 和协议状态需启动后确认）。");
        } catch (Exception e) { Log.warn("QQ 尚未连接：请启用 ws://127.0.0.1:3001 正向 WebSocket 服务。"); }
        try {
            SdClient.GenerationRequest current = sd.generationRequest();
            SdClient.GenerationSettings parameters = current.settings();
            Log.info("SD prompt 可读取，来源：" + current.prompts().source());
            Log.info("SD 生成设置：" + parameters.samplerName() + "，" + parameters.width() + "×" + parameters.height()
                    + "，已选样式 " + parameters.styles().size() + " 个。来源：" + parameters.source());
        }
        catch (Exception e) { Log.error("SD 检查失败", e); }
    }
}

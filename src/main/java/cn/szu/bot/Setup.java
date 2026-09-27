package cn.szu.bot;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import cn.szu.bot.chat.DeepSeekPrompts;

/**
 * 初次部署向导：把必须由人填的东西一次问清楚，并尽量自动探测能探测的。
 *
 * <ul>
 *   <li>Stable Diffusion：先扫常见端口找活着的 API，再从 <code>/sdapi/v1/sd-models</code> 的模型全路径
 *       反推安装目录；找不到才问，并顺手把 LoRA 目录（<code>models/Lora</code>）一起填好。</li>
 *   <li>NapCat（QQ）：端口选填，默认沿用配置或 3001。</li>
 *   <li>DeepSeek：两条通道各一份密钥（先填地址也行，会自动换成 api_base 再问密钥）。</li>
 *   <li>网页控制台：登录令牌，留空沿用已有或自动生成。</li>
 * </ul>
 *
 * 只写 config.json 与两份密钥文件；不触碰提示词、样式、队列等运行数据。
 */
public final class Setup {
    private static final int[] SD_PORTS = {7860, 7861, 7862, 7863, 7864, 7865, 7866, 7867, 7868, 7869};
    private static final String[] SD_MARKERS = {"webui-user.bat", "webui.bat", "launch.py", "webui",
            "models/Stable-diffusion", "models/checkpoints"};
    private final Path root;
    private final Settings settings;
    private final BufferedReader input;

    public Setup(Path root, Settings settings) {
        this.root = root;
        this.settings = settings;
        this.input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    /** 需要向导吗：两条通道的密钥或网页令牌任一缺失，就当作还没配置完。 */
    public static boolean needed(Settings settings) {
        return !keyPresent(settings.root, DeepSeekPrompts.Channel.IMAGE.keyFile())
                || !keyPresent(settings.root, DeepSeekPrompts.Channel.CHAT.keyFile())
                || tokenMissing(settings);
    }

    private static boolean keyPresent(Path root, String file) {
        try {
            Path path = root.resolve(file);
            return Files.isRegularFile(path) && !Files.readString(path, StandardCharsets.UTF_8).strip().isBlank();
        } catch (Exception error) { return false; }
    }

    private static boolean tokenMissing(Settings settings) {
        try { return settings.webToken().isBlank(); }
        catch (Exception error) { return true; }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("bot.home", ".")).toAbsolutePath().normalize();
        Log.init(root);
        new Setup(root, new Settings(root)).run();
    }

    public void run() throws IOException {
        Log.info("=== 初次配置向导 ===");
        Log.info("直接回车使用 [方括号] 里的默认值；之后随时可以用 run.bat --setup 重新配置。");
        JsonObject config = settings.snapshot();

        int port = detectSdPort(Json.str(Json.obj(config, "sd"), "base_url", ""));
        if (port > 0) Log.info("检测到 Stable Diffusion API 正在 127.0.0.1:" + port + " 上运行。");
        port = askPort("Stable Diffusion 的 API 端口", port, 7860);
        Path sdRoot = locateStableDiffusion(port, Json.str(Json.obj(config, "sd"), "root", ""));
        if (sdRoot != null) Log.info("已自动定位 Stable Diffusion 目录：" + sdRoot);
        String answer = ask("Stable Diffusion 安装目录", sdRoot == null ? "" : sdRoot.toString());
        if (!answer.isBlank()) {
            Path candidate = Path.of(answer.strip());
            if (Files.isDirectory(candidate)) sdRoot = candidate;
            else Log.warn("目录不存在，本次不写入：" + candidate);
        }
        Path loraDir = detectLoraDir(sdRoot);

        int qqPort = detectQqPort(Json.str(Json.obj(config, "qq"), "ws_url", ""));
        qqPort = askPort("NapCat 的 WebSocket 端口（选填，QQ 不接也能先用网页控制台）", qqPort, 3001);

        Log.info("两条 DeepSeek 通道各填一份密钥（sk-… 开头）；用自建/中转网关时，先填地址，密钥随后再问。");
        String[] image = askChannel(config, DeepSeekPrompts.Channel.IMAGE);
        String[] chat = askChannel(config, DeepSeekPrompts.Channel.CHAT);

        String token = askToken(config);

        JsonObject next = config.deepCopy();
        JsonObject sd = Json.obj(next, "sd");
        sd.addProperty("base_url", "http://127.0.0.1:" + port);
        if (sdRoot != null) sd.addProperty("root", sdRoot.toAbsolutePath().normalize().toString().replace('\\', '/'));
        // SD 目录找到了就把自启动打开（生成前发现 SD 没在跑就拉起来）；用 /sd auto off 或网页面板可关。
        if (sdRoot != null && !sd.has("auto_start")) sd.addProperty("auto_start", true);
        if (sdRoot == null) Log.warn("没找到 SD 安装目录，自启动不可用：请填 sd.root，或直接用绘世启动器手动开 SD。");
        next.add("sd", sd);
        if (loraDir != null) {
            JsonObject civitai = Json.obj(next, "civitai");
            civitai.addProperty("lora_dir", loraDir.toAbsolutePath().normalize().toString().replace('\\', '/'));
            next.add("civitai", civitai);
        } else {
            JsonObject civitai = Json.obj(next, "civitai");
            String configured = Json.str(civitai, "lora_dir", "");
            if (configured.isBlank()) Log.warn("没找到 LoRA 目录，.lora 下载前请手动填 civitai.lora_dir（或重跑 --setup）。");
        }
        JsonObject qq = Json.obj(next, "qq");
        qq.addProperty("ws_url", "ws://127.0.0.1:" + qqPort);
        next.add("qq", qq);
        if (!image[0].isBlank()) addApiBase(next, DeepSeekPrompts.Channel.IMAGE, image[0]);
        if (!chat[0].isBlank()) addApiBase(next, DeepSeekPrompts.Channel.CHAT, chat[0]);
        JsonObject webui = Json.obj(next, "webui");
        webui.addProperty("access_token", token);
        webui.addProperty("enabled", true);
        next.add("webui", webui);
        Json.atomicWrite(root.resolve("config.json"), next);
        settings.reload();

        if (!image[1].isBlank()) writeKey(DeepSeekPrompts.Channel.IMAGE.keyFile(), image[1]);
        if (!chat[1].isBlank()) writeKey(DeepSeekPrompts.Channel.CHAT.keyFile(), chat[1]);

        Log.info("=== 配置完成 ===");
        Log.info("Stable Diffusion：http://127.0.0.1:" + port + (sdRoot == null ? "" : "（目录 " + sdRoot + "）"));
        Log.info("NapCat：ws://127.0.0.1:" + qqPort + "（QQ 连不上不影响网页控制台）");
        Log.info("网页控制台：http://<本机或局域网 IP>:" + settings.webPort() + "/，登录令牌 " + token);
        Log.info("DeepSeek 密钥：生图 " + (image[1].isBlank() ? "沿用已有" : "已写入")
                + " / 聊天 " + (chat[1].isBlank() ? "沿用已有" : "已写入"));
        Log.info("Civitai 账号可以稍后配置：控制台「系统 → Civitai 账号」生成一次性登录链接即可；"
                + "不配时搜索与下载走 civitai.com（内容受限）。");
    }

    // ---------------------------------------------------------------- 输入

    private String ask(String label, String fallback) {
        String shown = fallback == null || fallback.isBlank() ? "" : " [" + fallback + "]";
        System.out.print(label + shown + "：");
        System.out.flush();
        String line;
        try { line = input.readLine(); }
        catch (IOException error) { return fallback == null ? "" : fallback; }
        if (line == null) { System.out.println(); return fallback == null ? "" : fallback; }
        String value = line.strip();
        return value.isEmpty() ? (fallback == null ? "" : fallback) : value;
    }

    private int askPort(String label, int detected, int fallback) {
        while (true) {
            String value = ask(label, String.valueOf(detected > 0 ? detected : fallback));
            try {
                int port = Integer.parseInt(value);
                if (port >= 1 && port <= 65535) return port;
            } catch (NumberFormatException ignored) { /* 再问一次 */ }
            System.out.println("端口须为 1–65535 的整数，请重新输入。");
        }
    }

    /** 返回 [api_base, key]；地址与密钥填在同一个问题时也能自动分开。 */
    private String[] askChannel(JsonObject config, DeepSeekPrompts.Channel channel) {
        String current = Json.str(Json.obj(config, channel.section()), "api_base", "");
        String base = ask(channel.label() + " API 地址（回车用官方 api.deepseek.com）", current).strip();
        if (base.equals(DeepSeekPrompts.DEFAULT_API) || base.equals("https://api.deepseek.com")) base = current;
        String existing = currentKey(channel.keyFile());
        String hint = existing.isBlank() ? "" : "（已配置 " + mask(existing) + "，回车沿用）";
        String key = ask(channel.label() + " API 密钥" + hint, "").strip();
        if (key.startsWith("http://") || key.startsWith("https://")) {
            base = key;
            key = ask(channel.label() + " API 密钥（上面填的是地址，这里填 sk-…）", "").strip();
        }
        return new String[]{base, key};
    }

    private String askToken(JsonObject config) {
        String current = Json.str(Json.obj(config, "webui"), "access_token", "");
        if (current.isBlank()) current = randomToken();
        String value = ask("网页控制台登录令牌（至少 8 位）", current).strip();
        if (value.length() < 8) {
            System.out.println("令牌太短，改用自动生成的：" + current);
            return current;
        }
        return value;
    }

    /** 网页端配置页也用同一套令牌规则，所以这里不是 private。 */
    static String randomToken() {
        String alphabet = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder token = new StringBuilder();
        Random random = new Random();
        for (int index = 0; index < 32; index++) token.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return token.toString();
    }

    private String currentKey(String file) {
        try {
            Path path = root.resolve(file);
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8).strip() : "";
        } catch (Exception error) { return ""; }
    }

    /** 密钥只回显成这样：够本人确认是哪一条，又不足以被抄走。 */
    static String mask(String key) {
        return key.length() <= 8 ? "已配置" : key.substring(0, 4) + "…" + key.substring(key.length() - 3);
    }

    private void writeKey(String file, String key) throws IOException {
        Path path = root.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, key.strip() + "\n", StandardCharsets.UTF_8);
        Log.info("已写入密钥文件：" + file);
    }

    private static void addApiBase(JsonObject config, DeepSeekPrompts.Channel channel, String value) {
        JsonObject section = Json.obj(config, channel.section());
        section.addProperty("api_base", value.strip());
        config.add(channel.section(), section);
    }

    // ---------------------------------------------------------------- 自动探测

    /** 扫常见端口，返回第一个能响应 SD API 的端口（没有则 0）。 */
    static int detectSdPort(String configured) {
        List<Integer> candidates = new ArrayList<>();
        int fromConfig = portOf(configured);
        if (fromConfig > 0) candidates.add(fromConfig);
        for (int port : SD_PORTS) if (!candidates.contains(port)) candidates.add(port);
        for (int port : candidates) if (sdAlive(port)) return port;
        return 0;
    }

    private static int portOf(String url) {
        try {
            if (url == null || url.isBlank()) return 0;
            int port = URI.create(url.strip()).getPort();
            return port > 0 ? port : 0;
        } catch (Exception error) { return 0; }
    }

    static boolean sdAlive(int port) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(700)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/sdapi/v1/options"))
                    .timeout(Duration.ofMillis(1200)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception error) { return false; }
    }

    /**
     * 自动定位 Stable Diffusion 目录：优先用正在运行的 API 反推（模型文件全路径 → 安装根目录），
     * 否则在常见位置浅扫带标识文件的目录。
     */
    static Path locateStableDiffusion(int port, String configured) {
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.strip());
            if (Files.isDirectory(path)) return path;
        }
        if (port > 0) {
            Path fromApi = locateFromApi(port);
            if (fromApi != null) return fromApi;
        }
        for (Path candidate : scanCandidates()) if (looksLikeWebUi(candidate)) return candidate;
        return null;
    }

    /** /sdapi/v1/sd-models 里每个模型都有完整路径，往上找到带 models/ 标识的根目录。 */
    private static Path locateFromApi(int port) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/sdapi/v1/sd-models"))
                    .timeout(Duration.ofMillis(2000)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            // 这个接口返回的是数组，Json.parse（本项目里要求对象）不适用，直接用 Gson 解析。
            JsonArray models = Json.GSON.fromJson(response.body(), JsonArray.class);
            if (models == null) return null;
            for (JsonElement item : models) {
                String filename = Json.str(item.getAsJsonObject(), "filename", "");
                if (filename.isBlank()) continue;
                for (Path parent = Path.of(filename).getParent(); parent != null; parent = parent.getParent()) {
                    if (isWebUiRoot(parent)) return parent;
                }
            }
        } catch (Exception error) { Log.warn("从 SD API 反推安装目录失败：" + Bot.error(error)); }
        return null;
    }

    private static boolean isWebUiRoot(Path path) {
        return (Files.isDirectory(path.resolve("models")) && Files.isDirectory(path.resolve("repositories")))
                || Files.isRegularFile(path.resolve("webui-user.bat"))
                || Files.isRegularFile(path.resolve("launch.py"));
    }

    /** 常见安装位置：固定盘根下的若干目录，深度限制在一两层，避免全盘扫描。 */
    private static List<Path> scanCandidates() {
        List<Path> candidates = new ArrayList<>();
        for (File driveFile : File.listRoots()) {
            Path drive = driveFile.toPath();
            if (!drive.toString().matches("[A-Za-z]:\\\\")) continue;
            candidates.add(drive.resolve("sd"));
            candidates.add(drive.resolve("stable-diffusion-webui"));
            candidates.add(drive.resolve("sd-webui"));
            for (String parent : List.of("sd", "AI", "tools")) {
                Path base = drive.resolve(parent);
                if (!Files.isDirectory(base)) continue;
                try (Stream<Path> children = Files.list(base)) {
                    children.filter(Files::isDirectory).limit(40).forEach(candidates::add);
                } catch (IOException ignored) { }
            }
        }
        Path home = Path.of(System.getProperty("user.home", "."));
        candidates.add(home.resolve("stable-diffusion-webui"));
        candidates.add(home.resolve("sd-webui"));
        return candidates;
    }

    private static boolean looksLikeWebUi(Path path) {
        Path name = path.getFileName();
        if (name == null) return false;
        String lower = name.toString().toLowerCase(Locale.ROOT);
        if (lower.contains("node_modules") || lower.contains("venv")) return false;
        for (String marker : SD_MARKERS) if (Files.exists(path.resolve(marker))) return true;
        return false;
    }

    /** LoRA 目录：A1111 默认在 <root>/models/Lora（大小写与新旧版本不一致，逐个试）。 */
    static Path detectLoraDir(Path sdRoot) {
        if (sdRoot == null) return null;
        for (String name : List.of("Lora", "lora", "LoRA", "LyCORIS")) {
            Path candidate = sdRoot.resolve("models").resolve(name);
            if (Files.isDirectory(candidate)) return candidate;
        }
        Path models = sdRoot.resolve("models");
        return Files.isDirectory(models) ? models.resolve("Lora") : null;
    }

    /** NapCat 端口：优先沿用配置里的，其次看 3001–3010 有没有在监听的，最后默认 3001。 */
    static int detectQqPort(String configured) {
        int fromConfig = portOf(configured);
        if (fromConfig > 0) return fromConfig;
        for (int port = 3001; port <= 3010; port++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
                return port;
            } catch (Exception ignored) { /* 继续试 */ }
        }
        return 3001;
    }
}

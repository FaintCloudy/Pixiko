package cn.szu.bot.testsupport;

import cn.szu.bot.Json;
import cn.szu.bot.TestCleanup;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 测试隔离的单一入口：一个测试进程 = 一个 UUID run-id = 一棵只属于自己的临时根目录。
 *
 * <p>契约见 {@code docs/TEST-ISOLATION.md}（用户第 14 节强制要求 1–14 条）。这个类负责其中
 * 1（不碰生产端口）、2（不碰生产数据）、3（独立临时根）、5/6（动态端口）、7（并行不共享）、
 * 11（违规立即失败）、12（结束/中断都清理）这几条里"测试这一侧"的部分；{@code build.ps1 -Test}
 * 负责给每个 run 起根目录并注入属性，{@link TestRuntime} 负责假服务。
 *
 * <h2>目录约定</h2>
 * <pre>
 * F:\Bot\work\tests\&lt;run-id&gt;\          临时根（TestEnvironment#home）
 *   .pixiko-test-root                  标记文件（内容 = run-id + PID）：生产启动见到它就拒绝启动
 *   bot.lock                           本进程的锁文件（内容 = PID），与生产 F:\Bot\data\bot.lock 无关
 *   config.json                        测试专用配置（端口全部指向 127.0.0.1 的临时假服务）
 *   data\  logs\  maps\  generated\  lora\  work\
 * </pre>
 *
 * <h2>系统属性</h2>
 * <ul>
 *   <li>{@code -Dbot.test.mode=true}：测试模式开关（生产 {@code Main} 见到它就拒绝启动）。</li>
 *   <li>{@code -Dbot.test.home=<run 根>}：本次 run 的临时根；不传则由本类生成
 *       {@code <生产根>/work/tests/<run-id>} 并回写属性。</li>
 *   <li>{@code -Dbot.test.run.id=<UUID>}：run-id；不传则生成。</li>
 *   <li>{@code -Dbot.test.production.root=<仓库根>}：生产根（只用来判断"什么算生产路径"，绝不写入）。</li>
 *   <li>{@code -Dbot.test.web.port=0} / {@code -Dbot.test.onebot.port=0} / {@code -Dbot.test.sd.port=0}：
 *       假服务期望端口，{@code 0} = 由操作系统分配（{@link #requestedPort}）。</li>
 * </ul>
 *
 * <p>兼容：老测试读的是 {@code -Dbot.test.work}（默认 {@code work}，即 {@code F:\Bot\work}）。本类会把
 * {@code bot.test.work} 指向 {@code <run 根>\work}，于是既有用例自动落到本次 run 的目录里，
 * 一行都不用改。{@code bot.test.home} 与 {@code bot.test.work} 两个都传时以 {@code home} 为准。
 *
 * <p>清理：{@link #close()} 与 JVM shutdown hook 都走 {@link #cleanup()}，先关自己起的资源（反序），
 * 再用 {@link TestCleanup} 带重试地删掉整棵 run 根目录。失败/中断也一样跑。
 */
public final class TestEnvironment implements AutoCloseable {

    /** 测试模式开关。 */
    public static final String PROP_MODE = "bot.test.mode";
    /** 本次 run 的临时根。 */
    public static final String PROP_HOME = "bot.test.home";
    /** 老属性：测试临时工作目录（默认 {@code work}）。 */
    public static final String PROP_LEGACY_WORK = "bot.test.work";
    /** 本次 run 的 UUID。 */
    public static final String PROP_RUN_ID = "bot.test.run.id";
    /** 生产根（仓库根），只读判断用。 */
    public static final String PROP_PRODUCTION_ROOT = "bot.test.production.root";
    /** 假 WebUI 期望端口（0 = 动态）。 */
    public static final String PROP_WEB_PORT = "bot.test.web.port";
    /** 假 OneBot 期望端口（0 = 动态）。 */
    public static final String PROP_ONEBOT_PORT = "bot.test.onebot.port";
    /** 假 SD 期望端口（0 = 动态）。 */
    public static final String PROP_SD_PORT = "bot.test.sd.port";

    /** 测试根目录里的标记文件；生产 {@code Main} 见到它就拒绝启动。 */
    public static final String MARKER = cn.szu.bot.TestModeGuard.MARKER;

    /** 生产端口：测试既不许连、也不许监听（用户第 14 节第 1 条）。 */
    public static final List<Integer> PRODUCTION_PORTS = List.of(8787, 3001, 7860, 7861);

    /** {@code <生产根>/work/tests}：所有 run-id 目录的唯一父目录。 */
    private static final String RUNS_DIR = "work/tests";

    private final String runId;
    private final Path productionRoot;
    private final Path home;
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<String> audit = new ArrayList<>();
    private final Map<String, Integer> ports = new LinkedHashMap<>();
    private final Thread shutdownHook;
    private boolean closed;

    // ------------------------------------------------------------------ 构造

    /**
     * 为当前进程建一个独立 run 根。第一次调用生成 UUID run-id；同一 JVM 内再调用会复用属性里的 run-id，
     * 但根目录必须不同（{@link #createAt}）。
     */
    public static TestEnvironment create() throws IOException {
        String runId = System.getProperty(PROP_RUN_ID, "").strip();
        if (runId.isEmpty()) {
            runId = UUID.randomUUID().toString();
            System.setProperty(PROP_RUN_ID, runId);
        }
        Path production = productionRoot();
        String configured = System.getProperty(PROP_HOME, "").strip();
        Path home = configured.isEmpty()
                ? production.resolve(RUNS_DIR).resolve(runId)
                : Path.of(configured).toAbsolutePath().normalize();
        return new TestEnvironment(runId, production, home);
    }

    /** 在指定根上建环境（并行 run、子进程用）：根必须落在 {@code <生产根>/work/tests} 下。 */
    public static TestEnvironment createAt(Path home) throws IOException {
        String runId = System.getProperty(PROP_RUN_ID, "").strip();
        if (runId.isEmpty()) runId = UUID.randomUUID().toString();
        return new TestEnvironment(runId, productionRoot(), home.toAbsolutePath().normalize());
    }

    /** 生产根：{@code -Dbot.test.production.root}，否则当前工作目录（{@code build.ps1 -Test} 会显式传）。 */
    public static Path productionRoot() {
        String configured = System.getProperty(PROP_PRODUCTION_ROOT, "").strip();
        Path root = configured.isEmpty() ? Path.of("").toAbsolutePath() : Path.of(configured).toAbsolutePath();
        try { return root.toRealPath(); } catch (IOException ignored) { return root.normalize(); }
    }

    private TestEnvironment(String runId, Path productionRoot, Path home) throws IOException {
        this.runId = runId;
        this.productionRoot = productionRoot;
        this.home = home;
        // 第 11 条：测试模式 + 测试根必须自洽，任何一条对不上就立即失败（不是警告）。
        System.setProperty(PROP_MODE, "true");
        System.setProperty(PROP_RUN_ID, runId);
        System.setProperty(PROP_HOME, home.toString());
        System.setProperty(PROP_PRODUCTION_ROOT, productionRoot.toString());
        assertIsolatedRoot(home, productionRoot);
        System.setProperty(PROP_LEGACY_WORK, home.resolve("work").toString());
        createLayout();
        this.shutdownHook = new Thread(this::cleanup, "test-isolation-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    // ------------------------------------------------------------------ 路径

    public String runId() { return runId; }
    public Path home() { return home; }
    public Path data() { return home.resolve("data"); }
    public Path logs() { return home.resolve("logs"); }
    public Path maps() { return home.resolve("maps"); }
    public Path generated() { return home.resolve("generated"); }
    public Path lora() { return home.resolve("lora"); }
    /** 老测试用的 {@code bot.test.work} 目录。 */
    public Path work() { return home.resolve("work"); }
    public Path configFile() { return home.resolve("config.json"); }
    /** 本进程的锁文件（等价于生产 {@code data/bot.lock} 的"测试专用"版本）。 */
    public Path lockFile() { return home.resolve("bot.lock"); }
    public Path dataLockFile() { return data().resolve("bot.lock"); }

    /** 目录布局（第 3 条）。 */
    private void createLayout() throws IOException {
        for (Path directory : List.of(home, data(), logs(), maps(), generated(), lora(), work(),
                data().resolve("generated"), data().resolve("civitai"), data().resolve("webui"))) {
            Files.createDirectories(directory);
        }
        Files.writeString(home.resolve(MARKER), "run-id=" + runId + System.lineSeparator()
                + "pid=" + ProcessHandle.current().pid() + System.lineSeparator()
                + "created=" + Instant.now() + System.lineSeparator(), StandardCharsets.UTF_8);
        String owner = "pid=" + ProcessHandle.current().pid() + System.lineSeparator() + "run-id=" + runId
                + System.lineSeparator();
        Files.writeString(lockFile(), owner, StandardCharsets.UTF_8);
        Files.writeString(dataLockFile(), owner, StandardCharsets.UTF_8);
        writeConfig(baseConfig());
    }

    // ------------------------------------------------------------------ 守卫生效点

    /**
     * 第 11 条测试侧守卫：根目录绝不许落在生产位置上，且必须正好在 {@code <生产根>/work/tests/<run-id>} 里。
     *
     * <p>失败是硬失败（抛 {@link IllegalStateException}），不是打日志——"测试悄悄跑了生产数据"比"测试没跑起来"
     * 难查得多。
     */
    public static void assertIsolatedRoot(Path home, Path productionRoot) {
        Path root = home.toAbsolutePath().normalize();
        Path production = productionRoot.toAbsolutePath().normalize();
        if (root.equals(production)) {
            throw new IllegalStateException("测试隔离守卫：临时根不能是生产根 " + production + "。");
        }
        List<Path> forbidden = List.of(
                production.resolve("config.json"),
                production.resolve("data"),
                production.resolve("logs"),
                production.resolve("maps"),
                production.resolve("build"),
                production.resolve("build/pixiko.jar"));
        for (Path guarded : forbidden) {
            Path target = guarded.toAbsolutePath().normalize();
            if (root.equals(target) || root.startsWith(target)) {
                throw new IllegalStateException("测试隔离守卫：临时根 " + root + " 落在生产路径 " + target
                        + " 里。测试必须在 " + production.resolve(RUNS_DIR) + "\\<run-id> 下运行。");
            }
        }
        Path runs = production.resolve(RUNS_DIR).normalize();
        if (!root.startsWith(runs)) {
            throw new IllegalStateException("测试隔离守卫：临时根 " + root + " 不在 " + runs
                    + " 之下。第 3 条要求每个 run 用独立目录。");
        }
        if (root.equals(runs)) {
            throw new IllegalStateException("测试隔离守卫：临时根不能是 " + runs + " 本身，必须是它下面的 run-id 目录。");
        }
        if (Files.exists(production.resolve(MARKER))) {
            throw new IllegalStateException("测试隔离守卫：生产根 " + production + " 里出现了测试标记 " + MARKER
                    + "，说明生产目录被当成测试目录用过，先查清再跑。");
        }
    }

    /** 第 1 条：任何假服务、任何目标端口都不许是生产端口。 */
    public static void assertNotProductionPort(int port) {
        if (PRODUCTION_PORTS.contains(port)) {
            throw new IllegalStateException("测试隔离守卫：端口 " + port + " 是生产端口 "
                    + PRODUCTION_PORTS + "，测试既不许连也不许监听。");
        }
        if (port < 0 || port > 65535) throw new IllegalArgumentException("非法端口：" + port);
    }

    /** 期望端口：{@code 0}（默认）= 交给 {@code ServerSocket(0)} 由操作系统分配。 */
    public static int requestedPort(String property) {
        String value = System.getProperty(property, "0").strip();
        int port;
        try { port = Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new IllegalStateException("属性 " + property + " 不是端口：" + value); }
        assertNotProductionPort(port);
        return port;
    }

    /** 记录一个本次 run 真正用到的端口（服务名 → 端口），供报告与断言使用。 */
    public synchronized int claimPort(String service, int port) {
        assertNotProductionPort(port);
        ports.put(service, port);
        audit.add(service + " 127.0.0.1:" + port);
        return port;
    }

    public synchronized Map<String, Integer> ports() { return new LinkedHashMap<>(ports); }

    /** 本 run 碰过的所有 {@code 127.0.0.1:port}（证据用）。 */
    public synchronized List<String> endpointAudit() { return List.copyOf(audit); }

    /** 打印假服务端口（交付报告第 2 项）。 */
    public synchronized void printPorts(String title) {
        StringBuilder line = new StringBuilder("ISOLATION PORTS [" + title + "] run-id=" + runId + "：");
        if (ports.isEmpty()) line.append("（无）");
        else ports.forEach((service, port) -> line.append(' ').append(service).append("=127.0.0.1:").append(port));
        System.out.println(line);
    }

    /** 登记一个要在 {@link #cleanup()} 里关掉的资源（反序关闭）。 */
    public synchronized <T extends AutoCloseable> T register(T resource) {
        resources.add(resource);
        return resource;
    }

    /** 断言某条路径在本次 run 的临时根里（第 2 条的运行时兜底）。 */
    public Path requireInside(Path path) {
        Path target = path.toAbsolutePath().normalize();
        if (!target.startsWith(home)) {
            throw new IllegalStateException("测试隔离守卫：路径 " + target + " 不在本次 run 的临时根 " + home + " 里。");
        }
        return target;
    }

    // ------------------------------------------------------------------ 配置

    /**
     * 测试专用基础配置：所有地址都指向 {@code 127.0.0.1} 的临时假服务（端口由 {@link TestRuntime} 注入），
     * {@code webui.port=0} 由操作系统分配；LoRA / 生成图目录一律落在本次 run 的临时根里。
     */
    public JsonObject baseConfig() {
        String lora = lora().toString().replace('\\', '/');
        JsonObject config = new JsonObject();
        config.addProperty("bot_name", "测试小鸟");
        config.addProperty("owner_user_id", "10001");
        config.addProperty("startup_group_id", "");
        config.addProperty("startup_notice", "");
        config.addProperty("shutdown_notice", "");

        JsonObject qq = new JsonObject();
        qq.addProperty("ws_url", "ws://127.0.0.1:0");
        qq.addProperty("access_token", "offline-test-token");
        qq.addProperty("bot_name", "测试小鸟");
        config.add("qq", qq);

        JsonObject webui = new JsonObject();
        webui.addProperty("enabled", false);
        webui.addProperty("host", "127.0.0.1");
        webui.addProperty("port", 0);                    // 0 = 动态；Settings#webPort() 会夹到 1，
        webui.addProperty("access_token", "offline-test-token");   // 测试用 WebUiServer(settings, bot, 0) 显式指定
        config.add("webui", webui);

        JsonObject sd = new JsonObject();
        sd.addProperty("base_url", "http://127.0.0.1:0");
        sd.addProperty("root", lora);
        sd.addProperty("timeout_seconds", 5);
        config.add("sd", sd);

        JsonObject civitai = new JsonObject();
        civitai.addProperty("lora_dir", lora);
        civitai.addProperty("base_url", "https://civitai.com");   // 只在校验里用，流量走注入的 transport
        civitai.addProperty("timeout_seconds", 5);
        config.add("civitai", civitai);

        JsonObject image = new JsonObject();
        image.addProperty("api_base", "http://127.0.0.1:0");
        image.addProperty("timeout_seconds", 5);
        config.add("progen", image);
        JsonObject chat = new JsonObject();
        chat.addProperty("api_base", "http://127.0.0.1:0");
        chat.addProperty("timeout_seconds", 5);
        config.add("chat_api", chat);
        return config;
    }

    /** 写测试配置（会做一次"没有生产端口"的体检）。 */
    public void writeConfig(JsonObject config) throws IOException {
        String text = Json.GSON.toJson(config);
        for (int port : PRODUCTION_PORTS) {
            if (text.contains(":" + port)) {
                throw new IllegalStateException("测试隔离守卫：测试配置里出现生产端口 :" + port + "：" + text);
            }
        }
        Files.createDirectories(home);
        Json.atomicWrite(configFile(), config);
    }

    // ------------------------------------------------------------------ 生产只读快照（证据用，第 14 条第 b 条）

    /**
     * 生产数据指纹：**只读元数据**（相对路径 + 大小 + 修改时间），从不打开、也从不读取文件内容。
     *
     * <p>这样既能证明"测试没改生产 config.json / data/**"，又不会把生产配置里的密钥读进测试进程，
     * 也不会把任何 key/cookie/token 打到日志里（铁律）。
     */
    public static String productionFingerprint(Path productionRoot, int maxEntries) throws IOException {
        MessageDigest digest = newDigest();
        StringBuilder text = new StringBuilder();
        for (String name : List.of("config.json")) {
            Path file = productionRoot.resolve(name);
            if (Files.isRegularFile(file)) text.append(name).append('|').append(Files.size(file)).append('|')
                    .append(Files.getLastModifiedTime(file).toMillis()).append('\n');
            else text.append(name).append("|absent\n");
        }
        int entries = 0;
        Path data = productionRoot.resolve("data");
        if (Files.isDirectory(data)) {
            try (Stream<Path> walk = Files.walk(data)) {
                for (Path path : walk.sorted(Comparator.naturalOrder()).toList()) {
                    if (entries++ >= maxEntries) break;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    Path relative = data.relativize(path);
                    text.append("data/").append(relative.toString().replace('\\', '/')).append('|')
                            .append(Files.size(path)).append('|')
                            .append(Files.getLastModifiedTime(path).toMillis()).append('\n');
                }
            }
        }
        text.append("entries=").append(Math.min(entries, maxEntries)).append('\n');
        digest.update(text.toString().getBytes(StandardCharsets.UTF_8));
        return hex(digest.digest());
    }

    /** 生产 config.json 的元数据（大小 + 修改时间），用于"前后一致"断言。 */
    public static String productionConfigStamp(Path productionRoot) throws IOException {
        Path file = productionRoot.resolve("config.json");
        if (!Files.isRegularFile(file)) return "absent";
        return Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis();
    }

    /**
     * 生产数据"结构指纹"：只含相对路径 + 大小（**不含修改时间**）。
     *
     * <p>为什么单独来一份：生产实例正在运行，它自己可能在同一时间更新某个文件的修改时间（那是它的活动，
     * 不是测试干的）。所以硬断言用这份"没有新增/没有删除/没有改大小"的指纹；带修改时间的那份
     * {@link #productionFingerprint} 只做报告，mtime 漂移最多打一行提示。
     */
    public static String productionStructureFingerprint(Path productionRoot, int maxEntries) throws IOException {
        MessageDigest digest = newDigest();
        StringBuilder text = new StringBuilder();
        Path config = productionRoot.resolve("config.json");
        text.append("config.json|").append(Files.isRegularFile(config) ? Files.size(config) : -1).append('\n');
        int entries = 0;
        Path data = productionRoot.resolve("data");
        if (Files.isDirectory(data)) {
            try (Stream<Path> walk = Files.walk(data)) {
                for (Path path : walk.sorted(Comparator.naturalOrder()).toList()) {
                    if (entries++ >= maxEntries) break;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    text.append("data/").append(data.relativize(path).toString().replace('\\', '/')).append('|')
                            .append(Files.size(path)).append('\n');
                }
            }
        }
        digest.update(text.toString().getBytes(StandardCharsets.UTF_8));
        return hex(digest.digest());
    }

    // ------------------------------------------------------------------ 进程快照（证据用，第 14 条第 a/e 条）

    /** 本机所有 java/node/python 进程的 {@code pid@启动时间}（不改动它们，只读）。 */
    public static Set<String> processSnapshot() {
        Set<String> snapshot = new TreeSet<>();
        long self = ProcessHandle.current().pid();
        ProcessHandle.allProcesses().forEach(process -> {
            if (process.pid() == self) return;
            String command = process.info().command().orElse("").toLowerCase(Locale.ROOT);
            String base = command.substring(command.lastIndexOf('\\') + 1).substring(command.lastIndexOf('/') + 1);
            if (!Set.of("java.exe", "javaw.exe", "node.exe", "python.exe", "pythonw.exe").contains(base)) return;
            snapshot.add(process.pid() + "@" + process.info().startInstant().map(Instant::toString).orElse("?"));
        });
        return snapshot;
    }

    /**
     * 生产端口当前的**监听者 PID**（{@code netstat -ano} 的 LISTENING 行，纯只读）。
     *
     * <p>这是第 a 条"生产端口的监听进程与 PID 不变"的直接证据：不建立任何连接（连一下就是违规），
     * 只读操作系统的套接字表。拿不到时返回空表，调用方会退回到"生产 Pixiko PID"这条证据。
     */
    public static Map<Integer, List<Long>> productionListeners() {
        Map<Integer, List<Long>> listeners = new LinkedHashMap<>();
        for (int port : PRODUCTION_PORTS) listeners.put(port, new ArrayList<>());
        try {
            Process process = new ProcessBuilder("netstat", "-ano").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            for (String line : output.split("\r?\n")) {
                String text = line.strip();
                if (!text.contains("LISTENING")) continue;
                String[] fields = text.split("\\s+");
                if (fields.length < 5) continue;
                String local = fields[1];
                int colon = local.lastIndexOf(':');
                if (colon < 0) continue;
                int port;
                try { port = Integer.parseInt(local.substring(colon + 1)); }
                catch (NumberFormatException ignored) { continue; }
                if (!listeners.containsKey(port)) continue;
                long pid;
                try { pid = Long.parseLong(fields[fields.length - 1]); }
                catch (NumberFormatException ignored) { continue; }
                if (!listeners.get(port).contains(pid)) listeners.get(port).add(pid);
            }
        } catch (Exception error) {
            System.err.println("警告：无法用 netstat 读取生产端口监听者：" + error);
        }
        for (List<Long> pids : listeners.values()) pids.sort(Comparator.naturalOrder());
        return listeners;
    }

    /** 正在跑的生产 Pixiko（{@code cn.szu.bot.Main}）PID 列表——也就是 8787 端口的监听者。
     *
     * <p>两条路：能拿到命令行就用命令行（带 {@code bot.home} / {@code pixiko.jar}）；拿不到（本机 Windows
     * 上 {@code ProcessHandle.info().commandLine()} 常常是空的）就用 JDK 自带的 {@code jcmd -l}，
     * 它只列 JVM、不 attach，对生产实例零影响，也不会连任何端口。
     */
    public static List<Long> productionPixikoPids() {
        List<Long> pids = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(process -> {
            String command = process.info().commandLine().orElse("");
            if (command.contains("cn.szu.bot.Main")
                    && (command.contains("pixiko.jar") || command.contains("bot.home="))) {
                pids.add(process.pid());
            }
        });
        if (pids.isEmpty()) pids.addAll(jcmdPixikoPids());
        pids.sort(Comparator.naturalOrder());
        return pids;
    }

    /** {@code <java.home>/bin/jcmd -l} 里主类是 {@code cn.szu.bot.Main} 的 PID（只读，不 attach）。 */
    private static List<Long> jcmdPixikoPids() {
        List<Long> pids = new ArrayList<>();
        Path jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd.exe");
        if (!Files.isRegularFile(jcmd)) jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd");
        if (!Files.isRegularFile(jcmd)) return pids;
        try {
            Process process = new ProcessBuilder(jcmd.toString(), "-l").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            for (String line : output.split("\r?\n")) {
                String[] parts = line.strip().split("\\s+", 2);
                if (parts.length < 2) continue;
                if (!parts[1].strip().equals("cn.szu.bot.Main")) continue;
                try { pids.add(Long.parseLong(parts[0])); } catch (NumberFormatException ignored) { }
            }
        } catch (Exception error) {
            System.err.println("警告：无法用 jcmd 识别生产 Pixiko：" + error);
        }
        return pids;
    }

    // ------------------------------------------------------------------ 关闭

    /**
     * 第 12 条：正常结束、异常结束、被打断都走这里——先反序关掉自己起的服务，再删掉自己的 run-id 目录。
     * 幂等，可重复调用。
     */
    public void cleanup() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        List<AutoCloseable> closing;
        synchronized (this) { closing = new ArrayList<>(resources); resources.clear(); }
        for (int index = closing.size() - 1; index >= 0; index--) {
            try { closing.get(index).close(); }
            catch (Exception error) { System.err.println("警告：测试资源关闭失败：" + error); }
        }
        TestCleanup.awaitQuiet(home, 500);
        TestCleanup.deleteQuietly(home, 8, 150);
        try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
        catch (IllegalStateException shuttingDown) { /* JVM 正在退出：钩子本来就会跑 */ }
    }

    @Override public void close() { cleanup(); }

    // ------------------------------------------------------------------ 小工具

    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 不可用", error); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value));
        return text.toString();
    }
}

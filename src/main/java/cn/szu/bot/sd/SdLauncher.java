package cn.szu.bot.sd;

import cn.szu.bot.Json;
import cn.szu.bot.Log;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Stable Diffusion WebUI 自启动：生成前发现 SD 没在跑就把它拉起来，等到接口可用再继续。
 *
 * <p>启动入口按优先级挑选（全部走本机已有的安装，不下载任何东西）：
 * <ol>
 *   <li>{@code sd.start_command}：用户显式配置的命令（.bat/.exe 或完整命令行）；</li>
 *   <li>{@code <sd.root>/webui-user.bat}（A1111 启动脚本，额外参数用 %* 透传给 launch.py）；</li>
 *   <li>{@code <sd.root>/python/python.exe <sd.root>/launch.py <args>}。</li>
 * </ol>
 * 参数默认 {@code --api --autolaunch --skip-python-version-check}，并且**保证带 --api**（没有它机器人连不上）；
 * 如果 SD 正在运行，会从它的进程命令行里学一次真实参数存进 {@code sd.start_args}
 * （绘世启动器那种带 --xformers / --medvram-sdxl 的启动，下次自启动就原样照用）。
 *
 * <p>并发与节流：同一时刻只允许一次启动尝试；两次尝试之间至少间隔 {@code sd.start_min_interval_seconds}
 * （默认 180 秒），避免 SD 起不来时被反复拉起。等待就绪有超时（{@code sd.start_timeout_seconds}，默认 300 秒）。
 */
public final class SdLauncher {
    /** 探活：SD 的 API 是否能连上。 */
    public interface Probe { boolean reachable(); }
    /** 真正拉起进程（测试里可以注入假的，不启动任何东西）。 */
    public interface Starter { Process launch(List<String> command, Path workDir, Path logFile) throws IOException; }

    public static final List<String> DEFAULT_ARGS = List.of("--api", "--skip-python-version-check");
    private static final Duration PROBE_GAP = Duration.ofSeconds(3);
    /** 就绪探活的最小间隔（测试可以缩短，避免等待）。 */
    public static final String START_ARGS_KEY = "start_args";

    private final Path root;
    /** 每次都读当前配置（控制台改了开关要立刻生效，不能拿启动时的快照）。 */
    private final java.util.function.Supplier<JsonObject> configSource;
    private final Probe probe;
    private final Starter starter;
    private final LongSupplier clock;
    private final Duration probeGap;
    private final AtomicBoolean starting = new AtomicBoolean();
    private final Path logFile;
    private volatile long lastAttemptMillis;
    private volatile String lastStatus = "还没有尝试启动过 SD。";

    public SdLauncher(Path root, JsonObject config, Probe probe) {
        this(root, () -> config, probe, SdLauncher::defaultStart, System::currentTimeMillis, PROBE_GAP);
    }

    /** 配置随取随读的构造（生产用：读 settings.snapshot() 里的 sd 段）。 */
    public SdLauncher(Path root, java.util.function.Supplier<JsonObject> configSource, Probe probe) {
        this(root, configSource, probe, SdLauncher::defaultStart, System::currentTimeMillis, PROBE_GAP);
    }

    /** 可注入探活/启动实现与时钟（测试用，也可给别的宿主复用）。 */
    public SdLauncher(Path root, JsonObject config, Probe probe, Starter starter, LongSupplier clock) {
        this(root, () -> config, probe, starter, clock, PROBE_GAP);
    }

    /** 完整构造：probeGap 是就绪探活间隔（测试里用很短的值）。 */
    public SdLauncher(Path root, java.util.function.Supplier<JsonObject> configSource, Probe probe, Starter starter,
                      LongSupplier clock, Duration probeGap) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        this.configSource = configSource == null ? JsonObject::new : configSource;
        this.probe = probe;
        this.starter = starter;
        this.clock = clock;
        this.probeGap = probeGap == null || probeGap.isZero() || probeGap.isNegative() ? PROBE_GAP : probeGap;
        this.logFile = this.root == null ? null : this.root.resolve("logs/sd-autostart.log");
    }

    private JsonObject config() {
        JsonObject current = configSource.get();
        return current == null ? new JsonObject() : current;
    }

    // ------------------------------------------------------------------ 配置

    /** 是否允许自动启动（sd.auto_start，默认开）。 */
    public boolean enabled() { return Json.bool(config(), "auto_start", true); }
    /** 机器人启动时是否顺带把 SD 拉起来（sd.start_on_boot，默认关）。 */
    public boolean startOnBoot() { return Json.bool(config(), "start_on_boot", false); }
    public int timeoutSeconds() { return Math.max(3, Json.num(config(), "start_timeout_seconds", 300)); }
    public int minIntervalSeconds() { return Math.max(0, Json.num(config(), "start_min_interval_seconds", 180)); }
    public Path root() { return root; }

    /** 启动 SD 安装目录（sd.root）。 */
    public static Path root(JsonObject config) {
        String value = Json.str(config, "root", "").strip();
        return value.isEmpty() ? null : Path.of(value);
    }

    /** 明确配置的启动命令（sd.start_command）。 */
    String configuredCommand() { return Json.str(config(), "start_command", "").strip(); }

    /** 启动参数：优先 sd.start_args，其次从正在运行的 SD 学到的值，最后默认值；保证含 --api。 */
    public List<String> startArgs() {
        String configured = Json.str(config(), "start_args", "").strip();
        List<String> args = configured.isEmpty() ? new ArrayList<>(DEFAULT_ARGS) : splitArgs(configured);
        if (args.stream().noneMatch(arg -> arg.equals("--api"))) args.add("--api");
        return List.copyOf(args);
    }

    /** 找到的启动入口（没有则返回 null）。 */
    public Path launcherScript() {
        if (root == null || !Files.isDirectory(root)) return null;
        // 优先"安装目录自带的 python + launch.py"：绘世整合包就是这种布局，
        // 而 webui-user.bat/webui.bat 会去找系统 python（实测失败：Windows Store 的 python 占位符）。
        Path python = pythonExecutable();
        Path script = root.resolve("launch.py");
        if (python != null && Files.isRegularFile(script)) return python;
        for (String name : List.of("webui-user.bat", "webui.bat")) {
            Path candidate = root.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private Path pythonExecutable() {
        if (root == null) return null;
        for (String relative : List.of("python/python.exe", "python/python", "venv/Scripts/python.exe", ".venv/Scripts/python.exe")) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /** 能不能启动（配置齐、入口找得到）。 */
    public boolean available() { return !configuredCommand().isEmpty() || launcherScript() != null; }

    /** 自启动日志改写到这个文件（生产环境放机器人的 logs 目录，方便和别的日志一起看）。 */
    public void logTo(Path file) {
        if (file == null) return;
        this.customLog = file.toAbsolutePath().normalize();
    }
    private volatile Path customLog;

    private Path logTarget() { return customLog != null ? customLog : logFile; }

    /**
     * 一行状态：给 /sd status、网页面板和日志用。
     */
    public String describe() {
        boolean up = probe.reachable();
        StringBuilder text = new StringBuilder(up ? "Stable Diffusion：运行中" : "Stable Diffusion：未运行");
        text.append("（自动启动：").append(enabled() ? "开启" : "关闭").append("）");
        if (root == null) text.append("\nSD 目录：未配置（config.json 的 sd.root）");
        else {
            text.append("\nSD 目录：").append(root);
            Path script = launcherScript();
            text.append("\n启动入口：").append(configuredCommand().isEmpty()
                    ? (script == null ? "没找到（检查 sd.root，或配置 sd.start_command）" : script)
                    : configuredCommand() + "（sd.start_command）");
            if (available()) text.append("\n启动参数：").append(String.join(" ", startArgs()));
        }
        text.append("\n最近一次：").append(lastStatus);
        return text.toString();
    }

    // ------------------------------------------------------------------ 启动

    /**
     * 需要时启动 SD 并等它就绪。无事可做（已运行/没配置/刚试过）返回空串；
     * 真的启动了则返回一句给用户看的话（含耗时），失败时返回失败原因。
     */
    public String ensureRunning() { return ensureRunning(false); }

    /** 手动启动（/sd start 与网页按钮）：不看 enabled，但同样有节流与并发保护。 */
    public String startNow() {
        if (probe.reachable()) return "SD 已经在运行了。";
        if (!available()) return "没找到 SD 启动入口：请在 config.json 填 sd.root（SD 安装目录）或 sd.start_command。\n" + describe();
        String notice = ensureRunning(true);
        return notice.isEmpty() ? lastStatus : notice;
    }

    private String ensureRunning(boolean force) {
        if (probe.reachable()) return "";
        if (!force && !enabled()) return "";
        if (!available()) {
            lastStatus = "没找到 SD 启动入口：请在 config.json 填 sd.root（SD 安装目录）或 sd.start_command。";
            return force ? lastStatus : "";   // 自动启动时没配置就不打扰用户，生成失败时的报错已经说明了原因
        }
        long now = clock.getAsLong();
        if (lastAttemptMillis > 0 && now - lastAttemptMillis < minIntervalSeconds() * 1000L) {
            long wait = (minIntervalSeconds() * 1000L - (now - lastAttemptMillis)) / 1000;
            lastStatus = "刚启动过还在等 SD 就绪（" + wait + " 秒后可再试）。";
            return force ? lastStatus : "";
        }
        if (!starting.compareAndSet(false, true)) { lastStatus = "已经在启动 SD 了，等它起来。"; return force ? lastStatus : ""; }
        lastAttemptMillis = now;
        try {
            List<String> command = command();
            if (command.isEmpty()) { lastStatus = "启动命令为空，未启动。"; return force ? lastStatus : ""; }
            long started = clock.getAsLong();
            Log.info("SD 未运行，开始" + (force ? "手动" : "自动") + "启动：" + String.join(" ", command) + "（工作目录 " + root + "）");
            lastStatus = "正在启动 SD：" + String.join(" ", command);
            try { starter.launch(command, root, logTarget()); }
            catch (Exception error) {
                lastStatus = "启动 SD 失败：" + error.getMessage();
                Log.error("启动 SD 失败：" + error.getMessage());
                return "SD 未运行，尝试自动启动失败：" + error.getMessage();
            }
            boolean ready = waitUntilReady(Duration.ofSeconds(timeoutSeconds()));
            long elapsed = (clock.getAsLong() - started) / 1000;
            if (ready) {
                lastStatus = "已自动启动 SD 并等到接口可用（用时 " + elapsed + " 秒）。";
                Log.info(lastStatus);
                return "SD WebUI 没在运行，已自动启动并等到就绪（用时 " + elapsed + " 秒）。";
            }
            lastStatus = "自动启动后等了 " + timeoutSeconds() + " 秒仍连不上 SD；请检查启动日志 " + logTarget() + "。";
            Log.warn(lastStatus);
            return "SD 未运行，已尝试自动启动，但等了 " + timeoutSeconds() + " 秒仍连不上；请检查启动日志：" + logTarget();
        } finally {
            starting.set(false);
        }
    }

    /** 轮询探活直到就绪或超时；返回是否就绪。迭代次数也设上限，时钟被冻结时也不会死循环。 */
    boolean waitUntilReady(Duration timeout) {
        long deadline = clock.getAsLong() + timeout.toMillis();
        int maxAttempts = (int) Math.max(1, timeout.toMillis() / Math.max(1, probeGap.toMillis()) + 1);
        int attempts = 0;
        while (clock.getAsLong() < deadline && attempts < maxAttempts) {
            if (probe.reachable()) return true;
            attempts++;
            if (attempts % 10 == 0) Log.info("等待 SD 就绪中…（已探活 " + attempts + " 次）");
            try { Thread.sleep(probeGap.toMillis()); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
        return probe.reachable();
    }

    public List<String> command() {
        String configured = configuredCommand();
        if (!configured.isEmpty()) {
            List<String> args = splitArgs(configured);
            if (args.isEmpty()) return List.of();
            List<String> full = new ArrayList<>();
            String exe = args.get(0);
            if (exe.toLowerCase(Locale.ROOT).endsWith(".bat") || exe.toLowerCase(Locale.ROOT).endsWith(".cmd")) {
                full.add("cmd.exe"); full.add("/c"); full.addAll(args);
                if (args.stream().noneMatch(arg -> arg.equals("--api"))) full.add("--api");
                return List.copyOf(full);
            }
            full.addAll(args);
            if (args.stream().noneMatch(arg -> arg.equals("--api"))) full.add("--api");
            return List.copyOf(full);
        }
        Path script = launcherScript();
        if (script == null) return List.of();
        String name = script.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".bat") || name.endsWith(".cmd")) {
            List<String> full = new ArrayList<>(List.of("cmd.exe", "/c", script.toString()));
            full.addAll(startArgs());
            return List.copyOf(full);
        }
        List<String> full = new ArrayList<>(List.of(script.toString(), root.resolve("launch.py").toString()));
        full.addAll(startArgs());
        return List.copyOf(full);
    }

    /** 真正起进程：不继承控制台，输出重定向到 logs/sd-autostart.log（追加），进程独立于机器人存活。 */
    private static Process defaultStart(List<String> command, Path workDir, Path logFile) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir == null ? null : workDir.toFile());
        builder.redirectErrorStream(true);
        prepareEnvironment(workDir, builder.environment());
        if (logFile != null) {
            Files.createDirectories(logFile.getParent());
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("NUL")));
        } else {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        Process process = builder.start();
        Log.info("SD 启动进程已创建：pid=" + process.pid() + "，日志 " + logFile);
        return process;
    }

    /**
     * 补齐整合包自带的 git / python 到 PATH（并设 GIT）。
     *
     * <p>绘世这类整合包不把 git 放进系统 PATH，手动启动时由启动器补上；机器人直接起 launch.py 时
     * 没有这一步，A1111 的 prepare_environment() 会因为找不到 git 直接报 "Couldn't fetch assets" 退出
     * （实测第一次自启动就是这样失败的）。
     */
    public static Map<String, String> prepareEnvironment(Path workDir, Map<String, String> environment) {
        if (workDir == null || environment == null) return environment;
        List<Path> extra = new ArrayList<>();
        for (String relative : List.of("git/cmd", "git/mingw64/bin", "python", "python/Scripts")) {
            Path directory = workDir.resolve(relative);
            if (Files.isDirectory(directory)) extra.add(directory);
        }
        if (extra.isEmpty()) return environment;
        StringBuilder prefix = new StringBuilder();
        for (Path directory : extra) prefix.append(directory).append(java.io.File.pathSeparator);
        environment.put("PATH", prefix + environment.getOrDefault("PATH", ""));
        for (Path directory : extra) {
            Path git = directory.resolve("git.exe");
            if (Files.isRegularFile(git)) { environment.putIfAbsent("GIT", git.toString()); break; }
        }
        environment.putIfAbsent("PYTHONUTF8", "1");
        return environment;
    }

    // ------------------------------------------------------------------ 学参数

    /**
     * 从正在运行的 SD 进程里学一次启动参数并写进 sd.start_args。
     * 用户平时是拿绘世启动器（--xformers --medvram-sdxl …）起的，学到真实参数后自启动就与原样一致。
     * 返回学到的参数（没学到返回空）。
     */
    public static String learnArgsFromRunningSd() {
        for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
            ProcessHandle.Info info;
            try { info = handle.info(); } catch (Exception ignored) { continue; }
            Optional<String> commandLine;
            try { commandLine = info.commandLine(); } catch (Exception ignored) { continue; }
            if (commandLine.isEmpty()) continue;
            String parsed = launchArgsOf(commandLine.get());
            if (!parsed.isEmpty()) return parsed;
        }
        return "";
    }

    /** 从一条命令行里取出 launch.py 之后的启动参数（"python launch.py --api --xformers" → "--api --xformers"）。 */
    public static String launchArgsOf(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) return "";
        List<String> parts = splitArgs(commandLine);
        int at = -1;
        for (int index = 0; index < parts.size(); index++) {
            String part = parts.get(index).replace('\\', '/').toLowerCase(Locale.ROOT);
            if (part.equals("launch.py") || part.endsWith("/launch.py")) { at = index; break; }
        }
        if (at < 0 || at + 1 >= parts.size()) return "";
        List<String> args = new ArrayList<>();
        for (int index = at + 1; index < parts.size(); index++) {
            String arg = parts.get(index);
            // 只保留以 - 开头的参数：命令行里可能还夹着 python 路径之类的东西。
            if (arg.startsWith("-")) args.add(arg);
        }
        return String.join(" ", args);
    }

    /** 拆命令行：支持引号包裹的路径。 */
    public static List<String> splitArgs(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char character : text.toCharArray()) {
            if (character == '"') { quoted = !quoted; continue; }
            if (!quoted && Character.isWhitespace(character)) {
                if (current.length() > 0) { result.add(current.toString()); current.setLength(0); }
                continue;
            }
            current.append(character);
        }
        if (current.length() > 0) result.add(current.toString());
        return result;
    }

    // ------------------------------------------------------------------ 日志文件

    /** 追加一行到 SD 自启动日志（供设置变更等少量事件记录）。 */
    void note(String text) {
        Path target = logTarget();
        if (target == null) return;
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, "[" + java.time.LocalDateTime.now() + "] " + text + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { /* 日志写不了不影响启动 */ }
    }
}

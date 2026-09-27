package cn.szu.bot;

import com.google.gson.*;
import cn.szu.bot.sd.SdLauncher;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SD WebUI 自启动：启动入口挑选、参数保证带 --api、探活与等待、节流与并发保护、超时提示，
 * 以及"从正在运行的 SD 进程学启动参数"的命令行解析。
 * 测试里注入假的探活与假的启动器，不会真的拉起任何进程。
 */
public final class SdAutoStartTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        argsAndCommand();
        environment();
        launching();
        System.out.println("SdAutoStartTest: " + checks + " checks passed: launcher entry, args, wait-for-ready, throttle, timeout, learned args");
    }

    private static Path caseRoot() throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "sd-autostart").toAbsolutePath();
        Files.createDirectories(work);
        return Files.createTempDirectory(work, "case-");
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void argsAndCommand() throws Exception {
        Path root = caseRoot();
        Files.createDirectories(root.resolve("python"));
        Files.writeString(root.resolve("python/python.exe"), "stub");
        Files.writeString(root.resolve("launch.py"), "stub");

        // 1) 从正在运行的 SD 命令行里学参数（绘世启动器那种）。
        String learned = SdLauncher.launchArgsOf(
                "\"F:/sd/sd-webui\\python\\python.exe\" F:/sd/sd-webui\\launch.py --medvram-sdxl --xformers --api --autolaunch");
        check(learned.equals("--medvram-sdxl --xformers --api --autolaunch"), "learn args after launch.py: " + learned);
        check(SdLauncher.launchArgsOf("python something_else.py --api").isEmpty(), "ignore command lines that are not launch.py");
        check(SdLauncher.splitArgs("--a \"b c\" --d").equals(List.of("--a", "b c", "--d")), "split quoted command line");

        // 2) 默认参数必须带 --api；配置了参数就用配置的，缺 --api 也要补上。
        JsonObject config = new JsonObject();
        SdLauncher launcher = new SdLauncher(root, () -> config, () -> false);
        check(launcher.startArgs().contains("--api"), "default args contain --api: " + launcher.startArgs());
        check(launcher.available(), "python + launch.py is a usable launcher entry");
        check(launcher.enabled(), "auto start is on by default");
        String command = String.join(" ", launcher.command());
        check(command.contains("launch.py") && command.contains("--api"), "launch command: " + command);
        config.addProperty("start_args", "--xformers --medvram-sdxl");
        check(launcher.startArgs().equals(List.of("--xformers", "--medvram-sdxl", "--api")), "configured args are used and --api is added: " + launcher.startArgs());

        // 3) 显式命令优先；.bat 走 cmd /c，同样补 --api。
        config.addProperty("start_command", "webui-user.bat --xformers");
        List<String> custom = launcher.command();
        check(custom.get(0).equalsIgnoreCase("cmd.exe") && custom.contains("webui-user.bat") && custom.contains("--api"),
                "explicit .bat command runs through cmd /c and adds --api: " + custom);
        config.addProperty("start_command", "");
        // 绘世整合包那种布局：自带 python + launch.py 优先（webui-user.bat 会找系统 python，实测失败）。
        check(launcher.launcherScript().getFileName().toString().equalsIgnoreCase("python.exe"), "prefer bundled python + launch.py");
        Files.writeString(root.resolve("webui-user.bat"), "@echo off");
        check(launcher.launcherScript().getFileName().toString().equalsIgnoreCase("python.exe"), "bundled python wins over webui-user.bat");
        Files.deleteIfExists(root.resolve("python/python.exe"));
        check(launcher.launcherScript().getFileName().toString().equals("webui-user.bat"), "fall back to webui-user.bat when no bundled python");
        Files.deleteIfExists(root.resolve("webui-user.bat"));
        deleteTree(root);
    }

    /** 整合包自带的 git/python 要补进 PATH，否则 A1111 的 prepare_environment() 找不到 git 会直接失败。 */
    private static void environment() throws Exception {
        Path root = caseRoot();
        Files.createDirectories(root.resolve("git/cmd"));
        Files.createDirectories(root.resolve("python"));
        Files.writeString(root.resolve("git/cmd/git.exe"), "stub");
        Files.writeString(root.resolve("python/python.exe"), "stub");
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "C:\\Windows");
        Map<String, String> prepared = SdLauncher.prepareEnvironment(root, env);
        String path = prepared.get("PATH");
        check(path.contains(root.resolve("git/cmd").toString()) && path.contains(root.resolve("python").toString()),
                "bundled git/python are prepended to PATH: " + path);
        check(path.endsWith("C:\\Windows"), "the inherited PATH is kept: " + path);
        check(prepared.get("GIT").endsWith("git.exe"), "GIT points at the bundled git: " + prepared.get("GIT"));
        Map<String, String> untouched = SdLauncher.prepareEnvironment(null, env);
        check(untouched == env, "no work dir: environment is untouched");
        deleteTree(root);
    }

    private static void launching() throws Exception {
        Path root = caseRoot();
        Files.createDirectories(root.resolve("python"));
        Files.writeString(root.resolve("python/python.exe"), "stub");
        Files.writeString(root.resolve("launch.py"), "stub");
        JsonObject config = new JsonObject();
        config.addProperty("start_timeout_seconds", 3);
        java.time.Duration gap = java.time.Duration.ofMillis(20);
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger probes = new AtomicInteger();
        // 前两次探活"没起来"，之后就算就绪（模拟 SD 启动）。
        SdLauncher launcher = new SdLauncher(root, () -> config, () -> probes.incrementAndGet() > 2,
                (command, workDir, logFile) -> { launches.incrementAndGet(); return null; }, System::currentTimeMillis, gap);

        // 已经就绪：什么都不做。
        SdLauncher running = new SdLauncher(root, () -> config, () -> true,
                (command, workDir, logFile) -> { launches.incrementAndGet(); return null; }, System::currentTimeMillis, gap);
        check(running.ensureRunning().isEmpty(), "already running: no start, no noise");
        check(launches.get() == 0, "already running: nothing was launched");

        // 没在跑 → 启动一次并等到就绪。
        String notice = launcher.ensureRunning();
        check(launches.get() == 1, "started exactly once: " + launches.get());
        check(notice.contains("已自动启动并等到就绪"), "success notice carries elapsed time: " + notice);
        check(launcher.describe().contains("已自动启动"), "status records the last attempt: " + launcher.describe());

        // 节流：间隔内不再拉起（用会走的假时钟确认）。
        AtomicLong clock = new AtomicLong(1_000_000);
        SdLauncher throttled = new SdLauncher(root, () -> config, () -> false,
                (command, workDir, logFile) -> { launches.incrementAndGet(); return null; }, clock::get, gap);
        String first = throttled.ensureRunning();
        check(first.contains("仍连不上"), "first attempt runs into the timeout: " + first);
        check(first.contains("sd-autostart.log"), "timeout notice points at the start log: " + first);
        int after = launches.get();
        clock.addAndGet(10_000);
        check(throttled.ensureRunning().isEmpty() && throttled.describe().contains("刚启动过"), "inside the interval it only reports");
        check(launches.get() == after, "inside the interval nothing new was launched: " + launches.get());
        clock.addAndGet(300_000);
        check(throttled.ensureRunning().contains("仍连不上"), "after the interval it may try again");

        // 关掉自动启动：连不上也不动。
        config.addProperty("auto_start", false);
        SdLauncher disabled = new SdLauncher(root, () -> config, () -> false,
                (command, workDir, logFile) -> { launches.incrementAndGet(); return null; }, System::currentTimeMillis, gap);
        int before = launches.get();
        check(disabled.ensureRunning().isEmpty(), "auto_start off: automatic start does nothing");
        check(launches.get() == before, "auto_start off: nothing was launched");
        check(disabled.startNow().contains("仍连不上") || disabled.startNow().contains("SD 已经在运行"), "manual start ignores the auto_start switch");
        config.addProperty("auto_start", true);

        // 没配置启动入口：不启动，并说明去哪里配。
        Path empty = caseRoot();
        SdLauncher unconfigured = new SdLauncher(empty, () -> config, () -> false,
                (command, workDir, logFile) -> { launches.incrementAndGet(); return null; }, System::currentTimeMillis, gap);
        int beforeUnconfigured = launches.get();
        check(unconfigured.ensureRunning().isEmpty(), "no launcher: automatic start stays quiet");
        check(!unconfigured.available() && unconfigured.describe().contains("没找到"), "status explains the missing launcher: " + unconfigured.describe());
        check(launches.get() == beforeUnconfigured, "no launcher: nothing was launched");
        check(unconfigured.startNow().contains("没找到 SD 启动入口"), "manual start explains what to configure");

        // 启动命令本身就失败（例如 python 路径不对）：把原因原样报出来。
        SdLauncher broken = new SdLauncher(root, () -> config, () -> false,
                (command, workDir, logFile) -> { throw new java.io.IOException("python missing"); }, System::currentTimeMillis, gap);
        String brokenNotice = broken.ensureRunning();
        check(brokenNotice.contains("尝试自动启动失败") && brokenNotice.contains("python missing"), "launch failure reports the reason: " + brokenNotice);

        deleteTree(root);
        deleteTree(empty);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}

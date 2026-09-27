package cn.szu.bot;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Console-first diagnostics. Every line is printed to the running console (the window started by run.bat)
 * and mirrored to files under logs/ when a bot home directory is known:
 *
 * <ul>
 *   <li><code>logs/bot-YYYYMMDD.log</code> — 全部事件（网页面板的"全部"就是它）；</li>
 *   <li><code>logs/qq-YYYYMMDD.log</code> — QQ 侧（NapCat 收发、指令、生成、异常）；</li>
 *   <li><code>logs/web-YYYYMMDD.log</code> — 网页侧（HTTP 请求、网页指令与回执）。</li>
 * </ul>
 *
 * 哪一侧由调用线程的标记决定（{@link #markWeb()}）：网页 HTTP 线程与网页指令线程都打网页标记，
 * 其余（QQ 事件线程、生成线程、启动流程）算 QQ 侧。两边都能通过网页日志面板查看。
 *
 * Tests never call init, so they only see console output. Credentials are never passed here by callers.
 */
public final class Log {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Object LOCK = new Object();
    private static volatile Path directory;
    private static volatile Path allFile;
    private static final ThreadLocal<Boolean> WEB_SIDE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Log() {}

    /** Enables file mirroring; call once at startup with the bot home directory. */
    public static void init(Path root) {
        if (root == null) return;
        try {
            Path target = root.toAbsolutePath().normalize().resolve("logs");
            Files.createDirectories(target);
            directory = target;
            allFile = target.resolve("bot-" + LocalDateTime.now().format(DAY) + ".log");
        } catch (Exception e) {
            directory = null;
            allFile = null;
        }
    }

    /** 当前线程算网页侧：HTTP 线程与网页指令线程用，日志会额外写进 web-*.log。 */
    public static void markWeb() { WEB_SIDE.set(Boolean.TRUE); }
    /** 清掉网页标记（线程池复用线程时必须复位）。 */
    public static void clearWeb() { WEB_SIDE.remove(); }
    /** 这一段算网页侧，结束后自动复位；返回前一次的状态，便于嵌套恢复。 */
    public static boolean webSide() { return Boolean.TRUE.equals(WEB_SIDE.get()); }

    /** Optional sink for WARN/ERROR lines: the bot mirrors those into the main group when asked to. */
    private static volatile java.util.function.BiConsumer<String, String> mirror;

    public static void mirror(java.util.function.BiConsumer<String, String> sink) { mirror = sink; }

    public static void info(String message) { write("INFO", message, System.out); }
    /** Full text (no 200-character cap): used for model thinking output, which must stay readable. */
    public static void raw(String message) { writeRaw("THINK", message, System.out); }
    public static void warn(String message) { write("WARN", message, System.err); }
    /**
     * WARN with the top stack frames inlined: a swallowed exception must still show where it came from,
     * and the log file stays one line per event.
     */
    public static void warn(String message, Throwable error) {
        StringBuilder frames = new StringBuilder();
        StackTraceElement[] trace = error == null ? new StackTraceElement[0] : error.getStackTrace();
        for (int index = 0; index < trace.length && index < 3; index++) frames.append(index == 0 ? " @" : " <-").append(trace[index]);
        write("WARN", message + frames, System.err);
    }
    public static void error(String message) { write("ERROR", message, System.err); }
    public static void error(String message, Throwable error) {
        write("ERROR", error == null ? message : message + " | " + Bot.error(error), System.err);
    }

    /** Bounded single-line rendering for message bodies, ids and free text. */
    public static String text(String value) {
        if (value == null) return "";
        String one = value.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]+", " ").replaceAll("\\s+", " ").strip();
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }

    private static void write(String level, String message, PrintStream console) {
        String line = "[" + LocalDateTime.now().format(STAMP) + "] [" + level + "] " + text(message);
        console.println(line);
        append(line);
        java.util.function.BiConsumer<String, String> sink = mirror;
        if (sink != null && ("WARN".equals(level) || "ERROR".equals(level))) {
            try { sink.accept(level, text(message)); } catch (RuntimeException ignored) { }
        }
    }

    /** Same as write, but the body is kept whole (multi-line thinking is easier to read that way). */
    private static void writeRaw(String level, String message, PrintStream console) {
        String stamp = "[" + LocalDateTime.now().format(STAMP) + "] [" + level + "] ";
        String body = message == null ? "" : message;
        console.println(stamp + body);
        append(stamp + body);
    }

    /** 同时写"全部"与当前侧（qq/web）两个文件；磁盘问题绝不能影响消息处理。 */
    private static void append(String line) {
        Path base = directory;
        if (base == null) return;
        String day = LocalDateTime.now().format(DAY);
        Path side = base.resolve((webSide() ? "web-" : "qq-") + day + ".log");
        synchronized (LOCK) {
            writeFile(allFile, line);
            writeFile(side, line);
        }
    }

    private static void writeFile(Path target, String line) {
        if (target == null) return;
        try {
            Files.writeString(target, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // Console output already happened; a log file problem must never break message handling.
        }
    }
}

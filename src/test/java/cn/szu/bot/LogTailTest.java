package cn.szu.bot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「全部日志」文件挑选：{@link Log#newestDailyFile} 的回退规则。
 *
 * <p>为什么专门测它：网页的「全部日志」读 {@code logs/bot-<今天>.log}，而机器人在零点之前启动时，
 * 当天的文件要等它写下第一行才出现；跨零点那段时间面板就会是空白。这类"只在零点前后出现"的毛病
 * 手测基本抓不到，所以把挑选规则单独钉住（真写几个文件、真的去挑）。
 */
public final class LogTailTest {

    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "log-tail").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        Path logs = root.resolve("logs");
        Files.createDirectories(logs);

        equal(null, Log.newestDailyFile(logs, "bot-", null), "空目录：老实给 null（不当成有日志）");
        equal(null, Log.newestDailyFile(root.resolve("并不存在"), "bot-", null), "目录不存在：给 null");

        Path old = write(logs, "bot-20200101.log", "老日志哨兵\n");
        equal(old, Log.newestDailyFile(logs, "bot-", null), "只有一份旧日志：就选它（跨零点窗口的回退）");

        Path newer = write(logs, "bot-20200102.log", "新一点的哨兵\n");
        equal(newer, Log.newestDailyFile(logs, "bot-", null), "多份时取文件名日期最大的那份");

        // 其它前缀、以及启动脚本留下的 .stdout/.stderr 都不能被当成日志
        write(logs, "qq-20200103.log", "qq 的日志\n");
        write(logs, "web-20200104.log", "web 的日志\n");
        write(logs, "bot-20200105.stdout.log", "启动输出\n");
        write(logs, "bot-20200106.stderr.log", "启动错误\n");
        equal(newer, Log.newestDailyFile(logs, "bot-", null), "只认 bot-*.log（qq-/web-/stdout/stderr 都不算）");

        Path newestQq = write(logs, "qq-20200107.log", "更新的 qq\n");
        equal(newestQq, Log.newestDailyFile(logs, "qq-", null), "前缀是可配的：qq- 就只找 qq-");

        Path today = write(logs, "bot-29991231.log", "今天\n");
        equal(today, Log.newestDailyFile(logs, "bot-", null), "有更晚的日期就用更晚的");

        // 真正要防的那条：今天已经有了，挑选必须以「今天」为准，而不是回退到某份旧文件
        equal(today, Log.newestDailyFile(logs, "bot-", old), "今天存在时就用今天（回退只在缺当天文件时生效）");

        // 只有旧文件时的回退，给出的必须就是那份旧文件（内容也对）
        Files.deleteIfExists(today);
        Path picked = Log.newestDailyFile(logs, "bot-", logs.resolve("bot-39991231.log"));
        equal(newer, picked, "缺当天文件：回退到最近一份");
        check(Files.readString(picked, StandardCharsets.UTF_8).contains("新一点的哨兵"), "回退到的那份内容是对的");

        System.out.println("LogTailTest: " + checks + " assertions passed：空目录/缺目录、只有旧日志、取日期最大、"
                + "前缀过滤（qq-/web-/stdout/stderr 不算）、有当天就用当天、缺当天回退到最近一份。");
    }

    private static Path write(Path dir, String name, String body) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }

    private static void equal(Object expected, Object actual, String detail) {
        checks++;
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) throw new AssertionError(detail + "：期望 " + expected + "，实际 " + actual);
    }
}

package cn.szu.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 测试临时目录的收尾工具：先等后台写干净，再带重试地删除。
 *
 * <p>为什么需要它：{@code Bot.close()} 只是把出图队列、自动领取、提示词改写、LoRA 这些后台执行器
 * {@code shutdownNow()}，既不等待线程退出，也不保证此刻没有写了一半的文件。测试如果在 close() 之后
 * 立刻 {@code Files.walk(root).delete} 自己的 case- 目录，就会和"还在往这个目录写文件"的后台线程
 * 抢同一棵目录树：删除抛 {@link java.nio.file.DirectoryNotEmptyException}（实测
 * {@code DeepSeekChannelsTest} 里 {@code .gen 1} 之后的收尾正是如此——生成任务在测试已经判定通过
 * 之后才把图片和待领取队列落盘），于是"清理没成功"被当成"用例失败"，同一份代码在机器有负载时
 * 偶发挂套件。
 *
 * <p>{@link #awaitQuiet} 等目录树连续多次快照一致（说明没有线程还在写），{@link #deleteQuietly}
 * 每次重试都重新遍历整棵树（新文件随时可能出现），最后一次仍失败只在 stderr 打一行警告：
 * 清理不成功不应该把用例判死，用例的断言本身一个字都不放宽。
 */
public final class TestCleanup {
    private TestCleanup() {}

    /** 等目录树安静下来：默认连续 3 次快照一致、每次间隔 50ms。 */
    public static void awaitQuiet(Path root, long millis) { awaitQuiet(root, millis, 3, 50); }

    /**
     * @param stableSamples  需要连续多少次快照完全一致才算"没人再写了"
     * @param intervalMillis 两次快照之间的间隔
     */
    public static void awaitQuiet(Path root, long millis, int stableSamples, long intervalMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
        List<String> previous = null;
        int stable = 0;
        while (System.nanoTime() < deadline) {
            List<String> snapshot = snapshot(root);
            if (previous != null && snapshot.equals(previous)) {
                if (++stable >= Math.max(1, stableSamples)) return;
            } else {
                stable = 0;
            }
            previous = snapshot;
            try {
                Thread.sleep(Math.max(1, intervalMillis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 带重试的删除：默认最多 5 次，每次间隔 200ms。 */
    public static void deleteQuietly(Path root) { deleteQuietly(root, 5, 200); }

    public static void deleteQuietly(Path root, int attempts, long backoffMillis) {
        IOException last = null;
        int tries = Math.max(1, attempts);
        for (int attempt = 1; attempt <= tries; attempt++) {
            last = deleteOnce(root);
            if (last == null) return;
            // 后台线程可能只是慢了半拍：等它把这一笔写完再重来一次。
            awaitQuiet(root, Math.max(1, backoffMillis), 2, Math.max(1, backoffMillis));
        }
        System.err.println("警告：测试临时目录清理失败（重试 " + tries + " 次后仍被后台线程写入或占用），"
                + "已忽略以免把清理失败误判成用例失败：" + root + "：" + last);
    }

    /** 一次完整删除；目录已经不在算成功，仍有残留（或又被写进来）时返回失败原因。 */
    private static IOException deleteOnce(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return null;
        IOException failure = null;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    failure = error;
                }
            }
        } catch (IOException error) {
            failure = error;
        } catch (RuntimeException error) {
            failure = new IOException("遍历测试临时目录失败", error);
        }
        if (failure == null && Files.exists(root, LinkOption.NOFOLLOW_LINKS)) failure = new IOException("目录未被删除：" + root);
        return failure;
    }

    /** 目录树快照（相对路径 + 大小 + 修改时间）：只要有线程还在写，连续两次快照就会不一样。 */
    private static List<String> snapshot(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<String> entries = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.forEach(path -> {
                String detail;
                try {
                    detail = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            ? "dir:" + Files.getLastModifiedTime(path).toMillis()
                            : "file:" + Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis();
                } catch (IOException vanished) {
                    detail = "gone";
                }
                entries.add(root.relativize(path) + "=" + detail);
            });
        } catch (IOException | RuntimeException error) {
            entries.add("<遍历失败>=" + error);
        }
        entries.sort(Comparator.naturalOrder());
        return entries;
    }
}

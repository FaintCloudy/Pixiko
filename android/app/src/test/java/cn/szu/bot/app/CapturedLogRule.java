package cn.szu.bot.app;

import org.junit.rules.ExternalResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试规则：把 {@link Log} 的出口换成一个内存记录器，测试结束自动还原。
 *
 * <p>两个作用：
 * <ol>
 *   <li><b>让单测跑得下去</b>：不换出口的话，任何走到 {@code Log.d/i/w} 的代码都会抛
 *       {@code RuntimeException: Method d in android.util.Log not mocked}
 *       （AGP 给单测挂的 android.jar 是 stub），断言行都到不了；</li>
 *   <li><b>让日志可以被断言</b>：例如「下载/检查更新的日志里不许出现令牌」，
 *       这是一条真的安全回归测试，不是摆设。</li>
 * </ol>
 *
 * <p>用法：{@code @Rule public final CapturedLogRule logs = new CapturedLogRule();}
 */
public class CapturedLogRule extends ExternalResource {

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());

    /**
     * 不依赖 JUnit 规则顺序的安装方式：在 {@code @Before} 里直接调它。
     *
     * <p>为什么还要这个：规则（{@code @Rule}）与 {@code @Before} 的执行顺序是「规则先」，
     * 但为了不让人猜，测试里显式调一次更清楚（重复安装没有副作用）。
     */
    public void install() {
        before();
    }

    @Override
    protected void before() {
        lines.clear();
        warnings.clear();
        Log.setSink((priority, tag, message) -> {
            String line = priority + " " + tag + " " + message;
            lines.add(line);
            if (priority >= android.util.Log.WARN) warnings.add(line);
        });
    }

    @Override
    protected void after() {
        Log.setSink(null);
    }

    /** 全部日志行（形如 {@code 4 PixikoApp 发现新版本：…}）。 */
    public List<String> lines() {
        synchronized (lines) {
            return new ArrayList<>(lines);
        }
    }

    public List<String> warnings() {
        synchronized (warnings) {
            return new ArrayList<>(warnings);
        }
    }

    /** 有没有哪一行包含这段文本（排查用）。 */
    public boolean anyContains(String needle) {
        for (String line : lines()) {
            if (line.contains(needle)) return true;
        }
        return false;
    }

    /** 把日志打到测试输出里，方便报告里贴证据。 */
    public void dump(String label) {
        System.out.println("[CapturedLog] ==== " + label + "（共 " + lines().size() + " 行）");
        for (String line : lines()) System.out.println("[CapturedLog] " + line);
    }
}

package cn.szu.bot;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 生产 / 测试互斥守卫（用户第 14 节第 11 条，契约见 {@code docs/TEST-ISOLATION.md}）。
 *
 * <p>两件事，都必须在**读生产 config.json 之前**判定：
 * <ol>
 *   <li><b>生产不得冒充测试</b>：命令行里带了 {@code -Dbot.test.mode} / {@code -Dbot.test.home} /
 *       {@code -Dbot.test.run.id} 的 JVM 一定是从测试脚手架里起来的，它绝不许加载生产配置——
 *       否则"跑测试"会顺手把线上实例的配置读进来、甚至写回去。</li>
 *   <li><b>测试目录不得当生产用</b>：临时根里有 {@value #MARKER} 标记时同样拒绝启动，
 *       免得在 {@code work\tests\<run-id>} 里误起一个"生产"实例。</li>
 * </ol>
 *
 * <p>正常启动（{@code run.bat} / {@code start.bat}，不带任何 {@code -D} 参数、根目录是仓库根）时
 * 两个条件都不成立，这里是个纯粹的空操作，生产行为一个字都不变。
 */
public final class TestModeGuard {

    /** 测试根目录里的标记文件；生产启动见到它就拒绝启动。 */
    public static final String MARKER = ".pixiko-test-root";

    private TestModeGuard() {}

    /** 当前 JVM 是不是测试模式（{@code -Dbot.test.mode=true}）。 */
    public static boolean testMode() {
        return Boolean.getBoolean("bot.test.mode");
    }

    /** 当前 JVM 有没有带测试隔离参数（任一个非空即算）。 */
    public static boolean hasTestIsolationArguments() {
        return testMode()
                || !System.getProperty("bot.test.home", "").strip().isEmpty()
                || !System.getProperty("bot.test.run.id", "").strip().isEmpty();
    }

    /**
     * 在生产启动路径的最前面调用（必须在 {@link Settings} 与首次配置生成之前）。
     *
     * @throws IllegalStateException 带测试参数，或根目录是测试临时根
     */
    public static void refuseProductionStartup(Path root) {
        String home = System.getProperty("bot.test.home", "").strip();
        String runId = System.getProperty("bot.test.run.id", "").strip();
        if (hasTestIsolationArguments()) {
            throw new IllegalStateException("拒绝以生产模式启动：当前 JVM 带测试隔离参数（bot.test.mode="
                    + testMode() + "，bot.test.home=" + home + "，bot.test.run.id=" + runId + "）。"
                    + "测试请用 build.ps1 -Test；生产请不带这些 -D 参数启动。");
        }
        if (root != null && Files.exists(root.resolve(MARKER))) {
            throw new IllegalStateException("拒绝以生产模式启动：" + root.toAbsolutePath().normalize()
                    + " 是测试临时根目录（存在 " + MARKER + " 标记）。生产请用仓库根目录启动。");
        }
    }
}

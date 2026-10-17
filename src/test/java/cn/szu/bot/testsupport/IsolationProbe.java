package cn.szu.bot.testsupport;

import cn.szu.bot.Json;
import cn.szu.bot.sd.SdClient;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 独立子进程探针：给 {@code IsolationTest} 的 (c) 并行、(d) 异常退出两条用例当"第二套/第三套测试"。
 *
 * <p>它自己也是一次完整的隔离 run：{@link TestEnvironment#createAt} 建临时根、{@link TestRuntime} 起
 * 四个假服务（全绑 {@code 127.0.0.1} 动态端口）。三种模式：
 * <ul>
 *   <li>{@code ok <home>}：正常跑完 → 打印端口 → {@code close()}（目录会被删掉）→ exit 0。</li>
 *   <li>{@code crash <home> <portFile>}：同 {@code ok}，但写出假 SD 端口后 {@code Runtime.halt(9)}，
 *       既不关服务也不删目录——模拟测试异常退出，用来验证"不遗留监听端口与子进程"。</li>
 * </ul>
 *
 * <p>注意：文件名不以 {@code Test} 结尾，也不在 {@code cn.szu.bot} 包下，{@code build.ps1 -Test}
 * 不会把它当成一个测试类跑；只有父测试会显式拉起它。
 */
public final class IsolationProbe {

    private IsolationProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：IsolationProbe ok|crash <run 根> [端口文件]");
            System.exit(2);
            return;
        }
        String mode = args[0];
        Path home = Path.of(args[1]).toAbsolutePath().normalize();
        TestEnvironment environment = TestEnvironment.createAt(home);
        TestRuntime runtime = new TestRuntime(environment);
        if ("crash".equals(mode)) {
            Path portFile = Path.of(args[2]);
            Files.writeString(portFile, Integer.toString(runtime.sd().port()), StandardCharsets.UTF_8);
            runtime.printPorts("crash-probe run-id=" + environment.runId());
            System.out.flush();
            System.out.println("PROBE-CRASH sd=" + runtime.sd().port());
            System.out.flush();
            Runtime.getRuntime().halt(9);          // 故意不清理：模拟测试进程被强杀
            return;
        }
        try (environment) {
            try (runtime) {
                JsonObject config = runtime.config();
                SdClient sdClient = new SdClient(environment.home(), Json.obj(config, "sd"));
                String model = sdClient.models().get(0);
                runtime.printPorts("parallel-probe run-id=" + environment.runId());
                System.out.println("PROBE-OK run-id=" + environment.runId()
                        + " home=" + environment.home()
                        + " onebot=" + runtime.oneBot().port()
                        + " sd=" + runtime.sd().port()
                        + " deepseek=" + runtime.deepSeek().port()
                        + " civitai=" + runtime.civitai().port()
                        + " model=" + model);
            }
        }
    }
}

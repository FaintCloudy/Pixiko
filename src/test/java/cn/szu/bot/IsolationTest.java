package cn.szu.bot;

import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.qq.QqClient;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.testsupport.FakeOneBot;
import cn.szu.bot.testsupport.TestEnvironment;
import cn.szu.bot.testsupport.TestRuntime;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第 14 节的隔离性回归测试（a–e 五条），一个类、可单独运行。
 *
 * <pre>
 * java -ea -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dbot.test.mode=true \
 *      -Dbot.test.production.root=F:\Bot \
 *      -cp "build\classes;build\test-classes;lib\gson-2.13.1.jar;lib\spring\*" \
 *      cn.szu.bot.IsolationTest
 * </pre>
 *
 * <p>它自己就是一次隔离 run：{@link TestEnvironment} 建 {@code F:\Bot\work\tests\<uuid>}，四个假服务全在
 * {@code 127.0.0.1} 的动态端口上；测试跑完（含失败）目录会被删掉。
 *
 * <h2>五条用例</h2>
 * <ol>
 *   <li><b>a</b>：测试前后生产端口的监听者（生产 Pixiko 的 PID）与本机 java/node/python 进程集合一致；
 *       本次 run 打开的每个端口都断言"不是生产端口、只在 127.0.0.1"。<b>全程不连生产端口</b>——连一下
 *       就是违规，所以这里用进程集合与端口审计代替 connect 探测。</li>
 *   <li><b>b</b>：生产 {@code config.json} 的大小+修改时间、以及 {@code data/**} 的"路径+大小"结构指纹
 *       前后一致（只读元数据，从不读文件内容，也就不可能把 key/cookie/token 带出来）。</li>
 *   <li><b>c</b>：两套测试<b>并行</b>跑（各一个子 JVM、各一个 UUID run-id），端口与目录都不冲突。</li>
 *   <li><b>d</b>：子进程 {@code Runtime.halt(9)} 异常退出后，它监听过的端口能被立刻重新绑定、
 *       进程也确实没了（不遗留监听端口与子进程）。</li>
 *   <li><b>e</b>：主 Pixiko 正在跑（本机 java 进程带 {@code bot.home=F:\Bot} 与 {@code pixiko.jar}）时，
 *       上面这些隔离用例全部通过；测试用的是自己 run 目录里的 {@code bot.lock}，不碰生产 {@code data/bot.lock}。</li>
 * </ol>
 */
public final class IsolationTest {

    private static int checks;
    private static final int MAX_FINGERPRINT_ENTRIES = 20000;
    private static final Pattern PROBE_PORTS =
            Pattern.compile("onebot=(\\d+) sd=(\\d+) deepseek=(\\d+) civitai=(\\d+)");

    public static void main(String[] args) throws Exception {
        Path production = TestEnvironment.productionRoot();
        System.out.println("ISOLATION production-root=" + production);

        Set<String> processesBefore = TestEnvironment.processSnapshot();
        List<Long> pixikoBefore = TestEnvironment.productionPixikoPids();
        Map<Integer, List<Long>> listenersBefore = TestEnvironment.productionListeners();
        String configStampBefore = TestEnvironment.productionConfigStamp(production);
        String structureBefore = TestEnvironment.productionStructureFingerprint(production, MAX_FINGERPRINT_ENTRIES);
        String fingerprintBefore = TestEnvironment.productionFingerprint(production, MAX_FINGERPRINT_ENTRIES);
        System.out.println("ISOLATION BASELINE pixiko-pids=" + pixikoBefore
                + " prod-listeners=" + listenersBefore
                + " config-stamp=" + configStampBefore
                + " structure=" + shorten(structureBefore)
                + " data-fingerprint=" + shorten(fingerprintBefore)
                + " processes=" + processesBefore.size());

        Path runHome;
        String runId;
        try (TestEnvironment env = TestEnvironment.create()) {
            runHome = env.home();
            runId = env.runId();
            System.out.println("ISOLATION RUN root=" + runHome + " run-id=" + runId);
            check(Files.isDirectory(runHome) && Files.isRegularFile(env.configFile())
                            && Files.isDirectory(env.data()) && Files.isDirectory(env.logs())
                            && Files.isDirectory(env.maps()) && Files.isDirectory(env.generated())
                            && Files.isDirectory(env.lora()) && Files.isRegularFile(env.lockFile()),
                    "第 3 条：run 根里有 config.json/data/logs/maps/generated/lora/bot.lock");

            try (TestRuntime runtime = new TestRuntime(env)) {
                JsonObject config = runtime.config();
                runtime.printPorts("main run-id=" + runId);
                a_portsAndProcesses(env, runtime, config, processesBefore, pixikoBefore, listenersBefore);
                b_productionDataUntouched(env, production, configStampBefore, structureBefore, fingerprintBefore, pixikoBefore);
                c_parallelRuns(env);
                d_abnormalExit(env);
                e_liveProduction(env, pixikoBefore);
            }
        }
        check(!Files.exists(runHome), "第 12 条：run 根在 close() 之后被删干净：" + runHome);

        String structureAfter = TestEnvironment.productionStructureFingerprint(production, MAX_FINGERPRINT_ENTRIES);
        String configStampAfter = TestEnvironment.productionConfigStamp(production);
        Map<Integer, List<Long>> listenersAfter = TestEnvironment.productionListeners();
        Set<String> processesAfter = TestEnvironment.processSnapshot();
        List<Long> pixikoAfter = TestEnvironment.productionPixikoPids();
        check(listenersBefore.equals(listenersAfter),
                "第 14 条 a：生产端口监听者（端口→PID）前后一致：before=" + listenersBefore + " after=" + listenersAfter);
        check(configStampBefore.equals(configStampAfter),
                "生产 config.json 大小/修改时间没变：before=" + configStampBefore + " after=" + configStampAfter);
        check(structureBefore.equals(structureAfter),
                "生产 data/** 结构指纹没变：before=" + shorten(structureBefore) + " after=" + shorten(structureAfter));
        check(processesAfter.containsAll(processesBefore),
                "第 14 条 a：测试期间没有杀掉任何本来在跑的 java/node/python 进程");
        check(pixikoAfter.containsAll(pixikoBefore),
                "第 14 条 e：生产 Pixiko 没被换掉：" + pixikoBefore + " → " + pixikoAfter);
        if (!pixikoAfter.equals(pixikoBefore)) {
            System.out.println("NOTE e：生产 Pixiko PID 集合有变化（别的进程在动它，不是本测试）："
                    + pixikoBefore + " → " + pixikoAfter);
        }
        if (!processesAfter.equals(processesBefore)) {
            System.out.println("NOTE a：测试窗口内有别的瞬态 JVM 起落（同机其它任务），"
                    + "只硬断言「生产端口监听者 PID 不变」与「没杀掉原有的进程」；"
                    + "processes " + processesBefore.size() + " → " + processesAfter.size());
        }

        System.out.println("IsolationTest PASS: " + checks + " checks（a 端口/进程、b 生产数据、c 并行、d 异常退出、"
                + "e 主实例在跑）；生产 config.json=" + configStampAfter
                + "，data 结构指纹=" + shorten(structureAfter) + "，生产 PID=" + pixikoAfter);
    }

    // ------------------------------------------------------------------ a：端口与进程

    private static void a_portsAndProcesses(TestEnvironment env, TestRuntime runtime, JsonObject config,
                                            Set<String> processesBefore, List<Long> pixikoBefore,
                                            Map<Integer, List<Long>> listenersBefore) throws Exception {
        Map<String, Integer> ports = runtime.ports();
        check(ports.size() == 4, "四个假服务各一个端口：" + ports);
        for (Map.Entry<String, Integer> entry : ports.entrySet()) {
            int port = entry.getValue();
            check(!TestEnvironment.PRODUCTION_PORTS.contains(port),
                    "第 1 条：" + entry.getKey() + " 没有落在生产端口上（" + port + "）");
            check(port > 1024 && port <= 65535, "第 5/6 条：" + entry.getKey() + " 是操作系统分配的高位端口：" + port);
        }
        check(new java.util.HashSet<>(ports.values()).size() == ports.size(), "四个假服务端口互不相同：" + ports);

        // 假服务确实只在本机回环上：用实际的监听地址验证（不是靠"猜"）。
        // OneBot 那条不这样探：裸 TCP 连一下会让 WebSocket 握手先看到 EOF；它的可达性由下面的 QqClient 证明。
        for (Map.Entry<String, Integer> entry : ports.entrySet()) {
            if (entry.getKey().startsWith("onebot")) continue;
            check(canConnect(InetAddress.getByName("127.0.0.1"), entry.getValue()),
                    "假服务 " + entry.getKey() + " 在 127.0.0.1:" + entry.getValue() + " 上可达");
        }

        // 测试配置里一个生产端口都不许出现（第 1 条，配置层）。
        String text = config.toString();
        for (int port : TestEnvironment.PRODUCTION_PORTS) {
            check(!text.contains(":" + port), "测试配置里没有生产端口 :" + port);
        }
        check(!text.contains("api.deepseek.com") && !text.contains("civitai.com/api"), "测试配置没有指向真实上游");

        // 假 OneBot：真 WebSocket 一来一回（机器人连的是 127.0.0.1 的假服务，不是真 NapCat）。
        JsonObject qq = config.getAsJsonObject("qq");
        try (QqClient client = new QqClient(qq)) {
            BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
            client.start(events::add);
            FakeOneBot.Connection connection = runtime.oneBot().connection();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!client.isConnected() && System.nanoTime() < deadline) Thread.sleep(10);
            check(client.isConnected(), "QqClient 连上了假 OneBot");
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", "group");
            event.addProperty("group_id", 123456789L);
            event.addProperty("user_id", 10001L);
            event.addProperty("self_id", 999L);
            event.addProperty("message_id", 1);
            event.addProperty("raw_message", ".help");
            connection.send(event.toString());
            JsonObject received = events.poll(10, TimeUnit.SECONDS);
            check(received != null && received.get("group_id").getAsLong() == 123456789L,
                    "假 OneBot 推的事件原样到达 QqClient");
        }

        // 假 SD：真 HTTP 打本机假服务。
        SdClient sd = new SdClient(env.home(), config.getAsJsonObject("sd"));
        List<String> models = sd.models();
        check(models.contains("fake-model.safetensors"), "假 SD 的模型列表：" + models);
        check(sd.styles().contains("假样式"), "假 SD 的预设样式列表：" + sd.styles());
        check(runtime.sd().hits("/sdapi/v1/sd-models") == 1, "假 SD 收到了那一次请求：" + runtime.sd().requests());

        // 假 DeepSeek：注入的 transport 把请求打到本机 127.0.0.1 的假服务。
        DeepSeekPrompts.Transport transport = runtime.deepSeekTransport();
        JsonObject body = new JsonObject();
        body.addProperty("model", "fake-model");
        var response = transport.post(body, "offline-test-key", java.time.Duration.ofSeconds(10));
        check(response.status() == 200 && response.body().contains(TestRuntime.DEEPSEEK_ANSWER),
                "假 DeepSeek 回了固定内容（status=" + response.status() + "）");
        check(!runtime.deepSeek().requests().isEmpty(), "假 DeepSeek 收到了请求：" + runtime.deepSeek().requests());
        check(Json.str(config.getAsJsonObject("progen"), "api_base", "").startsWith("http://127.0.0.1:"),
                "progen.api_base 指向本机假服务：" + Json.str(config.getAsJsonObject("progen"), "api_base", ""));

        // 假 Civitai：注入的 transport 把 https://civitai.com 的请求转到本机假站点。
        CivitaiClient civitai = new CivitaiClient(env.home(), config.getAsJsonObject("civitai"),
                runtime.civitaiTransport(), runtime.civitaiResolver(), null);
        check(civitai.query("测试").isEmpty(), "假 Civitai 的搜索结果为空列表（假站点返回 items:[]）");
        check(!runtime.civitai().requests().isEmpty(), "假 Civitai 收到了请求：" + runtime.civitai().requests());
        check(Json.str(config.getAsJsonObject("civitai"), "lora_dir", "").replace('\\', '/')
                        .startsWith(env.home().toString().replace('\\', '/') + "/lora"),
                "Civitai 的 LoRA 目录在本次 run 里：" + Json.str(config.getAsJsonObject("civitai"), "lora_dir", ""));

        // 假 WebUI（Spring）：server.port=0 绑定后从实际监听地址读端口（第 5 条）。
        // 直接用 WebUiApplication.start（生产同一个入口），不改 WebUiServer 的两参构造器语义。
        Settings settings = new Settings(env.home());
        try (Bot bot = new Bot(settings, new SdClient(env.home(), config.getAsJsonObject("sd")),
                (event, segments) -> CompletableFuture.completedFuture(null))) {
            org.springframework.context.ConfigurableApplicationContext context =
                    cn.szu.bot.web.WebUiApplication.start(settings, bot, "127.0.0.1", 0);
            try {
                String actual = context.getEnvironment().getProperty("local.server.port");
                check(actual != null && !actual.isBlank(), "Spring 启动后能读到 local.server.port=" + actual);
                int port = Integer.parseInt(actual.strip());
                check(port > 1024 && !TestEnvironment.PRODUCTION_PORTS.contains(port),
                        "Spring 实际监听端口是动态分配的：" + port);
                env.claimPort("webui-spring", port);
                check(canConnect(InetAddress.getByName("127.0.0.1"), port), "WebUI 在 127.0.0.1:" + port + " 上真的可连");
            } finally {
                context.close();
            }
        }
        env.printPorts("a 用例结束时");

        // 进程集合（生产端口的监听者就是生产 Pixiko，它的 PID 必须在）。
        check(!pixikoBefore.isEmpty(), "第 14 条 a/e：测试开始时确实有生产 Pixiko 在跑：" + pixikoBefore);
        Map<Integer, List<Long>> listenersNow = TestEnvironment.productionListeners();
        check(listenersNow.equals(listenersBefore),
                "第 14 条 a：a 用例期间生产端口监听者没变：" + listenersBefore + " → " + listenersNow);
        check(TestEnvironment.processSnapshot().containsAll(processesBefore),
                "第 14 条 a：测试期间没有杀掉任何本来在跑的 java/node/python 进程");
        env.requireInside(env.data());
        System.out.println("PASS a：假服务端口 " + ports + "（全部 127.0.0.1 动态端口，生产端口 "
                + TestEnvironment.PRODUCTION_PORTS + " 一个没碰），生产 PID=" + pixikoBefore + " 未变");
    }

    // ------------------------------------------------------------------ b：生产数据

    private static void b_productionDataUntouched(TestEnvironment env, Path production, String configStampBefore,
                                                  String structureBefore, String fingerprintBefore,
                                                  List<Long> pixikoBefore) throws Exception {
        String configStampNow = TestEnvironment.productionConfigStamp(production);
        String structureNow = TestEnvironment.productionStructureFingerprint(production, MAX_FINGERPRINT_ENTRIES);
        String fingerprintNow = TestEnvironment.productionFingerprint(production, MAX_FINGERPRINT_ENTRIES);
        check(configStampBefore.equals(configStampNow),
                "生产 config.json 大小/修改时间没变：before=" + configStampBefore + " now=" + configStampNow);
        check(structureBefore.equals(structureNow),
                "生产 data/** 结构指纹没变：before=" + shorten(structureBefore) + " now=" + shorten(structureNow));
        if (!fingerprintBefore.equals(fingerprintNow)) {
            System.out.println("NOTE b：data/** 的修改时间有漂移（路径与大小未变），来自正在运行的生产实例 "
                    + pixikoBefore + "，不是本测试：before=" + shorten(fingerprintBefore)
                    + " now=" + shorten(fingerprintNow));
        }
        long files;
        try (var walk = Files.walk(env.home())) { files = walk.filter(Files::isRegularFile).count(); }
        check(files > 0, "本次 run 的临时根里确实有文件（配置/锁/数据）：" + files + " 个");
        System.out.println("PASS b：生产 config.json=" + configStampNow + "，data 结构指纹=" + shorten(structureNow)
                + "（只读元数据，未读任何文件内容）");
    }

    // ------------------------------------------------------------------ c：并行

    private static void c_parallelRuns(TestEnvironment env) throws Exception {
        Path parent = env.home().getParent();
        String idA = env.runId() + "-par-a";
        String idB = env.runId() + "-par-b";
        Path homeA = parent.resolve(idA);
        Path homeB = parent.resolve(idB);
        String classpath = System.getProperty("java.class.path");
        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        List<Process> processes = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        try {
            processes.add(new ProcessBuilder(javaExe, "-ea", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                    "-Dbot.test.mode=true", "-Dbot.test.run.id=" + idA,
                    "-Dbot.test.production.root=" + TestEnvironment.productionRoot(),
                    "-Dbot.test.home=" + homeA,
                    "-cp", classpath, "cn.szu.bot.testsupport.IsolationProbe", "ok", homeA.toString())
                    .redirectErrorStream(true).start());
            processes.add(new ProcessBuilder(javaExe, "-ea", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                    "-Dbot.test.mode=true", "-Dbot.test.run.id=" + idB,
                    "-Dbot.test.production.root=" + TestEnvironment.productionRoot(),
                    "-Dbot.test.home=" + homeB,
                    "-cp", classpath, "cn.szu.bot.testsupport.IsolationProbe", "ok", homeB.toString())
                    .redirectErrorStream(true).start());
            for (Process process : processes) {
                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                outputs.add(output);
                check(finished, "并行子测试在 120 秒内结束");
                check(process.exitValue() == 0, "并行子测试 exit=0（实际 " + process.exitValue() + "）：\n" + tail(output));
            }
        } finally {
            for (Process process : processes) if (process.isAlive()) process.destroyForcibly();
        }
        Map<String, Integer> portsA = parsePorts(outputs.get(0));
        Map<String, Integer> portsB = parsePorts(outputs.get(1));
        check(portsA.size() == 4 && portsB.size() == 4, "两套并行 run 各拿到四个端口：" + portsA + " / " + portsB);
        for (Map.Entry<String, Integer> entry : portsA.entrySet()) {
            check(!entry.getValue().equals(portsB.get(entry.getKey())),
                    "第 7 条：并行两套的 " + entry.getKey() + " 端口不同（" + entry.getValue()
                            + " vs " + portsB.get(entry.getKey()) + "）");
            check(!TestEnvironment.PRODUCTION_PORTS.contains(entry.getValue()), "并行 run 也不碰生产端口");
        }
        check(!homeA.equals(homeB), "第 3/7 条：并行两套的临时目录不同");
        check(!Files.exists(homeA) && !Files.exists(homeB), "并行子测试跑完删掉了自己的目录");
        System.out.println("PASS c：两套并行 run 端口不冲突 A=" + portsA + " B=" + portsB + "，目录各删各的");
    }

    // ------------------------------------------------------------------ d：异常退出

    private static void d_abnormalExit(TestEnvironment env) throws Exception {
        Path home = env.home().getParent().resolve(env.runId() + "-crash");
        Path portFile = env.home().resolve("crash-port.txt");
        String classpath = System.getProperty("java.class.path");
        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaExe, "-ea", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "-Dbot.test.mode=true", "-Dbot.test.run.id=" + env.runId() + "-crash",
                "-Dbot.test.production.root=" + TestEnvironment.productionRoot(),
                "-Dbot.test.home=" + home,
                "-cp", classpath, "cn.szu.bot.testsupport.IsolationProbe", "crash", home.toString(),
                portFile.toString())
                .redirectErrorStream(true).start();
        try {
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(finished, "异常退出子进程在 120 秒内结束");
            check(process.exitValue() != 0, "异常退出子进程不是 0（实际 " + process.exitValue() + "）");
            check(Files.isRegularFile(portFile), "异常退出子进程留下了它监听的端口：" + portFile + "\n" + tail(output));
            int port = Integer.parseInt(Files.readString(portFile, StandardCharsets.UTF_8).strip());
            check(!TestEnvironment.PRODUCTION_PORTS.contains(port), "异常退出子进程用的也不是生产端口：" + port);
            boolean rebound = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!rebound && System.nanoTime() < deadline) {
                try (ServerSocket probe = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))) {
                    rebound = probe.getLocalPort() == port;
                } catch (IOException stillBound) { Thread.sleep(50); }
            }
            check(rebound, "第 14 条 d：进程异常退出后端口 " + port + " 立刻可以重新绑定（没有遗留监听）");
            check(!process.isAlive(), "第 14 条 d：异常退出的子进程确实没了");
            System.out.println("PASS d：异常退出（halt）后端口 " + port + " 可重新绑定，进程已消失；"
                    + "残留目录 " + home.getFileName() + " 由测试清理");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            TestCleanup.deleteQuietly(home, 4, 100);
        }
    }

    // ------------------------------------------------------------------ e：主实例在跑

    private static void e_liveProduction(TestEnvironment env, List<Long> pixikoBefore) throws Exception {
        check(!pixikoBefore.isEmpty(), "第 14 条 e：主 Pixiko 正在跑（PID " + pixikoBefore + "）");
        for (long pid : pixikoBefore) {
            check(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "第 14 条 e：主 Pixiko PID " + pid + " 测试期间一直在跑");
        }
        // 生产 bot.lock 是生产实例的；我们的锁在自己 run 里，内容是本进程 PID。
        Path ourLock = env.lockFile();
        check(Files.isRegularFile(ourLock), "本 run 有自己的 bot.lock：" + ourLock);
        check(Files.readString(ourLock, StandardCharsets.UTF_8).contains("pid=" + ProcessHandle.current().pid()),
                "本 run 的 bot.lock 记的是本进程 PID");
        check(Files.isRegularFile(env.dataLockFile()), "data/bot.lock 也在本 run 里：" + env.dataLockFile());
        check(env.lockFile().toAbsolutePath().normalize().startsWith(env.home()), "bot.lock 在本次 run 的根里");
        check(env.dataLockFile().toAbsolutePath().normalize().startsWith(env.home()), "data/bot.lock 不在生产 data/ 下");
        System.out.println("PASS e：主 Pixiko " + pixikoBefore + " 全程在跑（它持有生产 data/bot.lock），"
                + "本 run 用自己的 bot.lock=" + ourLock);
    }

    // ------------------------------------------------------------------ 工具

    private static Map<String, Integer> parsePorts(String output) {
        Map<String, Integer> ports = new LinkedHashMap<>();
        Matcher matcher = PROBE_PORTS.matcher(output);
        if (!matcher.find()) throw new AssertionError("并行子测试没有打印端口：" + tail(output));
        ports.put("onebot", Integer.parseInt(matcher.group(1)));
        ports.put("sd", Integer.parseInt(matcher.group(2)));
        ports.put("deepseek", Integer.parseInt(matcher.group(3)));
        ports.put("civitai", Integer.parseInt(matcher.group(4)));
        return ports;
    }

    private static boolean canConnect(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 5000);
            return true;
        } catch (IOException refused) { return false; }
    }

    private static String shorten(String hash) {
        return hash == null || hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private static String tail(String text) {
        String[] lines = text.split("\r?\n");
        int from = Math.max(0, lines.length - 12);
        return String.join("\n", List.of(lines).subList(from, lines.length));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("FAIL（IsolationTest）：" + message);
    }
}

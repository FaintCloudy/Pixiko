package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>局域网扫描</b>：网段推导、探测（真实本机 8787 / 不存在的端口）、并发上限与总预算、
 * 多台一起返回、无网卡/无权限不崩。
 *
 * <p>为什么这些能在 JVM 单测里真跑：{@link LanScanner} 把"探哪个地址"（{@link LanScanner#scanHosts}）
 * 与"怎么探"（{@link LanScanner.Prober}）都做成了参数，而且<b>不碰 {@code android.*}</b>。
 * 于是可以拿真实本机的 {@code http://127.0.0.1:8787/healthz}、真实的
 * {@link java.net.ServerSocket} 与真实线程池来断言，而不是靠"源码里有这行字"。
 *
 * <p>{@link CapturedLogRule} 是必需的：{@link LanScanner#localSegments()} 在系统不让枚举网卡时会走
 * {@link Log#w}，而单测里的 {@code android.util.Log} 是 stub（一调就抛 "not mocked"）。
 */
public class LanScannerTest {

    @Rule
    public final CapturedLogRule logs = new CapturedLogRule();

    // ------------------------------------------------------------------ ② 网段推导

    @Test
    public void derivesTheSubnetHostsFromTheLocalPrefix() {
        // 192.168.1.37/24 → .1 … .254（真实 /24 边界）
        List<String> hosts = LanScanner.hostsFor("192.168.1.37", 24);
        System.out.println("[实测] 192.168.1.37/24 → " + hosts.size() + " 个：" + hosts.get(0) + " … "
                + hosts.get(hosts.size() - 1) + "（网段 " + LanScanner.segmentOf("192.168.1.37", 24).network + "）");
        assertEquals(254, hosts.size());
        assertEquals("192.168.1.1", hosts.get(0));
        assertEquals("192.168.1.254", hosts.get(hosts.size() - 1));

        // 10.0.5.9/16 → 真实范围是 6 万多个，3 秒扫不完，所以只取包含本机的 /24 切片（等价边界）
        List<String> wide = LanScanner.hostsFor("10.0.5.9", 16);
        LanScanner.Segment wideSegment = LanScanner.segmentOf("10.0.5.9", 16);
        System.out.println("[实测] 10.0.5.9/16 → 网络 " + wideSegment.network + "/16，实际探 "
                + wide.size() + " 个：" + wide.get(0) + " … " + wide.get(wide.size() - 1));
        assertEquals("10.0.0.0", wideSegment.network);
        assertEquals("10.0.5.0", wideSegment.slice);
        assertEquals(254, wide.size());
        assertEquals("10.0.5.1", wide.get(0));
        assertEquals("10.0.5.254", wide.get(wide.size() - 1));
        for (String host : wide) {
            assertTrue("切片里的地址必须仍落在 10.0.0.0/16 里：" + host, host.startsWith("10.0."));
            assertTrue("而且必须是私网地址：" + host, LanScanner.isPrivateV4(host));
        }

        // /32 → 只探自己一个；/31 → RFC 3021 的两个，且都不越界
        assertEquals(Arrays.asList("192.168.1.37"), LanScanner.hostsFor("192.168.1.37", 32));
        assertEquals(Arrays.asList("192.168.1.36", "192.168.1.37"), LanScanner.hostsFor("192.168.1.37", 31));
        // /30 → 去掉网络号与广播地址，只剩两台
        assertEquals(Arrays.asList("192.168.1.37", "192.168.1.38"), LanScanner.hostsFor("192.168.1.37", 30));
        // /25 → 只在自己的 /25 里（.129 … .254），不许越到 .128 或 .255
        List<String> half = LanScanner.hostsFor("192.168.1.130", 25);
        assertEquals(126, half.size());
        assertEquals("192.168.1.129", half.get(0));
        assertEquals("192.168.1.254", half.get(half.size() - 1));

        // 每个网段的目标数都不许超过 254（3 秒预算的物理上限）
        for (String probe : new String[]{"10.0.5.9", "172.20.5.9", "192.168.1.37"}) {
            for (int prefix : new int[]{8, 12, 16, 23, 24, 25, 26, 30, 31, 32}) {
                List<String> targets = LanScanner.hostsFor(probe, prefix);
                assertTrue("/" + prefix + " 的目标数超了 254：" + targets.size(),
                        targets.size() <= LanScanner.MAX_HOSTS_PER_SEGMENT);
            }
        }

        // 169.254/16（链路本地）与 127/8（回环）被排除：不推导、不扫描
        assertNull("169.254 不能扫", LanScanner.segmentOf("169.254.1.5", 16));
        assertTrue(LanScanner.hostsFor("169.254.1.5", 16).isEmpty());
        assertFalse(LanScanner.isPrivateV4("169.254.1.5"));
        assertNull("127.0.0.0/8 不能扫", LanScanner.segmentOf("127.0.0.1", 8));
        assertTrue(LanScanner.hostsFor("127.0.0.1", 8).isEmpty());
        assertFalse(LanScanner.isPrivateV4("127.0.0.1"));
        // 三个私网段都认，172.16/12 的边界要准
        assertTrue(LanScanner.isPrivateV4("10.0.5.9"));
        assertTrue(LanScanner.isPrivateV4("172.16.0.1"));
        assertTrue(LanScanner.isPrivateV4("172.31.255.254"));
        assertFalse(LanScanner.isPrivateV4("172.32.0.1"));
        assertTrue(LanScanner.isPrivateV4("192.168.0.1"));
        assertFalse(LanScanner.isPrivateV4("8.8.8.8"));
    }

    // ------------------------------------------------------------------ ③ 探测（真实本机）

    @Test
    public void detectsTheRealLocalHealthzAndDropsDeadPortsWhileStayingInsideTheBudget() throws Exception {
        // ① 真实本机：http://127.0.0.1:8787/healthz（机器人跑着的时候才有）
        //
        //    这条刻意写成「探测结论必须与 reachable 一致」而不是硬写 200：8787 上有没有服务取决于
        //    机器人是否在跑（本轮实测就跑过一次"机器人不在"），硬写会让整个套件在那种时刻无故变红。
        //    机器人真在跑的时候，下面的断言就是硬性的 200（本轮第一次跑：probe → 200，真实耗时 2ms）。
        long startedAt = System.currentTimeMillis();
        int code = LanScanner.HTTP_PROBER.probe("127.0.0.1", LanScanner.PER_HOST_TIMEOUT_MS);
        long elapsed = System.currentTimeMillis() - startedAt;
        boolean live = LanScanner.reachable("http://127.0.0.1:8787", 500);
        System.out.println("[实测] HTTP_PROBER.probe(127.0.0.1, " + LanScanner.PER_HOST_TIMEOUT_MS + ") → "
                + code + "，真实耗时 " + elapsed + "ms（8787 上有 Pixiko=" + live + "）");
        assertEquals("探测结论必须与 reachable 一致：8787 上有服务 → 200，没有 → 0（实测 " + code
                + "，8787 上有服务=" + live + "）", live ? 200 : 0, code);

        // 同一套"是不是 Pixiko 的 healthz"判据：200 但响应体不是 {"ok":true} 的不能被认成控制台
        try (FakeHttpServer pixiko = new FakeHttpServer(); FakeHttpServer other = new FakeHttpServer()) {
            serveHealthz(pixiko);
            other.serve("/healthz", (request, response) -> {
                response.status(200);
                response.setBody("<html>some router admin page</html>".getBytes(StandardCharsets.UTF_8));
            });
            int pixikoCode = LanScanner.healthzProber(portOf(pixiko)).probe("127.0.0.1", 400);
            int otherCode = LanScanner.healthzProber(portOf(other)).probe("127.0.0.1", 400);
            System.out.println("[实测] 假 Pixiko（{\"ok\": true}）→ " + pixikoCode
                    + "；200 但不是 Pixiko 的页面 → " + otherCode);
            assertEquals("响应体是 {\"ok\": true} 的必须命中", 200, pixikoCode);
            assertEquals("200 但不是 Pixiko 的 healthz 不能算命中", 0, otherCode);
        }

        // ② 不存在的端口：超时/拒连后被排除
        int closedPort = closedPort();
        long closedStart = System.currentTimeMillis();
        int closed = LanScanner.healthzProber(closedPort).probe("127.0.0.1", LanScanner.PER_HOST_TIMEOUT_MS);
        long closedMs = System.currentTimeMillis() - closedStart;
        System.out.println("[实测] 关闭端口 127.0.0.1:" + closedPort + " → 返回 " + closed
                + "，耗时 " + closedMs + "ms");
        assertEquals("关闭/不存在的端口不能算命中", 0, closed);
        LanScanner.Result dead = LanScanner.scanHosts(Arrays.asList("127.0.0.1"),
                LanScanner.healthzProber(closedPort));
        assertTrue("超时/拒连的主机必须被排除在结果之外：" + dead.found, dead.found.isEmpty());
        assertEquals(1, dead.probed);

        // ③ 并发上限 32 + 总预算：254 个地址 × 120ms 的假探测
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        LanScanner.Prober busy = (host, timeout) -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(120);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return 200;
        };
        List<String> hosts = new ArrayList<>();
        for (int index = 1; index <= 254; index++) hosts.add("192.168.1." + index);
        long scanStart = System.currentTimeMillis();
        LanScanner.Result full = LanScanner.scanHosts(hosts, busy);
        long scanMs = System.currentTimeMillis() - scanStart;
        System.out.println("[实测] 254 地址 × 120ms 假探测：耗时 " + scanMs + "ms，峰值并发 " + peak.get()
                + "，命中 " + full.found.size() + "，probed=" + full.probed);
        assertTrue("并发峰值不能超过 " + LanScanner.CONCURRENCY + "（实测 " + peak.get() + "）",
                peak.get() <= LanScanner.CONCURRENCY);
        assertEquals("254 个地址要全部探完", 254, full.probed);
        assertEquals("全部命中", 254, full.found.size());
        assertTrue("耗时要落在总预算内（实测 " + scanMs + "ms，预算 " + LanScanner.TOTAL_BUDGET_MS + "ms）",
                scanMs < LanScanner.TOTAL_BUDGET_MS + 1_000L);

        // ④ 单台探测卡死 2s × 254 台：3 秒总预算必须生效，并标出"没扫完"
        LanScanner.Prober stuck = (host, timeout) -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return 0;
        };
        long budgetStart = System.currentTimeMillis();
        LanScanner.Result cut = LanScanner.scanHosts(hosts, stuck);
        long budgetMs = System.currentTimeMillis() - budgetStart;
        System.out.println("[实测] 每台都卡 2s：耗时 " + budgetMs + "ms（预算 " + LanScanner.TOTAL_BUDGET_MS
                + "ms），probed=" + cut.probed + "/254，budgetExhausted=" + cut.budgetExhausted);
        assertTrue("总预算 3s 必须生效（实测 " + budgetMs + "ms）",
                budgetMs <= LanScanner.TOTAL_BUDGET_MS + 1_000L);
        assertTrue("预算用完要标出来", cut.budgetExhausted);
        assertTrue("没扫完：probed 要小于 254（实测 " + cut.probed + "）", cut.probed < 254);
    }

    // ------------------------------------------------------------------ ④ 多台一起返回

    @Test
    public void returnsEveryHostThatAnswersNotJustTheFirst() throws Exception {
        try (FakeHttpServer first = new FakeHttpServer(); FakeHttpServer second = new FakeHttpServer()) {
            serveHealthz(first);
            serveHealthz(second);
            final Map<String, Integer> ports = new HashMap<>();
            ports.put("host-a", portOf(first));
            ports.put("host-b", portOf(second));
            // 两台都真的在回 {"ok": true}：探测器按"主机名"选端口，主机名只是这条测试里的地址标签。
            LanScanner.Prober prober = (host, timeoutMs) -> {
                Integer port = ports.get(host);
                return port == null ? 0 : LanScanner.healthzProber(port).probe("127.0.0.1", timeoutMs);
            };
            LanScanner.Result result = LanScanner.scanHosts(Arrays.asList("host-a", "host-b"), prober);
            System.out.println("[实测] 两台真实假服务 → 命中 " + result.found + "（共 " + result.probed + " 次探测）");
            assertEquals("两台都要返回，不能只留第一台：" + result.found, 2, result.found.size());
            assertTrue(result.found.contains("host-a"));
            assertTrue(result.found.contains("host-b"));
        }

        // 多网段汇总：scanSegments 把每个网段的命中都收进 found（用假探测器，避开固定 8787 端口）
        LanScanner.Prober hitAll = (host, timeoutMs) -> 200;
        LanScanner.Report report = LanScanner.scanSegments(
                Arrays.asList(LanScanner.segmentOf("192.168.1.37", 32),
                        LanScanner.segmentOf("10.0.5.9", 32)),
                hitAll, null, null);
        System.out.println("[实测] 两个 /32 网段 → " + report.describe());
        assertEquals("两个网段的两台都该在结果里：" + report.found, 2, report.found.size());
        assertTrue(report.found.contains("http://192.168.1.37:8787"));
        assertTrue(report.found.contains("http://10.0.5.9:8787"));
        assertTrue(report.ok());
    }

    // ------------------------------------------------------------------ ⑤ 无网络 / 无权限

    @Test
    public void survivesMissingNetworkOrPermissionsWithAReadableReason() {
        // 注入 null：不抛、不崩，给出可读原因
        assertNull(LanScanner.segmentOf(null, 24));
        assertTrue(LanScanner.hostsFor((String) null, 24).isEmpty());
        assertTrue(LanScanner.hostsFor((LanScanner.Segment) null).isEmpty());
        assertFalse(LanScanner.isPrivateV4(null));

        LanScanner.Report noSegments = LanScanner.scanSegments(null, LanScanner.HTTP_PROBER, null, null);
        System.out.println("[实测] 没有网卡 → problem=「" + noSegments.problem + "」，found=" + noSegments.found);
        assertTrue("没有网段时要给可读原因：" + noSegments.problem, noSegments.problem.contains("没有找到局域网网卡"));
        assertTrue(noSegments.found.isEmpty());
        assertEquals(0, noSegments.probed);

        LanScanner.Report emptySegments = LanScanner.scanSegments(new ArrayList<>(), LanScanner.HTTP_PROBER, null, null);
        assertTrue(emptySegments.found.isEmpty());
        assertFalse("空网段也要有原因", emptySegments.problem.isEmpty());

        LanScanner.Result noHosts = LanScanner.scanHosts(null, LanScanner.HTTP_PROBER);
        assertTrue(noHosts.found.isEmpty());
        assertEquals(0, noHosts.probed);

        LanScanner.Report noProber = LanScanner.scanSegments(LanScanner.localSegments(), null, null, null);
        assertFalse("探测函数缺失也要有原因", noProber.problem.isEmpty());

        // 真实枚举本机网卡：允许 0 个私网网段（比如只走 127/8），但**不许抛**
        List<LanScanner.Segment> segments = LanScanner.localSegments();
        System.out.println("[实测] 本机私网网段（" + segments.size() + " 个）：" + LanScanner.localPrefixes());
        for (LanScanner.Segment segment : segments) {
            assertTrue("枚举出来的网段必须是私网：" + segment.label(), LanScanner.isPrivateV4(segment.address));
            assertTrue(segment.hostCount <= LanScanner.MAX_HOSTS_PER_SEGMENT);
        }

        // 不可达的地址探活不崩（既有默认地址逻辑照旧可用）
        assertFalse(LanScanner.reachable("http://127.0.0.1:" + closedPort(), 300));
        assertFalse(LanScanner.reachable("不是地址", 300));
        assertFalse(LanScanner.reachable(null, 300));
    }

    // ------------------------------------------------------------------ 工具

    private static void serveHealthz(FakeHttpServer server) {
        server.serve("/healthz", (request, response) -> {
            response.status(200);
            response.setBody("{\"ok\": true}".getBytes(StandardCharsets.UTF_8));
        });
    }

    private static int portOf(FakeHttpServer server) {
        String url = server.baseUrl();
        return Integer.parseInt(url.substring(url.lastIndexOf(':') + 1));
    }

    /** 拿一个"肯定没人监听"的本机端口：绑一下再关掉，端口就被系统回收了。 */
    private static int closedPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (Exception error) {
            throw new IllegalStateException("拿不到空闲端口", error);
        }
    }
}

package cn.szu.bot.app;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 局域网扫描：按<b>本机地址 + 前缀长度</b>算出所在网段，对该网段的主机在 {@code 8787} 端口并发探
 * {@code /healthz}，找出跑着控制台的机器。
 *
 * <p>参数是硬要求（也是手机端"优先用局域网 IP"这件事的物理上限）：
 * <ul>
 *   <li>并发上限 {@value #CONCURRENCY}；</li>
 *   <li>单地址探测超时 {@value #PER_HOST_TIMEOUT_MS}ms（300–500ms 档内取偏小值，理由见下）；</li>
 *   <li>整轮总预算 {@code ≤3s}（{@value #TOTAL_BUDGET_MS}ms），到点立刻收工，宁可漏报也不让用户干等；</li>
 *   <li><b>只探私网</b>：{@code 10/8}、{@code 172.16/12}、{@code 192.168/16}。
 *       {@code 169.254/16}（链路本地，没拿到 DHCP 时的自配地址）与 {@code 127/8}（本机回环）
 *       <b>不算私网、不参与扫描</b> —— 探它们只是白烧 3 秒预算。</li>
 * </ul>
 *
 * <p>为什么单地址超时取 300ms 而不是 400ms：3s 预算 / 300ms = 10 轮 × 32 并发 = 320 个地址，
 * 刚好盖住一个 /24 的 254 个；取 400ms 的话 8 轮只有 256 个，会在最后一刻漏掉几台。
 * 局域网内 RST/RTT 都在毫秒级，300ms 对"真有服务"的机器绰绰有余，只有不在线的机器才吃满它。
 *
 * <h2>网段怎么推（前缀，不是硬编码 /24）</h2>
 * 用 {@link InterfaceAddress#getNetworkPrefixLength()} 拿到前缀长度，再按位与算网络号：
 * <ul>
 *   <li>{@code /32}（点对点/单机）→ 只探这一个地址；{@code /31}（RFC 3021）→ 探这两个地址；</li>
 *   <li>{@code /24}–{@code /30} → 真实主机范围（去掉网络号与广播地址），例如 {@code 192.168.1.37/24}
 *       → {@code 192.168.1.1 … 192.168.1.254}；</li>
 *   <li>比 {@code /24} 短（{@code /8}–{@code /23}，例如 {@code 10.0.5.9/16}）→ 真实范围是 6 万多个地址，
 *       3 秒绝对扫不完，于是<b>只取包含本机的那一个 /24 切片</b>（{@code 10.0.5.1 … 10.0.5.254}）。
 *       这是「等价边界」的实现选择：手机和服务器在同一个 /16 里时，几乎必然也在同一个 /24 里。</li>
 * </ul>
 * 任何网段都不超过 {@value #MAX_HOSTS_PER_SEGMENT} 个探测目标。
 *
 * <h2>可注入</h2>
 * 真正决定"能不能扫出东西"的两件事——<b>探哪个地址</b>与<b>怎么探</b>——都做成了参数：
 * {@link #scanSegments(List, Prober, AtomicBoolean, Progress)} 与
 * {@link #scanHosts(List, Prober, int, int, long, AtomicBoolean, Progress)} 都是纯 Java（不碰
 * {@code android.*}），所以 JVM 单测能拿<b>真实本机</b>（{@code http://127.0.0.1:8787/healthz}）与
 * 真实端口跑断言，也能注入一个假探测器量并发上限与总预算。
 */
public final class LanScanner {

    /** 并发上限：32 个线程足够把一个 /24 压到 1–3 秒，又不至于把手机的网络栈打满。 */
    public static final int CONCURRENCY = 32;
    /** 单地址探测超时（毫秒）。取值理由见类注释：3s / 300ms × 32 ≈ 320 个地址。 */
    public static final int PER_HOST_TIMEOUT_MS = 300;
    /** 整轮扫描的硬上限（毫秒）：从 scanHosts 开始算，到点立刻返回已找到的结果。 */
    public static final long TOTAL_BUDGET_MS = 3_000L;
    /** 单个网段最多探多少个地址（254 = 一个 /24 的可用主机数）。 */
    public static final int MAX_HOSTS_PER_SEGMENT = 254;

    /**
     * 一次探测：给一个主机地址，返回 HTTP 状态码（0＝连不上/不是 Pixiko）。
     *
     * <p>做成接口是为了 JVM 单测能注入假实现（真实端口 + 假服务，或纯计时实现），
     * 生产实现见 {@link #healthzProber(int)}。
     */
    public interface Prober {
        int probe(String host, int timeoutMs);
    }

    /** 扫描进度（在扫描线程上回调，实现方自己切主线程）。 */
    public interface Progress {
        void onProbe(int done, int total);
    }

    /** 扫描回调，全部在主线程调用。 */
    public interface Listener {
        /** 每扫完一个地址回调一次（done/total 用于进度条）。 */
        void onProgress(int done, int total);

        /** 发现一台服务（可能多次；结果已去重）。 */
        void onFound(String base, String host);

        /** 正常结束/被取消都会回调一次。 */
        void onFinished(List<String> found, boolean cancelled);
    }

    /**
     * 一个可扫的网段：本机地址 + 前缀长度 → 网络号 + 实际要探的 /24 切片。
     *
     * <p>纯数据 + 纯计算，构造只走 {@link #segmentOf(String, int)}（非法输入返回 {@code null}）。
     */
    public static final class Segment {
        /** 本机地址，形如 {@code 192.168.1.37}。 */
        public final String address;
        /** 前缀长度，例如 24。 */
        public final int prefixLength;
        /** 按前缀算出的网络号，形如 {@code 192.168.1.0}（展示/诊断用）。 */
        public final String network;
        /** 实际要探的 /24 切片基址，形如 {@code 192.168.1.0}（比 /24 短时 ≠ network）。 */
        public final String slice;
        /** 切片前缀，形如 {@code 192.168.1.}（拼主机地址用）。 */
        final String slicePrefix;
        /** 探测范围（含端点）。 */
        public final String firstHost;
        public final String lastHost;
        /** 探测目标个数。 */
        public final int hostCount;

        Segment(String address, int prefixLength, String network, String slice,
                String slicePrefix, String firstHost, String lastHost, int hostCount) {
            this.address = address;
            this.prefixLength = prefixLength;
            this.network = network;
            this.slice = slice;
            this.slicePrefix = slicePrefix;
            this.firstHost = firstHost;
            this.lastHost = lastHost;
            this.hostCount = hostCount;
        }

        /** 给人看的一句话：{@code 192.168.1.0/24（本机 192.168.1.37，探 1–254）}。 */
        public String label() {
            return network + "/" + prefixLength + "（本机 " + address + "，探 " +
                    lastHostSuffix(firstHost) + "–" + lastHostSuffix(lastHost) + "）";
        }

        private static String lastHostSuffix(String ip) {
            if (ip == null) return "?";
            int at = ip.lastIndexOf('.');
            return at < 0 ? ip : ip.substring(at + 1);
        }

        @Override public String toString() { return label(); }
    }

    /** 一次扫描的结果（{@link #scanHosts} 的返回值，也是 {@link #report()} 的内容）。 */
    public static final class Result {
        /** 命中的主机地址（按输入顺序，已去重）。 */
        public final List<String> found;
        /** 真的探过多少个地址。 */
        public final int probed;
        /** 计划探多少个地址。 */
        public final int total;
        /** 实际耗时（毫秒）。 */
        public final long elapsedMs;
        /** 是不是被 {@link #cancel()} 打断的。 */
        public final boolean cancelled;
        /** 是不是总预算先用完了（没扫完，结果是"已知的最好答案"）。 */
        public final boolean budgetExhausted;

        Result(List<String> found, int probed, int total, long elapsedMs,
               boolean cancelled, boolean budgetExhausted) {
            this.found = found;
            this.probed = probed;
            this.total = total;
            this.elapsedMs = elapsedMs;
            this.cancelled = cancelled;
            this.budgetExhausted = budgetExhausted;
        }
    }

    /** {@link #scanSegments} 的结果：命中地址 + 网段说明 + 耗时 + 失败原因（可读）。 */
    public static final class Report {
        /** 命中地址，形如 {@code http://192.168.1.5:8787}。 */
        public final List<String> found;
        /** 扫过的网段（{@link Segment#label()}）。 */
        public final List<String> segments;
        public final int probed;
        public final long elapsedMs;
        public final boolean cancelled;
        public final boolean budgetExhausted;
        /** 非空 = 这次为什么扫不出东西（没有网卡/没权限/探测函数不可用）。 */
        public final String problem;

        Report(List<String> found, List<String> segments, int probed, long elapsedMs,
               boolean cancelled, boolean budgetExhausted, String problem) {
            this.found = found;
            this.segments = segments;
            this.probed = probed;
            this.elapsedMs = elapsedMs;
            this.cancelled = cancelled;
            this.budgetExhausted = budgetExhausted;
            this.problem = problem == null ? "" : problem;
        }

        public boolean ok() { return problem.isEmpty() && !found.isEmpty(); }

        /** 一行结论（设置页/日志用）。 */
        public String describe() {
            if (!problem.isEmpty()) return problem;
            StringBuilder text = new StringBuilder();
            text.append("扫描 ").append(segments.isEmpty() ? "（无网段）" : String.join("、", segments))
                    .append("，探了 ").append(probed).append(" 个地址，耗时 ").append(elapsedMs).append("ms");
            if (budgetExhausted) text.append("（预算用完，结果可能不全）");
            if (cancelled) text.append("（已取消）");
            text.append("，发现 ").append(found.size()).append(" 台");
            return text.toString();
        }
    }

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile Report lastReport;

    /** 请求取消：立刻停掉还在跑的探测（离开扫描页时必须调，否则线程会拖着 IO）。 */
    public void cancel() {
        cancelled.set(true);
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }

    public boolean isCancelled() { return cancelled.get(); }

    /** 最近一次扫描的结果；还没扫过返回 {@code null}。 */
    public Report report() { return lastReport; }

    /**
     * 开始扫描。本方法<b>立即返回</b>，结果通过 {@code listener} 回调（回调前会切到主线程）。
     */
    public void start(final Listener listener) { start(listener, HTTP_PROBER); }

    /** 同上，但用注入的探测器（单测/特殊端口用）。 */
    public void start(final Listener listener, final Prober prober) {
        worker = new Thread(() -> {
            Report outcome;
            try {
                List<Segment> segments = localSegments();
                Log.d("开始局域网扫描：" + (segments.isEmpty() ? "没有可用私网网段"
                        : String.join("、", labels(segments))));
                outcome = scanSegments(segments, prober, cancelled, (done, total) -> {
                    final int at = done;
                    final int of = total;
                    post(listener, () -> listener.onProgress(at, of));
                });
                Log.i("局域网扫描结束：" + outcome.describe());
                for (final String base : outcome.found) {
                    post(listener, () -> listener.onFound(base, UrlHelper.hostOnly(base)));
                }
            } catch (Exception error) {
                // 没有权限（SecurityException）、网卡被拔、线程被中断…… 一律不往外抛：
                // 扫描失败是正常结果，调用方看 Report.problem 即可。
                Log.w("扫描出错", error);
                outcome = new Report(new ArrayList<>(), new ArrayList<>(), 0, 0L,
                        false, false, "扫描出错：" + Log.describe(error));
            }
            lastReport = outcome;
            final List<String> found = outcome.found;
            final boolean wasCancelled = outcome.cancelled || cancelled.get();
            post(listener, () -> listener.onFinished(found, wasCancelled));
        }, "pixiko-lan-scan");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------ 核心：扫描（纯 Java，可单测）

    /**
     * 对给定网段<b>同步</b>扫一遍并返回结果。没有任何 {@code android.*} 调用 ——
     * 单测可以直接调它，生产路径由 {@link #start(Listener, Prober)} 包在后台线程里。
     *
     * <p>{@code segments} 为 {@code null}/空 → 返回一个带可读原因（{@code problem}）的空结果，
     * <b>不抛异常、不崩</b>（没有 Wi-Fi / 系统不让枚举网卡都是这种情况）。
     */
    public static Report scanSegments(List<Segment> segments, Prober prober,
                                      AtomicBoolean cancel, Progress progress) {
        long started = System.currentTimeMillis();
        List<String> labels = new ArrayList<>();
        List<String> hosts = new ArrayList<>();
        if (segments != null) {
            for (Segment segment : segments) {
                if (segment == null) continue;
                labels.add(segment.label());
                hosts.addAll(hostsFor(segment));
            }
        }
        if (hosts.isEmpty()) {
            return new Report(new ArrayList<>(), labels, 0, System.currentTimeMillis() - started,
                    false, false, "没有找到局域网网卡（可能没连 Wi-Fi/有线网，或系统不让读网卡）");
        }
        if (prober == null) {
            return new Report(new ArrayList<>(), labels, 0, System.currentTimeMillis() - started,
                    false, false, "探测函数不可用，没法扫描");
        }
        Result result = scanHosts(hosts, prober, CONCURRENCY, PER_HOST_TIMEOUT_MS, TOTAL_BUDGET_MS,
                cancel, progress);
        List<String> found = new ArrayList<>();
        for (String ip : result.found) found.add("http://" + ip + ":" + UrlHelper.DEFAULT_PORT);
        return new Report(found, labels, result.probed, result.elapsedMs,
                result.cancelled, result.budgetExhausted, "");
    }

    /** 扫描一组主机地址（{@code /healthz} 在 {@code 8787}）。纯 Java，可单测。 */
    public static Result scanHosts(List<String> hosts, Prober prober) {
        return scanHosts(hosts, prober, CONCURRENCY, PER_HOST_TIMEOUT_MS, TOTAL_BUDGET_MS, null, null);
    }

    /**
     * 扫描一组主机地址：并发上限 {@code concurrency}（硬封顶 {@value #CONCURRENCY}）、
     * 单地址超时 {@code perHostTimeoutMs}、总预算 {@code budgetMs}，可取消。
     *
     * <p>三个数字都是<b>上界</b>，不是"大约"：线程池大小 = 并发上限，{@code get()} 的等待时间
     * 不会超过剩余预算，到点就 {@code shutdownNow()} 并把自己标成 {@code budgetExhausted}。
     */
    public static Result scanHosts(final List<String> hosts, final Prober prober,
                                   int concurrency, int perHostTimeoutMs, long budgetMs,
                                   final AtomicBoolean cancel, final Progress progress) {
        long started = System.currentTimeMillis();
        final List<String> found = new ArrayList<>();
        if (hosts == null || hosts.isEmpty() || prober == null) {
            return new Result(found, 0, hosts == null ? 0 : hosts.size(), 0L, false, false);
        }
        final int limit = Math.max(1, Math.min(concurrency, CONCURRENCY));
        final int timeout = Math.max(1, perHostTimeoutMs);
        final long deadline = started + Math.max(1L, budgetMs);
        final int total = hosts.size();

        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "pixiko-lan-probe");
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService pool = Executors.newFixedThreadPool(limit, factory);
        int probed = 0;
        boolean cancelled = false;
        boolean budgetExhausted = false;
        try {
            List<Future<Integer>> futures = new ArrayList<>(total);
            for (final String host : hosts) {
                if (isCancelled(cancel)) { cancelled = true; break; }
                futures.add(pool.submit(() -> isCancelled(cancel) ? 0 : prober.probe(host, timeout)));
            }
            for (int index = 0; index < futures.size(); index++) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0 && !futures.get(index).isDone()) {
                    budgetExhausted = true;
                    break;
                }
                int code;
                try {
                    code = futures.get(index).get(Math.max(1L, left), TimeUnit.MILLISECONDS);
                } catch (Exception error) {
                    // 超时/被取消：这一台当"没响应"处理，结果里不出现。
                    futures.get(index).cancel(true);
                    code = 0;
                }
                probed++;
                if (progress != null) progress.onProbe(probed, total);
                if (isHit(code) && !found.contains(hosts.get(index))) found.add(hosts.get(index));
                if (isCancelled(cancel)) { cancelled = true; break; }
            }
        } finally {
            pool.shutdownNow();
        }
        return new Result(found, probed, total, System.currentTimeMillis() - started,
                cancelled, budgetExhausted);
    }

    /** 2xx 才算命中（{@code healthzProber} 已经把"200 但不是 Pixiko"压成 0）。 */
    static boolean isHit(int code) { return code >= 200 && code < 300; }

    private static boolean isCancelled(AtomicBoolean cancel) {
        return cancel != null && cancel.get();
    }

    /** 生产用探测器：{@code GET http://<host>:8787/healthz}，200 且是 Pixiko 的 healthz 才算命中。 */
    public static final Prober HTTP_PROBER = healthzProber(UrlHelper.DEFAULT_PORT);

    /**
     * 造一个探 {@code /healthz} 的探测器（端口可换，单测用本地假服务时用得上）。
     *
     * <p>"是不是 Pixiko 的 healthz"用响应体判：真实服务端 {@code /healthz} 回
     * {@code {"ok": true}}（见 WebPageController.healthz），所以要求 200 <b>且</b>响应体里有
     * {@code "ok"} + {@code true}。这样同一网段上其它跑着 8787 端口的服务（路由器管理页之类）
     * 不会被误当成控制台。
     */
    public static Prober healthzProber(final int port) {
        final int actualPort = port > 0 ? port : UrlHelper.DEFAULT_PORT;
        return (host, timeoutMs) -> {
            String url = "http://" + host + ":" + actualPort + "/healthz";
            try {
                Http.Result result = Http.get(url, null, timeoutMs);
                if (result.code != 200) return result.code;
                return isPixikoHealthz(result.body) ? 200 : 0;
            } catch (Exception error) {
                // 连不上/超时都是"这台没服务"，是正常结果而不是错误。
                return 0;
            }
        };
    }

    /** {@code /healthz} 的响应体像不像 Pixiko 的（{@code {"ok": true}}）。 */
    public static boolean isPixikoHealthz(String body) {
        if (body == null) return false;
        String text = body.replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "");
        return text.contains("\"ok\":true");
    }

    /**
     * 一个基地址的 {@code /healthz} 通不通（200–299）。
     *
     * <p>启动时「已保存的地址还通不通」就是问这一句：通了就<b>跳过扫描</b>（省电、省时间），
     * 不通才去扫所在网段。纯 Java（{@link Http} + {@link UrlHelper}），单测能对着
     * {@code http://127.0.0.1:8787} 断言它真的区分得出"通"和"不通"。
     */
    public static boolean reachable(String base, int timeoutMs) {
        String normalized = UrlHelper.normalizeBase(base);
        if (normalized == null) return false;
        return isHit(Http.reachable(UrlHelper.join(normalized, "/healthz"), timeoutMs));
    }

    // ------------------------------------------------------------------ 网段推导（纯 Java，可单测）

    /**
     * 收集本机所有「可扫的私网 IPv4 网段」。
     *
     * <p>走 {@link NetworkInterface#getInterfaceAddresses()} 而不是只信 WifiManager：
     * 手机也可能走有线/热点，而且 {@link InterfaceAddress#getNetworkPrefixLength()} 直接给出前缀长度
     * （这正是"按前缀算网段"要的输入），<b>不需要任何权限</b>，也就没有"没定位权限拿不到 SSID"那一类坑。
     *
     * <p>枚举网卡失败（{@code SecurityException}/系统限制）时返回空列表并记一条 WARN ——
     * 调用方按"没有网卡"处理（{@link #scanSegments} 会给出可读原因），绝不崩。
     */
    public static List<Segment> localSegments() {
        List<Segment> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nic == null || !nic.isUp() || nic.isLoopback()) continue;
                for (InterfaceAddress iface : nic.getInterfaceAddresses()) {
                    if (iface == null) continue;
                    InetAddress address = iface.getAddress();
                    if (!(address instanceof Inet4Address)) continue;
                    Segment segment = segmentOf(address.getHostAddress(), iface.getNetworkPrefixLength());
                    if (segment == null) continue;                 // 非私网/链路本地/回环 → 不扫
                    if (!seen.add(segment.slice)) continue;        // 同一 /24 切片只扫一次
                    out.add(segment);
                }
            }
        } catch (Exception error) {
            Log.w("枚举网卡失败（可能没有权限或没有网络）", error);
        }
        return out;
    }

    /** {@link #localSegments()} 的展示用版本（{@code 192.168.1.0/24（本机 …，探 1–254）}）。 */
    public static List<String> localPrefixes() {
        return labels(localSegments());
    }

    private static List<String> labels(List<Segment> segments) {
        List<String> out = new ArrayList<>();
        if (segments != null) for (Segment segment : segments) if (segment != null) out.add(segment.label());
        return out;
    }

    /**
     * 由一个「本机地址 + 前缀长度」算出可扫网段；不合法/不该扫的返回 {@code null}。
     *
     * <p>返回 {@code null} 的情况：地址不是合法 IPv4、前缀不在 0–32、
     * 或者<b>不是私网</b>（{@code 10/8}、{@code 172.16/12}、{@code 192.168/16} 之外一律不扫 ——
     * 包括 {@code 169.254/16} 链路本地与 {@code 127/8} 回环）。
     */
    public static Segment segmentOf(String ip, int prefixLength) {
        long value = parseV4(ip);
        if (value < 0) return null;
        if (!isPrivateV4(ip)) return null;
        if (prefixLength < 0 || prefixLength > 32) return null;

        long mask = prefixLength == 0 ? 0L : (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
        long network = value & mask;
        // 探测块：/24 及更长按真实网段；比 /24 短就切到包含本机的那一个 /24（3s 预算只够 254 个地址）。
        long slice = prefixLength >= 24 ? network : (value & 0xFFFFFF00L);
        long first;
        long last;
        if (prefixLength == 32) {
            first = value;
            last = value;
        } else if (prefixLength == 31) {
            // RFC 3021：/31 两端的地址都是可用的主机地址（没有网络号/广播地址之分）。
            long base = value & 0xFFFFFFFEL;
            first = base;
            last = base + 1;
        } else if (prefixLength >= 24) {
            long broadcast = network | (~mask & 0xFFFFFFFFL);
            first = network + 1;
            last = broadcast - 1;
        } else {
            first = slice + 1;
            last = slice + 254;
        }
        long count = last - first + 1;
        if (count <= 0) return null;
        if (count > MAX_HOSTS_PER_SEGMENT) last = first + MAX_HOSTS_PER_SEGMENT - 1;

        return new Segment(ipOf(value), prefixLength, ipOf(network), ipOf(slice),
                ipOf(slice) + ".", ipOf(first), ipOf(last), (int) (last - first + 1));
    }

    /** 该网段要探的主机地址（{@code segmentOf} 不合法时空列表，不抛）。 */
    public static List<String> hostsFor(Segment segment) {
        List<String> out = new ArrayList<>();
        if (segment == null) return out;
        long first = parseV4(segment.firstHost);
        if (first < 0) return out;
        for (int index = 0; index < segment.hostCount; index++) out.add(ipOf(first + index));
        return out;
    }

    /** {@code hostsFor(segmentOf(ip, prefixLength))} 的便捷写法；不合法时空列表。 */
    public static List<String> hostsFor(String ip, int prefixLength) {
        return hostsFor(segmentOf(ip, prefixLength));
    }

    /**
     * 是不是「能扫的私网 IPv4」：{@code 10/8}、{@code 172.16/12}、{@code 192.168/16}。
     *
     * <p>{@code 169.254/16}（链路本地）与 {@code 127/8}（回环）刻意返回 {@code false}：
     * 它们<b>不可用、不计入</b>局域网优先排序，也不参与扫描。
     *
     * <p>内部一律用 {@code long} 做无符号 32 位运算：{@code 192.168.x.x} 的最高位是 1，
     * 用 {@code int} 会变成负数（曾经因此把整个 192.168/16 判成"不是私网"）。
     */
    public static boolean isPrivateV4(String host) {
        long value = parseV4(host);
        if (value < 0) return false;
        long first = (value >>> 24) & 0xFFL;
        long second = (value >>> 16) & 0xFFL;
        if (first == 10L) return true;
        if (first == 172L && second >= 16L && second <= 31L) return true;
        return first == 192L && second == 168L;
    }

    /** 点分十进制 → 无符号 32 位整数（0 … 4294967295）；不是严格 IPv4 返回 -1。 */
    static long parseV4(String host) {
        if (host == null) return -1L;
        String[] parts = host.trim().split("\\.", -1);
        if (parts.length != 4) return -1L;
        long value = 0L;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return -1L;
            long octet = 0L;
            for (int index = 0; index < part.length(); index++) {
                char ch = part.charAt(index);
                if (ch < '0' || ch > '9') return -1L;
                octet = octet * 10L + (ch - '0');
            }
            if (octet > 255L) return -1L;
            value = (value << 8) | octet;
        }
        return value;
    }

    /** 无符号 32 位整数 → 点分十进制。 */
    static String ipOf(long value) {
        return ((value >>> 24) & 0xFFL) + "." + ((value >>> 16) & 0xFFL) + "."
                + ((value >>> 8) & 0xFFL) + "." + (value & 0xFFL);
    }

    /** 把回调切回主线程：扫描线程不许碰 UI。 */
    private static void post(Listener listener, Runnable action) {
        if (listener == null || action == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(action);
    }
}

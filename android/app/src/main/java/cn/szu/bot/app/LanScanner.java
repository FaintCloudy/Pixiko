package cn.szu.bot.app;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 局域网扫描：把本机所在网段的 {@code .1}–{@code .254} 全探一遍，找出跑着控制台的地址。
 *
 * <p>参数是硬要求：并发 32、单个探测 400ms 超时、总上限 10s、可取消。
 * 用 {@link Http#reachable} 打 {@code /healthz}（免令牌），所以扫到的都是「真的有服务」的机器。
 *
 * <p>两个细节值得记一下：
 * <ul>
 *   <li>不是每台机器都秒回 RST：不在线的 IP 往往要等到超时。所以一轮扫不完就<b>只补扫没响应的</b>，
 *       总时限一到立刻收工，宁可漏报也不让用户干等；</li>
 *   <li>网段可能不止一个（Wi-Fi + 有线 + VPN 虚拟网卡），全部收集起来一起扫，
 *       但 10.0.0.0/8 这种巨网段只取 /24 切片（254 个地址封顶）。</li>
 * </ul>
 */
public final class LanScanner {

    /** 并发上限：32 个线程足够把一次 254 地址的扫描压到 1–3 秒。 */
    private static final int CONCURRENCY = 32;
    /** 单地址探测超时。 */
    private static final int PER_HOST_TIMEOUT_MS = 400;
    /** 整个扫描的硬上限（从 start 开始算）。 */
    private static final long TOTAL_BUDGET_MS = 10_000L;

    /** 扫描进度回调，全部在主线程调用。 */
    public interface Listener {
        /** 每扫完一个地址回调一次（done/total 用于进度条）。 */
        void onProgress(int done, int total);

        /** 发现一台服务（可能多次；结果已去重）。 */
        void onFound(String base, String host);

        /** 正常结束/被取消都会回调一次。 */
        void onFinished(List<String> found, boolean cancelled);
    }

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile ExecutorService pool;
    private volatile Thread worker;

    /** 请求取消：立刻停掉还在跑的探测（扫描页退出时必须调，否则线程会拖着 IO）。 */
    public void cancel() {
        cancelled.set(true);
        ExecutorService executor = pool;
        if (executor != null) executor.shutdownNow();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }

    public boolean isCancelled() { return cancelled.get(); }

    /**
     * 开始扫描。本方法<b>立即返回</b>，结果通过 {@code listener} 回调（回调前会切到主线程）。
     */
    public void start(final Listener listener) {
        worker = new Thread(() -> {
            List<String> found = new ArrayList<>();
            boolean wasCancelled = false;
            try {
                List<String> prefixes = localPrefixes();
                if (prefixes.isEmpty()) {
                    Log.w("扫不到任何 /24 网段：可能没有连 Wi-Fi/有线网");
                    post(listener, () -> listener.onFinished(found, false));
                    return;
                }
                int total = prefixes.size() * 254;
                Log.d("开始扫描 " + prefixes.size() + " 个网段，共 " + total + " 个地址");

                pool = Executors.newFixedThreadPool(CONCURRENCY);
                List<String> pending = new ArrayList<>();
                for (String prefix : prefixes) for (int i = 1; i <= 254; i++) pending.add(prefix + i);

                long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
                int done = 0;

                // 最多两轮：第一轮全场，第二轮只补第一轮没响应的（RST 慢/丢包的机器）。
                for (int round = 0; round < 2 && !cancelled.get(); round++) {
                    if (System.currentTimeMillis() >= deadline) break;
                    List<String> nextPending = new ArrayList<>();
                    List<Future<String>> futures = new ArrayList<>();
                    for (final String ip : pending) {
                        if (cancelled.get()) break;
                        futures.add(pool.submit(new Callable<String>() {
                            @Override public String call() {
                                if (cancelled.get()) return null;
                                String url = "http://" + ip + ":" + UrlHelper.DEFAULT_PORT + "/healthz";
                                int code = Http.reachable(url, PER_HOST_TIMEOUT_MS);
                                return code >= 200 && code < 400 ? ip : null;
                            }
                        }));
                    }
                    for (int i = 0; i < futures.size(); i++) {
                        String ip = pending.get(i);
                        String hit = null;
                        try {
                            // get 带超时：即使某个探测卡住也不会把总预算拖穿。
                            long left = Math.max(200L, deadline - System.currentTimeMillis());
                            hit = futures.get(i).get(Math.min(left, 2000L), TimeUnit.MILLISECONDS);
                        } catch (Exception error) {
                            // 超时/取消都当「这一台没响应」处理，下一轮再补。
                            futures.get(i).cancel(true);
                        }
                        done++;
                        final int progress = done;
                        post(listener, () -> listener.onProgress(Math.min(progress, total), total));
                        if (cancelled.get()) { wasCancelled = true; break; }
                        if (hit != null) {
                            String base = "http://" + hit + ":" + UrlHelper.DEFAULT_PORT;
                            if (!found.contains(base)) {
                                found.add(base);
                                final String reported = base;
                                post(listener, () -> listener.onFound(reported, UrlHelper.hostOnly(reported)));
                            }
                        } else {
                            nextPending.add(ip);
                        }
                    }
                    if (cancelled.get()) { wasCancelled = true; break; }
                    // 补扫只在还有时间、且确实筛掉了一批地址时才做。
                    if (round == 0 && nextPending.size() < pending.size() && System.currentTimeMillis() < deadline) {
                        pending = nextPending;
                    } else {
                        break;
                    }
                }
                wasCancelled = wasCancelled || cancelled.get();
            } catch (Exception error) {
                Log.w("扫描出错", error);
            } finally {
                ExecutorService executor = pool;
                if (executor != null) executor.shutdownNow();
                final boolean flag = wasCancelled;
                post(listener, () -> listener.onFinished(found, flag));
            }
        }, "pixiko-lan-scan");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 收集本机所有「像局域网」的 IPv4 /24 前缀（形如 {@code 192.168.1.}）。
     *
     * <p>走 {@link NetworkInterface} 而不是只信 WifiManager：手机也可能走有线/热点，
     * 而且 Android 10+ 拿 SSID 还要定位权限，这里只要地址，不需要权限。
     */
    public static List<String> localPrefixes() {
        Set<String> prefixes = new LinkedHashSet<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    if (!address.isSiteLocalAddress()) continue;   // 10./172.16-31./192.168.
                    String host = address.getHostAddress();
                    int at = host.lastIndexOf('.');
                    if (at <= 0) continue;
                    prefixes.add(host.substring(0, at + 1));
                }
            }
        } catch (Exception error) {
            Log.w("枚举网卡失败", error);
        }
        return new ArrayList<>(prefixes);
    }

    /** 把回调切回主线程：扫描线程不许碰 UI。 */
    private static void post(Listener listener, Runnable action) {
        if (listener == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(action);
    }
}

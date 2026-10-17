package cn.szu.bot;

import cn.szu.bot.web.WebAuthFilter;
import cn.szu.bot.web.WebAuthFilter.Throttle;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Web 鉴权限流：按客户端计数、并发原子累计、TTL、容量上限、成功清理，以及 X-Forwarded-For 信任策略。
 *
 * <p>纯逻辑 + 可控时钟：不起 HTTP、不读真实时钟，也不打印任何令牌（这里用的都是编的假令牌）。
 * 接口与状态码的端到端行为仍在 {@code WebUiTest} 里跑。
 */
public final class WebAuthFilterTest {

    private static final String TOKEN = "test-token-123456";
    private static final String WRONG = "wrong-token";
    private static final long T0 = 1_700_000_000_000L;
    private static int checks;

    public static void main(String[] args) throws Exception {
        threshold();
        isolation();
        expiry();
        successClears();
        concurrency();
        capacity();
        forwardedFor();
        System.out.println("WebAuthFilterTest: " + checks + " assertions passed：第 10 次失败后冷却（第 10 次仍 401、"
                + "之后 429、30 秒后恢复）、不同客户端互不影响、15 分钟窗口过期恢复、成功清理、"
                + "20 线程并发失败不丢计数、容量上限与最旧淘汰、X-Forwarded-For 默认不信任与白名单策略");
    }

    /** 第 10 次失败后冷却 30 秒：第 10 次本身照旧 401，第 11 次 429，冷却结束后又能试。 */
    private static void threshold() {
        AtomicLong now = new AtomicLong(T0);
        Throttle throttle = new Throttle(now::get);
        String client = "203.0.113.7";
        for (int i = 1; i <= 9; i++)
            check(WebAuthFilter.decide(TOKEN, WRONG, client, throttle) == WebAuthFilter.UNAUTHORIZED,
                    "第 " + i + " 次失败回 401");
        check(throttle.failures(client) == 9, "9 次失败计数是 9：" + throttle.failures(client));
        check(!throttle.blocked(client), "没到阈值，不在冷却里");
        check(WebAuthFilter.decide(TOKEN, WRONG, client, throttle) == WebAuthFilter.UNAUTHORIZED,
                "第 10 次失败仍然回 401（阈值语义与改造前一致）");
        check(throttle.blocked(client), "第 10 次之后进入冷却");
        check(WebAuthFilter.decide(TOKEN, WRONG, client, throttle) == WebAuthFilter.THROTTLED, "冷却期间回 429");
        now.addAndGet(29_000);
        check(WebAuthFilter.decide(TOKEN, WRONG, client, throttle) == WebAuthFilter.THROTTLED, "冷却 29 秒时还是 429");
        now.addAndGet(1_000);
        check(WebAuthFilter.decide(TOKEN, WRONG, client, throttle) == WebAuthFilter.UNAUTHORIZED,
                "冷却结束后可以再试（401，不是 429）");
        check(throttle.failures(client) == 1, "冷却结束后从 1 重新数：" + throttle.failures(client));
        // 冷却中拿对令牌照旧放行（判定顺序与改造前一致：先比令牌、再看冷却），并顺手清掉记录。
        for (int i = 0; i < 10; i++) WebAuthFilter.decide(TOKEN, WRONG, client, throttle);
        check(throttle.blocked(client), "再次进入冷却");
        check(WebAuthFilter.decide(TOKEN, TOKEN, client, throttle) == WebAuthFilter.ALLOW, "冷却中拿对令牌仍然放行");
        check(throttle.size() == 0, "放行时把冷却记录整条清掉：" + throttle.size());
    }

    /** 不同客户端各算各的：A 被打满不影响 B。 */
    private static void isolation() {
        Throttle throttle = new Throttle(() -> T0);
        String a = "203.0.113.7", b = "198.51.100.9";
        for (int i = 0; i < 10; i++) WebAuthFilter.decide(TOKEN, WRONG, a, throttle);
        check(throttle.blocked(a), "A 被限流");
        check(WebAuthFilter.decide(TOKEN, WRONG, a, throttle) == WebAuthFilter.THROTTLED, "A 拿 429");
        check(WebAuthFilter.decide(TOKEN, TOKEN, b, throttle) == WebAuthFilter.ALLOW,
                "B 不受 A 影响，拿对令牌照常放行");
        check(WebAuthFilter.decide(TOKEN, WRONG, b, throttle) == WebAuthFilter.UNAUTHORIZED,
                "B 自己第一次失败还是 401（不是 429）");
        check(throttle.failures(b) == 1, "B 从 1 开始数：" + throttle.failures(b));
    }

    /** TTL：15 分钟窗口过了，旧失败作废，不会"慢慢攒到 10 次"。 */
    private static void expiry() {
        AtomicLong now = new AtomicLong(T0);
        Throttle throttle = new Throttle(now::get);
        String client = "203.0.113.7";
        for (int i = 0; i < 9; i++) WebAuthFilter.decide(TOKEN, WRONG, client, throttle);
        check(throttle.failures(client) == 9, "先攒 9 次失败");
        now.addAndGet(Throttle.WINDOW_MILLIS - 1);
        check(throttle.failures(client) == 9, "窗口内计数还在");
        now.addAndGet(1);
        check(throttle.failures(client) == 0, "过了 15 分钟窗口，旧计数作废");
        check(WebAuthFilter.decide(TOKEN, WRONG, client, throttle) == WebAuthFilter.UNAUTHORIZED,
                "过期后还能再试（不是 429）");
        check(throttle.failures(client) == 1, "过期后从 1 重新数：" + throttle.failures(client));
        for (int i = 0; i < 8; i++) WebAuthFilter.decide(TOKEN, WRONG, client, throttle);
        check(throttle.failures(client) == 9 && !throttle.blocked(client),
                "跨窗口的老失败不算进阈值：" + throttle.failures(client));
    }

    /** 成功登录把这个客户端的失败记录清干净，下次从 1 数。 */
    private static void successClears() {
        Throttle throttle = new Throttle(() -> T0);
        String client = "203.0.113.7";
        for (int i = 0; i < 9; i++) WebAuthFilter.decide(TOKEN, WRONG, client, throttle);
        check(throttle.failures(client) == 9, "先攒 9 次失败");
        check(WebAuthFilter.decide(TOKEN, TOKEN, client, throttle) == WebAuthFilter.ALLOW, "正确令牌放行");
        check(throttle.failures(client) == 0, "成功后失败计数归零：" + throttle.failures(client));
        check(throttle.size() == 0, "成功登录把整条记录删掉：" + throttle.size());
        for (int i = 1; i <= 9; i++)
            check(WebAuthFilter.decide(TOKEN, WRONG, client, throttle) == WebAuthFilter.UNAUTHORIZED,
                    "成功后重新数，第 " + i + " 次还是 401（没有被旧账顶到 429）");
        check(!throttle.blocked(client), "成功后重新数的 9 次不触发冷却");
    }

    /** 同一客户端 20 线程并发失败：计数一次不丢、不抛异常（compute 里完成读—判断—写）。 */
    private static void concurrency() throws Exception {
        // 阈值放到很高，好数"到底记了多少次"——默认的 10 次会按设计清零并进冷却。
        Throttle throttle = new Throttle(System::currentTimeMillis, 100_000,
                Throttle.COOLDOWN_MILLIS, Throttle.WINDOW_MILLIS, Throttle.MAX_CLIENTS);
        String client = "203.0.113.7";
        int threads = 20, rounds = 25;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) pool.execute(() -> {
                try {
                    start.await();
                    for (int round = 0; round < rounds; round++) throttle.fail(client);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
            start.countDown();
            pool.shutdown();
            check(pool.awaitTermination(30, TimeUnit.SECONDS), "并发失败线程都在 30 秒内结束（没有死锁）");
        } finally {
            pool.shutdownNow();
        }
        check(failure.get() == null, "并发记失败不抛异常：" + failure.get());
        check(throttle.failures(client) == threads * rounds,
                threads + " 线程 × " + rounds + " 次并发失败一次不丢：" + throttle.failures(client));
    }

    /** 容量上限：塞进去的客户端远超上限时，表停在上限、淘汰最久没活动的那个。 */
    private static void capacity() {
        AtomicLong now = new AtomicLong(T0);
        int limit = 16;
        Throttle throttle = new Throttle(now::get, Throttle.MAX_FAILURES, Throttle.COOLDOWN_MILLIS,
                Throttle.WINDOW_MILLIS, limit);
        int total = limit * 3;
        for (int i = 0; i < total; i++) {
            throttle.fail("10.0.0." + i);
            now.addAndGet(1);      // 每来一个客户端时间走一格，淘汰顺序才是确定的
        }
        check(throttle.size() == limit, "表大小停在上限（" + throttle.size() + " / " + limit + "）");
        check(throttle.failures("10.0.0.0") == 0, "最旧的客户端被淘汰");
        check(throttle.failures("10.0.0." + (total - 1)) == 1, "最新的客户端还在");
    }

    /** X-Forwarded-For：默认一概不信；信任打开且来源受信时，取最右边那一跳不受信的地址。 */
    private static void forwardedFor() {
        String spoofed = "1.2.3.4";
        check(WebAuthFilter.clientKey("203.0.113.7", spoofed, false, List.of()).equals("203.0.113.7"),
                "默认不信任 X-Forwarded-For：伪造的头不影响限流键");
        check(WebAuthFilter.clientKey("203.0.113.7", spoofed, true, List.of()).equals("203.0.113.7"),
                "信任打开但来源不是本机/白名单，照样不看这个头");
        check(WebAuthFilter.clientKey("127.0.0.1", spoofed, true, List.of()).equals(spoofed),
                "本机代理 + 信任打开：用头里的客户端地址");
        check(WebAuthFilter.clientKey("::1", "2001:db8::1", true, List.of()).equals("2001:db8::1"),
                "IPv6 客户端地址同样认");
        check(WebAuthFilter.clientKey("::ffff:127.0.0.1", spoofed, true, List.of()).equals(spoofed),
                "双栈套接字把 IPv4 报成 ::ffff:127.x 时照旧算本机");
        check(WebAuthFilter.clientKey("192.168.1.5", spoofed, true, List.of("192.168.1.5")).equals(spoofed),
                "白名单里的代理发来的头才采信");
        check(WebAuthFilter.clientKey("192.168.1.6", spoofed, true, List.of("192.168.1.5")).equals("192.168.1.6"),
                "不在白名单里的代理照旧按它自己算");
        check(WebAuthFilter.clientKey("127.0.0.1", "198.51.100.9, 192.168.1.5", true, List.of("192.168.1.5"))
                .equals("198.51.100.9"), "取最右边那一跳不受信的地址（最后一跳可信值）");
        check(WebAuthFilter.clientKey("127.0.0.1", "198.51.100.9, 127.0.0.1", true, List.of())
                .equals("198.51.100.9"), "右边是受信代理就继续往左看");
        check(WebAuthFilter.clientKey("127.0.0.1", "not-an-ip", true, List.of()).equals("127.0.0.1"),
                "格式不对的跳整条头都不信，退回直接连接地址");
        check(WebAuthFilter.clientKey("127.0.0.1", "1.2.3.4, <script>", true, List.of()).equals("127.0.0.1"),
                "掺了别的东西的头不采信");
        check(WebAuthFilter.clientKey("127.0.0.1", "1.2.3.4".repeat(80), true, List.of()).equals("127.0.0.1"),
                "超长头不采信（防内存放大）");
        check(WebAuthFilter.clientKey("127.0.0.1",
                        "1.1.1.1, 2.2.2.2, 3.3.3.3, 4.4.4.4, 5.5.5.5, 6.6.6.6, 7.7.7.7, 8.8.8.8, 9.9.9.9",
                        true, List.of()).equals("127.0.0.1"),
                "跳数过多的头不采信");
        check(!WebAuthFilter.clientKey("127.0.0.1.evil", spoofed, true, List.of()).equals(spoofed),
                "长得像回环但不是地址的来源不受信（不做前缀匹配），伪造的头也不采信");
        check(WebAuthFilter.clientKey(null, spoofed, true, List.of()).equals(WebAuthFilter.UNKNOWN_CLIENT),
                "拿不到连接地址时用固定键");
        // 落到限流上：伪造的头不会把别人的桶填满（受害者照旧能登录）。
        Throttle throttle = new Throttle(() -> T0);
        String attacker = WebAuthFilter.clientKey("203.0.113.7", "198.51.100.9", false, List.of());
        for (int i = 0; i < 10; i++) WebAuthFilter.decide(TOKEN, WRONG, attacker, throttle);
        check(throttle.blocked(attacker), "伪造头的那一方自己被限流");
        check(!throttle.blocked("198.51.100.9"), "伪造的头没有把计数记到受害地址上");
        check(WebAuthFilter.decide(TOKEN, TOKEN, "198.51.100.9", throttle) == WebAuthFilter.ALLOW,
                "受害地址照旧能登录");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

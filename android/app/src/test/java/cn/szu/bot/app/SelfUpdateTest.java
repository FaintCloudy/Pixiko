package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link SelfUpdate}（下载 → 断点续传 → sha256 校验）的端到端断言。
 *
 * <p>用 {@link FakeHttpServer} 起一个<b>只监听回环的本地假服务</b>，按服务端契约返回 APK 字节
 * （{@code Content-Type: application/vnd.android.package-archive} + {@code Content-Length}
 * + {@code X-Pixiko-Sha256} + 支持 {@code Range}）。这不是 mock —— 数据真的走了一遍
 * {@link java.net.HttpURLConnection} 的读写，所以「下载进度推进到 100%」「续传真的发了 Range」
 * 都是被真的量到的，而不是靠假设。
 *
 * <p>覆盖到的四件事（正是任务书要求逐条验证的）：
 * <ol>
 *   <li>正常下载 + 进度回调单调推进到 100%；</li>
 *   <li>服务端中途掐断 → 下一次尝试带 {@code Range} 续传，最终拼出哈希正确的文件；</li>
 *   <li>服务端给的内容与 sha256 不符 → <b>拒绝</b>、报错、删掉坏文件（不留垃圾）；</li>
 *   <li>本地已有校验通过的文件 → 不再下载（复用）。</li>
 * </ol>
 */
public class SelfUpdateTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** 把 Log 出口换成内存记录器（否则 android.util.Log 是 stub，一调就抛）。 */
    @Rule
    public final CapturedLogRule logs = new CapturedLogRule();

    private FakeHttpServer server;
    private byte[] body;

    @Before
    public void startFakeServer() throws Exception {
        logs.install();
        body = Sha256Test.fakeApkBytes(200_000);
        server = new FakeHttpServer();
        server.serveApkContract(body, "/api/app/apk");
    }

    @After
    public void stopFakeServer() {
        if (server != null) server.close();
    }

    private String apkUrl() {
        return server.baseUrl() + "/api/app/apk";
    }

    private UpdateInfo info() {
        // 用真的解析器构造，顺带证明「解析出来的字段能直接喂给下载器」。
        return UpdateInfo.parse("{\"version\":\"1.7.0\",\"versionCode\":170,"
                + "\"sizeBytes\":" + body.length + ","
                + "\"sha256\":\"" + server.payloadSha256() + "\","
                + "\"apkUrl\":\"" + apkUrl() + "\","
                + "\"channel\":\"dev\"}");
    }

    private List<String> rangeHeaders() {
        List<String> out = new ArrayList<>();
        for (FakeHttpServer.Request request : server.requests()) out.add(request.header("Range"));
        return out;
    }

    // ---------------------------------------------------------------- 1) 正常下载 + 进度

    @Test
    public void downloadsVerifiesAndReportsMonotonicProgress() throws Exception {
        File dir = temporary.newFolder("update");
        final List<long[]> progress = Collections.synchronizedList(new ArrayList<>());

        SelfUpdate.Result result = SelfUpdate.download(info(), dir,
                (downloaded, total) -> progress.add(new long[]{downloaded, total}));

        assertTrue("下载 + 校验应该成功：" + result.message, result.ok);
        assertNotNull(result.apk);
        assertTrue("成品文件必须存在", result.apk.isFile());
        assertEquals("字节数必须和服务端给的一致", body.length, result.apk.length());
        assertEquals("算出来的 sha256 必须就是服务端那个", server.payloadSha256(), result.sha256);
        assertEquals(server.payloadSha256(), Sha256.of(result.apk));
        assertTrue("校验通过的成品必须以 .verified.apk 结尾（这个后缀就是「已验过」的不变量）",
                result.apk.getName().endsWith(".verified.apk"));

        assertFalse("必须有进度回调", progress.isEmpty());
        long previous = -1;
        for (long[] point : progress) {
            assertTrue("进度不许倒退：" + previous + " → " + point[0], point[0] >= previous);
            previous = point[0];
        }
        long[] last = progress.get(progress.size() - 1);
        assertEquals("最后一次进度必须等于总字节数", body.length, last[0]);
        assertEquals("总长度必须来自 Content-Length", body.length, last[1]);
        assertTrue("至少要报过 2 次（不是一步到位）", progress.size() >= 2);
        System.out.println("[SelfUpdateTest] 进度回调 " + progress.size() + " 次，末次="
                + last[0] + "/" + last[1]);

        File[] leftovers = dir.listFiles((d, name) -> name.endsWith(".part"));
        assertTrue("不该留下 .part 半成品", leftovers == null || leftovers.length == 0);
        assertEquals("第一次请求不该带 Range", null, rangeHeaders().get(0));
    }

    // ---------------------------------------------------------------- 2) 断点续传

    @Test
    public void resumesWithRangeHeaderAfterServerCutsTheConnection() throws Exception {
        File dir = temporary.newFolder("resume");
        // 缺口（cut）必须比「socket 发送缓冲 + 客户端接收缓冲」大，否则那一段会全部滞留在内核缓冲里，
        // 一开 SO_LINGER 就被 RST 一起丢掉，客户端一个字节都收不到（实测：缺口 60000 时客户端
        // 收到 0 字节）。改成 2MB 的包、缺口 400KB，保证确实有一段字节上了线再被掐断。
        body = Sha256Test.fakeApkBytes(2 * 1024 * 1024);
        server.serveApkContract(body, "/api/app/apk");
        server.truncateFirstResponseAt(400_000);

        SelfUpdate.Result result = SelfUpdate.download(info(), dir, null);

        assertTrue("断点续传最终必须拼出正确的包：" + result.message, result.ok);
        assertEquals(body.length, result.apk.length());
        assertEquals("拼出来的文件哈希必须与服务端一致（证明续传的字节拼对了）",
                server.payloadSha256(), Sha256.of(result.apk));

        List<String> ranges = rangeHeaders();
        System.out.println("[SelfUpdateTest] Range 请求头序列 = " + ranges);
        System.out.println("[SelfUpdateTest] 全部请求 = " + server.requests());
        logs.dump("断点续传过程");
        assertEquals("应该正好两次请求：第一次断在半路，第二次续传完成，实际=" + ranges, 2, ranges.size());
        assertEquals("第一次不该带 Range（本地什么都没有）", null, ranges.get(0));
        assertTrue("第二次必须带 Range 续传，且起点 = 第一次真正收到的字节数，实际=" + ranges.get(1),
                ranges.get(1) != null && ranges.get(1).startsWith("bytes="));
        long resumeAt = Long.parseLong(ranges.get(1).substring("bytes=".length()).replace("-", ""));
        assertTrue("续传起点必须落在掐断处附近（0 < N < 总长），实际=" + resumeAt,
                resumeAt > 0 && resumeAt < body.length);
        assertFalse("第一次是网络断，不该说成 sha256 校验失败", logs.anyContains("sha256 校验失败"));
        assertTrue("第一次失败要记「保留已下到的字节、下次续传」，实际日志=" + logs.lines(),
                logs.anyContains("下次续传"));
    }

    // ---------------------------------------------------------------- 3) 哈希不符 → 拒绝安装

    @Test
    public void refusesToInstallWhenSha256DoesNotMatchAndDeletesTheBadFile() throws Exception {
        File dir = temporary.newFolder("mismatch");
        // 服务端发另一份「长度一样、内容不同」的包：这样 sizeBytes 对得上，
        // 唯一能发现问题的手段就是 sha256 —— 正好验证校验真的在起作用。
        byte[] wrong = new byte[body.length];
        for (int i = 0; i < wrong.length; i++) wrong[i] = (byte) ((i * 17 + 3) & 0xFF);
        server.serveApkContract(wrong, "/api/app/apk");

        UpdateInfo info = UpdateInfo.parse("{\"version\":\"1.7.0\",\"versionCode\":170,"
                + "\"sizeBytes\":" + body.length + ","
                + "\"sha256\":\"" + FakeHttpServer.sha256(body) + "\","   // 期望的是**正确**那份的哈希
                + "\"apkUrl\":\"" + apkUrl() + "\"}");
        assertNotNull(info);

        SelfUpdate.Result result = SelfUpdate.download(info, dir, null);

        assertFalse("sha256 不符就绝不允许成功", result.ok);
        assertEquals("失败时不许给出可安装的文件", null, result.apk);
        assertTrue("错误信息要说明是 sha256 校验失败，实际=" + result.message,
                result.message.contains("sha256"));
        System.out.println("[SelfUpdateTest] 哈希不符时的报错 = " + result.message);

        File[] files = dir.listFiles();
        assertTrue("坏文件与半成品都必须被删掉，实际残留=" + java.util.Arrays.toString(files),
                files == null || files.length == 0);
        assertEquals("重试 3 次都失败 → 应该请求过 3 次", 3, server.requests().size());
    }

    // ---------------------------------------------------------------- 4) 复用已校验文件

    @Test
    public void reusesAlreadyVerifiedFileInsteadOfDownloadingAgain() throws Exception {
        File dir = temporary.newFolder("reuse");
        UpdateInfo info = info();

        SelfUpdate.Result first = SelfUpdate.download(info, dir, null);
        assertTrue(first.ok);
        int requestsAfterFirst = server.requests().size();

        SelfUpdate.Result second = SelfUpdate.download(info, dir, null);
        assertTrue("第二次应该复用本地已校验的文件", second.ok);
        assertEquals("复用时不该再发请求", requestsAfterFirst, server.requests().size());
        assertEquals("复用返回的是同一个文件", first.apk.getAbsoluteFile(), second.apk.getAbsoluteFile());
        assertTrue("消息里要说明是复用：" + second.message, second.message.contains("复用"));
    }

    // ---------------------------------------------------------------- 其它守卫

    @Test
    public void rejectsHtmlErrorPageEvenWhenServerSays200() throws Exception {
        File dir = temporary.newFolder("html");
        server.serveApkContract(body, "/api/app/apk");
        server.contentType("text/html; charset=utf-8");

        SelfUpdate.Result result = SelfUpdate.download(info(), dir, null);

        assertFalse("返回的是网页而不是安装包 → 必须失败", result.ok);
        assertTrue("要说清是内容类型不对，实际=" + result.message, result.message.contains("不是安装包"));
    }

    @Test
    public void refusesDownloadWithoutSha256() throws Exception {
        File dir = temporary.newFolder("nohash");
        UpdateInfo noHash = UpdateInfo.parse("{\"versionCode\":170,\"apkUrl\":\"" + apkUrl() + "\"}");
        assertNotNull(noHash);

        SelfUpdate.Result result = SelfUpdate.download(noHash, dir, null);

        assertFalse("没有 sha256 就不该下载", result.ok);
        assertTrue(result.message.contains("sha256"));
        assertEquals("连请求都不该发出去", 0, server.requests().size());
        assertFalse("不该建出文件", new File(dir, SelfUpdate.targetName(noHash) + ".part").exists());
    }

    @Test
    public void refusesDownloadWhenServerSha256IsMalformed() throws Exception {
        File dir = temporary.newFolder("badhash");
        UpdateInfo bad = UpdateInfo.parse("{\"versionCode\":170,\"apkUrl\":\"" + apkUrl()
                + "\",\"sha256\":\"deadbeef\"}");
        assertNotNull(bad);

        SelfUpdate.Result result = SelfUpdate.download(bad, dir, null);

        assertFalse("短哈希必须被拒（否则等于没校验）", result.ok);
        assertTrue("要说清是格式问题：" + result.message, result.message.contains("64 位十六进制"));
        assertEquals(0, server.requests().size());
    }

    @Test
    public void parsesContentRangeStart() {
        assertEquals(100L, SelfUpdate.parseContentRangeStart("bytes 100-999/1000"));
        assertEquals(0L, SelfUpdate.parseContentRangeStart("bytes 0-99/100"));
        assertEquals(-1L, SelfUpdate.parseContentRangeStart(null));
        assertEquals(-1L, SelfUpdate.parseContentRangeStart("garbage"));
    }

    @Test
    public void targetNameCarriesVersionAndCodeAndIsFilesystemSafe() {
        UpdateInfo info = UpdateInfo.parse("{\"version\":\"1.7.0-beta/2\",\"versionCode\":170}");
        assertNotNull(info);
        String name = SelfUpdate.targetName(info);
        assertEquals("pixiko-update-1.7.0-beta_2-170", name);
        assertFalse("不许出现路径分隔符", name.contains("/"));
    }

    @Test
    public void cleanWorkFilesRemovesPartAndVerifiedFilesOnly() throws Exception {        File dir = temporary.newFolder("cleanup");
        File part = new File(dir, "pixiko-update-1.7.0-170.part");
        File verified = new File(dir, "pixiko-update-1.7.0-170.verified.apk");
        File unrelated = new File(dir, "keep-me.txt");
        assertTrue(part.createNewFile());
        assertTrue(verified.createNewFile());
        assertTrue(unrelated.createNewFile());

        SelfUpdate.cleanWorkFiles(dir);

        assertFalse("半成品要清", part.exists());
        assertFalse("已校验的包也要清（新版本已经起来了）", verified.exists());
        assertTrue("不相干的文件不许动", unrelated.exists());
    }

    @Test
    public void fileNameAndLengthAreLoggedForTroubleshooting() throws Exception {
        // 只是把「下载用了哪个 URL」记进 System.out，方便报告里贴交互日志。
        File dir = temporary.newFolder("log");
        SelfUpdate.Result result = SelfUpdate.download(info(), dir, null);
        assertTrue(result.ok);
        for (FakeHttpServer.Request request : server.requests()) {
            System.out.println("[SelfUpdateTest] 假服务收到：" + request);
        }
        assertEquals("apkUrl 里的路径必须被原样请求", "/api/app/apk", server.requests().get(0).path);
        assertEquals("GET", server.requests().get(0).method);
        assertEquals("契约是免令牌 → 下载也不该带 Authorization",
                null, server.requests().get(0).header("Authorization"));
        System.out.println("[SelfUpdateTest] 下载产物 = " + result.apk.getName()
                + "，" + result.apk.length() + " 字节，sha256=" + result.sha256);
    }
}

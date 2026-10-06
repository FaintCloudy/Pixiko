package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「点下载更新时，本地那份缓存到底算不算『已经下载好』」的回归断言。
 *
 * <p>用户报的原话：「点击下载更新后不会下载而是直接安装<b>老的</b>」。根因是设置页里那条盲装短路
 * —— 只要 {@code UpdatePrefs} 记录的文件还在就直接进安装流程，而 {@code cacheDir/update} 里
 * 刻意残留着<b>上一版</b>下好的包。修法是把判据收进 {@link UpdateCache#planFor}（本类的主角），
 * 并在 {@link UpdateInstaller#checkInstallable} 那个唯一安装出口再核一次。
 *
 * <p>本类覆盖 6 件必须成立的事（对应报告里的验证清单）：
 * <ol>
 *   <li>缓存里是旧包（哈希 ≠ 服务端当前 announced）→ 判定为<b>重新下载</b>、不返回可安装文件，旧包被删；</li>
 *   <li>缓存里的包 = 服务端当前 announced（名字 + sha256 都对）→ 才允许走「直接安装」；</li>
 *   <li>版本变化（缓存记录的 sha256 ≠ 新 announced）→ 旧文件被删（当前版本的 .part 保留给断点续传）；</li>
 *   <li>sha256 不符的包<b>永不</b>能进安装 Intent（闸门拒绝 + 源码里只有一个安装出口且在闸门之后）；</li>
 *   <li>versionCode 不比本机大 → 不安装（既有的 {@code isNewerThan} 判据不许退化）；</li>
 *   <li>「重新下载」这条路上进度回调真的被调用过（对着本地假服务真下了一遍）。</li>
 * </ol>
 *
 * <p><b>没有真机（{@code adb devices} 为空）</b>，所以覆盖到的是「纯判据 + 真实 HTTP 下载 + 构建期
 * 源码结构检查」这三层；「系统安装器真的被拉起、APK 真的被替换」这一层本类覆盖不到，报告里如实标注。
 */
public class UpdateCacheTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** 把 Log 出口换成内存记录器（否则 android.util.Log 是 stub，一调就抛）。 */
    @Rule
    public final CapturedLogRule logs = new CapturedLogRule();

    @Before
    public void installLogSink() {
        logs.install();
    }

    // ---------------------------------------------------------------- 工具

    private static File write(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    /** 与 {@link Sha256Test#fakeApkBytes} 同样长度、但内容不同的一份「毒包」（只改一个字节就够）。 */
    private static byte[] tamperedBytes(int size) {
        byte[] bytes = Sha256Test.fakeApkBytes(size);
        bytes[size / 2] = (byte) (bytes[size / 2] ^ 0x5A);
        return bytes;
    }

    /** 造一份「服务端当前 announced」的更新信息（sha256 就是那份文件的真实哈希）。 */
    private static UpdateInfo announced(String version, int versionCode, File apk, String apkUrl) {
        String sha;
        try {
            sha = Sha256.of(apk);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        UpdateInfo info = UpdateInfo.parse("{\"version\":\"" + version + "\",\"versionCode\":" + versionCode
                + ",\"sizeBytes\":" + apk.length()
                + ",\"sha256\":\"" + sha + "\""
                + ",\"apkUrl\":\"" + apkUrl + "\""
                + ",\"channel\":\"dev\"}");
        assertNotNull("测试自己造的 JSON 必须能被解析", info);
        return info;
    }

    /** 按 {@link SelfUpdate} 的命名规则造「上一版下好的包」。 */
    private File cachedFile(File dir, String version, int versionCode, byte[] bytes) throws IOException {
        return write(new File(dir, "pixiko-update-" + version + "-" + versionCode + ".verified.apk"), bytes);
    }

    // ------------------------------------------- 1) 旧包 → 必须重新下载（不许直接装）

    @Test
    public void staleCachedApkIsDeletedAndDownloadIsRequired() throws Exception {
        File dir = temporary.newFolder("stale");
        // 上一版（1.6.1）下好的包还躺在 cacheDir/update 里 —— 这就是用户装上「老的」的那个包。
        File stale = cachedFile(dir, "1.6.1", 161, Sha256Test.fakeApkBytes(4096));
        File announcedApk = write(new File(dir, "server-1.6.2.apk"), Sha256Test.fakeApkBytes(8192));
        UpdateInfo info = announced("1.6.2", 162, announcedApk, "http://127.0.0.1:1/api/app/apk");

        UpdateCache.Decision plan = UpdateCache.planFor(info, stale, 160);

        System.out.println("[UpdateCacheTest] 旧缓存 → " + plan);
        assertTrue("哈希对不上就必须重新下载，实际=" + plan.verdict, plan.isDownload());
        assertFalse("绝不允许把旧包当成「已下载好」直接装", plan.isInstall());
        assertNull("重新下载这条路上不许给出任何可安装文件", plan.apk);
        assertTrue("旧包要当场删掉", plan.deletedStale);
        assertFalse("旧包必须真的没了", stale.exists());
    }

    // ------------------------------------------- 2) 名字 + 哈希都对上 → 才允许直接安装

    @Test
    public void onlyAHashMatchingCachedApkMayBeInstalledDirectly() throws Exception {
        File dir = temporary.newFolder("match");
        File poisonDir = temporary.newFolder("poison");
        byte[] serverBytes = Sha256Test.fakeApkBytes(6000);
        File announcedApk = write(new File(dir, "server.apk"), serverBytes);
        UpdateInfo info = announced("1.6.2", 162, announcedApk, "http://127.0.0.1:1/api/app/apk");
        // 名字就是当前版本的目标名，内容也确实是服务端当前那份。
        File good = write(new File(dir, SelfUpdate.targetName(info) + ".verified.apk"), serverBytes);
        // 反例：**同样长度**、内容只差一个字节、名字也是当前版本 —— 唯一能发现问题的判据就是 sha256。
        File poisoned = write(new File(poisonDir, SelfUpdate.targetName(info) + ".verified.apk"),
                tamperedBytes(6000));
        assertEquals("反例必须与正例等长（否则 sizeBytes 就能发现问题，证明不了 sha256 在起作用）",
                good.length(), poisoned.length());

        UpdateCache.Decision ok = UpdateCache.planFor(info, good, 160);
        assertTrue("名字 + sha256 都对上才允许直接安装：" + ok.message, ok.isInstall());
        assertEquals(good.getAbsoluteFile(), ok.apk.getAbsoluteFile());
        assertTrue("对得上的包不许删", good.exists());

        UpdateCache.Decision bad = UpdateCache.planFor(info, poisoned, 160);
        assertFalse("sha256 不符的包永远不许走「直接安装」", bad.isInstall());
        assertNull(bad.apk);
        assertFalse("哈希不符的缓存包要删掉（免得下次又被当成已下载好）", poisoned.exists());
    }

    // ------------------------------------------- 3) 版本变化 → 清掉旧文件（.part 保留）

    @Test
    public void versionChangePurgesOldFilesAndKeepsTheCurrentVersionsWorkFiles() throws Exception {
        File dir = temporary.newFolder("purge");
        File announcedApk = write(new File(dir, "server.apk"), Sha256Test.fakeApkBytes(7000));
        UpdateInfo info = announced("1.6.2", 162, announcedApk, "http://127.0.0.1:1/api/app/apk");

        File oldVerified = cachedFile(dir, "1.6.1", 161, Sha256Test.fakeApkBytes(4096));
        File oldPart = write(new File(dir, "pixiko-update-1.6.1-161.part"), Sha256Test.fakeApkBytes(2048));
        File currentPart = write(new File(dir, SelfUpdate.targetName(info) + ".part"), Sha256Test.fakeApkBytes(1024));
        File currentVerified = write(new File(dir, SelfUpdate.targetName(info) + ".verified.apk"),
                Sha256Test.fakeApkBytes(7000));
        File unrelated = write(new File(dir, "keep-me.txt"), "x".getBytes(StandardCharsets.UTF_8));

        UpdateCache.Purge purge = UpdateCache.purgeStale(dir, info);

        System.out.println("[UpdateCacheTest] 版本变化清理 → " + purge + "，剩余="
                + java.util.Arrays.toString(dir.listFiles()));
        assertEquals("上一版的 .verified.apk 与 .part 都该删（旧包就是事故主角）", 2, purge.deletedFiles);
        assertFalse("上一版的包必须被删", oldVerified.exists());
        assertFalse("上一版的半成品必须被删", oldPart.exists());
        assertTrue("当前版本的半成品要留着（断点续传靠它）", currentPart.exists());
        assertTrue("当前版本且哈希核过的成品要留着（用户可能只是想再点一次安装）", currentVerified.exists());
        assertTrue("不相干的文件一个都不许动", unrelated.exists());
    }

    // ------------------------------------------- 4)+5) 安装闸门：哈希 / 版本号

    @Test
    public void installGateRefusesMismatchedHashAndNonNewerVersionCode() {
        String shaA = FakeHttpServer.sha256(Sha256Test.fakeApkBytes(4096));
        String shaB = FakeHttpServer.sha256(Sha256Test.fakeApkBytes(4097));

        UpdateInstaller.Gate mismatch = UpdateInstaller.checkInstallable(shaA, shaB, 162, 160);
        assertFalse("sha256 不符的包永不进入安装 Intent", mismatch.allowed);
        assertTrue("拒绝原因要说清是 sha256：" + mismatch.reason, mismatch.reason.contains("sha256"));

        assertFalse("服务端没给合法 sha256 时也要拒绝",
                UpdateInstaller.checkInstallable("deadbeef", shaB, 162, 160).allowed);

        UpdateInstaller.Gate sameCode = UpdateInstaller.checkInstallable(shaA, shaA, 160, 160);
        assertFalse("versionCode 不比本机大就不安装：" + sameCode.reason, sameCode.allowed);
        assertFalse("更旧的包更不许装", UpdateInstaller.checkInstallable(shaA, shaA, 159, 160).allowed);

        assertTrue("读不到 APK 里的 versionCode（-1）时按约定只信 sha256，不因此拒绝",
                UpdateInstaller.checkInstallable(shaA, shaA, -1, 160).allowed);
        assertTrue("哈希对 + versionCode 更大 → 放行",
                UpdateInstaller.checkInstallable(shaA, shaA, 162, 160).allowed);
    }

    @Test
    public void serverReportingANonNewerVersionNeverInstallsEvenWithAMatchingCache() throws Exception {
        File dir = temporary.newFolder("uptodate");
        // 服务端说的就是本机这一版（1.6.0 / 160），而本地恰好有它的一个哈希核过的包。
        File sameApk = write(new File(dir, "server-1.6.0.apk"), Sha256Test.fakeApkBytes(5000));
        UpdateInfo info = announced("1.6.0", 160, sameApk, "http://127.0.0.1:1/api/app/apk");
        File cached = write(new File(dir, SelfUpdate.targetName(info) + ".verified.apk"), Sha256Test.fakeApkBytes(5000));

        UpdateCache.Decision plan = UpdateCache.planFor(info, cached, 160);

        System.out.println("[UpdateCacheTest] 已是最新 → " + plan);
        assertTrue("versionCode 不比本机大 → 不安装（既有判据不许退化）", plan.isUpToDate());
        assertFalse(plan.isInstall());
        assertFalse(plan.isDownload());
        assertTrue("这时没必要删包（本机就是这一版，用户也许会重装）", cached.exists());
        // 反证：同一份包、只是本机更旧（159）时它是「可安装」的 —— 说明上面的 UP_TO_DATE
        // 是版本判据起作用，而不是因为这个包本身有问题。
        assertTrue("同一份包在本机更旧时应该判为可安装（说明包本身是好的）",
                UpdateCache.planFor(info, cached, 159).isInstall());
    }

    // ------------------------------------------- 6) 重新下载这条路上进度真的推进 + 真下了一遍

    @Test
    public void staleCacheLeadsToARealDownloadWithProgress() throws Exception {
        File dir = temporary.newFolder("redownload");
        File stale = cachedFile(dir, "1.6.1", 161, Sha256Test.fakeApkBytes(4096));

        byte[] fresh = Sha256Test.fakeApkBytes(200_000);
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.serveApkContract(fresh, "/api/app/apk");
            File announcedApk = write(new File(dir, "server.apk"), fresh);
            UpdateInfo info = announced("1.6.2", 162, announcedApk, server.baseUrl() + "/api/app/apk");

            // 与设置页完全同一条判断：旧缓存 → DOWNLOAD。
            UpdateCache.Decision plan = UpdateCache.planFor(info, stale, 160);
            assertTrue(plan.isDownload());

            final List<long[]> progress = Collections.synchronizedList(new ArrayList<>());
            SelfUpdate.Result result = SelfUpdate.download(info, dir,
                    (downloaded, total) -> progress.add(new long[]{downloaded, total}));

            assertTrue("重新下载必须成功：" + result.message, result.ok);
            assertEquals("下到的必须是服务端当前那一份", info.sha256, Sha256.of(result.apk));
            assertEquals("文件名必须带当前 versionCode", SelfUpdate.targetName(info) + ".verified.apk",
                    result.apk.getName());
            assertFalse("旧包必须已经被清掉", stale.exists());
            assertFalse("重新下载这条路上进度回调至少要响一次（否则就是「根本没下」）", progress.isEmpty());
            long[] last = progress.get(progress.size() - 1);
            assertEquals("进度要走到文件末尾", fresh.length, last[0]);
            assertTrue("假服务端必须真的被请求过（证明不是「跳过下载直接装」）", server.requests().size() >= 1);
            System.out.println("[UpdateCacheTest] 重新下载：请求 " + server.requests().size()
                    + " 次，进度回调 " + progress.size() + " 次，末次=" + last[0] + "/" + last[1]);
        }
    }

    // ------------------------------------------- 结构：安装出口只有一个，且在闸门之后

    @Test
    public void thereIsExactlyOneInstallExitAndItSitsBehindTheGate() throws Exception {
        String source = read("src/main/java/cn/szu/bot/app/SettingsActivity.java");

        int exits = count(source, "UpdateInstaller.buildInstallIntent(");
        assertEquals("把包装进系统安装器的地方只能有一个（多了就会有绕过闸门的暗门）", 1, exits);

        String install = body(source, "private void promptInstall(");
        assertTrue("安装出口必须先算本地那份的 sha256：" + install, install.contains("Sha256.of(apk)"));
        assertTrue("安装出口必须过 checkInstallable 闸门", install.contains("UpdateInstaller.checkInstallable("));
        assertTrue("被闸门拒绝时不许走到安装：" + install, install.contains("if (!gate.allowed)"));
        assertTrue("闸门必须在组装安装 Intent 之前",
                install.indexOf("checkInstallable(") < install.indexOf("buildInstallIntent("));

        String download = body(source, "private void beginDownload(");
        assertTrue("「点下载更新」必须先过 UpdateCache.planFor 再决定干什么",
                download.contains("UpdateCache.planFor("));
        assertTrue("只有 plan 说 INSTALL（且是设置页按钮那条入口）才允许直接安装：" + download,
                download.contains("allowCachedInstall && plan.isInstall()"));
        assertTrue("versionCode 不比本机大 → 什么都不做：" + download, download.contains("plan.isUpToDate()"));
        assertFalse("不许再有「文件存在就直接装」的盲装短路",
                download.contains("promptInstall(downloadedApk"));
    }

    // ---------------------------------------------------------------- 读源码的小工具

    private static Path appDir() {
        Path here = Paths.get("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("src/main/AndroidManifest.xml"))) return candidate;
            Path nested = candidate.resolve("app/src/main/AndroidManifest.xml");
            if (Files.isRegularFile(nested)) return candidate.resolve("app");
        }
        throw new IllegalStateException("找不到 app 模块目录，当前工作目录=" + here);
    }

    private static String read(String relative) throws IOException {
        Path path = appDir().resolve(relative);
        assertTrue("文件必须存在：" + path, Files.isRegularFile(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 取一个方法的方法体（从签名后第一个 {@code &#123;} 起按花括号配对）。 */
    private static String body(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue("源码里找不到这个方法：" + signature, at >= 0);
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return source.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("花括号不配对：" + signature);
    }

    private static int count(String text, String needle) {
        int hits = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            hits++;
            at = text.indexOf(needle, at + needle.length());
        }
        return hits;
    }
}

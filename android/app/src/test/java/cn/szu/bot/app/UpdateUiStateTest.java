package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设置页「一键更新」按钮 + 主界面横幅按钮<b>共用的那个状态机</b>（{@link UpdateUiState}）的断言。
 *
 * <p>用户的原话是「服务器设置里要加入直接的更新新版本按钮，而不是只能在横幅上安装」。所以这里钉死两件事：
 * <ol>
 *   <li><b>按钮文案必须与点下去真的干什么一致</b>：已是最新 → 禁用；查到 1.6.2 → 「更新到 1.6.2」
 *       （版本号来自 {@code /api/app/update}，不写死）；下载中 → 「下载中 37%」；已下载且校验通过 →
 *       「安装 1.6.2」；失败 → 「重试更新」+ 一行原因；没配服务器 → 禁用 + 提示；</li>
 *   <li><b>横幅与设置页同源</b>：两处的文案都由同一个 {@code State} 派生，所以不可能出现
 *       「横幅说下载、点进去却安装」这种自相矛盾。</li>
 * </ol>
 *
 * <p>覆盖分层（如实说）：状态机与文案、真实 HTTP 的「检查 → 下载 → 校验」链路、以及源码结构
 * （安装出口唯一、在闸门之后）都能在 JVM 上钉死；<b>没有真机（{@code adb devices} 为空）</b>，
 * 「系统安装器真的被拉起来」这一层覆盖不到。
 */
public class UpdateUiStateTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** 把 Log 出口换成内存记录器（否则 android.util.Log 是 stub，一调就抛）。 */
    @Rule
    public final CapturedLogRule logs = new CapturedLogRule();

    @Before
    public void installLogSink() {
        logs.install();
    }

    /** 造一份服务端应答形状的更新信息（version = 服务端报的版本号，绝不写死在代码里）。 */
    private static UpdateInfo info(String version, int versionCode, boolean installable) {
        String sha = FakeHttpServer.sha256(Sha256Test.fakeApkBytes(1024));
        UpdateInfo parsed = UpdateInfo.parse("{\"version\":\"" + version + "\",\"versionCode\":" + versionCode
                + ",\"sizeBytes\":1024"
                + ",\"sha256\":\"" + (installable ? sha : "") + "\""
                + ",\"apkUrl\":\"" + (installable ? "http://127.0.0.1:1/api/app/apk" : "") + "\""
                + ",\"channel\":\"dev\"}");
        assertNotNull(parsed);
        return parsed;
    }

    // ------------------------------------- ① ② ④ ⑥：按钮文案跟着状态走

    @Test
    public void oneClickButtonFollowsTheUpdateLifecycle() {
        // ⑥ 没配服务器地址 → 禁用 + 文案「先配置服务器地址」
        UpdateUiState.State noServer = UpdateUiState.derive(false, false, false, null, 160, false, false, 0, "");
        assertEquals(UpdateUiState.Phase.NO_SERVER, noServer.phase);
        assertFalse("没配服务器时按钮必须禁用", noServer.enabled);
        assertEquals(R.string.settings_oneclick_no_server, noServer.labelRes);

        // 配了服务器但这次还没查过 → 可点，文案「检查并更新」
        UpdateUiState.State unchecked = UpdateUiState.derive(true, false, false, null, 160, false, false, 0, "");
        assertEquals(UpdateUiState.Phase.UNCHECKED, unchecked.phase);
        assertTrue(unchecked.enabled);
        assertEquals(R.string.settings_oneclick_unchecked, unchecked.labelRes);

        // ① 查过了、服务端没有更新的版本（这时 info 仍然是 null，靠 checked 区分）→ 同样是「已是最新版本」且禁用
        UpdateUiState.State checkedNoInfo = UpdateUiState.derive(true, true, false, null, 160, false, false, 0, "");
        assertEquals(UpdateUiState.Phase.UP_TO_DATE, checkedNoInfo.phase);
        assertFalse("查到「没有更新」之后按钮必须禁用", checkedNoInfo.enabled);
        assertEquals(R.string.settings_oneclick_up_to_date, checkedNoInfo.labelRes);

        // ① 服务端报的 versionCode 不比本机大 → 禁用 + 「已是最新版本」（哪怕本地恰好有包可装）
        UpdateUiState.State upToDate = UpdateUiState.derive(true, true, false,
                info("1.6.0", 160, true), 160, true, false, 0, "");
        assertEquals(UpdateUiState.Phase.UP_TO_DATE, upToDate.phase);
        assertFalse("已是最新时按钮必须禁用", upToDate.enabled);
        assertEquals(R.string.settings_oneclick_up_to_date, upToDate.labelRes);

        // ② 查到新版 → 「更新到 1.6.2」，版本号取自服务端应答（换成 1.6.3 文案就跟着变）
        UpdateUiState.State available = UpdateUiState.derive(true, true, false,
                info("1.6.2", 162, true), 160, false, false, 0, "");
        assertEquals(UpdateUiState.Phase.AVAILABLE, available.phase);
        assertTrue(available.enabled);
        assertEquals(R.string.settings_oneclick_update_to, available.labelRes);
        assertEquals("版本号必须来自服务端那个 version", "1.6.2", available.labelArgs[0]);
        UpdateUiState.State next = UpdateUiState.derive(true, true, false,
                info("1.6.3", 163, true), 160, false, false, 0, "");
        assertEquals("版本号不许写死：换成 1.6.3，文案参数必须跟着变", "1.6.3", next.labelArgs[0]);
        assertNotEquals(available.labelArgs[0], next.labelArgs[0]);

        // 服务端没给可下载的包 → 按钮禁用，文案说明「暂无安装包」
        UpdateUiState.State noPackage = UpdateUiState.derive(true, true, false,
                info("1.6.2", 162, false), 160, false, false, 0, "");
        assertFalse("服务端没给安装包时按钮禁用", noPackage.enabled);
        assertEquals(R.string.settings_oneclick_no_package, noPackage.labelRes);

        // 下载中 → 「下载中 37%」（按钮上直接显示进度）
        UpdateUiState.State downloading = UpdateUiState.derive(true, true, false,
                info("1.6.2", 162, true), 160, false, true, 37, "");
        assertEquals(UpdateUiState.Phase.DOWNLOADING, downloading.phase);
        assertEquals(R.string.settings_oneclick_downloading, downloading.labelRes);
        assertEquals(37, downloading.labelArgs[0]);
        assertFalse("下载中不该让用户再点一次", downloading.enabled);

        // 已下载且 sha256 校验通过 → 「安装 1.6.2」
        UpdateUiState.State ready = UpdateUiState.derive(true, true, false,
                info("1.6.2", 162, true), 160, true, false, 100, "");
        assertEquals(UpdateUiState.Phase.READY_TO_INSTALL, ready.phase);
        assertTrue(ready.enabled);
        assertEquals(R.string.settings_oneclick_install, ready.labelRes);
        assertEquals("1.6.2", ready.labelArgs[0]);

        // ④(文案这一半) sha256 不符 → 失败态：按钮「重试更新」+ 一行原因
        UpdateUiState.State failed = UpdateUiState.derive(true, true, false,
                info("1.6.2", 162, true), 160, false, false, 0,
                "安装包的 sha256 与服务端当前版本不一致（算出来 aa…，服务端说 bb…），已拒绝安装。");
        assertEquals(UpdateUiState.Phase.FAILED, failed.phase);
        assertEquals(R.string.settings_oneclick_retry, failed.labelRes);
        assertTrue("失败后必须还能重试（按钮可点）", failed.enabled);
        assertTrue("必须带一行原因：" + failed.reason, failed.reason.contains("sha256"));
    }

    // ------------------------------------- ⑤ 横幅与设置页同源

    @Test
    public void bannerAndPanelLabelsComeFromTheSameState() {
        UpdateInfo info = info("1.6.2", 162, true);

        // 同一个 State：设置页说「更新到 1.6.2」，横幅说「下载更新」—— 两个字面不同但同源，
        // 因为横幅那句话的语义就是「去设置页走一遍更新流程」。
        UpdateUiState.State available = UpdateUiState.derive(true, true, false, info, 160, false, false, 0, "");
        assertEquals(R.string.settings_oneclick_update_to, available.labelRes);
        assertEquals(R.string.update_banner_action, available.bannerRes);

        // 本地已经有校验通过的包 → 两处同时变成「安装 1.6.2」（横幅不再说「下载」，避免文案与行为打架）
        UpdateUiState.State ready = UpdateUiState.derive(true, true, false, info, 160, true, false, 0, "");
        assertEquals(R.string.settings_oneclick_install, ready.labelRes);
        assertEquals("横幅必须跟着同一个状态变：已校验好就说「安装」", R.string.settings_oneclick_install, ready.bannerRes);
        assertEquals("1.6.2", ready.bannerArgs[0]);

        // 下载中 / 失败：两处也同步
        UpdateUiState.State downloading = UpdateUiState.derive(true, true, false, info, 160, false, true, 37, "");
        assertEquals(R.string.settings_oneclick_downloading, downloading.bannerRes);
        assertEquals(37, downloading.bannerArgs[0]);
        UpdateUiState.State failed = UpdateUiState.derive(true, true, false, info, 160, false, false, 0, "连不上服务器");
        assertEquals(R.string.settings_oneclick_retry, failed.bannerRes);

        // 「同一 state 改一次，两处都变」：把版本从 1.6.2 改成 1.6.3，面板与横幅的参数一起变
        UpdateUiState.State next = UpdateUiState.derive(true, true, false, info("1.6.3", 163, true),
                160, true, false, 0, "");
        assertEquals("1.6.3", next.labelArgs[0]);
        assertEquals("1.6.3", next.bannerArgs[0]);

        // 没配服务器：两处都不许出现「安装」这种谎话
        UpdateUiState.State noServer = UpdateUiState.derive(false, false, false, null, 160, false, false, 0, "");
        assertFalse(noServer.enabled);
        assertNotEquals(R.string.settings_oneclick_install, noServer.labelRes);
        assertNotEquals(R.string.settings_oneclick_install, noServer.bannerRes);
    }

    // ------------------------------------- ③ 一键：检查 → 下载（有进度）→ 校验 → 会产生安装 Intent

    @Test
    public void oneClickRunsCheckThenDownloadThenVerifyAndWouldInstall() throws Exception {
        File dir = temporary.newFolder("oneclick");
        byte[] body = Sha256Test.fakeApkBytes(150_000);

        try (FakeHttpServer apkServer = new FakeHttpServer();
             FakeHttpServer apiServer = new FakeHttpServer()) {
            apkServer.serveApkContract(body, "/api/app/apk");
            final String json = "{\"version\":\"1.6.2\",\"versionCode\":162,\"sizeBytes\":" + body.length
                    + ",\"sha256\":\"" + apkServer.payloadSha256() + "\""
                    + ",\"apkUrl\":\"" + apkServer.baseUrl() + "/api/app/apk\""
                    + ",\"channel\":\"dev\"}";
            apiServer.serve("/api/app/update", (request, response) -> {
                response.status(200);
                response.setBody(json.getBytes(StandardCharsets.UTF_8));
                response.set("Content-Type", "application/json");
            });

            // ---- 第 1 步：检查更新（一键更新的第一下就是它）----
            UpdateChecker.Outcome outcome = UpdateChecker.check(apiServer.baseUrl(), "", null);
            assertTrue("检查更新必须成功：" + outcome.message, outcome.ok);
            assertNotNull(outcome.info);
            assertEquals("POST", apiServer.requests().get(0).method);

            UpdateUiState.State available = UpdateUiState.derive(true, true, false,
                    outcome.info, 160, false, false, 0, "");
            assertTrue("查到新版 → 按钮可点", available.enabled);
            assertEquals(R.string.settings_oneclick_update_to, available.labelRes);
            assertEquals("1.6.2", available.labelArgs[0]);

            // ---- 第 2 步：下载 + sha256 校验（进度回调必须真的响）----
            UpdateCache.Decision plan = UpdateCache.planFor(outcome.info, null, 160);
            assertTrue("本地没有包 → 必须下载", plan.isDownload());
            final List<long[]> progress = Collections.synchronizedList(new ArrayList<>());
            SelfUpdate.Result result = SelfUpdate.download(outcome.info, dir,
                    (downloaded, total) -> progress.add(new long[]{downloaded, total}));
            assertTrue("下载 + 校验必须成功：" + result.message, result.ok);
            assertEquals("下到的必须就是服务端报的那一份", outcome.info.sha256, Sha256.of(result.apk));
            assertFalse("一键更新这条路上进度回调至少要响一次：", progress.isEmpty());
            assertEquals("进度要走到文件末尾（按钮上的「下载中 x%」就是它算的）",
                    body.length, progress.get(progress.size() - 1)[0]);

            // ---- 第 3 步：会产生安装 Intent 吗？闸门必须放行（真机上这一步就是 buildInstallIntent）----
            UpdateInstaller.Gate gate = UpdateInstaller.checkInstallable(outcome.info.sha256,
                    Sha256.of(result.apk), UpdateInstaller.readApkVersionCode(null, result.apk), 160);
            assertTrue("走完 检查→下载→校验 之后闸门必须放行：" + gate.reason, gate.allowed);
            UpdateUiState.State ready = UpdateUiState.derive(true, true, false,
                    outcome.info, 160, true, false, 100, "");
            assertEquals(R.string.settings_oneclick_install, ready.labelRes);

            // ---- ④ 反面：sha256 不符 → 闸门拒绝（不会产生安装 Intent）+ 按钮「重试更新」----
            UpdateInstaller.Gate rejected = UpdateInstaller.checkInstallable(outcome.info.sha256,
                    FakeHttpServer.sha256(Sha256Test.fakeApkBytes(150_001)), 162, 160);
            assertFalse("sha256 不符时绝不产生安装 Intent", rejected.allowed);
            UpdateUiState.State failed = UpdateUiState.derive(true, true, false,
                    outcome.info, 160, false, false, 0, rejected.reason);
            assertEquals(R.string.settings_oneclick_retry, failed.labelRes);
            assertTrue(failed.reason.contains("sha256"));
        }
    }

    // ------------------------------------- 结构：一键按钮接在这条唯一、且过了闸门的路径上

    @Test
    public void oneClickButtonIsWiredIntoTheSingleGatedInstallPath() throws Exception {
        String settings = read("src/main/java/cn/szu/bot/app/SettingsActivity.java");
        String main = read("src/main/java/cn/szu/bot/app/MainActivity.java");
        String layout = read("src/main/res/layout/activity_settings.xml");
        String strings = read("src/main/res/values/strings.xml");

        // 控件与文案：一键按钮 + 它下面那行说明，都在布局/文案里
        assertTrue("布局里必须有新的「一键更新」按钮", layout.contains("@+id/update_oneclick"));
        assertTrue("布局里必须有那行说明（边界/失败原因）", layout.contains("@+id/update_oneclick_hint"));
        for (String name : new String[]{"settings_oneclick_no_server", "settings_oneclick_unchecked",
                "settings_oneclick_up_to_date", "settings_oneclick_update_to",
                "settings_oneclick_downloading", "settings_oneclick_install", "settings_oneclick_retry"}) {
            assertTrue("状态机要用的文案资源缺了：" + name, strings.contains("name=\"" + name + "\""));
        }

        // 一键按钮的点击 → oneClickUpdate；而 oneClickUpdate = 先检查（startCheck）再下载（oneClickDownloadStep）
        String bind = body(settings, "private void bindUpdateSection(");
        assertTrue("按钮要真的挂上点击事件：" + bind, bind.contains("updateOneclick.setOnClickListener"));
        assertTrue(bind.contains("oneClickUpdate()"));
        String oneClick = body(settings, "private void oneClickUpdate()");
        assertTrue("没配服务器时不许发请求，要提示去填地址：" + oneClick, oneClick.contains("repository.current()"));
        assertTrue(oneClick.contains("getString(R.string.settings_oneclick_hint_no_server)"));
        assertTrue("第 1 步：还没查过就先检查：" + oneClick, oneClick.contains("startCheck("));
        assertTrue("第 2 步：接着走下载/校验/安装：" + oneClick, oneClick.contains("oneClickDownloadStep"));
        assertFalse("横幅上点「稍后」只影响横幅，不许挡住设置页的一键更新",
                oneClick.contains("ignoredVersionCode"));

        // 同源：两个界面都从同一个状态机取文案（没有任何一处自己拼版本号文案）
        assertTrue("设置页必须用唯一状态机", settings.contains("UpdateUiState.derive("));
        assertTrue("横幅必须用同一个状态机（否则就是两份状态）", main.contains("UpdateUiState.derive("));
        assertTrue("设置页的一键按钮文案取自 State", settings.contains("state.labelRes"));
        assertTrue("横幅按钮文案取自同一个 State", main.contains("state.bannerRes"));

        // 安装出口唯一，且在 sha256/versionCode 闸门之后（一键更新也走的是这一条出口）
        assertEquals("把包装进系统安装器的地方只能有一个", 1,
                count(settings, "UpdateInstaller.buildInstallIntent("));
        String install = body(settings, "private void promptInstall(");
        assertTrue("必须先过闸门", install.contains("UpdateInstaller.checkInstallable("));
        assertTrue("被拒时不许走到安装", install.contains("if (!gate.allowed)"));
        assertTrue("闸门必须在组装安装 Intent 之前",
                install.indexOf("checkInstallable(") < install.indexOf("buildInstallIntent("));

        // 一键路径最终汇进 beginDownload（下载/校验/安装都复用那一条，不另起一套）
        String step = body(settings, "private void oneClickDownloadStep()");
        assertTrue("一键更新必须复用 beginDownload（那里才是下载 + 校验 + 安装）",
                step.contains("beginDownload("));
        System.out.println("[UpdateUiStateTest] 一键路径：oneClickUpdate → startCheck → oneClickDownloadStep"
                + " → beginDownload → promptInstall → checkInstallable → buildInstallIntent");
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

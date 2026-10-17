package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * <b>「加载期间也能换服务器」的源码级检查</b>：直接读真实的 {@code MainActivity.java}、
 * {@code activity_main.xml}、{@code activity_settings.xml} 与 {@code strings.xml}，
 * 断言那条链"从布局到点击到弹窗"是通的，而且是<b>只在该弹的时候弹</b>。
 *
 * <p>为什么只能到源码级：{@code adb devices} 为空、JVM 单测里没有 Robolectric，起不了 Activity/WebView
 * （{@code android.util.Log} 是 stub，{@code WebView.loadUrl} 更是碰不到）。所以把"少一行就静默失效"
 * 的几处钉死在源码里 —— 和本仓库既有的 {@link WebViewLifecycleCheckTest} /
 * {@link ManifestAndResourcesCheckTest} 是同一套做法。
 *
 * <p>能真跑的那一半（URL 拼装、8787 是否真的可达）在这里也是真断言：
 * {@link DeviceScope#pageUrl} 与 {@link LanScanner#reachable} 都是纯 Java。
 */
public class ServerPickerCheckTest {

    @Rule
    public final CapturedLogRule logs = new CapturedLogRule();

    private static final String MAIN = "src/main/java/cn/szu/bot/app/MainActivity.java";
    private static final String SETTINGS = "src/main/java/cn/szu/bot/app/SettingsActivity.java";

    /** 从当前工作目录往上找到含 {@code app/src/main/AndroidManifest.xml} 的那个目录。 */
    private static Path appDir() {
        Path here = Paths.get("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path manifest = candidate.resolve("src/main/AndroidManifest.xml");
            if (Files.isRegularFile(manifest)) return candidate;
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

    /** 取一个方法的方法体（从签名后第一个花括号起按花括号配对）。 */
    private static String body(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue("源码里找不到这个方法：" + signature, at >= 0);
        int open = source.indexOf('{', at);
        assertTrue("找不到方法体：" + signature, open >= 0);
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

    /** 去掉 Java 注释（只用于"不许出现"的检查：注释里写着"不许这么做"不算做了）。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    // ------------------------------------------------------------------ ⑥ 设置页「扫描局域网」

    @Test
    public void settingsPageStillHasTheManualScanButtonAndItChangesState() throws Exception {
        String layout = read("src/main/res/layout/activity_settings.xml");
        assertTrue("设置页必须有「扫描局域网」按钮：@+id/action_scan", layout.contains("@+id/action_scan"));
        assertTrue("扫描进度控件也要在：@+id/scan_progress", layout.contains("@+id/scan_progress"));
        assertTrue("按钮文案必须真的写着「扫描局域网」", layout.contains("@string/settings_scan"));

        String code = stripComments(read(SETTINGS));
        String toggle = body(code, "private void toggleScan()");
        assertTrue("按钮要挂到 toggleScan：" + toggle, toggle.contains("action_scan") || code.contains("action_scan"));
        assertTrue("点了要进入「进行中」状态（按钮变「取消扫描」）：" + toggle, toggle.contains("取消扫描"));
        assertTrue("进行中要显示进度条：" + toggle, toggle.contains("scanProgress.setVisibility(View.VISIBLE)"));
        assertTrue("扫描期间要能取消：" + toggle, toggle.contains("scanner.cancel()"));
        assertTrue("网段要用新的前缀推导（localPrefixes）显示：" + toggle, toggle.contains("LanScanner.localPrefixes()"));
        assertTrue("没有网卡时给的提示要留在源码里", code.contains("没找到局域网网卡"));

        String strings = read("src/main/res/values/strings.xml");
        assertTrue(strings.contains("settings_scan"));
        assertTrue(strings.contains("scan_running"));
    }

    // ------------------------------------------------------------------ ⑨ 加载中入口始终可点

    @Test
    public void pickServerEntryIsInPlaceAndOpensThePickerWhileLoading() throws Exception {
        String layout = read("src/main/res/layout/activity_main.xml");
        assertTrue("加载期间那条入口必须在布局里：@+id/pick_server_bar", layout.contains("@+id/pick_server_bar"));
        assertTrue("入口按钮要在：@+id/pick_server", layout.contains("@+id/pick_server"));
        assertTrue("入口文案走字符串资源", layout.contains("@string/pick_server_entry"));
        // 默认隐藏，靠 setLoading 在加载期间显示（正常浏览时不占地方）。
        int barAt = layout.indexOf("@+id/pick_server_bar");
        assertTrue("pick_server_bar 默认必须是 gone", layout.substring(barAt).contains("android:visibility=\"gone\""));
        // 既有控件一个都不能丢（不许为了加这个入口把错误页/下拉刷新弄坏）。
        for (String id : new String[]{"webview", "progress", "swipe", "error_page", "error_detail",
                "error_retry", "error_settings", "error_browser", "toolbar", "update_banner"}) {
            assertTrue("既有控件丢了：@+id/" + id, layout.contains("@+id/" + id));
        }

        String code = stripComments(read(MAIN));
        String bind = body(code, "private void bindPickServer()");
        assertTrue("要找到并挂上那个按钮：" + bind, bind.contains("R.id.pick_server"));
        assertTrue("点击必须弹出选择器：" + bind, bind.contains("showServerPicker("));

        // 加载中 → 入口可见；加载完 → 收起。两处都在 setLoading 里，并且被 onPageStarted/onPageFinished 调用。
        String loading = body(code, "private void setLoading(boolean value)");
        assertTrue("加载中要显示：" + loading, loading.contains("View.VISIBLE"));
        assertTrue("加载完要收起：" + loading, loading.contains("View.GONE"));
        assertTrue("加载中要开始计时：" + loading, loading.contains("scheduleLoadTimeout()"));
        String started = body(code, "public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon)");
        assertTrue("onPageStarted 必须置「加载中」：" + started, started.contains("setLoading(true)"));
        String finished = body(code, "public void onPageFinished(WebView view, String url)");
        assertTrue("onPageFinished 必须收尾：" + finished, finished.contains("setLoading(false)"));
        String loadUrl = body(code, "private void loadUrl(String url)");
        assertTrue("loadUrl 一进去就算加载中（别等 onPageStarted 的空窗）：" + loadUrl, loadUrl.contains("setLoading(true)"));

        // 失败页上入口也不许收起来：那时候用户唯一想做的就是换一台。
        String errorPage = body(code, "private void showErrorPage(String detail)");
        assertTrue("错误页上「选择服务器…」必须仍然可见：" + errorPage,
                errorPage.contains("pickServerBar.setVisibility(View.VISIBLE)"));
        String hide = body(code, "private void hideErrorPage()");
        assertTrue("收起入口要跟「正在加载」挂钩：" + hide, hide.contains("if (!loading"));
    }

    // ------------------------------------------------------------------ ⑩ 失败/超时自动弹一次

    @Test
    public void failureAndTimeoutAutoPopThePickerAtMostOnce() throws Exception {
        String code = stripComments(read(MAIN));

        // ① 加载失败：onReceivedError / onReceivedHttpError 都要走到统一收尾
        String failed = body(code, "private void onMainFrameLoadFailed(String detail)");
        assertTrue("失败要弹选择器：" + failed, failed.contains("showServerPicker(true)"));
        assertTrue("失败要弹原生错误页：" + failed, failed.contains("showErrorPage("));
        assertTrue("失败要退出「加载中」（否则看门狗还会再弹一次）：" + failed, failed.contains("setLoading(false)"));

        String error = body(code, "public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error)");
        assertTrue("onReceivedError（主文档）要走到 onMainFrameLoadFailed：" + error,
                error.contains("onMainFrameLoadFailed("));
        String httpError = body(code, "public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse)");
        assertTrue("onReceivedHttpError 要走到 onMainFrameLoadFailed：" + httpError,
                httpError.contains("onMainFrameLoadFailed("));
        // 子资源失败不许弹（图标挂了不该糊住整屏、更不该弹选择器）。
        assertTrue("子资源失败要提前 return：" + error, error.contains("isForMainFrame"));

        // ② 超时：6 秒，且只弹一次
        assertTrue("超时秒数是 6 秒：LOAD_TIMEOUT_MS = 6_000L", code.contains("LOAD_TIMEOUT_MS = 6_000L"));
        String watchdog = body(code, "private void scheduleLoadTimeout()");
        assertTrue("看门狗到点要弹选择器：" + watchdog, watchdog.contains("showServerPicker(true)"));
        assertTrue("看门狗必须用 postDelayed 排期：" + watchdog, watchdog.contains("mainHandler.postDelayed"));
        assertTrue("加载已经结束时不许再弹：" + watchdog, watchdog.contains("if (!loading"));

        // ③ "只弹一次"：autoPickerShown 挡住重复
        String picker = body(code, "private void showServerPicker(boolean auto)");
        assertTrue("自动弹之前必须查标记：" + picker, picker.contains("if (autoPickerShown)"));
        assertTrue("查完要置位：" + picker, picker.contains("autoPickerShown = true"));
        assertTrue("已经在显示时不许再叠一个弹窗：" + picker, picker.contains("if (openPicker != null)"));
        assertTrue("自动弹出要顺带扫一次局域网：" + picker, picker.contains("startLanScan(false)"));
        String loadUrl = body(code, "private void loadUrl(String url)");
        assertTrue("新一轮加载要重置「已经弹过」：" + loadUrl, loadUrl.contains("autoPickerShown = false"));
    }

    // ------------------------------------------------------------------ ⑪ 选中即切 + /m?scope= 不丢 + 成功不弹

    @Test
    public void switchingKeepsTheMobileScopeInjectionAndSuccessNeverPopsThePicker() throws Exception {
        // ---- 真断言：/m?scope= 的拼装（纯 Java）----
        String base = "http://127.0.0.1:8787";
        String url = DeviceScope.pageUrl(base, UrlHelper.PATH_MOBILE, "dev-0123456789ab");
        assertEquals("切到这台服务器后 /m 地址必须仍带设备 scope", base + "/m?scope=dev-0123456789ab", url);
        // 8787 上有 Pixiko 时，切过去这个地址就是能连的（本轮第一次跑时 probe → 200、/24 真扫也扫到了它）。
        boolean live = LanScanner.reachable(base, 500);
        System.out.println("[实测] " + base + " 可达=" + live
                + "（机器人没在跑时这条只断言前后判据一致，不硬写 200）");
        assertEquals("探活的前后判据必须一致：reachable(base) == (probe 得到 200)",
                live, LanScanner.HTTP_PROBER.probe("127.0.0.1", 300) == 200);

        // ---- 源码级：切换走既有链路，不另起一套 ----
        String code = stripComments(read(MAIN));
        String switchTo = body(code, "void switchTo(String rawBase)");
        assertTrue("地址仍要过既有校验（normalizeBase）：" + switchTo, switchTo.contains("UrlHelper.normalizeBase"));
        assertTrue("坏地址要拒绝：" + switchTo, switchTo.contains("pick_server_bad_address"));
        assertTrue("要记住选择（写进 ServerRepository）：" + switchTo, switchTo.contains("repository.upsert("));
        assertTrue("要设成当前服务器：" + switchTo, switchTo.contains("repository.setLastId("));
        assertTrue("令牌要沿用当前的（换机器不用重抄）：" + switchTo, switchTo.contains("current.token"));
        assertTrue("切换后要重新注入令牌：" + switchTo, switchTo.contains("tokenInjected = false"));
        assertTrue("切换一律走既有的 loadHome()：" + switchTo, switchTo.contains("loadHome()"));

        String home = body(code, "private String homeUrl()");
        assertTrue("homeUrl 必须仍用 DeviceScope.pageUrl 注入 /m?scope=：" + home,
                home.contains("DeviceScope.pageUrl"));
        String loadHome = body(code, "private void loadHome()");
        assertTrue("loadHome 走 homeUrl()：" + loadHome, loadHome.contains("loadUrl(homeUrl())"));

        // ---- 源码级：正常加载成功时绝不弹 ----
        String finished = body(code, "public void onPageFinished(WebView view, String url)");
        assertFalse("加载成功了还弹选择器就是打扰：onPageFinished 里不许出现 showServerPicker",
                finished.contains("showServerPicker"));
        assertTrue("成功时要清掉失败标记：" + finished, finished.contains("loadFailed = false"));

        // ---- 启动时「可达就跳过扫描」（省电/省时间，不无脑扫）----
        String discovery = body(code, "private void startLanFirstDiscovery()");
        assertTrue("先探已保存的地址：" + discovery, discovery.contains("baseReachable("));
        assertTrue("可达就跳过扫描：" + discovery, discovery.contains("跳过局域网扫描"));
        assertTrue("不可达才扫一次：" + discovery, discovery.contains("startLanScan(false)"));
        assertTrue("一次运行只跑一次：" + discovery, discovery.contains("lanDiscoveryStarted"));
        String baseReachable = body(code, "static boolean baseReachable(String base)");
        assertTrue("判据与扫描同源（LanScanner.reachable）：" + baseReachable,
                baseReachable.contains("LanScanner.reachable("));
    }
}

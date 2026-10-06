package cn.szu.bot.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * <b>WebView 生命周期的「成对」检查</b>：直接读<b>真实的</b> MainActivity.java / NativeHook.java，
 * 断言 {@code onPause} 与 {@code onResume} 是成对的、回前台会主动叫网页继续跑、
 * 而"图收不到"的那条路没有被谁掐掉。
 *
 * <p>为什么要有这一层：这正是用户报的「手机端挂起（在浏览其他应用）也收不到图」里属于外壳的那一半。
 * 这一类的错都是「少一行就静默失效」型 —— 少了 {@code onResume()} 那一句，App 从后台回来
 * WebView 就一直是暂停态（网页里的轮询再也醒不过来），可编译期一个字都不会报；
 * 少了回前台的唤醒，网页只能等自己 1.5 秒的看门狗，用户看到的就是"回来过一会儿才有图"。
 * 没有真机/模拟器时（{@code adb devices} 为空）这是能做、也做得住的那一层，
 * 真机那一层在交付说明里如实标注未覆盖。
 *
 * <p>与 {@link ManifestAndResourcesCheckTest} 的分工：那边断言清单/资源，这边断言生命周期本身。
 */
public class WebViewLifecycleCheckTest {

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

    /**
     * 取一个方法的方法体：从签名之后第一个 {@code &#123;} 起按花括号配对。
     *
     * <p>为什么不用正则/contains 就完事：这一片的注释里到处是 {@code onResume()} 这样的字眼
     * （既有 javadoc 也有普通注释），只查"整份源码里有没有这一句"会把注释当成实现。
     */
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

    /**
     * 去掉 Java 的块注释与行注释（**只用来做"不许出现"的检查**）。
     *
     * <p>为什么必须去掉：这一片的 javadoc 里正大光明地写着"刻意不调 pauseTimers()、也不会有
     * resumeTimers" —— 那是**文档**（解释为什么不做），不是实现。不去注释就等于把"解释"判成"干了"。
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    // ---------------------------------------------------------------- 成对

    @Test
    public void pauseAndResumeArePairedOnTheWebView() throws Exception {
        String source = read("src/main/java/cn/szu/bot/app/MainActivity.java");
        String pause = body(source, "protected void onPause()");
        String resume = body(source, "protected void onResume()");
        assertTrue("onPause 必须调 webView.onPause()（否则后台动画/定位继续跑）：" + pause,
                pause.contains("webView.onPause()"));
        assertTrue("onResume 必须调 webView.onResume()，与 onPause 成对 —— "
                        + "少了它 App 从后台回来 WebView 会一直停在暂停态，网页里的轮询再也醒不过来：" + resume,
                resume.contains("webView.onResume()"));
        // 两个方法都必须在动 WebView 之前先挡一次 null（onCreate 提前 return 的那条路径上它是 null）。
        assertTrue("onPause 里要先判空：" + pause, pause.contains("webView != null"));
        assertTrue("onResume 里要先判空：" + resume, resume.contains("webView == null"));
    }

    @Test
    public void resumeAlsoWakesThePageImmediately() throws Exception {
        String source = read("src/main/java/cn/szu/bot/app/MainActivity.java");
        String resume = body(source, "protected void onResume()");
        assertTrue("onResume 里必须顺手唤醒网页（回前台立刻补一拍，别等网页自己 1.5 秒的看门狗）：" + resume,
                resume.contains("wakeWebPage()"));
        String wake = body(source, "private void wakeWebPage()");
        assertTrue("唤醒要真的把脚本注进网页：" + wake, wake.contains("NativeHook.resumeScript()"));
        assertTrue("唤醒只能在 UI 线程用 evaluateJavascript：" + wake, wake.contains("evaluateJavascript"));
        assertTrue("只对自己那台服务器的页面叫醒（外链页面不去打扰）：" + wake, wake.contains("bridgeAttached"));

        String hook = read("src/main/java/cn/szu/bot/app/NativeHook.java");
        String script = body(hook, "public static String resumeScript()");
        assertTrue("唤醒脚本要派发 focus（网页的唤醒通道认它）：" + script,
                script.contains("dispatchEvent(new Event('focus'))"));
        assertTrue("唤醒脚本要派发 resume（有些安卓壳就是派这个）：" + script,
                script.contains("dispatchEvent(new Event('resume'))"));
        assertFalse("刻意不派发 visibilitychange：那要连带把 document.visibilityState 说成 visible，"
                        + "而有的壳里它一直停在 hidden，硬掰会和网页自己的判断打架",
                script.contains("visibilitychange"));
        assertTrue("脚本要防御式（try/catch，失败也只回一句 error）：" + script, script.contains("catch"));
    }

    // ---------------------------------------------------------------- 别把「收图」掐掉

    @Test
    public void jsTimersAreNotGloballyPausedInTheBackground() throws Exception {
        String code = stripComments(read("src/main/java/cn/szu/bot/app/MainActivity.java"));
        // WebView.onPause() 按官方文档**不暂停 JavaScript**；pauseTimers() 才是"全局停掉 JS 定时器"。
        // 手机端收图靠的正是网页里的轮询（/api/capture、/api/quests），停掉它等于把
        // "挂起期间尽量把图收下来"这条路掐死 —— 与用户的要求相反。
        assertFalse("不许在后台停掉 JS 定时器（pauseTimers）：手机端收图就靠网页里的轮询",
                code.contains("pauseTimers"));
        assertFalse("同理不许出现 resumeTimers（没有 pause 就不该有它，省得后人以为是成对漏了）",
                code.contains("resumeTimers"));
    }

    @Test
    public void foregroundReturnDoesNotReloadOrWipeSessionState() throws Exception {
        String source = read("src/main/java/cn/szu/bot/app/MainActivity.java");
        String code = stripComments(source);
        String resume = body(source, "protected void onResume()");
        assertFalse("回前台不许整页 reload（粗暴且会打断用户正在做的事）：" + resume, resume.contains("reload"));
        assertFalse("回前台不许清历史/清缓存：" + resume,
                resume.contains("clearHistory") || resume.contains("clearCache"));
        // onStop / onStart 里清状态是"挂起回来状态就没了"的另一条常见成因：这里一个都不许有。
        assertFalse("不许重写 onStop（那里清会话状态 = 挂起回来状态全丢）", code.contains("protected void onStop()"));
        assertFalse("不许重写 onStart 去清会话状态", code.contains("protected void onStart()"));
        // 旋转 / 分屏 / 字体缩放都不重建 Activity（不重建就不会整页重来），由清单的 configChanges 保证。
        String manifest = read("src/main/AndroidManifest.xml");
        assertTrue("MainActivity 必须声明 configChanges，否则旋转/分屏会重建 Activity（整页重来）：" + manifest,
                manifest.contains("android:configChanges=\"orientation|screenSize"));
        assertTrue("MainActivity 必须是 singleTop（从最近任务/通知回来不新建实例）",
                manifest.contains("android:launchMode=\"singleTop\""));
    }

    // ---------------------------------------------------------------- 上滑刷新（1.6.2 回归）

    /**
     * <b>「{@code /m} 上滑必刷新」这条链必须在源码里看得见</b>：用户报的这个问题在 1.6.1 修过、
     * 1.6.2 又丢了（修复整段从源码里消失，于是 dex 里也没有），
     * 所以这里不测行为（JVM 单测跑不了 WebView），而是把"源码里有这条链"钉死 ——
     * 少一行就静默失效、编译期一个字都不报，正是 {@link WebViewLifecycleCheckTest} 这一类要拦的东西。
     *
     * <p>链的内容：{@code /m} → {@code swipe.setEnabled(false)}（{@code /m} 自带网页版下拉刷新，
     * 见 {@code webui/m/app.js} 的 {@code PixikoM.ptrInstall}）；非 {@code /m}（完整控制台 {@code /}）
     * → {@code swipe.setEnabled(true)}。并且至少被<b>两处</b>调用，覆盖「页面切换」与 {@code onPageFinished}
     * （前进/后退回到 {@code /m} 这条唯一不经过 {@code loadHome()} 的路）。
     */
    @Test
    public void mobileEntryTurnsNativePullToRefreshOff() throws Exception {
        String code = stripComments(read("src/main/java/cn/szu/bot/app/MainActivity.java"));

        String apply = body(code, "private void applySwipeAvailability(boolean mobile)");
        assertTrue("手机界面 /m 上必须关掉原生下拉刷新（否则「上滑必刷新」原样复现）：" + apply,
                apply.contains("swipe.setEnabled(false)"));
        assertTrue("完整控制台 / 上必须保留原生下拉刷新（那条路没有网页版可顶）：" + apply,
                apply.contains("swipe.setEnabled(true)"));

        String bar = body(code, "private void applyActionBarVisibility()");
        assertTrue("applyActionBarVisibility 里必须跟着调一次"
                        + "（onCreate / loadHome=toggleUi+换服务器 / adoptUiPathFrom 含 hash 路由 都走它）：" + bar,
                bar.contains("applySwipeAvailability("));
        String finished = body(code, "public void onPageFinished(WebView view, String url)");
        assertTrue("onPageFinished 里必须再调一次（前进/后退回到 /m 不经过 loadHome()）：" + finished,
                finished.contains("applySwipeAvailability("));

        int occurrences = code.split("applySwipeAvailability\\(", -1).length - 1;
        int callSites = occurrences - 1;   // 减掉方法声明那一处
        assertTrue("applySwipeAvailability 至少要被两处调用（现在 " + callSites + " 处）", callSites >= 2);

        // 兜底那层：容器换成了自家子类，canChildScrollUp() 问真正会滚的 WebView，
        // 而不是 SwipeRefreshLayout 默认问的那个不滚动的 FrameLayout。
        String container = read("src/main/java/cn/szu/bot/app/PixikoSwipeRefreshLayout.java");
        String up = body(container, "public boolean canChildScrollUp()");
        assertTrue("canChildScrollUp() 必须问 WebView 的 getScrollY()/canScrollVertically(-1)，"
                        + "不能再用父类那个永远答'到顶'的实现：" + up,
                up.contains("getScrollY()") && up.contains("canScrollVertically(-1)"));
    }
}

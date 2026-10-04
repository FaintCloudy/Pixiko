package cn.szu.bot.app;

import android.app.Activity;
import android.content.Context;
import android.webkit.JavascriptInterface;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 网页 ↔ 原生的桥。注册名固定为 {@code PixikoNative}（见 {@link NativeHook#INTERFACE_NAME}），
 * 网页侧由 {@link NativeHook} 注入的那段 JS 调用。
 *
 * <p>被 {@code @JavascriptInterface} 标注的方法运行在 WebView 的 JavaBridge 线程上，
 * <b>绝不能在这里做 IO</b>（会卡住整个桥甚至 ANR）：所以两个入口都只是丢给后台线程，
 * 结果再回主线程 Toast / 拉系统界面。
 *
 * <p>安全上的说明：桥只挂在我们自己配置的那台服务器返回的页面上（见 MainActivity 的 host 判断），
 * 第三方站点拿不到这个方法，避免任意网页都能指挥 app 下文件。
 */
public final class PixikoBridge {

    /** 只在 MainActivity 存活期间持有，且是弱引用：桥可能被 WebView 的 JS 长时间持有。 */
    private final java.lang.ref.WeakReference<Activity> activity;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "pixiko-image-io");
        thread.setDaemon(true);
        return thread;
    });

    public PixikoBridge(Activity activity) { this.activity = new java.lang.ref.WeakReference<>(activity); }

    /** 网页里长按/右键一张图 → 存进系统相册。 */
    @JavascriptInterface
    public void saveImage(final String src, final String alt) {
        final Activity host = activity.get();
        if (host == null || src == null || src.isBlank()) return;
        final String url = resolve(host, src);
        if (url == null) {
            ToastBus.show(host, "这张图拿不到可下载的地址，试试点开大图再长按。");
            return;
        }
        Log.d("保存图片请求 → " + UrlHelper.stripQuery(url) + "（alt=" + (alt == null ? "" : alt) + "）");
        workers.execute(() -> {
            ImageStore.SaveResult result = ImageStore.saveToGallery(host, url, alt);
            ToastBus.show(host, result.message);
            Log.d("保存结果：" + result.message);
        });
    }

    /** 网页里长按菜单选「分享」→ 走系统分享面板。 */
    @JavascriptInterface
    public void shareImage(final String src, final String alt) {
        final Activity host = activity.get();
        if (host == null || src == null || src.isBlank()) return;
        final String url = resolve(host, src);
        if (url == null) {
            ToastBus.show(host, "这张图拿不到可分享的地址。");
            return;
        }
        workers.execute(() -> {
            String message = ImageStore.share(host, url, alt, "分享图片");
            ToastBus.show(host, message);
        });
    }

    /**
     * 长按图片弹的原生菜单：真机上手势最不容易误触，也给用户「先看一眼再决定」的机会。
     * JS 侧只负责命中并 preventDefault，菜单本身用 AlertDialog（原生，不依赖网页样式）。
     */
    @JavascriptInterface
    public void showImageMenu(final String src, final String alt) {
        final Activity host = activity.get();
        if (host == null || src == null || src.isBlank()) return;
        final String url = resolve(host, src);
        ToastBus.main().post(() -> {
            Activity current = activity.get();
            if (current == null || current.isFinishing()) return;
            String label = alt == null || alt.isBlank() ? "这张图片" : alt;
            new android.app.AlertDialog.Builder(current)
                    .setTitle(label)
                    .setItems(new CharSequence[]{"保存到相册", "分享…"}, (dialog, which) -> {
                        if (which == 0) saveImage(src, alt); else shareImage(src, alt);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    /** 网页侧自检用：原生桥在不在。 */
    @JavascriptInterface
    public boolean available() { return true; }

    // ---------------------------------------------------------------- app 级入口
    //
    // 为什么有这四个方法：手机端界面 /m 自带顶部 app bar ＋ 底部 tab，和外壳的原生 ActionBar 叠起来
    // 是两条栏，所以 MainActivity 在 /m 下把原生 ActionBar 隐藏了（见 applyActionBarVisibility）。
    // 栏一藏，菜单就点不到，于是「服务器设置 / 清空网页缓存 / 清除登录状态 / 在浏览器打开」
    // 必须由网页调回原生 —— 就是下面四个。
    //
    // 网页侧调用名 → 原生实现（都在 MainActivity 里，和菜单用的是同一段代码）：
    //   window.PixikoNative.openServerSettings() → MainActivity.openServerSettings()
    //   window.PixikoNative.clearWebCache()      → MainActivity.clearWebCache()
    //   window.PixikoNative.clearLoginState()    → MainActivity.clearLoginState()
    //   window.PixikoNative.openInBrowser()      → MainActivity.openInBrowser()
    //
    // 线程：@JavascriptInterface 方法跑在 WebView 的 JavaBridge 线程上，而开 Activity /
    // evaluateJavascript / clearCache 都必须回主线程，所以统一走 onMain(...)。
    // 桥只在配置的那台服务器的页面上挂着（见 MainActivity 的 host 判断），第三方页面调不到。

    /** 打开原生的「服务器设置」页（选/加/改服务器、测试连接、扫描局域网）。 */
    @JavascriptInterface
    public void openServerSettings() { onMain(MainActivity::openServerSettings); }

    /** 清空 WebView 的网页缓存（不含 localStorage 里的令牌，那是 clearLoginState 的事）。 */
    @JavascriptInterface
    public void clearWebCache() { onMain(MainActivity::clearWebCache); }

    /** 清除登录状态：删掉 localStorage 里的令牌并 reload，网页会回到自己的锁屏。 */
    @JavascriptInterface
    public void clearLoginState() { onMain(MainActivity::clearLoginState); }

    /** 用系统浏览器打开当前页（原生的「在浏览器打开」）。 */
    @JavascriptInterface
    public void openInBrowser() { onMain(MainActivity::openInBrowser); }

    /**
     * 把「开界面 / 弹窗 / 动 WebView」这类必须在主线程做的事丢回主线程，
     * 并从弱引用里<b>重新取一次</b> Activity（排队期间它可能已经销毁）。
     */
    private void onMain(java.util.function.Consumer<MainActivity> action) {
        ToastBus.main().post(() -> {
            Activity host = activity.get();
            if (host instanceof MainActivity && !host.isFinishing()) {
                action.accept((MainActivity) host);
            }
        });
    }

    /** app 版本号，注入脚本里会写进 window.__pixikoNativeVersion，方便排查「网页用的是哪一版外壳」。 */
    @JavascriptInterface
    public String version() {
        Activity host = activity.get();
        if (host == null) return "";
        try { return host.getPackageManager().getPackageInfo(host.getPackageName(), 0).versionName; }
        catch (Exception error) { return ""; }
    }

    /** Activity 销毁时把线程池收掉。 */
    public void shutdown() { workers.shutdownNow(); }

    /** 相对地址（/api/image?token=…&path=…）拼上当前基地址；绝对地址原样用。 */
    private static String resolve(Context context, String src) {
        String base = Prefs.currentBase(context);
        return UrlHelper.absolutize(base, src);
    }
}

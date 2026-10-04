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

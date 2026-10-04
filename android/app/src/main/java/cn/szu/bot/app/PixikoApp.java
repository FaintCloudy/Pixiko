package cn.szu.bot.app;

import android.app.Application;
import android.os.Build;
import android.os.StrictMode;
import android.webkit.WebView;

/**
 * 进程级初始化。
 *
 * <p>做三件事，都是为了「排查问题时能看懂」：
 * <ol>
 *   <li>debug 包打开 {@code WebView.setWebContentsDebuggingEnabled(true)}：
 *       可以用 Chrome 的 {@code chrome://inspect} 直接调试网页，不必靠猜；</li>
 *   <li>把 WebView 的数据目录统一到应用私有目录（默认就是），并输出一次 WebView 版本；</li>
 *   <li>列出这个外壳与网页的分工（第一次看 logcat 的时候一眼就明白）。</li>
 * </ol>
 */
public class PixikoApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // 令牌、图片下载都在子线程，但 WebView/MediaStore 偶尔会在主线程碰磁盘；
        // 只在 debug 包里把 StrictMode 的线程策略打开成「记日志」，方便定位又不至于崩。
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyLog()
                    .build());
            WebView.setWebContentsDebuggingEnabled(true);
        }

        Log.i("Pixiko Android 外壳启动；WebView 包名=" + WebView.getCurrentWebViewPackage()
                + "，版本=" + BuildConfig.VERSION_NAME + "，Android " + Build.VERSION.RELEASE);
        Log.i("分工：网页 = 全部控制台功能（WebView 原样加载）；原生 = 服务器管理/局域网扫描/图片保存分享/下拉刷新/错误页/常亮");
    }
}

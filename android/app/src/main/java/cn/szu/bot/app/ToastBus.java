package cn.szu.bot.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Toast 小工具：下载/保存都在后台线程，Toast 必须回主线程，这里收口一次。
 * 用 application context，避免持有 Activity 造成泄漏。
 */
public final class ToastBus {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ToastBus() { }

    public static void show(Context context, String message) {
        if (context == null || message == null || message.isBlank()) return;
        final Context app = context.getApplicationContext();
        MAIN.post(() -> Toast.makeText(app, message, Toast.LENGTH_LONG).show());
    }

    public static void shortToast(Context context, String message) {
        if (context == null || message == null) return;
        final Context app = context.getApplicationContext();
        MAIN.post(() -> Toast.makeText(app, message, Toast.LENGTH_SHORT).show());
    }

    public static Handler main() { return MAIN; }
}

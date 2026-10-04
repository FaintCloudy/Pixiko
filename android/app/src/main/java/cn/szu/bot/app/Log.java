package cn.szu.bot.app;

/**
 * 极薄的日志门面：全 app 统一 {@code PixikoApp} 这个 tag，方便 `adb logcat -s PixikoApp` 一把捞。
 *
 * <p>约定：<b>任何日志都不许带令牌</b>。要打令牌相关的信息只打长度或掩码（见 {@link #mask}）。
 *
 * <p>注意方法体里一律写全限定名 {@code android.util.Log.xxx}：本类自己叫 Log，
 * 不写全限定名会递归调到自己身上（栈溢出）。
 */
public final class Log {

    public static final String TAG = "PixikoApp";

    private Log() { }

    public static void d(String message) { android.util.Log.d(TAG, String.valueOf(message)); }

    public static void i(String message) { android.util.Log.i(TAG, String.valueOf(message)); }

    public static void w(String message) { android.util.Log.w(TAG, String.valueOf(message)); }

    public static void w(String message, Throwable error) { android.util.Log.w(TAG, message + "：" + describe(error)); }

    public static void e(String message, Throwable error) { android.util.Log.e(TAG, message + "：" + describe(error)); }

    /** 只用于排查的简短异常描述（不含请求头，避免把令牌带出去）。 */
    public static String describe(Throwable error) {
        if (error == null) return "null";
        String text = error.getClass().getSimpleName() + (error.getMessage() == null ? "" : (": " + error.getMessage()));
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    /** 令牌掩码：永远不要直接打令牌。 */
    public static String mask(String token) {
        if (token == null || token.isEmpty()) return "(空)";
        if (token.length() <= 2) return "**";
        return token.substring(0, 1) + "***" + token.substring(token.length() - 1) + "(" + token.length() + "位)";
    }
}

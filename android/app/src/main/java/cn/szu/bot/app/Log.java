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

    /**
     * 日志出口的委托。生产环境是 {@code android.util.Log}；<b>JVM 单测</b>里可以换成自己的实现。
     *
     * <p>为什么需要这个钩子：AGP 给单测挂的 {@code android.util.Log} 是 stub，一调就抛
     * {@code RuntimeException: Method d in android.util.Log not mocked}。而「下载/校验」这些
     * 逻辑里到处都要打日志，于是单测<b>根本跑不到断言</b>。
     *
     * <p>两条路可选：{@code testOptions.unitTests.returnDefaultValues = true}（让所有 Android API 静默返回默认值，
     * 会把「没 mock 就悄悄过」的坑放大），或者这里开一个只有测试会用的出口。
     * 选后者：<b>只影响日志</b>，其它 Android API 该报错还是报错。
     * 顺带还能在测试里断言「日志里不许出现令牌」。
     */
    public interface Sink {
        void log(int priority, String tag, String message);
    }

    private static volatile Sink sink;

    private Log() { }

    /** 仅供测试：替换日志出口（传 null 恢复 {@code android.util.Log}）。 */
    public static void setSink(Sink replacement) {
        sink = replacement;
    }

    public static void d(String message) { emit(android.util.Log.DEBUG, String.valueOf(message)); }

    public static void i(String message) { emit(android.util.Log.INFO, String.valueOf(message)); }

    public static void w(String message) { emit(android.util.Log.WARN, String.valueOf(message)); }

    public static void w(String message, Throwable error) {
        emit(android.util.Log.WARN, withError(message, error));
    }

    public static void e(String message, Throwable error) {
        emit(android.util.Log.ERROR, withError(message, error));
    }

    private static String withError(String message, Throwable error) {
        return String.valueOf(message) + "：" + describe(error);
    }

    /**
     * 唯一的出口：有委托就用委托，没有就写 logcat。
     *
     * <p>生产路径用 {@code android.util.Log.println(priority, tag, msg)} 而不是 d/i/w/e：
     * println 是 android.util.Log 里唯一「一次调用覆盖所有等级」的方法，
     * 这样这里只有一条生产路径要维护（而且它比各等级方法更底层，不会被 tag 长度限制之类的细节咬到）。
     */
    private static void emit(int priority, String message) {
        Sink active = sink;
        if (active != null) {
            active.log(priority, TAG, message);
            return;
        }
        android.util.Log.println(priority, TAG, message);
    }

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

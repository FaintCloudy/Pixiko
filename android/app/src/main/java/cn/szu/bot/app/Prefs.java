package cn.szu.bot.app;

import android.content.Context;

/**
 * 一点点「当前正在用哪台服务器」的全局读法。
 *
 * <p>为什么需要它：{@link PixikoBridge} 拿到的是一个 {@code img.src} 相对地址，
 * 要拼绝对地址就得知道当前基地址；而桥不该持有 Activity 的字段（WebView 会在页面里长期持有桥对象）。
 * 所以从 {@link ServerRepository} 现读一次。
 */
public final class Prefs {

    private Prefs() { }

    /** 当前服务器的基地址；没配过返回 null。 */
    public static String currentBase(Context context) {
        ServerConfig config = new ServerRepository(context).current();
        return config == null ? null : config.base;
    }

    /**
     * 本机装着的这个包的 {@code versionCode}。
     *
     * <p>用 PackageManager 现读而不是 {@link BuildConfig#VERSION_CODE}：BuildConfig 是<b>编译时</b>的常量，
     * 而这里要回答的是「<b>用户手机上现在装的是哪一版</b>」—— 只有当 app 真的被替换过，这个值才会变，
     * 这正是「装完了没有」的自然判据。读不到返回 -1（调用方按「不知道怎么比」处理，宁可不提示）。
     */
    public static int installedVersionCode(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception error) {
            Log.w("读不到本机 versionCode", error);
            return -1;
        }
    }

    /** 本机 versionName（显示用）。读不到返回 "?"。 */
    public static String installedVersionName(Context context) {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null || name.isBlank() ? "?" : name;
        } catch (Exception error) {
            return "?";
        }
    }

    /**
     * 更新包下载目录：{@code cacheDir/update}。
     *
     * <p>为什么用 cacheDir 而不是 {@code getExternalFilesDir}：
     * <ul>
     *   <li>cacheDir 在应用私有存储里，别的 app 读不到，也不需要任何存储权限；</li>
     *   <li>外部私有目录（{@code /sdcard/Android/data/…}）在部分 ROM 上会被文件管理器/清理软件看到并删掉，
     *       而且 Android 11+ 对它的访问还多一层限制；</li>
     *   <li>安装器只需要 {@code content://}（FileProvider），对文件真实位置没有要求，放哪儿都一样能装。</li>
     * </ul>
     * <p>代价：cacheDir 在系统存储紧张时可能被清掉。所以我们把「已校验好」和「下到一半」分成
     * {@code .verified.apk} / {@code .part} 两个后缀，并在新版本起来后统一清理（见
     * {@link SelfUpdate#cleanWorkFiles(java.io.File)}）。
     */
    public static java.io.File updateDir(Context context) {
        return new java.io.File(context.getCacheDir(), "update");
    }
}

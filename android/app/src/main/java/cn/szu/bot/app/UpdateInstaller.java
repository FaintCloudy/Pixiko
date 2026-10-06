package cn.szu.bot.app;

import java.io.File;
import java.io.IOException;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

/**
 * 「把下载好的 APK 交给系统安装器」这一段。这是自我更新的最后一步，也是<b>唯一必须由用户参与</b>的一步。
 *
 * <h2>现实边界（写清楚了，别再被问第二次）</h2>
 * <p><b>普通 app 无法静默自我替换。</b>静默安装（{@code PackageInstaller} 的无交互提交）需要
 * root，或者 app 被装成 device-owner / 持有平台签名。本 app 是普通应用（&nbsp;{@code cn.szu.bot.app}，
 * debug 签名），所以能做的现实形态就是：
 * <blockquote>app 内下载 + sha256 校验全自动 → 一键拉起系统安装器 → <b>用户在系统弹窗里点一下「安装」</b>。</blockquote>
 * <p>装完之后用户重开 app，版本号自然就是新版；这一步我们无法代替，也<b>不会假装</b>能做到。
 *
 * <h2>为什么要 FileProvider</h2>
 * <p>Android 7（API 24）起把 {@code file://} 的 URI 暴露给别的 app 会直接抛
 * {@link android.os.FileUriExposedException}，所以必须走 {@link FileProvider} 生成
 * {@code content://<applicationId>.fileprovider/...}，并加
 * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} 把这一次读取授权单独发给安装器。
 * 白名单在 {@code res/xml/file_paths.xml}：只放出 {@code cacheDir/update/} 这一个子目录，
 * 不放开整个 cacheDir，更不放开 files/ 与外部存储。
 */
public final class UpdateInstaller {

    /** FileProvider 的 authority：与 AndroidManifest 里 {@code ${applicationId}.fileprovider} 一致。 */
    public static String authority(Context context) {
        return context.getPackageName() + ".fileprovider";
    }

    /**
     * 交给系统安装器的 Intent 的「可断言部分」。
     *
     * <p>为什么要这么一层：{@link Intent} 与 {@link Uri} 在 JVM 单测里是 stub（一调就抛
     * {@code RuntimeException: Stub!}），没法直接断言「Intent 长什么样」。于是把<b>决定安装行为的那几个值</b>
     * 抽成一个纯数据对象，由 {@link #installIntent(InstallAction, Uri)} 唯一的组装点来用。
     * 单测断言这个组装点（不传 Uri 也能断言 action/type/flags），生产路径再把 Uri 填进去 ——
     * 这样「Intent 的 action/type/flags 对不对」就是可重复断言的了，而不是只在真机上看运气。
     */
    public static final class InstallAction {
        /** {@link Intent#ACTION_VIEW}。 */
        public final String action;
        /** {@code application/vnd.android.package-archive}。 */
        public final String mimeType;
        /** {@link Intent#FLAG_GRANT_READ_URI_PERMISSION}。 */
        public final int grantReadUriPermissionFlag;
        /** {@link Intent#FLAG_ACTIVITY_NEW_TASK}。 */
        public final int newTaskFlag;

        InstallAction(String action, String mimeType, int grantReadUriPermissionFlag, int newTaskFlag) {
            this.action = action;
            this.mimeType = mimeType;
            this.grantReadUriPermissionFlag = grantReadUriPermissionFlag;
            this.newTaskFlag = newTaskFlag;
        }

        /** 合并后的 flags（安装器只需要这两个）。 */
        public int flags() {
            return grantReadUriPermissionFlag | newTaskFlag;
        }
    }

    private UpdateInstaller() { }

    /** 组装 Intent 时要用的那几个常量值。 */
    public static InstallAction apkInstallAction() {
        return new InstallAction(
                Intent.ACTION_VIEW,
                SelfUpdate.APK_MIME,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * <b>唯一的 Intent 组装点。</b>单测传 {@code uri == null} 来断言 action/type/flags；
     * 生产路径传真实 {@code content://} URI。
     *
     * <p>{@code setDataAndType}（而不是分开的 setData + setType）：安装器必须同时看到
     * {@code content://} 这个 data <b>和</b> APK 的 MIME 才会认，分开设会互相清掉。
     */
    public static Intent installIntent(InstallAction action, Uri uri) {
        Intent intent = new Intent(action.action);
        if (uri != null) intent.setDataAndType(uri, action.mimeType);
        else intent.setType(action.mimeType);
        intent.addFlags(action.grantReadUriPermissionFlag);
        intent.addFlags(action.newTaskFlag);
        return intent;
    }

    /**
     * 把下载好的（已通过 sha256 校验的）APK 变成安装 Intent。
     *
     * @throws IOException 拿不到 FileProvider URI（file_paths 白名单没覆盖这个路径时会这样）
     */
    public static Intent buildInstallIntent(Context context, File apk) throws IOException {
        if (apk == null || !apk.isFile()) throw new IOException("安装包不存在或已被清理");
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(context, authority(context), apk);
        } catch (IllegalArgumentException error) {
            // FileProvider 只对 file_paths.xml 里声明过的路径放行；走到这里说明白名单和下载目录对不上。
            throw new IOException("FileProvider 拒绝了这个路径（检查 res/xml/file_paths.xml）："
                    + apk.getAbsolutePath(), error);
        }
        return installIntent(apkInstallAction(), uri);
    }

    /**
     * 本 app 现在有没有「安装未知应用」的授权。
     *
     * <p>API 26（Android 8.0，本 app 的 minSdk 就是它）起这个权限是<b>按来源应用</b>授予的：
     * 用户必须在「设置 → 应用 → 特殊权限 → 安装未知应用」里单独放行本 app。
     * 没有它的话 {@code ACTION_VIEW} 的安装 Intent 会被系统直接拒掉（用户只看到一闪而过或干脆没反应），
     * 所以必须先检查、再引导。
     */
    public static boolean canRequestPackageInstalls(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;   // 8.0 以下没有这个开关
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (RuntimeException error) {
            // 个别 ROM 的 PackageManager 会在这里抛；保守返回 false 走引导流程，比静默失败好。
            Log.w("查询「安装未知应用」授权失败，按未授权处理", error);
            return false;
        }
    }

    /**
     * 引导用户去系统的「安装未知应用」授权页。
     *
     * <p>用 {@code ACTION_MANAGE_UNKNOWN_APP_SOURCES} + 本包名，系统会直接落在<b>本 app 那一项</b>上，
     * 用户少找两层菜单。部分 ROM 不认这个 action（会抛 {@link android.content.ActivityNotFoundException}），
     * 那就退回「本应用的详情页」，再不行退到「所有应用列表」——一层层兜底，不给用户一个死按钮。
     */
    public static void openUnknownSourcesSettings(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent direct = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (tryStart(context, direct)) return;
        }
        Intent appDetails = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
        appDetails.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(context, appDetails)) return;

        Intent list = new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
        list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!tryStart(context, list)) {
            Log.w("这台设备上没有能打开「安装未知应用」设置页的界面");
        }
    }

    private static boolean tryStart(Context context, Intent intent) {
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException error) {
            Log.d("打不开设置页（" + intent.getAction() + "）：" + Log.describe(error));
            return false;
        }
    }
}

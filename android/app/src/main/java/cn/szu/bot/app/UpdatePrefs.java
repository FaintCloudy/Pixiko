package cn.szu.bot.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 更新功能的开关与「别再烦我」状态。
 *
 * <p>和 {@link ServerRepository} 一样存在应用私有的 SharedPreferences 里（卸载即清除），
 * 但<b>另开一个文件</b> {@code pixiko_update}：更新状态和服务器配置是两件事，
 * 混在一起会让「清理服务器配置」这类操作意外把更新开关也带走。
 *
 * <p>三条规则都由这里落库：
 * <ol>
 *   <li>{@link #autoCheck()} —— 设置页那个开关，<b>默认开</b>（缺省值就是 true）；</li>
 *   <li>{@link #ignoredVersionCode()} —— 用户点过「稍后」的那个 versionCode。
 *       <b>不</b>写成布尔值而是记下具体版本号：下个版本出来时应该重新提示，别永久静音；</li>
 *   <li>{@link #downloadedPath()} —— 已校验好的 APK 路径，进程被杀/重启后还能接着装，
 *       不用重下几十 MB。</li>
 * </ol>
 */
public final class UpdatePrefs {

    private static final String PREFS = "pixiko_update";

    private static final String KEY_AUTO_CHECK = "auto_check";
    private static final String KEY_IGNORED_VERSION_CODE = "ignored_version_code";
    private static final String KEY_DOWNLOADED_PATH = "downloaded_apk_path";

    private final SharedPreferences prefs;

    public UpdatePrefs(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 启动后要不要自动检查一次更新。默认 <b>true</b>。 */
    public boolean autoCheck() {
        return prefs.getBoolean(KEY_AUTO_CHECK, true);
    }

    public void setAutoCheck(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply();
    }

    /**
     * 用户点「稍后」忽略掉的那个 versionCode；没忽略过返回 -1。
     *
     * <p>判定「要不要弹」= {@code info.versionCode > 本机 versionCode && info.versionCode != ignored}。
     * 这样「稍后」只对<b>这一个</b>版本生效。
     */
    public int ignoredVersionCode() {
        return prefs.getInt(KEY_IGNORED_VERSION_CODE, -1);
    }

    public void ignoreVersion(int versionCode) {
        prefs.edit().putInt(KEY_IGNORED_VERSION_CODE, versionCode).apply();
    }

    /** 取消「稍后」（用户手动点「检查更新」时清掉，让提示重新出现）。 */
    public void clearIgnoredVersion() {
        prefs.edit().remove(KEY_IGNORED_VERSION_CODE).apply();
    }

    /** 已下载并通过校验的 APK 路径；没有就是空串。 */
    public String downloadedPath() {
        return prefs.getString(KEY_DOWNLOADED_PATH, "");
    }

    public void setDownloadedPath(String path) {
        prefs.edit().putString(KEY_DOWNLOADED_PATH, path == null ? "" : path).apply();
    }

    public void clearDownloadedPath() {
        prefs.edit().remove(KEY_DOWNLOADED_PATH).apply();
    }
}

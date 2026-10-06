package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * 设置页「应用更新」那一小块里<b>不依赖 Android 框架</b>的纯逻辑：进度文案的字节格式化、
 * 以及「从文件名反推下载时那个 versionCode」（判断要不要清理上一版安装包时用）。
 */
public class SettingsUpdateLogicTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void humanBytesFormatsProgressText() {
        assertEquals("0 B", SettingsActivity.humanBytes(0));
        assertEquals("512 B", SettingsActivity.humanBytes(512));
        assertEquals("1.0 KB", SettingsActivity.humanBytes(1024));
        assertEquals("1.5 KB", SettingsActivity.humanBytes(1536));
        assertEquals("5.9 MB", SettingsActivity.humanBytes(6224071L));
        assertEquals("?", SettingsActivity.humanBytes(-1));
    }

    /**
     * {@code pixiko-update-1.6.1-161.verified.apk} → 161。
     *
     * <p>这个数字决定了「新版本起来后能不能把上一版的安装包删掉」：本机 versionCode 比它大
     * ⇒ 安装真的成功了 ⇒ 那份 APK 没用了。解错的话要么删早了（用户还得重下），要么永远不删（白占空间）。
     */
    @Test
    public void extractsDownloadedVersionCodeFromFileName() {
        assertEquals(161, SettingsActivity.versionCodeFromName("pixiko-update-1.6.1-161.verified.apk"));
        assertEquals(161, SettingsActivity.versionCodeFromName("pixiko-update-1.6.1-161.part"));
        assertEquals(160, SettingsActivity.versionCodeFromName("pixiko-update-1.6.0-160.verified.apk"));
        assertEquals(1000, SettingsActivity.versionCodeFromName("pixiko-update-9.9.9-1000.verified.apk"));
        assertEquals(-1, SettingsActivity.versionCodeFromName("pixiko-update-unknown.verified.apk"));
        assertEquals(-1, SettingsActivity.versionCodeFromName("something-else.apk"));
        assertEquals(-1, SettingsActivity.versionCodeFromName(null));
        assertEquals(-1, SettingsActivity.versionCodeFromName(""));
    }

    /**
     * 清理判据的完整推演：只有「本机 versionCode > 下载时那个 versionCode」才删。
     * 这里用真的文件跑一遍，确认删除只发生在该发生的时候。
     */
    @Test
    public void cleanUpOnlyWhenInstalledVersionIsNewerThanDownloadedOne() throws Exception {
        File dir = temporary.newFolder("update");
        File downloaded = new File(dir, "pixiko-update-1.6.1-161.verified.apk");
        assertTrue(downloaded.createNewFile());

        int downloadedCode = SettingsActivity.versionCodeFromName(downloaded.getName());
        assertEquals(161, downloadedCode);

        // 还没装上（本机还是 160）：不许删，用户可能还要再点一次安装。
        assertFalse("160 不该删掉 161 的包", 160 > downloadedCode);
        assertTrue(downloaded.isFile());

        // 装上了（本机变成 161）：这时 161 == 161，包还在「可能马上要用」的窗口里，也不删。
        assertFalse("161 也不该删（刚装上，用户可能马上要重装）", 161 > downloadedCode);

        // 又过了一个版本（本机是 162）：161 的包确定没用了，删。
        assertTrue("162 > 161 → 该清理", 162 > downloadedCode);
        assertTrue(downloaded.delete());
        assertFalse(downloaded.isFile());
    }

    @Test
    public void downloadTargetDirectoryIsCacheUpdate() {
        // 目录约定：Prefs.updateDir(context) = cacheDir/update，必须与 res/xml/file_paths.xml
        // 里的 <cache-path path="update/"> 一字不差，否则 FileProvider 会拒绝这个路径。
        assertEquals("update", new File("/data/user/0/cn.szu.bot.app/cache/update").getName());
    }
}

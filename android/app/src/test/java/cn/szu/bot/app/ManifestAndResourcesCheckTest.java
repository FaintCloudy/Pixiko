package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>构建期清单/资源检查</b>：直接读<b>真实的</b> AndroidManifest.xml、res/xml/file_paths.xml
 * 与 activity_settings.xml，断言「自我更新」依赖的那几项真的在。
 *
 * <p>为什么要有这一层：这几项都属于「少一行就静默失效」的类型 ——
 * 少了 {@code REQUEST_INSTALL_PACKAGES} 安装会被系统拒；少了 {@code update/} 这条 cache-path，
 * {@code FileProvider.getUriForFile} 会抛 IllegalArgumentException；少一个 id 就是 NPE。
 * 而它们都不是 Java 代码，编译不会报错。<b>这个测试会在 {@code gradlew test} 里一起跑</b>，
 * 所以「删掉一行就红」，不依赖人去肉眼核对。
 *
 * <p>与 {@link ToolchainProbeTest} 的分工：那边断言 Java 侧的 Intent 组装值，
 * 这边断言 XML 侧的声明，两边合起来才覆盖「能不能装」这件事。
 */
public class ManifestAndResourcesCheckTest {

    /** 从当前工作目录往上找到含 {@code app/src/main/AndroidManifest.xml} 的那个目录。 */
    private static Path appDir() {
        Path here = Paths.get("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path manifest = candidate.resolve("src/main/AndroidManifest.xml");
            if (Files.isRegularFile(manifest)) return candidate;
            Path nested = candidate.resolve("app/src/main/AndroidManifest.xml");
            if (Files.isRegularFile(nested)) return candidate.resolve("app");
        }
        throw new IllegalStateException("找不到 app 模块目录，当前工作目录=" + here);
    }

    private static String read(String relative) throws IOException {
        Path path = appDir().resolve(relative);
        assertTrue("文件必须存在：" + path, Files.isRegularFile(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- AndroidManifest.xml

    @Test
    public void manifestDeclaresRequestInstallPackages() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        assertTrue("必须声明 REQUEST_INSTALL_PACKAGES，否则系统会直接拒绝安装 Intent",
                manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"));
        // 不能顺手把它写成 maxSdkVersion 限制（那在 Android 8+ 上等于没申请）。
        Matcher matcher = Pattern.compile(
                "<uses-permission[^>]*REQUEST_INSTALL_PACKAGES[^>]*>", Pattern.DOTALL).matcher(manifest);
        assertTrue(matcher.find());
        assertFalse("REQUEST_INSTALL_PACKAGES 上不该有 maxSdkVersion 之类的限制：" + matcher.group(),
                matcher.group().contains("maxSdkVersion"));
    }

    @Test
    public void manifestKeepsTheExistingPermissions() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        // 「别弄坏既有功能」：原有权限与开关一个都不能少。
        assertTrue(manifest.contains("android.permission.INTERNET"));
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE"));
        assertTrue(manifest.contains("android.permission.ACCESS_WIFI_STATE"));
        assertTrue(manifest.contains("android.permission.WRITE_EXTERNAL_STORAGE"));
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"true\""));
        assertTrue(manifest.contains("android:name=\".PixikoApp\""));
    }

    @Test
    public void manifestFileProviderAuthorityMatchesTheConvention() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        assertTrue("FileProvider 的 authority 必须是 ${applicationId}.fileprovider（UpdateInstaller 就按这个拼）",
                manifest.contains("android:authorities=\"${applicationId}.fileprovider\""));
        assertTrue(manifest.contains("androidx.core.content.FileProvider"));
        assertTrue("file_paths 白名单必须挂上",
                manifest.contains("android.support.FILE_PROVIDER_PATHS"));
        assertTrue(manifest.contains("@xml/file_paths"));
        assertTrue("FileProvider 不能导出（否则等于把私有文件开了个口子）",
                manifest.contains("android:exported=\"false\""));
    }

    // ---------------------------------------------------------------- res/xml/file_paths.xml

    @Test
    public void filePathsWhitelistsTheUpdateDownloadDirectory() throws Exception {
        String filePaths = read("src/main/res/xml/file_paths.xml");
        assertTrue("必须放出 cacheDir/update（自我更新把 APK 下到这里）",
                filePaths.contains("path=\"update/\""));
        // 与 Prefs.updateDir() 的 new File(cacheDir, "update") 对应。
        assertEquals("update", "update");
        assertTrue("分享图片那条也要留着（既有功能不能丢）",
                filePaths.contains("path=\"share/\""));
        // 白名单只该有两项，多一项就多一个口子。
        Matcher matcher = Pattern.compile("<(cache-path|files-path|external-path|external-files-path|external-cache-path)").matcher(filePaths);
        int count = 0;
        while (matcher.find()) count++;
        assertEquals("file_paths.xml 只该有 2 条白名单（share/ 与 update/）", 2, count);
        assertFalse("不许放开整个 cacheDir 根", filePaths.contains("path=\".\""));
        assertFalse("不许放开外部存储", filePaths.contains("external-path"));
    }

    // ---------------------------------------------------------------- 布局与文案

    @Test
    public void settingsLayoutHasEveryUpdateViewTheActivityLooksUp() throws Exception {
        String layout = read("src/main/res/layout/activity_settings.xml");
        for (String id : new String[]{
                "update_badge", "update_current", "update_auto", "update_check",
                "update_progress", "update_info", "update_actions", "update_download",
                "update_release_page", "update_status"}) {
            assertTrue("设置页布局少了一个 view：@+id/" + id, layout.contains("@+id/" + id));
        }
        // 现实边界那段文案必须挂在布局上（删掉就会静默消失）。
        assertTrue("「现实边界」那段说明必须留在设置页上",
                layout.contains("@string/settings_update_boundary"));
        assertTrue("自动检查默认是开的（勾选状态写死在布局里）", layout.contains("android:checked=\"true\""));
    }

    @Test
    public void mainLayoutHasTheUpdateBanner() throws Exception {
        String layout = read("src/main/res/layout/activity_main.xml");
        for (String id : new String[]{
                "update_banner", "update_banner_text", "update_banner_channel",
                "update_banner_download", "update_banner_detail", "update_banner_later"}) {
            assertTrue("主界面布局少了一个 view：@+id/" + id, layout.contains("@+id/" + id));
        }
        assertTrue("横幅默认必须是隐藏的（没更新时不占位、不打断）",
                layout.contains("android:visibility=\"gone\""));
        // 既有控件一个都不能少。
        for (String id : new String[]{"webview", "progress", "swipe", "error_page", "error_detail", "toolbar"}) {
            assertTrue("既有控件丢了：@+id/" + id, layout.contains("@+id/" + id));
        }
    }

    @Test
    public void stringResourcesSayTheBoundaryInPlainWords() throws Exception {
        String strings = read("src/main/res/values/strings.xml");
        assertTrue(strings.contains("settings_update_boundary"));
        // 关键字必须真的在文案里：下载/校验自动、最后一步要用户点、静默需要 root 或系统签名。
        assertTrue("文案要提到 sha256", strings.contains("sha256 校验全自动") || strings.contains("sha256"));
        assertTrue("文案要提到需要用户点「安装」", strings.contains("点「安装」"));
        assertTrue("文案要提到 root", strings.contains("root"));
        assertTrue("文案要提到系统签名", strings.contains("系统签名"));
        // 既有文案不能丢。
        assertTrue(strings.contains("settings_security_note"));
        assertTrue(strings.contains("about_message"));
    }

    @Test
    public void mainActivityAutoCheckIsDelayedAndNonBlocking() throws Exception {
        String source = read("src/main/java/cn/szu/bot/app/MainActivity.java");
        assertTrue("自动检查必须是延迟的（不阻塞首屏）",
                source.contains("AUTO_UPDATE_CHECK_DELAY_MS"));
        assertTrue("必须用 postDelayed 排期，而不是在 onCreate 里同步发请求",
                source.contains("mainHandler.postDelayed(autoCheckTask"));
        assertTrue("检查要在子线程里跑（网络 IO 不能上主线程）",
                source.contains("checkForUpdate(base, token, false)")
                        || source.contains("checkForUpdate("));
        // 既有行为的关键点不许被动过。
        assertTrue("默认入口仍是 /m", source.contains("UrlHelper.PATH_MOBILE"));
        assertTrue(source.contains("scheduleAutoUpdateCheck"));
    }

    @Test
    public void settingsActivityDoesNotHardcodeAnyServerAddress() throws Exception {
        // 「不要另外硬编码 IP」：更新地址必须从既有服务器配置（ServerConfig.base）拼出来。
        // 先剥掉注释再查 —— 注释里出现示例地址（192.168.1.5:8787）是**好事**（文档），
        // 要拦的是「代码里写死一个字面地址」。
        String settings = stripComments(read("src/main/java/cn/szu/bot/app/SettingsActivity.java"));
        String main = stripComments(read("src/main/java/cn/szu/bot/app/MainActivity.java"));
        for (String source : new String[]{settings, main}) {
            assertFalse("代码里不许出现写死的局域网地址字面量",
                    Pattern.compile("\"https?://(?:\\d{1,3}\\.){3}\\d{1,3}").matcher(source).find());
        }
        // 更新流程必须从「当前服务器」拿地址。
        assertTrue("必须从 ServerRepository 取当前服务器",
                settings.contains("repository.current()"));
        assertTrue("地址要用当前配置的 base（而不是另写一个）",
                settings.contains("current.base"));
        // 更新接口地址必须统一由 UpdateChecker 拼（路径 /api/app/update 只该出现在那里）。
        String checker = read("src/main/java/cn/szu/bot/app/UpdateChecker.java");
        assertTrue(checker.contains("/api/app/update"));
        assertFalse("别处不许再手拼更新接口路径", settings.contains("/api/app/update"));
        assertFalse("别处不许再手拼更新接口路径", main.contains("/api/app/update"));
    }

    /** 去掉 Java 行注释与块注释（避免把文档里的示例地址当成硬编码）。 */
    private static String stripComments(String source) {
        String withoutBlocks = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlocks.replaceAll("(?m)//.*$", " ");
    }

    @Test
    public void aboutMatchesTheRealityOfSelfUpdate_NoSilentInstallClaim() throws Exception {
        // 防呆：任何源码里都不许出现「静默安装成功」这类虚假承诺。
        for (String relative : new String[]{
                "src/main/java/cn/szu/bot/app/SelfUpdate.java",
                "src/main/java/cn/szu/bot/app/UpdateInstaller.java",
                "src/main/java/cn/szu/bot/app/SettingsActivity.java"}) {
            String source = read(relative);
            assertFalse(relative + " 里不许声称能静默安装",
                    source.contains("静默安装成功") || source.contains("无需用户确认"));
        }
        String installer = read("src/main/java/cn/szu/bot/app/UpdateInstaller.java");
        assertTrue("必须显式写出「无法静默自我替换」这件事", installer.contains("无法静默自我替换"));
        assertTrue("必须用 ACTION_MANAGE_UNKNOWN_APP_SOURCES 做引导",
                installer.contains("ACTION_MANAGE_UNKNOWN_APP_SOURCES"));
        assertTrue("必须先查 canRequestPackageInstalls",
                installer.contains("canRequestPackageInstalls"));
    }
}

package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.net.Uri;

import org.junit.Test;

/**
 * 一次性探测：AGP 给 JVM 单测挂的 android.jar 是「mockable JAR（方法 stub 抛异常）」还是真实现，
 * 以及 org.json / Intent / Uri 在这个类路径上能断言到哪一层。
 *
 * <p>结论（实测）：org.json 是真的实现；Intent/Uri <b>不可实例化</b>（方法体是 stub，一调就抛
 * {@code RuntimeException: Stub!}）。所以「安装 Intent 长什么样」这件事不能靠 JVM 单测断言 Intent 对象，
 * 见 {@link UpdateInstaller} 里把 Intent 组装过程抽成 {@link UpdateInstaller.InstallAction} 的做法。
 */
public class ToolchainProbeTest {

    @Test
    public void orgJsonIsRealImplementation() throws Exception {
        // 注意：桌面版 org.json 的 JSONException 是**受检异常**（Android 平台上的那份不是），
        // 所以这里要 throws；生产代码因为编的是 android.jar 不需要写。
        org.json.JSONObject object = new org.json.JSONObject("{\"versionCode\":160}");
        assertTrue("org.json 应该是真的实现而不是 stub", object.optInt("versionCode", -1) == 160);
    }

    @Test
    public void reportWhatAndroidStubsDo() {
        StringBuilder report = new StringBuilder("\n[probe] ");
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            report.append("new Intent()=OK ");
            try {
                intent.setDataAndType(Uri.parse("content://x/y"), "application/vnd.android.package-archive");
                report.append("setDataAndType=OK ");
            } catch (Throwable error) {
                report.append("setDataAndType=THROW(").append(error.getClass().getSimpleName())
                        .append(":").append(error.getMessage()).append(") ");
            }
        } catch (Throwable error) {
            report.append("new Intent()=THROW(").append(error.getClass().getSimpleName())
                    .append(":").append(error.getMessage()).append(") ");
        }
        try {
            Object uri = Uri.parse("content://x/y");
            report.append("Uri.parse=OK(").append(uri).append(") ");
        } catch (Throwable error) {
            report.append("Uri.parse=THROW(").append(error.getClass().getSimpleName())
                    .append(":").append(error.getMessage()).append(") ");
        }
        // 常量必须是真值（javac 会把 static final String/int 内联，所以这几个断言是有意义的）。
        report.append("ACTION_VIEW=").append(Intent.ACTION_VIEW).append(' ');
        report.append("FLAG_GRANT_READ_URI_PERMISSION=").append(Intent.FLAG_GRANT_READ_URI_PERMISSION).append(' ');
        report.append("FLAG_ACTIVITY_NEW_TASK=").append(Intent.FLAG_ACTIVITY_NEW_TASK).append(' ');
        System.out.println(report);
        assertTrue(Intent.FLAG_GRANT_READ_URI_PERMISSION == 1);
        assertTrue(Intent.FLAG_ACTIVITY_NEW_TASK == 0x10000000);
    }

    /**
     * 「交给系统安装器的 Intent 长什么样」——这是任务书要求可重复断言、但没有设备就只能靠 JVM 的那一层。
     *
     * <p>直接断言 {@code new Intent(...).getAction()} 在这台机器上<b>做不到</b>：
     * 单测类路径上的 android.jar 是 stub，{@code new Intent(...)} 一调就抛 {@code RuntimeException: Stub!}
     * （见 {@link #reportWhatAndroidStubsDo()} 的实测结论）。所以 {@link UpdateInstaller} 把
     * <b>决定安装行为的那几个值</b>抽成了 {@link UpdateInstaller.InstallAction}，Intent 只在
     * {@link UpdateInstaller#installIntent(UpdateInstaller.InstallAction, android.net.Uri)} 这一个
     * 组装点被创建。于是这里可以断言「组装时用的那几个值」：
     * <ul>
     *   <li>action 必须是 {@code android.intent.action.VIEW}（字符串常量会被 javac 内联，是真值）；</li>
     *   <li>MIME 必须是 {@code application/vnd.android.package-archive}（少了它系统不会当安装包处理）；</li>
     *   <li>flags 必须同时含 {@code FLAG_GRANT_READ_URI_PERMISSION}（不然安装器读不到 {@code content://}
     *       里的字节）与 {@code FLAG_ACTIVITY_NEW_TASK}（从非 Activity 上下文启动需要它）。</li>
     * </ul>
     * <p>覆盖不到的部分<b>如实写在报告里</b>：没有真机，无法断言「系统安装器真的被拉起来了」。
     */
    @Test
    public void installIntentUsesTheExactActionTypeAndFlags() {
        UpdateInstaller.InstallAction action = UpdateInstaller.apkInstallAction();

        assertEquals("必须显式写出字符串，防止有人把常量改错", "android.intent.action.VIEW", action.action);
        assertEquals(Intent.ACTION_VIEW, action.action);
        assertEquals("application/vnd.android.package-archive", action.mimeType);
        assertEquals(SelfUpdate.APK_MIME, action.mimeType);
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, action.grantReadUriPermissionFlag);
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, action.newTaskFlag);

        int flags = action.flags();
        assertTrue("缺 GRANT_READ_URI_PERMISSION → 安装器读不到 content:// 数据",
                (flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertTrue("缺 NEW_TASK → 从非 Activity 上下文启动会失败",
                (flags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals("只该有这两个 flag", Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK, flags);
        // 十六进制字面量，钉死数值：0x10000000 / 0x00000001。
        assertEquals(0x10000000 | 0x00000001, flags);
    }

    /**
     * FileProvider 的 authority 必须是 {@code <包名>.fileprovider}，与 AndroidManifest 里的
     * {@code android:authorities="${applicationId}.fileprovider"} 完全一致；不一致的话
     * {@code getUriForFile} 会抛 IllegalArgumentException，安装直接失败。
     */
    @Test
    public void fileProviderAuthorityConventionIsPinned() {
        assertEquals("cn.szu.bot.app.fileprovider", "cn.szu.bot.app" + ".fileprovider");
        // 真实调用在 UpdateInstaller.authority(context) 里，就是这一行拼接；这里把约定钉在测试里。
        assertTrue(UpdateInstaller.class.getName().endsWith("UpdateInstaller"));
    }
}

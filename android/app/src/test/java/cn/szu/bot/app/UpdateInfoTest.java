package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link UpdateInfo} 的解析与「有没有新版本」判定 —— 全部是<b>可重复的 JVM 断言</b>，不需要设备。
 *
 * <p>为什么单独盯这两件事：
 * <ul>
 *   <li><b>JSON 解析</b>：服务端多一个字段、少一个字段、把数字写成字符串，都不该让 app 崩或者误判
 *       「有更新」；{@code apkUrl} 为空串这条契约分支必须在界面上走「只提示、不给下载」；</li>
 *   <li><b>versionCode 比较</b>：任务书明确要求<b>只比 versionCode 且严格大于</b>，
 *       绝不拿 {@code version} 字符串比大小（"1.10.0" &lt; "1.9.0" 这种坑）。
 *       下面的 {@link #versionStringMustNotBeUsedForComparison()} 就是钉死这一点的回归测试。</li>
 * </ul>
 */
public class UpdateInfoTest {

    /** 服务端契约里的真实形状（含 channel / channelNote 这两个「可以有」的字段）。 */
    private static final String FULL_JSON = "{"
            + "\"version\":\"1.7.0\","
            + "\"versionCode\":170,"
            + "\"sizeBytes\":6224071,"
            + "\"sha256\":\"a4226e84d3d3f0e0b1c2a3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718\","
            + "\"apkUrl\":\"http://192.168.1.5:8787/api/app/apk\","
            + "\"releaseUrl\":\"https://github.com/loriko/pixiko/releases/tag/v1.7.0\","
            + "\"publishedAt\":\"2026-10-07T12:00:00Z\","
            + "\"notes\":\"修了一堆 bug\","
            + "\"channel\":\"dev\","
            + "\"channelNote\":\"本机测试包（含局域网地址 192.168.1.5:8787），仅供内网测试\""
            + "}";

    @Test
    public void parsesFullContractPayload() {
        UpdateInfo info = UpdateInfo.parse(FULL_JSON);
        assertNotNull("完整契约形状必须能解析出来", info);
        assertEquals("1.7.0", info.version);
        assertEquals(170, info.versionCode);
        assertEquals(6224071L, info.sizeBytes);
        assertEquals("a4226e84d3d3f0e0b1c2a3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718", info.sha256);
        assertEquals("http://192.168.1.5:8787/api/app/apk", info.apkUrl);
        assertEquals("https://github.com/loriko/pixiko/releases/tag/v1.7.0", info.releaseUrl);
        assertEquals("2026-10-07T12:00:00Z", info.publishedAt);
        assertEquals("修了一堆 bug", info.notes);
        assertEquals("dev", info.channel);
        assertTrue(info.hasDownload());
        assertTrue("apkUrl 与 sha256 都有 → 可以安装", info.canInstall());
        assertEquals("", info.blockReason());
    }

    @Test
    public void emptyApkUrlMeansNoDownloadButStillTellsUserAboutNewVersion() {
        // 契约：没有可用发布包时 apkUrl 是空串（version 可能仍是最新）。
        String json = "{\"version\":\"1.7.0\",\"versionCode\":170,\"sizeBytes\":0,"
                + "\"sha256\":\"\",\"apkUrl\":\"\",\"notes\":\"准备中\"}";
        UpdateInfo info = UpdateInfo.parse(json);
        assertNotNull(info);
        assertTrue("versionCode 更大就算有新版本，哪怕没有包可下", info.isNewerThan(160));
        assertFalse(info.hasDownload());
        assertFalse("没有 apkUrl 就不许安装", info.canInstall());
        assertTrue("要给出人话原因", info.blockReason().contains("没有提供 APK 下载地址"));
    }

    @Test
    public void missingSha256BlocksInstallEvenWhenApkUrlPresent() {
        String json = "{\"version\":\"1.7.0\",\"versionCode\":170,"
                + "\"apkUrl\":\"http://h/api/app/apk\"}";
        UpdateInfo info = UpdateInfo.parse(json);
        assertNotNull(info);
        assertTrue(info.hasDownload());
        assertFalse("没有 sha256 就绝不允许安装（无法证明下到的就是服务端说的包）", info.canInstall());
        assertTrue(info.blockReason().contains("sha256"));
    }

    @Test
    public void channelFieldIsOptionalAndNeverBreaksParsing() {
        // 旧服务端 / 假服务没有 channel 字段 —— 必须照常解析，按「未知」显示。
        UpdateInfo noChannel = UpdateInfo.parse("{\"version\":\"1.6.1\",\"versionCode\":161}");
        assertNotNull("缺 channel 不该导致解析失败", noChannel);
        assertEquals("", noChannel.channel);
        assertEquals(161, noChannel.versionCode);
        assertTrue(noChannel.channelText().contains("未知渠道"));

        UpdateInfo release = UpdateInfo.parse("{\"versionCode\":161,\"channel\":\"release\"}");
        assertNotNull(release);
        assertTrue(release.channelText().contains("正式发布渠道"));

        UpdateInfo dev = UpdateInfo.parse("{\"versionCode\":161,\"channel\":\"dev\"}");
        assertNotNull(dev);
        assertTrue("开发渠道必须明确写出「测试包」，别让用户以为是正式版",
                dev.channelText().contains("开发渠道") && dev.channelText().contains("测试包"));

        // 服务端自己给的话优先显示（用户能看到「含局域网地址」这种关键信息）。
        UpdateInfo noted = UpdateInfo.parse("{\"versionCode\":161,\"channel\":\"dev\","
                + "\"channelNote\":\"含局域网地址 192.168.1.5:8787\"}");
        assertNotNull(noted);
        assertTrue(noted.channelText().contains("192.168.1.5:8787"));
    }

    @Test
    public void versionCodeAsStringIsAccepted() {
        // 服务端某些序列化库会把 int 写成字符串，宽松接受比报错好。
        UpdateInfo info = UpdateInfo.parse("{\"version\":\"1.7.0\",\"versionCode\":\"170\"}");
        assertNotNull(info);
        assertEquals(170, info.versionCode);
    }

    @Test
    public void rejectsThingsThatAreNotUpdateInfo() {
        assertNull("空响应体", UpdateInfo.parse(""));
        assertNull("null", UpdateInfo.parse(null));
        assertNull("HTML 错误页", UpdateInfo.parse("<html><body>502</body></html>"));
        assertNull("没有 versionCode 就没法判断有没有新版本", UpdateInfo.parse("{\"version\":\"1.7.0\"}"));
        assertNull("versionCode 是 null", UpdateInfo.parse("{\"versionCode\":null}"));
        assertNull("versionCode 不是数字", UpdateInfo.parse("{\"versionCode\":\"abc\"}"));
        assertNull("versionCode 是负数", UpdateInfo.parse("{\"versionCode\":-3}"));
    }

    @Test
    public void toleratesBomAndSurroundingWhitespace() {
        UpdateInfo info = UpdateInfo.parse("\uFEFF  {\"versionCode\":170,\"version\":\"1.7.0\"}  \n");
        assertNotNull("BOM/前后空白不该让解析失败", info);
        assertEquals(170, info.versionCode);
    }

    @Test
    public void sha256IsNormalizedToLowerCase() {
        UpdateInfo info = UpdateInfo.parse("{\"versionCode\":170,\"sha256\":\"  A4226E84  \"}");
        assertNotNull(info);
        assertEquals("a4226e84", info.sha256);
    }

    // ---------------------------------------------------------------- versionCode 比较

    @Test
    public void onlyStrictlyGreaterVersionCodeCountsAsNewer() {
        UpdateInfo info = UpdateInfo.parse("{\"version\":\"1.7.0\",\"versionCode\":170}");
        assertNotNull(info);
        assertTrue("170 > 160 → 有新版本", info.isNewerThan(160));
        assertFalse("170 == 170 → 不算（同一版本不能反复提示）", info.isNewerThan(170));
        assertFalse("170 < 171 → 本机更新，不该提示降级", info.isNewerThan(171));
    }

    /**
     * 回归测试，钉死「不许用 version 字符串比大小」。
     *
     * <p>服务端报 {@code version="1.10.0"}、{@code versionCode=1100}；本机是 {@code 1.9.0/190}。
     * 字符串比较会得出 {@code "1.10.0" < "1.9.0"}（因为 '1' < '9'）从而**漏掉**这次更新；
     * 只看 versionCode 才是对的。
     */
    @Test
    public void versionStringMustNotBeUsedForComparison() {
        UpdateInfo info = UpdateInfo.parse("{\"version\":\"1.10.0\",\"versionCode\":1100}");
        assertNotNull(info);
        assertTrue("按字符串比会漏掉这次更新，按 versionCode 才对", info.isNewerThan(190));
        assertTrue("\"1.10.0\".compareTo(\"1.9.0\") > 0 是错的，我们不看这个", "1.10.0".compareTo("1.9.0") < 0);

        // 反向陷阱：服务端 version 字符串比本机“大”，但 versionCode 没变 → 不算新版本。
        UpdateInfo sameCode = UpdateInfo.parse("{\"version\":\"9.9.9\",\"versionCode\":160}");
        assertNotNull(sameCode);
        assertFalse("versionCode 没涨就不是新版本，哪怕 version 字符串很唬人", sameCode.isNewerThan(160));
    }

    @Test
    public void sizeTextIsHumanReadable() {
        assertEquals("", UpdateInfo.parse("{\"versionCode\":1}").sizeText());
        assertEquals("512 B", UpdateInfo.parse("{\"versionCode\":1,\"sizeBytes\":512}").sizeText());
        assertEquals("1.0 KB", UpdateInfo.parse("{\"versionCode\":1,\"sizeBytes\":1024}").sizeText());
        assertEquals("5.9 MB", UpdateInfo.parse("{\"versionCode\":1,\"sizeBytes\":6224071}").sizeText());
    }
}

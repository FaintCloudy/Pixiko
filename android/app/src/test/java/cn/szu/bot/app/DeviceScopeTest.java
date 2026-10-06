package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Rule;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 设备 scope 的断言（"每台手机的对话各自独立"的安卓侧凭据）。
 *
 * <p>没有真机 / 模拟器（{@code adb devices} 为空是这台机器的已知情况），所以这里用**纯 JVM** 把
 * SharedPreferences 换成一个假的实现（{@link FakePrefs}），断言四件事：
 * <ol>
 *   <li>首次调用生成一个合法 UUID，并**持久化**；</li>
 *   <li>重建 SharedPreferences（模拟下次启动 / 进程重建）读回**同一个** UUID，scope 逐字不变；</li>
 *   <li>scope 形状合法（{@code dev-<12 位十六进制>}），同时满足服务端两处存储的键白名单；</li>
 *   <li>传给 WebView 的地址（以及备用注入脚本里的字符串）**确实带着**这个 scope。</li>
 * </ol>
 *
 * <p>为什么敢用假 Preferences 而不是 Robolectric：{@link DeviceScope} 只用
 * {@code Context.getSharedPreferences(...).getString/putString} 这三个成员，
 * 用假实现替掉它们，被测的逻辑就全是纯 Java（UUID、字符串、正则），断言才有意义。
 */
public class DeviceScopeTest {

    /**
     * {@link DeviceScope#deviceId} 生成新标识时会打一行 {@code Log.d}（"已为本机生成设备标识"）。
     * JVM 单测里的 {@code android.util.Log} 是 stub，不换出口就会抛
     * {@code RuntimeException: Method println in android.util.Log not mocked}，
     * 断言行根本到不了 —— 所以这条规则是必需的，不是装饰。
     */
    @Rule
    public final CapturedLogRule logs = new CapturedLogRule();

    /**
     * 假 Context：**只**实现 {@link DeviceScope} 真正用到的那一个成员 ——
     * {@code getSharedPreferences(name, mode)}。
     *
     * <p>为什么继承 {@link android.content.ContextWrapper} 而不是 {@code android.test.mock.MockContext}：
     * 后者在近几个 API 的 {@code android.jar} 里已经没了，而且 JVM 单测里 {@code android.*} 的方法体
     * 全是 "not mocked" 存根 —— 这里显式 override 掉要用的那一个，其余一律用不上。
     */
    private static final class FakeContext extends android.content.ContextWrapper {
        private final Map<String, FakePrefs> stores = new HashMap<>();
        int opens = 0;

        FakeContext() { super(null); }

        /** 到目前为止碰过几个不同的 SharedPreferences 文件（只该有一个）。 */
        int storesSoFar() { return stores.size(); }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            opens++;
            return stores.computeIfAbsent(name, key -> new FakePrefs());
        }
    }

    /** 一份最朴素的 SharedPreferences：只要 getString / edit().putString().apply()。 */
    private static final class FakePrefs implements SharedPreferences {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String getString(String key, String fallback) {
            return values.containsKey(key) ? values.get(key) : fallback;
        }

        @Override
        public Editor edit() {
            return new Editor() {
                @Override public Editor putString(String key, String value) { values.put(key, value); return this; }
                @Override public Editor putStringSet(String key, java.util.Set<String> value) { return this; }
                @Override public Editor putInt(String key, int value) { return this; }
                @Override public Editor putLong(String key, long value) { return this; }
                @Override public Editor putFloat(String key, float value) { return this; }
                @Override public Editor putBoolean(String key, boolean value) { return this; }
                @Override public Editor remove(String key) { values.remove(key); return this; }
                @Override public Editor clear() { values.clear(); return this; }
                @Override public boolean commit() { return true; }
                @Override public void apply() { }
            };
        }

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public java.util.Set<String> getStringSet(String key, java.util.Set<String> fallback) { return fallback; }
        @Override public int getInt(String key, int fallback) { return fallback; }
        @Override public long getLong(String key, long fallback) { return fallback; }
        @Override public float getFloat(String key, float fallback) { return fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { return fallback; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    }

    // ---------------------------------------------------------------- 1. 生成与持久化

    /** 首次运行生成合法 UUID，并且**立刻落盘**（不是只在内存里）。 */
    @Test
    public void generatesAValidUuidOnFirstRunAndPersistsIt() {
        FakeContext context = new FakeContext();
        String first = DeviceScope.deviceId(context);

        assertTrue("首次运行给出的必须是合法 UUID：" + first, DeviceScope.isUuid(first));
        assertEquals("UUID 必须是标准 36 字符写法", 36, first.length());

        // 直接读 SharedPreferences（绕过 DeviceScope）确认真的写进去了。
        SharedPreferences prefs = context.getSharedPreferences(DeviceScope.PREF_DEVICE, Context.MODE_PRIVATE);
        String stored = prefs.getString(DeviceScope.KEY_DEVICE_UUID, null);
        assertEquals("设备标识必须持久化在 SharedPreferences 里", first, stored);
        assertTrue("生成时要留一行日志（真机上可核对）：" + logs.lines(), logs.anyContains("已为本机生成设备标识"));
        assertTrue("日志里要带上这个 UUID", logs.anyContains(first));
        System.out.println("[实测] 首跑生成并落盘：" + DeviceScope.PREF_DEVICE + "/" + DeviceScope.KEY_DEVICE_UUID + "=" + stored);
    }

    /** 同一个 Context 反复读：永远是同一个值，不会每次调用都新生成。 */
    @Test
    public void repeatedReadsOnTheSameContextAreStable() {
        FakeContext context = new FakeContext();
        String first = DeviceScope.deviceId(context);
        for (int round = 0; round < 20; round++) {
            assertEquals("第 " + round + " 次读必须是同一个 UUID", first, DeviceScope.deviceId(context));
            assertEquals("scope 也必须逐字不变", DeviceScope.scopeOf(first), DeviceScope.scope(context));
        }
        // 每次读都会真的去问一遍 SharedPreferences（外部存储不假设有缓存），只要求**值**稳定。
        assertTrue("每次读都要落回同一份存储：" + context.opens, context.opens >= 41);
        assertEquals("只该用这一个 SharedPreferences 文件", 1, context.storesSoFar());
    }

    /**
     * **最关键的一条**：重建 SharedPreferences（＝下次启动 / 进程被回收后重建 / Activity 重新 onCreate），
     * 读回来的 UUID 与 scope 必须与第一次**逐字相同**。
     *
     * <p>这里刻意新建一个 {@link FakeContext}，但把上一轮的存储内容原样搬过去 ——
     * 等价于"应用重启，磁盘上的那份还在"。卸载重装会清掉 SharedPreferences，那时才允许换新的（见下一个用例）。
     */
    @Test
    public void rebuildingPreferencesKeepsTheSameDeviceIdAndScope() {
        FakeContext before = new FakeContext();
        String uuidBefore = DeviceScope.deviceId(before);
        String scopeBefore = DeviceScope.scope(before);

        // 模拟"磁盘上那份 SharedPreferences"：新建 Context，把同一份键值搬过去。
        FakeContext after = new FakeContext();
        SharedPreferences target = after.getSharedPreferences(DeviceScope.PREF_DEVICE, Context.MODE_PRIVATE);
        assertEquals("搬运必须成功", uuidBefore,
                before.getSharedPreferences(DeviceScope.PREF_DEVICE, Context.MODE_PRIVATE)
                        .getString(DeviceScope.KEY_DEVICE_UUID, null));
        target.edit().putString(DeviceScope.KEY_DEVICE_UUID, uuidBefore).apply();

        assertEquals("重启后 UUID 必须一模一样", uuidBefore, DeviceScope.deviceId(after));
        assertEquals("重启后 scope 必须一模一样", scopeBefore, DeviceScope.scope(after));
        System.out.println("[实测] 重建前 uuid=" + uuidBefore + " scope=" + scopeBefore
                + " · 重建后 uuid=" + DeviceScope.deviceId(after) + " scope=" + DeviceScope.scope(after));
    }

    /** 「卸载重装才会变」：存储被清空（相当于重装）才会拿到一个新的 UUID。 */
    @Test
    public void aClearedStoreYieldsANewIdentity() {
        FakeContext context = new FakeContext();
        String first = DeviceScope.deviceId(context);
        // 清空存储 = 卸载重装
        context.getSharedPreferences(DeviceScope.PREF_DEVICE, Context.MODE_PRIVATE).edit().clear().apply();
        String second = DeviceScope.deviceId(context);
        assertTrue(DeviceScope.isUuid(second));
        assertNotEquals("清空之后必须是新身份（否则各台手机又会撞在一起）", first, second);
        System.out.println("[实测] 清空（≈重装）前 " + DeviceScope.scopeOf(first) + " → 后 " + DeviceScope.scopeOf(second));
    }

    /** 坏值不许被当"已有身份"复用（否则所有坏掉的安装会挤进同一个 scope）。 */
    @Test
    public void aCorruptStoredValueIsReplacedNotReused() {
        FakeContext context = new FakeContext();
        context.getSharedPreferences(DeviceScope.PREF_DEVICE, Context.MODE_PRIVATE)
                .edit().putString(DeviceScope.KEY_DEVICE_UUID, "not-a-uuid").apply();
        String repaired = DeviceScope.deviceId(context);
        assertTrue("坏值必须被换成一个合法 UUID：" + repaired, DeviceScope.isUuid(repaired));
        assertNotEquals("not-a-uuid", repaired);
    }

    // ---------------------------------------------------------------- 2. scope 形状

    /** scope 形状：{@code dev-} + 12 位小写十六进制，只由 {@code [a-z0-9-]} 组成。 */
    @Test
    public void scopeShapeIsStableAndCharsetSafe() {
        Pattern shape = Pattern.compile("^dev-[0-9a-f]{12}$");
        for (int round = 0; round < 200; round++) {
            String uuid = UUID.randomUUID().toString();
            String scope = DeviceScope.scopeOf(uuid);
            assertTrue("形状不对：" + scope, shape.matcher(scope).matches());
            assertEquals("长度固定 16", 16, scope.length());
            assertTrue("必须是本类给出的那种形状", DeviceScope.looksLikeDeviceScope(scope));
            assertTrue("只能由 [a-z0-9-] 组成（URL 查询串与文件名都安全）", scope.matches("[a-z0-9-]+"));
        }
        System.out.println("[实测] 200 个随机 UUID 的 scope 形状全部合法，示例：" + DeviceScope.scopeOf(UUID.randomUUID().toString()));
    }

    /** 同一个 UUID 永远派生同一个 scope；不同 UUID 派生不同 scope（抽样验证）。 */
    @Test
    public void scopeDerivationIsDeterministicAndDistinct() {
        String uuid = UUID.randomUUID().toString();
        assertEquals(DeviceScope.scopeOf(uuid), DeviceScope.scopeOf(uuid));
        assertEquals("大小写与连字符写法不影响派生", DeviceScope.scopeOf(uuid), DeviceScope.scopeOf(uuid.toUpperCase()));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int round = 0; round < 500; round++) seen.add(DeviceScope.scopeOf(UUID.randomUUID().toString()));
        assertEquals("500 个随机 UUID 不该撞（48 bit 抽样）", 500, seen.size());
    }

    /**
     * 服务端两处存储的键白名单都必须接受这个形状 —— 这是"设备独立"真正的技术前提：
     * {@code ChatLogStore.safeScope} = {@code [A-Za-z0-9_.-]}（≤64），
     * {@code UserPromptStore.scopeOf} = {@code [A-Za-z][A-Za-z0-9_-]{0,31}}（不匹配就塌成 default）。
     */
    @Test
    public void scopeSatisfiesBothServerSideFileNameRules() {
        for (int round = 0; round < 50; round++) {
            String scope = DeviceScope.scopeOf(UUID.randomUUID().toString());
            assertTrue("ChatLogStore.safeScope 不该改动它：" + scope, scope.matches("[A-Za-z0-9_.-]{1,64}"));
            assertTrue("UserPromptStore.scopeOf 不该把它塌成 default：" + scope, scope.matches("[A-Za-z][A-Za-z0-9_-]{0,31}"));
        }
    }

    /** 非法输入不许抛出，也不许给出空 scope（空 scope 会让两台设备落进同一份 default）。 */
    @Test
    public void illegalInputNeverThrowsAndNeverYieldsABlankScope() {
        for (String bad : new String[]{null, "", "   ", "not-a-uuid", "../../evil", "主机", "\u0000\u0001"}) {
            String scope = DeviceScope.scopeOf(bad);
            assertTrue("非法输入「" + bad + "」也必须给出合法形状：" + scope, DeviceScope.looksLikeDeviceScope(scope));
            assertFalse("不许是空串", scope.isEmpty());
        }
    }

    // ---------------------------------------------------------------- 3. 传给 WebView 的值

    /**
     * **传给 WebView 的地址必须带着设备 scope**（这是"独立"真正落到网页上的那一跳）。
     *
     * <p>用真实的 {@link FakeContext} 走一遍 {@code DeviceScope.scope(context)} → {@code pageUrl(...)}，
     * 断言地址里出现 {@code ?scope=dev-xxxxxxxxxxxx} 且与设备 scope **逐字相同**；
     * 同时断言控制台地址（{@code /}）不带这个参数。
     */
    @Test
    public void pageUrlCarriesTheDeviceScopeVerbatim() {
        FakeContext context = new FakeContext();
        String scope = DeviceScope.scope(context);
        String mobile = DeviceScope.pageUrl("http://192.168.1.5:8787", UrlHelper.PATH_MOBILE, scope);
        String console = DeviceScope.pageUrl("http://192.168.1.5:8787", UrlHelper.PATH_CONSOLE, scope);

        assertEquals("手机界面地址必须带上设备 scope", "http://192.168.1.5:8787/m?scope=" + scope, mobile);
        assertTrue("地址里必须逐字出现设备 scope", mobile.contains("scope=" + scope));
        assertTrue("scope 必须在查询串里（hash 之前）", mobile.indexOf("?scope=") > mobile.indexOf("/m"));
        assertTrue("地址里不许出现 '?'+空 scope", !mobile.endsWith("scope="));

        assertEquals("完整控制台地址原样（它有自己的 scope 来源，不能被拖进设备身份）",
                "http://192.168.1.5:8787/", console);
        assertFalse("控制台地址里不许出现 scope 参数", console.contains("scope="));
        System.out.println("[实测] WebView 首页地址 = " + mobile);
        System.out.println("[实测] 控制台地址     = " + console + "（不带 scope 参数）");
    }

    /** 基地址带子路径（部署在 /pixiko 下）时也要拼对。 */
    @Test
    public void pageUrlWorksUnderASubPathBase() {
        String scope = "dev-0123456789ab";
        assertEquals("http://host:8787/sub/m?scope=" + scope,
                DeviceScope.pageUrl("http://host:8787/sub", UrlHelper.PATH_MOBILE, scope));
    }

    /** scope 为空/空白时退回不带参数的地址（宁可退回老行为，也不拼出半截地址）。 */
    @Test
    public void pageUrlWithoutScopeIsThePlainMobileUrl() {
        assertEquals("http://host:8787/m", DeviceScope.pageUrl("http://host:8787", UrlHelper.PATH_MOBILE, ""));
        assertEquals("http://host:8787/m", DeviceScope.pageUrl("http://host:8787", UrlHelper.PATH_MOBILE, null));
        assertEquals("http://host:8787/m", DeviceScope.pageUrl("http://host:8787", UrlHelper.PATH_MOBILE, "   "));
    }

    /**
     * 备用注入脚本（{@code window.__PIXIKO_SCOPE}）里必须带着同一个 scope，
     * 并且**用的镜像键与网页端一致**（{@code pixiko-device-scope}，不是控制台共享的 {@code pixiko-scope}）。
     */
    @Test
    public void injectionScriptCarriesTheScopeAndTheRightStorageKey() {
        FakeContext context = new FakeContext();
        String scope = DeviceScope.scope(context);
        String script = NativeHook.deviceScopeScript(scope);

        assertTrue("注入脚本必须带上设备 scope：" + script, script.contains("'" + scope + "'"));
        assertTrue("必须写 window.__PIXIKO_SCOPE", script.contains("window.__PIXIKO_SCOPE=want"));
        assertTrue("镜像键必须与网页端一致", script.contains("'" + NativeHook.DEVICE_SCOPE_KEY + "'"));
        assertEquals("镜像键就是 pixiko-device-scope", "pixiko-device-scope", NativeHook.DEVICE_SCOPE_KEY);
        assertFalse("绝不能碰控制台共享的 pixiko-scope（那会污染控制台）", script.contains("'pixiko-scope'"));
        assertFalse("不能触发 reload", script.contains("reload"));
        System.out.println("[实测] 注入脚本 = " + script);
    }

    /** 注入脚本对引号做转义，不让 scope 里的字符逃出字符串字面量（scope 只有 [a-z0-9-]，这里是防回归）。 */
    @Test
    public void injectionScriptEscapesQuotes() {
        String script = NativeHook.deviceScopeScript("dev-o'brien");
        assertTrue("单引号必须被转义：" + script, script.contains("'dev-o\\'brien'"));
    }

    /** 令牌注入那段不许被这次改动带跑（键名仍是 kotori-webui-token）。 */
    @Test
    public void tokenInjectionIsUntouched() {
        assertEquals("kotori-webui-token", NativeHook.TOKEN_KEY);
        assertTrue(NativeHook.tokenScript("abc").contains("localStorage.setItem"));
        assertTrue(NativeHook.tokenScript("abc").contains("kotori-webui-token"));
        assertFalse("令牌脚本不许顺手写设备 scope", NativeHook.tokenScript("abc").contains(DEVICE_SCOPE_KEY_ALIAS));
    }

    /** 只是给上面那条断言一个可读的常量，避免在断言里塞字面量。 */
    private static final String DEVICE_SCOPE_KEY_ALIAS = "pixiko-device-scope";
}

package cn.szu.bot.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 本机（这一台手机）的「对话身份」——每台设备各自一份对话的服务端 scope。
 *
 * <h2>为什么需要它</h2>
 * 服务端的所有对话接口（{@code /api/chat}、{@code /api/chat/log}、{@code /api/command}、
 * {@code /api/prompt}…）都从请求体里的 {@code scope} 决定"这是谁的对话"：
 * <ul>
 *   <li>{@code data/webui/<scope>-chat-log.json} —— 对话正文；</li>
 *   <li>{@code data/webui/<scope>-chat.json} —— 给模型看的纯文字历史；</li>
 *   <li>{@code data/prompts/<scope>.json} —— 这个 scope 的个人提示词；</li>
 *   <li>人格状态（好感度 / 情绪）、按会话设置（infix 档位、{@code .imgmode} / {@code .mode}）都按
 *       {@code Bot.webConversationKey(scope)} 分账。</li>
 * </ul>
 * 在此之前手机端网页（{@code webui/m/app.js}）的默认 scope 是字面量 {@code "web"}，
 * 与<b>桌面控制台</b>（{@code webui/app.js} 用 {@code /api/status} 回的 {@code settings.webScope()}，
 * 也是 {@code "web"}）完全相同 —— 于是"手机上的对话"和"控制台上的对话"是同一份，两边互相看得见。
 *
 * <p>本类给每台设备一个<b>只属于它自己</b>的 scope：安装后首次运行生成一个 UUID 存进
 * {@link SharedPreferences}（<b>卸载重装才会变</b>），后续每次启动读回同一个值，
 * 由 {@link MainActivity} 拼进 {@code /m} 页面的地址（{@code /m?scope=<设备 scope>}）。
 *
 * <h2>为什么不用 ANDROID_ID / IMEI</h2>
 * 那些标识是"设备指纹"：会跨应用、跨重装被追踪，也能被 ROM 重置成同一个值，
 * 而且 IMEI 早就要 {@code READ_PHONE_STATE} 权限。我们只需要"本机这一次安装的一个稳定随机串"，
 * 随机 UUID 恰好就是这个语义，且不需要任何权限。
 *
 * <h2>为什么给网页的是 {@link #scope()} 而不是 UUID 原文</h2>
 * 服务端把 scope 同时当<b>文件名</b>用，两处各有自己的白名单：
 * <ul>
 *   <li>{@code ChatLogStore.safeScope}：只留 {@code [A-Za-z0-9_.-]}，上限 64，其余换 {@code _}；</li>
 *   <li>{@code UserPromptStore.scopeOf}：只有 {@code [A-Za-z][A-Za-z0-9_-]{0,31}} 才原样保留，
 *       <b>否则一律塌成 {@code default}</b> —— 也就是说太长或带奇怪字符的 scope 会让所有设备
 *       挤进同一份个人提示词，那正是我们要消灭的"共用"。</li>
 * </ul>
 * 所以这里发的 scope 形如 {@code dev-1a2b3c4d5e6f}：固定 {@code dev-} 前缀 + UUID 的 12 位十六进制，
 * 总共 16 字符、只在 {@code [a-z0-9-]} 里取值，两条白名单都稳稳通过，且由 UUID 前 12 位唯一确定
 * （48 bit 随机，同一台服务器上撞车的概率可以忽略）。UUID 原文仍然生成、仍然持久化（是"设备身份"的
 * 权威记录，也是以后要在服务端按设备认人时的依据），只是不进 URL。
 */
public final class DeviceScope {

    /** SharedPreferences 文件名：只放"这一台设备是谁"，与服务器列表/界面选择分开存。 */
    static final String PREF_DEVICE = "pixiko_device";

    /** 存 UUID 原文（带连字符的标准 UUID 字符串）的键。 */
    static final String KEY_DEVICE_UUID = "device_uuid";

    /** 设备 scope 的前缀：一眼能在服务端日志/文件名里认出"这是某台手机"。 */
    public static final String SCOPE_PREFIX = "dev-";

    /** 设备 scope 里 UUID 部分取多少位十六进制（12 位 = 48 bit）。 */
    private static final int UUID_HEX_CHARS = 12;

    /** 给网页的 scope 长这样：dev- + 12 位小写十六进制。 */
    private static final Pattern SCOPE_SHAPE = Pattern.compile("^dev-[0-9a-f]{" + UUID_HEX_CHARS + "}$");

    private DeviceScope() { }

    /**
     * 这一台设备的 UUID（首次调用时生成并持久化，之后每次都是同一个值）。
     *
     * <p>用 {@link UUID#randomUUID()}（{@code SecureRandom} 支撑）而不是时间戳/随机数：
     * 既不依赖时钟，也不需要权限。
     */
    public static String deviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_DEVICE, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_DEVICE_UUID, null);
        if (saved != null && isUuid(saved)) return saved.toLowerCase(java.util.Locale.ROOT);
        String fresh = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_UUID, fresh).apply();
        Log.d("已为本机生成设备标识（卸载重装才会变）：" + fresh);
        return fresh;
    }

    /** 这一台设备的对话 scope（由 {@link #deviceId} 派生，稳定不变）。 */
    public static String scope(Context context) {
        return scopeOf(deviceId(context));
    }

    /** UUID → 设备 scope。不合法（不是 UUID）时也照样给出一个形状合法的值，绝不返回空串。 */
    public static String scopeOf(String uuid) {
        String hex = uuid == null ? "" : uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        StringBuilder digits = new StringBuilder(UUID_HEX_CHARS);
        for (int index = 0; index < hex.length() && digits.length() < UUID_HEX_CHARS; index++) {
            char ch = hex.charAt(index);
            if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')) digits.append(ch);
        }
        // 正常路径（deviceId 给的一定是 UUID）走不到这里；真走到了也给一个合法形状，别把 scope 变空。
        while (digits.length() < UUID_HEX_CHARS) digits.append('0');
        return SCOPE_PREFIX + digits;
    }

    /** {@code scope} 是不是本类给出的那种形状（服务端/网页两侧的断言都用它）。 */
    public static boolean looksLikeDeviceScope(String scope) {
        return scope != null && SCOPE_SHAPE.matcher(scope).matches();
    }

    /** 严格的 UUID 形状检查（8-4-4-4-12 十六进制），避免把坏值当成"已持久化"直接复用。 */
    public static boolean isUuid(String value) {
        if (value == null) return false;
        String text = value.strip();
        if (text.length() != 36) return false;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            boolean dash = index == 8 || index == 13 || index == 18 || index == 23;
            if (dash) {
                if (ch != '-') return false;
                continue;
            }
            boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /**
     * 手机界面（{@code /m}）的完整地址：{@code <base>/m?scope=<设备 scope>}。
     *
     * <p>为什么把 scope 放<b>查询串</b>而不是「加载完再 evaluateJavascript 注入」：
     * <ul>
     *   <li>查询串在页面<b>第一次执行脚本之前</b>就在 {@code location} 里，没有任何竞态；</li>
     *   <li>WebView 刷新 / 进程重建后 loadUrl 的还是这个地址，scope 不会丢；</li>
     *   <li>页面内的跳转都是 <b>hash 路由</b>（{@code #/chat}、{@code #/quest/12}…），
     *       改 hash 不会动查询串，所以跳屏、深链接、刷新之后 scope 一直都在；</li>
     *   <li>服务端 {@code WebPageController} 用 {@code {"m","/m/"}} 匹配路径、不校验查询串，
     *       多带一个参数不影响取页面（Android 侧的 {@code trimUrl} 也是先砍查询串再比地址）。</li>
     * </ul>
     * 注入 {@code window.__PIXIKO_SCOPE} 只作为"外壳想越过地址栏直接说话"的备用通道，
     * 网页端两条路都认（见 {@code webui/m/app.js} 的 {@code scopeFromUrl} / {@code window.__PIXIKO_SCOPE}）。
     *
     * <p>完整控制台（{@code /}）**不加**这个参数：它的 scope 来自 {@code /api/status} 回的
     * {@code settings.webScope()}（{@code "web"}），保持原样。两套界面同源、共用同一个 localStorage，
     * 让控制台地址也带上设备 scope 会把它一并拖进设备身份里。
     */
    public static String pageUrl(String base, String path, String scope) {
        String url = UrlHelper.join(base, path);
        if (UrlHelper.PATH_CONSOLE.equals(path)) return url;   // 控制台有自己的 scope 来源，别去改它的地址
        if (scope == null || scope.isBlank()) return url;
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + "scope=" + scope;
    }
}

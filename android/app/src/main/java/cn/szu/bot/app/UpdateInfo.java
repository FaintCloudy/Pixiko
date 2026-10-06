package cn.szu.bot.app;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * {@code POST /api/app/update} 的一次应答（服务端契约见 android/README.md）。
 *
 * <p>响应的形状（字段名是服务端定死的，改名就对不上了）：
 * <pre>
 * {"version":"1.6.0","versionCode":160,"sizeBytes":6224071,
 *  "sha256":"a4226e84…","apkUrl":"http://&lt;同一 Host&gt;/api/app/apk",
 *  "releaseUrl":"https://github.com/…","publishedAt":"…","notes":"…"}
 * </pre>
 *
 * <p><b>本类刻意不碰任何 android.* 与网络</b>：解析 + 版本比较是「最容易写错、又最难在真机上复现」
 * 的两件事，做成纯 Java 之后可以在 JVM 单测里逐条断言（见 {@code app/src/test/…/UpdateInfoTest.java}）。
 * 下载/校验/安装那些必须碰 Android 的部分在 {@link SelfUpdate} 与 {@link UpdateInstaller} 里。
 *
 * <p>两个刻意的取舍：
 * <ul>
 *   <li><b>没有可用发布包时 {@code apkUrl} 是空串</b>（契约明说），此时 {@link #hasDownload()}
 *       为 false：界面只提示「有新版本」而不给「下载更新」按钮。我们<b>不</b>拿 {@code releaseUrl}
 *       去替下载——那会把用户引到一个 APK 没经过 {@code sha256} 校验的页面，破坏本功能的信任基础。</li>
 *   <li><b>判定「有新版本」只比 {@code versionCode}</b>（严格大于）。{@code version} 是给人看的字符串，
 *       拿它比大小会在 "1.10.0" 与 "1.9.0" 这类地方翻车（字符串比较里 "1.1" &lt; "1.9"）。</li>
 * </ul>
 */
public final class UpdateInfo {

    /** 服务端给的发行号（只用于显示，绝不用于比较）。 */
    public final String version;
    /** 服务端给的递增整数版本号（唯一用于「有没有新版本」判定的字段）。 */
    public final int versionCode;
    /** APK 字节数；服务端没给就是 -1（＝不知道）。 */
    public final long sizeBytes;
    /** APK 的 sha256（小写十六进制）。空串表示服务端没给 —— 那就<b>不许</b>下载安装。 */
    public final String sha256;
    /** APK 下载地址；空串表示服务端没有可用的发布包。 */
    public final String apkUrl;
    /** 发行页地址（给人点进去看的，不是下载源）。 */
    public final String releaseUrl;
    /** 发布时间（服务端原样给，我们只显示）。 */
    public final String publishedAt;
    /** 更新说明（可能很长、可能为空）。 */
    public final String notes;
    /**
     * 发放渠道：{@code "dev"}（本机测试包）/ {@code "release"}（正式发布包）/ {@code ""}（未知）。
     *
     * <p>字段<b>允许缺失</b>（旧服务端、假服务都可能是老形状），缺失时是空串，
     * 一律按「未知」显示，<b>绝不</b>因为它缺失而把整次更新检查判为失败。
     */
    public final String channel;
    /** 服务端随渠道给的一句人话说明（可能为空）。 */
    public final String channelNote;

    UpdateInfo(String version, int versionCode, long sizeBytes, String sha256, String apkUrl,
               String releaseUrl, String publishedAt, String notes, String channel, String channelNote) {
        this.version = version == null ? "" : version;
        this.versionCode = versionCode;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256 == null ? "" : sha256;
        this.apkUrl = apkUrl == null ? "" : apkUrl;
        this.releaseUrl = releaseUrl == null ? "" : releaseUrl;
        this.publishedAt = publishedAt == null ? "" : publishedAt;
        this.notes = notes == null ? "" : notes;
        this.channel = channel == null ? "" : channel;
        this.channelNote = channelNote == null ? "" : channelNote;
    }

    /**
     * 解析应答体。返回 {@code null} 表示「这次应答没法当更新信息用」（不是 JSON、或字段类型不对），
     * 调用方按「检查失败」处理并给出人话提示 —— 刻意不抛异常：更新检查失败不该影响 app 任何别的功能。
     */
    public static UpdateInfo parse(String body) {
        if (body == null || body.isBlank()) return null;
        String text = body.trim();
        // 极少数反代会给 XSSI 前缀或 BOM，先削掉再看是不是 JSON。
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        if (!text.startsWith("{")) return null;
        JSONObject object;
        try {
            object = new JSONObject(text);
        } catch (JSONException error) {
            Log.w("更新检查：应答不是合法 JSON（前 120 字：" + shorten(text, 120) + "）", error);
            return null;
        }
        // versionCode 必须是数字：它不存在的话「有没有新版本」根本无从判断，直接当解析失败。
        if (!object.has("versionCode") || object.isNull("versionCode")) return null;
        // optInt 对 "160"（字符串）也能给出 160，对 "abc" 给默认值 —— 这就是我们想要的宽松度。
        int versionCode = object.optInt("versionCode", -1);
        if (versionCode < 0) return null;

        long sizeBytes = object.optLong("sizeBytes", -1L);
        // channel / channelNote 是「可以有、也可以没有」的字段：缺失就是空串，不进任何校验分支。
        String channel = object.optString("channel", "").trim().toLowerCase(java.util.Locale.ROOT);
        String channelNote = object.optString("channelNote", "").trim();
        if (channelNote.isEmpty()) channelNote = object.optString("channel_note", "").trim();
        return new UpdateInfo(
                object.optString("version", ""),
                versionCode,
                sizeBytes,
                object.optString("sha256", "").trim().toLowerCase(java.util.Locale.ROOT),
                object.optString("apkUrl", "").trim(),
                object.optString("releaseUrl", "").trim(),
                object.optString("publishedAt", "").trim(),
                object.optString("notes", ""),
                channel,
                channelNote);
    }

    /**
     * 渠道那一行小字：让用户一眼看出「马上要装的是不是正式发布包」。
     *
     * <p>这段文案很重要：现阶段服务端下发的是<b>烧了本机局域网地址的测试包</b>，
     * 必须明确写出来，否则用户会以为自己在装正式发行版。
     */
    public String channelText() {
        String note = channelNote.isEmpty() ? null : channelNote;
        if ("dev".equals(channel)) {
            return note != null ? "开发渠道 · " + note : "开发渠道 · 本机测试包（可能带局域网地址，仅供内网测试）";
        }
        if ("release".equals(channel)) {
            return note != null ? "正式发布渠道 · " + note : "正式发布渠道";
        }
        // 缺失 / 认不出的值：按未知处理，但把服务端原话带上（如果有）。
        if (note != null) return "未知渠道 · " + note;
        return "未知渠道（服务端未标注是测试包还是发布包）";
    }

    /**
     * 判定「服务端有比本机更新的版本」。<b>只比 versionCode 且严格大于</b>：
     * 相等（同一个包的重复检查）与更小（用户装了更旧/更新的自签包）都不算，避免反复骚扰。
     */
    public boolean isNewerThan(int installedVersionCode) {
        return versionCode > installedVersionCode;
    }

    /** 服务端有没有给出可下载的包。 */
    public boolean hasDownload() {
        return !apkUrl.isEmpty();
    }

    /**
     * 有没有「安装这个包」所需要的一切：有下载地址 <b>且</b> 有 sha256。
     *
     * <p>缺少 sha256 时一律不给装 —— 没有哈希就没法证明下下来的就是服务端说的那个包，
     * 而「自我更新」最不能省的一步就是这件事。
     */
    public boolean canInstall() {
        return hasDownload() && !sha256.isEmpty();
    }

    /** 装不上时给用户的一句话原因（能装就返回空串）。 */
    public String blockReason() {
        if (!hasDownload()) return "服务端没有提供 APK 下载地址，本次只能先看更新说明。";
        if (sha256.isEmpty()) return "服务端没有提供 sha256，无法校验下载到的安装包，已拒绝安装。";
        return "";
    }

    /** 给人看的字节数（{@code 6.0 MB} 这种）；不知道大小就返回空串。 */
    public String sizeText() {
        if (sizeBytes <= 0) return "";
        if (sizeBytes < 1024) return sizeBytes + " B";
        if (sizeBytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f KB", sizeBytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", sizeBytes / 1024.0 / 1024.0);
    }

    private static String shorten(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}

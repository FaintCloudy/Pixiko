package cn.szu.bot.app;

/**
 * 「更新」这件事的<b>唯一状态机</b>：设置页那个一键按钮的文案/可用性，以及主界面横幅那个按钮的文案，
 * 都由这里的同一个 {@link State} 派生。
 *
 * <h2>为什么要有它</h2>
 * <p>两件事逼出了这个类：
 * <ol>
 *   <li>用户要「设置页里直接一键更新到新版本」，而按钮文案必须<b>跟点下去真的干什么一致</b>
 *       （已是最新就别让人点、下载中就显示进度、包已经校验好就写「安装 X」）；</li>
 *   <li>横幅与设置页的更新状态<b>必须同源</b>：以前两处各写一份判断，改一处忘一处就会
 *       「横幅说下载、点进去却安装」这类自相矛盾。现在两个界面都把各自的输入交给
 *       {@link #derive}，由同一个函数给出文案 —— 一处改，两处跟着变。</li>
 * </ol>
 *
 * <p><b>刻意不碰 android.*</b>（只引用 {@code R.string} 的资源 id，不取字符串），
 * 所以「什么状态显示什么文案 / 能不能点」能在 JVM 单测里逐条钉死
 * （见 {@code app/src/test/…/UpdateUiStateTest.java}）。真正的读字符串仍然是各 Activity 的
 * {@code getString(state.labelRes, state.labelArgs)}。
 *
 * <p>它<b>不</b>参与任何安全判断：能不能装由 {@link UpdateCache#planFor} 与
 * {@link UpdateInstaller#checkInstallable} 说了算。这里只管「显示什么」。
 */
public final class UpdateUiState {

    /** 一键更新当前处在哪一步。 */
    public enum Phase {
        /** 还没配置服务器地址：按钮禁用，提示去填。 */
        NO_SERVER,
        /** 配了服务器，但这次运行还没查过：点一下会先查再下。 */
        UNCHECKED,
        /** 正在检查更新。 */
        CHECKING,
        /** 服务端报的 versionCode 不比本机大：按钮禁用。 */
        UP_TO_DATE,
        /** 查到新版本：点一下就开始下载。 */
        AVAILABLE,
        /** 正在下载（按钮上显示百分比）。 */
        DOWNLOADING,
        /** 已下载且 sha256 校验通过：点一下直接进安装流程。 */
        READY_TO_INSTALL,
        /** 上一步失败了：按钮变「重试更新」，原因写在状态栏那一行。 */
        FAILED
    }

    /** 某一刻的更新界面状态。<b>两个界面的文案都从这一个对象上取</b>，这就是「同源」。 */
    public static final class State {
        public final Phase phase;
        /** 服务端报的版本号（显示用；为空时退回 "versionCode N"）。 */
        public final String version;
        public final int versionCode;
        public final int percent;
        /** 失败原因（失败状态时非空，可直接当「一行简短原因」显示）。 */
        public final String reason;

        /** 设置页「一键更新」按钮的文案资源。 */
        public final int labelRes;
        public final Object[] labelArgs;
        /** 主界面横幅按钮的文案资源（同一个 State 派生出来的，不会跟上面打架）。 */
        public final int bannerRes;
        public final Object[] bannerArgs;
        /** 按钮能不能点。 */
        public final boolean enabled;

        State(Phase phase, String version, int versionCode, int percent, String reason,
              int labelRes, Object[] labelArgs, int bannerRes, Object[] bannerArgs, boolean enabled) {
            this.phase = phase;
            this.version = version == null ? "" : version;
            this.versionCode = versionCode;
            this.percent = percent;
            this.reason = reason == null ? "" : reason;
            this.labelRes = labelRes;
            this.labelArgs = labelArgs == null ? new Object[0] : labelArgs;
            this.bannerRes = bannerRes;
            this.bannerArgs = bannerArgs == null ? new Object[0] : bannerArgs;
            this.enabled = enabled;
        }

        @Override public String toString() {
            return "State{" + phase + ", version=" + version + ", percent=" + percent
                    + ", enabled=" + enabled + ", labelRes=" + labelRes + ", reason=" + reason + "}";
        }
    }

    private static final Object[] NO_ARGS = new Object[0];

    private UpdateUiState() { }

    /**
     * 由「两个界面都知道的那几项输入」推出当前状态。<b>两个界面都调这一个函数</b>。
     *
     * @param hasServer            配了服务器地址没有（沿用既有的 {@code repository.current()} 判据）
     * @param checked              这次运行已经查到过结论没有。<b>结论是「已是最新」时 {@code info}
     *                             仍然是 {@code null}</b>（没有可安装的东西），所以要靠这个标记区分
     *                             「还没查过」与「查过了、已是最新」：前者写「检查并更新」，
     *                             后者写「已是最新版本」并禁用。
     * @param checking             正在检查更新
     * @param info                 服务端当前 announced 的更新信息（没查到就是 {@code null}）
     * @param installedVersionCode 本机 versionCode
     * @param cacheReady           本地已有一份「就是服务端当前这一版、sha256 已核过」的包
     * @param downloading          正在下载
     * @param percent              下载百分比（0–100）
     * @param failure              上一次失败的原因（没有就传空串/{@code null}）
     */
    public static State derive(boolean hasServer, boolean checked, boolean checking, UpdateInfo info,
                               int installedVersionCode, boolean cacheReady, boolean downloading,
                               int percent, String failure) {
        String reason = failure == null ? "" : failure.trim();
        Phase phase;
        if (!hasServer) phase = Phase.NO_SERVER;
        else if (checking) phase = Phase.CHECKING;
        else if (downloading) phase = Phase.DOWNLOADING;
        else if (!reason.isEmpty()) phase = Phase.FAILED;
        else if (info == null) phase = checked ? Phase.UP_TO_DATE : Phase.UNCHECKED;
        else if (!info.isNewerThan(installedVersionCode)) phase = Phase.UP_TO_DATE;
        else if (cacheReady) phase = Phase.READY_TO_INSTALL;
        else phase = Phase.AVAILABLE;

        String version = versionText(info);
        int code = info == null ? -1 : info.versionCode;
        int pct = Math.max(0, Math.min(100, percent));
        boolean installable = info != null && info.canInstall();

        switch (phase) {
            case NO_SERVER:
                return new State(phase, version, code, pct, reason,
                        R.string.settings_oneclick_no_server, NO_ARGS,
                        R.string.update_banner_action, NO_ARGS, false);
            case CHECKING:
                return new State(phase, version, code, pct, reason,
                        R.string.settings_oneclick_checking, NO_ARGS,
                        R.string.update_banner_action, NO_ARGS, false);
            case UP_TO_DATE:
                return new State(phase, version, code, pct, reason,
                        R.string.settings_oneclick_up_to_date, NO_ARGS,
                        R.string.settings_oneclick_up_to_date, NO_ARGS, false);
            case DOWNLOADING:
                return new State(phase, version, code, pct, reason,
                        R.string.settings_oneclick_downloading, new Object[]{pct},
                        R.string.settings_oneclick_downloading, new Object[]{pct}, false);
            case READY_TO_INSTALL:
                return new State(phase, version, code, pct, reason,
                        R.string.settings_oneclick_install, new Object[]{version},
                        R.string.settings_oneclick_install, new Object[]{version}, true);
            case FAILED:
                return new State(phase, version, code, pct, reason,
                        R.string.settings_oneclick_retry, NO_ARGS,
                        R.string.settings_oneclick_retry, NO_ARGS, true);
            case AVAILABLE:
                return new State(phase, version, code, pct, reason,
                        installable ? R.string.settings_oneclick_update_to : R.string.settings_oneclick_no_package,
                        new Object[]{version},
                        installable ? R.string.update_banner_action : R.string.update_banner_detail,
                        NO_ARGS, installable);
            case UNCHECKED:
            default:
                // 还没查过：一键按钮可点（点一下会先查）；横幅在没有结论时不该出现，文案沿用「下载更新」。
                return new State(phase, version, code, pct, reason,
                        R.string.settings_oneclick_unchecked, NO_ARGS,
                        R.string.update_banner_action, NO_ARGS, true);
        }
    }

    /**
     * 版本号的显示文本：优先用服务端给的 {@code version}，为空时退回 {@code versionCode}。
     * <b>绝不写死</b> —— 用户看到的「更新到 1.6.2」里的 1.6.2 必须来自
     * {@code /api/app/update} 的应答。
     */
    static String versionText(UpdateInfo info) {
        if (info == null) return "";
        if (!info.version.isEmpty()) return info.version;
        return info.versionCode > 0 ? "versionCode " + info.versionCode : "";
    }
}

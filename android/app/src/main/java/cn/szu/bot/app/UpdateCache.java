package cn.szu.bot.app;

import java.io.File;
import java.io.IOException;

/**
 * 「本地 cacheDir/update 里那份安装包还能不能直接用」的<b>唯一判据</b>。
 *
 * <h2>为什么要有这个类（用户报的 bug 的正面修法）</h2>
 * <p>用户原话：「点击下载更新后不会下载而是直接安装<b>老的</b>」。
 * 根因是设置页里那条<b>盲装短路</b>——只要 {@code UpdatePrefs} 记录的那个文件还在，
 * 点「下载更新」就直接进安装流程，<b>既没核 sha256，也没看版本号</b>；而 {@code cacheDir/update}
 * 里刻意残留着上一版下好的包（设计是「升级后才清、宁可不删」）。于是：
 * <pre>
 *   cacheDir/update/pixiko-update-1.6.1-161.verified.apk（上次下的 1.6.1）
 *   + 服务端现在 announced 1.6.2 → 点「下载更新」→ 装了 1.6.1（比本机还旧/一样）
 * </pre>
 *
 * <p>本类把所有判据收在一处，并且<b>刻意不碰 android.*</b>（只用 {@link File} 与 {@link Sha256}），
 * 所以「什么情况下才算已下载好」「什么情况下必须重新下载」「什么情况下删旧包」
 * 都能在 JVM 单测里逐条钉死（见 {@code app/src/test/…/UpdateCacheTest.java}）。
 *
 * <p>两条铁律（与 {@link UpdateInstaller} 里的安装闸门互为兜底，任一单独成立就够）：
 * <ol>
 *   <li><b>只有 sha256 确实等于服务端当前 announced 那份</b>，本地包才算「已下载好」；</li>
 *   <li><b>只有 versionCode 比本机大</b>，才谈得上安装（服务端报的不比本机大就什么都不做）。</li>
 * </ol>
 */
public final class UpdateCache {

    /** 点「下载更新」时该干什么。 */
    public enum Verdict {
        /** 老老实实下载 + 校验（本地那份要么没有，要么跟服务端当前版本对不上）。 */
        DOWNLOAD,
        /** 本地那份<b>确实就是</b>服务端当前 announced 的包（名字与 sha256 都核过）：可以直接交给安装器。 */
        INSTALL,
        /** 服务端报的版本不比本机新（或压根没有可安装的包）：既不下载也不安装。 */
        UP_TO_DATE
    }

    /** {@link #planFor} 的结论。 */
    public static final class Decision {
        public final Verdict verdict;
        /** {@code INSTALL} 时要交给安装器的文件；其它情况为 {@code null}。 */
        public final File apk;
        /** 本地那份实测出来的 sha256（没算过就是空串）。 */
        public final String sha256;
        /** 可以直接显示给用户的一句人话。 */
        public final String message;
        /** 这次判定顺手删掉了一个「与服务端当前版本对不上」的旧包。 */
        public final boolean deletedStale;

        Decision(Verdict verdict, File apk, String sha256, String message, boolean deletedStale) {
            this.verdict = verdict;
            this.apk = apk;
            this.sha256 = sha256 == null ? "" : sha256;
            this.message = message == null ? "" : message;
            this.deletedStale = deletedStale;
        }

        public boolean isInstall() { return verdict == Verdict.INSTALL; }

        public boolean isDownload() { return verdict == Verdict.DOWNLOAD; }

        public boolean isUpToDate() { return verdict == Verdict.UP_TO_DATE; }

        @Override public String toString() {
            return "Decision{" + verdict + ", apk=" + (apk == null ? "null" : apk.getName())
                    + ", deletedStale=" + deletedStale + ", message=" + message + "}";
        }
    }

    /** {@link #purgeStale} 的结果。 */
    public static final class Purge {
        public final int deletedFiles;
        public final long deletedBytes;
        public final int keptFiles;

        Purge(int deletedFiles, long deletedBytes, int keptFiles) {
            this.deletedFiles = deletedFiles;
            this.deletedBytes = deletedBytes;
            this.keptFiles = keptFiles;
        }

        @Override public String toString() {
            return "Purge{删除 " + deletedFiles + " 个/" + deletedBytes + " 字节，保留 " + keptFiles + " 个}";
        }
    }

    private UpdateCache() { }

    /**
     * <b>点「下载更新」时唯一的决策函数。</b>
     *
     * @param info                服务端<b>当前</b> announced 的更新信息（{@code null} ＝ 还不知道，按「别装」处理）
     * @param cached              本地记录的那份安装包（{@code null} ＝ 没有）
     * @param installedVersionCode 本机 versionCode（{@code -1} ＝ 读不到，按「不知道」处理：只要服务端报的 &gt; -1 就算新）
     * @return 该下载 / 该直接安装 / 什么都不做；对不上服务端当前版本的旧包<b>当场删掉</b>（{@code deletedStale=true}）
     */
    public static Decision planFor(UpdateInfo info, File cached, int installedVersionCode) {
        if (info == null) {
            return new Decision(Verdict.UP_TO_DATE, null, "", "还没有查到新版本，先点「检查更新」。", false);
        }
        if (!info.canInstall()) {
            return new Decision(Verdict.UP_TO_DATE, null, "", info.blockReason(), false);
        }
        if (!info.isNewerThan(installedVersionCode)) {
            // 铁律②：服务端报的 versionCode（严格大于才算新）不比本机大 → 绝不安装。
            return new Decision(Verdict.UP_TO_DATE, null, "",
                    "服务端报的 versionCode（" + info.versionCode + "）不比本机（" + installedVersionCode
                            + "）大，不提示也不安装。", false);
        }
        if (cached == null || !cached.isFile() || cached.length() <= 0L) {
            return new Decision(Verdict.DOWNLOAD, null, "", "本地还没有校验通过的安装包，开始下载。", false);
        }

        String actual;
        try {
            actual = Sha256.of(cached);
        } catch (IOException error) {
            boolean deleted = delete(cached);
            return new Decision(Verdict.DOWNLOAD, null, "",
                    "读不了本地缓存的安装包（" + Log.describe(error) + "），已删掉、重新下载。", deleted);
        }

        // 铁律①：文件名必须是「服务端当前这一版」的目标名（versionCode 就写在名字里），
        // 且内容 sha256 必须等于服务端当前 announced 的那个。两者缺一，这份包就不算「已下载好」。
        boolean nameMatches = cached.getName().equals(SelfUpdate.targetName(info) + SelfUpdate.VERIFIED_SUFFIX);
        if (!nameMatches || !Sha256.matches(actual, info.sha256)) {
            boolean deleted = delete(cached);
            return new Decision(Verdict.DOWNLOAD, null, actual,
                    "本地缓存的是另一个安装包（sha256 " + head(actual) + "… ≠ 服务端当前 "
                            + head(info.sha256) + "…，文件名" + (nameMatches ? "对得上" : "也不对")
                            + "），已删掉、重新下载。", deleted);
        }
        return new Decision(Verdict.INSTALL, cached, actual,
                "本地已有与服务端当前版本一致（sha256 核对通过）的安装包，直接安装。", false);
    }

    /**
     * <b>版本变了就清缓存。</b>把 {@code dir} 里凡是「不是服务端当前 announced 那个包」的
     * {@code *.verified.apk} / {@code *.part} 全删掉。
     *
     * <p>三条刻意的规则：
     * <ul>
     *   <li>名字就是当前版本、且 sha256 核得过的 {@code .verified.apk} —— <b>留</b>（用户可能只是想再点一次安装）；</li>
     *   <li>名字是当前版本的 {@code .part} —— <b>留</b>（断点续传靠它，删了就等于让用户重下几十 MB）；</li>
     *   <li>其它 {@code .verified.apk} / {@code .part}（上一版残留、服务端换了包、坏文件） —— <b>删</b>。
     *       这就是「点下载更新却把旧包装上」里那份旧包的归宿。</li>
     * </ul>
     * <p>{@code info} 为空 / 不可安装时<b>什么都不做</b>：这时我们并不知道服务端当前是哪个包，
     * 既不能证明本地那份对，也没有任何理由删它（宁可不删）。
     */
    public static Purge purgeStale(File dir, UpdateInfo info) {
        if (info == null || !info.canInstall()) return new Purge(0, 0, 0);
        if (dir == null || !dir.isDirectory()) return new Purge(0, 0, 0);
        File[] files = dir.listFiles();
        if (files == null) return new Purge(0, 0, 0);

        String keepName = SelfUpdate.targetName(info);
        int deleted = 0;
        int kept = 0;
        long bytes = 0L;
        for (File file : files) {
            String name = file.getName();
            boolean verified = name.endsWith(SelfUpdate.VERIFIED_SUFFIX);
            boolean part = name.endsWith(SelfUpdate.PART_SUFFIX);
            if (!verified && !part) continue;          // 不相干的文件一律不碰
            if (name.equals(keepName + SelfUpdate.PART_SUFFIX)) {
                kept++;                                 // 当前版本的半成品：留给断点续传
                continue;
            }
            if (verified && name.equals(keepName + SelfUpdate.VERIFIED_SUFFIX)
                    && hashMatches(file, info.sha256)) {
                kept++;                                 // 就是当前这一版且哈希核过：留着
                continue;
            }
            long length = file.length();
            if (file.delete()) {
                deleted++;
                bytes += length;
                Log.i("已删除与服务端当前版本不符的缓存安装包：" + name + "（" + length + " 字节）");
            }
        }
        return new Purge(deleted, bytes, kept);
    }

    /**
     * 这份文件是不是「服务端当前 announced 的那个包」：文件名对得上 <b>且</b> sha256 核过。
     *
     * <p>先比文件名（几乎零成本），名字不对就直接返回 false，<b>不去读几 MB 的文件算哈希</b>——
     * 这个方法会在「有新版本」的界面上被调用，不能因为一次 sha256 把主线程卡出 jank。
     */
    public static boolean isCachedFor(UpdateInfo info, File file) {
        return looksVerifiedFor(info, file) && hashMatches(file, info.sha256);
    }

    /**
     * <b>只比文件名</b>（不比哈希）的廉价版本，用来决定「按钮上该写『安装 X』还是『下载更新』」。
     *
     * <p>为什么仅凭文件名就够：{@link SelfUpdate} 只在 sha256 校验通过之后才把
     * {@code .part} 改名成 {@code <目标名>.verified.apk}，而目标名里带着版本号与 versionCode
     * （「存在的 .verified.apk ⇒ 哈希一定验过」是那个类自己声明并保持的不变量）。
     * 所以「名字 = 服务端当前这一版的目标名」就意味着「它当初是为这一版校验通过的」。
     *
     * <p><b>它不参与任何安全判断</b>：真到安装前，{@code SettingsActivity.promptInstall} 仍然会用
     * {@link #isCachedFor} + {@link UpdateInstaller#checkInstallable} 实测 sha256。万一这里乐观了，
     * 结果也只是「点下去变成真的下载一遍」，而不是「装上一个没核过的包」。
     */
    public static boolean looksVerifiedFor(UpdateInfo info, File file) {
        if (info == null || !info.canInstall()) return false;
        if (file == null || !file.isFile() || file.length() <= 0L) return false;
        return file.getName().equals(SelfUpdate.targetName(info) + SelfUpdate.VERIFIED_SUFFIX);
    }

    /** 算哈希并比对；读不动就是「不匹配」（宁可重下，也不冒险装）。 */
    private static boolean hashMatches(File file, String expectedSha256) {
        try {
            return Sha256.matches(Sha256.of(file), expectedSha256);
        } catch (IOException error) {
            Log.w("校验本地缓存安装包失败，按不匹配处理", error);
            return false;
        }
    }

    private static boolean delete(File file) {
        if (file == null || !file.exists()) return false;
        return file.delete();
    }

    /** 日志/文案里只放哈希前几位（整串太长）。 */
    private static String head(String hex) {
        String text = hex == null ? "" : hex;
        return text.length() <= 12 ? text : text.substring(0, 12);
    }
}

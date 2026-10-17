package cn.szu.bot.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「手机端优先用局域网 IP」的<b>排序核心</b>：把三个来源的地址收成一份统一的候选列表。
 *
 * <pre>
 *   已保存的地址（ServerRepository）
 * + 烧进 APK 的默认地址（BuildConfig.PIXIKO_DEFAULT_HOST）
 * + 扫描发现的地址（{@link LanScanner}）
 * </pre>
 *
 * <p>排序规则只有两条，但顺序不能反：
 * <ol>
 *   <li><b>局域网私网地址整档在前</b>（{@code 10/8}、{@code 172.16/12}、{@code 192.168/16}），
 *       公网地址与域名整档在后。{@code 169.254/16}（链路本地）与 {@code 127/8} 视为<b>不可用</b>，
 *       不算私网（判据见 {@link LanScanner#isPrivateV4(String)}，与扫描用的是同一个 —— 两处判据
 *       一旦分家，就会出现"扫到了却排在域名后面"这种自相矛盾）；</li>
 *   <li>同一档内按来源排：<b>已保存 &gt; 默认 &gt; 扫描</b>。用户存过的东西永远优先于构建时烧进去的
 *       测试地址，后者又优先于扫出来的（扫出来的只是"现在能连"，不一定是用户想用的那台）。</li>
 * </ol>
 *
 * <p>去重按<b>规范化之后的基地址</b>（{@link UrlHelper#normalizeBase(String)}）：同一个地址同时出现在
 * 多个来源里时只留排在前面那一条 —— 于是"已保存的 192.168.1.5 又被扫出来一次"不会在列表里出现两遍，
 * 而且会带着「已保存」的身份排在默认/扫描之前。
 *
 * <p>纯 Java、不碰 {@code android.*}，所以排序这件事在 JVM 单测里是真断言，不是源码级猜测。
 */
public final class ServerCandidates {

    /** 候选地址是从哪来的（决定同一档内的先后）。 */
    public enum Source {
        /** 用户保存过的服务器。 */
        SAVED,
        /** 构建时烧进 APK 的默认地址（{@code BuildConfig.PIXIKO_DEFAULT_HOST}）。 */
        DEFAULT,
        /** 局域网扫描发现的。 */
        SCANNED
    }

    /** 一个候选地址。 */
    public static final class Candidate {
        /** 规范化后的基地址，形如 {@code http://192.168.1.5:8787}。 */
        public final String base;
        /** 主机（不带端口），形如 {@code 192.168.1.5}。 */
        public final String host;
        /** 来源。 */
        public final Source source;

        Candidate(String base, String host, Source source) {
            this.base = base;
            this.host = host;
            this.source = source;
        }

        /** 是不是局域网私网地址（决定排在哪一档）。 */
        public boolean isLan() {
            return LanScanner.isPrivateV4(host);
        }

        @Override public String toString() {
            return base + "（" + source + (isLan() ? "，局域网" : "，非局域网") + "）";
        }
    }

    private ServerCandidates() { }

    /**
     * 三个来源 → 一份排序好的候选列表（可重复调用，纯函数）。
     *
     * @param saved        已保存的基地址（按用户添加顺序；{@code null} 当空处理）
     * @param buildDefault 烧进 APK 的默认地址（可为 {@code null}/空串）
     * @param scanned      扫描发现的基地址（{@code null} 当空处理）
     */
    public static List<Candidate> rank(List<String> saved, String buildDefault, List<String> scanned) {
        List<Candidate> all = new ArrayList<>();
        collect(all, saved, Source.SAVED);
        collect(all, java.util.Collections.singletonList(buildDefault), Source.DEFAULT);
        collect(all, scanned, Source.SCANNED);

        List<Candidate> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int tier = 0; tier < 2; tier++) {
            boolean wantLan = tier == 0;
            for (Source source : Source.values()) {          // 枚举顺序就是 SAVED / DEFAULT / SCANNED
                for (Candidate candidate : all) {
                    if (candidate.source != source) continue;
                    if (candidate.isLan() != wantLan) continue;
                    if (!seen.add(candidate.base)) continue; // 同一地址只留排前面那一条
                    ordered.add(candidate);
                }
            }
        }
        return ordered;
    }

    /** 只要地址（UI/日志用，保持 {@link #rank} 的顺序）。 */
    public static List<String> bases(List<Candidate> candidates) {
        List<String> out = new ArrayList<>();
        if (candidates == null) return out;
        for (Candidate candidate : candidates) if (candidate != null) out.add(candidate.base);
        return out;
    }

    /** 这份列表里第一个局域网地址；一个都没有返回 {@code null}。 */
    public static String firstLanBase(List<Candidate> candidates) {
        if (candidates == null) return null;
        for (Candidate candidate : candidates) if (candidate != null && candidate.isLan()) return candidate.base;
        return null;
    }

    private static void collect(List<Candidate> into, List<String> raw, Source source) {
        if (raw == null) return;
        for (String item : raw) {
            String base = UrlHelper.normalizeBase(item);
            if (base == null) continue;                       // 空/坏地址不算候选
            String host = UrlHelper.hostOnly(base);
            if (host == null || host.isBlank()) continue;
            into.add(new Candidate(base, host, source));
        }
    }
}

package cn.szu.bot.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * <b>候选排序</b>：用户要的是「手机端优先检索局域网 IP」，所以候选列表必须先局域网、后公网/域名，
 * 同一档内「已保存 &gt; 默认（烧进 APK 的地址）&gt; 扫描到」。
 *
 * <p>这里只用 {@link ServerCandidates}（纯 Java，不碰 {@code android.*}），所以断言的是<b>真行为</b>，
 * 不是"源码里看起来有这几行"。
 */
public class ServerCandidatesTest {

    private static List<String> bases(List<ServerCandidates.Candidate> ranked) {
        List<String> out = new ArrayList<>();
        for (ServerCandidates.Candidate candidate : ranked) out.add(candidate.base);
        return out;
    }

    private static int indexOf(List<ServerCandidates.Candidate> ranked, String base) {
        List<String> bases = bases(ranked);
        int at = bases.indexOf(base);
        assertTrue("候选里没有这个地址：" + base + "（实际 " + bases + "）", at >= 0);
        return at;
    }

    @Test
    public void lanAddressesComeFirstAndSameTierKeepsSavedThenDefaultThenScanned() {
        // ---- 场景一：任务里点名的那一组 ----
        // 已保存 = 公网域名；默认 = 192.168.1.5:8787；扫描到 = 192.168.1.9:8787
        List<ServerCandidates.Candidate> ranked = ServerCandidates.rank(
                Arrays.asList("example.com"),
                "192.168.1.5:8787",
                Arrays.asList("192.168.1.9:8787"));
        System.out.println("[实测] 排序结果（saved=example.com, default=192.168.1.5, scanned=192.168.1.9）："
                + bases(ranked));

        int lanDefault = indexOf(ranked, "http://192.168.1.5:8787");
        int lanScanned = indexOf(ranked, "http://192.168.1.9:8787");
        int publicDomain = indexOf(ranked, "http://example.com:8787");
        assertTrue("私网地址（默认）必须排在公网域名之前：" + bases(ranked), lanDefault < publicDomain);
        assertTrue("私网地址（扫描到）必须排在公网域名之前：" + bases(ranked), lanScanned < publicDomain);
        assertTrue("同一档内「默认」要排在「扫描」之前：" + bases(ranked), lanDefault < lanScanned);

        // ---- 场景二：已保存的也是局域网 → 三档全在局域网档里，顺序 已保存 > 默认 > 扫描 ----
        List<ServerCandidates.Candidate> mixed = ServerCandidates.rank(
                Arrays.asList("192.168.1.7:8787", "bot.example.com"),
                "192.168.1.5:8787",
                Arrays.asList("192.168.1.9:8787"));
        System.out.println("[实测] 排序结果（已保存含一个私网 + 一个域名）：" + bases(mixed));
        assertEquals("局域网三个在前（已保存 .7 → 默认 .5 → 扫描 .9），公网域名殿后",
                Arrays.asList(
                        "http://192.168.1.7:8787",
                        "http://192.168.1.5:8787",
                        "http://192.168.1.9:8787",
                        "http://bot.example.com:8787"),
                bases(mixed));

        // ---- 去重：同一地址同时是"已保存"和"扫描到"时只留一条，且带「已保存」的身份 ----
        List<ServerCandidates.Candidate> deduped = ServerCandidates.rank(
                Arrays.asList("192.168.1.5:8787"), "192.168.1.5:8787", Arrays.asList("192.168.1.5:8787"));
        assertEquals("同一个地址只该出现一次：" + bases(deduped), 1, deduped.size());
        assertEquals(ServerCandidates.Source.SAVED, deduped.get(0).source);

        // ---- 169.254/16（链路本地，视为不可用）不算私网，不能插到局域网档里 ----
        List<ServerCandidates.Candidate> linkLocal = ServerCandidates.rank(
                Arrays.asList("169.254.10.2:8787"), null, Arrays.asList("192.168.1.9:8787"));
        System.out.println("[实测] 169.254 必须排在真局域网之后：" + bases(linkLocal));
        assertEquals("192.168.1.9:8787 要排在 169.254 之前（前者才是能用的局域网）："
                        + bases(linkLocal), "http://192.168.1.9:8787", linkLocal.get(0).base);
        ServerCandidates.Candidate linkLocalCandidate = linkLocal.get(linkLocal.size() - 1);
        assertEquals("http://169.254.10.2:8787", linkLocalCandidate.base);
        assertFalse("169.254/16 不算私网", linkLocalCandidate.isLan());

        // ---- 空/坏输入不崩、不产生幽灵候选 ----
        assertTrue(ServerCandidates.rank(null, null, null).isEmpty());
        assertTrue(ServerCandidates.rank(Arrays.asList("", "   "), "", Arrays.asList((String) null)).isEmpty());

        // 判据与扫描侧共用一个（LanScanner.isPrivateV4）：两处一旦分家就会出现"扫到了却排在域名后面"。
        assertTrue(ServerCandidates.rank(Arrays.asList("10.0.5.9:8787"), null, null).get(0).isLan());
        assertTrue(ServerCandidates.rank(Arrays.asList("172.20.1.2:8787"), null, null).get(0).isLan());
        assertFalse(ServerCandidates.rank(Arrays.asList("172.32.1.2:8787"), null, null).get(0).isLan());
        assertFalse(ServerCandidates.rank(Arrays.asList("127.0.0.1:8787"), null, null).get(0).isLan());
    }
}

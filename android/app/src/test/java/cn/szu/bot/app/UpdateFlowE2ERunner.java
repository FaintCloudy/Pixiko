package cn.szu.bot.app;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 交付用的<b>端到端假服务验证</b>驱动程序（不是 JUnit 用例，而是一个可以直接跑、把过程打印成
 * 「交互日志」的 main）。它复用生产的 {@link UpdateChecker} / {@link UpdateInfo} / {@link SelfUpdate}
 * 三个类，配套 {@link FakeHttpServer} 当假服务端，把任务书要求验证的四件事走一遍并逐条打印结论：
 *
 * <pre>
 *   ①手动检查更新拿到「有新版本」（dev 与 release 两种 channel 各跑一遍）
 *   ②下载进度推进（打印每次回调的百分数）
 *   ③sha256 不符时拒绝安装（故意给错哈希）
 *   ④sha256 正确时走到「可以安装」的最终状态（Intent 的 action/type/flags 由
 *     ToolchainProbeTest / ManifestAndResourcesCheckTest 断言，见报告）
 * </pre>
 *
 * <p>为什么单独写一个 main 而不是只靠 JUnit：报告要的是<b>一段能贴给人看的交互日志</b>
 * （假服务收到了什么请求、进度到多少、失败时说了什么），JUnit 的报告以断言为主，
 * 过程证据不好呈现。断言仍然在 JUnit 里（那才是可重复的回归）。
 */
public final class UpdateFlowE2ERunner {

    /**
     * 把这次运行的完整输出<b>同时</b>写一份到 UTF-8 文件里。
     *
     * <p>为什么需要：Windows PowerShell 5.1 的管道会按 ANSI 重新编码 Java 的 stdout，
     * 中文在控制台/重定向文件里会变成乱码（实测）。报告要贴的是「干净可读的交互日志」，
     * 所以由 Java 自己再落一份 UTF-8。
     */
    private static java.io.PrintStream teeLog;

    private static void startTee(String[] args) throws Exception {
        File target = args != null && args.length > 0
                ? new File(args[0])
                : defaultLogFile();
        if (target.getParentFile() != null) target.getParentFile().mkdirs();
        final java.io.PrintStream fileOut = new java.io.PrintStream(
                new java.io.FileOutputStream(target), false, "UTF-8");
        // 必须**先**把原来的 stdout 抓在手里：下面的 System.setOut 会把它换掉，
        // 若在 tee 里再去读 System.out，就成了「写到 System.out → 又回到 tee」的无限递归
        // （实测直接 StackOverflowError 刷满 stderr）。
        final java.io.PrintStream console = System.out;
        teeLog = new java.io.PrintStream(new java.io.OutputStream() {
            @Override public void write(int b) {
                console.write(b);
                fileOut.write(b);
            }

            @Override public void write(byte[] bytes, int off, int len) {
                console.write(bytes, off, len);
                fileOut.write(bytes, off, len);
            }

            @Override public void flush() {
                console.flush();
                fileOut.flush();
            }
        }, true, "UTF-8");
        System.setOut(teeLog);
        System.out.println("# 完整日志（UTF-8）落盘位置：" + target.getAbsolutePath());
    }

    private static File defaultLogFile() {
        File here = new File("").getAbsoluteFile();
        for (File candidate = here; candidate != null; candidate = candidate.getParentFile()) {
            if (new File(candidate, "app/src/main/AndroidManifest.xml").isFile()) {
                return new File(candidate, "dist/_logs/e2e-fake-server.log");
            }
        }
        return new File("e2e-fake-server.log");
    }

    /** 让 Log 的出口打到 stdout（否则 android.util.Log 在 JVM 上是 stub，一调就抛）。 */
    private static void installStdoutLogSink() {
        Log.setSink((priority, tag, message) -> System.out.println("    LOG  " + tag + "  " + message));
    }

    public static void main(String[] args) throws Exception {
        installStdoutLogSink();
        startTee(args);
        File workDir = new File(System.getProperty("java.io.tmpdir"), "pixiko-update-e2e-" + System.nanoTime());
        if (!workDir.mkdirs()) throw new IllegalStateException("建不了临时目录 " + workDir);

        System.out.println("================ Pixiko 安卓端「检查更新 + 自我更新」端到端验证 ================");
        System.out.println("工作目录（模拟 app 的 cacheDir/update）： " + workDir.getAbsolutePath());
        System.out.println("本机 versionName/versionCode（模拟）： 1.6.0 / 160");
        System.out.println();

        boolean devOk = runChannel("dev", "1.7.0", 170, workDir);
        System.out.println();
        boolean releaseOk = runChannel("release", "2.0.0", 200, workDir);
        System.out.println();
        boolean mismatchOk = runSha256Mismatch(workDir);
        System.out.println();
        boolean noPackageOk = runNoPackage();
        System.out.println();
        boolean upToDateOk = runUpToDate(workDir);

        System.out.println("================ 结论 ================");
        System.out.println("① channel=dev    手动检查 + 下载 + 校验 + 可安装 : " + (devOk ? "通过" : "失败"));
        System.out.println("① channel=release 手动检查 + 下载 + 校验 + 可安装 : " + (releaseOk ? "通过" : "失败"));
        System.out.println("② 下载进度推进                                  : " + (devOk ? "通过（见上面每次进度回调）" : "失败"));
        System.out.println("③ sha256 不符 → 拒绝安装并删除坏文件             : " + (mismatchOk ? "通过" : "失败"));
        System.out.println("④ apkUrl 为空 → 只提示、不给下载                 : " + (noPackageOk ? "通过" : "失败"));
        System.out.println("⑤ versionCode 不涨 → 不提示更新                  : " + (upToDateOk ? "通过" : "失败"));
        System.out.println("======================================================================");

        boolean allOk = devOk && releaseOk && mismatchOk && noPackageOk && upToDateOk;
        deleteRecursively(workDir);
        System.out.println(allOk ? "全部场景通过（exit 0）" : "有场景未通过（exit 1）");
        System.out.flush();
        if (teeLog != null) teeLog.flush();
        System.exit(allOk ? 0 : 1);
    }

    /** 一个完整渠道的走查：POST /api/app/update → 解析 → 下载（带进度）→ sha256 校验。 */
    private static boolean runChannel(String channel, String version, int versionCode, File workDir)
            throws Exception {
        System.out.println("---------------- 场景：" + channel + " 渠道 " + version + " (" + versionCode + ") ----------------");

        // 假 APK：故意不是最小文件（128KB），这样进度回调会报好几次。
        byte[] apk = Sha256Test.fakeApkBytes(131_072);
        String sha = FakeHttpServer.sha256(apk);
        String channelNote = "dev".equals(channel)
                ? "本机测试包（含局域网地址 172.30.204.50:8787），仅供内网测试"
                : "";

        try (FakeHttpServer apkServer = new FakeHttpServer();
             FakeHttpServer apiServer = new FakeHttpServer()) {
            apkServer.serveApkContract(apk, "/api/app/apk");
            String apkUrl = apkServer.baseUrl() + "/api/app/apk";

            String json = "{\"version\":\"" + version + "\",\"versionCode\":" + versionCode
                    + ",\"sizeBytes\":" + apk.length
                    + ",\"sha256\":\"" + sha + "\""
                    + ",\"apkUrl\":\"" + apkUrl + "\""
                    + ",\"releaseUrl\":\"https://github.com/loriko/pixiko/releases\""
                    + ",\"publishedAt\":\"2026-10-07T12:00:00Z\""
                    + ",\"notes\":\"修了若干问题；新增自动更新。\""
                    + ",\"channel\":\"" + channel + "\""
                    + (channelNote.isEmpty() ? "" : ",\"channelNote\":\"" + channelNote + "\"")
                    + "}";
            apiServer.serve("/api/app/update", (request, response) -> {
                response.status(200);
                response.setBody(json.getBytes(StandardCharsets.UTF_8));
                response.set("Content-Type", "application/json");
            });

            System.out.println("  假服务端(update) = " + apiServer.baseUrl() + "   假服务端(apk) = " + apkUrl);
            System.out.println("  假 APK: " + apk.length + " 字节, sha256=" + sha);

            // ---- ① 检查更新 ----
            UpdateChecker.Outcome outcome = UpdateChecker.check(apiServer.baseUrl(), "", null);
            if (!outcome.ok || outcome.info == null) {
                System.out.println("  ✗ 检查更新失败：" + outcome.message);
                return false;
            }
            for (FakeHttpServer.Request request : apiServer.requests()) {
                System.out.println("  → 假服务端收到：" + request);
            }
            UpdateInfo info = outcome.info;
            boolean newer = info.isNewerThan(160);
            System.out.println("  ✓ 解析结果：version=" + info.version + " versionCode=" + info.versionCode
                    + " size=" + info.sizeText() + " channel=" + info.channel);
            System.out.println("  ✓ 版本判定 isNewerThan(160)=" + newer
                    + "（严格比 versionCode，不比 version 字符串）");
            System.out.println("  ✓ 界面会显示这一行渠道说明：「" + info.channelText() + "」");
            System.out.println("  ✓ 更新说明：" + info.notes);
            if (!newer || !info.canInstall()) {
                System.out.println("  ✗ 期望「有新版本且可下载」");
                return false;
            }

            // ---- ② 下载 + 进度 ----
            final List<Integer> percents = Collections.synchronizedList(new ArrayList<>());
            final List<String> progressLines = Collections.synchronizedList(new ArrayList<>());
            SelfUpdate.Result result = SelfUpdate.download(info, workDir, (downloaded, total) -> {
                int percent = total > 0 ? (int) Math.min(100L, downloaded * 100L / total) : -1;
                String line = "     进度 " + (percent < 0 ? "?" : percent + "%")
                        + "  (" + downloaded + " / " + total + " 字节)";
                progressLines.add(line);
                System.out.println(line);
                percents.add(percent);
            });

            // ---- ③④ 校验结论 ----
            System.out.println("  ✓ 下载结果 ok=" + result.ok + "  消息=" + result.message);
            if (!result.ok || result.apk == null) return false;
            String actualSha = Sha256.of(result.apk);
            System.out.println("  ✓ 校验：算出来 " + actualSha);
            System.out.println("  ✓ 校验：服务端 " + sha + "  → matches=" + Sha256.matches(actualSha, sha));
            System.out.println("  ✓ 产物：" + result.apk.getName() + "（" + result.apk.length() + " 字节，sha256 已核过）");
            System.out.println("  ✓ 下一步（真实设备上）会调用："
                    + "Intent(ACTION_VIEW).setDataAndType(content://cn.szu.bot.app.fileprovider/update/…, "
                    + SelfUpdate.APK_MIME + ") + FLAG_GRANT_READ_URI_PERMISSION + FLAG_ACTIVITY_NEW_TASK");
            System.out.println("      最后一下「安装」必须由用户在系统弹窗里点 —— 普通 app 无法静默自我替换。");
            System.out.println("  ✓ 假服务端(apk)收到 " + apkServer.requests().size() + " 次请求：" + apkServer.requests());

            if (percents.size() < 2) {
                System.out.println("  ✗ 期望至少 2 次进度回调，实际 " + percents.size());
                return false;
            }
            for (int i = 1; i < percents.size(); i++) {
                if (percents.get(i) < percents.get(i - 1)) {
                    System.out.println("  ✗ 进度倒退了：" + percents);
                    return false;
                }
            }
            if (!Sha256.matches(actualSha, sha)) {
                System.out.println("  ✗ 哈希不一致");
                return false;
            }
            return true;
        }
    }

    /** ③ 故意给错哈希：必须拒绝、报错、删掉坏文件。 */
    private static boolean runSha256Mismatch(File workDir) throws Exception {
        System.out.println("---------------- 场景：sha256 不符（服务端哈希与实际内容不一致） ----------------");
        File dir = new File(workDir, "mismatch");
        dir.mkdirs();

        byte[] served = Sha256Test.fakeApkBytes(65_536);
        String lie = FakeHttpServer.sha256(Sha256Test.fakeApkBytes(65_537));   // 长度都不同的假哈希

        try (FakeHttpServer apkServer = new FakeHttpServer()) {
            apkServer.serveApkContract(served, "/api/app/apk");
            String apkUrl = apkServer.baseUrl() + "/api/app/apk";
            UpdateInfo info = UpdateInfo.parse("{\"version\":\"1.7.0\",\"versionCode\":170,"
                    + "\"sizeBytes\":" + served.length + ",\"sha256\":\"" + lie + "\","
                    + "\"apkUrl\":\"" + apkUrl + "\"}");
            System.out.println("  声明 sha256 = " + lie);
            System.out.println("  实际内容 sha256 = " + FakeHttpServer.sha256(served));

            SelfUpdate.Result result = SelfUpdate.download(info, dir, null);
            System.out.println("  → 下载结果 ok=" + result.ok + "（期望 false）");
            System.out.println("  → 报错文案：" + result.message);
            System.out.println("  → 是否给出了可安装文件：" + (result.apk != null) + "（期望 false）");
            System.out.println("  → 目录残留：" + java.util.Arrays.toString(dir.listFiles()) + "（期望空）");
            System.out.println("  假服务端请求次数 = " + apkServer.requests().size() + "（重试 " + SelfUpdate.MAX_ATTEMPTS + " 次）");

            boolean ok = !result.ok
                    && result.apk == null
                    && result.message.contains("sha256")
                    && (dir.listFiles() == null || dir.listFiles().length == 0);
            System.out.println(ok ? "  ✓ 拒绝安装并已清理坏文件" : "  ✗ 期望：拒绝安装 + 清理坏文件");
            return ok;
        }
    }

    /** ④ 契约里的「没有可用发布包」：apkUrl 为空串 → 只提示有新版本，不给下载。 */
    private static boolean runNoPackage() throws Exception {
        System.out.println("---------------- 场景：apkUrl 为空串（服务端没有可用发布包） ----------------");
        try (FakeHttpServer apiServer = new FakeHttpServer()) {
            apiServer.serve("/api/app/update", (request, response) -> {
                response.status(200);
                response.setBody(("{\"version\":\"1.7.0\",\"versionCode\":170,\"apkUrl\":\"\","
                        + "\"sha256\":\"\",\"notes\":\"还在构建中\"}").getBytes(StandardCharsets.UTF_8));
            });
            UpdateChecker.Outcome outcome = UpdateChecker.check(apiServer.baseUrl(), "", null);
            if (!outcome.ok || outcome.info == null) {
                System.out.println("  ✗ 检查更新失败：" + outcome.message);
                return false;
            }
            UpdateInfo info = outcome.info;
            System.out.println("  → 有新版本：" + info.isNewerThan(160));
            System.out.println("  → canInstall=" + info.canInstall() + "（期望 false，界面不给「下载更新」按钮）");
            System.out.println("  → 界面提示：" + info.blockReason());
            boolean ok = info.isNewerThan(160) && !info.canInstall() && info.blockReason().contains("没有提供 APK 下载地址");
            System.out.println(ok ? "  ✓ 只提示、不给下载" : "  ✗ 期望只提示、不给下载");
            return ok;
        }
    }

    /** ⑥ versionCode 没涨（本机已是最新）→ 不该提示更新。 */
    private static boolean runUpToDate(File workDir) throws Exception {
        System.out.println("---------------- 场景：服务端 versionCode == 本机（不该提示更新） ----------------");
        try (FakeHttpServer apiServer = new FakeHttpServer()) {
            apiServer.serve("/api/app/update", (request, response) -> {
                response.status(200);
                response.setBody(("{\"version\":\"1.6.0\",\"versionCode\":160,"
                        + "\"apkUrl\":\"http://127.0.0.1:1/api/app/apk\",\"sha256\":\""
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}")
                        .getBytes(StandardCharsets.UTF_8));
            });
            UpdateChecker.Outcome outcome = UpdateChecker.check(apiServer.baseUrl(), "", null);
            if (!outcome.ok || outcome.info == null) {
                System.out.println("  ✗ 检查更新失败：" + outcome.message);
                return false;
            }
            boolean newer = outcome.info.isNewerThan(160);
            System.out.println("  → 服务端 version=" + outcome.info.version + " versionCode=" + outcome.info.versionCode);
            System.out.println("  → isNewerThan(160)=" + newer + "（期望 false：界面会显示「已经是最新版本」，不弹横幅）");
            System.out.println(newer ? "  ✗ 不该提示更新" : "  ✓ 正确判定为已是最新");
            return !newer;
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        // 顺手确认产物文件读得动（避免留下半截文件还以为验证过了）。
        if (file.isFile()) {
            try (FileInputStream ignored = new FileInputStream(file)) {
                // 只是打开一下。
            } catch (Exception error) {
                System.out.println("（清理时读文件失败：" + Log.describe(error) + "）");
            }
        }
        if (!file.delete()) System.out.println("（删不掉：" + file + "）");
    }

    private UpdateFlowE2ERunner() { }
}

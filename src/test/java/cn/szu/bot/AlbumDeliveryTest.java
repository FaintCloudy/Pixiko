package cn.szu.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cn.szu.bot.sd.ImageOutbox;
import cn.szu.bot.sd.SdClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次拿到多张图时合成一条「合并转发」（每个节点一张图），单张仍走普通发送。
 *
 * <p>只用一个记录型 Sender 观察 Bot 的真实出站调用：send / sendMap / sendRecord 的次数、节点数与节点顺序，
 * 并用「不完成的 future」精确制造"传输层确认之前不得 acknowledge"的场景。
 * 所有状态都写在临时根目录里，不碰真实 data，也不需要 QQ 或 SD。
 */
public final class AlbumDeliveryTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        threeImagesBecomeOneRecord();
        recordSendLogsForwardCount();
        singleImageStaysPlain();
        singleSendLogsPlainDelivery();
        batchingFollowsImageCount();
        failedRecordFallsBackAndStillAcknowledges();
        failedRecordAndFallbackBothFail();
        mutedGroupSendsNothing();
        mapBatchUsesOneRecordAndSingleMapStaysPlain();
        mapDeliveryLogsAndFallback();
        oversizedMapStillReportsFailureAndReleasesSlot();
        transportWithoutForwardSupportKeepsOldDelivery();
        System.out.println("AlbumDeliveryTest: " + assertions + " assertions passed：多图合并转发（含发送方式日志）、"
                + "单张普通发送、分批与 ACK 时机、合并转发失败回退普通发送并照常 ACK、回退也失败才不 ACK、禁言不发、"
                + "地图多图/单张/回退、读图失败如实报错并释放名额、无合并转发时保持旧发法。");
    }

    /** 一批 3 张 → 恰好一次 sendRecord、3 个节点、顺序一致、零次 sendMap；ACK 发生在确认之后。 */
    private static void threeImagesBecomeOneRecord() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Path> paths = f.seed(3, "seed");
            f.bot.accept(f.groupEvent(".get"));
            Dispatch dispatch = f.recorder.awaitImage(5000);
            equal("sendRecord", dispatch.kind(), "3 张图片走一条合并转发");
            equal(3, dispatch.nodes().size(), "合并转发里有 3 个节点");
            check(dispatch.singleImageNodes(), "每个节点只含一张图");
            equal(files(paths), dispatch.files(), "节点顺序与文件顺序一致");
            equal(0, f.recorder.mapped.get(), "合并转发不调用 sendMap");
            equal(1, f.recorder.records.get(), "一批只发一次合并转发");
            equal(0, f.recorder.plains.get(), "整批不再逐张普通发送");
            equal(3, f.client.pendingImages().size(), "传输层确认之前不得 acknowledge");
            dispatch.confirm();
            check(f.recorder.awaitText(5000).contains("本次领取完成，共 3 张"), "回执文案不变");
            f.awaitPending(0);
            equal(0, f.pending(), "确认之后才 acknowledge");
            f.recorder.assertNoImage(300);
        }
    }

    /** 合并转发成功：恰好一次 sendRecord、N 个节点、零次 send，并且打出一行"合并转发 N 张"。 */
    private static void recordSendLogsForwardCount() throws Exception {
        try (Fixture f = new Fixture()) {
            f.seed(3, "seed");
            String console = f.captureConsole(() -> {
                f.bot.accept(f.groupEvent(".get"));
                Dispatch dispatch = f.recorder.awaitImage(5000);
                equal("sendRecord", dispatch.kind(), "多图仍然只发一条合并转发");
                equal(3, dispatch.nodes().size(), "节点数等于图片数");
                check(dispatch.singleImageNodes(), "每个节点只含一张图");
                equal(1, f.recorder.records.get(), "恰好一次 sendRecord");
                equal(0, f.recorder.plains.get(), "合并转发成功时不调用普通发送");
                dispatch.confirm();
                check(f.recorder.awaitText(5000).contains("本次领取完成，共 3 张"), "回执不变");
                f.awaitPending(0);
            });
            check(console.contains("合并转发 3 张"), "控制台要有发送方式日志「合并转发 3 张」：" + tail(console));
        }
    }

    /** 单张 → 恰好一次 send、零次 sendRecord / sendMap，并且打出一行"单张普通发送"。 */
    private static void singleSendLogsPlainDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            f.seed(1, "seed");
            String console = f.captureConsole(() -> {
                f.bot.accept(f.groupEvent(".get"));
                Dispatch dispatch = f.recorder.awaitImage(5000);
                equal("send", dispatch.kind(), "单张仍然普通发送");
                equal(1, f.recorder.plains.get(), "单张只调用一次 send");
                equal(0, f.recorder.records.get(), "单张不套合并转发");
                dispatch.confirm();
                check(f.recorder.awaitText(5000).contains("本次领取完成，共 1 张"), "回执不变");
                f.awaitPending(0);
            });
            check(console.contains("单张普通发送"), "控制台要有发送方式日志「单张普通发送」：" + tail(console));
        }
    }

    /** 单张 → 恰好一次 send、零次 sendRecord / sendMap。 */
    private static void singleImageStaysPlain() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Path> paths = f.seed(1, "seed");
            f.bot.accept(f.groupEvent(".get"));
            Dispatch dispatch = f.recorder.awaitImage(5000);
            equal("send", dispatch.kind(), "单张图片仍然普通发送");
            equal(1, dispatch.nodes().size(), "单张只有一个消息段");
            check(dispatch.singleImageNodes(), "单张节点只含一张图");
            equal(files(paths), dispatch.files(), "发的就是那张图");
            equal(0, f.recorder.records.get(), "单张绝不套合并转发");
            equal(0, f.recorder.mapped.get(), "生成图片不用 sendMap");
            dispatch.confirm();
            check(f.recorder.awaitText(5000).contains("本次领取完成，共 1 张"), "单张回执不变");
            f.awaitPending(0);
            equal(0, f.pending(), "单张确认后清空");
        }
    }

    /** /imgcnt 2 分批：5 张 → 2 张合并转发 / 2 张合并转发 / 1 张普通；ACK 只标记真正发出去的。 */
    private static void batchingFollowsImageCount() throws Exception {
        try (Fixture f = new Fixture()) {
            check(f.command(".imgcnt 2").contains("2 张"), "每批上限设为 2 张");
            equal(2, new Settings(f.root).imageCount(), "上限已保存");
            List<Path> paths = f.seed(5, "seed");
            f.bot.accept(f.groupEvent(".get"));
            Dispatch first = f.recorder.awaitImage(5000);
            equal("sendRecord", first.kind(), "第一批 2 张：一条合并转发");
            equal(files(paths.subList(0, 2)), first.files(), "第一批顺序正确");
            equal(5, f.client.pendingImages().size(), "第一批确认前一张都没标记");
            first.confirm();
            Dispatch second = f.recorder.awaitImage(5000);
            equal("sendRecord", second.kind(), "第二批 2 张：一条合并转发");
            equal(files(paths.subList(2, 4)), second.files(), "第二批顺序正确");
            equal(3, f.client.pendingImages().size(), "只标记真正确认的那一批");
            second.confirm();
            Dispatch third = f.recorder.awaitImage(5000);
            equal("send", third.kind(), "最后 1 张：普通发送");
            equal(files(paths.subList(4, 5)), third.files(), "第三批就是最后一张");
            equal(1, f.client.pendingImages().size(), "第三批确认前只剩它自己");
            third.confirm();
            check(f.recorder.awaitText(5000).contains("本次领取完成，共 5 张"), "回执文案不变");
            f.awaitPending(0);
            equal(0, f.pending(), "三批全部确认后才清空");
            equal(2, f.recorder.records.get(), "三次发送里两次合并转发");
            equal(1, f.recorder.plains.get(), "一次普通发送");
            equal(0, f.recorder.mapped.get(), "分批不改变发送通道");
            f.recorder.assertNoImage(300);
        }
    }

    /**
     * 合并转发失败 → 回退普通发送。回退成功时调用方照常 acknowledge（这是本次的行为变更：
     * 旧断言是"sendRecord 失败 → 不 ack"，现在只要图真的发出去了就算领取完成）。
     */
    private static void failedRecordFallsBackAndStillAcknowledges() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Path> paths = f.seed(3, "seed");
            f.recorder.failRecords = true;
            f.bot.accept(f.groupEvent(".get"));
            Dispatch attempt = f.recorder.awaitImage(5000);
            equal("sendRecord", attempt.kind(), "多图仍然先尝试一次合并转发");
            equal(3, attempt.nodes().size(), "节点数不变");
            Dispatch fallback = f.recorder.awaitImage(5000);
            equal("send", fallback.kind(), "合并转发失败后回退普通发送");
            equal(3, fallback.segments(), "回退把整批发成一条普通消息");
            equal(files(paths), fallback.files(), "回退按原顺序发整批");
            equal(1, f.recorder.records.get(), "只尝试一次合并转发");
            equal(1, f.recorder.plains.get(), "只回退一次普通发送");
            equal(3, f.client.pendingImages().size(), "回退确认之前仍然不得 acknowledge");
            fallback.confirm();
            check(f.recorder.awaitText(5000).contains("本次领取完成，共 3 张"), "回退成功照样算领取完成");
            f.awaitPending(0);
            equal(0, f.pending(), "回退成功 → 照常 acknowledge");
            f.recorder.assertNoImage(300);
        }
    }

    /** 合并转发与回退的普通发送都失败 → 不 acknowledge，回执里带上失败原因。 */
    private static void failedRecordAndFallbackBothFail() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Path> paths = f.seed(3, "seed");
            f.recorder.failRecords = true;
            f.recorder.failPlains = true;
            String console = f.captureConsole(() -> {
                f.bot.accept(f.groupEvent(".get"));
                Dispatch attempt = f.recorder.awaitImage(5000);
                equal("sendRecord", attempt.kind(), "先尝试合并转发");
                Dispatch fallback = f.recorder.awaitImage(5000);
                equal("send", fallback.kind(), "合并转发失败后仍然回退普通发送");
                equal(3, fallback.segments(), "回退把整批放在一条普通消息里");
                equal(files(paths), fallback.files(), "回退顺序不变");
                String receipt = f.recorder.awaitText(5000);
                check(receipt.contains("领取图片失败") && receipt.contains("已确认领取 0 张"), "如实报错：" + receipt);
                check(receipt.contains("模拟普通发送失败"), "回退失败的原因要带上：" + receipt);
                equal(paths.size(), f.client.pendingImages().size(), "两次都失败绝不能 acknowledge");
                f.recorder.assertNoImage(300);
            });
            equal(1, f.recorder.records.get(), "只尝试一次合并转发");
            equal(1, f.recorder.plains.get(), "只回退一次普通发送");
            check(console.contains("合并转发失败，已改为普通发送 3 张"), "日志要记下这次回退：" + tail(console));
            equal(3, f.pending(), "回执发出后待领取列表原样保留");
        }
    }

    /** 禁言期间"聊天记录"照旧被跳过：多图一张都不发；解除后恢复合并转发。 */
    private static void mutedGroupSendsNothing() throws Exception {
        try (Fixture f = new Fixture()) {
            f.seed(3, "seed");
            f.bot.accept(f.ban("999", "0", "ban", 600));
            check(!f.bot.muteStatus().isEmpty(), "全群禁言状态已生效");
            f.bot.accept(f.groupEvent(".get"));
            f.recorder.assertNoImage(400);
            f.recorder.assertNoDispatch(200);
            f.bot.accept(f.ban("999", "0", "lift_ban", 0));
            check(f.bot.muteStatus().isEmpty(), "解除禁言后恢复");
            waitUntil(() -> f.pending() == 0, 3000);
            Thread.sleep(150);
            f.bot.accept(f.groupEvent(".rg 3"));
            Dispatch dispatch = f.recorder.awaitImage(5000);
            equal("sendRecord", dispatch.kind(), "解除禁言后多图仍是一条合并转发");
            equal(3, dispatch.nodes().size(), "回溯 3 张 = 3 个节点");
            check(dispatch.singleImageNodes(), "每个节点只含一张图");
            dispatch.confirm();
            check(f.recorder.awaitText(5000).contains("历史图片回溯完成，共 3 张"), "回溯回执文案不变");
            equal(0, f.recorder.mapped.get(), "回溯不用 sendMap");
        }
    }

    /** 地图（sendImages）：多张合成一条合并转发，单张仍走 sendMap；label 回执保持。 */
    private static void mapBatchUsesOneRecordAndSingleMapStaysPlain() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Path> yh = f.mapImages("yh", List.of("a.png", "b.png", "c.png"));
            f.bot.accept(f.groupEvent(".yh"));
            String label = f.recorder.awaitText(5000);
            check(label.contains("深大粤海地图") && label.contains("3 张"), "先回一句说明：" + label);
            Dispatch dispatch = f.recorder.awaitImage(5000);
            equal("sendRecord", dispatch.kind(), "地图 3 张走一条合并转发");
            equal(3, dispatch.nodes().size(), "3 个节点");
            check(dispatch.singleImageNodes(), "每个节点只含一张图");
            equal(mapFiles(yh), dispatch.files(), "地图节点顺序与目录顺序一致");
            equal(0, f.recorder.mapped.get(), "多张地图不再逐张 sendMap");
            dispatch.confirm();

            f.mapImages("liv", List.of("only.png"));
            f.bot.accept(f.groupEvent(".liv"));
            check(f.recorder.awaitText(5000).contains("1 张"), "单张地图照样先说明");
            Dispatch single = f.recorder.awaitImage(5000);
            equal("sendMap", single.kind(), "单张地图仍走 sendMap");
            equal(1, f.recorder.mapped.get(), "只有这一次 sendMap");
            equal(1, f.recorder.records.get(), "单张地图不再套合并转发");
            single.confirm();
            f.recorder.assertNoImage(300);

            // 合并转发失败 → 回退逐张普通发送；回退成功也算发出去了，信号量必须释放
            // （连续 5 次失败之后仍然发得出去）。
            f.recorder.failRecords = true;
            for (int attempt = 0; attempt < 5; attempt++) {
                f.bot.accept(f.groupEvent(".yh"));
                check(f.recorder.awaitText(5000).contains("深大粤海地图"), "失败前先给说明（第 " + attempt + " 次）");
                equal("sendRecord", f.recorder.awaitImage(5000).kind(), "地图多图仍然先尝试合并转发");
                for (int image = 1; image <= 3; image++) {
                    Dispatch fallback = f.recorder.awaitImage(5000);
                    equal("sendMap", fallback.kind(), "合并转发失败后回退 sendMap（第 " + image + " 张）");
                    fallback.confirm();
                }
            }
            check(f.recorder.texts.stream().noneMatch(text -> text.contains("图片发送失败")),
                    "回退成功就不该有失败回执：" + f.recorder.texts);
            f.recorder.failRecords = false;
            f.bot.accept(f.groupEvent(".liv"));
            check(f.recorder.awaitText(5000).contains("深大丽湖地图：1 张"), "五次失败之后发送名额没有泄漏");
            Dispatch recovered = f.recorder.awaitImage(5000);
            equal("sendMap", recovered.kind(), "失败恢复后单张地图照旧 sendMap");
            recovered.confirm();
        }
    }

    /**
     * 传输层没实现合并转发（只实现了 send）时保持它原来就有的发法：
     * 一次领取仍是一条含 N 张图的消息，地图仍逐张发送——绝不把一批图拆成一串消息。
     */
    private static void transportWithoutForwardSupportKeepsOldDelivery() throws Exception {
        try (Fixture f = new Fixture(new LegacyRecorder())) {
            List<Path> paths = f.seed(3, "seed");
            f.bot.accept(f.groupEvent(".get"));
            Dispatch dispatch = f.recorder.awaitImage(5000);
            equal("send", dispatch.kind(), "没有合并转发的传输层照旧走 send");
            equal(3, dispatch.segments(), "整批仍然是一条消息（3 个图片段）");
            equal(files(paths), dispatch.files(), "顺序不变");
            equal(0, f.recorder.records.get(), "不会调用它没实现的 sendRecord");
            equal(1, f.recorder.plains.get(), "一次领取只发一条消息");
            check(!dispatch.singleImageNodes(), "没有节点概念：整批就在同一条消息里");
            dispatch.confirm();
            check(f.recorder.awaitText(5000).contains("本次领取完成，共 3 张"), "回执不变");
            f.awaitPending(0);
            equal(0, f.pending(), "确认后照旧清空");

            f.mapImages("yh", List.of("a.png", "b.png"));
            f.bot.accept(f.groupEvent(".yh"));
            check(f.recorder.awaitText(5000).contains("深大粤海地图"), "地图先给说明");
            Dispatch first = f.recorder.awaitImage(5000);
            equal("sendMap", first.kind(), "地图仍逐张 sendMap");
            equal(1, first.nodes().size(), "每张地图各自一条消息");
            first.confirm();
            Dispatch second = f.recorder.awaitImage(5000);
            equal("sendMap", second.kind(), "第二张仍单独发");
            second.confirm();
            equal(0, f.recorder.records.get(), "不套合并转发");
        }
    }

    /** 地图路径的发送方式日志：多图合并转发 + 单张普通发送；合并转发失败 → 回退 sendMap。 */
    private static void mapDeliveryLogsAndFallback() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Path> maps = f.mapImages("yh", List.of("a.png", "b.png", "c.png"));
            String forward = f.captureConsole(() -> {
                f.bot.accept(f.groupEvent(".yh"));
                check(f.recorder.awaitText(5000).contains("深大粤海地图：3 张"), "地图先给说明");
                Dispatch dispatch = f.recorder.awaitImage(5000);
                equal("sendRecord", dispatch.kind(), "地图 3 张走一条合并转发");
                equal(3, dispatch.nodes().size(), "3 个节点");
                equal(mapFiles(maps), dispatch.files(), "节点顺序与目录顺序一致");
                dispatch.confirm();
                f.recorder.assertNoImage(300);
            });
            equal(1, f.recorder.records.get(), "地图多图只发一次合并转发");
            equal(0, f.recorder.mapped.get(), "地图多图不走 sendMap");
            check(forward.contains("地图发送方式") && forward.contains("合并转发 3 张"),
                    "控制台要有地图合并转发日志：" + tail(forward));

            f.mapImages("liv", List.of("only.png"));
            String single = f.captureConsole(() -> {
                f.bot.accept(f.groupEvent(".liv"));
                check(f.recorder.awaitText(5000).contains("深大丽湖地图：1 张"), "单张地图先给说明");
                Dispatch dispatch = f.recorder.awaitImage(5000);
                equal("sendMap", dispatch.kind(), "单张地图走 sendMap");
                dispatch.confirm();
                f.recorder.assertNoImage(300);
            });
            equal(1, f.recorder.mapped.get(), "单张地图只调用一次 sendMap");
            equal(1, f.recorder.records.get(), "单张地图不套合并转发");
            check(single.contains("地图发送方式") && single.contains("单张普通发送"),
                    "控制台要有单张地图发送日志：" + tail(single));

            // 合并转发失败 → 回退逐张 sendMap，并打一行"合并转发失败，已改为普通发送 N 张"。
            f.recorder.failRecords = true;
            String fallbackLog = f.captureConsole(() -> {
                f.bot.accept(f.groupEvent(".yh"));
                check(f.recorder.awaitText(5000).contains("深大粤海地图：3 张"), "回退前先给说明");
                equal("sendRecord", f.recorder.awaitImage(5000).kind(), "地图多图先尝试合并转发");
                for (int image = 1; image <= 3; image++) {
                    Dispatch fallback = f.recorder.awaitImage(5000);
                    equal("sendMap", fallback.kind(), "合并转发失败后回退 sendMap（第 " + image + " 张）");
                    fallback.confirm();
                }
                f.recorder.assertNoImage(300);
            });
            equal(2, f.recorder.records.get(), "第二次 .yh 又尝试了一次合并转发");
            equal(4, f.recorder.mapped.get(), "回退把三张地图逐张 sendMap（含前面单张那次）");
            check(fallbackLog.contains("合并转发失败，已改为普通发送 3 张"), "日志要记下这次回退：" + tail(fallbackLog));
            check(f.recorder.texts.stream().noneMatch(text -> text.contains("图片发送失败")),
                    "回退成功就不该有失败回执：" + f.recorder.texts);
        }
    }

    /**
     * 合并转发前的读图失败（例如某张地图超过 20MB）也要如实报错，而且发送名额必须释放。
     * 用稀疏文件造超大图片：只占文件大小，不真写 20MB 数据。
     *
     * <p>注意回退语义：读图失败也走"回退普通发送"这条兜底，所以能读出来的那张会先被逐张发出去；
     * 无论回退与否，读不出的那张都不会发，失败回执一定会到。
     */
    private static void oversizedMapStillReportsFailureAndReleasesSlot() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Path> maps = f.mapImages("yh", List.of("a.png"));
            Path huge = f.root.resolve("maps/yh/b.png");
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(huge.toFile(), "rw")) { file.setLength(21L * 1024 * 1024); }
            f.bot.accept(f.groupEvent(".yh"));
            check(f.recorder.awaitText(5000).contains("深大粤海地图：2 张"), "先说明要发几张");
            Dispatch readable = f.recorder.tryImage(800);
            if (readable != null) {
                equal("sendMap", readable.kind(), "读图失败触发回退时，能读的那张先普通发出");
                readable.confirm();
            }
            check(f.recorder.awaitText(5000).contains("图片发送失败"), "读图失败要如实报错");
            f.recorder.assertNoImage(300);

            f.mapImages("liv", List.of("only.png"));
            f.bot.accept(f.groupEvent(".liv"));
            check(f.recorder.awaitText(5000).contains("深大丽湖地图：1 张"), "失败之后发送名额没有泄漏");
            Dispatch recovered = f.recorder.awaitImage(5000);
            equal("sendMap", recovered.kind(), "恢复后单张地图照旧 sendMap");
            recovered.confirm();
            equal(1, maps.size(), "测试夹具本身只有一张正常地图");
            check(Files.size(huge) > 20L * 1024 * 1024, "超过 20MB 就是这次读图失败的原因");
        }
    }

    // ------------------------------------------------------------------ 工具
    private record Dispatch(String kind, JsonObject event, List<JsonArray> nodes, CompletableFuture<Void> ack) {
        boolean image() {
            if (nodes.isEmpty() || nodes.get(0).isEmpty()) return false;
            JsonElement segment = nodes.get(0).get(0);
            return segment.isJsonObject() && "image".equals(Json.str(segment.getAsJsonObject(), "type", ""));
        }
        boolean singleImageNodes() {
            if (nodes.isEmpty()) return false;
            for (JsonArray node : nodes) {
                if (node.size() != 1) return false;
                JsonElement segment = node.get(0);
                if (!segment.isJsonObject() || !"image".equals(Json.str(segment.getAsJsonObject(), "type", ""))) return false;
            }
            return true;
        }
        /** 每个节点里那张图的 data.file（生成图是本地文件 URI，地图是 base64）。 */
        List<String> files() {
            List<String> result = new ArrayList<>();
            for (JsonArray node : nodes) for (JsonElement segment : node)
                result.add(Json.str(Json.obj(segment.getAsJsonObject(), "data"), "file", ""));
            return result;
        }
        /** 所有节点里的图片段总数（sendRecord：节点数；send/sendMap：那一条消息的图片段数）。 */
        int segments() {
            int total = 0;
            for (JsonArray node : nodes) total += node.size();
            return total;
        }
        void confirm() { ack.complete(null); }
    }

    /** 记录每一次出站调用；图片一律挂起，等测试显式"确认"。 */
    private abstract static class RecorderBase implements Bot.Sender {
        private final BlockingQueue<Dispatch> dispatches = new LinkedBlockingQueue<>();
        private final AtomicInteger records = new AtomicInteger(), plains = new AtomicInteger(), mapped = new AtomicInteger();
        /** 每一次文本出站（回执、说明、报错）的正文，便于断言"没有失败回执"。 */
        final List<String> texts = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean failRecords;
        volatile boolean failPlains;

        final CompletableFuture<Void> record(String kind, JsonObject event, List<JsonArray> nodes) {
            boolean image = !nodes.isEmpty() && !nodes.get(0).isEmpty()
                    && nodes.get(0).get(0).isJsonObject()
                    && "image".equals(Json.str(nodes.get(0).get(0).getAsJsonObject(), "type", ""));
            Dispatch dispatch = new Dispatch(kind, event.deepCopy(), nodes, new CompletableFuture<>());
            if (image) {
                // 计数必须先于入队：awaitImage() 一取到 dispatch，测试线程马上就会读这几个计数，
                // 先入队再计数会让"这条是 sendMap"与 mapped==0 同时成立（负载下实测偶发失败）。
                if ("send".equals(kind)) plains.incrementAndGet();
                else if ("sendMap".equals(kind)) mapped.incrementAndGet();
                else records.incrementAndGet();
            }
            dispatches.add(dispatch);
            if (!image) { texts.add(Bot.messageText(nodes.get(0))); return CompletableFuture.completedFuture(null); }   // 文本回执照常立即送达
            if (failRecords && "sendRecord".equals(kind))
                return CompletableFuture.failedFuture(new IOException("模拟合并转发失败（send_group_forward_msg），retcode=1200"));
            if (failPlains && "send".equals(kind))
                return CompletableFuture.failedFuture(new IOException("模拟普通发送失败（send_group_msg），retcode=1200"));
            return dispatch.ack();
        }
        Dispatch awaitImage(long millis) throws InterruptedException {
            Dispatch next = tryImage(millis);
            if (next == null) throw new AssertionError("等待图片发送超时（" + millis + " ms）");
            return next;
        }
        /**
         * 等一张图片出站，超时就返回 null。只用于"发不发得出来都有可能"的场景，
         * 因此它会跳过并丢弃途中的非图片出站——调用点必须保证图片之前没有文本回执要靠 awaitText 拿。
         */
        Dispatch tryImage(long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                Dispatch next = poll(deadline);
                if (next == null) return null;
                if (next.image()) return next;
            }
        }
        String awaitText(long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                Dispatch next = poll(deadline);
                if (next == null) throw new AssertionError("等待文本回执超时（" + millis + " ms）");
                if (!next.image()) return Bot.messageText(next.nodes().get(0));
            }
        }
        void assertNoImage(long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                Dispatch next = poll(deadline);
                if (next == null) return;
                check(!next.image(), "不该发出的图片被发送了：" + next.kind());
            }
        }
        void assertNoDispatch(long millis) throws InterruptedException {
            Dispatch next = dispatches.poll(millis, TimeUnit.MILLISECONDS);
            check(next == null, "禁言期间一条消息都不该发出去，实际发了：" + (next == null ? "" : next.kind()));
        }
        private Dispatch poll(long deadline) throws InterruptedException {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return dispatches.poll();
            return dispatches.poll(remaining, TimeUnit.NANOSECONDS);
        }
    }

    /** 会发合并转发的传输层：三个出口都实现。 */
    private static final class Recorder extends RecorderBase {
        @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
            return record("send", event, List.of(segments));
        }
        @Override public CompletableFuture<Void> sendMap(JsonObject event, JsonArray segments) {
            return record("sendMap", event, List.of(segments));
        }
        @Override public CompletableFuture<Void> sendRecord(JsonObject event, List<JsonArray> messages) {
            return record("sendRecord", event, List.copyOf(messages));
        }
    }

    /** 没有合并转发的传输层（只实现 send / sendMap）：图片应当保持它原来的发法。 */
    private static final class LegacyRecorder extends RecorderBase {
        @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
            return record("send", event, List.of(segments));
        }
        @Override public CompletableFuture<Void> sendMap(JsonObject event, JsonArray segments) {
            return record("sendMap", event, List.of(segments));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final SdClient client;
        final RecorderBase recorder;
        final Bot bot;
        private final AtomicInteger ids = new AtomicInteger();

        Fixture() throws Exception { this(new Recorder()); }

        Fixture(RecorderBase recorder) throws Exception {
            this.recorder = recorder;
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "album-delivery").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:9/");
            JsonObject maps = new JsonObject();
            maps.addProperty("yh", "maps/yh");
            maps.addProperty("liv", "maps/liv");
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            config.add("maps", maps);
            config.addProperty("owner_user_id", "10000");
            config.addProperty("gen_auto_get", false);
            Json.atomicWrite(root.resolve("config.json"), config);
            client = new SdClient(root, sd);
            bot = new Bot(new Settings(root), client, recorder);
        }

        String command(String text) throws Exception {
            bot.accept(groupEvent(text));
            return recorder.awaitText(5000);
        }

        JsonObject groupEvent(String text) {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", "group");
            event.addProperty("self_id", "10000");
            event.addProperty("group_id", "999");
            event.addProperty("user_id", "456");
            event.addProperty("message_id", ids.incrementAndGet());
            event.add("message", Maps.text(text));
            return event;
        }

        /** OneBot v11 群禁言通知。 */
        JsonObject ban(String group, String user, String subType, long duration) {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "notice");
            event.addProperty("notice_type", "group_ban");
            event.addProperty("sub_type", subType);
            event.addProperty("group_id", group);
            event.addProperty("user_id", user);
            event.addProperty("operator_id", "1");
            event.addProperty("duration", duration);
            event.addProperty("self_id", "10000");
            event.addProperty("time", System.currentTimeMillis() / 1000);
            return event;
        }

        /** 真实的小 PNG 写进 data/generated/<task>/，并进入待领取队列（保持入队顺序）。 */
        List<Path> seed(int count, String task) throws Exception {
            Path directory = root.resolve("data/generated").resolve(task);
            Files.createDirectories(directory);
            List<Path> paths = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                Path path = directory.resolve(String.format(Locale.ROOT, "%02d.png", index));
                Files.write(path, png());
                paths.add(path);
            }
            new ImageOutbox(root).append(paths);
            return List.copyOf(paths);
        }

        List<Path> mapImages(String campus, List<String> names) throws Exception {
            Path directory = root.resolve("maps").resolve(campus);
            Files.createDirectories(directory);
            List<Path> paths = new ArrayList<>();
            for (String name : names) {
                Path path = directory.resolve(name);
                Files.write(path, png());
                paths.add(path);
            }
            return List.copyOf(paths);
        }

        /** 待领取张数；读盘失败按 -1 处理（仅用于等待/断言，不掩盖真实发送结果）。 */
        int pending() { try { return client.pendingImages().size(); } catch (IOException e) { return -1; } }

        /** 等待领取队列变成 expected 张（ACK 落盘是异步的）。 */
        void awaitPending(int expected) throws Exception { waitUntil(() -> pending() == expected, 3000); }

        /**
         * 捕获这段代码执行期间的控制台输出（stdout + stderr）：Bot 的发送方式用 INFO 打在 stdout、
         * 回退失败用 WARN 打在 stderr，两边一起收才能断言"日志里确实记下了"。
         */
        String captureConsole(Body body) throws Exception {
            java.io.PrintStream out = System.out, err = System.err;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            java.io.PrintStream capture = new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8);
            System.setOut(capture); System.setErr(capture);
            try { body.run(); }
            finally {
                capture.flush();
                System.setOut(out); System.setErr(err);
            }
            return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        public void close() throws IOException {
            bot.close();
            // 只删自己这个 case- 临时目录；领取/ACK 是后台线程在写盘，所以等目录安静下来再带重试删除（见 TestCleanup）。
            TestCleanup.awaitQuiet(root, 5000);
            TestCleanup.deleteQuietly(root);
        }
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }

    /** Maps.localImages 用的本地文件引用（生成图片路径）。 */
    private static List<String> files(List<Path> paths) throws IOException {
        List<String> result = new ArrayList<>();
        for (Path path : paths) result.add(path.toRealPath().toUri().toASCIIString());
        return result;
    }

    /** Maps.image 用的 base64 引用（地图路径）。 */
    private static List<String> mapFiles(List<Path> paths) throws IOException {
        List<String> result = new ArrayList<>();
        for (Path path : paths) result.add("base64://" + Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
        return result;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long millis) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
    }

    /** 捕获控制台期间要跑的代码（允许抛异常）。 */
    @FunctionalInterface
    private interface Body { void run() throws Exception; }

    /** 控制台日志的最后几行：断言失败时贴进报告，比整段日志好读。 */
    private static String tail(String console) {
        List<String> lines = console.lines()
                .filter(line -> line.contains("发送方式") || line.contains("合并转发失败") || line.contains("图片发送失败"))
                .toList();
        return lines.isEmpty() ? "（没有相关日志行）" : String.join(" | ", lines);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + "：expected=" + expected + ", actual=" + actual);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}

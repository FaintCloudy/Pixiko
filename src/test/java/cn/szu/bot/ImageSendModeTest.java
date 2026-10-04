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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code .imgmode}：按会话控制出图是否合成一条「聊天记录」（QQ 合并转发）。
 *
 * <p>只用一个记录型 {@link Bot.Sender} 观察 Bot 真实的出站调用（send / sendMap / sendRecord 的次数、节点数、
 * 顺序），图片一律挂起等测试显式确认；传输层有两个变体，分别覆盖"会发合并转发"与"不会发合并转发"。
 * 绝不碰真 QQ、真 Stable Diffusion 与真实 data：所有状态都写在临时根目录里。
 *
 * <p>每个用例只给一个夹具 seed 一次：{@link SdClient} 会把待领取队列缓存在自己的 {@link ImageOutbox} 里，
 * 同一个夹具里第二次 seed 写的是另一个实例（与 {@link AlbumDeliveryTest} 的用法保持一致）。
 *
 * <p>覆盖：① auto 下 1 张普通、3 张合并（回归既有行为）；② single 下 3 张 = 3 次普通发送、0 次 sendRecord；
 * ③ record 下一张也合并（1 张 = 1 次 sendRecord）；④ record 但没有合并转发能力时如实回退普通发送；
 * ⑤ 设置按会话隔离（含私聊）；⑥ 老配置 / 坏值读成 auto 且不报错；⑦ 中文别名、大小写与非法参数的用法提示。
 */
public final class ImageSendModeTest {
    private static int assertions;
    private static final String GROUP = "999";
    private static final String KEY_999 = "10000:group:999";
    private static final String KEY_888 = "10000:group:888";
    private static final String KEY_PRIVATE = "10000:private:456";

    public static void main(String[] args) throws Exception {
        autoKeepsExistingDelivery();
        singleSendsEveryImageOnItsOwn();
        recordMergesEvenASingleImage();
        recordMergesMultipleImagesToo();
        recordWithoutTransportSupportFallsBackHonestly();
        modeIsPerConversation();
        oldConfigAndBrokenValuesStayOnAuto();
        aliasesUsageAndHelp();
        System.out.println("ImageSendModeTest: " + assertions + " assertions passed：.imgmode 查看/record/single/auto、"
                + "中文别名、auto 行为不变（1 张普通、3 张合并）、single 逐张（3 张=3 次 send/0 次 sendRecord）、"
                + "record 一张也合并（1 张=1 次 sendRecord）、无合并转发能力时如实回退、按会话（含私聊）隔离、"
                + "老配置与坏值读成 auto、非法参数给用法、.help 只多一行。");
    }

    /** ① 没设置过（老配置）就是 auto：1 张普通发送，日志与回执一字不变。 */
    private static void autoKeepsExistingDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            equal(Settings.ImageSendMode.AUTO, f.settings().imageSendMode(KEY_999), "没有设置过的会话读成 auto（老配置兼容）");
            String view = f.command(".imgmode");
            check(view.contains("自动（auto）"), "查看回执报出当前是自动：" + view);
            check(view.contains("多于一张") && view.contains("合并转发"), "查看回执说明自动规则：多张合并");
            check(view.contains("单张保持普通发送"), "查看回执说明自动规则：单张普通");
            check(view.contains("imgmode record") && view.contains("imgmode single"), "查看回执给出改法：" + view);

            f.seed(1, "one");
            String single = f.captureConsole(() -> {
                f.bot.accept(f.groupEvent(GROUP, ".get"));
                Dispatch dispatch = f.recorder.awaitImage(5000);
                equal("send", dispatch.kind(), "auto 下 1 张走普通发送");
                equal(1, dispatch.segments(), "这条消息里就一张图");
                equal(1, f.recorder.plains.get(), "恰好一次 send");
                equal(0, f.recorder.records.get(), "单张不套合并转发");
                dispatch.confirm();
                check(f.recorder.awaitText(5000).contains("本次领取完成，共 1 张"), "回执不变");
                f.awaitPending(0);
            });
            check(single.contains("单张普通发送"), "控制台日志保持「单张普通发送」：" + tail(single));
        }
        try (Fixture f = new Fixture()) {
            List<Path> three = f.seed(3, "three");
            String merged = f.captureConsole(() -> {
                f.bot.accept(f.groupEvent(GROUP, ".get"));
                Dispatch dispatch = f.recorder.awaitImage(5000);
                equal("sendRecord", dispatch.kind(), "auto 下 3 张走一条合并转发");
                equal(3, dispatch.nodes().size(), "3 个节点");
                check(dispatch.singleImageNodes(), "每个节点只含一张图");
                equal(files(three), dispatch.files(), "节点顺序与文件顺序一致");
                dispatch.confirm();
                check(f.recorder.awaitText(5000).contains("本次领取完成，共 3 张"), "回执不变");
                f.awaitPending(0);
            });
            equal(1, f.recorder.records.get(), "恰好一次合并转发");
            equal(0, f.recorder.plains.get(), "整批不再逐张普通发送");
            check(merged.contains("合并转发 3 张"), "控制台日志保持「合并转发 3 张」：" + tail(merged));
            check(!merged.contains("imgmode"), "auto 的日志不出现 .imgmode 标注：" + tail(merged));
        }
    }

    /** ② single：3 张 = 3 次普通发送、0 次 sendRecord，顺序不变，每条消息只含一张图。 */
    private static void singleSendsEveryImageOnItsOwn() throws Exception {
        try (Fixture f = new Fixture()) {
            String reply = f.command(".imgmode single");
            check(reply.contains("已设为") && reply.contains("普通发送") && reply.contains("逐张"), "设置回执说清是逐张普通发送：" + reply);
            equal(Settings.ImageSendMode.SINGLE, f.settings().imageSendMode(KEY_999), "single 按会话保存下来了");

            List<Path> paths = f.seed(3, "seed");
            List<Dispatch> dispatches = new ArrayList<>();
            String console = f.captureConsole(() -> {
                f.bot.accept(f.groupEvent(GROUP, ".get"));
                for (int index = 0; index < 3; index++) {
                    Dispatch dispatch = f.recorder.awaitImage(5000);
                    equal("send", dispatch.kind(), "single 下第 " + (index + 1) + " 张走普通发送");
                    equal(1, dispatch.segments(), "每次普通发送只带一张图");
                    dispatches.add(dispatch);
                    // 逐张：下一张必须等这一张确认之后才发，所以此刻只发到第 index+1 张
                    equal(index + 1, f.recorder.plains.get(), "第 " + (index + 1) + " 张发出时后面的还没发（逐张等确认）");
                    equal(0, f.recorder.records.get(), "single 下至今一次 sendRecord 都没有");
                    dispatch.confirm();
                }
                check(f.recorder.awaitText(5000).contains("本次领取完成，共 3 张"), "回执不变");
                f.awaitPending(0);
            });
            equal(3, f.recorder.plains.get(), "single 下 3 张 = 3 次普通发送");
            equal(0, f.recorder.records.get(), "single 下 0 次 sendRecord");
            equal(0, f.recorder.mapped.get(), "出图不用 sendMap");
            equal(files(paths), allFiles(dispatches), "逐张发送的顺序与文件顺序一致");
            check(console.contains("普通发送 3 张（按 .imgmode single）"), "控制台日志要写明按 .imgmode single：" + tail(console));
            check(!console.contains("合并转发"), "single 下不再出现合并转发日志：" + tail(console));
            f.recorder.assertNoImage(300);
        }
    }

    /** ③ record：只有一张也合成一条合并转发（1 张 = 1 次 sendRecord）。 */
    private static void recordMergesEvenASingleImage() throws Exception {
        try (Fixture f = new Fixture()) {
            String reply = f.command(".imgmode record");
            check(reply.contains("已设为") && reply.contains("合并转发") && reply.contains("只有一张也"), "设置回执说清一张也合并：" + reply);
            equal(Settings.ImageSendMode.RECORD, f.settings().imageSendMode(KEY_999), "record 按会话保存下来了");

            List<Path> one = f.seed(1, "one");
            String console = f.captureConsole(() -> {
                f.bot.accept(f.groupEvent(GROUP, ".get"));
                Dispatch dispatch = f.recorder.awaitImage(5000);
                equal("sendRecord", dispatch.kind(), "record 下只有一张也走合并转发");
                equal(1, dispatch.nodes().size(), "一个节点");
                check(dispatch.singleImageNodes(), "节点里就是那张图");
                equal(files(one), dispatch.files(), "发的就是那张图");
                equal(1, f.recorder.records.get(), "1 张 = 1 次 sendRecord");
                equal(0, f.recorder.plains.get(), "record 下不普通发送");
                dispatch.confirm();
                check(f.recorder.awaitText(5000).contains("本次领取完成，共 1 张"), "回执不变");
                f.awaitPending(0);
            });
            check(console.contains("合并转发 1 张（按 .imgmode record）"), "控制台日志要写明一张也合并：" + tail(console));
            f.recorder.assertNoImage(300);
        }
    }

    /** ③（续）record 下多张同样是一条合并转发，不是逐张，也不是一条普通消息。 */
    private static void recordMergesMultipleImagesToo() throws Exception {
        try (Fixture f = new Fixture()) {
            check(f.command(".imgmode 合并转发").contains("已设为"), "中文别名也能设成 record");
            List<Path> three = f.seed(3, "three");
            f.bot.accept(f.groupEvent(GROUP, ".get"));
            Dispatch dispatch = f.recorder.awaitImage(5000);
            equal("sendRecord", dispatch.kind(), "record 下 3 张仍是一条合并转发");
            equal(3, dispatch.nodes().size(), "3 个节点");
            check(dispatch.singleImageNodes(), "每个节点只含一张图");
            equal(files(three), dispatch.files(), "顺序一致");
            equal(1, f.recorder.records.get(), "只发一次 sendRecord");
            equal(0, f.recorder.plains.get(), "一次普通发送都没有");
            dispatch.confirm();
            check(f.recorder.awaitText(5000).contains("本次领取完成，共 3 张"), "回执不变");
            f.awaitPending(0);
        }
    }

    /** ④ record 但传输层不会发合并转发：如实回退普通发送（日志说明原因），绝不假装成功、也绝不丢图。 */
    private static void recordWithoutTransportSupportFallsBackHonestly() throws Exception {
        try (Fixture f = new Fixture(new LegacyRecorder())) {
            String reply = f.command(".imgmode record");
            check(reply.contains("不支持合并转发") && reply.contains("回退"), "设置回执要如实说明当前传输层做不到：" + reply);
            equal(Settings.ImageSendMode.RECORD, f.settings().imageSendMode(KEY_999), "设置仍然保存下来了");

            f.seed(1, "one");
            String console = f.captureConsole(() -> {
                f.bot.accept(f.groupEvent(GROUP, ".get"));
                Dispatch dispatch = f.recorder.awaitImage(5000);
                equal("send", dispatch.kind(), "record 在不会发合并转发的传输层上回退普通发送");
                equal(1, dispatch.segments(), "一张就是一条普通消息");
                equal(0, f.recorder.records.get(), "绝不调用它没实现的 sendRecord");
                equal(1, f.recorder.plains.get(), "恰好一次普通发送");
                dispatch.confirm();
                check(f.recorder.awaitText(5000).contains("本次领取完成，共 1 张"), "图确实发出去了，照常算领取完成");
                f.awaitPending(0);
            });
            check(console.contains("传输层不支持合并转发") && console.contains("已如实回退"),
                    "日志要如实记下这次回退：" + tail(console));
        }
        try (Fixture f = new Fixture(new LegacyRecorder())) {
            f.command(".imgmode record");
            f.seed(3, "three");
            String console = f.captureConsole(() -> {
                f.bot.accept(f.groupEvent(GROUP, ".get"));
                Dispatch dispatch = f.recorder.awaitImage(5000);
                equal("send", dispatch.kind(), "3 张同样回退普通发送");
                equal(3, dispatch.segments(), "整批放在一条普通消息里，一张都不丢");
                dispatch.confirm();
                check(f.recorder.awaitText(5000).contains("本次领取完成，共 3 张"), "回执不变");
                f.awaitPending(0);
            });
            equal(1, f.recorder.plains.get(), "只发一次普通消息");
            equal(0, f.recorder.records.get(), "全程一次 sendRecord 都没有");
            check(console.contains("传输层不支持合并转发"), "日志如实说明：" + tail(console));
        }
    }

    /** ⑤ 按会话隔离：A 会话设 single 不影响 B 会话与私聊，私聊自己有独立的会话键。 */
    private static void modeIsPerConversation() throws Exception {
        try (Fixture f = new Fixture()) {
            check(f.settings().imageSendMode(KEY_PRIVATE) == Settings.ImageSendMode.AUTO, "私聊键默认 auto");
            check(f.command(".imgmode single").contains("已设为"), "群 999 设为 single");
            check(f.groupCommand("888", ".imgmode").contains("自动（auto）"), "群 888 不受影响，仍是自动");
            equal(Settings.ImageSendMode.SINGLE, f.settings().imageSendMode(KEY_999), "999 = single");
            equal(Settings.ImageSendMode.AUTO, f.settings().imageSendMode(KEY_888), "888 = auto");

            check(f.privateCommand(".imgmode").contains("自动（auto）"), "私聊也是独立会话，默认自动");
            check(f.privateCommand(".imgmode 合并转发").contains("已设为"), "私聊可以单独设成 record");
            equal(Settings.ImageSendMode.RECORD, f.settings().imageSendMode(KEY_PRIVATE), "私聊键 = record");
            equal(Settings.ImageSendMode.SINGLE, f.settings().imageSendMode(KEY_999), "私聊的设置没串到群里");
            equal(Settings.ImageSendMode.AUTO, f.settings().imageSendMode(KEY_888), "群 888 依然是 auto");

            // 888 出 3 张仍走自动规则（合并转发），证明 999 的 single 没外溢
            List<Path> three = f.seed(3, "seed");
            f.bot.accept(f.groupEvent("888", ".get"));
            Dispatch dispatch = f.recorder.awaitImage(5000);
            equal("sendRecord", dispatch.kind(), "888 走自动规则：一条合并转发");
            equal(3, dispatch.nodes().size(), "3 个节点");
            equal(files(three), dispatch.files(), "顺序一致");
            dispatch.confirm();
            check(f.recorder.awaitText(5000).contains("本次领取完成，共 3 张"), "回执不变");
            f.awaitPending(0);
            equal(1, f.recorder.records.get(), "888 那次是唯一一次 sendRecord");
            equal(0, f.recorder.plains.get(), "888 的自动规则没有被 999 的 single 带偏");
        }
    }

    /** ⑥ 老配置（没有 image_send 段）与坏值都必须读成 auto，且不报错、不丢别的键。 */
    private static void oldConfigAndBrokenValuesStayOnAuto() throws Exception {
        Path root = Files.createTempDirectory(workRoot(), "legacy-");
        try {
            Json.atomicWrite(root.resolve("config.json"), minimalConfig());
            Settings settings = new Settings(root);
            equal(Settings.ImageSendMode.AUTO, settings.imageSendMode(KEY_999), "老配置没有该字段 → auto（读得进来）");

            settings.setImageSendMode(KEY_999, Settings.ImageSendMode.RECORD);
            Settings reopened = new Settings(root);
            equal(Settings.ImageSendMode.RECORD, reopened.imageSendMode(KEY_999), "重启后仍然记得 record");
            JsonObject after = Json.parse(Files.readString(root.resolve("config.json")));
            equal("http://127.0.0.1:9/", Json.str(Json.obj(after, "sd"), "base_url", ""), "原有的 sd 段没被这次保存抹掉");
            equal("10000", Json.str(after, "owner_user_id", ""), "原有的顶层键也在");
            equal("record", after.getAsJsonObject("image_send").getAsJsonObject("modes").get(KEY_999).getAsString(),
                    "存储位置：image_send.modes.<会话键> = record");
            equal(Settings.ImageSendMode.AUTO, reopened.imageSendMode(KEY_888), "同段里别的会话仍是 auto（只写自己那条）");

            // 另一个会话再设一次：两条互不覆盖
            reopened.setImageSendMode(KEY_888, Settings.ImageSendMode.SINGLE);
            equal(Settings.ImageSendMode.RECORD, new Settings(root).imageSendMode(KEY_999), "999 的 record 还在");
            equal(Settings.ImageSendMode.SINGLE, new Settings(root).imageSendMode(KEY_888), "888 的 single 也写进去了");

            reopened.setImageSendMode(KEY_999, Settings.ImageSendMode.AUTO);
            equal(Settings.ImageSendMode.AUTO, new Settings(root).imageSendMode(KEY_999), "设回 auto 等于删掉这条设置");
            equal(Settings.ImageSendMode.SINGLE, new Settings(root).imageSendMode(KEY_888), "删自己那条不影响别人");

            for (String broken : new String[]{"", "banana", "RECORDING", "3"}) {
                Json.atomicWrite(root.resolve("config.json"), configWithImageSend(jsonModes(KEY_999, broken)));
                equal(Settings.ImageSendMode.AUTO, new Settings(root).imageSendMode(KEY_999), "坏值「" + broken + "」→ auto（不报错）");
            }
            // 类型不对（段是字符串、modes 是数字/数组、会话值是对象）也不能让读配置失败
            JsonObject wrongSection = minimalConfig(); wrongSection.addProperty("image_send", "oops");
            Json.atomicWrite(root.resolve("config.json"), wrongSection);
            equal(Settings.ImageSendMode.AUTO, new Settings(root).imageSendMode(KEY_999), "image_send 类型不对 → auto");

            JsonObject wrongModes = minimalConfig();
            JsonObject section = new JsonObject(); section.addProperty("modes", 7); wrongModes.add("image_send", section);
            Json.atomicWrite(root.resolve("config.json"), wrongModes);
            equal(Settings.ImageSendMode.AUTO, new Settings(root).imageSendMode(KEY_999), "modes 类型不对 → auto");

            JsonObject arrayModes = minimalConfig();
            JsonObject arraySection = new JsonObject(); JsonArray array = new JsonArray(); array.add(KEY_999);
            arraySection.add("modes", array); arrayModes.add("image_send", arraySection);
            Json.atomicWrite(root.resolve("config.json"), arrayModes);
            equal(Settings.ImageSendMode.AUTO, new Settings(root).imageSendMode(KEY_999), "modes 是数组 → auto");

            JsonObject objectValue = minimalConfig();
            JsonObject modes = new JsonObject(); JsonObject nested = new JsonObject(); nested.addProperty("mode", "record");
            modes.add(KEY_999, nested); objectValue.add("image_send", sections(modes));
            Json.atomicWrite(root.resolve("config.json"), objectValue);
            equal(Settings.ImageSendMode.AUTO, new Settings(root).imageSendMode(KEY_999), "会话值是对象 → auto");
        } finally { TestCleanup.deleteQuietly(root); }
    }

    /** ⑦ 中文别名 / 大小写 / 前后空格都认；非法参数给用法且不改动已保存的设置；.help 只多一行。 */
    private static void aliasesUsageAndHelp() throws Exception {
        // 纯解析：不经 Bot，钉住别名表本身
        for (String value : new String[]{"record", "RECORD", "forward", "聊天记录", "合并转发", "转发", "  转发  "})
            equal(Settings.ImageSendMode.RECORD, Settings.ImageSendMode.parse(value), "别名「" + value + "」→ record");
        for (String value : new String[]{"single", "SINGLE", "plain", "普通", "普通发送", "单张", "逐张"})
            equal(Settings.ImageSendMode.SINGLE, Settings.ImageSendMode.parse(value), "别名「" + value + "」→ single");
        for (String value : new String[]{"auto", "AUTO", "自动", "默认"})
            equal(Settings.ImageSendMode.AUTO, Settings.ImageSendMode.parse(value), "别名「" + value + "」→ auto");
        for (String value : new String[]{"", "  ", "banana", "record single", "记录"})
            equal(null, Settings.ImageSendMode.parse(value), "认不出的参数「" + value + "」→ null（由指令给用法）");
        equal(Settings.ImageSendMode.AUTO, Settings.ImageSendMode.stored(null), "stored(null) → auto");

        try (Fixture f = new Fixture()) {
            for (String value : new String[]{"聊天记录", "合并转发", "转发", "record", "FORWARD"}) {
                String reply = f.command(".imgmode " + value);
                check(reply.contains("已设为") && reply.contains("合并转发"), "「" + value + "」设置成功：" + reply);
                equal(Settings.ImageSendMode.RECORD, f.settings().imageSendMode(KEY_999), "「" + value + "」已保存为 record");
            }
            for (String value : new String[]{"普通", "单张", "逐张", "single", "plain"}) {
                String reply = f.command(".imgmode " + value);
                check(reply.contains("已设为") && reply.contains("普通发送"), "「" + value + "」设置成功：" + reply);
                equal(Settings.ImageSendMode.SINGLE, f.settings().imageSendMode(KEY_999), "「" + value + "」已保存为 single");
            }
            for (String value : new String[]{"自动", "默认", "auto"}) {
                String reply = f.command(".imgmode " + value);
                check(reply.contains("已设为") && reply.contains("自动"), "「" + value + "」设置成功：" + reply);
                equal(Settings.ImageSendMode.AUTO, f.settings().imageSendMode(KEY_999), "「" + value + "」已回到 auto");
            }
            // 斜杠写法也认（QQ 里 . 与 / 是同一个指令），大小写不敏感
            check(f.command("/imgmode 合并转发").contains("已设为"), "/imgmode 也认");
            equal(Settings.ImageSendMode.RECORD, f.settings().imageSendMode(KEY_999), "斜杠写法同样生效");

            // 非法参数：给用法，且不能悄悄改掉已保存的设置
            String bad = f.command(".imgmode 香蕉");
            check(bad.contains("用法") && bad.contains("imgmode"), "非法参数要给用法：" + bad);
            check(bad.contains("record") && bad.contains("single") && bad.contains("auto"), "用法里列全三个取值：" + bad);
            equal(Settings.ImageSendMode.RECORD, f.settings().imageSendMode(KEY_999), "非法参数不改变已保存的设置");
            String tooMany = f.command(".imgmode record single");
            check(tooMany.contains("用法"), "多个参数同样给用法：" + tooMany);
            equal(Settings.ImageSendMode.RECORD, f.settings().imageSendMode(KEY_999), "多个参数也不改变设置");

            // .help：只加了一行 .imgmode，原有指令还在
            String help = f.command(".help");
            check(help.contains(".imgmode [record|single|auto]"), "help 里有 .imgmode 一行：" + line(help, ".imgmode"));
            check(help.contains("一律合并") && help.contains("逐张普通发送") && help.contains("自动（默认"), "help 说清三个取值");
            check(help.contains(".imgcnt <数量>"), "help 里原有的 .imgcnt 没被挤掉");
            check(help.contains(".get — 按命令任务与 imgcnt 分批领取"), "help 里原有的 .get 没被挤掉");
            check(help.contains(".vae [status|list|set"), "help 里原有的 .vae 没被挤掉");
            equal(1, help.split("\\.imgmode", -1).length - 1, ".help 里 .imgmode 只出现一次（一行）");

            // 控制台裸词（不带点）：consoleCommand 认得 imgmode
            equal(".imgmode record", Bot.consoleCommand("imgmode record"), "网页控制台裸词能补成 .imgmode");
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 一次出站调用（图片挂起等确认；文本立即完成）。 */
    private record Dispatch(String kind, List<JsonArray> nodes, CompletableFuture<Void> ack) {
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
        /** 每个节点里那张图的 data.file。 */
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
        /** 每一次文本出站（回执、说明、报错）的正文，便于断言"没有失败回执"、也便于超时时给出线索。 */
        final List<String> texts = new java.util.concurrent.CopyOnWriteArrayList<>();

        final CompletableFuture<Void> record(String kind, JsonObject event, List<JsonArray> nodes) {
            boolean image = !nodes.isEmpty() && !nodes.get(0).isEmpty()
                    && nodes.get(0).get(0).isJsonObject()
                    && "image".equals(Json.str(nodes.get(0).get(0).getAsJsonObject(), "type", ""));
            Dispatch dispatch = new Dispatch(kind, List.copyOf(nodes), new CompletableFuture<>());
            if (image) {
                // 计数先于入队：awaitImage() 拿到 dispatch 后测试线程马上会读这几个计数。
                if ("send".equals(kind)) plains.incrementAndGet();
                else if ("sendMap".equals(kind)) mapped.incrementAndGet();
                else records.incrementAndGet();
            }
            dispatches.add(dispatch);
            if (!image) { texts.add(Bot.messageText(nodes.get(0))); return CompletableFuture.completedFuture(null); }
            return dispatch.ack();
        }
        Dispatch awaitImage(long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                long remaining = deadline - System.nanoTime();
                Dispatch next = remaining <= 0 ? dispatches.poll() : dispatches.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) throw new AssertionError("等待图片发送超时（" + millis + " ms）"
                        + (texts.isEmpty() ? "" : "；期间收到的回执：" + texts.get(texts.size() - 1)));
                if (next.image()) return next;
            }
        }
        String awaitText(long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                long remaining = deadline - System.nanoTime();
                Dispatch next = remaining <= 0 ? dispatches.poll() : dispatches.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) throw new AssertionError("等待文本回执超时（" + millis + " ms）");
                if (!next.image()) return Bot.messageText(next.nodes().get(0));
            }
        }
        void assertNoImage(long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                long remaining = deadline - System.nanoTime();
                Dispatch next = remaining <= 0 ? dispatches.poll() : dispatches.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) return;
                check(!next.image(), "不该发出的图片被发送了：" + next.kind());
            }
        }
    }

    /** 会发合并转发的传输层。 */
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

    /** 不会发合并转发的传输层（只实现 send / sendMap）：supportsRecord() 为 false。 */
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
            Files.createDirectories(workRoot());
            root = Files.createTempDirectory(workRoot(), "case-");
            Json.atomicWrite(root.resolve("config.json"), minimalConfig());
            client = new SdClient(root, Json.obj(Json.parse(Files.readString(root.resolve("config.json"))), "sd"));
            bot = new Bot(new Settings(root), client, recorder);
        }

        Settings settings() throws IOException { return new Settings(root); }

        String command(String text) throws Exception { return groupCommand(GROUP, text); }

        String groupCommand(String group, String text) throws Exception {
            bot.accept(groupEvent(group, text));
            return recorder.awaitText(5000);
        }

        String privateCommand(String text) throws Exception {
            bot.accept(privateEvent(text));
            return recorder.awaitText(5000);
        }

        JsonObject groupEvent(String group, String text) {
            JsonObject event = baseEvent("group");
            event.addProperty("group_id", group);
            event.addProperty("user_id", "456");
            event.add("message", Maps.text(text));
            return event;
        }

        JsonObject privateEvent(String text) {
            JsonObject event = baseEvent("private");
            event.addProperty("user_id", "456");
            event.add("message", Maps.text(text));
            return event;
        }

        private JsonObject baseEvent(String type) {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", type);
            event.addProperty("self_id", "10000");
            event.addProperty("message_id", ids.incrementAndGet());
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

        int pending() { try { return client.pendingImages().size(); } catch (IOException error) { return -1; } }

        void awaitPending(int expected) throws Exception {
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                if (pending() == expected) return;
                Thread.sleep(20);
            }
            check(pending() == expected, "待领取张数应变成 " + expected + "，实际 " + pending() + "（ACK 没落盘？）");
        }

        /** 捕获这段代码执行期间的控制台输出（stdout + stderr）：INFO 在 stdout、WARN 在 stderr，两边一起收。 */
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
            TestCleanup.awaitQuiet(root, 5000);
            TestCleanup.deleteQuietly(root);
        }
    }

    /** 老配置的样子：只有本来就有的键，没有 image_send 段。 */
    private static JsonObject minimalConfig() {
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
        return config;
    }

    private static Path workRoot() { return Path.of(System.getProperty("bot.test.work", "work"), "imgmode").toAbsolutePath(); }

    private static JsonObject jsonModes(String key, String value) {
        JsonObject modes = new JsonObject(); modes.addProperty(key, value);
        return sections(modes);
    }
    private static JsonObject sections(JsonObject modes) {
        JsonObject section = new JsonObject(); section.add("modes", modes);
        return section;
    }
    private static JsonObject configWithImageSend(JsonObject section) {
        JsonObject config = minimalConfig(); config.add("image_send", section); return config;
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

    /** 逐张发送时把所有 dispatch 的图片路径按顺序拼起来，核对顺序与张数。 */
    private static List<String> allFiles(List<Dispatch> dispatches) {
        List<String> result = new ArrayList<>();
        for (Dispatch dispatch : dispatches) result.addAll(dispatch.files());
        return result;
    }

    /** 控制台里跟发送方式有关的最后几行。 */
    private static String tail(String console) {
        List<String> lines = console.lines()
                .filter(line -> line.contains("发送方式") || line.contains("合并转发失败") || line.contains("图片发送失败"))
                .toList();
        return lines.isEmpty() ? "（没有相关日志行）" : String.join(" | ", lines);
    }
    private static String line(String text, String needle) {
        for (String row : text.split("\\R")) if (row.contains(needle)) return row;
        return "（help 里没有 " + needle + "）";
    }

    @FunctionalInterface
    private interface Body { void run() throws Exception; }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + "：expected=" + expected + ", actual=" + actual);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}

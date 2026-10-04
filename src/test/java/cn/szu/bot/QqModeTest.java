package cn.szu.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import cn.szu.bot.sd.ImageOutbox;
import cn.szu.bot.sd.SdClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code .mode}：QQ 侧回执档位（{@code normal} 只发肯定与图片 / {@code debug} 完整回执＝现状），按会话持久保存。
 *
 * <p>两条腿都是桩，绝不碰真 QQ、真 Stable Diffusion 与真 data：
 * <ul>
 *   <li>出图/提示词/设置走**回环 HTTP 假 SD**（真 {@link SdClient}），图片是自己造的 128×128 PNG；</li>
 *   <li>VAE 冲突用**假 VAE 桩**注入 BLOCK 快照，出图前防呆的拒绝路径是真的；</li>
 *   <li>出站用一个记录型 {@link Bot.Sender}：文本立即"成功"，图片挂起等测试确认，每一次出站都留底
 *       （{@code seen}），既能断言"发了什么"，也能断言"这段时间什么都没发"。</li>
 * </ul>
 *
 * <p>覆盖：① 默认（没设置）＝现状，且与显式 {@code .mode debug} 逐字相同；② normal 下 {@code .gen}
 * 入队回执不含全部生成参数字段，也不含多步过程文案；③ normal 下仍然有肯定 + 图片，且领取（ACK）不受影响；
 * ④ normal 下失败/被拒/权限不足/用法错误/需要用户决定的提示照旧发出；⑤ 按会话隔离；⑥ 无参数看当前值、
 * 非法值给用法且不改设置；⑦ 切回 debug 后与现状一致；⑧ 存储位置与坏配置容错；⑨ .help 只加一行。
 */
public final class QqModeTest {
    private static int assertions;
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final String GROUP = "999";
    private static final String KEY_999 = "10000:group:999";
    private static final String KEY_888 = "10000:group:888";
    private static final String KEY_PRIVATE = "10000:private:456";
    /** 出图回执里那一整块参数清单的字段名：normal 下一个都不许出现。 */
    private static final List<String> PARAM_NAMES = List.of(
            "采样方法", "图片尺寸", "迭代步数", "CFG", "种子", "基础模型", "提示词来源", "参数来源");

    public static void main(String[] args) throws Exception {
        aliasesAndStoredValues();
        storageShapeAndBrokenConfig();
        defaultIsCurrentBehaviourWordForWord();
        normalHidesTheWholeParameterBlock();
        normalStillAffirmsAndDeliversImages();
        normalKeepsFailuresVisible();
        modesArePerConversation();
        currentValueAndInvalidArgument();
        switchingBackToDebugRestoresEverything();
        helpHasExactlyOneNewLine();
        check(assertions >= 60, "断言条数应 ≥ 60，实际 " + assertions);
        System.out.println("QqModeTest: " + assertions + " assertions passed：.mode 查看/常规/debug、中文别名、"
                + "默认＝现状（未设置与显式 debug 逐字相同）、normal 隐藏全部生成参数字段与多步过程文案、"
                + "normal 仍有肯定+图片且领取流程不受影响、失败/VAE 拒绝/权限/用法错误照旧可见、"
                + "按会话（含私聊）隔离、非法值给用法且不改设置、切回 debug 与现状一致、存储 qq_mode.modes.<会话键>、"
                + ".help 只加一行。");
    }

    // ------------------------------------------------------------------ ① 解析表

    /** 别名表与存储取值：钉住 .mode 认哪些词、config.json 里写什么。 */
    private static void aliasesAndStoredValues() {
        for (String value : new String[]{"normal", "NORMAL", "Normal", "quiet", "简洁", "常规", "  常规  "})
            equal(Settings.QqMode.NORMAL, Settings.QqMode.parse(value), "别名「" + value + "」→ normal");
        for (String value : new String[]{"debug", "DEBUG", "Debug", "verbose", "full", "调试", "详细", "完整"})
            equal(Settings.QqMode.DEBUG, Settings.QqMode.parse(value), "别名「" + value + "」→ debug");
        for (String value : new String[]{"", "  ", "banana", "normal debug", "常规模式", "调试模式x", "3"})
            equal(null, Settings.QqMode.parse(value), "认不出的参数「" + value + "」→ null（由指令给用法）");
        equal(Settings.QqMode.DEBUG, Settings.QqMode.stored(null), "stored(null) → debug（默认）");
        for (String value : new String[]{"", "banana", "3", "NORMALING", "normality"})
            equal(Settings.QqMode.DEBUG, Settings.QqMode.stored(value), "存坏的值「" + value + "」→ debug");
        for (String value : new String[]{"normal", "NORMAL", " quiet ", "简洁"})
            equal(Settings.QqMode.NORMAL, Settings.QqMode.stored(value), "存的值「" + value + "」→ normal");
        equal("normal", Settings.QqMode.NORMAL.key(), "normal 存进 config.json 的取值");
        equal("debug", Settings.QqMode.DEBUG.key(), "debug 存进 config.json 的取值");
        equal("常规", Settings.QqMode.NORMAL.label(), "normal 的中文说法");
        equal("调试", Settings.QqMode.DEBUG.label(), "debug 的中文说法");
    }

    /** 存储位置、按会话独立、debug 等于删掉这条设置、坏配置一律读成 debug 且不报错。 */
    private static void storageShapeAndBrokenConfig() throws Exception {
        Files.createDirectories(workRoot());
        Path root = Files.createTempDirectory(workRoot(), "store-");
        try {
            Json.atomicWrite(root.resolve("config.json"), config(false));
            Settings settings = new Settings(root);
            equal(Settings.QqMode.DEBUG, settings.qqMode(KEY_999), "老配置没有 qq_mode 段 → debug（读得进来）");

            settings.setQqMode(KEY_999, Settings.QqMode.NORMAL);
            equal(Settings.QqMode.NORMAL, new Settings(root).qqMode(KEY_999), "重启后仍然记得 normal");
            JsonObject after = Json.parse(Files.readString(root.resolve("config.json")));
            equal("normal", after.getAsJsonObject("qq_mode").getAsJsonObject("modes").get(KEY_999).getAsString(),
                    "存储位置：qq_mode.modes.<会话键> = normal");
            equal("http://127.0.0.1:9/", Json.str(Json.obj(after, "sd"), "base_url", ""), "原有的 sd 段没被这次保存抹掉");
            equal("10000", Json.str(after, "owner_user_id", ""), "原有的顶层键也在");
            equal(Settings.QqMode.DEBUG, new Settings(root).qqMode(KEY_888), "同段里别的会话仍是 debug（只写自己那条）");

            settings.setQqMode(KEY_888, Settings.QqMode.NORMAL);
            equal(Settings.QqMode.NORMAL, new Settings(root).qqMode(KEY_999), "999 的 normal 还在");
            equal(Settings.QqMode.NORMAL, new Settings(root).qqMode(KEY_888), "888 的 normal 也写进去了");

            settings.setQqMode(KEY_999, Settings.QqMode.DEBUG);
            equal(Settings.QqMode.DEBUG, new Settings(root).qqMode(KEY_999), "设回 debug 等于删掉这条设置（回到默认）");
            equal(Settings.QqMode.NORMAL, new Settings(root).qqMode(KEY_888), "删自己那条不影响别人");

            for (JsonElement broken : List.of(new JsonPrimitive("oops"), new JsonPrimitive(7), new JsonArray())) {
                JsonObject brokenConfig = config(false);
                brokenConfig.add("qq_mode", broken);
                Json.atomicWrite(root.resolve("config.json"), brokenConfig);
                equal(Settings.QqMode.DEBUG, new Settings(root).qqMode(KEY_999), "qq_mode 段类型不对 → debug（不报错）");
            }
            JsonObject wrongModes = config(false);
            JsonObject section = new JsonObject(); section.addProperty("modes", 7); wrongModes.add("qq_mode", section);
            Json.atomicWrite(root.resolve("config.json"), wrongModes);
            equal(Settings.QqMode.DEBUG, new Settings(root).qqMode(KEY_999), "modes 类型不对 → debug");
            JsonObject arrayModes = config(false);
            JsonObject arraySection = new JsonObject(); arraySection.add("modes", new JsonArray());
            arrayModes.add("qq_mode", arraySection);
            Json.atomicWrite(root.resolve("config.json"), arrayModes);
            equal(Settings.QqMode.DEBUG, new Settings(root).qqMode(KEY_999), "modes 是数组 → debug");
            JsonObject objectValue = config(false);
            JsonObject modes = new JsonObject(); JsonObject nested = new JsonObject(); nested.addProperty("mode", "normal");
            modes.add(KEY_999, nested);
            JsonObject objectSection = new JsonObject(); objectSection.add("modes", modes);
            objectValue.add("qq_mode", objectSection);
            Json.atomicWrite(root.resolve("config.json"), objectValue);
            equal(Settings.QqMode.DEBUG, new Settings(root).qqMode(KEY_999), "会话值是对象 → debug");
        } finally { TestCleanup.deleteQuietly(root); }
    }

    // ------------------------------------------------------------------ ② 默认＝现状

    /**
     * 默认（没设置过）必须与现状逐字相同：把同一段脚本跑两遍——一遍不设置，一遍显式 {@code .mode debug}——
     * 把每一步的出站正文/发送方式/图片文件名逐条对比，要求一字不差。
     */
    private static void defaultIsCurrentBehaviourWordForWord() throws Exception {
        List<String> unset;
        try (Fixture f = new Fixture()) {
            equal(Settings.QqMode.DEBUG, new Settings(f.root).qqMode(KEY_999), "没有设置过的会话读成 debug（默认＝现状）");
            unset = f.script();
        }
        List<String> explicit;
        try (Fixture f = new Fixture()) {
            check(f.command(".mode debug").contains("已设为"), ".mode debug 可用");
            equal(Settings.QqMode.DEBUG, new Settings(f.root).qqMode(KEY_999), "显式 debug 仍是默认值");
            explicit = f.script();
        }
        equal(unset.size(), explicit.size(), "未设置与显式 debug 的出站条数一样\n未设置：" + unset + "\n显式：" + explicit);
        int compared = Math.min(unset.size(), explicit.size());
        for (int index = 0; index < compared; index++)
            equal(unset.get(index), explicit.get(index), "第 " + (index + 1) + " 条出站逐字相同");
        check(compared >= 12, "对比到的出站条数应该够多（实际 " + compared + "）");
    }

    // ------------------------------------------------------------------ ③ normal 少发什么

    /** normal 下 .gen 入队回执：不含整块生成参数，也不含「已加入生成队列」「任务 #N」这类过程文案。 */
    private static void normalHidesTheWholeParameterBlock() throws Exception {
        try (Fixture f = new Fixture()) {
            String set = f.command(".mode normal");
            check(set.contains("已设为") && set.contains("常规"), "可以切到常规：" + set);
            equal(Settings.QqMode.NORMAL, new Settings(f.root).qqMode(KEY_999), "normal 按会话保存下来了");

            f.bot.accept(f.groupEvent(GROUP, ".gen"));
            String ack = f.awaitText(20000);
            check(ack.contains("开始生成"), "常规档仍有一句肯定：" + ack);
            for (String name : PARAM_NAMES)
                check(!ack.contains(name), "常规档的入队回执不含「" + name + "」：" + ack);
            check(!ack.contains("已加入生成队列"), "常规档不再报「已加入生成队列」：" + ack);
            check(!ack.contains("任务 #"), "常规档不再报任务号：" + ack);
            check(!ack.contains("共 1 次生成"), "常规档不再报「共 N 次生成」：" + ack);
            check(!ack.contains("多步执行完成") && !ack.contains("第 1 步"), "常规档不含多步过程文案：" + ack);
            check(ack.lines().count() <= 2, "常规档的肯定是一两句短话：" + ack);

            Delivery generated = f.awaitImage(30000);
            check(generated.image() && "send".equals(generated.kind()), "图片照常发出：" + generated.kind());
            generated.ack().complete(null);
            check(f.quiet(800), "常规档下「任务 #1 已完成…」与「本次领取完成」都不再发；实际还发了：" + f.seen());
            f.awaitPending(0);

            // 多步链路（自然语言被拆成多步时走的那条路）：总结与每一步的回执块整条都不再发
            int mark = f.mark();
            f.bot.executeChatCommands(f.groupEvent(GROUP, "多步"),
                    List.of(".prompt add mode_chain_a", ".prompt add mode_chain_b"), new JsonObject());
            check(f.quiet(700), "常规档下多步执行的总结与每一步回执块都不再发；实际还发了：" + f.since(mark));
            check(f.since(mark).stream().noneMatch(line -> line.contains("多步执行") || line.contains("【1】")),
                    "常规档下没有多步过程文案：" + f.since(mark));
        }
    }

    // ------------------------------------------------------------------ ④ normal 仍然有什么

    /** normal 下：一句肯定 + 图片（按 .imgmode 规则），而且领取（ACK 落盘）照常完成。 */
    private static void normalStillAffirmsAndDeliversImages() throws Exception {
        // .get：3 张仍按 auto 规则合成一条合并转发，计数回执不发，但图片真的被领取了
        try (Fixture f = new Fixture()) {
            f.command(".mode normal");
            List<Path> three = f.seed(3, "three");
            f.bot.accept(f.groupEvent(GROUP, ".get"));
            Delivery merged = f.awaitImage(15000);
            equal("sendRecord", merged.kind(), "normal 下 3 张仍按 auto 规则合成一条合并转发");
            equal(3, merged.nodes().size(), "3 个节点");
            check(merged.singleImageNodes(), "每个节点只含一张图");
            equal(files(three), merged.names(), "节点顺序与文件顺序一致");
            merged.ack().complete(null);
            check(f.quiet(700), "normal 下不再发「本次领取完成，共 3 张」；实际还发了：" + f.seen());
            f.awaitPending(0);
            equal(0, f.pending(), "图片照常从待领取列表被领取（少发一条回执不影响领取流程）");
        }
        // .gen：图真的出出来 → 自动领取 → 图片自己发到会话里，且 .imgmode single 规则照旧
        try (Fixture f = new Fixture()) {
            f.command(".mode normal");
            f.command(".imgmode single");
            f.bot.accept(f.groupEvent(GROUP, ".gen"));
            String ack = f.awaitText(30000);
            check(ack.contains("好的") && ack.contains("开始生成"), "一句肯定照发：" + ack);
            Delivery generated = f.awaitImage(30000);
            check(generated.image(), "图片照发");
            equal("send", generated.kind(), ".imgmode single 下逐张普通发送（规则没被动过）");
            equal(1, generated.nodes().size(), "一条消息里就一张图");
            check(generated.names().get(0).startsWith("image-"), "发的是这次生成的图：" + generated.names());
            generated.ack().complete(null);
            check(f.quiet(800), "normal 下不报任务结算与领取计数；实际还发了：" + f.seen());
            f.awaitPending(0);
            equal(0, f.pending(), "自动领取照常生效（待领取清零）");
            equal(1, f.generationCalls(), "确实出了一次图（假 SD 只收到一次 txt2img）");
        }
        // 用户明确问参数时照旧回答：过滤只发生在"出图回执"，不在"用户查询"
        try (Fixture f = new Fixture()) {
            f.command(".mode normal");
            String settingsText = f.command(".settings");
            check(settingsText.contains("采样方法") && settingsText.contains("图片尺寸"),
                    "normal 下用户明确问参数照旧回答：" + settingsText);
        }
        // 关闭自动领取时图片不会自己出现：常规档只留一句"发送 .get 领取"（要用户动手的提示不能吞）
        try (Fixture f = new Fixture(false)) {
            f.command(".mode normal");
            f.bot.accept(f.groupEvent(GROUP, ".gen"));
            String ack = f.awaitText(30000);
            check(ack.contains("开始生成"), "肯定照发：" + ack);
            String hint = f.awaitText(30000);
            check(hint.contains(".get"), "关闭自动领取时给出领取办法：" + hint);
            for (String name : PARAM_NAMES) check(!hint.contains(name), "这句提示里不含「" + name + "」：" + hint);
            check(!hint.contains("任务 #"), "这句提示里不含任务号：" + hint);
            check(hint.lines().count() <= 2, "这句提示是短的一两句：" + hint);
            check(f.seen().stream().noneMatch(line -> line.startsWith("image:")), "关闭自动领取时不会自动发图：" + f.seen());
            check(f.pending() >= 1, "图片留在待领取列表里等 .get：" + f.pending());
        }
    }

    // ------------------------------------------------------------------ ⑤ normal 不许吞什么

    /** 失败 / 被拒绝 / 权限不足 / 用法错误 / 需要用户决定的提示，在 normal 下必须照旧发出来。 */
    private static void normalKeepsFailuresVisible() throws Exception {
        // ① 出图失败（假 SD 的 txt2img 返回 500）
        try (Fixture f = new Fixture()) {
            f.command(".mode normal");
            f.generationFails = true;
            f.bot.accept(f.groupEvent(GROUP, ".gen"));
            String ack = f.awaitText(20000);
            check(ack.contains("开始生成"), "肯定照发：" + ack);
            String settle = f.awaitText(30000);
            check(settle.contains("生成失败 1 次"), "失败次数照旧报出来：" + settle);
            check(settle.contains("最近失败原因"), "失败原因照旧发出来（normal 绝不吞）：" + settle);
            check(settle.contains("任务 #1"), "哪一条任务失败也说清楚：" + settle);
            check(settle.contains("已完成"), "结算状态照旧在：" + settle);
            check(f.seen().stream().noneMatch(line -> line.startsWith("image:")), "失败没有图片：" + f.seen());
        }
        // ② VAE 冲突被拒（用户显式选了冲突的 VAE → 出图前防呆拒绝）
        try (Fixture f = new Fixture()) {
            f.command(".mode normal");
            f.bot.useVaeSupport(blockedVae());
            f.bot.accept(f.groupEvent(GROUP, ".gen"));
            f.awaitText(20000);
            String refused = f.awaitText(30000);
            check(refused.contains("任务 #1 已拒绝"), "被拒绝照旧发出来：" + refused);
            check(refused.contains("这次生成被拒绝"), "拒绝理由照旧发出来：" + refused);
            check(refused.contains("拒绝原因"), "拒绝原因字段照旧发出来：" + refused);
            check(refused.contains(".vae auto") && refused.contains(".vae fix"), "改法照旧发出来：" + refused);
            equal(0, f.generationCalls(), "被拒绝时一次 txt2img 都不发");
            check(f.seen().stream().noneMatch(line -> line.startsWith("image:")), "拒绝路径没有图片：" + f.seen());
        }
        // ③ 权限不足 / 用法错误 / 参数校验错误 / 需要用户决定的提示
        try (Fixture f = new Fixture()) {
            f.command(".mode normal");
            String denied = f.command(".chat toggle");
            check(denied.contains("仅 owner"), "权限不足照旧发出来：" + denied);
            String badMode = f.command(".mode 香蕉");
            check(badMode.contains("用法") && badMode.contains("normal") && badMode.contains("debug"),
                    "非法参数给用法：" + badMode);
            String badGen = f.command(".gen abc");
            check(badGen.contains("用法") && badGen.contains("gen"), "指令用法错误照旧发出来：" + badGen);
            String zero = f.command(".gen 0");
            check(zero.contains("正整数"), "参数取值错误照旧发出来：" + zero);
            String empty = f.command(".get");
            check(empty.contains("暂无待领取图片"), "需要用户动手的提示照旧发出（否则用户以为机器人坏了）：" + empty);
            String noSuchLora = f.command(".lora status");
            check(!noSuchLora.isBlank(), "普通查询照旧回答：" + noSuchLora);
            // 明确问用法/帮助也要照旧
            String help = f.command(".help");
            check(help.contains(".mode [normal|debug]"), "normal 下 .help 照旧完整：" + line(help, ".mode ["));
        }
    }

    // ------------------------------------------------------------------ ⑥ 会话隔离

    /** A 会话 normal 不影响 B 会话：B 仍然是 debug 全量输出（含生成参数与计数回执）。 */
    private static void modesArePerConversation() throws Exception {
        try (Fixture f = new Fixture()) {
            check(f.command(".mode normal").contains("已设为"), "群 999 设为 normal");
            String other = f.groupCommand("888", ".mode");
            check(other.contains("调试") && other.contains("debug"), "群 888 不受影响，仍是调试：" + other);
            equal(Settings.QqMode.NORMAL, new Settings(f.root).qqMode(KEY_999), "999 = normal");
            equal(Settings.QqMode.DEBUG, new Settings(f.root).qqMode(KEY_888), "888 = debug");
            equal(Settings.QqMode.DEBUG, new Settings(f.root).qqMode(KEY_PRIVATE), "私聊键默认 debug");

            check(f.privateCommand(".mode").contains("调试"), "私聊也是独立会话，默认调试");
            check(f.privateCommand(".mode 常规").contains("已设为"), "私聊可以单独设成 normal");
            equal(Settings.QqMode.NORMAL, new Settings(f.root).qqMode(KEY_PRIVATE), "私聊键 = normal");
            equal(Settings.QqMode.DEBUG, new Settings(f.root).qqMode(KEY_888), "私聊的设置没串到群 888");

            // 888 出图：入队回执仍是 debug 全量（参数名一个不少），结算与领取计数也照旧
            f.bot.accept(f.groupEvent("888", ".gen"));
            String ack = f.awaitText(20000);
            check(ack.contains("已加入生成队列"), "888 仍是 debug 的完整入队回执：" + ack);
            for (String name : PARAM_NAMES) check(ack.contains(name), "888 的入队回执有「" + name + "」");
            String settle = f.awaitText(30000);
            check(settle.contains("任务 #1 已完成"), "888 的结算回执照旧：" + settle);
            Delivery image = f.awaitImage(30000);
            image.ack().complete(null);
            String count = f.awaitText(20000);
            check(count.contains("本次领取完成，共 1 张"), "888 的领取计数回执照旧：" + count);
            f.awaitPending(0);

            // 999 的 normal 没有外溢：它自己依然只发肯定与图片
            equal(Settings.QqMode.NORMAL, new Settings(f.root).qqMode(KEY_999), "999 仍是 normal");
        }
    }

    // ------------------------------------------------------------------ ⑦ 查看 / 非法参数

    /** .mode 不带参数显示当前值；非法值给用法且不改动已保存的设置；中文/斜杠/大小写都认。 */
    private static void currentValueAndInvalidArgument() throws Exception {
        try (Fixture f = new Fixture()) {
            String initial = f.command(".mode");
            check(initial.contains("本会话的回执档位") && initial.contains("调试") && initial.contains("debug"),
                    "不带参数显示当前值（默认调试）：" + initial);
            check(initial.contains("mode normal") && initial.contains("mode debug"), "给出改法：" + initial);
            check(initial.contains("完整回执"), "说明 debug 是完整回执：" + initial);

            String changed = f.command(".mode 常规");
            check(changed.contains("已设为") && changed.contains("常规"), "中文别名也能设：" + changed);
            check(changed.contains("只发一句肯定与图片"), "说明 normal 只发肯定与图片：" + changed);
            equal(Settings.QqMode.NORMAL, new Settings(f.root).qqMode(KEY_999), "「常规」已保存为 normal");

            String now = f.command(".mode");
            check(now.contains("常规") && now.contains("normal"), "再看一次显示常规：" + now);

            String bad = f.command(".mode 香蕉");
            check(bad.contains("用法"), "非法参数给用法：" + bad);
            check(bad.contains("normal") && bad.contains("debug"), "用法里列全两个取值：" + bad);
            equal(Settings.QqMode.NORMAL, new Settings(f.root).qqMode(KEY_999), "非法参数不改变已保存的设置");
            String tooMany = f.command(".mode normal debug");
            check(tooMany.contains("用法"), "多个参数同样给用法：" + tooMany);
            equal(Settings.QqMode.NORMAL, new Settings(f.root).qqMode(KEY_999), "多个参数也不改变设置");

            check(f.command("/mode debug").contains("已设为"), "斜杠写法也认");
            equal(Settings.QqMode.DEBUG, new Settings(f.root).qqMode(KEY_999), "斜杠写法同样生效");
            check(f.command(".MODE nOrMaL").contains("已设为"), "大小写不敏感");
            equal(Settings.QqMode.NORMAL, new Settings(f.root).qqMode(KEY_999), "大小写混写同样生效");
            check(f.command(".mode 调试").contains("已设为"), "中文「调试」也能设回 debug");
            equal(Settings.QqMode.DEBUG, new Settings(f.root).qqMode(KEY_999), "「调试」已回到 debug");
            check(f.groupCommand("888", ".mode").contains("调试"), "别的会话没被这条指令改到");
            equal(Settings.QqMode.DEBUG, new Settings(f.root).qqMode(KEY_888), "888 仍是默认 debug");
        }
    }

    // ------------------------------------------------------------------ ⑧ 切回 debug

    /** 切回 debug 之后输出与现状一致：参数清单、多步记录、结算与领取计数回执统统回来。 */
    private static void switchingBackToDebugRestoresEverything() throws Exception {
        try (Fixture f = new Fixture()) {
            f.command(".mode normal");
            f.bot.accept(f.groupEvent(GROUP, ".gen"));
            String quietAck = f.awaitText(30000);
            check(!quietAck.contains("采样方法"), "常规下入队回执没有参数：" + quietAck);
            Delivery first = f.awaitImage(30000);
            first.ack().complete(null);
            check(f.quiet(800), "常规下没有结算与领取计数回执：" + f.seen());
            f.awaitPending(0);

            check(f.command(".mode debug").contains("已设为"), "切回 debug");
            equal(Settings.QqMode.DEBUG, new Settings(f.root).qqMode(KEY_999), "切回 debug 已保存");

            f.bot.accept(f.groupEvent(GROUP, ".gen"));
            String ack = f.awaitText(20000);
            check(ack.contains("已加入生成队列"), "切回 debug 后入队回执是完整形态：" + ack);
            for (String name : PARAM_NAMES) check(ack.contains(name), "切回 debug 后「" + name + "」回来了");
            String settle = f.awaitText(30000);
            check(settle.contains("任务 #2 已完成"), "结算回执回来了：" + settle);
            Delivery second = f.awaitImage(30000);
            second.ack().complete(null);
            String count = f.awaitText(20000);
            check(count.contains("本次领取完成，共 1 张"), "领取计数回执回来了：" + count);
            f.awaitPending(0);

            f.bot.executeChatCommands(f.groupEvent(GROUP, "多步"),
                    List.of(".prompt add mode_chain_c", ".prompt add mode_chain_d"), new JsonObject());
            String chain = f.awaitRecord(10000);
            check(chain.contains("多步执行完成：2/2 条"), "多步执行的总结回来了：" + chain);
            check(chain.contains("【1】.prompt add mode_chain_c"), "第 1 步的回执块回来了：" + chain);
            check(chain.contains("【2】.prompt add mode_chain_d"), "第 2 步的回执块回来了：" + chain);
        }
    }

    // ------------------------------------------------------------------ ⑨ help / 控制台

    /** .help 只加一行；网页控制台裸词与聊天链路白名单都认得 .mode。 */
    private static void helpHasExactlyOneNewLine() throws Exception {
        try (Fixture f = new Fixture()) {
            String help = f.command(".help");
            check(help.contains(".mode [normal|debug]"), "help 里有 .mode 一行：" + line(help, ".mode ["));
            check(help.contains("normal（常规）") && help.contains("debug（调试，默认）"), "help 说清两个取值");
            check(help.contains("失败、被拒绝、权限不足与用法错误照旧发出"), "help 说清安全底线");
            long modeLines = help.lines().filter(row -> row.strip().startsWith(".mode ")).count();
            equal(1L, modeLines, "help 里以 .mode 开头的行只有一行（.model 之类不算）");
            check(help.contains(".imgmode [record|single|auto]"), "help 里原有 .imgmode 没被挤掉");
            check(help.contains(".imgcnt <数量>"), "help 里原有 .imgcnt 没被挤掉");
            check(help.contains(".get — 按命令任务与 imgcnt 分批领取"), "help 里原有 .get 没被挤掉");
            check(help.contains(".vae [status|list|set"), "help 里原有 .vae 没被挤掉");

            equal(".mode normal", Bot.consoleCommand("mode normal"), "网页控制台裸词能补成 .mode");
            equal(".model set x", Bot.consoleCommand("model set x"), "model 不会被 mode 抢走");
            equal(".imgmode record", Bot.consoleCommand("imgmode record"), "imgmode 不受影响");

            cn.szu.bot.chat.ChatActions.validate(List.of(".mode normal"));
            check(true, "聊天链路白名单允许 .mode");
            try {
                cn.szu.bot.chat.ChatActions.validate(List.of(".modex normal"));
                check(false, "未知指令必须被挡住");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("不支持"), "未知指令仍被挡住：" + expected.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ 假 VAE

    /** 出图前防呆的 BLOCK 现场：用户可以显式指定了那个冲突的 VAE（vaeAuto=false）。 */
    private static Bot.VaeSupport blockedVae() {
        return new Bot.VaeSupport() {
            @Override public boolean forge() { return true; }
            @Override public String forgePreset() { return "xl"; }
            @Override public String vae() { return "qwen_image_vae.safetensors"; }
            @Override public List<String> vaeList() { return List.of("Automatic", "None", "qwen_image_vae.safetensors"); }
            @Override public List<String> presetModules(String preset) { return List.of(); }
            @Override public JsonObject vaeSnapshot() {
                JsonObject snapshot = new JsonObject();
                snapshot.addProperty("reachable", true);
                snapshot.addProperty("vae", "qwen_image_vae.safetensors");
                snapshot.addProperty("vaeAuto", false);
                snapshot.addProperty("checkpoint", "waiIllustriousSDXL_v170.safetensors");
                JsonArray modules = new JsonArray(); modules.add("qwen_3_06b_base.safetensors");
                snapshot.add("modules", modules);
                JsonObject conflict = new JsonObject();
                conflict.addProperty("level", "BLOCK");
                conflict.addProperty("reason", "当前底模是 SDXL，却挂着 Qwen 的 VAE/文本编码器");
                conflict.addProperty("suggestion", "把 VAE 设回 Automatic，或在 Forge 页面清空额外模块");
                conflict.add("culprits", new JsonArray());
                snapshot.add("conflict", conflict);
                JsonArray choices = new JsonArray(); choices.add("Automatic"); choices.add("qwen_image_vae.safetensors");
                snapshot.add("choices", choices);
                return snapshot;
            }
            @Override public SdClient.GenerationSettings setVae(String name) { return null; }
            @Override public JsonObject setForgePreset(String preset) { return new JsonObject(); }
        };
    }

    // ------------------------------------------------------------------ 出站记录

    /** 一次出站调用（图片挂起等测试确认；文本立即完成）。 */
    private record Delivery(String kind, List<JsonArray> nodes, CompletableFuture<Void> ack) {
        boolean image() {
            for (JsonArray node : nodes) for (JsonElement segment : node)
                if (segment.isJsonObject() && "image".equals(Json.str(segment.getAsJsonObject(), "type", ""))) return true;
            return false;
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
        /** 每个节点里的文本拼起来（合并转发的每个节点都算）。 */
        String text() {
            StringBuilder out = new StringBuilder();
            for (JsonArray node : nodes) {
                String piece = Bot.messageText(node);
                if (piece.isEmpty()) continue;
                if (out.length() > 0) out.append('\n');
                out.append(piece);
            }
            return out.toString();
        }
        /** 图片段的名字（去掉随机任务目录，便于两次运行逐字对比）。 */
        List<String> names() {
            List<String> result = new ArrayList<>();
            for (JsonArray node : nodes) for (JsonElement segment : node) {
                if (!segment.isJsonObject()) continue;
                JsonObject object = segment.getAsJsonObject();
                if (!"image".equals(Json.str(object, "type", ""))) continue;
                String file = Json.str(Json.obj(object, "data"), "file", "");
                int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
                result.add(slash < 0 ? file : file.substring(slash + 1));
            }
            return result;
        }
    }

    /** 记录每一次出站；图片一律挂起等测试"确认"，全部出站按顺序留在 seen 里。 */
    private static final class Wire implements Bot.Sender {
        private final BlockingQueue<Delivery> queue = new LinkedBlockingQueue<>();
        /** 至今收到的全部出站（一行一条），用来断言"这段时间什么都没发"。 */
        final List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();

        private CompletableFuture<Void> record(String kind, List<JsonArray> nodes) {
            Delivery delivery = new Delivery(kind, List.copyOf(nodes), new CompletableFuture<>());
            seen.add(describe(delivery));
            queue.add(delivery);
            return delivery.image() ? delivery.ack() : CompletableFuture.completedFuture(null);
        }

        static String describe(Delivery delivery) {
            return delivery.image()
                    ? "image:" + delivery.kind() + ":" + String.join(",", delivery.names())
                    : "text:" + delivery.kind() + ":" + delivery.text().replaceAll("\\R", " / ");
        }

        @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
            return record("send", List.of(segments));
        }
        @Override public CompletableFuture<Void> sendMap(JsonObject event, JsonArray segments) {
            return record("sendMap", List.of(segments));
        }
        @Override public CompletableFuture<Void> sendRecord(JsonObject event, List<JsonArray> messages) {
            return record("sendRecord", List.copyOf(messages));
        }
    }

    // ------------------------------------------------------------------ 夹具

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
        final ExecutorService executor;
        final SdClient client;
        final Bot bot;
        final Wire wire = new Wire();
        private final JsonObject bridge = new JsonObject();
        private final AtomicInteger generations = new AtomicInteger();
        /** 假 SD 的 txt2img 是否失败（HTTP 500）。 */
        volatile boolean generationFails;
        private final String imageBase64;

        Fixture() throws Exception { this(true); }

        /** autoGet = 出图任务完成后是否自动领取（config.json 的 gen_auto_get）。 */
        Fixture(boolean autoGet) throws Exception {
            Files.createDirectories(workRoot());
            root = Files.createTempDirectory(workRoot(), "case-");
            bridge.addProperty("positive", "mode test +");
            bridge.addProperty("negative", "mode test -");
            bridge.addProperty("sampler_name", "Euler a");
            bridge.add("styles", new JsonArray());
            bridge.addProperty("width", 512); bridge.addProperty("height", 512);
            bridge.addProperty("revision", 1);
            bridge.addProperty("source", "webui-live");
            bridge.addProperty("settings_initialized", true);
            bridge.addProperty("live", true);
            imageBase64 = base64(plainImage());
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "qqmode-mock"); thread.setDaemon(true); return thread;
            });
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sd.addProperty("timeout_seconds", 5);
            JsonObject config = config(autoGet);
            config.add("sd", sd);
            Json.atomicWrite(root.resolve("config.json"), config);
            client = new SdClient(root, sd);
            bot = new Bot(new Settings(root), client, wire);
        }

        Settings settings() throws IOException { return new Settings(root); }
        /** 出图是否真的下发给假 SD（txt2img 调用次数）。 */
        int generationCalls() { return generations.get(); }

        String command(String text) throws Exception { return groupCommand(GROUP, text); }

        String groupCommand(String group, String text) throws Exception {
            bot.accept(groupEvent(group, text));
            return awaitText(10000);
        }

        String privateCommand(String text) throws Exception {
            bot.accept(privateEvent(text));
            return awaitText(10000);
        }

        JsonObject groupEvent(String group, String text) {
            JsonObject event = base("group");
            event.addProperty("group_id", group);
            event.addProperty("user_id", "456");
            event.add("message", Maps.text(text));
            return event;
        }

        JsonObject privateEvent(String text) {
            JsonObject event = base("private");
            event.addProperty("user_id", "456");
            event.add("message", Maps.text(text));
            return event;
        }

        private JsonObject base(String type) {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", type);
            event.addProperty("self_id", "10000");
            event.addProperty("message_id", IDS.incrementAndGet());
            return event;
        }

        /** 真实的小 PNG 写进 data/generated/<task>/，并进入待领取队列（保持入队顺序）。 */
        List<Path> seed(int count, String task) throws Exception {
            Path directory = root.resolve("data/generated").resolve(task);
            Files.createDirectories(directory);
            List<Path> paths = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                Path path = directory.resolve(String.format(java.util.Locale.ROOT, "%02d.png", index));
                Files.write(path, png());
                paths.add(path);
            }
            new ImageOutbox(root).append(paths);
            return List.copyOf(paths);
        }

        int pending() { try { return client.pendingImages().size(); } catch (IOException error) { return -1; } }

        void awaitPending(int expected) throws Exception {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                if (pending() == expected) return;
                Thread.sleep(20);
            }
            check(pending() == expected, "待领取张数应变成 " + expected + "，实际 " + pending() + "（ACK 没落盘？）");
        }

        // ---------------------------------------------------------- 出站等待

        int mark() { return wire.seen.size(); }
        List<String> since(int index) { return List.copyOf(wire.seen.subList(Math.min(index, wire.seen.size()), wire.seen.size())); }
        List<String> seen() { return List.copyOf(wire.seen); }

        /** 这段时间内没有任何出站。 */
        boolean quiet(long millis) throws InterruptedException {
            int before = wire.seen.size();
            Thread.sleep(millis);
            return wire.seen.size() == before;
        }

        String awaitText(long millis) throws InterruptedException {
            return await(false, null, millis).text();
        }

        String awaitRecord(long millis) throws InterruptedException {
            return await(false, "sendRecord", millis).text();
        }

        Delivery awaitImage(long millis) throws InterruptedException {
            return await(true, null, millis);
        }

        private Delivery await(boolean image, String kind, long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                long remaining = deadline - System.nanoTime();
                Delivery next = remaining <= 0 ? wire.queue.poll() : wire.queue.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null)
                    throw new AssertionError("等待出站超时（" + millis + " ms，kind=" + kind + "，image=" + image
                            + "）；至今收到：\n" + String.join("\n", wire.seen));
                if (next.image() != image) {
                    // 不合要求的图片不能一直挂着：确认掉它，避免领取线程死等。
                    if (next.image()) next.ack().complete(null);
                    continue;
                }
                if (kind != null && !kind.equals(next.kind())) continue;
                return next;
            }
        }

        /**
         * 固定脚本：把每一步的出站原样记下来（文本连正文、图片连发送方式与文件名），
         * 供"未设置 vs 显式 .mode debug"逐字对比。每一步的出站条数都是确定的（见各处注释）。
         */
        List<String> script() throws Exception {
            List<String> log = new ArrayList<>();
            for (String command : List.of(".mode", ".settings", ".imgmode", ".size set 640 640",
                    ".vae 香蕉", ".vae set nope.safetensors", ".gen abc", ".prompt add mode_test_a")) {
                bot.accept(groupEvent(GROUP, command));
                log.add("text:" + normalize(awaitText(15000)));
            }
            seed(2, "seed");
            bot.accept(groupEvent(GROUP, ".get"));
            Delivery images = awaitImage(15000);
            log.add("image:" + images.kind() + ":" + String.join(",", images.names()));
            images.ack().complete(null);
            log.add("text:" + normalize(awaitText(15000)));            // 本次领取完成，共 2 张。
            bot.executeChatCommands(groupEvent(GROUP, "多步"),
                    List.of(".prompt add mode_chain_a", ".prompt add mode_chain_b"), new JsonObject());
            log.add("record:" + normalize(awaitRecord(15000)));
            bot.accept(groupEvent(GROUP, ".batch .prompt add mode_batch_a ; .prompt add mode_batch_b"));
            log.add("record:" + normalize(awaitRecord(15000)));
            bot.accept(groupEvent(GROUP, ".gen"));
            log.add("text:" + normalize(awaitText(20000)));            // 已加入生成队列…
            log.add("text:" + normalize(awaitText(30000)));            // 任务 #1 已完成…
            Delivery generated = awaitImage(30000);
            log.add("image:" + generated.kind() + ":" + String.join(",", generated.names()));
            generated.ack().complete(null);
            log.add("text:" + normalize(awaitText(20000)));            // 本次领取完成，共 1 张。
            return log;
        }

        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/pixiko-bridge/v1/prompts")) {
                    synchronized (bridge) {
                        if (exchange.getRequestMethod().equals("PUT")) {
                            JsonObject body = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                            if (body.get("expected_revision").getAsInt() != bridge.get("revision").getAsInt()) {
                                send(exchange, 409, bridge.toString()); return;
                            }
                            for (String key : List.of("positive", "negative", "sampler_name", "styles", "width", "height"))
                                if (body.has(key)) bridge.add(key, body.get(key).deepCopy());
                            bridge.addProperty("revision", bridge.get("revision").getAsInt() + 1);
                        }
                        send(exchange, 200, bridge.toString());
                    }
                } else if (path.equals("/sdapi/v1/samplers")) {
                    JsonArray values = new JsonArray();
                    JsonObject euler = new JsonObject(); euler.addProperty("name", "Euler a"); values.add(euler);
                    send(exchange, 200, values.toString());
                } else if (path.equals("/sdapi/v1/txt2img")) {
                    generations.incrementAndGet();
                    if (generationFails) { send(exchange, 500, "{\"error\":\"模型载入失败（mock）\"}"); return; }
                    JsonArray images = new JsonArray();
                    images.add(imageBase64);
                    JsonObject out = new JsonObject(); out.add("images", images);
                    send(exchange, 200, out.toString());
                } else {
                    // /sdapi/v1/options、/internal/ping 等：一律 404 → VAE 快照读不到，出图前不拦不提醒
                    send(exchange, 404, "{}");
                }
            } finally { exchange.close(); }
        }

        static void send(HttpExchange exchange, int status, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        public void close() throws IOException {
            bot.close();
            server.stop(0);
            executor.shutdownNow();
            TestCleanup.awaitQuiet(root, 5000);
            TestCleanup.deleteQuietly(root);
        }
    }

    /** 老配置的样子：只有本来就有的键，没有 qq_mode 段；gen_auto_get 默认关闭（脚本自己控制领取）。 */
    private static JsonObject config(boolean autoGet) {
        JsonObject sd = new JsonObject();
        sd.addProperty("base_url", "http://127.0.0.1:9/");
        JsonObject maps = new JsonObject();
        maps.addProperty("yh", "maps/yh");
        maps.addProperty("liv", "maps/liv");
        JsonObject config = new JsonObject();
        config.add("sd", sd);
        config.add("maps", maps);
        config.addProperty("owner_user_id", "10000");
        config.addProperty("gen_auto_get", autoGet);
        return config;
    }

    private static Path workRoot() { return Path.of(System.getProperty("bot.test.work", "work"), "qqmode").toAbsolutePath(); }

    private static String normalize(String text) {
        return text.replaceAll("127\\.0\\.0\\.1:\\d+", "127.0.0.1:PORT")
                .replaceAll("(?i)[A-Z]:[\\\\/][^\\s\"，。；]*", "<path>");
    }

    /** "大面积同色背景＋中间一条彩色带"的图：正常图，绝不会被灰图自检判成废图。 */
    private static BufferedImage plainImage() {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 128; y++)
            for (int x = 0; x < 128; x++)
                image.setRGB(x, y, y >= 60 && y < 80 ? (x * 3 % 256) << 16 | (x * 7 % 256) << 8 | (y * 5 % 256) : 0xE6F0FA);
        return image;
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(plainImage(), "png", bytes);
        return bytes.toByteArray();
    }

    private static String base64(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /** 图片文件名（与 Delivery.names() 同一口径，便于核对顺序）。 */
    private static List<String> files(List<Path> paths) {
        List<String> result = new ArrayList<>();
        for (Path path : paths) result.add(path.getFileName().toString());
        return result;
    }

    private static String line(String text, String needle) {
        for (String row : text.split("\\R")) if (row.contains(needle)) return row;
        return "（help 里没有 " + needle + "）";
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + "：expected=" + expected + ", actual=" + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}

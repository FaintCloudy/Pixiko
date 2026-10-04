package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.civitai.CivitaiStyleSync;
import cn.szu.bot.sd.LocalStyles;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.StylePreviews;

/**
 * 「加载样式但 preset（主要是尺寸）不同步」这条链路的守门测试：
 *
 * <ol>
 *   <li><b>尺寸换算表</b>：展示图尺寸（2400×3744 / 1808×2336）按同比例缩到合法生成尺寸，合法值原样不动；</li>
 *   <li><b>载入侧</b>：样式的 {@code forge_preset} 先切（复用 {@code .model preset} 那条路，且**在写底模之前**），
 *       尺寸紧接着套用并如实说明缩放了；预设在样式里没写 / 就是当前预设时行为照旧（不切、不报错）；</li>
 *   <li><b>保存侧</b>：展示图样式落盘时 {@code width/height} 记的是**能直接用于生成的尺寸**，
 *       展示图真实像素进 {@code previewWidth/previewHeight}，重复同步仍然是「复用」（不反复重写）；</li>
 *   <li><b>网页接口</b>：{@code /api/styles} 的步数是整数（32，不是 32.0），并带上展示图真实像素。</li>
 * </ol>
 *
 * 全程临时目录 + 回环地址上的桩 SD，不碰生产 {@code data/}、不碰真 WebUI、不发 QQ 消息。
 */
public final class StylePresetSyncTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        sizeTable();
        sizeEdgeCases();
        appliedSizeText();
        presetSwitchesBeforeCheckpoint();
        presetIsIdempotent();
        legalSizeIsAppliedAsIs();
        withoutPresetFieldBehavesAsBefore();
        rejectedSizeSaysWhy();
        saveSideScalesPreviewSize();
        syncKeepsReusingAfterScaling();
        apiStylesIntegerStepsAndPreviewSize();
        System.out.println("StylePresetSyncTest: " + assertions
                + " assertions passed: 尺寸换算表（2400×3744→984×1536、1808×2336→1192×1536、合法值原样）、"
                + "载入先切 preset 再写底模/尺寸并如实说明缩放、preset 幂等与老样式兼容、"
                + "保存侧 width/height 记生成尺寸 + previewWidth/previewHeight 留展示图像素、/api/styles 步数为整数.");
    }

    // ---------------------------------------------------------------- ① 尺寸换算表

    /** 表格化：输入 → 期望输出（含"原样"与"同比例缩放"两类）。 */
    private static void sizeTable() {
        assertFit(2400, 3744, 984, 1536, "展示图竖幅 2400×3744");
        assertFit(3744, 2400, 1536, 984, "展示图横幅 3744×2400");
        assertFit(2048, 2048, 2048, 2048, "刚好在合法上限的 2048×2048 原样");
        assertFit(1808, 2336, 1192, 1536, "展示图 1808×2336");
        assertFit(1024, 1024, 1024, 1024, "常见预设尺寸 1024×1024 原样");
        assertFit(768, 512, 768, 512, "用户自己设的 768×512 原样");
        assertFit(1664, 1216, 1664, 1216, "线上机器人的 1664×1216 原样");
        // 合法范围内的值一个都不许动（这是"不要顺手缩放"的硬约束）。
        for (int[] size : List.of(new int[]{64, 64}, new int[]{1536, 1536}, new int[]{2048, 1024}, new int[]{512, 2048})) {
            int[] fitted = SdClient.fitGenerationSize(size[0], size[1]);
            check(fitted[0] == size[0] && fitted[1] == size[1], "合法尺寸必须原样：" + size[0] + "×" + size[1] + " → " + show(fitted));
        }
    }

    /** 边界：非 8 倍数、超上限、极小、极端比例，以及"结果一定合法"。 */
    private static void sizeEdgeCases() {
        // 两边都在范围内、只是不是 8 的倍数：各自就近取整到 8 的倍数，不做整体缩放。
        assertFit(1000, 700, 1000, 704, "范围内非 8 倍数就地取整");
        assertFit(1001, 703, 1000, 704, "范围内非 8 倍数（就近）");
        // 超过上限：长边压到 1536。
        assertFit(4096, 2048, 1536, 768, "4096×2048 压到 1536×768（2:1 不变）");
        assertFit(3000, 1000, 1536, 512, "3000×1000 压到 1536×512（3:1 不变）");
        // 极小：抬到 64 的下限。
        assertFit(32, 32, 64, 64, "极小尺寸抬到 64×64");
        assertFit(16, 8, 128, 64, "16×8 抬到 128×64（比例仍 2:1）");
        // 极端比例（>32:1）：比例保不住，但必须仍然合法（两边 64–2048、8 的倍数）。
        int[] extreme = SdClient.fitGenerationSize(100000, 100);
        check(extreme[0] == 2048 && extreme[1] == 64, "极端比例以 2048×64 封顶：" + show(extreme));
        int[] extremeTall = SdClient.fitGenerationSize(64, 4096);
        check(SdClient.validGenerationSize(extremeTall[0], extremeTall[1]),
                "1:64 这种极端比例也必须是合法尺寸（比例保不住）：" + show(extremeTall));
        // 普通比例：结果必须能被 validateSize 接受，且宽高比偏差 ≤1%。
        List<int[]> inputs = List.of(new int[]{2400, 3744}, new int[]{3744, 2400}, new int[]{1808, 2336},
                new int[]{2401, 3743}, new int[]{4096, 2048}, new int[]{3000, 1000}, new int[]{1000, 700},
                new int[]{6000, 6001}, new int[]{2049, 2049}, new int[]{48, 48});
        for (int[] size : inputs) {
            int[] fitted = SdClient.fitGenerationSize(size[0], size[1]);
            check(SdClient.validGenerationSize(fitted[0], fitted[1]),
                    "换算结果必须合法：" + size[0] + "×" + size[1] + " → " + show(fitted));
            double wanted = (double) size[0] / size[1], got = (double) fitted[0] / fitted[1];
            double drift = Math.abs(got - wanted) / wanted;
            check(drift <= 0.01, "宽高比偏差必须 ≤1%：" + size[0] + "×" + size[1] + " → " + show(fitted)
                    + "（偏差 " + String.format(Locale.ROOT, "%.4f%%", drift * 100) + "）");
        }
        check(SdClient.fitGenerationSize(0, 100) == null, "没有尺寸（0×100）返回 null");
        check(SdClient.fitGenerationSize(-8, 100) == null, "负数尺寸返回 null");
    }

    /** 载入回执里的那句话：原样就是「尺寸 1024×1024」，缩放过必须写明原值与新值。 */
    private static void appliedSizeText() {
        equal("尺寸 1024×1024", SdClient.sizeAppliedText(1024, 1024, SdClient.fitGenerationSize(1024, 1024)),
                "没有缩放就不要多话");
        equal("尺寸 768×512", SdClient.sizeAppliedText(768, 512, SdClient.fitGenerationSize(768, 512)),
                "合法尺寸原样套用");
        String scaled = SdClient.sizeAppliedText(2400, 3744, SdClient.fitGenerationSize(2400, 3744));
        equal("尺寸 2400×3744 → 同比例缩到 984×1536", scaled, "缩放过要写明原值→新值");
    }

    // ---------------------------------------------------------------- ② 载入侧：预设 + 尺寸

    /** 样式记着别的栈的预设：载入时**先切预设再写底模**，最后套缩放后的尺寸，回执逐项如实。 */
    private static void presetSwitchesBeforeCheckpoint() throws Exception {
        try (Fixture f = new Fixture()) {
            f.seed(List.of(style("跨栈样式 1", "portrait", model(
                    "baseModel", "Anima", "stack", "anima", "stackSource", "civitai",
                    "forge_preset", "anima", "checkpoint", "animaCatTower_v11.safetensors",
                    "sampler", "ER SDE", "scheduler", "Beta", "steps", 32, "cfg", 4, "distilledCfg", 3,
                    "width", 2400, "height", 3744, "sizeSource", "preview",
                    "previewWidth", 2400, "previewHeight", 3744))));
            check("xl".equals(f.preset()), "桩上当前是 xl 栈（样式的 anima 是另一个栈）：" + f.preset());
            String reply = f.command(".style load 跨栈样式 1");
            check(reply.contains("预设 xl → anima"), "回执写明预设跟着样式切了：" + reply);
            check(reply.contains("尺寸 2400×3744 → 同比例缩到 984×1536"), "回执写明尺寸是缩放后的：" + reply);
            equal("anima", f.preset(), "预设真的切到了 anima");
            // 顺序：切预设的 POST 必须发生在写底模（读模型列表做名称归一）之前。
            int switchAt = f.indexOfCall("options:forge_preset=anima");
            int checkpointAt = f.indexOfCall("models");
            check(switchAt >= 0, "切预设确实发了 forge_preset：" + f.calls());
            check(checkpointAt >= 0, "写了底模（读了一次模型列表）：" + f.calls());
            check(switchAt < checkpointAt, "顺序必须是「先切预设、再写底模」：" + f.calls());
            SdClient client = f.client;
            equal(984, client.settings().width(), "尺寸按同比例缩到 984");
            equal(1536, client.settings().height(), "尺寸按同比例缩到 1536");
            equal("ER SDE", client.settings().samplerName(), "采样方法照旧套回");
            equal("Beta", client.settings().scheduler(), "调度器照旧套回");
            equal(32, client.parameters().steps(), "步数照旧套回");
            equal(4.0, client.parameters().cfgScale(), "CFG 照旧套回");
            equal(3.0, client.settings().distilledCfg(), "Shift 照旧套回");
            check(client.parameters().checkpoint().contains("animaCatTower_v11"), "底模换成了样式里的那个："
                    + client.parameters().checkpoint());
        }
    }

    /** 样式的预设就是当前预设：不重复切（桩上一次 forge_preset POST 都不该有），但回执要说明。 */
    private static void presetIsIdempotent() throws Exception {
        try (Fixture f = new Fixture()) {
            f.seed(List.of(style("同栈样式 1", "portrait", model(
                    "forge_preset", "xl", "checkpoint", "waiIllustriousSDXL_v170.safetensors",
                    "steps", 24, "cfg", 4.5, "distilledCfg", 9, "width", 1024, "height", 1024))));
            equal("xl", f.preset(), "桩上当前就是 xl");
            String reply = f.command(".style load 同栈样式 1");
            check(reply.contains("预设 xl（已是当前预设，未重复切换）"), "回执说明没有重复切：" + reply);
            equal(-1, f.indexOfCall("options:forge_preset=xl"), "同一预设不产生切换请求：" + f.calls());
            equal(-1, f.indexOfCall("options:forge_preset="), "一个 forge_preset POST 都没有：" + f.calls());
            equal(1024, f.client.settings().width(), "合法尺寸照旧套用");
        }
    }

    /** 合法尺寸：原样套用，回执里不许出现"缩到"。 */
    private static void legalSizeIsAppliedAsIs() throws Exception {
        try (Fixture f = new Fixture()) {
            f.seed(List.of(style("小尺寸样式 1", "portrait", model(
                    "forge_preset", "xl", "width", 768, "height", 512))));
            String reply = f.command(".style load 小尺寸样式 1");
            check(reply.contains("尺寸 768×512"), "合法尺寸照实报：" + reply);
            check(!reply.contains("缩到"), "合法尺寸不该被缩放：" + reply);
            equal(768, f.client.settings().width(), "宽度就是 768");
            equal(512, f.client.settings().height(), "高度就是 512");
        }
    }

    /** 老样式（没有 forge_preset 字段）：不切预设、不报错，别的参数照旧套用。 */
    private static void withoutPresetFieldBehavesAsBefore() throws Exception {
        try (Fixture f = new Fixture()) {
            f.seed(List.of(style("老样式 1", "portrait", model(
                    "checkpoint", "waiIllustriousSDXL_v170.safetensors", "sampler", "Euler a",
                    "steps", 20, "cfg", 5.2, "width", 1664, "height", 1216))));
            String before = f.preset();
            String reply = f.command(".style load 老样式 1");
            check(!reply.contains("预设"), "老样式不切预设、回执里也不提预设：" + reply);
            equal(before, f.preset(), "预设没被动过");
            equal(-1, f.indexOfCall("options:forge_preset="), "没有发出任何 forge_preset 切换：" + f.calls());
            equal(1664, f.client.settings().width(), "尺寸照旧套用");
            equal(1216, f.client.settings().height(), "尺寸照旧套用");
            equal(20, f.client.parameters().steps(), "步数照旧套用");
        }
    }

    /**
     * 尺寸实在套不上时（这里用"WebUI 桥接一直 409"模拟）：回执必须写清为什么，
     * 缩放过还要说明"缩完仍被拒绝"，不能再是含糊的"未被接受（保留原值）"。
     */
    private static void rejectedSizeSaysWhy() throws Exception {
        try (Fixture f = new Fixture()) {
            f.bridgeConflict = true;                     // 桥接在线但每次 PUT 都 409
            f.seed(List.of(style("被拒样式 1", "portrait", model(
                    "width", 2400, "height", 3744, "sizeSource", "preview"))));
            int before = f.client.settings().width();
            String reply = f.command(".style load 被拒样式 1");
            check(reply.contains("尺寸 2400×3744 未套用"), "回执写明这项没套上：" + reply);
            check(reply.contains("已同比例缩到 984×1536，仍被拒绝"), "缩放过要说清缩到了多少仍被拒：" + reply);
            check(reply.contains("正在被其他窗口修改"), "要说清被拒的原因（不是含糊的\"未被接受\"）：" + reply);
            check(reply.contains("保留原值"), "还要说明原值被保留：" + reply);
            equal(before, f.client.settings().width(), "套不上时尺寸保持原值");
        }
    }

    // ---------------------------------------------------------------- ③ 保存侧
    /** 展示图样式的尺寸：width/height 记生成尺寸，previewWidth/previewHeight 记展示图真实像素。 */
    private static void saveSideScalesPreviewSize() throws Exception {
        try (Fixture f = new Fixture()) {
            JsonObject recorded = CivitaiStyleSync.applyPreview(
                    model("baseModel", "SDXL", "width", 1024, "height", 1024),
                    "Naruse_Shiroha_-_Summer_pockets_IL", new int[]{2400, 3744}, "data/style-previews/x.png");
            equal(984L, (long) Json.num(recorded, "width", 0), "width 记的是能直接用于生成的 984");
            equal(1536L, (long) Json.num(recorded, "height", 0), "height 记的是能直接用于生成的 1536");
            equal(2400L, (long) Json.num(recorded, "previewWidth", 0), "展示图真实宽度留在 previewWidth");
            equal(3744L, (long) Json.num(recorded, "previewHeight", 0), "展示图真实高度留在 previewHeight");
            equal(CivitaiStyleSync.PREVIEW_SIZE_SOURCE, Json.str(recorded, "sizeSource", ""), "sizeSource 字段保留（界面靠它标注）");
            // LocalStyles 落盘那一道也要做同样的换算（qq/web 的 .style save 与任何 store.save 都走它）。
            LocalStyles local = new LocalStyles(f.root);
            local.save("手存样式 1", "portrait", "bad", false, model(
                    "baseModel", "SDXL", "width", 1808, "height", 2336, "sizeSource", "preview"));
            LocalStyles.Style saved = local.get("手存样式 1");
            equal(1192L, (long) Json.num(saved.model(), "width", 0), "落盘时也换算成 1192");
            equal(1536L, (long) Json.num(saved.model(), "height", 0), "落盘时也换算成 1536");
            equal(1808L, (long) Json.num(saved.model(), "previewWidth", 0), "落盘时记下展示图真实宽度");
            equal(2336L, (long) Json.num(saved.model(), "previewHeight", 0), "落盘时记下展示图真实高度");
            // 合法尺寸的手存样式：一个字都不该多出来。
            local.save("手存样式 2", "portrait", "bad", false, model("baseModel", "SDXL", "width", 768, "height", 512));
            JsonObject plain = local.get("手存样式 2").model();
            check(!plain.has("previewWidth") && !plain.has("previewHeight"), "非展示图样式不写 preview 字段：" + plain);
            equal(768L, (long) Json.num(plain, "width", 0), "非展示图样式尺寸原样");
            // 老文件里已经存了超限值：不迁移也能工作（载入侧现算），读出来还是原值。
            f.writeRawStyle("老展示图样式 1", "old", model("baseModel", "SDXL", "width", 2400, "height", 3744,
                    "sizeSource", "preview"));
            equal(2400L, (long) Json.num(new LocalStyles(f.root).get("老展示图样式 1").model(), "width", 0),
                    "老样式的超限值不被读操作改写");
            String reply = f.command(".style load 老展示图样式 1");
            check(reply.contains("尺寸 2400×3744 → 同比例缩到 984×1536"), "老样式载入时现算缩放：" + reply);
            equal(984, f.client.settings().width(), "老样式的尺寸也真的套上了");
        }
    }

    /** 展示图样式反复同步必须仍是「复用」：换算后的尺寸 + preview 标注不能被当成"参数变了"。 */
    private static void syncKeepsReusingAfterScaling() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] cover = fakePng(2400, 3744);
            LocalStyles local = new LocalStyles(f.root);
            local.save("测试模型 1", "portrait, <lora:测试:1>", "bad", false,
                    model("baseModel", "Anima", "stack", "anima", "width", 1024, "height", 1024));
            StylePreviews.save(f.root, "测试模型 1", cover);
            Path lora = f.root.resolve("work/shirohaANY-clothes.safetensors");
            Files.createDirectories(lora.getParent());
            Files.writeString(lora, "stub");
            CivitaiClient.DownloadedLora download = new CivitaiClient.DownloadedLora("测试模型", "v1", "Anima",
                    List.of(), lora, true, 11, 22, List.of(
                            new CivitaiClient.ShowcasePrompt(1, "portrait", "bad", true, "")));
            JsonObject preset = model("baseModel", "Anima", "stack", "anima", "sampler", "ER SDE",
                    "width", 1024, "height", 1024);
            CivitaiStyleSync.Outcome first = CivitaiStyleSync.run(f.root, download, "<lora:测试:1>", (SdClient) null, true, (CivitaiClient) null, preset);
            equal(1, first.sized(), "第一次同步补上展示图尺寸：" + first.text());
            JsonObject stored = local.get("测试模型 1").model();
            equal(984L, (long) Json.num(stored, "width", 0), "落盘的是生成尺寸 984");
            equal(1536L, (long) Json.num(stored, "height", 0), "落盘的是生成尺寸 1536");
            equal(2400L, (long) Json.num(stored, "previewWidth", 0), "展示图真实宽度记下来了");
            equal(3744L, (long) Json.num(stored, "previewHeight", 0), "展示图真实高度记下来了");
            equal(CivitaiStyleSync.PREVIEW_SIZE_SOURCE, Json.str(stored, "sizeSource", ""), "sizeSource 保留");
            CivitaiStyleSync.Outcome again = CivitaiStyleSync.run(f.root, download, "<lora:测试:1>", (SdClient) null, true, (CivitaiClient) null, preset);
            check(again.text().contains("复用 1"), "重复同步仍然是复用：" + again.text());
            equal(0, again.sized(), "复用时不报补尺寸");
            JsonObject after = local.get("测试模型 1").model();
            equal(984L, (long) Json.num(after, "width", 0), "复用后尺寸不变");
            equal(2400L, (long) Json.num(after, "previewWidth", 0), "复用后展示图像素不变");
            // 展示图换成另一张尺寸（真实像素变了）：要跟着更新，不能一直复用旧值。
            StylePreviews.save(f.root, "测试模型 1", fakePng(1808, 2336));
            CivitaiStyleSync.Outcome changed = CivitaiStyleSync.run(f.root, download, "<lora:测试:1>", (SdClient) null, true, (CivitaiClient) null, preset);
            equal(1, changed.sized(), "展示图尺寸变了要按新的补一次：" + changed.text());
            JsonObject updated = local.get("测试模型 1").model();
            equal(1192L, (long) Json.num(updated, "width", 0), "按新展示图换算成 1192");
            equal(2336L, (long) Json.num(updated, "previewHeight", 0), "previewHeight 跟着换成 2336");
        }
    }

    // ---------------------------------------------------------------- ④ 网页接口

    /** /api/styles：步数是整数（32 而不是 32.0），并且带上展示图真实像素。 */
    private static void apiStylesIntegerStepsAndPreviewSize() throws Exception {
        try (Fixture f = new Fixture()) {
            JsonObject stored = model("baseModel", "SDXL", "stack", "xl", "steps", new java.math.BigDecimal("32.0"),
                    "cfg", new java.math.BigDecimal("4.5"), "width", 984, "height", 1536,
                    "sizeSource", "preview", "previewWidth", 2400, "previewHeight", 3744);
            f.writeRawStyle("面板样式 1", "portrait", stored);
            JsonObject list = f.bot.webStyles();
            JsonObject item = null;
            for (JsonElement element : list.getAsJsonArray("styles"))
                if (element.getAsJsonObject().get("name").getAsString().equals("面板样式 1")) item = element.getAsJsonObject();
            check(item != null, "/api/styles 里有这条样式：" + list);
            JsonObject model = item.getAsJsonObject("model");
            equal("32", model.get("steps").getAsString(), "步数显示成整数 32");
            equal("4.5", model.get("cfg").getAsString(), "CFG 的小数不动");
            equal(984, item.get("width").getAsInt(), "width 是载入时会用的生成尺寸");
            equal(1536, item.get("height").getAsInt(), "height 是载入时会用的生成尺寸");
            equal(2400, item.get("previewWidth").getAsInt(), "previewWidth 给界面显示展示图像素");
            equal(3744, item.get("previewHeight").getAsInt(), "previewHeight 给界面显示展示图像素");
            equal("preview", Json.str(item, "sizeSource", ""), "sizeSource 原样保留（前端靠它标注）");
            check(Json.str(item, "modelSummary", "").contains("984×1536"), "摘要里的尺寸是生成尺寸："
                    + Json.str(item, "modelSummary", ""));
            check(Json.str(item, "modelSummary", "").contains("由展示图 2400×3744 同比例缩放"),
                    "摘要说明这个尺寸是从展示图换算来的：" + Json.str(item, "modelSummary", ""));
            // 保存路径上也不该再写出 32.0：Forge 的预设里步数是 double（这台桩上 xl 是 24.0），落盘时要成整数。
            SdClient client = f.client;
            client.settings();
            String steps = client.styleModelParams("Anima", SdClient.CIVITAI_SOURCE).get("steps").getAsString();
            check(!steps.contains("."), "新存的样式里步数不带小数点：" + steps);
            equal("24", steps, "步数就是 24（桩上 xl 预设的 24.0 归一成整数）");
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static void assertFit(int width, int height, int expectWidth, int expectHeight, String label) {
        int[] fitted = SdClient.fitGenerationSize(width, height);
        check(fitted[0] == expectWidth && fitted[1] == expectHeight, label + "：" + width + "×" + height
                + " 应落到 " + expectWidth + "×" + expectHeight + "，实际 " + show(fitted));
    }

    private static String show(int[] size) { return size == null ? "null" : size[0] + "×" + size[1]; }

    /** 一份最小的 PNG 头（ImageSize 只读签名 + IHDR 的宽高）：够 StylePreviews 存、够读出尺寸。 */
    private static byte[] fakePng(int width, int height) {
        byte[] bytes = new byte[40];
        bytes[0] = (byte) 0x89; bytes[1] = 'P'; bytes[2] = 'N'; bytes[3] = 'G';
        bytes[4] = 13; bytes[5] = 10; bytes[6] = 26; bytes[7] = 10;
        bytes[12] = 'I'; bytes[13] = 'H'; bytes[14] = 'D'; bytes[15] = 'R';
        bytes[16] = (byte) (width >> 24); bytes[17] = (byte) (width >> 16); bytes[18] = (byte) (width >> 8); bytes[19] = (byte) width;
        bytes[20] = (byte) (height >> 24); bytes[21] = (byte) (height >> 16); bytes[22] = (byte) (height >> 8); bytes[23] = (byte) height;
        return bytes;
    }

    private static JsonObject style(String name, String positive, JsonObject model) {
        JsonObject item = new JsonObject();
        item.addProperty("name", name);
        item.addProperty("positive", positive);
        item.addProperty("negative", "");
        item.addProperty("updated_at", "2026-01-01T00:00:00Z");
        if (model != null) item.add("model", model);
        return item;
    }

    /** 键值对拼一份 model（数字按数字写，其余按字符串）。 */
    private static JsonObject model(Object... pairs) {
        JsonObject model = new JsonObject();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            String key = String.valueOf(pairs[index]);
            Object value = pairs[index + 1];
            if (value instanceof Number number) model.addProperty(key, number);
            else model.addProperty(key, String.valueOf(value));
        }
        return model;
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    /**
     * 桩 Forge：两个栈（xl / anima）各自的检查点与推荐参数，POST {@code /sdapi/v1/options} 真的改状态，
     * 并把每个请求按顺序记进 {@link #calls}（顺序断言靠它）。
     */
    private static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
        final SdClient client;
        final Bot bot;
        final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private volatile String preset = "xl";
        /** true = 桥接在线但每次 PUT 都 409（模拟"生成参数正被别人改"）：用来测尺寸套不上时的文案。 */
        volatile boolean bridgeConflict = false;

        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "style-preset-sync-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            Files.createDirectories(root.resolve("work"));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "style-preset-stub");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sd.addProperty("timeout_seconds", 3);
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            config.addProperty("owner_user_id", "123");
            Json.atomicWrite(root.resolve("config.json"), config);
            client = new SdClient(root, sd);
            bot = new Bot(new Settings(root), client, (event, segments) -> {
                replies.add(Bot.messageText(segments));
                return CompletableFuture.completedFuture(null);
            });
        }

        String preset() { return preset; }

        List<String> calls() { return List.copyOf(calls); }

        /** 第一个匹配前缀的调用下标；没有就返回 -1。 */
        int indexOfCall(String prefix) {
            List<String> snapshot = calls();
            for (int index = 0; index < snapshot.size(); index++) if (snapshot.get(index).startsWith(prefix)) return index;
            return -1;
        }

        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (path.equals("/sdapi/v1/options")) {
                    if (method.equals("POST")) {
                        JsonObject update = body.isBlank() ? new JsonObject() : Json.parse(body).getAsJsonObject();
                        if (update.has("forge_preset")) {
                            preset = update.get("forge_preset").getAsString();
                            calls.add("options:forge_preset=" + preset);
                        }
                        if (update.has("sd_model_checkpoint")) {
                            calls.add("options:checkpoint=" + update.get("sd_model_checkpoint").getAsString());
                        }
                        send(exchange, 200, "{}");
                    } else {
                        send(exchange, 200, Json.GSON.toJson(options()));
                    }
                } else if (path.equals("/sdapi/v1/sd-models")) {
                    calls.add("models");
                    JsonArray list = new JsonArray();
                    for (String name : List.of("animaCatTower_v11.safetensors [0351429bd9]",
                            "waiIllustriousSDXL_v170.safetensors [f116b0c78f]")) {
                        JsonObject item = new JsonObject();
                        item.addProperty("title", name);
                        list.add(item);
                    }
                    send(exchange, 200, Json.GSON.toJson(list));
                } else if (path.equals("/sdapi/v1/samplers")) {
                    JsonArray list = new JsonArray();
                    for (String name : List.of("Euler a", "ER SDE")) {
                        JsonObject item = new JsonObject();
                        item.addProperty("name", name);
                        item.add("aliases", new JsonArray());
                        list.add(item);
                    }
                    send(exchange, 200, Json.GSON.toJson(list));
                } else if (path.equals("/sdapi/v1/schedulers")) {
                    JsonArray list = new JsonArray();
                    for (String name : List.of("Automatic", "Beta")) {
                        JsonObject item = new JsonObject();
                        item.addProperty("name", name);
                        list.add(item);
                    }
                    send(exchange, 200, Json.GSON.toJson(list));
                } else if (path.equals("/sdapi/v1/prompt-styles") || path.equals("/sdapi/v1/sd-modules")) {
                    send(exchange, 200, "[]");
                } else if (path.equals("/pixiko-bridge/v1/prompts")) {
                    if (bridgeConflict) {
                        // 桥接在线（读得到状态），但写一律 409：SdClient 重试三次后如实报错。
                        if (method.equals("PUT")) { calls.add("bridge:409"); send(exchange, 409, "{}"); }
                        else send(exchange, 200, Json.GSON.toJson(bridgeState()));
                    } else {
                        send(exchange, 404, "{}");   // 桥接离线：本机样式照旧可用
                    }
                } else {
                    send(exchange, 404, "{}");
                }
            } catch (Exception ignored) {
                try { send(exchange, 500, "{}"); } catch (Exception alsoIgnored) { /* 桩服务，尽力而为 */ }
            } finally {
                exchange.close();
            }
        }

        /** 桥接在线时的状态（形状与真桥接相同）：读得到、但 PUT 一律 409。 */
        JsonObject bridgeState() {
            JsonObject state = new JsonObject();
            state.addProperty("positive", "bridge positive");
            state.addProperty("negative", "bridge negative");
            state.addProperty("source", "webui-live");
            state.addProperty("revision", 7);
            state.addProperty("sampler_name", "Euler a");
            state.add("styles", new JsonArray());
            state.addProperty("width", 1024);
            state.addProperty("height", 1024);
            state.addProperty("settings_initialized", true);
            return state;
        }

        /** Forge 的 options：当前预设 + 两个栈的检查点/推荐参数（步数按真机那样是 double 32.0）。 */
        JsonObject options() {
            JsonObject options = new JsonObject();
            options.addProperty("sd_model_checkpoint", checkpoint(preset));
            options.addProperty("forge_preset", preset);
            options.addProperty("forge_checkpoint_xl", checkpoint("xl"));
            options.addProperty("forge_checkpoint_anima", checkpoint("anima"));
            options.addProperty("xl_t2i_sampler", "Euler a");
            options.addProperty("xl_t2i_scheduler", "Automatic");
            options.addProperty("xl_t2i_step", 24.0);
            options.addProperty("xl_t2i_cfg", 4.5);
            options.addProperty("xl_t2i_dcfg", 9.0);
            options.addProperty("xl_t2i_width", 1024);
            options.addProperty("xl_t2i_height", 1024);
            options.addProperty("anima_t2i_sampler", "ER SDE");
            options.addProperty("anima_t2i_scheduler", "Beta");
            options.addProperty("anima_t2i_step", 32.0);
            options.addProperty("anima_t2i_cfg", 4.0);
            options.addProperty("anima_t2i_dcfg", 3.0);
            options.addProperty("anima_t2i_width", 1024);
            options.addProperty("anima_t2i_height", 1024);
            return options;
        }

        static String checkpoint(String preset) {
            return preset.equals("anima") ? "animaCatTower_v11.safetensors" : "waiIllustriousSDXL_v170.safetensors";
        }

        static void send(HttpExchange exchange, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        /** 直接写一份 data/local-styles.json（模拟用户的真实数据文件）。 */
        void seed(List<JsonObject> styles) throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("version", 1);
            JsonArray array = new JsonArray();
            styles.forEach(array::add);
            data.add("styles", array);
            Json.atomicWrite(root.resolve("data/local-styles.json"), data);
        }

        /** 往已有的样式库里直接追加一条原文（不走 save，模拟"文件里已经存了超限值"的老数据）。 */
        void writeRawStyle(String name, String positive, JsonObject model) throws Exception {
            Path file = root.resolve("data/local-styles.json");
            JsonObject data = Files.isRegularFile(file) ? Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                    : new JsonObject();
            JsonArray array = data.has("styles") ? data.getAsJsonArray("styles") : new JsonArray();
            array.add(style(name, positive, model));
            data.addProperty("version", 1);
            data.add("styles", array);
            Json.atomicWrite(file, data);
        }

        String command(String text) throws Exception {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", "private");
            event.addProperty("self_id", 777);
            event.addProperty("user_id", 123);
            event.addProperty("message_id", System.nanoTime());
            event.add("message", Maps.text(text));
            replies.clear();
            calls.clear();
            bot.accept(event);
            String reply = replies.poll(7, TimeUnit.SECONDS);
            check(reply != null, "命令要有回执：" + text);
            return reply;
        }

        public void close() {
            bot.close();
            server.stop(0);
        }
    }
}

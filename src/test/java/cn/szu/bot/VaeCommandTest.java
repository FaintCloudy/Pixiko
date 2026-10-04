package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.VaeGuard;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * `.vae` 指令、出图前防呆与出图后灰图自检的测试。
 *
 * <p>两条腿都是桩，绝不碰真机器人、真 QQ、真 Stable Diffusion：
 * <ul>
 *   <li>提示词/设置/出图走**回环 HTTP 假 SD**（真 {@link SdClient}，接口与 SdCommandsTest 一致）；</li>
 *   <li>VAE 的读写在 {@link Bot.VaeSupport} 上换成**假 VAE 桩**——真实冲突判定仍由
 *       {@link VaeGuard} 算，所以"SDXL 底模挂 qwen 模块"这种事故组合是真的判成 BLOCK 的。</li>
 * </ul>
 * 图片是自己用 {@link BufferedImage} 造的：纯灰（1 色）与"大面积同色背景但正常"的图各一张。
 */
public final class VaeCommandTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static int assertions;

    /** 事故现场：SDXL 底模 + anima 残留的 Qwen 额外模块。 */
    private static final String SDXL_CHECKPOINT = "waiIllustriousSDXL_v170.safetensors";
    private static final String QWEN_VAE = "qwen_image_vae.safetensors";
    private static final String QWEN_ENCODER = "qwen_3_06b_base.safetensors";

    public static void main(String[] args) throws Exception {
        blankDetectionNumbers();
        commandBranches();
        conflictGuard();
        graySelfCheck();
        realClientSnapshot();
        System.out.println("VaeCommandTest: " + assertions + " assertions passed: .vae status/list/set/auto/none/check/fix, "
                + "illegal name, permissions, help, pre-generation BLOCK split (auto-fix/refuse), WARN, "
                + "post-generation gray self-check with a single retry, plain-image false positives, "
                + "and the real SdClient vaeSnapshot() shape (BLOCK + reachable=false).");
    }

    /**
     * 真 {@link SdClient} 的 VAE 接口（另一个代理的实现）产出的快照，我方读取是否对得上：
     * conflict.level 是小写、modules 是 basename、unreachable 时 reachable=false —— 这些都在这里钉住。
     */
    private static void realClientSnapshot() throws Exception {
        try (Fixture f = new Fixture()) {
            f.bot.useVaeSupport(Bot.sdVaeSupport(f.client));      // 换回真 SdClient 的 VAE 读写
            f.optionsAvailable = true;
            f.optionValues.addProperty("sd_vae", "Automatic");
            f.optionValues.addProperty("sd_model_checkpoint", SDXL_CHECKPOINT);
            JsonArray modules = new JsonArray();
            modules.add("F:/sd/models/VAE/qwen_image_vae.safetensors");
            modules.add("F:/sd/models/text_encoder/qwen_3_06b_base.safetensors");
            f.optionValues.add("forge_additional_modules", modules);
            JsonObject vaeEntry = new JsonObject();
            vaeEntry.addProperty("model_name", "qwen_image_vae.safetensors");
            vaeEntry.addProperty("filename", "F:\\sd\\models\\VAE\\qwen_image_vae.safetensors");
            f.modulesCatalog.add(vaeEntry);

            String status = f.command(".vae");
            check(status.contains("VAE：Automatic"), "真快照的 vae 字段读到了：" + status);
            check(status.contains("底模：" + SDXL_CHECKPOINT), "真快照的 checkpoint 读到了");
            check(status.contains("额外模块（Forge）：qwen_image_vae.safetensors"), "真快照的 modules 是 basename");
            check(status.contains("冲突检测：冲突（BLOCK）"), "真 VaeGuard 的判定被读成 BLOCK（level 小写也认）");
            check(status.contains("原因：") && status.contains("qwen_3_06b_base"), "真快照的原因与冲突模块都在");
            check(status.contains("建议："), "真快照的建议在");
            check(!status.contains("读不到 SD 的实时状态"), "options 读得到时不说读不到");

            String list = f.command(".vae list");
            check(list.contains("Automatic") && list.contains("None"), "真 vaeList 前两项是 Automatic/None：" + list);
            check(list.contains("qwen_image_vae.safetensors"), "真 vaeList 从 sd-modules 里收到了 VAE");

            String checkText = f.command(".vae check");
            check(checkText.contains("修复动作（.vae fix 会依次执行）"), "真快照也能列出修复动作：" + checkText);
            check(checkText.contains("清掉 Forge 额外模块"), "修复动作来自真 VaeGuard.repairPlan");

            // 真 .vae set：写 /sdapi/v1/options 并回读
            int posts = f.optionPosts.get();
            String set = f.command(".vae set None");
            check(set.contains("VAE 已设为「None」"), "真 .vae set 回执：" + set);
            check(f.optionPosts.get() > posts, "真 .vae set 真的写了 options");
            equal("None", f.optionValues.get("sd_vae").getAsString(), "options 里的 sd_vae 已改");

            String illegal = f.command(".vae set nope.safetensors");
            check(illegal.startsWith("操作失败："), "真 .vae set 非法名字报错：" + illegal);
            check(illegal.contains("nope.safetensors"), "非法名字说明是哪一个");

            // SD 读不到（options 404）：reachable=false → 如实说明，且出图前不拦不提醒
            f.optionsAvailable = false;
            String offline = f.command(".vae");
            check(offline.contains("读不到 SD 的实时状态"), "options 读不到时如实说明：" + offline);
            f.plainDefault();
            String receipt = f.generate(".gen");
            check(!receipt.contains("VAE 提醒（不影响本次生成）"), "读不到实时状态时不在出图前乱提醒：" + receipt);
            check(receipt.contains("任务 #1 已完成"), "读不到实时状态也照常出图");
            equal(1, f.generations.size(), "读不到实时状态时出图不受影响");
        }
    }

    // ------------------------------------------------------------------ 灰图判定的数值

    private static void blankDetectionNumbers() throws Exception {
        try (Fixture f = new Fixture()) {
            Path gray = f.writePng("gray.png", 128, 128, (x, y) -> 0x7F7F7F);
            Path black = f.writePng("black.png", 96, 96, (x, y) -> 0x000000);
            Path twoTone = f.writePng("twotone.png", 128, 128, (x, y) -> x < 64 ? 0xFFFFFF : 0x000000);
            Random random = new Random(7);
            Path noise = f.writePng("noise.png", 128, 128, (x, y) -> random.nextInt());
            Path plain = f.plainImage("plain.png");
            Path gradient = f.writePng("gradient.png", 128, 128, (x, y) -> (x * 2) << 16 | (y * 2) << 8);
            Path tiny = f.writePng("tiny.png", 2, 2, (x, y) -> 0x7F7F7F);
            Path small = f.writePng("small.png", 63, 128, (x, y) -> 0x7F7F7F);

            // 事故图的数值：标准差 0、1 种颜色 —— 必须判废。
            check(Bot.blankImage(gray), "纯灰图被判为废图");
            check(Bot.blankImage(black), "纯黑图被判为废图（大面积同色的极端情况）");
            check(!Bot.blankImage(noise), "随机噪声图不是废图");
            // 反例：大面积同色背景但画面正常（背景占 ~85%，中间一条彩色带）。
            check(!Bot.blankImage(plain), "大面积同色背景但正常的图不能被判灰");
            check(!Bot.blankImage(gradient), "渐变图不是废图");
            check(!Bot.blankImage(twoTone), "两种颜色各占一半（标准差很大）不是废图");
            check(!Bot.blankImage(tiny), "2×2 的图（测试桩）不判定");
            check(!Bot.blankImage(small), "63×128 未达最小判定尺寸，不判定");
            check(!Bot.blankImage(null), "空路径不判定");
            check(!Bot.blankImage(f.root.resolve("does-not-exist.png")), "读不出来的图按正常处理（只抓灰图）");

            // 直接对 BufferedImage 断言双条件（标准差与颜色数）。
            double[] grayStats = stats(gray);
            check(grayStats[0] <= Bot.BLANK_DEVIATION, "纯灰图标准差 ≈ 0：" + grayStats[0]);
            check(grayStats[1] <= Bot.BLANK_COLORS, "纯灰图颜色数 ≤ " + Bot.BLANK_COLORS + "：" + grayStats[1]);
            double[] plainStats = stats(plain);
            check(plainStats[0] > Bot.BLANK_DEVIATION || plainStats[1] > Bot.BLANK_COLORS,
                    "正常素图至少不满足一条判废条件：标准差 " + plainStats[0] + "，颜色数 " + plainStats[1]);
            // 判定的实测数值（证据用）：事故灰图 vs 本次修复后的正常图。
            System.out.println("blankImage 实测：纯灰图 标准差=" + round(grayStats[0]) + " 颜色数=" + (int) grayStats[1]
                    + "（判废）；纯黑图 标准差=" + round(stats(black)[0]) + " 颜色数=" + (int) stats(black)[1] + "（判废）"
                    + "；正常素图 标准差=" + round(plainStats[0]) + " 颜色数=" + (int) plainStats[1] + "（不判废）"
                    + "；噪声图 标准差=" + round(stats(noise)[0]) + " 颜色数=" + (int) stats(noise)[1] + "（不判废）");
            check(Bot.blankPixels(new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)), "全黑 BufferedImage 判废（直接构造）");
            check(!Bot.blankPixels(null), "null 图像不判废");
            check(Bot.blankPixels(grayPixels(128, 128, 200)), "接近白的纯色也判废（颜色数 1）");
            check(!Bot.blankPixels(grayPixels(128, 128, 200, 10)), "有一条不同色的带子就不判废");

            // 常量本身也要钉住（免得有人把阈值调到误伤正常图）。
            equal(0.5, Bot.BLANK_DEVIATION, "标准差阈值");
            equal(4, Bot.BLANK_COLORS, "颜色数阈值");
            equal(64, Bot.BLANK_MIN_SIZE, "最小判定尺寸");
        }
    }

    private static double round(double value) { return Math.round(value * 100) / 100.0; }

    /** [标准差, 颜色数]（与 Bot 内部同一口径，只用于断言）。 */
    private static double[] stats(Path path) throws Exception {
        BufferedImage image = ImageIO.read(path.toFile());
        Map<Integer, Integer> colors = new HashMap<>();
        double sum = 0, squares = 0;
        long count = 0;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                colors.merge(rgb, 1, Integer::sum);
                double luma = 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
                sum += luma; squares += luma * luma; count++;
            }
        double mean = sum / count;
        return new double[]{Math.sqrt(Math.max(0, squares / count - mean * mean)), colors.size()};
    }

    private static BufferedImage grayPixels(int width, int height, int color) { return grayPixels(width, height, color, 0); }

    private static BufferedImage grayPixels(int width, int height, int color, int bandHeight) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) image.setRGB(x, y, y < bandHeight ? 0x204060 : color);
        return image;
    }

    // ------------------------------------------------------------------ 指令分支

    private static void commandBranches() throws Exception {
        try (Fixture f = new Fixture()) {
            // 正常状态（OK）：status 的内容
            String status = f.command(".vae");
            check(status.contains("VAE：Automatic（Automatic＝跟随当前模型，推荐）"), "status 报当前 VAE 与 Automatic：" + status);
            check(status.contains("底模：" + SDXL_CHECKPOINT), "status 报当前底模");
            check(status.contains("额外模块（Forge）：（无）"), "status 报额外模块");
            check(status.contains("冲突检测：正常（OK）"), "status 报冲突判定 OK");
            check(status.contains("可用 VAE（共 10 个）："), "status 报可用 VAE 总数");
            check(status.contains("qwen_image_vae.safetensors"), "status 列前若干个 VAE 名字");
            check(status.contains(".vae list 看全部"), "列表太长时提示 .vae list");
            check(status.contains(".vae fix"), "status 带用法");
            equal(status, f.command(".vae status"), ".vae status 与 .vae 同一条路");
            equal(status, f.command(".vae 状态"), "中文别名 状态 也认");

            // 事故状态（BLOCK）：把 anima 的残留模块挂回 SDXL 底模
            f.vae.modules = List.of(QWEN_VAE, QWEN_ENCODER);
            String blocked = f.command(".vae");
            check(blocked.contains("冲突检测：冲突（BLOCK）"), "事故组合判成 BLOCK：" + blocked);
            check(blocked.contains("原因：") && blocked.contains("残留"), "BLOCK 带原因");
            check(blocked.contains("冲突的额外模块：" + QWEN_VAE), "BLOCK 列出冲突模块");
            check(blocked.contains("建议："), "BLOCK 带建议");

            // .vae list
            String list = f.command(".vae list");
            check(list.contains("可用 VAE（共 10 个）"), "list 报总数：" + list);
            check(list.contains("#1 Automatic"), "list 带编号 #1 Automatic");
            check(list.contains("#2 None"), "list 带编号 #2 None");
            check(list.contains("qwen_image_vae.safetensors"), "list 列出全部名字");
            check(list.contains("用 .vae set <名字> 指定"), "list 带用法");

            // .vae set：正常名字 + 立刻回读
            f.vae.modules = List.of();
            String set = f.command(".vae set vae-ft-mse-840000-ema-pruned.safetensors");
            check(set.contains("VAE 已设为「vae-ft-mse-840000-ema-pruned.safetensors」"), "set 回执：" + set);
            check(set.contains("回读校验：vae-ft-mse-840000-ema-pruned.safetensors"), "set 立刻回读");
            equal("vae-ft-mse-840000-ema-pruned.safetensors", f.vae.vae, "set 真的写进去了");
            check(set.contains("冲突检测：冲突（BLOCK）"), "SD1.5 的 VAE 配 SDXL 底模判成 BLOCK");
            check(set.contains("⚠ 这个组合现在会出灰图"), "set 到冲突组合时给出灰图警告");
            check(set.contains("出图前的防呆会"), "set 到冲突组合时说明会被拒绝");

            // .vae set #2 走编号（实时列表解析）
            String byNumber = f.command(".vae set #2");
            check(byNumber.contains("VAE 已设为「None」"), "set #2 按 .vae list 的编号解析：" + byNumber);
            equal("None", f.vae.vae, "set #2 写入 None");

            // .vae auto / .vae none
            f.vae.vae = "qwen_image_vae.safetensors";
            String auto = f.command(".vae auto");
            check(auto.contains("VAE 已设为 Automatic（跟随当前模型）"), "auto 回执：" + auto);
            check(auto.contains("回读校验：Automatic"), "auto 立刻回读");
            equal("Automatic", f.vae.vae, "auto 写入 Automatic");
            f.vae.vae = "Automatic";
            String none = f.command(".vae none");
            check(none.contains("VAE 已设为 None（不使用额外 VAE）"), "none 回执：" + none);
            equal("None", f.vae.vae, "none 写入 None");
            f.vae.vae = "Automatic";

            // 非法名字：把可用列表原样交回
            String illegal = f.command(".vae set 不存在的vae.safetensors");
            check(illegal.startsWith("操作失败："), "非法名字报错：" + illegal);
            check(illegal.contains("没有这个 VAE"), "非法名字说明原因");
            check(illegal.contains("qwen_image_vae.safetensors"), "非法名字带上可用列表");
            check(illegal.contains("用法：.vae"), "非法名字带用法");

            // 用法错误
            check(f.command(".vae set").contains("用法：.vae set <名字>"), ".vae set 缺名字报用法");
            check(f.command(".vae 乱写").contains("用法：.vae"), "未知子命令报用法");
            check(f.command(".vae auto 多余的参数").contains("不带参数"), ".vae auto 不接受参数");
            check(f.command(".vae list x").contains("不带参数"), ".vae list 不接受参数");
            check(f.command(".vae check 1").contains("不带参数"), ".vae check 不接受参数");

            // .vae check：只检测，不改状态
            f.vae.modules = List.of(QWEN_VAE, QWEN_ENCODER);
            String before = f.vae.vae;
            List<String> beforeModules = f.vae.modules;
            f.vae.calls.clear();
            String checkText = f.command(".vae check");
            check(checkText.contains("冲突检测：冲突（BLOCK）"), "check 报 BLOCK：" + checkText);
            check(checkText.contains("修复动作（.vae fix 会依次执行）"), "check 列出修复动作");
            check(checkText.contains("清掉 Forge 额外模块"), "check 的动作来自 VaeGuard.repairPlan");
            check(checkText.contains("Automatic"), "check 建议设回 Automatic");
            equal(before, f.vae.vae, "check 不改 VAE");
            equal(beforeModules, f.vae.modules, "check 不改额外模块");
            check(f.vae.calls.isEmpty(), "check 不写任何 SD 设置：" + f.vae.calls);

            f.vae.modules = List.of();
            String cleanCheck = f.command(".vae check");
            check(cleanCheck.contains("冲突检测：正常（OK）"), "配置正常时 check 报 OK");
            check(cleanCheck.contains("无需修复"), "配置正常时 check 说无需修复");

            // .vae fix：清掉残留模块 + VAE 设回 Automatic，逐条回报
            f.vae.modules = List.of(QWEN_VAE, QWEN_ENCODER);
            f.vae.vae = "qwen_image_vae.safetensors";
            f.vae.preset = "xl";
            f.vae.presetPlans = new LinkedHashMap<>(Map.of("xl", List.of()));
            f.vae.calls.clear();
            String fix = f.command(".vae fix");
            check(fix.contains("VAE 修复完成，实际做了："), "fix 逐条回报：" + fix);
            check(fix.contains("已重新套用 Forge 预设「xl」"), "fix 重新套用当前预设清模块");
            check(fix.contains("残留模块已清空"), "fix 说明该预设不需要额外模块");
            check(fix.contains("已把 VAE 设回 Automatic（跟随当前模型）"), "fix 把 VAE 设回 Automatic");
            check(fix.contains("修复后：VAE=Automatic（Automatic＝跟随当前模型）"), "fix 回读 VAE");
            check(fix.contains("额外模块=无"), "fix 回读额外模块");
            check(fix.contains("冲突检测：正常（OK）"), "fix 后冲突解除");
            check(f.vae.calls.contains("setForgePreset:xl"), "fix 调用 setForgePreset：" + f.vae.calls);
            check(f.vae.calls.contains("setVae:Automatic"), "fix 调用 setVae：");
            check(f.vae.modules.isEmpty(), "fix 后额外模块真的清空了");

            // fix 换成 anima 预设：模块按该预设自己的清单装回来（残留的 SDXL VAE 被清掉）
            f.vae.checkpoint = "animaCatTower_v11-full.safetensors";
            f.vae.preset = "anima";
            f.vae.presetPlans = new LinkedHashMap<>(Map.of("anima", List.of(QWEN_VAE, QWEN_ENCODER)));
            f.vae.modules = List.of("sdxl_vae.safetensors");
            f.vae.calls.clear();
            String fixAnima = f.command(".vae fix");
            check(fixAnima.contains("已重新套用 Forge 预设「anima」"), "fix 认当前预设 anima：" + fixAnima);
            check(fixAnima.contains(QWEN_VAE), "fix 把该预设自己的模块装上");
            check(fixAnima.contains("冲突检测：正常（OK）"), "anima 栈配 anima 模块判 OK");
            check(f.vae.calls.contains("presetModules:anima"), "fix 回读该预设的模块清单");
            equal(List.of(QWEN_VAE, QWEN_ENCODER), f.vae.modules, "anima 模块已装回");

            // 回读发现模块清不掉：如实报告，让人去 Forge 页面手工清
            f.vae.checkpoint = SDXL_CHECKPOINT;
            f.vae.preset = "xl";
            f.vae.presetPlans = new LinkedHashMap<>(Map.of("xl", List.of()));
            f.vae.modules = List.of(QWEN_ENCODER);
            f.vae.presetKeepsModules = true;
            String stubborn = f.command(".vae fix");
            check(stubborn.contains("⚠ 回读发现额外模块仍有"), "清不掉时如实报告：" + stubborn);
            check(stubborn.contains(QWEN_ENCODER), "报告里点名残留模块");
            check(stubborn.contains("手工清空"), "给出人工处理办法");
            f.vae.presetKeepsModules = false;

            // 预设切不动（API 拒绝）：fix 不崩，说明失败原因，仍然尝试设 VAE
            f.vae.modules = List.of(QWEN_VAE);
            f.vae.forgePresetFails = true;
            f.vae.vae = "qwen_image_vae.safetensors";
            String failedPreset = f.command(".vae fix");
            check(failedPreset.contains("重新套用 Forge 预设「xl」失败"), "切预设失败要说明：" + failedPreset);
            check(failedPreset.contains("已把 VAE 设回 Automatic"), "切预设失败也照样设 VAE");
            f.vae.forgePresetFails = false;

            // 不是 Forge：说不清就别说，提示手工清
            f.vae.forge = false;
            f.vae.modules = List.of(QWEN_VAE);
            String notForge = f.command(".vae fix");
            check(notForge.contains("当前不是 Forge"), "非 Forge 时如实说明：" + notForge);
            f.vae.forge = true;

            // 读不到 SD 状态（SD 没跑/接口不对）：status 要如实说，不假装 OK
            f.vae.snapshotFails = true;
            String offline = f.command(".vae");
            check(offline.contains("读不到 VAE 状态"), "读不到状态时如实说明：" + offline);
            check(offline.contains("用法：.vae"), "读不到状态也给用法");
            check(f.command(".vae check").contains("检测不了"), "读不到状态时 check 如实说明");
            String offlineFix = f.command(".vae fix");
            check(offlineFix.contains("读不到 SD 的当前状态"), "读不到状态时 fix 也如实说明：" + offlineFix);
            check(offlineFix.contains("已把 VAE 设回 Automatic"), "读不到状态也照样把 VAE 设回 Automatic");
            f.vae.snapshotFails = false;

            // 权限：与 .sampler / .size 一致，普通成员（456）就能用；换私聊也一样
            check(f.command("private", ".vae").contains("VAE：Automatic"), "私聊里也能查看");
            check(f.command("private", ".vae auto").contains("VAE 已设为"), "私聊里也能设置");
            check(f.command(".vae set Automatic").contains("VAE 已设为"), "普通成员无需 owner/admin");

            // .help：只加了一行 .vae，别的指令还在
            String help = f.command(".help");
            check(help.contains(".vae"), "help 里有 .vae");
            check(help.contains(".vae [status|list|set <名字>|auto|none|check|fix]"), "help 说清 .vae 的分支：" + help.replaceAll("(?s).*(\\.vae[^\\n]*).*", "$1"));
            check(help.contains("Automatic"), "help 提到 Automatic");
            check(help.contains(".sampler set"), "help 里原有指令没被挤掉");
            check(help.contains(".model preset"), "help 里 .model preset 还在");
            check(help.contains(".style load"), "help 里 .style load 还在");

            // 静态小工具
            equal("BLOCK", Bot.vaeLevel(f.vae.snapshot()), "vaeLevel 读快照");
            equal("OK", Bot.vaeLevel(null), "vaeLevel 读不到当 OK（不误拦）");
            equal("OK", Bot.vaeLevel(new JsonObject()), "vaeLevel 缺字段当 OK");
            JsonObject lower = new JsonObject();
            JsonObject conflict = new JsonObject();
            conflict.addProperty("level", "warn");
            lower.add("conflict", conflict);
            equal("WARN", Bot.vaeLevel(lower), "vaeLevel 统一大写");
            check(Bot.vaeLevelLabel("BLOCK").contains("灰图"), "BLOCK 的中文说明提到灰图");
            check(Bot.vaeLevelLabel("WARN").contains("提醒"), "WARN 的中文说明");
            check(Bot.vaeLevelLabel("OK").contains("正常"), "OK 的中文说明");
            equal("Automatic", Bot.vaeAlias("auto"), "auto 别名");
            equal("Automatic", Bot.vaeAlias("AUTO"), "auto 别名不区分大小写");
            equal("Automatic", Bot.vaeAlias("自动"), "中文别名 自动");
            equal("None", Bot.vaeAlias("无"), "中文别名 无");
            equal("None", Bot.vaeAlias("none"), "none 别名");
            equal("my.safetensors", Bot.vaeAlias(" my.safetensors "), "普通名字原样保留");
        }
    }

    // ------------------------------------------------------------------ 出图前防呆

    private static void conflictGuard() throws Exception {
        // ① BLOCK + Automatic（残留模块造成的）→ 自动修复，并明确说一句，然后照常出图
        try (Fixture f = new Fixture()) {
            f.vae.modules = List.of(QWEN_VAE, QWEN_ENCODER);
            f.vae.vae = "Automatic";
            f.vae.preset = "xl";
            f.vae.presetPlans = new LinkedHashMap<>(Map.of("xl", List.of()));
            f.plainDefault();
            String receipt = f.generate(".gen");
            check(receipt.contains("已加入生成队列"), "入队回执：" + receipt);
            check(receipt.contains("检测到 VAE/额外模块与当前模型冲突，已自动修复"), "出图前自动修复并明确告知：" + receipt);
            check(receipt.contains("已重新套用 Forge 预设「xl」"), "自动修复逐条说明做了什么");
            check(receipt.contains("本次生成按修复后的配置继续"), "说明本次生成继续");
            check(receipt.contains("任务 #1 已完成"), "生成正常结算：" + receipt);
            equal(1, f.generations.size(), "自动修复后照常出图（1 次请求）");
            check(f.vae.modules.isEmpty(), "自动修复真的清掉了残留模块");
            equal("Automatic", f.vae.vae, "自动修复后 VAE 仍是 Automatic");
            check(f.vae.calls.contains("setForgePreset:xl"), "自动修复调用了 setForgePreset");
            check(Bot.vaeLevel(f.vae.vaeSnapshot()).equals("OK"), "修复后冲突解除");
        }

        // ② BLOCK + 用户显式指定了冲突的 VAE → 拒绝这次生成，不出图
        try (Fixture f = new Fixture()) {
            f.vae.vae = QWEN_VAE;                       // 用户自己选的 Qwen VAE，配 SDXL 底模
            f.vae.modules = List.of(QWEN_ENCODER);
            f.plainDefault();
            String receipt = f.generate(".gen", "已拒绝");
            check(receipt.contains("任务 #1 已拒绝"), "整条任务被拒绝：" + receipt);
            check(receipt.contains("这次生成被拒绝"), "拒绝理由说清楚：" + receipt);
            check(receipt.contains(QWEN_VAE), "拒绝理由点名冲突的 VAE");
            check(receipt.contains(".vae auto") && receipt.contains(".vae fix"), "拒绝理由给出改法");
            check(receipt.contains(".vae check"), "拒绝理由建议先确认");
            check(receipt.contains("图片生成成功 0 次"), "被拒绝时如实报 0 次成功：" + receipt);
            equal(0, f.generations.size(), "被拒绝时一次 SD 请求都不发（绝不默默出灰图）");
            equal(QWEN_VAE, f.vae.vae, "拒绝不改用户显式选的 VAE");
            check(!f.vae.calls.contains("setVae:Automatic"), "拒绝时不动手改配置");
            check(!receipt.contains("灰图，已自动修复"), "拒绝路径不会假装修复过");
        }

        // ③ WARN：只提醒，不拦，照常出图
        try (Fixture f = new Fixture()) {
            f.vae.modules = List.of("mystery_module.safetensors");   // 认不出归属 → WARN
            f.plainDefault();
            String receipt = f.generate(".gen");
            check(Bot.vaeLevel(f.vae.vaeSnapshot()).equals("WARN"), "认不出归属判成 WARN");
            check(receipt.contains("⚠ VAE 提醒（不影响本次生成）"), "WARN 只提醒一句：" + receipt);
            check(receipt.contains(".vae check"), "WARN 提示怎么确认");
            check(receipt.contains("任务 #1 已完成"), "WARN 不拦生成");
            equal(1, f.generations.size(), "WARN 时照常出图");
        }

        // ④ 出图前只检查一次：.gen 3 只查一次快照、只提醒一次
        try (Fixture f = new Fixture()) {
            f.vae.modules = List.of("mystery_module.safetensors");
            f.plainDefault();
            String receipt = f.generate(".gen 3", "已完成");
            check(receipt.contains("任务 #1 已完成"), "3 次生成正常结算：" + receipt);
            equal(3, f.generations.size(), ".gen 3 出 3 张");
            equal(1, countOf(receipt, "⚠ VAE 提醒（不影响本次生成）"), "一个任务的 3 张只提醒一次");
            equal(1, f.vae.snapshotCalls(), "一个任务只查一次冲突快照");
        }
    }

    // ------------------------------------------------------------------ 出图后灰图自检

    private static void graySelfCheck() throws Exception {
        // ① 灰图 → 自动修复 → 重试成功（只重试一次）
        try (Fixture f = new Fixture()) {
            f.vae.modules = List.of("sdxl_vae.safetensors");   // 与 SDXL 同族：冲突检测是 OK（证明这道防线独立生效）
            f.vae.preset = "xl";
            f.vae.presetPlans = new LinkedHashMap<>(Map.of("xl", List.of()));
            check(Bot.vaeLevel(f.vae.vaeSnapshot()).equals("OK"), "灰图用例里冲突检测是 OK（防线独立生效）");
            f.images.add(f.grayPng());
            f.images.add(f.plainPng());
            f.vae.calls.clear();
            String receipt = f.generate(".gen", "已完成");
            check(receipt.contains("检测到灰图，已自动修复 VAE/额外模块并重试成功"), "灰图自检 + 补救回执：" + receipt);
            check(receipt.contains("已重新套用 Forge 预设「xl」"), "补救动作进回执");
            check(receipt.contains("任务 #1 已完成"), "补救后正常结算");
            check(receipt.contains("共 1 张"), "只交付重试成功的那张：" + receipt);
            equal(2, f.generations.size(), "灰图只重试一次（共 2 次请求）");
            check(!receipt.contains("仍"), "重试成功后不说还有废图");
            check(f.vae.calls.contains("setForgePreset:xl"), "灰图补救真的动了配置：" + f.vae.calls);
            check(f.vae.modules.isEmpty(), "灰图补救清掉了可疑的额外模块");
            List<Path> pending = f.client.pendingImages();
            equal(1, pending.size(), "待领取列表里只剩正常图：共 " + pending.size() + " 张");
            check(!Bot.blankImage(pending.get(0)), "待领取的那张不是灰图");
        }

        // ② 重试仍是灰图 → 如实报失败 + .vae check 建议，且不再重试第三次
        try (Fixture f = new Fixture()) {
            f.vae.modules = List.of("sdxl_vae.safetensors");
            f.vae.preset = "xl";
            f.vae.presetPlans = new LinkedHashMap<>(Map.of("xl", List.of()));
            f.images.add(f.grayPng());
            f.images.add(f.grayPng());
            String receipt = f.generate(".gen", "已完成");
            check(receipt.contains("检测到灰图，已自动修复 VAE/额外模块，但重试仍有 1 张是纯色废图，已丢弃"), "补救失败如实报：" + receipt);
            check(receipt.contains("请用 .vae check 查看冲突"), "补救失败附 .vae check 建议");
            check(receipt.contains("生成失败 1 次"), "补救失败计入失败次数");
            check(receipt.contains("共 0 张"), "废图不交付");
            equal(2, f.generations.size(), "只重试一次，绝不死循环");
            equal(0, f.client.pendingImages().size(), "废图不进待领取列表（.get 也领不到）");
        }

        // ③ 反例：大面积同色背景但正常的图不能被判灰（不修、不重试、不报警）
        try (Fixture f = new Fixture()) {
            f.plainDefault();
            String receipt = f.generate(".gen", "已完成");
            check(!receipt.contains("检测到灰图"), "正常素图不触发灰图补救：" + receipt);
            check(receipt.contains("任务 #1 已完成") && receipt.contains("共 1 张"), "正常素图照常交付");
            equal(1, f.generations.size(), "正常素图不重试");
            equal(1, f.client.pendingImages().size(), "正常素图进待领取列表");
            // 同一张图直接在文件层面再确认一次
            check(!Bot.blankImage(f.client.pendingImages().get(0)), "被交付的那张图不是废图");
        }

        // ④ 一批两张都是灰图：整批丢，只重试一次
        try (Fixture f = new Fixture()) {
            f.imagesPerRequest = 2;
            f.images.add(f.grayPng());
            f.images.add(f.grayPng());
            f.images.add(f.plainPng());
            f.images.add(f.plainPng());
            String receipt = f.generate(".gen", "已完成");
            check(receipt.contains("检测到灰图，已自动修复 VAE/额外模块并重试成功"), "两灰两好：整批重试成功：" + receipt);
            check(receipt.contains("共 2 张"), "交付重试的两张");
            equal(2, f.generations.size(), "两灰两好：整批只重试一次");
            equal(2, f.client.pendingImages().size(), "待领取列表只有正常的两张");
            check(f.client.pendingImages().stream().noneMatch(Bot::blankImage), "待领取的两张都不是废图");
        }
    }

    private static int countOf(String text, String fragment) {
        int count = 0, from = 0;
        while (true) {
            int at = text.indexOf(fragment, from);
            if (at < 0) return count;
            count++; from = at + fragment.length();
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    // ------------------------------------------------------------------ 夹具

    private record Reply(JsonObject event, JsonArray segments) {
        String text() { return Bot.messageText(segments); }
    }

    /**
     * 假 VAE 桩：状态用内存变量模拟，冲突判定仍然调用真的 {@link VaeGuard}——
     * 所以"SDXL 底模挂 qwen 模块"这条事故组合在测试里是真的 BLOCK。
     */
    private static final class FakeVae implements Bot.VaeSupport {
        volatile String vae = "Automatic";
        volatile String checkpoint = SDXL_CHECKPOINT;
        volatile List<String> modules = List.of();
        volatile String preset = "xl";
        volatile Map<String, List<String>> presetPlans = new LinkedHashMap<>(Map.of("xl", List.of()));
        volatile boolean forge = true;
        volatile boolean snapshotFails;
        volatile boolean forgePresetFails;
        volatile boolean presetKeepsModules;
        final Set<String> available = new LinkedHashSet<>(List.of("Automatic", "None",
                "vae-ft-mse-840000-ema-pruned.safetensors", QWEN_VAE,
                "sdxl_vae.safetensors", "flux_ae.safetensors", "sd3_vae.safetensors",
                "hunyuan_vae.safetensors", "kolors_vae.safetensors", "wan_vae.safetensors"));
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger snapshots = new AtomicInteger();

        public boolean forge() { return forge; }
        public String forgePreset() { return preset; }
        public String vae() { return vae; }
        public List<String> vaeList() { return List.copyOf(available); }

        public List<String> presetModules(String preset) {
            calls.add("presetModules:" + preset);
            return List.copyOf(presetPlans.getOrDefault(preset, List.of()));
        }

        public JsonObject vaeSnapshot() {
            snapshots.incrementAndGet();
            if (snapshotFails) throw new IllegalStateException("SD WebUI 连不上");
            return snapshot();
        }

        JsonObject snapshot() {
            JsonObject result = new JsonObject();
            result.addProperty("vae", vae);
            result.addProperty("vaeAuto", VaeGuard.automaticVae(vae));
            result.addProperty("checkpoint", checkpoint);
            JsonArray moduleArray = new JsonArray();
            for (String module : modules) moduleArray.add(module);
            result.add("modules", moduleArray);
            VaeGuard.Conflict conflict = VaeGuard.check(new VaeGuard.Facts(checkpoint, vae, modules));
            JsonObject level = new JsonObject();
            level.addProperty("level", conflict.severity().name());
            level.addProperty("reason", conflict.reason());
            level.addProperty("suggestion", conflict.suggestion());
            JsonArray culprits = new JsonArray();
            for (String culprit : conflict.culprits()) culprits.add(culprit);
            level.add("culprits", culprits);
            result.add("conflict", level);
            JsonArray choices = new JsonArray();
            for (String name : available) choices.add(name);
            result.add("choices", choices);
            return result;
        }

        public SdClient.GenerationSettings setVae(String name) {
            calls.add("setVae:" + name);
            if (!available.contains(name))
                throw new IllegalArgumentException("没有这个 VAE：" + name + "；可用：" + String.join("、", available) + "。");
            vae = name;
            return null;
        }

        public JsonObject setForgePreset(String name) {
            calls.add("setForgePreset:" + name);
            if (forgePresetFails) throw new IllegalStateException("这个 Forge 构建不接受 API 切预设");
            preset = name;
            if (!presetKeepsModules) modules = List.copyOf(presetPlans.getOrDefault(name, List.of()));
            return new JsonObject();
        }

        int snapshotCalls() { return snapshots.get(); }
    }

    /** 回环假 SD（提示词桥接 + 采样方法 + txt2img 返回自己造的 PNG）＋假 VAE 桩。 */
    private static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
        final ExecutorService executor;
        final JsonObject sdConfig = new JsonObject(), state = new JsonObject();
        final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
        final List<JsonObject> generations = Collections.synchronizedList(new ArrayList<>());
        final Deque<String> images = new ConcurrentLinkedDeque<>();
        final FakeVae vae = new FakeVae();
        final SdClient client;
        final Bot bot;
        volatile String defaultImage;
        volatile int imagesPerRequest = 1;
        /** 假 SD 的 /sdapi/v1/options（真 SdClient 的 VAE 读写走这里）。 */
        final JsonObject optionValues = new JsonObject();
        final JsonArray modulesCatalog = new JsonArray();
        final AtomicInteger optionPosts = new AtomicInteger();
        volatile boolean optionsAvailable;

        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "vae-command-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            state.addProperty("positive", "vae initial +");
            state.addProperty("negative", "vae initial -");
            state.addProperty("sampler_name", "Euler a");
            state.add("styles", new JsonArray());
            state.addProperty("width", 128); state.addProperty("height", 128);
            state.addProperty("revision", 1);
            state.addProperty("source", "webui-live");
            state.addProperty("settings_initialized", true); state.addProperty("live", true);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "vae-command-mock"); thread.setDaemon(true); return thread;
            });
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            sdConfig.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sdConfig.addProperty("timeout_seconds", 5);
            JsonObject config = new JsonObject();
            config.add("sd", sdConfig.deepCopy());
            JsonArray admins = new JsonArray(); admins.add(123);
            config.add("admin_user_ids", admins);
            config.addProperty("gen_auto_get", false);
            Json.atomicWrite(root.resolve("config.json"), config);
            client = new SdClient(root, sdConfig);
            bot = new Bot(new Settings(root), client, (event, segments) -> {
                replies.add(new Reply(event.deepCopy(), segments.deepCopy()));
                return CompletableFuture.completedFuture(null);
            });
            bot.useVaeSupport(vae);
            // 个人提示词：.gen 需要非空提示词
            client.change(false, "edit", "vae captured +");
            client.change(true, "edit", "vae captured -");
        }

        /** 默认出图给"大面积同色背景但正常"的图。 */
        void plainDefault() throws Exception { defaultImage = plainPng(); }

        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/pixiko-bridge/v1/prompts")) {
                    synchronized (state) {
                        if (exchange.getRequestMethod().equals("PUT")) {
                            JsonObject body = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                            if (body.get("expected_revision").getAsInt() != state.get("revision").getAsInt()) {
                                send(exchange, 409, state.toString()); return;
                            }
                            for (String key : List.of("positive", "negative", "sampler_name", "styles", "width", "height"))
                                if (body.has(key)) state.add(key, body.get(key).deepCopy());
                            state.addProperty("revision", state.get("revision").getAsInt() + 1);
                        }
                        send(exchange, 200, state.toString());
                    }
                } else if (path.equals("/sdapi/v1/samplers")) {
                    JsonArray values = new JsonArray();
                    JsonObject euler = new JsonObject(); euler.addProperty("name", "Euler a"); values.add(euler);
                    send(exchange, 200, values.toString());
                } else if (path.equals("/sdapi/v1/options")) {
                    if (!optionsAvailable) { send(exchange, 404, "{}"); return; }
                    synchronized (optionValues) {
                        if (exchange.getRequestMethod().equals("POST")) {
                            optionPosts.incrementAndGet();
                            JsonObject body = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                            for (Map.Entry<String, JsonElement> entry : body.entrySet())
                                optionValues.add(entry.getKey(), entry.getValue().deepCopy());
                        }
                        send(exchange, 200, optionValues.toString());
                    }
                } else if (path.equals("/sdapi/v1/sd-modules")) {
                    send(exchange, 200, modulesCatalog.toString());
                } else if (path.equals("/sdapi/v1/txt2img")) {
                    generations.add(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    JsonArray returned = new JsonArray();
                    for (int index = 0; index < imagesPerRequest; index++) {
                        String image = images.poll();
                        if (image == null) image = defaultImage;
                        returned.add(image);
                    }
                    send(exchange, 200, "{\"images\":" + returned + "}");
                } else {
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

        // ---------------------------------------------------------- 造图

        interface Painter { int rgb(int x, int y); }

        Path writePng(String name, int width, int height, Painter painter) throws Exception {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) image.setRGB(x, y, painter.rgb(x, y));
            Path file = root.resolve(name);
            ImageIO.write(image, "png", file.toFile());
            return file;
        }

        /** 事故图：纯灰，1 种颜色。 */
        String grayPng() throws Exception { return base64(writePng("gray-gen.png", 128, 128, (x, y) -> 0x7F7F7F)); }

        /** 大面积同色背景（约 85%）＋中间一条彩色带：正常但很素，绝不能被判灰。 */
        Path plainImage(String name) throws Exception {
            return writePng(name, 128, 128, (x, y) -> {
                if (y >= 60 && y < 80) return (x * 3 % 256) << 16 | ((x * 7) % 256) << 8 | (y * 5 % 256);
                return 0xE6F0FA;
            });
        }

        String plainPng() throws Exception { return base64(plainImage("plain-gen.png")); }

        private static String base64(Path file) throws IOException {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        }

        // ---------------------------------------------------------- 指令

        String command(String type, String command) throws Exception {
            bot.accept(event(type, command));
            return take().text();
        }

        String command(String command) throws Exception { return command("group", command); }

        /** 发一条 .gen 并收集回执，直到出现 stop 片段（默认 = 结算回执）。 */
        String generate(String command) throws Exception { return generate(command, "已完成"); }

        String generate(String command, String stop) throws Exception {
            bot.accept(event("group", command));
            StringBuilder all = new StringBuilder(take().text());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                Reply reply = replies.poll(300, TimeUnit.MILLISECONDS);
                if (reply == null) continue;
                all.append('\n').append(reply.text());
                if (reply.text().contains(stop)) return all.toString();
            }
            throw new AssertionError("等不到生成结算回执（" + stop + "），已收到：\n" + all);
        }

        Reply take() throws Exception {
            Reply reply = replies.poll(10, TimeUnit.SECONDS);
            if (reply == null) throw new AssertionError("等不到回执");
            return reply;
        }

        static JsonObject event(String type, String command) {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", type);
            event.addProperty("user_id", 456);
            event.addProperty("self_id", 777);
            event.addProperty("message_id", IDS.incrementAndGet());
            if (type.equals("group")) event.addProperty("group_id", 999);
            event.add("message", Maps.text(command));
            return event;
        }

        public void close() {
            bot.close();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}

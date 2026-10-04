package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.VaeGuard;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VAE 能力 + 冲突防呆的测试（扁平目录，包 {@code cn.szu.bot}）。
 *
 * <p>两部分：
 * <ol>
 *   <li><b>{@link VaeGuard} 的判定表</b>：纯函数、离线，重点覆盖 2026-10 的灰图事故
 *       （SDXL 检查点 + anima 残留的 {@code qwen_image_vae} / {@code qwen_3_06b_base} → BLOCK，
 *       culprits 给出要清的那两个模块，repairPlan 给出清空方案）；</li>
 *   <li><b>{@link SdClient} 的 VAE 读写</b>：{@code vae()/setVae()/vaeList()/presetModules()/vaeSnapshot()}
 *       与"切预设连 {@code forge_additional_modules} 一起切"（含调用顺序断言），
 *       全部打在 {@link HttpServer} 假 SD 桩上，**不碰真机**。</li>
 * </ol>
 */
public final class VaeGuardTest {
    private static int assertions;

    /** 事故现场那两个模块（线上 options 里的原样值，是完整路径）。 */
    private static final String QWEN_VAE = "F:\\sd\\sd-webui-forge-neo\\models\\VAE\\qwen_image_vae.safetensors";
    private static final String QWEN_TE = "F:\\sd\\sd-webui-forge-neo\\models\\text_encoder\\qwen_3_06b_base.safetensors";
    private static final String SDXL = "waiIllustriousSDXL_v170.safetensors";

    public static void main(String[] args) throws Exception {
        accidentReplay();
        misreportFromConflictingReadings();
        consistentReadingsStillBlock();
        sameFamilyAndAutomatic();
        fluxAndOtherFamilies();
        vagueNames();
        familyTables();
        repairPlans();
        vaeClientReads();
        vaeClientWrites();
        presetSwitchCarriesModules();
        presetSwitchFallbackOrder();
        presetWithItsOwnBadModules();
        snapshotAndHealth();
        snapshotCrossChecks();
        styleParamsCarryVae();
        System.out.println("VaeGuardTest: " + assertions + " assertions passed.");
    }

    // ---------------------------------------------------------------- ① VaeGuard 判定表

    /**
     * **误报复现（2026-10 用户实测）**：用户在 Forge 里用的是 anima，但 {@code options} 的
     * {@code sd_model_checkpoint} 还是上一次的 SDXL（切换期间/经 UI 切换后有一段时间是旧值），
     * 而他按"当前模型是 anima"把 sd_vae 设成了 {@code qwen_image_vae} —— 这是**对的**配置，
     * 绝不能再判 BLOCK。
     */
    private static void misreportFromConflictingReadings() {
        VaeGuard.Facts clash = new VaeGuard.Facts(SDXL, "qwen_image_vae.safetensors", List.of(),
                List.of(new VaeGuard.Reading("options", SDXL),
                        new VaeGuard.Reading("Forge 预设 anima", "animaCatTower_v11.safetensors")));
        VaeGuard.Conflict conflict = VaeGuard.check(clash);
        equal(VaeGuard.Severity.WARN, conflict.severity(), "读数矛盾只能 WARN，不许 BLOCK");
        check(!conflict.conflict() && conflict.warned() && !conflict.blocked(), "WARN 不算冲突");
        equal("warn", conflict.level(), "level=warn");
        equal(List.of(), conflict.culprits(), "读数矛盾时不给 culprits（不许被自动清掉）");
        check(conflict.reason().contains("检查点读数不一致"), "原因必须点明读数不一致：" + conflict.reason());
        check(conflict.reason().contains("options=" + SDXL), "原因里写出 options 读到什么：" + conflict.reason());
        check(conflict.reason().contains("Forge 预设 anima=animaCatTower_v11.safetensors"),
                "原因里写出预设读到什么：" + conflict.reason());
        check(conflict.reason().contains("无法确认是否真冲突，请以 Forge 页面为准"), "原因给出结论口径：" + conflict.reason());
        check(!conflict.reason().contains("出灰图"), "读数矛盾时不许出现「会出灰图」这种断言：" + conflict.reason());
        check(!conflict.suggestion().contains("出灰图"), "建议也一样不许吓人：" + conflict.suggestion());
        check(conflict.suggestion().contains("Forge 页面"), "建议让用户去 Forge 页面确认：" + conflict.suggestion());
        check(conflict.reason().contains("确实对不上"), "把「若哪个读数是真的则会怎样」讲清楚，让用户自己判断：" + conflict.reason());
        check(VaeGuard.inconsistent(clash), "inconsistent() 能独立判出来（网页据此提示）");
        List<String> plan = VaeGuard.repairPlan(clash);
        check(plan.stream().anyMatch(line -> line.contains("先到 Forge 页面确认")), "修复方案先让用户确认，不自动改：" + plan);
        check(plan.stream().noneMatch(line -> line.contains("把 sd_vae 设回 Automatic")),
                "读数矛盾时不许建议直接改 VAE：" + plan);
        check(plan.stream().noneMatch(line -> line.contains("清掉 Forge 额外模块")),
                "读数矛盾时不许建议清模块：" + plan);

        // 三源：options 与"已加载哈希"都说 SDXL、只有预设说是 anima —— 依然只 WARN，但依据更全。
        VaeGuard.Conflict withHash = VaeGuard.check(new VaeGuard.Facts(SDXL, "qwen_image_vae.safetensors", List.of(),
                List.of(new VaeGuard.Reading("options", SDXL),
                        new VaeGuard.Reading("已加载哈希", SDXL),
                        new VaeGuard.Reading("Forge 预设 anima", "animaCatTower_v11.safetensors"))));
        equal(VaeGuard.Severity.WARN, withHash.severity(), "三源里只要有一个不同族就只 WARN");
        check(withHash.reason().contains("已加载哈希=" + SDXL), "原因列出哈希反查到的读数：" + withHash.reason());
        equal(List.of(), withHash.culprits(), "依旧不给 culprits");

        // 机器人自己记的（可能陈旧）也算一个来源：读了但和 options 不一致 → WARN。
        VaeGuard.Conflict remembered = VaeGuard.check(new VaeGuard.Facts(SDXL, "qwen_image_vae.safetensors", List.of(),
                List.of(new VaeGuard.Reading("options", SDXL),
                        new VaeGuard.Reading("Forge 预设 xl", SDXL),
                        new VaeGuard.Reading("机器人记录", "animaCatTower_v11.safetensors [2d0343cd69]"))));
        equal(VaeGuard.Severity.WARN, remembered.severity(), "机器人记录与 options 矛盾时也不 BLOCK");
        check(remembered.reason().contains("机器人记录="), "原因里带上机器人记录：" + remembered.reason());

        // 同值不同来源（带不带 [哈希] 后缀）不算矛盾，去重之后照常判。
        VaeGuard.Conflict dedup = VaeGuard.check(new VaeGuard.Facts(SDXL, "qwen_image_vae.safetensors", List.of(),
                List.of(new VaeGuard.Reading("options", SDXL),
                        new VaeGuard.Reading("机器人记录", SDXL + " [f116b0c78f]"))));
        equal(VaeGuard.Severity.BLOCK, dedup.severity(), "同一个检查点的两种写法不是矛盾，仍然 BLOCK");
        check(dedup.reason().contains("读数："), "BLOCK 也要写清依据：" + dedup.reason());

        // 空读数（例如 forge_checkpoint_sd 是空）不算一个来源，不构成矛盾。
        VaeGuard.Conflict blankReading = VaeGuard.check(new VaeGuard.Facts(SDXL, "qwen_image_vae.safetensors", List.of(),
                List.of(new VaeGuard.Reading("Forge 预设 sd", ""), new VaeGuard.Reading("options", SDXL))));
        equal(VaeGuard.Severity.BLOCK, blankReading.severity(), "空读数被忽略，只剩一个自洽来源 → 仍然 BLOCK");

        // 认不出的读数（用户的自定义检查点）不构成矛盾：不能因为"认不出"就把真冲突降级。
        VaeGuard.Conflict unknownReading = VaeGuard.check(new VaeGuard.Facts(SDXL, "qwen_image_vae.safetensors", List.of(),
                List.of(new VaeGuard.Reading("options", SDXL),
                        new VaeGuard.Reading("Forge 预设 anima", "完全没见过的模型.safetensors"))));
        equal(VaeGuard.Severity.BLOCK, unknownReading.severity(), "认不出的读数不参与矛盾判定（只有一个已知族）");
    }

    /** 两个来源**一致**地说是 SDXL（或一致地说是 anima）：自洽，照常判 BLOCK / OK。 */
    private static void consistentReadingsStillBlock() {
        // 真冲突：options 与预设一致都说 SDXL，而 sd_vae 是 qwen 的 → 仍然 BLOCK（别把真问题放过）。
        VaeGuard.Conflict block = VaeGuard.check(new VaeGuard.Facts(SDXL, "qwen_image_vae.safetensors", List.of(),
                List.of(new VaeGuard.Reading("options", SDXL), new VaeGuard.Reading("Forge 预设 xl", SDXL))));
        equal(VaeGuard.Severity.BLOCK, block.severity(), "两源一致的 SDXL + qwen 的 VAE 仍然 BLOCK");
        check(block.conflict() && block.blocked(), "仍然是确证冲突");
        equal(List.of("qwen_image_vae.safetensors"), block.culprits(), "culprits 是那个 VAE");
        check(block.reason().contains("读数：options=" + SDXL), "BLOCK 原因写清依据（哪个来源）：" + block.reason());
        check(block.reason().contains("Forge 预设 xl=" + SDXL), "BLOCK 原因写清依据（另一来源）：" + block.reason());
        check(!block.reason().contains("读数不一致"), "自洽的读数不能说成不一致");
        check(block.suggestion().contains("可能出灰图"), "BLOCK 的建议是「可能出灰图」的量级：" + block.suggestion());
        check(VaeGuard.repairPlan(new VaeGuard.Facts(SDXL, "qwen_image_vae.safetensors", List.of(),
                        List.of(new VaeGuard.Reading("options", SDXL), new VaeGuard.Reading("Forge 预设 xl", SDXL))))
                .get(0).contains("把 sd_vae 设回 Automatic"), "自洽冲突照旧给修复动作");

        // 真冲突（额外模块残留）+ 两源一致：culprits 是两个模块。
        VaeGuard.Conflict modules = VaeGuard.check(new VaeGuard.Facts(SDXL, "Automatic", List.of(QWEN_VAE, QWEN_TE),
                List.of(new VaeGuard.Reading("options", SDXL), new VaeGuard.Reading("Forge 预设 xl", SDXL))));
        equal(VaeGuard.Severity.BLOCK, modules.severity(), "两源一致的 SDXL + 残留 qwen 模块仍然 BLOCK");
        equal(2, modules.culprits().size(), "culprits 是两个模块");
        check(modules.reason().contains("读数："), "残留模块的 BLOCK 也写清依据");

        // 自洽地说是 anima + qwen 的 VAE/模块 → OK（用户那次真正该看到的结论）。
        VaeGuard.Facts anima = new VaeGuard.Facts("animaCatTower_v11.safetensors", "qwen_image_vae.safetensors",
                List.of(QWEN_VAE, QWEN_TE),
                List.of(new VaeGuard.Reading("options", "animaCatTower_v11.safetensors"),
                        new VaeGuard.Reading("Forge 预设 anima", "animaCatTower_v11.safetensors"),
                        new VaeGuard.Reading("已加载哈希", "animaCatTower_v11.safetensors")));
        VaeGuard.Conflict ok = VaeGuard.check(anima);
        equal(VaeGuard.Severity.OK, ok.severity(), "三源一致说是 anima + qwen 的配置 → OK");
        check(!VaeGuard.inconsistent(anima), "自洽的读数");
        equal(List.of(), ok.culprits(), "OK 没有 culprits");
        equal("ok", ok.level(), "level=ok");
        equal(List.of("无需修复：sd_vae 与 Forge 额外模块都和检查点同族。"), VaeGuard.repairPlan(anima),
                "OK 的修复方案只有一句");
    }

    /** 事故复现：SDXL 检查点 + anima 残留的 qwen VAE／文本编码器（sd_vae 还是 Automatic）。 */
    private static void accidentReplay() {
        VaeGuard.Facts facts = new VaeGuard.Facts(SDXL, "Automatic", List.of(QWEN_VAE, QWEN_TE));
        VaeGuard.Conflict conflict = VaeGuard.check(facts);
        check(conflict.conflict(), "确证错配 → conflict=true");
        equal(VaeGuard.Severity.BLOCK, conflict.severity(), "事故组合必须 BLOCK（照这样出图就是灰图）");
        equal("block", conflict.level(), "level 给网页用（小写 block）");
        equal("冲突", conflict.label(), "中文说法");
        check(conflict.blocked() && !conflict.warned() && !conflict.clean(), "blocked/warned/clean 三个判定互斥");
        equal(2, conflict.culprits().size(), "两个残留模块都是 culprits");
        check(conflict.culprits().contains(QWEN_VAE) && conflict.culprits().contains(QWEN_TE),
                "culprits 原样给出要清的字符串：" + conflict.culprits());
        check(conflict.reason().contains("qwen_image_vae.safetensors"), "原因里点名 VAE：" + conflict.reason());
        check(conflict.reason().contains("qwen_3_06b_base.safetensors"), "原因里点名文本编码器：" + conflict.reason());
        check(conflict.reason().contains("文本编码器"), "原因里说清那是文本编码器");
        check(conflict.reason().contains("SDXL"), "原因里说清检查点是 SDXL 族：" + conflict.reason());
        check(conflict.suggestion().contains("Automatic"), "建议把 sd_vae 设回 Automatic");
        check(conflict.suggestion().contains("forge_additional_modules"), "建议里点明要清的是 forge_additional_modules");

        List<String> plan = VaeGuard.repairPlan(facts);
        check(plan.size() >= 2, "修复方案至少两步：" + plan);
        check(plan.get(0).contains("清掉 Forge 额外模块"), "第一步是清额外模块：" + plan.get(0));
        check(plan.get(0).contains("qwen_image_vae.safetensors") && plan.get(0).contains("qwen_3_06b_base.safetensors"),
                "方案点名那两个模块：" + plan.get(0));
        check(!plan.get(0).contains("F:\\"), "方案里给人看的是文件名，不是完整路径：" + plan.get(0));
        check(plan.stream().anyMatch(line -> line.contains(".model preset")), "方案告诉用户换栈要切预设：" + plan);
        check(plan.stream().noneMatch(line -> line.contains("把 sd_vae 设回 Automatic")),
                "sd_vae 本来就是 Automatic，不该多给一条改 VAE 的步骤：" + plan);

        // 与 vaeSnapshot 的 modules 同形态（去目录的文件名）时判定必须一样。
        VaeGuard.Conflict bare = VaeGuard.check(new VaeGuard.Facts(SDXL, "Automatic",
                List.of("qwen_image_vae.safetensors", "qwen_3_06b_base.safetensors")));
        equal(VaeGuard.Severity.BLOCK, bare.severity(), "文件名形态也一样 BLOCK");
        equal(List.of("qwen_image_vae.safetensors", "qwen_3_06b_base.safetensors"), bare.culprits(),
                "culprits 跟着输入形态走");

        // 只残留 VAE / 只残留文本编码器：两个都得单独判出来。
        VaeGuard.Conflict vaeOnly = VaeGuard.check(new VaeGuard.Facts(SDXL, "Automatic", List.of(QWEN_VAE)));
        equal(VaeGuard.Severity.BLOCK, vaeOnly.severity(), "只剩 VAE 残留也是 BLOCK");
        equal(List.of(QWEN_VAE), vaeOnly.culprits(), "culprits 只有那一个 VAE");
        VaeGuard.Conflict teOnly = VaeGuard.check(new VaeGuard.Facts(SDXL, "Automatic", List.of(QWEN_TE)));
        equal(VaeGuard.Severity.BLOCK, teOnly.severity(), "只剩文本编码器残留也是 BLOCK");
        check(teOnly.reason().contains("文本编码器"), "只剩文本编码器时原因也说得清");

        // 同一个错配的另一种形态：sd_vae 本身被写成了 qwen 的 VAE。
        VaeGuard.Conflict explicit = VaeGuard.check(new VaeGuard.Facts(SDXL, QWEN_VAE, List.of()));
        equal(VaeGuard.Severity.BLOCK, explicit.severity(), "sd_vae 手动指到别族的 VAE → BLOCK");
        equal(List.of(QWEN_VAE), explicit.culprits(), "culprits 是那个 VAE");
        check(explicit.reason().contains("sd_vae"), "原因说得清是 sd_vae：" + explicit.reason());
        check(VaeGuard.repairPlan(new VaeGuard.Facts(SDXL, QWEN_VAE, List.of())).get(0).contains("把 sd_vae 设回 Automatic"),
                "方案第一步是把 sd_vae 设回 Automatic");

        // sd_vae 与额外模块同时错：两条修复步骤都要有。
        List<String> both = VaeGuard.repairPlan(new VaeGuard.Facts(SDXL, QWEN_VAE, List.of(QWEN_TE)));
        check(both.stream().anyMatch(line -> line.contains("sd_vae")), "两条一起错时给出 sd_vae 步骤：" + both);
        check(both.stream().anyMatch(line -> line.contains("清掉 Forge 额外模块")), "两条一起错时给出清模块步骤：" + both);
        equal(2, VaeGuard.check(new VaeGuard.Facts(SDXL, QWEN_VAE, List.of(QWEN_TE))).culprits().size(),
                "两条一起错时 culprits 有两个");
    }

    /** 同族 / Automatic / None：这些必须是 OK，不能被误拦。 */
    private static void sameFamilyAndAutomatic() {
        VaeGuard.Conflict ok = VaeGuard.check(new VaeGuard.Facts(SDXL, "sdxl_vae.safetensors", List.of()));
        equal(VaeGuard.Severity.OK, ok.severity(), "SDXL 检查点 + SDXL VAE → OK");
        equal("ok", ok.level(), "level=ok");
        check(!ok.conflict() && ok.clean() && !ok.blocked(), "OK 时三个判定一致");
        equal(List.of(), ok.culprits(), "OK 没有 culprits");
        equal("", ok.suggestion(), "OK 不给建议");
        check(ok.reason().contains("同族"), "OK 的原因说得清：" + ok.reason());
        equal(List.of("无需修复：sd_vae 与 Forge 额外模块都和检查点同族。"),
                VaeGuard.repairPlan(new VaeGuard.Facts(SDXL, "sdxl_vae.safetensors", List.of())), "OK 的修复方案只有一句");

        for (String auto : List.of("", "Automatic", "automatic", "auto", "None", "none", "自动", "无"))
            equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts(SDXL, auto, List.of())).severity(),
                    "「" + auto + "」交给 WebUI 自己挑 → OK");
        check(VaeGuard.automaticVae("Automatic") && VaeGuard.automaticVae("None") && VaeGuard.automaticVae(""),
                "automaticVae 认这几种写法");
        check(!VaeGuard.automaticVae("sdxl_vae.safetensors") && !VaeGuard.automaticVae("vae-ft-mse-840000-ema-pruned.safetensors"),
                "具体文件名不算 Automatic");

        // Illustrious / NoobAI / Pony 都是 SDXL 架构：配 SDXL 的 VAE 必须 OK。
        for (String xl : List.of("waiIllustriousSDXL_v170.safetensors", "noobaiXLNaonya_v22.safetensors",
                "ponyDiffusionV6XL_v6StartWithThisOne.safetensors", "animagineXL40_v4Opt.safetensors"))
            equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts(xl, "sdxl_vae.safetensors", List.of())).severity(),
                    xl + " 属于 SDXL 族，配 SDXL 的 VAE 是 OK");
        equal("sdxl", VaeGuard.groupOfCheckpoint(new VaeGuard.Facts("waiIllustriousSDXL_v170.safetensors", "", List.of())),
                "Illustrious 归 SDXL 族");

        // SD1.5 自成一族：vae-ft-mse / animevae 都是它的 VAE。
        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts("sd15_anime_v3.safetensors",
                "vae-ft-mse-840000-ema-pruned.safetensors", List.of())).severity(), "SD1.5 检查点 + SD1.5 的 VAE → OK");
        // Forge 里像 Counterfeit 这种文件名判不出族的：调用方把预设归属（sd 栈）一起给进来。
        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts("Counterfeit-V3.0_fp16.safetensors", "",
                "sd", "vae-ft-mse-840000-ema-pruned.safetensors", List.of())).severity(),
                "Counterfeit（Forge 预设归属 sd 栈）+ SD1.5 的 VAE → OK");
        equal(VaeGuard.Severity.BLOCK, VaeGuard.check(new VaeGuard.Facts("Counterfeit-V3.0_fp16.safetensors", "",
                "sd", "sdxl_vae.safetensors", List.of())).severity(), "SD1.5 检查点 + SDXL 的 VAE → BLOCK");
        // animevae.pt 里含 "anima" 子串，绝不能因此被当成 anima/Qwen 栈放行。
        VaeGuard.Conflict animevae = VaeGuard.check(new VaeGuard.Facts(SDXL, "animevae.pt", List.of()));
        equal(VaeGuard.Severity.BLOCK, animevae.severity(), "animevae.pt 是 SD1.5 的 VAE，配 SDXL 检查点要 BLOCK");
        equal("sd15", VaeGuard.groupOfVae("animevae.pt"), "animevae 归 SD1.5（不是 anima/Qwen）");

        // anima（Qwen-Image 架构）自带 qwen 的 VAE + 文本编码器：那是它**该有的**配置。
        VaeGuard.Conflict anima = VaeGuard.check(new VaeGuard.Facts("animaCatTower_v11.safetensors", "Automatic",
                List.of(QWEN_VAE, QWEN_TE)));
        equal(VaeGuard.Severity.OK, anima.severity(), "anima 检查点 + qwen 模块 → OK（这是 Anima 的正常配置）");
        equal("qwen", VaeGuard.groupOfCheckpoint(new VaeGuard.Facts("animaCatTower_v11.safetensors", "", List.of())),
                "anima 与 qwen 同族（Forge 预设 anima 自带的就是 qwen_image_vae + qwen_3_06b_base）");
        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts("qwen_image_2509.safetensors", "Automatic",
                List.of("qwen_image_vae.safetensors", "qwen_3_06b_base.safetensors"))).severity(), "qwen 检查点 + qwen 模块 → OK");
        equal(VaeGuard.Severity.BLOCK, VaeGuard.check(new VaeGuard.Facts("qwen_image_2509.safetensors", "Automatic",
                List.of("sdxl_vae.safetensors"))).severity(), "qwen 检查点 + SDXL 的 VAE → BLOCK");

        // 检查点自己的 VAE 放在额外模块里也应当 OK（Forge 允许这么配）。
        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts(SDXL, "Automatic",
                List.of("sdxl_vae.safetensors"))).severity(), "SDXL 检查点 + 额外模块里的 SDXL VAE → OK");
    }

    /** Flux / SD3 / 其他族的文本编码器与 VAE。 */
    private static void fluxAndOtherFamilies() {
        String flux = "flux1-dev-fp8.safetensors";
        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts(flux, "Automatic", List.of("t5xxl_fp16.safetensors"))).severity(),
                "Flux 检查点 + t5xxl → OK");
        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts(flux, "Automatic",
                List.of("F:\\sd\\models\\text_encoder\\clip_l.safetensors", "t5xxl_fp16.safetensors"))).severity(),
                "Flux 检查点 + clip_l + t5xxl → OK");
        equal(VaeGuard.Severity.BLOCK, VaeGuard.check(new VaeGuard.Facts(flux, "Automatic",
                List.of("qwen_3_06b_base.safetensors"))).severity(), "Flux 检查点 + qwen 文本编码器 → BLOCK");
        VaeGuard.Conflict fluxVae = VaeGuard.check(new VaeGuard.Facts(flux, "vae-ft-mse-840000-ema-pruned.safetensors", List.of()));
        equal(VaeGuard.Severity.BLOCK, fluxVae.severity(), "Flux 检查点 + SD1.5 的 VAE → BLOCK");
        equal(List.of("vae-ft-mse-840000-ema-pruned.safetensors"), fluxVae.culprits(), "culprits 是那个 VAE");
        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts(flux, "ae.safetensors", List.of())).severity(),
                "Flux 的 VAE 叫 ae.safetensors（名字里没有 vae）也要认出来");
        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts("flux1-krea-dev.safetensors", "ae.safetensors", List.of())).severity(),
                "Flux.1 Krea 与 Flux 同族");
        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts("chroma-unlocked-v3.safetensors", "ae.safetensors", List.of())).severity(),
                "Chroma（Flux Schnell 派生）与 Flux 同族");

        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts("sd3.5_large.safetensors", "Automatic",
                List.of("clip_g.safetensors", "t5xxl_fp16.safetensors"))).severity(), "SD3.5 + clip_g/t5xxl → OK");
        equal(VaeGuard.Severity.BLOCK, VaeGuard.check(new VaeGuard.Facts("sd3.5_large.safetensors", "Automatic",
                List.of("qwen_3_06b_base.safetensors"))).severity(), "SD3.5 + qwen 文本编码器 → BLOCK");
        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts("hunyuan_dit_v1.safetensors", "Automatic",
                List.of("llava_llama3_fp8_scaled.safetensors"))).severity(), "Hunyuan + llava 文本编码器 → OK");
        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts("wan2.2_t2v.safetensors", "Automatic",
                List.of("umt5_xxl_fp8.safetensors"))).severity(), "Wan + umt5 文本编码器 → OK");
        equal(VaeGuard.Severity.BLOCK, VaeGuard.check(new VaeGuard.Facts("kolors_v1.0.safetensors", "Automatic",
                List.of("qwen_3_06b_base.safetensors"))).severity(), "Kolors + qwen 文本编码器 → BLOCK");
    }

    /** 认不出来 → WARN（宁可不判，也不误 BLOCK；而且不许给出 culprits 让人去删）。 */
    private static void vagueNames() {
        VaeGuard.Conflict warn = VaeGuard.check(new VaeGuard.Facts(SDXL, "my_custom_final_v3.safetensors", List.of()));
        equal(VaeGuard.Severity.WARN, warn.severity(), "认不出的 VAE → WARN");
        check(!warn.conflict() && warn.warned() && !warn.blocked(), "WARN 不算冲突（不该拦住出图）");
        equal("warn", warn.level(), "level=warn");
        equal(List.of(), warn.culprits(), "WARN 不给 culprits（不确定就不许被自动删）");
        check(warn.suggestion().contains("Automatic"), "WARN 的建议是回到 Automatic：" + warn.suggestion());
        check(warn.reason().contains("my_custom_final_v3.safetensors"), "WARN 的原因点名那个 VAE");
        List<String> warnPlan = VaeGuard.repairPlan(new VaeGuard.Facts(SDXL, "my_custom_final_v3.safetensors", List.of()));
        check(warnPlan.stream().anyMatch(line -> line.contains("Automatic")), "WARN 的方案建议设成 Automatic：" + warnPlan);

        VaeGuard.Conflict unknownCp = VaeGuard.check(new VaeGuard.Facts("totally_unknown_thing.safetensors", "Automatic", List.of()));
        equal(VaeGuard.Severity.OK, unknownCp.severity(), "检查点认不出但 VAE 是 Automatic、没有额外模块 → OK（没什么可冲突的）");
        equal(VaeGuard.Severity.WARN, VaeGuard.check(new VaeGuard.Facts("totally_unknown_thing.safetensors",
                "sdxl_vae.safetensors", List.of())).severity(), "检查点认不出 + 指定了具体 VAE → WARN");
        equal(VaeGuard.Severity.WARN, VaeGuard.check(new VaeGuard.Facts("totally_unknown_thing.safetensors",
                "Automatic", List.of("qwen_3_06b_base.safetensors"))).severity(), "检查点认不出 + 有额外模块 → WARN");
        equal(VaeGuard.Severity.WARN, VaeGuard.check(new VaeGuard.Facts(SDXL, "Automatic",
                List.of("someWeirdModule.safetensors"))).severity(), "额外模块认不出 → WARN");
        equal(VaeGuard.Severity.WARN, VaeGuard.check(new VaeGuard.Facts(SDXL, "Automatic",
                List.of("model.safetensors"))).severity(), "占位名字 → WARN");
        List<String> vaguePlan = VaeGuard.repairPlan(new VaeGuard.Facts(SDXL, "Automatic",
                List.of("someWeirdModule.safetensors")));
        check(vaguePlan.stream().anyMatch(line -> line.contains("someWeirdModule.safetensors")), "WARN 方案点名那个模块：" + vaguePlan);
        equal(VaeGuard.Severity.OK, VaeGuard.check(new VaeGuard.Facts("", "", List.of())).severity(),
                "什么都没有（离线空快照）→ OK");
    }

    /** 族表与名字判定的往返（栈 → 族、VAE 名 → 族）。 */
    private static void familyTables() {
        Map<String, String> stacks = new LinkedHashMap<>();
        stacks.put("sd", VaeGuard.SD15);
        stacks.put("xl", VaeGuard.SDXL);
        stacks.put("flux", VaeGuard.FLUX);
        stacks.put("krea", VaeGuard.FLUX);
        stacks.put("chroma", VaeGuard.FLUX);
        stacks.put("anima", VaeGuard.QWEN);
        stacks.put("qwen", VaeGuard.QWEN);
        stacks.put("sd3", VaeGuard.SD3);
        stacks.put("cascade", VaeGuard.CASCADE);
        stacks.put("hunyuan", VaeGuard.HUNYUAN);
        stacks.put("wan", VaeGuard.WAN);
        stacks.put("kolors", VaeGuard.KOLORS);
        stacks.put("pixart", VaeGuard.PIXART);
        stacks.put("playground", VaeGuard.PLAYGROUND);
        stacks.put("lumina", VaeGuard.LUMINA);
        stacks.put("z-image", VaeGuard.Z_IMAGE);
        stacks.put("nitro", VaeGuard.NITRO);
        stacks.put("odor", VaeGuard.ODOR);
        stacks.put("pony-v7", VaeGuard.UNKNOWN);
        stacks.put("", VaeGuard.UNKNOWN);
        for (Map.Entry<String, String> entry : stacks.entrySet())
            equal(entry.getValue(), VaeGuard.groupOfStack(entry.getKey()), "栈 " + entry.getKey() + " → 族");

        Map<String, String> vaes = new LinkedHashMap<>();
        vaes.put("qwen_image_vae.safetensors", VaeGuard.QWEN);
        vaes.put("animevae.pt", VaeGuard.SD15);
        vaes.put("vae-ft-mse-840000-ema-pruned.safetensors", VaeGuard.SD15);
        vaes.put("vae-ft-ema-560000-ema-pruned.safetensors", VaeGuard.SD15);
        vaes.put("sdxl_vae.safetensors", VaeGuard.SDXL);
        vaes.put("sd_xl_vae_fp16_fix.safetensors", VaeGuard.SDXL);
        vaes.put("ae.safetensors", VaeGuard.FLUX);
        vaes.put("flux_vae.safetensors", VaeGuard.FLUX);
        vaes.put("sd3_vae.safetensors", VaeGuard.SD3);
        vaes.put("mystery_blob.bin", VaeGuard.UNKNOWN);
        vaes.put("", VaeGuard.UNKNOWN);
        for (Map.Entry<String, String> entry : vaes.entrySet())
            equal(entry.getValue(), VaeGuard.groupOfVae(entry.getKey()), "VAE " + entry.getKey() + " → 族");

        check(!VaeGuard.groupLabel(VaeGuard.QWEN).isEmpty() && VaeGuard.groupLabel(VaeGuard.QWEN).contains("Qwen"),
                "族的标签非空且说得清：" + VaeGuard.groupLabel(VaeGuard.QWEN));
        check(VaeGuard.groupLabel(VaeGuard.SDXL).contains("SDXL"), "SDXL 的标签：" + VaeGuard.groupLabel(VaeGuard.SDXL));
        equal("", VaeGuard.groupLabel(VaeGuard.UNKNOWN), "认不出的族没有标签");
        equal("", VaeGuard.groupLabel("nope"), "未知族 key 也没有标签");

        // 调用方已经判好了底模名/栈时，就用它（比文件名更可信）。
        equal(VaeGuard.SDXL, VaeGuard.groupOfCheckpoint(new VaeGuard.Facts("weird_name.safetensors", "SDXL", "", "", List.of())),
                "给了底模名就按底模名判");
        equal(VaeGuard.QWEN, VaeGuard.groupOfCheckpoint(new VaeGuard.Facts("weird_name.safetensors", "", "anima", "", List.of())),
                "给了栈就按栈判");
        equal("xl", VaeGuard.stackOfCheckpoint(new VaeGuard.Facts("weird_name.safetensors", "SDXL", "", "", List.of())),
                "底模名 SDXL 归一成 xl 栈");
        equal("waiIllustriousSDXL_v170.safetensors",
                new VaeGuard.Facts("F:\\sd\\models\\StableDiffusion\\waiIllustriousSDXL_v170.safetensors [f116b0c7]", "", List.of())
                        .checkpointName(), "检查点名去目录、去哈希后缀");
        equal(3, new VaeGuard.Facts("a.safetensors", "sdxl_vae.safetensors",
                List.of("m1.safetensors", "", "m2.safetensors")).modules().size(), "空模块名被丢掉");
        equal(List.of(), new VaeGuard.Facts("a.safetensors", null, null).modules(), "null 模块列表当空处理");
    }

    /** 修复方案的完整形态。 */
    private static void repairPlans() {
        List<String> accident = VaeGuard.repairPlan(new VaeGuard.Facts(SDXL, "Automatic", List.of(QWEN_VAE, QWEN_TE)));
        equal(2, accident.size(), "事故的修复方案两步：" + accident);
        check(accident.get(1).contains(".model preset"), "第二步告诉用户怎么正确换栈：" + accident.get(1));
        check(accident.get(1).contains("残留"), "第二步点明是残留：" + accident.get(1));
        List<String> ok = VaeGuard.repairPlan(new VaeGuard.Facts("animaCatTower_v11.safetensors", "Automatic",
                List.of(QWEN_VAE, QWEN_TE)));
        equal(List.of("无需修复：sd_vae 与 Forge 额外模块都和检查点同族。"), ok, "anima 自己那套不用修");
        List<String> warn = VaeGuard.repairPlan(new VaeGuard.Facts(SDXL, "Automatic",
                List.of("someWeirdModule.safetensors")));
        check(warn.stream().anyMatch(line -> line.contains("清空") || line.contains("Automatic")),
                "认不出来时给保守建议：" + warn);
    }

    // ---------------------------------------------------------------- ② SdClient（假 SD 桩）

    /** VAE 的读：{@code vae()}、{@code vaeList()}、{@code presetModules()}、{@code modules()}。 */
    private static void vaeClientReads() throws Exception {
        try (Stub stub = new Stub()) {
            stub.vae = "sdxl_vae.safetensors";
            SdClient client = stub.client();
            equal("sdxl_vae.safetensors", client.vae(), "vae() 读 SD 的 options（那是真正生效的值）");

            List<String> list = client.vaeList();
            equal("Automatic", list.get(0), "VAE 列表第一个必须是 Automatic");
            equal("None", list.get(1), "第二个是 None");
            check(list.contains("qwen_image_vae.safetensors"), "含 sd-modules 里的 VAE：" + list);
            check(list.contains("animevae.pt"), "含子目录里的 VAE（按 basename，Forge 的下拉框就是这么列的）");
            check(list.contains("sdxl_vae.safetensors"), "含当前 sd_vae 自己");
            check(list.contains("disk_only_vae.safetensors"), "扫目录兜底：models/VAE 下的文件也在列表里");
            check(list.contains("legacy.vae.ckpt"), "扫目录兜底：models/*.vae.* 也在列表里");
            check(!list.contains("qwen_3_06b_base.safetensors"), "文本编码器不能混进 VAE 列表：" + list);
            equal(list.size(), new LinkedHashSet<>(list).size(), "列表没有重复项");
            check(stub.optionPosts.get() == 0, "vaeList() 只读，不写任何选项");

            equal(List.of("qwen_image_vae.safetensors", "qwen_3_06b_base.safetensors"), client.presetModules("anima"),
                    "presetModules 给出该预设自带的模块（去目录的文件名，与 vaeSnapshot 的 modules 同形态）");
            equal(List.of(), client.presetModules("xl"), "xl 预设没有额外模块");
            equal(List.of(), client.presetModules("nope"), "未知预设返回空列表（不抛异常）");
            equal(List.of(), client.presetModules(""), "空预设名返回空列表");
            check(client.modules().contains("qwen_3_06b_base.safetensors"), "modules() 的原有语义不变（文本编码器也在内）");
            equal(4, client.modules().size(), "modules() 仍然是 sd-modules 的全部条目");

            stub.modules.add(QWEN_VAE);
            equal("sdxl_vae.safetensors", client.vaeSnapshot().get("vae").getAsString(), "快照读的是当前 sd_vae");
            stub.optionsStatus = 500;
            equal("", client.vae(), "读不到 options 时 vae() 退回机器人自己记的值（这里是空串）");
            List<String> offline = client.vaeList();
            equal("Automatic", offline.get(0), "离线也能给出列表（至少 Automatic/None）");
            check(offline.contains("disk_only_vae.safetensors"), "离线时靠扫目录兜底：" + offline);
        }
        // 机器人自己记的 VAE：写进去之后，即使 options 读不到也要能报出来。
        try (Stub stub = new Stub()) {
            SdClient client = stub.client();
            client.setVae("disk_only_vae.safetensors");
            try (Stub other = new Stub()) {
                SdClient elsewhere = other.client();
                equal("Automatic", elsewhere.vae(), "另一个目录的客户端读的是它自己那台 SD 的值（记录不串味）");
            }
            SdClient restarted = stub.client();
            stub.optionsStatus = 500;
            equal("disk_only_vae.safetensors", restarted.vae(), "读不到 options 时退回机器人自己记的 VAE");
        }
    }

    /** VAE 的写：合法名落盘、非法名先抛且绝不写入。 */
    private static void vaeClientWrites() throws Exception {
        try (Stub stub = new Stub()) {
            SdClient client = stub.client();
            SdClient.GenerationSettings updated = client.setVae("sdxl_vae.safetensors");
            equal(1, stub.optionPosts.get(), "setVae 只发一次 options 写请求");
            equal("sd_vae", lastPostKey(stub), "写的是 sd_vae");
            equal("sdxl_vae.safetensors", stub.posts.get(0).get("sd_vae").getAsString(), "请求体里是那个 VAE 名");
            equal("sdxl_vae.safetensors", updated.vae(), "返回值带上新的 VAE");
            equal("sdxl_vae.safetensors", stub.vae, "假 SD 的 sd_vae 真的改了");
            equal("sdxl_vae.safetensors", client.vae(), "回读也是新值");
            equal("sdxl_vae.safetensors", settingsFile(stub.root).get("vae").getAsString(), "落进 data/sd-settings.json");
            equal("本地持久化（未同步 WebUI 当前页面）", updated.source(), "来源标成本地设置");

            equal("Automatic", client.setVae("auto").vae(), "auto → Automatic");
            equal("Automatic", client.setVae("AUTOMATIC").vae(), "大小写不敏感");
            equal("None", client.setVae("none").vae(), "none → None");
            equal("None", client.setVae("无").vae(), "中文「无」→ None");
            equal("None", settingsFile(stub.root).get("vae").getAsString(), "None 也落盘");
            equal("None", client.settings().withVae("None").vae(), "withVae 只换这一项");
            equal("disk_only_vae.safetensors", client.setVae("disk_only_vae.safetensors").vae(), "扫目录找到的 VAE 也能设");

            // 非法名字：先抛、带可用列表，且**一个请求都不发**、磁盘上的记录一个字不改。
            int posts = stub.optionPosts.get();
            String before = settingsFile(stub.root).get("vae").getAsString();
            expectFailure(() -> client.setVae("完全不存在.safetensors"), "未知 VAE", "非法 VAE 名要报错");
            expectFailure(() -> client.setVae("完全不存在.safetensors"), "disk_only_vae.safetensors",
                    "报错里带上可用列表");
            expectFailure(() -> client.setVae(""), "请提供 VAE 名称", "空名字要报错");
            equal(posts, stub.optionPosts.get(), "非法名字一个写请求都不发");
            equal(before, settingsFile(stub.root).get("vae").getAsString(), "非法名字不改磁盘上的记录");
            equal("disk_only_vae.safetensors", client.vae(), "非法名字不改当前值");

            // 路径形态也收（WebUI 里存的是 basename，但用户可能贴完整路径）。
            equal("disk_only_vae.safetensors",
                    client.setVae(stub.sdRoot.resolve("models/VAE/disk_only_vae.safetensors").toString()).vae(),
                    "贴完整路径也认（归一成 basename）");
        }
        // 桩的 options 写接口坏掉时：不能假装成功。
        try (Stub stub = new Stub()) {
            SdClient client = stub.client();
            stub.writeStatus = 500;
            expectFailure(() -> client.setVae("disk_only_vae.safetensors"), "HTTP 500", "写失败要抛出来");
            check(!Files.exists(stub.root.resolve("data/sd-settings.json")), "写失败不留本地记录");
        }
    }

    /** 切预设必须连 {@code forge_additional_modules} 一起切（空清单也要写 = 清残留）。 */
    private static void presetSwitchCarriesModules() throws Exception {
        try (Stub stub = new Stub()) {
            SdClient client = stub.client();
            JsonObject anima = client.setForgePreset("anima");
            equal(1, stub.optionPosts.get(), "一次请求就把预设与额外模块一起写上");
            JsonObject first = stub.posts.get(0);
            equal("anima", Json.str(first, "forge_preset", ""), "第一次写就带 forge_preset");
            equal(List.of(QWEN_VAE, QWEN_TE), strings(first.get("forge_additional_modules")), "同一请求里带上该预设的模块");
            equal(2, anima.getAsJsonArray("modules").size(), "返回值里如实报告这一栈的模块");
            check(anima.get("modulesWritten").getAsBoolean(), "写成功要如实说（回读确认过）");
            String applied = anima.get("applied").toString();
            check(applied.contains("额外模块 2 个") && applied.contains("qwen_image_vae.safetensors"),
                    "applied 里说明额外模块变成了什么：" + applied);
            equal(List.of(QWEN_VAE, QWEN_TE), List.copyOf(stub.modules), "假 SD 的 forge_additional_modules 真的换了");
            equal("animaCatTower_v11.safetensors", stub.checkpoint, "底模也切到这一栈的检查点");

            // 切回 xl：xl 自己没有额外模块 → 必须写空数组，把 anima 的残留清掉（这就是事故的根因）。
            JsonObject xl = client.setForgePreset("xl");
            equal(2, stub.optionPosts.get(), "切回 xl 又一次请求");
            JsonObject second = stub.posts.get(1);
            equal("xl", Json.str(second, "forge_preset", ""), "第二次也带 forge_preset");
            equal(0, second.getAsJsonArray("forge_additional_modules").size(), "空清单也要写（写空数组）");
            check(xl.get("modulesWritten").getAsBoolean(), "清空也要回读确认");
            check(xl.get("applied").toString().contains("额外模块已清空"),
                    "applied 说清是清空：" + xl.get("applied"));
            equal(List.of(), List.copyOf(stub.modules), "残留模块被清掉了（这就是这次灰图事故的修复点）");
            equal(SDXL, stub.checkpoint, "底模切回 SDXL");
            equal("ok", Json.str(Json.obj(xl, "conflict"), "level", ""), "切完之后冲突判定是 ok");

            // 事故的一来一回：xl → anima → xl，最后一次必须干干净净。
            client.setForgePreset("anima");
            client.setForgePreset("xl");
            equal(List.of(), List.copyOf(stub.modules), "一来一回之后额外模块仍然是空的");
            equal("ok", Json.str(Json.obj(client.vaeSnapshot(), "conflict"), "level", ""), "快照判定 ok");
            equal("Automatic", stub.vae, "切预设不会动 sd_vae");
        }
        // 桩返回空字符串（有的构建没有模块时给 ""）时，回读也要当成"空清单"。
        try (Stub stub = new Stub()) {
            stub.emptyModulesAsBlank = true;
            SdClient client = stub.client();
            JsonObject xl = client.setForgePreset("xl");
            check(xl.get("modulesWritten").getAsBoolean(), "回读是空字符串也要认成已清空");
        }
    }

    /** 不接受 forge_preset 的构建（真机上就是 500 KeyError）：调用顺序必须是"预设 → 检查点 → 模块"。 */
    private static void presetSwitchFallbackOrder() throws Exception {
        try (Stub stub = new Stub()) {
            stub.rejectPreset = true;
            SdClient client = stub.client();
            JsonObject anima = client.setForgePreset("anima");
            equal(4, stub.optionPosts.get(), "这条路上要发四次请求：" + stub.posts);
            equal("anima", Json.str(stub.posts.get(0), "forge_preset", ""), "① 先试 预设+模块");
            equal("anima", Json.str(stub.posts.get(1), "forge_preset", ""), "② 再试 只发预设");
            check(!stub.posts.get(1).has("forge_additional_modules"), "② 不带模块（别让模块字段把预设请求带崩）");
            equal("animaCatTower_v11.safetensors", Json.str(stub.posts.get(2), "sd_model_checkpoint", ""),
                    "③ 预设不被接受时显式写这一栈的检查点");
            check(!stub.posts.get(2).has("forge_preset") && !stub.posts.get(2).has("forge_additional_modules"),
                    "③ 只写检查点");
            equal(List.of(QWEN_VAE, QWEN_TE), strings(stub.posts.get(3).get("forge_additional_modules")),
                    "④ 最后写该栈的额外模块（qwen VAE + 文本编码器）");
            check(!stub.posts.get(3).has("forge_preset"),
                    "④ 不能再带 forge_preset（这个构建不认它，带上会把模块写入一起弄失败）");
            equal(List.of(QWEN_VAE, QWEN_TE), List.copyOf(stub.modules), "模块真的装上了");
            equal("animaCatTower_v11.safetensors", stub.checkpoint, "检查点真的换了");
            check(anima.get("modulesWritten").getAsBoolean() && anima.get("applied").toString().contains("底模="),
                    "applied 如实报告：底模 + 额外模块");

            // 同一条路上清残留：切回 xl 要把模块写空。
            int before = stub.optionPosts.get();
            client.setForgePreset("xl");
            equal(before + 4, stub.optionPosts.get(), "清残留也走同一条四次请求的路");
            List<String> last = strings(stub.posts.get(stub.posts.size() - 1).get("forge_additional_modules"));
            equal(List.of(), last, "最后一次写入的是空清单");
            equal(List.of(), List.copyOf(stub.modules), "残留模块被清掉了");
            equal(SDXL, stub.checkpoint, "底模回到 SDXL");
        }
        // 连模块字段都不认的构建：如实报"未确认"，不能假装成功。
        try (Stub stub = new Stub()) {
            stub.rejectModules = true;
            SdClient client = stub.client();
            JsonObject anima = client.setForgePreset("anima");
            check(!anima.get("modulesWritten").getAsBoolean(), "写不进模块时要如实说没确认");
            check(anima.get("applied").toString().contains("未确认"), "applied 里点明未确认：" + anima.get("applied"));
            equal("anima", stub.preset, "预设本身还是切过去了（别因为模块失败就回滚）");
        }
    }

    /**
     * 预设**自己**就配错了（真机上的实例：{@code forge_additional_modules_sd} 里存着 qwen_image_vae +
     * qwen_3_06b_base，而这一栈是 SD 栈）：照配置如实写，但必须在回执里警告——不然切过去又是一次灰图。
     */
    private static void presetWithItsOwnBadModules() throws Exception {
        try (Stub stub = new Stub()) {
            stub.presetCheckpoints.put("sd", "sd15_anime_v3.safetensors");
            stub.presetModules.put("sd", List.of(QWEN_VAE, QWEN_TE));
            SdClient client = stub.client();
            JsonObject result = client.setForgePreset("sd");
            equal("block", Json.str(Json.obj(result, "conflict"), "level", ""), "预设自带模块与它自己的检查点不是一族 → block");
            check(result.get("applied").toString().contains("⚠"), "applied 里必须警告：" + result.get("applied"));
            check(Json.str(Json.obj(result, "conflict"), "reason", "").contains("qwen_3_06b_base.safetensors"),
                    "警告里点名那个模块");
            equal(List.of(QWEN_VAE, QWEN_TE), List.copyOf(stub.modules), "照配置如实写，不偷偷过滤（过滤要用户先确认）");
            equal(VaeGuard.Severity.BLOCK, VaeGuard.check(new VaeGuard.Facts("sd15_anime_v3.safetensors", "",
                    "sd", "Automatic", List.of(QWEN_VAE, QWEN_TE))).severity(), "同一份事实单独判定也是 BLOCK");
        }
    }

    /** {@code vaeSnapshot()}：网页/指令都靠它，字段与离线容错都要稳。 */
    private static void snapshotAndHealth() throws Exception {
        try (Stub stub = new Stub()) {
            stub.checkpoint = SDXL;
            stub.modules.clear();
            stub.modules.add(QWEN_VAE);
            stub.modules.add(QWEN_TE);
            stub.vae = "Automatic";
            SdClient client = stub.client();
            JsonObject snapshot = client.vaeSnapshot();
            equal("Automatic", Json.str(snapshot, "vae", ""), "快照的 vae");
            check(snapshot.get("vaeAuto").getAsBoolean(), "Automatic → vaeAuto=true");
            equal(SDXL, Json.str(snapshot, "checkpoint", ""), "快照的检查点");
            equal("xl", Json.str(snapshot, "preset", ""), "快照带上当前 Forge 预设");
            equal(List.of("qwen_image_vae.safetensors", "qwen_3_06b_base.safetensors"),
                    strings(snapshot.get("modules")), "modules 是给人看的文件名");
            equal(List.of(QWEN_VAE, QWEN_TE), strings(snapshot.get("modulesRaw")), "modulesRaw 是原样值");
            JsonObject conflict = Json.obj(snapshot, "conflict");
            equal("block", Json.str(conflict, "level", ""), "事故现场的判定是 block");
            check(Json.str(conflict, "reason", "").contains("qwen_3_06b_base.safetensors"), "原因写在快照里");
            equal(List.of("qwen_image_vae.safetensors", "qwen_3_06b_base.safetensors"), strings(conflict.get("culprits")),
                    "culprits 与 modules 同形态，网页能直接显示");
            equal("Automatic", strings(snapshot.get("choices")).get(0), "choices 第一个是 Automatic");
            check(snapshot.get("reachable").getAsBoolean(), "读到了 → reachable=true");
            equal(0, snapshot.getAsJsonArray("errors").size(), "读到了就没有 errors");

            VaeGuard.Facts facts = client.vaeFacts();
            equal(SDXL, facts.checkpoint(), "vaeFacts 的检查点");
            equal("Automatic", facts.vae(), "vaeFacts 的 VAE");
            equal(2, facts.modules().size(), "vaeFacts 的模块");
            equal(VaeGuard.Severity.BLOCK, VaeGuard.check(facts).severity(), "vaeFacts 直接喂给 VaeGuard 就是 BLOCK");

            stub.modules.clear();
            equal("ok", Json.str(Json.obj(client.vaeSnapshot(), "conflict"), "level", ""), "正常配置 → ok");
            stub.vae = "qwen_image_vae.safetensors";
            JsonObject manual = client.vaeSnapshot();
            check(!manual.get("vaeAuto").getAsBoolean(), "手动指定的 VAE → vaeAuto=false");
            equal("block", Json.str(Json.obj(manual, "conflict"), "level", ""), "手动指到错族的 VAE → block");

            stub.optionsStatus = 500;
            JsonObject offline = client.vaeSnapshot();
            check(!offline.get("reachable").getAsBoolean(), "读不到时 reachable=false（页面照常渲染）");
            check(offline.getAsJsonArray("errors").size() >= 1, "读不到时 errors 里如实写原因");
            check(offline.has("conflict"), "读不到也照样给一个判定（多半是 WARN/OK）");
            check(offline.getAsJsonArray("choices").size() >= 2, "读不到也有至少 Automatic/None 可选");
        }
    }

    /**
     * 走真 {@link SdClient#vaeSnapshot()} 这条路的交叉验证（网页横幅与出图前防呆读的都是它）：
     * options 说是 SDXL + 当前预设说是 anima + sd_vae=qwen → WARN；三源一致说 anima → OK。
     */
    private static void snapshotCrossChecks() throws Exception {
        // ① 误报现场：options（含哈希反查）说 SDXL，当前预设是 anima，用户按 anima 设了 qwen 的 VAE。
        try (Stub stub = new Stub()) {
            stub.checkpoint = SDXL;
            stub.checkpointHash = "f116b0c78ff441467b0cdc8f1936e1ed18ea31e9997c7b132b1b8db533f0bd04";
            stub.preset = "anima";
            stub.vae = "qwen_image_vae.safetensors";
            SdClient client = stub.client();
            JsonObject snapshot = client.vaeSnapshot();
            JsonObject conflict = Json.obj(snapshot, "conflict");
            equal("warn", Json.str(conflict, "level", ""), "误报现场必须是 WARN（不是 BLOCK）");
            check(!snapshot.get("readingsConsistent").getAsBoolean(), "readingsConsistent=false：网页据此提示以 Forge 页面为准");
            check(Json.str(conflict, "reason", "").contains("检查点读数不一致"), "原因含「读数不一致」：" + conflict);
            check(!Json.str(conflict, "reason", "").contains("出灰图"), "原因不许出现「会出灰图」：" + conflict);
            check(Json.str(conflict, "suggestion", "").contains("Forge 页面"), "建议让用户去 Forge 页面确认");
            equal(0, conflict.getAsJsonArray("culprits").size(), "读数矛盾时不给 culprits");
            List<String> sources = readingSources(snapshot);
            check(sources.contains("options") && sources.contains("Forge 预设 anima") && sources.contains("已加载哈希"),
                    "三条读数都带上了：" + sources);
            equal(SDXL, Json.str(snapshot, "checkpoint", ""), "checkpoint 仍是 options 读到的值（不影响显示）");
            equal("qwen_image_vae.safetensors", Json.str(snapshot, "vae", ""), "sd_vae 照旧如实报");
            equal(VaeGuard.Severity.WARN, VaeGuard.check(client.vaeFacts()).severity(),
                    "vaeFacts() 独立判定也是 WARN（出图前防呆只用提醒不拒绝）");
        }
        // ② 自洽的 anima：三源一致 + qwen 的 VAE/模块 → OK（用户那次真正该看到的结论）。
        try (Stub stub = new Stub()) {
            stub.checkpoint = "animaCatTower_v11.safetensors";
            stub.checkpointHash = "2d0343cd69ffffffffffffffffffffffffffffffffffffffffffffffffffff";
            stub.preset = "anima";
            stub.vae = "qwen_image_vae.safetensors";
            stub.modules.add(QWEN_VAE);
            stub.modules.add(QWEN_TE);
            SdClient client = stub.client();
            JsonObject snapshot = client.vaeSnapshot();
            equal("ok", Json.str(Json.obj(snapshot, "conflict"), "level", ""), "三源一致说是 anima + qwen 的配置 → OK");
            check(snapshot.get("readingsConsistent").getAsBoolean(), "readingsConsistent=true");
            check(readingSources(snapshot).contains("已加载哈希"), "哈希读数也在");
        }
        // ③ 机器人自己记的底模与 options 矛盾（记的是 anima、SD 上其实是 SDXL）→ 也只 WARN，并点名来源。
        try (Stub stub = new Stub()) {
            stub.config.addProperty("checkpoint", "animaCatTower_v11.safetensors [2d0343cd69]");
            stub.checkpoint = SDXL;
            stub.preset = "xl";
            stub.vae = "qwen_image_vae.safetensors";
            SdClient client = stub.client();
            JsonObject conflict = Json.obj(client.vaeSnapshot(), "conflict");
            equal("warn", Json.str(conflict, "level", ""), "机器人记录与 options 矛盾 → WARN");
            check(Json.str(conflict, "reason", "").contains("机器人记录="), "原因点名机器人记录：" + conflict);
        }
        // ④ 自洽的 SDXL（options + 预设 + 哈希都说 SDXL）配 qwen 的 VAE → 仍然 BLOCK（真冲突不放过）。
        try (Stub stub = new Stub()) {
            stub.checkpoint = SDXL;
            stub.checkpointHash = "f116b0c78ff441467b0cdc8f1936e1ed18ea31e9997c7b132b1b8db533f0bd04";
            stub.preset = "xl";
            stub.vae = "qwen_image_vae.safetensors";
            SdClient client = stub.client();
            JsonObject snapshot = client.vaeSnapshot();
            JsonObject conflict = Json.obj(snapshot, "conflict");
            equal("block", Json.str(conflict, "level", ""), "自洽的 SDXL + qwen 的 VAE 仍然 BLOCK");
            check(Json.str(conflict, "reason", "").contains("读数："), "BLOCK 原因写清依据：" + conflict);
            check(snapshot.get("readingsConsistent").getAsBoolean(), "读数自洽");
            check(Json.str(conflict, "suggestion", "").contains("可能出灰图"), "建议是「可能出灰图」的量级");
        }
    }

    /** 快照里的读数来源列表（网页横幅/指令都靠它显示依据）。 */
    private static List<String> readingSources(JsonObject snapshot) {
        List<String> sources = new ArrayList<>();
        if (!snapshot.has("readings")) return List.of();
        for (JsonElement item : snapshot.getAsJsonArray("readings"))
            if (item.isJsonObject()) sources.add(item.getAsJsonObject().get("source").getAsString());
        return List.copyOf(sources);
    }

    /** 样式载入这条路：VAE 与额外模块要跟着一起套上，文案也要如实。 */    private static void styleParamsCarryVae() throws Exception {
        try (Stub stub = new Stub()) {
            SdClient client = stub.client();
            JsonObject model = Json.parse("{\"forge_preset\":\"anima\",\"vae\":\"qwen_image_vae.safetensors\"}");
            List<String> applied = client.applyModelParams(model, preset -> {
                client.setForgePreset(preset);
                return List.of("预设 " + preset);
            });
            check(applied.stream().anyMatch(line -> line.contains("预设")), "回执里说明切了预设：" + applied);
            check(applied.stream().anyMatch(line -> line.contains("VAE qwen_image_vae.safetensors")),
                    "回执里说明套上了 VAE：" + applied);
            equal("qwen_image_vae.safetensors", stub.vae, "样式里的 VAE 真的写进了 SD");
            equal(List.of(QWEN_VAE, QWEN_TE), List.copyOf(stub.modules), "预设的额外模块也跟着切了");
            equal("qwen_image_vae.safetensors", Json.str(client.modelParams(), "vae", ""),
                    "modelParams() 记下当前 VAE（存样式用；只读本地、不发请求）");

            // 样式里的 VAE 现在不可用（被删/名字写错）：如实说"保留原值"，不影响别的参数。
            stub.vae = "Automatic";
            List<String> failed = client.applyModelParams(Json.parse("{\"vae\":\"没有这个vae.safetensors\"}"), null);
            check(failed.stream().anyMatch(line -> line.contains("没有这个vae.safetensors") && line.contains("保留原值")),
                    "不可用的 VAE 要如实说：" + failed);
            equal("Automatic", stub.vae, "不可用的 VAE 不改动当前值");
        }
    }

    // ---------------------------------------------------------------- 桩

    /** 假 SD WebUI（Forge Neo 形态）：options / sd-modules，写操作全部留痕且可控地拒绝。 */
    private static final class Stub implements AutoCloseable {
        final Path root, sdRoot;
        final HttpServer server;
        final ExecutorService executor;
        final JsonObject config = new JsonObject();
        final AtomicInteger optionPosts = new AtomicInteger();
        final List<JsonObject> posts = Collections.synchronizedList(new ArrayList<>());
        final List<JsonObject> sdModules = Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> presetCheckpoints = new LinkedHashMap<>();
        final Map<String, List<String>> presetModules = new LinkedHashMap<>();
        final List<String> modules = Collections.synchronizedList(new ArrayList<>());
        /** 模拟"不认 forge_preset 的构建"（真机 POST 它会 500 KeyError）。 */
        volatile boolean rejectPreset;
        /** 模拟"不认 forge_additional_modules 的构建"。 */
        volatile boolean rejectModules;
        /** 没有额外模块时 options 里给空字符串而不是空数组（两种形态都要能读）。 */
        volatile boolean emptyModulesAsBlank;
        volatile int optionsStatus = 200, modulesStatus = 200, writeStatus = 200;
        volatile String vae = "Automatic", checkpoint = "", preset = "xl", checkpointHash = "";

        Stub() throws IOException {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "vae-guard-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            sdRoot = Files.createTempDirectory(work, "sd-");
            Files.createDirectories(sdRoot.resolve("models/VAE/sd1.5"));
            Files.write(sdRoot.resolve("models/VAE/disk_only_vae.safetensors"), new byte[] {1});
            Files.write(sdRoot.resolve("models/legacy.vae.ckpt"), new byte[] {1});

            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "sd-mock"); thread.setDaemon(true); return thread;
            });
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();

            config.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            config.addProperty("root", sdRoot.toString());
            config.addProperty("timeout_seconds", 5);
            config.addProperty("sampler_name", "Euler a");
            config.add("styles", Json.GSON.toJsonTree(List.of()));
            config.addProperty("width", 832);
            config.addProperty("height", 1216);
            config.addProperty("cfg_scale", 7.5);
            config.addProperty("seed", 1);

            preset("xl", SDXL, List.of());
            preset("anima", "animaCatTower_v11.safetensors", List.of(QWEN_VAE, QWEN_TE));
            preset("sd", "Counterfeit-V3.0_fp16.safetensors", List.of());
            checkpoint = SDXL;

            sdModules.add(module("qwen_image_vae.safetensors", QWEN_VAE));
            sdModules.add(module("animevae.pt", "F:\\sd\\sd-webui-forge-neo\\models\\VAE\\sd1.5\\animevae.pt"));
            sdModules.add(module("sdxl_vae.safetensors", "F:\\sd\\sd-webui-forge-neo\\models\\VAE\\sdxl_vae.safetensors"));
            sdModules.add(module("qwen_3_06b_base.safetensors", QWEN_TE));
        }

        private void preset(String name, String checkpoint, List<String> modules) {
            presetCheckpoints.put(name, checkpoint);
            presetModules.put(name, modules);
        }

        private static JsonObject module(String name, String filename) {
            JsonObject item = new JsonObject();
            item.addProperty("model_name", name);
            item.addProperty("filename", filename);
            return item;
        }

        SdClient client() throws IOException { return new SdClient(root, config); }

        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/sdapi/v1/options")) {
                    if (exchange.getRequestMethod().equals("POST")) {
                        JsonObject body = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        posts.add(body);
                        optionPosts.incrementAndGet();
                        if (writeStatus != 200) { send(exchange, writeStatus, "{\"detail\":\"write broken\"}"); return; }
                        // 真机的行为：不认的键会让整个请求 500（KeyError），而且**什么都不改**。
                        if (body.has("forge_preset") && rejectPreset) { send(exchange, 500, "{\"detail\":\"KeyError: forge_preset\"}"); return; }
                        if (body.has("forge_additional_modules") && rejectModules) { send(exchange, 500, "{\"detail\":\"KeyError\"}"); return; }
                        apply(body);
                        send(exchange, 200, "");   // 真机 POST /sdapi/v1/options 返回空响应体
                        return;
                    }
                    if (optionsStatus != 200) { send(exchange, optionsStatus, "{\"detail\":\"broken\"}"); return; }
                    send(exchange, 200, Json.GSON.toJson(options()));
                } else if (path.equals("/sdapi/v1/sd-modules")) {
                    if (modulesStatus != 200) { send(exchange, modulesStatus, "{}"); return; }
                    JsonArray list = new JsonArray();
                    for (JsonObject item : sdModules) list.add(item.deepCopy());
                    send(exchange, 200, Json.GSON.toJson(list));
                } else if (path.equals("/sdapi/v1/sd-models")) {
                    JsonArray list = new JsonArray();
                    for (String title : List.of(SDXL + " [f116b0c78f]", "animaCatTower_v11.safetensors [2d0343cd69]")) {
                        JsonObject item = new JsonObject(); item.addProperty("title", title); list.add(item);
                    }
                    send(exchange, 200, Json.GSON.toJson(list));
                } else send(exchange, 404, "{}");
            } catch (RuntimeException error) {
                send(exchange, 500, "{\"detail\":\"" + error.getClass().getSimpleName() + "\"}");
            } finally { exchange.close(); }
        }

        private void apply(JsonObject body) {
            // 这个桩模拟"完整支持 forge_preset 的构建"：切预设就是把这一栈的检查点也一起换上。
            if (body.has("forge_preset")) {
                preset = body.get("forge_preset").getAsString();
                String target = presetCheckpoints.get(preset);
                if (target != null) checkpoint = target;
            }
            if (body.has("sd_model_checkpoint")) checkpoint = body.get("sd_model_checkpoint").getAsString();
            if (body.has("sd_vae")) vae = body.get("sd_vae").getAsString();
            if (body.has("forge_additional_modules")) {
                modules.clear();
                for (JsonElement item : body.getAsJsonArray("forge_additional_modules")) modules.add(item.getAsString());
            }
        }

        /** GET options 的响应：真机上是 762 个键，这里只给判定需要的那些。 */
        private JsonObject options() {
            JsonObject options = new JsonObject();
            options.addProperty("sd_vae", vae);
            options.addProperty("sd_model_checkpoint", checkpoint);
            if (!checkpointHash.isBlank()) options.addProperty("sd_checkpoint_hash", checkpointHash);
            options.addProperty("forge_preset", preset);
            if (emptyModulesAsBlank && modules.isEmpty()) options.addProperty("forge_additional_modules", "");
            else options.add("forge_additional_modules", Json.GSON.toJsonTree(List.copyOf(modules)));
            for (Map.Entry<String, String> entry : presetCheckpoints.entrySet()) {
                String name = entry.getKey();
                options.addProperty("forge_checkpoint_" + name, entry.getValue());
                options.add("forge_additional_modules_" + name, Json.GSON.toJsonTree(presetModules.get(name)));
                options.addProperty(name + "_t2i_sampler", "ER SDE");
                options.addProperty(name + "_t2i_scheduler", "Beta");
                options.addProperty(name + "_t2i_step", 32);
                options.addProperty(name + "_t2i_cfg", 4);
                options.addProperty(name + "_t2i_dcfg", 3);
                options.addProperty(name + "_t2i_width", 1024);
                options.addProperty(name + "_t2i_height", 1024);
            }
            return options;
        }

        static void send(HttpExchange exchange, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        public void close() throws IOException {
            server.stop(0);
            executor.shutdownNow();
            for (Path base : List.of(root, sdRoot)) {
                try (var paths = Files.walk(base)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 断言工具

    private interface Operation { Object run() throws Exception; }

    private static void expectFailure(Operation operation, String fragment, String message) throws Exception {
        try { operation.run(); throw new AssertionError(message + "：本应抛异常，实际成功了"); }
        catch (IOException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains(fragment),
                    message + "：" + expected.getMessage());
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + "：expected=" + expected + "，actual=" + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static JsonObject settingsFile(Path root) throws IOException {
        return Json.parse(Files.readString(root.resolve("data/sd-settings.json")));
    }

    /** 最后一次 POST /sdapi/v1/options 里唯一（或第一个）业务的键名。 */
    private static String lastPostKey(Stub stub) {
        JsonObject post = stub.posts.get(stub.posts.size() - 1);
        for (String key : post.keySet()) if (!key.equals("expected_revision")) return key;
        return "";
    }

    private static List<String> strings(JsonElement element) {
        if (element == null || !element.isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) values.add(item.getAsString());
        return List.copyOf(values);
    }
}

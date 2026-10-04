package cn.szu.bot.sd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * VAE／Forge 额外模块与当前检查点的**防呆判定**。
 *
 * <p>为什么要有这个类：2026-10 的一次真实事故——把 Forge 预设从 {@code xl} 切到 {@code anima} 再切回
 * {@code xl} 时，{@code forge_additional_modules} 里 anima 的 {@code qwen_image_vae.safetensors} 与
 * {@code qwen_3_06b_base.safetensors} **没有跟着清掉**，于是 SDXL 检查点配上了 Qwen 的 VAE＋文本编码器，
 * 连出 4 张纯灰图（标准差 0.00、只有 1 种颜色、11 KB）。这类错配在 Forge 里几乎一定出灰图/废图，
 * 但 WebUI 自己不会拦——所以要机器人先判、先拦、先告诉用户怎么修。
 *
 * <p><b>判定逻辑是纯函数</b>：输入是 {@link Facts}（检查点名/底模名/栈、当前 {@code sd_vae}、
 * 当前 {@code forge_additional_modules}），**不发任何网络请求、不读文件**，因此可以完全离线单测。
 * 网络侧的读取（options / sd-modules / 目录扫描）与写入在 {@link SdClient} 里。
 *
 * <p><b>判据（保守优先：宁可 WARN 不要误 BLOCK）</b>
 * <ol>
 *   <li><b>栈不匹配 → BLOCK</b>：检查点与当前 VAE／额外模块里的 VAE 各自用 {@link StackClassifier}
 *       的体系判族（SD1.5 / SDXL（含 Illustrious、NoobAI、Pony、Animagine）/ Flux（含 Krea、Chroma）/
 *       Qwen-Anima / SD3 / …），确证不同族就是 BLOCK——SDXL 检查点配 {@code qwen_image_vae} 正是这次事故；</li>
 *   <li><b>额外模块残留 → BLOCK</b>：{@code forge_additional_modules} 里有**文本编码器**
 *       （{@code qwen_3_06b_base} / {@code t5xxl} / {@code clip_l} …）而当前检查点不属于那个架构 → BLOCK；</li>
 *   <li>{@code sd_vae} 与检查点同族 → OK；{@code sd_vae = Automatic}（或 {@code None}）→ 不判（SD 自己挑）；</li>
 *   <li><b>只凭名字认不出来 → WARN</b>（例如自定义命名的 VAE）：说明"无法判断，建议 Automatic"，
 *       并**不**把认不出来的名字当 culprits——不确定就不许被调用方拿去自动删除。</li>
 * </ol>
 *
 * <p><b>已知的保守性取舍</b>：族与族的兼容表是按本机实据写的（anima＝Qwen-Image 架构，见 Forge 预设
 * {@code anima} 自带 qwen_image_vae + qwen_3_06b_base）。冷门栈（z-image / nitro / odor / lumina…）
 * 只按自己的族算：某个冷门栈若实际复用 Qwen 文本编码器，这里会误报 BLOCK；没有实据前宁可报出来让人确认，
 * 也不要静默放过——但认不出名字时一律只给 WARN。
 */
public final class VaeGuard {

    /** 严重程度：{@code OK} 正常；{@code WARN} 认不出来（不敢下结论）；{@code BLOCK} 确证不匹配（会出灰图）。 */
    public enum Severity { OK, WARN, BLOCK }

    /** 检查点／VAE 的架构族（{@link #UNKNOWN} 表示名字里认不出来）。 */
    public static final String SD15 = "sd15", SDXL = "sdxl", FLUX = "flux", QWEN = "qwen", SD3 = "sd3",
            CASCADE = "cascade", KOLORS = "kolors", PIXART = "pixart", PLAYGROUND = "playground",
            LUMINA = "lumina", HUNYUAN = "hunyuan", WAN = "wan", Z_IMAGE = "z-image", NITRO = "nitro",
            ODOR = "odor";
    /** 认不出归属族。 */
    public static final String UNKNOWN = "";

    /** 认不出来时给用户的建议：不要乱改，回到最安全的 Automatic。 */
    public static final String WARN_SUGGESTION =
            "认不出归属时别乱改：把 sd_vae 设成 Automatic，并清空 Forge 额外模块（forge_additional_modules），让 WebUI 自己挑。";
    /**
     * {@code sd_vae} / 额外模块的族**确证**与检查点不一致时的建议。
     *
     * <p>措辞刻意留有余地（"可能"）：判定只是"按读数一致地看是这样配的"，真正的灰图还取决于运行时；
     * 依据（哪几个来源、各读到什么）都写在 {@code reason} 里，让用户能自己判断。
     */
    public static final String BLOCK_SUGGESTION =
            "按这些读数，这样配可能出灰图：把 sd_vae 设回 Automatic，并清空 Forge 额外模块（forge_additional_modules）"
                    + "后再重新出图；要换栈请用 .model preset <名字>（切预设会连检查点与额外模块一起换）。";
    /**
     * 多源读数**互相矛盾**时的建议：什么都不改，先让用户去 Forge 页面确认。
     *
     * <p>为什么要这样：2026-10 的一次误报——用户在 Forge 里用的是 anima，但 options 的
     * {@code sd_model_checkpoint} 还是上一次的 SDXL（切换期间/经 UI 切换后有一段时间是旧值），
     * 于是"SDXL 检查点 + qwen 的 VAE"被判成 BLOCK 并拦下了生成，而 VAE 其实是对的。
     * 读数自相矛盾时**不许**下结论、**不许**自动改配置（改错方向会把本来正常的栈换成错的）。
     */
    public static final String INCONSISTENT_SUGGESTION =
            "读数不一致时先别自动改（改错方向会把本来正常的栈换成错的）：到 Forge 页面确认当前真正加载的模型，"
                    + "然后用 .vae auto（跟随当前模型）或 .vae set <名字> 明确指定 VAE。";

    /** 检查点读数不一致时的固定说法（测试与会话都在盯这句）。 */
    public static final String INCONSISTENT_PREFIX = "检查点读数不一致：";
    /** 不一致时的结论句：明确"没下结论"，并且不提"会出灰图"。 */
    public static final String INCONSISTENT_TAIL = " —— 无法确认是否真冲突，请以 Forge 页面为准";

    /**
     * 一条"检查点读数"：来源标签 + 读到的值。多源交叉验证用（读不到的来源不要放进来，空值会被忽略）。
     *
     * @param source     来源（如 {@code options} / {@code Forge 预设 anima} / {@code 机器人记录} / {@code 已加载哈希}）
     * @param checkpoint 该来源读到的检查点（文件名/标题/路径都行）
     */
    public record Reading(String source, String checkpoint) {
        public Reading {
            source = source == null ? "" : source.strip();
            checkpoint = checkpoint == null ? "" : checkpoint.strip();
        }
    }

    /**
     * 一次判定的输入（**纯数据**，由调用方从 WebUI 读好再传进来）。
     *
     * @param checkpoint         当前检查点（文件名、标题或路径都行，带 {@code [哈希]} 也认）
     * @param checkpointBaseModel 已经识别出来的底模名（{@code SDXL} / {@code Illustrious} / {@code Anima}…），
     *                           有就比文件名更可信；没有填空串
     * @param checkpointStack    已经判出来的栈 key（{@code xl} / {@code anima} / {@code flux}…）；没有填空串
     * @param vae                当前 {@code sd_vae}（{@code Automatic} / {@code None} / 文件名）
     * @param modules            当前 {@code forge_additional_modules}（原样字符串，通常是完整路径）
     * @param readings           **多源读数**（options／当前 Forge 预设声明的那一个／机器人自己记的／按
     *                           {@code sd_checkpoint_hash} 反查到的"已加载"）。互相矛盾时判定只到 WARN，
     *                           绝不 BLOCK——见 {@link #INCONSISTENT_PREFIX}
     */
    public record Facts(String checkpoint, String checkpointBaseModel, String checkpointStack,
                        String vae, List<String> modules, List<Reading> readings) {
        public Facts {
            checkpoint = checkpoint == null ? "" : checkpoint.strip();
            checkpointBaseModel = checkpointBaseModel == null ? "" : checkpointBaseModel.strip();
            checkpointStack = checkpointStack == null ? "" : checkpointStack.strip();
            vae = vae == null ? "" : vae.strip();
            modules = modules == null ? List.of() : List.copyOf(modules);
            readings = readings == null ? List.of() : List.copyOf(readings);
        }

        /** 常用的一种：只给检查点名、当前 sd_vae、当前 forge_additional_modules（栈与底模名现场判）。 */
        public Facts(String checkpoint, String vae, List<String> modules) { this(checkpoint, "", "", vae, modules, List.of()); }

        /** 不带多源读数、但带栈/底模名的写法（Forge 预设归属这条判据就用它）。 */
        public Facts(String checkpoint, String checkpointBaseModel, String checkpointStack,
                     String vae, List<String> modules) {
            this(checkpoint, checkpointBaseModel, checkpointStack, vae, modules, List.of());
        }

        /** 带多源读数的写法：主检查点 + 当前 sd_vae + 额外模块 + 各来源读数。 */
        public Facts(String checkpoint, String vae, List<String> modules, List<Reading> readings) {
            this(checkpoint, "", "", vae, modules, readings);
        }

        /** 去目录、去 {@code [哈希]} 后缀的检查点文件名（提示语里用）。 */
        public String checkpointName() { return StackClassifier.bareName(checkpoint); }
    }

    /**
     * 判定结论。
     *
     * @param conflict   **只在确证不匹配（{@link Severity#BLOCK}）时为 true**；认不出来（WARN）不算冲突，
     *                   免得调用方把"不确定"当成"不许出图"
     * @param severity   严重程度
     * @param reason     中文原因（可能由多条判据拼起来）
     * @param suggestion 中文建议；{@code OK} 时是空串
     * @param culprits   **要清掉的原样字符串**（与 {@code forge_additional_modules} 里的值逐字对应，
     *                   便于调用方直接拿去做删除）；只有 BLOCK 才会有，WARN 一律为空
     */
    public record Conflict(boolean conflict, Severity severity, String reason, String suggestion, List<String> culprits) {
        public Conflict {
            severity = severity == null ? Severity.OK : severity;
            reason = reason == null ? "" : reason;
            suggestion = suggestion == null ? "" : suggestion;
            culprits = culprits == null ? List.of() : List.copyOf(culprits);
        }

        public boolean blocked() { return severity == Severity.BLOCK; }
        public boolean warned() { return severity == Severity.WARN; }
        public boolean clean() { return severity == Severity.OK; }
        /** 网页 JSON 里用的 level：{@code ok} / {@code warn} / {@code block}。 */
        public String level() { return severity.name().toLowerCase(Locale.ROOT); }
        /** 中文说法（回执里用）：正常 / 注意 / 冲突。 */
        public String label() { return switch (severity) { case OK -> "正常"; case WARN -> "注意"; case BLOCK -> "冲突"; }; }
    }

    /** 额外模块的种类。 */
    private enum Kind { VAE, TEXT_ENCODER, UNKNOWN }

    /** 一个额外模块的判定：原样字符串、种类、**能配得上的族**（空表示认不出来）。 */
    private record Module(String value, Kind kind, List<String> groups) { }

    /** 一条判据的结论：是否确证不匹配（BLOCK）、是不是 sd_vae 这一项、原样名字、族、中文说明。 */
    private record Finding(boolean block, boolean vae, String name, String group, String message) { }

    /** 名字里出现这些就按**文本编码器**认（VAE 的名字里一定带 vae，见 {@link #classifyModule}）。 */
    private static final List<String> TEXT_ENCODER_HINTS = List.of(
            "qwen 3", "qwen 2", "qwen3", "qwen2", "qwen",
            "umt5", "mt5", "t5xxl", "t5 xl", "t5",
            "clip l", "clipl", "clip g", "clipg", "llava", "llama", "gemma", "byt5",
            "text encoder", "text encoders");

    private VaeGuard() { }

    // ---------------------------------------------------------------- 对外判定

    /** 判定一次（纯函数）。 */
    public static Conflict check(Facts facts) {
        Objects.requireNonNull(facts, "facts 不能为空");
        List<Finding> findings = analyze(facts);
        List<String> blocks = new ArrayList<>(), warns = new ArrayList<>(), culprits = new ArrayList<>();
        for (Finding finding : findings) {
            if (finding.block()) { blocks.add(finding.message()); culprits.add(finding.name()); }
            else warns.add(finding.message());
        }
        if (!blocks.isEmpty())
            return new Conflict(true, Severity.BLOCK, String.join("；", blocks), BLOCK_SUGGESTION, culprits);
        if (!warns.isEmpty()) {
            // 读数自相矛盾时给的是"先确认、别自动改"那套说法，不是"认不出来就设 Automatic"。
            String suggestion = inconsistent(facts) ? INCONSISTENT_SUGGESTION : WARN_SUGGESTION;
            return new Conflict(false, Severity.WARN, String.join("；", warns), suggestion, List.of());
        }
        return new Conflict(false, Severity.OK,
                "sd_vae 与 Forge 额外模块都和检查点同族"
                        + (facts.checkpointName().isEmpty() ? "" : "（" + facts.checkpointName() + "）"), "", List.of());
    }

    /**
     * 修复方案（纯函数，逐条给可执行的步骤）：确证不匹配时给"把 sd_vae 设回 Automatic"与
     * "清掉 forge_additional_modules 里的哪几个模块"；认不出来时给"先设成 Automatic"这种保守建议；
     * **多源读数矛盾时什么都不让改**——先确认 Forge 页面里真正加载的是哪个模型。
     */
    public static List<String> repairPlan(Facts facts) {
        Objects.requireNonNull(facts, "facts 不能为空");
        if (inconsistent(facts)) {
            List<Source> sources = sources(facts, groupOfCheckpoint(facts));
            return List.of(
                    INCONSISTENT_PREFIX + String.join(" / ", partsForInconsistent(sources))
                            + "：先到 Forge 页面确认当前真正加载的模型，再决定要不要改（现在改错方向会把正常的栈换成错的）",
                    "确认后可用 .vae auto（跟随当前模型）或 .vae set <名字> 明确指定 VAE");
        }
        List<Finding> findings = analyze(facts);
        if (findings.isEmpty()) return List.of("无需修复：sd_vae 与 Forge 额外模块都和检查点同族。");
        String where = facts.checkpointName().isEmpty() ? "当前检查点" : facts.checkpointName();
        List<String> plan = new ArrayList<>();
        List<String> badVae = names(findings, true, true);
        if (!badVae.isEmpty())
            plan.add("把 sd_vae 设回 Automatic（当前 " + String.join("、", badVae) + " 与 " + where + " 不是同一族）");
        List<String> badModules = names(findings, false, true);
        if (!badModules.isEmpty()) {
            plan.add("清掉 Forge 额外模块（forge_additional_modules）里的 " + String.join("、", bareNames(badModules)));
            plan.add("要换栈就用 .model preset <名字>：切预设时会把该预设自带的额外模块一并写上，"
                    + "不再留下上一个栈（anima/qwen 这类）的残留");
        }
        List<String> vagueVae = names(findings, true, false);
        if (!vagueVae.isEmpty())
            plan.add("认不出 " + String.join("、", vagueVae) + " 的归属：建议把 sd_vae 设成 Automatic，让 WebUI 自己挑");
        List<String> vagueModules = bareNames(names(findings, false, false));
        if (!vagueModules.isEmpty())
            plan.add("认不出归属的额外模块：" + String.join("、", vagueModules)
                    + "；不确定就先清空 forge_additional_modules（或切到与检查点同族的 Forge 预设）");
        return List.copyOf(plan);
    }

    // ---------------------------------------------------------------- 族判定（对外也给调用方用）

    /**
     * 栈 key（{@link StackClassifier} 的体系）→ 架构族。
     *
     * <p>几个关键映射都有本机实据：{@code xl} 收下 Illustrious／NoobAI／Pony／Animagine（都是 SDXL 架构）；
     * {@code anima} 与 {@code qwen} 同族——Forge 预设 {@code anima} 自带的就是 {@code qwen_image_vae} ＋
     * {@code qwen_3_06b_base}（线上 options 实测）；{@code krea} 与 {@code chroma} 都归 Flux
     * （Krea 是 Flux.1 Krea，Chroma 是 Flux Schnell 派生，用的是同一个 autoencoder）。
     */
    public static String groupOfStack(String stack) {
        String value = stack == null ? "" : stack.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case StackClassifier.SD -> SD15;
            case StackClassifier.XL -> SDXL;
            case StackClassifier.FLUX, StackClassifier.KREA, StackClassifier.CHROMA -> FLUX;
            case StackClassifier.QWEN, StackClassifier.ANIMA -> QWEN;
            case StackClassifier.SD3 -> SD3;
            case StackClassifier.CASCADE -> CASCADE;
            case StackClassifier.KOLORS -> KOLORS;
            case StackClassifier.PIXART -> PIXART;
            case StackClassifier.PLAYGROUND -> PLAYGROUND;
            case StackClassifier.LUMINA -> LUMINA;
            case StackClassifier.HUNYUAN -> HUNYUAN;
            case StackClassifier.WAN -> WAN;
            case StackClassifier.Z_IMAGE -> Z_IMAGE;
            case StackClassifier.NITRO -> NITRO;
            case StackClassifier.ODOR -> ODOR;
            default -> UNKNOWN;
        };
    }

    /** 检查点的架构族：先用调用方给的栈，再用底模名，最后才拿文件名关键词兜底。 */
    public static String groupOfCheckpoint(Facts facts) {
        Objects.requireNonNull(facts, "facts 不能为空");
        return groupOfStack(stackOfCheckpoint(facts));
    }

    /**
     * 多源读数是不是**自相矛盾**（网页／指令据此提示"以 Forge 页面为准"，并据此决定不自动改配置）。
     * 矛盾时 {@link #check(Facts)} 只会给 WARN，绝不会 BLOCK。
     */
    public static boolean inconsistent(Facts facts) {
        Objects.requireNonNull(facts, "facts 不能为空");
        return distinctGroups(sources(facts, groupOfCheckpoint(facts))).size() > 1;
    }

    /** 检查点的栈 key（同一个判定链，给提示语与日志用）。 */
    public static String stackOfCheckpoint(Facts facts) {
        Objects.requireNonNull(facts, "facts 不能为空");
        if (!facts.checkpointStack().isEmpty()) return StackClassifier.stackOfPreset(facts.checkpointStack());
        if (!facts.checkpointBaseModel().isEmpty()) {
            String stack = StackClassifier.stackOfBaseModel(StackClassifier.canonicalBaseModel(facts.checkpointBaseModel()));
            if (!stack.isEmpty()) return stack;
        }
        return StackClassifier.stackOfFilename(facts.checkpointName());
    }

    /**
     * VAE 文件名的架构族：先认本机见过的那几个 VAE 家族名，再退回 {@link StackClassifier} 的文件名关键词。
     *
     * <p>顺序很要紧：{@code animevae.pt} 里含 {@code anima} 子串，直接交给 {@link StackClassifier}
     * 会被判成 Anima/Qwen 栈（真身是 SD1.5 的 VAE），所以本机实据表排在前面。
     */
    public static String groupOfVae(String name) {
        String file = StackClassifier.bareName(name);
        String key = normalize(file);
        if (key.isEmpty()) return UNKNOWN;
        // ① 本机/常见 VAE 的实据表（unet 侧的关键词表认不出这些名字）。
        if (key.contains("animevae") || key.contains("vae ft mse") || key.contains("vae ft ema")
                || key.contains("840000") || key.contains("kl f8")) return SD15;
        if (key.equals("ae") || key.startsWith("ae ") || key.contains("flux ae")) return FLUX;   // Flux 官方 VAE 就叫 ae.safetensors
        // ② 名字里带家族名的（qwen_image_vae / sdxl_vae / sd3_vae / hunyuan_vae …）。
        if (key.contains("qwen")) return QWEN;
        if (key.contains("sdxl") || key.contains("sd xl")) return SDXL;
        if (key.contains("flux")) return FLUX;
        if (key.contains("sd3") || key.contains("sd 3")) return SD3;
        if (key.contains("cascade")) return CASCADE;
        if (key.contains("hunyuan")) return HUNYUAN;
        if (key.contains("kolors")) return KOLORS;
        if (key.contains("lumina")) return LUMINA;
        if (key.contains("z image") || key.contains("zimage")) return Z_IMAGE;
        // ③ 交给项目自己的关键词表兜底（illustrious / noob / pony / sd 1.5 …）。
        return groupOfStack(StackClassifier.stackOfFilename(file));
    }

    /** 族的短标签（中文提示语里用）；认不出来是空串。 */
    public static String groupLabel(String group) {
        String value = group == null ? "" : group.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case SD15 -> "SD1.5";
            case SDXL -> "SDXL（Illustrious/NoobAI/Pony 同族）";
            case FLUX -> "Flux（含 Krea/Chroma）";
            case QWEN -> "Qwen/Anima";
            case SD3 -> "SD3";
            case CASCADE -> "Stable Cascade";
            case KOLORS -> "Kolors";
            case PIXART -> "PixArt";
            case PLAYGROUND -> "Playground";
            case LUMINA -> "Lumina";
            case HUNYUAN -> "Hunyuan";
            case WAN -> "Wan";
            case Z_IMAGE -> "Z-Image";
            case NITRO -> "Nitro-E";
            case ODOR -> "ODOR";
            default -> UNKNOWN;
        };
    }

    /** 这一项算不算"交给 WebUI 自己挑"（Automatic / None / 空 / 中文的自动）。 */
    public static boolean automaticVae(String vae) {
        String value = vae == null ? "" : vae.strip().toLowerCase(Locale.ROOT);
        return value.isEmpty() || value.equals("automatic") || value.equals("auto") || value.equals("default")
                || value.equals("none") || value.equals("off") || value.equals("自动") || value.equals("无");
    }

    // ---------------------------------------------------------------- 内部实现

    /** 一个来源读到的检查点：原样值、来源标签、判出来的族。 */
    private record Source(String value, String sources, String group) { }

    /**
     * 多源交叉验证：把"同一个检查点的多个读数"收集起来（每条读数各占一项，同值不合并——依据要逐条写清）。
     *
     * <p>这是修"误报"的关键一步：options 的 {@code sd_model_checkpoint} 有可能还是**上一次**的值
     * （Forge 切换模型期间/经 UI 切换后有一段时间是旧的），而当前 Forge 预设声明的是另一个；
     * 两者矛盾时**不能**拿其中一个去判"族冲突"。
     *
     * @param explicitGroup 调用方已经判好的"主检查点"的族（来自 {@code checkpointStack}／底模名，可信度最高）
     */
    private static List<Source> sources(Facts facts, String explicitGroup) {
        List<Source> result = new ArrayList<>();
        String name = facts.checkpoint();
        boolean covered = false;
        for (Reading reading : facts.readings())
            if (!reading.checkpoint().isBlank() && !name.isBlank() && sameCheckpoint(reading.checkpoint(), name))
                covered = true;
        if (!name.isBlank() && !covered) result.add(new Source(name, "检查点", explicitGroup));
        for (Reading reading : facts.readings()) {
            if (reading.checkpoint().isBlank()) continue;
            String label = reading.source().isBlank() ? "检查点" : reading.source();
            boolean main = !name.isBlank() && sameCheckpoint(reading.checkpoint(), name);
            String group = main ? explicitGroup
                    : groupOfStack(StackClassifier.stackOfFilename(StackClassifier.bareName(reading.checkpoint())));
            result.add(new Source(reading.checkpoint(), label, group));
        }
        return result;
    }

    /** 两个写法是不是"同一个检查点"（去目录、去 {@code [哈希]} 后缀、大小写不敏感）。 */
    private static boolean sameCheckpoint(String left, String right) {
        String a = StackClassifier.bareName(left).strip().toLowerCase(Locale.ROOT);
        String b = StackClassifier.bareName(right).strip().toLowerCase(Locale.ROOT);
        return !a.isEmpty() && a.equals(b);
    }

    /** 读数里出现了几个**不同**的族（0/1 个＝自洽；≥2 个＝自相矛盾）。 */
    private static List<String> distinctGroups(List<Source> sources) {
        List<String> groups = new ArrayList<>();
        for (Source source : sources)
            if (!source.group().isEmpty() && !groups.contains(source.group())) groups.add(source.group());
        return groups;
    }

    /** 一条来源的写法：{@code options=waiIllustrious…（SDXL 族）}。 */
    private static String part(Source source) {
        String label = groupLabel(source.group());
        return source.sources() + "=" + StackClassifier.bareName(source.value())
                + (label.isEmpty() ? "" : "（" + label + "族）");
    }

    /** 「options=waiIllustrious…（SDXL 族）、Forge 预设 xl=…（SDXL 族）」这样的一句依据。 */
    private static String evidence(List<Source> sources) {
        List<String> parts = new ArrayList<>();
        for (Source source : sources) parts.add(part(source));
        return String.join("、", parts);
    }

    /**
     * 读数自相矛盾时的整句（含"若把哪个读数当真，则哪一项对不上"的提示）。**不提"会出灰图"**。
     */
    private static String inconsistentMessage(Facts facts, List<Source> sources) {
        String text = INCONSISTENT_PREFIX + String.join(" / ", partsForInconsistent(sources)) + INCONSISTENT_TAIL;
        String hint = mismatchHint(facts, sources);
        return hint.isEmpty() ? text : text + hint;
    }

    /** 矛盾时逐源写法（用 {@code /} 分隔，与提示语一致）。 */
    private static List<String> partsForInconsistent(List<Source> sources) {
        List<String> parts = new ArrayList<>();
        for (Source source : sources) parts.add(part(source));
        return parts;
    }

    /** 「（若真加载的是 options 里的 X（SDXL 族），则 sd_vae = qwen_image_vae 确实对不上）」。 */
    private static String mismatchHint(Facts facts, List<Source> sources) {
        for (Source source : sources) {
            if (source.group().isEmpty()) continue;
            List<String> bad = new ArrayList<>();
            if (!automaticVae(facts.vae())) {
                String group = groupOfVae(facts.vae());
                if (!group.isEmpty() && !group.equals(source.group())) bad.add("sd_vae = " + facts.vae());
            }
            for (String module : facts.modules()) {
                if (module == null || module.isBlank()) continue;
                Module info = classifyModule(module);
                if (info.groups().isEmpty() || info.groups().contains(source.group())) continue;
                bad.add((info.kind() == Kind.TEXT_ENCODER ? "文本编码器 " : "额外模块 ") + display(module));
            }
            if (!bad.isEmpty())
                return "（若真加载的是 " + StackClassifier.bareName(source.value()) + "（" + groupLabel(source.group())
                        + "族），则 " + String.join("、", bad) + " 确实对不上）";
        }
        return "";
    }

    private static List<Finding> analyze(Facts facts) {
        List<Finding> findings = new ArrayList<>();
        String where = facts.checkpointName().isEmpty() ? "当前检查点" : facts.checkpointName();
        List<Source> sources = sources(facts, groupOfCheckpoint(facts));
        List<String> groups = distinctGroups(sources);
        // ⓪ 多源读数互相矛盾：**不下结论**——只给 WARN，说清各源读到了什么、让用户自己判断。
        if (groups.size() > 1) {
            findings.add(new Finding(false, false, "", "", inconsistentMessage(facts, sources)));
            return findings;
        }
        String checkpointGroup = groups.isEmpty() ? UNKNOWN : groups.get(0);
        String checkpointLabel = groupLabel(checkpointGroup);
        String by = sources.isEmpty() ? "" : "（读数：" + evidence(sources) + "）";
        // ① sd_vae：写死了具体文件时才判；Automatic/None 交给 WebUI 自己挑。
        String vae = facts.vae();
        if (!automaticVae(vae)) {
            String group = groupOfVae(vae);
            if (group.isEmpty()) {
                findings.add(new Finding(false, true, vae, "",
                        "sd_vae「" + vae + "」的名字里看不出归属族，没法确认它和 " + where + " 是不是同一族"));
            } else if (checkpointGroup.isEmpty()) {
                findings.add(new Finding(false, true, vae, group,
                        "sd_vae「" + vae + "」是" + groupLabel(group) + "族的，但 " + where + " 认不出归属，没法确认是否匹配"));
            } else if (!group.equals(checkpointGroup)) {
                findings.add(new Finding(true, true, vae, group,
                        "sd_vae = " + vae + "（" + groupLabel(group) + "族）与检查点 " + where
                                + "（" + checkpointLabel + "族）不是同一族" + by));
            }
        }
        // ② forge_additional_modules：残留的 VAE / 文本编码器必须和这一栈对得上（这次灰图事故的根因）。
        for (String module : facts.modules()) {
            if (module == null || module.isBlank()) continue;
            Module info = classifyModule(module);
            String shown = display(module);
            String kindLabel = info.kind() == Kind.TEXT_ENCODER ? "文本编码器" : "额外模块";
            if (info.groups().isEmpty()) {
                findings.add(new Finding(false, false, module, "",
                        kindLabel + "「" + shown + "」认不出归属族，没法确认它配不配得上 " + where));
                continue;
            }
            String groupsText = groupLabels(info.groups());
            if (checkpointGroup.isEmpty()) {
                findings.add(new Finding(false, false, module, info.groups().get(0),
                        kindLabel + "「" + shown + "」是" + groupsText + "族的，但 " + where
                                + " 认不出归属，没法确认是否匹配"));
                continue;
            }
            if (!info.groups().contains(checkpointGroup)) {
                findings.add(new Finding(true, false, module, info.groups().get(0),
                        "Forge 额外模块残留：" + kindLabel + "「" + shown + "」是" + groupsText + "族的，而检查点 "
                                + where + " 是" + checkpointLabel + "族" + by));
            }
        }
        return findings;
    }

    /** 原样字符串 → 模块判定。名字里带 vae 的一律当 VAE（{@code qwen_image_vae} 是 VAE，不是文本编码器）。 */
    static Module classifyModule(String value) {
        String key = normalize(value);
        String vae = groupOfVae(value);
        if (key.contains("vae")) return new Module(value, Kind.VAE, vae.isEmpty() ? List.of() : List.of(vae));
        if (looksTextEncoder(key)) return new Module(value, Kind.TEXT_ENCODER, textEncoderGroups(key));
        return new Module(value, Kind.UNKNOWN, vae.isEmpty() ? List.of() : List.of(vae));
    }

    private static boolean looksTextEncoder(String key) {
        for (String hint : TEXT_ENCODER_HINTS) if (key.contains(hint)) return true;
        return false;
    }

    /**
     * 文本编码器能配得上的族。顺序要紧：{@code umt5}／{@code mt5} 里含 {@code t5}，必须排在张量 5 之前。
     */
    private static List<String> textEncoderGroups(String key) {
        if (key.contains("qwen")) return List.of(QWEN);
        if (key.contains("umt5") || key.contains("mt5")) return List.of(WAN);
        if (key.contains("t5xxl") || key.contains("t5 xl") || key.contains("t5")) return List.of(FLUX, SD3);
        if (key.contains("clip g") || key.contains("clipg")) return List.of(SD3);
        if (key.contains("clip l") || key.contains("clipl")) return List.of(FLUX, SD3);
        if (key.contains("llava") || key.contains("llama")) return List.of(HUNYUAN);
        return List.of();
    }

    private static List<String> names(List<Finding> findings, boolean vae, boolean block) {
        List<String> result = new ArrayList<>();
        for (Finding finding : findings)
            if (finding.vae() == vae && finding.block() == block && !finding.name().isBlank())
                result.add(finding.name());
        return result;
    }

    /** 显示名：去目录、去哈希后缀（判定与 culprits 仍然用原样字符串）。 */
    private static String display(String value) {
        String bare = StackClassifier.bareName(value);
        return bare.isEmpty() ? value.strip() : bare;
    }

    private static List<String> bareNames(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) result.add(display(value));
        return result;
    }

    private static String groupLabels(List<String> groups) {
        List<String> labels = new ArrayList<>();
        for (String group : groups) {
            String label = groupLabel(group);
            if (!label.isEmpty()) labels.add(label);
        }
        return String.join("／", labels);
    }

    /** 小写 + 非字母数字换成空格：{@code qwen_image_vae.safetensors} → {@code qwen image vae safetensors}。 */
    private static String normalize(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            out.append(Character.isLetterOrDigit(ch) ? ch : ' ');
        }
        return out.toString().strip();
    }
}

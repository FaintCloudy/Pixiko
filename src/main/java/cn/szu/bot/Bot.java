package cn.szu.bot;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.*;
import javax.imageio.ImageIO;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.ChatService;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.chat.SceneDecomposer;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.civitai.CivitaiLinkLogin;
import cn.szu.bot.civitai.CivitaiStyleSync;
import cn.szu.bot.civitai.DownloadControl;
import cn.szu.bot.civitai.LoraTriggers;
import cn.szu.bot.prompt.CategoryModel;
import cn.szu.bot.prompt.PromptEditor;
import cn.szu.bot.prompt.PromptFunctions;
import cn.szu.bot.prompt.PromptUsage;
import cn.szu.bot.prompt.TagSuggest;
import cn.szu.bot.prompt.TermCategories;
import cn.szu.bot.sd.GenerationPreset;
import cn.szu.bot.sd.LocalStyles;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;
import cn.szu.bot.sd.VaeGuard;

public final class Bot implements AutoCloseable {
    @FunctionalInterface public interface Sender {
        CompletableFuture<Void> send(JsonObject event, JsonArray segments);
        default CompletableFuture<Void> sendMap(JsonObject event, JsonArray segments) { return send(event, segments); }
        /**
         * Sends several messages as ONE chat record, each entry staying its own message node.
         * Transports without forward-message support fall back to sending them in order.
         */
        default CompletableFuture<Void> sendRecord(JsonObject event, List<JsonArray> messages) {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (JsonArray message : messages) chain = chain.thenCompose(ignored -> send(event, message));
            return chain;
        }
        /**
         * true = 这个传输层真的会发合并转发（重写了 {@link #sendRecord}）。
         *
         * <p>上面的默认实现只是给"不支持合并转发"的传输层留的兼容回退（逐条发出去，没有节点概念）。
         * 机器人在把一批图片合成合并转发之前会先问这一句：不支持时就保持它原来就有的发法
         * （一次领取一条消息、地图逐张发送），绝不因为多了一段回退逻辑就把一批图片拆成一串消息。
         */
        default boolean supportsRecord() {
            try {
                return getClass().getMethod("sendRecord", JsonObject.class, List.class).getDeclaringClass() != Sender.class;
            } catch (NoSuchMethodException | SecurityException ignored) { return false; }
        }
        /** QQ API passthrough used for member lookups; transports that cannot do it fail explicitly. */
        default CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
            return CompletableFuture.failedFuture(new IOException("当前传输不支持 " + action + " 查询。"));
        }
    }
    @FunctionalInterface public interface LoraDownloader {
        CivitaiClient.DownloadedLora download(String url, Consumer<String> stage, CivitaiClient.Progress meter) throws Exception;
        default CivitaiClient.DownloadedLora download(String url, Consumer<String> stage) throws Exception {
            return download(url, stage, null);
        }
        /**
         * 带下载控制（暂停/取消）的重载：真实实现（{@link CivitaiClient#download}）在拷贝循环里查它，
         * 真实路径下取消会抛 {@link DownloadControl.CancelledException}。默认实现忽略控制对象，
         * 这样既有的替身下载器与 lambda 不用改（既不暂停也不取消，语义等于"控制不可用"）。
         */
        default CivitaiClient.DownloadedLora download(String url, Consumer<String> stage, CivitaiClient.Progress meter,
                                                     DownloadControl control) throws Exception {
            return download(url, stage, meter);
        }
    }
    private record LoraResult(String text,boolean success) {}
    @FunctionalInterface private interface LoraAction { LoraResult run() throws Exception; }
    private final Settings settings;
    private final ChatService chat;
    private final SdClient sd;
    /**
     * VAE 的读写入口（默认就是 {@link #sd}）。做成可替换的一层是因为 {@link SdClient} 是 final 的，
     * 测试没法用子类打桩——`.vae`、出图前防呆与灰图补救必须在假 SD 桩上也能完整跑一遍。
     */
    private VaeSupport vae;
    private final Sender sender;
    /** 传输层真的会发合并转发时才把多张图合成一条（见 {@link Sender#supportsRecord()}）。 */
    private final boolean forwardRecords;
    /** Every user owns a private prompt pair, persisted under data/prompts/. */
    private final UserPromptStore userPrompts;
    /** 机器人自己的样式库：与 WebUI 的预设样式完全分开，同名也不会冲突。 */
    private final cn.szu.bot.sd.LocalStyles localStyles;
    private final LoraDownloader loraDownloader;
    /**
     * 本机 LoRA 触发词的耐久存储（data/lora-triggers.json）：下载成功那一刻写入，之后随时可查。
     * 触发词与"是否加载模型""是否写进提示词"无关——用户要的只是留着做参考。
     */
    private final LoraTriggers loraTriggers;
    /** SD WebUI 自启动：生成前发现 SD 没在跑就把它拉起来。 */
    private final cn.szu.bot.sd.SdLauncher sdLauncher;
    /** 群禁言识别：被禁言时不再尝试发送（见 {@link MuteGuard}）。 */
    private final MuteGuard mute = new MuteGuard();
    private final ExecutorService generation = Executors.newSingleThreadExecutor();
    private final ExecutorService progenIO = Executors.newSingleThreadExecutor();
    private final AtomicBoolean progenBusy = new AtomicBoolean();
    /**
     * 生图频道（提示词改写/画面检查）的客户端覆盖，只给测试用（见 {@link #useImageClientForTests}）：
     * 测试要能对"一次指令到底调了几次 DeepSeek"计数，并且注入"检查发现矛盾/检查失败/检查乱返回"这些假响应。
     * 生产路径永远是 {@code null}，即按 config.json 的 progen 段与密钥文件真的去调 DeepSeek。
     */
    private static volatile DeepSeekPrompts imageClientOverride;
    private final ExecutorService chatWorkflows = Executors.newSingleThreadExecutor(task -> { Thread thread=new Thread(task,"pixiko-chat-workflow");thread.setDaemon(true);return thread; });
    private final Map<String,CompletableFuture<Boolean>> chatWorkflowSteps=new ConcurrentHashMap<>();
    /** Chat chains still running; shutdown waits for them instead of cutting a user's chain in half. */
    private final java.util.concurrent.atomic.AtomicInteger activeChatWorkflows=new java.util.concurrent.atomic.AtomicInteger();
    private final ExecutorService outboxDelivery = Executors.newSingleThreadExecutor();
    private final ExecutorService imageIO = Executors.newFixedThreadPool(2);
    private final Semaphore imageBatches = new Semaphore(4);
    private final Object generationLock = new Object(), generationSubmissionLock = new Object();
    private final Deque<GenerationJob> generationJobs = new ArrayDeque<>();
    private final Deque<GenerationJob> finishedJobs = new ArrayDeque<>();
    private boolean generationWorkerActive, generationRunning;
    private BigInteger nextGenerationId = BigInteger.ZERO, waitingGenerations = BigInteger.ZERO, suspendedGenerations = BigInteger.ZERO;
    /** 正在下发的那一个任务（挂起/取消/置顶都要认得它）。 */
    private GenerationJob currentGeneration;
    private final AtomicBoolean drainingImages = new AtomicBoolean(), closed = new AtomicBoolean();
    private final ThreadPoolExecutor loraIO = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
    private final AtomicBoolean loraBusy = new AtomicBoolean(), loraClosed = new AtomicBoolean();
    private volatile String loraStatus = "尚未下载 LoRA。";
    /** Live download meter so .lora status can report MiB/total, percent and an ETA. */
    private volatile long loraDownloaded, loraTotal = -1, loraStartedNanos;
    /** 正在下载（而不是加载）——控制台据此决定要不要显示进度条。 */
    private volatile boolean loraDownloading;
    /**
     * 当前下载的暂停/取消开关：只有下载任务（{@code downloading=true}）会新建一个，
     * 任务一结束就作废（置 null），所以下一次下载拿到的一定是**新**对象，不会被上次的取消标记污染。
     */
    private volatile DownloadControl loraControl;
    /** 当前 LoRA 任务的完成信号：/api/lora/cancel 据此等到下载线程真的退出（半截文件已清理）才回执。 */
    private volatile CountDownLatch loraJobDone = new CountDownLatch(0);
    /** 当前 LoRA 任务的回执（网页接口触发的才有）：进度与结果都要能进 /quest/#N。 */
    private volatile WebCapture loraReceipt;
    private final Map<String, List<CivitaiClient.SearchResult>> loraSearches = new ConcurrentHashMap<>();
    /** 最近一次搜索的**页码信息**（本页编号从 1 开始；规划层据此知道 #N 是"本页第 N 条"）。 */
    private final Map<String, CivitaiClient.SearchPage> loraPages = new ConcurrentHashMap<>();
    private static String conversation(JsonObject event) {
        return Json.str(event, "self_id", "") + ":" + Json.str(event, "message_type", "") + ":"
                + Json.str(event, "group_id", "") + ":" + Json.str(event, "user_id", "");
    }
    /**
     * 婚配数据的会话键：同一个群共享一份文件（不能带 user_id，否则别人的婚配关系查不到），
     * 私聊则按用户各自独立。
     */
    static String marriageKey(JsonObject event) {
        String group = Json.str(event, "group_id", "");
        if ("group".equals(Json.str(event, "message_type", "")) && !group.isBlank())
            return Json.str(event, "self_id", "") + ":group:" + group;
        return Json.str(event, "self_id", "") + ":private:" + Json.str(event, "user_id", "");
    }
    private final Map<String, List<String>> selections = new ConcurrentHashMap<>();
    /** Last time a list/number-producing command ran in a conversation, for topic-gap expiry. */
    private final Map<String, Long> selectionTimes = new ConcurrentHashMap<>();
    /**
     * 最近一次真正发给用户的编号列表。用户紧接着说"第 N 个 / #N / 这个 / 就它"时，指的一定是这份列表，
     * 而不是程序顺手预取的别的编号体系（"刚搜完 Civitai，机器人却按本地 LoRA 列表解释 #2"就是这么来的）。
     */
    private record ShownList(String kind, List<String> labels, long nanos) {}
    private final Map<String, ShownList> shownLists = new ConcurrentHashMap<>();
    /** Character a user asked about, so a short confirmation can continue the task chain. */
    private final Map<String, String> pendingCharacters = new ConcurrentHashMap<>();
    /**
     * 引用消息与本次请求的分界标记：引用内容只是背景（可能包含旧列表、旧编号、别人的话），
     * 守卫与编号检查只能看标记后面的本次请求，否则引用里的内容会被当成用户这次的要求。
     */
    public static final String REQUEST_MARK = "\n【我这条消息】";
    /** 最近展示的编号列表叫什么、其中每一项该怎么用，供规划模型把"第 N 个"变成正确的动作。 */
    static String listTitle(String kind) {
        return switch (kind == null ? "" : kind) {
            case "civitai" -> "Civitai 搜索结果";
            case "lora" -> "本机已下载的 LoRA";
            case "style" -> "样式（本机样式 + WebUI 预设）";
            case "function" -> "提示词集";
            case "preset" -> "参数预设";
            case "sampler" -> "采样方法";
            case "vae" -> "VAE";
            case "model" -> "基础模型";
            case "usage" -> "提示词分类与词条";
            case "char" -> "角色候选";
            case "prompt" -> "个人正向提示词词条";
            case "promptR" -> "个人反向提示词词条";
            default -> "编号列表";
        };
    }
    /** 该列表的编号对应哪条指令；返回 null 表示没有可直接执行的指令（例如分类目录）。 */
    static String listAction(String kind) {
        return switch (kind == null ? "" : kind) {
            case "civitai" -> ".lora download #N";
            case "lora" -> ".lora load #N";
            case "style" -> ".style load #N";
            case "function" -> ".function load #N";
            case "preset" -> ".preset load #N";
            case "sampler" -> ".sampler set #N";
            case "vae" -> ".vae set #N";
            case "model" -> ".model set #N";
            case "usage" -> ".usage #N";
            case "char" -> ".char apply #N";
            case "prompt", "promptR" -> ".prompt remove #N";
            default -> null;
        };
    }
    String numbered(JsonObject event, String kind, List<String> names) { return numbered(event, kind, names, names); }
    String numbered(JsonObject event, String kind, List<String> names, List<String> labels) {
        selections.put(conversation(event) + ":" + kind, List.copyOf(names));
        selectionTimes.put(conversation(event), System.nanoTime());
        shownLists.put(conversation(event), new ShownList(kind, List.copyOf(labels), System.nanoTime()));
        if (names.isEmpty()) return "（无）";
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) lines.add("#" + (i + 1) + " " + labels.get(i));
        return "\n" + String.join("\n", lines);
    }
    /** 测试与调用方查询：这份会话最近展示的编号列表种类。 */
    String shownListKind(JsonObject event) {
        ShownList shown = shownLists.get(conversation(event));
        return shown == null ? "" : shown.kind();
    }
    /**
     * 用户只说了"第 N 个/#N/就它"这类指代、而模型没给出任何指令时，把最近展示的那份列表连同它对应的动作
     * 再强调一次，让模型补出指令。这条兜底覆盖"刚搜完 Civitai，机器人却反问要哪个列表"这一类上下文断裂：
     * 只要用户确实在指代列表条目，就不该用一句澄清把编号丢掉。
     */
    /** 纯指代解析结果：该执行的指令，以及它指的那一项（用于回执，避免只说一句空话）。 */
    public record ShownSelection(String command, String label) {
        /** 只是要澄清（编号与名字对不上、名字有歧义），不执行任何指令。 */
        public boolean clarification() { return command == null || command.isBlank(); }
        public static ShownSelection ask(String text) { return new ShownSelection("", text); }
    }
    /** 只认"就第二个 / 选#2 / 下载 #2"这类整句都是指代的消息；带别的内容一律交给模型。 */
    private static final Pattern SHOWN_REFERENCE = Pattern.compile(
            "^(?:(?:请|帮我|麻烦|就|选|要|用|拿|下载|加载|来|换|应用|使用|这个|那个|它)*\\s*)?#([0-9]{1,3})\\s*(?:号|个|条|项)?\\s*(?:吧|呢|了|！|!|。|\\.|，|,)?$");
    /** "选择sy军 / 用白河沙滩 / 套上 #62 sy军 / 加载第二个"这类挑选句：动词 + 编号 + 名字。 */
    private static final Pattern SHOWN_PICK = Pattern.compile(
            "^(?:请|帮我|麻烦|给我)?\\s*(?:选择|挑选|套上|换成|切换|改成|选好|载入|加载|应用|使用|就|要|选|用|拿|套|上|来|换|加载|载入)?\\s*"
            + "(#?[0-9]{1,3})?\\s*(号|个|条|项|第)?\\s*([^\\s，。；,;!！?？#]*?)\\s*(?:吧|呢|了|，|,|。|\\.|！|!|\\?|？)?$");
    /** 挑选句里明确出现过的动词；没有动词的句子（例如只报一个名字）交给模型处理。 */
    private static final Pattern SHOWN_PICK_VERB = Pattern.compile(
            "^(?:请|帮我|麻烦|给我)?\\s*(?:选择|挑选|套上|换成|切换|改成|选好|载入|加载|应用|使用|就|要|选|用|拿|套|上|来|换)\\s*\\S+");
    /** 量词/单位：光有"3张""2次"这类说法时不当作编号挑选，交给模型（多半是要生成几张）。 */
    private static final Pattern SHOWN_COUNTER = Pattern.compile("^(张|张图|张图片|次|份|分钟|秒|块|点|条|项|个|号)$");

    /**
     * 用户整句只在指代刚看过的编号列表时，直接由程序把编号翻成对应指令——不再依赖模型猜是哪份列表。
     * 这一层覆盖"刚搜完 Civitai，模型却按本地 LoRA 列表解释 #2"以及预设/采样方法/基础模型/分类目录
     * 等所有列表：编号属于哪份列表、该用哪条指令，都由 {@code last_list} 决定。
     *
     * <p>除了纯编号，还接受"选择 sy军"、"用白河沙滩"、"#62 sy军营"这类"动词 + 编号/名字"的挑选句：
     * 名字唯一就由程序定编号；名字与给定编号对不上就**拒绝执行**并要求确认——实测模型看不见列表
     * 末尾（曾经只给到 #60）时会编造"#62 = sy军营"，用户照着发 #62 就套上了完全不相干的样式。
     */
    public static ShownSelection resolveShownSelection(JsonObject choices, String message) {
        if (choices == null || !choices.has("last_list") || !choices.get("last_list").isJsonObject()) return null;
        String text = speakableNumbers(message == null ? "" : message).strip();
        JsonObject list = choices.getAsJsonObject("last_list");
        String kind = Json.str(list, "kind", "");
        String action = listAction(kind);
        if (action == null || action.isBlank()) return null;
        JsonArray items = list.has("items") && list.get("items").isJsonArray() ? list.getAsJsonArray("items") : new JsonArray();
        if (items.isEmpty()) return null;
        List<String> labels = new ArrayList<>();
        for (JsonElement item : items) labels.add(item.getAsString().replaceFirst("^#[0-9]{1,3}\\s+", ""));

        java.util.regex.Matcher plain = SHOWN_REFERENCE.matcher(text);
        if (plain.matches()) {
            int index = Integer.parseInt(plain.group(1));
            if (index < 1 || index > labels.size()) return ShownSelection.ask("这份列表只有 " + labels.size() + " 项，没有 #" + index + "。");
            return new ShownSelection(action.replace("#N", "#" + index), labels.get(index - 1));
        }
        java.util.regex.Matcher pick = SHOWN_PICK.matcher(text);
        if (!pick.matches()) return null;
        // 提问句（"第二个有什么特点"）里也会出现编号加一串字：那不是挑选，交给模型去回答。
        if (text.matches("(?s).*(有什么|怎么样|怎么|为什么|是不是|能不能|可不可以|吗[？?]?|呢[？?]?|？|\\?).*")) return null;
        Integer index = pick.group(1) == null ? null : Integer.parseInt(pick.group(1).replace("#", ""));
        String asked = pick.group(3) == null ? "" : pick.group(3).strip();
        String needle = normalizeName(asked);
        boolean measure = pick.group(2) != null && !pick.group(2).isBlank();
        // 没有挑选动词时：只认"编号（可带量词）"或"编号 + 名字"（"62个"、"#62 sy军营"）。
        // "3张"这种数量说法不能被当成编号挑选，光报一个名字也交给模型。
        boolean hasVerb = SHOWN_PICK_VERB.matcher(text).find();
        if (!hasVerb && (index == null || (needle.isEmpty() && !measure) || SHOWN_COUNTER.matcher(needle).matches())) return null;
        if (index != null) {
            if (index < 1 || index > labels.size())
                return ShownSelection.ask("你说的是 #" + index + "，但这份列表只有 " + labels.size() + " 项。");
            String label = labels.get(index - 1);
            // 编号与名字对不上：绝不执行，先把真实编号报出来。
            if (!needle.isEmpty() && !nameMatches(needle, label))
                return ShownSelection.ask("编号对不上：#" + index + " 实际是「" + label + "」，不是「" + asked + "」。"
                        + "\n要哪一个直接说编号或名字，我再动手（你的提示词这次没有被改动）。");
            return new ShownSelection(action.replace("#N", "#" + index), label);
        }
        if (needle.isEmpty()) return null;
        // 完全相等优先：用户打全名时不该被别的"名字里包含它"的条目抢走。
        for (int at = 0; at < labels.size(); at++)
            if (normalizeName(labels.get(at)).equals(needle))
                return new ShownSelection(action.replace("#N", "#" + (at + 1)), labels.get(at));
        List<Integer> matches = new ArrayList<>();
        for (int at = 0; at < labels.size(); at++) if (nameMatches(needle, labels.get(at))) matches.add(at + 1);
        if (matches.size() == 1) {
            int at = matches.get(0);
            return new ShownSelection(action.replace("#N", "#" + at), labels.get(at - 1));
        }
        if (matches.size() > 1) {
            StringBuilder options = new StringBuilder();
            for (int at : matches) options.append("\n#").append(at).append(" ").append(labels.get(at - 1));
            return ShownSelection.ask("「" + asked + "」在这份列表里对上了 " + matches.size() + " 项，说个编号：" + options);
        }
        return null;
    }

    /**
     * 修 bug 2（对齐 TS 版已修的做法）：把"编号指代 + 后续动作"的句子切成两半。
     * 「选择第二个然后生成」里的"然后生成"是动作、不是名字；以前 SHOWN_PICK 把整句当成"编号 + 名字"，
     * 与 #2 的真实名称对不上，于是回一句"编号对不上…"的澄清，用户的复合要求落空。
     *
     * <p>先把"第二个"这类说法转成 #2，再用正则找**第一个**编号指代：head 是到编号为止的那截，
     * tail 是后面那截去掉开头连接词后的剩余。tail 为空（就是纯指代）时返回 null，调用方保持原行为。
     */
    static String[] splitNumberedFollowUp(String message) {
        String text = speakableNumbers(DeepSeekPrompts.currentRequest(message == null ? "" : message)).strip();
        if (text.isEmpty()) return null;
        Matcher marker = Pattern.compile("(?:#\\s*[0-9]{1,3}|第\\s*[0-9]{1,3}\\s*(?:个|张|条|项|号)?)").matcher(text);
        if (!marker.find()) return null;
        String head = text.substring(0, marker.end()).strip();
        String tail = text.substring(marker.end()).strip()
                .replaceFirst("^(?:然后|接着|之后|最后|顺便|并且|同时|并|再)\\s*", "").strip();
        if (tail.isEmpty()) return null;
        return new String[]{head, tail};
    }

    /**
     * 编号指代 + 后续出图动作拼成一条链：「选择第二个然后生成」→ [".style load #2", ".gen"]。
     * tail 交给现成的 {@link DeepSeekPrompts#pureGenerationPlan} 判断：只有它确实"就是个出图动作"
     * （否定句"先不要生成"会让它返回 null）时才拼链；编号对不上或名字有歧义时返回 null，
     * 由调用方原来的单步路径给出澄清，绝不假装执行。
     */
    public static ChatActions.Plan numberedFollowUpPlan(JsonObject choices, String message) {
        String[] split = splitNumberedFollowUp(message);
        if (split == null) return null;
        ChatActions.Plan followUp = DeepSeekPrompts.pureGenerationPlan(split[1]);
        if (followUp == null) return null;
        ShownSelection direct = resolveShownSelection(choices, split[0]);
        if (direct == null || direct.clarification()) return null;
        List<String> commands = new ArrayList<>();
        commands.add(direct.command());
        commands.addAll(followUp.commands());
        return new ChatActions.Plan("好，就按你刚看过的列表来：" + direct.label() + "；" + followUp.reply(),
                commands, "", 100);
    }

    /** 名字比对用的归一化：去掉空格/下划线/标点，统一小写。 */
    private static String normalizeName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-·、,，。.()（）\\[\\]【】|/\\\\]+", "");
    }

    private static boolean nameMatches(String needle, String label) {
        if (needle.isEmpty() || label == null) return false;
        String hay = normalizeName(label);
        // 只认"条目名字里包含用户说的那段"：反过来（用户说得更长）会把 "sy军" 误配到 "sy" 上。
        return !hay.isEmpty() && hay.contains(needle);
    }
    /**
     * "下载第一个/#3"指的是最近一次 Civitai 搜索的编号。搜索结果只存在于内存里（重启即失效），
     * 此时必须如实说明并让他重新搜索——绝不能把编号改派给本机 LoRA 列表去"加载"，
     * 那会加载一个完全不相干的模型（实测："搜索森野精华"→重启→"下载第一个"→加载了本机第 1 个 LoRA）。
     */
    /**
     * 依据审查：这次新加的每个词条都要能说清来源，并写进 {@code reasons}（供回执逐条列出）。
     * 来源只认三种：① 前置拆解某一条简单修改对应的必用词条；② 场景扩写（本身已按用户措辞过滤）；
     * ③ 词条候选（按用户中文措辞严格匹配出来的）。三者都对不上的新词条无法解释，直接移除并记入 {@code removed}。
     */
    static String auditAdditions(String positive, String original, PromptUsage usage, Set<String> allowed,
                                 List<String> texts, SceneDecomposer.Scene scene,
                                 List<String> sceneTags, List<String> expanded,
                                 List<String> reasons, List<String> removed) {
        Map<String, String> source = new LinkedHashMap<>();
        if (sceneTags != null) for (String tag : sceneTags) source.putIfAbsent(PromptEditor.key(tag), "词条候选（按你的中文措辞匹配）");
        if (expanded != null) for (String tag : expanded) source.putIfAbsent(PromptEditor.key(tag), "场景扩写（按你的措辞收敛）");
        if (scene != null)
            for (int index = 0; index < scene.parts().size(); index++) {
                SceneDecomposer.Part part = scene.parts().get(index);
                for (String tag : part.tags()) source.putIfAbsent(PromptEditor.key(tag), "拆解第 " + (index + 1) + " 条：" + part.text());
            }
        // 你的措辞本身能对上的词条同样算有依据（模型自己写的 on_stomach、lying_on_person 也说得通）。
        if (usage != null && texts != null)
            for (String text : texts) {
                if (text == null || text.isBlank()) continue;
                try {
                    for (String tag : usage.strictHints(text, allowed, 20))
                        source.putIfAbsent(PromptEditor.key(tag), "你的措辞「" + text + "」对应");
                } catch (Exception ignored) { }
            }
        List<String> kept = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String term : PromptEditor.parts(positive == null ? "" : positive)) {
            String key = PromptEditor.key(term);
            if (!seen.add(key)) continue;
            if (!usesTerm(original, term)) {
                String reason = source.get(key);
                if (reason == null) { if (removed != null) removed.add(term + "（没有任何拆解或要求依据）"); continue; }
                if (reasons != null) reasons.add(term + "　← " + reason);
            }
            kept.add(term);
        }
        return String.join(", ", kept);
    }    public String staleDownloadGuidance(JsonObject event, String message) {
        String text = speakableNumbers(message == null ? "" : message).strip();
        if (text.isEmpty()) return null;
        if (!text.matches("(?s).*(下载|download).*")) return null;
        if (!text.matches("(?s).*#[0-9]{1,3}.*")) return null;
        if (!loraSearches.getOrDefault(conversation(event), List.of()).isEmpty()) return null;
        return "这次没有可用的 Civitai 搜索结果：搜索编号只保留在内存里，重启或换一次搜索后就失效了，我没有动你的 LoRA。\n"
                + "要下载就先重新搜索：.lora query <关键词>，再发 .lora download #编号。\n"
                + "如果你其实是想用本机已下载的 LoRA：先 .lora list 看编号，再发 .lora load #编号。";
    }
    /**
     * 用户只说了"第 N 个/#N/就它"这类指代、而模型没给出任何指令时，把最近展示的那份列表连同它对应的动作
     * 再强调一次，让模型补出指令。这条兜底覆盖"刚搜完 Civitai，机器人却反问要哪个列表"这一类上下文断裂：
     * 只要用户确实在指代列表条目，就不该用一句澄清把编号丢掉。
     */
    public static String selectionNudge(JsonObject choices, String message) {        if (choices == null || !choices.has("last_list") || !choices.get("last_list").isJsonObject()) return null;
        String text = speakableNumbers(message == null ? "" : message).strip();
        if (text.isEmpty()) return null;
        java.util.regex.Matcher index = Pattern.compile("#([0-9]{1,3})").matcher(text);
        boolean numbered = index.find();
        boolean pointer = text.matches("(?s).*(就它|选它|就要它|这个|那个|刚才那个|上面那个|我选的|我挑的|我查的|继续|接着用|还是用).*");
        if (!numbered && !pointer) return null;
        JsonObject list = choices.getAsJsonObject("last_list");
        String kind = Json.str(list, "kind", "");
        String title = Json.str(list, "title", listTitle(kind));
        String action = Json.str(list, "action", Objects.requireNonNullElse(listAction(kind), ""));
        StringBuilder hint = new StringBuilder("系统提示：用户刚看过一份编号列表「").append(title).append("」（kind=").append(kind).append("），内容：");
        JsonArray items = list.has("items") && list.get("items").isJsonArray() ? list.getAsJsonArray("items") : new JsonArray();
        for (int at = 0; at < items.size() && at < 20; at++) hint.append("\n").append(items.get(at).getAsString());
        hint.append("\n他这句话就是在指这份列表的条目。");
        if (!action.isBlank())
            hint.append("请直接输出 ").append(action).append("（把 #N 换成他说的编号）；不要重新输出列表指令，也不要反问是哪个列表。");
        else
            hint.append("请按他指的编号处理这份列表，不要重新列一遍，也不要反问是哪个列表。");
        return hint.toString();
    }
    /**
     * "第 71 个样式" means the same as "#71". The planner only understands #N, so spoken ordinals are
     * rewritten before planning; Chinese numerals up to 99 are converted as well.
     */
    static String speakableNumbers(String text) {
        if (text == null || text.isBlank()) return text;
        // 区间（"第12到第16项"、"第十二至第十六"）先合并成 #12-#16，再处理单个"第 N 个"。
        Matcher range = Pattern.compile("第\\s*([0-9]{1,3}|[一二三四五六七八九十百]{1,4})\\s*(?:个|条|项|号)?\\s*(?:到|至|~|～|-|—|–)\\s*第?\\s*([0-9]{1,3}|[一二三四五六七八九十百]{1,4})\\s*(?:个|条|项|号)?")
                .matcher(text);
        StringBuilder ranged = new StringBuilder();
        while (range.find()) {
            int from = ordinal(range.group(1)), to = ordinal(range.group(2));
            if (from <= 0 || to <= 0 || to < from) { range.appendReplacement(ranged, Matcher.quoteReplacement(range.group(0))); continue; }
            range.appendReplacement(ranged, "#" + from + "-#" + to);
        }
        range.appendTail(ranged);
        Matcher matcher = Pattern.compile("第\\s*([0-9]{1,3}|[一二三四五六七八九十百]{1,4})\\s*(?:个|条|项|号)").matcher(ranged.toString());
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int value = ordinal(matcher.group(1));
            if (value <= 0) continue;
            matcher.appendReplacement(out, "#" + value);
        }
        matcher.appendTail(out);
        return out.toString();
    }
    /** 71 / 七十一 / 十一 / 十 → 71 / 71 / 11 / 10. */
    static int ordinal(String token) {
        if (token == null || token.isBlank()) return -1;
        if (token.matches("[0-9]{1,3}")) return Integer.parseInt(token);
        if (token.indexOf('百') >= 0) return -1;   // 三位以上不常见，交给模型处理
        String digits = "一二三四五六七八九";
        int tens = 0, ones = 0;
        int tenIndex = token.indexOf('十');
        if (tenIndex >= 0) {
            String before = token.substring(0, tenIndex), after = token.substring(tenIndex + 1);
            tens = before.isEmpty() ? 1 : digits.indexOf(before.charAt(0)) + 1;
            ones = after.isEmpty() ? 0 : digits.indexOf(after.charAt(0)) + 1;
            if (tens <= 0 || ones < 0) return -1;
            return tens * 10 + ones;
        }
        if (token.length() != 1) return -1;
        int value = digits.indexOf(token.charAt(0)) + 1;
        return value;
    }
    private String select(JsonObject event, String kind, String value) throws Exception {
        if (!value.startsWith("#")) return value;
        int index;
        try { index = Integer.parseInt(value.substring(1)) - 1; } catch (NumberFormatException e) { index = -1; }
        if (index < 0) throw new IllegalArgumentException("编号无效：请写成 #1 这样的编号。");
        // 网页控制台的编号来自 /api/styles、/api/loras 这类接口，不是"用户在聊天里看过的那份列表"：
        // 直接按**实时资源**解析——.style load #2 就是一次普通 CRUD，不需要先 .style list，也不会因为
        // 列表变过就报"编号已变化"（这正是控制台点"载入/删除"一直失败的原因）。
        List<String> live = Json.bool(event, "webui", false) ? liveSelection(event, kind) : null;
        if (live != null && !live.isEmpty()) {
            if (index >= live.size()) throw new IllegalArgumentException("编号无效，当前 " + kind + " 列表只有 " + live.size() + " 项。");
            numbered(event, kind, live);
            return live.get(index);
        }
        List<String> names = selections.getOrDefault(conversation(event) + ":" + kind, List.of());
        if (names.isEmpty()) {
            // The user does not have to run ".style list" first: the program looks the list up itself.
            names = internalSelection(event, kind);
            if (!names.isEmpty()) numbered(event, kind, names);
        }
        if (!names.isEmpty() && index < names.size()) {
            String candidate = names.get(index);
            // 旧快照里的这一项已经不在实时列表里（刚删过、刚下载过）：按实时列表重新解析，而不是报"编号已变化"。
            List<String> fresh = liveSelection(event, kind);
            if (fresh == null || fresh.isEmpty() || containsIgnoreCase(fresh, candidate)) return candidate;
            Log.info(kind + " 编号 " + value + " 对应的「" + candidate + "」已不在实时列表，改按实时列表解析");
            if (index >= fresh.size()) throw new IllegalArgumentException("编号无效，当前 " + kind + " 列表只有 " + fresh.size() + " 项。");
            numbered(event, kind, fresh);
            return fresh.get(index);
        }
        throw new IllegalArgumentException("编号无效，当前 " + kind + " 列表只有 " + names.size() + " 项。");
    }
    /** 实时列表（用于核对/解析编号）；不支持的种类或查询失败返回 null，调用方保持原行为。 */
    private List<String> liveSelection(JsonObject event, String kind) {
        try {
            switch (kind) {
                case "style" -> { return styleCatalog(); }
                case "lora" -> { return sd.loras().stream().map(SdClient.Lora::name).toList(); }
                case "function" -> { return new PromptFunctions(settings.root).names(); }
                // VAE 也按实时列表解析：`.vae set #2` 与 `.vae list` 看到的编号一致。
                case "vae" -> { return vae.vaeList(); }
                // 提示词词条也按实时内容解析：网页上点某个词条的 #编号 不会再报"编号无效"。
                case "prompt" -> {
                    String positive = userPrompts.prompts(promptScope(event)).positive();
                    return positive == null ? List.<String>of() : new ArrayList<>(promptTerms(positive));
                }
                case "promptr" -> {
                    String negative = userPrompts.prompts(promptScope(event)).negative();
                    return negative == null ? List.<String>of() : new ArrayList<>(promptTerms(negative));
                }
                default -> { return null; }
            }
        } catch (Exception error) {
            Log.warn("实时读取 " + kind + " 列表失败（编号按旧快照解析）：" + error(error));
            return null;
        }
    }
    /** The live list behind a #number, fetched on demand when the conversation has no snapshot yet. */
    private List<String> internalSelection(JsonObject event, String kind) throws Exception {
        switch (kind) {
            case "style": return styleCatalog();
            case "lora": return sd.loras().stream().map(SdClient.Lora::name).toList();
            case "function": return new PromptFunctions(settings.root).names();
            case "vae": return vae.vaeList();
            default: return List.of();
        }
    }
    /** 样式目录：只有机器人自己的样式库（数据全部在 data/local-styles.json，直接套用到个人提示词）。 */
    List<String> styleCatalog() { return localStyles.names(); }
    static boolean containsIgnoreCase(List<String> values, String value) {
        if (value == null) return false;
        String wanted = value.strip();
        for (String item : values) if (item != null && item.strip().equalsIgnoreCase(wanted)) return true;
        return false;
    }
    /** Pre-fills the numbered list when a message mentions "#N" or "第N个", so the planner sees real names. */
    void primeNumberedSelections(JsonObject event, String text) {
        if (text == null || text.isBlank()) return;
        if (!text.matches("(?s).*(#[0-9]{1,3}|第\\s*[0-9一二三四五六七八九十]{1,3}).*")) return;
        List<String> kinds = new ArrayList<>();
        if (text.matches("(?s).*(样式|style|风格|预设).*")) kinds.add("style");
        if (text.matches("(?s).*(?i).*(lora|lo?ra).*")) kinds.add("lora");
        if (text.matches("(?s).*(提示词集|function).*")) kinds.add("function");
        if (kinds.isEmpty()) {
            // 没指明种类时沿用用户刚看过的那份列表：再抓第二种编号会出现两套编号体系
            // （用户在看样式，程序却顺手抓了 LoRA，于是 "#12" 指谁就说不清了）。
            // "下载第 N 个"指的是 Civitai 搜索结果的编号，绝不能因此去抓本机 LoRA 列表。
            if (text.matches("(?s).*(下载|download).*")) return;
            String prefix = conversation(event) + ":";
            Long seen = selectionTimes.get(conversation(event));
            boolean fresh = seen != null && System.nanoTime() - seen <= TimeUnit.SECONDS.toNanos(settings.selectionGapSeconds());
            if (fresh && (shownLists.containsKey(conversation(event))
                    || selections.keySet().stream().anyMatch(key -> key.startsWith(prefix)))) return;
            kinds.add("style"); kinds.add("lora");
        }
        for (String kind : kinds) {
            try {
                if (selections.containsKey(conversation(event) + ":" + kind)) continue;
                List<String> names = internalSelection(event, kind);
                if (!names.isEmpty()) { numbered(event, kind, names); Log.info("按需查询 " + kind + " 列表（" + names.size() + " 项）以解析编号"); }
            } catch (Exception error) { Log.warn("按需查询 " + kind + " 列表失败：" + error(error)); }
        }
    }
    private String selectedArguments(JsonObject event, String kind, String arguments) throws Exception {
        Matcher m = Pattern.compile("^(load|prompt|show|remove|delete|overwrite|set)\\s+(#[0-9]+)$", Pattern.CASE_INSENSITIVE).matcher(arguments);
        return m.matches() ? m.group(1) + " " + select(event, kind, m.group(2)) : arguments;
    }
    private void usage(JsonObject event, String query) throws Exception {
        String selection = select(event, "usage", query.strip());
        if (selection.startsWith("tag:")) { reply(event, selection.substring(4)); return; }
        String output = new PromptUsage(settings.root).browse(selection);
        List<String> choices = new ArrayList<>(); List<String> lines = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            if (line.startsWith(".usage ") || line.startsWith("/usage ")) { choices.add(line.substring(7)); line = "#" + choices.size() + " " + line; }
            else if (line.contains(" — ") && !line.startsWith("prompt — ")) { choices.add("tag:" + line); line = "#" + choices.size() + " " + line; }
            lines.add(line);
        }
        if (!choices.isEmpty()) numbered(event, "usage", choices);
        reply(event, String.join("\n", lines));
    }
    private final ThreadLocal<Boolean> chatDispatchFailed = new ThreadLocal<>();
    /** While a .batch runs, per-step receipts are collected and sent as one consolidated message. */
    private final ThreadLocal<List<String>> batchReceipts = new ThreadLocal<>();
    JsonObject selectionContext(JsonObject event) {
        JsonObject result = new JsonObject();
        // 当前 prompt 的**词条分类**：模型据此理解"只保留人物和服饰，其余清空"这类按类别的要求，
        // 并且能把用户的说法映射成 /prompt keep|drop 的类别名。与编号列表的有效期无关，永远给出。
        try {
            SdClient.Prompts prompts = userPrompts.prompts(promptScope(event));
            List<String> parts = PromptEditor.parts(prompts.positive() == null ? "" : prompts.positive());
            JsonObject terms = new JsonObject();
            terms.addProperty("count", parts.size());
            terms.addProperty("positive", prompts.positive() == null ? "" : prompts.positive());
            JsonObject grouped = new JsonObject();
            for (Map.Entry<String, List<String>> entry : TermCategories.group(usageIndex(), parts).entrySet())
                grouped.add(entry.getKey(), Json.GSON.toJsonTree(entry.getValue()));
            terms.add("categories", grouped);
            terms.add("category_names", Json.GSON.toJsonTree(TermCategories.ORDER));
            terms.addProperty("hint", "按类别操作提示词时用 /prompt keep <类别…>（只留这些，其余清空）或 /prompt drop <类别…>（删掉这些）；"
                    + "类别名用这里的键（人物/服装/动作/姿势/表情/场景/环境/镜头/画面/物品…），同义说法如 环境=场景+环境、视角=镜头、服饰=服装。");
            result.add("prompt_terms", terms);
        } catch (Exception ignored) { /* 提示词读不到时就不给这份上下文 */ }
        String prefix = conversation(event) + ":";
        // Numbers and lists from an expired topic must not be offered to the planner any more.
        Long seen = selectionTimes.get(conversation(event));
        if (seen == null || System.nanoTime() - seen > java.util.concurrent.TimeUnit.SECONDS.toNanos(settings.selectionGapSeconds()))
            return result;
        selections.forEach((key, values) -> {
            if (key.startsWith(prefix)) result.add(key.substring(prefix.length()), Json.GSON.toJsonTree(values.stream().limit(300).toList()));
        });
        List<CivitaiClient.SearchResult> search = loraSearches.get(conversation(event));
        if (search != null) result.add("civitai", Json.GSON.toJsonTree(search.stream().map(CivitaiClient.SearchResult::url).toList()));
        // 搜索的页码：编号是**本页**编号（`.lora download #N` 指本页第 N 条），别让模型理解成"全部结果的第 N 条"。
        CivitaiClient.SearchPage page = loraPages.get(conversation(event));
        if (page != null && !page.results().isEmpty()) {
            JsonObject info = new JsonObject();
            info.addProperty("page", page.page());
            info.addProperty("pageSize", page.pageSize());
            info.addProperty("count", page.count());
            info.addProperty("hasMore", page.hasMore());
            info.addProperty("query", page.query());
            if (page.totalPagesKnown()) info.addProperty("totalPages", page.totalPages());
            info.addProperty("hint", "编号是本页编号：.lora download #N 指这一页的第 N 条；翻页用 .lora search <词> <页码>");
            result.add("civitai_page", info);
        }
        // 用户刚看过的那份编号列表要单独标出来：它是"第 N 个 / #N"的第一解释，别的列表只是可选种类。
        ShownList shown = shownLists.get(conversation(event));
        if (shown != null) {
            JsonObject list = new JsonObject();
            list.addProperty("kind", shown.kind());
            list.addProperty("title", listTitle(shown.kind()));
            String action = listAction(shown.kind());
            if (action != null) list.addProperty("action", action);
            list.addProperty("hint", "用户说的「第 N 个 / #N / 这个 / 那个 / 就它」首先指这份列表：不要重新列一遍表，也不要用别的列表的编号");
            // 完整给出列表：曾经只给到 #60，模型看不见末尾就编造"#62 = 某个名字"，用户照着发编号会套错东西。
            list.addProperty("total", shown.labels().size());
            JsonArray items = new JsonArray();
            // 不设上限：列表有多长就给多长，模型看不见末尾时会编造编号（实测"#62"被说成另一个名字）。
            for (int index = 0; index < shown.labels().size(); index++)
                items.add("#" + (index + 1) + " " + shown.labels().get(index));
            list.add("items", items);
            result.add("last_list", list);
        }
        return result;
    }
    /**
     * 今日老婆 / 强娶 / 离婚。数据按会话（群）分开存放，回执带群名、昵称、QQ 号与头像图片。
     */
    private void marriage(JsonObject event, String text) throws Exception {
        String conversation = marriageKey(event);
        String requester = Json.str(event, "user_id", "");
        Marriage store = new Marriage(settings.root);
        String groupName = groupName(event);
        if (text.matches("(?i)^[./](jrlp|wife)(?:\\s+.*)?$")) {
            List<Marriage.Member> members = groupMembers(event);
            List<Marriage.Member> candidates = new ArrayList<>();
            for (Marriage.Member member : members)
                if (!member.qq().equals(requester) && !member.qq().equals(Json.str(event, "self_id", ""))) candidates.add(member);
            if (candidates.isEmpty()) { reply(event, "这一轮没找到可以抽的群友，稍后再试。"); return; }
            Marriage.Member picked = candidates.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));
            store.recordDraw(conversation, requester, picked.qq());
            Log.info("今日老婆（" + conversation + "）：" + requester + " → " + picked.qq());
            sendMarriageCard(event, crypto("今日老婆",
                    "抽取者 " + requester + " 抽到了 " + picked.name() + "（" + picked.qq() + "）", conversation), groupName, picked,
                    "今天的老婆就是这位啦，快去打招呼吧。");
            return;
        }
        if (text.matches("(?i)^[./](结婚|marry|propose)(?:\\s+[\\s\\S]*)?$")) {
            String value = text.replaceFirst("(?i)^[./](结婚|marry|propose)", "").strip();
            if (value.isBlank()) value = Objects.requireNonNullElse(mentionedUser(event), "");
            int window = settings.marriageProposalSeconds();
            if (value.isBlank()) {
                // No target: report the invitations that are actually pending for the caller.
                Marriage.Proposal incoming = store.proposalFor(conversation, requester);
                Marriage.Proposal outgoing = store.proposalFrom(conversation, requester);
                if (incoming != null) {
                    reply(event, memberInfo(event, incoming.from()).name() + "（" + incoming.from() + "）向你求婚，"
                            + "请在剩余 " + seconds(incoming) + " 秒内回复 .同意 或 .拒绝。");
                    return;
                }
                if (outgoing != null) {
                    reply(event, "你已向 " + memberInfo(event, outgoing.to()).name() + "（" + outgoing.to() + "）求婚，"
                            + "等待对方在剩余 " + seconds(outgoing) + " 秒内回复 .同意 或 .拒绝。");
                    return;
                }
                reply(event, "用法：.结婚 <@某人|QQ号|群名片>；双方都须未婚配，对方须在 " + window + " 秒内回复 .同意。"
                        + "\n（@ 需要与 .结婚 写在同一条消息里。）");
                return;
            }
            String target = resolveUser(event, value);
            Marriage.Outcome outcome = store.propose(conversation, requester, target, window);
            if (!outcome.ok()) { reply(event, "求婚失败：" + outcome.reason()); return; }
            Marriage.Member member = memberInfo(event, target);
            Log.info("已求婚（" + conversation + "）：" + requester + " → " + target + "（" + window + " 秒内回应）");
            sendMarriageCard(event, crypto("求婚",
                    requester + " 向 " + member.name() + "（" + target + "）求婚", conversation), groupName, member,
                    "请在 " + window + " 秒内回复 .同意 或 .拒绝，逾期这次求婚就作废啦。");
            return;
        }
        Boolean agree = consentWord(text.replaceFirst("(?i)^[./]", "").strip().split("\\s+", 2)[0]);
        if (agree != null) {
            Marriage.Outcome outcome = agree ? store.accept(conversation, requester, settings.marriageProposalSeconds())
                    : store.reject(conversation, requester);
            if (!outcome.ok()) { reply(event, (agree ? "同意失败：" : "拒绝失败：") + outcome.reason()); return; }
            if (!agree) {
                String from = outcome.proposal().from();
                Log.info("求婚被拒（" + conversation + "）：" + from + " ✗ " + requester);
                reply(event, "已拒绝 " + memberInfo(event, from).name() + "（" + from + "）的求婚。");
                return;
            }
            showCouple(event, conversation, groupName, outcome.couple().a(), outcome.couple().b());
            return;
        }
        if (text.matches("(?i)^[./](强娶|force)(?:\\s+[\\s\\S]*)?$")) {
            String value = text.replaceFirst("(?i)^[./](强娶|force)", "").strip();
            // An @segment never appears in the plain text, so ".强娶 @某人" arrives with an empty argument:
            // read the mention straight from the event before deciding the command was malformed.
            if (value.isBlank()) value = Objects.requireNonNullElse(mentionedUser(event), "");
            if (value.isBlank()) {
                reply(event, "用法：.强娶 <@某人|QQ号|群名片>；每人每天一次，只能强娶未婚配的人。"
                        + "\n（@ 需要与 .强娶 写在同一条消息里。）");
                return;
            }
            String target = resolveUser(event, value);
            Marriage.Outcome outcome = store.forceMarry(conversation, requester, target);
            if (!outcome.ok()) { reply(event, "强娶失败：" + outcome.reason()); return; }
            Marriage.Member member = memberInfo(event, target);
            Log.info("强娶成功（" + conversation + "）：" + requester + " → " + target);
            sendMarriageCard(event, crypto("强娶",
                    requester + " 强娶了 " + member.name() + "（" + target + "）", conversation), groupName, member,
                    "恭喜成亲！从今天起你们就是一对啦。");
            return;
        }
        if (text.matches("(?i)^[./](离婚|divorce)(?:\\s+.*)?$")) {
            Marriage.Outcome outcome = store.divorce(conversation, requester);
            if (!outcome.ok()) { reply(event, "离婚失败：" + outcome.reason()); return; }
            String partner = outcome.couple().b();
            Marriage.Member member = memberInfo(event, partner);
            Log.info("离婚（" + conversation + "）：" + requester + " 与 " + partner);
            sendMarriageCard(event, crypto("离婚",
                    requester + " 和 " + member.name() + "（" + partner + "）离婚了", conversation), groupName, member,
                    "缘分尽了就散了吧，各自安好。");
            return;
        }
        reply(event, "婚配指令：.结婚 <@某人|QQ号|群名片>（对方须在 " + settings.marriageProposalSeconds()
                + " 秒内 .同意）、.同意、.拒绝、.强娶 <@某人>、.离婚、.jrlp。");
    }

    /** 同意用语的整句匹配；普通聊天里只有等待该用户的求婚时才生效。 */
    private static final Pattern CONSENT = Pattern.compile("(?i)^(同意|我愿意|答应|接受|可以|好|好呀|行|yes|yeah|ok|accept)$");
    private static final Pattern REFUSAL = Pattern.compile("(?i)^(拒绝|不同意|我拒绝|不接受|不答应|不用|算了|不行|no|nope|reject)$");

    /** "同意"/"拒绝" 的判定：整句匹配才返回结论，其余一律返回 null。 */
    static Boolean consentWord(String text) {
        String word = text == null ? "" : text.strip().replaceAll("[\\s。！!~～、，,.？?]+$", "");
        if (CONSENT.matcher(word).matches()) return Boolean.TRUE;
        if (REFUSAL.matcher(word).matches()) return Boolean.FALSE;
        return null;
    }

    /**
     * 普通消息里的"同意"/"拒绝"：只有确实有等待该用户的求婚时才接管，否则交回聊天，不劫持日常对话。
     */
    private boolean marriageConsent(JsonObject event, String text) throws Exception {
        Boolean agree = consentWord(text);
        if (agree == null) return false;
        String conversation = marriageKey(event), user = Json.str(event, "user_id", "");
        Marriage store = new Marriage(settings.root);
        if (store.proposalFor(conversation, user) == null) return false;
        String groupName = groupName(event);
        Marriage.Outcome outcome = agree ? store.accept(conversation, user, settings.marriageProposalSeconds())
                : store.reject(conversation, user);
        if (!outcome.ok()) { reply(event, (agree ? "同意失败：" : "拒绝失败：") + outcome.reason()); return true; }
        if (!agree) {
            String from = outcome.proposal().from();
            Log.info("求婚被拒（" + conversation + "）：" + from + " ✗ " + user);
            reply(event, "已拒绝 " + memberInfo(event, from).name() + "（" + from + "）的求婚。");
            return true;
        }
        showCouple(event, conversation, groupName, outcome.couple().a(), outcome.couple().b());
        return true;
    }

    /** 结婚成功：回执带双方的昵称与 QQ，头像用发起求婚的一方。 */
    private void showCouple(JsonObject event, String conversation, String groupName, String proposer, String invitee) {
        Marriage.Member member = memberInfo(event, proposer), other = memberInfo(event, invitee);
        Log.info("结婚成功（" + conversation + "）：" + proposer + " ♥ " + invitee);
        sendMarriageCard(event, crypto("结婚",
                proposer + "（" + member.name() + "）和 " + invitee + "（" + other.name() + "）结婚了", conversation),
                groupName, member, "被邀请的一方已经同意，从今天起你们就是一对啦。");
    }

    /** 求婚剩余秒数（向上取整，至少 1）。 */
    private static long seconds(Marriage.Proposal proposal) {
        return Math.max(1, (proposal.remainingMillis() + 999) / 1000);
    }

    /** 回执：一条文字（含群名、昵称、QQ）加一张头像图片。 */
    private void sendMarriageCard(JsonObject event, String opening, String groupName, Marriage.Member member, String closing) {
        String heading = "【" + groupName + "】\n昵称：" + member.name() + "\nQQ：" + member.qq();
        try {
            JsonArray segments = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("type", "text");
            JsonObject textData = new JsonObject();
            textData.addProperty("text", opening + "\n" + heading + "\n" + closing);
            textPart.add("data", textData);
            segments.add(textPart);
            JsonObject imagePart = new JsonObject();
            imagePart.addProperty("type", "image");
            JsonObject imageData = new JsonObject();
            imageData.addProperty("file", Marriage.avatarUrl(member.qq()));
            imagePart.add("data", imageData);
            segments.add(imagePart);
            sender.send(event, segments).exceptionally(failure -> {
                Log.warn("婚配回执发送失败，改为纯文本：" + error(failure));
                reply(event, opening + "\n" + heading + "\n" + closing);
                return null;
            });
        } catch (Exception error) {
            reply(event, opening + "\n" + heading + "\n" + closing);
        }
    }

    /**
     * 回执开场：DeepSeek 生成的俏皮短句 + 事实本身。事实必须原样保留——模型只负责开场白，
     * 不能把"谁和谁结婚/强娶/离婚"这类关键信息顶掉（内置文案失败时同样带上事实）。
     */
    private String crypto(String scene, String fact, String conversation) {
        String fallback = switch (scene) {
            case "今日老婆" -> "来啦来啦，今天的缘分抽签结果——";
            case "求婚" -> "有人鼓起勇气开口了——";
            case "结婚" -> "成了成了，撒花——";
            case "强娶" -> "哦豁，动手挺快。";
            default -> "唉，又散了一对。";
        };
        try {
            // 俏皮开场属于对话文本，走聊天频道；婚配功能本身不依赖聊天开关。
            DeepSeekPrompts client = DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.CHAT);
            String reply = client.chat(settings.chatPersonality(), new JsonArray(),
                    "场景：" + scene + "；事实：" + fact + "。请用一到两句俏皮、口语化的中文作开场白，"
                            + "不要输出任何指令、不要提设定或规则、不要超过 60 字。");
            String opening = reply == null || reply.isBlank() ? fallback : reply.strip();
            return opening + "\n" + fact;
        } catch (Exception error) {
            Log.warn("婚配俏皮文案生成失败，使用内置文案：" + error(error));
            return fallback + "\n" + fact;
        }
    }

    private String groupName(JsonObject event) {
        String group = Json.str(event, "group_id", "");
        if (group.isBlank()) return "私聊";
        try {
            JsonObject params = new JsonObject(); params.addProperty("group_id", group);
            JsonElement data = sender.callApi("get_group_info", params).get(10, TimeUnit.SECONDS);
            if (data != null && data.isJsonObject()) {
                String name = Json.str(data.getAsJsonObject(), "group_name", "");
                if (!name.isBlank()) return name;
            }
        } catch (Exception error) { Log.warn("群名称查询失败：" + error(error)); }
        return "群 " + group;
    }

    private List<Marriage.Member> groupMembers(JsonObject event) {
        List<Marriage.Member> members = new ArrayList<>();
        String group = Json.str(event, "group_id", "");
        if (group.isBlank()) {
            members.add(memberInfo(event, Json.str(event, "user_id", "")));
            return members;
        }
        try {
            JsonObject params = new JsonObject(); params.addProperty("group_id", group);
            JsonElement data = sender.callApi("get_group_member_list", params).get(20, TimeUnit.SECONDS);
            if (data != null && data.isJsonArray()) for (JsonElement element : data.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject member = element.getAsJsonObject();
                String qq = Json.str(member, "user_id", "");
                if (qq.isBlank()) continue;
                String card = Json.str(member, "card", "");
                String name = card.isBlank() ? Json.str(member, "nickname", qq) : card;
                members.add(new Marriage.Member(qq, name, Marriage.avatarUrl(qq)));
            }
        } catch (Exception error) { Log.warn("群成员查询失败：" + error(error)); }
        if (members.isEmpty()) members.add(memberInfo(event, Json.str(event, "user_id", "")));
        return members;
    }

    private Marriage.Member memberInfo(JsonObject event, String qq) {
        String group = Json.str(event, "group_id", "");
        String name = qq;
        if (!group.isBlank()) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("group_id", group); params.addProperty("user_id", qq);
                JsonElement data = sender.callApi("get_group_member_info", params).get(10, TimeUnit.SECONDS);
                if (data != null && data.isJsonObject()) {
                    JsonObject member = data.getAsJsonObject();
                    String card = Json.str(member, "card", "");
                    name = card.isBlank() ? Json.str(member, "nickname", qq) : card;
                }
            } catch (Exception error) { Log.warn("群成员信息查询失败：" + error(error)); }
        }
        return new Marriage.Member(qq, name, Marriage.avatarUrl(qq));
    }

    void executeChatCommands(JsonObject event, List<String> commands, JsonObject choices) throws Exception {        ChatActions.validate(commands);
        if (closed.get() || !settings.allowed(event)) throw new IllegalArgumentException("当前会话无操作权限或机器人正在关闭。");
        // 一条指令里的多个 .infix 步骤先合成一步：默认（global）档下"一条指令只执行一次 infix"，
        // 否则每一步都拿整份提示词改写一次、互相删掉对方刚写进去的内容（实测 3 个 infix + 1 个 gen 的案例）。
        // parts 档不合并（旧行为完整保留）。
        commands = mergeInfixSteps(commands, event, settings);
        // 常规档：一条自然语言请求（可能拆成好几步）也只有一个收据窗口——整条链路最后只留一句肯定、
        // 或（有失败时）一句说明；图片照旧发。窗口挂在 context 上，异步步骤（.infix/.gen/LoRA 下载）
        // 带着同一份事件回来时仍然记在这条指令上。
        NormalWindow window = openNormalWindow(event);
        JsonObject context=attachNormalWindow(event.deepCopy());List<String> sequence=List.copyOf(commands);JsonObject frozen=choices.deepCopy();
        // 这一份 context 已经带着窗口 id：后面的步骤（包括换线程跑的异步步骤）都靠它认领同一条指令，
        // 本线程的 ThreadLocal 立刻收掉，免得留在池化线程上串到下一个会话。
        if (window != null && normalWindowHere.get() == window) normalWindowHere.remove();
        // 一条自然语言请求被拆成多步执行时，各步回执先收集起来，执行完合成一条聊天记录发送，
        // 不再把每一步都单独刷屏。
        ChainRecord record = sequence.size() > 1 ? new ChainRecord() : null;
        String recordKey = ChatService.conversationKey(context);
        if (record != null) chainRecords.put(recordKey, record);
        if(sequence.stream().anyMatch(ChatActions::asynchronous)) {
            // Tags known to be loaded: collected step by step, because ".style load" only introduces the LoRA
            // tag in the middle of the chain and a later rewrite must not be allowed to drop it.
            List<String> knownLoraTags = new ArrayList<>(loraTagsIn(userPrompts.prompts(promptScope(context)).positive()));
            // 但用户这次明确点名要删的那几个不在此列：删了就是删了，别再自动加回来。
            Set<String> keepRemoved = lorasRequestedRemoved(Json.str(context, "raw_message", ""), knownLoraTags);
            try { activeChatWorkflows.incrementAndGet(); chatWorkflows.execute(() -> {
                try { runChatCommands(context,sequence,frozen,knownLoraTags,record,keepRemoved); }
                catch(Exception e) { replyFailure(context,"多步骤指令已停止："+error(e)); }
                finally {
                    // Even when a step stopped the chain, a loaded LoRA tag must not be left missing.
                    restoreLoraTags(context, knownLoraTags, keepRemoved);
                    // 词库补齐（v2 链路）默认不再执行：自由改写由 DeepSeek 自己决定写什么，
                    // 程序不再拿标准词条去补/改它的结果。需要时见 backup\infix-v2-strict-*。
                    try {
                        String fixed = selfCheckPrompts(promptScope(context));
                        if (!fixed.isEmpty()) reply(context, "提示词自检修正：" + fixed);
                    } catch (Exception checkError) { Log.warn("提示词自检失败：" + error(checkError)); }
                    finishChainRecord(context, record, recordKey, sequence.size());
                    activeChatWorkflows.decrementAndGet();
                    releaseNormalWindow(context);          // 常规档：这条指令的收据到这里定稿
                    closeNormalWindow(window);
                }
            }); } catch(RejectedExecutionException e) {
                activeChatWorkflows.decrementAndGet();
                if (record != null) chainRecords.remove(recordKey);
                releaseNormalWindow(context);
                closeNormalWindow(window);
                throw new IllegalStateException("机器人正在关闭。");
            }
        } else {
            try { runChatCommands(context,sequence,frozen,new ArrayList<>(),record); }
            finally {
                finishChainRecord(context, record, recordKey, sequence.size());
                releaseNormalWindow(context);
                closeNormalWindow(window);
            }
        }
    }
    /**
     * 多步执行期间收集回执：每步的命令 + 该步产生的回执合成一个块，最后连同总结发成一条聊天记录。
     */
    private static final class ChainRecord {
        private final List<String> lines = new ArrayList<>();
        private final List<String> blocks = new ArrayList<>();
        private int succeeded;
        private int claimed;
        private String failure;
        void add(String line) { lines.add(line); }
        int size() { return lines.size(); }
        void step(String command, int from, String error) {
            if (error == null) succeeded++;
            StringBuilder block = new StringBuilder("【").append(error == null ? succeeded : succeeded + 1).append("】").append(command);
            if (error != null) { block.append("\n失败：").append(error); failure = command; }
            for (int index = from; index < lines.size(); index++) block.append("\n").append(lines.get(index).strip());
            blocks.add(block.toString());
            claimed = lines.size();
        }
        /** 链路收尾产生的回执（失败说明、LoRA 恢复、词条补齐、自检修正）也要进同一条聊天记录。 */
        String trailing() {
            if (lines.size() <= claimed) return "";
            StringBuilder rest = new StringBuilder("【收尾】");
            for (int index = claimed; index < lines.size(); index++) rest.append("\n").append(lines.get(index).strip());
            claimed = lines.size();
            return rest.toString();
        }
        String summary(int total) {
            if (failure != null)
                return "多步执行：" + succeeded + "/" + total + " 条（已在失败处停止：" + failure + "）";
            if (blocks.size() < total)
                return "多步执行：" + succeeded + "/" + total + " 条已执行（其余步骤未执行，原因见下）";
            return "多步执行完成：" + succeeded + "/" + total + " 条（全部成功）";
        }
        boolean empty() { return blocks.isEmpty() && lines.isEmpty(); }
        /** 每一步都跑到底了吗（有一步被跳过/提前返回就不算）。 */
        boolean complete(int total) { return blocks.size() >= total; }
        String text(int total) { return summary(total) + "\n" + String.join("\n", blocks); }
    }
    private final Map<String, ChainRecord> chainRecords = new ConcurrentHashMap<>();
    /** 执行结束：把这一步链路的回执合成一条聊天记录发送；失败时退回单条文本。 */
    private void finishChainRecord(JsonObject event, ChainRecord record, String key, int total) {
        if (record == null) return;
        chainRecords.remove(key);
        // 常规档：整条链路共用一个收据窗口（各步的收据已经在窗口里了），这里只补一句总结——
        // 全成功就是一句肯定，有一步失败就是那条失败说明（失败优先级最高，会把肯定顶掉）。
        NormalWindow window = normalWindowFor(event);
        if (window != null) {
            if (record.failure != null) {
                StringBuilder failure = new StringBuilder(record.summary(total));
                if (!record.blocks.isEmpty()) failure.append("\n").append(record.blocks.get(record.blocks.size() - 1).strip());
                window.offer(ReceiptRank.FAILURE, publicCommands(failure.toString()));
            } else {
                window.offer(ReceiptRank.AFFIRM, publicCommands(record.summary(total)));
            }
            return;
        }
        if (record.empty()) return;
        // 常规档只留"肯定"与图片：多步执行的"过程"（总结 + 每一步的命令与回执块，例如
        // 「多步执行完成：2/2 条（全部成功）」「【1】.prompt add …」）在全部成功时不再发。
        // 只要有任何一步失败（failure != null）或没跑完（blocks 少于 total，原因写在收尾里），
        // 就走下面原有的完整形态——错误、失败原因与"需要用户决定"的说明绝不吞。
        // 全部成功但收尾里有内容（LoRA 恢复、词条补齐、自检修正、多步骤指令已停止…）时，只发收尾那一段。
        if (quietReceipts(event) && record.failure == null && record.complete(total)) {
            String tail = record.trailing();
            if (!tail.isEmpty()) reply(event, tail);
            return;
        }
        List<JsonArray> messages = new ArrayList<>();
        // Every node is user-facing: commands must read with "." like every other receipt.
        messages.add(Maps.text(publicCommands(record.summary(total))));
        for (String block : record.blocks) messages.add(Maps.text(publicCommands(block)));
        String tail = record.trailing();
        if (!tail.isEmpty()) messages.add(Maps.text(publicCommands(tail)));
        sender.sendRecord(event, messages).exceptionally(error -> {
            Log.warn("多步回执合并发送失败，改为单条文本：" + error(error));
            reply(event, record.text(total));
            return null;
        });
    }
    private void runChatCommands(JsonObject event,List<String> commands,JsonObject choices) throws Exception {
        runChatCommands(event, commands, choices, new ArrayList<>(), null);
    }
    private void runChatCommands(JsonObject event,List<String> commands,JsonObject choices,List<String> knownLoraTags) throws Exception {
        runChatCommands(event, commands, choices, knownLoraTags, null, Set.of());
    }
    private void runChatCommands(JsonObject event,List<String> commands,JsonObject choices,List<String> knownLoraTags,ChainRecord record) throws Exception {
        runChatCommands(event, commands, choices, knownLoraTags, record, Set.of());
    }
    private void runChatCommands(JsonObject event,List<String> commands,JsonObject choices,List<String> knownLoraTags,ChainRecord record,
                                 Set<String> keepRemoved) throws Exception {
        for (String command : commands) {
            int receiptStart = record == null ? 0 : record.size();
            String canonical=internalCommand(command);
            // Prevent #1 silently switching to another item while the API request is in flight.
            Matcher indexed = Pattern.compile("^/(style|function|preset|sampler|model|promptR|prompt)\\s+(?:load|prompt|show|remove|delete|overwrite|set)\\s+#[0-9]+$", Pattern.CASE_INSENSITIVE).matcher(canonical);
            String kind = indexed.matches() ? indexed.group(1).toLowerCase(Locale.ROOT) : null;
            if (kind != null && kind.equals("promptr")) kind = "promptR";
            if (canonical.matches("(?i)^/usage\\s+#[0-9]+$")) kind = "usage";
            if (canonical.matches("(?i)^/lora\\s+load\\s+#[0-9]+(?:\\s+\\S+)?$")) kind = "lora";
            if (canonical.matches("(?i)^/lora\\s+download\\s+#[0-9]+(?:\\s+\\S+)?$")) kind = "civitai";
            if (kind != null && !Json.bool(event, "webui", false)) {
                JsonElement before = choices.get(kind), now = selectionContext(event).get(kind);
                if (before == null || !before.equals(now)) throw new IllegalArgumentException("查询编号在等待聊天回复时已变化，请重新查询后操作。");
            }
            JsonObject forwarded = event.deepCopy(); forwarded.remove("message_id");
            forwarded.addProperty("post_type", "message"); forwarded.addProperty("raw_message", command);
            forwarded.add("message", Maps.text(command));
            boolean asynchronous=ChatActions.asynchronous(command);String step=asynchronous?UUID.randomUUID().toString():"";
            CompletableFuture<Boolean> completion=asynchronous?new CompletableFuture<>():null;
            if(asynchronous) { forwarded.addProperty("_chat_workflow_step",step);chatWorkflowSteps.put(step,completion); }
            chatDispatchFailed.set(false);
            try {
                collectLoraTags(event, knownLoraTags);
                // A generation snapshots the prompt the moment it runs: every known LoRA tag must be back in
                // place before the ".gen" step of the same chain executes.
                if (canonical.matches("(?i)^/gen(?:\\s+[\\s\\S]*)?$")) restoreLoraTags(event, knownLoraTags, keepRemoved);
                accept(forwarded);
                if (Boolean.TRUE.equals(chatDispatchFailed.get())) return;
                if(asynchronous && !completion.get(2,TimeUnit.HOURS)) throw new IllegalStateException("异步步骤未成功，后续指令未执行："+command);
                collectLoraTags(event, knownLoraTags);
                if (record != null) record.step(canonical, receiptStart, null);
            } catch(TimeoutException e) { throw new IllegalStateException("异步步骤等待超时，后续指令未执行："+command); }
            catch(Exception e) { if (record != null) record.step(canonical, receiptStart, error(e)); throw e; }
            finally { chatDispatchFailed.remove();if(asynchronous)chatWorkflowSteps.remove(step); }
        }
    }
    /** Remembers every LoRA tag this chain has seen, including the one a style load added mid-chain. */
    private void collectLoraTags(JsonObject event, List<String> known) {
        if (known == null) return;
        try {
            for (String tag : loraTagsIn(userPrompts.prompts(promptScope(event)).positive()))
                if (!known.contains(tag)) known.add(tag);
        } catch (Exception ignored) { }
    }
    private void completeChatWorkflowStep(JsonObject event,boolean success) {
        if (event == null) return;                      // 控制台内部接口没有会话步骤要收尾
        String step=Json.str(event,"_chat_workflow_step","");CompletableFuture<Boolean> future=chatWorkflowSteps.get(step);
        if(future!=null) future.complete(success);
    }
    private final Set<String> seen = new LinkedHashSet<>();
    private static final Pattern PROMPT = Pattern.compile("^/(promptR|prompt)(?:\\s+(add|remove|set|clear|undo|classify|keep|drop|category)(?:\\s+([\\s\\S]*))?)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern USAGE = Pattern.compile("^/usage(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROGEN = Pattern.compile("^/progen(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SD_SETTINGS = Pattern.compile("^/(settings|sampler|style|size|preset|steps|cfg|seed|model|function|vae)(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SET_VALUE = Pattern.compile("^set(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAVE_STYLE = Pattern.compile("^(save|overwrite)(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_CONTENT = Pattern.compile("^(prompt|load)(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERATE_COMMAND = Pattern.compile("^/gen(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE);
    /** .imgmode：出图是否合成「聊天记录」（QQ 合并转发），按会话保存。 */
    private static final Pattern IMAGE_MODE_COMMAND = Pattern.compile("^/imgmode(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE);
    /** .mode：QQ 侧回执档位（normal 只发肯定与图片 / debug 完整回执＝现状），按会话保存。 */
    private static final Pattern QQ_MODE_COMMAND = Pattern.compile("^/mode(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LORA_COMMAND = Pattern.compile("^/lora(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_LORA = Pattern.compile("^(\"(?:[^\"\\\\]|\\\\.)*\")(?:\\s+(\\S+))?$");
    private static final Gson STRICT_JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Pattern PUBLIC_COMMAND_PREFIX=Pattern.compile("(?<![\\p{L}\\p{N}_:/])/(help|yh|liv|get|settings|chat|admin|char|batch|sampler|style|size|steps|cfg|seed|model|promptR|prompt|preset|function|lora|gen|rg|imgcnt|imgmode|mode|usage|map|progen|infix|progress|vae|sd)(?![\\p{L}\\p{N}_-])",Pattern.CASE_INSENSITIVE);
    public static final String HELP = publicCommands("""
        神户小鸟 · Pixiko（SD 生图机器人）　群聊、私聊均可
        群聊先用 @机器人或小鸟名字唤醒；同一用户此后 30 分钟内可连续对话并滚动续期。私聊无需唤名，指令仍直接响应。
        日常聊天可执行明确提出的 help 指令操作，沿用原权限；只讨论操作方法时不执行。
        群里只有被 @ 或叫名字（小鸟/小鳥/ことり/kotori）时才会回复；被点名后如果我愿意继续这段对话，30 分钟内你在这个会话里不 @ 也能接着聊。
        /chat — 查看聊天设置（仅 owner/admin）
        /chat toggle — 开关当前群/当前私聊的日常聊天，持久保存（仅 owner/admin；只影响聊天频道，不影响生图）
        /chat global on|off — 全局总开关（仅 owner；只影响聊天频道，生图指令始终可用）
        /chat model [名称] — 查看/设置聊天频道的模型（仅 owner；生图频道独立，不受影响）
        /chat infix <修改要求> — 用 DeepSeek 智能修改基础性格设定（仅 owner）
        /chat personality <基础性格设定> — 保存性格设定（仅 owner）
        /chat add <内容> — 将内容追加到当前性格设定末尾（仅 owner）
        /chat frequency <每分钟发言次数> — 聊天回复频率上限，0 为静默（仅 owner）
   /chat notice on|off — 开关上/下线播报是否发到主群（仅 owner/admin）
   /chat log on|off — 开关把 WARN/ERROR 日志同步到主群，便于远程观察（仅 owner/admin）
         /chat corpus on|off — 原作语料复现开关：命中原作问答时照搬小鸟那一句（仅 owner）
         /affinity — 查看你与我的好感度与分档；/affinity <QQ号> 查看某人（仅 owner）
        /batch <指令1> ; <指令2> ; … — 一条消息按顺序执行多条指令，逐条回执并汇总（最多 20 条）
        /admin — 查看用法
        /admin list — 查看 owner 与 admin 名单（仅 owner/admin）
        /admin add <@成员|QQ号|群名片> — 添加 admin（仅 owner）
        /admin remove <@成员|QQ号|群名片> — 移除 admin（仅 owner）
        /yh — 发送粤海地图
        /liv — 发送丽湖地图
        /prompt — 查看正向、反向 prompt 和数据来源
        /promptR — 查看反向 prompt
        /usage — 浏览提示词中文分类目录
        /usage <分类路径> — 逐级浏览，例如 /usage 服饰、/usage 服饰/上衣；叶级列出 prompt — 中文含义
        /usage 词库 [分类] [起始条数] — 浏览内置中文词库（中文—标准词条对照）的分类与词条，分页每页 40 条
        /usage 搜索 <中文或英文> [起始条数] — 在内置中文词库里搜索词条，例如 /usage 搜索 地铁、/usage 搜索 chikan
        /usage 词条 <词条或中文> — 看某个词条的词意、使用需求与注意事项，例如 /usage 词条 chikan、/usage 词条 3d背景
        /prompt add <prompt> — 追加正向 prompt
        /promptR add <prompt> — 追加反向 prompt
        /prompt remove <prompt> — 按完整词项匹配删除，支持唯一部分匹配、空格/下划线纠正，反馈实际删除内容
        /promptR remove <prompt> — 同上，操作反向 prompt
        /prompt set <whole-prompt> — 替换全部正向 prompt，必须提供内容
        /prompt clear — 清空正向 prompt
        /prompt classify — 把当前正向 prompt 的每个词条按类别列出（人物/服装/动作/姿势/表情/场景/环境/镜头/画面/物品…）
        /prompt keep <类别…> — 只保留这些类别，其余词条清空，例如 /prompt keep 人物 服饰（LoRA/嵌入标签始终保留）
        /prompt drop <类别…> — 删掉这些类别，例如 /prompt drop 环境 物品（可用 环境/视角/画风/衣服 这类说法）
        /prompt category [status|model on|off] — 复合短语兜底模型（DeepSeek）的开关与用量，默认开启，按会话保存
        /prompt undo — 正反向 prompt 一并回退，可连续回退多步（最多保留 20 步）
        /promptR set <whole-prompt> — 替换全部反向 prompt，必须提供内容
        /promptR clear — 清空反向 prompt
        /gen toggle — 开关每个任务完成后自动领取，默认开启，重启保留
        /char <角色名或关键词> — 在本机 LoRA 与 WebUI 样式中查找该角色，列出候选并询问是否应用
        /lora query <模型搜索词> [页码] — 搜索 Civitai，结果合并成一条聊天记录（每个节点带本页编号、封面与信息；/lora search 是同一个命令）
        /lora download #编号 [权重] — 下载最近搜索中**本页**的模型（#N 指本页第 N 条）
        /rg <数量> — 回溯最近的图片，按图片上限（.imgcnt）分批、发送方式跟随 .imgmode，不改变待领取列表
        /progress — 查看 SD WebUI 当前生成进度（第几步／百分比／预计剩余时间）与机器人队列状态
        /imgcnt <数量> — 每条聊天记录图片上限，默认 300，按任务分开发送
        /imgmode [record|single|auto] — 出图是否合成聊天记录（合并转发）：record 一律合并（一张也合并）、single 一律逐张普通发送、auto 自动（默认：多张合并、单张普通），按会话保存
        /mode [normal|debug] — 回执档位（按会话保存）：normal（常规）只发一句肯定与图片，不显示生成参数、不发多步/计数这类过程回执；debug（调试，默认）是完整回执。失败、被拒绝、权限不足与用法错误照旧发出
        /jrlp — 今日老婆：随机抽一位群友，回执带群名、昵称、QQ 与头像（每群数据独立）
        /结婚 <@某人|QQ号|群名片> — 向未婚配的群友求婚，对方须在 180 秒内回复 /同意 才成立
        /同意 — 同意最近一次向你的求婚（超过 180 秒失效，须重新求婚）
        /拒绝 — 拒绝最近一次向你的求婚
        /强娶 <@某人|QQ号|群名片> — 每人每天一次，只能强娶未婚配者
        /离婚 — 每天一次，解除自己的婚配关系
        /infix <修改要求> — 把要求交给 DeepSeek，由它按自己的判断改写你个人的正反向提示词并应用（允许自然语言短语；默认一次指令只改一次，落地前对整份提示词做一次画面检查）
        /infix mode [global|parts] — infix 档位（按会话保存，默认 global）：global 把一条指令里的所有改写要求合成一次、作用在整份提示词上；parts 是旧的分组多步实现；/infix conflict on|off 才是传统正反义词排斥器开关
        /infix filter — 查看标准词库约束状态；/infix filter on|off 按会话开启或关闭（默认关闭＝自由改写，仅 owner/admin）
        /infix conflict — 查看传统正反义词排斥器状态；/infix conflict on|off 按会话切换（默认关闭：矛盾交给上面那次整份提示词画面检查判断，仅 owner/admin）
        /progen <文字描述> — 通过 DeepSeek API 生成英文正反向提示词供查看，不自动修改当前 prompt
        /settings — 查看当前尺寸、采样方法、步数、CFG、种子、基础模型和数据来源
        /sampler — 查看当前采样方法
        /sampler list — 列出 WebUI 可用采样方法
        /sampler set <完整名称> — 修改采样方法，名称可含空格
        /style — 查看机器人的样式库（样式完全独立在机器人这边）
        /style list — 列出机器人样式库里的全部样式
        /style save <名称> — 把当前个人提示词保存为样式（含 LoRA 标签；只存机器人这边）
        /style overwrite <名称> — 覆盖同名样式
        /style import webui [overwrite] — 把 WebUI 里已有的预设样式一次性搬进机器人样式库（同名默认跳过）
        /style prompt <名称|#编号> — 查看样式原文的正向、反向 prompt
        /style load <名称|#编号> [nolora] — 用样式替换当前正反向 prompt；nolora 表示不加载样式里的 LoRA
        /style rename [overwrite] <旧名称|#编号|#6-#9> <新名称或前缀> — 样式改名
        /style delete <名称|#编号|#6-#9> — 删除样式
        /style category <名称|#编号|#6-#9> [分类名] — 查看或修改样式分类；分类名给 - 表示恢复自动分类（LoRA 附带样式一个 LoRA 一个分类）
        /size — 查看图片宽高（像素）
        /size set <宽> <高> — 修改宽高，例如 /size set 768 512
        /steps [set <步数>] — 查看或设置机器人迭代步数
        /cfg [set <数值>] — 查看或设置 CFG
        /seed [set <种子>] — 查看或设置种子；-1 为随机
        /model — 查看机器人当前基础模型
        /model list — 列出基础模型完整名称
        /model set <完整名称> — 指定后续生成使用的基础模型
        /model set auto — 不固定底模，每次提交任务时用 WebUI 当前模型（Forge Neo 的预设栈用这个）
        /model preset — 列出 Forge／Forge Neo 的预设（底模 + VAE + 文本编码器一栈一栈）
        /model preset <名字> — 切到某个预设，并采纳它自己的采样方法、调度器、尺寸、步数、CFG
        /vae [status|list|set <名字>|auto|none|check|fix] — VAE 可选设置：查看当前 VAE 与冲突判定，check 只检测（列出修复动作），fix 清掉冲突的额外模块并把 VAE 设回 Automatic
        /preset list — 查看已保存的参数预设
        /preset save <名称> — 保存当前尺寸、采样方法、步数、CFG、种子和基础模型
        /preset overwrite <名称> — 覆盖同名参数预设
        /preset show <名称> — 查看参数预设
        /preset load <名称> — 加载参数预设，用于后续提交的生成任务
        /preset remove <名称> — 删除参数预设
        /function list — 列出已保存的提示词集
        /function save <名称> — 保存当前正向、反向提示词为提示词集
        /function overwrite <名称> — 覆盖同名提示词集；使用中须先移出
        /function prompt <名称> — 查看提示词集内容
        /function load <名称> — 追加到当前正反向提示词末尾，不重复加入已有词项
        /function active — 查看已加载的提示词集
        /function remove <名称> — 整组移出该集合加入的词项，保留原有词项和其他集合共享的词项
        /function clear — 移出全部已加载提示词集
        /function delete <名称> — 删除已保存的提示词集；使用中须先移出
        /function rename [overwrite] <旧名称> <新名称> — 重命名提示词集定义并同步各用户的加载关联
        /function reset — 仅清除集合关联，不删除当前提示词，用于手工修改后的重新整理
        /lora download <Civitai链接> [权重] — 下载并启用 LoRA，同时将展示图提示词保存为“模型名 编号”样式
        /lora search <关键词> [页码] — 翻页搜索 Civitai（关键词以数字结尾时用双引号，如 /lora search "milf 2"）
        /lora status — 查看最近下载状态
        /lora list — 列出 WebUI 本地 LoRA
        /lora triggers <名称|#编号> — 查看已记下的触发词（原样列出、带 Civitai 页面与底模）；/lora triggers set <名称> <词1, 词2> 手动补录（仅管理员）
        /lora load <完整本地名称> [权重] — 重新加载或启用已有 LoRA
        /lora rename <旧本地名称> <新本地名称> — 重命名本地 LoRA 文件并同步你个人 prompt 的标签
        /gen [次数] — 按当前完整参数排队生成，默认 1 次；生成过程中不逐张通知，任务结束汇总
        /gen status|list — 查看队列：每个任务的状态（生成中/等待中/已挂起）、进度、已生成张数与来源会话
        /gen first #编号 — 把某个任务置顶为第一优先级，下一位就生成它
        /gen hold #编号 — 挂起某个任务（队列先跑别的；正在下发的这一张会跑完）
        /gen resume #编号 — 继续挂起的任务
        /gen cancel #编号|all — 取消某个任务或整个队列，已经生成的图片仍然可以领取
        /gen status — 查看队列（任务状态、进度、已生成张数）
        /get — 按命令任务与 imgcnt 分批领取；未完成任务仅预览不移除
        /sd — 查看 SD WebUI 与自启动状态（目录、启动入口、启动参数）
        /sd start — 现在就把 SD WebUI 拉起来（没在跑时），并等到接口可用
        /sd auto on|off — 开关"生成前发现 SD 没在跑就自动启动"（默认开启）
        /sd boot on|off — 开关"机器人启动时顺带启动 SD"（默认关闭）
        /map path — 查看地图路径（仅 owner/admin）
        /map set yh <路径> — 设置粤海地图文件或文件夹（仅 owner）
        /map set liv <路径> — 设置丽湖地图文件或文件夹（仅 owner）
        /help — 显示此帮助
        路径含空格可加双引号。目录按文件名发送，最多 10 张，每张最多 20MB。
        宽高均为 64–2048 的整数且为 8 的倍数，也支持 /size set 768x512。
        LoRA 权重默认为 1，范围 0–2；名称含空格可加双引号，末尾是数字时请加双引号。
        LoRA 下载或加载同一时刻只处理一个；启用用于下一次生成，触发词由你选择添加。
        生成次数为正整数，不设单次任务数和队列容量上限；按批次计数排队，不预先创建海量任务。
        生成任务按顺序逐个运行，采用发出指令时的完整参数；后续修改只影响新提交的任务。
        列表条目可用 #编号 选择，如 /style load #1、/function load #1、/sampler set #1；编号以本人本会话最近一次对应列表为准。
        「第 N 个 / #N / 这个 / 就它」默认指你最近一次看到的那份列表：刚搜索过 Civitai 就是搜索结果的编号（用 /lora download #N 下载），
        刚列过本地 LoRA 才是本地列表的编号（用 /lora load #N 加载）；要看本地列表请先说「本地 LoRA」或直接 /lora list。
        preset 保存生成参数，style 加载时替换提示词，function 加载时追加并可整组移出。
        所有生成相关指令直接以 / 开头，不再加 sd 前缀。步数/CFG/种子以机器人设置为准。
        SD 自启动：生成前如果 SD WebUI 没在跑，机器人会按 sd.root 找到的启动入口把它拉起来并等到接口可用（/sd auto off 可关）。
        所有会话共享持久化提示词、生成参数与待领取图片；领取期间新生成的图片留待下次 /get。
        """.strip());

    public Bot(Settings settings, SdClient sd, Sender sender) {
        this(settings, sd, sender, new LoraDownloader() {
            public CivitaiClient.DownloadedLora download(String url, Consumer<String> stage, CivitaiClient.Progress meter) throws Exception {
                return new CivitaiClient(settings.root, Json.obj(settings.snapshot(), "civitai")).download(url, stage, meter);
            }
            @Override public CivitaiClient.DownloadedLora download(String url, Consumer<String> stage, CivitaiClient.Progress meter,
                                                                   DownloadControl control) throws Exception {
                return new CivitaiClient(settings.root, Json.obj(settings.snapshot(), "civitai")).download(url, stage, meter, control);
            }
        });
    }
    public Bot(Settings settings, SdClient sd, Sender sender, LoraDownloader loraDownloader) {        this.settings = settings; this.sd = sd; this.sender = muteAware(webAware(sender)); this.loraDownloader = Objects.requireNonNull(loraDownloader);
        // 触发词存储：构造只算路径，不读盘、不建文件（第一次写记录时才落盘）。
        this.loraTriggers = new LoraTriggers(settings.root);
        // VAE 的读写统一走这一层：生产环境就是 SdClient 本身，测试可以换成假 SD 桩（SdClient 是 final，没法用子类打桩）。
        this.vae = sdVaeSupport(sd);
        // 传输层是否真的实现了合并转发（要在包装之前问原始传输层：包装器自己会转发 sendRecord）。
        this.forwardRecords = sender.supportsRecord();
        // 回执正文落盘目录（data/quests/<number>.json）：回执不再过期，正文随时读得回来。
        this.questBodyDir = settings.root.resolve("data/quests");
        // 回执索引：只存摘要与已读标记，列表在重启后也还在（读失败就是空索引，不影响回执）。
        this.questFile = settings.root.resolve("data/quests.json");
        this.questIndex = new QuestIndex();
        this.questIndex.load(questFile);
        // 索引读进来时可能已经裁掉了最旧的几条：连它们的正文文件一起删，别留孤儿。
        sweepTrimmedQuests();
        // 刚读进来的索引就是"已保存"的样子：之后真的要写盘才算变化（空索引不会凭空建文件）。
        this.questSavedRevision = this.questIndex.revision();
        // 任务号在进程内自增；重启后从索引与磁盘正文文件里的最大号接着发，新回执不会和旧的撞号。
        WEB_QUEST_SEQ.accumulateAndGet(Math.max(questIndex.latest(), latestQuestBodyNumber()), Math::max);
        this.sdLauncher = new cn.szu.bot.sd.SdLauncher(cn.szu.bot.sd.SdLauncher.root(Json.obj(settings.snapshot(), "sd")),
                () -> Json.obj(settings.snapshot(), "sd"), sd::reachable);
        // SD 的启动输出写进机器人自己的 logs 目录，和别的日志放在一起看。
        sdLauncher.logTo(settings.root.resolve("logs/sd-autostart.log"));
        // SD 正在运行时顺手学一次它的启动参数（绘世启动器的 --xformers 之类），下次自启动照原样起。
        if (sd.reachable()) learnSdStartArgs();
        this.chat = new ChatService(settings, this.sender, this::chatPlan, this::executeChatCommands, this::selectionContext, System::nanoTime);
        this.userPrompts = new UserPromptStore(settings.root);
        this.localStyles = new cn.szu.bot.sd.LocalStyles(settings.root);
        // Personal prompt copies are created lazily; the owner inherits the pre-existing shared prompt once.
        try {
            String owner = UserPromptStore.scopeOf(settings.ownerId());
            userPrompts.seed(owner, sd.prompts());
            new PromptFunctions(settings.root).migrateLegacy(owner);
        } catch (Exception e) { Log.warn("个人 prompt 初始化跳过：" + error(e)); }
        installLogMirror();
        warmUsageIndex();
    }
    /**
     * 后台预热中文词库索引（data/prompt-zh-tags.json，3 万多条，建索引要几百毫秒）。
     * 不在启动路径上同步做：第一次打开提示词面板或查词条释义时才现建，会白白等一次。
     */
    private void warmUsageIndex() {
        Thread warm = new Thread(() -> {
            try { usageIndex(); } catch (Exception error) { Log.warn("中文词库预热失败（不影响使用）：" + error(error)); }
        }, "pixiko-usage-warmup");
        warm.setDaemon(true);
        warm.start();
    }
    /**
     * SD 正在运行、但配置里还没有启动参数时，从它的进程命令行里学一次（写进 sd.start_args）。
     * 用户平时用绘世启动器（--xformers --medvram-sdxl…）启动，学到之后自启动就与手动启动一致。
     */
    void learnSdStartArgs() {
        try {
            if (!Json.str(Json.obj(settings.snapshot(), "sd"), "start_args", "").isBlank()) return;
            String learned = cn.szu.bot.sd.SdLauncher.learnArgsFromRunningSd();
            if (learned.isBlank()) return;
            settings.sdSetting("start_args", new JsonPrimitive(learned));
            Log.info("从正在运行的 SD 学到启动参数，已保存到 sd.start_args：" + learned);
        } catch (Exception error) {
            Log.warn("学习 SD 启动参数失败（不影响使用）：" + error(error));
        }
    }
    /** 机器人启动时（可选）把 SD 拉起来：sd.start_on_boot。 */
    public void startSdOnBoot() {
        if (!sdLauncher.startOnBoot()) return;
        if (sd.reachable()) { learnSdStartArgs(); return; }
        generation.execute(() -> {
            String notice = sdLauncher.startNow();
            if (!notice.isEmpty() && !notice.startsWith("SD 已经在运行")) Log.info("开机自启动 SD：" + notice);
        });
    }
    /**
     * When ".chat log on" is set, WARN/ERROR lines are mirrored into the main group so the bot can be
     * watched remotely. Throttled and recursion-guarded: log-mirroring must never log itself.
     */
    private void installLogMirror() {
        Log.mirror((level, message) -> {
            if (!settings.logMirrorEnabled() || !logMirrorGuard.compareAndSet(false, true)) return;
            try {
                if (System.nanoTime() - lastLogMirrorNanos < TimeUnit.SECONDS.toNanos(3)) return;
                String group = Json.str(settings.snapshot(), "startup_group_id", "");
                if (!group.matches("[1-9][0-9]{0,19}")) return;
                lastLogMirrorNanos = System.nanoTime();
                JsonObject params = new JsonObject();
                params.addProperty("group_id", group);
                params.addProperty("message", "[" + level + "] " + message);
                sender.callApi("send_group_msg", params);
            } catch (Exception ignored) { }
            finally { logMirrorGuard.set(false); }
        });
    }
    private final AtomicBoolean logMirrorGuard = new AtomicBoolean();
    private volatile long lastLogMirrorNanos;
    public void accept(JsonObject event) {
        // 常规档：一条消息（＝一条用户指令，含分号拆出的多条）共用一个收据窗口，收尾时只放一条文本出去。
        NormalWindow window = null;
        try {
            String postType = Json.str(event, "post_type", "");
            if (!"message".equals(postType)) {
                observeNotice(event);
                return;
            }
            if (!settings.allowed(event)) {
                Log.info("消息被白名单拒绝：" + describeConversation(event));
                return;
            }
            mute.selfId(Json.str(event, "self_id", ""));
            if (Json.str(event, "user_id", "!").equals(Json.str(event, "self_id", "?"))) return;
            String text = messageText(event.has("message") ? event.get("message") : event.get("raw_message"));
            if (duplicate(event)) { Log.info("忽略重复事件：" + describeConversation(event)); return; }
            text=internalCommand(text);
            String quoted = quotedText(event, sender);
            if (!quoted.isEmpty()) {
                Log.info("引用消息（" + describeConversation(event) + "）：" + Log.text(quoted));
                event.addProperty("quoted_text", quoted);
            }
            if (!text.startsWith("/")) {
                // A plain "同意" answers a pending proposal; without one the message stays ordinary chat.
                if (marriageConsent(event, text)) return;
                // 群禁言期间连规划都不做：回复发不出去，只会白花一次模型调用。
                String mutedGroup = Json.str(event, "group_id", "");
                if (!mutedGroup.isEmpty() && mute.skip(mutedGroup, "聊天回复")) return;
                // "下载第一个" 但搜索结果已失效时如实说明，不要改派给本机 LoRA 列表。
                // 但群里没被点名、也不在对话窗口的用户不该拿到这句回复：先过 ChatService 那道同一个闸门，
                // 没被点名就完全不回，继续往下走 primeNumberedSelections + chat.accept（它自己会丢掉这条消息）。
                String stale = staleDownloadGuidance(event, text);
                if (stale != null) {
                    if (chat.addressedOrContinuing(event, text)) { reply(event, stale); return; }
                    Log.info("失效搜索引导已静音（" + describeConversation(event) + "，用户 " + Json.str(event, "user_id", "")
                            + "）：群里未被点名且不在对话窗口，不回复");
                }
                primeNumberedSelections(event, text);
                chat.accept(event, speakableNumbers(quoted.isEmpty() ? text : "[引用] " + quoted + "\n" + REQUEST_MARK + text));
                return;
            }
            // 到这里就是一条真的指令了：开常规档的收据窗口（debug 档 / 已经在一条指令里 → null＝照旧直接发）。
            window = openNormalWindow(event);
            if (text.matches("(?i)^[./](jrlp|强娶|离婚|结婚|同意|拒绝|wife|marry|propose|divorce|accept|reject)(?:\\s+[\\s\\S]*)?$")) { marriage(event, text); return; }
            if (text.matches("(?i)^[./]affinity(?:\\s+[\\s\\S]*)?$")) { affinityCommand(event, text.replaceFirst("(?i)^[./]affinity","")); return; }
            List<String> commands = splitCommands(text);
            // 一条消息里出现多个 .infix（复合语句 `.infix … ; .infix … ; .gen`）时先合成**一步**：
            // 默认（global）档下一条指令只执行一次 infix，否则每一步都拿整份提示词改写一次、互相覆盖。
            // parts 档不合并（旧行为完整保留）。这里是所有真实消息（QQ/网页指令）的唯一入口。
            commands = mergeInfixSteps(commands, event, settings);
            if (commands.size() > 1) {
                String who = describeConversation(event) + "，用户 " + Json.str(event, "user_id", "");
                Log.info("单条消息包含 " + commands.size() + " 条指令（" + who + "），按顺序执行");
                for (int index = 0; index < commands.size(); index++) {
                    String command = commands.get(index);
                    Log.info("指令 " + (index + 1) + "/" + commands.size() + "（" + who + "）：" + Log.text(command));
                    markQueryCommand(event, queryCommand(command));
                    try { dispatch(event, command); }
                    catch (Exception e) {
                        if (chatDispatchFailed.get() != null) chatDispatchFailed.set(true);
                        Log.error("第 " + (index + 1) + " 条指令失败，剩余 " + (commands.size() - index - 1) + " 条未执行（" + who + "）", e);
                        replyFailure(event, "第 " + (index + 1) + " 条指令失败，后续指令未执行：\n" + command + "\n" + error(e));
                        return;
                    }
                    if (Boolean.TRUE.equals(chatDispatchFailed.get())) return;
                }
                return;
            }
            Log.info("指令（" + describeConversation(event) + "，用户 " + Json.str(event, "user_id", "") + "）：" + Log.text(text));
            markQueryCommand(event, queryCommand(commands.get(0)));
            dispatch(event, commands.get(0));
        } catch (Exception e) {
            if (chatDispatchFailed.get() != null) chatDispatchFailed.set(true);
            Log.error("指令处理失败（" + describeConversation(event) + "）", e);
            replyFailure(event, "操作失败：" + error(e));
        } finally {
            closeNormalWindow(window);
        }
    }
    /**
     * Splits one message into consecutive commands, one per line. Only a message whose every non-blank line
     * is itself a command is split, so a command with a multi-line argument still arrives as a single command.
     */
    static List<String> splitCommands(String text) {
        if (text == null || text.indexOf('\n') < 0) return List.of(text == null ? "" : text);
        List<String> commands = new ArrayList<>();
        for (String raw : text.split("\\R", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            String command = internalCommand(line);
            if (!command.startsWith("/")) return List.of(text);
            commands.add(command);
        }
        return commands.size() > 1 ? List.copyOf(commands) : List.of(text);
    }
    private void dispatch(JsonObject event, String text) throws Exception {
            Matcher chatting = Pattern.compile("^/chat(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE).matcher(text);
            if (chatting.matches()) { chatCommand(event, Objects.requireNonNullElse(chatting.group(1), "").strip()); return; }
            if (text.equals("/help")) { reply(event, HELP); return; }
            if (text.equals("/admin") || text.startsWith("/admin ")) { adminCommand(event, text); return; }
            if (text.equals("/sd") || text.startsWith("/sd ")) { sdCommand(event, text.substring(3).strip()); return; }
            if (text.equals("/batch") || text.startsWith("/batch ")) { batchCommand(event, text); return; }
            Matcher usage = USAGE.matcher(text);
            if (usage.matches()) {
                usage(event, Objects.requireNonNullElse(usage.group(1), "")); return;
            }
            if (text.matches("(?i)^/imgcnt(?:\\s+.*)?$")) {
                String value = text.substring(7).strip();
                if (!value.isEmpty()) {
                    int count;
                    try { count = Integer.parseInt(value); } catch (NumberFormatException e) { throw new IllegalArgumentException("用法：/imgcnt <正整数>"); }
                    settings.imageCount(count);
                }
                reply(event, "每条聊天记录图片上限：" + settings.imageCount() + " 张（已保存，按生成任务分别发送）。"); return;
            }
            Matcher imageMode = IMAGE_MODE_COMMAND.matcher(text);
            if (imageMode.matches()) { imageMode(event, Objects.requireNonNullElse(imageMode.group(1), "").strip()); return; }
            Matcher mode = QQ_MODE_COMMAND.matcher(text);
            if (mode.matches()) { qqMode(event, Objects.requireNonNullElse(mode.group(1), "").strip()); return; }
            Matcher progress = Pattern.compile("^/(progress|进度)(?:\\s+.*)?$", Pattern.CASE_INSENSITIVE).matcher(text);
            if (progress.matches()) {
                // 进度直接来自 SD WebUI（GET /sdapi/v1/progress），队列来自机器人自己。
                reply(event, sd.progress().describe() + "\n队列状态：\n" + generationStatus());
                return;
            }
            Matcher recent = Pattern.compile("^/rg(?:\\s+(\\S+))?$", Pattern.CASE_INSENSITIVE).matcher(text);
            if (recent.matches()) {
                int count;
                try { count = Integer.parseInt(Objects.requireNonNullElse(recent.group(1), "")); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("用法：/rg <正整数>，按图片数量回溯。"); }
                if (count < 1) throw new IllegalArgumentException("回溯数量须为正整数。");
                getImages(event, null, count); return;
            }
            Matcher infix = Pattern.compile("^/infix(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE).matcher(text);
            if (infix.matches()) { infix(event, Objects.requireNonNullElse(infix.group(1), "").strip()); return; }
            Matcher progen = PROGEN.matcher(text);
            if (progen.matches()) { progen(event, Objects.requireNonNullElse(progen.group(1), "").strip()); return; }
            if (text.equals("/yh") || text.equals("/liv")) {
                sendImages(event, Maps.files(settings.mapPath(text.substring(1))), text.equals("/yh") ? "深大粤海地图" : "深大丽湖地图");
                return;
            }
            if (text.equals("/map") || text.startsWith("/map ")) { map(event, text); return; }
            if (text.equals("/get")) {
                getImages(event); return;
            }
            Matcher generating = GENERATE_COMMAND.matcher(text);
            if (generating.matches()) {
                String value = Objects.requireNonNullElse(generating.group(1), "1");
                if (value.equalsIgnoreCase("toggle")) { reply(event, "每个生成任务完成后自动领取：" + (settings.toggleAutoGet() ? "开启" : "关闭") + "（已保存）。"); return; }
                if (value.equalsIgnoreCase("status") || value.equals("列表") || value.equalsIgnoreCase("list")) { reply(event, generationStatus()); return; }
                // 队列控制：挂起 / 继续 / 取消（可选全部）/ 置顶为第一优先级。
                Matcher control = Pattern.compile("^\\s*(hold|suspend|resume|continue|cancel|first|priority|top|挂起|暂停|继续|恢复|取消|置顶|优先)\\s*(?:#?\\s*(\\S+))?\\s*$",
                        Pattern.CASE_INSENSITIVE).matcher(value);
                if (control.matches()) {
                    String action = switch (control.group(1).toLowerCase(Locale.ROOT)) {
                        case "hold", "suspend", "挂起", "暂停" -> "hold";
                        case "resume", "continue", "继续", "恢复" -> "resume";
                        case "cancel", "取消" -> "cancel";
                        default -> "first";
                    };
                    reply(event, generationControl(action, Objects.requireNonNullElse(control.group(2), "")));
                    return;
                }
                BigInteger count;
                try { count = new BigInteger(value); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("用法：/gen [次数]，次数须为正整数；/gen status 查看队列；/gen hold|resume|cancel|first #编号 管理任务。"); }
                if (count.signum() <= 0) throw new IllegalArgumentException("生成次数须为正整数。");
                generate(event, count); return;
            }
            Matcher character = Pattern.compile("^/char(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE).matcher(text);
            if (character.matches()) { character(event, Objects.requireNonNullElse(character.group(1), "").strip()); return; }            Matcher lora = LORA_COMMAND.matcher(text);
            if (lora.matches()) { lora(event, Objects.requireNonNullElse(lora.group(1), "").strip()); return; }
            Matcher options = SD_SETTINGS.matcher(text);
            if (options.matches()) { sdSettings(event, options.group(1).toLowerCase(Locale.ROOT),
                    Objects.requireNonNullElse(options.group(2), "").strip()); return; }
            Matcher m = PROMPT.matcher(text);
            if (m.matches()) {
                boolean negative = m.group(1).equalsIgnoreCase("promptR");
                String scope = promptScope(event);
                if (m.group(2) == null) {
                    SdClient.Prompts p = effectivePrompts(scope);
                    String positiveList = negative ? "" : numbered(event, "prompt", promptTerms(p.positive()));
                    String negativeList = numbered(event, "promptR", promptTerms(p.negative()));
                    reply(event, (negative ? "反向 prompt：\n" + display(p.negative()) : formatPrompts(p)) + "\n来源：" + p.source()
                            + (negative ? "" : "\n正向词条：" + positiveList) + "\n反向词条：" + negativeList
                            + "\n这是你个人的 prompt（只有你自己能看到和使用），已持久化，重启后仍保留。"
                            + "\n移除词条可用 /prompt remove #编号 或 /promptR remove #编号。"
                            + (negative ? "" : "\n词条分类：\n" + TermCategories.describe(usageIndex(), p.positive())
                                    + "\n只保留某几类：/prompt keep 人物 服饰（其余清空）；删掉某几类：/prompt drop 环境 物品。"));
                } else {
                    String op = m.group(2).toLowerCase(Locale.ROOT), value = Objects.requireNonNullElse(m.group(3), "").strip();
                    if (value.length() > 20000) throw new IllegalArgumentException("prompt 超过 20000 字符。");
                    if (op.equals("classify")) {
                        if (negative) throw new IllegalArgumentException("分类只针对正向 prompt：请使用 /prompt classify。");
                        if (!value.isEmpty()) throw new IllegalArgumentException("classify 后不需要参数。");
                        reply(event, TermCategories.describe(usageIndex(), userPrompts.prompts(scope).positive()));
                        return;
                    }
                    if (op.equals("keep") || op.equals("drop")) {
                        if (negative) throw new IllegalArgumentException("按类别筛选只针对正向 prompt：请使用 /prompt keep 或 /prompt drop。");
                        reply(event, categoryFilter(scope, op.equals("keep"), value));
                        return;
                    }
                    // 方案 B 的开关与用量（只展示，不设额度上限）：/prompt category model on|off、/prompt category status。
                    if (op.equals("category")) {
                        if (negative) throw new IllegalArgumentException("复合短语兜底模型只针对正向 prompt：请使用 /prompt category。");
                        reply(event, categoryModelCommand(scope, value));
                        return;
                    }
                    if (op.equals("undo")) {
                        if(negative) throw new IllegalArgumentException("正反向 prompt 会一并回退，请使用 /prompt undo。");
                        if(!value.isEmpty()) throw new IllegalArgumentException("undo 后不需要参数。");
                        PromptFunctions functions=new PromptFunctions(settings.root);functions.recover(() -> userPrompts.prompts(scope), scope);
                        SdClient.Prompts before=userPrompts.prompts(scope);
                        UserPromptStore.Undo undone=userPrompts.undo(scope);functions.reset(scope);
                        reply(event,"已回退到上一次 prompt，本次变化：\n"+formatPromptDiff(before, undone.prompts())
                                +"\n还可回退 "+undone.remaining()+" 次（最多保留 "+UserPromptStore.MAX_HISTORY+" 步；"
                                +"再改一次就会压入新的一步）");return;
                    } else if (op.equals("clear")) {
                        if (!value.isEmpty()) throw new IllegalArgumentException("clear 后不需要参数。");
                    } else if (value.isEmpty()) throw new IllegalArgumentException("请提供 prompt 文本；清空请使用 /" + (negative ? "promptR" : "prompt") + " clear。");
                    if (op.equals("remove") && value.startsWith("#")) {
                        value = select(event, negative ? "promptR" : "prompt", value);
                        SdClient.Prompts current = userPrompts.prompts(scope);
                        if (!promptTerms(negative ? current.negative() : current.positive()).contains(value))
                            throw new IllegalArgumentException("编号对应的提示词已变化，请重新 /prompt 或 /promptR 查看后移除。");
                    }
                    // SD 认不得中文：add/set/remove 的取值里只要有汉字，就不把中文写进 prompt，
                    // 改成转一条等价的中文要求交给 .infix（见 infix(...)：由改写模型换成标准英文词条并应用）。
                    // clear/undo/classify/keep/drop 不走这里（它们的取值不是要写进 prompt 的词条）。
                    if (hasHan(value) && (op.equals("add") || op.equals("set") || op.equals("remove"))) {
                        String request = (negative ? "反向提示词" : "正向提示词")
                                + (op.equals("add") ? "里加上：" : op.equals("set") ? "里改为：" : "里删掉：") + value;
                        // 用户说的中文词条先查**本机词库**：能唯一换出标准英文词条就直接照做——
                        // 这是"用户自己的意志"（`.prompt add 微笑` 就是要把 smile 加进去），不许机器人擅自
                        // 把它改写成"交给模型改写"（那等于把用户的话重新解释一遍），也不该白花一次模型调用。
                        List<String> local = localTagsFor(value, currentVocabulary(scope));
                        if (!local.isEmpty()) {
                            SdClient.PromptChange direct = userPrompts.change(scope, negative, op,
                                    String.join(", ", local), userPrompts.vocabulary(styleTerms()));
                            if (op.equals("remove")) new PromptFunctions(settings.root).forget(scope, negative, direct.changed(), false);
                            SdClient.Prompts updated = direct.prompts();
                            reply(event, "已按你的要求" + (op.equals("add") ? "加入" : op.equals("remove") ? "删除" : "改为")
                                    + "：" + String.join("、", local) + "（「" + value + "」的本机词条对应写法）"
                                    + "\n（没有调用改写模型：这条要求本机就能唯一对应上）"
                                    + "\n" + (negative ? "反向" : "正向") + " prompt 已更新（仅你个人）：\n"
                                    + display(negative ? updated.negative() : updated.positive())
                                    + "\n来源：" + updated.source());
                            return;
                        }
                        // 本机唯一对应不上：**保持既有行为**——如实说明这条中文要求交给改写模型，
                        // 由模型换成标准英文词条后落地（prompt 里不会写进中文）。
                        Log.info("prompt 指令里的中文已转交改写（" + describeConversation(event) + "）：" + request);
                        reply(event, "prompt 指令只认英文词条：这条中文要求已交给改写处理（等价于 .infix " + request
                                + "），由改写模型给出标准英文词条，不会把中文写进 prompt。");
                        infix(event, request);
                        return;
                    }
                    boolean wholeField = op.equals("set") || op.equals("clear");
                    if (wholeField) op = "edit";
                    SdClient.PromptChange change = userPrompts.change(scope, negative, op, value, userPrompts.vocabulary(styleTerms()));
                    if (op.equals("edit") || op.equals("remove"))
                        new PromptFunctions(settings.root).forget(scope, negative, change.changed(), op.equals("edit"));
                    SdClient.Prompts p = change.prompts();
                    String feedback = op.equals("edit") ? "" : (op.equals("remove") ? "已删除：" : "已添加：")
                            + (change.changed().isEmpty() ? "（无）" : String.join("、", change.changed())) + "\n"
                            + (change.unchanged().isEmpty() ? "" : "已存在，未重复添加：" + String.join("、", change.unchanged()) + "\n");
                    reply(event, feedback + (negative ? "反向" : "正向") + " prompt 已更新（仅你个人）：\n" + display(negative ? p.negative() : p.positive()) + "\n来源：" + p.source());
                }
                return;
            }
            if (text.startsWith("/")) { if (chatDispatchFailed.get() != null) chatDispatchFailed.set(true); Log.warn("无法识别的指令（" + describeConversation(event) + "）：" + Log.text(text)); reply(event, "指令格式不正确，请发送 /help 查看用法。"); }
    }
    private static List<String> promptTerms(String text) {
        try { return PromptEditor.parts(text); } catch (IllegalArgumentException e) { return List.of(); }
    }
    /** `/affinity [QQ号]`：看好感度与分档（K3）。只读，不改任何执行相关设置。 */
    private void affinityCommand(JsonObject event, String arguments) throws Exception {
        String target=arguments.strip();
        String conversation=ChatService.conversationKey(event);
        if(!target.isEmpty()) {
            requireOwner(event,"/affinity <QQ号>");
            if(!target.matches("[0-9]{4,12}")) throw new IllegalArgumentException("用法：/affinity <QQ号>（只填数字）。");
            String other=Json.str(event,"self_id","")+":private:"+target;
            int score=cn.szu.bot.chat.PersonaState.of(settings.root).affinity(other);
            reply(event,"与 "+target+" 的好感度："+score+"（"+cn.szu.bot.chat.PersonaState.tier(score)+"，按私聊会话记账）。");
            return;
        }
        cn.szu.bot.chat.PersonaState state=cn.szu.bot.chat.PersonaState.of(settings.root);
        int score=state.affinity(conversation);
        cn.szu.bot.chat.PersonaState.Mood mood=state.mood(conversation);
        reply(event,"你与我的好感度："+score+"（"+cn.szu.bot.chat.PersonaState.tier(score)+"）\n"
                +"此刻的情绪："+mood.mood()+"（强度 "+mood.intensity()+"）"
                +(mood.reason().isBlank() ? "" : "，原因："+mood.reason())
                +"\n分档：生疏 <35 ／ 熟悉 35–64 ／ 亲近 65–84 ／ 很亲近 ≥85。"
                +"好感度与情绪只影响我的语气和亲密度，**不影响我照不照做**——生图类指令永远照做。");
    }
    private void chatCommand(JsonObject event, String arguments) throws Exception {
        String conversation=ChatService.conversationKey(event);
        String lower=arguments.toLowerCase(Locale.ROOT);
        if(arguments.equalsIgnoreCase("toggle")) {
            requireStaff(event, "/chat toggle");
            boolean enabled=settings.toggleChat(conversation); chat.changed(conversation,false);
            Log.info("聊天开关（本会话 "+conversation+"，操作者 "+Json.str(event,"user_id","")+"）："+(enabled?"开启":"关闭"));
            reply(event,"本会话聊天已"+(enabled?"开启":"关闭")+"（已保存，重启后仍生效）。\n全局开关："
                    +(settings.chatEnabled()?"开启":"关闭")+"，本会话现在"+(settings.chatEnabled(conversation)?"会回复":"不会回复")
                    +"\n使用 /chat global on|off 控制全局总开关（仅 owner）。");
            return;
        }
        if(lower.startsWith("global")) {
            String value=lower.substring("global".length()).strip();
            if(!value.equals("on") && !value.equals("off"))
                throw new IllegalArgumentException("用法：/chat global on|off（全局总开关，仅 owner）");
            requireOwner(event, "/chat global");
            boolean enabled=settings.setChatEnabled(value.equals("on")); chat.changed(false);
            Log.info("聊天全局开关："+(enabled?"开启":"关闭"));
            reply(event,"全局聊天开关已"+(enabled?"开启":"关闭")+"（已保存）。\n各会话可用 /chat toggle 单独关闭（owner/admin）；全局关闭时所有会话都不回复。");
            return;
        }
        if(lower.startsWith("corpus")) {
            requireOwner(event, "/chat corpus");
            String raw=arguments.substring("corpus".length()).strip();
            if(raw.isEmpty()) {
                reply(event,"原作语料复现："+(settings.corpusReplay()?"开":"关")
                        +"（chat.corpus_replay，默认开）；命中时最多注入 "+settings.corpusTopK()+" 条原作问答（chat.corpus_top_k）。"
                        +"\n语料文件：data/kotori-corpus.txt（保留 `===== seenXXXXX =====` 与【说话人】「台词」格式）。"
                        +"\n开=命中时优先照搬原作里小鸟那一句（最小改写），未命中时只用口癖锚点；关=只保留口癖锚点。用 /chat corpus on|off 切换。");
                return;
            }
            boolean on;
            if(raw.equalsIgnoreCase("on")||raw.equals("开")) on=true;
            else if(raw.equalsIgnoreCase("off")||raw.equals("关")) on=false;
            else { reply(event,"用法：/chat corpus on|off（当前 "+(settings.corpusReplay()?"开":"关")+"）——控制是否照搬原作语料里的原句。"); return; }
            settings.chatSetting("corpus_replay",new JsonPrimitive(on));
            reply(event,"原作语料复现已"+(on?"开启":"关闭")+"（已保存）。"+(on?"":"现在只保留口癖锚点，不再照搬原句。"));
            return;
        }
        if(lower.startsWith("notice")) {
            requireStaff(event, "/chat notice");
            String raw=arguments.substring("notice".length()).strip();
            boolean on;
            if(raw.equalsIgnoreCase("on")||raw.equalsIgnoreCase("开")) on=true;
            else if(raw.equalsIgnoreCase("off")||raw.equalsIgnoreCase("关")) on=false;
            else { reply(event,"用法：/chat notice on|off（当前 "+(settings.startupNoticeEnabled()?"开":"关")+"）——控制上/下线播报是否发到主群。"); return; }
            settings.setStartupNoticeEnabled(on);
            reply(event,"上/下线播报已"+(on?"开启":"关闭")+"（已保存）。");
            return;
        }
        if(lower.startsWith("log")) {
            requireStaff(event, "/chat log");
            String raw=arguments.substring("log".length()).strip();
            boolean on;
            if(raw.equalsIgnoreCase("on")||raw.equalsIgnoreCase("开")) on=true;
            else if(raw.equalsIgnoreCase("off")||raw.equalsIgnoreCase("关")) on=false;
            else { reply(event,"用法：/chat log on|off（当前 "+(settings.logMirrorEnabled()?"开":"关")+"）——开启后把 WARN/ERROR 日志同步到主群，便于远程观察。"); return; }
            settings.setLogMirrorEnabled(on);
            reply(event,"日志同步到主群已"+(on?"开启":"关闭")+"（已保存，只同步警告与错误，最多每 3 秒一条）。");
            return;
        }
        if(lower.equals("infix") || lower.startsWith("infix ")) {
            if(lower.equals("infix")) throw new IllegalArgumentException("用法：/chat infix <修改要求>，用 DeepSeek 修改基础性格设定（初始设定），最多 8000 字符。");
            requireOwner(event, "/chat infix");
            chatInfix(event, arguments.substring("infix".length()).strip());
            return;
        }
        if(lower.startsWith("personality ")) {
            requireOwner(event, "/chat personality");
            String value=arguments.substring(12).strip();
            if(value.isBlank() || value.length()>8000) throw new IllegalArgumentException("性格设定须为 1–8000 字符。");
            settings.setChatPersonality(value); chat.changed(true);
            Log.info("聊天性格设定已更新："+value.length()+" 字符");
        } else if(lower.startsWith("add ")) {
            requireOwner(event, "/chat add");
            String value=arguments.substring(4).strip();
            if(value.isBlank() || value.length()>8000) throw new IllegalArgumentException("追加内容须为 1–8000 字符。");
            String current=settings.chatPersonality(),updated=current.isBlank()?value:current+"\n"+value;
            if(updated.length()>20000) throw new IllegalArgumentException("追加后性格设定超过 20000 字符，请缩短内容。");
            settings.setChatPersonality(updated);chat.changed(true);
            Log.info("聊天性格设定已追加："+value.length()+" 字符，共 "+updated.length()+" 字符");
            reply(event,"已追加到基础性格设定末尾：\n"+value);return;
        } else if(lower.equals("model") || lower.startsWith("model ")) {
            String raw=arguments.substring("model".length()).strip();
            String chatModel=Json.str(DeepSeekPrompts.sectionOf(settings, DeepSeekPrompts.Channel.CHAT),"model","deepseek-flash");
            String imageModel=Json.str(DeepSeekPrompts.sectionOf(settings, DeepSeekPrompts.Channel.IMAGE),"model","deepseek-flash");
            if(raw.isEmpty()) {
                reply(event,"聊天频道模型："+chatModel+"\n生图频道模型："+imageModel
                        +"\n两条通道的模型、思考开关、输出上限与密钥都彼此独立；用 /chat model <名称> 只改聊天频道，"
                        +"不会影响 .infix/.gen/.progen。");
                return;
            }
            requireOwner(event, "/chat model");
            if(raw.length()>100||raw.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("模型名称须为 1–100 个字符且不含控制字符。");
            settings.chatApiSetting("model",new JsonPrimitive(raw));
            Log.info("聊天频道模型："+chatModel+" → "+raw);
            reply(event,"聊天频道模型已设为 "+raw+"（已保存，只影响聊天频道；生图频道仍为 "+imageModel+"）。");
            return;
        } else if(lower.startsWith("frequency ")) {
            requireOwner(event, "/chat frequency");
            int value;
            try { value=Integer.parseInt(arguments.substring(10).strip()); } catch(NumberFormatException e) { throw new IllegalArgumentException("频率须为非负整数，0 表示静默。"); }
            if(value<0) throw new IllegalArgumentException("频率须为非负整数。");
            settings.chatSetting("frequency",new JsonPrimitive(value)); chat.changed(false);
            Log.info("聊天频率上限："+value+" 次/分钟/会话");
        } else if(!arguments.isEmpty()) throw new IllegalArgumentException("用法：/chat、/chat toggle、/chat global on|off（owner）、/chat model [名称]（owner）、/chat infix <修改要求>（owner）、/chat personality <基础性格设定>（owner）、/chat add <内容>（owner）、/chat frequency <每分钟次数>（owner）");
        requireStaff(event, "/chat");
        reply(event,"聊天功能：全局 "+(settings.chatEnabled()?"开启":"关闭")+"，本会话 "+(settings.chatEnabled(conversation)?"开启":"关闭")
                +"\n本开关只控制聊天频道：关闭后不回复自然语言对话，也不做自然语言指令规划；"
                +".infix/.gen/.progen 等生图指令不受影响，始终可用。"
                +"\nDeepSeek 通道（各自独立的模型、思考开关、输出上限与密钥）："
                +"\n· "+channelStatus(DeepSeekPrompts.Channel.CHAT)
                +"\n· "+channelStatus(DeepSeekPrompts.Channel.IMAGE)
                +"\n群聊先由 @机器人或小鸟名字唤醒；同一用户连续对话窗口："+settings.chatContextSeconds()+" 秒（每次消息和回复后续期）。"
                +"\n只有被 @ 或叫名字（小鸟/小鳥/ことり/kotori）时才会回复；被点名后如果我愿意继续这段对话，30 分钟内你在这个会话里不 @ 也能接着聊。"
                +"\n聊天使用独立单线程，不占用 SD 生成队列。"
                +"\n每个会话每分钟最多回复："+settings.chatFrequency()+" 次"
                +"\n基础性格（仅 owner 可修改，含对 owner 的专属规则）：\n"+settings.chatPersonality());
    }
    /**
     * 一条 DeepSeek 通道的现状：模型、思考开关、密钥文件与是否已配置。两条通道完全独立——改聊天频道的
     * 模型/密钥不会影响生图频道。密钥只报告"已配置"，绝不回显内容。
     */
    private String channelStatus(DeepSeekPrompts.Channel channel) {
        JsonObject section = DeepSeekPrompts.sectionOf(settings, channel);
        String file = Json.str(section, "api_key_file", channel.keyFile());
        boolean configured = Files.isRegularFile(settings.root.resolve(file))
                || Files.isRegularFile(settings.root.resolve(DeepSeekPrompts.Channel.IMAGE.keyFile()));
        return channel.label() + "：" + Json.str(section, "model", "deepseek-flash")
                + "（配置段 " + channel.section() + "，密钥 " + (configured ? "已配置" : "未配置")
                + " " + file + (Json.bool(section, "thinking", false) ? "，思考开启" : "") + "）";
    }
    /**
     * 默认链路（v1 自由改写）：把当前正反向提示词和用户要求直接交给 DeepSeek，由它按自己的判断改写。
     * SD3/SDXL 类模型读得懂自然语言，所以不再把中文要求拆解成多条、也不再要求新词必须是标准词库词条；
     * 词库只在本会话显式打开「标准词库约束」时用来过滤词库外的新词。
     * 旧版（v2 词库约束版）的拆解/候选/扩写/重试/清理/依据审查/补齐都还保留在本文件里，默认不再调用，
     * 完整备份见 backup\infix-v2-strict-*（含说明与恢复步骤）。
     */
    private void infix(JsonObject event, String instruction) throws Exception {
        String lowerInstruction = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        if (instruction.equalsIgnoreCase("filter") || lowerInstruction.startsWith("filter ")) {
            String conversation = ChatService.conversationKey(event);
            String argument = instruction.length() > "filter".length() ? instruction.substring("filter".length()).strip() : "";
            if (!argument.isEmpty()) {
                requireStaff(event, "/infix filter");
                if (!argument.equalsIgnoreCase("on") && !argument.equalsIgnoreCase("off"))
                    throw new IllegalArgumentException("用法：/infix filter、/infix filter on、/infix filter off");
                boolean enabled = settings.setInfixFilterEnabled(conversation, argument.equalsIgnoreCase("on"));
                Log.info("标准词库约束（" + conversation + "）：" + (enabled ? "开启" : "关闭"));
                reply(event, enabled
                        ? "标准词库约束已开启：本会话的 .infix 只允许使用 data/prompt-tags.txt 中已有的词条，"
                            + "词库外的新词会被忽略并在回执中列出。"
                        : "标准词库约束已关闭：本会话的 .infix 由 DeepSeek 自由改写，词库外的新词（含自然语言短语）会保留。");
                completeChatWorkflowStep(event, true);
                return;
            }
            boolean enabled = settings.infixFilterEnabled(conversation);
            reply(event, "标准词库约束（本会话）：" + (enabled ? "开启" : "关闭")
                    + (enabled ? "（.infix filter off 可关闭，仅 owner/admin）" : "（.infix filter on 可开启，仅 owner/admin）")
                    + "\n关闭时（默认）由 DeepSeek 自由改写，允许自然语言短语，不查标准词库；"
                    + "开启时新词必须是 data/prompt-tags.txt 里的词条，你自己提示词里已有的词条（含 LoRA 标签）任何时候都允许。");
            completeChatWorkflowStep(event, true);
            return;
        }
        if (infixControl(event, instruction)) return;
        if (instruction.isBlank() || instruction.length() > 8000) throw new IllegalArgumentException("用法：/infix <修改要求>，最多 8000 字符。");
        if (closed.get()) throw new IllegalStateException("机器人正在关闭。");
        // 默认档（global）：一条指令只执行一次 infix —— 不拆组、不逐步，只有一步改写 + 一步画面检查。
        // 旧的多步实现（parts）完整保留，用户用 .infix mode parts 切回去。
        if (settings.infixMode(ChatService.conversationKey(event)) == Settings.InfixMode.GLOBAL) {
            infixGlobal(event, instruction);
            return;
        }
        infixParts(event, instruction);
    }
    /**
     * {@code .infix mode} / {@code .infix conflict}：两个按会话持久化的开关（权限与 {@code .infix} 本身一致）。
     *
     * <ul>
     *   <li>{@code .infix mode} 显示当前档位；{@code .infix mode global|parts}（也认中文 全局/分组）切换。
     *       默认 global：一条指令只执行一次 infix，不拆组、不逐步。parts 是旧的多步实现。</li>
     *   <li>{@code .infix conflict} 显示传统正反义词排斥器状态；{@code on|off} 切换。
     *       默认 off：{@code cross-section view} 与 {@code front view} 这类并不冲突的组合不再被本地删掉，
     *       矛盾一律交给改写之后那次整份提示词画面检查（DeepSeek）判断。</li>
     * </ul>
     *
     * <p>返回 true 表示这一条已经被当作控制指令处理完，绝不当成改写要求再走一遍模型。
     */
    private boolean infixControl(JsonObject event, String instruction) throws Exception {
        String text = instruction == null ? "" : instruction.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("mode") || lower.startsWith("mode ") || lower.equals("档位") || lower.startsWith("档位 ")) {
            String conversation = ChatService.conversationKey(event);
            // 前缀长度按实际匹配到的写法算（"mode"/"档位" 都是 2 个字符，"mode " 4 个）。
            String argument = lower.startsWith("mode") ? text.substring(Math.min(4, text.length())).strip() : text.substring(2).strip();
            if (!argument.isEmpty()) {
                requireStaff(event, "/infix mode");
                Settings.InfixMode mode = Settings.InfixMode.parse(argument);
                if (mode == null) throw new IllegalArgumentException("用法：/infix mode、/infix mode global、/infix mode parts（中文也认：全局/分组）");
                settings.setInfixMode(conversation, mode);
                Log.info("infix 档位（" + conversation + "）：" + mode.key());
                reply(event, mode == Settings.InfixMode.GLOBAL
                        ? "infix 档位已切到「全局」（默认）：一条指令只执行一次 infix —— 这条指令里的所有改写要求合成一次交给 DeepSeek，"
                            + "作用在整份提示词上，不拆组、不逐步；改写落地前还会对整份提示词做一次画面检查。"
                        : "infix 档位已切到「分组」（旧行为）：保留原来的拆分/多步实现，一条指令里的多个诉求会分成若干步分别改写；"
                            + "想切回一条指令只改一次用 .infix mode global。");
                completeChatWorkflowStep(event, true);
                return true;
            }
            Settings.InfixMode mode = settings.infixMode(conversation);
            reply(event, "infix 档位（本会话）：" + mode.key() + "（" + mode.label() + "）"
                    + (mode == Settings.InfixMode.GLOBAL ? "，默认档" : "")
                    + "\nglobal（全局，默认）：一条指令里的所有改写要求合成一次交给 DeepSeek，作用在整份提示词上，不拆组、不逐步；"
                    + "改写落地前对整份提示词做一次画面检查（正反向是否自相矛盾、有没有坏组合/残留中文/坏标签、LoRA 标签是否完好）。"
                    + "\nparts（分组）：旧的多步实现，一条指令里的多个诉求分成若干步分别改写。"
                    + "\n切换：/infix mode global、/infix mode parts（中文：全局/分组，仅 owner/admin）。"
                    + "\n传统正反义词排斥器（本地互斥词表）：" + (settings.infixConflictEnabled(conversation) ? "开启" : "关闭（默认）")
                    + "，用 /infix conflict on|off 切换；关闭时矛盾一律交给上面那次画面检查判断。");
            completeChatWorkflowStep(event, true);
            return true;
        }
        if (lower.equals("conflict") || lower.startsWith("conflict ") || lower.equals("互斥") || lower.startsWith("互斥 ")) {
            String conversation = ChatService.conversationKey(event);
            int cut = lower.startsWith("conflict") ? "conflict".length() : 2;
            String argument = text.length() > cut ? text.substring(cut).strip() : "";
            if (!argument.isEmpty()) {
                requireStaff(event, "/infix conflict");
                boolean enabled;
                if (argument.equalsIgnoreCase("on") || argument.equals("开") || argument.equals("开启")) enabled = true;
                else if (argument.equalsIgnoreCase("off") || argument.equals("关") || argument.equals("关闭")) enabled = false;
                else throw new IllegalArgumentException("用法：/infix conflict、/infix conflict on、/infix conflict off");
                settings.setInfixConflictEnabled(conversation, enabled);
                Log.info("传统正反义词排斥器（" + conversation + "）：" + (enabled ? "开启" : "关闭"));
                reply(event, enabled
                        ? "传统正反义词排斥器已开启（本地互斥词表）：姿势/视角/载具/室内外同族词条只保留一个，"
                            + "互斥在本地就解决，不再问模型。"
                        : "传统正反义词排斥器已关闭（默认）：本地的姿势/视角/载具/室内外互斥整理不再执行"
                            + "（cross-section view 与 front view 这类并不冲突的组合不会再被删），"
                            + "矛盾一律交给改写之后那次整份提示词画面检查（DeepSeek）判断。");
                completeChatWorkflowStep(event, true);
                return true;
            }
            boolean enabled = settings.infixConflictEnabled(conversation);
            reply(event, "传统正反义词排斥器（本会话）：" + (enabled ? "开启" : "关闭（默认）")
                    + "\n关闭时（默认）：本地不再按互斥词表删/改词条（cross-section view 与 front view 会原样保留），"
                    + "正反向是否自相矛盾由改写之后那次 DeepSeek 整份提示词画面检查判断并修正。"
                    + "\n开启时：恢复旧的本地行为（同族词条只保留一个、反义词对删掉一个），不花模型额度。"
                    + "\n切换：/infix conflict on|off（仅 owner/admin）。");
            completeChatWorkflowStep(event, true);
            return true;
        }
        return false;
    }
    /**
     * 默认档：**一次 DeepSeek 改写调用**作用在整份提示词上（必要时含反向），不拆组、不并行、不逐步；
     * 落地前再用**一次**调用对整份提示词做画面检查（发现矛盾/坏组合就让模型在同一次检查里给出修正后的完整提示词）。
     * 一条指令最多 2 次 DeepSeek 调用（1 次改写 + 1 次检查/修正）。
     *
     * <p>失败与回滚策略：
     * <ul>
     *   <li>改写调用失败 → 什么都不改，如实说明（这一步不算成功，链式指令的后续步骤会停下）；</li>
     *   <li>检查失败/超时/返回不可用 → **按改写后的版本原样落地**，并在回执里写明"未通过画面检查"（绝不静默，
     *       也绝不因此丢掉或改坏用户的提示词）；</li>
     *   <li>检查给出修正 → 修正稿要过落地前校验（非空、LoRA 标签仍在、没有新增中文），任一项不满足就**回滚到
     *       改写后的版本**，并说明修正稿被丢弃的原因。</li>
     * </ul>
     */
    private void infixGlobal(JsonObject event, String instruction) throws Exception {
        if (!progenBusy.compareAndSet(false, true)) { reply(event, "已有 DeepSeek 请求正在处理，请稍后重试。"); completeChatWorkflowStep(event, false); return; }
        JsonObject context = attachNormalWindow(event.deepCopy()); replyProcess(context, "正在通过 DeepSeek 智能修改你个人的提示词。");
        String scope = promptScope(event);
        String conversation = ChatService.conversationKey(event);
        boolean strictDictionary = settings.infixFilterEnabled(conversation);
        boolean conflict = settings.infixConflictEnabled(conversation);
        try { progenIO.execute(() -> {
            String message; boolean succeeded = false;
            try {
                SdClient.Prompts original = effectivePrompts(scope);
                var client = clientFor();
                DeepSeekPrompts.Result rewritten;
                try { rewritten = client.edit(instruction, original, TermCategories.describe(usageIndex(), original.positive())); }
                catch (Exception model) {
                    Log.warn("提示词改写调用没有成功：" + error(model));
                    message = "智能修改未完成：" + error(model) + "\n（一个字都没改，你的提示词保持原样）";
                    replyRanked(context, ReceiptRank.FAILURE, message); completeChatWorkflowStep(context, false); releaseNormalWindow(context);
                    return;
                }
                Bot.InfixFilter filter = strictDictionary
                        ? filterInfixVocabulary(settings.root, original, rewritten)
                        : new Bot.InfixFilter(rewritten, List.of());
                String applied = applyModelRewrite(scope, original, filter.result(), filter.rejected(), instruction, conflict);
                if (applied == null) {
                    // 模型结果与当前提示词一致（没有可应用的变化）：这一步仍算成功，回执如实说明，不假装改了。
                    message = "这次没有改动任何词条：改写模型没有给出可应用的修改。\n" + InfixIntent.formsHelp();
                    replyRanked(context, ReceiptRank.AFFIRM, message); completeChatWorkflowStep(context, true); releaseNormalWindow(context);
                    return;
                }
                SdClient.Prompts landed = userPrompts.prompts(scope);
                ScreenOutcome screen = screenPrompt(client, scope, landed);
                String result = "智能修改已应用，本次变化：\n" + formatPromptDiff(original, userPrompts.prompts(scope))
                        + formatRejected(filter.rejected())
                        + screen.suffix()
                        + "（用 .prompt 查看完整提示词）";
                Log.info("整份提示词画面检查（" + conversation + "）：" + screen.log());
                Log.info(result);
                message = result; succeeded = true;
            } catch (Exception e) { message = "智能修改未完成：" + error(e); }
            finally { progenBusy.set(false); }
            replyRanked(context, succeeded ? ReceiptRank.AFFIRM : ReceiptRank.FAILURE, message);
            completeChatWorkflowStep(context, succeeded);
            releaseNormalWindow(context);
        }); } catch (RejectedExecutionException e) { progenBusy.set(false); completeChatWorkflowStep(event, false); releaseNormalWindow(context); throw new IllegalStateException("机器人正在关闭。"); }
    }
    /** 一次整份提示词画面检查的落地结果：要拼进回执的一句后缀 + 给日志的一行。 */
    private record ScreenOutcome(String suffix, String log) { }
    /**
     * 改写落地之前对整份提示词做一次检查（DeepSeek）：正反向是否自相矛盾、有没有明显会让画面崩坏的组合、
     * 是否残留中文或坏标签、LoRA 标签是否完好。
     *
     * <p>检查给出修正稿时先过落地前校验，通过才落地（回执写明修正了什么）；检查失败/超时/返回不可用，
     * 或者修正稿没通过校验，都**按改写后的版本原样落地**，并在回执里明确说明"未通过画面检查"。
     * 这一步只可能发生在这一个方法里，一条指令最多再多一次 DeepSeek 调用。
     */
    private ScreenOutcome screenPrompt(DeepSeekPrompts client, String scope, SdClient.Prompts current) {
        DeepSeekPrompts.Review review;
        try { review = client.review(current); }
        catch (Exception failed) {
            Log.warn("整份提示词画面检查没有完成：" + error(failed));
            return new ScreenOutcome("\n画面检查：未通过画面检查（检查调用失败：" + error(failed) + "），已按改写后的版本落地，"
                    + "如需再试可以再说一次要求或 .prompt undo。", "未通过（调用失败：" + error(failed) + "），按改写后版本落地");
        }
        if (review.ok())
            return new ScreenOutcome("\n画面检查：已通过（正反向无自相矛盾、无明显崩坏组合、无残留中文、LoRA 标签完好）", "已通过");
        if (review.corrected() == null)
            return new ScreenOutcome("\n画面检查：未通过（检查未给出可用的修正稿），已按改写后的版本落地。"
                    + (review.issues().isBlank() ? "" : "检查说明：" + review.issues()), "未通过（无修正稿），按改写后版本落地");
        String why = promptRejection(new SdClient.Prompts(review.corrected().positive(), review.corrected().negative(),
                UserPromptStore.PERSONAL_SOURCE), scope);
        if (why != null)
            return new ScreenOutcome("\n画面检查：未通过（修正稿被丢弃：" + why + "），已按改写后的版本落地。"
                    + (review.issues().isBlank() ? "" : "检查说明：" + review.issues()), "未通过（修正稿 " + why + "），按改写后版本落地");
        try {
            userPrompts.replace(scope, new SdClient.Prompts(review.corrected().positive(), review.corrected().negative(),
                    UserPromptStore.PERSONAL_SOURCE));
        } catch (Exception failed) {
            Log.warn("画面检查的修正稿没能落地：" + error(failed));
            return new ScreenOutcome("\n画面检查：未通过（修正稿落地失败：" + error(failed) + "），已按改写后的版本落地。",
                    "未通过（修正稿落地失败：" + error(failed) + "）");
        }
        return new ScreenOutcome("\n画面检查：发现矛盾/坏组合并已修正 —— "
                + (review.issues().isBlank() ? "检查给出的问题已修正" : review.issues()), "发现矛盾，已用修正稿落地：" + review.issues());
    }
    /**
     * 落地前校验：任一不满足就返回一句"为什么不能落地"（调用方据此回滚到改写后的版本），全部满足返回 null。
     * 三条判据与既有硬规矩一致：① 改后提示词不能空；② LoRA/嵌入标签一个都不能少；③ 不能出现原先没有的中文。
     */
    private String promptRejection(SdClient.Prompts candidate, String scope) {
        if (candidate == null) return "没有内容";
        if (candidate.positive().isBlank()) return "正向提示词被清空了";
        SdClient.Prompts current;
        try { current = userPrompts.prompts(scope); } catch (Exception ignored) { current = candidate; }
        try {
            for (String tag : promptModelTags(current.positive()))
                if (!promptModelTags(candidate.positive()).contains(tag)) return "丢了 LoRA/嵌入标签 " + tag;
            String newChinese = newChineseText(current, candidate);
            if (!newChinese.isBlank()) return "把中文写进了提示词：" + newChinese;
        } catch (Exception broken) { return "校验失败：" + error(broken); }
        return null;
    }
    /**
     * 候选提示词里**新出现**的中文词项（空串＝没有）。存量中文不算：用户自己的提示词里本来就可能带中文，
     * 判定必须与快照比，否则每次改写都会被自己的历史判成"写了中文"。
     */
    static String newChineseText(SdClient.Prompts current, SdClient.Prompts candidate) {
        String before = (current == null ? "" : current.positive()) + ", " + (current == null ? "" : current.negative());
        List<String> fresh = new ArrayList<>();
        for (String term : PromptEditor.parts(candidate.positive() + ", " + candidate.negative())) {
            if (!InfixIntent.hasCjk(term)) continue;
            if (before.contains(term)) continue;
            if (!fresh.contains(term)) fresh.add(term);
        }
        return String.join("、", fresh);
    }
    /** 某个提示词里的全部 LoRA/嵌入标签（写坏了也不抛：返回已认出来的那些）。 */
    static List<String> promptModelTags(String prompt) {
        List<String> found = new ArrayList<>();
        if (prompt == null) return found;
        Matcher matcher = Pattern.compile("(?i)<(?:lora|lyco|embedding|hypernet):[^>]*>").matcher(prompt);
        while (matcher.find()) if (!found.contains(matcher.group())) found.add(matcher.group());
        return found;
    }
    /**
     * 把用户说的中文词条换成本机词库里**唯一**对应的标准英文词条（换不出来返回空列表）。
     *
     * <p>这是"用户自己的意志"那条哲学的实现：{@code .prompt add 微笑} 就是要把 smile 加进去，
     * 能本机唯一对应上就**直接照做**，不调 DeepSeek、不生成 `.infix` 合成指令。
     *
     * <p>判据刻意从严（宁可交回用户选，也不猜）：
     * <ol>
     *   <li>人工维护的 {@link #SCENE_SYNONYMS}（中文 → 标准词条，命中即用第一个在词库里的候选）；</li>
     *   <li>内置中文词库 {@code data/prompt-zh-tags.json} 的**别名精确相等**匹配（用 {@code |} 分隔的别名，
     *       只认整条相等，不做子串——子串会把"微笑"匹到一串别的词）；</li>
     *   <li>候选择一即可；一个词条同时命中多个不同 tag 时算"不唯一"，返回空（交回用户决定）。</li>
     * </ol>
     */
    List<String> localTagsFor(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) return List.of();
        List<String> terms = PromptEditor.parts(value);
        if (terms.isEmpty()) return List.of();
        List<String> mapped = new ArrayList<>();
        for (String term : terms) {
            String tag = localTagFor(term.strip(), allowed);
            if (tag == null) return List.of();
            mapped.add(tag);
        }
        return List.copyOf(mapped);
    }
    /** 单个中文词的唯一标准词条；换不出来或不唯一返回 null。 */
    private String localTagFor(String term, Set<String> allowed) {
        if (term.isEmpty()) return null;
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String candidate : SCENE_SYNONYMS.getOrDefault(term, List.of()))
            if (allowedContains(allowed, candidate)) { candidates.add(candidate); break; }
        if (candidates.isEmpty()) {
            PromptUsage usage = usageIndex();
            if (usage != null) for (String alias : usage.exactAliases(term)) if (allowedContains(allowed, alias)) candidates.add(alias);
        }
        if (candidates.isEmpty() && repoUsage() != null)
            for (String alias : repoUsage().exactAliases(term)) if (allowedContains(allowed, alias)) candidates.add(alias);
        Log.info("中文直通查库（" + term + "）：候选=" + candidates + "，工作区索引=" + (usageIndex() == null ? "不可用" : "可用"));
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }
    /**
     * 工作区里那份中文别名索引的兜底（{@code ./data/prompt-usage.json}，进程启动目录）。
     *
     * <p>机器人根目录（{@code settings.root}）下没有这份文件时（例如只为会话建的临时数据目录），
     * 中文直通就没法命中——用工作区这份只读兜底，读不到就返回 null，绝不抛。
     */
    private volatile PromptUsage repoUsageCache;
    private volatile boolean repoUsageLoaded;
    private PromptUsage repoUsage() {
        if (repoUsageLoaded) return repoUsageCache;
        synchronized (this) {
            if (!repoUsageLoaded) {
                try { repoUsageCache = new PromptUsage(Path.of(".").toAbsolutePath().normalize()); }
                catch (Exception ignored) { repoUsageCache = null; }
                repoUsageLoaded = true;
            }
        }
        return repoUsageCache;
    }
    /**
     * 当前会话有效的词库（标准词表 + 用户自己 prompt 里已有的词条）：中文直通路径只允许换出这里面的词，
     * 与 {@code .infix} 的词库约束完全同一套口径。
     */
    private Set<String> currentVocabulary(String scope) {
        try { return vocabulary(settings.root, userPrompts.prompts(scope)); }
        catch (Exception unavailable) {
            Log.warn("中文直通查词库失败（不影响其他路径）：" + error(unavailable));
            return Set.of();
        }
    }
    /**
     * 生图频道（提示词改写/画面检查）的客户端：生产环境按 config.json 的 progen 段与密钥文件新建，
     * 测试可以用 {@link #useImageClientForTests} 换成假客户端来计数与注入假响应。
     */
    static DeepSeekPrompts clientFor(Settings settings) {
        DeepSeekPrompts override = imageClientOverride;
        return override != null ? override : DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.IMAGE);
    }
    /** 本实例的生图频道客户端（见 {@link #clientFor(Settings)}）。 */
    private DeepSeekPrompts clientFor() { return clientFor(settings); }
    /**
     * 测试专用：让本进程后续所有生图频道调用（改写 / 画面检查 / 严格重试）都走这个假客户端，
     * 好对"一条 .infix 到底调了几次 DeepSeek"计数、并注入"检查发现矛盾/检查失败/检查乱返回"这些响应。
     * 传 {@code null} 恢复真实客户端。
     */
    public static void useImageClientForTests(DeepSeekPrompts client) { imageClientOverride = client; }
    /** 测试辅助：生图频道客户端当前是否被测试覆盖（只报 true/false，不泄露任何内容）。 */
    public static boolean debugImageClient() { return imageClientOverride != null; }
    /**
     * 一次指令里的多个 {@code .infix} 步骤合并成**一步**：默认（global）档下"一条指令只执行一次 infix"。
     *
     * <p>实测 bug（data/quests/252.json、logs/bot-stdout.log 04:31）：用户一条复合语句
     * （{@code .infix 加入多视角分镜 ; .infix 只把分镜那一条保留为特写 ; .infix 加上：分镜 ; .gen}）
     * 被拆成 **3 个 infix 步骤 + 1 个 gen**，每一步都拿整份提示词改写一次、互相打架。现在把这几步的
     * 改写要求合成**一条** .infix（放在第一个 .infix 原来的位置），一次交给 DeepSeek 改写整份提示词。
     *
     * <p><b>什么算一条、什么算多条</b>：判据是"用户是否显式要求分成多条独立指令"——
     * <ul>
     *   <li>一条指令（一条消息 / 一条复合语句 / 模型计划出来的多步）里的所有 {@code .infix} → **合成一条**，
     *       因为它们的诉求都落在同一份提示词上，分开改必然互相覆盖；</li>
     *   <li>用户显式写 {@code .infix #keep <要求>}（或 {@code !keep}）→ 这一条不参与合并，
     *       多条 {@code #keep} 就是用户明确要的"多条独立指令"，各改各的（给需要逐步微调的场合留出口）。</li>
     * </ul>
     * 控制指令（{@code .infix mode} / {@code .infix filter} / {@code .infix conflict}）永远不参与合并。
     * 非 infix 的步骤（{@code .gen}、{@code .style load}…）顺序与数量都不变。
     */
    static List<String> mergeInfixSteps(List<String> commands, JsonObject event, Settings settings) {
        if (commands == null || commands.size() < 2) return commands;
        Settings.InfixMode mode = infixModeOf(settings, event);
        if (mode == Settings.InfixMode.PARTS) return commands;
        List<String> kept = new ArrayList<>();
        List<String> instructions = new ArrayList<>();
        List<String> originals = new ArrayList<>();
        int slot = -1;
        for (String command : commands) {
            // 切档位的指令本身不合并；它后面的 .infix 按切换后的档位判断（顺序执行，语义不变）。
            Settings.InfixMode switched = modeSwitchOf(command);
            if (switched != null) {
                mode = switched;
                if (mode == Settings.InfixMode.PARTS) { flushInfix(kept, instructions, originals, slot); instructions.clear(); originals.clear(); slot = -1; }
                kept.add(command);
                continue;
            }
            Matcher step = Pattern.compile("(?is)^[./]infix\\s+([\\s\\S]+)$").matcher(internalCommand(command == null ? "" : command));
            if (!step.matches() || infixControlCommand(step.group(1))) { kept.add(command); continue; }
            if (mode == Settings.InfixMode.PARTS) { kept.add(command); continue; }
            String raw = step.group(1).strip();
            // 显式出口：.infix #keep <要求>（也认 !keep）表示"这一条不要和别的 infix 合并"，各改各的。
            if (raw.matches("(?is)^[#!]keep(\\s.*)?$")) { kept.add(command); continue; }
            instructions.add(raw);
            originals.add(command);
            if (slot < 0) { slot = kept.size(); kept.add(null); }
        }
        flushInfix(kept, instructions, originals, slot);
        return List.copyOf(kept);
    }
    /**
     * 把收集到的改写要求合成一条 .infix 放回它原来的位置。只有一条（或没有）时不合并：
     * 把占位的空槽换成**原始命令**（绝不丢步骤），多条时换成合并后的那一条。
     */
    private static void flushInfix(List<String> kept, List<String> instructions, List<String> originals, int slot) {
        if (slot >= 0) {
            if (instructions.size() < 2) {
                kept.set(slot, originals.isEmpty() ? kept.get(slot) : originals.get(0));
            } else {
                String merged = "/infix " + String.join("；", instructions);
                kept.set(slot, merged);
                Log.info("同一份提示词的 " + instructions.size() + " 条改写诉求已合成 1 个 infix 步骤（默认档：一次指令只执行一次 infix）："
                        + Log.text(merged));
            }
        }
        instructions.clear();
        originals.clear();
    }
    /** 这个会话的 infix 档位（默认 global）；事件没有会话信息时也返回默认值，绝不抛。 */
    static Settings.InfixMode infixModeOf(Settings settings, JsonObject event) {
        try { return settings.infixMode(ChatService.conversationKey(event)); }
        catch (Exception ignored) { return Settings.InfixMode.GLOBAL; }
    }
    /** 这个 .infix 是不是控制指令（模式/词库约束/互斥开关）——控制指令不参与合并，也不走改写。 */
    static boolean infixControlCommand(String instruction) {
        String lower = instruction == null ? "" : instruction.strip().toLowerCase(Locale.ROOT);
        return lower.startsWith("mode") || lower.startsWith("档位")
                || lower.startsWith("filter") || lower.startsWith("conflict") || lower.startsWith("互斥");
    }
    /**
     * 这条指令是不是在切 infix 档位（{@code .infix mode global|parts}）。会切档位的指令按顺序执行，
     * 它后面的 .infix 合并时要按**切换后**的档位判断（否则"先切 parts 再发两条 infix"会被错误合并成一条）。
     */
    static Settings.InfixMode modeSwitchOf(String command) {
        Matcher step = Pattern.compile("(?is)^[./]infix\\s+([\\s\\S]+)$").matcher(internalCommand(command == null ? "" : command));
        if (!step.matches()) return null;
        String text = step.group(1).strip();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("mode ") || lower.startsWith("档位"))) return null;
        String argument = lower.startsWith("mode") ? text.substring(Math.min(4, text.length())).strip() : text.substring(2).strip();
        return Settings.InfixMode.parse(argument);
    }
    /** 旧的多步实现（parts 档）：一条指令里的多个诉求分成若干步分别改写，完整保留。 */
    private void infixParts(JsonObject event, String instruction) throws Exception {
        if (!progenBusy.compareAndSet(false, true)) { reply(event, "已有 DeepSeek 请求正在处理，请稍后重试。");completeChatWorkflowStep(event,false);return; }
        JsonObject context = attachNormalWindow(event.deepCopy()); replyProcess(context, "正在通过 DeepSeek 智能修改你个人的提示词。");
        String scope = promptScope(event);
        try { progenIO.execute(() -> {
            String message; boolean succeeded=false;
            // A（InfixIntent）：先做一次纯程序的祈使句解析——"改为 X"/"加入 X"/"删掉 X"/"把 X 改成 Y"。
            // 认不出来就是 null，整条仍然交给模型；认出来但**要写入的内容含中文**时也不能直接落地（见下）。
            InfixIntent.Intent intent = InfixIntent.parse(instruction);
            try {
                SdClient.Prompts original = effectivePrompts(scope);
                // 词库约束只在会话显式开启时生效；确定性与模型两条路共用这一个开关。
                boolean strictDictionary = settings.infixFilterEnabled(ChatService.conversationKey(event));
                // 传统正反义词排斥器（默认关）：只在会话显式 .infix conflict on 时才做本地互斥整理。
                boolean conflict = settings.infixConflictEnabled(ChatService.conversationKey(event));
                // 纯"按类别筛选"的改写要求（"仅保留人物和服饰，其余清空"）：程序按分类直接执行，
                // 不花模型额度、也不会因为模型自由发挥而漏删词条。分类清单与投喂给模型的是同一份。
                CategorySurgery surgery = parseCategorySurgery(instruction);
                if (surgery != null) {
                    message = categoryFilter(scope, surgery.keepOnly(),
                            String.join(" ", surgery.keepOnly() ? surgery.keep() : surgery.remove()));
                    succeeded = true;
                } else {
                    // A 为什么没落地（null = 能直接落地）。失败回执要说清是哪一步没懂，所以原因在这里就算好。
                    String blocked = infixIntentBlocked(intent, original, strictDictionary);
                    if (intent != null && blocked == null) {
                        // 确定性快速路径：句式清楚、内容也是能直接写进 prompt 的英文词条/模型标签。
                        // 不花模型额度，也不会再出现"模型没给出可执行指令"那种什么都没发生的结果。
                        message = applyInfixIntent(scope, original, intent, instruction, conflict);
                        succeeded = true;
                    } else {
                        ModelOutcome outcome = infixByModel(scope, original, instruction, intent, blocked, strictDictionary, conflict);
                        message = outcome.message();
                        // 改写调用本身失败时仍然算这一步没成功（链式指令的后续步骤必须停下，
                        // 否则会拿着没改过的 prompt 继续 .gen）——与改动前的外层 catch 语义一致。
                        succeeded = outcome.succeeded();
                    }
                }
            } catch (Exception e) { message = "智能修改未完成：" + error(e); }
            finally { progenBusy.set(false); }
            Log.info(message);
            // 改了就是一句肯定，没改成就是那句失败说明——同一处只出一条（normal 下由收据窗口定稿）。
            replyRanked(context, succeeded ? ReceiptRank.AFFIRM : ReceiptRank.FAILURE, message);
            completeChatWorkflowStep(context,succeeded);
            releaseNormalWindow(context);
        }); } catch (RejectedExecutionException e) { progenBusy.set(false);completeChatWorkflowStep(event,false);releaseNormalWindow(context);throw new IllegalStateException("机器人正在关闭。"); }
    }
    /**
     * A（{@link InfixIntent}）识别出的祈使句**能不能由程序直接落地**；能落地返回 {@code null}，
     * 不能落地返回一句"为什么不能"的中文说明（失败回执要原样告诉用户是哪一步没懂）。
     *
     * <p>三条判据：
     * 1. **要写入的内容必须是能直接进 prompt 的英文词条/模型标签**：含汉字（等 SD 读不了的脚本）的
     *    <b>新内容</b>一律不算，必须交给模型翻译——prompt 里不许出现中文词条；只有本来就已经在 prompt 里的
     *    中文词条才允许继续使用/删除（历史遗留的个人提示词）；
     * 2. 会话开启了标准词库约束（{@code .infix filter on}）时，新词条还必须在词库里；不在就交给模型+词库过滤，
     *    保持"约束开启时新词必须是标准词条"这条既有行为；
     * 3. 词级替换（SWAP）的来源必须在**指定那一侧**里找得到对应词条，找不到就不猜
     *    （例如「把画面从肛门插入改成…」——"画面从肛门插入"不是词条，只有模型能对上）。
     */
    private String infixIntentBlocked(InfixIntent.Intent intent, SdClient.Prompts original, boolean strictDictionary) {
        if (intent == null) return null;
        boolean negative = intent.side() == InfixIntent.Side.NEGATIVE;
        String side = negative ? original.negative() : original.positive();
        String label = negative ? "反向" : "正向";
        try {
            PromptEditor.parts(side);   // 提示词本身写坏了（括号不匹配）时不接管，交给模型去修
        } catch (IllegalArgumentException broken) {
            return label + " prompt 的括号/转义不完整，程序不敢直接改它（" + broken.getMessage() + "）。";
        }
        if (intent.kind() == InfixIntent.Kind.SWAP)
            for (String from : intent.from())
                if (!InfixIntent.matchesAny(side, from))
                    return "词级替换的来源「" + from + "」在" + label + " prompt 里找不到对应词条，我不猜该动哪一个。";
        for (String term : intent.terms()) {
            if (!InfixIntent.hasCjk(term) || TermCategories.isLoraOrEmbedding(term)) continue;
            if (InfixIntent.matchesAny(side, term)) continue;
            return "要写入的内容「" + term + "」是中文描述：prompt 里不许出现中文词条，"
                    + "得让模型翻译成标准英文词条（写成英文我就能直接改）。";
        }
        if (strictDictionary && !dictionaryAllows(original, intent.terms()))
            return "标准词库约束已开启（.infix filter on），而新词条" + InfixIntent.brief(intent.terms())
                    + "不在词库 data/prompt-tags.txt 里，所以交给模型+词库过滤处理。";
        return null;
    }
    /** 标准词库约束开启时，确定性路径也要过同一道词库关（新词必须是词库或现有 prompt 里的词条）。 */
    private boolean dictionaryAllows(SdClient.Prompts original, List<String> terms) {
        if (terms.isEmpty()) return true;
        try {
            Set<String> allowed = vocabulary(settings.root, original);
            for (String term : terms) if (!allowedContains(allowed, term)) return false;
            return true;
        } catch (Exception unavailable) {
            Log.warn("标准词库读取失败，这次改写交给模型：" + error(unavailable));
            return false;
        }
    }
    /**
     * 模型改写路径（默认链路）。
     *
     * <p>注意：A 的落地判断**不在模型失败之后才做**，而是在调用模型之前就做（见 {@link #infix}）——
     * 能确定性执行的要求根本走不到这里，所以"模型没给出指令"对这类要求不会再发生。走到这里的只剩两类：
     * 句式认不出来（{@code intent == null}）与认出来了但不能直接落地（中文描述型等，{@code blocked != null}）。
     * 后者第一次改写没给出可应用的变化时，会用更严格的指令再问一次（见 {@link #infixTranslationRetry}）。
     */
    private ModelOutcome infixByModel(String scope, SdClient.Prompts original, String instruction, InfixIntent.Intent intent,
                                      String blocked, boolean strictDictionary, boolean conflict) throws Exception {
        // 提示词改写走生图频道：与聊天频道的模型/密钥/额度完全分开。
        DeepSeekPrompts client = clientFor();
        // 自由改写：不做拆解、不喂候选词，模型按自己的判断改写；词库约束只在显式开启时生效。
        // 同时把当前 prompt 的**分类清单**交给模型，让它能理解"按类别"的要求（只保留人物和服饰）。
        DeepSeekPrompts.Result rewritten = null;
        String modelError = null;
        try { rewritten = client.edit(instruction, original, TermCategories.describe(usageIndex(), original.positive())); }
        catch (Exception model) { modelError = error(model); Log.warn("提示词改写调用没有成功：" + modelError); }
        if (rewritten == null)   // 调用失败：这一步不算成功（链式指令的后续步骤要停下）
            return new ModelOutcome(infixIntentFallback(scope, original, instruction, intent, blocked, List.of(), modelError, strictDictionary, conflict), false);
        InfixFilter filtered = strictDictionary
                ? filterInfixVocabulary(settings.root, original, rewritten)
                : new InfixFilter(rewritten, List.of());
        String applied = applyModelRewrite(scope, original, filtered.result(), filtered.rejected(), instruction, conflict);
        if (applied != null) return new ModelOutcome(applied, true);
        // 模型结果与当前提示词一致（没有可应用的变化）→ 走兜底：中文描述型再严格重试一次，否则如实说明。
        // 调用本身是成功的，所以这一步仍算成功（与改动前"没有可应用的变化"回执的语义一致）。
        return new ModelOutcome(infixIntentFallback(scope, original, instruction, intent, blocked, filtered.rejected(), null, strictDictionary, conflict), true);
    }
    /** 一次模型改写路径的结果：给用户的回执 + 这一步算不算成功（链式指令据此决定要不要往下走）。 */
    private record ModelOutcome(String message, boolean succeeded) { }
    /**
     * 应用模型给出的改写，返回回执；模型结果与当前提示词完全一致（没有可应用的变化）时返回 {@code null}，
     * 由调用方走兜底。并发保护、函数集归属、互斥自检与互斥替换都保持原样。
     *
     * <p>{@code conflict} 是**传统正反义词排斥器**的开关（默认关）：关掉时本地不再按互斥词表删/改词条，
     * 正反向是否自相矛盾交给改写之后那次整份提示词画面检查（DeepSeek）判断——剖面图与正面视角这类
     * 实际并不冲突的组合不会再被本地误删。
     */
    private String applyModelRewrite(String scope, SdClient.Prompts original, DeepSeekPrompts.Result result,
                                     List<String> rejected, String instruction, boolean conflict) throws Exception {
        synchronized (userPrompts) {
            SdClient.Prompts current=userPrompts.prompts(scope);
            if(!current.positive().equals(original.positive()) || !current.negative().equals(original.negative()))
                throw new IllegalStateException("等待 DeepSeek 时提示词已被修改，本次结果未覆盖新内容，请重新 /infix。");
            if(result.positive().equals(original.positive()) && result.negative().equals(original.negative())) return null;
            PromptFunctions functions = new PromptFunctions(settings.root); functions.recover(() -> userPrompts.prompts(scope), scope);
            SdClient.Prompts updated = applyPersonalInfix(userPrompts, scope, original, result);
            functions.forget(scope, false, List.of(), true); functions.forget(scope, true, List.of(), true);
            // 冲突自检：只在传统正反义词排斥器打开时执行（默认关闭，交给整份提示词画面检查）。
            String fixed = conflict ? selfCheckPrompts(scope) : "";
            if (!fixed.isEmpty()) updated = userPrompts.prompts(scope);
            // 互斥原则还要"以本次要求为准"：用户这次改的槽位（地点/姿势/视角/载具/时段），
            // 旧值必须让位——光靠"后出现的胜"会因为模型把新词写在前面而留下旧的。
            List<String> replaced = new ArrayList<>();
            String enforced = conflict
                    ? enforceRequestedFamilies(userPrompts.prompts(scope).positive(), instruction, replaced)
                    : userPrompts.prompts(scope).positive();
            if (!enforced.isBlank() && !replaced.isEmpty()
                    && !enforced.equals(userPrompts.prompts(scope).positive())) {
                userPrompts.replace(scope, new SdClient.Prompts(enforced, userPrompts.prompts(scope).negative(), UserPromptStore.PERSONAL_SOURCE));
                updated = userPrompts.prompts(scope);
            }
            return "智能修改已应用，本次变化：\n" + formatPromptDiff(original, updated)
                    + formatRejected(rejected)
                    + (fixed.isEmpty() ? "" : "\n自检修正：" + fixed)
                    + (replaced.isEmpty() ? "" : "\n互斥替换：" + String.join("、", replaced))
                    + "（用 .prompt 查看完整提示词）";
        }
    }
    /**
     * 模型没给出可应用改动时的兜底：要求里有中文描述（A 认得出句式、但内容必须由模型翻译成英文词条）时，
     * 用更严格的指令再问一次；仍然不行就如实说明是哪一步没懂。
     */
    private String infixIntentFallback(String scope, SdClient.Prompts original, String instruction, InfixIntent.Intent intent,
                                       String blocked, List<String> rejected, String modelError, boolean strictDictionary,
                                       boolean conflict) throws Exception {
        if (modelError == null && InfixIntent.hasCjk(instruction)) {
            String retry = infixTranslationRetry(scope, original, instruction, strictDictionary, conflict);
            if (retry != null) return retry;
        }
        return infixNothingUnderstood(intent, instruction, rejected, modelError, blocked);
    }
    /**
     * 中文描述型的第二次尝试：第一次改写没产出变化时，明确要求它"只返回改好的完整提示词 JSON"，
     * 并把中文必须翻译成英文词条这条硬规矩再写一遍。成功返回回执，失败返回 {@code null}（由调用方报失败）。
     */
    private String infixTranslationRetry(String scope, SdClient.Prompts original, String instruction, boolean strictDictionary,
                                         boolean conflict) {
        String nudge = "【重试】这条要求里有中文描述，必须由你翻译成标准英文词条后落实；"
                + "只返回 JSON：{\"positive\":\"...\",\"negative\":\"...\"}（改好后的完整提示词），"
                + "不要解释、不要 Markdown、不要把任何中文写进 positive/negative。原要求：";
        if (instruction.length() + nudge.length() > 8000) return null;
        try {
            DeepSeekPrompts.Result again = clientFor()
                    .edit(nudge + instruction, original, TermCategories.describe(usageIndex(), original.positive()));
            InfixFilter filtered = strictDictionary
                    ? filterInfixVocabulary(settings.root, original, again)
                    : new InfixFilter(again, List.of());
            String applied = applyModelRewrite(scope, original, filtered.result(), filtered.rejected(), instruction, conflict);
            if (applied == null) { Log.info("中文描述型要求严格重试后仍无有效变化（" + scope + "）"); return null; }
            Log.info("中文描述型要求严格重试后已应用（" + scope + "）");
            return "（第一次改写没有给出可应用的修改，用更严格的要求重试后成功）\n" + applied;
        } catch (Exception failed) {
            Log.warn("中文描述型改写的严格重试没有成功：" + error(failed));
            return null;
        }
    }
    /**
     * 失败回执：说清是**哪一步**没懂/没落地，并列出我认得的形式（旧的"我没有解析出可执行的指令"太笼统，
     * 用户不知道该改哪里，线上这类回执出现过 65 次）。
     */
    private String infixNothingUnderstood(InfixIntent.Intent intent, String instruction, List<String> rejected,
                                          String modelError, String blocked) {
        StringBuilder out = new StringBuilder();
        if (modelError != null) out.append("智能修改未完成：").append(modelError).append('\n');
        else out.append("这次没有改动任何词条：改写模型没有给出可应用的修改。\n");
        if (intent == null) {
            out.append(InfixIntent.hasCjk(instruction)
                    ? "这条要求里有中文画面描述，我不把它当词条直接写进 prompt（SD 只认英文词条），需要模型翻译成标准英文词条，"
                        + "但它这次没有给出可用的结果。\n"
                    : "我也没能从这条要求里认出「要改哪一步」：它不像单条祈使句（可能是多步、带条件，或只是一句画面描述），"
                        + "所以一个字都没改。\n");
        } else {
            out.append("我认出了这是「").append(intent.note()).append("」，但没有落地：")
                    .append(blocked == null ? "改写模型没有给出可用的结果。" : blocked).append('\n');
        }
        out.append(InfixIntent.formsHelp());
        out.append(formatRejected(rejected));
        return out.toString();
    }
    /**
     * 把 A 解析出的祈使句直接落到个人提示词上。文本变换在 {@link InfixIntent#apply}（纯函数、可单测）里，
     * 这里负责落盘、函数集归属、与模型路径相同的两道互斥自检、以及回执；
     * **只动指令指定的那一侧**，另一侧一个字符都不碰。
     */
    private String applyInfixIntent(String scope, SdClient.Prompts original, InfixIntent.Intent intent, String instruction,
                                    boolean conflict) throws Exception {
        boolean negative = intent.side() == InfixIntent.Side.NEGATIVE;
        String before = negative ? original.negative() : original.positive();
        String label = negative ? "反向" : "正向";
        String other = negative ? original.positive() : original.negative();
        InfixIntent.Change change = InfixIntent.apply(before, intent);
        if (change.prompt().equals(before))
            return "这次没有改动任何词条：识别为「" + intent.note() + "」，但" + label + " prompt 里没有"
                    + InfixIntent.brief(change.missing().isEmpty() ? intent.terms() : change.missing())
                    + otherSideHint(other, intent) + "\n（用 .prompt 查看完整提示词）";
        synchronized (userPrompts) {
            SdClient.Prompts current = userPrompts.prompts(scope);
            if (!current.positive().equals(original.positive()) || !current.negative().equals(original.negative()))
                throw new IllegalStateException("提示词已被修改，本次结果未覆盖新内容，请重新 /infix。");
            PromptFunctions functions = new PromptFunctions(settings.root);
            functions.recover(() -> userPrompts.prompts(scope), scope);
            SdClient.Prompts updated = userPrompts.replace(scope, new SdClient.Prompts(
                    negative ? original.positive() : change.prompt(),
                    negative ? change.prompt() : original.negative(), UserPromptStore.PERSONAL_SOURCE));
            // 函数集归属：整体替换等于这一侧不再由函数集拥有；增/删/换只放弃动过的那几个词条。
            if (intent.kind() == InfixIntent.Kind.REPLACE) functions.forget(scope, negative, List.of(), true);
            else functions.forget(scope, negative, change.changed(), false);
            // 与模型改写路径相同的两道程序侧自检：它们本来就是"任何改写都不许在 prompt 里留下矛盾"的
            // 全局硬规矩（性交类补 1boy、同族词条让位），确定性路径不能因为"没经过模型"就跳过。
            // 传统正反义词排斥器默认关闭（见 Settings#infixConflictEnabled）：关掉时这两道本地互斥整理都不做，
            // 矛盾交给改写之后那次整份提示词画面检查判断。
            String fixed = conflict ? selfCheckPrompts(scope) : "";
            if (!fixed.isEmpty()) updated = userPrompts.prompts(scope);
            List<String> replaced = new ArrayList<>();
            String enforced = conflict
                    ? enforceRequestedFamilies(userPrompts.prompts(scope).positive(), instruction, replaced)
                    : userPrompts.prompts(scope).positive();
            if (!enforced.isBlank() && !replaced.isEmpty()
                    && !enforced.equals(userPrompts.prompts(scope).positive())) {
                userPrompts.replace(scope, new SdClient.Prompts(enforced, userPrompts.prompts(scope).negative(), UserPromptStore.PERSONAL_SOURCE));
                updated = userPrompts.prompts(scope);
            }
            return "已按祈使句直接修改（没有使用模型，也没有花 DeepSeek 额度）：\n" + formatPromptDiff(original, updated)
                    + "\n识别为：" + intent.note()
                    + (fixed.isEmpty() ? "" : "\n自检修正：" + fixed)
                    + (replaced.isEmpty() ? "" : "\n互斥替换：" + String.join("、", replaced))
                    + (intent.kind() == InfixIntent.Kind.REPLACE && !change.unchanged().isEmpty()
                        ? "\nLoRA/嵌入标签原样保留：" + InfixIntent.brief(change.unchanged()) : "")
                    + (intent.kind() != InfixIntent.Kind.REPLACE && !change.unchanged().isEmpty()
                        ? "\n已存在，未重复加：" + InfixIntent.brief(change.unchanged()) : "")
                    + (change.missing().isEmpty() ? "" : "\n没找到这些词条：" + InfixIntent.brief(change.missing()))
                    + "\n（用 .prompt 查看完整提示词）";
        }
    }
    /**
     * 正向没找到、另一侧里却有时，提示用户"要删那边请说反向删掉 …"——
     * 删哪一侧由用户说了算，程序不替他猜着去动另一侧。
     */
    private String otherSideHint(String other, InfixIntent.Intent intent) {
        if (intent.kind() != InfixIntent.Kind.REMOVE) return "";
        List<String> found = new ArrayList<>();
        for (String term : intent.terms()) if (InfixIntent.matchesAny(other, term)) found.add(term);
        if (found.isEmpty()) return "";
        return "（" + InfixIntent.brief(found) + " 在另一侧 prompt 里；要删那边请说「反向删掉 …」——"
                + "我只动你指定的那一侧，不去猜）";
    }
    /**
     * 在 LoRA 目录里定位一个 LoRA 文件：优先用 WebUI 官方列表给的路径（名称/别名/文件名都能认），
     * 列表里还没有的（刚放进去、SD 未刷新）再按文件名扫描目录。找不到返回 null。
     */
    private Path loraFile(Path directory, String requested) {
        String wanted = requested == null ? "" : requested.strip();
        if (wanted.isEmpty()) return null;
        try {
            List<SdClient.Lora> catalog = sd.loras();
            for (SdClient.Lora item : catalog)
                if (item.name().equals(wanted) || item.alias().equals(wanted)) return existing(Path.of(item.path()));
            for (SdClient.Lora item : catalog)
                if (item.name().equalsIgnoreCase(wanted) || item.alias().equalsIgnoreCase(wanted)) return existing(Path.of(item.path()));
            for (SdClient.Lora item : catalog) {
                String stem = item.path().replaceFirst("(?i)\\.safetensors$", "");
                if (stem.endsWith("/" + wanted) || stem.endsWith("\\" + wanted)) return existing(Path.of(item.path()));
            }
        } catch (Exception ignored) { /* WebUI 离线时退回扫目录 */ }
        if (directory == null || !Files.isDirectory(directory)) return null;
        try (var files = Files.list(directory)) {
            for (Path candidate : files.filter(Files::isRegularFile).toList()) {
                String fileName = candidate.getFileName().toString();
                String stem = fileName.replaceFirst("(?i)\\.safetensors$", "");
                if (stem.equalsIgnoreCase(wanted) || fileName.equalsIgnoreCase(wanted)) return candidate;
            }
        } catch (Exception ignored) { /* 读不到目录就当找不到 */ }
        return null;
    }
    /** 只有磁盘上真的存在这个文件才算找到：WebUI 列表可能还留着刚删掉的旧条目。 */
    private static Path existing(Path candidate) {
        return candidate != null && Files.isRegularFile(candidate) ? candidate : null;
    }
    /**
     * SD WebUI 的生成进度（网页控制台与 /progress 指令共用一份实现）。
     * 队列状态也一并给出：进度来自 SD 自己，队列来自机器人，两者对不上时能立刻看出来。
     */
    public JsonObject webProgress() {
        SdClient.GenerationProgress progress = sd.progress();
        JsonObject result = new JsonObject();
        result.addProperty("reachable", progress.reachable());
        result.addProperty("running", progress.running());
        result.addProperty("percent", Math.round(progress.percent() * 100));
        result.addProperty("step", progress.step());
        result.addProperty("steps", progress.steps());
        result.addProperty("etaSeconds", Math.round(progress.etaSeconds()));
        result.addProperty("job", progress.job());
        result.addProperty("text", progress.describe());
        result.addProperty("queue", generationStatus());
        return result;
    }
    /** LoRA/LyCORIS tags currently in a scope's positive prompt: a rewrite must never silently drop them. */
    static List<String> loraTagsIn(String prompt) {
        List<String> tags = new ArrayList<>();
        if (prompt == null) return tags;
        Matcher matcher = Pattern.compile("(?i)<(?:lora|lyco|locon):[^>]+>").matcher(prompt);
        while (matcher.find()) if (!tags.contains(matcher.group())) tags.add(matcher.group());
        return tags;
    }
    /** Puts back any LoRA tag a rewrite dropped after it had been loaded. */
    private void restoreLoraTags(JsonObject event, List<String> loadedBefore) {
        restoreLoraTags(event, loadedBefore, Set.of());
    }
    /**
     * Puts back any LoRA tag a rewrite dropped after it had been loaded.
     *
     * <p>{@code keepRemoved} 是"用户这次明确要求删掉、就不要再恢复"的标签：不说这一句的话，
     * 「删除 saku kanb」这种明确要求会被安全网原样加回去，等于没听用户的（见 {@link #lorasRequestedRemoved}）。
     */
    void restoreLoraTags(JsonObject event, List<String> loadedBefore, Set<String> keepRemoved) {
        if (loadedBefore == null || loadedBefore.isEmpty()) return;
        try {
            String scope = promptScope(event);
            SdClient.Prompts current = userPrompts.prompts(scope);
            List<String> present = loraTagsIn(current.positive());
            List<String> missing = new ArrayList<>(), skipped = new ArrayList<>();
            for (String tag : loadedBefore) {
                if (present.contains(tag)) continue;
                if (keepRemoved.contains(tag)) skipped.add(tag);
                else missing.add(tag);
            }
            if (!skipped.isEmpty()) Log.info("按要求保持移除（不自动恢复）的 LoRA 标签：" + String.join(", ", skipped));
            if (missing.isEmpty()) return;
            String positive = current.positive().isBlank() ? String.join(", ", missing)
                    : current.positive().strip().replaceAll(",\\s*$", "") + ", " + String.join(", ", missing);
            userPrompts.replace(scope, new SdClient.Prompts(positive, current.negative(), UserPromptStore.PERSONAL_SOURCE));
            Log.info("改写丢失了 LoRA 标签，已恢复：" + String.join(", ", missing));
            reply(event, "已恢复被改写丢掉的 LoRA 标签：" + String.join("、", missing));
        } catch (Exception error) {
            Log.warn("恢复 LoRA 标签失败：" + error(error));
        }
    }

    /** 删除类措辞：出现这些词才认为用户是在"让 LoRA 走开"，而不是普通改写。 */
    private static final Pattern REMOVAL_WORDS = Pattern.compile(
            "删除|删掉|去掉|移除|去除|不用|不要|取消|清理|撤掉|换掉|drop|remove|delete|without");
    /**
     * 用户这次明确点名要删掉的 LoRA 标签。
     *
     * <p>判断方式：请求里有删除类措辞 + 提到了某个标签**模型名的一部分**（≥4 个字符的前缀，
     * 所以「删除 saku kanb」能同时对上 `Sakuraba_Victoria_Ruri` 与 `Kanbe_Kotori`）。
     * 只按模型名匹配（不是 "lora" 这个词），避免「不要动 LoRA，换个夜景」被误判成删除。
     */
    static Set<String> lorasRequestedRemoved(String request, List<String> tags) {
        Set<String> removed = new LinkedHashSet<>();
        if (request == null || request.isBlank() || tags == null || tags.isEmpty()) return removed;
        String lower = request.toLowerCase(Locale.ROOT);
        if (!REMOVAL_WORDS.matcher(lower).find()) return removed;
        String compact = lower.replaceAll("[\\s_\\-（）()【】\\[\\]「」,，、:：]+", "");
        for (String tag : tags) {
            // <lora:Kanbe_Kotori_1_nai:1> → Kanbe_Kotori_1_nai
            String stem = tag.replaceFirst("(?i)^<[^:>]+:", "").replaceFirst(":[^:>]*>$", "");
            for (String word : stem.split("[^\\p{Alnum}]+")) {
                String name = word.toLowerCase(Locale.ROOT);
                if (name.length() < 4) continue;
                boolean hit = false;
                // 从最长的前缀开始比：用户写全名对得上，写 "saku" 这种缩写也对得上。
                for (int length = name.length(); length >= 4 && !hit; length--) hit = compact.contains(name.substring(0, length));
                if (hit) { removed.add(tag); break; }
            }
        }
        return removed;
    }
    /**
     * Tags the request asks for that the prompt does not express yet, in table order and without duplicates.
     * A synonym that is already present covers its whole group, so nothing is added twice.
     */
    static List<String> missingRequestedTags(String request, String positive, Set<String> allowed) {
        List<String> missing = new ArrayList<>();
        if (request == null || request.isBlank()) return missing;
        for (Map.Entry<String, List<String>> entry : SCENE_SYNONYMS.entrySet()) {
            List<Integer> at = occurrences(request, entry.getKey());
            if (at.isEmpty()) continue;
            // "把校服换成军装" names 校服 only as the item being replaced: adding it back would contradict the
            // request, so a key that is always a replacement source is skipped.
            if (at.stream().allMatch(index -> replacedAway(request, entry.getKey(), index))) continue;
            List<String> candidates = new ArrayList<>();
            for (String candidate : entry.getValue()) if (allowedContains(allowed, candidate)) candidates.add(candidate);
            if (candidates.isEmpty()) continue;
            if (candidates.stream().anyMatch(candidate -> usesTerm(positive, candidate))) continue;
            String chosen = candidates.get(0);
            if (!missing.contains(chosen)) missing.add(chosen);
        }
        return missing;
    }
    private static List<Integer> occurrences(String text, String key) {
        List<Integer> found = new ArrayList<>();
        for (int index = text.indexOf(key); index >= 0; index = text.indexOf(key, index + 1)) found.add(index);
        return found;
    }
    /**
     * True when the wording at this occurrence marks the item as the one being replaced or removed, as in
     * "把校服换成军装" / "将森林改为草地" / "去掉森林". Only then must the item not be added back.
     */
    static boolean replacedAway(String request, String key, int at) {
        if (request == null || key == null || at < 0 || at + key.length() > request.length()) return false;
        String before = request.substring(Math.max(0, at - 8), at);
        String after = request.substring(at + key.length(), Math.min(request.length(), at + key.length() + 8));
        boolean carried = Pattern.compile("(把|将|给|让)[^，。；,;!？?]{0,6}$").matcher(before).find();
        boolean swapped = Pattern.compile("^\\s*(换成|换为|改为|改成|改作|替换成|替换为|变成|变为|转为)").matcher(after).find();
        if (carried && swapped) return true;
        return Pattern.compile("(移除|删掉|删除|去掉|去掉|不要|不用|脱下|去除|清除)[^，。；,;!？?]{0,4}$").matcher(before).find();
    }
    /** Antonym token pairs used to detect contradictory tags in general, not case by case. */
    private static final List<String[]> ANTONYMS = List.of(
            new String[]{"front", "back"}, new String[]{"open", "closed"}, new String[]{"long", "short"},
            new String[]{"big", "small"}, new String[]{"large", "small"}, new String[]{"left", "right"},
            new String[]{"up", "down"}, new String[]{"top", "bottom"}, new String[]{"inside", "outside"},
            new String[]{"indoors", "outdoors"}, new String[]{"day", "night"}, new String[]{"dark", "bright"},
            new String[]{"wet", "dry"}, new String[]{"hot", "cold"}, new String[]{"high", "low"},
            new String[]{"wide", "narrow"}, new String[]{"thick", "thin"}, new String[]{"near", "far"},
            new String[]{"full", "empty"}, new String[]{"old", "young"}, new String[]{"awake", "sleeping"},
            new String[]{"standing", "sitting"}, new String[]{"standing", "lying"}, new String[]{"sitting", "lying"},
            new String[]{"looking_at_viewer", "looking_away"}, new String[]{"facing_viewer", "facing_away"});
    /** 互斥族：同一族里只保留一个（后出现的胜），用于姿势/视角/载具/室内外这类不能共存的词条。 */
    private static final List<java.util.regex.Pattern> EXCLUSIVE_FAMILIES = List.of(
            java.util.regex.Pattern.compile("^(standing|sitting|lying|kneeling|squatting|on_back|on_stomach|on_side|crossed_legs)$"),
            java.util.regex.Pattern.compile("^(front_view|back_view|from_side|from_above|from_below|from_behind|three_quarter_view|dutch_angle)$"),
            java.util.regex.Pattern.compile("^.*(_view)$"),
            // 载具：地铁与汽车不会同时出现（"subway" 与 "car" 并存会让画面自相矛盾）；
            // 但 train_interior / interior 是"车厢内部"的视角词，和 subway 互补，不算冲突。
            java.util.regex.Pattern.compile("^(subway|train|car|taxi|bus|airplane|aircraft|ship|boat|bicycle|motorcycle|scooter|carriage|truck|van|helicopter|rocket)$"),
            java.util.regex.Pattern.compile("^(indoors|outdoors|indoor|outdoor)$"));
    /** 画风/质量类词条：用户没提风格时，改写不该自己加这些（realistic、highres…）。 */
    private static final java.util.Set<String> STYLE_ONLY_TAGS = Set.of(
            "realistic", "photorealistic", "photo_realistic", "hyperrealistic", "photorealism", "semi_realistic",
            "highres", "hi_res", "absurdres", "8k", "4k", "16k", "uhd", "hd",
            "masterpiece", "best_quality", "amazing_quality", "good_quality", "ultra_detailed", "extremely_detailed",
            "highly_detailed", "detailed", "ultra-detailed", "sharp_focus");
    /** 用户没提风格时，判断某个词条是不是"画风/质量"类（带权重包裹的也认）。 */
    static boolean isStyleOnlyTag(String term) {
        String key = PromptEditor.key(term);
        if (STYLE_ONLY_TAGS.contains(key)) return true;
        for (String token : key.split("[^a-z0-9]+"))
            if (STYLE_ONLY_TAGS.contains(token)) return true;
        return false;
    }
    /** 用户这句话有没有在要求画风/质量。 */
    static boolean mentionsStyle(String instruction) {
        return instruction != null && instruction.matches("(?s).*(风格|画风|写实|真实感|照片|photo|realistic|render|"
                + "厚涂|平涂|赛璐璐|动漫风|插画|手绘|3d|2\\.5d|像素|水彩|油画|quality|质量|高清|细节).*");
    }
    /**
     * 验收清理：把"这次新加、但用户没要求"的词条去掉——
     * 1) 与用户明确要求的词条同族互斥的（要求 subway 时不能又加 car）；
     * 2) 用户没提风格时新加的画风/质量词条（realistic、highres…）。
     * 只动这一次新加的词条，用户原有的内容一律保留。
     */
    static String dropUnrequestedTags(String positive, String original, List<String> required, boolean dropStyle, List<String> removed) {
        List<String> before = PromptEditor.parts(original == null ? "" : original);
        List<String> kept = new ArrayList<>();
        for (String term : PromptEditor.parts(positive == null ? "" : positive)) {
            String key = PromptEditor.key(term);
            boolean fresh = before.stream().noneMatch(existing -> PromptEditor.key(existing).equals(key));
            if (!fresh) { kept.add(term); continue; }
            String conflict = null;
            for (String wanted : required == null ? List.<String>of() : required) {
                if (sameFamily(key, PromptEditor.key(wanted))) { conflict = wanted; break; }
            }
            if (conflict != null) { removed.add(term + "（与要求的 " + conflict + " 冲突）"); continue; }
            if (dropStyle && isStyleOnlyTag(term)) { removed.add(term + "（用户没有要求画风/质量）"); continue; }
            // 新加的多词词条统一写成下划线形式（sexual harassment → sexual_harassment），与词库写法一致。
            kept.add(cn.szu.bot.prompt.PromptUsage.tagSpelling(term));
        }
        return String.join(", ", kept);
    }
    private static boolean sameFamily(String first, String second) {
        if (first.equals(second)) return false;
        return EXCLUSIVE_FAMILIES.stream().anyMatch(family -> family.matcher(first).matches() && family.matcher(second).matches());
    }
    /** 两个词条是否互斥（姿势/视角/载具/室内外），供拆解层去掉同一条里的自相矛盾候选。 */
    public static boolean tagsConflict(String first, String second) {
        return sameFamily(PromptEditor.key(first), PromptEditor.key(second));
    }
    /** 互斥族里的全部成员（英文写法），用于识别"用户这句话到底要求了哪一族"。 */
    private static final List<String> EXCLUSIVE_FAMILY_MEMBERS = List.of(
            "standing", "sitting", "lying", "kneeling", "squatting", "on_back", "on_stomach", "on_side", "crossed_legs",
            "front_view", "back_view", "from_side", "from_above", "from_below", "from_behind", "three_quarter_view", "dutch_angle",
            "subway", "train", "car", "taxi", "bus", "airplane", "aircraft", "ship", "boat", "bicycle", "motorcycle",
            "scooter", "carriage", "truck", "van", "helicopter", "rocket",
            "indoors", "outdoors", "indoor", "outdoor", "day", "night");
    /**
     * 用户这句话明确要求的词条组（中文说法走 {@link #SCENE_SYNONYMS}，英文原词直接认）。
     * 每组是同一个槽位的候选写法：组里任意一个出现在改写结果里，就算用户的要求落实了。
     */
    static List<List<String>> requestedFamilies(String instruction) {
        List<List<String>> groups = new ArrayList<>();
        if (instruction == null || instruction.isBlank()) return groups;
        for (Map.Entry<String, List<String>> entry : SCENE_SYNONYMS.entrySet()) {
            int at = instruction.indexOf(entry.getKey());
            if (at < 0) continue;
            // "把校服换成军装"里的校服只是被替换的旧值，不是本次要求。
            if (replacedAway(instruction, entry.getKey(), at)) continue;
            List<String> group = new ArrayList<>();
            for (String candidate : entry.getValue()) if (!group.contains(candidate)) group.add(candidate);
            if (!group.isEmpty() && groups.stream().noneMatch(existing -> existing.equals(group))) groups.add(group);
        }
        String lower = instruction.toLowerCase(Locale.ROOT);
        for (String tag : EXCLUSIVE_FAMILY_MEMBERS) {
            if (!Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(tag) + "(?![a-z0-9_])").matcher(lower).find()) continue;
            List<String> group = List.of(tag);
            if (groups.stream().noneMatch(existing -> existing.contains(tag))) groups.add(group);
        }
        return groups;
    }
    /**
     * 互斥原则的兜底（程序侧）：用户这次明确要求的槽位一旦落实，同族的旧词条必须让位——
     * 要求"站着"就删掉旧的 sitting，要求"地铁"就删掉 car，要求"夜晚"就删掉 day。
     * 只有用户要的值确实在提示词里才动旧的（避免新值没加上、旧值又被删掉，两头落空）。
     */
    static String enforceRequestedFamilies(String positive, String instruction, List<String> replacedOut) {
        if (positive == null) return "";
        if (positive.isBlank() || instruction == null || instruction.isBlank()) return positive;
        List<List<String>> groups = requestedFamilies(instruction);
        if (groups.isEmpty()) return positive;
        List<String> terms = new ArrayList<>();
        for (String term : PromptEditor.parts(positive)) terms.add(PromptEditor.key(term).replace(' ', '_'));
        String fixed = positive;
        for (List<String> group : groups) {
            boolean present = terms.stream().anyMatch(term -> group.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(term)));
            if (!present) continue;
            for (String term : new ArrayList<>(terms)) {
                if (term.isBlank()) continue;
                if (group.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(term))) continue;
                boolean conflicts = sameFamily(term, group.get(0))
                        || group.stream().anyMatch(candidate -> antonymic(term, candidate));
                if (!conflicts) continue;
                fixed = removeTerm(fixed, term);
                if (replacedOut != null) replacedOut.add(term + "（与要求的 " + group.get(0) + " 互斥）");
                terms.set(terms.indexOf(term), "");
            }
        }
        return fixed;
    }
    /** Intercourse families: they only make sense together with a male partner in frame. */
    private static final java.util.regex.Pattern SEX_ACT = java.util.regex.Pattern.compile(
            "(^|_)(sex|vaginal|anal|penis|fellatio|cunnilingus|paizuri|handjob|irrumatio|deepthroat|nakadashi|creampie|missionary|doggystyle|straddle|sex_from_behind|cum_in_[a-z_]+)($|_)");
    private static final List<String> MALE_PARTNER = List.of("1boy", "2boys", "multiple_boys", "male", "man", "penis", "male_focus");

    /**
     * Post-rewrite self-check: sexual content must name the partner (1boy), and mutually exclusive tags are
     * resolved in favour of the newer one. Fixes are applied and reported, never left for the image to reveal.
     */
    String selfCheckPrompts(String scope) throws Exception {
        SdClient.Prompts current = userPrompts.prompts(scope);
        List<String> fixes = new ArrayList<>();
        String positive = fixPromptConflicts(current.positive(), fixes);
        if (fixes.isEmpty()) return "";
        userPrompts.replace(scope, new SdClient.Prompts(positive, current.negative(), UserPromptStore.PERSONAL_SOURCE));
        Log.info("提示词自检修正（" + scope + "）：" + String.join("、", fixes));
        return String.join("、", fixes);
    }
    /**
     * Pure self-check core. Rules are vocabulary-driven so any dictionary tag participates:
     * 1) an intercourse tag without a male/partner tag gets one; 2) tags sharing a suffix whose leading tokens
     * are antonyms (front_view/back_view, long_hair/short_hair) cannot coexist; 3) bare antonym pairs
     * (day/night) cannot coexist; 4) mutually exclusive families (pose, viewing angle) keep only the newest.
     */
    static String fixPromptConflicts(String positive, List<String> fixesOut) {
        if (positive == null || positive.isBlank()) return positive == null ? "" : positive;
        String fixed = positive;
        List<String> terms = new ArrayList<>();
        // PromptEditor keys normalise underscores to spaces; the rules below need canonical underscored tags.
        for (String term : PromptEditor.parts(fixed)) terms.add(PromptEditor.key(term).replace(' ', '_'));
        if (terms.stream().anyMatch(tag -> SEX_ACT.matcher(tag).find())
                && terms.stream().noneMatch(MALE_PARTNER::contains)) {
            fixed = appendTag(fixed, "1boy");
            fixesOut.add("补 1boy（含性交类词条）");
            terms.add("1boy");
        }
        fixed = dropConflicts(fixed, terms, fixesOut, false);
        fixed = dropConflicts(fixed, terms, fixesOut, true);
        return fixed;
    }
    /** Removes one side of every contradictory pair; {@code families} selects the family rule over antonyms. */
    private static String dropConflicts(String fixed, List<String> terms, List<String> fixesOut, boolean families) {
        Set<String> dropped = new HashSet<>();
        for (int i = 0; i < terms.size(); i++) {
            for (int j = i + 1; j < terms.size(); j++) {
                String first = terms.get(i), second = terms.get(j);
                if (dropped.contains(first) || dropped.contains(second)) continue;
                boolean conflict = families ? sameFamily(first, second) : antonymic(first, second);
                if (!conflict) continue;
                String drop = terms.indexOf(first) < terms.indexOf(second) ? first : second;   // keep the newer
                fixed = removeTerm(fixed, drop);
                dropped.add(drop);
                fixesOut.add("删除冲突词条 " + drop + "（与 " + (drop.equals(first) ? second : first) + " 互斥）");
                terms.set(terms.indexOf(drop), "");
            }
        }
        return fixed;
    }
    /** Two tags that share their trailing token but carry antonymic leading tokens contradict each other. */
    private static boolean antonymic(String first, String second) {
        if (first.isBlank() || second.isBlank() || first.equals(second)) return false;
        int cut = first.lastIndexOf('_');
        String head = cut < 0 ? first : first.substring(0, cut), tail = cut < 0 ? "" : first.substring(cut + 1);
        if (!tail.isEmpty()) {
            int otherCut = second.lastIndexOf('_');
            if (tail.equals(otherCut < 0 ? "" : second.substring(otherCut + 1))
                    && antonymTokens(head, otherCut < 0 ? second : second.substring(0, otherCut))) return true;
        }
        // Without a shared suffix only the exact antonym pairs count, so long_hair never clashes with short_sleeves.
        return exactAntonyms(first, second);
    }
    private static boolean exactAntonyms(String first, String second) {
        for (String[] pair : ANTONYMS)
            if ((first.equals(pair[0]) && second.equals(pair[1])) || (first.equals(pair[1]) && second.equals(pair[0]))) return true;
        return false;
    }
    private static boolean antonymTokens(String first, String second) {
        for (String[] pair : ANTONYMS) {
            if (matches(first, pair[0]) && matches(second, pair[1])) return true;
            if (matches(first, pair[1]) && matches(second, pair[0])) return true;
        }
        return false;
    }
    private static boolean matches(String tag, String token) {
        return tag.equals(token) || tag.contains(token + "_") || tag.contains("_" + token) || tag.startsWith(token);
    }
    private static String appendTag(String positive, String tag) {
        if (positive == null || positive.isBlank()) return tag;
        return positive.strip().replaceAll(",\\s*$", "") + ", " + tag;
    }
    private static String removeTerm(String positive, String tag) {
        List<String> kept = new ArrayList<>();
        for (String term : PromptEditor.parts(positive)) if (!PromptEditor.key(term).equals(PromptEditor.key(tag))) kept.add(term);
        return String.join(", ", kept);
    }
    /** Drops the listed terms from a prompt, keeping everything else byte-for-byte. */
    static String removeTerms(String prompt, List<String> terms) {
        String result = prompt == null ? "" : prompt;
        for (String term : terms) result = removeTerm(result, term);
        return result.strip().replaceAll("^,|,$", "").strip();
    }
    /**
     * The user's own wording names standard tags (晴天 → clear_sky, 铠甲 → armor …). A rewrite usually lands
     * them, but when the model paraphrased into a non-dictionary word the filter dropped it and the requested
     * change silently never reached the prompt. Those verified tags are appended at the end of the chain and
     * reported, so an explicit request is never lost.
     */
    void ensureRequestedTags(JsonObject event, List<String> commands) {
        try {
            if (commands == null) return;
            String scope = promptScope(event);
            // The request lives in the "/infix <要求>" step, not in event["message"]: a real QQ event
            // carries an array of segments there, so reading it as a string threw and silently disabled this
            // whole safety net (the only hint left was DeepSeek happening to emit a dictionary tag).
            Set<String> backfilled = new LinkedHashSet<>();
            for (String command : commands) {
                Matcher request = Pattern.compile("(?is)^[./]infix\\s+([\\s\\S]+)$").matcher(internalCommand(command == null ? "" : command));
                if (!request.matches() || request.group(1).isBlank()) continue;
                backfilled.addAll(backfillRequestedTags(scope, request.group(1).strip(), userPrompts.prompts(scope)));
            }
            Log.info("/infix 链路补齐检查：缺失=" + (backfilled.isEmpty() ? "（无）" : String.join("、", backfilled)));
            if (!backfilled.isEmpty()) reply(event, "已补充你要求的标准词条（改写未覆盖时由程序补上）：" + String.join("、", backfilled));
        } catch (Exception error) {
            Log.warn("补充用户要求的标准词条失败：" + error(error), error);        }
    }
    /**
     * Appends the canonical tags that the user's wording asks for but the current prompt does not express
     * yet, and returns them (empty when nothing was missing). Every tag is verified against the dictionary,
     * so a request can never silently disappear into a non-dictionary word.
     */
    private List<String> backfillRequestedTags(String scope, String instruction, SdClient.Prompts current) throws Exception {        if (instruction == null || instruction.isBlank()) return List.of();
        Set<String> allowed = vocabulary(settings.root, current);
        List<String> missing = missingRequestedTags(instruction, current.positive(), allowed);
        if (missing.isEmpty()) return List.of();
        String positive = current.positive().isBlank() ? String.join(", ", missing)
                : current.positive().strip().replaceAll(",\\s*$", "") + ", " + String.join(", ", missing);
        userPrompts.replace(scope, new SdClient.Prompts(positive, current.negative(), UserPromptStore.PERSONAL_SOURCE));
        Log.info("/infix 链路补充用户要求的标准词条：" + String.join(", ", missing));
        return missing;
    }
    /**
     * 纯函数版本的补齐判定：给定提示词与要求文本，返回应当补上的标准词条（不落盘）。
     * 线上路径与评测共用同一份逻辑，评测结果才对线上有意义。
     */
    static List<String> requestedTags(Path root, SdClient.Prompts current, String instruction) throws Exception {
        if (instruction == null || instruction.isBlank()) return List.of();
        return missingRequestedTags(instruction, current.positive(), vocabulary(root, current));
    }
    /** 前置拆解出的每一条简单修改都要各自补齐（逐条读回最新提示词，避免互相覆盖）。 */
    List<String> backfillRequestedTags(String scope, List<String> instructions) throws Exception {
        List<String> collected = new ArrayList<>();
        for (String instruction : instructions) {
            if (instruction == null || instruction.isBlank()) continue;
            collected.addAll(backfillRequestedTags(scope, instruction, userPrompts.prompts(scope)));
        }
        return collected;
    }
    /**
     * Applies an edited pair to one personal prompt copy, but only when it still matches what DeepSeek
     * was given: a concurrent edit by the same user wins and the model result is discarded.
     */
    public static SdClient.Prompts applyPersonalInfix(UserPromptStore store, String scope, SdClient.Prompts original,
                                                     DeepSeekPrompts.Result result) throws Exception {
        SdClient.Prompts current = store.prompts(scope);
        if (!current.positive().equals(original.positive()) || !current.negative().equals(original.negative()))
            throw new IllegalStateException("等待 DeepSeek 时提示词已被修改，本次结果未覆盖新内容，请重新 /infix。");
        return store.replace(scope, new SdClient.Prompts(result.positive(), result.negative(), UserPromptStore.PERSONAL_SOURCE));
    }

    /** 验收结果：result 是过滤后的提示词，rejected 是被丢弃的词条，converted 是被换成标准词的近义词。 */
    public record InfixFilter(DeepSeekPrompts.Result result,List<String> rejected,List<String> converted) {
        public InfixFilter(DeepSeekPrompts.Result result, List<String> rejected) { this(result, rejected, List.of()); }
    }
    /** Allowed vocabulary: the standard tag dictionary plus every term already present in the current prompts. */
    static Set<String> vocabulary(Path root, SdClient.Prompts original) throws Exception {
        Path file=root.resolve("data/prompt-tags.txt");
        if(!Files.isRegularFile(file)) throw new IOException("标准提示词词库 data/prompt-tags.txt 不存在，/infix 未应用。");
        if(Files.size(file)>16L*1024*1024) throw new IOException("prompt-tags.txt 超过 16MB，/infix 未应用。");
        Set<String> allowed=new HashSet<>();
        for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)) {
            if(line.isBlank() || line.strip().startsWith("#")) continue;
            for(String term:PromptEditor.parts(line)) allowed.add(PromptEditor.key(term));
        }
        // Existing custom/style/LoRA syntax may be retained or moved; only newly introduced terms require dictionary membership.
        for(String term:PromptEditor.parts(original.positive())) allowed.add(PromptEditor.key(term));
        for(String term:PromptEditor.parts(original.negative())) allowed.add(PromptEditor.key(term));
        return allowed;
    }
    /** Chinese text is never a valid SD tag: drop it unless the user's own prompt already contained it. */
    static String stripCjk(String text, String original) {
        if (text == null || text.isBlank()) return text;
        List<String> kept = new ArrayList<>();
        for (String term : PromptEditor.parts(text)) {
            if (term.matches("(?s).*[\\u4e00-\\u9fff].*") && (original == null || !original.contains(term))) continue;
            kept.add(term);
        }
        return String.join(", ", kept);
    }
    /** Remove only newly invented tags; valid and pre-existing prompt items remain applicable. */
    static InfixFilter filterInfixVocabulary(Set<String> allowed, DeepSeekPrompts.Result result) {
        List<String> rejected=new ArrayList<>();
        String positive=filterTerms(result.positive(),"正向",allowed,rejected);
        String negative=filterTerms(result.negative(),"反向",allowed,rejected);
        return new InfixFilter(new DeepSeekPrompts.Result(positive,negative),List.copyOf(rejected));
    }
    /**
     * 验收：先尝试把词库外的词换成含义相近的标准词（近义词/词形变体/拼写最接近的词库词），换不动的才丢弃。
     * 这样"返回的词都合规、但用户要的改动大部分缺失"不会再发生：能被词库表达的都会转成标准词落地。
     */
    static InfixFilter filterInfixVocabulary(Set<String> allowed, DeepSeekPrompts.Result result, boolean allowNew, PromptUsage usage) {
        if (usage == null) return filterInfixVocabulary(allowed, result, allowNew);
        List<String> rejected = new ArrayList<>(), converted = new ArrayList<>();
        String positive = filterTerms(result.positive(), "正向", allowed, rejected, allowNew, usage, converted, true);
        String negative = filterTerms(result.negative(), "反向", allowed, rejected, allowNew, usage, converted, false);
        return new InfixFilter(new DeepSeekPrompts.Result(positive, negative), List.copyOf(rejected), List.copyOf(converted));
    }
    /**
     * Same filter with the dictionary constraint switchable: with allowNew the model's own English tags are
     * kept (they are still reported), while a term the prompt already contained stays allowed as always.
     */
    static InfixFilter filterInfixVocabulary(Set<String> allowed, DeepSeekPrompts.Result result, boolean allowNew) {
        if (!allowNew) return filterInfixVocabulary(allowed, result);
        List<String> rejected = new ArrayList<>();
        String positive = filterTerms(result.positive(), "正向", allowed, rejected, true);
        String negative = filterTerms(result.negative(), "反向", allowed, rejected, true);
        return new InfixFilter(new DeepSeekPrompts.Result(positive, negative), List.of());
    }
    /** Words kept although the dictionary does not know them; listed in the receipt when the constraint is off. */
    static List<String> outsideDictionary(Set<String> allowed, DeepSeekPrompts.Result result) {
        List<String> found = new ArrayList<>();
        for (String field : List.of("正向", "反向")) {
            String text = field.equals("正向") ? result.positive() : result.negative();
            for (String term : PromptEditor.parts(text))
                if (!allowed.contains(PromptEditor.key(term)) && !found.contains(field + "：" + term)) found.add(field + "：" + term);
        }
        return found;
    }
    static InfixFilter filterInfixVocabulary(Path root, SdClient.Prompts original, DeepSeekPrompts.Result result) throws Exception {
        return filterInfixVocabulary(vocabulary(root,original),result);
    }
    /** Drops Chinese wording the rewrite introduced: SD prompts are English tags, and Chinese breaks them. */
    static InfixFilter withoutNewChinese(InfixFilter filter, SdClient.Prompts original) {
        return new InfixFilter(new DeepSeekPrompts.Result(
                stripCjk(filter.result().positive(), original.positive()),
                stripCjk(filter.result().negative(), original.negative())), filter.rejected());
    }
    /**
     * Chinese scene wording mapped to canonical A1111 tags. The model otherwise invents plausible but
     * non-existent tags such as "sunny" or "cloudy"; the vocabulary filter then drops them and the user's
     * requested change silently never lands. Only entries present in the loaded dictionary are offered.
     */
    private static final Map<String, List<String>> SCENE_SYNONYMS = Map.ofEntries(
            Map.entry("晴天", List.of("clear_sky", "sky")), Map.entry("晴朗", List.of("clear_sky", "sky")),
            Map.entry("晴空", List.of("clear_sky", "sky")), Map.entry("放晴", List.of("clear_sky", "sky")),
            Map.entry("阴天", List.of("overcast", "cloudy_sky")), Map.entry("多云", List.of("cloudy_sky", "overcast")),
            Map.entry("阴沉", List.of("overcast", "cloudy_sky")),
            Map.entry("大雾", List.of("fog")), Map.entry("浓雾", List.of("fog")), Map.entry("薄雾", List.of("fog")),
            Map.entry("雾", List.of("fog")),
            Map.entry("雨天", List.of("rain")), Map.entry("下雨", List.of("rain")), Map.entry("降雨", List.of("rain")),
            Map.entry("雨中", List.of("rain")), Map.entry("雨", List.of("rain")),
            Map.entry("雷雨", List.of("lightning", "thunder", "rain")), Map.entry("打雷", List.of("lightning", "thunder")),
            Map.entry("雷电", List.of("lightning", "thunder")), Map.entry("雷", List.of("lightning", "thunder")),
            Map.entry("晚霞", List.of("sunset")), Map.entry("夕阳", List.of("sunset")), Map.entry("日落", List.of("sunset")),
            Map.entry("黄昏", List.of("dusk", "evening", "sunset")), Map.entry("傍晚", List.of("evening", "dusk")),
            Map.entry("日暮", List.of("dusk", "evening")),
            Map.entry("深夜", List.of("night", "night_sky")), Map.entry("午夜", List.of("night")),
            Map.entry("夜晚", List.of("night")), Map.entry("夜色", List.of("night", "night_sky")),
            Map.entry("晚上", List.of("night")),
            Map.entry("清晨", List.of("morning")), Map.entry("早晨", List.of("morning")), Map.entry("黎明", List.of("morning")),
            Map.entry("正午", List.of("day", "sunlight")), Map.entry("中午", List.of("day", "sunlight")),
            Map.entry("逆光", List.of("backlighting")), Map.entry("背光", List.of("backlighting")),
            Map.entry("霓虹灯光", List.of("neon_lights")), Map.entry("霓虹灯", List.of("neon_lights")),
            Map.entry("霓虹", List.of("neon_lights")),
            Map.entry("烛光", List.of("candlelight")), Map.entry("烛火", List.of("candlelight")),
            Map.entry("蜡烛", List.of("candlelight")),
            Map.entry("月光", List.of("moonlight")), Map.entry("月色", List.of("moonlight")), Map.entry("月夜", List.of("moonlight", "night")),
            Map.entry("柔和光线", List.of("soft_focus", "light_rays")), Map.entry("柔和光照", List.of("soft_focus", "light_rays")),
            Map.entry("柔光", List.of("soft_focus", "light_rays")), Map.entry("柔和", List.of("soft_focus")),
            Map.entry("泳装", List.of("swimsuit")), Map.entry("泳衣", List.of("swimsuit")), Map.entry("水着", List.of("swimsuit")),
            Map.entry("军装", List.of("military_uniform")), Map.entry("军服", List.of("military_uniform")),
            Map.entry("铠甲", List.of("armor")), Map.entry("盔甲", List.of("armor")),
            Map.entry("女仆装", List.of("maid")), Map.entry("女仆", List.of("maid")),
            Map.entry("和服", List.of("kimono")), Map.entry("和式", List.of("japanese_clothes", "kimono")),
            Map.entry("日式", List.of("japanese_clothes", "kimono")),
            Map.entry("西装", List.of("suit")), Map.entry("西服", List.of("suit")), Map.entry("正装", List.of("suit")),
            Map.entry("运动服", List.of("sportswear", "jersey")), Map.entry("运动装", List.of("sportswear", "jersey")),
            Map.entry("巫女服", List.of("miko", "japanese_clothes")), Map.entry("巫女", List.of("miko", "japanese_clothes")),
            Map.entry("白大褂", List.of("white_coat")), Map.entry("白袍", List.of("white_coat")), Map.entry("白衣", List.of("white_coat")),
            Map.entry("校服", List.of("school_uniform")),
            Map.entry("神社", List.of("shrine")), Map.entry("教室", List.of("classroom")),
            Map.entry("樱花", List.of("cherry_blossoms")), Map.entry("雪国", List.of("snow")),
            Map.entry("下雪", List.of("snow", "snowing")), Map.entry("雪", List.of("snow")),
            Map.entry("天台", List.of("rooftop")), Map.entry("屋顶", List.of("rooftop")),
            Map.entry("水族馆", List.of("aquarium")), Map.entry("医院", List.of("hospital")),
            Map.entry("军营操场", List.of("military", "military_vehicle")), Map.entry("操场", List.of("military", "military_vehicle")),
            Map.entry("图书馆", List.of("library")), Map.entry("沙滩", List.of("beach")), Map.entry("海滩", List.of("beach")),
            Map.entry("古城街道", List.of("town", "alley", "cobblestone", "street")),
            Map.entry("古城", List.of("town", "alley", "cobblestone")), Map.entry("老街", List.of("town", "alley")),
            Map.entry("街道", List.of("street")), Map.entry("小巷", List.of("alley")), Map.entry("废墟", List.of("ruins")),
            Map.entry("城堡", List.of("castle")), Map.entry("村庄", List.of("village")), Map.entry("寺庙", List.of("temple")),
            Map.entry("博物馆", List.of("museum")), Map.entry("风景", List.of("scenery", "landscape")),
            Map.entry("风衣", List.of("trench_coat", "coat")), Map.entry("大衣", List.of("coat", "long_coat")),
            Map.entry("外套", List.of("coat")),
            Map.entry("水底", List.of("underwater")), Map.entry("水下", List.of("underwater")), Map.entry("水中", List.of("underwater")),
            Map.entry("撑伞", List.of("holding_umbrella", "umbrella")), Map.entry("打伞", List.of("holding_umbrella", "umbrella")),
            Map.entry("雨伞", List.of("holding_umbrella", "umbrella")),
            Map.entry("回头", List.of("looking_back")), Map.entry("回眸", List.of("looking_back")),
            Map.entry("奔跑", List.of("running")), Map.entry("跑动", List.of("running")),
            Map.entry("坐着", List.of("sitting")), Map.entry("坐姿", List.of("sitting")), Map.entry("坐下", List.of("sitting")),
            Map.entry("跳跃", List.of("jumping")), Map.entry("跳起", List.of("jumping")),
            Map.entry("站立", List.of("standing")), Map.entry("站姿", List.of("standing")), Map.entry("站着", List.of("standing")),
            // 地点与环境
            Map.entry("草地", List.of("grass", "grasslands", "meadow", "lawn")),
            Map.entry("草坪", List.of("lawn", "grass", "meadow")),
            Map.entry("草原", List.of("grasslands", "grass", "meadow")),
            Map.entry("草甸", List.of("meadow", "grasslands", "grass")),
            Map.entry("森林", List.of("forest", "tree")), Map.entry("树林", List.of("forest", "tree")),
            Map.entry("沙漠", List.of("desert")), Map.entry("荒野", List.of("wasteland", "desert", "ruins")),
            Map.entry("战场", List.of("battlefield", "battle", "ruins")),
            // 人物与人数
            Map.entry("女性", List.of("1girl", "girl")), Map.entry("少女", List.of("1girl", "girl")),
            Map.entry("女孩", List.of("1girl", "girl")), Map.entry("男性", List.of("1boy", "boy")),
            Map.entry("男子", List.of("1boy", "boy")), Map.entry("男人", List.of("1boy", "boy")),
            Map.entry("两人", List.of("2girls", "2boys", "multiple_girls")),
            // 动作、体力与表情
            Map.entry("喘着气", List.of("heavy_breathing")), Map.entry("喘气", List.of("heavy_breathing")),
            Map.entry("喘息", List.of("heavy_breathing")), Map.entry("气喘", List.of("heavy_breathing")),
            Map.entry("上气不接下气", List.of("heavy_breathing")), Map.entry("呼吸急促", List.of("heavy_breathing")),
            Map.entry("行军", List.of("walking", "military", "army")), Map.entry("急行军", List.of("walking", "military")),
            Map.entry("长途行军", List.of("walking", "military")), Map.entry("拉练", List.of("military", "training", "walking")),
            Map.entry("跋涉", List.of("walking", "military")), Map.entry("赶路", List.of("walking")),
            Map.entry("艰苦", List.of("exhausted")), Map.entry("艰辛", List.of("exhausted")),
            Map.entry("疲惫", List.of("exhausted")), Map.entry("精疲力尽", List.of("exhausted")),
            Map.entry("劳累", List.of("exhausted")), Map.entry("狼狈", List.of("exhausted")),
            Map.entry("流汗", List.of("sweat")), Map.entry("出汗", List.of("sweat")), Map.entry("汗水", List.of("sweat")),
            // 公园 / 户外玩耍这一组：模型爱写 children playing in a park，词库里存的却是 child/park/playing。
            Map.entry("公园", List.of("park", "playground")), Map.entry("游乐场", List.of("playground", "park")),
            Map.entry("玩耍", List.of("playing", "play")), Map.entry("游玩", List.of("playing", "play")),
            Map.entry("玩乐", List.of("playing")), Map.entry("玩耍的孩子", List.of("child", "playing")),
            Map.entry("儿童", List.of("child", "children")), Map.entry("小孩", List.of("child", "kid")),
            Map.entry("孩子", List.of("child")), Map.entry("树", List.of("tree", "plant")),
            Map.entry("树木", List.of("tree", "plant")), Map.entry("大树", List.of("tree")),
            Map.entry("长椅", List.of("bench")), Map.entry("秋千", List.of("swing")), Map.entry("滑梯", List.of("slide")),
            Map.entry("沙坑", List.of("sandbox")), Map.entry("花坛", List.of("flower_bed", "flower")),
            Map.entry("花丛", List.of("flower", "flower_bed")), Map.entry("户外", List.of("outdoors", "scenery")),
            Map.entry("室外", List.of("outdoors")), Map.entry("阳光", List.of("sunlight", "sun")),
            Map.entry("自然光", List.of("sunlight")), Map.entry("白天", List.of("day", "sunlight")),
            Map.entry("公园长椅", List.of("bench")),
            // 骚扰类场景：找不到这组词条时，拆解出来的"性骚扰"那一条会整条消失。
            Map.entry("性骚扰", List.of("chikan", "molestation", "groping")),
            Map.entry("骚扰", List.of("chikan", "molestation", "groping")),
            Map.entry("猥亵", List.of("molestation", "chikan", "groping")),
            Map.entry("痴汉", List.of("chikan", "molestation")),
            Map.entry("非礼", List.of("molestation", "groping")),
            Map.entry("咸猪手", List.of("groping", "chikan")),
            Map.entry("偷拍", List.of("voyeurism", "upskirt")), Map.entry("偷窥", List.of("voyeurism")),
            Map.entry("抚摸", List.of("groping", "groping_motion")), Map.entry("触摸", List.of("groping")),
            Map.entry("猥琐", List.of("chikan")),
            // 车厢 / 人群 / 情绪
            Map.entry("地铁", List.of("subway", "train")), Map.entry("电车", List.of("train", "subway")),
            Map.entry("列车", List.of("train")), Map.entry("车厢", List.of("train_interior", "interior", "train")),
            Map.entry("地铁车厢", List.of("train_interior", "subway")),
            Map.entry("人群", List.of("crowd")), Map.entry("人潮", List.of("crowd")), Map.entry("拥挤", List.of("crowded", "crowd")),
            Map.entry("害怕", List.of("scared")), Map.entry("惊恐", List.of("scared")), Map.entry("恐惧", List.of("scared")),
            Map.entry("不安", List.of("uncomfortable", "scared")), Map.entry("抗拒", List.of("uncomfortable")),
            Map.entry("不情愿", List.of("uncomfortable")), Map.entry("强制", List.of("forced")),
            Map.entry("强迫", List.of("forced")), Map.entry("无力反抗", List.of("forced")));
    /** Canonical tags for the Chinese wording in one instruction, restricted to the loaded dictionary. */
    static List<String> sceneHints(String instruction, Set<String> allowed) {
        List<String> hints = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : SCENE_SYNONYMS.entrySet()) {
            if (!instruction.contains(entry.getKey())) continue;
            for (String candidate : entry.getValue()) {
                if (!allowedContains(allowed, candidate) || hints.contains(candidate)) continue;
                hints.add(candidate);
                break;
            }
        }
        return hints;
    }
    /**
     * Every dictionary-valid tag that expresses the Chinese wording — used when checking whether a prompt
     * already covers a request: "sky" must count as covering 晴天 just as "clear_sky" does.
     */
    public static List<String> sceneTagCandidates(String instruction, Set<String> allowed) {
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : SCENE_SYNONYMS.entrySet()) {
            if (!instruction.contains(entry.getKey())) continue;
            for (String candidate : entry.getValue())
                if (allowedContains(allowed, candidate) && !candidates.contains(candidate)) candidates.add(candidate);
        }
        return candidates;
    }
    /**
     * The allowed set holds PromptEditor-normalised keys ("soft focus"), while the synonym table uses canonical
     * underscored tags ("soft_focus"): comparing raw forms silently rejected every multi-word tag.
     */
    static boolean allowedContains(Set<String> allowed, String tag) {
        return allowed.contains(tag) || allowed.contains(PromptEditor.key(tag));
    }
    /**
     * Bounded vocabulary hints that are handed to DeepSeek with the first /infix request, so it can copy
     * canonical terms instead of inventing them: Chinese meaning matches from the local dictionary, then
     * dictionary keys containing an English word used in the request.
     */
    static List<String> infixHints(Path root, String instruction, Set<String> allowed) {
        PromptUsage usage = null;
        try { usage = new PromptUsage(root); }
        catch (Exception e) { Log.warn("/infix 中文候选词读取失败：" + error(e)); }
        return infixHints(usage, instruction, allowed);
    }
    /** 用已经加载好的词库实例取候选词，避免每次 .infix 重复解析 data/prompt-usage.json 与内置中文词库。 */
    static List<String> infixHints(PromptUsage usage, String instruction, Set<String> allowed) {
        if (instruction == null || instruction.isBlank()) return List.of();
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        hints.addAll(sceneHints(instruction, allowed));
        if (usage != null) {
            try { hints.addAll(usage.hints(instruction, allowed, 80)); }
            catch (Exception e) { Log.warn("/infix 中文候选词读取失败：" + error(e)); }
        }
        LinkedHashSet<String> words = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,}").matcher(instruction);
        while (matcher.find() && words.size() < 8) words.add(matcher.group().toLowerCase(Locale.ROOT));
        if (!words.isEmpty()) {
            java.util.TreeSet<String> matched = new java.util.TreeSet<>();
            for (String key : allowed) {
                String lowered = key.toLowerCase(Locale.ROOT);
                for (String word : words) if (lowered.contains(word)) { matched.add(key); break; }
            }
            for (String key : matched) { hints.add(key); if (hints.size() >= 160) break; }
        }
        return List.copyOf(hints);
    }
    private static String filterTerms(String text,String field,Set<String> allowed,List<String> rejected) {
        return filterTerms(text, field, allowed, rejected, false);
    }
    /** allowNew keeps terms the dictionary does not know instead of dropping them (reported by the caller). */
    private static String filterTerms(String text,String field,Set<String> allowed,List<String> rejected,boolean allowNew) {
        return filterTerms(text, field, allowed, rejected, allowNew, null, null, true);
    }
    /**
     * 逐词验收：命中词库就保留；没命中时先请 {@link PromptUsage#canonical} 找一个含义相近的标准词，
     * 换成标准词后保留（记入 converted）；实在换不了才丢弃（记入 rejected）。
     */
    private static String filterTerms(String text,String field,Set<String> allowed,List<String> rejected,boolean allowNew,
                                      PromptUsage usage,List<String> converted,boolean positiveField) {
        List<String> kept=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(String term:PromptEditor.parts(text)) {
            // 同一个词条只保留第一次出现：模型偶尔会重复输出（回执里就会出现"新增 3d_background、3d_background"）。
            if(!seen.add(PromptEditor.key(term))) continue;
            if(allowed.contains(PromptEditor.key(term))) { kept.add(term); continue; }
            // 只有开启词库约束时才做"近义词→标准词"的转换；关闭约束时保留模型原词。
            String canonical = (!allowNew && usage != null) ? usage.canonical(term, allowed) : null;
            if (canonical != null) {
                if (converted != null && !converted.contains(field + "：" + term + " → " + canonical))
                    converted.add(field + "：" + term + " → " + canonical);
                if (!usesTerm(String.join(", ", kept), canonical)) kept.add(canonical);
                continue;
            }
            if (allowNew) kept.add(term);
            else rejected.add(field+"："+term);
        }
        return String.join(", ",kept);
    }
    /** True when the prompt already carries that canonical term. */
    static boolean usesTerm(String prompt, String tag) {
        if (prompt == null || prompt.isBlank() || tag == null) return false;
        String key = PromptEditor.key(tag);
        for (String term : PromptEditor.parts(prompt)) if (PromptEditor.key(term).equals(key)) return true;
        return false;
    }
    /**
     * Terms of the original prompt that the rewrite no longer contains. A handful of replacements is
     * normal (the request usually swaps a few tags), so only a real loss triggers the preserve retry.
     */
    static List<String> droppedTerms(String original, String rewritten) {
        if (original == null || original.isBlank()) return List.of();
        Set<String> present = new HashSet<>();
        for (String term : PromptEditor.parts(rewritten == null ? "" : rewritten)) present.add(PromptEditor.key(term));
        List<String> dropped = new ArrayList<>();
        for (String term : PromptEditor.parts(original)) {
            String key = PromptEditor.key(term);
            if (key.isBlank() || present.contains(key)) continue;
            if (!dropped.contains(term)) dropped.add(term);
        }
        return dropped;
    }
    /** True when the loss is large enough to look like compression rather than the requested change. */
    static boolean lostTooMuch(String original, List<String> dropped) {
        int total = PromptEditor.parts(original == null ? "" : original).size();
        return dropped.size() >= 4 && total > 0 && dropped.size() * 4 >= total;
    }
    /** 近义词转换的回执：说明"这个词被换成了哪个标准词"，而不是"被丢弃"。 */
    private static String formatConverted(List<String> converted) {
        if (converted == null || converted.isEmpty()) return "";
        int shown = Math.min(20, converted.size());
        return "\n已把近义词换成标准词条（" + converted.size() + " 项）：\n"
                + String.join("\n", converted.subList(0, shown))
                + (converted.size() > shown ? "\n……另有 " + (converted.size() - shown) + " 项" : "");
    }
    private static String formatRejected(List<String> rejected) {        if(rejected.isEmpty()) return "";
        int shown=Math.min(20,rejected.size());
        return "\n已忽略词库外新词（"+rejected.size()+" 项）：\n"
                +String.join("\n",rejected.subList(0,shown))
                +(rejected.size()>shown ? "\n……另有 "+(rejected.size()-shown)+" 项" : "");
    }
    /** With the constraint off the model's own words are kept, but the receipt still names them. */
    private static String formatKeptOutsideDictionary(List<String> kept) {
        if (kept.isEmpty()) return "";
        int shown = Math.min(20, kept.size());
        return "\n标准词库约束已关闭，保留词库外新词（" + kept.size() + " 项，可能不被 SD 识别）：\n"
                + String.join("\n", kept.subList(0, shown))
                + (kept.size() > shown ? "\n……另有 " + (kept.size() - shown) + " 项" : "");
    }

    private void progen(JsonObject event, String description) {
        if (description.isBlank() || description.length() > 8000) throw new IllegalArgumentException("用法：/progen <文字描述>，描述须为 1–8000 个字符。");
        if (closed.get()) throw new IllegalStateException("机器人正在关闭。");
        if (!progenBusy.compareAndSet(false, true)) { reply(event, "已有提示词生成请求正在处理，请稍后重试。");completeChatWorkflowStep(event,false);return; }
        JsonObject context = attachNormalWindow(event.deepCopy());
        replyProcess(context, "正在通过 DeepSeek 生成提示词，完成后会回复。");
        try {
            progenIO.execute(() -> {
                String message;boolean succeeded=false;
                try {
                    // .progen 生成提示词，走生图频道。
                    var result = DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.IMAGE).generate(description);
                    message = "DeepSeek 提示词生成完成：\n正向 prompt：\n" + result.positive() + "\n反向 prompt：\n" + display(result.negative())
                            + "\n可分别用 /prompt set 与 /promptR set 设置后，发送 /gen 生成图片。";
                    succeeded=true;
                } catch (Exception e) { message = "提示词生成失败：" + error(e); }
                finally { progenBusy.set(false); }
                Log.info("progen 完成：" + Log.text(message));
                replyRanked(context, succeeded ? ReceiptRank.AFFIRM : ReceiptRank.FAILURE, message);
                completeChatWorkflowStep(context,succeeded);
                releaseNormalWindow(context);
            });
        } catch (RejectedExecutionException e) { progenBusy.set(false);completeChatWorkflowStep(event,false);releaseNormalWindow(context);throw new IllegalStateException("机器人正在关闭。"); }
    }
    /**
     * /char <关键词>: look the character up in the local LoRA files and the WebUI styles, then ask whether
     * to apply one. Candidates are stored with #numbers, so a plain ".lora load #1" / ".style load #1" works.
     */
    private void character(JsonObject event, String keyword) throws Exception {
        if (keyword.isBlank() || keyword.length() > 100) throw new IllegalArgumentException("用法：/char <角色名或关键词>");
        if (keyword.equalsIgnoreCase("download") || keyword.equalsIgnoreCase("yes") || keyword.equals("好") || keyword.equals("可以")) {
            String remembered = pendingCharacters.remove(conversation(event));
            if (remembered == null) throw new IllegalArgumentException("还没有待处理的角色查询：先用 /char <角色名> 查找。");
            reply(event, "好，我去 Civitai 搜索「" + remembered + "」的 LoRA；列表里会带编号和封面，你挑一个编号回复 /lora download #编号 我再下载。");
            lora(event, "query " + remembered);
            return;
        }
        if (keyword.toLowerCase(Locale.ROOT).startsWith("apply")) {
            String rest = keyword.length() > "apply".length() ? keyword.substring("apply".length()).strip() : "";
            String spec = rest.split("\\s+", 2)[0];
            if (spec.isBlank()) throw new IllegalArgumentException("用法：/char apply #编号 [修改要求]");
            String requirement = rest.length() > spec.length() ? stripQuotes(rest.substring(spec.length()).strip()) : "";
            String chosen = select(event, "char", spec);
            int split = chosen.indexOf('|');
            if (split < 0) throw new IllegalArgumentException("候选编号无效，请重新 /char <角色名>。");
            String kind = chosen.substring(0, split), name = chosen.substring(split + 1);
            List<String> steps = new ArrayList<>();
            steps.add(("style".equals(kind) ? "/style load " : "/lora load ") + '"' + name + '"');
            if (!requirement.isBlank()) steps.add("/infix " + requirement);
            steps.add("/gen 1");
            Log.info("角色图任务链（" + kind + "：" + name + "）：" + steps.size() + " 步");
            replyProcess(event, "开始按顺序执行：" + String.join(" → ", steps));
            runChatCommands(event, steps, selectionContext(event));
            return;
        }
        if (keyword.equalsIgnoreCase("cancel") || keyword.equals("算了") || keyword.equals("不用")) {
            String remembered = pendingCharacters.remove(conversation(event));
            reply(event, remembered == null ? "当前没有待处理的角色查询。" : "好，不下载了；需要出图就直接 /gen。");
            return;
        }
        String key = keyword.toLowerCase(Locale.ROOT).replace(' ', '_');
        List<String> loraNames = new ArrayList<>(), loraLabels = new ArrayList<>(), styles = new ArrayList<>();
        try {
            for (SdClient.Lora lora : sd.loras()) {
                String alias = Objects.requireNonNullElse(lora.alias(), "");
                String haystack = (lora.name() + " " + alias).toLowerCase(Locale.ROOT).replace(' ', '_');
                if (!haystack.contains(key)) continue;
                loraNames.add(lora.name());
                loraLabels.add(lora.name() + (alias.isBlank() || alias.equals(lora.name()) ? "" : "（别名：" + alias + "）"));
            }
        } catch (Exception e) { Log.warn("/char 读取本地 LoRA 失败：" + error(e)); }
        for (String style : localStyles.names())
            if (style.toLowerCase(Locale.ROOT).replace(' ', '_').contains(key)) styles.add(style);
        if (loraNames.isEmpty() && styles.isEmpty()) {
            pendingCharacters.put(conversation(event), keyword);
            reply(event, "本地没有找到与「" + keyword + "」匹配的 LoRA 或样式。\n"
                    + "需要我去 Civitai 搜索并下载对应的 LoRA（并把模型展示图的提示词存成样式）吗？\n"
                    + "同意的话回复 /lora query " + keyword + "，我先列出候选和封面给你挑编号，你选定后我再下载并加载；"
                    + "也可以直接 /gen 用你自己的 prompt 出图。");
            return;
        }
        List<String> candidates = new ArrayList<>(), labels = new ArrayList<>();
        for (int index = 0; index < loraNames.size(); index++) {
            candidates.add("lora|" + loraNames.get(index));
            labels.add("LoRA：" + loraLabels.get(index));
        }
        for (String style : styles) {
            candidates.add("style|" + style);
            labels.add("样式：" + style);
        }
        reply(event, "找到与「" + keyword + "」相关的内容：" + numbered(event, "char", candidates, labels)
                + "\n选定后用一条指令即可完成「应用基底 → 按你的要求改写提示词 → 生成」："
                + "\n/char apply #编号 <修改要求>（修改要求可省略，例如 /char apply #5 郊外穿军装拉练）");
    }    /**
     * 把一次 Civitai 搜索登记成编号列表（kind=civitai）。回执里的 #编号、规划层看到的 last_list、
     * 以及 /lora download #N 的解析必须来自同一份列表，否则"第二个"就会被解释成本地 LoRA 的第 2 项。
     */
    void registerLoraSearch(JsonObject event, List<CivitaiClient.SearchResult> results) {
        loraSearches.put(conversation(event), List.copyOf(results));
        numbered(event, "civitai",
                results.stream().map(CivitaiClient.SearchResult::url).toList(),
                results.stream().map(result -> result.name() + "（基础模型：" + result.baseModel() + "）").toList());
    }
    /**
     * 带页码的登记：编号列表只放**这一页**的结果，所以 {@code .lora download #N} 指本页第 N 条
     * （翻页会整体换掉这份列表，与用户看到的回执一一对应）；页码另存一份给规划层上下文用。
     */
    void registerLoraSearch(JsonObject event, CivitaiClient.SearchPage page) {
        registerLoraSearch(event, page.results());
        loraPages.put(conversation(event), page);
    }
    /** `.lora search <关键词> [页码]` 解析出来的参数；页码默认 1。 */
    record LoraSearchQuery(String words, int page) { }
    /**
     * 解析搜索参数：{@code <关键词> [页码]}。
     *
     * <p>页码是**结尾的纯数字**（1–3 位）。关键词自己以数字结尾时（例如 "milf 2"）把整段用双引号包起来，
     * 就不会被当成页码——这条规则写进帮助里，也写进用法报错里。
     */
    static LoraSearchQuery parseLoraSearch(String value) {
        String text = value == null ? "" : value.strip();
        String usage = "用法：/lora search <模型搜索词> [页码]，例如 /lora search tsumugi 2；"
                + "搜索词自己以数字结尾时用双引号包起来，例如 /lora search \"milf 2\"。";
        if (text.isEmpty()) throw new IllegalArgumentException(usage);
        if (text.length() > 2 && text.startsWith("\"") && text.endsWith("\"")) {
            String quoted = text.substring(1, text.length() - 1).strip();
            if (quoted.isEmpty()) throw new IllegalArgumentException(usage);
            return new LoraSearchQuery(quoted, 1);
        }
        Matcher tail = Pattern.compile("^(.*?)\\s+([0-9]{1,3})$", Pattern.DOTALL).matcher(text);
        if (tail.matches() && !tail.group(1).isBlank()) {
            int page = Integer.parseInt(tail.group(2));
            // 结尾写 0（或 "00"）时不当作"关键词以数字结尾"：一律报错，免得悄悄搜错词。
            if (page < 1) throw new IllegalArgumentException("页码从 1 开始；" + usage);
            return new LoraSearchQuery(tail.group(1).strip(), page);
        }
        return new LoraSearchQuery(text, 1);
    }
    /** 回执里"下一页：.lora search <词> <页码>"的写法：搜索词含空格或以数字结尾时加引号，免得页码被当成搜索词。 */
    static String quoteLoraSearchWords(String words) {
        String text = words == null ? "" : words.strip();
        return text.matches(".*\\s.*") || text.matches(".*[0-9]$") ? "\"" + text + "\"" : text;
    }
    /**
     * 搜索回执的页码说明（纯函数，便于断言）：第 X 页（Civitai 不返回总数时就不编页数）、
     * 本页编号的语义、以及下一页的写法。
     */
    static String searchPageLines(CivitaiClient.SearchPage page) {
        StringBuilder text = new StringBuilder("第 ").append(page.page())
                .append(page.totalPagesKnown() ? "/" + page.totalPages() : "")
                .append(" 页，本次返回 ").append(page.count()).append(" 项（每页 ").append(page.pageSize()).append(" 条）");
        text.append("\n本页编号：.lora download #N 指本页第 N 条（不是全部搜索结果里的第 N 条）");
        if (page.hasMore()) text.append("\n下一页：.lora search ").append(quoteLoraSearchWords(page.query()))
                .append(" ").append(page.nextPage());
        else text.append("\n这已经是最后一页。");
        return text.toString();
    }
    /** 页码越界（翻过头）时的回执：如实说没有更多，并给出回到第 1 页的写法。 */
    static String searchPageOutOfRange(CivitaiClient.SearchPage page) {
        return "第 " + page.page() + " 页没有内容：这次搜索没有那么多结果（每页 " + page.pageSize() + " 条）。"
                + "\n回到第一页：.lora search " + quoteLoraSearchWords(page.query()) + " 1"
                + "\n（你上一次看到的本页编号没有被改动）";
    }
    /**
     * 把一次搜索的一页结果发出去：能发合并转发时只发**一条**聊天记录（每条结果一个节点），
     * 传输层不支持时保持它原来的发法（逐条，并如实说明原因），合并转发失败也回退成逐条发送——
     * 不管走哪条路，结果一条都不能丢。
     *
     * <p>节点正文就是用户在手机上看到的那段纯文本（编号、模型名、NSFW 标记、底模、下载量/点赞/大小/发布、
     * 触发词、页面；见 {@link #loraSearchNodeText}），
     * 编号与 {@code .lora download #N} 用的是同一份"本页"列表，所以两边永远对得上。
     * 节点数 = 结果条数（页头/页脚另有回执，不占节点）；一页最多 {@link CivitaiClient#MAX_PAGE_SIZE} 条，
     * 所以节点数也最多这么多；节点正文不设长度上限（只有 QQ 侧的整包大小上限，超了会发送失败并走逐条兜底）。
     */
    void sendLoraSearchResults(JsonObject event, CivitaiClient.SearchPage page) {
        List<CivitaiClient.SearchResult> results = page.results();
        if (results.isEmpty()) return;
        if (!forwardRecords) {
            Log.info("LoRA 搜索结果发送方式（" + describeConversation(event) + "）：普通发送 " + results.size() + " 条（传输层不支持合并转发）");
            reply(event, "这个传输层不支持合并转发，已逐条发送。");
            sendLoraSearchResultsOneByOne(event, results);
            return;
        }
        List<JsonArray> nodes = new ArrayList<>(results.size());
        for (int at = 0; at < results.size(); at++) nodes.add(loraSearchNode(at + 1, results.get(at)));
        Log.info("LoRA 搜索结果发送方式（" + describeConversation(event) + "）：合并转发 " + nodes.size() + " 条");
        // 合并转发失败（NapCat 不支持 send_group_forward_msg、节点格式被拒……）绝不能把结果丢了：
        // 回退成逐条发送（逐条那条路自己还有"封面发不出去就改用文字"的兜底），这里只回退一次、不重试。
        //
        // 但"结果未知"（API 响应超时、连接中断）是另一回事：那些结果可能已经发出去了，此时再逐条发一遍
        // 就是同一批结果出现两遍（线上 /lora query gpt 就是这样：30 秒超时 → 又发了 10 条）。
        // 所以只有**确定被拒**才逐条兜底；不确定时只说明一句，不重发（宁可少发一次，也不许出现两遍）。
        CompletableFuture<Void> sent;
        try {
            sent = sender.sendRecord(event, nodes).handle((ignored, failure) -> {
                if (failure == null) return CompletableFuture.<Void>completedFuture(null);
                if (deliveryUnknown(failure)) {
                    Log.warn("LoRA 搜索结果的合并转发结果未知（可能已发出），未自动重发 " + results.size()
                            + " 条（" + describeConversation(event) + "）：" + error(failure));
                    reply(event, "合并转发失败，搜索结果可能已发出（超时/连接中断，发送结果未知），未自动重发。"
                            + "如果群里确实没看到，请重新执行一次 .lora query。");
                    return CompletableFuture.<Void>completedFuture(null);
                }
                Log.warn("LoRA 搜索结果的合并转发失败，已改为逐条发送 " + results.size() + " 条（" + describeConversation(event) + "）：" + error(failure));
                reply(event, "合并转发失败，已逐条发送（搜索结果一条不少）。");
                return sendLoraSearchResultsOneByOne(event, results);
            }).thenCompose(future -> future);
        } catch (Exception refused) {
            // 传输层同步拒绝（没连上 QQ 之类）：同样回退，别让搜索结果凭空消失。
            Log.warn("LoRA 搜索结果的合并转发未被接受，已改为逐条发送 " + results.size() + " 条（" + describeConversation(event) + "）：" + error(refused));
            reply(event, "合并转发失败，已逐条发送（搜索结果一条不少）。");
            sendLoraSearchResultsOneByOne(event, results);
            return;
        }
        // 等这条聊天记录确认之后再回"搜索完成，共 N 项"的总回执：顺序与逐条发送时一致，
        // 回执不会排在它描述的那些结果前面（原实现是逐条 .get()，同样是发完才回执）。
        try { sent.get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); Log.warn("等待 LoRA 搜索结果的合并转发时被中断（" + describeConversation(event) + "）"); }
        catch (ExecutionException failed) { Log.warn("LoRA 搜索结果发送失败（" + describeConversation(event) + "）：" + error(failed)); }
    }
    /**
     * 逐条发送一页搜索结果（传输层不支持合并转发，或合并转发失败后的回退）。
     *
     * <p>从发不出去的那一条起（常见：图床被墙或超时）改用一条纯文本把余下条目一次给出，编号不变、
     * 版式与合并转发**同一个** {@link #loraSearchNodeText}（只少了封面图），所以"封面发不出去"最多丢封面，
     * 绝不丢结果、也绝不换一套排版。
     */
    private CompletableFuture<Void> sendLoraSearchResultsOneByOne(JsonObject event, List<CivitaiClient.SearchResult> results) {
        for (int at = 0; at < results.size(); at++) {
            CivitaiClient.SearchResult result = results.get(at);
            try { sender.send(event.deepCopy(), loraSearchNode(at + 1, result)).get(); }
            catch (Exception failure) {
                Log.warn("LoRA 搜索结果第 " + (at + 1) + " 条发送失败，余下改用文字：" + error(failure));
                StringBuilder fallback = new StringBuilder("封面发送失败，余下条目以文字给出（版式与合并转发一致）：\n");
                for (int rest = at; rest < results.size(); rest++) {
                    if (rest > at) fallback.append('\n');
                    fallback.append(loraSearchNodeText(rest + 1, results.get(rest))).append('\n');
                }
                reply(event, fallback.toString());
                break;
            }
        }
        return CompletableFuture.completedFuture(null);
    }
    /**
     * 一条搜索结果的出站消息，也就是合并转发里的一个节点：正文是一段人能读的纯文本，有封面就再带一张封面。
     */
    static JsonArray loraSearchNode(int number, CivitaiClient.SearchResult result) {
        JsonArray message = new JsonArray();
        message.addAll(Maps.text(loraSearchNodeText(number, result)));
        if (result.cover() != null && !result.cover().isEmpty()) {
            JsonObject image = new JsonObject(), data = new JsonObject();
            image.addProperty("type", "image");
            data.addProperty("file", result.cover());
            image.add("data", data);
            message.add(image);
        }
        return message;
    }
    /** 触发词一行的软上限（码点）：超过就按词里本来就有的逗号换行，绝不切字。 */
    static final int LORA_SEARCH_WORD_LINE_CHARS = 120;
    /**
     * 上面那个节点的正文。版式（一个结果一个节点，首行是"#编号 名称"，其余按"一眼能读"分组）：
     *
     * <pre>
     * #3 Chatgpt Cartoon Style - Illustrious
     * 底模：Illustrious
     * 下载量：146｜点赞：34｜大小：217.9 MB｜发布：2025-11-17
     * 触发词（1 条）：
     * Stylized cartoon, expressive outlines, cartoony proportions, High-contrast highlights
     * 页面：https://civitai.red/models/2144329?modelVersionId=2415380
     * </pre>
     *
     * <p>每一条规则都有测试盯着：
     * <ul>
     *   <li>名称、底模、触发词一律**原文**给出，不截断、不加省略号；名称里的换行压成空格，
     *       否则名称能伪装出"底模："这种字段行。</li>
     *   <li>空值不占行：没有底模/下载量/点赞/大小/发布/触发词就没有那一行——不写"未标注"，也不留空行。</li>
     *   <li>字段名全中文，不出现 {@code modelId}/{@code versionId}/{@code nsfw}/{@code cover} 这类内部键名，
     *       更没有裸 JSON（QQ 的聊天记录节点在手机上就是这几行字）。</li>
     *   <li>触发词**一条一行**（Civitai 的 trainedWords 一个元素一行，条数写在组头上）：原文里的英文逗号
     *       紧挨着顿号（{@code ,、}）既难看又会改写要复制的提示词，所以不再用顿号拼成一行。</li>
     *   <li>过长的触发词按**它自己的逗号**换行（每行尽量不超过 {@value #LORA_SEARCH_WORD_LINE_CHARS} 个字）；
     *       没有可断处就整行给出——一个字都不许用省略号吃掉。</li>
     *   <li>"页面"永远单独一行、整行只有这个地址，直接长按复制。</li>
     *   <li>NSFW 单独一行"标记"，不贴在底模后面（贴上去会被读成底模名的一部分）。</li>
     * </ul>
     */
    static String loraSearchNodeText(int number, CivitaiClient.SearchResult result) {
        StringBuilder text = new StringBuilder("#").append(number).append(' ').append(loraSearchName(result.name()));
        if (result.nsfw()) text.append("\n标记：NSFW（成人内容）");
        String base = loraSearchBaseModel(result.baseModel());
        if (!base.isEmpty()) text.append("\n底模：").append(base);
        String metrics = loraSearchMetrics(result);
        if (!metrics.isEmpty()) text.append('\n').append(metrics);
        List<String> words = loraSearchWords(result.trainedWords());
        if (!words.isEmpty()) {
            text.append("\n触发词（").append(words.size()).append(" 条）：");
            for (String word : words) for (String line : loraSearchTriggerLines(word)) text.append('\n').append(line);
        }
        text.append("\n页面：").append(result.url());
        return text.toString();
    }
    /** 节点首行的名称：换行压成空格（只动换行，其余一字不改、不截断）；真的没有名字就用解析层同一个词"未命名"。 */
    static String loraSearchName(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty()) return "未命名";
        return value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }
    /** "底模"这一项：Civitai 没给（空串，或解析层的占位"未知"）就返回空串，调用方连这一行都不写。 */
    static String loraSearchBaseModel(String baseModel) {
        String base = baseModel == null ? "" : baseModel.strip();
        return base.isEmpty() || base.equals("未知") || base.equals("未标注") ? "" : base;
    }
    /**
     * 一行"数字栏"：下载量、点赞、大小、发布时间——有的才写，之间用全角竖线分开；
     * 一个都没有就返回空串（调用方不留空行）。
     *
     * <p>点赞是 Civitai 的 {@code thumbsUpCount}：v1 API 里没有 0–5 分的评分字段，
     * 所以如实写"点赞"，不把赞/踩换算成一个编出来的分数。
     */
    static String loraSearchMetrics(CivitaiClient.SearchResult result) {
        List<String> parts = new ArrayList<>();
        if (result.downloads() > 0) parts.add("下载量：" + loraSearchCount(result.downloads()));
        if (result.likes() > 0) parts.add("点赞：" + loraSearchCount(result.likes()));
        String size = loraSearchSize(result.sizeKb());
        if (!size.isEmpty()) parts.add("大小：" + size);
        String date = loraSearchDate(result.publishedAt());
        if (!date.isEmpty()) parts.add("发布：" + date);
        return String.join("｜", parts);
    }
    /** 千分位计数（下载量/点赞共用，与旧版下载量的写法一致）。 */
    static String loraSearchCount(long value) { return String.format(Locale.ROOT, "%,d", value); }
    /** 版本发布时间只取日期（{@code 2026-06-03T12:00:00.000Z} → {@code 2026-06-03}）；不是这个形状就整项不写，绝不编日期。 */
    static String loraSearchDate(String publishedAt) {
        String value = publishedAt == null ? "" : publishedAt.strip();
        Matcher date = Pattern.compile("^([0-9]{4}-[0-9]{2}-[0-9]{2})(?:[T ].*)?$").matcher(value);
        return date.matches() ? date.group(1) : "";
    }
    /** 触发词：去掉空白项（Civitai 偶尔给空串），其余原样、有序、不截断。 */
    static List<String> loraSearchWords(List<String> trainedWords) {
        if (trainedWords == null) return List.of();
        List<String> words = new ArrayList<>();
        for (String word : trainedWords) if (word != null && !word.isBlank()) words.add(word);
        return List.copyOf(words);
    }
    /**
     * 一条触发词占的正文行：短词就是它自己；超过 {@value #LORA_SEARCH_WORD_LINE_CHARS} 个字的词按**它自己的逗号**
     * 换行（换行处吃掉紧跟逗号的那个空格）。没有逗号/空格可断就整行给出——**绝不**切字、绝不加省略号。
     */
    static List<String> loraSearchTriggerLines(String word) {
        String value = word == null ? "" : word;
        List<String> lines = new ArrayList<>();
        for (String piece : value.split("\\R", -1)) {
            String rest = piece.strip();
            if (rest.isEmpty()) { lines.add(""); continue; }
            while (rest.codePointCount(0, rest.length()) > LORA_SEARCH_WORD_LINE_CHARS) {
                int cut = loraSearchTriggerBreak(rest, LORA_SEARCH_WORD_LINE_CHARS);
                if (cut <= 0) break;
                lines.add(rest.substring(0, cut).stripTrailing());
                rest = rest.substring(cut).stripLeading();
            }
            lines.add(rest);
        }
        return List.copyOf(lines);
    }
    /** 在不超过 {@code limit} 个码点的前缀里找最后一个逗号（没有逗号就找最后一个空格）之后的位置；没有可断处返回 -1。 */
    private static int loraSearchTriggerBreak(String text, int limit) {
        int end = text.offsetByCodePoints(0, Math.min(limit, text.codePointCount(0, text.length())));
        int at = text.lastIndexOf(',', end - 1);
        if (at < 0) at = text.lastIndexOf(' ', end - 1);
        return at < 0 ? -1 : at + 1;
    }
    /** 文件大小：1 MB 以下写 KB，以上写 MB、1 GB 以上写 GB（都是一位小数）；未知（0 / 负数）就不写这一项。 */
    static String loraSearchSize(double sizeKb) {
        if (!(sizeKb > 0)) return "";
        if (sizeKb >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f GB", sizeKb / (1024 * 1024));
        return sizeKb >= 1024 ? String.format(Locale.ROOT, "%.1f MB", sizeKb / 1024) : String.format(Locale.ROOT, "%.0f KB", sizeKb);
    }
    private void lora(JsonObject event, String arguments) throws Exception {
        String loraScope = promptScope(event);
        Matcher searching = Pattern.compile("(?i)^(query|search)(?:\\s+([\\s\\S]*))?$").matcher(arguments);
        if (searching.matches()) {
            LoraSearchQuery parsed = parseLoraSearch(searching.group(2));
            startLora(event, "正在搜索 Civitai LoRA（第 " + parsed.page() + " 页）。", false, () -> {
                CivitaiClient.SearchPage page = new CivitaiClient(settings.root, Json.obj(settings.snapshot(), "civitai"))
                        .search(parsed.words(), parsed.page());
                // 翻过头：登记空列表会把用户手上的编号清掉，所以这里只回报、不动编号。
                if (page.results().isEmpty())
                    return new LoraResult(parsed.page() == 1 ? "未找到匹配的 LoRA。" : searchPageOutOfRange(page), false);
                registerLoraSearch(event, page);
                // 一次搜索的结果只发**一条**聊天记录（合并转发），每条结果一个节点；传输层不支持或
                // 合并转发失败时由它回退成逐条发送（并如实说明），结果一条不少。
                sendLoraSearchResults(event, page);
                return new LoraResult("搜索完成，共 " + page.count() + " 项。使用 /lora download #编号 [权重] 下载；"
                        + "编号属于你在本会话最近一次搜索的**本页**（只保存在内存里，重启后必须重新搜索）。\n"
                        + searchPageLines(page), true);
            }); return;
        }
        if (arguments.equalsIgnoreCase("status")) {
            DownloadControl watching = loraControl;   // 先取一次：任务随时可能结束并把控制对象作废
            reply(event, "最近 LoRA 下载状态：\n" + loraStatus + "\n" + loraProgressLine()
                    + (watching != null && watching.isPaused() ? "\n当前状态：已暂停" : "")
                    + "\n可用命令：.lora cancel 取消当前下载、.lora pause 暂停、.lora resume 继续（控制台对应 /api/lora/cancel、pause、resume）。");
            return;
        }
        if (arguments.equalsIgnoreCase("cancel") || arguments.equalsIgnoreCase("pause") || arguments.equalsIgnoreCase("resume")) {
            // 取消/暂停/继续下载：和 .lora download 同一条权限规则（admin_only 时仅管理员）。
            requireLoraPermission(event);
            String action = arguments.toLowerCase(Locale.ROOT);
            JsonObject outcome = action.equals("cancel") ? loraCancelJson() : action.equals("pause") ? loraPauseJson() : loraResumeJson();
            reply(event, "LoRA 下载：" + Json.str(outcome, "message", "") + "\n" + loraProgressLine());
            return;
        }
        Matcher triggerQuery = Pattern.compile("(?i)^triggers(?:\\s+([\\s\\S]*))?$").matcher(arguments);
        if (triggerQuery.matches()) {
            String rest = Objects.requireNonNullElse(triggerQuery.group(1), "").strip();
            Matcher setting = Pattern.compile("(?i)^set(?:\\s+([\\s\\S]*))?$").matcher(rest);
            if (setting.matches()) {
                loraTriggerSet(event, Objects.requireNonNullElse(setting.group(1), "").strip());
                return;
            }
            if (rest.isEmpty())
                throw new IllegalArgumentException("用法：/lora triggers <名称|#编号>——查看这个 LoRA 已记下的触发词"
                        + "（原样列出、不截断，并附 Civitai 页面地址与底模）。"
                        + "手动补录：/lora triggers set <名称|#编号> <词1, 词2>（仅管理员）。");
            String value = rest.startsWith("#") ? select(event, "lora", rest) : stripQuotes(rest);
            reply(event, loraTriggerText(value));
            return;
        }
        if (arguments.equalsIgnoreCase("list")) {
            startLora(event, "正在读取 WebUI 本地 LoRA 列表。", false, () -> {
                List<SdClient.Lora> values = sd.loras();
                // 底模兜底（当前 Forge 预设栈）只读一次；每个 LoRA 的底模按 Civitai → Forge 元数据 → 预设 的优先级识别。
                SdClient.BaseModel presetBase = values.isEmpty() ? SdClient.BaseModel.NONE : presetBaseModelOrNone();
                List<String> names = new ArrayList<>(), labels = new ArrayList<>();
                Map<String, List<String>> groups = new LinkedHashMap<>();
                for (int index = 0; index < values.size(); index++) {
                    SdClient.Lora item = values.get(index);
                    String alias = item.alias() == null || item.alias().isBlank() || item.alias().equals(item.name())
                            ? "" : "别名：" + item.alias() + "；";
                    SdClient.BaseModel base = loraBaseModel(item.name(), item.path(), presetBase);
                    names.add(item.name());
                    labels.add(item.name() + "（" + alias
                            + (base.known() ? "底模：" + base.name() + base.note() : "底模未识别")
                            + "；" + loraTriggerSummary(item.name(), item.path(), index + 1) + "）");
                    groups.computeIfAbsent(base.known() ? base.name() + base.note() : "未识别底模", ignored -> new ArrayList<>())
                            .add("#" + (index + 1));
                }
                // 编号顺序保持列表顺序（#N 是 /lora load #N、/lora delete #N 的依据），
                // 分组只在上面多给一行"哪个底模是哪几个"。
                StringBuilder text = new StringBuilder("WebUI 本地 LoRA：" + values.size() + " 个（按底模分组）");
                for (Map.Entry<String, List<String>> group : groups.entrySet())
                    text.append("\n【").append(group.getKey()).append("】").append(String.join("、", group.getValue()));
                return new LoraResult(safeLoraText(text
                        + numbered(event, "lora", values.stream().map(SdClient.Lora::name).toList(), labels)
                        + "\n触发词明细：.lora triggers <名字|#编号>（原样列出，不截断；下载成功时会自动记下）"), true);
            });
            return;
        }
        if (arguments.equalsIgnoreCase("detail") || arguments.toLowerCase(Locale.ROOT).startsWith("detail ")) {
            String value = arguments.length() > 6 ? arguments.substring(6).strip() : "";
            if (value.isBlank())
                throw new IllegalArgumentException("用法：/lora detail <名称|#编号>——看一个本地 LoRA 的底模（含来源）、Civitai 记录与展示图样式。");
            startLora(event, "正在读取 LoRA 详情。", false, () -> {
                String requested = value.startsWith("#") ? select(event, "lora", value) : stripQuotes(value);
                return new LoraResult(safeLoraText(loraDetailText(requested)), true);
            });
            return;
        }
        if (arguments.equalsIgnoreCase("cover") || arguments.toLowerCase(Locale.ROOT).startsWith("cover ")) {
            String value = arguments.length() > 5 ? arguments.substring(5).strip() : "";
            if (value.isBlank())
                throw new IllegalArgumentException("用法：/lora cover <名称|#编号|all>——给已经下载好的 LoRA 补抓 Civitai 展示图（all 表示所有还缺图的）。");
            startLora(event, "正在补抓 Civitai 展示图；进度看控制台进度条或 /lora status。", true, () -> loraCover(event, value));
            return;
        }
        if (arguments.equalsIgnoreCase("rename") || arguments.toLowerCase(Locale.ROOT).startsWith("rename ")) {
            String value = arguments.length() > 6 ? arguments.substring(6).strip() : "";
            String[] parts = renameNames(value, "用法：/lora rename <旧本地名称> <新本地名称>（名称含空格时用双引号）");
            Path directory = Path.of(Json.str(Json.obj(settings.snapshot(), "civitai"), "lora_dir", ""));
            if (!Files.isDirectory(directory)) throw new IllegalStateException("未配置可用的 LoRA 目录，无法重命名。");
            String oldName = parts[0].strip(), newName = parts[1].strip();
            if (newName.contains("/") || newName.contains("\\") || newName.contains(".."))
                throw new IllegalArgumentException("新名称不能包含路径分隔符。");
            Path source = null;
            try (var files = Files.list(directory)) {
                for (Path candidate : files.filter(Files::isRegularFile).toList()) {
                    String file = candidate.getFileName().toString();
                    String stem = file.toLowerCase(Locale.ROOT).endsWith(".safetensors") ? file.substring(0, file.length() - ".safetensors".length()) : file;
                    if (stem.equalsIgnoreCase(oldName) || file.equalsIgnoreCase(oldName)) { source = candidate; break; }
                }
            }
            if (source == null) throw new IllegalArgumentException("LoRA 目录里找不到「" + oldName + "」；用 /lora list 核对名称。");
            String target = newName.toLowerCase(Locale.ROOT).endsWith(".safetensors") ? newName : newName + ".safetensors";
            Path destination = directory.resolve(target);
            if (Files.exists(destination)) throw new IllegalArgumentException("已存在同名文件：" + target + "；请换一个名称。");
            Files.move(source, destination);
            // 展示图跟着改名走：不然改完名封面就"丢了"（文件还在，但按新名字找不到了）。
            Path oldPreview = CivitaiClient.previewPath(source), newPreview = CivitaiClient.previewPath(destination);
            try { if (Files.isRegularFile(oldPreview)) Files.move(oldPreview, newPreview); }
            catch (Exception error) { Log.warn("LoRA 展示图改名失败：" + error(error)); }
            String oldStem = source.getFileName().toString().replaceFirst("(?i)\\.safetensors$", "");
            String newStem = target.replaceFirst("(?i)\\.safetensors$", "");
            sd.loras();
            userPrompts.renameLoraTag(promptScope(event), oldStem, newStem);
            Log.info("LoRA 已重命名：" + source.getFileName() + " -> " + target);
            reply(event, "LoRA 已重命名：" + oldStem + " -> " + newStem
                    + "\nWebUI 列表已刷新；你个人 prompt 里的 <lora:" + oldStem + ":...> 标签已同步为新名。"
                    + "\n如果本地记录或样式仍引用旧名，可用 /lora load " + newStem + " 重新确认。");
            return;
        }
        if (arguments.equalsIgnoreCase("delete") || arguments.toLowerCase(Locale.ROOT).startsWith("delete ")) {
            String value = arguments.length() > 7 ? arguments.substring(7).strip() : "";
            if (value.isBlank()) throw new IllegalArgumentException("用法：/lora delete <名称|#编号>（用 /lora list 查看编号）。");
            String requested = value.startsWith("#") ? select(event, "lora", value) : value;
            Path directory = Path.of(Json.str(Json.obj(settings.snapshot(), "civitai"), "lora_dir", ""));
            if (!Files.isDirectory(directory)) throw new IllegalStateException("未配置可用的 LoRA 目录（civitai.lora_dir），无法删除。");
            Path target = loraFile(directory, requested);
            if (target == null) throw new IllegalArgumentException("LoRA 目录里找不到可删除的文件：「" + requested + "」（目录：" + directory
                    + "）。如果刚删过，WebUI 的列表可能还没刷新；用 /lora list 核对名称。");
            Path base = directory.toAbsolutePath().normalize();
            if (!target.toAbsolutePath().normalize().startsWith(base))
                throw new IllegalArgumentException("只能删除 LoRA 目录下的文件：" + base);
            if (!Files.isRegularFile(target)) throw new IllegalArgumentException("不是可删除的文件：" + target.getFileName());
            String stem = target.getFileName().toString().replaceFirst("(?i)\\.safetensors$", "");
            Files.delete(target);
            // 删模型就删封面：不然 LoRA 目录里会攒一堆再也对不上号的 .preview.png。
            try { Files.deleteIfExists(CivitaiClient.previewPath(target)); }
            catch (Exception error) { Log.warn("LoRA 展示图删除失败：" + error(error)); }
            try { sd.refreshLoras(); } catch (Exception refresh) { Log.warn("删除后刷新 WebUI LoRA 列表失败：" + error(refresh)); }
            List<String> referenced = new ArrayList<>();
            for (String tag : loraTagsIn(userPrompts.prompts(promptScope(event)).positive()))
                if (tag.toLowerCase(Locale.ROOT).contains(stem.toLowerCase(Locale.ROOT))) referenced.add(tag);
            List<String> styles = new ArrayList<>();
            for (LocalStyles.Style style : localStyles.styles())
                if (style.positive() != null && style.positive().toLowerCase(Locale.ROOT).contains("<lora:" + stem.toLowerCase(Locale.ROOT) + ":")) styles.add(style.name());
            Log.info("LoRA 已删除：" + target.getFileName() + "（" + describeConversation(event) + "）");
            reply(event, "已从磁盘删除 LoRA：" + stem + "\n目录：" + base
                    + (referenced.isEmpty() ? "" : "\n注意：你的 prompt 里还有 " + String.join("、", referenced) + " 标签，"
                            + "用 /prompt remove <标签> 去掉它（SD 找不到该 LoRA 时会忽略这个标签）。")
                    + (styles.isEmpty() ? "" : "\n引用它的样式：" + String.join("、", styles) + "（可用 /style load 后重新保存，或 /style delete 删除）"));
            return;
        }
        Matcher command = Pattern.compile("^(download|load)(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE).matcher(arguments);
        if (!command.matches() || command.group(2) == null || command.group(2).isBlank())
            throw new IllegalArgumentException("用法：/lora download <Civitai链接> [权重]、/lora status、/lora list、/lora detail <名称|#编号>、"
                    + "/lora triggers <名称|#编号>（查看触发词）、"
                    + "/lora cancel（取消当前下载）、/lora pause（暂停）、/lora resume（继续）、"
                    + "/lora load <完整本地名称> [权重] 或 /lora delete <名称|#编号>。权重默认 1，范围 0–2。");
        JsonObject civitai = Json.obj(settings.snapshot(), "civitai");
        if (Json.bool(civitai, "admin_only", false) && !settings.isAdmin(Json.str(event, "user_id", "")))
            throw new IllegalArgumentException("LoRA 下载和加载已设为仅管理员可用；请在本机 config.json 的 admin_user_ids 配置管理员。");
        String value = command.group(2).strip();
        if (command.group(1).equalsIgnoreCase("download")) {
            String[] parts = value.split("\\s+");
            if (parts.length > 2) throw new IllegalArgumentException("用法：/lora download <Civitai链接> [权重]。权重默认 1，范围 0–2。");
            if (parts[0].startsWith("#")) {
                List<CivitaiClient.SearchResult> results = loraSearches.getOrDefault(conversation(event), List.of());
                int index;
                try { index = Integer.parseInt(parts[0].substring(1)) - 1; } catch (NumberFormatException e) { index = -1; }
                if (index < 0 || index >= results.size())
                    throw new IllegalArgumentException("编号无效：本次搜索的**本页**只有 " + results.size() + " 项"
                            + (results.isEmpty() ? "；先用 .lora search <搜索词> [页码] 搜索。" : "（#N 指本页第 N 条）。"));
                parts[0] = results.get(index).url();
            }
            double weight = parts.length == 2 ? loraWeight(parts[1]) : 1.0;
            startLora(event, "开始下载 LoRA，完成后自动保存展示图样式并加载模型；使用 /lora status 查看状态，"
                    + ".lora pause/resume 暂停或继续，.lora cancel 取消。", true, true,
                    () -> downloadAndLoad(parts[0], weight));
        } else {
            LoraSelection parsed = loraSelection(value);
            LoraSelection selected = new LoraSelection(select(event, "lora", parsed.name()), parsed.weight());
            startLora(event, "正在刷新并加载 LoRA，完成后会通知。", false, () -> {
                SdClient.LoadedLora loaded = sd.loadLora(selected.name(), selected.weight());
                userPrompts.withLoraTag(loraScope, loaded.tag());
                String styleReport;
                try { styleReport = CivitaiStyleSync.syncLoaded(settings.root, sd, loaded, civitaiClientOrNull()); }
                catch (Exception e) { styleReport = "LoRA 已加载，但样式同步失败：" + error(e); }
                return new LoraResult(safeLoraText("LoRA 已加载：" + loaded.name() + "\n已加入你个人的正向 prompt：" + loaded.tag()
                        + "\n用于下一次生成。\n提示词来源：" + loaded.prompts().source() + (styleReport.isEmpty() ? "" : "\n" + styleReport)),true);
            });
        }
    }
    /**
     * 下载一个 LoRA：抓模型 + 展示图、刷新 WebUI 目录、把展示图存成样式。
     *
     * <p><b>不碰提示词</b>：既不改你个人的 prompt，也不改写 WebUI 页面上的 prompt——
     * 下载只负责"把模型放到本机、让 WebUI 能选到它"。要不要用这个 LoRA（把 {@code <lora:...>}
     * 写进出图提示词）是用户自己的事：点本地列表的「加载」，或自己把标签写进去。
     *
     * <p>QQ 指令（{@code .lora download}）和控制台内部接口（{@code /api/lora/download}）走的是同一段。
     */
    private LoraResult downloadAndLoad(String link, double weight) throws Exception {
        DownloadControl control = loraControl;
        loraStartedNanos = System.nanoTime(); loraDownloaded = 0; loraTotal = -1;
        CivitaiClient.DownloadedLora downloaded;
        try {
            downloaded = loraDownloader.download(link,
                    this::loraStage,
                    (done, size) -> { loraDownloaded = done; loraTotal = size; },
                    control);
        } catch (DownloadControl.CancelledException cancelled) {
            // 取消：下载端已经停流并删掉半截临时文件（见 CivitaiClient 的 finally），这里只管如实回执。
            Log.info("LoRA 下载已取消：" + Log.text(link));
            loraStatus = safeLoraText("LoRA 下载已取消：传输已停止，半截文件已清理，没有登记进 LoRA 列表。");
            return new LoraResult(loraStatus, false);
        } finally {
            loraTotal = -1;
            // 传输阶段到此结束：之后是"加载模型/抓展示图"，取消下载已经没有意义了（取消接口据此如实说明）。
            if (control != null) control.markFinished();
        }
        String filename = downloaded.path().getFileName().toString();
        // 触发词在"下载成功这一刻"就落盘：与是否加载模型、是否把标签写进提示词完全无关；
        // 即使后面刷新/抓展示图失败，甚至这次下载被取消（文件已完整），触发词也已经留下了。
        String triggerLine = recordLoraTriggers(downloaded);
        if (control != null && control.isCancelled()) {
            // 取消来晚了：文件已经完整下载并通过校验（不是半截），但后面的刷新/展示图/加载一律不做。
            loraStatus = safeLoraText("LoRA 文件已完整下载：" + filename + "；取消请求到得太晚——下载已经完成（文件保留在 LoRA 目录），"
                    + "之后的刷新/展示图/加载阶段已跳过。\n" + triggerLine + "\n要用请点本机列表的「加载」。");
            Log.info("LoRA 下载已完成但收到取消请求（文件保留）：" + filename);
            return new LoraResult(loraStatus, false);
        }
        Path previewFile = CivitaiClient.previewPath(downloaded.path());
        boolean hasPreview = Files.isRegularFile(previewFile);
        String showcaseReport = "展示图样式尚未处理：须先确认本机 LoRA 标签。";
        loraStatus = safeLoraText("文件已保存：" + filename + "；正在刷新 WebUI 的 LoRA 列表。");
        try {
            // 刷新 WebUI 的 LoRA 目录并解析出本机标签（<lora:名字:权重>）——只读，不改任何 prompt。
            String tag = sd.resolvedLoraTag(downloaded.path(), weight);
            // 展示图样式要连模型参数一起存：底模按优先级识别（Civitai → Forge 元数据 → 预设推断），
            // 采样方法/步数/CFG/Shift/尺寸取当前 Forge 预设栈。
            JsonObject styleModel = loraStyleModel(downloaded.path().getFileName().toString(), downloaded.path(), downloaded.baseModel());
            showcaseReport = CivitaiStyleSync.sync(settings.root, downloaded, tag, sd, true, civitaiClientOrNull(), styleModel, showcaseLimit());
            // 回执要短：触发词和"展示图 N → 样式名"的逐条清单又长又只是参考，全部留给日志与本机记录，
            // 回执只说"成了、标签是什么、有没有动提示词、顺带存了什么"。
            String name = filename.replaceFirst("(?i)\\.safetensors$", "");
            StringBuilder message = new StringBuilder("LoRA ")
                    .append(downloaded.reused() ? "本地文件已复用" : "下载成功").append("：").append(name)
                    .append("\n本机标签：").append(tag).append("（没有改动提示词；要用就点本机列表的「加载」）");
            // 触发词永远报一句：下载时记下来了（哪怕是 0 条），用户可以随时回头查。
            message.append("\n");
            if (hasPreview) message.append("展示图：").append(previewFile.getFileName()).append("｜");
            message.append(triggerLine);
            message.append("\n").append(showcaseSummary(showcaseReport));
            SdClient.BaseModel downloadBase = loraBaseModel(filename, downloaded.path().toString(), null);
            Log.info("LoRA 详情（" + name + "）：基础模型=" + display(downloadBase.known()
                            ? downloadBase.name() + "（" + downloadBase.sourceLabel() + "）"
                            : "未识别（Civitai 记录里没有，Forge 元数据里也没有）")
                    + "；展示图=" + (hasPreview ? previewFile.getFileName().toString() : "无")
                    + "；触发词=" + downloaded.trainedWords().size() + " 条"
                    + (triggerLine.startsWith("触发词已记下") ? "" : "（存储写入失败）") + "\n" + showcaseReport);
            loraStatus = safeLoraText(message.toString());
            return new LoraResult(loraStatus, true);
        } catch (Exception e) {
            String retry = new JsonPrimitive(filename).toString();
            loraStatus = safeLoraText("LoRA 文件已保存：" + filename + "。\n刷新/确认本机标签失败：" + error(e)
                    + "\n" + triggerLine
                    + "\n发送 /lora load " + retry + " " + weight + " 可重试。\n" + showcaseSummary(showcaseReport));
            return new LoraResult(loraStatus, false);
        }
    }
    /**
     * 展示图样式报告的**汇总行**（第一行永远是"展示图样式：新增 X，修正 Y，…"）。
     * 逐条清单只在日志里看——回执里贴十几行"展示图 N → 样式名"没人读。
     */
    static String showcaseSummary(String report) {
        if (report == null || report.isBlank()) return "展示图样式：未处理。";
        int cut = report.indexOf('\n');
        return cut < 0 ? report : report.substring(0, cut);
    }
    /** 报一次 LoRA 进度：既写进面板用的 loraStatus，也进当前回执（如果有）。 */
    private void loraStage(String stage) {
        loraStatus = safeLoraText(stage);
        WebCapture receipt = loraReceipt;
        if (receipt != null) receipt.capture(Maps.text(stage));
    }
    /** 机器人自己的 Civitai 客户端（补展示图 / 样式预览图）；只是构造，不发请求。 */
    private CivitaiClient civitaiClient() throws java.io.IOException {
        return new CivitaiClient(settings.root, Json.obj(settings.snapshot(), "civitai"));
    }
    /**
     * 展示图样式的张数上限：config.json 的 {@code civitai.showcase_limit}（默认 0 = 不限）。
     * 超过上限的展示图会按「超出上限」计数并提示怎么调大，不再静默少做几张。
     */
    private int showcaseLimit() {
        return CivitaiClient.showcaseLimit(Json.obj(settings.snapshot(), "civitai"));
    }
    /**
     * 补预览图是附加功能，配置不全（比如没配 civitai.lora_dir）时不能把主流程一起拖垮：
     * 构造不出来就当作"没有客户端"，样式文本照旧同步，只是没有预览图。
     */
    private CivitaiClient civitaiClientOrNull() {
        try { return civitaiClient(); }
        catch (Exception error) {
            Log.warn("Civitai 未配置完整，本次不抓展示图/样式预览图：" + error(error));
            return null;
        }
    }
    /**
     * 控制台「下载」按钮：走控制台自己的接口，<b>不</b>再借道指令通道。
     *
     * <p>不等下载跑完就返回——面板靠 /api/lora/progress 每秒轮询真实进度，
     * 接口要是阻塞到下载结束，进度条就没意义了。
     */
    public JsonObject webLoraDownload(String url, double weight, String scope) {
        String link = url == null ? "" : url.strip();
        if (link.isBlank()) throw new IllegalArgumentException("请给出要下载的 Civitai 模型链接。");
        if (Double.isNaN(weight) || weight < 0 || weight > 2) throw new IllegalArgumentException("权重范围 0–2，默认 1。");
        requireLoraPermission(webEvent(scope == null ? "" : scope, "lora download"));
        WebCapture receipt = newCapture(".lora download " + link);
        if (!startLoraJob(null, receipt, "正在下载 LoRA，完成后自动保存展示图样式并加载模型。", true, true, () -> downloadAndLoad(link, weight))) {
            webCaptures.remove(receipt.id());
            return loraBusyJson();
        }
        JsonObject started = loraStartedJson();
        started.addProperty("quest", receipt.number());
        started.addProperty("captureId", receipt.id());
        return started;
    }
    /**
     * 控制台「补抓展示图」按钮：给还没配图的 LoRA 按本地 Civitai 记录补一张，同样不等它跑完。
     * {@code name} 为空或 {@code all} 表示补全所有缺图的。
     */
    public JsonObject webLoraCover(String name, String scope) {
        String value = name == null || name.isBlank() ? "all" : name.strip();
        requireLoraPermission(webEvent(scope == null ? "" : scope, "lora cover"));
        WebCapture receipt = newCapture(".lora cover " + value);
        if (!startLoraJob(null, receipt, "正在补抓 Civitai 展示图。", true, () -> loraCover(null, value))) {
            webCaptures.remove(receipt.id());
            return loraBusyJson();
        }
        JsonObject started = loraStartedJson();
        started.addProperty("quest", receipt.number());
        started.addProperty("captureId", receipt.id());
        return started;
    }
    /**
     * 控制台「取消」按钮：{@code POST /api/lora/cancel}。
     *
     * <p>马上停传输、删掉半截文件（临时 .part 由下载端的 finally 清），并且**等到下载线程真的退出**
     * 才回执——回执里那句"已取消"必须是既成事实，不是"正在取消"。等不到的极端情况（下载器压根不看
     * 控制对象、或卡在网络读取）也如实说明，不假装成功。
     *
     * <p>没有任务在跑、或取消发生在"文件已下完、正在加载/抓展示图"阶段时，一样返回 200 + 一句人话
     * （幂等，不报错）：后者会明说这个阶段取消不了。
     */
    public JsonObject webLoraCancel(String scope) {
        requireLoraPermission(webEvent(scope == null ? "" : scope, "lora cancel"));
        return loraCancelJson();
    }
    /** 控制台「暂停」按钮：{@code POST /api/lora/pause}（幂等：没任务、已暂停都只回一句话）。 */
    public JsonObject webLoraPause(String scope) {
        requireLoraPermission(webEvent(scope == null ? "" : scope, "lora pause"));
        return loraPauseJson();
    }
    /** 控制台「继续」按钮：{@code POST /api/lora/resume}（幂等：没任务、没暂停都只回一句话）。 */
    public JsonObject webLoraResume(String scope) {
        requireLoraPermission(webEvent(scope == null ? "" : scope, "lora resume"));
        return loraResumeJson();
    }
    /** 取消当前下载；返回值和 /api/lora/progress 同一份 JSON，外加一句 message 与 cancelled。 */
    private JsonObject loraCancelJson() {
        DownloadControl control = loraControl;
        if (!loraBusy.get())
            return loraControlJson("当前没有 LoRA 下载任务在跑，无需取消。", false);
        if (control == null)
            return loraControlJson("当前在跑的是 LoRA 加载/补展示图，没有可取消的下载。", false);
        if (control.isFinished())
            return loraControlJson("LoRA 文件已经下载完，正在加载模型/抓展示图，这个阶段无法取消（下载本身已经结束）。", false);
        control.cancel();
        boolean stopped = awaitLoraJob(5);
        return loraControlJson(stopped
                ? "已取消本次 LoRA 下载：传输已停止，半截文件已清理，没有登记进 LoRA 列表。"
                : "取消请求已发出，但下载线程没有在 5 秒内停下（可能卡在网络读取）；它会尽快自行结束。", stopped);
    }
    /** 暂停当前下载：进度立刻不再前进（传输循环会在下一块 64 KB 处阻塞）。 */
    private JsonObject loraPauseJson() {
        DownloadControl control = loraControl;
        if (!loraBusy.get())
            return loraControlJson("当前没有 LoRA 下载在跑，无需暂停。", false);
        if (control == null)
            return loraControlJson("当前在跑的是 LoRA 加载/补展示图，不能暂停。", false);
        if (control.isFinished())
            return loraControlJson("LoRA 文件已经下载完，正在加载模型/抓展示图，这个阶段不能暂停。", false);
        if (control.isPaused())
            return loraControlJson("LoRA 下载已经处于暂停状态；用 resume 继续，或 cancel 取消。", false);
        control.pause();
        loraStage("LoRA 下载已暂停（用 .lora resume / /api/lora/resume 继续，.lora cancel / /api/lora/cancel 取消）。");
        return loraControlJson("已暂停 LoRA 下载。", false);
    }
    /** 继续已暂停的下载。 */
    private JsonObject loraResumeJson() {
        DownloadControl control = loraControl;
        if (!loraBusy.get())
            return loraControlJson("当前没有 LoRA 下载在跑，无需继续。", false);
        if (control == null || control.isFinished())
            return loraControlJson("LoRA 下载已结束（正在加载模型/抓展示图），不需要继续。", false);
        if (!control.isPaused())
            return loraControlJson("LoRA 下载没有处于暂停状态，无需继续。", false);
        control.resume();
        loraStage("已继续下载 LoRA。");
        return loraControlJson("已继续 LoRA 下载。", false);
    }
    /** 取消/暂停/继续的统一回应：和 loraProgress() 同一份 JSON（老字段齐全）+ message（+ cancelled）。 */
    private JsonObject loraControlJson(String message, boolean cancelled) {
        JsonObject result = loraProgress();
        result.addProperty("message", message);
        result.addProperty("cancelled", cancelled);
        return result;
    }
    /** 等当前 LoRA 任务收尾（最多 seconds 秒）；true = 线程真的退出了（半截文件也已清理）。 */
    private boolean awaitLoraJob(long seconds) {
        CountDownLatch done = loraJobDone;
        try { return done.await(seconds, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }
    /** 任务已经开跑：带上 started 让接口回 202，前端据此开始轮询进度。 */
    private JsonObject loraStartedJson() {
        JsonObject result = loraProgress();
        result.addProperty("started", true);
        return result;
    }
    /** 已经有 LoRA 任务在跑时的统一回应（接口据此回 409，不排队）。 */
    private JsonObject loraBusyJson() {
        JsonObject result = loraProgress();
        result.addProperty("started", false);
        result.addProperty("error", "已有 LoRA 下载或加载操作正在处理，请等它结束后再试。");
        return result;
    }
    /**
     * 控制台接口的权限判断：和指令通道同一条规则（{@code civitai.admin_only} 只管下载/加载，
     * 搜索和查看列表不受限），只是"当前用户"取的是网页会话的身份。
     */
    private void requireLoraPermission(JsonObject event) {
        JsonObject civitai = Json.obj(settings.snapshot(), "civitai");
        if (!Json.bool(civitai, "admin_only", false)) return;
        if (!settings.isAdmin(Json.str(event, "user_id", "")))
            throw new IllegalArgumentException("LoRA 下载和加载已设为仅管理员可用；请在本机 config.json 的 admin_user_ids 配置管理员。");
    }
    /**
     * "12.3 MiB / 245.6 MiB（5.0%），速度 1.2 MiB/s，预计剩余 3 分 12 秒" — or a plain note when no
     * download is running, so .lora status always answers with the real state.
     */
    String loraProgressLine() {
        long total = loraTotal, done = loraDownloaded;
        if (total <= 0 || done < 0 || loraStartedNanos == 0) return "（当前没有进行中的下载）";
        long elapsed = Math.max(1, System.nanoTime() - loraStartedNanos);
        double seconds = elapsed / 1_000_000_000.0;
        double mib = 1024.0 * 1024.0;
        double speed = done / seconds;                       // bytes per second
        StringBuilder line = new StringBuilder();
        line.append(String.format(Locale.ROOT, "%.1f MiB / %.1f MiB（%.1f%%）", done / mib, total / mib, done * 100.0 / total));
        if (speed > 0) line.append(String.format(Locale.ROOT, "，速度 %.2f MiB/s", speed / mib));
        if (speed > 0 && total > done) {
            long remain = (long) Math.ceil((total - done) / speed);
            line.append("，预计剩余 ").append(remain >= 3600
                    ? (remain / 3600) + " 小时 " + (remain % 3600) / 60 + " 分"
                    : remain >= 60 ? (remain / 60) + " 分 " + (remain % 60) + " 秒" : remain + " 秒");
        }
        return line.toString();
    }
    /**
     * 控制台进度条用的实时状态。和 {@link #loraProgressLine()} 同源，只是给网页一份结构化的。
     *
     * <p>{@code metered} 为假时也要给出 {@code stage}：下载前的"正在读取模型信息"、下载后的
     * "正在加载到 WebUI"都没有字节数可报，进度条退回不确定态（来回滚动），不能显示成 0%。
     */
    public JsonObject loraProgress() {
        JsonObject result = new JsonObject();
        boolean downloading = loraDownloading, busy = loraBusy.get();
        DownloadControl control = loraControl;
        result.addProperty("busy", busy);
        result.addProperty("downloading", downloading);
        result.addProperty("stage", loraStatus == null ? "" : loraStatus);
        // 控制台「取消/暂停」按钮的三个新字段（老字段一个都没动：桌面端的进度卡还在读 busy/downloading/stage/metered）。
        // active：有任务在跑（下载、加载、补展示图都算），语义上等于 busy。
        // cancellable：这**一阶段**能不能取消——只有传输还在进行时才算（进了加载/抓展示图就取消不了下载了）。
        // paused：当前下载是否暂停（取消过的下载不算暂停）。
        result.addProperty("active", busy);
        result.addProperty("cancellable", control != null && !control.isFinished() && !control.isCancelled());
        result.addProperty("paused", control != null && control.isPaused());
        long total = loraTotal, done = loraDownloaded;
        boolean metered = downloading && total > 0 && done >= 0;
        result.addProperty("metered", metered);
        if (metered) {
            long elapsed = Math.max(1, System.nanoTime() - loraStartedNanos);
            double seconds = elapsed / 1_000_000_000.0;
            double speed = done / seconds;
            result.addProperty("done", done);
            result.addProperty("total", total);
            result.addProperty("percent", Math.min(100.0, done * 100.0 / total));
            result.addProperty("speed", speed);
            if (speed > 0 && total > done) result.addProperty("etaSeconds", (long) Math.ceil((total - done) / speed));
        }
        return result;
    }
    private void startLora(JsonObject event, String start, boolean downloading, LoraAction action) {
        startLora(event, start, downloading, false, action);
    }
    /**
     * @param cancellable 这次任务带一个可暂停/取消的下载控制对象：只有真正下载文件的才传 true；
     *                    「补展示图」这类虽然也标 downloading（面板照样显示进度条），但不该假装能暂停/取消。
     */
    private void startLora(JsonObject event, String start, boolean downloading, boolean cancellable, LoraAction action) {
        if (startLoraJob(event, start, downloading, cancellable, action)) return;
        reply(event, "已有 LoRA 下载或加载操作正在处理，请稍后重试；/lora status 可查看最近下载状态。");
        completeChatWorkflowStep(event, false);
    }
    /**
     * 启动一个后台 LoRA 任务（下载 / 加载 / 补展示图）。
     *
     * @param event 要回执的会话；控制台内部接口触发时传 {@code null}——它不回执，结果只写进
     *              {@link #loraStatus}，由面板轮询 {@code /api/lora/progress} 取。
     * @return false 表示已经有任务在跑（调用方自己决定是回"稍后再试"还是回 409）
     */
    private boolean startLoraJob(JsonObject event, String start, boolean downloading, LoraAction action) {
        return startLoraJob(event, null, start, downloading, false, action);
    }
    private boolean startLoraJob(JsonObject event, String start, boolean downloading, boolean cancellable, LoraAction action) {
        return startLoraJob(event, null, start, downloading, cancellable, action);
    }
    /**
     * @param receipt     网页接口触发的任务带上自己的回执：进度与结果都会进 /quest/#N（QQ/聊天路径传 null）
     * @param cancellable true 时新建一个 {@link DownloadControl} 串进下载链路（可暂停/可取消）
     */
    private boolean startLoraJob(JsonObject event, WebCapture receipt, String start, boolean downloading, LoraAction action) {
        return startLoraJob(event, receipt, start, downloading, false, action);
    }
    private boolean startLoraJob(JsonObject event, WebCapture receipt, String start, boolean downloading, boolean cancellable, LoraAction action) {
        if (loraClosed.get()) throw new IllegalStateException("机器人正在关闭，请稍后重试。");
        if (!loraBusy.compareAndSet(false, true)) return false;
        JsonObject context = event == null ? null : attachNormalWindow(event.deepCopy());
        CountDownLatch done = new CountDownLatch(1);
        loraJobDone = done;
        // 每次下载都是**新**的控制对象：上一次下载的取消/暂停绝不会留到这一次。
        loraControl = cancellable ? new DownloadControl() : null;
        loraReceipt = receipt;
        if (receipt != null) receipt.capture(Maps.text(start));
        if (downloading) { loraStatus = safeLoraText(start); loraDownloading = true; }
        Log.info("LoRA 操作开始（" + (context == null ? "控制台" : describeConversation(context)) + "）：" + Log.text(start));
        // 常规档：这条只是"正在下载/加载"的过程说明，收进这条指令的唯一回执里。
        if (context != null) replyProcess(context, start);
        try {
            loraIO.execute(() -> {
                LoraResult result;
                try { result = action.run(); }
                catch (Exception e) {
                    result = new LoraResult(safeLoraText((downloading ? "LoRA 下载失败：" : "LoRA 操作失败：") + error(e)),false);
                    if (downloading) loraStatus = result.text();
                } finally {
                    if (downloading) loraDownloading = false;
                    loraBusy.set(false);
                    loraControl = null;     // 控制对象随任务一起作废
                    loraReceipt = null;
                    done.countDown();       // 取消接口据此确认"半截文件已清掉、下载线程真的退出了"
                }
                Log.info("LoRA 操作完成（成功=" + result.success() + "）：" + Log.text(result.text()));
                // 成功就是一句肯定，失败就是那条说明（含"展示图样式：新增/修正/复用…"这类汇总行，
                // 常规档下它们全都收在这一条里，不再一步一行）。
                if (context != null) {
                    replyRanked(context, result.success() ? ReceiptRank.AFFIRM : ReceiptRank.FAILURE, result.text());
                    releaseNormalWindow(context);
                }
                // 控制台触发的任务没有 QQ 回执通道：把最终结论写进 loraStatus，进度条收起时显示的就是它。
                else loraStatus = result.text();
                if (receipt != null) { receipt.capture(Maps.text(result.text())); receipt.finish(); observeQuest(receipt); }
                completeChatWorkflowStep(context, result.success());
            });
        } catch (RejectedExecutionException e) {
            loraBusy.set(false);
            loraDownloading = false;
            loraControl = null;
            loraReceipt = null;
            done.countDown();
            if (downloading) loraStatus = "操作未启动；机器人正在关闭，请稍后重试。";
            if (receipt != null) { receipt.capture(Maps.text("操作失败：机器人正在关闭，请稍后重试。")); receipt.finish(); observeQuest(receipt); }
            completeChatWorkflowStep(context, false);
            releaseNormalWindow(context);
            throw new IllegalStateException("机器人正在关闭，请稍后重试。");
        }
        return true;
    }
    /**
     * 补展示图：按本地 Civitai 记录里的版本号去取图，不重新下载模型文件。一次把两样都照顾到——
     * LoRA 旁边的封面（{@code <模型名>.preview.png}）和展示图样式的预览图。
     * 已有的直接跳过，所以重复点是安全的（只会多问几次 Civitai 的版本信息）。
     */
    private LoraResult loraCover(JsonObject event, String value) throws Exception {
        JsonObject civitai = Json.obj(settings.snapshot(), "civitai");
        Path directory = Path.of(Json.str(civitai, "lora_dir", ""));
        if (!Files.isDirectory(directory)) throw new IllegalStateException("未配置可用的 LoRA 目录（civitai.lora_dir），无法补抓展示图。");
        List<SdClient.Lora> catalog;
        try { catalog = sd.loras(); }
        catch (Exception error) { throw new IllegalStateException("读不到 WebUI 的 LoRA 列表：" + error(error)); }
        Path records = settings.root.resolve("data/civitai");
        List<Path> targets = new ArrayList<>();
        if (value.equalsIgnoreCase("all")) {
            // 不是"只挑缺封面的"：封面齐了也得走一遍，样式的预览图可能还没补过。
            for (SdClient.Lora item : catalog) {
                Path file = loraFile(directory, item.name());
                if (file != null && Files.isRegularFile(records.resolve(file.getFileName() + ".json"))) targets.add(file);
            }
            if (targets.isEmpty())
                return new LoraResult(safeLoraText("本机 " + catalog.size() + " 个 LoRA 都没有 Civitai 下载记录，没有可补的展示图。"), true);
        } else {
            // "#编号" 只有聊天会话里有（那是"你刚看过的那份列表"）；控制台按名字点名，编号没有意义。
            String requested = value.startsWith("#") && event != null ? select(event, "lora", value) : value;
            Path file = loraFile(directory, requested);
            if (file == null) throw new IllegalArgumentException("LoRA 目录里找不到「" + requested + "」；用 /lora list 核对名称。");
            targets.add(file);
        }
        CivitaiClient client = new CivitaiClient(settings.root, civitai);
        int covers = 0, failed = 0, index = 0, stylePreviews = 0, styleSizes = 0;
        List<String> lines = new ArrayList<>();
        for (Path file : targets) {
            index++;
            final int position = index;
            String filename = file.getFileName().toString();
            String where = "补展示图（" + position + "/" + targets.size() + "）" + filename + "：";
            loraStatus = safeLoraText(where + "正在读取 Civitai 版本信息。");
            JsonObject record = readManifest(records.resolve(filename + ".json"));
            if (record == null) {
                failed++;
                lines.add(filename + "：没有 Civitai 下载记录，无法确定版本；重新用 /lora download 下一个才会有记录。");
                continue;
            }
            try {
                long modelId = record.get("model_id").getAsLong(), versionId = record.get("version_id").getAsLong();
                JsonObject version = client.versionInfo(modelId, versionId);
                Path previewFile = CivitaiClient.previewPath(file);
                boolean hadCover = Files.isRegularFile(previewFile);
                Path preview = client.saveLoraPreview(version, file, stage -> loraStatus = safeLoraText(where + stage));
                if (preview != null && !hadCover) covers++;
                StyleBackfill backfill = backfillStylePreviews(client, version, file, modelId, versionId, record);
                stylePreviews += backfill.previews();
                styleSizes += backfill.sized();
                if (!backfill.report().isEmpty()) lines.add(filename + "：" + backfill.report());
                if (preview == null && !hadCover) lines.add(filename + "：这个版本在 Civitai 上没有可用的展示图。");
                else if (!hadCover) lines.add(filename + " → " + preview.getFileName());
            } catch (Exception error) {
                failed++;
                lines.add(filename + "：" + error(error));
            }
        }
        String message = "补展示图：LoRA 封面 " + covers + " 张，样式预览图 " + stylePreviews + " 张，"
                + "补展示图尺寸 " + styleSizes + " 条，失败 " + failed + " 个，处理 " + targets.size() + " 个。"
                + (covers == 0 && stylePreviews == 0 && styleSizes == 0 && failed == 0 ? "\n本来就没有缺的，不用补。" : "")
                + (lines.isEmpty() ? "" : "\n" + String.join("\n", lines));
        return new LoraResult(safeLoraText(message), failed == 0);
    }
    /**
     * 补齐展示图样式与它们的预览图：同一个版本的展示图就是那几条样式的配图，按本地记录里的
     * {@code showcase_prompts} 对应回去。
     *
     * <p>{@code create=true}：缺的样式**要建出来**。老版本只认 A1111 元数据，ComfyUI 出图的模型
     * 整批被判成"没有提示词"，样式一条都没落盘；只补图不建样式的话，那些模型永远补不回来。
     * 已经存在的样式按内容匹配复用，不会重复建。
     *
     * <p>这条重跑路径同时补两样老数据缺的东西：v1.0.10 之前没有模型参数、v1.0.12 之前没有展示图尺寸
     * （{@code model.width/height}，来自展示图本身的像素）。
     */
    private record StyleBackfill(int previews, int sized, String report) { }

    /**
     * @return 这次新存下来的预览图张数、补上尺寸的样式条数，以及同步回执（供「补展示图」汇总）
     */
    private StyleBackfill backfillStylePreviews(CivitaiClient client, JsonObject version, Path file, long modelId, long versionId, JsonObject record) {
        try {
            String tag = sd.resolvedLoraTag(file, 1);
            List<CivitaiClient.ShowcasePrompt> showcases = CivitaiClient.showcasePrompts(version);
            if (showcases.isEmpty()) return new StyleBackfill(0, 0, "");
            long before = stylePreviewCount();
            CivitaiClient.DownloadedLora download = new CivitaiClient.DownloadedLora(
                    Json.str(record, "model_name", ""), Json.str(record, "version_name", ""), Json.str(record, "base_model", ""),
                    List.of(), file, true, modelId, versionId, showcases);
            // 补图顺手把模型参数补齐：v1.0.10 之前存的展示图样式只有提示词，没有底模/采样参数。
            JsonObject model = loraStyleModel(file.getFileName().toString(), file, Json.str(record, "base_model", ""));
            CivitaiStyleSync.Outcome outcome = CivitaiStyleSync.run(settings.root, download, tag, sd, true, client, model, showcaseLimit());
            int previews = (int) Math.max(0, stylePreviewCount() - before);
            return new StyleBackfill(previews, outcome.sized(), outcome.text().split("\n", 2)[0]);
        } catch (Exception error) {
            Log.warn("补样式预览图失败（" + file.getFileName() + "）：" + error(error));
            return new StyleBackfill(0, 0, "");
        }
    }
    /** 现在一共存了多少张样式预览图（补图前后各数一次，差值就是要报的张数）。 */
    private long stylePreviewCount() {
        Path directory = cn.szu.bot.sd.StylePreviews.dir(settings.root);
        try (var files = Files.list(directory)) { return files.filter(Files::isRegularFile).count(); }
        catch (Exception ignored) { return 0; }
    }
    /** 读 Civitai 下载记录（data/civitai/<文件名>.json）；读不到就当没有，别让补图整个失败。 */
    private static JsonObject readManifest(Path file) {
        try { return Files.isRegularFile(file) ? Json.parse(Files.readString(file)) : null; }
        catch (Exception error) { return null; }
    }
    /**
     * 这个 LoRA 的 Civitai 记录里的底模（{@code data/civitai/<文件名>.json} 的 base_model）。
     *
     * <p>记录是按**下载时的文件名**存的，所以要用本机文件去对，不能拿 WebUI 的显示名去猜。
     */
    private String civitaiBaseModel(Path file) {
        if (file == null) return "";
        try {
            JsonObject record = readManifest(settings.root.resolve("data/civitai").resolve(file.getFileName() + ".json"));
            return record == null ? "" : Json.str(record, "base_model", "");
        } catch (Exception error) { return ""; }
    }
    /**
     * 一个 LoRA 的底模，优先级：Civitai 下载记录 → Forge 的 LoRA 元数据 → 当前 Forge 预设栈（标成推断）。
     * 任何一步读不到都不影响其它来源：最差也只是"按当前预设推断"或"未识别"。
     *
     * @param presetFallback 当前预设栈的底模（列表里算一次就够，传 null 表示现读）
     */
    private SdClient.BaseModel loraBaseModel(String name, String path, SdClient.BaseModel presetFallback) {
        Path file = null;
        try { if (path != null && !path.isBlank()) file = Path.of(path); }
        catch (Exception ignored) { /* 列表里的路径用不了就当没有 Civitai 记录 */ }
        if (file == null || !Files.isRegularFile(file)) file = localLoraFile(name);
        try { return sd.resolveBaseModel(civitaiBaseModel(file), name, path, presetFallback); }
        catch (Exception error) { return presetFallback == null ? SdClient.BaseModel.NONE : presetFallback; }
    }
    /** 当前 Forge 预设栈的底模（列表兜底用，读一次）；读不到就是"没有"。 */
    private SdClient.BaseModel presetBaseModelOrNone() {
        try { return sd.presetBaseModel(); } catch (Exception error) { return SdClient.BaseModel.NONE; }
    }
    /**
     * 载入样式之后：这份样式的**归属栈**与当前 Forge 栈不一致就如实提示一句（**不偷偷切栈**）。
     * 判不出栈、不是 Forge、读不到预设都返回空串，绝不硬猜一句警告出来。
     */
    private String styleStackNotice(LocalStyles.Style style) {
        if (style == null || !style.hasModel()) return "";
        try { return sd.styleStackNotice(style.model()); } catch (Exception error) { return ""; }
    }
    /**
     * 基础模型的展示标签（{@code waiIllustriousSDXL_v170 [SDXL 栈 · 预设 xl]}）。
     * 判不出栈的原样返回文件名——**不编栈**。顺序与 {@code names} 一一对应。
     */
    List<String> modelLabels(List<String> names) {
        Map<String, SdClient.ModelInfo> byTitle = new LinkedHashMap<>();
        try {
            for (SdClient.ModelInfo info : sd.modelInfos()) byTitle.put(info.title(), info);
        } catch (Exception error) { /* 读不到就只给文件名 */ }
        List<String> labels = new ArrayList<>();
        for (String name : names) {
            SdClient.ModelInfo info = byTitle.get(name);
            if (info == null || !info.stackKnown()) { labels.add(name); continue; }
            String preset = info.preset() == null || info.preset().isBlank() ? info.stack() : info.preset();
            labels.add(info.name() + " [" + info.stackLabel() + (preset.equalsIgnoreCase(info.stack()) ? "" : " · 预设 " + preset) + "]");
        }
        return labels;
    }
    /** 网页「基础模型」下拉的数据：每个模型带归属栈（前端显示 {@code 名字 [SDXL 栈]}）。 */
    private JsonArray modelOptions() {
        JsonArray result = new JsonArray();
        try {
            for (SdClient.ModelInfo info : sd.modelInfos()) {
                JsonObject item = new JsonObject();
                item.addProperty("title", info.title());
                item.addProperty("name", info.name());
                item.addProperty("stack", info.stack());
                item.addProperty("stackLabel", info.stackLabel());
                item.addProperty("preset", info.preset() == null ? "" : info.preset());
                item.addProperty("stackSource", info.stackSource());
                item.addProperty("stackSourceLabel", cn.szu.bot.sd.StackClassifier.sourceLabel(info.stackSource()));
                item.addProperty("baseModel", info.baseModel());
                item.addProperty("label", info.label());
                item.addProperty("evidence", info.evidence());
                result.add(item);
            }
        } catch (Exception error) { /* SD 没在跑：下拉为空，面板另有说明 */ }
        return result;
    }
    /**
     * 在 LoRA 目录里按名字找一个文件（只扫目录，不问 WebUI）：列一页 LoRA 不该为每个都多打一次接口。
     */
    private Path localLoraFile(String name) {
        String directory = Json.str(Json.obj(settings.snapshot(), "civitai"), "lora_dir", "");
        String wanted = name == null ? "" : name.strip().replace('\\', '/');
        if (directory.isBlank() || wanted.isBlank()) return null;
        try {
            Path base = Path.of(directory).toAbsolutePath().normalize();
            String filename = wanted.substring(wanted.lastIndexOf('/') + 1);
            Path direct = base.resolve(filename).normalize();
            if (direct.startsWith(base) && Files.isRegularFile(direct)) return direct;
            Path withExtension = base.resolve(filename.toLowerCase(Locale.ROOT).endsWith(".safetensors") ? filename : filename + ".safetensors").normalize();
            return withExtension.startsWith(base) && Files.isRegularFile(withExtension) ? withExtension : null;
        } catch (Exception error) { return null; }
    }
    /** 本机 Civitai 记录里的模型名（展示图样式的前缀就是它，查"这个 LoRA 有哪些样式"用）。 */
    private String loraRecordName(Path file) {
        JsonObject record = file == null ? null : readManifest(settings.root.resolve("data/civitai").resolve(file.getFileName() + ".json"));
        return record == null ? "" : Json.str(record, "model_name", "");
    }

    // ---- LoRA 触发词（data/lora-triggers.json）----

    /**
     * 下载成功那一刻把触发词写进 {@code data/lora-triggers.json}，并返回回执里那一行。
     *
     * <p>写的是 Civitai 给的 trained words 原文与 Civitai 页面上的 Base Model；加载失败、用户不加进提示词、
     * 甚至这次下载被取消（文件已经完整）都不影响写入。这个方法**永远不抛错**：存储写不进去也只是如实回一句，
     * 绝不把一次成功的下载变成失败。
     */
    private String recordLoraTriggers(CivitaiClient.DownloadedLora downloaded) {
        String filename = downloaded.path().getFileName().toString();
        String stem = LoraTriggers.key(filename);
        String look = ".lora triggers " + quotedLoraName(stem);
        LoraTriggers.Trigger record = null;
        String failure = "";
        try {
            record = loraTriggers.put(new LoraTriggers.Trigger(stem, filename,
                    downloaded.trainedWords() == null ? List.of() : downloaded.trainedWords(),
                    LoraTriggers.sourceUrl(civitaiBaseUrl(), downloaded.modelId(), downloaded.versionId()),
                    downloaded.modelId(), downloaded.versionId(),
                    LoraTriggers.baseModel(downloaded.baseModel()), loraStackOf(filename, downloaded.path()),
                    java.time.Instant.now().toString(), LoraTriggers.SOURCE_DOWNLOAD));
            Log.info("LoRA 触发词已记下（" + stem + "）：" + record.count() + " 条 → " + loraTriggers.path());
        } catch (Exception error) {
            failure = error(error);
            Log.warn("LoRA 触发词写入失败（" + stem + "）：" + failure);
        }
        if (record == null) return "触发词记录失败：" + failure + "（LoRA 文件本身已经保存好了）";
        if (record.count() == 0) return "触发词已记下：0 条（Civitai 这个版本没给；" + look + " 可查看）";
        return "触发词已记下：" + record.count() + " 条（" + look + " 可随时查看）";
    }

    /** 本机家族/栈判断（只作参考，另一路存进触发词记录的 stack 字段）；判不出来就空着，不去猜。 */
    private String loraStackOf(String name, Path file) {
        try {
            SdClient.BaseModel base = loraBaseModel(name, file == null ? "" : file.toString(), null);
            return base.stackKnown() ? base.stack() : "";
        } catch (Exception error) { return ""; }
    }

    /** 配置里的 Civitai 站点地址（页面链接用）；没配就用默认镜像。 */
    private String civitaiBaseUrl() {
        String configured = Json.str(Json.obj(settings.snapshot(), "civitai"), "base_url", CivitaiClient.DEFAULT_BASE_URL).strip();
        return configured.isBlank() ? CivitaiClient.DEFAULT_BASE_URL : configured;
    }

    /** 名字里有空格或以 # 开头时加双引号，用户可以直接把回执里的写法复制回来。 */
    private static String quotedLoraName(String name) {
        String value = Objects.requireNonNullElse(name, "");
        if (value.isEmpty()) return value;
        boolean needsQuotes = value.startsWith("#");
        for (int index = 0; index < value.length() && !needsQuotes; index++)
            if (Character.isWhitespace(value.charAt(index))) needsQuotes = true;
        return needsQuotes ? new JsonPrimitive(value).toString() : value;
    }

    /**
     * 本机 LoRA 的触发词记录：先查 {@code data/lora-triggers.json}，再**只读**回落到
     * {@code data/civitai/<文件名>.json} 里的下载记录；两边都没有返回 {@code null}（= 没有记录）。
     */
    private LoraTriggers.Trigger loraTriggersOf(String name, String path) {
        LoraTriggers.Trigger stored = loraTriggers.get(name);
        if (stored != null) return stored;
        String filename = loraFileName(name, path);
        if (filename.isBlank()) return null;
        stored = loraTriggers.get(filename);
        return stored != null ? stored : LoraTriggers.fromCivitaiRecord(settings.root, filename, civitaiBaseUrl());
    }

    /** 本机 LoRA 的文件名（含 .safetensors）：优先用真实文件，其次按 WebUI 给的名字补后缀。 */
    private String loraFileName(String name, String path) {
        try {
            if (path != null && !path.isBlank()) {
                Path candidate = Path.of(path);
                if (Files.isRegularFile(candidate)) return candidate.getFileName().toString();
            }
        } catch (Exception ignored) { /* 路径不可用就退回按名字找文件 */ }
        Path file = localLoraFile(name);
        if (file != null) return file.getFileName().toString();
        String value = Objects.requireNonNullElse(name, "").strip().replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1);
        if (value.isBlank()) return "";
        return value.toLowerCase(Locale.ROOT).endsWith(".safetensors") ? value : value + ".safetensors";
    }

    /** `.lora list` 每条里那半句触发词摘要（没有记录就如实说"未记录"，不假装有）。 */
    private String loraTriggerSummary(String name, String path, int number) {
        LoraTriggers.Trigger record = loraTriggersOf(name, path);
        if (record == null) return "触发词未记录";
        if (record.count() == 0) return "触发词 0 条（下载时 Civitai 没给）";
        return "触发词 " + record.count() + " 条（.lora triggers #" + number + " 看全部）";
    }

    /** 触发词查询/补录定位到的本机 LoRA：名字 + 真实文件（拿不到文件时为 null）。 */
    private record LoraTarget(String name, Path file) {
        String fileName() { return file == null ? "" : file.getFileName().toString(); }
    }

    /** 本机 LoRA 列表（WebUI 拿不到时扫配置目录；扫描只读，不改任何文件）。 */
    private List<LoraEntry> loraEntries() {
        List<LoraEntry> entries = new ArrayList<>();
        try {
            for (SdClient.Lora item : sd.loras()) {
                Path file = null;
                try {
                    Path candidate = Path.of(item.path());
                    if (Files.isRegularFile(candidate)) file = candidate;
                } catch (Exception ignored) { /* 列表里的路径不可用就按名字找 */ }
                if (file == null) file = localLoraFile(item.name());
                entries.add(new LoraEntry(item.name(), Objects.requireNonNullElse(item.alias(), ""),
                        LoraTriggers.key(item.name()), file));
            }
        } catch (Exception error) { Log.warn("读取 WebUI LoRA 列表失败，改扫本机 LoRA 目录：" + error(error)); }
        if (entries.isEmpty()) {
            String directory = Json.str(Json.obj(settings.snapshot(), "civitai"), "lora_dir", "");
            try (var files = Files.list(Path.of(directory))) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".safetensors"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))).toList())
                    entries.add(new LoraEntry(file.getFileName().toString(), "",
                            LoraTriggers.key(file.getFileName().toString()), file));
            } catch (Exception error) { /* 目录没配/读不到：候选项就是空的，回执里会如实说 */ }
        }
        return entries;
    }

    /** LoRA 目录里的一个条目：显示名、别名、文件名去掉扩展名、真实文件。 */
    private record LoraEntry(String name, String alias, String stem, Path file) { }

    /**
     * 把用户写的名字/#编号解析成本机 LoRA：先精确（显示名/别名/文件名），再"唯一命中才算"的宽松匹配；
     * 命中多个或一个都没有就**列出候选项**——绝不挑一个"最像的"去查错模型。
     */
    private LoraTarget resolveLoraTarget(String requested) throws Exception { return resolveLoraTarget(requested, false); }

    /**
     * @param exactOnly 写数据（手动补录）时必须写完整名字或 #编号：宽松匹配只留给"查询"用，
     *                  免得"只写了名字的一部分"就把词写到了别的 LoRA 上
     */
    private LoraTarget resolveLoraTarget(String requested, boolean exactOnly) throws Exception {
        String wanted = Objects.requireNonNullElse(requested, "").strip();
        if (wanted.isBlank()) throw new IllegalArgumentException("请给出 LoRA 名称或 #编号（用 /lora list 查看本机列表）。");
        String key = LoraTriggers.key(wanted);
        List<LoraEntry> entries = loraEntries();
        for (LoraEntry entry : entries) {
            if (entry.name().equalsIgnoreCase(wanted) || entry.stem().equalsIgnoreCase(key)
                    || (!entry.alias().isBlank() && entry.alias().equalsIgnoreCase(wanted)))
                return new LoraTarget(entry.name(), entry.file());
        }
        if (exactOnly)
            throw new IllegalArgumentException("要补录的 LoRA 必须写完整名字（或 #编号）：「" + wanted + "」不是本机某个 LoRA 的完整名字；"
                    + "名字含空格时用双引号，例如 /lora triggers set \"测试 LoRA 2\" 词1, 词2。候选项："
                    + loraCandidates(entries.stream().map(LoraEntry::name).toList()));
        String wantedLoose = LoraTriggers.looseKey(key);
        List<LoraEntry> matched = new ArrayList<>();
        for (LoraEntry entry : entries)
            if (!wantedLoose.isEmpty() && LoraTriggers.looseKey(entry.stem()).equals(wantedLoose)) matched.add(entry);
        // 再退一步：用户只写了文件名的一部分（"未记录" → "未记录LoRA"）。同样只有唯一命中才敢用。
        if (matched.isEmpty() && wantedLoose.length() >= 2) {
            for (LoraEntry entry : entries)
                if (LoraTriggers.looseKey(entry.stem()).contains(wantedLoose)) matched.add(entry);
        }
        if (matched.size() == 1) return new LoraTarget(matched.get(0).name(), matched.get(0).file());
        if (matched.size() > 1)
            throw new IllegalArgumentException("「" + wanted + "」匹配到多个 LoRA，请写完整名字或 #编号："
                    + loraCandidates(matched.stream().map(LoraEntry::name).toList()));
        throw new IllegalArgumentException("本机 LoRA 里找不到「" + wanted + "」；候选项：" + loraCandidates(entries.stream().map(LoraEntry::name).toList()));
    }

    /** 候选项列表（最多 12 个，多了只报个数——回执不是文件列表）。 */
    private static String loraCandidates(List<String> names) {
        if (names == null || names.isEmpty()) return "（本机一个 LoRA 都没有：先用 /lora download 下一个）";
        if (names.size() <= 12) return String.join("、", names);
        return String.join("、", names.subList(0, 12)) + " 等 " + names.size() + " 个";
    }

    /**
     * {@code .lora triggers <名称|#编号>} 的回执：触发词**原样**逐条列出（有序、不截断、不省略号），
     * 并附来源（Civitai 页面地址 / 底模 / 记录时间）。没有记录时如实说明，绝不编。
     */
    private String loraTriggerText(String requested) throws Exception {
        LoraTarget target = resolveLoraTarget(requested);
        LoraTriggers.Trigger record = loraTriggersOf(target.name(), target.file() == null ? "" : target.file().toString());
        StringBuilder text = new StringBuilder("LoRA：" + target.name());
        if (target.file() != null) text.append("（本机文件 ").append(target.file().getFileName()).append("）");
        if (record == null) {
            text.append("\n这个 LoRA 没有记下触发词（可能是本地导入、或下载时 Civitai 没给）。");
            text.append("\n可以自己补录：/lora triggers set ").append(quotedLoraName(target.name())).append(" 词1, 词2（仅管理员）");
            return text.toString();
        }
        if (loraTriggers.corrupt())
            text.append("\n注意：触发词存储文件（").append(loraTriggers.path()).append("）读起来有问题，下面只显示能读到的部分。");
        if (record.count() == 0) {
            text.append("\n触发词：0 条——").append(record.sourceLabel()).append("里这个版本没有触发词。");
        } else {
            text.append("\n触发词 ").append(record.count()).append(" 条（原样列出，未截断）：");
            for (int index = 0; index < record.trainedWords().size(); index++)
                text.append("\n").append(index + 1).append(". ").append(record.trainedWords().get(index));
            boolean multiline = false;
            for (String word : record.trainedWords())
                if (word.indexOf('\n') >= 0 || word.indexOf('\r') >= 0) multiline = true;
            if (multiline) text.append("\n（有触发词本身含换行，就照上面逐条原样看；这里不再给合并写法，免得改写了原文）");
            else text.append("\n合并写法（可直接粘贴）：").append(String.join(", ", record.trainedWords()));
        }
        text.append("\n来源：").append(record.sourceLabel())
                .append("｜").append(record.hasPage() ? record.pageAddress() : "没有 Civitai 页面地址")
                .append("｜").append(record.baseModel().isBlank() ? "底模未记录" : "Civitai 底模 " + record.baseModel());
        if (!record.stack().isBlank()) text.append("（本机栈 ").append(record.stack()).append("）");
        if (!record.recordedAt().isBlank()) text.append("｜记录时间 ").append(record.recordedAt());
        return text.toString();
    }

    /**
     * {@code .lora triggers set <名称|#编号> <词1, 词2>}：管理员手动补录（写进同一份存储）。
     * 只改触发词，原来记着的页面地址/底模/栈尽力保留；不碰 LoRA 文件，也不碰任何提示词。
     */
    private void loraTriggerSet(JsonObject event, String arguments) throws Exception {
        if (!settings.isAdmin(Json.str(event, "user_id", "")))
            throw new IllegalArgumentException("手动补录触发词仅管理员可用（在本机 config.json 的 admin_user_ids 里配置管理员）。");
        String[] parts = triggerSetArguments(arguments);
        String requested = parts[0].startsWith("#") && event != null ? select(event, "lora", parts[0]) : parts[0];
        List<String> words = new ArrayList<>();
        for (String word : parts[1].split("[,，、\\r\\n]")) {
            String trimmed = word.strip();
            if (!trimmed.isEmpty()) words.add(trimmed);
        }
        if (words.isEmpty()) throw new IllegalArgumentException("请至少给出一个触发词，例如：/lora triggers set \"模型名\" 词1, 词2（仅管理员）。");
        LoraTarget target = resolveLoraTarget(requested, true);
        LoraTriggers.Trigger record = loraTriggers.setWords(target.name(), target.fileName(), words);
        Log.info("LoRA 触发词手动补录（" + record.loraName() + "）：" + record.count() + " 条（" + describeConversation(event) + "）");
        reply(event, "已写入触发词记录：" + record.loraName() + "（" + record.count() + " 条）"
                + "\n存储：" + loraTriggers.path()
                + "\n查看：/lora triggers " + quotedLoraName(record.loraName())
                + "\n（来源记为「" + record.sourceLabel() + "」；不改 LoRA 文件，也不动提示词）");
    }

    /** 把 {@code <名称|#编号> <词1, 词2>} 拆成名字与词表；名字含空格时用双引号。 */
    static String[] triggerSetArguments(String arguments) {
        String text = Objects.requireNonNullElse(arguments, "").strip();
        if (text.isEmpty())
            throw new IllegalArgumentException("用法：/lora triggers set <名称|#编号> <词1, 词2>；名称含空格时用双引号，例如 "
                    + "/lora triggers set \"我的 LoRA\" 词1, 词2。");
        if (text.startsWith("\"")) {
            // 名字在双引号里（可含空格），后面整段都是词表——QUOTED_LORA 只认一个尾随 token（权重），这里不能复用。
            Matcher quoted = Pattern.compile("^(\"(?:[^\"\\\\]|\\\\.)*\")(?:\\s+([\\s\\S]+))?$").matcher(text);
            if (!quoted.matches())
                throw new IllegalArgumentException("名称引号格式不正确，例如 /lora triggers set \"我的 LoRA\" 词1, 词2。");
            String name;
            try {
                name = STRICT_JSON.fromJson(quoted.group(1), String.class);
            } catch (JsonParseException error) {
                throw new IllegalArgumentException("名称须使用有效的双引号字符串。");
            }
            String words = Objects.requireNonNullElse(quoted.group(2), "").strip();
            if (name == null || name.isBlank()) throw new IllegalArgumentException("请提供 LoRA 名称（或 #编号）。");
            if (words.isEmpty()) throw new IllegalArgumentException("请至少给出一个触发词，例如：/lora triggers set \"我的 LoRA\" 词1, 词2。");
            return new String[]{name, words};
        }
        Matcher separated = Pattern.compile("^(\\S+)\\s+([\\s\\S]+)$").matcher(text);
        if (!separated.matches())
            throw new IllegalArgumentException("用法：/lora triggers set <名称|#编号> <词1, 词2>；名称含空格时用双引号，例如 "
                    + "/lora triggers set \"我的 LoRA\" 词1, 词2。");
        return new String[]{separated.group(1), separated.group(2).strip()};
    }
    /**
     * 展示图样式要记下的模型参数：底模按优先级识别（Civitai → Forge 元数据 → 预设推断），
     * 采样方法/调度器/步数/CFG/Shift/尺寸用当前 Forge 预设栈（没有预设就用机器人当前设置）。
     * 算不出来就返回 null（样式只存提示词，照旧可用）。
     */
    private JsonObject loraStyleModel(String name, Path file, String civitaiBaseModel) {
        try {
            SdClient.BaseModel base = sd.resolveBaseModel(civitaiBaseModel, name, file == null ? "" : file.toString());
            JsonObject model = sd.styleModelParams(base.name(), base.source());
            if (model.size() > 0) Log.info("展示图样式模型参数（" + name + "）：底模=" + (base.known() ? base.name() + "（" + base.sourceLabel() + "）" : "未识别"));
            return model.size() == 0 ? null : model;
        } catch (Exception error) {
            Log.warn("读取样式模型参数失败（展示图样式只存提示词）：" + error(error));
            return null;
        }
    }
    /**
     * 按**归属栈**分组（网页 LoRA 面板的组头用，例如 {@code Anima 栈（4）}）；判出栈的排前面，判不出的排最后。
     * 组里同时保留底模名——同一个栈可以有多个底模（Illustrious / NoobAI / Pony 都是 SDXL 栈）。
     */
    private static JsonArray loraGroups(JsonArray items) {
        Map<String, JsonObject> groups = new LinkedHashMap<>();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            String key = Json.str(item, "groupKey", "");      // 前端按 groupKey 分桶，这里就是栈键
            JsonObject group = groups.get(key);
            if (group == null) {
                group = new JsonObject();
                group.addProperty("key", key);
                group.addProperty("stack", key);
                group.addProperty("label", Json.str(item, "stackLabel", ""));
                group.addProperty("stackSource", Json.str(item, "stackSource", ""));
                group.addProperty("preset", Json.str(item, "preset", ""));
                group.addProperty("baseModel", Json.str(item, "baseModel", ""));
                group.addProperty("baseModelSource", Json.str(item, "baseModelSource", ""));
                group.add("baseModels", new JsonArray());
                group.add("names", new JsonArray());
                groups.put(key, group);
            }
            group.getAsJsonArray("names").add(Json.str(item, "name", ""));
            group.addProperty("count", group.getAsJsonArray("names").size());
            String base = Json.str(item, "baseModel", "");
            JsonArray bases = group.getAsJsonArray("baseModels");
            boolean seen = false;
            for (JsonElement existing : bases) if (existing.getAsString().equalsIgnoreCase(base)) { seen = true; break; }
            if (!base.isEmpty() && !seen) bases.add(base);
        }
        List<JsonObject> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingInt((JsonObject group) -> Json.str(group, "key", "").isEmpty() ? 1 : 0)
                .thenComparing(group -> Json.str(group, "stack", "").toLowerCase(Locale.ROOT)));
        JsonArray result = new JsonArray();
        for (JsonObject group : ordered) result.add(group);
        return result;
    }
    /**
     * 本机 LoRA 有没有展示图（{@code <模型名>.preview.png}）。控制台据此决定显示缩略图还是占位。
     * 名称来自 WebUI 的 LoRA 列表，可能是子目录相对名，所以这里必须把解析结果锁在 lora_dir 里。
     */
    private static boolean hasLoraPreview(String directory, String name) {
        return loraPreview(directory, name) != null;
    }
    /** 展示图文件本身（不存在返回 null）；越界、目录没配、名称空都按"没有"处理。 */
    private static Path loraPreview(String directory, String name) {
        if (directory == null || directory.isBlank() || name == null || name.isBlank()) return null;
        try {
            Path base = Path.of(directory).toAbsolutePath().normalize();
            Path file = base.resolve(name + ".preview.png").normalize();
            return file.startsWith(base) && Files.isRegularFile(file) ? file : null;
        } catch (Exception ignored) { return null; }
    }
    /** 控制台取本机 LoRA 展示图用：返回可读的绝对路径，其余一律 null。 */
    public Path loraPreviewFile(String name) {
        return loraPreview(Json.str(Json.obj(settings.snapshot(), "civitai"), "lora_dir", ""), name);
    }
    /**
     * 控制台取样式预览图（只给网页看）：必须是样式库里真的存在的样式，路径由名字哈希算出来，
     * 不存在、名字为空、样式已删都返回 null。
     */
    public Path stylePreviewFile(String name) {
        try {
            if (name == null || name.isBlank() || localStyles.get(name) == null) return null;
            Path file = cn.szu.bot.sd.StylePreviews.file(settings.root, name);
            return file != null && Files.isRegularFile(file) ? file : null;
        } catch (Exception ignored) { return null; }
    }
    /**
     * `/lora detail <名称|#编号>`：一个 LoRA 的底模（含来源）、文件位置、Civitai 记录与它的展示图样式。
     * 底模走和列表同一条优先级（Civitai 记录 → Forge 元数据 → 当前预设推断），识别不出来就如实说没有。
     */
    private String loraDetailText(String requested) throws Exception {
        List<SdClient.Lora> catalog = sd.loras();
        SdClient.Lora found = null;
        for (SdClient.Lora item : catalog) {
            String stem = item.path().replace('\\', '/');
            stem = stem.substring(stem.lastIndexOf('/') + 1).replaceFirst("(?i)\\.safetensors$", "");
            if (item.name().equalsIgnoreCase(requested) || item.alias().equalsIgnoreCase(requested) || stem.equalsIgnoreCase(requested)) {
                found = item; break;
            }
        }
        if (found == null) throw new IllegalArgumentException("WebUI 列表里没有「" + requested + "」；用 /lora list 核对名称（编号也行）。");
        String directory = Json.str(Json.obj(settings.snapshot(), "civitai"), "lora_dir", "");
        Path file = null;
        try { file = Path.of(found.path()); } catch (Exception ignored) { /* 列表里的路径不可用时退回扫目录 */ }
        if (file == null || !Files.isRegularFile(file)) file = localLoraFile(found.name());
        SdClient.BaseModel base = loraBaseModel(found.name(), found.path(), presetBaseModelOrNone());
        StringBuilder text = new StringBuilder("LoRA：" + found.name());
        if (!found.alias().isBlank() && !found.alias().equals(found.name())) text.append("\n别名：").append(found.alias());
        text.append("\n文件：").append(found.path());
        text.append("\n底模：").append(base.known() ? base.name() + base.note() : "未识别");
        if (base.known()) text.append("（来源：").append(base.sourceLabel()).append("）");
        // 归属栈：这是"能不能直接用"的关键——栈与当前预设不一致就得先 .model preset 切过去。
        if (base.stackKnown()) {
            String preset = "";
            try { preset = cn.szu.bot.sd.StackClassifier.presetFor(base.stack(), sd.forge() ? sd.forgePresets() : List.of()); } catch (Exception ignored) { /* 读不到预设就不提 */ }
            text.append("\n归属栈：").append(base.stackLabel())
                    .append(preset.isBlank() ? "（没有同名预设，在 Forge 页面切到 " + base.stack() + "）" : "（预设 " + preset + "）")
                    .append("；来源：").append(base.stackSourceLabel().isBlank() ? base.stackSource() : base.stackSourceLabel());
            if (!base.evidence().isBlank()) text.append("\n判据：").append(base.evidence());
        } else {
            text.append("\n归属栈：未识别（判不出就不猜：换底模时按 Forge 预设页面对照）");
        }
        JsonObject record = file == null ? null : readManifest(settings.root.resolve("data/civitai").resolve(file.getFileName() + ".json"));
        if (record == null) {
            text.append("\nCivitai 记录：无（不是用 /lora download 下的，或记录已删）");
        } else {
            String recorded = Json.str(record, "base_model", "");
            text.append("\nCivitai 记录：").append(Json.str(record, "model_name", "未命名"))
                    .append(" / ").append(Objects.requireNonNullElse(Json.str(record, "version_name", ""), "").isBlank() ? "未知版本" : Json.str(record, "version_name", ""))
                    .append("；记录里的底模=").append(recorded.isBlank() ? "未记录" : recorded);
        }
        // 触发词：先看本机触发词存储，再只读回落到下载记录——查询入口统一是 .lora triggers。
        LoraTriggers.Trigger trigger = loraTriggersOf(found.name(), file == null ? "" : file.toString());
        if (trigger == null) {
            text.append("\n触发词：没有记下（本地导入、或下载时 Civitai 没给；/lora triggers set 可手动补录）");
        } else if (trigger.count() == 0) {
            text.append("\n触发词：0 条（下载时 Civitai 没给；可 /lora triggers set 手动补录）");
        } else {
            text.append("\n触发词：").append(trigger.count()).append(" 条（.lora triggers ")
                    .append(quotedLoraName(trigger.loraName())).append(" 看全部）");
        }
        text.append("\n展示图：").append(hasLoraPreview(directory, found.name())
                ? found.name() + ".preview.png" : "无（/lora cover " + found.name() + " 可补抓）");
        String recordName = loraRecordName(file);
        List<String> styles = new ArrayList<>();
        if (!recordName.isBlank()) {
            String stylePrefix = CivitaiStyleSync.prefix(recordName) + " ";
            for (String style : localStyles.names()) if (style.startsWith(stylePrefix)) styles.add(style);
        }
        text.append("\n展示图样式：").append(styles.isEmpty() ? "无" : styles.size() + " 个（" + String.join("、", styles) + "）");
        return text.toString();
    }
    private record LoraSelection(String name, double weight) {}
    private static LoraSelection loraSelection(String value) {
        if (value.startsWith("\"")) {
            Matcher quoted = QUOTED_LORA.matcher(value);
            if (!quoted.matches()) throw new IllegalArgumentException("LoRA 名称引号格式不正确，例如 /lora load \"我的 LoRA 2\" 0.8");
            try {
                String name = STRICT_JSON.fromJson(quoted.group(1), String.class);
                if (name == null || name.isBlank()) throw new IllegalArgumentException("请提供 LoRA 的完整本地名称。");
                return new LoraSelection(name, quoted.group(2) == null ? 1.0 : loraWeight(quoted.group(2)));
            } catch (JsonParseException e) { throw new IllegalArgumentException("LoRA 名称须使用有效的双引号字符串。"); }
        }
        Matcher separated = Pattern.compile("^([\\s\\S]*\\S)\\s+(\\S+)$").matcher(value);
        if (separated.matches()) {
            String suffix = separated.group(2);
            try {
                Double.parseDouble(suffix);
                return new LoraSelection(separated.group(1).strip(), loraWeight(suffix));
            } catch (NumberFormatException ignored) { /* A nonnumeric suffix is part of the full name. */ }
        }
        return new LoraSelection(value, 1.0);
    }
    private static double loraWeight(String value) {
        final double weight;
        try { weight = Double.parseDouble(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("LoRA 权重必须是 0–2 的数字，默认 1。"); }
        if (!Double.isFinite(weight) || weight < 0 || weight > 2) throw new IllegalArgumentException("LoRA 权重必须在 0–2 之间。");
        return weight;
    }
    private String safeLoraText(String value) {
        String safe = Objects.requireNonNullElse(value, "正在处理 LoRA。");
        String environmentToken = System.getenv("CIVITAI_API_TOKEN");
        if (environmentToken != null && !environmentToken.isEmpty()) safe = safe.replace(environmentToken, "（凭据已隐藏）");
        JsonObject config = Json.obj(settings.snapshot(), "civitai");
        for (Map.Entry<String, JsonElement> entry : config.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if ((key.contains("token") || key.contains("key") || key.contains("password")) && entry.getValue().isJsonPrimitive()) {
                String secret = entry.getValue().getAsString();
                if (!secret.isEmpty()) safe = safe.replace(secret, "（凭据已隐藏）");
            }
        }
        return safe.replaceAll("(?i)https?://\\S+", "（链接已隐藏）")
                .replaceAll("(?i)(token|api[_-]?key|authorization)(\\s*[:=]\\s*)[^\\s,;&]+", "$1$2（凭据已隐藏）");
    }
    /**
     * Forge / Forge Neo 的预设：列出、切换，并**采纳**该预设自己的推荐参数。
     *
     * <p>Forge 把「底模 + VAE + 文本编码器」按预设分成一栈一栈，例如 {@code anima} 这一栈默认是
     * 「animaCatTower + ER SDE + beta + 32 步 + CFG 4 + 蒸馏 CFG（界面上的 Shift）3」。
     * 机器人出图时会把自己的采样方法/步数/CFG 发过去覆盖页面默认值，所以换预设之后必须把这些
     * 一起采纳过来，不然就会拿 SD1.5 的参数去跑 Anima。
     */
    private void forgePreset(JsonObject event, String wanted) throws Exception {
        if (!sd.forge())
            throw new IllegalStateException("当前 WebUI 不是 Forge／Forge Neo（没有 forge_preset 这套预设）；A1111 直接 /model list 换底模即可。");
        String active = sd.forgePreset();
        if (wanted.isBlank()) {
            StringBuilder text = new StringBuilder("Forge 预设（当前：" + active + "）：");
            for (String name : sd.forgePresets()) {
                JsonObject defaults = sd.forgePresetDefaults(name);
                text.append("\n").append(name.equalsIgnoreCase(active) ? "▶ " : "  ").append(name)
                        .append(" ← ").append(Json.str(defaults, "checkpoint", "（该栈还没有检查点）"))
                        .append("\n      ").append(Json.str(defaults, "sampler", "?"))
                        .append(" / ").append(Json.str(defaults, "scheduler", "?"))
                        .append(" / ").append(Json.num(defaults, "steps", 0)).append(" 步")
                        .append(" / CFG ").append(trimNumber(Json.decimal(defaults, "cfg", 0)));
                double distilled = Json.decimal(defaults, "distilledCfg", 0);
                if (distilled > 0) text.append(" / 蒸馏 CFG ").append(trimNumber(distilled));
                int width = Json.num(defaults, "width", 0), height = Json.num(defaults, "height", 0);
                if (width > 0 && height > 0) text.append(" / ").append(width).append("×").append(height);
                if (defaults.has("modules") && defaults.getAsJsonArray("modules").size() > 0)
                    text.append(" / 模块 ").append(defaults.getAsJsonArray("modules").size()).append(" 个");
            }
            text.append("\n切换：.model preset <名字>（会把该预设的采样方法、调度器、尺寸、步数、CFG 采纳成机器人设置）");
            reply(event, text.toString());
            return;
        }
        JsonObject defaults = sd.setForgePreset(wanted);
        String preset = Json.str(defaults, "preset", wanted);
        List<String> applied = adoptForgePreset(defaults);
        String checkpoint = Json.str(defaults, "checkpoint", "");
        StringBuilder text = new StringBuilder("已切到 Forge 预设：" + preset);
        if (!checkpoint.isBlank()) text.append("\n底模：").append(checkpoint).append("（预设自带的那一个）");
        JsonArray modules = defaults.has("modules") ? defaults.getAsJsonArray("modules") : new JsonArray();
        text.append("\n该预设的额外模块：").append(modules.size() == 0 ? "（空）" : modules.toString());
        if (modules.size() == 0 && preset.equalsIgnoreCase("anima")) {
            text.append("\n⚠ Anima 通常要 qwen_image_vae.safetensors 与 qwen_3_06b_base.safetensors"
                    + "（在 Forge 的「VAE / Text Encoder」里选，或让它自动挑）；这两个文件本机已经有了。");
        }
        text.append(applied.isEmpty() ? "" : "\n已采纳：" + String.join("、", applied));
        reply(event, text.toString());
    }

    /**
     * 把某个 Forge 预设自带的参数**采纳**成机器人设置：切预设之后必须做，
     * 否则出图时会拿着上一栈的采样方法/尺寸/步数/CFG 去跑新模型（Anima 会被 SD1.5 的参数带偏）。
     * 命令（{@code .model preset <名字>}）与网页控制台共用这一条路，返回「实际采纳了什么」。
     */
    private List<String> adoptForgePreset(JsonObject defaults) {
        List<String> applied = new ArrayList<>();
        String sampler = Json.str(defaults, "sampler", "");
        if (!sampler.isBlank()) {
            try { sd.setSampler(sampler); applied.add("采样方法 " + sampler); }
            catch (Exception error) { applied.add("采样方法 " + sampler + "（该模型不可用，保留原值：" + error(error) + "）"); }
        }
        String scheduler = Json.str(defaults, "scheduler", "");
        double distilled = Json.decimal(defaults, "distilledCfg", 0);
        if (!scheduler.isBlank() || distilled > 0) {
            try {
                sd.setForgeExtras(scheduler, distilled);
                applied.add("调度器 " + (scheduler.isBlank() ? "（不变）" : scheduler)
                        + (distilled > 0 ? "，蒸馏 CFG " + trimNumber(distilled) : ""));
            } catch (Exception error) { applied.add("调度器/蒸馏 CFG 未能写入：" + error(error)); }
        }
        int width = Json.num(defaults, "width", 0), height = Json.num(defaults, "height", 0);
        if (width > 0 && height > 0) {
            try { sd.setSize(width, height); applied.add("尺寸 " + width + "×" + height); }
            catch (Exception error) { applied.add("尺寸 " + width + "×" + height + " 未被接受：" + error(error)); }
        }
        int steps = Json.num(defaults, "steps", 0);
        if (steps > 0) { try { sd.setParameter("steps", String.valueOf(steps)); applied.add("步数 " + steps); } catch (Exception error) { applied.add("步数未能写入：" + error(error)); } }
        double cfg = Json.decimal(defaults, "cfg", 0);
        if (cfg > 0) { try { sd.setParameter("cfg", trimNumber(cfg)); applied.add("CFG " + trimNumber(cfg)); } catch (Exception error) { applied.add("CFG 未能写入：" + error(error)); } }
        // 预设与底模是一栈的：把机器人固定的底模也对齐到这一栈（没有就改成"跟随"），
        // 否则出图时会用 override_settings 把上一个预设的检查点塞回来，盖掉刚切的预设。
        String checkpoint = Json.str(defaults, "checkpoint", "");
        try {
            if (!checkpoint.isBlank()) { sd.setParameter("model", checkpoint); applied.add("底模 " + checkpoint); }
            else { sd.setParameter("model", "auto"); applied.add("底模改为跟随 WebUI（该预设栈里没有检查点）"); }
        } catch (Exception error) {
            try { sd.setParameter("model", "auto"); } catch (Exception ignored) { /* 连跟随都写不进去就只能保留原值 */ }
            applied.add("底模与预设名字对不上（" + error(error) + "），已改为跟随 WebUI 当前模型");
        }
        return applied;
    }

    /**
     * Forge／Forge Neo 的防呆：**只换底模不换预设栈**会出全灰废图（实测 SDXL 底模配 anima 栈 → 2 KB 灰图；
     * 同一台机器上用 anima 栈自己的底模 + ER SDE + beta + Shift 3 → 671 KB 正常图）。
     *
     * <p>判据是**这个底模自己的归属栈**（safetensors 头部元数据 / 张量结构 / Civitai 记录 / Forge 元数据 /
     * Forge 预设配置，见 {@link cn.szu.bot.sd.StackClassifier}）：与当前预设不是同一栈时，给出明确的切换命令。
     * 判不出栈时退回原来的通用警告（按各预设的检查点名字比对）——**绝不硬说一个栈出来**。
     *
     * @return 一句警告（不属于当前栈时），或空串（正常 / 不是 Forge / 跟随当前模型）
     */
    private String forgeStackWarning(String checkpoint) {
        try {
            if (!sd.forge()) return "";
            String requested = checkpoint == null ? "" : checkpoint.strip();
            if (requested.isEmpty() || SdClient.isAutoModel(requested)) return "";
            String active = sd.forgePreset();
            // 先把"这个底模属于哪一栈"判出来（栈是从文件本身/记录里读的，不是按名字对齐到预设）。
            SdClient.ModelInfo info = sd.checkpointInfo(requested);
            if (info != null && info.stackKnown()) {
                if (cn.szu.bot.sd.StackClassifier.matchesPreset(info.stack(), active)) return "";
                String preset = info.preset() == null || info.preset().isBlank() ? info.stack() : info.preset();
                return "\n⚠ 「" + requested + "」属于" + info.stackLabel() + "（预设 " + preset + "），当前是 "
                        + (active.isBlank() ? "未知" : active) + " 栈 —— 直接换会出全灰废图，请用 .model preset "
                        + preset + " 或在 Forge 页面切到 " + preset + "。";
            }
            // 栈判不出来：保留原来的通用警告（拿各预设自带的检查点名字比对）。
            for (String preset : sd.forgePresets()) {
                String stackCheckpoint = Json.str(sd.forgePresetDefaults(preset), "checkpoint", "");
                if (stackCheckpoint.isBlank()) continue;
                String base = stackCheckpoint.replaceAll("(?i)\\.safetensors$", "").toLowerCase(Locale.ROOT);
                String wanted = requested.toLowerCase(Locale.ROOT);
                boolean same = wanted.equalsIgnoreCase(stackCheckpoint) || wanted.startsWith(base);
                if (!same) continue;
                if (preset.equalsIgnoreCase(active)) return "";
                return "\n⚠ 这个底模属于 Forge 预设「" + preset + "」，当前栈是「" + active
                        + "」：只换底模不换栈，出的是全灰废图。请改用 .model preset " + preset
                        + "，或先在 Forge 页面把 UI Preset 切到 " + preset + "。";
            }
            return "\n⚠ 这个底模不在任何 Forge 预设栈里（当前栈「" + active
                    + "」）：栈里的 VAE / 文本编码器对不上时出的是全灰废图。建议用 .model preset <名字> 切换，"
                    + "或在 Forge 页面选好预设再出图。";
        } catch (Exception error) { return ""; }
    }

    /** 当前模型参数快照；读不到（SD 没跑）返回 null——存样式时不该因为 SD 离线就失败。 */
    private JsonObject modelParamsOrNull() {
        try { return sd.modelParams(); }
        catch (Exception error) { Log.warn("读取 SD 模型参数失败（样式只存提示词）：" + error(error)); return null; }
    }

    /**
     * 载入样式时切 Forge 预设：**复用 {@code .model preset} 那条路**——{@link SdClient#setForgePreset}
     * 切栈（必要时连检查点与额外模块一起），再 {@link #adoptForgePreset} 把该栈的推荐参数采纳成机器人设置。
     *
     * <p>只把「实际采纳了什么」交回给 {@link SdClient#applyModelParams}：它会把这一行写进回执，
     * 然后继续用样式自己记着的参数逐项覆盖（样式记的值优先）。这里不发 QQ 回执、不改样式。
     */
    private List<String> switchPresetForStyle(String preset) throws Exception {
        return adoptForgePreset(sd.setForgePreset(preset));
    }

    /** 网页「Forge 预设」卡片：当前预设 + 每个预设的底模与推荐参数（不是 Forge 就如实说）。 */
    public JsonObject webForgePresets() throws Exception {
        JsonObject result = new JsonObject();
        boolean forge = sd.forge();
        result.addProperty("forge", forge);
        result.addProperty("active", forge ? sd.forgePreset() : "");
        result.addProperty("model", sd.parameters().checkpoint());
        JsonArray presets = new JsonArray();
        if (forge) for (String name : sd.forgePresets()) presets.add(sd.forgePresetDefaults(name));
        result.add("presets", presets);
        return result;
    }

    /** 网页切换 Forge 预设：切栈 + 采纳该栈参数（与 {@code .model preset <名字>} 同一条路）。 */
    public JsonObject webSetForgePreset(String name) throws Exception {
        String wanted = name == null ? "" : name.strip();
        if (wanted.isEmpty()) throw new IllegalArgumentException("请先选择一个预设。");
        if (wanted.equalsIgnoreCase("auto") || wanted.equals("跟随")) {
            sd.setParameter("model", "auto");
            JsonObject followed = webForgePresets();
            followed.addProperty("notice", "底模已改为跟随 WebUI 当前模型（预设仍以 Forge 页面里选的那个为准）。");
            return followed;
        }
        JsonObject defaults = sd.setForgePreset(wanted);
        List<String> applied = adoptForgePreset(defaults);
        JsonObject result = webForgePresets();
        JsonArray done = new JsonArray();
        for (String item : applied) done.add(item);
        result.add("applied", done);
        result.addProperty("notice", "已切到 Forge 预设「" + Json.str(defaults, "preset", wanted) + "」"
                + (applied.isEmpty() ? "。" : "：" + String.join("；", applied) + "。"));
        return result;
    }

    /** 3.0 显示成 "3"，3.5 保持 "3.5"（回执里别出现 4.0 这种零头）。 */
    private static String trimNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    // --------------------------------------------------------------- VAE：可选设置 + 冲突防呆 + 灰图补救

    /**
     * VAE 相关的 SD 操作。生产环境直接转发给 {@link SdClient}（{@link #sdVaeSupport}）；{@code SdClient}
     * 是 final 的——测试没法用子类打桩——所以这里留一个注入点（{@link #useVaeSupport}），让 `.vae` 的判定、
     * 出图前防呆与出图后灰图补救能在"假 SD 桩"上完整跑一遍。
     */
    interface VaeSupport {
        boolean forge() throws Exception;
        String forgePreset() throws Exception;
        String vae() throws Exception;
        List<String> vaeList() throws Exception;
        List<String> presetModules(String preset) throws Exception;
        JsonObject vaeSnapshot() throws Exception;
        SdClient.GenerationSettings setVae(String name) throws Exception;
        JsonObject setForgePreset(String preset) throws Exception;
    }

    static VaeSupport sdVaeSupport(SdClient sd) {
        return new VaeSupport() {
            @Override public boolean forge() { return sd.forge(); }
            @Override public String forgePreset() throws Exception { return sd.forgePreset(); }
            @Override public String vae() throws Exception { return sd.vae(); }
            @Override public List<String> vaeList() throws Exception { return sd.vaeList(); }
            @Override public List<String> presetModules(String preset) throws Exception { return sd.presetModules(preset); }
            @Override public JsonObject vaeSnapshot() throws Exception { return sd.vaeSnapshot(); }
            @Override public SdClient.GenerationSettings setVae(String name) throws Exception { return sd.setVae(name); }
            @Override public JsonObject setForgePreset(String preset) throws Exception { return sd.setForgePreset(preset); }
        };
    }

    /** 测试注入点（包内可见）：换成假 SD 桩，不改 SdClient。 */
    void useVaeSupport(VaeSupport support) { this.vae = support; }

    /** `.vae` 的用法（错误提示与状态回执共用一份说法）。 */
    static final String VAE_USAGE = "用法：.vae（查看状态）、.vae list（全部可用 VAE）、.vae set <名字>、"
            + ".vae auto（跟随当前模型）、.vae none（不用额外 VAE）、.vae check（只检测冲突，列出修复动作）、"
            + ".vae fix（清掉冲突的额外模块并把 VAE 设回 Automatic）";
    /** 状态里最多直接列几个 VAE 名字（太长只列前若干个 + "共 N 个"）。 */
    static final int VAE_LIST_SHOWN = 8;
    /**
     * 灰图判定的双条件：像素亮度标准差 ≈ 0 **且** 颜色数极少。
     *
     * <p>实测（本次事故）：纯灰废图标准差 0.00、只有 1 种颜色、11 KB；修复后正常图标准差 10.40、318 色。
     * 只满足一条不判废——"大面积纯色背景但画面正常"的图有渐变/噪点/细节，标准差远大于 0.5。
     */
    static final double BLANK_DEVIATION = 0.5;
    static final int BLANK_COLORS = 4;
    /** 最小可判定尺寸（生成允许的最小边长是 64）：更小的图（测试桩、缩略图）判不出画面，直接放过。 */
    static final int BLANK_MIN_SIZE = 64;
    /** 抽样上限：最多 128×128 个点均匀覆盖整幅图，不为一张图扫全像素。 */
    static final int BLANK_SAMPLES = 128;

    /**
     * 这张图是不是"没画出来"的灰图/纯色废图。只抽样判断；读不出来（不是图片/文件坏了）按正常处理——
     * 这道防线只负责灰图，别的错误交给原有的失败路径。
     */
    static boolean blankImage(Path path) {
        if (path == null) return false;
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            return image != null && blankPixels(image);
        } catch (Exception error) {
            Log.warn("灰图自检读取失败（按正常图处理）：" + path.getFileName() + "：" + error(error));
            return false;
        }
    }

    /** 抽样判定：标准差 ≈ 0 且颜色数 ≤ {@link #BLANK_COLORS}。两个条件同时成立才算废图。 */
    static boolean blankPixels(BufferedImage image) {
        if (image == null) return false;
        int width = image.getWidth(), height = image.getHeight();
        if (width < BLANK_MIN_SIZE || height < BLANK_MIN_SIZE) return false;
        int stepX = Math.max(1, width / BLANK_SAMPLES), stepY = Math.max(1, height / BLANK_SAMPLES);
        long samples = 0;
        double sum = 0, squares = 0;
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < height; y += stepY)
            for (int x = 0; x < width; x += stepX) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                double luma = 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
                sum += luma; squares += luma * luma; samples++;
                if (colors.size() <= BLANK_COLORS) colors.add(rgb);
            }
        if (samples == 0) return false;
        double mean = sum / samples;
        double deviation = Math.sqrt(Math.max(0, squares / samples - mean * mean));
        return deviation <= BLANK_DEVIATION && colors.size() <= BLANK_COLORS;
    }

    /** 冲突等级；缺字段/读不出来都当 OK——防呆宁可少报，也不误拦正常生成。 */
    static String vaeLevel(JsonObject snapshot) {
        if (snapshot == null || !snapshot.has("conflict")) return "OK";
        String level = Json.str(Json.obj(snapshot, "conflict"), "level", "OK").strip().toUpperCase(Locale.ROOT);
        return level.isEmpty() ? "OK" : level;
    }

    static String vaeLevelLabel(String level) {
        return switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
            case "BLOCK" -> "冲突（BLOCK）——照这样出图会得到灰图，必须先修";
            case "WARN" -> "提醒（WARN）——能出图，但 VAE/额外模块可能不对";
            default -> "正常（OK）";
        };
    }

    /** 读不到 SD 实时选项时的一句说明（`reachable=false`）；读到了就是空串。 */
    static String vaeReachability(JsonObject snapshot) {
        if (snapshot == null || Json.bool(snapshot, "reachable", true)) return "";
        List<String> errors = jsonStrings(snapshot.get("errors"));
        return "\n（读不到 SD 的实时状态，以下按机器人记住的值给出"
                + (errors.isEmpty() ? "" : "：" + String.join("；", errors)) + "）";
    }

    /** 冲突判定的人话（等级 + 原因 + 建议 + 冲突的额外模块）。 */
    static String vaeConflictText(JsonObject snapshot) {
        if (snapshot == null || !snapshot.has("conflict"))
            return "冲突检测：读不到 SD 的当前状态（SD WebUI 没在跑或地址不对）";
        JsonObject conflict = Json.obj(snapshot, "conflict");
        StringBuilder text = new StringBuilder("冲突检测：").append(vaeLevelLabel(vaeLevel(snapshot)));
        String reason = Json.str(conflict, "reason", "");
        if (!reason.isBlank()) text.append("\n原因：").append(reason);
        List<String> culprits = jsonStrings(conflict.get("culprits"));
        if (!culprits.isEmpty()) text.append("\n冲突的额外模块：").append(String.join("、", culprits));
        String suggestion = Json.str(conflict, "suggestion", "");
        if (!suggestion.isBlank()) text.append("\n建议：").append(suggestion);
        return text.toString();
    }

    /** 用快照里的字段构一次 {@link VaeGuard} 输入（检查点 / sd_vae / forge_additional_modules）。 */
    static VaeGuard.Facts vaeFacts(JsonObject snapshot) {
        return new VaeGuard.Facts(Json.str(snapshot, "checkpoint", ""), Json.str(snapshot, "vae", ""),
                jsonStrings(snapshot.get("modules")));
    }

    /** 修复动作清单：判定逻辑归 {@code VaeGuard.repairPlan}（`.vae check` 列出来，`.vae fix` 照着做）。 */
    static List<String> vaePlan(JsonObject snapshot) {
        if (snapshot == null) return List.of();
        try { return VaeGuard.repairPlan(vaeFacts(snapshot)); }
        catch (Exception error) { Log.warn("生成修复方案失败：" + error(error)); return List.of(); }
    }

    private static List<String> jsonStrings(JsonElement element) {
        List<String> values = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return values;
        for (JsonElement item : element.getAsJsonArray())
            if (item != null && item.isJsonPrimitive()) values.add(item.getAsString());
        return values;
    }

    /** `auto` / `none` 这类写法（QQ 与网页）统一成快照里用的名字。 */
    static String vaeAlias(String name) {
        String value = name == null ? "" : name.strip();
        if (value.equalsIgnoreCase("auto") || value.equalsIgnoreCase("automatic")
                || value.equals("自动") || value.equals("跟随")) return "Automatic";
        if (value.equalsIgnoreCase("none") || value.equals("无") || value.equals("不用")) return "None";
        return value;
    }

    /**
     * 一次性 VAE 冲突快照；读不到（SD 没在跑 / 这个构建不支持）返回 null。
     * 调用方据此决定"不拦"，绝不因为读不到状态就把生成挡住。
     */
    private JsonObject vaeSnapshotOrNull() {
        try { return vae.vaeSnapshot(); }
        catch (Exception error) { Log.warn("读取 VAE 状态失败：" + error(error)); return null; }
    }

    /** 可用 VAE：优先用快照里的 choices，没有就问一次列表接口。 */
    private List<String> vaeChoices(JsonObject snapshot) {
        List<String> choices = snapshot == null ? List.of() : jsonStrings(snapshot.get("choices"));
        if (!choices.isEmpty()) return choices;
        try { return vae.vaeList(); }
        catch (Exception error) { Log.warn("读取 VAE 列表失败：" + error(error)); return List.of(); }
    }

    /** `.vae` 的全部子命令（QQ 侧；权限与 .sampler、.size 一致）。 */
    private void vaeCommand(JsonObject event, String arguments) throws Exception {
        String rest = arguments == null ? "" : arguments.strip();
        String action = rest.isEmpty() ? "status" : rest.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String value = rest.length() > action.length() ? rest.substring(action.length()).strip() : "";
        switch (action) {
            case "status", "状态", "查看" -> {
                requireNoValue(value, action);
                reply(event, vaeStatus(event));
            }
            case "list", "列表" -> {
                requireNoValue(value, action);
                List<String> names = vae.vaeList();
                reply(event, "可用 VAE（共 " + names.size() + " 个）：" + numbered(event, "vae", names)
                        + (names.isEmpty() ? "\n（读不到 VAE 列表：SD WebUI 没在跑或这个构建不支持；可用 .vae auto 或 .vae none）" : "")
                        + "\n用 .vae set <名字> 指定，.vae auto 跟随当前模型。");
            }
            case "set", "设置" -> {
                if (value.isBlank()) throw new IllegalArgumentException("用法：.vae set <名字>（.vae list 查看全部可用 VAE）。");
                vaeAssign(event, vaeAlias(value), "设为「" + value + "」");
            }
            case "auto", "跟随" -> {
                requireNoValue(value, action);
                vaeAssign(event, "Automatic", "设为 Automatic（跟随当前模型）");
            }
            case "none", "无" -> {
                requireNoValue(value, action);
                vaeAssign(event, "None", "设为 None（不使用额外 VAE）");
            }
            case "check", "检测" -> {
                requireNoValue(value, action);
                reply(event, vaeCheckText(vaeSnapshotOrNull()));
            }
            case "fix", "修复" -> {
                requireNoValue(value, action);
                reply(event, vaeFixText());
            }
            default -> throw new IllegalArgumentException(VAE_USAGE);
        }
    }

    private static void requireNoValue(String value, String action) {
        if (!value.isBlank()) throw new IllegalArgumentException(".vae " + action + " 不带参数。" + VAE_USAGE);
    }

    /** 设置 VAE 并**立刻回读校验**；非法名字把可用列表原样交回用户。 */
    private void vaeAssign(JsonObject event, String name, String headline) throws Exception {
        JsonObject snapshot = vaeApply(name);
        String level = vaeLevel(snapshot);
        StringBuilder text = new StringBuilder("VAE 已" + headline
                + (snapshot.has("vae") ? "，回读校验：" + Json.str(snapshot, "vae", name) : "（已保存，但读不到 SD 当前状态，出图前还会再检测一次）"));
        text.append("\n").append(vaeConflictText(snapshot));
        if (level.equals("BLOCK"))
            text.append("\n⚠ 这个组合现在会出灰图：出图前的防呆会**拒绝**这次生成，"
                    + "请先 .vae auto 或 .vae fix 把配置对齐。");
        else if (level.equals("WARN"))
            text.append("\n（只是提醒，不会拦住生成；不放心可以 .vae check 看修复动作）");
        text.append("\n后续提交的生成任务生效。");
        reply(event, text.toString());
    }

    /** 写入 VAE（返回回读到的快照 + 一句 message；QQ 与网页共用）。 */
    private JsonObject vaeApply(String name) throws Exception {
        String wanted = vaeAlias(name);
        if (wanted.isBlank()) throw new IllegalArgumentException("请先给出 VAE 名字。" + VAE_USAGE);
        try { vae.setVae(wanted); }
        catch (Exception error) { throw new IllegalArgumentException(Bot.error(error) + "\n" + VAE_USAGE); }
        JsonObject snapshot = vaeSnapshotOrNull();
        if (snapshot == null) snapshot = new JsonObject();
        snapshot.addProperty("message", "VAE 已设置为「" + Json.str(snapshot, "vae", wanted) + "」，后续生成任务生效。");
        return snapshot;
    }

    /** `.vae` 的状态回执：当前 VAE、是否 Automatic、冲突判定、可用列表（太长只列前若干个）。 */
    private String vaeStatus(JsonObject event) throws Exception {
        JsonObject snapshot = vaeSnapshotOrNull();
        if (snapshot == null)
            return "读不到 VAE 状态：SD WebUI 没有在跑或地址不对。等它跑起来再用 .vae 查看。\n" + VAE_USAGE;
        boolean auto = Json.bool(snapshot, "vaeAuto", false);
        String current = Json.str(snapshot, "vae", "");
        List<String> modules = jsonStrings(snapshot.get("modules"));
        StringBuilder text = new StringBuilder("VAE：" + (current.isBlank() ? "（读不到）" : current)
                + (auto ? "（Automatic＝跟随当前模型，推荐）" : "（手动指定）"));
        String checkpoint = Json.str(snapshot, "checkpoint", "");
        if (!checkpoint.isBlank()) text.append("\n底模：").append(checkpoint);
        text.append("\n额外模块（Forge）：").append(modules.isEmpty() ? "（无）" : String.join("、", modules));
        text.append(vaeReachability(snapshot));
        text.append("\n").append(vaeConflictText(snapshot));
        List<String> choices = vaeChoices(snapshot);
        if (!choices.isEmpty()) {
            int shown = Math.min(choices.size(), VAE_LIST_SHOWN);
            text.append("\n可用 VAE（共 ").append(choices.size()).append(" 个）：")
                    .append(String.join("、", choices.subList(0, shown)))
                    .append(choices.size() > shown ? " …（.vae list 看全部）" : "");
            numbered(event, "vae", choices);
        } else {
            text.append("\n可用 VAE：读不到列表（可用 .vae auto 或 .vae none）");
        }
        text.append("\n").append(VAE_USAGE);
        return text.toString();
    }

    /** `.vae check`：只做冲突检测，不改任何设置；修复动作来自 {@link VaeGuard#repairPlan}。 */
    private String vaeCheckText(JsonObject snapshot) {
        if (snapshot == null) return "检测不了：读不到 SD 的 VAE/额外模块状态（SD WebUI 没在跑或地址不对）。";
        StringBuilder text = new StringBuilder(vaeConflictText(snapshot));
        text.append(vaeReachability(snapshot));
        List<String> plan = vaePlan(snapshot);
        if (vaeLevel(snapshot).equals("OK"))
            text.append("\n修复动作：").append(plan.isEmpty() ? "无需修复。" : String.join("；", plan));
        else
            text.append("\n修复动作（.vae fix 会依次执行）：\n- ").append(String.join("\n- ", plan));
        return text.toString();
    }

    /** `.vae fix`：执行修复并逐条回报做了什么，最后回读说明现状。 */
    private String vaeFixText() {
        JsonObject before = vaeSnapshotOrNull();
        List<String> actions = vaeRepair();
        JsonObject after = vaeSnapshotOrNull();
        return "VAE 修复完成，实际做了：\n- " + String.join("\n- ", actions)
                + "\n" + vaeStateLine(after)
                + "\n" + vaeConflictText(after)
                + (before == null || vaeLevel(before).equals("OK") ? ""
                        : "\n（VaeGuard 修复方案：\n- " + String.join("\n- ", vaePlan(before)) + "）")
                + (after == null ? "" : "\n后续生成任务按修复后的配置出图。");
    }

    private static String vaeStateLine(JsonObject snapshot) {
        if (snapshot == null) return "修复后读不到 SD 的当前状态（SD WebUI 没在跑或地址不对）";
        List<String> modules = jsonStrings(snapshot.get("modules"));
        return "修复后：VAE=" + Json.str(snapshot, "vae", "（读不到）")
                + (Json.bool(snapshot, "vaeAuto", false) ? "（Automatic＝跟随当前模型）" : "（手动指定）")
                + "；额外模块=" + (modules.isEmpty() ? "无" : String.join("、", modules));
    }

    /**
     * 执行修复：清掉冲突的额外模块（**重新套用当前 Forge 预设**——模块清单随预设一起回到这一栈自己的
     * 配置，anima 残留的 qwen 模块就是这样清掉的）并回读校验，再把 VAE 设回 Automatic。
     *
     * @return 逐条人话的动作清单（含失败原因）；调用方直接写进回执
     */
    private List<String> vaeRepair() {
        List<String> actions = new ArrayList<>();
        JsonObject before = vaeSnapshotOrNull();
        List<String> modules = before == null ? List.of() : jsonStrings(before.get("modules"));
        boolean forge = false;
        String preset = "";
        try { forge = vae.forge(); } catch (Exception error) { Log.warn("判断是不是 Forge 失败：" + error(error)); }
        if (forge) {
            try { preset = vae.forgePreset(); }
            catch (Exception error) { Log.warn("读取当前 Forge 预设失败：" + error(error)); }
        }
        if (!modules.isEmpty() && !preset.isBlank()) {
            try {
                vae.setForgePreset(preset);
                List<String> expected = List.of();
                try { expected = vae.presetModules(preset); } catch (Exception error) { Log.warn("读取预设模块清单失败：" + error(error)); }
                JsonObject now = vaeSnapshotOrNull();
                List<String> left = now == null ? List.of() : jsonStrings(now.get("modules"));
                actions.add("已重新套用 Forge 预设「" + preset + "」，额外模块回到这一栈自己的清单"
                        + (expected.isEmpty() ? "（该预设不需要额外模块，残留模块已清空）" : "：" + String.join("、", expected)));
                if (!left.isEmpty() && !sameNames(left, expected))
                    actions.add("⚠ 回读发现额外模块仍有：" + String.join("、", left)
                            + "（这个 Forge 构建可能不接受 API 改模块，请在 Forge 页面「VAE / Text Encoder」里手工清空）");
            } catch (Exception error) {
                actions.add("重新套用 Forge 预设「" + preset + "」失败：" + error(error));
            }
        } else if (!modules.isEmpty()) {
            actions.add("⚠ 当前不是 Forge 或读不到预设名：没法用切预设清掉额外模块（" + String.join("、", modules)
                    + "），请在 SD 设置里手工清空 forge_additional_modules");
        }
        if (before == null || !Json.bool(before, "vaeAuto", false)) {
            try { vae.setVae("Automatic"); actions.add("已把 VAE 设回 Automatic（跟随当前模型）"); }
            catch (Exception error) { actions.add("设置 VAE 失败：" + error(error)); }
        } else {
            actions.add("VAE 本来就是 Automatic，无需改动");
        }
        if (actions.isEmpty()) actions.add("没有需要修复的项目（VAE 与额外模块和当前底模一致）");
        return actions;
    }

    private static boolean sameNames(List<String> left, List<String> right) {
        Set<String> wanted = new HashSet<>();
        for (String item : right) wanted.add(item.toLowerCase(Locale.ROOT));
        for (String item : left) if (!wanted.contains(item.toLowerCase(Locale.ROOT))) return false;
        return left.size() == right.size();
    }

    /**
     * **出图前的防呆**：生成任务开始时查一次 VAE/额外模块冲突。
     *
     * <ul>
     *   <li>BLOCK 且用户的 VAE 是 Automatic（说明是残留模块造成的）→ 自动修复，并明确告诉用户；</li>
     *   <li>BLOCK 但用户**显式指定**了那个冲突的 VAE → 返回拒绝理由（整条任务不再出图，绝不默默出灰图）；</li>
     *   <li>WARN → 只提醒一句，不拦；读不到状态 → 不拦（出图后还有灰图自检兜底）。</li>
     * </ul>
     *
     * @return 空串＝可以继续生成；非空＝拒绝这次生成的理由
     */
    private String vaePreflight(GenerationJob job) {
        JsonObject snapshot = vaeSnapshotOrNull();
        if (snapshot == null) return "";
        // 读不到 WebUI 的实时选项（SD 没在跑/地址不对）：不提醒也不拦——出图后还有灰图自检兜底。
        if (!Json.bool(snapshot, "reachable", true)) {
            Log.warn("生成任务 #" + job.number + " 读不到 VAE 实时状态，本次跳过出图前检查（出图后仍会自检灰图）");
            return "";
        }
        String level = vaeLevel(snapshot);
        if (level.equals("OK")) return "";
        JsonObject conflict = Json.obj(snapshot, "conflict");
        String reason = Json.str(conflict, "reason", "");
        String current = Json.str(snapshot, "vae", "");
        if (level.equals("WARN")) {
            Log.warn("生成任务 #" + job.number + " 出图前 VAE 提醒：" + reason);
            replyProcess(job.event, "⚠ VAE 提醒（不影响本次生成）：" + (reason.isBlank() ? vaeConflictText(snapshot) : reason)
                    + "\n要确认配置：.vae check");
            return "";
        }
        if (Json.bool(snapshot, "vaeAuto", false)) {
            List<String> actions = vaeRepair();
            Log.warn("生成任务 #" + job.number + " 出图前检测到 VAE/额外模块与当前模型冲突，已自动修复："
                    + String.join("；", actions));
            replyProcess(job.event, "检测到 VAE/额外模块与当前模型冲突，已自动修复：\n- " + String.join("\n- ", actions)
                    + "\n本次生成按修复后的配置继续。");
            return "";
        }
        // 用户显式指定的 VAE 自己就冲突：拒绝这次生成，并说清怎么改，绝不默默出灰图。
        String suggestion = Json.str(conflict, "suggestion", "");
        Log.warn("生成任务 #" + job.number + " 因 VAE 冲突被拒绝：VAE=" + current + "，" + reason);
        return "这次生成被拒绝：当前 VAE「" + current + "」与当前底模/额外模块冲突，"
                + (reason.isBlank() ? "照这样出图只会得到灰图。" : reason + "。")
                + "\n改法：.vae auto（跟随当前模型）或 .vae fix（清掉冲突的额外模块并把 VAE 设回 Automatic）"
                + (suggestion.isBlank() ? "" : "\n建议：" + suggestion)
                + "\n改完用 .vae check 确认，再重新发送 .gen。";
    }

    /** 灰图补救结果：可交付的图片 + 给用户的说明（空串＝一切正常，不必多话）。 */
    private record GrayCheck(List<Path> images, String notice, boolean failed) {}

    /**
     * **出图后的最后一道防线**：对每张新生成的图做灰图自检。判为废图就记一条 WARN、自动修复一次、
     * **只重试这一次**，成功就在回执里说明，仍失败如实报失败并附 `.vae check` 建议。
     * 这道防线独立于冲突检测：哪怕上面没识别出冲突，灰图也照抓。
     */
    private GrayCheck grayCheck(GenerationJob job, List<Path> generated) throws Exception {
        List<Path> blanks = generated.stream().filter(Bot::blankImage).toList();
        if (blanks.isEmpty()) return new GrayCheck(generated, "", false);
        List<Path> good = generated.stream().filter(path -> !blanks.contains(path)).toList();
        Log.warn("生成任务 #" + job.number + " 第 " + job.done.add(BigInteger.ONE) + "/" + job.total
                + " 次出图检测到灰图（纯色废图）" + blanks.size() + "/" + generated.size() + " 张："
                + blanks.stream().map(path -> path.getFileName().toString()).toList() + "，自动修复 VAE/额外模块后重试一次");
        List<String> actions = vaeRepair();
        List<Path> retried = sd.generate(job.snapshot, job.taskId);
        List<Path> stillBlank = retried.stream().filter(Bot::blankImage).toList();
        List<Path> recovered = new ArrayList<>(good);
        for (Path path : retried) if (!stillBlank.contains(path)) recovered.add(path);
        List<Path> discarded = new ArrayList<>(blanks);
        discarded.addAll(stillBlank);
        discarded.removeAll(recovered);
        if (!discarded.isEmpty()) discardImages(discarded);
        if (stillBlank.isEmpty()) {
            Log.info("生成任务 #" + job.number + " 灰图补救成功：重试 " + retried.size() + " 张全部正常（"
                    + String.join("；", actions) + "）");
            return new GrayCheck(recovered, "检测到灰图，已自动修复 VAE/额外模块并重试成功（" + String.join("；", actions) + "）。", false);
        }
        Log.warn("生成任务 #" + job.number + " 灰图补救失败：重试仍有 " + stillBlank.size() + " 张纯色废图");
        return new GrayCheck(recovered, "检测到灰图，已自动修复 VAE/额外模块，但重试仍有 " + stillBlank.size()
                + " 张是纯色废图，已丢弃。\n本次修复：" + String.join("；", actions)
                + "\n请用 .vae check 查看冲突（也检查 Forge 页面里的 UI 预设 / 底模 / 额外模块是否同一栈）。",
                recovered.isEmpty());
    }

    /**
     * 废图不再进待领取列表：自动领取与 .get 都不会把它发给用户，文件仍留在磁盘上便于事后排查。
     */
    private void discardImages(List<Path> images) {
        try {
            sd.acknowledgeImages(images);
            Log.warn("已丢弃 " + images.size() + " 张灰图（不再进入待领取列表）："
                    + images.stream().map(path -> path.getFileName().toString()).toList());
        } catch (Exception error) {
            Log.warn("丢弃灰图失败（这些图仍可能在待领取列表里）：" + error(error));
        }
    }

    /** 网页「VAE」卡：直接回 SdClient 的快照（当前 VAE、额外模块、底模、冲突判定、可用列表）。 */
    public JsonObject webVae() throws Exception { return vae.vaeSnapshot(); }

    /** 网页 VAE 下拉框的选项（第一个是 Automatic）。 */
    public JsonObject webVaeList() throws Exception {
        JsonObject result = new JsonObject();
        result.add("choices", Json.GSON.toJsonTree(vae.vaeList()));
        return result;
    }

    /** 网页设置 VAE：设置后回同一份快照 + 一句 message（auto/none 也认）。 */
    public JsonObject webVaeSet(String name) throws Exception { return vaeApply(name); }

    /** 网页一键修复：清掉冲突的额外模块 + 把 VAE 设回 Automatic，回同一份快照 + actions。 */
    public JsonObject webVaeFix() {
        List<String> actions = vaeRepair();
        JsonObject snapshot = vaeSnapshotOrNull();
        if (snapshot == null) snapshot = new JsonObject();
        JsonArray done = new JsonArray();
        for (String action : actions) done.add(action);
        snapshot.add("actions", done);
        snapshot.addProperty("message", "已执行修复：" + String.join("；", actions));
        return snapshot;
    }

    private void sdSettings(JsonObject event, String option, String arguments) throws Exception {
        arguments = selectedArguments(event, option, arguments);
        // `.vae` 与 /sampler、/size 这类生成参数同一权限：不做 owner/admin 限制。
        if (option.equals("vae")) { vaeCommand(event, arguments); return; }
        if (option.equals("function")) { function(event, arguments); return; }
        if (option.equals("preset")) { preset(event, arguments); return; }
        if (Set.of("steps", "cfg", "seed", "model").contains(option)) {
            if (arguments.isEmpty()) { reply(event, sd.parameters().describe() + "\n步数、CFG、种子使用机器人持久化参数。"); return; }
            if (option.equals("model") && arguments.equalsIgnoreCase("list")) {
                List<String> names = sd.models();
                // 方括号里是**归属栈**（从 safetensors 头部/记录/Forge 预设配置判出来的），换栈用 .model preset。
                reply(event, "WebUI 基础模型（方括号是它属于哪一栈，换栈用 .model preset <预设>）："
                        + numbered(event, "model", names, modelLabels(names))); return;
            }
            // Forge / Forge Neo：底模、VAE、文本编码器是按「预设」分栈的（anima / flux / xl …），
            // 切模型得连着预设一起看，否则会出现 preset=anima 却加载着 SDXL 这种错配。
            if (option.equals("model") && (arguments.equalsIgnoreCase("preset")
                    || arguments.toLowerCase(Locale.ROOT).startsWith("preset "))) {
                forgePreset(event, arguments.length() > 6 ? arguments.substring(6).strip() : "");
                return;
            }
            Matcher assignment = SET_VALUE.matcher(arguments);
            if (!assignment.matches() || assignment.group(1) == null || assignment.group(1).isBlank())
                throw new IllegalArgumentException("用法：/" + option + " set <值>");
            String assigned = assignment.group(1).strip();
            reply(event, "生成参数已保存，用于后续提交的任务：\n" + sd.setParameter(option, assigned).describe()
                    + (option.equals("model") ? forgeStackWarning(assigned) : ""));
            return;
        }
        String usage = switch (option) {
            case "settings" -> "用法：/settings";
            case "sampler" -> "用法：/sampler、/sampler list 或 /sampler set <完整名称>";
            case "style" -> "用法：/style、/style list、/style save <名称>、/style overwrite <名称>、"
                    + "/style import webui [overwrite]、/style prompt <名称|#编号>、/style load <名称|#编号> [nolora]、"
                    + "/style rename [overwrite] <名称|#编号|#起-#止> <新名称或前缀>、/style delete <名称|#编号|#起-#止>、"
                    + "/style category <名称|#编号|#起-#止> [分类名]";
            default -> "用法：/size 或 /size set <宽> <高>；宽高须为 64–2048 的整数且为 8 的倍数。";
        };
        if (arguments.isEmpty()) {
            SdClient.GenerationSettings current = sd.settings();
            String value = switch (option) {
                case "sampler" -> "当前采样方法：" + current.samplerName();
                case "style" -> styleStatus(event);
                case "size" -> "当前图片尺寸：" + dimensions(current);
                default -> formatSettings(current, promptScope(event));
            };
            reply(event, value + "\n来源：" + current.source()
                    + (option.equals("settings") ? "\n" + sd.parameters().describe() + "\n步数、CFG、种子使用机器人持久化参数。" : ""));
            return;
        }
        if (option.equals("settings")) throw new IllegalArgumentException(usage);
        if (arguments.equalsIgnoreCase("list") && (option.equals("sampler") || option.equals("style"))) {
            if (option.equals("style")) {
                // 样式只有一份：机器人自己的样式库。载入时直接套用到个人提示词，不依赖 WebUI。
                // 不再记录"上一次载入哪个样式"：样式是固定模板，提示词文本才是唯一事实。
                List<String> local = localStyles.names();
                // 编号列表里带出分类：`#12 名字 ［LoRA 附带］`——编号仍按列表顺序，编号与分类是两件事。
                List<String> labels = new ArrayList<>();
                for (String name : local) {
                    LocalStyles.Style style = localStyles.get(name);
                    labels.add(style == null ? name : name + " ［" + localStyles.categoryOf(style) + "］");
                }
                reply(event, "样式列表（共 " + local.size() + " 个）："
                        + numbered(event, option, local, labels)
                        + (local.isEmpty() ? "\n还没有样式：用 .style save <名称> 保存当前提示词；"
                            + "要把 WebUI 里已有的预设样式搬进来，用 .style import webui。" : "")
                        + categorySummary()
                        + "\n样式只属于机器人：.style load <名称|#编号> 用样式替换你个人的正反向 prompt（替换后 prompt 就是你自己的，"
                        + "再改 prompt 不会自动恢复成样式），与 WebUI 无关。"
                        + "\n改分类：.style category <名称|#编号|#起-#止> <分类名>；分类名给 - 恢复自动分类。");
                return;
            }
            reply(event, "WebUI 可用采样方法：" + numbered(event, option, sd.samplers()));
            return;
        }
        if (option.equals("style")) {
            Matcher content = STYLE_CONTENT.matcher(arguments);
            if (content.matches()) {
                String name = Objects.requireNonNullElse(content.group(2), "").strip();
                if (content.group(1).equalsIgnoreCase("load")) {
                    if (name.isEmpty()) throw new IllegalArgumentException("用法：/style load <名称|#编号> [nolora] [noparams]，用样式替换你个人的正向、反向 prompt；"
                            + "nolora 不加载样式里的 LoRA，noparams 不套用样式记着的模型参数（底模/采样/步数/CFG/尺寸）。");
                    // 可选修饰 nolora：只替换画面词条，样式里的 <lora:…> 不写进提示词。
                    boolean noLora = name.matches("(?is).*\\s(?:nolora|no-lora|不带lora|不要lora|不加载lora)$");
                    if (noLora) name = name.replaceFirst("(?is)\\s(?:nolora|no-lora|不带lora|不要lora|不加载lora)$", "").strip();
                    // 可选修饰 noparams：只要提示词，不把样式里记着的模型参数套回来。
                    boolean noParams = name.matches("(?is).*\\s(?:noparams|no-params|不带参数|不要参数|不套参数)$");
                    if (noParams) name = name.replaceFirst("(?is)\\s(?:noparams|no-params|不带参数|不要参数|不套参数)$", "").strip();
                    // 修 bug 1（对齐 TS 版修过的"答应了不做"那条）：/style load 必须和 /style save、/style rename
                    // 一样先 stripQuotes。/.char apply 生成的是 .style load "样式名"（含空格的名称整段传入），
                    // 以前引号被当成名字的一部分 → 报「没有这个样式："样式名"」，"应用基底 → .infix → .gen"
                    // 整条链在第 1 步就死。名称里的引号去掉后再查库。
                    name = stripQuotes(name);
                    // 编号也要认：.style load #3 与 .style load "#3" 都按样式列表的实时编号解析成真实名称
                    // （与 .lora load #N 的行为一致）。
                    if (name.startsWith("#")) name = select(event, "style", name);
                    String scope = promptScope(event);
                    SdClient.Prompts previous = effectivePrompts(scope);
                    LocalStyles.Style local = localStyles.get(name);
                    if (local == null) throw new IllegalArgumentException("没有这个样式：" + name
                            + "。用 .style list 查看全部样式（编号也可用于 .style load #编号）。");
                    String canonical = local.name();
                    // 样式里可能带 WebUI 的 {prompt} 占位符（导入时保留原样），载入时按 WebUI 的语义换成空。
                    String positive = SdClient.styleText(local.positive());
                    String negative = SdClient.styleText(local.negative());
                    List<String> skipped = List.of();
                    if (noLora) {
                        skipped = loraTagsIn(positive);
                        positive = removeTerms(positive, skipped);
                    }
                    SdClient.Prompts composed = new SdClient.Prompts(positive, negative, UserPromptStore.PERSONAL_SOURCE);
                    SdClient.Prompts updated = userPrompts.replace(scope, composed);
                    new PromptFunctions(settings.root).reset(scope);
                    // 样式记着模型参数就一并套回来（Anima 这类一栈一栈的模型，光换提示词会出废图）。
                    // 顺序由 applyModelParams 定：先按样式里的 forge_preset 切预设（复用 .model preset 那条路），
                    // 再写底模/采样/调度器/步数/CFG/尺寸——栈不对时写别的栈的检查点会出全灰废图。
                    List<String> params = List.of();
                    if (!noParams && local.hasModel()) params = sd.applyModelParams(local.model(), this::switchPresetForStyle);
                    reply(event, "已用样式「" + canonical + "」替换你个人的正向、反向 prompt，本次变化：\n"
                            + formatPromptDiff(previous, updated)
                            + (skipped.isEmpty() ? "" : "\n（已按 nolora 跳过样式里的 " + String.join("、", skipped) + "）")
                            + (local.hasModel()
                                    ? (noParams ? "\n（样式带有模型参数：" + local.modelSummary() + "；本次按 noparams 没有套用）"
                                                : "\n已套用样式的模型参数：" + (params.isEmpty() ? "（没有可用项）" : String.join("、", params)))
                                    : "")
                            + styleStackNotice(local)
                            + "\n分类：" + localStyles.categoryOf(local) + (local.hasCategory() ? "（手动设置）" : "（按规则自动）")
                            + "\n（替换后的 prompt 就是你自己的文本，之后改 prompt 不会再被样式覆盖；用 .prompt 查看完整提示词）");
                } else {
                    if (name.isEmpty()) throw new IllegalArgumentException("用法：/style prompt <名称|#编号>，查看样式原文。");
                    // 修 bug 1：（与 `.style load` 同一处）网页/角色链会带引号传名称，编号也要能解析。
                    name = stripQuotes(name);
                    if (name.startsWith("#")) name = select(event, "style", name);
                    List<String> values = new ArrayList<>();
                    LocalStyles.Style local = localStyles.get(name);
                    if (local == null) throw new IllegalArgumentException("没有这个样式：" + name + "。用 .style list 查看全部样式。");
                    values.add("样式：" + local.name()
                            + "\n分类：" + localStyles.categoryOf(local) + (local.hasCategory() ? "（手动设置）" : "（按规则自动）")
                            + (local.hasModel() ? "\n模型参数：" + local.modelSummary() : "")
                            + "\n正向 prompt 原文：\n" + display(local.positive())
                            + "\n反向 prompt 原文：\n" + display(local.negative()));
                    reply(event, String.join("\n\n", values));
                }
                return;
            }
            Matcher category = Pattern.compile("^category(?:\\s+([\\s\\S]*))?$", Pattern.CASE_INSENSITIVE).matcher(arguments);
            if (category.matches()) {
                String rest = Objects.requireNonNullElse(category.group(1), "").strip();
                if (rest.isEmpty())
                    throw new IllegalArgumentException("用法：/style category <名称|#编号|#起-#止> [分类名]；只给名称时查看当前分类，"
                            + "分类名给 - 恢复自动分类（LoRA 附带样式自动按所属 LoRA 分组：一个 LoRA 一个分类，"
                            + "认不出具体 LoRA 的才归到「" + LocalStyles.LORA_CATEGORY + "」）。");
                // 名称可能含空格：整段恰好是一条已存在的样式名时按"只看不改"处理，否则按"目标 + 分类名"拆开。
                String spec = rest;
                String value = null;
                if (localStyles.get(stripQuotes(rest)) == null) {
                    Matcher split = Pattern.compile("^(\\S+)\\s+([\\s\\S]+)$").matcher(rest);
                    if (split.matches()) {
                        spec = split.group(1);
                        value = stripQuotes(split.group(2).strip());
                    }
                }
                List<String> targets = styleTargets(event, spec);
                if (value == null) {
                    List<String> lines = new ArrayList<>();
                    for (String target : targets) {
                        LocalStyles.Style style = localStyles.get(target);
                        if (style == null) { lines.add("没有这个样式：" + target); continue; }
                        lines.add(style.name() + "：" + localStyles.categoryOf(style)
                                + (style.hasCategory() ? "（手动设置）" : "（按规则自动）"));
                    }
                    reply(event, "样式分类：\n" + String.join("\n", lines)
                            + "\n改分类：.style category <名称|#编号|#起-#止> <分类名>；分类名给 - 恢复自动分类。");
                    return;
                }
                boolean clear = value.equals("-") || value.equals("清除") || value.equals("清空");
                List<String> done = new ArrayList<>(), failed = new ArrayList<>();
                for (String target : targets) {
                    try {
                        LocalStyles.Style updated = localStyles.setCategory(target, clear ? "" : value);
                        done.add(updated.name() + " → " + localStyles.categoryOf(updated) + (updated.hasCategory() ? "" : "（按规则自动）"));
                    } catch (Exception error) { failed.add(target + "（" + error(error) + "）"); }
                }
                reply(event, reportBatch("样式分类", done, failed, List.of())
                        + (clear ? "\n已清空手动分类：这些样式回到自动规则（LoRA 附带样式一个 LoRA 一个分类 → 归属栈 → "
                            + LocalStyles.OTHER_CATEGORY + "）。" : "")
                        + "\n网页「样式」页按分类分组，也可以在那里改。");
                return;
            }
            Matcher rename = Pattern.compile("^rename(?:\\s+(overwrite))?\\s+(\\S+)\\s+([\\s\\S]+)$", Pattern.CASE_INSENSITIVE).matcher(arguments);
            if (rename.matches()) {
                boolean overwrite = rename.group(1) != null;
                List<String> targets = styleTargets(event, rename.group(2));
                String base = stripQuotes(rename.group(3).strip());
                if (base.isBlank()) throw new IllegalArgumentException("用法：/style rename [overwrite] <名称|#编号|#起-#止> <新名称或前缀>");
                List<String> destinations = new ArrayList<>();
                for (int index = 0; index < targets.size(); index++)
                    destinations.add(targets.size() == 1 ? base : base + " " + (index + 1));
                List<String> done = new ArrayList<>(), failed = new ArrayList<>(), skipped = new ArrayList<>();
                List<String> existing = localStyles.names();
                for (int index = 0; index < targets.size(); index++) {
                    String source = targets.get(index), destination = destinations.get(index);
                    if (source.equals(destination)) { skipped.add(source + "（名称相同）"); continue; }
                    if (!overwrite && containsIgnoreCase(existing, destination)) { skipped.add(source + " → " + destination + "（目标已存在）"); continue; }
                    try {
                        LocalStyles.Style renamed = localStyles.rename(source, destination, overwrite);
                        done.add(source + " → " + renamed.name());
                    } catch (Exception error) { failed.add(source + "（" + error(error) + "）"); }
                }
                reply(event, reportBatch("样式改名", done, failed, skipped));
                return;
            }
            Matcher remove = Pattern.compile("^delete\\s+([\\s\\S]+)$", Pattern.CASE_INSENSITIVE).matcher(arguments);
            if (remove.matches()) {
                List<String> targets = styleTargets(event, remove.group(1));
                List<String> done = new ArrayList<>(), failed = new ArrayList<>();
                for (String target : targets) {
                    try {
                        LocalStyles.Style removed = localStyles.delete(target);
                        done.add(removed.name());
                    } catch (Exception error) { failed.add(target + "（" + error(error) + "）"); }
                }
                reply(event, reportBatch("样式删除", done, failed, List.of()));
                return;
            }
            Matcher imported = Pattern.compile("^import(?:\\s+(webui|覆盖|overwrite))*\\s*$", Pattern.CASE_INSENSITIVE).matcher(arguments);
            if (imported.matches()) {
                boolean overwrite = arguments.matches("(?is).*(overwrite|覆盖).*");
                List<SdClient.StylePrompt> presets = sd.stylePrompts();
                List<LocalStyles.Style> incoming = new ArrayList<>();
                for (SdClient.StylePrompt preset : presets)
                    incoming.add(new LocalStyles.Style(preset.name(),
                            SdClient.styleText(preset.positive()), SdClient.styleText(preset.negative()), ""));
                LocalStyles.ImportResult result = localStyles.importAll(incoming, overwrite);
                reply(event, "已把 WebUI 的预设样式搬进机器人样式库：导入 " + result.imported() + " 个"
                        + (result.skipped() > 0 ? "，跳过同名 " + result.skipped() + " 个（要覆盖加 overwrite）" : "")
                        + "\n现在共 " + localStyles.count() + " 个样式；之后机器人不再读 WebUI 的样式列表，"
                        + ".style list 与 .style load 都只用这份库。"
                        + (result.skipped() > 0 ? "\n（覆盖写法：.style import webui overwrite）" : ""));
                return;
            }
            Matcher save = SAVE_STYLE.matcher(arguments);
            if (save.matches()) {
                if (save.group(2) == null || save.group(2).isBlank()) throw new IllegalArgumentException(usage);
                String raw = save.group(2).strip();
                // 样式默认连 LoRA 标签一起存（这样载入样式就能完整复现出图效果）；
                // 旧写法 withlora 仍然接受但已经没有区别，留作兼容。
                String requested = raw.replaceFirst("(?is)\\s(?:withlora|\\+lora|带lora|包括lora|含lora)$", "").strip();
                // An unusable name must fail before anything else happens.
                requested = SdClient.styleSaveName(stripQuotes(requested));
                // 保存的是调用者自己的提示词（他真正出图用的那一份），LoRA 标签一并保留。
                // WebUI 读不到（离线/故障）时退回已有的个人提示词：本机样式库不该被别的程序拖住。
                String scope = promptScope(event);
                SdClient.Prompts current;
                boolean inherited = false;
                try { current = effectivePrompts(scope); }
                catch (Exception unavailable) { current = userPrompts.prompts(scope); inherited = true; }
                List<String> lora = loraTagsIn(current.positive());
                // 连同**当前模型参数**一起记下来：载入样式时把底模/采样/步数/CFG/尺寸一并套回去。
                JsonObject modelParams = modelParamsOrNull();
                LocalStyles.Style saved = localStyles.save(requested, current.positive(), current.negative(),
                        save.group(1).equalsIgnoreCase("overwrite"), modelParams);
                reply(event, "样式已" + (save.group(1).equalsIgnoreCase("overwrite") ? "覆盖保存" : "保存") + "：" + saved.name()
                        + "\n提示词来源：你个人的 prompt（只存在机器人这边，与 WebUI 无关）"
                        + (inherited ? "\n（WebUI 页面提示词读取失败，本次用你已有的个人提示词保存）" : "")
                        + (saved.hasModel() ? "\n已记下模型参数：" + saved.modelSummary() + "（载入时会一起套用；只要提示词就加 noparams）"
                                            : "\n（这次没读到 SD 的模型参数，样式只存提示词）")
                        + (lora.isEmpty() ? "" : "\n已连同 " + lora.size() + " 个 LoRA 标签一起保存；载入时不想加载它们就加 nolora：.style load " + saved.name() + " nolora")
                        + "\n使用 .style load " + saved.name() + " 直接替换当前提示词。");
                return;
            }
        }
        SdClient.GenerationSettings updated;
        {
            if (option.equals("style")) throw new IllegalArgumentException(usage);
            Matcher assignment = SET_VALUE.matcher(arguments);
            if (!assignment.matches() || assignment.group(1) == null || assignment.group(1).isBlank())
                throw new IllegalArgumentException(usage);
            String value = assignment.group(1).strip();
            updated = switch (option) {
                case "sampler" -> sd.setSampler(value);
                default -> {
                    String[] size = value.split("\\s+", -1);
                    Matcher joined = Pattern.compile("^([+-]?\\d+)\\s*[xX×]\\s*([+-]?\\d+)$").matcher(value);
                    if (joined.matches()) size = new String[]{joined.group(1), joined.group(2)};
                    if (size.length != 2) throw new IllegalArgumentException(usage);
                    int width, height;
                    try { width = Integer.parseInt(size[0]); height = Integer.parseInt(size[1]); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("宽高必须填写整数。" + usage); }
                    yield sd.setSize(width, height);
                }
            };
        }
        reply(event, "生成参数已更新并保存：\n" + formatSettings(updated, promptScope(event)) + "\n来源：" + updated.source()
                + (option.equals("style") ? "\n使用 /style load <名称> 替换提示词框内容。" : ""));
    }
    /**
     * ".style" 的查看结果：机器人这边的样式库（唯一来源）。
     * 不再报告"当前载入的样式"：样式是固定模板，载入后提示词就是用户自己的文本。
     */
    private String styleStatus(JsonObject event) throws Exception {
        List<String> local = localStyles.names();
        StringBuilder text = new StringBuilder();
        text.append("样式库：").append(local.isEmpty() ? "（空；.style save <名称> 保存当前提示词，或 .style import webui 搬入 WebUI 预设样式）"
                : local.size() + " 个：" + String.join("、", local));
        text.append(categorySummary());
        text.append("\n样式完全独立在机器人这边：载入会替换你个人的正反向 prompt，不需要 WebUI 在线，也不会改动 WebUI。");
        text.append("\n样式是固定模板，prompt 才是你自己的内容：不记录「上一次载入哪个样式」，改 prompt 之后不会被自动覆盖。");
        return text.toString();
    }

    /** 样式分类的一行汇总（`.style` 与 `.style list` 都用它；空库或只有 0 条的组就没有这一行）。 */
    private String categorySummary() {
        List<String> parts = new ArrayList<>();
        for (LocalStyles.Group group : localStyles.categoryGroups())
            if (group.count() > 0) parts.add(group.name() + " " + group.count());
        if (parts.isEmpty()) return "";
        return "\n分类：" + String.join("、", parts)
                + "（每个 LoRA 附带样式各成一个分类（分类名＝LoRA 名）；认不出具体 LoRA 的才归到「"
                + LocalStyles.LORA_CATEGORY + "」，其余按归属栈）";
    }
    private void function(JsonObject event, String arguments) throws Exception {        String scope = promptScope(event);
        PromptFunctions functions = new PromptFunctions(settings.root);
        PromptFunctions.Reader reader = () -> userPrompts.prompts(scope);
        PromptFunctions.Writer writer = transform -> userPrompts.replace(scope, transform.apply(userPrompts.prompts(scope)));
        if (arguments.isEmpty() || arguments.equalsIgnoreCase("list")) {
            reply(event, "提示词集（定义共享，加载关联按个人）：" + numbered(event, "function", functions.names())); return;
        }
        if (arguments.equalsIgnoreCase("reset")) {
            functions.reset(scope); reply(event, "已清除你个人的提示词集关联，当前提示词保持不变。"); return;
        }
        if (arguments.equalsIgnoreCase("active")) {
            functions.recover(reader, scope);
            reply(event, "你已加载的提示词集：" + numbered(event, "function", functions.active(scope))); return;
        }
        if (arguments.equalsIgnoreCase("clear")) {
            functionChanged(event, functions.remove(reader, writer, scope, null), false); return;
        }
        Matcher rename = Pattern.compile("^rename(?:\\s+(overwrite))?\\s+([\\s\\S]+)$", Pattern.CASE_INSENSITIVE).matcher(arguments);
        if (rename.matches()) {
            String[] names = renameNames(rename.group(2), "用法：/function rename [overwrite] <旧名称> <新名称>（名称含空格时用双引号）");
            functions.recover(reader, scope);
            functions.rename(names[0], names[1], rename.group(1) != null);
            Log.info("提示词集已重命名：" + names[0] + " -> " + names[1]);
            reply(event, "提示词集已重命名：" + names[0] + " -> " + names[1]
                    + "\n定义共享，各用户已加载关联里的旧名也已同步改名。");
            return;
        }
        Matcher command = Pattern.compile("^(save|overwrite|prompt|load|remove|delete)(?:\\s+([\\s\\S]+))?$", Pattern.CASE_INSENSITIVE).matcher(arguments);
        if (!command.matches() || command.group(2) == null)
            throw new IllegalArgumentException("用法：/function list|active|clear 或 /function save|overwrite|prompt|load|remove|delete <名称>");
        String name = GenerationPreset.Store.name(stripQuotes(command.group(2)));
        switch (command.group(1).toLowerCase(Locale.ROOT)) {
            case "save", "overwrite" -> {
                functions.recover(reader, scope); functions.save(name, userPrompts.prompts(scope), command.group(1).equalsIgnoreCase("overwrite"));
                reply(event, "提示词集已保存（定义共享）：" + name + "\n使用 /function load " + name + " 追加到你个人的 prompt。");
            }
            case "prompt" -> {
                PromptFunctions.Pair pair = functions.get(name);
                reply(event, "提示词集：" + name + "\n正向 prompt：\n" + display(pair.positive()) + "\n反向 prompt：\n" + display(pair.negative()));
            }
            case "load" -> functionChanged(event, functions.load(reader, writer, scope, name), true);
            case "remove" -> functionChanged(event, functions.remove(reader, writer, scope, name), false);
            case "delete" -> { functions.recover(reader, scope); functions.delete(name); reply(event, "提示词集已删除：" + name); }
        }
    }
    private void functionChanged(JsonObject event, PromptFunctions.Change result, boolean added) {
        reply(event, "提示词集已" + (added ? "加载" : "移出") + "。\n正向实际" + (added ? "新增" : "移除") + "：" + display(result.changed().positive())
                + "\n反向实际" + (added ? "新增" : "移除") + "：" + display(result.changed().negative())
                + "\n" + formatPrompts(result.prompts()) + "\n来源：" + result.prompts().source());
    }
    private void preset(JsonObject event, String arguments) throws Exception {
        GenerationPreset.Store store = new GenerationPreset.Store(settings.root);
        if (arguments.isEmpty() || arguments.equalsIgnoreCase("list")) {
            reply(event, "参数预设：" + numbered(event, "preset", store.names())); return;
        }
        Matcher command = Pattern.compile("^(save|overwrite|show|load|remove)(?:\\s+([\\s\\S]+))?$", Pattern.CASE_INSENSITIVE).matcher(arguments);
        if (!command.matches() || command.group(2) == null)
            throw new IllegalArgumentException("用法：/preset list 或 /preset save|overwrite|show|load|remove <名称>");
        // 名称允许带引号：网页控制台的按钮一直发的是 ".preset load \"名称\"" 这种形式。
        String name = GenerationPreset.Store.name(stripQuotes(command.group(2)));
        switch (command.group(1).toLowerCase(Locale.ROOT)) {
            case "save", "overwrite" -> {
                store.save(name, sd.generationRequest(), command.group(1).equalsIgnoreCase("overwrite"));
                reply(event, "参数预设已保存：" + name + "\n" + store.get(name).describe());
            }
            case "show" -> reply(event, "参数预设：" + name + "\n" + store.get(name).describe());
            case "load" -> {
                GenerationPreset selected = store.get(name); sd.loadPreset(name, selected);
                reply(event, "参数预设已加载：" + name + "\n" + selected.describe() + "\n用于后续提交的任务；当前提示词保持不变。");
            }
            case "remove" -> { store.remove(name); reply(event, "参数预设已删除：" + name); }
        }
    }
    /**
     * /chat infix <要求>: let DeepSeek rewrite the base personality (the bot's setup prompt).
     * The result is applied only when nobody changed the personality while DeepSeek was working.
     */
    private void chatInfix(JsonObject event, String instruction) {
        if (instruction.isBlank() || instruction.length() > 8000) throw new IllegalArgumentException("用法：/chat infix <修改要求>，最多 8000 字符。");
        String original = settings.chatPersonality();
        if (original.isBlank()) throw new IllegalStateException("当前基础性格设定为空，请先用 /chat personality 设置后再修改。");
        if (closed.get()) throw new IllegalStateException("机器人正在关闭。");
        if (!progenBusy.compareAndSet(false, true)) { reply(event, "已有 DeepSeek 请求正在处理，请稍后重试。"); completeChatWorkflowStep(event,false); return; }
        JsonObject context = attachNormalWindow(event.deepCopy());
        replyProcess(context, "正在通过 DeepSeek 修改基础性格设定（初始设定）。");
        try { progenIO.execute(() -> {
            String message; boolean succeeded=false;
            try {
                // 基础性格属于聊天频道，走聊天频道的模型与密钥。
                String updated = DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.CHAT).editPersonality(instruction, original);
                if (updated.equals(original)) {
                    message = "智能修改没有可应用的有效变化，基础性格设定保持不变。";
                } else {
                    applyChatPersonality(settings, original, updated); chat.changed(true);
                    message = "基础性格设定已修改并立即生效，旧聊天上下文已清除：\n" + updated
                            + "\n可用 /chat 查看当前设定，/chat personality <设定> 手工替换全文。";
                }
                succeeded=true;
            } catch (Exception e) { message = "性格设定修改未完成：" + error(e); }
            finally { progenBusy.set(false); }
            Log.info(message);
            replyRanked(context, succeeded ? ReceiptRank.AFFIRM : ReceiptRank.FAILURE, message);
            completeChatWorkflowStep(context,succeeded);
            releaseNormalWindow(context);
        }); } catch (RejectedExecutionException e) { progenBusy.set(false); completeChatWorkflowStep(event,false); releaseNormalWindow(context); throw new IllegalStateException("机器人正在关闭。"); }
    }
    /** Compare-and-set style apply: never clobber a personality that changed while DeepSeek was working. */
    static String applyChatPersonality(Settings settings, String expected, String updated) throws IOException {
        if (!settings.chatPersonality().equals(expected))
            throw new IllegalStateException("等待 DeepSeek 时性格设定已被修改，本次结果未覆盖新内容，请重新 /chat infix。");
        if (updated == null || updated.isBlank()) throw new IOException("DeepSeek 返回的性格设定为空，未修改。");
        if (updated.length() > 20000) throw new IOException("DeepSeek 返回的性格设定超过 20000 字符，未应用。");
        settings.setChatPersonality(updated);
        return updated;
    }
    /**
     * /batch: run several commands from one message in order and report a consolidated receipt.
     * Each step keeps its own receipt and permission check; the batch stops at the first failure.
     */
    private void batchCommand(JsonObject event, String text) throws Exception {
        String body = text.length() > "/batch".length() ? text.substring("/batch".length()).strip() : "";
        if (body.isBlank()) throw new IllegalArgumentException("用法：/batch <指令1> ; <指令2> ; …（分号或换行分隔，最多 20 条）");
        List<String> steps = new ArrayList<>();
        for (String part : body.split("[;；\n]")) {
            String step = internalCommand(part.strip());
            if (step.isBlank()) continue;
            if (!step.startsWith("/")) throw new IllegalArgumentException("批量里的每一条都要以 . 开头：" + part.strip());
            if (step.matches("(?i)^/batch(?:\\s[\\s\\S]*)?$")) throw new IllegalArgumentException("批量命令里不能再嵌套 .batch。");
            steps.add(step);
        }
        if (steps.isEmpty()) throw new IllegalArgumentException("用法：/batch <指令1> ; <指令2> ; …（分号或换行分隔，最多 20 条）");
        if (steps.size() > 20) throw new IllegalArgumentException("一次最多 20 条指令，请分批提交。");
        List<String> collected = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        int succeeded = 0;
        String failure = null;
        batchReceipts.set(collected);
        try {
            for (String step : steps) {
                int before = collected.size();
                try { dispatch(event, step); succeeded++; }
                catch (Exception error) {
                    failure = step;
                    blocks.add("【" + (succeeded + 1) + "】" + step + " 失败：" + error(error));
                    break;
                }
                StringBuilder block = new StringBuilder("【").append(succeeded).append("】").append(step);
                for (int index = before; index < collected.size(); index++) block.append("\n").append(collected.get(index).strip());
                blocks.add(block.toString());
            }
        } finally { batchReceipts.remove(); }
        String summary = "批量执行完成：" + succeeded + "/" + steps.size() + " 条"
                + (failure == null ? "（全部成功）" : "（已在失败处停止：" + failure + "）");
        // 常规档：整批只留一条——全成功是一句肯定，中途失败是那条失败说明（含原因）。
        NormalWindow window = normalWindowFor(event);
        if (window != null) {
            window.offer(failure == null ? ReceiptRank.AFFIRM : ReceiptRank.FAILURE,
                    publicCommands(failure == null ? summary
                            : summary + (blocks.isEmpty() ? "" : "\n" + blocks.get(blocks.size() - 1).strip())));
            return;
        }
        // Each receipt stays its own message; they travel together inside one chat record.
        ChainRecord chain = chainRecords.get(ChatService.conversationKey(event));
        if (chain != null) {
            // A batch inside a natural-language chain: its receipts belong to the chain's single record.
            chain.add(summary + (blocks.isEmpty() ? "" : "\n" + String.join("\n", blocks)));
            return;
        }
        List<JsonArray> record = new ArrayList<>();
        record.add(Maps.text(summary));
        for (String block : blocks) record.add(Maps.text(block));
        JsonObject target = event.deepCopy();
        sender.sendRecord(target, record).exceptionally(error -> {
            Log.warn("批量回执合并发送失败，改为逐条发送：" + error(error));
            reply(target, summary + (blocks.isEmpty() ? "" : "\n" + String.join("\n", blocks)));
            return null;
        });
    }
    /** One owner rules the bot; admins are appointed by the owner and may only manage their own group's speech. */
    /**
     * /sd — SD WebUI 与自启动的状态与开关。启动是重操作，只让 owner/admin 用。
     */
    private void sdCommand(JsonObject event, String arguments) throws Exception {
        if (arguments.isEmpty() || arguments.equalsIgnoreCase("status") || arguments.equals("状态")) {
            reply(event, sdLauncher.describe());
            return;
        }
        if (arguments.equalsIgnoreCase("start") || arguments.equals("启动")) {
            requireStaff(event, "/sd start");
            replyProcess(event, "正在尝试启动 SD WebUI，请稍候…");
            String notice = sdLauncher.startNow();
            reply(event, (notice.isEmpty() ? "SD 已就绪。" : notice) + "\n" + sdLauncher.describe());
            return;
        }
        Matcher toggle = Pattern.compile("^(auto|boot)\\s+(on|off|开|关)$", Pattern.CASE_INSENSITIVE).matcher(arguments);
        if (toggle.matches()) {
            requireOwner(event, "/sd " + toggle.group(1).toLowerCase(Locale.ROOT));
            boolean enabled = toggle.group(2).equalsIgnoreCase("on") || toggle.group(2).equals("开");
            String key = toggle.group(1).equalsIgnoreCase("auto") ? "auto_start" : "start_on_boot";
            settings.sdSetting(key, new JsonPrimitive(enabled));
            Log.info("SD 自启动设置：" + key + "=" + enabled);
            reply(event, (key.equals("auto_start")
                    ? "生成前自动启动 SD：" + (enabled ? "开启" : "关闭")
                    : "机器人启动时启动 SD：" + (enabled ? "开启" : "关闭")) + "（已保存）\n" + sdLauncher.describe());
            return;
        }
        throw new IllegalArgumentException("用法：/sd、/sd start、/sd auto on|off、/sd boot on|off");
    }
    private void adminCommand(JsonObject event, String text) throws Exception {
        String user = Json.str(event, "user_id", "");
        String arguments = text.length() > "/admin".length() ? text.substring("/admin".length()).strip() : "";
        String usage = "用法：/admin add <@成员|QQ号|群名片>、/admin remove <@成员|QQ号|群名片>、/admin list";
        if (arguments.isEmpty()) throw new IllegalArgumentException(usage);
        if (arguments.equalsIgnoreCase("list")) {
            requireStaff(event, "/admin list");
            List<String> admins = settings.adminIds();
            reply(event, "owner：" + settings.ownerId() + "\nadmin（" + admins.size() + "）："
                    + (admins.isEmpty() ? "（无）" : "\n" + String.join("\n", admins))
                    + "\nadmin 只能开关自己所在群聊的发言；其他管理指令仅 owner 可用。");
            return;
        }
        Matcher command = Pattern.compile("^(add|remove)\\s+([\\s\\S]+)$", Pattern.CASE_INSENSITIVE).matcher(arguments);
        if (!command.matches()) throw new IllegalArgumentException(usage);
        boolean adding = command.group(1).equalsIgnoreCase("add");
        requireOwner(event, "/admin " + command.group(1).toLowerCase(Locale.ROOT));
        String target = resolveUser(event, command.group(2).strip());
        List<String> admins = adding ? settings.addAdmin(target) : settings.removeAdmin(target);
        Log.info("admin 名单" + (adding ? "添加" : "移除") + "：" + target + "（操作者 " + user + "）；现在共 " + admins.size() + " 人");
        reply(event, (adding ? "已添加 admin：" : "已移除 admin：") + target
                + "\n当前 admin（" + admins.size() + "）：" + (admins.isEmpty() ? "（无）" : String.join("、", admins)));
    }
    /** 网页控制台来的合成事件：已经用访问令牌鉴权过，不再要求 QQ owner/admin 身份。 */
    static boolean fromWebConsole(JsonObject event) {
        return event != null && Json.bool(event, "webui", false);
    }
    private void requireOwner(JsonObject event, String action) throws IOException {
        if (fromWebConsole(event)) return;
        if (!settings.isOwner(Json.str(event, "user_id", "")))
            throw new IllegalArgumentException("「" + action + "」仅 owner 可用（owner QQ：" + settings.ownerId() + "）。");
    }
    private void requireStaff(JsonObject event, String action) throws IOException {
        if (fromWebConsole(event)) return;
        if (!settings.isStaff(Json.str(event, "user_id", "")))
            throw new IllegalArgumentException("「" + action + "」仅 owner 或 admin 可用（owner QQ：" + settings.ownerId() + "）。");
    }
    /** Resolves an @segment, a plain QQ number or a unique group member name into one QQ number. */
    String resolveUser(JsonObject event, String value) throws Exception {
        String mentioned = mentionedUser(event);
        if (mentioned != null) return mentioned;
        String plain = value.strip();
        if (Settings.isUserId(plain)) return plain;
        if (!"group".equals(Json.str(event, "message_type", "")))
            throw new IllegalArgumentException("私聊中请直接填写 QQ 号；「" + plain + "」无法解析为账号。");
        if (plain.isBlank() || plain.length() > 100) throw new IllegalArgumentException("请提供有效的 @成员、QQ 号或群名片。");
        JsonObject params = new JsonObject();
        params.add("group_id", new com.google.gson.JsonPrimitive(Json.str(event, "group_id", "")));
        JsonElement data;
        try { data = sender.callApi("get_group_member_list", params).get(20, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IOException("无法查询群成员（" + error(e) + "）；请改用 QQ 号或 @ 该成员。"); }
        if (data == null || !data.isJsonArray()) throw new IOException("群成员列表不可用；请改用 QQ 号或 @ 该成员。");
        List<String> exact = new ArrayList<>(), partial = new ArrayList<>();
        for (JsonElement item : data.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            JsonObject member = item.getAsJsonObject();
            String id = Json.str(member, "user_id", "");
            if (!Settings.isUserId(id)) continue;
            String card = Json.str(member, "card", "").strip(), nickname = Json.str(member, "nickname", "").strip();
            if (plain.equalsIgnoreCase(card) || plain.equalsIgnoreCase(nickname)) { if (!exact.contains(id)) exact.add(id); }
            else if (!card.isBlank() && card.contains(plain) || !nickname.isBlank() && nickname.contains(plain)) {
                if (!partial.contains(id)) partial.add(id);
            }
        }
        List<String> matches = exact.isEmpty() ? partial : exact;
        if (matches.isEmpty()) throw new IllegalArgumentException("群里没有匹配「" + plain + "」的成员；请改用 QQ 号或 @ 该成员。");
        if (matches.size() > 1) throw new IllegalArgumentException("「" + plain + "」匹配到多人：" + String.join("、", matches) + "；请改用 QQ 号或 @ 该成员。");
        return matches.get(0);
    }
    /**
     * The body of the message this one quotes (QQ "reply"), from the segment itself or via get_msg.
     * Bounded and never treated as a command: it is context for understanding references only.
     */
    static String quotedText(JsonObject event, Bot.Sender sender) {
        JsonElement message = event.get("message");
        String id = "";
        String embedded = "";
        if (message != null && message.isJsonArray()) {
            for (JsonElement item : message.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                JsonObject segment = item.getAsJsonObject();
                if (!"reply".equals(Json.str(segment, "type", ""))) continue;
                JsonObject data = Json.obj(segment, "data");
                id = Json.str(data, "id", Json.str(data, "message_id", "")).strip();
                for (String field : new String[]{"text", "message", "raw_message"}) {
                    JsonElement value = data.get(field);
                    if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && !value.getAsString().isBlank()) {
                        embedded = messageText(value).strip();
                        break;
                    }
                }
                break;
            }
        }
        String quoted = embedded;
        if (quoted.isBlank() && !id.isEmpty() && sender != null) {
            try {
                JsonObject params = new JsonObject(); params.addProperty("message_id", id);
                JsonElement data = sender.callApi("get_msg", params).get(5, TimeUnit.SECONDS);
                if (data != null && data.isJsonObject()) {
                    JsonObject object = data.getAsJsonObject();
                    JsonElement content = object.has("message") ? object.get("message") : object.get("raw_message");
                    if (content != null) quoted = messageText(content).strip();
                }
            } catch (Exception error) { Log.warn("读取引用消息失败：" + error(error)); }
        }
        quoted = quoted.replaceAll("[\\p{Cntrl}\\p{Cf}]+", " ").replaceAll("\\s+", " ").strip();
        return quoted.length() > 300 ? quoted.substring(0, 300) + "…" : quoted;
    }
    /** The single @target of a message, ignoring @全体成员 and the bot itself. */
    static String mentionedUser(JsonObject event) {
        Set<String> found = new LinkedHashSet<>();
        String self = Json.str(event, "self_id", "");
        JsonElement message = event.get("message");
        if (message != null && message.isJsonArray())
            for (JsonElement item : message.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                JsonObject segment = item.getAsJsonObject();
                if (!"at".equals(Json.str(segment, "type", ""))) continue;
                String qq = Json.str(Json.obj(segment, "data"), "qq", "").strip();
                if (Settings.isUserId(qq) && !qq.equals(self)) found.add(qq);
            }
        if (found.isEmpty()) {
            Matcher matcher = Pattern.compile("\\[CQ:at,qq=([1-9][0-9]{0,19})\\]").matcher(Json.str(event, "raw_message", ""));
            while (matcher.find()) if (!matcher.group(1).equals(self)) found.add(matcher.group(1));
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }
    private void map(JsonObject event, String text) throws Exception {
        if (text.equals("/map path")) {
            requireStaff(event, "/map path");
            reply(event, "粤海：" + settings.mapPath("yh") + "\n丽湖：" + settings.mapPath("liv")); return;
        }
        requireOwner(event, "/map set");
        Matcher m = Pattern.compile("^/map\\s+set\\s+(yh|liv)\\s+([\\s\\S]+)$").matcher(text);
        if (!m.matches()) throw new IllegalArgumentException("用法：/map set yh <路径> 或 /map set liv <路径>");
        String value = m.group(2).strip();
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length()-1);
        settings.setMapPath(m.group(1), value);
        reply(event, "地图路径已保存，重启后仍有效：" + settings.mapPath(m.group(1)));
    }
    private static final class GenerationJob {
        final String taskId = UUID.randomUUID().toString();
        final List<Path> completedImages = new ArrayList<>();
        final JsonObject event;
        final SdClient.GenerationRequest snapshot;
        BigInteger remaining, number, total, done = BigInteger.ZERO, failed = BigInteger.ZERO, images = BigInteger.ZERO;
        String lastError = "";
        /** 挂起：不参与调度，插到队首也不会被取；已经在下发的那一张会跑完。 */
        boolean suspended;
        /** 取消：跑完当前这张就结算（已生成的图片照常可领取）。 */
        boolean cancelled;
        /** 出图前的 VAE 冲突检查只做一次（这一任务的第一张之前），不必每张都查。 */
        boolean vaeChecked;
        /** 出图前的防呆拒绝了这次生成：整条任务到此为止（剩余次数由结算统一清账）。 */
        String refused;
        GenerationJob(JsonObject event, SdClient.GenerationRequest snapshot, BigInteger remaining) {
            this.event = event; this.snapshot = snapshot; this.remaining = remaining; this.total = remaining;
        }
        String status() {
            if (cancelled) return "已取消";
            if (suspended) return "已挂起";
            return "等待中";
        }
    }
    private String generationStatus() {
        synchronized (generationLock) {
            StringBuilder status = new StringBuilder("生成队列：" + (generationRunning ? "运行中" : "空闲") + "，等待 " + waitingGenerations
                    + " 个" + (suspendedGenerations.signum() > 0 ? "（另有挂起 " + suspendedGenerations + " 个）" : "")
                    + "，合计 " + waitingGenerations.add(suspendedGenerations).add(generationRunning ? BigInteger.ONE : BigInteger.ZERO)
                    + " 个。自动领取：" + (settings.autoGet() ? "开启" : "关闭") + "。");
            int slot = 0;
            for (GenerationJob job : generationJobs) status.append("\n#").append(++slot).append(" 任务 #").append(job.number).append("：")
                    .append(job.done).append("/").append(job.total)
                    .append(job == currentGeneration
                            ? (job.cancelled ? " 生成中（取消待生效）" : job.suspended ? " 生成中（挂起待生效）" : " 生成中")
                            : " " + job.status())
                    .append("，失败 ").append(job.failed).append("，已生成 ").append(job.images).append(" 张")
                    .append("（" + describeConversation(job.event) + "）");
            for (GenerationJob job : finishedJobs) status.append("\n任务 #").append(job.number).append("：").append(job.done).append("/")
                    .append(job.total).append(" 已完成，失败 ").append(job.failed).append("，共 ").append(job.images).append(" 张");
            return status.toString();
        }
    }
    /** 队列操作：挂起 / 继续 / 取消 / 置顶。返回给用户的回执文本。 */
    private String generationControl(String action, String argument) {
        String target = argument == null ? "" : argument.strip().replaceFirst("^#", "");
        synchronized (generationLock) {
            if (action.equals("cancel") && (target.equalsIgnoreCase("all") || target.equals("全部") || target.isEmpty())) {
                List<GenerationJob> cancelled = new ArrayList<>();
                for (GenerationJob job : generationJobs) { job.cancelled = true; cancelled.add(job); }
                BigInteger freed = waitingGenerations.add(suspendedGenerations);
                waitingGenerations = BigInteger.ZERO; suspendedGenerations = BigInteger.ZERO;
                Log.info("取消全部生成任务：" + cancelled.size() + " 个（含正在生成的那张会跑完）");
                return "已取消队列里的全部任务（" + cancelled.size() + " 个，共 " + freed + " 次待生成）。"
                        + "\n正在下发的那一张会跑完，其余不再生成；已经生成的图片照常可以领取（" + (settings.autoGet() ? "会自动领取" : "用 .get 领取") + "）。";
            }
            if (target.isEmpty()) return "用法：.gen hold|resume|cancel|first #编号（.gen list 查看编号；取消全部用 .gen cancel all）";
            GenerationJob job = null;
            for (GenerationJob each : generationJobs) if (each.number.toString().equals(target)) { job = each; break; }
            if (job == null) return "没有这个任务：# " + target + "。用 .gen list 查看队列里的任务编号（刚完成的会出现在列表末尾）。";
            switch (action) {
                case "hold" -> {
                    if (job.cancelled) return "任务 #" + job.number + " 已经取消了。";
                    if (job.suspended) return "任务 #" + job.number + " 本来就是挂起状态。";
                    job.suspended = true;
                    BigInteger held = job.remaining;
                    suspendedGenerations = suspendedGenerations.add(held);
                    waitingGenerations = waitingGenerations.subtract(held);
                    Log.info("生成任务 #" + job.number + " 已挂起（剩余 " + held + " 次）");
                    return (job == currentGeneration
                            ? "任务 #" + job.number + " 已挂起：正在下发的这一张会先跑完，之后还剩 " + held + " 次不生成。"
                            : "任务 #" + job.number + " 已挂起：还剩 " + held + " 次不生成。")
                            + "\n队列会先跑别的任务；继续用：.gen resume #" + job.number;
                }
                case "resume" -> {
                    if (job.cancelled) return "任务 #" + job.number + " 已经取消了，无法继续。";
                    if (!job.suspended) return "任务 #" + job.number + " 没有挂起，正在按顺序排队。";
                    job.suspended = false;
                    suspendedGenerations = suspendedGenerations.subtract(job.remaining);
                    waitingGenerations = waitingGenerations.add(job.remaining);
                    ensureGenerationWorker();
                    Log.info("生成任务 #" + job.number + " 已继续（剩余 " + job.remaining + " 次）");
                    return "任务 #" + job.number + " 已继续，重新排进队列（剩余 " + job.remaining + " 次）。";
                }
                case "cancel" -> {
                    if (job.cancelled) return "任务 #" + job.number + " 已经取消了。";
                    job.cancelled = true;
                    BigInteger freed = job.remaining.add(job == currentGeneration ? BigInteger.ONE : BigInteger.ZERO);
                    waitingGenerations = waitingGenerations.subtract(job.remaining);
                    if (job.suspended) suspendedGenerations = suspendedGenerations.subtract(job.remaining);
                    job.remaining = BigInteger.ZERO;
                    Log.info("生成任务 #" + job.number + " 已取消（" + (job == currentGeneration ? "当前这张跑完即停" : "直接跳过") + "）");
                    return "任务 #" + job.number + " 已取消：不再生成剩下的 " + freed + " 次。"
                            + (job == currentGeneration ? "\n正在下发的那一张会跑完。" : "")
                            + "\n已经生成的 " + job.images + " 张照常保留，" + (settings.autoGet() ? "任务结算后自动领取。" : "用 .get 领取。");
                }
                case "first" -> {
                    if (job.cancelled) return "任务 #" + job.number + " 已经取消了。";
                    job.suspended = false;
                    generationJobs.remove(job);
                    generationJobs.addFirst(job);
                    if (job == currentGeneration) return "任务 #" + job.number + " 本来就在生成中。";
                    BigInteger resumed = job.remaining;
                    if (resumed.signum() > 0) { /* 已在等待计数里，位置变化不影响计数 */ }
                    ensureGenerationWorker();
                    Log.info("生成任务 #" + job.number + " 已置顶为第一优先级");
                    return "任务 #" + job.number + " 已置顶：下一位生成（它就是第一优先级，队首）。";
                }
                default -> { return "用法：.gen hold|resume|cancel|first #编号"; }
            }
        }
    }
    /** 队列空了但还有活时把工作线程拉起来（置顶/继续之后必须调用）。 */
    private void ensureGenerationWorker() {
        if (closed.get() || generationWorkerActive) return;
        generationWorkerActive = true;
        try { generation.execute(this::runGenerationQueue); }
        catch (RejectedExecutionException e) { generationWorkerActive = false; }
    }
    /** 网页「出图」页的任务列表：序号、任务号、状态、进度、图片数。 */
    public JsonArray webTasks() {
        synchronized (generationLock) {
            JsonArray tasks = new JsonArray();
            int slot = 0;
            for (GenerationJob job : generationJobs) {
                JsonObject item = new JsonObject();
                item.addProperty("slot", ++slot);
                item.addProperty("number", job.number.toString());
                item.addProperty("done", job.done.toString());
                item.addProperty("total", job.total.toString());
                item.addProperty("images", job.images.toString());
                item.addProperty("failed", job.failed.toString());
                item.addProperty("running", job == currentGeneration);
                item.addProperty("suspended", job.suspended);
                item.addProperty("cancelled", job.cancelled);
                // 进度百分比给网页画进度条：done/total 都是 BigInteger，按 double 估算即可。
                item.addProperty("percent", job.total.signum() == 0 ? 0
                        : Math.min(100, (int) Math.round(100.0 * job.done.doubleValue() / job.total.doubleValue())));
                item.addProperty("status", job == currentGeneration
                        ? (job.cancelled ? "生成中（取消待生效）" : job.suspended ? "生成中（挂起待生效）" : "生成中")
                        : job.status());
                tasks.add(item);
            }
            return tasks;
        }
    }
    /** 网页任务操作：hold / resume / cancel / first，与指令走同一条实现。 */
    public JsonObject webTaskAction(String action, String number) {
        String normalized = switch (action == null ? "" : action.strip().toLowerCase(Locale.ROOT)) {
            case "hold", "suspend" -> "hold";
            case "resume", "continue" -> "resume";
            case "cancel" -> "cancel";
            case "first", "priority", "top" -> "first";
            default -> throw new IllegalArgumentException("不支持的任务操作：" + action);
        };
        JsonObject result = new JsonObject();
        result.addProperty("message", generationControl(normalized, number));
        result.add("tasks", webTasks());
        return result;
    }
    /**
     * 网页「生成参数」卡的即时应用：面板里没有「应用」按钮，改完就是当前生效值。
     * 只处理这次真的传上来的字段（空值表示不动它），一处写失败不会连累其它字段；
     * 返回完整状态，网页直接用返回值刷新面板。
     */
    public JsonObject webGeneration(JsonObject body) throws Exception {
        List<String> changed = new ArrayList<>();
        SdClient.GenerationSettings current = sd.settings();
        int width = Json.num(body, "width", current.width());
        int height = Json.num(body, "height", current.height());
        if (width != current.width() || height != current.height()) {
            SdClient.GenerationSettings applied = sd.setSize(width, height);
            changed.add("尺寸 " + applied.width() + " × " + applied.height() + " 像素");
        }
        String sampler = Json.str(body, "sampler", "").strip();
        if (!sampler.isEmpty() && !sampler.equals(current.samplerName()))
            changed.add("采样方法 " + sd.setSampler(sampler).samplerName());
        cn.szu.bot.sd.GenerationParameters parameters = sd.parameters();
        String model = Json.str(body, "model", "").strip();
        if (!model.isEmpty() && !model.equalsIgnoreCase(parameters.checkpoint())) {
            parameters = sd.setParameter("model", model);
            String stackWarning = forgeStackWarning(model);
            if (!stackWarning.isBlank()) changed.add(stackWarning.strip());
            changed.add("基础模型 " + parameters.checkpoint());
        }
        if (numberProvided(body, "steps")) {
            int steps = body.get("steps").getAsInt();
            if (steps != parameters.steps()) {
                parameters = sd.setParameter("steps", Integer.toString(steps));
                changed.add("步数 " + parameters.steps());
            }
        }
        if (numberProvided(body, "cfg")) {
            double cfg = body.get("cfg").getAsDouble();
            if (Double.compare(cfg, parameters.cfgScale()) != 0) {
                parameters = sd.setParameter("cfg", Double.toString(cfg));
                changed.add("CFG " + parameters.cfgScale());
            }
        }
        if (numberProvided(body, "seed")) {
            long seed = body.get("seed").getAsLong();
            if (seed != parameters.seed()) {
                parameters = sd.setParameter("seed", Long.toString(seed));
                changed.add("种子 " + parameters.seed());
            }
        }
        if (numberProvided(body, "imageCount")) {
            int count = body.get("imageCount").getAsInt();
            if (count != settings.imageCount()) {
                settings.imageCount(count);
                changed.add("图片上限 " + settings.imageCount() + " 张/条");
            }
        }
        JsonObject result = webStatus();
        result.add("changed", Json.GSON.toJsonTree(changed));
        // 回执里连"这份参数现在归谁"一起说清楚：采样方法/尺寸跟 WebUI 页面走，步数/CFG/种子/底模是机器人自己的记录。
        String source = result.has("generation") && result.get("generation").isJsonObject()
                ? Json.str(result.getAsJsonObject("generation"), "source", "") : "";
        result.addProperty("message", (changed.isEmpty() ? "生成参数没有变化。" : "已生效：" + String.join("，", changed))
                + (source.isBlank() ? "" : "\n参数来源：" + source));
        return result;
    }

    /** 面板只在字段真有值时才提交它：空字符串或 null 表示这次不改这个字段。 */
    private static boolean numberProvided(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull() && !body.get(key).getAsString().isBlank();
    }
    /**
     * The prompts a scope should work with. A scope that never wrote anything inherits the current WebUI
     * page prompt (the one everyone sees), so ordinary members can generate and edit straight away instead
     * of being told to set up a prompt first. The inherited copy then becomes their own.
     */
    SdClient.Prompts effectivePrompts(String scope) throws Exception {
        SdClient.Prompts mine = userPrompts.prompts(scope);
        if (!mine.positive().isBlank() || !mine.negative().isBlank()) return mine;
        SdClient.Prompts shared = sdPromptsForSeed();
        if (shared.positive().isBlank() && shared.negative().isBlank()) return mine;
        Log.info("首次使用：继承当前 WebUI 提示词（" + scope + "）");
        userPrompts.seed(scope, shared);
        return userPrompts.prompts(scope);
    }
    /**
     * 读一份"可继承的共享提示词"（用户自己的还是空的时候，首次使用会从这儿补一份）。
     *
     * <p>这一步只是**可选增强**：读不到就不继承，绝不该让整条指令失败。实测（ChineseTagCommandTest）：
     * 同一条线程上先跑完一次 DeepSeek 改写、紧接着读 WebUI 桥接时，{@code SdClient} 的 HTTP 调用会抛出
     * "Stable Diffusion 请求已中断"（线程的中断标志其实没置位，属于 HttpClient／桥接侧的瞬时故障）——
     * 这里清掉中断标志重试一次；仍然失败就按"读不到"处理（返回空），把真正的失败留给改写调用去报。
     */
    private SdClient.Prompts sdPromptsForSeed() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try { return sd.prompts(); }
            catch (Exception failure) {
                Log.warn("读取 WebUI 共享提示词失败（第 " + attempt + " 次）：" + error(failure));
                Thread.interrupted();
            }
        }
        Log.warn("读不到 WebUI 共享提示词，本次不继承（用户自己的 prompt 不受影响）。");
        return new SdClient.Prompts("", "", "unavailable");
    }
    private void generate(JsonObject event, BigInteger count) throws Exception {
        // Serialize submissions while capturing their snapshots, without blocking the GPU worker's bookkeeping.
        synchronized (generationSubmissionLock) {
            synchronized (generationLock) {
                if (closed.get()) throw new IllegalStateException("机器人正在关闭，请稍后重试。");
            }
            // A user who never set a personal prompt starts from the prompt everyone can see on the WebUI
            // page: "用当前这套提示词出图" must work for every member, not only for the owner.
            SdClient.Prompts personal = effectivePrompts(promptScope(event));
            if (personal.positive().isBlank() && personal.negative().isBlank())
                throw new IllegalArgumentException("当前没有可用的提示词：先发送 /prompt set <内容>、/prompt add <词> 或 /style load <样式>，再 /gen。");
            SdClient.GenerationRequest snapshot = new SdClient.GenerationRequest(personal, sd.settings(), sd.parameters());
            synchronized (generationLock) {
                if (closed.get()) throw new IllegalStateException("机器人正在关闭，请稍后重试。");
                // 常规档：这条任务接管本指令的收据窗口——"入队"这句肯定先记账、不发，
                // 等这一批图真的发完（或失败）再定稿：图出去了就不再出声，出不去就是那一条说明。
                GenerationJob submitted = new GenerationJob(attachNormalWindow(event.deepCopy()), snapshot, count);
                submitted.number = nextGenerationId = nextGenerationId.add(BigInteger.ONE);
                // 常规档：只回一句"肯定"，不把参数清单（采样方法/尺寸/步数/CFG/种子/底模/提示词来源/参数来源）
                // 与任务号一起刷出来。debug 档（默认）就是原来那条完整回执。
                reply(event, quietReceipts(event)
                        ? "好的，开始生成 " + count + " 次～"
                        : "已加入生成队列，任务 #" + submitted.number + "，共 " + count + " 次生成，将依次开始生成图片。\n"
                                + formatSettings(snapshot.settings(), promptScope(event))
                                + "\n" + snapshot.parameters().describe()
                                + "\n提示词来源：" + snapshot.prompts().source() + "\n参数来源：" + snapshot.settings().source());
                generationJobs.addLast(submitted);
                waitingGenerations = waitingGenerations.add(count);
                Log.info("生成任务 #" + submitted.number + " 入队：" + count + " 次，来自 " + describeConversation(event)
                        + "，采样 " + snapshot.settings().samplerName() + "，" + snapshot.settings().width() + "×" + snapshot.settings().height()
                        + "，步数 " + snapshot.parameters().steps() + "，CFG " + snapshot.parameters().cfgScale()
                        + "，种子 " + snapshot.parameters().seed());
                if (!generationWorkerActive) {
                    generationWorkerActive = true;
                    try { generation.execute(this::runGenerationQueue); }
                    catch (RejectedExecutionException e) {
                        generationWorkerActive = false; generationJobs.clear(); waitingGenerations = BigInteger.ZERO; suspendedGenerations = BigInteger.ZERO;
                        releaseNormalWindow(submitted.event);   // 任务没起来：窗口只被任务占着，这里还回去
                        throw new IllegalStateException("机器人正在关闭，本次任务未启动。");
                    }
                }
            }
        }
    }
    private void runGenerationQueue() {
        while (true) {
            List<GenerationJob> settled = new ArrayList<>();
            GenerationJob job;
            synchronized (generationLock) {
                if (closed.get()) {
                    // 停机：静默清空队列（正在跑的那一个仍会走下面的结算回执），避免关机时刷一串"已停止"。
                    for (GenerationJob each : generationJobs) { each.cancelled = true; each.remaining = BigInteger.ZERO; }
                    generationJobs.clear(); waitingGenerations = BigInteger.ZERO; suspendedGenerations = BigInteger.ZERO;
                    generationWorkerActive = false; generationRunning = false; currentGeneration = null;
                    job = null;
                } else {
                    job = pickGenerationJob(settled);
                    if (job == null) { generationWorkerActive = false; generationRunning = false; currentGeneration = null; }
                    else {
                        currentGeneration = job; generationRunning = true;
                        if (job.remaining.signum() > 0) {
                            job.remaining = job.remaining.subtract(BigInteger.ONE);
                            waitingGenerations = waitingGenerations.subtract(BigInteger.ONE);
                        }
                    }
                }
            }
            for (GenerationJob done : settled) noticeFinishedGeneration(done, closed.get() ? "已停止" : "已取消");
            if (job == null) return;
            long started=System.nanoTime();
            try {
                // SD 没在跑就先把它拉起来（自启动，见 SdLauncher）：等到接口可用再提交这一张。
                String autoStart = sdLauncher.ensureRunning();
                if (!autoStart.isEmpty()) {
                    Log.info("生成任务 #" + job.number + "：" + autoStart);
                    // 常规档：SD 自启动只是一句过程说明，收进这条指令的唯一回执里，不再单独刷屏。
                    replyProcess(job.event, autoStart);
                }
                // 出图前的防呆（只查一次）：残留的额外模块会被自动修掉，用户显式选错的 VAE 会被拒绝。
                if (!job.vaeChecked) {
                    job.vaeChecked = true;
                    String refusal = vaePreflight(job);
                    if (!refusal.isEmpty()) {
                        job.refused = refusal;
                        // 拒绝原因随结算回执一起发出（用户不必去翻日志）。
                        synchronized (generationLock) { job.lastError = refusal; }
                    }
                }
                if (job.refused != null) {
                    // 拒绝：不调用 SD（绝不会默默出一张灰图），拒绝原因由下面的结算回执带出。
                } else {
                    List<Path> result = sd.generate(job.snapshot, job.taskId);
                    // 出图后的最后一道防线：灰图自检 + 自动修复 + 只重试一次（独立于上面的冲突检测）。
                    GrayCheck gray = grayCheck(job, result);
                    if (!gray.notice().isEmpty()) replyProcess(job.event, gray.notice());
                    if (gray.failed()) synchronized (generationLock) {
                        job.failed = job.failed.add(BigInteger.ONE);
                        job.lastError = "出图全是灰图（纯色废图）：VAE/额外模块与当前模型冲突，自动修复后重试仍失败；用 .vae check 查看";
                    }
                    result = gray.images();
                    job.completedImages.addAll(result);
                    synchronized (generationLock) { job.images = job.images.add(BigInteger.valueOf(result.size())); }
                    Log.info("生成任务 #"+job.number+" 第 "+job.done.add(BigInteger.ONE)+"/"+job.total+" 次成功："
                            +result.size()+" 张，耗时 "+millis(started)+" ms");
                }
            } catch (Exception e) {
                synchronized (generationLock) { job.failed = job.failed.add(BigInteger.ONE); job.lastError = error(e); }
                Log.error("生成任务 #"+job.number+" 第 "+job.done.add(BigInteger.ONE)+"/"+job.total
                        +" 次失败，耗时 "+millis(started)+" ms", e);
            }
            String notice = null, settledReason = null;
            synchronized (generationLock) {
                job.done = job.done.add(BigInteger.ONE); generationRunning = false;
                if (currentGeneration == job) currentGeneration = null;
                // 取消优先：跑完手上这张就结算；挂起则留在队列里等 .gen resume。
                if (job.refused != null) { settledReason = "已拒绝"; notice = settleGenerationJob(job, settledReason); }
                else if (job.cancelled) { settledReason = "已取消"; notice = settleGenerationJob(job, settledReason); }
                else if (job.remaining.signum() == 0 || closed.get()) {
                    settledReason = closed.get() ? "已停止" : "已完成";
                    notice = settleGenerationJob(job, settledReason);
                }
            }
            if (notice != null) {
                Log.info(notice);
                replySettleNotice(job, settledReason, notice);
                if (!closed.get() && settings.autoGet() && !job.completedImages.isEmpty()) getImages(job.event, List.copyOf(job.completedImages));
                releaseNormalWindow(job.event);   // 任务这一份占用收尾；图片那一路自己另占一份，发完才定稿
            }
        }
    }
    /** 取下一个可跑的任务：队首优先，跳过并结算已取消的，跳过挂起的（挂起不占调度）。 */
    private GenerationJob pickGenerationJob(List<GenerationJob> settled) {
        for (java.util.Iterator<GenerationJob> iterator = generationJobs.iterator(); iterator.hasNext(); ) {
            GenerationJob next = iterator.next();
            if (next.cancelled) {
                iterator.remove();
                waitingGenerations = waitingGenerations.subtract(next.remaining);
                if (next.suspended) suspendedGenerations = suspendedGenerations.subtract(next.remaining);
                next.remaining = BigInteger.ZERO;
                settled.add(next);
                finishedJobs.addFirst(next); while (finishedJobs.size() > 20) finishedJobs.removeLast();
                continue;
            }
            if (next.suspended) continue;
            return next;
        }
        return null;
    }
    /** 结算一个任务：移出队列、留痕、必要时补一条回执。 */
    private String settleGenerationJob(GenerationJob job, String reason) {
        generationJobs.remove(job);
        if (job.suspended) { suspendedGenerations = suspendedGenerations.subtract(job.remaining); job.suspended = false; }
        waitingGenerations = waitingGenerations.subtract(job.remaining);
        job.remaining = BigInteger.ZERO;
        finishedJobs.addFirst(job); while (finishedJobs.size() > 20) finishedJobs.removeLast();
        // 被出图前防呆拒绝时没生成过任何图片：成功次数按 0 报（done 记的是"这次尝试"）。
        BigInteger succeeded = job.refused != null ? BigInteger.ZERO : job.done.subtract(job.failed);
        return "任务 #" + job.number + " " + reason + "：" + job.done + "/" + job.total
                + "，图片生成成功 " + succeeded + " 次，生成失败 " + job.failed + " 次，共 " + job.images + " 张。"
                + (job.lastError.isEmpty() ? "" : (job.refused != null ? "\n拒绝原因：" : "\n最近失败原因：") + job.lastError)
                + (settings.autoGet() ? "\n已生成的图片将自动领取。" : "\n发送 /get 领取已生成的图片。");
    }
    /**
     * 结算回执在常规档（{@code .mode normal}）下的发送决策——这是结算回执唯一的落点。
     *
     * <p>常规档下它只是这条指令收据里的一个**候选**：失败/被拒绝/取消/停止/没有成品都按最高优先级记进去
     * （会把"入队"那条肯定顶掉，绝不会出现"入队一条 + 结算一条"），全部成功且图片马上会自动发出时
     * 连候选都不记——图片本身就是回执。关闭自动领取时图片不会自己出现，于是留一句"发送 .get 领取"。
     */
    private void replySettleNotice(GenerationJob job, String reason, String notice) {
        boolean trouble = job.refused != null || job.failed.signum() > 0 || !job.lastError.isEmpty()
                || !"已完成".equals(reason) || job.completedImages.isEmpty();
        if (trouble) { replyFailure(job.event, notice); return; }
        if (!quietReceipts(job.event)) { reply(job.event, notice); return; }
        if (settings.autoGet()) return;
        reply(job.event, "生成完成，发送 /get 领取图片。");
    }
    /** 回执 + 自动领取；reason 为 null 表示调用方已经自己回过执了。 */
    private void noticeFinishedGeneration(GenerationJob job, String reason) {
        if (reason != null) {
            String notice = settleGenerationJob(job, reason);
            Log.info(notice);
            replySettleNotice(job, reason, notice);
        }
        if (!closed.get() && settings.autoGet() && !job.completedImages.isEmpty()) getImages(job.event, List.copyOf(job.completedImages));
        releaseNormalWindow(job.event);      // 任务这一份占用收尾；图片那一路自己另占一份，发完才定稿
    }
    private void getImages(JsonObject event) { getImages(event, null); }
    private void getImages(JsonObject event, List<Path> automaticImages) { getImages(event, automaticImages, 0); }
    private void getImages(JsonObject event, List<Path> automaticImages, int recentCount) {
        if (closed.get()) throw new IllegalStateException("机器人正在关闭，请稍后重试。");
        if (automaticImages == null && !drainingImages.compareAndSet(false, true)) { reply(event, "正在领取图片，请等待本次领取完成后再试。"); return; }
        // 常规档：领取/回溯也是这条指令的一部分——窗口押到"图真的发完"再定稿，
        // 所以"本次领取完成，共 N 张"不会和前面的肯定凑成两条。
        JsonObject context = attachNormalWindow(event.deepCopy());
        try {
            outboxDelivery.execute(() -> {
                String notice = null;
                int delivered = 0;
                boolean failed = false;
                // 常规档：图片已经发出去了，"本次领取完成，共 N 张"这种计数回执就不必再发一条。
                // 领取本身（acknowledgeImages）在本方法里先于回执完成，少发消息不会漏领。
                // 只对这一条成功计数回执生效：暂无图片、发送失败、预览保留这些说明照旧发出。
                boolean quietSuccess = false;
                try {
                    // Freeze incomplete task identities before sending: preview ACK must not consume their images,
                    // even when the task finishes while this upload is in flight.
                    Set<String> previewTasks = new HashSet<>();
                    if (automaticImages == null && recentCount == 0) synchronized (generationLock) {
                        for (GenerationJob job : generationJobs) previewTasks.add("task-" + job.taskId);
                    }
                    List<Path> pending = recentCount > 0 ? sd.recentImages(recentCount) : sd.pendingImages().stream().filter(path -> automaticImages == null || automaticImages.contains(path)).toList();
                    if (pending.isEmpty() && automaticImages != null) return;
                    if (pending.isEmpty()) notice = recentCount > 0 ? "暂无历史图片。" : "暂无待领取图片，请先发送 /gen。";
                    else {
                        int batchNumber = 0;
                        // 回溯（.rg）与领取（.get／自动领取）走同一套发送规则：分批之后统一交给 sendBatch 按 .imgmode 分流。
                        // 只有分批口径不同——领取按生成任务分开发送（同一次 /gen 的多次 SD 调用算一条聊天记录），
                        // 回溯只按 .imgcnt 切块：历史图片基本上一张一个 task- 目录，按任务切会把本该合成一条
                        // 聊天记录的几张拆成一串单张消息（线上 .rg 4 打出四行「单张普通发送」就是这个原因）。
                        for (List<Path> batch : recentCount > 0 ? historyBatches(pending, settings.imageCount())
                                : imageBatches(pending, settings.imageCount())) {
                        batchNumber++;
                        long started = System.nanoTime();
                        sendBatch(context, batch).get();
                        try { if (recentCount == 0) sd.acknowledgeImages(batch.stream().filter(path -> {
                            Path owner = path.getParent().getParent();
                            return owner == null || !previewTasks.contains(owner.getFileName().toString());
                        }).toList()); }
                        catch (Exception e) {
                            throw new IOException("图片已发送并收到确认，但领取记录保存失败；下次 /get 可能重发整条记录：" + error(e), e);
                        }
                        delivered += batch.size();
                        Log.info("图片发送成功（" + describeConversation(context) + "）：第 " + batchNumber + " 批 "
                                + batch.size() + " 张，耗时 " + millis(started) + " ms");
                        }
                        notice = (recentCount > 0 ? "历史图片回溯完成，共 " : "本次领取完成，共 ") + delivered + " 张。" + (previewTasks.isEmpty() ? "" : "\n未完成任务的图片仅预览，仍保留在待领取列表。");
                        quietSuccess = quietReceipts(context);
                        markImagesDelivered(context);
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    failed = true;
                    notice = recentCount > 0 ? "历史图片发送失败：本次已确认发送 " + delivered + " 张，历史文件及待领取列表未修改。\n" + error(e)
                            : "领取图片失败：本次已确认领取 " + delivered + " 张，未完成的图片已保留。\n" + error(e);
                    Log.error("领取图片失败（" + describeConversation(context) + "，已确认 " + delivered + " 张）", e);
                } finally {
                    if (automaticImages == null) drainingImages.set(false);
                    // 先记候选（发送失败是"有事"：最高优先级，照旧发出来），再放掉这一次占用：
                    // 放完就定稿，所以顺序不能颠倒。
                    if (notice != null && !quietSuccess) replyRanked(context, failed ? ReceiptRank.FAILURE : ReceiptRank.AFFIRM, notice);
                    releaseNormalWindow(context);
                }
            });
        } catch (RejectedExecutionException e) {
            if (automaticImages == null) drainingImages.set(false);
            releaseNormalWindow(context);
            throw new IllegalStateException("机器人正在关闭，请稍后重试。");
        }
    }
    /**
     * 一批图片的发送方式：多于一张时合成一条「合并转发」（每张图一个节点），单张图保持普通发送。
     * 返回的 future 在传输层确认之后才完成——调用方据此决定是否 acknowledge。
     *
     * <p>会话可以用 {@code .imgmode} 改掉这套默认规则：single 一律逐张普通发送、record 一律合并转发（一张也合并）。
     * 没有设置过（含老配置）就是 auto，也就是下面这段原本的规则，行为一字未改。
     */
    private CompletableFuture<Void> sendBatch(JsonObject event, List<Path> batch) throws IOException {
        Settings.ImageSendMode mode = settings.imageSendMode(ChatService.conversationKey(event));
        if (mode == Settings.ImageSendMode.SINGLE) {
            Log.info("图片发送方式（" + describeConversation(event) + "）：普通发送 " + batch.size() + " 张（按 .imgmode single）");
            return sendGeneratedIndividually(event, batch);
        }
        if (mode == Settings.ImageSendMode.RECORD) return sendBatchAsRecord(event, batch);
        if (batch.size() <= 1) {
            Log.info("图片发送方式（" + describeConversation(event) + "）：单张普通发送");
            return sender.send(event, Maps.localImages(batch));
        }
        if (!forwardRecords) {
            Log.info("图片发送方式（" + describeConversation(event) + "）：普通发送 " + batch.size() + " 张（传输层不支持合并转发）");
            return sender.send(event, Maps.localImages(batch));
        }
        Log.info("图片发送方式（" + describeConversation(event) + "）：合并转发 " + batch.size() + " 张");
        // 每个节点只含一张图；仍用本地文件引用（不把图片字节塞进 JSON），顺序与 batch 一致。
        List<JsonArray> nodes = new ArrayList<>(batch.size());
        for (Path path : batch) nodes.add(Maps.localImages(List.of(path)));
        // 合并转发失败（比如 NapCat 不支持 send_group_forward_msg、节点格式被拒）绝不能把图丢了：
        // 回退到普通发送，且返回的 future 就是回退那次发送的结果——回退成功调用方照常 acknowledge，
        // 回退也失败才把失败抛给调用方（不 acknowledge）。这里只回退一次，不会重试。
        return sendImagesAsRecord(event, batch, nodes);
    }
    /**
     * {@code .imgmode record}：一张也合成一条「合并转发」。
     *
     * <p>传输层没实现合并转发时如实回退普通发送——日志说明原因，绝不假装成功。回退语义与 auto 一致：
     * 回退成功调用方照常 acknowledge（图确实发出去了），回退也失败才把失败抛给调用方（不 acknowledge）。
     */
    private CompletableFuture<Void> sendBatchAsRecord(JsonObject event, List<Path> batch) throws IOException {
        if (!forwardRecords) {
            Log.warn("图片发送方式（" + describeConversation(event) + "）：普通发送 " + batch.size()
                    + " 张（传输层不支持合并转发，.imgmode record 无法生效，已如实回退）");
            return sender.send(event, Maps.localImages(batch));
        }
        Log.info("图片发送方式（" + describeConversation(event) + "）：合并转发 " + batch.size() + " 张（按 .imgmode record）");
        // 每个节点只含一张图；仍用本地文件引用（不把图片字节塞进 JSON），顺序与 batch 一致。
        List<JsonArray> nodes = new ArrayList<>(batch.size());
        for (Path path : batch) nodes.add(Maps.localImages(List.of(path)));
        return sendImagesAsRecord(event, batch, nodes);
    }
    /**
     * 一批图片的合并转发，以及失败之后的处理——auto 与 {@code .imgmode record} 共用这一段，避免两处走样。
     *
     * <p>两种失败必须分开（线上事故的核心）：
     * <ul>
     *   <li><b>确定被拒</b>（NapCat 回了 retcode，比如不支持 {@code send_group_forward_msg}、节点格式被拒）：
     *       这批图确实没发出去，照旧逐张普通发送兜底，返回回退那次的结果（回退成功调用方照常 acknowledge）。</li>
     *   <li><b>结果未知</b>（API 响应超时、连接中断）：合并转发**可能已经成功**。此时再逐张发一遍，
     *       用户就会看到同一批图出现两遍（线上 {@code 07:44:36 → 07:45:06} 的 10 张图就是这样）。
     *       所以这里只发一条纯文字说明「可能已发出，未自动重发」，**绝不重发**，
     *       并把这个不确定当成"这次发送已经结束"返回给调用方——图可能真的在群里，不能让调用方再补一份。</li>
     * </ul>
     * 宁可少发一次，也不许同一批出现两遍。
     */
    private CompletableFuture<Void> sendImagesAsRecord(JsonObject event, List<Path> batch, List<JsonArray> nodes) {
        return sender.sendRecord(event, nodes).handle((ignored, failure) -> {
            if (failure == null) return CompletableFuture.<Void>completedFuture(null);
            if (deliveryUnknown(failure)) {
                Log.warn("合并转发结果未知（可能已发出），未自动重发 " + batch.size() + " 张（"
                        + describeConversation(event) + "）：" + error(failure));
                reply(event, "合并转发失败，" + batch.size() + " 张图可能已发出（超时/连接中断，发送结果未知），未自动重发。"
                        + "如果确实没看到，请重新执行一次领取指令。");
                return CompletableFuture.<Void>completedFuture(null);
            }
            Log.warn("合并转发失败，已改为普通发送 " + batch.size() + " 张（" + describeConversation(event) + "）：" + error(failure));
            try { return sender.send(event, Maps.localImages(batch)); }
            catch (Exception fallbackFailure) { return CompletableFuture.<Void>failedFuture(fallbackFailure); }
        }).thenCompose(future -> future);
    }
    /**
     * 这个失败是不是"结果未知"（超时、连接中断、QQ 转异步）——也就是"可能已经发出去了"。
     *
     * <p>只有这一种失败不许重发：把"可能已发出"当成"没发出去"再发一遍，就是同一批内容出现两遍。
     * 传输层用 {@link cn.szu.bot.qq.DeliveryUnknownException} 标记它，这里把包装（CompletionException /
     * ExecutionException）拆开递归找；其它异常一律照旧走"确定失败"的兜底/上报路径。
     */
    static boolean deliveryUnknown(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof cn.szu.bot.qq.DeliveryUnknownException) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }
    /** {@code .imgmode single}：多张也逐张普通发送（一张一条消息），读下一张要等前一张确认。 */
    private CompletableFuture<Void> sendGeneratedIndividually(JsonObject event, List<Path> paths) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Path path : paths) chain = chain.thenCompose(ignored -> {
            try { return sender.send(event, Maps.localImages(List.of(path))); }
            catch (Exception failure) { return CompletableFuture.<Void>failedFuture(failure); }
        });
        return chain;
    }
    /**
     * {@code .imgmode} —— 控制出图会不会以「聊天记录」（QQ 合并转发）的形式发出，按会话持久保存。
     *
     * <p>不带参数看当前设置；record/聊天记录/合并转发/转发 一律合并转发（一张也合并），
     * single/普通/普通发送/单张/逐张 一律普通发送（多张也逐张），auto/自动/默认 回到默认自动规则。
     * 权限与 {@code .imgcnt}、{@code .size} 这类生成参数一致：任何会话成员都能改自己会话的设置。
     */
    private void imageMode(JsonObject event, String argument) throws Exception {
        String conversation = ChatService.conversationKey(event);
        if (!argument.isEmpty()) {
            Settings.ImageSendMode mode = Settings.ImageSendMode.parse(argument);
            if (mode == null) throw new IllegalArgumentException(
                    "用法：/imgmode、/imgmode record（聊天记录/合并转发/转发）、/imgmode single（普通/单张/逐张）、/imgmode auto（自动）");
            settings.setImageSendMode(conversation, mode);
            Log.info("图片发送形式（" + conversation + "）：" + mode.key());
            reply(event, "本会话的图片发送形式已设为「" + mode.label() + "」：" + describeImageMode(mode));
            return;
        }
        Settings.ImageSendMode mode = settings.imageSendMode(conversation);
        reply(event, "本会话的图片发送形式：" + mode.label() + "（" + mode.key() + "）\n" + describeImageMode(mode)
                + "\n改法：/imgmode record（聊天记录/合并转发/转发）、/imgmode single（普通/单张/逐张）、/imgmode auto（自动）");
    }
    /** 一种发送形式给用户看的一句话说明；传输层做不到合并转发时补一句实情，别让人以为设置生效了。 */
    private String describeImageMode(Settings.ImageSendMode mode) {
        String detail = switch (mode) {
            case AUTO -> "自动规则（默认）：一批多于一张时合成一条聊天记录（合并转发），单张保持普通发送。";
            case RECORD -> "一律合并转发：只有一张也合成一条聊天记录。";
            case SINGLE -> "一律普通发送：多张也逐张发出去，不合成聊天记录。";
        };
        if (mode == Settings.ImageSendMode.RECORD && !forwardRecords)
            detail += "\n注意：当前传输层不支持合并转发（没实现 sendRecord），record 实际会回退成普通发送。";
        return detail;
    }
    /**
     * {@code .mode} —— QQ 侧回执档位，按会话持久保存（{@code qq_mode.modes.<会话键>}）。
     *
     * <p>不带参数看当前档位；normal/常规/简洁 只发"肯定"与图片，debug/调试/详细/完整 回到完整回执（默认）。
     * 权限与 {@code .imgmode}、{@code .imgcnt} 这类设置一致：任何会话成员都能改自己会话的设置。
     * 这条指令自己的回执在两个档位下都照发——用户明确问的，回答不能吞。
     */
    private void qqMode(JsonObject event, String argument) throws Exception {
        String conversation = ChatService.conversationKey(event);
        if (!argument.isEmpty()) {
            Settings.QqMode mode = Settings.QqMode.parse(argument);
            if (mode == null) throw new IllegalArgumentException(
                    "用法：/mode、/mode normal（常规/简洁）、/mode debug（调试/详细，默认）");
            settings.setQqMode(conversation, mode);
            Log.info("QQ 回执档位（" + conversation + "）：" + mode.key());
            reply(event, "本会话的回执档位已设为「" + mode.label() + "」：" + describeQqMode(mode));
            return;
        }
        Settings.QqMode mode = settings.qqMode(conversation);
        reply(event, "本会话的回执档位：" + mode.label() + "（" + mode.key() + "）\n" + describeQqMode(mode)
                + "\n改法：/mode normal（常规/简洁）、/mode debug（调试/详细，默认）");
    }
    /** 一种回执档位给用户看的一句话说明。 */
    private String describeQqMode(Settings.QqMode mode) {
        return switch (mode) {
            case DEBUG -> "调试（默认，现状）：完整回执——出图参数、多步执行过程与领取计数都会发出来。";
            case NORMAL -> "常规：只发一句肯定与图片；出图参数、入队/多步/领取计数这些过程回执不再发。"
                    + "失败、被拒绝、权限不足、用法错误这些说明照旧发出。";
        };
    }
    /**
     * 这个会话是不是常规档（{@code .mode normal}）：只发"肯定"与图片。
     * 没有设置过（含老配置）就是 false＝debug＝现状，一行都不变。
     */
    private boolean normalReceipts(JsonObject event) {
        return settings.qqMode(ChatService.conversationKey(event)) == Settings.QqMode.NORMAL;
    }
    /**
     * 这条消息是不是"要经 QQ 发出去"的指令回执。网页控制台的回执走自己的收集器（存档 + /quest/#N），
     * 不经过 QQ：常规档在那里一律不合并——控制台看到的永远完整。存档与 QQ 回执是两条路，互不影响。
     */
    private boolean qqReceipts(JsonObject event) {
        return webCaptureFor(event) == null;
    }
    /** 常规档 + 走 QQ：可以收成一条的指令回执。 */
    private boolean quietReceipts(JsonObject event) {
        return normalReceipts(event) && qqReceipts(event);
    }
    static List<List<Path>> imageBatches(List<Path> paths, int limit) {
        if (limit < 1) throw new IllegalArgumentException("图片上限须为正整数。");
        // One /gen submission owns a durable task directory spanning all its SD calls.
        Map<String, List<Path>> tasks = new LinkedHashMap<>();
        for (Path path : paths) {
            Path parent = path.getParent(), owner = parent == null ? null : parent.getParent();
            String name = owner == null || owner.getFileName() == null ? "" : owner.getFileName().toString();
            String task = name.matches("task-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    ? owner.toString() : "legacy";
            tasks.computeIfAbsent(task, key -> new ArrayList<>()).add(path);
        }
        List<List<Path>> batches = new ArrayList<>();
        for (List<Path> task : tasks.values()) for (int start = 0; start < task.size();) {
            int end = start + Math.min(limit, task.size() - start);
            batches.add(List.copyOf(task.subList(start, end))); start = end;
        }
        return batches;
    }
    /**
     * 回溯（{@code .rg}）的分批：只按 {@code .imgcnt} 切块，不按生成任务分开发送。
     *
     * <p>历史图片基本上一张一个 {@code task-} 目录（每次 {@code /gen} 一个），按任务切会让一次回溯
     * 变成一串单张消息，{@code .imgmode record} 也就无从把回溯的几张合成一条聊天记录。
     * 取哪些图／取几张／顺序都不变，这里只管"发送形式"这一层的分批。
     */
    static List<List<Path>> historyBatches(List<Path> paths, int limit) {
        if (limit < 1) throw new IllegalArgumentException("图片上限须为正整数。");
        List<List<Path>> batches = new ArrayList<>();
        for (int start = 0; start < paths.size(); start += limit)
            batches.add(List.copyOf(paths.subList(start, Math.min(paths.size(), start + limit))));
        return batches;
    }
    private synchronized boolean duplicate(JsonObject event) {
        if (!event.has("message_id")) return false;
        String key = Json.str(event, "self_id", "") + ":" + Json.str(event, "message_type", "") + ":" + Json.str(event, "group_id", "") + ":" + Json.str(event, "user_id", "") + ":" + event.get("message_id");
        if (!seen.add(key)) return true;
        if (seen.size() > 4096) { Iterator<String> iterator = seen.iterator(); iterator.next(); iterator.remove(); }
        return false;
    }
    /** Splits "旧 新" or "\"旧名\" \"新名\"" into exactly two names. */
    static String[] renameNames(String value, String usage) {
        String text = value.strip();
        Matcher quoted = Pattern.compile("^\"((?:[^\"\\\\]|\\\\.)*)\"\\s+\"((?:[^\"\\\\]|\\\\.)*)\"$").matcher(text);
        if (quoted.matches()) return new String[]{ unescapeName(quoted.group(1)), unescapeName(quoted.group(2)) };
        String[] parts = text.split("\\s+", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) throw new IllegalArgumentException(usage);
        return new String[]{ parts[0].strip(), parts[1].strip() };
    }
    private static String unescapeName(String value) { return value.replace("\\\"", "\"").replace("\\\\", "\\"); }
    /** Expands "#6-#9,#12" (or plain names) into real style names of the caller's last style list. */
    private List<String> styleTargets(JsonObject event, String spec) throws Exception {
        List<String> names = new ArrayList<>();
        for (String token : spec.split("[,\uFF0C\u3001]")) {
            String part = token.strip();
            if (part.isEmpty()) continue;
            Matcher range = Pattern.compile("^#(\\d+)\\s*[-~至]\\s*#?(\\d+)$").matcher(part);
            if (range.matches()) {
                int from = Integer.parseInt(range.group(1)), to = Integer.parseInt(range.group(2));
                if (from > to) { int swap = from; from = to; to = swap; }
                if (to - from > 199) throw new IllegalArgumentException("一次最多处理 200 个样式：" + part);
                for (int index = from; index <= to; index++) names.add(select(event, "style", "#" + index));
            } else if (part.startsWith("#")) {
                names.add(select(event, "style", part));
            } else {
                names.add(stripQuotes(part));
            }
        }
        if (names.isEmpty()) throw new IllegalArgumentException("请提供样式名称或 #编号（支持 #6-#9 与逗号分隔）。");
        return List.copyOf(new java.util.LinkedHashSet<>(names));
    }
    private static String stripQuotes(String value) {
        String text = value.strip();
        if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\""))
            return text.substring(1, text.length()-1).replace("\\\"", "\"").replace("\\\\", "\\");
        return text;
    }
    private static String reportBatch(String label, List<String> done, List<String> failed, List<String> skipped) {
        StringBuilder text = new StringBuilder(label + "完成：" + done.size() + " 项");
        if (!done.isEmpty()) text.append("\n").append(String.join("\n", done));
        if (!skipped.isEmpty()) text.append("\n已跳过 ").append(skipped.size()).append(" 项：\n").append(String.join("\n", skipped));
        if (!failed.isEmpty()) text.append("\n失败 ").append(failed.size()).append(" 项：\n").append(String.join("\n", failed));
        return text.toString();
    }
    private static String percent(double value) { return Math.round(value*100)+"%"; }
    private static double parsePercent(String command,String raw) {
        int number;
        try { number=Integer.parseInt(raw.strip()); }
        catch(NumberFormatException e) { throw new IllegalArgumentException("用法："+command+" <0-100>"); }
        if(number<0||number>100) throw new IllegalArgumentException("用法："+command+" <0-100>");
        return number/100.0;
    }    private static String describeConversation(JsonObject event) {
        return "group".equals(Json.str(event, "message_type", ""))
                ? "群 " + Json.str(event, "group_id", "") : "私聊 " + Json.str(event, "user_id", "");
    }
    /** The calling user's own prompt copy. */
    private static String promptScope(JsonObject event) {
        return UserPromptStore.scopeOf(Json.str(event, "user_id", ""));
    }
    /** Style texts are an optional correction source for personal prompt edits. */
    private List<String> styleTerms() {
        try {
            List<String> terms = new ArrayList<>();
            for (SdClient.StylePrompt style : sd.stylePrompts()) { terms.add(style.positive()); terms.add(style.negative()); }
            return terms;
        } catch (Exception e) { return List.of(); }
    }
    /**
     * 这段文本里有没有汉字。prompt 里只允许标准英文词条，所以 `.prompt`/`.promptR` 的 add/set/remove
     * 取值一旦含汉字就不走直接写入，改成交给 .infix 让改写模型处理（见 {@link #infix}）。
     */
    private static boolean hasHan(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.codePoints().anyMatch(value -> Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN);
    }
    private static long millis(long startedNanos) { return Math.round((System.nanoTime() - startedNanos) / 1_000_000.0); }
    // ---------------------------------------------------------------------------------------------
    // WebUI 桥：网页控制台是机器人的另一个入口，走的仍是同一套指令、权限、守卫与回执。
    // ---------------------------------------------------------------------------------------------
    /** 一条网页指令（或一次网页对话的执行链）产生的回执：按<b>出站消息</b>分组，组内文字与图片保持原顺序。 */
    /** 任务号的发号器：/quest/#22 里的 22 就是它，从 1 开始，进程内唯一。 */
    private static final java.util.concurrent.atomic.AtomicInteger WEB_QUEST_SEQ = new java.util.concurrent.atomic.AtomicInteger();
    public static final class WebCapture {
        private final String id, command;
        /** 这条回执的任务号：网页用 /quest/#N 直接定位它。 */
        private final int number = WEB_QUEST_SEQ.incrementAndGet();
        private final List<String> texts = new CopyOnWriteArrayList<>();
        private final List<JsonObject> images = new CopyOnWriteArrayList<>();
        /** 每条出站消息一组（组内是有序的 text / image 片段）：LoRA 搜索就是一条一项，回执也照这个渲染。 */
        private final List<JsonArray> messages = new CopyOnWriteArrayList<>();
        private final long startedNanos = System.nanoTime();
        /** 开始时刻的墙钟（回执正文落盘时的 startedAt）：与 startedNanos 一起记，进程内相对时间照旧。 */
        private final long startedMillis = System.currentTimeMillis();
        /**
         * 正文有变化（来了文字/图片、指令跑完）还没写进 data/quests/&lt;number&gt;.json。
         * 落盘成功后清掉；写失败就留着，下次再试——这样磁盘上的正文总是最新的一份。
         */
        private volatile boolean bodyDirty;
        private volatile long lastActivityNanos = System.nanoTime();
        private volatile boolean closed;
        /** 这条指令的执行体已经跑完（回执可能还在陆续到达，见 quiet()）。 */
        private volatile boolean done;
        /** 网页最后一次取走回执的时间：取走之后的静默期不必再等，避免下一条指令被 409 挡住。 */
        private volatile long readNanos;
        /** 网页 /api/image 能读到的目录（data/generated）：内嵌图片要落盘才能显示。 */
        private final Path imageRoot;
        WebCapture(String id, String command) { this(id, command, null); }
        WebCapture(String id, String command, Path imageRoot) { this.id = id; this.command = command; this.imageRoot = imageRoot; }
        public String id() { return id; }
        public String command() { return command; }
        public int number() { return number; }
        /** 索引用的摘要信息：文字条数、图片条数、第一段文字（列表接口只回这些，不回正文数组）。 */
        public int textCount() { return texts.size(); }
        public int imageCount() { return images.size(); }
        public String firstText() { return texts.isEmpty() ? "" : texts.get(0); }
        /** 收下一条出站消息：文本进 texts、图片段进 images（扁平视图），同时按原顺序记进 {@link #messages}。 */
        void capture(JsonArray segments) {
            if (segments == null) return;
            JsonArray group = new JsonArray();
            for (JsonElement item : segments) {
                if (item == null || !item.isJsonObject()) continue;
                JsonObject segment = item.getAsJsonObject();
                String type = Json.str(segment, "type", "");
                JsonObject data = Json.obj(segment, "data");
                if ("text".equals(type)) {
                    String text = Json.str(data, "text", "");
                    if (!text.isBlank()) {
                        texts.add(text);
                        JsonObject node = new JsonObject();
                        node.addProperty("type", "text");
                        node.addProperty("text", text);
                        group.add(node);
                    }
                } else if ("image".equals(type)) {
                    String file = materialize(Json.str(data, "file", ""));
                    if (!file.isBlank()) {
                        JsonObject image = new JsonObject(); image.addProperty("file", file); images.add(image);
                        JsonObject node = new JsonObject();
                        node.addProperty("type", "image");
                        node.addProperty("file", file);
                        group.add(node);
                    }
                } else if ("record".equals(type) || "forward".equals(type)) {
                    // 合并转发里面每条消息都算一条：递归进去各自成组，与会话里看到的条数一致。
                    if (data.has("messages") && data.get("messages").isJsonArray())
                        for (JsonElement node : data.getAsJsonArray("messages")) if (node.isJsonObject()) capture(node.getAsJsonObject().getAsJsonArray("content"));
                }
            }
            if (!group.isEmpty()) { messages.add(group); bodyDirty = true; }
            lastActivityNanos = System.nanoTime();
        }
        public boolean closed() { return closed; }
        /**
         * 图片地址转成网页能读的形式：`base64://…`（地图、Civitai 展示图这种内嵌图片）落盘到
         * data/generated/webui/ 再给相对路径——/api/image 只认这个目录下的真实图片文件。
         * 别的形式（file:// 与 data/generated 下的相对路径）原样返回。
         */
        private String materialize(String file) {
            if (file == null || file.isBlank()) return "";
            if (!file.startsWith("base64://")) return file;
            if (imageRoot == null) return "";
            try {
                byte[] bytes = Base64.getDecoder().decode(file.substring("base64://".length()).replaceAll("\\s", ""));
                if (bytes.length == 0 || bytes.length > 20L * 1024 * 1024) return "";
                Path directory = imageRoot.resolve("webui");
                Files.createDirectories(directory);
                pruneWebImages(directory);
                Path target = directory.resolve(UUID.randomUUID() + "." + imageExtension(bytes));
                Files.write(target, bytes);
                return "data/generated/webui/" + target.getFileName();
            } catch (Exception error) {
                Log.warn("网页回执里的内嵌图片保存失败（该图不会显示在控制台）：" + error(error));
                return "";
            }
        }
        /** 网页内嵌图片的扩展名：按文件头认，避免用错扩展名被 /api/image 拒掉。 */
        private static String imageExtension(byte[] bytes) {
            if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "png";
            if (bytes.length > 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "gif";
            if (bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "webp";
            return "jpg";
        }
        /** 内嵌图片是临时的：超过一天的文件顺手删掉，别让控制台目录无限膨胀。 */
        private static void pruneWebImages(Path directory) {
            long deadline = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
            try (var files = Files.list(directory)) {
                for (Path path : files.toList()) {
                    try { if (Files.getLastModifiedTime(path).toMillis() < deadline) Files.deleteIfExists(path); }
                    catch (Exception ignored) { /* 删不掉就留着 */ }
                }
            } catch (Exception ignored) { /* 目录读不到不影响本次落盘 */ }
        }
        void close() { closed = true; }
        /** 指令执行体跑完：正文也算变了，落盘一次（之后异步补的图片还会再写）。 */
        void finish() { done = true; bodyDirty = true; }
        public boolean done() { return done; }
        /** 回执开始的墙钟毫秒（落盘用；相对时间仍看 {@link #ageMillis()}）。 */
        public long startedMillis() { return startedMillis; }
        /** 正文有变化还没落盘（{@link Bot#saveQuestBody} 写完会清掉）。 */
        boolean bodyDirty() { return bodyDirty; }
        void bodySaved() { bodyDirty = false; }
        public long ageMillis() { return millis(startedNanos); }
        public boolean quiet() { return System.nanoTime() - lastActivityNanos > TimeUnit.MILLISECONDS.toNanos(1200); }
        void read() { readNanos = System.nanoTime(); }
        /**
         * 回执已收完：指令跑完、有内容，并且"输出了 1.2 秒没动静"或"网页已经把最新回执取走"。
         */
        boolean settled() {
            if (!done || texts.isEmpty()) return false;
            return quiet() || readNanos >= lastActivityNanos;
        }
        public JsonObject json(boolean busy) {
            JsonObject result = new JsonObject();
            result.addProperty("id", id);
            result.addProperty("quest", number);
            result.addProperty("command", command);
            result.add("texts", Json.GSON.toJsonTree(new ArrayList<>(texts)));
            result.add("images", Json.GSON.toJsonTree(new ArrayList<>(images)));
            // 分组视图：前端按它把一条消息渲染成一张卡（一条 LoRA = 一段文字 + 一张封面，同一条）。
            result.add("messages", Json.GSON.toJsonTree(new ArrayList<>(messages)));
            result.addProperty("busy", busy);
            // 指令执行体是否已经跑完：前端要靠它判断"内容都打完了可以收工"，
            // 只看 busy 会在指令刚受理、回执还空着的那一刻就停止跟随（图片就打不出来了）。
            result.addProperty("done", done);
            result.addProperty("closed", closed);
            result.addProperty("ageMillis", ageMillis());
            return result;
        }
    }
    /** 内存里的回执收集器：正文另有磁盘副本（data/quests/&lt;number&gt;.json），淘汰了也能读回来。 */
    private final Map<String, WebCapture> webCaptures = new ConcurrentHashMap<>();
    /** 每个网页会话最新的一条指令：异步后续消息（生成完成、下载进度）落到它上面。 */
    private final Map<String, WebCapture> webCurrent = new ConcurrentHashMap<>();
    /**
     * 内存里最多保留多少条回执（按任务号淘汰最旧的）。
     * 淘汰**前**一定先把正文写进磁盘，所以 /api/quest 照样给得出完整内容。
     * 包级非 final：测试可以直接设小值来跑淘汰路径。
     */
    int webCaptureLimit = DEFAULT_WEB_CAPTURE_LIMIT;
    /** 内存里保留回执条数的默认上限。 */
    static final int DEFAULT_WEB_CAPTURE_LIMIT = 200;
    /** 回执索引：只存摘要与已读标记（正文在 data/quests/&lt;n&gt;.json），重启后列表还能看到。 */
    private final QuestIndex questIndex;
    /** 索引落盘文件（data/quests.json）。 */
    private final Path questFile;
    /** 回执正文落盘目录（data/quests/&lt;number&gt;.json）：回执不再过期，正文随时可读。 */
    private final Path questBodyDir;
    /** 索引写盘节流：距上次写盘不足 2 秒、且内容没变，就不写——900ms 一次的轮询不能把磁盘写爆。 */
    private static final long QUEST_SAVE_INTERVAL_MILLIS = 2000;
    private volatile long questSavedMillis;
    private volatile long questSavedRevision = -1;
    /** 正在执行这条指令的线程自己的回执：并发的多条指令不会互相串消息。 */
    private final ThreadLocal<WebCapture> webThreadCapture = new ThreadLocal<>();
    /** 控制台的指令并发执行（不再单线程排队），4 条同时跑足够用，也不会把机器压垮。 */
    private final ExecutorService webIO = Executors.newFixedThreadPool(4, task -> { Thread thread = new Thread(task, "pixiko-webui"); thread.setDaemon(true); return thread; });
    /**
     * 一条出站消息进哪个回执：正在执行这条指令的线程自己的收集器优先（并发时不串消息），
     * 异步后续（生成完成、下载进度、图片送达）落到该会话最新的一条上。不是网页消息时返回 null。
     */
    private WebCapture webCaptureFor(JsonObject event) {
        WebCapture mine = webThreadCapture.get();
        if (mine != null && !mine.closed()) return mine;
        // 异步步骤带着事件回来：按事件里的回执 id 精确归位（并发多条指令也不会串消息）。
        String bound = Json.str(event, WEB_CAPTURE_KEY, "");
        if (!bound.isEmpty()) {
            WebCapture capture = webCaptures.get(bound);
            if (capture != null && !capture.closed()) return capture;
        }
        WebCapture current = webCurrent.get(ChatService.conversationKey(event));
        return current == null || current.closed() ? null : current;
    }
    /**
     * 出站消息的中转：<b>禁言期间直接不发</b>，其余情况交给网页捕获器或传输层。
     *
     * <p>这里是所有出站消息唯一的必经之路（聊天回复、指令回执、生成好的图片、回执失败提示），
     * 所以禁言判断放在这一层就够，业务分支不用各自判断。
     */
    private Sender muteAware(Sender transport) {
        return new Sender() {
            @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                if (skipMuted(event, segments, "消息")) return CompletableFuture.completedFuture(null);
                return transport.send(event, segments).whenComplete((ignored, error) -> verifyMute(event, error));
            }
            @Override public CompletableFuture<Void> sendMap(JsonObject event, JsonArray segments) {
                if (skipMuted(event, segments, "地图")) return CompletableFuture.completedFuture(null);
                return transport.sendMap(event, segments).whenComplete((ignored, error) -> verifyMute(event, error));
            }
            @Override public CompletableFuture<Void> sendRecord(JsonObject event, List<JsonArray> messages) {
                if (skipMuted(event, messages.isEmpty() ? new JsonArray() : messages.get(0), "聊天记录")) {
                    return CompletableFuture.completedFuture(null);
                }
                return transport.sendRecord(event, messages).whenComplete((ignored, error) -> verifyMute(event, error));
            }
            @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) { return transport.callApi(action, params); }
        };
    }

    /** true = 这个群正在禁言，这次不发送（日志由 MuteGuard 去重）。 */
    private boolean skipMuted(JsonObject event, JsonArray segments, String what) {
        String group = Json.str(event, "group_id", "");
        if (group.isEmpty() || !mute.muted(group)) return false;
        String summary = segments == null ? "" : Log.text(Bot.messageText(segments));
        return mute.skip(group, what + (summary.isEmpty() ? "" : "：" + summary));
    }

    /** 群禁言通知：只认 group_ban；全员禁言与"机器人自己被禁言"都算，别人的禁言不关机器人的事。 */
    private void observeNotice(JsonObject event) {
        mute.selfId(Json.str(event, "self_id", ""));
        mute.observe(event);
    }

    /**
     * 发送失败之后回查一次：真的被禁言就静音到解除，只是别的失败（网络、风控、消息被拒）就照旧如实报错。
     * 回查按群每分钟最多一次，所以不会因为连续失败去刷 NapCat。
     */
    private void verifyMute(JsonObject event, Throwable error) {
        if (error == null) return;
        String group = Json.str(event, "group_id", "");
        if (group.isEmpty() || !"group".equals(Json.str(event, "message_type", ""))) return;
        if (!mute.shouldRecheck(group)) return;
        mute.markChecked(group);
        String self = mute.selfId().isEmpty() ? Json.str(event, "self_id", "") : mute.selfId();
        JsonObject member = new JsonObject();
        member.addProperty("group_id", group);
        if (!self.isEmpty()) member.addProperty("user_id", self);
        member.addProperty("no_cache", true);
        sender.callApi("get_group_member_info", member).whenComplete((data, failure) -> {
            if (failure == null && data != null && data.isJsonObject()) {
                Long until = shutUpUntil(data.getAsJsonObject());
                if (until != null) {
                    mute.learned(group, until, until > System.currentTimeMillis() ? "机器人被禁言中" : "");
                    return;
                }
            }
            // 成员信息里没有禁言字段时，再看看整个群是不是全员禁言。
            JsonObject params = new JsonObject();
            params.addProperty("group_id", group);
            sender.callApi("get_group_info", params).whenComplete((info, groupFailure) -> {
                if (groupFailure == null && info != null && info.isJsonObject() && groupAllShut(info.getAsJsonObject()))
                    mute.learned(group, System.currentTimeMillis() + MuteGuard.UNKNOWN_BAN_MILLIS, "全群禁言中");
            });
        });
    }

    /** 成员信息里的禁言截止时间（秒 → 毫秒）；没有这个字段返回 null（而不是"没禁言"）。 */
    static Long shutUpUntil(JsonObject member) {
        for (String key : new String[]{"shut_up_timestamp", "shutup_timestamp", "shut_up_time"}) {
            JsonElement value = member.get(key);
            if (value == null || !value.isJsonPrimitive()) continue;
            try {
                long seconds = value.getAsLong();
                if (seconds <= 0) return 0L;
                return seconds > 1_000_000_000_000L ? seconds : seconds * 1000L;
            } catch (RuntimeException ignored) { /* 换个字段名再试 */ }
        }
        return null;
    }

    /** 群信息里的"全员禁言"标记（不同实现对字段的叫法不一样，认识的都认）。 */
    static boolean groupAllShut(JsonObject group) {
        for (String key : new String[]{"all_shut", "group_all_shut", "all_shutup", "shut_all"}) {
            JsonElement value = group.get(key);
            if (value == null || !value.isJsonPrimitive()) continue;
            try {
                if (value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : value.getAsInt() != 0) return true;
            } catch (RuntimeException ignored) { /* 换个字段名再试 */ }
        }
        Long until = shutUpUntil(group);
        return until != null && until > System.currentTimeMillis();
    }

    /** 禁言状态（网页与日志用）。 */
    public JsonArray muteStatus() { return mute.snapshot(); }

    /** 测试用：直接拿到禁言守卫。 */
    MuteGuard muteGuard() { return mute; }
    /** 网页指令事件里带回执 id 的键（异步步骤据此把回执送回原来那条指令）。 */
    static final String WEB_CAPTURE_KEY = "_web_capture";
    /**
     * 出站消息的中转：网页会话正在收集回执时，消息交给收集器而不是 QQ；其余情况原样发给传输层。
     * 这样文本、图片、合并转发都用同一套捕获逻辑，不需要在每个业务分支里重复判断。
     */
    private Sender webAware(Sender transport) {
        return new Sender() {
            @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
                WebCapture capture = webCaptureFor(event);
                if (capture == null) return transport.send(event, segments);
                capture.capture(segments);
                observeQuest(capture);
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletableFuture<Void> sendMap(JsonObject event, JsonArray segments) {
                WebCapture capture = webCaptureFor(event);
                if (capture == null) return transport.sendMap(event, segments);
                capture.capture(segments);
                observeQuest(capture);
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletableFuture<Void> sendRecord(JsonObject event, List<JsonArray> messages) {
                WebCapture capture = webCaptureFor(event);
                if (capture == null) return transport.sendRecord(event, messages);
                for (JsonArray message : messages) capture.capture(message);
                observeQuest(capture);
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) { return transport.callApi(action, params); }
        };
    }
    /**
     * 网页会话使用的合成事件：私聊、身份固定为 "web"（网页自己的 scope，不含任何 QQ 号）。
     * 权限不再靠 QQ 身份判断——事件带 webui=true，而入口本身已经用访问令牌鉴权过。
     */
    JsonObject webEvent(String scope, String text) {
        String user = UserPromptStore.scopeOf(scope);
        JsonObject event = new JsonObject();
        event.addProperty("post_type", "message"); event.addProperty("message_type", "private");
        // self_id 必须与 user_id 不同，否则会被当成"自己发的消息"直接丢掉（accept 里有这道判断）。
        event.addProperty("self_id", WEB_SELF_ID); event.addProperty("user_id", user);
        event.addProperty("message_id", System.nanoTime());
        event.add("message", Maps.text(text));
        event.addProperty("raw_message", text);
        event.addProperty("webui", true);
        return event;
    }
    /** 网页会话的会话键（网页指令与 QQ 指令共用同一套选择列表/回执路由，但网页是独立会话）。 */
    public static String webConversationKey(String scope) {
        return WEB_SELF_ID + ":private:" + UserPromptStore.scopeOf(scope);
    }
    /** 网页合成事件的 self_id：只是一个稳定的本地标识，不对应任何 QQ 号。 */
    public static final String WEB_SELF_ID = "pixiko";
    /**
     * 网页会话最新的一条指令收集器（异步消息的落点）。
     *
     * <p>指令跑完（回执静默）之后就不再算"进行中"：网页只轮询、从不主动关闭，若一直算占用，
     * 下一条指令会永远收到 409「上一条指令还在执行」——实测正是这样把整个控制台卡死的。
     * 15 分钟仍未结束的收集器视为异常残留，一并放过，避免极端情况下再卡住。
     */
    public WebCapture webActive(String scope) {
        WebCapture capture = webCurrent.get(webConversationKey(scope));
        if (capture == null || capture.closed()) return null;
        if (capture.settled()) return null;
        if (capture.ageMillis() > TimeUnit.MINUTES.toMillis(15)) {
            Log.warn("WebUI 回执收集器超过 15 分钟仍未结束，按残留处理：" + capture.command());
            return null;
        }
        return capture;
    }
    /**
     * 网页发起一条指令：**立即受理、并发异步执行**，回执进这条指令自己的收集器，由网页轮询取走。
     * 不再要求"上一条跑完"：控制台上连点几个按钮、一次发多条命令互不阻塞，回执也各归各的。
     */
    public WebCapture webCommand(String scope, List<String> commands) {
        String label = String.join(" ; ", commands);
        WebCapture capture = new WebCapture(UUID.randomUUID().toString(), label,
                settings.root.resolve("data/generated"));
        JsonObject event = webEvent(scope, label);
        // 把回执 id 写进事件：异步步骤（LoRA 列表/下载、图片发送、生成完成）拿着同一份事件回来时，
        // 消息仍然进这条指令自己的回执，不会串到并发发出的另一条指令上。
        event.addProperty(WEB_CAPTURE_KEY, capture.id());
        webCaptures.put(capture.id(), capture);
        webCurrent.put(webConversationKey(scope), capture);
        observeQuest(capture);
        Log.info("WebUI 指令（" + scope + "）：" + Log.text(label) + "（并发执行中 " + webRunning() + " 条）");
        webIO.execute(() -> {
            Log.markWeb();   // 网页指令的执行日志进 web-*.log
            webThreadCapture.set(capture);
            try {
                ChatActions.validate(commands);
                executeChatCommands(event, commands, selectionContext(event));
                if (commands.size() == 1 && Boolean.TRUE.equals(chatDispatchFailed.get())) chatDispatchFailed.remove();
            } catch (Exception error) {
                Log.warn("WebUI 指令失败：" + error(error));
                capture.capture(Maps.text("操作失败：" + error(error)));
            } finally {
                // 指令体已结束：回执再静默 1.2 秒就算收完，网页可以随时发下一条。
                capture.finish();
                observeQuest(capture);
                webThreadCapture.remove();
                Log.clearWeb();
            }
        });
        return capture;
    }
    /**
     * 按**任务号**取一条回执（网页 /quest/#22）。
     *
     * <p>number ≤ 0 表示"最新一条"。回执正文不再有过期一说：还在内存就直接给，
     * 已经重启/被淘汰就从 {@code data/quests/&lt;number&gt;.json} 读回来（多一个 {@code fromDisk:true}），
     * 只有磁盘上也没有才如实说"不在了"。
     */
    public JsonObject webQuest(int number) {
        pruneWebCaptures();
        int wanted = number > 0 ? number : latestQuest();
        WebCapture capture = null;
        if (wanted > 0) {
            for (WebCapture item : webCaptures.values()) if (item.number == wanted) { capture = item; break; }
        }
        if (capture == null) {
            // 内容不在了也照样算"打开过"：索引里那一条（如果还在）标为已读，列表里不再算未读。
            markQuestRead(wanted);
            JsonObject stored = wanted > 0 ? questFromDisk(wanted) : null;
            if (stored != null) {
                stored.addProperty("latest", latestQuest());
                return stored;
            }
            JsonObject missing = new JsonObject();
            missing.addProperty("quest", number);
            missing.addProperty("latest", latestQuest());
            // 正文文件也没了（被索引上限裁掉或被手工删掉）时，索引里还有摘要就把摘要一起给出去：
            // /quest#N 这种直接打开的链接至少能看到"有过这么一条任务"，不用只吃一句报错。
            JsonObject stale = wanted > 0 ? questIndex.item(wanted) : null;
            if (stale != null) {
                stale.addProperty("quest", number);
                stale.addProperty("latest", latestQuest());
                stale.addProperty("error", "回执 #" + number + " 不在了：磁盘上没有正文文件（data/quests/"
                        + number + ".json），下面是索引里的摘要。");
                return stale;
            }
            missing.addProperty("error", number <= 0 ? "还没有任何任务回执。" : "回执 #" + number + " 不在了。");
            return missing;
        }
        capture.read();
        // 打开过就标为已读（已读不会再变回未读），并把最新状态刷进索引（顺带落一次正文快照）。
        markQuestRead(capture.number());
        observeQuest(capture);
        // "还在跑"必须把出图队列算进去：生成一张图要几十秒，只报 busy（DeepSeek/LoRA）
        // 会让回执页在生成期间就停表——用户就永远等不到那张图。
        JsonObject result = capture.json(webBusy() || generationQueued());
        result.addProperty("latest", latestQuest());
        return result;
    }
    /** 出图队列里还有任务吗（含正在生成的那一个）。 */
    public boolean generationQueued() {
        synchronized (generationLock) { return !generationJobs.isEmpty(); }
    }
    /** 最近一条回执的任务号（没有就是 0）：内存里没有就退回索引里的最大号（重启后照样有效）。 */
    public int latestQuest() {
        pruneWebCaptures();
        int latest = 0;
        for (WebCapture item : webCaptures.values()) latest = Math.max(latest, item.number);
        QuestIndex index = questIndex;
        if (index != null) latest = Math.max(latest, index.latest());
        return latest;
    }
    /**
     * 网页「任务回执列表」：索引里的摘要（最新在前，最多 limit 条，钳到 1..{@link QuestIndex#MAX_ENTRIES}）。
     *
     * <p>只回元信息与摘要，**不回任何正文数组**；正文一律在 {@code data/quests/&lt;n&gt;.json}，
     * 所以 {@code expired} 永远是 false、{@code retainedMinutes} 恒为 0（表示不过期）。
     */
    public JsonObject webQuests(int limit) {
        observeQuests();
        return questIndex.json(limit, 0);
    }
    /**
     * 批量标记已读：numbers 里的任务号（索引里没有的忽略），或者 all=true 全部。
     * 返回 {@code {"unread":N,"marked":M}}，marked 是本次真正从"未读"变"已读"的条数。
     */
    public JsonObject webMarkQuestsRead(Set<Integer> numbers, boolean all) {
        observeQuests();
        int marked = all ? questIndex.markAllRead() : questIndex.markRead(numbers);
        persistQuests(true);
        JsonObject result = new JsonObject();
        result.addProperty("unread", questIndex.unread());
        result.addProperty("marked", marked);
        return result;
    }
    /**
     * 刷新索引里还活着的回执（列表与状态接口都先走这一步），把正文写进磁盘，再按节流落盘索引。
     * 先 prune：内存超上限时淘汰最旧的几条（正文早已落盘，列表里那一条只是 busy 变 false）。
     */
    private void observeQuests() {
        pruneWebCaptures();
        QuestIndex index = questIndex;
        if (index == null) return;
        index.begin();
        for (WebCapture capture : webCaptures.values()) {
            index.observe(capture);
            saveQuestBody(capture);
        }
        sweepTrimmedQuests();
        persistQuests(false);
    }
    /** 一条回执变了（来了文字/图片、指令跑完）：立刻刷新索引与磁盘正文，落盘节流只管索引。 */
    private void observeQuest(WebCapture capture) {
        QuestIndex index = questIndex;
        if (index == null || capture == null) return;
        index.observe(capture);
        saveQuestBody(capture);
        sweepTrimmedQuests();
        persistQuests(false);
    }
    /**
     * 一条回执的正文落盘：{@code data/quests/&lt;number&gt;.json}（{@link Json#atomicWrite}，UTF-8 无 BOM）。
     *
     * <p>写的是**引用**不是图片字节：{@code images}/{@code messages} 里的 {@code file} 就是网页
     * {@code /api/image} 能读到的那个相对路径，和 {@link WebCapture#json(boolean)} 里一样。
     * 正文没变化（{@link WebCapture#bodyDirty()} 为 false）就完全不写。
     *
     * <p>只写正文不写摘要：摘要在 {@code data/quests.json} 索引里，两者互相独立，谁坏了都不连累对方。
     */
    void saveQuestBody(WebCapture capture) {
        if (capture == null || questBodyDir == null || !capture.bodyDirty()) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("version", 1);
            body.addProperty("number", capture.number);
            body.addProperty("command", capture.command());
            body.addProperty("startedAt", QuestIndex.stamp(capture.startedMillis()));
            body.addProperty("done", capture.done());
            body.add("texts", Json.GSON.toJsonTree(new ArrayList<>(capture.texts)));
            body.add("images", Json.GSON.toJsonTree(new ArrayList<>(capture.images)));
            body.add("messages", Json.GSON.toJsonTree(new ArrayList<>(capture.messages)));
            Json.atomicWrite(questBodyFile(capture.number), body);
            capture.bodySaved();
        } catch (Exception error) {
            // 写失败只写日志：回执本身照旧在内存里、照旧能看，下次有变化再试。
            Log.warn("网页回执正文写入失败（回执本身不受影响）：" + error(error));
        }
    }
    /**
     * 从 {@code data/quests/&lt;number&gt;.json} 读回一条回执的**完整正文**（字段名与
     * {@link WebCapture#json(boolean)} 一致，另加 {@code fromDisk:true} 便于排查）。
     *
     * <p>没有这个文件、或者文件坏了都返回 null（坏文件只写日志，当作"没有正文"）。
     */
    private JsonObject questFromDisk(int number) {
        Path file = questBodyFile(number);
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            JsonObject stored = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            JsonObject result = new JsonObject();
            // 磁盘上的回执没有活着的收集器 id：给一个稳定可读的标识，字段本身照旧保留。
            result.addProperty("id", "quest-" + number);
            result.addProperty("quest", number);
            result.addProperty("command", Json.str(stored, "command", ""));
            result.add("texts", jsonArray(stored, "texts"));
            result.add("images", jsonArray(stored, "images"));
            result.add("messages", jsonArray(stored, "messages"));
            result.addProperty("busy", false);
            result.addProperty("done", Json.bool(stored, "done", true));
            // 磁盘上没有"网页正在接收它的消息"这回事：收集器等同于已关闭。
            result.addProperty("closed", true);
            long started = QuestIndex.parseStamp(Json.str(stored, "startedAt", ""));
            result.addProperty("ageMillis", started <= 0 ? 0 : Math.max(0, System.currentTimeMillis() - started));
            // 回执不再过期：磁盘上读回来的正文同样是"还活着的内容"。
            result.addProperty("expired", false);
            result.addProperty("fromDisk", true);
            return result;
        } catch (Exception error) {
            Log.warn("网页回执正文读取失败（当作没有正文）：" + error(error));
            return null;
        }
    }
    /** 磁盘正文里的数组字段：缺失或类型不对就给空数组（半截文件也不至于抛异常）。 */
    private static JsonArray jsonArray(JsonObject stored, String key) {
        return stored.has(key) && stored.get(key).isJsonArray() ? stored.getAsJsonArray(key) : new JsonArray();
    }
    /** 一条回执的正文文件（data/quests/&lt;number&gt;.json）。 */
    private Path questBodyFile(int number) {
        return questBodyDir == null || number <= 0 ? null : questBodyDir.resolve(number + ".json");
    }
    /**
     * 索引裁掉的最旧那批：连 {@code data/quests/&lt;n&gt;.json} 一起删。
     * 索引都不留的号，正文文件留着只会变成没人引用的孤儿。
     */
    private void sweepTrimmedQuests() {
        QuestIndex index = questIndex;
        if (index == null) return;
        for (int number : index.drainTrimmed()) {
            Path file = questBodyFile(number);
            if (file == null) continue;
            try { Files.deleteIfExists(file); }
            catch (Exception error) { Log.warn("旧回执正文删除失败（留着不影响使用）：" + error(error)); }
        }
    }
    /**
     * 磁盘上回执正文文件里的最大任务号（quests.json 丢了也不会在重启后撞号）。
     * 包级可见：启动路径用它抬发号器，测试也直接验证这一句。
     */
    int latestQuestBodyNumber() {
        if (questBodyDir == null) return 0;
        int latest = 0;
        try (var files = Files.list(questBodyDir)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".json")) continue;
                try { latest = Math.max(latest, Integer.parseInt(name.substring(0, name.length() - ".json".length()))); }
                catch (NumberFormatException ignored) { /* 不是任务号的文件忽略 */ }
            }
        } catch (Exception ignored) { /* 目录不存在或读不到：就当没有磁盘正文 */ }
        return latest;
    }
    /**
     * 索引落盘（节流）：内容没变就完全不写，变了也最多每 2 秒写一次——
     * 网页 900ms 一次的轮询不会把磁盘写爆，下一轮轮询会把还没落盘的变化补上。
     */
    private void persistQuests(boolean force) {
        QuestIndex index = questIndex;
        if (index == null) return;
        long revision = index.revision();
        if (revision == questSavedRevision) return;
        long now = System.currentTimeMillis();
        if (!force && now - questSavedMillis < QUEST_SAVE_INTERVAL_MILLIS) return;
        questSavedRevision = revision;
        questSavedMillis = now;
        index.save(questFile);
    }
    /** 打开一条回执：把索引里对应任务号标为已读（没有的号忽略），真的变了就立刻落盘。 */
    private void markQuestRead(int number) {
        QuestIndex index = questIndex;
        if (index == null || number <= 0) return;
        if (index.markRead(List.of(number)) > 0) persistQuests(true);
    }
    /**
     * 建一条回执（内部接口触发的任务也得有回执，不然"每次任务一个回执"就漏了）。
     * 和 {@link #webCommand} 一样登记进 webCaptures，网页照常轮询、/quest/#N 也能看。
     */
    private WebCapture newCapture(String label) {
        WebCapture capture = new WebCapture(UUID.randomUUID().toString(), label, settings.root.resolve("data/generated"));
        webCaptures.put(capture.id(), capture);
        observeQuest(capture);
        return capture;
    }
    /** 正在执行的网页指令条数（日志与网页面板用）。 */
    public int webRunning() {
        int running = 0;
        for (WebCapture capture : webCaptures.values()) if (!capture.done()) running++;
        return running;
    }
    public WebCapture webCapture(String id) {
        pruneWebCaptures();
        WebCapture capture = webCaptures.get(id);
        if (capture != null) {
            capture.read();
            // 按 id 取回执也是"打开过"：同样标为已读。
            markQuestRead(capture.number());
            observeQuest(capture);
        }
        return capture;
    }
    /**
     * 内存回收：回执**不再按时间过期**（正文已经写进 {@code data/quests/&lt;number&gt;.json}），
     * 这里只把内存里的收集器限在最近 {@link #webCaptureLimit} 条——按任务号淘汰最旧的，
     * 淘汰**前**先 {@link #saveQuestBody}，所以淘汰之后 {@code /api/quest} 照样给得出完整正文。
     */
    private void pruneWebCaptures() {
        int limit = Math.max(1, webCaptureLimit);
        while (webCaptures.size() > limit) {
            WebCapture oldest = null;
            for (WebCapture capture : webCaptures.values()) {
                if (oldest == null || capture.number < oldest.number) oldest = capture;
            }
            if (oldest == null) break;
            saveQuestBody(oldest);
            webCaptures.remove(oldest.id());
        }
        webCurrent.values().removeIf(capture -> !webCaptures.containsKey(capture.id()));
    }
    /**
     * 关闭收集器：之后这个会话的消息重新发往 QQ（网页不再接收）。
     *
     * <p>回执本身**留着**——它现在是 /quest/#N 这种可分享的链接，关掉收集不该让链接失效；
     * 内存回收只按条数上限走（见 {@link #pruneWebCaptures()}），正文另有磁盘副本。
     * {@code closed} 标记已经足够让 {@link #webCaptureFor} 与 {@link #webActive} 不再把它当成活动回执。
     */
    public void webClose(String id) {
        WebCapture capture = webCapture(id);
        if (capture == null) return;
        capture.close();
        webCurrent.values().removeIf(value -> value == capture);
    }
    /** 是否还有后台工作在跑：网页据此决定要不要继续轮询。 */
    public boolean webBusy() { return activeChatWorkflows.get() > 0 || progenBusy.get() || loraBusy.get(); }
    /**
     * 网页对话：走聊天频道规划，返回自然回应，并（默认）把计划里的指令交给同一条回执通道执行。
     * 与 QQ 侧完全同源——包括"不许只说不做""编号必须核对"这些守卫。
     */
    public JsonObject webChat(String scope, String message, JsonArray history, boolean execute) throws Exception {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("消息不能为空。");
        JsonObject event = webEvent(scope, message);
        // 网页与 QQ 完全同源：编号消息也要先按"用户最近看过的列表"预取，否则网页里 "#2" 会缺上下文。
        primeNumberedSelections(event, speakableNumbers(message));
        ChatActions.Plan plan;
        try {
            // 与 QQ 侧同源："下载第 N 个"而搜索结果失效时如实说明，不要变成加载本机 LoRA。
            String stale = staleDownloadGuidance(event, message);
            // 与 QQ 侧同源：整句只是编号指代时直接由程序决定指令，别让模型在多份列表里挑错。
            // 修 bug 2：网页与 QQ 走同一条链路——「选择第二个然后生成」先拼成 载入 #N → .gen 两步。
            ChatActions.Plan chained = numberedFollowUpPlan(selectionContext(event), message);
            ShownSelection direct = chained == null ? resolveShownSelection(selectionContext(event), message) : null;
            ChatActions.Plan surgery = categorySurgeryPlan(message);
            if (stale != null) {
                plan = new ChatActions.Plan(stale, List.of(), "", 100);
            } else if (surgery != null) {
                plan = surgery;
            } else if (chained != null) {
                Log.info("编号指代 + 后续出图，拼成一条链路（网页）：" + chained.commands());
                plan = chained;
            } else if (direct != null && direct.clarification()) {
                // 编号与名字对不上（或名字有歧义）：程序直接问清楚，绝不执行。
                Log.info("编号与用户说法对不上，改为澄清：" + direct.label());
                plan = new ChatActions.Plan(direct.label(), List.of(), "", 100);
            } else if (direct != null) {
                plan = new ChatActions.Plan("好，就按你刚看过的列表来：" + direct.label(), List.of(direct.command()), "", 100);
            } else {
                // K2/K3/K4：网页端与 QQ 同源——把好感度分档、情绪与这轮该用的敏感话题反应一起交给规划层。
                String stateHint = chat.personaHint(scope, message);
                String voice = stateHint.isBlank() ? settings.chatPersonality() : settings.chatPersonality() + "\n\n" + stateHint;
                plan = DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.CHAT)
                        .chatPlan(voice, history, speakableNumbers(message), selectionContext(event),
                                speakerFor(scope), cn.szu.bot.chat.KotoriCorpus.referenceFor(settings, speakableNumbers(message),
                                        history == null ? 0 : history.size() / 2));
                // 与 QQ 侧同样的兜底：计划为空但用户在指代刚看过的编号列表时，强调一次再规划。
                if (plan.commands().isEmpty()) {
                    JsonObject choices = selectionContext(event);
                    String nudge = selectionNudge(choices, message);
                    if (nudge != null) {
                        ChatActions.Plan retry = DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.CHAT)
                                .chatPlan(settings.chatPersonality(), history, speakableNumbers(message) + "\n" + nudge,
                                        choices, speakerFor(scope), cn.szu.bot.chat.KotoriCorpus.referenceFor(settings, speakableNumbers(message),
                                                history == null ? 0 : history.size() / 2));
                        if (!retry.commands().isEmpty()) plan = retry;
                    }
                }
            }
        } catch (Exception error) {
            if (webActive(scope) != null) webClose(webActive(scope).id());
            throw error;
        }        JsonObject result = new JsonObject();
        // 回复（或规划）之后落人格状态：好感度/情绪按 K4 触发表与模型给的字段更新。
        chat.applyPersonaState(scope, message, plan);
        result.addProperty("reply", Bot.publicCommands(plan.reply()));
        result.add("commands", Json.GSON.toJsonTree(plan.commands()));
        result.addProperty("interest", plan.interest());
        if (execute && !plan.commands().isEmpty()) {
            WebCapture capture = webCommand(scope, plan.commands());
            result.addProperty("captureId", capture.id());
            result.addProperty("quest", capture.number());
        }
        return result;
    }
    private JsonObject speakerFor(String scope) {
        JsonObject speaker = new JsonObject();
        speaker.addProperty("id", UserPromptStore.scopeOf(scope));
        speaker.addProperty("display_name", "网页端");
        speaker.addProperty("role", "owner");
        return speaker;
    }
    /**
     * 网页接口 {@code POST /api/infix/mode}：读/写一个会话的 infix 档位（界面代理的按钮绑这个）。
     *
     * <p>{@code scope} 省略时由调用方传当前网页会话。{@code mode} 支持 {@code global} / {@code parts}
     * （也认中文 全局/分组）；**非法值一律拒绝（400）且不改动已存值**——配置与按钮状态不会因为一次误点而变。
     * 返回 {@code {"mode":…,"default":"global","scope":…}}。
     */
    public JsonObject setInfixMode(String scope, String mode) throws Exception {
        String resolved = (scope == null || scope.isBlank()) ? settings.webScope() : scope.strip();
        String conversation = webConversationKey(resolved);
        String wanted = mode == null ? "" : mode.strip();
        Settings.InfixMode parsed = Settings.InfixMode.parse(wanted);
        if (parsed == null)
            throw new IllegalArgumentException("mode 只能是 global 或 parts（中文也认：全局/分组）。");
        settings.setInfixMode(conversation, parsed);
        Log.info("WebUI infix 档位（" + resolved + "）：" + parsed.key());
        JsonObject result = new JsonObject();
        result.addProperty("mode", parsed.key());
        result.addProperty("default", Settings.InfixMode.GLOBAL.key());
        result.addProperty("scope", resolved);
        return result;
    }
    /**
     * 网页状态面板的数据。网页与 QQ 两侧独立：这里只报网页自己用得上的东西
     * （模型/生成参数/队列/图片/词库约束/Civitai 账号），不含 owner QQ、admin 名单、地图等 QQ 侧信息。
     */
    public JsonObject webStatus() throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("botName", settings.botName());
        result.addProperty("scope", settings.webScope());
        result.addProperty("webPort", settings.webPort());
        result.addProperty("path", settings.root.toString());
        result.addProperty("time", java.time.LocalDateTime.now().toString());
        JsonObject chat = new JsonObject();
        chat.addProperty("global", settings.chatEnabled());
        chat.addProperty("frequency", settings.chatFrequency());
        chat.addProperty("contextSeconds", settings.chatContextSeconds());
        chat.addProperty("personalityChars", settings.chatPersonality().length());
        chat.addProperty("personality", settings.chatPersonality());
        result.add("chat", chat);
        result.add("chatChannel", channelJson(DeepSeekPrompts.Channel.CHAT));
        result.add("imageChannel", channelJson(DeepSeekPrompts.Channel.IMAGE));
        // L4：只读展示好感度与情绪（只影响语气与亲密度，不影响照不照做）。
        try {
            cn.szu.bot.chat.PersonaState state = cn.szu.bot.chat.PersonaState.of(settings.root);
            int score = state.affinity(settings.webScope());
            cn.szu.bot.chat.PersonaState.Mood mood = state.mood(settings.webScope());
            JsonObject affinity = new JsonObject();
            affinity.addProperty("score", score);
            affinity.addProperty("tier", cn.szu.bot.chat.PersonaState.tier(score));
            result.add("affinity", affinity);
            JsonObject moodJson = new JsonObject();
            moodJson.addProperty("mood", mood.mood());
            moodJson.addProperty("intensity", mood.intensity());
            moodJson.addProperty("updatedAt", mood.updatedAt());
            result.add("mood", moodJson);
        } catch (Exception personaError) {
            Log.warn("网页状态里的好感度/情绪读取失败（不影响其余面板）：" + error(personaError));
        }
        SdClient.GenerationSettings current = sd.settings();
        JsonObject generation = new JsonObject();
        generation.addProperty("sampler", current.samplerName());
        generation.addProperty("width", current.width());
        generation.addProperty("height", current.height());
        generation.addProperty("source", current.source());
        generation.add("styles", Json.GSON.toJsonTree(current.styles()));
        generation.addProperty("model", sd.parameters().checkpoint());
        generation.addProperty("steps", sd.parameters().steps());
        generation.addProperty("cfg", sd.parameters().cfgScale());
        generation.addProperty("seed", sd.parameters().seed());
        generation.addProperty("status", generationStatus());
        result.add("generation", generation);
        result.addProperty("infixFilter", settings.infixFilterEnabled(webConversationKey(settings.webScope())));
        // 网页的 infix 档位（界面代理的按钮绑定这个字段；写入口是 POST /api/infix/mode）。
        result.addProperty("infixMode", settings.infixMode(webConversationKey(settings.webScope())).key());
        result.addProperty("infixModeDefault", Settings.InfixMode.GLOBAL.key());
        result.addProperty("infixConflict", settings.infixConflictEnabled(webConversationKey(settings.webScope())));
        result.addProperty("imageCount", settings.imageCount());
        result.addProperty("autoGet", settings.autoGet());
        result.add("civitai", civitaiStatus());
        result.add("sd", sdStatus());
        result.add("endpoints", endpointStatus());
        result.add("mutes", mute.snapshot());
        // 这里只要词条个数：以前为了一个数字把整份提示词的"词条 + 中文释义"都算了一遍（几万条词库全表扫），
        // /api/status 因此要半秒——出图面板点「开始生成」先发 /api/generation，前摇就是这么来的。
        result.addProperty("promptTerms", promptTerms(effectivePrompts(settings.webScope()).positive()).size());
        result.addProperty("pendingImages", sd.pendingImages().size());
        result.addProperty("loraStatus", loraStatus);
        // 任务回执：列表页的角标只看这两个数（未读几条、最新是几号），不用把整份列表拉下来。
        observeQuests();
        result.add("quests", questIndex.counts());
        return result;
    }

    // ------------------------------------------------------------------ Civitai 一次性登录

    /** 一次性登录令牌 → 过期时间（毫秒时间戳）；用掉即作废。 */
    private final Map<String, Long> civitaiLoginTokens = new ConcurrentHashMap<>();
    /** 链接有效期：够在浏览器里登录一次，又不至于长期挂着。 */
    public static final int CIVITAI_LINK_MINUTES = 15;

    /** Civitai 账号状态：有没有 Cookie、用的是哪个站、Cookie 的脱敏提示。 */
    public JsonObject civitaiStatus() {
        JsonObject result = new JsonObject();
        JsonObject civitai = Json.obj(settings.snapshot(), "civitai");
        String cookie = Json.str(civitai, "session_cookie", "").strip();
        String base = Json.str(civitai, "base_url", CivitaiClient.DEFAULT_BASE_URL).strip();
        result.addProperty("hasCookie", !cookie.isBlank());
        result.addProperty("host", base);
        result.addProperty("cookieHint", cookie.isBlank() ? "" : cookieHint(cookie));
        result.addProperty("fallbackHost", CivitaiClient.OFFICIAL_BASE_URL);
        result.addProperty("linkMinutes", CIVITAI_LINK_MINUTES);
        return result;
    }

    private static String cookieHint(String cookie) {
        int at = cookie.indexOf("civ-token=");
        String value = at >= 0 ? cookie.substring(at + "civ-token=".length()) : cookie;
        int end = value.indexOf(';');
        if (end >= 0) value = value.substring(0, end);
        return value.length() <= 10 ? "已保存" : value.substring(0, 6) + "…" + value.substring(value.length() - 4);
    }

    /** 生成一个一次性登录令牌；返回令牌本身，由网页拼成链接。 */
    public synchronized String civitaiIssueLoginToken() {
        long now = System.currentTimeMillis();
        civitaiLoginTokens.entrySet().removeIf(entry -> entry.getValue() < now);
        String token = UUID.randomUUID().toString().replace("-", "");
        civitaiLoginTokens.put(token, now + CIVITAI_LINK_MINUTES * 60_000L);
        return token;
    }

    /** 令牌是否有效（未过期、未用过）。 */
    private synchronized boolean civitaiTokenValid(String token) {
        if (token == null || token.isBlank()) return false;
        Long expires = civitaiLoginTokens.get(token);
        if (expires == null || expires < System.currentTimeMillis()) return false;
        civitaiLoginTokens.remove(token);
        return true;
    }

    /** 打开一次性登录页时只检查、不消费令牌（页面里还要用它提交 Cookie）。 */
    public synchronized boolean civitaiLinkUsable(String token) {
        if (token == null || token.isBlank()) return false;
        Long expires = civitaiLoginTokens.get(token);
        return expires != null && expires >= System.currentTimeMillis();
    }

    /** 保存浏览器端回填的 Civitai 会话 Cookie（一次性令牌校验通过才会写入）。 */
    public JsonObject civitaiSaveCookie(String token, String cookie) throws Exception {
        if (!civitaiTokenValid(token)) throw new IllegalArgumentException("登录链接已失效，请在控制台重新生成。");
        String value = String.join("; ", Arrays.stream(String.valueOf(cookie).split("[\\r\\n]+"))
                .map(String::strip).filter(part -> !part.isBlank()).toList());
        if (value.isBlank()) throw new IllegalArgumentException("没有收到 Cookie 内容。");
        if (!value.contains("civ-token") && !value.contains("="))
            throw new IllegalArgumentException("这看起来不是浏览器 Cookie，请重新复制。");
        if (value.length() > 8000) throw new IllegalArgumentException("Cookie 太长，请只复制 civitai 相关的那几条。");
        settings.civitaiSetting("session_cookie", new JsonPrimitive(value));
        Log.info("Civitai 登录 Cookie 已保存（" + cookieHint(value) + "），搜索与下载改用它。");
        JsonObject result = civitaiStatus();
        result.addProperty("ok", true);
        return result;
    }

    /**
     * 用邮件里的一次性登录链接换取会话 Cookie（设计见 {@code docs/MIGRATION.md}「Civitai 账号」一节）。
     *
     * <p>机器人替浏览器走完整条跳转链（见 {@link CivitaiLinkLogin}），把会话 Cookie 写进
     * {@code config.json} 的 {@code civitai.session_cookie}，再带它打一次 LoRA 列表接口验证。
     * <b>链接与 Cookie 都不进日志</b>，只记脱敏提示。
     */
    public JsonObject civitaiSaveFromLink(String link) throws Exception {
        JsonObject civitai = Json.obj(settings.snapshot(), "civitai");
        CivitaiLinkLogin.Result followed = CivitaiLinkLogin.follow(civitai, link);
        String value = followed.cookie();
        if (value.isBlank())
            throw new IllegalArgumentException("这条链接没有带回会话 Cookie：可能已经用过、过期，或不是完整的登录链接。请重新在 Civitai 申请一封登录邮件。");
        if (value.length() > 8000) throw new IllegalArgumentException("Cookie 太长，这条链接可能不是登录链接。");
        settings.civitaiSetting("session_cookie", new JsonPrimitive(value));
        Log.info("Civitai 登录链接已处理（" + cookieHint(value) + "，跟随 " + followed.hops() + " 跳），搜索与下载改用它。");
        boolean verified = CivitaiLinkLogin.verify(civitai, value,
                Json.str(civitai, "base_url", CivitaiClient.DEFAULT_BASE_URL));
        if (!verified) Log.warn("Civitai 会话 Cookie 已保存，但带它查询 LoRA 列表没成功：可能需要检查 civitai.proxy_url 或重新申请登录邮件。");
        JsonObject result = civitaiStatus();
        result.addProperty("ok", true);
        result.addProperty("verified", verified);
        return result;
    }

    /** 清除已保存的 Civitai Cookie：之后回退到 civitai.com（内容受限）。 */
    public JsonObject civitaiClearCookie() throws Exception {
        settings.civitaiSetting("session_cookie", new JsonPrimitive(""));
        Log.info("Civitai 登录 Cookie 已清除，之后使用 " + CivitaiClient.OFFICIAL_BASE_URL + "（内容受限）。");
        JsonObject result = civitaiStatus();
        result.addProperty("ok", true);
        return result;
    }
    private JsonObject channelJson(DeepSeekPrompts.Channel channel) {
        JsonObject section = DeepSeekPrompts.sectionOf(settings, channel);
        String file = Json.str(section, "api_key_file", channel.keyFile());
        boolean configured = Files.isRegularFile(settings.root.resolve(file))
                || Files.isRegularFile(settings.root.resolve(DeepSeekPrompts.Channel.IMAGE.keyFile()));
        JsonObject result = new JsonObject();
        result.addProperty("label", channel.label());
        result.addProperty("section", channel.section());
        result.addProperty("model", Json.str(section, "model", "deepseek-flash"));
        result.addProperty("thinking", Json.bool(section, "thinking", false));
        result.addProperty("keyFile", file);
        result.addProperty("keyConfigured", configured);
        return result;
    }
    /** 个人提示词的完整内容与词条编号（网页编辑器用）。 */
    public JsonObject webPrompt(String scope) throws Exception {
        SdClient.Prompts prompts = effectivePrompts(scope);
        JsonObject result = new JsonObject();
        result.addProperty("scope", scope);
        result.addProperty("positive", prompts.positive());
        result.addProperty("negative", prompts.negative());
        List<String> positive = promptTerms(prompts.positive()), negative = promptTerms(prompts.negative());
        result.add("positiveTerms", Json.GSON.toJsonTree(positive));
        result.add("negativeTerms", Json.GSON.toJsonTree(negative));
        // 每个词条旁边给出中文释义（词库里查得到才有），网页的提示词面板直接显示。
        result.add("positiveItems", Json.GSON.toJsonTree(termItems(positive)));
        result.add("negativeItems", Json.GSON.toJsonTree(termItems(negative)));
        result.addProperty("canUndo", userPrompts.historyDepth(scope));
        return result;
    }
    // ------------------------------------------- 网页内部接口：纯本机的面板操作
    //
    // 个人 prompt、样式库、提示词集、参数预设都存在机器人这边的 JSON 里，改它们既不需要 SD 桥接、
    // 也不需要 DeepSeek。这些操作以前是网页拼一条 `.prompt remove …` 丢进指令通道、再轮询回执，
    // 慢且容易因为编号/守卫失败；现在网页直接调这里的内部接口，同步返回最新的面板数据。

    /**
     * 提示词旁边那点中文释义：先查内置中文词库，查不到再问 DeepSeek（`learn=true`）。
     *
     * <p>问回来的释义会记进 `data/prompt-meanings.json`，同一个词条之后不再重复问；
     * 没有配置生图频道密钥、或网络不通时，这里只是把该词条留空，不影响面板和出图。
     */
    public JsonObject webMeanings(List<String> terms, boolean learn) throws Exception {
        PromptUsage usage = null;
        try { usage = usageIndex(); } catch (Exception ignored) { /* 词库缺失：全靠模型兜底 */ }
        JsonObject meanings = new JsonObject();
        List<String> unknown = new ArrayList<>();
        for (String raw : terms == null ? List.<String>of() : terms) {
            String term = raw == null ? "" : raw.strip();
            if (term.isEmpty() || meanings.has(term)) continue;
            String known = usage == null ? "" : meaningOf(usage, term);
            if (known.isEmpty()) known = learnedMeanings().getOrDefault(term, "");
            if (!known.isEmpty()) meanings.addProperty(term, known);
            else if (!unknown.contains(term)) unknown.add(term);
        }
        JsonArray unresolved = new JsonArray();
        if (!unknown.isEmpty()) {
            boolean answered = false;
            if (learn) {
                try {
                    Map<String, String> fromModel = DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.IMAGE).meanings(unknown);
                    for (Map.Entry<String, String> entry : fromModel.entrySet()) {
                        meanings.addProperty(entry.getKey(), entry.getValue());
                        rememberMeanings(Map.of(entry.getKey(), entry.getValue()));
                    }
                    answered = true;
                } catch (Exception error) {
                    // 没密钥/网络不通/额度不足都只记一条日志：释义是锦上添花，不能拖累面板。
                    Log.warn("词条释义问 DeepSeek 失败（面板照常显示）：" + error(error));
                }
            }
            for (String term : unknown) if (!meanings.has(term)) unresolved.add(term);
            JsonObject result = new JsonObject();
            result.add("meanings", meanings);
            result.add("unresolved", unresolved);
            result.addProperty("asked", answered ? unknown.size() : 0);
            return result;
        }
        JsonObject result = new JsonObject();
        result.add("meanings", meanings);
        result.add("unresolved", unresolved);
        result.addProperty("asked", 0);
        return result;
    }
    /** 从模型学到的词条释义（data/prompt-meanings.json），面板加载时不再重复问。 */
    private final Map<String, String> meaningCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean meaningsLoaded;
    private Map<String, String> learnedMeanings() {
        if (meaningsLoaded) return meaningCache;
        synchronized (this) {
            if (!meaningsLoaded) {
                meaningsLoaded = true;
                Path file = settings.root.resolve("data/prompt-meanings.json");
                try {
                    if (Files.isRegularFile(file)) {
                        JsonObject saved = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                        JsonObject map = Json.obj(saved, "meanings");
                        for (String key : map.keySet()) {
                            String value = Json.str(map, key, "").strip();
                            if (!key.isBlank() && !value.isEmpty()) meaningCache.put(key, value);
                        }
                    }
                } catch (Exception error) {
                    Log.warn("词条释义缓存读取失败（会重新问模型）：" + error(error));
                }
            }
        }
        return meaningCache;
    }
    /** 记住一批新学到（或人工修正过）的释义，顺手把缓存文件写回磁盘。 */
    private void rememberMeanings(Map<String, String> learned) {
        if (learned == null || learned.isEmpty()) return;
        Map<String, String> cache = learnedMeanings();
        cache.putAll(learned);
        // 缓存只做"别再问一遍"用，太大了就丢掉最早的一批（LinkedHashMap 保序 → 重建时截断）。
        try {
            Map<String, String> ordered = new java.util.LinkedHashMap<>(cache);
            while (ordered.size() > MEANING_CACHE_LIMIT) {
                String eldest = ordered.keySet().iterator().next();
                ordered.remove(eldest);
                cache.remove(eldest);
            }
            JsonObject state = new JsonObject();
            state.addProperty("version", 1);
            JsonObject map = new JsonObject();
            ordered.forEach(map::addProperty);
            state.add("meanings", map);
            Json.atomicWrite(settings.root.resolve("data/prompt-meanings.json"), state);
        } catch (Exception error) {
            Log.warn("词条释义缓存写入失败（不影响使用）：" + error(error));
        }
    }
    /** 词条释义缓存上限：只为了避免重复问模型，不需要无限增长。 */
    private static final int MEANING_CACHE_LIMIT = 4000;

    /**
     * 网页「Civitai 搜索」：结构化返回结果（名称/基础模型/封面/下载数/训练词/大小），
     * 由网页自己渲染成卡片，而不是像 QQ 那样把文本与封面当一条消息发出去。
     *
     * <p>结果同时按网页会话登记（和 `.lora query` 一样），所以卡片上的「下载」按钮用
     * `.lora download #编号` 仍然指向这次搜索。
     */
    public JsonObject webCivitaiSearch(String scope, String query) throws Exception {
        return webCivitaiSearch(scope, query, 1);
    }
    /**
     * @param page 页码，从 1 开始（网页的「上一页/下一页」）。翻过头时返回空结果 + {@code note}，
     *             并且**不动**已登记的编号列表，前端把注记显示在空列表里即可。
     */
    public JsonObject webCivitaiSearch(String scope, String query, int page) throws Exception {
        String words = query == null ? "" : query.strip();
        if (words.isEmpty()) throw new IllegalArgumentException("请填写搜索词。");
        if (words.length() > 200) throw new IllegalArgumentException("搜索词最多 200 个字符。");
        CivitaiClient.SearchPage found = new CivitaiClient(settings.root, Json.obj(settings.snapshot(), "civitai")).search(words, page);
        JsonObject event = webEvent(scope, "lora query");
        // 空页不登记：网页/QQ 手上的"本页编号"要保持原样，不能被一次翻过头清空。
        if (!found.results().isEmpty()) registerLoraSearch(event, found);
        JsonArray items = new JsonArray();
        int number = 0;
        for (CivitaiClient.SearchResult result : found.results()) {
            JsonObject item = new JsonObject();
            item.addProperty("number", ++number);
            item.addProperty("name", result.name());
            item.addProperty("baseModel", result.baseModel());
            item.addProperty("url", result.url());
            item.addProperty("cover", result.cover());
            item.addProperty("downloads", result.downloads());
            item.addProperty("sizeKb", result.sizeKb());
            item.addProperty("nsfw", result.nsfw());
            item.add("trainedWords", Json.GSON.toJsonTree(result.trainedWords()));
            items.add(item);
        }
        JsonObject response = new JsonObject();
        response.addProperty("query", words);
        response.add("results", items);
        response.addProperty("count", items.size());
        // 页码与翻页依据：Civitai 关键词搜索不返回总数，所以 totalPages 给 -1、totalPagesKnown=false，
        // 前端按 hasMore 决定「下一页」能不能点（宁可说不知道页数，也不编一个）。
        response.addProperty("page", found.page());
        response.addProperty("pageSize", found.pageSize());
        response.addProperty("hasMore", found.hasMore());
        response.addProperty("totalPages", found.totalPagesKnown() ? found.totalPages() : -1);
        response.addProperty("totalPagesKnown", found.totalPagesKnown());
        response.addProperty("nextPage", found.hasMore() ? found.nextPage() : 0);
        if (items.isEmpty() && found.page() > 1) response.addProperty("note", searchPageOutOfRange(found).split("\n", 2)[0]);
        response.addProperty("hint", "本页编号：卡片上的「下载」用 #N，指这一页的第 N 条");
        return response;
    }

    /** 网页搜索请求体里的页码：缺省 1；乱填直接报错，别悄悄按第 1 页搜。 */
    public static int webSearchPage(JsonObject body) {
        if (body == null || !body.has("page") || body.get("page").isJsonNull()) return 1;
        try { return Integer.parseInt(body.get("page").getAsString().strip()); }
        catch (Exception error) { throw new IllegalArgumentException("page 必须是正整数。"); }
    }

    /** 封面图代理：网页的 <img> 打到机器人这边，由机器人带着登录态去 Civitai 取图。 */
    public CivitaiClient.Image civitaiCover(String url) throws Exception {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("缺少封面地址。");
        // 先判地址：不是 Civitai 图床就直接 400，不受 civitai 配置是否完整影响。
        if (!CivitaiClient.coverHostAllowed(url)) throw new IllegalArgumentException("只允许抓取 Civitai 图床的封面图。");
        return new CivitaiClient(settings.root, Json.obj(settings.snapshot(), "civitai")).cover(url);
    }

    /** 提示词面板：加/删/整段替换/清空/回退（side = positive | negative）。 */
    public JsonObject webPromptEdit(String scope, String side, String action, String value) throws Exception {
        boolean negative = "negative".equalsIgnoreCase(String.valueOf(side).strip());
        String op = String.valueOf(action == null ? "" : action).strip().toLowerCase(Locale.ROOT);
        String text = value == null ? "" : value.strip();
        if (text.length() > 20000) throw new IllegalArgumentException("prompt 超过 20000 字符。");
        String message;
        switch (op) {
            case "add", "remove" -> {
                if (text.isEmpty()) throw new IllegalArgumentException("请提供词条内容。");
                // 也认 #编号：按当前词条的实时列表解析，面板刷新过就不会错位。
                if (op.equals("remove") && text.startsWith("#")) text = webTermAt(scope, negative, text);
                SdClient.PromptChange change = userPrompts.change(scope, negative, op, text, userPrompts.vocabulary(styleTerms()));
                if (op.equals("remove")) new PromptFunctions(settings.root).forget(scope, negative, change.changed(), false);
                message = (op.equals("remove") ? "已删除：" : "已添加：")
                        + (change.changed().isEmpty() ? "（无变化）" : String.join("、", change.changed()))
                        + (change.unchanged().isEmpty() ? "" : "；本来就有：" + String.join("、", change.unchanged()));
            }
            case "set", "clear" -> {
                boolean clearing = op.equals("clear");
                SdClient.PromptChange change = userPrompts.change(scope, negative, "edit", clearing ? "" : text,
                        userPrompts.vocabulary(styleTerms()));
                new PromptFunctions(settings.root).forget(scope, negative, change.changed(), true);
                message = (negative ? "反向" : "正向") + " prompt 已" + (clearing ? "清空" : "整段替换") + "。";
            }
            case "undo" -> {
                PromptFunctions functions = new PromptFunctions(settings.root);
                functions.recover(() -> userPrompts.prompts(scope), scope);
                SdClient.Prompts before = userPrompts.prompts(scope);
                UserPromptStore.Undo undone = userPrompts.undo(scope);
                functions.reset(scope);
                message = "已回退到上一次 prompt，本次变化：正向 " + diffCount(before.positive(), undone.prompts().positive())
                        + " 处、反向 " + diffCount(before.negative(), undone.prompts().negative())
                        + " 处；还可回退 " + undone.remaining() + " 次。";
            }
            default -> throw new IllegalArgumentException("不支持的提示词操作：" + op);
        }
        JsonObject result = webPrompt(scope);
        result.addProperty("message", message);
        return result;
    }
    /** `#编号` → 当前提示词里的词条（找不到就说清楚，让网页刷新面板）。 */
    private String webTermAt(String scope, boolean negative, String number) throws Exception {
        SdClient.Prompts prompts = effectivePrompts(scope);
        List<String> terms = promptTerms(negative ? prompts.negative() : prompts.positive());
        int index;
        try { index = Integer.parseInt(number.substring(1).strip()) - 1; }
        catch (NumberFormatException error) { throw new IllegalArgumentException("编号格式不正确：" + number); }
        if (index < 0 || index >= terms.size())
            throw new IllegalArgumentException("没有第 " + number.substring(1) + " 个词条（当前共 " + terms.size() + " 个），请刷新面板后重试。");
        return terms.get(index);
    }
    /** 两段文本的词条差异条数（回执里给个人话，不必逐条列）。 */
    private static int diffCount(String before, String after) {
        java.util.Set<String> left = new java.util.LinkedHashSet<>(promptTerms(before));
        java.util.Set<String> right = new java.util.LinkedHashSet<>(promptTerms(after));
        java.util.Set<String> all = new java.util.LinkedHashSet<>(left);
        all.addAll(right);
        int changed = 0;
        for (String term : all) if (left.contains(term) != right.contains(term)) changed++;
        return changed;
    }

    /** 提示词集面板：保存/覆盖/加载/移出/清空/重置/改名/删除（定义共享，加载关联按个人）。 */
    public JsonObject webFunctionsEdit(String scope, String action, String name, String newName, boolean overwrite) throws Exception {
        PromptFunctions functions = new PromptFunctions(settings.root);
        PromptFunctions.Reader reader = () -> userPrompts.prompts(scope);
        PromptFunctions.Writer writer = transform -> userPrompts.replace(scope, transform.apply(userPrompts.prompts(scope)));
        functions.recover(reader, scope);
        String op = String.valueOf(action == null ? "" : action).strip().toLowerCase(Locale.ROOT);
        String target = GenerationPreset.Store.name(stripQuotes(name == null ? "" : name));
        String message;
        switch (op) {
            case "save", "overwrite" -> {
                if (target.isBlank()) throw new IllegalArgumentException("请填写提示词集名称。");
                functions.save(target, userPrompts.prompts(scope), overwrite || op.equals("overwrite"));
                message = "提示词集已保存（定义共享）：" + target;
            }
            case "load" -> { requireFunctionName(target); message = functionChangeText(functions.load(reader, writer, scope, target), true); }
            case "remove" -> { requireFunctionName(target); message = functionChangeText(functions.remove(reader, writer, scope, target), false); }
            case "clear" -> message = functionChangeText(functions.remove(reader, writer, scope, null), false);
            case "reset" -> { functions.reset(scope); message = "已清除你个人的提示词集关联，当前提示词保持不变。"; }
            case "rename" -> {
                requireFunctionName(target);
                String next = GenerationPreset.Store.name(stripQuotes(newName == null ? "" : newName));
                if (next.isBlank()) throw new IllegalArgumentException("请填写新名称。");
                functions.rename(target, next, overwrite);
                Log.info("提示词集已重命名：" + target + " -> " + next);
                message = "提示词集已重命名：" + target + " → " + next + "（各用户已加载的关联同步改名）。";
            }
            case "delete" -> {
                requireFunctionName(target);
                // 正在使用的提示词集不让直接删（定义删了、个人 prompt 里的词条却还在）。
                // 网页上用户已经按过确认框，这里就顺手先移出，再删定义——结果与"先移出再删除"完全一致。
                boolean wasActive = functions.active(scope).contains(target);
                if (wasActive) functions.remove(reader, writer, scope, target);
                functions.delete(target);
                message = "提示词集已删除：" + target + (wasActive ? "（先把已加载的词条从你的 prompt 里移出了）" : "");
            }
            default -> throw new IllegalArgumentException("不支持的提示词集操作：" + op);
        }
        JsonObject result = webFunctions(scope);
        result.addProperty("message", message);
        return result;
    }
    private static void requireFunctionName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("请提供提示词集名称。");
    }
    /** 与 QQ 侧 `.function load/remove` 同一套话术。 */
    private static String functionChangeText(PromptFunctions.Change result, boolean added) {
        return "提示词集已" + (added ? "加载" : "移出") + "。\n正向实际" + (added ? "新增" : "移除") + "："
                + oneLine(result.changed().positive()) + "\n反向实际" + (added ? "新增" : "移除") + "：" + oneLine(result.changed().negative());
    }
    private static String oneLine(String text) {
        String value = String.valueOf(text == null ? "" : text).strip();
        return value.isEmpty() ? "（无）" : value;
    }

    /**
     * 样式面板：保存/覆盖/改名/删除/载入/改分类。样式库是机器人自己的 JSON，不需要 WebUI；
     * 「导入 WebUI 预设样式」要读桥接，仍走指令通道。
     */
    public JsonObject webStylesEdit(String scope, String action, String name, String newName,
                                    boolean overwrite, boolean noLora) throws Exception {
        return webStylesEdit(scope, action, name, newName, overwrite, noLora, "", "");
    }

    /**
     * @param category 只给 {@code action=category} 用：新分类名（空串或 {@code -}/{@code 清除}＝清空手动分类，
     *                 回到默认规则）。复用现有接口，不另造一条。
     */
    public JsonObject webStylesEdit(String scope, String action, String name, String newName,
                                    boolean overwrite, boolean noLora, String category) throws Exception {
        return webStylesEdit(scope, action, name, newName, overwrite, noLora, category, "");
    }

    /**
     * @param cover 只给 {@code action=save/overwrite} 用：这次保存的封面图。
     *              空串＝默认用**最近一次生成的图**；{@code -}/{@code none}/{@code 清除}/{@code 不设}＝本次不设封面；
     *              其余当成 {@code data/generated} 里的图片路径（控制台 /api/images 给的就是这种相对路径）。
     *              封面是独立文件（{@code data/style-previews}），不动 {@code data/local-styles.json} 的结构。
     */
    public JsonObject webStylesEdit(String scope, String action, String name, String newName,
                                    boolean overwrite, boolean noLora, String category, String cover) throws Exception {
        String op = String.valueOf(action == null ? "" : action).strip().toLowerCase(Locale.ROOT);
        JsonObject event = webEvent(scope, op);
        String message;
        switch (op) {
            case "save", "overwrite" -> {
                String target = SdClient.styleSaveName(stripQuotes(name == null ? "" : name));
                // 封面先解析、后保存：指定的路径不合法就在这里报错，不会出现"样式存进去了、封面没设成"的半截结果。
                StyleCoverPlan coverPlan = planStyleCover(cover);
                // 保存的是调用者真正出图用的那份提示词；WebUI 读不到时退回已有的个人提示词（本机样式库不被别的程序拖住）。
                SdClient.Prompts current;
                boolean inherited = false;
                try { current = effectivePrompts(scope); }
                catch (Exception unavailable) { current = userPrompts.prompts(scope); inherited = true; }
                LocalStyles.Style saved = localStyles.save(target, current.positive(), current.negative(),
                        overwrite || op.equals("overwrite"), modelParamsOrNull());
                message = "样式已" + (op.equals("overwrite") ? "覆盖保存" : "保存") + "：" + saved.name()
                        + (inherited ? "（WebUI 提示词读取失败，本次用你已有的个人提示词保存）" : "（含 LoRA 标签）")
                        + (saved.hasModel() ? "；已记下模型参数：" + saved.modelSummary() : "")
                        + "；" + applyStyleCover(saved.name(), coverPlan);
            }
            case "rename" -> {
                List<String> targets = styleTargets(event, name == null ? "" : name);
                String base = SdClient.styleSaveName(stripQuotes(newName == null ? "" : newName.strip()));
                List<String> done = new ArrayList<>(), failed = new ArrayList<>(), skipped = new ArrayList<>();
                List<String> existing = localStyles.names();
                for (int index = 0; index < targets.size(); index++) {
                    String source = targets.get(index);
                    String destination = targets.size() == 1 ? base : base + " " + (index + 1);
                    if (source.equals(destination)) { skipped.add(source + "（名称相同）"); continue; }
                    if (!overwrite && containsIgnoreCase(existing, destination)) { skipped.add(source + " → " + destination + "（目标已存在）"); continue; }
                    try { done.add(source + " → " + localStyles.rename(source, destination, overwrite).name()); }
                    catch (Exception error) { failed.add(source + "（" + error(error) + "）"); }
                }
                message = reportBatch("样式改名", done, failed, skipped);
            }
            case "delete" -> {
                List<String> targets = styleTargets(event, name == null ? "" : name);
                List<String> done = new ArrayList<>(), failed = new ArrayList<>();
                for (String target : targets) {
                    try { done.add(localStyles.delete(target).name()); }
                    catch (Exception error) { failed.add(target + "（" + error(error) + "）"); }
                }
                message = reportBatch("样式删除", done, failed, List.of());
            }
            case "category" -> {
                // 改分类（与 QQ 侧 `.style category` 同一套规则）：name 允许 #编号 与 #起-#止 批量，
                // category 为空或 - 表示清空手动分类、回到默认规则。
                List<String> targets = styleTargets(event, name == null ? "" : name);
                String value = String.valueOf(category == null ? "" : category).strip();
                boolean clear = value.isEmpty() || value.equals("-") || value.equals("清除") || value.equals("清空");
                List<String> done = new ArrayList<>(), failed = new ArrayList<>();
                for (String target : targets) {
                    try {
                        LocalStyles.Style updated = localStyles.setCategory(target, clear ? "" : value);
                        done.add(updated.name() + " → " + localStyles.categoryOf(updated) + (updated.hasCategory() ? "" : "（按规则自动）"));
                    } catch (Exception error) { failed.add(target + "（" + error(error) + "）"); }
                }
                message = reportBatch("样式分类", done, failed, List.of())
                        + (clear ? "；已清空手动分类，回到自动规则（LoRA 附带样式一个 LoRA 一个分类 → 归属栈 → "
                            + LocalStyles.OTHER_CATEGORY + "）" : "");
            }
            case "load" -> {
                // 修 bug 1（与 QQ 侧 `.style load` 同一处）：网页控制台与角色链会带引号传名称，
                // 这里同样要先 stripQuotes；#编号 也按实时样式列表解析。
                String requested = stripQuotes(name == null ? "" : name.strip());
                // 与 QQ 侧一致：名称后面可以跟 noparams，表示只要提示词、不套用样式里的模型参数。
                boolean noParams = requested.matches("(?is).*\\s(?:noparams|no-params|不带参数|不要参数|不套参数)$");
                if (noParams) requested = requested.replaceFirst("(?is)\\s(?:noparams|no-params|不带参数|不要参数|不套参数)$", "").strip();
                if (requested.isEmpty()) throw new IllegalArgumentException("请选择要载入的样式。");
                if (requested.startsWith("#")) requested = select(event, "style", requested);
                SdClient.Prompts previous = effectivePrompts(scope);
                LocalStyles.Style local = localStyles.get(requested);
                if (local == null) throw new IllegalArgumentException("没有这个样式：" + requested + "。");
                String positive = SdClient.styleText(local.positive());
                String negative = SdClient.styleText(local.negative());
                List<String> skippedLora = List.of();
                if (noLora) { skippedLora = loraTagsIn(positive); positive = removeTerms(positive, skippedLora); }
                SdClient.Prompts updated = userPrompts.replace(scope,
                        new SdClient.Prompts(positive, negative, UserPromptStore.PERSONAL_SOURCE));
                new PromptFunctions(settings.root).reset(scope);
                // 网页载入同样把样式记着的模型参数套回来（WebUI 之前只换提示词，模型还得手点）；
                // 与 QQ 侧同一条路：先切样式记着的 Forge 预设，再逐项套参数。
                List<String> params = local.hasModel() && !noParams
                        ? sd.applyModelParams(local.model(), this::switchPresetForStyle) : List.of();
                message = "已用样式「" + local.name() + "」替换你个人的正向、反向 prompt（正向 "
                        + diffCount(previous.positive(), updated.positive()) + " 处、反向 "
                        + diffCount(previous.negative(), updated.negative()) + " 处变化）"
                        + (skippedLora.isEmpty() ? "" : "；已按 nolora 跳过 " + String.join("、", skippedLora))
                        + (local.hasModel() ? "；模型参数：" + (params.isEmpty() ? "未套用" : String.join("、", params)) : "")
                        + "；分类：" + localStyles.categoryOf(local)
                        + styleStackNotice(local);
            }
            default -> throw new IllegalArgumentException("不支持的样式操作：" + op);
        }
        JsonObject result = webStyles();
        result.addProperty("message", message);
        return result;
    }

    /**
     * 网页「把这张图设成这个样式的封面」：只写 {@code data/style-previews} 里那张图，
     * 样式本身、{@code data/local-styles.json} 的结构一个字都不动。
     *
     * @param name 目标样式名（不存在就报错，并列出可用的名字）
     * @param path {@code data/generated} 里的图片路径，校验规则与保存样式时的封面完全一样（见 {@link #generatedCover}）
     */
    public JsonObject webStyleCover(String name, String path) throws Exception {
        String requested = stripQuotes(name == null ? "" : name.strip());
        LocalStyles.Style style = localStyles.get(requested);
        if (style == null)
            throw new IllegalArgumentException("没有这个样式：" + requested + "。用 .style list 查看全部样式" + styleNamesNotice());
        Path image = generatedCover(path);
        byte[] bytes = readCoverImage(image, true);
        cn.szu.bot.sd.StylePreviews.save(settings.root, style.name(), bytes);
        JsonObject result = webStyles();
        result.addProperty("message", "样式封面已更新：" + style.name() + " ← " + image.getFileName());
        return result;
    }

    /** 保存样式时的封面决定：{@code mode} = {@code default}（最近生成图）/ {@code none}（本次不设）/ {@code file}（指定的图）。 */
    private record StyleCoverPlan(String mode, Path image, byte[] bytes) { }

    /**
     * 解析保存样式时控制台给的封面参数（在样式真正落盘**之前**跑，坏路径当场报错）：
     * <ul>
     *   <li>空串（前端没给）：<b>默认用最近一次生成的图</b>（跳过 {@code data/generated/webui} 里的回执内嵌图），
     *       没有可用图 / 读不出来 / 超过 20MB 就不设封面，也不报错；</li>
     *   <li>{@code -} / {@code none} / {@code 清除} / {@code 不设}（大小写与全角空格都认）：这次不设封面，原有封面保留；</li>
     *   <li>其余：{@code data/generated} 里的图片路径，越界 / 不存在 / 读不出来都抛 {@link IllegalArgumentException}。</li>
     * </ul>
     */
    private StyleCoverPlan planStyleCover(String cover) {
        String value = coverValue(cover);
        if (isNoCover(value)) return new StyleCoverPlan("none", null, null);
        if (!value.isEmpty()) {
            Path image = generatedCover(value);
            return new StyleCoverPlan("file", image, readCoverImage(image, true));
        }
        Path image = latestGeneratedImage();
        return new StyleCoverPlan("default", image, image == null ? null : readCoverImage(image, false));
    }

    /** 前后空白、引号、全角空格都清掉（前端可能把 {@code -} 写成 {@code 　-　}）。 */
    private static String coverValue(String cover) {
        String value = cover == null ? "" : cover.replace('\u3000', ' ').strip();
        return value.isEmpty() ? "" : stripQuotes(value);
    }

    /** 「这次不设封面」的几种写法（大小写不敏感）。 */
    private static boolean isNoCover(String value) {
        String lower = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return lower.equals("-") || lower.equals("none") || lower.equals("no") || lower.equals("off")
                || lower.equals("不设") || lower.equals("清除") || lower.equals("清空") || lower.equals("无");
    }

    /**
     * 最近一次**生成的作品**；没有可用的图、目录读不出来都返回 null（封面不是保存样式的必要条件）。
     *
     * <p>{@code data/generated/webui} 是回执里的内嵌图（地图、Civitai 封面这类临时图），出图面板
     * {@code webImages} 也把它排除在外——它不该被当成"最近一次生成的图"顶掉用户真正的那张作品。
     * 所以按 mtime 从新到旧多看几张，跳过内嵌目录后的第一张才是默认封面。
     */
    private Path latestGeneratedImage() {
        try {
            Path embedded = settings.root.toAbsolutePath().normalize().resolve("data/generated/webui");
            for (Path image : sd.recentImages(60)) {
                Path normalized = image.toAbsolutePath().normalize();
                if (normalized.startsWith(embedded)) continue;
                return normalized;
            }
            return null;
        } catch (Exception error) {
            Log.warn("读最近生成图失败（样式封面跳过）：" + error(error));
            return null;
        }
    }

    /**
     * 控制台给的封面路径 → {@code data/generated} 里的一张真实图片。
     *
     * <p>只认 {@code data/generated} 目录树内的路径：绝对路径按原样算，相对路径按机器人根目录算
     * （{@code /api/images} 给的就是 {@code data/generated/…} 这种相对路径）。先
     * {@code toAbsolutePath().normalize()} 再用 {@code startsWith} 判越界——{@code ../} 拼出来的路径
     * normalize 之后必然落到目录外，所以抛错时一个字节都还没写。
     * 和 LoRA 展示图那一套一致，这里不做 {@code toRealPath()}：目录里指出去的符号链接按"目录内的图"对待。
     */
    private Path generatedCover(String path) {
        String value = coverValue(path);
        if (value.isEmpty())
            throw new IllegalArgumentException("请给一张封面图：data/generated 里的图片路径（出图面板里能看到）。");
        Path base = settings.root.toAbsolutePath().normalize().resolve("data/generated");
        Path candidate;
        try { candidate = Path.of(value); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("封面图片路径看不懂：" + value); }
        Path image = (candidate.isAbsolute() ? candidate : settings.root.resolve(candidate)).toAbsolutePath().normalize();
        if (!image.startsWith(base))
            throw new IllegalArgumentException("封面只能是 data/generated 里的图片：" + value + "（用出图面板里的那张图）。");
        if (!Files.isRegularFile(image))
            throw new IllegalArgumentException("data/generated 里没有这张图片：" + value + "。");
        return image;
    }

    /**
     * 读封面字节：上限 20MB（和网页回执里的内嵌图一个规格）；连 32 字节都不到的不算图片
     * （{@code StylePreviews.save} 本来也会忽略这种）。{@code required=false} 的那一路
     * （默认取最近生成图）读不到就返回 null，绝不打断保存。
     */
    private byte[] readCoverImage(Path image, boolean required) {
        try {
            if (Files.size(image) > 20L * 1024 * 1024) throw new IOException("图片超过 20MB");
            byte[] bytes = Files.readAllBytes(image);
            if (bytes.length < 32) throw new IOException("不是有效的图片（只有 " + bytes.length + " 字节）");
            return bytes;
        } catch (Exception error) {
            if (required)
                throw new IllegalArgumentException("读不出这张封面图：" + image.getFileName() + "（" + error(error) + "）。");
            Log.warn("样式封面跳过：最近生成图读不出来（" + image.getFileName() + "）：" + error(error));
            return null;
        }
    }

    /** 样式存好之后真正落盘封面，返回回复文案里的那一句（短句，和别处一个风格）。 */
    private String applyStyleCover(String styleName, StyleCoverPlan plan) {
        if ("none".equals(plan.mode()))
            return "本次不设封面" + (cn.szu.bot.sd.StylePreviews.has(settings.root, styleName) ? "（保留原有的封面）" : "");
        if (plan.bytes() == null) return "（没有可用的最近生成图，本次未设封面）";
        try { cn.szu.bot.sd.StylePreviews.save(settings.root, styleName, plan.bytes()); }
        catch (Exception error) {
            Log.warn("样式封面落盘失败（" + styleName + "）：" + error(error));
            return "（封面没写进磁盘：" + error(error) + "）";
        }
        return ("file".equals(plan.mode()) ? "已记下封面：" : "封面：最近生成的 ") + plan.image().getFileName();
    }

    /** 样式名对不上时列一下可用的名字（太多只列前 20 个）。 */
    private String styleNamesNotice() {
        List<String> names = localStyles.names();
        if (names.isEmpty()) return "（样式库现在是空的，先用 .style save <名称> 存一个）。";
        int shown = Math.min(20, names.size());
        return "（可用：" + String.join("、", names.subList(0, shown))
                + (names.size() > shown ? " 等 " + names.size() + " 个" : "") + "）。";
    }

    /** 参数预设面板：保存/覆盖/删除（预设就是一份本机快照；「加载」会改 SD 参数，仍走指令通道）。 */
    public JsonObject webPresetsEdit(String action, String name, boolean overwrite) throws Exception {
        GenerationPreset.Store store = new GenerationPreset.Store(settings.root);
        String op = String.valueOf(action == null ? "" : action).strip().toLowerCase(Locale.ROOT);
        String target = GenerationPreset.Store.name(stripQuotes(name == null ? "" : name));
        if (target.isBlank()) throw new IllegalArgumentException("请填写预设名称。");
        String message;
        switch (op) {
            case "save", "overwrite" -> {
                store.save(target, sd.generationRequest(), overwrite || op.equals("overwrite"));
                message = "参数预设已保存：" + target;
            }
            case "remove", "delete" -> { store.remove(target); message = "参数预设已删除：" + target; }
            default -> throw new IllegalArgumentException("不支持的预设操作：" + op);
        }
        JsonObject result = webPresets();
        result.addProperty("message", message);
        return result;
    }

    /** 词条 + 中文释义（`#编号`、词条原文、词库里的中文写法，查不到时为空串）。 */
    private List<JsonObject> termItems(List<String> terms) {
        List<JsonObject> result = new ArrayList<>();
        PromptUsage usage;
        try { usage = usageIndex(); } catch (Exception error) { usage = null; }
        for (int index = 0; index < terms.size(); index++) {
            String term = terms.get(index);
            JsonObject item = new JsonObject();
            item.addProperty("number", index + 1);
            item.addProperty("term", term);
            String meaning = usage == null ? "" : meaningOf(usage, term);
            // 词库里没有就问过 DeepSeek 并记在 data/prompt-meanings.json：直接带上，面板不用再问一次。
            if (meaning.isEmpty()) meaning = learnedMeanings().getOrDefault(term, "");
            item.addProperty("meaning", meaning);
            result.add(item);
        }
        return result;
    }
    /** 一个词条的中文释义：先按整条找，再退到末尾的中心词（frilled apron → apron 围裙）。 */
    private static String meaningOf(PromptUsage usage, String term) {
        if (term == null || term.isBlank()) return "";
        List<String> candidates = new ArrayList<>();
        candidates.add(term.strip());
        String key = PromptEditor.key(term).replace(' ', '_');
        if (!key.equals(term.strip())) candidates.add(key);
        for (String candidate : candidates) {
            String meaning = lookupMeaning(usage, candidate);
            if (!meaning.isEmpty()) return meaning;
        }
        // 整条查不到时用末尾 1–3 个词（合成词的中心词）去查，好歹给个方向。
        String[] words = term.strip().replace('_', ' ').split("\\s+");
        for (int take = Math.min(3, words.length - 1); take >= 1; take--) {
            String tail = String.join(" ", Arrays.copyOfRange(words, words.length - take, words.length));
            String meaning = lookupMeaning(usage, tail);
            if (!meaning.isEmpty()) return meaning;
        }
        return "";
    }
    /** 词库里精确命中这个词条时返回它的第一个中文写法，否则空串。查的是索引表，不做全表搜索。 */
    private static String lookupMeaning(PromptUsage usage, String candidate) {
        try {
            PromptUsage.Entry entry = usage.entry(candidate);
            if (entry == null) return "";
            List<String> aliases = entry.aliases();
            return aliases.isEmpty() ? "" : aliases.get(0);
        } catch (Exception ignored) { /* 查不到就当没有释义 */ }
        return "";
    }
    /** 样式表：机器人自己的样式库（唯一来源，载入时直接套用到个人提示词）。 */
    public JsonObject webStyles() throws Exception {
        List<String> local = localStyles.names();
        // 分类只读地现算一次：同时算进「分类清单」与每条样式的 categoryKey/categorySource，
        // 两边的 key 必须一模一样（前端靠 categoryKey 持久化折叠状态）。
        LocalStyles.LoraIndex index = localStyles.loraIndex();
        JsonArray items = new JsonArray();
        // 底模分类：列表上方的"Anima 12 个、NoobAI 5 个"用。**不重排列表**——样式编号是
        // .style load #N、批量 #6-#9 的依据，因为分组而变号会让那些指令打错目标。
        Map<String, Integer> byBase = new LinkedHashMap<>();
        int number = 0;
        for (String name : local) {
            JsonObject item = styleItem(++number, name, "local", index);
            items.add(item);
            String base = Json.str(item, "baseModel", "");
            if (!base.isBlank()) byBase.merge(base, 1, Integer::sum);
        }
        JsonObject result = new JsonObject();
        result.add("styles", items);
        result.add("local", Json.GSON.toJsonTree(local));
        result.addProperty("library", local.size());
        result.add("baseModelGroups", styleBaseModelGroups(byBase));
        // 分类清单（组头与「改分类」下拉都读它）：只读地现算，不往 data/local-styles.json 里写东西。
        result.add("categories", categoryItems());
        return result;
    }

    /**
     * 分类清单（组头与「改分类」下拉都读它）：{@code key} / {@code name} / {@code kind} / {@code count} / {@code lora}。
     * 顺序：lora 组（一个 LoRA 一组，count 降序、同 count 按名字升序）→ stack 组（固定栈序）→ manual 组
     * → {@code none} 永远最后。**只读地现算，不往 data/local-styles.json 里写东西。**
     */
    private JsonArray categoryItems() {
        JsonArray result = new JsonArray();
        for (LocalStyles.Group group : localStyles.categoryGroups()) {
            JsonObject item = new JsonObject();
            item.addProperty("key", group.key());
            item.addProperty("name", group.name());
            item.addProperty("kind", group.kind());
            item.addProperty("count", group.count());
            item.addProperty("lora", group.lora());
            result.add(item);
        }
        return result;
    }

    /** 样式按底模分类的汇总（数量多的在前，同数量按名字）。 */
    private static JsonArray styleBaseModelGroups(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> -entry.getValue())
                .thenComparing(entry -> entry.getKey().toLowerCase(Locale.ROOT)));
        JsonArray result = new JsonArray();
        for (Map.Entry<String, Integer> entry : entries) {
            JsonObject group = new JsonObject();
            group.addProperty("baseModel", entry.getKey());
            group.addProperty("count", entry.getValue());
            result.add(group);
        }
        return result;
    }
    /**
     * 「查看原文」里的步数要显示成整数：Forge 的配置里它是 {@code 32.0}（double），样式文件里也就存成了
     * {@code 32.0}，网页上看着像小数。数值语义不变（{@code Json.num} 照旧读得出来），只是 JSON 里没有零头。
     * 非整数（例如谁手工写了 32.5）原样留着，不假装是整数。
     */
    private static JsonObject integerSteps(JsonObject model) {
        if (model == null || !model.has("steps")) return model;
        JsonElement value = model.get("steps");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return model;
        double steps = value.getAsDouble();
        if (steps == Math.rint(steps)) model.addProperty("steps", (long) steps);
        return model;
    }
    /** 一行样式：带原文（网页「查看原文」直接弹信息框，不用再走指令通道）。 */
    private JsonObject styleItem(int number, String name, String source, LocalStyles.LoraIndex index) {
        JsonObject item = new JsonObject();
        item.addProperty("number", number);
        item.addProperty("name", name);
        item.addProperty("source", source);
        item.addProperty("local", "local".equals(source));
        // 展示图（控制台「样式」页的缩略图）：没有就是没有，前端给占位。
        item.addProperty("preview", cn.szu.bot.sd.StylePreviews.has(settings.root, name));
        try {
            LocalStyles.Style style = localStyles.get(name);
            if (style != null) {
                item.addProperty("positive", style.positive() == null ? "" : style.positive());
                item.addProperty("negative", style.negative() == null ? "" : style.negative());
                // 分类：生效值（手动优先、否则自动规则）+ key（与 categories 里那条一模一样）+
                // 种类（lora/stack/manual/none，前端按它分组）+ 是不是手动设的。
                LocalStyles.Classification classification = LocalStyles.classify(style, index);
                item.addProperty("category", classification.name());
                item.addProperty("categoryKey", classification.key());
                item.addProperty("categorySource", classification.kind());
                item.addProperty("categoryStored", style.category());
                item.addProperty("categoryAuto", !style.hasCategory());
                // 样式记着的模型参数（底模 + 采样方法/调度器/步数/CFG/Shift/尺寸）：面板直接显示摘要，
                // 底模还给一份单独的字段，方便按底模标注。
                if (style.hasModel()) {
                    // 面板直接显示 model 的原始 JSON：步数在 Forge 的配置里是 32.0（double），
                    // 显示成 32 才正常（数值语义不变，Json.num 照旧读得出来）。
                    item.add("model", integerSteps(style.model().deepCopy()));
                    item.addProperty("modelSummary", style.modelSummary());
                    // 样式属于哪一栈（样式里的 stack 字段；老样式没有就用底模名判一次）。
                    String stack = Json.str(style.model(), "stack", "");
                    if (stack.isBlank()) stack = cn.szu.bot.sd.StackClassifier.stackOf(
                            Json.str(style.model(), "baseModel", ""), Json.str(style.model(), "checkpoint", ""));
                    // 底模名：样式自己记着的优先；**老样式**只有检查点名、没有底模名时，按归属栈补该栈的规范
                    // 底模名，好让「按底模分组」不漏项。只读回填，**不写回** data/local-styles.json；
                    // 栈也判不出来（检查点名毫无线索）就照旧留空——那是"真的没有底模信息"，不填默认值。
                    String baseModel = Json.str(style.model(), "baseModel", "");
                    String baseModelSource = Json.str(style.model(), "baseModelSource", "");
                    if (baseModel.isBlank() && !stack.isBlank()) {
                        baseModel = cn.szu.bot.sd.StackClassifier.baseModelOfStack(stack);
                        if (!baseModel.isBlank()) baseModelSource = cn.szu.bot.sd.StackClassifier.INFERRED_SOURCE;
                    }
                    item.addProperty("baseModel", baseModel);
                    item.addProperty("baseModelSource", baseModelSource);
                    item.addProperty("stack", stack);
                    item.addProperty("stackLabel", cn.szu.bot.sd.StackClassifier.stackLabel(stack));
                    String stackSource = Json.str(style.model(), "stackSource", "");
                    if (stackSource.isBlank() && !stack.isBlank()) stackSource = cn.szu.bot.sd.StackClassifier.INFERRED_SOURCE;
                    item.addProperty("stackSource", stackSource);
                    item.addProperty("forgePreset", Json.str(style.model(), "forge_preset", ""));
                    // 尺寸单独给一份：width/height 是**载入时会用的生成尺寸**（展示图样式记的是按展示图
                    // 同比例换算出来的那个值），展示图自己的真实像素另给 previewWidth/previewHeight；
                    // sizeSource 保留，面板照旧按它标注来源。
                    item.addProperty("width", Json.num(style.model(), "width", 0));
                    item.addProperty("height", Json.num(style.model(), "height", 0));
                    item.addProperty("previewWidth", Json.num(style.model(), "previewWidth", 0));
                    item.addProperty("previewHeight", Json.num(style.model(), "previewHeight", 0));
                    item.addProperty("sizeSource", Json.str(style.model(), "sizeSource", ""));
                    item.addProperty("previewImage", Json.str(style.model(), "previewImage", ""));
                    item.addProperty("loraName", Json.str(style.model(), "lora", ""));
                }
            }
        } catch (Exception error) { /* 单条读不出来不影响列表 */ }
        return item;
    }
    /** 网页用的 SD 状态：是否在跑、自启动开关、启动入口与参数（不含任何密钥）。 */
    public JsonObject sdStatus() {
        JsonObject result = new JsonObject();
        try {
            result.addProperty("reachable", sd.reachable());
            result.addProperty("autoStart", sdLauncher.enabled());
            result.addProperty("startOnBoot", sdLauncher.startOnBoot());
            result.addProperty("available", sdLauncher.available());
            Path root = sdLauncher.root();
            result.addProperty("root", root == null ? "" : root.toString());
            Path script = sdLauncher.launcherScript();
            result.addProperty("launcher", script == null ? "" : script.toString());
            result.addProperty("args", String.join(" ", sdLauncher.startArgs()));
            result.addProperty("text", sdLauncher.describe());
        } catch (Exception error) {
            result.addProperty("reachable", false);
            result.addProperty("text", "读取 SD 状态失败：" + error(error));
        }
        return result;
    }
    /**
     * 系统页签要显示的本机接口地址：两条 DeepSeek 通道、Stable Diffusion、NapCat。
     * 只给地址与最终 URL，不带任何密钥（密钥在 data/*-api-key.txt 里，永不外发）。
     */
    public JsonObject endpointStatus() {
        JsonObject snapshot = settings.snapshot();
        JsonObject result = new JsonObject();
        JsonObject chat = Json.obj(snapshot, "chat_api"), image = Json.obj(snapshot, "progen");
        String chatBase = Json.str(chat, "api_base", "").strip();
        String imageBase = Json.str(image, "api_base", "").strip();
        result.addProperty("chatApi", chatBase.isEmpty() ? DeepSeekPrompts.DEFAULT_API : chatBase);
        result.addProperty("imageApi", imageBase.isEmpty() ? DeepSeekPrompts.DEFAULT_API : imageBase);
        result.addProperty("chatApiUrl", DeepSeekPrompts.apiUrl(chat));
        result.addProperty("imageApiUrl", DeepSeekPrompts.apiUrl(image));
        result.addProperty("sd", Json.str(Json.obj(snapshot, "sd"), "base_url", ""));
        result.addProperty("napcat", Json.str(Json.obj(snapshot, "qq"), "ws_url", ""));
        return result;
    }
    /** 网页「启动 SD」按钮：拉起 SD 并等到就绪（可能等几分钟，所以放在后台线程里跑）。 */
    public JsonObject webSdStart() {
        JsonObject result = new JsonObject();
        result.addProperty("message", sdLauncher.startNow());
        result.add("sd", sdStatus());
        return result;
    }
    /** 网页按钮用的异步启动：立即返回，状态由 /api/sd/status 轮询。 */
    public void startSdInBackground() {
        if (sd.reachable()) return;
        generation.execute(() -> {
            String notice = sdLauncher.startNow();
            Log.info("网页触发启动 SD：" + notice.replaceAll("\\R+", " "));
        });
    }
    public JsonObject webLoras() throws Exception {
        JsonArray items = new JsonArray();
        String failure = "";
        List<SdClient.Lora> loras;
        try {
            loras = sd.loras();
        } catch (Exception error) {
            // SD 没在跑时不要把整个接口变成 500：面板要能显示"为什么读不到"，而不是空白加一条报错。
            loras = List.of();
            failure = error(error);
        }
        String directory = Json.str(Json.obj(settings.snapshot(), "civitai"), "lora_dir", "");
        // 底模兜底（当前 Forge 预设栈）只读一次：每个 LoRA 都去问一次 Forge 没必要。
        SdClient.BaseModel presetBase = loras.isEmpty() ? SdClient.BaseModel.NONE : presetBaseModelOrNone();
        List<String> presets = List.of();
        try { if (!loras.isEmpty() && sd.forge()) presets = sd.forgePresets(); } catch (Exception ignored) { /* 不是 Forge 就没有预设 */ }
        for (int index = 0; index < loras.size(); index++) {
            JsonObject item = new JsonObject();
            item.addProperty("number", index + 1);
            item.addProperty("name", loras.get(index).name());
            item.addProperty("alias", loras.get(index).alias());
            // 有展示图才给 true：前端据此决定显示缩略图还是"无图"占位，不必自己去猜文件名。
            item.addProperty("preview", hasLoraPreview(directory, loras.get(index).name()));
            // 底模（基础模型）：safetensors 头部 → Civitai 记录 → Forge 元数据 → 张量结构 → 当前预设（推断）。
            SdClient.BaseModel base = loraBaseModel(loras.get(index).name(), loras.get(index).path(), presetBase);
            item.addProperty("baseModel", base.name());
            item.addProperty("baseModelSource", base.source());
            item.addProperty("baseModelLabel", base.known() ? "底模 " + base.name() + base.note() : "底模未识别");
            item.addProperty("baseModelGroupKey", base.groupKey());
            item.addProperty("evidence", base.evidence());
            // 归属栈：前端按 **stack** 分组（组头「Anima 栈（1）」），底模名照旧展示。
            item.addProperty("stack", base.stack());
            item.addProperty("stackLabel", base.stackLabel());
            item.addProperty("stackSource", base.stackSource());
            item.addProperty("stackSourceLabel", base.stackSourceLabel());
            item.addProperty("preset", cn.szu.bot.sd.StackClassifier.presetFor(base.stack(), presets));
            item.addProperty("groupKey", base.stack());
            // 触发词（Civitai 的 trained words）：固定字段名 trainedWords，形如 ["词1","词2"]，原样有序；
            // 没有记录或 Civitai 没给时是空数组 []（不是 null），来源信息拿不到就给 null。界面由前端消费。
            LoraTriggers.Trigger trigger = loraTriggersOf(loras.get(index).name(), loras.get(index).path());
            item.add("trainedWords", Json.GSON.toJsonTree(trigger == null ? List.of() : trigger.trainedWords()));
            item.addProperty("triggerCount", trigger == null ? 0 : trigger.count());
            item.add("sourceUrl", trigger == null || trigger.sourceUrl().isBlank()
                    ? JsonNull.INSTANCE : new JsonPrimitive(trigger.sourceUrl()));
            item.add("triggerRecordedAt", trigger == null || trigger.recordedAt().isBlank()
                    ? JsonNull.INSTANCE : new JsonPrimitive(trigger.recordedAt()));
            item.addProperty("triggerSource", trigger == null ? "" : trigger.source());
            item.addProperty("triggerSourceLabel", trigger == null ? "" : trigger.sourceLabel());
            item.addProperty("triggerStack", trigger == null ? "" : trigger.stack());
            items.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("loras", items);
        result.add("groups", loraGroups(items));
        result.addProperty("count", items.size());
        result.addProperty("directory", directory);
        result.addProperty("downloadStatus", loraStatus);
        // 实时进度和 /api/lora/progress 同一份数据：面板一进来就能接着显示正在跑的下载。
        result.add("download", loraProgress());
        if (!failure.isBlank()) {
            result.addProperty("error", failure);
            result.addProperty("status", "读取本机 LoRA 失败");
            return result;
        }
        // 这一行曾经只报 Civitai 下载进度，没下载过就写"尚未下载 LoRA。"——页面上看起来像"一个 LoRA 都没有"。
        // 现在先说本机实际情况，下载进度单独给（没下载过就不提）。
        result.addProperty("status", items.isEmpty() ? "本机还没有 LoRA 文件"
                : "本机 " + items.size() + " 个 LoRA"
                        + (loraStatus.isBlank() || loraStatus.startsWith("尚未下载") ? "" : "；最近一次下载：" + loraStatus));
        return result;
    }
    /** 待领取图片的相对路径（网页用 /api/image 取图）。 */
    /**
     * 出图面板的图片列表：<b>最近生成的</b>（扫 {@code data/generated}，重启后仍在）+ <b>待领取的</b>（发送队列）。
     *
     * <p>只列待领取是不够的：任务完成后机器人默认会自动领取，队列随即清空，于是面板一刷新就什么都不剩
     * （用户报的"生成完的图片刷新后消失"）。这里以最近生成为主体，另外用 {@code pending} 标出哪些还没领取；
     * 顺序是新→旧，前端直接照序渲染。
     */
    public JsonArray webImages(int limit) throws Exception {
        int cap = limit > 0 ? limit : 60;
        Path root = settings.root.toAbsolutePath().normalize();
        List<Path> pending = new ArrayList<>(sd.pendingImages());
        LinkedHashSet<Path> pendingSet = new LinkedHashSet<>();
        for (Path image : pending) pendingSet.add(image.toAbsolutePath().normalize());
        List<Path> ordered = new ArrayList<>();
        // data/generated/webui 是回执内嵌图片（地图、封面等临时图），不属于"生成的作品"，别混进面板。
        Path embedded = root.resolve("data/generated/webui");
        try {
            for (Path image : sd.recentImages(cap * 2)) if (!image.toAbsolutePath().normalize().startsWith(embedded)) ordered.add(image);
        } catch (Exception error) { Log.warn("历史图片目录不可用，出图面板只列待领取：" + error.getMessage()); }
        for (int index = pending.size() - 1; index >= 0; index--) {          // 待领取的同样新的在前
            Path image = pending.get(index).toAbsolutePath().normalize();
            if (!ordered.contains(image)) ordered.add(image);
        }
        JsonArray result = new JsonArray();
        for (Path image : ordered) {
            if (result.size() >= cap) break;
            if (!Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS)) continue;
            JsonObject item = new JsonObject();
            item.addProperty("path", root.relativize(image).toString().replace('\\', '/'));
            item.addProperty("name", image.getFileName().toString());
            item.addProperty("size", Files.size(image));
            item.addProperty("modified", Files.getLastModifiedTime(image).toInstant().toString());
            item.addProperty("pending", pendingSet.contains(image));
            result.add(item);
        }
        return result;
    }
    /** 提示词集：定义内容 + 本 scope 是否已加载（网页列表用）。 */
    public JsonObject webFunctions(String scope) throws Exception {
        PromptFunctions functions = new PromptFunctions(settings.root);
        List<String> active = functions.active(scope);
        JsonArray items = new JsonArray();
        for (String name : functions.names()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            item.addProperty("active", active.contains(name));
            try {
                PromptFunctions.Pair pair = functions.get(name);
                item.addProperty("positive", pair.positive());
                item.addProperty("negative", pair.negative());
            } catch (Exception error) { item.addProperty("positive", ""); item.addProperty("negative", ""); }
            items.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("functions", items);
        result.add("active", Json.GSON.toJsonTree(active));
        return result;
    }
    /** 生成参数 preset：名称 + 内容（网页列表用）。 */
    public JsonObject webPresets() throws Exception {
        GenerationPreset.Store store = new GenerationPreset.Store(settings.root);
        JsonArray items = new JsonArray();
        for (String name : store.names()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            try { item.add("preset", store.get(name).json()); } catch (Exception error) { /* 单个损坏不影响列表 */ }
            items.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("presets", items);
        JsonObject current = new JsonObject();
        SdClient.GenerationSettings settingsNow = sd.settings();
        current.addProperty("sampler", settingsNow.samplerName());
        current.addProperty("width", settingsNow.width());
        current.addProperty("height", settingsNow.height());
        current.addProperty("steps", sd.parameters().steps());
        current.addProperty("cfg", sd.parameters().cfgScale());
        current.addProperty("seed", sd.parameters().seed());
        current.addProperty("model", sd.parameters().checkpoint());
        result.add("current", current);
        return result;
    }
    /** 采样方法与基础模型目录（网页下拉框用）。 */
    public JsonObject webOptions() throws Exception {
        JsonObject result = new JsonObject();
        // SD 没在跑时下拉框为空即可，接口本身不该 500（面板上会用进度那一行说明原因）。
        List<String> samplers = List.of(), models = List.of();
        String failure = "";
        try { samplers = sd.samplers(); } catch (Exception error) { failure = error(error); }
        try { models = sd.models(); } catch (Exception error) { failure = failure.isBlank() ? error(error) : failure; }
        result.add("samplers", Json.GSON.toJsonTree(samplers));
        result.add("models", Json.GSON.toJsonTree(models));
        // 每个基础模型的**归属栈**（底模名从 safetensors 头部/记录/Forge 预设配置判出来）：
        // 下拉框里显示「waiIllustriousSDXL_v170 [SDXL 栈]」，选错栈时页面也能拿 preset 给出提示。
        result.add("modelOptions", modelOptions());
        result.add("functions", Json.GSON.toJsonTree(new PromptFunctions(settings.root).names()));
        if (!failure.isBlank()) result.addProperty("error", failure);
        return result;
    }
    /** tab 补全的词库句柄：懒建一次，内部再按词库文件 mtime 缓存（见 {@link TagSuggest}）。 */
    private volatile TagSuggest tagSuggest;

    /** tab 补全：把输入框里正在敲的那个词补成标准词条（英文前缀 + 中文写法，见 {@link TagSuggest}）。 */
    public JsonObject webTagSuggest(String query, int limit) {
        TagSuggest suggest = tagSuggest;
        if (suggest == null) { suggest = new TagSuggest(settings.root); tagSuggest = suggest; }
        JsonArray tags = new JsonArray();
        for (TagSuggest.Hint hint : suggest.suggest(query, limit)) {
            JsonObject item = new JsonObject();
            item.addProperty("tag", hint.tag());
            if (!hint.zh().isBlank()) item.addProperty("zh", hint.zh());
            if (!hint.category().isBlank()) item.addProperty("category", hint.category());
            item.addProperty("rank", hint.rank());
            tags.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("query", query == null ? "" : query.strip());
        result.add("tags", tags);
        return result;
    }

    /** 提示词中文分类浏览：给定路径返回目录文本与可选编号项。 */
    public JsonObject webUsage(String query) throws Exception {
        String selection = select(webEvent(settings.webScope(), "usage"), "usage", (query == null || query.isBlank() ? "" : query.strip()));
        JsonObject result = new JsonObject();
        List<String> choices = new ArrayList<>();
        if (selection.startsWith("tag:")) {
            result.addProperty("text", selection.substring(4));
            result.add("choices", new JsonArray());
            return result;
        }
        String output;
        try { output = new PromptUsage(settings.root).browse(selection); }
        catch (Exception error) {
            result.addProperty("text", "提示词分类目录不可用：" + error(error));
            result.add("choices", new JsonArray());
            result.addProperty("query", selection);
            return result;
        }
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            if (line.startsWith(".usage ") || line.startsWith("/usage ")) { choices.add(line.substring(7)); line = "#" + choices.size() + " " + line; }
            else if (line.contains(" — ") && !line.startsWith("prompt — ")) { choices.add("tag:" + line); line = "#" + choices.size() + " " + line; }
            lines.add(line);
        }
        result.addProperty("text", String.join("\n", lines));
        result.add("choices", Json.GSON.toJsonTree(choices));
        result.addProperty("query", selection);
        return result;
    }
    /**
     * 前置拆解层与验收层的共用入口：中文别名索引（data/prompt-usage.json）。只读一次并缓存，
     * 缺失时返回 null（此时退化为原来的纯词库过滤，不影响功能）。
     */
    private volatile PromptUsage usageCache;
    private volatile boolean usageLoaded;
    PromptUsage usageIndex() {
        if (usageLoaded) return usageCache;
        synchronized (this) {
            if (!usageLoaded) {
                try { usageCache = new PromptUsage(settings.root); }
                catch (Exception error) { Log.warn("中文别名索引不可用（不影响词库过滤）：" + error(error)); usageCache = null; }
                usageLoaded = true;
            }
        }
        return usageCache;
    }
    /** 把拆解出的简单修改附在原始要求后面，让改写层逐条落实。 */
    static String enrich(String instruction, SceneDecomposer.Scene scene) {
        if (scene == null || scene.parts().isEmpty()) return instruction;
        StringBuilder text = new StringBuilder(instruction.strip());
        text.append("\n拆解后的简单修改（必须逐条落实，不能漏项）：");
        int index = 1;
        for (SceneDecomposer.Part part : scene.parts()) {
            text.append("\n").append(index++).append(". ").append(part.text());
            if (!part.tags().isEmpty()) text.append("（对应标准词条：").append(String.join(", ", part.tags())).append("）");
        }
        return text.toString();
    }
    /**
     * 像 .progen 那样先自由扩写，再把结果**逐词**收敛成词库里的标准词。逐词而不是整句，是因为
     * progen 爱写 "children playing in a sunny park" 这种自然短语：整句去猜只会猜到同名的角色词条。
     * 只保留词库里真实存在（或其词形/写法变体存在）的词，转不了的直接丢掉，失败时返回空列表。
     */
    List<String> expandedTags(DeepSeekPrompts client, String instruction, Set<String> allowed, PromptUsage usage) {
        try {
            DeepSeekPrompts.Result free = client.generate(instruction);
            List<String> tags = new ArrayList<>();
            for (String phrase : PromptEditor.parts(free.positive())) {
                if (allowed.contains(PromptEditor.key(phrase))) {
                    if (!tags.contains(phrase) && isPlainTag(phrase) && (usage == null || usage.expresses(phrase, instruction))) tags.add(phrase);
                    continue;
                }
                for (String word : PromptUsage.words(phrase)) {
                    if (word.length() < 3) continue;
                    String canonical = usage == null ? null : usage.canonical(word, allowed);
                    if (canonical == null || !isPlainTag(canonical) || tags.contains(canonical)) continue;
                    // 扩写只是"帮用户把话说完"：词库里恰好存在、但用户没提到的英文词（flats/support/sett…）不许进来。
                    if (usage != null && !usage.expresses(canonical, instruction)) continue;
                    tags.add(canonical);
                    if (tags.size() >= 12) break;
                }
                if (tags.size() >= 12) break;
            }
            return List.copyOf(tags);
        } catch (Exception error) {
            Log.warn("场景扩写补充失败（不影响改写）：" + error(error));
            return List.of();
        }
    }
    /** 中文类别说法 → 词库分类（"环境"同时含场景，方便用户只说一个词）。 */
    private static final Map<String, List<String>> CATEGORY_WORDS = Map.ofEntries(
            Map.entry("环境", List.of("环境", "场景")), Map.entry("背景", List.of("场景", "环境")),
            Map.entry("场景", List.of("场景", "环境")), Map.entry("地点", List.of("场景")),
            Map.entry("人物", List.of("人物", "角色")), Map.entry("角色", List.of("角色")), Map.entry("作品", List.of("作品")),
            Map.entry("服饰", List.of("服装")), Map.entry("服装", List.of("服装")), Map.entry("衣服", List.of("服装")), Map.entry("穿搭", List.of("服装")),
            Map.entry("表情", List.of("表情")), Map.entry("动作", List.of("动作")), Map.entry("行为", List.of("动作")),
            Map.entry("姿势", List.of("姿势")), Map.entry("体位", List.of("姿势")),
            Map.entry("物品", List.of("物品")), Map.entry("道具", List.of("物品")),
            Map.entry("镜头", List.of("镜头")), Map.entry("视角", List.of("镜头")), Map.entry("构图", List.of("镜头")), Map.entry("机位", List.of("镜头")),
            Map.entry("画风", List.of("画面")), Map.entry("画质", List.of("画面")), Map.entry("画面", List.of("画面")), Map.entry("质量", List.of("画面")),
            Map.entry("其他", List.of("其他")), Map.entry("其它", List.of("其他")), Map.entry("未分类", List.of("其他")), Map.entry("词库外", List.of("其他")));
    /** 一次"按类别筛选词条"的要求：保留哪些分类、删除哪些分类、是否只保留列出的分类。 */
    record CategorySurgery(Set<String> keep, Set<String> remove, boolean keepOnly) {}
    /**
     * 识别"仅保留人物和服饰，把其他提示词全部清空"这类**按类别**筛选提示词的要求。
     * 只认"整句都是这类要求"的句子（其余内容交给模型），避免把正常画面要求和筛选混在一起时误删词条。
     */
    static CategorySurgery parseCategorySurgery(String instruction) {
        if (instruction == null || instruction.isBlank()) return null;
        String text = instruction.strip();
        if (text.length() > 120) return null;
        Set<String> keep = new LinkedHashSet<>(), remove = new LinkedHashSet<>();
        boolean keepOnly = text.matches("(?s).*(其他|其它|其余|剩下|别的|只保留|仅保留|只要).*");
        String[] clauses = text.split("[，,。；;、\\n]+");
        boolean understood = false;
        for (String clause : clauses) {
            String part = clause.strip();
            if (part.isEmpty()) continue;
            Set<String> categories = new LinkedHashSet<>();
            for (Map.Entry<String, List<String>> word : CATEGORY_WORDS.entrySet())
                if (part.contains(word.getKey())) categories.addAll(word.getValue());
            boolean keepClause = part.matches("(?s).*(保留|留下|只留|仅留|只要|不要删|别删).*");
            boolean removeClause = part.matches("(?s).*(删除|删掉|删去|去除|去掉|移除|清除|清掉|清空|清理|不要|别要|丢弃).*");
            if (categories.isEmpty()) {
                // 只含"其他/其余/词条/全部"这类说法时算"其余都处理"，不算没看懂。
                if (part.matches("(?s).*(其他|其它|其余|剩下|别的|词条|全部|所有).*") && (keepClause || removeClause)) understood = true;
                continue;
            }
            understood = true;
            if (removeClause && !keepClause) remove.addAll(categories);
            else { keep.addAll(categories); if (!removeClause) keepOnly = true; }
        }
        // 整句必须只剩"筛选"相关的话：混着画面内容（"保留环境，然后加一个女孩"）就交给模型，
        // 否则程序会照筛选结果删词条，把用户真正想加的内容也一起丢掉。
        String residue = text;
        for (String word : CATEGORY_WORDS.keySet()) residue = residue.replace(word, "");
        residue = residue.replaceAll(FILTER_WORDS, "");
        if (residue.codePointCount(0, residue.length()) >= 2) return null;
        if (!understood || (keep.isEmpty() && remove.isEmpty())) return null;
        if (!keep.isEmpty()) keepOnly = true;      // 说了"保留 X"，就是把其他清掉
        return new CategorySurgery(Set.copyOf(keep), Set.copyOf(remove), keepOnly);
    }
    /**
     * "筛选"这句话用到的全部虚词与套话：识别时先抹掉它们，剩下的字才是"还说了别的画面内容"。
     * 这里必须齐——少一个词就会把正常的筛选要求当成没看懂（"仅保留人物和服饰，将所有的其他提示词清空"）。
     */
    private static final String FILTER_WORDS = "(保留|留下|只留|仅留|只要|不要删|别删|删除|删掉|删去|去除|去掉|移除|清除|清掉|清空|清理|不要|别要|丢弃"
            + "|其他|其它|其余|剩下|别的|词条|词语|词汇|提示词|prompt|标签|条目|内容|部分|全部|所有|全|都|一起|一并|一切|相关|这些|那些|这|那"
            + "|只|仅|是|的|地|得|了|着|和|与|以及|及|或者|或|类|把|将|给|帮|我|请|把|并且|然后|再|先|就|也|还|要|能|可以|一下|一下儿|词|字"
            + "|保留下来|留下来|整理|筛选|过滤|清洗|[，,。；;、\\s:：!！?？\"'“”（）()【】\\[\\]])";
    /** 从"人物 服饰"/"人物和服饰"/"视角"这类说法里解析出分类；认不出返回空集合。 */
    static Set<String> categoriesFromWords(String words) {
        Set<String> categories = new LinkedHashSet<>();
        if (words == null || words.isBlank()) return categories;
        for (String token : words.split("[，,、；;/|\\s]+")) {
            String part = token.strip();
            if (part.isEmpty()) continue;
            boolean matched = false;
            for (Map.Entry<String, List<String>> word : CATEGORY_WORDS.entrySet())
                if (part.contains(word.getKey())) { categories.addAll(word.getValue()); matched = true; }
            if (!matched && TermCategories.ORDER.contains(part)) categories.add(part);
        }
        return categories;
    }
    /** 应用类别筛选：返回新的正向提示词，并记录保留/移除的明细。 */
    static String applyCategorySurgery(String positive, CategorySurgery surgery, PromptUsage usage,
                                      List<String> kept, List<String> removed, List<String> protectedTerms) {
        return applyCategorySurgery(positive, surgery, usage, kept, removed, protectedTerms, null);
    }
    /**
     * 同 {@link #applyCategorySurgery}，外加方案 B 的兜底模型：本地判不出来的**复合短语**（多词、本地归类为
     * "其他"）由模型切片段（结果有缓存），程序只删属于目标类别的片段。模型参与的短语不再退回本地的片段
     * 判定——判不出来时这条短语一个字都不动，免得又把复合短语的一部分草率吃掉（见 {@link CategoryModel}）。
     */
    static String applyCategorySurgery(String positive, CategorySurgery surgery, PromptUsage usage,
                                      List<String> kept, List<String> removed, List<String> protectedTerms,
                                      CategoryModel model) {
        List<String> result = new ArrayList<>();
        // 方案 B：先把这条命令里**本地判不出来**的复合短语收集起来，一次性批量问模型（缓存命中不发请求）。
        // 一条命令只发这一批请求；失败的短语没有任何判定，下面按"一个字都不动"处理。
        Map<String, List<CategoryModel.Segment>> verdicts = Map.of();
        if (!surgery.keepOnly() && model != null && model.enabled()) {
            List<String> suspects = new ArrayList<>();
            for (String term : PromptEditor.parts(positive == null ? "" : positive)) {
                if (TermCategories.isLoraOrEmbedding(term)) continue;
                if (CategoryModel.needsModel(usage, term) && !suspects.contains(term)) suspects.add(term);
            }
            if (!suspects.isEmpty()) verdicts = model.resolve(suspects);
        }
        for (String term : PromptEditor.parts(positive == null ? "" : positive)) {
            if (TermCategories.isLoraOrEmbedding(term)) { result.add(term); if (protectedTerms != null && !protectedTerms.contains(term)) protectedTerms.add(term); continue; }
            String category = TermCategories.categoryOf(usage, term);
            boolean unknown = TermCategories.OTHER.equals(category);
            // 方案 B：本地判不出来的复合短语按模型的片段判定只删目标类别的片段；模型没给出可采信的
            // 切分（关闭/失败/拼不回原短语）时整条原样保留，不再退回本地的部分片段判定去删。
            if (!surgery.keepOnly() && model != null && model.enabled() && CategoryModel.needsModel(usage, term)) {
                String left = CategoryModel.dropFragments(term, surgery.remove(), usage, verdicts.get(term.strip()));
                if (!left.equals(term)) {
                    if (removed != null) removed.add(term + "（模型判定：只删" + String.join("、", surgery.remove()) + "片段）");
                    if (left.isBlank()) continue;
                    if (kept != null) kept.add(left + "（" + category + "·保留其余片段）");
                    result.add(left);
                    continue;
                }
                if (kept != null) kept.add(term + "（" + (unknown ? "未分类" : category) + "）");
                result.add(term);
                continue;
            }
            // 只删某几类时按**片段**删：复合短语里只有属于该类的那一段会被摘掉
            // （standing sex from behind → standing sex），其余片段原样拼回；
            // 整条都属于该类（multiple views）时才整条删除；没有目标片段时一个字都不动。
            String shrunk = surgery.keepOnly() ? term : TermCategories.withoutCategories(term, surgery.remove());
            if (!surgery.keepOnly() && shrunk.isBlank()) {
                if (removed != null) removed.add(term + (unknown ? "（未分类）" : "（" + category + "）"));
                continue;
            }
            if (!surgery.keepOnly() && !shrunk.equals(term)) {
                if (removed != null) removed.add(term + "（" + category + "·只删该片段）");
                if (kept != null) kept.add(shrunk + "（" + category + "·保留其余片段）");
                result.add(shrunk);
                continue;
            }
            boolean drop;
            if (!unknown) {
                if (surgery.keepOnly()) drop = !surgery.keep().contains(category);
                else drop = surgery.remove().contains(category);
            } else {
                // 词库与词表都认不出的词条：只保留某几类时不认识就删（用户要的是"其他清空"），只删某类时保留。
                drop = surgery.keepOnly() && surgery.remove().isEmpty();
            }
            if (drop) { if (removed != null) removed.add(term + (unknown ? "（未分类）" : "（" + category + "）")); continue; }
            if (kept != null) kept.add(term + "（" + (unknown ? "未分类" : category) + "）");
            result.add(term);
        }
        return String.join(", ", result);
    }
    /**
     * 按类别筛选某个 scope 的个人正向提示词。指令（/prompt keep|drop）与聊天里
     * "仅保留人物和服饰"这类要求共用这一条实现，因此网页、QQ、命令三条路的结果完全一致。
     */
    String categoryFilter(String scope, boolean keep, String words) throws Exception {
        if (words == null || words.isBlank())
            throw new IllegalArgumentException("请写出要处理的类别，例如 /prompt keep 人物 服饰、/prompt drop 环境 物品。"
                    + "\n可用类别：" + String.join("、", TermCategories.ORDER) + "（也可说 环境/视角/画风/衣服 这类同义说法）。");
        Set<String> categories = categoriesFromWords(words);
        if (categories.isEmpty())
            throw new IllegalArgumentException("认不出这些类别：" + words + "\n可用类别：" + String.join("、", TermCategories.ORDER)
                    + "（也可说 环境/背景/视角/机位/衣服/道具 这类同义说法）。");
        CategorySurgery surgery = keep ? new CategorySurgery(Set.copyOf(categories), Set.of(), true)
                : new CategorySurgery(Set.of(), Set.copyOf(categories), false);
        PromptUsage usage = usageIndex();
        SdClient.Prompts current = userPrompts.prompts(scope);
        List<String> kept = new ArrayList<>(), removed = new ArrayList<>(), tags = new ArrayList<>();
        String positive = applyCategorySurgery(current.positive(), surgery, usage, kept, removed, tags, categoryModel(scope));
        if (positive.equals(current.positive())) {
            return "当前 prompt 没有需要变动的词条：" + (keep ? "保留 " : "删除 ")
                    + String.join("、", categories) + " 的结果与现在一致。\n" + TermCategories.describe(usage, current.positive());
        }
        PromptFunctions functions = new PromptFunctions(settings.root);
        functions.recover(() -> userPrompts.prompts(scope), scope);
        userPrompts.replace(scope, new SdClient.Prompts(positive, current.negative(), UserPromptStore.PERSONAL_SOURCE));
        functions.forget(scope, false, List.of(), true);
        Log.info("按类别筛选 prompt（" + scope + "）：" + (keep ? "保留 " : "删除 ") + String.join("、", categories)
                + "，保留 " + kept.size() + " 条，删除 " + removed.size() + " 条");
        return "已按类别整理正向 prompt（" + (keep ? "保留 " : "删除 ") + String.join("、", categories) + "）：\n"
                + "保留 " + kept.size() + " 条：" + summarizeCategories(kept)
                + (removed.isEmpty() ? "" : "\n删除 " + removed.size() + " 条：" + summarizeCategories(removed))
                + (tags.isEmpty() ? "" : "\nLoRA/嵌入标签原样保留：" + String.join("、", tags))
                + "\n反向 prompt 未改动；用 /prompt 查看完整结果，/prompt undo 可以回退这一步。";
    }
    /**
     * 方案 B 的兜底模型实例（惰性创建：缓存与用量计数都挂在这一个实例上）。按会话套开关：
     * 关闭时 {@link CategoryModel#resolve} 不发请求、不读缓存，筛选完全走本地判定。
     */
    private volatile CategoryModel categoryModel;
    CategoryModel categoryModel(String scope) throws Exception {
        CategoryModel model = categoryModel;
        if (model == null) {
            synchronized (this) {
                if (categoryModel == null) categoryModel = CategoryModel.deepSeek(settings);
                model = categoryModel;
            }
        }
        model.setEnabled(settings.categoryModelEnabled(scope));
        return model;
    }
    /**
     * {@code /prompt category status} 与 {@code /prompt category model on|off}：复合短语兜底模型的开关与用量。
     * 用量**只展示、不限制**（本地判不出来的短语有多少就处理多少，单次请求只按字符数自动分批）。
     */
    String categoryModelCommand(String scope, String value) throws Exception {
        CategoryModel model = categoryModel(scope);
        String argument = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (argument.isEmpty() || argument.equals("status") || argument.equals("状态") || argument.equals("用量")) {
            JsonObject status = model.status();
            boolean on = status.get("enabled").getAsBoolean();
            return "复合短语兜底模型（DeepSeek）：" + (on ? "开启" : "关闭") + "（默认开启，按会话保存）"
                    + "\n缓存 " + status.get("cached").getAsInt() + " 条短语：" + status.get("cacheFile").getAsString()
                    + "\n用量（只统计、不限制）：调用 " + status.get("lastCalls").getAsInt() + " 次，发送 "
                    + status.get("lastPhrases").getAsInt() + " 条短语，命中缓存 " + status.get("lastCachedHits").getAsInt()
                    + " 条，兜底保留 " + status.get("lastFallbacks").getAsInt() + " 条"
                    + "\n单次请求 " + status.get("batchChars").getAsInt() + " 字符 / " + status.get("batchPhrases").getAsInt()
                    + " 条自动分批，短语总数不限；失败的短语按未分类处理（一个字都不删）"
                    + "\n" + (on ? "关闭：/prompt category model off" : "开启：/prompt category model on");
        }
        String toggle = argument.replace("model", "").strip();
        if (toggle.equals("on") || toggle.equals("开") || toggle.equals("开启") || toggle.equals("启用")) {
            settings.setCategoryModelEnabled(scope, true);
            model.setEnabled(true);
            return "复合短语兜底模型已开启：本地判不出来的复合短语会交给 DeepSeek 切片段（结果缓存，第二次不再调用）。";
        }
        if (toggle.equals("off") || toggle.equals("关") || toggle.equals("关闭") || toggle.equals("禁用")) {
            settings.setCategoryModelEnabled(scope, false);
            model.setEnabled(false);
            return "复合短语兜底模型已关闭：/prompt drop|keep 完全走本地判定（不发请求、不读缓存）。";
        }
        throw new IllegalArgumentException("用法：/prompt category status 查看开关与用量；"
                + "/prompt category model on|off 开关本会话的复合短语兜底模型（默认开启）。");
    }
    /** 把"词条（分类）"明细汇总成"服装 21 条、环境 3 条（场地、天气…）"这样的一行。 */
    private static String summarizeCategories(List<String> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String item : items) {
            String category = item.replaceAll("^.*（(.+)）$", "$1");
            counts.merge(category, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) parts.add(entry.getKey() + " " + entry.getValue() + " 条");
        String sample = items.size() <= 12 ? String.join("、", items) : String.join("、", items.subList(0, 12)) + "…";
        return String.join("、", parts) + "（" + sample + "）";
    }
    /**
     * 聊天里"仅保留人物和服饰，其他清空"这类纯筛选请求：程序直接照分类执行，
     * 不交给模型猜（分类结果由 {@link TermCategories} 给出，模型看到的也是同一份）。
     */
    private ChatActions.Plan categorySurgeryPlan(String message) {
        CategorySurgery surgery = parseCategorySurgery(message);
        if (surgery == null) return null;
        Set<String> categories = surgery.keepOnly() ? surgery.keep() : surgery.remove();
        boolean keep = surgery.keepOnly();
        String command = "/prompt " + (keep ? "keep " : "drop ") + String.join(" ", categories);
        String reply = "好，" + (keep ? "只保留 " : "删掉 ") + String.join("、", categories)
                + (keep ? "，其余词条清空（LoRA/嵌入标签保留）" : " 这几类词条") + "。";
        Log.info("按类别筛选的聊天请求，程序直接执行：" + command);
        return new ChatActions.Plan(reply, List.of(command), "", 100);
    }
    /**
     * QQ 侧的聊天规划入口（{@link ChatService.Planner}）：纯"按类别筛选词条"的要求先由程序直接执行，
     * 其余交给聊天频道模型；模型拿到 selections.prompt_terms 里同一份分类清单，两条路结果一致。
     */
    ChatActions.Plan chatPlan(String personality, JsonArray history, String message, JsonObject choices, JsonObject speaker) throws Exception {
        ChatActions.Plan surgery = categorySurgeryPlan(message);
        if (surgery != null) return surgery;
        return DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.CHAT).chatPlan(personality, history, message, choices, speaker);
    }
    /** LoRA 与嵌入标签：任何筛选都不动它们。 */
    static boolean isLoraOrEmbedding(String term) { return TermCategories.isLoraOrEmbedding(term); }    /** 只接受"普通词条"：角色名/作者名（带括号、冒号）不是画面要素，不能当补词用。 */
    static boolean isPlainTag(String tag) {
        return tag != null && !tag.isBlank() && tag.indexOf('(') < 0 && tag.indexOf(':') < 0 && tag.length() <= 40;
    }
    /** 这句要求是在描述场景/画面（而不是指定某个词条或整段替换）。 */
    static boolean describesScene(String instruction) {
        if (instruction == null || instruction.isBlank()) return false;
        return !instruction.matches("(?s).*(改成|改为|换成|换为|替换|移除|去掉|删除|删除|清空|设置|设为|调整|调成|变成|变成).*");
    }
    // ---------------------------------------------------------------- 常规档的"唯一回执"
    //
    // 不变量（.mode normal）：**一条用户指令最多产生 1 条文本消息**。这条文本要么是一句肯定，
    // 要么什么都不发（图片/图集已经承载了结果）；失败、被拒绝、权限不足、用法错误、参数校验
    // 一律用这一条说出来，绝不吞。debug 档（默认）一行都不变：下面这套只在常规档开窗，别处照旧。
    //
    // 做法是"**候选先记账、收尾才发**"，全部落在组装/发送决策这一层：没有任何一处对已生成的长文本
    // 做正则删减，只是从这条指令产生的那几条候选里挑一条发。优先级：
    //   失败说明（FAILURE） > 用户显式查询的答案（ANSWER） > 肯定（AFFIRM） > 过程行（PROCESS，不出声）
    // 于是"入队一条 + 结算一条"不可能同时出现：入队的肯定先记账、不发；结算若是失败说明，它直接把
    // 肯定顶掉；结算若是成功且图片已发出，肯定自动让位（图片本身就是回执）。

    /** 把"这条指令的收据"挂在事件上：异步步骤（出图结算、领取、下载收尾）拿着同一份事件回来时仍记在同一条指令上。 */
    static final String NORMAL_WINDOW_KEY = "_qq_normal_window";

    /** 一条候选文本在常规档里的分量：越靠后越优先，收尾时只放分量最高的那一条出去。 */
    enum ReceiptRank {
        /** 过程行（"正在…"、SD 自启动说明、灰图自检说明、VAE 自动修复说明…）：常规档一律不出声。 */
        PROCESS,
        /** 肯定：常规档一条指令只留一句；图片已经承载结果时连这一句也不发。 */
        AFFIRM,
        /** 用户显式查询的答案：用户问的必须答全，不因"只留一条"或"图片已发出"被削掉。 */
        ANSWER,
        /** 失败/被拒绝/权限不足/用法错误/参数校验：最高优先，任何情况下都要发出来。 */
        FAILURE
    }

    /**
     * 一条指令在常规档下的唯一回执窗口。
     *
     * <p>候选先记账、收尾才发（{@link #commit()} 里只挑选，不改写任何一条文本）。窗口可以被异步步骤
     * "占用"（出图任务、领取流程、LoRA/DeepSeek 后台任务）：每占用一次 {@code holders} 加一，
     * 收尾时减一，减到 0 才定稿——所以"入队"的那条肯定要等这一批图发完，才知道该不该出声。
     */
    static final class NormalWindow {
        final String id = UUID.randomUUID().toString();
        final String conversation;
        private final JsonObject event;
        private final Map<ReceiptRank, String> candidates = new EnumMap<>(ReceiptRank.class);
        private final long startedMillis = System.currentTimeMillis();
        private boolean answerMode;
        private boolean imageDelivered;
        private int holders = 1;
        private boolean closed;

        NormalWindow(JsonObject event) {
            this.event = event.deepCopy();
            this.conversation = ChatService.conversationKey(event);
        }
        synchronized void answerMode(boolean on) { answerMode = on; }
        synchronized void offer(ReceiptRank rank, String text) {
            if (text == null || text.isBlank()) return;
            // 用户显式查询那条指令里的肯定，本身就是"用户在问的答案"：不许被后续的图片静音掉。
            candidates.put(rank == ReceiptRank.AFFIRM && answerMode ? ReceiptRank.ANSWER : rank, text);
        }
        synchronized boolean has(ReceiptRank rank) { return candidates.containsKey(rank); }
        synchronized String peek(ReceiptRank rank) { return candidates.get(rank); }
        synchronized void imageDelivered() { imageDelivered = true; }
        synchronized boolean imageDeliveredAlready() { return imageDelivered; }
        synchronized void hold() { holders++; }
        synchronized boolean isClosed() { return closed; }
        synchronized int holders() { return holders; }
        synchronized long ageMillis() { return System.currentTimeMillis() - startedMillis; }
        /** 收尾一次占用；返回 true 表示持有者都放完了，可以定稿。 */
        synchronized boolean release() {
            if (holders > 0) holders--;
            return holders == 0 && !closed;
        }
        /** 定稿：挑一条发出去；没有可发的返回 null（＝这条指令在常规档下不出声）。 */
        synchronized String commit() {
            closed = true;
            String failure = candidates.get(ReceiptRank.FAILURE);
            if (failure != null) return failure;             // 失败说明永远优先，也永远不吞
            String answer = candidates.get(ReceiptRank.ANSWER);
            // 用户明确问的照旧回答：图也发了、答案也要给（这是"答案"，不是"过程回执"）。
            if (answer != null) return answer;
            if (imageDelivered) return null;                 // 图片已经是回执了
            return candidates.get(ReceiptRank.AFFIRM);
        }
    }

    private final Map<String, NormalWindow> normalWindows = new ConcurrentHashMap<>();
    /** 正在跑这条指令的线程自己的收据窗口（异步步骤靠事件上的 {@link #NORMAL_WINDOW_KEY} 找回同一个）。 */
    private final ThreadLocal<NormalWindow> normalWindowHere = new ThreadLocal<>();

    /**
     * 开一条指令的收据窗口。不满足条件就返回 null＝这条指令照旧直接发：
     * <ul>
     *   <li>会话是 debug（默认，含老配置）——一行都不变；</li>
     *   <li>已经在一条指令里（{@code .batch}、多步链路里再走 {@code accept}）——沿用外层那一条；</li>
     *   <li>这条消息走的是网页回执收集器——控制台的存档必须完整，不在这里合并。</li>
     * </ul>
     */
    private NormalWindow openNormalWindow(JsonObject event) {
        if (normalWindowHere.get() != null) return null;
        // 已经归属某条指令的事件（多步链路/异步步骤带回来的那份）不再另开一个窗口。
        if (!Json.str(event, NORMAL_WINDOW_KEY, "").isEmpty()) return null;
        if (!quietReceipts(event)) return null;
        NormalWindow window = new NormalWindow(event);
        normalWindows.put(window.id, window);
        normalWindowHere.set(window);
        pruneNormalWindows();
        return window;
    }
    /** 指令本身的那一份占用收尾（清掉本线程的窗口，再放掉一次占用）。 */
    private void closeNormalWindow(NormalWindow window) {
        if (window == null) return;
        if (normalWindowHere.get() == window) normalWindowHere.remove();
        releaseNormalWindow(window);
    }
    /**
     * 让这次调用占住这条指令的收据窗口（异步步骤带着事件回来时算同一条指令）。每调用一次就多占一份，
     * 调用方用完必须 {@link #releaseNormalWindow(JsonObject)} 一次。事件上没有窗口时原样返回。
     */
    private JsonObject attachNormalWindow(JsonObject event) {
        NormalWindow window = normalWindowHere.get();
        if (window == null || window.isClosed()) window = windowById(Json.str(event, NORMAL_WINDOW_KEY, ""));
        if (window == null || window.isClosed()) return event;
        if (Json.str(event, NORMAL_WINDOW_KEY, "").isEmpty()) event.addProperty(NORMAL_WINDOW_KEY, window.id);
        window.hold();
        return event;
    }
    /** 按 id 找收据窗口（已定稿/已淘汰返回 null）。 */
    private NormalWindow windowById(String id) {
        if (id == null || id.isEmpty()) return null;
        NormalWindow window = normalWindows.get(id);
        return window == null || window.isClosed() ? null : window;
    }
    /** 这个事件属于哪个收据窗口；没有（debug 档、或窗口已定稿）就是 null＝照旧直接发。 */
    private NormalWindow normalWindowFor(JsonObject event) {
        NormalWindow mine = normalWindowHere.get();
        if (mine != null && !mine.isClosed()) return mine;
        return windowById(Json.str(event, NORMAL_WINDOW_KEY, ""));
    }
    /** 异步步骤收尾：放掉它那一次占用（放完就定稿）。 */
    private void releaseNormalWindow(JsonObject event) {
        NormalWindow window = windowById(Json.str(event, NORMAL_WINDOW_KEY, ""));
        if (window != null) releaseNormalWindow(window);
    }
    private void releaseNormalWindow(NormalWindow window) {
        if (window == null || !window.release()) return;
        normalWindows.remove(window.id);
        String text = window.commit();
        if (text == null) {
            Log.info("常规档：本指令不出声（" + window.conversation + "）——图片已经承载了结果，或没有要报的东西");
            return;
        }
        // 定稿这一条就是真正发出去的那条：走原来的发送链路（网页捕获、禁言、日志都照旧）。
        sendReply(window.event, text);
    }
    /**
     * 窗口是内存对象，极端情况下（后台任务永久卡住）不能无限留着：超过 10 分钟一律定稿发出去。
     * 这样"肯定"最多晚到，不会丢。
     */
    private void pruneNormalWindows() {
        if (normalWindows.size() <= 64) return;
        long now = System.currentTimeMillis();
        for (NormalWindow window : List.copyOf(normalWindows.values()))
            if (now - window.startedMillis > TimeUnit.MINUTES.toMillis(10)) {
                Log.warn("常规档收据超过 10 分钟仍未收尾，先定稿发出（" + window.conversation + "）");
                normalWindows.remove(window.id);
                String text = window.commit();
                if (text != null) sendReply(window.event, text);
            }
    }
    /** 记下"这条指令已经有图片发出去了"：图片本身就是回执，收尾时那条肯定就不必再出声。 */
    private void markImagesDelivered(JsonObject event) {
        NormalWindow window = normalWindowFor(event);
        if (window != null) window.imageDelivered();
    }
    /** 记住这条指令是不是"用户显式查询"：是的话它的答案不许被"只留一条"削掉。 */
    private void markQueryCommand(JsonObject event, boolean query) {
        NormalWindow window = normalWindowFor(event);
        if (window != null) window.answerMode(query);
    }
    /**
     * 用户显式查询：这条指令的答案本身就是用户要的东西。只管"问"，不管"改"——
     * 带取值参数的 {@code .size/.sampler/.mode/...} 是动作，不算查询。
     */
    static boolean queryCommand(String command) {
        Matcher matcher = Pattern.compile("(?i)^/(help|settings|progress|usage|imgcnt|size|sampler|model|imgmode|mode"
                + "|preset|function|vae|sd|lora|style|prompt|promptr|gen)(?:\\s+([\\s\\S]*))?$").matcher(command == null ? "" : command.strip());
        if (!matcher.matches()) return false;
        String verb = matcher.group(1).toLowerCase(Locale.ROOT);
        String argument = Objects.requireNonNullElse(matcher.group(2), "").strip().toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "help", "settings", "progress", "usage" -> true;
            // 不带参数的这些是"查当前值"；带上取值/动作词就是改东西（改东西只算一句肯定）。
            case "imgcnt", "prompt", "promptr" -> argument.isEmpty();
            case "preset" -> argument.isEmpty() || argument.matches("(list|show)(\\s.*)?");
            case "function" -> argument.isEmpty() || argument.matches("(list|active|prompt|show)(\\s.*)?");
            case "size", "sampler", "model", "imgmode", "mode", "sd", "vae" ->
                    argument.isEmpty() || argument.equals("status") || argument.equals("状态")
                            || argument.equals("list") || argument.equals("列表");
            case "lora" -> argument.isEmpty() || argument.matches("(list|status|triggers|search)(\\s.*)?");
            case "style" -> argument.isEmpty() || argument.matches("(list|show|prompt)(\\s.*)?");
            case "gen" -> argument.matches("(status|状态|列表|list)(\\s.*)?");
            default -> false;
        };
    }

    /** 一句肯定（默认档位）：常规档下会先记进这条指令的收据，收尾时可能被失败说明顶掉或被图片让位。 */
    private void reply(JsonObject event, String text) { replyRanked(event, ReceiptRank.AFFIRM, text); }
    /** 过程行：常规档一律不出声（debug 照旧）。 */
    private void replyProcess(JsonObject event, String text) { replyRanked(event, ReceiptRank.PROCESS, text); }
    /**
     * 失败说明：常规档也一定要发出来。它在这条指令里优先级最高，会把更早记下的"肯定"顶掉——
     * 所以一条指令仍然是 1 条文本，而失败原因一个字都不会少。
     */
    private void replyFailure(JsonObject event, String text) { replyRanked(event, ReceiptRank.FAILURE, text); }

    private void replyRanked(JsonObject event, ReceiptRank rank, String text) {
        // `text` is reassigned, so the immutable copy is what the failure handler captures.
        String output = publicCommands(text);
        if (chatDispatchFailed.get() != null && (output.contains("仅 owner") || output.contains("需要管理员权限") || output.contains("请稍后重试") || output.contains("仅管理员"))) chatDispatchFailed.set(true);
        NormalWindow window = normalWindowFor(event);
        if (window != null) {
            Log.info("常规档收据（" + describeConversation(event) + "，" + rank + "）：" + Log.text(output));
            window.offer(rank, output);
            return;
        }
        sendReply(event, output);
    }

    /** 真的把一条文本交给传输层（原 {@code reply} 的行为：先记回执，再发）。 */
    private void sendReply(JsonObject event, String output) {
        Log.info("回复（" + describeConversation(event) + "）：" + Log.text(output));
        ChainRecord chain = chainRecords.get(ChatService.conversationKey(event));
        if (chain != null) { chain.add(output); return; }
        List<String> collected = batchReceipts.get();
        if (collected != null) { collected.add(output); return; }
        // The transport splits long text into nodes inside one forward-message card.
        CompletableFuture<Void> sent = sender.send(event, Maps.text(output));
        sent.exceptionally(e -> { notifyReplyFailure(event, output, e); return null; });
    }
    /**
     * A reply that could not be delivered is reported to the same conversation as a short plain message, so the
     * failure is visible in QQ instead of only in the local log. The notice is sent directly (never through
     * reply) and kept to at most two lines, so it is not folded into a chat record and cannot recurse.
     */
    private void notifyReplyFailure(JsonObject event, String original, Throwable failure) {
        Log.error("回复发送失败（" + describeConversation(event) + "）", failure);
        String reason = error(failure).replaceAll("\\R+", " ").strip();
        String summary = Log.text(original);
        String notice = "回复发送失败：" + reason + (summary.isEmpty() ? "" : "\n原回复摘要（" + original.length() + " 字符）：" + summary);
        sender.send(event, Maps.text(publicCommands(notice)))
                .exceptionally(retry -> { Log.warn("失败提示也未能发送（" + describeConversation(event) + "）：" + error(retry)); return null; });
    }
    static String internalCommand(String text) { return text.startsWith(".") ? "/"+text.substring(1) : text; }
    /**
     * 控制台可以不加点和斜杠（真 CLI 手感）：首词命中已知指令名时补上 "."，其余原样返回。
     * 认不出的裸词不动它——网页控制台会把它当作和机器人说话（走聊天频道）。
     */
    public static String consoleCommand(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.startsWith(".") || trimmed.startsWith("/")) return trimmed;
        String first = trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return CONSOLE_COMMANDS.contains(first) ? "." + trimmed : trimmed;
    }
    /** 控制台认识的指令名（与 PUBLIC_COMMAND_PREFIX 一致，另加常用中文别名）。 */
    private static final Set<String> CONSOLE_COMMANDS = Set.of(
            "help", "yh", "liv", "get", "settings", "chat", "admin", "char", "batch",
            "sampler", "style", "size", "steps", "cfg", "seed", "model", "prompt", "promptr",
            "preset", "function", "lora", "gen", "rg", "imgcnt", "imgmode", "mode", "usage", "map", "progen", "infix", "progress", "vae", "sd",
            "affinity",
            "jrlp", "wife", "marry", "propose", "divorce", "accept", "reject",
            "帮助", "进度", "老婆", "今日老婆", "强娶", "离婚", "同意", "拒绝");
    public static String publicCommands(String text) {
        // Never let a model-invented pseudo tag (for example <system_warning>…) reach a chat.
        if (text.contains("<system")) text = text.replaceAll("(?is)<system[^>]*>[\\s\\S]*?</system[^>]*>", "").replaceAll("(?is)</?system[^>]*>", "");
        text = PUBLIC_COMMAND_PREFIX.matcher(text).replaceAll(".$1");
        // Command names that are not ASCII cannot ride the regex above; every command is shown with ".".
        for (String name : List.of("强娶", "离婚", "结婚", "同意", "拒绝")) text = text.replace("/" + name, "." + name);
        return text;
    }
    private void sendImages(JsonObject event, List<Path> paths) throws Exception { sendImages(event, paths, ""); }

    /**
     * 发送图片（地图这类）。label 非空时先回一句说明——网页控制台只看到图片时无从判断成功与否。
     */
    private void sendImages(JsonObject event, List<Path> paths, String label) throws Exception {
        long total = 0;
        for (Path path : paths) {
            total += java.nio.file.Files.size(path);
            if (total > 100L * 1024 * 1024) throw new IllegalArgumentException("图片总大小超过 100MB，请减少图片。");
        }
        if (!imageBatches.tryAcquire()) throw new IllegalStateException("正在发送图片，请稍后再试。");
        // 说明放在抢到发送名额之后：被拒时用户看到的是"正在发送图片"，而不是一条其实没发出去的说明。
        if (!label.isBlank()) reply(event, label + "：" + paths.size() + " 张（" + String.join("、", paths.stream().map(path -> path.getFileName().toString()).toList()) + "）");
        // 合并转发一次性发出整批；逐张发送时则只在前一张确认之后才读下一张。
        CompletableFuture<Void> sent;
        try {
            if (paths.size() > 1 && forwardRecords) {
                // 多于一张：合成一条合并转发（每张图一个节点），图片在 imageIO 上读好后一次发出。
                Log.info("地图发送方式（" + describeConversation(event) + "）：合并转发 " + paths.size() + " 张");
                sent = CompletableFuture.completedFuture(paths).thenComposeAsync(batch -> {
                    try {
                        List<JsonArray> built = new ArrayList<>(batch.size());
                        for (Path path : batch) built.add(Maps.image(path));
                        return sender.sendRecord(event, built);
                    } catch (Exception e) { return CompletableFuture.<Void>failedFuture(e); }
                }, imageIO).handle((ignored, failure) -> {
                    // 合并转发失败不能丢图：回退成逐张普通发送；回退成败决定 whenComplete 里的报错与信号量释放。
                    if (failure == null) return CompletableFuture.<Void>completedFuture(null);
                    Log.warn("合并转发失败，已改为普通发送 " + paths.size() + " 张（" + describeConversation(event) + "）：" + error(failure));
                    return sendImagesIndividually(event, paths);
                }).thenCompose(future -> future);
            } else {
                // 单张，或传输层不会发合并转发：照旧逐张普通发送（读下一张要等前一张确认）。
                Log.info("地图发送方式（" + describeConversation(event) + "）：" + (paths.size() <= 1
                        ? "单张普通发送" : "普通发送 " + paths.size() + " 张（传输层不支持合并转发）"));
                sent = sendImagesIndividually(event, paths);
            }
            sent.whenComplete((v, e) -> {
                imageBatches.release();
                if (e != null) { Log.error("图片发送失败（" + describeConversation(event) + "）", e); reply(event, "图片发送失败：" + error(e)); }
            });
        } catch (Exception e) { imageBatches.release(); throw e; }
    }
    /** 逐张普通发送（单张/传输层不支持合并转发/合并转发失败回退）：读下一张要等前一张确认。 */
    private CompletableFuture<Void> sendImagesIndividually(JsonObject event, List<Path> paths) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Path path : paths) chain = chain.thenComposeAsync(v -> {
            try { return sender.sendMap(event, Maps.image(path)); }
            catch (Exception e) { return CompletableFuture.<Void>failedFuture(e); }
        }, imageIO);
        return chain;
    }
    public static String messageText(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString().replaceAll("\\[CQ:[^\\]]*\\]", "")
            .replace("&#91;", "[").replace("&#93;", "]").replace("&amp;", "&").strip();
        if (!value.isJsonArray()) return "";
        StringBuilder text = new StringBuilder();
        for (JsonElement item : value.getAsJsonArray()) if (item.isJsonObject()) {
            JsonObject segment = item.getAsJsonObject();
            if (Json.str(segment, "type", "").equals("text")) text.append(Json.str(Json.obj(segment, "data"), "text", ""));
        }
        return text.toString().strip();
    }
    private static String display(String s) { return s.isEmpty() ? "（空）" : s; }
    private static String dimensions(SdClient.GenerationSettings settings) { return settings.width() + " × " + settings.height() + " 像素"; }
    /** 设置回执：采样方法 / 尺寸。不再报告"当前样式"：样式载入后就是用户自己的 prompt 文本。 */
    private String formatSettings(SdClient.GenerationSettings settings, String scope) {
        return "采样方法：" + settings.samplerName()
                + "\n图片尺寸：" + dimensions(settings);
    }
    private static String formatPrompts(SdClient.Prompts p) { return "正向 prompt：\n" + display(p.positive()) + "\n反向 prompt：\n" + display(p.negative()); }
    /**
     * What a prompt rewrite actually changed — added and removed terms only. Dumping the whole prompt pair
     * into a group is unreadable; ".prompt" still shows the full text for anyone who wants it.
     */
    static String formatPromptDiff(SdClient.Prompts before, SdClient.Prompts after) {
        StringBuilder out = new StringBuilder();
        appendTermDiff(out, "正向", before == null ? "" : before.positive(), after == null ? "" : after.positive());
        appendTermDiff(out, "反向", before == null ? "" : before.negative(), after == null ? "" : after.negative());
        return out.toString().strip();
    }
    private static void appendTermDiff(StringBuilder out, String label, String before, String after) {
        List<String> oldTerms = PromptEditor.parts(before), newTerms = PromptEditor.parts(after);
        Set<String> oldKeys = new HashSet<>(), newKeys = new HashSet<>();
        for (String term : oldTerms) oldKeys.add(PromptEditor.key(term));
        for (String term : newTerms) newKeys.add(PromptEditor.key(term));
        List<String> added = new ArrayList<>(), removed = new ArrayList<>();
        for (String term : newTerms) if (!oldKeys.contains(PromptEditor.key(term))) added.add(term);
        for (String term : oldTerms) if (!newKeys.contains(PromptEditor.key(term))) removed.add(term);
        if (added.isEmpty() && removed.isEmpty()) { out.append(label).append("：无变化\n"); return; }
        out.append(label).append("：");
        if (!added.isEmpty()) out.append("新增 ").append(brief(added));
        if (!removed.isEmpty()) out.append(added.isEmpty() ? "" : "；").append("移除 ").append(brief(removed));
        out.append('\n');
    }
    private static String brief(List<String> terms) {
        int shown = Math.min(20, terms.size());
        return String.join("、", terms.subList(0, shown)) + (terms.size() > shown ? " 等 " + terms.size() + " 项" : "");
    }
    public static String error(Throwable e) {
        while ((e instanceof CompletionException || e instanceof ExecutionException) && e.getCause() != null) e = e.getCause();
        String text = e.getMessage();
        if (text == null || text.isBlank()) text = e.getClass().getSimpleName();
        return text.length() > 600 ? text.substring(0, 600) : text;
    }
    /** Chat chains still executing; a graceful shutdown lets them finish instead of aborting them. */
    int activeChatWorkflows() { return activeChatWorkflows.get(); }
    @Override public void close() {
        closed.set(true); loraClosed.set(true);
        // 关停前把回执索引里还没落盘的变化补上（已读标记、最后几段摘要）。
        observeQuests();
        persistQuests(true);
        // Bounded grace period: a chain that is mid-way (for example .style load → .infix → .gen) must not
        // be cut in half, but shutdown still has to be prompt.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (activeChatWorkflows.get() > 0 && System.nanoTime() < deadline) {
            try { Thread.sleep(50); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        synchronized (generationLock) { generationJobs.clear(); waitingGenerations = BigInteger.ZERO; suspendedGenerations = BigInteger.ZERO; generation.shutdownNow(); }
        chat.close();chatWorkflows.shutdownNow();chatWorkflowSteps.values().forEach(future->future.complete(false));chatWorkflowSteps.clear();
        loraIO.shutdownNow(); progenIO.shutdownNow(); outboxDelivery.shutdownNow(); drainingImages.set(false); imageIO.shutdownNow();
    }
}

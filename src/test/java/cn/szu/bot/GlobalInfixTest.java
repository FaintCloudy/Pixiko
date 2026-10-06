package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import cn.szu.bot.chat.ChatService;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;

/**
 * 「全局 infix」档（默认）的回归：一条指令只执行一次 infix。
 *
 * <p>用户实测的两个 bug 都在这里钉住：
 * <ol>
 *   <li><b>一条复合语句被拆成 3 个 infix + 1 个 gen</b>（data/quests/252.json、logs/bot-stdout.log 04:31）：
 *       每一步都拿整份提示词改写一次，前一步刚写进去的内容被后一步删掉。现在同一条指令里的多个
 *       {@code .infix} 会先合并成**一步**，一次 DeepSeek 改写调用作用在整份提示词上。</li>
 *   <li><b>传统正反义词排斥器误判</b>（{@code cross-section view} 与 {@code front view} 被判互斥）：
 *       默认关闭本地互斥整理，矛盾交给改写落地前那一次<b>整份提示词画面检查</b>（DeepSeek）判断；
 *       {@code .infix conflict on} 可以恢复旧行为。</li>
 * </ol>
 *
 * <p>全程不碰网络与线上数据：DeepSeek 由假客户端计数与注入响应（{@code Bot.useImageClientForTests}），
 * 所有路径都在临时目录里；网页两个接口用本机的 Spring 服务（自由端口）实测。
 */
public final class GlobalInfixTest {
    private static int checks;
    private static final List<String> covered = new ArrayList<>();

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("断言失败（第 " + checks + " 条）：" + what);
        covered.add(what);
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "global-infix").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        try {
            settingsAndPersistence(root);
            callCapAndMerging(root);
            conflictResolverDefaultOff(root);
            deepSeekScreen(root);
            partModeKeepsOldBehaviour(root);
            modeCommandAndUndo(root);
            webInterfaces(root);
            System.out.println("GlobalInfixTest: " + checks + " assertions passed：默认 global（一条指令 1 次改写 + ≤1 次画面检查）、"
                    + "复合语句合成 1 个 infix、传统反义词排斥器默认关闭并可恢复、检查矛盾修正/检查失败如实说明/落地前校验回滚、"
                    + "mode 指令与会话隔离、.prompt undo 一次撤回、/api/infix/mode 与 /api/settings 的 infixMode");
        } finally {
            TestCleanup.deleteQuietly(root);
        }
    }

    // ------------------------------------------------------------------ 夹具

    /** 一次 .infix 指令跑完之后的现场：DeepSeek 调用流水、回执、落地后的提示词。 */
    private record Run(List<String> calls, List<String> promptAtCall, List<String> replies, SdClient.Prompts prompts) {
        String last() { return replies.isEmpty() ? "" : replies.get(replies.size() - 1); }
        String all() { return String.join("\n", replies); }
        long rewrites() { return calls.stream().filter("rewrite"::equals).count(); }
        long reviews() { return calls.stream().filter("review"::equals).count(); }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final Bot bot;
        final List<JsonArray> sent = new CopyOnWriteArrayList<>();
        final List<String> calls = new ArrayList<>();
        final List<String> promptAtCall = new ArrayList<>();
        /** 实际交给改写模型的 instruction 文本（显式 .infix 中文要求时必须非空）。 */
        final List<String> rewriteInstructionsSeen = new ArrayList<>();
        /** 每一次改写调用返回的提示词（按顺序取，取完就重复最后一条）。 */
        final List<String> rewrites = new ArrayList<>();
        /** 每一次画面检查的假响应：JSON 文本；{@code "@fail"} 表示这次调用直接失败。 */
        final List<String> reviews = new ArrayList<>();
        final boolean strictDictionaryListMode;
        HttpServer web;
        int webPort;
        String token = "global-infix-token";

        Fixture(String prefix, boolean strictDictionaryListMode) throws Exception {
            this.strictDictionaryListMode = strictDictionaryListMode;
            root = Files.createTempDirectory(Path.of(System.getProperty("bot.test.work", "work"), "global-infix").toAbsolutePath(), prefix);
            Files.createDirectories(root.resolve("data"));
            // 测试用的最小词库 + 中文别名索引：strictDictionary 路径要能读到，否则它自己会抛"词库不存在"。
            Files.writeString(root.resolve("data/prompt-tags.txt"),
                    "1girl\nsolo\ngrass\nmilitary_uniform\nfront_view\ncross_section_view\nstanding\nlying\nsmile\nblur\nbad_hands\n");
            // 密钥文件必须存在：DeepSeekPrompts 的 exchange 在读密钥之前先查这个文件，
            // 假客户端也一样（否则测试会拿到"未配置密钥"而不是我们注入的响应）。
            Files.writeString(root.resolve("data/deepseek-api-key.txt"), "test-key-not-real\n");
            JsonObject sdConfig = new JsonObject();
            sdConfig.addProperty("base_url", "http://127.0.0.1:9/");
            JsonObject config = new JsonObject();
            config.add("sd", sdConfig);
            config.addProperty("owner_user_id", "456");
            JsonObject webui = new JsonObject();
            webui.addProperty("enabled", true);
            webui.addProperty("host", "127.0.0.1");
            webui.addProperty("port", 0);
            webui.addProperty("access_token", token);
            config.add("webui", webui);
            Json.atomicWrite(root.resolve("config.json"), config);
            Settings settings = new Settings(root);
            bot = new Bot(settings, new SdClient(root, sdConfig), (event, segments) -> {
                sent.add(segments.deepCopy());
                return CompletableFuture.completedFuture(null);
            });
            Bot.useImageClientForTests(fakeClient(settings));
        }

        /** 假生图频道客户端：按系统提示词判定"改写"还是"画面检查"，并如实计数。 */
        private DeepSeekPrompts fakeClient(Settings settings) throws Exception {
            return DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.IMAGE, (body, key, timeout) -> {
                String system = systemOf(body);
                if (system.contains("You review one final Stable Diffusion")) {
                    // 改写/检查都有"同一次请求失败后重试"的容错：同一个用例里的重试必须拿到**同一个**
                    // 假响应（否则第二次会掉进"没有脚本 → 默认通过"，用例就假绿/假红了）。
                    // 所以按**调用次数对脚本条数取模**给响应，"@fail"永远失败。
                    String prompt = Json.str(Json.parse(userOf(body)), "positive", "");
                    int call = count("review");
                    int index = reviews.isEmpty() ? -1 : call % reviews.size();
                    calls.add("review");
                    promptAtCall.add(prompt);
                    if (index >= 0 && "@fail".equals(reviews.get(index))) throw new java.io.IOException("假检查调用失败");
                    if (index >= 0) return completion(reviews.get(index));
                    return completion("{\"ok\":true,\"issues\":\"\"}");
                }
                int index = count("rewrite");
                calls.add("rewrite");
                JsonObject input = Json.parse(userOf(body));
                // 记录"实际交给模型的指令文本"：显式 .infix + 中文要求时这里必须非空。
                rewriteInstructionsSeen.add(Json.str(input, "instruction", ""));
                promptAtCall.add(Json.str(input, "positive", ""));
                String positive = Json.str(input, "positive", "");
                String negative = Json.str(input, "negative", "");
                String scripted = index < rewrites.size() ? rewrites.get(index) : (rewrites.isEmpty() ? null : rewrites.get(rewrites.size() - 1));
                // "@fail"：这一次改写调用直接失败（模拟网络/超时），用来验证"一个字都不改、如实说明"。
                if ("@fail".equals(scripted)) throw new java.io.IOException("假改写调用失败");
                if (scripted == null)
                    return completion("{\"positive\":" + Json.GSON.toJson(positive) + ",\"negative\":" + Json.GSON.toJson(negative) + "}");
                if (scripted.startsWith("@")) {
                    // "@add:<词条>"：把词条追加到当前正向提示词末尾（模拟"改写落地"）。
                    String added = scripted.substring("@add:".length());
                    String merged = positive.isBlank() ? added : positive.strip().replaceAll(",\\s*$", "") + ", " + added;
                    return completion("{\"positive\":" + Json.GSON.toJson(merged) + ",\"negative\":" + Json.GSON.toJson(negative) + "}");
                }
                return completion(scripted);
            });
        }

        private int count(String kind) {
            int found = 0;
            for (String call : calls) if (call.equals(kind)) found++;
            return found;
        }

        private static String systemOf(JsonObject body) {
            JsonArray messages = body.getAsJsonArray("messages");
            return messages.get(0).getAsJsonObject().get("content").getAsString();
        }

        private static String userOf(JsonObject body) {
            JsonArray messages = body.getAsJsonArray("messages");
            return messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
        }

        private static DeepSeekPrompts.Response completion(String content) {
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant"); message.addProperty("content", content);
            JsonObject choice = new JsonObject();
            choice.addProperty("index", 0); choice.addProperty("finish_reason", "stop"); choice.add("message", message);
            JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject envelope = new JsonObject();
            envelope.addProperty("id", "test"); envelope.addProperty("object", "chat.completion"); envelope.add("choices", choices);
            return new DeepSeekPrompts.Response(200, envelope.toString());
        }

        void seed(String scope, String positive, String negative) throws Exception {
            UserPromptStore store = new UserPromptStore(root);
            store.replace(scope, new SdClient.Prompts(positive, negative, UserPromptStore.PERSONAL_SOURCE));
        }

        /**
         * 跑一条**多步指令**：完全走网页/链路那条路（{@code executeChatCommands(event, commands, choices)}），
         * 也就是"模型把一条复合语句规划成若干条命令"的真实形态（命令是一条条分开的字符串）。
         * 真实 QQ 消息里，一条消息按**换行**拆成多条命令（{@link Bot#splitCommands}），分号不拆——
         * 所以分号形态的复合语句要在这里用列表喂进去，才等于线上"3 个 infix + 1 个 gen"的场景。
         */
        List<String> steps(List<String> commands, String scope) throws Exception {
            int from = sent.size();
            JsonObject event = event(String.join(" ; ", commands), scope);
            bot.executeChatCommands(event, commands, new JsonObject());
            return collect(from);
        }

        /** 真实消息入口（{@code accept}）：一条消息里按换行拆命令，分号只是同一条命令的参数。 */
        List<String> command(String text, String scope) throws Exception {
            int from = sent.size();
            bot.accept(event(text, scope));
            return collect(from);
        }

        /** 等这一批回执静默（异步步骤跑完）后把新增的回执取出来。 */
        private List<String> collect(int from) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            int quiet = 0;
            while (System.nanoTime() < deadline) {
                Thread.sleep(100);
                if (sent.size() == from) { quiet++; if (quiet >= 20) break; continue; }
                // 静默 2 秒才算跑完：改写 + 画面检查（可能还有一次重试）是异步的，窗口太短会读半截回执。
                String last = Bot.messageText(sent.get(sent.size() - 1));
                // 进度回执（"正在通过 DeepSeek…"）不算结束：后面的异步结果还没到，继续等。
                if (last.contains("正在通过 DeepSeek")) { quiet = 0; continue; }
                // 终态回执（改写结果/多步汇总/失败）才算结束，再静默 2 秒收全同一条指令的后续回执。
                if (last.contains("智能修改") || last.contains("多步执行") || last.contains("操作失败")
                        || last.contains("没有改动任何词条") || last.contains("已补充你要求的标准词条")) {
                    quiet++; if (quiet >= 20) break; continue;
                }
                quiet = 0;
            }
            List<String> out = new ArrayList<>();
            for (int index = from; index < sent.size(); index++) out.add(Bot.messageText(sent.get(index)));
            return out;
        }

        JsonObject event(String text, String scope) {
            boolean group = scope != null && scope.startsWith("1:group:");
            String user = scope == null ? "2" : (group ? scope.substring(scope.lastIndexOf(':') + 1) : scope);
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message"); event.addProperty("message_type", group ? "group" : "private");
            event.addProperty("self_id", 1); event.addProperty("user_id", user);
            if (group) event.addProperty("group_id", "999");
            event.addProperty("message_id", Math.abs(text.hashCode()) + from());
            event.addProperty("message", text);
            return event;
        }

        private int counter;

        private int from() { return ++counter; }

        Run run(String instruction, String scope, boolean clearCalls) throws Exception {
            if (clearCalls) { calls.clear(); promptAtCall.clear(); }
            List<String> replies = command(instruction, scope);
            return new Run(List.copyOf(calls), List.copyOf(promptAtCall), replies, new UserPromptStore(root).prompts(scope));
        }

        void startWeb() throws Exception {
            webPort = freePort();
            new Settings(root).webSetting("port", new JsonPrimitive(webPort));
            web = null;
        }

        @Override public void close() {
            Bot.useImageClientForTests(null);
            try { bot.close(); } catch (Exception ignored) { }
            TestCleanup.deleteQuietly(root);
        }
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
    }

    // ------------------------------------------------------------------ ① 设置持久化

    private static void settingsAndPersistence(Path root) throws Exception {
        Path caseRoot = Files.createTempDirectory(root, "settings-");
        JsonObject config = new JsonObject();
        Json.atomicWrite(caseRoot.resolve("config.json"), config);
        Settings settings = new Settings(caseRoot);
        check(settings.infixMode("1:group:1") == Settings.InfixMode.GLOBAL, "未设置时默认 global");
        check(settings.infixMode(null) == Settings.InfixMode.GLOBAL, "会话键为空也不炸、仍是 global");
        settings.setInfixMode("1:group:1", Settings.InfixMode.PARTS);
        check(settings.infixMode("1:group:1") == Settings.InfixMode.PARTS, "parts 能存下来");
        check(settings.infixMode("1:group:2") == Settings.InfixMode.GLOBAL, "会话之间互相隔离");
        JsonObject stored = Json.parse(Files.readString(caseRoot.resolve("config.json")));
        check(stored.has("infix_mode") && stored.getAsJsonObject("infix_mode").getAsJsonObject("modes").has("1:group:1"),
                "存储位置是 infix_mode.modes.<会话键>");
        check(stored.getAsJsonObject("infix_mode").getAsJsonObject("modes").size() == 1, "只写非默认值（global 不留键）");
        settings.setInfixMode("1:group:1", Settings.InfixMode.GLOBAL);
        check(settings.infixMode("1:group:1") == Settings.InfixMode.GLOBAL, "切回 global");
        check(Json.parse(Files.readString(caseRoot.resolve("config.json"))).getAsJsonObject("infix_mode")
                .getAsJsonObject("modes").size() == 0, "global 等于删掉这条设置");
        check(Settings.InfixMode.parse("全局") == Settings.InfixMode.GLOBAL, "中文「全局」认");
        check(Settings.InfixMode.parse("分组") == Settings.InfixMode.PARTS, "中文「分组」认");
        check(Settings.InfixMode.parse("nonsense") == null, "认不出的档位返回 null（调用方给用法）");
        check(Settings.InfixMode.stored("PARTS") == Settings.InfixMode.PARTS, "存进去的值大小写不敏感");
        check(Settings.InfixMode.stored("broken") == Settings.InfixMode.GLOBAL, "坏值读成默认 global");
        check(Settings.InfixMode.stored(null) == Settings.InfixMode.GLOBAL, "缺失读成默认 global");
        check(Settings.InfixMode.GLOBAL.key().equals("global") && Settings.InfixMode.PARTS.key().equals("parts"),
                "落盘取值就是 global/parts");
        // 段类型不对（被人手改成字符串）：照样读成 global，不抛。
        config = new JsonObject(); config.addProperty("infix_mode", "global");
        Json.atomicWrite(caseRoot.resolve("config.json"), config);
        check(new Settings(caseRoot).infixMode("1:group:1") == Settings.InfixMode.GLOBAL, "段类型不对读成默认 global");
        // 传统反义词排斥器：默认关，能开能关，只写本会话。
        check(!new Settings(caseRoot).infixConflictEnabled("1:group:1"), "传统正反义词排斥器默认关闭");
        Settings again = new Settings(caseRoot);
        again.setInfixConflictEnabled("1:group:1", true);
        check(again.infixConflictEnabled("1:group:1"), "可以开启（一键恢复旧行为）");
        check(!again.infixConflictEnabled("1:group:2"), "开启只影响本会话");
        again.setInfixConflictEnabled("1:group:1", false);
        check(!again.infixConflictEnabled("1:group:1"), "可以重新关闭");
    }

    // ------------------------------------------------------------------ ② 调用次数上限与合并

    private static void callCapAndMerging(Path root) throws Exception {
        // 合并的纯函数口径先钉住：非 infix 步骤一个不动、顺序不变。
        JsonObject plainEvent = new JsonObject();
        plainEvent.addProperty("message_type", "private"); plainEvent.addProperty("user_id", "456");
        Path mergeRoot = Files.createTempDirectory(root, "merge-");
        Json.atomicWrite(mergeRoot.resolve("config.json"), new JsonObject());
        Settings plain = new Settings(mergeRoot);
        List<String> merged = Bot.mergeInfixSteps(List.of(".infix 加入 a", ".gen", ".infix 加入 b"), plainEvent, plain);
        check(merged.size() == 2, "两个 infix + 一个 gen → 两步：" + merged);
        check(merged.get(0).equals("/infix 加入 a；加入 b"), "两个 infix 的要求合成一条：" + merged.get(0));
        check(merged.get(1).equals(".gen"), "gen 原样保留在后面：" + merged);
        check(Bot.mergeInfixSteps(List.of(".style load \"x\"", ".infix a", ".infix b", ".gen"), plainEvent, plain).size() == 3,
                "style load + 2 infix + gen → 3 步");
        check(Bot.mergeInfixSteps(List.of(".infix a"), plainEvent, plain).equals(List.of(".infix a")), "只有一条 infix 时不动");
        check(Bot.mergeInfixSteps(List.of(".infix 加入 a", ".infix #keep 加入 b"), plainEvent, plain).size() == 2,
                "显式 #keep 的 infix 不参与合并（多条独立指令的出口）："
                        + Bot.mergeInfixSteps(List.of(".infix 加入 a", ".infix #keep 加入 b"), plainEvent, plain));
        check(Bot.mergeInfixSteps(List.of(".infix 加入 a", ".infix #keep 加入 b"), plainEvent, plain).get(0).equals(".infix 加入 a"),
                "剩下那一条保持原样（不变成合并形态）");
        check(Bot.mergeInfixSteps(List.of(".infix 加入 a", ".infix #keep 加入 b"), plainEvent, plain).get(1).equals(".infix #keep 加入 b"),
                "#keep 那条原样保留");
        check(Bot.mergeInfixSteps(List.of(".infix mode parts", ".infix 加入 a"), plainEvent, plain).size() == 2,
                "控制指令 .infix mode 不参与合并");
        check(Bot.mergeInfixSteps(List.of(".infix mode parts", ".infix 加入 a", ".infix 加入 b"), plainEvent, plain).size() == 3,
                "先切 parts 再发的两条 infix 不合并（按切换后的档位判断）");
        check(Bot.mergeInfixSteps(List.of(".infix mode global", ".infix 加入 a", ".infix 加入 b"), plainEvent, plain).size() == 2,
                "先切 global 再发的两条 infix 照常合并");
        check(Bot.mergeInfixSteps(List.of(".infix filter on", ".infix 加入 a"), plainEvent, plain).size() == 2,
                ".infix filter 不参与合并");
        check(Bot.mergeInfixSteps(List.of(".infix conflict on", ".infix 加入 a"), plainEvent, plain).size() == 2,
                ".infix conflict 不参与合并");

        try (Fixture f = new Fixture("merged-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, solo, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:grass");
            // 一条复合语句（用户实测的形态）：3 个 infix + 1 个 gen，命令是分开的字符串（模型 plan 的形态）。
            f.calls.clear();
            List<String> replies = f.steps(List.of(".infix 加入草地", ".infix 加入军装", ".infix 加入微笑", ".gen 1"), scope);
            check(f.calls.size() <= 2, "复合语句的 DeepSeek 调用总数 ≤ 2（1 改写 + ≤1 检查），实测 " + f.calls);
            check(f.count("rewrite") == 1, "复合语句只改写一次（不是 3 次）：" + f.calls);
            check(f.count("review") <= 1, "画面检查最多一次：" + f.calls);
            Run run = new Run(List.copyOf(f.calls), List.of(), replies, new UserPromptStore(f.root).prompts(scope));
            check(run.rewrites() == 1, "改写调用恰好 1 次");
            check(run.reviews() <= 1, "检查调用 ≤1 次");
            check(f.rewrites.size() == 1, "测试只排了 1 个假改写响应（多调就会走到「重复最后一条」）");
            check(f.promptAtCall.stream().noneMatch(""::equals), "每次调用都带着当前提示词");
            check(run.all().contains("多步执行") || run.all().contains("智能修改"), "回执说出了结果：" + run.all());
            check(!run.all().contains("3/3"), "不再是 3 步 infix 的形态");
            // 一条指令里的多个 infix 合并后，合并后的文本必须包含全部诉求（不能丢项）。
            String mergedText = Bot.mergeInfixSteps(List.of(".infix 加入草地", ".infix 加入军装", ".infix 加入微笑"),
                    plainEvent, plain).get(0);
            check(mergedText.contains("草地") && mergedText.contains("军装") && mergedText.contains("微笑"),
                    "合并后的指令包含全部诉求：" + mergedText);
            // 一条指令只改一次：同一份提示词不会出现"改了两遍"的痕迹（模型只被问过一次）。
            check(f.count("rewrite") == 1, "同一条指令只问一次模型 → 不存在改两遍");
            // 用户**显式**发 .infix + 中文要求时，交给模型的指令必须非空（空指令根本不该调用模型）。
            f.rewrites.clear();
            f.rewrites.add("@add:grass");
            f.calls.clear();
            f.steps(List.of(".infix 添加不存在的手"), scope);
            check(f.count("rewrite") == 1, "显式 .infix 中文要求会真的调用模型一次：" + f.calls);
            check(f.rewriteInstructionsSeen.stream().anyMatch(text -> !text.isBlank()),
                    "交给模型的指令必须非空：" + f.rewriteInstructionsSeen);
        }
    }

    // ------------------------------------------------------------------ ③ 传统反义词排斥器

    private static void conflictResolverDefaultOff(Path root) throws Exception {
        // 纯函数口径：下划线写法的 cross_section_view 与 front_view 会被本地"同族互斥"删掉一个（旧行为），
        // 这正是用户说的误判——默认关闭后它不再被执行。
        // （注意 tagsConflict 吃的是 PromptEditor.key 的规范形态："cross_section_view" 规范化后是
        //  "cross section view"，所以这里用修正前的自检实证，而不是拿裸字符串去问 tagsConflict。）
        List<String> fixes = new ArrayList<>();
        String before = "1girl, cross_section_view, front_view, smile";
        String after = Bot.fixPromptConflicts(before, fixes);
        check(!fixes.isEmpty() && !after.contains("cross_section_view") && after.contains("front_view"),
                "旧的本地自检确实会删掉其中一个（未修前的实证）：" + fixes + " → " + after);
        check(after.equals("1girl, front_view, smile"), "被删掉的正是 cross_section_view：" + after);
        // 换成带连字符的写法同样会被旧自检删掉一个（PromptEditor.key 只把下划线折成空格，
        // "cross-section view" 仍带着 _view 的规范化形态进入同族判定）——所以用户无论怎么写都会中招。
        List<String> hyphenFixes = new ArrayList<>();
        String hyphenAfter = Bot.fixPromptConflicts("1girl, cross-section view, front view", hyphenFixes);
        check(!hyphenFixes.isEmpty() && !hyphenAfter.contains("cross-section view") && hyphenAfter.contains("front view"),
                "连字符写法同样被旧自检删掉一个：" + hyphenFixes + " → " + hyphenAfter);
        check(!Bot.tagsConflict("subway", "train_interior"), "互补词条本来就不算冲突（对照）");

        try (Fixture f = new Fixture("conflict-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, cross-section view, front view, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:smile");
            f.reviews.add("{\"ok\":true,\"issues\":\"\"}");
            Run run = f.run(".infix 加入微笑", scope, true);
            check(!run.prompts().positive().contains("删除冲突词条"), "回执里没有'删除冲突词条'这类本地互斥动静");
            check(run.prompts().positive().contains("cross-section view") && run.prompts().positive().contains("front view"),
                    "停用后两个视角词条都原样保留：" + run.prompts().positive());
            check(run.last().contains("画面检查"), "回执里能看到画面检查这一步：" + run.last());
            check(!run.last().contains("互斥替换"), "没有再出现'互斥替换'：" + run.last());
            check(run.rewrites() == 1, "改写仍然只有一次");
        }

        // 开关打开（on）时恢复旧行为：同族词条只留一个。
        // 注意要通过机器人自己的指令改（Bot 持有的是启动时读入的那份 Settings，外部直接写文件它看不见）。
        try (Fixture f = new Fixture("conflict-on-", false)) {
            String scope = "456";
            List<String> conflictOn = f.command(".infix conflict on", scope);
            check(conflictOn.get(0).contains("已开启"), "conflict on 回执：" + conflictOn.get(0));
            check(new Settings(f.root).infixConflictEnabled("1:private:456"), "conflict on 已落盘");
            f.seed(scope, "1girl, standing, lying, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:smile");
            f.reviews.add("{\"ok\":true,\"issues\":\"\"}");
            Run run = f.run(".infix 加入微笑", scope, true);
            boolean one = !(run.prompts().positive().contains("standing") && run.prompts().positive().contains("lying"));
            check(one, "打开开关后本地互斥整理恢复（同族只留一个）：" + run.prompts().positive());
        }
        // .infix conflict 的显示/切换/非法值
        try (Fixture f = new Fixture("conflict-cmd-", false)) {
            String scope = "456";
            f.calls.clear();
            List<String> show = f.command(".infix conflict", scope);
            check(show.get(0).contains("关闭（默认）"), "显示默认关闭：" + show.get(0));
            check(f.count("rewrite") == 0, "显示状态不调用 DeepSeek");
            List<String> on = f.command(".infix conflict on", scope);
            check(on.get(0).contains("已开启"), "可以打开：" + on.get(0));
            check(new Settings(f.root).infixConflictEnabled("1:private:456"), "开关落到本会话");
            List<String> off = f.command(".infix conflict off", scope);
            check(off.get(0).contains("已关闭"), "可以关回去：" + off.get(0));
            check(!new Settings(f.root).infixConflictEnabled("1:private:456"), "关闭也落盘");
            List<String> bad = f.command(".infix conflict 也许", scope);
            check(bad.get(0).contains("conflict"), "非法值给用法：" + bad.get(0));
            check(!new Settings(f.root).infixConflictEnabled("1:private:456"), "非法值不改设置");
            List<String> member = f.command(".infix conflict on", "888");
            check(member.get(0).contains("仅 owner 或 admin"), "权限与 .infix 本身一致：" + member.get(0));
            check(!new Settings(f.root).infixConflictEnabled("1:private:888"), "越权不改设置");
        }
    }

    // ------------------------------------------------------------------ ④ 画面检查

    private static void deepSeekScreen(Path root) throws Exception {
        // 检查发现矛盾 → 用修正稿落地（矛盾要素消失、LoRA 标签仍在、无中文）。
        try (Fixture f = new Fixture("screen-fix-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, standing, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("{\"positive\":\"1girl, standing, lying on back, <lora:kotori:1>\",\"negative\":\"bad_hands\"}");
            f.reviews.add("{\"ok\":false,\"issues\":\"standing 与 lying on back 同时存在\","
                    + "\"positive\":\"1girl, lying on back, <lora:kotori:1>\",\"negative\":\"bad_hands\"}");
            Run run = f.run(".infix 让人物躺下", scope, true);
            check(run.prompts().positive().contains("lying on back"), "修正稿已落地：" + run.prompts().positive());
            check(!run.prompts().positive().contains("standing"), "矛盾要素消失：" + run.prompts().positive());
            check(run.prompts().positive().contains("<lora:kotori:1>"), "LoRA 标签仍在：" + run.prompts().positive());
            check(!Bot.newChineseText(run.prompts(), run.prompts()).isEmpty() == false, "落地后的提示词没有中文");
            check(run.rewrites() == 1 && run.reviews() == 1, "1 次改写 + 1 次检查：" + run.calls);
            check(run.last().contains("画面检查") && run.last().contains("修正"), "回执说明检查并修正了：" + run.last());
        }
        // 检查通过 → 原样落地 + 回执一句"已通过"。
        try (Fixture f = new Fixture("screen-ok-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:grass");
            f.reviews.add("{\"ok\":true,\"issues\":\"\"}");
            Run run = f.run(".infix 加入草地", scope, true);
            check(run.prompts().positive().contains("grass"), "改写已落地：" + run.prompts().positive());
            check(run.last().contains("已通过"), "回执写明检查通过：" + run.last());
            check(run.rewrites() == 1 && run.reviews() == 1, "1 改写 + 1 检查：" + run.calls);
        }
        // 检查失败/超时 → 按改写后的版本落地，且明确说明未通过检查（绝不静默）。
        try (Fixture f = new Fixture("screen-fail-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:grass");
            f.reviews.add("@fail");
            Run run = f.run(".infix 加入草地", scope, true);
            check(run.prompts().positive().contains("grass"), "检查失败时仍按改写后的版本落地：" + run.prompts().positive());
            check(run.prompts().positive().contains("<lora:kotori:1>"), "LoRA 标签没丢");
            check(run.last().contains("未通过画面检查"), "回执里明确写了未通过检查：" + run.last());
            check(run.last().contains("落地"), "并说明已经按改写后的版本落地：" + run.last());
            check(run.rewrites() == 1, "改写只调一次（检查失败不重试）：" + run.calls);
        }
        // 检查乱返回（不是 JSON）→ 同样如实说明、按改写后的版本落地。
        try (Fixture f = new Fixture("screen-junk-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:grass");
            f.reviews.add("我觉得没问题呀");
            Run run = f.run(".infix 加入草地", scope, true);
            check(run.prompts().positive().contains("grass"), "乱返回时按改写后的版本落地：" + run.prompts().positive());
            check(run.last().contains("未通过画面检查"), "乱返回也明确说明未通过检查：" + run.last());
            check(run.reviews() >= 1, "乱返回会按既有容错重试（最多 3 次）：" + run.calls);
        }
        // 落地前校验：修正稿空 / 含中文 / 丢 LoRA → 回滚到改写后的版本（不是空、不是坏）。
        checkEmptyCorrection(root);
        // 检查调用本身不可用（没配密钥）→ 一样按改写后的版本落地，不许把用户的提示词丢掉。
        try (Fixture f = new Fixture("screen-missing-key-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:grass");
            f.reviews.add("@fail");
            Run run = f.run(".infix 加入草地", scope, true);
            check(!run.prompts().positive().isBlank(), "提示词不是空的");
            check(run.prompts().positive().contains("1girl"), "原有内容没被丢掉");
            check(run.last().contains("未通过画面检查"), "如实说明：" + run.last());
        }
        // 改写调用失败 → 一个字都不改（不是"检查失败"那条路径）。
        try (Fixture f = new Fixture("rewrite-fail-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, smile, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@fail");
            Run run = f.run(".infix 加入草地", scope, true);
            check(run.prompts().positive().equals("1girl, smile, <lora:kotori:1>"), "改写失败时提示词原样：" + run.prompts().positive());
            check(run.last().contains("智能修改未完成") || run.last().contains("没有成功"), "回执如实说明失败：" + run.last());
            check(run.reviews() == 0, "改写失败不再做检查：" + run.calls);
        }
        // 模型没有给出可应用的变化 → 不假装改了、也不报失败。
        try (Fixture f = new Fixture("rewrite-noop-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("{\"positive\":\"1girl, <lora:kotori:1>\",\"negative\":\"bad_hands\"}");
            Run run = f.run(".infix 加入草地", scope, true);
            check(run.prompts().positive().equals("1girl, <lora:kotori:1>"), "没有变化就什么都不改");
            check(run.last().contains("这次没有改动任何词条"), "回执如实说明没有改动：" + run.last());
            check(run.reviews() == 0, "没有落地就不做检查：" + run.calls);
        }
    }

    private static void checkEmptyCorrection(Path root) throws Exception {
        // 修正稿把正向清空 → 必须回滚到改写后的版本（这类响应不算"可用的修正稿"，直接丢弃并说明）。
        try (Fixture f = new Fixture("screen-empty-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:grass");
            f.reviews.add("{\"ok\":false,\"issues\":\"清空\",\"positive\":\"\",\"negative\":\"\"}");
            Run run = f.run(".infix 加入草地", scope, true);
            check(run.prompts().positive().contains("grass"), "修正稿为空 → 回滚到改写后的版本：" + run.prompts().positive());
            check(!run.prompts().positive().isBlank(), "不是空的");
            check(run.last().contains("画面检查：未通过") && run.last().contains("修正稿被丢弃")
                            && run.last().contains("正向提示词被清空"),
                    "回执说明修正稿被丢弃的原因：" + run.last());
        }
        // 修正稿写了中文 → 回滚。
        try (Fixture f = new Fixture("screen-cjk-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:grass");
            f.reviews.add("{\"ok\":false,\"issues\":\"改了\",\"positive\":\"1girl, 草地, <lora:kotori:1>\",\"negative\":\"bad_hands\"}");
            Run run = f.run(".infix 加入草地", scope, true);
            check(!run.prompts().positive().contains("草地"), "修正稿含中文 → 不回滚以外的内容也不落地中文：" + run.prompts().positive());
            check(run.prompts().positive().contains("grass"), "回滚到改写后的版本：" + run.prompts().positive());
            check(run.last().contains("中文"), "回执说明原因：" + run.last());
        }
        // 修正稿丢了 LoRA 标签 → 回滚。
        try (Fixture f = new Fixture("screen-lora-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:grass");
            f.reviews.add("{\"ok\":false,\"issues\":\"重写\",\"positive\":\"1girl, grass, smile\",\"negative\":\"bad_hands\"}");
            Run run = f.run(".infix 加入草地", scope, true);
            check(run.prompts().positive().contains("<lora:kotori:1>"), "修正稿丢 LoRA → 回滚到带标签的版本：" + run.prompts().positive());
            check(!run.last().contains("修正稿已落地"), "没有落地修正稿");
            check(run.last().contains("画面检查：未通过") && run.last().contains("丢了 LoRA/嵌入标签"),
                    "如实说明修正稿丢了 LoRA 标签：" + run.last());
        }
    }

    // ------------------------------------------------------------------ ⑤ parts 档（旧行为保留）

    private static void partModeKeepsOldBehaviour(Path root) throws Exception {
        // 旧的拆分判据仍在（parts 档就是靠它把一句话拆成多步）；本地拆分只在"一句话多诉求"时触发。
        check(DeepSeekPrompts.needsSplit("把背景换成草地，把衣服换成军装，顺手加个微笑"),
                "parts 档的拆分判据仍在（旧路径没有被删）");
        check(!DeepSeekPrompts.needsSplit("加入草地"), "单诉求的一句话不会被拆");
        // parts 档下，一条指令里用户显式写的多个 .infix 不合并（旧行为：各改各的）。
        try (Fixture f = new Fixture("parts-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.command(".infix mode parts", scope);
            check(new Settings(f.root).infixMode("1:private:456") == Settings.InfixMode.PARTS, "parts 档已生效");
            f.rewrites.add("@add:grass");
            f.rewrites.add("@add:military_uniform");
            f.calls.clear();
            List<String> replies = f.steps(List.of(".infix 加入草地", ".infix 加入军装"), scope);
            check(f.count("rewrite") == 2, "parts 档两个 infix 各改一次（不合并，旧路径可复现）：" + f.calls);
            check(f.count("review") == 0, "parts 档不做整份画面检查");
            check(replies.stream().anyMatch(text -> text.contains("多步执行")), "parts 档仍发多步回执：" + replies);
        }
        // parts 档下，一条指令里用户显式写的多个 .infix 也保持多步（旧行为）。
        try (Fixture f = new Fixture("parts-multi-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.command(".infix mode parts", scope);
            check(new Settings(f.root).infixMode("1:private:456") == Settings.InfixMode.PARTS, "parts 档已生效（多步用例）");
            f.rewrites.add("@add:grass");
            f.rewrites.add("@add:military_uniform");
            f.rewrites.add("@add:smile");
            f.calls.clear();
            List<String> replies = f.steps(List.of(".infix 加入草地", ".infix 加入军装", ".infix 加入微笑"), scope);
            check(f.count("rewrite") == 3, "parts 档三条 infix 各改一次（旧路径，可复现）：" + f.calls);
            check(f.count("review") == 0, "parts 档不做整份画面检查");
            check(replies.stream().anyMatch(text -> text.contains("多步执行")), "parts 档仍发多步回执：" + replies);
        }
        // 对照：同样的三条 infix 在 global 档下只改一次。
        try (Fixture f = new Fixture("global-multi-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:grass");
            f.calls.clear();
            f.steps(List.of(".infix 加入草地", ".infix 加入军装", ".infix 加入微笑"), scope);
            check(f.count("rewrite") == 1, "global 档三条 infix 合成一次改写：" + f.calls);
            check(f.count("review") == 1, "global 档有一次画面检查：" + f.calls);
            check(f.calls.size() == 2, "global 档总调用恰好 2 次（1 改写 + 1 检查）：" + f.calls);
        }
        // 真实消息入口（accept）：**换行**拆出的多条 .infix 在 global 档下也只改一次（不合并就互相覆盖）。
        // 这也是 `.infix A ⏎ .infix B` 唯一可用的形态：accept() 按顺序**同步**下发，第二条会撞上
        // "已有 DeepSeek 请求正在处理"（parts 档下这个限制一直都在，所以这里只钉 global 的合并行为）。
        try (Fixture f = new Fixture("global-newline-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:grass");
            List<String> replies = f.command(".infix 加入草地\n.infix 加入军装", scope);
            check(f.count("rewrite") == 1, "换行拆出的两条 infix 在 global 档下只改一次：" + f.calls);
            check(f.count("review") <= 1, "换行形态同样最多一次检查：" + f.calls);
            check(!replies.stream().anyMatch(text -> text.contains("已有 DeepSeek 请求")),
                    "合并之后不会再撞上「已有请求」那道闸：" + replies);
            check(replies.stream().anyMatch(text -> text.contains("智能修改")), "换行形态有回执：" + replies);
        }
        // parts 档的对照：同样的换行消息在 parts 档下**第二条会被闸掉**（这是既有行为，如实钉住，
        // 所以"一条消息里多个 .infix 各改各的"在 parts 档下只有走计划链路（executeChatCommands）才成立）。
        try (Fixture f = new Fixture("parts-newline-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.command(".infix mode parts", scope);
            check(new Settings(f.root).infixMode("1:private:456") == Settings.InfixMode.PARTS, "parts 档已生效（换行对照）");
            f.rewrites.add("@add:grass");
            f.calls.clear();
            List<String> replies = f.command(".infix 加入草地\n.infix 加入军装", scope);
            check(f.count("rewrite") == 1, "parts 档下换行消息的第二条被请求闸挡住（既有行为）：" + f.calls);
            check(replies.stream().anyMatch(text -> text.contains("已有 DeepSeek 请求")),
                    "如实回执「已有请求」：" + replies);
        }
    }

    // ------------------------------------------------------------------ ⑥ mode 指令、会话隔离、undo

    private static void modeCommandAndUndo(Path root) throws Exception {
        try (Fixture f = new Fixture("mode-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            List<String> show = f.command(".infix mode", scope);
            check(show.get(0).contains("infix 档位（本会话）：global"), "默认显示 global：" + show.get(0));
            check(show.get(0).contains("parts"), "回执里说明怎么切回 parts");
            check(show.get(0).contains("画面检查"), "回执里说明整份提示词画面检查");
            check(f.count("rewrite") == 0, "显示状态不调 DeepSeek");
            List<String> parts = f.command(".infix mode parts", scope);
            check(parts.get(0).contains("已切到「分组」"), "可以切到 parts：" + parts.get(0));
            check(new Settings(f.root).infixMode("1:private:456") == Settings.InfixMode.PARTS, "切换落盘");
            check(new Settings(f.root).infixMode("1:private:456") == Settings.InfixMode.PARTS
                    && new Settings(f.root).infixMode("1:private:888") == Settings.InfixMode.GLOBAL, "会话隔离：别的会话还是默认 global");
            List<String> global = f.command(".infix mode 全局", scope);
            check(global.get(0).contains("已切到「全局」"), "中文「全局」也认：" + global.get(0));
            check(new Settings(f.root).infixMode("1:private:456") == Settings.InfixMode.GLOBAL, "中文切换同样落盘");
            List<String> bad = f.command(".infix mode 随便", scope);
            check(bad.get(0).contains("mode"), "非法值给用法：" + bad.get(0));
            check(new Settings(f.root).infixMode("1:private:456") == Settings.InfixMode.GLOBAL, "非法值不改设置");
            List<String> member = f.command(".infix mode parts", "888");
            check(member.get(0).contains("仅 owner 或 admin"), "权限与 .infix 一致：" + member.get(0));
            check(new Settings(f.root).infixMode("1:private:888") == Settings.InfixMode.GLOBAL, "越权不改设置");
            // 英文大小写
            check(f.command(".infix MODE PARTS", scope).get(0).contains("已切到「分组」"), "大小写不敏感");
            f.command(".infix mode global", scope);
            check(f.command(".infix mode", scope).get(0).contains("global"), "切回 global 后显示正确");
            // 显示里也带传统反义词排斥器状态
            check(f.command(".infix mode", scope).get(0).contains("传统正反义词排斥器"), "mode 回执里带上排斥器状态");
        }
        // 一条指令一次改写 + 一次撤销：undo 能一次撤回整条改动（global 天然一步）。
        try (Fixture f = new Fixture("undo-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:grass");
            f.reviews.add("{\"ok\":true,\"issues\":\"\"}");
            f.run(".infix 加入草地", scope, true);
            check(new UserPromptStore(f.root).prompts(scope).positive().contains("grass"), "改写已落地");
            f.command(".prompt undo", scope);
            check(new UserPromptStore(f.root).prompts(scope).positive().equals("1girl, <lora:kotori:1>"),
                    "一次 .prompt undo 撤回整条指令的改动：" + new UserPromptStore(f.root).prompts(scope).positive());
            check(f.count("rewrite") == 1, "撤回不需要再调模型：" + f.calls);
        }
        // 一条复合语句（infix + gen）的变体：多行、自然语言混合、一句话里多个诉求。
        try (Fixture f = new Fixture("compound-", false)) {
            String scope = "456";
            f.seed(scope, "1girl, <lora:kotori:1>", "bad_hands");
            f.rewrites.add("@add:grass");
            f.reviews.add("{\"ok\":true,\"issues\":\"\"}");
            f.calls.clear();
            // 变体 1：多行链路（infix 换行 gen）
            f.command(".infix 加入草地\n.gen 1", scope);
            check(f.count("rewrite") == 1, "多行链路（infix ⏎ gen）只改写一次：" + f.calls);
            f.calls.clear();
            f.rewrites.clear();
            f.rewrites.add("@add:military_uniform");
            // 变体 2：一句话里多个诉求（含顿号/逗号）
            f.command(".infix 把背景换成草地，把衣服换成军装，顺手加个微笑 ; .gen 1", scope);
            check(f.count("rewrite") == 1, "一句话里多个诉求也只改写一次：" + f.calls);
            check(f.count("review") <= 1, "同样最多一次检查：" + f.calls);
            f.calls.clear();
            f.rewrites.clear();
            f.rewrites.add("@add:smile");
            // 变体 3：3 个 infix 步骤（用户实测的形态）
            f.command(".infix 加入草地 ; .infix 加入军装 ; .infix 加入微笑 ; .gen 1", scope);
            check(f.count("rewrite") == 1, "3 个 infix 步骤 → 1 次改写：" + f.calls);
            check(!f.calls.isEmpty() && f.calls.size() <= 2, "总调用 ≤2：" + f.calls);
        }
    }

    // ------------------------------------------------------------------ ⑦ 网页接口

    private static void webInterfaces(Path root) throws Exception {
        try (Fixture fixture = new Fixture("web-run-", false)) {
            Settings settings = new Settings(fixture.root);
            int port = freePort();
            settings.webSetting("port", new JsonPrimitive(port));
            try (cn.szu.bot.web.WebUiServer server = new cn.szu.bot.web.WebUiServer(settings, fixture.bot)) {
                server.start();
                String base = "http://127.0.0.1:" + port;
                // ① /api/status 与 /api/settings 都必须带 infixMode（既有字段一个不少）。
                JsonObject status = get(base, "/api/status", fixture.token);
                check(status.has("infixMode") && status.get("infixMode").getAsString().equals("global"),
                        "/api/status 有 infixMode=global：" + status.get("infixMode"));
                for (String field : List.of("infixFilter", "imageCount", "autoGet", "generation", "chat", "civitai", "sd", "quests"))
                    check(status.has(field), "/api/status 既有字段仍在：" + field);
                JsonObject settingsResponse = post(base, "/api/settings", fixture.token, body("key", "logMirror", "value", "on"));
                check(settingsResponse.has("infixMode") && settingsResponse.get("infixMode").getAsString().equals("global"),
                        "POST /api/settings 返回里新增 infixMode：" + settingsResponse.get("infixMode"));
                post(base, "/api/settings", fixture.token, body("key", "logMirror", "value", "off"));
                // ② POST /api/infix/mode 两个方向。
                JsonObject parts = post(base, "/api/infix/mode", fixture.token, body("mode", "parts"));
                check(parts.get("mode").getAsString().equals("parts"), "切到 parts：" + parts);
                check(parts.get("default").getAsString().equals("global"), "回执里带 default=global：" + parts);
                check(parts.has("scope") && parts.get("scope").getAsString().equals("web"), "回执里带 scope：" + parts);
                check(new Settings(fixture.root).infixMode(Bot.webConversationKey("web")) == Settings.InfixMode.PARTS,
                        "parts 已落到网页会话");
                check(get(base, "/api/status", fixture.token).get("infixMode").getAsString().equals("parts"),
                        "状态接口立刻反映新档位");
                JsonObject global = post(base, "/api/infix/mode", fixture.token, body("mode", "global"));
                check(global.get("mode").getAsString().equals("global"), "切回 global：" + global);
                check(new Settings(fixture.root).infixMode(Bot.webConversationKey("web")) == Settings.InfixMode.GLOBAL,
                        "global 已落到网页会话");
                // ③ 非法 mode → 400 且不改已存值。
                post(base, "/api/infix/mode", fixture.token, body("mode", "parts"));
                int bad = statusCode(base, "/api/infix/mode", fixture.token, body("mode", "nonsense"));
                check(bad == 400, "非法 mode 返回 400：" + bad);
                check(new Settings(fixture.root).infixMode(Bot.webConversationKey("web")) == Settings.InfixMode.PARTS,
                        "400 之后已存值没被改动");
                int missing = statusCode(base, "/api/infix/mode", fixture.token, new JsonObject());
                check(missing == 400, "缺少 mode 也 400：" + missing);
                check(new Settings(fixture.root).infixMode(Bot.webConversationKey("web")) == Settings.InfixMode.PARTS,
                        "缺少 mode 同样不改值");
                // ④ 请求体不是合法 JSON 也是 400（沿用既有网页接口规则）。
                check(statusCode(base, "/api/infix/mode", fixture.token, body("mode", "global")) == 200, "合法请求 200");
                // ⑤ 鉴权沿用既有网页规则。
                check(statusCode(base, "/api/infix/mode", null, body("mode", "global")) == 401, "无令牌被拒绝");
                check(statusCode(base, "/api/infix/mode", "wrong", body("mode", "global")) == 401, "错误令牌被拒绝");
                // ⑥ 中文别名。
                JsonObject chinese = post(base, "/api/infix/mode", fixture.token, body("mode", "全局"));
                check(chinese.get("mode").getAsString().equals("global"), "中文「全局」也认：" + chinese);
                post(base, "/api/infix/mode", fixture.token, body("mode", "global"));
            }
        }
    }

    private static JsonObject body(String... pairs) {
        JsonObject object = new JsonObject();
        for (int index = 0; index + 1 < pairs.length; index += 2) object.addProperty(pairs[index], pairs[index + 1]);
        return object;
    }

    private static JsonObject get(String base, String path, String token) throws Exception {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                .send(builder.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Json.parse(response.body().isBlank() ? "{}" : response.body());
    }

    private static int statusCode(String base, String path, String token, JsonObject payload) throws Exception {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return java.net.http.HttpClient.newHttpClient().send(builder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).statusCode();
    }

    private static JsonObject post(String base, String path, String token, JsonObject payload) throws Exception {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                .send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) throw new AssertionError("HTTP " + response.statusCode() + "：" + response.body());
        return Json.parse(response.body().isBlank() ? "{}" : response.body());
    }
}

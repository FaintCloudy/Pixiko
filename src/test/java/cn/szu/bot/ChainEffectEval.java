package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;

/**
 * Effect evaluation of the natural-language task chain: the plan is really executed — styles are read
 * from the running WebUI, prompts are really rewritten for a per-task user, and the generation requests
 * that the queue finally sends are captured (txt2img itself is stubbed, no GPU time is spent).
 *
 * A task counts as a complete hit only when the executed effect is right: the basis really reached the
 * prompt, every requested change really reached the prompt that generation used, the generation count
 * matches, nothing redundant ran and the chain ran in order (a late basis load would wipe the edits).
 *
 * Run with eval-effect.ps1; writes work/chain-effect-report.md and work/chain-effect-failures.log.
 */
public final class ChainEffectEval {
    private static final double MIN_HIT_RATE = Double.parseDouble(System.getProperty("hitrate.min", "0.97"));
    private static final int TASK_COUNT = Integer.parseInt(System.getProperty("tasks.count", "500"));
    private static final long SEED = Long.parseLong(System.getProperty("tasks.seed", "20260919"));
    private static final int WORKERS = Integer.parseInt(System.getProperty("workers", "6"));
    private static final boolean LLM_JUDGE = Boolean.parseBoolean(System.getProperty("llm.judge", "true"));
    private static final double STYLE_TAG_FLOOR = Double.parseDouble(System.getProperty("style.tag.floor", "0.25"));
    private static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    /** A real user always has prompts already; "别用样式" means "edit what I have", not "start from nothing". */
    private static final String BASE_PROMPT = "1girl, solo, standing, looking at viewer, street, outdoors, day, sky";
    private static final String BASE_NEGATIVE = "lowres, bad anatomy, watermark";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final String TINY_PNG = placeholderPng();

    /** A real, decodable PNG: the queue decodes the response image before it will publish anything. */
    private static String placeholderPng() {
        try {
            java.awt.image.BufferedImage image =
                    new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception error) {
            return "";
        }
    }

    record Outcome(ChainTaskSet.ScaleTask task, List<String> commands, List<String> receipts, String prompt,
                   String basisPrompt, int generations, int planned, List<String> problems, List<String> calls,
                   long millis, boolean drift) {
        boolean hit() { return problems.isEmpty() && !drift; }
    }

    private static final List<Outcome> OUTCOMES = new ArrayList<>();
    private static final Map<String, String> STYLE_PROMPTS = new ConcurrentHashMap<>();
    private static final Map<String, String> FUNCTION_PROMPTS = new ConcurrentHashMap<>();
    private static volatile Set<String> DICTIONARY = Set.of();
    private static volatile String fingerprint;
    private static String model = "deepseek-flash";
    private static long startedAt;

    public static void main(String[] args) throws Exception {
        Path installation = Path.of(System.getProperty("bot.home", ".")).toAbsolutePath().normalize();
        Path work = Path.of(System.getProperty("bot.test.work", "work"));
        Files.createDirectories(work);
        // The evaluation really writes prompts and function state, so it runs against a sandbox copy of the
        // installation: the user's own prompt files and .function activations are never touched.
        Path root = sandbox(installation, work);
        JsonObject config = Json.parse(Files.readString(installation.resolve("config.json"), StandardCharsets.UTF_8));
        JsonObject progen = ChainTaskSet.obj(config, "progen");
        JsonObject sdConfig = ChainTaskSet.obj(config, "sd");
        model = Json.str(progen, "model", "deepseek-flash");
        String personality = Json.str(ChainTaskSet.obj(config, "chat"), "personality", "");
        if (personality.isBlank()) throw new IllegalStateException("config.json 缺少 chat.personality。");
        startedAt = System.currentTimeMillis();
        System.out.println("沙盒根目录：" + root);

        ChainTaskSet.preflight(root, config);
        loadStylePrompts(sdConfig);
        loadFunctionPrompts(installation);
        loadDictionary(root);
        List<ChainTaskSet.ScaleTask> tasks = ChainTaskSet.generate(TASK_COUNT, SEED);
        System.out.println("效果评测：模型 " + model + "，任务 " + tasks.size() + " 条（种子 " + SEED + "，并发 " + WORKERS
                + "，语义判定 " + (LLM_JUDGE ? "开" : "关") + "），完全命中门槛 " + ChainTaskSet.percent(MIN_HIT_RATE));
        for (ChainTaskSet.Numbered list : ChainTaskSet.LISTS.values())
            System.out.println("编号表 " + list.kind() + "：" + list.names().size() + " 项（来源 " + list.source() + "）");

        try { Files.deleteIfExists(work.resolve("chain-effect-progress.log")); } catch (Exception ignored) { }
        run(root, sdConfig, progen, personality, tasks);
        int hit = (int) OUTCOMES.stream().filter(Outcome::hit).count();
        long drift = OUTCOMES.stream().filter(Outcome::drift).count();
        int measurable = OUTCOMES.size() - (int) drift;
        double rate = measurable == 0 ? 0 : (double) hit / measurable;
        Files.writeString(work.resolve("chain-effect-report.md"), report(hit, rate, tasks), StandardCharsets.UTF_8);
        Files.writeString(work.resolve("chain-effect-failures.log"), failureLog(), StandardCharsets.UTF_8);
        Files.writeString(work.resolve("chain-effect-calls.log"), callLog(), StandardCharsets.UTF_8);
        System.out.println("效果完全命中：" + hit + "/" + measurable + "（" + ChainTaskSet.percent(rate) + "，另有 "
                + drift + " 条环境漂移已剔除），报告 " + work.resolve("chain-effect-report.md"));
        if (rate + 1e-9 < MIN_HIT_RATE) {
            System.err.println("效果完全命中率低于门槛 " + ChainTaskSet.percent(MIN_HIT_RATE));
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ fixtures from the real WebUI

    private static void loadStylePrompts(JsonObject sdConfig) {
        try {
            SdClient sd = new SdClient(Path.of(System.getProperty("bot.home", ".")).toAbsolutePath().normalize(), sdConfig);
            for (SdClient.StylePrompt style : sd.stylePrompts()) STYLE_PROMPTS.put(style.name(), style.positive());
            Log.info("效果评测：已读取 " + STYLE_PROMPTS.size() + " 个样式原文用于校验基底");
        } catch (Exception error) {
            Log.warn("效果评测：读取样式原文失败，样式校验将跳过：" + Bot.error(error));
        }
    }

    private static void loadFunctionPrompts(Path root) {
        try {
            Path file = root.resolve("data/sd-functions.json");
            if (!Files.exists(file)) return;
            JsonObject stored = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            JsonObject definitions = stored.has("definitions") && stored.get("definitions").isJsonObject()
                    ? stored.getAsJsonObject("definitions") : stored;
            for (Map.Entry<String, JsonElement> entry : definitions.entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonObject()) {
                    JsonObject item = value.getAsJsonObject();
                    String positive = Json.str(item, "positive", Json.str(item, "prompt", ""));
                    if (!positive.isBlank()) FUNCTION_PROMPTS.put(entry.getKey(), positive);
                } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    FUNCTION_PROMPTS.put(entry.getKey(), value.getAsString());
                }
            }
            Log.info("效果评测：已读取 " + FUNCTION_PROMPTS.size() + " 个提示词集原文用于校验基底");
        } catch (Exception error) {
            Log.warn("效果评测：读取提示词集原文失败，相关校验将跳过：" + Bot.error(error));
        }
    }

    /** The same standard dictionary .infix must respect, loaded through the bot's own reader. */
    private static void loadDictionary(Path root) {
        try {
            List<String> terms = new UserPromptStore(root).vocabulary(List.of());
            Set<String> set = new HashSet<>();
            for (String term : terms) {
                String canonical = ChainTaskSet.canonical(term);
                if (canonical.isBlank()) continue;
                set.add(canonical);
                set.add(canonical.replace('_', ' '));
            }
            DICTIONARY = Set.copyOf(set);
            Log.info("效果评测：已载入 SD 标准词库 " + terms.size() + " 条");
        } catch (Exception error) {
            Log.warn("效果评测：载入 SD 标准词库失败，词库校验将跳过：" + Bot.error(error));
        }
    }

    // ------------------------------------------------------------------ execution
    private static void run(Path root, JsonObject sdConfig, JsonObject progen, String personality,
                            List<ChainTaskSet.ScaleTask> tasks) throws Exception {
        String upstream = Json.str(sdConfig, "base_url", "http://127.0.0.1:7860");
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        List<Future<Outcome>> futures = new ArrayList<>();
        AtomicInteger finished = new AtomicInteger();
        for (ChainTaskSet.ScaleTask task : tasks) futures.add(pool.submit(() -> {
            Outcome outcome = evaluate(root, sdConfig, upstream, progen, personality, task);
            int done = finished.incrementAndGet();
            // Live trace: what was planned and whether the executed effect matched. Also appended to
            // work/chain-effect-progress.log, so a running evaluation can be watched line by line.
            String line = "[任务 " + done + "/" + tasks.size() + "] #" + outcome.task().id() + " "
                    + (outcome.hit() ? "PASS" : "FAIL") + " 计划=" + outcome.commands()
                    + " 生成=" + outcome.generations() + "/" + outcome.planned()
                    + (outcome.hit() ? "" : " 问题=" + String.join("；", outcome.problems()));
            System.out.println(line);
            appendProgress(line);
            return outcome;
        }));
        try {
            for (Future<Outcome> future : futures) OUTCOMES.add(future.get());
        } finally {
            pool.shutdownNow();
        }
        for (Outcome outcome : OUTCOMES) {
            if (outcome.hit()) continue;
            if (outcome.task().id() <= 40 || OUTCOMES.size() <= 60)
                System.err.println("FAIL #" + outcome.task().id() + " " + outcome.task().message() + " → " + outcome.commands()
                        + "\nprompt=" + Log.text(outcome.prompt()) + "\n  ⟵ " + String.join("；", outcome.problems()));
        }
    }

    private static Outcome evaluate(Path root, JsonObject sdConfig, String upstream, JsonObject progen,
                                    String personality, ChainTaskSet.ScaleTask task) {
        List<String> calls = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> commands = List.of(), receipts = new ArrayList<>();
        String prompt = "", basisPrompt = "", basisName = task.spec().basisToken();
        int generations = 0, planned = 0;
        long started = System.nanoTime();
        String userId = String.valueOf(900000L + task.id());
        try (WebUiProxy proxy = new WebUiProxy(upstream)) {
            JsonObject proxied = sdConfig.deepCopy();
            proxied.addProperty("base_url", proxy.url());
            DeepSeekPrompts.Transport transport = (body, key, timeout) -> {
                String seen = ChainEvalSupport.fingerprint(body);
                if (seen != null && fingerprint == null) fingerprint = seen;
                HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT)).timeout(timeout)
                        .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                calls.add("part=" + ChainEvalSupport.requestOf(body) + "  ⟶  "
                        + ChainEvalSupport.responseOf(response.statusCode(), response.body()));
                return new DeepSeekPrompts.Response(response.statusCode(), response.body());
            };
            DeepSeekPrompts client = new DeepSeekPrompts(root, progen, transport, query -> "（效果评测未执行联网检索）");
            JsonObject speaker = new JsonObject();
            speaker.addProperty("id", userId);
            speaker.addProperty("role", "user");
            speaker.addProperty("display_name", "小明");

            // Really execute the chain for this task's own prompt scope.
            SdClient sd = new SdClient(root, proxied);
            BlockingQueue<String> replies = new LinkedBlockingQueue<>();
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", "private");
            event.addProperty("self_id", 1);
            event.addProperty("user_id", userId);
            event.addProperty("message_id", task.id());
            event.addProperty("message", task.message());
            try (Bot bot = new Bot(new Settings(root), sd, (sent, segments) -> {
                replies.add(Bot.messageText(segments));
                return CompletableFuture.completedFuture(null);
            })) {
                new UserPromptStore(root).replace(userId,
                        new SdClient.Prompts(BASE_PROMPT, BASE_NEGATIVE, UserPromptStore.PERSONAL_SOURCE));
                // The user first lists what they want to pick from; the request then refers to that list.
                JsonArray history = new JsonArray();
                JsonObject selections = new JsonObject();
                if (!task.spec().kind().isEmpty()) {
                    ChainTaskSet.Numbered listed = listInConversation(bot, replies, event, task.spec().kind());
                    if (!listed.names().isEmpty()) {
                        selections.add(task.spec().kind(), Json.GSON.toJsonTree(listed.names()));
                        history = historyOf(listed);
                        basisName = resolveBasisName(task, listed.names());
                    }
                }
                if (selections.isEmpty()) { selections = ChainTaskSet.selections(); history = ChainTaskSet.historyFor(task); }

                ChatActions.Plan plan = client.chatPlan(personality, history, task.message(), selections, speaker);
                commands = plan.commands();
                problems.addAll(ChainEvalSupport.judgePlan(task, commands));
                planned = plannedGenerations(commands);

                bot.executeChatCommands(event, commands, selections);
                waitForWorkflow(bot);
                waitForGenerations(proxy, planned);
            }
            while (true) {
                String receipt = replies.poll();
                if (receipt == null) break;
                receipts.add(receipt);
            }
            for (String receipt : receipts) if (receiptFailure(receipt)) problems.add("执行回执报错：" + Log.text(receipt));

            UserPromptStore store = new UserPromptStore(root);
            SdClient.Prompts finalPrompts = store.prompts(userId);
            prompt = finalPrompts.positive();
            // Without a style the untouched baseline is what "not newly introduced" means.
            basisPrompt = task.spec().kind().isEmpty() ? BASE_PROMPT : basisPrompt(basisName);
            generations = proxy.generations.get();
            if (planned > 0 && generations != planned) problems.add("实际生成 " + generations + " 次（应为 " + planned + "）");
            if (planned == 0 && generations > 0) problems.add("要求不要生成，却实际生成 " + generations + " 次");
            problems.addAll(judgeEffect(task, prompt, basisName, basisPrompt));
            if (LLM_JUDGE) problems.addAll(judgeSemantics(root, progen, task, prompt));
            // Diagnostics: a missing change is either a rewrite miss or a backfill that never ran/failed.
            if (problems.stream().anyMatch(problem -> problem.contains("提示词未体现"))) {
                boolean backfilled = receipts.stream().anyMatch(receipt -> receipt.contains("已补充你要求的标准词条"));
                problems.add("补齐回执：" + (backfilled ? "有" : "无") + "；最终 prompt=" + shorten(prompt, 400));
            }
        } catch (Exception error) {
            problems.add("执行异常：" + Bot.error(error));
        }
        long millis = Math.round((System.nanoTime() - started) / 1_000_000.0);
        // The style/LoRA this task refers to may be renamed or deleted in the live WebUI while the run is
        // going on: such a task is unmeasurable and is excluded from the denominator instead of counted.
        boolean drift = receipts.stream().anyMatch(receipt -> receipt.contains("未知预设样式")
                || receipt.contains("未知 LoRA") || receipt.contains("提示词集不存在"));
        if (drift) problems.add("环境漂移：评测期间该样式/LoRA/提示词集被改动或删除");
        return new Outcome(task, commands, List.copyOf(receipts), prompt, basisPrompt, generations, planned,
                problems, List.copyOf(calls), millis, drift);
    }

    /** Runs the real list command in this task's own conversation and parses its numbered reply. */
    private static ChainTaskSet.Numbered listInConversation(Bot bot, BlockingQueue<String> replies, JsonObject event,
                                                            String kind) throws Exception {
        JsonObject asked = event.deepCopy();
        asked.addProperty("message", "." + kind + " list");
        replies.clear();
        bot.accept(asked);
        StringBuilder collected = new StringBuilder();
        String first = replies.poll(60, TimeUnit.SECONDS);
        if (first != null) {
            collected.append(first).append('\n');
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline) {
                String next = replies.poll(3, TimeUnit.SECONDS);
                if (next == null) break;
                collected.append(next).append('\n');
            }
        }
        List<String> names = new ArrayList<>();
        java.util.regex.Matcher numbered = java.util.regex.Pattern.compile("(?m)^#([0-9]+)\\s+(.+?)\\s*$")
                .matcher(collected.toString());
        while (numbered.find()) {
            // .lora list shows "name（别名：alias）" but stores the bare name; the snapshot must match.
            names.add(numbered.group(2).replaceFirst("（别名：[^）]*）\\s*$", "").strip());
        }
        replies.clear();
        return new ChainTaskSet.Numbered(kind, List.copyOf(names), collected.toString().strip(), "任务会话内真实命令");
    }

    private static JsonArray historyOf(ChainTaskSet.Numbered listed) {
        JsonArray history = new JsonArray();
        JsonObject asked = new JsonObject();
        asked.addProperty("role", "user");
        asked.addProperty("content", "." + listed.kind() + " list");
        JsonObject answered = new JsonObject();
        answered.addProperty("role", "assistant");
        answered.addProperty("content", listed.reply());
        history.add(asked);
        history.add(answered);
        return history;
    }

    /** A chain with asynchronous steps is submitted, not awaited: wait for it before reading the effect. */
    private static void waitForWorkflow(Bot bot) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (bot.activeChatWorkflows() > 0 && System.nanoTime() < deadline) Thread.sleep(100);
    }

    /** A receipt reports a real failure; a generation summary that says "生成失败 0 次" does not. */
    private static boolean receiptFailure(String receipt) {
        if (receipt == null) return false;
        if (receipt.contains("操作失败") || receipt.contains("格式不正确") || receipt.contains("多步骤指令已停止")
                || receipt.contains("未成功") || receipt.contains("没有可执行") || receipt.contains("请重新查询")) return true;
        java.util.regex.Matcher failed = java.util.regex.Pattern.compile("生成失败\\s*([0-9]+)\\s*次").matcher(receipt);
        return failed.find() && !"0".equals(failed.group(1));
    }

    /** "#7" refers to the list the user just asked for; the real name is what the styles/loras carry. */
    private static String resolveBasisName(ChainTaskSet.ScaleTask task, List<String> names) {
        String token = task.spec().basisToken();
        if (!token.startsWith("#")) return token;
        int index;
        try { index = Integer.parseInt(token.substring(1)); } catch (NumberFormatException error) { return token; }
        return index >= 1 && index <= names.size() ? names.get(index - 1) : token;
    }

    /** Appends one line to work/chain-effect-progress.log so the run can be followed while it happens. */
    private static void appendProgress(String line) {
        try {
            Path file = Path.of(System.getProperty("bot.test.work", "work")).resolve("chain-effect-progress.log");
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    private static void waitForGenerations(WebUiProxy proxy, int planned) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (planned > 0 && proxy.generations.get() < planned && System.nanoTime() < deadline) Thread.sleep(100);
        Thread.sleep(planned > 0 ? 300 : 600);   // let the queue settle before the prompt is read
    }

    private static int plannedGenerations(List<String> commands) {
        for (String command : commands) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?is)^[./]gen(?:\\s+(\\S+))?$").matcher(command.strip());
            if (!matcher.matches()) continue;
            String value = matcher.group(1);
            if (value == null) return 1;
            try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 1; }
        }
        return 0;
    }

    /** The prompt the chosen basis should have contributed: the real style/LoRA/function text. */
    private static String basisPrompt(String name) {
        if (name == null || name.isBlank() || name.startsWith("#")) return "";
        if (STYLE_PROMPTS.containsKey(name)) return STYLE_PROMPTS.get(name);
        if (FUNCTION_PROMPTS.containsKey(name)) return FUNCTION_PROMPTS.get(name);
        return "";
    }

    // ------------------------------------------------------------------ effect judging

    /** The prompt generation actually used must contain the basis and every requested change. */
    private static List<String> judgeEffect(ChainTaskSet.ScaleTask task, String prompt, String basisName, String basisPrompt) {
        List<String> problems = new ArrayList<>();
        ChainTaskSet.Spec spec = task.spec();
        if (prompt == null || prompt.isBlank()) {
            if (spec.generate() || !spec.edits().isEmpty()) problems.add("执行后没有可用的提示词");
            return problems;
        }
        if ("style".equals(spec.kind()) && !basisPrompt.isBlank()) {
            // The style really applied when its identity survives: the leading tags (character/base) and its
            // LoRA tag must be there; the rest may legitimately change because the request asked for it.
            List<String> tags = ChainTaskSet.tagsOf(basisPrompt);
            if (!tags.isEmpty()) {
                long present = tags.stream().filter(tag -> prompt.contains(tag)).count();
                double share = (double) present / tags.size();
                List<String> identity = tags.subList(0, Math.min(3, tags.size()));
                String lora = tags.stream().filter(tag -> tag.contains("<lora:")).findFirst().orElse("");
                boolean identityKept = identity.stream().anyMatch(prompt::contains)
                        && (lora.isEmpty() || prompt.contains(lora));
                if (!identityKept || present < 2 || share < STYLE_TAG_FLOOR)
                    problems.add("样式基底没有生效（样式词条命中 " + present + "/" + tags.size()
                            + (identityKept ? "，身份词条保留" : "，身份词条丢失") + "）");
            }
        }
        if ("lora".equals(spec.kind())) {
            // The WebUI applies its own canonical tag (<lora:kuro_4c_nai:1>), which need not match the Civitai
            // file identity, so any LoRA tag in a prompt that had none means the load really took effect.
            boolean applied = prompt.matches("(?is).*<lora:[^>]+>.*")
                    || (!basisName.startsWith("#") && prompt.contains(basisName));
            if (!applied) problems.add("LoRA 基底没有生效：" + basisName);
        }
        if ("function".equals(spec.kind()) && !basisPrompt.isBlank()) {
            List<String> tags = ChainTaskSet.tagsOf(basisPrompt);
            long present = tags.stream().filter(tag -> prompt.contains(tag)).count();
            if (!tags.isEmpty() && present * 2 < tags.size()) problems.add("提示词集基底没有生效（命中 " + present + "/" + tags.size() + "）");
        }
        problems.addAll(judgeDictionary(prompt, basisPrompt));
        return problems;
    }

    /**
     * The prompt that generation used must be valid SD prompt text: everything the rewrite newly
     * introduced has to be a standard dictionary term, no Chinese may leak into the tag text, and the
     * weight/grouping brackets must stay balanced.
     */
    private static List<String> judgeDictionary(String prompt, String basisPrompt) {
        List<String> problems = new ArrayList<>();
        if (prompt.isBlank()) return problems;
        if (DICTIONARY.isEmpty()) return problems;
        List<String> baselines = ChainTaskSet.tagsOf(basisPrompt).stream().map(ChainTaskSet::canonical).toList();
        List<String> unknown = new ArrayList<>();
        for (String tag : ChainTaskSet.tagsOf(prompt)) {
            String canonical = ChainTaskSet.canonical(tag);
            if (canonical.isBlank() || baselines.contains(canonical)) continue;
            if (canonical.startsWith("<") || canonical.contains("lora:") || canonical.equals("break")
                    || canonical.equals("and") || canonical.equals("|")) continue;
            if (DICTIONARY.contains(canonical)) continue;
            if (DICTIONARY.contains(canonical.replace('_', ' '))) continue;
            if (DICTIONARY.contains(canonical.replace(' ', '_'))) continue;
            unknown.add(tag.strip());
        }
        if (!unknown.isEmpty())
            problems.add("提示词新引入了 " + unknown.size() + " 个非标准词库词条：" + String.join("、", unknown.stream().limit(3).toList()));
        List<String> baselineTags = ChainTaskSet.tagsOf(basisPrompt);
        // Chinese inside a LoRA tag (<lora:ami ichigo(天衣 いちご):1>) is the model's real name, not a leak.
        String withoutTags = prompt.replaceAll("(?is)<[^>]*>", " ");
        if (baselineTags.stream().noneMatch(tag -> tag.matches("(?s).*[\\u4e00-\\u9fff].*"))
                && withoutTags.matches("(?s).*[\\u4e00-\\u9fff].*"))
            problems.add("提示词里混入了中文，不是标准 SD 词条");
        long opens = prompt.chars().filter(character -> character == '(').count();
        long closes = prompt.chars().filter(character -> character == ')').count();
        if (opens != closes) problems.add("提示词括号不配平（" + opens + " 个左括号 / " + closes + " 个右括号）");
        return problems;
    }

    /** Asks the model whether the executed prompt really expresses every requested change. */
    private static List<String> judgeSemantics(Path root, JsonObject progen, ChainTaskSet.ScaleTask task, String prompt) {
        List<String> problems = new ArrayList<>();
        List<ChainTaskSet.Edit> edits = task.spec().edits();
        if (edits.isEmpty() || prompt.isBlank()) return problems;
        // Deterministic first: the canonical tag for the requested change either reached the prompt or not.
        // The model verdict only has to resolve the remaining, rephrased cases.
        List<String> pending = new ArrayList<>();
        for (ChainTaskSet.Edit edit : edits) {
            if (canonicalLanded(edit, prompt)) continue;
            pending.add(edit.category() + edit.value());
        }
        if (pending.isEmpty()) return problems;
        if (!LLM_JUDGE) {
            for (String item : pending) problems.add("提示词未体现「" + item + "」");
            return problems;
        }
        try {
            DeepSeekPrompts client = new DeepSeekPrompts(root, progen);
            JsonArray requirements = new JsonArray();
            for (String item : pending) requirements.add(item);
            JsonObject input = new JsonObject();
            input.add("requirements", requirements);
            input.addProperty("positive_prompt", prompt.length() > 4000 ? prompt.substring(0, 4000) : prompt);
            String reply = client.chat("", new JsonArray(), input.toString(),
                    "你在检查一张图的正向提示词是否真的实现了用户提出的每一项画面修改。"
                            + "只输出 JSON：{\"covered\":[true,false,…]}，顺序与 requirements 完全一致，数量必须相同；"
                            + "只有提示词里能明确看出该项修改才算 true（英文近义表达也算，例如 rainy street 覆盖雨天、"
                            + "kimono 覆盖和服、looking back 覆盖回头、dusk 覆盖黄昏）。");
            List<Boolean> covered = parseCovered(reply, pending.size());
            for (int i = 0; i < covered.size(); i++)
                if (!covered.get(i)) problems.add("提示词未体现「" + pending.get(i) + "」");
        } catch (Exception error) {
            // A failed judgement must not silently count as a hit.
            for (String item : pending) problems.add("提示词未体现「" + item + "」（语义判定失败：" + Bot.error(error) + "）");
        }
        return problems;
    }

    /** True when any dictionary-valid synonym for the requested change is present in the executed prompt. */
    private static boolean canonicalLanded(ChainTaskSet.Edit edit, String prompt) {
        if (DICTIONARY.isEmpty()) return false;
        String normalized = ChainTaskSet.canonical(prompt);
        // All synonyms count: a prompt saying "sky" already covers 晴天 even without "clear_sky".
        for (String candidate : Bot.sceneTagCandidates(edit.category() + edit.value(), DICTIONARY))
            if (java.util.regex.Pattern.compile("(^|[_,\\s])" + java.util.regex.Pattern.quote(candidate) + "($|[_,\\s])")
                    .matcher(normalized).find()) return true;
        return false;
    }

    private static List<Boolean> parseCovered(String reply, int expected) {
        List<Boolean> covered = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)\\b(true|false)\\b").matcher(reply == null ? "" : reply);
        while (matcher.find()) covered.add("true".equalsIgnoreCase(matcher.group(1)));
        while (covered.size() < expected) covered.add(false);
        return covered.subList(0, expected);
    }

    // ------------------------------------------------------------------ webui proxy

    /** Forwards everything to the running WebUI, but answers txt2img itself so no GPU time is spent. */
    static final class WebUiProxy implements AutoCloseable {
        private final HttpServer server;
        private final String upstream;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        final AtomicInteger generations = new AtomicInteger();

        WebUiProxy(String upstream) throws Exception {
            this.upstream = upstream.endsWith("/") ? upstream.substring(0, upstream.length() - 1) : upstream;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void handle(HttpExchange exchange) {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/sdapi/v1/txt2img")) {
                    generations.incrementAndGet();
                    byte[] out = ("{\"images\":[\"" + TINY_PNG + "\"],\"info\":\"{}\",\"parameters\":{}}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, out.length);
                    exchange.getResponseBody().write(out);
                    return;
                }
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(upstream + exchange.getRequestURI()))
                        .timeout(Duration.ofSeconds(180));
                // The bridge extension authenticates on X-Pixiko-Bridge, so headers must survive the hop.
                for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
                    String name = header.getKey();
                    if (name == null || header.getValue().isEmpty()) continue;
                    if (name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Content-Length")
                            || name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("Transfer-Encoding")
                            || name.equalsIgnoreCase("Expect") || name.equalsIgnoreCase("Upgrade"))
                        continue;
                    try { builder.header(name, header.getValue().get(0)); } catch (IllegalArgumentException ignored) { }
                }
                if (!exchange.getRequestHeaders().containsKey("Content-Type")) builder.header("Content-Type", "application/json");
                if (body.length > 0) builder.method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
                else builder.method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.noBody());
                HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.statusCode(), response.body().length == 0 ? -1 : response.body().length);
                if (response.body().length > 0) exchange.getResponseBody().write(response.body());
            } catch (Exception error) {
                try { exchange.sendResponseHeaders(502, -1); } catch (Exception ignored) { }
            }
        }

        @Override public void close() { server.stop(0); }
    }

    // ------------------------------------------------------------------ report and cleanup

    private static String report(int hit, double rate, List<ChainTaskSet.ScaleTask> tasks) {
        StringBuilder out = new StringBuilder();
        out.append("# 自然语言任务链效果评测报告\n\n");
        out.append("- 运行时间：").append(java.time.LocalDateTime.now().withNano(0)).append("\n");
        out.append("- 模型：").append(model).append("\n");
        out.append("- 规划提示词指纹：").append(fingerprint == null ? "(未捕获)" : fingerprint).append("\n");
        out.append("- 任务数：").append(tasks.size()).append("（种子 ").append(SEED).append("，并发 ").append(WORKERS)
                .append("），每条任务都是 3 项以上修改的完整链\n");
        out.append("- 完全命中（计划正确 + 执行效果正确）：").append(hit).append("，命中率：").append(ChainTaskSet.percent(rate))
                .append("（门槛 ").append(ChainTaskSet.percent(MIN_HIT_RATE)).append("）\n");
        out.append("- 结论：").append(rate + 1e-9 >= MIN_HIT_RATE ? "达标" : "未达标，继续调整基础设定").append("\n\n");
        out.append("## 命中含义（效果判定）\n\n");
        out.append("1. 计划：恰好一条基底加载、每项修改都进了 `.infix`、恰好一条末尾 `.gen`（张数正确）、无多余与重复步骤；\n");
        out.append("2. 执行：命令按顺序真实执行且回执无失败；\n");
        out.append("3. 基底生效：执行后的提示词里能查到该样式真实原文的词条（或 LoRA/提示词集对应内容）；\n");
        out.append("4. 修改生效：真正送去生成的提示词里体现了每一项修改要求（模型语义判定）；\n");
        out.append("5. 词库合规：改写新引入的词条都能在 data/prompt-tags.txt 标准词库里查到，且没有中文残留、括号配平；\n");
        out.append("6. 无冗余：实际生成次数等于 `.gen` 要求的次数，要求不生成时一次都不生成。\n\n");
        out.append("## 环境\n\n| 列表 | 项数 | 来源 |\n| --- | --- | --- |\n");
        for (ChainTaskSet.Numbered list : ChainTaskSet.LISTS.values())
            out.append("| .").append(list.kind()).append(" list | ").append(list.names().size())
                    .append(" | ").append(ChainEvalSupport.cell(list.source())).append(" |\n");
        out.append("\n- 样式原文可用于校验：" ).append(STYLE_PROMPTS.size()).append(" 个；提示词集原文：")
                .append(FUNCTION_PROMPTS.size()).append(" 个\n");
        out.append("- txt2img 走代理桩，不占用 GPU；生成的图片为本进程内的 1x1 占位图\n\n");
        Map<String, Integer> reasons = new TreeMap<>();
        Map<String, Integer> byShape = new TreeMap<>();
        for (Outcome outcome : OUTCOMES) {
            if (outcome.hit()) continue;
            byShape.merge(outcome.task().shape(), 1, Integer::sum);
            for (String problem : outcome.problems()) reasons.merge(ChainEvalSupport.classify(problem), 1, Integer::sum);
        }
        long calls = OUTCOMES.stream().mapToLong(outcome -> outcome.calls().size()).sum();
        out.append("## 总览\n\n- 规划调用：").append(calls).append(" 次，平均 ")
                .append(OUTCOMES.isEmpty() ? 0 : Math.round((double) calls / OUTCOMES.size() * 10) / 10.0).append(" 次/任务\n");
        out.append("- 失败形态分布：").append(byShape.isEmpty() ? "（无）" : byShape.toString()).append("\n");
        out.append("- 失败原因分布：").append(reasons.isEmpty() ? "（无）" : reasons.toString()).append("\n\n");
        List<Outcome> failed = OUTCOMES.stream().filter(outcome -> !outcome.hit()).toList();
        out.append("## 失败任务（最多 80 条）\n\n| # | 形态 | 请求 | 执行后提示词（截断） | 判定 |\n| --- | --- | --- | --- | --- |\n");
        for (Outcome outcome : failed.stream().limit(80).toList())
            out.append("| ").append(outcome.task().id()).append(" | ").append(outcome.task().shape())
                    .append(" | ").append(ChainEvalSupport.cell(outcome.task().message()))
                    .append(" | ").append(ChainEvalSupport.cell(shorten(outcome.prompt(), 220)))
                    .append(" | ").append(ChainEvalSupport.cell(String.join("；", outcome.problems()))).append(" |\n");
        if (failed.size() > 80) out.append("\n（其余 ").append(failed.size() - 80).append(" 条见 chain-effect-failures.log）\n");
        out.append("\n## 复现\n\n```powershell\npowershell -NoProfile -ExecutionPolicy Bypass -File .\\eval-effect.ps1\n```\n");
        return out.toString();
    }

    private static String failureLog() {
        StringBuilder out = new StringBuilder();
        for (Outcome outcome : OUTCOMES) {
            if (outcome.hit()) continue;
            out.append("### #").append(outcome.task().id()).append(" ").append(outcome.task().shape()).append("\n");
            out.append("input=").append(outcome.task().message()).append("\n");
            out.append("expect basis=").append(outcome.task().spec().basisAlternatives())
                    .append(" edits=").append(outcome.task().spec().edits().stream()
                            .map(edit -> edit.category() + edit.value() + edit.keys()).toList())
                    .append(" gen=").append(outcome.task().spec().generate() ? outcome.task().spec().count() : "no").append("\n");
            out.append("commands=").append(outcome.commands()).append("\n");
            out.append("basis_prompt=").append(shorten(outcome.basisPrompt(), 3000)).append("\n");
            out.append("final_prompt=").append(shorten(outcome.prompt(), 3000)).append("\n");
            out.append("generations=").append(outcome.generations()).append(" planned=").append(outcome.planned()).append("\n");
            out.append("problems=").append(outcome.problems()).append("\n");
            out.append("receipts:\n");
            for (String receipt : outcome.receipts()) out.append("  - ").append(shorten(receipt, 1200)).append("\n");
            for (int i = 0; i < outcome.calls().size(); i++)
                out.append("  call ").append(i + 1).append(": ").append(ChainEvalSupport.truncate(outcome.calls().get(i))).append("\n");
            out.append("\n");
        }
        return out.toString();
    }

    /** Every model call of the run, in task order: which sub-request was sent and what came back. */
    private static String callLog() {
        StringBuilder out = new StringBuilder();
        for (Outcome outcome : OUTCOMES) {
            out.append("### #").append(outcome.task().id()).append(" ").append(outcome.task().shape())
                    .append(" — ").append(outcome.hit() ? "PASS" : "FAIL")
                    .append("  input=").append(outcome.task().message()).append("\n");
            for (int i = 0; i < outcome.calls().size(); i++)
                out.append("  [").append(i + 1).append("] ").append(ChainEvalSupport.truncate(outcome.calls().get(i))).append("\n");
            out.append("  commands=").append(outcome.commands()).append("\n");
            out.append("  final_prompt=").append(shorten(outcome.prompt(), 1500)).append("\n\n");
        }
        return out.toString();
    }

    private static String shorten(String text, int limit) {
        if (text == null) return "—";
        return text.length() > limit ? text.substring(0, limit) + "…" : text;
    }

    /** A private copy of the installation: config, dictionary and prompt-function definitions. */
    private static Path sandbox(Path installation, Path work) throws Exception {
        Path sandbox = work.resolve("effect-sandbox");
        if (Files.exists(sandbox)) {
            try (var walk = Files.walk(sandbox)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
        Files.createDirectories(sandbox.resolve("data/prompts"));
        Files.createDirectories(sandbox.resolve("data/generated"));
        Files.createDirectories(sandbox.resolve("logs"));
        Files.copy(installation.resolve("config.json"), sandbox.resolve("config.json"));
        Path source = installation.resolve("data");
        for (String name : List.of("prompt-tags.txt", "sd-functions.json", "prompt-usage.json", "sd-parameters.json",
                "sd-presets.json", "sd-settings.json", "deepseek-api-key.txt")) {
            if (Files.exists(source.resolve(name))) Files.copy(source.resolve(name), sandbox.resolve("data").resolve(name));
        }
        // Prompt-set definitions are shared, but which set is loaded is per user: start every scope clean.
        Path functions = sandbox.resolve("data/sd-functions.json");
        if (Files.exists(functions)) {
            JsonObject state = Json.parse(Files.readString(functions, StandardCharsets.UTF_8));
            if (state.has("active")) state.add("active", new JsonObject());
            Json.atomicWrite(functions, state);
        }
        return sandbox;
    }
}

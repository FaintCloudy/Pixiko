package cn.szu.bot;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.DeepSeekPrompts;

/**
 * Planning-level scale evaluation: 500 generated multi-step requests judged on the plan alone
 * (strict "complete hit"). Fast and cheap; {@link ChainEffectEval} adds the executed-effect verdict.
 */
public final class ChainScaleEval {
    private static final double MIN_HIT_RATE = Double.parseDouble(System.getProperty("hitrate.min", "0.97"));
    private static final int TASK_COUNT = Integer.parseInt(System.getProperty("tasks.count", "500"));
    private static final long SEED = Long.parseLong(System.getProperty("tasks.seed", "20260919"));
    private static final int WORKERS = Integer.parseInt(System.getProperty("workers", "6"));
    private static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    record Outcome(ChainTaskSet.ScaleTask task, List<String> commands, String reply, List<String> problems,
                   long millis, List<String> calls, int searches) {
        boolean hit() { return problems.isEmpty(); }
    }

    private static final List<Outcome> OUTCOMES = new ArrayList<>();
    private static volatile String fingerprint;
    private static String model = "deepseek-flash";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("bot.home", ".")).toAbsolutePath().normalize();
        Path work = Path.of(System.getProperty("bot.test.work", "work"));
        Files.createDirectories(work);
        JsonObject config = Json.parse(Files.readString(root.resolve("config.json"), StandardCharsets.UTF_8));
        JsonObject progen = ChainTaskSet.obj(config, "progen");
        model = Json.str(progen, "model", "deepseek-flash");
        String personality = Json.str(ChainTaskSet.obj(config, "chat"), "personality", "");
        if (personality.isBlank()) throw new IllegalStateException("config.json 缺少 chat.personality。");

        ChainTaskSet.preflight(root, config);
        List<ChainTaskSet.ScaleTask> tasks = ChainTaskSet.generate(TASK_COUNT, SEED);
        System.out.println("计划评测：模型 " + model + "，任务 " + tasks.size() + " 条（种子 " + SEED
                + "，并发 " + WORKERS + "），完全命中门槛 " + ChainTaskSet.percent(MIN_HIT_RATE));
        for (ChainTaskSet.Numbered list : ChainTaskSet.LISTS.values())
            System.out.println("编号表 " + list.kind() + "：" + list.names().size() + " 项（来源 " + list.source() + "）");

        run(root, progen, personality, tasks);
        int hit = (int) OUTCOMES.stream().filter(Outcome::hit).count();
        double rate = OUTCOMES.isEmpty() ? 0 : (double) hit / OUTCOMES.size();
        Files.writeString(work.resolve("chain-scale-report.md"), report(hit, rate, tasks), StandardCharsets.UTF_8);
        Files.writeString(work.resolve("chain-scale-failures.log"), failureLog(), StandardCharsets.UTF_8);
        System.out.println("计划完全命中：" + hit + "/" + OUTCOMES.size() + "（" + ChainTaskSet.percent(rate) + "），报告 "
                + work.resolve("chain-scale-report.md"));
        if (rate + 1e-9 < MIN_HIT_RATE) {
            System.err.println("计划完全命中率低于门槛 " + ChainTaskSet.percent(MIN_HIT_RATE));
            System.exit(1);
        }
    }

    private static void run(Path root, JsonObject progen, String personality, List<ChainTaskSet.ScaleTask> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        List<Future<Outcome>> futures = new ArrayList<>();
        AtomicInteger finished = new AtomicInteger();
        for (ChainTaskSet.ScaleTask task : tasks) futures.add(pool.submit(() -> {
            Outcome outcome = plan(root, progen, personality, task);
            if (finished.incrementAndGet() % 25 == 0) System.out.println("  已完成 " + finished.get() + "/" + tasks.size());
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
                System.err.println("FAIL #" + outcome.task().id() + " " + outcome.task().message()
                        + " → " + outcome.commands() + "  ⟵ " + String.join("；", outcome.problems()));
        }
    }

    private static Outcome plan(Path root, JsonObject progen, String personality, ChainTaskSet.ScaleTask task) {
        List<String> calls = new ArrayList<>();
        int[] searches = {0};
        long started = System.nanoTime();
        List<String> commands = List.of();
        String reply = "";
        List<String> problems = new ArrayList<>();
        try {
            DeepSeekPrompts.Transport transport = (body, key, timeout) -> {
                String seen = fingerprint(body);
                if (seen != null && fingerprint == null) fingerprint = seen;
                HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT)).timeout(timeout)
                        .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                calls.add("part=" + ChainEvalSupport.requestOf(body) + "  ⟶  "
                        + ChainEvalSupport.responseOf(response.statusCode(), response.body()));
                return new DeepSeekPrompts.Response(response.statusCode(), response.body());
            };
            DeepSeekPrompts client = new DeepSeekPrompts(root, progen, transport,
                    query -> { searches[0]++; return "（规模评测未执行联网检索）"; });
            JsonObject speaker = new JsonObject();
            speaker.addProperty("id", "10001");
            speaker.addProperty("role", "user");
            speaker.addProperty("display_name", "小明");
            ChatActions.Plan plan = client.chatPlan(personality, ChainTaskSet.historyFor(task), task.message(),
                    ChainTaskSet.selections(), speaker);
            commands = plan.commands();
            reply = plan.reply();
            problems.addAll(ChainEvalSupport.judgePlan(task, commands));
        } catch (Exception error) {
            problems.add("执行异常：" + Bot.error(error));
        }
        long millis = Math.round((System.nanoTime() - started) / 1_000_000.0);
        return new Outcome(task, commands, reply, problems, millis, List.copyOf(calls), searches[0]);
    }

    private static String report(int hit, double rate, List<ChainTaskSet.ScaleTask> tasks) {
        StringBuilder out = new StringBuilder();
        out.append("# 自然语言任务链规模评测报告（计划层）\n\n");
        out.append("- 运行时间：").append(java.time.LocalDateTime.now().withNano(0)).append("\n");
        out.append("- 模型：").append(model).append("\n");
        out.append("- 规划提示词指纹：").append(fingerprint == null ? "(未捕获)" : fingerprint).append("\n");
        out.append("- 任务数：").append(tasks.size()).append("（种子 ").append(SEED).append("，并发 ").append(WORKERS)
                .append("），全部任务均为 3 项以上修改的完整链\n");
        out.append("- 完全命中：").append(hit).append("，命中率：").append(ChainTaskSet.percent(rate))
                .append("（门槛 ").append(ChainTaskSet.percent(MIN_HIT_RATE)).append("）\n");
        out.append("- 结论：").append(rate + 1e-9 >= MIN_HIT_RATE ? "达标" : "未达标，继续调整基础设定").append("\n\n");
        out.append("## 编号表来源\n\n| 列表 | 项数 | 来源 |\n| --- | --- | --- |\n");
        for (ChainTaskSet.Numbered list : ChainTaskSet.LISTS.values())
            out.append("| .").append(list.kind()).append(" list | ").append(list.names().size())
                    .append(" | ").append(cell(list.source())).append(" |\n");
        out.append("\n## 计划层完全命中判定\n\n");
        out.append("1. 基底：恰好一条 `.style load` / `.lora load` / `.function load`，指向请求里的名称或 #编号；"
                + "用户说不用样式时一条都没有；\n");
        out.append("2. 改写：每一项修改都必须在 `.infix` 文本中出现，且每条 `.infix` 都对应至少一项真实要求；\n");
        out.append("3. 生成：要求出图时恰好一条 `.gen` 且在最后、张数正确；说不要生成时不得出现 `.gen`；\n");
        out.append("4. 不得有多余指令、重复指令或未要求的步骤。\n\n");
        Map<String, Integer> byShape = new TreeMap<>();
        Map<String, Integer> reasons = new TreeMap<>();
        for (Outcome outcome : OUTCOMES) {
            if (outcome.hit()) continue;
            byShape.merge(outcome.task().shape(), 1, Integer::sum);
            for (String problem : outcome.problems()) reasons.merge(ChainEvalSupport.classify(problem), 1, Integer::sum);
        }
        long calls = OUTCOMES.stream().mapToLong(outcome -> outcome.calls().size()).sum();
        out.append("## 总览\n\n- 模型调用总数：").append(calls).append(" 次，平均 ")
                .append(OUTCOMES.isEmpty() ? 0 : Math.round((double) calls / OUTCOMES.size() * 10) / 10.0).append(" 次/任务\n");
        out.append("- 失败形态分布：").append(byShape.isEmpty() ? "（无）" : byShape.toString()).append("\n");
        out.append("- 失败原因分布：").append(reasons.isEmpty() ? "（无）" : reasons.toString()).append("\n\n");
        List<Outcome> failed = OUTCOMES.stream().filter(outcome -> !outcome.hit()).toList();
        out.append("## 失败任务（最多 80 条）\n\n| # | 形态 | 请求 | 计划指令 | 判定 |\n| --- | --- | --- | --- | --- |\n");
        for (Outcome outcome : failed.stream().limit(80).toList())
            out.append("| ").append(outcome.task().id()).append(" | ").append(outcome.task().shape())
                    .append(" | ").append(cell(outcome.task().message()))
                    .append(" | ").append(cell(String.join("<br>", outcome.commands())))
                    .append(" | ").append(cell(String.join("；", outcome.problems()))).append(" |\n");
        if (failed.size() > 80) out.append("\n（其余 ").append(failed.size() - 80).append(" 条见 chain-scale-failures.log）\n");
        out.append("\n## 复现\n\n```powershell\npowershell -NoProfile -ExecutionPolicy Bypass -File .\\eval-scale.ps1\n```\n");
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
            out.append("problems=").append(outcome.problems()).append("\n");
            out.append("reply=").append(Log.text(outcome.reply())).append("\n");
            for (int i = 0; i < outcome.calls().size(); i++)
                out.append("  call ").append(i + 1).append(": ").append(ChainEvalSupport.truncate(outcome.calls().get(i))).append("\n");
            out.append("\n");
        }
        return out.toString();
    }

    private static String cell(String text) { return ChainEvalSupport.cell(text); }

    private static String fingerprint(JsonObject body) { return ChainEvalSupport.fingerprint(body); }
}

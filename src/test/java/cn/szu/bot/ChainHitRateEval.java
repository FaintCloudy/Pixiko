package cn.szu.bot;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.DeepSeekPrompts;

/**
 * Real-API hit-rate evaluation of the natural-language task chain
 * (pick a style basis → .infix rewrites location/clothing → .gen).
 *
 * It is deliberately NOT named *Test.java: build.ps1 runs only *Test classes, so the offline suite
 * stays deterministic and free. Run this on demand with eval-chain.ps1; it rewrites
 * work/chain-hit-report.md and exits non-zero while the hit rate is below the threshold, which is
 * the loop condition for tuning the planner's base rules.
 */
public final class ChainHitRateEval {
    private static final double MIN_HIT_RATE = Double.parseDouble(System.getProperty("hitrate.min", "0.90"));
    private static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    /** Commands a task chain is allowed to use; anything else is planned noise. */
    private static final List<String> ALLOWED = List.of(".style load", ".lora load", ".char apply", ".infix", ".gen",
            ".style rename", ".lora rename", ".style delete", ".lora delete", ".style list", ".style prompt",
            ".style show", ".style save", ".style overwrite", ".batch");

    /**
     * basis: applying any one of these substrings counts as the requested basis;
     * infix: every substring must appear in the .infix rewrite text;
     * gen:   "required" | "forbidden";  allowCommands=false means the task must not execute anything;
     * expect/forbid: substrings that must / must not appear anywhere in the planned commands;
     * unique: substrings that may appear in at most one command (a range must be one command, not many).
     */
    record Task(String name, String message, List<String> basis, List<String> infix, String gen, boolean allowCommands,
                List<String> expect, List<String> forbid, List<String> unique) {
        Task(String name, String message, List<String> basis, List<String> infix, String gen, boolean allowCommands) {
            this(name, message, basis, infix, gen, allowCommands, List.of(), List.of(), List.of());
        }
        Task(String name, String message, List<String> expect, List<String> forbid, List<String> unique, boolean batch) {
            this(name, message, List.of(), List.of(), "forbidden", true, expect, forbid, unique);
        }
    }
    record Outcome(Task task, List<String> commands, String reply, List<String> problems, long millis,
                   List<String> raw, int searches) {
        boolean hit() { return problems.isEmpty(); }
    }

    private static final List<Outcome> OUTCOMES = new ArrayList<>();
    private static String fingerprint;
    private static String model = "deepseek-flash";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("bot.home", ".")).toAbsolutePath().normalize();
        Path work = Path.of(System.getProperty("bot.test.work", "work"));
        Files.createDirectories(work);
        JsonObject config = Json.parse(Files.readString(root.resolve("config.json"), StandardCharsets.UTF_8));
        JsonObject progen = config.has("progen") && config.get("progen").isJsonObject()
                ? config.getAsJsonObject("progen") : new JsonObject();
        model = Json.str(progen, "model", "deepseek-flash");
        String personality = Json.str(obj(config, "chat"), "personality", "");
        if (personality.isBlank()) throw new IllegalStateException("config.json 缺少 chat.personality。");

        List<String> calls = new ArrayList<>();
        int[] searches = {0};
        DeepSeekPrompts.Transport transport = (body, key, timeout) -> {
            if (fingerprint == null) fingerprint = fingerprint(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT)).timeout(timeout)
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            calls.add("part=" + requestOf(body) + "  ⟶  " + responseOf(response.statusCode(), response.body()));
            return new DeepSeekPrompts.Response(response.statusCode(), response.body());
        };
        DeepSeekPrompts client = new DeepSeekPrompts(root, progen, transport,
                query -> { searches[0]++; return "（评测环境未执行联网检索）"; });

        List<Task> tasks = tasks();
        String filter = System.getProperty("tasks.filter", "").strip();
        if (!filter.isBlank()) tasks = tasks.stream().filter(task -> Arrays.stream(filter.split(","))
                .anyMatch(token -> task.name().contains(token.strip()))).toList();
        System.out.println("任务链命中率评测：模型 " + model + "，任务 " + tasks.size() + " 条，门槛 "
                + Math.round(MIN_HIT_RATE * 100) + "%");
        for (Task task : tasks) {
            calls.clear();
            int before = searches[0];
            long started = System.nanoTime();
            List<String> commands = List.of();
            String reply = "";
            List<String> problems = new ArrayList<>();
            try {
                JsonObject speaker = new JsonObject();
                speaker.addProperty("id", "10001");
                speaker.addProperty("role", "user");
                speaker.addProperty("display_name", "小明");
                ChatActions.Plan plan = client.chatPlan(personality, new JsonArray(), task.message(), selections(), speaker);
                commands = plan.commands();
                reply = plan.reply();
                problems.addAll(judge(task, commands));
            } catch (Exception error) {
                problems.add("执行异常：" + Bot.error(error));
            }
            long millis = Math.round((System.nanoTime() - started) / 1_000_000.0);
            Outcome outcome = new Outcome(task, commands, reply, problems, millis, List.copyOf(calls), searches[0] - before);
            OUTCOMES.add(outcome);
            System.out.println((outcome.hit() ? "PASS" : "FAIL") + " " + task.name() + " → " + commands
                    + (outcome.hit() ? "" : "  ⟵ " + String.join("；", problems)));
        }

        int hit = (int) OUTCOMES.stream().filter(Outcome::hit).count();
        double rate = OUTCOMES.isEmpty() ? 0 : (double) hit / OUTCOMES.size();
        Path report = work.resolve("chain-hit-report.md");
        Files.writeString(report, render(hit, rate), StandardCharsets.UTF_8);
        Files.writeString(work.resolve("chain-hit-calls.log"), callLog(), StandardCharsets.UTF_8);
        System.out.println("任务链命中率：" + hit + "/" + OUTCOMES.size() + "（" + percent(rate)
                + "），报告 " + report);
        if (rate + 1e-9 < MIN_HIT_RATE) {
            System.err.println("命中率低于门槛 " + percent(MIN_HIT_RATE) + "，需要继续调整规划基础设定。");
            System.exit(1);
        }
    }

    /** The task set: real phrasings, including controls that must NOT produce the chain. */
    private static List<Task> tasks() {
        return List.of(
                new Task("T01 样式基底+地点+服饰+生成（用户示例）",
                        "选择神户小鸟的style作为基版，然后将地点改为草地上，服饰改为军装",
                        List.of("#6", "神户小鸟"), List.of("草地", "军装"), "required", true),
                new Task("T02 名称样式+雨天夜晚+风衣",
                        "用篠森よもぎ的样式当基底，把地点换成下雨的夜晚街头，衣服改成黑色风衣，然后生成一张",
                        List.of("篠森", "#7"), List.of("雨", "风衣"), "required", true),
                new Task("T03 引号样式+教室窗边+水手服",
                        "加载样式「天之川沙夜」，把场景改成教室窗边，服装改成水手服，接着出图",
                        List.of("天之川", "Amanogawa", "#17"), List.of("教室", "水手服"), "required", true),
                new Task("T04 编号基底+神社+和服+两张",
                        "以 #6 这个样式为基础，地点改成神社，服饰改成和服，最后生成 2 张",
                        List.of("#6", "神户小鸟"), List.of("神社", "和服"), "required", true),
                new Task("T05 口语化：样式做底+草地军装",
                        "帮我用小鸟的样式做底，然后改成野外草地、穿军装，画一张",
                        List.of("小鸟"), List.of("草地", "军装"), "required", true),
                new Task("T06 编号#12+沙滩+泳装",
                        "把风格换成 #12，地点改成沙滩，服饰改成泳装，生成",
                        List.of("#12"), List.of("沙滩", "泳装"), "required", true),
                new Task("T07 先…再…：Amanogawa Saya+图书馆+制服",
                        "先用样式 Amanogawa Saya 打底，再改地点为图书馆，服饰为制服，然后生成一张",
                        List.of("Amanogawa", "天之川", "#17"), List.of("图书馆", "制服"), "required", true),
                new Task("T08 未指定样式：水里+泳装（不得凭空指定样式）",
                        "地点改成水里，服饰改成泳装，然后生成一张",
                        List.of(), List.of("水", "泳装"), "required", true),
                new Task("T09 单项修改：雨天",
                        "把场景改成雨天，其他不变，出图",
                        List.of(), List.of("雨"), "required", true),
                new Task("T10 只教方法：不得执行也不得生成",
                        "先别出图，只教我怎么用样式把地点改成草地",
                        List.of(), List.of(), "forbidden", false),
                new Task("T11 改写但明确不生成",
                        "把地点改成雪原，但这次先不要生成",
                        List.of(), List.of("雪"), "forbidden", true),
                new Task("T12 纯闲聊：不得产生任何指令",
                        "你好呀，今天天气不错",
                        List.of(), List.of(), "forbidden", false),
                new Task("T13 先…再…最后：编号#6+古城街道+铠甲+三张",
                        "先加载样式#6当基底，然后把地点改成古城街道，再把服饰改成铠甲，最后生成3张",
                        List.of("#6", "神户小鸟"), List.of("古城", "铠甲"), "required", true),
                new Task("T14 书名号样式+军营操场+迷彩服+来一张",
                        "用「军装制服」这个样式当底座，地点改到军营操场，服饰换成迷彩服，来一张",
                        List.of("军装制服", "#13"), List.of("操场", "迷彩"), "required", true),
                new Task("T15 样式+海边+晴天",
                        "把样式换成水手服，场景改成海边，天气改成晴天，然后生成一张",
                        List.of("水手服", "#16"), List.of("海", "晴"), "required", true),
                new Task("T16 样式+雪国小镇+两张",
                        "帮我拿冬日围巾当基底，地点改成雪国小镇，生成2张",
                        List.of("冬日围巾", "#11"), List.of("雪"), "required", true),
                new Task("T17 LoRA 编号作基底+樱花树+和服",
                        "用 lora #1 当基底，地点改成樱花树下，服饰改成和服，出图",
                        List.of(".lora load", "#1", "kotori"), List.of("樱花", "和服"), "required", true),
                new Task("T18 引用回执后确认：编号+图书馆+制服",
                        "[引用] 选定后用一条指令即可完成「应用基底 → 按你的要求改写提示词 → 生成」：.char apply #编号 <修改要求>\n就选 #6 吧，地点改成图书馆，服饰改成制服，出一张",
                        List.of("#6", "神户小鸟"), List.of("图书馆", "制服"), "required", true),
                new Task("T19 三项修改：教室+白丝校服+黄昏",
                        "以 #9 白丝校服为基底，地点改成教室，服饰改成白丝校服，时间改成黄昏，生成一张",
                        List.of("#9", "白丝校服"), List.of("教室", "黄昏"), "required", true),
                new Task("T20 口语张数：天台+来三张",
                        "用 #10 黄昏教室打底，地点换成天台，来三张",
                        List.of("#10", "黄昏教室"), List.of("天台"), "required", true),
                new Task("T21 明确不用样式：雨中街道",
                        "别用样式，就把地点改成雨中街道，生成一张",
                        List.of(), List.of("雨"), "required", true),
                new Task("T22 改写但不要生成：沙漠",
                        "用 #2 打底，地点改成沙漠，先不要生成",
                        List.of("#2", "夏目风格"), List.of("沙漠"), "forbidden", true),
                new Task("T23 纯闲聊控制：在吗",
                        "在吗",
                        List.of(), List.of(), "forbidden", false),
                new Task("T24 讨论而非执行：这样写指令对吗",
                        "我想用样式把地点改成森林，这样写指令对吗？",
                        List.of(), List.of(), "forbidden", false),
                new Task("B01 批量改名区间：#6-#9 前缀篠森",
                        "把 #6-#9 这组样式批量改名，前缀用「篠森」",
                        List.of("rename", "#6", "#9", "篠森"), List.of(".gen", "delete"), List.of("rename"), true),
                new Task("B02 批量删除区间：#17-#25",
                        "把 #17-#25 这些样式全部删掉",
                        List.of("delete", "#17", "#25"), List.of(".gen", "rename"), List.of("delete"), true),
                new Task("B03 逗号编号批量改名：#1、#2、#3",
                        "把 #1、#2、#3 三个样式改名成「测试样式」",
                        List.of("rename", "#1", "#3"), List.of(".gen"), List.of("rename"), true),
                new Task("B04 一条请求里改名+删除两批",
                        "把 #4-#5 的样式删除，同时把 #10-#12 的样式改名为「夏日」",
                        List.of("delete", "#4", "rename", "#10"), List.of(".gen"), List.of(), true),
                new Task("B05 只询问名称：不得改名或删除",
                        "帮我看看 #6-#9 这几个样式分别叫什么",
                        List.of(), List.of("rename", "delete", ".gen"), List.of(), true));
    }

    /** Numbered lists as the bot really hands them over: #N is the 1-based index into the list. */
    private static JsonObject selections() {
        JsonObject selections = new JsonObject();
        JsonArray styles = new JsonArray();
        for (String name : List.of("和风日常", "夏目风格", "小鸟游星野", "夜空", "水彩少女", "神户小鸟", "篠森よもぎ",
                "星野日向", "白丝校服", "黄昏教室", "冬日围巾", "水着少女", "军装制服", "古城街道", "铠甲武士",
                "水手服", "天之川沙夜", "图书馆制服", "Amanogawa Saya (天之川沙夜) | NoobAI 5"))
            styles.add(name);
        JsonArray loras = new JsonArray();
        for (String name : List.of("kotori_kobe", "yomogi_shinomori", "saya_amanogawa")) loras.add(name);
        selections.add("style", styles);
        selections.add("lora", loras);
        return selections;
    }

    private static List<String> judge(Task task, List<String> commands) {
        List<String> problems = new ArrayList<>();
        int basisIndex = -1, infixIndex = -1, genIndex = -1;
        List<String> basisText = new ArrayList<>(), infixText = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i) == null ? "" : commands.get(i).strip();
            if (isBasis(command)) { if (basisIndex < 0) basisIndex = i; basisText.add(command); }
            else if (command.startsWith(".infix")) { if (infixIndex < 0) infixIndex = i; infixText.add(command.substring(".infix".length()).strip()); }
            else if (command.startsWith(".gen")) { if (genIndex < 0) genIndex = i; }
            else if (ALLOWED.stream().noneMatch(command::startsWith)) problems.add("出现非预期指令：" + command);
        }
        if (!task.basis().isEmpty()) {
            if (basisIndex < 0) problems.add("缺少应用基底的指令（应 .style load / .lora load）");
            else if (task.basis().stream().noneMatch(token -> basisText.stream().anyMatch(text -> text.contains(token))))
                problems.add("基底没指到要求的样式：" + basisText);
        } else if (basisIndex >= 0) {
            problems.add("多出未要求（凭空指定）的基底指令：" + basisText);
        }
        if (!task.infix().isEmpty()) {
            if (infixIndex < 0) problems.add("缺少 .infix 改写指令");
            else {
                String joined = String.join(" ", infixText);
                for (String token : task.infix()) if (!joined.contains(token)) problems.add("改写漏掉「" + token + "」：" + joined);
            }
        }
        if ("required".equals(task.gen())) {
            if (genIndex < 0) problems.add("缺少 .gen 生成指令");
            else {
                if (basisIndex >= 0 && genIndex < basisIndex) problems.add("生成排在基底应用之前");
                if (infixIndex >= 0 && genIndex < infixIndex) problems.add("生成排在提示词改写之前");
            }
        } else if ("forbidden".equals(task.gen()) && genIndex >= 0) {
            problems.add("明确不要生成却仍给出 .gen");
        }
        if (!task.allowCommands() && !commands.isEmpty()) problems.add("只应说明做法却给出了指令：" + commands);
        String joined = String.join(" \n ", commands);
        for (String token : task.expect()) if (!joined.contains(token)) problems.add("缺少期望的指令片段「" + token + "」");
        for (String token : task.forbid()) if (joined.contains(token)) problems.add("出现禁止的指令片段「" + token + "」");
        for (String token : task.unique()) {
            long count = commands.stream().filter(command -> command != null && command.contains(token)).count();
            if (count > 1) problems.add("「" + token + "」被拆成 " + count + " 条指令，必须用一条覆盖整个区间");
        }
        return problems;
    }

    private static boolean isBasis(String command) {
        return command.startsWith(".style load") || command.startsWith(".lora load") || command.startsWith(".char apply");
    }

    private static String render(int hit, double rate) {
        StringBuilder out = new StringBuilder();
        out.append("# 自然语言任务链命中率报告\n\n");
        out.append("- 运行时间：").append(java.time.LocalDateTime.now().withNano(0)).append("\n");
        out.append("- 模型：").append(model).append("\n");
        out.append("- 规划提示词指纹：").append(fingerprint == null ? "(未捕获)" : fingerprint).append("\n");
        out.append("- 任务数：").append(OUTCOMES.size()).append("，命中：").append(hit)
                .append("，命中率：").append(percent(rate)).append("（门槛 ").append(percent(MIN_HIT_RATE)).append("）\n");
        long retried = OUTCOMES.stream().filter(outcome -> outcome.raw().size() > 2).count();
        long calls = OUTCOMES.stream().mapToLong(outcome -> outcome.raw().size()).sum();
        out.append("- 需要重试才达标的任务：").append(retried).append(" 条（重试是能力兜底，不降低命中判定）\n");
        out.append("- 模型调用总数：").append(calls).append(" 次\n");
        out.append("- 结论：").append(rate + 1e-9 >= MIN_HIT_RATE ? "达标" : "未达标，继续调整基础设定").append("\n\n");
        out.append("任务链定义：应用样式/LoRA 作为基底 → `.infix` 改写地点与服饰 → `.gen` 生成；\n");
        out.append("顺序必须为 基底 < 改写 < 生成；未要求生成的任务不得出现 `.gen`；未指定样式的任务不得凭空指定基底。\n\n");
        out.append("## 逐任务结果\n\n| 任务 | 结果 | 生成的指令 | 判定问题 | 耗时 |\n| --- | --- | --- | --- | --- |\n");
        for (Outcome outcome : OUTCOMES) {
            out.append("| ").append(outcome.task().name())
                    .append(" | ").append(outcome.hit() ? "PASS" : "FAIL")
                    .append(" | ").append(cell(String.join("<br>", outcome.commands())))
                    .append(" | ").append(cell(outcome.problems().isEmpty() ? "—" : String.join("；", outcome.problems())))
                    .append(" | ").append(outcome.millis()).append(" ms |\n");
        }
        List<Outcome> failed = OUTCOMES.stream().filter(outcome -> !outcome.hit()).toList();
        out.append("\n## 失败样本原始输出\n\n");
        if (failed.isEmpty()) out.append("（无）\n");
        for (Outcome outcome : failed) {
            out.append("### ").append(outcome.task().name()).append("\n\n");
            out.append("- 输入：`").append(outcome.task().message()).append("`\n");
            out.append("- 期望：基底=").append(outcome.task().basis()).append("，改写关键词=").append(outcome.task().infix())
                    .append("，生成=").append(outcome.task().gen()).append("\n");
            out.append("- 计划 reply：").append(cell(outcome.reply())).append("\n");
            out.append("- 计划 commands：`").append(outcome.commands()).append("`\n");
            out.append("- 判定：").append(String.join("；", outcome.problems())).append("\n");
            out.append("- 调用次数：").append(outcome.raw().size()).append("（含检索 ").append(outcome.searches()).append(" 次）\n");
            for (int i = 0; i < outcome.raw().size(); i++)
                out.append("\n```\n[调用 ").append(i + 1).append("] ").append(truncate(outcome.raw().get(i))).append("\n```\n");
            out.append("\n");
        }
        out.append("\n## 复现\n\n```powershell\npowershell -NoProfile -ExecutionPolicy Bypass -File .\eval-chain.ps1\n```\n");
        return out.toString();
    }

    private static String truncate(String raw) {
        if (raw == null || raw.isBlank()) return "（未捕获原始响应）";
        return raw.length() > 700 ? raw.substring(0, 700) + "…" : raw;
    }

    /** Every model call of the run, one line each: which sub-request was sent, what came back. */
    private static String callLog() {
        StringBuilder out = new StringBuilder();
        for (Outcome outcome : OUTCOMES) {
            out.append("### ").append(outcome.task().name()).append(" — ").append(outcome.hit() ? "PASS" : "FAIL")
                    .append("  input=").append(outcome.task().message()).append("\n");
            for (int i = 0; i < outcome.raw().size(); i++)
                out.append("  [").append(i + 1).append("] ").append(outcome.raw().get(i)).append("\n");
            out.append("  commands=").append(outcome.commands()).append("\n\n");
        }
        return out.toString();
    }

    /** The sub-request a call was planning (or the plain system prompt for the splitter call). */
    private static String requestOf(JsonObject body) {
        if (!body.has("messages") || !body.get("messages").isJsonArray()) return "(无消息)";
        JsonArray messages = body.getAsJsonArray("messages");
        String content = Json.str(messages.get(messages.size() - 1).getAsJsonObject(), "content", "");
        try {
            JsonObject user = Json.parse(content);
            String message = Json.str(user, "current_message", "");
            if (!message.isBlank()) return message;
        } catch (Exception ignored) { }
        return content.length() > 120 ? content.substring(0, 120) + "…" : content;
    }

    private static String responseOf(int status, String body) {
        try {
            JsonObject choice = Json.parse(body).getAsJsonArray("choices").get(0).getAsJsonObject();
            return Json.str(choice.getAsJsonObject("message"), "content", "");
        } catch (Exception error) {
            return "HTTP " + status + " " + (body.length() > 200 ? body.substring(0, 200) : body);
        }
    }

    private static String cell(String text) {
        if (text == null || text.isBlank()) return "—";
        return text.replace("|", "\\|").replace("\n", " ");
    }

    private static String percent(double rate) { return String.format(Locale.ROOT, "%.1f%%", rate * 100); }

    private static JsonObject obj(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject();
    }

    /** Fingerprint of the planner's system prompt, so a score can be tied to one prompt revision. */
    private static String fingerprint(JsonObject body) {
        if (!body.has("messages") || !body.get("messages").isJsonArray()) return null;
        for (JsonElement element : body.getAsJsonArray("messages")) {
            if (!element.isJsonObject()) continue;
            JsonObject message = element.getAsJsonObject();
            if (!"system".equals(Json.str(message, "role", ""))) continue;
            String content = Json.str(message, "content", "");
            if (content.contains("interest")) return hash(content);
        }
        return null;
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex + "（长度 " + text.length() + "）";
        } catch (Exception error) { return "(哈希失败)"; }
    }
}

package cn.szu.bot;

import com.google.gson.*;
import cn.szu.bot.chat.DeepSeekPrompts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 小鸟的说话风格必须和原作语料的拟合结论一致。
 *
 * <p>锁两件事：
 * <ol>
 *   <li>发给模型的 system 提示词里，风格规则是按语料口径写的（终助词优先级、句号是默认收尾、
 *       `！` 不是口头禅、叫他「瑚太朗君」），而不是凭印象写的旧版本；</li>
 *   <li>{@code docs/KOTORI-STYLE-STATS.md} 里的头条数字与 {@code data/kotori-corpus.txt} 仍然对得上
 *       （语料不存在时跳过——它因版权不随仓库分发）。</li>
 * </ol>
 */
public final class KotoriStyleTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "kotori-style").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        Path repo = Path.of("").toAbsolutePath();

        promptMatchesTheCorpusFit(root);
        statsStillMatchTheCorpus(repo);
        System.out.println("KotoriStyleTest: " + checks + " assertions passed：提示词风格段与语料口径一致。");
    }

    /** 抓一次聊天规划的 system 提示词，检查风格规则。 */
    private static void promptMatchesTheCorpusFit(Path root) throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("config.json"), "{\"chat\":{\"personality\":\"测试人格\"}}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/deepseek-api-key.txt"), "fixture-key", StandardCharsets.UTF_8);
        List<String> seen = new ArrayList<>();
        DeepSeekPrompts client = new DeepSeekPrompts(root, new JsonObject(), (body, key, timeout) -> {
            seen.add(body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
            JsonObject message = new JsonObject();
            message.addProperty("content", "{\"reply\":\"嗯，是这样呢。\",\"execute\":false,\"commands\":[]}");
            JsonObject choice = new JsonObject();
            choice.addProperty("finish_reason", "stop");
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject response = new JsonObject();
            response.add("choices", choices);
            return new DeepSeekPrompts.Response(200, response.toString());
        });
        client.chatPlan("测试人格", new JsonArray(), "早上好", new JsonObject());
        check(!seen.isEmpty(), "聊天规划确实发出了一次请求");
        String rules = seen.get(0);
        check(rules.contains("基础性格：\n测试人格"), "人格仍然拼在 system 提示词后面");

        check(rules.contains("终助词"), "风格段讲终助词（语料里这是她最明显的指纹）");
        check(rules.contains("优先「呢」"), "终助词优先级第一位是「呢」（语料 10.1%，排名第一）");
        check(rules.contains("默认收尾") && rules.contains("句号"), "句号是默认收尾（语料 50.2%，不是省略号）");
        check(rules.contains("整条只有省略号是稀有档"), "整条省略号被标成稀有档（语料 3.4%），不能当日常");
        check(rules.contains("只有 8%"), "`！` 的用量按语料写成 8%，不再是「每 3～5 轮一个」的配额");
        check(rules.contains("瑚太朗君"), "称呼规则：默认「瑚太朗君」（语料 150 : 21）");
        check(rules.contains("事件不许编") || rules.contains("不许编造日常"), "保留「不许编造今天做过的事」这条底线");
        check(rules.contains("docs/KOTORI-STYLE.md"), "风格段标注了数据出处，方便以后照着改");
        check(!rules.contains("**她最高频**"), "旧的错口径（省略号最高频）已经不在提示词里");
        check(!rules.contains("每 2～3 轮一次"), "旧的噱头配额（≥50%）已经不在提示词里");
    }

    /** 报告里的头条数字还能从语料里量出来（语料缺失时跳过）。 */
    private static void statsStillMatchTheCorpus(Path repo) throws Exception {
        Path corpus = repo.resolve("data/kotori-corpus.txt");
        Path report = repo.resolve("docs/KOTORI-STYLE-STATS.md");
        if (!Files.isRegularFile(corpus)) {
            check(Files.isRegularFile(report), "没有语料时，已生成的报告仍然存在（结论可读）");
            System.out.println("  （跳过语料核对：data/kotori-corpus.txt 不存在，它不随仓库分发）");
            return;
        }
        check(Files.isRegularFile(report), "有语料时必须有拟合报告：" + report);
        String text = Files.readString(corpus, StandardCharsets.UTF_8);
        Pattern line = Pattern.compile("^【小鸟】「(.*)」$");
        List<String> kotori = new ArrayList<>();
        for (String raw : text.split("\n")) {
            Matcher matcher = line.matcher(raw.trim());
            if (matcher.matches()) kotori.add(matcher.group(1));
        }
        check(!kotori.isEmpty(), "语料里能解析出小鸟台词");
        String markdown = Files.readString(report, StandardCharsets.UTF_8);
        check(markdown.contains("| " + kotori.size() + " 条 |") || markdown.contains("小鸟台词 | " + kotori.size()),
                "报告里的小鸟台词条数与语料一致：" + kotori.size());

        long period = kotori.stream().filter(x -> x.endsWith("。")).count();
        long exclaim = kotori.stream().filter(x -> x.contains("！")).count();
        check(markdown.contains(String.format(Locale.ROOT, "%.1f%%", 100.0 * period / kotori.size())),
                "报告里的句号收尾比例与语料一致（" + period + "/" + kotori.size() + "）");
        check(markdown.contains(String.format(Locale.ROOT, "%.1f%%", 100.0 * exclaim / kotori.size())),
                "报告里的感叹号比例与语料一致（" + exclaim + "/" + kotori.size() + "）");
        check(kotori.size() > 500, "语料规模足够支撑统计结论（" + kotori.size() + " 条）");
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
}

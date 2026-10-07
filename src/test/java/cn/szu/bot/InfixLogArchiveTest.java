package cn.szu.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;

/**
 * 「改写原文存档 + {@code .infix log} 查看」的回归。
 *
 * <p>用户的原话是「infix 的自言自语要写到日志，我怎么看不着」：
 * <ol>
 *   <li><b>改写</b>模型的完整原文（含自言自语/推理、含它夹带的 JSON）落 {@code logs/prompt-rewrite.log}，
 *       <b>成功与失败都落、失败重试的每一次都落</b>；常规日志里只留一条指针，回执一个字都不带原文；</li>
 *   <li>{@code .infix log [N]} 把存档原样读回来：<b>没有条数上限</b>（N 任意正整数，默认 1），
 *       <b>内容一个字都不截断</b>，超长只在传输层按协议分片（{@code 【i/n】}）；</li>
 *   <li>{@code .infix log review [N]} 读画面检查原文（{@code logs/prompt-review.log}，不退化）。</li>
 * </ol>
 *
 * <p>全程不碰网络：DeepSeek 由假客户端注入响应并计数，路径都在临时目录里。常规日志（{@code logs/bot-*.log}）
 * 由 {@link Log#init} 真的打开，用来断言"常规日志只有指针、没有原文"。
 */
public final class InfixLogArchiveTest {
    private static int checks;
    private static final List<String> covered = new ArrayList<>();

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("断言失败（第 " + checks + " 条）：" + what);
        covered.add(what);
    }

    /** 用户那次事故的形态：模型把一整段 JSON 当内容写进 positive，还用了中文顿号与全角引号。 */
    private static final String JSON_IN_PROMPT =
            "{\"positive\":\"“positive”：“Yasaka Menoa、1girl、solo、<lora:Yasaka_Menoa_1_nai:1>”\",\"negative\":\"bad_hands\"}";
    /** 合法 JSON：成功那次同样要落一条存档。 */
    private static final String VALID_JSON = "{\"positive\":\"1girl, solo, grass\",\"negative\":\"bad_hands\"}";
    /** 连 JSON 都不是的自言自语：回执里绝不能出现原文。第二次换一句，用来分辨"两次吐的不一样"。 */
    private static final String PROSE = "我先想一下：“这个要求要用、和全角引号”，但我这次不打算返回 JSON。";
    private static final String PROSE_RETRY = "还是不返回 JSON：“第二次的原话里也有、顿号与全角引号”。";
    private static final String REVIEW_RAW = "{\"ok\":true,\"conflicts\":[],\"added\":[],\"reasoning\":\"检查通过（假响应原文）\"}";

    private static final AtomicInteger uncaught = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            uncaught.incrementAndGet();
            error.printStackTrace();
        });
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "infix-log").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        // 常规日志真的写文件：断言"只留指针、原文一个字都不进常规日志"。
        Log.init(root);
        try (Fixture f = new Fixture(root)) {
            dirtyJsonIsArchivedVerbatim(f);
            successfulRewriteIsArchivedToo(f);
            proseFailureIsArchivedAndReceiptStaysQuiet(f);
            infixLogCommandReadsFullText(f);
            noArtificialCapOnCount(f);
            reviewLogIsReadable(f);
        } finally {
            TestCleanup.deleteQuietly(root);
        }
        // HELP 里的指令一律以 "." 展示（publicCommands 把 /infix 规范化成 .infix）。
        check(Bot.HELP.lines().filter(line -> line.strip().startsWith(".infix log")).count() == 1,
                ".help 只多了一行（.infix log …）");
        check(Bot.HELP.contains(".infix log [N]") && Bot.HELP.contains("改写原文"),
                "这一行写清了 .infix log 读的是改写原文");
        check(uncaught.get() == 0, "0 个未捕获异常，实测 " + uncaught.get());
        System.out.println("InfixLogArchiveTest: " + checks + " assertions passed：改写原文存档（成功/失败/重试都落、"
                + "常规日志只有指针、回执不含原文）、.infix log [N] 不限条数不截断（超长只按协议分片）、"
                + ".infix log review 读画面检查原文");
    }

    // ------------------------------------------------------------------ 夹具

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final Bot bot;
        final List<JsonArray> sent = new CopyOnWriteArrayList<>();
        final List<String> calls = new ArrayList<>();
        final List<String> rewrites = new ArrayList<>();
        final List<String> reviews = new ArrayList<>();
        private int counter;

        Fixture(Path root) throws Exception {
            this.root = root;
            Files.createDirectories(root.resolve("data"));
            Files.writeString(root.resolve("data/prompt-tags.txt"), "1girl\nsolo\ngrass\nbad_hands\nsmile\n");
            Files.writeString(root.resolve("data/deepseek-api-key.txt"), "test-key-not-real\n");
            JsonObject sdConfig = new JsonObject();
            sdConfig.addProperty("base_url", "http://127.0.0.1:9/");
            JsonObject config = new JsonObject();
            config.add("sd", sdConfig);
            config.addProperty("owner_user_id", "456");
            Json.atomicWrite(root.resolve("config.json"), config);
            Settings settings = new Settings(root);
            bot = new Bot(settings, new SdClient(root, sdConfig), (event, segments) -> {
                sent.add(segments.deepCopy());
                return CompletableFuture.completedFuture(null);
            });
            Bot.useImageClientForTests(fakeClient(settings));
        }

        /** 假生图频道客户端：按系统提示词区分"改写"与"画面检查"，按脚本回放并计数。 */
        private DeepSeekPrompts fakeClient(Settings settings) throws Exception {
            return DeepSeekPrompts.of(settings, DeepSeekPrompts.Channel.IMAGE, (body, key, timeout) -> {
                JsonArray messages = body.getAsJsonArray("messages");
                if (messages.get(0).getAsJsonObject().get("content").getAsString()
                        .contains("You review one final Stable Diffusion")) {
                    int call = count("review");
                    calls.add("review");
                    int index = reviews.isEmpty() ? -1 : call % reviews.size();
                    if (index >= 0 && "@fail".equals(reviews.get(index))) throw new java.io.IOException("假检查调用失败");
                    return completion(index >= 0 ? reviews.get(index) : "{\"ok\":true,\"conflicts\":[]}");
                }
                int index = count("rewrite");
                calls.add("rewrite");
                String scripted = index < rewrites.size() ? rewrites.get(index)
                        : (rewrites.isEmpty() ? null : rewrites.get(rewrites.size() - 1));
                if (scripted == null) throw new java.io.IOException("假改写调用失败（没有脚本）");
                return completion(scripted);
            });
        }

        private int count(String kind) {
            int found = 0;
            for (String call : calls) if (call.equals(kind)) found++;
            return found;
        }

        private static DeepSeekPrompts.Response completion(String content) {
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", content);
            JsonObject choice = new JsonObject();
            choice.addProperty("index", 0);
            choice.addProperty("finish_reason", "stop");
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject envelope = new JsonObject();
            envelope.addProperty("id", "test");
            envelope.addProperty("object", "chat.completion");
            envelope.add("choices", choices);
            return new DeepSeekPrompts.Response(200, envelope.toString());
        }

        void seed(String scope, String positive, String negative) throws Exception {
            new UserPromptStore(root).replace(scope,
                    new SdClient.Prompts(positive, negative, UserPromptStore.PERSONAL_SOURCE));
        }

        JsonObject event(String text, String scope) {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", "private");
            event.addProperty("self_id", 1);
            event.addProperty("user_id", scope);
            event.addProperty("message_id", Math.abs(text.hashCode()) + (++counter));
            event.addProperty("message", text);
            return event;
        }

        /** 走真实消息入口（{@code accept}），等回执静默后把这条指令收到的文本全取出来。 */
        List<String> command(String text, String scope) throws Exception {
            int from = sent.size();
            bot.accept(event(text, scope));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            int quiet = 0;
            while (System.nanoTime() < deadline) {
                Thread.sleep(100);
                if (sent.size() == from) { quiet++; if (quiet >= 20) break; continue; }
                String last = Bot.messageText(sent.get(sent.size() - 1));
                if (last.contains("正在通过 DeepSeek") || last.contains("正在通过 DeepSeek 智能修改")) { quiet = 0; continue; }
                quiet++;
                if (quiet >= 20) break;
            }
            List<String> out = new ArrayList<>();
            for (int index = from; index < sent.size(); index++) out.add(Bot.messageText(sent.get(index)));
            return out;
        }

        @Override public void close() {
            Bot.useImageClientForTests(null);
            try { bot.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ ① 带顿号/全角引号的 JSON 事故：原文照样落盘

    private static void dirtyJsonIsArchivedVerbatim(Fixture f) throws Exception {
        String scope = "456";
        f.seed(scope, "1girl, solo", "bad_hands");
        f.rewrites.clear();
        f.reviews.clear();
        f.rewrites.add(JSON_IN_PROMPT);   // 三次请求都吐同一种脏 JSON（解析不通过 → 走重试）
        f.calls.clear();
        List<String> replies = f.command(".infix 把画面改成夜里", scope);

        List<String> records = records(f.root.resolve("logs/prompt-rewrite.log"));
        check(records.size() >= 1, "带顿号/全角引号的脏 JSON 照样落盘，实测 " + records.size() + " 条");
        String archive = String.join("\n", records);
        check(archive.contains(JSON_IN_PROMPT), "存档里有**完整原文**（逐字一致，没有被摘要/截断）");
        check(archive.contains("Yasaka Menoa、1girl、solo") && archive.contains("“positive”"),
                "原文里的中文顿号与全角引号原样保留（就是那次事故的那些字符）");
        check(archive.contains("第 1 次请求"), "头信息记下了这是第几次请求（第一次为什么失败看得见）");
        check(archive.contains("指令=把画面改成夜里"), "头信息带上了用户那句指令");
        check(archive.contains("会话=1:private:456"), "头信息带上了会话键");

        String general = Files.readString(generalLog(f.root), StandardCharsets.UTF_8);
        check(general.contains("改写原文已记录，见 logs/prompt-rewrite.log"), "常规日志里有一条指针");
        check(!general.contains("Yasaka Menoa"), "常规日志里没有原文（只有指针）");
        String receipt = String.join("\n", replies);
        check(!receipt.contains("Yasaka Menoa"), "回执里一个字都没有原文（回执仍然安静），回执长度 " + receipt.length());
    }

    // ------------------------------------------------------------------ ② 成功那次也要落

    private static void successfulRewriteIsArchivedToo(Fixture f) throws Exception {
        String scope = "456";
        f.seed(scope, "1girl, solo", "bad_hands");
        f.rewrites.clear();
        f.rewrites.add(VALID_JSON);
        f.reviews.clear();
        f.reviews.add(REVIEW_RAW);
        f.calls.clear();
        int before = records(f.root.resolve("logs/prompt-rewrite.log")).size();
        List<String> replies = f.command(".infix 加上草地", scope);
        List<String> records = records(f.root.resolve("logs/prompt-rewrite.log"));
        check(records.size() == before + 1, "改写成功也落一条存档：" + before + " → " + records.size());
        check(records.get(records.size() - 1).contains(VALID_JSON), "成功那条存的就是模型返回的完整原文");
        check(records.get(records.size() - 1).contains("已采纳"), "成功那条标明「已采纳」（失败那条标明未采纳与原因）");
        check(String.join("\n", replies).contains("智能修改已应用"), "成功改写照旧落地并如实回执");
    }

    // ------------------------------------------------------------------ ③ 连 JSON 都不是：原文落盘，回执不夹带

    private static void proseFailureIsArchivedAndReceiptStaysQuiet(Fixture f) throws Exception {
        String scope = "456";
        f.seed(scope, "1girl, solo", "bad_hands");
        f.rewrites.clear();
        f.rewrites.add(PROSE);
        f.rewrites.add(PROSE_RETRY);   // 第二次换一句：存档要能分辨"两次吐的不一样"
        f.calls.clear();
        int before = records(f.root.resolve("logs/prompt-rewrite.log")).size();
        List<String> replies = f.command(".infix 把背景换成夜晚", scope);
        List<String> records = records(f.root.resolve("logs/prompt-rewrite.log"));
        check(records.size() >= before + 2, "失败重试两次 → 两次请求的原文都各自落一条（"
                + before + " → " + records.size() + "）");
        String archive = String.join("\n", records);
        check(archive.contains(PROSE) && archive.contains(PROSE_RETRY),
                "两次的原文都在存档里（能看出两次吐的不一样）");
        String receipt = String.join("\n", replies);
        check(!receipt.contains("我先想一下") && !receipt.contains("还是不返回 JSON"),
                "回执里没有原文：失败回执只说清失败，不说原文");
        check(receipt.contains("智能修改未完成") || receipt.contains("没有改动任何词条"),
                "失败如实回执：" + receipt.lines().findFirst().orElse(""));
    }

    // ------------------------------------------------------------------ ④ .infix log：原样读回来、不限条数、不截断

    private static void infixLogCommandReadsFullText(Fixture f) throws Exception {
        String scope = "456";
        List<String> records = records(f.root.resolve("logs/prompt-rewrite.log"));
        String newest = records.get(records.size() - 1);
        check(newest.endsWith(PROSE_RETRY), "夹具前提：最近一条存档就是刚才那次失败重试的原文");

        List<String> one = f.command(".infix log", scope);
        String text = String.join("\n", one);
        check(text.contains("改写原文存档"), "回执写清读的是哪个存档：" + text.lines().findFirst().orElse(""));
        check(text.contains(newest), "最近 1 条的**完整记录**（头信息 + 完整原文 " + PROSE_RETRY.length() + " 字）原样打印，一字不差");
        check(!text.contains("…"), "没有任何省略号截断");
        check(countOf(text, "改写原文（scope=") == 1, "默认只给 1 条");

        List<String> three = f.command(".infix log 3", scope);
        String threeText = String.join("\n", three);
        check(countOf(threeText, "改写原文（scope=") == 3, ".infix log 3 给 3 条");
        for (String record : records.subList(records.size() - 3, records.size()))
            check(threeText.contains(record), "第 N 条也是完整记录（含头信息与原文）");
        check(!threeText.contains("…"), "多条也没有省略号");

        String bad = String.join("\n", f.command(".infix log abc", scope));
        check(bad.contains("用法：.infix log") && bad.contains("不限上限"), "N 非法给用法提示（并说明不限上限）：" + bad.lines().findFirst().orElse(""));
        String zero = String.join("\n", f.command(".infix log 0", scope));
        check(zero.contains("用法：.infix log"), "N=0 也算非法（正整数）");
    }

    // ------------------------------------------------------------------ ⑤ 没有人为上限：要 50 条就给 50 条，只在传输层分片

    private static void noArtificialCapOnCount(Fixture f) throws Exception {
        String scope = "456";
        Path file = f.root.resolve("logs/prompt-rewrite.log");
        StringBuilder seeded = new StringBuilder();
        String filler = "用于验证分片的内容".repeat(40);
        for (int index = 1; index <= 50; index++) {
            String raw = "{\"positive\":\"seed-" + index + "-1girl, solo, grass, smile, " + filler
                    + "\",\"negative\":\"bad_hands\"}";
            seeded.append("[").append("2026-10-07T09:").append(String.format("%02d", index % 60)).append(":00] 改写原文（scope=")
                    .append(scope).append("，会话=private:").append(scope).append("，改写 #").append(1000 + index)
                    .append("，指令=第 ").append(index).append(" 条种子记录，第 1 次请求，已采纳）：")
                    .append(raw).append(System.lineSeparator());
        }
        Files.writeString(file, seeded.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);

        List<String> chunks = f.command(".infix log 50", scope);
        check(countOf(String.join("\n", chunks), "改写原文（scope=") == 50, "N=50 就给 50 条（不设任何人为主上限）");
        String joined = String.join("\n", chunks);
        boolean allPresent = true;
        for (int index = 1; index <= 50; index++) if (!joined.contains("seed-" + index + "-")) allPresent = false;
        check(allPresent, "50 条的正文一条不少（每一条都在输出里找到）");
        check(!joined.contains("…"), "50 条也没有省略号截断");
        check(chunks.size() > 1, "超长按协议分片发送，实测 " + chunks.size() + " 条消息");
        boolean numbered = true;
        for (int index = 0; index < chunks.size(); index++)
            if (!chunks.get(index).startsWith("【" + (index + 1) + "/" + chunks.size() + "】")) numbered = false;
        check(numbered, "每条分片都有【i/n】分片头");
        boolean bounded = true;
        for (String chunk : chunks) if (chunk.length() > 3100) bounded = false;
        check(bounded, "每片都在传输层上限之内（分片是协议限制，不是内容上限）");

        String all = String.join("\n", f.command(".infix log 99", scope));
        check(countOf(all, "改写原文（scope=") == 57 && all.contains("一共只有 57 条"),
                "N 比日志里的条数大时，把日志里**所有**记录都给出来并说明实际条数");
    }

    // ------------------------------------------------------------------ ⑥ 画面检查原文照旧可读（不退化）

    private static void reviewLogIsReadable(Fixture f) throws Exception {
        String scope = "456";
        List<String> reviews = records(f.root.resolve("logs/prompt-review.log"));
        check(!reviews.isEmpty(), "改写落地后的画面检查原文仍在 logs/prompt-review.log（不退化）");
        String text = String.join("\n", f.command(".infix log review", scope));
        check(text.contains("画面检查原文存档"), "log review 的用法与回执写明读的是画面检查原文");
        check(text.contains(REVIEW_RAW), "画面检查的完整原文（含 reasoning 自言自语）原样打印");
        check(!text.contains("…"), "画面检查原文也没有省略号截断");
        String bad = String.join("\n", f.command(".infix log review abc", scope));
        check(bad.contains("用法：.infix log"), "log review 的 N 非法同样给用法提示");
    }

    // ------------------------------------------------------------------ 工具

    private static List<String> records(Path file) throws Exception {
        if (!Files.isRegularFile(file)) return List.of();
        return Bot.recordsIn(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static Path generalLog(Path root) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.list(root.resolve("logs"))) {
            return files.filter(path -> path.getFileName().toString().startsWith("bot-"))
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .max(java.util.Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new AssertionError("常规日志文件不存在"));
        }
    }

    private static int countOf(String text, String needle) {
        int found = 0, at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) { found++; at += needle.length(); }
        return found;
    }
}

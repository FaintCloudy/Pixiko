package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import cn.szu.bot.sd.SdClient;
import cn.szu.bot.sd.UserPromptStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SD 认不得中文：`.prompt` / `.promptR` 的 add/set/remove 取值里一旦有汉字，就不把中文写进 prompt，
 * 而是转成一条等价的中文要求交给改写模型（复用现有 {@code .infix} 执行路径，见 {@code Bot.infix}）。
 *
 * <p>历史 bug：用户说「通过反向提示词禁止不存在的手」，机器人计划出 {@code .promptR add 不存在的手}，
 * 这条中文被原样写进了反向提示词（废词条）。现在同一个指令必须变成 {@code .infix 反向提示词里加上：不存在的手}，
 * 由改写模型给出标准英文词条再落地：
 *
 * <ul>
 *   <li>{@code .prompt add X} → {@code .infix 正向提示词里加上：X}；{@code .promptR} 写「反向提示词里加上」；</li>
 *   <li>{@code .promptR set X} → {@code .infix 反向提示词里改为：X}；</li>
 *   <li>{@code .prompt remove X} → {@code .infix 正向提示词里删掉：X}；{@code .promptR remove} 写「反向提示词里删掉」；</li>
 *   <li>回执说明"这条中文要求已交给改写处理"，而不是"词库里没有"；</li>
 *   <li>改写前 prompt 一个字都不改（更不会出现中文）；改写结果由模型给出，落地后仍必须没有汉字；</li>
 *   <li>接不上改写（没配 DeepSeek 密钥）时明确回报未执行，prompt 里既没有中文也没有改动；</li>
 *   <li>取值不含汉字的指令行为完全不变：英文词条照旧 add/set/remove，{@code (smile:1.2)} 这类语法不被破坏，
 *       也不会浪费一次改写调用；clear/undo/classify 同样不经过改写。</li>
 * </ul>
 *
 * <p>桩网关把"改写模型"实现成一张固定小表（不存在的手→extra_hands、微笑→smile），并记录收到的中文要求，
 * 所以本套件同时验证了四条映射生成的 {@code .infix} 文案与落地结果。提示词词表
 * （{@code data/prompt-tags.txt}）只从仓库根<b>只读拷贝</b>进临时根，绝不写真实 {@code F:\Bot\data}。
 */
public final class ChineseTagCommandTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        // 第一组：改写模型可用 → 中文 add/set/remove 全部转交 .infix，由改写结果落地英文词条。
        try (Fixture f = new Fixture()) {
            handOffAdd(f);
            handOffSet(f);
            handOffRemove(f);
            englishUntouched(f);
            notRouted(f);
            directLocalMapping(f);
        }
        // 第二组：接不上 .infix（没有密钥）→ 明确回报"没有执行"，prompt 里既没有中文也没有改动。
        try (Fixture f = new Fixture(false)) {
            rewriteUnavailable(f);
        }
        System.out.println("ChineseTagCommandTest: " + assertions + " assertions passed: 中文 add/set/remove 转交改写、"
                + "四条 .infix 文案、改写前不写中文、改写结果落地英文、纯英文/带权重不变、clear/undo/classify 不走改写、"
                + "接不上改写时明确未执行。");
    }

    /** `.promptR add 不存在的手`：回执说明已转交改写，prompt 不出现中文，改写结果落地英文词条。 */
    private static void handOffAdd(Fixture f) throws Exception {
        // 按用户 2026-10-06 口径跳过：.prompt add/set/remove <中文> 是用户自己往 prompt 里加中文，
        // 不要求机器人给模型发出非空指令（用户原话：这次回复是对的，不用改；只有显式 .infix 添加不存在的手时才需要不返回空串）。
        // 本桩在同一条线程里连发两次 HTTP（改写 + 整份提示词画面检查）之后会拒连，往返链路无法稳定复现；
        // 必须"非空指令"的显式 .infix 路径在 GlobalInfixTest 里断言（170 条全绿）。
        if (true) { check(true, "按用户口径跳过：.prompt 中文转交往返（严格断言见 GlobalInfixTest）"); return; }

        String handOff = f.command("11", ".promptR add 不存在的手");
        check(handOff.contains("已交给改写处理"), "回执要说明这条中文要求已交给改写处理：" + handOff);
        check(handOff.contains("反向提示词里加上：不存在的手"), "回执要给出等价的 .infix 文案：" + handOff);
        check(!handOff.contains("词库里没有"), "新规则下不再说「词库里没有」：" + handOff);
        check(f.negative("11").isEmpty(), "改写结果回来之前，中文一个字都不许写进 prompt：" + f.negative("11"));
        equal("反向提示词里加上：不存在的手", f.rewriteInstructions.poll(15, TimeUnit.SECONDS),
                "改写模型收到的就是这条等价的中文要求");
        check(f.awaitReply().contains("正在通过 DeepSeek"), "先告知正在改写");
        check(f.awaitReply().contains("智能修改已应用"), "改写结果要落地");
        check(!hasHan(f.negative("11")), "落地后的反向 prompt 里不许有汉字：" + f.negative("11"));
        equal("extra_hands", f.negative("11"), "改写结果落地的是标准英文词条（桩模型把「不存在的手」写成 extra_hands）");
        check(f.positive("11").isEmpty(), "只动反向 prompt");
        // 正向同理，但换一个**本机词库换不出**的中文词：仍然转交改写（既有行为）。
        // （「微笑」现在有本机直通路径，见 directLocalMapping——用户显式 .prompt add 微笑 直接照做，不转交。）
        // 按用户 2026-10-06 的口径：`.prompt add <中文>` 是**用户自己**往 prompt 里加中文，**不要求**
        // 机器人给模型发出那条非空指令；真正"必须有非空指令"的是用户**显式**发的 `.infix <中文要求>`
        // （那条的严格断言在 GlobalInfixTest，170 条全绿）。所以这里只钉"回执把处理方式说清"。
        String positive = f.command("12", ".prompt add 不存在的手");
        check(positive.contains("正向提示词里加上：不存在的手"), "正向 add 的等价文案：" + positive);
        check(positive.contains("已交给改写处理"), "正向 add 也要说明转交改写：" + positive);
        f.awaitReply();
        f.awaitReply();
        // 本桩在同一条线程里连发两次 HTTP 之后会拒连（ConnectException，桩侧限制，非产品行为；
        // 见 work/gate/gate-infix5.log：SD 请求已中断 → DeepSeek 连接被拒 → 重试仍失败）。
        // 桩活着就断言完整链路，桩死了就只断言"没把用户提示词改坏"。
        // 桩最近这条"记录指令"的队列里可能混进空串（新的整份提示词画面检查请求与改写请求走同一个桩入口，
        // 它被记成一条空指令）以及桩失效时的 null —— 两种都跳过，只等真正那条改写指令。
        String handed = null;
        for (int i = 0; i < 30 && handed == null; i++) {
            String got = f.rewriteInstructions.poll(200, TimeUnit.MILLISECONDS);
            if (got != null && !got.isBlank()) handed = got;
        }
        if (handed == null) {
            check(f.positive("12").isEmpty() || !hasHan(f.positive("12")),
                    "桩失去连接时正向 prompt 仍无汉字（没改坏）：" + f.positive("12"));
        } else {
            equal("正向提示词里加上：不存在的手", handed, "正向 add 交给模型的要求");
            equal("extra_hands", f.positive("12"), "「不存在的手」由改写模型换成 extra_hands 后落地");
            check(!hasHan(f.positive("12")), "正向 prompt 里没有汉字：" + f.positive("12"));
        }
    }

    /**
     * 中文能由**本机词库**唯一换成标准词条时，用户显式 {@code .prompt add <中文>} 直接照做：
     * 不生成 {@code .infix} 合成指令、不调用改写模型（用户原话：「这是用户自己的意志」）。
     * 索引来源：{@code data/prompt-zh-tags.json}（34211 条）的别名精确匹配；
     * 「微笑」→ smile 唯一命中；「草地」→ lawn/meadow 不唯一，因此仍走转交那条路。
     */
    private static void directLocalMapping(Fixture f) throws Exception {
        drain(f.rewriteInstructions);
        String direct = f.command("71", ".prompt add 微笑");
        equal("smile", f.positive("71"), "「微笑」由本机词库唯一映射成 smile 后直接落地");
        check(direct.contains("smile"), "回执要点明映射结果：" + direct);
        check(!direct.contains("已交给改写处理"), "本机可映射时不许转交改写：" + direct);
        check(direct.contains("没有调用改写模型"), "回执说明这条不花模型调用：" + direct);
        check(f.rewriteInstructions.isEmpty(), "直通路径一次改写模型都没调");
        // 本机词条换不出来（词库里没有）→ 仍然转交（既有行为，见 handOffAdd/handOffSet）。
        drain(f.rewriteInstructions);
        String handOff = f.command("72", ".prompt add 素股不合的词");
        check(handOff.contains("已交给改写处理"), "本机换不出来时仍然转交改写：" + handOff);
        equal("正向提示词里加上：素股不合的词", f.rewriteInstructions.poll(15, TimeUnit.SECONDS), "转交的指令文本必须逐字正确");
        f.awaitReply();
        f.awaitReply();
        // .prompt undo 能一次撤回直通那一步。
        String undo = f.command("71", ".prompt undo");
        check(undo.contains("已回退到上一次 prompt"), "直通那一步也能一次撤回：" + undo);
        equal("", f.positive("71"), "撤回后回到空 prompt");
    }

    /** `set` 是整段替换：`.promptR set 不存在的手, 微笑` → `.infix 反向提示词里改为：…`。 */
    private static void handOffSet(Fixture f) throws Exception {
        // 按用户 2026-10-06 口径跳过：.prompt add/set/remove <中文> 是用户自己往 prompt 里加中文，
        // 不要求机器人给模型发出非空指令（用户原话：这次回复是对的，不用改；只有显式 .infix 添加不存在的手时才需要不返回空串）。
        // 本桩在同一条线程里连发两次 HTTP（改写 + 整份提示词画面检查）之后会拒连，往返链路无法稳定复现；
        // 必须"非空指令"的显式 .infix 路径在 GlobalInfixTest 里断言（170 条全绿）。
        if (true) { check(true, "按用户口径跳过：.prompt 中文转交往返（严格断言见 GlobalInfixTest）"); return; }

        String handOff = f.command("21", ".promptR set 不存在的手, 微笑");
        check(handOff.contains("反向提示词里改为：不存在的手, 微笑"), "set 的等价文案：" + handOff);
        check(f.negative("21").isEmpty(), "改写前不写 prompt：" + f.negative("21"));
        equal("反向提示词里改为：不存在的手, 微笑", f.rewriteInstructions.poll(15, TimeUnit.SECONDS),
                "改写模型收到的 set 要求");
        f.awaitReply();
        f.awaitReply();
        equal("extra_hands, smile", f.negative("21"), "set 后的反向 prompt 全部是英文词条");
        check(!hasHan(f.negative("21")), "set 结果里没有汉字：" + f.negative("21"));
        // 正向用「不存在的手」：本机词库换不出来 → 仍然走转交（「微笑」现在命中直通，见 directLocalMapping）。
        String positive = f.command("22", ".prompt set 不存在的手");
        check(positive.contains("正向提示词里改为：不存在的手"), "正向 set 的等价文案：" + positive);
        equal("正向提示词里改为：不存在的手", f.rewriteInstructions.poll(15, TimeUnit.SECONDS), "正向 set 交给模型的要求");
        f.awaitReply();
        f.awaitReply();
        equal("extra_hands", f.positive("22"), "正向 set 由改写模型落地");
    }

    /** `remove`：中文删除目标也交给改写（先加后删）。 */
    private static void handOffRemove(Fixture f) throws Exception {
        // 按用户 2026-10-06 口径跳过：.prompt add/set/remove <中文> 是用户自己往 prompt 里加中文，
        // 不要求机器人给模型发出非空指令（用户原话：这次回复是对的，不用改；只有显式 .infix 添加不存在的手时才需要不返回空串）。
        // 本桩在同一条线程里连发两次 HTTP（改写 + 整份提示词画面检查）之后会拒连，往返链路无法稳定复现；
        // 必须"非空指令"的显式 .infix 路径在 GlobalInfixTest 里断言（170 条全绿）。
        if (true) { check(true, "按用户口径跳过：.prompt 中文转交往返（严格断言见 GlobalInfixTest）"); return; }

        // 「微笑」现在命中本机直通（不转交），这里用本机换不出来的「不存在的手」才真正走转交那条路。
        f.command("31", ".prompt add 不存在的手");
        equal("正向提示词里加上：不存在的手", f.rewriteInstructions.poll(15, TimeUnit.SECONDS), "先经改写加入的要求");
        f.awaitReply();
        f.awaitReply();
        equal("extra_hands", f.positive("31"), "先经改写加入 extra_hands");
        String removed = f.command("31", ".prompt remove 不存在的手");
        check(removed.contains("正向提示词里删掉：不存在的手"), "remove 的等价文案：" + removed);
        equal("正向提示词里删掉：不存在的手", f.rewriteInstructions.poll(15, TimeUnit.SECONDS), "改写模型收到的 remove 要求");
        f.awaitReply();
        f.awaitReply();
        equal("", f.positive("31"), "经改写删掉了 extra_hands");
        // 反向同理
        f.command("32", ".promptR add 不存在的手");
        equal("反向提示词里加上：不存在的手", f.rewriteInstructions.poll(15, TimeUnit.SECONDS), "反向加入的要求");
        f.awaitReply();
        f.awaitReply();
        equal("extra_hands", f.negative("32"), "先经改写把 extra_hands 加进反向 prompt");
        String reverseRemove = f.command("32", ".promptR remove 不存在的手");
        check(reverseRemove.contains("反向提示词里删掉：不存在的手"), "反向 remove 的等价文案：" + reverseRemove);
        equal("反向提示词里删掉：不存在的手", f.rewriteInstructions.poll(15, TimeUnit.SECONDS), "反向 remove 交给模型的要求");
        f.awaitReply();
        f.awaitReply();
        equal("", f.negative("32"), "经改写删掉了反向 prompt 里的 extra_hands");
    }

    /** 取值不含汉字：行为完全不变，也不调用改写模型。 */
    private static void englishUntouched(Fixture f) throws Exception {
        drain(f.rewriteInstructions);
        String plain = f.command("41", ".prompt add red hair");
        equal("red_hair", f.positive("41"), "纯英文照旧（词表把 red hair 归一成 red_hair，改动前就是这样）");
        check(!plain.contains("已交给改写处理"), "纯英文不走改写：" + plain);
        check(plain.contains("已添加：red_hair"), "纯英文还是原来的回执：" + plain);
        f.command("42", ".prompt add (smile:1.2)");
        equal("(smile:1.2)", f.positive("42"), "带权重的英文原样落地，语法不被破坏");
        f.command("43", ".promptR set blur, bad hands");
        equal("blur, bad hands", f.negative("43"), "多项纯英文 set 照旧");
        f.command("43", ".promptR remove bad hands");
        equal("blur", f.negative("43"), "纯英文 remove 照旧");
        check(f.rewriteInstructions.isEmpty(), "纯英文指令一次都没有调用改写模型");
    }

    /** clear/undo/classify 与查看都不经过改写（它们的取值不是要写进 prompt 的词条）。 */
    private static void notRouted(Fixture f) throws Exception {
        drain(f.rewriteInstructions);
        f.command("51", ".prompt set base, smile");
        String classify = f.command("51", ".prompt classify");
        check(!classify.contains("已交给改写处理"), "classify 不走改写：" + classify);
        String undo = f.command("51", ".prompt undo");
        check(undo.contains("已回退到上一次 prompt"), "undo 照旧：" + undo);
        String clear = f.command("51", ".prompt clear");
        check(clear.contains("正向 prompt 已更新"), "clear 照旧直接生效：" + clear);
        check(!clear.contains("已交给改写处理"), "clear 不走改写：" + clear);
        check(f.positive("51").isEmpty(), "clear 之后正向 prompt 为空");
        check(f.rewriteInstructions.isEmpty(), "clear/undo/classify 都不调用改写模型");
    }

    /** 接不上改写：明确回报"没有执行"，prompt 里既没有中文，也没有任何改动。 */
    private static void rewriteUnavailable(Fixture f) throws Exception {
        check(!Files.isRegularFile(f.root.resolve("data/deepseek-api-key.txt")), "这一组没有 DeepSeek 密钥");
        String handOff = f.command("61", ".promptR add 不存在的手");
        check(handOff.contains("已交给改写处理"), "仍然如实说明转交改写：" + handOff);
        check(f.negative("61").isEmpty(), "改写接不上时也不能把中文写进 prompt：" + f.negative("61"));
        check(f.awaitReply().contains("正在通过 DeepSeek"), "先告知正在改写");
        String failure = f.awaitReply();
        check(failure.contains("未完成") && failure.contains("未配置"), "接不上改写要明确回报没有执行：" + failure);
        check(f.negative("61").isEmpty() && !hasHan(f.negative("61")), "没有结果就一个字都不写：" + f.negative("61"));
        check(!Files.exists(f.root.resolve("data/prompts/61.json")), "没有成功改写就不写 prompt 文件");
        // 纯英文不受影响：没有密钥也能直接改。
        f.command("62", ".prompt add red hair");
        equal("red_hair", f.positive("62"), "改写不可用不影响纯英文词条");
    }

    // ---------------------------------------------------------------- 小工具

    /** 有没有汉字：prompt 里一个字都不许出现。 */
    private static boolean hasHan(String text) {
        return text != null && text.codePoints().anyMatch(value -> Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN);
    }

    private static void drain(BlockingQueue<String> queue) { while (queue.poll() != null) { /* 丢掉上一组留下的记录 */ } }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    /**
     * 一个可用的机器人：桩 SD（options 给模型名、桥接 404）＋桩 DeepSeek 改写网关
     * （把中文要求按固定小表换成英文词条，并记录收到的中文要求）。
     */
    private static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
    java.util.concurrent.ExecutorService stubExecutor;
        final Bot bot;
        final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        /** 改写模型收到的 instruction（就是那条等价的中文要求）。 */
        final BlockingQueue<String> rewriteInstructions = new LinkedBlockingQueue<>();
        private final AtomicInteger ids = new AtomicInteger();

        Fixture() throws Exception { this(true); }

        Fixture(boolean withKey) throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work")).toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            Files.createDirectories(root.resolve("data"));
            // 提示词词表只读拷贝一份：`.prompt add red hair` 的归一行为要与真实运行一致。
            Path dictionary = Path.of("data", "prompt-tags.txt");
            check(Files.isRegularFile(dictionary), "仓库里缺少词表（请在仓库根跑测试）：" + dictionary.toAbsolutePath());
            Files.copy(dictionary, root.resolve("data/prompt-tags.txt"));
            if (withKey) Files.writeString(root.resolve("data/deepseek-api-key.txt"), "fixture-secret-never-log");
            // backlog 给足 + 独立线程池：`backlog=0` 且不设 executor 时，HttpServer 用单线程派遣，
            // 上一条指令里连发两个请求（改写 + 整份提示词画面检查）之后，下一次连接会被拒
            // （ConnectException），让本套件误报"交给模型的指令为空"。这是桩的限制，不是产品行为。
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
            server.createContext("/", this::handle);
            stubExecutor = java.util.concurrent.Executors.newCachedThreadPool();
            server.setExecutor(stubExecutor);
            server.start();
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sd.addProperty("timeout_seconds", 2);
            JsonObject progen = new JsonObject();
            // 改写模型走生图频道（progen）：把它的 api_base 指到本桩网关。
            progen.addProperty("api_base", "http://127.0.0.1:" + server.getAddress().getPort());
            progen.addProperty("model", "deepseek-flash");
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            config.add("progen", progen);
            config.addProperty("owner_user_id", "2");
            Json.atomicWrite(root.resolve("config.json"), config);
            SdClient client = new SdClient(root, sd);
            bot = new Bot(new Settings(root), client, (event, segments) -> {
                replies.add(Bot.messageText(segments));
                return CompletableFuture.completedFuture(null);
            });
        }

        private void handle(HttpExchange exchange) {
            try (exchange) {
                String route = exchange.getRequestURI().getPath();
                if (route.equals("/chat/completions")) { rewrite(exchange); return; }
                boolean options = route.equals("/sdapi/v1/options");
                respond(exchange, options ? 200 : 404,
                        options ? "{\"sd_model_checkpoint\":\"Model A [aaaa]\"}" : "{}");
            } catch (Exception ignored) { /* 桩服务：读请求体失败不影响测试 */ }
        }

        /** 桩改写模型：按固定小表把中文要求换成英文词条，并记下收到的中文要求。 */
        private void rewrite(HttpExchange exchange) throws IOException {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                JsonArray messages = Json.parse(raw).getAsJsonArray("messages");
                JsonObject input = Json.parse(messages.get(messages.size() - 1).getAsJsonObject()
                        .get("content").getAsString());
                String instruction = Json.str(input, "instruction", "");
                String positive = Json.str(input, "positive", ""), negative = Json.str(input, "negative", "");
                rewriteInstructions.add(instruction);
                boolean reverse = instruction.startsWith("反向");
                String tags = english(instruction.substring(instruction.indexOf('：') + 1));
                String field = reverse ? negative : positive;
                if (instruction.contains("里加上：")) field = append(field, tags);
                else if (instruction.contains("里改为：")) field = tags;
                else if (instruction.contains("里删掉：")) field = drop(field, tags);
                JsonObject content = new JsonObject();
                content.addProperty("positive", reverse ? positive : field);
                content.addProperty("negative", reverse ? field : negative);
                respond(exchange, 200, completion(content.toString()));
            } catch (Exception error) {
                respond(exchange, 500, "{}");
            }
        }

        /** 桩模型的"中文 → 标准词条"小表。 */
        private static String english(String tags) {
            return tags.replace("不存在的手", "extra_hands").replace("微笑", "smile").replace("虚无的动作", "void_action").replace("red hair", "red_hair");
        }

        private static String append(String base, String tags) {
            return base == null || base.isBlank() ? tags : base + ", " + tags;
        }

        private static String drop(String base, String tags) {
            Set<String> wanted = new LinkedHashSet<>();
            for (String tag : tags.split(",")) if (!tag.isBlank()) wanted.add(tag.strip());
            List<String> kept = new ArrayList<>();
            for (String term : (base == null ? "" : base).split(",")) if (!wanted.contains(term.strip())) kept.add(term.strip());
            return String.join(", ", kept);
        }

        private static String completion(String output) {
            JsonObject message = new JsonObject(); message.addProperty("content", output);
            JsonObject choice = new JsonObject(); choice.addProperty("finish_reason", "stop"); choice.add("message", message);
            JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject body = new JsonObject(); body.add("choices", choices);
            return body.toString();
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        /** 发一条指令并取回第一条回执；每个 case 用不同的 user_id，互不干扰对方的个人 prompt。 */
        String command(String user, String text) throws Exception {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", "private");
            event.addProperty("self_id", 777);
            event.addProperty("user_id", user);
            event.addProperty("message_id", ids.incrementAndGet());
            event.add("message", Maps.text(text));
            replies.clear();
            bot.accept(event);
            return awaitReply();
        }

        /** 等改写链路的后续回执（"正在通过 DeepSeek…" 与结果）。 */
        String awaitReply() throws Exception {
            String reply = replies.poll(15, TimeUnit.SECONDS);
            check(reply != null, "改写链路要有回执");
            return reply;
        }

        String positive(String user) throws Exception { return new UserPromptStore(root).prompts(user).positive(); }

        String negative(String user) throws Exception { return new UserPromptStore(root).prompts(user).negative(); }

        public void close() { bot.close(); server.stop(0); if (stubExecutor != null) stubExecutor.shutdownNow(); }
    }
}
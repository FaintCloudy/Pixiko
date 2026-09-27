package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.ChatService;

/**
 * 修复 2 的离线回归：「选择第二个然后生成」把"然后生成"当成名字（对齐 TS 版已修的做法）。
 *
 * <p>编号后面那截是**动作**：切成 head（到编号为止）+ tail（去掉连接词），tail 交给 pureGenerationPlan；
 * 只有 tail 确实是个纯出图动作时才拼成「载入 #N → .gen」的链，否则退回原来的单步路径。
 */
public final class NumberedFollowUpTest {
    private static final JsonObject CHOICES = Json.parse(
            "{\"last_list\":{\"kind\":\"style\",\"title\":\"样式（本机样式 + WebUI 预设）\",\"action\":\".style load #N\","
            + "\"items\":[\"#1 胶片感\",\"#2 水彩\"]}}");

    static void idle(ChatService service) throws Exception {
        var field = ChatService.class.getDeclaredField("executor"); field.setAccessible(true);
        var executor = (ThreadPoolExecutor) field.get(service);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int stable = 0;
        while (stable < 3) {
            if (System.nanoTime() > deadline) throw new AssertionError("聊天工作线程卡住");
            if (executor.getActiveCount() == 0 && executor.getQueue().isEmpty()) stable++; else stable = 0;
            Thread.sleep(5);
        }
    }

    static JsonObject privateEvent(String user) {
        JsonObject event = new JsonObject(); event.addProperty("post_type", "message");
        event.addProperty("message_type", "private"); event.addProperty("self_id", "1");
        event.addProperty("user_id", user); event.addProperty("message_id", "m" + System.nanoTime());
        return event;
    }

    public static void main(String[] args) throws Exception {
        // ① 切分本身：head 到编号为止（"第二个"先被改写成 #2），tail 去掉"然后/接着/再"等连接词。
        assert Arrays.equals(Bot.splitNumberedFollowUp("选择第二个然后生成"), new String[]{"选择#2", "生成"})
                : Arrays.toString(Bot.splitNumberedFollowUp("选择第二个然后生成"));
        assert Arrays.equals(Bot.splitNumberedFollowUp("用第二个再画两张"), new String[]{"用#2", "画两张"})
                : Arrays.toString(Bot.splitNumberedFollowUp("用第二个再画两张"));
        assert Arrays.equals(Bot.splitNumberedFollowUp("#2 接着出图"), new String[]{"#2", "出图"})
                : Arrays.toString(Bot.splitNumberedFollowUp("#2 接着出图"));
        // 纯指代没有后续 → null（保持原来的单步行为，绝不能把 .gen 硬塞进去）。
        assert Bot.splitNumberedFollowUp("选择第二个") == null : "纯指代不该被切出 tail";
        assert Bot.splitNumberedFollowUp("先不要生成") == null : "没有编号的否定句不受影响";
        // 带引用前缀时只看「我这条消息」之后的正文。
        assert Arrays.equals(Bot.splitNumberedFollowUp("[引用] 旧消息\n" + Bot.REQUEST_MARK + "选择第二个然后生成"),
                new String[]{"选择#2", "生成"}) : "引用里的旧内容不参与切分";

        // ② 拼链：只有 tail 是纯出图动作时才成立。
        ChatActions.Plan chain = Bot.numberedFollowUpPlan(CHOICES, "选择第二个然后生成");
        assert chain != null && chain.commands().equals(List.of(".style load #2", ".gen"))
                : "「选择第二个然后生成」应为两步：" + chain;
        assert chain.reply().startsWith("好，就按你刚看过的列表来：水彩") : chain.reply();
        assert chain.reply().contains("出图") : chain.reply();
        ChatActions.Plan two = Bot.numberedFollowUpPlan(CHOICES, "用第二个再画两张");
        assert two != null && two.commands().equals(List.of(".style load #2", ".gen 2"))
                : "「用第二个再画两张」应带张数：" + two;
        // 纯指代不拼链，仍由 resolveShownSelection 单步处理。
        assert Bot.numberedFollowUpPlan(CHOICES, "选择第二个") == null : "纯指代保持单步";
        // 否定优先：「先不要生成」不能拼出 .gen（pureGenerationPlan 自带 negatesGeneration 判断）。
        assert Bot.numberedFollowUpPlan(CHOICES, "选择第二个先不要生成") == null : "否定要退回原路径";
        // 名字对不上（"第二个小鸟"）：tail 不是出图动作 → 不拼链，交给单步路径给澄清。
        assert Bot.numberedFollowUpPlan(CHOICES, "第二个小鸟") == null : "名字对不上时不拼链";
        Bot.ShownSelection mismatch = Bot.resolveShownSelection(CHOICES, "第二个小鸟");
        assert mismatch != null && mismatch.clarification() && mismatch.label().contains("编号对不上")
                : "名字对不上仍要澄清：" + mismatch;

        // ③ 端到端（ChatService）：这种句子由程序判定，一次模型都不调，指令为 载入 → 出图。
        Path root = Files.createTempDirectory(Path.of(System.getProperty("bot.test.work", "work")), "numbered-followup-");
        Json.atomicWrite(root.resolve("config.json"), new JsonObject());
        Settings settings = new Settings(root);
        AtomicInteger planned = new AtomicInteger();
        List<String> replies = new CopyOnWriteArrayList<>();
        List<List<String>> executed = new CopyOnWriteArrayList<>();
        try (ChatService service = new ChatService(settings,
                (event, segments) -> { replies.add(Bot.messageText(segments)); return CompletableFuture.completedFuture(null); },
                (personality, history, message, choices, speaker) -> {
                    planned.incrementAndGet();
                    return new ChatActions.Plan("正常聊天回复", List.of(), "", 100);
                },
                (event, commands, choices) -> executed.add(List.copyOf(commands)),
                event -> CHOICES, System::nanoTime)) {
            JsonObject user = privateEvent("carol");
            service.accept(user, "选择第二个然后生成"); idle(service);
            assert planned.get() == 0 : "这种句子由程序判定，不该再问模型";
            assert executed.equals(List.of(List.of(".style load #2", ".gen"))) : "载入 #2 之后再出图：" + executed;
            assert replies.get(0).contains("好，就按你刚看过的列表来：水彩") : replies.get(0);
            assert !replies.get(0).contains("编号对不上") : "不该出现「编号对不上」的澄清：" + replies.get(0);

            service.accept(user, "用第二个再画两张"); idle(service);
            assert executed.get(1).equals(List.of(".style load #2", ".gen 2")) : "带张数：" + executed;

            service.accept(user, "选择第二个"); idle(service);
            assert executed.get(2).equals(List.of(".style load #2")) : "纯指代仍是单步：" + executed;

            // 名字对不上：走澄清，不执行任何指令，也不问模型。
            int before = executed.size();
            service.accept(user, "第二个小鸟"); idle(service);
            assert executed.size() == before : "澄清不该执行指令：" + executed;
            assert replies.get(replies.size() - 1).contains("编号对不上") : replies.get(replies.size() - 1);
            assert planned.get() == 0 : "澄清同样由程序给出";
        }

        // ④ 网页通道（/api/chat 走的 webChat）与 QQ 同源：先登记列表，再发同一句话。
        try (var f = new GenerationPresetTest.Fixture()) {
            JsonObject webEvent = f.bot.webEvent("2", "style list");
            f.bot.numbered(webEvent, "style", List.of("胶片感", "水彩"));
            JsonObject web = f.bot.webChat("2", "选择第二个然后生成", new JsonArray(), false);
            assert web.getAsJsonArray("commands").toString().equals("[\".style load #2\",\".gen\"]")
                    : "网页侧也要拼成两步：" + web;
            assert web.get("reply").getAsString().contains("好，就按你刚看过的列表来：水彩") : web;
            JsonObject plain = f.bot.webChat("2", "选择第二个", new JsonArray(), false);
            assert plain.getAsJsonArray("commands").toString().equals("[\".style load #2\"]")
                    : "网页侧的纯指代仍是单步：" + plain;
        }
        System.out.println("NumberedFollowUpTest PASS: 「编号 + 然后生成」切成 载入→出图 两步（QQ + 网页），纯指代/否定/名字不符保持原行为");
    }
}

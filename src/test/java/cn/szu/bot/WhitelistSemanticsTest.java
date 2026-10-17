package cn.szu.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import cn.szu.bot.sd.SdClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 「白名单语义」的端到端回归（Phase 1 第 1 项唯一改动面：白名单语义 + {@code .whitelist} 指令）。
 *
 * <p>正确语义（需求表）：
 * <table border="1">
 *   <caption>白名单只管公共指令</caption>
 *   <tr><th>白名单</th><th>聊天</th><th>公共指令</th><th>管理指令</th></tr>
 *   <tr><td>关闭</td><td>所有人可以</td><td>所有人可以</td><td>仍按 owner/admin 校验</td></tr>
 *   <tr><td>开启</td><td><b>所有人仍然可以聊天</b></td><td>只有白名单成员</td><td>仍按 owner/admin 校验</td></tr>
 * </table>
 *
 * <p>要把改造前的四个 bug 钉住：①白名单判定在聊天/指令分流**之前**，私聊里不在 {@code allowed_user_ids} 的人
 * 连普通聊天都被丢；②{@code contains(..., emptyAllows=true)} 让"空数组＝全放行"，白名单门空转；③owner 没有豁免、
 * 也没有任何 {@code .whitelist} 指令；④{@code allowed_user_ids} 一非空，网页控制台与网页聊天全报"无操作权限"。
 *
 * <h2>隔离（用户第 14 节）</h2>
 * <ul>
 *   <li>根目录：{@code -Dbot.test.work=<run 根>\work} 下再开 {@code case-*} 临时目录
 *       （{@link Files#createTempDirectory}），不碰生产 {@code config.json} 与 {@code data/**}；</li>
 *   <li>桩：SD 与聊天模型都是 {@code 127.0.0.1:0} 的 loopback HTTP（端口由操作系统分配），
 *       <b>不连 8787/3001/7860/7861</b>，不起 WebUI、不连 OneBot；</li>
 *   <li>不杀任何进程、不改任何已有文件；结束时带重试删掉自己的临时根。</li>
 * </ul>
 *
 * <p>输出里的号码一律是假号（{@code 1000x} / {@code 300xx} / {@code 700xx}），打印前一律经
 * {@link Bot#maskedId} 掩码。
 */
public final class WhitelistSemanticsTest {
    /** 场景（断言）计数：9 个场景对应需求 1–9 条验证项；第 10 条是 git diff，不在测试里。 */
    private static int assertions;

    // 全部是假号：源码里不出现任何真实 QQ 号。
    private static final String OWNER = "10001";
    private static final String SELF = "10000";
    /** 一个与任何会话都无关的群号：用来证明"群不在名单里也不影响聊天"。 */
    private static final String GROUP_OTHER = "79999";

    public static void main(String[] args) throws Exception {
        System.out.println("WhitelistSemanticsTest（隔离根：" + mask(root()) + "）");
        try (Fixture f = new Fixture()) {
            whitelistOff(f);
            whitelistOn(f);
            ownerExempt(f);
            chatPlanCannotEscape(f);
            webConsoleUnaffected(f);
            whitelistCommand(f);
            persists(f);
            noCollateralDamage(f);
            masked(f);
        }
        System.out.println("WhitelistSemanticsTest: " + assertions + " assertions passed（9 个场景）："
                + "①关闭时聊天/公共指令放行、管理指令仍按 owner；②开启时聊天仍放行而非成员指令被拒；"
                + "③owner 免写名单；④聊天计划里的 .gen 不越权；⑤网页事件不受白名单限制；"
                + "⑥.whitelist toggle/list/add/remove（仅 owner、编号只用于查看、remove 用号码）；"
                + "⑦开关与成员落盘；⑧关闭时 .help/.mode 行为不变；⑨输出号码全部掩码。");
    }

    // ------------------------------------------------------------------ ① 白名单关闭

    /** ① 关闭：群聊聊天 ✅、私聊聊天 ✅、公共指令 ✅、管理指令仍按 owner（非 owner 被拒）。 */
    private static void whitelistOff(Fixture f) throws Exception {
        // 故意把 allowed_group_ids 填成"别的群"、allowed_user_ids 留空：改造前这套配置会把下面所有消息都丢掉。
        f.configure(false, new String[]{GROUP_OTHER}, new String[0]);
        Reasons r = new Reasons();

        int before = f.recorder.total();
        f.bot.accept(f.groupAtEvent("70001", "30001", "小鸟，在吗"));
        f.awaitCount(before + 1, 8000);
        String groupChat = f.recorder.last();
        r.ok(groupChat != null && !groupChat.contains("白名单"), "群聊普通聊天被丢掉了");

        before = f.recorder.total();
        f.bot.accept(f.privateEvent("30002", "今天有点累"));
        f.awaitCount(before + 1, 8000);
        String privateChat = f.recorder.last();
        r.ok(privateChat != null && !privateChat.contains("白名单"), "私聊普通聊天被丢掉了");

        before = f.recorder.total();
        f.bot.accept(f.privateEvent("30003", "/imgcnt"));
        f.awaitCount(before + 1, 8000);
        String publicCommand = f.recorder.last();
        r.ok(publicCommand != null && publicCommand.contains("图片上限"), "公共指令没有正常执行");

        before = f.recorder.total();
        f.bot.accept(f.groupEvent("70001", "30004", "/admin list"));
        f.awaitCount(before + 1, 8000);
        String admin = f.recorder.last();
        r.ok(admin != null && admin.contains("仅 owner 或 admin"), "非 owner 的管理指令没有被拒");

        check("① 白名单关闭：聊天/公共指令放行、管理指令仍按 owner", r);
    }

    // ------------------------------------------------------------------ ② 白名单开启

    /** ② 开启：聊天仍 ✅（群+私）；非白名单成员执行公共指令 → 被拒且回执说明；白名单成员执行 ✅。 */
    private static void whitelistOn(Fixture f) throws Exception {
        f.configure(true, new String[0], new String[0]);
        Reasons r = new Reasons();

        int before = f.recorder.total();
        f.bot.accept(f.groupAtEvent("70002", "30005", "小鸟，在吗"));
        f.awaitCount(before + 1, 8000);
        String groupChat = f.recorder.last();
        r.ok(groupChat != null && !groupChat.contains("白名单"), "开启后群聊普通聊天被丢了");

        before = f.recorder.total();
        f.bot.accept(f.privateEvent("30006", "今天有点累"));
        f.awaitCount(before + 1, 8000);
        String privateChat = f.recorder.last();
        r.ok(privateChat != null && !privateChat.contains("白名单"), "开启后私聊普通聊天被丢了");

        before = f.recorder.total();
        f.bot.accept(f.privateEvent("30007", "/imgcnt 7"));
        f.awaitCount(before + 1, 8000);
        String denied = f.recorder.last();
        r.ok(denied != null && denied.contains("白名单") && !denied.contains("图片上限"),
                "非白名单成员的公共指令没有被拒，或回执没说清原因");
        r.ok(f.settings.imageCount() == 300, "被拒的指令仍然落地了（imgcnt 变了）");

        // 名单里只有 30008：他能执行。
        f.configure(true, new String[0], new String[]{"30008"});
        before = f.recorder.total();
        f.bot.accept(f.privateEvent("30008", "/imgcnt"));
        f.awaitCount(before + 1, 8000);
        String member = f.recorder.last();
        r.ok(member != null && member.contains("图片上限"), "白名单成员执行公共指令被误拒");

        check("② 白名单开启：聊天仍放行、非成员指令被拒且有说明、成员指令可用", r);
    }

    // ------------------------------------------------------------------ ③ owner 豁免

    /** ③ owner 豁免：开启且 owner 不在名单 → 仍可执行指令（不需要把自己写进名单）。 */
    private static void ownerExempt(Fixture f) throws Exception {
        f.configure(true, new String[0], new String[]{"30008"});   // 名单里没有 owner
        Reasons r = new Reasons();
        r.ok(!f.settings.whitelistUsers().contains(OWNER), "owner 被写进了名单，场景前提不成立");

        int before = f.recorder.total();
        f.bot.accept(f.privateEvent(OWNER, "/imgcnt"));
        f.awaitCount(before + 1, 8000);
        String receipt = f.recorder.last();
        r.ok(receipt != null && receipt.contains("图片上限"), "owner 不在名单时被白名单拦住");

        check("③ owner 隐式豁免（开启且 owner 不在名单）", r);
    }

    // ------------------------------------------------------------------ ④ 聊天计划里的指令

    /** ④ 聊天计划命令：开启 + 非白名单成员发起聊天、计划里带 .gen → 被拒（不越权，SD 一次都没被调用）。 */
    private static void chatPlanCannotEscape(Fixture f) throws Exception {
        f.configure(true, new String[0], new String[]{"30008"});   // 发起人 30010 不在名单
        Reasons r = new Reasons();
        int sdBefore = f.sdGenerations.get();
        f.plan.set(PLAN_WITH_GEN);
        int before = f.recorder.total();
        f.bot.accept(f.privateEvent("30010", "帮我画一张画"));
        // 聊天回复照发（聊天本身不受白名单影响），随后是"聊天指令未全部完成：白名单…"那一句。
        r.ok(f.awaitContains("白名单", before, 15000), "计划里的指令没有被白名单拒绝：" + f.recorder.last());
        r.ok(f.recorder.total() > before, "聊天回复也没发出来（聊天被误伤）");
        // 生成一步都没跑：SdClient 根本没有收到 txt2img 请求。
        Thread.sleep(300);
        r.ok(sdBefore == f.sdGenerations.get(), "被拒的 .gen 仍然发起了生成");
        f.plan.set(PLAN_CHAT);

        check("④ 聊天计划里的 .gen 不越权", r);
    }

    // ------------------------------------------------------------------ ⑤ 网页事件

    /** ⑤ 网页事件：allowed_user_ids 非空时，网页合成事件的指令与聊天都不被拦（回归"控制台报无操作权限"）。 */
    private static void webConsoleUnaffected(Fixture f) throws Exception {
        f.configure(true, new String[0], new String[]{"30011"});   // 名单非空，且不含网页身份
        Reasons r = new Reasons();
        r.ok(Bot.fromWebConsole(f.bot.webEvent("web", "/imgcnt")), "网页事件没有带上 webui 判据（fromWebConsole=false）");

        int before = f.recorder.total();
        f.bot.accept(f.bot.webEvent("web", "/imgcnt"));
        f.awaitCount(before + 1, 8000);
        String command = f.recorder.last();
        r.ok(command != null && command.contains("图片上限"), "网页指令被白名单拦住了");

        before = f.recorder.total();
        f.bot.accept(f.bot.webEvent("web", "在吗"));
        f.awaitCount(before + 1, 8000);
        String chat = f.recorder.last();
        r.ok(chat != null && !chat.contains("白名单"), "网页聊天被白名单拦住了");

        check("⑤ 网页控制台/网页聊天不受白名单限制", r);
    }

    // ------------------------------------------------------------------ ⑥ .whitelist 指令

    /** ⑥ toggle/list/add/remove：非 owner 全被拒；owner 可用；list 编号正确；remove 必须用号码。 */
    private static void whitelistCommand(Fixture f) throws Exception {
        f.configure(false, new String[0], new String[]{"30011"});   // 先关闭：非 owner 撞到的是 owner 校验
        Reasons r = new Reasons();

        for (String command : new String[]{"/whitelist toggle", "/whitelist list", "/whitelist add 30013",
                "/whitelist remove 30013"}) {
            int before = f.recorder.total();
            f.bot.accept(f.privateEvent("30012", command));   // 非 owner
            f.awaitCount(before + 1, 8000);
            String receipt = f.recorder.last();
            r.ok(receipt != null && receipt.contains("仅 owner 可用"), "非 owner 的「" + command + "」没有被拒");
        }
        r.ok(!f.settings.commandWhitelistEnabled(), "非 owner 的 toggle 竟然改了开关");
        r.ok(f.settings.whitelistUsers().equals(List.of("30011")), "非 owner 的 add/remove 竟然改了名单");

        // owner：toggle 打开
        int before = f.recorder.total();
        f.bot.accept(f.privateEvent(OWNER, "/whitelist toggle"));
        f.awaitCount(before + 1, 8000);
        String toggled = f.recorder.last();
        r.ok(toggled != null && toggled.contains("已开启") && f.settings.commandWhitelistEnabled(),
                "owner 的 toggle 没有生效或没有回执");

        // owner：add（编号 = 2）
        before = f.recorder.total();
        f.bot.accept(f.privateEvent(OWNER, "/whitelist add 30014"));
        f.awaitCount(before + 1, 8000);
        String added = f.recorder.last();
        r.ok(added != null && added.contains("已加入白名单：30014") && added.contains("2. 用户 30014"),
                "owner 的 add 没有生效，或 list 里的编号不是 2");

        // owner：重复添加要明确说明
        before = f.recorder.total();
        f.bot.accept(f.privateEvent(OWNER, "/whitelist add 30014"));
        f.awaitCount(before + 1, 8000);
        String duplicate = f.recorder.last();
        r.ok(duplicate != null && duplicate.contains("已在白名单中"), "重复添加没有明确说明");

        // owner：list 显示开关状态 + 编号（1 = 30011、2 = 30014）
        before = f.recorder.total();
        f.bot.accept(f.privateEvent(OWNER, "/whitelist list"));
        f.awaitCount(before + 1, 8000);
        String listed = f.recorder.last();
        r.ok(listed != null && listed.contains("已开启")
                && listed.contains("1. 用户 30011") && listed.contains("2. 用户 30014"), "list 的开关状态或编号不正确");

        // owner：remove 用号码（不是编号）
        before = f.recorder.total();
        f.bot.accept(f.privateEvent(OWNER, "/whitelist remove 30014"));
        f.awaitCount(before + 1, 8000);
        String removed = f.recorder.last();
        r.ok(removed != null && removed.contains("已移出白名单：30014")
                && !f.settings.whitelistUsers().contains("30014"), "remove 用号码没有生效");

        // owner：移除不存在的号码要明确说明
        before = f.recorder.total();
        f.bot.accept(f.privateEvent(OWNER, "/whitelist remove 39999"));
        f.awaitCount(before + 1, 8000);
        String missing = f.recorder.last();
        r.ok(missing != null && missing.contains("不在白名单中"), "移除不存在的号码没有明确说明");

        // owner：非法号码给用法
        before = f.recorder.total();
        f.bot.accept(f.privateEvent(OWNER, "/whitelist add 不是号码"));
        f.awaitCount(before + 1, 8000);
        String invalid = f.recorder.last();
        r.ok(invalid != null && invalid.contains("用法"), "非法号码没有给用法提示");

        check("⑥ .whitelist toggle/list/add/remove", r);
    }

    // ------------------------------------------------------------------ ⑦ 持久化

    /** ⑦ 持久化：改完写盘、重新构造 Settings 后状态与成员仍在；缺键时是"关闭 + 空名单"。 */
    private static void persists(Fixture f) throws Exception {
        Reasons r = new Reasons();
        Settings reloaded = new Settings(f.root);
        r.ok(reloaded.commandWhitelistEnabled(), "重新构造 Settings 后开关丢了");
        r.ok(reloaded.whitelistUsers().equals(List.of("30011")),
                "重新构造 Settings 后成员不对：" + mask(reloaded.whitelistUsers()));

        // "不新增必需键"：只有 owner 的最小配置读出来必须是关闭 + 空名单。
        Path bare = Files.createTempDirectory(f.root, "bare-");
        Files.createDirectories(bare.resolve("data"));
        JsonObject minimal = new JsonObject();
        minimal.addProperty("owner_user_id", OWNER);
        Json.atomicWrite(bare.resolve("config.json"), minimal);
        Settings plain = new Settings(bare);
        r.ok(!plain.commandWhitelistEnabled() && plain.whitelistUsers().isEmpty() && plain.whitelistGroups().isEmpty(),
                "缺 whitelist 键时不是「关闭 + 空名单」");

        check("⑦ 开关与成员落盘（重新构造 Settings 仍在；缺键＝关闭+空名单）", r);
    }

    // ------------------------------------------------------------------ ⑧ 不误伤

    /** ⑧ 不误伤：白名单关闭时 {@code .help} / {@code .mode} 等既有指令行为不变。 */
    private static void noCollateralDamage(Fixture f) throws Exception {
        f.configure(false, new String[0], new String[0]);
        Reasons r = new Reasons();

        int before = f.recorder.total();
        f.bot.accept(f.privateEvent("30015", "/help"));
        f.awaitCount(before + 1, 8000);
        String help = f.recorder.last();
        r.ok(help != null && help.contains("Pixiko"), ".help 没有回复");
        r.ok(help != null && help.contains(".whitelist"), ".help 里没有 .whitelist 那一行");

        before = f.recorder.total();
        f.bot.accept(f.privateEvent("30016", "/mode"));
        f.awaitCount(before + 1, 8000);
        String mode = f.recorder.last();
        r.ok(mode != null && mode.contains("回执档位"), ".mode 没有回复");
        r.ok(new Settings(f.root).qqMode(SELF + ":private:30016") == Settings.QqMode.DEBUG, ".mode 的默认档位被改了");

        check("⑧ 关闭时 .help/.mode 行为不变", r);
    }

    // ------------------------------------------------------------------ ⑨ 掩码

    /** ⑨ 不打印号码：掩码后就只剩首尾，够分辨、抄不走。 */
    private static void masked(Fixture f) {
        Reasons r = new Reasons();
        r.same("1***890", Bot.maskedId("1234567890"), "掩码形状不对");
        r.same("***", Bot.maskedId("1234"), "短号码没有整体掩码");
        r.same("（无）", Bot.maskedId(""), "空号码没有兜底");
        r.same("1***890", mask(List.of("1234567890")), "名单打印没有掩码");
        check("⑨ 号码掩码（maskedId + 名单打印）", r);
    }

    // ------------------------------------------------------------------ 断言

    /** 一个场景里收集到的失败原因；{} 为空 = 通过。 */
    private static final class Reasons {
        private final StringBuilder text = new StringBuilder();
        boolean ok(boolean condition, String description) {
            if (!condition) text.append("；").append(description);
            return condition;
        }
        boolean same(Object expected, Object actual, String description) {
            return ok(Objects.equals(expected, actual),
                    description + "（expected=" + expected + ", actual=" + actual + "）");
        }
    }

    private static void check(String label, Reasons reasons) {
        assertions++;
        if (reasons.text.length() > 0) throw new AssertionError(label + "：" + reasons.text);
        System.out.println("  PASS " + label);
    }

    /** 名单打印成掩码串（测试自己的输出也不许出现完整号码）。 */
    private static String mask(List<String> ids) {
        StringBuilder text = new StringBuilder();
        for (String id : ids) { if (text.length() > 0) text.append("、"); text.append(Bot.maskedId(id)); }
        return text.toString();
    }

    private static String mask(Path path) {
        return path == null ? "（无）" : path.toString();
    }

    private static Path root() {
        return Path.of(System.getProperty("bot.test.work", "work")).toAbsolutePath();
    }

    // ------------------------------------------------------------------ 桩

    /** 记录型 Sender：每一次 send / sendMap / sendRecord 的正文都留在这里。 */
    private static final class Recorder implements Bot.Sender {
        final List<String> texts = new CopyOnWriteArrayList<>();
        private final AtomicInteger sends = new AtomicInteger(), maps = new AtomicInteger(), records = new AtomicInteger();

        @Override public CompletableFuture<Void> send(JsonObject event, JsonArray segments) {
            sends.incrementAndGet();
            texts.add(Bot.messageText(segments));
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> sendMap(JsonObject event, JsonArray segments) {
            maps.incrementAndGet();
            texts.add(Bot.messageText(segments));
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> sendRecord(JsonObject event, List<JsonArray> messages) {
            records.incrementAndGet();
            if (!messages.isEmpty()) texts.add(Bot.messageText(messages.get(0)));
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<JsonElement> callApi(String action, JsonObject params) {
            return CompletableFuture.failedFuture(new IOException("测试传输层不支持 " + action));
        }
        int total() { return sends.get() + maps.get() + records.get(); }
        String last() { return texts.isEmpty() ? null : texts.get(texts.size() - 1); }
    }

    /** 聊天模型的两种固定计划：纯聊天 / 带一条 .gen。 */
    private static final String PLAN_CHAT = plan("嗯，在的哦。", new String[0]);
    private static final String PLAN_WITH_GEN = plan("好，这就给你画。", new String[]{".gen 1"});

    private static String plan(String reply, String[] commands) {
        JsonObject plan = new JsonObject();
        plan.addProperty("reply", reply);
        plan.addProperty("execute", commands.length > 0);
        JsonArray list = new JsonArray();
        for (String command : commands) list.add(command);
        plan.add("commands", list);
        plan.addProperty("search_query", "");
        plan.addProperty("interest", 90);
        plan.addProperty("join", true);
        plan.addProperty("join_reason", "对方在跟我说话");
        JsonObject message = new JsonObject();
        message.addProperty("content", plan.toString());
        JsonObject choice = new JsonObject();
        choice.addProperty("finish_reason", "stop");
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return response.toString();
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final Recorder recorder = new Recorder();
        final AtomicReference<String> plan = new AtomicReference<>(PLAN_CHAT);
        /** 被真调过的 txt2img 次数：用来断言"被拒的 .gen 一次都没跑"。 */
        final AtomicInteger sdGenerations = new AtomicInteger();
        final Settings settings;
        final Bot bot;
        private final HttpServer server;
        private final AtomicInteger ids = new AtomicInteger();

        Fixture() throws Exception {
            // 隔离：只在 -Dbot.test.work（build.ps1 -Test 注入的本次 run 目录）下开自己的临时根。
            Path work = root();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            Files.createDirectories(root.resolve("data"));

            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // SD：桥接不可用（等于"SD 没在跑"），只数 txt2img 被调用了几次。
            server.createContext("/internal/ping", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
            server.createContext("/pixiko-bridge/v1/prompts", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
            server.createContext("/sdapi/v1/txt2img", exchange -> {
                sdGenerations.incrementAndGet();
                respond(exchange, 200, "{\"images\":[],\"info\":\"{}\"}");
            });
            server.createContext("/chat/completions", exchange -> {
                try { exchange.getRequestBody().readAllBytes(); } catch (IOException ignored) { /* 只用作桩 */ }
                respond(exchange, 200, plan.get());
            });
            server.start();
            int port = server.getAddress().getPort();

            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + port);
            JsonObject chatApi = new JsonObject();
            chatApi.addProperty("api_base", "http://127.0.0.1:" + port);
            chatApi.addProperty("model", "test-model");
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            config.add("chat_api", chatApi);
            config.addProperty("owner_user_id", OWNER);
            config.addProperty("gen_auto_get", false);
            Json.atomicWrite(root.resolve("config.json"), config);
            Files.writeString(root.resolve("data/deepseek-chat-api-key.txt"), "test-key", StandardCharsets.UTF_8);

            settings = new Settings(root);
            bot = new Bot(settings, new SdClient(root, sd), recorder);
        }

        /** 直接改真实配置键（与 {@code .whitelist} / 手改 config.json 落的是同一份文件）。 */
        void configure(boolean enabled, String[] groups, String[] users) throws IOException {
            settings.setCommandWhitelistEnabled(enabled);
            settings.rootSetting("allowed_group_ids", array(groups));
            settings.rootSetting("allowed_user_ids", array(users));
        }

        private static JsonArray array(String[] values) {
            JsonArray array = new JsonArray();
            for (String value : values) array.add(value);
            return array;
        }

        /** 群聊文本消息。 */
        JsonObject groupEvent(String group, String user, String text) {
            JsonObject event = envelope("group", group, user);
            event.add("message", Maps.text(text));
            return event;
        }

        /** 群聊消息，正文前带一个指向机器人自己的 at 段（群里必须点名才会回复）。 */
        JsonObject groupAtEvent(String group, String user, String text) {
            JsonObject event = envelope("group", group, user);
            JsonArray message = new JsonArray();
            JsonObject at = new JsonObject();
            at.addProperty("type", "at");
            JsonObject data = new JsonObject();
            data.addProperty("qq", SELF);
            at.add("data", data);
            message.add(at);
            for (JsonElement segment : Maps.text(text)) message.add(segment);
            event.add("message", message);
            return event;
        }

        JsonObject privateEvent(String user, String text) {
            JsonObject event = envelope("private", "", user);
            event.add("message", Maps.text(text));
            return event;
        }

        private JsonObject envelope(String type, String group, String user) {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", type);
            event.addProperty("self_id", SELF);
            if (!group.isEmpty()) event.addProperty("group_id", group);
            event.addProperty("user_id", user);
            event.addProperty("message_id", ids.incrementAndGet());
            return event;
        }

        /** 等出站条数达到 expected（聊天回复与指令回执走不同的异步链路）。 */
        void awaitCount(int expected, long millis) throws Exception {
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                if (recorder.total() >= expected) return;
                Thread.sleep(10);
            }
            throw new AssertionError("等待第 " + expected + " 条出站消息超时（实际 " + recorder.total() + " 条）");
        }

        /** 等第 since 条之后出现含 needle 的一条（链路可能先发聊天回复、再发失败说明）。 */
        boolean awaitContains(String needle, int since, long millis) throws Exception {
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                List<String> texts = recorder.texts;
                for (int index = Math.min(since, texts.size()); index < texts.size(); index++)
                    if (texts.get(index).contains(needle)) return true;
                Thread.sleep(10);
            }
            return false;
        }

        public void close() throws Exception {
            bot.close();
            server.stop(0);
            TestCleanup.awaitQuiet(root, 5000);
            TestCleanup.deleteQuietly(root);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}

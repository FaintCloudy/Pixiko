package cn.szu.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import cn.szu.bot.web.ChatLogStore;

/**
 * 对话正文落盘（{@link ChatLogStore}）：往返、坏文件、逐条规范化（含丢空条目）、
 * 缺文件静默而坏数据才 warn、200 条与 512KB 裁剪、scope 净化（不许逃出 data/webui）、
 * UTF-8 无 BOM 与纯 LF、并发写不半截。
 * 只在 {@code bot.test.work} 下的临时根里写文件，跑完删掉。
 */
public final class ChatLogStoreTest {
    static int checks = 0;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError("FAIL: " + what); }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work")).toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "chatlog-");
        try {
            roundTrip(root);
            brokenFiles(root);
            normalization(root);
            textClip(root);
            entryCap(root);
            byteCap(root);
            scopeSanitize(root);
            fileEncoding(root);
            concurrency(root);
            nullAndJunk(root);
            warnObservation(root);
            appendIdempotent(root);
            appendReceiptFold(root);
            appendVersusPush(root);
        } finally {
            TestCleanup.deleteQuietly(root);
        }
        check(!Files.exists(root), "临时根已清理干净：" + root);
        System.out.println("ChatLogStoreTest: " + checks + " assertions passed: 往返与深拷贝、坏文件、逐条规范化（含丢空条目）、"
                + "缺文件静默与坏数据才 warn、超长文字截断、只留最后 200 条、512KB 裁剪、scope 净化不逃逸、"
                + "无 BOM 与纯 LF、8 线程并发不半截、空输入不抛、"
                + "服务端追加按「回执号 + 条内序号」幂等、多步回执逐条对齐且图并进上一条正文、"
                + "页面整组 push 与服务端 append 交替不丢不重。");
    }

    // ---------------------------------------------------------------- 12) 服务端追加：幂等

    /** 同一条回执消息 append 两次 → 日志里只有 1 条（断言条数与内容）。 */
    static void appendIdempotent(Path root) throws Exception {
        ChatLogStore store = new ChatLogStore(root);
        List<ChatLogStore.Append> items = List.of(new ChatLogStore.Append("quest:7:1", "已加入生成队列", List.of()));
        check(store.append("idem", items) == 1, "第一次 append 真的写了 1 条");
        check(store.load("idem").size() == 1, "第一次 append 之后是 1 条");
        check(store.append("idem", items) == 0, "同一条再 append 一次：不写（返回 0）");
        check(store.load("idem").size() == 1, "同一条 append 两次仍然只有 1 条：" + store.load("idem"));
        check("已加入生成队列".equals(store.load("idem").get(0).getAsJsonObject().get("text").getAsString()),
                "留下的正是那一条正文");
        check("bot".equals(store.load("idem").get(0).getAsJsonObject().get("role").getAsString()), "追加条目 role=bot");

        // 换一个 store 实例（等价于机器人重启）：账本在磁盘上，照样不重复
        ChatLogStore restarted = new ChatLogStore(root);
        check(restarted.append("idem", items) == 0, "重启后同一条 append 依旧不写（账本持久）");
        check(restarted.load("idem").size() == 1, "重启后仍然只有 1 条");

        // 同一批里重复的键也算一次
        List<ChatLogStore.Append> twice = List.of(
                new ChatLogStore.Append("quest:8:1", "结算完成", List.of()),
                new ChatLogStore.Append("quest:8:1", "结算完成", List.of()));
        check(store.append("idem", twice) == 1, "同一批里同一个键只落 1 条");
        check(store.load("idem").size() == 2, "现在一共 2 条：" + store.load("idem").size());

        // 图片后到：同一条正文就地补成带图的版本，**绝不新开一条**
        List<ChatLogStore.Append> withImage = List.of(
                new ChatLogStore.Append("quest:8:1", "结算完成", List.of("data/generated/a.png")));
        check(store.append("idem", withImage) == 1, "图后到：这一条被就地更新（算 1 次改动）");
        JsonArray after = store.load("idem");
        check(after.size() == 2, "图后到没有多出气泡：" + after.size());
        JsonObject tail = after.get(1).getAsJsonObject();
        check(tail.getAsJsonArray("images").size() == 1
                        && "data/generated/a.png".equals(tail.getAsJsonArray("images").get(0).getAsString()),
                "图挂在同一条正文上：" + tail);
        check(store.append("idem", withImage) == 0 && store.load("idem").size() == 2, "带图版本再 append 一次也幂等");

        // 空条目（既没文字也没图）不落
        check(store.append("idem", List.of(new ChatLogStore.Append("quest:9:1", "", List.of()))) == 0,
                "空条目不算一次追加");
        check(store.load("idem").size() == 2, "空条目不落盘");

        // 顺带钉住：账本是**旁挂文件**，正文文件的结构一个字都没变
        Path file = ChatLogStore.fileFor(root, "idem");
        JsonObject state = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        check(state.keySet().toString().equals("[version, scope, savedAtMillis, entries]"),
                "正文文件结构不变：" + state.keySet());
        check(Files.isRegularFile(ChatLogStore.ledgerFileFor(root, "idem")), "幂等账本落在旁挂文件上");
    }

    // ---------------------------------------------------------------- 13) 多步回执的分组

    /** 多步回执（6 条正文 + 1 张图，图在第 5 条之后）→ 每条正文各一条、图挂在第 5 条上。 */
    static void appendReceiptFold(Path root) {
        List<JsonArray> messages = new ArrayList<>();
        for (int step = 1; step <= 4; step++) messages.add(textGroup("第 " + step + " 步完成"));
        messages.add(textGroup("开始生成图片"));
        messages.add(imageGroup("data/generated/pic.png"));
        messages.add(textGroup("本次领取完成，共 1 张。"));
        List<ChatLogStore.Append> items = ChatLogStore.receipt("31", messages);
        check(items.size() == 6, "6 条正文 + 1 张图 → 6 条条目（图并进第 5 条正文，不新开）：" + items.size());
        check("quest:31:1".equals(items.get(0).key()) && "quest:31:6".equals(items.get(5).key()),
                "幂等键是回执号 + 条内序号：" + items.get(0).key() + " … " + items.get(5).key());
        check(items.get(3).images().isEmpty(), "第 4 条没有图");
        check(items.get(4).images().size() == 1 && "data/generated/pic.png".equals(items.get(4).images().get(0)),
                "图挂在上一条正文（第 5 条）上：" + items.get(4).images());
        check("开始生成图片".equals(items.get(4).text()), "第 5 条正文就是图前面那句");
        check(items.get(5).images().isEmpty(), "第 6 条（图之后的领取）没有图");
        check("本次领取完成，共 1 张。".equals(items.get(5).text()), "第 6 条正文是领取那句话");

        ChatLogStore store = new ChatLogStore(root);
        check(store.append("fold", items) == 6, "6 条各落一次");
        JsonArray saved = store.load("fold");
        check(saved.size() == 6, "正文里 6 条：" + saved.size());
        for (int index = 0; index < 4; index++)
            check(saved.get(index).getAsJsonObject().get("text").getAsString().startsWith("第 " + (index + 1) + " 步"),
                    "前 4 条顺序与内容不变（第 " + (index + 1) + " 条）");
        check(saved.get(4).getAsJsonObject().getAsJsonArray("images").size() == 1,
                "第 5 条带上了那张图：" + saved.get(4));
        check(!saved.get(5).getAsJsonObject().has("images"), "第 6 条没有图（图没有被塞到末尾那条）");
        check(store.append("fold", ChatLogStore.receipt("31", messages)) == 0, "整条回执重推一次：一条都不重复");

        // 纯图回执（一条正文都没有）：图自己占一条，且不会因此多出空气泡
        List<JsonArray> onlyImage = List.of(imageGroup("data/generated/only.png"));
        List<ChatLogStore.Append> lone = ChatLogStore.receipt("32", onlyImage);
        check(lone.size() == 1 && lone.get(0).text().isEmpty() && lone.get(0).images().size() == 1,
                "纯图回执 → 一条只有图的条目：" + lone);
    }

    // ---------------------------------------------------------------- 14) 页面 push 与服务端 append 交替

    /**
     * 并发口径：页面整组 push（{@link #save}）与服务端 append 交替发生 → 不出重复、不丢条目。
     *
     * <p>这里把两种真实形状都跑一遍：
     * ① push 的那份**少了服务端已经 append 的条目**（页面快照旧了）→ 服务端把那条补回来（不丢）；
     * ② push 的那份**已经带了**同样的条目（页面读到了服务端那一份）→ 认账、不重复写第二遍。
     */
    static void appendVersusPush(Path root) {
        ChatLogStore store = new ChatLogStore(root);
        JsonArray userSays = new JsonArray();
        userSays.add(entry("user", "帮我生成一张"));
        store.save("race2", userSays);

        List<ChatLogStore.Append> receipt = List.of(
                new ChatLogStore.Append("quest:41:1", "已加入生成队列", List.of()),
                new ChatLogStore.Append("quest:41:2", "任务完成", List.of("data/generated/x.png")));
        check(store.append("race2", receipt) == 2, "服务端追加 2 条");
        check(store.load("race2").size() == 3, "现在 1 条用户 + 2 条回执：" + store.load("race2").size());

        // ① 页面拿着一份旧快照（只有用户那条）整组 push → 服务端的两条必须找回来
        JsonArray stalePage = new JsonArray();
        stalePage.add(entry("user", "帮我生成一张"));
        store.save("race2", stalePage);
        JsonArray afterPush = store.load("race2");
        check(afterPush.size() == 3, "页面整组 push 之后服务端追加的 2 条被补回来（不丢）：" + afterPush.size());
        check("已加入生成队列".equals(afterPush.get(1).getAsJsonObject().get("text").getAsString()),
                "补回来的是那两条回执，顺序不变");
        check(afterPush.get(2).getAsJsonObject().getAsJsonArray("images").size() == 1,
                "带图那条也原样回来：" + afterPush.get(2));

        // ② 页面这次读到了服务端那一份（整组 push 里已经包含它们）→ 不许再写第二遍
        JsonArray fresh = new JsonArray();
        fresh.add(entry("user", "帮我生成一张"));
        fresh.add(entry("bot", "已加入生成队列"));
        JsonObject withImage = entry("bot", "任务完成");
        JsonArray images = new JsonArray();
        images.add("data/generated/x.png");
        withImage.add("images", images);
        fresh.add(withImage);
        store.save("race2", fresh);
        check(store.load("race2").size() == 3, "页面推的与服务端追的是同一批：不出重复：" + store.load("race2").size());
        check(store.append("race2", receipt) == 0, "之后服务端再 append 同一条：一条都不写");
        check(store.load("race2").size() == 3, "仍然 3 条：" + store.load("race2").size());

        // ③ 页面推的这份**逐字节就是服务端追加过的那两条**（只是少了它自己的一条用户消息）：
        //    服务端认账、一条都不补，也一条都不重复（比对是"完整形状"的，不靠下标、不靠前缀）。
        JsonArray exactPage = new JsonArray();
        exactPage.add(entry("bot", "已加入生成队列"));
        JsonObject exactImage = entry("bot", "任务完成");
        JsonArray exactImages = new JsonArray();
        exactImages.add("data/generated/x.png");
        exactImage.add("images", exactImages);
        exactPage.add(exactImage);
        store.save("race2", exactPage);
        JsonArray cleared = store.load("race2");
        check(cleared.size() == 2, "页面这份与服务端追加的逐字节一致：不补、不重复（2 条）：" + cleared.size());
        check("已加入生成队列".equals(cleared.get(0).getAsJsonObject().get("text").getAsString())
                        && "任务完成".equals(cleared.get(1).getAsJsonObject().get("text").getAsString()),
                "两条顺序照旧：" + cleared);

        // ③b 再推一份"少了服务端追加过的第二条"的（旧快照形状）→ 只把少的那条补回来，不加第二遍
        JsonArray staleAgain = new JsonArray();
        staleAgain.add(entry("user", "帮我生成一张"));
        staleAgain.add(entry("bot", "已加入生成队列"));
        store.save("race2", staleAgain);
        JsonArray restored = store.load("race2");
        check(restored.size() == 3, "少了一条回执：补回它（3 条）：" + restored.size());
        check("任务完成".equals(restored.get(2).getAsJsonObject().get("text").getAsString())
                        && restored.get(2).getAsJsonObject().getAsJsonArray("images").size() == 1,
                "补回来的是带图那条，落在末尾：" + restored.get(2));

        // ④ 交替并发：写线程 push、另一个线程 append，收尾时断言"不丢"
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        final int rounds = 30;
        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (int round = 0; round < rounds; round++) {
                    JsonArray page = new JsonArray();
                    page.add(entry("user", "第 " + round + " 轮"));
                    store.save("race3", page);
                }
            } catch (Throwable error) {
                throw new RuntimeException(error);
            }
        }));
        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (int round = 0; round < rounds; round++) {
                    store.append("race3", List.of(new ChatLogStore.Append("quest:5" + round + ":1", "回执 " + round, List.of())));
                }
            } catch (Throwable error) {
                throw new RuntimeException(error);
            }
        }));
        start.countDown();
        boolean failed = false;
        for (Future<?> future : futures) {
            try { future.get(120, TimeUnit.SECONDS); } catch (Exception error) { failed = true; }
        }
        pool.shutdown();
        check(!failed, "push 与 append 交替 30 轮：两边都没抛");
        JsonArray raced = store.load("race3");
        check(raced.size() > 0 && raced.size() <= 200, "交替之后是一份合法正文（" + raced.size() + " 条）");
        long receipts = 0;
        for (JsonElement node : raced)
            if (node.getAsJsonObject().get("text").getAsString().startsWith("回执 ")) receipts++;
        check(receipts >= 1, "服务端 append 的条目至少留下一条（不因为页面 push 全丢）：" + receipts);
    }

    static JsonArray textGroup(String text) {
        JsonArray group = new JsonArray();
        JsonObject node = new JsonObject();
        node.addProperty("type", "text");
        node.addProperty("text", text);
        group.add(node);
        return group;
    }

    static JsonArray imageGroup(String file) {
        JsonArray group = new JsonArray();
        JsonObject node = new JsonObject();
        node.addProperty("type", "image");
        node.addProperty("file", file);
        group.add(node);
        return group;
    }

    // ---------------------------------------------------------------- 1) 往返

    static void roundTrip(Path root) throws Exception {
        ChatLogStore store = new ChatLogStore(root);
        check(store.cap() == 200, "条目上限常量 200");
        check(store.bytesCap() == 512 * 1024, "字节上限常量 512KB");
        check(store.load("web").size() == 0, "缺文件 → 空数组、不抛");

        JsonArray input = new JsonArray();
        input.add(entry("user", "你好，看看这张图"));
        input.add(imageEntry("bot", "画好了", "data/webui/img/a.png", "data/webui/img/b.jpg"));
        input.add(entry("sys", "指令已发送：.gen 1"));
        store.save("web", input);

        JsonArray loaded = store.load("web");
        check(loaded.size() == 3, "往返条数：" + loaded.size());
        JsonObject first = loaded.get(0).getAsJsonObject();
        check("user".equals(first.get("role").getAsString()), "首条 role");
        check("你好，看看这张图".equals(first.get("text").getAsString()), "首条 text");
        check(!first.has("images"), "无图条目不带 images 字段");
        JsonObject second = loaded.get(1).getAsJsonObject();
        check("bot".equals(second.get("role").getAsString()), "带图条目 role");
        check("画好了".equals(second.get("text").getAsString()), "带图条目 text");
        check(second.keySet().toString().equals("[role, text, images]"), "字段顺序 role,text,images：" + second.keySet());
        JsonArray images = second.getAsJsonArray("images");
        check(images != null && images.size() == 2, "图片数 2");
        check("data/webui/img/a.png".equals(images.get(0).getAsString()), "图片顺序 1");
        check("data/webui/img/b.jpg".equals(images.get(1).getAsString()), "图片顺序 2");
        JsonObject third = loaded.get(2).getAsJsonObject();
        check("sys".equals(third.get("role").getAsString()), "回执条目 role=sys");
        check("指令已发送：.gen 1".equals(third.get("text").getAsString()), "回执条目 text");

        // load 必须是深拷贝：改返回值不能污染下一次 load，也不能改到文件
        loaded.add(entry("user", "偷偷加的"));
        loaded.get(0).getAsJsonObject().addProperty("text", "改过了");
        JsonArray again = store.load("web");
        check(again.size() == 3, "load 是深拷贝：加条目不影响文件");
        check("你好，看看这张图".equals(again.get(0).getAsJsonObject().get("text").getAsString()), "load 是深拷贝：改字段不影响文件");

        Path file = ChatLogStore.fileFor(root, "web");
        check(file.equals(ChatLogStore.fileFor(root, "web")), "fileFor 是纯函数");
        check("web-chat-log.json".equals(file.getFileName().toString()), "文件名后缀 -chat-log.json：" + file.getFileName());
        check(webui(root).equals(file.getParent()), "文件在 data/webui 下");
        check(Files.isRegularFile(file), "文件已落盘");

        JsonObject state = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        check(state.get("version").getAsInt() == 1, "version=1");
        check("web".equals(state.get("scope").getAsString()), "scope 回写");
        check(state.get("savedAtMillis").getAsLong() > 0, "savedAtMillis 是毫秒时间戳");
        check(state.getAsJsonArray("entries").size() == 3, "落盘 entries 条数");
        store.save("web", new JsonArray());
        check(store.load("web").size() == 0, "存空数组＝清空这段对话");
    }

    // ---------------------------------------------------------------- 2) 坏文件

    static void brokenFiles(Path root) throws Exception {
        ChatLogStore store = new ChatLogStore(root);
        Files.createDirectories(webui(root));
        Path file = ChatLogStore.fileFor(root, "broken");
        Files.writeString(file, "{这不是 JSON", StandardCharsets.UTF_8);
        check(store.load("broken").size() == 0, "坏 JSON → 空数组、不抛");
        Files.writeString(file, "", StandardCharsets.UTF_8);
        check(store.load("broken").size() == 0, "空文件 → 空数组");
        Files.writeString(file, "[1,2,3]", StandardCharsets.UTF_8);
        check(store.load("broken").size() == 0, "根不是对象 → 空数组");
        Files.writeString(file, "{\"version\":1}", StandardCharsets.UTF_8);
        check(store.load("broken").size() == 0, "没有 entries 字段 → 空数组");
        Files.writeString(file, "{\"version\":1,\"entries\":{\"a\":1}}", StandardCharsets.UTF_8);
        check(store.load("broken").size() == 0, "entries 是对象 → 空数组");
        Files.writeString(file, "{\"version\":1,\"entries\":\"x\"}", StandardCharsets.UTF_8);
        check(store.load("broken").size() == 0, "entries 是字符串 → 空数组");
        Files.writeString(file, "{\"version\":1,\"entries\":null}", StandardCharsets.UTF_8);
        check(store.load("broken").size() == 0, "entries 是 null → 空数组");
        Files.writeString(file, "{\"entries\":[{\"role\":\"user\",\"text\":\"带 BOM\"}]}", StandardCharsets.UTF_8);
        Files.writeString(file, "\uFEFF" + Files.readString(file, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        check(store.load("broken").size() == 1, "带 BOM 的 JSON 也能读回");
        Path asDirectory = ChatLogStore.fileFor(root, "dirscope");
        Files.createDirectories(asDirectory);
        check(store.load("dirscope").size() == 0, "目标是个目录 → 空数组、不抛");
    }

    // ---------------------------------------------------------------- 3) 逐条规范化

    static void normalization(Path root) {
        ChatLogStore store = new ChatLogStore(root);
        JsonArray raw = new JsonArray();
        raw.add(new JsonPrimitive("不是对象，整条丢"));
        raw.add(new JsonPrimitive(42));
        raw.add(JsonNull.INSTANCE);
        raw.add(entry("admin", "非法角色按 bot"));
        JsonObject noRole = new JsonObject(); noRole.addProperty("text", "没有 role");
        raw.add(noRole);
        JsonObject noText = new JsonObject(); noText.addProperty("role", "user");
        raw.add(noText);                                    // 既没文字也没图：空条目，整条丢
        JsonObject numericText = new JsonObject(); numericText.addProperty("role", "user"); numericText.addProperty("text", 12345);
        raw.add(numericText);
        JsonObject objectText = new JsonObject(); objectText.addProperty("role", "bot"); objectText.add("text", new JsonObject());
        raw.add(objectText);                                // 同上：空条目，整条丢
        JsonObject onlyImage = new JsonObject(); onlyImage.addProperty("role", "sys"); onlyImage.add("text", new JsonObject());
        JsonArray onlyImageList = new JsonArray(); onlyImageList.add("only.png"); onlyImage.add("images", onlyImageList);
        raw.add(onlyImage);                                 // 有图就不算空条目：文字归一成空串
        JsonObject messy = new JsonObject(); messy.addProperty("role", "bot"); messy.addProperty("text", "图");
        JsonArray messyImages = new JsonArray();
        messyImages.add("ok.png"); messyImages.add(""); messyImages.add(7); messyImages.add(JsonNull.INSTANCE); messyImages.add("b.png");
        messy.add("images", messyImages);
        raw.add(messy);
        JsonObject nonArrayImages = entry("bot", "images 不是数组");
        nonArrayImages.addProperty("images", "x.png");
        raw.add(nonArrayImages);
        JsonObject many = entry("bot", "图太多");
        JsonArray seventy = new JsonArray();
        for (int index = 0; index < 70; index++) seventy.add("p" + index + ".png");
        many.add("images", seventy);
        raw.add(many);
        JsonObject longImage = entry("bot", "路径超长");
        JsonArray longImages = new JsonArray();
        longImages.add("L".repeat(2000)); longImages.add("keep.png");
        longImage.add("images", longImages);
        raw.add(longImage);
        check(raw.size() == 13, "原始条目 13 条（含 3 条坏条目、2 条空条目）");

        store.save("norm", raw);
        JsonArray loaded = store.load("norm");
        check(loaded.size() == 8, "坏条目与空条目被丢、其余保留：" + loaded.size());
        check("bot".equals(loaded.get(0).getAsJsonObject().get("role").getAsString())
                && "非法角色按 bot".equals(loaded.get(0).getAsJsonObject().get("text").getAsString()), "非法 role 按 bot");
        check("bot".equals(loaded.get(1).getAsJsonObject().get("role").getAsString()), "没有 role 的条目按 bot");
        check("没有 role".equals(loaded.get(1).getAsJsonObject().get("text").getAsString()), "没有 role 的条目文字保留");
        check("12345".equals(loaded.get(2).getAsJsonObject().get("text").getAsString()), "数字 text 转字符串");
        JsonObject onlyKept = loaded.get(3).getAsJsonObject();
        check("sys".equals(onlyKept.get("role").getAsString()), "只有图的条目 role 保留");
        check("".equals(onlyKept.get("text").getAsString()), "对象 text → 空串");
        check(onlyKept.getAsJsonArray("images").size() == 1
                && "only.png".equals(onlyKept.getAsJsonArray("images").get(0).getAsString()), "有图就不算空条目，图片留下");
        JsonArray keptImages = loaded.get(4).getAsJsonObject().getAsJsonArray("images");
        check(keptImages != null && keptImages.size() == 2, "非字符串/空串图片被丢：" + keptImages);
        check("ok.png".equals(keptImages.get(0).getAsString()) && "b.png".equals(keptImages.get(1).getAsString()), "留下的图片顺序不变");
        check(!loaded.get(5).getAsJsonObject().has("images"), "images 不是数组 → 不带 images 字段");
        JsonArray capped = loaded.get(6).getAsJsonObject().getAsJsonArray("images");
        check(capped.size() == 60, "图片最多 60 张：" + capped.size());
        check("p0.png".equals(capped.get(0).getAsString()) && "p59.png".equals(capped.get(59).getAsString()), "取前 60 张");
        JsonArray clipped = loaded.get(7).getAsJsonObject().getAsJsonArray("images");
        check(clipped.size() == 2, "超长路径没被整条丢掉");
        check(clipped.get(0).getAsString().length() == 1024, "超长图片路径截到 1024：" + clipped.get(0).getAsString().length());
        check(clipped.get(0).getAsString().startsWith("LLLL"), "截断保留开头");
        check("keep.png".equals(clipped.get(1).getAsString()), "同条目的正常路径不受影响");
    }

    // ---------------------------------------------------------------- 4) 超长文字

    static void textClip(Path root) {
        ChatLogStore store = new ChatLogStore(root);
        String longText = "头".repeat(25000);
        JsonArray input = new JsonArray();
        input.add(entry("bot", longText));
        store.save("clip", input);
        String saved = store.load("clip").get(0).getAsJsonObject().get("text").getAsString();
        check(saved.startsWith(longText.substring(0, 20000)), "超长文字保留开头 20000 字");
        check(saved.length() > 20000 && saved.contains("已截断"), "超长文字带省略提示");
        check(saved.length() < longText.length(), "超长文字确实被截短");
        String shortText = "短".repeat(20000);
        JsonArray exact = new JsonArray();
        exact.add(entry("bot", shortText));
        store.save("clip", exact);
        check(shortText.equals(store.load("clip").get(0).getAsJsonObject().get("text").getAsString()), "正好 20000 字不截断");
    }

    // ---------------------------------------------------------------- 5) 只留最后 200 条

    static void entryCap(Path root) {
        ChatLogStore store = new ChatLogStore(root);
        JsonArray input = new JsonArray();
        for (int index = 0; index < 250; index++) input.add(entry("user", "m" + index));
        store.save("cap", input);
        JsonArray loaded = store.load("cap");
        check(loaded.size() == 200, "250 条 → 200 条：" + loaded.size());
        check("m50".equals(loaded.get(0).getAsJsonObject().get("text").getAsString()), "留的是最后 200 条（首条 m50）");
        check("m249".equals(loaded.get(199).getAsJsonObject().get("text").getAsString()), "末条 m249");
        boolean ordered = true;
        for (int index = 0; index < loaded.size(); index++)
            if (!("m" + (50 + index)).equals(loaded.get(index).getAsJsonObject().get("text").getAsString())) ordered = false;
        check(ordered, "200 条顺序不变且连续");
    }

    // ---------------------------------------------------------------- 6) 512KB 裁剪

    static void byteCap(Path root) throws Exception {
        ChatLogStore store = new ChatLogStore(root);
        JsonArray input = new JsonArray();
        for (int index = 0; index < 40; index++) input.add(entry("bot", "E" + index + ":" + "x".repeat(20000)));
        store.save("big", input);
        Path file = ChatLogStore.fileFor(root, "big");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        long serialized = Json.GSON.toJson(Json.parse(text)).getBytes(StandardCharsets.UTF_8).length;
        check(serialized <= store.bytesCap(), "落盘序列化长度 ≤ 512KB：" + serialized);
        check(Files.size(file) <= store.bytesCap() + 1, "文件字节数 ≤ 512KB+1（末尾 LF）：" + Files.size(file));
        JsonArray loaded = store.load("big");
        check(loaded.size() >= 1, "至少留 1 条：" + loaded.size());
        check(loaded.size() < 40, "超限时真的丢了最旧的：" + loaded.size());
        int first = 40 - loaded.size();
        check(loaded.get(0).getAsJsonObject().get("text").getAsString().startsWith("E" + first + ":"), "丢的是最旧的，留下的是尾部");
        check(loaded.get(loaded.size() - 1).getAsJsonObject().get("text").getAsString().startsWith("E39:"), "最新的那条一定在");
        boolean contiguous = true;
        for (int index = 0; index < loaded.size(); index++)
            if (!loaded.get(index).getAsJsonObject().get("text").getAsString().startsWith("E" + (first + index) + ":")) contiguous = false;
        check(contiguous, "留下的是一段连续的尾部");
        // 单条就超限时也不能丢成空文件
        JsonArray one = new JsonArray();
        one.add(entry("bot", "O:" + "y".repeat(20000)));
        store.save("single", one);
        check(store.load("single").size() == 1, "只剩 1 条时不再丢");
    }

    // ---------------------------------------------------------------- 7) scope 净化

    static void scopeSanitize(Path root) throws Exception {
        ChatLogStore store = new ChatLogStore(root);
        Path evil = ChatLogStore.fileFor(root, "../../evil");
        check(webui(root).equals(evil.getParent()), "被净化的 scope 父目录仍是 data/webui");
        check(evil.normalize().startsWith(root.toAbsolutePath().normalize()), "净化后路径仍在根目录里");
        check(".._.._evil-chat-log.json".equals(evil.getFileName().toString()), "点与斜杠的净化结果：" + evil.getFileName());
        check(!evil.getFileName().toString().contains("/") && !evil.getFileName().toString().contains("\\"), "文件名里没有分隔符");
        Path windows = ChatLogStore.fileFor(root, "C:\\Windows\\system32\\..\\..\\evil");
        check(webui(root).equals(windows.getParent()), "Windows 绝对路径 scope 也出不去");
        check(!windows.getFileName().toString().contains(":"), "冒号被替换掉");
        check("default-chat-log.json".equals(ChatLogStore.fileFor(root, "").getFileName().toString()), "空 scope → default");
        check("default-chat-log.json".equals(ChatLogStore.fileFor(root, null).getFileName().toString()), "null scope → default");
        check("___-chat-log.json".equals(ChatLogStore.fileFor(root, "###").getFileName().toString()), "非法字符逐个换成下划线");
        String longScope = "s".repeat(300);
        check(ChatLogStore.fileFor(root, longScope).getFileName().toString().equals("s".repeat(64) + "-chat-log.json"), "scope 截到 64 字符");
        check(ChatLogStore.fileFor(root, longScope).getFileName().toString().length() == 64 + "-chat-log.json".length(), "文件名长度 = 64 + 后缀");

        long before = regularFiles(root).size();
        JsonArray input = new JsonArray();
        input.add(entry("user", "越狱 scope"));
        store.save("../../evil", input);
        long after = regularFiles(root).size();
        check(after == before + 1, "只新增了 1 个文件：" + before + " → " + after);
        check(Files.isRegularFile(evil), "data/webui 里确实出现净化后的文件名");
        check(store.load("../../evil").size() == 1, "用原始 scope 能按净化后的文件读回");
        check(store.load(".._.._evil").size() == 1, "用净化后的字面 scope 读到同一份");
        check(outsideWebui(root).isEmpty(), "根目录里 data/webui 之外没有新文件：" + outsideWebui(root));
        check(!Files.exists(root.resolve("evil-chat-log.json")), "根目录下没有 evil-chat-log.json");
        check(!Files.exists(root.getParent().resolve("evil-chat-log.json")), "上级目录也没有 evil-chat-log.json");
        check(!Files.exists(root.getParent().getParent().resolve("evil-chat-log.json")), "上上级目录也没有 evil-chat-log.json");
    }

    // ---------------------------------------------------------------- 8) 落盘编码

    static void fileEncoding(Path root) throws Exception {
        ChatLogStore store = new ChatLogStore(root);
        String tricky = "第一行\r\n第二行\u0007控制符";
        JsonArray input = new JsonArray();
        input.add(entry("user", tricky));
        input.add(imageEntry("bot", "emoji 🐦 与中文", "data/webui/img/中文 名.png"));
        store.save("enc", input);
        Path file = ChatLogStore.fileFor(root, "enc");
        byte[] raw = Files.readAllBytes(file);
        check(raw.length > 3, "文件非空");
        check(!(raw[0] == (byte) 0xEF && raw[1] == (byte) 0xBB && raw[2] == (byte) 0xBF), "无 UTF-8 BOM");
        int cr = 0;
        for (byte value : raw) if (value == 13) cr++;
        check(cr == 0, "CR 计数为 0（纯 LF），实际 " + cr);
        check(raw[raw.length - 1] == 10, "以 LF 收尾");
        check(raw[0] == '{', "以 { 开头");
        String text = new String(raw, StandardCharsets.UTF_8);
        check(text.indexOf('\r') < 0, "整份文本里没有 CR");
        JsonObject state = Json.parse(text);
        check(state.getAsJsonArray("entries").size() == 2, "Json.parse 能读回");
        JsonArray loaded = store.load("enc");
        check(tricky.equals(loaded.get(0).getAsJsonObject().get("text").getAsString()), "CR/控制符原样往返");
        check("emoji 🐦 与中文".equals(loaded.get(1).getAsJsonObject().get("text").getAsString()), "emoji 往返");
        check("data/webui/img/中文 名.png".equals(loaded.get(1).getAsJsonObject().getAsJsonArray("images").get(0).getAsString()),
                "中文图片名往返");
    }

    // ---------------------------------------------------------------- 9) 并发

    static void concurrency(Path root) throws Exception {
        ChatLogStore store = new ChatLogStore(root);
        int threads = 8, rounds = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        AtomicBoolean writing = new AtomicBoolean(true);
        List<Future<?>> futures = new ArrayList<>();
        for (int tag = 0; tag < threads; tag++) {
            final int id = tag;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        JsonArray input = new JsonArray();
                        for (int index = 0; index < 3; index++) input.add(entry(index == 0 ? "user" : "bot", "T" + id + "-R" + round + "-" + index));
                        store.save("race", input);
                    }
                } catch (Throwable error) {
                    failures.incrementAndGet();
                }
            }));
        }
        // 一边写一边读：读者永远只能看到「上一份完整文件」，没有半截
        futures.add(pool.submit(() -> {
            try {
                start.await();
                while (writing.get()) {
                    JsonArray seen = store.load("race");
                    int size = seen.size();
                    if (size != 0 && size != 3) throw new AssertionError("读到了半截正文：" + size);
                }
            } catch (Throwable error) {
                failures.incrementAndGet();
            }
        }));
        start.countDown();
        for (int index = 0; index < threads; index++) futures.get(index).get(120, TimeUnit.SECONDS);
        writing.set(false);
        futures.get(threads).get(120, TimeUnit.SECONDS);
        pool.shutdown();
        check(pool.awaitTermination(30, TimeUnit.SECONDS), "线程池收干净");
        check(failures.get() == 0, "并发 save/load 全都没抛：" + failures.get());

        JsonArray loaded = store.load("race");
        check(loaded.size() == 3, "并发后是某一次完整写入（3 条）：" + loaded.size());
        String tagSeen = null;
        boolean same = true;
        for (int index = 0; index < loaded.size(); index++) {
            String value = loaded.get(index).getAsJsonObject().get("text").getAsString();
            String tag = value.substring(0, value.indexOf("-R"));
            if (tagSeen == null) tagSeen = tag;
            else if (!tagSeen.equals(tag)) same = false;
        }
        check(same, "3 条来自同一次 save，没有写串：" + tagSeen);
        Path file = ChatLogStore.fileFor(root, "race");
        JsonObject state = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        check(state.getAsJsonArray("entries").size() == 3, "并发后文件仍是合法 JSON");
        long temp = 0;
        try (var paths = Files.list(webui(root))) {
            temp = paths.filter(path -> path.getFileName().toString().endsWith(".tmp")).count();
        }
        check(temp == 0, "原子写的临时文件没有残留：" + temp);
        store.save("race", new JsonArray());
        check(store.load("race").size() == 0, "并发之后照样能清空");
    }

    // ---------------------------------------------------------------- 10) 空输入与垃圾输入

    static void nullAndJunk(Path root) throws Exception {
        ChatLogStore store = new ChatLogStore(root);
        store.save("junk", null);
        check(store.load("junk").size() == 0, "null entries → 空正文、不抛");
        check(Files.isRegularFile(ChatLogStore.fileFor(root, "junk")), "空正文也落盘（清空语义）");
        JsonArray weird = new JsonArray();
        weird.add(JsonNull.INSTANCE);
        JsonObject nested = new JsonObject();
        nested.add("nested", new JsonObject());
        JsonArray nestedImages = new JsonArray();
        nestedImages.add("nested.png");
        nested.add("images", nestedImages);
        weird.add(nested);
        weird.add(new JsonPrimitive(true));
        store.save("junk", weird);
        JsonArray loaded = store.load("junk");
        check(loaded.size() == 1, "非对象/空条目丢掉，带图的那条留下：" + loaded.size());
        check("bot".equals(loaded.get(0).getAsJsonObject().get("role").getAsString()), "垃圾条目的 role 落回 bot");
        check("".equals(loaded.get(0).getAsJsonObject().get("text").getAsString())
                && loaded.get(0).getAsJsonObject().getAsJsonArray("images").size() == 1, "只有图的垃圾条目文字归一成空串、图片留下");
        JsonArray empties = new JsonArray();
        empties.add(new JsonObject());
        JsonObject blankText = new JsonObject(); blankText.addProperty("role", "bot"); blankText.addProperty("text", "");
        empties.add(blankText);
        JsonObject blankImages = new JsonObject(); blankImages.addProperty("role", "sys"); blankImages.add("images", new JsonArray());
        empties.add(blankImages);
        check(empties.size() == 3, "三条空条目的构造没问题");
        store.save("empties", empties);
        check(store.load("empties").size() == 0, "全是空条目 → 读回是空数组");
        JsonObject emptyState = Json.parse(Files.readString(ChatLogStore.fileFor(root, "empties"), StandardCharsets.UTF_8));
        check(emptyState.getAsJsonArray("entries").size() == 0, "全是空条目 → 落盘 entries 真的是 []");
        check(emptyState.get("version").getAsInt() == 1 && "empties".equals(emptyState.get("scope").getAsString()),
                "空条目也照常落一份合法文件（清空语义）");
        JsonArray single = new JsonArray();
        single.add(entry("user", "default 里的对话"));
        store.save(null, single);
        check(Files.isRegularFile(ChatLogStore.fileFor(root, null)), "null scope 落在 default 文件");
        check(store.load(null).size() == 1, "null scope 能读回");
        check(store.load("").size() == 1, "空 scope 和 null 是同一份");
    }

    // ---------------------------------------------------------------- 11) 缺文件静默、坏数据才 warn

    /**
     * 观测口径：{@code Log.warn} 每次都取当时的 {@code System.err}（见 {@code Log.write}），
     * 所以把 {@code System.err} 临时换成一个缓冲流就能读到这一行——不改任何公开签名。
     * 这段里没有别的线程，抓到的内容就是 load 自己写的。
     */
    static void warnObservation(Path root) throws Exception {
        ChatLogStore store = new ChatLogStore(root);
        Files.createDirectories(webui(root));
        check(captureErr(() -> store.load("silent")).isEmpty(), "缺文件静默：不 warn");
        check(store.load("silent").size() == 0, "缺文件仍给空数组");
        check(!Files.exists(ChatLogStore.fileFor(root, "silent")), "读缺文件不会顺手建文件");

        Path file = ChatLogStore.fileFor(root, "yelling");
        Files.writeString(file, "{这不是 JSON", StandardCharsets.UTF_8);
        String broken = captureErr(() -> store.load("yelling"));
        check(broken.contains("WARN"), "坏 JSON 仍然 warn：" + broken);
        check(broken.contains("yelling-chat-log.json"), "warn 里点名是哪个文件");
        check(store.load("yelling").size() == 0, "坏 JSON 仍给空数组");

        Files.writeString(file, "{\"version\":1,\"entries\":{}}", StandardCharsets.UTF_8);
        check(captureErr(() -> store.load("yelling")).contains("WARN"), "entries 不是数组仍然 warn");
        Files.writeString(file, "[1,2,3]", StandardCharsets.UTF_8);
        check(captureErr(() -> store.load("yelling")).contains("WARN"), "根不是对象仍然 warn");
        Path asDirectory = ChatLogStore.fileFor(root, "dirwarn");
        Files.createDirectories(asDirectory);
        check(captureErr(() -> store.load("dirwarn")).contains("WARN"), "读失败（目标是目录）仍然 warn");

        JsonArray input = new JsonArray();
        input.add(entry("user", "正常一份"));
        store.save("quiet", input);
        check(captureErr(() -> store.load("quiet")).isEmpty(), "正常读取不 warn");
        check(captureErr(() -> store.save("quiet", input)).isEmpty(), "正常写入不 warn");
        check(store.load("quiet").size() == 1, "正常那份还是 1 条");
    }

    // ---------------------------------------------------------------- 工具

    /** 把这一段里打到 stderr 的日志抓成字符串（结束后一定还原）。 */
    static String captureErr(Runnable action) {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try {
            System.setErr(new java.io.PrintStream(buffer, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8).strip();
    }

    static JsonObject entry(String role, String text) {
        JsonObject value = new JsonObject();
        value.addProperty("role", role);
        value.addProperty("text", text);
        return value;
    }

    static JsonObject imageEntry(String role, String text, String... images) {
        JsonObject value = entry(role, text);
        JsonArray list = new JsonArray();
        for (String image : images) list.add(image);
        value.add("images", list);
        return value;
    }

    static Path webui(Path root) { return root.toAbsolutePath().normalize().resolve("data").resolve("webui"); }

    static List<Path> regularFiles(Path root) throws Exception {
        try (var paths = Files.walk(root)) { return paths.filter(Files::isRegularFile).toList(); }
    }

    static List<Path> outsideWebui(Path root) throws Exception {
        Path dir = webui(root);
        return regularFiles(root).stream().filter(path -> !path.startsWith(dir)).toList();
    }
}

package cn.szu.bot;

import java.nio.file.*;
import cn.szu.bot.chat.KotoriCorpus;

/** 原作语料：解析、命中检索、风格锚点兜底、文件缺失不报错。 */
public final class KotoriCorpusTest {
    static int checks = 0;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError("FAIL: " + what); }

    static final String SAMPLE = """
            ===== seen00001 =====

            【瑚太朗】「喂，起来了～」
            【小鸟】「…咕～」

            【瑚太朗】「早上好。」
            【小鸟】「早上好瑚太朗君！」

            【瑚太朗】「你在干什么？」
            【小鸟】「发呆哦。」

            【瑚太朗】「我喜欢你，小鸟。」
            【小鸟】「…唔。」

            【瑚太朗】「今天也最喜欢小鸟了。」
            【小鸟】「诶，连这个都知道了吗？讨厌…好丢人」

            【小鸟】「唔～嗯～」
            【小鸟】「………」

            ===== seen00002 =====

            【千早】「早上好～」
            【小鸟】「早上好！　今天小千也很胸部呢！」
            """;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(System.getProperty("bot.test.work", "work")), "corpus-test-");
        Path file = KotoriCorpus.defaultFile(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, SAMPLE);

        KotoriCorpus corpus = new KotoriCorpus(file);
        check(corpus.pairCount() == 6, "六组问答（小鸟连说两句不算问答）：" + corpus.pairCount());
        check(corpus.anchorCount() > 0, "风格锚点取自语料原句：" + corpus.anchorCount());

        // 1) 原作提问 → 命中并返回紧随其后的小鸟原句
        KotoriCorpus.Reference hit = corpus.reference("早上好。", 3);
        check(hit.matched() && !hit.hits().isEmpty(), "原作台词必须命中：" + hit.hits());
        check(hit.hits().get(0).reply().contains("早上好瑚太朗君"), "命中后返回小鸟原句：" + hit.hits().get(0));
        check(hit.hits().get(0).prompt().contains("早上好"), "命中项带上对方的原话");

        KotoriCorpus.Reference asking = corpus.reference("你在干什么？", 3);
        check(asking.matched() && asking.hits().get(0).reply().equals("发呆哦。"), "「你在干什么？」→「发呆哦。」：" + asking.hits());

        KotoriCorpus.Reference confession = corpus.reference("我喜欢你，小鸟。", 3);
        check(confession.matched() && confession.hits().get(0).reply().contains("唔"), "告白命中极短含糊的「…唔。」：" + confession.hits());
        KotoriCorpus.Reference praise = corpus.reference("今天也最喜欢小鸟了。", 3);
        check(praise.matched() && praise.hits().get(0).reply().contains("好丢人"), "被夸/被亲昵命中淡化式回答：" + praise.hits());

        // 2) 无关键的闲聊 → 不命中，退回风格锚点
        KotoriCorpus.Reference miss = corpus.reference("帮我看看这个季度的财务报表吧", 3);
        check(!miss.matched() && miss.hits().isEmpty(), "无关消息不得命中");
        check(!miss.anchors().isEmpty(), "未命中时仍给出口癖锚点");
        check(miss.anchors().stream().noneMatch(line -> line.isBlank()), "锚点里没有空行");

        // 3) 注入文本：命中时写明照搬、未命中时只给味道参考，且不超过上限
        String injectHit = corpus.injectText(hit, 0);
        check(injectHit.contains("优先照搬") && injectHit.contains("早上好瑚太朗君"), "命中注入必须写明优先照搬：" + injectHit);
        String injectMiss = corpus.injectText(miss, 0);
        check(injectMiss.contains("轮换样板") && !injectMiss.contains("优先照搬"), "未命中只能当样板参考");
        check(corpus.injectText(hit, 0).length() <= KotoriCorpus.MAX_INJECT_CHARS, "注入长度受上限约束");

        // 4) 语料文件缺失：不报错，只走风格锚点
        Path bare = Files.createTempDirectory(Path.of(System.getProperty("bot.test.work", "work")), "corpus-bare-");
        Json.atomicWrite(bare.resolve("config.json"), new com.google.gson.JsonObject());
        Settings settings = new Settings(bare);
        check(settings.corpusReplay(), "corpus_replay 默认开");
        check(settings.corpusTopK() == 3, "corpus_top_k 默认 3");
        String absent = KotoriCorpus.referenceFor(settings, "早上好。");
        check(absent.isEmpty(), "语料缺失时注入为空，且不抛异常：" + absent);

        // 5) 关掉复现 → 不再拿语料（含锚点）注入
        Files.createDirectories(KotoriCorpus.defaultFile(bare).getParent());
        Files.writeString(KotoriCorpus.defaultFile(bare), SAMPLE);
        check(!KotoriCorpus.referenceFor(settings, "早上好。").isEmpty(), "有语料时能取到参考台词");
        settings.chatSetting("corpus_replay", new com.google.gson.JsonPrimitive(false));
        check(KotoriCorpus.referenceFor(settings, "早上好。").isEmpty(), "关掉复现后不再注入参考台词");
        settings.chatSetting("corpus_replay", new com.google.gson.JsonPrimitive(true));
        settings.chatSetting("corpus_top_k", new com.google.gson.JsonPrimitive(1));
        check(new Settings(bare).corpusTopK() == 1, "corpus_top_k 可配置");

        // 6) 剥唤醒前缀 + 情感类强制检索：用户投诉的那句必须命中「我喜欢你，小鸟。」这一对
        check(KotoriCorpus.stripWakePrefix("@Loriko 小鸟，我喜欢你。").equals("我喜欢你。"),
                "唤醒前缀必须剥掉：" + KotoriCorpus.stripWakePrefix("@Loriko 小鸟，我喜欢你。"));
        check(KotoriCorpus.stripWakePrefix("[CQ:at,qq=123] 小鸟 早上好。").equals("早上好。"), "CQ at 段也要剥掉");
        check(KotoriCorpus.topicOf("小鸟，我喜欢你。") == KotoriCorpus.Topic.AFFECTION, "表白必须识别成情感类");
        check(KotoriCorpus.topicOf("我们要不要交往？") == KotoriCorpus.Topic.AFFECTION, "交往属于情感类");
        check(KotoriCorpus.topicOf("小鸟，你最近有没有想我？") == KotoriCorpus.Topic.AFFECTION, "「想我」属于情感类");
        check(KotoriCorpus.topicOf("抱一下嘛。") == KotoriCorpus.Topic.AFFECTION, "「抱一下」属于情感类");
        check(KotoriCorpus.topicOf("你真厉害") == KotoriCorpus.Topic.PRAISE, "夸奖识别成夸奖类");
        check(KotoriCorpus.topicOf("今天有点累") == KotoriCorpus.Topic.DAILY, "日常闲聊是普通类");
        KotoriCorpus.Reference confessionHit = corpus.reference("@Loriko 小鸟，我喜欢你。", 3);
        check(confessionHit.matched() && confessionHit.forced(), "带前缀的表白必须强制命中：" + confessionHit.matched());
        check(confessionHit.hits().get(0).reply().contains("唔"), "表白命中照搬「…唔。」：" + confessionHit.hits().get(0));
        String confessionInject = corpus.injectText(confessionHit, 0);
        check(confessionInject.contains("极短") && confessionInject.contains("不许讲道理"), "情感类注入要写明极短、不许讲道理");

        // 7) 情感类未命中 → 同场景锚点，而不是通用锚点
        Path tinyFile = Files.createTempFile(root, "tiny-corpus-", ".txt");
        Files.writeString(tinyFile, """
                ===== seen00009 =====

                【瑚太朗】「今天天气不错呢。」
                【小鸟】「是呢。」

                【瑚太朗】「风很大啊。」
                【小鸟】「唔～」
                """);
        KotoriCorpus tiny = new KotoriCorpus(tinyFile);
        KotoriCorpus.Reference topicMiss = tiny.reference("请和我交往吧", 3);
        check(topicMiss.topic() == KotoriCorpus.Topic.AFFECTION && !topicMiss.matched(),
                "情感类未命中时保持情感类标记：" + topicMiss.hits());
        check(!topicMiss.anchors().isEmpty(), "情感类未命中时仍给同场景锚点：" + topicMiss.anchors());

        // 8) AI 陪伴腔 / 说教 / 凭空动作：程序级禁止清单
        for (String banned : new String[]{"我会稳稳接住你", "抱抱你", "我懂你的感受", "我会一直在", "陪着你走", "你的感受是合理的",
                "给你空间", "允许自己", "你已经很努力了", "我一直都在", "无条件陪着你"})
            check(KotoriCorpus.forbiddenReply(banned) != null, "AI 陪伴腔必须被拦下：" + banned);
        for (String banned : new String[]{"我喜欢的人要站得稳", "把份量全压给我", "不是不领情", "好啦，先把水喝了"})
            check(KotoriCorpus.forbiddenReply(banned) != null, "说教/元解释/凭空动作必须被拦下：" + banned);
        String userComplaint = "……诶？大清早的说这个。\n不接哦。不是不领情——是你这句话我听着，心里会先凉一下。"
                + "我喜欢的人要站得稳、自己站得住，而不是把份量全压给我。\n好啦，先把水喝了。";
        check(KotoriCorpus.forbiddenReply(userComplaint) != null, "用户投诉的那条回复必须被判定为违规");
        for (String clean : new String[]{"…唔。", "原来如此。", "诶——？", "发呆哦。", "唔～，那就少干点嘛。", "早上好～", "………"})
            check(KotoriCorpus.forbiddenReply(clean) == null, "正常原句不得被误伤：" + clean);

        // 9) G1：命中时注入的是"整段"（含对方台词、多轮），不是单句；总量 ≤2000 字
        KotoriCorpus.Reference multi = corpus.reference("早上好。", 2);
        check(multi.matched(), "多轮片段用例需要先命中");
        String multiInject = corpus.injectText(multi, 0);
        check(multiInject.contains("【小鸟】") && multiInject.contains("【瑚太朗】") && multiInject.contains("【千早】"),
                "命中时必须注入含其他人台词的整段片段：" + multiInject);
        check(multiInject.split("【").length >= 4, "片段至少要有多轮对白：" + multiInject.split("【").length);
        check(multiInject.contains("这些不是参考资料") || multiInject.contains("你自己说过的话"),
                "注入必须说明「这是你自己的话」：" + multiInject);
        check(multiInject.length() <= KotoriCorpus.MAX_INJECT_CHARS,
                "注入总量不得超过上限 " + KotoriCorpus.MAX_INJECT_CHARS + "：" + multiInject.length());
        check(KotoriCorpus.MAX_INJECT_CHARS == 2000, "上限按 G1 放宽到 2000");
        // 轮换：不同轮数注入的样板组合应不同
        KotoriCorpus.Reference missRef = corpus.reference("帮我看看这个季度的财务报表吧", 3);
        String turn0 = corpus.injectText(missRef, 0), turn2 = corpus.injectText(missRef, 2);
        check(!turn0.equals(turn2), "未命中时的样板要按会话轮数轮换");
        // 旁听（窗口外插话）整条路径已删除，KotoriCorpus.briefFor/briefText 也随之删除：不再断言。

        // 10) 任务 I：断言/语气词/标点素材库全部取自语料原句，并带 seen 出处
        check(corpus.assertionCount() > 0, "断言素材库非空：" + corpus.assertionCount());
        check(corpus.particleCount() > 0, "语气词素材库非空：" + corpus.particleCount());
        check(corpus.punctuationCount() > 0, "标点素材库非空：" + corpus.punctuationCount());
        String material = corpus.speechMaterialText(0);
        check(material.contains("喝止") || material.contains("否决"), "断言素材要按功能分组：" + material);
        check(material.contains("标点按功能用"), "标点素材要写清功能：" + material);
        check(material.contains("省略号") || material.contains("停顿"), "省略号功能必须写进素材");
        check(corpus.injectText(multi, 0).contains("小鸟式断言") || corpus.injectText(multi, 0).contains("语气词"),
                "注入里要带上断言/语气词素材");
        check(corpus.forbiddenReply("伤害禁止！") == null, "喝止类断言不得被禁止清单误伤");
        check(corpus.forbiddenReply("ＮＧ！") == null, "否决类断言不得被禁止清单误伤");

        System.out.println("KotoriCorpusTest: " + checks + " assertions passed: 解析、命中检索、整段注入、锚点轮换、剥前缀、情感强制检索、"
                + "禁止话术清单、断言/语气词/标点素材库。");
    }
}
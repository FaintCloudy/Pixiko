package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import cn.szu.bot.chat.DeepSeekPrompts;
import cn.szu.bot.chat.SceneDecomposer;
import cn.szu.bot.prompt.PromptUsage;

/**
 * 前置拆解层与验收层：复杂要求先拆成三条以上简单修改，每条都能找到标准词；
 * 验收时词库外的词先尝试换成含义相近的标准词，而不是直接丢掉。
 */
public final class SceneDecomposeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "scene-decompose").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "case-");
        Files.createDirectories(root.resolve("data"));
        try {
            Files.writeString(root.resolve("data/deepseek-api-key.txt"), "eval-key");
            Files.writeString(root.resolve("data/prompt-usage.json"), """
                {"version":1,"source":"fixture","categories":[{"name":"场景","children":[],"tags":[
                {"prompt":"grass","meaning":"草地"},{"prompt":"train","meaning":"地铁"},
                {"prompt":"heavy_breathing","meaning":"喘气"},{"prompt":"blush","meaning":"脸红"},
                {"prompt":"groping","meaning":"抚摸"},{"prompt":"chikan","meaning":"痴汉"},
                {"prompt":"hip_focus","meaning":"臀部特写"},{"prompt":"molestation","meaning":"猥亵"}]}]}""");
            PromptUsage usage = new PromptUsage(root);
            Set<String> allowed = new HashSet<>(List.of("grass", "train", "heavy breathing", "blush", "groping", "chikan",
                    "hip focus", "molestation", "1girl", "solo", "lowres", "blur", "child"));
            canonicalisation(usage, allowed);
            acceptance(usage, allowed);
            complexityAndAspects();
            unrequestedTagCleanup();
            decomposition(root, usage, allowed);
            enrichment();
            System.out.println("SceneDecomposeTest: " + checks + " assertions passed：近义词转标准词、验收不丢弃、未要求词条清理、复杂度判定、拆解重试、拆解提示注入");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    /** 近义词/词形变体/拼写最接近的词库词都要能对上。 */
    private static void canonicalisation(PromptUsage usage, Set<String> allowed) {
        check("grass".equals(usage.canonical("grasses", allowed)), "复数转标准词：grasses → grass");
        check("blush".equals(usage.canonical("blushes", allowed)), "es 复数：blushes → blush");
        check("heavy_breathing".equals(usage.canonical("heavy_breath", allowed)), "共享实词：heavy_breath → heavy_breathing");
        check("heavy_breathing".equals(usage.canonical("heavy breathing", allowed)), "空格写法：heavy breathing → heavy_breathing");
        check("hip_focus".equals(usage.canonical("hip focus", allowed)), "空格写法：hip focus → hip_focus");
        check(usage.canonical("xyzzy_foo", allowed) == null, "毫不相关的词不会被硬套");
        // 只认词形变化：多出来的词不会去猜（曾经把 playground 猜成 wax_play、natural 猜成 natu）。
        check(usage.canonical("groping_hands", allowed) == null, "多余词不会去猜相近词条");
        check("playground".equals(usage.canonical("playground", allowed)) || usage.canonical("playground", allowed) == null,
                "词库里没有 playground 时不会乱猜");
        check("child".equals(usage.canonical("children", allowed)), "不规则复数：children → child");
    }

    /** 验收：能转的就转成标准词保留，转不了才丢弃。 */
    private static void acceptance(PromptUsage usage, Set<String> allowed) {
        DeepSeekPrompts.Result rewritten = new DeepSeekPrompts.Result("1girl, grass, heavy_breath, blushs, xyzzy_foo", "lowres, blur");
        Bot.InfixFilter filter = Bot.filterInfixVocabulary(allowed, rewritten, false, usage);
        String positive = filter.result().positive();
        check(positive.contains("grass") && positive.contains("heavy_breathing") && positive.contains("blush"),
                "近义词被换成标准词后保留：" + positive);
        check(filter.converted().size() == 2
                        && filter.converted().stream().anyMatch(item -> item.contains("heavy_breath") && item.contains("heavy_breathing"))
                        && filter.converted().stream().anyMatch(item -> item.contains("blushs") && item.contains("blush")),
                "转换项被如实报告：" + filter.converted());
        check(filter.rejected().size() == 1 && filter.rejected().get(0).contains("xyzzy_foo"),
                "真正无法对应的词仍然被丢弃并报告：" + filter.rejected());
        // 关闭词库约束时不该做转换（保持模型原样）
        Bot.InfixFilter relaxed = Bot.filterInfixVocabulary(allowed, rewritten, true, usage);
        check(relaxed.result().positive().contains("heavy_breath"), "关闭约束时保留模型原词：" + relaxed.result().positive());
        check(relaxed.converted().isEmpty(), "关闭约束时不做转换");
        // 没有别名索引时退化为原来的行为
        Bot.InfixFilter plain = Bot.filterInfixVocabulary(allowed, rewritten, false, null);
        check(plain.converted().isEmpty() && plain.rejected().size() == 3, "缺少别名索引时按原规则丢弃：" + plain.rejected());
    }

    /** 验收清理：用户没要求的画风词条、与要求冲突的词条都要被移除，无关的新词保留。 */
    private static void unrequestedTagCleanup() {
        List<String> removed = new ArrayList<>();
        String cleaned = Bot.dropUnrequestedTags("1girl, solo, subway, car, interior, realistic, crowd",
                "1girl, solo", List.of("subway", "crowd"), true, removed);
        check(cleaned.contains("subway") && cleaned.contains("crowd") && cleaned.contains("interior"),
                "要求的词条与无关新词保留：" + cleaned);
        check(!cleaned.contains("car"), "要求 subway 时不会保留互斥的 car：" + cleaned);
        check(!cleaned.contains("realistic"), "用户没要求画风时去掉 realistic：" + cleaned);
        check(removed.size() == 2 && removed.stream().anyMatch(item -> item.contains("car"))
                        && removed.stream().anyMatch(item -> item.contains("realistic")),
                "移除项如实报告：" + removed);
        // 用户原有的词条一律不动，即使它和新词条同族。
        List<String> kept = new ArrayList<>();
        String preserved = Bot.dropUnrequestedTags("1girl, car, subway", "1girl, car", List.of("subway"), true, kept);
        check(preserved.contains("car") && preserved.contains("subway"), "原有词条不会被清理：" + preserved);
        // 用户明确要求画风时，画风词条保留。
        List<String> none = new ArrayList<>();
        check(Bot.dropUnrequestedTags("1girl, realistic", "1girl", List.of(), false, none).contains("realistic"),
                "要求画风时保留 realistic");
        check(Bot.isStyleOnlyTag("(masterpiece:1.2)") && Bot.isStyleOnlyTag("highres") && !Bot.isStyleOnlyTag("chikan"),
                "画风/质量词条识别（含权重包裹）");
        check(Bot.mentionsStyle("写实风格的照片") && !Bot.mentionsStyle("地铁痴汉性骚扰女性"), "是否在要求画风");
        check(Bot.tagsConflict("subway", "car") && Bot.tagsConflict("sitting", "standing")
                        && !Bot.tagsConflict("subway", "train_interior"),
                "载具/姿势互斥识别（地铁与车厢不算冲突）");
    }

    private static void complexityAndAspects() {
        check(SceneDecomposer.isComplex("女性被男性地铁痴汉"), "复杂场景需要拆解");
        check(SceneDecomposer.isComplex("骑士骑着马冲进燃烧的村庄，风雪很大"), "多分句需要拆解");
        check(!SceneDecomposer.isComplex("换成草地"), "单个简单修改不需要拆解");
        check(SceneDecomposer.missingAspects(List.of("男性抚摸隐私部位", "女性脸红")).equals(List.of("场景")),
                "缺少场景时如实报告：" + SceneDecomposer.missingAspects(List.of("男性抚摸隐私部位", "女性脸红")));
        check(SceneDecomposer.missingAspects(List.of("男性在地铁上抚摸", "女性脸红", "周围人看着")).isEmpty(),
                "人物/动作/场景齐备时不再报告");
        // "删除所有视角词/清空环境词"是对提示词的清理要求，不算画面要素，也不该被当成缺口。
        check(SceneDecomposer.isCleanup("删除所有视角词") && SceneDecomposer.isCleanup("清空所有环境词")
                && SceneDecomposer.isCleanup("去掉背景词") && !SceneDecomposer.isCleanup("以臀部为主要视角"),
                "清理要求的识别要准确");
        check(SceneDecomposer.missingAspects(List.of("删除所有视角词", "清空环境词", "女性在电车上被骚扰", "性交", "电车车厢内")).isEmpty(),
                "清理条目不参与覆盖判定：" + SceneDecomposer.missingAspects(List.of("删除所有视角词", "清空环境词", "女性在电车上被骚扰", "性交", "电车车厢内")));
    }

    /** 拆解不足三条时会带着要求重试；模型把三条塞成一条时按标点拆开。 */
    private static void decomposition(Path root, PromptUsage usage, Set<String> allowed) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DeepSeekPrompts retrying = new DeepSeekPrompts(root, new JsonObject(), (body, key, timeout) -> {
            int call = calls.incrementAndGet();
            if (call == 1) return ok("{\"parts\":[\"男性抚摸\",\"女性脸红\"]}");
            return ok("{\"parts\":[\"男性在地铁上抚摸女性\",\"女性脸红\",\"女性低头不敢看\"]}");
        });
        SceneDecomposer.Scene scene = SceneDecomposer.decompose("女性被男性地铁痴汉", allowed, usage, retrying);
        check(calls.get() >= 2, "拆解不足三条时会重试（调用 " + calls.get() + " 次）");
        check(scene.parts().size() >= 3 && scene.complete(), "重试后拆出三条以上：" + scene.texts());
        long tagged = scene.parts().stream().filter(part -> !part.tags().isEmpty()).count();
        check(tagged >= 2, "至少两条简单修改能对上标准词：" + scene.parts().stream()
                .map(part -> part.text() + "[" + String.join(",", part.tags()) + "]").toList());
        check(scene.allTags().contains("groping") && scene.allTags().contains("blush") && scene.allTags().contains("train"),
                "拆解出的词条来自词库（抚摸/脸红/地铁）：" + scene.allTags());

        DeepSeekPrompts single = new DeepSeekPrompts(root, new JsonObject(), (body, key, timeout) ->
                ok("{\"parts\":[\"男性抚摸女性，女性脸红，女性低头\"]}"));
        SceneDecomposer.Scene split = SceneDecomposer.decompose("女性被男性地铁痴汉", allowed, usage, single);
        check(split.parts().size() >= 3, "一条里塞多句时按标点拆开：" + split.texts());

        DeepSeekPrompts failing = new DeepSeekPrompts(root, new JsonObject(), (body, key, timeout) ->
                ok("{\"parts\":[\"男性抚摸\"]}"));
        SceneDecomposer.Scene incomplete = SceneDecomposer.decompose("女性被男性地铁痴汉", allowed, usage, failing);
        check(!incomplete.complete(), "实在拆不出三条时如实标记为不完整：" + incomplete.texts());
    }

    private static void enrichment() {
        SceneDecomposer.Scene scene = new SceneDecomposer.Scene("女性被男性地铁痴汉",
                List.of(new SceneDecomposer.Part("男性抚摸隐私部位", List.of("groping")),
                        new SceneDecomposer.Part("女性脸红", List.of("blush")),
                        new SceneDecomposer.Part("性骚扰", List.of("chikan"))), true);
        String enriched = Bot.enrich("女性被男性地铁痴汉", scene);
        check(enriched.contains("拆解后的简单修改") && enriched.contains("1. 男性抚摸隐私部位")
                        && enriched.contains("2. 女性脸红") && enriched.contains("3. 性骚扰"),
                "拆解结果被逐条写进改写要求：" + enriched.replace("\n", " / "));
        check(enriched.contains("对应标准词条：groping"), "每条都带上对应标准词条");
        check(Bot.enrich("换成草地", new SceneDecomposer.Scene("换成草地", List.of(), true)).equals("换成草地"),
                "没有拆解时保持原样");
    }

    private static DeepSeekPrompts.Response ok(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant"); message.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0); choice.addProperty("finish_reason", "stop"); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("id", "test"); envelope.addProperty("object", "chat.completion"); envelope.add("choices", choices);
        return new DeepSeekPrompts.Response(200, envelope.toString());
    }

    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
}

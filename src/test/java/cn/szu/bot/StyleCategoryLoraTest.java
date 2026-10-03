package cn.szu.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import cn.szu.bot.sd.LocalStyles;
import cn.szu.bot.sd.SdClient;

/**
 * 「一个 LoRA 一个样式分类」（v1.0.14）：
 * <ul>
 *   <li>判据优先级：{@code model.lora} → {@code data/civitai-style-links.json} 映射 → 样式名前缀；</li>
 *   <li>分类名用 {@code data/civitai/<file>.json} 里的 {@code model_name}（人看的显示名），查不到才用文件名；</li>
 *   <li>判不出具体 LoRA 时才退回「LoRA 附带」；不是展示图样式就按归属栈；都判不出是「未分类」；</li>
 *   <li>手动分类优先、清空回自动；老版本自动写下的 category="LoRA 附带" 不算手动分类；</li>
 *   <li>{@code /api/styles} 的 categories 与每条样式的 categoryKey 完全一致、排序 lora → 栈 → 手动 → 未分类；
 *       读接口**不改写** data/local-styles.json。</li>
 * </ul>
 */
public final class StyleCategoryLoraTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        try (Fixture f = new Fixture()) { criteriaPriority(f); }
        try (Fixture f = new Fixture()) { civitaiDisplayNames(f); }
        try (Fixture f = new Fixture()) { loraAttachedFallback(f); }
        try (Fixture f = new Fixture()) { manualBeatsAuto(f); }
        try (Fixture f = new Fixture()) { oldLoraCategoryIsAutomatic(f); }
        try (Fixture f = new Fixture()) { orderingAndCategories(f); }
        try (Fixture f = new Fixture()) { webApiContract(f); }
        System.out.println("StyleCategoryLoraTest: " + assertions + " assertions passed: 一个 LoRA 一个分类、"
                + "判据优先级（model.lora → 展示图映射 → 样式名前缀）、Civitai model_name 当分类名、"
                + "认不出具体 LoRA 时退回「LoRA 附带」、手动分类优先与清空、老数据 category=LoRA 附带 自动迁移、"
                + "categories/categoryKey 一致与排序、读接口不改写数据文件。");
    }

    /** 判据优先级：model.lora ＞ 展示图映射 ＞ 样式名前缀；三条样式落到三个不同的 LoRA 分类。 */
    private static void criteriaPriority(Fixture f) throws Exception {
        f.links(Map.of(
                "1/2/链接模型.safetensors/1", "链接样式 1",
                "3/4/优先模型.safetensors/1", "优先样式 5"));
        f.seed(List.of(
                style("优先样式 5", "a", model("lora", "优先模型", "sizeSource", "preview")),
                style("链接样式 1", "b", model("sizeSource", "preview")),
                style("前缀模型 7", "c", model("sizeSource", "preview"))));
        LocalStyles styles = new LocalStyles(f.root);
        equal("优先模型", styles.categoryOf(styles.get("优先样式 5")), "判据 1：model.lora 比映射更可靠");
        equal("lora:优先模型", styles.classify(styles.get("优先样式 5")).key(), "判据 1 的分类 key");
        equal(LocalStyles.KIND_LORA, styles.classify(styles.get("优先样式 5")).kind(), "判据 1 的分类种类");
        equal("链接模型", styles.categoryOf(styles.get("链接样式 1")), "判据 2：映射里的 LoRA 文件名（比样式名前缀可靠）");
        equal("前缀模型", styles.categoryOf(styles.get("前缀模型 7")), "判据 3：样式名前缀（去掉尾号）");
        // 一个 LoRA 一个分类：三条样式分到三个 LoRA 组（外加永远在最后的「未分类」）。
        List<LocalStyles.Group> groups = styles.categoryGroups();
        equal(4, groups.size(), "三条样式＝三个 LoRA 组 + 未分类：" + groups);
        equal(1, groups.get(0).count(), "每组各 1 条");
        equal(0, groups.get(3).count(), "未分类 0 条但这一组仍要给出来");
        equal(LocalStyles.NONE_KEY, groups.get(3).key(), "未分类的 key 固定是 none");
        equal(LocalStyles.OTHER_CATEGORY, groups.get(3).name(), "未分类的名字");
        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < 3; index++) names.add(groups.get(index).name());
        equal(new LinkedHashSet<>(List.of("优先模型", "链接模型", "前缀模型")), names, "三条样式分到三个 LoRA 分类");
    }

    /** 分类名用 Civitai 记录里的 model_name：model.lora 与「只靠映射」两条路都要换成同一个显示名。 */
    private static void civitaiDisplayNames(Fixture f) throws Exception {
        f.civitai("Naruse_Shiroha_-_Summer_pockets_IL.safetensors.json", "Naruse Shiroha - Summer pockets IL",
                "F:\\sd\\sd-webui-forge-neo\\models\\Lora\\Naruse_Shiroha_-_Summer_pockets_IL.safetensors");
        f.links(Map.of(
                "1241445/1399163/Naruse_Shiroha_-_Summer_pockets_IL.safetensors/1", "Naruse Shiroha - Summer pockets IL 1",
                // 样式改过名也不怕：映射按**样式名**找记录，找得到就用记录里的 LoRA 文件名。
                "1241445/1399163/Naruse_Shiroha_-_Summer_pockets_IL.safetensors/2", "老记录 8"));
        f.seed(List.of(
                // 有 model.lora：标识是文件名，显示名要换成记录里的 model_name。
                style("Naruse Shiroha - Summer pockets IL 1", "a", model("lora", "Naruse_Shiroha_-_Summer_pockets_IL")),
                // 没有 model.lora：只靠展示图映射的文件名，同样要换成人看的 model_name。
                style("老记录 8", "b", model("sizeSource", "preview")),
                // Civitai 里没有记录的 LoRA：显示名退到文件名（去扩展名，下划线保留）。
                style("陌生 LoRA 3", "c", model("lora", "Some_Unknown_Lora"))));
        LocalStyles styles = new LocalStyles(f.root);
        equal("Naruse Shiroha - Summer pockets IL", styles.categoryOf(styles.get("Naruse Shiroha - Summer pockets IL 1")),
                "model.lora → Civitai 记录的 model_name");
        equal("Naruse Shiroha - Summer pockets IL", styles.categoryOf(styles.get("老记录 8")),
                "映射里的 LoRA 文件名 → Civitai 记录的 model_name");
        equal("Some_Unknown_Lora", styles.categoryOf(styles.get("陌生 LoRA 3")), "没有 Civitai 记录就用文件名");
        equal(2, styles.categoryGroups().get(0).count(), "同一个 LoRA 的两条样式进同一个分类");
        equal("lora:Naruse Shiroha - Summer pockets IL", styles.categoryGroups().get(0).key(), "分类 key 用显示名");
    }

    /** 是展示图样式但认不出具体 LoRA → 退回「LoRA 附带」；不是展示图样式 → 按归属栈；都判不出 → 未分类。 */
    private static void loraAttachedFallback(Fixture f) throws Exception {
        // 映射的键不合法（没有 LoRA 文件名）时也认不出具体 LoRA。
        f.links(Map.of("bad-key", "无文件名样式"));
        f.seed(List.of(
                style("展示图没有尾号", "a", model("sizeSource", "preview")),
                style("无文件名样式", "b", model("sizeSource", "preview")),
                style("Anima 普通样式", "c", model("baseModel", "Anima")),
                style("SDXL 普通样式", "d", model("stack", "xl")),
                style("截图 2", "e", model("stack", "sd")),
                // WebUI 导入的普通预设样式：名字结尾带数字，但没有展示图实据，绝不能被分进 LoRA 分类。
                style("篠森よもぎ 2", "imported", null),
                style("什么都没有", "f", null)));
        LocalStyles styles = new LocalStyles(f.root);
        equal(LocalStyles.LORA_CATEGORY, styles.categoryOf(styles.get("展示图没有尾号")), "认不出具体 LoRA 退回「LoRA 附带」");
        equal(LocalStyles.KIND_LORA, styles.classify(styles.get("展示图没有尾号")).kind(), "兜底分类仍算 LoRA 类");
        equal("lora:" + LocalStyles.LORA_CATEGORY, styles.classify(styles.get("展示图没有尾号")).key(), "兜底分类的 key");
        equal(LocalStyles.LORA_CATEGORY, styles.categoryOf(styles.get("无文件名样式")), "映射键里没有文件名也判不出 LoRA");
        equal("Anima 栈", styles.categoryOf(styles.get("Anima 普通样式")), "普通样式按归属栈：Anima 栈");
        equal("stack:anima", styles.classify(styles.get("Anima 普通样式")).key(), "栈分类的 key 用栈名");
        equal("SDXL 栈", styles.categoryOf(styles.get("SDXL 普通样式")), "普通样式按归属栈：SDXL 栈");
        // 名字结尾带数字的**普通**样式不算展示图样式：没有被名字误分进 LoRA 分类。
        equal("", LocalStyles.loraName(styles.get("截图 2"), LocalStyles.LoraIndex.EMPTY), "普通样式不会只按名字被认成 LoRA");
        equal("SD 1.5 栈", styles.categoryOf(styles.get("截图 2")), "名字像展示图样式但没有实据 → 按归属栈");
        equal(LocalStyles.KIND_NONE, styles.classify(styles.get("篠森よもぎ 2")).kind(),
                "WebUI 导入的普通样式「篠森よもぎ 2」不得被判成 LoRA 分类");
        equal(LocalStyles.OTHER_CATEGORY, styles.categoryOf(styles.get("篠森よもぎ 2")), "没有实据的普通样式是未分类");
        equal(LocalStyles.OTHER_CATEGORY, styles.categoryOf(styles.get("什么都没有")), "什么都判不出是未分类");
        equal(LocalStyles.NONE_KEY, styles.classify(styles.get("什么都没有")).key(), "未分类的 key");
        equal(2, styles.categoryGroups().get(0).count(), "「LoRA 附带」两条合成一组：" + styles.categoryGroups());
    }

    /** 手动分类优先于自动；清空回自动；用户手动写的分类不会被保存/同步改回去。 */
    private static void manualBeatsAuto(Fixture f) throws Exception {
        f.seed(List.of(style("鸣濑白羽 1", "a", model("lora", "Naruse_Shiroha_-_Summer_pockets_IL"))));
        LocalStyles styles = new LocalStyles(f.root);
        equal("Naruse_Shiroha_-_Summer_pockets_IL", styles.categoryOf(styles.get("鸣濑白羽 1")), "先是自动分类");
        // 命令与网页两条路都能设手动分类（#编号 来自最近一次 .style list）。
        f.command(".style list");
        check(f.command(".style category #1 我的分类").contains("我的分类"), "命令设手动分类");
        equal("我的分类", f.categoryInFile("鸣濑白羽 1"), "手动分类落盘");
        LocalStyles.Style manual = styles.get("鸣濑白羽 1");
        equal("manual:我的分类", styles.classify(manual).key(), "手动分类的 key 前缀");
        equal(LocalStyles.KIND_MANUAL, styles.classify(manual).kind(), "手动分类的 kind");
        check(manual.hasCategory(), "手动分类记在样式里");
        // 覆盖保存不会把手动分类改回去（展示图样式同步走的就是这条路）。
        styles.save("鸣濑白羽 1", "a2", "", true, model("lora", "别的"), "");
        equal("我的分类", styles.categoryOf(styles.get("鸣濑白羽 1")), "覆盖保存保留手动分类");
        // 清空 → 回自动（用保存下来的新 model.lora）。
        check(f.command(".style category #1 -").contains("已清空手动分类"), "清空手动分类");
        equal("", f.categoryInFile("鸣濑白羽 1"), "清空后文件里没有分类字段");
        equal("别的", styles.categoryOf(styles.get("鸣濑白羽 1")), "清空后回到自动（model.lora）");
        // 老版本的自动分类名「LoRA 附带」不是用户能选的手动分类：给这个值等于回自动。
        equal("", LocalStyles.categoryName(LocalStyles.LORA_CATEGORY), "「LoRA 附带」不是手动分类名");
        styles.setCategory("鸣濑白羽 1", LocalStyles.LORA_CATEGORY);
        equal("", f.categoryInFile("鸣濑白羽 1"), "设成「LoRA 附带」＝回到自动规则");
        equal("别的", styles.categoryOf(styles.get("鸣濑白羽 1")), "设成「LoRA 附带」后仍是自动分类");
        // 网页接口的改分类走同一条路（回执里的分类名与接口一致）。
        JsonObject edited = f.bot.webStylesEdit("web", "category", "鸣濑白羽 1", null, false, false, "网页分类");
        check(edited.get("message").getAsString().contains("鸣濑白羽 1 → 网页分类"), "网页改分类回执：" + edited.get("message").getAsString());
        equal("网页分类", f.categoryInFile("鸣濑白羽 1"), "网页改分类落盘");
        JsonObject page = f.bot.webStyles();
        JsonObject item = page.getAsJsonArray("styles").get(0).getAsJsonObject();
        equal("网页分类", item.get("category").getAsString(), "接口里这条样式的分类名");
        equal("manual:网页分类", item.get("categoryKey").getAsString(), "接口里这条样式的 categoryKey");
        equal("manual", item.get("categorySource").getAsString(), "接口里这条样式的 categorySource");
        equal(false, item.get("categoryAuto").getAsBoolean(), "手动分类不是自动的");
    }

    /** 老数据：v1.0.12 给展示图样式自动写下的 category="LoRA 附带" 要当成"没手动设过"（读的时候现算，不落盘）。 */
    private static void oldLoraCategoryIsAutomatic(Fixture f) throws Exception {
        JsonObject legacy = style("Naruse 展示图 1", "a",
                model("lora", "Naruse_Shiroha_-_Summer_pockets_IL", "sizeSource", "preview"));
        legacy.addProperty("category", LocalStyles.LORA_CATEGORY);
        f.seed(List.of(legacy, style("没有分类的老样式 2", "b", model("sizeSource", "preview"))));
        byte[] before = f.localStylesBytes();
        LocalStyles styles = new LocalStyles(f.root);
        equal(false, styles.get("Naruse 展示图 1").hasCategory(), "老版本的自动分类名不算手动分类");
        equal("Naruse_Shiroha_-_Summer_pockets_IL", styles.categoryOf(styles.get("Naruse 展示图 1")), "老样式回到各自的 LoRA 分类");
        JsonObject page = f.bot.webStyles();
        JsonObject item = page.getAsJsonArray("styles").get(0).getAsJsonObject();
        equal(true, item.get("categoryAuto").getAsBoolean(), "老分类字段被当成自动");
        equal("", item.get("categoryStored").getAsString(), "categoryStored 里不再显示老分类名");
        equal("Naruse_Shiroha_-_Summer_pockets_IL", item.get("category").getAsString(), "接口给出的分类名");
        check(Arrays.equals(before, f.localStylesBytes()), "读接口不改写 data/local-styles.json");
    }

    /** 排序：lora 组（count 降序、同 count 名字升序）→ stack 组（固定栈序）→ manual 组 → none 最后。 */
    private static void orderingAndCategories(Fixture f) throws Exception {
        f.seed(List.of(
                style("Beta 图 1", "a", model("lora", "Beta")),
                style("Beta 图 2", "b", model("lora", "Beta")),
                style("alpha 图 1", "c", model("lora", "alpha")),
                style("alpha 图 2", "d", model("lora", "alpha")),
                style("Gamma 图 1", "e", model("lora", "Gamma")),
                style("SDXL 甲", "f", model("stack", "xl")),
                style("SDXL 乙", "g", model("stack", "xl")),
                style("Anima 甲", "h", model("stack", "anima")),
                style("手动分类的样式", "i", model("stack", "sd"), "我的分类"),
                style("手动分类的另一条", "j", null, "我的分类"),
                style("没着落", "k", null)));
        LocalStyles styles = new LocalStyles(f.root);
        List<String> keys = new ArrayList<>();
        for (LocalStyles.Group group : styles.categoryGroups()) keys.add(group.key());
        equal(List.of("lora:alpha", "lora:Beta", "lora:Gamma", "stack:anima", "stack:xl", "manual:我的分类", "none"),
                keys, "分组顺序：lora（count 降序、同 count 名字升序）→ stack（固定栈序）→ manual → none");
        List<Integer> counts = new ArrayList<>();
        for (LocalStyles.Group group : styles.categoryGroups()) counts.add(group.count());
        equal(List.of(2, 2, 1, 1, 2, 2, 1), counts, "各组的条数");
        equal(true, styles.categoryGroups().get(0).lora(), "lora 组的 lora 标记");
        equal(false, styles.categoryGroups().get(3).lora(), "栈组的 lora 标记");
        equal("Anima 栈", styles.categoryGroups().get(3).name(), "栈组的名字");
        equal("我的分类", styles.categoryOf(styles.get("手动分类的另一条")), "没有 model 参数时手动分类照样生效");
        equal("我的分类", styles.categoryOf(styles.get("手动分类的样式")), "手动分类优先于归属栈");
        equal(LocalStyles.OTHER_CATEGORY, styles.categoryOf(styles.get("没着落")), "没着落的是未分类");
        // 未分类永远排在最后（0 条也给这一组：criteriaPriority 里验证过 count=0 的情况）。
        equal(LocalStyles.NONE_KEY, styles.categoryGroups().get(6).key(), "未分类排最后");
        equal(1, styles.categoryGroups().get(6).count(), "未分类的条数");
    }

    /** {@code /api/styles}：categories 与每条样式的 categoryKey 必须一一对上，且读多少次都不改数据文件。 */
    private static void webApiContract(Fixture f) throws Exception {
        f.civitai("DeepSeek_ZipZipPipe_style_anima2b.safetensors.json",
                "DeepSeek 鲸鱼娘 | DeepSeek Whale Girl — ZipZipPipe style (Anima)",
                "F:\\sd\\models\\Lora\\DeepSeek_ZipZipPipe_style_anima2b.safetensors");
        Map<String, String> links = new LinkedHashMap<>();
        for (int number = 1; number <= 3; number++)
            links.put("2919135/3302634/DeepSeek_ZipZipPipe_style_anima2b.safetensors/" + number, "DeepSeek 风格 " + number);
        f.links(links);
        List<JsonObject> seeded = new ArrayList<>();
        for (int number = 1; number <= 3; number++)
            seeded.add(style("DeepSeek 风格 " + number, "deepseek " + number, model("baseModel", "Anima", "stack", "anima")));
        seeded.add(style("鸣濑白羽 1", "shiroha", model("lora", "Naruse_Shiroha_-_Summer_pockets_IL", "sizeSource", "preview")));
        seeded.add(style("鸣濑白羽 2", "shiroha2", model("lora", "Naruse_Shiroha_-_Summer_pockets_IL", "sizeSource", "preview")));
        seeded.add(style("SDXL 普通 甲", "xl", model("stack", "xl")));
        seeded.add(style("手动样式", "manual", model("stack", "flux"), "我的分类"));
        seeded.add(style("没着落", "none", null));
        f.seed(seeded);
        byte[] before = f.localStylesBytes();

        JsonObject page = f.bot.webStyles();
        equal(8, page.get("library").getAsInt(), "library 是样式总数");
        equal(8, page.getAsJsonArray("styles").size(), "styles 一条不少");
        // 每条样式的 categoryKey 都要在 categories 里，且名字与那一组一模一样（前端靠 key 持久化折叠状态）。
        Map<String, JsonObject> categories = new LinkedHashMap<>();
        for (JsonElement element : page.getAsJsonArray("categories")) {
            JsonObject item = element.getAsJsonObject();
            check(item.has("key") && item.has("name") && item.has("kind") && item.has("count") && item.has("lora"),
                    "categories 每项都有 key/name/kind/count/lora：" + item);
            check(categories.put(item.get("key").getAsString(), item) == null, "categories 的 key 不重复：" + item.get("key"));
        }
        for (JsonElement element : page.getAsJsonArray("styles")) {
            JsonObject item = element.getAsJsonObject();
            String key = item.get("categoryKey").getAsString();
            JsonObject group = categories.get(key);
            check(group != null, "样式的 categoryKey 必须在 categories 里：" + item.get("name") + " → " + key);
            equal(group.get("name").getAsString(), item.get("category").getAsString(), "分类名与那组一致（" + item.get("name") + "）");
            equal(group.get("kind").getAsString(), item.get("categorySource").getAsString(), "categorySource 与 kind 一致（" + item.get("name") + "）");
            equal(group.get("lora").getAsBoolean(), LocalStyles.KIND_LORA.equals(item.get("categorySource").getAsString()),
                    "lora 标记与 kind 一致");
        }
        List<String> keys = new ArrayList<>(categories.keySet());
        equal(List.of("lora:DeepSeek 鲸鱼娘 | DeepSeek Whale Girl — ZipZipPipe style (Anima)",
                        "lora:Naruse_Shiroha_-_Summer_pockets_IL",
                        "stack:xl",
                        "manual:我的分类",
                        "none"),
                keys, "categories 顺序与 key（lora 组按 count 降序 → stack → manual → none）");
        equal(3, categories.get(keys.get(0)).get("count").getAsInt(), "DeepSeek 那个 LoRA 3 条");
        equal(2, categories.get(keys.get(1)).get("count").getAsInt(), "鸣濑白羽那个 LoRA 2 条");
        equal(1, categories.get(keys.get(2)).get("count").getAsInt(), "SDXL 栈 1 条");
        equal(1, categories.get("none").get("count").getAsInt(), "「没着落」那一条进了未分类");
        equal("none", categories.get("none").get("kind").getAsString(), "未分类的 kind");
        // 再读一次结果一样，而且数据文件一个字节都没被改。
        JsonObject again = f.bot.webStyles();
        equal(page.toString(), again.toString(), "两次读接口结果一致");
        check(Arrays.equals(before, f.localStylesBytes()), "/api/styles 不改写 data/local-styles.json");
    }

    // ---------------------------------------------------------------- 小工具

    private static JsonObject style(String name, String positive, JsonObject model) {
        return style(name, positive, model, null);
    }

    private static JsonObject style(String name, String positive, JsonObject model, String category) {
        JsonObject item = new JsonObject();
        item.addProperty("name", name);
        item.addProperty("positive", positive);
        item.addProperty("negative", "");
        item.addProperty("updated_at", "2026-01-01T00:00:00Z");
        if (category != null && !category.isEmpty()) item.addProperty("category", category);
        if (model != null) item.add("model", model);
        return item;
    }

    /** 键值对拼一份 model（数字按数字写，其余按字符串）。 */
    private static JsonObject model(Object... pairs) {
        JsonObject model = new JsonObject();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            String key = String.valueOf(pairs[index]);
            Object value = pairs[index + 1];
            if (value instanceof Number number) model.addProperty(key, number);
            else model.addProperty(key, String.valueOf(value));
        }
        return model;
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    /** 一个可用的机器人（真 SdClient + 桩 WebUI），样式库与分类用的外部文件都在临时目录里。 */
    private static final class Fixture implements AutoCloseable {
        final Path root;
        final HttpServer server;
        final SdClient client;
        final Bot bot;
        final BlockingQueue<String> replies = new LinkedBlockingQueue<>();

        Fixture() throws Exception {
            Path work = Path.of(System.getProperty("bot.test.work", "work"), "style-category-lora-tests").toAbsolutePath();
            Files.createDirectories(work);
            root = Files.createTempDirectory(work, "case-");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String route = exchange.getRequestURI().getPath();
                try (exchange) {
                    // 桩 WebUI：能读 options（机器人设置初始化要用），桥接一律 404（离线也要能用本机样式）。
                    String body = route.equals("/sdapi/v1/options") ? "{\"sd_model_checkpoint\":\"Model A [aaaa]\"}" : "{}";
                    int status = route.equals("/sdapi/v1/options") ? 200 : 404;
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception ignored) { /* 桩服务：读请求体失败也不影响测试 */ }
            });
            server.start();
            JsonObject sd = new JsonObject();
            sd.addProperty("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
            sd.addProperty("timeout_seconds", 2);
            JsonObject config = new JsonObject();
            config.add("sd", sd);
            config.addProperty("owner_user_id", "123");
            Json.atomicWrite(root.resolve("config.json"), config);
            client = new SdClient(root, sd);
            bot = new Bot(new Settings(root), client, (event, segments) -> {
                replies.add(Bot.messageText(segments));
                return CompletableFuture.completedFuture(null);
            });
        }

        /** 直接写一份 data/local-styles.json（模拟用户的真实数据文件）。 */
        void seed(List<JsonObject> styles) throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("version", 1);
            JsonArray array = new JsonArray();
            styles.forEach(array::add);
            data.add("styles", array);
            Json.atomicWrite(root.resolve("data/local-styles.json"), data);
        }

        /** 写一份展示图样式映射（data/civitai-style-links.json）：键 = 模型号/版本号/LoRA 文件名/图号。 */
        void links(Map<String, String> entries) throws Exception {
            JsonObject data = new JsonObject();
            for (Map.Entry<String, String> entry : entries.entrySet()) data.addProperty(entry.getKey(), entry.getValue());
            Json.atomicWrite(root.resolve("data/civitai-style-links.json"), data);
        }

        /** 写一份 Civitai 下载记录（data/civitai/<file>.json）：分类名就是这里的 model_name。 */
        void civitai(String fileName, String modelName, String loraPath) throws Exception {
            JsonObject record = new JsonObject();
            record.addProperty("model_name", modelName);
            record.addProperty("original_filename", Path.of(loraPath).getFileName().toString());
            record.addProperty("path", loraPath);
            Json.atomicWrite(root.resolve("data/civitai").resolve(fileName), record);
        }

        byte[] localStylesBytes() throws Exception {
            return Files.readAllBytes(root.resolve("data/local-styles.json"));
        }

        /** 文件里这条样式记着的分类（没有该字段＝空串）。 */
        String categoryInFile(String name) throws Exception {
            JsonObject data = Json.parse(Files.readString(root.resolve("data/local-styles.json"), StandardCharsets.UTF_8));
            for (JsonElement item : data.getAsJsonArray("styles")) {
                JsonObject style = item.getAsJsonObject();
                if (style.get("name").getAsString().equals(name)) return Json.str(style, "category", "");
            }
            throw new AssertionError("样式库里没有「" + name + "」");
        }

        String command(String text) throws Exception {
            JsonObject event = new JsonObject();
            event.addProperty("post_type", "message");
            event.addProperty("message_type", "private");
            event.addProperty("self_id", 777);
            event.addProperty("user_id", 123);
            event.addProperty("message_id", System.nanoTime());
            event.add("message", Maps.text(text));
            replies.clear();
            bot.accept(event);
            String reply = replies.poll(7, TimeUnit.SECONDS);
            check(reply != null, "命令要有回执：" + text);
            return reply;
        }

        public void close() { bot.close(); server.stop(0); }
    }
}

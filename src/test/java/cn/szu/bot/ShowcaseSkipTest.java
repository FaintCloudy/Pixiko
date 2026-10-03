package cn.szu.bot;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import cn.szu.bot.civitai.CivitaiClient;
import cn.szu.bot.civitai.CivitaiStyleSync;
import cn.szu.bot.sd.SdClient;

/**
 * 展示图样式「跳过」的分类计数：不能再只报一个「跳过 9」。
 *
 * <p>用例形状来自真机：某个 LoRA 的 9 张展示图在 Civitai 上**根本没有提示词元数据**
 * （实测 metadata 里连 meta 字段都没有），所以只能如实跳过并说明原因——绝不伪造提示词。
 * 另外覆盖"同名已存在 / 没有对应样式（不新建）""修正覆盖同名样式""上限可配置"。
 */
public final class ShowcaseSkipTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        noPromptImagesAreClassified();
        nameTakenVersusNoMatch();
        correctionOverwritesSameName();
        limitIsConfigurable();
        System.out.println("ShowcaseSkipTest: " + assertions + " assertions passed: skip reasons classified by cause, honest no-prompt message, name-taken vs no-match, correction path, configurable limit.");
    }

    /** 真机形状：9 张展示图全都没有提示词元数据（作者没传 A1111/ComfyUI 参数）。 */
    private static void noPromptImagesAreClassified() throws Exception {
        Path root = tempRoot("showcase-noprompt-");
        try {
            List<CivitaiClient.ShowcasePrompt> images = new ArrayList<>();
            for (int number = 1; number <= 9; number++)
                images.add(new CivitaiClient.ShowcasePrompt(number, "", "", false,
                        "元数据里没有可读取的提示词（A1111 与 ComfyUI 都查过了）", "https://image.civitai.com/x/" + number + ".jpeg"));
            CivitaiClient.DownloadedLora download = new CivitaiClient.DownloadedLora(
                    "summer pockets-tsumugi wenders", "tsumugi1.0", "SD 1.5", List.of("tsumugi wenders"),
                    root.resolve("tsumugiANY2.0.safetensors"), true, 24552, 29367, images);
            Memory store = new Memory();
            CivitaiStyleSync.Outcome outcome = CivitaiStyleSync.run(root, download, "<lora:tsumugiANY2.0:1>", store, true, null, null);

            equal(9, outcome.skipped(), "9 张全跳过");
            equal(9, outcome.skips().get(CivitaiStyleSync.SKIP_NO_PROMPT), "9 张的原因都是「该图没有提示词元数据」");
            equal(1, outcome.skips().size(), "只有一种原因，不该混进别的分类：" + outcome.skips());
            equal(0, outcome.saved(), "一条样式都没建");
            check(store.styles.isEmpty(), "没有提示词就不许编一条样式出来：" + store.styles);

            String summary = Bot.showcaseSummary(outcome.text());
            check(summary.contains("跳过 9（该图没有提示词元数据 9）"), "回执按原因分类计数：" + summary);
            check(summary.contains("做不成样式"), "回执说清后果（不会伪造提示词）：" + summary);
            check(!summary.contains("同名"), "没有同名时不提同名：" + summary);
            check(outcome.text().contains("不会伪造提示词"), "详细提示也说明不伪造提示词：" + outcome.text());
            check(outcome.text().contains("提示（" + CivitaiStyleSync.SKIP_NO_PROMPT + "）"), "按原因给出可操作提示行：" + outcome.text());
        } finally { delete(root); }
    }

    /** 不新建的重跑路径：名字被内容不同的样式占了 ≠ 压根没有对应样式，两类要分开计数。 */
    private static void nameTakenVersusNoMatch() throws Exception {
        Path root = tempRoot("showcase-nametaken-");
        try {
            Memory store = new Memory();
            store.styles.put("模型 1", new SdClient.StylePrompt("模型 1", "another lora's prompt", "neg"));
            CivitaiClient.DownloadedLora download = new CivitaiClient.DownloadedLora("模型", "v1", "SD 1.5", List.of(),
                    root.resolve("model.safetensors"), true, 7, 8, List.of(
                    new CivitaiClient.ShowcasePrompt(1, "fresh one", "neg", true, ""),
                    new CivitaiClient.ShowcasePrompt(2, "fresh two", "neg", true, "")));

            CivitaiStyleSync.Outcome skipped = CivitaiStyleSync.run(root, download, "<lora:model:1>", store, false, null, null);
            equal(2, skipped.skipped(), "同名与无匹配各一条，共跳过 2");
            equal(1, skipped.skips().get(CivitaiStyleSync.SKIP_NAME_TAKEN), "名字被占（内容不同）算「同名已存在」");
            equal(1, skipped.skips().get(CivitaiStyleSync.SKIP_NO_MATCH), "没有对应样式算「本次不新建」");
            String summary = Bot.showcaseSummary(skipped.text());
            check(summary.contains("跳过 2（同名样式内容不同 1、该图没有对应样式且本次不新建 1）"),
                    "两类跳过分别计数：" + summary);
            check(skipped.text().contains(".lora cover"), "同名/无匹配都要给出可操作路径（.lora cover 修正或新建）：" + skipped.text());
            equal("another lora's prompt", store.styles.get("模型 1").positive(), "不新建模式下绝不改写已有样式");

            // 换成新建模式：同名不再"跳过"，而是挑一个空号建出来（用户手改过的样式原样保留）。
            CivitaiStyleSync.Outcome created = CivitaiStyleSync.run(root, download, "<lora:model:1>", store, true, null, null);
            equal(0, created.skipped(), "新建模式下同名不再是跳过理由：" + created.skips());
            equal(2, created.saved(), "两张都建出来了");
            equal("another lora's prompt", store.styles.get("模型 1").positive(), "用户手改过的同名样式保持原样");
            equal(3, store.styles.size(), "另外挑了两个空号");
        } finally { delete(root); }
    }

    /** 同名但内容不同 + 已知映射：走现有「修正」路径覆盖它，旧内容先备份。 */
    private static void correctionOverwritesSameName() throws Exception {
        Path root = tempRoot("showcase-correct-");
        try {
            Memory store = new Memory();
            store.styles.put("模型 1", new SdClient.StylePrompt("模型 1", "old prompt, <lora:stale:1>", "old negative"));
            CivitaiClient.DownloadedLora download = new CivitaiClient.DownloadedLora("模型", "v1", "SD 1.5", List.of(),
                    root.resolve("model.safetensors"), true, 7, 8, List.of(
                    new CivitaiClient.ShowcasePrompt(1, "fresh prompt", "new negative", true, "")));
            // 展示图 ↔ 样式 的映射（网页/下载自动写的那份）：明确说第 1 张对应「模型 1」。
            JsonObject links = new JsonObject();
            links.addProperty("7/8/model.safetensors/1", "模型 1");
            Files.createDirectories(root.resolve("data"));
            Json.atomicWrite(root.resolve("data/civitai-style-links.json"), links);

            CivitaiStyleSync.Outcome outcome = CivitaiStyleSync.run(root, download, "<lora:model:2>", store, true, null, null);
            equal(1, outcome.corrected(), "同名内容不同走「修正」计数（不新造一个 overwrite 语义）");
            equal(0, outcome.saved(), "不是新增");
            equal(0, outcome.skipped(), "修正过的就不该再算跳过：" + outcome.skips());
            // 修正的语义是"只换 LoRA 标签，保留样式里已有的提示词"（用户手动改过的内容不被展示图覆盖）。
            equal("old prompt, <lora:model:2>", store.styles.get("模型 1").positive(), "LoRA 标签换成当前本机标签");
            equal("old negative", store.styles.get("模型 1").negative(), "反向提示词保持原样");
            equal(1, store.overwrites.getOrDefault("模型 1", 0), "覆盖写入一次（overwrite=true）");
            try (var backups = Files.list(root.resolve("data/civitai-style-backups"))) {
                equal(1L, backups.count(), "旧内容先备份，可回查");
            }
        } finally { delete(root); }
    }

    /** 每个 LoRA 建多少条样式可配置：超出的按「超出上限」计数并提示怎么调大。 */
    private static void limitIsConfigurable() throws Exception {
        Path root = tempRoot("showcase-limit-");
        try {
            Memory store = new Memory();
            CivitaiClient.DownloadedLora download = new CivitaiClient.DownloadedLora("模型", "v1", "SD 1.5", List.of(),
                    root.resolve("model.safetensors"), true, 7, 8, List.of(
                    new CivitaiClient.ShowcasePrompt(1, "one", "neg", true, ""),
                    new CivitaiClient.ShowcasePrompt(2, "two", "neg", true, ""),
                    new CivitaiClient.ShowcasePrompt(3, "three", "neg", true, "")));

            CivitaiStyleSync.Outcome capped = CivitaiStyleSync.run(root, download, "<lora:model:1>", store, true, null, null, 2);
            equal(2, capped.saved(), "上限 2：只建前 2 条样式");
            equal(1, capped.skipped(), "第 3 条被上限挡住，但**要计数**而不是静默丢掉");
            equal(1, capped.skips().get(CivitaiStyleSync.SKIP_OVER_LIMIT), "原因是「超出展示图样式上限」");
            String summary = Bot.showcaseSummary(capped.text());
            check(summary.contains("超出展示图样式上限 1"), "回执写明超上限的数量：" + summary);
            check(summary.contains("civitai.showcase_limit"), "回执告诉用户上限在哪配：" + summary);
            equal(2, store.styles.size(), "上限之内落盘 2 条");

            CivitaiStyleSync.Outcome unlimited = CivitaiStyleSync.run(root, download, "<lora:model:1>", store, true, null, null, 0);
            equal(1, unlimited.saved(), "调成不限（0）后，缺的那条补建出来");
            equal(2, unlimited.reused(), "已有的两条是复用，不重复写");
            check(unlimited.skips().isEmpty(), "不限时没有超上限的跳过：" + unlimited.skips());

            JsonObject config = new JsonObject();
            equal(0, CivitaiClient.showcaseLimit(config), "没配 showcase_limit 时默认不限");
            config.addProperty("showcase_limit", 3);
            equal(3, CivitaiClient.showcaseLimit(config), "配了就用配置值");
            config.addProperty("showcase_limit", 0);
            equal(0, CivitaiClient.showcaseLimit(config), "0 表示不限");
            config.addProperty("showcase_limit", -5);
            equal(0, CivitaiClient.showcaseLimit(config), "负数按不限处理");
            config.addProperty("showcase_limit", 100000);
            equal(1000, CivitaiClient.showcaseLimit(config), "上限有封顶，避免一次写爆样式库");
            config.addProperty("showcase_limit", "五");
            equal(0, CivitaiClient.showcaseLimit(config), "乱填退回不限而不是崩掉");
        } finally { delete(root); }
    }

    private static final class Memory implements CivitaiStyleSync.Store {
        final Map<String, SdClient.StylePrompt> styles = new LinkedHashMap<>();
        final Map<String, Integer> overwrites = new HashMap<>();
        public List<SdClient.StylePrompt> list() { return List.copyOf(styles.values()); }
        public void save(String name, String positive, String negative, boolean overwrite) {
            // 新增必须是 overwrite=false、已存在必须是 overwrite=true，别把别人的提示词悄悄盖掉。
            if (overwrite != styles.containsKey(name)) throw new AssertionError("overwrite 标记与是否已存在不一致：" + name);
            if (overwrite) overwrites.merge(name, 1, Integer::sum);
            styles.put(name, new SdClient.StylePrompt(name, positive, negative));
        }
    }

    private static Path tempRoot(String prefix) throws IOException {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "showcase-skip-tests").toAbsolutePath();
        Files.createDirectories(work);
        return Files.createTempDirectory(work, prefix);
    }

    private static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }

    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}

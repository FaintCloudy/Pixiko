package cn.szu.bot.testsupport;

import cn.szu.bot.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 测试专用的**最小词库夹具**：在本次 run 的临时根里写一份 {@code data/prompt-tags.txt}、
 * {@code data/prompt-usage.json}、{@code data/prompt-zh-tags.json}，内容是这里逐条声明的标准词条子集。
 *
 * <h2>为什么不再拷生产词库</h2>
 * 用户第 14 节要求自动化测试**不读写生产数据**（{@code F:\Bot\config.json}、{@code data/**}、{@code logs/**}、
 * {@code maps/**}）。老用例为了拿到 "soft_focus / grass / 军装" 这些词条，用"当前目录"去解析
 * 仓库根下的真实 {@code data/prompt-tags.txt}（2.3 MB）与 {@code data/prompt-usage.json}（475 KB）——
 * 那既是生产数据、又让测试结果随线上词库漂移。
 *
 * <p>现在改成：测试启动时把**它自己需要的那几十条**写进测试根，被测代码通过已有的可注入入口
 * （{@code Settings(root)} / {@code Bot.vocabulary(root, …)} / {@code new PromptUsage(root)}）读测试根里的这份。
 * 词条内容是本文件里的字面量，不是生产文件的副本：测试从此与线上词库解耦，也不可能读到生产数据。
 *
 * <h2>词条口径</h2>
 * <ul>
 *   <li>{@link #TAGS}：{@code prompt-tags.txt} 的行。{@code Bot.missingRequestedTags} 只在候选词条出现在
 *       这份白名单里时才补（草地 → grass 等），所以这里必须覆盖用例断言到的每个标准词条。</li>
 *   <li>{@link #USAGE_JSON}：{@code prompt-usage.json}（版本 1，{@code PromptUsage} 的必需文件）。只是
 *       一份可浏览的小分类树，让被测代码拿到"有词库"的正常路径，而不是缺文件时的降级路径。</li>
 *   <li>{@link #LIBRARY_JSON}：{@code prompt-zh-tags.json}（可选中文库）。分类下标与
 *       {@link #LIBRARY_CATEGORIES} 对齐，{@code multiple views} 明确归"镜头"——{@code CompoundCategoryTest}
 *       要断言"词库优先时复合短语仍不被拉回镜头"，这份小词库让该断言真的走词库路径。</li>
 * </ul>
 */
public final class VocabularyFixture {

    /** 夹具来源标记：写进 JSON 的 {@code source}，任何报告里一眼能看出"这不是生产词库"。 */
    public static final String SOURCE = "测试夹具（VocabularyFixture 手写子集，不是生产 data 的副本）";

    /** {@code prompt-usage.json} 的分类名（顺序即 {@code prompt-zh-tags.json} 里的分类下标）。 */
    public static final List<String> LIBRARY_CATEGORIES =
            List.of("人物", "镜头", "场景", "动作", "环境", "服装", "画面");

    /** {@code prompt-tags.txt} 的全部标准词条（本案需要的最小集合）。 */
    public static final List<String> TAGS = List.of(
            "1girl",
            "solo",
            "grass",
            "grasslands",
            "meadow",
            "lawn",
            "forest",
            "tree",
            "military_uniform",
            "school_uniform",
            "white_coat",
            "maid",
            "heavy_breathing",
            "walking",
            "running",
            "exhausted",
            "soft_focus",
            "light_rays");

    /** {@code prompt-zh-tags.json} 的条目数（{@code PromptUsage.librarySize()} 的期望值）。 */
    public static final int LIBRARY_ENTRIES = 11;

    private VocabularyFixture() {}

    /** 把词库夹具写进 {@code root/data/}；返回写出的三个文件（调用方可直接断言它们在测试根里）。 */
    public static List<Path> install(Path root) throws IOException {
        Path data = root.resolve("data");
        Files.createDirectories(data);
        Path tags = data.resolve("prompt-tags.txt");
        Path usage = data.resolve("prompt-usage.json");
        Path library = data.resolve("prompt-zh-tags.json");
        Files.writeString(tags, String.join("\n", TAGS) + "\n", StandardCharsets.UTF_8);
        Json.atomicWrite(usage, Json.parse(USAGE_JSON));
        Json.atomicWrite(library, Json.parse(LIBRARY_JSON));
        return List.of(tags, usage, library);
    }

    /**
     * 硬断言：本次测试用的根必须落在 {@code <生产根>/work/tests} 之下，且不是生产根本身。
     *
     * <p>三个 legacy 用例以前直接读 {@code F:\Bot\data}；加这道断言后，"夹具装到生产目录里" 会立即失败，
     * 而不是悄悄污染线上数据。
     */
    public static void requireTestRoot(Path root) throws IOException {
        Path target = root.toAbsolutePath().normalize();
        Path production = TestEnvironment.productionRoot();
        Path runs = production.resolve("work/tests");
        if (!target.startsWith(runs) || target.equals(runs)) {
            throw new IllegalStateException("测试隔离守卫：词库夹具只能装进 " + runs + "\\<run-id>\\…，收到的是 "
                    + target + "。");
        }
        for (String guarded : List.of("config.json", "data", "logs", "maps")) {
            Path forbidden = production.resolve(guarded).normalize();
            if (target.equals(forbidden) || target.startsWith(forbidden)) {
                throw new IllegalStateException("测试隔离守卫：拒绝把词库夹具写进生产路径 " + forbidden + "。");
            }
        }
    }

    /** 断言夹具文件确实在测试根里（不是指向生产的那条路径）。 */
    public static void requireInstalled(Path root, List<Path> files) {
        Path base = root.toAbsolutePath().normalize();
        for (Path file : files) {
            Path target = file.toAbsolutePath().normalize();
            if (!target.startsWith(base)) {
                throw new IllegalStateException("测试隔离守卫：夹具文件 " + target + " 不在测试根 " + base + " 里。");
            }
            if (!Files.isRegularFile(target)) {
                throw new IllegalStateException("测试隔离守卫：夹具文件没有写出来：" + target);
            }
        }
    }

    /** {@code prompt-usage.json}：版本 1 的最小分类树（{@code PromptUsage} 必需的文件）。 */
    private static final String USAGE_JSON = """
            {
              "version": 1,
              "source": "%s",
              "categories": [
                {"name": "人物", "tags": [{"prompt": "1girl", "meaning": "一个女孩"},
                                          {"prompt": "maid", "meaning": "女仆装"}], "children": []},
                {"name": "镜头", "tags": [{"prompt": "multiple views", "meaning": "多视角"},
                                          {"prompt": "close-up", "meaning": "特写"},
                                          {"prompt": "from behind", "meaning": "从背后"}], "children": []},
                {"name": "场景", "tags": [{"prompt": "grass", "meaning": "草地"},
                                          {"prompt": "forest", "meaning": "森林"}], "children": []},
                {"name": "动作", "tags": [{"prompt": "walking", "meaning": "行走"},
                                          {"prompt": "running", "meaning": "奔跑"}], "children": []},
                {"name": "环境", "tags": [{"prompt": "exhausted", "meaning": "疲惫"},
                                          {"prompt": "heavy_breathing", "meaning": "喘气"}], "children": []},
                {"name": "服装", "tags": [{"prompt": "military_uniform", "meaning": "军装"},
                                          {"prompt": "school_uniform", "meaning": "校服"},
                                          {"prompt": "white_coat", "meaning": "白大褂"}], "children": []},
                {"name": "画面", "tags": [{"prompt": "soft_focus", "meaning": "柔焦"}], "children": []}
              ]
            }
            """.formatted(SOURCE);

    /**
     * {@code prompt-zh-tags.json}：中文写法 → 标准词条（{@code [tag, 别名, 分类下标, 热度]}）。
     *
     * <p>只放本批用例真正会用到的条目；{@code multiple views} 归"镜头"（下标 1），
     * 复合短语（{@code standing sex from behind} 等）故意**不**入表——它们由英文词表启发式判成"其他"，
     * 用例正是要断言"就算有词库，也不会把复合短语整条拉回镜头"。
     */
    private static final String LIBRARY_JSON = """
            {
              "version": 1,
              "source": "%s",
              "categories": ["人物", "镜头", "场景", "动作", "环境", "服装", "画面"],
              "entries": [
                ["multiple views", "多视角|多角度", 1, 9000],
                ["close-up", "特写|近景", 1, 8000],
                ["from behind", "从背后", 1, 7000],
                ["maid", "女仆装|女仆", 0, 6000],
                ["grass", "草地|草坪", 2, 5000],
                ["military_uniform", "军装|军服", 5, 4000],
                ["heavy_breathing", "喘气|喘息", 4, 3000],
                ["walking", "行走|步行", 3, 2000],
                ["exhausted", "疲惫|精疲力尽", 4, 1500],
                ["soft_focus", "柔焦|柔和光线", 6, 1200],
                ["white_coat", "白大褂", 5, 1000]
              ]
            }
            """.formatted(SOURCE);

    /** 读回夹具（给需要断言"测试根里那份就是夹具"的用例用）。 */
    public static JsonObject readUsage(Path root) throws IOException {
        return Json.parse(Files.readString(root.resolve("data/prompt-usage.json"), StandardCharsets.UTF_8));
    }

    /** 夹具里声明的词条名（供断言 {@code allowedContains}）。 */
    public static JsonArray tagsJson() {
        JsonArray array = new JsonArray();
        for (String tag : TAGS) array.add(tag);
        return array;
    }

    /**
     * 夹具词条在 {@code Bot.vocabulary} 里的**键形态**：{@code PromptEditor.key} 会把下划线换成空格
     * （{@code soft_focus} → {@code soft focus}），所以断言"词库里有这些词条"要用这份集合，不能用 {@link #TAGS}。
     */
    public static java.util.Set<String> keys() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String tag : TAGS) keys.add(cn.szu.bot.prompt.PromptEditor.key(tag));
        return keys;
    }
}

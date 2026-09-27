package cn.szu.bot;

import cn.szu.bot.prompt.TagSuggest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;

/**
 * 提示词 tab 补全（{@link TagSuggest}）：英文前缀、空格写法、中文说法、热度排序、
 * 词库外词条、limit、空查询、词库全缺时的退化，以及按 mtime 重载。
 */
public final class TagSuggestTest {

    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("bot.test.work", "work"), "tag-suggest-tests").toAbsolutePath();
        Files.createDirectories(work);
        Path root = Files.createDirectory(work.resolve("case-" + System.nanoTime()));
        Files.createDirectories(root.resolve("data"));
        Path vocabulary = root.resolve("data/prompt-tags.txt");
        write(vocabulary, """
                # 测试词表
                1girl
                1girls
                hair_ornament
                long_hair
                obscure_tag_zzz
                short_hair
                vocabulary_only_tag_qqq
                """);
        write(root.resolve("data/prompt-usage.json"), """
                {"version":1,"source":"test","categories":[{"name":"测试","tags":[{"prompt":"1girl","meaning":"一个女孩"}],"children":[]}]}
                """);
        write(root.resolve("data/prompt-zh-tags.json"), """
                {"version":1,"categories":["人物","服装"],"entries":[
                  ["1girl","一个女孩|少女",0,426664,""],
                  ["long_hair","长发",0,120000,""],
                  ["short_hair","短发",0,80000,""],
                  ["obscure_tag_zzz","冷门标签",1,12,""]
                ]}
                """);
        TagSuggest suggest = new TagSuggest(root);

        List<TagSuggest.Hint> prefix = suggest.suggest("1gi", 10);
        check(prefix.size() == 2, "前缀命中两条：1girl 与 1girls（实际 " + prefix.size() + "）");
        check(prefix.get(0).tag().equals("1girl"), "按热度排序：1girl 在 1girls 之前（实际 " + prefix.get(0).tag() + "）");
        check(prefix.get(0).zh().equals("一个女孩、少女"), "候选带中文写法（取前两个）：" + prefix.get(0).zh());
        check(prefix.get(0).category().equals("人物"), "候选带分类：" + prefix.get(0).category());
        check(prefix.get(0).rank() == 426664, "候选带热度：" + prefix.get(0).rank());

        check(suggest.suggest("long hair", 5).get(0).tag().equals("long_hair"), "空格写法等价于下划线");
        check(suggest.suggest("长发", 5).stream().anyMatch(hint -> hint.tag().equals("long_hair")), "中文说法也能补出词条");
        check(suggest.suggest("1girl", 5).get(0).tag().equals("1girl"), "完全相同的词条排最前");
        check(suggest.suggest("1gi", 1).size() == 1, "limit 生效");
        check(suggest.suggest("hair", 10).size() == 3, "hair 相关的三条都能补出来");

        List<TagSuggest.Hint> outside = suggest.suggest("vocabulary_only", 5);
        check(outside.size() == 1 && outside.get(0).tag().equals("vocabulary_only_tag_qqq"),
                "只存在于标准词表（中文词库里没有）的词条也能补出来");
        check(outside.get(0).zh().isEmpty() && outside.get(0).rank() == 0, "这类词条没有中文与热度，但仍然是候选");
        check(suggest.suggest("没有这个词", 5).isEmpty(), "查不到时返回空列表而不是报错");

        List<TagSuggest.Hint> hottest = suggest.suggest("", 2);
        check(hottest.size() == 2 && hottest.get(0).tag().equals("1girl"), "空查询返回最热的词条（Ctrl+Space 用）");

        // 词表被重新生成（mtime 变化）后要能跟上，不能一直用旧的缓存。
        write(vocabulary, Files.readString(vocabulary, StandardCharsets.UTF_8) + "brand_new_tag\n");
        Files.setLastModifiedTime(vocabulary, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        check(suggest.suggest("brand_new", 5).size() == 1, "词表换了以后按 mtime 重载");

        Path bare = Files.createDirectory(work.resolve("bare-" + System.nanoTime()));
        Files.createDirectories(bare.resolve("data"));
        check(new TagSuggest(bare).suggest("1gi", 5).isEmpty(), "两个词库都不在时返回空列表，不抛异常");

        cleanup(work);
        System.out.println("TagSuggestTest: " + assertions + " assertions passed.");
    }

    private static void write(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void cleanup(Path work) {
        try (var paths = Files.walk(work)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (Exception ignored) { }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}

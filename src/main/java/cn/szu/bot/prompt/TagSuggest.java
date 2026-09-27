package cn.szu.bot.prompt;

import cn.szu.bot.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 提示词输入框的 tab 补全：把用户正在敲的那个词补成标准 Danbooru 词条。
 *
 * <p>两个来源各管一段，合起来覆盖「英文词条」与「中文说法」两条输入路径：
 *
 * <ul>
 *   <li>{@code data/prompt-tags.txt}（14 万条标准词表）——按<b>前缀</b>匹配词条名，
 *       所以生僻词也能补出来；用一次排序好的数组 + 二分查找，不走全表扫描。</li>
 *   <li>{@code data/prompt-zh-tags.json}（{@link PromptUsage} 的内置词库，3.7 万条）——提供<b>中文写法</b>、
 *       分类与热度，也是「敲中文补词条」那条路径。</li>
 * </ul>
 *
 * <p>排序：完全相同的词条优先，其次按热度（Danbooru 出现次数）从高到低，最后按词条名，这样
 * 「1gi」先补 {@code 1girl} 而不是冷门词。两个词库都<b>按文件 mtime 缓存</b>（{@link PromptUsage} 的构造
 * 要建几万条索引，绝不能每个按键重建一次）；文件缺失或损坏时退化成只剩另一侧可用，不抛异常、不挡输入。
 */
public final class TagSuggest {

    /** 一条候选：标准词条、中文写法（可能为空）、分类（可能为空）、热度、是否由中文/别名命中。 */
    public record Hint(String tag, String zh, String category, long rank, boolean alias) { }

    /** 一次前缀扫描最多看多少条，避免单字母查询把时间耗在几万条候选上。 */
    private static final int PREFIX_SCAN_LIMIT = 20000;
    private static final int MAX_LIMIT = 50;
    /** 还没试过加载的哨兵值（与"文件不存在"的 0 区分开，避免每次按键都重试并刷日志）。 */
    private static final long UNTRIED = -2;

    private final Path root;
    private volatile String[] vocabulary = new String[0];
    private volatile long vocabularyStamp = UNTRIED;
    private volatile PromptUsage usage;
    private volatile long usageStamp = UNTRIED;

    public TagSuggest(Path root) { this.root = root.toAbsolutePath().normalize(); }

    /**
     * 给定正在输入的词，返回最多 {@code limit} 条候选。
     *
     * @param query 当前光标前那个词（例如 {@code 1gi}、{@code long_hair}、{@code 女孩}）；空串返回最热的词条
     */
    public List<Hint> suggest(String query, int limit) {
        int cap = Math.max(1, Math.min(MAX_LIMIT, limit));
        String raw = query == null ? "" : query.strip();
        PromptUsage library = usage();
        Map<String, Hint> found = new LinkedHashMap<>();
        if (raw.isEmpty()) {
            if (library != null) for (PromptUsage.Entry entry : library.byCategory("", cap)) put(found, entry.tag(), entry);
            return List.copyOf(found.values());
        }
        // 标准词表按前缀补：二分找到第一段，再顺着往下走，直到不再是这个前缀（数组已排序）。
        String prefix = normalize(raw);
        String[] words = vocabulary();
        int scanned = 0;
        for (int at = lowerBound(words, prefix); at < words.length; at++) {
            String tag = words[at];
            if (!tag.startsWith(prefix)) break;
            put(found, tag, library == null ? null : library.entry(tag));
            if (++scanned >= PREFIX_SCAN_LIMIT) break;
        }
        // 中文说法与英文别名：词库里按含义搜索，和前缀匹配合并（同一条词条只留一份）。
        if (library != null) for (PromptUsage.Entry entry : library.search(raw, cap)) put(found, entry.tag(), entry);
        List<Hint> sorted = new ArrayList<>(found.values());
        String exact = prefix;
        sorted.sort(Comparator.comparing((Hint hint) -> !hint.tag().equalsIgnoreCase(exact))
                .thenComparing(Comparator.comparingLong(Hint::rank).reversed())
                .thenComparing(Hint::tag));
        return sorted.size() > cap ? List.copyOf(sorted.subList(0, cap)) : List.copyOf(sorted);
    }

    /** 已排序词表里第一个 ≥ prefix 的下标：前缀匹配的起点。 */
    private static int lowerBound(String[] words, String prefix) {
        int low = 0, high = words.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (words[mid].compareTo(prefix) < 0) low = mid + 1; else high = mid;
        }
        return low;
    }

    /** 词库条目 → 候选（中文取前两个写法，够看又不会把下拉撑爆）。 */
    private static void put(Map<String, Hint> found, String tag, PromptUsage.Entry entry) {
        if (tag == null || tag.isBlank()) return;
        if (entry == null) { found.putIfAbsent(tag, new Hint(tag, "", "", 0, false)); return; }
        found.put(tag, new Hint(tag, meaning(entry), entry.category(), entry.rank(), false));
    }

    private static String meaning(PromptUsage.Entry entry) {
        List<String> aliases = entry.aliases();
        if (aliases.isEmpty()) return "";
        return aliases.size() == 1 ? aliases.get(0) : aliases.get(0) + "、" + aliases.get(1);
    }

    /** 词条名统一成词表里的写法：小写、空格换下划线（用户敲 `long hair` 也要能补出 `long_hair`）。 */
    static String normalize(String query) {
        return query.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    /** 标准词表：一次读入、排序、缓存；文件变了（重新生成词库）才重读。 */
    private String[] vocabulary() {
        long stamp = stamp("data/prompt-tags.txt");
        if (vocabularyStamp == stamp) return vocabulary;
        synchronized (this) {
            if (vocabularyStamp == stamp) return vocabulary;
            try {
                Path file = root.resolve("data/prompt-tags.txt");
                List<String> words = new ArrayList<>();
                if (Files.isRegularFile(file))
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        String word = line.strip();
                        if (word.isEmpty() || word.startsWith("#")) continue;   // 首行是来源说明
                        words.add(word);
                    }
                words.sort(String::compareTo);
                vocabulary = words.toArray(new String[0]);
            } catch (Exception error) {
                vocabulary = new String[0];
                Log.warn("标准词表 data/prompt-tags.txt 读取失败，tab 补全只剩中文词库那条路径：" + error.getMessage());
            }
            vocabularyStamp = stamp;
            return vocabulary;
        }
    }

    /** 中文词库：构造一次要建几万条索引，所以缓存；坏了就返回 null（补全退化成英文前缀匹配）。 */
    private PromptUsage usage() {
        long stamp = stamp("data/prompt-usage.json", "data/prompt-zh-tags.json");
        if (usageStamp == stamp) return usage;
        synchronized (this) {
            if (usageStamp == stamp) return usage;
            try { usage = new PromptUsage(root); }
            catch (Exception error) {
                usage = null;
                Log.warn("中文词库不可用，tab 补全只用标准词表：" + error.getMessage());
            }
            usageStamp = stamp;
            return usage;
        }
    }

    /** 词库文件的"版本"：mtime 之和（缺一个就少一份；都不存在时是 0，仍会尝试一次）。 */
    private long stamp(String... relatives) {
        long total = 0;
        for (String relative : relatives) {
            try { total += Files.getLastModifiedTime(root.resolve(relative)).toMillis(); }
            catch (IOException ignored) { /* 文件不在：不参与版本 */ }
        }
        return total;
    }
}

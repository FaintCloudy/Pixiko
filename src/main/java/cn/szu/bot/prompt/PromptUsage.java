package cn.szu.bot.prompt;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import cn.szu.bot.Json;
import cn.szu.bot.Log;

/**
 * Read-only, offline navigation through the WebUI's named prompt categories, plus the built-in
 * "Chinese wording → standard tag" library in {@code data/prompt-zh-tags.json}.
 *
 * <p>分类词库（{@code data/prompt-usage.json}）供 {@code .usage} 逐级浏览；内置中文词库是 .infix 与前置拆解层
 * 的候选来源：中文说法越多，模型越容易直接抄到真实存在的标准词条，而不是自己编一个词库外的词。
 */
public final class PromptUsage {
    /** 一条可匹配的词条：标准词条 + 中文含义（内置词库条目的多个中文写法用空格连接）。 */
    private record Tag(String prompt, String meaning, long rank, boolean library) {}
    private record Category(String name, List<Category> children, List<Tag> tags) {}
    /** 内置中文词库条目：标准词条、中文写法、分类、danbooru 热度（越大越常用，用于排序）、用法标记。 */
    public record Entry(String tag, List<String> aliases, String category, long rank, List<String> flags) {}
    private static final java.util.regex.Pattern WORD = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,}");
    /** 一次匹配最多精细打分多少条候选；候选更多时按相关度截断，避免长句把时间耗在明显不相关的词条上。 */
    private static final int CANDIDATE_LIMIT = 4000;
    /** 命中次数不超过这个数的二字组算"冷门"：命中它的词条一律保留，不参与按热度截断。 */
    private static final int RARE_POSTINGS = 400;
    private final List<Category> roots;
    private final List<Tag> all = new ArrayList<>();
    private final List<Entry> library = new ArrayList<>();
    /** 词条 → 条目，供反查（依据审查、场景扩写过滤）。 */
    private final Map<String, Entry> libraryByTag = new HashMap<>();
    private final List<String> libraryCategories = new ArrayList<>();
    private String librarySource = "";
    private final String source;
    /** 中文二字组 → 词条下标；用于把候选范围从几万条缩到几十条。 */
    private final Map<String, int[]> gramIndex = new HashMap<>();
    /** 英文词（含中文含义里的英文）→ 词条下标。 */
    private final TreeMap<String, int[]> tokenIndex = new TreeMap<>();

    public PromptUsage(Path root) throws IOException {
        Path file = root.resolve("data/prompt-usage.json");
        if (!Files.isRegularFile(file)) throw new IOException("分类词库不存在，请检查 data/prompt-usage.json。");
        try {
            JsonObject saved = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (saved.get("version").getAsBigDecimal().intValueExact() != 1) throw new IOException("Unknown dictionary version");
            roots = read(saved.getAsJsonArray("categories"), 0);
            source = saved.get("source").getAsString();
        } catch (Exception e) { throw new IOException("无法读取 data/prompt-usage.json 分类词库，原文件未修改。", e); }
        flatten(roots);
        loadUseNotes(root);
        loadLibrary(root);
        buildIndex();
    }
    private void flatten(List<Category> nodes) {
        for (Category node : nodes) { all.addAll(node.tags()); flatten(node.children()); }
    }
    /** 内置中文词库是可选的：缺失或损坏时退化为原来的分类词库，不影响 .infix 的其他环节。 */
    private void loadLibrary(Path root) {
        Path file = root.resolve("data/prompt-zh-tags.json");
        if (!Files.isRegularFile(file)) return;
        try {
            JsonObject saved = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (saved.get("version").getAsBigDecimal().intValueExact() != 1) throw new IOException("Unknown library version");
            for (JsonElement name : saved.getAsJsonArray("categories")) libraryCategories.add(name.getAsString());
            for (JsonElement item : saved.getAsJsonArray("entries")) {
                JsonArray row = item.getAsJsonArray();
                String tag = row.get(0).getAsString();
                String aliases = row.get(1).getAsString();
                int category = row.get(2).getAsInt();
                long rank = row.size() > 3 ? row.get(3).getAsLong() : 0;
                List<String> flags = new ArrayList<>();
                if (row.size() > 4 && row.get(4).isJsonPrimitive())
                    for (String flag : row.get(4).getAsString().split(",")) if (!flag.isBlank()) flags.add(flag.strip());
                String name = category >= 0 && category < libraryCategories.size() ? libraryCategories.get(category) : "其他";
                List<String> words = new ArrayList<>();
                for (String alias : aliases.split("\\|")) if (!alias.isBlank()) words.add(alias);
                if (words.isEmpty()) continue;
                Entry entry = new Entry(tag, List.copyOf(words), name, rank, List.copyOf(flags));
                library.add(entry);
                libraryByTag.putIfAbsent(tag.toLowerCase(Locale.ROOT), entry);
                libraryByTag.putIfAbsent(tag.toLowerCase(Locale.ROOT).replace('_', ' '), entry);
                libraryByTag.putIfAbsent(tag.toLowerCase(Locale.ROOT).replace(' ', '_'), entry);
            }
            librarySource = saved.has("source") ? saved.get("source").getAsString() : "内置中文词库";
        } catch (Exception error) {
            library.clear(); libraryCategories.clear();
            Log.warn("内置中文词库 data/prompt-zh-tags.json 不可用（.infix 将退化为分类词库）：" + error);
        }
    }
    /** 分类词库 + 内置词库合并成可匹配的条目，并建立倒排索引。 */
    private void buildIndex() {
        Map<String, List<Integer>> grams = new HashMap<>();
        Map<String, List<Integer>> tokens = new TreeMap<>();
        for (Entry entry : library) all.add(new Tag(entry.tag(), String.join(" ", entry.aliases()), entry.rank(), true));
        for (int index = 0; index < all.size(); index++) {
            Tag tag = all.get(index);
            String text = tag.prompt() + " " + tag.meaning();
            Set<String> seenGrams = new HashSet<>(), seenTokens = new HashSet<>();
            for (int start = 0; start + 2 <= text.length(); start++) {
                String gram = text.substring(start, start + 2);
                if (!isHan(gram.charAt(0)) || !isHan(gram.charAt(1))) continue;
                if (seenGrams.add(gram)) grams.computeIfAbsent(gram, key -> new ArrayList<>()).add(index);
            }
            for (java.util.regex.Matcher matcher = WORD.matcher(text); matcher.find();) {
                String token = matcher.group().toLowerCase(Locale.ROOT);
                if (seenTokens.add(token)) tokens.computeIfAbsent(token, key -> new ArrayList<>()).add(index);
            }
        }
        for (Map.Entry<String, List<Integer>> item : grams.entrySet()) gramIndex.put(item.getKey(), toArray(item.getValue()));
        for (Map.Entry<String, List<Integer>> item : tokens.entrySet()) tokenIndex.put(item.getKey(), toArray(item.getValue()));
    }
    private static int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int index = 0; index < result.length; index++) result[index] = values.get(index);
        return result;
    }
    static boolean isHan(char value) { return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN; }
    /**
     * 缩小匹配范围：请求里的每个中文二字组、每个英文词都能直接查到候选下标。任何"含义包含请求片段"的
     * 条目至少包含请求的一个二字组，所以这样不会漏掉旧实现能命中的条目；命中二字组越多说明越相关，
     * 因此在候选过多时按"命中二字组数 + 词条热度"截断，只把最相关的一批交给后面的精细打分。
     * 索引查不到任何候选时退回全量扫描（例如纯英文请求或词库里确实没有的写法），保证行为与以前一致。
     */
    private List<Tag> candidates(String request) {
        Map<Integer, int[]> info = new HashMap<>();   // id -> {命中二字组数, 是否命中过冷门二字组}
        for (int start = 0; start + 2 <= request.length(); start++) {
            String gram = request.substring(start, start + 2);
            if (!isHan(gram.charAt(0)) || !isHan(gram.charAt(1))) continue;
            int[] postings = gramIndex.get(gram);
            if (postings == null) continue;
            boolean rare = postings.length <= RARE_POSTINGS;
            for (int id : postings) {
                int[] entry = info.computeIfAbsent(id, key -> new int[2]);
                entry[0]++;
                if (rare) entry[1] = 1;
            }
        }
        for (java.util.regex.Matcher matcher = WORD.matcher(request); matcher.find() && matcher.group().length() >= 3;) {
            String word = matcher.group().toLowerCase(Locale.ROOT);
            for (String key : tokenIndex.tailMap(word).keySet()) {
                if (!key.startsWith(word)) break;
                for (int id : tokenIndex.get(key)) {
                    int[] entry = info.computeIfAbsent(id, key2 -> new int[2]);
                    entry[0] += 2;
                }
            }
        }
        if (info.isEmpty()) return all;
        // 命中两个以上二字组、或命中了冷门二字组（"分镜""痴汉"这种只在少数词条里出现的说法）都算强相关。
        // 冷门命中先全部保留，再按热度补足其余候选：只按热度截断会把"分镜 → multiple views"这类
        // 冷门但准确的词条挤掉，留下热门却无关的词条（"分镜聚焦臀部接触部位"就只剩下 ass）。
        List<Integer> strong = new ArrayList<>(), weak = new ArrayList<>();
        for (Map.Entry<Integer, int[]> item : info.entrySet()) {
            if (info.get(item.getKey())[1] == 1) strong.add(item.getKey());
            else if (item.getValue()[0] >= 2) weak.add(item.getKey());
        }
        Comparator<Integer> byRelevance = Comparator
                .comparingInt((Integer id) -> info.get(id)[0]).reversed()
                .thenComparing(Comparator.comparingLong((Integer id) -> all.get(id).rank()).reversed());
        strong.sort(byRelevance);
        weak.sort(byRelevance);
        List<Integer> ids = new ArrayList<>(strong);
        for (int id : weak) {
            if (ids.size() >= CANDIDATE_LIMIT) break;
            ids.add(id);
        }
        if (ids.size() > CANDIDATE_LIMIT) ids = ids.subList(0, CANDIDATE_LIMIT);
        List<Tag> result = new ArrayList<>(ids.size());
        for (int id : ids) result.add(all.get(id));
        return result;
    }
    /**
     * English candidate tags for a request: tags whose stored Chinese meaning contains part of the request,
     * plus tags whose English form contains a word from the request. Only entries already allowed by the
     * caller's vocabulary are returned, so every hint is safe to use verbatim in a prompt.
     */
    public List<String> hints(String request, Set<String> allowed, int limit) {
        if (request == null || request.isBlank() || allowed == null || allowed.isEmpty() || limit < 1) return List.of();
        LinkedHashSet<String> grams = new LinkedHashSet<>();
        for (int start = 0; start < request.length(); start++)
            for (int size = 6; size >= 2; size--) {
                int end = start + size;
                if (end > request.length()) continue;
                String gram = request.substring(start, end).strip();
                if (gram.length() == size && gram.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN))
                    grams.add(gram);
            }
        LinkedHashSet<String> words = new LinkedHashSet<>();
        for (java.util.regex.Matcher matcher = WORD.matcher(request); matcher.find() && words.size() < 8;)
            words.add(matcher.group().toLowerCase(Locale.ROOT));
        if (grams.isEmpty() && words.isEmpty()) return List.of();
        List<Hit> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Tag tag : candidates(request)) {
            String meaning = tag.meaning() == null ? "" : tag.meaning();
            String lowered = meaning.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String gram : grams) if (meaning.contains(gram)) score = Math.max(score, gram.length());
            for (String word : words) if (lowered.contains(word) || tag.prompt().toLowerCase(Locale.ROOT).contains(word)) score = Math.max(score, 4);
            if (score < 2) continue;
            if (!meaning.isBlank() && meaning.strip().equalsIgnoreCase(request.strip())) score += 4;
            List<String> terms = new ArrayList<>();
            try {
                for (String term : PromptEditor.parts(tag.prompt())) if (allowed.contains(PromptEditor.key(term))) terms.add(term);
            } catch (IllegalArgumentException ignored) { continue; }
            if (terms.isEmpty()) continue;
            String hint = String.join(", ", terms);
            if (seen.add(hint)) hits.add(new Hit(hint, score, meaning.length(), tag.rank(), ""));
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed()
                .thenComparing(Comparator.comparingLong(Hit::rank).reversed())
                .thenComparingInt(Hit::meaningLength).thenComparing(Hit::hint));
        List<String> result = new ArrayList<>();
        for (Hit hit : hits) { result.add(hit.hint()); if (result.size() >= limit) break; }
        return List.copyOf(result);
    }
    /**
     * 验收时的"近义词转标准词"：模型返回的词条不在词库里时，先尝试把它变成词库里真实存在的标准词，
     * 而不是直接判为词库外新词丢掉——否则会出现"返回的词都合规，但用户要的改动大部分缺失"。
     *
     * <p>依次尝试：规范写法（空格/下划线、大小写、权重）→ 单复数与词形变体 → 词库里与它共享实词且
     * 拼写最接近的标准词。找不到真正的对应词时返回 null（调用方再决定丢弃并报告）。
     */
    public String canonical(String term, Set<String> allowed) {
        if (term == null || term.isBlank() || allowed == null || allowed.isEmpty()) return null;
        String raw = term.strip();
        for (String variant : variants(raw)) {
            String key = PromptEditor.key(variant);
            if (allowed.contains(key)) return tagSpelling(variant);
        }
        // 兜底：只接受"写法几乎相同"的标准词——逐词对齐，只有一对词互为前缀（heavy breath → heavy breathing）。
        // 以前用"共享实词"打分，会把 children playing in a sunny park 映射成角色名 teth_(sky:_children_of_the_light)
        // 这种完全不相干的词条，必须避免。
        List<String> tokens = words(raw);
        if (tokens.isEmpty() || tokens.size() > 3) return null;
        String best = null;
        for (String candidate : allowed) {
            if (candidate.indexOf('(') >= 0 || candidate.indexOf(':') >= 0) continue;   // 角色/作者/特殊语法一律不猜
            List<String> parts = words(candidate);
            if (parts.size() != tokens.size()) continue;
            if (!aligned(tokens, parts)) continue;
            if (best == null || candidate.length() < best.length()) best = candidate;
        }
        return best == null ? null : tagSpelling(best);
    }
    /**
     * 逐词对齐：每个词必须相等，或其中一个恰好是另一个加上词形后缀（breath → breathing）。
     * 只认词形变化，不做"共享子串"的猜测——否则 playground 会被猜成 wax_play、natural 猜成 natu。
     */
    private static boolean aligned(List<String> left, List<String> right) {
        boolean morphed = false;
        for (int index = 0; index < left.size(); index++) {
            String a = left.get(index), b = right.get(index);
            if (a.equals(b)) continue;
            if (morphed) return false;
            if (!morphology(a, b)) return false;
            morphed = true;
        }
        return true;
    }
    /** 两个词是否只差一个常见词形后缀（较长的一方 = 较短的一方 + 后缀）。 */
    private static boolean morphology(String first, String second) {
        String shorter = first.length() <= second.length() ? first : second;
        String longer = first.length() <= second.length() ? second : first;
        if (shorter.length() < 4 || longer.length() > shorter.length() + 4) return false;
        for (String suffix : MORPHOLOGY_SUFFIXES)
            if (longer.equals(shorter + suffix)) return true;
        // 结尾辅音重复：run → running、stop → stopped
        if (longer.startsWith(shorter) && longer.length() >= shorter.length() + 2
                && longer.charAt(shorter.length()) == shorter.charAt(shorter.length() - 1)) {
            String rest = longer.substring(shorter.length() + 1);
            for (String suffix : MORPHOLOGY_SUFFIXES) if (rest.equals(suffix)) return true;
        }
        // 去掉结尾 e：dance → dancing
        if (shorter.endsWith("e") && longer.startsWith(shorter.substring(0, shorter.length() - 1))) {
            String rest = longer.substring(shorter.length() - 1);
            for (String suffix : MORPHOLOGY_SUFFIXES) if (rest.equals(suffix)) return true;
        }
        return false;
    }
    private static final List<String> MORPHOLOGY_SUFFIXES = List.of("ing", "ed", "es", "s", "ly", "ness", "ion", "ings");
    /** 词条拆词（小写、去掉权重与括号语法）；用于逐词对齐，不做子串猜测。 */
    public static List<String> words(String term) {
        List<String> result = new ArrayList<>();
        for (String token : PromptEditor.key(Objects.requireNonNullElse(term, "")).split("[^a-z0-9]+"))
            if (!token.isEmpty()) result.add(token);
        return List.copyOf(result);
    }
    /**
     * SD 词条用下划线连接；带权重或标签写法（(tag:1.2)、&lt;lora:...&gt;）的保持原样，
     * 否则把词库里的多词写法转成下划线形式，避免"two words"被当成两个标签。
     */
    public static String tagSpelling(String value) {
        String text = value.strip();
        if (text.isEmpty()) return text;
        char first = text.charAt(0);
        if (first == '(' || first == '[' || first == '<') return text;
        return text.replace(' ', '_');
    }
    /** 同一个词的常见写法：原样、空格换下划线、下划线换空格、单复数、-ing/-ed 还原、英美拼写。 */
    static List<String> variants(String term) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String value = term.strip();
        result.add(value);
        result.add(value.replace(' ', '_'));
        result.add(value.replace('_', ' '));
        String lower = value.toLowerCase(Locale.ROOT);
        result.add(lower);
        result.add(lower.replace(' ', '_'));
        String singular = IRREGULAR_PLURALS.get(lower.replace(' ', '_'));
        if (singular != null) result.add(singular);
        if (lower.endsWith("ies") && lower.length() > 4) result.add(lower.substring(0, lower.length() - 3) + "y");
        if (lower.endsWith("es") && lower.length() > 3) result.add(lower.substring(0, lower.length() - 2));
        if (lower.endsWith("s") && lower.length() > 3) result.add(lower.substring(0, lower.length() - 1));
        if (lower.endsWith("ing") && lower.length() > 5) result.add(lower.substring(0, lower.length() - 3));
        if (lower.endsWith("ed") && lower.length() > 4) result.add(lower.substring(0, lower.length() - 2));
        if (lower.endsWith("our")) result.add(lower.substring(0, lower.length() - 3) + "or");
        if (lower.endsWith("or")) result.add(lower.substring(0, lower.length() - 2) + "our");
        if (lower.contains("isation")) result.add(lower.replace("isation", "ization"));
        if (lower.contains("ization")) result.add(lower.replace("ization", "isation"));
        if (lower.contains("colour")) result.add(lower.replace("colour", "color"));
        if (lower.contains("color")) result.add(lower.replace("color", "colour"));
        return List.copyOf(result);
    }
    /** 不规则复数与常见近义写法：模型很爱用它们，词库里存的却是单数或另一个词。 */
    private static final Map<String, String> IRREGULAR_PLURALS = Map.ofEntries(
            Map.entry("children", "child"), Map.entry("people", "person"), Map.entry("women", "woman"),
            Map.entry("men", "man"), Map.entry("feet", "foot"), Map.entry("teeth", "tooth"),
            Map.entry("mice", "mouse"), Map.entry("geese", "goose"), Map.entry("leaves", "leaf"),
            Map.entry("knives", "knife"), Map.entry("wives", "wife"), Map.entry("lives", "life"),
            Map.entry("boys", "boy"), Map.entry("girls", "girl"), Map.entry("kids", "child"),
            Map.entry("trees", "tree"), Map.entry("flowers", "flower"), Map.entry("clouds", "cloud"),
            Map.entry("stars", "star"), Map.entry("birds", "bird"), Map.entry("hands", "hand"),
            Map.entry("legs", "leg"), Map.entry("eyes", "eye"), Map.entry("ears", "ear"),
            Map.entry("breasts_plural", "breasts"), Map.entry("panties", "panty"),
            Map.entry("sunny", "sunlight"), Map.entry("sunshine", "sunlight"), Map.entry("daylight", "day"),
            Map.entry("natural lighting", "sunlight"), Map.entry("natural_lighting", "sunlight"),
            Map.entry("outdoor", "outdoors"), Map.entry("outdoor scene", "outdoors"),
            Map.entry("candid", "photo_background"), Map.entry("candid photo", "photo_background"),
            Map.entry("playground equipment", "playground"), Map.entry("green grass", "grass"),
            Map.entry("bushes", "bush"), Map.entry("plants", "plant"), Map.entry("benches", "bench"));
    /** 一条候选：英文词条 + 匹配分 + 中文含义长度（越短越贴近本义）+ 热度 + 命中的中文说法。 */
    private record Hit(String hint, int score, int meaningLength, long rank, String alias) {}
    /**
     * 高置信度的中文别名匹配（前置拆解层用）：含义与短句完全一致、以短句开头，或短句里包含这个说法。
     * 普通 {@link #hints} 为了召回率会接受很松的 2 字匹配，这里必须精确，因为它的结果会被当成"必须落实"的词条。
     *
     * <p>同一句话里的不同说法轮流取词条：短句"分镜展示交合部位"里既有"分镜"又有"交合部位"，
     * 若只按分数排序，"交合部位"会占满名额，"分镜"就永远落不到词条上（实测的"分镜查不到词条"）。
     */
    public List<String> strictHints(String request, Set<String> allowed, int limit) {
        if (request == null || request.isBlank() || allowed == null || allowed.isEmpty() || limit < 1) return List.of();
        String text = request.strip();
        Map<String, List<Hit>> groups = new LinkedHashMap<>();
        for (Tag tag : candidates(request)) {
            String meaning = tag.meaning() == null ? "" : tag.meaning().strip();
            if (meaning.isEmpty()) continue;
            AliasMatch match = aliasMatch(text, meaning, tag.library());
            if (match.score() < 3) continue;
            List<String> terms = new ArrayList<>();
            try {
                for (String term : PromptEditor.parts(tag.prompt())) if (allowed.contains(PromptEditor.key(term))) terms.add(term);
            } catch (IllegalArgumentException ignored) { continue; }
            if (terms.isEmpty()) continue;
            groups.computeIfAbsent(match.alias().isBlank() ? meaning : match.alias(), key -> new ArrayList<>())
                    .add(new Hit(String.join(", ", terms), match.score(), meaning.length(), tag.rank(), match.alias()));
        }
        List<List<Hit>> ordered = new ArrayList<>(groups.values());
        for (List<Hit> group : ordered) group.sort(hitsByRelevance);
        ordered.sort(Comparator.comparingInt((List<Hit> group) -> group.get(0).score()).reversed()
                .thenComparing(Comparator.comparingLong((List<Hit> group) -> group.get(0).rank()).reversed()));
        List<String> result = new ArrayList<>();
        for (int round = 0; result.size() < limit; round++) {
            boolean added = false;
            for (List<Hit> group : ordered) {
                if (round >= group.size()) continue;
                String hint = group.get(round).hint();
                if (!result.contains(hint)) result.add(hint);
                added = true;
                if (result.size() >= limit) break;
            }
            if (!added) break;
        }
        return List.copyOf(result);
    }
    private static final Comparator<Hit> hitsByRelevance = Comparator.comparingInt(Hit::score).reversed()
            .thenComparing(Comparator.comparingLong(Hit::rank).reversed())
            .thenComparingInt(Hit::meaningLength).thenComparing(Hit::hint);
    /**
     * 内置词库的中文写法是精确的整词，直接用整词比对（含义里用空格分隔多个写法，所以补空格做边界）；
     * 分类词库的含义是人工短语，沿用原来的"完全一致 / 前缀 / 较长的共同片段"打分。
     *
     * <p>内置词库的"必用词"必须严格：整词相同或长短相差不到一半的前缀关系得满分；
     * 短句里包含某个说法（"女性表情羞耻惊慌"含"羞耻"）也算命中，但分数更低——否则拆解出的短句
     * 会因为写了几个要素就整条查不到词条（.infix 回执里的"词库里没有对应词条"）。泛指词例外：
     * "女性/男性"这类只在整词或前缀命中时才算，不然任何长句都会被它们刷成命中。
     */
    private static int aliasScore(String text, String meaning, boolean library) {
        return aliasMatch(text, meaning, library).score();
    }
    /** 命中的中文说法 + 分数：拆解出的短句里常有两三个要素，按说法分组取词条才能每个要素都覆盖到。 */
    private record AliasMatch(int score, String alias) {}
    private static AliasMatch aliasMatch(String text, String meaning, boolean library) {
        if (library) {
            String padded = " " + meaning + " ";
            if (padded.contains(" " + text + " ")) return new AliasMatch(100, text);
            AliasMatch best = new AliasMatch(0, "");
            for (String alias : meaning.split(" ")) {
                if (alias.isBlank() || alias.length() < 2) continue;
                if (alias.startsWith(text) || text.startsWith(alias)) {
                    int shorter = Math.min(alias.length(), text.length());
                    int longer = Math.max(alias.length(), text.length());
                    if (shorter * 2 >= longer) {
                        int score = 60 + Math.min(30, shorter);
                        if (score > best.score()) best = new AliasMatch(score, alias);
                        continue;
                    }
                }
                if (text.contains(alias) && !GENERIC_ALIASES.contains(alias)) {
                    int score = 30 + Math.min(20, alias.length() * 2);
                    if (score > best.score()) best = new AliasMatch(score, alias);
                }
            }
            return best;
        }
        if (meaning.equalsIgnoreCase(text)) return new AliasMatch(100, meaning);
        if (meaning.startsWith(text) || text.startsWith(meaning)) return new AliasMatch(60 + Math.min(30, meaning.length()), meaning);
        int best = 0;
        for (int size = Math.min(6, text.length()); size >= 3; size--)
            for (int start = 0; start + size <= text.length(); start++) {
                String gram = text.substring(start, start + size);
                // "的背景""的脸"这类以虚词开头的片段不算实词命中：否则任何句子提到"背景/脸/手"都会把
                // 一整类词条（各种 *_background）全拉进候选，模型就会加上用户没要求的词条。
                if (FUNCTION_WORDS.indexOf(gram.charAt(0)) >= 0) continue;
                // 含义被短句包含（公园 ⊂ 孩子在公园玩）也算命中，但含义必须至少两个字且不比 gram 长。
                if (meaning.contains(gram) && size > best) best = size;
                else if (gram.contains(meaning) && meaning.length() >= 2 && meaning.length() <= size && size > best) best = size;
            }
        return best >= 3 && meaning.length() <= best + 4 ? new AliasMatch(best, meaning) : new AliasMatch(0, "");
    }
    /** 虚词：这些字开头的片段不能当作实词命中（"的背景"不是"背景"这个词）。 */
    private static final String FUNCTION_WORDS = "的了是在和与把被给为中上下这那不没有就都也很太之或及而其以及着过地得";
    /** 泛指词：只有在整词或前缀命中时才算，避免"女性/男性"把任何长句都刷成命中。 */
    private static final Set<String> GENERIC_ALIASES = Set.of(
            "女性", "男性", "女人", "男人", "女孩", "男孩", "少女", "少年", "孩子", "儿童", "人物", "人类", "大家",
            "背景", "场景", "画面");
    /** 内置中文词库总条数（0 表示词库文件不可用）。 */
    /**
     * 一个中文说法在**内置中文词库**里精确对应到哪些标准词条（别名整条相等才认，不做子串）。
     *
     * <p>给 {@code .prompt add 微笑} 这类"用户明确要加"的直通路径用：命中且唯一时可以直接照做、
     * 不调 DeepSeek；命中多个（一词多义）就由调用方交回用户决定。空列表＝本机没有这个说法。
     */
    public List<String> exactAliases(String term) {
        if (term == null || term.isBlank()) return List.of();
        String wanted = term.strip();
        List<String> found = new ArrayList<>();
        for (Entry entry : library) {
            if (entry.aliases().stream().noneMatch(alias -> alias.strip().equals(wanted))) continue;
            if (!found.contains(entry.tag())) found.add(entry.tag());
        }
        return List.copyOf(found);
    }
    /** 内置中文词库的条目数（0＝该文件不可用，直通路径退化为只认人工同义词表）。 */
    public int librarySize() { return library.size(); }
    /**
     * 该词条"什么需求下才该用"：分类模板 + {@code data/prompt-zh-use-notes.txt} 里的人工说明。
     * 回执与 .usage 都用这句解释某个词条为什么会（不该）出现。
     */
    public String timing(Entry entry) {
        String custom = useNotes.get(entry.tag().toLowerCase(Locale.ROOT));
        if (custom != null && !custom.isBlank()) return custom;
        String names = String.join("、", entry.aliases().subList(0, Math.min(3, entry.aliases().size())));
        return switch (entry.category()) {
            case "人物" -> "要求画面里出现「" + names + "」这类人物或外貌特征时";
            case "角色" -> "只在用户点名该角色时（角色词条不要自己加）";
            case "作品" -> "只在用户点名该作品时（作品词条不要自己加）";
            case "表情" -> "要求「" + names + "」这类表情时";
            case "动作" -> "要求「" + names + "」这个动作/行为时";
            case "姿势" -> "要求「" + names + "」这个姿势或体位时";
            case "服装" -> "要求角色穿「" + names + "」时";
            case "场景" -> "要求场景是「" + names + "」时";
            case "环境" -> "要求天气、时段或自然环境是「" + names + "」时";
            case "物品" -> "要求画面里出现「" + names + "」时";
            case "镜头" -> "要求用「" + names + "」这种取景或视角时";
            case "画面" -> "要求画质或画风是「" + names + "」时（用户没提画质/画风就不要加）";
            default -> "用户明确提到「" + names + "」时";
        };
    }
    /** 词条所属分类（人物/服装/场景/环境/表情/动作/姿势/物品/镜头/画面/角色/作品/其他）；词库外返回空串。 */
    public String categoryOf(String tag) {
        Entry entry = entry(tag);
        return entry == null ? "" : entry.category();
    }
    /**
     * 精确命中一个词条（忽略大小写，`twin braids` 与 `twin_braids` 等价）；词库外返回 null。
     *
     * <p>查的是 {@code libraryByTag} 这张索引表：几万条词库里做精确命中必须是查表，
     * 不能走 {@link #search}（那是全表扫描 + 排序，给「提示词面板 44 个词条配释义」用会花掉半秒）。
     */
    public Entry entry(String tag) {
        if (tag == null || tag.isBlank()) return null;
        // 提示词里可能写 "twin braids"，词库存的是 "twin_braids"：两种写法都要认。
        String key = PromptEditor.key(tag).strip().toLowerCase(Locale.ROOT);
        Entry entry = libraryByTag.get(key);
        if (entry == null) entry = libraryByTag.get(key.replace(' ', '_'));
        if (entry == null) entry = libraryByTag.get(key.replace('_', ' '));
        return entry;
    }
    /** 词库里所有分类名。 */
    public List<String> categoryNames() { return List.copyOf(CATEGORIES); }
    private static final List<String> CATEGORIES = List.of("人物", "角色", "作品", "表情", "动作", "姿势", "服装", "场景", "环境", "物品", "镜头", "画面", "其他");    /** 该词条的注意事项（成人向、画质/画风、角色/作品、构图…）。 */
    public List<String> cautions(Entry entry) {
        List<String> result = new ArrayList<>();
        for (String flag : entry.flags()) {
            String note = switch (flag) {
                case "成人向" -> "成人向词条：只在用户明确要求该行为/部位时使用，不要自行添加";
                case "画质" -> "画质词条：用户没提画质时不要加";
                case "画风" -> "画风词条：用户没提画风时不要加，避免整张图跑偏";
                case "点名才用" -> "角色/作品词条：只在用户点名时使用";
                case "构图" -> "取景/构图词条：用户没要求机位或视角时不要加，也不要与同族词条同时使用";
                default -> "";
            };
            if (!note.isBlank() && !result.contains(note)) result.add(note);
        }
        return List.copyOf(result);
    }
    /**
     * 这个中文词条是否真能由这段措辞表达（它的某个中文写法出现在这段文字里）。
     * 场景扩写（自由生成）的产物必须过这一关：词库里恰好存在、但用户根本没提到的英文词条不许进来。
     */
    public boolean expresses(String tag, String text) {
        if (tag == null || text == null || text.isBlank()) return false;
        Entry entry = libraryByTag.get(tag.strip().toLowerCase(Locale.ROOT));
        if (entry == null) return false;
        return aliasMatch(text, String.join(" ", entry.aliases()), true).score() >= 3;
    }    /** 一行说明：`词条 — 中文写法（用法：…｜注意：…）`。 */
    public String describe(Entry entry) {
        List<String> parts = new ArrayList<>();
        parts.add("词意：" + String.join("、", entry.aliases()));
        parts.add("使用需求：" + timing(entry));
        List<String> cautions = cautions(entry);
        if (!cautions.isEmpty()) parts.add("注意：" + String.join("；", cautions));
        parts.add("分类：" + entry.category());
        return entry.tag() + " — " + String.join("｜", parts);
    }
    /** 人工说明表（data/prompt-zh-use-notes.txt）：词条 → 使用需求。 */
    private final Map<String, String> useNotes = new HashMap<>();
    private void loadUseNotes(Path root) {
        Path file = root.resolve("data/prompt-zh-use-notes.txt");
        if (!Files.isRegularFile(file)) return;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int at = line.indexOf('=');
                if (at < 0) continue;
                String note = line.substring(at + 1).strip();
                if (note.isEmpty()) continue;
                for (String tag : line.substring(0, at).split(",")) {
                    String key = tag.strip().toLowerCase(Locale.ROOT);
                    if (!key.isEmpty()) useNotes.put(key, note);
                }
            }
        } catch (Exception error) {
            Log.warn("词条用法说明读取失败（不影响词库）：" + error);
        }
    }
    /** 内置中文词库的分类与条数，按条数从多到少。 */
    public List<String> libraryCategories() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entry entry : library) counts.merge(entry.category(), 1, Integer::sum);
        List<String> result = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .forEach(item -> result.add(item.getKey() + " " + item.getValue()));
        return List.copyOf(result);
    }
    /** 在词条与中文写法里搜关键词，命中的词条按热度从高到低返回。 */
    public List<Entry> search(String keyword, int limit) {
        List<Entry> result = new ArrayList<>();
        if (keyword == null || keyword.isBlank() || limit < 1) return result;
        String needle = keyword.strip().toLowerCase(Locale.ROOT);
        for (Entry entry : library) {
            boolean hit = entry.tag().toLowerCase(Locale.ROOT).contains(needle);
            if (!hit) for (String alias : entry.aliases()) if (alias.contains(keyword.strip())) { hit = true; break; }
            if (hit) result.add(entry);
        }
        result.sort(Comparator.comparingLong(Entry::rank).reversed().thenComparing(Entry::tag));
        return result.size() > limit ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
    }
    /** 某个分类（或全部分类）的词条，按热度从高到低。 */
    public List<Entry> byCategory(String category, int limit) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : library) if (category == null || category.isBlank() || entry.category().equals(category)) result.add(entry);
        result.sort(Comparator.comparingLong(Entry::rank).reversed().thenComparing(Entry::tag));
        return result.size() > limit ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
    }
    private static List<Category> read(JsonArray values, int depth) throws IOException {        if (values == null || depth > 32) throw new IOException("Invalid category nesting");
        List<Category> result = new ArrayList<>(); Set<String> names = new HashSet<>();
        for (JsonElement value : values) {
            JsonObject item = value.getAsJsonObject(); String name = item.get("name").getAsString();
            if (name.isBlank() || !name.equals(name.strip()) || name.contains("/") || name.codePoints().anyMatch(Character::isISOControl)
                    || !names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Invalid or duplicate category name");
            List<Tag> tags = new ArrayList<>();
            for (JsonElement entry : item.getAsJsonArray("tags")) {
                JsonObject tag = entry.getAsJsonObject(); String prompt = tag.get("prompt").getAsString();
                if (prompt.isBlank()) throw new IOException("Empty prompt");
                tags.add(new Tag(prompt, tag.get("meaning").getAsString(), 0, false));
            }
            result.add(new Category(name, read(item.getAsJsonArray("children"), depth + 1), List.copyOf(tags)));
        }
        return List.copyOf(result);
    }
    /** 每页列出多少条词条；再多的用"下一页"指令翻页。 */
    private static final int PAGE = 40;
    public String browse(String query) {
        if (query.length() > 1000) throw new IllegalArgumentException("分类路径过长，请从 .usage 开始浏览。");
        String trimmed = query.strip();
        if (trimmed.equals("词库") || trimmed.equals("词库库")) return libraryOverview();
        if (trimmed.startsWith("词库")) return libraryPage(trimmed.substring(2).strip());
        if (trimmed.startsWith("搜索")) return searchPage(trimmed.substring(2).strip());
        if (trimmed.startsWith("词条")) return detailPage(trimmed.substring(2).strip());
        String remaining = trimmed;
        List<Category> children = roots; Category selected = null;
        List<String> path = new ArrayList<>(); String error = "";
        while (!remaining.isEmpty()) {
            String wanted = remaining.toLowerCase(Locale.ROOT);
            Category match = children.stream().filter(node -> {
                String name = node.name().toLowerCase(Locale.ROOT);
                return wanted.equals(name) || wanted.startsWith(name + "/") || wanted.startsWith(name + " ");
            }).max(Comparator.comparingInt(node -> node.name().length())).orElse(null);
            if (match == null) { error = "未找到子分类：" + remaining + "\n请使用下方列出的完整路径。\n"; break; }
            selected = match; path.add(match.name()); children = match.children();
            remaining = remaining.substring(match.name().length()).strip();
            if (remaining.startsWith("/")) remaining = remaining.substring(1).strip();
        }
        String location = String.join("/", path);
        StringBuilder out = new StringBuilder(error).append("提示词分类：").append(location.isEmpty() ? "全部" : location);
        if (!children.isEmpty()) {
            out.append("\n子分类（").append(children.size()).append("）：");
            for (Category child : children)
                out.append("\n.usage ").append(location.isEmpty() ? "" : location + "/").append(child.name());
        }
        if (selected != null && !selected.tags().isEmpty()) {
            out.append("\nprompt — 中文含义（").append(selected.tags().size()).append(" 项）：");
            for (Tag tag : selected.tags()) out.append("\n").append(tag.prompt()).append(" — ")
                    .append(tag.meaning().isBlank() ? "（词库未提供中文含义）" : tag.meaning());
        } else if (children.isEmpty()) out.append("\n该分类暂无词条。");
        if (!path.isEmpty()) out.append("\n返回：.usage").append(path.size() > 1 ? " " + String.join("/", path.subList(0, path.size() - 1)) : "");
        out.append("\n来源：").append(source).append("（含机翻，仅供参考）");
        if (path.isEmpty()) out.append(libraryFooter());
        return out.toString();
    }
    /** 根目录附一行内置中文词库的规模，方便直接进入或搜索。 */
    private String libraryFooter() {
        if (library.isEmpty()) return "\n内置中文词库：不可用（data/prompt-zh-tags.json 缺失或损坏）。";
        return "\n内置中文词库（中文—标准词条对照）：" + library.size() + " 条，"
                + "\n.usage 词库 查看分类    .usage 搜索 <中文或英文> 直接找词条    .usage 词条 <词条> 看它该在什么需求下用";
    }
    private String libraryOverview() {
        if (library.isEmpty()) return "内置中文词库不可用：data/prompt-zh-tags.json 缺失或损坏。\n可用 .usage 浏览本机中文分类词库。";
        StringBuilder out = new StringBuilder("内置中文词库（中文—标准词条对照）：共 " + library.size() + " 条");
        int aliases = 0;
        for (Entry entry : library) aliases += entry.aliases().size();
        out.append("，中文写法 ").append(aliases).append(" 个");
        out.append("\n分类：");
        for (String item : libraryCategories()) out.append("\n.usage 词库 ").append(item.substring(0, item.lastIndexOf(' ')));
        out.append("\n按中文或英文搜索：.usage 搜索 <关键词>");
        out.append("\n看词条用法（词意 / 使用需求 / 注意）：.usage 词条 <词条或中文>");
        if (!librarySource.isBlank()) out.append("\n来源：").append(librarySource);
        return out.toString();
    }
    private String libraryPage(String arguments) {
        if (library.isEmpty()) return "内置中文词库不可用：data/prompt-zh-tags.json 缺失或损坏。";
        String category = "";
        int offset = 0;
        for (String piece : arguments.split("\\s+")) {
            if (piece.isBlank()) continue;
            if (piece.chars().allMatch(Character::isDigit)) offset = Math.max(0, Integer.parseInt(piece));
            else category = piece;
        }
        final String wanted = category;
        if (!wanted.isBlank() && !wanted.equals("全部") && libraryCategories().stream().noneMatch(item -> item.startsWith(wanted + " ")))
            return "未知分类：" + wanted + "\n可用分类：\n" + String.join("\n", libraryCategories());
        List<Entry> entries = byCategory(category.isBlank() || category.equals("全部") ? null : category, offset + PAGE);
        if (entries.size() <= offset) return "该分类没有更多词条了。\n返回：.usage 词库";
        StringBuilder out = new StringBuilder("内置中文词库" + (category.isBlank() || category.equals("全部") ? "" : "·" + category)
                + "（第 " + (offset + 1) + "–" + (offset + Math.min(PAGE, entries.size() - offset)) + " 条）：");
        for (int index = offset; index < Math.min(entries.size(), offset + PAGE); index++) {
            Entry entry = entries.get(index);
            out.append("\n").append(entry.tag()).append(" — ").append(String.join("、", entry.aliases()))
                    .append(entry.flags().isEmpty() ? "" : "（" + String.join("、", entry.flags()) + "）");
        }
        if (entries.size() > offset + PAGE)
            out.append("\n下一页：.usage 词库 ").append(category.isBlank() ? "" : category + " ").append(offset + PAGE);
        out.append("\n看词条用法：.usage 词条 <词条或中文>");
        out.append("\n返回：.usage 词库");
        return out.toString();
    }
    /** `.usage 词条 <关键词>`：列出命中词条的词意、使用需求与注意事项。 */
    private String detailPage(String arguments) {
        if (library.isEmpty()) return "内置中文词库不可用：data/prompt-zh-tags.json 缺失或损坏。";
        String keyword = arguments.strip();
        if (keyword.isBlank()) return "用法：.usage 词条 <英文词条或中文说法>，例如 .usage 词条 chikan、.usage 词条 痴汉";
        List<Entry> entries = search(keyword, 30);
        if (entries.isEmpty()) return "词库里没有匹配 \"" + keyword + "\" 的词条。\n换一个更短的关键词，或用 .usage 搜索 " + keyword + " 看候选。";
        StringBuilder out = new StringBuilder("词条用法（匹配 " + keyword + "，共 " + entries.size() + " 条）：");
        for (Entry entry : entries) out.append("\n").append(describe(entry));
        out.append("\n返回：.usage 词库");
        return out.toString();
    }
    private String searchPage(String arguments) {
        if (library.isEmpty()) return "内置中文词库不可用：data/prompt-zh-tags.json 缺失或损坏。";
        String keyword = arguments;
        int offset = 0;
        java.util.regex.Matcher tail = java.util.regex.Pattern.compile("^(.*?)\\s+(\\d+)$").matcher(arguments);
        if (tail.matches() && !tail.group(1).isBlank()) { keyword = tail.group(1).strip(); offset = Integer.parseInt(tail.group(2)); }
        if (keyword.isBlank()) return "用法：.usage 搜索 <中文或英文关键词>";
        List<Entry> entries = search(keyword, offset + PAGE + 1);
        if (entries.isEmpty()) return "内置中文词库里没有匹配 \"" + keyword + "\" 的词条。\n可以换一个更短的关键词，例如 .usage 搜索 地铁";
        if (entries.size() <= offset) return "该关键词没有更多结果了。\n返回：.usage 搜索 " + keyword;
        StringBuilder out = new StringBuilder("内置中文词库搜索 \"" + keyword + "\"（第 " + (offset + 1) + "–"
                + (offset + Math.min(PAGE, entries.size() - offset)) + " 条）：");
        for (int index = offset; index < Math.min(entries.size(), offset + PAGE); index++) {
            Entry entry = entries.get(index);
            out.append("\n").append(entry.tag()).append(" — ").append(String.join("、", entry.aliases()))
                    .append(entry.flags().isEmpty() ? "" : "（" + String.join("、", entry.flags()) + "）");
        }
        if (entries.size() > offset + PAGE) out.append("\n下一页：.usage 搜索 ").append(keyword).append(" ").append(offset + PAGE);
        out.append("\n看用法：.usage 词条 ").append(keyword);
        out.append("\n返回：.usage 词库");
        return out.toString();
    }
}

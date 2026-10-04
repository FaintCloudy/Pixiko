package cn.szu.bot.prompt;

import java.util.*;

/**
 * 提示词词条分类：把 prompt 里的每一条归到「人物 / 角色 / 作品 / 表情 / 动作 / 姿势 / 服装 / 物品 / 场景 /
 * 环境 / 镜头 / 画面 / LoRA / 其他」，供两处使用：
 *
 * 1) 投喂给模型：{@link #describe} 生成一段"当前 prompt 按类别分组"的清单，
 *    让"只保留人物和服饰，其余清空"这类**按类别**的要求能被理解；
 * 2) 程序侧执行：{@link Bot 侧}的 applyCategorySurgery 直接用 {@link #categoryOf} 逐条判断，
 *    不依赖模型也能准确筛选，且筛选结果与投喂给模型的分类完全一致。
 *
 * 词库（data/prompt-usage.json，37,000+ 条）能认的走词库；认不出的用内置英文词条词表兜底，
 * 因为用户实际的 prompt 里有大量下划线英文标签（maid headdress、frilled apron…）词库未必收录。
 *
 * <p><b>复合短语（本次收紧的部分）</b>：一条词条可能是好几个方面拼起来的描述从句
 * （{@code male pulling her panties aside from behind} = 人物 + 动作 + 服装 + 镜头）。这种词条：
 * <ul>
 *   <li>不再被草率地整条归到"恰好命中子串"的某一个方面：{@link #categoryOf} 返回 {@link #OTHER}，
 *       让"这条到底是什么"由 {@link #matchedCategories} / {@link #fragments} 说明；</li>
 *   <li>删除时按**片段**处理：{@code /prompt drop 镜头} 只摘掉属于镜头的片段（{@code from behind}），
 *       其余片段原样拼回；只有整条都属于该类别的词条（{@code multiple views}）才整条删除。</li>
 * </ul>
 */
public final class TermCategories {
    private TermCategories() {}

    /** LoRA / LyCORIS / 嵌入标签：任何分类筛选都不该动它们。 */
    public static final String LORA = "LoRA";
    /** 认不出的词条，以及"多个方面混在一起的复合短语"：筛选时才需要用户决定怎么处理。 */
    public static final String OTHER = "其他";

    /** 分类的固定顺序（词库的分类 + LoRA + 其他）。 */
    public static final List<String> ORDER = List.of(
            "人物", "角色", "作品", "表情", "动作", "姿势", "服装", "物品", "场景", "环境", "镜头", "画面", LORA, OTHER);

    /**
     * 逐条归类；永远不会返回空串。
     *
     * <p>判定顺序与原来一致（词库优先、英文词表兜底），只在一种情况上收紧：一条词条牵涉多个方面、
     * 而"镜头"只是句中/句尾沾上的（{@code standing sex from behind}、{@code male pulling her panties
     * aside from behind}）时归 {@link #OTHER}，不整条算作镜头。这样 {@code /prompt drop 镜头} 不会再把
     * 整条描述型词条删掉——删除时只摘属于镜头的片段（见 {@link #withoutCategories}）。
     */
    public static String categoryOf(PromptUsage usage, String term) {
        if (isLoraOrEmbedding(term)) return LORA;
        if (term == null || term.isBlank()) return OTHER;
        String fromLibrary = usage == null ? "" : usage.categoryOf(term);
        // 词库把它归到"其他"时也再走一次英文词表：词库的兜底类不如后缀/词组规则准（"two side up" 是发型）。
        if (!fromLibrary.isBlank() && !OTHER.equals(fromLibrary) && ORDER.contains(fromLibrary)) return fromLibrary;
        return heuristic(term);
    }

    /**
     * 这条词条究竟牵涉哪几个方面（按 {@link #ORDER} 排序，不含"其他"）。
     * 空列表 = 词表认不出；多个 = 复合短语（{@link #categoryOf} 因此可能返回"其他"）。
     * 这是"一条词条能被切成几个片段"的另一种说法，只用于说明与展示。
     */
    public static List<String> matchedCategories(String term) {
        if (term == null || term.isBlank()) return List.of();
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (Fragment fragment : fragments(term)) labels.add(fragment.category());
        List<String> ordered = new ArrayList<>();
        for (String category : ORDER) if (labels.contains(category)) ordered.add(category);
        return List.copyOf(ordered);
    }

    /** 一条词条里被认出来的一个片段：类别 + 该片段在词条文本里的起止（按分隔符切出的词元下标，含头不含尾）。 */
    public record Fragment(String category, int from, int to) {}

    /**
     * 把一条词条切成若干**有类别的片段**（按分隔符切成词元后逐段匹配）。没认出来的词元不属于任何片段，
     * 调用方必须原样保留——"宁可少删，不许把用户没要求删的内容吃掉"。
     */
    public static List<Fragment> fragments(String term) {
        String text = plain(term);
        if (text.isEmpty()) return List.of();
        // 词边界：`close-up`/`multiple_views` 在比较前统一成空格写法，所以它们与 "close up"、
        // "multiple views" 是同一个词；片段定位与"删哪一段"都以这些词为单位，绝不从单词中间切开。
        List<String> tokens = tokens(text);
        // 1) 每个位置取最长命中（"full body" 整条命中，不会退化成 "full" + 其余）；中间片段没命中也要
        //    接着往后拼，因为命中可能从第二个词才开始（"extreme close-up"）。
        List<Fragment> candidates = new ArrayList<>();
        int index = 0;
        while (index < tokens.size()) {
            String category = null;
            int span = 0;
            for (int end = index; end < tokens.size() && end - index < MAX_PHRASE_WORDS; end++) {
                String matched = headPhrase(tokens, index, end);
                if (matched != null && end - index + 1 > span) { category = matched; span = end - index + 1; }
            }
            // 单词片段往后看：这个词自己就是词表词、后面又紧跟另一个词表词时，它只是修饰语
            // （"aerial view" 的 aerial、"blue eyes" 的 blue），不单独成段——否则删镜头只摘掉一个词，
            // 把同义的 view 留在原地。反过来说，"multiple views" 的 views（后面没有词表词）仍然成段。
            if (span == 1 && PHRASE_WORDS.contains(spell(tokens.get(index)))
                    && index + 1 < tokens.size() && PHRASE_WORDS.contains(spell(tokens.get(index + 1)))) {
                index++;
                continue;
            }
            if (span > 0) {
                // 往前看：紧挨着的前置修饰词属于同一段（"extreme close-up" 的 extreme），整段一起删；
                // 功能词（of/the/her…）和自带类别的词不吸收，保证先出现的词条归先出现的片段
                // （"male pulling" 的 male 仍是人物片段，不会被 pulling 吃掉）。
                int start = index;
                if (span < 2)
                    while (start > 0 && !PHRASE_WORDS.contains(spell(tokens.get(start - 1)))
                            && !FUNCTION_WORDS.contains(spell(tokens.get(start - 1)))) start--;
                candidates.add(new Fragment(category, start, index + span));
                index += span;
                continue;
            }
            index++;
        }
        // 2) 长片段优先占用词元：这样 "aerial view" 整段算镜头（而不是只剩后面那个 view），
        //    "multiple views" 也仍然整段算镜头。
        candidates.sort(Comparator.comparingInt((Fragment item) -> item.to() - item.from()).reversed()
                .thenComparingInt(Fragment::from));
        boolean[] used = new boolean[tokens.size()];
        List<Fragment> selected = new ArrayList<>();
        for (Fragment candidate : candidates) {
            boolean free = true;
            for (int position = candidate.from(); position < candidate.to(); position++) if (used[position]) { free = false; break; }
            if (!free) continue;
            for (int position = candidate.from(); position < candidate.to(); position++) used[position] = true;
            selected.add(candidate);
        }
        selected.sort(Comparator.comparingInt(Fragment::from));
        return List.copyOf(selected);
    }

    /** 词表里从 {@code from} 到 {@code to}（含）这段词的整段命中（词边界，长写法优先由调用方比较）。 */
    private static String headPhrase(List<String> tokens, int from, int to) {
        StringBuilder candidate = new StringBuilder();
        for (int part = from; part <= to; part++) {
            if (part > from) candidate.append(' ');
            candidate.append(spell(tokens.get(part)));
        }
        return HEAD_PHRASES.get(candidate.toString());
    }

    /** 功能词：不是修饰语，不能被左边的片段吸收（"her view" 的 her 要留给自己的片段）。 */
    private static final Set<String> FUNCTION_WORDS = Set.of(
            "a", "an", "the", "her", "his", "their", "its", "my", "your", "our", "of", "to", "for", "with", "and",
            "or", "in", "on", "at", "by", "as", "is", "are", "was", "were", "that", "this", "these", "those");

    /**
     * 删掉这条词条里**属于这些类别**的片段，其余片段按原顺序拼回；格式收拾干净（单词之间单空格、
     * 首尾不留分隔符）。判不出来、或不属于目标类别的部分一个字都不动；没有任何目标片段时原样返回。
     *
     * <p>这是 {@code /prompt drop 镜头} 的真正执行体：{@code standing sex from behind} → {@code standing sex}；
     * {@code multiple views}（整个词条就是镜头词）→ 空串（整条删掉）。
     */
    public static String withoutCategories(String term, Collection<String> categories) {
        if (term == null || term.isBlank() || categories == null || categories.isEmpty()) return term == null ? "" : term;
        Set<String> wanted = new HashSet<>(categories);
        List<Fragment> all = fragments(term);
        // 整条词条没有片段可摘，但它自己（按同一份词表）整条就属于要删的某个方面（"multiple views"
        // 整条都是镜头词）→ 整条删掉；否则一个字都不动。
        if (all.isEmpty()) return wanted.contains(wholeCategory(term)) ? "" : term;
        List<String> tokens = tokens(plain(term));
        List<String> pieces = new ArrayList<>();
        int cursor = 0;
        for (Fragment fragment : all) {
            if (cursor < fragment.from()) pieces.add(term.substring(offset(term, cursor), offset(term, fragment.from())));
            if (!wanted.contains(fragment.category())) pieces.add(term.substring(offset(term, fragment.from()), offset(term, fragment.to())));
            cursor = fragment.to();
        }
        if (cursor < tokens.size()) pieces.add(term.substring(offset(term, cursor)));
        if (pieces.size() == all.size() && all.stream().noneMatch(fragment -> wanted.contains(fragment.category()))) return term;
        return join(pieces);
    }

    /** 不看词库、只看词表时这条词条整条属于哪个方面（复合短语返回"其他"）。 */
    private static String wholeCategory(String term) {
        if (term == null || term.isBlank()) return OTHER;
        String category = heuristic(term);
        return OTHER.equals(category) ? null : category;
    }

    /** 文本里第 {@code index} 个词元的字符起点（保留用户原本的写法，只按位置摘片段）。 */
    private static int offset(String text, int index) {
        int seen = 0, position = 0, length = text.length();
        while (position < length && seen < index) {
            while (position < length && isSeparator(text.charAt(position))) position++;
            while (position < length && !isSeparator(text.charAt(position))) position++;
            seen++;
        }
        while (position < length && isSeparator(text.charAt(position))) position++;
        return position;
    }

    /** 拼回片段：单词之间单个空格，首尾不留分隔符（不会出现双空格或前后多余逗号）。 */
    private static String join(List<String> pieces) {
        StringBuilder text = new StringBuilder();
        for (String piece : pieces) {
            String cleaned = stripSeparators(piece.strip());
            if (cleaned.isEmpty()) continue;
            if (text.length() > 0) text.append(' ');
            text.append(cleaned);
        }
        return text.toString();
    }

    private static String stripSeparators(String value) {
        int start = 0, end = value.length();
        while (start < end && isSeparator(value.charAt(start))) start++;
        while (end > start && isSeparator(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isSeparator(char value) { return value == '_' || value == '-' || Character.isWhitespace(value) || value == ',' || value == ';'; }

    /** 按分类分组（保持分类顺序与词条原始顺序）。 */
    public static Map<String, List<String>> group(PromptUsage usage, List<String> terms) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String term : terms == null ? List.<String>of() : terms) {
            if (term == null || term.isBlank()) continue;
            grouped.computeIfAbsent(categoryOf(usage, term), key -> new ArrayList<>()).add(term);
        }
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        for (String category : ORDER) {
            List<String> items = grouped.get(category);
            if (items != null && !items.isEmpty()) ordered.put(category, List.copyOf(items));
        }
        // 词库将来新增分类时也不能丢词条。
        for (Map.Entry<String, List<String>> entry : grouped.entrySet())
            if (!ordered.containsKey(entry.getKey())) ordered.put(entry.getKey(), List.copyOf(entry.getValue()));
        return ordered;
    }

    /**
     * 喂给模型的一段清单：每个分类一行，行内是原始词条（不改写、不翻译），
     * 模型照这个分组执行"只保留某几类、清空其余"就不会漏词条。复合短语放在"其他"里并注明它牵涉的方面，
     * 模型因此知道它不该被当成某一个方面的词整条删掉。
     */
    public static String describe(PromptUsage usage, List<String> terms) {
        List<String> items = new ArrayList<>();
        for (String term : terms == null ? List.<String>of() : terms) if (term != null && !term.isBlank()) items.add(term);
        if (items.isEmpty()) return "当前正向提示词是空的。";
        Map<String, List<String>> grouped = group(usage, items);
        StringBuilder text = new StringBuilder("当前正向提示词共 ").append(items.size()).append(" 条，按类别分组：");
        for (Map.Entry<String, List<String>> entry : grouped.entrySet())
            text.append("\n").append(entry.getKey()).append("(").append(entry.getValue().size()).append(")：")
                    .append(String.join("、", entry.getValue()));
        text.append("\n分类含义：人物=角色身份与外貌特征，服装=衣服鞋袜配饰，动作/姿势=行为与体位，表情=表情情绪，")
                .append("场景/环境=地点、天气、时段、光线，镜头=取景与视角，画面=画质与画风，物品=道具，")
                .append("角色/作品=点名才用的角色与作品名，LoRA=模型标签（任何筛选都要保留）；")
                .append("其他=认不出的自定义词，以及把多个方面写在一句里的复合短语（例如 standing sex from behind）；")
                .append("复合短语按片段处理：删某一类只摘掉那一段，其余部分保留。");
        return text.toString();
    }

    /** 分词后的分类清单（给 .prompt classify 用）。 */
    public static String describe(PromptUsage usage, String prompt) {
        List<String> terms = new ArrayList<>();
        try { terms.addAll(PromptEditor.parts(prompt == null ? "" : prompt)); }
        catch (IllegalArgumentException ignored) { /* 提示词异常时按空处理 */ }
        return describe(usage, terms);
    }

    public static boolean isLoraOrEmbedding(String term) {
        if (term == null) return false;
        String text = term.strip().toLowerCase(Locale.ROOT);
        return text.startsWith("<") || text.contains("<lora:") || text.startsWith("embedding:") || text.matches("^<[^>]+>$");
    }

    /** 去掉权重、括号，并把 `_`/`-` 统一成空格，得到用于词表匹配的朴素写法（词边界与 {@link #tokens} 一致）。 */
    private static String plain(String term) {
        String text = PromptEditor.key(term == null ? "" : term).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (char character : text.toCharArray())
            result.append(character == '_' || character == '-' ? ' ' : character);
        return result.toString().strip();
    }

    /**
     * 切成"词"：字母数字与撇号算词内字符，其余（空格、连字符、下划线、逗号…）都是分隔符。
     * {@link #plain} 已经把 `_` 统一成空格，连字符在这里也是分隔符，所以 "close-up"、"close up"、
     * `close_up` 是同一个词；片段定位与"删哪一段"都以这些词为单位，绝不会从单词中间切开。
     */
    private static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        for (String token : text.split("[^a-z0-9']+")) if (!token.isEmpty()) result.add(token);
        return result;
    }

    /** 拼写比较用：`close-up`/`close_up` 与 `close up` 是同一个词。 */
    private static String spell(String token) { return token.replace('_', ' ').replace('-', ' '); }

    /**
     * 英文词表兜底，判定顺序与原来一致（先整段词组命中、再按最后一个词兜底），
     * 只在多命中时加一道"复合短语"闸门：一条词条同时牵涉多个方面、而且没有一个方面能当它的"头"
     * （{@code standing sex from behind}）时归"其他"，而不是草率地整条算作其中某一个方面。
     */
    private static String heuristic(String term) {
        String text = plain(term);
        if (text.isEmpty()) return OTHER;
        List<String> words = tokens(text);
        // 1) 整段词组命中（整词边界上，不切单词）：判定顺序与原来一致——词表里越靠前的分类越先认。
        //    命中顺序必须自己用 List 记住：Set.of/copyOf 的迭代顺序不是插入顺序，靠它取"第一个命中"
        //    会随 JVM 运行而变（同一个词条有时归 A 有时归 B）。
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : LEXICON.entrySet()) {
            for (String key : entry.getValue()) {
                if (containsPhrase(text, spell(key))) { labels.add(entry.getKey()); break; }
            }
        }
        // 2) 词表里一个词都没命中时，再按最后一个词兜底（"wolf tail" 是人物特征）。
        List<String> contained = List.copyOf(labels);
        if (labels.isEmpty()) {
            String tail = tailCategory(words);
            if (tail != null) labels.add(tail);
        }
        if (labels.size() == 1) return labels.get(0);
        if (labels.size() > 1) {
            // 多命中：按原来的优先级取第一个命中的方面（既有分类行为逐条不变），只收紧一种情况——
            // "镜头"只是**句中/句尾**沾上的（standing sex from behind、male pulling her panties aside
            // from behind）：尾部的外景从句不代表整条词条是镜头词，这时归"其他"；删除时只摘镜头那一段
            // （见 withoutCategories）。
            String first = contained.get(0);
            if (!"镜头".equals(first)) return first;
            return cameraHeaded(text, words.isEmpty() ? "" : words.get(0)) ? first : OTHER;
        }
        // 词表和词库都不认、但写法像人名/作品名的（Shinamori Yomogi、Kanbe Kotori）：算"角色"名。
        // 这样"仅保留人物和服饰"不会把用户点名放进去的角色名当成杂物删掉。
        if (looksLikeProperName(term)) return "角色";
        return OTHER;
    }

    /**
     * "镜头"的词条级命中在不在词条**开头/整条**：{@code portrait}、{@code multiple views}、
     * {@code full body}、{@code from behind}（单独出现）。句尾的 {@code … from behind} 不算。
     */
    private static boolean cameraHeaded(String text, String first) {
        for (String key : LEXICON.getOrDefault("镜头", List.of())) {
            String needle = spell(key);
            if (needle.isEmpty()) continue;
            if (text.equals(needle) || text.startsWith(needle + " ")) return true;
            if (needle.indexOf(' ') < 0 && needle.equals(first)) return true;
        }
        return false;
    }

    /** 镜头词条里以方位/状语开头的写法（{@code from behind}、{@code on back}）——说明机位，不是整条的主干。 */
    private static final Set<String> ADVERBIAL_STARTS = Set.of(
            "from", "on", "in", "at", "to", "with", "by", "up", "down", "out", "over", "under", "above", "below",
            "behind", "beside", "besides", "against", "toward", "towards", "into", "onto", "off", "aside", "near");

    /** 只按结尾那个词（词表词条或后缀）判断的方面；没有则返回 null。 */
    private static String tailCategory(List<String> words) {
        String last = words.isEmpty() ? "" : words.get(words.size() - 1);
        if (last.isEmpty()) return null;
        for (Map.Entry<String, List<String>> entry : LEXICON.entrySet())
            if (entry.getValue().stream().anyMatch(key -> spell(key).equals(last))) return entry.getKey();
        for (Map.Entry<String, List<String>> entry : SUFFIXES.entrySet())
            for (String suffix : entry.getValue()) if (last.equals(suffix)) return entry.getKey();
        return null;
    }

    /** 首字母大写的词组（人名/作品名写法）。全小写的自定义词仍归入"其他"，由用户决定去留。 */
    private static boolean looksLikeProperName(String term) {
        String text = term == null ? "" : term.strip();
        if (text.isEmpty() || text.length() > 60 || text.startsWith("(") || text.startsWith("<") || text.contains("=")) return false;
        int capitalized = 0;
        for (String token : text.split("[\\s_]+")) {
            if (token.isEmpty()) continue;
            if (Character.isUpperCase(token.charAt(0)) && token.codePoints().anyMatch(Character::isLowerCase)) capitalized++;
        }
        return capitalized >= 1;
    }

    /** 词组命中：整段相等，或在词边界上（前后是空格/串首串尾），避免 "hair" 命中 "hairbrush"。 */
    private static boolean containsPhrase(String text, String needle) {
        if (needle.isBlank()) return false;
        if (text.equals(needle)) return true;
        if (text.startsWith(needle + " ") || text.endsWith(" " + needle)) return true;
        return text.contains(" " + needle + " ");
    }

    /** 词表：分类 → 英文关键词（整词命中）。顺序即优先级，越靠前的分类越先认。 */
    private static final Map<String, List<String>> LEXICON = lexicon();
    private static Map<String, List<String>> lexicon() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("镜头", List.of("view", "views", "angle", "shot", "closeup", "close-up", "close up", "portrait",
                "profile", "pov", "focus", "framing", "foreshortening", "fisheye", "panorama", "perspective",
                "composition", "depth of field", "bokeh", "upper body", "lower body", "full body", "cowboy shot",
                "from above", "from below", "from side", "from behind", "from outside", "dutch angle", "wide shot",
                "medium shot", "mid shot", "full shot", "aerial", "three-quarter view", "foot focus",
                "face focus", "eyes focus", "hip focus", "solo focus", "lens flare", "foreshortening",
                "extreme close-up", "extreme closeup", "close-up on", "closeup on"));
        map.put("表情", List.of("smile", "smiling", "grin", "blush", "blushing", "tears", "teary", "crying", "cry",
                "sad", "angry", "anger", "surprised", "expressionless", "embarrassed", "smug", "pout", "open mouth",
                "closed eyes", "half-closed eyes", "half closed eyes", "one eye closed", "wink", "frown", "scared", "worried", "shy",
                "happy", "joy", "serious", "smirk", "tongue", "licking", "biting", "gasp", "moaning", "sweat",
                "sweatdrop", "light smile", "blank stare", "stare"));
        map.put("姿势", List.of("standing", "sitting", "lying", "kneeling", "squatting", "crouching", "on back",
                "on stomach", "on side", "crossed legs", "spread legs", "legs up", "legs apart", "wariza", "seiza",
                "indian style", "straddling", "all fours", "m legs", "arched back", "bent over", "hand on hip",
                "arms behind back", "arms up", "arms crossed", "crossed arms", "hugging own legs", "fetal position",
                "sitting on lap", "on one knee", "tiptoes", "standing on one leg", "sitting on chair", "on chair",
                "against wall", "leaning forward", "prone", "supine"));
        map.put("动作", List.of("holding", "holds", "walking", "running", "jumping", "flying", "dancing", "sleeping",
                "eating", "drinking", "reading", "writing", "hug", "hugging", "kiss", "kissing", "sucking",
                "looking", "looking at viewer", "looking away", "looking back", "looking down", "looking up",
                "reaching", "touching", "grabbing", "pulling", "pushing", "carrying", "riding", "swimming",
                "fighting", "attacking", "laughing", "singing", "playing", "cooking", "cleaning", "bathing",
                "undressing", "dressing", "tying", "lifting", "covering", "spread", "squeezing", "head tilt",
                "turn one's back", "facing viewer", "facing away", "eye contact", "glance", "peeking", "peeping",
                "outstretched", "reaching out", "hand up", "hands up", "waving", "pointing", "crossed fingers",
                "holding hands", "on shoulders", "carried", "hug from behind", "lap pillow", "headpat"));
        map.put("服装", List.of("dress", "skirt", "shirt", "blouse", "sweater", "cardigan", "hoodie", "jacket",
                "coat", "cape", "cloak", "robe", "apron", "uniform", "suit", "vest", "shorts", "pants", "trousers",
                "jeans", "leggings", "pantyhose", "thighhighs", "stockings", "socks", "garter", "underwear", "bra",
                "panties", "bikini", "swimsuit", "leotard", "bodysuit", "lingerie", "nightgown", "pajamas", "kimono",
                "yukata", "hakama", "maid", "nurse", "miko", "boots", "shoes", "sandals", "heels", "slippers",
                "gloves", "mittens", "hat", "cap", "hood", "headdress", "headband", "hairband", "hair ribbon",
                "hair ornament", "hair flower", "ribbon", "bow", "bowtie", "necktie", "scarf", "belt", "sash",
                "collar", "cuffs", "wrist cuffs", "frills", "frilled", "lace", "ruffles", "trim", "ornament",
                "necklace", "choker", "earrings", "bracelet", "brooch", "tiara", "crown", "veil", "armor",
                "breastplate", "pauldron", "gauntlets", "greaves", "costume", "outfit", "clothes", "clothing",
                "wear", "legwear", "handbag", "bag", "backpack", "purse", "umbrella", "glasses", "sunglasses",
                "eyepatch", "mask", "bandage", "bandages", "tie", "sleeves", "sleeve", "cuff", "hem", "zipper",
                "button", "detached sleeves", "short sleeves", "long sleeves", "puffy sleeves", "puff sleeves",
                "back bow", "heart button", "waist apron", "frilled apron", "maid headdress", "orange dress"));
        map.put("人物", List.of("1girl", "2girls", "1boy", "2boys", "multiple girls", "multiple boys", "girl",
                "boy", "woman", "man", "female", "male", "lady", "ladies", "solo", "hair", "bangs", "ponytail",
                "twintails", "twintail", "braid", "braids", "ahoge", "hair bun", "two side up", "side ponytail",
                "drill hair", "wavy hair", "straight hair", "eyes", "eyebrows", "eyelashes", "pupils", "iris",
                "face", "skin", "lips", "nose", "ear", "ears", "fang", "fangs", "horns", "tail", "wings",
                "breasts", "large breasts", "small breasts", "cleavage", "navel", "thighs", "legs", "arms", "hands",
                "fingers", "feet", "body", "waist", "hips", "ass", "mature", "loli", "child", "chibi", "muscular",
                "blonde", "brunette", "redhead", "silver hair", "white hair", "black hair", "blue hair", "pink hair",
                "green hair", "purple hair", "orange hair", "brown hair", "grey hair", "multicolored hair",
                "gradient hair", "long hair", "short hair", "very long hair", "medium hair", "blue eyes", "red eyes",
                "green eyes", "purple eyes", "yellow eyes", "brown eyes", "grey eyes", "black eyes", "aqua eyes",
                "orange eyes", "heterochromia", "pointy ears", "animal ears", "cat ears", "fox ears", "halo",
                "colored inner hair", "inner hair", "streaked hair", "hair intakes"));
        map.put("场景", List.of("background", "scenery", "indoors", "outdoors", "classroom", "school", "bedroom",
                "kitchen", "bathroom", "office", "library", "shrine", "temple", "church", "castle", "ruins",
                "street", "alley", "city", "town", "village", "forest", "tree", "trees", "grass", "lawn", "meadow",
                "grasslands", "field", "beach", "ocean", "sea", "lake", "river", "water", "underwater", "mountain",
                "sky", "clouds", "cloud", "starry sky", "night sky", "moon", "sun", "stars", "space", "room",
                "window", "door", "wall", "floor", "ceiling", "stairs", "rooftop", "balcony", "garden", "park",
                "flower", "flowers", "cherry blossoms", "petals", "snow", "desert", "cave", "bridge", "train",
                "subway", "car", "bus", "bicycle", "motorcycle", "ship", "boat", "airplane", "chair", "table",
                "bed", "sofa", "curtain", "lamp", "mirror", "clock", "book", "books", "sign", "poster", "fence",
                "building", "architecture", "simple background", "white background", "grey background",
                "black background", "gradient background", "two-tone background", "pillow", "blanket", "bathtub"));
        map.put("环境", List.of("day", "night", "nighttime", "evening", "morning", "afternoon", "noon", "dusk",
                "dawn", "sunset", "sunrise", "twilight", "rain", "rainy", "raining", "snowing", "fog", "foggy",
                "mist", "cloudy", "overcast", "clear sky", "wind", "windy", "storm", "thunder", "lightning",
                "weather", "season", "spring", "summer", "autumn", "winter", "sunlight", "moonlight", "starlight",
                "candlelight", "neon lights", "lighting", "backlighting", "rim light", "light rays", "sunbeam",
                "god rays", "shadows", "darkness", "warm", "cold", "hot", "humid", "fire", "smoke", "bloom",
                "light particles", "luminous", "glowing", "glow"));
        map.put("物品", List.of("sword", "katana", "blade", "gun", "pistol", "rifle", "weapon", "weapons", "spear",
                "bow and arrow", "arrow", "shield", "staff", "wand", "knife", "dagger", "scythe", "hammer", "axe",
                "whip", "chain", "rope", "cage", "bottle", "cup", "glass", "mug", "teacup", "plate", "bowl", "food",
                "cake", "bread", "fruit", "apple", "ice cream", "candy", "phone", "smartphone", "camera",
                "computer", "laptop", "keyboard", "television", "headphones", "microphone", "guitar", "violin",
                "piano", "notebook", "paper", "pen", "pencil", "letter", "map", "card", "ticket", "money", "coin",
                "key", "keys", "doll", "teddy bear", "balloon", "present", "gift", "basket", "lantern", "candle",
                "torch", "flag", "jewelry", "gem", "crystal", "potion", "syringe", "stethoscope", "tool",
                "toolbox", "wrench", "broom", "bucket", "vehicle", "military vehicle", "wheelchair", "parasol"));
        map.put("画面", List.of("masterpiece", "best quality", "high quality", "quality", "highres", "hi res",
                "high resolution", "resolution", "absurdres", "ultra detailed", "extremely detailed",
                "highly detailed", "detailed", "8k", "4k",
                "uhd", "realistic", "photorealistic", "photo realistic", "hyperrealistic", "photorealism",
                "semi realistic", "anime", "anime style", "manga", "comic", "cartoon", "chibi", "sketch",
                "lineart", "line art", "watercolor", "oil painting", "painting", "illustration", "monochrome",
                "greyscale", "grayscale", "sepia", "pixel art", "3d", "3d model", "render", "cg", "flat color",
                "thick coating", "cell shading", "cyberpunk", "steampunk", "retro", "vintage", "artstyle",
                "traditional media", "impressionism", "surreal", "minimalism", "fanart", "official art",
                "concept art", "wallpaper", "poster", "logo", "signature", "watermark", "text", "border", "framed",
                "letterboxed", "artist name", "twitter username", "patreon username", "blurry", "blur",
                "out of focus", "lowres", "bad anatomy", "bad hands", "censored", "mosaic censoring", "bar censor",
                "username", "web address", "error", "jpeg artifacts", "worst quality", "low quality"));
        return Collections.unmodifiableMap(map);
    }

    /** 后缀兜底：词表整词没命中时，按后缀判断（比前缀更准，"hair" 结尾多半是人物特征）。 */
    private static final Map<String, List<String>> SUFFIXES = suffixes();
    private static Map<String, List<String>> suffixes() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("服装", List.of("dress", "skirt", "shirt", "apron", "ribbon", "bowtie", "bow", "sleeves", "sleeve",
                "cuffs", "cuff", "boots", "shoes", "socks", "thighhighs", "stockings", "gloves", "hat", "cap",
                "headdress", "headband", "necklace", "choker", "earrings", "bracelet", "uniform", "suit", "coat",
                "jacket", "cape", "scarf", "collar", "frills", "lace", "trim", "wear", "clothes", "clothing"));
        map.put("人物", List.of("hair", "eyes", "ears", "tail", "wings", "horns", "face", "skin", "breasts",
                "thighs", "legs", "arms", "hands", "fingers", "body", "hips", "bangs", "ponytail", "braid", "ahoge"));
        map.put("表情", List.of("smile", "grin", "blush", "tears", "expression", "mouth", "frown", "pout", "wink"));
        map.put("镜头", List.of("view", "angle", "shot", "focus", "framing"));
        map.put("画面", List.of("style", "artstyle", "quality", "detailed", "res", "painting", "illustration", "art"));
        return Collections.unmodifiableMap(map);
    }

    /**
     * 片段识别用的词表：整段词组（多词）+ 自成词条的单词。只认**词边界**上的完整命中，
     * 不做会切断单词的粗暴替换；单个词条里的同一个词元只算一次，重叠时取最长的写法。
     */
    /**
     * 片段识别用的词表：词表里的**每一个写法**（多词词组与单词都算），值是该写法的类别。
     * 只认词边界上的完整命中，绝不做会切断单词的替换。
     */
    private static final Map<String, String> HEAD_PHRASES = headPhrases();
    /** 词表里出现过的单词（判断"紧跟着的另一个词条"用）。 */
    private static final Set<String> PHRASE_WORDS = phraseWords();
    private static final int MAX_PHRASE_WORDS = 8;
    private static Map<String, String> headPhrases() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : LEXICON.entrySet())
            for (String key : entry.getValue()) map.putIfAbsent(key.replace('_', ' ').replace('-', ' '), entry.getKey());
        return Collections.unmodifiableMap(map);
    }
    private static Set<String> phraseWords() {
        Set<String> words = new LinkedHashSet<>(HEAD_PHRASES.keySet());
        words.removeIf(word -> word.indexOf(' ') >= 0);
        return Collections.unmodifiableSet(words);
    }
}

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
 */
public final class TermCategories {
    private TermCategories() {}

    /** LoRA / LyCORIS / 嵌入标签：任何分类筛选都不该动它们。 */
    public static final String LORA = "LoRA";
    /** 认不出的词条：筛选时才需要用户决定怎么处理。 */
    public static final String OTHER = "其他";

    /** 分类的固定顺序（词库的分类 + LoRA + 其他）。 */
    public static final List<String> ORDER = List.of(
            "人物", "角色", "作品", "表情", "动作", "姿势", "服装", "物品", "场景", "环境", "镜头", "画面", LORA, OTHER);

    /** 逐条归类；永远不会返回空串。 */
    public static String categoryOf(PromptUsage usage, String term) {
        if (isLoraOrEmbedding(term)) return LORA;
        if (term == null || term.isBlank()) return OTHER;
        String fromLibrary = usage == null ? "" : usage.categoryOf(term);
        // 词库把它归到"其他"时也再走一次英文词表：词库的兜底类不如后缀/词组规则准（"two side up" 是发型）。
        if (!fromLibrary.isBlank() && !OTHER.equals(fromLibrary) && ORDER.contains(fromLibrary)) return fromLibrary;
        return heuristic(term);
    }

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
     * 模型照这个分组执行"只保留某几类、清空其余"就不会漏词条。
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
                .append("角色/作品=点名才用的角色与作品名，LoRA=模型标签（任何筛选都要保留）。");
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

    /** 去掉权重、括号与下划线，得到用于词表匹配的朴素写法。 */
    private static String plain(String term) {
        String text = PromptEditor.key(term == null ? "" : term).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (char character : text.toCharArray())
            result.append(character == '_' ? ' ' : character);
        return result.toString().strip();
    }

    /** 英文词表兜底：先整段词组命中（长词组优先），再按最后一个词兜底。 */
    private static String heuristic(String term) {
        String text = plain(term);
        if (text.isEmpty()) return OTHER;
        List<String> words = List.of(text.split("[^a-z0-9']+"));
        for (Map.Entry<String, List<String>> entry : LEXICON.entrySet()) {
            for (String key : entry.getValue()) {
                String needle = key.replace('_', ' ');
                if (containsPhrase(text, needle)) return entry.getKey();
            }
        }
        String last = words.isEmpty() ? "" : words.get(words.size() - 1);
        for (Map.Entry<String, List<String>> entry : SUFFIXES.entrySet()) {
            for (String suffix : entry.getValue()) if (last.equals(suffix)) return entry.getKey();
        }
        // 词表和词库都不认、但写法像人名/作品名的（Shinomori Yomogi、Kanbe Kotori）：算"角色"名。
        // 这样"仅保留人物和服饰"不会把用户点名放进去的角色名当成杂物删掉。
        if (looksLikeProperName(term)) return "角色";
        return OTHER;
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
                "medium shot", "mid shot", "full shot", "aerial", "multiple views", "three-quarter view", "foot focus",
                "face focus", "eyes focus", "hip focus", "solo focus", "lens flare", "foreshortening"));
        map.put("表情", List.of("smile", "smiling", "grin", "blush", "blushing", "tears", "teary", "crying", "cry",
                "sad", "angry", "anger", "surprised", "expressionless", "embarrassed", "smug", "pout", "open mouth",
                "closed eyes", "half-closed eyes", "one eye closed", "wink", "frown", "scared", "worried", "shy",
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
                "absurdres", "ultra detailed", "extremely detailed", "highly detailed", "detailed", "8k", "4k",
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
}

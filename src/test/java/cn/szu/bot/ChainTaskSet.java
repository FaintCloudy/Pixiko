package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import cn.szu.bot.sd.SdClient;

/**
 * The shared task set of the chain evaluation: a generated, reproducible family of multi-step Chinese
 * requests (basis → several .infix edits → .gen) plus the real numbered lists they refer to.
 *
 * The numbered lists are not fixtures: {@link #preflight} runs the real .style list / .lora list /
 * .function list commands through the Bot and keeps the numbered reply, so "#7" in a task refers to
 * exactly the list a user would have seen. Both the planning-only evaluation and the effect
 * evaluation build on this class, so they always measure the same tasks.
 */
public final class ChainTaskSet {
    /** One requested change: the category the user named, its wording, and acceptable proof substrings. */
    record Edit(String category, String value, List<String> keys) {}
    /** What a complete plan must contain for one generated task. */
    record Spec(String kind, String basisToken, List<String> basisAlternatives, List<Edit> edits,
                int count, boolean strictCount, boolean generate) {}
    record ScaleTask(int id, String message, Spec spec, String shape) {}
    /** A numbered list produced by the real command, in the same order the user saw it. */
    record Numbered(String kind, List<String> names, String reply, String source) {}

    static final Map<String, Numbered> LISTS = new LinkedHashMap<>();

    private ChainTaskSet() {}

    // ------------------------------------------------------------------ numbered lists

    /** Executes the real list commands through the Bot and parses the numbered replies. */
    static void preflight(Path root, JsonObject config) {
        for (String command : List.of(".style list", ".lora list", ".function list")) {
            String kind = command.split("\\s+")[0].substring(1);
            try {
                Numbered list = execute(root, config, command);
                if (list.names().isEmpty()) throw new IllegalStateException("命令没有返回任何编号项");
                LISTS.put(kind, list);
                Log.info("规模评测：已执行 " + command + "，得到 " + list.names().size() + " 项");
            } catch (Exception error) {
                LISTS.put(kind, new Numbered(kind, List.of(), "", "不可用：" + Bot.error(error)));
                Log.warn("规模评测：" + command + " 未能取得编号表：" + Bot.error(error));
            }
        }
    }

    private static Numbered execute(Path root, JsonObject config, String command) throws Exception {
        SdClient sd = new SdClient(root, obj(config, "sd"));
        BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        Bot bot = new Bot(new Settings(root), sd, (event, segments) -> {
            replies.add(Bot.messageText(segments));
            return CompletableFuture.completedFuture(null);
        });
        JsonObject event = new JsonObject();
        event.addProperty("post_type", "message");
        event.addProperty("message_type", "private");
        event.addProperty("self_id", 1);
        event.addProperty("user_id", new Settings(root).ownerId());
        event.addProperty("message_id", 1);
        event.addProperty("message", command);
        bot.accept(event);
        // Async commands acknowledge first ("正在读取…") and send the list as a later message: drain until quiet.
        StringBuilder collected = new StringBuilder();
        String first = replies.poll(60, TimeUnit.SECONDS);
        if (first == null) throw new IllegalStateException("命令超时未回复");
        collected.append(first).append('\n');
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
        while (System.nanoTime() < deadline) {
            String next = replies.poll(4, TimeUnit.SECONDS);
            if (next == null) break;
            collected.append(next).append('\n');
        }
        List<String> names = new ArrayList<>();
        Matcher numbered = Pattern.compile("(?m)^#([0-9]+)\\s+(.+?)\\s*$").matcher(collected.toString());
        while (numbered.find()) {
            // .lora list shows "name（别名：alias）" but stores the bare name; the snapshot must match it.
            names.add(numbered.group(2).replaceFirst("（别名：[^）]*）\\s*$", "").strip());
        }
        String kind = command.split("\\s+")[0].substring(1);
        return new Numbered(kind, List.copyOf(names), collected.toString().strip(), "真实命令");
    }

    // ------------------------------------------------------------------ task generation

    private static final List<Edit> PLACES = List.of(
            new Edit("地点", "神社", List.of("神社")), new Edit("地点", "古城街道", List.of("古城")),
            new Edit("地点", "教室窗边", List.of("教室")), new Edit("地点", "樱花树下", List.of("樱花")),
            new Edit("地点", "雪国小镇", List.of("雪")), new Edit("地点", "学校天台", List.of("天台")),
            new Edit("地点", "水族馆", List.of("水族馆")), new Edit("地点", "医院走廊", List.of("医院")),
            new Edit("地点", "军营操场", List.of("操场")), new Edit("地点", "图书馆", List.of("图书馆")),
            new Edit("地点", "沙滩", List.of("沙滩")), new Edit("地点", "水底", List.of("水")));
    private static final List<Edit> OUTFITS = List.of(
            new Edit("服饰", "军装", List.of("军装")), new Edit("服饰", "泳装", List.of("泳装")),
            new Edit("服饰", "和服", List.of("和服")), new Edit("服饰", "女仆装", List.of("女仆")),
            new Edit("服饰", "黑色风衣", List.of("风衣")), new Edit("服饰", "铠甲", List.of("铠甲")),
            new Edit("服饰", "白大褂", List.of("白大褂", "白衣", "白袍")), new Edit("服饰", "运动服", List.of("运动服")),
            new Edit("服饰", "巫女服", List.of("巫女")), new Edit("服饰", "西装", List.of("西装", "西服")));
    private static final List<Edit> WEATHER = List.of(
            new Edit("天气", "雨天", List.of("雨")), new Edit("天气", "晴天", List.of("晴")),
            new Edit("天气", "阴天", List.of("阴")), new Edit("天气", "大雾", List.of("雾")),
            new Edit("天气", "雷雨", List.of("雷")), new Edit("天气", "晚霞", List.of("晚霞", "霞")));
    private static final List<Edit> TIMES = List.of(
            new Edit("时间", "黄昏", List.of("黄昏", "傍晚")), new Edit("时间", "清晨", List.of("清晨", "早晨")),
            new Edit("时间", "深夜", List.of("深夜", "午夜")), new Edit("时间", "正午", List.of("正午", "中午")));
    private static final List<Edit> LIGHTS = List.of(
            new Edit("光线", "逆光", List.of("逆光")), new Edit("光线", "柔和光线", List.of("柔和")),
            new Edit("光线", "霓虹灯光", List.of("霓虹")), new Edit("光线", "烛光", List.of("烛光", "蜡烛")),
            new Edit("光线", "月光", List.of("月光", "月色")));
    private static final List<Edit> ACTIONS = List.of(
            new Edit("动作", "站立", List.of("站")), new Edit("动作", "回头", List.of("回头")),
            new Edit("动作", "奔跑", List.of("奔跑")), new Edit("动作", "坐着", List.of("坐")),
            new Edit("动作", "跳跃", List.of("跳跃", "跳起")), new Edit("动作", "撑伞", List.of("伞")));
    private static final List<List<Edit>> CATEGORIES = List.of(PLACES, OUTFITS, WEATHER, TIMES, LIGHTS, ACTIONS);
    private static final List<String> CATEGORY_NAMES = List.of("地点", "服饰", "天气", "时间", "光线", "动作");

    static List<ScaleTask> generate(int count, long seed) {
        Random random = new Random(seed);
        List<ScaleTask> tasks = new ArrayList<>();
        List<Named> styles = usableNames(LISTS.get("style"));
        List<Named> loras = usableNames(LISTS.get("lora"));
        List<Named> functions = usableNames(LISTS.get("function"));
        for (int id = 1; id <= count; id++) {
            int roll = random.nextInt(100);
            Basis basis = roll < 8 ? Basis.none()
                    : roll < 63 ? Basis.of("style", styles, random)
                    : roll < 88 ? Basis.of("lora", loras, random)
                    : Basis.of("function", functions, random);
            List<Edit> edits = pickEdits(random, 3 + random.nextInt(2), basis.text());
            if (edits.size() < 3) { id--; continue; }   // keep every task a >3-step chain
            boolean generate = random.nextInt(100) >= 8;
            int count_ = 1 + random.nextInt(3);
            boolean chineseNumber = generate && random.nextInt(100) < 20;
            String message = render(random, basis, edits, generate, count_, chineseNumber);
            Spec spec = new Spec(basis.kind(), basis.token(), basis.alternatives(), edits, count_, chineseNumber, generate);
            tasks.add(new ScaleTask(id, message, spec, basis.kind() + "/" + edits.size() + "改/" + (generate ? "生成" : "不生成")));
        }
        return tasks;
    }

    /** A usable numbered entry: the name must stay tied to its real 1-based position in the list. */
    record Named(int index, String name) {}

    private static List<Named> usableNames(Numbered list) {
        List<Named> names = new ArrayList<>();
        if (list == null) return names;
        for (int i = 0; i < list.names().size(); i++) {
            String name = list.names().get(i);
            if (name.isBlank() || name.length() > 40) continue;
            if (name.contains("\"") || name.contains("\\") || name.contains("|")) continue;
            names.add(new Named(i, name));
            if (names.size() >= 25) break;   // only the numbered entries that fit in the history node
        }
        return names;
    }

    /** A basis the request can name: by exact name, by #number, or "don't use any style". */
    private record Basis(String kind, String text, String token, List<String> alternatives, String number) {
        static Basis none() { return new Basis("", "", "", List.of(), ""); }
        static Basis of(String kind, List<Named> names, Random random) {
            if (names.isEmpty()) return none();
            Named entry = names.get(random.nextInt(names.size()));
            boolean byNumber = random.nextInt(100) < 45;
            String reference = "#" + (entry.index() + 1);
            String token = byNumber ? reference : entry.name();
            return new Basis(kind, entry.name(), token, List.of(entry.name(), reference), reference);
        }
    }

    private static List<Edit> pickEdits(Random random, int size, String basisText) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < CATEGORIES.size(); i++) order.add(i);
        Collections.shuffle(order, random);
        List<Edit> chosen = new ArrayList<>();
        for (int categoryIndex : order) {
            if (chosen.size() >= size) break;
            List<Edit> pool = CATEGORIES.get(categoryIndex);
            Edit candidate = pool.get(random.nextInt(pool.size()));
            if (!compatible(candidate, chosen, basisText)) continue;
            chosen.add(candidate);
        }
        // Deterministic order of appearance, so the expected chain reads like the request.
        chosen.sort(Comparator.comparingInt(edit -> CATEGORY_NAMES.indexOf(edit.category())));
        return chosen;
    }

    /**
     * Two edits in one task must be independently provable: their keyword sets may not overlap, and no
     * keyword may already appear in the basis name (otherwise a wrong plan could look complete).
     */
    private static boolean compatible(Edit candidate, List<Edit> chosen, String basisText) {
        for (String key : candidate.keys()) {
            if (!basisText.isEmpty() && basisText.contains(key)) return false;
            for (Edit other : chosen) for (String otherKey : other.keys())
                if (key.contains(otherKey) || otherKey.contains(key)) return false;
        }
        return true;
    }

    private static final String[][] BASIS_CLAUSES = {
            {"用", "做基底"}, {"以", "为风格打底"}, {"拿", "当底子"}, {"把样式换成", ""}, {"以", "为基底"}
    };
    private static final String[] EDIT_VERBS = {"改成", "改为", "换成", "换为", "变成", "调成"};
    private static final String[] CONNECTORS = {"，", "、", "；", "，然后", "，再", "；接着", "，最后", "，顺便"};
    private static final String[] PREFIXES = {"", "帮我", "麻烦", "小助手", "喂"};

    private static String render(Random random, Basis basis, List<Edit> edits, boolean generate, int count, boolean chinese) {
        String quote = switch (random.nextInt(3)) { case 0 -> "「"; case 1 -> "\""; default -> ""; };
        String close = "「".equals(quote) ? "」" : quote;
        StringBuilder message = new StringBuilder(PREFIXES[random.nextInt(PREFIXES.length)]);
        if (!basis.kind().isEmpty()) {
            String reference = basis.token();
            if (basis.kind().equals("lora")) {
                // A LoRA basis must be worded as a LoRA, otherwise ".style load" would be a fair reading.
                String[] loraClauses = {"用 lora ", "拿 lora ", "把 LoRA 换成 ", "加载 lora "};
                message.append(loraClauses[random.nextInt(loraClauses.length)]).append(quote).append(reference).append(close)
                        .append(random.nextBoolean() ? " 当基底" : " 打底");
            } else if (basis.kind().equals("function")) {
                message.append("加载提示词集 ").append(quote).append(reference).append(close).append("，用它当底子");
            } else {
                String[] clause = BASIS_CLAUSES[random.nextInt(BASIS_CLAUSES.length)];
                message.append(clause[0]).append(quote).append(reference).append(close).append(clause[1]);
            }
        } else {
            message.append("别用样式");
        }
        for (int i = 0; i < edits.size(); i++) {
            Edit edit = edits.get(i);
            String connector = i == 0 ? "，" : CONNECTORS[random.nextInt(CONNECTORS.length)];
            message.append(connector).append(edit.category()).append(EDIT_VERBS[random.nextInt(EDIT_VERBS.length)]).append(edit.value());
        }
        if (generate) {
            String[] generators = chinese
                    ? new String[]{"，然后生成" + chinese(count) + "张", "，来" + chinese(count) + "张", "，最后出" + chinese(count) + "张"}
                    : new String[]{"，然后生成" + count + "张", "，最后出图", "，出" + count + "张", "，画一张", "，然后生成" + count + "张图"};
            message.append(generators[random.nextInt(generators.length)]);
        } else {
            message.append(random.nextBoolean() ? "，先不要生成" : "，这次先别出图");
        }
        return message.toString();
    }

    private static String chinese(int value) {
        return switch (value) { case 1 -> "一"; case 2 -> "两"; default -> "三"; };
    }

    /** The history a user would have: the real numbered reply of the list they asked for. */
    static JsonArray historyFor(ScaleTask task) {
        JsonArray history = new JsonArray();
        Numbered list = LISTS.get(task.spec().kind());
        if (list == null || list.names().isEmpty() || task.spec().basisAlternatives().isEmpty()) return history;
        JsonObject asked = new JsonObject();
        asked.addProperty("role", "user");
        asked.addProperty("content", "." + list.kind() + " list");
        JsonObject answered = new JsonObject();
        answered.addProperty("role", "assistant");
        answered.addProperty("content", list.reply());
        history.add(asked);
        history.add(answered);
        return history;
    }

    static JsonObject selections() {
        JsonObject selections = new JsonObject();
        for (Map.Entry<String, Numbered> entry : LISTS.entrySet())
            if (!entry.getValue().names().isEmpty()) selections.add(entry.getKey(), Json.GSON.toJsonTree(entry.getValue().names()));
        return selections;
    }

    static JsonObject obj(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject();
    }

    static String percent(double rate) { return String.format(Locale.ROOT, "%.1f%%", rate * 100); }

    /** The real style/LoRA prompt text behind a chosen basis, used to check the applied basis. */
    static List<String> tagsOf(String prompt) {
        List<String> tags = new ArrayList<>();
        if (prompt == null) return tags;
        for (String tag : prompt.split("[,\\n]")) {
            String clean = tag.replaceAll("^\\s+|\\s+$", "");
            if (clean.length() >= 3) tags.add(clean);
        }
        return tags;
    }

    /**
     * Canonical comparison form of one prompt term: weights unwrapped, escapes removed, spacing folded to
     * underscores — the same shape data/prompt-tags.txt uses.
     */
    static String canonical(String tag) {
        String text = tag == null ? "" : tag.strip().toLowerCase(Locale.ROOT);
        Matcher weight = Pattern.compile("^\\((.*):[0-9.]+\\s*\\)$").matcher(text);
        if (weight.matches()) text = weight.group(1).strip();
        // A bare parenthesis group is emphasis syntax, not part of the tag: (monochrome) covers monochrome.
        Matcher group = Pattern.compile("^\\(([^():]+)\\)$").matcher(text);
        if (group.matches()) text = group.group(1).strip();
        text = text.replace("\\(", "(").replace("\\)", ")").replace("\\[", "[").replace("\\]", "]").replace("\\\\", "\\");
        text = text.replaceAll("[\\s_]+", "_").replaceAll("^_+|_+$", "");
        return text;
    }
}

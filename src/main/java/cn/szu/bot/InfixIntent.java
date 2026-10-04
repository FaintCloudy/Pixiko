package cn.szu.bot;

import cn.szu.bot.prompt.PromptEditor;
import cn.szu.bot.prompt.TermCategories;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「改为 X」「加入 X」「删掉 X」「把 X 改成 Y」这类**单条祈使句**的确定性解析与落地。
 *
 * <p>为什么需要它：这些说法本身没有歧义，本来用不着模型；整条丢给模型时，模型偶尔产不出可执行的改写，
 * 用户最后只收到一句「模型没有给出可执行的指令，所以什么都没改」（线上日志里这类回执出现过 65 次）。
 * 认得出句式、且要写入的内容就是英文词条／模型标签时，由程序直接改：又快、又不花额度、也不会"什么都没发生"。
 * 认不出来（多步、带条件、纯画面描述）时 {@link #parse(String)} 返回 {@code null}，仍然整条交给模型。
 *
 * <p><b>一条硬规矩</b>：prompt 里不许出现中文词条（见 {@code Block Chinese prompt tags in commands}）。
 * 所以"解析成功"不等于"可以直接落地"：{@link #needsTranslation(Intent)} 为真（要写入的内容含汉字/假名等
 * SD 读不了的脚本）时必须交给模型翻译成标准英文词条；这一层判断由调用方（{@code Bot.infix}）做，
 * 本类只保证把"含中文"如实标出来，绝不把中文原样当成可以写进 prompt 的内容。
 *
 * <p>本类全是纯函数，不碰磁盘、不发请求，可以直接单测（见 {@code InfixIntentTest}）。
 */
public final class InfixIntent {
    private InfixIntent() { }

    /** 一句话里要做的动作。 */
    public enum Kind { REPLACE, ADD, REMOVE, SWAP }

    /**
     * 动作落在哪一侧。
     * 默认正向（"删掉 X" = 从正向删）；只有明确说了"反向/负向"才动反向。
     */
    public enum Side { POSITIVE, NEGATIVE }

    /**
     * 一条识别出来的祈使句。
     *
     * @param kind  动作：整体替换 / 追加 / 删除 / 词级替换
     * @param side  作用的一侧（默认 {@link Side#POSITIVE}）
     * @param terms 要写入的词条（SWAP 时是"换成什么"）；可能含中文，是否可直接落地由 {@link #needsTranslation(Intent)} 判断
     * @param from  只有 SWAP 用：被替换掉的旧词条
     * @param note  回执用的中文说明，例如「整体替换正向提示词」
     */
    public record Intent(Kind kind, Side side, List<String> terms, List<String> from, String note) {
        public Intent { terms = List.copyOf(terms); from = List.copyOf(from); }
        /** 要写入的内容含中文（等 SD 读不了的脚本）时 true：必须交给模型翻译，不能直接写进 prompt。 */
        public boolean needsTranslation() { return InfixIntent.needsTranslation(this); }
    }

    /** {@link #apply(String, Intent)} 的结果：新提示词文本 + 真正生效/没生效的词条（回执与测试共用）。 */
    public record Change(String prompt, List<String> changed, List<String> unchanged, List<String> missing) {
        public Change {
            changed = List.copyOf(changed);
            unchanged = List.copyOf(unchanged);
            missing = List.copyOf(missing);
        }
    }

    /** 超过这个长度基本是"列举很长/整段描述"，不猜，交给模型。 */
    private static final int MAX_INSTRUCTION = 200;
    /** 一条祈使句里最多认这么多词条；再多就是列举，交给模型。 */
    private static final int MAX_TERMS = 8;
    /** 单条"词条"超过这个长度基本是整句描述，不猜。 */
    private static final int MAX_TERM_LENGTH = 60;

    /** 换的意思（最长优先：正则按书写顺序取第一个成功的分支）。 */
    private static final Pattern SWAP_VERB = Pattern.compile(
            "替换为|替换成|替换|设置为|设为|设成|改为|改成|改作|换成|换为|变为|变成|转为|变作");
    /** 加的意思。特别注意：**不收单字「加」**——「加速一点」「加个滤镜」会误判，那种交给模型。 */
    private static final Pattern ADD_VERB = Pattern.compile(
            "加上|加进|加入|添加进去|添加|增加|追加|补上|补充|多加|添上|再加|加个");
    /** 删的意思。同样不收含糊的「不要」「取消」（它们常出现在别的语境里）。 */
    private static final Pattern REMOVE_VERB = Pattern.compile(
            "删掉|删除|删去|去掉|去除|移除|移掉|清掉|清除|减去|拿掉|撤掉|别加|不要加");
    /**
     * 这些说法属于**别的**指令（撤销、出图、加载…）：整句不接管，仍然交给聊天规划与模型。
     * 「撤回上一条修改，改为素股」这类多步要求必须返回 null，绝不能只执行后半句。
     */
    private static final Pattern OTHER_ACTION = Pattern.compile(
            "撤回|撤销|回退|恢复|重做|重新|再来|出图|生成|画一张|来一张|加载|载入|下载|查询|搜索|列出|查看|清空|改名|重命名");
    /** 问句不是祈使句。 */
    private static final Pattern QUESTION = Pattern.compile("怎么|如何|怎样|能不能|可不可以|可以吗|是什么|为什么|教程|教我|介绍|讲讲|解释|吗\\s*[？?]|[？?]");
    /** 带条件的要求不猜。 */
    private static final Pattern CONDITION = Pattern.compile("如果|假如|要是|除非|否则|如果说");
    /** 多动作/多步的连接词：出现就是不接管。 */
    private static final Pattern MULTI_STEP = Pattern.compile("然后|并且|同时|接着|顺带|顺便|另外|之后|最后|再把|并在|并把|并改|并加|并删");

    private static final Pattern NEGATIVE_SIDE = Pattern.compile(
            "(?:负向|反向)(?:的|提示词|prompt|词)?(?:里|中|上|里面|上面|中的)?");
    private static final Pattern POSITIVE_SIDE = Pattern.compile(
            "(?:正[向面])(?:的|提示词|prompt|词)?(?:里|中|上|里面|上面|中的)?");
    /** 祈使句开头常见的套话。注意不收单字「帮」「想」这类会吃掉词条首字的写法。 */
    private static final Pattern FILLER = Pattern.compile("^(?:请|麻烦|帮我|帮忙|给我|你|我|要|想|把|将|给|让|一下)+");

    /**
     * 解析一条改写要求。
     *
     * @return 识别出来的 {@link Intent}；看不出来（多步／条件／纯描述／只有"改为"两个字）返回 {@code null}，
     *         表示"交给模型，别猜"
     */
    public static Intent parse(String instruction) {
        if (instruction == null) return null;
        // 全角/半角、大小写、空白的宽容：NFKC 把全角标点与字母数字折成半角，分隔符判断因此只有一套。
        String text = Normalizer.normalize(instruction, Normalizer.Form.NFKC).strip();
        if (text.isEmpty() || text.length() > MAX_INSTRUCTION) return null;
        if (QUESTION.matcher(text).find() || CONDITION.matcher(text).find() || MULTI_STEP.matcher(text).find()) return null;
        if (OTHER_ACTION.matcher(text).find()) return null;

        List<Hit> hits = new ArrayList<>();
        collect(hits, SWAP_VERB, Kind.SWAP, text);
        collect(hits, ADD_VERB, Kind.ADD, text);
        collect(hits, REMOVE_VERB, Kind.REMOVE, text);
        // 一个动作动词都没有 → 只可能是句首单独的「改 X」；有两个以上 → 多动作，整句交给模型。
        if (hits.isEmpty()) return bareChange(text);
        if (hits.size() > 1) return null;

        Hit hit = hits.get(0);
        Prefix prefix = cleanPrefix(text.substring(0, hit.start()));
        List<String> after = terms(text.substring(hit.end()));
        if (hit.kind == Kind.SWAP) {
            if (after.isEmpty()) return null;
            if (prefix.content().isEmpty()) return build(Kind.REPLACE, prefix.side(), after, List.of());
            List<String> sources = terms(prefix.content());
            if (sources.isEmpty()) return build(Kind.REPLACE, prefix.side(), after, List.of());
            // 多个来源对多个目标必须一一对应；对不上就是不猜（"把 A 和 B 改成 C" 交给模型）。
            if (sources.size() > 1 && sources.size() != after.size()) return null;
            return build(Kind.SWAP, prefix.side(), after, sources);
        }
        // 加/删：内容通常在动词后面；「把渔网袜加上」「把校服删掉」内容在动词前面（前面已经剥掉套话与侧别词）。
        List<String> beforeTerms = terms(prefix.content());
        if (!after.isEmpty() && !beforeTerms.isEmpty()) return null;   // 两侧都有内容：说不清，交给模型
        List<String> targets = after.isEmpty() ? beforeTerms : after;
        if (targets.isEmpty()) return null;
        return build(hit.kind, prefix.side(), targets, List.of());
    }

    /** 句首单独的「改 X」（"改"后面不是 为/成/作）：按"整体替换正向提示词"处理。 */
    private static Intent bareChange(String text) {
        if (!text.startsWith("改")) return null;
        String rest = text.substring(1);
        if (rest.startsWith("为") || rest.startsWith("成") || rest.startsWith("作")) return null;
        rest = rest.replaceFirst("^(?:一下|下|个|点)\\s*", "");
        List<String> targets = terms(rest);
        return build(Kind.REPLACE, Side.POSITIVE, targets, List.of());
    }

    private static Intent build(Kind kind, Side side, List<String> targets, List<String> from) {
        if (!usable(targets)) return null;
        if (kind == Kind.SWAP && !usable(from)) return null;
        String label = side == Side.NEGATIVE ? "反向" : "正向";
        String note = switch (kind) {
            case REPLACE -> "整体替换" + label + "提示词";
            case ADD -> "向" + label + "提示词追加";
            case REMOVE -> "从" + label + "提示词删除";
            case SWAP -> "把" + label + "提示词里的「" + String.join("、", from) + "」换成「" + String.join("、", targets) + "」";
        };
        return new Intent(kind, side, targets, from, note);
    }

    private record Hit(int start, int end, Kind kind) { }
    private record Prefix(Side side, String content) { }

    /** 记下某一类动作动词在本句里的全部位置（同一位置最长优先，find() 不回头重复计数）。 */
    private static void collect(List<Hit> hits, Pattern verbs, Kind kind, String text) {
        Matcher matcher = verbs.matcher(text);
        while (matcher.find()) hits.add(new Hit(matcher.start(), matcher.end(), kind));
    }

    /** 剥掉"把/请/帮我…"和"正向/反向提示词里"这类套话，并记下说的是哪一侧。 */
    private static Prefix cleanPrefix(String raw) {
        Side side = Side.POSITIVE;
        String text = raw == null ? "" : raw;
        Matcher negative = NEGATIVE_SIDE.matcher(text);
        if (negative.find()) { side = Side.NEGATIVE; text = negative.replaceFirst(""); }
        else {
            Matcher positive = POSITIVE_SIDE.matcher(text);
            if (positive.find()) text = positive.replaceFirst("");
        }
        String previous;
        do {
            previous = text;
            text = FILLER.matcher(text).replaceFirst("")
                    .replaceFirst("^(?:的|里|中|上|上面|里面|中的|的话)+", "")
                    .replaceFirst("^[\\s:：,，。、;；=＝]+", "").strip();
        } while (!text.equals(previous));
        return new Prefix(side, text);
    }

    /**
     * 把"词条串"切成词条：顶层逗号/顿号/分号/换行/和、与、跟、以及、还有 断开，
     * 括号里的分隔符不动（「素股（大腿内侧摩擦，…）」算**一条**，否则会把它拆成垃圾）。
     */
    static List<String> terms(String raw) {
        List<String> result = new ArrayList<>();
        String text = trimTail(cleanEdges(raw));
        if (text.isEmpty()) return result;
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isOpen(ch)) { depth++; current.append(ch); continue; }
            if (isClose(ch)) { if (depth > 0) depth--; current.append(ch); continue; }
            if (depth == 0) {
                if (ch == ',' || ch == '，' || ch == '、' || ch == ';' || ch == '；' || ch == '\n' || ch == '|') {
                    addTerm(result, current.toString()); current.setLength(0); continue;
                }
                if (ch == '和' || ch == '与' || ch == '跟') { addTerm(result, current.toString()); current.setLength(0); continue; }
                if ((ch == '以' || ch == '还') && i + 1 < text.length() && text.charAt(i + 1) == (ch == '以' ? '及' : '有')) {
                    addTerm(result, current.toString()); current.setLength(0); i++; continue;
                }
            }
            current.append(ch);
        }
        addTerm(result, current.toString());
        return result;
    }

    private static boolean isOpen(char ch) { return ch == '(' || ch == '[' || ch == '<' || ch == '{'; }
    private static boolean isClose(char ch) { return ch == ')' || ch == ']' || ch == '>' || ch == '}'; }

    private static String cleanEdges(String raw) {
        if (raw == null) return "";
        return raw.strip()
                .replaceFirst("^[\\s:：=＝,，.。;；、-]+", "")
                .replaceFirst("^[「『\"'“”]+", "")
                .strip();
    }

    /** 剥掉句尾的语气词与"这几项/这些/谢谢"这类收尾，让「… 这几项」也能对上词条。 */
    private static String trimTail(String raw) {
        String text = raw == null ? "" : raw.strip();
        for (int round = 0; round < 4; round++) {
            String next = text.replaceFirst("[\\s，,。.、;；!！?？~～…]+$", "")
                    .replaceFirst("(?:吧|谢谢|多谢|一下|一点|这些|那些|它们|这几项|那几项|这几种|那几种|的内容|的部分|就好了|就行|即可|等|等等)$", "")
                    .strip();
            if (next.equals(text)) break;
            text = next;
        }
        return text;
    }

    private static void addTerm(List<String> out, String raw) {
        if (raw == null) return;
        String term = raw.strip()
                .replaceFirst("^[「『\"'“”]+", "")
                .replaceAll("[」』\"'“”]+$", "")
                .replaceFirst("^[\\s:：=＝]+", "")
                .replaceAll("[\\s:：,，。;；!！?？]+$", "")
                .strip();
        if (term.isEmpty()) return;
        if (!out.contains(term)) out.add(term);
    }

    /** 词条要像"提示词内容"，而且句子里不能再藏着别的动作动词（藏了就说明是多动作）。 */
    private static boolean usable(List<String> terms) {
        if (terms == null || terms.isEmpty() || terms.size() > MAX_TERMS) return false;
        for (String term : terms) {
            if (term == null || term.isBlank() || term.length() > MAX_TERM_LENGTH) return false;
            if (term.matches("^[改增删移去换补添清加].*")) return false;
            if (term.matches("(?s).*(然后|并且|同时|接着|顺便|顺带|如果|假如|要是|除非|否则).*")) return false;
            // 一长句中文（带句末标点）是描述，不是词条。
            if (hasCjk(term) && term.length() > 12 && term.matches("(?s).*[。！？!?；;].*")) return false;
        }
        return true;
    }

    /** 要写入的内容含 SD 读不了的脚本（汉字/假名/谚文）时 true；模型标签（&lt;lora:…&gt;）不算。 */
    public static boolean needsTranslation(Intent intent) {
        if (intent == null) return false;
        for (String term : intent.terms()) if (!TermCategories.isLoraOrEmbedding(term) && hasCjk(term)) return true;
        return false;
    }

    /**
     * 文本里有没有 SD 读不了的脚本。比 {@code Bot.hasHan} 宽：汉字、假名、谚文、注音都算
     * （「ことり」「한글」写进 prompt 一样没用），但全角字母数字不算（NFKC 之后本来就是半角）。
     */
    public static boolean hasCjk(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int code = text.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(code);
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
                    || script == Character.UnicodeScript.BOPOMOFO) return true;
            i += Character.charCount(code);
        }
        return false;
    }

    /**
     * 提示词里有没有和 {@code term} 对得上的词条：先按规范化键完全相等，再退一步做**唯一**包含匹配
     * （"pantyhose" 能对上 black_pantyhose）。对得上多个候选时算"对不上"——宁可交给模型，也不猜。
     */
    public static boolean matchesAny(String prompt, String term) {
        return !matches(prompt, term).isEmpty();
    }

    private static List<String> matches(String prompt, String term) {
        List<String> hits = new ArrayList<>();
        if (prompt == null || term == null || term.isBlank()) return hits;
        String wanted = PromptEditor.key(term);
        if (wanted.isEmpty()) return hits;
        List<String> terms;
        // 提示词本身写坏了（括号不匹配）时算"对不上"：调用方据此交给模型，不在这里抛异常。
        try { terms = PromptEditor.parts(prompt); } catch (IllegalArgumentException broken) { return hits; }
        for (String existing : terms) {
            String key = PromptEditor.key(existing);
            if (key.equals(wanted)) return List.of(existing);
            if (key.contains(wanted)) hits.add(existing);
        }
        return hits.size() == 1 ? hits : List.of();
    }

    /**
     * 把一条祈使句落到提示词文本上（纯函数，不落盘）。
     *
     * <p>只动指令指定的那一侧。删/换找不到对应词条时**原文一个字都不动**，并把它们放进
     * {@link Change#missing()}：调用方据此如实回报"没找到"，绝不去另一侧瞎删（另一边要不要动，
     * 由用户说"反向…"决定）。
     */
    public static Change apply(String prompt, Intent intent) {
        String text = prompt == null ? "" : prompt;
        if (intent == null) return new Change(text, List.of(), List.of(), List.of());
        return switch (intent.kind()) {
            case REPLACE -> replace(text, intent);
            case ADD -> add(text, intent);
            case REMOVE -> remove(text, intent);
            case SWAP -> swap(text, intent);
        };
    }

    private static Change replace(String text, Intent intent) {
        List<String> result = new ArrayList<>(intent.terms());
        // LoRA/嵌入标签任何改写都不丢（代码库里"筛选不动模型标签"是硬规矩）：整体替换时原样保留。
        List<String> kept = new ArrayList<>();
        for (String term : PromptEditor.parts(text)) {
            if (!TermCategories.isLoraOrEmbedding(term)) continue;
            if (result.stream().anyMatch(existing -> PromptEditor.key(existing).equals(PromptEditor.key(term)))) continue;
            result.add(term);
            kept.add(term);
        }
        String updated = String.join(", ", result);
        if (sameKeys(text, updated)) updated = text;      // 与原文等价时连空白都不动
        return new Change(updated, List.copyOf(intent.terms()), List.copyOf(kept), List.of());
    }

    private static Change add(String text, Intent intent) {
        List<String> current = new ArrayList<>(PromptEditor.parts(text));
        List<String> changed = new ArrayList<>(), unchanged = new ArrayList<>();
        for (String term : intent.terms()) {
            if (current.stream().anyMatch(existing -> PromptEditor.key(existing).equals(PromptEditor.key(term)))) {
                unchanged.add(term);
                continue;
            }
            current.add(term);
            changed.add(term);
        }
        if (changed.isEmpty()) return new Change(text, List.of(), unchanged, List.of());
        return new Change(String.join(", ", current), changed, unchanged, List.of());
    }

    private static Change remove(String text, Intent intent) {
        List<String> kept = new ArrayList<>(), changed = new ArrayList<>(), missing = new ArrayList<>();
        for (String term : PromptEditor.parts(text)) {
            String key = PromptEditor.key(term);
            boolean hit = intent.terms().stream().anyMatch(target -> PromptEditor.key(target).equals(key));
            if (hit) changed.add(term); else kept.add(term);
        }
        for (String target : intent.terms())
            if (changed.stream().noneMatch(term -> PromptEditor.key(term).equals(PromptEditor.key(target)))) missing.add(target);
        if (changed.isEmpty()) return new Change(text, List.of(), List.of(), missing);
        return new Change(String.join(", ", kept), changed, List.of(), missing);
    }

    private static Change swap(String text, Intent intent) {
        List<String> current = PromptEditor.parts(text);
        List<String> output = new ArrayList<>(), changed = new ArrayList<>(), missing = new ArrayList<>();
        List<Integer> usedSources = new ArrayList<>();
        boolean inserted = false;
        for (String term : current) {
            int source = -1;
            if (intent.from().size() > 1) {
                for (int i = 0; i < intent.from().size(); i++)
                    if (!usedSources.contains(i) && matchesAnyTerm(term, intent.from().get(i))) { source = i; break; }
            } else if (!usedSources.contains(0) && matchesAnyTerm(term, intent.from().get(0))) {
                source = 0;
            }
            if (source < 0) { output.add(term); continue; }
            usedSources.add(source);
            changed.add(term);
            // 单个来源换成一组目标时只替换第一次命中的位置，避免同一条被替换多次而重复写入。
            if (intent.from().size() == 1) {
                if (!inserted) { output.addAll(intent.terms()); inserted = true; }
            } else {
                output.add(intent.terms().get(source));
            }
        }
        for (int i = 0; i < intent.from().size(); i++) if (!usedSources.contains(i)) missing.add(intent.from().get(i));
        if (changed.isEmpty()) return new Change(text, List.of(), List.of(), missing);
        return new Change(String.join(", ", output), changed, List.of(), missing);
    }

    /** 一个词条是不是对得上某个来源说法（规范化键相等，或唯一的包含匹配）。 */
    private static boolean matchesAnyTerm(String term, String source) {
        String key = PromptEditor.key(term), wanted = PromptEditor.key(source);
        if (key.equals(wanted)) return true;
        return !wanted.isEmpty() && key.contains(wanted);
    }

    private static boolean sameKeys(String before, String after) {
        List<String> a = PromptEditor.parts(before).stream().map(PromptEditor::key).toList();
        List<String> b = PromptEditor.parts(after).stream().map(PromptEditor::key).toList();
        return a.equals(b);
    }

    /**
     * 失败回执里的"我认得的形式"清单。回执文案与测试共用这一份，避免以后改了文案没人知道测试在钉什么。
     */
    public static String formsHelp() {
        return "我认得的形式（其余说法交给模型判断）：\n"
                + "· 改为 <英文词条>／反向改为 <英文词条>：整体替换某一侧提示词\n"
                + "· 加上 <英文词条>／反向加上 <英文词条>：追加到某一侧（已有的不会重复加）\n"
                + "· 删掉 <英文词条>／反向删掉 <英文词条>：只从某一侧删除，不碰另一侧\n"
                + "· 把 <旧词条> 改成 <新词条>：只换掉匹配到的那个词条\n"
                + "· 仅保留人物和服饰／删除环境：按类别筛选（这条由分类逻辑直接执行）\n"
                + "要写入的内容是中文描述时（例如「改为素股」「加入渔网袜」），我会交给模型翻译成标准英文词条，"
                + "不会把中文写进 prompt；写成英文（例如「改为 thigh sex」「加入 fishnet pantyhose」）我就能直接执行。";
    }

    /** 回执里报词条用：给列表加个上限，免得一次刷屏。 */
    static String brief(List<String> terms) {
        if (terms == null || terms.isEmpty()) return "（无）";
        int shown = Math.min(12, terms.size());
        return String.join("、", terms.subList(0, shown)) + (terms.size() > shown ? " 等 " + terms.size() + " 项" : "");
    }
}

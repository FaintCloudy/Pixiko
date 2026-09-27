package cn.szu.bot.chat;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import cn.szu.bot.Log;

/**
 * 原作文本复现：把 data/kotori-corpus.txt 里「别人一句 → 小鸟一句」的成对台词建成索引，
 * 聊天时用字符 2-gram 重合度检索最像的几条，把「原作同场景问答」作为参考台词注入提示词。
 *
 * <p>为什么用 n-gram 而不是向量库：语料是几十万字的中文对白，检索只需要"像不像同一句话"，
 * 2-gram 重合度足够准，且零依赖、可离线、每次调用是微秒级（不增加模型调用次数）。
 *
 * <p>认不出来的消息不硬塞：退化成固定的一组"风格锚点"（同样取自语料），只当口癖参考。
 * 注入内容用后即弃，不写进对话历史。
 */
public final class KotoriCorpus {
    /**
     * 禁止出现在她话里的"AI 陪伴/心理咨询话术"（用户点名：这类腔调本身就该被排除，而不是去回应它）。
     * 常量便于测试断言与程序级守卫共用同一份清单。
     */
    public static final List<String> COMPANION_PHRASES = List.of(
            "接住", "稳稳", "抱抱", "被看见", "你的感受", "共情", "倾听", "陪着你", "陪伴你", "给你空间",
            "允许自己", "疗愈", "情绪价值", "边界感", "守护你", "偏爱你", "无条件", "慢慢来，不", "慢慢来哦",
            "你已经很努力", "有我在", "我会一直在", "我一直都在", "我会陪", "我会懂你");
    /** 说教/元解释/凭空动作：用户贴的那条真实回复里的词，必须不再出现。 */
    public static final List<String> LECTURE_PHRASES = List.of(
            "站得稳", "份量", "不领情", "先把水喝了", "价值观", "我理解你", "希望你能明白", "你要明白", "说白了");
    /** 回复里命中了哪条禁止话术；干净就返回 null。 */
    public static String forbiddenReply(String reply) {
        if (reply == null || reply.isBlank()) return null;
        for (String phrase : COMPANION_PHRASES) if (reply.contains(phrase)) return "AI 陪伴腔：" + phrase;
        for (String phrase : LECTURE_PHRASES) if (reply.contains(phrase)) return "说教/元解释/凭空动作：" + phrase;
        return null;
    }
    /** 一条原作问答：别人说的那句 + 紧接着小鸟的那句。 */
    /**
     * 一条原作问答：对方那句（prompt）+ 紧接着小鸟那句（reply）+ 场景名，
     * 另带 sceneIndex/replyLine，用来在命中时取回**整个场景片段**（前后各若干轮）。
     */
    public record Pair(String prompt, String reply, String scene, int sceneIndex, int replyLine) {}
    /** 素材条目：功能标签 + 语料原句 + 出处（seen 编号），保证"都是真原句"。 */
    public record Material(String fn, String text, String seen) {}
    /** 消息的场景分类：情感/亲昵类会强制检索并优先照搬，风格锚点也按场景取。 */
    public enum Topic { AFFECTION, PRAISE, INVITATION, DAILY }
    /** 一次检索结果：命中的原作问答（可能为空）+ 风格锚点 + 是否命中 + 场景 + 是否属于"强制检索"。 */
    public record Reference(List<Pair> hits, List<String> anchors, boolean matched, Topic topic, boolean forced) {
        public boolean isEmpty() { return hits.isEmpty() && anchors.isEmpty(); }
    }
    /** 归一化重合度达到这个值就算命中。 */
    public static final double HIT_OVERLAP = 0.35;
    /** 或者：共享的 2-gram 达到这个数量也算命中。 */
    public static final int HIT_SHARED = 3;
    /** 参考台词注入的字数上限（G1：放宽到 2000，取代原来的 1200）。 */
    public static final int MAX_INJECT_CHARS = 2000;
    /** 命中时最多注入几条。 */
    public static final int DEFAULT_TOP_K = 3;
    /** 风格锚点条数。 */
    private static final int ANCHOR_COUNT = 8;
    /** 场景锚点条数（情感/夸奖/邀约各一套）。 */
    private static final int TOPIC_ANCHOR_COUNT = 5;
    /** 一句话短到这个长度才可能当"短应和"锚点。 */
    private static final int SHORT_LINE = 10;
    /** 被表白/被夸时她那些"极短、含糊、退开"的原句长度上限。 */
    private static final int EVASIVE_LINE = 14;
    /** 情感/亲昵：这类消息强制检索并优先照搬（用户投诉的那条就是被前缀稀释导致没命中）。 */
    private static final java.util.regex.Pattern AFFECTION = java.util.regex.Pattern.compile(
            "(?s).*(喜欢你|喜欢上|我喜欢|爱你|爱上|表白|告白|交往|在一起|做我的|我的人|结婚|娶|嫁|想你|想我|有没有想|念我|"
            + "抱抱|抱一下|抱我|亲一下|亲亲|亲我|"
            + "接住你|守护你|一直陪|陪着你|保护你|养你|ずっと|好き|大好き|want you|love you).*");
    /** 夸奖（会被她淡化、拨开）。 */
    private static final java.util.regex.Pattern PRAISE = java.util.regex.Pattern.compile(
            "(?s).*(你真厉害|好厉害|太厉害|真棒|好棒|真可爱|好可爱|真漂亮|好漂亮|真温柔|好温柔|真帅|好帅|了不起|干得好|做得不错|"
            + "谢谢你|谢谢|辛苦了|真可靠).*");
    /** 邀约（她会打岔、转移）。 */
    private static final java.util.regex.Pattern INVITATION = java.util.regex.Pattern.compile(
            "(?s).*(一起去|要不要去|去不去|来我家|去我家|约会|去玩|出去走走|陪我|等你下班|请你吃|带你).*");
    /** 消息属于哪类场景：情感/夸奖/邀约各有专门的原句锚点。 */
    public static Topic topicOf(String message) {
        String text = message == null ? "" : message;
        if (AFFECTION.matcher(text).matches()) return Topic.AFFECTION;
        if (PRAISE.matcher(text).matches()) return Topic.PRAISE;
        if (INVITATION.matcher(text).matches()) return Topic.INVITATION;
        return Topic.DAILY;
    }
    /**
     * 剥掉唤醒前缀：`@某人`、`[CQ:at,qq=…]`、开头叫她名字（小鸟/小鳥/ことり/Kotori/Loriko）以及"在吗/你好"。
     * 这些前缀会把 2-gram 重合度稀释成"没命中"——实测「@Loriko 小鸟，我喜欢你。」因此错过了
     * 语料里「我喜欢你，小鸟。」→「…唔。」这一对。
     */
    public static String stripWakePrefix(String text) {
        if (text == null) return "";
        String value = text.strip();
        value = value.replaceAll("(?is)\\[CQ:at,[^\\]]*\\]", " ");
        value = value.replaceAll("(?s)^\\s*(?:@\\S+\\s*)+", " ");
        value = value.replaceAll("(?s)^\\s*(?:小鸟|小鳥|ことり|kotori|Loriko|loriko)\\s*[，,、：:!！~～]?\\s*", " ");
        value = value.replaceAll("(?s)^\\s*(?:在吗|在么|你好|hello|hi)\\s*[，,、：:!！~～]?\\s*", " ");
        return value.strip();
    }

    private final List<Pair> pairs = new ArrayList<>();
    /** 每个场景的完整行（说话人 + 台词），命中时用来取回前后各几轮的整段片段。 */
    private final List<List<String[]>> sceneLines = new ArrayList<>();
    private final List<String> anchors = new ArrayList<>();
    /** 按场景分好的锚点：情感/夸奖/邀约各一套，命中失败时按消息类型取同场景的原句。 */
    private final List<String> affectionAnchors = new ArrayList<>();
    private final List<String> praiseAnchors = new ArrayList<>();
    private final List<String> invitationAnchors = new ArrayList<>();
    /** 六类轮换样板（短应和/短吐槽/被夸/沉重/告白/噱头），未命中时按会话轮数轮换注入。 */
    private final List<List<String>> anchorGroups = new ArrayList<>();
    /** 噱头素材库：她造过的谐音/改读/自造词，全是语料原句。 */
    private final List<String> gimmicks = new ArrayList<>();
    /** 小鸟式断言（短、！收尾）：喝止/否决/欢呼/催促。 */
    private final List<Material> assertions = new ArrayList<>();
    /** 语气词与应和（短、口语）：按功能分组注入。 */
    private final List<Material> particles = new ArrayList<>();
    /** 素材库来源：全部小鸟台词（含连续独白，用 seen 记出处）。 */
    private final List<Material> allReplies = new ArrayList<>();
    /** 标点素材：！？……～—— 各自的真实用法例句。 */
    private final Map<String, List<Material>> punctuation = new LinkedHashMap<>();
    /** 短句池，锚点从这里按固定步长取样，保证每次启动结果一致。 */
    private final List<String> shortLines = new ArrayList<>();
    private final Path file;

    public KotoriCorpus(Path file) {
        this.file = file;
        load();
    }
    /** 语料文件（相对 bot.home）。 */
    public static Path defaultFile(Path root) { return root.resolve("data/kotori-corpus.txt"); }
    public int pairCount() { return pairs.size(); }
    public int anchorCount() { return anchors.size(); }

    /**
     * 解析语料。格式固定：`===== seenXXXXX =====` 分节，节内每行 `【说话人】「台词」`。
     * 解析失败/文件缺失只记一行日志，绝不让机器人起不来。
     */
    private void load() {
        if (!Files.isRegularFile(file)) {
            Log.info("原作语料不存在（" + file + "）：原作复现只走风格锚点；把原文保存到该路径即可启用。");
            return;
        }
        String text;
        try { text = Files.readString(file); }
        catch (Exception error) { Log.warn("读取原作语料失败，本次只走风格锚点：" + error.getMessage()); return; }
        String scene = "";
        String previousSpeaker = "";
        String previousText = "";
        int sceneIndex = -1;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("=====")) {
                scene = line.replace("=", "").strip();
                sceneLines.add(new ArrayList<>());
                sceneIndex++;
                previousSpeaker = ""; previousText = "";
                continue;
            }
            String speaker = speakerOf(line);
            if (speaker == null) continue;
            String said = quoteOf(line);
            if (said.isEmpty()) continue;
            if (sceneIndex < 0) { sceneLines.add(new ArrayList<>()); sceneIndex++; }
            List<String[]> current = sceneLines.get(sceneIndex);
            current.add(new String[]{speaker, said});
            if (isKotori(speaker)) {
                // 前一句是别人说的才成对：小鸟自己连说两句不算问答。
                if (!previousSpeaker.isEmpty() && !isKotori(previousSpeaker))
                    pairs.add(new Pair(previousText, said, scene, sceneIndex, current.size() - 1));
                if (said.length() <= SHORT_LINE) shortLines.add(said);
                // 素材库用**全部**小鸟台词（含连续独白），这样省略号/语气词/断言才有足够样本。
                allReplies.add(new Material("", said, scene));
            }
            previousSpeaker = speaker;
            previousText = said;
        }
        buildAnchors();
        buildTopicAnchors();
        buildGimmicks();
        buildAnchorGroups();
        buildSpeechMaterials();
        Log.info("原作语料已载入：" + pairs.size() + " 组问答（" + sceneLines.size() + " 个场景）、通用锚点 " + anchors.size()
                + " 条、情感 " + affectionAnchors.size() + " 条、夸奖 " + praiseAnchors.size() + " 条、邀约 " + invitationAnchors.size()
                + " 条、噱头素材 " + gimmicks.size() + " 条、轮换样板 " + anchorGroups.size() + " 组、断言 "
                + assertions.size() + " 条、语气词 " + particles.size() + " 条、标点素材 "
                + punctuation.values().stream().mapToInt(List::size).sum() + " 条（" + file + "）");
    }
    /**
     * 任务 I 的素材库：小鸟式断言、语气词、标点用法——**全部从语料里抽真原句**，并记下 seen 出处。
     */
    private void buildSpeechMaterials() {
        java.util.Set<String> seenTexts = new java.util.LinkedHashSet<>();
        for (Material line : allReplies) {
            String reply = line.text();
            if (reply.isEmpty()) continue;
            String seen = line.seen();
            if (reply.length() <= 24 && reply.matches("(?s).*[！!]\\s*$") && seenTexts.add("A:" + reply))
                if (assertions.size() < 40) assertions.add(new Material(assertionKind(reply), reply, seen));
            if (reply.length() <= 8 && !reply.contains("。") && seenTexts.add("P:" + reply))
                if (particles.size() < 60) particles.add(new Material(particleKind(reply), reply, seen));
            for (String mark : List.of("！", "？", "……", "～", "——")) {
                if (!reply.contains(mark) || reply.length() > 24) continue;
                if (!seenTexts.add("M" + mark + ":" + reply)) continue;
                List<Material> bucket = punctuation.computeIfAbsent(mark, key -> new ArrayList<>());
                if (bucket.size() < 8) bucket.add(new Material(punctuationFunction(mark), reply, seen));
            }
        }
        // 单个 … 也算省略号用法（她经常只写一个）。
        for (Material line : allReplies) {
            String reply = line.text();
            if (reply.isEmpty() || reply.length() > 24 || !reply.contains("…") || reply.contains("……")) continue;
            if (!seenTexts.add("M…:" + reply)) continue;
            List<Material> bucket = punctuation.computeIfAbsent("……", key -> new ArrayList<>());
            if (bucket.size() < 8) bucket.add(new Material(punctuationFunction("……"), reply, line.seen()));
        }
    }
    static String assertionKind(String reply) {
        if (reply.matches("(?s).*(禁止|住手|住口|别闹|够了|好啦好啦|回去啦|到此为止|不行|NG|ＮＧ|免了|拒绝|不检点).*")) return "喝止/否决";
        if (reply.matches("(?s).*(YES|ＹＥＳ|Yea|Ｙｅａ|好耶|ＦＵ|了不起|干得漂亮).*")) return "肯定/欢呼";
        if (reply.matches("(?s).*(上课|回去|拜拜|走吧|开始).*")) return "催促/解散";
        return "断言";
    }
    static String particleKind(String reply) {
        if (reply.matches("(?s).*(嗯|哦|原来|是这样|是呐|也是呢).*")) return "应和";
        if (reply.matches("(?s).*(诶|什么|怎样|为什么|哈|嚯).*")) return "疑问/装傻";
        if (reply.matches("(?s).*(唔|呒|呼|啊啦).*")) return "迟疑/思考";
        if (reply.matches("(?s).*(哇|好耶|ＦＵ|真好|哦哦).*")) return "惊喜/兴奋";
        if (reply.matches("(?s).*(切|笨蛋|坏心眼|恶心|唔哇).*")) return "不满/吐槽";
        if (reply.matches("(?s).*(算了|算啦|好忙|午饭).*")) return "敷衍/逃避";
        if (reply.matches("(?s).*(诶嘿|呋呋|啊哈哈|嘿嘿).*")) return "笑";
        return "语气词";
    }
    static String punctuationFunction(String mark) {
        return switch (mark) {
            case "！" -> "喝止、断言、欢呼、催促（短句 + 一句最多一个）";
            case "？" -> "装傻、反问、不解（常单独成句）";
            case "……" -> "停顿、迟疑、沉默、回避（最高频；允许整句只有省略号）";
            case "～" -> "拖长音、轻快、耍赖（节制，一轮最多一处）";
            default -> "被自己或对方打断、硬转话题、自我更正（省略号=留白，破折号=打断）";
        };
    }
    /** 断言/语气词/标点的注入素材（按轮数轮换取样，全部带 seen 出处）。 */
    public String speechMaterialText(int turn) {
        if (assertions.isEmpty() && particles.isEmpty() && punctuation.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        if (!assertions.isEmpty()) {
            text.append("她的小鸟式断言（短句＋！收尾；喝止不解释、不说教；只用于非执行类）：\n");
            for (String kind : List.of("喝止/否决", "肯定/欢呼", "催促/解散", "断言")) {
                List<String> sample = sampleByKind(assertions, kind, 3, turn);
                if (!sample.isEmpty()) text.append("· ").append(kind).append("：").append(String.join("／", sample)).append("\n");
            }
        }
        if (!particles.isEmpty()) {
            text.append("她的语气词（按功能用）：\n");
            for (String kind : List.of("应和", "疑问/装傻", "迟疑/思考", "惊喜/兴奋", "不满/吐槽", "敷衍/逃避", "笑")) {
                List<String> sample = sampleByKind(particles, kind, 3, turn);
                if (!sample.isEmpty()) text.append("· ").append(kind).append("：").append(String.join("／", sample)).append("\n");
            }
        }
        if (!punctuation.isEmpty()) {
            text.append("标点按功能用（反例：平淡闲聊里乱撒 ！、拿……当装饰、句末堆 ～）：\n");
            for (Map.Entry<String, List<Material>> entry : punctuation.entrySet()) {
                List<Material> bucket = entry.getValue();
                List<String> sample = new ArrayList<>();
                for (int index = 0; index < 2 && index < bucket.size(); index++) sample.add(bucket.get((Math.max(0, turn) + index) % bucket.size()).text());
                text.append("· ").append(entry.getKey()).append("＝").append(punctuationFunction(entry.getKey()))
                    .append("，例：").append(String.join("／", sample)).append("\n");
            }
        }
        return text.toString();
    }
    private static List<String> sampleByKind(List<Material> source, String kind, int limit, int turn) {
        List<Material> same = new ArrayList<>();
        for (Material item : source) if (item.fn().equals(kind)) same.add(item);
        List<String> result = new ArrayList<>();
        for (int index = 0; index < limit && index < same.size(); index++)
            result.add(same.get(Math.floorMod(turn, same.size()) >= 0 ? (Math.floorMod(turn, same.size()) + index) % same.size() : index).text());
        return result;
    }
    /** 素材库规模（测试与日志用）。 */
    public int assertionCount() { return assertions.size(); }
    public int particleCount() { return particles.size(); }
    public int punctuationCount() { return punctuation.values().stream().mapToInt(List::size).sum(); }
    /** `【名字】「台词」` 里的名字；不是这个格式就返回 null。 */
    static String speakerOf(String line) {
        if (!line.startsWith("【")) return null;
        int end = line.indexOf('】');
        if (end <= 1) return null;
        return line.substring(1, end).strip();
    }
    /** `「台词」` 里的台词；取不到就返回空串（例如旁白行）。 */
    static String quoteOf(String line) {
        int start = line.indexOf('「');
        int end = line.lastIndexOf('」');
        if (start < 0 || end <= start) return "";
        return line.substring(start + 1, end).strip();
    }
    static boolean isKotori(String speaker) {
        return speaker.contains("小鸟") || speaker.contains("小鳥") || speaker.contains("ことり") || speaker.contains("Kotori");
    }
    /**
     * 风格锚点：从语料里挑真正存在的短句——短应和、省略号、淡化夸奖、短吐槽各取一点。
     * 全是语料原句，绝不自己写。
     */
    private void buildAnchors() {
        LinkedHashSet<String> picked = new LinkedHashSet<>();
        pickInto(picked, line -> line.length() <= SHORT_LINE && (line.contains("～") || line.contains("嗯") || line.contains("诶") || line.contains("哦")), 3);
        pickInto(picked, line -> line.contains("是吗") || line.contains("也是呢") || line.contains("原来如此"), 2);
        pickInto(picked, line -> line.contains("……") || line.contains("..."), 1);
        pickInto(picked, line -> line.contains("笨蛋") || line.contains("恶心") || line.contains("小气") || line.contains("大笨蛋"), 1);
        pickInto(picked, line -> line.endsWith("呐") || line.endsWith("哟") || line.endsWith("呢"), 1);
        if (picked.size() < ANCHOR_COUNT) {
            // 还不够就用短句池按固定步长补齐（确定性取样，不随启动变化）。
            int step = Math.max(1, shortLines.size() / (ANCHOR_COUNT + 1));
            for (int index = 0; index < shortLines.size() && picked.size() < ANCHOR_COUNT; index += step) picked.add(shortLines.get(index));
        }
        anchors.addAll(picked);
        if (anchors.size() > ANCHOR_COUNT) anchors.subList(ANCHOR_COUNT, anchors.size()).clear();
    }
    private void pickInto(Set<String> into, java.util.function.Predicate<String> wanted, int limit) {
        int added = 0;
        for (String line : shortLines) {
            if (added >= limit) break;
            if (into.contains(line) || !wanted.test(line)) continue;
            into.add(line); added++;
        }
    }
    /**
     * 场景锚点：表白/夸奖/邀约这三类，用户明确要求"命中失败时给同类场景的原句"，
     * 而不是通用锚点。取自语料里对应场景的**回答**，优先短、含糊、退开的那种。
     */
    private void buildTopicAnchors() {
        collectTopic(affectionAnchors, AFFECTION);
        collectTopic(praiseAnchors, PRAISE);
        collectTopic(invitationAnchors, INVITATION);
    }
    private void collectTopic(List<String> into, java.util.regex.Pattern topic) {
        LinkedHashSet<String> picked = new LinkedHashSet<>();
        // 第一优先：极短、含糊、退开的回答（「…唔。」「原来如此。」「诶——？」「………」）
        for (Pair pair : pairs) {
            if (picked.size() >= TOPIC_ANCHOR_COUNT) break;
            if (!topic.matcher(pair.prompt()).matches()) continue;
            String reply = pair.reply();
            if (reply.isEmpty() || reply.length() > EVASIVE_LINE) continue;
            if (!reply.matches("(?s).*(唔|嗯|诶|原来|是吗|这样啊|……|\\.\\.\\.|对不起|不知道|诶嘿).*")) continue;
            picked.add(reply);
        }
        // 第二优先：同场景里最短的几句回答，同样是语料原句。
        if (picked.size() < TOPIC_ANCHOR_COUNT) {
            List<String> shortest = new ArrayList<>();
            for (Pair pair : pairs) {
                if (!topic.matcher(pair.prompt()).matches()) continue;
                String reply = pair.reply();
                if (!reply.isEmpty() && reply.length() <= EVASIVE_LINE + 6) shortest.add(reply);
            }
            shortest.sort(Comparator.comparingInt(String::length));
            for (String reply : shortest) {
                if (picked.size() >= TOPIC_ANCHOR_COUNT) break;
                picked.add(reply);
            }
        }
        into.addAll(picked);
        if (into.size() > TOPIC_ANCHOR_COUNT) into.subList(TOPIC_ANCHOR_COUNT, into.size()).clear();
    }
    /** 该场景的锚点（没有就退回通用锚点）。 */
    List<String> anchorsFor(Topic topic) {
        List<String> picked = switch (topic) {
            case AFFECTION -> affectionAnchors;
            case PRAISE -> praiseAnchors;
            case INVITATION -> invitationAnchors;
            case DAILY -> anchors;
        };
        return picked.isEmpty() ? List.copyOf(anchors) : List.copyOf(picked);
    }
    /** 噱头素材库：从语料里挑出她造过的谐音/改读/自造词（带引号玩梗、括号注、押韵注、Gesu 尾）。 */
    private void buildGimmicks() {
        for (Pair pair : pairs) {
            String reply = pair.reply();
            if (reply.length() > 40) continue;
            boolean looksLikeGag = reply.contains("「") || reply.contains("（") || reply.contains("(笑)")
                    || reply.contains("押韵") || reply.contains("Gesu") || reply.contains("gesu")
                    || reply.contains("嘛，") || reply.matches("(?s).*[A-Za-z]{2,}.*");
            if (!looksLikeGag) continue;
            if (gimmicks.contains(reply)) continue;
            gimmicks.add(reply);
            if (gimmicks.size() >= 40) break;
        }
    }
    /** 六类轮换样板：短应和/短吐槽/被夸/沉重/告白/噱头，每类 1～2 条，全是语料原句。 */
    private void buildAnchorGroups() {
        anchorGroups.add(pickReplies(line -> line.length() <= SHORT_LINE && (line.contains("～") || line.contains("嗯") || line.contains("是吗")), 2));
        anchorGroups.add(pickReplies(line -> line.matches("(?s).*(村民|游戏脑|大笨蛋|坏心眼|小气|恶心|你这种人).*"), 2));
        anchorGroups.add(pickReplies(line -> line.matches("(?s).*(诶，是吗|才没有|哪有|讨厌|好丢人|没有啦).*"), 2));
        anchorGroups.add(pickReplies(line -> line.matches("(?s).*(………|……|唔…|对不起|我不知道|不想说).*"), 2));
        anchorGroups.add(affectionAnchors.isEmpty() ? List.of() : List.copyOf(affectionAnchors.subList(0, Math.min(2, affectionAnchors.size()))));
        anchorGroups.add(gimmicks.isEmpty() ? List.of() : List.copyOf(gimmicks.subList(0, Math.min(2, gimmicks.size()))));
    }
    private List<String> pickReplies(java.util.function.Predicate<String> wanted, int limit) {
        List<String> picked = new ArrayList<>();
        for (Pair pair : pairs) {
            if (picked.size() >= limit) break;
            String reply = pair.reply();
            if (reply.isEmpty() || picked.contains(reply) || !wanted.test(reply)) continue;
            picked.add(reply);
        }
        return List.copyOf(picked);
    }
    /**
     * 命中时取出**整个场景片段**：命中那句前后各若干轮，含对方的台词，原样保留
     * `【说话人】「台词」` 的行式与标点——用户要求"给整段，不是只给一句"。
     */
    public String fragment(Pair pair, int before, int after) {
        if (pair == null || pair.sceneIndex() < 0 || pair.sceneIndex() >= sceneLines.size()) return "";
        List<String[]> lines = sceneLines.get(pair.sceneIndex());
        int from = Math.max(0, pair.replyLine() - Math.max(0, before));
        int to = Math.min(lines.size() - 1, pair.replyLine() + Math.max(0, after));
        StringBuilder text = new StringBuilder();
        for (int index = from; index <= to; index++) {
            String[] line = lines.get(index);
            text.append("【").append(line[0]).append("】「").append(line[1]).append("」\n");
        }
        return text.toString();
    }
    /** 命中片段的轮数：命中那句前后各 3～6 轮（含对方台词）。 */
    public static final int FRAGMENT_BEFORE = 4;
    public static final int FRAGMENT_AFTER = 4;
    /**
     * 检索：拿用户这句话去找最像的原作提问，命中就返回它和紧随其后的小鸟回答。
     * 先剥唤醒前缀（@某人、CQ at、叫她名字），否则重合度会被前缀稀释成"没命中"。
     * 情感/亲昵类消息**强制检索**，并且只在同场景候选里选，命中失败时给同场景锚点。
     */
    public Reference reference(String message, int topK) {
        Topic topic = topicOf(message);
        boolean forced = topic == Topic.AFFECTION;
        List<String> anchorsCopy = anchorsFor(topic);
        String query = clean(message);
        if (query.length() < 2 || pairs.isEmpty()) return new Reference(List.of(), anchorsCopy, false, topic, forced);
        Set<String> wanted = grams(query);
        // 情感类：只在"对方那句也属于情感类"的候选里检索，避免被别的场景抢走。
        List<Pair> pool = new ArrayList<>();
        for (Pair pair : pairs) {
            if (topic != Topic.DAILY && topicOf(pair.prompt()) != topic) continue;
            pool.add(pair);
        }
        if (pool.isEmpty()) pool = pairs;
        List<Pair> scored = new ArrayList<>();
        Map<Pair, Double> scores = new HashMap<>();
        for (Pair pair : pool) {
            String candidate = clean(pair.prompt());
            if (candidate.isEmpty()) continue;
            Set<String> have = grams(candidate);
            int shared = 0;
            for (String gram : wanted) if (have.contains(gram)) shared++;
            double overlap = (double) shared / Math.max(1, Math.min(wanted.size(), have.size()));
            if (overlap < HIT_OVERLAP && shared < HIT_SHARED) continue;
            scores.put(pair, overlap);
            scored.add(pair);
        }
        if (scored.isEmpty()) return new Reference(List.of(), anchorsCopy, false, topic, forced);
        scored.sort((left, right) -> {
            int byScore = Double.compare(scores.get(right), scores.get(left));
            if (byScore != 0) return byScore;
            // 同分优先"极短、退开"的回答：这才是她面对这类话的真实反应。
            int leftLen = left.reply().length(), rightLen = right.reply().length();
            return Integer.compare(leftLen, rightLen);
        });
        int wanted_count = Math.max(1, Math.min(5, topK));
        List<Pair> hits = new ArrayList<>();
        Set<String> seenReplies = new LinkedHashSet<>();
        for (Pair pair : scored) {
            if (hits.size() >= wanted_count) break;
            // 同一句回答只注入一次，避免三条参考全是同一个反应。
            if (!seenReplies.add(pair.reply())) continue;
            hits.add(pair);
        }
        return new Reference(List.copyOf(hits), anchorsCopy, !hits.isEmpty(), topic, forced);
    }
    static String clean(String text) {
        if (text == null) return "";
        String value = text.replaceAll("\\[(?:引用|我这条消息)\\][^\\n]*", " ");
        return stripWakePrefix(value).replaceAll("[\\s，,。.！!？?、；;：:~～★]+", "").strip();
    }
    /** 字符 2-gram（中文短句检索够用，且不需要分词库）。 */
    static Set<String> grams(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 1 < text.length(); index++) result.add(text.substring(index, index + 2));
        return result;
    }
    /** 带 `！` 的断言样例（取自语料原句，轮换取样）。 */
    public List<String> bangAssertions(int turn, int limit) {
        List<String> result = new ArrayList<>();
        for (Material item : assertions) {
            if (result.size() >= limit) break;
            String text = item.text();
            if (!text.contains("！") && !text.contains("!")) continue;
            if (result.contains(text)) continue;
            result.add(text);
        }
        if (result.size() > 1) {
            int offset = Math.floorMod(turn, result.size());
            result = new ArrayList<>(result.subList(offset, result.size()));
            result.addAll(new ArrayList<>(List.copyOf(result)).subList(0, 0));
        }
        return result;
    }
    /** 噱头素材（轮换取样，条数按 L2 加倍）。 */
    public List<String> gimmickSample(int turn, int limit) {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < limit && index < gimmicks.size(); index++)
            result.add(gimmicks.get(Math.floorMod(turn + index, gimmicks.size())));
        return result;
    }
    /** 把检索结果写成注入提示词的一段文本（不超过 {@link #MAX_INJECT_CHARS} 字，turn 决定样板轮换）。 */
    public String injectText(Reference reference, int turn) {
        if (reference == null || reference.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        // L1/L2：带 ！ 的断言与噱头素材放到**最前面**（放后面模型会忽略），条数加倍。
        List<String> bang = bangAssertions(turn, 5);
        if (!bang.isEmpty()) {
            text.append("【本轮至少用一句】她的短断言（句末带 `！`，一句里只用一个感叹号）：")
                .append(String.join("／", bang))
                .append("；日常回合请把这一句用在回应里（例如「不行哟！」「就这样定了！」「去吧！」）。\n");
        }
        List<String> gag = gimmickSample(turn, 6);
        if (!gag.isEmpty()) {
            text.append("【本轮必须带一个噱头】她的噱头素材（谐音／改读／自造词）：")
                .append(String.join("／", gag))
                .append("——**日常回合每轮都要有一个**：可以照用上面任一条，也可以拿对方话里的词、本次对话真实存在的东西、"
                        + "或她自己的东西（土／草／特卖／零钱／存钱罐／猪排丼／森林／工房／琪比摩斯）**现场造一个新的**"
                        + "（可以故意读错一个字再自己接住「啊，不对」）；不要解释笑点，也不许编造日常。\n");
        }
        if (reference.matched()) {
            text.append("下面是原作里与你这句话最接近的整段对白。**这些不是参考资料，是你自己说过的话**：\n")
                .append("照这个用词、标点、断句节奏和口气说话（短句独立成句、`…``——`表停顿迟疑、句末多用哦／呐／呢），")
                .append("而不是只把其中一句抄过来。\n");
            for (Pair pair : reference.hits()) {
                String fragment = fragment(pair, FRAGMENT_BEFORE, FRAGMENT_AFTER);
                if (fragment.isBlank()) fragment = "【对方】「" + pair.prompt() + "」\n【小鸟】「" + pair.reply() + "」\n";
                text.append(fragment);
                text.append("（你要回的是上面这一段里「小鸟」的那类反应）\n");
            }
            text.append("优先照搬最贴近的那一句，其余按你自己的口气接；必要时只做贴合当前时间/对象/上下文的最小改写。\n");
        } else {
            text.append("（没有匹配到同场景对白，下面是语料里的轮换样板，照这个味道说）\n");
        }
        List<String> rotated = rotate(reference, turn);
        if (!rotated.isEmpty()) {
            text.append("语料原句的味道参考：");
            text.append(String.join("／", rotated));
            text.append("\n");
        }
        if (!gimmicks.isEmpty()) {
            text.append("她的噱头素材（谐音／改读／自造词，示范用）：");
            List<String> sample = new ArrayList<>();
            for (int index = 0; index < 3 && index < gimmicks.size(); index++) sample.add(gimmicks.get((Math.max(0, turn) + index) % gimmicks.size()));
            text.append(String.join("／", sample));
            text.append("\n有依据地现场造一个新的（可以故意读错一个字再自己接住「啊，不对」）；不要解释笑点。\n");
        }
        String materials = speechMaterialText(turn);
        if (!materials.isBlank()) text.append(materials);
        if (reference.topic() == Topic.AFFECTION) {
            text.append("这是表白/亲昵类：她的真实反应是**极短**——「…唔。」「原来如此。」「诶——？」这种，"
                    + "最多再岔开一句到真实存在的东西上；不许讲道理、不许解释自己为什么拒绝、不许编造递水之类的具体动作。\n");
        } else if (reference.topic() == Topic.PRAISE) {
            text.append("这是夸奖类：先「诶」一下或否认，再把话拨开，不许追着接受夸奖。\n");
        } else if (reference.topic() == Topic.INVITATION) {
            text.append("这是邀约类：含糊、打岔、把话头转开，不许长篇答应或分析。\n");
        }
        String result = text.toString();
        return result.length() > MAX_INJECT_CHARS ? result.substring(0, MAX_INJECT_CHARS) : result;
    }
    /** 轮换样板：按会话轮数在六类里轮着取，避免每轮注入同一批句子。 */
    private List<String> rotate(Reference reference, int turn) {
        List<String> result = new ArrayList<>();
        if (reference.matched()) {
            if (!reference.anchors().isEmpty()) result.addAll(reference.anchors());
            return result;
        }
        int size = anchorGroups.size();
        if (size == 0) return reference.anchors().isEmpty() ? List.of() : new ArrayList<>(reference.anchors());
        int offset = Math.floorMod(turn, size);
        for (int step = 0; step < size; step++) {
            List<String> group = anchorGroups.get((offset + step) % size);
            for (String line : group) if (!result.contains(line)) result.add(line);
            if (result.size() >= 8) break;
        }
        if (result.isEmpty() && !reference.anchors().isEmpty()) result.addAll(reference.anchors());
        return result;
    }
    /** 旁听（只为拿 join）那次调用用的极简版：只给两句味道参考，不注入整段，省 token。 */
    public String briefText(Reference reference) {
        if (reference == null) return "";
        List<String> lines = reference.matched() && !reference.hits().isEmpty()
                ? List.of(reference.hits().get(0).reply())
                : (reference.anchors().isEmpty() ? List.of() : reference.anchors().subList(0, Math.min(2, reference.anchors().size())));
        if (lines.isEmpty()) return "";
        return "她说话的味道（极简提示）：" + String.join("／", lines) + "\n";
    }
    private static volatile KotoriCorpus cached;
    private static volatile long cachedStamp = -1;
    /**
     * 给一条用户消息取出要注入的参考台词文本。关掉复现（chat.corpus_replay=false）时返回空串；
     * 语料文件改了会自动重新载入。任何异常都只记日志、返回空串，绝不影响正常聊天。
     * turn 是当前会话的轮数，用来轮换未命中时的样板组合。
     */
    public static String referenceFor(cn.szu.bot.Settings settings, String message, int turn) {
        try {
            KotoriCorpus corpus = load(settings);
            if (corpus == null) return "";
            if (corpus.pairCount() == 0 && corpus.anchorCount() == 0) return "";
            return corpus.injectText(corpus.reference(message, settings.corpusTopK()), turn);
        } catch (Exception error) {
            Log.warn("原作语料检索失败，本次不注入参考台词：" + cn.szu.bot.Bot.error(error));
            return "";
        }
    }
    /** 保留两参重载（测试与不需要轮换的调用点用）。 */
    public static String referenceFor(cn.szu.bot.Settings settings, String message) {
        return referenceFor(settings, message, 0);
    }
    /** 旁听（只为拿 join）那次调用：只给极简味道提示，不注入整段对白。 */
    public static String briefFor(cn.szu.bot.Settings settings, String message) {
        try {
            KotoriCorpus corpus = load(settings);
            if (corpus == null) return "";
            if (corpus.pairCount() == 0 && corpus.anchorCount() == 0) return "";
            return corpus.briefText(corpus.reference(message, settings.corpusTopK()));
        } catch (Exception error) {
            return "";
        }
    }
    /** 需要时才读语料（含按修改时间失效），失败返回 null。 */
    private static KotoriCorpus load(cn.szu.bot.Settings settings) {
        if (!settings.corpusReplay()) return null;
        Path file = defaultFile(settings.root);
        long stamp;
        try { stamp = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1; }
        catch (Exception error) { return null; }
        KotoriCorpus corpus = cached;
        if (corpus == null || stamp != cachedStamp) {
            corpus = new KotoriCorpus(file);
            cached = corpus; cachedStamp = stamp;
        }
        return corpus;
    }
}

package cn.szu.bot;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import cn.szu.bot.prompt.PromptEditor;
import cn.szu.bot.prompt.PromptUsage;

/**
 * 内置中文词库评测（离线，不调用 DeepSeek）：
 * 1) 规模：词条数与中文写法数都必须 ≥ 20000；
 * 2) 合法性：每条词条都在 data/prompt-tags.txt 里，中文写法非空、不含英文、长度合规；
 * 3) 覆盖面：场景/人物/动作/姿势/表情/环境等分类都要有足够词条；
 * 4) 命中率：给定中文说法，词库必须能给出对应的标准词条（严格候选命中率门槛 90%）。
 *
 * <p>报告写到 {@code work/zh-tag-report.md}，未命中明细写到 {@code work/zh-tag-failures.log}。
 */
public final class ZhTagEval {
    /** 每条：中文说法 + 期望分类（分类只用于报告分组）。 */
    private record Case(String word, String category) {}

    private static final List<Case> CASES = List.of(
            // 人物
            c("人物", "少女"), c("人物", "少年"), c("人物", "男孩"), c("人物", "女孩"), c("人物", "老人"),
            c("人物", "婴儿"), c("人物", "警察"), c("人物", "护士"), c("人物", "医生"), c("人物", "老师"),
            c("人物", "学生"), c("人物", "女仆"), c("人物", "巫女"), c("人物", "骑士"), c("人物", "公主"),
            c("人物", "吸血鬼"), c("人物", "天使"), c("人物", "恶魔"), c("人物", "机器人"), c("人物", "猫娘"),
            c("人物", "偶像"), c("人物", "魔法少女"), c("人物", "修女"), c("人物", "海盗"), c("人物", "忍者"),
            c("人物", "士兵"), c("人物", "侦探"), c("人物", "上班族"), c("人物", "双马尾"), c("人物", "长发"),
            c("人物", "短发"), c("人物", "金发"), c("人物", "蓝眼睛"), c("人物", "猫耳"), c("人物", "尾巴"),
            c("人物", "翅膀"), c("人物", "巨乳"), c("人物", "雀斑"), c("人物", "虎牙"), c("人物", "异色瞳"),
            // 表情
            c("表情", "微笑"), c("表情", "大笑"), c("表情", "哭泣"), c("表情", "流泪"), c("表情", "脸红"),
            c("表情", "害羞"), c("表情", "生气"), c("表情", "愤怒"), c("表情", "惊讶"), c("表情", "震惊"),
            c("表情", "悲伤"), c("表情", "难过"), c("表情", "恐惧"), c("表情", "害怕"), c("表情", "皱眉"),
            c("表情", "眯眼"), c("表情", "闭眼"), c("表情", "张嘴"), c("表情", "吐舌"), c("表情", "无表情"),
            c("表情", "得意"), c("表情", "尴尬"), c("表情", "疑惑"), c("表情", "无奈"), c("表情", "打哈欠"),
            // 动作
            c("动作", "奔跑"), c("动作", "走路"), c("动作", "跳跃"), c("动作", "坐下"), c("动作", "拥抱"),
            c("动作", "亲吻"), c("动作", "牵手"), c("动作", "抚摸"), c("动作", "挥手"), c("动作", "鼓掌"),
            c("动作", "拍照"), c("动作", "自拍"), c("动作", "打电话"), c("动作", "看书"), c("动作", "写字"),
            c("动作", "画画"), c("动作", "弹吉他"), c("动作", "唱歌"), c("动作", "跳舞"), c("动作", "游泳"),
            c("动作", "骑马"), c("动作", "开车"), c("动作", "骑车"), c("动作", "吃饭"), c("动作", "喝水"),
            c("动作", "睡觉"), c("动作", "做饭"), c("动作", "洗澡"), c("动作", "购物"), c("动作", "结账"),
            c("动作", "回头"), c("动作", "转身"), c("动作", "偷看"), c("动作", "注视"), c("动作", "性骚扰"),
            c("动作", "痴汉"), c("动作", "猥亵"), c("动作", "偷拍"), c("动作", "摸头"), c("动作", "抱紧"),
            // 性行为与分镜：.infix 实测缺口（性交没被拆出来、分镜/交合部位查不到词条）
            c("动作", "性交"), c("动作", "性行为"), c("动作", "交合"), c("动作", "插入"), c("动作", "做爱"),
            c("动作", "后入"), c("动作", "骑乘位"), c("动作", "口交"), c("动作", "自慰"), c("动作", "高潮"),
            c("动作", "射精"), c("动作", "呻吟"), c("动作", "挣扎"), c("动作", "捆绑"), c("动作", "张开双腿"),
            c("姿势", "分镜聚焦臀部接触部位"), c("姿势", "分镜展示交合部位"), c("姿势", "交合部位"),
            c("姿势", "臀部为主视角"), c("姿势", "主视角"), c("姿势", "分镜"), c("姿势", "四格漫画"), c("姿势", "分屏"),
            // 姿势
            c("姿势", "盘腿坐"), c("姿势", "侧坐"), c("姿势", "跪坐"), c("姿势", "蹲着"), c("姿势", "趴着"),
            c("姿势", "跪着"), c("姿势", "躺着"), c("姿势", "双手叉腰"), c("姿势", "双手抱胸"), c("姿势", "双手合十"),
            c("姿势", "手托腮"), c("姿势", "张开双腿"), c("姿势", "举起双手"), c("姿势", "靠在墙上"), c("姿势", "躺在草地"),
            c("姿势", "公主抱"), c("姿势", "倒立"), c("姿势", "单腿站立"), c("姿势", "蜷缩"), c("姿势", "跷二郎腿"),
            // 场景
            c("场景", "教室"), c("场景", "学校"), c("场景", "医院"), c("场景", "病房"), c("场景", "图书馆"),
            c("场景", "办公室"), c("场景", "便利店"), c("场景", "超市"), c("场景", "书店"), c("场景", "咖啡厅"),
            c("场景", "餐厅"), c("场景", "酒吧"), c("场景", "厨房"), c("场景", "卧室"), c("场景", "浴室"),
            c("场景", "更衣室"), c("场景", "游泳池"), c("场景", "体育馆"), c("场景", "健身房"), c("场景", "地下室"),
            c("场景", "电梯"), c("场景", "楼梯"), c("场景", "天台"), c("场景", "阳台"), c("场景", "宿舍"),
            c("场景", "酒店"), c("场景", "车站"), c("场景", "月台"), c("场景", "地铁车厢"), c("场景", "机场"),
            c("场景", "公园"), c("场景", "游乐园"), c("场景", "操场"), c("场景", "广场"), c("场景", "花园"),
            c("场景", "神社"), c("场景", "寺庙"), c("场景", "教堂"), c("场景", "城堡"), c("场景", "废墟"),
            c("场景", "墓地"), c("场景", "村庄"), c("场景", "城市"), c("场景", "街道"), c("场景", "小巷"),
            c("场景", "夜市"), c("场景", "祭典"), c("场景", "舞台"), c("场景", "室内"), c("场景", "室外"),
            // 环境
            c("环境", "晴天"), c("环境", "阴天"), c("环境", "下雨"), c("环境", "暴雨"), c("环境", "打雷"),
            c("环境", "闪电"), c("环境", "下雪"), c("环境", "雾"), c("环境", "大风"), c("环境", "彩虹"),
            c("环境", "极光"), c("环境", "日出"), c("环境", "日落"), c("环境", "黄昏"), c("环境", "夜晚"),
            c("环境", "深夜"), c("环境", "满月"), c("环境", "星空"), c("环境", "天空"), c("环境", "云"),
            c("环境", "森林"), c("环境", "草原"), c("环境", "花田"), c("环境", "沙漠"), c("环境", "雪山"),
            c("环境", "海边"), c("环境", "沙滩"), c("环境", "大海"), c("环境", "湖泊"), c("环境", "河流"),
            c("环境", "瀑布"), c("环境", "温泉"), c("环境", "火山"), c("环境", "海底"), c("环境", "樱花"),
            c("环境", "红叶"), c("环境", "草地"), c("环境", "树"), c("环境", "花"), c("环境", "阳光"));

    private static Case c(String category, String word) { return new Case(word, category); }
    private static final List<String> REQUIRED_CATEGORIES = List.of("人物", "动作", "姿势", "表情", "场景", "环境", "服装", "物品", "镜头", "画面");

    public static void main(String[] args) throws Exception {
        double minHitRate = args.length > 0 ? Double.parseDouble(args[0]) : 0.90;
        Path root = Path.of(System.getProperty("bot.home", ".")).toAbsolutePath().normalize();
        Path work = root.resolve("work");
        Files.createDirectories(work);
        Files.writeString(work.resolve("zh-tag-failures.log"), "");
        List<String> failures = new ArrayList<>();
        Path libraryFile = root.resolve("data/prompt-zh-tags.json");
        if (!Files.isRegularFile(libraryFile)) throw new AssertionError("缺少内置中文词库：" + libraryFile);
        JsonObject library = Json.parse(Files.readString(libraryFile, StandardCharsets.UTF_8));
        JsonArray entries = library.getAsJsonArray("entries");
        JsonArray names = library.getAsJsonArray("categories");
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (JsonElement name : names) byCategory.put(name.getAsString(), 0);
        Set<String> dictionary = new HashSet<>();
        for (String line : Files.readAllLines(root.resolve("data/prompt-tags.txt"), StandardCharsets.UTF_8)) {
            String tag = line.strip();
            if (tag.isEmpty() || tag.startsWith("#")) continue;
            dictionary.add(PromptEditor.key(tag));
        }
        int aliases = 0;
        for (JsonElement item : entries) {
            JsonArray row = item.getAsJsonArray();
            String tag = row.get(0).getAsString();
            String text = row.get(1).getAsString();
            String category = names.get(row.get(2).getAsInt()).getAsString();
            byCategory.merge(category, 1, Integer::sum);
            if (!dictionary.contains(PromptEditor.key(tag))) failures.add("词条不在标准词库里：" + tag);
            if (tag.indexOf('(') >= 0 || tag.indexOf(':') >= 0) failures.add("词条带括号/冒号，机器人会过滤：" + tag);
            List<String> words = List.of(text.split("\\|"));
            aliases += words.size();
            if (words.isEmpty()) failures.add("没有中文写法：" + tag);
            if (words.size() > 12) failures.add("中文写法超过 12 个：" + tag);
            for (String word : words) {
                if (word.isBlank()) failures.add("空的中文写法：" + tag);
                else if (word.length() > 12) failures.add("中文写法过长：" + tag + " → " + word);
                else if (word.codePoints().noneMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN))
                    failures.add("中文写法没有汉字：" + tag + " → " + word);
            }
        }
        for (String category : REQUIRED_CATEGORIES)
            if (byCategory.getOrDefault(category, 0) < 90)
                failures.add("分类词条过少：" + category + " 只有 " + byCategory.getOrDefault(category, 0) + " 条（要求 ≥90）");
        if (entries.size() < 20000) failures.add("词条总数不足 20000：" + entries.size());
        if (aliases < 20000) failures.add("中文写法总数不足 20000：" + aliases);

        PromptUsage usage = new PromptUsage(root);
        if (usage.librarySize() != entries.size()) failures.add("载入的词条数与文件不一致：" + usage.librarySize());
        Set<String> allowed = Bot.vocabulary(root, new cn.szu.bot.sd.SdClient.Prompts("", "", "eval"));
        int strictHits = 0, recallHits = 0;
        List<String> lines = new ArrayList<>();
        Map<String, int[]> perCategory = new LinkedHashMap<>();
        for (Case task : CASES) {
            List<String> strict = usage.strictHints(task.word(), allowed, 3);
            List<String> recall = usage.hints(task.word(), allowed, 8);
            boolean strictOk = !strict.isEmpty();
            boolean recallOk = !recall.isEmpty();
            if (strictOk) strictHits++;
            if (recallOk) recallHits++;
            int[] counts = perCategory.computeIfAbsent(task.category(), key -> new int[2]);
            counts[0] += strictOk ? 1 : 0;
            counts[1]++;
            lines.add("| " + task.category() + " | " + task.word() + " | " + (strictOk ? "命中" : "未命中") + " | "
                    + String.join(", ", strict.isEmpty() ? recall.subList(0, Math.min(3, recall.size())) : strict) + " |");
            if (!strictOk) {
                failures.add("严格候选未命中：" + task.word() + "（召回：" + recall.subList(0, Math.min(5, recall.size())) + "）");
                if (!recallOk) failures.add("召回也没有结果：" + task.word());
            }
        }
        double rate = CASES.isEmpty() ? 0 : (double) strictHits / CASES.size();
        double recallRate = CASES.isEmpty() ? 0 : (double) recallHits / CASES.size();
        StringBuilder report = new StringBuilder("# 内置中文词库报告\n\n");
        report.append("词条 ").append(entries.size()).append(" 条，中文写法 ").append(aliases).append(" 个\n\n");
        report.append("分类分布：").append(byCategory.entrySet().stream()
                .filter(item -> item.getValue() > 0).map(item -> item.getKey() + " " + item.getValue())
                .reduce((left, right) -> left + "，" + right).orElse("（无）")).append("\n\n");
        report.append("中文说法命中率：严格候选 ").append(String.format(Locale.ROOT, "%.1f%%", rate * 100))
                .append("（").append(strictHits).append("/").append(CASES.size()).append("，门槛 ")
                .append(String.format(Locale.ROOT, "%.0f%%", minHitRate * 100)).append("），召回 ")
                .append(String.format(Locale.ROOT, "%.1f%%", recallRate * 100)).append("\n\n");
        report.append("| 分类 | 命中/总数 |\n|---|---|\n");
        for (Map.Entry<String, int[]> item : perCategory.entrySet())
            report.append("| ").append(item.getKey()).append(" | ").append(item.getValue()[0]).append("/")
                    .append(item.getValue()[1]).append(" |\n");
        report.append("\n## 逐条结果\n\n| 分类 | 中文说法 | 严格候选 | 标准词条 |\n|---|---|---|---|\n");
        for (String line : lines) report.append(line).append("\n");
        if (!failures.isEmpty()) {
            report.append("\n## 未命中/异常明细（前 80 条）\n\n");
            for (String failure : failures.subList(0, Math.min(80, failures.size()))) report.append("- ").append(failure).append("\n");
        }
        Files.writeString(work.resolve("zh-tag-report.md"), report.toString());
        Files.writeString(work.resolve("zh-tag-failures.log"), String.join("\n", failures));
        System.out.println("ZhTagEval: 词条 " + entries.size() + " 条 / 中文写法 " + aliases + " 个；严格候选命中率 "
                + String.format(Locale.ROOT, "%.1f%%", rate * 100) + "（" + strictHits + "/" + CASES.size() + "），门槛 "
                + String.format(Locale.ROOT, "%.0f%%", minHitRate * 100) + "；报告 " + work.resolve("zh-tag-report.md"));
        if (!failures.isEmpty()) System.out.println("ZhTagEval: 有 " + failures.size() + " 条异常，详见 work/zh-tag-failures.log");
        if (rate < minHitRate) throw new AssertionError("中文词库严格候选命中率未达标：" + strictHits + "/" + CASES.size());
        long blocking = failures.stream().filter(line -> line.startsWith("词条") || line.startsWith("分类词条过少")
                || line.startsWith("中文写法") || line.startsWith("载入的词条数") || line.startsWith("没有中文写法")).count();        if (blocking > 0) throw new AssertionError("中文词库存在 " + blocking + " 条结构性问题，详见 work/zh-tag-failures.log");
    }
}

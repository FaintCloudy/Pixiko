package cn.szu.bot.chat;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import cn.szu.bot.Json;
import cn.szu.bot.Log;
import cn.szu.bot.prompt.PromptUsage;

/**
 * 前置拆解层：把一句复杂的画面要求拆成若干条"简单修改"，再交给 .infix 去改写提示词。
 *
 * <p>复杂要求（例如"女性被男性地铁痴汉"）直接丢给改写模型时，模型往往只落实其中一两点，用户要的其余
 * 内容静默丢失。这一层先把它拆成人、动作、场景三类都覆盖到的短句（每句一个动作/一个部位/一个场景），
 * 并为每句预算出对应的 SD 标准词条；改写阶段把这些标准词条作为"必须使用"的候选一起喂给模型，
 * 验收时再按这些词条核对是否真的落地，缺了就补、说错就转成标准词。
 */
public final class SceneDecomposer {
    /** 一条简单修改：中文短句 + 它对应的标准词条（来自词库与中文别名索引）。 */
    public record Part(String text, List<String> tags) {}
    /** 一次拆解的结果；complete 表示是否真的拆出了至少三条简单修改。 */
    public record Scene(String original, List<Part> parts, boolean complete) {
        public List<String> texts() { return parts.stream().map(Part::text).toList(); }
        public List<String> allTags() {
            LinkedHashSet<String> tags = new LinkedHashSet<>();
            for (Part part : parts) tags.addAll(part.tags());
            return List.copyOf(tags);
        }
    }
    /** 至少拆出这么多条简单修改才算合格。 */
    public static final int MIN_PARTS = 3;
    /** 最多拆这么多条，避免一条请求无限膨胀。 */
    public static final int MAX_PARTS = 6;
    /** 每条简单修改对应的候选标准词条上限（喂给模型 + 用于验收）。只取少量高置信度的，避免把噪声当必用词。 */
    private static final int TAGS_PER_PART = 3;
    /** 人物/动作/场景三类，用来检查拆解是否覆盖到"方方面面"。 */
    private static final List<List<String>> ASPECTS = List.of(
            List.of("男", "女", "少女", "少年", "男孩", "女孩", "孩子", "小孩", "儿童", "宝宝", "老", "人", "群", "角色",
                    "她", "他", "两人", "多人", "家人", "母亲", "父亲", "大人", "青年", "行人", "群众",
                    "父亲", "护士", "医生", "学生", "高中生", "偶像", "骑士", "公主", "警察", "警官", "女仆", "猫娘", "女巫",
                    "剑士", "吸血鬼", "机器人", "老人", "小偷", "粉丝", "狗", "猫", "鸽子"),
            List.of("摸", "揉", "抓", "抱", "吻", "舔", "插", "骑", "跑", "奔", "走", "站", "坐", "躺", "跪", "趴", "跳",
                    "哭", "笑", "喘", "脸", "眼神", "表情", "叫", "喊", "抽搐", "颤抖", "看", "低头", "抬头", "回头",
                    "脱下", "穿上", "换衣", "举", "绑", "按", "压", "挥", "打", "推", "拉", "咬", "吃", "喝", "唱歌",
                    "跳舞", "睡", "睁", "闭", "擦", "捡", "挑", "买", "结账", "做饭", "洗", "撑伞", "等车", "训练", "喂",
                    "告白", "冲锋", "行军", "拍照", "打针", "压腿", "挥剑", "猥亵", "性骚扰", "痴汉", "袭", "撞",
                    "熬", "煮", "冒", "烟", "沸腾", "伸", "递", "甩", "拍", "划", "指", "吮", "吸", "吐", "浸", "湿",
                    "写", "读", "弹", "演", "唱", "抱紧", "亲", "抚摸", "触摸", "注视", "盯", "眯", "皱眉", "张嘴", "呻吟",
                    "性交", "交合", "做爱", "插入", "抽插", "交配", "后入", "骑乘", "高潮", "射精", "自慰", "口交", "乳交",
                    "手交", "足交", "肛交", "舔阴", "指交", "撑开", "掰开", "张开腿", "抬起腿", "分镜", "视角", "特写", "聚焦"),
            List.of("地铁", "电车", "列车", "车厢", "公交", "车站", "教室", "校园", "学校", "街道", "街", "巷", "马路",
                    "房间", "室内", "床上", "浴室", "更衣室", "浴场", "泳池", "海边", "沙滩", "海", "森林", "草地", "花田",
                    "山", "雪山", "雪地", "雪", "雨", "夜", "深夜", "白天", "午后", "黄昏", "阳光", "月光", "室外", "天台",
                    "屋顶", "医院", "病房", "酒吧", "舞台", "车", "电梯", "废墟", "荒", "神社", "城堡", "花园", "图书馆",
                    "咖啡厅", "便利店", "甜品店", "夜市", "办公室", "体育馆", "舞蹈室", "厨房", "院子", "广场", "长椅",
                    "樱花树", "树下", "窗", "镜子", "地板", "收银台", "摊", "村庄", "火", "烟火", "烟花", "工地", "车厢",
                    "桌子", "阶梯", "台阶", "门口"));

    private SceneDecomposer() { }

    /** 这句要求是否需要前置拆解：包含多个分句、多个场景/动作关键词，或足够长。 */
    public static boolean isComplex(String instruction) {
        if (instruction == null || instruction.isBlank()) return false;
        String text = instruction.strip();
        int aspects = 0;
        for (List<String> aspect : ASPECTS) if (aspect.stream().anyMatch(text::contains)) aspects++;
        int clauses = text.split("[，,。；;、\\n]").length;
        return aspects >= 2 || clauses >= 2 || text.length() >= 18;
    }

    /** 拆解是否覆盖了人物、动作、场景三类（复杂场景应该三者都有）。只统计要"画出来"的条目。 */
    public static List<String> missingAspects(List<String> parts) {
        String joined = String.join(" ", parts.stream().filter(part -> !isCleanup(part)).toList());
        List<String> missing = new ArrayList<>();
        String[] labels = {"人物", "动作", "场景"};
        for (int index = 0; index < ASPECTS.size(); index++)
            if (ASPECTS.get(index).stream().noneMatch(joined::contains)) missing.add(labels[index]);
        return List.copyOf(missing);
    }
    /**
     * "删除所有视角词""清空环境词"这类是**对提示词的清理要求**，不是要画出来的画面要素：
     * 它们没有对应词条，也不该参与人物/动作/场景覆盖与"没落实"的核对（否则回执里会多出假缺口）。
     */
    public static boolean isCleanup(String text) {
        if (text == null) return false;
        String value = text.strip();
        if (value.isEmpty()) return false;
        return value.matches("^(删除|删掉|删去|清空|清除|清掉|去掉|去除|移除|不要|取消|省去|抹掉|抹去)(所有|全部|掉|去|除)?.*")
                || value.matches(".*(删除|清空|清除|去掉|移除)(所有|全部)?(视角|环境|背景|光线|光照|风格|画风|构图|镜头).*");
    }

    /**
     * 调一次模型做拆解，并给每条简单修改预算标准词条。拆不出三条时按规则重试；仍不足则标记
     * complete=false，让上层如实报告（而不是假装拆好了）。
     */
    public static Scene decompose(String instruction, Set<String> allowed, PromptUsage usage, DeepSeekPrompts client) {
        List<String> parts = List.of();
        String focus = "";
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                parts = request(instruction, client, focus);
            } catch (Exception error) {
                Log.warn("前置拆解失败（第 " + attempt + " 次）：" + cn.szu.bot.Bot.error(error));
                parts = List.of();
            }
            if (parts.size() >= MIN_PARTS && missingAspects(parts).isEmpty()) break;
            if (parts.size() >= MIN_PARTS && attempt >= 2) break;
            List<String> missing = missingAspects(parts);
            focus = "上次只拆出 " + parts.size() + " 条" + (missing.isEmpty() ? "" : "，缺少" + String.join("、", missing))
                    + "；必须至少 " + MIN_PARTS + " 条，并且人物、动作、场景都要覆盖到。";
            if (attempt < 3) Log.info("前置拆解不足，重试：" + focus);
        }
        List<Part> collected = new ArrayList<>();
        Set<String> usedTags = new LinkedHashSet<>();
        for (String part : parts) {
            List<String> tags = new ArrayList<>();
            for (String tag : tagsFor(part, allowed, usage)) {
                // 跨条也要保持自洽：要求 subway 时别再把 train 列成另一条的必用词（同族只保留先出现的）。
                boolean conflicts = false;
                for (String used : usedTags) if (cn.szu.bot.Bot.tagsConflict(used, tag)) { conflicts = true; break; }
                if (conflicts) continue;
                tags.add(tag);
                usedTags.add(tag);
            }
            collected.add(new Part(part, List.copyOf(tags)));
        }
        boolean complete = collected.size() >= MIN_PARTS && missingAspects(parts).isEmpty();
        Log.info("前置拆解（" + (complete ? "合格" : "不完整") + "）：" + instruction + " → "
                + String.join(" | ", collected.stream().map(part -> part.text() + "[" + String.join(",", part.tags()) + "]").toList()));
        return new Scene(instruction, List.copyOf(collected), complete);
    }
    /** 每条简单修改对应的候选标准词条：中文同义词表 + 高置信度别名索引 + 逐词收敛，全部来自已加载词库。 */
    static List<String> tagsFor(String part, Set<String> allowed, PromptUsage usage) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        // 1) 内置中文同义词表（草地→grass、性骚扰→chikan/molestation…），最可靠，优先。
        try { tags.addAll(cn.szu.bot.Bot.sceneTagCandidates(part, allowed)); }
        catch (Exception ignored) { }
        if (usage != null) {
            // 2) 分类词库的中文别名索引（高置信度匹配）。
            try { tags.addAll(usage.strictHints(part, allowed, TAGS_PER_PART)); }
            catch (Exception error) { Log.warn("别名索引查询失败：" + cn.szu.bot.Bot.error(error)); }
            // 3) 短句里直接出现的英文词（收敛成词库写法）。
            for (String word : PromptUsage.words(part)) {
                if (word.length() < 4) continue;
                try {
                    String canonical = usage.canonical(word, allowed);
                    if (canonical != null) tags.add(canonical);
                } catch (Exception ignored) { }
                if (tags.size() >= TAGS_PER_PART * 2) break;
            }
        }
        List<String> result = new ArrayList<>();
        for (String tag : tags) {
            if (result.size() >= TAGS_PER_PART) break;
            // 角色名/作者名这类带括号或冒号的词条不做"必用词"，它们是噪声不是画面要素。
            if (tag.indexOf('(') >= 0 || tag.indexOf(':') >= 0) continue;
            // 同一条简单修改里不能同时出现互斥词条（站/坐、地铁/汽车），否则必用清单自相矛盾。
            boolean conflicts = false;
            for (String kept : result) if (cn.szu.bot.Bot.tagsConflict(kept, tag)) { conflicts = true; break; }
            if (conflicts) continue;
            result.add(tag);
        }
        return List.copyOf(result);
    }

    /** 一次拆解请求：只输出简单短句，不解释、不翻译、不输出指令。 */
    private static List<String> request(String instruction, DeepSeekPrompts client, String focus) throws Exception {
        String rules = """
            你把一句复杂的画面要求拆成若干条"简单修改"，供后面的提示词改写逐条落实。
            每条只写一件事：一个人物的动作/表情，或一个身体部位，或一个场景要素；不要重复、不要解释、不要翻译成英文、不要输出任何指令。
            每条不超过 18 个字，用中文。
            必须至少 3 条，最多 6 条；人物、动作、场景三类都要覆盖到（没有人物时也要覆盖动作与场景）。
            用户提到的每个动作、行为、体位、视角、画面要求都要各自成条，一条都不要省略或合并：
            性行为也要如实拆出来（性交、插入、口交、骑乘、后入、自慰……），不要用"接触""互动""贴近"这类模糊说法代替，
            该说"性交"就写"性交"；用户说的"分镜/视角/特写/聚焦某部位"要单独成条，不要和动作揉在一起。
            "删除所有视角词/清空环境词"这类清理要求照原样写成一条，不要改写成画面要素（它们不是要画出来的东西）。
            拆出的条数不够时，就把人物、身体部位、场景要素再拆细一些，而不是把两件事并成一条。
            不要新增用户没说的情节，只把用户这句话里已经包含的内容拆开说清楚。
            只返回 JSON：{"parts":["简单修改1","简单修改2","简单修改3"]}
            """ + (focus.isBlank() ? "" : "\n上次的问题：" + focus);
        List<String> parts = client.decompose(instruction, rules);
        List<String> cleaned = new ArrayList<>();
        for (String part : parts) {
            String text = part == null ? "" : part.strip().replaceAll("^[①-⑨0-9.、)\\s]+", "").strip();
            if (text.isEmpty() || text.length() > 40) continue;
            if (cleaned.stream().anyMatch(existing -> existing.equals(text))) continue;
            cleaned.add(text);
            if (cleaned.size() >= MAX_PARTS) break;
        }
        if (cleaned.size() < MIN_PARTS) {
            // 模型把一句话塞进一条时，按标点再切一次，尽量凑够三条。
            List<String> split = new ArrayList<>();
            for (String part : cleaned) for (String piece : part.split("[，,。；;、]")) {
                String text = piece.strip();
                if (text.length() >= 2 && !split.contains(text)) split.add(text);
            }
            if (split.size() >= MIN_PARTS) return List.copyOf(split.subList(0, Math.min(MAX_PARTS, split.size())));
        }
        return List.copyOf(cleaned);
    }
}

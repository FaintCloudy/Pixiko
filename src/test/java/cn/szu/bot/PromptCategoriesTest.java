package cn.szu.bot;

import com.google.gson.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.prompt.PromptUsage;
import cn.szu.bot.prompt.TermCategories;
import cn.szu.bot.sd.UserPromptStore;
import java.util.*;

/**
 * 词条分类与"按类别"处理提示词：
 * 1) 分类器本身（词库优先、英文词表兜底、LoRA 标签不动）；
 * 2) 程序侧识别"仅保留人物和服饰，其他提示词全部清空"这类整句要求（不依赖模型）；
 * 3) 指令 /prompt classify|keep|drop 真的改对了词条；
 * 4) infix 的互斥原则：用户这次改的槽位，旧值必须让位。
 */
public final class PromptCategoriesTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        categories();
        surgeryParsing();
        commands();
        exclusion();
        System.out.println("PromptCategoriesTest: " + checks + " checks passed：词条分类、按类别筛选、聊天识别、互斥替换");
    }

    private static void categories() {
        PromptUsage none = null;   // 没有中文词库时走英文词表兜底（生产环境两者都有）
        check(TermCategories.categoryOf(none, "maid headdress").equals("服装"), "maid headdress 是服装");
        check(TermCategories.categoryOf(none, "orange dress").equals("服装"), "orange dress 是服装");
        check(TermCategories.categoryOf(none, "white wrist cuffs").equals("服装"), "wrist cuffs 是服装");
        check(TermCategories.categoryOf(none, "blonde hair").equals("人物"), "blonde hair 是人物特征");
        check(TermCategories.categoryOf(none, "red eyes").equals("人物"), "red eyes 是人物特征");
        check(TermCategories.categoryOf(none, "very long hair").equals("人物"), "very long hair 是人物特征");
        check(TermCategories.categoryOf(none, "depth of field").equals("镜头"), "depth of field 是镜头");
        check(TermCategories.categoryOf(none, "from above").equals("镜头"), "from above 是镜头");
        check(TermCategories.categoryOf(none, "night").equals("环境"), "night 是环境");
        check(TermCategories.categoryOf(none, "sunset").equals("环境"), "sunset 是环境");
        check(TermCategories.categoryOf(none, "classroom").equals("场景"), "classroom 是场景");
        check(TermCategories.categoryOf(none, "simple background").equals("场景"), "simple background 是场景");
        check(TermCategories.categoryOf(none, "sitting").equals("姿势"), "sitting 是姿势");
        check(TermCategories.categoryOf(none, "holding sword").equals("动作"), "holding 是动作");
        check(TermCategories.categoryOf(none, "light smile").equals("表情"), "light smile 是表情");
        check(TermCategories.categoryOf(none, "masterpiece").equals("画面"), "masterpiece 是画面");
        check(TermCategories.categoryOf(none, "watercolor").equals("画面"), "watercolor 是画面");
        check(TermCategories.categoryOf(none, "<lora:Shinomori_Yomogi_1a_nai:1>").equals(TermCategories.LORA), "LoRA 标签单独一类");
        check(TermCategories.categoryOf(none, "Shinomori Yomogi").equals("角色"), "人名写法算角色名，不会被当成杂物删掉");
        check(TermCategories.categoryOf(none, "asdfgh").equals(TermCategories.OTHER), "认不出的自定义词归入其他");

        List<String> prompt = List.of("Shinomori Yomogi", "blonde hair", "maid headdress", "orange dress",
                "depth of field", "night", "simple background", "masterpiece", "<lora:NFFA10.3:0.5>");
        String described = TermCategories.describe(none, prompt);
        check(described.contains("服装(2)"), "分类清单按类分组并给出条数：" + described);
        check(described.contains("人物(1)") && described.contains("角色(1)") && described.contains("镜头(1)")
                && described.contains("环境(1)") && described.contains("场景(1)") && described.contains("画面(1)")
                && described.contains("LoRA(1)"), "分类清单覆盖每一类：" + described);
        check(described.contains("<lora:NFFA10.3:0.5>"), "LoRA 标签出现在清单里");
        check(described.contains("角色(1)：Shinomori Yomogi"), "角色名单独成类：" + described);
    }

    private static void surgeryParsing() {
        Bot.CategorySurgery surgery = Bot.parseCategorySurgery("仅保留人物和服饰，将所有的其他提示词清空");
        check(surgery != null, "整句「仅保留人物和服饰，将所有的其他提示词清空」能被识别");
        check(surgery.keepOnly() && surgery.keep().containsAll(List.of("人物", "角色", "服装")),
                "识别为只保留 人物/角色/服装：" + surgery.keep());
        check(Bot.parseCategorySurgery("只保留人物和服饰").keepOnly(), "「只保留人物和服饰」识别为只保留");
        Bot.CategorySurgery removal = Bot.parseCategorySurgery("把环境词和物品词删掉");
        check(removal != null && removal.remove().containsAll(List.of("场景", "环境", "物品")),
                "「删掉环境词和物品词」识别为删除：" + removal);
        check(Bot.parseCategorySurgery("只留人物，服饰也都删了，再加一个女孩") == null, "混着画面要求时交给模型，不擅自删词条");
        check(Bot.parseCategorySurgery("今天天气不错") == null, "普通聊天不触发分类筛选");
        check(Bot.categoriesFromWords("人物 服饰").containsAll(List.of("人物", "角色", "服装")), "分类词解析支持空格分隔");
        check(Bot.categoriesFromWords("视角").contains("镜头"), "视角 → 镜头");
        check(Bot.categoriesFromWords("胡言乱语").isEmpty(), "认不出的分类返回空");
    }

    private static void commands() throws Exception {
        try (GenerationPresetTest.Fixture f = new GenerationPresetTest.Fixture()) {
            String original = "Shinomori Yomogi, blonde hair, red eyes, maid headdress, orange dress, white apron, "
                    + "white thighhighs, depth of field, night, simple background, masterpiece, <lora:Shinomori_Yomogi_1a_nai:1>";
            f.command("private", ".prompt set " + original);
            String classified = f.command("private", ".prompt classify");
            check(classified.contains("服装") && classified.contains("人物") && classified.contains("镜头")
                    && classified.contains("环境") && classified.contains("场景") && classified.contains("画面"),
                    "classify 列出各分类：" + classified);

            String kept = f.command("private", ".prompt keep 人物 服饰");
            check(kept.contains("保留") && kept.contains("删除"), "keep 回执说明保留与删除：" + kept);
            String positive = new UserPromptStore(f.root).prompts("2").positive();
            check(positive.contains("Shinomori Yomogi") && positive.contains("blonde hair") && positive.contains("red eyes")
                    && positive.contains("maid headdress") && positive.contains("orange dress") && positive.contains("white thighhighs"),
                    "人物与服饰词条全部保留：" + positive);
            check(!positive.contains("depth of field") && !positive.contains("night") && !positive.contains("simple background")
                    && !positive.contains("masterpiece"), "镜头/环境/场景/画面的词条被清空：" + positive);
            check(positive.contains("<lora:Shinomori_Yomogi_1a_nai:1>"), "LoRA 标签永远保留：" + positive);
            check(new UserPromptStore(f.root).prompts("2").negative().equals("live negative"), "反向 prompt 未被改动");
            check(f.command("private", ".prompt undo").contains("已回退"), "分类筛选可以一步回退");

            check(f.command("private", ".prompt drop 环境 物品").contains("删除"), "drop 回执说明删除");
            check(!new UserPromptStore(f.root).prompts("2").positive().contains("night"), "drop 删掉了环境词");
            check(new UserPromptStore(f.root).prompts("2").positive().contains("masterpiece"), "drop 只删指定类别，画面词保留");
            check(f.command("private", ".prompt drop 不存在的类别").contains("认不出"), "无法识别的类别给出提示");

            // 聊天里的整句要求：程序直接给出筛选指令，不需要模型逐条猜。
            f.command("private", ".prompt set 1girl, night, depth of field, masterpiece");
            ChatActions.Plan plan = f.bot.chatPlan("personality", new JsonArray(), "仅保留人物和服饰，把其他提示词全部清空",
                    new JsonObject(), new JsonObject());
            check(plan.commands().size() == 1 && plan.commands().get(0).startsWith("/prompt keep"),
                    "聊天请求直接变成按类别筛选指令：" + plan.commands());
            f.bot.executeChatCommands(event("private", "2"), plan.commands(), new JsonObject());
            String afterChat = new UserPromptStore(f.root).prompts("2").positive();
            check(afterChat.contains("1girl") && !afterChat.contains("night") && !afterChat.contains("depth of field"),
                    "聊天触发的结果与指令一致：" + afterChat);
            f.replies.clear();

            // 模型侧上下文里带着同一份分类清单：模型能按类别名直接输出指令。
            JsonObject terms = f.bot.selectionContext(event("private", "2")).getAsJsonObject("prompt_terms");
            check(terms != null && terms.getAsJsonObject("categories").has("人物"),
                    "selections.prompt_terms 带着分类清单：" + terms);
            check(terms.get("hint").getAsString().contains("/prompt keep"), "分类清单里说明怎么用 keep/drop");
        }
    }

    private static void exclusion() {
        List<String> replaced = new ArrayList<>();
        String fixed = Bot.enforceRequestedFamilies("1girl, standing, sitting, long hair", "让她坐下", replaced);
        check(!fixed.contains("standing") && fixed.contains("sitting"), "要求坐下时删掉冲突的 standing：" + fixed);
        check(replaced.contains("standing（与要求的 sitting 互斥）"), "回执说明互斥替换：" + replaced);
        replaced.clear();
        String vehicle = Bot.enforceRequestedFamilies("1girl, car, subway, neon lights", "改成地铁", replaced);
        check(!vehicle.contains("car") && vehicle.contains("subway"), "要求地铁时删掉 car：" + vehicle);
        replaced.clear();
        String timeOfDay = Bot.enforceRequestedFamilies("1girl, day, night, masterpiece", "改成夜晚", replaced);
        check(!timeOfDay.contains("day") && timeOfDay.contains("night"), "改成夜晚时删掉 day：" + timeOfDay);
        replaced.clear();
        String untouched = Bot.enforceRequestedFamilies("1girl, night, masterpiece", "给她加一条围巾", replaced);
        check(untouched.contains("night") && replaced.isEmpty(), "没提到时间就不动 night：" + untouched);
        // 用户要的新值还没落实时不删旧值（免得两头落空）。
        replaced.clear();
        String guarded = Bot.enforceRequestedFamilies("1girl, standing", "让她坐下", replaced);
        check(guarded.contains("standing") && replaced.isEmpty(), "新值不在提示词里时先不动旧值：" + guarded);
    }

    private static JsonObject event(String type, String user) {
        JsonObject event = new JsonObject();
        event.addProperty("post_type", "message");
        event.addProperty("message_type", type);
        event.addProperty("self_id", 1);
        event.addProperty("user_id", user);
        event.addProperty("group_id", 3);
        event.addProperty("message_id", 1);
        event.addProperty("message", "");
        return event;
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}

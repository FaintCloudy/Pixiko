package cn.szu.bot;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import cn.szu.bot.prompt.PromptUsage;

public final class PromptUsageTest {
    public static void main(String[] args) throws Exception {
        try (var f = new GenerationPresetTest.Fixture()) {
            JsonObject dictionary = new JsonObject(); dictionary.addProperty("version", 1); dictionary.addProperty("source", "测试词库");
            JsonObject leaf = category("长袖", List.of(), "white_shirt", "白衬衫");
            JsonObject clothes = category("服饰", List.of(category("上衣", List.of(leaf), "shirt", "衬衫"),
                    category("裙子", List.of(), "skirt", "裙子")), null, null);
            JsonArray roots = new JsonArray(); roots.add(clothes); roots.add(category("特殊", List.of(), "embedding", ""));
            dictionary.add("categories", roots); Json.atomicWrite(f.root.resolve("data/prompt-usage.json"), dictionary);
            String state = f.state.toString();
            String top = f.command("group", ".usage");
            check(top.contains(".usage 服饰") && top.contains(".usage 特殊") && !top.contains("white_shirt"), "root only lists categories");
            String middle = f.command("private", ".usage 服饰");
            check(middle.contains(".usage 服饰/上衣") && middle.contains(".usage 服饰/裙子"), "all children with full commands");
            String mixed = f.command("group", ".usage 服饰/上衣");
            check(mixed.contains("shirt — 衬衫") && mixed.contains(".usage 服饰/上衣/长袖"), "node can contain both tags and deeper categories");
            String deepest = f.command("private", ".usage 服饰/上衣/长袖");
            check(deepest.contains("white_shirt — 白衬衫") && deepest.contains("返回：.usage 服饰/上衣"), "third-level leaf and parent navigation");
            check(f.command("group", ".usage 服饰 上衣 长袖").equals(deepest), "spaces and slashes navigate identically");
            check(f.command("private", ".usage 特殊").contains("词库未提供"), "missing translation is explicit");
            String unknown = f.command("private", ".usage 服饰/不存在");
            check(unknown.contains("未找到") && unknown.contains(".usage 服饰/裙子"), "unknown child returns useful parent menu");
            check(f.command("private", ".usage ../outside").contains("未找到"), "input cannot become a filesystem path");
            check(state.equals(f.state.toString()), "browsing does not change live SD state");
            check(f.command("group", ".help").contains(".usage <分类路径>"), "help documents direct command");
            check(f.command("group", ".usage 词库").contains("不可用"), "missing built-in library is reported, not crashing");
            check(f.command("group", ".usage 搜索 地铁").contains("不可用"), "search without built-in library is reported");
            f.server.stop(0);
            check(f.command("private", ".usage 服饰/裙子").contains("skirt — 裙子"), "offline navigation");
        }
        Path production = Path.of(System.getProperty("bot.test.work", "work")).toAbsolutePath().getParent();
        if (Files.exists(production.resolve("data/prompt-usage.json"))) {
            PromptUsage actual = new PromptUsage(production);
            check(actual.browse("").contains("子分类（11）"), "real imported categories load");
            check(actual.browse("服饰/上衣").contains(" — "), "real leaf maps prompts to meanings");
            check(actual.browse("").contains("内置中文词库"), "root advertises the built-in Chinese library");
            if (Files.exists(production.resolve("data/prompt-zh-tags.json"))) {
                check(actual.librarySize() >= 20000, "built-in library has at least 20000 entries: " + actual.librarySize());
                String overview = actual.browse("词库");
                check(overview.contains("内置中文词库") && overview.contains(".usage 词库"), "library overview lists categories");
                String scene = actual.browse("词库 场景");
                check(scene.contains(" — ") && scene.contains("返回：.usage 词库"), "library category page lists entries");
                check(actual.browse("搜索 地铁").contains("subway — "), "search finds 地铁 → subway");
                check(actual.browse("搜索 chikan").contains("chikan — "), "search finds an English tag");
                check(actual.browse("搜索 不存在的词条xyz").contains("没有匹配"), "empty search is explicit");
                check(actual.browse("词库 不存在").contains("未知分类"), "unknown library category is explicit");
                // 词条用法：词意 + 使用需求 + 注意事项
                String detail = actual.browse("词条 chikan");
                check(detail.contains("chikan") && detail.contains("使用需求") && detail.contains("成人向"),
                        "词条用法给出词意/使用需求/注意：" + detail);
                String camera = actual.browse("词条 multiple_views");
                check(camera.contains("使用需求") && (camera.contains("分镜") || camera.contains("取景")),
                        "分镜类词条说明什么时候用：" + camera);
                check(actual.browse("词条 不存在的词条zzz").contains("没有匹配"), "未知词条如实说明");
                check(actual.browse("词库").contains("用法"), "词库总览提到用法查看方式");
                Set<String> allowed = Bot.vocabulary(production, new cn.szu.bot.sd.SdClient.Prompts("", "", "test"));
                check(actual.strictHints("痴汉", allowed, 3).contains("chikan"), "痴汉 maps to chikan");
                check(actual.strictHints("性骚扰", allowed, 3).stream().anyMatch(tag -> tag.contains("molestation")),
                        "性骚扰 maps to molestation");
                check(actual.strictHints("地铁车厢", allowed, 3).contains("train_interior"), "地铁车厢 maps to train_interior");
                check(actual.strictHints("脸红", allowed, 3).contains("blush"), "脸红 maps to blush");
                // 长句里出现的实词照样要命中，但"女性/男性"这类泛指词不能把整句刷成命中。
                List<String> longSentence = actual.strictHints("女性被男性地铁痴汉", allowed, 6);
                check(longSentence.contains("chikan") || longSentence.contains("subway"), "long sentences ground their real nouns: " + longSentence);
                check(longSentence.stream().noneMatch(tag -> tag.contains("futa")), "泛指的性别词不该带出无关词条：" + longSentence);
                List<String> panel = actual.strictHints("分镜展示交合部位", allowed, 3);
                check(panel.contains("comic") || panel.contains("multiple_views"), "分镜 lands on a panel tag: " + panel);
                check(panel.contains("hip_focus") || panel.contains("crotch_focus"), "交合部位 lands on a focus tag: " + panel);
                check(actual.hints("少女在雨中撑着伞等公交车", allowed, 20).stream().anyMatch(tag -> tag.contains("umbrella")),
                        "recall hints cover umbrella in a long Chinese sentence");
                // 精确命中必须是查表：提示词面板给几十个词条配中文释义，全表扫描（search + 排序）
                // 一次要好几毫秒，累积半秒——出图面板点「开始生成」的前摇就是它。
                PromptUsage.Entry spaced = actual.entry("twin braids");
                PromptUsage.Entry underscored = actual.entry("twin_braids");
                check(spaced != null && underscored != null && spaced.tag().equals(underscored.tag()),
                        "下划线与空格两种写法精确命中同一个词条：" + spaced);
                check(actual.entry("肯定不存在的词条zzz") == null, "词库外的词条返回 null");
                long lookupStarted = System.nanoTime();
                int hits = 0;
                for (int i = 0; i < 5000; i++) if (actual.entry("twin_braids") != null) hits++;
                long lookupMillis = (System.nanoTime() - lookupStarted) / 1_000_000;
                check(hits == 5000 && lookupMillis < 1000, "5000 次精确命中只用 " + lookupMillis + "ms（走索引表，不是全表扫描）");
            }
        }
        System.out.println("PromptUsageTest PASS: category tree, all children/tags, deep paths, missing translation, "
                + "offline read-only commands, real dictionary, built-in Chinese library (size/search/strict candidates).");
    }
    private static JsonObject category(String name, List<JsonObject> children, String prompt, String meaning) {
        JsonObject result = new JsonObject(); result.addProperty("name", name);
        JsonArray nodes = new JsonArray(); children.forEach(nodes::add); result.add("children", nodes);
        JsonArray tags = new JsonArray();
        if (prompt != null) { JsonObject tag = new JsonObject(); tag.addProperty("prompt", prompt); tag.addProperty("meaning", meaning); tags.add(tag); }
        result.add("tags", tags); return result;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

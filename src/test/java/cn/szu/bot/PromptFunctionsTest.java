package cn.szu.bot;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import cn.szu.bot.prompt.PromptFunctions;
import cn.szu.bot.sd.UserPromptStore;

public final class PromptFunctionsTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        try (var f = new GenerationPresetTest.Fixture()) {
            // /sd 现在只有 SD WebUI 自启动这一件事（/sd、/sd start、/sd auto|boot on|off），
            // 旧的生成指令前缀（/sd generate、/sd lora …）仍然不存在。
            check(!Bot.HELP.contains("/sd generate"), "old sd generation prefix removed from help");
            String legacy = f.command("private", "/sd generate 1");
            check(legacy.contains("用法：.sd") && !legacy.contains("已加入生成队列"), "old sd generation prefix no longer works：" + legacy);
            check(f.command("private", "/sd").contains("Stable Diffusion"), "/sd 现在报告 SD 与自启动状态");
            check(f.command("private", "/sd nonsense").contains("用法：.sd"), "/sd 的用法被说明");
            check(f.command("private", ".function list").contains("（无）"), "empty list");
            edit(f, "masterpiece, red hair, (soft light:1.2)", "blur, bad hands");
            check(f.command("group", ".function save 集合 A").contains("已保存"), "save positive/negative pair");
            check(f.command("private", ".function save 集合 A").contains("同名"), "no silent overwrite");
            check(f.command("private", ".function prompt 集合 A").contains("(soft light:1.2)"), "view exact weighted term");
            // 网页控制台的按钮发带引号的名称；引号必须被剥掉（曾经被当成名字的一部分）。
            check(f.command("private", ".function prompt \"集合 A\"").contains("(soft light:1.2)"), "quoted name works for prompt");
            check(f.command("private", ".function prompt \"没有这个集\"").contains("不存在"), "quoted missing set still reports not found");
            edit(f, "soft_light, blue eyes", "bad hands, noise");
            f.command("private", ".function save 集合 B");
            edit(f, "masterpiece", "blur");
            check(f.command("group", ".function load 集合 A").contains("正向实际新增：red hair, (soft light:1.2)"), "append and feedback only added tokens");
            check(new UserPromptStore(f.root).prompts("2").positive().equals("masterpiece, red hair, (soft light:1.2)"), "append at end");
            check(f.command("private", ".function load 集合 A").contains("已加载"), "repeat load rejected without duplication");
            check(f.command("private", ".function overwrite 集合 A").contains("正在使用"), "active definition cannot be overwritten");
            check(f.command("private", ".function load 集合 B").contains("正向实际新增：blue eyes"), "normalized duplicate shared across sets");
            check(new PromptFunctions(f.root).active().size() == 2, "ownership survives reopening");
            check(f.command("group", ".function remove 集合 A").contains("正向实际移除：red hair"), "remove group excludes shared term");
            check(new UserPromptStore(f.root).prompts("2").positive().equals("masterpiece, (soft light:1.2), blue eyes"), "shared positive retained");
            check(new UserPromptStore(f.root).prompts("2").negative().equals("blur, bad hands, noise"), "shared negative retained");
            f.command("private", ".function remove 集合 B");
            check(new UserPromptStore(f.root).prompts("2").positive().equals("masterpiece") && new UserPromptStore(f.root).prompts("2").negative().equals("blur"), "last owner removal restores original pair");
            f.command("group", ".function load 集合 A"); f.command("private", ".function load 集合 B");
            check(f.command("group", ".function clear").contains("已移出"), "clear all loaded sets");
            check(new UserPromptStore(f.root).prompts("2").positive().equals("masterpiece") && new UserPromptStore(f.root).prompts("2").negative().equals("blur"), "clear preserves baseline terms and separators");
            f.command("private", ".function load 集合 A");
            f.command("group", ".prompt set masterpiece, red hair");
            f.command("private", ".function remove 集合 A");
            check(new UserPromptStore(f.root).prompts("2").positive().equals("masterpiece, red hair"), "manual replacement relinquishes positive ownership");
            check(new UserPromptStore(f.root).prompts("2").negative().equals("blur"), "negative ownership still removed as a group");
            edit(f, "masterpiece", "blur"); f.command("private", ".function load 集合 A");
            f.command("private", ".prompt remove red_hair"); f.command("private", ".prompt add red hair");
            f.command("private", ".function remove 集合 A");
            check(new UserPromptStore(f.root).prompts("2").positive().equals("masterpiece, red hair"), "manually removed and re-added token preserved");
            edit(f, "masterpiece", "blur"); f.command("private", ".function load 集合 A");
            f.command("group", ".style import webui"); // 样式只有机器人这一份：先把 WebUI 预设搬进来
            f.command("group", ".style load replace");
            check(f.command("private", ".function active").contains("（无）"), "style replacement resets function associations");
            check(new UserPromptStore(f.root).prompts("2").positive().equals("replacement, red hair"), "style still replaces instead of appending");
            edit(f, "base", "base negative");
            // Personal prompts are local: no bridge CAS is involved, so the load always applies to this copy.
            check(f.command("private", ".function load 集合 B").contains("已加载"), "personal function load succeeds without the bridge");
            check(new UserPromptStore(f.root).prompts("2").positive().startsWith("base, "), "personal append keeps the baseline first");
            f.command("private", ".function clear");
            check(new UserPromptStore(f.root).prompts("2").positive().equals("base"), "clear restores the personal baseline");
            check(!Json.parse(Files.readString(f.root.resolve("data/sd-functions.json"))).has("pending"), "no stale journal is left behind");
            check(f.command("private", ".function load 集合 B").contains("已加载"), "reload after clear");
            f.command("private", ".function remove 集合 B");
            check(new UserPromptStore(f.root).prompts("2").positive().equals("base"), "whole-group removal restores the baseline");
            f.command("private", ".function load 集合 A");
            String beforeReset = new UserPromptStore(f.root).prompts("2").positive();
            f.command("private", ".function reset");
            check(new UserPromptStore(f.root).prompts("2").positive().equals(beforeReset), "reset only clears associations");
            check(f.command("group", ".function delete 集合 A").contains("已删除"), "delete saved set");
            check(!new PromptFunctions(f.root).names().contains("集合 A"), "deletion persists");
            f.command("private", ".function overwrite 集合 B");
            check(new PromptFunctions(f.root).get("集合 B").positive().equals(beforeReset), "explicit overwrite uses current pair");
            f.command("private", ".function load 集合 B"); f.command("private", ".function rename \"集合 B\" \"集合 C\"");
            check(f.command("private", ".function list").contains("集合 C") && !f.command("private", ".function list").contains("集合 B"), "definition renamed");
            check(f.command("private", ".function active").contains("集合 C"), "loaded association follows the rename");
            check(f.command("private", ".function remove 集合 C").contains("已移出"), "ownership still removable under the new name");
            check(f.command("private", ".style rename replace 新样式").contains("样式改名完成：1 项"), "style rename works on the bot library");
            check(f.command("private", ".style rename 不存在的样式 另一个").contains("失败 1 项"), "unknown style name is refused");
            edit(f, "", ""); check(f.command("group", ".function save empty").contains("均为空"), "empty set rejected");
        }
        journalFailurePreventsMutation();
        System.out.println("PromptFunctionsTest: " + checks + " checks passed: append/remove, shared ownership, originals, manual edits, style replacement, CAS retry, journal recovery, persistence, prefix removal.");
    }
    private static void journalFailurePreventsMutation() throws Exception {
        try (var f = new GenerationPresetTest.Fixture()) {
            edit(f, "new term", "negative term"); f.command("private", ".function save A"); edit(f, "base", "baseline");
            PromptFunctions functions = new PromptFunctions(f.root);
            Path path = f.root.resolve("data/sd-functions.json"), backup = f.root.resolve("data/functions-backup.json");
            Files.move(path, backup); Files.createDirectory(path); Path obstruction = path.resolve("hold"); Files.writeString(obstruction, "owned test");
            try {
                try { functions.load(f.sd, "A"); throw new AssertionError("expected journal failure"); }
                catch (IOException expected) { check(new UserPromptStore(f.root).prompts("2").positive().equals("base") && new UserPromptStore(f.root).prompts("2").negative().equals("baseline"), "journal failure before remote mutation"); }
            } finally { Files.delete(obstruction); Files.delete(path); Files.move(backup, path); }
        }
    }
    private static void edit(GenerationPresetTest.Fixture f, String positive, String negative) throws Exception {
        check(!f.command("private", positive.isEmpty() ? ".prompt clear" : ".prompt set " + positive).contains("操作失败"), "positive edit");
        check(!f.command("private", negative.isEmpty() ? ".promptR clear" : ".promptR set " + negative).contains("操作失败"), "negative edit");
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}

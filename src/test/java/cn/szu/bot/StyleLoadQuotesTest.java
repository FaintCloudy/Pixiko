package cn.szu.bot;

import com.google.gson.*;
import cn.szu.bot.sd.UserPromptStore;

/**
 * 修复 1 的离线回归：/style load、/style prompt 缺 stripQuotes（对齐 TS 版"答应了不做"那条）。
 *
 * <p>复现的是真实链路：/char apply 生成 .style load "样式名"（带引号，为了让含空格的名称整段传入），
 * 以前引号被当成名字的一部分 → 报「没有这个样式："样式名"」→「应用基底 → .infix → .gen」第 1 步就死。
 */
public final class StyleLoadQuotesTest {
    public static void main(String[] args) throws Exception {
        try (var f = new GenerationPresetTest.Fixture()) {
            // fixture 的 WebUI 预设样式 "replace"（正向 replacement, red hair）搬进机器人样式库。
            assert f.command("private", ".style import webui").contains("导入 1 个") : "样式导入失败";
            assert f.command("private", ".style list").contains("#1 replace");

            // ① 带引号的名称：/char apply 生成的那一种写法。
            String quoted = f.command("private", ".style load \"replace\"");
            assert quoted.contains("已用样式「replace」替换") : "带引号的名称要能命中：" + quoted;
            assert new UserPromptStore(f.root).prompts("2").positive().contains("replacement")
                    : "样式文本要真的写进个人提示词";

            // ② 不带引号的名称、③ #编号、④ 带引号的 #编号：都要命中同一条样式。
            assert f.command("private", ".style load replace").contains("已用样式「replace」替换") : "裸名称仍要能命中";
            assert f.command("private", ".style load #1").contains("已用样式「replace」替换") : "编号要能解析：" ;
            assert f.command("private", ".style load \"#1\"").contains("已用样式「replace」替换") : "带引号的编号也要能解析";
            assert f.command("private", ".style load \"replace\" nolora").contains("已用样式「replace」替换")
                    : "带引号 + nolora 修饰也要能命中";

            // ⑤ /style prompt 同一处：带引号 / 编号都要能查看原文。
            assert f.command("private", ".style prompt \"replace\"").contains("replacement") : "带引号的 prompt 查询";
            assert f.command("private", ".style prompt #1").contains("replacement") : "编号的 prompt 查询";

            // ⑥ 找不到的样式仍如实报错，而且报的是去掉引号后的名字。
            String missing = f.command("private", ".style load \"没有这个样式\"");
            assert missing.contains("没有这个样式") && !missing.contains("\"没有这个样式\"")
                    : "报错里的名称不该带引号：" + missing;

            // ⑦ 网页通道（webStylesEdit 的 load 分支）同样处理引号与编号。
            JsonObject webQuoted = f.bot.webStylesEdit("web", "load", "\"replace\"", null, false, false);
            assert webQuoted.get("message").getAsString().contains("已用样式「replace」替换")
                    : "网页载入带引号的名称：" + webQuoted.get("message").getAsString();
            JsonObject webNumber = f.bot.webStylesEdit("web", "load", "#1", null, false, false);
            assert webNumber.get("message").getAsString().contains("已用样式「replace」替换")
                    : "网页载入 #编号：" + webNumber.get("message").getAsString();
            try {
                f.bot.webStylesEdit("web", "load", "\"没有这个样式\"", null, false, false);
                throw new AssertionError("不存在的样式不该被接受");
            } catch (IllegalArgumentException expected) {
                assert expected.getMessage().equals("没有这个样式：没有这个样式。") : expected.getMessage();
            }
        }
        System.out.println("StyleLoadQuotesTest PASS: /style load、/style prompt 的名称去引号与 #编号解析（QQ + 网页）");
    }
}

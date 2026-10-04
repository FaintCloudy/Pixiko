package cn.szu.bot;

import com.google.gson.*;
import java.util.*;
import cn.szu.bot.chat.ChatActions;
import cn.szu.bot.chat.DeepSeekPrompts;

/**
 * 修复 3 的离线回归：unrequestedBasis 对 #N 目标不认"用户按名字点的那一项"。
 *
 * <p>这一条是**比 Java 原来更宽**的新行为（TS 版就是这么修的）：用户说"加载空门苍lora"、
 * 模型给 .style load #3，原话里当然没有 #3，但第 3 项的名字里含"空门苍" → 放行。
 * 列表里没有这一项、或名字都没命中，照旧拦下。
 */
public final class BasisGuardTest {
    /** 真实列表：第 3 项是"夏日口袋(summer pockets) | 空门苍(Sorakado Ao) | anima 1"。 */
    private static final JsonObject NUMBERED = Json.parse(
            "{\"style\":[\"replace\",\"second\",\"夏日口袋(summer pockets) | 空门苍(Sorakado Ao) | anima 1\"]}");
    private static final JsonObject TWO = Json.parse("{\"style\":[\"replace\",\"second\"]}");

    static ChatActions.Plan load(String command) {
        return new ChatActions.Plan("好", List.of(command), "", 90);
    }

    public static void main(String[] args) {
        // ① 名字出现在原话里（分段后的"空门苍"） → 放行。
        assert DeepSeekPrompts.unrequestedBasis(load(".style load #3"), NUMBERED, "加载空门苍lora") == null
                : "名字对上了就该放行：" + DeepSeekPrompts.unrequestedBasis(load(".style load #3"), NUMBERED, "加载空门苍lora");
        // ② 名字没被提到 → 照旧拦下（不能因为"用户看过列表"就放行任何编号）。
        String blind = DeepSeekPrompts.unrequestedBasis(load(".style load #3"), NUMBERED, "加个帽子");
        assert blind != null && blind.contains("#3") : "名字没提到时必须拦下：" + blind;
        // ③ 列表里根本没有第 3 项 → 不能凭编号放行。
        String absent = DeepSeekPrompts.unrequestedBasis(load(".style load #3"), TWO, "加载空门苍lora");
        assert absent != null && absent.contains("#3") : "越界编号不能放行：" + absent;
        // ④ 括号里的部分也算命中（"用 summer pockets"）。
        assert DeepSeekPrompts.unrequestedBasis(load(".style load #3"), NUMBERED, "用 summer pockets 当基底") == null
                : "括号内的名字段也该命中";
        // ⑤ 原话里明确给了编号 → 照旧放行（原有行为不变）。纯"第二个"不会走到这道守卫：
        //    ChatService 先由 resolveShownSelection 直接执行，守卫看到的已经是展开后的指令。
        assert DeepSeekPrompts.unrequestedBasis(load(".style load #2"), TWO, "选 #2") == null : "显式编号照旧放行";
        assert DeepSeekPrompts.unrequestedBasis(load(".style load #2"), TWO, "第二个") != null
                : "没有 #编号 字样的纯指代在这道守卫里照旧拦下";
        // ⑥ 指代豁免仍然优先（"用这个样式"），不会因为新分支误伤。
        assert DeepSeekPrompts.unrequestedBasis(load(".style load #1"), TWO, "用这个样式") == null : "指代豁免";
        // ⑦ 名字与编号都在、但编号指的不是那一项时不能放行（新分支只补"按名字点"的情况）。
        String wrong = DeepSeekPrompts.unrequestedBasis(load(".style load #2"), NUMBERED, "加载空门苍lora");
        assert wrong != null && wrong.contains("#2") : "名字对应的是别的编号时仍要拦：" + wrong;
        // ⑧ 名称分支（无编号）行为不变：点名了才放行。
        assert DeepSeekPrompts.unrequestedBasis(load(".style load replace"), NUMBERED, "用 replace 当基底") == null;
        String unnamed = DeepSeekPrompts.unrequestedBasis(load(".style load replace"), NUMBERED, "加个性感标签");
        assert unnamed != null && unnamed.contains("replace") : unnamed;
        // ⑨ 消息带引用时只看「我这条消息」之后的正文（引用里的名字不算本次要求）。
        String quoted = "[引用] 加载空门苍lora\n" + Bot.REQUEST_MARK + "加个帽子";
        assert DeepSeekPrompts.unrequestedBasis(load(".style load #3"), NUMBERED, quoted) != null
                : "引用里的名字不能当成本次要求";
        System.out.println("BasisGuardTest PASS: #N 目标按用户点到的名字放行，越界/未命中/引用内容照旧拦下");
    }
}

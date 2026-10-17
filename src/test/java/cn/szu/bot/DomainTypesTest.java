package cn.szu.bot;

import cn.szu.bot.domain.CommandError;
import cn.szu.bot.domain.ConversationId;
import cn.szu.bot.domain.PromptPair;
import cn.szu.bot.domain.Scope;
import cn.szu.bot.domain.UserId;

import java.util.EnumSet;

/**
 * {@code cn.szu.bot.domain} 的单元测试：不可变值对象的相等性、构造期校验与归一化、日志安全。
 *
 * <p>这些类型是纯数据（不依赖 Gson、不含可变集合、不碰文件/网络），所以这里不需要任何桩：
 * 全部断言都在进程内完成，不连接 QQ/SD/网页，也不读写生产目录。夹具用的号码都是假号。
 */
public final class DomainTypesTest {
    private static int assertions;

    public static void main(String[] args) {
        scopeIsAFixedEnum();
        conversationIdIsAnImmutableValue();
        userIdNeverLeaksItsRawValue();
        promptPairNormalisesAndHidesItsBody();
        commandErrorCarriesACodeAndAMessage();
        check(assertions >= 20, "断言条数应 ≥ 20，实际 " + assertions);
        System.out.println("DomainTypesTest: " + assertions + " assertions passed：Scope/ConversationId/UserId/"
                + "PromptPair/CommandError 的相等性、校验、归一化与日志安全（不打印任何号码或提示词原文）。");
    }

    /** {@code Scope}：四个取值，名字稳定（持久化/log 里会出现）。 */
    private static void scopeIsAFixedEnum() {
        check(Scope.values().length == 4, "Scope 应有 4 个取值，实际 " + Scope.values().length);
        check(EnumSet.allOf(Scope.class).equals(EnumSet.of(Scope.GROUP, Scope.PRIVATE, Scope.WEB, Scope.DEVICE)),
                "Scope 取值：" + EnumSet.allOf(Scope.class));
        for (Scope scope : Scope.values()) check(Scope.valueOf(scope.name()) == scope, "valueOf 往返：" + scope);
    }

    /** {@code ConversationId}：去掉首尾空白、空白归一成占位、非法输入当场报错、相等性只看 scope+id。 */
    private static void conversationIdIsAnImmutableValue() {
        ConversationId group = new ConversationId(Scope.GROUP, " 999 ");
        check(group.id().equals("999"), "首尾空白应被去掉：" + group.id());
        check(group.equals(new ConversationId(Scope.GROUP, "999")), "同 scope+id 相等");
        check(group.hashCode() == new ConversationId(Scope.GROUP, "999").hashCode(), "相等对象哈希相同");
        check(!group.equals(new ConversationId(Scope.PRIVATE, "999")), "scope 不同就不相等");
        check(group.toString().equals("GROUP:999"), "toString：" + group);

        ConversationId unknown = ConversationId.of(Scope.PRIVATE, "   ");
        check(unknown.unknown() && unknown.id().equals(ConversationId.UNKNOWN), "空白 id 归一成占位：" + unknown);

        expectThrows(NullPointerException.class, () -> new ConversationId(null, "1"), "scope 不能为 null");
        expectThrows(IllegalArgumentException.class, () -> new ConversationId(Scope.GROUP, "  "), "id 不能为空白");
        expectThrows(IllegalArgumentException.class, () -> new ConversationId(Scope.GROUP, null), "id 不能为 null");
        expectThrows(IllegalArgumentException.class, () -> new ConversationId(Scope.GROUP, "9\n9"), "id 不能含控制字符");
    }

    /** {@code UserId}：构造期校验 + 日志里只出现掩码（QQ 号不许进日志）。 */
    private static void userIdNeverLeaksItsRawValue() {
        UserId user = new UserId(" 123456789 ");
        check(user.value().equals("123456789"), "首尾空白应被去掉：" + user.value());
        check(user.equals(new UserId("123456789")), "同值相等");
        check(user.equals(UserId.of("123456789")), "of 与构造器等价");
        check(user.masked().equals("12***89"), "掩码：" + user.masked());
        check(!user.toString().contains("123456789"), "toString 不许带原文：" + user);
        check(user.toString().contains("12***89"), "toString 只给掩码：" + user);
        check(new UserId("123").masked().equals("***"), "短号码整段打码：" + new UserId("123").masked());
        check(UserId.of(null).unknown() && UserId.of(" ").unknown(), "空白 id 归一成占位");
        check(!user.unknown(), "正常 id 不是占位");

        expectThrows(NullPointerException.class, () -> new UserId(null), "value 不能为 null");
        expectThrows(IllegalArgumentException.class, () -> new UserId("   "), "value 不能为空白");
        expectThrows(IllegalArgumentException.class, () -> new UserId("1\u00002"), "value 不能含控制字符");
    }

    /** {@code PromptPair}：null 归一成空串、首尾空白去掉、不可变替换、日志只给长度。 */
    private static void promptPairNormalisesAndHidesItsBody() {
        PromptPair pair = new PromptPair(null, "  blur, lowres  ");
        check(pair.positive().isEmpty(), "null 正向归一成空串");
        check(pair.negative().equals("blur, lowres"), "反向去掉首尾空白：" + pair.negative());
        check(pair.equals(new PromptPair("", "blur, lowres")), "同值相等");
        check(!pair.isEmpty(), "反向有内容就不是空对");
        check(PromptPair.EMPTY.isEmpty(), "EMPTY 是空对");
        check(PromptPair.of("  a , b ", null).positive().equals("a , b"), "词条内部的空白与标点保持原样");
        check(pair.withPositive("cat").negative().equals("blur, lowres"), "withPositive 不动反向");
        check(pair.withNegative("bad anatomy").positive().equals(""), "withNegative 不动正向");
        check(!pair.toString().contains("blur"), "toString 不许带提示词正文：" + pair);
        check(pair.toString().contains("反向 12 字符"), "toString 报长度：" + pair);
    }

    /** {@code CommandError}：分类非空、说明归一、toString 可读。 */
    private static void commandErrorCarriesACodeAndAMessage() {
        CommandError usage = CommandError.of(CommandError.Code.USAGE, "  用法：/mode normal  ");
        check(usage.code() == CommandError.Code.USAGE, "分类：" + usage.code());
        check(usage.message().equals("用法：/mode normal"), "说明去掉首尾空白：" + usage.message());
        check(usage.equals(new CommandError(CommandError.Code.USAGE, "用法：/mode normal")), "同值相等");
        check(!usage.equals(CommandError.of(CommandError.Code.FORBIDDEN, "用法：/mode normal")), "分类不同就不相等");
        check(usage.toString().equals("USAGE: 用法：/mode normal"), "toString：" + usage);
        check(CommandError.of(CommandError.Code.EXTERNAL, null).message().isEmpty(), "null 说明归一等空串");
        check(CommandError.of(CommandError.Code.TIMEOUT, "").toString().equals("TIMEOUT"), "没有说明时只打分类");
        check(CommandError.Code.values().length == 5, "失败分类应恰好 5 种");
        check(EnumSet.allOf(CommandError.Code.class).equals(EnumSet.of(CommandError.Code.USAGE,
                CommandError.Code.FORBIDDEN, CommandError.Code.NOT_FOUND, CommandError.Code.EXTERNAL,
                CommandError.Code.TIMEOUT)), "失败分类：" + EnumSet.allOf(CommandError.Code.class));
        expectThrows(NullPointerException.class, () -> CommandError.of(null, "x"), "分类不能为 null");
    }

    // ---------------------------------------------------------------- 工具

    private static void expectThrows(Class<? extends Throwable> expected, Runnable action, String message) {
        try {
            action.run();
            check(false, message + "（应当抛 " + expected.getSimpleName() + "）");
        } catch (Throwable thrown) {
            check(expected.isInstance(thrown), message + "（实际 " + thrown.getClass().getSimpleName() + "）");
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}

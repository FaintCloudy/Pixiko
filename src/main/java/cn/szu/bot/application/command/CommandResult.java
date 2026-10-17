package cn.szu.bot.application.command;

import cn.szu.bot.domain.CommandError;

import java.util.Objects;

/**
 * 一条指令处理完的结果。
 *
 * <p>三种形态，互斥：
 * <ul>
 *   <li>{@link #text(String)}：handler 没有自己发消息，{@code text} 由 dispatcher 通过
 *       {@link CommandContext#reply(String)} 发出去；</li>
 *   <li>{@link #replied()}：handler 已经自己回过了（Phase 2 的三条委托指令就是这种——回执由原来的实现发出，
 *       再发一遍就成了两条消息）；</li>
 *   <li>{@link #failed(CommandError)}：没做成，{@code error} 说明原因（同样算"这条指令被注册表接住了"，
 *       不再回落旧路径）。</li>
 * </ul>
 *
 * <p>{@link #unhandled()} 表示"注册表没接这条指令"，调用方必须回落到原有实现。
 */
public record CommandResult(String text, boolean handled, CommandError error) {

    public CommandResult {
        if (text != null && error != null) throw new IllegalArgumentException("有 text 就不该同时有 error");
        if (!handled && (text != null || error != null)) throw new IllegalArgumentException("未接住的指令不能带 text/error");
    }

    /** handler 交出一句话，由 dispatcher 发。 */
    public static CommandResult text(String text) {
        return new CommandResult(Objects.requireNonNull(text, "text"), true, null);
    }

    /** handler 自己已经把消息发出去了（委托既有实现时用这个）。 */
    public static CommandResult replied() {
        return new CommandResult(null, true, null);
    }

    /** 注册表没接住：调用方走原路径。 */
    public static CommandResult unhandled() {
        return new CommandResult(null, false, null);
    }

    /** 接住了但失败了。 */
    public static CommandResult failed(CommandError error) {
        return new CommandResult(null, true, Objects.requireNonNull(error, "error"));
    }

    /** 接住了且没出错。 */
    public boolean ok() {
        return handled && error == null;
    }

    @Override
    public String toString() {
        if (!handled) return "CommandResult(unhandled)";
        if (error != null) return "CommandResult(failed: " + error + ")";
        return text == null ? "CommandResult(replied)" : "CommandResult(text: " + text.length() + " 字符)";
    }
}

package cn.szu.bot.application.command;

/**
 * 一条已登记指令的处理函数。
 *
 * <p>为什么是 {@code throws Exception} 而不是 {@code throws CommandError}：{@link cn.szu.bot.domain.CommandError}
 * 是不可变 record（值对象），Java 不允许 record 继承 {@link Exception}，所以它不可能出现在 {@code throws} 里。
 * 业务的"失败"用 {@link CommandResult#failed} 返回；只有真正该冒泡给调用方（例如要保留原始异常信息与
 * 供上层统一报错）的异常才 {@code throw}，dispatcher 不吞它。
 */
@FunctionalInterface
public interface CommandHandler {
    CommandResult handle(CommandContext context) throws Exception;
}

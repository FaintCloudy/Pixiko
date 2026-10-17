package cn.szu.bot.application.command;

import java.util.Objects;

/**
 * 派发一条指令：先查注册表，命中就交给 {@link CommandHandler}；没命中（或登记了但门不通过）
 * 就调用 {@link CommandFallback}，也就是原来的那条老路。
 *
 * <p>三条不变量：
 * <ol>
 *   <li>没命中时**只**调 fallback，返回 {@link CommandResult#unhandled()}，不产生任何回执；</li>
 *   <li>命中时**只**调 handler，绝不回落（否则同一条指令会被执行两遍）；handler 抛出的异常原样冒泡，
 *       dispatcher 不包、不吞、不改——上层看到的异常与走老路时完全一样；</li>
 *   <li>{@link CommandResult#text(String)} 由这里通过 {@link CommandContext#reply(String)} 发出；
 *       {@link CommandResult#replied()} 什么都不发（handler 已经自己回过了）。</li>
 * </ol>
 */
public final class CommandDispatcher {
    private final CommandRegistry registry;

    public CommandDispatcher(CommandRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** 派发：命中注册表走 handler，未命中走 fallback。返回结果（fallback 那条返回 unhandled）。 */
    public CommandResult dispatch(CommandContext context, CommandFallback fallback) throws Exception {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(fallback, "fallback");
        CommandRegistry.Entry entry = registry.entry(context.command());
        if (entry == null || !entry.matches(context)) {
            fallback.run();
            return CommandResult.unhandled();
        }
        CommandResult result = entry.handler().handle(context);
        if (result == null) throw new IllegalStateException("指令处理器没有返回结果：" + entry.name());
        if (result.text() != null) context.reply(result.text());
        return result;
    }

    /** 这个上下文会被注册表接住吗（只查表，不执行）。 */
    public boolean handles(CommandContext context) {
        CommandRegistry.Entry entry = context == null ? null : registry.entry(context.command());
        return entry != null && entry.matches(context);
    }

    /** 注册表本身（测试/排查用）。 */
    public CommandRegistry registry() {
        return registry;
    }
}

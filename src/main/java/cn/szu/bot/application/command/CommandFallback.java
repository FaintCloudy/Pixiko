package cn.szu.bot.application.command;

/**
 * 注册表没接住时要走的那条老路（Phase 2 里就是原来的 {@code dispatch} 那一长串 if）。
 *
 * <p>允许抛受检异常：老路的签名就是 {@code throws Exception}，包一层 lambda 不该改变它的异常语义。
 */
@FunctionalInterface
public interface CommandFallback {
    void run() throws Exception;
}

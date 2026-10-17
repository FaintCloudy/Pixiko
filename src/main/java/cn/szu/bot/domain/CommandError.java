package cn.szu.bot.domain;

import java.util.Objects;

/**
 * 一条指令为什么没做成。不可变，可以安全地放进 {@code CommandResult} 或日志。
 *
 * <p>只有"分类 + 一句说明"：分类决定以后由谁把它翻成回执（权限 / 用法 / 找不到 / 外部依赖 / 超时），
 * 说明是给人看的那一句。{@code message} 为 {@code null} 时归一成空串。
 *
 * <p>注意：这是**值对象**，不是异常。Java 的 record 不能继承 {@link Exception}，所以"抛"这条路径
 * 用 {@code CommandResult.failed(error)} 表达，handler 的签名里没有 {@code throws CommandError}。
 */
public record CommandError(Code code, String message) {
    /** 失败分类。 */
    public enum Code {
        /** 用法不对（参数缺失/无法解析/越界）。 */
        USAGE,
        /** 权限不够（不是 owner/admin/成员，或被白名单挡住）。 */
        FORBIDDEN,
        /** 找得到指令，但对象不存在（编号失效、模型/样式没找到）。 */
        NOT_FOUND,
        /** 外部依赖失败（SD / DeepSeek / Civitai / QQ 传输）。 */
        EXTERNAL,
        /** 等待超时。 */
        TIMEOUT
    }

    public CommandError {
        Objects.requireNonNull(code, "code");
        message = message == null ? "" : message.strip();
    }

    public static CommandError of(Code code, String message) {
        return new CommandError(code, message);
    }

    @Override
    public String toString() {
        return code + (message.isEmpty() ? "" : ": " + message);
    }
}

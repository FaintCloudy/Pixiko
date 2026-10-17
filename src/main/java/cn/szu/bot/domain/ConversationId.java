package cn.szu.bot.domain;

import java.util.Objects;

/**
 * 一个会话的身份：{@link Scope} + 会话自己的 id（群号 / 用户 id / 网页 scope）。
 *
 * <p>不可变值对象。{@code id} 去掉首尾空白后必须非空、且不含控制字符——这两条是构造期的硬约束，
 * 免得"空会话"在日志和持久化里变成一堆看不出差别的键。事件里确实取不到 id 时用
 * {@link #of(Scope, String)} 归一成 {@link #UNKNOWN}，调用方不必自己判空。
 *
 * <p>不依赖 Gson、不含可变集合，{@link #toString()} 只给 {@code scope:id}，日志里直接打是安全的
 * （群号在项目里本来就不是秘密，QQ 号才要掩码，见 {@link UserId}）。
 */
public record ConversationId(Scope scope, String id) {
    /** 事件里取不到会话 id 时的占位值（不对应任何真实的群或用户）。 */
    public static final String UNKNOWN = "unknown";

    public ConversationId {
        Objects.requireNonNull(scope, "scope");
        id = id == null ? "" : id.strip();
        if (id.isEmpty()) throw new IllegalArgumentException("会话 id 不能为空");
        if (id.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("会话 id 不能含控制字符");
    }

    /** 宽松构造：{@code null}/空白 id 一律变成 {@link #UNKNOWN}，永远不会抛。 */
    public static ConversationId of(Scope scope, String id) {
        return new ConversationId(Objects.requireNonNull(scope, "scope"), id == null || id.isBlank() ? UNKNOWN : id);
    }

    /** 是不是"取不到 id"的占位会话。 */
    public boolean unknown() {
        return UNKNOWN.equals(id);
    }

    @Override
    public String toString() {
        return scope + ":" + id;
    }
}

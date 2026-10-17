package cn.szu.bot.domain;

import java.util.Objects;

/**
 * 一个用户（或网页身份）的不可变 id。
 *
 * <p>{@code value} 去掉首尾空白后必须非空、不含控制字符。事件里取不到时用 {@link #of(String)} 归一成
 * {@link #UNKNOWN}，不会抛。
 *
 * <p><b>日志安全</b>：QQ 号属于不该出现在日志/回执里的东西（见项目里既有的 {@code maskedId} 做法），
 * 所以 {@link #toString()} 与 {@link #masked()} 只给掩码，绝不输出原文；要拿原文请显式调用
 * {@link #value()}，那是给协议层用的。
 */
public record UserId(String value) {
    /** 事件里取不到用户 id 时的占位值（不对应任何真人）。 */
    public static final String UNKNOWN = "unknown";

    public UserId {
        Objects.requireNonNull(value, "value");
        value = value.strip();
        if (value.isEmpty()) throw new IllegalArgumentException("用户 id 不能为空");
        if (value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("用户 id 不能含控制字符");
    }

    /** 宽松构造：{@code null}/空白 id 一律变成 {@link #UNKNOWN}，永远不会抛。 */
    public static UserId of(String value) {
        return new UserId(value == null || value.isBlank() ? UNKNOWN : value);
    }

    /** 是不是"取不到 id"的占位用户。 */
    public boolean unknown() {
        return UNKNOWN.equals(value);
    }

    /**
     * 日志/回执用的掩码：保留首尾各两个字，中间打码。太短的（≤4 字）整段打码，
     * 免得"保留 2+2 位"其实等于把原文原样打出来。
     */
    public String masked() {
        if (value.length() <= 4) return "***";
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    @Override
    public String toString() {
        return "UserId(" + masked() + ")";
    }
}

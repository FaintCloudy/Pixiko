package cn.szu.bot.domain;

/**
 * 一条消息/指令来自哪一种会话。只描述"从哪儿来"，不含任何权限语义（权限判定是下一步的事）。
 *
 * <p>故意用枚举而不是字符串：{@code GROUP}/{@code PRIVATE} 是 QQ 侧两种会话，{@code WEB} 是网页控制台的
 * 合成会话（不含 QQ 号），{@code DEVICE} 是其它来源（手机端、未来的本地设备）的兜底。
 */
public enum Scope {
    GROUP,
    PRIVATE,
    WEB,
    DEVICE
}

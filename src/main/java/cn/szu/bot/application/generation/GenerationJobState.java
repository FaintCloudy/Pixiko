package cn.szu.bot.application.generation;

/**
 * 生成任务的生命周期状态（Phase 1）。
 *
 * <p>为什么要一个枚举：Phase 1 之前用 {@code cancelled} / {@code suspended} 两个布尔自由组合表达状态，
 * 「挂起」和「取消」可以同时为真（先 hold 再 cancel all），十二处计数点各写各的"我该从哪个池子扣"，
 * 于是出现「等待 -1」与「挂起永久虚高」这两类越界。现在状态只允许朝 {@link #terminal()} 迁移，
 * 计数只在 Bot 的唯一转换入口 {@code transition()} 里按「离开旧状态还一份、进入新状态记一份」增减。
 *
 * <p>与状态正交、**不属于**状态的标记仍留在任务对象上：
 * {@code generating}（这一张已经进了 SD 请求，用来决定取消时要不要真去 interrupt）与
 * {@code interrupted}（SD 已确认停止）；{@code refused} 是拒绝原因字段。
 */
public enum GenerationJobState {
    /** 排队中：参与调度，剩余次数记在等待池。 */
    QUEUED("等待中"),
    /** 挂起：不参与调度，剩余次数记在挂起池；已经在下发的那一张会跑完。 */
    HELD("已挂起"),
    /** 正在下发：这一张不在剩余次数里（剩余次数是它后面还没开始的次数）。 */
    RUNNING("生成中"),
    /** 已取消：剩余次数清零，已经生成的图片照常可领。 */
    CANCELLED("已取消"),
    /** 已完成：剩余次数清零。 */
    SUCCEEDED("已完成"),
    /** 已失败（出图前防呆拒绝等）：剩余次数清零。 */
    FAILED("已失败");

    private final String label;

    GenerationJobState(String label) { this.label = label; }

    /** 队列/网页里给用户看的状态词（与 Phase 1 之前的文案逐字一致）。 */
    public String label() { return label; }

    /** 终态：不再占任何计数，剩余次数必须归零。 */
    public boolean terminal() { return this == CANCELLED || this == SUCCEEDED || this == FAILED; }

    /** 还占着某个计数池的状态（QUEUED/RUNNING 记等待池，HELD 记挂起池）。 */
    public boolean holdsCounters() { return !terminal(); }
}

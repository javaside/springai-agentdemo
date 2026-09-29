package io.github.javaside.springai.codetui.agent.goal;

/**
 * /goal 循环的相位。
 *
 * <p>{@link #INACTIVE} 与 {@link #PAUSED} 之外皆为活动/终态；终态单调——一旦进入
 * {@link #isTerminal()} 为真的相位，不再向任何其他相位转移（僵尸防护：迟到回调、迟到 verdict
 * 一律丢弃）。INACTIVE 只表示"从未设定过 goal（或已清空到初始态）"。
 */
public enum GoalPhase {

    /** 未设定 goal（或 /clear 后回到初始态）。 */
    INACTIVE,

    /** 自动轮进行中（含等 turn 结束 / 倒计时 / 评估中）。 */
    RUNNING,

    /** 暂停：等待用户任意消息恢复；再按 Esc 或 /goal stop 转 {@link #CANCELLED}。 */
    PAUSED,

    /** 终态：评估器判定目标已达成。 */
    SATISFIED,

    /** 终态：评估器判定目标不可达成。 */
    IMPOSSIBLE,

    /** 终态：自动轮配额耗尽。 */
    MAX_TURNS,

    /** 终态：token 预算软超限。 */
    BUDGET_EXCEEDED,

    /** 终态：用户取消（Esc 二次 / /goal stop）。 */
    CANCELLED,

    /** 终态：/clear 清空。 */
    CLEARED;

    /** 是否为终态（终态单调，不再转移）。 */
    public boolean isTerminal() {
        return this == SATISFIED || this == IMPOSSIBLE || this == MAX_TURNS
                || this == BUDGET_EXCEEDED || this == CANCELLED || this == CLEARED;
    }
}

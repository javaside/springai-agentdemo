package io.github.javaside.springai.codetui.agent.goal;

/**
 * 进入 {@link GoalPhase#PAUSED} 的原因（终态原因见 {@link GoalPhase} 本身）。
 *
 * <p>熔断同时为真时原因唯一确定，优先级见 spec §7：STALLED &gt; ERROR &gt; EVALUATOR &gt; PROTOCOL。
 */
public enum PauseReason {

    /** 用户按 Esc（自动轮取消 / EVALUATING 中 / 倒计时中）。 */
    ESC,

    /** 连续 stalled 达阈值（PROGRESS=stalled 或本轮零工具调用）。 */
    STALLED,

    /** onError 连续失败达阈值。 */
    ERROR,

    /** 评估器调用异常（超时/抛错）连续达阈值。 */
    EVALUATOR,

    /** 评估器输出协议解析失败（无 VERDICT 行/非法取值）连续达阈值。 */
    PROTOCOL
}

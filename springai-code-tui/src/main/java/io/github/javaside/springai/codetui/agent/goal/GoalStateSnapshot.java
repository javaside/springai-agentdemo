package io.github.javaside.springai.codetui.agent.goal;

import java.time.Instant;
import java.util.List;

/**
 * {@link GoalManager} 状态的一次性锁内快照——goal 面板与状态栏共用同一次 {@code snapshot()}
 * 调用，避免两次加锁之间的状态变化造成渲染撕裂。
 *
 * @param phase         当前相位（INACTIVE 时其余字段均为空/零值）
 * @param pauseReason   进入 PAUSED 的原因；非 PAUSED 为 {@code null}
 * @param condition     目标条件原文；未设定 goal 为 {@code null}
 * @param turnsUsed     已用自动轮次
 * @param maxTurns      自动轮上限（来自 {@link GoalConfig}）
 * @param tokenSpent    本 goal 消耗 token（相对 activate 基线的增量；未注入 accumulator 时为 0）
 * @param tokenBudget   token 预算（0=预算关闭）
 * @param stalledStreak 连续停滞轮数（熔断计数）
 * @param activatedAt   激活时间；未设定 goal 为 {@code null}
 * @param recentTraces  最近的评估轨迹（面板滚动行：onVerdict 放行时入账，≤8 条 FIFO，含终局 verdict）
 * @param lastSummary   最近一条结论摘要（达成/不可达原因等；空串=尚无）
 */
public record GoalStateSnapshot(
        GoalPhase phase,
        PauseReason pauseReason,
        String condition,
        int turnsUsed,
        int maxTurns,
        long tokenSpent,
        long tokenBudget,
        int stalledStreak,
        Instant activatedAt,
        List<GoalEvalTrace> recentTraces,
        String lastSummary) {

    /** 面板轨迹行：第几轮、评估器给了什么结论、一句话原因。 */
    public record GoalEvalTrace(int turn, String verdict, String reason) { }
}

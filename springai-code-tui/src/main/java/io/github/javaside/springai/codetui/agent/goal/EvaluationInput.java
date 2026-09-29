package io.github.javaside.springai.codetui.agent.goal;

import java.util.List;

/**
 * 评估器输入快照（{@code GoalManager.buildEvaluationInput} 锁内一次性取齐，防两次锁读撕裂；
 * Task 6 的 {@code GoalEvaluator} 消费）。
 *
 * @param condition     goal 条件原文（评估器语言跟随它：含 CJK → 中文输出）
 * @param turn          已用自动轮次
 * @param maxTurns      自动轮上限
 * @param stalledStreak 当前停滞连击（评估器判 stalled 的参照之一）
 * @param stateLedger   累积进度账本（空串=尚无）
 * @param recentTurns   最近轮滚动记录（≤8 条 FIFO；防御性不可变副本）
 */
public record EvaluationInput(String condition, int turn, int maxTurns, int stalledStreak,
                              String stateLedger, List<GoalTurnRecord> recentTurns) {

    public EvaluationInput {
        recentTurns = List.copyOf(recentTurns);
    }
}

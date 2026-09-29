package io.github.javaside.springai.codetui.agent.goal;

/**
 * 单轮滚动记录：评估素材里「最近几轮发生了什么」的最小快照（{@code GoalManager} 内 ≤8 条 FIFO，
 * 超 8 挤掉最旧）。
 *
 * @param turn             该轮的自动轮次（落记录时的 {@code turnsUsed}）
 * @param evalReason       该轮开始前的上一轮评估结论（首轮为 {@code null}；给评估器上下文连续性）
 * @param assistantTail    该轮 agent 末文本尾部（≤2000 字符，{@link GoalText#tail} 码点安全截断）
 * @param toolCallCount    该轮工具调用次数
 * @param userInterjection 该轮用户插话原文（无插话为 {@code null}；用户纠偏对评估器可见）
 */
public record GoalTurnRecord(int turn, String evalReason, String assistantTail,
                             int toolCallCount, String userInterjection) {
}

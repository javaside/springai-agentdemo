package io.github.javaside.springai.codetui.agent.goal;

/**
 * 一轮结束后的素材收集（{@code GoalManager.recordTurnMaterial} 的入参；CodingAgent 收集，Task 9）。
 *
 * <p>尾部截断与轮次/上一轮结论的绑定在 {@code GoalManager} 锁内完成（{@link GoalText#tail}
 * 2000 字符码点安全），收集方只递原文即可。
 *
 * @param assistantTail    该轮 agent 末文本（原文，可超长；落库前截尾部）
 * @param toolCallCount    该轮工具调用次数
 * @param userInterjection 该轮用户插话原文（无插话为 {@code null}）
 */
public record GoalTurnMaterial(String assistantTail, int toolCallCount, String userInterjection) {
}

package io.github.javaside.springai.codetui.agent;

import java.util.List;

/**
 * AgentListener 的空实现接口版：测试只覆写关心的方法，其余噪音方法给空默认实现。
 *
 * <p>与 {@link StubListener}（抽象类版）的区别：本接口可用于匿名实现 {@code new AgentListenerAdapter() { }}，
 * 也可被测试内的静态类 {@code implements}。
 *
 * <p><b>刻意不覆写 {@code onPlanSubmitted} / {@code onPermissionRequested}</b>：
 * 它们在 {@link AgentListener} 上的默认实现<b>不是空实现</b>（会立刻应答，让工具线程能继续），
 * 在这里覆成空实现就等于把「静默挂死」引进测试桩。
 */
interface AgentListenerAdapter extends AgentListener {
    @Override default void onTurnStarted(long turnId) {}
    @Override default void onUserMessage(long turnId, String text) {}
    @Override default void onAssistantToken(long turnId, String token) {}
    @Override default void onToolStarted(long turnId, String toolName, String input) {}
    @Override default void onToolFinished(long turnId, String toolName, String output, boolean ok) {}
    @Override default void onSubagentStarted(long turnId, String taskId, String agentName, String description) {}
    @Override default void onSubagentFinished(long turnId, String taskId, String finalText) {}
    @Override default void onTodoUpdated(long turnId, List<String> todoLines) {}
    @Override default void onTurnComplete(long turnId) {}
    @Override default void onError(long turnId, Throwable error) {}
    @Override default void onQuestionAsked(long turnId, AskRequest request) {}
    @Override default void onCompactionStarted(String reason) {}
    @Override default void onCompactionFinished(int eventsRemoved, int tokensSaved) {}
    @Override default void onCompactionFailed(String message) {}
}

package com.example.springai.codetui.agent;

import java.util.List;

/** AgentListener 的空实现基类：测试只覆写关心的方法。 */
class StubListener implements AgentListener {
    @Override public void onTurnStarted(long turnId) {}
    @Override public void onUserMessage(long turnId, String text) {}
    @Override public void onAssistantToken(long turnId, String token) {}
    @Override public void onToolStarted(long turnId, String toolName, String input) {}
    @Override public void onToolFinished(long turnId, String toolName, String output, boolean ok) {}
    @Override public void onSubagentStarted(long turnId, String taskId, String agentName, String description) {}
    @Override public void onSubagentFinished(long turnId, String taskId, String finalText) {}
    @Override public void onTodoUpdated(long turnId, List<String> todoLines) {}
    @Override public void onTurnComplete(long turnId) {}
    @Override public void onError(long turnId, Throwable error) {}
    @Override public void onQuestionAsked(long turnId, AskRequest request) {}
    @Override public void onCompactionStarted(String reason) {}
    @Override public void onCompactionFinished(int eventsRemoved, int tokensSaved) {}
    @Override public void onCompactionFailed(String message) {}
}

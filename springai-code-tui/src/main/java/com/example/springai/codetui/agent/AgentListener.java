package com.example.springai.codetui.agent;

import java.util.List;

/**
 * CodingAgent → UI 的唯一接缝。只用纯 Java 类型（不泄漏任何 Spring AI 类型）。
 * 每个方法都带 turnId，供 UI 过滤「已取消回合」的迟到事件。
 */
public interface AgentListener {
    void onTurnStarted(long turnId);                 // submit 顶部同步调用
    void onUserMessage(long turnId, String text);
    void onAssistantToken(long turnId, String token);
    void onToolStarted(long turnId, String toolName, String input);
    void onToolFinished(long turnId, String toolName, String output, boolean ok);
    void onTodoUpdated(long turnId, List<String> todoLines);   // Todos 转成可显示的行
    void onTurnComplete(long turnId);
    void onError(long turnId, Throwable error);
}

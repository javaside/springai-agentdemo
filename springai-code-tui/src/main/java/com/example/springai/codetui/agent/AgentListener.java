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

    /**
     * 模型经 AskUserQuestionTool 发问：UI 应弹出作答面板并最终经 {@code request.responder()} 应答。
     * 与其它方法一样带 turnId 供迟到过滤。落地端会阻塞工具线程直到 UI 应答（见 UserQuestionBridge）。
     */
    void onQuestionAsked(long turnId, AskRequest request);

    // ── 会话压缩（跨回合的横切信号；无 turnId） ──
    /** 压缩开始。reason: "auto"（阈值触发）| "manual"（/compact）。 */
    void onCompactionStarted(String reason);
    /** 压缩完成。eventsRemoved：被归档移除的事件数；tokensSaved：估算节省 token。 */
    void onCompactionFinished(int eventsRemoved, int tokensSaved);
    /** 压缩失败。message：失败原因（已做 null 安全处理的字符串）。 */
    void onCompactionFailed(String message);
}

package io.github.javaside.springai.codetui.agent;

/** 审批面板的五种结果——UI 选完后经 {@link PermissionResponder} 喂回阻塞的工具线程。 */
public enum PermissionOutcome {
    /** 允许一次。 */
    ALLOW_ONCE,
    /** 允许，本会话不再问（加一条内存 session 规则）。 */
    ALLOW_SESSION,
    /** 允许，永久（写 {@code <root>/.codetui/permissions.json}）。 */
    ALLOW_ALWAYS,
    /** 拒绝，让模型换个做法——<b>回合继续</b>，工具返回拒绝串。 */
    DENY,
    /** 中断本回合——工具线程抛 {@link PermissionCancelledException}，UI 侧走既有 {@code cancelTurnFor}。 */
    CANCEL
}

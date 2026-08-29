package io.github.javaside.springai.codetui.agent.seam;

/**
 * 计划审批的结果。前三项对应面板三个选项，{@link #CANCEL} 对应 Esc / 回合被取消。
 */
public enum PlanOutcome {
    /** 批准并切到「自动接受编辑」——工作区内的改动不再逐个问。 */
    APPROVE_ACCEPT_EDITS,
    /** 批准并切到「默认」——逐个确认。 */
    APPROVE_DEFAULT,
    /** 继续完善计划：留在 PLAN，把用户的反馈文本带回给模型。 */
    KEEP_PLANNING,
    /** 中断本回合——工具线程抛 {@link PermissionCancelledException}，UI 走既有 {@code cancelTurnFor}。 */
    CANCEL
}

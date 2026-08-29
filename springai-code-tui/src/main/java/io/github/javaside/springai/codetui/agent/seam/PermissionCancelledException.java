package io.github.javaside.springai.codetui.agent.seam;

/**
 * 审批 / 计划审批期回合被中断（用户选「拒绝并中断本回合」、Esc，或回合被 dispose 中断了工具线程）。
 *
 * <p>{@code PermissionCallback} 与 {@code PlanApprovalBridge} 共用本异常——两者的语义完全一致
 * （「这个回合结束了」），再造一个近乎同名的异常只会让调用方多一个必须记住的分支。
 *
 * <p>与 {@link QuestionCancelledException} 同款：此时回合已被 dispose、会话经 {@code doOnCancel} 回滚，
 * 本异常随流被丢弃，不会走到用户面前。
 */
public class PermissionCancelledException extends RuntimeException {
    public PermissionCancelledException() {
        super("permission request cancelled");
    }
}

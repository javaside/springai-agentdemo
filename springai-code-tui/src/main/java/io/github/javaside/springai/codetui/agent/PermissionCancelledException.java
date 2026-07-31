package io.github.javaside.springai.codetui.agent;

/**
 * 审批期回合被中断（用户选「拒绝并中断本回合」/ Esc，或回合被 dispose 中断了工具线程）。
 *
 * <p>与 {@link QuestionCancelledException} 同款：此时回合已被 dispose、会话经 {@code doOnCancel} 回滚，
 * 本异常随流被丢弃，不会走到用户面前。
 */
public class PermissionCancelledException extends RuntimeException {
    public PermissionCancelledException() {
        super("permission request cancelled");
    }
}

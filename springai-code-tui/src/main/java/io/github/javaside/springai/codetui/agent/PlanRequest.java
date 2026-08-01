package io.github.javaside.springai.codetui.agent;

import java.util.Objects;

/**
 * 一次计划审批请求：由 {@code PlanApprovalBridge} 构造、经 {@code AgentListener.onPlanSubmitted}
 * 交给 UI；UI 把 {@link #plan} 渲染进 scrollback 并弹三选项面板，用户选完经 {@link #responder}
 * 唤醒阻塞的工具线程。
 *
 * @param turnId    发起回合（迟到过滤）
 * @param plan      模型提交的 markdown 计划正文（null 归一成空串）
 * @param responder UI 应答回调，<b>不可为 null</b>
 */
public record PlanRequest(long turnId, String plan, PlanResponder responder) implements ModalRequest {

    /**
     * 归一 + 拒绝 null responder：<b>在构造期失败，而不是在排空期</b>。
     *
     * <p>理由与 {@link PermissionRequest} 完全相同：排空循环在某个元素上抛 NPE 会中断，
     * 队列里其后的工具线程永不被唤醒，且异常沿 {@code synchronized} 的 {@code cancelCurrent()}
     * 传到 UI 线程的 Esc 处理器。
     */
    public PlanRequest {
        Objects.requireNonNull(responder, "responder 不可为 null：null 会让取消期的排空循环中断，"
                + "队列里其后的工具线程永久 park");
        plan = plan == null ? "" : plan;
    }

    /** {@link ModalRequest} 统一取消入口：投 CANCEL，工具线程随后抛 {@link PermissionCancelledException}。 */
    @Override
    public void cancel() {
        responder.respond(PlanOutcome.CANCEL, "");
    }
}

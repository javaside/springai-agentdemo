package io.github.javaside.springai.codetui.agent.seam;

/**
 * UI → 工具线程的一次性应答口（实现方是 {@code PlanApprovalBridge} 内的一次性队列）。
 *
 * <p>契约与 {@link PermissionResponder} <b>完全一致</b>，逐条复述是因为违反任一条都会造成静默挂死：
 * <ul>
 *   <li><b>实现方必须非阻塞</b>——本口由 UI 线程调用，阻塞就是冻住整个界面；</li>
 *   <li><b>一次性消费</b>：容量 1 的队列 + 非阻塞 {@code offer}，消费方<b>只读一次</b>。
 *       <b>不得写重试循环、不得复用/重新武装同一个 handoff</b>——否则会读到陈旧的 CANCEL，
 *       杀掉用户刚刚批准的计划；</li>
 *   <li><b>实现不得抛异常</b>——取消期排空循环遍历整条模态队列，一个实现抛异常
 *       就让其后的工具线程永久 park。</li>
 * </ul>
 *
 * @see ModalRequest#cancel()
 */
@FunctionalInterface
public interface PlanResponder {
    /**
     * @param outcome  用户的选择
     * @param feedback 仅 {@link PlanOutcome#KEEP_PLANNING} 有意义（用户希望怎么改）；其余情况传空串
     */
    void respond(PlanOutcome outcome, String feedback);
}

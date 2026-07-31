package io.github.javaside.springai.codetui.agent;

/**
 * UI → 工具线程的一次性应答口（实现方是 {@code PermissionCallback} 内的一次性队列）。
 *
 * <p><b>实现方必须非阻塞</b>：本口由 UI 线程调用（面板选完 / 回合取消时遍历模态队列），
 * 若实现会阻塞，卡住的就是整个界面。照 {@code UserQuestionBridge} 的做法——
 * 容量 1 的队列 + {@code offer}。
 *
 * <p><b>「首个信号胜出」靠的是一次性消费，不是「队列满了就丢」</b>。
 * 这条差别务必读准：工具线程消费掉第一个结果后队列<b>即空</b>，
 * 此时迟到的 {@code CANCEL} 会被<b>入队</b>而非丢弃。今天无害，只因消费方恰好只 poll 一次。
 * 所以真正的保证是：<b>一次性消费方 + 非阻塞 {@code offer}；消费方只读一次，故后续信号被忽略</b>（不是被丢弃）。
 * 由此推出两条实现禁令——{@code PermissionCallback} 的实现者尤其注意：
 * <ul>
 *   <li><b>不得写重试循环</b>再读第二次；</li>
 *   <li><b>不得复用 / 重新武装同一个 handoff</b>。</li>
 * </ul>
 * 违反任一条都会读到那个陈旧的 {@code CANCEL}，<b>杀掉用户刚刚批准的回合</b>。
 * 每次工具调用都必须新建一个 handoff（这也是「一请求一应答口」的由来）。
 *
 * <p><b>实现不得抛异常</b>：取消期的排空循环会遍历整条模态队列逐个调用本口，
 * 一个实现抛异常就会中断循环、让其后的工具线程永久 park（见 {@link ModalRequest#cancel()}）。
 */
@FunctionalInterface
public interface PermissionResponder {
    void respond(PermissionOutcome outcome);
}

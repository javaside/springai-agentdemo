package io.github.javaside.springai.codetui.agent;

/**
 * UI → 工具线程的一次性应答口（实现方是 {@code PermissionCallback} 内的一次性队列）。
 *
 * <p><b>实现方必须非阻塞</b>：本口由 UI 线程调用（面板选完 / 回合取消时遍历模态队列），
 * 若实现会阻塞，卡住的就是整个界面。照 {@code UserQuestionBridge} 的做法——
 * 容量 1 的队列 + {@code offer}：满了就丢，「首个信号胜出」，
 * 重复或交叉的应答（如面板已选完又来一发迟到的 cancel）被安全丢弃，既不卡死也不抛异常。
 */
@FunctionalInterface
public interface PermissionResponder {
    void respond(PermissionOutcome outcome);
}

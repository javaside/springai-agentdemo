package io.github.javaside.springai.codetui.agent;

import java.util.List;

/**
 * 一次问询句柄：由 {@code UserQuestionBridge} 构造、经 {@link AgentListener#onQuestionAsked} 交给 UI。
 * UI 据 {@link #questions} 渲染作答面板，答完/取消经 {@link #responder} 唤醒工具线程。
 *
 * @param turnId    发起问询的回合（供 UI 迟到过滤）
 * @param questions 顺序问询的问题列表（1–4 个）
 * @param responder UI 应答回调
 */
public record AskRequest(long turnId, List<QuestionSpec> questions, AskResponder responder)
        implements ModalRequest {

    public AskRequest {
        questions = List.copyOf(questions);
    }

    /** {@link ModalRequest} 统一取消入口：转成问询自己的取消语义。 */
    @Override
    public void cancel() {
        responder.cancel();
    }
}

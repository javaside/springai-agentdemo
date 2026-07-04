package com.example.springai.codetui.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question.Option;

/**
 * AskUserQuestionTool 的 QuestionHandler：把工具线程 ↔ UI 线程之间的一次问询变成阻塞握手。
 *
 * <p><b>为何阻塞</b>：{@code handle()} 在工具调用线程上同步执行、必须返回完整答案 map 才算工具执行完毕。
 * 本桥把问询经 {@link AgentListener#onQuestionAsked} 交给 UI，然后阻塞在一次性队列 {@code take()} 上，
 * 直到 UI 经 {@link AskResponder#answer} 喂回答案（或 {@link AskResponder#cancel} 取消）。
 *
 * <p><b>turnId</b>：{@code handle()} 在被 {@link ToolEventCallback} 装饰的工具 call 内同线程同步触发，
 * 故直接读 {@link ToolEventCallback#currentTurnId()}（与 TodoWriteTool 的 handler 同款）。
 *
 * <p><b>取消</b>：cancel 经队列投递哨兵 {@link #CANCEL}，{@code handle()} 唤醒后抛
 * {@link QuestionCancelledException}。此时回合已被 dispose、会话经 doOnCancel 回滚，异常随流被丢弃。
 *
 * <p><b>答案完整性</b>：UI 顺序问询、答完全部问题才 answer，故返回 map 覆盖所有问题文本，
 * {@code AskUserQuestionTool} 的 answersValidation 不会触发 InvalidUserAnswerException。
 */
public final class UserQuestionBridge implements AskUserQuestionTool.QuestionHandler {

    /** 取消哨兵（身份比较）。 */
    private static final Object CANCEL = new Object();

    private final AgentListener listener;

    public UserQuestionBridge(AgentListener listener) {
        this.listener = listener;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> handle(List<Question> questions) {
        long turnId = ToolEventCallback.currentTurnId();
        List<QuestionSpec> specs = translate(questions);
        ArrayBlockingQueue<Object> handoff = new ArrayBlockingQueue<>(1);
        AskResponder responder = new AskResponder() {
            @Override public void answer(Map<String, String> a) { handoff.offer(a); }
            @Override public void cancel() { handoff.offer(CANCEL); }
        };
        listener.onQuestionAsked(turnId, new AskRequest(turnId, specs, responder));
        Object result;
        try {
            result = handoff.take();                 // 阻塞工具线程直到 UI 应答
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QuestionCancelledException();
        }
        if (result == CANCEL) {
            throw new QuestionCancelledException();
        }
        return (Map<String, String>) result;
    }

    /** Spring AI 的 Question/Option → 纯 Java QuestionSpec/OptionSpec（剥离 Spring AI 类型，接缝纪律）。 */
    static List<QuestionSpec> translate(List<Question> questions) {
        List<QuestionSpec> out = new ArrayList<>(questions.size());
        for (Question q : questions) {
            List<OptionSpec> opts = new ArrayList<>();
            for (Option o : q.options()) {
                opts.add(new OptionSpec(o.label(), o.description()));
            }
            boolean multi = q.multiSelect() != null && q.multiSelect();
            out.add(new QuestionSpec(q.question(), q.header(), opts, multi));
        }
        return out;
    }
}

package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.AskResponder;
import io.github.javaside.springai.codetui.agent.seam.OptionSpec;
import io.github.javaside.springai.codetui.agent.seam.QuestionSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ConversationState 对 AskRequest 的落地：正常回合入模态队列、迟到回合丢弃并 cancel、移除后清空。 */
class ConversationStateAskTest {

    private static AskRequest req(long turnId, AtomicBoolean cancelled) {
        AskResponder r = new AskResponder() {
            @Override public void answer(Map<String, String> a) {}
            @Override public void cancel() { cancelled.set(true); }
        };
        QuestionSpec q = new QuestionSpec("选哪个?", "选择",
                List.of(new OptionSpec("A", "第一"), new OptionSpec("B", "第二")), false);
        return new AskRequest(turnId, List.of(q), r);
    }

    @Test
    void onQuestionAsked_storesForCurrentTurn() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(7);                       // acceptingTurnId = 7
        AskRequest r = req(7, new AtomicBoolean());
        s.onQuestionAsked(7, r);
        assertSame(r, s.peekModal(), "当前回合的问询应进模态队列");
    }

    @Test
    void onQuestionAsked_dropsAndCancelsLateTurn() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(9);                       // 当前回合是 9
        AtomicBoolean cancelled = new AtomicBoolean();
        s.onQuestionAsked(8, req(8, cancelled));  // 迟到回合 8
        assertNull(s.peekModal(), "迟到回合不应弹面板");
        assertTrue(cancelled.get(), "迟到问询应被 cancel 以唤醒工具线程");
    }

    @Test
    void removeModal_resets() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AskRequest r = req(1, new AtomicBoolean());
        s.onQuestionAsked(1, r);
        s.removeModal(r);
        assertNull(s.peekModal());
    }
}

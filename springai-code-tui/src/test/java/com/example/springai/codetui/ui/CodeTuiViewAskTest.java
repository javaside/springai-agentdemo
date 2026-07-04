package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.AskRequest;
import com.example.springai.codetui.agent.AskResponder;
import com.example.springai.codetui.agent.OptionSpec;
import com.example.springai.codetui.agent.QuestionSpec;
import com.example.springai.codetui.agent.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 作答面板（单选）纯状态断言：不起真实 TUI，直接喂 KeyEvent 给 View 的按键入口。 */
class CodeTuiViewAskTest {

    private static AskRequest ask(AtomicReference<Map<String, String>> got, AtomicBoolean cancelled, QuestionSpec... q) {
        AskResponder r = new AskResponder() {
            @Override public void answer(Map<String, String> a) { got.set(a); }
            @Override public void cancel() { cancelled.set(true); }
        };
        return new AskRequest(1, List.of(q), r);
    }

    private static QuestionSpec single(String qn, String... labels) {
        java.util.List<OptionSpec> os = new java.util.ArrayList<>();
        for (String l : labels) os.add(new OptionSpec(l, l + " 说明"));
        return new QuestionSpec(qn, "选择", os, false);
    }

    private static CodeTuiView view(ConversationState s) {
        return new CodeTuiView(s, (SubmitHandler) t -> null, Path.of("."));
    }

    @Test
    void singleSelect_enterRecordsLabelAndFinishes() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), single("选哪个?", "A", "B", "C")));
        CodeTuiView v = view(s);
        v.tickForTest();                                    // drain 侦测进入作答态
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));     // 高亮 A→B
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));    // 选 B
        assertEquals(Map.of("选哪个?", "B"), got.get());
        assertNull(s.pendingAsk(), "答完应清除 pendingAsk");
    }

    @Test
    void multiQuestion_advancesThenFinishes() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(),
                single("Q1?", "A", "B"), single("Q2?", "X", "Y")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));    // Q1 → A
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));     // Q2 高亮 X→Y
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));    // Q2 → Y
        assertEquals(Map.of("Q1?", "A", "Q2?", "Y"), got.get());
    }

    @Test
    void esc_cancelsAndClears() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        s.onQuestionAsked(1, ask(new AtomicReference<>(), cancelled, single("选?", "A", "B")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertTrue(cancelled.get(), "Esc 应触发 responder.cancel");
        assertNull(s.pendingAsk());
    }
}

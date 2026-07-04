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

    private static QuestionSpec multi(String qn, String... labels) {
        java.util.List<OptionSpec> os = new java.util.ArrayList<>();
        for (String l : labels) os.add(new OptionSpec(l, l + " 说明"));
        return new QuestionSpec(qn, "特性", os, true);
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

    @Test
    void emptyOptions_autoCancelsWithoutEnteringModal() {
        // 上游 Java 校验不强制选项数；空选项问询若进模态，首个 ↑↓ 会 `% 0` 除零崩线程。drain 应优雅降级为取消。
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        QuestionSpec noOpts = new QuestionSpec("坏问题?", "坏", List.of(), false);
        s.onQuestionAsked(1, ask(new AtomicReference<>(), cancelled, noOpts));
        CodeTuiView v = view(s);
        v.tickForTest();                                   // 侦测到畸形问询
        assertTrue(cancelled.get(), "空选项问询应被自动取消");
        assertNull(s.pendingAsk(), "畸形问询应从 state 摘除，避免反复重入");
        // 进模态后若误入，下面这次 ↑ 会除零抛异常；不抛即证明未入模态。
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));
    }

    @Test
    void multiSelect_spaceTogglesCommaJoined() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), multi("要哪些?", "认证", "数据库", "缓存")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofChar(' '));            // 勾选「认证」(高亮在 0)
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // 高亮→数据库
        v.feedKeyForTest(KeyEvent.ofChar(' '));            // 勾选「数据库」
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 确认
        assertEquals(Map.of("要哪些?", "认证, 数据库"), got.get());
    }

    @Test
    void multiSelect_enterWithNoneShowsNoticeAndStays() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), multi("要哪些?", "A", "B")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 未勾选
        assertNull(got.get(), "未勾选不应提交");
        // 提示必须真正出现在作答态状态行（statusLine 早于通用 notice 分支 return，故须自带回显）。
        assertTrue(v.askStatusText().startsWith("至少选择一项"), "空选确认应在状态行显示提示，实际：" + v.askStatusText());
        // 勾选一项后提示应消失，且能正常提交。
        v.feedKeyForTest(KeyEvent.ofChar(' '));
        assertEquals("↑↓ 移动 · 空格勾选 · Enter 确认 · Esc 取消", v.askStatusText(), "勾选后应清掉提示、恢复操作指引");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(Map.of("要哪些?", "A"), got.get());
    }

    @Test
    void multiSelect_untoggleRemovesFromAnswer() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), multi("要哪些?", "认证", "数据库", "缓存")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofChar(' '));            // 勾选「认证」(idx0)
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // →数据库
        v.feedKeyForTest(KeyEvent.ofChar(' '));            // 勾选「数据库」(idx1)
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));      // ←认证
        v.feedKeyForTest(KeyEvent.ofChar(' '));            // 取消勾选「认证」
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 确认：只剩「数据库」
        assertEquals(Map.of("要哪些?", "数据库"), got.get());
    }
}

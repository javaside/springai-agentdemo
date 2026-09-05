package io.github.javaside.springai.codetui.ui;

import dev.tamboui.text.CharWidth;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationStateRetryTest {

    @Test
    void retryFlushesPartialLineAddsSeparatorAndEntersRetrying() {
        ConversationState state = started(1L);
        state.onAssistantToken(1L, "半截");

        state.onRetryScheduled(1L, 1, 2, 1000L, "流中断");

        List<OutputLine> lines = state.drainPending();
        int partial = indexOf(lines, OutputLine.Kind.ASSISTANT, "半截");
        int separator = indexOf(lines, OutputLine.Kind.INFO, "");
        int retry = indexContaining(lines, OutputLine.Kind.INFO, "↻ 重试中 (1/2·续跑)");
        assertTrue(partial >= 0, "半截残行应保留：" + lines);
        assertTrue(separator > partial && retry > separator, "残行、空 INFO、重试行应依次出现：" + lines);
        assertTrue(lines.get(retry).text().contains("流中断"));
        assertTrue(lines.get(retry).text().contains("1.0s 后重发"));
        assertEquals(ConversationState.Status.RETRYING, state.status());
        assertFalse(state.isIdle());
        assertEquals("↻ 重试中 1/2·续跑", state.retryLabel());
        assertEquals("1.0s", state.retryBackoffText());

        state.onAssistantToken(1L, "新");
        assertEquals(ConversationState.Status.THINKING, state.status());
        assertNull(state.retryLabel());
        assertNull(state.retryBackoffText());
    }

    @Test
    void transportRetryWithoutPartialLineHasNoSeparator() {
        ConversationState state = started(1L);

        state.onRetryScheduled(1L, 2, 5, 500L, "429");

        List<OutputLine> lines = state.drainPending();
        assertEquals(1, lines.size(), lines.toString());
        assertEquals(OutputLine.Kind.INFO, lines.get(0).kind());
        assertTrue(lines.get(0).text().contains("(2/5·传输)"));
        assertEquals("↻ 重试中 2/5·传输", state.retryLabel());
        assertEquals("0.5s", state.retryBackoffText());
    }

    @Test
    void lateRetryIsIgnored() {
        ConversationState state = started(2L);
        state.drainPending();

        state.onRetryScheduled(1L, 1, 2, 1000L, "late");

        assertTrue(state.drainPending().isEmpty());
        assertEquals(ConversationState.Status.THINKING, state.status());
        assertNull(state.retryLabel());
    }

    @Test
    void toolFinishMovesRetryingBackToThinking() {
        ConversationState state = started(1L);
        state.onRetryScheduled(1L, 1, 2, 1000L, "断流");

        state.onToolFinished(1L, "Read", "ok", true);

        assertEquals(ConversationState.Status.THINKING, state.status());
    }

    @Test
    void compactionDoesNotReplaceRetryingStatus() {
        ConversationState state = started(1L);
        state.onRetryScheduled(1L, 1, 2, 1000L, "断流");

        state.onCompactionStarted("auto");
        assertEquals(ConversationState.Status.RETRYING, state.status());
        state.onCompactionFinished(1, 10);
        assertEquals(ConversationState.Status.RETRYING, state.status());
    }

    @Test
    void retryReasonIsSingleLineAndWholeInfoLineFits80Columns() {
        ConversationState state = started(1L);
        String reason = ("很长的原因\n仍然很长 ").repeat(30);

        state.onRetryScheduled(1L, 1, 2, 1000L, reason);

        OutputLine retry = state.drainPending().get(0);
        assertFalse(retry.text().contains("\n"));
        assertTrue(CharWidth.of(retry.text()) <= 80,
                () -> "重试行显示宽度应不超过 80，实际 " + CharWidth.of(retry.text()) + "：" + retry.text());
    }

    @Test
    void retryPublishesAllIncludingControl() {
        ConversationState state = started(1L);
        List<Integer> bits = new ArrayList<>();
        state.setUiChangeListener(bits::add);

        state.onRetryScheduled(1L, 1, 2, 1000L, "断流");

        assertEquals(List.of(UiDirty.ALL), bits);
        assertTrue((bits.get(0) & UiDirty.CONTROL) != 0);
    }

    private static ConversationState started(long turnId) {
        ConversationState state = new ConversationState();
        state.onTurnStarted(turnId);
        state.drainPending();
        return state;
    }

    private static int indexOf(List<OutputLine> lines, OutputLine.Kind kind, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).kind() == kind && lines.get(i).text().equals(text)) return i;
        }
        return -1;
    }

    private static int indexContaining(List<OutputLine> lines, OutputLine.Kind kind, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).kind() == kind && lines.get(i).text().contains(text)) return i;
        }
        return -1;
    }
}

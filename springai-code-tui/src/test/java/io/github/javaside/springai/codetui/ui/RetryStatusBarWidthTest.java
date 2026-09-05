package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryStatusBarWidthTest {

    private static final SubmitHandler NOOP = text -> null;

    @Test
    void retryAtDefault80ColumnsKeepsLabelAndCancelButOmitsBackoff(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView view = new CodeTuiView(state, NOOP, root);
        state.onTurnStarted(1L);
        state.onRetryScheduled(1L, 1, 2, 1000L, "断流");

        String screen = ViewScreen.of(view, 80);

        assertTrue(screen.contains("↻ 重试中 1/2·续跑"), screen);
        assertTrue(screen.contains("Esc 取消"), screen);
        assertFalse(screen.contains("退避"), screen);
    }

    @Test
    void retryAtWideTerminalShowsBackoff(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView view = new CodeTuiView(state, NOOP, root);
        state.onTurnStarted(1L);
        state.onRetryScheduled(1L, 1, 2, 1000L, "断流");
        view.terminalWidthForTest(120);

        String screen = ViewScreen.of(view, 120);

        assertTrue(screen.contains("退避 1.0s"), screen);
        assertTrue(screen.contains("Esc 取消"), screen);
    }
}

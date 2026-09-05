package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.session.ContextStats;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryStatusBarWidthTest {

    private static final SubmitHandler NOOP = text -> null;
    private static final SubmitHandler CACHED_CONTEXT = new SubmitHandler() {
        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public ContextStats contextStats() {
            return new ContextStats(100, 40, 50, 8, 2, 155_184L, 100_000L, 200_000L, 20, 10, 0, 0L,
                    120_832L, 155_184L, 78);
        }
    };

    @Test
    void retryAtDefault80ColumnsKeepsLabelAndCancelButOmitsBackoff(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView view = new CodeTuiView(state, CACHED_CONTEXT, root);
        state.onTurnStarted(1L);
        state.onRetryScheduled(1L, 1, 2, 1000L, "断流");
        view.ctxUsageForTest().refresh();

        String screen = ViewScreen.of(view, 80);

        assertTrue(screen.contains("↻ 重试中 1/2·续跑"), screen);
        assertTrue(screen.contains("Esc 取消"), screen);
        assertFalse(screen.contains("退避"), screen);
        assertFalse(screen.contains("缓存命中"), screen);
    }

    @Test
    void retryBackoffAppearsAt100ColumnsButNot99(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView view = new CodeTuiView(state, NOOP, root);
        state.onTurnStarted(1L);
        state.onRetryScheduled(1L, 1, 2, 1000L, "断流");

        view.terminalWidthForTest(99);
        String screen99 = ViewScreen.of(view, 99);
        assertFalse(screen99.contains("退避"), screen99);

        view.terminalWidthForTest(100);
        String screen100 = ViewScreen.of(view, 100);
        assertTrue(screen100.contains("退避 1.0s"), screen100);
        assertTrue(screen100.contains("Esc 取消"), screen100);
    }
}

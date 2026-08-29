package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.session.ContextStats;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusyCacheHitStatusTest {

    private static final class Stub implements SubmitHandler {
        private final ContextStats stats;
        private final PermissionMode mode;

        private Stub(ContextStats stats, PermissionMode mode) {
            this.stats = stats;
            this.mode = mode;
        }

        @Override
        public reactor.core.Disposable submit(String text) {
            return null;
        }

        @Override
        public ContextStats contextStats() {
            return stats;
        }

        @Override
        public String currentModel() {
            return "deepseek-chat";
        }

        @Override
        public PermissionMode permissionMode() {
            return mode;
        }
    }

    @Test
    void thinkingShowsCacheHit(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView view = view(state, root, statsWithCacheHit());
        state.onUserMessage(1L, "hello");
        view.ctxUsageForTest().refresh();
        state.onTurnStarted(1L);

        String screen = ViewScreen.of(view, 80);
        assertTrue(screen.contains("思考中"), screen);
        assertTrue(screen.contains("缓存命中 78%"), screen);
        assertFalse(screen.contains("上下文 "), screen);
    }

    @Test
    void runningToolFitsCacheHitAndCancelAt80Columns(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView view = view(state, root, statsWithCacheHit(), PermissionMode.ACCEPT_EDITS);
        state.onUserMessage(1L, "hello");
        view.ctxUsageForTest().refresh();
        state.onTurnStarted(1L);
        state.onToolStarted(1L, "Task", "{\"prompt\":\"a very long delegated task prompt that fills the status line and must shrink\"}");

        String screen = ViewScreen.of(view, 80);
        assertTrue(screen.contains("运行 Task"), screen);
        assertTrue(screen.contains("缓存命中 78%"), screen);
        assertTrue(screen.contains("Esc 取消"), screen);
        assertFalse(screen.contains("上下文 "), screen);
    }

    @Test
    void busyStatesOmitCacheSegmentWithoutUsage(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView view = view(state, root, statsWithoutCacheHit());
        state.onUserMessage(1L, "hello");
        view.ctxUsageForTest().refresh();
        state.onTurnStarted(1L);

        String thinking = ViewScreen.of(view, 80);
        assertFalse(thinking.contains("缓存命中"), thinking);
        assertFalse(thinking.contains("·  ·"), thinking);

        state.onToolStarted(1L, "Read", "{\"filePath\":\"/tmp/a\"}");
        String running = ViewScreen.of(view, 80);
        assertFalse(running.contains("缓存命中"), running);
        assertFalse(running.contains("·  ·"), running);
    }

    private static CodeTuiView view(ConversationState state, Path root, ContextStats stats) {
        return view(state, root, stats, PermissionMode.DEFAULT);
    }

    private static CodeTuiView view(ConversationState state, Path root, ContextStats stats, PermissionMode mode) {
        return new CodeTuiView(state, new Stub(stats, mode), root);
    }

    private static ContextStats statsWithCacheHit() {
        return new ContextStats(100, 40, 50, 8, 2, 155_184L, 100_000L, 200_000L,
                20, 10, 0, 0L, 120_832L, 155_184L, 78);
    }

    private static ContextStats statsWithoutCacheHit() {
        return new ContextStats(100, 40, 50, 8, 2, 155_184L, 100_000L, 200_000L,
                20, 10, 0, 0L, 0L, 0L, null);
    }
}

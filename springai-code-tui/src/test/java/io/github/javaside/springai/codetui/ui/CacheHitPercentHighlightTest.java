package io.github.javaside.springai.codetui.ui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import io.github.javaside.springai.codetui.agent.session.ContextStats;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CacheHitPercentHighlightTest {

    private static final class Stub implements SubmitHandler {
        private final ContextStats stats;

        private Stub(ContextStats stats) {
            this.stats = stats;
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
    }

    @Test
    void idleHighlightsOnlyCacheHitValue(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView view = view(state, root);
        state.onUserMessage(1L, "hello");
        view.ctxUsageForTest().refresh();

        assertCacheHitStyle(ViewScreen.bufferOf(view, 80), Theme.HINT);
    }

    @Test
    void thinkingHighlightsOnlyCacheHitValue(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView view = view(state, root);
        state.onUserMessage(1L, "hello");
        view.ctxUsageForTest().refresh();
        state.onTurnStarted(1L);

        assertCacheHitStyle(ViewScreen.bufferOf(view, 80), Theme.DIM);
    }

    @Test
    void runningToolHighlightsOnlyCacheHitValue(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView view = view(state, root);
        state.onUserMessage(1L, "hello");
        view.ctxUsageForTest().refresh();
        state.onTurnStarted(1L);
        state.onToolStarted(1L, "Read", "{\"filePath\":\"/tmp/a\"}");

        assertCacheHitStyle(ViewScreen.bufferOf(view, 80), Theme.DIM);
    }

    private static void assertCacheHitStyle(Buffer buffer, Style expectedLabelStyle) {
        assertEquals(expectedLabelStyle, firstCellOfSubstring(buffer, "缓存命中").style());
        Cell value = cellAtSubstringOffset(buffer, "缓存命中 78%", "缓存命中 ".length());
        assertEquals(Color.indexed(115), value.style().fg().orElseThrow());
        assertTrue(value.style().effectiveModifiers().contains(Modifier.BOLD));
    }

    private static Cell firstCellOfSubstring(Buffer buffer, String needle) {
        return cellAtSubstringOffset(buffer, needle, 0);
    }

    private static Cell cellAtSubstringOffset(Buffer buffer, String needle, int offset) {
        for (int y = 0; y < buffer.height(); y++) {
            StringBuilder row = new StringBuilder();
            List<Integer> columns = new ArrayList<>();
            for (int x = 0; x < buffer.width(); x++) {
                Cell cell = buffer.get(x, y);
                if (cell.isContinuation()) continue;
                String symbol = cell.symbol().isEmpty() ? " " : cell.symbol();
                row.append(symbol);
                for (int i = 0; i < symbol.length(); i++) columns.add(x);
            }
            int start = row.indexOf(needle);
            if (start >= 0) return buffer.get(columns.get(start + offset), y);
        }
        fail("屏幕中找不到文本：" + needle);
        throw new AssertionError("unreachable");
    }

    private static CodeTuiView view(ConversationState state, Path root) {
        return new CodeTuiView(state, new Stub(statsWithCacheHit()), root);
    }

    private static ContextStats statsWithCacheHit() {
        return new ContextStats(100, 40, 50, 8, 2, 155_184L, 100_000L, 200_000L,
                20, 10, 0, 0L, 120_832L, 155_184L, 78);
    }
}

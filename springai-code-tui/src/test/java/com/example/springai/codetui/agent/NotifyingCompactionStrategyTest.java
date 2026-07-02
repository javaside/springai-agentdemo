package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NotifyingCompactionStrategy：成功路径发 started→finished（转发 result 的计数），失败路径发 started→failed 并重抛。 */
class NotifyingCompactionStrategyTest {

    /** 只记录压缩三事件的最小 listener，其余接缝方法留空。 */
    private static final class CapturingListener implements AgentListener {
        String startedReason;
        int finishedRemoved = -1, finishedSaved = -1;
        String failedMessage;
        @Override public void onTurnStarted(long turnId) { }
        @Override public void onUserMessage(long turnId, String text) { }
        @Override public void onAssistantToken(long turnId, String token) { }
        @Override public void onToolStarted(long turnId, String toolName, String input) { }
        @Override public void onToolFinished(long turnId, String toolName, String output, boolean ok) { }
        @Override public void onTodoUpdated(long turnId, List<String> todoLines) { }
        @Override public void onTurnComplete(long turnId) { }
        @Override public void onError(long turnId, Throwable error) { }
        @Override public void onCompactionStarted(String reason) { startedReason = reason; }
        @Override public void onCompactionFinished(int eventsRemoved, int tokensSaved) {
            finishedRemoved = eventsRemoved; finishedSaved = tokensSaved;
        }
        @Override public void onCompactionFailed(String message) { failedMessage = message; }
    }

    @Test
    void success_firesStartedThenFinished_withResultCounts() {
        CapturingListener listener = new CapturingListener();
        // archivedEvents 为空 → eventsRemoved()==0；tokensEstimatedSaved==1234
        CompactionResult result = new CompactionResult(List.of(), List.of(), 1234);
        AtomicReference<CompactionRequest> seen = new AtomicReference<>();
        CompactionStrategy spyDelegate = req -> { seen.set(req); return result; };

        var notifying = new NotifyingCompactionStrategy(spyDelegate, listener, "manual");
        CompactionResult out = notifying.compact(null);   // spyDelegate 忽略入参，可传 null

        assertEquals(result, out, "应透传 delegate 的结果");
        assertEquals("manual", listener.startedReason);
        assertEquals(result.eventsRemoved(), listener.finishedRemoved, "转发 eventsRemoved");
        assertEquals(1234, listener.finishedSaved, "转发 tokensEstimatedSaved");
        assertNull(listener.failedMessage, "成功路径不应发 failed（与 finished 互斥）");
    }

    @Test
    void failure_firesStartedThenFailed_andRethrows() {
        CapturingListener listener = new CapturingListener();
        CompactionStrategy delegate = req -> { throw new RuntimeException("boom"); };

        var notifying = new NotifyingCompactionStrategy(delegate, listener, "auto");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> notifying.compact(null));
        assertEquals("boom", ex.getMessage(), "异常应重抛");
        assertEquals("auto", listener.startedReason, "失败前应已发 started");
        assertTrue(listener.failedMessage.contains("boom"), "应发 failed 且含原因");
        assertEquals(-1, listener.finishedRemoved, "失败路径不应发 finished（与 failed 互斥）");
    }
}

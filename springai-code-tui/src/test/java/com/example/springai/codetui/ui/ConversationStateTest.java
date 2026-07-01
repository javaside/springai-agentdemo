package com.example.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ConversationState 的并发/取消过滤/状态机 行为断言（Task 4 TDD）。 */
class ConversationStateTest {

    /** 1. 并发无异常 + 一致快照 + token 合并成一行（数量守恒）。 */
    @Test
    void concurrentTokens_noException_andCoalesceIntoOneLine() throws Exception {
        ConversationState state = new ConversationState();
        long turn = 1L;
        state.onTurnStarted(turn);

        int threads = 8;
        int perThread = 500;
        String token = "x";
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicBoolean failed = new AtomicBoolean(false);

        for (int i = 0; i < threads; i++) {
            Thread writer = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        state.onAssistantToken(turn, token);
                    }
                } catch (Throwable t) {
                    failed.set(true);
                } finally {
                    done.countDown();
                }
            });
            writer.start();
        }

        // 主线程边写边读快照：不得抛 ConcurrentModificationException
        Thread reader = new Thread(() -> {
            try {
                start.await();
                while (done.getCount() > 0) {
                    List<String> snap = state.transcriptSnapshot();
                    // 遍历快照，若底层被并发修改会暴露问题
                    for (String s : snap) {
                        s.length();
                    }
                }
            } catch (Throwable t) {
                failed.set(true);
            }
        });
        reader.start();

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "writers should finish");
        reader.join(5000);

        assertFalse(failed.get(), "no exception during concurrent read/write");

        // token 合并成一行：找到 AI 行，其内容长度 == 写入的 token 总数
        List<String> snap = state.transcriptSnapshot();
        long aiLines = snap.stream().filter(l -> l.startsWith("AI> ")).count();
        assertEquals(1, aiLines, "所有 token 应合并到唯一一行 AI 行");
        String aiLine = snap.stream().filter(l -> l.startsWith("AI> ")).findFirst().orElseThrow();
        String content = aiLine.substring("AI> ".length());
        assertEquals(threads * perThread, content.length(), "token 数量守恒");
    }

    /** 2. 取消后同回合的迟到 token 被丢弃。 */
    @Test
    void lateTokenAfterCancel_isDropped() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        state.onAssistantToken(1L, "abc");
        String before = assistantContent(state);
        assertEquals("abc", before);

        state.cancelCurrent();  // acceptingTurnId = -1

        state.onAssistantToken(1L, "DEF");  // 迟到，应被丢弃
        String after = assistantContent(state);
        assertEquals("abc", after, "取消后同回合迟到 token 不应增长内容");
    }

    /** 2b. 切换到新回合后，旧回合迟到 token 被丢弃。 */
    @Test
    void lateTokenAfterSwitchTurn_isDropped() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        state.onAssistantToken(1L, "old");
        state.onTurnStarted(2L);            // 切走
        state.onAssistantToken(1L, "late"); // 旧回合迟到，丢弃
        state.onAssistantToken(2L, "new");  // 新回合正常

        List<String> snap = state.transcriptSnapshot();
        assertTrue(snap.stream().anyMatch(l -> l.equals("AI> old")), "旧回合 old 行保留");
        assertTrue(snap.stream().anyMatch(l -> l.equals("AI> new")), "新回合 new 行记录");
        assertTrue(snap.stream().noneMatch(l -> l.contains("late")), "旧回合迟到 token 丢弃");
    }

    /** 3. 运行态：ToolStarted→RUNNING_TOOL；ToolFinished→THINKING；TurnComplete→IDLE。 */
    @Test
    void statusMachine_tools_andComplete() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        assertEquals(ConversationState.Status.THINKING, state.status());

        state.onToolStarted(1L, "read", "file.txt");
        assertEquals(ConversationState.Status.RUNNING_TOOL, state.status());

        state.onToolFinished(1L, "read", "ok", true);
        assertEquals(ConversationState.Status.THINKING, state.status());

        state.onTurnComplete(1L);
        assertEquals(ConversationState.Status.IDLE, state.status());
    }

    /** 4. 单飞判据：初始 idle；start 后非 idle；complete/error/cancel 后回 idle。 */
    @Test
    void singleFlight_isIdleTransitions() {
        ConversationState complete = new ConversationState();
        assertTrue(complete.isIdle());
        complete.onTurnStarted(1L);
        assertFalse(complete.isIdle());
        complete.onTurnComplete(1L);
        assertTrue(complete.isIdle());

        ConversationState err = new ConversationState();
        err.onTurnStarted(1L);
        assertFalse(err.isIdle());
        err.onError(1L, new RuntimeException("boom"));
        assertTrue(err.isIdle());

        ConversationState cancel = new ConversationState();
        cancel.onTurnStarted(1L);
        assertFalse(cancel.isIdle());
        cancel.cancelCurrent();
        assertTrue(cancel.isIdle());
    }

    /** onUserMessage 落 transcript；onTodoUpdated 替换快照。 */
    @Test
    void userMessage_andTodoSnapshot() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        state.onUserMessage(1L, "hello");
        assertTrue(state.transcriptSnapshot().stream().anyMatch(l -> l.equals("你> hello")));

        state.onTodoUpdated(1L, List.of("[ ] a", "[x] b"));
        assertEquals(List.of("[ ] a", "[x] b"), state.todoSnapshot());
        // 再次更新替换而非追加
        state.onTodoUpdated(1L, List.of("[x] c"));
        assertEquals(List.of("[x] c"), state.todoSnapshot());
    }

    /** 取在建助手行的内容（去掉 "AI> " 前缀）；无则空串。 */
    private static String assistantContent(ConversationState state) {
        return state.transcriptSnapshot().stream()
                .filter(l -> l.startsWith("AI> "))
                .map(l -> l.substring("AI> ".length()))
                .findFirst()
                .orElse("");
    }

    /** 预存的里程碑1 方法仍可用（回归保护）。 */
    @Test
    void milestone1_methodsPreserved() {
        ConversationState state = new ConversationState();
        state.typeString("ab");
        state.typeChar('c');
        assertEquals("abc", state.currentInput());
        state.backspace();
        assertEquals("ab", state.currentInput());
        state.setNotice("hi");
        assertEquals("hi", state.notice());
        assertEquals("ab", state.takeInput());
        assertEquals("", state.notice());
        state.appendLine("line1");
        assertTrue(state.transcriptSnapshot().contains("line1"));
    }
}

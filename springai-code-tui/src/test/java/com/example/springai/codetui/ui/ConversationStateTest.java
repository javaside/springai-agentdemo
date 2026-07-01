package com.example.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ConversationState 行内滚动模型的并发/取消过滤/状态机 行为断言（Claude Code 式）。 */
class ConversationStateTest {

    /** 1. 并发写在建助手行无异常 + 一致读 + 完成后定稿成一行（数量守恒）。 */
    @Test
    void concurrentTokens_noException_flushToOnePendingLine() throws Exception {
        ConversationState state = new ConversationState();
        long turn = 1L;
        state.onTurnStarted(turn);

        int threads = 8, perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicBoolean failed = new AtomicBoolean(false);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) state.onAssistantToken(turn, "x");
                } catch (Throwable t) {
                    failed.set(true);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        Thread reader = new Thread(() -> {
            try {
                start.await();
                while (done.getCount() > 0) state.streaming().length();   // 并发只读，不得抛异常
            } catch (Throwable t) {
                failed.set(true);
            }
        });
        reader.start();

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "writers should finish");
        reader.join(5000);
        assertFalse(failed.get(), "no exception during concurrent read/write");

        assertEquals(threads * perThread, state.streaming().length(), "在建行累积所有 token");
        state.onTurnComplete(turn);                       // 定稿 → 进 pending
        assertEquals("", state.streaming(), "完成后在建行清空");
        List<String> drained = state.drainPending();
        assertEquals(1, drained.size(), "定稿成唯一一行");
        assertEquals(threads * perThread, drained.get(0).length(), "token 数量守恒");
    }

    /** 2. 取消：在建行定稿进 pending，之后同回合迟到 token 被丢弃。 */
    @Test
    void cancel_flushesPartial_thenDropsLateTokens() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        state.onAssistantToken(1L, "abc");

        state.cancelCurrent();                            // 定稿 "abc" + acceptingTurnId=-1
        assertEquals("", state.streaming());
        assertEquals(List.of("abc"), state.drainPending(), "取消把已产出的部分定稿");

        state.onAssistantToken(1L, "DEF");                // 迟到，丢弃
        assertEquals("", state.streaming());
        assertTrue(state.drainPending().isEmpty(), "取消后迟到 token 不产生输出");
    }

    /** 2b. 切到新回合后，旧回合迟到 token 被丢弃，新回合正常。 */
    @Test
    void switchTurn_dropsOldLateTokens() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        state.onAssistantToken(1L, "old");
        state.onTurnComplete(1L);                         // 定稿 old
        state.onTurnStarted(2L);
        state.onAssistantToken(1L, "late");               // 旧回合迟到，丢弃
        state.onAssistantToken(2L, "new");
        state.onTurnComplete(2L);

        List<String> drained = state.drainPending();
        assertTrue(drained.contains("old"), "旧回合 old 定稿");
        assertTrue(drained.contains("new"), "新回合 new 定稿");
        assertTrue(drained.stream().noneMatch(l -> l.contains("late")), "旧回合迟到 token 丢弃");
    }

    /** 3. 状态机：ToolStarted→RUNNING_TOOL；ToolFinished→THINKING；TurnComplete→IDLE。 */
    @Test
    void statusMachine_tools_andComplete() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        assertEquals(ConversationState.Status.THINKING, state.status());
        state.onToolStarted(1L, "read", "file.txt");
        assertEquals(ConversationState.Status.RUNNING_TOOL, state.status());
        assertEquals("read", state.activeTool());
        state.onToolFinished(1L, "read", "ok", true);
        assertEquals(ConversationState.Status.THINKING, state.status());
        state.onTurnComplete(1L);
        assertEquals(ConversationState.Status.IDLE, state.status());
    }

    /** 4. 单飞判据：初始 idle；start 后非 idle；complete/error/cancel 后回 idle。 */
    @Test
    void singleFlight_isIdleTransitions() {
        ConversationState c = new ConversationState();
        assertTrue(c.isIdle());
        c.onTurnStarted(1L);
        assertFalse(c.isIdle());
        c.onTurnComplete(1L);
        assertTrue(c.isIdle());

        ConversationState e = new ConversationState();
        e.onTurnStarted(1L);
        e.onError(1L, new RuntimeException("boom"));
        assertTrue(e.isIdle());

        ConversationState x = new ConversationState();
        x.onTurnStarted(1L);
        x.cancelCurrent();
        assertTrue(x.isIdle());
    }

    /** onUserMessage / onToolFinished / onTodoUpdated / onError 都进 pending（滚入 scrollback）。 */
    @Test
    void finalizedLines_goToPending() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        state.onUserMessage(1L, "hello");
        state.onToolStarted(1L, "grep", "foo");
        state.onToolFinished(1L, "grep", "out", true);
        state.onTodoUpdated(1L, List.of("[ ] a", "[x] b"));
        state.onError(1L, new RuntimeException("bad"));

        List<String> p = state.drainPending();
        assertTrue(p.stream().anyMatch(l -> l.contains("hello")), "用户行进 pending");
        assertTrue(p.stream().anyMatch(l -> l.equals("🛠 grep ✓")), "工具完成行进 pending");
        assertTrue(p.stream().anyMatch(l -> l.contains("计划")), "todo 进 pending");
        assertTrue(p.stream().anyMatch(l -> l.contains("bad")), "错误进 pending");
    }

    /** 里程碑1 输入方法仍可用（回归保护）。 */
    @Test
    void inputMethodsPreserved() {
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
    }
}

package com.example.springai.codetui.ui;

import com.example.springai.codetui.ui.ConversationState.OutputLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ConversationState 行内滚动模型的并发/取消过滤/状态机/分类 行为断言。 */
class ConversationStateTest {

    private static List<String> texts(List<OutputLine> lines) {
        return lines.stream().map(OutputLine::text).toList();
    }

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
                while (done.getCount() > 0) state.streaming().length();
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
        state.onTurnComplete(turn);
        assertEquals("", state.streaming(), "完成后在建行清空");
        List<OutputLine> drained = state.drainPending();
        assertEquals(1, drained.size(), "定稿成唯一一行");
        assertEquals(threads * perThread, drained.get(0).text().length(), "token 数量守恒");
        assertEquals(OutputLine.Kind.ASSISTANT, drained.get(0).kind(), "助手正文类型");
    }

    /** 2. 取消：在建行定稿进 pending，之后同回合迟到 token 被丢弃。 */
    @Test
    void cancel_flushesPartial_thenDropsLateTokens() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        state.onAssistantToken(1L, "abc");

        state.cancelCurrent();
        assertEquals("", state.streaming());
        assertEquals(List.of("abc"), texts(state.drainPending()), "取消把已产出的部分定稿");

        state.onAssistantToken(1L, "DEF");
        assertEquals("", state.streaming());
        assertTrue(state.drainPending().isEmpty(), "取消后迟到 token 不产生输出");
    }

    /** 2b. 切到新回合后，旧回合迟到 token 被丢弃，新回合正常。 */
    @Test
    void switchTurn_dropsOldLateTokens() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        state.onAssistantToken(1L, "old");
        state.onTurnComplete(1L);
        state.onTurnStarted(2L);
        state.onAssistantToken(1L, "late");
        state.onAssistantToken(2L, "new");
        state.onTurnComplete(2L);

        List<String> drained = texts(state.drainPending());
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

    /** 各类定稿行进 pending 且带正确类型（供 UI 分色）。 */
    @Test
    void finalizedLines_goToPending_withKinds() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        state.onUserMessage(1L, "hello");
        state.onToolStarted(1L, "grep", "foo");
        state.onToolFinished(1L, "grep", "out", true);
        state.onTodoUpdated(1L, List.of("a", "b"));
        state.onError(1L, new RuntimeException("bad"));

        List<OutputLine> p = state.drainPending();
        assertTrue(p.stream().anyMatch(l -> l.kind() == OutputLine.Kind.USER && l.text().contains("hello")));
        assertTrue(p.stream().anyMatch(l -> l.kind() == OutputLine.Kind.TOOL_START && l.text().contains("grep") && l.text().contains("foo")));
        assertTrue(p.stream().anyMatch(l -> l.kind() == OutputLine.Kind.TOOL_OK && l.text().contains("grep")));
        assertTrue(p.stream().anyMatch(l -> l.kind() == OutputLine.Kind.ERROR && l.text().contains("bad")));
        assertEquals(List.of("a", "b"), state.todoSnapshot(), "todo 进固定面板而非 scrollback");
    }

    /** 流式按真实换行下沉：遇到 \n 的完整逻辑行下沉 scrollback，只留最后未换行残行。 */
    @Test
    void takeCompleteStreamingLines_splitsOnNewline_keepsPartial() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        state.onAssistantToken(1L, "L1\nL2\npart");
        assertEquals(List.of("L1", "L2"), state.takeCompleteStreamingLines(), "换行完整行下沉");
        assertEquals("part", state.streaming(), "未换行残行留在 live 区");
        assertTrue(state.takeCompleteStreamingLines().isEmpty(), "无新换行则不下沉");
        state.onTurnComplete(1L);
        assertEquals(List.of("part"), texts(state.drainPending()), "完成时残行定稿");
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

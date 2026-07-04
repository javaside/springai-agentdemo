package com.example.springai.codetui.ui;

import com.example.springai.codetui.ui.ConversationState.OutputLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void compaction_started_setsFlagAndReason() {
        ConversationState s = new ConversationState();
        assertFalse(s.isCompacting(), "初始不在压缩");

        s.onCompactionStarted("manual");

        assertTrue(s.isCompacting(), "started 后应处于压缩中");
        assertEquals("manual", s.compactReason());
        assertTrue(s.compactElapsedNanos() >= 0, "经过时间应可读且非负");
    }

    @Test
    void compaction_finished_clearsFlagAndPushesSummaryLine() {
        ConversationState s = new ConversationState();
        s.onCompactionStarted("auto");

        s.onCompactionFinished(7, 1234);

        assertFalse(s.isCompacting(), "finished 后应退出压缩中");
        assertTrue(s.drainPending().stream().anyMatch(l -> l.text().contains("7") && l.text().contains("1234")),
                "完成行应含移除事件数与节省 token");
    }

    @Test
    void compaction_finished_zeroRemoved_pushesNothingToCompactLine() {
        ConversationState s = new ConversationState();
        s.onCompactionStarted("manual");

        s.onCompactionFinished(0, 0);

        assertFalse(s.isCompacting());
        assertTrue(s.drainPending().stream().anyMatch(l -> l.text().contains("无可压缩")),
                "0 移除应提示无可压缩");
    }

    @Test
    void compaction_failed_clearsFlagAndPushesErrorLine() {
        ConversationState s = new ConversationState();
        s.onCompactionStarted("manual");

        s.onCompactionFailed("boom");

        assertFalse(s.isCompacting(), "failed 后应退出压缩中");
        assertTrue(s.drainPending().stream().anyMatch(l ->
                        l.text().contains("boom") && l.kind() == ConversationState.OutputLine.Kind.ERROR),
                "失败行应含原因且为 ERROR 类型");
    }

    @Test
    void compactElapsedNanos_isZero_whenNotCompacting() {
        ConversationState s = new ConversationState();
        assertEquals(0L, s.compactElapsedNanos(), "未压缩时经过时间应为 0");

        s.onCompactionStarted("manual");
        s.onCompactionFinished(1, 1);
        assertEquals(0L, s.compactElapsedNanos(), "压缩结束后经过时间应回到 0");
    }

    @Test
    void subagentEvents_produceNestedOutputLines() {
        ConversationState st = new ConversationState();
        st.onTurnStarted(1L);
        st.onSubagentStarted(1L, "task_1", "explore", "分析认证模块");
        st.onToolStarted(1L, "task_1", "Grep", "{\"pattern\":\"auth\"}");   // 4-arg (taskId) overload
        st.onSubagentFinished(1L, "task_1", "认证走 JWT\n（更多细节）");
        List<ConversationState.OutputLine> out = st.drainPending();
        // 找到三类子 agent 行
        assertTrue(out.stream().anyMatch(o -> o.kind() == ConversationState.OutputLine.Kind.SUBAGENT_START
                && o.text().contains("Task(explore)")));
        assertTrue(out.stream().anyMatch(o -> o.kind() == ConversationState.OutputLine.Kind.SUBAGENT_TOOL
                && o.text().contains("Grep")));
        assertTrue(out.stream().anyMatch(o -> o.kind() == ConversationState.OutputLine.Kind.SUBAGENT_END
                && o.text().contains("认证走 JWT") && !o.text().contains("更多细节")));  // 只取首行
    }

    @Test
    void mainFlowTool_withNullTaskId_usesNormalPath() {
        ConversationState st = new ConversationState();
        st.onTurnStarted(1L);
        st.onToolStarted(1L, null, "Bash", "{\"cmd\":\"ls\"}");   // taskId=null → 委托主流
        List<ConversationState.OutputLine> out = st.drainPending();
        assertTrue(out.stream().anyMatch(o -> o.kind() == ConversationState.OutputLine.Kind.TOOL_START
                && o.text().contains("Bash")));
    }

    @Test
    void isBusy_trueWhenTurnActive_orCompacting_elseFalse() {
        ConversationState s = new ConversationState();
        assertFalse(s.isBusy(), "初始空闲、未压缩：不忙");

        s.onTurnStarted(1L);
        assertTrue(s.isBusy(), "回合进行中：忙");
        s.onTurnComplete(1L);
        assertFalse(s.isBusy(), "回合结束：不忙");

        s.onCompactionStarted("manual");
        assertTrue(s.isBusy(), "压缩中：忙（即便无活跃回合）");
        s.onCompactionFinished(1, 1);
        assertFalse(s.isBusy(), "压缩结束：不忙");
    }

    // ── 两层 todo 分流：任务面板（控制器计划，taskId==null）vs todo 面板（当前子 agent，taskId!=null） ──

    @Test
    void taskPanel_controllerTodo_goesToTaskPanel() {
        // 控制器计划（taskId==null）进任务面板：开发计划进度
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onTodoUpdated(1L, null, List.of("▶ 任务1", "○ 任务2", "○ 任务3"));
        assertEquals(List.of("▶ 任务1", "○ 任务2", "○ 任务3"), s.todoSnapshot(), "控制器计划进任务面板");
        assertNull(s.subAgentTodoSnapshot(), "无在跑子 agent：todo 面板收起");
    }

    @Test
    void subAgentTodo_goesToTodoPanel_notTaskPanel() {
        // 子 agent 内部 todo（taskId!=null）进 todo 面板，且不覆盖任务面板
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onTodoUpdated(1L, null, List.of("▶ 任务1", "○ 任务2"));      // 控制器计划
        s.onSubagentStarted(1L, "t1", "implementer", "实现任务1");
        s.onTodoUpdated(1L, "t1", List.of("✓ 写测试", "▶ 实现"));      // 子 agent 内部 todo
        assertEquals(List.of("▶ 任务1", "○ 任务2"), s.todoSnapshot(), "子 agent todo 不得覆盖任务面板");
        ConversationState.SubAgentTodo sub = s.subAgentTodoSnapshot();
        assertNotNull(sub, "有在跑子 agent 且有 todo：todo 面板显示");
        assertEquals("implementer", sub.agentName());
        assertEquals(List.of("✓ 写测试", "▶ 实现"), sub.lines());
    }

    @Test
    void subAgentTodo_clearedWhenSubagentFinishes() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "implementer", "d");
        s.onTodoUpdated(1L, "t1", List.of("▶ 实现"));
        assertNotNull(s.subAgentTodoSnapshot(), "运行中有 todo 面板");
        s.onSubagentFinished(1L, "t1", "done", true);
        assertNull(s.subAgentTodoSnapshot(), "子 agent 结束：todo 面板收起");
    }

    @Test
    void subAgentTodo_switchesToNextSubagent() {
        // 下一个子 agent 开始：todo 面板切到它、清空上一个的 todo
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "implementer", "d1");
        s.onTodoUpdated(1L, "t1", List.of("✓ 全部完成"));
        s.onSubagentFinished(1L, "t1", "done", true);
        s.onSubagentStarted(1L, "t2", "reviewer", "d2");
        assertNull(s.subAgentTodoSnapshot(), "新子 agent 尚未产生 todo：面板暂收起");
        s.onTodoUpdated(1L, "t2", List.of("▶ 审查"));
        ConversationState.SubAgentTodo sub = s.subAgentTodoSnapshot();
        assertNotNull(sub);
        assertEquals("reviewer", sub.agentName(), "切到下一个子 agent");
        assertEquals(List.of("▶ 审查"), sub.lines(), "不带上一个子 agent 的 todo");
    }

    @Test
    void taskPanel_turnStart_clearsBothPanels() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onTodoUpdated(1L, null, List.of("▶ 任务1"));
        s.onSubagentStarted(1L, "t1", "implementer", "d");
        s.onTodoUpdated(1L, "t1", List.of("▶ 实现"));
        s.onTurnStarted(2L);
        assertTrue(s.todoSnapshot().isEmpty(), "新回合清空任务面板");
        assertNull(s.subAgentTodoSnapshot(), "新回合清空 todo 面板");
    }

    @Test
    void subagentEvents_stillCoexistWithScrollbackLines() {
        // 回归：面板机制变了，但 scrollback 内联详情行不受影响（共存形态）
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "explore", "分析认证");
        s.onToolStarted(1L, "t1", "Grep", "{\"pattern\":\"auth\"}");
        s.onSubagentFinished(1L, "t1", "认证走 JWT", true);
        List<ConversationState.OutputLine> out = s.drainPending();
        assertTrue(out.stream().anyMatch(o -> o.kind() == ConversationState.OutputLine.Kind.SUBAGENT_START));
        assertTrue(out.stream().anyMatch(o -> o.kind() == ConversationState.OutputLine.Kind.SUBAGENT_TOOL));
        assertTrue(out.stream().anyMatch(o -> o.kind() == ConversationState.OutputLine.Kind.SUBAGENT_END));
    }
}

package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.AskResponder;
import io.github.javaside.springai.codetui.agent.seam.OptionSpec;
import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;
import io.github.javaside.springai.codetui.agent.seam.PlanOutcome;
import io.github.javaside.springai.codetui.agent.seam.PlanRequest;
import io.github.javaside.springai.codetui.agent.seam.QuestionSpec;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConversationState} 的变化通知契约（事件驱动 UI 的 Task 2）：
 *
 * <ul>
 *   <li>每类<b>有效</b> mutation 发布正确（可组合）的 dirty bits，且恰好通知一次、版本恰好 +1；</li>
 *   <li>迟到 / no-op mutation 不改版本、不通知；</li>
 *   <li>通知发生在 state 监视器<b>外</b>（listener 里可从别的线程回读 state 快照）；</li>
 *   <li>listener 抛异常被隔离，状态与版本照常推进，后续通知照常；</li>
 *   <li>模态离队路径（cancel / reset / 迟到 / 队满）在通知开启时仍<b>恰好唤醒一次</b>；</li>
 *   <li><b>先 publish、后外部应答</b>：入队失败路径在锁内提交的状态变化（队满 ERROR 行 + ALL）
 *       必须先于 responder / {@code cancel()} 调用发布——违约 responder 抛异常吞不掉通知，
 *       而异常本身照原语义上抛（生产调用方靠它失败关闭，吞掉 = 工具线程永久 park）。</li>
 * </ul>
 */
class ConversationStateNotificationTest {

    record MutationCase(String name, int expectedBits, StateMutation setup, StateMutation mutation) {
        MutationCase(String name, int expectedBits, StateMutation mutation) {
            this(name, expectedBits, state -> { }, mutation);
        }
        @Override public String toString() { return name; }
    }

    @FunctionalInterface
    interface StateMutation { void apply(ConversationState state); }

    static Stream<MutationCase> mutations() {
        int outputView = UiDirty.OUTPUT | UiDirty.VIEW;
        int viewControl = UiDirty.VIEW | UiDirty.CONTROL;
        int all = UiDirty.ALL;
        return Stream.of(
                // ── OUTPUT | VIEW：scrollback 有新存量 ──
                new MutationCase("pushInfo", outputView, s -> s.pushInfo("info")),
                new MutationCase("replayHistory", outputView,
                        s -> s.replayHistory(history())),
                new MutationCase("user message", outputView,
                        s -> s.onTurnStarted(1), s -> s.onUserMessage(1, "hi")),
                new MutationCase("assistant token", outputView,
                        s -> s.onTurnStarted(1), s -> s.onAssistantToken(1, "x")),
                new MutationCase("guardrail bypass", outputView,
                        s -> s.onGuardrailBypassed(1, "写入 .git/ 内部")),
                new MutationCase("rule recorded", outputView,
                        s -> s.onTurnStarted(1), s -> s.onRuleRecorded(1, true, "已记录")),
                new MutationCase("mcp ready with tools", outputView, s -> s.onMcpReady(2, 5)),
                new MutationCase("subagent started", outputView,
                        s -> s.onTurnStarted(1), s -> s.onSubagentStarted(1, "t1", "explore", "调查")),
                new MutationCase("subagent finished", outputView,
                        s -> { s.onTurnStarted(1); s.onSubagentStarted(1, "t1", "explore", "d"); },
                        s -> s.onSubagentFinished(1, "t1", "结论", true)),
                new MutationCase("subagent tool line", outputView,
                        s -> { s.onTurnStarted(1); s.onSubagentStarted(1, "t1", "explore", "d"); },
                        s -> s.onToolStarted(1, "t1", "Grep", "{}")),
                new MutationCase("subagent tool failure line", outputView,
                        s -> { s.onTurnStarted(1); s.onSubagentStarted(1, "t1", "explore", "d"); },
                        s -> s.onToolFinished(1, "t1", "Grep", "out", false)),
                // 迟到回合的 BYPASS 账也得能出汇总（flushGuardrailBypasses 在迟到过滤之前）
                new MutationCase("late turn complete flushes bypass summary", outputView,
                        s -> s.onGuardrailBypassed(1, "写入 .git/ 内部：a"),
                        s -> s.onTurnComplete(1)),

                // ── VIEW：live 区快照变化 ──
                new MutationCase("controller todo", UiDirty.VIEW,
                        s -> s.onTurnStarted(1), s -> s.onTodoUpdated(1, List.of("todo"))),
                new MutationCase("background panel tool update", UiDirty.VIEW,
                        s -> s.onBackgroundTaskStarted("task", "explore", "调查"),
                        s -> s.onToolStarted(-1, "task", "Grep", "{}")),
                new MutationCase("background killed", UiDirty.VIEW,
                        s -> s.onBackgroundTaskStarted("task", "explore", "调查"),
                        s -> s.markBackgroundKilled("task")),
                new MutationCase("notice real change", UiDirty.VIEW, s -> s.setNotice("notice")),
                new MutationCase("compaction started", viewControl, s -> s.onCompactionStarted("auto")),

                // ── VIEW | CONTROL：队列 / 模态 ──
                new MutationCase("queue add", viewControl, s -> s.enqueue("q", null)),
                new MutationCase("queue poll", viewControl, s -> s.enqueue("q", null), ConversationState::pollQueued),
                new MutationCase("queue clear", viewControl, s -> s.enqueue("q", null), ConversationState::clearQueued),
                new MutationCase("modal add (permission)", viewControl,
                        s -> s.onTurnStarted(1), s -> s.onPermissionRequested(1, permission(1, new CopyOnWriteArrayList<>()))),
                new MutationCase("modal add (question)", viewControl,
                        s -> s.onTurnStarted(1), s -> s.onQuestionAsked(1, ask(1, new CopyOnWriteArrayList<>()))),
                new MutationCase("modal add (plan)", viewControl,
                        s -> s.onTurnStarted(1), s -> s.onPlanSubmitted(1, plan(1, new CopyOnWriteArrayList<>()))),
                new MutationCase("modal remove", viewControl,
                        s -> { s.onTurnStarted(1); s.onPermissionRequested(1, permission(1, new CopyOnWriteArrayList<>())); },
                        s -> s.removeModal(s.peekModal())),
                new MutationCase("modal clear", viewControl,
                        s -> { s.onTurnStarted(1); s.onPermissionRequested(1, permission(1, new CopyOnWriteArrayList<>())); },
                        ConversationState::clearModals),

                // ── OUTPUT | VIEW | CONTROL：回合 / 工具 / 后台结果 ──
                new MutationCase("turn started", all, s -> s.onTurnStarted(1)),
                new MutationCase("turn complete", all, s -> s.onTurnStarted(1), s -> s.onTurnComplete(1)),
                new MutationCase("turn error", all,
                        s -> s.onTurnStarted(1), s -> s.onError(1, new RuntimeException("x"))),
                new MutationCase("tool started", all,
                        s -> s.onTurnStarted(1), s -> s.onToolStarted(1, "Bash", "{}")),
                new MutationCase("tool finished", all,
                        s -> s.onTurnStarted(1), s -> s.onToolFinished(1, "Bash", "", true)),
                new MutationCase("background start", all, s -> s.onBackgroundTaskStarted("task", "agent", "desc")),
                new MutationCase("background finish", all,
                        s -> s.onBackgroundTaskStarted("task", "agent", "desc"),
                        s -> s.onBackgroundTaskFinished("task", "done", true)),
                new MutationCase("compaction finished", all,
                        s -> s.onCompactionStarted("auto"), s -> s.onCompactionFinished(7, 123)),
                new MutationCase("compaction failed", all,
                        s -> s.onCompactionStarted("auto"), s -> s.onCompactionFailed("boom")),

                // ── 复合路径：合并成一次通知，而不是嵌套 helper 各发一次 ──
                new MutationCase("cancel current flushes streaming", all,
                        s -> { s.onTurnStarted(1); s.onAssistantToken(1, "partial"); },
                        ConversationState::cancelCurrent),
                new MutationCase("reset for new session", all,
                        s -> { s.onTurnStarted(1); s.pushInfo("x"); s.onTodoUpdated(1, List.of("t")); s.enqueue("q", null); },
                        ConversationState::resetForNewSession)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    void effectiveMutationsPublishExpectedDirtyBitsExactlyOnce(MutationCase mutationCase) {
        ConversationState state = new ConversationState();
        List<Integer> bits = new ArrayList<>();
        mutationCase.setup().apply(state);
        state.setUiChangeListener(bits::add);
        long before = state.uiVersion();

        mutationCase.mutation().apply(state);

        assertEquals(List.of(mutationCase.expectedBits()), bits,
                "应恰好通知一次，且 dirty bits 为预期组合");
        assertEquals(before + 1, state.uiVersion(), "有效 mutation 恰好推进一个版本");
    }

    @Test
    @DisplayName("迟到 / no-op mutation：不改版本、不通知")
    void filteredAndNoOpMutationsDoNotPublishOrAdvanceVersion() {
        ConversationState state = new ConversationState();
        // 前置在挂监听之前就位：todo 已是 same、后台任务已登记并被终止（其完成事件必须被丢弃）
        state.onTurnStarted(1);
        state.onTodoUpdated(1, List.of("same"));
        state.onSubagentStarted(1, "t1", "explore", "d");
        state.onBackgroundTaskStarted("killed-task", "explore", "d");
        assertTrue(state.markBackgroundKilled("killed-task"), "前置：任务确已被终止");
        state.setNotice("same");                    // notice 已是 same，重复设置才是 no-op

        AtomicInteger calls = new AtomicInteger();
        state.setUiChangeListener(bits -> calls.incrementAndGet());
        long before = state.uiVersion();

        // 迟到（回合外 / 未知 taskId）
        state.onAssistantToken(99, "late");
        state.onUserMessage(99, "late");
        state.onTodoUpdated(99, List.of("late"));
        state.onToolStarted(1, "ghost", "Grep", "{}");
        state.onToolFinished(1, "ghost", "Grep", "out", false);
        // 子 agent 工具成功结束：不出行
        state.onToolFinished(1, "t1", "Grep", "out", true);
        // 子 agent 内部 todo：不上面板
        state.onTodoUpdated(1, "t1", List.of("inner"));
        // todo 内容未变：no-op
        state.onTodoUpdated(1, List.of("same"));
        // 已终止的后台任务：完成事件整体丢弃（无行、无面板变化）
        state.onBackgroundTaskFinished("killed-task", "late", true);
        // 空队列 / 空模态 / 同值 notice / 空消息 / 零工具 / 空历史
        state.pollQueued();
        state.clearQueued();
        state.removeModal(permission(1, new CopyOnWriteArrayList<>()));
        state.clearModals();
        state.setNotice("same");
        state.onRuleRecorded(1, true, "");
        state.onMcpReady(1, 0);
        state.replayHistory(List.<Message>of());

        assertEquals(0, calls.get(), "no-op / 被过滤的 mutation 不得通知");
        assertEquals(before, state.uiVersion(), "no-op / 被过滤的 mutation 不得推进版本");
    }

    @Test
    @DisplayName("通知发生在 state 监视器外：listener 内起线程读同步快照必须能完成")
    void notificationRunsOutsideStateMonitor() throws Exception {
        ConversationState state = new ConversationState();
        CountDownLatch snapshotCompleted = new CountDownLatch(1);
        state.setUiChangeListener(bits -> {
            Thread reader = new Thread(() -> {
                state.todoSnapshot();               // synchronized 读：monitor 被占就永远进不来
                snapshotCompleted.countDown();
            });
            reader.start();
            try {
                assertTrue(snapshotCompleted.await(2, TimeUnit.SECONDS),
                        "listener 在 state 监视器内执行——UI 回读状态会死锁");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        state.pushInfo("wake");
        assertEquals(1, state.uiVersion());
    }

    @Test
    @DisplayName("模态排空路径的通知同样在监视器外（cancelCurrent 不经嵌套 publish 持锁）")
    void modalCancellationNotifiesOutsideStateMonitor() throws Exception {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        state.onPermissionRequested(1, permission(1, sink));
        CountDownLatch snapshotCompleted = new CountDownLatch(1);
        state.setUiChangeListener(bits -> {
            Thread reader = new Thread(() -> {
                state.submissionSnapshot();         // synchronized 读
                snapshotCompleted.countDown();
            });
            reader.start();
            try {
                assertTrue(snapshotCompleted.await(2, TimeUnit.SECONDS),
                        "cancelCurrent 的通知在 state 监视器内执行");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        state.cancelCurrent();

        assertEquals(List.of(PermissionOutcome.CANCEL), sink, "通知之外，唤醒语义不变");
    }

    @Test
    @DisplayName("listener 抛异常被隔离：版本照常推进，后续 mutation 照常通知")
    void throwingListenerIsIsolatedAndLaterMutationsStillPublish() {
        ConversationState state = new ConversationState();
        state.setUiChangeListener(bits -> { throw new IllegalStateException("boom"); });

        assertDoesNotThrow(() -> state.pushInfo("first"));
        assertEquals(1, state.uiVersion(), "listener 炸了也必须已记账");

        AtomicInteger calls = new AtomicInteger();
        state.setUiChangeListener(bits -> calls.incrementAndGet());
        assertDoesNotThrow(() -> state.pushInfo("second"));
        assertEquals(2, state.uiVersion());
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("cancelCurrent 把子变化合并成一次通知：模态 + 流式定稿 + 状态复位只发一轮")
    void cancelCurrentMergesSubChangesIntoSingleNotification() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1);
        state.onAssistantToken(1, "partial");
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        state.onPermissionRequested(1, permission(1, sink));
        List<Integer> bits = new ArrayList<>();
        state.setUiChangeListener(bits::add);
        long before = state.uiVersion();

        state.cancelCurrent();

        assertEquals(List.of(UiDirty.ALL), bits, "嵌套 helper 必须合并 bits，不得发布两次");
        assertEquals(before + 1, state.uiVersion());
        assertEquals(1, sink.size(), "模态必须恰好被唤醒一次");
        assertTrue(state.drainPending().stream().anyMatch(l -> l.text().equals("partial")),
                "取消仍要把在建行定稿进 pending");
    }

    @Test
    @DisplayName("resetForNewSession 同样合并成一次通知")
    void resetForNewSessionMergesSubChangesIntoSingleNotification() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1);
        state.pushInfo("x");
        state.onTodoUpdated(1, List.of("t"));
        state.enqueue("q", null);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        state.onPermissionRequested(1, permission(1, sink));
        List<Integer> bits = new ArrayList<>();
        state.setUiChangeListener(bits::add);
        long before = state.uiVersion();

        state.resetForNewSession();

        assertEquals(List.of(UiDirty.ALL), bits);
        assertEquals(before + 1, state.uiVersion());
        assertEquals(1, sink.size(), "/clear 仍要唤醒 pending 模态，恰好一次");
        assertTrue(state.todoSnapshot().isEmpty());
        assertEquals(0, state.queuedCount());
    }

    @Test
    @DisplayName("通知开启时，每个离队模态仍恰好被唤醒一次（cancel / reset / 迟到 / 队满）")
    void everyDequeuedModalIsAwakenedExactlyOnceWhileNotificationsFlow() {
        // cancelCurrent：两个审批 + 一个问询
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        List<String> askCancels = new CopyOnWriteArrayList<>();
        s.onPermissionRequested(1, permission(1, sink));
        s.onPermissionRequested(1, permission(1, sink));
        s.onQuestionAsked(1, ask(1, askCancels));
        s.setUiChangeListener(bits -> { });          // 通知开启，且 listener 每次 mutation 都会被打到
        s.cancelCurrent();
        assertEquals(List.of(PermissionOutcome.CANCEL, PermissionOutcome.CANCEL), sink,
                "每个审批恰好一次 CANCEL");
        assertEquals(List.of("cancelled"), askCancels, "问询恰好被取消一次");

        // resetForNewSession
        ConversationState r = new ConversationState();
        r.onTurnStarted(1);
        List<PermissionOutcome> sink2 = new CopyOnWriteArrayList<>();
        r.onPermissionRequested(1, permission(1, sink2));
        AtomicInteger notifications = new AtomicInteger();
        r.setUiChangeListener(bits -> notifications.incrementAndGet());
        r.resetForNewSession();
        assertEquals(1, sink2.size(), "/clear 恰好唤醒一次");

        // 迟到：应答一次、零通知
        ConversationState late = new ConversationState();
        late.onTurnStarted(2);
        AtomicInteger lateCalls = new AtomicInteger();
        List<PermissionOutcome> denied = new CopyOnWriteArrayList<>();
        List<String> askLate = new CopyOnWriteArrayList<>();
        List<PlanOutcome> planLate = new CopyOnWriteArrayList<>();
        late.setUiChangeListener(bits -> lateCalls.incrementAndGet());
        late.onPermissionRequested(1, permission(1, denied));
        late.onQuestionAsked(1, ask(1, askLate));
        late.onPlanSubmitted(1, plan(1, planLate));
        assertEquals(List.of(PermissionOutcome.DENY), denied, "迟到审批：DENY 恰好一次");
        assertEquals(List.of("cancelled"), askLate, "迟到问询：cancel 恰好一次");
        assertEquals(List.of(PlanOutcome.CANCEL), planLate, "迟到计划：CANCEL 恰好一次");
        assertEquals(0, lateCalls.get(), "迟到请求不得发通知");
    }

    @Test
    @DisplayName("队满溢出：留下 ERROR 行并发布，同时恰好 DENY 一次")
    void modalOverflowPublishesAndDeniesExactlyOnce() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        for (int i = 0; i < ConversationState.MODAL_QUEUE_CAP; i++) {
            s.onPermissionRequested(1, permission(1, sink));
        }
        List<Integer> bits = new ArrayList<>();
        s.setUiChangeListener(bits::add);
        long before = s.uiVersion();

        s.onPermissionRequested(1, permission(1, sink));

        assertEquals(List.of(UiDirty.ALL), bits, "溢出既改队列态势又留下用户可见的行");
        assertEquals(before + 1, s.uiVersion());
        assertEquals(List.of(PermissionOutcome.DENY), sink, "溢出者恰好 DENY 一次");
        assertTrue(s.drainPending().stream().anyMatch(l -> l.kind() == ConversationState.OutputLine.Kind.ERROR),
                "队满必须留下用户看得见的一行");
    }

    @Test
    @DisplayName("hasPendingOutput / hasCompleteStreamingLine 是非破坏性探针")
    void pendingOutputAndCompleteStreamingLineAreNonDestructive() {
        ConversationState state = new ConversationState();
        assertFalse(state.hasPendingOutput());
        assertFalse(state.hasCompleteStreamingLine());

        state.pushInfo("line");
        state.onTurnStarted(1);
        state.onAssistantToken(1, "complete\npartial");

        assertTrue(state.hasPendingOutput());
        assertTrue(state.hasCompleteStreamingLine());
        assertEquals("complete\npartial", state.streaming(), "探针不得消费缓冲");
        assertEquals(1, state.drainPending().size(), "pending 原封未动");
    }

    @Test
    @DisplayName("null listener 归一成 no-op：不抛异常、不通知")
    void nullListenerIsNormalizedToNoop() {
        ConversationState state = new ConversationState();
        state.setUiChangeListener(null);
        assertDoesNotThrow(() -> state.pushInfo("still works"));
        assertEquals(1, state.uiVersion());
        assertEquals(1, state.drainPending().size());
    }

    // ── 违约 responder（I-1 回归）：已提交的状态变化必须照常 publish ──────────

    /**
     * 队满 + responder 违约抛异常：锁内已提交的 ERROR 行 + ALL <b>必须</b>先于 {@code respond} 发布，
     * 不得被违约实现连同异常一起吞掉；ERROR 行不回滚；异常<b>照原语义</b>向调用方上抛
     * （生产方 {@code PermissionCallback} 捕获它失败关闭成 DENY——吞掉它会把工具线程 park 在 handoff 上）。
     */
    @Test
    @DisplayName("队满 + 违约 responder：publish 先于 respond，ERROR 行不回滚，异常原样上抛")
    void permissionOverflowPublishesBeforeRogueResponder() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        List<PermissionOutcome> filler = new CopyOnWriteArrayList<>();
        for (int i = 0; i < ConversationState.MODAL_QUEUE_CAP; i++) {
            s.onPermissionRequested(1, permission(1, filler));
        }
        assertTrue(filler.isEmpty(), "前置：前 8 个全部入队");
        List<String> order = new CopyOnWriteArrayList<>();       // 钉死 publish 与 respond 的先后
        List<Integer> bits = new ArrayList<>();
        s.setUiChangeListener(b -> { order.add("publish"); bits.add(b); });
        long before = s.uiVersion();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> s.onPermissionRequested(1, new PermissionRequest(1, null, "Bash", "boom", "{}",
                        "why", null, o -> { order.add("respond"); sink.add(o); throw new IllegalStateException("responder 违约抛异常"); })),
                "异常按原语义向调用方传播（与 cancelModals 的「吞掉继续」不同，此处吞掉=工具线程永久 park）");

        assertEquals("responder 违约抛异常", thrown.getMessage());
        assertEquals(List.of("publish", "respond"), order, "已提交的变化必须先 publish，再调外部 responder");
        assertEquals(List.of(UiDirty.ALL), bits, "通知不得被违约 responder 吞掉");
        assertEquals(before + 1, s.uiVersion(), "版本已记账，不因异常回滚");
        assertEquals(List.of(PermissionOutcome.DENY), sink, "违约前 DENY 已送达该 responder（异常是它自己抛的）");
        assertTrue(s.drainPending().stream().anyMatch(l -> l.kind() == ConversationState.OutputLine.Kind.ERROR),
                "队满的 ERROR 行不被回滚");
    }

    /**
     * 迟到路径 + 违约 responder：无状态变化故零通知，异常照原语义上抛——把「迟到不发布」
     * 与「异常传播」两条语义同时钉死（与上一用例的差异只在 change==null）。
     */
    @Test
    @DisplayName("迟到 + 违约 responder：零通知、零版本，异常原样上抛")
    void permissionLatePathStaysSilentAndPropagatesRogueResponder() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(2);                                      // 回合已切换，请求 1 迟到
        List<Integer> bits = new ArrayList<>();
        s.setUiChangeListener(bits::add);
        long before = s.uiVersion();

        assertThrows(IllegalStateException.class,
                () -> s.onPermissionRequested(1, new PermissionRequest(1, null, "Bash", "cmd", "{}",
                        "why", null, o -> { throw new IllegalStateException("responder 违约抛异常"); })));

        assertEquals(List.of(), bits, "迟到路径无状态变化，本就不该通知");
        assertEquals(before, s.uiVersion());
        assertNull(s.peekModal(), "迟到请求不入队");
    }

    /** 计划审批队满 + 违约 responder：同审批路径——先 publish、ERROR 行不回滚、异常原样上抛。 */
    @Test
    @DisplayName("计划队满 + 违约 responder：publish 先于 respond，ERROR 行不回滚，异常原样上抛")
    void planOverflowPublishesBeforeRogueResponder() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        List<PlanOutcome> filler = new CopyOnWriteArrayList<>();
        for (int i = 0; i < ConversationState.MODAL_QUEUE_CAP; i++) {
            s.onPlanSubmitted(1, plan(1, filler));
        }
        assertTrue(filler.isEmpty(), "前置：前 8 个全部入队");
        List<String> order = new CopyOnWriteArrayList<>();
        List<Integer> bits = new ArrayList<>();
        s.setUiChangeListener(b -> { order.add("publish"); bits.add(b); });
        long before = s.uiVersion();
        List<PlanOutcome> sink = new CopyOnWriteArrayList<>();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> s.onPlanSubmitted(1, new PlanRequest(1, "# 计划", (o, f) -> {
                    order.add("respond"); sink.add(o); throw new IllegalStateException("responder 违约抛异常");
                })));

        assertEquals("responder 违约抛异常", thrown.getMessage());
        assertEquals(List.of("publish", "respond"), order, "已提交的变化必须先 publish，再调外部 responder");
        assertEquals(List.of(UiDirty.ALL), bits);
        assertEquals(before + 1, s.uiVersion());
        assertEquals(List.of(PlanOutcome.KEEP_PLANNING), sink, "违约前 KEEP_PLANNING 已送达该 responder");
        assertTrue(s.drainPending().stream().anyMatch(l -> l.kind() == ConversationState.OutputLine.Kind.ERROR),
                "队满的 ERROR 行不被回滚");
    }

    /** 计划迟到路径 + 违约 responder：零通知、零版本，异常原样上抛。 */
    @Test
    @DisplayName("计划迟到 + 违约 responder：零通知、零版本，异常原样上抛")
    void planLatePathStaysSilentAndPropagatesRogueResponder() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(2);
        List<Integer> bits = new ArrayList<>();
        s.setUiChangeListener(bits::add);
        long before = s.uiVersion();

        assertThrows(IllegalStateException.class,
                () -> s.onPlanSubmitted(1, new PlanRequest(1, "# 计划",
                        (o, f) -> { throw new IllegalStateException("responder 违约抛异常"); })));

        assertEquals(List.of(), bits, "迟到路径无状态变化，本就不该通知");
        assertEquals(before, s.uiVersion());
        assertNull(s.peekModal());
    }

    /**
     * 问询入队失败（迟到 / 队满共用同一 {@code cancel()} 出口）+ 违约 cancel()：
     * 该路径无状态变化（零通知），异常照原样上抛——{@code UserQuestionBridge} 靠透传它让工具调用失败，
     * 吞掉就是让工具线程 park 在 {@code take()} 上。
     */
    @Test
    @DisplayName("问询入队失败 + 违约 cancel()：零通知、零版本，异常原样上抛（迟到与队满两分支）")
    void askEnqueueFailurePropagatesRogueCancelWithoutPublishing() {
        AskResponder rogue = new AskResponder() {
            @Override public void answer(Map<String, String> a) { }
            @Override public void cancel() { throw new IllegalStateException("responder 违约抛异常"); }
        };

        // 迟到：回合已切换
        ConversationState late = new ConversationState();
        late.onTurnStarted(2);
        List<Integer> lateBits = new ArrayList<>();
        late.setUiChangeListener(lateBits::add);
        long lateBefore = late.uiVersion();
        assertThrows(IllegalStateException.class, () -> late.onQuestionAsked(1, ask(1, rogue)));
        assertEquals(List.of(), lateBits, "迟到路径无状态变化，零通知");
        assertEquals(lateBefore, late.uiVersion());
        assertNull(late.peekModal());

        // 队满：8 个模态占满后第 9 个问询直接走 cancel()
        ConversationState full = new ConversationState();
        full.onTurnStarted(1);
        List<PermissionOutcome> filler = new CopyOnWriteArrayList<>();
        for (int i = 0; i < ConversationState.MODAL_QUEUE_CAP; i++) {
            full.onPermissionRequested(1, permission(1, filler));
        }
        List<Integer> fullBits = new ArrayList<>();
        full.setUiChangeListener(fullBits::add);
        long fullBefore = full.uiVersion();
        assertThrows(IllegalStateException.class, () -> full.onQuestionAsked(1, ask(1, rogue)));
        assertEquals(List.of(), fullBits, "问询队满路径无状态变化，零通知");
        assertEquals(fullBefore, full.uiVersion());
        assertTrue(full.hasModal(), "队满的前 8 个模态原封未动（问询未入队）");
    }

    /** 正常路径钉死（I-1 修复不改变 responder 不抛异常时的行为）：先入队后应答为 no-op，只发一次 VIEW|CONTROL。 */
    @Test
    @DisplayName("正常入队路径（responder 不抛）：行为与修复前完全一致")
    void wellBehavedResponderKeepsOriginalBehaviour() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        List<Integer> bits = new ArrayList<>();
        s.setUiChangeListener(bits::add);
        long before = s.uiVersion();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        List<String> order = new CopyOnWriteArrayList<>();

        s.onPermissionRequested(1, new PermissionRequest(1, null, "Bash", "cmd", "{}", "why", null,
                o -> { order.add("respond"); sink.add(o); }));

        assertEquals(List.of(UiDirty.VIEW | UiDirty.CONTROL), bits, "正常入队只发一次 VIEW|CONTROL");
        assertEquals(before + 1, s.uiVersion());
        assertEquals(List.of(), order, "入队成功不调 responder——应答留给面板");
        assertEquals(0, sink.size());
        assertNotNull(s.peekModal());
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private static List<Message> history() {
        return List.of(new UserMessage("问题"), new AssistantMessage("回答"));
    }

    private static PermissionRequest permission(long turnId, List<PermissionOutcome> sink) {
        return new PermissionRequest(turnId, null, "Bash", "cmd", "{}", "why", null, sink::add);
    }

    private static AskRequest ask(long turnId, List<String> cancels) {
        AskResponder responder = new AskResponder() {
            @Override public void answer(Map<String, String> a) { }
            @Override public void cancel() { cancels.add("cancelled"); }
        };
        return new AskRequest(turnId, List.of(new QuestionSpec("选哪个?", "选择",
                List.of(new OptionSpec("A", "第一"), new OptionSpec("B", "第二")), false)), responder);
    }

    /** 指定 responder 的变体（供违约 responder 用例复用同一份题目）。 */
    private static AskRequest ask(long turnId, AskResponder responder) {
        return new AskRequest(turnId, List.of(new QuestionSpec("选哪个?", "选择",
                List.of(new OptionSpec("A", "第一"), new OptionSpec("B", "第二")), false)), responder);
    }

    private static PlanRequest plan(long turnId, List<PlanOutcome> sink) {
        return new PlanRequest(turnId, "# 计划", (outcome, feedback) -> sink.add(outcome));
    }
}

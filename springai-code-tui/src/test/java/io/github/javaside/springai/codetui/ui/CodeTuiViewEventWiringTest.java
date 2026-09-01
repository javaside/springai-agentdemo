package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.AskResponder;
import io.github.javaside.springai.codetui.agent.seam.OptionSpec;
import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;
import io.github.javaside.springai.codetui.agent.seam.PlanOutcome;
import io.github.javaside.springai.codetui.agent.seam.PlanRequest;
import io.github.javaside.springai.codetui.agent.seam.QuestionSpec;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.ui.update.UiChangeListener;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import io.github.javaside.springai.codetui.ui.update.UiUpdateCoordinator;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 7 核心切换的接线与「无周期任务」回归网（brief Step 1）。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li><b>参数化接线表</b>：每类 Agent 侧状态变化（pending / 流式 / 回合迁移 / 工具 / todo /
 *       子任务 / 后台 / 各模态 / 排队 / 插话 / 在飞计数 / notice / 上下文用量 / attention /
 *       MCP 异步）经真实事件源发布后，View 的 coordinator 被「唤醒」，一批
 *       {@code processUpdatesForTest} 把它消费掉；</li>
 *   <li><b>启动/停止形态</b>：{@code configure()} 关 tick、不再有周期 drain、
 *       启动期一次全量同步接住 View 启动前写入的状态、空闲时 coordinator 没有 timer 任务；</li>
 *   <li><b>控制顺序</b>：输出 → 模态 → attention → 插话/排队/后台自动送达，同批内顺序不变。</li>
 * </ol>
 *
 * <p>测试用 {@link CodeTuiView#coordinatorForTest()} 与
 * {@link CodeTuiView#processUpdatesForTest(int)} 两个新 seam；coordinator 的 UI update
 * 投递在测试态（未 run）落到内部记录队列，由 {@code runPendingUiUpdatesForTest()} 受控执行。
 */
class CodeTuiViewEventWiringTest {

    private static SubmitHandler noopHandler() {
        return new SubmitHandler() {
            @Override public Disposable submit(String text) { return () -> { }; }
        };
    }

    private static AskRequest ask(long turnId, List<QuestionSpec> questions, AtomicInteger cancels) {
        return new AskRequest(turnId, questions, new AskResponder() {
            @Override public void answer(Map<String, String> a) { }
            @Override public void cancel() { if (cancels != null) cancels.incrementAndGet(); }
        });
    }

    private static QuestionSpec single(String qn, String... labels) {
        List<OptionSpec> os = new ArrayList<>();
        for (String l : labels) os.add(new OptionSpec(l, l + " 说明"));
        return new QuestionSpec(qn, "选择", os, false);
    }

    // ── configure / 启动形态 ─────────────────────────────────────────────

    @Test
    @DisplayName("configure()：ticks 关闭（事件驱动，不再有常驻 tick 重绘）")
    void configure_disablesTicks() {
        CodeTuiView v = new CodeTuiView(new ConversationState(), noopHandler(), Path.of("."));
        InlineTuiConfig cfg = v.configure(4);
        assertFalse(cfg.ticksEnabled(), "tick 必须关闭：常驻周期重绘是本次重构删除的对象");
        // fix round I-2 真实钉：tickRate=null 是 InlineTuiRunner 构造里「不 scheduleAtFixedRate」
        // 的判定前提（config.ticksEnabled() && config.tickRate() != null）。只断言 ticksEnabled()
        // 不够——把 tickRate 留成非 null 值而 ticksEnabled 恒 true 的实现改动会让它静默通过。
        assertNull(cfg.tickRate(), "tickRate 必须为 null（runner 据此跳过 scheduleAtFixedRate）");
    }

    @Test
    @DisplayName("configure()：bracketed paste 与绑定语义保持不变")
    void configure_keepsBindingsAndPaste() {
        CodeTuiView v = new CodeTuiView(new ConversationState(), noopHandler(), Path.of("."));
        InlineTuiConfig cfg = v.configure(4);
        assertTrue(cfg.bracketedPaste(), "bracketed paste 必须保留（多行粘贴不被拆成多次提交）");
        assertNotNull(cfg.bindings(), "绑定表必须存在");
        assertTrue(cfg.bindings().matches(new KeyEvent(KeyCode.CHAR,
                        dev.tamboui.tui.event.KeyModifiers.CTRL, 'c'), dev.tamboui.tui.bindings.Actions.QUIT),
                "Ctrl+C 仍应触发 quit");
    }

    @Test
    @DisplayName("构造后即绑定 state 变化源：start 后通知落入 coordinator dirty bits")
    void constructor_bindsStateChangeSourceImmediately(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, noopHandler(), root);
        assertNotNull(v.coordinatorForTest(), "View 构造后必须立刻有 coordinator（不等 onStart）");
        v.startForTest();
        state.pushInfo("hello");
        assertTrue(v.coordinatorForTest().pendingDirtyBits() != 0,
                "state 变化必须落入 coordinator 的 dirty bits（MCP connecting 进入通知也走这条路）");
    }

    @Test
    @DisplayName("onStart 形态：无周期 drain；一次 ALL 批接住 View 启动前写入的状态并打欢迎横幅")
    void startup_consumesPreStartPendingWithoutPeriodicDrain(@TempDir Path root) throws Exception {
        ConversationState state = new ConversationState();
        state.pushInfo("启动前写入的权限提示");
        state.onTurnStarted(1L);
        state.onAssistantToken(1L, "恢复历史的完整行\n残行");
        CodeTuiView v = new CodeTuiView(state, noopHandler(), root);
        v.startForTest();   // 等价 onStart 的 coordinator start + 欢迎横幅 + 初始 ALL 同步

        // 「无周期任务」真实钉（fix round I-2）：直接扫描生产源——注释/字符串里提到不算，
        // 代码里出现这些符号 = 有人恢复了周期任务（或每帧轮询），必须变红。
        assertNoPeriodicSchedulingTokens(viewSource());
        assertTrue(v.welcomePrintedForTest(), "欢迎横幅应在启动路径打印（UI 线程一次性）");
        assertTrue(v.initialAllSyncDoneForTest(), "启动期必须有一次 UiDirty.ALL 初始全量同步");
        // 初始同步是「有界批」：首批吃 pending（队头惰性 + 防插队语义保留），流式完整行由
        // continuation 下一批接走——排空到静止（无 pending、无完整行）为止。
        for (int i = 0; i < 50
                && (state.hasPendingOutput() || state.hasCompleteStreamingLine()); i++) {
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
        }
        assertFalse(state.hasPendingOutput(), "初始同步（含 continuation）应消费 View 启动前的 pending");
        assertFalse(state.hasCompleteStreamingLine(), "初始同步（含 continuation）应消费 View 启动前的流式完整行");
    }

    @Test
    @DisplayName("空闲 coordinator：无任何在飞一次性任务（无 timer = 无周期唤醒）")
    void idleCoordinator_hasNoScheduledTimer(@TempDir Path root) throws Exception {
        CodeTuiView v = new CodeTuiView(new ConversationState(), noopHandler(), root);
        v.startForTest();
        UiUpdateCoordinator c = v.coordinatorForTest();
        assertFalse(c.updateScheduled(), "空闲时不得有待执行的 UI update");
        assertEquals(UiUpdateCoordinator.Lifecycle.RUNNING, c.lifecycle());
        // 让 scheduler 里的 timer（若有）到期，再确认它们没 publish 任何东西。
        TimeUnit.MILLISECONDS.sleep(150);
        v.runPendingUiUpdatesForTest();
        assertEquals(0, c.pendingDirtyBits(), "空闲时不应有任何 timer 悄悄 publish");
        assertFalse(v.hasContinuationScheduledForTest(), "空闲时不得有 continuation 在飞");
    }

    // ── 参数化接线表：每类变化 → 一批 processUpdates 能消费 ───────────────

    /**
     * 接线表驱动：对每类变化，走<b>真实事件源</b>（state / onSubmit fan-out），断言
     * coordinator 收到非零 dirty bits，且一批消费后清零。
     */
    @Test
    @DisplayName("接线表：pending/流式/工具/todo/子任务/后台/排队/notice/回合迁移都唤醒 coordinator")
    void changeSources_eachWakeCoordinator(@TempDir Path root) {
        record Case(String name, Consumer<ConversationState> mutation) { }
        List<Case> cases = List.of(
                new Case("pushInfo", s -> s.pushInfo("info")),
                new Case("assistantToken", s -> { s.onTurnStarted(1L); s.onAssistantToken(1L, "token"); }),
                new Case("toolStarted", s -> { s.onTurnStarted(1L); s.onToolStarted(1L, "Read", "{\"path\":\"a\"}"); }),
                new Case("toolFinished", s -> { s.onTurnStarted(1L); s.onToolFinished(1L, "Read", "ok", true); }),
                new Case("todoUpdated", s -> { s.onTurnStarted(1L); s.onTodoUpdated(1L, List.of("▶ do")); }),
                new Case("subagentStarted", s -> { s.onTurnStarted(1L); s.onSubagentStarted(1L, "t1", "explore", "描述"); }),
                new Case("backgroundStarted", s -> s.onBackgroundTaskStarted("b1", "explore", "描述")),
                new Case("backgroundFinished", s -> s.onBackgroundTaskFinished("b1", "结论", true)),
                new Case("notice", s -> s.setNotice("提示")),
                new Case("enqueue", s -> s.enqueue("排队的消息", null)),
                new Case("turnStarted", s -> s.onTurnStarted(1L)),
                new Case("turnComplete", s -> { s.onTurnStarted(1L); s.onTurnComplete(1L); }),
                new Case("compaction", s -> s.onCompactionStarted("手动"))
        );
        for (Case c : cases) {
            ConversationState fresh = new ConversationState();
            CodeTuiView v = new CodeTuiView(fresh, noopHandler(), root);
            v.startForTest();
            v.runPendingUiUpdatesForTest();
            assertEquals(0, v.coordinatorForTest().pendingDirtyBits(),
                    "[" + c.name() + "] 前置：初始同步后应无脏位");

            c.mutation().accept(fresh);
            assertTrue(v.coordinatorForTest().pendingDirtyBits() != 0,
                    "[" + c.name() + "] 变化必须唤醒 coordinator（dirty bits 非零）");

            v.runPendingUiUpdatesForTest();
            assertEquals(0, v.coordinatorForTest().pendingDirtyBits(),
                    "[" + c.name() + "] 一批消费后 dirty bits 应清零");
        }
    }

    @Test
    @DisplayName("模态：Ask/Permission/Plan 三类请求入队后一批进入对应模态")
    void modals_enterInOneBatch(@TempDir Path root) {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onQuestionAsked(1L, ask(1L, List.of(single("选哪个?", "A", "B")), null));
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();
        assertNotNull(v.activeAskForTest(), "Ask 模态应在事件驱动一批内进入");
        assertNull(v.activePermissionForTest());
        assertNull(v.activePlanForTest());

        ConversationState s2 = new ConversationState();
        s2.onTurnStarted(1L);
        s2.onPermissionRequested(1L, new PermissionRequest(1, null, "Bash", "rm x", "{}", "r", null,
                o -> { }));
        CodeTuiView v2 = new CodeTuiView(s2, noopHandler(), root);
        v2.startForTest();
        v2.runPendingUiUpdatesForTest();
        assertNotNull(v2.activePermissionForTest(), "Permission 模态应在事件驱动一批内进入");

        ConversationState s3 = new ConversationState();
        s3.onTurnStarted(1L);
        s3.onPlanSubmitted(1L, new PlanRequest(1, "计划", (o, f) -> { }));
        CodeTuiView v3 = new CodeTuiView(s3, noopHandler(), root);
        v3.startForTest();
        v3.runPendingUiUpdatesForTest();
        assertNotNull(v3.activePlanForTest(), "Plan 模态应在事件驱动一批内进入");
    }

    @Test
    @DisplayName("畸形问询：一批内移除请求并取消整回合（cancel 恰好一次，不静默丢弃）")
    void malformedAsk_isRemovedAndCancelsTurnInOneBatch(@TempDir Path root) {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        AtomicInteger cancelled = new AtomicInteger();
        s.onQuestionAsked(1L, ask(1L, List.of(), cancelled));   // 无问题 = 畸形
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();
        assertNull(v.activeAskForTest(), "畸形问询不得进入模态");
        assertEquals(1, cancelled.get(), "畸形问询必须应答一次（cancel），不能静默丢弃");
        assertTrue(s.isIdle(), "畸形问询取消整回合后 state 应回 IDLE");
    }

    @Test
    @DisplayName("onSubmit fan-out：setUiChangeListener 绑定的必须是 coordinator 本体")
    void submitHandler_fanOutBoundToCoordinator(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<UiChangeListener> bound = new CopyOnWriteArrayList<>();
        SubmitHandler handler = new SubmitHandler() {
            @Override public Disposable submit(String text) { return () -> { }; }
            @Override public void setUiChangeListener(UiChangeListener l) { bound.add(l); }
        };
        CodeTuiView v = new CodeTuiView(state, handler, root);
        v.startForTest();
        assertEquals(1, bound.size(), "onSubmit.setUiChangeListener 必须恰好绑定一次");
        assertTrue(v.coordinatorForTest() == bound.get(0),
                "绑定目标必须是 coordinator 本体（InlineTuiRunner 版，不是 Consumer 接缝）");
    }

    @Test
    @DisplayName("停止顺序：coordinator/context controller 先停，变化源解绑为 no-op，迟到通知 no-op")
    void stop_unbindsAndStopsBeforeSuperCleanup(@TempDir Path root) {
        ConversationState state = new ConversationState();
        AtomicInteger unbound = new AtomicInteger();
        AtomicInteger bound = new AtomicInteger();
        SubmitHandler handler = new SubmitHandler() {
            @Override public Disposable submit(String text) { return () -> { }; }
            @Override public void setUiChangeListener(UiChangeListener l) {
                if (l == null) unbound.incrementAndGet(); else bound.incrementAndGet();
            }
        };
        CodeTuiView v = new CodeTuiView(state, handler, root);
        v.startForTest();
        assertEquals(1, bound.get(), "启动时绑定一次");
        v.stopForTest();
        assertEquals(UiUpdateCoordinator.Lifecycle.STOPPED, v.coordinatorForTest().lifecycle(),
                "coordinator 必须先于超类清理停止");
        assertEquals(1, unbound.get(), "onSubmit 的变化源必须解绑（setUiChangeListener(null)）");
        // 解绑后迟到通知是 no-op：不再抛、不再堆 dirty bits。
        state.pushInfo("迟到通知");
        assertEquals(0, v.coordinatorForTest().pendingDirtyBits(), "stop 后迟到通知必须是 no-op");
    }

    // ── 控制顺序（同批内） ────────────────────────────────────────────────

    @Test
    @DisplayName("控制顺序：输出先消费，模态同步随后，attention 在自动出队之前推进")
    void controlOrder_outputBeforeModalBeforeAttentionBeforeAutoDelivery(@TempDir Path root) {
        // 场景：一条 pending 输出 + 一个 Ask 模态 + 一个已完成后台结果同时到位。
        // 期望同批内：① 输出行先下沉（哪怕预算被模态正文挤占，顺序不能颠倒）；
        //            ② 模态进入作答态；③ attention 进入 WAITING_USER；
        //            ④ 自动出队判定被「模态在场 = busy」闸住（不发 submit）。
        ConversationState s = new ConversationState();
        AtomicInteger submits = new AtomicInteger();
        SubmitHandler handler = new SubmitHandler() {
            @Override public Disposable submit(String text) { submits.incrementAndGet(); return () -> { }; }
            @Override public List<BackgroundResult> completedBackgroundTasks() {
                return List.of(new BackgroundResult("b1", "explore", "d", "结果正文", true));
            }
        };
        s.onTurnStarted(1L);
        s.pushInfo("一行输出");
        s.onQuestionAsked(1L, ask(1L, List.of(single("继续吗?", "是", "否")), null));
        CodeTuiView v = new CodeTuiView(s, handler, root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();

        assertFalse(s.hasPendingOutput(), "① 输出消费先于模态同步（本批必须吃掉 pending）");
        assertNotNull(v.activeAskForTest(), "② 模态同步紧随其后（同批内）");
        assertEquals(AttentionTracker.Phase.WAITING_USER, v.attentionForTest().phase(),
                "③ attention 推进在模态之后、自动出队之前");
        assertEquals(0, submits.get(), "④ 模态在场（busy）必须闸住后台自动送达");

        // 答完模态释放闸门：模态应答只唤醒工具线程，回合结束由 Agent 侧 onTurnComplete 发布
        // （测试直接补这个事件），随后下一批即可自动送达后台结果。
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 选「是」→ 应答、退出模态
        s.onTurnComplete(1L);                              // Agent 线程：回合结束（发布 CONTROL/VIEW）
        v.runPendingUiUpdatesForTest();
        v.processUpdatesForTest(UiDirty.CONTROL);
        assertTrue(submits.get() >= 1, "模态应答释放后，后台结果自动送达必须发生");
    }

    @Test
    @DisplayName("控制顺序：插话先于排队消息，排队消息先于后台结果（自动送达优先级不变）")
    void autoDeliveryPriority_interjectionThenQueuedThenBackground(@TempDir Path root) {
        // 插话 + 排队 + 后台结果三者并存且空闲：第一批只送插话（dispatch 后本批结束），
        // 后续批依次排队、后台。每批只送一条是既有纪律（用户排的队优先）。
        ConversationState s = new ConversationState();
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicInteger interjectionsLeft = new AtomicInteger(1);
        SubmitHandler handler = new SubmitHandler() {
            @Override public Disposable submit(String text) {
                order.add(text);
                return () -> { };
            }
            @Override public List<String> takePendingInterjections() {
                return interjectionsLeft.getAndDecrement() > 0 ? List.of("插话内容") : List.of();
            }
            @Override public List<BackgroundResult> completedBackgroundTasks() {
                return List.of(new BackgroundResult("b1", "explore", "d", "结果", true));
            }
        };
        s.enqueue("排队的消息", null);   // 空闲态 enqueue 直接入队（不 dispatch）
        CodeTuiView v = new CodeTuiView(s, handler, root);
        v.startForTest();

        v.runPendingUiUpdatesForTest();
        assertEquals(List.of("插话内容"), snapshot(order),
                "第一批只送插话（时序更早，优先级最高）");

        // 插话送走后（handler 继续报空），下一批送排队消息。
        v.processUpdatesForTest(UiDirty.CONTROL);
        assertEquals(List.of("插话内容", "排队的消息"), snapshot(order),
                "第二批送排队消息（先于后台结果）");

        v.processUpdatesForTest(UiDirty.CONTROL);
        assertTrue(order.size() == 3 && order.get(2).contains("结果"),
                "第三批才轮到后台结果自动送达");
    }

    private static List<String> snapshot(List<String> list) {
        return List.copyOf(list);
    }

    // ── fix round I-1 / I-3：处理失败的有界重试 + continuation 单一调度方 ───

    /**
     * 可编程 {@link SubmitHandler} 桩：{@code takePendingInterjections} 在批处理的
     * 自动出队段被调用（输出/模态/attention 之后），允许测试让<b>批处理进行中</b>抛业务
     * 异常——注入「批处理异常」的接缝（{@code ConversationState} 是 final 不能继承；
     * 其 {@code publish} 对 listener 异常有防护，从发布侧注入也到不了 processor）。
     *
     * <p>刻意选自动出队段而不是输出消费段：输出段抛异常时未消费的 pending 仍在，旧实现经
     * {@code outputRemaining} 的 continuation 也能自我恢复；<b>自动出队段在输出之后</b>，
     * 那里抛异常（dirty bits 已被 runBatch 取走、无输出存量）才是「静默停滞到下一个
     * 无关事件」的真实缺口。
     */
    private static final class FlakyHandler implements SubmitHandler {
        volatile Runnable beforeTakeInterjections;

        @Override public Disposable submit(String text) { return () -> { }; }

        @Override public List<String> takePendingInterjections() {
            Runnable hook = beforeTakeInterjections;
            if (hook != null) hook.run();
            return List.of();
        }
    }

    @Test
    @DisplayName("批处理业务异常：warn + 补发一次 ALL 重试批；连续失败有界（不无限循环、不静默停滞）")
    void batchFailure_warnsAndRetriesOnceThenStopsBounded(@TempDir Path root) throws Exception {
        FlakyHandler h = new FlakyHandler();
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, h, root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();   // 初始同步批正常完成
        assertEquals(0, v.coordinatorForTest().pendingDirtyBits(), "前置：初始同步后无脏位");
        int baselineBatches = v.processedBatchesForTest();

        // 自动出队段抛 IllegalStateException 一次（输出已排空、无 continuation 兜底——
        // 若 View 只记日志不补发，这条 pending 就静默停滞到下一个无关事件）。
        AtomicReference<RuntimeException> bomb = new AtomicReference<>(
                new IllegalStateException("boom: processor failure"));
        h.beforeTakeInterjections = () -> {
            RuntimeException b = bomb.get();
            if (b != null) {
                bomb.set(null);   // 只炸一次
                throw b;
            }
        };
        s.pushInfo("这条会炸批");
        v.runPendingUiUpdatesForTest();   // 失败批 + （若同步补发）重试批都会在这一轮排空里执行
        int afterFirstRound = v.processedBatchesForTest();
        assertTrue(afterFirstRound >= baselineBatches + 1,
                "前置：至少一个失败批已执行");

        // View 不得把异常抛出事件循环（测试队列即事件循环等价物，抛出 = 击穿防护），且必须自我恢复：
        // 补发一次 ALL 重试批把炸批时的 pending 消费掉。
        int guard = 0;
        while (s.hasPendingOutput() && guard++ < 100) {
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
        }
        assertFalse(s.hasPendingOutput(),
                "批处理异常后 View 必须补发重试批消费未完成的 pending（不得静默停滞到下一个无关事件）");
        assertTrue(v.processedBatchesForTest() >= baselineBatches + 2,
                "异常批 + 至少一个重试批（实际总批数 " + v.processedBatchesForTest() + "）");

        // 异常风暴有界：自动出队段每次都炸。连续失败达上限后 View 停止补发，
        // 不产生「失败→补发→再失败→再补发」的无限循环。注入点在输出段之后，故风暴批的
        // pending 仍被正常消费（数据不丢）；「停滞」的实质是模态同步/自动出队没跑完——
        // 由封顶后无新批自发出现来钉。
        AtomicInteger failures = new AtomicInteger();
        h.beforeTakeInterjections = () -> {
            failures.incrementAndGet();
            throw new IllegalStateException("persistent boom #" + failures.get());
        };
        s.pushInfo("风暴测试");
        v.runPendingUiUpdatesForTest();   // 触发失败批（含至多 MAX_BATCH_FAILURE_RETRIES 次补发）
        int batchesAtCap = v.processedBatchesForTest();
        for (int i = 0; i < 5; i++) {     // 再给足时间：封顶后不得有任何新批自发出现
            TimeUnit.MILLISECONDS.sleep(10);
            v.runPendingUiUpdatesForTest();
        }
        assertEquals(batchesAtCap, v.processedBatchesForTest(),
                "连续失败封顶后不得继续补发重试批（防异常风暴）：失败次数=" + failures.get());
        assertTrue(failures.get() >= 1 && failures.get() <= 3,
                "重试有界：同一连续失败序列的失败批数必须封顶（1 次原始失败 + 至多 "
                        + "2 次补发；实际失败 " + failures.get() + " 次）");
        // 下一个真实生产者事件仍能驱动新批（封顶只停补发，不锁死事件路径）。
        h.beforeTakeInterjections = null;
        s.pushInfo("风暴后的恢复");
        v.runPendingUiUpdatesForTest();
        assertTrue(v.processedBatchesForTest() > batchesAtCap,
                "封顶后真实事件必须仍能驱动新批（View 未被失败序列锁死）");
    }

    @Test
    @DisplayName("批处理异常不炸事件循环：返回 idle 结果（不声明任何 follow-up），后续批照常")
    void batchFailure_returnsIdleResultAndKeepsLoopAlive(@TempDir Path root) {
        FlakyHandler h = new FlakyHandler();
        h.beforeTakeInterjections = () -> { throw new IllegalStateException("processor failed"); };
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, h, root);
        v.startForTest();

        // 异常批（直接跑 ALL 批就会撞上）：View 不得把它抛出（抛出 = 击穿防护）。
        // 返回值退化为 idle——批没跑完，follow-up 需求不可知，声明 remaining 会安排 continuation 盲目重跑。
        UiUpdateCoordinator.UpdateResult r = v.processUpdatesForTest(UiDirty.ALL);
        assertNotNull(r, "异常批也必须返回结果（不抛、不返回 null）");
        assertFalse(r.outputRemaining(), "异常批不得声明 outputRemaining（continuation 会盲目重跑）");
        assertFalse(r.animationActive(), "异常批不得声明 animationActive");

        // 恢复后（不再抛）下一批照常消费：批处理失败不产生永久损伤。
        h.beforeTakeInterjections = null;
        UiUpdateCoordinator.UpdateResult ok = v.processUpdatesForTest(UiDirty.ALL);
        assertFalse(ok.outputRemaining(), "恢复后的批照常排空");
    }

    @Test
    @DisplayName("continuation 单一调度方：View 不再直接 scheduleOutputContinuation，只由 runBatch 排")
    void continuation_soleSchedulerIsCoordinatorRunBatch(@TempDir Path root) throws Exception {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("line ").append(i).append('\n');
        s.onAssistantToken(1L, big.toString());
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();

        // 单一调度方验证（fix round I-3）：View 侧不调 coordinator.scheduleOutputContinuation——
        // 生产路径只由 runBatch 按 outputRemaining 排。这里用真实事件驱动整条链：
        // publish(ALL) → runBatch → processUpdates 声明 remaining → runBatch 安排 continuation。
        UiUpdateCoordinator c = v.coordinatorForTest();
        v.runPendingUiUpdatesForTest();   // 初始同步批（经 runBatch）
        assertTrue(c.hasPendingContinuation(),
                "runBatch 收到 outputRemaining 后必须自己安排 continuation（生产唯一调度方）");

        // 排空到静止：continuation 链最终清空（无生产者事件）。
        int guard = 0;
        while ((s.hasPendingOutput() || s.hasCompleteStreamingLine() || c.hasPendingContinuation())
                && guard++ < 400) {
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
        }
        assertFalse(s.hasPendingOutput(), "continuation 链应最终排空 pending");
        assertFalse(s.hasCompleteStreamingLine(), "continuation 链应最终排空流式完整行");
        assertFalse(c.hasPendingContinuation(), "排空后不得残留 continuation timer");
    }

    // ── fix round I-2：无周期任务的真实钉（源码扫描） ──────────────────────

    /**
     * 读取 {@link CodeTuiView} 生产源码（从工作目录定位 {@code src/main/java} 下的文件；
     * surefire 工作目录是模块根，父级回退兜 IDE 内联运行）。
     */
    private static String viewSource() {
        Path p = Path.of("src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java");
        if (!Files.isRegularFile(p)) {
            p = Path.of("..", "springai-code-tui",
                    "src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java");
        }
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 CodeTuiView 源码做无周期任务扫描: " + p.toAbsolutePath(), e);
        }
    }

    /** 剥掉字符串/字符字面量与注释后的源码（符号扫描只看真实代码，注释里提到不算违规）。 */
    private static String stripLiteralsAndComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int mode = 0;   // 0=code 1=lineComment 2=blockComment 3=string 4=char
        while (i < src.length()) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            switch (mode) {
                case 3, 4 -> {
                    if (c == '\\') { i += 2; continue; }   // 转义序列整体跳过
                    if ((mode == 3 && c == '"') || (mode == 4 && c == '\'')) mode = 0;
                    i++;
                }
                case 1 -> {
                    if (c == '\n') { mode = 0; out.append(c); }
                    i++;
                }
                case 2 -> {
                    if (c == '*' && next == '/') { mode = 0; i += 2; } else i++;
                }
                default -> {
                    if (c == '/' && next == '/') mode = 1;
                    else if (c == '/' && next == '*') mode = 2;
                    else if (c == '"') mode = 3;
                    else if (c == '\'') mode = 4;
                    else out.append(c);
                    i++;
                }
            }
        }
        return out.toString();
    }

    /** 生产源码（去注释/字符串）里不得出现任何「周期调度 / 常驻 tick」符号。 */
    private static void assertNoPeriodicSchedulingTokens(String rawSource) {
        String code = stripLiteralsAndComments(rawSource);
        List<String> banned = List.of(
                "scheduleRepeating",     // 旧 66ms drain 的注册方式（InlineToolkitRunner.scheduleRepeating）
                "scheduleAtFixedRate",   // 固定频率任务的底层原语
                ".tickRate(",            // configure 里重新开启 tick
                ".onTick("               // 每帧喂拍类轮询（ResizeSettle.onTick）
        );
        for (String token : banned) {
            assertFalse(code.contains(token),
                    "CodeTuiView 生产代码不得包含周期调度符号「" + token
                            + "」（事件驱动：一次性任务归 coordinator，常驻周期是本次重构删除对象）");
        }
    }

    // ── 本地 UI 状态变化（不在 Agent 源里） ───────────────────────────────

    @Test
    @DisplayName("本地 UI 状态：按键路径改完状态后必须主动发布 VIEW（不等 Agent 事件）")
    void localUiChanges_publishViewDirectly(@TempDir Path root) {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();
        assertEquals(0, v.coordinatorForTest().pendingDirtyBits(), "前置：初始同步后无脏位");

        // 打开 /model 选择器：纯本地 UI 状态（不在 Agent 源里），按键路径必须主动 publish。
        for (char c : "/model".toCharArray()) v.feedKeyForTest(KeyEvent.ofChar(c));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(v.coordinatorForTest().pendingDirtyBits() != 0,
                "本地 UI 状态变化（选择器打开）必须主动发布 VIEW");
        assertTrue((v.coordinatorForTest().pendingDirtyBits() & UiDirty.VIEW) != 0,
                "发布位必须包含 VIEW");

        // 选择器内移动高亮（仍是本地状态）也必须发布。
        v.runPendingUiUpdatesForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        assertTrue((v.coordinatorForTest().pendingDirtyBits() & UiDirty.VIEW) != 0,
                "选择器移动高亮后必须再次发布 VIEW");
    }

    // ── 输出 continuation ────────────────────────────────────────────────

    @Test
    @DisplayName("输出 remaining 时：outputRemaining=true；多批后最终排空（无生产者事件也排空）")
    void outputRemaining_flagDrivesContinuation(@TempDir Path root) throws Exception {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("line ").append(i).append('\n');
        s.onAssistantToken(1L, big.toString());
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();

        // 直连批（不经 runBatch）：验证 View 只「声明」remaining（fix round I-3 后 View 不自排
        // continuation，单一调度方是 runBatch——故此处断言声明位，不断言 coordinator 槽；
        // 槽的排定由 continuation_soleSchedulerIsCoordinatorRunBatch 经真实链钉住）。
        UiUpdateCoordinator.UpdateResult r1 = v.processUpdatesForTest(UiDirty.ALL);
        assertTrue(r1.outputRemaining(), "5000 行一批打不完，必须声明 remaining");

        // 经真实链再跑一批（runBatch 收到 remaining → 自己排 continuation）：
        s.pushInfo("追加一批存量");   // 生产者事件把 runBatch 投进队列
        v.runPendingUiUpdatesForTest();
        assertTrue(v.hasContinuationScheduledForTest(),
                "runBatch 收到 outputRemaining 后必须安排 continuation（ZERO 延迟在飞或已消费皆可由后续排空证明）");

        // 无任何新生产者事件，靠 continuation 一批批排空：每轮「等 timer 到期 → 执行其 publish
        // 的 UI update」直到没有 continuation（5000 行 / 300 行批 ≈ 17 批；上限防死循环）。
        int batches = 0;
        while (batches < 200) {
            batches++;
            TimeUnit.MILLISECONDS.sleep(5);   // ZERO 延迟 timer 触发（scheduler 线程 publish → 测试队列）
            v.runPendingUiUpdatesForTest();
            if (!s.hasPendingOutput() && !s.hasCompleteStreamingLine()) {
                // 再跑一批确认队列也空（这批可能仍消费队列尾）
                UiUpdateCoordinator.UpdateResult r = v.processUpdatesForTest(UiDirty.OUTPUT);
                if (!r.outputRemaining()) break;
            }
        }
        UiUpdateCoordinator.UpdateResult last = v.processUpdatesForTest(UiDirty.OUTPUT);
        assertFalse(last.outputRemaining(), "排空后不得再声明 remaining");
        int guard = 0;
        while (v.coordinatorForTest().hasPendingContinuation() && guard++ < 50) {
            TimeUnit.MILLISECONDS.sleep(5);   // 让已排定的 ZERO 延迟 timer 自然触发完
            v.runPendingUiUpdatesForTest();
        }
        assertFalse(v.coordinatorForTest().hasPendingContinuation(), "排空后不得残留 continuation timer");
    }

    @Test
    @DisplayName("输出排空后：remaining=false，不安排 continuation（空闲无 timer）")
    void drainedOutput_reportsIdle(@TempDir Path root) {
        ConversationState s = new ConversationState();
        s.pushInfo("一行");
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        UiUpdateCoordinator.UpdateResult r = v.processUpdatesForTest(UiDirty.ALL);
        assertFalse(r.outputRemaining(), "一行输出一批即空，不该再要 continuation");
        assertFalse(v.hasContinuationScheduledForTest(), "空闲时不得有 continuation 在飞");
    }

    // ── tickForTest 兼容别名 ─────────────────────────────────────────────

    @Test
    @DisplayName("tickForTest 仍是兼容别名：跑一批 ALL，不启动任何周期任务")
    void tickForTest_remainsCompatibleAlias(@TempDir Path root) {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onQuestionAsked(1L, ask(1L, List.of(single("选哪个?", "A", "B")), null));
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.tickForTest();
        assertNotNull(v.activeAskForTest(), "tickForTest 必须仍能侦测并进入模态");
        // 「不启动周期任务」由源码扫描钉（startup_consumesPreStartPendingWithoutPeriodicDrain）；
        // 此处补行为面：跑一批后 coordinator 侧不得出现常驻性 continuation 在飞。
        assertFalse(v.coordinatorForTest().hasPendingContinuation(),
                "单批 ALL 后排空（无存量输出），不得留下在飞的 continuation timer");
    }

    @Test
    @DisplayName("resize 路径：宽度变化事件仍走 ResizeEvent 处理；SIGWINCH 合并兜底不再每帧轮询")
    void resize_keepsEventPathWithoutPerFramePolling(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), noopHandler(), root);
        v.startForTest();
        // fix round I-2：原 widthPollingEveryFrameForTest() 是编译期常量 false（恒真断言）。
        // 真实钉 = 源码扫描：每帧宽度轮询的载体正是 drain/tick 里的 ResizeSettle.onTick() 喂拍，
        // 两个符号都被禁（onTick 调用 / new ResizeSettle 实例化），谁恢复轮询谁变红。
        String src = viewSource();
        assertNoPeriodicSchedulingTokens(src);
        assertFalse(src.contains(".onTick("),
                "每帧宽度轮询（ResizeSettle.onTick 每帧喂拍）不得恢复：resize 走 ResizeEvent → 一次性 settle");
        assertFalse(src.contains("new ResizeSettle("),
                "ResizeSettle（帧驱动的停稳判定器）已随每帧轮询删除：settle 由 coordinator 132ms 一次性任务承担");
    }

    // ── Task 8：preview / animation / IME 的按需时间任务 ───────────────────

    /**
     * 首个流式残行可立即可见（§10.1「首个新残行立即请求预览」）：
     * token 到达后第一批渲染就把残行画进 live 区，不等 150ms 节流窗口。
     */
    @Test
    @DisplayName("preview：首个流式残行可立即可见（不等节流窗口）")
    void preview_firstStreamingTailIsImmediatelyVisible(@TempDir Path root) {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();

        s.onTurnStarted(1L);
        s.onAssistantToken(1L, "首个残行");
        v.runPendingUiUpdatesForTest();

        // 首段立即可见：第一批 render（ViewScreen 等价 render()）就读得到残行。
        // lastPreviewedTail 初值 ""，render 的采纳条件（curTail 非空且距上次预览 ≥0）立即满足。
        assertTrue(ViewScreen.of(v).contains("首个残行"),
                "首个流式残行必须立即可见（150ms 节流窗口只挡住后续更新）");
    }

    /**
     * 150ms 窗口内的多个 token 只产生<b>一个</b> pending preview wake（§10.1）：
     * 窗口内到达的 token 只更新状态、不追加调度；到期只发一次 VIEW。
     *
     * <p>每个消费步后跑一次 {@code ViewScreen.of(v)}（等价生产「批后即 render」——
     * requestUiUpdate 完成后必然一次合并绘制，preview 的采纳点在 render 内更新
     * {@code lastPreviewAtNanos}，下一批的剩余窗口由此起算）。不跑 render 的话
     * 采纳点不前进，窗口永远是 ZERO——那不是生产形态。
     */
    @Test
    @DisplayName("preview：150ms 窗口内多次 token 后，实际 follow-up 批次数保持有界")
    void preview_tokensInsideThrottleWindowProduceBoundedFollowUpBatches(@TempDir Path root) throws Exception {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();
        ViewScreen.of(v);   // 初始 render（等价生产首帧）

        s.onTurnStarted(1L);
        s.onAssistantToken(1L, "残行起点");
        v.runPendingUiUpdatesForTest();
        ViewScreen.of(v);   // 生产：批后 render 采纳首段（立即可见）并重置节流时钟
        assertTrue(v.hasPendingPreviewScheduledForTest(),
                "流式残行非空时必须有一个 preview 到期在飞（§10.1）");

        // 窗口内连续 50 个 token（真实事件，各自 publish OUTPUT|VIEW）。
        // 每次消费后 render（生产节拍），模拟 150ms 窗口内的密集到达。
        for (int i = 0; i < 50; i++) {
            s.onAssistantToken(1L, " more" + i);
            v.runPendingUiUpdatesForTest();
            ViewScreen.of(v);
        }
        // 节流窗口内每批至多安排一个 preview 一次性任务（coordinator 每类至多一个在飞）。
        assertTrue(v.hasPendingPreviewScheduledForTest(),
                "残行仍在时 preview 到期在飞是合法状态（下一窗口的唤醒）");

        // 静止等待：token 停止后，preview 到期只发一次 VIEW 批——残行内容在 render 处
        // 每 150ms 才被采纳一次，批次数必须有界（任何「一 token 一批」或自驱动循环都会远超）。
        // ⚠ 上限要容纳动画帧：回合仍在 THINKING（忙态），§10.3 的 66ms 帧续排是<b>合法</b>
        // 批次——200ms 观测窗 ≈3 个动画帧 + ≤2 个 preview 到期 + 调度余量，故上界取 10；
        // 一 token 一批会是 50+，自驱动循环会是 40（每 5ms 一批）——都远超此界。
        int batches = v.processedBatchesForTest();
        for (int i = 0; i < 40; i++) {   // 200ms 观测：≥1 个节流窗口到期
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
            ViewScreen.of(v);
        }
        int extra = v.processedBatchesForTest() - batches;
        assertTrue(extra <= 10,
                "窗口内 50 token 到期后批次数必须有界（动画帧 + preview 到期；实际新增 "
                        + extra + " 批）");
        // token 已停止且最新残行已采纳：preview demand 消失，不应再有下一窗口唤醒；
        // 回合结束后的清空静止另由 preview_noTaskAfterTurnCompletion 钉。
        assertFalse(v.hasPendingPreviewScheduledForTest(),
                "最新残行采纳后 preview timer 必须停止，下一真 token 再由 OUTPUT 事件重启");
    }

    /**
     * <b>静止残行不得自续排热循环</b>（C-1 回归钉）：token 停止后残行静止但非空
     * （streaming 只在工具开始/子 agent/回合结束才 flush），此时：
     * <ol>
     *   <li>残行内容已被 render 采纳（curTail == lastPreviewedTail）→ <b>无未采纳内容</b>
     *       → previewPending 必须为 false，不得续排任何 preview 到期；</li>
     *   <li>长观测窗（≥2×150ms 节流窗口）内批次数必须有界且极小——旧实现
     *       「previewPending = 残行非空」在 render 不采纳（tail 相同 → lastPreviewAtNanos
     *       不前进）时退化为 ZERO 延迟自续排：到期 → 批 → 再排 → 无限循环，
     *       任何 &gt;300ms 的流式中途停顿都触发 CPU/渲染线程自旋。</li>
     * </ol>
     *
     * <p>本测试在旧实现下必须红：ZERO 链每 5ms 观测步都在产生自发批
     * （观测 400ms+ 远超节流窗口，批次数会持续增长）。
     */
    @Test
    @DisplayName("preview：静止残行（内容已采纳）不自续排——长观测窗内批次有界、无 preview 排队")
    void preview_staticTailDoesNotSelfRescheduleHotLoop(@TempDir Path root) throws Exception {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();
        ViewScreen.of(v);   // 初始 render

        s.onTurnStarted(1L);
        s.onAssistantToken(1L, "中途停顿的残行");
        v.runPendingUiUpdatesForTest();
        v.renderForTest();  // 首段采纳（立即可见）；直接走真实采纳点，避免屏幕序列化丢样式文本

        // token 停止（模拟 LLM 流式中途停顿 >300ms 的常态）：残行静止但非空。
        // 先让首个 preview 到期被消费（窗口推进一轮），确保观测起点是「内容已采纳」的静止态。
        for (int i = 0; i < 40; i++) {   // 200ms：首个节流窗口到期
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
            ViewScreen.of(v);
        }

        // ── 观测起点：残行静止、内容已被 render 采纳 ──
        // 静止后不允许再排任何 preview 到期（无未采纳内容 = 无 demand）。
        for (int i = 0; i < 30 && v.hasPendingPreviewScheduledForTest(); i++) {
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
            ViewScreen.of(v);
        }
        assertFalse(v.hasPendingPreviewScheduledForTest(),
                "静止残行（内容已采纳）不得续排 preview：残行非空 ≠ 有未采纳内容"
                        + "——旧实现 ZERO 链自续排即 CPU 热循环");
        assertFalse(v.hasContinuationScheduledForTest(), "无输出存量时不得有 continuation");

        // 长观测窗（420ms ≥ 2×150ms 节流窗口 + 3×66ms 动画帧）：
        // 旧 ZERO 链会持续产生自发批（每 5ms 观测步都排新的 ZERO 到期），批次计数无界增长。
        int baseline = v.processedBatchesForTest();
        for (int i = 0; i < 84; i++) {   // 420ms 观测
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
            ViewScreen.of(v);
        }
        int extra = v.processedBatchesForTest() - baseline;
        // 上界只容纳合法来源：回合仍在 THINKING（忙态）的 66ms 动画帧（420/66 ≈ 7 帧）
        // + 少量调度余量。旧实现 ZERO 链 = 每观测步一批 → 80+ 批，远超此界。
        assertTrue(extra <= 10,
                "静止残行后长观测窗内批次数必须有界（动画帧 + 余量；实际新增 "
                        + extra + " 批）——ZERO 延迟自续排链会持续产生批次（CPU/渲染线程自旋）");
        // 观测窗结束后仍静止：无任何 preview 排队。
        assertFalse(v.hasPendingPreviewScheduledForTest(),
                "长观测窗结束后不得有 preview 排队（静止残行无 demand）");

        // 下一个真 token 到达时 preview 链必须重新启动（不因修复而丢失唤醒）。
        // 观测窗已超过节流窗口，生产会 schedulePreview(ZERO)：future 可能在本线程断言前完成，
        // 因此不能要求它仍处于 pending。ZERO 到期的确定性可观察结果是二者之一：
        // future 尚在飞，或它已 publish VIEW dirty bits（等待下一 UI 批消费）。
        s.onAssistantToken(1L, " 新内容");
        v.runPendingUiUpdatesForTest();
        assertTrue(v.hasPendingPreviewScheduledForTest()
                        || v.coordinatorForTest().pendingDirtyBits() != 0,
                "新 token（未采纳内容出现）必须重新启动 preview：timer 尚在飞或到期 VIEW 已发布");
        assertTrue(ViewScreen.of(v).contains("新内容"),
                "节流窗口已过（观测 420ms）→ 新残行当帧采纳");
    }

    /** 残行清空立即 VIEW、不等节流（§10.1）：回合结束预览行马上消失。 */
    @Test
    @DisplayName("preview：残行清空立即更新（flush 后预览行立即消失）")
    void preview_emptyTailBypassesThrottleImmediately(@TempDir Path root) {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();

        s.onTurnStarted(1L);
        s.onAssistantToken(1L, "残行内容");
        v.runPendingUiUpdatesForTest();      // 首段可见
        assertTrue(ViewScreen.of(v).contains("残行内容"));

        // 回合结束 → flushStreaming 把残行定稿、streaming 清空 → render 的采纳分支
        // （curTail.isEmpty() → 立即清空）当帧生效，不等 150ms。
        s.onTurnComplete(1L);
        v.runPendingUiUpdatesForTest();
        String screen = ViewScreen.of(v);
        assertFalse(screen.contains("残行内容"),
                "残行清空必须立即清空预览（curTail.isEmpty() 分支绕过节流）：\n" + screen);
    }

    /** 回合结束后不再续排 preview（§10.1）：流式静止后无 pending preview 任务。 */
    @Test
    @DisplayName("preview：回合结束后不续排 preview 任务")
    void preview_noTaskAfterTurnCompletion(@TempDir Path root) throws Exception {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();

        s.onTurnStarted(1L);
        s.onAssistantToken(1L, "流式内容");
        v.runPendingUiUpdatesForTest();
        s.onTurnComplete(1L);                   // 残行定稿 → streaming 清空
        v.runPendingUiUpdatesForTest();

        // 等 preview 窗口与任何在飞任务全部到期。
        for (int i = 0; i < 30; i++) {
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
        }
        assertFalse(v.hasPendingPreviewScheduledForTest(),
                "回合结束（streaming 空）后不得续排 preview 任务");
        assertEquals(0, v.coordinatorForTest().pendingDirtyBits(), "静止后无脏位");
    }

    /**
     * 动画帧只在忙态接通（§10.3）：THINKING/RUNNING_TOOL/compacting/运行中后台任务
     * 存在时 updateAnimationDemand(true)；全部消失立即 false；空闲无 timer。
     */
    @Test
    @DisplayName("animation：忙态接通 66ms 帧续排，状态消失立即停止，空闲无 timer")
    void animation_demandDrivenOnlyWhileBusyStatesExist(@TempDir Path root) throws Exception {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();

        // 空闲静态：无动画 timer。
        assertFalse(v.hasPendingAnimationFrameForTest(), "空闲静态界面不得有动画 timer");

        // THINKING：动画接通。
        s.onTurnStarted(1L);
        v.runPendingUiUpdatesForTest();
        assertTrue(v.hasPendingAnimationFrameForTest(), "THINKING 必须接通动画帧");

        // RUNNING_TOOL：动画保持。
        s.onToolStarted(1L, "Read", "{}");
        v.runPendingUiUpdatesForTest();
        assertTrue(v.hasPendingAnimationFrameForTest(), "RUNNING_TOOL 必须保持动画帧");

        // 回合结束（状态消失）：立即取消动画 timer。
        s.onToolFinished(1L, "Read", "ok", true);
        s.onTurnComplete(1L);
        v.runPendingUiUpdatesForTest();
        // 等 follow-up 到期消费完（最多 66ms 的在飞帧）。
        for (int i = 0; i < 30 && v.hasPendingAnimationFrameForTest(); i++) {
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
        }
        assertFalse(v.hasPendingAnimationFrameForTest(), "忙态消失后动画 timer 必须立即取消");

        // 压缩中：动画再次接通。
        s.onCompactionStarted("手动");
        v.runPendingUiUpdatesForTest();
        assertTrue(v.hasPendingAnimationFrameForTest(), "compacting 必须接通动画帧");
        s.onCompactionFinished(0, 0);
        v.runPendingUiUpdatesForTest();
        for (int i = 0; i < 30 && v.hasPendingAnimationFrameForTest(); i++) {
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
        }
        assertFalse(v.hasPendingAnimationFrameForTest(), "压缩结束后动画必须停止");

        // 运行中后台任务：动画接通（面板行波光 + 耗时跳动）。
        s.onBackgroundTaskStarted("b1", "explore", "描述");
        v.runPendingUiUpdatesForTest();
        assertTrue(v.hasPendingAnimationFrameForTest(), "运行中后台任务必须接通动画帧");
        s.onBackgroundTaskFinished("b1", "结果", true);
        v.runPendingUiUpdatesForTest();
        for (int i = 0; i < 30 && v.hasPendingAnimationFrameForTest(); i++) {
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
        }
        assertFalse(v.hasPendingAnimationFrameForTest(), "后台任务全部结束后动画必须停止");
        assertEquals(0, v.coordinatorForTest().pendingDirtyBits(), "静止后无脏位");
    }

    /**
     * 动画帧推进 StatusBar 的 animTick（§10.3「恢复动态」）：
     * 忙态下每个动画帧批自增 animTick，波光/压缩条不再停在最后一帧。
     */
    @Test
    @DisplayName("animation：每个动画帧批自增 animTick（波光恢复动态）")
    void animation_framesAdvanceAnimTick(@TempDir Path root) throws Exception {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();

        s.onTurnStarted(1L);
        v.runPendingUiUpdatesForTest();
        long tick0 = v.animTickForTest();
        // 等若干动画帧到期并消费（每帧一批 → animTick++）。
        int guard = 0;
        while (v.animTickForTest() < tick0 + 3 && guard++ < 100) {
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
        }
        assertTrue(v.animTickForTest() >= tick0 + 3,
                "忙态动画帧必须推进 animTick（" + tick0 + " → " + v.animTickForTest() + "）");
    }

    /** 空闲静止观测：一段时间内没有任何批次自发出现（无 timer = 无周期唤醒）。 */
    @Test
    @DisplayName("idle：静止界面持续观测无任何自发批次（无动画/preview/continuation timer）")
    void idle_noSpontaneousBatchesOverObservationWindow(@TempDir Path root) throws Exception {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();
        int baseline = v.processedBatchesForTest();

        // 观测窗口 ≥ 3×动画帧周期 + preview 窗口：任何残留 timer 都会在此自发产生批次。
        for (int i = 0; i < 40; i++) {
            TimeUnit.MILLISECONDS.sleep(10);
            v.runPendingUiUpdatesForTest();
        }
        assertEquals(baseline, v.processedBatchesForTest(),
                "空闲静止界面在观测窗口内不得有任何自发批次");
        assertFalse(v.hasPendingAnimationFrameForTest(), "空闲不得有动画 timer");
        assertFalse(v.hasPendingPreviewScheduledForTest(), "空闲不得有 preview timer");
        assertFalse(v.hasContinuationScheduledForTest(), "空闲不得有 continuation timer");
    }

    // ── Task 8：resize settle 的按需验证（132ms generation 替换已由 Task 7 落地） ──

    /**
     * 宽度变化立即钉光标并安排一次性 settle；连续宽度变化只让<b>最新</b> generation
     * 的 settle 动作执行（重放只发生一次）。停稳失败（测试态 runner()==null，
     * replayAfterResize 直接降级返回）也必须在 finally 复位 parkCursorAtTop。
     */
    @Test
    @DisplayName("resize：立即钉光标，仅最新 settle generation 执行，失败也 finally 复位停放")
    void resize_settleGenerationReplacementAndCursorParkReset(@TempDir Path root) throws Exception {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();

        // 首个宽度变化：光标立即钉顶（不等 settle）。
        v.onWidthChangedForTest();
        assertTrue(v.parkCursorAtTop, "宽度变化事件必须立即钉光标到第 0 行（resize 窗口开启）");

        // 静默窗口内的后续宽度变化：替换 generation（旧的 settle 永不执行——coordinator
        // 侧语义已由 UiUpdateCoordinatorTest.replacingResizeSettleSuppressesStaleAction 钉，
        // 此处验证 View 侧链条在替换后仍收敛：最新一代到期执行、停放复位）。
        v.onWidthChangedForTest();          // 第二次（generation 2 替换 generation 1）
        assertTrue(v.parkCursorAtTop, "静默窗口内的后续宽度变化保持钉顶（settle 未到期）");

        // 等 132ms+ 让最新 generation 到期（settle 动作经 requestUiUpdate → 测试队列）。
        for (int i = 0; i < 40; i++) {
            TimeUnit.MILLISECONDS.sleep(5);
            v.runPendingUiUpdatesForTest();
        }
        // settle 动作已执行（测试态 replayAfterResize 因 runner==null 降级）——但 finally
        // 必须把 IME 光标锚点放回文本行：这是「settle 失败也复位停放」的钉。
        assertFalse(v.parkCursorAtTop,
                "settle 执行后（含 replay 降级/失败）必须在 finally 复位 parkCursorAtTop"
                        + "——否则 IME 光标锚点永久钉在边框上（用户实报「打字错位」）");
    }

    /** resize 后批次照常：settle 不影响事件驱动消费（回归钉）。 */
    @Test
    @DisplayName("resize：settle 之后事件驱动的输出消费照常")
    void resize_settleDoesNotDisturbEventDrivenBatches(@TempDir Path root) throws Exception {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root);
        v.startForTest();
        v.runPendingUiUpdatesForTest();

        v.onWidthChangedForTest();
        s.pushInfo("resize 之后的输出");
        v.runPendingUiUpdatesForTest();
        assertFalse(s.hasPendingOutput(), "settle 在飞时输出消费照常进行");
    }
}

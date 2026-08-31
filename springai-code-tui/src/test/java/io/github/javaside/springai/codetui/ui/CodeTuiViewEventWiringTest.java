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

        assertTrue(v.repeatingDrainScheduledForTest() == 0,
                "事件驱动后不得再安排 scheduleRepeating drain");
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

        UiUpdateCoordinator.UpdateResult r1 = v.processUpdatesForTest(UiDirty.ALL);
        assertTrue(r1.outputRemaining(), "5000 行一批打不完，必须声明 remaining");
        assertTrue(v.hasContinuationScheduledForTest(), "remaining 时 coordinator 必须已安排 continuation");

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
        assertFalse(v.hasContinuationScheduledForTest(), "排空后不得残留 continuation timer");
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
        assertEquals(0, v.repeatingDrainScheduledForTest(), "tickForTest 不得安排周期任务");
    }

    @Test
    @DisplayName("resize 路径：宽度变化事件仍走 ResizeEvent 处理；SIGWINCH 合并兜底不再每帧轮询")
    void resize_keepsEventPathWithoutPerFramePolling(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), noopHandler(), root);
        v.startForTest();
        assertFalse(v.widthPollingEveryFrameForTest(),
                "事件驱动后不得保留每帧宽度轮询（ResizeEvent 已主动通知）");
    }
}

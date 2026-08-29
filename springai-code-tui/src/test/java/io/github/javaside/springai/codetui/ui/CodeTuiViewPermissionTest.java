package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批面板纯状态断言：不起真实 TUI，直接喂 KeyEvent 给 View 的按键入口。
 *
 * <p><b>每个 pending 请求背后都 park 着一个工具线程，而它持着回合</b>——故本类不只测「选项映射对不对」，
 * 更要测「面板不存在既不应答也不取消的状态」：外部取消后面板必须自己退出（{@link #externalCancelDropsPanel}）、
 * 未识别按键不得把面板卡住也不得落进输入框（{@link #unknownKeysAreSwallowed}）。
 */
class CodeTuiViewPermissionTest {

    private static final PermissionRule SUGGESTED =
            PermissionRule.parse("Bash(git push:*)", PermissionBehavior.ALLOW, RuleScope.SESSION);

    /** 带建议规则的请求：面板给全部五个选项。 */
    private static PermissionRequest perm(long turnId, List<PermissionOutcome> sink) {
        return new PermissionRequest(turnId, null, "Bash", "git push origin main",
                "{\"command\":\"git push origin main\"}", "第 1 段 `git push origin main` 未获授权",
                SUGGESTED, sink::add);
    }

    /** 无建议规则的请求（内置危险检查 / ask 规则）：面板只给三个选项。 */
    private static PermissionRequest permNoRule(long turnId, List<PermissionOutcome> sink) {
        return new PermissionRequest(turnId, null, "Write", "/Users/x/.ssh/config",
                "{\"filePath\":\"/Users/x/.ssh/config\"}", "写入 SSH 配置目录（内置检查，无法用规则豁免）",
                null, sink::add);
    }

    private static CodeTuiView view(ConversationState state, Path root) {
        return new CodeTuiView(state, new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
        }, root);
    }

    /** 建好一个「已开回合 + 队首一个审批请求 + 已进模态」的视图。 */
    private static CodeTuiView inModal(ConversationState state, PermissionRequest req, Path root) {
        state.onTurnStarted(1L);
        state.onPermissionRequested(1L, req);
        CodeTuiView v = view(state, root);
        v.tickForTest();
        return v;
    }

    private static KeyEvent enter() { return KeyEvent.ofKey(KeyCode.ENTER); }

    @Test
    @DisplayName("drain 侦测到队首审批请求即进入审批模态")
    void entersPermissionModal(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, perm(1L, sink), root);

        assertNotNull(v.activePermissionForTest(), "应进入审批模态");
        assertTrue(v.permStatusText().contains("等待授权"), "状态行应提示等待授权，实际：" + v.permStatusText());
    }

    @Test
    @DisplayName("Enter 确认默认项「允许一次」")
    void allowOnce(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, perm(1L, sink), root);

        v.feedKeyForTest(enter());

        assertEquals(List.of(PermissionOutcome.ALLOW_ONCE), sink);
        assertNull(v.activePermissionForTest(), "应答后退出模态");
        assertNull(state.peekModal(), "并从队列摘除");
    }

    @Test
    @DisplayName("↑↓ 移动高亮：↓ 一次落到「本会话不再问」")
    void arrowsMoveHighlight(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, perm(1L, sink), root);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        assertTrue(sink.isEmpty(), "移动高亮不应应答");
        v.feedKeyForTest(enter());

        assertEquals(List.of(PermissionOutcome.ALLOW_SESSION), sink);
    }

    @Test
    @DisplayName("↑ 环绕到最后一项「拒绝并中断本回合」")
    void upWrapsToLastOption(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, perm(1L, sink), root);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));
        v.feedKeyForTest(enter());

        assertEquals(List.of(PermissionOutcome.CANCEL), sink);
    }

    @Test
    @DisplayName("数字键 1–5 只移动高亮（不隐式确认，仍需 Enter）")
    void digitsMoveHighlight(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, perm(1L, sink), root);

        v.feedKeyForTest(KeyEvent.ofChar('4'));
        assertTrue(sink.isEmpty(), "数字键只移动高亮，不应直接应答");

        v.feedKeyForTest(enter());
        assertEquals(List.of(PermissionOutcome.DENY), sink);
    }

    @Test
    @DisplayName("数字 3 = 允许，永久：面板只负责应答，**不**自己打确认行")
    void allowAlwaysOnlyResponds(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, perm(1L, sink), root);

        v.feedKeyForTest(KeyEvent.ofChar('3'));
        v.feedKeyForTest(enter());

        assertEquals(List.of(PermissionOutcome.ALLOW_ALWAYS), sink);
        // 应答只是唤醒工具线程，写盘在它醒来之后才做。面板此刻打任何完成时描述都早于事实，
        // 且写盘失败也无从更正（PermissionOutcome 没有回传通道）——所以这里必须什么都不打。
        assertFalse(drained(state).contains("已记下"),
                "面板不该在写盘发生之前就宣布「已记下」");
    }

    @Test
    @DisplayName("规则记录结果由工具线程回报：成功走信息行、失败走醒目的错误行")
    void ruleRecordedIsReportedByToolThread() {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);

        state.onRuleRecorded(1L, true, "✓ 已记下允许规则：Bash(git push:*) → 已写入 /p/.codetui/permissions.json");
        String ok = drained(state);
        assertTrue(ok.contains("已写入"), "成功要说清落点，实际：" + ok);

        state.onRuleRecorded(1L, false, "⚠ 规则 Bash(x:*) 写盘失败（/p/.codetui/permissions.json），仅本次会话生效");
        String bad = drained(state);
        assertTrue(bad.contains("写盘失败"), "失败要说明白，实际：" + bad);
    }

    @Test
    @DisplayName("选「5. 拒绝并中断本回合」→ CANCEL；Esc 同义")
    void cancelTurn(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, perm(1L, sink), root);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertEquals(List.of(PermissionOutcome.CANCEL), sink);
        assertNull(v.activePermissionForTest(), "取消后应退出模态");
        assertTrue(state.isIdle(), "中断后回合应回 IDLE");
        assertEquals("已取消当前回合", state.notice());
    }

    @Test
    @DisplayName("选 5 与 Esc 走同一条中断路径")
    void fifthOptionCancelsToo(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, perm(1L, sink), root);

        v.feedKeyForTest(KeyEvent.ofChar('5'));
        v.feedKeyForTest(enter());

        assertEquals(List.of(PermissionOutcome.CANCEL), sink);
        assertTrue(state.isIdle(), "选 5 也必须结束回合");
    }

    @Test
    @DisplayName("拒绝（选 4）不取消回合——与中断严格区分")
    void denyKeepsTurnRunning(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, perm(1L, sink), root);

        v.feedKeyForTest(KeyEvent.ofChar('4'));
        v.feedKeyForTest(enter());

        assertEquals(List.of(PermissionOutcome.DENY), sink);
        assertFalse(state.isIdle(), "拒绝不该结束回合（回合继续，模型自寻替代）");
        assertEquals("", state.notice(), "拒绝不是取消，不该打「已取消当前回合」");
    }

    // ── suggested() == null：那两个「不再问」的选项做不到它们承诺的事，必须隐藏 ──

    @Test
    @DisplayName("无建议规则时只有三个选项（隐藏「本会话」「永久」）")
    void noSuggestionHidesRememberOptions() {
        List<PermissionOutcome> five = CodeTuiView.permOptions(perm(1L, new CopyOnWriteArrayList<>()))
                .stream().map(CodeTuiView.PermOption::outcome).toList();
        assertEquals(List.of(PermissionOutcome.ALLOW_ONCE, PermissionOutcome.ALLOW_SESSION,
                PermissionOutcome.ALLOW_ALWAYS, PermissionOutcome.DENY, PermissionOutcome.CANCEL), five);

        List<PermissionOutcome> three = CodeTuiView.permOptions(permNoRule(1L, new CopyOnWriteArrayList<>()))
                .stream().map(CodeTuiView.PermOption::outcome).toList();
        assertEquals(List.of(PermissionOutcome.ALLOW_ONCE, PermissionOutcome.DENY, PermissionOutcome.CANCEL),
                three, "suggested()==null 时「不再问」两项会被 PermissionCallback 降级成「仅这次」，"
                        + "显示出来就是骗人（用户按了「不再询问」下次照样被问）");
    }

    @Test
    @DisplayName("少了「本会话/永久」两项时，面板必须说明原因——否则看着像漏了选项")
    void noSuggestionExplainsWhyOptionsAreMissing(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = inModal(state, permNoRule(1L, new CopyOnWriteArrayList<>()), root);

        // 断言落在<b>渲染结果</b>上：这一行是否出现在屏幕上取决于 permissionChildren 的分支，
        // 只测 permOptions 看不见它（用户的困惑正来自「面板上什么都没说」）。
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("这类调用不提供「本会话 / 永久」"),
                "三选项形态必须解释为什么少了两项，实际屏幕：\n" + screen);
        assertFalse(screen.contains("允许后将记下规则"),
                "无建议规则时不得出现「允许后将记下规则」，那会凭空造出一条不存在的规则");

        // 反向对照：有建议规则时不该出现这句说明（否则它就成了一句人人可见的废话）
        ConversationState s2 = new ConversationState();
        CodeTuiView v2 = inModal(s2, perm(1L, new CopyOnWriteArrayList<>()), root);
        String screen2 = ViewScreen.of(v2);
        assertTrue(screen2.contains("允许后将记下规则"), "五选项形态应显示建议规则，实际屏幕：\n" + screen2);
        assertFalse(screen2.contains("这类调用不提供"), "有建议规则时不该说「不提供」");
    }

    @Test
    @DisplayName("无建议规则时 2 = 拒绝、3 = 中断，且任何按键都到不了 ALLOW_SESSION/ALLOW_ALWAYS")
    void noSuggestionRemapsDigits(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, permNoRule(1L, sink), root);

        v.feedKeyForTest(KeyEvent.ofChar('2'));
        v.feedKeyForTest(enter());
        assertEquals(List.of(PermissionOutcome.DENY), sink, "隐藏两项后，2 就是拒绝");

        ConversationState s2 = new ConversationState();
        List<PermissionOutcome> sink2 = new CopyOnWriteArrayList<>();
        CodeTuiView v2 = inModal(s2, permNoRule(1L, sink2), root);
        v2.feedKeyForTest(KeyEvent.ofChar('3'));
        v2.feedKeyForTest(enter());
        assertEquals(List.of(PermissionOutcome.CANCEL), sink2, "隐藏两项后，3 就是中断");

        // 越界数字不得移动高亮（否则会撞上不存在的选项）
        ConversationState s3 = new ConversationState();
        List<PermissionOutcome> sink3 = new CopyOnWriteArrayList<>();
        CodeTuiView v3 = inModal(s3, permNoRule(1L, sink3), root);
        v3.feedKeyForTest(KeyEvent.ofChar('4'));
        v3.feedKeyForTest(KeyEvent.ofChar('5'));
        v3.feedKeyForTest(enter());
        assertEquals(List.of(PermissionOutcome.ALLOW_ONCE), sink3, "4/5 越界应被忽略，高亮仍在第 1 项");
    }

    // ── 活性：面板不得停在「既不应答也不取消」的状态 ──

    @Test
    @DisplayName("未识别按键被吞掉：面板留着（仍可应答），且不落进输入框")
    void unknownKeysAreSwallowed(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, perm(1L, sink), root);

        for (char c : "x9 \t".toCharArray()) v.feedKeyForTest(KeyEvent.ofChar(c));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.BACKSPACE));

        assertEquals("", v.inputTextForTest(), "审批期按键不得落进输入框");
        assertNotNull(v.activePermissionForTest(), "面板仍在，用户仍能应答");
        assertTrue(sink.isEmpty(), "未识别按键不应产生应答");
        v.feedKeyForTest(enter());
        assertEquals(List.of(PermissionOutcome.ALLOW_ONCE), sink, "吞键之后仍能正常应答");
    }

    @Test
    @DisplayName("外部取消（cancelCurrent 已唤醒线程）后，下一 tick 面板必须自己退出")
    void externalCancelDropsPanel(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, perm(1L, sink), root);
        assertNotNull(v.activePermissionForTest());

        state.cancelCurrent();          // 别的路径（如 dispose/回合出错）清空了队列并唤醒了线程
        v.tickForTest();

        assertEquals(List.of(PermissionOutcome.CANCEL), sink, "线程已由 clearModals 唤醒");
        assertNull(v.activePermissionForTest(), "面板不能继续挂着一个没人在等的请求");
    }

    @Test
    @DisplayName("外部取消后同一 tick 内即可接上新请求（不吞掉后来的模态）")
    void panelPicksUpNextRequestAfterExternalCancel(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inModal(state, perm(1L, sink), root);

        state.cancelCurrent();
        state.onTurnStarted(2L);
        PermissionRequest next = perm(2L, sink);
        state.onPermissionRequested(2L, next);
        v.tickForTest();

        assertSame(next, v.activePermissionForTest(), "新回合的请求应被接上（认引用，不认相等）");
    }

    @Test
    @DisplayName("两个审批请求依次弹出（第一个处理完才轮到第二个）")
    void queuedModalsPopInOrder(@TempDir Path root) {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        state.onPermissionRequested(1L, perm(1L, sink));
        state.onPermissionRequested(1L, perm(1L, sink));

        CodeTuiView v = view(state, root);
        v.tickForTest();
        v.feedKeyForTest(enter());   // 批第一个
        assertEquals(1, sink.size());

        v.tickForTest();             // 下一 tick 弹第二个
        assertNotNull(v.activePermissionForTest());
        v.feedKeyForTest(enter());
        assertEquals(2, sink.size());
    }

    @Test
    @DisplayName("队列溢出（第 9 个被自动拒绝）必须留下用户看得见的一行")
    void overflowIsVisible(@TempDir Path root) {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        for (int i = 0; i < ConversationState.MODAL_QUEUE_CAP; i++) {
            state.onPermissionRequested(1L, perm(1L, sink));
        }
        assertTrue(drained(state).isEmpty(), "前 8 个正常入队，不该有告警");

        state.onPermissionRequested(1L, perm(1L, sink));

        assertEquals(List.of(PermissionOutcome.DENY), sink, "第 9 个被自动拒绝");
        String all = drained(state);
        assertTrue(all.contains("Bash"), "溢出告警应写明被拒的工具，实际：" + all);
        assertTrue(all.contains("拒绝"), "用户必须看得见「有个工具被悄悄拒了」，实际：" + all);
    }

    @Test
    @DisplayName("渲染冒烟：非审批态构造 UI 树不崩（scope 每帧 eager 求值）")
    void renderSmokeWhenNoModal(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root);
        assertNotNull(v.renderForTest(), "非审批态每帧仍会调用面板方法，首行必须判空");
    }

    @Test
    @DisplayName("渲染冒烟：审批态构造 UI 树不崩（含无建议规则的三选项形态）")
    void renderSmokeInModal(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = inModal(state, perm(1L, new CopyOnWriteArrayList<>()), root);
        assertNotNull(v.renderForTest());

        ConversationState s2 = new ConversationState();
        CodeTuiView v2 = inModal(s2, permNoRule(1L, new CopyOnWriteArrayList<>()), root);
        assertNotNull(v2.renderForTest());
    }

    private static String drained(ConversationState state) {
        return String.join("\n", state.drainPending().stream().map(ConversationState.OutputLine::text).toList());
    }
}

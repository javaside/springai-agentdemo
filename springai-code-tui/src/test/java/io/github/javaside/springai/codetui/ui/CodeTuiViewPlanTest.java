package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.PlanOutcome;
import io.github.javaside.springai.codetui.agent.PlanRequest;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划审批面板纯状态断言：不起真实 TUI，直接喂 KeyEvent 给 View 的按键入口。
 *
 * <p><b>本类首先是一张捕捉网</b>：{@code drain} 里的模态分派是 {@code instanceof} 链
 * （release=17 用不了类型模式 switch），漏一个分支既不是编译错误也没有别的测试会报警——
 * 而漏掉 {@link PlanRequest} 的后果是面板永不弹出、工具线程持着回合永久 park，agent 静默挂死。
 * {@link #panelShowsThreeOptions} 断言在<b>渲染结果</b>上，正是为了钉住这条链。
 */
class CodeTuiViewPlanTest {

    private record Answer(PlanOutcome outcome, String feedback) {}

    /** 记录型 scrollback 接缝：真实运行时是 {@code runner().println}，这里收进内存列表供断言。 */
    private static final class RecordingSink implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(Text line)   { lines.add(line.rawContent()); }
        @Override public void println(String line) { lines.add(line); }
    }

    private static KeyEvent enter() { return KeyEvent.ofKey(KeyCode.ENTER); }

    private static PlanRequest req(List<Answer> sink) {
        return new PlanRequest(1L, "# 计划\n\n- 第一步\n- 第二步",
                (o, f) -> sink.add(new Answer(o, f)));
    }

    private static CodeTuiView view(ConversationState state, Path root, RecordingSink sink) {
        return new CodeTuiView(state, new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
        }, root, sink);
    }

    /** 建好一个「已开回合 + 队首一个计划请求 + 已进模态」的视图。 */
    private static CodeTuiView inPlanModal(ConversationState state, PlanRequest r, Path root) {
        return inPlanModal(state, r, root, new RecordingSink());
    }

    private static CodeTuiView inPlanModal(ConversationState state, PlanRequest r, Path root, RecordingSink sink) {
        state.onTurnStarted(1L);
        state.onPlanSubmitted(1L, r);
        CodeTuiView v = view(state, root, sink);
        v.tickForTest();          // 让 drain 侦测到队首模态并进入模态态
        return v;
    }

    @Test
    @DisplayName("面板有标题和三个选项——这条同时钉住 instanceof 分派链没漏 PlanRequest 分支")
    void panelShowsThreeOptions(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = inPlanModal(state, req(new CopyOnWriteArrayList<>()), root);

        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("批准，自动接受编辑"), "缺选项一（多半是模态分派漏了 PlanRequest 分支）：\n" + screen);
        assertTrue(screen.contains("批准，逐个确认"), "缺选项二：\n" + screen);
        assertTrue(screen.contains("继续完善计划"), "缺选项三：\n" + screen);
    }

    @Test
    @DisplayName("计划正文进 scrollback（走既有 markdown 路径），且逐行拆——不塞进面板")
    void planBodyGoesToScrollbackOneLinePerPhysicalLine(@TempDir Path root) {
        RecordingSink sink = new RecordingSink();
        ConversationState state = new ConversationState();
        CodeTuiView v = inPlanModal(state, req(new CopyOnWriteArrayList<>()), root, sink);

        assertEquals(1, sink.lines.stream().filter(l -> l.contains("第一步")).count(),
                "计划正文应下沉 scrollback，实际：" + sink.lines);
        assertEquals(1, sink.lines.stream().filter(l -> l.contains("第二步")).count(),
                "实际：" + sink.lines);
        assertTrue(sink.lines.stream().noneMatch(l -> l.contains("第一步") && l.contains("第二步")),
                "一个 println = 一个物理行：多行计划必须按 \\n 逐行拆，实际：" + sink.lines);

        // 面板只放标题 + 三个选项：几十行的计划塞进行内面板会把输入框顶出屏幕。
        String screen = ViewScreen.of(v);
        assertFalse(screen.contains("第一步"), "计划正文不该进面板：\n" + screen);
    }

    @Test
    @DisplayName("1/2 快选 + Enter → 对应的批准结果")
    void approveOptions(@TempDir Path root) {
        List<Answer> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inPlanModal(new ConversationState(), req(sink), root);
        v.feedKeyForTest(KeyEvent.ofChar('1'));
        v.feedKeyForTest(enter());
        assertEquals(PlanOutcome.APPROVE_ACCEPT_EDITS, sink.get(0).outcome());

        List<Answer> sink2 = new CopyOnWriteArrayList<>();
        CodeTuiView v2 = inPlanModal(new ConversationState(), req(sink2), root);
        v2.feedKeyForTest(KeyEvent.ofChar('2'));
        v2.feedKeyForTest(enter());
        assertEquals(PlanOutcome.APPROVE_DEFAULT, sink2.get(0).outcome());
    }

    @Test
    @DisplayName("应答后退出模态并从队列摘除（否则 drain 下一 tick 会重进、重复应答）")
    void answeringLeavesModal(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<Answer> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inPlanModal(state, req(sink), root);
        assertNotNull(v.activePlanForTest(), "应先进入计划模态");

        v.feedKeyForTest(enter());

        assertEquals(1, sink.size());
        assertNull(v.activePlanForTest(), "应答后退出模态");
        assertNull(state.peekModal(), "并从队列摘除");
        v.tickForTest();
        assertEquals(1, sink.size(), "下一 tick 不得重复应答");
        assertNull(v.activePlanForTest(), "下一 tick 不得重进模态");
    }

    @Test
    @DisplayName("数字键只移动高亮，不隐式确认")
    void digitsDoNotConfirm(@TempDir Path root) {
        List<Answer> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inPlanModal(new ConversationState(), req(sink), root);
        v.feedKeyForTest(KeyEvent.ofChar('2'));
        assertTrue(sink.isEmpty(), "数字键不该直接应答");
        v.feedKeyForTest(enter());
        assertEquals(1, sink.size());
        assertEquals(PlanOutcome.APPROVE_DEFAULT, sink.get(0).outcome());
    }

    @Test
    @DisplayName("↑↓ 移动高亮并环绕：↑ 一次落到第三项「继续完善计划」")
    void arrowsWrapAround(@TempDir Path root) {
        List<Answer> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inPlanModal(new ConversationState(), req(sink), root);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));
        assertTrue(sink.isEmpty(), "移动高亮不应应答");
        v.feedKeyForTest(enter());
        assertTrue(sink.isEmpty(), "第三项应先收反馈，不能立刻应答");
        v.feedKeyForTest(enter());
        assertEquals(PlanOutcome.KEEP_PLANNING, sink.get(0).outcome());
    }

    @Test
    @DisplayName("选「继续完善」→ 进自由文本子模式，打字后 Enter 把反馈带回")
    void keepPlanningCollectsFeedback(@TempDir Path root) {
        List<Answer> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inPlanModal(new ConversationState(), req(sink), root);

        v.feedKeyForTest(KeyEvent.ofChar('3'));
        v.feedKeyForTest(enter());
        assertTrue(sink.isEmpty(), "选第三项后应先收反馈，不能立刻应答");

        for (char c : "补上回滚".toCharArray()) {
            v.feedKeyForTest(KeyEvent.ofChar(c));
        }
        assertTrue(ViewScreen.of(v).contains("补上回滚"), "子模式应回显已输入的反馈：\n" + ViewScreen.of(v));
        assertEquals("", v.inputTextForTest(), "反馈按键不得落进输入框");

        v.feedKeyForTest(enter());

        assertEquals(PlanOutcome.KEEP_PLANNING, sink.get(0).outcome());
        assertEquals("补上回滚", sink.get(0).feedback());
    }

    @Test
    @DisplayName("子模式 Esc 只退回选项态（面板留着，仍能应答）——不是死胡同也不中断回合")
    void escInFeedbackReturnsToOptions(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<Answer> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inPlanModal(state, req(sink), root);

        v.feedKeyForTest(KeyEvent.ofChar('3'));
        v.feedKeyForTest(enter());
        v.feedKeyForTest(KeyEvent.ofChar('x'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertTrue(sink.isEmpty(), "退回选项态不是应答");
        assertNotNull(v.activePlanForTest(), "面板必须还在");
        assertFalse(state.isIdle(), "退回选项态不该结束回合");
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("批准，自动接受编辑"), "应回到三选项形态：\n" + screen);
        assertFalse(screen.contains("x"), "退回时应清掉已输入的反馈：\n" + screen);

        // 退回后高亮仍停在第三项（原地返回，不跳位）：Enter 会再进子模式，选 1 才是批准。
        v.feedKeyForTest(enter());
        assertTrue(sink.isEmpty(), "第三项 Enter 仍是进子模式");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        v.feedKeyForTest(KeyEvent.ofChar('1'));
        v.feedKeyForTest(enter());   // 仍能正常应答
        assertEquals(PlanOutcome.APPROVE_ACCEPT_EDITS, sink.get(0).outcome());
    }

    @Test
    @DisplayName("Esc = 中断本回合：应答 CANCEL 且回合结束（不能只 responder.cancel）")
    void escCancelsTurn(@TempDir Path root) {
        List<Answer> sink = new CopyOnWriteArrayList<>();
        ConversationState state = new ConversationState();
        CodeTuiView v = inPlanModal(state, req(sink), root);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertEquals(PlanOutcome.CANCEL, sink.get(0).outcome());
        assertNull(v.activePlanForTest(), "取消后应退出模态");
        assertTrue(state.isIdle(), "Esc 必须走 cancelTurnFor 结束回合，否则残留悬空 tool_calls → 下条 400");
        assertEquals("已取消当前回合", state.notice());
    }

    @Test
    @DisplayName("任何按键都不得让面板停在「既不应答也不取消」的状态")
    void noDeadEnd(@TempDir Path root) {
        List<Answer> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inPlanModal(new ConversationState(), req(sink), root);

        for (char c : "xyz789".toCharArray()) {      // 越界数字与无关字符
            v.feedKeyForTest(KeyEvent.ofChar(c));
        }
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.BACKSPACE));
        assertTrue(sink.isEmpty(), "无关键不该隐式应答");
        assertEquals("", v.inputTextForTest(), "审批期按键不得落进输入框");

        v.feedKeyForTest(enter());
        assertEquals(1, sink.size(), "Enter 之后必须恰好应答一次");
        assertEquals(PlanOutcome.APPROVE_ACCEPT_EDITS, sink.get(0).outcome(),
                "越界数字 7/8/9 不该移动高亮（会撞上不存在的选项）");
    }

    @Test
    @DisplayName("外部取消（cancelCurrent 已唤醒线程）后，下一 tick 面板必须自己退出")
    void externalCancelDropsPanel(@TempDir Path root) {
        ConversationState state = new ConversationState();
        List<Answer> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inPlanModal(state, req(sink), root);

        state.cancelCurrent();      // 别的路径（dispose / 回合出错）清空队列并唤醒了线程
        v.tickForTest();

        assertEquals(PlanOutcome.CANCEL, sink.get(0).outcome(), "线程已由 clearModals 唤醒");
        assertNull(v.activePlanForTest(), "面板不能继续挂着一个没人在等的请求");
    }

    @Test
    @DisplayName("非计划态每帧仍会调用面板方法，首行必须判空（scope 是 eager 求值）")
    void renderSmokeWhenNotInPlanModal(@TempDir Path root) {
        CodeTuiView v = view(new ConversationState(), root, new RecordingSink());
        assertTrue(ViewScreen.of(v).length() > 0, "非模态态渲染不应抛异常");
    }
}

package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.goal.GoalConfig;
import io.github.javaside.springai.codetui.agent.goal.GoalManager;
import io.github.javaside.springai.codetui.agent.goal.GoalPhase;
import io.github.javaside.springai.codetui.agent.goal.GoalStateSnapshot;
import io.github.javaside.springai.codetui.agent.goal.GoalVerdict;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /goal} 命令面 + 状态栏 leading 指示 + 面板（Task 10）。
 *
 * <p>断言一律是「含某某子串」级别的<b>行为断言</b>（计划 T10 ruling：文案以实现为准）——
 * 钉的是「该说的说了、该挡的挡了、状态机真的动了」，不钉逐字文案。
 *
 * <p>桩策略：SubmitHandler 桩只覆写 {@code goal()} 返回<b>真</b> GoalManager（new GoalManager(config, null)），
 * 其余全部走接口默认实现——与生产同一条状态机代码路径，测的才不是镜像。
 * 面板正文测试直接喂手工构造的 {@link GoalStateSnapshot}（快照是公共 record），
 * 因为 Manager 的 recentTraces 投影尚未接线（Task 5 起 deferred），真 Manager 永远吐空轨迹。
 */
class CodeTuiViewGoalCommandTest {

    /** goal 桩：真 GoalManager + 默认 DEFAULT 权限档（接口默认值，循环会停下等批准的那档）。 */
    private static final class GoalHandler implements SubmitHandler {
        final GoalManager gm = new GoalManager(GoalConfig.from(k -> null), null);
        @Override public Disposable submit(String text) { return null; }
        @Override public GoalManager goal() { return gm; }
    }

    private static void submit(CodeTuiView v, String line) {
        v.setInputForTest(line);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    /** 测试态无批消费，pushInfo 停在 state.pending，直接排空取正文（CodeTuiViewClearTest 先例）。 */
    private static List<String> drain(ConversationState s) {
        List<String> out = new ArrayList<>();
        for (ConversationState.OutputLine ol; (ol = s.pollPending()) != null; ) out.add(ol.text());
        return out;
    }

    private static boolean anyContains(List<String> lines, String needle) {
        return lines.stream().anyMatch(l -> l.contains(needle));
    }

    @Test
    @DisplayName("/goal <条件>：activate 被调、状态立起、回显含条件；DEFAULT 档附 Shift+Tab 提示")
    void goalWithConditionActivates(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root);

        submit(v, "/goal 迁移完成且测试全绿");

        assertEquals(GoalPhase.RUNNING, h.gm.phase(), "INACTIVE 下唯有 activate 能立起 RUNNING");
        assertTrue(h.gm.hasAutoTurnPending(), "activate 应置首轮自动轮 pending");
        List<String> lines = drain(s);
        assertTrue(anyContains(lines, "goal 已设定") && anyContains(lines, "迁移完成且测试全绿"),
                "应回显设定成功与条件原文，实际：" + lines);
        assertTrue(anyContains(lines, "Shift+Tab"),
                "DEFAULT 档应提示切档（循环会停下等批准），实际：" + lines);
    }

    @Test
    @DisplayName("/goal 后跟空白：不出面板、不 activate，给 notice 且 phase 不变")
    void blankConditionRejectedWithNotice(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root);

        submit(v, "/goal    ");          // strip 后即裸 "/goal"，rest 为空

        assertEquals(GoalPhase.INACTIVE, h.gm.phase(), "空白条件不得 activate");
        assertFalse(s.notice().isEmpty(), "应给用法提示，实际：\"" + s.notice() + "\"");
        List<String> lines = drain(s);
        assertTrue(lines.isEmpty(), "INACTIVE 不该铺开面板正文，实际：" + lines);
    }

    @Test
    @DisplayName("/goal 面板：条件/RUNNING/N/M/stalled/逐条轨迹一行一条")
    void goalAlonePrintsPanel(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root);

        // 手工喂快照：真 Manager 的 recentTraces 投影未接线（恒空表），轨迹行只有喂快照才可见
        GoalStateSnapshot snap = new GoalStateSnapshot(
                GoalPhase.RUNNING, null, "迁移完成且测试全绿",
                3, 25, 1234L, 5_000_000L, 2,
                Instant.now().minusSeconds(90),
                List.of(new GoalStateSnapshot.GoalEvalTrace(2, "UNSATISFIED", "还差两个模块"),
                        new GoalStateSnapshot.GoalEvalTrace(3, "UNSATISFIED", "测试未全绿")),
                "已完成 user 模块");
        v.printGoalPanel(snap);

        List<String> lines = drain(s);
        assertTrue(anyContains(lines, "迁移完成且测试全绿"), "条件原文要在，实际：" + lines);
        assertTrue(anyContains(lines, "RUNNING"), "活动态要写 RUNNING，实际：" + lines);
        assertTrue(anyContains(lines, "3/25"), "轮次要写 N/M，实际：" + lines);
        assertTrue(anyContains(lines, "stalled"), "停滞连击要在，实际：" + lines);
        assertTrue(anyContains(lines, "1234"), "token 消耗要在，实际：" + lines);
        assertTrue(anyContains(lines, "2. UNSATISFIED — 还差两个模块"),
                "轨迹行格式 N. VERDICT — reason，实际：" + lines);
        assertTrue(anyContains(lines, "3. UNSATISFIED — 测试未全绿"), "两条轨迹都在，实际：" + lines);
        assertTrue(anyContains(lines, "已完成 user 模块"), "最近结论（该次报告）要在，实际：" + lines);
    }

    @Test
    @DisplayName("/goal stop 与 /goal clear 都转 CLEARED 并回显被清条件")
    void goalStopClearsAndEchoes(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root);

        submit(v, "/goal 第一个条件");
        drain(s);                                       // 清掉设定回显，别污染下一段断言
        submit(v, "/goal stop");
        assertEquals(GoalPhase.CLEARED, h.gm.phase(), "stop 应清空");
        List<String> stopLines = drain(s);
        assertTrue(anyContains(stopLines, "goal 已清除") && anyContains(stopLines, "第一个条件"),
                "stop 应回显被清条件，实际：" + stopLines);

        h.gm.activate("第二个条件");                     // activate 可从任意相态重设
        submit(v, "/goal clear");
        assertEquals(GoalPhase.CLEARED, h.gm.phase(), "clear 与 stop 同效");
        List<String> clearLines = drain(s);
        assertTrue(anyContains(clearLines, "第二个条件"), "clear 同样回显，实际：" + clearLines);
    }

    @Test
    @DisplayName("M4 终态下 /goal stop：clear 是 no-op，打「没有进行中的 goal」而非谎报「已清除」")
    void goalStopOnTerminalPhaseGivesNoticeNotFalseClear(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root);

        // 终态一：SATISFIED（自然终局——循环已结束，无事可清）
        h.gm.activate("目标 A");
        h.gm.onVerdict(h.gm.currentEpoch(),
                new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "全部完成", false, null, ""));
        assertEquals(GoalPhase.SATISFIED, h.gm.phase(), "前置：终态");
        submit(v, "/goal stop");
        assertEquals(GoalPhase.SATISFIED, h.gm.phase(), "clear 对终态 no-op：相位不得被改写");
        List<String> lines = drain(s);
        assertTrue(lines.isEmpty(), "不得谎报「已清除」，实际：" + lines);
        assertFalse(s.notice().isEmpty(), "应打 notice「没有进行中的 goal」，实际：\"" + s.notice() + "\"");
        assertTrue(s.notice().contains("没有进行中的 goal"), "notice 文案，实际：" + s.notice());

        // 终态报告面板仍可裸 /goal 查看（stop 的 notice 不代表报告消失）
        s.setNotice("");
        submit(v, "/goal");
        List<String> panel = drain(s);
        assertTrue(anyContains(panel, "目标 A") && anyContains(panel, "SATISFIED"),
                "终态报告面板仍可 /goal 查看，实际：" + panel);

        // 终态二：CLEARED（已清过再 stop——同样 no-op + notice）
        h.gm.activate("目标 B");
        h.gm.clear("stop");
        assertEquals(GoalPhase.CLEARED, h.gm.phase());
        submit(v, "/goal stop");
        assertEquals(GoalPhase.CLEARED, h.gm.phase());
        assertTrue(drain(s).isEmpty(), "CLEARED 下 stop 同样不得谎报已清除");
        assertTrue(s.notice().contains("没有进行中的 goal"), "CLEARED 下同样给 notice，实际：" + s.notice());
    }

    @Test
    @DisplayName("终态报告存活到下一个 goal：SATISFIED 后 /goal 仍见该次结论；重设后旧结论退场")
    void terminalReportSurvivesUntilNextGoal(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root);

        h.gm.activate("目标 A");
        h.gm.onVerdict(h.gm.currentEpoch(),
                new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "全部测试通过", false, null, ""));
        assertEquals(GoalPhase.SATISFIED, h.gm.phase(), "前置：已进终态");

        submit(v, "/goal");
        List<String> lines = drain(s);
        assertTrue(anyContains(lines, "目标 A"), "终态面板仍显示该次条件，实际：" + lines);
        assertTrue(anyContains(lines, "SATISFIED"), "终态相位要在，实际：" + lines);
        assertTrue(anyContains(lines, "全部测试通过"), "终态结论（报告）要在，实际：" + lines);

        h.gm.activate("目标 B");                          // 下一个 goal：旧报告不得阴魂不散
        submit(v, "/goal");
        List<String> fresh = drain(s);
        assertTrue(anyContains(fresh, "目标 B") && anyContains(fresh, "RUNNING"),
                "新 goal 面板要立起来，实际：" + fresh);
        assertFalse(anyContains(fresh, "全部测试通过"), "旧结论必须退场，实际：" + fresh);
    }

    @Test
    @DisplayName("状态栏 leading：INACTIVE→null；RUNNING N/M；倒计时 ⏳；评估中；PAUSED 已暂停")
    void statusLineLeadingSpan(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root);

        assertNull(v.goalLeadingSpan(), "未启用/INACTIVE：不占位（modeTag 同纪律）");

        h.gm.activate("推进迁移");
        assertEquals("◎ goal 0/25 · ", v.goalLeadingSpan().content(), "RUNNING：N/M");

        h.gm.takeAutoTurn();                              // 首轮发走：turnsUsed=1
        assertEquals("◎ goal 1/25 · ", v.goalLeadingSpan().content());

        h.gm.beginEvaluation();                           // 评估在飞（verdict 的唯一合法入口：serial 在此锁存）
        assertEquals("◎ goal 评估中 · ", v.goalLeadingSpan().content());

        h.gm.onVerdict(h.gm.currentEpoch(),
                new GoalVerdict(GoalVerdict.Outcome.UNSATISFIED, "还差一步", false, null, ""));
        String counting = v.goalLeadingSpan().content();
        assertTrue(counting.matches("◎ goal ⏳\\d+s · "),
                "UNSATISFIED 放行后置 gap 倒计时，deadline 现算，实际：" + counting);

        h.gm.takeAutoTurn();                              // 第二轮发走：turnsUsed=2、倒计时清
        assertEquals("◎ goal 2/25 · ", v.goalLeadingSpan().content());
        assertTrue(ViewScreen.of(v).contains("◎ goal 2/25"),
                "leading 必须真上状态行（分支顺序类缺陷只测纯函数测不到），实际：\n" + ViewScreen.of(v));

        h.gm.pauseByEsc();                                // Esc 一级：RUNNING→PAUSED
        assertEquals("◎ goal 已暂停 · ", v.goalLeadingSpan().content());
    }
}

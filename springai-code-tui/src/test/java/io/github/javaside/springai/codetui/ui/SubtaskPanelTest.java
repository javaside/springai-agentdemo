package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.ui.ConversationState.SubtaskStatus;
import io.github.javaside.springai.codetui.ui.ConversationState.SubtaskView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务面板（子 agent 状态）文本组装（计数标题 / 行文本 / 运行行附当前工具 / 超 CAP 窗口）。 */
class SubtaskPanelTest {

    private static SubtaskView v(String agent, String desc, SubtaskStatus st, String tool) {
        return new SubtaskView(agent, desc, st, tool);
    }

    @Test
    void header_countsDoneRunningAndFailed() {
        List<SubtaskView> subs = List.of(
                v("explore", "a", SubtaskStatus.DONE, ""),
                v("plan", "b", SubtaskStatus.RUNNING, "Grep"),
                v("bash", "c", SubtaskStatus.FAILED, ""));
        String h = CodeTuiView.subtaskHeaderText(subs);
        assertTrue(h.contains("✓1"), "1 完成");
        assertTrue(h.contains("▶1"), "1 运行");
        assertTrue(h.contains("✗1"), "1 失败");
    }

    @Test
    void header_noFailedSegmentWhenZero() {
        List<SubtaskView> subs = List.of(v("explore", "a", SubtaskStatus.DONE, ""));
        assertTrue(!CodeTuiView.subtaskHeaderText(subs).contains("失败"), "无失败时不显示失败段");
    }

    @Test
    void row_doneShowsCheckAndAgentAndDesc() {
        String r = CodeTuiView.subtaskRowText(v("explore", "分析认证", SubtaskStatus.DONE, "Grep"));
        assertTrue(r.contains("✓"), "完成图标");
        assertTrue(r.contains("explore"), "agent 类型");
        assertTrue(r.contains("分析认证"), "描述");
        assertTrue(!r.contains("Grep"), "非运行态不附当前工具");
    }

    @Test
    void row_runningAppendsCurrentTool() {
        String r = CodeTuiView.subtaskRowText(v("plan", "设计缓存", SubtaskStatus.RUNNING, "Grep"));
        assertTrue(r.contains("▶"), "运行图标");
        assertTrue(r.contains("· Grep"), "运行行尾附当前工具");
    }

    @Test
    void row_runningNoToolOmitsTail() {
        String r = CodeTuiView.subtaskRowText(v("plan", "设计缓存", SubtaskStatus.RUNNING, ""));
        assertTrue(!r.contains("·"), "无当前工具时不附尾巴");
    }

    @Test
    void visibleSubtasks_keepsRunningTail_whenOverCap() {
        // 串行执行下"运行中"总是最后一条：超 CAP 时窗口取末尾，保证运行行不被折叠掉
        java.util.List<SubtaskView> subs = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) subs.add(v("done" + i, "d" + i, SubtaskStatus.DONE, ""));
        subs.set(7, v("running", "最后一条", SubtaskStatus.RUNNING, "Grep"));
        List<SubtaskView> vis = CodeTuiView.visibleSubtasks(subs);
        assertEquals(6, vis.size(), "可见条数 = SUBTASK_CAP");
        assertTrue(vis.stream().anyMatch(x -> x.status() == SubtaskStatus.RUNNING), "运行行必须在可见窗口内");
        assertEquals("running", vis.get(vis.size() - 1).agentName(), "运行行是末条");
    }

    @Test
    void visibleSubtasks_returnsAll_whenWithinCap() {
        List<SubtaskView> subs = List.of(
                v("a", "1", SubtaskStatus.DONE, ""),
                v("b", "2", SubtaskStatus.RUNNING, "Read"));
        assertEquals(2, CodeTuiView.visibleSubtasks(subs).size(), "未超 CAP 时全显示");
    }

    @Test
    void renderTree_withSubtasks_doesNotThrow() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "explore", "分析认证");
        CodeTuiView v = new CodeTuiView(s, (io.github.javaside.springai.codetui.agent.SubmitHandler) t -> null,
                java.nio.file.Path.of("."));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(v::renderForTest);
    }
}

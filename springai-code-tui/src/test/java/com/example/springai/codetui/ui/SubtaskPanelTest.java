package com.example.springai.codetui.ui;

import com.example.springai.codetui.ui.ConversationState.SubtaskStatus;
import com.example.springai.codetui.ui.ConversationState.SubtaskView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 子任务面板文本组装（计数标题 / 行文本 / 运行行附当前工具）。 */
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
    void renderTree_withSubtasks_doesNotThrow() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "explore", "分析认证");
        CodeTuiView v = new CodeTuiView(s, (com.example.springai.codetui.agent.SubmitHandler) t -> null,
                java.nio.file.Path.of("."));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(v::renderForTest);
    }
}

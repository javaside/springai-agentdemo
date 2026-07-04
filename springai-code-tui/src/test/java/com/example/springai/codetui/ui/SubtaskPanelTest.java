package com.example.springai.codetui.ui;

import com.example.springai.codetui.ui.ConversationState.SubAgentTodo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** todo 面板（当前子 agent 内部 todo）标题文本组装 + 含子 agent todo 的渲染冒烟。 */
class SubtaskPanelTest {

    @Test
    void header_showsAgentAndDoneProgress() {
        SubAgentTodo sub = new SubAgentTodo("implementer",
                List.of("✓ 写测试", "▶ 实现", "○ 提交"));
        String h = CodeTuiView.subAgentTodoHeaderText(sub);
        assertTrue(h.contains("implementer"), "标明哪个子 agent");
        assertTrue(h.contains("✓1"), "已完成 1 条");
        assertTrue(h.contains("/3"), "共 3 条");
    }

    @Test
    void header_allDone_showsFullProgress() {
        SubAgentTodo sub = new SubAgentTodo("reviewer", List.of("✓ 审 A", "✓ 审 B"));
        String h = CodeTuiView.subAgentTodoHeaderText(sub);
        assertTrue(h.contains("✓2/2"), "全部完成");
    }

    @Test
    void renderTree_withSubAgentTodo_doesNotThrow() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "implementer", "实现缓存");
        s.onTodoUpdated(1L, "t1", List.of("▶ 写测试", "○ 实现"));   // 子 agent 内部 todo
        CodeTuiView v = new CodeTuiView(s, (com.example.springai.codetui.agent.SubmitHandler) t -> null,
                java.nio.file.Path.of("."));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(v::renderForTest);
    }
}

package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模式提示段是纯函数，便于单测；真正「有没有被注入进请求」由装配 + pty 冒烟兜。
 */
class PermissionModePromptTest {

    @Test
    @DisplayName("只有 PLAN 有内容，其余模式是空串（别给模型灌无关噪音）")
    void onlyPlanHasGuidance() {
        assertEquals("", PermissionModePrompt.of(PermissionMode.DEFAULT));
        assertEquals("", PermissionModePrompt.of(PermissionMode.ACCEPT_EDITS));
        assertEquals("", PermissionModePrompt.of(PermissionMode.BYPASS));
        assertEquals("", PermissionModePrompt.of(null), "null 也要给空串，不能 NPE 掉整个回合");

        String plan = PermissionModePrompt.of(PermissionMode.PLAN);
        assertFalse(plan.isBlank());
        assertTrue(plan.contains("ExitPlanMode"), "必须告诉模型出口在哪");
        assertTrue(plan.contains("计划模式"));
    }

    @Test
    @DisplayName("子 agent 版不得提 ExitPlanMode——它没有这个工具，指过去就是死路")
    void subagentVariantDoesNotMentionExitPlanMode() {
        String sub = PermissionModePrompt.forSubagent(PermissionMode.PLAN);
        assertFalse(sub.isBlank(), "PLAN 下子 agent 必须知道自己改不了东西");
        assertFalse(sub.contains("ExitPlanMode"),
                "子 agent 没有 ExitPlanMode（不在 decoratedList 里），提它等于指一条走不通的路：" + sub);
        assertTrue(sub.contains("只读") || sub.contains("不能修改"), "要说清边界：" + sub);

        // 主 agent 版反过来必须提它——那是主 agent 唯一的出口
        assertTrue(PermissionModePrompt.of(PermissionMode.PLAN).contains("ExitPlanMode"));
    }

    @Test
    @DisplayName("子 agent 版同样只有 PLAN 有内容，且不含花括号")
    void subagentVariantOnlyPlanAndNoBraces() {
        assertEquals("", PermissionModePrompt.forSubagent(PermissionMode.DEFAULT));
        assertEquals("", PermissionModePrompt.forSubagent(PermissionMode.ACCEPT_EDITS));
        assertEquals("", PermissionModePrompt.forSubagent(PermissionMode.BYPASS));
        assertEquals("", PermissionModePrompt.forSubagent(null));
        for (PermissionMode m : PermissionMode.values()) {
            String s = PermissionModePrompt.forSubagent(m);
            assertFalse(s.contains("{") || s.contains("}"), m + " 的子 agent 提示段含花括号：" + s);
        }
    }

    @Test
    @DisplayName("提示段里不得出现花括号——它作为 param 值注入，带花括号会炸模板引擎")
    void noBracesInGuidance() {
        for (PermissionMode m : PermissionMode.values()) {
            String s = PermissionModePrompt.of(m);
            assertFalse(s.contains("{") || s.contains("}"),
                    m + " 的提示段含花括号，注入时会被 SpringTemplate 当占位符解析：" + s);
        }
    }
}

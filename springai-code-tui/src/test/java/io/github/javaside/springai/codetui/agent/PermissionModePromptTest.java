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
    @DisplayName("提示段里不得出现花括号——它作为 param 值注入，带花括号会炸模板引擎")
    void noBracesInGuidance() {
        for (PermissionMode m : PermissionMode.values()) {
            String s = PermissionModePrompt.of(m);
            assertFalse(s.contains("{") || s.contains("}"),
                    m + " 的提示段含花括号，注入时会被 SpringTemplate 当占位符解析：" + s);
        }
    }
}

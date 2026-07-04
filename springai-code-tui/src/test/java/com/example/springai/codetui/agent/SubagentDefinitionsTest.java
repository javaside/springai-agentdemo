package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 四个内置子 agent：可加载、身份中性（无 Claude Code 字样）、tools 名在真实工具集内。 */
class SubagentDefinitionsTest {

    /** 与 AgentTools 实际注册的工具名一致——来自 ToolNameProbeTest 探针输出（PascalCase，大小写敏感）。 */
    private static final List<String> REAL_TOOL_NAMES =
            List.of("Write", "Read", "Edit", "Bash", "BashOutput", "KillShell", "Grep", "Glob", "TodoWrite");

    @Test
    void allFourLoadAndAreIdentityNeutral() {
        Map<String, SubagentSpec> all = SubagentLoader.loadBuiltins();
        for (SubagentSpec s : all.values()) {
            String body = s.systemPrompt().toLowerCase();
            assertFalse(body.contains("claude code"), s.name() + " 不应含 'Claude Code'");
            assertFalse(body.contains("anthropic's official cli"), s.name() + " 不应含 Anthropic CLI 身份");
            assertFalse(s.name().isBlank());
            assertFalse(s.description().isBlank());
        }
    }

    @Test
    void toolsFieldsUseRealNames() {
        Map<String, SubagentSpec> all = SubagentLoader.loadBuiltins();
        for (SubagentSpec s : all.values()) {
            for (String t : s.allowTools()) {
                assertTrue(REAL_TOOL_NAMES.contains(t),
                        s.name() + " 的 tools 含未知工具名: " + t + "（对照 REAL_TOOL_NAMES / 探针输出）");
            }
        }
    }
}

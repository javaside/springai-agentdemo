package io.github.javaside.springai.codetui.agent.subagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 四个内置子 agent：可加载、身份中性（无 Claude Code 字样）、tools 名在真实工具集内。 */
class SubagentDefinitionsTest {

    /** 与 AgentTools 实际注册的工具名一致——来自 ToolNameProbeTest 探针输出（PascalCase，大小写敏感）。
     *  含 allow 用的读写/命令工具，及 deny 用的 AskUserQuestionTool（注册名 = @Tool(name=...)，非方法名 askUserQuestion）。 */
    private static final List<String> REAL_TOOL_NAMES =
            List.of("Write", "Read", "Edit", "Bash", "BashOutput", "KillShell", "Grep", "Glob", "TodoWrite",
                    "AskUserQuestionTool");

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

    /** deny 名也必须是真实注册名——否则 disallowedTools 静默失效（曾误用方法名 askUserQuestion 而非 AskUserQuestionTool）。 */
    @Test
    void disallowedToolsUseRealNames() {
        Map<String, SubagentSpec> all = SubagentLoader.loadBuiltins();
        for (SubagentSpec s : all.values()) {
            for (String t : s.denyTools()) {
                assertTrue(REAL_TOOL_NAMES.contains(t),
                        s.name() + " 的 disallowedTools 含未知工具名: " + t
                                + "（deny 按真实注册名精确匹配，写错则静默失效）");
            }
        }
    }

    /** general-purpose 无 allow 白名单（继承全部），且经 deny 屏蔽了 AskUserQuestionTool（无头委派不弹问询）。 */
    @Test
    void generalPurposeDeniesAskTool() {
        SubagentSpec gp = SubagentLoader.loadBuiltins().get("general-purpose");
        assertTrue(gp.allowTools().isEmpty(), "general-purpose 应继承全部工具（无 allow 白名单）");
        assertTrue(gp.denyTools().contains("AskUserQuestionTool"),
                "general-purpose 应 deny AskUserQuestionTool");
    }
}

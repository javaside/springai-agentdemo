package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 子 agent 系统提示 = spec.systemPrompt() 追加项目指令（非空时）。 */
class SubagentInstructionsTest {

    private static SubagentSpec spec() {
        // 真实 7 参签名（见 SubagentRunnerOkTest:57）: name, description, systemPrompt, allowTools, denyTools, model, skills
        return new SubagentSpec("explore", "探索型子 agent。", "SUBAGENT 系统提示正文",
                List.of(), List.of(), null, List.of());
    }

    private static SubagentRunner runnerWith(String projectInstructions) {
        return new SubagentRunner(
                new ProviderRegistry(List.of(new DeepSeekProvider("fake-key"))),
                List.of(), new StubListener(), projectInstructions);
    }

    @Test
    void emptyInstructions_systemPromptUnchanged() {
        String eff = runnerWith("").effectiveSystemPrompt(spec());
        assertEquals("SUBAGENT 系统提示正文", eff, "无项目指令时应原样返回 spec 提示");
    }

    @Test
    void nullInstructions_treatedAsEmpty() {
        String eff = runnerWith(null).effectiveSystemPrompt(spec());
        assertEquals("SUBAGENT 系统提示正文", eff, "null 项目指令应归一化为空、原样返回 spec 提示");
    }

    @Test
    void nonEmptyInstructions_appendedAfterSpecPrompt() {
        String eff = runnerWith("PROJ_INSTR_文本").effectiveSystemPrompt(spec());
        assertTrue(eff.startsWith("SUBAGENT 系统提示正文"), "spec 提示在前");
        assertTrue(eff.contains("PROJ_INSTR_文本"), "项目指令被追加");
        assertTrue(eff.indexOf("SUBAGENT 系统提示正文") < eff.indexOf("PROJ_INSTR_文本"),
                "项目指令应在 spec 提示之后");
    }
}

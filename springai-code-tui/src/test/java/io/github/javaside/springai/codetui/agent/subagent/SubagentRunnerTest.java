package io.github.javaside.springai.codetui.agent.subagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentRunnerTest {

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }
            @Override public String call(String i) { return "ok"; }
        };
    }

    private static final List<ToolCallback> ALL =
            List.of(tool("read"), tool("grep"), tool("glob"), tool("shell"), tool("write"));

    @Test
    void allowListKeepsOnlyNamed() {
        SubagentSpec spec = new SubagentSpec("x", "d", "sys",
                List.of("read", "grep"), List.of(), null, List.of());
        List<String> kept = SubagentRunner.filterTools(ALL, spec)
                .stream().map(t -> t.getToolDefinition().name()).toList();
        assertEquals(List.of("read", "grep"), kept);
    }

    @Test
    void emptyAllowInheritsAllThenDenyRemoves() {
        SubagentSpec spec = new SubagentSpec("x", "d", "sys",
                List.of(), List.of("write", "shell"), null, List.of());
        List<String> kept = SubagentRunner.filterTools(ALL, spec)
                .stream().map(t -> t.getToolDefinition().name()).toList();
        assertTrue(kept.contains("read"));
        assertTrue(kept.contains("grep"));
        assertTrue(kept.contains("glob"));
        assertEquals(3, kept.size());   // write/shell 被 deny 剔除
    }

    // ---- describe：cause 链摊平 ----

    @Test
    void describeFlattensCauseChain() {
        // 模拟 openai-java 的典型形态：笼统顶层 message + Jackson 根因在 cause
        RuntimeException ex = new RuntimeException("Error reading response",
                new java.io.IOException("No content to map due to end-of-input"));
        assertEquals("Error reading response ← IOException: No content to map due to end-of-input",
                SubagentRunner.describe(ex));
    }

    @Test
    void describeSkipsAdjacentDuplicateMessages() {
        // CompletionException 等 wrapper 会复读 cause 的 message——不应重复出现
        RuntimeException inner = new RuntimeException("connection reset");
        RuntimeException wrapper = new RuntimeException("connection reset", inner);
        assertEquals("connection reset", SubagentRunner.describe(wrapper));
    }

    @Test
    void describeUsesClassNameWhenMessageBlank() {
        RuntimeException ex = new RuntimeException("outer", new NullPointerException());
        assertEquals("outer ← NullPointerException: NullPointerException", SubagentRunner.describe(ex));
    }

    @Test
    void describeCapsDepthOnCyclicChain() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);   // 人为构环
        // 封顶 5 层，不死循环即可；首层无前缀，后续带类型前缀
        String s = SubagentRunner.describe(b);
        assertTrue(s.startsWith("b ← RuntimeException: a"));
    }
}

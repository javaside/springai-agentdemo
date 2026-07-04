package com.example.springai.codetui.agent;

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
}

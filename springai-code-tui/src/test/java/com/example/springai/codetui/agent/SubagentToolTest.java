package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentToolTest {

    private static SubagentTool.SubagentCall call(String type) {
        return new SubagentTool.SubagentCall("do things", "the prompt", type);
    }

    @Test
    void toolNameIsTaskAndSchemaHasThreeParams() {
        ToolCallback tc = SubagentTool.create(Map.of(), (spec, prompt, desc, turn) -> "unused");
        assertEquals("Task", tc.getToolDefinition().name());
        String schema = tc.getToolDefinition().inputSchema();
        assertTrue(schema.contains("subagent_type"));
        assertTrue(schema.contains("prompt"));
        assertTrue(schema.contains("description"));
        // v1 不暴露后台/续跑参数
        assertTrue(!schema.contains("run_in_background"));
        assertTrue(!schema.contains("resume"));
    }

    @Test
    void unknownSubagentTypeThrowsClearError() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        SubagentTool.Dispatcher dispatch = (spec, prompt, desc, turn) -> "ran " + spec.name();
        var fn = SubagentTool.function(specs, dispatch);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> fn.apply(call("no-such")));
        assertTrue(ex.getMessage().contains("no-such"));
    }

    @Test
    void knownTypeDispatchesToRunner() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        SubagentTool.Dispatcher dispatch = (spec, prompt, desc, turn) -> "ran " + spec.name();
        var fn = SubagentTool.function(specs, dispatch);
        assertEquals("ran explore", fn.apply(call("explore")));
    }
}

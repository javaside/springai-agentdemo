package io.github.javaside.springai.codetui.agent.subagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentToolTest {

    private static SubagentTool.SubagentCall call(String type) {
        // run_in_background 传 null：既有用例覆盖的都是前台语义，null 正是模型省略该字段时的取值
        return new SubagentTool.SubagentCall("do things", "the prompt", type, null);
    }

    @Test
    void toolNameIsTaskAndSchemaHasFourParams() {
        ToolCallback tc = SubagentTool.create(Map.of(), (spec, prompt, desc, turn) -> "unused");
        assertEquals("Task", tc.getToolDefinition().name());
        String schema = tc.getToolDefinition().inputSchema();
        assertTrue(schema.contains("subagent_type"));
        assertTrue(schema.contains("prompt"));
        assertTrue(schema.contains("description"));
        assertTrue(schema.contains("run_in_background"));
        // 续跑仍未暴露
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

    @Test
    void parallelToolNameAndSchemaHasTasksList() {
        ToolCallback tc = SubagentTool.createParallel(Map.of(), (dispatches, turn) -> List.of());
        assertEquals("ParallelTasks", tc.getToolDefinition().name());
        String schema = tc.getToolDefinition().inputSchema();
        assertTrue(schema.contains("tasks"));
        assertTrue(schema.contains("subagent_type"));
        assertTrue(schema.contains("prompt"));
    }

    @Test
    void parallelRoutesKnownAndDegradesUnknownToFailure() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        SubagentTool.BatchDispatcher batch = (dispatches, turn) ->
                dispatches.stream().map(d -> "ran " + d.spec().name()).toList();
        var fn = SubagentTool.batchFunction(specs, batch);
        SubagentTool.ParallelCall pc = new SubagentTool.ParallelCall(List.of(
                new SubagentTool.SubagentCall("do a", "pa", "explore", null),
                new SubagentTool.SubagentCall("do b", "pb", "no-such", null)), null);
        String out = fn.apply(pc);
        assertTrue(out.contains("[1] explore"), out);
        assertTrue(out.contains("ran explore"), out);
        assertTrue(out.contains("[2] no-such ✗"), out);
        assertTrue(out.contains("未知 subagent 类型"), out);
    }

    @Test
    void parallelInterleavedKnownUnknownPreservesInputOrder() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        // BatchDispatcher 回显每个 dispatch 的 prompt，便于验证结果落回正确槽位
        SubagentTool.BatchDispatcher batch = (dispatches, turn) ->
                dispatches.stream().map(d -> "RESULT:" + d.prompt()).toList();
        var fn = SubagentTool.batchFunction(specs, batch);
        SubagentTool.ParallelCall pc = new SubagentTool.ParallelCall(List.of(
                new SubagentTool.SubagentCall("a", "pa", "explore", null),
                new SubagentTool.SubagentCall("b", "pb", "no-such", null),
                new SubagentTool.SubagentCall("c", "pc", "explore", null)), null);
        String out = fn.apply(pc);
        // 三段按输入顺序：[1] explore ✓ RESULT:pa / [2] no-such ✗ 未知 / [3] explore ✓ RESULT:pc
        int i1 = out.indexOf("[1] explore ✓");
        int i2 = out.indexOf("[2] no-such ✗");
        int i3 = out.indexOf("[3] explore ✓");
        assertTrue(i1 >= 0 && i2 > i1 && i3 > i2, "三段应按输入顺序出现，实际=\n" + out);
        assertTrue(out.contains("RESULT:pa"), out);   // 第 1 个已知的结果落回 [1]
        assertTrue(out.contains("RESULT:pc"), out);   // 第 2 个已知的结果落回 [3]（不是 [2]）
        assertTrue(out.contains("未知 subagent 类型"), out);
    }
}

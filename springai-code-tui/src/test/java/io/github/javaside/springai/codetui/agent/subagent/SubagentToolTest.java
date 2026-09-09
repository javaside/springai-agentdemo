package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.llm.ProviderModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentToolTest {

    private static SubagentTool.SubagentCall call(String type) {
        // run_in_background 传 null：既有用例覆盖的都是前台语义，null 正是模型省略该字段时的取值
        return new SubagentTool.SubagentCall("do things", "the prompt", type, null, null);
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
                new SubagentTool.SubagentCall("do a", "pa", "explore", null, null),
                new SubagentTool.SubagentCall("do b", "pb", "no-such", null, null)), null);
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
                new SubagentTool.SubagentCall("a", "pa", "explore", null, null),
                new SubagentTool.SubagentCall("b", "pb", "no-such", null, null),
                new SubagentTool.SubagentCall("c", "pc", "explore", null, null)), null);
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

    @Test
    void modelOverrideAppliedToDispatchedSpec() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        AtomicReference<String> capturedModel = new AtomicReference<>();
        SubagentTool.Dispatcher dispatch = (spec, prompt, desc, turn) -> {
            capturedModel.set(spec.model());
            return "ran";
        };
        var fn = SubagentTool.function(specs, dispatch);

        fn.apply(new SubagentTool.SubagentCall("do a", "pa", "explore", "openai:gpt-5.6-sol", null));

        assertEquals("openai:gpt-5.6-sol", capturedModel.get());
    }

    @Test
    void noModelOverrideLeavesSpecModelUnchanged() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), "static-model", List.of()));
        AtomicReference<String> capturedModel = new AtomicReference<>();
        SubagentTool.Dispatcher dispatch = (spec, prompt, desc, turn) -> {
            capturedModel.set(spec.model());
            return "ran";
        };
        var fn = SubagentTool.function(specs, dispatch);

        fn.apply(new SubagentTool.SubagentCall("do a", "pa", "explore", null, null));

        assertEquals("static-model", capturedModel.get(), "未传 model 时沿用 spec 静态配置");
    }

    @Test
    void modelOverrideAppliedInBackgroundDispatch() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        AtomicReference<String> capturedModel = new AtomicReference<>();
        SubagentTool.Dispatcher dispatch = (spec, prompt, desc, turn) -> "fg";
        SubagentTool.BackgroundDispatcher bg = (spec, prompt, desc) -> {
            capturedModel.set(spec.model());
            return "bg";
        };
        var fn = SubagentTool.function(specs, dispatch, bg);

        fn.apply(new SubagentTool.SubagentCall("do a", "pa", "explore", "deepseek:deepseek-v4-pro", true));

        assertEquals("deepseek:deepseek-v4-pro", capturedModel.get());
    }

    @Test
    void parallelTasksApplyIndependentModelOverridesPerSubtask() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        List<String> capturedModels = new ArrayList<>();
        SubagentTool.BatchDispatcher batch = (dispatches, turn) -> {
            for (var d : dispatches) capturedModels.add(d.spec().model());
            return dispatches.stream().map(d -> "ran " + d.spec().model()).toList();
        };
        var fn = SubagentTool.batchFunction(specs, batch);
        SubagentTool.ParallelCall pc = new SubagentTool.ParallelCall(List.of(
                new SubagentTool.SubagentCall("a", "p", "explore", "openai:gpt-5.6-sol", null),
                new SubagentTool.SubagentCall("b", "p", "explore", "deepseek:deepseek-v4-pro", null),
                new SubagentTool.SubagentCall("c", "p", "explore", null, null)), null);

        fn.apply(pc);

        List<String> expected = new ArrayList<>(List.of("openai:gpt-5.6-sol", "deepseek:deepseek-v4-pro"));
        expected.add(null);
        assertEquals(expected, capturedModels, "同一批里每条子任务各自独立的 model 覆盖，互不影响");
    }

    @Test
    void schemaExposesModelParam() {
        ToolCallback tc = SubagentTool.create(Map.of(), (spec, prompt, desc, turn) -> "unused");
        String schema = tc.getToolDefinition().inputSchema();
        assertTrue(schema.contains("\"model\""), schema);
    }

    @Test
    void descriptionListsAvailableModels() {
        List<ProviderModel> models = List.of(
                new ProviderModel("openai", "gpt-5.6-sol", "Sol", "d"),
                new ProviderModel("deepseek", "deepseek-v4-pro", "Pro", "d"));
        ToolCallback tc = SubagentTool.create(Map.of(), models, (spec, prompt, desc, turn) -> "unused", null);
        String desc = tc.getToolDefinition().description();
        assertTrue(desc.contains("openai:gpt-5.6-sol"), desc);
        assertTrue(desc.contains("deepseek:deepseek-v4-pro"), desc);
    }

    @Test
    void parallelDescriptionListsAvailableModels() {
        List<ProviderModel> models = List.of(new ProviderModel("openai", "gpt-5.6-sol", "Sol", "d"));
        ToolCallback tc = SubagentTool.createParallel(Map.of(), models, (dispatches, turn) -> List.of(), null);
        String desc = tc.getToolDefinition().description();
        assertTrue(desc.contains("openai:gpt-5.6-sol"), desc);
    }

    /**
     * 2026-09-09：真实会话实证——主 agent 连续两次因网络瞬时故障（HTTP/2 流中断）派发子 agent 失败后，
     * 自行决定"换个更稳定的模型重试"，用户全程没有要求切模型。工具描述必须明确劝阻这种自作主张，
     * 否则 model 覆盖会被模型当成故障排除手段，产生用户没要求过的跨模型切换。
     */
    @Test
    void descriptionWarnsAgainstAutonomousModelSwitching() {
        ToolCallback tc = SubagentTool.create(Map.of(), List.of(), (spec, prompt, desc, turn) -> "unused", null);
        String desc = tc.getToolDefinition().description();
        assertTrue(desc.contains("ONLY set `model`"), desc);
        assertTrue(desc.contains("transient"), desc);
    }

    @Test
    void parallelDescriptionWarnsAgainstAutonomousModelSwitching() {
        ToolCallback tc = SubagentTool.createParallel(Map.of(), List.of(), (dispatches, turn) -> List.of(), null);
        String desc = tc.getToolDefinition().description();
        assertTrue(desc.contains("asked for a specific model"), desc);
        assertTrue(desc.contains("transient"), desc);
    }
}

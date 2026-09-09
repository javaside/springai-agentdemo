package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task / ParallelTasks 的模型 roster 装配契约：真实 {@code registry.allModels()} 有没有接到
 * 工具描述里、入参 schema 有没有暴露 {@code model} 字段。
 *
 * <p><b>为什么必须打在真实装配产物上</b>：{@code SubagentTool.create}/{@code createParallel} 的
 * roster 格式化零件单测直接喂假 {@code List<ProviderModel>}，证明不了 {@code AgentTools.build}
 * 真的把 {@code registry.allModels()} 传了进去——那根线漏接（比如仍调旧的 3 参重载）不会编译错，
 * 零件单测照样全绿，只有从这里读回真实产物的断言能抓到。见 {@link AgentToolsBackgroundWiringTest}
 * 类注释里同一条纪律。
 */
class AgentToolsModelOverrideWiringTest {

    @Test
    @DisplayName("Task / ParallelTasks 描述里带真实模型 roster（provider:modelId）")
    void taskDescriptionExposesRealModelRoster(@TempDir Path root) {
        Map<String, ToolCallback> runtime = RuntimeToolSet.byRegisteredName(root);

        String taskDesc = runtime.get("Task").getToolDefinition().description();
        assertTrue(taskDesc.contains("deepseek:deepseek-v4-pro"),
                "Task 描述应含真实模型 roster，实际=" + taskDesc);

        String parallelDesc = runtime.get("ParallelTasks").getToolDefinition().description();
        assertTrue(parallelDesc.contains("deepseek:deepseek-v4-pro"),
                "ParallelTasks 描述应含真实模型 roster，实际=" + parallelDesc);
    }

    @Test
    @DisplayName("Task 入参 schema 暴露 model 覆盖字段")
    void taskSchemaExposesModelField(@TempDir Path root) {
        Map<String, ToolCallback> runtime = RuntimeToolSet.byRegisteredName(root);

        String schema = runtime.get("Task").getToolDefinition().inputSchema();
        assertTrue(schema.contains("\"model\""), "Task 入参 schema 应含 model 字段，实际=" + schema);
    }
}

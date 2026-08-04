package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.background.BackgroundTask;
import io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台子 agent 子系统的<b>装配</b>契约：TaskOutput 只给主 agent、后台派发器真的被接上、
 * 并发上限的 env 解析非法值不崩启动。
 *
 * <p><b>为什么这些断言必须打在真实装配产物上</b>：整条后台链的每一块（注册表、派发、工具、UI）
 * 各自都有单测且全绿，但只要装配漏一根线——比如 {@code enableBackground} 没调、
 * 或 {@code SubagentTool.create} 还是不带后台派发器的 2 参重载——模型传
 * {@code run_in_background=true} 只会拿到「后台模式不可用」，而所有零件单测照样绿。
 * 故这里一律从 {@code AgentTools.build}/{@code RuntimeToolSet} 读回真装出来的那一份。
 */
class AgentToolsBackgroundWiringTest {

    private static ProviderRegistry dummyRegistry() {
        return new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));
    }

    @Test
    @DisplayName("主 agent 工具集含 TaskOutput")
    void mainAgentToolSetContainsTaskOutput(@TempDir Path root) {
        Map<String, ToolCallback> runtime = RuntimeToolSet.byRegisteredName(root);

        assertTrue(runtime.containsKey("TaskOutput"),
                "主 agent 须能取回后台任务结果，实际工具集=" + runtime.keySet());
        // 防空转：确认读回的确实是真装配产物（否则「含 TaskOutput」对任何胡乱构造的 map 也可能成立）。
        assertTrue(runtime.containsKey("Task"), "同一份工具集里应有 Task，实际=" + runtime.keySet());
    }

    /**
     * 禁递归：子 agent 既拿不到 Task，也就不该拿到取后台结果的 TaskOutput——
     * 给了它等于让子 agent 去捞别人的后台任务结果。
     */
    @Test
    @DisplayName("子 agent 工具集不含 TaskOutput / Task / ParallelTasks")
    void subagentToolSetExcludesBackgroundAndDelegationTools(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
        List<String> subNames = rt.subagentRunner().toolNamesForTest();

        assertFalse(subNames.contains("TaskOutput"),
                "子 agent 不该能取后台任务结果，实际=" + subNames);
        assertFalse(subNames.contains("Task"), "实际=" + subNames);
        assertFalse(subNames.contains("ParallelTasks"), "实际=" + subNames);
        // 防空转：确认读回的列表真实非空。
        assertTrue(subNames.contains("TodoWrite"),
                "子 agent 应含共享工具（如 TodoWrite），证明列表真实非空，实际=" + subNames);
    }

    /** 模型看得见 run_in_background，才谈得上用它。 */
    @Test
    @DisplayName("Task / ParallelTasks 的 schema 暴露 run_in_background")
    void taskSchemaExposesRunInBackground(@TempDir Path root) {
        Map<String, ToolCallback> runtime = RuntimeToolSet.byRegisteredName(root);

        assertTrue(runtime.get("Task").getToolDefinition().inputSchema().contains("run_in_background"),
                "Task 入参 schema 应含 run_in_background，实际="
                        + runtime.get("Task").getToolDefinition().inputSchema());
        assertTrue(runtime.get("ParallelTasks").getToolDefinition().inputSchema()
                        .contains("run_in_background"),
                "ParallelTasks 入参 schema 应含 run_in_background");
    }

    /**
     * 装配真的接上了后台派发器——这是本任务的核心断言。
     *
     * <p>不去跑真模型（会联网），只断言 {@code runInBackground} 不再返回「未装配后台注册表」那句：
     * 那句话是 {@code enableBackground} 没被调用时的唯一出口，能走过它就证明注册表与线程池都在。
     */
    @Test
    @DisplayName("SubagentRunner 已启用后台模式（不再回「未装配后台注册表」）")
    void subagentRunnerHasBackgroundEnabled(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
        SubagentSpec spec = new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());

        String reply = rt.subagentRunner().runInBackground(spec, "prompt", "desc");

        assertFalse(reply.contains("后台模式不可用"),
                "装配层必须调 enableBackground，否则整条后台链是断的。实际回复=" + reply);
        assertTrue(reply.contains("task_"), "应立刻返回 taskId，实际=" + reply);
        rt.subagentRunner().shutdownBackground();   // 别把假 provider 的任务留在池里跑
    }

    /** 后台注册表与结果仓库须经 runtime 交出去，否则 CodingAgent 的四个后台方法只能是空实现。 */
    @Test
    @DisplayName("AgentRuntime 交出后台注册表与结果仓库")
    void runtimeExposesBackgroundRegistryAndResultStore(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());

        assertNotNull(rt.backgroundRegistry(), "UI 的 /tasks 面板与自动送达都要经它");
        assertNotNull(rt.backgroundResults(), "结果限幅的唯一入口要用它");
    }

    /**
     * CodingAgent 的四个后台方法真的落到注册表上（默认实现是空的，漏实现不会编译失败）。
     * 用桩构造 + 自建注册表，不碰真装配，也不联网。
     */
    @Test
    @DisplayName("CodingAgent 的后台方法委托到注册表，且结果在此处限幅")
    void codingAgentBackgroundMethodsDelegateAndTruncate(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        CodingAgent agent = CodingAgentBackgroundTestSupport.stub(root, reg);
        String id = reg.register("explore", "查点东西");
        reg.complete(id, "x".repeat(9000), true);   // 远超 TaskResultStore 的 4000 上限

        List<SubmitHandler.BackgroundResult> done = agent.completedBackgroundTasks();

        assertEquals(1, done.size(), "已完成未消费的任务应被交出来");
        assertTrue(done.get(0).ok(), "DONE 应映射成 ok=true");
        assertTrue(done.get(0).result().length() < 9000,
                "限幅必须发生在 completedBackgroundTasks——它是后台结果进会话的唯一入口，"
                        + "放别处都可能被另一条路径绕开。实际长度=" + done.get(0).result().length());
        assertTrue(agent.markBackgroundConsumed(id), "首次标记应成功");
        assertFalse(agent.markBackgroundConsumed(id), "重复标记应返回 false（互斥闸）");

        String running = reg.register("explore", "还在跑");
        assertTrue(agent.killBackgroundTask(running), "运行中的任务应可终止");
        assertEquals(BackgroundTask.Status.KILLED, reg.find(running).status());
    }

    /** killAll 要同时标状态和关线程池——只做前者的话任务状态变了但线程还在跑。 */
    @Test
    @DisplayName("killAllBackgroundTasks 同时标记状态并关闭后台线程池")
    void killAllMarksStatusAndShutsDownPool(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
        CodingAgent agent = CodingAgentBackgroundTestSupport.stub(
                root, rt.backgroundRegistry(), rt.subagentRunner());
        String id = rt.backgroundRegistry().register("explore", "还在跑");

        agent.killAllBackgroundTasks();

        assertEquals(BackgroundTask.Status.KILLED, rt.backgroundRegistry().find(id).status(),
                "运行中的任务应被标记 KILLED");
        // 池已 shutdownNow：再派新任务会被拒（RejectedExecutionException 已被 runInBackground 兜成文本）。
        SubagentSpec spec = new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
        String reply = rt.subagentRunner().runInBackground(spec, "p", "d");
        assertTrue(reply.contains("未启动"),
                "池关了就不该再受理新后台任务，否则线程仍在跑。实际=" + reply);
    }

    // ── env 解析：非法值回落默认而不是崩启动（测纯函数，不改进程环境变量） ──

    @Test
    @DisplayName("clampConcurrency：缺失/非法回落，越界钳到 [1,32]")
    void clampConcurrencyFallsBackAndClamps() {
        assertEquals(4, AgentTools.clampConcurrency(null, 4), "缺失应回落默认");
        assertEquals(4, AgentTools.clampConcurrency("  ", 4), "空白应回落默认");
        assertEquals(4, AgentTools.clampConcurrency("abc", 4), "非数字应回落默认，绝不崩启动");
        assertEquals(1, AgentTools.clampConcurrency("0", 4), "低于下界应钳到 1");
        assertEquals(32, AgentTools.clampConcurrency("999", 4), "高于上界应钳到 32");
        assertEquals(8, AgentTools.clampConcurrency(" 8 ", 4), "合法值应生效（允许两侧空白）");
    }

    @Test
    @DisplayName("resolveTaskOutputTimeout：缺失/非法回落 300，越界钳到 [1,3600]")
    void taskOutputTimeoutFallsBackAndClamps() {
        assertEquals(300, AgentTools.clampTaskOutputTimeout(null), "缺失应回落 300");
        assertEquals(300, AgentTools.clampTaskOutputTimeout("abc"), "非数字应回落 300");
        assertEquals(1, AgentTools.clampTaskOutputTimeout("0"), "低于下界应钳到 1");
        assertEquals(3600, AgentTools.clampTaskOutputTimeout("99999"), "高于上界应钳到 3600");
        assertEquals(60, AgentTools.clampTaskOutputTimeout(" 60 "), "合法值应生效");
    }
}

package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.background.BackgroundTask;
import io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry;
import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionConfig;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.subagent.SubagentSpec;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
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

    @Test
    @DisplayName("主 agent 工具集含 ListTasks，且 Task/ParallelTasks/TaskOutput 一个都没被挤掉")
    void mainAgentToolSetContainsListTasks(@TempDir Path root) {
        Map<String, ToolCallback> runtime = RuntimeToolSet.byRegisteredName(root);

        // 这四个必须同时在：AgentTools 的尾部下标是「相对末尾」的，加一个工具若漏改某个下标，
        // 会让某个既有工具静默变成 null 或被覆盖——不编译错、不测试错，运行期才炸。
        for (String name : List.of("Task", "ParallelTasks", "TaskOutput", "ListTasks")) {
            assertTrue(runtime.containsKey(name),
                    "主 agent 工具集缺 " + name + "（尾部下标漏改？），实际=" + runtime.keySet());
            assertNotNull(runtime.get(name), name + " 是 null——下标撞车了");
        }
    }

    @Test
    @DisplayName("子 agent 拿不到 ListTasks——它没有属于自己的后台任务，列出来的只会是别人的")
    void subagentToolSetOmitsListTasks(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
        List<String> subNames = rt.subagentRunner().toolNamesForTest();

        assertFalse(subNames.contains("ListTasks"),
                "子 agent 不该能列出主 agent 的后台任务，实际=" + subNames);
        // 防空转：确认读回的列表真实非空。
        assertTrue(subNames.contains("TodoWrite"),
                "子 agent 应含共享工具（如 TodoWrite），证明列表真实非空，实际=" + subNames);
    }

    /**
     * 第二个登记点：{@code ToolRegistry}。它决定这个工具<b>要不要弹审批</b>，
     * 与 {@code AgentTools} 的数组（决定<b>有没有</b>这个工具）是两回事，漏掉不会编译错。
     *
     * <p>漏登记 ⇒ 落进 {@code UNKNOWN} 兜底 ⇒ 保守 ASK ⇒ 模型每次列后台任务都弹一次审批面板。
     *
     * <p><b>连 TaskOutput 一起断言</b>：那行登记至今没有任何测试盖住，现在删掉它
     * 全仓不会有一条测试变红。既然来了就一起钉上。
     */
    @Test
    @DisplayName("ListTasks / TaskOutput 都已在 ToolRegistry 登记——否则每次调用都弹审批")
    void backgroundQueryToolsAreAllowedWithoutAsking(@TempDir Path root) {
        PermissionEngine engine = new PermissionEngine(
                root, PermissionConfig.empty(), PermissionMode.DEFAULT);

        for (String name : List.of("ListTasks", "TaskOutput")) {
            assertEquals(PermissionBehavior.ALLOW, engine.decide(name, "{}").behavior(),
                    name + " 未在 ToolRegistry 登记 ⇒ 落进 UNKNOWN ⇒ 每次调用都弹审批面板");
        }
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

    /**
     * ★ {@code killAllBackgroundTasks}（{@code /clear} 走这条）要同时标状态和真停线程，
     * 但<b>绝不能把后台派发能力一并烧掉</b>。
     *
     * <p><b>这里原来断言的是反面</b>（「池关了就不该再受理新后台任务」）——那对退出成立，
     * 对 {@code /clear} 是灾难：{@code ThreadPoolExecutor} 一旦 shutdown 就不可复用，
     * 而 {@code enableBackground} 全仓只在装配期调一次，于是 {@code /clear} 之后这个进程里
     * <b>每一次</b>后台派发都永久落进 rejected 分支，模型只会看到「等在跑的任务完成后重试」
     * 而永远等不到。{@code /clear} 与退出目前共用这一个入口，故它只能重建、不能关池。
     */
    @Test
    @DisplayName("★ killAllBackgroundTasks 标记状态但保留派发能力（/clear 之后仍可派发）")
    void killAllMarksStatusButKeepsDispatchUsable(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
        CodingAgent agent = CodingAgentBackgroundTestSupport.stub(
                root, rt.backgroundRegistry(), rt.subagentRunner());
        String id = rt.backgroundRegistry().register("explore", "还在跑");

        agent.killAllBackgroundTasks();

        assertEquals(BackgroundTask.Status.KILLED, rt.backgroundRegistry().find(id).status(),
                "运行中的任务应被标记 KILLED");
        SubagentSpec spec = new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
        String reply = rt.subagentRunner().runInBackground(spec, "p", "d");
        assertFalse(reply.contains("未启动"),
                "kill 全部任务之后后台派发必须仍然可用，否则本进程后台模式永久失效。实际=" + reply);
        assertTrue(reply.contains("task_"), "应立刻返回新 taskId，实际=" + reply);
        rt.subagentRunner().shutdownBackground();   // 别把假 provider 的任务留在池里跑
    }

    /**
     * {@code shutdownBackground}（关池语义）的拒绝理由必须与「队列已满」<b>分开</b>。
     *
     * <p>两者是完全不同的两件事：队列满是「等会儿再来」，池已关是「不再受理了」。
     * 共用一句话会把「池死了」说成「队列满了」，模型据此选择「等在跑的任务完成后重试」——
     * 而队列其实是空的、池是死的，它会永远等下去。
     *
     * <p>这条与 {@code /clear} 的重建修复<b>互相独立</b>：重建之后池不会再无声死掉，
     * 但 rejected 分支照样会被真实的队列满触发，那句话得只在真的队列满时才说得通。
     */
    @Test
    @DisplayName("shutdownBackground 关池后的拒绝理由与「队列已满」不同")
    void shutdownBackgroundClosesPoolWithItsOwnReason(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
        SubagentSpec spec = new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());

        rt.subagentRunner().shutdownBackground();

        String reply = rt.subagentRunner().runInBackground(spec, "p", "d");
        assertTrue(reply.contains("未启动"), "池关了就不该再受理新后台任务，实际=" + reply);
        assertFalse(reply.contains("队列已满"),
                "池已关不是队列满——共用这句话会让模型去等一个永远不会来的空位。实际=" + reply);
        assertTrue(rt.backgroundRegistry().all().isEmpty(),
                "关闭后的派发应在登记之前就被挡掉，否则又是一条停在 RUNNING 的幽灵。实际="
                        + rt.backgroundRegistry().all());
    }

    /**
     * ★ 装配期真的把「有没有人在插话」这根线接给了 {@code TaskOutput}。
     *
     * <p>{@link io.github.javaside.springai.codetui.agent.background.BackgroundTaskTool} 自己的单测
     * （{@code BackgroundTaskToolInterjectionTest}）只证明「给它一个会返回 true 的 supplier，它会让路」。
     * 生产若把那个参数填成 {@code () -> false}，那些单测<b>一条都不会红</b>，而真人这边
     * {@code block=true} 依旧是一堵 300 秒的墙——正是本条要钉住的那类装配漏线。
     *
     * <p>故这里必须与 {@code interjections()} 取自<b>同一次</b> build（见 {@code RuntimeToolSet.toolsOf}），
     * 各建一次的话两个对象互不相干，断言恒绿。
     *
     * <p><b>变异实测：把那个 supplier 改回 {@code () -> false}，本条确实红</b>——但杀死它的是
     * {@code @Timeout}（30 秒），不是下面那句 {@code ms < 5_000}：生产默认超时 300 秒，
     * 断言根本等不到执行。故看到 {@code TimeoutException} 就是「这根线断了」，别当成机器慢。
     * 留着 {@code ms < 5_000} 是为了另一半情形——有人把
     * {@code CODETUI_TASK_OUTPUT_TIMEOUT_SECONDS} 调小时，它给出的是能读的那句话。
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("★ TaskOutput(block=true) 拿到的是真实插话队列——装配漏线则等满 300 秒")
    void taskOutputYieldsToPendingInterjectionInRealWiring(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
        ToolCallback taskOutput = RuntimeToolSet.toolsOf(rt).get("TaskOutput");
        assertNotNull(taskOutput, "前置：主 agent 工具集里得有 TaskOutput，否则本用例是空转的");

        String id = rt.backgroundRegistry().register("explore", "永不完成的调查");
        rt.interjections().offer("先别等了，我有话说");

        long t0 = System.nanoTime();
        String out = taskOutput.call("{\"task_id\":\"" + id + "\",\"block\":true}",
                new ToolContext(Map.of("turnId", 1L)));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(ms < 5_000,
                "等了 " + ms + "ms——装配没把真实插话队列接给 TaskOutput（填成了恒 false 的 supplier？），"
                        + "用户那句话得等这段阻塞跑完才送得出去");
        assertTrue(out.contains("仍在运行"), "让位不等于任务出事了：" + out);
        assertEquals(1, rt.interjections().pendingCount(),
                "让路而已，不该顺手把插话消费掉——取走它是 InterjectingChatModel 的活");
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

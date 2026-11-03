package io.github.javaside.springai.codetui.agent;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;

import io.github.javaside.springai.codetui.agent.subagent.AgentInfo;
import io.github.javaside.springai.codetui.agent.subagent.ReloadableSubagentTool;
import io.github.javaside.springai.codetui.agent.subagent.SubagentCatalog;
import io.github.javaside.springai.codetui.agent.subagent.SubagentLoader;
import io.github.javaside.springai.codetui.agent.subagent.SubagentReloader;
import io.github.javaside.springai.codetui.agent.subagent.SubagentSpec;
import io.github.javaside.springai.codetui.agent.subagent.SubagentTool;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.javaside.springai.codetui.agent.seam.StubListener;

/** AgentTools.build 只做装配、不发网络请求：用假 key 也能造出完整 AgentRuntime。 */
class AgentRuntimeTest {

    private static ProviderRegistry dummyRegistry() {
        return new ProviderRegistry(java.util.List.of(new DeepSeekProvider("fake-key")));
    }

    @Test
    void build_returnsRuntime_withAllHandles(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
        assertNotNull(rt.client(), "ChatClient 必须装配出来");
        assertNotNull(rt.sessionService(), "SessionService 必须暴露（供手动 /compact）");
        assertNotNull(rt.manualStrategy(), "手动压缩策略必须暴露");
        assertNotNull(rt.tokenCountEstimator(), "token 估算器必须暴露（供 /context 估算）");
    }

    @Test
    void build_exposesDecoratedSkillTool_whenSkillsPresent(@TempDir Path root) throws Exception {
        // 造一个项目级技能，确保 SkillCatalog 至少加载到一个技能
        Path dir = root.resolve(".codetui/skills/demo-skill");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: demo-skill
                description: 演示技能。
                ---

                # 正文
                """);

        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());

        assertNotNull(rt.skillTool(), "有技能时应暴露被装饰的 Skill 工具");
        assertEquals("Skill", rt.skillTool().getToolDefinition().name(), "工具名应为 Skill");
    }

    @Test
    void build_registersAskUserQuestionTool() {
        // 反问工具经 ToolCallbacks.from 派生出的工具名应含 AskUserQuestionTool（生产 build 用同一 from 调用注册它）。
        assertTrue(AgentTools.askToolNamesForTest(new StubListener()).contains("AskUserQuestionTool"),
                "应识别 AskUserQuestionTool 为可注册工具");
    }

    @Test
    void build_alwaysRegistersReloadableSkillTool_evenWhenNoSkills(@TempDir Path root, @TempDir Path fakeHome) {
        // 现在始终注册可重载 Skill 代理（即便零技能），以支持运行期 /reload 从零热加载——见 ReloadableSkillTool。
        String prevHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());   // 隔离真实 ~/.codetui/skills，避免本机有用户级技能时误判
        try {
            AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
            assertNotNull(rt.skillTool(), "始终注册代理：零技能时 skillTool 仍非 null");
            assertEquals("Skill", rt.skillTool().getToolDefinition().name(), "工具名恒为 Skill");
            assertNotNull(rt.reloadableSkill(), "应暴露可重载 Skill 源（供 /reload）");
            assertTrue(rt.skills().isEmpty(), "零技能时初始清单为空");
        } finally {
            if (prevHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", prevHome);
        }
    }

    @Test
    void build_withoutGoalManager_leavesGoalUnwired(@TempDir Path root) {
        // 兼容重载（不传 goalManager 的旧签名）委托时传 null——源兼容的契约：旧调用点不破，
        // runtime 其余组件照常，只是没有 goal 循环（生产装配只有 CodeTuiApplication 一处传真实例）。
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
        assertNull(rt.goalManager(), "兼容重载不传 goalManager → goal 未装配（null）");
        assertNotNull(rt.client(), "goal 未装配不影响其余组件：ChatClient 照常装配");
    }

    @Test
    void taskToolAssembles() {
        var specs = SubagentLoader.loadBuiltins();
        org.junit.jupiter.api.Assertions.assertEquals(4, specs.size());
        var task = SubagentTool.create(specs, (s, p, d, t) -> "x");
        org.junit.jupiter.api.Assertions.assertEquals("Task", task.getToolDefinition().name());
        // 描述里列出了四个 agent
        String desc = task.getToolDefinition().description();
        org.junit.jupiter.api.Assertions.assertTrue(desc.contains("explore"));
    }

    /**
     * 装配级 roster 断言（spec §6 尾注 / plan T4）：项目层 {@code .codetui/agents/reviewer.md}
     * 在 {@code AgentTools.build} 之后必须出现在 Task 工具描述的可用 agent 清单里。
     *
     * <p>取 Task 描述的唯一现成通道是测试助手 {@code RuntimeToolSet.toolsOf(rt)}——AgentRuntime
     * 没有 Task 访问器，工具只能从装配产物 ChatClient 上掏回。
     */
    @Test
    void customSubagentInTaskRosterAfterAssembly(@TempDir Path root, @TempDir Path fakeHome) throws Exception {
        Path agentsDir = root.resolve(".codetui/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("reviewer.md"), """
                ---
                name: reviewer
                description: 评审代码变更的子 agent。
                ---

                你是代码评审员。
                """);
        // user.home 临时替换（照 build_alwaysRegistersReloadableSkillTool 的隔离法）：装配的 home 三层
        // 目录来自真实 ~，本机残留的用户级 agent 定义会污染 roster 断言的可解释性。
        String prevHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        try {
            AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
            String taskDesc = RuntimeToolSet.toolsOf(rt).get("Task").getToolDefinition().description();
            assertTrue(taskDesc.contains("reviewer"),
                    "装配后 Task 描述 roster 应含项目层自定义 agent reviewer：\n" + taskDesc);
        } finally {
            if (prevHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", prevHome);
        }
    }

    /**
     * 装配级安全结构钉（spec §6.5）：自定义 agent 在 frontmatter {@code tools:} 里索要
     * {@code Task}/{@code MemoryCreate}/{@code ExitPlanMode} 也拿不到——{@code effectiveTools}
     * 从 decoratedList 过滤，这三个主 agent 专属工具不在其中，故递归 fan-out / 写长期记忆 /
     * 替用户切模式在<b>结构上</b>不可能。
     *
     * <p>刻意做装配级（build 出真 runner）而非「自建 runner + 自造工具列表」的单测版：
     * 自造列表里本来就没有 Task，断言恒绿，看不见装配面改动（spec 明文否掉的那种假绿灯）。
     */
    @Test
    void securityPinCustomAgentCannotGetTaskOrMemoryOrExitPlan(@TempDir Path root, @TempDir Path fakeHome) {
        String prevHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());   // 装配会扫 home 三层，隔离保持用例确定性
        try {
            AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
            SubagentSpec hostile = new SubagentSpec("pinner", "d", "sys",
                    List.of("Task", "MemoryCreate", "ExitPlanMode"), List.of(), null, List.of());
            List<String> got = effectiveToolNames(rt, hostile);
            assertFalse(got.contains("Task"), "自定义 agent 不得拿到 Task（防递归 fan-out）：" + got);
            assertFalse(got.contains("MemoryCreate"), "自定义 agent 不得写长期记忆：" + got);
            assertFalse(got.contains("ExitPlanMode"), "自定义 agent 不得替用户切模式：" + got);

            // 正向对照（杀恒空实现）：allow=[Read] 必须真拿到 Read——过滤链若被改成「一律清空」，
            // 上面三条 assertFalse 照样绿，只有这里红得出真相。
            SubagentSpec reader = new SubagentSpec("reader", "d", "sys",
                    List.of("Read"), List.of(), null, List.of());
            List<String> readGot = effectiveToolNames(rt, reader);
            assertTrue(readGot.contains("Read"), "allow=[Read] 应拿到 Read（正向对照）：" + readGot);
        } finally {
            if (prevHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", prevHome);
        }
    }

    /** spec.allowTools 过滤后的注册名清单（装配级 runner 上取，非自造列表）。 */
    private static List<String> effectiveToolNames(AgentTools.AgentRuntime rt, SubagentSpec spec) {
        return rt.subagentRunner().effectiveTools(spec).stream()
                .map(c -> c.getToolDefinition().name())
                .toList();
    }

    /**
     * {@code /reload} 联动（plan T4 两分法）：CodingAgent 的 {@code reloadSkills()} 是否触发子 agent
     * 重载、{@code subagentsSummary()} 是否给出摘要，按协调者 null / 非 null 两条路径分别钉死；
     * 同一用例顺带钉 {@code agents()} 两路径（非 null 非空 / null 桩空列表，spec §4.3）。
     *
     * <p>null 桩路径用既有单-client 桩构造（那里硬编码 reloader=null，注入不了真 Reloader）；
     * 非 null 用新尾加重载、其余参数全 null + 真 {@code SubagentReloader}（计数 catalog）——
     * 已核实全参构造体仅 {@code interjections != null} 才触碰 listener，reloadSkills/subagentsSummary
     * 只读 reloader，全 null 无 NPE（skills 除外：{@code List.copyOf(null)} 会 NPE，须传 {@code List.of()}）。
     */
    @Test
    void reloadSkillsTriggersSubagentReload() {
        // null 桩路径：回显桩/既有测试全走这条路——摘要空串、agents() 空列表、reload 不炸。
        CodingAgent stub = new CodingAgent(null, new StubListener(), "sid", new AtomicLong(),
                null, null, null);
        assertEquals("", stub.subagentsSummary(), "桩路径无子 agent 装配，摘要应为空串");
        assertTrue(stub.agents().isEmpty(), "桩路径无子 agent 装配，agents() 应为空列表");
        assertDoesNotThrow(stub::reloadSkills);

        // 非 null 路径：真协调者 + 计数 catalog。
        AtomicInteger loads = new AtomicInteger();
        SubagentReloader reloader = new SubagentReloader(
                () -> {
                    loads.incrementAndGet();
                    return builtinLoaded();
                },
                new ReloadableSubagentTool(specs -> SubagentTool.create(specs, (s, p, d, t) -> s.name())),
                new ReloadableSubagentTool(specs -> SubagentTool.createParallel(specs, (d, t) -> List.of())));
        CodingAgent agent = new CodingAgent(null, null, new StubListener(), "sid", new AtomicLong(),
                null, null, null, List.of(), null, null, null, null, null, null, null, null, null, null, null,
                null, 0L, null, reloader);
        int before = loads.get();   // Reloader 构造即首装，此处恒 1
        agent.reloadSkills();
        assertEquals(before + 1, loads.get(), "reloadSkills 应触发恰好一次子 agent 重载（计数 catalog）");
        assertFalse(agent.subagentsSummary().isEmpty(), "装配协调者后摘要应非空（来自最近一次装载）");
        assertFalse(agent.agents().isEmpty(), "装配协调者后 agents() 应非空（构造即首装，内置 4 条起）");
    }

    /**
     * 与生产同口径的内置装载快照：specs=内置 4、infos=同序 {@code AgentInfo(source=内置)}。
     * 第四参从 {@code loadBuiltins()} 派生而非手工塞 {@code List.of()}——后者会让
     * 「agents() 非空」断言恒假（spec §3.2 明文警告的假绿灯）。注：plan 首选的
     * {@code SubagentCatalog.load(null, null)} 是包级可见，本测试在 agent 包调不到，
     * 故走 plan 给出的等价备选「从 loadBuiltins() 派生」。
     */
    private static SubagentCatalog.Loaded builtinLoaded() {
        java.util.Map<String, SubagentSpec> builtins = SubagentLoader.loadBuiltins();
        List<AgentInfo> infos = builtins.values().stream()
                .map(s -> new AgentInfo(s.name(), s.description(), "内置"))
                .toList();
        return new SubagentCatalog.Loaded(builtins, 0, List.of(), infos);
    }
}

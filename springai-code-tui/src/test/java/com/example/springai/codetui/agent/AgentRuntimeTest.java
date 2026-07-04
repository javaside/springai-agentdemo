package com.example.springai.codetui.agent;

import com.example.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void taskToolAssembles() {
        var specs = SubagentLoader.loadBuiltins();
        org.junit.jupiter.api.Assertions.assertEquals(4, specs.size());
        var task = SubagentTool.create(specs, (s, p, d, t) -> "x");
        org.junit.jupiter.api.Assertions.assertEquals("Task", task.getToolDefinition().name());
        // 描述里列出了四个 agent
        String desc = task.getToolDefinition().description();
        org.junit.jupiter.api.Assertions.assertTrue(desc.contains("explore"));
    }
}

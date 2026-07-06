package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆工具（AutoMemoryTools）的装配契约：6 个工具按注册名进入<b>主 agent</b>工具集、
 * 建出记忆目录，且<b>不</b>泄漏给子 agent（子 agent 上下文独立、不写长期记忆）。
 */
class AgentMemoryToolsTest {

    private static final List<String> MEMORY_NAMES = List.of(
            "MemoryView", "MemoryCreate", "MemoryStrReplace",
            "MemoryInsert", "MemoryDelete", "MemoryRename");

    private static ProviderRegistry dummyRegistry() {
        return new ProviderRegistry(java.util.List.of(new DeepSeekProvider("fake-key")));
    }

    @Test
    void buildMemoryTools_returnsAllSixByRegisteredName(@TempDir Path root) {
        ToolCallback[] tools = AgentTools.buildMemoryTools(root, new ConversationState());
        List<String> names = Arrays.stream(tools)
                .map(t -> t.getToolDefinition().name()).toList();
        for (String expected : MEMORY_NAMES) {
            assertTrue(names.contains(expected), "主 agent 记忆工具应含 " + expected + "，实际=" + names);
        }
    }

    @Test
    void buildMemoryTools_createsMemoryDirUnderCodetui(@TempDir Path root) {
        AgentTools.buildMemoryTools(root, new ConversationState());
        assertTrue(java.nio.file.Files.exists(root.resolve(".codetui").resolve("memory")),
                "AutoMemoryTools.build 应创建 .codetui/memory 目录");
    }

    /**
     * 子 agent 排除：记忆工具只拼进主 agent 的 toolsWithTask，从不进入传给 SubagentRunner 的 decoratedList。
     * 断言真实装配路径——读回 build 交给 SubagentRunner 的实际工具列表（零漂移）。
     */
    @Test
    void subagentToolSet_excludesMemoryTools(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
        List<String> subNames = rt.subagentRunner().toolNamesForTest();
        for (String mem : MEMORY_NAMES) {
            assertFalse(subNames.contains(mem),
                    "子 agent 工具集不应含记忆工具 " + mem + "，实际=" + subNames);
        }
        // 防空转：子 agent 确实拿到了共享工具（否则「不含记忆工具」对空列表也成立、断言无意义）。
        assertTrue(subNames.contains("TodoWrite"),
                "子 agent 应含共享工具（如 TodoWrite），证明读回的列表真实非空，实际=" + subNames);
    }
}

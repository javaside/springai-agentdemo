package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** AGENTS.md 内容含花括号时，build() 装配（含系统提示 ST 渲染）不得抛异常——证明作 param 值注入而非模板文字。 */
class AgentToolsProjectInstructionsTest {

    private static ProviderRegistry dummyRegistry() {
        return new ProviderRegistry(java.util.List.of(new DeepSeekProvider("fake-key")));
    }

    @Test
    void build_withBraceHeavyAgentsMd_doesNotThrow(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"),
                "构建：`mvn -q test`。示例配置：{ \"key\": \"{value}\" } 与 {{占位}}。");
        AgentTools.AgentRuntime rt = assertDoesNotThrow(
                () -> AgentTools.build(dummyRegistry(), root, new ConversationState()),
                "含花括号的 AGENTS.md 注入不得炸 ST");
        assertNotNull(rt.client(), "ChatClient 应正常装配");
    }

    @Test
    void build_withoutAgentsMd_doesNotThrow(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
        assertNotNull(rt.client());
    }
}

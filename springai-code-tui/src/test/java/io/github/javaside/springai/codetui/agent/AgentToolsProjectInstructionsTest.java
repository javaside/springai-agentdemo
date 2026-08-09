package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AGENTS.md 内容含花括号时，build() 装配（构建期，不含请求期 ST 渲染）不得抛异常——
 * 证明 ProjectInstructions 作 param 值存入而非拼进模板文字。
 * 另有一个确定性测试直接跑请求期渲染路径，证明默认 ST 渲染器不会二次解析 param 值内的花括号。
 */
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

    @Test
    void stRenderer_doesNotReparseBraceHeavyParamValue() {
        // 复刻生产渲染路径：Spring AI 在请求期用 PromptTemplate + 默认 StTemplateRenderer 渲染系统提示。
        // 证明「含花括号的内容作 param 值注入」时，ST 原样代入、不递归解析——这正是 AGENTS.md / AUTO_MEMORY 安全的根因。
        String braceHeavy = "构建：`mvn -q test`。配置：{ \"key\": \"{value}\" } 与 {{占位}}。";
        String out = org.springframework.ai.chat.prompt.PromptTemplate.builder()
                .template("{PROJECT_INSTRUCTIONS}")
                .variables(java.util.Map.of("PROJECT_INSTRUCTIONS", braceHeavy))
                .build()
                .render();
        assertEquals(braceHeavy, out, "ST 应原样代入 param 值，不把值内花括号当占位符再解析");
    }

    @Test
    void systemTemplate_treatsProjectRootAsDefaultDirectory_notAccessBoundary() throws Exception {
        var field = AgentTools.class.getDeclaredField("SYSTEM_TEMPLATE");
        field.setAccessible(true);
        String template = (String) field.get(null);

        assertTrue(template.contains("当前项目根目录是默认工作目录，不是强制访问边界"));
        assertTrue(template.contains("统一服从权限引擎、内置安全底线及用户审批结果"));
        assertFalse(template.contains("所有操作都应发生在当前项目根目录之内"));
    }
}

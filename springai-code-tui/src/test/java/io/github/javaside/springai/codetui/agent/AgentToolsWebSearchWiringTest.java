package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WebSearch 的门控接线：配了 BOCHA_API_KEY 才有这个工具。 */
class AgentToolsWebSearchWiringTest {

    @Test
    void noKey_noTool() {
        assertNull(AgentTools.createWebSearchTool(null, null), "未配 key 时不应创建工具");
        assertNull(AgentTools.createWebSearchTool("   ", null), "空白 key 时不应创建工具");
    }

    @Test
    void withKey_toolCreatedAndCountFromEnv() {
        assertNotNull(AgentTools.createWebSearchTool("fake-key", null), "配了 key 就应创建工具");
        assertNotNull(AgentTools.createWebSearchTool("fake-key", "20"), "带条数配置也应创建成功");
    }

    /** 注册名取 @Tool 注解而非方法名；子 agent 的 allow/deny 按注册名精确匹配，写错会静默失效。 */
    @Test
    void registeredToolNameIsWebSearch() {
        BochaWebSearchTool tool = AgentTools.createWebSearchTool("fake-key", null);

        List<String> names = Arrays.stream(ToolCallbacks.from(tool))
                .map(c -> c.getToolDefinition().name()).toList();

        assertEquals(List.of("WebSearch"), names,
                "注册名必须恰好是 WebSearch（方法名是 webSearch，两者不同），实际=" + names);
    }

    @Test
    void build_withoutBochaKey_stillAssemblesOffline(@TempDir Path root) {
        ProviderRegistry reg = new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));

        AgentTools.AgentRuntime rt = AgentTools.build(reg, root, new ConversationState());

        assertNotNull(rt.client(), "无搜索 key 时装配仍须成功");
    }

    @Test
    void decoratedToolCallbackKeepsName() {
        BochaWebSearchTool tool = AgentTools.createWebSearchTool("fake-key", null);
        ToolCallback raw = ToolCallbacks.from(tool)[0];

        ToolCallback decorated = new ToolEventCallback(raw, new ConversationState());

        assertTrue("WebSearch".equals(decorated.getToolDefinition().name()),
                "装饰后注册名不能变，实际=" + decorated.getToolDefinition().name());
    }

    @Test
    void guideIsEmptyWhenNoTool() {
        assertEquals("", AgentTools.webSearchGuide(false),
                "未注册搜索工具时，系统提示不应出现任何搜索相关指引");
    }

    @Test
    void guideMentionsWebSearchAndFetchHandoff() {
        String guide = AgentTools.webSearchGuide(true);

        assertTrue(guide.contains("WebSearch"), "应点名工具，实际=" + guide);
        assertTrue(guide.contains("webFetch"), "应说明与 webFetch 的分工，实际=" + guide);
        assertTrue(guide.contains("freshness"), "应提醒 freshness 一般别传，实际=" + guide);
        assertTrue(guide.contains("Sources"), "应要求列出来源，实际=" + guide);
    }

    /** 指引段作为 param 值注入，正文里的花括号会被 StringTemplate 当占位符解析而炸掉整个系统提示。 */
    @Test
    void guideContainsNoTemplateBraces() {
        String guide = AgentTools.webSearchGuide(true);

        assertTrue(!guide.contains("{") && !guide.contains("}"),
                "指引正文不得含花括号，实际=" + guide);
    }
}

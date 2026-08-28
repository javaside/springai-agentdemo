package io.github.javaside.springai.codetui.agent;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.tools.BochaWebSearchTool;
import io.github.javaside.springai.codetui.agent.tools.ToolEventCallback;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 两个搜索工具的门控接线：各自的 API key 配了才注册对应工具。 */
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
    void registeredToolNameIsBochaWebSearch() {
        BochaWebSearchTool tool = AgentTools.createWebSearchTool("fake-key", null);

        List<String> names = Arrays.stream(ToolCallbacks.from(tool))
                .map(c -> c.getToolDefinition().name()).toList();

        assertEquals(List.of("BochaWebSearch"), names,
                "两家共存后改用对称命名，避免模型把某一家当默认搜索。实际=" + names);
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

        assertTrue("BochaWebSearch".equals(decorated.getToolDefinition().name()),
                "装饰后注册名不能变，实际=" + decorated.getToolDefinition().name());
    }

    @Test
    void guideIsEmptyWhenNeitherToolRegistered() {
        assertEquals("", AgentTools.webSearchGuide(false, false),
                "两家都没注册时，系统提示不应出现任何搜索相关指引");
    }

    @Test
    void guideCoversBochaOnly() {
        String guide = AgentTools.webSearchGuide(true, false);

        assertTrue(guide.contains("BochaWebSearch"), "应点名博查工具，实际=" + guide);
        assertFalse(guide.contains("BraveWebSearch"), "Brave 没注册就不该提它，实际=" + guide);
        assertTrue(guide.contains("webFetch"), "应说明与 webFetch 的分工，实际=" + guide);
    }

    @Test
    void guideCoversBraveOnly() {
        String guide = AgentTools.webSearchGuide(false, true);

        assertTrue(guide.contains("BraveWebSearch"), "应点名 Brave 工具，实际=" + guide);
        assertFalse(guide.contains("BochaWebSearch"), "博查没注册就不该提它，实际=" + guide);
        assertTrue(guide.contains("webFetch"), "应说明与 webFetch 的分工，实际=" + guide);
    }

    @Test
    void guideExplainsDivisionWhenBothRegistered() {
        String guide = AgentTools.webSearchGuide(true, true);

        assertTrue(guide.contains("BochaWebSearch"), "实际=" + guide);
        assertTrue(guide.contains("BraveWebSearch"), "实际=" + guide);
        assertTrue(guide.contains("中文"), "应讲清中文走哪家，实际=" + guide);
        assertTrue(guide.contains("英文"), "应讲清英文走哪家，实际=" + guide);
        assertTrue(guide.contains("Sources"), "应要求列出来源，实际=" + guide);
    }

    /** 指引段作为 param 值注入，正文里的花括号会被 StringTemplate 当占位符解析而炸掉整个系统提示。 */
    @Test
    void noGuideVariantContainsTemplateBraces() {
        for (boolean bocha : new boolean[]{false, true}) {
            for (boolean brave : new boolean[]{false, true}) {
                String guide = AgentTools.webSearchGuide(bocha, brave);
                assertTrue(!guide.contains("{") && !guide.contains("}"),
                        "指引正文不得含花括号（bocha=" + bocha + ", brave=" + brave + "），实际=" + guide);
            }
        }
    }

    @Test
    void resolveBraveResultCountFallsBackAndClamps() {
        assertEquals(5, AgentTools.resolveBraveResultCount(null),
                "缺失应回退 5（Brave 免费档 2000 次/月，比博查更该省）");
        assertEquals(5, AgentTools.resolveBraveResultCount("  "), "空白应回退 5");
        assertEquals(5, AgentTools.resolveBraveResultCount("abc"), "非数字应回退 5");
        assertEquals(1, AgentTools.resolveBraveResultCount("0"), "低于下界应钳到 1");
        assertEquals(20, AgentTools.resolveBraveResultCount("999"), "高于上界应钳到 20");
        assertEquals(10, AgentTools.resolveBraveResultCount(" 10 "), "合法值应生效（允许两侧空白）");
    }

    @Test
    void noBraveKey_noBraveTool() {
        assertNull(AgentTools.createBraveWebSearchTool(null, null), "未配 BRAVE_API_KEY 时不应创建");
        assertNull(AgentTools.createBraveWebSearchTool("   ", null), "空白 key 时不应创建");
    }

    /** 库版注册名是 WebSearch，与博查撞名，必须被改成 BraveWebSearch。 */
    @Test
    void braveToolIsRenamedToAvoidCollision() {
        ToolCallback brave = AgentTools.createBraveWebSearchTool("fake-key", null);

        assertNotNull(brave, "配了 key 就应创建");
        assertEquals("BraveWebSearch", brave.getToolDefinition().name(),
                "库版注册名 WebSearch 会与博查工具撞名，必须改写");
    }

    /** 库里那段描述写死了「Claude」与「US only」，两条都不适用，必须换掉。 */
    @Test
    void braveDescriptionIsReplacedWithChineseGuidance() {
        ToolCallback brave = AgentTools.createBraveWebSearchTool("fake-key", null);
        String description = brave.getToolDefinition().description();

        assertFalse(description.contains("Claude"), "不应把别家产品名塞进本项目的工具描述");
        assertFalse(description.contains("only available in the US"), "实测国内直连可达，这句是错的");
        assertTrue(description.contains("英文"), "应说明它主打英文内容（与博查分工），实际=" + description);
        assertTrue(description.contains("site:"), "应提示用 site: 运算符而非 allowedDomains，实际=" + description);
    }

    /** 完整装饰链之后注册名仍须是 BraveWebSearch——中间任何一层丢了改名，工具分发就会撞上博查。 */
    @Test
    void braveKeepsRenamedNameThroughFullDecorationChain() {
        ToolCallback brave = AgentTools.createBraveWebSearchTool("fake-key", null);

        ToolCallback decorated = new ToolEventCallback(brave, new ConversationState());

        assertEquals("BraveWebSearch", decorated.getToolDefinition().name(),
                "装饰链末端的注册名，实际=" + decorated.getToolDefinition().name());
    }
}

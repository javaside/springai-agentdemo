package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResilientToolCallingManager} 行为验证：
 * <ul>
 *   <li>工具名解析失败（模型拼错）→ 返回「工具不存在 + 可用清单」的 tool 结果，而不是抛异常毁回合；</li>
 *   <li>非解析失败异常 → 原样抛出；</li>
 *   <li>正常路径 → 原样透传。</li>
 * </ul>
 */
class ResilientToolCallingManagerTest {

    /** 记录透传调用的 stub manager。 */
    private static final class Stub implements ToolCallingManager {
        final RuntimeException toThrow;
        final ToolExecutionResult toReturn;
        int calls;

        Stub(RuntimeException toThrow, ToolExecutionResult toReturn) {
            this.toThrow = toThrow;
            this.toReturn = toReturn;
        }

        @Override public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions o) {
            return List.of();
        }

        @Override public ToolExecutionResult executeToolCalls(Prompt p, ChatResponse r) {
            calls++;
            if (toThrow != null) throw toThrow;
            return toReturn;
        }
    }

    /** 一个带 options 的 prompt（options 是 ToolCallingChatOptions，含可用工具回调）。 */
    private static Prompt promptWithTools() {
        ToolCallback cb = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("BochaWebSearch").description("d")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}")
                        .build();
            }
            @Override public String call(String toolInput) { return "{}"; }
        };
        ToolCallingChatOptions opts = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(cb))
                .build();
        return new Prompt(List.of(), opts);
    }

    /** 一个带 tool_calls 的 ChatResponse，其中工具名是模型拼错的 BoochaWebSearch。 */
    private static ChatResponse responseWithMisspelledToolCall() {
        AssistantMessage am = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "BoochaWebSearch", "{}")))
                .build();
        return ChatResponse.builder().generations(List.of(new Generation(am))).build();
    }

    @Test
    void misspelledToolName_returnsToolNotFoundResult_notThrow() {
        Stub stub = new Stub(
                new IllegalStateException("No ToolCallback found for tool name: BoochaWebSearch"), null);
        ResilientToolCallingManager mgr = new ResilientToolCallingManager(stub);

        ToolExecutionResult result = mgr.executeToolCalls(promptWithTools(), responseWithMisspelledToolCall());

        // 委托被调用过（异常确实来自解析），且返回的是 tool 结果而非抛异常
        assertEquals(1, stub.calls, "应委托原 manager（异常来自它）");
        ToolResponseMessage last = (ToolResponseMessage) result.conversationHistory()
                .get(result.conversationHistory().size() - 1);
        ToolResponseMessage.ToolResponse resp = last.getResponses().get(0);
        assertEquals("BoochaWebSearch", resp.name(), "响应应对应模型请求的工具名");
        assertTrue(resp.responseData().contains("不存在"), "应明确告知工具不存在，实际=" + resp.responseData());
        // 工具清单本就随请求发给模型了，错误提示不再重复列出（见 ResilientToolCallingManager javadoc）。
        assertFalse(resp.responseData().contains("可用工具"),
                "不应在错误提示里重复列工具清单，实际=" + resp.responseData());
        assertTrue(resp.responseData().contains("正确"),
                "应提示用正确工具名重试，实际=" + resp.responseData());
    }

    @Test
    void nonResolutionException_isRethrown() {
        RuntimeException boom = new IllegalStateException("something else");
        Stub stub = new Stub(boom, null);
        ResilientToolCallingManager mgr = new ResilientToolCallingManager(stub);

        try {
            mgr.executeToolCalls(promptWithTools(), responseWithMisspelledToolCall());
        } catch (IllegalStateException e) {
            assertSame(boom, e, "非工具名解析失败异常应原样抛出");
            return;
        }
        throw new AssertionError("应抛出异常");
    }

    @Test
    void successPath_isDelegated() {
        ToolExecutionResult ok = ToolExecutionResult.builder().conversationHistory(List.of()).build();
        Stub stub = new Stub(null, ok);
        ResilientToolCallingManager mgr = new ResilientToolCallingManager(stub);

        assertSame(ok, mgr.executeToolCalls(promptWithTools(), responseWithMisspelledToolCall()),
                "正常路径应原样透传");
        assertEquals(1, stub.calls);
    }
}

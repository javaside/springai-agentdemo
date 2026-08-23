package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 对 {@link org.springframework.ai.model.tool.DefaultToolCallingManager} 的容错包装：
 * 工具名解析失败（模型把工具名拼错/编造，如 {@code BochaWebSearch} 写成 {@code BoochaWebSearch}）
 * 时，<b>不再让整个回合崩掉</b>，而是把「工具 X 不存在，请使用正确的工具名重新调用」作为一条
 * tool 结果回给模型，让模型自己纠正工具名后继续。
 *
 * <p><b>为什么需要它</b>：Spring AI 的 {@code DefaultToolCallingManager.executeToolCall} 在
 * {@code ToolCallbackResolver.resolve(name)} 返回 null 时直接抛 {@link IllegalStateException}
 * （"No ToolCallback found for tool name: X"），异常沿 advisor 链一路上抛，
 * {@code CodingAgent.handleError} 把整回合标成失败——模型一次拼错就毁掉整个回合，白白浪费
 * 一次调用。而把「工具不存在」回给模型（与正常工具结果同路径）是 agent 的标准纠错手段：
 * 模型看到提示后会改用正确工具名重试。
 *
 * <p><b>实现</b>：先尝试委托原 manager；仅在捕获到「No ToolCallback found」异常时介入，
 * 构造一条 {@link ToolResponseMessage}（对每个未解析出的 tool_call 说明该名字不存在），
 * 与请求历史一起拼成 {@link ToolExecutionResult} 返回——形状与正常工具执行结果完全一致，
 * Spring AI 会照常喂回模型。其余异常原样抛出。
 *
 * <p><b>为什么不在提示里列可用工具清单</b>：工具清单本就在每次请求里随 tools 发给模型，
 * 模型手里就有；错误提示再列一遍是重复噪音，且 {@code prompt.getOptions()} 的 toolCallbacks
 * 未必等于发给模型的那份（defaultTools 烘焙在 ChatClient 里，per-request 可能只覆盖部分）。
 * 只提示「不存在 + 用正确名字重试」，模型会从自己看到的工具列表里挑对的。
 */
public final class ResilientToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;

    public ResilientToolCallingManager(ToolCallingManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        try {
            return delegate.executeToolCalls(prompt, chatResponse);
        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if (msg == null || !msg.contains("No ToolCallback found")) {
                throw e;                       // 非工具名解析失败：原样抛出
            }
            return toolNotFoundResult(prompt, chatResponse, msg);
        }
    }

    /**
     * 把「工具名解析失败」转成一条 tool 结果回给模型。
     *
     * @param notFoundMessage 形如 "No ToolCallback found for tool name: X"（可能有 spring ai 前缀）
     */
    private ToolExecutionResult toolNotFoundResult(Prompt prompt, ChatResponse chatResponse, String notFoundMessage) {
        String unknown = extractUnknownToolName(notFoundMessage);
        AssistantMessage assistant = firstAssistantMessage(chatResponse);

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        if (assistant != null && assistant.getToolCalls() != null) {
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                String name = call.name();
                String data = toolNotFoundData(name, unknown);
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), name, data));
            }
        }
        // 兜底：即使拿不到 assistant message 也保证至少一条响应（带未知工具名）。
        if (responses.isEmpty()) {
            String data = toolNotFoundData(unknown, unknown);
            responses.add(new ToolResponseMessage.ToolResponse("unknown-tool-call", unknown, data));
        }

        List<Message> history = new ArrayList<>(prompt.getInstructions());
        if (assistant != null) {
            history.add(assistant);
        }
        history.add(ToolResponseMessage.builder().responses(responses).build());

        return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
    }

    /** 从未知工具名异常消息里抽出工具名（消息里是 "tool name: X"）。 */
    private static String extractUnknownToolName(String message) {
        int idx = message.indexOf("tool name: ");
        if (idx >= 0) {
            return message.substring(idx + "tool name: ".length()).trim();
        }
        return message;
    }

    private static String toolNotFoundData(String requestedName, String unknown) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具 \"").append(requestedName).append("\" 不存在");
        if (unknown != null && !unknown.isEmpty() && !unknown.equals(requestedName)) {
            sb.append("（错误工具名：").append(unknown).append("）");
        }
        sb.append("。请使用正确的工具名重新调用。");
        return sb.toString();
    }

    private static AssistantMessage firstAssistantMessage(ChatResponse chatResponse) {
        if (chatResponse != null && chatResponse.getResults() != null) {
            for (Generation g : chatResponse.getResults()) {
                if (g != null && g.getOutput() instanceof AssistantMessage) {
                    return (AssistantMessage) g.getOutput();
                }
            }
        }
        return null;
    }
}

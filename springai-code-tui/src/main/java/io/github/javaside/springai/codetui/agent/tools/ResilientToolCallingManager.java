package io.github.javaside.springai.codetui.agent.tools;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 对 {@link org.springframework.ai.model.tool.DefaultToolCallingManager} 的容错包装：
 * 工具名解析失败（模型把工具名拼错/编造，如 {@code BochaWebSearch} 写成 {@code BoochaWebSearch}）
 * 时，<b>不再让整个回合崩掉</b>，而是把「工具 X 不存在，请使用正确的工具名重新调用」等信息作为
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
 * 构造一条 {@link ToolResponseMessage} 回给模型，并利用 tool call 的<b>顺序</b>精确区分状态：
 * Spring AI 按序逐个解析+执行、失败即整体中断（见 {@code DefaultToolCallingManager.executeToolCall}），
 * 因此排在失败工具<b>之前</b>的确实已执行（结果随异常被框架丢弃）、失败工具本身报「不存在」、
 * 排在<b>之后</b>的从未被执行。三种回复各司其职，既不误导模型重复执行（已执行却报未执行），
 * 也不误导模型漏掉已产生的副作用。与请求历史一起拼成 {@link ToolExecutionResult} 返回——
 * 形状与正常工具执行结果完全一致，Spring AI 会照常喂回模型。其余异常原样抛出。
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
        Set<String> resolvable = resolvableToolNames(prompt);
        AssistantMessage assistant = firstAssistantMessage(chatResponse);

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        if (assistant != null && assistant.getToolCalls() != null) {
            List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
            // 失败工具在本批调用里的位置。Spring AI 按序逐个解析+执行、失败即整体中断，
            // 故：它之前的确实已执行（结果被框架丢弃）、它之后的从未被执行。
            int failedIndex = firstIndexOf(calls, unknown);
            for (int i = 0; i < calls.size(); i++) {
                AssistantMessage.ToolCall call = calls.get(i);
                String name = call.name();
                String data;
                if (failedIndex >= 0 && i < failedIndex) {
                    data = executedButResultLostData(name, unknown);
                } else if (unknown.equals(name)) {
                    data = notFoundData(name);
                } else if (failedIndex >= 0) {
                    data = notExecutedData(name, resolvable.contains(name), unknown);
                } else {
                    // 定位不到失败工具位置（异常消息解析不出名字）：不声称任何执行状态。
                    data = unknownStateData(name);
                }
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), name, data));
            }
        }
        // 兜底：即使拿不到 assistant message 也保证至少一条响应（带未知工具名）。
        if (responses.isEmpty()) {
            responses.add(new ToolResponseMessage.ToolResponse("unknown-tool-call", unknown, notFoundData(unknown)));
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

    /** 「工具不存在」：仅用于真正解析失败的那个工具名。 */
    private static String notFoundData(String name) {
        return "工具 \"" + name + "\" 不存在。请使用正确的工具名重新调用。";
    }

    /**
     * 「已执行但结果未回传」：排在失败工具之前，按序执行必然已完成（否则失败点会提前）；
     * 结果随异常被框架整体丢弃。必须点明「已执行」，否则模型会盲目重试、重复副作用。
     */
    private static String executedButResultLostData(String name, String failedName) {
        return "工具 \"" + name + "\" 已执行，但结果未回传：同批工具调用因工具 \"" + failedName
                + "\" 名称解析失败而整体中断。请勿盲目重试，先检查 " + name + " 的执行效果再决定下一步。";
    }

    /** 「未执行」：排在失败工具之后，循环在失败处中断，从未轮到这些工具。可解析的工具明确告知它可用。 */
    private static String notExecutedData(String name, boolean resolvable, String failedName) {
        return resolvable
                ? "工具 \"" + name + "\" 可解析但未执行：同批工具调用因工具 \"" + failedName
                        + "\" 名称解析失败而中断，请重新发起调用。"
                : "工具 \"" + name + "\" 未执行：同批工具调用因工具 \"" + failedName
                        + "\" 名称解析失败而中断，请重新发起调用。";
    }

    /** 定位不到失败工具位置时的中性兜底：不声称任何执行状态。 */
    private static String unknownStateData(String name) {
        return "工具 \"" + name + "\" 的执行状态无法确认：同批工具调用因工具名解析失败而中断。"
                + "请先核对相关状态，再决定是否重试。";
    }

    /** 名字在 tool calls 里的首个下标；找不到返回 -1。 */
    private static int firstIndexOf(List<AssistantMessage.ToolCall> calls, String name) {
        for (int i = 0; i < calls.size(); i++) {
            if (name.equals(calls.get(i).name())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 当前请求中工具解析器可见的工具名集合（来自 prompt options 的 toolCallbacks）。
     *
     * <p><b>覆盖范围</b>：真实解析是「toolCallbacks 按名匹配 → {@code resolver.resolve} 兜底」两段，
     * 这里只覆盖第一段；当前接线中 resolver 是空实现（{@code DelegatingToolCallbackResolver(List.of())}），
     * 故判断是完备的。若将来配置了 MCP 等 resolver，需同步扩展此方法。
     */
    private static Set<String> resolvableToolNames(Prompt prompt) {
        if (prompt == null || !(prompt.getOptions() instanceof ToolCallingChatOptions opts)
                || opts.getToolCallbacks() == null) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (ToolCallback cb : opts.getToolCallbacks()) {
            if (cb != null && cb.getToolDefinition() != null && cb.getToolDefinition().name() != null) {
                names.add(cb.getToolDefinition().name());
            }
        }
        return names;
    }

    /**
     * 取「第一个带非空 toolCalls 的 generation」——与 {@code DefaultToolCallingManager.executeToolCalls}
     * 筛选 generation 的条件一致（框架只处理这个 generation 的 tool calls，报错必须对到同一处，
     * 否则错误信息会发错工具名）。
     */
    private static AssistantMessage firstAssistantMessage(ChatResponse chatResponse) {
        if (chatResponse != null && chatResponse.getResults() != null) {
            for (Generation g : chatResponse.getResults()) {
                AssistantMessage am = g == null ? null : g.getOutput();
                if (am != null && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                    return am;
                }
            }
        }
        return null;
    }
}

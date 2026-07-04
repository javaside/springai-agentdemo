package com.example.springai.codetui.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * provider 中立的子 agent 执行器。前台串行：在主 agent 的 Task 工具调用内同步执行，
 * 子 agent 内部工具活动经带 taskId 的工具事件实时上报（见 {@link ToolEventCallback}）。
 *
 * <p>用激活 provider 的 chatModel 建子 agent 专用 ChatClient（过滤后工具 + system=spec.systemPrompt
 * + ToolCallAdvisor），<b>不挂</b> SessionMemory advisor——子 agent 上下文独立。model 空→跟随激活 provider。
 */
public final class SubagentRunner {

    private final ProviderRegistry registry;
    private final List<ToolCallback> tools;   // 已被 ToolEventCallback 装饰、带 root 边界的主 agent 工具列表
    private final AgentListener listener;
    private final Supplier<String> taskIdSupplier;

    public SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener) {
        this(registry, tools, listener, () -> "task_" + UUID.randomUUID());
    }

    SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                   Supplier<String> taskIdSupplier) {
        this.registry = registry;
        this.tools = tools;
        this.listener = listener;
        this.taskIdSupplier = taskIdSupplier;
    }

    /** 执行一次委派，返回子 agent 最终文本。parentTurnId=发起 Task 的回合。 */
    public String run(SubagentSpec spec, String prompt, String description, long parentTurnId) {
        String taskId = taskIdSupplier.get();
        listener.onSubagentStarted(parentTurnId, taskId, spec.name(), description);
        try {
            ChatClient client = ChatClient.builder(registry.active().chatModel())
                    .defaultToolCallbacks(filterTools(tools, spec))
                    .defaultAdvisors(ToolCallAdvisor.builder().build())
                    .build();
            ChatOptions options = resolveOptions(spec);
            String result = client.prompt()
                    .system(spec.systemPrompt())
                    .user(prompt)
                    // .options 接收 native builder（与 CodingAgent.submit 一致，mutate 保留 maxTokens 等）
                    .options(options.mutate())
                    // 子 agent 内部工具事件带上 parentTurnId + taskId（供 TUI 缩进）
                    .toolContext(Map.of(ToolEventCallback.TURN_ID_KEY, parentTurnId,
                            ToolEventCallback.TASK_ID_KEY, taskId))
                    .call()
                    .content();
            String finalText = result == null ? "" : result;
            listener.onSubagentFinished(parentTurnId, taskId, finalText, true);
            return finalText;
        } catch (RuntimeException ex) {
            listener.onSubagentFinished(parentTurnId, taskId, "子 agent 执行失败：" + ex.getMessage(), false);
            throw ex;
        }
    }

    /** model 空→激活 provider 默认（activeChatOptions）；否则用 spec.model 覆盖（走激活 provider 的 options）。 */
    private ChatOptions resolveOptions(SubagentSpec spec) {
        if (spec.model() == null || spec.model().isBlank()) {
            return registry.activeChatOptions();
        }
        // provider:model 的跨家路由留待 v2（spec §12）；v1 先在激活 provider 上按模型名覆盖。
        String modelId = spec.model().contains(":")
                ? spec.model().substring(spec.model().indexOf(':') + 1)
                : spec.model();
        return registry.active().options(modelId);
    }

    /** 按 allow（空=全部）过滤、再按 deny 剔除。按真实注册名精确匹配。 */
    static List<ToolCallback> filterTools(List<ToolCallback> all, SubagentSpec spec) {
        List<ToolCallback> result = new ArrayList<>();
        for (ToolCallback t : all) {
            String name = t.getToolDefinition().name();
            boolean allowed = spec.allowTools().isEmpty() || spec.allowTools().contains(name);
            boolean denied = spec.denyTools().contains(name);
            if (allowed && !denied) {
                result.add(t);
            }
        }
        return result;
    }
}

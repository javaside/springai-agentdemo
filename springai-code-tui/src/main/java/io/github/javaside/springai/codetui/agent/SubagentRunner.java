package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.client.ChatClient;
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
 * <p>用激活 provider 的 chatModel 建子 agent 专用 ChatClient（过滤后工具 + system=spec.systemPrompt），
 * <b>不挂</b> SessionMemory advisor——子 agent 上下文独立。model 空→跟随激活 provider。
 *
 * <p><b>工具调用（Spring AI 2.0）</b>：2.0 已把工具执行循环从 ChatModel 内部搬进 advisor 链，且
 * {@code ChatClient.builder(model)} 会<b>自动注册</b> {@code ToolCallingAdvisor}（带 observation 的
 * {@code ToolCallingManager}），故这里<b>不再</b>显式挂 {@code ToolCallAdvisor}（该类 2.0 起 deprecated 且待删除；
 * 显式挂反而会抑制自动注册、丢掉工具调用可观测性）。与主 agent（{@code AgentTools}）一致：只 {@code defaultTools}，
 * 工具循环交给自动注册的 advisor。
 */
public final class SubagentRunner {

    private final ProviderRegistry registry;
    private final List<ToolCallback> tools;   // 已被 ToolEventCallback 装饰、带 root 边界的主 agent 工具列表
    private final AgentListener listener;
    private final String projectInstructions;   // AGENTS.md 项目指令；追加到每个子 agent 的 spec 系统提示（可空）
    private final Supplier<String> taskIdSupplier;

    public SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                          String projectInstructions) {
        this(registry, tools, listener, projectInstructions, () -> "task_" + UUID.randomUUID());
    }

    SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                   String projectInstructions, Supplier<String> taskIdSupplier) {
        this.registry = registry;
        this.tools = tools;
        this.listener = listener;
        this.projectInstructions = projectInstructions == null ? "" : projectInstructions;
        this.taskIdSupplier = taskIdSupplier;
    }

    /** 执行一次委派，返回子 agent 最终文本。parentTurnId=发起 Task 的回合。 */
    public String run(SubagentSpec spec, String prompt, String description, long parentTurnId) {
        String taskId = taskIdSupplier.get();
        listener.onSubagentStarted(parentTurnId, taskId, spec.name(), description);
        try {
            // Spring AI 2.0：defaultTools 取代已废弃的 defaultToolCallbacks；工具调用 advisor 由 ChatClient 自动注册，
            // 不再显式挂（见类注释）。传 Object[]（每个元素是 ToolCallback）——与主 agent 的 defaultTools(toolsWithTask) 同构。
            ChatClient client = ChatClient.builder(registry.active().chatModel())
                    .defaultTools(filterTools(tools, spec).toArray())
                    .build();
            ChatOptions options = resolveOptions(spec);
            String result = client.prompt()
                    .system(effectiveSystemPrompt(spec))
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

    /** 子 agent 有效系统提示：spec 自身提示 + 项目指令（非空时追加）。纯函数，便于单测。 */
    String effectiveSystemPrompt(SubagentSpec spec) {
        if (projectInstructions.isEmpty()) {
            return spec.systemPrompt();
        }
        return spec.systemPrompt() + "\n\n" + projectInstructions;
    }

    /** 测试钩子：子 agent 可见工具（未经 spec 过滤）的注册名——校验主 agent 独有工具（如记忆工具）不泄漏给子 agent。 */
    List<String> toolNamesForTest() {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
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

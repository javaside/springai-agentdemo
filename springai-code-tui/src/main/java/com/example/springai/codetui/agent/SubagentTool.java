package com.example.springai.codetui.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 自写的 Task 工具：把子任务委派给一个子 agent。精简输入 schema（v1 无 run_in_background/resume）。
 * 不依赖框架 TaskTool——路由（subagent_type → SubagentSpec）就在本类，执行交给注入的 {@link Dispatcher}。
 */
public final class SubagentTool {

    private static final String DESCRIPTION_TEMPLATE = """
            Launch a specialized subagent to handle a complex, multi-step subtask autonomously.

            Each subagent has its own context, system prompt, and restricted tool set. Use this to
            delegate focused work (exploring the codebase, designing a plan, running commands) so the
            main conversation stays clean. The subagent returns a single final message as the result.

            Available subagent types:
            %s

            Usage:
            - Always give a short description (3-5 words) of what the subagent will do.
            - Give a detailed, self-contained prompt: the subagent does not see the main conversation.
            - Tell the subagent whether you want it to just research/read or to actually make changes.
            - Choose subagent_type from the list above.
            """;

    private SubagentTool() {
    }

    /** 子 agent 调用入参（精简）：只有 description / prompt / subagent_type。 */
    public record SubagentCall(
            @ToolParam(description = "3-5 word summary of what the subagent will do") String description,
            @ToolParam(description = "Detailed, self-contained task prompt for the subagent") String prompt,
            @ToolParam(description = "Which subagent type to use") String subagent_type) {
    }

    /** 把一次委派交给执行器（SubagentRunner.run 的函数式视图）。turnId=当前回合。 */
    @FunctionalInterface
    public interface Dispatcher {
        String dispatch(SubagentSpec spec, String prompt, String description, long turnId);
    }

    /** 构建名为 "Task" 的 ToolCallback。turnId 从 ThreadLocal 取（同主流工具，装配时落实）。 */
    public static ToolCallback create(Map<String, SubagentSpec> specs, Dispatcher dispatcher) {
        String roster = specs.values().stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        return FunctionToolCallback.builder("Task", function(specs, dispatcher))
                .description(DESCRIPTION_TEMPLATE.formatted(roster))
                .inputType(SubagentCall.class)
                .build();
    }

    /** 纯路由函数：subagent_type → spec，未知则抛清晰错误；命中则交 dispatcher（turnId 由 Dispatcher 实现内部经 ThreadLocal 取，这里传 -1L 占位）。 */
    static Function<SubagentCall, String> function(Map<String, SubagentSpec> specs, Dispatcher dispatcher) {
        return callArgs -> {
            SubagentSpec spec = specs.get(callArgs.subagent_type());
            if (spec == null) {
                throw new RuntimeException("No subagent found with type: " + callArgs.subagent_type()
                        + ". Available: " + String.join(", ", specs.keySet()));
            }
            return dispatcher.dispatch(spec, callArgs.prompt(), callArgs.description(), -1L);
        };
    }
}

package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.ArrayList;
import java.util.List;
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

    private static final String PARALLEL_DESCRIPTION_TEMPLATE = """
            Launch MULTIPLE subagents CONCURRENTLY to handle several INDEPENDENT subtasks at once.

            Use this ONLY when you have 2+ subtasks that are mutually independent and share no state
            (e.g. investigating unrelated failures, exploring separate subsystems). If subtasks depend
            on each other or need shared context, use the single Task tool instead.

            Each subtask has the same shape as Task: description, prompt, subagent_type. All subtasks
            run in parallel; results are returned together, one block per subtask (in input order),
            each marked success/failure independently — one failing subtask does not abort the others.

            Available subagent types:
            %s
            """;

    private SubagentTool() {
    }

    /** 子 agent 调用入参（精简）：只有 description / prompt / subagent_type。 */
    public record SubagentCall(
            @ToolParam(description = "3-5 word summary of what the subagent will do") String description,
            @ToolParam(description = "Detailed, self-contained task prompt for the subagent") String prompt,
            @ToolParam(description = "Which subagent type to use") String subagent_type) {
    }

    /** 批量子 agent 调用入参：一组子任务，每个复用单任务的三元组。 */
    public record ParallelCall(
            @ToolParam(description = "List of independent subtasks to run concurrently") List<SubagentCall> tasks) {
    }

    /** 把一次委派交给执行器（SubagentRunner.run 的函数式视图）。turnId=当前回合。 */
    @FunctionalInterface
    public interface Dispatcher {
        String dispatch(SubagentSpec spec, String prompt, String description, long turnId);
    }

    /** 把一批路由后的委派交给并发执行器（SubagentRunner.runAll 的函数式视图），按入参顺序返回各自结果文本。 */
    @FunctionalInterface
    public interface BatchDispatcher {
        List<String> dispatch(List<SubagentRunner.Dispatch> dispatches, long parentTurnId);
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

    /** 构建名为 "ParallelTasks" 的批量 ToolCallback。parentTurnId 由 BatchDispatcher 实现内部经 ThreadLocal 取，这里占位 -1L。 */
    public static ToolCallback createParallel(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher) {
        String roster = specs.values().stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        return FunctionToolCallback.builder("ParallelTasks", batchFunction(specs, dispatcher))
                .description(PARALLEL_DESCRIPTION_TEMPLATE.formatted(roster))
                .inputType(ParallelCall.class)
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

    /**
     * 批量路由函数：把每个 SubagentCall 路由成 SubagentRunner.Dispatch（未知类型该条降级为失败、<b>不抛</b>，
     * 服从失败隔离——与单任务 Task 抛异常有意不同）。已知条交 BatchDispatcher 并发执行，最后按入参顺序结构化汇总。
     */
    static Function<ParallelCall, String> batchFunction(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher) {
        return call -> {
            List<SubagentTool.SubagentCall> tasks = call.tasks() == null ? List.of() : call.tasks();
            List<SubagentRunner.Dispatch> dispatchable = new ArrayList<>();
            List<Integer> dispatchIndex = new ArrayList<>();
            String[] failure = new String[tasks.size()];
            String[] typeName = new String[tasks.size()];
            for (int i = 0; i < tasks.size(); i++) {
                SubagentTool.SubagentCall t = tasks.get(i);
                typeName[i] = t.subagent_type();
                SubagentSpec spec = t.subagent_type() == null ? null : specs.get(t.subagent_type());
                if (spec == null) {
                    failure[i] = "未知 subagent 类型: " + t.subagent_type()
                            + "（可用: " + String.join(", ", specs.keySet()) + "）";
                } else {
                    dispatchable.add(new SubagentRunner.Dispatch(spec, t.prompt(), t.description()));
                    dispatchIndex.add(i);
                }
            }
            List<String> ran = dispatcher.dispatch(dispatchable, -1L);
            String[] body = new String[tasks.size()];
            boolean[] ok = new boolean[tasks.size()];
            for (int i = 0; i < tasks.size(); i++) {
                if (failure[i] != null) { body[i] = failure[i]; ok[i] = false; }
            }
            for (int k = 0; k < ran.size(); k++) {
                int idx = dispatchIndex.get(k);
                String r = ran.get(k);
                boolean failed = r != null && r.startsWith("失败：");
                body[idx] = r;
                ok[idx] = !failed;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tasks.size(); i++) {
                if (i > 0) sb.append("\n\n");
                sb.append("[").append(i + 1).append("] ").append(typeName[i])
                        .append(ok[i] ? " ✓" : " ✗");
                sb.append("\n").append(body[i] == null ? "" : body[i]);
            }
            return sb.toString();
        };
    }
}

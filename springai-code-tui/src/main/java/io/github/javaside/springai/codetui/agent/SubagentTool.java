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
 * 自写的 Task 工具：把子任务委派给一个子 agent。精简输入 schema（暴露 run_in_background，仍不暴露 resume）。
 * 不依赖框架 TaskTool——路由（subagent_type → SubagentSpec）就在本类，执行交给注入的 {@link Dispatcher}
 * 或（后台时）{@link BackgroundDispatcher}。
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

            Foreground vs background:
            - DEFAULT to foreground (omit run_in_background or set false). Most coding workflows are
              sequential: explore → plan → implement → test. Each step needs the previous result.
            - Use run_in_background=true ONLY when ALL of these hold:
                1. The task is truly independent — its output does not determine your next action.
                2. You will retrieve the result later via TaskOutput once you need it.
                3. The task is read-only or uses only pre-approved tools (background tasks cannot
                   prompt for permission; calls needing approval are silently denied).
            - Do NOT use background merely because a task takes a long time. A slow foreground task
              that you need is always better than a background task whose result you have to wait for
              anyway.
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

    /** 子 agent 调用入参。{@code run_in_background} 缺省 / null 均视为前台（向后兼容）。
     *
     * @param description       3-5 词简述，进 UI 任务面板
     * @param prompt            自包含的任务提示（子 agent 看不到主对话）
     * @param subagent_type     子 agent 名，取值见 {@code SubagentLoader.loadBuiltins()}
     * @param run_in_background true = 后台执行；null / 缺省 = 前台，判空请用 {@link #background()}
     */
    public record SubagentCall(
            @ToolParam(description = "3-5 word summary of what the subagent will do") String description,
            @ToolParam(description = "Detailed, self-contained task prompt for the subagent") String prompt,
            @ToolParam(description = "Which subagent type to use") String subagent_type,
            @ToolParam(required = false, description =
                    "Background mode (default false/omitted = foreground). "
                    + "Set true ONLY when: the task is independent (you do not need its result to "
                    + "decide your next action), AND the task is read-only or uses only pre-approved "
                    + "tools (background tasks cannot prompt for permission — approval-gated calls are "
                    + "silently denied). Returns a task id immediately; retrieve the result later with "
                    + "TaskOutput. Do NOT set true just because the task is slow — if you need the "
                    + "result eventually in the same turn, foreground is always cleaner.") Boolean run_in_background) {

        /** null-safe：模型省略该字段时 Jackson 给 null，默认前台。 */
        public boolean background() {
            return Boolean.TRUE.equals(run_in_background);
        }
    }

    /** 批量子 agent 调用入参。
     *
     * @param tasks             并发执行的子任务列表，结果按入参顺序返回
     * @param run_in_background 整批是否后台执行；null / 缺省 = 前台，判空请用 {@link #background()}
     */
    public record ParallelCall(
            @ToolParam(description = "List of independent subtasks to run concurrently") List<SubagentCall> tasks,
            @ToolParam(required = false, description =
                    "Background mode for the whole batch (default false/omitted = foreground). "
                    + "Set true only when none of the results are needed to decide your next action. "
                    + "Returns task ids immediately; retrieve results later with TaskOutput. "
                    + "Same constraint as Task background: permission-gated calls are silently denied.")
            Boolean run_in_background) {

        public boolean background() {
            return Boolean.TRUE.equals(run_in_background);
        }
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

    /** 后台派发（{@code SubagentRunner.runInBackground} 的函数式视图）：立刻返回含 taskId 的文本。 */
    @FunctionalInterface
    public interface BackgroundDispatcher {
        String dispatch(SubagentSpec spec, String prompt, String description);
    }

    /** 无后台能力的装配（回显桩 / 测试桩）：请求后台时明确告知不可用，<b>不</b>静默跑成前台。 */
    public static ToolCallback create(Map<String, SubagentSpec> specs, Dispatcher dispatcher) {
        return create(specs, dispatcher, null);
    }

    /** 构建名为 "Task" 的 ToolCallback。turnId 从 ThreadLocal 取（同主流工具，装配时落实）。 */
    public static ToolCallback create(Map<String, SubagentSpec> specs, Dispatcher dispatcher,
                                      BackgroundDispatcher background) {
        String roster = specs.values().stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        return FunctionToolCallback.builder("Task", function(specs, dispatcher, background))
                .description(DESCRIPTION_TEMPLATE.formatted(roster))
                .inputType(SubagentCall.class)
                .build();
    }

    /** 无后台能力的批量装配：同 {@link #create(Map, Dispatcher)}，后台请求逐条明确拒绝。 */
    public static ToolCallback createParallel(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher) {
        return createParallel(specs, dispatcher, null);
    }

    /** 构建名为 "ParallelTasks" 的批量 ToolCallback。parentTurnId 由 BatchDispatcher 实现内部经 ThreadLocal 取，这里占位 -1L。 */
    public static ToolCallback createParallel(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher,
                                              BackgroundDispatcher background) {
        String roster = specs.values().stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        return FunctionToolCallback.builder("ParallelTasks", batchFunction(specs, dispatcher, background))
                .description(PARALLEL_DESCRIPTION_TEMPLATE.formatted(roster))
                .inputType(ParallelCall.class)
                .build();
    }

    static final String NO_BACKGROUND =
            "后台模式不可用（当前装配未提供后台派发器）。请改用 run_in_background=false。";

    /** 无后台派发器的路由函数（老装配）。 */
    static Function<SubagentCall, String> function(Map<String, SubagentSpec> specs, Dispatcher dispatcher) {
        return function(specs, dispatcher, null);
    }

    /** 纯路由函数：subagent_type → spec，未知则抛清晰错误；命中后按 run_in_background 决定交前台还是后台派发器（turnId 由 Dispatcher 实现内部经 ThreadLocal 取，这里传 -1L 占位）。 */
    static Function<SubagentCall, String> function(Map<String, SubagentSpec> specs, Dispatcher dispatcher,
                                                   BackgroundDispatcher background) {
        return callArgs -> {
            SubagentSpec spec = specs.get(callArgs.subagent_type());
            if (spec == null) {
                throw new RuntimeException("No subagent found with type: " + callArgs.subagent_type()
                        + ". Available: " + String.join(", ", specs.keySet()));
            }
            if (callArgs.background()) {
                return background == null
                        ? NO_BACKGROUND
                        : background.dispatch(spec, callArgs.prompt(), callArgs.description());
            }
            return dispatcher.dispatch(spec, callArgs.prompt(), callArgs.description(), -1L);
        };
    }

    /** 无后台派发器的批量路由函数（老装配）。 */
    static Function<ParallelCall, String> batchFunction(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher) {
        return batchFunction(specs, dispatcher, null);
    }

    /**
     * 批量路由函数：把每个 SubagentCall 路由成 SubagentRunner.Dispatch（未知类型该条降级为失败、<b>不抛</b>，
     * 服从失败隔离——与单任务 Task 抛异常有意不同）。已知条交 BatchDispatcher 并发执行，最后按入参顺序结构化汇总。
     */
    static Function<ParallelCall, String> batchFunction(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher,
                                                        BackgroundDispatcher background) {
        return call -> {
            List<SubagentTool.SubagentCall> tasks = call.tasks() == null ? List.of() : call.tasks();
            // 整批后台：逐条派发，每条各自成败（含"队列已满未启动"），不整批拒绝——与既有失败隔离语义一致。
            // 放在前台路由之前短路，是为了让下面那段前台并发/汇总代码保持原样不被后台语义污染。
            boolean anyBackground = call.background() || tasks.stream().anyMatch(SubagentCall::background);
            if (anyBackground) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < tasks.size(); i++) {
                    SubagentCall t = tasks.get(i);
                    SubagentSpec spec = t.subagent_type() == null ? null : specs.get(t.subagent_type());
                    String body;
                    if (spec == null) {
                        body = "未知 subagent 类型: " + t.subagent_type()
                                + "（可用: " + String.join(", ", specs.keySet()) + "）";
                    } else if (background == null) {
                        body = NO_BACKGROUND;
                    } else {
                        body = background.dispatch(spec, t.prompt(), t.description());
                    }
                    if (i > 0) sb.append("\n\n");
                    sb.append("[").append(i + 1).append("] ").append(t.subagent_type()).append("\n").append(body);
                }
                return sb.toString();
            }
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

package io.github.javaside.springai.codetui.agent.background;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * {@code ListTasks} 工具：列出本进程全部后台子 agent 任务及其状态。
 *
 * <p><b>为什么单独一个类而不塞进 {@link BackgroundTaskTool}</b>：那个类名是单数、
 * 职责是「取<b>一个</b>任务的结果」，塞进去会让类名说谎。
 *
 * <p><b>为什么需要它</b>：{@code TaskOutput} 的 {@code task_id} 是必填，模型必须先知道 id；
 * 而 id 只存在于会话历史里那条 {@code Task} 的返回值中。{@code /compact} 之后 id 被压掉，
 * 任务还在跑，模型再也查不了——它能派后台任务，却看不见自己派了什么。
 *
 * <p><b>仅主 agent</b>（不进 {@code decoratedList}），与 {@code TaskOutput} 同一条理由：
 * 子 agent 拿不到 {@code Task}，自然也没有属于自己的后台任务，给了它只会让它列出别人的。
 */
public final class BackgroundTaskListTool {

    private static final String DESCRIPTION = """
            List all background subagent tasks started with Task(run_in_background=true) in this
            process, with their status. Use TaskOutput(task_id) to retrieve a finished task's
            result. Background tasks do not survive a process restart.
            """;

    /** 无参。四种状态一共不会超过注册表上限，加 status 过滤是替一个不存在的问题写代码。 */
    public record NoArgs() {
    }

    private BackgroundTaskListTool() {
    }

    /** 构建名为 "ListTasks" 的 ToolCallback。 */
    public static ToolCallback create(BackgroundTaskRegistry registry) {
        return FunctionToolCallback.builder("ListTasks",
                        (NoArgs a) -> BackgroundDigest.full(registry.all()))
                .description(DESCRIPTION)
                .inputType(NoArgs.class)
                .build();
    }
}

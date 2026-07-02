package com.example.springai.codetui.agent;

import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springaicommunity.agent.utils.AgentEnvironment;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

/**
 * AgentTools —— 组装编码 Agent 用的 {@link ChatClient} 的工厂。
 *
 * <p>把 5 个社区工具（FileSystem/Shell/Grep/Glob/TodoWrite）用 {@link ToolEventCallback} 装饰后
 * 注册进 ChatClient，并配上 {@link MessageChatMemoryAdvisor} 记忆与 {@link AgentEnvironment} 环境系统提示。
 *
 * <p><b>turnId 走 ThreadLocal</b>：TodoWriteTool 的 todoEventHandler 只收 {@link Todos}、拿不到 turnId，
 * 而 handler 是在工具 call 内<b>同线程同步</b>触发的，所以直接读
 * {@link ToolEventCallback#currentTurnId()}（当前正在执行工具的回合），不读实时 activeTurnId，
 * 取消/并发下不会串轮。因此 {@link #build} <b>没有</b> activeTurnId 参数。
 *
 * <p><b>安全边界（设计方案 B）</b>：只有 FileSystemTools 有强制的目录边界；Shell/Grep/Glob 在技术上
 * <b>不</b>受 root 限制（见 {@code AgentToolsSecurityTest}）。这里靠系统提示要求模型自律约束在 root 内，
 * 而非承诺技术强制。
 */
public final class AgentTools {

    private AgentTools() {
    }

    /** DeepSeek 模型名（V4 现役 v4-flash；旧 deepseek-chat/reasoner 将于 2026-07-24 停用）。运行时可经 /model 覆盖。 */
    private static final String MODEL_NAME = "deepseek-v4-flash";

    /**
     * 系统提示：把自己定位成编码 Agent。内嵌 3 个 {@link AgentEnvironment} 占位符
     * （键名与下面 {@code .param(...)} 一致：ENVIRONMENT_INFO / GIT_STATUS / AGENT_MODEL）。
     * 不注入知识截止——DeepSeek 无精确公开值，编一个只会误导模型自述。诚实声明只有 FileSystemTools 有强制边界。
     */
    private static final String SYSTEM_TEMPLATE = """
            你是一个运行在终端里的中文编码助手（coding agent），帮助用户在当前项目里读写代码、排查问题、完成开发任务。

            工作方式：
            - 动手改代码前，先用 Grep / Glob / read 把相关文件和上下文看清楚，理解现状再动手，不要臆测。
            - 当任务包含 3 个或更多明确步骤、或用户要求你组织任务时，先调用 TodoWrite 把工作拆成结构化清单再执行；
              同一时间只允许一个任务处于 in_progress，开始前标 in_progress、完成后立刻标 completed。
            - 修改文件后，用 Shell 运行构建 / 测试 / 检查命令来验证改动确实生效，再给出结论。
            - 回答简洁，聚焦用户的目标本身。

            关于工作边界（请务必遵守）：
            - 只有文件读写工具（FileSystemTools）有被强制限定的目录边界；
              Shell / Grep / Glob 在技术上并未被限制在项目根目录内。
            - 因此请自我约束：所有操作都应发生在当前项目根目录之内，不要用绝对路径或 ../ 去访问项目之外的文件，
              也不要执行会影响项目之外的命令。

            <environment_info>
            {ENVIRONMENT_INFO}
            </environment_info>

            <git_status>
            {GIT_STATUS}
            </git_status>

            当前模型：{AGENT_MODEL}。
            """;

    /**
     * 组装编码 Agent 的 ChatClient。仅做装配，不发起任何网络请求，也不要求有效的 API key。
     *
     * @param model    DeepSeek 模型（由上游创建）
     * @param root     项目根目录（FileSystemTools 的强制边界 + Grep/Glob 的默认工作目录）
     * @param listener 工具 / Todo 事件出口
     *                 <p>注：conversationId（会话记忆）由 {@code CodingAgent.submit} 每次请求传入，
     *                 不在装配期绑定，故此处不需要 sessionId 参数。
     */
    public static ChatClient build(DeepSeekChatModel model, Path root, AgentListener listener) {
        FileSystemTools fs = FileSystemTools.builder().allowedDirectory(root).build();
        ShellTools sh = ShellTools.builder().build();
        GrepTool grep = GrepTool.builder().workingDirectory(root).build();
        GlobTool glob = GlobTool.builder().workingDirectory(root).build();
        TodoWriteTool todo = TodoWriteTool.builder()
                // turnId 走 ThreadLocal（handler 在工具 call 内同步触发），不读实时 activeTurnId
                .todoEventHandler(todos ->
                        listener.onTodoUpdated(ToolEventCallback.currentTurnId(), toLines(todos)))
                .build();

        // org.springframework.ai.support.ToolCallbacks（spring-ai-model）
        ToolCallback[] raw = ToolCallbacks.from(fs, sh, grep, glob, todo);
        ToolCallback[] decorated = new ToolCallback[raw.length];
        for (int i = 0; i < raw.length; i++) {
            decorated[i] = new ToolEventCallback(raw[i], listener);
        }

        return ChatClient.builder(model)
                .defaultSystem(s -> s.text(SYSTEM_TEMPLATE)
                        .param(AgentEnvironment.ENVIRONMENT_INFO_KEY, AgentEnvironment.info())
                        .param(AgentEnvironment.GIT_STATUS_KEY, AgentEnvironment.gitStatus())
                        .param(AgentEnvironment.AGENT_MODEL_KEY, MODEL_NAME))
                .defaultTools((Object[]) decorated)
                // 不手动加 ToolCallingAdvisor：2.0 里注册了 .tools/.defaultTools 会自动挂上工具调用循环。
                // conversationId 由 CodingAgent.submit 每次请求传入，不在这里绑定。
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(
                        MessageWindowChatMemory.builder().build()).build())
                .build();
    }

    /** 把 {@link Todos} 转成可显示的行：状态标记 + 内容。 */
    static List<String> toLines(Todos todos) {
        if (todos == null || todos.todos() == null) {
            return List.of();
        }
        return todos.todos().stream()
                .map(item -> statusMarker(item.status()) + " " + item.content())
                .toList();
    }

    private static String statusMarker(Todos.Status status) {
        if (status == null) {
            return "○";
        }
        return switch (status) {
            case completed -> "✓";     // 完成：打钩
            case in_progress -> "▶";   // 进行中：可区分状态
            case pending -> "○";       // 待办
        };
    }
}

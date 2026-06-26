package com.example.springai.agent.demo;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.IOException;
import java.util.List;

/**
 * 示例 7：TodoWrite 任务清单 —— 用第三方社区库 spring-ai-agent-utils 的 {@link TodoWriteTool}。
 *
 * <p>TodoWrite 不是“业务待办存储”，而是给 Agent 自己用的任务管理工具：当用户给出复杂目标时，
 * 模型先把工作拆成结构化 todo，再随着执行推进把任务从 pending 改成 in_progress / completed。
 * 这样控制台能实时看到 Agent 当前在做什么、已经完成了多少。
 *
 * <p>本示例保持纯 Java，不引入 Spring Boot 事件体系；直接通过 {@code todoEventHandler} 把每次
 * TodoWrite 更新渲染到控制台。上游 Boot 示例里可以把同一个 handler 接到 ApplicationEvent、
 * WebSocket 或 UI，这里为了教学只打印文本。
 */
public class TodoWriteToolDemo implements Demo {

    private static final String TODO_SYSTEM_PROMPT = """
            你是一个会显式管理任务进度的中文 AI 助手。
            当任务包含 3 个或更多明确步骤，或用户要求你组织任务时，必须先调用 TodoWrite 创建任务清单。
            工作过程中只允许一个任务处于 in_progress；开始某项前先把它标为 in_progress，完成后立刻标为 completed。
            Todo 的 content 使用中文祈使句，activeForm 使用“正在……”形式。
            最终回答要简短总结完成了哪些步骤，以及给出结果。
            """;

    private final ChatClient chatClient;

    public TodoWriteToolDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "TodoWrite 任务清单（TodoWriteTool · 第三方 spring-ai-agent-utils）";
    }

    @Override
    public int order() {
        return 7;
    }

    @Override
    public void run() {
        String task = """
                请帮我组织一个小型开发计划：为在线商城增加优惠券功能。
                你需要先拆解任务，再依次说明数据模型、接口、测试点和发布注意事项。
                请使用 TodoWrite 来组织你的任务。
                """;
        ConsoleDashboard dashboard = new ConsoleDashboard(clean(task), DashboardRenderer.create());
        dashboard.render();

        TodoWriteTool rawTodoWriteTool = TodoWriteTool.builder()
                .todoEventHandler(todos -> dashboard.updateTodos(todos).render())
                .build();
        ToolCallback todoWriteTool = new LoggingTodoWriteTool(ToolCallbacks.from(rawTodoWriteTool)[0], dashboard);

        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(80).build();
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder().build();
        RoundLoggingAdvisor roundLogger = new RoundLoggingAdvisor(ToolCallingAdvisor.DEFAULT_ORDER + 100, dashboard);

        String answer = chatClient.prompt()
                .system(TODO_SYSTEM_PROMPT)
                .user(task)
                .tools(todoWriteTool)
                .advisors(toolCallingAdvisor, memoryAdvisor, roundLogger)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "todo-write-demo"))
                .call()
                .content();

        dashboard.finish(answer).render();
        System.out.println("\n答：\n" + answer);
        System.out.println("""

                说明：TodoWriteTool 会校验每次提交的任务清单：任务内容不能为空，状态只能是
                pending / in_progress / completed，且同一时间最多只能有一个 in_progress。
                本示例通过 JLine Display 在真实终端中增量刷新固定控制台面板，因此任务确认后只刷新状态，
                不会每次更新都追加一整屏日志；非交互环境会自动退回普通文本输出。
                """);
    }

    static String renderProgress(Todos todos) {
        int completed = (int) todos.todos().stream()
                .filter(todo -> todo.status() == Todos.Status.completed)
                .count();
        int total = todos.todos().size();
        int percent = total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);

        StringBuilder out = new StringBuilder();
        out.append("Progress: ")
                .append(completed)
                .append("/")
                .append(total)
                .append(" tasks completed (")
                .append(percent)
                .append("%)\n");

        for (TodoItem item : todos.todos()) {
            out.append("  ")
                    .append(statusMarker(item.status()))
                    .append(" ")
                    .append(item.content())
                    .append("\n");
        }
        return out.toString();
    }

    static String describeMessage(Message message) {
        if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            String calls = assistantMessage.getToolCalls().stream()
                    .map(toolCall -> toolCall.name() + "(" + clean(toolCall.arguments()) + ")")
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            return "[ASSISTANT] 请求调用工具 " + calls;
        }
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            String responses = toolResponseMessage.getResponses().stream()
                    .map(response -> response.name() + " 返回: " + truncate(clean(response.responseData()), 120))
                    .reduce((left, right) -> left + "；" + right)
                    .orElse("");
            return "[TOOL] " + responses;
        }
        return "[" + message.getMessageType() + "] " + truncate(clean(message.getText()), 160);
    }

    static final class ConsoleDashboard {

        private final String task;
        private final DashboardRenderer renderer;
        private Todos todos = new Todos(List.of());
        private String round = "-";
        private String availableTools = "-";
        private String interactionStatus = "-";
        private String modelMessage = "-";
        private String toolCallRequest = "-";
        private String latestToolEvent = "-";
        private String finalAnswer = "";

        ConsoleDashboard(String task, DashboardRenderer renderer) {
            this.task = task;
            this.renderer = renderer;
        }

        ConsoleDashboard(String task, boolean animated) {
            this(task, animated ? DashboardRenderer.create() : new PlainDashboardRenderer());
        }

        ConsoleDashboard updateTodos(Todos todos) {
            this.todos = todos;
            return this;
        }

        ConsoleDashboard updateRound(int round, String availableTools) {
            this.round = String.valueOf(round);
            this.availableTools = availableTools;
            this.interactionStatus = "等待模型响应...";
            return this;
        }

        ConsoleDashboard updateAssistantResponse(String modelMessage, String toolCallRequest) {
            this.interactionStatus = "已收到模型响应";
            this.modelMessage = modelMessage == null || modelMessage.isBlank() ? "-" : modelMessage;
            this.toolCallRequest = toolCallRequest == null || toolCallRequest.isBlank() ? "-" : toolCallRequest;
            return this;
        }

        ConsoleDashboard updateToolEvent(String latestToolEvent) {
            this.latestToolEvent = latestToolEvent;
            return this;
        }

        ConsoleDashboard finish(String finalAnswer) {
            this.finalAnswer = finalAnswer == null ? "" : finalAnswer;
            this.interactionStatus = "完成";
            this.modelMessage = truncate(clean(this.finalAnswer), 160);
            return this;
        }

        void render() {
            renderer.render(renderFrame());
        }

        String renderFrame() {
            int completed = (int) todos.todos().stream()
                    .filter(todo -> todo.status() == Todos.Status.completed)
                    .count();
            int total = todos.todos().size();
            int percent = total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);

            StringBuilder out = new StringBuilder();
            out.append("TodoWrite 任务进度\n");
            out.append("任务: ").append(task).append("\n");
            out.append("进度: ").append(completed).append("/").append(total).append(" (").append(percent).append("%)\n\n");
            out.append("任务清单\n");
            if (todos.todos().isEmpty()) {
                out.append("  （等待模型调用 TodoWrite 创建任务清单）\n");
            }
            else {
                for (TodoItem item : todos.todos()) {
                    out.append("  ").append(statusMarker(item.status())).append(" ").append(item.content()).append("\n");
                }
            }
            out.append("\n模型交互\n");
            out.append("  当前轮次: ").append(round).append("\n");
            out.append("  可用工具: ").append(availableTools).append("\n");
            out.append("  状态: ").append(interactionStatus).append("\n");
            out.append("  大模型返回: ").append(modelMessage).append("\n");
            out.append("  工具调用请求: ").append(toolCallRequest).append("\n");
            out.append("  最近工具: ").append(latestToolEvent).append("\n");
            if (!finalAnswer.isBlank()) {
                out.append("\n最终摘要\n");
                out.append("  ").append(truncate(clean(finalAnswer), 160)).append("\n");
            }
            return out.toString();
        }
    }

    interface DashboardRenderer {

        void render(String frame);

        static DashboardRenderer create() {
            if (System.console() == null) {
                return new PlainDashboardRenderer();
            }
            try {
                return new JLineDashboardRenderer();
            }
            catch (IOException | RuntimeException ex) {
                return new PlainDashboardRenderer();
            }
        }
    }

    private static final class PlainDashboardRenderer implements DashboardRenderer {

        @Override
        public void render(String frame) {
            System.out.print(frame);
            System.out.flush();
        }
    }

    private static final class JLineDashboardRenderer implements DashboardRenderer {

        private final Terminal terminal;
        private final Display display;

        private JLineDashboardRenderer() throws IOException {
            this.terminal = TerminalBuilder.builder()
                    .name("todo-write-demo")
                    .system(true)
                    .build();
            this.display = new Display(terminal, false);
        }

        @Override
        public void render(String frame) {
            List<AttributedString> lines = frame.lines()
                    .map(AttributedString::fromAnsi)
                    .toList();
            display.resize(terminal.getHeight(), terminal.getWidth());
            display.update(lines, -1);
            terminal.flush();
        }
    }

    private static final class LoggingTodoWriteTool implements ToolCallback {

        private final ToolCallback delegate;
        private final ConsoleDashboard dashboard;

        private LoggingTodoWriteTool(ToolCallback delegate, ConsoleDashboard dashboard) {
            this.delegate = delegate;
            this.dashboard = dashboard;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return logAround(toolInput, () -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return logAround(toolInput, () -> delegate.call(toolInput, toolContext));
        }

        private String logAround(String toolInput, java.util.function.Supplier<String> invocation) {
            dashboard.updateToolEvent("TodoWrite 入参: " + truncate(clean(toolInput), 120)).render();
            String result = invocation.get();
            dashboard.updateToolEvent("TodoWrite 返回: " + truncate(clean(result), 120)).render();
            return result;
        }
    }

    private static final class RoundLoggingAdvisor implements CallAdvisor {

        private final int order;
        private final ConsoleDashboard dashboard;
        private int round = 0;

        private RoundLoggingAdvisor(int order, ConsoleDashboard dashboard) {
            this.order = order;
            this.dashboard = dashboard;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            int current = ++round;
            dashboard.updateRound(current, availableToolNames(request)).render();

            ChatClientResponse response = chain.nextCall(request);

            AssistantMessage output = output(response);
            dashboard.updateAssistantResponse(modelMessage(output), toolCallRequest(output)).render();
            return response;
        }

        @Override
        public String getName() {
            return "TodoWriteRoundLoggingAdvisor";
        }

        @Override
        public int getOrder() {
            return order;
        }

        private static String availableToolNames(ChatClientRequest request) {
            if (request.prompt().getOptions() instanceof ToolCallingChatOptions opts) {
                List<String> names = opts.getToolCallbacks().stream()
                        .map(toolCallback -> toolCallback.getToolDefinition().name())
                        .toList();
                if (!names.isEmpty()) {
                    return String.join(", ", names);
                }
            }
            return "（无）";
        }

        private static AssistantMessage output(ChatClientResponse response) {
            ChatResponse chatResponse = response.chatResponse();
            if (chatResponse == null || chatResponse.getResult() == null) {
                return null;
            }
            return chatResponse.getResult().getOutput();
        }

        private static String modelMessage(AssistantMessage output) {
            if (output == null) {
                return "(空响应)";
            }
            String text = clean(output.getText());
            return text.isBlank() ? "(无文本，见工具调用请求)" : truncate(text, 160);
        }

        private static String toolCallRequest(AssistantMessage output) {
            if (output == null || !output.hasToolCalls()) {
                return "-";
            }
            return output.getToolCalls().stream()
                    .map(toolCall -> toolCall.name() + "(" + truncate(clean(toolCall.arguments()), 120) + ")")
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("-");
        }
    }

    private static String statusMarker(Todos.Status status) {
        return switch (status) {
            case completed -> "[✓]";
            case in_progress -> "[>]";
            case pending -> "[ ]";
        };
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}

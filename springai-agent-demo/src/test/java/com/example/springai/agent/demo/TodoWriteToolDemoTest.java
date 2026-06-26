package com.example.springai.agent.demo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status.completed;
import static org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status.in_progress;
import static org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status.pending;

class TodoWriteToolDemoTest {

    @Test
    void renderProgressShowsCompletionRatioAndStatusMarkers() {
        Todos todos = new Todos(List.of(
                new TodoItem("梳理优惠券需求", completed, "正在梳理优惠券需求"),
                new TodoItem("设计接口和数据模型", in_progress, "正在设计接口和数据模型"),
                new TodoItem("编写测试清单", pending, "正在编写测试清单")
        ));

        String rendered = TodoWriteToolDemo.renderProgress(todos);

        assertEquals("""
                Progress: 1/3 tasks completed (33%)
                  [✓] 梳理优惠券需求
                  [>] 设计接口和数据模型
                  [ ] 编写测试清单
                """, rendered);
    }

    @Test
    void describeMessageShowsToolCallsAndToolResponses() {
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "TodoWrite",
                        "{\"todos\":[{\"content\":\"拆解需求\"}]}")))
                .build();
        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1",
                        "TodoWrite",
                        "Todos have been modified successfully. Ensure that you continue to use the todo list.")))
                .build();

        assertEquals("[ASSISTANT] 请求调用工具 TodoWrite({\"todos\":[{\"content\":\"拆解需求\"}]})",
                TodoWriteToolDemo.describeMessage(assistantMessage));
        assertEquals("[TOOL] TodoWrite 返回: Todos have been modified successfully. Ensure that you continue to use the todo list.",
                TodoWriteToolDemo.describeMessage(toolResponseMessage));
    }

    @Test
    void dashboardFrameKeepsTaskRowsStableAndOnlyChangesStatus() {
        TodoWriteToolDemo.ConsoleDashboard dashboard = new TodoWriteToolDemo.ConsoleDashboard("示例任务", false);

        dashboard.updateTodos(new Todos(List.of(
                new TodoItem("拆解需求", in_progress, "正在拆解需求"),
                new TodoItem("设计接口", pending, "正在设计接口")
        )));
        String firstFrame = dashboard.renderFrame();

        dashboard.updateTodos(new Todos(List.of(
                new TodoItem("拆解需求", completed, "正在拆解需求"),
                new TodoItem("设计接口", in_progress, "正在设计接口")
        )));
        String secondFrame = dashboard.renderFrame();

        assertEquals("""
                TodoWrite 任务进度
                任务: 示例任务
                进度: 0/2 (0%)

                任务清单
                  [>] 拆解需求
                  [ ] 设计接口

                模型交互
                  当前轮次: -
                  可用工具: -
                  状态: -
                  大模型返回: -
                  工具调用请求: -
                  最近工具: -
                """, firstFrame);
        assertEquals("""
                TodoWrite 任务进度
                任务: 示例任务
                进度: 1/2 (50%)

                任务清单
                  [✓] 拆解需求
                  [>] 设计接口

                模型交互
                  当前轮次: -
                  可用工具: -
                  状态: -
                  大模型返回: -
                  工具调用请求: -
                  最近工具: -
                """, secondFrame);
    }

    @Test
    void dashboardFrameShowsModelMessageAndToolCallRequestSeparately() {
        TodoWriteToolDemo.ConsoleDashboard dashboard = new TodoWriteToolDemo.ConsoleDashboard("示例任务", false);

        dashboard.updateRound(2, "TodoWrite")
                .updateAssistantResponse("我会先更新任务状态。", "TodoWrite({\"todos\":[...]})");

        assertEquals("""
                TodoWrite 任务进度
                任务: 示例任务
                进度: 0/0 (0%)

                任务清单
                  （等待模型调用 TodoWrite 创建任务清单）

                模型交互
                  当前轮次: 2
                  可用工具: TodoWrite
                  状态: 已收到模型响应
                  大模型返回: 我会先更新任务状态。
                  工具调用请求: TodoWrite({"todos":[...]})
                  最近工具: -
                """, dashboard.renderFrame());
    }

    @Test
    void dashboardKeepsLastToolCallRequestWhileWaitingForNextRound() {
        TodoWriteToolDemo.ConsoleDashboard dashboard = new TodoWriteToolDemo.ConsoleDashboard("示例任务", false);

        dashboard.updateRound(1, "TodoWrite")
                .updateAssistantResponse("(无文本，见工具调用请求)", "TodoWrite({\"todos\":[...]})");
        dashboard.updateRound(2, "TodoWrite");

        assertEquals("""
                TodoWrite 任务进度
                任务: 示例任务
                进度: 0/0 (0%)

                任务清单
                  （等待模型调用 TodoWrite 创建任务清单）

                模型交互
                  当前轮次: 2
                  可用工具: TodoWrite
                  状态: 等待模型响应...
                  大模型返回: (无文本，见工具调用请求)
                  工具调用请求: TodoWrite({"todos":[...]})
                  最近工具: -
                """, dashboard.renderFrame());
    }

    @Test
    void dashboardUsesRendererInsteadOfClearScreenWhenAnimated() {
        CapturingDashboardRenderer renderer = new CapturingDashboardRenderer();
        TodoWriteToolDemo.ConsoleDashboard dashboard = new TodoWriteToolDemo.ConsoleDashboard("示例任务", renderer);

        dashboard.render();
        dashboard.updateRound(1, "TodoWrite").render();

        assertEquals(2, renderer.frames.size());
        assertFalse(renderer.joinedFrames().contains("\033[H\033[2J"));
    }

    private static final class CapturingDashboardRenderer implements TodoWriteToolDemo.DashboardRenderer {

        private final List<String> frames = new ArrayList<>();

        @Override
        public void render(String frame) {
            frames.add(frame);
        }

        private String joinedFrames() {
            return String.join("", frames);
        }
    }
}

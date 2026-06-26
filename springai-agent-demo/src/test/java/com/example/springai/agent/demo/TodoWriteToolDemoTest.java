package com.example.springai.agent.demo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                  [x] 梳理优惠券需求
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
}

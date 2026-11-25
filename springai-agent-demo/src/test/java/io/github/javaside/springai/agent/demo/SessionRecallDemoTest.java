package io.github.javaside.springai.agent.demo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.session.EventQuery;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.tool.SessionRecallTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRecallDemoTest {

    static final String SID = "recall-test";

    @Test
    void recallFindsEventsOutsideTheView() {
        SessionService sessions = SessionDemoSupport.newSessions();
        ScriptedChatModel model = new ScriptedChatModel(p -> {
            if (ScriptedChatModel.isToolResult(p)) {
                return ScriptedChatModel.text("你最早说的是：" + ScriptedChatModel.firstToolResult(p));
            }
            return ScriptedChatModel.lastUserText(p).contains("暗号是什么")
                    ? ScriptedChatModel.toolCall("conversation_search", recallArguments(sessions, "海棠"))
                    : ScriptedChatModel.text("回声：" + ScriptedChatModel.lastUserText(p));
        });

        SessionRecallDemo.RecallRun run = SessionRecallDemo.seedAndRecall(
                ChatClient.builder(model).build(), sessions, SID, List.of());

        assertEquals(9, run.logSizeBefore());
        assertEquals(4, run.viewSizeBefore());
        List<SessionEvent> hits = sessions.searchEvents(SID, EventQuery.keyword("海棠"));
        assertEquals(MessageType.USER, hits.get(0).getMessageType());
        assertTrue(hits.get(0).getMessage().getText().contains("海棠"));
        assertFalse(ids(run.viewAfter()).contains(hits.get(0).getId()));   // 旧事件没有回到视图
        assertTrue(ids(run.logAfter()).contains(hits.get(0).getId()));     // 但一直在日志里
        assertTrue(run.answer().contains("海棠"));
        assertEquals(13, run.logAfter().size());
        assertEquals(8, run.viewAfter().size());
        assertEquals(List.of(1, 3, 5, 7, 5, 7), model.promptSizes());
    }

    @Test
    void recallArgumentsTargetTheKeywordParameter() {
        SessionService sessions = SessionDemoSupport.newSessions();
        ToolCallback recall = recallTool(sessions);

        String result = recall.call(recallArguments(sessions, "海棠"),
                new ToolContext(Map.of(SessionRecallTools.SESSION_ID_CONTEXT_KEY, "no-such-session")));

        // 关键词落在了正确的形参上：工具正常执行并返回「没搜到」，而不是一句「缺关键词」的错误提示。
        // 返回值落盘时是 JSON 字符串字面量，所以带一对引号
        assertEquals("\"No results found.\"", result);
    }

    static ToolCallback recallTool(SessionService sessions) {
        return ToolCallbacks.from(SessionRecallTools.builder(sessions).build())[0];
    }

    /**
     * 按输入 schema 里的属性名拼会话搜索工具的入参（关键词是第一个形参）。本仓库编译没开 {@code -parameters}，
     * Spring 7 也不再从 LocalVariableTable 取形参名，所以 {@code @Tool} 形参在 schema 里叫 {@code arg0}：
     * 写成 {@code {"query":..}} 时关键词为空，工具只会返回一句错误提示，模型答不出暗号。
     */
    static String recallArguments(SessionService sessions, String keyword) {
        String schema = recallTool(sessions).getToolDefinition().inputSchema();
        String property = new ObjectMapper().readTree(schema).get("properties").propertyNames().iterator().next();
        return "{\"" + property + "\":\"" + keyword + "\"}";
    }

    static List<String> ids(List<SessionEvent> events) {
        return events.stream().map(SessionEvent::getId).toList();
    }
}

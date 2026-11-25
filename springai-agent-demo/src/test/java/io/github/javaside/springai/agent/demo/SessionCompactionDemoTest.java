package io.github.javaside.springai.agent.demo;

import io.github.javaside.springai.agent.tools.WeatherTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.support.ToolCallbacks;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCompactionDemoTest {

    static final String SID = "demo-test";

    static ScriptedChatModel echoModel() {
        return new ScriptedChatModel(p -> ScriptedChatModel.text("回声：" + ScriptedChatModel.lastUserText(p)));
    }

    /** 只有问到天气才请求调用工具；拿到工具结果后给最终回答。 */
    static ScriptedChatModel weatherModel() {
        return new ScriptedChatModel(p -> ScriptedChatModel.isToolResult(p)
                ? ScriptedChatModel.text("北京今天晴。")
                : ScriptedChatModel.lastUserText(p).contains("天气")
                        ? ScriptedChatModel.toolCall("getWeather", weatherArguments("北京"))
                        : ScriptedChatModel.text("你好！"));
    }

    /**
     * 按输入 schema 里的属性名拼工具入参。本仓库编译没开 {@code -parameters}，Spring 7 也不再从 LocalVariableTable
     * 取形参名，所以 {@code @Tool} 形参在 schema 里叫 {@code arg0}：写成 {@code {"city":..}} 时实参是 null，
     * 工具抛出的 NullPointerException 会被当成「工具结果」记进日志，断言看起来只是内容不对。
     */
    static String weatherArguments(String city) {
        String schema = ToolCallbacks.from(new WeatherTools())[0].getToolDefinition().inputSchema();
        String property = new ObjectMapper().readTree(schema).get("properties").propertyNames().iterator().next();
        return "{\"" + property + "\":\"" + city + "\"}";
    }

    @Test
    void plainTurnsLogGrowsWhileViewShrinksAfterCompaction() {
        ScriptedChatModel model = echoModel();

        List<SessionCompactionDemo.TurnStat> stats = SessionCompactionDemo.playPlainTurns(
                ChatClient.builder(model).build(), SessionDemoSupport.newSessions(), SID, 5);

        assertEquals(List.of(new SessionCompactionDemo.TurnStat(2, 2), new SessionCompactionDemo.TurnStat(4, 4),
                new SessionCompactionDemo.TurnStat(6, 6), new SessionCompactionDemo.TurnStat(9, 4),
                new SessionCompactionDemo.TurnStat(11, 6)), stats);
        assertEquals(List.of(1, 3, 5, 7, 5), model.promptSizes());
    }

    @Test
    void compactionAppendsBoundaryWithoutRewritingLog() {
        ScriptedChatModel model = echoModel();
        ChatClient client = ChatClient.builder(model).build();
        SessionService sessions = SessionDemoSupport.newSessions();
        List<Advisor> advisors = SessionCompactionDemo.compactingAdvisors(sessions, 3, 2);
        for (int i = 1; i <= 3; i++) {
            SessionDemoSupport.ask(client, advisors, SID, "第" + i + "句");
        }
        List<SessionEvent> before = sessions.getEvents(SID, EventFilter.all());

        SessionDemoSupport.ask(client, advisors, SID, "第4句");

        List<SessionEvent> after = sessions.getEvents(SID, EventFilter.all());
        assertEquals(9, after.size());
        // 压缩前后，日志里原有的 6 条事件逐一相同（第 7、8 条是第 4 轮的一问一答，第 9 条才是边界）
        assertEquals(snapshot(before), snapshot(after.subList(0, before.size())));
        assertEquals(EventType.COMPACTION, after.get(8).getType());
        assertFalse(after.get(8).hasMessage());
        assertEquals(ids(after.subList(4, 8)), ids(sessions.getEvents(SID, EventFilter.active())));
    }

    @Test
    void toolTurnIsLoggedWholeAndNeverSplitByCompaction() {
        ScriptedChatModel model = weatherModel();

        SessionCompactionDemo.ToolTurnRun run = SessionCompactionDemo.playToolTurns(
                ChatClient.builder(model).build(), SessionDemoSupport.newSessions(), SID);

        assertEquals(List.of("USER", "ASSISTANT", "USER", "ASSISTANT", "TOOL", "ASSISTANT", "COMPACTION"),
                run.log().stream().map(e -> e.hasMessage() ? e.getMessageType().name() : e.getType().name()).toList());
        assertTrue(run.log().get(3).hasToolCalls());
        assertEquals(ids(run.log().subList(2, 6)), ids(run.view()));
        assertEquals(List.of(1, 3, 5), model.promptSizes());
    }

    @Test
    void renderLogMarksWhichEventsAreStillInTheView() {
        SessionCompactionDemo.ToolTurnRun run = SessionCompactionDemo.playToolTurns(
                ChatClient.builder(weatherModel()).build(), SessionDemoSupport.newSessions(), SID);

        List<String> lines = SessionCompactionDemo.renderLog(run.log(), run.view(), ZoneId.of("Asia/Shanghai"));

        assertEquals(List.of("  ✗ #1", "  ✗ #2", "  ✓ #3", "  ✓ #4", "  ✓ #5", "  ✓ #6", "  ✗ #7"),
                lines.stream().map(l -> l.substring(0, 6)).toList());
        assertTrue(lines.get(3).contains("🔧 getWeather(" + weatherArguments("北京") + ")"));
        assertTrue(lines.get(4).contains("北京：晴，26℃，微风"));
        assertTrue(lines.get(6).contains("压缩边界"));
    }

    static List<String> snapshot(List<SessionEvent> events) {
        return events.stream().map(e -> e.getId() + "|" + e.getMessage().getText()).toList();
    }

    static List<String> ids(List<SessionEvent> events) {
        return events.stream().map(SessionEvent::getId).toList();
    }
}

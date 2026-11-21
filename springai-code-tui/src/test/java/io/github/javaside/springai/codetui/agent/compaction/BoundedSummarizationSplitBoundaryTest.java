package io.github.javaside.springai.codetui.agent.compaction;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归档/保留边界必须切在「真正的用户回合起点」(root + USER),绝不落在 tool_use 与其 tool_result 之间。
 *
 * <p>回归:此前边界回退只判 {@link SessionEvent#isRootEvent()},但该方法在 spring-ai-session-management
 * 里只表示「非子 agent 分支」,单 agent 会话恒为 true——回退成空操作,切割点落在工具调用对中间,
 * 保留窗口以孤儿 tool_result 开头,发给 Anthropic 报 400
 * {@code unexpected tool_use_id found in tool_result blocks}(见真实故障会话 20260920T030232)。
 */
class BoundedSummarizationSplitBoundaryTest {

    private static final String KEY = "prov:model";
    private static final long WINDOW = 200_000L;
    private static final long RESERVE = 12_000L;

    private static SessionEvent event(Message m) {
        return SessionEvent.builder().sessionId("s").message(m).build();
    }

    private static SessionEvent user(String text) {
        return event(new UserMessage(text));
    }

    private static SessionEvent assistantCall(String id, String tool) {
        return event(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", tool, "{}")))
                .build());
    }

    private static SessionEvent toolResult(String id, String tool) {
        return event(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, tool, "ok")))
                .build());
    }

    /** 一个完整用户回合:USER → (assistant tool_use + tool_result) * steps。 */
    private static void appendTurn(List<SessionEvent> out, int turn, int steps) {
        out.add(user("turn-" + turn));
        for (int s = 0; s < steps; s++) {
            String id = "t" + turn + "s" + s;
            out.add(assistantCall(id, "Bash"));
            out.add(toolResult(id, "Bash"));
        }
    }

    private static CompactionRequest request(List<SessionEvent> events) {
        return CompactionRequest.of(Session.builder().id("s").build(), events);
    }

    private static BoundedSummarizationCompactionStrategy strategy(int maxEventsToKeep) {
        return new BoundedSummarizationCompactionStrategy(() -> 40_000L, String::length, in -> "summary",
                () -> new BoundedSummarizationCompactionStrategy.ModelSnapshot(KEY, WINDOW, RESERVE),
                new CalibrationState(), maxEventsToKeep);
    }

    /** 保留窗口(摘要 2 条之后)必须以 root USER 事件开头,且不含孤儿 tool_result。 */
    private static void assertNoOrphanToolResult(CompactionResult result) {
        // TODO(Task2 行为校准): 官方 2.1.0 的 preservedEvents 已不含前导 2 条合成摘要事件（它们分离进
        // summary()），「跳过前 2 条」的 subList 口径已过时，编译层先机械映射，行为校准阶段重定 kept 的切法。
        List<SessionEvent> compacted = result.preservedEvents();
        // compacted[0]=synthetic USER 摘要标记, [1]=synthetic ASSISTANT 摘要正文, [2..]=保留窗口
        List<SessionEvent> kept = compacted.subList(2, compacted.size());
        assertFalse(kept.isEmpty(), "应保留最近回合");
        assertEquals(MessageType.USER, kept.get(0).getMessageType(),
                "保留窗口必须从真正的用户回合起点切开,不能以 assistant/tool 中途开头");

        Set<String> seenCallIds = new HashSet<>();
        for (SessionEvent e : kept) {
            Message m = e.getMessage();
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                am.getToolCalls().forEach(tc -> seenCallIds.add(tc.id()));
            } else if (m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    assertTrue(seenCallIds.contains(r.id()),
                            "保留窗口内的 tool_result(" + r.id() + ")找不到对应的 tool_use → 会触发 Anthropic 400");
                }
            }
        }
    }

    @Test
    void naiveCutLandsOnToolResult_snapsBackToUserTurnStart() {
        // 每回合 1 步 = 3 事件(USER, assistantCall, toolResult)。4 回合 = 12 事件。
        // maxEventsToKeep=4 → 朴素切点 = 12-4 = 8,index 8 是 turn-3 的 toolResult(回合中间)。
        // 未修复:isRootEvent() 恒 true,回退空操作,切点停在 8 → 保留窗口以孤儿 toolResult 开头。
        List<SessionEvent> events = new ArrayList<>();
        for (int t = 0; t < 4; t++) appendTurn(events, t, 1);

        CompactionResult result = strategy(4).compact(request(events));

        assertNoOrphanToolResult(result);
    }

    @Test
    void naiveCutLandsOnAssistantToolCall_snapsBackToUserTurnStart() {
        // 朴素切点落在 assistant tool_use 上(其 tool_result 被留下,但 tool_use 若被归档也会孤儿)。
        // 构造:每回合 2 步。3 回合 = 3*(1+4) = 15 事件。maxEventsToKeep=5 → 切点=10。
        // index 10 处于 turn-2 内部,回退必须一路退到 turn-2 的 USER(index 10 恰好或更早)。
        List<SessionEvent> events = new ArrayList<>();
        for (int t = 0; t < 3; t++) appendTurn(events, t, 2);

        CompactionResult result = strategy(5).compact(request(events));

        assertNoOrphanToolResult(result);
    }

    @Test
    void tokenBudgetPath_alsoSnapsToUserTurnStart() {
        // 不设 maxEventsToKeep(=0),走 token 预算分支(newestSuffixStart)。
        // 大量小回合,让保留预算切在某个回合中间,验证同样回退到 USER。
        List<SessionEvent> events = new ArrayList<>();
        for (int t = 0; t < 30; t++) appendTurn(events, t, 1);

        BoundedSummarizationCompactionStrategy s = new BoundedSummarizationCompactionStrategy(
                () -> 40_000L, in -> 500, in -> "summary",   // 每事件估 500,预算切在中途
                () -> new BoundedSummarizationCompactionStrategy.ModelSnapshot(KEY, WINDOW, RESERVE),
                new CalibrationState());

        CompactionResult result = s.compact(request(events));

        // TODO(Task2 行为校准): 同 assertNoOrphanToolResult——subList(2, ...) 跳过合成摘要头的口径已过时。
        List<SessionEvent> compacted = result.preservedEvents();
        List<SessionEvent> kept = compacted.subList(2, compacted.size());
        if (!kept.isEmpty()) {
            assertEquals(MessageType.USER, kept.get(0).getMessageType(),
                    "token 预算分支也必须从用户回合起点切开");
            assertNoOrphanToolResult(result);
        }
    }
}

package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.goal.GoalConfig;
import io.github.javaside.springai.codetui.agent.goal.GoalManager;
import io.github.javaside.springai.codetui.agent.goal.GoalPhase;
import io.github.javaside.springai.codetui.agent.goal.GoalTurnMaterial;
import io.github.javaside.springai.codetui.agent.interjection.InterjectionText;
import io.github.javaside.springai.codetui.agent.llm.EmptyStreamException;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * CodingAgent 的 goal 接线（Task 9）四条守卫：
 *
 * <ol>
 *   <li><b>clearContext 联动清 goal</b>——/clear 换新会话时活动 goal 一并 {@code CLEARED}：
 *       那个目标是冲着旧会话定的，会话没了还让它驱动自动轮等于对着空气干活；</li>
 *   <li><b>handleError 通知 + 豁免口径</b>——普通错误计入（errorRetry=0 时一次即 PAUSED(ERROR)，
 *       phase 变化可断言），{@link EmptyStreamException}（瞬态空流）与 CancellationException 包装
 *       （Esc 取消）不计数；解包在 {@code GoalManager.onTurnError} 内做，agent 传原始 err；</li>
 *   <li><b>collectGoalMaterial 倒扫</b>——从会话尾部往前：跳过尾部插话取 unwrap 原文、
 *       第一条真实 UserMessage 停（本轮边界）、区间内最后一条 AssistantMessage 是本轮末文本、
 *       ToolResponseMessage 计工具调用数；</li>
 *   <li><b>边界形状</b>——尾部无插话 → interjection null；插话后无 AssistantMessage →
 *       assistantTail 取再前一条；空会话 → ("", 0, null)。</li>
 * </ol>
 *
 * <p>构造走最轻的 7 参 telescoping 重载（同 {@code CodingAgentClearContextTest}）：
 * 本四处测试不触发 ChatClient / 压缩 / token 估算，全传 null；listener 用 {@link StubListener}
 * 兜住 handleError 的 onError；goal 侧直接 new 真 {@link GoalManager}（不必 mock，断言 phase 即可）。
 */
class CodingAgentGoalTest {

    /** errorRetry=0：一条非豁免错误就把 goal 打进 PAUSED(ERROR)，错误计数差可直接读相态。 */
    private static GoalConfig config() {
        return new GoalConfig(25, 3, 0L, 0, 0, 1, 1, 5, "");
    }

    private static GoalManager activatedGoal() {
        GoalManager gm = new GoalManager(config(), null);
        gm.activate("把所有测试跑绿");
        return gm;
    }

    private static CodingAgent agentWith(SessionService sessions) {
        return new CodingAgent(null, new StubListener(), "s", new AtomicLong(), sessions, null, null);
    }

    private static SessionEvent event(Message m) {
        return SessionEvent.builder().sessionId("s").message(m).build();
    }

    /** 只回放预置事件的假 SessionService（同 {@code CodingAgentContextTest} 桩）。 */
    private static final class StubSessionService implements SessionService {
        final List<SessionEvent> events;
        StubSessionService(List<SessionEvent> events) { this.events = events; }
        @Override public List<SessionEvent> getEvents(String id, EventFilter f) { return events; }
        @Override public List<Message> getMessages(String id) { return events.stream().map(SessionEvent::getMessage).toList(); }
        @Override public Session create(CreateSessionRequest r) { throw new UnsupportedOperationException(); }
        @Override public Session findById(String id) { throw new UnsupportedOperationException(); }
        @Override public List<Session> findByUserId(String u) { throw new UnsupportedOperationException(); }
        @Override public void delete(String id) { throw new UnsupportedOperationException(); }
        @Override public int deleteExpiredSessions(Instant i) { throw new UnsupportedOperationException(); }
        @Override public void appendEvent(SessionEvent e) { throw new UnsupportedOperationException(); }
        @Override public CompactionResult compact(String id, CompactionTrigger t, CompactionStrategy s) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void clearContextClearsGoal() {
        GoalManager gm = activatedGoal();
        CodingAgent agent = agentWith(null);
        agent.bindGoal(gm, null, null);
        assertEquals(GoalPhase.RUNNING, gm.phase(), "前置：goal 已激活");

        agent.clearContext();

        assertEquals(GoalPhase.CLEARED, gm.phase(), "/clear 换新会话必须连带清掉活动 goal");
        assertSame(gm, agent.goal(), "SubmitHandler.goal() 须返回 bindGoal 绑定的同一实例");
    }

    @Test
    void handleErrorNotifiesGoalAndEmptyStreamExempt() {
        // (a) 普通异常：计入错误（errorRetry=0 → 一次即 PAUSED(ERROR)）
        GoalManager hit = activatedGoal();
        CodingAgent a1 = agentWith(null);
        a1.bindGoal(hit, null, null);
        a1.handleError(new RuntimeException("boom"), 1L);
        assertEquals(GoalPhase.PAUSED, hit.snapshot().phase(), "普通错误应经 onTurnError 计入熔断");
        assertEquals(io.github.javaside.springai.codetui.agent.goal.PauseReason.ERROR,
                hit.snapshot().pauseReason(), "连续错误熔断的原因是 ERROR");

        // (b) EmptyStreamException（瞬态空流）：豁免不计数 → 仍 RUNNING
        GoalManager exemptStream = activatedGoal();
        CodingAgent a2 = agentWith(null);
        a2.bindGoal(exemptStream, null, null);
        a2.handleError(new EmptyStreamException("空流"), 1L);
        assertEquals(GoalPhase.RUNNING, exemptStream.phase(), "空流豁免不得计入错误");

        // (c) CancellationException 包装（Esc 取消）：沿 cause 链豁免 → 仍 RUNNING
        GoalManager exemptCancel = activatedGoal();
        CodingAgent a3 = agentWith(null);
        a3.bindGoal(exemptCancel, null, null);
        a3.handleError(new RuntimeException("wrap", new CancellationException()), 1L);
        assertEquals(GoalPhase.RUNNING, exemptCancel.phase(), "取消包装豁免不得计入错误");
    }

    @Test
    void collectGoalMaterialScansSessionTail() {
        // 本轮形状（oldest-first）：
        // User(正常) → Assistant(toolCalls×2) → ToolResponse×2 → Assistant(最终回答) → User(插话)
        AssistantMessage toolCallTurn = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "Bash", "{\"command\":\"mvn test\"}"),
                        new AssistantMessage.ToolCall("c2", "function", "Read", "{\"path\":\"a.java\"}")))
                .build();
        ToolResponseMessage toolResponses = ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponse("c1", "Bash", "BUILD SUCCESS"),
                        new ToolResponse("c2", "Read", "class A {}")))
                .build();
        List<SessionEvent> events = List.of(
                event(new UserMessage("修复编译错误")),
                event(toolCallTurn),
                event(toolResponses),
                event(new AssistantMessage("最终回答文本：编译已修复。")),
                event(new UserMessage(InterjectionText.wrap("快点收尾"))));

        CodingAgent agent = agentWith(new StubSessionService(events));
        GoalTurnMaterial m = agent.collectGoalMaterial();

        assertEquals("最终回答文本：编译已修复。", m.assistantTail(), "assistantTail 取区间内最后一条 AssistantMessage 全文");
        assertEquals(2, m.toolCallCount(), "工具调用数按区间内 ToolResponse 计数");
        assertEquals("快点收尾", m.userInterjection(), "尾部插话取 unwrap 原文");
    }

    @Test
    void collectGoalMaterialSkipsTrailingInterjectionsOnly() {
        // (a) 尾部无插话：interjection 为 null
        CodingAgent noInterjection = agentWith(new StubSessionService(List.of(
                event(new UserMessage("问题一")),
                event(new AssistantMessage("回答一")))));
        GoalTurnMaterial plain = noInterjection.collectGoalMaterial();
        assertEquals("回答一", plain.assistantTail());
        assertEquals(0, plain.toolCallCount());
        assertNull(plain.userInterjection(), "尾部无插话时 interjection 为 null");

        // (b) 插话后无 AssistantMessage：assistantTail 取插话之前那条
        CodingAgent trailingOnly = agentWith(new StubSessionService(List.of(
                event(new UserMessage("问题一")),
                event(new AssistantMessage("回答一")),
                event(new UserMessage(InterjectionText.wrap("插话原文"))))));
        GoalTurnMaterial withInterjection = trailingOnly.collectGoalMaterial();
        assertEquals("回答一", withInterjection.assistantTail(), "插话后无 assistant 时取再前一条 AssistantMessage");
        assertEquals("插话原文", withInterjection.userInterjection(), "尾部插话取 unwrap 原文");
    }

    @Test
    void collectGoalMaterialEmptySessionYieldsBlankMaterial() {
        CodingAgent agent = agentWith(new StubSessionService(List.of()));
        GoalTurnMaterial m = agent.collectGoalMaterial();
        assertNotNull(m, "空会话仍返回素材对象（不返回 null，省调用方判空）");
        assertEquals("", m.assistantTail(), "空会话 assistantTail 为空串");
        assertEquals(0, m.toolCallCount());
        assertNull(m.userInterjection());
    }
}

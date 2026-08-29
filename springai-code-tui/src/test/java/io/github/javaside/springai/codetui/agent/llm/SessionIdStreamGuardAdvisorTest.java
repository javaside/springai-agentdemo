package io.github.javaside.springai.codetui.agent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现并验证「切 gpt 时 {@code SessionMemoryAdvisor.after()} 抛 No session ID」的根因与修复：
 * 当某迭代模型流为<b>空</b>（用 {@code Flux.empty()} 模拟 OpenAI 官方 SDK 流式路径的实测行为），
 * {@code SessionMemoryAdvisor} 聚合到的 context 为空、{@code after()} 抛错、assistant 无法落盘。
 * {@link SessionIdStreamGuardAdvisor} 用 {@code switchIfEmpty} 补一条携 context 的合成响应，使落盘正常。
 *
 * <p>不联网、确定性：真实 {@link ChatClient} + 真实 {@link SessionMemoryAdvisor} + 假 {@link ChatModel}。
 *
 * <p><b>范围说明</b>：本测试证明守卫把「空流」这一<b>失败条件</b>转成合法落盘的回合；它并不证明 OpenAI SDK
 * 在生产中确实产出空流——那需要一次带真实 {@code OPENAI_API_KEY} 的端到端跑（见计划的验证节）。
 */
class SessionIdStreamGuardAdvisorTest {

    /** 模型流恒空（模拟触发 bug 的迭代）。 */
    private static final class EmptyStreamModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
        }
        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.empty();
        }
    }

    /** 模型流正常产出一条（验证守卫在非空流上惰性、零改动）。 */
    private static final class OneChunkModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
        }
        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
        }
    }

    private static SessionService newService() {
        return DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build())
                .build();
    }

    private static SessionMemoryAdvisor memory(SessionService svc) {
        return SessionMemoryAdvisor.builder(svc).defaultUserId("u").build();   // 无压缩，隔离被测逻辑
    }

    /** 用给定 model + advisors 跑一轮流式；吞掉可能的终止异常（no-guard 场景 after() 会抛），以便随后断言落盘状态。 */
    private static void runTurn(ChatClient client, String sid) {
        try {
            client.prompt()
                    .user("hi")
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sid))
                    .stream().chatClientResponse()
                    .collectList().block(Duration.ofSeconds(10));
        } catch (Throwable ignored) {
            // no-guard 空流会让 after() 在聚合完成回调里抛 No session ID；本测试关心的是落盘可观察状态。
        }
    }

    @Test
    void emptyStream_withGuard_persistsAssistant_noCorruption() {
        SessionService svc = newService();
        ChatClient client = ChatClient.builder(new EmptyStreamModel())
                .defaultAdvisors(memory(svc), new SessionIdStreamGuardAdvisor())
                .build();

        runTurn(client, "guard-1");

        List<Message> msgs = svc.getMessages("guard-1");
        assertEquals(2, msgs.size(), "user + 合成 assistant 都应落盘（守卫补了携 context 的一条）");
        assertInstanceOf(AssistantMessage.class, msgs.get(msgs.size() - 1), "尾部是 assistant，历史合法（无连续 user）");
    }

    @Test
    void emptyStream_withoutGuard_assistantNotPersisted_control() {
        // 对照组：不装守卫，空流触发 after() 抛错，assistant 落不了盘（这正是堆连续 USER → DeepSeek 400 的根因）。
        SessionService svc = newService();
        ChatClient client = ChatClient.builder(new EmptyStreamModel())
                .defaultAdvisors(memory(svc))
                .build();

        runTurn(client, "noguard-1");

        List<Message> msgs = svc.getMessages("noguard-1");
        assertFalse(msgs.isEmpty(), "before() 已落盘 user");
        assertTrue(msgs.stream().noneMatch(m -> m instanceof AssistantMessage),
                "无守卫时 after() 抛错、assistant 未落盘（对照：证明守卫才是修复点）");
    }

    @Test
    void nonEmptyStream_guardIsInert_realAnswerPersisted() {
        SessionService svc = newService();
        ChatClient client = ChatClient.builder(new OneChunkModel())
                .defaultAdvisors(memory(svc), new SessionIdStreamGuardAdvisor())
                .build();

        runTurn(client, "inert-1");

        List<Message> msgs = svc.getMessages("inert-1");
        assertEquals(2, msgs.size());
        Message last = msgs.get(msgs.size() - 1);
        assertInstanceOf(AssistantMessage.class, last);
        assertEquals("answer", last.getText(), "非空流：守卫惰性，落盘的是模型真实答复而非合成空串");
    }
}

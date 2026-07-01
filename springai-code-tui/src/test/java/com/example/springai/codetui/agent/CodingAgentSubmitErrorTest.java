package com.example.springai.codetui.agent;

import com.example.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.Disposable;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：CodingAgent.submit 在响应式装配/订阅<b>同步抛异常</b>时，
 * 必须手动复位状态（onError → IDLE）并返回已 dispose 的句柄，
 * 而不是让异常逃逸、把 UI 永远卡在 THINKING（无终态事件）。
 * （整体评审 SHOULD-FIX #1）
 */
class CodingAgentSubmitErrorTest {

    /** 用动态代理造一个 prompt() 同步抛异常的 ChatClient，免去实现整个接口。 */
    private static ChatClient throwingOnPrompt(String message) {
        return (ChatClient) Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class[]{ChatClient.class},
                (proxy, method, args) -> {
                    if ("prompt".equals(method.getName())) {
                        throw new RuntimeException(message);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void syncAssemblyException_isReportedAndStateReset_notLeaked() {
        ConversationState state = new ConversationState();   // implements AgentListener
        CodingAgent agent = new CodingAgent(throwingOnPrompt("assembly-boom"), state, "s", new AtomicLong());

        Disposable d = assertDoesNotThrow(() -> agent.submit("hi"),
                "同步装配异常不应逃逸出 submit");

        assertTrue(state.isIdle(), "onError 应把状态复位到 IDLE（不卡在 THINKING）");
        assertTrue(d.isDisposed(), "同步失败应返回已 dispose 的句柄");
        assertTrue(state.transcriptSnapshot().stream().anyMatch(l -> l.contains("assembly-boom")),
                "错误信息应经 onError 落进 transcript");
    }
}

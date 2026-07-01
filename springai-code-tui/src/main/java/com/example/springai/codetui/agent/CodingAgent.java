package com.example.springai.codetui.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.Disposable;
import reactor.core.Disposables;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CodingAgent —— 编码 Agent 核心。把一次用户提交翻译成一轮流式对话，
 * 并把 Spring AI 的响应流「翻译」成纯 Java 的 {@link AgentListener} 事件。
 *
 * <p><b>接缝纪律</b>：{@link ChatClientResponse} 等 Spring AI 类型<b>不得</b>泄漏进
 * {@link AgentListener}——{@code handleChunk/handleError/handleComplete} 都是本类私有，
 * 只把抽取出的纯文本 / Throwable 交给 listener。
 *
 * <p><b>turnId 归属</b>：{@link #activeTurnId} 由本类拥有（每次 submit 自增），
 * <b>不</b>再与 AgentTools 共享；工具 / Todo 事件的 turnId 走 ToolContext / ThreadLocal（见 Task 5/6）。
 *
 * <p><b>取消竞态</b>：{@link #submit} 顶部<b>同步</b>调用 {@code onTurnStarted}（先锁定 UI 的
 * acceptingTurnId）再 subscribe，确保 dispose 时不会漏掉「回合已开始」的信号。
 */
public final class CodingAgent implements SubmitHandler {

    private final ChatClient chatClient;
    private final AgentListener listener;
    private final String sessionId;
    private final AtomicLong activeTurnId;

    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId) {
        this.chatClient = chatClient;
        this.listener = listener;
        this.sessionId = sessionId;
        this.activeTurnId = activeTurnId;
    }

    @Override
    public Disposable submit(String text) {
        long turnId = activeTurnId.incrementAndGet();
        listener.onTurnStarted(turnId);        // 同步：先锁定 acceptingTurnId，消除取消竞态
        listener.onUserMessage(turnId, text);
        try {
            return chatClient.prompt()
                    .user(text)
                    .toolContext(Map.of("turnId", turnId))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .stream().chatClientResponse()
                    .doOnNext(resp -> handleChunk(resp, turnId))
                    .doOnError(err -> handleError(err, turnId))
                    .doOnComplete(() -> handleComplete(turnId))
                    .subscribe();
        } catch (RuntimeException ex) {
            // 同步组装/订阅异常不走 doOnError：手动复位状态（onError → IDLE），
            // 否则 UI 会永远卡在 THINKING（无终态事件），且异常会逃逸出 View.handle。
            listener.onError(turnId, ex);
            return Disposables.disposed();
        }
    }

    /** 从一个流式块里抽取文本增量并发给 listener。全链路 null-guard：工具调用块常常没有输出/文本。 */
    private void handleChunk(ChatClientResponse resp, long turnId) {
        if (resp == null) {
            return;
        }
        ChatResponse chatResponse = resp.chatResponse();
        if (chatResponse == null) {
            return;
        }
        Generation generation = chatResponse.getResult();
        if (generation == null) {
            return;
        }
        AssistantMessage output = generation.getOutput();
        if (output == null) {
            return;
        }
        String delta = output.getText();   // AbstractMessage#getText()（javap 已核实）
        if (delta != null && !delta.isEmpty()) {
            listener.onAssistantToken(turnId, delta);
        }
    }

    private void handleError(Throwable err, long turnId) {
        listener.onError(turnId, err);
    }

    private void handleComplete(long turnId) {
        listener.onTurnComplete(turnId);
    }
}

package io.github.javaside.springai.codetui.agent.compaction;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.function.LongSupplier;

/** Compacts persisted history before SessionMemoryAdvisor loads it into the outbound prompt.
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。 */
public final class PreflightCompactionAdvisor implements StreamAdvisor {

    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 999;

    private final SessionService sessions;
    private final CompleteTokenCountTrigger trigger;
    private final CompactionStrategy strategy;

    PreflightCompactionAdvisor(SessionService sessions, long threshold, CompactionStrategy strategy) {
        this.sessions = sessions;
        this.trigger = new CompleteTokenCountTrigger(threshold, String::length);
        this.strategy = strategy;
    }

    /**
     * <b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public PreflightCompactionAdvisor(SessionService sessions, LongSupplier threshold,
                               TokenCountEstimator estimator, CompactionStrategy strategy) {
        this.sessions = sessions;
        this.trigger = new CompleteTokenCountTrigger(threshold, estimator);
        this.strategy = strategy;
    }

    @Override public String getName() { return "preflightCompaction"; }
    @Override public int getOrder() { return ORDER; }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            Object value = request.context().get(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY);
            if (value instanceof String sessionId && !sessionId.isBlank()
                    && sessions.findById(sessionId) != null) {
                sessions.compact(sessionId, trigger, strategy);
            }
            return chain.nextStream(request);
        });
    }
}

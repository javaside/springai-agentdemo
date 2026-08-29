package io.github.javaside.springai.codetui.agent.compaction;

import io.github.javaside.springai.codetui.agent.session.SessionTokenEstimator;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

/** Token trigger that includes tool response data and tool-call arguments. */
final class CompleteTokenCountTrigger implements CompactionTrigger {

    private final LongSupplier threshold;
    private final ToIntFunction<String> estimator;

    CompleteTokenCountTrigger(long threshold, TokenCountEstimator estimator) {
        this(() -> threshold, estimator::estimate);
    }

    CompleteTokenCountTrigger(long threshold, ToIntFunction<String> estimator) {
        this(() -> threshold, estimator);
    }

    CompleteTokenCountTrigger(LongSupplier threshold, TokenCountEstimator estimator) {
        this(threshold, estimator::estimate);
    }

    private CompleteTokenCountTrigger(LongSupplier threshold, ToIntFunction<String> estimator) {
        this.threshold = threshold;
        this.estimator = estimator;
    }

    @Override
    public boolean shouldCompact(CompactionRequest request) {
        long current = threshold.getAsLong();
        if (current <= 0) throw new IllegalStateException("threshold must be greater than 0");
        return SessionTokenEstimator.estimateEvents(request.events(), estimator) >= current;
    }
}

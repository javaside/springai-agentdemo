package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

/**
 * Compacts by token budget, sending only bounded chunks to the summarizer.
 * The newest complete suffix is kept verbatim; older events are summarized chunk-by-chunk.
 */
final class BoundedSummarizationCompactionStrategy implements CompactionStrategy {

    private static final String SUMMARY_SOURCE = "bounded-recursive-summarization";

    private final LongSupplier targetTokens;
    private final LongSupplier chunkTokens;
    private final ToIntFunction<String> estimator;
    private final Function<String, String> summarizer;

    BoundedSummarizationCompactionStrategy(long targetTokens, long chunkTokens,
                                           ToIntFunction<String> estimator,
                                           Function<String, String> summarizer) {
        this(() -> targetTokens, () -> chunkTokens, estimator, summarizer);
    }

    BoundedSummarizationCompactionStrategy(LongSupplier targetTokens, LongSupplier chunkTokens,
                                           ToIntFunction<String> estimator,
                                           Function<String, String> summarizer) {
        this.targetTokens = targetTokens;
        this.chunkTokens = chunkTokens;
        this.estimator = estimator;
        this.summarizer = summarizer;
    }

    @Override
    public CompactionResult compact(CompactionRequest request) {
        List<SessionEvent> events = request.events();
        long targetBudget = targetTokens.getAsLong();
        long chunkBudget = chunkTokens.getAsLong();
        if (targetBudget <= 0 || chunkBudget <= 0) throw new IllegalStateException("token budgets must be positive");
        long total = SessionTokenEstimator.estimateEvents(events, estimator);
        if (total <= targetBudget) {
            return new CompactionResult(events, List.of(), 0);
        }

        long keepBudget = Math.max(1L, targetBudget / 2L);
        int split = newestSuffixStart(events, keepBudget);
        if (split <= 0) {
            split = events.size() == 1 ? 1 : Math.max(1, events.size() - 1);
        }
        List<SessionEvent> archived = List.copyOf(events.subList(0, split));
        List<SessionEvent> kept = List.copyOf(events.subList(split, events.size()));
        if (SessionTokenEstimator.estimateEvents(kept, estimator) > keepBudget) {
            archived = events;
            kept = List.of();
        }

        List<String> summaries = new ArrayList<>();
        boolean summarizationFailed = false;
        try {
            for (List<SessionEvent> chunk : chunks(archived)) {
                for (String bounded : textChunks(format(chunk))) {
                    String summary = summarizer.apply(bounded);
                    if (summary != null && !summary.isBlank()) summaries.add(summary.strip());
                }
            }
        } catch (RuntimeException failure) {
            summarizationFailed = true;
        }

        String merged = summarizationFailed || summaries.isEmpty()
                ? localDigest(archived, Math.max(1L, targetBudget / 2L))
                : String.join("\n\n", summaries);
        try {
            for (int round = 0; !summarizationFailed && estimate(merged) > chunkBudget && round < 4; round++) {
                List<String> next = new ArrayList<>();
                for (String group : textChunks(merged)) {
                    String summary = summarizer.apply(group);
                    if (summary != null && !summary.isBlank()) next.add(summary.strip());
                }
                String candidate = String.join("\n\n", next);
                if (candidate.isEmpty() || estimate(candidate) >= estimate(merged)) break;
                merged = candidate;
            }
        } catch (RuntimeException failure) {
            summarizationFailed = true;
        }
        long summaryBudget = Math.max(1L, targetBudget
                - SessionTokenEstimator.estimateEvents(kept, estimator)
                - estimate("[Earlier conversation summary]\n") - 8L);
        if (estimate(merged) > summaryBudget) {
            merged = localDigest(archived, summaryBudget);
        }

        List<SessionEvent> compacted = new ArrayList<>(kept.size() + 2);
        compacted.add(synthetic(request, new UserMessage("[Earlier conversation summary]")));
        compacted.add(synthetic(request, new AssistantMessage(merged)));
        compacted.addAll(kept);
        int saved = (int) Math.max(0L, Math.min(Integer.MAX_VALUE,
                total - SessionTokenEstimator.estimateEvents(compacted, estimator)));
        return new CompactionResult(compacted, archived, saved);
    }

    private int newestSuffixStart(List<SessionEvent> events, long budget) {
        long used = 0L;
        int start = events.size();
        for (int i = events.size() - 1; i >= 0; i--) {
            long eventTokens = SessionTokenEstimator.estimateEvents(List.of(events.get(i)), estimator);
            if (start < events.size() && used + eventTokens > budget) break;
            used += eventTokens;
            start = i;
        }
        while (start < events.size() && start > 0 && !events.get(start).isRootEvent()) start--;
        return start;
    }

    private List<List<SessionEvent>> chunks(List<SessionEvent> events) {
        long chunkBudget = chunkTokens.getAsLong();
        List<List<SessionEvent>> out = new ArrayList<>();
        List<SessionEvent> current = new ArrayList<>();
        long used = 0L;
        for (SessionEvent event : events) {
            long tokens = SessionTokenEstimator.estimateEvents(List.of(event), estimator);
            if (!current.isEmpty() && used + tokens > chunkBudget) {
                out.add(List.copyOf(current));
                current.clear();
                used = 0L;
            }
            if (tokens > chunkBudget) {
                for (String part : textChunks(format(List.of(event)))) {
                    out.add(List.of(syntheticEvent(event.getSessionId(), new UserMessage(part))));
                }
            } else {
                current.add(event);
                used += tokens;
            }
        }
        if (!current.isEmpty()) out.add(List.copyOf(current));
        return out;
    }

    private List<String> textChunks(String text) {
        long chunkBudget = chunkTokens.getAsLong();
        List<String> out = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int low = from + 1, high = text.length(), best = from + 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (estimate(text.substring(from, mid)) <= chunkBudget) {
                    best = mid;
                    low = mid + 1;
                } else high = mid - 1;
            }
            out.add(text.substring(from, best));
            from = best;
        }
        return out;
    }

    private long estimate(String text) {
        return text == null || text.isEmpty() ? 0L : estimator.applyAsInt(text);
    }

    private String localDigest(List<SessionEvent> archived, long budget) {
        String prefix = "Earlier history was too large for a safe model-generated summary. "
                + archived.size() + " events were compacted locally. Recent context follows.";
        if (estimate(prefix) <= budget) return prefix;
        int low = 0, high = prefix.length(), best = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (estimate(prefix.substring(0, mid)) <= budget) {
                best = mid;
                low = mid + 1;
            } else high = mid - 1;
        }
        return prefix.substring(0, best);
    }

    private String format(List<SessionEvent> events) {
        StringBuilder out = new StringBuilder();
        for (SessionEvent event : events) {
            Message message = event.getMessage();
            out.append('[').append(message.getMessageType()).append("] ");
            if (message instanceof ToolResponseMessage tool) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    out.append(response.name()).append(": ").append(response.responseData()).append('\n');
                }
            } else {
                out.append(message.getText()).append('\n');
                if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                    for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                        out.append("tool_call ").append(call.name()).append(' ').append(call.arguments()).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    private SessionEvent synthetic(CompactionRequest request, Message message) {
        return syntheticEvent(request.session().id(), message);
    }

    private static SessionEvent syntheticEvent(String sessionId, Message message) {
        return SessionEvent.builder().sessionId(sessionId).message(message)
                .metadata(Map.of(SessionEvent.METADATA_SYNTHETIC, true,
                        SessionEvent.METADATA_COMPACTION_SOURCE, SUMMARY_SOURCE))
                .build();
    }
}

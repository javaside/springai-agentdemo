package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.util.List;
import java.util.function.ToIntFunction;

/** Token accounting shared by context display, compaction triggers, and preflight budgeting. */
final class SessionTokenEstimator {

    private SessionTokenEstimator() {}

    static long estimateEvents(List<SessionEvent> events, TokenCountEstimator estimator) {
        return estimateEvents(events, estimator::estimate);
    }

    static long estimateEvents(List<SessionEvent> events, ToIntFunction<String> estimator) {
        long total = 0L;
        for (SessionEvent event : events) {
            total += estimateMessage(event.getMessage(), estimator);
        }
        return total;
    }

    static long estimateMessages(List<Message> messages, TokenCountEstimator estimator) {
        long total = 0L;
        for (Message message : messages) total += estimateMessage(message, estimator::estimate);
        return total;
    }

    static long estimateMessage(Message message, ToIntFunction<String> estimator) {
        long total = estimatePart(message == null ? null : message.getText(), estimator);
        if (message instanceof ToolResponseMessage tool) {
            for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                total += estimatePart(response.responseData(), estimator);
            }
        }
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                total += estimatePart(call.name(), estimator);
                total += estimatePart(call.arguments(), estimator);
            }
        }
        return total;
    }

    private static int estimatePart(String text, ToIntFunction<String> estimator) {
        return text == null || text.isEmpty() ? 0 : estimator.applyAsInt(text + "\n");
    }
}

package io.github.javaside.springai.codetui.agent.session;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Token accounting shared by context display, compaction triggers, and preflight budgeting.
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class SessionTokenEstimator {

    private SessionTokenEstimator() {}

    static long estimateEvents(List<SessionEvent> events, TokenCountEstimator estimator) {
        return estimateEvents(events, estimator::estimate);
    }

    /**
     * {@link #estimateEvents(List, TokenCountEstimator)} 的函数式重载（测试用 String::length 等）。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static long estimateEvents(List<SessionEvent> events, ToIntFunction<String> estimator) {
        long total = 0L;
        for (SessionEvent event : events) {
            total += estimateMessage(event.getMessage(), estimator);
        }
        return total;
    }

    static long estimateMessages(List<Message> messages, TokenCountEstimator estimator) {
        return estimateMessages(messages, estimator::estimate);
    }

    /**
     * {@link #estimateMessages(List, TokenCountEstimator)} 的函数式重载（测试用 String::length 等）。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static long estimateMessages(List<Message> messages, ToIntFunction<String> estimator) {
        long total = 0L;
        for (Message message : messages) total += estimateMessage(message, estimator);
        return total;
    }

    /**
     * 按消息类型分桶估算会话消息 token（供 {@code /context} 分类展示）。
     * 与 {@link #estimateMessages} 同口径（含 tool 结果与 tool-call 参数），四桶之和 == 总数。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static Buckets estimateMessagesByType(List<Message> messages, TokenCountEstimator estimator) {
        return estimateMessagesByType(messages, estimator::estimate);
    }

    /**
     * {@link #estimateMessagesByType(List, TokenCountEstimator)} 的函数式重载（测试用 String::length 等）。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static Buckets estimateMessagesByType(List<Message> messages, ToIntFunction<String> estimator) {
        long system = 0L, user = 0L, assistant = 0L, tool = 0L;
        for (Message message : messages) {
            long n = estimateMessage(message, estimator);
            MessageType type = message == null ? null : message.getMessageType();
            if (type == MessageType.USER) {
                user += n;
            } else if (type == MessageType.ASSISTANT) {
                assistant += n;
            } else if (type == MessageType.TOOL) {
                tool += n;
            } else {
                system += n;   // SYSTEM 与未知类型归入系统/摘要桶
            }
        }
        return new Buckets(system, user, assistant, tool);
    }

    /**
     * 会话消息 token 的按类型分桶（纯数据，供 ContextStats 拷贝）。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public record Buckets(long systemTokens, long userTokens, long assistantTokens, long toolTokens) {

        /** <b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。 */
        public long total() {
            return systemTokens + userTokens + assistantTokens + toolTokens;
        }
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

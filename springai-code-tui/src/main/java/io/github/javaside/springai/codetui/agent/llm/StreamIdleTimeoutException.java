package io.github.javaside.springai.codetui.agent.llm;

/**
 * LLM 流空闲超时（{@link StreamIdleTimeoutChatModel} 首个/相邻响应间隔超时）。
 * 属瞬态故障，见 {@link RetryPolicy#shouldRetry}。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class StreamIdleTimeoutException extends RuntimeException {

    public StreamIdleTimeoutException(String message) {
        super(message);
    }
}

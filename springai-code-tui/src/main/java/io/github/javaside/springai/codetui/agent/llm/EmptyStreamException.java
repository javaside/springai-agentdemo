package io.github.javaside.springai.codetui.agent.llm;

/**
 * LLM 流「正常完成但零内容」的空流（网关坏窗口在流式路径上的另一副面孔）。
 * 属瞬态故障，见 {@link RetryPolicy#shouldRetry}。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class EmptyStreamException extends RuntimeException {

    public EmptyStreamException(String message) {
        super(message);
    }
}

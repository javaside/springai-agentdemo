package io.github.javaside.springai.codetui.agent;

/**
 * 子 agent 执行失败。message 是已摊平的 cause 链诊断文本（见 {@code SubagentRunner.describe}）——
 * 工具异常处理器回给主 agent 模型的就是 getMessage()，原始 SDK 异常的顶层 message 往往笼统
 * （如 "Error reading response"），真正根因在 cause 里，故在此预先摊平。cause 保留原异常供日志全栈。
 */
public class SubagentFailedException extends RuntimeException {
    public SubagentFailedException(String flattenedDetail, Throwable cause) {
        super(flattenedDetail, cause);
    }
}

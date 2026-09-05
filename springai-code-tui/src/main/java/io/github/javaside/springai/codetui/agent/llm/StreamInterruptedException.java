package io.github.javaside.springai.codetui.agent.llm;

/**
 * 主 agent 流<b>中途断开</b>（mid-stream）：已向下游下发过非空 chunk（{@code emittedChunks > 0}）后
 * 遭遇瞬态网络失败。{@link RetryingStreamChatModel}（L1）的零下发透明重试此时已不安全——
 * 重放会让用户看到重复内容——故把原始失败包装为本异常<b>放行</b>给 L2（CodingAgent 回合级续跑，
 * spec §3.3），由 L2 从已收到的内容续跑而非从头重放。
 *
 * <p>{@link #getMessage()} 恒为 null（构造置空）——UI 的 {@code formatError} 沿 cause 链取
 * 首个非空 message，置空让用户直接看到根因网络异常文案，而非本包装类的无信息样板。
 *
 * <p>属 {@link RetryPolicy} 红线类型；L1 双保险拒绝（红线短路 + {@code emitted==0} 恒等式），
 * 原样穿透命中 L2 白名单。
 *
 * <p><b>类型穿透要求</b>（spec §3.2）：本异常必须原样穿透 ToolCallingAdvisor 流式聚合、
 * SessionMemoryAdvisor 的 {@code publishOn + ChatClientMessageAggregator}、Vision/Interjecting
 * （纯委托无错误映射）才能命中 L2 白名单——中间层不得新增 onErrorMap 拦截。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class StreamInterruptedException extends RuntimeException {

    /** 断流前已向下游下发的「有内容」chunk 数（text 非空含纯空白，或 hasToolCalls）。 */
    private final long emittedChunks;

    public StreamInterruptedException(long emittedChunks, Throwable cause) {
        super(null, cause);   // message 置空：formatError 沿 cause 链落到根因文案
        this.emittedChunks = emittedChunks;
    }

    public long emittedChunks() {
        return emittedChunks;
    }
}

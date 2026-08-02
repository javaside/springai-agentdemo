package io.github.javaside.springai.codetui.agent;

/**
 * 会话上下文用量快照（{@code /context} 命令用）——<b>纯 Java</b>，不泄漏任何 Spring AI 类型，
 * 与 {@link AgentListener} 同属 CodingAgent→UI 的接缝纪律。
 *
 * <p>由 {@link SubmitHandler#contextStats()} 现算返回：读一遍当前会话事件与消息，统计条数与估算 token。
 * 全部为「只读快照」，读期间若有回合在写事件，拿到的是尽力而为的一致视图，不加锁。
 *
 * @param events          会话事件总数
 * @param userEvents      其中用户消息事件数
 * @param assistantEvents 其中助手消息事件数
 * @param toolEvents      其中工具消息事件数（tool_call/tool_result）
 * @param otherEvents     其余事件数（系统/摘要等）
 * @param estimatedTokens 全部消息文本的估算 token（同压缩用的 JTokkit 估算器）
 * @param tokenThreshold  自动压缩的累计 token 阈值（超过即对更早历史滚动摘要）
 * @param contextWindow   当前模型上下文窗口（token）
 * @param autoKeepEvents  自动压缩保留的最近事件数
 * @param manualKeepEvents 手动 {@code /compact} 保留的最近事件数（更激进）
 * @param visionImages    上一次出站请求实际兑现的图片张数
 * @param visionTokens    上一次出站请求的估算视觉 token。<b>不含在 {@code estimatedTokens} 里</b>——
 *                        后者只估会话存储里的文本，而图片从不进存储。两笔账必须分开，
 *                        合并会让「压缩阈值为什么没触发」变得无法解释。
 */
public record ContextStats(int events,
                           int userEvents,
                           int assistantEvents,
                           int toolEvents,
                           int otherEvents,
                           long estimatedTokens,
                           long tokenThreshold,
                           long contextWindow,
                           int autoKeepEvents,
                           int manualKeepEvents,
                           int visionImages,
                           long visionTokens) {

    /** 空快照（会话尚无事件 / 回显桩用）。 */
    public static ContextStats empty() {
        return new ContextStats(0, 0, 0, 0, 0, 0L,
                0L, 0L, 0, 0, 0, 0L);
    }
}

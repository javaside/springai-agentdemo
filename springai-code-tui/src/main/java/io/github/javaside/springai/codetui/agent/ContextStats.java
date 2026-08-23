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
 * @param visionImages    <b>本回合</b>累计兑现的图片张数（不是「上一次请求」：一个回合有几十次工具
 *                        迭代，按请求记则用户看到的几乎恒是 0，详见 {@code VisionSnapshot}）
 * @param visionTokens    本回合累计兑现图片的估算视觉 token。<b>不含在 {@code estimatedTokens} 里</b>——
 *                        后者只估会话存储里的文本，而图片从不进存储。两笔账必须分开，
 *                        合并会让「压缩阈值为什么没触发」变得无法解释。
 * @param cacheReadTokens 本会话累计的缓存读 token（provider 计费）
 * @param billedInputTokens 本会话累计的计费输入 token（= promptTokens，已含缓存）
 * @param cacheHitPercent 本会话缓存命中率（%），无计费输入时为 null
 * @param tokens          会话消息 token 的按类型分桶（用户/助手/工具/系统摘要），四桶之和 == estimatedTokens
 * @param systemPromptTokens 系统提示词估算 token（装配期快照，每回合固定重发）——<b>不含在 {@code estimatedTokens}
 *                        与 {@code tokens} 里</b>：系统提示词烘焙在 ChatClient，从不进会话存储。
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
                           long visionTokens,
                           long cacheReadTokens,
                           long billedInputTokens,
                           Integer cacheHitPercent,
                           TokenBreakdown tokens,
                           long systemPromptTokens) {

    /**
     * 会话消息 token 的按类型分桶（纯数据，/context 分类展示用）。
     *
     * @param systemTokens   系统/摘要消息 token（MessageType.SYSTEM 与未知类型）
     * @param userTokens     用户消息 token
     * @param assistantTokens 助手消息 token（含其 tool_calls 名字与参数）
     * @param toolTokens     工具结果 token（ToolResponseMessage 各 response 的 responseData）
     */
    public record TokenBreakdown(long systemTokens, long userTokens,
                                 long assistantTokens, long toolTokens) {

        public static TokenBreakdown empty() {
            return new TokenBreakdown(0L, 0L, 0L, 0L);
        }
    }

    /**
     * 每回合真实请求的估算 token = 会话消息估算 + 系统提示词快照。
     * {@code /context} 总数行与状态栏「上下文 N%」统一用这个口径：
     * 系统提示词每回合都完整重发，单看会话消息会把这笔固定开销算没，
     * 两处各用各的分母又会互相矛盾。
     */
    public long perTurnTokens() {
        return estimatedTokens + systemPromptTokens;
    }

    /** 17 参便捷构造：老调用点不填缓存与分桶字段（等价无缓存命中数据、零分桶）。 */
    public ContextStats(int events, int userEvents, int assistantEvents, int toolEvents, int otherEvents,
                        long estimatedTokens, long tokenThreshold, long contextWindow,
                        int autoKeepEvents, int manualKeepEvents, int visionImages, long visionTokens,
                        long cacheReadTokens, long billedInputTokens, Integer cacheHitPercent) {
        this(events, userEvents, assistantEvents, toolEvents, otherEvents, estimatedTokens,
                tokenThreshold, contextWindow, autoKeepEvents, manualKeepEvents, visionImages, visionTokens,
                cacheReadTokens, billedInputTokens, cacheHitPercent, TokenBreakdown.empty(), 0L);
    }

    /** 12 参便捷构造：老调用点不填缓存字段（等价无缓存命中数据）。 */
    public ContextStats(int events, int userEvents, int assistantEvents, int toolEvents, int otherEvents,
                        long estimatedTokens, long tokenThreshold, long contextWindow,
                        int autoKeepEvents, int manualKeepEvents, int visionImages, long visionTokens) {
        this(events, userEvents, assistantEvents, toolEvents, otherEvents, estimatedTokens,
                tokenThreshold, contextWindow, autoKeepEvents, manualKeepEvents, visionImages, visionTokens,
                0L, 0L, null);
    }

    /** 空快照（会话尚无事件 / 回显桩用）。 */
    public static ContextStats empty() {
        return new ContextStats(0, 0, 0, 0, 0, 0L,
                0L, 0L, 0, 0, 0, 0L, 0L, 0L, null, TokenBreakdown.empty(), 0L);
    }
}

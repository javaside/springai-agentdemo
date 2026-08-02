package io.github.javaside.springai.codetui.agent.media;

/**
 * <b>本回合</b>累计兑现的统计快照，供 {@code /context} 单列视觉占用。不可变，volatile 发布。
 *
 * <p><b>为什么按回合而不是按请求</b>：一个回合有几十次工具迭代，用户按 {@code /context} 那一刻，
 * 「上一次请求」几乎必然兑现 0 张（{@link VisionBudget#MAX_TURN_DELIVERIES} 用尽后每次都是 0；
 * 回合一结束引用落进历史，按「当轮兑现」规则更不会再兑现）。按请求记则这个数字在实践中恒为零，
 * 等于没写。按回合累计后回合内稳定、回合结束仍看得见刚才那轮花了多少，也与每回合上限同一口径
 * ——用户能直接读出还剩多少额度。
 *
 * @param turnKey 归属回合的标识（{@code VisionMaterializer} 从消息锚点算出）。同回合累加、
 *                换回合归零全靠它；放进 record 而不是另立字段，是为了让「读旧值→加→写回」
 *                读到的 (回合, 张数, token) 恒是自洽的一组，不会读到半截数据。
 *                {@link #EMPTY} 用 {@code null}，它与任何真实 turnKey 都不相等。
 * @param images  本回合累计兑现的图片张数
 * @param tokens  本回合累计兑现图片的估算 token（<b>只计真发出去的</b>，被预算挡下的不算）
 */
public record VisionSnapshot(String turnKey, int images, long tokens) {

    public static final VisionSnapshot EMPTY = new VisionSnapshot(null, 0, 0L);

    /** 在本回合的账上再加一笔；{@code turnKey} 保持不变。 */
    public VisionSnapshot plus(int moreImages, long moreTokens) {
        return new VisionSnapshot(turnKey, images + moreImages, tokens + moreTokens);
    }
}

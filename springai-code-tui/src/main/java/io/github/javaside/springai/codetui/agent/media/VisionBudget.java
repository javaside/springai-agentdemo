package io.github.javaside.springai.codetui.agent.media;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视觉预算：每请求分来源配额 + 每回合累计上限。
 *
 * <p><b>为什么配额分来源</b>：用户贴的图与工具产的图不是同一种负载。用户一次贴 1–3 张，
 * 那是他这一轮的<b>全部意图</b>；截图循环一个回合能产几十张，且旧截图几乎没有价值。
 * 若一视同仁按「从新到旧」取，「照这张稿子改」的稿子会被随后 Read 的三张图挤掉 ——
 * 功能在最典型的用法上直接失效。故<b>用户图保底不淘汰</b>，工具图只在彼此间竞争取最新一张。
 *
 * <p><b>为什么还要每回合累计</b>：每请求上限只封住单次上下文，封不住循环。20 次迭代 ×
 * 每次 3 张 ≈ 108k token 就在一个回合里。{@link #MAX_TURN_DELIVERIES} 是唯一能真正
 * 封住单回合花费的机制——上限因此可算：12 × ~1.8k ≈ 21.6k token。
 *
 * <p><b>为什么按 turnKey 分桶</b>：{@code ChatModel} 实例被主 agent 与所有子 agent 共用，
 * 并发子 agent 若共用一个计数器会互相冲掉对方的额度。turnKey 由调用方从消息锚点算出
 * （见 {@code VisionMaterializer}），同一回合内所有迭代恒定，不同 agent/回合天然不同。
 */
public final class VisionBudget {

    /** 每请求：用户当轮贴图上限（保底，不参与淘汰）。 */
    public static final int MAX_USER_IMAGES = 3;
    /** 每请求：工具产图上限（取最新一张）。 */
    public static final int MAX_TOOL_IMAGES = 1;
    /** 每请求：视觉 token 硬上限。 */
    public static final long MAX_REQUEST_TOKENS = 6_000L;
    /** 每回合：累计兑现次数（张·次）上限。 */
    public static final int MAX_TURN_DELIVERIES = 12;
    /** 计数表容量上限——超过即清空，防长会话里自身泄漏。 */
    public static final int MAX_TRACKED_TURNS = 8;

    private final Map<String, AtomicInteger> perTurn = new ConcurrentHashMap<>();

    /** 开一次「本请求」的预算会话。 */
    public Session open(String turnKey) {
        return new Session(counter(turnKey));
    }

    /** 当前跟踪的回合数（测试用）。 */
    public int trackedTurns() {
        return perTurn.size();
    }

    private AtomicInteger counter(String turnKey) {
        if (perTurn.size() >= MAX_TRACKED_TURNS && !perTurn.containsKey(turnKey)) {
            perTurn.clear();   // 粗暴但足够：回合是强时序的，老 key 不会再被访问
        }
        return perTurn.computeIfAbsent(turnKey, k -> new AtomicInteger());
    }

    /** 单次请求内的预算账本。非线程安全——一次 materialize 只在一个线程里跑完。 */
    public static final class Session {

        private final AtomicInteger turnCounter;
        private long requestTokens;

        private Session(AtomicInteger turnCounter) {
            this.turnCounter = turnCounter;
        }

        /** 本请求的 token 预算还容得下这张图吗？容得下则记账并返回 true。 */
        public boolean admit(long tokens) {
            if (requestTokens + tokens > MAX_REQUEST_TOKENS) {
                return false;
            }
            requestTokens += tokens;
            return true;
        }

        /** 占用一个回合额度；已用尽返回 false（调用方据此写 turn_budget_exhausted）。 */
        public boolean tryConsumeTurnSlot() {
            return turnCounter.incrementAndGet() <= MAX_TURN_DELIVERIES;
        }

        /** 本请求已计入的视觉 token（供 /context 统计）。 */
        public long tokensUsed() {
            return requestTokens;
        }
    }
}

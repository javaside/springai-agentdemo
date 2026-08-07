package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingAgentInterjectionFacadeTest {

    /** 桩路径（interjections==null）必须全 no-op，不能 NPE——大量既有测试走的是短构造。 */
    @Test
    @DisplayName("SubmitHandler 默认实现全 no-op")
    void defaultsAreNoOp() {
        SubmitHandler h = text -> () -> {};

        h.interject("随便说点");                                  // 不应抛
        assertEquals(0, h.pendingInterjections());
        assertEquals(List.of(), h.takePendingInterjections());
        assertEquals(List.of(), h.takeBackInterjections());
    }

    /** CodingAgent 自己的桩路径同样要 no-op：短重载补的是 null，四个方法都得挡住。 */
    @Test
    @DisplayName("无队列的 CodingAgent 四个门面方法全 no-op")
    void agentWithoutQueueIsNoOp() {
        SubmitHandler h = facadeOver(null);

        h.interject("随便说点");                                  // 不应 NPE
        assertEquals(0, h.pendingInterjections());
        assertEquals(List.of(), h.takePendingInterjections());
        assertEquals(List.of(), h.takeBackInterjections());
    }

    /** interject 必须落进队列，否则状态栏永远是 0、模型永远收不到。 */
    @Test
    @DisplayName("interject 落进队列并被 pendingInterjections 看见")
    void interjectReachesQueue() {
        Interjections q = new Interjections();
        SubmitHandler h = facadeOver(q);

        h.interject("改用方案 B");

        assertEquals(1, h.pendingInterjections());
        assertEquals(List.of("改用方案 B"), q.takePendingOnly());
    }

    /** 兜底出队与 Esc 回填取的是不同的东西——门面不能把两者委托到同一个队列方法。 */
    @Test
    @DisplayName("takePending 只取未送达，takeBack 通吃")
    void facadeDelegatesToDistinctConsumers() {
        Interjections q = new Interjections();
        q.offer("先说的");
        q.drainForInjection("call-1");   // 送达
        q.offer("后说的");

        SubmitHandler pending = facadeOver(q);
        assertEquals(List.of("后说的"), pending.takePendingInterjections(),
                "兜底出队取到了已送达的那条 → 会和补历史抢，同一句话发两遍");
        assertTrue(q.takeForHistory().isPresent(), "delivered 应仍在，留给补历史");
    }

    /** Esc 那条通吃：已送达的排前面，且交还原文而非包裹后的文本。 */
    @Test
    @DisplayName("takeBack 交还已送达与未送达的原文")
    void takeBackReturnsDeliveredAndPending() {
        Interjections q = new Interjections();
        q.offer("先说的");
        q.drainForInjection("call-1");
        q.offer("后说的");

        assertEquals(List.of("先说的", "后说的"), facadeOver(q).takeBackInterjections());
        assertTrue(q.takeForHistory().isEmpty(), "takeBack 之后 delivered 应已清空");
    }

    /**
     * 门面的真实实现是 {@code CodingAgent}——测替身只会证明替身接得上。
     *
     * <p>这里用全参构造装一个「只带插话队列」的最小 agent：其余协作者一律 null，本类不触发 submit。
     */
    /**
     * {@code /clear} 换了新会话：delivered/anchor 指向的旧会话已经不存在，留着会让下个回合的
     * 补历史插到一个对不上的位置。未送达的也一并丢——用户主动清空了上下文。
     */
    @Test
    @DisplayName("/clear 后插话队列清空（未送达与已送达都丢）")
    void clearContextDropsInterjections() {
        Interjections q = new Interjections();
        q.offer("旧会话里说的");
        q.drainForInjection("call-1");   // 送达，进 delivered
        q.offer("旧会话里还没送出去的");   // 未送达

        facadeOver(q).clearContext();

        assertEquals(0, q.pendingCount(), "未送达的也该丢——用户主动清空了上下文");
        assertTrue(q.takeForHistory().isEmpty(), "delivered/anchor 指向的旧会话已不存在");
    }

    /** 无队列的桩路径调 /clear 不能 NPE：既有测试大量走短构造。 */
    @Test
    @DisplayName("无队列时 /clear 不抛")
    void clearContextWithoutQueueDoesNotThrow() {
        facadeOver(null).clearContext();
    }

    private static SubmitHandler facadeOver(Interjections q) {
        return new CodingAgent(null, java.util.Map.of(),
                new io.github.javaside.springai.codetui.ui.ConversationState(), "sid",
                new java.util.concurrent.atomic.AtomicLong(),
                null, null, null, List.of(), null, null, null, null, null, null, null, null, null, null, q);
    }
}

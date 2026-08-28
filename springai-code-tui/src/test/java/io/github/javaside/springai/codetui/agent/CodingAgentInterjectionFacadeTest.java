package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.interjection.Interjections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;

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

    // ── 面板快照门面 ──

    /** 面板每帧读它。委托错到 takePendingOnly 上就会「渲染一帧把队列清空」，插话再也送不出去。 */
    @Test
    @DisplayName("pendingInterjectionTexts 是非破坏性的")
    void snapshotFacadeIsNonDestructive() {
        Interjections q = new Interjections();
        q.offer("第一句");
        SubmitHandler h = facadeOver(q);

        assertEquals(List.of("第一句"), h.pendingInterjectionTexts());
        assertEquals(List.of("第一句"), h.pendingInterjectionTexts(), "渲染一帧就把队列清空了");
        assertEquals(1, q.pendingCount());
    }

    @Test
    @DisplayName("无队列时快照门面返回空表")
    void snapshotFacadeWithoutQueue() {
        assertEquals(List.of(), facadeOver(null).pendingInterjectionTexts());
        assertEquals(List.of(), ((SubmitHandler) text -> () -> {}).pendingInterjectionTexts());
    }

    // ── 送达 → 信息流 ──

    /**
     * 构造 {@code CodingAgent} 就该把送达回调接上，且落到 {@code onUserMessage} 上——
     * 插话本来就是一条用户消息，它该和排队消息出队时长得一模一样（{@code › 原话} + {@code Kind.USER}）。
     * 另起一路渲染就会渲成别的样子，那正是这次要修掉的东西。
     */
    @Test
    @DisplayName("送达时打进信息流，形状同普通用户消息")
    void deliveryLandsInStreamAsUserMessage() {
        Interjections q = new Interjections();
        var state = new io.github.javaside.springai.codetui.ui.ConversationState();
        var turn = new java.util.concurrent.atomic.AtomicLong(7);
        agentOver(q, state, turn);
        state.onTurnStarted(7);
        state.drainPending();                      // 清掉开场行，只看插话打出来的

        q.fireDelivered("改用方案 B");

        var lines = state.drainPending();
        assertTrue(lines.stream().anyMatch(l ->
                        l.kind() == io.github.javaside.springai.codetui.ui.ConversationState.OutputLine.Kind.USER
                                && l.text().equals("› 改用方案 B")),
                "送达应打出一条与普通用户消息同形状的行，实际：" + lines);
    }

    /**
     * 交出的必须是<b>原文</b>：包裹后的文本里那段行为指引是给模型看的，
     * 把它渲进信息流就成了「用户说过这句话」——{@code -c} 回放踩过同一个坑。
     */
    @Test
    @DisplayName("信息流里是原文，不含 [interjection] 包裹")
    void streamShowsRawTextNotWrapper() {
        Interjections q = new Interjections();
        var state = new io.github.javaside.springai.codetui.ui.ConversationState();
        agentOver(q, state, new java.util.concurrent.atomic.AtomicLong(7));
        state.onTurnStarted(7);
        state.drainPending();

        q.offer("改用方案 B");
        q.drainForInjection("call-1");             // 真实送达路径：经队列而非直调 fireDelivered
        q.fireDelivered("改用方案 B");

        String all = state.drainPending().stream()
                .map(io.github.javaside.springai.codetui.ui.ConversationState.OutputLine::text)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("改用方案 B"));
        assertTrue(!all.contains("[interjection]") && !all.contains("未完成的工作仍在进行中"),
                "把给模型的行为指引渲成了用户原话：" + all);
    }

    /**
     * 回合已被 Esc 取消后迟到的送达不该再打进流——它属于一个用户已经放弃的回合。
     * 这条是白送的：回调走 {@code onUserMessage}，那里本就有 turnId 迟到过滤。
     * 但只有真接在 {@code onUserMessage} 上才白送，另起一路就得自己补。
     */
    @Test
    @DisplayName("迟到送达被回合过滤挡住")
    void lateDeliveryIsFiltered() {
        Interjections q = new Interjections();
        var state = new io.github.javaside.springai.codetui.ui.ConversationState();
        agentOver(q, state, new java.util.concurrent.atomic.AtomicLong(7));
        state.onTurnStarted(7);
        state.drainPending();

        // ⚠ 先断言「回合内的送达确实进得来」。只留下面那半条反向断言的话，
        // 接线整个被拆掉时它照样绿——什么都没送达，自然也就「没有迟到的话」。
        q.fireDelivered("回合内的话");
        assertTrue(state.drainPending().stream().anyMatch(l -> l.text().contains("回合内的话")),
                "回合内的送达就该进流——这半条在，下半条才是有效断言");

        state.cancelCurrent();                     // 用户按了 Esc
        state.drainPending();
        q.fireDelivered("迟到的话");

        assertTrue(state.drainPending().stream().noneMatch(l -> l.text().contains("迟到的话")),
                "已取消回合的迟到送达不该再打进流");
    }

    /**
     * 门面的真实实现是 {@code CodingAgent}——测替身只会证明替身接得上。
     *
     * <p>这里用全参构造装一个「只带插话队列」的最小 agent：其余协作者一律 null，本类不触发 submit。
     */
    private static SubmitHandler facadeOver(Interjections q) {
        return agentOver(q, new io.github.javaside.springai.codetui.ui.ConversationState(),
                new java.util.concurrent.atomic.AtomicLong());
    }

    private static CodingAgent agentOver(Interjections q,
                                         io.github.javaside.springai.codetui.ui.ConversationState state,
                                         java.util.concurrent.atomic.AtomicLong turn) {
        return new CodingAgent(null, java.util.Map.of(), state, "sid", turn,
                null, null, null, List.of(), null, null, null, null, null, null, null, null, null, null, q);
    }
}

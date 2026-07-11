package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 层③ 回归：Esc 取消并行子 agent 后，state 立即回 IDLE，但在飞子 agent 可能仍在跑、其迟到写入会污染会话。
 * View 的 busy 闸门必须把「有在飞子 agent」也算作忙——否则出队/续跑会立即起新回合，与旧回合并发写同一会话。
 * 这里直接驱动 {@code drain()}（出队路径），验证:在飞时排队消息<b>不</b>被派发；在飞清零后才派发。
 */
class CodeTuiViewInFlightGateTest {

    /** 可切换在飞标志、记录 submit 次数的 SubmitHandler 桩。 */
    private static final class Handler implements SubmitHandler {
        final AtomicBoolean inFlight = new AtomicBoolean(false);
        final AtomicInteger submits = new AtomicInteger();
        @Override public Disposable submit(String text) { submits.incrementAndGet(); return () -> {}; }
        @Override public boolean hasInFlightSubagents() { return inFlight.get(); }
    }

    @Test
    void drain_withInFlightSubagents_keepsMessageQueued_untilCleared() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        // 模拟:回合被 Esc 取消 → state 回 IDLE，但仍有在飞子 agent；此时来了一条排队消息。
        s.cancelCurrent();
        h.inFlight.set(true);
        s.enqueue("继续执行上一批未完成的计划", null);
        assertEquals(1, s.queuedCount());

        v.tickForTest();   // drain:busy()=isIdle(false→空闲) 但 hasInFlightSubagents=true ⇒ 忙 ⇒ 不出队
        assertEquals(1, s.queuedCount(), "有在飞子 agent 时排队消息不应被派发");
        assertEquals(0, h.submits.get(), "不应起新回合");

        h.inFlight.set(false);   // 旧子 agent 清空
        v.tickForTest();         // drain:此刻 busy()=false ⇒ 出队派发
        assertEquals(0, s.queuedCount(), "在飞清零后排队消息被派发");
        assertEquals(1, h.submits.get(), "起新回合一次");
    }

    @Test
    void drainingHint_shownOnlyWhenIdleAndInFlightSubagents() {
        // 取消并行子 agent 后 state 回 IDLE 但子 agent 可能仍在收尾（busy 仍 true、消息静默入队）——
        // 空闲且有在飞子 agent 时才给「等待收尾」提示；非空闲（THINKING/RUNNING 自有指示）或无在飞时不显示。
        assertNotNull(CodeTuiView.drainingSubagentsHint(true, true), "空闲 + 有在飞子 agent → 显示提示");
        assertNull(CodeTuiView.drainingSubagentsHint(true, false), "空闲 + 无在飞 → 常态行");
        assertNull(CodeTuiView.drainingSubagentsHint(false, true), "非空闲（回合进行中）→ 不覆盖思考/工具指示");
        assertNull(CodeTuiView.drainingSubagentsHint(false, false), "非空闲且无在飞 → 常态");
    }
}

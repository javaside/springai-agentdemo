package io.github.javaside.springai.codetui.agent.seam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanRequestTest {

    private record Answered(PlanOutcome outcome, String feedback) {}

    @Test
    @DisplayName("cancel() 投 CANCEL——取消期排空循环靠它唤醒工具线程")
    void cancelRespondsCancel() {
        List<Answered> sink = new CopyOnWriteArrayList<>();
        PlanRequest r = new PlanRequest(7L, "# 计划\n- 步骤一",
                (o, f) -> sink.add(new Answered(o, f)));

        r.cancel();

        assertEquals(1, sink.size());
        assertEquals(PlanOutcome.CANCEL, sink.get(0).outcome());
        assertEquals(7L, r.turnId());
    }

    @Test
    @DisplayName("null responder 在构造期就失败——留到排空期会中断循环、让其后的工具线程永久 park")
    void rejectsNullResponder() {
        assertThrows(NullPointerException.class, () -> new PlanRequest(1L, "x", null));
    }

    @Test
    @DisplayName("cancel() 可重复调用且不抛——契约要求（排空循环遍历整条队列）")
    void cancelIsIdempotentAndNeverThrows() {
        List<Answered> sink = new CopyOnWriteArrayList<>();
        PlanRequest r = new PlanRequest(1L, "x", (o, f) -> sink.add(new Answered(o, f)));
        r.cancel();
        r.cancel();
        assertTrue(sink.size() >= 1, "重复取消不得抛异常");
    }

    @Test
    @DisplayName("计划正文为 null 时归一成空串——面板渲染不判空")
    void nullPlanBecomesEmpty() {
        PlanRequest r = new PlanRequest(1L, null, (o, f) -> { });
        assertEquals("", r.plan());
    }
}

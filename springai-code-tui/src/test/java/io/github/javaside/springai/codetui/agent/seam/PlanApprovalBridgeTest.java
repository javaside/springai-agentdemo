package io.github.javaside.springai.codetui.agent.seam;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanApprovalBridgeTest {

    /** 捕获请求、不应答的 listener（模拟 UI 还没选）。 */
    private static class Capturing implements AgentListenerAdapter {
        final AtomicReference<PlanRequest> seen = new AtomicReference<>();
        final CountDownLatch arrived = new CountDownLatch(1);
        @Override public void onPlanSubmitted(long turnId, PlanRequest request) {
            seen.set(request);
            arrived.countDown();
        }
    }

    @Test
    @DisplayName("批准：工具线程被唤醒，返回串告诉模型可以开始，并按选项切了模式")
    void approveWakesToolThreadAndSwitchesMode() throws Exception {
        Capturing listener = new Capturing();
        AtomicReference<PermissionMode> switched = new AtomicReference<>();
        PlanApprovalBridge bridge = new PlanApprovalBridge(listener, switched::set);

        AtomicReference<String> result = new AtomicReference<>();
        Thread tool = new Thread(() -> result.set(bridge.handle("# 计划\n- 步骤一")));
        tool.start();

        assertTrue(listener.arrived.await(2, TimeUnit.SECONDS), "请求应立刻交给 listener，不得阻塞在回调里");
        listener.seen.get().responder().respond(PlanOutcome.APPROVE_ACCEPT_EDITS, "");
        tool.join(2000);

        assertTrue(result.get().contains("批准"), "返回串要让模型知道可以动手了：" + result.get());
        assertEquals(PermissionMode.ACCEPT_EDITS, switched.get(), "选项一应切到自动接受编辑");
    }

    @Test
    @DisplayName("继续完善：模式不动，反馈文本原样带回给模型")
    void keepPlanningCarriesFeedback() throws Exception {
        Capturing listener = new Capturing();
        AtomicReference<PermissionMode> switched = new AtomicReference<>();
        PlanApprovalBridge bridge = new PlanApprovalBridge(listener, switched::set);

        AtomicReference<String> result = new AtomicReference<>();
        Thread tool = new Thread(() -> result.set(bridge.handle("# 计划")));
        tool.start();
        assertTrue(listener.arrived.await(2, TimeUnit.SECONDS));

        listener.seen.get().responder().respond(PlanOutcome.KEEP_PLANNING, "先补上回滚方案");
        tool.join(2000);

        assertTrue(result.get().contains("先补上回滚方案"), "反馈必须原样回给模型：" + result.get());
        assertNull(switched.get(), "继续完善时不得切模式");
    }

    @Test
    @DisplayName("空反馈不得给模型一个悬空的冒号——面板允许空输入，措辞要说清「没有具体意见」")
    void blankFeedbackGetsUsableWording() throws Exception {
        Capturing listener = new Capturing();
        PlanApprovalBridge bridge = new PlanApprovalBridge(listener, m -> { });

        AtomicReference<String> result = new AtomicReference<>();
        Thread tool = new Thread(() -> result.set(bridge.handle("# 计划")));
        tool.start();
        assertTrue(listener.arrived.await(2, TimeUnit.SECONDS));

        listener.seen.get().responder().respond(PlanOutcome.KEEP_PLANNING, "   ");
        tool.join(2000);

        assertFalse(result.get().contains("计划："), "空反馈不该留下悬空冒号：" + result.get());
        assertTrue(result.get().contains("没有给出具体意见"), "要让模型知道是「没意见」而不是「意见丢了」");
        assertTrue(result.get().contains("ExitPlanMode"), "仍要指路");
    }

    @Test
    @DisplayName("取消：工具线程抛 PermissionCancelledException（回合已被 dispose，异常随流丢弃）")
    void cancelThrows() throws Exception {
        Capturing listener = new Capturing();
        PlanApprovalBridge bridge = new PlanApprovalBridge(listener, m -> { });

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread tool = new Thread(() -> {
            try {
                bridge.handle("# 计划");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        tool.start();
        assertTrue(listener.arrived.await(2, TimeUnit.SECONDS));

        listener.seen.get().cancel();          // 走 ModalRequest 统一取消入口
        tool.join(2000);

        assertNotNull(thrown.get());
        assertEquals(PermissionCancelledException.class, thrown.get().getClass());
    }

    @Test
    @DisplayName("回合被 dispose 中断工具线程 → 抛异常且中断位被重新置上（第二条逃生口）")
    void interruptEscapes() throws Exception {
        Capturing listener = new Capturing();
        PlanApprovalBridge bridge = new PlanApprovalBridge(listener, m -> { });

        AtomicReference<Boolean> interruptFlag = new AtomicReference<>();
        Thread tool = new Thread(() -> {
            try {
                bridge.handle("# 计划");
            } catch (PermissionCancelledException e) {
                interruptFlag.set(Thread.currentThread().isInterrupted());
            }
        });
        tool.start();
        assertTrue(listener.arrived.await(2, TimeUnit.SECONDS));

        tool.interrupt();
        tool.join(2000);

        assertEquals(Boolean.TRUE, interruptFlag.get(),
                "吞掉 InterruptedException 而不重新置位，会让上层再也看不到取消信号");
    }

    @Test
    @DisplayName("listener 默认实现不得挂死——没接管 UI 的落地端应当「继续完善计划」而不是永久 park")
    void defaultListenerDoesNotHang() throws Exception {
        AgentListenerAdapter bare = new AgentListenerAdapter() { };   // 不覆写 onPlanSubmitted
        PlanApprovalBridge bridge = new PlanApprovalBridge(bare, m -> { });

        AtomicReference<String> result = new AtomicReference<>();
        Thread tool = new Thread(() -> result.set(bridge.handle("# 计划")));
        tool.start();
        tool.join(2000);

        assertEquals(Thread.State.TERMINATED, tool.getState(), "默认实现让工具线程永久 park 就是静默挂死");
        assertNotNull(result.get());
    }
}

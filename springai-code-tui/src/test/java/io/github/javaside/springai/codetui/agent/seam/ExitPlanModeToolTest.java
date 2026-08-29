package io.github.javaside.springai.codetui.agent.seam;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.ToolCategory;
import io.github.javaside.springai.codetui.agent.permission.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitPlanModeToolTest {

    private static class Capturing implements AgentListenerAdapter {
        final AtomicReference<PlanRequest> seen = new AtomicReference<>();
        final CountDownLatch arrived = new CountDownLatch(1);
        @Override public void onPlanSubmitted(long turnId, PlanRequest request) {
            seen.set(request);
            arrived.countDown();
        }
    }

    @Test
    @DisplayName("注册名是 ExitPlanMode，且已登记为 INTERNAL——否则它自己会被 PLAN 模式拦住")
    void registeredAsInternal() {
        ToolCallback tool = PlanApprovalBridge.exitPlanModeTool(
                new PlanApprovalBridge(new Capturing(), m -> { }));
        assertEquals("ExitPlanMode", tool.getToolDefinition().name());

        ToolRegistry.Entry e = ToolRegistry.lookup("ExitPlanMode");
        assertEquals(ToolCategory.INTERNAL, e.category(),
                "没登记就是 UNKNOWN，PLAN 模式下会 DENY 掉它——计划模式成了没有出口的死胡同");
    }

    @Test
    @DisplayName("call 走桥：计划正文原样交给 UI，用户选完的结果原样回给模型")
    void callGoesThroughBridge() throws Exception {
        Capturing l = new Capturing();
        AtomicReference<PermissionMode> switched = new AtomicReference<>();
        ToolCallback tool = PlanApprovalBridge.exitPlanModeTool(new PlanApprovalBridge(l, switched::set));

        AtomicReference<String> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(tool.call("{\"plan\":\"# 我的计划\\n- 第一步\"}")));
        t.start();

        assertTrue(l.arrived.await(2, TimeUnit.SECONDS));
        assertTrue(l.seen.get().plan().contains("# 我的计划"), "计划正文要原样传到 UI");

        l.seen.get().responder().respond(PlanOutcome.APPROVE_DEFAULT, "");
        t.join(2000);

        assertTrue(out.get().contains("批准"));
        assertEquals(PermissionMode.DEFAULT, switched.get());
    }

    @Test
    @DisplayName("描述里必须写清「什么时候调它」——否则模型在计划模式下不知道出口在哪")
    void descriptionTellsWhenToCall() {
        ToolCallback tool = PlanApprovalBridge.exitPlanModeTool(
                new PlanApprovalBridge(new Capturing(), m -> { }));
        String d = tool.getToolDefinition().description();
        assertTrue(d.contains("计划模式") || d.contains("plan mode"), "描述：" + d);
    }
}

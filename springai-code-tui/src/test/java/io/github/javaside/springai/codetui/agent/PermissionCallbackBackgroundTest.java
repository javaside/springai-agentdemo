package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;

/**
 * 后台任务撞到 ASK 时不弹面板、直接 DENY，且按档位给建议；BYPASS 档零特殊处理。
 *
 * <p><b>为什么整类挂超时</b>：{@code askThenAct} 会在 {@code handoff.take()} 上无限阻塞等 UI 应答。
 * 只要「后台 ASK 就地降级」这段分流被去掉，后台用例就会走进那个 take() ——若没有超时，
 * 缺陷表现为<b>构建挂死</b>而不是用例变红，等于没有测试。变异验证时实测过：删掉后台分支，
 * 本类原样卡住 10 分钟无输出。SEPARATE_THREAD 是必须的——默认的 SAME_THREAD 只在用例返回后才检查，
 * 对永久阻塞完全无效。
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PermissionCallbackBackgroundTest {

    /** 记录是否真的执行了底层工具。 */
    private static final class Spy implements ToolCallback {
        final AtomicInteger invoked = new AtomicInteger();
        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("Write").description("d").inputSchema("{}").build();
        }
        @Override public String call(String input) { invoked.incrementAndGet(); return "done"; }
        @Override public String call(String input, ToolContext ctx) { return call(input); }
    }

    /** 后台调用的 ToolContext：turnId=-1 + backgroundTaskId。用 LinkedHashMap 固定迭代序。 */
    private static ToolContext backgroundCtx(String taskId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turnId", -1L);
        m.put("taskId", taskId);
        m.put("backgroundTaskId", taskId);
        return new ToolContext(m);
    }

    /**
     * <b>会应答</b>的审批出口桩，并记录被问了几次。
     *
     * <p>后台用例断言的是「压根没问」，看起来给个只记数的空桩就够了——<b>不够</b>。空桩在缺陷存在时
     * 让工具线程永久 park 在 {@code handoff.take()}，整个测试类挂死，CI 上看到的是超时而不是失败。
     * 真实 UI 总会应答，桩也必须应答：这样缺陷一出现就是断言当场变红（asked 从 0 变 1）。
     */
    private static PermissionCallback.Asker respondingAsker(AtomicInteger asked) {
        return (turnId, req) -> {
            asked.incrementAndGet();
            req.responder().respond(PermissionOutcome.ALLOW_ONCE);
        };
    }

    /** 前台调用的 ToolContext：有真实 turnId，无后台标记。 */
    private static ToolContext foregroundCtx(long turnId, String taskId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turnId", turnId);
        if (taskId != null) m.put("taskId", taskId);
        return new ToolContext(m);
    }

    @Test
    void backgroundAskBecomesDenyAndNeverReachesTheAsker() {
        Spy spy = new Spy();
        AtomicInteger asked = new AtomicInteger();
        var engine = PermissionTestSupport.engineAlwaysAsk(PermissionMode.DEFAULT);
        PermissionCallback pc = new PermissionCallback(spy, engine, respondingAsker(asked));

        String out = pc.call("{\"path\":\"/tmp/x\"}", backgroundCtx("task_ab12"));

        assertEquals(0, asked.get(), "后台任务绝不能弹审批面板——它的 turnId 早已过期，请求会被静默 DENY");
        assertEquals(0, spy.invoked.get(), "被拒的调用根本不该执行");
        assertTrue(out.contains("后台"), "拒绝理由要说明是因为后台任务：" + out);
    }

    @Test
    void foregroundAskStillReachesTheAskerEvenWithTaskId() {
        Spy spy = new Spy();
        AtomicInteger asked = new AtomicInteger();
        var engine = PermissionTestSupport.engineAlwaysAsk(PermissionMode.DEFAULT);
        PermissionCallback pc = new PermissionCallback(spy, engine, (turnId, req) -> {
            asked.incrementAndGet();
            req.responder().respond(PermissionOutcome.ALLOW_ONCE);
        });

        pc.call("{\"path\":\"/tmp/x\"}", foregroundCtx(7L, "task_front"));

        assertEquals(1, asked.get(),
                "前台子 agent 也有 taskId——绝不能靠 taskId 判后台，否则前台审批会静默消失");
        assertEquals(1, spy.invoked.get());
    }

    @Test
    void backgroundAllowStillRuns() {
        Spy spy = new Spy();
        var engine = PermissionTestSupport.engineAlwaysAllow(PermissionMode.DEFAULT);
        PermissionCallback pc = new PermissionCallback(spy, engine,
                (turnId, req) -> { throw new AssertionError("ALLOW 不该问"); });

        assertEquals("done", pc.call("{}", backgroundCtx("task_ab12")));
        assertEquals(1, spy.invoked.get(), "命中 allow 规则的后台调用照常执行");
    }

    @Test
    void backgroundDenyIsUnchanged() {
        Spy spy = new Spy();
        var engine = PermissionTestSupport.engineAlwaysDeny(PermissionMode.DEFAULT);
        PermissionCallback pc = new PermissionCallback(spy, engine,
                (turnId, req) -> { throw new AssertionError("DENY 不该问"); });

        pc.call("{}", backgroundCtx("task_ab12"));
        assertEquals(0, spy.invoked.get());
    }

    @Test
    void bypassModeNeedsNoSpecialHandlingForBackground() {
        // BYPASS 判定顺序是「deny → BYPASS 放行 → 后面全跳过」，根本不产生 ASK。
        // 故后台与前台走的是同一条路，本用例钉住"没有为 BYPASS 引入任何后台分支"。
        Spy spy = new Spy();
        var engine = PermissionTestSupport.engineAlwaysAllow(PermissionMode.BYPASS);
        PermissionCallback pc = new PermissionCallback(spy, engine,
                (turnId, req) -> { throw new AssertionError("BYPASS 档不该产生任何询问"); });

        assertEquals("done", pc.call("{}", backgroundCtx("task_ab12")));
        assertEquals("done", pc.call("{}", foregroundCtx(1L, null)));
        assertEquals(2, spy.invoked.get());
    }

    @Test
    void denyMessageSuggestsForegroundRetryInDefaultMode() {
        var engine = PermissionTestSupport.engineAlwaysAsk(PermissionMode.DEFAULT);
        PermissionCallback pc = new PermissionCallback(new Spy(), engine,
                respondingAsker(new AtomicInteger()));
        String out = pc.call("{}", backgroundCtx("task_ab12"));
        assertTrue(out.contains("run_in_background=false"), "要给出可执行的下一步：" + out);
    }

    @Test
    void denyMessageSuggestsAcceptEditsWorkspaceHintInAcceptEditsMode() {
        var engine = PermissionTestSupport.engineAlwaysAsk(PermissionMode.ACCEPT_EDITS);
        PermissionCallback pc = new PermissionCallback(new Spy(), engine,
                respondingAsker(new AtomicInteger()));
        String out = pc.call("{}", backgroundCtx("task_ab12"));
        assertTrue(out.contains("工作区"), "这一档区内本可自动放行，要提示目标在区外：" + out);
    }

    @Test
    void denyMessageInPlanModeTellsModelToReportBackInsteadOfRetrying() {
        var engine = PermissionTestSupport.engineAlwaysAsk(PermissionMode.PLAN);
        PermissionCallback pc = new PermissionCallback(new Spy(), engine,
                respondingAsker(new AtomicInteger()));
        String out = pc.call("{}", backgroundCtx("task_ab12"));
        assertTrue(out.contains("计划模式"), out);
        assertTrue(!out.contains("run_in_background=false"),
                "计划模式下改前台也一样被拒，指这条路等于让它白试一次");
    }
}

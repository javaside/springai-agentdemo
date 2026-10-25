package io.github.javaside.springai.codetui.headless;

import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.AskResponder;
import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;
import io.github.javaside.springai.codetui.agent.seam.PermissionResponder;
import io.github.javaside.springai.codetui.agent.seam.PlanOutcome;
import io.github.javaside.springai.codetui.agent.seam.PlanRequest;
import io.github.javaside.springai.codetui.agent.seam.PlanResponder;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** headless 监听器：分段/取消处置/退出码/latch——纯 JVM，responder 全捕获型桩。 */
class HeadlessAgentListenerTest {

    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    private final HeadlessAgentListener listener =
            new HeadlessAgentListener(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
    private String err() { return errBuf.toString(StandardCharsets.UTF_8); }

    // ── 捕获型桩 ─────────────────────────────────────────────
    private static final class RecAsk implements AskResponder {
        final AtomicInteger cancels = new AtomicInteger();
        @Override public void answer(Map<String, String> a) { }
        @Override public void cancel() { cancels.incrementAndGet(); }
    }
    private record RecPlan(PlanOutcome outcome, String feedback) { }
    private static final class RecPlanResponder implements PlanResponder {
        RecPlan got;
        @Override public void respond(PlanOutcome o, String f) { got = new RecPlan(o, f); }
    }
    private static final class RecPerm implements PermissionResponder {
        final List<PermissionOutcome> got = new ArrayList<>();
        @Override public void respond(PermissionOutcome o) { got.add(o); }
    }
    private static Disposable recordingDisposable(AtomicInteger disposed) {
        return disposed::incrementAndGet;   // Disposable 是单方法接口，lambda 即桩
    }

    @Test
    void segments_sealOnToolStart_finalReplyTakesLastNonEmpty() {
        listener.onAssistantToken(1L, "第一段开头");
        listener.onAssistantToken(1L, "续写");
        listener.onToolStarted(1L, "Bash", "{\"command\":\"ls\"}");   // 封段
        listener.onAssistantToken(1L, "最终结论");
        listener.onTurnComplete(1L);

        assertEquals("最终结论", listener.finalReply());
        assertTrue(err().contains("第一段开头续写"), "中间段进 stderr：" + err());
        assertTrue(err().contains("⏺ Bash"), "工具行进 stderr：" + err());
    }

    @Test
    void finalReply_blankTail_fallsBackToLastMiddle_allBlankEmpty() {
        listener.onAssistantToken(1L, "仅此一段");
        listener.onToolStarted(1L, "Read", "{}");   // 封段后尾部为空
        listener.onTurnComplete(1L);
        assertEquals("仅此一段", listener.finalReply(), "尾部空回退最后非空中间段");

        assertEquals("", new HeadlessAgentListener(System.err).finalReply(), "全程无文本得空串");
    }

    @Test
    void permissionDenied_withNotice_turnContinues() throws Exception {
        RecPerm perm = new RecPerm();
        PermissionRequest req = new PermissionRequest(1L, null, "Bash", "rm -rf /tmp/x",
                "{}", "测试", null, perm);
        listener.onPermissionRequested(1L, req);

        assertEquals(List.of(PermissionOutcome.DENY), perm.got);
        assertTrue(err().contains("headless 拒绝 Bash"), "拒绝要说出来：" + err());
        assertFalse(listener.awaitDone(0), "DENY 不放行 latch——回合继续（0 秒 await 立即返回 false）");
    }

    @Test
    void questionCancelsTurn_exit2_latchAlwaysReleased() throws Exception {
        RecAsk ask = new RecAsk();
        AtomicInteger disposed = new AtomicInteger();
        listener.bindTurn(recordingDisposable(disposed));

        listener.onQuestionAsked(1L, new AskRequest(1L, List.of(), ask));

        assertEquals(1, ask.cancels.get(), "先解除工具线程 park");
        assertEquals(1, disposed.get(), "再 dispose 终止回合");
        assertEquals(2, listener.exitCode());
        assertTrue(listener.awaitDone(1), "latch 已放行");
        assertTrue(err().contains("问询"), err());

        // turn 未注入（bindTurn 理论微窗）也不许 NPE 挂死
        HeadlessAgentListener bare = new HeadlessAgentListener(System.err);
        RecAsk ask2 = new RecAsk();
        bare.onQuestionAsked(1L, new AskRequest(1L, List.of(), ask2));
        assertEquals(2, bare.exitCode());
        assertTrue(bare.awaitDone(1), "无 turn 也放行");
    }

    @Test
    void planCancels_recordsText_exit0() throws Exception {
        RecPlanResponder plan = new RecPlanResponder();
        AtomicInteger disposed = new AtomicInteger();
        listener.bindTurn(recordingDisposable(disposed));

        listener.onPlanSubmitted(1L, new PlanRequest(1L, "## 计划正文", plan));

        assertEquals(PlanOutcome.CANCEL, plan.got.outcome());
        assertEquals(1, disposed.get());
        assertEquals("## 计划正文", listener.planText());
        assertEquals(0, listener.exitCode());
        assertTrue(listener.awaitDone(1));
    }

    @Test
    void errorSetsExit1_completeKeeps0_mcpReadyLatch() throws Exception {
        HeadlessAgentListener l = new HeadlessAgentListener(System.err);
        l.onMcpReady(2, 7);
        assertTrue(l.awaitMcpReady(1), "onMcpReady 放行");
        l.onTurnComplete(1L);
        assertEquals(0, l.exitCode());
        assertTrue(l.awaitDone(1));

        l.onError(2L, new RuntimeException("boom"));
        assertEquals(1, l.exitCode());
        assertTrue(l.awaitDone(1), "错误路径也放行");
    }
}

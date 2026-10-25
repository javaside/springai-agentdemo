package io.github.javaside.springai.codetui.headless;

import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;
import io.github.javaside.springai.codetui.agent.seam.PlanOutcome;
import io.github.javaside.springai.codetui.agent.seam.PlanRequest;
import reactor.core.Disposable;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * headless 模式的 AgentListener：事件→stderr 单行、token 分段累积、模态 fail-closed 处置。
 *
 * <p><b>同步纪律</b>同 ConversationState：事件来自 reactive 与工具两路线程，全方法 synchronized。
 * stderr 是注入的（生产 System.err；测试捕获）——stdout 通道由 Runner 独占（spec：stdout 只有最终回复）。
 */
public final class HeadlessAgentListener implements AgentListener {

    private final PrintStream err;
    private final CountDownLatch done = new CountDownLatch(1);
    private final CountDownLatch mcpReady = new CountDownLatch(1);
    private volatile int exitCode = 0;
    /** submit 后由 Runner 注入。理论微窗内可能为 null（spec 评审：判空 + try/finally，放行必达）。 */
    private volatile Disposable turn;
    private final StringBuilder currentSegment = new StringBuilder();
    private final List<String> middleSegments = new ArrayList<>();
    private String planText;

    public HeadlessAgentListener(PrintStream err) {
        this.err = err;
    }

    // ── Runner 消费口 ────────────────────────────────────────
    public void bindTurn(Disposable turn) { this.turn = turn; }
    public int exitCode() { return exitCode; }
    /** 超时返回 false（Runner 据此 dispose + 退出 1）。 */
    public boolean awaitDone(long seconds) throws InterruptedException {
        return done.await(seconds, TimeUnit.SECONDS);
    }
    public boolean awaitMcpReady(long seconds) throws InterruptedException {
        return mcpReady.await(seconds, TimeUnit.SECONDS);
    }
    public synchronized String planText() { return planText; }

    /** 最后一个非空段（尾部空则回退最后非空中间段；全程无文本返回空串）。 */
    public synchronized String finalReply() {
        // brief 原稿直接 currentSegment.isBlank()——StringBuilder 没有该方法（isBlank/strip 是 String 独有），
        // 取 toString() 后判定，语义不变：尾部非空即最终回复。
        String tail = currentSegment.toString();
        if (!tail.isBlank()) {
            return tail.strip();
        }
        for (int i = middleSegments.size() - 1; i >= 0; i--) {
            if (!middleSegments.get(i).isBlank()) {
                return middleSegments.get(i).strip();
            }
        }
        return "";
    }

    // ── 事件（spec 事件表逐行落地）────────────────────────────
    @Override public synchronized void onTurnStarted(long turnId) { }
    @Override public synchronized void onUserMessage(long turnId, String text) { }

    @Override public synchronized void onAssistantToken(long turnId, String token) {
        if (token != null && !token.isEmpty()) {
            currentSegment.append(token);
        }
    }

    @Override public synchronized void onToolStarted(long turnId, String toolName, String input) {
        sealSegment();
        err.println("⏺ " + toolName + firstLine(input));
    }

    @Override public synchronized void onToolFinished(long turnId, String toolName, String output, boolean ok) {
        err.println("  ⎿ " + (ok ? "✓" : "✗") + firstLine(output));
    }

    @Override public synchronized void onSubagentStarted(long turnId, String taskId, String agentName, String description) {
        err.println("▶ 子 agent " + agentName + "：" + description);
    }

    @Override public synchronized void onSubagentFinished(long turnId, String taskId, String finalText) {
        err.println("◀ 子 agent 结束" + firstLine(finalText));
    }

    @Override public synchronized void onTodoUpdated(long turnId, List<String> todoLines) { }

    @Override public synchronized void onTurnComplete(long turnId) {
        done.countDown();
    }

    @Override public synchronized void onError(long turnId, Throwable error) {
        err.println("✗ 回合失败：" + error);
        exitCode = 1;
        done.countDown();
    }

    /** fail-closed 的带提示版：DENY 后回合继续（默认实现即 DENY，这里只为 stderr 可见性）。 */
    @Override public synchronized void onPermissionRequested(long turnId, PermissionRequest request) {
        err.println("⚠ headless 拒绝 " + request.toolName() + "（" + request.target()
                + "）：无 UI 不可审批；可用 --permission-mode / allow 规则放宽");
        request.responder().respond(PermissionOutcome.DENY);
    }

    /** 取消处置与 UI cancelTurnFor 同序（先应答解除 park 再 dispose）；try/finally 保证放行必达。 */
    @Override public synchronized void onQuestionAsked(long turnId, AskRequest request) {
        err.println("⚠ 模型发起问询，headless 无法交互——取消回合（退出码 2）");
        try {
            request.responder().cancel();
            Disposable d = turn;
            if (d != null) {
                d.dispose();
            }
        } finally {
            exitCode = 2;
            done.countDown();
        }
    }

    /** 计划不批准：CANCEL + dispose，正文留给 stdout（--permission-mode plan + -p 的脚本用法）。 */
    @Override public synchronized void onPlanSubmitted(long turnId, PlanRequest request) {
        err.println("⏸ 模型提交计划——headless 不批准，输出正文后退出");
        planText = request.plan();
        try {
            request.responder().respond(PlanOutcome.CANCEL, "");
            Disposable d = turn;
            if (d != null) {
                d.dispose();
            }
        } finally {
            exitCode = 0;
            done.countDown();
        }
    }

    @Override public synchronized void onRuleRecorded(long turnId, boolean ok, String message) {
        err.println((ok ? "✓ " : "✗ ") + message);
    }

    /** BYPASS 下无人看着，放行记录必须可见。 */
    @Override public synchronized void onGuardrailBypassed(long turnId, String what) {
        err.println("⚠ BYPASS 放行：" + what);
    }

    @Override public synchronized void onRetryScheduled(long turnId, int attempt, int maxAttempts,
                                                        long backoffMs, String reason) {
        err.println("↻ 流式重试 " + attempt + "/" + maxAttempts + "（" + backoffMs + "ms 后）：" + reason);
    }

    @Override public synchronized void onQuotaWaitScheduled(long turnId, long resetAtEpochMs, String reason) {
        err.println("⏳ 限额等待：" + reason);
    }

    @Override public synchronized void onBackgroundTaskStarted(String taskId, String agentName, String description) {
        err.println("▶ 后台任务 " + taskId + "（注意：headless 回合结束即进程退出，后台任务随之终止）");
    }

    @Override public synchronized void onBackgroundTaskFinished(String taskId, String finalText, boolean ok) {
        err.println("◀ 后台任务 " + taskId + (ok ? " ✓" : " ✗"));
    }

    @Override public synchronized void onMcpReady(int serverCount, int toolCount) {
        err.println("（MCP：已连接 " + serverCount + " 个 server / " + toolCount + " 个工具。）");
        mcpReady.countDown();
    }

    @Override public synchronized void onCompactionStarted(String reason) {
        err.println("（会话压缩开始：" + reason + "）");
    }

    @Override public synchronized void onCompactionFinished(int eventsRemoved, int tokensSaved) {
        err.println("（会话压缩完成：移除 " + eventsRemoved + " 事件 / 约省 " + tokensSaved + " token。）");
    }

    @Override public synchronized void onCompactionFailed(String message) {
        err.println("（会话压缩失败：" + message + "）");
    }

    /** 工具开始=上一段收口；段文本先打 stderr（spec：中间段全部进 stderr），再打工具行。 */
    private void sealSegment() {
        String s = currentSegment.toString();
        currentSegment.setLength(0);
        if (!s.isBlank()) {
            middleSegments.add(s);
            err.println(s.strip());   // 中间段正文（模型边做边说的部分）
        }
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String line = s.strip().split("\n", 2)[0];
        return " " + (line.length() > 80 ? line.substring(0, 80) + "…" : line);
    }
}

package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.hooks.HookEvent;
import io.github.javaside.springai.codetui.agent.hooks.HookRegistry;
import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stop 钩子：handleComplete 提交异步执行，onTurnComplete 不被阻塞（spec 定案次序损失）。 */
class CodingAgentStopHookTest {

    /** latch 版 listener：数 onTurnComplete 与 onHookNotice 的到达序（抽象短签名同 RecListener 纪律）。 */
    private static final class LatchListener implements AgentListener {
        final AtomicInteger completes = new AtomicInteger();
        final CountDownLatch notice = new CountDownLatch(1);
        volatile boolean completeBeforeNotice;
        @Override public void onTurnStarted(long t) { }
        @Override public void onUserMessage(long t, String x) { }
        @Override public void onAssistantToken(long t, String x) { }
        @Override public void onToolStarted(long t, String n, String i) { }
        @Override public void onToolFinished(long t, String n, String o, boolean ok) { }
        @Override public void onSubagentStarted(long t, String id, String n, String d) { }
        @Override public void onSubagentFinished(long t, String id, String f) { }
        @Override public void onTodoUpdated(long t, List<String> lines) { }
        @Override public void onTurnComplete(long t) { completes.incrementAndGet(); }
        @Override public void onError(long t, Throwable e) { }
        @Override public void onQuestionAsked(long t, io.github.javaside.springai.codetui.agent.seam.AskRequest r) { }
        @Override public void onCompactionStarted(String r) { }
        @Override public void onCompactionFinished(int e, int s) { }
        @Override public void onCompactionFailed(String m) { }
        @Override public void onHookNotice(String message) {
            completeBeforeNotice = completes.get() > 0;   // 收尾先到 = 未阻塞
            notice.countDown();
        }
    }

    @Test
    void stopHookAsync_noticeAfterComplete_nullRegistryDegrades(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Path user = dir.resolve("user.json");
        Files.writeString(user, "{\"hooks\":{\"Stop\":[{\"command\":\"sleep 1; echo done >&2; exit 1\"}]}}");
        HookRegistry reg = HookRegistry.load(dir, user, dir.resolve("np.json"), dir.resolve("nt.json"));

        // listener 是 7 参构造器第 2 参——直接传入，无需任何测试接缝
        LatchListener l = new LatchListener();
        CodingAgent agent = new CodingAgent(null, l, "s", new java.util.concurrent.atomic.AtomicLong(),
                null, null, null);
        agent.bindHooks(reg);
        agent.handleComplete(1L);   // 本任务已改包私有——同包直调（handleError 先例）

        assertEquals(1, l.completes.get(), "onTurnComplete 同步发出——Stop 不阻塞收尾");
        assertTrue(l.notice.await(10, TimeUnit.SECONDS), "异步钩子完成后回执 onHookNotice");
        assertTrue(l.completeBeforeNotice, "收尾先于钩子回执（异步提交的证明）");

        agent.bindHooks(null);   // null 降级不抛
        agent.handleComplete(2L);
        assertEquals(2, l.completes.get());
    }
}

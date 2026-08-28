package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.AgentTools;
import io.github.javaside.springai.codetui.agent.CodingAgent;
import io.github.javaside.springai.codetui.agent.CodingAgentBackgroundTestSupport;
import io.github.javaside.springai.codetui.agent.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import io.github.javaside.springai.codetui.agent.subagent.SubagentSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /clear} 之后后台派发必须仍然可用——<b>全程走真实现，一个计数桩都不许有</b>。
 *
 * <p><b>为什么单开一个文件、而不是往 {@code CodeTuiViewTasksPanelTest} 里加</b>：那边的
 * {@code TasksStub.killAllBackgroundTasks()} 只做 {@code killAlls.incrementAndGet()}，
 * 从不进生产实现——这正是「/clear 之后后台模式永久失效」当初能全绿溜过去的原因。
 * 只要桩还在中间挡着，这条链上的缺陷就永远测不出来。这里的 handler 反过来
 * <b>把 killAllBackgroundTasks 真委托给生产 CodingAgent</b>，另一头接的是
 * {@code AgentTools.build} 装出来的真 SubagentRunner（含真线程池）。
 *
 * <p>链路：{@code /clear} 按键 → {@code CodeTuiView.submitInput} → {@code SubmitHandler}
 * → {@code CodingAgent.killAllBackgroundTasks} → {@code SubagentRunner} 的后台池 → 再次派发。
 */
class CodeTuiViewClearKeepsBackgroundUsableTest {

    /** 只在 killAllBackgroundTasks 上做<b>真委托</b>；其余方法留空，因为 /clear 这条路不走它们。 */
    private record DelegatingHandler(CodingAgent agent) implements SubmitHandler {
        @Override public Disposable submit(String text) { return null; }
        @Override public void killAllBackgroundTasks() { agent.killAllBackgroundTasks(); }
    }

    private static SubagentSpec spec() {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
    }

    @Test
    @DisplayName("★ /clear 之后仍能派发后台任务（真 View → 真 CodingAgent → 真线程池）")
    void backgroundDispatchStillWorksAfterClear(@TempDir Path root) {
        ConversationState listener = new ConversationState();
        AgentTools.AgentRuntime rt = AgentTools.build(
                new ProviderRegistry(List.of(new DeepSeekProvider("fake-key"))), root, listener);
        CodingAgent agent = CodingAgentBackgroundTestSupport.stub(
                root, rt.backgroundRegistry(), rt.subagentRunner());
        CodeTuiView v = new CodeTuiView(new ConversationState(), new DelegatingHandler(agent), root);

        // 前置：/clear 之前后台派发本来是好的（否则后面的断言是空转的）
        String before = rt.subagentRunner().runInBackground(spec(), "p", "clear 之前");
        assertTrue(before.contains("task_"), "前置：/clear 之前应能派发，实际=" + before);

        v.setInputForTest("/clear");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        String after = rt.subagentRunner().runInBackground(spec(), "p", "clear 之后");
        assertFalse(after.contains("未启动"),
                "/clear 只该终止任务、不该烧掉后台派发能力；ThreadPoolExecutor 一旦 shutdown 就不可复用，"
                        + "而 enableBackground 全仓只在装配期调一次，故这一处失手 = 本进程后台模式永久失效。实际="
                        + after);
        assertTrue(after.contains("task_"), "应立刻返回新 taskId，实际=" + after);

        rt.subagentRunner().shutdownBackground();   // 别把假 provider 的任务留在池里跑
    }
}

package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry;
import io.github.javaside.springai.codetui.agent.background.TaskResultStore;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试专用：造一个<b>只装了后台子系统</b>的 {@link CodingAgent}——其余依赖全 null。
 *
 * <p>目的是让后台四个方法的断言不必联网、也不必把 17 个参数抄进每个测试。
 * 走的仍是生产全参构造，不是另开一条构造路径。
 */
public final class CodingAgentBackgroundTestSupport {

    private CodingAgentBackgroundTestSupport() {
    }

    public static CodingAgent stub(Path root, BackgroundTaskRegistry registry) {
        return stub(root, registry, null);
    }

    public static CodingAgent stub(Path root, BackgroundTaskRegistry registry, SubagentRunner runner) {
        return new CodingAgent(null, null, new StubListener(), "test-session", new AtomicLong(),
                null, null, null, List.of(), null, null, null, runner,
                null, null, null, null, registry, new TaskResultStore(root));
    }
}

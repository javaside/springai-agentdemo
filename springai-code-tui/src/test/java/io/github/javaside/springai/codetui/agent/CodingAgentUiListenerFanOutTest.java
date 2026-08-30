package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry;
import io.github.javaside.springai.codetui.agent.background.TaskResultStore;
import io.github.javaside.springai.codetui.agent.interjection.Interjections;
import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.mcp.McpRegistry;
import io.github.javaside.springai.codetui.agent.mcp.McpServerConfig;
import io.github.javaside.springai.codetui.agent.mcp.McpConfigLoader;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import io.github.javaside.springai.codetui.agent.subagent.SubagentRunner;
import io.github.javaside.springai.codetui.agent.subagent.SubagentSpec;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodingAgent#setUiChangeListener} 的 fan-out 契约（事件驱动 UI 的 Task 4）：
 *
 * <ul>
 *   <li>一次调用把同一 listener 分发给 Interjections、SubagentRunner、BackgroundTaskRegistry、McpRegistry，
 *       <b>四条分发腿各有真实路径验证</b>——删掉任何一条腿的绑定（fix round I-2）本类必须变红；</li>
 *   <li>不重复绑定 ConversationState（View 直接绑，本门面不得越俎代庖）；</li>
 *   <li>缺件（null 子系统）不炸：null 归一后 fan-out 到在场者；</li>
 *   <li>{@link io.github.javaside.springai.codetui.agent.seam.SubmitHandler} 的 default no-op
 *       让既有匿名桩不破坏（编译期契约，由全仓测试回归保证）。</li>
 * </ul>
 */
class CodingAgentUiListenerFanOutTest {

    private static McpConfigLoader.LoadedServer server(Path root, String name) {
        return new McpConfigLoader.LoadedServer(
                new McpServerConfig.StdioServerConfig(name, false, Duration.ofSeconds(2),
                        "/nonexistent/definitely-not-a-real-binary", List.of(), Map.of()),
                McpConfigLoader.ConfigSource.PROJECT, root.resolve("mcp.json"));
    }

    /** 四个 source 各自的收听计数。 */
    private static final class Sinks {
        final List<String> hits = new CopyOnWriteArrayList<>();
    }

    /** 假 provider：SubagentRunner.run() 的最小真实路径（真实计数 + 通知，不联网）。 */
    private static LlmProvider provider() {
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        return new LlmProvider() {
            @Override public String id() { return "fake"; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return model; }
            @Override public ChatOptions options(String modelId) { return ChatOptions.builder().build(); }
            @Override public List<ModelOption> models() { return List.of(new ModelOption("fake-m", "Fake", "d")); }
            @Override public String defaultModel() { return "fake-m"; }
        };
    }

    private static SubagentSpec subSpec() {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
    }

    /** 带真实 provider 的 runner（fullAgent 的默认 runner 没有 registry，run() 走不了真实路径）。 */
    private static SubagentRunner realRunner() {
        return new SubagentRunner(new ProviderRegistry(List.of(provider())), List.of(), new StubListener(), "");
    }

    private CodingAgent fullAgent(Path root, Interjections interjections,
                                  BackgroundTaskRegistry backgroundRegistry, McpRegistry mcpRegistry,
                                  SubagentRunner runner) {
        return new CodingAgent(null, null, new StubListener(), "s", new java.util.concurrent.atomic.AtomicLong(),
                null, null, null, List.of(), null, null, null, runner,
                null, mcpRegistry, null, null, backgroundRegistry,
                backgroundRegistry == null ? null : new TaskResultStore(root), interjections, null, 0L);
    }

    private CodingAgent fullAgent(Path root, Interjections interjections,
                                  BackgroundTaskRegistry backgroundRegistry, McpRegistry mcpRegistry) {
        return fullAgent(root, interjections, backgroundRegistry, mcpRegistry, realRunner());
    }

    @Test
    @DisplayName("setUiChangeListener fan-out 到四个外部 source；各 source 的真实 mutation 都能打到 listener")
    void fanOutReachesAllFourExternalSources(@TempDir Path root) {
        Interjections interjections = new Interjections();
        BackgroundTaskRegistry backgroundRegistry = new BackgroundTaskRegistry(64);
        McpRegistry mcpRegistry = McpRegistry.initForTest(root, new StubListener(),
                List.of(server(root, "s1")), AgentTools.testEngine(root));
        // 挂上可观察的真实 runner：CodingAgent → SubagentRunner 这条分发腿用真实路径验证
        SubagentRunner runner = realRunner();
        CodingAgent agent = fullAgent(root, interjections, backgroundRegistry, mcpRegistry, runner);
        try {
            Sinks sinks = new Sinks();
            agent.setUiChangeListener(bits -> {
                assertTrue((bits & UiDirty.VIEW) != 0, "fan-out 出去的 bits 至少含 VIEW");
                sinks.hits.add("hit");
            });

            // 四个 source 各走一条最小真实 mutation：都应打到同一个 listener
            interjections.offer("插话");                                     // Interjections
            backgroundRegistry.register("explore", "d");                     // BackgroundTaskRegistry
            McpRegistry.ToggleResult r = mcpRegistry.enable("s1");          // McpRegistry（broken：结果也是变化）
            assertFalse(r.applied());
            // SubagentRunner：经 agent 绑定的 listener 必须收到 run() 进/出两次 VIEW|CONTROL——
            // 这正是「分发腿被删」时唯一会变红的断言（fix round I-2）
            long runnerVersionBefore = runner.uiVersion();
            runner.run(subSpec(), "hi", "desc", 1L);

            assertEquals(5, sinks.hits.size(),
                    "Interjections + BackgroundTaskRegistry + McpRegistry 各一次 + SubagentRunner 进/出两次");
            assertEquals(runnerVersionBefore + 2, runner.uiVersion(), "runner 真实计数路径各记账一次");
            assertEquals(0, runner.inFlightCount(), "前置：run 正常收尾");

            // 换 listener 也要 fan-out（可重设）：四源整体替换
            AtomicInteger second = new AtomicInteger();
            agent.setUiChangeListener(bits -> second.incrementAndGet());
            interjections.offer("第二条");
            backgroundRegistry.register("plan", "d2");
            runner.run(subSpec(), "again", "d2", 2L);
            assertEquals(4, second.get(), "重新绑定同样 fan-out 到四源（1 + 1 + 2）");
            assertEquals(5, sinks.hits.size(), "旧 listener 已被整体替换");
        } finally {
            mcpRegistry.close();
        }
    }

    @Test
    @DisplayName("null listener 同样 fan-out：四个 source 归一成 no-op，不抛异常")
    void nullListenerFanOutNormalizesEverywhere(@TempDir Path root) {
        Interjections interjections = new Interjections();
        BackgroundTaskRegistry backgroundRegistry = new BackgroundTaskRegistry(64);
        McpRegistry mcpRegistry = McpRegistry.initForTest(root, new StubListener(),
                List.of(server(root, "s1")), AgentTools.testEngine(root));
        // runner 也带上：null fan-out 的归一化覆盖 SubagentRunner 这条腿的真实 run() 路径
        SubagentRunner runner = realRunner();
        CodingAgent agent = fullAgent(root, interjections, backgroundRegistry, mcpRegistry, runner);
        try {
            assertDoesNotThrow(() -> agent.setUiChangeListener(null));

            // 归一后各 source 仍可正常 mutation（no-op listener 不炸、不丢状态）
            assertDoesNotThrow(() -> interjections.offer("still works"));
            assertEquals(1, interjections.pendingCount());
            assertDoesNotThrow(() -> backgroundRegistry.register("a", "d"));
            assertEquals(1, backgroundRegistry.uiVersion(), "版本照常推进");
            assertDoesNotThrow(() -> runner.run(subSpec(), "hi", "d", 1L));
            assertEquals(0, runner.inFlightCount(), "计数照常收尾（null listener 不炸收尾路径）");
            assertTrue(runner.uiVersion() >= 2, "runner 版本照常推进（进/出各一）");
        } finally {
            mcpRegistry.close();
        }
    }

    @Test
    @DisplayName("缺件（无 MCP / 无插话 / 无后台）不炸：fan-out 到在场者（含 SubagentRunner）")
    void partialSubsystemsStillFanOut(@TempDir Path root) {
        Interjections interjections = new Interjections();
        SubagentRunner runner = realRunner();
        CodingAgent agent = fullAgent(root, interjections, null, null, runner);
        Sinks sinks = new Sinks();

        assertDoesNotThrow(() -> agent.setUiChangeListener(bits -> sinks.hits.add("hit")));
        interjections.offer("只有插话");
        runner.run(subSpec(), "p", "d", 1L);
        assertEquals(3, sinks.hits.size(), "缺件时在场者照常 fan-out：Interjections 1 + runner 进/出 2");
    }

    @Test
    @DisplayName("桩路径 agent（全 null 子系统）：setUiChangeListener 为 no-op，不抛异常")
    void stubAgentAcceptsListenerWithoutExploding() {
        CodingAgent stub = new CodingAgent(null, new StubListener(), "s",
                new java.util.concurrent.atomic.AtomicLong(), null, null, null);
        assertDoesNotThrow(() -> stub.setUiChangeListener(bits -> { }));
        assertDoesNotThrow(() -> stub.setUiChangeListener(null));
    }
}

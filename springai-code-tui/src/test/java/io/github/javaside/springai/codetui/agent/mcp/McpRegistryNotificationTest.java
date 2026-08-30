package io.github.javaside.springai.codetui.agent.mcp;

import io.github.javaside.springai.codetui.agent.AgentTools;
import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpRegistry} 的变化通知契约（事件驱动 UI 的 Task 4）：
 *
 * <ul>
 *   <li>启动期 connecting 进入（init 后台连接起跑）→ VIEW；</li>
 *   <li>publishStartupResult 状态更新（无论写回成功 / close 丢弃 / 禁用丢弃）→ VIEW；</li>
 *   <li>运行期 enable / disable 的真实结果 → VIEW；</li>
 *   <li>未知 server 的 enable / disable 不通知；</li>
 *   <li>通知在 synchronized(this) 与 toggleLock <b>外</b>；</li>
 *   <li>listener 异常隔离；onMcpReady 原有回调照常恰好一次。</li>
 * </ul>
 */
class McpRegistryNotificationTest {

    private static ToolCallback fakeTool(String name) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
            }
            @Override public String call(String toolInput) { return "ok"; }
        };
    }

    private static McpConfigLoader.LoadedServer server(Path root, String name, boolean enabled) {
        return new McpConfigLoader.LoadedServer(
                new McpServerConfig.StdioServerConfig(name, enabled, Duration.ofSeconds(2),
                        "/nonexistent/definitely-not-a-real-binary-xyz", List.of(), Map.of()),
                McpConfigLoader.ConfigSource.PROJECT, root.resolve("mcp.json"));
    }

    /** 写一个含单条目的 mcp.json（enable/disable 回写断言用），返回文件路径。 */
    private static Path writeCfg(Path dir, String name, boolean enabled) throws Exception {
        Path f = dir.resolve("mcp.json");
        Files.writeString(f, "{\"mcpServers\":{\"" + name + "\":{\"command\":\"echo\",\"enabled\":" + enabled + "}}}");
        return f;
    }

    /** 立即成功的 connector（client 造不出来，用假工具代表成功）。 */
    private static Function<McpConfigLoader.LoadedServer, McpRegistry.Connected> succeed(String toolName) {
        return l -> new McpRegistry.Connected(null, List.of(fakeTool(toolName)), null);
    }

    private static void awaitIdle(McpRegistry reg) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (reg.connectingCount() == 0) {
                Thread.sleep(30);      // 让最后一次写回落定（计数递减在写回之后）
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("后台连接没有在 5s 内结束");
    }

    // ── 启动期 connecting 进入 / publishStartupResult ──

    /**
     * init 后台起跑那一刻（connecting 进入）与结果写回各发一次 VIEW。
     *
     * <p>「进入」的通知发生在 init 内部、挂监听之前——无法从外部观测，属预期；
     * 本用例用 gate 把连接卡住，先挂监听、再放行，钉死「写回那一半」的 VIEW 与时序
     * （放行前 0 条、放行后恰 +1，说明通知确实跟着写回走、不是 init 时的存量）。
     */
    @Test
    @DisplayName("写回结果 → 恰好一次 VIEW（先挂监听、后放行，无存量通知混入）")
    void startupResultPublishesExactlyOneView(@TempDir Path root) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Function<McpConfigLoader.LoadedServer, McpRegistry.Connected> gated = l -> {
            try {
                assertTrue(gate.await(5, TimeUnit.SECONDS), "闸门没被放行");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new McpRegistry.Connected(null, List.of(fakeTool("mcp__s1__t")), null);
        };
        McpRegistry reg = McpRegistry.initWithConnector(root, new StubListener(),
                List.of(server(root, "s1", true)), AgentTools.testEngine(root), gated);
        try {
            List<Integer> bits = new ArrayList<>();
            reg.setUiChangeListener(bits::add);
            long before = reg.uiVersion();

            assertEquals(1, reg.connectingCount(), "前置：连接确实在飞、结果尚未写回");
            assertEquals(0, bits.size(), "挂监听后到放行前：不得有任何通知");

            gate.countDown();
            awaitIdle(reg);

            assertEquals(List.of(UiDirty.VIEW), bits, "写回恰好一次 VIEW");
            assertEquals(before + 1, reg.uiVersion());
            assertEquals(1, reg.activeTools().size(), "前置：写回确实生效");
        } finally {
            reg.close();
        }
    }

    @Test
    @DisplayName("写回被 close 丢弃时仍发 VIEW（面板要从「连接中」翻页）")
    void discardedStartupResultStillPublishesView(@TempDir Path root) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Function<McpConfigLoader.LoadedServer, McpRegistry.Connected> gated = l -> {
            try {
                assertTrue(gate.await(5, TimeUnit.SECONDS), "闸门没被放行");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new McpRegistry.Connected(null, List.of(fakeTool("mcp__s1__t")), null);
        };
        McpRegistry reg = McpRegistry.initWithConnector(root, new StubListener(),
                List.of(server(root, "s1", true)), AgentTools.testEngine(root), gated);
        try {
            reg.close();       // 进程要退了：结果注定被丢弃
            List<Integer> bits = new ArrayList<>();
            reg.setUiChangeListener(bits::add);
            long before = reg.uiVersion();

            gate.countDown();  // 连接这才返回
            awaitIdle(reg);

            assertTrue(reg.activeTools().isEmpty(), "前置：被 close 丢弃，未写回");
            assertEquals(List.of(UiDirty.VIEW), bits,
                    "被丢弃的结果也是状态变化（CONNECTING → 离开），面板必须翻页");
            assertEquals(before + 1, reg.uiVersion());
        } finally {
            // close 已做
        }
    }

    // ── 运行期 enable / disable ──

    @Test
    @DisplayName("enable 真实结果（失败也算）→ VIEW；disable → VIEW")
    void enableAndDisablePublishView(@TempDir Path root) throws Exception {
        Path f = writeCfg(root, "s1", false);
        McpRegistry reg = McpRegistry.initForTest(root, new StubListener(),
                List.of(server(root, "s1", false)), AgentTools.testEngine(root));
        try {
            List<Integer> bits = new ArrayList<>();
            reg.setUiChangeListener(bits::add);
            long before = reg.uiVersion();

            McpRegistry.ToggleResult enabled = reg.enable("s1");   // 连接必失败，但意图已生效
            assertEquals(false, enabled.applied(), "前置：broken server 连不上");
            assertEquals(1, bits.size(), "enable 结果（失败）也是状态变化");
            assertEquals(UiDirty.VIEW, bits.get(0));
            assertEquals(before + 1, reg.uiVersion());

            bits.clear();
            McpRegistry.ToggleResult disabled = reg.disable("s1");
            assertEquals(true, disabled.applied(), "前置：disable 恒 applied");
            assertEquals(1, bits.size());
            assertEquals(UiDirty.VIEW, bits.get(0));
            assertEquals(before + 2, reg.uiVersion());
        } finally {
            reg.close();
        }
    }

    @Test
    @DisplayName("未知 server 的 enable / disable：不通知、不推进版本")
    void unknownServerTogglesStaySilent(@TempDir Path root) {
        McpRegistry reg = McpRegistry.initForTest(root, new StubListener(), List.of(),
                AgentTools.testEngine(root));
        try {
            AtomicInteger calls = new AtomicInteger();
            reg.setUiChangeListener(b -> calls.incrementAndGet());
            long before = reg.uiVersion();

            assertEquals(false, reg.enable("nope").applied());
            assertEquals(false, reg.disable("nope").applied());

            assertEquals(0, calls.get());
            assertEquals(before, reg.uiVersion());
        } finally {
            reg.close();
        }
    }

    // ── 锁外通知 ──

    @Test
    @DisplayName("通知发生在 synchronized(this) 与 toggleLock 外：listener 内起线程读 servers() 必须能完成")
    void notificationRunsOutsideInternalLocks(@TempDir Path root) throws Exception {
        Path f = writeCfg(root, "s1", true);
        McpRegistry reg = McpRegistry.initForTest(root, new StubListener(),
                List.of(server(root, "s1", false)), AgentTools.testEngine(root));
        try {
            CountDownLatch snapshotCompleted = new CountDownLatch(1);
            reg.setUiChangeListener(bits -> {
                Thread reader = new Thread(() -> {
                    reg.servers();                  // synchronized(this) 读：monitor 被占就永远进不来
                    snapshotCompleted.countDown();
                }, "mcp-lock-probe");
                reader.setDaemon(true);
                reader.start();
                try {
                    assertTrue(snapshotCompleted.await(2, TimeUnit.SECONDS),
                            "listener 在 registry 内部锁内执行（this 或 toggleLock）——UI 回读面板会死锁");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });

            McpRegistry.ToggleResult r = reg.disable("s1");   // 走 toggleLock 的完整路径

            assertNotNull(r);
        } finally {
            reg.close();
        }
    }

    // ── onMcpReady 保留 + 异常隔离 ──

    @Test
    @DisplayName("onMcpReady 原有 listener 调用保留：全连完恰好一次；UI listener 异常隔离")
    void onMcpReadySurvivesAndUiListenerIsIsolated(@TempDir Path root) throws Exception {
        AtomicInteger readyCalls = new AtomicInteger();
        AgentListener lis = new StubListener() {
            @Override public void onMcpReady(int serverCount, int toolCount) {
                readyCalls.incrementAndGet();
            }
        };
        McpRegistry reg = McpRegistry.initWithConnector(root, lis,
                List.of(server(root, "s1", true)), AgentTools.testEngine(root),
                succeed("mcp__s1__t"));
        try {
            reg.setUiChangeListener(bits -> { throw new IllegalStateException("boom"); });

            assertDoesNotThrow(() -> {
                awaitIdle(reg);
                Thread.sleep(100);     // 让 onMcpReady（最后一步）也跑完
            });

            assertEquals(1, readyCalls.get(), "onMcpReady 信息行走它，不受 UI 通知影响");
            assertTrue(reg.uiVersion() > 0, "listener 炸了也必须已记账");
            assertEquals(0, reg.connectingCount());
        } finally {
            reg.close();
        }
    }

    @Test
    @DisplayName("null listener 归一成 no-op：不抛异常")
    void nullListenerIsNormalizedToNoop(@TempDir Path root) {
        McpRegistry reg = McpRegistry.initForTest(root, new StubListener(), List.of(),
                AgentTools.testEngine(root));
        try {
            reg.setUiChangeListener(null);
            assertDoesNotThrow(reg::connectingCount);
            assertEquals(0, reg.uiVersion());
        } finally {
            reg.close();
        }
    }
}

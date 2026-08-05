package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 启动期连接改成<b>后台</b>之后的四条不变量。
 *
 * <p><b>为什么必须测</b>：改之前 {@code init} 用 join 保证「写回一定发生在任何读之前」，
 * 所以写回不加锁、也不可能与 close / disable 交错。这三条前提改完全部失效——
 * 写回与 {@code servers()}/{@code activeTools()} 真并发，而且可能发生在 {@code close()}
 * 之后（那个刚连上的 client 就没人关了，是孤儿子进程）或在用户 {@code /mcp} 禁用它之后
 * （写回等于把禁用悄悄复活）。
 *
 * <p><b>不用墙钟做断言</b>：「init 没有阻塞」是靠 latch 表达的——init 返回时连接还卡在
 * latch 上，这是状态断言，不是「跑得够快」。墙钟阈值在慢机器上会假红、在快机器上会假绿。
 *
 * <p>连接这一步走 {@code initWithConnector} 的测试接缝：{@code McpSyncClient} 构造函数包私有，
 * 造不出假的；而本次改动的风险<b>全在写回时机</b>，接缝正好对准风险。
 */
class McpRegistryBackgroundConnectTest {

    /** 只记 onMcpReady 的最小 listener（{@code ConversationState} 是 final，继承不了）。 */
    private static final class ReadyRecorder implements AgentListener {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger tools = new AtomicInteger(-1);

        @Override public void onMcpReady(int serverCount, int toolCount) {
            calls.incrementAndGet();
            tools.set(toolCount);
        }

        @Override public void onTurnStarted(long turnId) { }
        @Override public void onUserMessage(long turnId, String text) { }
        @Override public void onAssistantToken(long turnId, String token) { }
        @Override public void onToolStarted(long turnId, String toolName, String input) { }
        @Override public void onToolFinished(long turnId, String toolName, String output, boolean ok) { }
        @Override public void onSubagentStarted(long turnId, String taskId, String agentName, String description) { }
        @Override public void onSubagentFinished(long turnId, String taskId, String finalText) { }
        @Override public void onTodoUpdated(long turnId, List<String> todoLines) { }
        @Override public void onTurnComplete(long turnId) { }
        @Override public void onError(long turnId, Throwable error) { }
        @Override public void onQuestionAsked(long turnId, AskRequest request) { }
        @Override public void onCompactionStarted(String reason) { }
        @Override public void onCompactionFinished(int eventsRemoved, int tokensSaved) { }
        @Override public void onCompactionFailed(String message) { }
    }

    private static ToolCallback fakeTool(String name) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
            }
            @Override public String call(String toolInput) { return "ok"; }
        };
    }

    private static McpConfigLoader.LoadedServer server(Path root, String name) {
        return new McpConfigLoader.LoadedServer(
                new McpServerConfig.StdioServerConfig(name, true, Duration.ofSeconds(2),
                        "echo", List.of(), Map.of()),
                McpConfigLoader.ConfigSource.PROJECT, root.resolve("mcp.json"));
    }

    /** 连接卡在 latch 上，放行后返回一个带工具的成功结果（client 仍为 null——造不出真的）。 */
    private static McpRegistry.Connected blockThenSucceed(CountDownLatch gate, String toolName) {
        try {
            assertTrue(gate.await(5, TimeUnit.SECONDS), "闸门没被放行，测试自身超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new McpRegistry.Connected(null, List.of(fakeTool(toolName)), null);
    }

    @Test
    @DisplayName("init 不等连接：连接还卡着，init 已经返回，且面板显示「连接中」")
    void initDoesNotBlockOnConnect(@TempDir Path root) {
        CountDownLatch gate = new CountDownLatch(1);
        McpRegistry reg = McpRegistry.initWithConnector(root, new ConversationState(),
                List.of(server(root, "s1")), AgentTools.testEngine(root),
                l -> blockThenSucceed(gate, "mcp__s1__t"));

        // 到这里 init 已经返回，而连接还卡在 gate 上——这就是「没阻塞」的全部含义。
        assertEquals(1, reg.connectingCount(), "在飞连接数");
        assertEquals(McpRegistry.Status.CONNECTING, reg.servers().get(0).status(),
                "还在连的时候必须显示 CONNECTING，不能显示成 FAILED——那是在报一个还没发生的错");
        assertTrue(reg.activeTools().isEmpty(), "还没连上，工具当然还没有");

        gate.countDown();
    }

    @Test
    @DisplayName("连上之后：工具可见、计数归零、onMcpReady 恰好回调一次")
    void resultsBecomeVisibleAfterConnect(@TempDir Path root) throws Exception {
        ReadyRecorder state = new ReadyRecorder();
        AtomicInteger readyCalls = state.calls;
        AtomicInteger reportedTools = state.tools;
        CountDownLatch gate = new CountDownLatch(1);
        McpRegistry reg = McpRegistry.initWithConnector(root, state,
                List.of(server(root, "s1"), server(root, "s2")), AgentTools.testEngine(root),
                l -> blockThenSucceed(gate, "mcp__" + l.config().name() + "__t"));

        assertEquals(2, reg.connectingCount());
        gate.countDown();
        awaitIdle(reg);

        assertEquals(0, reg.connectingCount(), "全连完计数必须归零，否则状态栏永远挂着「连接中」");
        assertEquals(2, reg.activeTools().size(), "两家的工具都要能被下一回合快照到");
        assertEquals(1, readyCalls.get(), "onMcpReady 只发一次——每家发一次会刷屏");
        assertEquals(2, reportedTools.get());
    }

    /**
     * 孤儿守卫。close 之后才连上的结果<b>绝不能</b>写回：那个 client 已经不在 close 收集到的
     * 名单里，写回就等于留下一个没人认领的子进程（{@code mcp_smoke.py} 有一条断言盯它）。
     *
     * <p>这里断的是「没写回」这一半。另一半（把它就地关掉）需要一个真的 client 才观测得到，
     * 而它造不出来——这是本测试的已知边界，不是遗漏。
     */
    @Test
    @DisplayName("close 之后才连上的结果：一个字节都不写回")
    void resultArrivingAfterCloseIsDiscarded(@TempDir Path root) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        McpRegistry reg = McpRegistry.initWithConnector(root, new ConversationState(),
                List.of(server(root, "s1")), AgentTools.testEngine(root),
                l -> blockThenSucceed(gate, "mcp__s1__t"));

        reg.close();          // 进程要退了
        gate.countDown();     // 连接这才返回
        awaitIdle(reg);

        assertTrue(reg.activeTools().isEmpty(),
                "close 之后到达的连接结果不该被写回——写回的 client 没人会再关它");
    }

    /**
     * 「禁用被迟到写回复活」守卫。用户在连接在飞期间从 {@code /mcp} 关掉了它，
     * 迟到的写回不能把工具塞回去——那是用户明确关掉的东西自己跑回来了。
     */
    @Test
    @DisplayName("连接在飞期间被 /mcp 禁用：迟到的结果不得复活它")
    void resultForDisabledServerIsDiscarded(@TempDir Path root) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        McpRegistry reg = McpRegistry.initWithConnector(root, new ConversationState(),
                List.of(server(root, "s1")), AgentTools.testEngine(root),
                l -> blockThenSucceed(gate, "mcp__s1__t"));

        reg.disable("s1");    // 连接还卡着，用户已经把它关了
        gate.countDown();
        awaitIdle(reg);

        assertTrue(reg.activeTools().isEmpty(), "被禁用的 server 不该因为迟到的写回而复活");
        assertEquals(McpRegistry.Status.DISABLED, reg.servers().get(0).status());
        // 上面两条<b>都杀不掉「守卫被拿掉」这个变异</b>——实测过：disable 已经把 enabled 置成
        // false，而迟到写回不碰 enabled，于是工具虽然被塞回 e.tools，activeTools() 仍因
        // enabled==false 全部过滤掉，status 也照样是 DISABLED。真正露出来的是面板上的工具数：
        // 一个「已禁用」的 server 显示着 1 个工具。断这个才有鉴别力。
        assertEquals(0, reg.servers().get(0).toolCount(),
                "迟到的写回把工具塞回去了：面板会显示「已禁用」却带着工具数");
    }

    /** 等后台连接全部结束。轮询而不是 sleep 固定时长：后者在慢机器上会假红。 */
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
}

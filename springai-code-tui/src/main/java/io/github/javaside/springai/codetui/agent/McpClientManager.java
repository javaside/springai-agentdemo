package io.github.javaside.springai.codetui.agent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;

import io.github.javaside.springai.codetui.AppInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 管理一组 stdio MCP server 的生命周期：连接 → 工具发现 → 关闭。
 *
 * <p><b>失败隔离</b>：每个 server 的连接与发现各自 try/catch，一个坏 server 不影响其他
 * （不用 {@code SyncMcpToolCallbackProvider} 的聚合 flatMap——它一处失败会带崩全部，见设计文档 §2）。
 *
 * <p><b>并发安全</b>：每个 server 只建<b>一个</b>共享 {@link McpSyncClient}；stdio 传输出站串行化 +
 * JSON-RPC id 多路复用，故并行子 agent 同时调用同一 server 安全（设计文档 §2）。
 *
 * <p><b>传输无关</b>：只经 {@link McpTransportFactory} 拿抽象 {@link McpClientTransport}，
 * 连接/发现/关闭逻辑不含任何 stdio 专属分支。
 */
public final class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);
    private static final long CLOSE_BUDGET_MS = 2_000;   // 关闭总预算：绝不让清理拖慢 /exit

    private final List<McpSyncClient> clients;

    private McpClientManager(List<McpSyncClient> clients) {
        this.clients = clients;
    }

    /** 并行连接所有 server；每个各自 guard，失败跳过。返回持有已连 client 的 manager（可能 0 个）。 */
    public static McpClientManager connectAll(List<McpServerConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return new McpClientManager(List.of());
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(configs.size(), 8), r -> {
                    Thread t = new Thread(r, "mcp-connect");
                    t.setDaemon(true);   // daemon：绝不阻止 JVM 退出
                    return t;
                });
        try {
            List<CompletableFuture<McpSyncClient>> futures = new ArrayList<>();
            for (McpServerConfig cfg : configs) {
                futures.add(CompletableFuture.supplyAsync(() -> connectOne(cfg), pool));
            }
            List<McpSyncClient> connected = new ArrayList<>();
            for (CompletableFuture<McpSyncClient> f : futures) {
                McpSyncClient c = f.join();   // supplyAsync 内已 guard，join 不抛业务异常
                if (c != null) {
                    connected.add(c);
                }
            }
            return new McpClientManager(List.copyOf(connected));
        } finally {
            pool.shutdown();
        }
    }

    /** 连接结果：client 与 error 恰有一个非 null。error 为面向 UI 的失败摘要。 */
    record ConnectOutcome(McpSyncClient client, String error) { }

    /**
     * 连接单个 server：构造 transport → sync client → initialize()。任何失败 → 记 WARN、返回错误文本。
     * （带错误文本版，供 McpRegistry 的 /mcp 面板显示失败原因。）
     */
    static ConnectOutcome connectDetailed(McpServerConfig cfg) {
        McpClientTransport transport = McpTransportFactory.create(cfg).orElse(null);
        if (transport == null) {
            return new ConnectOutcome(null, "构造传输失败（详见日志）");
        }
        try {
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(cfg.timeoutMs())
                    .initializationTimeout(cfg.timeoutMs())
                    // 把 server 逻辑名塞进 client Implementation 的 title，discoverTools() 再读回作前缀。
                    .clientInfo(McpSchema.Implementation.builder("code-tui", AppInfo.version())
                            .title(cfg.name()).build())
                    .build();
            client.initialize();   // 阻塞握手，超时/失败抛异常
            log.info("MCP server '{}' 已连接。", cfg.name());
            return new ConnectOutcome(client, null);
        } catch (Exception e) {
            log.warn("MCP server '{}' 连接失败，跳过：{}", cfg.name(), e.getMessage());
            return new ConnectOutcome(null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static McpSyncClient connectOne(McpServerConfig cfg) {
        return connectDetailed(cfg).client();
    }

    /**
     * 发现单个已连 client 的工具，转成带 mcp__ 前缀的 {@link ToolCallback}（未装饰）；
     * {@code listTools} 失败记 WARN 返回空列表。
     */
    static List<ToolCallback> discoverTools(McpSyncClient client) {
        List<ToolCallback> out = new ArrayList<>();
        String server = "?";
        try {
            server = client.getClientInfo().title();   // = cfg.name()（见 connectDetailed 的 Implementation）
            for (McpSchema.Tool tool : client.listTools().tools()) {
                out.add(SyncMcpToolCallback.builder()
                        .mcpClient(client)
                        .tool(tool)
                        .prefixedToolName(prefixedName(server, tool.name()))
                        .build());
            }
        } catch (Exception e) {
            log.warn("MCP server '{}' 工具发现失败，跳过：{}", server, e.getMessage());
        }
        return out;
    }

    /**
     * 发现所有已连 server 的工具，转成带前缀的 {@link ToolCallback}（未装饰）。
     * 逐 client guard：某 server {@code listTools} 失败只丢它的工具，不影响其他。
     */
    public List<ToolCallback> toolCallbacks() {
        List<ToolCallback> out = new ArrayList<>();
        for (McpSyncClient client : clients) {
            out.addAll(discoverTools(client));
        }
        return out;
    }

    /** 关闭本 manager 持有的所有 client；语义与限制见 {@link #closeAll(Collection)}。 */
    public void close() {
        closeAll(clients);
    }

    /**
     * 关闭给定的一组 client（优雅），总时长硬限 {@value #CLOSE_BUDGET_MS}ms；绝不阻塞退出。
     *
     * <p><b>残留风险（有意取舍）</b>：本方法硬限 2s 总时长以优先保证 {@code /exit} 不卡。若某 server 的
     * {@code closeGracefully()} 在 2s 内未完成（SDK 内部最长 10s），本方法会放弃等待直接返回，其子进程
     * 可能在 JVM 退出瞬间未被优雅 destroy 而短暂残留，由 OS 在父进程消亡后回收——这是「不卡退出」优先于
     * 「保证优雅清理」的有意取舍。
     */
    static void closeAll(Collection<McpSyncClient> clients) {
        if (clients.isEmpty()) {
            return;
        }
        ExecutorService closer = Executors.newFixedThreadPool(
                Math.min(clients.size(), 8), r -> {
                    Thread t = new Thread(r, "mcp-close");
                    t.setDaemon(true);   // daemon：即便某个 closeGracefully 挂满 SDK 内部 10s，也不阻止 JVM 退出
                    return t;
                });
        try {
            for (McpSyncClient client : clients) {
                closer.submit(() -> {
                    try {
                        client.closeGracefully();
                    } catch (Exception e) {
                        log.warn("MCP client 关闭异常（忽略）：{}", e.getMessage());
                    }
                });
            }
            closer.shutdown();
            // 总预算 2s：到点就走，不等剩余在飞的 close（daemon 线程不阻止退出）。
            if (!closer.awaitTermination(CLOSE_BUDGET_MS, TimeUnit.MILLISECONDS)) {
                log.warn("MCP 关闭未在 {}ms 内完成，放弃等待（交由退出兜底）。", CLOSE_BUDGET_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closer.shutdownNow();
        }
    }

    /**
     * 工具名前缀：mcp__<server>__<tool>，段内经 McpToolUtils.format 归一
     * （strip 非法字符 + '-'→'_'），避免与内置工具/多 server 重名，并防止个别非法字符
     * 导致 provider 拒整个请求。
     *
     * <p>已知限制（本期可接受）：未做长度截断（OpenAI 64 字符上限）与跨 server 碰撞去重——
     * 本期仅 stdio、默认 DeepSeek（无 64 限制），实测工具名远短于 64 且碰撞需刻意构造；
     * 如接入更多 provider 或遇到碰撞，再引入库的 DefaultMcpToolNamePrefixGenerator。
     */
    static String prefixedName(String server, String tool) {
        return "mcp__" + sanitize(server) + "__" + sanitize(tool);
    }

    private static String sanitize(String s) {
        return org.springframework.ai.mcp.McpToolUtils.format(s);
    }
}

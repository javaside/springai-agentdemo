package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.MediaArtifactStore;
import io.github.javaside.springai.codetui.agent.media.MediaExternalizingCallback;
import io.github.javaside.springai.codetui.agent.media.TextReferenceMediaHandler;
import io.github.javaside.springai.codetui.agent.media.ToolResultMediaHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 运行期 MCP 中枢：持有<b>全量</b>条目（含 enabled:false 的），支持 /mcp 的启用/禁用即时生效 + 回写。
 * 取代裸 {@link McpClientManager} 流水线（其连接/发现/关闭静态件在此复用，不重写）。
 *
 * <p><b>装饰职责</b>：enable/初连时即用 PermissionCallback + ToolEventCallback + MediaExternalizingCallback
 * 装饰，{@link #activeTools()} 返回的始终是已装饰实例——与内置工具行为一致
 * （权限拦截 + TUI 工具活动行 + 媒体外置路径①）。
 *
 * <p><b>并发</b>：条目表用 synchronized(this) 保护；enable/disable 全程互斥于独立的 {@code toggleLock}
 * （registry 自证串行，不依赖 UI 层闸门），杜绝「enable 锁外连接在飞时 disable 插入、迟到写回把禁用复活」。
 * 连接（秒级阻塞）仍在 this 锁外做，不挡 servers()/activeTools() 读；disable 最坏被在飞 enable 挡秒级。
 * activeTools() 每回合取一次快照，回合中途切换不影响在飞回合。
 *
 * <p><b>降级契约</b>：连接失败记入 error 态（enabled 意图仍回写）；回写失败内存态照常生效、
 * ToggleResult.persisted=false 供 UI 提示；一律不抛异常。
 */
public final class McpRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpRegistry.class);

    public enum Status { CONNECTED, FAILED, DISABLED }

    /** /mcp 面板一行的数据。toolNames 为去掉 {@code mcp__<server>__} 前缀的短名。 */
    public record ServerView(String name, McpConfigLoader.ConfigSource source, Status status,
                             int toolCount, List<String> toolNames, String error) { }

    /** 切换结果：applied=内存态是否达成（enable=已连接；disable=恒 true），persisted=回写是否成功。 */
    public record ToggleResult(boolean applied, boolean persisted, String error) { }

    private static final class Entry {
        final McpConfigLoader.LoadedServer loaded;
        boolean enabled;
        McpSyncClient client;                       // null = 未连接
        List<ToolCallback> tools = List.of();       // 已装饰
        String error;                               // 最近一次连接失败摘要
        boolean connectedForTest;                   // 测试钩子：无真实 client 也视作已连接（生产恒 false）

        Entry(McpConfigLoader.LoadedServer loaded) {
            this.loaded = loaded;
            this.enabled = loaded.config().enabled();
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** enable/disable 全程互斥锁（连接阻塞期间不占 this，读方法不受挡）。锁序：toggleLock 外、this 内。 */
    private final Object toggleLock = new Object();
    private final Path root;
    private final AgentListener listener;
    private final MediaArtifactStore mediaStore;
    private final ToolResultMediaHandler mediaHandler;
    private final PermissionEngine permissionEngine;

    private McpRegistry(Path root, AgentListener listener, PermissionEngine permissionEngine) {
        this.root = root;
        this.listener = listener;
        this.mediaStore = new MediaArtifactStore(root.resolve(".codetui").resolve("artifacts"), root);
        this.mediaHandler = new TextReferenceMediaHandler();
        this.permissionEngine = Objects.requireNonNull(permissionEngine,
                "permissionEngine 不可为 null：漏传等于 MCP 工具全线无权限层");
    }

    /**
     * 生产入口：全量加载两层配置 + 并行连接 enabled 项（失败进 error 态，不抛）。
     *
     * @param permissionEngine 权限引擎，须与 {@code AgentTools.build} 用的是<b>同一个实例</b>。
     *                         故它由 {@code CodeTuiApplication} 在调用本方法<b>之前</b>建好——
     *                         MCP 工具在这里就要被装饰，等不到 build。
     */
    public static McpRegistry init(Path root, AgentListener listener, PermissionEngine permissionEngine) {
        return init(root, listener, McpConfigLoader.loadAll(root), permissionEngine);
    }

    static McpRegistry init(Path root, AgentListener listener, List<McpConfigLoader.LoadedServer> loaded,
                            PermissionEngine permissionEngine) {
        McpRegistry reg = new McpRegistry(root, listener, permissionEngine);
        for (McpConfigLoader.LoadedServer l : loaded) {
            reg.entries.put(l.config().name(), new Entry(l));
        }
        reg.connectEnabledInParallel();
        return reg;
    }

    /** 测试入口：不做启动期连接（条目按 enabled=false 或经 addConnectedForTest 塞假工具驱动）。 */
    static McpRegistry initForTest(Path root, AgentListener listener,
                                   List<McpConfigLoader.LoadedServer> loaded,
                                   PermissionEngine permissionEngine) {
        McpRegistry reg = new McpRegistry(root, listener, permissionEngine);
        for (McpConfigLoader.LoadedServer l : loaded) {
            reg.entries.put(l.config().name(), new Entry(l));
        }
        return reg;
    }

    /** 测试入口（向后兼容）：自建一个隔离的默认引擎，见 {@link AgentTools#testEngine}。 */
    static McpRegistry initForTest(Path root, AgentListener listener,
                                   List<McpConfigLoader.LoadedServer> loaded) {
        return initForTest(root, listener, loaded, AgentTools.testEngine(root));
    }

    /** 测试钩子：把条目直接置为「已启用、已连接、给定工具」（client 仍 null，close 时自然跳过）。 */
    synchronized void addConnectedForTest(String name, List<ToolCallback> decoratedTools) {
        Entry e = Objects.requireNonNull(entries.get(name), name);
        e.enabled = true;
        e.error = null;
        e.tools = List.copyOf(decoratedTools);
        e.connectedForTest = true;
    }

    /** 启动期并行连接（沿 McpClientManager.connectAll 的池模式；此时无并发访问，不加锁）。 */
    private void connectEnabledInParallel() {
        List<Entry> toConnect = entries.values().stream().filter(e -> e.enabled).toList();
        if (toConnect.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(toConnect.size(), 8), r -> {
            Thread t = new Thread(r, "mcp-connect");
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Entry e : toConnect) {
                futures.add(CompletableFuture.runAsync(() -> connectAndDiscover(e), pool));
            }
            futures.forEach(CompletableFuture::join);   // connectDetailed 内已 guard，不抛业务异常
        } finally {
            pool.shutdown();
        }
    }

    /** 连接三元组：成功时 client 非 null、error 为 null；失败时反之，tools 恒空。 */
    private record Connected(McpSyncClient client, List<ToolCallback> tools, String error) { }

    /** 连接 + 发现 + 装饰单条目（不写回 entry——写回策略由调用方决定）。 */
    private Connected connect(Entry e) {
        McpClientManager.ConnectOutcome out = McpClientManager.connectDetailed(e.loaded.config());
        if (out.client() == null) {
            return new Connected(null, List.of(), out.error());
        }
        List<ToolCallback> decorated = new ArrayList<>();
        for (ToolCallback raw : McpClientManager.discoverTools(out.client())) {
            decorated.add(decorate(raw));
        }
        return new Connected(out.client(), List.copyOf(decorated), null);
    }

    /** 启动期写回策略：直写 entry（此时无并发访问，不加锁；enabled 已为 true）。 */
    private void connectAndDiscover(Entry e) {
        Connected c = connect(e);
        e.client = c.client();
        e.tools = c.tools();
        e.error = c.error();
    }

    /**
     * 包私供测试断言装饰链（最外层 {@link PermissionCallback}）。
     *
     * <p>运行期 {@code /mcp} 启用的 server 也走这里——故权限层<b>不能</b>改成「装配期一次性包装」，
     * 那会让启动后新启用的 server 漏掉整层。
     */
    ToolCallback decorate(ToolCallback raw) {
        return new PermissionCallback(
                new ToolEventCallback(
                        new MediaExternalizingCallback(raw, mediaStore, mediaHandler, root), listener),
                permissionEngine, listener);
    }

    /** /mcp 面板数据源（快照）。 */
    public synchronized List<ServerView> servers() {
        List<ServerView> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            String name = e.loaded.config().name();
            Status status = !e.enabled ? Status.DISABLED
                    : (e.client != null || e.connectedForTest) ? Status.CONNECTED : Status.FAILED;
            // 本 entry 的注册名前缀（与 McpClientManager.prefixedName 同款 sanitize），按已知前缀精确剥离。
            String prefix = "mcp__" + org.springframework.ai.mcp.McpToolUtils.format(name) + "__";
            List<String> shortNames = e.tools.stream()
                    .map(t -> shortName(prefix, t.getToolDefinition().name())).toList();
            out.add(new ServerView(name, e.loaded.source(), status,
                    e.tools.size(), shortNames, e.error));
        }
        return out;
    }

    /** 当前「已启用且已连接」server 的已装饰工具（每回合快照）。 */
    public synchronized List<ToolCallback> activeTools() {
        List<ToolCallback> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.enabled) {
                out.addAll(e.tools);
            }
        }
        return out;
    }

    /**
     * 启用：连接（阻塞，秒级——调用方放后台线程）+ 发现 + 装饰 + 回写 enabled:true。
     * 连接失败也回写（用户意图是启用，下次启动自动重试）；已连接则幂等返回成功。
     */
    public ToggleResult enable(String name) {
        synchronized (toggleLock) {
            Entry e;
            synchronized (this) {
                e = entries.get(name);
                if (e == null) {
                    return new ToggleResult(false, false, "未知 server：" + name);
                }
                if (e.enabled && (e.client != null || e.connectedForTest)) {
                    return new ToggleResult(true, true, null);
                }
            }
            connectAndPublish(e);
            boolean persisted = McpConfigWriter.setEnabled(e.loaded.file(), name, true);
            synchronized (this) {
                return new ToggleResult(e.client != null, persisted, e.error);
            }
        }
    }

    /** 运行期写回策略：连接在 this 锁外做（阻塞秒级），仅结果发布时短暂持锁（调用方持 toggleLock）。 */
    private void connectAndPublish(Entry e) {
        Connected c = connect(e);
        synchronized (this) {
            e.enabled = true;
            e.client = c.client();
            e.tools = c.tools();
            e.error = c.error();
        }
    }

    /** 禁用：摘除工具（下回合快照即不含）+ 后台优雅关连接 + 回写 enabled:false。即时完成
     *（最坏被在飞 enable 的 toggleLock 挡秒级；UI 单飞契约成立时永不发生）。 */
    public ToggleResult disable(String name) {
        synchronized (toggleLock) {
            McpSyncClient toClose;
            Entry e;
            synchronized (this) {
                e = entries.get(name);
                if (e == null) {
                    return new ToggleResult(false, false, "未知 server：" + name);
                }
                toClose = e.client;
                e.client = null;
                e.tools = List.of();
                e.enabled = false;
                e.error = null;
                e.connectedForTest = false;
            }
            if (toClose != null) {
                Thread t = new Thread(() -> {
                    try {
                        toClose.closeGracefully();
                    } catch (Exception ex) {
                        log.warn("MCP client 关闭异常（忽略）：{}", ex.getMessage());
                    }
                }, "mcp-disable-close");
                t.setDaemon(true);
                t.start();
            }
            boolean persisted = McpConfigWriter.setEnabled(e.loaded.file(), name, false);
            return new ToggleResult(true, persisted, null);
        }
    }

    /** 退出清理：关所有在连 client（复用 2s 预算逻辑）。 */
    public void close() {
        List<McpSyncClient> toClose;
        synchronized (this) {
            toClose = entries.values().stream()
                    .map(e -> e.client).filter(Objects::nonNull).toList();
        }
        McpClientManager.closeAll(toClose);
    }

    /** 按本 server 的已知前缀 {@code mcp__<server>__} 剥出短名（含 {@code __} 的工具名不被过度剥离）；不匹配则原样。 */
    static String shortName(String prefix, String registered) {
        return registered.startsWith(prefix) ? registered.substring(prefix.length()) : registered;
    }
}

package io.github.javaside.springai.codetui.agent.mcp;

import io.github.javaside.springai.codetui.agent.AgentListener;
import io.github.javaside.springai.codetui.agent.AgentTools;
import io.github.javaside.springai.codetui.agent.PermissionCallback;
import io.github.javaside.springai.codetui.agent.ToolEventCallback;
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

    public enum Status { CONNECTED, FAILED, DISABLED, CONNECTING }

    /** /mcp 面板一行的数据。
     *
     * @param name      server 逻辑名
     * @param source    配置来源层（用户级 / 项目级）
     * @param status    当前状态；判定顺序见 {@code servers()}
     * @param toolCount 该 server 发现的工具数
     * @param toolNames 去掉 {@code mcp__<server>__} 前缀的短名
     * @param error     失败原因；正常时为 null
     */
    public record ServerView(String name, McpConfigLoader.ConfigSource source, Status status,
                             int toolCount, List<String> toolNames, String error) { }

    /** 切换结果。
     *
     * @param applied   内存态是否达成（enable = 已连接；disable = 恒 true）
     * @param persisted 回写 mcp.json 是否成功
     * @param error     失败原因；成功时为 null
     */
    public record ToggleResult(boolean applied, boolean persisted, String error) { }

    private static final class Entry {
        final McpConfigLoader.LoadedServer loaded;
        boolean enabled;
        McpSyncClient client;                       // null = 未连接
        List<ToolCallback> tools = List.of();       // 已装饰
        String error;                               // 最近一次连接失败摘要
        boolean connectedForTest;                   // 测试钩子：无真实 client 也视作已连接（生产恒 false）
        boolean connecting;                         // 启动期后台连接在飞（决定 /mcp 面板显示「连接中」而非「失败」）

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

    /**
     * 已 {@link #close()}。启动期后台连接是<b>唯一</b>能在 close 之后仍拿到一个活 client 的路径——
     * 那个 client 若照常写回 entry，就成了没人认领的孤儿子进程（{@code mcp_smoke.py} 有一条断言盯它）。
     * 故写回时在锁内查这个位，为真就当场关掉刚连上的 client、不写回。
     */
    private volatile boolean closed;

    /** 启动期后台连接池；{@link #close()} 要 shutdownNow 它，否则退出要等最慢的那个 server。 */
    private volatile ExecutorService startupPool;

    /** 启动期还在连的条目数（供状态栏「⟳ MCP 连接中 N」）。归零时回调一次 onMcpReady。 */
    private final java.util.concurrent.atomic.AtomicInteger connecting =
            new java.util.concurrent.atomic.AtomicInteger();

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

    /** 测试入口：不做启动期连接（条目按 enabled=false 或经 addConnectedForTest 塞假工具驱动）。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static McpRegistry initForTest(Path root, AgentListener listener,
                                   List<McpConfigLoader.LoadedServer> loaded,
                                   PermissionEngine permissionEngine) {
        McpRegistry reg = new McpRegistry(root, listener, permissionEngine);
        for (McpConfigLoader.LoadedServer l : loaded) {
            reg.entries.put(l.config().name(), new Entry(l));
        }
        return reg;
    }

    /** 测试入口（向后兼容）：自建一个隔离的默认引擎，见 {@link AgentTools#testEngine}。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static McpRegistry initForTest(Path root, AgentListener listener,
                                   List<McpConfigLoader.LoadedServer> loaded) {
        return initForTest(root, listener, loaded, AgentTools.testEngine(root));
    }

    /** 测试钩子：把条目直接置为「已启用、已连接、给定工具」（client 仍 null，close 时自然跳过）。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public synchronized void addConnectedForTest(String name, List<ToolCallback> decoratedTools) {
        Entry e = Objects.requireNonNull(entries.get(name), name);
        e.enabled = true;
        e.error = null;
        e.tools = List.copyOf(decoratedTools);
        e.connectedForTest = true;
    }

    /**
     * 启动期并行连接，<b>不等它连完</b>——{@code init} 立即返回，TUI 立刻渲染。
     *
     * <p><b>为什么可以不等</b>：{@code CodingAgent.submit} 每回合都重新快照 {@link #activeTools()}，
     * 工具晚到几秒只影响这几秒内发出的回合，不影响之后任何一回合。而等它连完的代价是实测的：
     * 一个远程 HTTP server（context7）就要 5~8 秒，那几秒里屏幕是空的。
     *
     * <p><b>此处再也不能说「无并发访问，不加锁」了</b>——原来的写法靠 join 保证写回发生在
     * 任何读之前，现在写回与 {@code servers()}/{@code activeTools()} 真并发。写回走
     * {@link #publishStartupResult}，在 this 锁内做；连接本身留在锁外（秒级，锁住会挡死读）。
     */
    private void connectEnabledInParallel() {
        List<Entry> toConnect = entries.values().stream().filter(e -> e.enabled).toList();
        if (toConnect.isEmpty()) {
            return;
        }
        for (Entry e : toConnect) {
            e.connecting = true;
        }
        connecting.set(toConnect.size());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(toConnect.size(), 8), r -> {
            Thread t = new Thread(r, "mcp-connect");
            t.setDaemon(true);
            return t;
        });
        startupPool = pool;
        for (Entry e : toConnect) {
            CompletableFuture.runAsync(() -> connectAndDiscover(e), pool);   // 刻意不 join
        }
        pool.shutdown();   // 不再接新任务；已提交的照跑（close 时才 shutdownNow）
    }

    /** 启动期还在连的 server 数（状态栏用）。全连完/未配置 MCP 时为 0。 */
    public int connectingCount() {
        return Math.max(0, connecting.get());
    }

    /** 连接三元组：成功时 client 非 null、error 为 null；失败时反之，tools 恒空。 */
    record Connected(McpSyncClient client, List<ToolCallback> tools, String error) { }

    /**
     * 测试接缝：替换掉「真的去连一个 server」这一步。
     *
     * <p>没有它就测不了后台连接的任何一条分支——{@code McpSyncClient} 是个构造函数包私有的实体类，
     * 造不出假的；而这次改动的风险<b>全在写回时机</b>（close 竞态、in-flight 期间被 disable），
     * 不在连接本身。接缝让测试能把连接卡在半路，正好对准风险。
     */
    private volatile java.util.function.Function<McpConfigLoader.LoadedServer, Connected> connector;

    /** 测试入口：后台连接照常起，但「连接」这一步走给定的 connector。 */
    static McpRegistry initWithConnector(Path root, AgentListener listener,
                                         List<McpConfigLoader.LoadedServer> loaded,
                                         PermissionEngine permissionEngine,
                                         java.util.function.Function<McpConfigLoader.LoadedServer, Connected> connector) {
        McpRegistry reg = new McpRegistry(root, listener, permissionEngine);
        for (McpConfigLoader.LoadedServer l : loaded) {
            reg.entries.put(l.config().name(), new Entry(l));
        }
        reg.connector = connector;
        reg.connectEnabledInParallel();
        return reg;
    }

    /** 连接 + 发现 + 装饰单条目（不写回 entry——写回策略由调用方决定）。 */
    private Connected connect(Entry e) {
        java.util.function.Function<McpConfigLoader.LoadedServer, Connected> stub = connector;
        if (stub != null) {
            return stub.apply(e.loaded);
        }
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

    /** 启动期后台连接的任务体：连接在锁外，写回走 {@link #publishStartupResult}。 */
    private void connectAndDiscover(Entry e) {
        Connected c;
        try {
            c = connect(e);
        } catch (RuntimeException ex) {
            // connectDetailed 内已 guard，正常不会到这里；到了也不能让异常吞掉计数递减，
            // 否则状态栏会永远挂着「⟳ MCP 连接中 N」。
            log.warn("MCP 连接意外抛出（按失败处理）：{}", ex.toString());
            c = new Connected(null, List.of(), ex.toString());
        }
        publishStartupResult(e, c);
    }

    /**
     * 启动期连接结果的发布点。<b>两道丢弃检查都在锁内做</b>：
     *
     * <ul>
     *   <li><b>已 close</b>：进程要退了，这个刚连上的 client 没人会再关它——当场关掉，别写回。
     *       不查这一下就是漏孤儿子进程。</li>
     *   <li><b>已被 /mcp 禁用</b>：用户在连接在飞期间把它关了，写回等于把禁用悄悄复活
     *       （与 {@code enable/disable} 用 toggleLock 防的是同一件事，这里靠「写回时复查意图」达成，
     *       不必让启动连接去争 toggleLock——那会让 /mcp 面板操作卡上好几秒）。</li>
     * </ul>
     *
     * <p>无论走哪条路，{@code connecting} 计数都要递减：状态栏和 onMcpReady 都靠它。
     */
    private void publishStartupResult(Entry e, Connected c) {
        McpSyncClient orphan = null;
        synchronized (this) {
            e.connecting = false;
            if (closed || !e.enabled) {
                orphan = c.client();
            } else {
                e.client = c.client();
                e.tools = c.tools();
                e.error = c.error();
            }
        }
        if (orphan != null) {
            closeQuietly(orphan);
        }
        if (connecting.decrementAndGet() == 0 && !closed) {
            int tools;
            int servers;
            synchronized (this) {
                tools = entries.values().stream().filter(x -> x.enabled).mapToInt(x -> x.tools.size()).sum();
                servers = (int) entries.values().stream().filter(x -> x.enabled && x.client != null).count();
            }
            listener.onMcpReady(servers, tools);
        }
    }

    /** 关一个没人认领的 client。失败只记 WARN——这条路本身就是收尾，再抛没有任何人能处理。 */
    private static void closeQuietly(McpSyncClient client) {
        try {
            client.closeGracefully();
        } catch (Exception ex) {
            log.warn("MCP client 关闭异常（忽略）：{}", ex.getMessage());
        }
    }

    /**
     * 本 registry 装饰 MCP 工具时用的权限引擎。
     *
     * <p>供 {@code AgentTools.build} 的向后兼容重载取用——让「内置工具与 MCP 工具共用同一个引擎」
     * 由类型保证，而不是靠调用方记得传对同一个对象。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public PermissionEngine permissionEngine() {
        return permissionEngine;
    }

    /**
     * 包私供测试断言装饰链（最外层 {@link PermissionCallback}）。
     *
     * <p>运行期 {@code /mcp} 启用的 server 也走这里——故权限层<b>不能</b>改成「装配期一次性包装」，
     * 那会让启动后新启用的 server 漏掉整层。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public ToolCallback decorate(ToolCallback raw) {
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
            // 顺序要紧：connecting 必须排在 FAILED 之前判。启动头几秒里 client 还是 null，
            // 照老写法会一律显示成「连接失败」——那是在报一个还没发生的错。
            Status status = !e.enabled ? Status.DISABLED
                    : (e.client != null || e.connectedForTest) ? Status.CONNECTED
                    : e.connecting ? Status.CONNECTING : Status.FAILED;
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

    /**
     * 退出清理：关所有在连 client（复用 2s 预算逻辑）。
     *
     * <p><b>{@code closed} 必须先置位、再收集</b>：置位之后，任何还在飞的启动期连接在
     * {@link #publishStartupResult} 里都会看到它，把自己刚连上的 client 就地关掉。
     * 反过来（先收集再置位）会留下一个窗口：那一瞬间连上的 client 既不在收集到的名单里、
     * 又因为 closed 还没置位而被写进了 entry——没人再关它，就是孤儿子进程。
     *
     * <p>{@code shutdownNow} 掐掉尚未开跑的连接任务，否则退出要陪最慢的那个 server 等满超时。
     */
    public void close() {
        closed = true;
        ExecutorService pool = startupPool;
        if (pool != null) {
            pool.shutdownNow();
        }
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

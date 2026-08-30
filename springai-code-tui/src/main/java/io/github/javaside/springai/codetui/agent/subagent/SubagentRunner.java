package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.RetryingChatModel;
import io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry;
import io.github.javaside.springai.codetui.agent.mcp.McpRegistry;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.prompt.PermissionModePrompt;
import io.github.javaside.springai.codetui.agent.tools.ToolEventCallback;
import io.github.javaside.springai.codetui.ui.update.UiChangeListener;
import io.github.javaside.springai.codetui.ui.update.UiChangeSource;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * provider 中立的子 agent 执行器。前台串行：在主 agent 的 Task 工具调用内同步执行，
 * 子 agent 内部工具活动经带 taskId 的工具事件实时上报（见 {@link ToolEventCallback}）。
 *
 * <p>用激活 provider 的 chatModel 建子 agent 专用 ChatClient（过滤后工具 + system=spec.systemPrompt），
 * <b>不挂</b> SessionMemory advisor——子 agent 上下文独立。model 空→跟随激活 provider。
 *
 * <p><b>工具调用（Spring AI 2.0）</b>：2.0 已把工具执行循环从 ChatModel 内部搬进 advisor 链，且
 * {@code ChatClient.builder(model)} 会<b>自动注册</b> {@code ToolCallingAdvisor}（带 observation 的
 * {@code ToolCallingManager}），故这里<b>不再</b>显式挂 {@code ToolCallAdvisor}（该类 2.0 起 deprecated 且待删除；
 * 显式挂反而会抑制自动注册、丢掉工具调用可观测性）。与主 agent（{@code AgentTools}）一致：只 {@code defaultTools}，
 * 工具循环交给自动注册的 advisor。
 *
 * <p><b>变化通知纪律（事件驱动 UI，Task 4）</b>：本类同时是 {@link UiChangeSource}——
 * <ul>
 *   <li>前台 {@link #inFlight} 计数每次<b>真实增 / 减</b> → 恰好一次 {@code VIEW|CONTROL}
 *       （busy 闸门要重估「取消后是否还有旧子 agent 未清」）；</li>
 *   <li>后台 {@link #backgroundInFlight} 计数变化只发 {@code VIEW}——<b>绝不</b>含 CONTROL
 *       （后台任务不进 busy 闸门，这正是两个计数分开的全部理由，混了等于后台化失效）；</li>
 *   <li>通知在<b>原子计数变化之后</b>、任何业务锁<b>外</b>调用（本类没有业务监视器，listener 也不得
 *       反向持有计数语义），且必须是 increment 后紧邻的 <b>try 块首语句</b>——listener 抛出的
 *       {@link RuntimeException} 被隔离成日志；即便抛 {@link Error}，计数递减也在同一 try 的
 *       finally 里，不会泄漏打断 busy 闸门的生命线；</li>
 *   <li>计数本身不经过 listener 路径变更：listener 只是「计数变了」的回声。</li>
 * </ul>
 */
public final class SubagentRunner implements UiChangeSource {

    private static final Logger log = LoggerFactory.getLogger(SubagentRunner.class);

    private final ProviderRegistry registry;
    private final List<ToolCallback> tools;   // 已被 ToolEventCallback 装饰、带 root 边界的主 agent 工具列表
    private final AgentListener listener;
    private final String projectInstructions;   // AGENTS.md 项目指令；追加到每个子 agent 的 spec 系统提示（可空）
    private final Supplier<String> taskIdSupplier;
    /** 批量 runAll 的并发上限（同时在飞的子 agent 数）。默认 4；装配层可传入自定义值。 */
    private final int maxConcurrency;
    /** 可空：MCP 工具的每次委派实时来源（enable/disable 即时反映到下一次委派的工具集）。 */
    private final McpRegistry mcpRegistry;
    /**
     * 当前权限模式的<b>实时</b>来源（同 mcpRegistry 的性质：读的是运行期状态，不是装配期快照）。
     *
     * <p><b>必须是 Supplier 而不是一个 PermissionMode 值</b>：模式随 {@code Shift+Tab} 运行期变化，
     * 而 SubagentRunner 是装配期建一次、之后长期存活的。存值就等于把启动那一刻的模式烘焙进去，
     * 切档后给子 agent 的提示会是错的——比没有提示更坏（它会理直气壮地按错误前提行动）。
     * 每次派发都经 {@link #effectiveSystemPrompt} 现取现算。
     */
    private final Supplier<PermissionMode> modeSupplier;

    /** 当前在飞的<b>前台</b>子 agent 数（串行 run + 并行 runAll）。供 UI busy 闸门判断「取消后是否还有旧子 agent 未清」。 */
    private final AtomicInteger inFlight = new AtomicInteger();
    /**
     * 当前在飞的<b>后台</b>子 agent 数。<b>刻意与前台分开计</b>：
     * 混在一起的话，只要有后台任务在跑，{@code hasInFlightSubagents()} 就会挡住新回合——
     * 后台化等于没做（这正是变异测试要钉的那一条）。本计数只供面板显示与退出清理。
     */
    private final AtomicInteger backgroundInFlight = new AtomicInteger();
    /**
     * 按 parentTurnId 索引的并行线程池集合：一个回合可发多次 ParallelTasks，故每 turn 是一组池。
     * 取消（{@link #cancelTurn}）据此 {@code shutdownNow} 拆掉该回合所有在飞并行子 agent（best-effort，见 runAll 中断语义）。
     */
    private final Map<Long, Set<ExecutorService>> poolsByTurn = new ConcurrentHashMap<>();

    /**
     * 后台任务注册表；null 表示未启用后台模式（老测试与回显桩不受影响）。
     *
     * <p><b>volatile</b>：这两个字段是「后台模式是否可用」的判据，写在装配线程
     * （{@link #enableBackground}）与 UI 线程（{@link #restartBackground}）、读在任意工具调用线程。
     * 实践上装配早于一切派发、有 happens-before 兜着，但把「可用性判据」的可见性押在时序巧合上
     * 没有任何好处，而标上成本为零。
     */
    private volatile BackgroundTaskRegistry backgroundRegistry;
    /** 后台常驻线程池；与回合级临时池<b>完全分开</b>——回合取消 shutdownNow 临时池时碰不到它。 */
    private volatile ThreadPoolExecutor backgroundPool;
    /** 建池参数，{@link #restartBackground} 重建时复用（只在 enableBackground 里写一次）。 */
    private volatile int backgroundConcurrency;
    private volatile int backgroundQueueCapacity;
    /**
     * 后台模式是否已<b>永久</b>关闭（{@code /exit}）。与 {@link #restartBackground}（换一个新池）截然不同：
     * 关闭之后新派发要被<b>明确</b>拒绝，且连登记都不做——不登记就不会留下停在 RUNNING 的幽灵条目。
     */
    private volatile boolean backgroundClosed;
    /** 线程序号跨重建递增：重建后若从 1 重来，jstack 里会出现两个 subagent-background-1，排查时分不清哪个池。 */
    private final AtomicLong backgroundThreadSeq = new AtomicLong();

    // ── 变化通知（事件驱动 UI；见类注释「变化通知纪律」） ──────────────────
    private volatile UiChangeListener uiChangeListener = UiChangeListener.noop();

    /** 单调递增的状态版本。仅诊断用，不参与任何跨 source 比较。 */
    private final AtomicLong uiVersion = new AtomicLong();

    /** 记录一次有效变化并推进版本（与原子计数变更同序：先变更、后记账、再通知）。 */
    private long changed() {
        return uiVersion.incrementAndGet();
    }

    /**
     * 在原子计数变化<b>之后</b>发布通知。listener 异常只记日志——调用方是子 agent 工具 / 池线程，
     * 异常回传会打断 try/finally 的计数收尾（那正是 busy 闸门防挂死的生命线）。
     */
    private void publish(long version) {
        if (version <= 0) return;
        try {
            uiChangeListener.onUiChanged(UiDirty.VIEW | UiDirty.CONTROL);
        } catch (RuntimeException e) {
            log.warn("UI change listener failed at subagent-runner version {}", version, e);
        }
    }

    /** 后台计数专用发布：只发 VIEW（后台任务绝不进 busy 闸门，见类注释）。 */
    private void publishBackground(long version) {
        if (version <= 0) return;
        try {
            uiChangeListener.onUiChanged(UiDirty.VIEW);
        } catch (RuntimeException e) {
            log.warn("UI change listener failed at subagent-runner version {}", version, e);
        }
    }

    @Override
    public void setUiChangeListener(UiChangeListener listener) {
        uiChangeListener = listener == null ? UiChangeListener.noop() : listener;
    }

    @Override
    public long uiVersion() {
        return uiVersion.get();
    }

    public SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                          String projectInstructions) {
        this(registry, tools, listener, projectInstructions, 4);
    }

    public SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                          String projectInstructions, int maxConcurrency) {
        this(registry, tools, listener, projectInstructions, maxConcurrency,
                () -> "task_" + UUID.randomUUID(), null);
    }

    public SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                          String projectInstructions, int maxConcurrency, McpRegistry mcpRegistry) {
        this(registry, tools, listener, projectInstructions, maxConcurrency,
                () -> "task_" + UUID.randomUUID(), mcpRegistry);
    }

    /**
     * 生产装配用：额外接一个权限模式的实时来源（传 {@code permissionEngine::mode}）。
     *
     * <p>其余重载一律以 {@code () -> null} 兜底 —— 等价「无模式信息」，
     * {@link PermissionModePrompt#forSubagent} 对 null 返回空串，故老调用方行为零变化。
     */
    public SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                          String projectInstructions, int maxConcurrency, McpRegistry mcpRegistry,
                          Supplier<PermissionMode> modeSupplier) {
        this(registry, tools, listener, projectInstructions, maxConcurrency,
                () -> "task_" + UUID.randomUUID(), mcpRegistry, modeSupplier);
    }

    SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                   String projectInstructions, int maxConcurrency, Supplier<String> taskIdSupplier) {
        this(registry, tools, listener, projectInstructions, maxConcurrency, taskIdSupplier, null);
    }

    SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                   String projectInstructions, int maxConcurrency, Supplier<String> taskIdSupplier,
                   McpRegistry mcpRegistry) {
        this(registry, tools, listener, projectInstructions, maxConcurrency, taskIdSupplier,
                mcpRegistry, () -> null);
    }

    SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                   String projectInstructions, int maxConcurrency, Supplier<String> taskIdSupplier,
                   McpRegistry mcpRegistry, Supplier<PermissionMode> modeSupplier) {
        this.registry = registry;
        this.tools = tools;
        this.listener = listener;
        this.projectInstructions = projectInstructions == null ? "" : projectInstructions;
        this.taskIdSupplier = taskIdSupplier;
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.mcpRegistry = mcpRegistry;
        this.modeSupplier = modeSupplier == null ? () -> null : modeSupplier;
    }

    /** 执行一次委派，返回子 agent 最终文本。parentTurnId=发起 Task 的回合。 */
    public String run(SubagentSpec spec, String prompt, String description, long parentTurnId) {
        String taskId = taskIdSupplier.get();
        listener.onSubagentStarted(parentTurnId, taskId, spec.name(), description);
        // increment 必须是 try 前的最后一条语句、publish 必须是 try 内的首语句——否则 onSubagentStarted 抛出
        // 会漏掉 finally 的递减，计数永久泄漏、busy 闸门永久卡死、UI 再也无法提交。publish 只隔 RuntimeException，
        // 放 try 外的话 listener 抛 Error（SOE/OOM/NoClassDefFoundError）同样会漏减（错误在这里不是「不该发生」，
        // 而是发生后 finally 必须仍然跑——这正是 try 存在的意义）。故 increment 紧贴 onSubagentStarted 之后、
        // 通知挪进 try 作为首语句（通知仍在计数变化之后，语义不变）。
        inFlight.incrementAndGet();   // 进入在飞（finally 递减）——喂给 UI busy 闸门，取消后仍未清的旧子 agent 会挡住 /continue
        try {
            publish(changed());       // 计数变化之后、锁外；异常隔离（不打断下面的执行与 finally 收尾）
            // 子 agent 内部工具事件带上 parentTurnId + taskId（供 TUI 缩进）
            String finalText = execute(spec, prompt,
                    Map.of(ToolEventCallback.TURN_ID_KEY, parentTurnId,
                           ToolEventCallback.TASK_ID_KEY, taskId));
            listener.onSubagentFinished(parentTurnId, taskId, finalText, true);
            return finalText;
        } catch (RuntimeException ex) {
            // 摊平 cause 链——SDK 常把根因（如 Jackson 的 end-of-input）包在笼统的
            // "Error reading response" 里，只取顶层 message 会丢掉唯一有诊断价值的信息。
            // 重抛换成带摊平文本的 SubagentFailedException：工具异常处理器回给模型的就是 getMessage()，
            // 原样重抛模型只能看到笼统顶层文本。cause 保留原异常，日志有全栈。
            log.error("子 agent 执行失败：spec={} taskId={} description={}", spec.name(), taskId, description, ex);
            String detail = describe(ex);
            listener.onSubagentFinished(parentTurnId, taskId, "子 agent 执行失败：" + detail, false);
            throw new SubagentFailedException(detail, ex);
        } finally {
            inFlight.decrementAndGet();   // 无论成功/失败/被中断（shutdownNow → interrupt → 网络调用抛出）都退出在飞
            publish(changed());           // 锁外发布：闸门解除必须让 UI 看到，否则 /continue 永久排队
        }
    }

    /**
     * 前台与后台共用的执行体：建子 agent 专用 ChatClient 并跑一次完整的工具循环。
     *
     * <p>抽出来的唯一目的是让前台/后台<b>只在 toolContext 与事件上报上不同</b>，
     * 模型、工具集、系统提示、重试策略全部同源——否则两条路会各自漂移，
     * 而「后台任务的行为和前台不一样」是最难排查的那类缺陷。
     *
     * <p>Spring AI 2.0：defaultTools 取代已废弃的 defaultToolCallbacks；工具调用 advisor 由 ChatClient
     * 自动注册，不再显式挂（见类注释）。传 Object[]（每个元素是 ToolCallback）——与主 agent 的
     * defaultTools(toolsWithTask) 同构。RetryingChatModel：子 agent 走阻塞 call()，代理网关会间歇性回
     * 200+空 body（SDK 抛 *InvalidDataException、自带重试不覆盖），在 ChatModel 层按 LLM call 粒度重试。
     */
    private String execute(SubagentSpec spec, String prompt, Map<String, Object> toolContext) {
        ProviderRegistry.RequestSelection selection = resolveSelection(spec);
        ChatClient client = ChatClient.builder(RetryingChatModel.wrap(selection.provider().chatModel()))
                .defaultTools(effectiveTools(spec).toArray())
                .build();
        ChatOptions options = selection.options();
        String result = client.prompt()
                .system(effectiveSystemPrompt(spec))
                .user(prompt)
                // .options 接收 native builder（与 CodingAgent.submit 一致，mutate 保留 maxTokens 等）
                .options(options.mutate())
                .toolContext(toolContext)
                .call()
                .content();
        return result == null ? "" : result;
    }

    /**
     * 批量并发执行多个子 agent，join 全部后按<b>入参顺序</b>返回各自结果。
     *
     * <p>失败隔离：单个子 agent 抛错不影响其他，该位置返回「失败：{@code <msg>}」文本（子 agent 内 run() 已 emit
     * onSubagentFinished(ok=false)）。turnId <b>显式</b>传入每个任务闭包——绝不在子线程读 ThreadLocal
     * （否则 turnId 丢失、UI 事件被迟到过滤器丢弃）。
     *
     * <p>线程池为<b>回合级局部</b>：容量 min(N, maxConcurrency)，join 后 shutdownNow 立即回收，无常驻线程。
     *
     * <p><b>中断（取消）语义</b>：被中断时立即 shutdownNow 并返回/抛出，<b>不</b> awaitTermination——
     * 保证调用方（回合取消）快速回到 IDLE。在飞子 agent 可能因底层网络阻塞不响应 interrupt 而继续跑完，
     * 其迟到事件由 ConversationState 的 turnId 迟到过滤器丢弃（best-effort 取消，取消可靠性的深入验证见 Task 5）。
     */
    public List<String> runAll(List<Dispatch> dispatches, long parentTurnId) {
        int n = dispatches.size();
        if (n == 0) {
            return new ArrayList<>();
        }
        AtomicLong seq = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, maxConcurrency), r -> {
            Thread t = new Thread(r, "subagent-parallel-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        // 注册进该 turn 的池集合：回合取消（Esc）时 cancelTurn 会对它 shutdownNow，拆掉在飞并行子 agent。
        Set<ExecutorService> turnPools = poolsByTurn.computeIfAbsent(parentTurnId, k -> ConcurrentHashMap.newKeySet());
        turnPools.add(pool);
        try {
            List<Callable<String>> tasks = new ArrayList<>(n);
            for (Dispatch d : dispatches) {
                tasks.add(() -> {
                    try {
                        return run(d.spec(), d.prompt(), d.description(), parentTurnId);
                    } catch (RuntimeException ex) {
                        // run() 已包成 SubagentFailedException（message=摊平文本），直接取 message 即可，
                        // 再 describe 会把摊平文本和原 cause 链重复拼接。
                        return "失败：" + ex.getMessage();
                    }
                });
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            List<String> results = new ArrayList<>(n);
            for (Future<String> f : futures) {
                try {
                    results.add(f.get());
                } catch (Exception ex) {
                    results.add("失败：" + describe(ex));
                }
            }
            return results;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("并行子任务被中断", ie);
        } finally {
            pool.shutdownNow();
            // 原子摘除本池；该 turn 再无池则连 key 一起清（避免每个用过 ParallelTasks 的回合都残留一个空集合）。
            poolsByTurn.computeIfPresent(parentTurnId, (k, set) -> {
                set.remove(pool);
                return set.isEmpty() ? null : set;
            });
        }
    }

    /**
     * 取消某回合的所有在飞并行子 agent：对该 turn 名下每个线程池 {@code shutdownNow}（中断工作线程）。
     * <b>立即返回、不 awaitTermination</b>——保证调用方（Esc 回合取消）快速回 IDLE，与 runAll 的中断语义一致。
     * 被中断的子 agent 的迟到会话/UI 写入由 {@code CodingAgent} 的出站净化与 {@code ConversationState} 的 turnId
     * 过滤兜底。未知 turn（无在飞并行任务）为静默无操作。串行 run() 无池、无法强制打断，靠出站净化 + busy 闸门兜底。
     */
    public void cancelTurn(long parentTurnId) {
        Set<ExecutorService> turnPools = poolsByTurn.get(parentTurnId);
        if (turnPools == null) {
            return;
        }
        for (ExecutorService pool : turnPools) {
            pool.shutdownNow();
        }
    }

    /** 默认后台等待队列容量。 */
    public static final int DEFAULT_BACKGROUND_QUEUE = 16;

    /** 启用后台模式（默认队列 16）。装配层调用；不调则 {@link #runInBackground} 返回不可用提示。 */
    public void enableBackground(BackgroundTaskRegistry registry, int concurrency) {
        enableBackground(registry, concurrency, DEFAULT_BACKGROUND_QUEUE);
    }

    /** 启用后台模式，显式指定并发与队列容量（测试用）。 */
    public void enableBackground(BackgroundTaskRegistry registry, int concurrency, int queueCapacity) {
        this.backgroundRegistry = registry;
        this.backgroundConcurrency = Math.min(32, Math.max(1, concurrency));
        this.backgroundQueueCapacity = Math.max(1, queueCapacity);
        this.backgroundClosed = false;
        this.backgroundPool = newBackgroundPool();
    }

    /** 按已记下的并发/队列参数造一个全新的后台池。{@link #enableBackground} 与 {@link #restartBackground} 共用。 */
    private ThreadPoolExecutor newBackgroundPool() {
        int n = backgroundConcurrency;
        return new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(backgroundQueueCapacity),
                r -> {
                    Thread t = new Thread(r, "subagent-background-" + backgroundThreadSeq.incrementAndGet());
                    t.setDaemon(true);   // 守护线程：绝不阻止 JVM 退出（退出清理是有界的，见 shutdownBackground）
                    return t;
                });
    }

    /**
     * 重启后台池：真打断在跑的子 agent 线程，然后<b>立刻换上一个全新的池</b>。{@code /clear} 走这条。
     *
     * <p><b>为什么不能只 shutdownNow 完事</b>：{@code ThreadPoolExecutor} 一旦 shutdown 就是终态、
     * 永不可复用，而 {@link #enableBackground} 全仓只在装配期调一次。只关不建 = 这个进程此后
     * <b>每一次</b>后台派发都永久落进 rejected 分支，而队列其实是空的、池是死的——模型读到「等在跑的
     * 任务完成后重试」就会一直等下去。这正是 {@code /clear} 与 {@code /exit} 必须分成两个方法的全部理由。
     *
     * <p><b>先换新池、再拆旧池</b>：反过来的话，这两步之间并发进来的派发会打在已死的池上。
     * 不 awaitTermination：本方法在 UI 线程上跑（{@code /clear} 按键），绝不为清理卡住界面；
     * 被打断的旧任务的迟到写入由注册表的 KILLED 状态与 ConversationState 的守卫兜底。
     *
     * <p>从未启用过后台模式时为静默无操作——凭空造一个池只会让「后台模式不可用」这条判据失真。
     */
    public void restartBackground() {
        ThreadPoolExecutor old = backgroundPool;
        if (old == null) {
            return;
        }
        backgroundClosed = false;   // 覆盖 /exit 已置位的极端顺序：能重建就说明后台模式又可用了
        backgroundPool = newBackgroundPool();
        old.shutdownNow();
    }

    /**
     * 后台派发：<b>立刻</b>返回一段含 taskId 的文本，子 agent 在常驻池里跑。
     *
     * <p>返回的文本<b>就是本次工具调用的结果</b>——所以不会留下悬空 {@code tool_calls}，
     * 「回合被中断后残留 assistant(tool_calls)、下一轮 400」那个已知坑在这条路上不存在。
     *
     * <p><b>绝不抛异常</b>：后台任务的失败是它自己的事，不该炸掉主 agent 正在进行的回合。
     */
    public String runInBackground(SubagentSpec spec, String prompt, String description) {
        // 快照池引用：下面判断拒绝理由时必须问的是「刚才 execute 到的那个池」，
        // 再读一次 volatile 可能已经被 restartBackground 换成新池，理由就说反了。
        ThreadPoolExecutor pool = backgroundPool;
        if (backgroundRegistry == null || pool == null) {
            return "后台模式不可用（未装配后台注册表）。请改用 run_in_background=false 的前台 Task。";
        }
        if (backgroundClosed) {
            // 提前返回，连 register 都不做——不登记就不会在 ⏱ 面板上留下一条停在 RUNNING 的幽灵。
            return "后台模式已关闭，不再受理新的后台任务，本次未启动。"
                    + "请改用 run_in_background=false 的前台 Task。";
        }
        String taskId = backgroundRegistry.register(spec.name(), description);
        listener.onBackgroundTaskStarted(taskId, spec.name(), description);
        // 与前台 run() 同一条纪律：increment 是 try 前的最后一条语句、publishBackground 是 try 内的首语句。
        // 同步窗口（increment → execute 成功）内的任何异常——包括 listener 抛的 Error（publishBackground
        // 只隔 RuntimeException）——都由下面的 finally 收尾递减，backgroundInFlight 才不会永久挂 1；
        // execute 成功后计数所有权移交 runBackgroundBody 的 finally（handedOff 必须紧贴 execute 置位，
        // 晚一步的话，其后任何异常都会被错当成「未派发」而双重递减）。
        backgroundInFlight.incrementAndGet();
        boolean handedOff = false;
        try {
            publishBackground(changed());   // 只发 VIEW：后台计数不进 busy 闸门（见类注释）
            pool.execute(() -> runBackgroundBody(spec, prompt, taskId));
            handedOff = true;               // 已交给池线程：本方法不得再动这个计数
            log.info("后台子 agent 已提交：spec={} taskId={} description={}",
                    spec.name(), taskId, description);
        } catch (RejectedExecutionException rejected) {
            // 标记 KILLED 而不是 FAILED：KILLED 不可送达，绝不会被自动送给模型。
            // 送一条「它失败了」给模型，读起来像它真的跑过——而它根本没启动。
            backgroundRegistry.kill(taskId);
            // 「池已关」与「队列满」是两件完全不同的事，绝不能共用一句话：队列满是「等会儿再来」，
            // 池已关是「这个进程不再受理了」。说反了，模型就会去等一个永远不会来的空位。
            String reason = pool.isShutdown()
                    ? "后台线程池已关闭（后台模式已停用，或刚被重启），本次未启动。"
                            + "请改用 run_in_background=false 的前台 Task。"
                    : "后台队列已满（并发上限已占满且等待队列已满），本次未启动。"
                            + "请改用 run_in_background=false 的前台 Task，或等待在跑的任务完成后重试。";
            // 必须补一次结束事件：上面已经发过 onBackgroundTaskStarted，不补这一下，UI 镜像里那条
            // BackgroundEntry 就永远停在 RUNNING——⏱ 面板的耗时涨到天荒地老、状态栏永久挂
            // 「⏱ N 个后台任务」，而用户按 k 想终止它时注册表里那条已是 KILLED，只会回「终止失败」。
            // ok=false（UI 记 FAILED）与注册表的 KILLED 刻意不一致：前者是给人看的「这条没跑成」，
            // 后者是给模型的「不可送达」，两个受众要的语义本就不同。
            listener.onBackgroundTaskFinished(taskId, reason, false);
            return reason;   // 返回前 finally 先收尾递减 + 通知（⏱ 面板把这条幽灵刷掉）
        } finally {
            if (!handedOff) {
                // 被拒 / listener 抛 Error / execute 抛其他异常：同步窗口内收回计数并通知。
                // 递减在最前——即便这次通知再抛 Error，计数也已经收回。
                backgroundInFlight.decrementAndGet();
                publishBackground(changed());
            }
        }
        return "已在后台启动：" + taskId + "（" + spec.name() + " · " + description + "）。"
                + "用 TaskOutput 取结果，或等待完成通知。";
    }

    /** 后台任务体：跑完把结果写回注册表并发事件。<b>任何异常都不得逃出本方法</b>（池线程死掉没人知道）。 */
    private void runBackgroundBody(SubagentSpec spec, String prompt, String taskId) {
        long startedAt = System.nanoTime();
        log.info("后台子 agent 开始执行：spec={} taskId={}", spec.name(), taskId);
        try {
            // turnId 传 -1：后台任务没有归属回合，塞一个真 turnId 只会让它的工具事件被迟到过滤丢弃。
            String finalText = execute(spec, prompt,
                    Map.of(ToolEventCallback.TURN_ID_KEY, -1L,
                           ToolEventCallback.TASK_ID_KEY, taskId,
                           ToolEventCallback.BACKGROUND_TASK_ID_KEY, taskId));
            backgroundRegistry.complete(taskId, finalText, true);
            listener.onBackgroundTaskFinished(taskId, finalText, true);
            log.info("后台子 agent 执行完成：spec={} taskId={} elapsedMs={}",
                    spec.name(), taskId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        } catch (RuntimeException ex) {
            log.error("后台子 agent 执行失败：spec={} taskId={}", spec.name(), taskId, ex);
            String detail = describe(ex);
            backgroundRegistry.complete(taskId, detail, false);
            listener.onBackgroundTaskFinished(taskId, detail, false);
        } catch (Throwable fatal) {
            // Error 也要兜：漏掉它，池线程静默死亡，任务永远停在 RUNNING，面板上转到天荒地老。
            log.error("后台子 agent 遇到致命错误：taskId={}", taskId, fatal);
            backgroundRegistry.complete(taskId, String.valueOf(fatal), false);
            listener.onBackgroundTaskFinished(taskId, String.valueOf(fatal), false);
        } finally {
            backgroundInFlight.decrementAndGet();
            publishBackground(changed());   // 只发 VIEW：后台收尾不进 busy 闸门
        }
    }

    /**
     * <b>永久</b>关掉后台模式并<b>有界</b>关闭后台池（硬限 2s），照抄 MCP 子进程清理的「不卡退出优先」取舍。
     *
     * <p><b>关了就回不来了</b>——{@code ThreadPoolExecutor} 一旦 shutdown 就是终态。凡是之后还要继续
     * 派后台任务的场景（{@code /clear} 换新会话就是），一律走 {@link #restartBackground}，
     * 理由见那里。目前生产路径上 {@code /exit} 与 {@code /clear} 共用
     * {@code killAllBackgroundTasks} → restart，本方法留给「把退出拆出独立关池语义」那步（后续项）
     * 与需要确定性收尾的测试。
     */
    public void shutdownBackground() {
        // 先置位再关池：此后的新派发被明确拒绝且不再登记，不会在退出途中冒出幽灵任务。
        backgroundClosed = true;
        if (backgroundPool == null) return;
        backgroundPool.shutdownNow();
        try {
            backgroundPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 当前在飞子 agent 总数（串行 + 并行）。供 UI busy 闸门判断取消后是否仍有旧子 agent 未清。 */
    public int inFlightCount() {
        return inFlight.get();
    }

    /** 当前在飞的后台子 agent 数（只供面板与退出清理，<b>绝不</b>进 busy 闸门）。 */
    public int backgroundInFlightCount() {
        return backgroundInFlight.get();
    }

    /**
     * 产出图片时把 artifact 路径写进报告。
     *
     * <p><b>为什么只是一句提示、不做机制</b>：子 agent 截的图与主 agent 的图落在同一个
     * artifacts 目录，但回到主 agent 的<b>只有它的最终文本报告</b>。报告里不写路径，
     * 主 agent 就无从得知这些图存在——而要让它知道，就得给子 agent 契约加一条「结构化产物清单」，
     * 那是比这个问题本身大得多的改动。一句提示能覆盖绝大多数情况，成本一行。
     *
     * <p><b>无条件追加，不按视觉能力开关</b>：即便当前模型不支持看图、或
     * {@code CODETUI_VISION=off}，「主 agent 不知道这些文件存在」这个问题依然成立
     * （它至少该知道有图、路径在哪）。按能力开关只会让同一次派发的提示随环境漂移，
     * 换来的收益是省一句话。
     */
    private static final String ARTIFACT_GUIDANCE = """
            若你在调查中产生了图片（截图、图表等），请把它们的 artifact 路径写进最终报告，
            否则主 agent 无从得知这些图存在。
            """;

    /**
     * 子 agent 有效系统提示：spec 自身提示 + 项目指令（非空时追加）+ 权限模式提示段（仅 PLAN 非空）
     * + 产物路径提示（{@link #ARTIFACT_GUIDANCE}，恒追加）。
     *
     * <p><b>每次派发都调一次</b>（见 {@code run} 里的 {@code .system(effectiveSystemPrompt(spec))}），
     * 故模式提示读的是<b>当下</b>的模式，{@code Shift+Tab} 切档在下一次委派即生效，没有装配期烘焙。
     *
     * <p><b>产物提示加在这里而不是四份内置 agent 的 md 里</b>：写进 md 要抄四份，四份还会各自漂移；
     * 这里是所有派发的必经之路，一处即可。将来若支持用户自定义 agent
     * （今天没有：{@code SubagentLoader.loadBuiltins} 只读 4 个 classpath 内置 md，
     * 全仓没有任何代码扫描 {@code .codetui/agents/}），它们也会自动带上这段，无需再改。
     *
     * <p><b>纯字符串拼接，不走模板参数</b>：这里注入的是最终 system 文本，调用方
     * {@code .system(String)} 不带任何 param，故 Spring 的模板引擎不会去解析它——
     * 正文里就算有花括号也炸不了（项目在 AGENTS.md 与长期记忆两处踩过的是<b>带 param</b> 的那条路）。
     * 即便如此，{@link PermissionModePrompt} 仍有单测钉死「正文无花括号」，双保险。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public String effectiveSystemPrompt(SubagentSpec spec) {
        StringBuilder sb = new StringBuilder(spec.systemPrompt());
        if (!projectInstructions.isEmpty()) {
            sb.append("\n\n").append(projectInstructions);
        }
        String modeGuidance = PermissionModePrompt.forSubagent(modeSupplier.get());
        if (!modeGuidance.isEmpty()) {
            sb.append("\n\n").append(modeGuidance);
        }
        sb.append("\n\n").append(ARTIFACT_GUIDANCE);
        return sb.toString();
    }

    /**
     * 摊平异常 cause 链为一行诊断文本：{@code Msg ← CauseType: causeMsg ← ...}（去重相邻重复 message，
     * 封顶 5 层防环）。给回主 agent 的失败文本用——SDK 顶层 message 往往是笼统的
     * "Error reading response"，真正根因（Jackson end-of-input、连接被重置等）在 cause 里。纯函数，便于单测。
     */
    static String describe(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        String prev = null;
        Throwable t = ex;
        for (int depth = 0; t != null && depth < 5; t = t.getCause(), depth++) {
            String msg = t.getMessage() == null || t.getMessage().isBlank()
                    ? t.getClass().getSimpleName()
                    : t.getMessage();
            if (msg.equals(prev)) {
                continue;   // 相邻 wrapper 复读同一 message（如 CompletionException）——跳过不重复
            }
            if (sb.length() > 0) {
                sb.append(" ← ").append(t.getClass().getSimpleName()).append(": ");
            }
            sb.append(msg);
            prev = msg;
        }
        return sb.toString();
    }

    /** 测试钩子：子 agent 可见工具（未经 spec 过滤）的注册名——校验主 agent 独有工具（如记忆工具）不泄漏给子 agent。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public List<String> toolNamesForTest() {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    /** model 空→激活 selection；否则在当前 v1 provider 路由下解析显式模型 selection。 */
    private ProviderRegistry.RequestSelection resolveSelection(SubagentSpec spec) {
        if (spec.model() == null || spec.model().isBlank()) {
            return registry.activeRequestSelection();
        }
        // provider:model 的跨家路由留待 v2（spec §12）；v1 先在激活 provider 上按模型名覆盖。
        String modelId = spec.model().contains(":")
                ? spec.model().substring(spec.model().indexOf(':') + 1)
                : spec.model();
        return registry.requestSelection(modelId);
    }

    /** 子 agent 有效工具 = 内置装饰工具 + MCP 实时工具（registry 快照），再按 spec allow/deny 过滤（注册名精确匹配）。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public List<ToolCallback> effectiveTools(SubagentSpec spec) {
        List<ToolCallback> all = new ArrayList<>(tools);
        if (mcpRegistry != null) {
            all.addAll(mcpRegistry.activeTools());
        }
        return filterTools(all, spec);
    }

    /** 按 allow（空=全部）过滤、再按 deny 剔除。按真实注册名精确匹配。 */
    static List<ToolCallback> filterTools(List<ToolCallback> all, SubagentSpec spec) {
        List<ToolCallback> result = new ArrayList<>();
        for (ToolCallback t : all) {
            String name = t.getToolDefinition().name();
            boolean allowed = spec.allowTools().isEmpty() || spec.allowTools().contains(name);
            boolean denied = spec.denyTools().contains(name);
            if (allowed && !denied) {
                result.add(t);
            }
        }
        return result;
    }

    /** 一次批量委派中的单个子任务：路由后的 spec + 该子任务的 prompt/description。
     *
     * @param spec        已按 subagent_type 解析出的子 agent 定义
     * @param prompt      该子任务的自包含任务提示
     * @param description 3-5 词简述，进 UI 任务面板
     */
    public record Dispatch(SubagentSpec spec, String prompt, String description) {
    }
}

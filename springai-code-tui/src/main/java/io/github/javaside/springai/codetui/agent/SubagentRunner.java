package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 */
public final class SubagentRunner {

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

    /** 当前在飞的子 agent 总数（串行 run + 并行 runAll 都计）；供 UI 的 busy 闸门判断「取消后是否还有旧子 agent 未清」。 */
    private final AtomicInteger inFlight = new AtomicInteger();
    /**
     * 按 parentTurnId 索引的并行线程池集合：一个回合可发多次 ParallelTasks，故每 turn 是一组池。
     * 取消（{@link #cancelTurn}）据此 {@code shutdownNow} 拆掉该回合所有在飞并行子 agent（best-effort，见 runAll 中断语义）。
     */
    private final Map<Long, Set<ExecutorService>> poolsByTurn = new ConcurrentHashMap<>();

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
        // 增计数须紧贴 try（其间不得有可抛异常的语句）——否则 onSubagentStarted 抛出会漏掉 finally 的递减，
        // 计数永久泄漏、busy 闸门永久卡死、UI 再也无法提交。故放在 onSubagentStarted 之后、try 之前。
        inFlight.incrementAndGet();   // 进入在飞（finally 递减）——喂给 UI busy 闸门，取消后仍未清的旧子 agent 会挡住 /continue
        try {
            // Spring AI 2.0：defaultTools 取代已废弃的 defaultToolCallbacks；工具调用 advisor 由 ChatClient 自动注册，
            // 不再显式挂（见类注释）。传 Object[]（每个元素是 ToolCallback）——与主 agent 的 defaultTools(toolsWithTask) 同构。
            // RetryingChatModel：子 agent 走阻塞 call()，代理网关会间歇性回 200+空 body（SDK 抛
            // *InvalidDataException、自带重试不覆盖），在 ChatModel 层按 LLM call 粒度重试（见该类注释）。
            ChatClient client = ChatClient.builder(RetryingChatModel.wrap(registry.active().chatModel()))
                    .defaultTools(effectiveTools(spec).toArray())
                    .build();
            ChatOptions options = resolveOptions(spec);
            String result = client.prompt()
                    .system(effectiveSystemPrompt(spec))
                    .user(prompt)
                    // .options 接收 native builder（与 CodingAgent.submit 一致，mutate 保留 maxTokens 等）
                    .options(options.mutate())
                    // 子 agent 内部工具事件带上 parentTurnId + taskId（供 TUI 缩进）
                    .toolContext(Map.of(ToolEventCallback.TURN_ID_KEY, parentTurnId,
                            ToolEventCallback.TASK_ID_KEY, taskId))
                    .call()
                    .content();
            String finalText = result == null ? "" : result;
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
        }
    }

    /**
     * 批量并发执行多个子 agent，join 全部后按<b>入参顺序</b>返回各自结果。
     *
     * <p>失败隔离：单个子 agent 抛错不影响其他，该位置返回「失败：<msg>」文本（子 agent 内 run() 已 emit
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

    /** 当前在飞子 agent 总数（串行 + 并行）。供 UI busy 闸门判断取消后是否仍有旧子 agent 未清。 */
    public int inFlightCount() {
        return inFlight.get();
    }

    /**
     * 子 agent 有效系统提示：spec 自身提示 + 项目指令（非空时追加）+ 权限模式提示段（仅 PLAN 非空）。
     *
     * <p><b>每次派发都调一次</b>（见 {@code run} 里的 {@code .system(effectiveSystemPrompt(spec))}），
     * 故模式提示读的是<b>当下</b>的模式，{@code Shift+Tab} 切档在下一次委派即生效，没有装配期烘焙。
     *
     * <p><b>纯字符串拼接，不走模板参数</b>：这里注入的是最终 system 文本，调用方
     * {@code .system(String)} 不带任何 param，故 Spring 的模板引擎不会去解析它——
     * 正文里就算有花括号也炸不了（项目在 AGENTS.md 与长期记忆两处踩过的是<b>带 param</b> 的那条路）。
     * 即便如此，{@link PermissionModePrompt} 仍有单测钉死「正文无花括号」，双保险。
     */
    String effectiveSystemPrompt(SubagentSpec spec) {
        StringBuilder sb = new StringBuilder(spec.systemPrompt());
        if (!projectInstructions.isEmpty()) {
            sb.append("\n\n").append(projectInstructions);
        }
        String modeGuidance = PermissionModePrompt.forSubagent(modeSupplier.get());
        if (!modeGuidance.isEmpty()) {
            sb.append("\n\n").append(modeGuidance);
        }
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

    /** 测试钩子：子 agent 可见工具（未经 spec 过滤）的注册名——校验主 agent 独有工具（如记忆工具）不泄漏给子 agent。 */
    List<String> toolNamesForTest() {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    /** model 空→激活 provider 默认（activeChatOptions）；否则用 spec.model 覆盖（走激活 provider 的 options）。 */
    private ChatOptions resolveOptions(SubagentSpec spec) {
        if (spec.model() == null || spec.model().isBlank()) {
            return registry.activeChatOptions();
        }
        // provider:model 的跨家路由留待 v2（spec §12）；v1 先在激活 provider 上按模型名覆盖。
        String modelId = spec.model().contains(":")
                ? spec.model().substring(spec.model().indexOf(':') + 1)
                : spec.model();
        return registry.active().options(modelId);
    }

    /** 子 agent 有效工具 = 内置装饰工具 + MCP 实时工具（registry 快照），再按 spec allow/deny 过滤（注册名精确匹配）。 */
    List<ToolCallback> effectiveTools(SubagentSpec spec) {
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

    /** 一次批量委派中的单个子任务：路由后的 spec + 该子任务的 prompt/description。 */
    public record Dispatch(SubagentSpec spec, String prompt, String description) {
    }
}

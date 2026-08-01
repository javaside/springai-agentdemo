package io.github.javaside.springai.codetui.agent;

import org.springaicommunity.agent.utils.AgentEnvironment;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import reactor.core.Disposable;
import reactor.core.Disposables;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import io.github.javaside.springai.codetui.agent.media.MediaExternalizingCallback;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.media.SessionFileExternalizer;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CodingAgent —— 编码 Agent 核心。把一次用户提交翻译成一轮流式对话，
 * 并把 Spring AI 的响应流「翻译」成纯 Java 的 {@link AgentListener} 事件。
 *
 * <p><b>接缝纪律</b>：{@link ChatClientResponse} 等 Spring AI 类型<b>不得</b>泄漏进
 * {@link AgentListener}——{@code handleChunk/handleError/handleComplete} 都是本类私有，
 * 只把抽取出的纯文本 / Throwable 交给 listener。
 *
 * <p><b>turnId 归属</b>：{@link #activeTurnId} 由本类拥有（每次 submit 自增），
 * <b>不</b>再与 AgentTools 共享；工具 / Todo 事件的 turnId 走 ToolContext / ThreadLocal（见 Task 5/6）。
 *
 * <p><b>取消竞态</b>：{@link #submit} 顶部<b>同步</b>调用 {@code onTurnStarted}（先锁定 UI 的
 * acceptingTurnId）再 subscribe，确保 dispose 时不会漏掉「回合已开始」的信号。
 */
public final class CodingAgent implements SubmitHandler {

    /** 仅 registry==null 的旧单-client/测试桩回退路径用；真实模型清单走 ProviderRegistry（支持 *_MODELS 配置）。 */
    public static final List<ModelOption> MODELS = List.of(
            new ModelOption("deepseek-v4-flash", "deepseek-v4-flash", "非思考 · 快 · 便宜"),
            new ModelOption("deepseek-v4-pro",   "deepseek-v4-pro",   "强推理 · 1.6T · 更慢更贵"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;
    private final ProviderRegistry registry;                       // 可空：多 provider 路径；null 走旧单-client 路径
    private final java.util.Map<String, ChatClient> clientsByProvider;   // 可空：按 provider id 取 ChatClient
    private final AgentListener listener;
    private volatile String sessionId;   // /clear 换新会话时原地替换；submit 里 advisor param 每回合实时读取
    private final AtomicLong activeTurnId;
    private final SessionService sessionService;
    private final CompactionStrategy manualStrategy;
    private final TokenCountEstimator tokenCountEstimator;   // 与压缩共用，供 /context 估算会话 token
    private final AtomicBoolean compactionInFlight = new AtomicBoolean(false);   // 防止并发/重复触发手动压缩
    private final List<SkillInfo> skills;   // 固定技能清单（测试桩用）；生产走 reloadableSkill 实时取
    private final ToolCallback skillTool;   // 被 ToolEventCallback 装饰的 Skill 工具（可空）；手动 /skill 发送前调用它
    private final ReloadableSkillTool reloadableSkill;   // 可空：生产路径的可重载技能源（/reload 命令触发重扫）
    private final SessionRepository sessionRepository;   // 可空：取消回合时回滚会话到回合前快照（见 submit 的 doOnCancel）
    private final SubagentRunner subagentRunner;   // 可空（测试桩）：取消回合时 shutdownNow 在飞并行子 agent + 供 busy 闸门查在飞数
    private final SessionFileExternalizer fileExternalizer;   // 可空（测试桩）：路径②，submit 开头把过往大文本 tool 结果外置为引用
    private final McpRegistry mcpRegistry;   // 可空：MCP 运行期中枢；submit 每回合 .tools(activeTools) 快照注入 + /mcp 门面委托
    /** 可空（测试桩）：权限引擎，与三处装配点里的 PermissionCallback 共用同一实例；门面（模式/规则）直通它。 */
    private final PermissionEngine permissionEngine;
    private volatile String model = MODELS.get(0).id();   // 运行时可经 /model 切换，对后续回合生效

    /** 无技能清单的构造（回显桩/测试桩用）：等价于技能为空。 */
    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator) {
        this(chatClient, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, List.of());
    }

    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills) {
        this(chatClient, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, null);
    }

    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills, ToolCallback skillTool) {
        this(chatClient, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, null);
    }

    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills, ToolCallback skillTool,
                       SessionRepository sessionRepository) {
        this.chatClient = chatClient;
        this.registry = null;
        this.clientsByProvider = null;
        this.listener = listener;
        this.sessionId = sessionId;
        this.activeTurnId = activeTurnId;
        this.sessionService = sessionService;
        this.manualStrategy = manualStrategy;
        this.tokenCountEstimator = tokenCountEstimator;
        this.skills = List.copyOf(skills);
        this.skillTool = skillTool;
        this.reloadableSkill = null;   // 单-client 桩路径：无可重载源，skills() 用固定 skills、reloadSkills() 无操作
        this.sessionRepository = sessionRepository;
        this.subagentRunner = null;    // 单-client 桩路径：无子 agent 执行器
        this.fileExternalizer = null;  // 单-client 桩路径：无路径②外置器
        this.mcpRegistry = null;       // 单-client 桩路径：无 MCP 支持
        this.permissionEngine = null;  // 单-client 桩路径：无权限引擎（门面退回默认值，见 permissionMode）
    }

    /**
     * 多 provider 生产构造：registry 决定激活 provider 与模型，clientsByProvider 提供各家 ChatClient。
     * submit 按激活 provider 选 ChatClient + 用该家 options 覆盖模型；/model 走 registry 跨家。
     *
     * @param reloadableSkill 可重载技能源（{@code /reload} 触发重扫）；亦作 {@code skills()} 的实时数据源。可空。
     */
    /** 向后兼容重载（无子 agent 执行器：测试桩用）。等价 subagentRunner=null（取消不拆并行池、busy 闸门恒不含在飞子 agent）。 */
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill) {
        this(registry, clientsByProvider, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, sessionRepository, reloadableSkill, null);
    }

    /** 向后兼容重载（无路径②外置器：测试桩用）。等价 fileExternalizer=null（submit 开头 no-op）。 */
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill, SubagentRunner subagentRunner) {
        this(registry, clientsByProvider, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, sessionRepository, reloadableSkill, subagentRunner, null);
    }

    /** 向后兼容重载（无 MCP 支持：测试桩用）。等价 mcpRegistry=null（不注入 MCP 工具、/mcp 门面返回空/null）。 */
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill, SubagentRunner subagentRunner,
                       SessionFileExternalizer fileExternalizer) {
        this(registry, clientsByProvider, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, sessionRepository, reloadableSkill, subagentRunner,
                fileExternalizer, null);
    }

    /** 向后兼容重载（无权限引擎：测试桩用）。等价 permissionEngine=null（权限门面退回默认值）。 */
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill, SubagentRunner subagentRunner,
                       SessionFileExternalizer fileExternalizer, McpRegistry mcpRegistry) {
        this(registry, clientsByProvider, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, sessionRepository, reloadableSkill, subagentRunner,
                fileExternalizer, mcpRegistry, null);
    }

    /**
     * 多 provider 生产构造（全参）：{@code mcpRegistry} 运行期 MCP 中枢，submit 每回合快照注入其 activeTools（可空）；
     * {@code permissionEngine} 权限引擎，须是 {@code AgentRuntime.permissionEngine()} 那一个——
     * 门面（Shift+Tab 切模式 / {@code /permissions} 列规则）改的必须正是工具那一侧读的那个对象（可空，桩路径用）。
     */
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill, SubagentRunner subagentRunner,
                       SessionFileExternalizer fileExternalizer, McpRegistry mcpRegistry,
                       PermissionEngine permissionEngine) {
        this.chatClient = null;
        this.registry = registry;
        this.clientsByProvider = clientsByProvider;
        this.listener = listener;
        this.sessionId = sessionId;
        this.activeTurnId = activeTurnId;
        this.sessionService = sessionService;
        this.manualStrategy = manualStrategy;
        this.tokenCountEstimator = tokenCountEstimator;
        this.skills = List.copyOf(skills);
        this.skillTool = skillTool;
        this.reloadableSkill = reloadableSkill;
        this.sessionRepository = sessionRepository;
        this.subagentRunner = subagentRunner;
        this.fileExternalizer = fileExternalizer;
        this.mcpRegistry = mcpRegistry;
        this.permissionEngine = permissionEngine;
    }

    @Override
    public Disposable submit(String text) {
        return submit(text, null);
    }

    @Override
    public Disposable submit(String text, String skillName) {
        long turnId = activeTurnId.incrementAndGet();
        // 出站净化（层①）：发请求前先把会话裁到合法前缀。上一回合若被取消、且有迟到的子 agent 写入漏进会话
        // （留下悬空 assistant(tool_calls) 或孤儿 tool 结果），只有 doOnCancel/handleError/磁盘加载三处净化，
        // 活进程会话常驻内存永不重载 → 坏数据会一直发出去、每条请求都 400。这里在每个回合出站前再净化一次兜底，
        // 使 /continue 等后续请求自愈；会话本就干净时为 no-op（sanitize 返回同引用、不写回）。
        trimDanglingToolCalls();
        externalizeSessionFiles();   // 路径②：回合间把过往文件全文换引用（搭 sanitize 那趟）
        listener.onTurnStarted(turnId);        // 同步：先锁定 acceptingTurnId，消除取消竞态
        listener.onUserMessage(turnId, text);  // UI 展示用户原文（注入只影响发给模型的文本）
        String effectiveText = injectSkill(text, skillName, turnId);
        // 出站净化（层①补丁）：若历史尾部残留一条 user（gpt 回合 after() 抛 No session ID 致 assistant 未落盘，
        // 见 SessionMemoryAdvisor 与 SessionEvents.collapseConsecutiveSameRole），advisor.before 会在其后再追加本回合
        // 新 user → 两条连续 user → DeepSeek 400（模型都没跑，出站 sanitize 与流式守卫都够不到）。此处把尾部残留 user
        // 折进出站消息、并从会话删除，断开该 400 循环。sanitize 已把任何尾部连续 user 折成一条，故最多折一次即净。
        effectiveText = foldTrailingUserIntoOutbound(sessionId, effectiveText);
        // 中断（Esc 取消 / 报错）时只裁掉「悬空尾巴」——末尾带 tool_calls 却无配对 tool 结果的消息——
        // 而保留已完成任务与计划（见 trimDanglingToolCalls）。否则中途取消会留下「带 tool_calls 无结果」的
        // 悬空 assistant，下一次请求发给 DeepSeek 会 400（insufficient tool messages following tool_calls）；
        // 而整段回滚又会连「任务 1..N 已完成、计划是什么」一起抹掉，导致无法续跑原计划。
        // 一次性快照激活 provider：client / options / grounding 全部由同一个 provider 派生，
        // 避免与并发 /model 切换交错（虽当前 submit 与 selectModel 同在 UI 线程，快照更自洽）。
        ChatClient client;
        org.springframework.ai.chat.prompt.ChatOptions perRequestOptions;
        String modelGrounding;
        if (registry != null) {
            LlmProvider active = registry.active();
            String activeModelId = registry.activeModelId();
            client = clientsByProvider.get(active.id());
            if (client == null) {
                throw new IllegalStateException("激活 provider 无对应 ChatClient：" + active.id());
            }
            perRequestOptions = active.options(activeModelId);
            modelGrounding = activeModelId;
        } else {
            client = chatClient;
            perRequestOptions = DeepSeekChatOptions.builder().model(model).build();
            modelGrounding = model;
        }
        try {
            Disposable reactive = client.prompt()
                    .user(effectiveText)
                    // MCP 工具每回合快照注入：与 defaultTools 合并（Spring AI 2.0 per-request tools 语义），
                    // /mcp 启停在下一回合即生效；mcp__ 前缀保证不与内置工具重名。空数组为 no-op。
                    .tools(mcpRegistry == null ? new Object[0] : mcpRegistry.activeTools().toArray())
                    .options(perRequestOptions.mutate())   // 每次请求按当前所选模型覆盖（mutate 回 native builder，保留 maxTokens 等）
                    // 同步覆盖系统提示里的 {AGENT_MODEL} grounding，使模型自报身份与实际所选一致（其余 param 沿用默认，merge 语义）
                    .system(s -> s.param(AgentEnvironment.AGENT_MODEL_KEY, modelGrounding))
                    .toolContext(Map.of(
                            "turnId", turnId,
                            MediaExternalizingCallback.CAPABILITIES_KEY, capabilitiesSnapshot()))   // 冻结「发起本回合的模型」能力
                    // 会话记忆键：SessionMemoryAdvisor 按此解析/自动创建会话（值即 chat_memory_conversation_id）
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
                    .stream().chatClientResponse()
                    .doOnNext(resp -> handleChunk(resp, turnId))
                    .doOnError(err -> handleError(err, turnId))
                    .doOnComplete(() -> handleComplete(turnId))
                    // 仅在「被取消」时触发（正常 complete/error 不触发）：裁掉悬空 tool_calls 尾巴，保留干净前缀。
                    .doOnCancel(this::trimDanglingToolCalls)
                    .subscribe();
            // 组合取消（层②）：dispose() 既取消 reactive 链，也对本回合在飞的并行子 agent shutdownNow。
            // Reactor 取消不 interrupt 阻塞在网络 IO 的子 agent 工具线程，若不显式拆池，取消后子 agent 仍会跑完、
            // 其迟到写入会污染会话并与随后的 /continue 竞态。cancelTurn 立即返回、不 await，不拖慢回 IDLE。
            if (subagentRunner == null) {
                return reactive;
            }
            return Disposables.composite(reactive, () -> subagentRunner.cancelTurn(turnId));
        } catch (RuntimeException ex) {
            // 同步组装/订阅异常不走 doOnError：手动复位状态（onError → IDLE），
            // 否则 UI 会永远卡在 THINKING（无终态事件），且异常会逃逸出 View.handle。
            listener.onError(turnId, ex);
            return Disposables.disposed();
        }
    }

    /**
     * 手动 /skill：发送前真正调用（被装饰的）Skill 工具——触发工具事件（UI 可见）并拿正文注入到 text 前。
     * skillName/skillTool 任一为空则原样返回（不注入）；工具调用失败也降级为不注入（事件已由装饰器上报）。
     */
    private String injectSkill(String text, String skillName, long turnId) {
        if (skillName == null || skillTool == null) {
            return text;
        }
        try {
            ToolContext ctx = new ToolContext(Map.of("turnId", turnId));
            String body = unwrapToolText(skillTool.call(toJsonCommand(skillName), ctx));
            return "<skill_instruction>\n" + body + "\n</skill_instruction>\n\n" + text;
        } catch (RuntimeException ex) {
            return text;   // 注入失败不阻断本轮：按原文发送（工具失败事件已由 ToolEventCallback 上报）
        }
    }

    /**
     * {@link ToolCallback#call} 的返回是「工具结果经 Spring AI 序列化」后的串——对返回 String 的工具即一个 JSON
     * 字符串字面量（带首尾引号、{@code \n} 被转义成两字符 {@code \}+{@code n}）。手动 {@code /skill} 要把正文当
     * <b>原始多行文本</b>嵌进用户消息，若原样嵌入，字面 {@code \n} 会连同引号一起落进会话并在 {@code -c} 回放里
     * 显示成一行长条（无法按真实换行拆分）。此处把 JSON 字符串解回原文；非 JSON 字符串字面量则原样返回。
     */
    static String unwrapToolText(String toolResult) {
        if (toolResult == null || toolResult.length() < 2
                || toolResult.charAt(0) != '"' || toolResult.charAt(toolResult.length() - 1) != '"') {
            return toolResult;   // 已是原文 / 非字符串结果（对象、数组）：原样返回
        }
        try {
            return MAPPER.readValue(toolResult, String.class);
        } catch (JacksonException e) {
            return toolResult;   // 不是合法 JSON 字符串：降级为原样，不阻断本轮
        }
    }

    /** 构造 SkillsInput 的 JSON 入参 {"command":"<name>"}。用 Jackson（与 DiffRenderer 一致），避免手写转义/硬编码线格式。 */
    private static String toJsonCommand(String skillName) {
        try {
            return MAPPER.writeValueAsString(Map.of("command", skillName));
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化 Skill 命令入参失败：" + skillName, e);
        }
    }

    /**
     * 中断（取消 / 报错）时调用：把会话裁到「最长干净前缀」，删掉末尾任何 {@code tool_calls} 未被后续 tool
     * 结果配平的悬空消息，用 {@link SessionRepository#replaceEvents} 覆盖写回。
     *
     * <p>相较「整段回滚到回合前」，这样能保留已完成任务与计划（TodoWrite / 已完成的 Task 回合）——它们都以
     * 「tool_calls 全部配平」的干净状态留在历史里，故 {@code /continue} 能据此续跑原计划；同时仍删掉那条会导致
     * 下轮 400 的悬空 {@code assistant(tool_calls)}。service / 仓库缺失（测试桩）时静默跳过。
     */
    void trimDanglingToolCalls() {
        if (sessionService == null || sessionRepository == null) {
            return;
        }
        String sid = sessionId;   // 快照：本方法可能在 reactive/后台线程运行，clearContext 会在 UI 线程换掉 volatile sessionId；
                                   // 两次读之间被换会把「旧会话净化后的事件」写进新会话 id（污染新会话 + 旧会话悬空 tool_calls 漏净化）。
        List<SessionEvent> events = sessionService.getEvents(sid);   // 时序、oldest-first
        List<SessionEvent> clean = SessionEvents.sanitize(events);         // 裁尾部悬空 tool_calls + 丢孤儿 tool 结果 + 折连续同角色
        if (clean != events) {   // sanitize 无改动时返回同一引用；引用不同即有裁剪/重建
            sessionRepository.replaceEvents(sid, List.copyOf(clean));
        }
    }

    /** 路径②：submit 开头把过往回合里携带文件全文的 tool 结果外置为引用。无改动 no-op。 */
    private void externalizeSessionFiles() {
        if (fileExternalizer == null || sessionService == null || sessionRepository == null) {
            return;
        }
        String sid = sessionId;
        List<SessionEvent> events = sessionService.getEvents(sid);
        List<SessionEvent> ext = fileExternalizer.externalize(events);
        if (ext != events) {
            sessionRepository.replaceEvents(sid, List.copyOf(ext));
        }
    }

    /**
     * 若会话历史尾部残留一条 {@link UserMessage}（上个回合的 {@code after()} 未落盘 assistant 所致），把它的文本折进
     * 本回合的出站消息、并从会话事件里删除，返回合并后的出站文本；否则原样返回 {@code outbound}。
     *
     * <p>为何必须在此处而非靠 {@link #trimDanglingToolCalls} / 流式守卫：{@code SessionMemoryAdvisor.before} 会<b>无条件</b>
     * 把本回合 user 追加到历史，若历史已以 user 结尾即成两条连续 user，被 DeepSeek 以 {@code 400} 拒收——且这发生在
     * <b>模型调用之前</b>，出站 sanitize（只改会话、不改本回合待发 user）与空流守卫都够不到。故在发送前就把尾部残留 user
     * 折进待发文本（只影响发给模型的内容，不动 {@code onUserMessage} 的 UI 展示，与 {@link #injectSkill} 同纪律）。
     *
     * <p>{@code sid} 由调用方快照传入（与 {@link #trimDanglingToolCalls} 同纪律，避免与 {@code /clear} 换 volatile 交错）。
     * service/仓库缺失（测试桩）时静默跳过。因 {@link SessionEvents#collapseConsecutiveSameRole} 已把尾部连续 user 折成一条，
     * 这里最多折一次即可清空边界。
     */
    private String foldTrailingUserIntoOutbound(String sid, String outbound) {
        if (sessionService == null || sessionRepository == null) {
            return outbound;
        }
        List<SessionEvent> events = sessionService.getEvents(sid);
        if (events.isEmpty()) {
            return outbound;
        }
        SessionEvent last = events.get(events.size() - 1);
        if (!(last.getMessage() instanceof UserMessage prevUser)) {
            return outbound;
        }
        String prevText = prevUser.getText();
        sessionRepository.replaceEvents(sid, List.copyOf(events.subList(0, events.size() - 1)));
        return (prevText == null || prevText.isBlank()) ? outbound : prevText + "\n\n" + outbound;
    }

    /** 冻结当前激活模型的能力快照进 toolContext（规避工具执行期间切模型的时序错配）。
     *  registry 缺失（旧单-client 测试路径）→ TEXT_ONLY。 */
    private ModelCapabilities capabilitiesSnapshot() {
        if (registry == null) return ModelCapabilities.TEXT_ONLY;
        return registry.active().capabilities(registry.activeModelId());
    }

    @Override
    public List<SkillInfo> skills() { return reloadableSkill != null ? reloadableSkill.skills() : skills; }

    /** {@code /reload}：重扫技能目录（用户级 + 项目级），使运行中新增/删除的技能对模型与 {@code /skills} 生效。 */
    @Override
    public void reloadSkills() {
        if (reloadableSkill != null) {
            reloadableSkill.reload();
        }
    }

    /** busy 闸门查询（层③）：仍有在飞子 agent 时，UI 把 /continue 等新回合排队而非立即起跑。桩路径（runner 为 null）恒 false。 */
    @Override
    public boolean hasInFlightSubagents() {
        return subagentRunner != null && subagentRunner.inFlightCount() > 0;
    }

    // ── MCP 管理门面（纯委托 McpRegistry；registry 缺失 = 无 MCP 支持） ──
    @Override
    public List<McpRegistry.ServerView> mcpServers() {
        return mcpRegistry == null ? List.of() : mcpRegistry.servers();
    }

    @Override
    public McpRegistry.ToggleResult enableMcp(String name) {
        return mcpRegistry == null ? null : mcpRegistry.enable(name);
    }

    @Override
    public McpRegistry.ToggleResult disableMcp(String name) {
        return mcpRegistry == null ? null : mcpRegistry.disable(name);
    }

    @Override
    public List<ModelOption> models() {
        return registry != null ? registry.allModels() : MODELS;
    }

    @Override
    public String currentModel() {
        return registry != null ? registry.activeModelId() : model;
    }

    @Override
    public void selectModel(String id) {
        if (registry != null) {
            registry.select(id);
            return;
        }
        for (ModelOption m : MODELS) {
            if (m.id().equals(id)) { this.model = id; return; }   // 仅接受已知模型
        }
    }

    /**
     * 手动压缩：在后台线程强制立即压缩本会话。总结 LLM 调用可能耗时数分钟，绝不阻塞调用线程（UI）。
     * 并发闸门在 {@code CodeTuiView}（仅空闲且非压缩中才调用本方法）；此处再用 CAS 兜底一层，
     * 防止任何调用方漏判导致两次压缩并发、把 UI 的「开始/完成」事件交错。已在压缩中则静默忽略。
     */
    @Override
    public void compact() {
        if (!compactionInFlight.compareAndSet(false, true)) {
            return;   // 已有压缩在进行：忽略重复触发（UI 已给出反馈，这里不再叠加）
        }
        Thread t = new Thread(this::runCompaction, "manual-compact");
        t.setDaemon(true);
        t.start();
    }

    /**
     * {@code /clear}：切到一个全新空会话。仅换 {@link #sessionId}（volatile 写），下一个回合的
     * {@code SessionMemoryAdvisor} 会按新 id 自动创建空会话；旧会话事件/文件<b>原样保留</b>，可 {@code -c} 恢复。
     * 调用方（{@code CodeTuiView}）已保证仅在空闲（非回合中/非压缩中）时调用，故此处无需再守卫并发。
     *
     * <p><b>顺带清掉会话级权限规则</b>：「允许，本会话不再问」的作用域就是这一次会话，
     * 跨到新会话继续生效等于用户以为清干净了、其实上一轮的授权还在。落盘规则（永久允许）不动。
     */
    @Override
    public void clearContext() {
        this.sessionId = SessionIds.newId();
        if (permissionEngine != null) {
            permissionEngine.clearSessionRules();
        }
    }

    // ── 权限门面（Shift+Tab 与 /permissions；引擎是唯一事实来源，这里只转发） ──

    /**
     * 当前权限模式。
     *
     * <p><b>无引擎的桩路径退回 DEFAULT 而不是抛</b>：门面是给状态栏读的，
     * 一个测试桩缺了引擎不该让 UI 渲染崩掉；而 DEFAULT 是最严的那档，退回它不会放宽任何东西。
     */
    @Override
    public PermissionMode permissionMode() {
        return permissionEngine == null ? PermissionMode.DEFAULT : permissionEngine.mode();
    }

    /** 循环到下一个模式（Shift+Tab），返回实际生效的模式（引擎可能拒绝进 BYPASS）。 */
    @Override
    public PermissionMode cyclePermissionMode() {
        return permissionEngine == null ? PermissionMode.DEFAULT : permissionEngine.cycleMode();
    }

    /** 当前生效的全部规则（{@code /permissions} 只读展示）。 */
    @Override
    public List<PermissionRule> permissionRules() {
        return permissionEngine == null ? List.of() : permissionEngine.effectiveRules();
    }

    /** 当前会话 id（包级可见，供测试断言换会话是否生效）。 */
    String sessionId() {
        return sessionId;
    }

    /**
     * 同步执行一次手动压缩（供 {@link #compact()} 的后台线程调用；包级可见便于测试直调）。
     * 用恒真触发器绕过 token 阈值，配合激进的手动策略。
     *
     * <p><b>本方法独占手动路径的三个生命周期事件</b>（started/finished/failed）：它<b>直接</b>调用
     * {@code sessionService.compact} 并拿到返回的 {@link CompactionResult} 自行上报，因此
     * {@code manualStrategy} 是<b>未包装</b>的裸策略（见 {@code AgentTools}）——若再套
     * {@link NotifyingCompactionStrategy}，同一次失败会被「装饰器 + 本方法」重复上报。装饰器只服务于
     * 自动路径（advisor 内部调用、拿不到返回值）。
     *
     * <p>先发 started 再调用：保证即便压缩在进入策略前就抛异常（会话不存在 / I/O），UI 也已显示过「正在压缩」，
     * 不会出现「只有失败行、没有开始提示」的静默错觉。{@code catch} 覆盖到 {@link Error}：即便 LLM 摘要抛出
     * {@code Error}，也先发 failed 把 {@code compacting} 清掉（否则 UI 永久卡在「压缩中」、{@code isBusy()}
     * 恒真、后续回合与 /compact 全被堵死），再把 {@code Error} 向上抛出、不吞。
     */
    void runCompaction() {
        try {
            listener.onCompactionStarted("manual");
            CompactionResult result = sessionService.compact(sessionId, req -> true, manualStrategy);
            listener.onCompactionFinished(result.eventsRemoved(), result.tokensEstimatedSaved());
        } catch (RuntimeException e) {
            listener.onCompactionFailed(String.valueOf(e.getMessage()));
        } catch (Error e) {
            listener.onCompactionFailed(String.valueOf(e.getMessage()));   // 先清状态，避免永久卡「压缩中」
            throw e;                                                        // Error 不吞：清完状态仍向上抛
        } finally {
            compactionInFlight.set(false);   // 释放兜底闸门（无论成功/失败）
        }
    }

    /**
     * 现算一份会话上下文用量快照（供 {@code /context} 命令展示）。
     *
     * <p>读一遍当前会话的事件（数条数、按消息类型分桶）与消息（拼接文本 → JTokkit 估算 token）。
     * 只读、不加锁：若恰有回合在写事件，拿到的是尽力而为的一致视图，对「看个大概用量」足够。
     * 会话尚无事件（未开始对话）时 {@code getEvents} 返回空列表，自然得到全 0 的快照。
     *
     * <p>token 估算走与压缩同一个 {@link TokenCountEstimator}。{@code Message} 只是 {@code Content}（有 getText），
     * 并非 {@code MediaContent}，故不能直接喂 {@code estimate(Iterable)}——改为拼接各消息文本后 {@code estimate(String)}，
     * 与「累计文本 token」的直觉一致。阈值/窗口/保留窗口等策略数取自 {@link AgentTools}（同包常量），保证与实际装配一致。
     */
    @Override
    public ContextStats contextStats() {
        String sid = sessionId;   // 快照一次：contextStats 内两读 sessionId，/clear 换 volatile 会致撕裂读（事件按旧会话、消息按新会话）
        List<SessionEvent> events = sessionService.getEvents(sid);
        int user = 0, assistant = 0, tool = 0, other = 0;
        for (SessionEvent e : events) {
            MessageType type = e.getMessageType();
            if (type == MessageType.USER) {
                user++;
            } else if (type == MessageType.ASSISTANT) {
                assistant++;
            } else if (type == MessageType.TOOL) {
                tool++;
            } else {
                other++;   // 系统 / 摘要等
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : sessionService.getMessages(sid)) {
            String text = m.getText();
            if (text != null && !text.isEmpty()) {
                sb.append(text).append('\n');
            }
        }
        long tokens = sb.length() == 0 ? 0L : tokenCountEstimator.estimate(sb.toString());
        return new ContextStats(events.size(), user, assistant, tool, other, tokens,
                AgentTools.COMPACTION_TOKEN_THRESHOLD, AgentTools.CONTEXT_WINDOW_TOKENS,
                AgentTools.MAX_EVENTS_TO_KEEP, AgentTools.MANUAL_MAX_EVENTS_TO_KEEP);
    }

    /** 从一个流式块里抽取文本增量并发给 listener。全链路 null-guard：工具调用块常常没有输出/文本。 */
    private void handleChunk(ChatClientResponse resp, long turnId) {
        if (resp == null) {
            return;
        }
        ChatResponse chatResponse = resp.chatResponse();
        if (chatResponse == null) {
            return;
        }
        Generation generation = chatResponse.getResult();
        if (generation == null) {
            return;
        }
        AssistantMessage output = generation.getOutput();
        if (output == null) {
            return;
        }
        String delta = output.getText();   // AbstractMessage#getText()（javap 已核实）
        if (delta != null && !delta.isEmpty()) {
            listener.onAssistantToken(turnId, delta);
        }
    }

    private void handleError(Throwable err, long turnId) {
        listener.onError(turnId, err);
        // 报错也裁掉悬空 tool_calls 尾巴：既避免坏历史下轮 400，又保留已完成任务与计划以便 /continue 续跑。
        trimDanglingToolCalls();
    }

    private void handleComplete(long turnId) {
        listener.onTurnComplete(turnId);
    }
}

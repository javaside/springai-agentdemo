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
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import io.github.javaside.springai.codetui.agent.interjection.InterjectingChatModel;
import io.github.javaside.springai.codetui.agent.compaction.NotifyingCompactionStrategy;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.RetryReporter;
import io.github.javaside.springai.codetui.agent.llm.StreamInterruptedException;
import io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig;
import io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig.StreamRetryMode;
import io.github.javaside.springai.codetui.agent.llm.ProviderModel;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.interjection.Interjections;
import io.github.javaside.springai.codetui.agent.mcp.McpRegistry;
import io.github.javaside.springai.codetui.agent.media.MediaExternalizingCallback;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.media.SessionFileExternalizer;
import io.github.javaside.springai.codetui.agent.media.VisionMaterializingChatModel;
import io.github.javaside.springai.codetui.agent.media.VisionSnapshot;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.prompt.PermissionModePrompt;
import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.skill.ReloadableSkillTool;
import io.github.javaside.springai.codetui.agent.skill.SkillInfo;
import io.github.javaside.springai.codetui.agent.session.ContextStats;
import io.github.javaside.springai.codetui.agent.session.SessionEvents;
import io.github.javaside.springai.codetui.agent.session.SessionIds;
import io.github.javaside.springai.codetui.agent.session.SessionTokenEstimator;
import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import io.github.javaside.springai.codetui.agent.subagent.SubagentRunner;
import io.github.javaside.springai.codetui.ui.update.UiChangeListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** 只用于「失败了也不该带崩回合」的那几处降级路径（如插话补历史）；不做常规日志。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CodingAgent.class);

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
    /** 可空（测试桩）：按 provider id 索引的视觉兑现装饰器，与 clientsByProvider 一一对应；仅供 /context 读快照。 */
    private final java.util.Map<String, VisionMaterializingChatModel> visionModels;
    private final io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry backgroundRegistry;   // 可空（测试桩）：后台任务的唯一并发真相源
    private final io.github.javaside.springai.codetui.agent.background.TaskResultStore backgroundResults;           // 可空（测试桩）：后台结果进会话前的限幅
    /** 可空（测试桩）：插话队列，与 ChatClient 装饰链共用同一实例；null 时插话相关方法全 no-op。 */
    private final Interjections interjections;
    /** 可空（测试桩）：会话级 token 用量累加器；contextStats() 读快照、clearContext() 清零。 */
    private final TokenUsageAccumulator usageAccumulator;
    /** 系统提示词估算 token（装配期快照，每回合固定重发）；contextStats() 分类展示用，单-client 桩路径为 0。 */
    private final long systemPromptTokens;
    private volatile String model = MODELS.get(0).id();   // 运行时可经 /model 切换，对后续回合生效

    /** L2 开关（全参构造注入；旧 telescoping 重载默认 ALL——测试必须可控，不读环境变量）。 */
    private final StreamRetryConfig retryConfig;

    /**
     * L1 重试计数桥载体：submit 开头<b>整体替换</b>（闭包捕获局部 l1Retries 与 turnId）；
     * 旧闭包失配即丢弃，回合终态不清空也无害。包私有方法 {@link #onL1Retry} 只读本 volatile。
     */
    private volatile RetryReporter activeTurnL1Sink;

    /** 无技能清单的构造（回显桩/测试桩用）：等价于技能为空。
     *
     * @param chatClient          单个 ChatClient（桩路径，无多 provider 路由）
     * @param listener            UI 接缝
     * @param sessionId           会话 id
     * @param activeTurnId        回合计数器，与 UI 共用同一实例
     * @param sessionService      会话服务
     * @param manualStrategy      {@code /compact} 用的压缩策略
     * @param tokenCountEstimator token 估算器
     */
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
        this.visionModels = null;      // 单-client 桩路径：无视觉装饰器（/context 的视觉列恒为 0）
        this.backgroundRegistry = null;  // 单-client 桩路径：无后台子系统（四个后台方法退回"什么都没有"）
        this.backgroundResults = null;
        this.interjections = null;       // 单-client 桩路径：无插话队列（插话门面全 no-op、回合末不补历史）
        this.usageAccumulator = null;    // 单-client 桩路径：无采集（缓存列恒 0/null）
        this.systemPromptTokens = 0L;    // 单-client 桩路径：无装配期估算（分类列恒 0）
        this.retryConfig = new StreamRetryConfig(StreamRetryMode.ALL);
    }

    /**
     * 多 provider 生产构造：registry 决定激活 provider 与模型，clientsByProvider 提供各家 ChatClient。
     * submit 按激活 provider 选 ChatClient + 用该家 options 覆盖模型；/model 走 registry 跨家。
     *
     * <p>本重载<b>无子 agent 执行器</b>（测试桩用），等价 subagentRunner=null：
     * 取消不拆并行池、busy 闸门恒不含在飞子 agent。
     *
     * @param registry           provider 注册表，决定激活 provider 与模型
     * @param clientsByProvider  各 provider 的 ChatClient，键为 provider id
     * @param listener           UI 接缝
     * @param sessionId          会话 id
     * @param activeTurnId       回合计数器，与 UI 共用同一实例
     * @param sessionService     会话服务
     * @param manualStrategy     {@code /compact} 用的压缩策略
     * @param tokenCountEstimator token 估算器
     * @param skills             装配期技能快照；{@code reloadableSkill} 非空时以后者为实时数据源
     * @param skillTool          已装饰的技能工具，{@code /skill} 手动挂载时复用同一实例
     * @param sessionRepository  会话仓库，出站净化需要它 replaceEvents
     * @param reloadableSkill 可重载技能源（{@code /reload} 触发重扫）；亦作 {@code skills()} 的实时数据源。可空。
     */
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

    /** 向后兼容重载（无视觉装饰器：测试桩用）。等价 visionModels=null（{@code /context} 的视觉列恒为 0）。 */
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill, SubagentRunner subagentRunner,
                       SessionFileExternalizer fileExternalizer, McpRegistry mcpRegistry,
                       PermissionEngine permissionEngine) {
        this(registry, clientsByProvider, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, sessionRepository, reloadableSkill, subagentRunner,
                fileExternalizer, mcpRegistry, permissionEngine, null);
    }

    /**
     * 向后兼容重载（无后台子系统：测试桩用）。等价 backgroundRegistry/backgroundResults=null——
     * 四个后台方法退回"什么都没有"，与接口默认实现同语义。
     */
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill, SubagentRunner subagentRunner,
                       SessionFileExternalizer fileExternalizer, McpRegistry mcpRegistry,
                       PermissionEngine permissionEngine,
                       java.util.Map<String, VisionMaterializingChatModel> visionModels) {
        this(registry, clientsByProvider, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, sessionRepository, reloadableSkill, subagentRunner,
                fileExternalizer, mcpRegistry, permissionEngine, visionModels, null, null);
    }

    /**
     * 向后兼容重载（无插话队列：测试桩用）。等价 {@code interjections=null}——
     * 插话门面全 no-op、回合末不补历史，与 {@link SubmitHandler} 的默认实现同语义。
     */
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill, SubagentRunner subagentRunner,
                       SessionFileExternalizer fileExternalizer, McpRegistry mcpRegistry,
                       PermissionEngine permissionEngine,
                       java.util.Map<String, VisionMaterializingChatModel> visionModels,
                       io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry backgroundRegistry,
                       io.github.javaside.springai.codetui.agent.background.TaskResultStore backgroundResults) {
        this(registry, clientsByProvider, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, sessionRepository, reloadableSkill, subagentRunner,
                fileExternalizer, mcpRegistry, permissionEngine, visionModels,
                backgroundRegistry, backgroundResults, null);
    }

    /**
     * 多 provider 生产构造（全参）：{@code mcpRegistry} 运行期 MCP 中枢，submit 每回合快照注入其 activeTools（可空）；
     * {@code permissionEngine} 权限引擎，须是 {@code AgentRuntime.permissionEngine()} 那一个——
     * 门面（Shift+Tab 切模式 / {@code /permissions} 列规则）改的必须正是工具那一侧读的那个对象（可空，桩路径用）。
     *
     * <p>{@code visionModels} 须是 {@code AgentRuntime.visionModels()} 那一份——{@code /context} 要报的是
     * <b>真正发出去的那条请求</b>兑现了多少图，读别的实例只会得到恒 0 的假账。
     *
     * <p>{@code backgroundRegistry} / {@code backgroundResults} 同样须是 {@code AgentRuntime} 那一份——
     * 注册表是后台任务的<b>唯一</b>并发真相源，另建一个等于 UI 面板和真正在跑的任务各看各的。
     *
     * <p>{@code interjections} 也须是 {@code AgentRuntime.interjections()} 那一份——它是 UI 与
     * {@code InterjectingChatModel} 之间的唯一交汇点。另建一个不会报错，只会让插话投进去永远没人取。
     */
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill, SubagentRunner subagentRunner,
                       SessionFileExternalizer fileExternalizer, McpRegistry mcpRegistry,
                       PermissionEngine permissionEngine,
                       java.util.Map<String, VisionMaterializingChatModel> visionModels,
                       io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry backgroundRegistry,
                       io.github.javaside.springai.codetui.agent.background.TaskResultStore backgroundResults,
                       Interjections interjections) {
        this(registry, clientsByProvider, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, sessionRepository, reloadableSkill, subagentRunner,
                fileExternalizer, mcpRegistry, permissionEngine, visionModels, backgroundRegistry,
                backgroundResults, interjections, null, 0L);
    }

    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill, SubagentRunner subagentRunner,
                       SessionFileExternalizer fileExternalizer, McpRegistry mcpRegistry,
                       PermissionEngine permissionEngine,
                       java.util.Map<String, VisionMaterializingChatModel> visionModels,
                       io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry backgroundRegistry,
                       io.github.javaside.springai.codetui.agent.background.TaskResultStore backgroundResults,
                       Interjections interjections,
                       TokenUsageAccumulator usageAccumulator,
                       long systemPromptTokens) {
        this(registry, clientsByProvider, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, sessionRepository, reloadableSkill, subagentRunner,
                fileExternalizer, mcpRegistry, permissionEngine, visionModels, backgroundRegistry,
                backgroundResults, interjections, usageAccumulator, systemPromptTokens,
                new StreamRetryConfig(StreamRetryMode.ALL));
    }

    /**
     * 多 provider 生产构造（全参，含 L2 开关）：其余 telescoping 重载一律默认
     * {@code new StreamRetryConfig(StreamRetryMode.ALL)} 委托到本构造——不读环境变量，测试必须可控。
     */
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill, SubagentRunner subagentRunner,
                       SessionFileExternalizer fileExternalizer, McpRegistry mcpRegistry,
                       PermissionEngine permissionEngine,
                       java.util.Map<String, VisionMaterializingChatModel> visionModels,
                       io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry backgroundRegistry,
                       io.github.javaside.springai.codetui.agent.background.TaskResultStore backgroundResults,
                       Interjections interjections,
                       TokenUsageAccumulator usageAccumulator,
                       long systemPromptTokens,
                       StreamRetryConfig retryConfig) {
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
        this.visionModels = visionModels;
        this.backgroundRegistry = backgroundRegistry;
        this.backgroundResults = backgroundResults;
        this.interjections = interjections;
        this.usageAccumulator = usageAccumulator;
        this.systemPromptTokens = systemPromptTokens;
        this.retryConfig = retryConfig;
        // 送达 → 信息流。刻意复用 onUserMessage 而不是新开一路回调：插话本来就是一条用户消息，
        // 走这条路它渲出来与排队消息出队时一模一样（› 原话 + Kind.USER），还白送 turnId 迟到过滤
        // （Esc 之后迟到的送达自动被挡）。另起一路就得把这两件事各自重写一遍。
        if (interjections != null) {
            interjections.onDelivered(text -> listener.onUserMessage(activeTurnId.get(), text));
        }
    }

    @Override
    public Disposable submit(String text) {
        return submit(text, null);
    }

    /**
     * 把同一个 UI 变化监听 fan-out 到四个 Agent 侧外部状态源（事件驱动 UI 的 Task 4）：
     * {@code Interjections}、{@code SubagentRunner}、{@code BackgroundTaskRegistry}、{@code McpRegistry}。
     *
     * <p><b>刻意不绑 {@code ConversationState}</b>（本类手里也根本没有它）：View 直接绑它。
     * 在这里代绑一次 + View 再绑一次 = 同一通知进 coordinator 两次，批次翻倍。
     *
     * <p>各源自己把 null 归一成 no-op；缺件（桩路径的 null 子系统）静默跳过。
     * 可重复调用：后一次整体替换前一次（每个源各自替换，无残留路由）。
     */
    @Override
    public void setUiChangeListener(UiChangeListener listener) {
        if (interjections != null) {
            interjections.setUiChangeListener(listener);
        }
        if (subagentRunner != null) {
            subagentRunner.setUiChangeListener(listener);
        }
        if (backgroundRegistry != null) {
            backgroundRegistry.setUiChangeListener(listener);
        }
        if (mcpRegistry != null) {
            mcpRegistry.setUiChangeListener(listener);
        }
    }

    @Override
    public Disposable submit(String text, String skillName) {
        long turnId = activeTurnId.incrementAndGet();
        // 本回合局部状态：resubscriptions / disposed / l1Retries 都是 submit 局部（⚠ 不得为实例字段——
        // 跨回合残留会让下一回合健康首轮误执行 prepareResume，spec §3.3 首轮纪律）。
        AtomicInteger resubscriptions = new AtomicInteger();
        AtomicBoolean disposed = new AtomicBoolean();
        AtomicInteger l1Retries = new AtomicInteger();
        boolean l2Enabled = retryConfig.l2Enabled();   // filter 闭包捕获该局部快照
        // L1 桥计数载体：submit 开头整体替换。turnId 比对（long 基本类型）在 sink 闭包内——
        // RetryReporter 签名无 turnId，onL1Retry 层没有可比对的值；旧闭包失配即丢弃，终态不清空无害。
        activeTurnL1Sink = (attempt, backoffMs, reason) -> {
            if (activeTurnId.get() == turnId) {
                l1Retries.incrementAndGet();
                listener.onRetryScheduled(turnId, attempt, 5, backoffMs, reason);
            }
        };
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
        // 一次性快照激活 provider：client / options / grounding / 能力全部由同一个 provider 派生并<b>留在 defer 外</b>
        // （若随每轮重订阅重取，退避等待期 /model 切换会让续跑轮能力快照与冻结的 provider 漂移——同回合模型漂移的小号版）；
        // permissionMode() 是运行期值（Shift+Tab），留在 defer 内每轮现取。
        ChatClient client;
        org.springframework.ai.chat.prompt.ChatOptions perRequestOptions;
        String modelGrounding;
        if (registry != null) {
            ProviderRegistry.RequestSelection selection = registry.activeRequestSelection();
            client = clientsByProvider.get(selection.provider().id());
            if (client == null) {
                throw new IllegalStateException("激活 provider 无对应 ChatClient：" + selection.provider().id());
            }
            perRequestOptions = selection.options();
            modelGrounding = selection.modelId();
        } else {
            client = chatClient;
            perRequestOptions = DeepSeekChatOptions.builder().model(model).build();
            modelGrounding = model;
        }
        // capabilitiesSnapshot 与上方 provider 快照段同纪律：**有意归同步语义**（Task 8 review I1）。
        // 它不在 defer 体内、不进 pipeline——若 registry 状态异常导致这里抛错，是**同步**抛给 submit 的
        // 调用方（与 client==null 的 IllegalStateException 一处），不会变成 error 信号经 doOnError 处理，
        // 也就绝不会与 handleErrorWithRetryPrefix 双发 listener.onError。别为「优雅」把它挪进 defer。
        ModelCapabilities capabilities = capabilitiesSnapshot();
        String effectiveOutbound = effectiveText;   // 重放表达式恒从 submit 闭包原始值重建（composeResumeUser 唯一输入）
        // 组装/订阅整体移入 defer：续跑重订阅 = 每轮经 defer 重建 spec（同一 chatClientResponse Flux 实例二次
        // 订阅抛 IllegalStateException: No StreamAdvisors——defer 内重建是重订阅安全的唯一形态，V1 实证）。
        // ⚠ 不再有外层 catch：组装异常变成 error 信号由 doOnError 统一处理（保留 catch 会双发 listener.onError）。
        Flux<ChatClientResponse> pipeline = Flux.defer(() -> {
            // disposed 检查：dispose 不能中断 boundedElastic worker 上已在跑的 defer 体——
            // 不查的话取消后仍会跑完一轮无谓的 prepareResume 会话写。cancel-先-置位保证 Esc 完成后标志必可见。
            if (disposed.get()) {
                return Flux.error(new CancellationException("回合已取消"));
            }
            int resub = resubscriptions.incrementAndGet();
            String outboundUser;
            if (resub == 1) {
                outboundUser = effectiveOutbound;
            } else {
                ResumeShape shape = prepareResume(sessionId, disposed);
                if (shape == ResumeShape.FROM_TEXT) {
                    // 原子取走 delivered+pending 全部原文（单锁）；返回后 pending 必空 → Interjecting 该轮早退，
                    // 不会在 compose 出的 user 之后再注入第二条 user（连续双 user 400）。
                    outboundUser = composeResumeUser(effectiveOutbound,
                            interjections == null ? List.of() : interjections.takeAllForResumeUser());
                } else {
                    // FROM_TOOLS：不调 .user()（V1 断言 2 口径：空 user 落库、出站不含），notice/插话走 Interjecting。
                    outboundUser = null;
                }
            }
            var spec = client.prompt();
            if (outboundUser != null) {
                spec = spec.user(outboundUser);
            }
            // MCP 工具快照注入（每轮重取 activeTools——与冷流重走语义一致，/mcp 启停即时生效）。
            Object[] mcpTools = mcpRegistry == null ? null : mcpRegistry.activeTools().toArray();
            if (mcpTools != null && mcpTools.length > 0) {
                spec = spec.tools(mcpTools);
            }
            return spec
                    .options(perRequestOptions.mutate())   // 每次请求按当前所选模型覆盖（mutate 回 native builder，保留 maxTokens 等）
                    // 同步覆盖系统提示里的 {AGENT_MODEL} grounding，使模型自报身份与实际所选一致（其余 param 沿用默认，merge 语义）；
                    // {PERMISSION_MODE} 必须每回合重算——模式随 Shift+Tab 运行期变化，而 defaultSystem 是 build 期烘焙的。
                    .system(s -> s.param(AgentEnvironment.AGENT_MODEL_KEY, modelGrounding)
                            .param(AgentTools.PERMISSION_MODE_KEY, PermissionModePrompt.of(permissionMode())))
                    .toolContext(Map.of(
                            "turnId", turnId,
                            MediaExternalizingCallback.CAPABILITIES_KEY, capabilities))   // 冻结「发起本回合的模型」能力
                    // 会话记忆键：SessionMemoryAdvisor 按此解析/自动创建会话（值即 chat_memory_conversation_id）
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
                    .stream().chatClientResponse();
        })
                // 阻塞准备段与 advisor 链不跑在 parallel 调度器；位置必须在 defer 与 retryWhen 之间（覆盖重订阅）。
                .subscribeOn(Schedulers.boundedElastic())
                // L2 回合级续跑：白名单 = StreamInterruptedException；上限 = 2 次续跑 + 首次 = 共 3 次完整流。
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(4))
                        .jitter(0d)   // 与 L1 同款显式关闭（jitter 会让真实 delay 与 resumeBackoffMs 不符）
                        .filter(ex -> l2Enabled && ex instanceof StreamInterruptedException)
                        // L2 提示行触发点（与 L1 同款 doBeforeRetry 纪律；退避 delay 前同步执行）：
                        // attempt = 续跑序号（1..2）；maxAttempts 传 2 供 UI 拼「续跑 b/2」文案。
                        .doBeforeRetry(sig -> listener.onRetryScheduled(turnId,
                                (int) sig.totalRetries() + 1,
                                2,
                                resumeBackoffMs((int) sig.totalRetries()),
                                "流中断")))
                .doOnNext(resp -> handleChunk(resp, turnId))
                // 终态错误一次（解包 L2 包装 + 拼重试前缀文案；l2 计数 = resubscriptions-1）。
                .doOnError(err -> handleErrorWithRetryPrefix(unwrapL2(err), turnId,
                        l1Retries.get(), resubscriptions.get() - 1))
                .doOnComplete(() -> handleComplete(turnId))
                // 仅在「被取消」时触发（正常 complete/error 不触发）：裁掉悬空 tool_calls 尾巴，保留干净前缀。
                .doOnCancel(this::trimDanglingToolCalls);
        // 错误已由 doOnError 处理；此处加空 errorConsumer 防止 Reactor 把未消费错误
        // 再次打印到日志（onErrorDropped / ErrorCallbackNotImplemented 噪音）。
        Disposable reactive = pipeline.subscribe(null, err -> {});
        // 组合取消（层②）：参数顺序是安全性前提——CompositeDisposable 按添加序 dispose，
        // reactive 在前、置位 disposed 的 runnable 在后：先 cancel（信号下传/订阅取消）后置位——
        // 此后 defer 体即使返回 CancellationException 也是 cancelled 后的 dropped 信号（不达 doOnError）。
        // 反序会打开 error-beats-cancel 窗口：置位后、cancel 前 error 可先到 doOnError → Esc 后屏幕出现
        // 「⚠ 出错：回合已取消」。cancelTurn 立即返回、不 await，不拖慢回 IDLE。
        return Disposables.composite(reactive, () -> {
            disposed.set(true);
            if (subagentRunner != null) {
                subagentRunner.cancelTurn(turnId);
            }
        });
    }

    /** 续跑恢复的形状：尾部 user → 重放 .user()；尾部 tool/其他 → 走 Interjecting 注入通道。 */
    private enum ResumeShape { FROM_TEXT, FROM_TOOLS }

    /** 续跑通知文本：形状①并入恢复 user、形状②经 Interjecting 一次性通道注入（spec §3.3）。 */
    private static final String RESUME_NOTICE = "<system-notice>\n"
            + "网络中断，你的上一段输出未被保留。请从中断处继续完成回答，不要重复已输出的内容。\n"
            + "</system-notice>";

    /**
     * 续跑轮出站前的会话重整（仅 resub&gt;1 执行）。执行序（spec §3.3 步骤 1-6）：
     * trim → 分流判定（trim 后尾部是 UserMessage → 形状①；否则②——先判会把悬空 tool_calls 误判成②的兄弟场景判错）
     * → strip（仅①）→ 全域清 blank user → 形状② refill（delivered 整元素 addFirst）→ set 前复查 disposed →
     * 形状② setResumeNotice（会话内最后一条 UserMessage 已含 {@code <system-notice>} 则跳过——防 ①→② 混变双份）。
     * 形状①的 takeAllForResumeUser 由调用方（defer）在 prepareResume 返回后调用，本方法不触碰队列取走类操作。
     */
    private ResumeShape prepareResume(String sid, AtomicBoolean disposed) {
        trimDanglingToolCalls();
        List<SessionEvent> events = sessionService.getEvents(sid);
        boolean tailIsUser = !events.isEmpty() && events.get(events.size() - 1).getMessage() instanceof UserMessage;
        ResumeShape shape = tailIsUser ? ResumeShape.FROM_TEXT : ResumeShape.FROM_TOOLS;
        if (shape == ResumeShape.FROM_TEXT) {
            stripTrailingTurnUser(sid);
        }
        purgeBlankUserEvents(sid);
        if (shape == ResumeShape.FROM_TOOLS && interjections != null) {
            interjections.refillForResume();   // 已送达插话 addFirst 回 pending（保留 pending，由 Interjecting 注入）
            // set 前再查一次 disposed：Esc 的 drainForRefill 若在 prepareResume 跑中先跑过（notice 尚未 set，
            // 清了个寂寞），此处不复查会把 notice 落进一次性通道 → 跨回合泄漏进下一回合 prompt。一行关窗。
            if (disposed.get()) {
                return ResumeShape.FROM_TOOLS;
            }
            if (!lastUserHasResumeNotice(sid)) {
                interjections.setResumeNotice(RESUME_NOTICE);
            }
        }
        return shape;
    }

    /**
     * 形状①的恢复 user：{@code effectiveText + "\n\n" + 合并插话 + "\n\n" + NOTICE}，纯函数、每轮从 submit
     * 闭包原始值重建（多轮续跑不叠加 notice：strip 先删上一轮含 notice 的那条再重放）。空插话退化为两段。
     */
    private String composeResumeUser(String effectiveText, List<String> resumeTexts) {
        if (resumeTexts == null || resumeTexts.isEmpty()) {
            return effectiveText + "\n\n" + RESUME_NOTICE;
        }
        return effectiveText + "\n\n" + String.join("\n", resumeTexts) + "\n\n" + RESUME_NOTICE;
    }

    /** 形状①：删掉会话尾部本回合 user（重走 advisor 链时 before() 必然再 append，删+重追加 = 恰好一条）。 */
    private void stripTrailingTurnUser(String sid) {
        if (sessionService == null || sessionRepository == null) {
            return;
        }
        List<SessionEvent> events = sessionService.getEvents(sid);
        if (events.isEmpty() || !(events.get(events.size() - 1).getMessage() instanceof UserMessage)) {
            return;
        }
        sessionRepository.replaceEvents(sid, List.copyOf(events.subList(0, events.size() - 1)));
    }

    /**
     * 全域删除 content 为空串的 UserMessage 事件（严格 {@code isEmpty()}，不用 isBlank——留出「纯空白用户消息」的
     * 将来语义）。合法性前提：提交/插话/skill 各闸门挡空输入（守卫测试点名）；本方法只清续跑伪影。
     * no-change 不写回（同 trim 模式）。清除点：prepareResume 内 + handleComplete + handleErrorWithRetryPrefix 尾部。
     */
    private void purgeBlankUserEvents(String sid) {
        if (sessionService == null || sessionRepository == null) {
            return;
        }
        List<SessionEvent> events = sessionService.getEvents(sid);
        List<SessionEvent> kept = new java.util.ArrayList<>(events.size());
        boolean changed = false;
        for (SessionEvent e : events) {
            Message m = e.getMessage();
            if (m instanceof UserMessage um && um.getText() != null && um.getText().isEmpty()) {
                changed = true;   // 续跑伪影 blank user：丢弃
            } else {
                kept.add(e);
            }
        }
        if (changed) {
            sessionRepository.replaceEvents(sid, List.copyOf(kept));
        }
    }

    /** 自尾部向前扫：会话内最后一条 UserMessage 文本是否已含 {@code <system-notice>}（形状②防双份的判据）。 */
    private boolean lastUserHasResumeNotice(String sid) {
        List<SessionEvent> events = sessionService.getEvents(sid);
        for (int i = events.size() - 1; i >= 0; i--) {
            Message m = events.get(i).getMessage();
            if (m instanceof UserMessage um) {
                return um.getText() != null && um.getText().contains("<system-notice>");
            }
        }
        return false;
    }

    /**
     * L1 桥接入口：只读 volatile {@link #activeTurnL1Sink}，null 直接 return（首回合前的防御）。
     * 真正的 turnId 比对发生在 submit 写入的 sink 闭包内（见该处注释）——本方法无参数可作比对。
     */
    void onL1Retry(int attempt, long backoffMs, String reason) {
        RetryReporter sink = activeTurnL1Sink;
        if (sink != null) {
            sink.report(attempt, backoffMs, reason);
        }
    }

    /**
     * L2 自身耗尽/穿透异常的解包：沿 cause 链剥掉包装集合（reactor 的 retryExhausted 或
     * StreamInterruptedException）到首个不属于集合的异常；SII message 为 null 无妨——文案拼接另取首个非空 message。
     */
    static Throwable unwrapL2(Throwable err) {
        Throwable cur = err;
        while (cur != null
                && (reactor.core.Exceptions.isRetryExhausted(cur) || cur instanceof StreamInterruptedException)) {
            Throwable cause = cur.getCause();
            if (cause == null) {
                return cur;
            }
            cur = cause;
        }
        return cur;
    }

    /** 失败文案前缀：全 0 → 空串（零重试错误原样直传，不得出现「已自动重试…仍失败」）。 */
    static String retryFailurePrefix(int l1Retries, int l2Retries) {
        if (l1Retries == 0 && l2Retries == 0) {
            return "";
        }
        return "已自动重试（传输 " + l1Retries + " 次/续跑 " + l2Retries + " 次）仍失败：";
    }

    /** 续跑退避纯函数：1s×2ⁿ 封顶 4s，与 Retry.backoff(2,1s) 同公式，jitter(0) 下与真实 delay 严格相等。 */
    static long resumeBackoffMs(int totalRetries) {
        return Math.min(1000L << Math.min(totalRetries, 2), 4000L);
    }

    /**
     * 终态错误入口：包装（而非复制）——前缀非空时构造 {@code RuntimeException(prefix + 根因串, err)} 后
     * 委托 {@link #handleError}（复用 log/onError/trim 三件事，禁止复制方法体）；前缀空则原样直传。
     * 入口对 CancellationException 直接 return（本 CE 全仓唯一生产者是 disposed 检查；防 composite 参数反序等
     * 未来改动打开 error-beats-cancel 窗口，Esc 后屏幕不得出现「⚠ 出错：回合已取消」）。末尾补一次 blank 清除。
     */
    private void handleErrorWithRetryPrefix(Throwable err, long turnId, int l1Retries, int l2Retries) {
        if (err instanceof CancellationException) {
            return;
        }
        String sid = sessionId;   // 快照（纪律同 trimDanglingToolCalls）：本方法跑在 reactive 线程，clearContext 会在
                                  // UI 线程换掉 volatile sessionId——末尾 purge 必须落在与 doOnError 同一会话上。
        String prefix = retryFailurePrefix(l1Retries, l2Retries);
        if (prefix.isEmpty()) {
            handleError(err, turnId);
        } else {
            handleError(new RuntimeException(prefix + rootCauseText(err), err), turnId);
        }
        // 耗尽失败路径的 blank 清除点：末轮若断在 FROM_TOOLS（blank 已随 before() 落库），这里清掉；
        // 否则末轮 blank 会因「tc落库→工具结果落库→断」而成中部事件永久残留。no-change 不写回，同 trim 模式。
        purgeBlankUserEvents(sid);
    }

    /** 根因串：沿 cause 链取首个非空 message，兜底类名（与 reasonOf 共用兜底规则，两套推导勿漂移）；不做宽截断。 */
    private static String rootCauseText(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                return t.getMessage();
            }
        }
        return err == null ? "" : err.getClass().getSimpleName();
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

    // ── 后台子 agent 门面（注册表是唯一并发真相源，本类只做转发 + 一处限幅） ──

    /**
     * 已结束、可送达、尚未消费的后台任务结果。
     *
     * <p><b>限幅就在这里做</b>：这是后台结果<b>进入会话的唯一入口</b>（自动送达与 /tasks 都经它），
     * 放在这里就绕不过去。放在调用方任何一处，都可能被另一条路径绕开——而绕开的后果是一个话痨
     * 子 agent 在没人看着的时候把上下文打满。
     *
     * <p>{@code backgroundResults} 为空（桩路径）时退化为不限幅：桩路径本就没有落盘目录可写。
     */
    @Override
    public List<SubmitHandler.BackgroundResult> completedBackgroundTasks() {
        if (backgroundRegistry == null) {
            return List.of();
        }
        List<SubmitHandler.BackgroundResult> out = new java.util.ArrayList<>();
        for (var t : backgroundRegistry.completedUnconsumed()) {
            String result = backgroundResults == null
                    ? t.result()
                    : backgroundResults.storeAndTruncate(t.taskId(), t.result());
            out.add(new SubmitHandler.BackgroundResult(t.taskId(), t.agentName(), t.description(),
                    result,
                    t.status() == io.github.javaside.springai.codetui.agent.background.BackgroundTask.Status.DONE));
        }
        return out;
    }

    /**
     * 取一次注册表快照交给 {@code BackgroundDigest}。
     *
     * <p><b>只取一次</b>：注册表方法都是 synchronized，取两次之间状态会变；
     * {@code BackgroundTask.consumed()} 是 public 的，一份 {@code all()} 足以筛出所有分组。
     */
    @Override
    public String backgroundDigestForContinue() {
        if (backgroundRegistry == null) {
            return "";     // 桩路径：没有后台子系统，也就没有可提醒的
        }
        return io.github.javaside.springai.codetui.agent.background.BackgroundDigest
                .forContinue(backgroundRegistry.all());
    }

    @Override
    public boolean markBackgroundConsumed(String taskId) {
        return backgroundRegistry != null && backgroundRegistry.markConsumed(taskId);
    }

    @Override
    public boolean killBackgroundTask(String taskId) {
        return backgroundRegistry != null && backgroundRegistry.kill(taskId);
    }

    /**
     * 终止全部后台任务（{@code /clear} 与退出共用这一个入口）。
     *
     * <p><b>两件事都得做</b>：{@code killAll()} 只改注册表里的状态，线程池里的任务照样在跑；
     * {@code restartBackground()} 才是真正把它们打断。只做前者的话面板上显示"已终止"而 CPU 上还在烧。
     *
     * <p><b>为什么是 restart 而不是 shutdown</b>（这一句是本方法存在的全部理由，别删）：
     * {@code ThreadPoolExecutor} 一旦 shutdown 就是终态、永不可复用，而
     * {@code SubagentRunner.enableBackground} 全仓只在装配期调一次（{@code AgentTools.build}）。
     * 于是在这里关池 = {@code /clear} 之后这个进程<b>再也派不出任何后台任务</b>，
     * 而模型只会读到「队列已满，等在跑的任务完成后重试」——队列其实是空的、池是死的，它会永远等下去。
     *
     * <p>退出走的是 {@link #shutdownBackground()}（真关池 + 有界 2s 收尾），不是这里。
     */
    @Override
    public void killAllBackgroundTasks() {
        if (backgroundRegistry != null) {
            backgroundRegistry.killAll();
        }
        if (subagentRunner != null) {
            subagentRunner.restartBackground();
        }
    }

    /**
     * 退出时的后台清理：终止全部任务 + 关闭线程池（终态，不重建，有界 2s）。
     *
     * <p><b>自己做 killAll，不要求调用方先调 {@link #killAllBackgroundTasks()}</b>——那个方法走
     * restart，会把在飞任务所在的池换成全新空池，随后这里的 awaitTermination 就等在一个空池上、
     * 0ms 返回，真正在跑的子 agent 一秒宽限都拿不到。把两件事合在这一个方法里，调用方就没有
     * "顺序写反"的机会。
     */
    @Override
    public void shutdownBackground() {
        if (backgroundRegistry != null) {
            backgroundRegistry.killAll();
        }
        if (subagentRunner != null) {
            subagentRunner.shutdownBackground();
        }
    }

    // ── MCP 管理门面（纯委托 McpRegistry；registry 缺失 = 无 MCP 支持） ──
    @Override
    public int connectingMcpCount() {
        return mcpRegistry == null ? 0 : mcpRegistry.connectingCount();
    }

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
    public List<ProviderModel> models() {
        if (registry != null) {
            return registry.allModels();
        }
        // registry==null 的旧单-client/测试桩回退路径：MODELS 都是 DeepSeek 模型。
        return MODELS.stream()
                .map(m -> new ProviderModel("deepseek", m.id(), m.label(), m.desc()))
                .toList();
    }

    @Override
    public String currentModel() {
        return registry != null ? registry.activeModelId() : model;
    }

    @Override
    public ModelCapabilities currentModelCapabilities() {
        return capabilitiesSnapshot();
    }

    @Override
    public String currentProviderId() {
        return registry != null ? registry.active().id() : "deepseek";
    }

    @Override
    public void selectModel(String providerId, String modelId) {
        if (registry != null) {
            registry.select(providerId, modelId);
            return;
        }
        for (ModelOption m : MODELS) {
            if (m.id().equals(modelId)) { this.model = modelId; return; }   // 旧路径仅接受已知模型
        }
    }

    @Override
    public io.github.javaside.springai.codetui.agent.thinking.ModelThinkingSettings thinkingSettings(
            String providerId, String modelId) {
        return registry != null ? registry.thinkingSettings(providerId, modelId) : null;
    }

    @Override
    public boolean saveThinkingSettings(String providerId, String modelId,
            io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig config) {
        return registry != null && registry.updateThinking(providerId, modelId, config);
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
     *
     * <p><b>以及插话队列</b>：同理，那些话是冲着旧会话说的。
     */
    @Override
    public void clearContext() {
        this.sessionId = SessionIds.newId();
        if (usageAccumulator != null) {
            usageAccumulator.reset();   // /clear 换新会话，缓存命中率从 0 重新累计
        }
        if (permissionEngine != null) {
            permissionEngine.clearSessionRules();
        }
        if (interjections != null) {
            // /clear 换了新会话：delivered/anchor 指向的旧会话已经不存在，留着会让下个回合
            // 的补历史插到一个对不上的位置。整体丢弃（未送达的也一并丢——用户主动清空了上下文）。
            interjections.drainForRefill();
        }
    }

    // ── 插话门面（队列是唯一事实来源，这里只转发；interjections==null 的桩路径全退化为无操作） ──

    @Override
    public void interject(String text) {
        if (interjections != null) {
            interjections.offer(text);
        }
    }

    @Override
    public int pendingInterjections() {
        return interjections == null ? 0 : interjections.pendingCount();
    }

    /**
     * 兜底出队用，<b>只取未送达的</b>。
     *
     * <p>与 {@link #takeBackInterjections} 委托到不同的队列方法，这一点不能合并：已送达那条归
     * {@link #persistInterjection} 补历史，被兜底出队抢走就会当成新回合再发一遍，模型看到同一句话两次。
     */
    @Override
    public List<String> takePendingInterjections() {
        return interjections == null ? List.of() : interjections.takePendingOnly();
    }

    /** Esc 取消时通吃（含已送达那条）：取消路径不跑补历史，不交还就凭空消失。 */
    @Override
    public List<String> takeBackInterjections() {
        return interjections == null ? List.of() : interjections.drainForRefill();
    }

    /**
     * 未送达插话的快照，供输入框上方的面板每次 render 读取。
     *
     * <p><b>非破坏性</b>——委托到 {@link Interjections#pendingSnapshot()} 而不是
     * {@code takePendingOnly()}。接错的话每次 render 都把队列清空一次，插话再也送不出去，
     * 而面板看上去还很正常（它读的就是刚被自己清掉的那份）。
     */
    @Override
    public List<String> pendingInterjectionTexts() {
        return interjections == null ? List.of() : interjections.pendingSnapshot();
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

    /**
     * 删一条规则（{@code /permissions} 面板）。落盘规则回写对应层文件、会话规则只摘内存，
     * 两者都由 {@link PermissionEngine#removeRule} 一并处理——面板不该知道规则存在哪。
     */
    @Override
    public boolean removePermissionRule(PermissionRule rule) {
        return permissionEngine != null && permissionEngine.removeRule(rule);
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
        // 分桶与总数出自同一次遍历（buckets.total()），保证 /context 上「总数 == 各分类之和」恒成立。
        SessionTokenEstimator.Buckets buckets =
                SessionTokenEstimator.estimateMessagesByType(sessionService.getMessages(sid), tokenCountEstimator);
        long tokens = buckets.total();
        VisionSnapshot vision = snapshotOf(visionModels, registry);
        TokenUsageAccumulator.Snapshot usage = usageAccumulator == null
                ? TokenUsageAccumulator.Snapshot.empty()
                : usageAccumulator.snapshot();
        return new ContextStats(events.size(), user, assistant, tool, other, tokens,
                AgentTools.autoCompactionThreshold(registry), AgentTools.contextWindow(registry),
                AgentTools.MAX_EVENTS_TO_KEEP, AgentTools.MANUAL_MAX_EVENTS_TO_KEEP,
                vision.images(), vision.tokens(),
                usage.cacheReadTokens(), usage.billedInputTokens(), usage.cacheHitPercent(),
                new ContextStats.TokenBreakdown(buckets.systemTokens(), buckets.userTokens(),
                        buckets.assistantTokens(), buckets.toolTokens()),
                systemPromptTokens);
    }

    /**
     * 当前<b>激活</b> provider 的上次兑现快照。无 registry / 无装饰器 / 该家没装饰器 → 空快照。
     *
     * <p>必须按激活 provider 取，不能随便取一个：每家 provider 一个装饰器、各记各的账，
     * 而 {@code /model} 可以跨家切换。取错家会报上一家的陈旧数字，且<b>看起来完全合理</b>
     * ——没有任何迹象提示这笔账根本不属于当前请求。
     *
     * <p>抽成包级静态纯函数是为了能单测「按激活取对了那一个」——造整个 CodingAgent 太贵。
     */
    static VisionSnapshot snapshotOf(java.util.Map<String, VisionMaterializingChatModel> models,
                                     ProviderRegistry registry) {
        if (models == null || registry == null) {
            return VisionSnapshot.EMPTY;
        }
        VisionMaterializingChatModel m = models.get(registry.active().id());
        return m == null ? VisionSnapshot.EMPTY : m.lastSnapshot();
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
        log.error("回合 {} 出错", turnId, err);
        listener.onError(turnId, err);
        // 报错也裁掉悬空 tool_calls 尾巴：既避免坏历史下轮 400，又保留已完成任务与计划以便 /continue 续跑。
        trimDanglingToolCalls();
    }

    private void handleComplete(long turnId) {
        persistInterjection();          // ⚠ 必须在 onTurnComplete 之前，见该方法注释
        // 最后一轮续跑成功后不再有 prepareResume：成功路径的 blank 伪影（FROM_TOOLS 轮 before() 落下的
        // 空 user）只能在这里清。onTurnComplete 之前完成，-c 恢复/压缩读取到的都是干净历史。
        String sid = sessionId;   // 快照（纪律同 trimDanglingToolCalls）：本方法跑在 reactive 线程，/clear 在 UI 线程换 volatile
        purgeBlankUserEvents(sid);
        listener.onTurnComplete(turnId);
    }

    /**
     * 把插话插进事件表：anchor 那条 tool 事件之后；anchor 为 null 或找不到 → 追加末尾。
     *
     * <p>追加末尾会让历史以 {@link UserMessage} 结尾，看着危险，但下个回合
     * {@link #foldTrailingUserIntoOutbound} 正好接住这个形状、把它折进出站文本，
     * 不会变成两条连续 user 被 DeepSeek 400 拒收。<b>不需要为此新写保护。</b>
     *
     * <p>纯函数，不碰仓库——便于单测直接断言位置（见 {@code InterjectionHistoryTest}）。
     */
    static List<SessionEvent> insertInterjectionForTest(List<SessionEvent> events, String sid,
                                                        String anchorToolCallId, String rawText) {
        List<SessionEvent> out = new java.util.ArrayList<>(events);
        out.add(insertIndex(events, anchorToolCallId),
                SessionEvent.builder().sessionId(sid)
                        .message(new UserMessage(InterjectingChatModel.wrapText(rawText)))
                        .build());
        return out;
    }

    /**
     * anchor 那条 tool 事件的下一个位置；anchor 为 null 或找不到 → 末尾。
     *
     * <p>从后往前找：同一个 tool call id 在历史里只会出现一次，但倒着找能在多轮工具的长历史上
     * 更快命中（插话的 anchor 总是最近那轮）。找不到通常是它已被 {@code sanitize} 裁掉。
     */
    private static int insertIndex(List<SessionEvent> events, String anchorToolCallId) {
        if (anchorToolCallId == null) {
            return events.size();
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).getMessage() instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm
                    && trm.getResponses().stream().anyMatch(r -> anchorToolCallId.equals(r.id()))) {
                return i + 1;
            }
        }
        return events.size();
    }

    /**
     * 回合末把「已送达模型、但尚未落库」的插话补进会话历史。
     *
     * <p>不补的话插话只活在那一次 {@code Prompt} 里：落盘后 {@code -c} 恢复、以及压缩之后，
     * 历史上就会出现一次「无来由的转向」——模型照着一句不存在的话改了方向。
     *
     * <p><b>必须在通知 listener 之前调用。</b>UI 的出队钩子靠 {@code !busy()} 放行，而 state 回 IDLE
     * 由 listener 事件驱动。放到通知之后，新回合的 {@code SessionMemoryAdvisor.before()} 会先写入
     * 新 user，本方法随后按 anchor 插入就会错位。<b>重排这两行不会报任何错</b>，只会在
     * {@code -c} 恢复后表现为消息顺序离奇。
     *
     * <p>本方法跑在 reactive 线程时 assistant 收尾消息<b>已经落库</b>（实测：{@code SessionMemoryAdvisor}
     * 先于 {@code doOnComplete}，{@code .call()} 与 {@code .stream()} 两条路径一致），
     * 故这里看到的历史形状就是 {@code … → tool → assistant(收尾)}，anchor 定位有意义。
     *
     * <p>{@code sid} 必须快照：本方法跑在 reactive 线程，{@code /clear} 会在 UI 线程换掉
     * volatile {@link #sessionId}（纪律同 {@link #trimDanglingToolCalls}）。
     *
     * <p>失败静默跳过：回合已经成功完成，不该因为一句插话没归档就变成错误。
     */
    private void persistInterjection() {
        if (interjections == null || sessionService == null || sessionRepository == null) {
            return;
        }
        Interjections.Delivered d = interjections.takeForHistory().orElse(null);
        if (d == null) {
            return;
        }
        String sid = sessionId;
        try {
            List<SessionEvent> out = insertInterjectionForTest(
                    sessionService.getEvents(sid), sid, d.anchorToolCallId(), d.text());
            sessionRepository.replaceEvents(sid, List.copyOf(out));
        } catch (RuntimeException ex) {
            log.info("插话补历史失败，跳过（回合已成功完成）", ex);
        }
    }
}

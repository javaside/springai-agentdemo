package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.background.BackgroundTaskListTool;
import io.github.javaside.springai.codetui.agent.llm.DynamicAuxChatModel;
import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.RetryPolicy;
import io.github.javaside.springai.codetui.agent.llm.RetryReporter;
import io.github.javaside.springai.codetui.agent.llm.RetryingStreamChatModel;
import io.github.javaside.springai.codetui.agent.llm.SessionIdStreamGuardAdvisor;
import io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig;
import io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry;
import io.github.javaside.springai.codetui.agent.background.BackgroundTaskTool;
import io.github.javaside.springai.codetui.agent.background.TaskResultStore;
import io.github.javaside.springai.codetui.agent.compaction.BoundedSummarizationCompactionStrategy;
import io.github.javaside.springai.codetui.agent.compaction.CalibrationState;
import io.github.javaside.springai.codetui.agent.compaction.ModelContextWindows;
import io.github.javaside.springai.codetui.agent.compaction.NotifyingCompactionStrategy;
import io.github.javaside.springai.codetui.agent.compaction.PreflightCompactionAdvisor;
import io.github.javaside.springai.codetui.agent.interjection.InterjectingChatModel;
import io.github.javaside.springai.codetui.agent.interjection.Interjections;
import io.github.javaside.springai.codetui.agent.goal.GoalEvaluationRunner;
import io.github.javaside.springai.codetui.agent.goal.GoalEvaluator;
import io.github.javaside.springai.codetui.agent.goal.GoalManager;
import io.github.javaside.springai.codetui.agent.mcp.McpRegistry;
import io.github.javaside.springai.codetui.agent.media.ArtifactGc;
import io.github.javaside.springai.codetui.agent.media.MediaArtifactStore;
import io.github.javaside.springai.codetui.agent.media.MediaExternalizingCallback;
import io.github.javaside.springai.codetui.agent.media.MediaReferencePreservingCompactionStrategy;
import io.github.javaside.springai.codetui.agent.media.SessionFileExternalizer;
import io.github.javaside.springai.codetui.agent.media.TextReferenceMediaHandler;
import io.github.javaside.springai.codetui.agent.media.ToolResultMediaHandler;
import io.github.javaside.springai.codetui.agent.media.ImageProfile;
import io.github.javaside.springai.codetui.agent.media.VisionBudget;
import io.github.javaside.springai.codetui.agent.media.VisionMaterializingChatModel;
import io.github.javaside.springai.codetui.agent.permission.PermissionConfig;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.prompt.MemoryPrompt;
import io.github.javaside.springai.codetui.agent.prompt.ProjectInstructions;
import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import io.github.javaside.springai.codetui.agent.seam.PlanApprovalBridge;
import io.github.javaside.springai.codetui.agent.seam.UserQuestionBridge;
import io.github.javaside.springai.codetui.agent.skill.ReloadableSkillTool;
import io.github.javaside.springai.codetui.agent.skill.SkillInfo;
import io.github.javaside.springai.codetui.agent.session.FileSessionRepository;
import io.github.javaside.springai.codetui.agent.subagent.SubagentLoader;
import io.github.javaside.springai.codetui.agent.subagent.SubagentRunner;
import io.github.javaside.springai.codetui.agent.subagent.SubagentSpec;
import io.github.javaside.springai.codetui.agent.subagent.SubagentTool;
import io.github.javaside.springai.codetui.agent.tools.BochaWebSearchTool;
import io.github.javaside.springai.codetui.agent.tools.PermissionCallback;
import io.github.javaside.springai.codetui.agent.tools.RenamedToolCallback;
import io.github.javaside.springai.codetui.agent.tools.TimeLimitedToolCallback;
import io.github.javaside.springai.codetui.agent.tools.TodoStaleReminder;
import io.github.javaside.springai.codetui.agent.tools.ToolEventCallback;
import io.github.javaside.springai.codetui.agent.tools.TurnToolLimitWiring;
import io.github.javaside.springai.codetui.agent.tools.ZhipuWebSearchTool;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.AutoMemoryTools;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springaicommunity.agent.utils.AgentEnvironment;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AgentTools —— 组装编码 Agent 用的 {@link ChatClient} 的工厂。
 *
 * <p>把社区工具（FileSystem/Shell/Grep/Glob/TodoWrite/SmartWebFetch）与三家搜索工具（博查/智谱/Brave，
 * 各自仅在配了 BOCHA_API_KEY / ZHIPU_API_KEY / BRAVE_API_KEY 时注册）用 {@link ToolEventCallback} 装饰后
 * 注册进 ChatClient，并配上 {@link SessionMemoryAdvisor} 会话记忆与 {@link AgentEnvironment} 环境系统提示。
 *
 * <p><b>网页获取（SmartWebFetch）</b>：{@link SmartWebFetchTool} 抓取网页→转 Markdown→用一个「裸」ChatClient
 * 按 prompt 做 AI 抽取（带 15 分钟缓存 / 重试）。这里关掉 domainSafetyCheck——它会去请求 {@code claude.ai}
 * 的域名信息 API，在本环境多半不可达、只会徒增延迟；本地编码 Agent 用不着这层校验。
 *
 * <p><b>会话记忆（spring-ai-session）</b>：取代原先的 {@code MessageChatMemoryAdvisor} 定长滑窗。
 * 事件溯源存储 + 「回合/token 感知」压缩——超过 token 阈值时用 LLM 滚动摘要把更早历史压成一条摘要事件，
 * 且压缩尊重回合边界、不会拆散某轮的 tool_call 与 tool_result。用文件仓库
 * （{@link FileSessionRepository}，落盘 {@code <root>/.codetui/sessions/}），进程重启后可加载、按项目隔离。
 *
 * <p><b>返回 {@link AgentRuntime}</b>：{@link #build} 除了 {@link ChatClient}，还暴露 {@link SessionService}
 * 与一份更激进的手动压缩策略（保留 20 事件），供上层 {@code /compact} 命令直接触发压缩。
 * <b>自动</b>（阈值触发）路径的策略用 {@link NotifyingCompactionStrategy} 包一层，使原本静默的压缩对 UI 可见；
 * <b>手动</b>路径的策略<b>不</b>包 Notifying——它由 {@code CodingAgent.runCompaction} 直接调用并自行上报事件，
 * 若再包装会重复上报同一次压缩。两条路径都再包一层
 * {@link MediaReferencePreservingCompactionStrategy}，把被摘要掉的图片引用逐字捞回来（否则 sha 路径
 * 一被 LLM 改写，图就再也寻址不到）。装配见 {@link #autoCompaction} / {@link #manualCompaction}。
 *
 * <p><b>为何不挂 conversation_search 工具</b>：0.5.0 的压缩是<b>销毁式</b>的——
 * {@code DefaultSessionService.compactWith} 只把压缩后的集合经 {@code replaceEvents} 覆盖写回，
 * 被压掉的 {@code archivedEvents} 从不落库（JDBC 仓库亦然：整会话 DELETE 后重插压缩集，无归档表）。
 * 故 {@code conversation_search} 只能搜到「摘要 + 保留窗口」这批存活事件，而它们本就已被 advisor 注入 prompt，
 * 工具边际价值极低，遂不注册。要「搜回压缩掉的老原文」需另一种范式（append-only 不压缩 + eventFilter 约束注入 + JDBC 持久化），
 * 非本类目标。
 *
 * <p><b>turnId 走 ThreadLocal</b>：TodoWriteTool 的 todoEventHandler 只收 {@link Todos}、拿不到 turnId，
 * 而 handler 是在工具 call 内<b>同线程同步</b>触发的，所以直接读
 * {@link ToolEventCallback#currentTurnId()}（当前正在执行工具的回合），不读实时 activeTurnId，
 * 取消/并发下不会串轮。因此 {@link #build} <b>没有</b> activeTurnId 参数。
 *
 * <p><b>安全边界</b>：<b>工具本身</b>没有任何强制目录边界——FileSystemTools/Shell/Grep/Glob 在技术上
 * 都<b>不</b>受 root 限制（见 {@code AgentToolsSecurityTest}）。曾给 FS 单设 allowedDirectory，
 * 但其余工具都能越界、这层边界形同虚设、反而制造「有沙箱」的假象，遂去掉。
 * 拦截改由<b>装饰链最外层</b>的 {@link PermissionCallback} 统一做（本类是三处装配点之一，
 * 另两处是 {@link #buildMemoryTools} 与 {@code McpRegistry.decorate}）：它按模式 + 规则 +
 * 内置危险检查判定放行 / 拒绝 / 人工审批。这是一道<b>护栏</b>而非沙箱——工具层依旧能越界，
 * 只是越界前会停下来问一次。
 */
public final class AgentTools {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentTools.class);

    private AgentTools() {
    }

    /**
     * 触发压缩的累计 token 阈值：超过即对更早历史做摘要压缩。
     * 上下文 1M（输出上限 384K），故把压缩当「偶尔兜底」而非常态税——约 40% 窗口才压，
     * 多数编码会话跑不到；仍距 1M 留 ~600k 给本轮响应 + 工具输出，避免某轮总量破 1M 报错。
     * 不变量：须明显 > MAX_EVENTS_TO_KEEP 个事件的 token 量，否则压缩压不到阈值以下、原地空转。可调。
     */
    static final int COMPACTION_TOKEN_THRESHOLD = 400_000;

    /** 旧单-client 测试路径的展示回退；生产路径按当前 provider/model 动态解析。 */
    static final long CONTEXT_WINDOW_TOKENS = 1_000_000L;

    private static final ModelContextWindows MODEL_CONTEXT_WINDOWS = ModelContextWindows.fromEnvironment();
    private static final double AUTO_COMPACTION_RATIO = 0.70d;
    private static final double COMPACTION_TARGET_RATIO = 0.55d;
    /** MediaReferencePreserving 在 base 预算完成后最多插入 20 条有界清单；提前留足 token，保证完整装饰链仍低于触发线。 */
    static final long MEDIA_MANIFEST_TOKEN_RESERVE = 16_000L;
    /** 摘要请求还含固定 system prompt 与角色封装；user chunk 只使用安全输入预算的一部分。 */
    private static final long SUMMARY_PROMPT_TOKEN_RESERVE = 4_000L;
    /** 摘要输出的显式上限（maxTokens=8192 之外的预留量）：乐观全量的成功判据是「输入预算内」，输出由再压缩与本地兜底兜住。 */
    private static final long SUMMARY_OUTPUT_RESERVE = 8_000L;
    /** 摘要请求的硬输出上限：无它时部分家的输出可膨胀到输入级，压缩产物反而顶破目标预算。 */
    private static final int SUMMARY_MAX_OUTPUT_TOKENS = 8192;

    static long contextWindow(ProviderRegistry registry) {
        return modelSnapshot(registry).windowTokens();
    }

    static long autoCompactionThreshold(ProviderRegistry registry) {
        return Math.max(32_000L, (long) (contextWindow(registry) * AUTO_COMPACTION_RATIO));
    }

    /**
     * 单次 {@link ProviderRegistry#activeRequestSelection()} 快照派生模型身份与窗口。
     *
     * <p><b>为什么必须单快照</b>：校准 key 与输入预算若分两次读注册表，{@code /model} 并发切换可在两读
     * 之间交错——把「A 家的区间」配「B 家的窗口」，学习与预算双双失真。{@code null} registry 走旧测试
     * 兜底窗口（行为不变）。
     */
    static BoundedSummarizationCompactionStrategy.ModelSnapshot modelSnapshot(ProviderRegistry registry) {
        if (registry == null) {
            return new BoundedSummarizationCompactionStrategy.ModelSnapshot(
                    "test:legacy", CONTEXT_WINDOW_TOKENS, SUMMARY_PROMPT_TOKEN_RESERVE + SUMMARY_OUTPUT_RESERVE);
        }
        ProviderRegistry.RequestSelection sel = registry.activeRequestSelection();
        long window = MODEL_CONTEXT_WINDOWS.resolve(sel.provider().id(), sel.modelId());
        return new BoundedSummarizationCompactionStrategy.ModelSnapshot(
                sel.provider().id() + ":" + sel.modelId(), window,
                SUMMARY_PROMPT_TOKEN_RESERVE + SUMMARY_OUTPUT_RESERVE);
    }

    /** 压缩时保持逐字原样的最近事件数（约最近 30 轮）；更早的被 LLM 滚动摘要吸收（压缩为销毁式，见类注释）。须 > overlapSize 且其 token 量远低于阈值。可调。 */
    static final int MAX_EVENTS_TO_KEEP = 120;

    /** 手动 /compact 的保留窗口：比自动的 120 激进得多，让「按需缩小上下文」真正生效。可调。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。 */
    public static final int MANUAL_MAX_EVENTS_TO_KEEP = 20;

    /**
     * <b>自动</b>压缩路径的装配：{@code Notifying( Preserving( base ) )}。
     *
     * <p>Notifying 必须在<b>最外层</b>：它上报的 {@code eventsRemoved} / {@code tokensEstimatedSaved}
     * 应当是「加了附件清单之后」的净效果。今天两个数字恰好都被 Preserving 原样透传，包反了也看不出差别；
     * 但一旦 Preserving 将来要把清单的开销记进账，包反的那份就会让 UI 报出偏乐观的数字。
     *
     * <p>{@code base}（真正做摘要的策略）由调用方传入，不在这里构造——这样装配级测试可以塞一个
     * 假 base 端到端验证「清单确实被插进去了」，不必起一个真 LLM。
     */
    static CompactionStrategy autoCompaction(CompactionStrategy base, AgentListener listener) {
        return new NotifyingCompactionStrategy(
                new MediaReferencePreservingCompactionStrategy(base), listener, "auto");
    }

    /**
     * <b>手动</b>（{@code /compact}）压缩路径的装配：{@code Preserving( base )}，<b>不</b>包 Notifying。
     *
     * <p>不包 Notifying 是刻意的——手动路径由 {@code CodingAgent.runCompaction} 直接调用
     * {@code sessionService.compact} 并拿到返回值自行上报生命周期事件，再包一层会把同一次压缩
     * （尤其是失败）重复上报。
     *
     * <p>但 Preserving <b>必须单独套上</b>：它和自动路径是两条独立的策略对象，只接自动那条的话，
     * 用户一按 {@code /compact} 图就全丢——这正是最容易漏、也最难在测试里发现的那种错。
     */
    static CompactionStrategy manualCompaction(CompactionStrategy base) {
        return new MediaReferencePreservingCompactionStrategy(base);
    }

    /** 会话记忆的默认用户 id（单会话 TUI，仅作归属占位）。 */
    private static final String DEFAULT_USER_ID = "code-tui-user";

    /** 记忆系统提示注入的 param 键；与 SYSTEM_TEMPLATE 里的 {AUTO_MEMORY} 占位符对应。 */
    private static final String AUTO_MEMORY_KEY = "AUTO_MEMORY";

    /** 项目指令注入的 param 键；与 SYSTEM_TEMPLATE 里的 {PROJECT_INSTRUCTIONS} 占位符对应。 */
    private static final String PROJECT_INSTRUCTIONS_KEY = "PROJECT_INSTRUCTIONS";

    /** 搜索指引注入的 param 键；与 SYSTEM_TEMPLATE 里的 {WEB_SEARCH_GUIDE} 占位符对应。 */
    private static final String WEB_SEARCH_GUIDE_KEY = "WEB_SEARCH_GUIDE";

    /** 任务清单纪律段注入的 param 键；与 SYSTEM_TEMPLATE 里的 {TODO_DISCIPLINE} 占位符对应。 */
    private static final String TODO_DISCIPLINE_KEY = "TODO_DISCIPLINE";

    /**
     * 任务清单纪律段（注入系统提示词，见 {@link #SYSTEM_TEMPLATE}）——把「更新清单」绑定到<b>具体完成事件</b>。
     *
     * <p><b>为什么不写成「记得更新清单」</b>：真机取证（2026-10-07-todowrite-not-updated-root-cause）
     * 度量了控制器做出完成标记时顺手更新清单的比例——非 SDD 会话 0.63、SDD 会话 0.37，
     * 而失败会话里完成动作被更具体的东西占掉（skill 的台账行有格式模板、委派让「完成」发生在
     * 子 agent 内、长流程文本比一行工具纪律更近）。笼统要求正是被挤掉的那一方，
     * 所以要写清<b>在哪些事件之后、改哪两个 status</b>，让动作与事件一一对应、照做即对。
     *
     * <p><b>不得含花括号</b>：本段作为 StringTemplate 的 param 值注入，正文出现 {@code {}}
     * 会炸掉整个系统提示渲染（同 {@code coAuthorGuide} 的约束，有测试钉守）。
     */
    static final String TODO_DISCIPLINE = """
            - 任务清单纪律（TodoWrite）：任务含 3 个以上步骤、或用户要求你组织任务时，先用 TodoWrite 建清单再执行；同一时间只允许一项 in_progress，开工前先标 in_progress。
            - 更新清单绑定在「完成事件」上，不要等到想起来了再补：每完成一个任务、每次 git 提交、每写完一条台账或进度记录之后，先调 TodoWrite 把已完成项改成 completed、把下一项改成 in_progress，再继续下一步。委派的子 agent 返回并通过审查 = 一个任务完成，同样先更新清单。
            - 任务面板是用户看到进度的唯一渠道：长时间不更新，用户看到的就是一个冻住的假进度。""";

    /**
     * 提交署名指引注入的 param 键；与 SYSTEM_TEMPLATE 里的 {CO_AUTHOR_GUIDE} 占位符对应。
     * <b>默认关闭</b>：未配置 {@code CODETUI_CO_AUTHOR} 时该段渲染为空串，模型看不到指引、也不会追加尾注。
     */
    private static final String CO_AUTHOR_GUIDE_KEY = "CO_AUTHOR_GUIDE";

    /**
     * 权限模式提示注入的 param 键；与 SYSTEM_TEMPLATE 里的 {PERMISSION_MODE} 占位符对应。
     *
     * <p>公开可见（与上面几个 private 键不同）：模式随 Shift+Tab 运行期变化，
     * 真实值由 {@code CodingAgent.submit} 每回合覆盖，装配期只填空串占位。
     */
    public static final String PERMISSION_MODE_KEY = "PERMISSION_MODE";

    /** Brave 工具的注册名——库版是 {@code WebSearch}，与博查工具撞名，必须改写。 */
    static final String BRAVE_TOOL_NAME = "BraveWebSearch";

    /**
     * Brave 默认条数。比博查的 8 保守：Brave 免费档 2000 次/月，更该省。
     *
     * <p><b>它只约束网页结果</b>：库版 {@code parseResults} 还会把 Brave 响应里的 videos 段一并解析进来，
     * 不受 {@code count} 限制（实测 count=3 → 3 条网页 + 6 条视频）。{@code parseResults} 是私有的，
     * 本项目改不了；要滤掉视频噪音只能在工具返回值上再包一层解析。
     */
    static final int BRAVE_DEFAULT_COUNT = 5;

    /** Brave 单次条数上限。 */
    static final int BRAVE_MAX_COUNT = 20;

    /** Brave 总超时。库版自建 RestClient 不暴露 requestFactory，只能在工具层兜（见 TimeLimitedToolCallback）。 */
    private static final java.time.Duration BRAVE_TIMEOUT = java.time.Duration.ofSeconds(20);

    /**
     * 替换库版 Brave 工具的描述。库里那段写死了「Allows Claude to search」（串了别家产品名）
     * 和「Web search is only available in the US」（实测国内直连可达，这句是错的），都不适用。
     * 这里改成中文，并写明与 BochaWebSearch 的分工，以及 allowedDomains 那个配额坑。
     */
    private static final String BRAVE_DESCRIPTION = """
            用 Brave 搜索引擎搜索互联网，返回网页标题、网址与简短描述。

            用法：
            - 主打英文内容：英文技术文档、GitHub issue、Stack Overflow、英文新闻优先用它。
              中文内容、国内站点、中文技术社区请改用 BochaWebSearch。
            - 它只返回简短描述，不含长摘要。需要网页原文细节时，把结果里的网址交给 webFetch 抓取。
            - 不要用 allowedDomains 限定域名：它是拿到结果之后在客户端过滤的，被过滤掉的结果照样
              消耗了配额条数。想限定站点请把 site:example.com 直接写进搜索词。
            - 搜索最新资料时在搜索词里带上年份，例如「React documentation 2026」。
            - 引用了搜索结果，请在回答末尾用 markdown 链接列出实际参考的网址（Sources）。
            """;

    /**
     * 系统提示：把自己定位成编码 Agent。内嵌 3 个 {@link AgentEnvironment} 占位符
     * （键名与下面 {@code .param(...)} 一致：ENVIRONMENT_INFO / GIT_STATUS / AGENT_MODEL）。
     * 不注入知识截止——DeepSeek 无精确公开值，编一个只会误导模型自述。诚实声明全线工具都无强制目录边界。
     */
    private static final String SYSTEM_TEMPLATE = """
            你是一个运行在终端里的中文编码助手（coding agent），帮助用户在当前项目里读写代码、排查问题、完成开发任务。

            工作方式：
            - 动手改代码前，先用 Grep / Glob / read 把相关文件和上下文看清楚，理解现状再动手，不要臆测。
            - 每次动手改代码、新增或修改功能行为之前，先看 Skill 工具描述里的可用技能清单：
              若清单里有覆盖当前任务的技能（尤其规范「动手前」流程的），先调用 Skill 读取其完整指令并遵循；
              「任务简单」不构成跳过理由，清单里没有匹配项时才按常规方式工作。
            {TODO_DISCIPLINE}
            - 修改文件后，用 Shell 运行构建 / 测试 / 检查命令来验证改动确实生效，再给出结论。
            - 一次要产出大段内容（整篇文档、长报告、大文件全量重写）时，必须分批输出：先 Write 写入第一部分，
              再用 Edit 逐部分追加，单次工具调用的内容控制在两百行左右，不要试图一次性吐出整篇万字长文。
              原因：巨型工具调用的参数在流式聚齐之前客户端收不到任何数据，生成耗时超过流空闲超时
              （默认 300 秒）就会被判定为流卡死并反复重试，回合永远无法完成。
            {CO_AUTHOR_GUIDE}
            - 需要项目之外的信息（外部文档、库用法、报错含义等）时，用 webFetch 传入网址和你要抽取的问题来获取；
              它只读网页、不能登录或执行 JS。别凭记忆臆断外部事实。
            - 当需求含糊、动手前关键口径或方案未定、或需要用户在多个实现方案之间拍板时，用 AskUserQuestionTool 向用户提问（一次 1–4 个问题，
              每问 2–4 个选项，可单选或多选）；不要在信息不足时自行臆测方向。
            - 回答简洁，聚焦用户的目标本身。
            - 用户是在终端里读你的回复，终端渲染不了图片：不要写 ![说明](路径) 这类 markdown 图片语法，
              用户看到的只会是一串原始文本。需要指向一张图时直接给出路径，并用文字讲清画面上有什么——
              图你看得见，用户看不见。
            {WEB_SEARCH_GUIDE}

            关于工具访问边界：
            - 当前项目根目录是默认工作目录，不是强制访问边界；任务需要时，可以使用绝对路径访问其他项目、系统临时目录及用户明确指定的文件。
            - 所有文件读写、Shell 与跨目录操作统一服从权限引擎、内置安全底线及用户审批结果；权限层要求确认时必须先获得批准，不要绕过或规避权限检查。
            - 操作范围应以完成用户任务所必需为限，不主动读取、修改或删除无关文件。

            <environment_info>
            {ENVIRONMENT_INFO}
            </environment_info>

            <git_status>
            {GIT_STATUS}
            </git_status>

            当前模型：{AGENT_MODEL}。

            {AUTO_MEMORY}

            {PROJECT_INSTRUCTIONS}

            {PERMISSION_MODE}
            """;

    /**
     * 组装编码 Agent 的 ChatClient。仅做装配，不发起任何网络请求，也不要求有效的 API key。
     *
     * @param registry provider 注册表（每个可用 provider 各建一个 ChatClient；auxClient 与系统提示的模型名取激活 provider）
     * @param root     项目根目录（Grep/Glob 的默认工作目录 + 会话/记忆/技能等落盘基准；<b>非</b>任何工具的强制边界）
     * @param listener 工具 / Todo 事件出口
     *                 <p>注：conversationId（会话记忆）由 {@code CodingAgent.submit} 每次请求传入，
     *                 不在装配期绑定，故此处不需要 sessionId 参数。
     * @param mcpRegistry 运行期 MCP 中枢（可空）。MCP 工具<b>不</b>烧入 defaultTools——改由
     *                 CodingAgent 每回合 {@code .tools(mcpRegistry.activeTools())} 快照注入、
     *                 SubagentRunner 经 {@code effectiveTools} 拼接注入，使 /mcp 的启停即时生效。
     *                 registry 自行完成 MCP 工具的装饰（PermissionCallback + ToolEventCallback + 媒体外置），此处不再装饰。
     * @param permissionEngine 权限引擎。<b>由调用方创建并传入</b>，因为它必须早于 {@code McpRegistry.init}
     *                 存在（MCP 工具在发现时就要被装饰），而 init 又早于本方法。三处装配点
     *                 （本装饰循环 / {@link #buildMemoryTools} / {@code McpRegistry.decorate}）
     *                 共用这一个实例——不共用则 Shift+Tab 切模式只对其中一批工具生效。
     */
    public static AgentRuntime build(ProviderRegistry registry, Path root, AgentListener listener,
                                      McpRegistry mcpRegistry, PermissionEngine permissionEngine) {
        return build(registry, root, listener, mcpRegistry, permissionEngine, StreamRetryConfig.fromEnv());
    }

    /**
     * 向后兼容重载：goal 未装配（{@link AgentRuntime#goalManager()} 为 null），其余同
     * {@link #build(ProviderRegistry, Path, AgentListener, McpRegistry, PermissionEngine, StreamRetryConfig, GoalManager)}
     * ——retryConfig 仍由调用方显式注入（为什么必须注入见彼处 javadoc，逐字相同）。
     *
     * <p><b>null 的语义</b>：goal 循环状态机<b>由调用方创建并传入</b>（生产装配只有
     * {@code CodeTuiApplication} 一处，它自己构造后经第 7 参传入）；本重载保持旧签名源兼容，
     * 旧调用点（测试为主）不破、runtime 其余组件照常，只是没有 goal 循环。
     *
     * @param retryConfig L1/L2 计费总闸（CODETUI_STREAM_RETRY：all/l1/l2/off），见 {@link StreamRetryConfig}
     */
    public static AgentRuntime build(ProviderRegistry registry, Path root, AgentListener listener,
                                      McpRegistry mcpRegistry, PermissionEngine permissionEngine,
                                      StreamRetryConfig retryConfig) {
        return build(registry, root, listener, mcpRegistry, permissionEngine, retryConfig, null);
    }

    /**
     * 生产装配（全参）：与 6-arg 兼容重载的唯一差别是多第 7 参 {@code goalManager}。
     *
     * <p><b>为什么 retryConfig 必须注入、而不是在 build 内读环境变量</b>：测试无法安全修改
     * {@code System.getenv}，而开发者本机真设了 {@code CODETUI_STREAM_RETRY} 会让链序断言随机红。
     * 注入重载让守卫测试逐档断言开关语义（off/l2 → 不包装直通，l1/all → 包 RetryingStreamChatModel）；
     * 默认重载走 {@link StreamRetryConfig#fromEnv()}（生产入口 {@code CodeTuiApplication} 调本
     * 7-arg 重载即此路径）。
     *
     * @param retryConfig L1/L2 计费总闸（CODETUI_STREAM_RETRY：all/l1/l2/off），见 {@link StreamRetryConfig}
     * @param goalManager goal 循环状态机，<b>由调用方创建并传入</b>——build 不新建、只原样透传进
     *                    {@link AgentRuntime#goalManager()}，与 View goal 槽/CodingAgent（Task 9 的
     *                    bindGoal）共享同一实例，另建一个等于 UI 永远看不到真相（同 interjections 先例）。
     *                    <b>null = goal 未装配</b>：兼容重载（5/6 参）一律传 null，runtime 其余组件照常，
     *                    只是没有 goal 循环；生产装配只有 {@code CodeTuiApplication} 一处传真实例。
     */
    public static AgentRuntime build(ProviderRegistry registry, Path root, AgentListener listener,
                                      McpRegistry mcpRegistry, PermissionEngine permissionEngine,
                                      StreamRetryConfig retryConfig, GoalManager goalManager) {
        java.util.Objects.requireNonNull(permissionEngine, "permissionEngine 不可为 null：漏传等于全线无权限层");
        // 不设 allowedDirectory：沙箱是库的 opt-in 特性，空列表即放行任何路径
        // （见 FileSystemTools.validateAllowedAccess：allowedDirectories.isEmpty() → 直接返回放行）。
        // 全线工具（Shell/Grep/Glob）本就不受 root 强制限制，单给 FS 设边界并无实际安全意义，
        // 反而制造「FS 有沙箱」的假象；遂与其余工具一致，统一靠系统提示自律约束。
        FileSystemTools fs = FileSystemTools.builder().build();
        ShellTools sh = ShellTools.builder().build();
        GrepTool grep = GrepTool.builder().workingDirectory(root).build();
        GlobTool glob = GlobTool.builder().workingDirectory(root).build();
        // 任务面板过期提醒：装配期建唯一实例，下面 7 处装饰点与 /clear 重置（wireTodoReminder→CodingAgent）
        // 共用同一份——另建一个等于装饰链在计数、/clear 清的是另一份，两边永不相交。
        TodoStaleReminder todoReminder = TodoStaleReminder.fromEnv();
        TodoWriteTool todo = TodoWriteTool.builder()
                // turnId/taskId 均走 ThreadLocal（handler 在工具 call 内同步触发），不读实时 activeTurnId。
                // taskId 区分层级：null=控制器计划 todo（任务面板）；非空=子 agent 内部 todo（todo 面板）。
                .todoEventHandler(todos -> {
                        listener.onTodoUpdated(ToolEventCallback.currentTurnId(),
                                ToolEventCallback.currentTaskId(), toLines(todos));
                        // 控制器分支同步喂提醒器：刷新快照 + 复位计数；子 agent 内部 todo 不上面板，不喂。
                        if (ToolEventCallback.currentTaskId() == null) {
                            todoReminder.onControllerTodoWritten(todos.todos());
                        }
                })
                .build();

        // 「裸」ChatClient（同模型、无工具、无记忆 advisor）：一份复用给两处内部 LLM 调用——
        // ① SmartWebFetch 的网页内容 AI 抽取；② 会话历史的滚动摘要。都不能带工具/记忆，否则会递归触发工具或记忆循环。
        // 底层用 DynamicAuxChatModel：每次调用实时解析激活 provider/模型，使这两处跟随 /model 切换
        // （否则绑死在启动那家，切换后仍打到旧家——见 DynamicAuxChatModel 类注释）。
        // <b>不要在这一行往里塞装饰器</b>——尤其不要塞视觉兑现（原因见 auxChatModel 的 javadoc：
        // 摘要含引用块，包上等于每次自动压缩静默变成视觉请求）。要动就去改 auxChatModel，
        // 那里有一条守卫测试等着（AuxClientNotVisionWrappedTest）。
        ChatClient auxClient = ChatClient.builder(auxChatModel(registry)).build();

        // 网页获取工具：抓取→转 Markdown→按 prompt AI 抽取。关掉 domainSafetyCheck（见类注释）。
        SmartWebFetchTool webFetch = SmartWebFetchTool.builder(auxClient)
                .domainSafetyCheck(false)
                .maxContentLength(150_000)   // v4-flash 上下文 1M，抽取前允许更长原文
                .maxRetries(3)
                .build();

        // 技能（Skill）：可重载代理，扫描六层技能来源（用户/项目 × .codetui/.claude/.agents，见 SkillCatalog）。
        // 与一次性 SkillCatalog 不同，这里<b>始终注册</b>该代理——即便当前零技能，也保留槽位，
        // 使运行中新增 SKILL.md 经 /reload 能被热加载（见 ReloadableSkillTool 类注释）。
        ReloadableSkillTool reloadableSkill = new ReloadableSkillTool(root);

        // 反问工具：QuestionHandler 阻塞工具线程、等 UI 经 onQuestionAsked → responder 应答（见 UserQuestionBridge）。
        AskUserQuestionTool askTool = AskUserQuestionTool.builder()
                .questionHandler(new UserQuestionBridge(listener))
                .answersValidation(true)   // UI 顺序问询保证答案完整，故校验开着也不会触发异常
                .build();

        // org.springframework.ai.support.ToolCallbacks（spring-ai-model）：@Tool 对象转 ToolCallback。
        // 数量不固定：搜索工具是条件注册（BOCHA_API_KEY / ZHIPU_API_KEY 配了才有；Brave 已是
        // ToolCallback 走 all 段那条路），故用 rawTools 列表而非定长参数。
        // Skill 工具本身已是 ToolCallback（非 @Tool 对象），单独追加进列表；随后统一用 ToolEventCallback 装饰，
        // 使「技能被调用」也在 TUI 显示为一行工具活动。
        // TodoWrite 直用库工具。历史：0.10.0 时代其 @Tool 入参是包装记录 Todos（唯一字段也叫 todos），
        // Spring AI 按 schema 再包一层 → 双层 {"todos":{"todos":[...]}}，模型（尤其非 Claude 系）几乎必然
        // 塌成单层导致 MismatchedInputException，故本项目曾用 TodoWriteToolAdapter 摊平。上游 0.13.0
        // (#74) 已把签名改为 todoWrite(List<TodoItem>)——修的正是这个坑，适配层自此冗余删除。
        ToolCallback todoCallback = ToolCallbacks.from(todo)[0];

        // 网络搜索（博查）：BOCHA_API_KEY 配了才注册。没配则工具根本不存在——模型看不到、
        // 也不会去调一个不存在的工具（系统提示的搜索指引段同步为空串，见 Task 7）。
        BochaWebSearchTool webSearch =
                createWebSearchTool(System.getenv("BOCHA_API_KEY"), System.getenv("BOCHA_SEARCH_COUNT"));

        // Brave 搜索（库版 BraveWebSearchTool）：BRAVE_API_KEY 配了才注册。与博查共存，
        // 由模型按内容语言自选（分工写在各自的工具描述里）。
        ToolCallback braveWebSearch =
                createBraveWebSearchTool(System.getenv("BRAVE_API_KEY"), System.getenv("BRAVE_SEARCH_COUNT"));

        // 智谱搜索（第三家，国内向）：ZHIPU_API_KEY 配了才注册，ZHIPU_SEARCH_ENABLED=0 可显式关
        // （key 与 LLM provider 共用，存在性表达不了「只要模型、不要按次计费的搜索」，见 zhipuSearchWanted）。
        // 与博查/Brave 共存，分工与冗余关系写在系统提示指引段（webSearchGuide）。
        ZhipuWebSearchTool zhipuWebSearch = zhipuSearchWanted(
                System.getenv("ZHIPU_API_KEY"), System.getenv("ZHIPU_SEARCH_ENABLED"))
                ? createZhipuWebSearchTool(
                        System.getenv("ZHIPU_API_KEY"),
                        System.getenv("ZHIPU_SEARCH_COUNT"),
                        System.getenv("ZHIPU_SEARCH_ENGINE"))
                : null;

        List<Object> rawTools = new ArrayList<>(List.of(fs, sh, grep, glob, webFetch, askTool));
        if (webSearch != null) {
            rawTools.add(webSearch);
        }
        if (zhipuWebSearch != null) {
            rawTools.add(zhipuWebSearch);   // @Tool 对象，与博查同路（统一走装饰链）
        }
        List<ToolCallback> all = new ArrayList<>(Arrays.asList(
                ToolCallbacks.from(rawTools.toArray())));
        all.add(todoCallback);      // 薄适配器版 TodoWrite（名仍为 "TodoWrite"）
        all.add(reloadableSkill);   // 始终注册可重载 Skill 代理（支持运行期 /reload 从零热加载）
        if (braveWebSearch != null) {
            all.add(braveWebSearch);   // 已是 ToolCallback（非 @Tool 对象），故不进 rawTools
        }

        // MCP 工具不并入此列表：由 McpRegistry 自行装饰，CodingAgent/SubagentRunner 每回合取快照注入（见 build javadoc）。

        // 媒体外置（路径①）：装饰循环前构造一次 store + handler，供每个工具的装饰器共享。
        MediaArtifactStore mediaStore =
                new MediaArtifactStore(root.resolve(".codetui").resolve("artifacts"), root);
        // 启动清理：截图循环会让 artifacts 迅速膨胀，而该目录按项目共享、跨会话累积、
        // /clear 也不清。不做引用扫描（要遍历所有会话文件，出错面大得多）——误删旧图
        // 只会让模型 Read 拿到「文件不存在」，可恢复。
        // 只在这里扫一次：McpRegistry 指向同一个目录，两处都加等于重复扫。
        ArtifactGc.sweep(root.resolve(".codetui").resolve("artifacts"), ArtifactGc.DEFAULT_MAX_BYTES);
        ToolResultMediaHandler mediaHandler = new TextReferenceMediaHandler();
        // 视觉预算的唯一实例：既给出站兑现（VisionMaterializer）判定额度，也让工具执行期
        // （MediaExternalizingCallback）在读图时知道额度是否已尽——后者缺了就出现
        // 「额度已耗尽，Read 结果仍写着 not_in_view/『Read 一次就能看』」的假话，
        // 模型据此反复 Read 仍看不到，只能答「看不见」。两处必须共用同一实例，各建一个则永不相等。
        VisionBudget visionBudget = new VisionBudget();
        // 媒体外置（路径②）：回合间（CodingAgent.submit 开头）把过往「非文本文件」读取结果换成引用（文本文件不动）。
        SessionFileExternalizer fileExternalizer = new SessionFileExternalizer(root);

        // 权限拦截包在<b>最外层</b>（见 PermissionCallback 类注释：放内层会让被拒的调用先在 TUI 显示成
        // 「工具开始运行」）。装饰顺序自外向内：PermissionCallback → ToolEventCallback → 媒体外置 → 真实工具。
        ToolCallback[] decorated = new ToolCallback[all.size()];
        ToolCallback decoratedSkillTool = null;   // 手动 /skill 路径复用同一个被装饰实例（事件/返回与自动路径一致）
        for (int i = 0; i < all.size(); i++) {
            decorated[i] = new PermissionCallback(new ToolEventCallback(
                    new MediaExternalizingCallback(all.get(i), mediaStore, mediaHandler, root, visionBudget),
                    listener, todoReminder),
                    permissionEngine, listener);
            if (all.get(i) == reloadableSkill) {
                decoratedSkillTool = decorated[i];   // 记住 Skill 代理装饰后的实例（供手动 /skill 复用）
            }
        }

        // 项目指令（AGENTS.md，人手写、提交入库）：渲染一次供主/子 agent 共用；无文件则为空段。
        String projectInstructions = ProjectInstructions.load(root);

        // 子 agent（Task 工具）：SubagentRunner 复用「已装饰、带边界」的工具列表 decorated；
        // Task 工具本身也用 ToolEventCallback 装饰，故委派本身在主流显示为一行。
        java.util.List<ToolCallback> decoratedList = java.util.List.of(decorated);
        int subagentConcurrency = resolveSubagentConcurrency();
        // permissionEngine::mode 是「实时」来源，不是快照：子 agent 的模式提示段每次派发现取现算，
        // 使 Shift+Tab 切档在下一次委派即生效（见 SubagentRunner.effectiveSystemPrompt）。
        SubagentRunner subagentRunner = new SubagentRunner(registry, decoratedList, listener,
                projectInstructions, subagentConcurrency, mcpRegistry, permissionEngine::mode);
        java.util.Map<String, SubagentSpec> subagentSpecs = SubagentLoader.loadBuiltins();

        // 后台子 agent 子系统：注册表（进程内、不落盘）+ 结果限幅落盘 + 常驻线程池。
        // 必须在建 Task / ParallelTasks 之前接上：那两个工具的后台分支拿到的就是这里的派发器引用，
        // 不调 enableBackground 的话模型传 run_in_background=true 只会得到「后台模式不可用」。
        BackgroundTaskRegistry backgroundRegistry = new BackgroundTaskRegistry(64);
        TaskResultStore backgroundResults = new TaskResultStore(root);
        subagentRunner.enableBackground(backgroundRegistry, resolveBackgroundConcurrency());

        ToolCallback taskTool = SubagentTool.create(subagentSpecs, registry.allModels(),
                (spec, prompt, desc, turnIgnored) ->
                        // 真实 parentTurnId 从 ThreadLocal 取（Task 工具被 ToolEventCallback 装饰，call 时已压入）
                        subagentRunner.run(spec, prompt, desc, ToolEventCallback.currentTurnId()),
                // 后台派发不带 turnId：后台任务没有归属回合（见 SubagentRunner.runBackgroundBody 传 -1）。
                subagentRunner::runInBackground);
        // Task / ParallelTasks 也包一层：它们类别是 INTERNAL、恒放行，包一层零行为差异，
        // 只为「所有工具走同一条链」——否则以后给它们加类别时会漏掉这两个。
        ToolCallback decoratedTaskTool =
                new PermissionCallback(new ToolEventCallback(taskTool, listener, todoReminder),
                        permissionEngine, listener);

        // 批量 ParallelTasks 工具：并发执行多个独立子 agent。parentTurnId 在工具线程（fan-out 前）从 ThreadLocal 取，
        // 再由 runAll 显式传入每个子任务闭包（子线程不读 ThreadLocal）。
        ToolCallback parallelTool = SubagentTool.createParallel(subagentSpecs, registry.allModels(),
                (dispatches, turnIgnored) ->
                        subagentRunner.runAll(dispatches, ToolEventCallback.currentTurnId()),
                subagentRunner::runInBackground);
        ToolCallback decoratedParallelTool = new PermissionCallback(
                new ToolEventCallback(parallelTool, listener, todoReminder), permissionEngine, listener);

        // 插话队列：UI 忙时投递，ChatModel 装饰层在下一次调用时取走随 prompt 送达。
        // 所有 provider 共用一个实例——插话与用哪家模型无关，切模型不该把没送出去的话弄丢。
        //
        // <b>建在这里而不是挨着 ChatClient 那段</b>：下面的 TaskOutput 也要它。那个工具的
        // block=true 分支会在主 agent 工具线程上一直等（最长 300 秒），期间主 agent 不发模型调用，
        // 而模型调用是插话的唯一送达点——它必须问得到「此刻有没有人在等着说话」才能让路。
        Interjections interjections = new Interjections();

        // TaskOutput：<b>仅主 agent</b>——不进 decoratedList（照记忆工具/ExitPlanMode 的既有做法）。
        // 子 agent 拿不到 Task，自然也没有属于自己的后台任务；给了它只会让它去捞别人的结果。
        // 装饰层数与 Task / ParallelTasks 一致（不包 MediaExternalizingCallback：返回值是纯文本）。
        ToolCallback decoratedTaskOutputTool = new PermissionCallback(
                new ToolEventCallback(
                        BackgroundTaskTool.create(backgroundRegistry, backgroundResults,
                                resolveTaskOutputTimeout(), () -> interjections.pendingCount() > 0),
                        listener, todoReminder),
                permissionEngine, listener);

        // ListTasks：同样<b>仅主 agent</b>——理由与 TaskOutput 逐字相同（子 agent 没有属于自己的后台任务）。
        ToolCallback decoratedListTasksTool = new PermissionCallback(
                new ToolEventCallback(BackgroundTaskListTool.create(backgroundRegistry), listener, todoReminder),
                permissionEngine, listener);

        // 长期记忆工具：仅主 agent 拥有——不进 decoratedList，避免子 agent 写长期记忆。
        ToolCallback[] memoryDecorated = buildMemoryTools(root, listener, permissionEngine, todoReminder);

        // ExitPlanMode：计划模式的唯一出口。同样<b>仅主 agent</b>——不进 decoratedList（照记忆工具的既有做法）：
        // 子 agent 没有自己的模式（继承主会话），提交计划只属于主 agent；给了它反而会让子 agent 替用户切模式。
        // 与其它工具一样被两层装饰——ToolEventCallback 是 currentTurnId() 的来源，不装饰的话桥里拿到的
        // turnId 是错的，请求会被 UI 当「迟到」直接丢弃，工具线程随之永久 park。
        // 不包 MediaExternalizingCallback：返回值是纯文本，没有媒体可外置（与 Task/ParallelTasks 同）。
        PlanApprovalBridge planBridge = new PlanApprovalBridge(listener, permissionEngine::setMode);
        ToolCallback decoratedExitPlanTool = new PermissionCallback(
                new ToolEventCallback(PlanApprovalBridge.exitPlanModeTool(planBridge), listener, todoReminder),
                permissionEngine, listener);

        // 主 agent 工具集 = 原装饰工具 + 记忆工具（仅主 agent）+ ExitPlanMode（仅主 agent）+ Task + ParallelTasks + TaskOutput + ListTasks
        // 注意：ParallelTasks / TaskOutput / ListTasks 仅给主 agent，不进 decoratedList，故子 agent 不能再派并行子 agent（禁递归 fan-out）、
        // 也拿不到、看不到别人的后台任务。
        // ⚠ 尾部五个下标是「相对末尾」的：往这里加工具必须同时改数组长度<b>和每一个既有下标</b>——
        // 漏改不会编译失败，只会让某个工具静默变成 null 或被覆盖，运行期才炸且报错离原因很远。
        Object[] toolsWithTask = new Object[decorated.length + memoryDecorated.length + 5];
        System.arraycopy(decorated, 0, toolsWithTask, 0, decorated.length);
        System.arraycopy(memoryDecorated, 0, toolsWithTask, decorated.length, memoryDecorated.length);
        toolsWithTask[toolsWithTask.length - 5] = decoratedExitPlanTool;
        toolsWithTask[toolsWithTask.length - 4] = decoratedTaskTool;
        toolsWithTask[toolsWithTask.length - 3] = decoratedParallelTool;
        toolsWithTask[toolsWithTask.length - 2] = decoratedTaskOutputTool;
        toolsWithTask[toolsWithTask.length - 1] = decoratedListTasksTool;

        // 事件溯源会话记忆：文件仓库（<root>/.codetui/sessions/，按项目隔离，进程重启后可加载）+ 「回合/token 感知」压缩。
        // 单独持有仓库引用：取消回合时 CodingAgent 用它 replaceEvents 裁剪半截历史（DefaultSessionService 不暴露该能力）。
        SessionRepository sessionRepository =
                new FileSessionRepository(root.resolve(".codetui").resolve("sessions"));
        SessionService sessionService = DefaultSessionService.builder()
                .sessionRepository(sessionRepository)
                .build();

        // token 估算器（JTokkit，spring-ai-commons 提供）：trigger 与摘要策略都需显式提供，无默认值。
        TokenCountEstimator tokenCountEstimator = new JTokkitTokenCountEstimator();

        java.util.function.LongSupplier contextWindow = () -> contextWindow(registry);
        java.util.function.LongSupplier autoThreshold = () -> autoCompactionThreshold(registry);
        java.util.function.LongSupplier targetBudget = () ->
                Math.max(8_000L, (long) (contextWindow.getAsLong() * COMPACTION_TARGET_RATIO)
                        - MEDIA_MANIFEST_TOKEN_RESERVE);
        java.util.function.LongSupplier manualTargetBudget = () ->
                Math.max(8_000L, contextWindow.getAsLong() / 4L - MEDIA_MANIFEST_TOKEN_RESERVE);

        // 单一 CalibrationState：auto/manual 两条策略共享——/compact 学到的容量立即被自动压缩复用，反之亦然。
        CalibrationState calibrationState = new CalibrationState();
        java.util.function.Supplier<BoundedSummarizationCompactionStrategy.ModelSnapshot> snapshot =
                () -> modelSnapshot(registry);

        CompactionStrategy autoStrategy = autoCompaction(
                new BoundedSummarizationCompactionStrategy(targetBudget,
                        tokenCountEstimator::estimate,
                        summarizeChunk(auxClient),
                        snapshot, calibrationState),
                listener);

        CompactionStrategy manualStrategy = manualCompaction(
                new BoundedSummarizationCompactionStrategy(manualTargetBudget,
                        tokenCountEstimator::estimate,
                        summarizeChunk(auxClient),
                        snapshot, calibrationState, MANUAL_MAX_EVENTS_TO_KEEP));

        SessionMemoryAdvisor memoryAdvisor = SessionMemoryAdvisor.builder(sessionService)
                .defaultUserId(DEFAULT_USER_ID)
                .build();
        PreflightCompactionAdvisor preflightCompaction = new PreflightCompactionAdvisor(
                sessionService, autoThreshold, tokenCountEstimator, autoStrategy);

        // 环境信息 / git 状态只取一次，供所有 provider 共用：既省掉每 provider 一次 git 子进程，
        // 也把 gitStatus 的调用收敛到一处 —— 便于用 quietGitStatus 屏蔽它对 System.out 的杂散打印。
        String environmentInfo = AgentEnvironment.info();
        String gitStatus = quietGitStatus();

        // 记忆系统提示：渲染一次供所有 provider 共用（注入记忆根路径；见 MemoryPrompt 为何不走 ST 渲染）
        String autoMemoryPrompt = MemoryPrompt.render(memoryDir(root).toString());

        // 搜索指引：与工具注册状态严格同步——工具没注册就不给模型任何搜索提示，
        // 否则模型会去调一个不存在的工具。
        String webSearchGuide = webSearchGuide(webSearch != null, zhipuWebSearch != null, braveWebSearch != null);

        // 署名指引：抽出来供 defaultSystem 与下面的系统提示词估算共用，避免重复解析环境变量。
        String coAuthorGuideText = coAuthorGuide(System.getenv("CODETUI_CO_AUTHOR"));

        // 系统提示词 token 估算（/context 分类展示用）：装配期渲染一份与 defaultSystem 同源的文本估算。
        // 运行期 GIT_STATUS / PERMISSION_MODE 每回合变化，但量级稳定（±几百 token），装配期快照足够
        // 回答「每回合固定开销有多大」；PERMISSION_MODE 按装配期空串计，AGENT_MODEL 取激活 provider。
        long systemPromptTokens = tokenCountEstimator.estimate(SYSTEM_TEMPLATE
                .replace(AgentEnvironment.ENVIRONMENT_INFO_KEY, environmentInfo)
                .replace(AgentEnvironment.GIT_STATUS_KEY, gitStatus)
                .replace(AgentEnvironment.AGENT_MODEL_KEY, registry.active().defaultModel())
                .replace(AUTO_MEMORY_KEY, autoMemoryPrompt)
                .replace(PROJECT_INSTRUCTIONS_KEY, projectInstructions)
                .replace(WEB_SEARCH_GUIDE_KEY, webSearchGuide)
                .replace(CO_AUTHOR_GUIDE_KEY, coAuthorGuideText)
                .replace(PERMISSION_MODE_KEY, ""));

        // 为每个可用 provider 各建一个 ChatClient：共享同一套装饰工具 + 会话记忆 advisor + 系统模板，
        // 仅底层 ChatModel 不同。CodingAgent.submit 按激活 provider 选对应 ChatClient 实现跨家切换。
        java.util.Map<String, ChatClient> clients = new java.util.LinkedHashMap<>();
        // 每个 provider 的视觉装饰器实例单独留一份引用：/context 要按<b>激活</b> provider 读
        // lastSnapshot()，而快照是每个装饰器自己的状态（预算也按实例计），拿错一个就报错数字。
        java.util.Map<String, VisionMaterializingChatModel> visionModels = new java.util.LinkedHashMap<>();
        // L1 重试事件的两段式桥（spec §3.2）：装配期包进每个 provider 的 RetryingStreamChatModel（reporter），
        // CodingAgent 构造完成后经 {@link #wireL1} 一次性 bind 到 codingAgent::onL1Retry。
        // 全 provider 共用一个实例——report 只透传、与模型家无关（与 interjections 全 provider 共用的同一理由）；
        // 若每 provider 一个桥，wireL1 只 bind 得到其中一份，其余家的 L1 重试对 UI 永久不可见。
        L1ReporterBridge bridge = new L1ReporterBridge();
        // L1 限额等待事件的两段式桥（spec §3.6）：完整镜像 bridge——全 provider 共用一个实例，
        // wrap 进每个 provider 主链的三参 hook；CodingAgent 构造后经 wireL1 bind 到 agent::onL1QuotaWait。
        L1QuotaBridge quotaBridge = new L1QuotaBridge();
        for (LlmProvider provider : registry.allProviders()) {
            if (!provider.available()) {
                continue;
            }
            // 视觉兑现的唯一接线点：包在 provider 的 ChatModel 外，位于整条 advisor 链<b>下游</b>，
            // 故兑现结果绝不会回流进会话存储（见 VisionMaterializingChatModel 类注释）。
            // ⚠ 主链 L1 零下发重试插在 provider.chatModel() 与 Vision 之间（spec §3.7 链序，勿挪）：
            //   最终链 Interjecting(Vision(RetryStream(Usage(IdleTimeout(raw)))))——Usage/IdleTimeout 在
            //   CodeTuiApplication 的 provider 包装层（{@code wrap()}），RetryStream 在其外。
            //   位置论证（spec §3.7）：RetryStream 在 IdleTimeout 外（空闲/首字节超时可重试）、在 Vision 内
            //   （L1 重订阅复用同一已物化 Prompt）、在 Usage 外（provider.chatModel() 是主/子/aux 三路共用
            //   的共享链——放共享链内会给子 agent 双重重试、给 aux 违反守卫；aux/子 agent 不被误包由守卫
            //   测试钉着：AuxClientNotRetryWrappedTest / 本装配段注释）。
            //   总闸：retryConfig.l1Enabled()=false（off/l2）时三元直通不包装（无 passthrough API，见
            //   RetryingStreamChatModel.wrap），守卫测试据此断言链中无 RetryingStreamChatModel。
            // ⚠ dispose 守卫（spec §5「dispose 后 boundedElastic 不再 replaceEvents」）不在此装配行——
            //   实际落位 CodingAgentTurnResumeTest 用例 7（repository 桩计数，Task 6 已实现），本任务不重复。
            ChatModel base = retryConfig.l1Enabled()
                    ? RetryingStreamChatModel.wrap(provider.chatModel(), bridge, quotaBridge)
                    : provider.chatModel();
            // 与工具侧（MediaExternalizingCallback）共用同一个 visionBudget 实例：出站兑现开回合、
            // 工具读图查同一桶，两侧额度口径才一致。
            // 图像规格按 provider 取：长边上限与 token 口径各家不同（见 ImageProfile），统一套用
            // Anthropic 口径会让 DeepSeek 的 token 被高估 3.6 倍、把额度白白吃光。
            VisionMaterializingChatModel visionModel = VisionMaterializingChatModel.wrap(
                    base, root,
                    modelId -> provider.capabilities(modelId).supportsImageInput(),
                    visionBudget,
                    ImageProfile.forProvider(provider.id()));
            visionModels.put(provider.id(), visionModel);
            // 插话注入包在<b>最外层</b>：位置必须在整条 advisor 链下游，才拿得到已配平的完整消息表
            // （工具结果落库与「构建下一次 prompt」是同一步，会话存储层看不到这个位置）。
            // 若将来主 agent 也用上 RetryingChatModel，本层必须仍在它<b>外面</b>——反了的话重试时
            // 队列已被第一次尝试排空，插话会在一次网络抖动后静默消失。
            // 工具执行接线统一收口 TurnToolLimitWiring（与子 agent 同源，防两路漂移）：Resilient 容错
            // （工具名拼错如 BochaWebSearch→BoochaWebSearch、执行异常 → 转错误文本回模型自纠）
            // + Spring AI 2.0.1 回合限流改默认不限（框架裸默认 40/150/THROW 会裸抛杀整回合；
            // 要熔断时 CODETUI_MAX_CALLS_PER_TOOL / CODETUI_MAX_TOTAL_TOOL_CALLS 可配，配了撞限也只回错误文本）。
            ToolCallingAdvisor.Builder<?> toolAdvisorBuilder = ToolCallingAdvisor.builder()
                    .toolCallingManager(TurnToolLimitWiring.create());
            ChatClient c = ChatClient.builder(
                    InterjectingChatModel.wrap(visionModel, interjections),
                    ObservationRegistry.NOOP, null, null, toolAdvisorBuilder)
                    .defaultSystem(s -> s.text(SYSTEM_TEMPLATE)
                            .param(AgentEnvironment.ENVIRONMENT_INFO_KEY, environmentInfo)
                            .param(AgentEnvironment.GIT_STATUS_KEY, gitStatus)
                            // 每个 client 烘焙自家默认模型，保证 defaultSystem 自洽；
                            // 每回合 submit 会用实际所选模型再覆盖此 param（见 CodingAgent.submit）。
                            .param(AgentEnvironment.AGENT_MODEL_KEY, provider.defaultModel())
                            .param(AUTO_MEMORY_KEY, autoMemoryPrompt)
                            .param(PROJECT_INSTRUCTIONS_KEY, projectInstructions)
                            .param(WEB_SEARCH_GUIDE_KEY, webSearchGuide)
                            .param(CO_AUTHOR_GUIDE_KEY, coAuthorGuideText)
                            .param(TODO_DISCIPLINE_KEY, TODO_DISCIPLINE)
                            // 装配期给空串占位；真实值由 CodingAgent.submit 每回合覆盖（merge 语义）。
                            // 不补默认值则模板渲染时缺 param 会抛。
                            .param(PERMISSION_MODE_KEY, ""))
                    .defaultTools(toolsWithTask)
                    // memoryAdvisor（会话记忆）+ 空流守卫（order +1001，紧邻 memoryAdvisor 内侧）：修复切 gpt 时
                    // SessionMemoryAdvisor.after() 因模型空流拿不到 session id 而抛 No session ID（见 SessionIdStreamGuardAdvisor）。
                    // 排列顺序不决定执行序（由 getOrder 决定）；守卫在 DeepSeek 上流非空即永不触发、零回归。
                    .defaultAdvisors(preflightCompaction, memoryAdvisor, new SessionIdStreamGuardAdvisor())
                    .build();
            clients.put(provider.id(), c);
        }

        return new AgentRuntime(clients, registry.active().id(), sessionService, sessionRepository,
                manualStrategy, tokenCountEstimator, reloadableSkill.skills(), decoratedSkillTool,
                reloadableSkill, subagentRunner, fileExternalizer, permissionEngine, visionModels,
                backgroundRegistry, backgroundResults, interjections, goalManager,
                systemPromptTokens, bridge, quotaBridge, todoReminder);
    }

    /**
     * 向后兼容重载：<b>engine 取自 {@code mcpRegistry}</b>，没有 MCP 时才自建一个隔离的默认引擎
     * （见 {@link #testEngine}）。
     *
     * <p><b>为什么不自建</b>：自建就成了<b>第二个</b>引擎——内置工具挂新的、MCP 工具挂
     * {@code McpRegistry} 里那个旧的。这种错法失效时<b>不报错</b>：用户 Shift+Tab 切到更严的模式，
     * 状态栏也跟着变了，而 MCP 工具仍按旧模式放行。故这里从 registry 取，让「共用同一个引擎」
     * 由类型而不是由调用纪律来保证。{@code mcpRegistry == null} 时全场只有这一个引擎，无从不一致。
     *
     * @param registry    provider 注册表
     * @param root        项目根目录（工具工作目录、各类状态文件的基准）
     * @param listener    UI 接缝
     * @param mcpRegistry MCP 注册表；null = 无 MCP 支持
     * @return 装配好的运行时
     */
    public static AgentRuntime build(ProviderRegistry registry, Path root, AgentListener listener,
                                      McpRegistry mcpRegistry) {
        return build(registry, root, listener, mcpRegistry,
                mcpRegistry == null ? testEngine(root) : mcpRegistry.permissionEngine());
    }

    /** 向后兼容：无 MCP 支持（等价 registry=null）。现有测试与旧调用走这条。
     *
     * @param registry provider 注册表
     * @param root     项目根目录
     * @param listener UI 接缝
     * @return 装配好的运行时
     */
    public static AgentRuntime build(ProviderRegistry registry, Path root, AgentListener listener) {
        return build(registry, root, listener, (McpRegistry) null);
    }

    /**
     * 测试/兼容用的隔离引擎：空配置 + DEFAULT 模式。
     *
     * <p><b>刻意不读两层 {@code permissions.json}</b>：用户层在 {@code ~/.codetui/permissions.json}，
     * 读了就会让测试结果随开发者本机的个人规则漂移（在 CI 上绿、在某人机器上红，或反过来）。
     * 生产的引擎由 {@code CodeTuiApplication} 用 {@code PermissionConfigLoader.load(root)} 建。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static PermissionEngine testEngine(Path root) {
        return new PermissionEngine(root, PermissionConfig.empty(), PermissionMode.DEFAULT);
    }

    /** 子 agent 并行并发度：环境变量 CODETUI_SUBAGENT_CONCURRENCY，非法/缺失回退 4，钳制到 [1, 32]。 */
    private static int resolveSubagentConcurrency() {
        String raw = System.getenv("CODETUI_SUBAGENT_CONCURRENCY");
        if (raw == null || raw.isBlank()) {
            return 4;
        }
        try {
            return Math.min(32, Math.max(1, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    /** 后台子 agent 并发上限：环境变量 CODETUI_BACKGROUND_CONCURRENCY，非法/缺失回退 4，钳制到 [1, 32]。 */
    private static int resolveBackgroundConcurrency() {
        return clampConcurrency(System.getenv("CODETUI_BACKGROUND_CONCURRENCY"), 4);
    }

    /** TaskOutput 阻塞等待上限（秒）：环境变量 CODETUI_TASK_OUTPUT_TIMEOUT_SECONDS，非法/缺失回退 300，钳制到 [1, 3600]。 */
    private static int resolveTaskOutputTimeout() {
        return clampTaskOutputTimeout(System.getenv("CODETUI_TASK_OUTPUT_TIMEOUT_SECONDS"));
    }

    /**
     * 解析并钳制到 {@code [1, 32]}，缺失/非法回落 {@code fallback}。
     *
     * <p><b>抽成纯函数是为了能被单测直接盯住</b>：env 读取那一层没法在测试里安全改（改进程环境变量会
     * 泄漏到同 JVM 里跑的其它用例），而"用户在 env 里写了个 abc"恰恰是最该被钉住的一条——
     * 它必须回落默认而不是让启动崩掉。形状照 {@link #resolveSubagentConcurrency}。
     */
    static int clampConcurrency(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.min(32, Math.max(1, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            log.warn("CODETUI_BACKGROUND_CONCURRENCY 不是合法整数（{}），回落默认 {}", raw, fallback);
            return fallback;
        }
    }

    /** 同上，用于 TaskOutput 超时：钳到 {@code [1, 3600]} 秒，缺失/非法回落 300。 */
    static int clampTaskOutputTimeout(String raw) {
        if (raw == null || raw.isBlank()) {
            return 300;
        }
        try {
            return Math.min(3600, Math.max(1, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            log.warn("CODETUI_TASK_OUTPUT_TIMEOUT_SECONDS 不是合法整数（{}），回落默认 300", raw);
            return 300;
        }
    }

    /**
     * 按 env 决定是否创建博查搜索工具：{@code apiKey} 空即返回 null（不注册）。
     * env 的<b>读取</b>在这里，<b>解析语义</b>（回退/钳制）在 {@link BochaWebSearchTool#resolveResultCount}——
     * 与 {@code ModelListEnv.parse} 一样把 env 值作为参数传入，测试才能不依赖真实环境变量。
     */
    /**
     * 搜索指引段：按实际注册了哪些搜索工具动态拼装——都没注册就返回空串，模型看不到指引，
     * 也就不会去调不存在的工具。三家已是 2^3 = 8 态，if 分支硬编码不可维护，改为
     * 「总起 + 分工句 + 公共尾 + 各家专属坑」条目拼装，第四家只需在 {@link #divisionLine} 与
     * 专属坑段各加一个条目。
     *
     * <p><b>跨工具分工只写在这里</b>（按注册状态渲染，绝不提不存在的工具）；工具自身的
     * @Tool 描述只写自家能力与用法。
     *
     * <p>正文<b>不得含花括号</b>：它作为 param 值注入（与 AUTO_MEMORY / PROJECT_INSTRUCTIONS 同法），
     * 花括号会被 StringTemplate 当占位符解析而炸掉整个系统提示渲染。
     */
    static String webSearchGuide(boolean bocha, boolean zhipu, boolean brave) {
        if (!bocha && !zhipu && !brave) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- 需要项目之外的最新信息（库的用法、报错含义、版本变更、新闻等）时先搜索，别凭记忆臆断外部事实。\n");
        sb.append("  ").append(divisionLine(bocha, zhipu, brave)).append('\n');
        sb.append("- 拿到网址后，需要网页原文细节就把该网址交给 webFetch 抓取。\n");
        if (bocha || zhipu) {
            sb.append("  国内源（").append(domesticNames(bocha, zhipu))
              .append("）的 freshness 一般不要传：默认不限时间效果最好，硬指时间范围反而容易搜空。\n");
        }
        if (zhipu) {
            sb.append("  ZhipuWebSearch 的 include 与 freshness 不要同时传：两者同时生效时结果条数限制会失效。\n");
        }
        if (brave) {
            sb.append("  BraveWebSearch 不要用 allowedDomains 限定域名（客户端过滤，白烧配额），改把 site:xxx 写进搜索词。\n");
        }
        sb.append("- 回答里引用了搜索结果，就在末尾列出 Sources，用 markdown 链接列出你实际参考的网址。");
        return sb.toString();
    }

    /** 分工句：国内源（博查/智谱）与 Brave 的组合各有措辞；只描述已注册的工具。 */
    private static String divisionLine(boolean bocha, boolean zhipu, boolean brave) {
        String domestic = domesticNames(bocha, zhipu);
        if (!brave) {
            if (bocha && zhipu) {
                return "搜索工具：" + domestic + "，两家互为冗余：一家搜不到或额度耗尽时换另一家再试。";
            }
            return "搜索工具：" + domestic + "。";
        }
        if (domestic.isEmpty()) {
            return "搜索工具：BraveWebSearch（主打英文技术文档、GitHub issue、英文新闻）。";
        }
        return "多个搜索工具按内容分工：中文内容、国内站点、中文技术社区用 " + domestic
                + "（国内向，返回摘要）；英文技术文档、GitHub issue、英文新闻用 BraveWebSearch。";
    }

    /** 已注册的国内源工具名，两家时用斜杠连接；无国内源返回空串。 */
    private static String domesticNames(boolean bocha, boolean zhipu) {
        if (bocha && zhipu) {
            return "BochaWebSearch / ZhipuWebSearch";
        }
        if (bocha) {
            return "BochaWebSearch";
        }
        if (zhipu) {
            return "ZhipuWebSearch";
        }
        return "";
    }

    /**
     * 提交署名指引段：{@code CODETUI_CO_AUTHOR} 未配置（null / 空白）时返回空串——功能默认关闭，
     * 模型看不到指引、不会主动追加尾注；配置了才返回指引，并把签名内联进尾注。
     * 形状照 {@link #webSearchGuide}——env 读取在 {@code build}，渲染语义留在纯函数便于测试。
     *
     * <p>签名值<b>不得含花括号</b>：它随本段作为 param 值注入（与 webSearchGuide / AUTO_MEMORY 同法），
     * 花括号会被 StringTemplate 当占位符解析而炸掉整个系统提示渲染。
     */
    static String coAuthorGuide(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String signature = raw.trim();
        return """
            - 用 git commit 提交改动时，在提交信息末尾追加署名尾注，让 GitHub 把 code-tui 计为共同作者：
              Co-Authored-By: %s
            """.formatted(signature);
    }

    static BochaWebSearchTool createWebSearchTool(String apiKey, String countEnv) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return BochaWebSearchTool.builder(apiKey)
                .resultCount(BochaWebSearchTool.resolveResultCount(countEnv))
                .build();
    }

    /**
     * 按 env 决定是否创建智谱搜索工具：{@code apiKey} 空即返回 null（不注册）。
     * env 的<b>读取</b>在这里，<b>解析语义</b>（回退/钳制/白名单）在
     * {@link ZhipuWebSearchTool#resolveResultCount} / {@link ZhipuWebSearchTool#resolveSearchEngine}。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static ZhipuWebSearchTool createZhipuWebSearchTool(String apiKey, String countEnv, String engineEnv) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return ZhipuWebSearchTool.builder(apiKey)
                .resultCount(ZhipuWebSearchTool.resolveResultCount(countEnv))
                .searchEngine(ZhipuWebSearchTool.resolveSearchEngine(engineEnv))
                .build();
    }

    /**
     * 智谱搜索是否要注册：{@code apiKey} 非空，且 {@code ZHIPU_SEARCH_ENABLED}（trim 后）不是 {@code "0"}。
     *
     * <p><b>为何只有智谱有这个开关</b>：它的 key 与 LLM provider 共用（同一把 {@code ZHIPU_API_KEY}），
     * key 的存在性表达不了「只要模型、不要按次计费的搜索」——没充标准余额、搜索走 Coding Plan
     * 套餐 MCP 的用户需要显式关掉内置工具。博查/Brave 的 key 本就是为搜索单独配的，不存在此冲突，
     * 不加开关。开关只认 {@code 0}：其他值一律视为开（回退语义，与 {@code resolve*} 系列一致），
     * 避免用户随手填个 {@code false}/{@code no} 以为关了实际还开着。
     */
    static boolean zhipuSearchWanted(String apiKey, String enabledEnv) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        return !"0".equals(enabledEnv == null ? "" : enabledEnv.trim());
    }

    /**
     * 解析 {@code BRAVE_SEARCH_COUNT}：缺失 / 非数字回退 {@link #BRAVE_DEFAULT_COUNT}，
     * 越界钳到 {@code [1, BRAVE_MAX_COUNT]}。形状照 {@link #resolveSubagentConcurrency}。
     */
    static int resolveBraveResultCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return BRAVE_DEFAULT_COUNT;
        }
        try {
            return Math.min(BRAVE_MAX_COUNT, Math.max(1, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return BRAVE_DEFAULT_COUNT;
        }
    }

    /**
     * 按 env 决定是否创建 Brave 搜索工具：{@code apiKey} 空即返回 null（不注册）。
     *
     * <p>直接复用库版 {@code BraveWebSearchTool}（不重写响应解析），外面套两层补它的硬缺陷：
     * {@link RenamedToolCallback} 改注册名 + 换描述，{@link TimeLimitedToolCallback} 兜总超时。
     *
     * <p><b>能力退化</b>：库版 baseUrl 是硬编码常量，无法指向本地 stub，故 Brave 的响应解析
     * <b>无法离线单测</b>——那半边的正确性只能靠 {@code BraveWebSearchSmokeTest} 真机冒烟保证。
     * 我们能离线测的只有自己写的外壳（改名、描述、超时、门控）。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static ToolCallback createBraveWebSearchTool(String apiKey, String countEnv) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        org.springaicommunity.agent.tools.BraveWebSearchTool tool =
                org.springaicommunity.agent.tools.BraveWebSearchTool.builder(apiKey.trim())
                        .resultCount(resolveBraveResultCount(countEnv))
                        .build();
        ToolCallback raw = ToolCallbacks.from(tool)[0];
        return new TimeLimitedToolCallback(
                new RenamedToolCallback(raw, BRAVE_TOOL_NAME, BRAVE_DESCRIPTION), BRAVE_TIMEOUT);
    }

    /**
     * build 的产物：每个可用 provider 的 ChatClient（按 provider id 索引）+ 手动 /compact 所需的会话句柄。
     *
     * @param clients             每个可用 provider 各一个 ChatClient，键为 {@code provider.id()}（共享工具/记忆/系统模板，仅底层 ChatModel 不同）
     * @param activeProviderId    激活（默认）provider 的 id，{@link #client()} 据此选默认 ChatClient
     * @param sessionService      会话服务（手动压缩经它 {@code compact(id, trigger, strategy)}；/context 经它读事件）
     * @param sessionRepository   会话事件仓库（取消回合时 {@code CodingAgent} 用它 {@code replaceEvents} 回滚半截历史）
     * @param manualStrategy      激进的手动压缩策略（未包装装饰器，见类注释）
     * @param tokenCountEstimator token 估算器（与压缩共用，供 {@code /context} 估算会话 token）
     * @param skills              初始（装配期）可用技能清单快照；运行期实时清单改经 {@link #reloadableSkill}
     * @param skillTool           被 ToolEventCallback 装饰的 Skill 代理（供手动 /skill 复用）；始终非 null
     * @param reloadableSkill     可重载 Skill 代理（{@code /reload} 触发重扫 + {@code skills()} 实时数据源）
     * @param subagentRunner      子 agent 执行器；当前主要供测试观测「记忆工具不泄漏给子 agent」的边界（生产暂无消费方）
     * @param fileExternalizer    路径②：回合间（{@code CodingAgent.submit} 开头）把过往大文本 tool 结果外置为引用，与路径①共用 store/root
     * @param permissionEngine    权限引擎（UI 经它读/切模式、列生效规则；三处装配点共用同一实例）
     * @param visionModels        每个 provider 的视觉兑现装饰器，键同 {@code clients}；供 {@code /context} 按激活 provider 读 {@code lastSnapshot()}
     * @param interjections       插话队列，全 provider 共用一个实例；与 {@code clients} 的 {@link InterjectingChatModel} 装饰层是同一个对象，
     *                            {@code CodingAgent} 拿它做门面与回合末补历史。另建一个等于 UI 投的话永远没人取
     * @param goalManager         goal 循环状态机，与 View goal 槽/CodingAgent.bindGoal 共享同一实例——
     *                            另建一个等于 UI 永远看不到真相（照 interjections 字段先例措辞）。
     *                            <b>null = goal 未装配</b>（兼容重载路径）：runtime 其余组件照常，只是没有 goal 循环
     * @param backgroundRegistry  后台任务注册表（进程内、不落盘）；{@code TaskOutput} / {@code ListTasks} / {@code /tasks} 面板共用
     * @param backgroundResults   后台结果限幅存储；超长正文落 artifacts 并在返回文本里给出路径
     * @param systemPromptTokens  装配期一次性估算的系统提示词 token 数，供 {@code /context} 与状态栏用同一分母
     * @param bridge              L1 重试事件的两段式桥——与每个 provider 主链里 {@link RetryingStreamChatModel}
     *                            wrap 进去的是<b>同一个实例</b>；CodingAgent 构造后经 {@link #wireL1} bind 到
     *                            {@code agent::onL1Retry}（装配期未 bind，volatile null 守卫 no-op）
     * @param quotaBridge         L1 限额等待事件的两段式桥（spec §3.6）——镜像 {@code bridge}：与三参 wrap 进
     *                            provider 主链的是<b>同一个实例</b>；经 {@link #wireL1} bind 到
     *                            {@code agent::onL1QuotaWait}（装配期未 bind，volatile null 守卫 no-op）
     */
    public record AgentRuntime(java.util.Map<String, ChatClient> clients,
                               String activeProviderId,
                               SessionService sessionService,
                               SessionRepository sessionRepository,
                               CompactionStrategy manualStrategy,
                               TokenCountEstimator tokenCountEstimator,
                               List<SkillInfo> skills,
                               ToolCallback skillTool,
                               ReloadableSkillTool reloadableSkill,
                               SubagentRunner subagentRunner,
                               SessionFileExternalizer fileExternalizer,
                               PermissionEngine permissionEngine,
                               java.util.Map<String, VisionMaterializingChatModel> visionModels,
                               BackgroundTaskRegistry backgroundRegistry,
                               TaskResultStore backgroundResults,
                               Interjections interjections,
                               GoalManager goalManager,
                               long systemPromptTokens,
                               L1ReporterBridge bridge,
                               L1QuotaBridge quotaBridge,
                               TodoStaleReminder todoReminder) {

        /** 便捷：激活 provider 的 ChatClient（单-provider 用法与旧代码兼容）。 */
        public ChatClient client() { return clients.get(activeProviderId); }
    }

    /**
     * 把 {@link AgentRuntime#bridge()} bind 到 {@code CodingAgent} 的 {@code onL1Retry}（spec §3.2 两段式时序），
     * 并同步把 {@link AgentRuntime#quotaBridge()} bind 到 {@code onL1QuotaWait}（spec §3.6 镜像桥）：
     * 装配期 {@code build} 建桥并把桥包进 {@link RetryingStreamChatModel}，CodingAgent 构造<b>完成后</b>经本方法接线。
     *
     * <p><b>为什么必须放在 AgentTools 而不是 {@code CodeTuiApplication}</b>：bind 的是方法引用
     * {@code agent::onL1Retry}，而 {@code onL1Retry} 是 <b>包私有</b> 方法——方法引用在引用方编译期
     * 解析可访问性，{@code CodeTuiApplication}（codetui 包）写 {@code codingAgent::onL1Retry} 直接编译失败；
     * 本方法在 agent 包内编译，天然可访问。调用方（跨包）只调这一个 public 静态方法。
     */
    public static void wireL1(AgentRuntime rt, CodingAgent agent) {
        rt.bridge().bind(agent::onL1Retry);
        rt.quotaBridge().bind(agent::onL1QuotaWait);
    }

    /**
     * 把 goal 三件套两段式接进 {@code CodingAgent}（Task 9，镜像 {@link #wireL1} 的桥接形状）：
     * {@code CodingAgent.bindGoal} 是<b>包私有</b>方法——内部要闭包「当前会话 id」给
     * {@code GoalManager} 评估结论落库，出了 agent 包不可见；{@code CodeTuiApplication}
     * （codetui 包）直接调会编译失败，须经本方法在 agent 包内转接。
     *
     * <p>{@code goalManager} 取自 {@link AgentRuntime#goalManager()}——<b>必须</b>与 build 第 7 参
     * 那一份 same（状态机只有一份，View goal 槽也从 runtime 取；另建一个等于 UI 永远看不到真相）。
     * {@code runner}/{@code evaluator} 由装配方建好传入；三者均可为 null（无 goal 子系统的桩路径，
     * bindGoal 侧 null 守卫降级）。
     */
    public static void wireGoal(AgentRuntime rt, CodingAgent agent,
                                GoalEvaluationRunner runner, GoalEvaluator evaluator) {
        agent.bindGoal(rt.goalManager(), runner, evaluator);
    }

    /**
     * 把 {@link AgentRuntime#todoReminder()} 绑到 {@code CodingAgent}（镜像 {@link #wireGoal} 的两段式形状）：
     * {@code CodingAgent.bindTodoReminder} 是<b>包私有</b>方法，跨包直调编译失败，须经本方法在 agent 包内转接。
     * <b>必须传 runtime 里那份</b>——提醒器是装配期唯一实例，另建一个 /clear 就清不到装饰链正在用的那份。
     */
    public static void wireTodoReminder(AgentRuntime rt, CodingAgent agent) {
        agent.bindTodoReminder(rt.todoReminder());
    }

    /**
     * L1 重试事件的两段式桥（完整形状，spec §3.2）：装配期实例建好即包进 {@link RetryingStreamChatModel}，
     * 而真正消费方（CodingAgent 的 {@code onL1Retry} → UI ↻ 行）要等 CodingAgent 构造完才存在——
     * 故桥先持 volatile sink（装配期 null），{@link #bind} 后转发。
     *
     * <p><b>volatile 快照读 + null 守卫</b>：{@code report} 与 {@code bind} 可能在不同线程（模型线程 vs
     * 装配/构造线程）；快照读保证单次 report 内不因 bind 交错读到两次不同值，null 守卫让装配期偶发
     * 上报（理论窗口极小：L1 重试发生在首个回合前）不 NPE。
     */
    static final class L1ReporterBridge implements RetryReporter {
        private volatile RetryReporter sink;          // 装配期 null；wireL1 后有值
        void bind(RetryReporter r) { this.sink = r; }
        @Override public void report(int attempt, long backoffMs, String reason) {
            RetryReporter s = sink;                   // 快照
            if (s != null) s.report(attempt, backoffMs, reason);
        }
    }

    /** L1 限额等待事件的两段式桥（spec §3.6，完整镜像 {@link L1ReporterBridge}：装配期 null 守卫 + bind 后转发）。 */
    static final class L1QuotaBridge implements RetryPolicy.QuotaWaitHook {
        private volatile RetryPolicy.QuotaWaitHook sink;          // 装配期 null；wireL1 后有值
        void bind(RetryPolicy.QuotaWaitHook h) { this.sink = h; }
        @Override public void onQuotaWait(long waitMs, long resetAtEpochMs, String reason) {
            RetryPolicy.QuotaWaitHook s = sink;                   // 快照
            if (s != null) s.onQuotaWait(waitMs, resetAtEpochMs, reason);
        }
    }

    /**
     * 辅助 client（SmartWebFetch 抽取 + 会话滚动摘要）底层的 ChatModel。
     *
     * <p><b>这个 ChatModel 绝不能被 {@link VisionMaterializingChatModel} 包上。</b>摘要请求里的消息
     * <b>含引用块</b>——一旦包上，每次压缩都会把历史图兑现成真字节发给摘要模型，<b>一次纯文本摘要
     * 静默变成视觉请求</b>。而压缩是<b>自动触发</b>的、全程无提示，你不会注意到，直到看账单。
     *
     * <p><b>同样绝不能被 {@link RetryingStreamChatModel} 包上</b>：aux 链（辅助 ChatClient）经
     * {@link DynamicAuxChatModel} <b>每次调用实时直取 {@code provider.chatModel()}</b>——主链的 L1
     * 重试只应在 AgentTools 主链 wrap（spec §3.7 收敛一行），放这里会给摘要/抽取请求也套上重试层。
     * 两件事都有守卫测试盯住（{@code AuxClientNotVisionWrappedTest} / {@code AuxClientNotRetryWrappedTest}）。
     *
     * <p>之所以把它抽成一个独立方法而不是埋在 {@link #build} 一长串构造里：让「auxClient 用哪个
     * ChatModel」变成<b>一处显式决策</b>，可以被守卫测试直接盯住——否则「顺手包上」这件事没有任何东西挡得住。
     */
    static ChatModel auxChatModel(ProviderRegistry registry) {
        return new DynamicAuxChatModel(registry);
    }

    /**
     * 压缩摘要的单次 LLM 调用（auto/manual 共用）。
     *
     * <p><b>maxTokens=8192</b>：摘要输出若无硬上限，部分家的输出可膨胀到输入级——压缩产物反而顶破
     * 目标预算，触发线形同虚设。经 {@code DynamicAuxChatModel} 字段级合并后覆盖该家基础值。
     * system prompt 同步声明输出量级，让上限对模型本身可见（双保险：提示 + 硬截断）。
     */
    private static java.util.function.Function<String, String> summarizeChunk(ChatClient auxClient) {
        return chunk -> auxClient.prompt()
                .system("Summarize this conversation chunk for continuation. Preserve decisions, constraints, "
                        + "completed work, pending tasks, errors, commands, and file paths. "
                        + "Keep the summary under 8000 tokens.")
                .user(chunk)
                .options(org.springframework.ai.chat.prompt.ChatOptions.builder()
                        .maxTokens(SUMMARY_MAX_OUTPUT_TOKENS))
                .call()
                .content();
    }

    /**
     * 长期记忆工具（spring-ai-agent-utils 的 AutoMemoryTools，6 个 @Tool）——仅主 agent 使用。
     * 记忆根 <root>/.codetui/memory（与会话仓库同级、按项目隔离；AutoMemoryTools.build() 自动建目录，
     * 已被 .codetui/ gitignore）。每个工具用 PermissionCallback(ToolEventCallback(...)) 装饰，
     * 使记忆操作在 TUI 显示成一行工具活动、并与其余工具走同一条权限链。
     * 单一事实来源：build() 与测试钩子共用本方法，避免装配与断言漂移。
     *
     * <p>记忆工具在登记表里是 {@code INTERNAL}（恒放行，路径已被库侧钳死在 {@code .codetui/memory} 内），
     * 故这层平时不拦任何东西；包上是为了「所有工具走同一条链」——用户写一条
     * {@code deny: MemoryDelete(*)} 时它得真管用。
     */
    static ToolCallback[] buildMemoryTools(Path root, AgentListener listener, PermissionEngine engine) {
        return buildMemoryTools(root, listener, engine, null);
    }

    /**
     * 完整形状：{@code reminder} 参与装饰（null=停用，向后兼容重载走这里）。
     * 记忆工具仅主 agent 使用，控制器级调用照常计数/提醒（MemoryView 这类「读记忆」也算工作进展）。
     */
    static ToolCallback[] buildMemoryTools(Path root, AgentListener listener, PermissionEngine engine,
                                           TodoStaleReminder reminder) {
        Path dir = memoryDir(root);
        AutoMemoryTools memoryTools = AutoMemoryTools.builder().memoriesDir(dir).build();
        ToolCallback[] raw = ToolCallbacks.from(memoryTools);
        ToolCallback[] decorated = new ToolCallback[raw.length];
        for (int i = 0; i < raw.length; i++) {
            decorated[i] = new PermissionCallback(new ToolEventCallback(raw[i], listener, reminder), engine, listener);
        }
        return decorated;
    }

    /** 向后兼容（<b>测试用</b>）：自建一个隔离的默认引擎（见 {@link #testEngine}）。 */
    static ToolCallback[] buildMemoryTools(Path root, AgentListener listener) {
        return buildMemoryTools(root, listener, testEngine(root));
    }

    /** 记忆根目录 <root>/.codetui/memory（与会话仓库同级、按项目隔离；已被 .codetui/ gitignore）。单一事实来源：prompt 与工具根共用。 */
    static Path memoryDir(Path root) {
        return root.resolve(".codetui").resolve("memory");
    }

    /** 测试钩子：返回反问工具经 {@link ToolCallbacks#from} 派生出的工具名（校验它确被识别为 @Tool 并注册）。 */
    static List<String> askToolNamesForTest(AgentListener listener) {
        AskUserQuestionTool ask = AskUserQuestionTool.builder()
                .questionHandler(new UserQuestionBridge(listener)).build();
        List<String> names = new ArrayList<>();
        for (ToolCallback c : ToolCallbacks.from(ask)) {
            names.add(c.getToolDefinition().name());
        }
        return names;
    }

    /** 把 {@link Todos} 转成可显示的行：状态标记 + 内容。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。 */
    public static List<String> toLines(Todos todos) {
        if (todos == null || todos.todos() == null) {
            return List.of();
        }
        return todos.todos().stream()
                .map(item -> statusMarker(item.status()) + " " + item.content())
                .toList();
    }

    /**
     * 调 {@link AgentEnvironment#gitStatus()} 但屏蔽它对 {@code System.out} 的杂散打印。
     *
     * <p>该库方法在「git 不可用」或「当前目录不是 git 仓库」两个分支里，会直接
     * {@code System.out.println("Not inside a git repository." / "Git is not available...")}
     * 并返回空串。code-tui 是内联 TUI、与库共用 stdout，这行会漏进终端 scrollback 污染画面。
     * 这里在装配期（单线程、TUI 尚未接管终端）临时把 stdout 换成黑洞，取回返回值后立即还原——
     * 返回值（正常时是格式化好的 git 状态块、异常分支是空串）照旧喂给系统提示，只是不再打印。
     */
    private static String quietGitStatus() {
        java.io.PrintStream original = System.out;
        try {
            System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
            return AgentEnvironment.gitStatus();
        } finally {
            System.setOut(original);
        }
    }

    private static String statusMarker(Todos.Status status) {
        if (status == null) {
            return "○";
        }
        return switch (status) {
            case completed -> "✓";     // 完成：打钩
            case in_progress -> "▶";   // 进行中：可区分状态
            case pending -> "○";       // 待办
        };
    }
}

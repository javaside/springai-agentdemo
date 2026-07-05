package com.example.springai.codetui.agent;

import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springaicommunity.agent.utils.AgentEnvironment;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.ai.session.compaction.TokenCountTrigger;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AgentTools —— 组装编码 Agent 用的 {@link ChatClient} 的工厂。
 *
 * <p>把 6 个社区工具（FileSystem/Shell/Grep/Glob/TodoWrite/SmartWebFetch）用 {@link ToolEventCallback} 装饰后
 * 注册进 ChatClient，并配上 {@link SessionMemoryAdvisor} 会话记忆与 {@link AgentEnvironment} 环境系统提示。
 *
 * <p><b>网页获取（SmartWebFetch）</b>：{@link SmartWebFetchTool} 抓取网页→转 Markdown→用一个「裸」ChatClient
 * 按 prompt 做 AI 抽取（带 15 分钟缓存 / 重试）。这里关掉 domainSafetyCheck——它会去请求 {@code claude.ai}
 * 的域名信息 API，在本环境多半不可达、只会徒增延迟；本地编码 Agent 用不着这层校验。
 *
 * <p><b>会话记忆（spring-ai-session）</b>：取代原先的 {@code MessageChatMemoryAdvisor} 定长滑窗。
 * 事件溯源存储 + 「回合/token 感知」压缩——超过 token 阈值时用 LLM 滚动摘要把更早历史压成一条摘要事件，
 * 且压缩尊重回合边界、不会拆散某轮的 tool_call 与 tool_result。当前用内存仓库
 * （{@link InMemorySessionRepository}），进程退出即失效。
 *
 * <p><b>返回 {@link AgentRuntime}</b>：{@link #build} 除了 {@link ChatClient}，还暴露 {@link SessionService}
 * 与一份更激进的手动压缩策略（保留 20 事件），供上层 {@code /compact} 命令直接触发压缩。
 * <b>自动</b>（阈值触发）路径的策略用 {@link NotifyingCompactionStrategy} 包一层，使原本静默的压缩对 UI 可见；
 * <b>手动</b>路径的策略<b>不</b>包装——它由 {@code CodingAgent.runCompaction} 直接调用并自行上报事件，
 * 若再包装会重复上报同一次压缩。
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
 * <p><b>安全边界（设计方案 B）</b>：只有 FileSystemTools 有强制的目录边界；Shell/Grep/Glob 在技术上
 * <b>不</b>受 root 限制（见 {@code AgentToolsSecurityTest}）。这里靠系统提示要求模型自律约束在 root 内，
 * 而非承诺技术强制。
 */
public final class AgentTools {

    private AgentTools() {
    }

    /**
     * 触发压缩的累计 token 阈值：超过即对更早历史做摘要压缩。
     * v4-flash 上下文 1M（输出上限 384K），故把压缩当「偶尔兜底」而非常态税——约 40% 窗口才压，
     * 多数编码会话跑不到；仍距 1M 留 ~600k 给本轮响应 + 工具输出，避免某轮总量破 1M 报错。
     * 不变量：须明显 > MAX_EVENTS_TO_KEEP 个事件的 token 量，否则压缩压不到阈值以下、原地空转。可调。
     */
    static final int COMPACTION_TOKEN_THRESHOLD = 400_000;

    /** 当前模型（v4-flash）上下文窗口 token 数；仅用于 {@code /context} 展示「已用 / 窗口」占比，非硬约束。 */
    static final long CONTEXT_WINDOW_TOKENS = 1_000_000L;

    /** 压缩时保持逐字原样的最近事件数（约最近 30 轮）；更早的被 LLM 滚动摘要吸收（压缩为销毁式，见类注释）。须 > overlapSize 且其 token 量远低于阈值。可调。 */
    static final int MAX_EVENTS_TO_KEEP = 120;

    /** 手动 /compact 的保留窗口：比自动的 120 激进得多，让「按需缩小上下文」真正生效。可调。 */
    static final int MANUAL_MAX_EVENTS_TO_KEEP = 20;

    /** 手动策略的重叠窗口：须 >=0 且 < MANUAL_MAX_EVENTS_TO_KEEP，给摘要与保留段留一点上下文衔接。 */
    private static final int MANUAL_OVERLAP_SIZE = 3;

    /** 会话记忆的默认用户 id（单会话 TUI，仅作归属占位）。 */
    private static final String DEFAULT_USER_ID = "code-tui-user";

    /**
     * 系统提示：把自己定位成编码 Agent。内嵌 3 个 {@link AgentEnvironment} 占位符
     * （键名与下面 {@code .param(...)} 一致：ENVIRONMENT_INFO / GIT_STATUS / AGENT_MODEL）。
     * 不注入知识截止——DeepSeek 无精确公开值，编一个只会误导模型自述。诚实声明只有 FileSystemTools 有强制边界。
     */
    private static final String SYSTEM_TEMPLATE = """
            你是一个运行在终端里的中文编码助手（coding agent），帮助用户在当前项目里读写代码、排查问题、完成开发任务。

            工作方式：
            - 动手改代码前，先用 Grep / Glob / read 把相关文件和上下文看清楚，理解现状再动手，不要臆测。
            - 当任务包含 3 个或更多明确步骤、或用户要求你组织任务时，先调用 TodoWrite 把工作拆成结构化清单再执行；
              同一时间只允许一个任务处于 in_progress，开始前标 in_progress、完成后立刻标 completed。
            - 修改文件后，用 Shell 运行构建 / 测试 / 检查命令来验证改动确实生效，再给出结论。
            - 需要项目之外的信息（外部文档、库用法、报错含义等）时，用 webFetch 传入网址和你要抽取的问题来获取；
              它只读网页、不能登录或执行 JS。别凭记忆臆断外部事实。
            - 当任务匹配某个「可用技能」的描述时（如撰写规范提交信息、遵循某领域规范），先调用 Skill 工具并传入技能名，
              读取其完整指令后再据此产出结果；没有匹配的技能时正常作答，不要臆造技能名。
            - 当需求含糊、或需要用户在多个实现方案之间拍板时，用 AskUserQuestionTool 向用户提问（一次 1–4 个问题，
              每问 2–4 个选项，可单选或多选）；不要在信息不足时自行臆测方向。
            - 回答简洁，聚焦用户的目标本身。

            关于工作边界（请务必遵守）：
            - 只有文件读写工具（FileSystemTools）有被强制限定的目录边界；
              Shell / Grep / Glob 在技术上并未被限制在项目根目录内。
            - 因此请自我约束：所有操作都应发生在当前项目根目录之内，不要用绝对路径或 ../ 去访问项目之外的文件，
              也不要执行会影响项目之外的命令。

            <environment_info>
            {ENVIRONMENT_INFO}
            </environment_info>

            <git_status>
            {GIT_STATUS}
            </git_status>

            当前模型：{AGENT_MODEL}。
            """;

    /**
     * 组装编码 Agent 的 ChatClient。仅做装配，不发起任何网络请求，也不要求有效的 API key。
     *
     * @param registry provider 注册表（每个可用 provider 各建一个 ChatClient；auxClient 与系统提示的模型名取激活 provider）
     * @param root     项目根目录（FileSystemTools 的强制边界 + Grep/Glob 的默认工作目录）
     * @param listener 工具 / Todo 事件出口
     *                 <p>注：conversationId（会话记忆）由 {@code CodingAgent.submit} 每次请求传入，
     *                 不在装配期绑定，故此处不需要 sessionId 参数。
     */
    public static AgentRuntime build(ProviderRegistry registry, Path root, AgentListener listener) {
        FileSystemTools fs = FileSystemTools.builder().allowedDirectory(root).build();
        ShellTools sh = ShellTools.builder().build();
        GrepTool grep = GrepTool.builder().workingDirectory(root).build();
        GlobTool glob = GlobTool.builder().workingDirectory(root).build();
        TodoWriteTool todo = TodoWriteTool.builder()
                // turnId/taskId 均走 ThreadLocal（handler 在工具 call 内同步触发），不读实时 activeTurnId。
                // taskId 区分层级：null=控制器计划 todo（任务面板）；非空=子 agent 内部 todo（todo 面板）。
                .todoEventHandler(todos ->
                        listener.onTodoUpdated(ToolEventCallback.currentTurnId(),
                                ToolEventCallback.currentTaskId(), toLines(todos)))
                .build();

        // 「裸」ChatClient（同模型、无工具、无记忆 advisor）：一份复用给两处内部 LLM 调用——
        // ① SmartWebFetch 的网页内容 AI 抽取；② 会话历史的滚动摘要。都不能带工具/记忆，否则会递归触发工具或记忆循环。
        // 绑定激活（默认）provider 的模型。
        org.springframework.ai.chat.model.ChatModel activeModel = registry.active().chatModel();
        ChatClient auxClient = ChatClient.builder(activeModel).build();

        // 网页获取工具：抓取→转 Markdown→按 prompt AI 抽取。关掉 domainSafetyCheck（见类注释）。
        SmartWebFetchTool webFetch = SmartWebFetchTool.builder(auxClient)
                .domainSafetyCheck(false)
                .maxContentLength(150_000)   // v4-flash 上下文 1M，抽取前允许更长原文
                .maxRetries(3)
                .build();

        // 技能（Skill）：可重载代理，扫描两层技能来源（用户 ~/.codetui/skills + 项目 <root>/.codetui/skills）。
        // 与一次性 SkillCatalog 不同，这里<b>始终注册</b>该代理——即便当前零技能，也保留槽位，
        // 使运行中新增 SKILL.md 经 /reload 能被热加载（见 ReloadableSkillTool 类注释）。
        ReloadableSkillTool reloadableSkill = new ReloadableSkillTool(root);

        // 反问工具：QuestionHandler 阻塞工具线程、等 UI 经 onQuestionAsked → responder 应答（见 UserQuestionBridge）。
        AskUserQuestionTool askTool = AskUserQuestionTool.builder()
                .questionHandler(new UserQuestionBridge(listener))
                .answersValidation(true)   // UI 顺序问询保证答案完整，故校验开着也不会触发异常
                .build();

        // org.springframework.ai.support.ToolCallbacks（spring-ai-model）：7 个 @Tool 对象转 ToolCallback。
        // Skill 工具本身已是 ToolCallback（非 @Tool 对象），单独追加进列表；随后统一用 ToolEventCallback 装饰，
        // 使「技能被调用」也在 TUI 显示为一行工具活动。
        List<ToolCallback> all = new ArrayList<>(Arrays.asList(
                ToolCallbacks.from(fs, sh, grep, glob, todo, webFetch, askTool)));
        all.add(reloadableSkill);   // 始终注册可重载 Skill 代理（支持运行期 /reload 从零热加载）
        ToolCallback[] decorated = new ToolCallback[all.size()];
        ToolCallback decoratedSkillTool = null;   // 手动 /skill 路径复用同一个被装饰实例（事件/返回与自动路径一致）
        for (int i = 0; i < all.size(); i++) {
            decorated[i] = new ToolEventCallback(all.get(i), listener);
            if (all.get(i) == reloadableSkill) {
                decoratedSkillTool = decorated[i];   // 记住 Skill 代理装饰后的实例（供手动 /skill 复用）
            }
        }

        // 子 agent（Task 工具）：SubagentRunner 复用「已装饰、带边界」的工具列表 decorated；
        // Task 工具本身也用 ToolEventCallback 装饰，故委派本身在主流显示为一行。
        java.util.List<ToolCallback> decoratedList = java.util.List.of(decorated);
        SubagentRunner subagentRunner = new SubagentRunner(registry, decoratedList, listener);
        java.util.Map<String, SubagentSpec> subagentSpecs = SubagentLoader.loadBuiltins();
        ToolCallback taskTool = SubagentTool.create(subagentSpecs,
                (spec, prompt, desc, turnIgnored) ->
                        // 真实 parentTurnId 从 ThreadLocal 取（Task 工具被 ToolEventCallback 装饰，call 时已压入）
                        subagentRunner.run(spec, prompt, desc, ToolEventCallback.currentTurnId()));
        ToolCallback decoratedTaskTool = new ToolEventCallback(taskTool, listener);

        // 主 agent 工具集 = 原装饰工具 + Task 工具
        Object[] toolsWithTask = new Object[decorated.length + 1];
        System.arraycopy(decorated, 0, toolsWithTask, 0, decorated.length);
        toolsWithTask[decorated.length] = decoratedTaskTool;

        // 事件溯源会话记忆：内存仓库 + 「回合/token 感知」压缩，取代原滑窗记忆。
        // 单独持有仓库引用：取消回合时 CodingAgent 用它 replaceEvents 回滚半截历史（DefaultSessionService 不暴露该能力）。
        SessionRepository sessionRepository = InMemorySessionRepository.builder().build();
        SessionService sessionService = DefaultSessionService.builder()
                .sessionRepository(sessionRepository)
                .build();

        // token 估算器（JTokkit，spring-ai-commons 提供）：trigger 与摘要策略都需显式提供，无默认值。
        TokenCountEstimator tokenCountEstimator = new JTokkitTokenCountEstimator();

        // 自动压缩策略（保留 120）→ 包一层通知装饰器（reason="auto"），让阈值触发的压缩对 UI 可见。
        // 自动路径由 advisor 内部调用、拿不到返回值，必须靠装饰器上报 started/finished/failed。
        CompactionStrategy autoStrategy = new NotifyingCompactionStrategy(
                RecursiveSummarizationCompactionStrategy.builder(auxClient)
                        .maxEventsToKeep(MAX_EVENTS_TO_KEEP)
                        .tokenCountEstimator(tokenCountEstimator).build(),
                listener, "auto");

        // 手动压缩策略（保留 20，更激进）：<b>不</b>包装装饰器——手动路径由 {@code CodingAgent.runCompaction}
        // 直接调用 sessionService.compact 并拿到返回值自行上报生命周期事件；若再包装会导致失败被重复上报。
        CompactionStrategy manualStrategy = RecursiveSummarizationCompactionStrategy.builder(auxClient)
                .maxEventsToKeep(MANUAL_MAX_EVENTS_TO_KEEP)
                .overlapSize(MANUAL_OVERLAP_SIZE)
                .tokenCountEstimator(tokenCountEstimator).build();

        SessionMemoryAdvisor memoryAdvisor = SessionMemoryAdvisor.builder(sessionService)
                .defaultUserId(DEFAULT_USER_ID)
                .compactionTrigger(TokenCountTrigger.builder()
                        .threshold(COMPACTION_TOKEN_THRESHOLD)
                        .tokenCountEstimator(tokenCountEstimator).build())
                .compactionStrategy(autoStrategy)
                .build();

        // 环境信息 / git 状态只取一次，供所有 provider 共用：既省掉每 provider 一次 git 子进程，
        // 也把 gitStatus 的调用收敛到一处 —— 便于用 quietGitStatus 屏蔽它对 System.out 的杂散打印。
        String environmentInfo = AgentEnvironment.info();
        String gitStatus = quietGitStatus();

        // 为每个可用 provider 各建一个 ChatClient：共享同一套装饰工具 + 会话记忆 advisor + 系统模板，
        // 仅底层 ChatModel 不同。CodingAgent.submit 按激活 provider 选对应 ChatClient 实现跨家切换。
        java.util.Map<String, ChatClient> clients = new java.util.LinkedHashMap<>();
        for (LlmProvider provider : registry.allProviders()) {
            if (!provider.available()) {
                continue;
            }
            ChatClient c = ChatClient.builder(provider.chatModel())
                    .defaultSystem(s -> s.text(SYSTEM_TEMPLATE)
                            .param(AgentEnvironment.ENVIRONMENT_INFO_KEY, environmentInfo)
                            .param(AgentEnvironment.GIT_STATUS_KEY, gitStatus)
                            // 每个 client 烘焙自家默认模型，保证 defaultSystem 自洽；
                            // 每回合 submit 会用实际所选模型再覆盖此 param（见 CodingAgent.submit）。
                            .param(AgentEnvironment.AGENT_MODEL_KEY, provider.defaultModel()))
                    .defaultTools(toolsWithTask)
                    .defaultAdvisors(memoryAdvisor)
                    .build();
            clients.put(provider.id(), c);
        }

        return new AgentRuntime(clients, registry.active().id(), sessionService, sessionRepository,
                manualStrategy, tokenCountEstimator, reloadableSkill.skills(), decoratedSkillTool,
                reloadableSkill);
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
     */
    public record AgentRuntime(java.util.Map<String, ChatClient> clients,
                               String activeProviderId,
                               SessionService sessionService,
                               SessionRepository sessionRepository,
                               CompactionStrategy manualStrategy,
                               TokenCountEstimator tokenCountEstimator,
                               List<SkillInfo> skills,
                               ToolCallback skillTool,
                               ReloadableSkillTool reloadableSkill) {

        /** 便捷：激活 provider 的 ChatClient（单-provider 用法与旧代码兼容）。 */
        public ChatClient client() { return clients.get(activeProviderId); }
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

    /** 把 {@link Todos} 转成可显示的行：状态标记 + 内容。 */
    static List<String> toLines(Todos todos) {
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

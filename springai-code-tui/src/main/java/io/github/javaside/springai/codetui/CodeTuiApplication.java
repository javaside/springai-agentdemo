package io.github.javaside.springai.codetui;

import io.github.javaside.springai.codetui.agent.AgentTools;
import io.github.javaside.springai.codetui.agent.AnthropicProvider;
import io.github.javaside.springai.codetui.agent.CodingAgent;
import io.github.javaside.springai.codetui.agent.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.FileSessionRepository;
import io.github.javaside.springai.codetui.agent.McpRegistry;
import io.github.javaside.springai.codetui.agent.ModelPreference;
import io.github.javaside.springai.codetui.agent.OpenAiProvider;
import io.github.javaside.springai.codetui.agent.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.QwenProvider;
import io.github.javaside.springai.codetui.agent.SessionIds;
import io.github.javaside.springai.codetui.agent.ZhipuProvider;
import io.github.javaside.springai.codetui.agent.permission.DangerousPaths;
import io.github.javaside.springai.codetui.agent.permission.PermissionConfig;
import io.github.javaside.springai.codetui.agent.permission.PermissionConfigLoader;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.ui.CodeTuiView;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * springai-code-tui 入口 —— 接入真实 {@link CodingAgent}，装配模型 / 工具 / TUI 并启动。
 */
public class CodeTuiApplication {

    private static final Logger log = LoggerFactory.getLogger(CodeTuiApplication.class);

    /** {@code --permission-mode} 的参数名（取值 {@code plan}/{@code default}/{@code acceptEdits}）。 */
    private static final String PERMISSION_MODE_FLAG = "--permission-mode";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        // 多家 provider：谁配了 key 谁 available。至少需一家可用（通常 DeepSeek）。
        // base-url 可选：配了 *_BASE_URL 就覆盖，否则用各家内置默认（便于走代理/私有网关）。
        // 模型清单可选：配了 *_MODELS（逗号分隔，首项为默认模型）就覆盖，否则用各家内置清单。
        ProviderRegistry registry;
        try {
            registry = createProviderRegistry(root, System.getenv());
        } catch (IllegalStateException e) {
            System.out.println("⚠️  未检测到任何可用大模型 key。请至少配置一个：" +
                    "DEEPSEEK_API_KEY / ZHIPU_API_KEY / DASHSCOPE_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY，再运行。");
            return;
        }

        ConversationState state = new ConversationState();       // implements AgentListener
        restoreLastModel(registry, root, state);                 // 上次用的模型（<root>/.codetui/model.json）
        AtomicLong activeTurnId = new AtomicLong();

        // 会话选择（多槽）：默认每次启动 = 新 session（干净上下文、新文件，不读旧历史）；
        // 仅 -c / --continue 才恢复「最近一次」会话（按文件最后修改时间选）。仓库惰性加载，默认启动不碰盘。
        Path sessionsDir = root.resolve(".codetui").resolve("sessions");
        boolean wantContinue = hasContinueFlag(args);
        Optional<String> latest = FileSessionRepository.latestSessionId(sessionsDir);
        boolean resumed = wantContinue && latest.isPresent();
        String sessionId = resumed ? latest.get() : SessionIds.newId();

        // 权限引擎：整个进程<b>只有这一个</b>，三处装配点（AgentTools 装饰循环 / buildMemoryTools /
        // McpRegistry.decorate）共用它。必须建在 McpRegistry.init <b>之前</b>——MCP 工具在 init 里
        // 就被发现并装饰，那时就得拿到引擎；「MCP 工具延迟装饰」不是替代方案（运行期 /mcp 新启用的
        // server 会漏掉整层权限）。
        // 顺序上还有两件事在这之前落定：① --dangerously-skip-permissions 在任何工具可能执行之前就已解析，
        // ② 配置加载不会抛（PermissionConfigLoader 的降级契约：文件坏了就整份跳过并 WARN），
        // 故没有「引擎还没读到配置就先放行了几次」的窗口。
        boolean skipPermissions = hasBypassFlag(args);
        PermissionConfig permissionConfig = PermissionConfigLoader.load(root);
        PermissionMode cliMode = startupMode(args);
        // 优先级：--dangerously-skip-permissions > --permission-mode > 配置文件 defaultMode。
        // 前者是显式的「我知道我在做什么」，不该被一个同时出现的 --permission-mode plan 悄悄改掉语义。
        PermissionMode startMode = skipPermissions ? PermissionMode.BYPASS
                : (cliMode != null ? cliMode : permissionConfig.defaultMode());
        PermissionEngine permissionEngine = new PermissionEngine(root, permissionConfig, startMode);
        if (skipPermissions) {
            state.pushInfo("⚠ 已启用 --dangerously-skip-permissions：除 deny 规则与内置危险检查外，"
                    + "全部工具调用不再询问。");
            if (cliMode != null) {
                state.pushInfo("• 已忽略 --permission-mode " + cliMode.label()
                        + "：--dangerously-skip-permissions 优先级更高。");
            }
        } else if (startMode == PermissionMode.PLAN) {
            state.pushInfo("⏸ 已进入计划模式：只能读取和探索，产出计划后经你批准才会动手。");
        }
        String rootNotice = overBroadRootNotice(root);
        if (!rootNotice.isEmpty()) {
            state.pushInfo(rootNotice);
        }

        // MCP：启动期全量加载 .codetui/mcp.json（两层，含禁用项）→ 并行连接 enabled 项 → 发现+装饰工具。
        // 运行期 /mcp 可启停（详见 McpRegistry）。连接失败进 error 态、静默降级。
        // init 立即返回，连接在后台跑：实测一个远程 HTTP server 就要 5~8 秒，等它等的是一块空屏。
        // 「已发现 N 个工具」那行改由 registry 在全部连完时经 AgentListener.onMcpReady 回调补上
        // ——在这里同步算只会数到 0。
        McpRegistry mcpRegistry = McpRegistry.init(root, state, permissionEngine);

        // 从此处起装配 runtime/agent/view 直至 view.run() 全程 try/finally 关 MCP：
        // init() 已可能拉起子进程，任一装配步骤抛异常也不能让它们变孤儿。
        int exitCode = 0;
        try {
            AgentTools.AgentRuntime runtime =
                    AgentTools.build(registry, root, state, mcpRegistry, permissionEngine);
            CodingAgent agent = new CodingAgent(registry, runtime.clients(), state, sessionId, activeTurnId,
                    runtime.sessionService(), runtime.manualStrategy(), runtime.tokenCountEstimator(),
                    runtime.skills(), runtime.skillTool(), runtime.sessionRepository(),
                    runtime.reloadableSkill(), runtime.subagentRunner(), runtime.fileExternalizer(),
                    mcpRegistry, runtime.permissionEngine(), runtime.visionModels(),
                    runtime.backgroundRegistry(), runtime.backgroundResults(), runtime.interjections());

            // 开场提示：恢复则把上次对话回放进 scrollback（仿 Claude Code --continue，直观重现，见 ConversationState.replayHistory）；
            // -c 但无可恢复则说明；默认启动但存在旧会话则提示可用 -c。
            if (resumed) {
                state.replayHistory(runtime.sessionService().getMessages(sessionId));
            } else if (wantContinue) {
                state.pushInfo("（没有可恢复的会话，已开始新会话。）");
            } else if (latest.isPresent()) {
                state.pushInfo("（提示：存在上次会话，加 -c / --continue 启动可恢复。）");
            }

            CodeTuiView view = new CodeTuiView(state, agent, root);   // root：diff 渲染时读原文件 + 相对化展示路径
            view.run();
        } catch (Throwable t) {
            t.printStackTrace();
            exitCode = 1;
        } finally {
            // 保证任何路径（含装配期抛异常）都关闭 MCP 子进程（有界 2s），避免孤儿进程
            mcpRegistry.close();
        }
        // /exit 后立即终止 JVM。HTTP 客户端会留下非 daemon 线程——实测 OkHttp（OpenAI/智谱/Anthropic
        // 走这条）的 "OkHttp Dispatcher" 线程 keep-alive 达 60s，若不强制退出，进程会在 /exit 后卡 ~60s 才自然
        // 消亡（用户报错后立即 /exit 恰落在这 60s 窗口内，故"报过错就卡很久"）。TUI 退出语义即"立即终止"：
        // 会话已按事件原子落盘、无待刷新状态，quit() 也已在 run() 返回前恢复终端，故 System.exit 安全。
        System.exit(exitCode);
    }

    /** 按环境变量构建全部 provider 并加载工作区思考配置。供 main 与测试共用。 */
    static ProviderRegistry createProviderRegistry(Path root, java.util.Map<String, String> env) {
        return new ProviderRegistry(java.util.List.of(
                new DeepSeekProvider(env.get("DEEPSEEK_API_KEY"), env.get("DEEPSEEK_BASE_URL"), env.get("DEEPSEEK_MODELS")),
                new ZhipuProvider(env.get("ZHIPU_API_KEY"), env.get("ZHIPU_BASE_URL"), env.get("ZHIPU_MODELS")),
                new QwenProvider(env.get("DASHSCOPE_API_KEY"), env.get("DASHSCOPE_BASE_URL"), env.get("DASHSCOPE_MODELS")),
                new AnthropicProvider(env.get("ANTHROPIC_API_KEY"), env.get("ANTHROPIC_BASE_URL"), env.get("ANTHROPIC_MODELS")),
                new OpenAiProvider(env.get("OPENAI_API_KEY"), env.get("OPENAI_BASE_URL"), env.get("OPENAI_MODELS"))),
                io.github.javaside.springai.codetui.agent.thinking.ThinkingConfigStore.load(root));
    }

    /**
     * 过宽工作区的启动提示；正常 root 返回空串。
     *
     * <p><b>必须有这一行</b>：过宽时判定会变严格（工作区豁免失效），用户会突然多出很多审批。
     * 不说明原因的话，表现出来就是「这程序今天怎么老弹窗」——<b>静默变严格与静默失效同样糟</b>。
     */
    static String overBroadRootNotice(Path root) {
        if (!DangerousPaths.isOverBroadRoot(root)) {
            return "";
        }
        return "⚠ 当前工作区过宽（" + root + "）：「工作区内」不再作为豁免依据，"
                + "涉及系统位置的操作会照常询问。建议切到一个具体的项目目录再运行。";
    }

    /**
     * 恢复上次用的模型（{@code <root>/.codetui/model.json}）。
     *
     * <p><b>为什么是「构造完 registry 之后 select」而不是做成构造入参</b>：
     * {@link ProviderRegistry} 不该认识磁盘，且改构造签名要牵动全部调用点与测试。
     * 构造后 select 还白送一个失效检测——{@code select()} 对未知模型是<b>静默忽略</b>的，
     * 所以 select 完比一下 {@code activeModelId()} 是不是等于要恢复的那个，
     * 不等就说明「这个模型现在用不了了」。不需要新增任何 API。
     * （那条静默忽略的行为由 {@code ProviderRegistryTest.selectUnknownModelIsIgnored} 钉着。）
     *
     * <p><b>必须在 {@link ConversationState} 建好之后调用</b>：回退提示要走
     * {@code state.pushInfo} 落进开场 scrollback，和权限模式的开场提示同一条路。
     *
     * <p><b>与 {@code -c} 正交</b>：模型记忆独立于会话恢复，不带 {@code -c} 的默认启动
     * 照样生效——那正是这个功能存在的理由。
     *
     * <p><b>回退时刻意不清掉盘上那条记录</b>，于是只要用户不再选一次模型，
     * 每次启动都会看到同一句「已回退」。这是<b>有意的</b>：清掉的代价是——你只是临时
     * 注释掉一个 key 跑一次，回头把 key 加回来，记忆已经没了。让提示重复出现，
     * 远好过替用户「忘掉」他明确选过的东西。
     *
     * <p><b>也该留在 {@code AgentTools.build} 之前（潜伏约束，今天还咬不到人）</b>：
     * {@code build} 会把 {@code registry.active().id()} <b>快照</b>进
     * {@code AgentRuntime.activeProviderId}。挪到 build 之后，跨家恢复时那份快照就是恢复<b>前</b>
     * 那一家。今天没有实际后果——读这个快照的只有 {@code AgentRuntime.client()}，
     * 而它<b>只在测试里</b>被调用；生产路径每回合现取
     * （{@code CodingAgent.submit} 走 {@code clientsByProvider.get(active.id())}）。
     * 写在这里是因为「哪天有人让生产代码用上 {@code client()}」和「哪天有人挪动这行」
     * 是两件独立的事，凑到一起才出事，而那时谁都不会想到看这个方法。
     */
    static void restoreLastModel(ProviderRegistry registry, Path root, ConversationState state) {
        Optional<String> remembered = ModelPreference.read(root);
        if (remembered.isEmpty()) {
            return;                            // 首次运行：走原有行为，一个字都不多说
        }
        String id = remembered.get();
        registry.select(id);
        if (!id.equals(registry.activeModelId())) {
            state.pushInfo("• 上次用的模型 " + id + " 现在不可用，已回退到 "
                    + registry.activeModelId() + "。");
        }
    }

    /** 是否带续跑启动选项（仿 Claude Code 的 -c / --continue）。 */
    private static boolean hasContinueFlag(String[] args) {
        for (String a : args) {
            if ("-c".equals(a) || "--continue".equals(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否带「跳过权限检查」启动选项（仿 Claude Code 的 {@code --dangerously-skip-permissions}）。
     *
     * <p><b>必须整串精确相等</b>：前缀相近的参数（{@code --dangerously-skip}）不得误判成开了裸奔模式。
     *
     * <p><b>它只决定「起步于 BYPASS」，不再是唯一入口</b>：BYPASS 已在 {@code Shift+Tab} 环上四档平权
     * （见 {@link PermissionMode#next()}），运行期不带本参数也切得进去。本参数保留的意义只剩
     * 「启动即裸奔」——半无人值守的脚本化启动仍需要它。
     */
    static boolean hasBypassFlag(String[] args) {
        for (String a : args) {
            if ("--dangerously-skip-permissions".equals(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 {@code --permission-mode <值>} 或 {@code --permission-mode=<值>}；无/非法/想进 BYPASS 一律返回 null
     * （调用方回退到配置文件的 {@code defaultMode}）。
     *
     * <p><b>刻意不认 {@code bypass}</b>：<b>启动即裸奔</b>只该有一个显眼的开关
     * （{@code --dangerously-skip-permissions}）。这里认了它，那道开关就多出一个不显眼的同义词——
     * 用户看着命令行以为只是「选了个模式」。运行期切进 BYPASS 是另一回事：用户当面按 {@code Shift+Tab}、
     * 在场、有意图，那条路四档平权、不受本方法约束。
     *
     * <p><b>必须整串精确相等</b>：{@code --permission-modex} 这种前缀相近的参数不得误判
     * （与 {@link #hasBypassFlag} 同纪律）。
     */
    static PermissionMode startupMode(String[] args) {
        String raw = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (PERMISSION_MODE_FLAG.equals(a)) {
                if (i + 1 >= args.length) {
                    return null;                       // 缺值：别读到越界，也别猜
                }
                // 下一个参数是另一个选项（如 `--permission-mode --continue`）→ 当缺值处理。
                // 否则会把 `--continue` 当成模式名，认不出后静默回退——而 hasContinueFlag 仍会
                // 独立扫到它，于是「模式被忽略、continue 照常生效」，用户看不出自己漏写了值。
                if (args[i + 1] != null && args[i + 1].startsWith("--")) {
                    log.warn("--permission-mode 后面跟的是另一个选项（{}），当作没给值处理。", args[i + 1]);
                    return null;
                }
                raw = args[i + 1];
                break;
            }
            if (a != null && a.startsWith(PERMISSION_MODE_FLAG + "=")) {
                raw = a.substring(PERMISSION_MODE_FLAG.length() + 1);
                break;
            }
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (PermissionMode.BYPASS.name().equalsIgnoreCase(v)) {
            log.warn("--permission-mode 不接受 bypass：--dangerously-skip-permissions 已经是"
                    + "「启动即进该档」的写法，不再设第二条等价路径（运行期 Shift+Tab 切进去是允许的）。已忽略。");
            return null;
        }
        for (PermissionMode m : PermissionMode.values()) {
            if (m.name().equalsIgnoreCase(v)) {
                return m;
            }
        }
        if ("acceptEdits".equalsIgnoreCase(v)) {       // 兼容 Claude Code 风格的小驼峰
            return PermissionMode.ACCEPT_EDITS;
        }
        log.warn("未知 --permission-mode='{}'，已忽略。可选值：default / acceptEdits / plan。", v);
        return null;
    }

}

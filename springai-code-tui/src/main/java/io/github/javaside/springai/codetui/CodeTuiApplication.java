package io.github.javaside.springai.codetui;

import io.github.javaside.springai.codetui.agent.AgentTools;
import io.github.javaside.springai.codetui.agent.AnthropicProvider;
import io.github.javaside.springai.codetui.agent.CodingAgent;
import io.github.javaside.springai.codetui.agent.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.FileSessionRepository;
import io.github.javaside.springai.codetui.agent.McpClientManager;
import io.github.javaside.springai.codetui.agent.McpConfigLoader;
import io.github.javaside.springai.codetui.agent.McpServerConfig;
import io.github.javaside.springai.codetui.agent.OpenAiProvider;
import io.github.javaside.springai.codetui.agent.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.QwenProvider;
import io.github.javaside.springai.codetui.agent.SessionIds;
import io.github.javaside.springai.codetui.agent.ZhipuProvider;
import io.github.javaside.springai.codetui.ui.CodeTuiView;
import io.github.javaside.springai.codetui.ui.ConversationState;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.ai.tool.ToolCallback;

/**
 * springai-code-tui 入口 —— 接入真实 {@link CodingAgent}，装配模型 / 工具 / TUI 并启动。
 */
public class CodeTuiApplication {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        // 多家 provider：谁配了 key 谁 available。至少需一家可用（通常 DeepSeek）。
        // base-url 可选：配了 *_BASE_URL 就覆盖，否则用各家内置默认（便于走代理/私有网关）。
        // 模型清单可选：配了 *_MODELS（逗号分隔，首项为默认模型）就覆盖，否则用各家内置清单。
        ProviderRegistry registry;
        try {
            registry = new ProviderRegistry(java.util.List.of(
                    new DeepSeekProvider(System.getenv("DEEPSEEK_API_KEY"), System.getenv("DEEPSEEK_BASE_URL"), System.getenv("DEEPSEEK_MODELS")),
                    new ZhipuProvider(System.getenv("ZHIPU_API_KEY"), System.getenv("ZHIPU_BASE_URL"), System.getenv("ZHIPU_MODELS")),
                    new QwenProvider(System.getenv("DASHSCOPE_API_KEY"), System.getenv("DASHSCOPE_BASE_URL"), System.getenv("DASHSCOPE_MODELS")),
                    new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY"), System.getenv("ANTHROPIC_BASE_URL"), System.getenv("ANTHROPIC_MODELS")),
                    new OpenAiProvider(System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_BASE_URL"), System.getenv("OPENAI_MODELS"))));
        } catch (IllegalStateException e) {
            System.out.println("⚠️  未检测到任何可用大模型 key。请至少配置一个：" +
                    "DEEPSEEK_API_KEY / ZHIPU_API_KEY / DASHSCOPE_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY，再运行。");
            return;
        }

        ConversationState state = new ConversationState();       // implements AgentListener
        AtomicLong activeTurnId = new AtomicLong();

        // 会话选择（多槽）：默认每次启动 = 新 session（干净上下文、新文件，不读旧历史）；
        // 仅 -c / --continue 才恢复「最近一次」会话（按文件最后修改时间选）。仓库惰性加载，默认启动不碰盘。
        Path sessionsDir = root.resolve(".codetui").resolve("sessions");
        boolean wantContinue = hasContinueFlag(args);
        Optional<String> latest = FileSessionRepository.latestSessionId(sessionsDir);
        boolean resumed = wantContinue && latest.isPresent();
        String sessionId = resumed ? latest.get() : SessionIds.newId();

        // MCP：启动期读 .codetui/mcp.json（两层）→ 并行连接 → 发现工具。连接失败静默降级为空。
        java.util.List<McpServerConfig> mcpConfigs = McpConfigLoader.load(root);
        McpClientManager mcpManager = McpClientManager.connectAll(mcpConfigs);
        java.util.List<ToolCallback> mcpTools = mcpManager.toolCallbacks();
        if (!mcpTools.isEmpty()) {
            state.pushInfo("（MCP：已发现 " + mcpTools.size() + " 个工具。）");
        }

        // 从此处起装配 runtime/agent/view 直至 view.run() 全程 try/finally 关 MCP：
        // connectAll() 已可能拉起子进程，任一装配步骤抛异常也不能让它们变孤儿。
        int exitCode = 0;
        try {
            AgentTools.AgentRuntime runtime = AgentTools.build(registry, root, state, mcpTools);
            CodingAgent agent = new CodingAgent(registry, runtime.clients(), state, sessionId, activeTurnId,
                    runtime.sessionService(), runtime.manualStrategy(), runtime.tokenCountEstimator(),
                    runtime.skills(), runtime.skillTool(), runtime.sessionRepository(),
                    runtime.reloadableSkill(), runtime.subagentRunner(), runtime.fileExternalizer());

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
            mcpManager.close();
        }
        // /exit 后立即终止 JVM。HTTP 客户端会留下非 daemon 线程——实测 OkHttp（OpenAI/智谱/Anthropic
        // 走这条）的 "OkHttp Dispatcher" 线程 keep-alive 达 60s，若不强制退出，进程会在 /exit 后卡 ~60s 才自然
        // 消亡（用户报错后立即 /exit 恰落在这 60s 窗口内，故"报过错就卡很久"）。TUI 退出语义即"立即终止"：
        // 会话已按事件原子落盘、无待刷新状态，quit() 也已在 run() 返回前恢复终端，故 System.exit 安全。
        System.exit(exitCode);
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

}

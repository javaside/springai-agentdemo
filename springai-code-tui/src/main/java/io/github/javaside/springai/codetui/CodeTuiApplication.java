package io.github.javaside.springai.codetui;

import io.github.javaside.springai.codetui.agent.AgentTools;
import io.github.javaside.springai.codetui.agent.AnthropicProvider;
import io.github.javaside.springai.codetui.agent.CodingAgent;
import io.github.javaside.springai.codetui.agent.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.FileSessionRepository;
import io.github.javaside.springai.codetui.agent.OpenAiProvider;
import io.github.javaside.springai.codetui.agent.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.SessionIds;
import io.github.javaside.springai.codetui.agent.ZhipuProvider;
import io.github.javaside.springai.codetui.ui.CodeTuiView;
import io.github.javaside.springai.codetui.ui.ConversationState;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * springai-code-tui 入口 —— 接入真实 {@link CodingAgent}，装配模型 / 工具 / TUI 并启动。
 */
public class CodeTuiApplication {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        // 三家 provider：谁配了 key 谁 available。至少需一家可用（通常 DeepSeek）。
        // base-url 可选：配了 *_BASE_URL 就覆盖，否则用各家内置默认（便于走代理/私有网关）。
        ProviderRegistry registry;
        try {
            registry = new ProviderRegistry(java.util.List.of(
                    new DeepSeekProvider(System.getenv("DEEPSEEK_API_KEY"), System.getenv("DEEPSEEK_BASE_URL")),
                    new ZhipuProvider(System.getenv("ZHIPU_API_KEY"), System.getenv("ZHIPU_BASE_URL")),
                    new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY"), System.getenv("ANTHROPIC_BASE_URL")),
                    new OpenAiProvider(System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_BASE_URL"))));
        } catch (IllegalStateException e) {
            System.out.println("⚠️  未检测到任何可用大模型 key。请至少配置一个：" +
                    "DEEPSEEK_API_KEY / ZHIPU_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY，再运行。");
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

        AgentTools.AgentRuntime runtime = AgentTools.build(registry, root, state);
        CodingAgent agent = new CodingAgent(registry, runtime.clients(), state, sessionId, activeTurnId,
                runtime.sessionService(), runtime.manualStrategy(), runtime.tokenCountEstimator(),
                runtime.skills(), runtime.skillTool(), runtime.sessionRepository(),
                runtime.reloadableSkill(), runtime.subagentRunner());

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
        try {
            view.run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);   // 交互期崩溃：打印后同样强制退出（否则也会卡在下方 60s 非 daemon 线程上）
        }
        // /exit 后立即终止 JVM。HTTP 客户端会留下非 daemon 线程——实测 OkHttp（OpenAI/智谱/Anthropic
        // 走这条）的 "OkHttp Dispatcher" 线程 keep-alive 达 60s，若不强制退出，进程会在 /exit 后卡 ~60s 才自然
        // 消亡（用户报错后立即 /exit 恰落在这 60s 窗口内，故"报过错就卡很久"）。TUI 退出语义即"立即终止"：
        // 会话已按事件原子落盘、无待刷新状态，quit() 也已在 run() 返回前恢复终端，故 System.exit 安全。
        System.exit(0);
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

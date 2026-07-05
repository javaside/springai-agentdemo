package com.example.springai.codetui;

import com.example.springai.codetui.agent.AgentTools;
import com.example.springai.codetui.agent.AnthropicProvider;
import com.example.springai.codetui.agent.CodingAgent;
import com.example.springai.codetui.agent.DeepSeekProvider;
import com.example.springai.codetui.agent.FileSessionRepository;
import com.example.springai.codetui.agent.OpenAiProvider;
import com.example.springai.codetui.agent.ProviderRegistry;
import com.example.springai.codetui.ui.CodeTuiView;
import com.example.springai.codetui.ui.ConversationState;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
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
                    new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY"), System.getenv("ANTHROPIC_BASE_URL")),
                    new OpenAiProvider(System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_BASE_URL"))));
        } catch (IllegalStateException e) {
            System.out.println("⚠️  未检测到任何可用大模型 key。请至少配置一个：" +
                    "DEEPSEEK_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY，再运行。");
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
        String sessionId = resumed ? latest.get() : newSessionId();

        AgentTools.AgentRuntime runtime = AgentTools.build(registry, root, state);
        CodingAgent agent = new CodingAgent(registry, runtime.clients(), state, sessionId, activeTurnId,
                runtime.sessionService(), runtime.manualStrategy(), runtime.tokenCountEstimator(),
                runtime.skills(), runtime.skillTool(), runtime.sessionRepository(),
                runtime.reloadableSkill());

        // 开场提示：恢复了显示历史条数；-c 但无可恢复则说明；默认启动但存在旧会话则提示可用 -c。
        if (resumed) {
            int n = runtime.sessionService().getEvents(sessionId).size();
            state.pushInfo("↺ 已恢复上次会话（" + n + " 条历史）。输入 /continue 可继续上次未完成的计划。");
        } else if (wantContinue) {
            state.pushInfo("（没有可恢复的会话，已开始新会话。）");
        } else if (latest.isPresent()) {
            state.pushInfo("（提示：存在上次会话，加 -c / --continue 启动可恢复。）");
        }

        CodeTuiView view = new CodeTuiView(state, agent, root);   // root：diff 渲染时读原文件 + 相对化展示路径
        view.run();
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

    /** 新会话 id：UTC 时间戳 + 短随机，文件名安全（[0-9A-Za-z-]），可按名近似排序。 */
    private static String newSessionId() {
        String ts = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        return ts + "-" + UUID.randomUUID().toString().substring(0, 6);
    }
}

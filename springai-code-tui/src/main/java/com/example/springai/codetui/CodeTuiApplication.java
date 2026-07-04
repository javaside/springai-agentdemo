package com.example.springai.codetui;

import com.example.springai.codetui.agent.AgentTools;
import com.example.springai.codetui.agent.AnthropicProvider;
import com.example.springai.codetui.agent.CodingAgent;
import com.example.springai.codetui.agent.DeepSeekProvider;
import com.example.springai.codetui.agent.OpenAiProvider;
import com.example.springai.codetui.agent.ProviderRegistry;
import com.example.springai.codetui.ui.CodeTuiView;
import com.example.springai.codetui.ui.ConversationState;

import java.nio.file.Path;
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
        String sessionId = "code-tui-session";                   // v1 单会话固定 id

        AgentTools.AgentRuntime runtime = AgentTools.build(registry, root, state);
        CodingAgent agent = new CodingAgent(registry, runtime.clients(), state, sessionId, activeTurnId,
                runtime.sessionService(), runtime.manualStrategy(), runtime.tokenCountEstimator(),
                runtime.skills(), runtime.skillTool(), runtime.sessionRepository(),
                runtime.reloadableSkill());
        CodeTuiView view = new CodeTuiView(state, agent, root);   // root：diff 渲染时读原文件 + 相对化展示路径
        view.run();
    }
}

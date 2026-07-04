package com.example.springai.codetui;

import com.example.springai.codetui.agent.AgentTools;
import com.example.springai.codetui.agent.CodingAgent;
import com.example.springai.codetui.agent.DeepSeekProvider;
import com.example.springai.codetui.agent.ProviderRegistry;
import com.example.springai.codetui.ui.CodeTuiView;
import com.example.springai.codetui.ui.ConversationState;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * springai-code-tui 入口 —— 接入真实 {@link CodingAgent}，装配模型 / 工具 / TUI 并启动。
 */
public class CodeTuiApplication {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("⚠️  未检测到 DEEPSEEK_API_KEY。请先：export DEEPSEEK_API_KEY=你的key 再运行。");
            return;
        }

        // provider 注册表：目前仅 DeepSeek（现役、默认激活；模型 deepseek-v4-flash）。
        // 后续多家接入时在此追加 provider，AgentTools.build 会为每个可用 provider 各建一个 ChatClient。
        ProviderRegistry registry = new ProviderRegistry(List.of(new DeepSeekProvider(apiKey)));

        ConversationState state = new ConversationState();       // implements AgentListener
        AtomicLong activeTurnId = new AtomicLong();               // 交给 CodingAgent 生成 id
        String sessionId = "code-tui-session";                    // v1 单会话固定 id

        AgentTools.AgentRuntime runtime = AgentTools.build(registry, root, state);
        ChatClient client = runtime.client();
        CodingAgent agent = new CodingAgent(client, state, sessionId, activeTurnId,
                runtime.sessionService(), runtime.manualStrategy(), runtime.tokenCountEstimator(),
                runtime.skills(), runtime.skillTool(), runtime.sessionRepository());
        CodeTuiView view = new CodeTuiView(state, agent, root);   // root：diff 渲染时读原文件 + 相对化展示路径
        view.run();
    }
}

package com.example.springai.codetui;

import com.example.springai.codetui.agent.AgentTools;
import com.example.springai.codetui.agent.CodingAgent;
import com.example.springai.codetui.ui.CodeTuiView;
import com.example.springai.codetui.ui.ConversationState;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Path;
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

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.deepseek.com")
                .build();

        // 模型名 "deepseek-v4-flash"（V4 现役；旧的 deepseek-chat/deepseek-reasoner 将于 2026-07-24 停用，
        // 期间被透明路由到 v4-flash）。显式设置，避免依赖会过期的默认名。
        DeepSeekChatModel model = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(DeepSeekChatOptions.builder()
                        .model("deepseek-v4-flash")
                        .build())
                .build();

        ConversationState state = new ConversationState();       // implements AgentListener
        AtomicLong activeTurnId = new AtomicLong();               // 交给 CodingAgent 生成 id
        String sessionId = "code-tui-session";                    // v1 单会话固定 id

        AgentTools.AgentRuntime runtime = AgentTools.build(model, root, state);
        ChatClient client = runtime.client();
        CodingAgent agent = new CodingAgent(client, state, sessionId, activeTurnId,
                runtime.sessionService(), runtime.manualStrategy());
        CodeTuiView view = new CodeTuiView(state, agent, root);   // root：diff 渲染时读原文件 + 相对化展示路径
        view.run();
    }
}

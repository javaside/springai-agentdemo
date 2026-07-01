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
 * springai-code-tui 入口 —— 里程碑3：接入真实 {@link CodingAgent}，
 * 取代里程碑1/2 的回显桩与被动重绘自检探针。
 */
public class CodeTuiApplication {
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("⚠️  未检测到 DEEPSEEK_API_KEY。请先：export DEEPSEEK_API_KEY=你的key 再运行。");
            return;
        }

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.deepseek.com")
                .build();

        // 模型名用字符串 "deepseek-chat"（枚举常量 DEEPSEEK_CHAT 在 2.0 已 @Deprecated）
        DeepSeekChatModel model = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(DeepSeekChatOptions.builder()
                        .build())
                .build();

        ConversationState state = new ConversationState();       // implements AgentListener
        AtomicLong activeTurnId = new AtomicLong();               // 交给 CodingAgent 生成 id
        Path root = Path.of(System.getProperty("user.dir"));
        String sessionId = "code-tui-session";                    // v1 单会话固定 id

        ChatClient client = AgentTools.build(model, root, state);
        CodingAgent agent = new CodingAgent(client, state, sessionId, activeTurnId);
        CodeTuiView view = new CodeTuiView(state, agent);         // 与骨架同签名，第二参从回显桩换成真 agent
        view.run();
    }
}

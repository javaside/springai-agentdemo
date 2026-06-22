package com.example.springai.agent;

import com.example.springai.agent.demo.ChatMemoryDemo;
import com.example.springai.agent.demo.Demo;
import com.example.springai.agent.demo.MultiStepAgentDemo;
import com.example.springai.agent.demo.ToolCallingDemo;
import com.example.springai.agent.demo.ToolMemoryAdvisorDemo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import java.util.List;

/**
 * springai-agent-demo 入口 —— 纯 Java，不依赖 Spring Boot。
 *
 * <p>和 core 一样，DeepSeekApi / ChatModel / ChatClient 都由我们手动创建。
 * （MCP 示例因为配置较重、最能体现 Spring Boot 的便利，已迁到 springai-boot-demo。）
 */
public class AgentDemoApplication {

    public static void main(String[] args) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("⚠️  未检测到环境变量 DEEPSEEK_API_KEY，调用 DeepSeek 的示例会失败。");
            System.out.println("   设置方式（macOS/Linux）：export DEEPSEEK_API_KEY=你的key  然后重新运行。\n");
        }

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(apiKey == null ? "" : apiKey)
                .baseUrl("https://api.deepseek.com")
                .build();

        // 模型名用字符串 "deepseek-chat"（枚举常量 DEEPSEEK_CHAT 在 2.0 已 @Deprecated）
        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(DeepSeekChatOptions.builder()
                        .model("deepseek-chat")
                        .temperature(0.7)
                        .build())
                .build();

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是一个会使用工具的中文 AI 助手。需要外部信息时请调用合适的工具，再据此回答。")
                .build();

        List<Demo> demos = List.of(
                new ToolCallingDemo(chatClient),
                new ChatMemoryDemo(chatClient),
                new MultiStepAgentDemo(chatClient),
                new ToolMemoryAdvisorDemo(chatClient)
        );

        new ConsoleMenu("Spring AI 智能体（Agent）示例（原始 API）", demos).run();
    }
}

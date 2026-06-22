package com.example.springai.core;

import com.example.springai.core.demo.ChatDemo;
import com.example.springai.core.demo.Demo;
import com.example.springai.core.demo.EmbeddingDemo;
import com.example.springai.core.demo.PromptTemplateDemo;
import com.example.springai.core.demo.RagDemo;
import com.example.springai.core.demo.StreamDemo;
import com.example.springai.core.demo.StructuredOutputDemo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

import java.util.List;

/**
 * springai-core-demo 入口 —— 纯 Java，不依赖 Spring Boot。
 *
 * <p>这就是一个普通的 {@code main} 方法。注意看：下面这些对象（DeepSeekApi、ChatModel、
 * ChatClient、EmbeddingModel）全是我们【自己手动 new / build 出来】的。
 *
 * <p>★ 学习重点：这一整段“手动接线”的代码，在 springai-boot-demo 里会【全部消失】——
 *   因为 Spring Boot 的 starter 会自动配置好这些 Bean。对比两个模块，你就能彻底理解
 *   “自动配置（auto-configuration）到底替你做了什么”。
 */
public class CoreDemoApplication {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("⚠️  未检测到环境变量 DEEPSEEK_API_KEY，调用 DeepSeek 的示例会失败。");
            System.out.println("   设置方式（macOS/Linux）：export DEEPSEEK_API_KEY=你的key  然后重新运行。\n");
        }

        // 1) 连接 DeepSeek 的底层 HTTP 客户端
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(apiKey == null ? "" : apiKey)
                .baseUrl("https://api.deepseek.com")
                .build();

        // 2) 对话模型：包装 api + 默认参数（模型名、温度 0.7）
        //    模型名用字符串 "deepseek-chat"（DeepSeek 当前可用模型）。
        //    注：DeepSeekApi.ChatModel 枚举里的 DEEPSEEK_CHAT 常量在 2.0 已标记 @Deprecated，故改用字符串。
        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(DeepSeekChatOptions.builder()
                        .model("deepseek-chat")
                        .temperature(0.7)
                        .build())
                .build();

        // 3) ChatClient：推荐的高层对话入口，这里加一个默认系统提示词
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是一个乐于助人的中文 AI 助手，回答尽量简洁清晰。")
                .build();

        // 4) 本地向量模型：new 出来后必须调用 afterPropertiesSet() 触发模型加载（首次会下载 ONNX 文件）
        TransformersEmbeddingModel embeddingModel = new TransformersEmbeddingModel();
        embeddingModel.afterPropertiesSet();

        // 5) 手动把示例装进列表（以前这一步是 Spring 自动收集的）
        List<Demo> demos = List.of(
                new ChatDemo(chatClient),
                new PromptTemplateDemo(chatClient),
                new StreamDemo(chatClient),
                new StructuredOutputDemo(chatClient),
                new EmbeddingDemo(embeddingModel),
                new RagDemo(chatClient, embeddingModel)
        );

        new ConsoleMenu("Spring AI 核心能力示例（原始 API）", demos).run();
    }
}

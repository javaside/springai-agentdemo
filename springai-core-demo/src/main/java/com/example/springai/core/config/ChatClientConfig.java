package com.example.springai.core.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供一个共享的 {@link ChatClient}。
 *
 * <p>ChatClient 是 Spring AI 推荐的对话入口（类似 RestClient/WebClient 的流式 API）。
 * Spring AI 的 starter 已经自动装配好了 {@code ChatClient.Builder}（底层连的是 DeepSeek），
 * 我们在这里基于它构建一个带默认“系统提示词”的 ChatClient，供各个示例直接注入使用。
 */
@Configuration
public class ChatClientConfig {

    /**
     * 注意这里的“两层”关系，初学者最容易在这里混淆：
     *   - 方法参数 {@code ChatClient.Builder} 是 starter【自动配置】的，我们没声明它，直接拿来用；
     *   - 返回的 {@code ChatClient} 是我们【手动 @Bean】的 —— 因为要给它加一个默认系统提示词，
     *     所以才显式配置。这也是全项目里唯一一个“手写”的 AI Bean，其余都靠自动配置。
     * 运行「自动配置揭秘」示例，能把这层关系直接打印出来对照。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                // 默认系统提示词：设定 AI 的角色/风格，会作用于所有用它发起的对话
                .defaultSystem("你是一个乐于助人的中文 AI 助手，回答尽量简洁清晰。")
                .build();
    }
}

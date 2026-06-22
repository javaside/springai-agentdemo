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

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                // 默认系统提示词：设定 AI 的角色/风格，会作用于所有用它发起的对话
                .defaultSystem("你是一个乐于助人的中文 AI 助手，回答尽量简洁清晰。")
                .build();
    }
}

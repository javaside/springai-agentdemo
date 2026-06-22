package com.example.springai.boot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供共享 {@link ChatClient}。
 *
 * <p>对比一下 core 模块：那边是 {@code ChatClient.builder(chatModel)}，其中 chatModel 要自己 new；
 * 这里参数 {@code ChatClient.Builder} 是 starter【自动配置】好的，直接拿来用即可——
 * 这一个差别，就是“自动装配替你做的事”的缩影。
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一个乐于助人的中文 AI 助手，回答尽量简洁清晰。")
                .build();
    }
}

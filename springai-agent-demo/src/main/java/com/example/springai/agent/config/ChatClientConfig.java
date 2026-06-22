package com.example.springai.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供共享的 {@link ChatClient}（底层连 DeepSeek）。
 * 各智能体示例在此基础上再附加工具、记忆等能力。
 */
@Configuration
public class ChatClientConfig {

    /**
     * 参数 {@code ChatClient.Builder} 由 starter【自动配置】；返回的 {@code ChatClient} 由我们【手动 @Bean】。
     * 运行「自动配置揭秘」示例可对照查看二者来源。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一个会使用工具的中文 AI 助手。需要外部信息时请调用合适的工具，再据此回答。")
                .build();
    }
}

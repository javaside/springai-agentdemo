package com.example.springai.boot.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 示例：极简对话 —— 体会“自动装配”后业务代码有多干净。
 *
 * <p>整个类只需注入一个 {@code ChatClient} 就能对话。底层的 DeepSeekApi、ChatModel 是怎么
 * 创建、怎么读取 API Key、怎么设默认参数的，全部由 starter 自动搞定，业务代码完全不用关心。
 * （回看 core 的 CoreDemoApplication，那些步骤都要你亲手写。）
 */
@Component
public class BootChatDemo implements Demo {

    private final ChatClient chatClient;

    public BootChatDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "极简对话（注入 ChatClient 即可）";
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public void run() {
        String question = "用一句话说明：Spring Boot 的自动配置帮开发者省去了什么？";
        System.out.println("问：" + question);
        String answer = chatClient.prompt().user(question).call().content();
        System.out.println("答：" + answer);
    }
}

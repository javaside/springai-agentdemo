package com.example.springai.core.demo;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 示例 1：最基础的对话。
 *
 * <p>核心就是一行链式调用：prompt() -> user(问题) -> call()（同步等待结果）-> content()（取文本）。
 * 本类是个普通 Java 类，{@code chatClient} 由 {@code CoreDemoApplication} 手动 new 好后传进来。
 */
public class ChatDemo implements Demo {

    private final ChatClient chatClient;

    public ChatDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "基础对话（ChatClient 同步调用）";
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public void run() {
        String question = "用一句话介绍一下 Spring AI 是什么。";
        System.out.println("问：" + question);

        String answer = chatClient.prompt()   // 开始构建一次请求
                .user(question)                // 用户消息
                .call()                        // 同步调用，阻塞等待模型返回
                .content();                    // 取出纯文本回答

        System.out.println("答：" + answer);
    }
}

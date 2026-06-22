package com.example.springai.agent.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Component;

/**
 * 示例 2：多轮对话记忆（ChatMemory）。
 *
 * <p>大模型本身是“无状态”的——它不会自动记得上一句。要实现连续对话，需要把历史消息
 * 一起发给模型。Spring AI 用 {@link ChatMemory} 保存历史，用
 * {@link MessageChatMemoryAdvisor} 在每次请求时自动把历史拼进去。
 *
 * <p>本示例发起两轮对话：第二轮的问题依赖第一轮的内容（指代“它”），以此验证“记住了”。
 */
@Component
public class ChatMemoryDemo implements Demo {

    private final ChatClient chatClient;

    public ChatMemoryDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "多轮对话记忆（ChatMemory）";
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public void run() {
        // 一块“记忆区”，最多保留最近 20 条消息
        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        // 用 conversationId 区分不同会话（同一个 id 共享同一段记忆）
        String conversationId = "user-1";

        String q1 = "我最喜欢的编程语言是 Java，请记住。";
        String a1 = ask(memoryAdvisor, conversationId, q1);
        System.out.println("第1轮 问：" + q1);
        System.out.println("第1轮 答：" + a1 + "\n");

        String q2 = "我刚才说我最喜欢的编程语言是什么？";
        String a2 = ask(memoryAdvisor, conversationId, q2);
        System.out.println("第2轮 问：" + q2);
        System.out.println("第2轮 答：" + a2);
        System.out.println("\n（若第2轮答出「Java」，说明记忆生效了）");
    }

    private String ask(MessageChatMemoryAdvisor advisor, String conversationId, String userText) {
        return chatClient.prompt()
                .advisors(advisor)
                // 指定本次对话使用哪段记忆
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(userText)
                .call()
                .content();
    }
}

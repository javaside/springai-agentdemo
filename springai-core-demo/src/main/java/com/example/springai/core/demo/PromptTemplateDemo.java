package com.example.springai.core.demo;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 示例 2：Prompt 模板 + 临时系统提示词。
 *
 * <p>用占位符 {@code {xxx}} 写提示词模板，再用 param 填值，避免手工拼字符串。
 * 同时演示在单次请求里用 system(...) 覆盖默认角色设定。
 */
public class PromptTemplateDemo implements Demo {

    private final ChatClient chatClient;

    public PromptTemplateDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "Prompt 模板（占位符 + System 提示词）";
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public void run() {
        String topic = "递归";
        String audience = "10 岁小学生";

        String answer = chatClient.prompt()
                // 本次请求专用的系统提示词（覆盖默认设定）
                .system("你是一名擅长打比方的科普老师。")
                // 用户消息使用模板：{topic} / {audience} 会被下面的 param 替换
                .user(u -> u.text("请用适合{audience}理解的方式，解释什么是「{topic}」，不超过 80 字。")
                        .param("audience", audience)
                        .param("topic", topic))
                .call()
                .content();

        System.out.println("主题：" + topic + "（讲给：" + audience + "）");
        System.out.println("答：" + answer);
    }
}

package com.example.springai.agent.demo;

import com.example.springai.agent.tools.DateTimeTools;
import com.example.springai.agent.tools.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 示例 1：工具调用（Tool / Function Calling）。
 *
 * <p>把若干带 {@code @Tool} 的对象通过 tools(...) 交给模型；当问题需要实时信息时，
 * 模型会自动“请求调用”相应工具，Spring AI 执行后把结果回传给模型，模型再给出最终回答。
 * 整个“是否调用、调用哪个、怎么传参”都由模型自主决定。
 */
@Component
public class ToolCallingDemo implements Demo {

    private final ChatClient chatClient;

    public ToolCallingDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "工具调用（让模型调用 Java 方法查时间/天气）";
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public void run() {
        String question = "现在几点了？另外北京今天天气怎么样？";
        System.out.println("问：" + question);

        String answer = chatClient.prompt()
                .user(question)
                // 把工具对象交给模型；模型会按需调用其中的 @Tool 方法
                .tools(new DateTimeTools(), new WeatherTools())
                .call()
                .content();

        System.out.println("答：" + answer);
    }
}

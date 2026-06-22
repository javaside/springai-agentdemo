package com.example.springai.core.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 示例 4：结构化输出（让模型直接返回 Java 对象）。
 *
 * <p>不用自己解析 JSON：调用 entity(...) 时，Spring AI 会自动在提示词里加入“请按这个结构返回”的说明，
 * 并把模型返回的 JSON 反序列化成你的 Java 类型。
 */
@Component
public class StructuredOutputDemo implements Demo {

    private final ChatClient chatClient;

    public StructuredOutputDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** 用 record 定义想要的返回结构。 */
    public record Book(String title, String author, int year) {
    }

    @Override
    public String title() {
        return "结构化输出（返回 Java 对象 / List）";
    }

    @Override
    public int order() {
        return 4;
    }

    @Override
    public void run() {
        List<Book> books = chatClient.prompt()
                .user("推荐 3 本经典的计算机科学书籍。")
                .call()
                // 把回答直接转成 List<Book>，无需手写 JSON 解析
                .entity(new org.springframework.core.ParameterizedTypeReference<List<Book>>() {
                });

        System.out.println("模型返回的结构化数据：");
        for (Book b : books) {
            System.out.printf("  - 《%s》 %s（%d）%n", b.title(), b.author(), b.year());
        }
    }
}

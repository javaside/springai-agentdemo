package com.example.springai.core.demo;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 示例 3：流式输出（打字机效果）。
 *
 * <p>用 stream() 代替 call()，返回的是一个 Flux（响应式数据流），模型每生成一小段就推送一段。
 * 这里用 toStream() 把响应式流转成普通 Java Stream，逐段打印，模拟“边想边说”的效果。
 */
public class StreamDemo implements Demo {

    private final ChatClient chatClient;

    public StreamDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "流式输出（Streaming / 打字机效果）";
    }

    @Override
    public int order() {
        return 3;
    }

    @Override
    public void run() {
        String question = "写一首关于编程的四行小诗。";
        System.out.println("问：" + question);
        System.out.print("答：");

        chatClient.prompt()
                .user(question)
                .stream()              // 流式调用，返回 Flux<String>
                .content()
                .toStream()            // Flux -> java.util.stream.Stream（仅为方便在控制台同步打印）
                .forEach(System.out::print);

        System.out.println();
    }
}

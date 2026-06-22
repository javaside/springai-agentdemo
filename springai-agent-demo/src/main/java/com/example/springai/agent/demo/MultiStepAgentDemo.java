package com.example.springai.agent.demo;

import com.example.springai.agent.tools.DateTimeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 示例 3：多步 Agent（自动串联多个工具）。
 *
 * <p>这是“智能体”最直观的体现：给一个需要多步推理 + 多次工具调用才能完成的任务，
 * 模型会自己规划步骤、连续调用工具，最后汇总答案。
 *
 * <p>本例的问题“距离 2026 年国庆还有几天”需要两步：
 * <ol>
 *   <li>调用 getCurrentDateTime 获取“今天是几号”</li>
 *   <li>调用 daysBetween 计算今天到 2026-10-01 的天数</li>
 * </ol>
 * 模型会自动完成这条链路——我们只管提问，不用手写调用顺序。
 */
@Component
public class MultiStepAgentDemo implements Demo {

    private final ChatClient chatClient;

    public MultiStepAgentDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "多步 Agent（自动串联多个工具完成任务）";
    }

    @Override
    public int order() {
        return 3;
    }

    @Override
    public void run() {
        String task = "今天距离 2026 年 10 月 1 日国庆节还有多少天？请说明今天的日期。";
        System.out.println("任务：" + task);

        String answer = chatClient.prompt()
                .user(task)
                .tools(new DateTimeTools())   // 提供时间相关工具，模型会按需多次调用
                .call()
                .content();

        System.out.println("结果：" + answer);
    }
}

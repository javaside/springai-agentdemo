package com.example.springai.agent.demo;

import com.example.springai.agent.tools.DateTimeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 示例 4：ToolCallingAdvisor + MessageChatMemoryAdvisor，以及「两者顺序」的意义。
 *
 * <p>背景知识：
 * <ul>
 *   <li>默认情况下，工具调用循环是在 ChatModel【内部】完成的，其它 advisor 看不到中间过程。</li>
 *   <li>{@link ToolCallingAdvisor} 把这个“调用工具→拿结果→再问模型”的循环搬到了【advisor 链】里，
 *       于是链上其它 advisor（比如记忆）就有机会介入每一轮工具调用。</li>
 * </ul>
 *
 * <p>advisor 的执行顺序由 {@code getOrder()} 决定：<b>order 值越小越靠“外层”</b>
 * （请求时先执行、响应时后处理）。所以 ToolCallingAdvisor 与 MessageChatMemoryAdvisor 谁的 order 小，
 * 谁就在外层、把对方包在里面。这一“里外关系”决定了【工具调用的中间消息要不要写进记忆】：
 *
 * <ul>
 *   <li><b>记忆在外层（order 比工具小，即默认）</b>：记忆包住整个工具循环，只看到“用户提问”和
 *       “最终回答”，工具调用的中间消息<b>不会</b>进入记忆 → 历史干净。</li>
 *   <li><b>记忆在内层（order 比工具大）</b>：记忆处在工具循环之内，每一轮工具调用都经过它，
 *       于是中间的“请求调用工具 / 工具返回结果”等消息<b>也会</b>被记进记忆 → 保留完整工具轨迹。</li>
 * </ul>
 *
 * <p>本示例对同一个需要调用工具的问题，分别用两种顺序各跑一次，然后打印各自记忆里实际存了哪些消息，
 * 让“顺序的意义”一目了然。
 */
public class ToolMemoryAdvisorDemo implements Demo {

    private final ChatClient chatClient;

    public ToolMemoryAdvisorDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "ToolCallingAdvisor + 记忆：advisor 顺序的意义";
    }

    @Override
    public int order() {
        return 4;
    }

    @Override
    public void run() {
        String question = "今天是几号？另外，距离 2026 年 10 月 1 日还有多少天？";

        // 记忆默认 order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER；ToolCallingAdvisor 默认更大（更靠内层）。
        int memoryOrder = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

        // ---------- 场景 A：记忆在外层（默认顺序）----------
        // 工具用默认 order（比记忆大→更内层）。记忆包住整个工具循环，工具中间消息不入记忆。
        ChatMemory memoryA = MessageWindowChatMemory.builder().build();
        Advisor toolAdvisorA = ToolCallingAdvisor.builder().build(); // 默认 order，conversationHistoryEnabled=true
        Advisor memAdvisorA = MessageChatMemoryAdvisor.builder(memoryA).order(memoryOrder).build();
        runScenario("A · 记忆在【外层】（默认）", question, memoryA, toolAdvisorA, memAdvisorA);

        // ---------- 场景 B：记忆在内层 ----------
        // 把工具 order 调到比记忆更小（更外层）→ 记忆落到工具循环【内部】，每轮工具调用都经过记忆，
        // 中间消息会被记进记忆。此时关闭工具 advisor 自带的历史，交由内层记忆来承载对话历史。
        ChatMemory memoryB = MessageWindowChatMemory.builder().build();
        Advisor toolAdvisorB = ToolCallingAdvisor.builder()
                .advisorOrder(memoryOrder - 100)          // 比记忆更靠外层
                .disableInternalConversationHistory()      // 历史改由内层记忆承载
                .build();
        Advisor memAdvisorB = MessageChatMemoryAdvisor.builder(memoryB).order(memoryOrder).build();
        runScenario("B · 记忆在【内层】", question, memoryB, toolAdvisorB, memAdvisorB);

        System.out.println("""
                ────────────────────────────────────────────────
                对比结论：
                  - 场景 A（记忆在外层，默认）：记忆里只有「用户提问 + 最终回答」，工具调用细节被挡在外面 → 历史精简。
                  - 场景 B（记忆在内层）：记忆里还多出「模型请求调用工具 / 工具返回结果」等中间消息 → 保留完整工具轨迹。
                选择依据：想要干净的对话历史就让记忆在外层（默认）；想把工具调用过程也持久化（审计/调试/可复盘）就让记忆在内层。
                """);
    }

    private void runScenario(String label, String question, ChatMemory memory, Advisor toolAdvisor, Advisor memoryAdvisor) {
        String conversationId = "conv-" + label.charAt(0);
        System.out.println("\n========== 场景 " + label + " ==========");
        System.out.println("问：" + question);

        String answer = chatClient.prompt()
                .user(question)
                .tools(new DateTimeTools())
                .advisors(toolAdvisor, memoryAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        System.out.println("答：" + answer);

        List<Message> stored = memory.get(conversationId);
        System.out.println("→ 这次对话结束后，记忆里实际存了 " + stored.size() + " 条消息：");
        for (Message m : stored) {
            String text = m.getText() == null ? "" : m.getText().replaceAll("\\s+", " ").trim();
            if (text.length() > 60) {
                text = text.substring(0, 60) + "…";
            }
            System.out.printf("    [%s] %s%n", m.getMessageType(), text.isEmpty() ? "(无文本，通常是工具调用/工具结果消息)" : text);
        }
    }
}

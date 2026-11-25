package io.github.javaside.springai.agent;

import io.github.javaside.springai.agent.demo.Demo;
import io.github.javaside.springai.agent.demo.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentDemoApplicationTest {

    static ChatClient anyClient() {
        return AgentDemoApplication.buildChatClient(new ScriptedChatModel(p -> ScriptedChatModel.text("好")));
    }

    @Test
    void demosAreNumberedOneToTenInMenuOrder() {
        List<Demo> demos = AgentDemoApplication.demos(anyClient());

        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), demos.stream().map(Demo::order).toList());
        assertEquals(List.of("ToolCallingDemo", "ChatMemoryDemo", "SessionMemoryDemo", "SessionCompactionDemo",
                "SessionRecallDemo", "MultiStepAgentDemo", "ToolMemoryAdvisorDemo", "ToolSearchDemo", "SkillToolDemo",
                "TodoWriteToolDemo"), demos.stream().map(d -> d.getClass().getSimpleName()).toList());
    }

    @Test
    void chatMemoryDemosAreKeptButMarkedDeprecated() {
        List<Demo> demos = AgentDemoApplication.demos(anyClient());

        assertEquals("对话记忆（ChatMemory，2.1 起已弃用）", demos.get(1).title());
        assertEquals("Advisor 顺序（ChatMemory，2.1 起已弃用）", demos.get(6).title());
    }

    @Test
    void sharedChatClientNeedsNoSessionId() {
        // 会话 advisors 只能按请求传入：共享 ChatClient 不带会话 id 也必须能正常对话
        assertEquals("好", anyClient().prompt().user("你好").call().content());
    }
}

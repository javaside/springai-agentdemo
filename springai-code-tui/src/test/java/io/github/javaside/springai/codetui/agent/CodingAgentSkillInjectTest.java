package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.javaside.springai.codetui.agent.seam.StubListener;

/**
 * CodingAgent 手动 /skill 注入：发送前真正调用（被装饰的）Skill 工具，
 * 触发工具事件（UI 可见）并把返回正文注入到 user 消息前。
 *
 * <p>ChatClient 用 null——注入发生在 chatClient.prompt() 之前；prompt() 触发 NPE 会被 submit 的
 * catch(RuntimeException) 兜底为 onError（状态复位），不影响「工具已被调用」这一先决事实。
 *
 * <p><b>注入的工具是被 {@link ToolEventCallback} 装饰后的实例</b>（与生产装配一致，见 AgentTools）：
 * 工具事件由该装饰器上报，CodingAgent 只负责「发送前真正调用它」。故测试把裸 fake 包一层装饰器再注入。
 */
class CodingAgentSkillInjectTest {

    private static final class RecordingListener extends StubListener {
        final List<String> toolStarts = new ArrayList<>();
        final List<String> toolInputs = new ArrayList<>();
        final AtomicReference<Boolean> lastOk = new AtomicReference<>();
        String userMessage;
        @Override public void onToolStarted(long turnId, String toolName, String input) {
            toolStarts.add(toolName); toolInputs.add(input);
        }
        @Override public void onToolFinished(long turnId, String toolName, String output, boolean ok) {
            lastOk.set(ok);
        }
        @Override public void onUserMessage(long turnId, String text) { this.userMessage = text; }
    }

    private static ToolCallback fakeSkillTool(AtomicReference<String> capturedInput) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("Skill").description("d").inputSchema("{}").build();
            }
            @Override public String call(String toolInput) { return call(toolInput, null); }
            @Override public String call(String toolInput, ToolContext toolContext) {
                capturedInput.set(toolInput);
                return "Base directory for this skill: /tmp/demo\n\n技能正文内容ABC";
            }
        };
    }

    @Test
    void submitWithSkill_callsToolAndReportsEvents() {
        RecordingListener listener = new RecordingListener();
        AtomicReference<String> toolInput = new AtomicReference<>();
        // 与生产一致：注入的是被 ToolEventCallback 装饰后的 Skill 工具，事件由装饰器上报。
        ToolCallback decorated = new ToolEventCallback(fakeSkillTool(toolInput), listener);
        CodingAgent agent = new CodingAgent(null, listener, "s", new AtomicLong(),
                null, null, null, List.of(), decorated);

        agent.submit("帮我写提交信息", "git-commit-message");

        assertEquals(List.of("Skill"), listener.toolStarts, "应触发一次名为 Skill 的工具事件");
        assertTrue(listener.toolInputs.get(0).contains("git-commit-message"), "工具入参应含技能名");
        assertEquals("{\"command\":\"git-commit-message\"}", toolInput.get(), "入参应为 SkillsInput 的 command JSON");
        assertTrue(listener.lastOk.get(), "工具应报告成功完成");
        assertEquals("帮我写提交信息", listener.userMessage,
                "onUserMessage 应记录用户原文（注入只影响发给模型的文本，不改 UI 展示的用户消息）");
    }

    @Test
    void submitWithNullSkill_doesNotCallTool() {
        RecordingListener listener = new RecordingListener();
        AtomicReference<String> toolInput = new AtomicReference<>();
        ToolCallback decorated = new ToolEventCallback(fakeSkillTool(toolInput), listener);
        CodingAgent agent = new CodingAgent(null, listener, "s", new AtomicLong(),
                null, null, null, List.of(), decorated);

        agent.submit("普通消息", null);

        assertTrue(listener.toolStarts.isEmpty(), "skillName 为 null 时不应调用工具");
        assertEquals("普通消息", listener.userMessage);
    }

    @Test
    void submitWithSkill_butNoSkillTool_doesNotCrash() {
        RecordingListener listener = new RecordingListener();
        CodingAgent agent = new CodingAgent(null, listener, "s", new AtomicLong(),
                null, null, null, List.of(), null);

        agent.submit("消息", "some-skill");

        assertTrue(listener.toolStarts.isEmpty(), "skillTool 为 null 时不调用、不崩");
        assertFalse(listener.userMessage == null, "用户消息仍应被记录");
    }
}

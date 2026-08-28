package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.LlmProvider;
import io.github.javaside.springai.codetui.agent.ModelOption;
import io.github.javaside.springai.codetui.agent.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.StubListener;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SubagentRunner.run 成功传 ok=true、异常传 ok=false 并抛出。用假 ChatModel/Provider，不联网。 */
class SubagentRunnerOkTest {

    /** 记录 4 参 onSubagentFinished 的 ok。 */
    private static final class RecordingListener extends StubListener {
        Boolean ok;
        String finalText;
        @Override public void onSubagentFinished(long turnId, String taskId, String finalText, boolean ok) {
            this.ok = ok;
            this.finalText = finalText;
        }
    }

    /** 假 ChatModel：success 流式返回固定文本；否则流以异常终止。子 agent 的 call 已桥接到 stream（RetryingChatModel）。 */
    private static ChatModel chatModel(boolean success) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("subagent path bridges to stream()");
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                if (!success) return Flux.error(new RuntimeException("boom"));
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
    }

    /** 假 LlmProvider：可用、返回上面的假 ChatModel、options 用空 ChatOptions（支持 mutate）。 */
    private static LlmProvider provider(ChatModel model) {
        return new LlmProvider() {
            @Override public String id() { return "fake"; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return model; }
            @Override public ChatOptions options(String modelId) { return ChatOptions.builder().build(); }
            @Override public List<ModelOption> models() { return List.of(new ModelOption("fake-m", "Fake", "d")); }
            @Override public String defaultModel() { return "fake-m"; }
        };
    }

    private static SubagentSpec spec() {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
    }

    @Test
    void success_emitsOkTrue_andReturnsContent() {
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel(true))));
        RecordingListener lis = new RecordingListener();
        SubagentRunner runner = new SubagentRunner(reg, List.of(), lis, "");
        String out = runner.run(spec(), "hi", "desc", 1L);
        assertEquals("done", out);
        assertEquals(Boolean.TRUE, lis.ok);
    }

    @Test
    void failure_emitsOkFalse_andRethrows() {
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel(false))));
        RecordingListener lis = new RecordingListener();
        SubagentRunner runner = new SubagentRunner(reg, List.of(), lis, "");
        assertThrows(RuntimeException.class, () -> runner.run(spec(), "hi", "desc", 1L));
        assertEquals(Boolean.FALSE, lis.ok);
    }

    /** 失败文本应摊平 cause 链——SDK 顶层 message 笼统（如 "Error reading response"），根因在 cause 里。 */
    @Test
    void failure_textCarriesCauseChain() {
        ChatModel throwing = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                // 带可重试特征（end-of-input）：穷尽重试后原样抛出，验证失败文本仍摊平 cause 链
                return Flux.error(new RuntimeException("Error reading response",
                        new java.io.IOException("No content to map due to end-of-input")));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(throwing)));
        RecordingListener lis = new RecordingListener();
        SubagentRunner runner = new SubagentRunner(reg, List.of(), lis, "");
        assertThrows(RuntimeException.class, () -> runner.run(spec(), "hi", "desc", 1L));
        assertTrue(lis.finalText.contains("Error reading response"), "应含顶层 message");
        assertTrue(lis.finalText.contains("No content to map due to end-of-input"), "应含 cause 根因");
    }

    /**
     * 端到端 wiring：run() 应把 spec 系统提示 + 项目指令一并写进发给模型的 Prompt 的系统消息。
     * 用捕获式假 ChatModel 抓下真实 Prompt，断言系统消息文本两者兼含——覆盖 effectiveSystemPrompt 的纯函数单测够不着的「真的接进了 .system(...)」这一段。
     */
    @Test
    void run_carriesProjectInstructionsIntoPromptSystemMessage() {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        ChatModel capturing = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                captured.set(prompt);
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(capturing)));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "PROJ_WIRING_MARKER");

        runner.run(spec(), "hi", "desc", 1L);

        String systemText = captured.get().getSystemMessage().getText();
        assertTrue(systemText.contains("sys"), "系统消息应含 spec 自身系统提示（spec()=\"sys\"）");
        assertTrue(systemText.contains("PROJ_WIRING_MARKER"), "系统消息应含项目指令（证明真的接进了 .system(...)）");
    }
}

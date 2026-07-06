package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    /** 假 ChatModel：success 返回固定文本；否则 call 抛异常。ChatModel 抽象方法只有 call/stream。 */
    private static ChatModel chatModel(boolean success) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                if (!success) throw new RuntimeException("boom");
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("blocking path only");
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
}

package io.github.javaside.springai.codetui;

import io.github.javaside.springai.codetui.agent.llm.AnthropicProvider;
import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelPreference;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动时把「上次用的模型」恢复回来。
 *
 * <p><b>为什么这段逻辑是从 main 里抽出来的</b>：{@code main} 会起 TUI，测不了。
 * 不抽出来，这个装配点就是零覆盖——而装配点恰恰是最容易接错、又最不容易被发现的地方。
 */
class CodeTuiApplicationModelRestoreTest {

    /** deepseek（默认 deepseek-v4-pro）+ anthropic，两家都可用。 */
    private static ProviderRegistry registry() {
        return new ProviderRegistry(List.of(new DeepSeekProvider("k"), new AnthropicProvider("k")));
    }

    @Test
    @DisplayName("记住的模型可用：激活它，且一声不吭")
    void restoresRememberedModel(@TempDir Path root) {
        assertTrue(ModelPreference.write(root, "anthropic", "claude-opus-5"), "前提：偏好写得进去");
        ProviderRegistry reg = registry();
        ConversationState state = new ConversationState();

        CodeTuiApplication.restoreLastModel(reg, root, state);

        assertEquals("claude-opus-5", reg.activeModelId());
        assertEquals("anthropic", reg.active().id(), "跨家恢复：provider 也要跟着切");
        assertTrue(state.drainPending().isEmpty(), "恢复成功是常态，不该拿一行提示去打扰用户");
    }

    /**
     * 失效兜底。杀掉「删掉 activeModelId 比对」这个变异——没有那道比对，
     * 回退会静默发生，用户只会觉得「记忆功能坏了」。
     */
    @Test
    @DisplayName("记住的模型用不了了：回退默认，并说清楚回退到了哪")
    void unavailableModelFallsBackAndSaysSo(@TempDir Path root) {
        assertTrue(ModelPreference.write(root, "openai", "gpt-5.5"), "前提：偏好写得进去");
        ProviderRegistry reg = registry();      // 没有 openai ⇒ gpt-5.5 选不中
        ConversationState state = new ConversationState();

        CodeTuiApplication.restoreLastModel(reg, root, state);

        assertEquals("deepseek-v4-pro", reg.activeModelId(), "选不中就该保持默认");
        List<ConversationState.OutputLine> lines = state.drainPending();
        assertEquals(1, lines.size(), "该有且只有一行提示:" + lines);
        String t = lines.get(0).text();
        assertTrue(t.contains("gpt-5.5"), "要说清是哪个模型没了:" + t);
        assertTrue(t.contains("deepseek-v4-pro"), "也要说清现在用的是哪个:" + t);
    }

    @Test
    @DisplayName("没有记忆：什么都不做，走现在的行为")
    void noMemoryChangesNothing(@TempDir Path root) {
        ProviderRegistry reg = registry();
        ConversationState state = new ConversationState();

        CodeTuiApplication.restoreLastModel(reg, root, state);

        assertEquals("deepseek-v4-pro", reg.activeModelId());
        assertTrue(state.drainPending().isEmpty(), "首次运行不该冒出任何提示");
    }
}

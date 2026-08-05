package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRegistryTest {

    @Test
    void aggregatesOnlyAvailableProviders() {
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"),
                new AnthropicProvider("k"),
                new OpenAiProvider("")));        // 不可用
        assertEquals("deepseek", reg.active().id());
        assertEquals("deepseek-v4-pro", reg.activeModelId());
        List<String> ids = reg.allModels().stream().map(ModelOption::id).toList();
        assertTrue(ids.contains("deepseek-v4-flash"));
        assertTrue(ids.contains("claude-sonnet-5"));
        assertFalse(ids.contains("gpt-5.5"));   // openai 不可用，不列出
    }

    @Test
    void selectCrossProviderModelSwitchesActive() {
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"), new AnthropicProvider("k")));
        reg.select("claude-opus-5");
        assertEquals("anthropic", reg.active().id());
        assertEquals("claude-opus-5", reg.activeModelId());
        assertEquals("claude-opus-5", reg.activeChatOptions().getModel());
    }

    /**
     * <b>这条现在是承重的</b>：{@code CodeTuiApplication.restoreLastModel} 拿
     * 「select 完 activeModelId 变没变」当「记住的模型还可用吗」的判据。
     * 这里一旦改成「未知模型抛异常」或「回退到默认」，那边的失效检测就会跟着错，
     * 而它自己的测试未必看得出来。
     */
    @Test
    void selectUnknownModelIsIgnored() {
        ProviderRegistry reg = new ProviderRegistry(List.of(new DeepSeekProvider("k")));
        reg.select("no-such-model");
        assertEquals("deepseek-v4-pro", reg.activeModelId());
    }
}

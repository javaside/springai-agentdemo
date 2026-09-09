package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRegistryThinkingTest {

    @Test
    void activeSelectionSnapshotsProviderModelConfigAndOptions() {
        ThinkingConfigStore store = ThinkingConfigStore.inMemory();
        store.put("openai", "gpt-6-astra", ThinkingConfig.enabledEffort("high"));
        ProviderRegistry registry = new ProviderRegistry(List.of(new OpenAiProvider("k")), store);
        ProviderRegistry.RequestSelection selection = registry.activeRequestSelection();
        assertEquals("openai", selection.provider().id());
        assertEquals("gpt-6-astra", selection.modelId());
        assertEquals("high", selection.config().effort());
        assertEquals("high", ((OpenAiChatOptions) selection.options()).getReasoningEffort());
    }

    @Test
    void settingsForInactiveModelDoNotSwitchSelection() {
        ProviderRegistry registry = new ProviderRegistry(List.of(new OpenAiProvider("k")));
        assertEquals("gpt-6-astra", registry.activeModelId());
        assertEquals("gpt-5.6-sol", registry.thinkingSettings("openai", "gpt-5.6-sol").modelId());
        assertEquals("gpt-6-astra", registry.activeModelId());
    }

    @Test
    void updateValidatesBeforeMutating() {
        ProviderRegistry registry = new ProviderRegistry(List.of(new ZhipuProvider("k")));
        assertThrows(IllegalArgumentException.class,
                () -> registry.updateThinking("zhipu", "glm-5.1", ThinkingConfig.enabledEffort("max")));
        assertEquals(ThinkingConfig.defaults(), registry.thinkingSettings("zhipu", "glm-5.1").config());
    }

    @Test
    void defaultRemovesSetting() {
        ProviderRegistry registry = new ProviderRegistry(List.of(new OpenAiProvider("k")));
        assertTrue(registry.updateThinking("openai", "gpt-5.6-sol", ThinkingConfig.enabledEffort("high")));
        assertTrue(registry.updateThinking("openai", "gpt-5.6-sol", ThinkingConfig.defaults()));
        assertEquals(ThinkingConfig.defaults(), registry.thinkingSettings("openai", "gpt-5.6-sol").config());
    }

    @Test
    void saveFailureKeepsMemory(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".codetui"), "not a directory");
        ProviderRegistry registry = new ProviderRegistry(List.of(new OpenAiProvider("k")), ThinkingConfigStore.load(root));
        ThinkingConfig config = ThinkingConfig.enabledEffort("high");
        assertFalse(registry.updateThinking("openai", "gpt-5.6-sol", config));
        assertEquals(config, registry.thinkingSettings("openai", "gpt-5.6-sol").config());
    }

    @Test
    void requestSelectionForExplicitModelUsesItsSetting() {
        ThinkingConfigStore store = ThinkingConfigStore.inMemory();
        store.put("openai", "gpt-5.6-terra", ThinkingConfig.enabledEffort("low"));
        ProviderRegistry registry = new ProviderRegistry(List.of(new OpenAiProvider("k")), store);
        assertEquals("low", registry.requestSelection("gpt-5.6-terra").config().effort());
        assertEquals("gpt-6-astra", registry.activeModelId());
    }

    /** 同名模型的思考设置必须按 provider 隔离：配 A 家不该动 B 家。 */
    @Test
    void sameModelIdAcrossProvidersKeepsThinkingSeparate() {
        ThinkingConfigStore store = ThinkingConfigStore.inMemory();
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"), new OpencodeGoProvider("k")), store);

        assertTrue(reg.updateThinking("opencode-go", "deepseek-v4-pro",
                ThinkingConfig.enabledEffort("high")));
        assertEquals(ThinkingConfig.defaults(),
                reg.thinkingSettings("deepseek", "deepseek-v4-pro").config());
        assertEquals("high",
                reg.thinkingSettings("opencode-go", "deepseek-v4-pro").config().effort());
    }

    /** 精确路由必须认 providerId，不能被「谁排前面」决定——即便两家都持有同名 modelId。 */
    @Test
    void requestSelectionTwoArgRoutesToExactProvider() {
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"), new OpencodeGoProvider("k")));
        // deepseek 排第一（激活 provider），但显式指定 opencode-go：必须精确路由到 opencode-go，不能落到激活的 deepseek
        ProviderRegistry.RequestSelection sel = reg.requestSelection("opencode-go", "deepseek-v4-pro");
        assertEquals("opencode-go", sel.provider().id());
        assertEquals("deepseek-v4-pro", sel.modelId());
    }

    @Test
    void requestSelectionTwoArgUnknownThrows() {
        ProviderRegistry reg = new ProviderRegistry(List.of(new OpenAiProvider("k")));
        assertThrows(IllegalArgumentException.class,
                () -> reg.requestSelection("deepseek", "deepseek-v4-pro"));
    }

    @Test
    void requestSelectionOneArgUnknownThrows() {
        ProviderRegistry reg = new ProviderRegistry(List.of(new OpenAiProvider("k")));
        assertThrows(IllegalArgumentException.class,
                () -> reg.requestSelection("totally-bogus-model"));
    }

    /** 回归：今天两个分支等价，会把「不属于激活 provider 的 modelId」硬塞给激活 provider。 */
    @Test
    void requestSelectionOneArgFindsOwnerAcrossProviders() {
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"), new OpenAiProvider("k")));
        // 激活 provider 是列表首个 available 的（DeepSeek），但这个 modelId 只属于 OpenAI
        ProviderRegistry.RequestSelection sel = reg.requestSelection("gpt-5.6-sol");
        assertEquals("openai", sel.provider().id());
    }
}

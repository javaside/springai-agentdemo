package io.github.javaside.springai.codetui.agent;

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
        store.put("openai", "gpt-5.6-sol", ThinkingConfig.enabledEffort("high"));
        ProviderRegistry registry = new ProviderRegistry(List.of(new OpenAiProvider("k")), store);
        ProviderRegistry.RequestSelection selection = registry.activeRequestSelection();
        assertEquals("openai", selection.provider().id());
        assertEquals("gpt-5.6-sol", selection.modelId());
        assertEquals("high", selection.config().effort());
        assertEquals("high", ((OpenAiChatOptions) selection.options()).getReasoningEffort());
    }

    @Test
    void settingsForInactiveModelDoNotSwitchSelection() {
        ProviderRegistry registry = new ProviderRegistry(List.of(new OpenAiProvider("k")));
        assertEquals("gpt-5.6-sol", registry.activeModelId());
        assertEquals("gpt-5.6-terra", registry.thinkingSettings("gpt-5.6-terra").modelId());
        assertEquals("gpt-5.6-sol", registry.activeModelId());
    }

    @Test
    void updateValidatesBeforeMutating() {
        ProviderRegistry registry = new ProviderRegistry(List.of(new ZhipuProvider("k")));
        assertThrows(IllegalArgumentException.class,
                () -> registry.updateThinking("glm-5.1", ThinkingConfig.enabledEffort("max")));
        assertEquals(ThinkingConfig.defaults(), registry.thinkingSettings("glm-5.1").config());
    }

    @Test
    void defaultRemovesSetting() {
        ProviderRegistry registry = new ProviderRegistry(List.of(new OpenAiProvider("k")));
        assertTrue(registry.updateThinking("gpt-5.6-sol", ThinkingConfig.enabledEffort("high")));
        assertTrue(registry.updateThinking("gpt-5.6-sol", ThinkingConfig.defaults()));
        assertEquals(ThinkingConfig.defaults(), registry.thinkingSettings("gpt-5.6-sol").config());
    }

    @Test
    void saveFailureKeepsMemory(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".codetui"), "not a directory");
        ProviderRegistry registry = new ProviderRegistry(List.of(new OpenAiProvider("k")), ThinkingConfigStore.load(root));
        ThinkingConfig config = ThinkingConfig.enabledEffort("high");
        assertFalse(registry.updateThinking("gpt-5.6-sol", config));
        assertEquals(config, registry.thinkingSettings("gpt-5.6-sol").config());
    }

    @Test
    void requestSelectionForExplicitModelUsesItsSetting() {
        ThinkingConfigStore store = ThinkingConfigStore.inMemory();
        store.put("openai", "gpt-5.6-terra", ThinkingConfig.enabledEffort("low"));
        ProviderRegistry registry = new ProviderRegistry(List.of(new OpenAiProvider("k")), store);
        assertEquals("low", registry.requestSelection("gpt-5.6-terra").config().effort());
        assertEquals("gpt-5.6-sol", registry.activeModelId());
    }
}

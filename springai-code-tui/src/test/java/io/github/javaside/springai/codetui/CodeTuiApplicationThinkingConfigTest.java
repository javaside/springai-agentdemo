package io.github.javaside.springai.codetui;

import io.github.javaside.springai.codetui.agent.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeTuiApplicationThinkingConfigTest {

    @Test
    void registryLoadsPersistedThinkingSettings(@TempDir Path root) throws Exception {
        Path file = ThinkingConfigStore.fileFor(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"version":1,"providers":{"openai":{"gpt-5.6-sol":{"mode":"ENABLED","effort":"high"}}}}
                """);
        ProviderRegistry registry = CodeTuiApplication.createProviderRegistry(root,
                Map.of("OPENAI_API_KEY", "k"));
        assertEquals("high", registry.thinkingSettings("gpt-5.6-sol").config().effort());
    }
}

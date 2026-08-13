package io.github.javaside.springai.codetui.agent.thinking;

import java.util.Objects;

public record ModelThinkingSettings(
        String providerId,
        String modelId,
        String label,
        ThinkingConfig config,
        ThinkingCapabilities capabilities) {

    public ModelThinkingSettings {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(capabilities, "capabilities");
    }

    public String summary() {
        return capabilities.summary(config);
    }
}

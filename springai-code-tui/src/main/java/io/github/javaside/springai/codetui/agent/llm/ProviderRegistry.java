package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.thinking.ModelThinkingSettings;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayList;
import java.util.List;

/** Holds all providers and the active provider/model selection. */
public final class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final List<LlmProvider> providers;
    private final ThinkingConfigStore thinkingStore;
    private volatile LlmProvider active;
    private volatile String activeModelId;

    public ProviderRegistry(List<? extends LlmProvider> providers) {
        this(providers, ThinkingConfigStore.inMemory());
    }

    public ProviderRegistry(List<? extends LlmProvider> providers, ThinkingConfigStore thinkingStore) {
        this.providers = List.copyOf(providers);
        this.thinkingStore = thinkingStore;
        LlmProvider first = null;
        for (LlmProvider provider : this.providers) {
            if (provider.available()) {
                first = provider;
                break;
            }
        }
        if (first == null) {
            throw new IllegalStateException("没有任何可用的 LlmProvider（至少需配置一家的 API key）");
        }
        this.active = first;
        this.activeModelId = first.defaultModel();
        validatePersistedSettings();
    }

    public LlmProvider active() { return active; }
    public String activeModelId() { return activeModelId; }

    /** Compatibility path for auxiliary calls: deliberately DEFAULT. */
    public ChatOptions activeChatOptions() { return active.options(activeModelId); }

    public List<LlmProvider> allProviders() { return providers; }

    public List<ProviderModel> allModels() {
        List<ProviderModel> all = new ArrayList<>();
        for (LlmProvider provider : providers) {
            if (!provider.available()) {
                continue;
            }
            for (ModelOption model : provider.models()) {
                all.add(new ProviderModel(provider.id(), model.id(), model.label(), model.desc()));
            }
        }
        return all;
    }

    /**
     * 按「第一个拥有该 id 的可用 provider」切换（列表序靠前）。
     *
     * <p>这是<b>只给旧格式偏好回退</b>与历史调用点保留的宽松入口：当模型身份只有裸 modelId、
     * 无法区分同名不同家时，只能命中靠前的那家。新代码应优先用
     * {@link #select(String, String)} 精确指定 provider，避免同名模型串号。
     */
    public synchronized void select(String modelId) {
        ModelOwner owner = ownerOf(modelId);
        if (owner != null) {
            active = owner.provider();
            activeModelId = owner.model().id();
        }
    }

    /** 精确切换：指定 provider 与模型。对未知/不可用的 (providerId, modelId) 静默忽略（同旧语义）。 */
    public synchronized void select(String providerId, String modelId) {
        ModelOwner owner = ownerOf(providerId, modelId);
        if (owner != null) {
            active = owner.provider();
            activeModelId = owner.model().id();
        }
    }

    public synchronized RequestSelection activeRequestSelection() {
        return selection(active, activeModelId);
    }

    /** Explicit subagent model remains scoped to the active provider in v1. */
    public synchronized RequestSelection requestSelection(String modelId) {
        LlmProvider provider = active;
        boolean belongsToActive = provider.models().stream().anyMatch(model -> model.id().equals(modelId));
        if (!belongsToActive) {
            // Keep the established v1 behavior: use the active provider even for a custom override.
            return selection(provider, modelId);
        }
        return selection(provider, modelId);
    }

    public synchronized ModelThinkingSettings thinkingSettings(String providerId, String modelId) {
        ModelOwner owner = ownerOf(providerId, modelId);
        if (owner == null) {
            throw new IllegalArgumentException("未知或不可用模型: " + providerId + "/" + modelId);
        }
        ThinkingConfig config = thinkingStore.get(providerId, modelId);
        return new ModelThinkingSettings(providerId, modelId, owner.model().label(), config,
                owner.provider().thinkingCapabilities(modelId));
    }

    public synchronized boolean updateThinking(String providerId, String modelId, ThinkingConfig config) {
        ModelOwner owner = ownerOf(providerId, modelId);
        if (owner == null) {
            throw new IllegalArgumentException("未知或不可用模型: " + providerId + "/" + modelId);
        }
        owner.provider().thinkingCapabilities(modelId).validate(config);
        thinkingStore.put(providerId, modelId, config);
        return thinkingStore.save();
    }

    private RequestSelection selection(LlmProvider provider, String modelId) {
        ThinkingConfig config = effectiveConfig(provider, modelId);
        return new RequestSelection(provider, modelId, config, provider.options(modelId, config));
    }

    private ThinkingConfig effectiveConfig(LlmProvider provider, String modelId) {
        ThinkingConfig config = thinkingStore.get(provider.id(), modelId);
        try {
            provider.thinkingCapabilities(modelId).validate(config);
            return config;
        } catch (IllegalArgumentException e) {
            log.warn("思考配置 {}/{} 已不兼容（{}），本次按官方默认运行，原记录保留。",
                    provider.id(), modelId, e.getMessage());
            return ThinkingConfig.defaults();
        }
    }

    /** 只按 modelId 找：命中「第一个拥有该 id 的可用 provider」（列表序靠前）。旧格式回退用。 */
    private ModelOwner ownerOf(String modelId) {
        for (LlmProvider provider : providers) {
            if (!provider.available()) {
                continue;
            }
            for (ModelOption model : provider.models()) {
                if (model.id().equals(modelId)) {
                    return new ModelOwner(provider, model);
                }
            }
        }
        return null;
    }

    /** 精确找：provider + model 都匹配才命中。 */
    private ModelOwner ownerOf(String providerId, String modelId) {
        for (LlmProvider provider : providers) {
            if (!provider.available() || !provider.id().equals(providerId)) {
                continue;
            }
            for (ModelOption model : provider.models()) {
                if (model.id().equals(modelId)) {
                    return new ModelOwner(provider, model);
                }
            }
        }
        return null;
    }

    private void validatePersistedSettings() {
        thinkingStore.snapshot().forEach((providerId, models) -> {
            LlmProvider provider = providers.stream()
                    .filter(candidate -> candidate.available() && candidate.id().equals(providerId))
                    .findFirst().orElse(null);
            if (provider == null) {
                return;
            }
            models.forEach((modelId, config) -> {
                if (provider.models().stream().noneMatch(model -> model.id().equals(modelId))) {
                    return;
                }
                try {
                    provider.thinkingCapabilities(modelId).validate(config);
                } catch (IllegalArgumentException e) {
                    log.warn("思考配置 {}/{} 已不兼容（{}），请求时将按官方默认运行，原记录保留。",
                            providerId, modelId, e.getMessage());
                }
            });
        });
    }

    private record ModelOwner(LlmProvider provider, ModelOption model) { }

    public record RequestSelection(LlmProvider provider, String modelId,
                                   ThinkingConfig config, ChatOptions options) { }
}

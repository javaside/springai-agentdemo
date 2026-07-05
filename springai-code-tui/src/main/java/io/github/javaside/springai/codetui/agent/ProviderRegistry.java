package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * 持有全部 {@link LlmProvider}，记录「当前激活 provider + 激活模型」。
 *
 * <p>初始激活 = 第一个 {@link LlmProvider#available() 可用} 的 provider（构造入参顺序即偏好序，
 * 通常 deepseek 打头），激活模型 = 该 provider 的 {@link LlmProvider#defaultModel()}。
 * {@code /model} 经 {@link #allModels()} 跨家列出可用模型，选中经 {@link #select(String)} 同时切换 provider 与模型。
 */
public final class ProviderRegistry {

    private final List<LlmProvider> providers;   // 全部（含不可用），按偏好序
    private volatile LlmProvider active;
    private volatile String activeModelId;

    public ProviderRegistry(List<LlmProvider> providers) {
        this.providers = List.copyOf(providers);
        LlmProvider first = null;
        for (LlmProvider p : this.providers) {
            if (p.available()) { first = p; break; }
        }
        if (first == null) {
            throw new IllegalStateException("没有任何可用的 LlmProvider（至少需配置一家的 API key）");
        }
        this.active = first;
        this.activeModelId = first.defaultModel();
    }

    public LlmProvider active() { return active; }

    public String activeModelId() { return activeModelId; }

    /** 激活 provider 对该模型的每请求 options（供 CodingAgent.submit 覆盖模型）。 */
    public ChatOptions activeChatOptions() { return active.options(activeModelId); }

    /** 全部 provider（含不可用），按偏好序。装配期遍历建 ChatClient 用。 */
    public List<LlmProvider> allProviders() { return providers; }

    /** 全部可用 provider 的模型聚合（供 /model 展示）。不可用 provider 的模型不列。 */
    public List<ModelOption> allModels() {
        List<ModelOption> all = new ArrayList<>();
        for (LlmProvider p : providers) {
            if (p.available()) {
                all.addAll(p.models());
            }
        }
        return all;
    }

    /**
     * 选中一个模型：在可用 provider 中找拥有该 modelId 的那家，切换激活 provider 与激活模型。
     * modelId 三家全局唯一；找不到（未知/属于不可用家）则静默忽略、保持原激活。
     */
    public void select(String modelId) {
        for (LlmProvider p : providers) {
            if (!p.available()) {
                continue;
            }
            for (ModelOption m : p.models()) {
                if (m.id().equals(modelId)) {
                    this.active = p;
                    this.activeModelId = modelId;
                    return;
                }
            }
        }
        // 未知模型：忽略
    }
}

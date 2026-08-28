package io.github.javaside.springai.codetui.agent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 「动态」辅助 {@link ChatModel}：每次调用时<b>实时</b>解析 {@link ProviderRegistry} 的当前激活 provider 与模型，
 * 而不在装配期一次性绑定。
 *
 * <p><b>为何需要它</b>：{@code AgentTools} 里有两处内部 LLM 调用复用同一个「裸」ChatClient——
 * ① {@code SmartWebFetchTool} 的网页内容 AI 抽取；② 会话历史的滚动摘要（压缩）。它们的工具/策略实例在装配期
 * 建好、之后被所有 provider 的 ChatClient 共享，故若把辅助 ChatClient 绑死在「启动时的激活 provider」上，
 * 这两处便会<b>永久无视 {@code /model} 切换</b>——用户切到别家后，抽取/摘要仍打到启动那家。用本类作辅助
 * ChatClient 的底层模型，即可让两处<b>同时</b>跟随切换，且无需为每个 provider 复制一份工具/策略。
 *
 * <p><b>每次调用做两件事</b>：取激活 provider 的 {@link ChatModel} 作委托；用该家对激活模型的每请求
 * {@link ChatOptions}（只覆盖模型 + 该家必填项如 Anthropic 的 maxTokens）替换 prompt 的 options——与
 * {@code CodingAgent.submit} 主对话路径的每请求覆盖语义一致。辅助调用本就不带特殊 options（纯 system+user），
 * 整体替换安全；<b>唯一例外</b>是 prompt 自带 maxTokens（压缩摘要的 8192 输出上限）——它按<b>字段级覆盖</b>
 * 合并进基础 options，而不是被替换丢掉，防止摘要路径切到带必填 maxTokens 的家后输出上限静默漂移。
 *
 * <p><b>激活身份一次读取</b>：委托模型与 options 必须出自<b>同一个</b> provider 快照，否则并发 {@code /model}
 * 切换若在两次读之间交错，会把「B 家模型」配上「A 家 native options 类型」，触发跨家 options 类型错配。故走
 * {@link ProviderRegistry#activeRequestSelection()}——它对 (provider, modelId) 的读取与派生是同步原子的，
 * 身份与 options 永远同源。注意 aux 路径的 options 契约仍是 <b>DEFAULT 思考配置</b>（见
 * {@code DynamicAuxChatModelTest#auxAlwaysUsesDefaultConfig} 守卫），故身份取自快照、options 仍走
 * {@link LlmProvider#options(String)} 单参入口，而不取快照里思考配置感知的那份。
 */
public final class DynamicAuxChatModel implements ChatModel {

    private final ProviderRegistry registry;

    public DynamicAuxChatModel(ProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ProviderRegistry.RequestSelection sel = registry.activeRequestSelection();   // 单快照：模型与 options 同源
        return sel.provider().chatModel().call(withActiveOptions(prompt, sel));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ProviderRegistry.RequestSelection sel = registry.activeRequestSelection();
        return sel.provider().chatModel().stream(withActiveOptions(prompt, sel));
    }

    /**
     * 用快照中 provider 对快照模型的每请求 options 重建 prompt（保留原消息，覆盖模型）。
     *
     * <p>prompt 自带 maxTokens 时按字段级覆盖合并（mutate 保留 native options 的其余状态，
     * 如 Anthropic 必填项），其余情况整体替换——同历史行为。
     */
    private Prompt withActiveOptions(Prompt prompt, ProviderRegistry.RequestSelection sel) {
        ChatOptions base = sel.provider().options(sel.modelId());   // DEFAULT 思考配置（aux 契约）
        ChatOptions override = prompt.getOptions();
        ChatOptions merged = override == null || override.getMaxTokens() == null
                ? base
                : base.mutate().maxTokens(override.getMaxTokens()).build();
        return new Prompt(prompt.getInstructions(), merged);
    }
}

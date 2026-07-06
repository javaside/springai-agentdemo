package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
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
 * {@link org.springframework.ai.chat.prompt.ChatOptions}（只覆盖模型 + 该家必填项如 Anthropic 的 maxTokens）
 * 替换 prompt 的 options——与 {@code CodingAgent.submit} 主对话路径的每请求覆盖语义一致。辅助调用本就不带
 * 特殊 options（纯 system+user），整体替换安全。
 *
 * <p><b>激活 provider 一次读取</b>：委托模型与 options 必须出自<b>同一个</b> provider 快照，否则并发 {@code /model}
 * 切换若在两次读之间交错，会把「B 家模型」配上「A 家 native options 类型」，触发跨家 options 类型错配。故与
 * {@code CodingAgent.submit} 一致——把 {@link ProviderRegistry#active()} 读进一个局部变量，再由它派生模型与 options。
 */
public final class DynamicAuxChatModel implements ChatModel {

    private final ProviderRegistry registry;

    public DynamicAuxChatModel(ProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        LlmProvider active = registry.active();   // 单次读取：模型与 options 同源，见类注释
        return active.chatModel().call(withActiveOptions(prompt, active));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        LlmProvider active = registry.active();
        return active.chatModel().stream(withActiveOptions(prompt, active));
    }

    /** 用给定 provider 对当前激活模型的每请求 options 重建 prompt（保留原消息，覆盖模型），使委托模型按激活模型出参。 */
    private Prompt withActiveOptions(Prompt prompt, LlmProvider active) {
        return new Prompt(prompt.getInstructions(), active.options(registry.activeModelId()));
    }
}

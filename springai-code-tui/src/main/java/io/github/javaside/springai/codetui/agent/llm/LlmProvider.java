package io.github.javaside.springai.codetui.agent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;

/**
 * 一家大模型 provider 的抽象。主 agent 与子 agent 共用。
 *
 * <p>只有配了对应 API key 的 provider 才 {@link #available()}——不可用的不出现在 {@code /model}、不阻断启动。
 * {@link #chatModel()} 返回框架的 {@link ChatModel} 接口（三家实现都实现它），供 {@code ChatClient.builder} 通用装配。
 * {@link #options(String)} 返回该家 native 的每请求 {@link ChatOptions}（只覆盖模型；其余走 chatModel 的默认 options）。
 */
public interface LlmProvider {

    /** provider 稳定 id：{@code "deepseek"} | {@code "zhipu"} | {@code "qwen"} | {@code "anthropic"} | {@code "openai"}。 */
    String id();

    /** 对应 API key 是否已配置。false 则不装配 ChatModel、不出现在 /model。 */
    boolean available();

    /**
     * 该家的 {@link ChatModel}（带 key/base、默认 options）。仅在 {@link #available()} 为 true 时可调用；
     * 不可用时调用抛 {@link IllegalStateException}（装配期不应触碰不可用 provider）。
     */
    ChatModel chatModel();

    /** 每请求覆盖模型用的该家 native {@link ChatOptions}（只设 model，Anthropic 另附必填 maxTokens）。
     *
     * @param modelId 目标模型 id
     * @return 该家 native options，可直接交给对应 ChatModel
     */
    ChatOptions options(String modelId);

    /** 该模型支持的思考配置；默认不支持，保持旧 provider 的零行为变化。
     *
     * @param modelId 目标模型 id
     * @return 该模型的思考能力描述
     */
    default ThinkingCapabilities thinkingCapabilities(String modelId) {
        return ThinkingCapabilities.unsupported();
    }

    /** 带思考配置的每请求 options；默认只接受 DEFAULT。
     *
     * @param modelId 目标模型 id
     * @param config  思考配置；与 {@link #thinkingCapabilities} 不兼容时抛异常
     * @return 该家 native options
     */
    default ChatOptions options(String modelId, ThinkingConfig config) {
        thinkingCapabilities(modelId).validate(config);
        return options(modelId);
    }

    /** 该家可选模型（供 /model 展示与选择）。
     *
     * @return 模型清单，首项即 {@link #defaultModel()}
     */
    List<ModelOption> models();

    /** 该家默认模型 id（激活该家时的初始模型）。
     *
     * @return 默认模型 id
     */
    String defaultModel();

    /** 该模型的 provider 专属能力（视觉等）；安全默认值为纯文本。
     * 支持图片的 provider 必须显式覆写，未知模型应判为不支持。
     *
     * @param modelId 目标模型 id
     * @return 该模型的输入能力
     */
    default ModelCapabilities capabilities(String modelId) {
        return ModelCapabilities.TEXT_ONLY;
    }
}

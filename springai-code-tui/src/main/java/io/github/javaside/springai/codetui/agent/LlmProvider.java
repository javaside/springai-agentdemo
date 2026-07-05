package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

/**
 * 一家大模型 provider 的抽象。主 agent 与子 agent 共用。
 *
 * <p>只有配了对应 API key 的 provider 才 {@link #available()}——不可用的不出现在 {@code /model}、不阻断启动。
 * {@link #chatModel()} 返回框架的 {@link ChatModel} 接口（三家实现都实现它），供 {@code ChatClient.builder} 通用装配。
 * {@link #options(String)} 返回该家 native 的每请求 {@link ChatOptions}（只覆盖模型；其余走 chatModel 的默认 options）。
 */
public interface LlmProvider {

    /** provider 稳定 id：{@code "deepseek"} | {@code "anthropic"} | {@code "openai"}。 */
    String id();

    /** 对应 API key 是否已配置。false 则不装配 ChatModel、不出现在 /model。 */
    boolean available();

    /**
     * 该家的 {@link ChatModel}（带 key/base、默认 options）。仅在 {@link #available()} 为 true 时可调用；
     * 不可用时调用抛 {@link IllegalStateException}（装配期不应触碰不可用 provider）。
     */
    ChatModel chatModel();

    /** 每请求覆盖模型用的该家 native {@link ChatOptions}（只设 model，Anthropic 另附必填 maxTokens）。 */
    ChatOptions options(String modelId);

    /** 该家可选模型（供 /model 展示与选择）。 */
    List<ModelOption> models();

    /** 该家默认模型 id（激活该家时的初始模型）。 */
    String defaultModel();
}

package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingMode;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Routes immutable thinking settings to a fixed-config native DeepSeek delegate. */
final class DeepSeekThinkingChatModel implements ChatModel {

    private final ChatModel defaultDelegate;
    private final Function<ThinkingConfig, ChatModel> delegateFactory;
    private final Map<ThinkingConfig, ChatModel> delegates = new ConcurrentHashMap<>();
    private final io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry visionRegistry;

    /** 既有两参构造：配一个私有注册表（仅测试用；文本请求不碰注册表，行为与之前一致）。 */
    DeepSeekThinkingChatModel(ChatModel defaultDelegate, Function<ThinkingConfig, ChatModel> delegateFactory) {
        this(defaultDelegate, delegateFactory, new io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry());
    }

    DeepSeekThinkingChatModel(ChatModel defaultDelegate,
                              Function<ThinkingConfig, ChatModel> delegateFactory,
                              io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry visionRegistry) {
        this.defaultDelegate = defaultDelegate;
        this.delegateFactory = delegateFactory;
        this.visionRegistry = visionRegistry;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ThinkingConfig config = configOf(prompt.getOptions());
        ChatModel delegate = delegate(config);
        Prompt nativePrompt = withNativeOptions(prompt);
        java.util.List<String> registered = registerMedia(nativePrompt);
        try {
            return delegate.call(nativePrompt);
        } finally {
            registered.forEach(visionRegistry::take);   // 清理本请求注册、未被改写器消费的 key
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ThinkingConfig config = configOf(prompt.getOptions());
        ChatModel delegate = delegate(config);
        Prompt nativePrompt = withNativeOptions(prompt);
        java.util.List<String> registered = registerMedia(nativePrompt);
        return delegate.stream(nativePrompt)
                .doFinally(sig -> registered.forEach(visionRegistry::take));
    }

    /**
     * 把当轮 {@code UserMessage} 上的 {@code Media} 按「消息下标:media 序号」注册进
     * {@link DeepSeekVisionMediaRegistry}，供 HTTP 层改写器（{@code DeepSeekThinkingBodyCodec}）
     * 消费。<b>用户图与工具图（合成消息）在这里一视同仁</b>——两者对改写器都是
     * 「某条 user 消息 + media 列表」。
     *
     * <p><b>并发论证</b>：有图请求在本项目只有主 agent 当前回合（串行）；无图请求
     * （子 agent、摘要等）{@code registerMedia} 返回空列表，finally/doFinally 的清理是 no-op，
     * <b>不碰注册表</b>——与并发有图请求互不干扰。注册前防御性清空：若上一次有图请求
     * 的 Flux 从未被订阅（理论不发生，ChatClient 总是立即订阅），残留不会污染本次。
     */
    private java.util.List<String> registerMedia(Prompt prompt) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        java.util.List<org.springframework.ai.chat.messages.Message> msgs = prompt.getInstructions();
        if (msgs == null || msgs.isEmpty()) {
            return keys;
        }
        if (!visionRegistry.isEmpty()) {
            visionRegistry.clear();
        }
        for (int i = 0; i < msgs.size(); i++) {
            org.springframework.ai.chat.messages.Message m = msgs.get(i);
            if (!(m instanceof UserMessage user)) {
                continue;
            }
            java.util.List<Media> media = user.getMedia();
            if (media == null || media.isEmpty()) {
                continue;
            }
            for (int j = 0; j < media.size(); j++) {
                Media md = media.get(j);
                byte[] bytes = md.getDataAsByteArray();
                if (bytes == null || bytes.length == 0) {
                    continue;
                }
                String mime = md.getMimeType() == null ? "image/png" : md.getMimeType().toString();
                visionRegistry.put(i, j,
                        io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.Entry.inline(bytes, mime));
                keys.add(io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.key(i, j));
            }
        }
        return keys;
    }

    @Override
    public ChatOptions getOptions() {
        // 必须返回「思考包装」而非裸 native 选项：ChatClient 合并链以 getOptions().mutate() 起 base，
        // 若这里返回 native DeepSeekChatOptions，其 Builder 在 combineWith(每回合的 DeepSeekThinkingChatOptions.Builder)
        // 时既不匹配 DefaultChatOptionsBuilder 也不匹配 DeepSeek 的 AbstractBuilder，模型与思考配置都会被静默丢掉。
        return new DeepSeekThinkingChatOptions(defaultNativeOptions(), ThinkingConfig.defaults());
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return getOptions();
    }

    private org.springframework.ai.deepseek.DeepSeekChatOptions defaultNativeOptions() {
        return (org.springframework.ai.deepseek.DeepSeekChatOptions) defaultDelegate.getOptions();
    }

    int delegateCount() {
        return delegates.size();
    }

    private ChatModel delegate(ThinkingConfig config) {
        if (config.mode() == ThinkingMode.DEFAULT) {
            return defaultDelegate;
        }
        return delegates.computeIfAbsent(config, delegateFactory);
    }

    private Prompt withNativeOptions(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options instanceof DeepSeekThinkingChatOptions thinking) {
            return new Prompt(prompt.getInstructions(), thinking.nativeOptions());
        }
        return prompt;
    }

    private static ThinkingConfig configOf(ChatOptions options) {
        return options instanceof DeepSeekThinkingChatOptions thinking
                ? thinking.thinkingConfig() : ThinkingConfig.defaults();
    }
}

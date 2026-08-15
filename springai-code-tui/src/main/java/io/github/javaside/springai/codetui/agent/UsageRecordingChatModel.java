package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 记录 token 用量的 {@link ChatModel} 装饰器。包在每家 provider 的 chatModel 外，主 agent / 子 agent /
 * 摘要三条路径都经它，把每次模型调用的 usage 原子累加进共享的 {@link TokenUsageAccumulator}。
 *
 * <p><b>流式只记一次</b>：Spring AI 流式的每个 chunk 都带<b>累计</b> usage，最后一个 chunk 即完整值，
 * 故用 {@code doOnNext} 记最新、{@code doFinally} 提交一次（成功/报错/取消统一收口），杜绝按 chunk 重复计数。
 */
public final class UsageRecordingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final TokenUsageAccumulator accumulator;

    public UsageRecordingChatModel(ChatModel delegate, TokenUsageAccumulator accumulator) {
        this.delegate = delegate;
        this.accumulator = accumulator;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        record(usageOf(response));
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        AtomicReference<Usage> last = new AtomicReference<>();
        return delegate.stream(prompt)
                .doOnNext(response -> {
                    Usage usage = usageOf(response);
                    if (usage != null) {
                        last.set(usage);
                    }
                })
                .doFinally(signal -> record(last.get()));
    }

    /**
     * <b>必须</b>转发 {@link #getOptions()}：ChatClient 构建请求时从这里取基础 options
     * （{@code DefaultChatClientUtils}: {@code getChatModel().getOptions().mutate()}）。漏转发会落到
     * 接口 default（裸 {@code DefaultChatOptions}）→ provider 的 ChatModel 强转家族 options 时直接
     * ClassCastException。本项目在 {@code RetryingChatModel} / {@code VisionMaterializingChatModel}
     * 上栽过同一个坑。
     */
    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @SuppressWarnings("removal")   // 2.0 起 deprecated，default 已委托 getOptions()；显式转发保险
    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    private void record(Usage usage) {
        if (usage != null) {
            accumulator.record(usage);
        }
    }
}

package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class UsageRecordingChatModelTest {

    private static ChatResponse response(int prompt, int completion, long cacheRead) {
        var usage = new DefaultUsage(prompt, completion, prompt + completion, null, cacheRead, 0L);
        var meta = ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("x"))), meta);
    }

    /** 可控 delegate：call 返回 callResponse，stream 返回给定响应序列。 */
    private static ChatModel model(ChatResponse callResponse, List<ChatResponse> streamResponses) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return callResponse; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.fromIterable(streamResponses); }
        };
    }

    @Test
    void call_recordsOnce() {
        var acc = new TokenUsageAccumulator();
        var model = new UsageRecordingChatModel(model(response(100, 50, 80), List.of()), acc);

        model.call(new Prompt("hi"));

        assertEquals(100L, acc.snapshot().promptTokens());
        assertEquals(50L, acc.snapshot().completionTokens());
        assertEquals(80L, acc.snapshot().cacheReadTokens());
    }

    @Test
    void stream_recordsOnlyLastChunk() {
        var acc = new TokenUsageAccumulator();
        // 流式每个 chunk 都是累计 usage；最后一个才是完整值
        List<ChatResponse> chunks = List.of(
                response(100, 10, 10),
                response(100, 30, 40),
                response(100, 50, 80));
        var model = new UsageRecordingChatModel(model(null, chunks), acc);

        model.stream(new Prompt("hi")).blockLast();

        var s = acc.snapshot();
        assertEquals(100L, s.promptTokens(), "只记最后 chunk，不重复累加 prompt");
        assertEquals(50L, s.completionTokens(), "只记最后 chunk，不重复累加 completion");
        assertEquals(80L, s.cacheReadTokens());
    }

    @Test
    void stream_error_stillRecordsSeenUsage() {
        var acc = new TokenUsageAccumulator();
        Flux<ChatResponse> erroring = Flux.concat(
                Flux.just(response(100, 50, 80)),
                Flux.error(new RuntimeException("boom")));
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return null; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return erroring; }
        };
        var model = new UsageRecordingChatModel(delegate, acc);

        try {
            model.stream(new Prompt("hi")).blockLast();
        } catch (RuntimeException expected) {
            // doOnTerminate 在 onError 传播前仍提交
        }

        assertEquals(80L, acc.snapshot().cacheReadTokens(), "报错也应提交已看到的 usage");
    }

    @Test
    void stream_recordsBeforeDownstreamOnComplete() {
        // 回归：Reactor doFinally 的 callback 在 downstream.onComplete() 之后才执行，
        // 流完成信号到达下游时 record 尚未提交 → 恰好在此刻读统计会缺最后一笔。
        // 本测试在下游 doOnComplete 里断言「已提交」，钉住语义：必须用 doOnTerminate（先提交后传播）。
        var acc = new TokenUsageAccumulator();
        var model = new UsageRecordingChatModel(model(null, List.of(response(100, 50, 80))), acc);
        java.util.concurrent.atomic.AtomicLong seenAtComplete = new java.util.concurrent.atomic.AtomicLong(-1);

        model.stream(new Prompt("hi"))
                .doOnComplete(() -> seenAtComplete.set(acc.snapshot().promptTokens()))
                .blockLast();

        assertEquals(100L, seenAtComplete.get(), "下游收到完成信号时，最后一笔 usage 必须已进累加器");
        assertEquals(100L, acc.snapshot().promptTokens());
    }

    @Test
    void stream_cancel_stillRecordsSeenUsage() {
        var acc = new TokenUsageAccumulator();
        // 发一个 chunk 后挂起（永不完成），取消时提交已看到的累计 usage
        Flux<ChatResponse> hanging = Flux.concat(Flux.just(response(100, 30, 40)), Flux.never());
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return null; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return hanging; }
        };
        var model = new UsageRecordingChatModel(delegate, acc);

        reactor.core.Disposable d = model.stream(new Prompt("hi")).subscribe();
        d.dispose();

        assertEquals(100L, acc.snapshot().promptTokens(), "取消也应提交已看到的 usage");
        assertEquals(40L, acc.snapshot().cacheReadTokens());
    }

    @Test
    void forwardsOptionsToDelegate() {
        // 回归：装饰器漏转发 getOptions()/getDefaultOptions() 会落到接口 default（DefaultChatOptions），
        // 下游 provider ChatModel 强转家族 options 时抛 ClassCastException（DefaultChatOptions → DeepSeekChatOptions）。
        ChatOptions opts = DeepSeekChatOptions.builder().model("deepseek-v4-pro").build();
        ChatOptions defaultOpts = DeepSeekChatOptions.builder().model("deepseek-v4-flash").build();
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return null; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
            @Override public ChatOptions getOptions() { return opts; }
            @Override public ChatOptions getDefaultOptions() { return defaultOpts; }
        };
        var model = new UsageRecordingChatModel(delegate, new TokenUsageAccumulator());

        assertSame(opts, model.getOptions(), "getOptions() 必须转发 delegate 的家族 options");
        assertSame(defaultOpts, model.getDefaultOptions(), "getDefaultOptions() 必须转发 delegate");
    }
}

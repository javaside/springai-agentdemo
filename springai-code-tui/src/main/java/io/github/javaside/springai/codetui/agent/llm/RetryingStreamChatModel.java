package io.github.javaside.springai.codetui.agent.llm;

import dev.tamboui.text.CharWidth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主 agent 专用 ChatModel 装饰器（spec
 * {@code docs/superpowers/specs/2026-09-03-main-agent-stream-retry-design.md} §3.2）：
 * <b>L1 零下发透明重试</b>——只对「下游还没收到任何非空 chunk」的瞬态失败重订阅（对用户完全透明，
 * 屏幕无内容、无残行），mid-stream 瞬态失败<b>包装 {@link StreamInterruptedException} 放行</b>给
 * L2（CodingAgent 回合级续跑），空流（正常完成但零有效内容）视同零下发失败。
 *
 * <p><b>L1 重试安全不变式</b>：L1 重订阅安全 ⇔ {@code emitted==0} ⇔ 下游未收到任何非空 chunk
 * （{@code retryWhen} 的 filter 恒等式 {@code emitted==0 && shouldRetry} 保证 emitted&gt;0 恒拒绝
 * 重试；空白-only chunk <b>计入</b> emitted——它已被下游下发，不计数会破「零下发 = 下游零观测」
 * 不变式，R6-m1）。
 *
 * <p><b>classify 判定树（§3.2）</b>——能到达 classify 的错误只有两种：
 * <ol>
 *   <li>{@code Retry.backoff} 耗尽包装（私有 {@code RetryExhaustedException}，判定用公有
 *       {@link reactor.core.Exceptions#isRetryExhausted}）→ 取 {@code getCause()} 解包放行
 *       最后一次原始失败；</li>
 *   <li>被 filter 拒绝的非包装错误——其中 {@code shouldRetry && emitted>0} → 包装
 *       {@link StreamInterruptedException}（message 置空）放行给 L2；其余（4xx、中断/取消、
 *       非瞬态）→ 原样放行。</li>
 * </ol>
 *
 * <p><b>退避</b>：{@code Retry.backoff(4, 500ms)} 指数封顶 4s、jitter(0) <b>显式关闭</b>
 * （默认 0.5 会随机化退避，打翻序列测试、UI 文案「0.5s 后重发」与预算算术；单用户 TUI
 * 无并发不需要抖动，且与子 agent {@code Thread.sleep} 版严格同参）。判据与退避的唯一真相源是
 * {@link RetryPolicy}。
 *
 * <p><b>前提（单回合串行约束）</b>：{@code emitted} 为实例字段、doOnSubscribe 重置——主链同一
 * 时刻仅一个活跃订阅。{@link #stream} 每次调用返回的冷流按订阅序各自重置，行为与局部变量等价。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class RetryingStreamChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RetryingStreamChatModel.class);

    static final long BACKOFF_MS = 500;
    static final long CAP_BACKOFF_MS = 4000;
    /** 重试次数（⚠ Retry.backoff 首参是重试次数不是总尝试数）：总尝试 = 5，与子 agent 对齐。 */
    static final long L1_RETRIES = 4;

    /** reason 的显示宽上限（UI ↻ 行 reason 部分的预算，超出尾加 …）。 */
    static final int REASON_MAX_WIDTH = 60;

    private final ChatModel delegate;
    /** 可选重试事件钩子（UI ↻ 行）；null = no-op。 */
    private final RetryReporter reporter;

    /**
     * 已向下游下发的「有内容」chunk 计数（text 非空含纯空白，或 hasToolCalls）。
     * filter 与 classify 都读它——Reactor 信号传递自带 happens-before，读到的是终值；
     * 重置只在（重）订阅时（doOnSubscribe）。
     */
    private final AtomicInteger emitted = new AtomicInteger();

    private RetryingStreamChatModel(ChatModel delegate, RetryReporter reporter) {
        this.delegate = delegate;
        this.reporter = reporter;
    }

    /** 包裹一个 ChatModel 为 L1 零下发重试装饰器（生产装配入口；reporter 可 null）。 */
    public static ChatModel wrap(ChatModel delegate, RetryReporter reporter) {
        return new RetryingStreamChatModel(delegate, reporter);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt)
                .doOnSubscribe(s -> emitted.set(0))                       // 每次订阅/重订阅重置
                .doOnNext(r -> { if (hasContent(r)) emitted.incrementAndGet(); })
                .concatWith(Mono.defer(() -> emitted.get() == 0           // 空流守卫（完成但零有效内容）
                        ? Mono.error(new EmptyStreamException("LLM 流式响应为空（无文本、无工具调用）——疑似网关空响应"))
                        : Mono.empty()))                                   // → 转 error 进 retryWhen
                .retryWhen(Retry.backoff(L1_RETRIES, Duration.ofMillis(BACKOFF_MS))
                        .jitter(0d)                                        // R4：显式关闭，序列 0.5/1/2/4
                        .maxBackoff(Duration.ofMillis(CAP_BACKOFF_MS))
                        .doBeforeRetry(sig -> {
                            // attempt = 即将进行的第几次尝试（首重试=2），与 RetryReporter 口径一致
                            int attempt = (int) sig.totalRetries() + 2;
                            long backoffMs = RetryPolicy.backoffMsAfter((int) sig.totalRetries() + 1);
                            String reason = reasonOf(sig.failure());
                            log.warn("主 agent 流式请求失败（第 {}/{} 次），{}ms 后重试：{}",
                                    attempt, L1_RETRIES + 1, backoffMs, reason);
                            if (reporter != null) {
                                reporter.report(attempt, backoffMs, reason);
                            }
                        })
                        .filter(ex -> emitted.get() == 0 && RetryPolicy.shouldRetry(ex)))
                .transformDeferred(this::classify);
    }

    /**
     * classify 三出口（§3.2 判定树）：耗尽解包放行 / mid-stream 包装放行 / 其余原样。
     * 能到达这里的错误只有两种（被接受未耗尽的已被 retryWhen 重订阅吞掉）。
     */
    private Publisher<ChatResponse> classify(Flux<ChatResponse> f) {
        return f.onErrorMap(ex -> {
            if (reactor.core.Exceptions.isRetryExhausted(ex)) {
                return ex.getCause();                                      // 出口1：解包放行最后一次原始失败
            }
            if (RetryPolicy.shouldRetry(ex) && emitted.get() > 0) {
                return new StreamInterruptedException(emitted.get(), ex);   // 出口2：L2 白名单
            }
            return ex;                                                    // 出口3：原样（4xx/取消/非瞬态/穿透类型）
        });
    }

    /**
     * chunk 是否「有内容」：text 非空（{@code != null && !isEmpty()}，<b>含纯空白</b>，R6-m1）
     * 或 hasToolCalls。usage-only / finish-only 收尾 chunk 不计。null 防护照抄
     * {@link RetryingChatModel#isEffectivelyEmpty}（{@code getResult()} 可为 null）。
     */
    static boolean hasContent(ChatResponse r) {
        if (r == null || r.getResult() == null) return false;
        AssistantMessage out = r.getResult().getOutput();
        boolean nonEmptyText = out.getText() != null && !out.getText().isEmpty();
        return nonEmptyText || out.hasToolCalls();
    }

    /**
     * 根因摘要（直接流入 UI ↻ 行，规则钉死不得自行发明）：沿 cause 链取首个非空 message——推导委托
     * {@link RetryPolicy#firstNonBlankMessage}（与 CodingAgent rootCauseText 同源，防两处漂移），
     * 类名兜底，显示宽超 {@value #REASON_MAX_WIDTH} 尾加 …。
     */
    static String reasonOf(Throwable failure) {
        if (failure == null) return "unknown";
        return CharWidth.truncateWithEllipsis(
                RetryPolicy.firstNonBlankMessage(failure, failure.getClass().getSimpleName()),
                REASON_MAX_WIDTH, "…", CharWidth.TruncatePosition.END);
    }

    /** 主 agent 不用 call()；原样委托（与 stream 路径的重试语义无关）。 */
    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    /**
     * <b>必须</b>转发 2.0 的 {@link #getOptions()}（三装饰器栽过的坑）：ChatClient 构建请求时
     * 从这里取基础 options。漏转发会落到接口 default（裸 DefaultChatOptions）→ 不是
     * ToolCallingChatOptions、ToolCallingAdvisor 整个跳过（丢工具），且 provider ChatModel
     * 强转家族 options 直接 ClassCastException。
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
}

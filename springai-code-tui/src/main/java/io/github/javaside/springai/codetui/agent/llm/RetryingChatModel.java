package io.github.javaside.springai.codetui.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * 子 agent 专用 ChatModel 装饰器（见 {@code SubagentRunner}）：把阻塞式 {@link #call} <b>桥接到流式</b>
 * {@link ChatModel#stream} 并聚合回单个 {@link ChatResponse}，外加「瞬态坏响应」重试。
 *
 * <p><b>为什么桥接到流式（curl 实测证据，2026-07-15）</b>：代理网关的<b>非流式</b>端点存在长达数分钟的坏窗口，
 * 期间几乎 100% 请求返回 HTTP 200 + <b>空 body</b>（一轮实测 8/8 全空），SDK 在 2xx 上直接反序列化即抛
 * *InvalidDataException（cause=Jackson end-of-input）；而<b>流式</b>端点同窗口持续正常（主 agent 全天稳定）。
 * 坏窗口以分钟计，call() 级重试无论多少次都穿不过去——必须换到实测稳定的流式传输，重试只作二道防线。
 *
 * <p><b>为什么在 ChatModel 层桥接</b>而不是 SubagentRunner 改用 ChatClient.stream()：工具循环
 * （ToolCallingAdvisor.adviseCall）在本层之上，桥接对其透明；重试保持「单次 LLM call」粒度——循环中途某次
 * 失败不丢已完成的工具迭代。聚合用框架自带 {@link MessageAggregator}（ToolCallingAdvisor 流式路径同款），
 * 工具调用增量已由各 provider 的 stream 实现合并成完整 ToolCall，聚合结果对工具循环等价。
 *
 * <p><b>空流守卫</b>：坏窗口下网关也可能回「正常完成但零内容」的空流（不抛异常）——聚合结果既无文本也无
 * 工具调用时视同瞬态失败重试，绝不把空串静默交回主 agent（实测曾致主 agent 误判「子代理返回空响应」）。
 *
 * <p><b>不</b>重试取消/中断（回合 Esc 要立即退出，且中断标志位必须保留）；stream() 原样透传（子 agent 不用）。
 *
 * <p><b>退避</b>：指数 1s×2^n 封顶 30s（序列 1s·2s·4s·8s·16s·30s），实际取 {@link RetryPolicy#nextDelayMs}
 * （对齐 Retry-After 头），总尝试 7 次。瞬态判据与退避的唯一真相源是
 * {@link RetryPolicy}（与后续 RetryingStreamChatModel 共用），本类仅保留同名静态方法委托
 * （2026-08-17 生产日志实测扩容）。休眠可注入
 * （{@link RetryingChatModel#RetryingChatModel(ChatModel, LongConsumer)}），测试不必真实等待。
 * 限额等待（智谱 Coding Plan 429 限额码）经 {@link RetryPolicy#quotaWaitMs} 分流：睡到重置点、
 * 不占普通退避预算（quotaWaits ≤ {@link RetryPolicy#MAX_QUOTA_WAITS}）——见 spec
 * {@code 2026-09-22-zhipu-quota-wait-retry-design.md}。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class RetryingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RetryingChatModel.class);

    /** 总尝试次数（1 次原始 + 6 次重试）。日志实测网关坏窗口/限流以十秒计，退避序列 1s·2s·4s·8s·16s·30s。 */
    static final int MAX_ATTEMPTS = 7;

    private final ChatModel delegate;
    /** 休眠器：生产 Thread::sleep；测试注入收集间隔的桩，避免真实等待。 */
    private final LongConsumer sleeper;
    /** 可选限额等待钩子（UI ⏳ 行）；null = no-op。 */
    private final RetryPolicy.QuotaWaitHook quotaHook;

    /** 生产装配（既有语义；quota 上报 no-op）。 */
    public static ChatModel wrap(ChatModel delegate) {
        return wrap(delegate, null);
    }

    /** 全参装配（spec §3.4.4）：quotaHook 为限额等待 UI 上报；null = no-op。 */
    public static ChatModel wrap(ChatModel delegate, RetryPolicy.QuotaWaitHook quotaHook) {
        return new RetryingChatModel(delegate, quotaHook);
    }

    private RetryingChatModel(ChatModel delegate, RetryPolicy.QuotaWaitHook quotaHook) {
        this(delegate, defaultSleeper(), quotaHook);
    }

    /** 既有两参构造（既有测试在用，保留委托——H3：删了会编译失败）。 */
    RetryingChatModel(ChatModel delegate, LongConsumer sleeper) {
        this(delegate, sleeper, null);
    }

    /** 测试可见：注入休眠器与限额钩子。 */
    RetryingChatModel(ChatModel delegate, LongConsumer sleeper, RetryPolicy.QuotaWaitHook quotaHook) {
        this.delegate = delegate;
        this.sleeper = sleeper;
        this.quotaHook = quotaHook;
    }

    /** 生产 sleeper（原构造内匿名体提取为方法，两处构造共用；换算纪律不变——调用点传 raw 值）。 */
    private static LongConsumer defaultSleeper() {
        return ms -> {
            try {
                // 实际睡眠经 RetryPolicy 的测试钩子换算（生产恒等）——与 L1/L2 的 reactive 退避共用
                // 同一压缩函数：耗尽用例一处 setDelayScaleForTest 即可让子 agent 阻塞退避也秒过。
                Thread.sleep(RetryPolicy.scaledDelayMsForTest(ms));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        };
    }

    /**
     * 计算第 attempt 次失败后的退避毫秒数（attempt 为 1 基尝试序号）。纯委托
     * {@link RetryPolicy#backoffMsAfter}（唯一真相源）；签名保留供既有测试直调。
     */
    static long backoffMsAfter(int attempt) {
        return RetryPolicy.backoffMsAfter(attempt);
    }

    /**
     * 阻塞调用 + 瞬态重试（while 形态，spec §3.4 实现红线：单次迭代恰一次睡眠；
     * 限额判定先于 attempt 预算 bail——否则第 7 次尝试上的限额直接抛）。
     * 限额等待不递增 attempt（预算豁免），由 quotaWaits ≤ {@link RetryPolicy#MAX_QUOTA_WAITS} 兜底。
     *
     * <p><b>空流豁免 shouldRetry（语义钉）</b>：原 for 实现对空流是合成异常后<b>无条件</b>重试
     * （不问 shouldRetry——合成异常无瞬态特征，问就是必抛）。while 版用 emptyStream 标志保留该语义。
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        int attempt = 1;                 // 即将进行的尝试（1 基）
        int quotaWaits = 0;              // 本 call 内的连续限额等待（每次调用重建，天然回合级）
        while (true) {
            boolean emptyStream;
            RuntimeException failure;
            try {
                ChatResponse aggregated = streamAndAggregate(prompt);
                if (!isEffectivelyEmpty(aggregated)) {
                    return aggregated;
                }
                emptyStream = true;      // 空流：不设 shouldRetry 门（原 for 语义）
                failure = new RuntimeException("LLM 流式响应为空（无文本、无工具调用）——疑似网关空响应，已尝试 "
                        + attempt + "/" + MAX_ATTEMPTS + " 次");
                log.warn("LLM 返回空流（疑似网关坏响应），第 {}/{} 次尝试{}", attempt, MAX_ATTEMPTS,
                        attempt < MAX_ATTEMPTS ? "，将重试" : "，放弃");
            } catch (RuntimeException ex) {
                emptyStream = false;
                failure = ex;
            }
            // 限额分支：先于普通预算 bail；取消/中断类失败（shouldRetry 否决）与空流绝不等待
            Optional<QuotaLimit> quota = (!emptyStream && RetryPolicy.shouldRetry(failure))
                    ? QuotaLimitDetector.detect(failure) : Optional.empty();
            if (quota.isPresent() && quota.get().resetAt() != null) {
                if (quotaWaits++ >= RetryPolicy.MAX_QUOTA_WAITS) {
                    throw failure;                                 // 限额兜底终态
                }
                long waitMs = RetryPolicy.quotaWaitMs(quota.get());
                log.warn("智谱限额已到（code={}，resetAt={}），等待 {}ms 后重试（quotaWait {}/{}）：{}",
                        quota.get().code(), quota.get().resetAt(), waitMs, quotaWaits,
                        RetryPolicy.MAX_QUOTA_WAITS,
                        RetryPolicy.firstNonBlankMessage(failure, failure.getClass().getSimpleName()));
                if (quotaHook != null) {
                    quotaHook.onQuotaWait(waitMs, quota.get().resetAt().toEpochMilli(),
                            RetryPolicy.firstNonBlankMessage(failure, failure.getClass().getSimpleName()));
                }
                sleeper.accept(waitMs);
                continue;                                          // attempt 不递增：不占普通预算
            }
            if ((!emptyStream && !shouldRetry(failure)) || attempt >= MAX_ATTEMPTS) {
                throw failure;
            }
            if (!emptyStream) {   // 空流只打上面那条日志，别双打
                log.warn("LLM 流式请求失败（疑似网关坏响应），第 {}/{} 次尝试后重试：{}",
                        attempt, MAX_ATTEMPTS, failure.getMessage());
            }
            sleeper.accept(RetryPolicy.nextDelayMs(attempt, failure));   // raw 值：生产 sleeper 体内做换算
            attempt++;
        }
    }

    /** 一次流式请求 + 聚合为单响应。被中断时 blockLast 抛 RuntimeException(InterruptedException)——不重试、快速退出。 */
    private ChatResponse streamAndAggregate(Prompt prompt) {
        AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
        new MessageAggregator().aggregate(delegate.stream(prompt), aggregated::set).blockLast();
        return aggregated.get();
    }

    /** 聚合结果是否「实质为空」：无文本且无工具调用。纯函数，便于单测。 */
    static boolean isEffectivelyEmpty(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return true;
        }
        AssistantMessage out = response.getResult().getOutput();
        boolean blankText = out.getText() == null || out.getText().isBlank();
        return blankText && !out.hasToolCalls();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    /**
     * <b>必须</b>转发 2.0 的 {@link #getOptions()}：ChatClient 构建请求时从这里取基础 options
     * （{@code DefaultChatClientUtils}: {@code getChatModel().getOptions().mutate()}）。漏转发会落到接口
     * default（裸 DefaultChatOptions）→ 不是 ToolCallingChatOptions、ToolCallingAdvisor 整个跳过（子 agent
     * 丢工具），且 provider ChatModel 强转家族 options 直接 ClassCastException。
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

    /**
     * 是否值得重试（瞬态判据）。纯委托 {@link RetryPolicy#shouldRetry}（唯一真相源，
     * 与后续 RetryingStreamChatModel 共用）；签名保留供既有测试直调。
     */
    static boolean shouldRetry(Throwable ex) {
        return RetryPolicy.shouldRetry(ex);
    }
}

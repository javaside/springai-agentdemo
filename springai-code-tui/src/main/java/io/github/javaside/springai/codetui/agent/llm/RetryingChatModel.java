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
 * <p><b>退避</b>：指数 500ms×2^n 封顶 4s，总尝试 5 次。瞬态判据与退避的唯一真相源是
 * {@link RetryPolicy}（与后续 RetryingStreamChatModel 共用），本类仅保留同名静态方法委托
 * （2026-08-17 生产日志实测扩容）。休眠可注入
 * （{@link RetryingChatModel#RetryingChatModel(ChatModel, LongConsumer)}），测试不必真实等待。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class RetryingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RetryingChatModel.class);

    /** 总尝试次数（1 次原始 + 4 次重试）。日志实测网关坏窗口/限流以十秒计，3 次等价没等。 */
    static final int MAX_ATTEMPTS = 5;

    private final ChatModel delegate;
    /** 休眠器：生产 Thread::sleep；测试注入收集间隔的桩，避免真实等待。 */
    private final LongConsumer sleeper;

    private RetryingChatModel(ChatModel delegate) {
        this(delegate, ms -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        });
    }

    /** 测试可见：注入自定义休眠器（收集中断请求等）。 */
    RetryingChatModel(ChatModel delegate, LongConsumer sleeper) {
        this.delegate = delegate;
        this.sleeper = sleeper;
    }

    /**
     * 包裹一个 ChatModel 为重试装饰器（生产装配入口）。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static ChatModel wrap(ChatModel delegate) {
        return new RetryingChatModel(delegate);
    }

    /**
     * 计算第 attempt 次失败后的退避毫秒数（attempt 为 1 基尝试序号）。纯委托
     * {@link RetryPolicy#backoffMsAfter}（唯一真相源）；签名保留供既有测试直调。
     */
    static long backoffMsAfter(int attempt) {
        return RetryPolicy.backoffMsAfter(attempt);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                sleeper.accept(backoffMsAfter(attempt - 1));
            }
            try {
                ChatResponse aggregated = streamAndAggregate(prompt);
                if (!isEffectivelyEmpty(aggregated)) {
                    return aggregated;
                }
                // 空流守卫：正常完成但零内容零工具调用——网关坏窗口的另一副面孔，按瞬态失败重试
                last = new RuntimeException("LLM 流式响应为空（无文本、无工具调用）——疑似网关空响应，已尝试 "
                        + attempt + "/" + MAX_ATTEMPTS + " 次");
                log.warn("LLM 返回空流（疑似网关坏响应），第 {}/{} 次尝试{}",
                        attempt, MAX_ATTEMPTS, attempt < MAX_ATTEMPTS ? "，将重试" : "，放弃");
            } catch (RuntimeException ex) {
                if (!shouldRetry(ex) || attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
                last = ex;
                log.warn("LLM 流式请求失败（疑似网关坏响应），第 {}/{} 次尝试后重试：{}",
                        attempt, MAX_ATTEMPTS, ex.getMessage());
            }
        }
        throw last;
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

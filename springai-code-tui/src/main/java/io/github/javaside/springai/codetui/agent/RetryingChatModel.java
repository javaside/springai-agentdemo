package io.github.javaside.springai.codetui.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * 子 agent 专用 ChatModel 装饰器（见 {@link SubagentRunner}）：把阻塞式 {@link #call} <b>桥接到流式</b>
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
 * <p><b>退避</b>：指数 500ms×2^n 封顶 4s，总尝试 5 次。瞬态判据见 {@link #shouldRetry}（2026-08-17
 * 生产日志实测扩容）。休眠可注入（{@link RetryingChatModel#RetryingChatModel(ChatModel, LongConsumer)}），
 * 测试不必真实等待。
 */
final class RetryingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RetryingChatModel.class);

    /** 总尝试次数（1 次原始 + 4 次重试）。日志实测网关坏窗口/限流以十秒计，3 次等价没等。 */
    static final int MAX_ATTEMPTS = 5;
    /** 首次重试前的退避毫秒数；之后指数翻倍（500、1000、2000、4000），封顶见 {@link #CAP_BACKOFF_MS}。 */
    private static final long BACKOFF_MS = 500;
    /** 单次退避封顶：再往上也只是干等，网关级别的长坏窗口救不了（那是换流式传输解决的事）。 */
    private static final long CAP_BACKOFF_MS = 4000;

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

    static ChatModel wrap(ChatModel delegate) {
        return new RetryingChatModel(delegate);
    }

    /** 计算第 attempt 次失败后的退避毫秒数：BACKOFF_MS × 2^(attempt-1)，封顶 CAP_BACKOFF_MS。纯函数。 */
    static long backoffMsAfter(int attempt) {
        long ms = BACKOFF_MS;
        for (int i = 1; i < attempt && ms < CAP_BACKOFF_MS; i++) {
            ms *= 2;
        }
        return Math.min(ms, CAP_BACKOFF_MS);
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
     * 是否值得重试：cause 链逐层判断，取消/中断优先短路（Esc 回合取消的伴生，绝不重试）。
     *
     * <p>瞬态判据（2026-08-17 生产日志实测的四类故障，spec：
     * {@code docs/superpowers/specs/2026-08-18-subagent-retry-transient-expansion-design.md}）：
     * <ul>
     *   <li>「2xx + 坏 body」解析失败：*InvalidDataException 类名后缀（openai/anthropic 同名后缀，
     *       按类名匹配保持 provider 中立）或 Jackson 的 "No content to map"；
     *   <li>网络断连：{@link IOException} 家族（EOF/SocketTimeout/Connect 均子类），或类名以
     *       IoException 结尾（openai-java 的 OpenAIIoException，同法不引新依赖）；
     *   <li>流中途断开：message 含 "EOF reached while reading"（WebClientResponseException 把
     *       EOFException 摊平进顶层 message、cause 链上只剩自身的场景）；
     *   <li>限流：message 含 "rate limit"（大小写不敏感；覆盖 200-wrapped 的 SseException 与 429）；
     *   <li>网关 5xx：cause 链上的 WebClientResponseException 且 is5xxServerError。
     * </ul>
     *
     * <p><b>红线不重试</b>：401/403（欠费、密钥错——重试只会更慢更花钱）、其余 4xx（请求本身有病）、
     * 中断/取消。把 IOException 全家族视为瞬态有理论误伤面（证书错误等），但误伤代价只是几次
     * 快速失败，漏掉代价是整个子 agent 报废重跑。纯函数，便于单测。
     */
    static boolean shouldRetry(Throwable ex) {
        boolean transientFailure = false;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof CancellationException) {
                return false;
            }
            String cls = t.getClass().getSimpleName();
            if (cls.endsWith("InvalidDataException") || cls.endsWith("IoException")
                    || t instanceof IOException) {
                transientFailure = true;
            }
            if (t instanceof WebClientResponseException wcre) {
                if (wcre.getStatusCode().is5xxServerError()) {
                    transientFailure = true;
                } else if (wcre.getStatusCode().is4xxClientError()) {
                    // 4xx（401/403/400…）是确定态：重试无意义且欠费场景下更花钱。
                    // 2xx 不在此列——「200 OK 但 body 坏」正是网关坏窗口的形态，交给 EOF/解析特征判定。
                    return false;
                }
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("no content to map") || lower.contains("eof reached while reading")
                        || lower.contains("rate limit")) {
                    transientFailure = true;
                }
            }
        }
        return transientFailure;
    }
}

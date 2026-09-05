package io.github.javaside.springai.codetui.agent.llm;

import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/**
 * LLM 重试策略的<b>唯一真相源</b>：瞬态判据（{@link #shouldRetry}）与指数退避（{@link #backoffMsAfter}）
 * 的全部逻辑都在这里。{@link RetryingChatModel} 与后续的 RetryingStreamChatModel <b>共用</b>本类，
 * 各自只保留同名静态方法做纯委托——判据改动只允许发生在此处。
 *
 * <p><b>红线（4xx/中断/取消）不动</b>：401/403（欠费、密钥错——重试只会更慢更花钱）、其余 4xx
 * （请求本身有病）、中断/取消（Esc 回合取消的伴生，绝不重试）一律否决。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class RetryPolicy {

    /** 首次重试前的退避毫秒数；之后指数翻倍（500、1000、2000、4000），封顶见 {@link #CAP_BACKOFF_MS}。 */
    private static final long BACKOFF_MS = 500;
    /** 单次退避封顶：再往上也只是干等，网关级别的长坏窗口救不了（那是换流式传输解决的事）。 */
    private static final long CAP_BACKOFF_MS = 4000;

    private RetryPolicy() {
    }

    /**
     * 计算第 attempt 次失败后的退避毫秒数：BACKOFF_MS × 2^(attempt-1)，封顶 CAP_BACKOFF_MS。纯函数。
     *
     * <p>{@code attempt} 为 <b>1 基尝试序号</b>（第 n 次尝试失败后的退避，{@code backoffMsAfter(1)=500}）——
     * 与 RetryReporter 的 attempt（即将进行的尝试 2..5）差 1，勿混。
     */
    public static long backoffMsAfter(int attempt) {
        long ms = BACKOFF_MS;
        for (int i = 1; i < attempt && ms < CAP_BACKOFF_MS; i++) {
            ms *= 2;
        }
        return Math.min(ms, CAP_BACKOFF_MS);
    }

    /**
     * 是否值得重试：cause 链逐层判断，取消/中断优先短路（Esc 回合取消的伴生，绝不重试）。
     *
     * <p>瞬态判据（2026-08-17 生产日志实测的四类故障 + 流式场景，spec：
     * {@code docs/superpowers/specs/2026-08-18-subagent-retry-transient-expansion-design.md}）：
     * <ul>
     *   <li>「2xx + 坏 body」解析失败：*InvalidDataException 类名后缀（openai/anthropic 同名后缀，
     *       按类名匹配保持 provider 中立）或 Jackson 的 "No content to map"；
     *   <li>网络断连：{@link IOException} 家族（EOF/SocketTimeout/Connect 均子类），或类名以
     *       IoException 结尾（openai-java 的 OpenAIIoException，同法不引新依赖）；
     *   <li>流中途断开：message 含 "EOF reached while reading"（WebClientResponseException 把
     *       EOFException 摊平进顶层 message、cause 链上只剩自身的场景）；
     *   <li>限流：message 含 "rate limit"（大小写不敏感；覆盖 200-wrapped 的 SseException 与 429 文案）
     *       或 {@link WebClientResponseException} 状态 429——429 虽是 4xx，但它是唯一的
     *       「请求没病、服务端在节流」4xx（Retry-After 语义），spec §5 L1 行「零下发 429 →
     *       重试成功」点名（Task 2 补，见该任务报告的偏差记录）；
     *   <li>网关 5xx：cause 链上的 WebClientResponseException 且 is5xxServerError；
     *   <li>流式专属：{@link StreamIdleTimeoutException}（空闲超时）与 {@link EmptyStreamException}
     *       （空流）——网关坏窗口在流式路径上的两副面孔。
     * </ul>
     *
     * <p><b>红线不重试</b>：401/403（欠费、密钥错——重试只会更慢更花钱）、其余 4xx（除 429 限流；
     * 请求本身有病）、中断/取消——含 {@link StreamInterruptedException}（L1 的 mid-stream 出口
     * 包装类型：它<b>本身携带</b>「已下发 chunk」语义，重试等于向下游重放已见内容；且它必须
     * 原样穿透 L1 的 retryWhen 才能命中 L2 白名单，spec §3.2 类型穿透要求）。把 IOException
     * 全家族视为瞬态有理论误伤面（证书错误等），但误伤代价只是几次快速失败，漏掉代价是
     * 整个子 agent 报废重跑。纯函数，便于单测。
     */
    public static boolean shouldRetry(Throwable ex) {
        boolean transientFailure = false;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof CancellationException
                    || t instanceof StreamInterruptedException) {
                return false;
            }
            String cls = t.getClass().getSimpleName();
            if (t instanceof StreamIdleTimeoutException || t instanceof EmptyStreamException
                    || cls.endsWith("InvalidDataException") || cls.endsWith("IoException")
                    || t instanceof IOException) {
                transientFailure = true;
            }
            if (t instanceof WebClientResponseException wcre) {
                if (wcre.getStatusCode().is5xxServerError()
                        || wcre.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    transientFailure = true;
                } else if (wcre.getStatusCode().is4xxClientError()) {
                    // 其余 4xx（401/403/400…）是确定态：重试无意义且欠费场景下更花钱。
                    // 2xx 不在此列——「200 OK 但 body 坏」正是网关坏窗口的形态，交给 EOF/解析特征判定。
                    return false;
                }
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("no content to map") || lower.contains("eof reached while reading")
                        || lower.contains("rate limit")) {
                    transientFailure = true;
                }
            }
        }
        return transientFailure;
    }
}

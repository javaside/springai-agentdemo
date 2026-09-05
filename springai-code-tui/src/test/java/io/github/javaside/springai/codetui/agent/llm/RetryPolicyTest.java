package io.github.javaside.springai.codetui.agent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetryPolicy（瞬态判据 + 指数退避的唯一真相源）：RetryingChatModel.shouldRetry/backoffMsAfter
 * 必须是它的<b>纯委托</b>。等价性用<b>比对式</b>（两边调用 assertEquals），不抄期望值——
 * 将来有人在 RetryingChatModel 上解开委托、改回本体，这里立即红。
 */
class RetryPolicyTest {

    /** 模拟 openai-java 的 OpenAIInvalidDataException（按类名后缀匹配，无需真依赖）。 */
    private static final class FakeInvalidDataException extends RuntimeException {
        FakeInvalidDataException() {
            super("Error reading response", new RuntimeException("No content to map due to end-of-input"));
        }
    }

    /** 模拟 openai-java 的 OpenAIIoException（OkHttp 断连：Request failed / Stream failed）。 */
    private static final class FakeIoException extends RuntimeException {
        FakeIoException(String message) {
            super(message, new IOException("connection closed"));
        }
    }

    /** 瞬态命中分支：解析失败 / IOException 家族 / EOF 中断 body / 限流 / 5xx。 */
    private static List<Throwable> transientCases() {
        List<Throwable> cases = new ArrayList<>();
        // 「2xx + 坏 body」解析失败：类名后缀 / Jackson message
        cases.add(new FakeInvalidDataException());
        cases.add(new RuntimeException("wrapper", new RuntimeException("No content to map due to end-of-input")));
        // IOException 家族：EOF / SocketTimeout / 裸 IOException / 类名后缀 IoException
        cases.add(new RuntimeException(new EOFException("EOF reached while reading")));
        cases.add(new RuntimeException(new SocketTimeoutException("read timed out")));
        cases.add(new RuntimeException(new IOException("connection reset")));
        cases.add(new FakeIoException("Request failed"));
        // 200 OK 但 body 中途断（WebClientResponseException 摊平 EOF 进顶层 message）
        WebClientResponseException ok = WebClientResponseException.create(200, "OK", null, null, null);
        cases.add(new RuntimeException(
                "200 OK from POST https://api.deepseek.com/chat/completions, "
                        + "but response failed with cause: java.io.EOFException: EOF reached while reading",
                ok));
        // 限流：SseException 文案 / 429
        cases.add(new RuntimeException("200: Upstream rate limit exceeded, please retry later"));
        cases.add(new RuntimeException("429 Too Many Requests: RATE LIMIT hit"));
        // 网关 5xx
        WebClientResponseException badGateway = WebClientResponseException.create(502, "Bad Gateway", null, null, null);
        cases.add(new RuntimeException("upstream failed", badGateway));
        return cases;
    }

    /** 红线否决分支：4xx 确定态 / 取消（嵌套瞬态）/ 中断 / 业务异常。 */
    private static List<Throwable> redLineCases() {
        List<Throwable> cases = new ArrayList<>();
        WebClientResponseException unauthorized = WebClientResponseException.create(401, "Unauthorized", null, null, null);
        cases.add(new RuntimeException("auth failed", unauthorized));
        WebClientResponseException forbidden = WebClientResponseException.create(403, "Forbidden", null, null, null);
        cases.add(new RuntimeException("forbidden", forbidden));
        // 取消优先：链上同时挂着瞬态解析失败也绝不重试（Esc 要立即退出）
        FakeInvalidDataException parse = new FakeInvalidDataException();
        RuntimeException cancelled = new RuntimeException("outer", new CancellationException());
        cancelled.getCause().initCause(parse);
        cases.add(cancelled);
        cases.add(new RuntimeException("outer", new InterruptedException("sleep interrupted")));
        cases.add(new IllegalStateException("bad api key"));
        return cases;
    }

    /** 覆盖 RetryingChatModel.shouldRetry 的全部判据分支。 */
    private static List<Throwable> representativeThrowables() {
        List<Throwable> all = new ArrayList<>(transientCases());
        all.addAll(redLineCases());
        return all;
    }

    /** 等价性【比对式，非抄写式】：委托不被解开 + 免双份期望值维护。 */
    @Test
    void shouldRetryDelegatesToRetryPolicyForEveryKnownCase() {
        for (Throwable t : representativeThrowables()) {
            assertEquals(RetryingChatModel.shouldRetry(t), RetryPolicy.shouldRetry(t),
                    "RetryingChatModel.shouldRetry 必须与 RetryPolicy.shouldRetry 一致，t=" + t);
        }
    }

    /** 代表性集合的语义锚：瞬态命中（防比对式两边同为 false 的假绿）。 */
    @Test
    void representativeTransientCasesActuallyRetry() {
        for (Throwable t : transientCases()) {
            assertTrue(RetryPolicy.shouldRetry(t), "应为瞬态可重试，t=" + t);
        }
    }

    /** 代表性集合的语义锚：红线否决（4xx/取消/中断/业务异常绝不重试）。 */
    @Test
    void representativeRedLinesNeverRetry() {
        for (Throwable t : redLineCases()) {
            assertFalse(RetryPolicy.shouldRetry(t), "红线不重试，t=" + t);
        }
    }

    // ---- 新增瞬态判据（本任务引入；Task 2 的流式重试依赖）----

    @Test
    void shouldRetryStreamIdleTimeoutException() {
        assertTrue(RetryPolicy.shouldRetry(new StreamIdleTimeoutException("等待模型流数据超时")));
    }

    @Test
    void shouldRetryEmptyStreamException() {
        assertTrue(RetryPolicy.shouldRetry(new EmptyStreamException("空流")));
    }

    /** 包装后仍命中：cause 链遍历内同样生效。 */
    @Test
    void shouldRetryWrappedNewTransientTypes() {
        assertTrue(RetryPolicy.shouldRetry(
                new RuntimeException("stream failed", new StreamIdleTimeoutException("等待模型流数据超时"))));
        assertTrue(RetryPolicy.shouldRetry(
                new RuntimeException("stream failed", new EmptyStreamException("空流"))));
    }

    // ---- 指数退避（attempt 为 1 基尝试序号：第 n 次尝试失败后的退避）----

    @Test
    void backoffSequenceIsExponentialCapped() {
        assertEquals(500, RetryPolicy.backoffMsAfter(1));
        assertEquals(1000, RetryPolicy.backoffMsAfter(2));
        assertEquals(2000, RetryPolicy.backoffMsAfter(3));
        assertEquals(4000, RetryPolicy.backoffMsAfter(4));
        assertEquals(4000, RetryPolicy.backoffMsAfter(99), "封顶后不再增长");
    }

    /** 退避同样委托：两个入口必须逐点一致。 */
    @Test
    void backoffDelegatesToRetryPolicy() {
        for (int attempt = 1; attempt <= 6; attempt++) {
            assertEquals(RetryingChatModel.backoffMsAfter(attempt), RetryPolicy.backoffMsAfter(attempt),
                    "backoffMsAfter(" + attempt + ") 两入口应一致");
        }
    }
}

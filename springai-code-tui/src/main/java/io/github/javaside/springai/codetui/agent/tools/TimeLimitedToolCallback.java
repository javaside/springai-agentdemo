package io.github.javaside.springai.codetui.agent.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 给<b>没有自带超时</b>的工具兜一层总超时。
 *
 * <p><b>为什么需要</b>：库版 {@code BraveWebSearchTool} 内部自建 RestClient 且不暴露 requestFactory，
 * 无法在 HTTP 层设超时——Spring 默认走 Apache HttpClient，响应超时为 null 即无限等。
 *
 * <p><b>已知代价，非疏漏</b>：底层阻塞 read 不保证响应中断，{@code cancel(true)} 之后工作线程可能滞留到
 * 请求自行结束。故线程池用 <b>daemon</b> 线程，保证滞留线程不阻止 JVM 退出。这是次优解——比无限等好，
 * 但不如在 HTTP 层设超时干净。
 *
 * <p><b>不要套在博查工具上</b>：那个已在 HTTP 层设了 connect 10s / read 20s，再包一层只会让同一个失败
 * 出现两种措辞、且难判断哪一层先触发。
 *
 * <p><b>ThreadLocal 不跨线程</b>：委托在线程池线程上执行，故本装饰器<b>内层</b>的工具读不到
 * {@link ToolEventCallback#currentTurnId()} 这类 ThreadLocal。当前只用于 Brave（不依赖它），
 * 若将来要套到依赖 ThreadLocal 的工具上，须先解决传递问题。
 */
public final class TimeLimitedToolCallback implements ToolCallback {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "tool-timeout");
        t.setDaemon(true);
        return t;
    });

    private final ToolCallback delegate;
    private final Duration timeout;

    public TimeLimitedToolCallback(ToolCallback delegate, Duration timeout) {
        this.delegate = delegate;
        this.timeout = timeout;
    }

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }

    @Override public String call(String toolInput) { return call(toolInput, null); }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        Future<String> future = EXECUTOR.submit(() -> delegate.call(toolInput, toolContext));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                    name + " 超时（超过 " + timeout.toSeconds() + " 秒未返回）", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " 被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;      // 委托自己的异常原样传播，不改语义、不丢定位信息
            }
            throw new IllegalStateException(name + " 执行失败：" + cause, cause);
        }
    }
}

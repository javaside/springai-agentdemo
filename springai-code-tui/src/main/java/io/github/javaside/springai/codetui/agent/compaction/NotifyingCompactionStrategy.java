package io.github.javaside.springai.codetui.agent.compaction;

import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;

/**
 * 装饰任意 {@link CompactionStrategy}，在 {@code compact()} 前后经 {@link AgentListener} 发压缩事件，
 * 让原本静默的压缩对 UI 可见。自动（阈值触发）与手动（/compact）两条路径各包一份，reason 区分。
 *
 * <p>started 只在真正进入策略时发（trigger 已判定要压缩），故不会误报；finished 转发
 * {@link CompactionResult} 的 {@code eventsRemoved()} / {@code tokensEstimatedSaved()}；
 * 失败时发 failed 并<b>重抛</b>（不吞异常，让上层 SessionService 的事务/版本语义照常）。
 */
public final class NotifyingCompactionStrategy implements CompactionStrategy {

    private final CompactionStrategy delegate;
    private final AgentListener listener;
    private final String reason;   // "auto" | "manual"

    public NotifyingCompactionStrategy(CompactionStrategy delegate, AgentListener listener, String reason) {
        this.delegate = delegate;
        this.listener = listener;
        this.reason = reason;
    }

    @Override
    public CompactionResult compact(CompactionRequest request) {
        listener.onCompactionStarted(reason);
        try {
            CompactionResult result = delegate.compact(request);
            listener.onCompactionFinished(result.eventsRemoved(), result.tokensEstimatedSaved());
            return result;
        } catch (RuntimeException e) {
            listener.onCompactionFailed(String.valueOf(e.getMessage()));
            throw e;
        }
    }
}

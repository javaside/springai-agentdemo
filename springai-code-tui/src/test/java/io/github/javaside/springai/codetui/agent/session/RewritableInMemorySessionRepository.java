package io.github.javaside.springai.codetui.agent.session;

import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;

import java.time.Instant;
import java.util.List;

/**
 * 测试侧的「可改写」内存仓库（仅 src/test）。
 *
 * <p><b>为什么需要它</b>：官方 2.1.0 的 {@link InMemorySessionRepository} 收紧为 append-only
 * （无 replaceEvents），而 {@link CodingAgent}／{@code GoalManager} 的修复性改写（取消回合回滚、
 * 裁悬空 tool_calls、插话补历史）经 {@link HistoryRewritingRepository} 走整表替换。旧测试直接拿
 * 社区版 0.5.0 的 InMemory 当 CodingAgent 的仓库参数，迁移后没有官方等价物，故由本夹具补位：
 * 官方方法全部委托内嵌 InMemory，「整表替换」用 delete → save → appendEvents(expectedVersion=0)
 * 重建事件表实现（事件保留原 id，重建后日志全新，不会触发 id 幂等去重）。
 *
 * <p><b>已知偏差（与 FileSessionRepository 的语义差异）</b>：每次替换后 version 重置为 1 而非单调 +1。
 * 仅用例不依赖版本号对账时才可用本夹具；需要 CAS 语义的测试仍应自建桩（先例
 * {@code GoalManagerEvalStoreTest.RecordingRepo} 以 events.size() 充当版本号）。
 */
public class RewritableInMemorySessionRepository implements HistoryRewritingRepository {

    /** 子类（CountingRepo/GatedRepo 等包装桩）需要直接委托/读它，故开放为 protected。 */
    protected final InMemorySessionRepository delegate = InMemorySessionRepository.builder().build();

    @Override
    public Session save(Session session) {
        return delegate.save(session);
    }

    @Override
    public boolean saveIfAbsent(Session session) {
        return delegate.saveIfAbsent(session);
    }

    @Override
    public Session findById(String sessionId) {
        return delegate.findById(sessionId);
    }

    @Override
    public List<Session> findByMetadata(String name, String value) {
        return delegate.findByMetadata(name, value);
    }

    @Override
    public void delete(String sessionId) {
        delegate.delete(sessionId);
    }

    @Override
    public int deleteExpiredSessions(Instant before) {
        return delegate.deleteExpiredSessions(before);
    }

    @Override
    public void appendEvent(SessionEvent event) {
        delegate.appendEvent(event);
    }

    @Override
    public boolean appendEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
        return delegate.appendEvents(sessionId, events, expectedVersion);
    }

    @Override
    public long getEventVersion(String sessionId) {
        return delegate.getEventVersion(sessionId);
    }

    @Override
    public List<SessionEvent> findEvents(String sessionId, org.springframework.ai.session.EventFilter filter) {
        return delegate.findEvents(sessionId, filter);
    }

    @Override
    public void replaceEvents(String sessionId, List<SessionEvent> events) {
        Session session = delegate.findById(sessionId);
        if (session == null) {
            return;   // 会话不存在无从替换：保持与「只对已存在会话改写」的调用方契约一致
        }
        delegate.delete(sessionId);
        delegate.save(session);
        if (!events.isEmpty()) {
            delegate.appendEvents(sessionId, List.copyOf(events), 0L);
        }
    }

    @Override
    public boolean replaceEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
        if (delegate.getEventVersion(sessionId) != expectedVersion) {
            return false;
        }
        replaceEvents(sessionId, events);
        return true;
    }
}

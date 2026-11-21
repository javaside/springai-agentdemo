package io.github.javaside.springai.codetui.agent.session;

import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;

import java.util.List;

/**
 * 本项目自有的「历史改写」扩展：在官方 {@link SessionRepository} 之上保留全量替换事件日志的能力。
 *
 * <p><b>为什么需要它</b>：spring-ai 2.1.0 官方 session 模块的压缩改为 append-only——
 * {@code DefaultSessionService.compact} 只追加 boundary + summary 事件，靠
 * {@code EventFilter.active()} 视图遮蔽被覆盖的历史，官方接口不再提供任何「改写历史」的入口。
 * 但本项目有一组<b>修复性改写</b>没有 append 语义可用：取消回合回滚半截历史（CodingAgent）、
 * 裁悬空 tool_calls / 外部化媒体 / 插话补历史（CodingAgent 会话净化）、goal 标记 fold（GoalManager
 * 的读-改-写 CAS）。这些操作必须整体替换事件列表，故以自有接口形态保留社区版 0.5.0 的两个方法，
 * 由 {@link FileSessionRepository} 实现。
 *
 * <p><b>约束</b>：实现类必须自持单调 version（替换成功 version+1），CAS 变体以
 * {@code expectedVersion} 与 {@link #getEventVersion(String)} 对账；调用方职责见各使用点注释。
 */
public interface HistoryRewritingRepository extends SessionRepository {

    /** 全量替换会话事件日志（无条件覆盖写）。 */
    void replaceEvents(String sessionId, List<SessionEvent> events);

    /** CAS 变体：仅当当前版本等于 {@code expectedVersion} 时替换，返回是否命中。 */
    boolean replaceEvents(String sessionId, List<SessionEvent> events, long expectedVersion);
}

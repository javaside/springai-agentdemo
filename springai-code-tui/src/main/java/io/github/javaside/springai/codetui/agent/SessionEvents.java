package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话事件的纯逻辑工具（无状态）。供 {@link CodingAgent}（中断裁剪）与 {@link FileSessionRepository}（加载裁剪）共用，
 * 避免仓库反向依赖 CodingAgent。
 */
final class SessionEvents {

    private SessionEvents() {
    }

    /**
     * 最长干净前缀长度：扫到某处时，之前每个 {@code assistant(tool_calls)} 的 id 都已被后续 tool 结果覆盖
     * （pending 为空）——该处即可安全截断。按 id <b>集合</b>配平：一条 {@link ToolResponseMessage} 可含多个
     * 结果，一次多 tool_call 也可由多条结果覆盖。正常完成的回合以「无 tool_calls 的助手终答」结尾，pending 恒空，
     * 返回等于 {@code events.size()}（裁剪成 no-op）。
     */
    static int cleanPrefixLength(List<SessionEvent> events) {
        Set<String> pending = new HashSet<>();
        int cleanLen = 0;
        for (int i = 0; i < events.size(); i++) {
            Message m = events.get(i).getMessage();
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) pending.add(tc.id());
            } else if (m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) pending.remove(tr.id());
            }
            if (pending.isEmpty()) cleanLen = i + 1;   // 到此 tool_calls 全部配平 = 可安全截断处
        }
        return cleanLen;
    }

    /**
     * 把事件序列净化成 OpenAI/DeepSeek 可接受的合法对话：既裁掉尾部<b>悬空 tool_calls</b>（assistant 发了调用但没结果），
     * 也丢掉<b>孤儿 tool 结果</b>（{@code role:tool} 消息的 id 在其之前并无对应的 assistant {@code tool_calls}）。
     *
     * <p>为什么需要后者：{@link #cleanPrefixLength} 只按「pending 是否清空」找可截断点，对「id 从未入过 pending」的
     * 孤儿 tool 结果是 no-op（remove 不存在的 id 什么也不做），于是孤儿会被原样保留。而 API 要求每条 tool 消息都必须
     * 紧跟一个含匹配 {@code tool_call_id} 的 assistant 消息，否则 {@code 400 Bad Request}。恢复会话（{@code -c}）时若历史里
     * 残留这种孤儿（曾观测到 AskUserQuestion 被中断留下的孤儿结果），首个请求就会 400——本方法在加载时把它清掉。
     *
     * <p>做法：三遍纯函数流水线，每遍无改动时都返回<b>同一引用</b>，故整体在无需改动时亦返回入参同引用：
     * <ol>
     *   <li>{@link #removeOrphanToolResults}：丢孤儿 tool 结果 / 重建部分孤儿；</li>
     *   <li>{@link #collapseConsecutiveSameRole}：折叠连续同角色（user↔user、普通 assistant↔assistant）——修复
     *       「gpt 回合 {@code after()} 抛 No session ID 致 assistant 不落盘，堆出连续 USER」被 DeepSeek 拒收的 400；
     *       排在删孤儿<b>之后</b>，因删掉夹在两 user 间的孤儿 tool 会新暴露出连续 user；</li>
     *   <li>{@link #trimToCleanPrefix}：{@link #cleanPrefixLength} 裁尾部悬空 tool_calls（折叠只动纯文本、不碰 tool 配对，故不影响此步）。</li>
     * </ol>
     */
    static List<SessionEvent> sanitize(List<SessionEvent> events) {
        List<SessionEvent> a = removeOrphanToolResults(events);
        List<SessionEvent> b = collapseConsecutiveSameRole(a);
        return trimToCleanPrefix(b);
    }

    /**
     * 一遍扫描维护「未被覆盖的 tool_call id 集合 open」。assistant(tool_calls) 把 id 记入 open；每条 tool 消息
     * 只保留 id 命中 open 的结果（并从 open 移除），命中为空则整条丢弃、部分命中则重建为只含命中项。无改动返回同一引用。
     */
    static List<SessionEvent> removeOrphanToolResults(List<SessionEvent> events) {
        List<SessionEvent> filtered = new ArrayList<>(events.size());
        Set<String> open = new HashSet<>();
        boolean changed = false;
        for (SessionEvent ev : events) {
            Message m = ev.getMessage();
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) open.add(tc.id());
                filtered.add(ev);
            } else if (m instanceof ToolResponseMessage trm) {
                List<ToolResponseMessage.ToolResponse> kept = new ArrayList<>();
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    if (open.remove(tr.id())) kept.add(tr);   // 命中一个待覆盖的 tool_call：保留；否则是孤儿，丢弃
                }
                if (kept.isEmpty()) {
                    changed = true;                            // 整条都是孤儿 → 丢弃
                } else if (kept.size() == trm.getResponses().size()) {
                    filtered.add(ev);                          // 全部命中 → 原样保留
                } else {
                    filtered.add(withResponses(ev, kept));     // 部分命中 → 重建为只含命中项
                    changed = true;
                }
            } else {
                filtered.add(ev);
            }
        }
        return changed ? filtered : events;
    }

    /** 尾部悬空 tool_calls 裁剪：截到最长干净前缀。无需裁剪返回同一引用。 */
    static List<SessionEvent> trimToCleanPrefix(List<SessionEvent> events) {
        int clean = cleanPrefixLength(events);
        return clean < events.size() ? new ArrayList<>(events.subList(0, clean)) : events;
    }

    /**
     * 折叠相邻的同角色消息，使会话满足「user/assistant 交替」（严格厂商如 DeepSeek 拒收连续同角色，返回 400）。
     * 只折叠两类：相邻 {@link UserMessage}，以及相邻<b>普通</b> {@link AssistantMessage}（{@code !hasToolCalls()}）；
     * 文本用 {@code "\n\n"} 拼接、<b>不丢内容</b>，保留<b>首条</b>事件的信封（id/时间戳/branch/metadata）以稳定时序。
     *
     * <p>类型判定即安全边界：{@link ToolResponseMessage} 与带 {@code tool_calls} 的 assistant 永不参与合并，
     * 故 tool_call / tool 结果的配对结构（{@link #cleanPrefixLength} / {@link #removeOrphanToolResults} 依赖）分毫不动；
     * {@link org.springframework.ai.chat.messages.SystemMessage} 亦不合并。无改动返回同一引用。
     */
    static List<SessionEvent> collapseConsecutiveSameRole(List<SessionEvent> events) {
        List<SessionEvent> out = new ArrayList<>(events.size());
        boolean changed = false;
        for (SessionEvent ev : events) {
            if (!out.isEmpty()) {
                Message prev = out.get(out.size() - 1).getMessage();
                Message cur = ev.getMessage();
                if (mergeableUsers(prev, cur) || mergeablePlainAssistants(prev, cur)) {
                    out.set(out.size() - 1, mergeText(out.get(out.size() - 1), ev));   // 保留首条信封
                    changed = true;
                    continue;
                }
            }
            out.add(ev);
        }
        return changed ? out : events;
    }

    private static boolean mergeableUsers(Message prev, Message cur) {
        return prev instanceof UserMessage && cur instanceof UserMessage;
    }

    private static boolean mergeablePlainAssistants(Message prev, Message cur) {
        return prev instanceof AssistantMessage pa && !pa.hasToolCalls()
                && cur instanceof AssistantMessage ca && !ca.hasToolCalls();
    }

    /** 把 cur 的文本并入 prevEv，重建同角色消息（user→UserMessage / 普通 assistant→AssistantMessage），保留 prevEv 信封。 */
    private static SessionEvent mergeText(SessionEvent prevEv, SessionEvent curEv) {
        String merged = concat(prevEv.getMessage().getText(), curEv.getMessage().getText());
        Message m = prevEv.getMessage() instanceof UserMessage
                ? new UserMessage(merged)
                : new AssistantMessage(merged);   // 由 mergeablePlainAssistants 保证：此处 assistant 必无 tool_calls
        SessionEvent.Builder b = SessionEvent.builder()
                .id(prevEv.getId())
                .sessionId(prevEv.getSessionId())
                .timestamp(prevEv.getTimestamp())
                .message(m)
                .branch(prevEv.getBranch());
        if (prevEv.getMetadata() != null) {
            b.metadata(prevEv.getMetadata());
        }
        return b.build();
    }

    /** 用 {@code "\n\n"} 拼接两段文本；任一为空/空白则返回另一段（避免多余分隔符与空内容）。 */
    private static String concat(String a, String b) {
        boolean ae = a == null || a.isBlank();
        boolean be = b == null || b.isBlank();
        if (ae) return b == null ? "" : b;
        if (be) return a;
        return a + "\n\n" + b;
    }

    /** 用给定的（子集）tool 结果重建一条 tool 事件，保留其余元数据（id/sessionId/timestamp/branch/metadata）。 */
    private static SessionEvent withResponses(SessionEvent ev, List<ToolResponseMessage.ToolResponse> kept) {
        SessionEvent.Builder b = SessionEvent.builder()
                .id(ev.getId())
                .sessionId(ev.getSessionId())
                .timestamp(ev.getTimestamp())
                .message(ToolResponseMessage.builder().responses(kept).build())
                .branch(ev.getBranch());
        if (ev.getMetadata() != null) {
            b.metadata(ev.getMetadata());
        }
        return b.build();
    }
}

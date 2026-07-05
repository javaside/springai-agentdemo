package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.session.SessionEvent;

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
}

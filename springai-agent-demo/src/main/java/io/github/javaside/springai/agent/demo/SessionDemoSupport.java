package io.github.javaside.springai.agent.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;

import java.util.List;

/**
 * 三个会话示例共用的两段接线：建一个内存会话服务、带会话 advisors 发一轮请求。
 *
 * <p>advisors 一律按请求传入，不挂到共享 ChatClient 的 defaultAdvisors 上：会话记忆 advisor 要求每个请求
 * 都带会话 id，漏了会直接抛 IllegalStateException，挂成默认会让其它示例全部失败。
 */
final class SessionDemoSupport {

    private SessionDemoSupport() {
    }

    /** 内存仓库的会话服务：进程退出即丢（落库见 springai-boot-demo 的会话落库示例）。 */
    static SessionService newSessions() {
        return DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build())
                .build();
    }

    /**
     * 带会话 id 发一轮请求，返回回答文本。
     *
     * @param advisors 本轮的会话 advisors（{@link SessionAdvisors#builder} 的产物）
     * @param tools    本轮注册的工具对象，可以为空
     */
    static String ask(ChatClient chatClient, List<Advisor> advisors, String sessionId, String userText,
            Object... tools) {
        ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                .user(userText)
                .advisors(advisors)
                .advisors(SessionAdvisors.session(sessionId));
        if (tools.length > 0) {
            request = request.tools(tools);
        }
        // CallResponseSpec 的每个取值方法都会重走一遍 advisor 链（链是有状态的）：只取一次，存成变量
        return request.call().content();
    }

}

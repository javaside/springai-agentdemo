package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** clearContext：换一个新的 sessionId（volatile 写），不依赖 chatClient/session/estimator，故全传 null。 */
class CodingAgentClearContextTest {

    @Test
    void clearContext_swapsSessionId_toNewNonEmptyValue() {
        CodingAgent agent = new CodingAgent(null, null, "old-session", new AtomicLong(), null, null, null);
        assertEquals("old-session", agent.sessionId(), "初始 id");

        agent.clearContext();

        String after = agent.sessionId();
        assertNotEquals("old-session", after, "clear 后应换新 id");
        assertNotEquals(null, after, "新 id 非空");
    }
}

package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeepSeekThinkingBodyCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] BASE_REQUEST = """
            {"model":"deepseek-v4-pro","messages":[{"role":"assistant","content":"answer","reasoning_content":"chain"}],"tools":[{"type":"function"}]}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void defaultIsByteForByteUnchanged() {
        assertArrayEquals(BASE_REQUEST,
                DeepSeekThinkingBodyCodec.decorate(BASE_REQUEST, ThinkingConfig.defaults()));
    }

    @Test
    void enabledAddsThinkingAndEffortWithoutChangingMessages() throws Exception {
        var root = MAPPER.readTree(DeepSeekThinkingBodyCodec.decorate(BASE_REQUEST,
                ThinkingConfig.enabledEffort("max")));
        assertEquals("enabled", root.path("thinking").path("type").stringValue());
        assertEquals("max", root.path("reasoning_effort").stringValue());
        assertEquals(1, root.path("messages").size());
        assertEquals("chain", root.path("messages").get(0).path("reasoning_content").stringValue());
        assertEquals(1, root.path("tools").size());
    }

    @Test
    void disabledAddsOnlyThinking() throws Exception {
        var root = MAPPER.readTree(DeepSeekThinkingBodyCodec.decorate(BASE_REQUEST, ThinkingConfig.disabled()));
        assertEquals("disabled", root.path("thinking").path("type").stringValue());
        assertEquals(false, root.has("reasoning_effort"));
    }
}

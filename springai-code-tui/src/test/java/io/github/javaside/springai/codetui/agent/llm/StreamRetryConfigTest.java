package io.github.javaside.springai.codetui.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig.StreamRetryMode.ALL;
import static io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig.StreamRetryMode.L1_ONLY;
import static io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig.StreamRetryMode.L2_ONLY;
import static io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig.StreamRetryMode.OFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamRetryConfigTest {

    @Test
    void from_defaultsToAllWhenUnsetOrBlank() {
        assertEquals(ALL, StreamRetryConfig.from(name -> null).mode());
        assertEquals(ALL, StreamRetryConfig.from(name -> "  ").mode());
    }

    @Test
    void from_mapsSupportedValues() {
        Map<String, StreamRetryConfig.StreamRetryMode> cases = Map.of(
                "all", ALL,
                "l1", L1_ONLY,
                "l2", L2_ONLY,
                "off", OFF);

        cases.forEach((raw, expected) ->
                assertEquals(expected, StreamRetryConfig.from(name -> raw).mode()));
    }

    @Test
    void from_trimsAndIgnoresCase() {
        assertEquals(OFF, StreamRetryConfig.from(name -> "  OFF  ").mode());
    }

    @Test
    void from_fallsBackToAllForInvalidValue() {
        assertEquals(ALL, StreamRetryConfig.from(name -> "garbage").mode());
    }

    @Test
    void from_readsStreamRetryEnvironmentKey() {
        assertEquals(L1_ONLY, StreamRetryConfig.from(name -> {
            assertEquals("CODETUI_STREAM_RETRY", name);
            return "l1";
        }).mode());
    }

    @Test
    void enabledFlagsFollowModeMatrix() {
        assertEnabled(new StreamRetryConfig(ALL), true, true);
        assertEnabled(new StreamRetryConfig(L1_ONLY), true, false);
        assertEnabled(new StreamRetryConfig(L2_ONLY), false, true);
        assertEnabled(new StreamRetryConfig(OFF), false, false);
    }

    private static void assertEnabled(StreamRetryConfig config, boolean l1Enabled, boolean l2Enabled) {
        if (l1Enabled) {
            assertTrue(config.l1Enabled());
        } else {
            assertFalse(config.l1Enabled());
        }
        if (l2Enabled) {
            assertTrue(config.l2Enabled());
        } else {
            assertFalse(config.l2Enabled());
        }
    }
}

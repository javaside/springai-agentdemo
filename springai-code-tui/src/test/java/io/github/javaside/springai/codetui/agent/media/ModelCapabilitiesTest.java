package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelCapabilitiesTest {
    @Test
    void textOnly_bothFalse() {
        ModelCapabilities c = ModelCapabilities.TEXT_ONLY;
        assertFalse(c.supportsImageInput());
        assertFalse(c.supportsVideoInput());
    }

    @Test
    void carriesFlags() {
        ModelCapabilities c = new ModelCapabilities(true, false);
        assertTrue(c.supportsImageInput());
        assertFalse(c.supportsVideoInput());
    }
}

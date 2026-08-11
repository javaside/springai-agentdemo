package dev.tamboui.inline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineDisplayBaselineTest {

    @Test
    void patchClassKeepsTamboui040Surface() throws Exception {
        assertNotNull(InlineDisplay.class.getMethod("render", java.util.function.BiConsumer.class,
                int.class, int.class, int.class));
        assertNotNull(InlineDisplay.class.getMethod("println", String.class));
        assertNotNull(InlineDisplay.class.getMethod("println", dev.tamboui.text.Text.class));
        assertNotNull(InlineDisplay.class.getMethod("release"));
    }

    @Test
    void classIsLoadedFromPatchArtifact() {
        String source = InlineDisplay.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(source.contains("springai-tamboui-inline-patch"), source);
    }
}

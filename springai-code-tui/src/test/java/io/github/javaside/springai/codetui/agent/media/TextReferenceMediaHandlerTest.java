// TextReferenceMediaHandlerTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TextReferenceMediaHandlerTest {
    private static MediaArtifact img() {
        return new MediaArtifact("b".repeat(64), Path.of("/x/b.png"),
                ".codetui/artifacts/b.png", "image/png", "image/png",
                MediaKind.IMAGE, 100L, 8, 8, null, ArtifactSource.MATERIALIZED, true);
    }

    @Test
    void canDeliver_alwaysFalse_thisIteration() {
        ToolResultMediaHandler h = new TextReferenceMediaHandler();
        assertFalse(h.canDeliver(MediaKind.IMAGE, new ModelCapabilities(true, true)));  // 无注入器 → 恒 false
        assertFalse(h.canDeliver(MediaKind.IMAGE, ModelCapabilities.TEXT_ONLY));
    }

    @Test
    void represent_returnsReferenceOnly() {
        String out = new TextReferenceMediaHandler().represent(img(), ModelCapabilities.TEXT_ONLY);
        assertTrue(FileReference.isReference(out));
        assertTrue(out.contains("delivery: reference_only"));
    }
}

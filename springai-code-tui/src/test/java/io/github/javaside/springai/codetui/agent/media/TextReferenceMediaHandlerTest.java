// TextReferenceMediaHandlerTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TextReferenceMediaHandlerTest {
    private static MediaArtifact img() {
        return new MediaArtifact("b".repeat(64), Path.of("/x/b.png"),
                ".codetui/artifacts/b.png", "image/png", "image/png",
                MediaKind.IMAGE, 100L, 8, 8, null, ArtifactSource.MATERIALIZED, true, "b.png");
    }

    @Test
    void represent_returnsReferenceOnly() {
        String out = new TextReferenceMediaHandler().represent(img(), ModelCapabilities.TEXT_ONLY);
        assertTrue(FileReference.isReference(out));
        assertTrue(out.contains("delivery: reference_only"));
    }

    /** 模型有视觉能力时，引用要写 not_in_view——告诉它「Read 一次就能看」。 */
    @Test
    void visionCapableModelGetsNotInView() {
        ToolResultMediaHandler h = new TextReferenceMediaHandler();
        ModelCapabilities caps = new ModelCapabilities(true, false);
        assertTrue(h.canDeliver(MediaKind.IMAGE, caps));
        assertTrue(h.represent(img(), caps).contains("delivery: not_in_view"));
    }

    /** 无视觉能力时写 reference_only——阻止模型为一张它看不见的图白 Read 一次。 */
    @Test
    void textOnlyModelGetsReferenceOnly() {
        ToolResultMediaHandler h = new TextReferenceMediaHandler();
        ModelCapabilities caps = ModelCapabilities.TEXT_ONLY;
        assertFalse(h.canDeliver(MediaKind.IMAGE, caps));
        assertTrue(h.represent(img(), caps).contains("delivery: reference_only"));
    }

    /** 视频本期不兑现——有视觉能力也不投递视频。 */
    @Test
    void videoIsNeverDeliverable() {
        ToolResultMediaHandler h = new TextReferenceMediaHandler();
        assertFalse(h.canDeliver(MediaKind.VIDEO, new ModelCapabilities(true, true)));
    }
}

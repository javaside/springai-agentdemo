// MediaArtifactTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MediaArtifactTest {
    @Test
    void shortIdIsFirst16OfSha() {
        MediaArtifact a = new MediaArtifact(
                "a".repeat(64), Path.of("/x/.codetui/artifacts/" + "a".repeat(64) + ".png"),
                ".codetui/artifacts/" + "a".repeat(64) + ".png",
                "image/png", "image/png", MediaKind.IMAGE, 100L, 12, 34, null,
                ArtifactSource.MATERIALIZED, true, "a.png");
        assertEquals("a".repeat(16), a.shortId());
    }
}

// FileReferenceTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FileReferenceTest {
    private static MediaArtifact img() {
        return new MediaArtifact("a".repeat(64), Path.of("/x/a.png"),
                ".codetui/artifacts/" + "a".repeat(64) + ".png",
                "image/png", "image/png", MediaKind.IMAGE, 519531L, 2400, 1632, null,
                ArtifactSource.MATERIALIZED, true);
    }

    @Test
    void renders_hasMarkersAndFields_noBase64() {
        String ref = FileReference.render(img(), "reference_only", "当前模型无视觉能力，未发送图像内容");
        assertTrue(ref.startsWith("[file reference]"));
        assertTrue(ref.contains("[/file reference]"));
        assertTrue(ref.contains("kind: image"));
        assertTrue(ref.contains("mime_type: image/png"));
        assertTrue(ref.contains("size_bytes: 519531"));
        assertTrue(ref.contains("dimensions: 2400x1632"));
        assertTrue(ref.contains(".codetui/artifacts/"));
        assertTrue(ref.contains("delivery: reference_only"));
    }

    @Test
    void isReference_detectsMarker() {
        assertTrue(FileReference.isReference("blah\n[file reference]\n...\n[/file reference]"));
        assertFalse(FileReference.isReference("just some normal tool output"));
    }
}

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
                ArtifactSource.MATERIALIZED, true, "a.png");
    }

    private static MediaArtifact artifact() {
        return new MediaArtifact(
                "b7e2f1".repeat(10) + "abcd", Path.of("/p/.codetui/artifacts/x.png"),
                ".codetui/artifacts/x.png", "image/png", null, MediaKind.IMAGE,
                1234L, 1440, 900, null, ArtifactSource.MATERIALIZED, true,
                "cart.png");
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

    @Test
    void renderCarriesOriginalName() {
        String s = FileReference.render(artifact(), FileReference.DELIVERY_NOT_IN_VIEW, "r");
        assertTrue(s.contains("name: cart.png"), "引用块缺少原始文件名：\n" + s);
    }

    /** 五种 delivery 态各自可区分——模型据此决定要不要 Read。 */
    @Test
    void deliveryStatesAreDistinct() {
        assertEquals("delivered", FileReference.DELIVERY_DELIVERED);
        assertEquals("reference_only", FileReference.DELIVERY_REFERENCE_ONLY);
        assertEquals("not_in_view", FileReference.DELIVERY_NOT_IN_VIEW);
        assertEquals("budget_exceeded", FileReference.DELIVERY_BUDGET_EXCEEDED);
        assertEquals("turn_budget_exhausted", FileReference.DELIVERY_TURN_EXHAUSTED);
    }

    /** 兑现后必须能把 delivery 行就地改写为 delivered——否则模型同时收到「你看不见」和那张图。 */
    @Test
    void deliveryLineCanBeRewrittenInPlace() {
        String before = FileReference.render(artifact(), FileReference.DELIVERY_NOT_IN_VIEW, "r");
        String after = FileReference.withDelivery(before, FileReference.DELIVERY_DELIVERED);
        assertTrue(after.contains("delivery: delivered"), "没改成 delivered：\n" + after);
        assertFalse(after.contains("delivery: not_in_view"), "旧状态残留：\n" + after);
    }

    /** 改写只动 delivery 行，其余逐字不变——引用块里的 path/sha 是模型的寻址依据，动一个字都不行。 */
    @Test
    void rewriteTouchesOnlyTheDeliveryLine() {
        String before = FileReference.render(artifact(), FileReference.DELIVERY_NOT_IN_VIEW, "r");
        String after = FileReference.withDelivery(before, FileReference.DELIVERY_DELIVERED);
        assertEquals(before.lines().count(), after.lines().count(), "行数变了");
        assertTrue(after.contains("path: .codetui/artifacts/x.png"), "path 被改动了");
        assertTrue(after.contains("name: cart.png"), "name 被改动了");
    }
}

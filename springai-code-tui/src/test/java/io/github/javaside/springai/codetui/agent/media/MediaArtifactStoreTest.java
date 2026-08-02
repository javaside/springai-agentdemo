// MediaArtifactStoreTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaArtifactStoreTest {
    private static byte[] png() {
        byte[] b = new byte[33];
        int[] sig = {0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
        for (int i = 0; i < 8; i++) b[i] = (byte) sig[i];
        b[16]=0;b[17]=0;b[18]=0;b[19]=10; b[20]=0;b[21]=0;b[22]=0;b[23]=20; // 10x20
        return b;
    }

    @Test
    void put_writesContentAddressedFile_magicOverridesDeclared(@TempDir Path root) throws Exception {
        MediaArtifactStore store = new MediaArtifactStore(root.resolve(".codetui/artifacts"), root);
        // 声明成 jpeg，但字节是 png → 以 magic 为准
        MediaArtifact a = store.put(png(), "image/jpeg", "t.png");

        assertEquals("image/png", a.mimeType());
        assertEquals("image/jpeg", a.declaredMimeType());
        assertEquals(MediaKind.IMAGE, a.kind());
        assertEquals(10, a.width());
        assertEquals(20, a.height());
        assertTrue(a.ownedByStore());
        assertEquals(ArtifactSource.MATERIALIZED, a.source());
        assertTrue(Files.exists(a.path()), "artifact 文件应落盘");
        assertTrue(a.path().getFileName().toString().equals(a.sha() + ".png"), "文件名=完整 sha.ext");
        assertTrue(a.relativePath().startsWith(".codetui/artifacts/"));
    }

    @Test
    void put_idempotent_sameContentSameFile(@TempDir Path root) {
        MediaArtifactStore store = new MediaArtifactStore(root.resolve(".codetui/artifacts"), root);
        MediaArtifact a = store.put(png(), "image/png", "t.png");
        MediaArtifact b = store.put(png(), "image/png", "t.png");
        assertEquals(a.sha(), b.sha());
        assertEquals(a.path(), b.path());
    }

    @Test
    void put_lazilyCreatesDir(@TempDir Path root) {
        Path dir = root.resolve(".codetui/artifacts");
        assertFalse(Files.exists(dir));
        new MediaArtifactStore(dir, root).put(png(), "image/png", "t.png");
        assertTrue(Files.exists(dir));
    }
}

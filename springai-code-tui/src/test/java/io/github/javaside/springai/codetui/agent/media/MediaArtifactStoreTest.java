// MediaArtifactStoreTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MediaArtifactStoreTest {
    private static byte[] png() {
        byte[] b = new byte[33];
        int[] sig = {0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
        for (int i = 0; i < 8; i++) b[i] = (byte) sig[i];
        b[16]=0;b[17]=0;b[18]=0;b[19]=10; b[20]=0;b[21]=0;b[22]=0;b[23]=20; // 10x20
        return b;
    }

    /** 另一张 PNG：只改宽高，字节不同 → sha 不同，用来验证 latest 链会被重建到新图上。 */
    private static byte[] otherPng() {
        byte[] b = png();
        b[19] = 30; b[23] = 40;   // 30x40
        return b;
    }

    /** 明确不是图片的字节：带 NUL 与高位字节，Tika 认不出 magic → application/octet-stream → BINARY。 */
    private static byte[] binaryBlob() {
        byte[] b = new byte[64];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (i % 2 == 0 ? 0x00 : 0xE7);
        return b;
    }

    /** 本机/本文件系统能不能建符号链接。建不出来（CI、Windows 无特权）就跳过相关用例，
     *  而不是让断言恒真变成一条假测试。 */
    private static boolean symlinksSupported(Path dir) {
        Path probe = dir.resolve(".symlink-probe");
        try {
            Files.createDirectories(dir);
            Files.createSymbolicLink(probe, dir.resolve("nonexistent-target"));
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
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

    /** 图片落盘后要有一条 latest.<ext> 软链——用户能直接 open，不用复制 64 位 sha。 */
    @Test
    void put_image_createsLatestSymlink(@TempDir Path root) throws Exception {
        Path dir = root.resolve(".codetui/artifacts");
        assumeTrue(symlinksSupported(dir), "本文件系统建不了符号链接，跳过");

        MediaArtifact a = new MediaArtifactStore(dir, root).put(png(), "image/png", "t.png");

        Path link = dir.resolve("latest.png");
        // exists 默认跟随链接，用 NOFOLLOW 才是在问「链本身在不在」
        assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "应生成 latest.png");
        assertTrue(Files.isSymbolicLink(link), "latest.png 应是软链而非文件副本");
        assertEquals(a.path().getFileName(), Files.readSymbolicLink(link), "应指向那个 sha 文件");
        assertTrue(Files.isSameFile(link, a.path()));
    }

    /** 再存一张不同的图，latest 必须重建到新图上——只建一次就不动等于永远停在第一张。 */
    @Test
    void put_secondImage_relinksLatestToNewest(@TempDir Path root) throws Exception {
        Path dir = root.resolve(".codetui/artifacts");
        assumeTrue(symlinksSupported(dir), "本文件系统建不了符号链接，跳过");

        MediaArtifactStore store = new MediaArtifactStore(dir, root);
        MediaArtifact first = store.put(png(), "image/png", "a.png");
        MediaArtifact second = store.put(otherPng(), "image/png", "b.png");
        assertNotEquals(first.sha(), second.sha(), "两张图应是不同内容");

        Path link = dir.resolve("latest.png");
        assertEquals(second.path().getFileName(), Files.readSymbolicLink(link), "latest 应指向新的那张");
        assertTrue(Files.exists(first.path()), "旧图本身不该被建链动作删掉");
    }

    /** 非图片不建链：视频/二进制建了也没人会去 open。 */
    @Test
    void put_nonImage_doesNotCreateLatestLink(@TempDir Path root) {
        Path dir = root.resolve(".codetui/artifacts");
        assumeTrue(symlinksSupported(dir), "本文件系统建不了符号链接，跳过");

        MediaArtifact a = new MediaArtifactStore(dir, root)
                .put(binaryBlob(), "application/octet-stream", "blob.bin");
        assertEquals(MediaKind.BINARY, a.kind(), "前提：这段字节应被判成 BINARY");

        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "latest.*")) {
            assertFalse(s.iterator().hasNext(), "非图片不该有任何 latest.* 链");
        } catch (IOException e) {
            fail(e);
        }
    }
}

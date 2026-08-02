package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImagePreparerTest {

    @TempDir Path dir;

    private Path write(String name, String fmt, int w, int h) throws Exception {
        Path p = dir.resolve(name);
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), fmt, p.toFile());
        return p;
    }

    @Test
    void smallPngPassesThroughUnchanged() throws Exception {
        Path p = write("s.png", "png", 800, 600);
        PreparedImage r = new ImagePreparer().prepare(p, "image/png").orElseThrow();
        assertEquals("image/png", r.mimeType());
        assertEquals(800, r.width());
        assertArrayEquals(Files.readAllBytes(p), r.bytes());
    }

    @Test
    void oversizedPngIsScaledToMaxEdge() throws Exception {
        Path p = write("big.png", "png", 3840, 2160);
        PreparedImage r = new ImagePreparer().prepare(p, "image/png").orElseThrow();
        assertEquals(1568, r.width());
        assertEquals(882, r.height());
    }

    /** BMP 各家 API 都不收，但 ImageIO 认得 → 转码为 PNG。 */
    @Test
    void bmpIsTranscodedToPng() throws Exception {
        Path p = write("a.bmp", "bmp", 100, 100);
        PreparedImage r = new ImagePreparer().prepare(p, "image/bmp").orElseThrow();
        assertEquals("image/png", r.mimeType());
    }

    /** WebP：ImageIO 解不了但各家收 → 原样发，不缩。 */
    @Test
    void webpIsSentAsIsWithoutScaling() throws Exception {
        Path p = dir.resolve("a.webp");
        Files.write(p, new byte[]{'R','I','F','F',0,0,0,0,'W','E','B','P'});
        PreparedImage r = new ImagePreparer().prepare(p, "image/webp").orElseThrow();
        assertEquals("image/webp", r.mimeType());
        assertArrayEquals(Files.readAllBytes(p), r.bytes());
    }

    /** HEIC：ImageIO 解不了、各家也不收 → 不兑现。 */
    @Test
    void heicIsNotPrepared() throws Exception {
        Path p = dir.resolve("a.heic");
        Files.write(p, new byte[]{0,0,0,24,'f','t','y','p','h','e','i','c'});
        assertTrue(new ImagePreparer().prepare(p, "image/heic").isEmpty());
    }

    /** OOM 防护：超像素上限的图必须在解码<b>之前</b>被拒。
     *  只断言「返回空」不够——解码完再拒也是空，可那一下 216MB 的 BufferedImage 已经分配出去了，
     *  正是要防的事。所以必须断言解码次数为 0。 */
    @Test
    void hugePixelCountIsRejectedWithoutDecoding() throws Exception {
        Path p = write("huge.png", "png", 9000, 6000);   // 54 MP > 50 MP 上限
        ImagePreparer preparer = new ImagePreparer();
        assertTrue(preparer.prepare(p, "image/png").isEmpty());
        assertEquals(0, preparer.decodeCount(), "超限图不得被解码");
    }

    /** 反面对照：没超限的图确实走了解码——否则上面那条 0 可能只是因为哪条路都没解码。 */
    @Test
    void oversizedButAllowedImageIsActuallyDecoded() throws Exception {
        Path p = write("ok.png", "png", 2000, 1000);
        ImagePreparer preparer = new ImagePreparer();
        assertTrue(preparer.prepare(p, "image/png").isPresent());
        assertEquals(1, preparer.decodeCount());
    }

    @Test
    void missingFileYieldsEmpty() {
        assertTrue(new ImagePreparer().prepare(dir.resolve("nope.png"), "image/png").isEmpty());
    }

    /** 缓存：同一文件重复准备只解码一次（一回合 6 次迭代否则要白干 6 遍）。 */
    @Test
    void repeatedPrepareIsCached() throws Exception {
        Path p = write("c.png", "png", 2000, 1000);
        ImagePreparer preparer = new ImagePreparer();
        PreparedImage a = preparer.prepare(p, "image/png").orElseThrow();
        PreparedImage b = preparer.prepare(p, "image/png").orElseThrow();
        assertSame(a.bytes(), b.bytes());
        assertEquals(1, preparer.decodeCount(), "第二次必须命中缓存，不得重新解码");
    }
}

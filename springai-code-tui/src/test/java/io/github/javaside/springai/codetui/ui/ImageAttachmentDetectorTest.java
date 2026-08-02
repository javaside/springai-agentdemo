package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.media.VisionBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageAttachmentDetectorTest {

    @TempDir Path root;
    @TempDir Path outside;

    private Path png(Path dir, String rel) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent() == null ? dir : p.getParent());
        ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
        return p;
    }

    private List<DetectedImage> detect(String text) {
        return new ImageAttachmentDetector().detect(text, root);
    }

    // ── 基本识别 ────────────────────────────────────────────

    @Test
    void detectsRelativePathAgainstProjectRoot() throws Exception {
        png(root, "docs/bug.png");
        List<DetectedImage> got = detect("看下 docs/bug.png 里这个报错");
        assertEquals(1, got.size());
        assertEquals("bug.png", got.get(0).name());
        assertEquals(120, got.get(0).width());
        assertEquals(80, got.get(0).height());
        assertTrue(got.get(0).insideRoot());
    }

    @Test
    void detectsAbsolutePathOutsideRoot() throws Exception {
        Path p = png(outside, "shot.png");
        List<DetectedImage> got = detect("看下 " + p + " 这个");
        assertEquals(1, got.size());
        assertFalse(got.get(0).insideRoot(), "项目外的图应标记 insideRoot=false");
    }

    // ── 拖拽的转义形态 ───────────────────────────────────────

    /**
     * macOS Terminal.app / iTerm2 拖拽时用反斜杠转义空格。漏了这条，
     * 「从桌面拖中文截图」（默认文件名就带空格）完全失效。
     */
    @Test
    void handlesBackslashEscapedSpaces() throws Exception {
        Path p = png(outside, "截屏 2026-08-02.png");
        String dragged = p.toString().replace(" ", "\\ ");
        assertEquals(1, detect("看下 " + dragged).size(), "反斜杠转义的路径没认出来");
    }

    @Test
    void handlesSingleQuotedPath() throws Exception {
        Path p = png(outside, "my shot.png");
        assertEquals(1, detect("看下 '" + p + "'").size());
    }

    @Test
    void handlesDoubleQuotedPath() throws Exception {
        Path p = png(outside, "my shot.png");
        assertEquals(1, detect("看下 \"" + p + "\"").size());
    }

    @Test
    void handlesChineseFileName() throws Exception {
        png(root, "docs/界面截图.png");
        assertEquals(1, detect("看下 docs/界面截图.png").size());
    }

    @Test
    void detectsMultiplePathsInOneMessage() throws Exception {
        png(root, "a.png");
        png(root, "b.png");
        assertEquals(2, detect("对比 a.png 和 b.png").size());
    }

    // ── 反例：这些都不该附 ───────────────────────────────────

    /** 魔数不是图片就不附——扩展名不可信。 */
    @Test
    void ignoresNonImageDespiteImageExtension() throws Exception {
        Files.writeString(root.resolve("fake.png"), "这其实是文本");
        assertTrue(detect("看下 fake.png").isEmpty(), "按扩展名误判成图片了");
    }

    @Test
    void ignoresMissingFile() {
        assertTrue(detect("看下 docs/nope.png").isEmpty());
    }

    /** 必须独立成词，不能从更长的词里切子串。 */
    @Test
    void ignoresPathEmbeddedInLongerToken() throws Exception {
        png(root, "bug.png");
        assertTrue(detect("见 xxbug.pngyy 这个").isEmpty(), "从更长的词里切出了子串");
    }

    @Test
    void emptyAndNullTextAreSafe() {
        assertTrue(detect("").isEmpty());
        assertTrue(detect(null).isEmpty());
        assertTrue(new ImageAttachmentDetector().detect("x", null).isEmpty());
    }

    // ── 上限 ────────────────────────────────────────────────

    /** 与已有的 VisionBudget.MAX_USER_IMAGES 同一个常量，不另设。 */
    @Test
    void capsAtMaxUserImagesAndReportsOverflow() throws Exception {
        for (int i = 0; i < 5; i++) png(root, "i" + i + ".png");
        ImageAttachmentDetector.Result r = new ImageAttachmentDetector()
                .detectWithOverflow("i0.png i1.png i2.png i3.png i4.png", root);
        assertEquals(VisionBudget.MAX_USER_IMAGES, r.images().size());
        assertEquals(5 - VisionBudget.MAX_USER_IMAGES, r.overflow());
    }

    // ── 缓存 ────────────────────────────────────────────────

    /**
     * 边打边识别会对同一路径反复嗅探；同一实例第二次（路径 + mtime 都没变）不该重新读盘。
     *
     * <p>用「换内容但保持 mtime 不变」来探测：命中缓存则仍报 1 张，重新嗅探则因内容已是文本报 0。
     * <b>不用「删掉文件看还认不认」探测</b>——那要求缓存连 stat 都不做，等于文件删了仍一直附着一张
     * 不存在的图（提交侧再炸），是把 bug 当特性测。
     */
    @Test
    void repeatedDetectionOfSamePathIsCached() throws Exception {
        Path p = png(root, "c.png");
        ImageAttachmentDetector d = new ImageAttachmentDetector();
        assertEquals(1, d.detect("看 c.png", root).size());
        java.nio.file.attribute.FileTime mtime = Files.getLastModifiedTime(p);
        Files.writeString(p, "这其实是文本");
        Files.setLastModifiedTime(p, mtime);
        assertEquals(1, d.detect("看 c.png", root).size(), "第二次没命中缓存（重新读盘了）");
    }

    /** 但 mtime 一变就必须重算——否则用户改完图，附件行还在报旧尺寸。 */
    @Test
    void cacheIsInvalidatedWhenFileChanges() throws Exception {
        Path p = png(root, "d.png");
        ImageAttachmentDetector d = new ImageAttachmentDetector();
        assertEquals(1, d.detect("看 d.png", root).size());
        Files.writeString(p, "这其实是文本");
        Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(p).toMillis() + 5_000));
        assertTrue(d.detect("看 d.png", root).isEmpty(), "mtime 变了还在用缓存");
    }

    /** 文件删掉就不该再附——缓存不能让它一直挂在附件行上。 */
    @Test
    void deletedFileIsNoLongerDetected() throws Exception {
        Path p = png(root, "e.png");
        ImageAttachmentDetector d = new ImageAttachmentDetector();
        assertEquals(1, d.detect("看 e.png", root).size());
        Files.delete(p);
        assertTrue(d.detect("看 e.png", root).isEmpty(), "文件已删除却仍被识别为可附图片");
    }
}

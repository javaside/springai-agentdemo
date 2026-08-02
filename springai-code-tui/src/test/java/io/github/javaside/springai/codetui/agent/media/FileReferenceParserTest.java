package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileReferenceParserTest {

    @TempDir Path root;
    /** 独立的第二棵树，用来构造「文件真实存在但不在 root 内」——只有它能真正压住包含校验。 */
    @TempDir Path outside;

    private String block(String name, String path, String kind) {
        return "[file reference]\n"
                + "id: sha256:abcd1234abcd1234\n"
                + "kind: " + kind + "\n"
                + "mime_type: image/png\n"
                + "size_bytes: 1234\n"
                + "dimensions: 1440x900\n"
                + "name: " + name + "\n"
                + "path: " + path + "\n"
                + "delivery: not_in_view\n"
                + "reason: x\n"
                + "[/file reference]";
    }

    private Path makeFile(String rel) throws Exception {
        Path p = root.resolve(rel);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        return p;
    }

    @Test
    void parsesWellFormedImageReference() throws Exception {
        makeFile("docs/bug.png");
        List<ParsedReference> refs =
                FileReferenceParser.parse("看这个\n" + block("bug.png", "docs/bug.png", "image"), root);
        assertEquals(1, refs.size());
        assertEquals("bug.png", refs.get(0).name());
        assertEquals("image/png", refs.get(0).mimeType());
        assertEquals(root.resolve("docs/bug.png").toRealPath(), refs.get(0).file());
    }

    /** 注入防线：path 逃出 root 的引用一律不认。 */
    @Test
    void rejectsPathEscapingRoot() {
        List<ParsedReference> refs =
                FileReferenceParser.parse(block("x.png", "../../../etc/passwd", "image"), root);
        assertTrue(refs.isEmpty(), "越界路径被接受了，这是注入面");
    }

    /**
     * 注入防线的硬核版：目标文件<b>真实存在</b>，只是不在 root 内。
     *
     * <p>为什么单靠 {@code ../../../etc/passwd} 不够：那条相对路径拼到临时 root 后指向一个
     * 不存在的位置，即使包含校验被拆掉也会因「文件不存在」被挡下——测试会为了错误的理由变绿。
     * 这里用绝对路径 + 真实文件，让唯一能拦住它的只剩包含校验。
     */
    @Test
    void rejectsExistingFileOutsideRoot() throws Exception {
        Path secret = outside.resolve("id_rsa");
        Files.writeString(secret, "PRIVATE KEY");
        assertTrue(FileReferenceParser.parse(
                        block("id_rsa", secret.toAbsolutePath().toString(), "image"), root).isEmpty(),
                "root 外的真实文件被接受了，这是注入面");
    }

    /** 文件不存在（比如已被 artifact GC 删掉）也不认——兑现时会读不到字节。 */
    @Test
    void rejectsMissingFile() {
        List<ParsedReference> refs =
                FileReferenceParser.parse(block("gone.png", "docs/gone.png", "image"), root);
        assertTrue(refs.isEmpty(), "不存在的文件被接受了");
    }

    /** 严格匹配：缺必填字段不做启发式补全。 */
    @Test
    void rejectsIncompleteBlock() {
        String broken = "[file reference]\nkind: image\n[/file reference]";
        assertTrue(FileReferenceParser.parse(broken, root).isEmpty(), "残缺块被接受了");
    }

    /** 非图片 kind 不参与视觉兑现。 */
    @Test
    void ignoresNonImageKinds() throws Exception {
        makeFile("a.bin");
        assertTrue(FileReferenceParser.parse(block("a.bin", "a.bin", "binary"), root).isEmpty());
    }

    /** 下标区间必须精确框住整块，Task 7 要靠它就地改写 delivery 行。 */
    @Test
    void recordsBlockOffsetsForInPlaceRewrite() throws Exception {
        makeFile("docs/bug.png");
        String text = "前缀\n" + block("bug.png", "docs/bug.png", "image") + "\n后缀";
        ParsedReference r = FileReferenceParser.parse(text, root).get(0);
        String slice = text.substring(r.start(), r.end());
        assertTrue(slice.startsWith("[file reference]"), "起点不对：" + slice);
        assertTrue(slice.endsWith("[/file reference]"), "终点不对：" + slice);
    }

    /** 一段文本里多个引用块要全部认出，且顺序与出现顺序一致。 */
    @Test
    void parsesMultipleBlocksInOrder() throws Exception {
        makeFile("a.png");
        makeFile("b.png");
        String text = block("a.png", "a.png", "image") + "\n中间说明\n" + block("b.png", "b.png", "image");
        List<ParsedReference> refs = FileReferenceParser.parse(text, root);
        assertEquals(2, refs.size());
        assertEquals("a.png", refs.get(0).name());
        assertEquals("b.png", refs.get(1).name());
    }

    @Test
    void nullInputsAreSafe() {
        assertTrue(FileReferenceParser.parse(null, root).isEmpty());
        assertTrue(FileReferenceParser.parse("whatever", null).isEmpty());
    }

    /**
     * 文件名注入：Unix 文件名可含换行，而 originalName 原样取自磁盘、render 又把 name
     * 写在 kind 之后——于是一个精心命名的文件能注入一行 kind: image，
     * 靠 HashMap 后写覆盖把非图片伪装成图片。重复字段整块丢弃即堵死这条路。
     */
    @Test
    void rejectsBlockWithDuplicateFieldFromNewlineInName() throws Exception {
        makeFile("evil.bin");
        String injected = "[file reference]\n"
                + "id: sha256:abcd1234abcd1234\n"
                + "kind: binary\n"
                + "mime_type: application/octet-stream\n"
                + "size_bytes: 1234\n"
                + "name: evil\n"
                + "kind: image\n"          // ← 文件名里的换行造出来的注入行
                + "x.bin\n"
                + "path: evil.bin\n"
                + "delivery: not_in_view\n"
                + "reason: x\n"
                + "[/file reference]";
        assertTrue(FileReferenceParser.parse(injected, root).isEmpty(),
                "带重复 kind 的块被接受了——非图片可伪装成图片");
    }

    /**
     * 重复字段一律拒，不限于 kind——取首取末都是猜，两个消费者猜得不一样就是歧义。
     *
     * <p><b>为什么两个重复值都刻意选「合法」的</b>：若把第二条写成 {@code path: /etc/passwd}，
     * 块会被<b>包含校验</b>拦下而不是被重复规则拦下——测试为了错误的理由变绿，把 parseBlock 的
     * 判重整个去掉它照样是绿的（已在变异副本上实测过）。这里两条 path 都指向 root 内真实存在的
     * 文件，而 reason 根本不参与任何校验，于是唯一能拦下它们的只剩重复字段规则。
     */
    @Test
    void rejectsBlockWithAnyDuplicateField() throws Exception {
        makeFile("a.png");
        makeFile("b.png");
        String dupPath = block("a.png", "a.png", "image")
                .replace("delivery: not_in_view", "path: b.png\ndelivery: not_in_view");
        assertTrue(FileReferenceParser.parse(dupPath, root).isEmpty(), "重复 path 被接受了");

        // reason 不参与任何校验，重复它仍须整块丢弃——这才撑得起「任何字段」这句话
        String dupReason = block("a.png", "a.png", "image")
                .replace("reason: x", "reason: x\nreason: y");
        assertTrue(FileReferenceParser.parse(dupReason, root).isEmpty(), "重复 reason 被接受了");
    }
}

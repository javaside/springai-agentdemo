// MagicSnifferTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MagicSnifferTest {
    private static byte[] bytes(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }

    @Test
    void png() {
        MagicSniffer.Sniffed s = MagicSniffer.sniff(bytes(0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A));
        assertEquals(MediaKind.IMAGE, s.kind());
        assertEquals("image/png", s.mimeType());
        assertEquals("png", s.ext());
    }

    @Test
    void jpeg() {
        assertEquals("image/jpeg", MagicSniffer.sniff(bytes(0xFF,0xD8,0xFF,0xE0)).mimeType());
    }

    @Test
    void pdf() {
        assertEquals("application/pdf", MagicSniffer.sniff(bytes(0x25,0x50,0x44,0x46,0x2D)).mimeType());
    }

    @Test
    void mp4_ftypAtOffset4() {
        assertEquals(MediaKind.VIDEO,
                MagicSniffer.sniff(bytes(0,0,0,0x18,0x66,0x74,0x79,0x70,0x69,0x73,0x6F,0x6D)).kind());
    }

    @Test
    void unknown_isBinaryOctetStreamBin() {
        MagicSniffer.Sniffed s = MagicSniffer.sniff(bytes(0x01,0x02,0x03,0x04));
        assertEquals(MediaKind.BINARY, s.kind());
        assertEquals("application/octet-stream", s.mimeType());
        assertEquals("bin", s.ext());
    }

    @Test
    void nullOrEmpty_unknown() {
        assertEquals(MediaKind.BINARY, MagicSniffer.sniff(null).kind());
        assertEquals(MediaKind.BINARY, MagicSniffer.sniff(new byte[0]).kind());
    }

    // --- Tika 带来的、手写魔数白名单原本漏判的格式 ---

    @Test
    void gif() {
        MagicSniffer.Sniffed s = MagicSniffer.sniff(bytes(0x47,0x49,0x46,0x38,0x39,0x61,1,0,1,0));
        assertEquals(MediaKind.IMAGE, s.kind());
        assertEquals("image/gif", s.mimeType());
    }

    @Test
    void tiff_littleEndian_image() {
        MagicSniffer.Sniffed s = MagicSniffer.sniff(bytes(0x49,0x49,0x2A,0x00,0x08,0x00,0x00,0x00));
        assertEquals(MediaKind.IMAGE, s.kind());
        assertEquals("image/tiff", s.mimeType());
    }

    @Test
    void ico_image() {
        assertEquals(MediaKind.IMAGE,
                MagicSniffer.sniff(bytes(0x00,0x00,0x01,0x00,0x01,0x00,0x10,0x10)).kind());
    }

    @Test
    void gzip_binary() {
        assertEquals("application/gzip",
                MagicSniffer.sniff(bytes(0x1F,0x8B,0x08,0x00,0,0,0,0)).mimeType());
    }

    @Test
    void plainTextSource_isText_notMedia() {
        MagicSniffer.Sniffed s = MagicSniffer.sniff("public class Foo {}\n// comment\n".getBytes());
        assertEquals(MediaKind.TEXT, s.kind(), "源码应判 TEXT，不当媒体");
        assertTrue(s.mimeType().startsWith("text/"));
    }

    // --- isTextFile：带强 magic 的文本格式（application/* 子型）必须仍判文本，否则会被错误外置 ---

    private static Path write(Path dir, String name, byte[] content) throws Exception {
        Path f = dir.resolve(name);
        Files.write(f, content);
        return f;
    }

    /** 回归：pom.xml 带 <?xml → Tika 判 application/xml（顶层非 text），但父链归 text/plain。
     *  修此前只看顶层 type → isTextFile=false → pom.xml 正文被路径①错误引用化。 */
    @Test
    void xmlWithDeclaration_isTextFile(@TempDir Path dir) throws Exception {
        Path f = write(dir, "pom.xml",
                "<?xml version=\"1.0\"?>\n<project><modelVersion>4.0.0</modelVersion></project>\n".getBytes());
        assertTrue(MagicSniffer.isTextFile(f), "pom.xml（application/xml）应判文本");
    }

    /** shebang 脚本 → application/x-sh，父链 → text/plain。 */
    @Test
    void shellScript_isTextFile(@TempDir Path dir) throws Exception {
        Path f = write(dir, "script.sh", "#!/bin/bash\necho hello\n".getBytes());
        assertTrue(MagicSniffer.isTextFile(f), "shebang 脚本（application/x-sh）应判文本");
    }

    /** xhtml → application/xhtml+xml → application/xml → text/plain。 */
    @Test
    void xhtml_isTextFile(@TempDir Path dir) throws Exception {
        Path f = write(dir, "app.xhtml",
                "<?xml version=\"1.0\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>x</body></html>".getBytes());
        assertTrue(MagicSniffer.isTextFile(f), "xhtml 应判文本");
    }

    @Test
    void jsonAndSource_isTextFile(@TempDir Path dir) throws Exception {
        assertTrue(MagicSniffer.isTextFile(write(dir, "package.json", "{\n \"name\":\"demo\"\n}\n".getBytes())));
        assertTrue(MagicSniffer.isTextFile(write(dir, "Plain.java", "public class P {}\n".getBytes())));
        assertTrue(MagicSniffer.isTextFile(write(dir, "notes.md", "# Title\ntext\n".getBytes())));
    }

    /** 负例回归：真媒体/二进制绝不能被父链误判成文本。 */
    @Test
    void media_isNotTextFile(@TempDir Path dir) throws Exception {
        byte[] png = new byte[64];
        int[] sig = {0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
        for (int i = 0; i < 8; i++) png[i] = (byte) sig[i];
        png[12]='I';png[13]='H';png[14]='D';png[15]='R';png[19]=10;png[23]=20;
        assertFalse(MagicSniffer.isTextFile(write(dir, "shot.png", png)), "PNG 不是文本");
        assertFalse(MagicSniffer.isTextFile(write(dir, "doc.pdf", "%PDF-1.4\n%âãÏÓ\n".getBytes())), "PDF 不是文本");
        assertFalse(MagicSniffer.isTextFile(write(dir, "a.zip", bytes(0x50,0x4B,0x03,0x04,0,0,0,0))), "ZIP 不是文本");
        assertFalse(MagicSniffer.isTextFile(write(dir, "a.gz", bytes(0x1F,0x8B,0x08,0,0,0,0,0))), "GZIP 不是文本");
    }
}

// MagicSnifferTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
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
}

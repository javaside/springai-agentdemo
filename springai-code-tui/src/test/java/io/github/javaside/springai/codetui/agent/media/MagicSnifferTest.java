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
}

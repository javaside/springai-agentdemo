// ImageDimensionsTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ImageDimensionsTest {
    /** 构造最小 PNG 头：8 字节签名 + IHDR 长度/类型 + width(4)/height(4)。 */
    private static byte[] png(int w, int h) {
        byte[] b = new byte[33];
        int[] sig = {0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
        for (int i = 0; i < 8; i++) b[i] = (byte) sig[i];
        // b[8..15] = IHDR chunk length(4) + "IHDR"(4)，值无关紧要
        putInt(b, 16, w);
        putInt(b, 20, h);
        return b;
    }
    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte)(v>>>24); b[off+1] = (byte)(v>>>16); b[off+2] = (byte)(v>>>8); b[off+3] = (byte) v;
    }

    @Test
    void pngWidthHeight() {
        Optional<int[]> d = ImageDimensions.of(png(2400, 1632));
        assertTrue(d.isPresent());
        assertArrayEquals(new int[]{2400, 1632}, d.get());
    }

    @Test
    void tooShort_empty() {
        assertTrue(ImageDimensions.of(new byte[]{1,2,3}).isEmpty());
    }
}

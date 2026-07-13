// ImageDimensions.java
package io.github.javaside.springai.codetui.agent.media;

import java.util.Optional;

/** 只解 PNG/JPEG 文件头拿宽高（不解码像素）。越界/未知/损坏一律返回空。 */
public final class ImageDimensions {
    private ImageDimensions() {}

    /** @return {width, height}，无法确定时 empty。 */
    public static Optional<int[]> of(byte[] b) {
        if (b == null) return Optional.empty();
        if (isPng(b)) return png(b);
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return jpeg(b);
        return Optional.empty();
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 24 && (b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50
                && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47;
    }

    private static Optional<int[]> png(byte[] b) {
        // PNG IHDR：宽在 offset 16、高在 offset 20，各 4 字节大端。
        int w = int32(b, 16), h = int32(b, 20);
        return (w > 0 && h > 0) ? Optional.of(new int[]{w, h}) : Optional.empty();
    }

    private static Optional<int[]> jpeg(byte[] b) {
        // 扫 SOF0..SOF3/SOF5..SOF7/SOF9..SOF11 段：0xFF 后跟 SOF marker，段内 offset+5 起为 height(2)/width(2)。
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) { i++; continue; }
            int marker = b[i + 1] & 0xFF;
            if (isSof(marker)) {
                int h = int16(b, i + 5), w = int16(b, i + 7);
                return (w > 0 && h > 0) ? Optional.of(new int[]{w, h}) : Optional.empty();
            }
            int len = int16(b, i + 2);
            if (len < 2) return Optional.empty();
            i += 2 + len;
        }
        return Optional.empty();
    }

    private static boolean isSof(int m) {
        return (m >= 0xC0 && m <= 0xC3) || (m >= 0xC5 && m <= 0xC7) || (m >= 0xC9 && m <= 0xCB);
    }

    private static int int32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o+1] & 0xFF) << 16) | ((b[o+2] & 0xFF) << 8) | (b[o+3] & 0xFF);
    }
    private static int int16(byte[] b, int o) { return ((b[o] & 0xFF) << 8) | (b[o+1] & 0xFF); }
}

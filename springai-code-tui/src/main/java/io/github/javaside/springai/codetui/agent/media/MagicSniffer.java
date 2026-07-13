package io.github.javaside.springai.codetui.agent.media;

/** 按文件头魔数判类型——MCP 的 mimeType 是外部输入不可信，一律以实际魔数为准。 */
public final class MagicSniffer {
    private MagicSniffer() {}

    public record Sniffed(MediaKind kind, String mimeType, String ext) {}

    private static final Sniffed UNKNOWN =
            new Sniffed(MediaKind.BINARY, "application/octet-stream", "bin");

    public static Sniffed sniff(byte[] b) {
        if (starts(b, 0x89,0x50,0x4E,0x47)) return new Sniffed(MediaKind.IMAGE, "image/png", "png");
        if (starts(b, 0xFF,0xD8,0xFF))      return new Sniffed(MediaKind.IMAGE, "image/jpeg", "jpg");
        if (starts(b, 0x47,0x49,0x46,0x38)) return new Sniffed(MediaKind.IMAGE, "image/gif", "gif");
        if (starts(b, 0x52,0x49,0x46,0x46) && at(b,8,0x57,0x45,0x42,0x50))
            return new Sniffed(MediaKind.IMAGE, "image/webp", "webp");
        if (starts(b, 0x25,0x50,0x44,0x46)) return new Sniffed(MediaKind.BINARY, "application/pdf", "pdf");
        if (starts(b, 0x50,0x4B,0x03,0x04)) return new Sniffed(MediaKind.BINARY, "application/zip", "zip");
        if (starts(b, 0x1A,0x45,0xDF,0xA3)) return new Sniffed(MediaKind.VIDEO, "video/webm", "webm");
        if (at(b,4,0x66,0x74,0x79,0x70))    return new Sniffed(MediaKind.VIDEO, "video/mp4", "mp4");
        return UNKNOWN;
    }

    private static boolean starts(byte[] b, int... sig) { return at(b, 0, sig); }

    private static boolean at(byte[] b, int off, int... sig) {
        if (b == null || b.length < off + sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((b[off + i] & 0xFF) != sig[i]) return false;
        return true;
    }
}

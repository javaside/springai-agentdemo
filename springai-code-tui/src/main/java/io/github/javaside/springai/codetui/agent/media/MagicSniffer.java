package io.github.javaside.springai.codetui.agent.media;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypes;

import java.io.ByteArrayInputStream;

/** 按文件内容魔数判类型——声明的 MIME（如 MCP 的 mimeType）是外部输入不可信，一律以实际字节为准。
 *  底层用 Apache Tika（tika-core）的内容检测，覆盖上千种格式；只做<b>类型检测</b>，不引 parser。
 *  对外 API（{@link Sniffed}）保持稳定：kind + 权威 mimeType + 扩展名。 */
public final class MagicSniffer {
    private MagicSniffer() {}

    public record Sniffed(MediaKind kind, String mimeType, String ext) {}

    private static final Sniffed UNKNOWN =
            new Sniffed(MediaKind.BINARY, "application/octet-stream", "bin");

    private static final MimeTypes MIME_TYPES;
    static {
        // 用默认 TikaConfig 的 MimeTypes 仓库（含全部 magic 定义）；构造一次复用，线程安全。
        MIME_TYPES = TikaConfig.getDefaultConfig().getMimeRepository();
    }

    /** 按字节内容嗅探类型。null/空/无法确定 → UNKNOWN（BINARY, application/octet-stream, bin）。 */
    public static Sniffed sniff(byte[] b) {
        if (b == null || b.length == 0) return UNKNOWN;
        try (TikaInputStream in = TikaInputStream.get(new ByteArrayInputStream(b))) {
            MediaType type = MIME_TYPES.detect(in, new Metadata());
            if (type == null) return UNKNOWN;
            String mime = type.getBaseType().toString();
            if (mime.isBlank() || "application/octet-stream".equals(mime)) return UNKNOWN;
            return new Sniffed(kindOf(mime), mime, extOf(mime));
        } catch (Exception e) {
            return UNKNOWN;   // 检测失败绝不误判/不崩，退回未知二进制
        }
    }

    /** MIME 顶层类型 → MediaKind。text/* 归 TEXT；image/* → IMAGE；video/* → VIDEO；其余 → BINARY。 */
    private static MediaKind kindOf(String mime) {
        if (mime.startsWith("image/")) return MediaKind.IMAGE;
        if (mime.startsWith("video/")) return MediaKind.VIDEO;
        if (mime.startsWith("text/")) return MediaKind.TEXT;
        return MediaKind.BINARY;
    }

    /** 由 MIME 取首选扩展名（Tika 注册表），失败时从子类型兜底。 */
    private static String extOf(String mime) {
        try {
            MimeType mt = MIME_TYPES.getRegisteredMimeType(mime);
            if (mt != null) {
                String ext = mt.getExtension();   // 形如 ".png"，可能为空串
                if (ext != null && !ext.isBlank()) return ext.startsWith(".") ? ext.substring(1) : ext;
            }
        } catch (Exception ignore) {
            // 落到子类型兜底
        }
        int slash = mime.indexOf('/');
        String sub = slash >= 0 ? mime.substring(slash + 1) : mime;
        int plus = sub.indexOf('+');                    // image/svg+xml → svg
        if (plus >= 0) sub = sub.substring(0, plus);
        return sub.isBlank() ? "bin" : sub;
    }
}

package io.github.javaside.springai.codetui.agent.media;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypes;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** 按文件内容魔数判类型——声明的 MIME（如 MCP 的 mimeType）是外部输入不可信，一律以实际字节为准。
 *  底层用 Apache Tika（tika-core）的内容检测，覆盖上千种格式；只做<b>类型检测</b>，不引 parser。
 *  对外 API（{@link Sniffed}）保持稳定：kind + 权威 mimeType + 扩展名。 */
public final class MagicSniffer {
    private MagicSniffer() {}

    public record Sniffed(MediaKind kind, String mimeType, String ext) {}

    private static final Sniffed UNKNOWN =
            new Sniffed(MediaKind.BINARY, "application/octet-stream", "bin");

    private static final MimeTypes MIME_TYPES;
    private static final MediaTypeRegistry TYPE_REGISTRY;
    static {
        // 用默认 TikaConfig 的 MimeTypes 仓库（含全部 magic 定义）；构造一次复用，线程安全。
        TikaConfig cfg = TikaConfig.getDefaultConfig();
        MIME_TYPES = cfg.getMimeRepository();
        TYPE_REGISTRY = cfg.getMediaTypeRegistry();
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

    /** 磁盘文件是否为文本：明确识别为文本、其 MIME 的 Tika 父类型链含 text/*、或无法识别（未知字节，
     *  可能是纯文本/源码）——都当文本。仅「明确识别为 image/video/pdf/zip 等非文本媒体」才判 false。
     *  读不到当文本（宁可留会话不误引用）。
     *  <p>用父类型链而非仅顶层 type 是关键：Tika 对带强 magic 的文本格式给出 application/* 子型
     *  （pom.xml→application/xml、shebang 脚本→application/x-sh、json→text/javascript…），顶层是
     *  application 而非 text，但它们在 Tika 注册表里的父链最终归到 text/plain。只看顶层会把 pom.xml 之类
     *  误判成二进制而错误外置（违背修正 #9「文本文件正文永不外置」）；查父链一次覆盖，且随 Tika 升级自动跟进。 */
    public static boolean isTextFile(Path file) {
        try {
            byte[] head = readHead(file, 512);   // Tika 文本判定需多看几字节
            Sniffed s = sniff(head);
            if (s.kind() == MediaKind.TEXT) return true;                     // 明确文本（text/*）
            if (s.kind() == MediaKind.BINARY
                    && "application/octet-stream".equals(s.mimeType())) {    // 未知 → 保守当文本
                return true;
            }
            return hasTextSupertype(s.mimeType());                           // application/xml、x-sh 等经父链归 text
        } catch (Exception e) {
            return true;
        }
    }

    /** MIME 的 Tika 父类型链里是否出现 text/*（到 octet-stream 或成环即止）。 */
    private static boolean hasTextSupertype(String mimeStr) {
        try {
            MediaType t = MediaType.parse(mimeStr);
            Set<MediaType> seen = new HashSet<>();
            while (t != null && seen.add(t)) {
                if ("text".equals(t.getType())) return true;
                if (MediaType.OCTET_STREAM.equals(t)) return false;
                t = TYPE_REGISTRY.getSupertype(t);
            }
        } catch (Exception ignore) {
            // 解析/查询失败：落到 false，交由调用方保守处理
        }
        return false;
    }

    private static byte[] readHead(Path file, int n) throws java.io.IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[n];
            int read = in.readNBytes(buf, 0, n);
            if (read == n) return buf;
            byte[] head = new byte[read];
            System.arraycopy(buf, 0, head, 0, read);
            return head;
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

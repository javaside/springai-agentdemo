// FileReference.java
package io.github.javaside.springai.codetui.agent.media;

/** 会话里替换文件内容的结构化引用块：稳定、可再解析、语言无关。UI 可再渲成中文短句。 */
public final class FileReference {
    private FileReference() {}

    public static final String OPEN = "[file reference]";
    public static final String CLOSE = "[/file reference]";

    /** 幂等判定：一段工具结果里是否已含引用块（含则不再重复外置）。 */
    public static boolean isReference(String s) {
        return s != null && s.contains(OPEN);
    }

    /** 渲染引用块。dimensions/lines 仅在有值时出现。 */
    public static String render(MediaArtifact a, String delivery, String reason) {
        StringBuilder b = new StringBuilder();
        b.append(OPEN).append('\n');
        b.append("id: sha256:").append(a.shortId()).append('\n');
        b.append("kind: ").append(a.kind().name().toLowerCase()).append('\n');
        b.append("mime_type: ").append(a.mimeType()).append('\n');
        if (a.declaredMimeType() != null && !a.declaredMimeType().equals(a.mimeType())) {
            b.append("declared_mime_type: ").append(a.declaredMimeType()).append('\n');
        }
        b.append("size_bytes: ").append(a.size()).append('\n');
        if (a.width() != null && a.height() != null) {
            b.append("dimensions: ").append(a.width()).append('x').append(a.height()).append('\n');
        }
        if (a.lineCount() != null) {
            b.append("lines: ").append(a.lineCount()).append('\n');
        }
        b.append("path: ").append(a.relativePath()).append('\n');
        b.append("delivery: ").append(delivery).append('\n');
        b.append("reason: ").append(reason).append('\n');
        b.append(CLOSE);
        return b.toString();
    }
}

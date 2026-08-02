// FileReference.java
package io.github.javaside.springai.codetui.agent.media;

/** 会话里替换文件内容的结构化引用块：稳定、可再解析、语言无关。UI 可再渲成中文短句。 */
public final class FileReference {
    private FileReference() {}

    public static final String OPEN = "[file reference]";
    public static final String CLOSE = "[/file reference]";

    /** 图已随本请求交付给模型。 */
    public static final String DELIVERY_DELIVERED = "delivered";
    /** 当前模型无视觉能力——取回来也看不见，别 Read。 */
    public static final String DELIVERY_REFERENCE_ONLY = "reference_only";
    /** 模型有视觉能力，只是这张不在当轮视野——Read 一次就能看。 */
    public static final String DELIVERY_NOT_IN_VIEW = "not_in_view";
    /** 当轮图太多，这张被预算挤掉——想看就单独 Read 它。 */
    public static final String DELIVERY_BUDGET_EXCEEDED = "budget_exceeded";
    /** 本回合累计视觉额度已用尽——结束本回合后重新发起。 */
    public static final String DELIVERY_TURN_EXHAUSTED = "turn_budget_exhausted";

    private static final String DELIVERY_PREFIX = "delivery: ";

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
        // 原始文件名放在 path 之前：sha 路径不可读，模型跨回合靠这行指认「购物车那张」。
        if (a.originalName() != null && !a.originalName().isBlank()) {
            b.append("name: ").append(a.originalName()).append('\n');
        }
        b.append("path: ").append(a.relativePath()).append('\n');
        b.append(DELIVERY_PREFIX).append(delivery).append('\n');
        b.append("reason: ").append(reason).append('\n');
        b.append(CLOSE);
        return b.toString();
    }

    /**
     * 把引用块里的 {@code delivery} 行换成新状态，其余逐字不动。
     *
     * <p><b>为什么必须有它</b>：兑现只加 {@code Media} 不改文本，模型会同时收到
     * 「这张图你看不见」和那张图——自相矛盾的信号。改写只作用于<b>出站副本</b>，
     * 会话存储里那份保持原状（存储里永远不该出现 delivered）。
     */
    public static String withDelivery(String referenceBlock, String delivery) {
        if (referenceBlock == null) return null;
        String[] lines = referenceBlock.split("\n", -1);
        StringBuilder out = new StringBuilder(referenceBlock.length());
        // 用下标而非 out.length() 判首行：首行为空时长度仍是 0，会吞掉一个换行导致行数变化。
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(lines[i].startsWith(DELIVERY_PREFIX) ? DELIVERY_PREFIX + delivery : lines[i]);
        }
        return out.toString();
    }
}

// TextReferenceMediaHandler.java
package io.github.javaside.springai.codetui.agent.media;

/** 把外置产物渲染成结构化文本引用。字节永不进会话——真投递由出站侧另行加 Media 块。 */
public final class TextReferenceMediaHandler implements ToolResultMediaHandler {

    /**
     * 「能不能真投递」最终由出站侧的 VisionMaterializer 说了算（它还要过预算与格式检查），
     * 本方法只回答「模型有没有这个能力」——决定引用里写 reference_only 还是 not_in_view。
     * 视频本期一律不投递（各家支持度差异大、token 不可控）。
     */
    @Override
    public boolean canDeliver(MediaKind kind, ModelCapabilities caps) {
        return kind == MediaKind.IMAGE && caps.supportsImageInput();
    }

    @Override
    public String represent(MediaArtifact media, ModelCapabilities caps) {
        if (canDeliver(media.kind(), caps)) {
            // 有能力：这张图可能在当轮视野里、也可能不在——由出站侧决定并就地改写 delivery。
            // 此处一律写 not_in_view 是保守默认：模型据此知道「Read 一次就能看」。
            return FileReference.render(media, FileReference.DELIVERY_NOT_IN_VIEW,
                    "not currently in view; Read this path to bring it into view");
        }
        return FileReference.render(media, FileReference.DELIVERY_REFERENCE_ONLY,
                "current model has no image input; re-reading will not show it");
    }
}

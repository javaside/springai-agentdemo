// TextReferenceMediaHandler.java
package io.github.javaside.springai.codetui.agent.media;

/** 本期唯一实现：无注入器 → canDeliver 恒 false → 一律输出结构化文本引用。
 *  视觉真注入（canDeliver=true 时的原生 image 块）属 Path B，此处留桩注释。 */
public final class TextReferenceMediaHandler implements ToolResultMediaHandler {

    @Override
    public boolean canDeliver(MediaKind kind, ModelCapabilities caps) {
        // Path B：当接上原生 image 注入器后，这里改为
        //   return kind == MediaKind.IMAGE && caps.supportsImageInput();
        return false;
    }

    @Override
    public String represent(MediaArtifact media, ModelCapabilities caps) {
        // canDeliver=false → reference_only；字节永不进模型。
        return FileReference.render(media, "reference_only",
                "content externalized from session memory; re-read to view");
    }
}

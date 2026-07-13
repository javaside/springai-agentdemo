package io.github.javaside.springai.codetui.agent.media;

/** 模型能力快照（按模型判定）。随请求冻结进 ToolContext，规避工具执行期间切模型的时序错配。
 *  图与视频分离：支持图不代表支持视频。 */
public record ModelCapabilities(boolean supportsImageInput, boolean supportsVideoInput) {
    /** 纯文本模型（本期全部）。 */
    public static final ModelCapabilities TEXT_ONLY = new ModelCapabilities(false, false);
}

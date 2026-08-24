// ToolResultMediaHandler.java
package io.github.javaside.springai.codetui.agent.media;

/** 表示策略扩展位：给定媒体产物与能力，决定「能否真投递」与「在工具结果里的表示」。 */
public interface ToolResultMediaHandler {
    /** 当前能力下能否把该类媒体真投递给模型（= 模型支持该类输入 {@code &&} 本链路已接注入器）。 */
    boolean canDeliver(MediaKind kind, ModelCapabilities caps);

    /** 产出该媒体在工具结果里的表示（本期恒引用；视觉分支属 Path B）。 */
    String represent(MediaArtifact media, ModelCapabilities caps);
}

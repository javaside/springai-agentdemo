package io.github.javaside.springai.codetui.agent.media;

/** 上一次兑现的统计快照，供 {@code /context} 单列视觉占用。不可变，volatile 发布。 */
public record VisionSnapshot(int images, long tokens) {
    public static final VisionSnapshot EMPTY = new VisionSnapshot(0, 0L);
}

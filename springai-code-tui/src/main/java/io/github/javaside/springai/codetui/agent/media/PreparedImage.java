package io.github.javaside.springai.codetui.agent.media;

/**
 * 已准备好、可直接挂到 {@code Media} 上的图片字节。
 *
 * @param bytes    实际要发出去的字节（可能是缩过/转码过的，<b>不一定等于磁盘原件</b>）
 * @param mimeType 这些字节的类型（转码后可能与磁盘原件不同）
 * @param width    实际发出去的宽
 * @param height   实际发出去的高
 * @param estimatedTokens 估算视觉 token（宽×高/750，Anthropic 口径，仅用于预算）
 */
public record PreparedImage(byte[] bytes, String mimeType, int width, int height,
                            long estimatedTokens) {
}

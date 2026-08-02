package io.github.javaside.springai.codetui.agent.media;

import java.nio.file.Path;

/**
 * 从一段文本里解析出的一个<b>可兑现</b>图片引用。
 *
 * <p>「可兑现」是强承诺：能构造出本对象，就意味着 path 已通过 root 包含校验、文件真实存在、
 * kind 确为 image。下游可以直接读字节，不必再验一遍。
 *
 * @param sha      引用里的短 id（去掉 {@code sha256:} 前缀），单请求内去重用
 * @param name     原始文件名，给模型指认「哪一张」
 * @param mimeType 引用里声明的类型（实际类型仍以磁盘魔数为准，见 {@code ImagePreparer}）
 * @param file     已通过包含校验、真实存在的磁盘路径（已解符号链接）
 * @param start    该引用块在原文本中的起始下标（含），供就地改写 delivery
 * @param end      该引用块在原文本中的结束下标（不含）
 */
public record ParsedReference(String sha, String name, String mimeType,
                              Path file, int start, int end) {
}

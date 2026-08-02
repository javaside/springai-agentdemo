package io.github.javaside.springai.codetui.ui;

import java.nio.file.Path;

/**
 * 从输入框文本里识别出的一张待附图片。
 *
 * @param file       磁盘上的真实文件（已确认存在、可读、魔数是图片）
 * @param name       给模型看的可读名字（取文件名）
 * @param width      像素宽（解析不出时为 0）
 * @param height     像素高（解析不出时为 0）
 * @param insideRoot 是否在 project root 内。<b>决定提交时怎么处理</b>：root 内指原文件
 *                   （模型 Read 得到当前内容），root 外必须复制进 artifacts——否则写进引用块的
 *                   越界 path 会被 {@code FileReferenceParser} 的注入防线整块丢弃，且无任何报错。
 */
public record DetectedImage(Path file, String name, int width, int height, boolean insideRoot) {
}

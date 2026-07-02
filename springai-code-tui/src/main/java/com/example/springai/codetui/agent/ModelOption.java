package com.example.springai.codetui.agent;

/**
 * 一个可选模型条目（供 {@code /model} 选择器展示与切换）。
 *
 * @param id    传给 API 的模型标识（如 {@code deepseek-v4-flash}）
 * @param label 展示名
 * @param desc  一句话说明（右侧暗色提示）
 */
public record ModelOption(String id, String label, String desc) {
}

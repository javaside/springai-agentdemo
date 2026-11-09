package io.github.javaside.springai.tamboui.demo;

/**
 * 宽度对照表的一行：同一样本串，「上游 CharWidth 算出的宽度」vs「终端实际渲染宽度」。
 *
 * @param label          展示名
 * @param sample         样本串（调用方用码点构造，防不可见字符被悄悄改动）
 * @param upstream       上游 TamboUI 0.4.0 {@code CharWidth.of(sample)} 的实测返回值
 * @param actualTerminal 终端实际渲染宽度（口径取自 springai-tamboui-inline-patch 的实测记录：
 *                       组合序列按终端实际渲染宽度计）
 * @param sick           两口径是否不符（带病行——教学展示的核心）
 */
public record WidthSample(String label, String sample, int upstream, int actualTerminal, boolean sick) {
}

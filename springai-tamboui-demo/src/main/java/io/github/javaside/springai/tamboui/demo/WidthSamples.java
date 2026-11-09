package io.github.javaside.springai.tamboui.demo;

import java.util.List;

/**
 * 宽度对照表数据源（示例①使用）。upstream 列为上游 TamboUI 0.4.0 的实测值
 * （2026-11-09 本机探针：对每个样本调 {@code CharWidth.of} 逐个记录，WidthSamplesTest
 * 的「现场互证」断言钉住这批值不漂移）；actualTerminal 列取自 patch 模块 spec 的实测记录。
 *
 * <p>样本一律码点构造：VS16/ZWJ/ZWNJ 是不可见字符，字面量会被复制粘贴与格式化悄悄改坏。</p>
 */
public final class WidthSamples {

    private WidthSamples() {
    }

    public static List<WidthSample> table() {
        return List.of(
                new WidthSample("ASCII", "A", 1, 1, false),
                new WidthSample("中文", new String(new int[]{0x4E2D}, 0, 1), 2, 2, false),
                new WidthSample("雪花+VS16", new String(new int[]{0x2744, 0xFE0F}, 0, 2), 1, 2, true),
                new WidthSample("keycap", new String(new int[]{0x0039, 0xFE0F, 0x20E3}, 0, 3), 1, 2, true),
                new WidthSample("ZWJ 家族", new String(new int[]{0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467}, 0, 5), 2, 5, true),
                new WidthSample("含 ZWNJ", new String(new int[]{0x0061, 0x200C, 0x0062}, 0, 3), 2, 3, true)
        );
    }
}

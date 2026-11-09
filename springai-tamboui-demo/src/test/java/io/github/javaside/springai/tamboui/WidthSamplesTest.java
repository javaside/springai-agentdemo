package io.github.javaside.springai.tamboui;

import dev.tamboui.text.CharWidth;
import io.github.javaside.springai.tamboui.demo.WidthSample;
import io.github.javaside.springai.tamboui.demo.WidthSamples;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宽度对照表测试——钉死上游 TamboUI 0.4.0 {@link CharWidth} 的实测口径（2026-11-09 本机探针）。
 *
 * <p>四个「带病值」（雪花+VS16=1、keycap=1、ZWJ 家族=2、含 ZWNJ=2）是教学叙事的事实基础：
 * 本模块用上游原版 jar，测出来就是这些值。上游将来修好病值时
 * {@code CharWidth.of(sample) == upstream} 的互证断言会变红——提示更新示例文案与 README，
 * 这是活断言，不是坏掉。</p>
 *
 * <p>样本一律码点构造，禁止字符串字面量：这类序列里的 VS16/ZWJ/ZWNJ 是不可见字符，
 * 复制粘贴与编辑器格式化都可能悄悄改掉它们（历史上已多次发生）。</p>
 */
class WidthSamplesTest {

    /** 码点构造快捷方式。 */
    private static String cps(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    void tableHasSixRowsInTeachingOrder() {
        assertEquals(6, WidthSamples.table().size());
        assertEquals(List.of("ASCII", "中文", "雪花+VS16", "keycap", "ZWJ 家族", "含 ZWNJ"),
                WidthSamples.table().stream().map(WidthSample::label).toList());
    }

    @Test
    void asciiAndCjkAreHealthy() {
        List<WidthSample> table = WidthSamples.table();
        WidthSample ascii = table.get(0);
        assertEquals("A", ascii.sample());
        assertEquals(1, ascii.upstream());
        assertEquals(1, ascii.actualTerminal());
        assertEquals(false, ascii.sick());

        WidthSample cjk = table.get(1);
        assertEquals(cps(0x4E2D), cjk.sample());
        assertEquals(2, cjk.upstream());
        assertEquals(2, cjk.actualTerminal());
        assertEquals(false, cjk.sick());
    }

    @Test
    void vs16SnowflakeIsSick() {
        WidthSample row = WidthSamples.table().get(2);
        assertEquals(cps(0x2744, 0xFE0F), row.sample());
        assertEquals(1, row.upstream());
        assertEquals(2, row.actualTerminal());
        assertTrue(row.sick());
    }

    @Test
    void keycapIsSick() {
        WidthSample row = WidthSamples.table().get(3);
        assertEquals(cps(0x0039, 0xFE0F, 0x20E3), row.sample());
        assertEquals(1, row.upstream());
        assertEquals(2, row.actualTerminal());
        assertTrue(row.sick());
    }

    @Test
    void zwjFamilyIsSick() {
        WidthSample row = WidthSamples.table().get(4);
        assertEquals(cps(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467), row.sample());
        assertEquals(2, row.upstream());
        assertEquals(5, row.actualTerminal());
        assertTrue(row.sick());
    }

    @Test
    void zwnjIsSick() {
        WidthSample row = WidthSamples.table().get(5);
        assertEquals(cps(0x0061, 0x200C, 0x0062), row.sample());
        assertEquals(2, row.upstream());
        assertEquals(3, row.actualTerminal());
        assertTrue(row.sick());
    }

    @Test
    void everyUpstreamValueMatchesLiveLibrary() {
        // 表与库现场互证：WidthSamples 里写死的 upstream 值必须等于当场调 CharWidth 的返回值。
        for (WidthSample row : WidthSamples.table()) {
            assertEquals(row.upstream(), CharWidth.of(row.sample()),
                    "上游实测漂移：" + row.label() + " —— 库行为变了，须同步更新示例文案与 README");
        }
    }
}

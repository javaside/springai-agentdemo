package io.github.javaside.springai.tamboui.demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 第 3 课的数据源测试：演示文本（三粒度/颜色来源/对齐）与宽度基础示例。
 *
 * <p>宽度部分只教健康口径的基础事实：ASCII 1 列、CJK 2 列、混合求和——宽度测量的
 * 深水区（组合序列等）不属于入门课范围。</p>
 */
class TextStyleShowcaseTest {

    @Test
    void demoTextHasTeachingLines() {
        var text = TextStyleShowcase.demoText();
        assertEquals(10, text.height());
    }

    @Test
    void cjkLineWidthCountsTwoColumnsPerChar() {
        // 「终端富文本」5 个中文 × 2 列 = 10 列（Line.width() 按 CharWidth 口径求和）
        assertEquals(10, TextStyleShowcase.cjkLine().width());
    }

    @Test
    void mixedLineWidthSumsAcrossSpans() {
        // Line 宽度 = 各 Span 宽度之和：「第」(2) + "2"(1) + 「课」(2) = 5
        assertEquals(5, TextStyleShowcase.mixedLine().width());
    }

    @Test
    void widthExamplesCoverBasics() {
        List<TextStyleShowcase.WidthExample> rows = TextStyleShowcase.widthExamples();
        assertEquals(List.of(
                new TextStyleShowcase.WidthExample("A", 1),
                new TextStyleShowcase.WidthExample("中", 2),
                new TextStyleShowcase.WidthExample("中文A", 5)
        ), rows);
        // 示例值必须与库现场一致（CharWidth 实测）
        for (TextStyleShowcase.WidthExample row : rows) {
            assertEquals(dev.tamboui.text.CharWidth.of(row.sample()), row.columns());
        }
    }
}

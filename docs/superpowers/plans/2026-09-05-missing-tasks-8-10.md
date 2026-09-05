# Task 8-10 完整 TDD 实施步骤（需插入到主计划中）

## 插入位置
在 Task 7 (Line 1173) 之后、Task 11 (Line 1176) 之前

---

## Task 8: MarkdownTable render 主逻辑（第 1 部分：解析与列宽计算）

**Files:**
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/MarkdownTableTest.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/MarkdownTable.java`

- [ ] **Step 1: 写失败测试 - 基本表格排版**

```java
@Test
void render_producesAlignedTable() {
    List<String> block = List.of(
        "| Name | Age |",
        "|------|-----|",
        "| Alice | 30 |",
        "| Bob | 25 |"
    );
    
    List<List<Span>> result = MarkdownTable.render(block, 80);
    
    // 4 行输出：表头（加粗）、分隔线、2 行数据
    assertEquals(4, result.size());
    
    // 验证表头加粗
    boolean hasBold = result.get(0).stream()
        .anyMatch(s -> s.style() != null && s.style().contains(Style.BOLD));
    assertTrue(hasBold);
    
    // 验证分隔线是 ─
    String separatorLine = result.get(1).stream()
        .map(Span::content)
        .collect(java.util.stream.Collectors.joining());
    assertTrue(separatorLine.matches("─+"));
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -pl springai-code-tui -Dtest=MarkdownTableTest#render_producesAlignedTable`
Expected: FAIL (render 返回 fallback)

- [ ] **Step 3: 实现 render 主逻辑第 1 部分（解析与列宽）**

修改 `render` 方法，在现有 fallback 逻辑后添加：

```java
static List<List<Span>> render(List<String> block, int inner) {
    // 入口守卫（已有）
    if (inner < 6 || block == null || block.size() < 2) {
        return fallbackToRaw(block);
    }
    
    // 解析表头和分隔行
    String headerLine = block.get(0);
    String separatorLine = block.get(1);
    
    if (!looksLikeRow(headerLine) || !isSeparator(separatorLine)) {
        return fallbackToRaw(block);
    }
    
    List<String> headerCells = parseCells(headerLine);
    List<Alignment> alignments = alignments(separatorLine);
    
    // 分隔行列数必须 >= 表头列数
    if (alignments.size() < headerCells.size()) {
        return fallbackToRaw(block);
    }
    
    int columnCount = headerCells.size();
    
    // 解析所有行（含表头），每个单元格先过内联解析
    List<List<String>> allRowsRaw = new ArrayList<>();
    List<List<List<Span>>> allRowsSpans = new ArrayList<>();
    
    // 表头行
    allRowsRaw.add(headerCells);
    List<List<Span>> headerSpans = headerCells.stream()
        .map(MarkdownRenderer::renderInline)
        .collect(Collectors.toList());
    allRowsSpans.add(headerSpans);
    
    // 数据行
    for (int i = 2; i < block.size(); i++) {
        String line = block.get(i);
        List<String> cells = adjustCellCount(parseCells(line), columnCount);
        allRowsRaw.add(cells);
        
        List<List<Span>> cellSpans = cells.stream()
            .map(MarkdownRenderer::renderInline)
            .collect(Collectors.toList());
        allRowsSpans.add(cellSpans);
    }
    
    // 计算列宽（基于 spans 拼接后的显示宽度）
    int[] columnWidths = new int[columnCount];
    for (List<List<Span>> rowSpans : allRowsSpans) {
        for (int i = 0; i < columnCount; i++) {
            int cellWidth = spansDisplayWidth(rowSpans.get(i));
            columnWidths[i] = Math.max(columnWidths[i], cellWidth);
        }
    }
    
    // 空列最小宽度 4
    for (int i = 0; i < columnWidths.length; i++) {
        if (columnWidths[i] == 0) {
            columnWidths[i] = 4;
        }
    }
    
    // 计算表格总宽
    int separatorWidth = Math.max(0, 2 * (columnCount - 1));
    int totalWidth = separatorWidth;
    for (int w : columnWidths) {
        totalWidth += w;
    }
    
    // 超宽处理：削列
    if (totalWidth > inner) {
        int[] reduced = reduceColumnWidths(columnWidths, inner);
        
        // 削列超限（返回 null），退回原样
        if (reduced == null) {
            return fallbackToRaw(block);
        }
        
        columnWidths = reduced;
        
        // 重新计算总宽
        totalWidth = separatorWidth;
        for (int w : columnWidths) {
            totalWidth += w;
        }
        
        // 仍然超宽（每列已到最小宽 4），退回原样
        int minTotalWidth = 4 * columnCount + separatorWidth;
        if (totalWidth > inner || totalWidth < minTotalWidth) {
            return fallbackToRaw(block);
        }
    }
    
    // 格式化输出（Task 9 实现）
    return formatTableOutput(allRowsRaw, columnWidths, alignments, 
                            totalWidth, columnCount);
}

/**
 * 格式化输出（占位，Task 9 实现）
 */
private static List<List<Span>> formatTableOutput(
        List<List<String>> allRowsRaw,
        int[] columnWidths,
        List<Alignment> alignments,
        int totalWidth,
        int columnCount) {
    // 占位，返回空列表
    return List.of();
}
```

- [ ] **Step 4: 添加必要的 import**

在文件顶部添加：

```java
import java.util.stream.Collectors;
```

- [ ] **Step 5: 运行测试验证（部分失败）**

Run: `mvn test -pl springai-code-tui -Dtest=MarkdownTableTest#render_producesAlignedTable`
Expected: FAIL (formatTableOutput 返回空列表)

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/MarkdownTableTest.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/MarkdownTable.java
git commit -m "feat(code-tui): MarkdownTable.render 解析与列宽计算逻辑"
```

---

## Task 9: MarkdownTable render 主逻辑（第 2 部分：格式化输出）

**Files:**
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/MarkdownTableTest.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/MarkdownTable.java`

- [ ] **Step 1: 实现 formatTableOutput 方法**

```java
/**
 * 格式化表格输出：格内折行 + 对齐 + 组装 spans。
 * 含产出侧 600 行上限保护。
 */
private static List<List<Span>> formatTableOutput(
        List<List<String>> allRowsRaw,
        int[] columnWidths,
        List<Alignment> alignments,
        int totalWidth,
        int columnCount) {
    
    List<List<Span>> output = new ArrayList<>();
    int outputLineCount = 0;
    final int MAX_OUTPUT_LINES = 600;
    
    for (int rowIdx = 0; rowIdx < allRowsRaw.size(); rowIdx++) {
        List<String> rowRaw = allRowsRaw.get(rowIdx);
        
        // 格内折行（基于 raw text）
        List<List<String>> wrappedCells = new ArrayList<>();
        int maxLinesInRow = 1;
        
        for (int colIdx = 0; colIdx < columnCount; colIdx++) {
            String cellText = rowRaw.get(colIdx);
            List<String> wrappedLines = wrapCellContent(cellText, columnWidths[colIdx]);
            wrappedCells.add(wrappedLines);
            maxLinesInRow = Math.max(maxLinesInRow, wrappedLines.size());
        }
        
        // 按物理行输出
        for (int lineIdx = 0; lineIdx < maxLinesInRow; lineIdx++) {
            if (outputLineCount >= MAX_OUTPUT_LINES) {
                // 产出侧上限，中止并退回原样
                return fallbackToRaw(reconstructBlock(allRowsRaw));
            }
            
            List<Span> physicalLine = new ArrayList<>();
            
            for (int colIdx = 0; colIdx < columnCount; colIdx++) {
                List<String> cellLines = wrappedCells.get(colIdx);
                String content = lineIdx < cellLines.size() ? cellLines.get(lineIdx) : "";
                
                // 应用对齐
                boolean isLastColumn = (colIdx == columnCount - 1);
                String aligned = applyAlignment(content, columnWidths[colIdx], 
                                               alignments.get(colIdx), isLastColumn);
                
                // 表头加粗
                if (rowIdx == 0) {
                    physicalLine.add(Span.styled(aligned, Style.BOLD));
                } else {
                    physicalLine.add(Span.text(aligned));
                }
                
                // 列间空格（最后一列无）
                if (colIdx < columnCount - 1) {
                    physicalLine.add(Span.text("  "));
                }
            }
            
            output.add(physicalLine);
            outputLineCount++;
        }
        
        // 表头后插入分隔线
        if (rowIdx == 0) {
            if (outputLineCount >= MAX_OUTPUT_LINES) {
                return fallbackToRaw(reconstructBlock(allRowsRaw));
            }
            
            String separator = "─".repeat(totalWidth);
            output.add(List.of(Span.text(separator)));
            outputLineCount++;
        }
    }
    
    return output;
}

/**
 * 应用对齐（左/中/右），返回补白后的字符串。
 * isLastColumn: 最后一列的尾部补白必须丢掉。
 */
private static String applyAlignment(String content, int width, 
                                     Alignment align, boolean isLastColumn) {
    int contentWidth = displayWidth(content);
    int padding = width - contentWidth;
    
    if (padding <= 0) {
        return content;
    }
    
    switch (align) {
        case RIGHT:
            return " ".repeat(padding) + content;
        case CENTER:
            int leftPad = padding / 2;
            int rightPad = padding - leftPad;
            // 最后一列丢弃尾部补白
            if (isLastColumn) {
                rightPad = 0;
            }
            return " ".repeat(leftPad) + content + " ".repeat(rightPad);
        case LEFT:
        default:
            // 最后一列不补白
            if (isLastColumn) {
                return content;
            }
            return content + " ".repeat(padding);
    }
}

/**
 * 重建块原文（用于产出上限触发 fallback）
 */
private static List<String> reconstructBlock(List<List<String>> rows) {
    List<String> block = new ArrayList<>();
    for (List<String> row : rows) {
        block.add("| " + String.join(" | ", row) + " |");
    }
    return block;
}
```

- [ ] **Step 2: 运行测试验证通过**

Run: `mvn test -pl springai-code-tui -Dtest=MarkdownTableTest#render_producesAlignedTable`
Expected: PASS

- [ ] **Step 3: 写失败测试 - 产出侧 600 行上限**

```java
@Test
void render_fallsBackWhenOutputExceeds600Lines() {
    // 构造一张表：10 行 × 每行折成 70 行 = 700 行
    List<String> block = new ArrayList<>();
    block.add("| A |");
    block.add("|---|");
    
    String longContent = "x".repeat(300); // 宽度 4 时折成 75 行
    for (int i = 0; i < 10; i++) {
        block.add("| " + longContent + " |");
    }
    
    List<List<Span>> result = MarkdownTable.render(block, 80);
    
    // 超过 600 行，应退回原样（12 行：表头+分隔+10 行数据）
    assertEquals(12, result.size());
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -pl springai-code-tui -Dtest=MarkdownTableTest#render_fallsBackWhenOutputExceeds600Lines`
Expected: PASS

- [ ] **Step 5: 运行完整 MarkdownTableTest 套件**

Run: `mvn test -pl springai-code-tui -Dtest=MarkdownTableTest`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/MarkdownTableTest.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/MarkdownTable.java
git commit -m "feat(code-tui): 完成 MarkdownTable.render 格式化输出（含 600 行上限）"
```

---

## Task 10: MarkdownRenderer 字段定义与测试文件准备

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/MarkdownRenderer.java`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/MarkdownRendererTableTest.java`
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/MarkdownTableTest.java`

- [ ] **Step 1: 在 MarkdownRenderer 中添加状态机字段定义**

在 `MarkdownRenderer` 类顶部（现有字段之后）添加：

```java
// 表格块状态机
private enum TableState { 
    IDLE,       // 空闲
    CANDIDATE,  // 候选态（第一行是 | 开头，等待分隔行）
    IN_BLOCK,   // 块内（正在收集表格行）
    DEGRADED    // 降级态（超上限，后续 | 行原样输出）
}

private TableState tableState = TableState.IDLE;
private List<String> bufferedLines = new ArrayList<>();
private int bufferedCharCount = 0;

// 缓冲上限
private static final int MAX_BUFFERED_LINES = 200;
private static final int MAX_BUFFERED_CHARS = 64 * 1024;
```

- [ ] **Step 2: 添加 hasBuffered() 方法**

```java
/**
 * 缓冲里是否压着表格（候选态也算）。
 */
public boolean hasBuffered() {
    return tableState == TableState.CANDIDATE || tableState == TableState.IN_BLOCK;
}
```

- [ ] **Step 3: 验证编译通过**

Run: `mvn compile -pl springai-code-tui`
Expected: BUILD SUCCESS

- [ ] **Step 4: 创建 MarkdownRendererTableTest 文件骨架**

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.ui.styled.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTableTest {
    private MarkdownRenderer md;
    
    @BeforeEach
    void setUp() {
        md = new MarkdownRenderer();
    }
    
    // 测试将在 Task 11-12 添加
}
```

- [ ] **Step 5: 补充 MarkdownTable 边界测试**

```java
@Test
void render_handlesSingleColumnTable() {
    List<String> block = List.of(
        "| A |",
        "|---|",
        "| 1 |"
    );
    
    List<List<Span>> result = MarkdownTable.render(block, 80);
    assertEquals(3, result.size());
}

@Test
void render_handlesHeaderOnlyTable() {
    List<String> block = List.of(
        "| A | B |",
        "|---|---|"
    );
    
    List<List<Span>> result = MarkdownTable.render(block, 80);
    // 表头 + 分隔线
    assertEquals(2, result.size());
}

@Test
void render_handlesCJKAlignment() {
    List<String> block = List.of(
        "| 参数 | 类型 |",
        "|------|------|",
        "| codetui.syncOutput | String |"
    );
    
    List<List<Span>> result = MarkdownTable.render(block, 80);
    assertEquals(3, result.size());
    assertNotNull(result);
}
```

- [ ] **Step 6: 运行测试验证通过**

Run: `mvn test -pl springai-code-tui -Dtest=MarkdownTableTest`
Expected: All tests pass

- [ ] **Step 7: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/MarkdownRenderer.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/MarkdownRendererTableTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/MarkdownTableTest.java
git commit -m "feat(code-tui): 添加 MarkdownRenderer 状态机字段定义与测试文件"
```

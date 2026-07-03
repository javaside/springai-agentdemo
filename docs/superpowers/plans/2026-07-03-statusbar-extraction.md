# StatusBar 抽取 Implementation Plan（CodeTuiView 重构 3/4）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把状态行的两段「动画型」内容渲染（波光 shimmer、压缩进度条）抽成纯函数类 `StatusBar`，使其可离线单测；分发器 `statusLine()` 留在视图。

**Architecture:** `StatusBar` 无状态、无构造依赖，只把 `(文本, animTick)` 渲染成 `dev.tamboui.text.Text`（视图侧 `richText(...)` 包裹）。视图删掉 3 个动画方法、改 3 个返回支委托 `statusBar`，其余状态行支原封不动。`animTick` 留在视图（drain 帧时钟，兼 ctx 节流）。纯搬运、零行为改动。

**Tech Stack:** Java 21、TamboUI 0.4.0（`dev.tamboui.text.{Text,Line,Span}` / `dev.tamboui.style.Style`）、JUnit 5、Maven（`mvn -o -q -pl springai-code-tui -am ...`）。

参考设计：`docs/superpowers/specs/2026-07-03-statusbar-extraction-design.md`

---

### Task 1: 新建 `StatusBar` 纯函数类（搬入 3 个动画方法）

**Files:**
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/StatusBar.java`

- [ ] **Step 1: 写出完整 StatusBar 源文件**

内容逐字如下（`shimmer`/`shimmerSpans`/`compacting` 三方法系从 `CodeTuiView` 原样搬入，仅 `shimmerSpans`/`compacting` 的 `animTick` 由字段读取改为方法入参，返回类型由 `Element`/`richText(...)` 改为直接产 `Text`）：

```java
package com.example.springai.codetui.ui;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;

import java.util.ArrayList;
import java.util.List;

import static com.example.springai.codetui.ui.Theme.DIM;
import static com.example.springai.codetui.ui.Theme.SHIMMER_HI;
import static com.example.springai.codetui.ui.Theme.THINK;

/**
 * 状态行的两种「动画」内容渲染：<b>波光</b>（处理中）与<b>压缩进度条</b>。都是
 * {@code (文本, animTick)} → {@link Text} 的<b>纯函数</b>，无状态、不认识 tamboui runner，
 * 视图侧用 {@code richText(...)} 包裹落帧。
 *
 * <p><b>为什么抽出</b>：这段逐字上色 / 三角波光带的下标算术，此前埋在 {@link CodeTuiView}
 * （{@link dev.tamboui.toolkit.app.InlineApp}）里无法单测。抽成纯函数后可喂 tick 断言高亮带位置、
 * 进度条往返。状态行的<b>分发</b>（按 picking/slash/compacting/status 挑哪条）仍留在视图——那是视图的
 * 编排职责，读的是 #4/#5/#6 的态；本类只把选中的「动画型」内容渲染出来。
 */
final class StatusBar {

    /**
     * 处理中状态行内容：{@code label} 上叠一道随 {@code animTick} 左→右扫过的高亮波光（表示系统在动），
     * {@code suffix}（如「· Esc 取消」）保持 {@link Theme#DIM 暗色}静态。{@code base} 是 label 非高亮处底色
     * （思考=THINK、跑工具=RUNNING）。
     */
    Text shimmer(String label, String suffix, Style base, long animTick) {
        List<Span> spans = shimmerSpans(label, base, animTick);
        if (!suffix.isEmpty()) spans.add(Span.styled(suffix, DIM));
        return Text.from(Line.from(spans));
    }

    /** 把 label 逐字符上色：距移动中心 ≤1 的字符用 {@link Theme#SHIMMER_HI 高亮}，其余用 {@code base}，形成左→右扫过的光带。 */
    private List<Span> shimmerSpans(String label, Style base, long animTick) {
        int n = label.length();
        List<Span> spans = new ArrayList<>(n);
        if (n == 0) return spans;
        int period = n + 6;                           // 光带扫完 + 一段间隔再重来（脉冲感）
        int center = (int) ((animTick / 2) % period); // 每 2 帧(~66ms)前进一格，避免过快闪烁
        for (int i = 0; i < n; i++) {
            spans.add(Span.styled(String.valueOf(label.charAt(i)), Math.abs(i - center) <= 1 ? SHIMMER_HI : base));
        }
        return spans;
    }

    /**
     * 压缩状态行内容：「⟳ 正在压缩会话历史…（计时）· 不可中断」+ 一段左右往返的<b>不确定型</b>进度条。
     * 库不暴露压缩进度，故只做真实经过时间 + 往返光块（不伪造百分比）。{@code elapsedNanos} 由
     * {@link ConversationState#compactElapsedNanos()} 提供，{@code animTick} 驱动往返。
     */
    Text compacting(long elapsedNanos, long animTick) {
        long sec = elapsedNanos / 1_000_000_000L;
        String elapsed = sec >= 60 ? (sec / 60) + "m " + (sec % 60) + "s" : sec + "s";
        // v1 压缩不可中断（底层库调用不可取消），明确告知用户 Esc 不会打断本次压缩。
        String label = "⟳ 正在压缩会话历史… (" + elapsed + ") · 不可中断  ";

        int width = 24;                                   // 进度条格数
        int period = width * 2;                           // 往返一轮
        int pos = (int) ((animTick / 2) % period);        // 每 2 帧前进一格
        int center = pos < width ? pos : period - pos;    // 三角波：来回移动

        List<Span> spans = new ArrayList<>(width + 1);
        spans.add(Span.styled(label, THINK));
        for (int i = 0; i < width; i++) {
            boolean lit = Math.abs(i - center) <= 1;
            spans.add(Span.styled(lit ? "▰" : "▱", lit ? SHIMMER_HI : THINK));
        }
        return Text.from(Line.from(spans));
    }
}
```

- [ ] **Step 2: 编译（此时 StatusBar 未被引用，仅验证自身可编译）**

Run: `mvn -o -q -pl springai-code-tui -am compile`
Expected: BUILD SUCCESS，无错误。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/StatusBar.java
git commit -m "refactor(code-tui): 新增 StatusBar 纯函数类（波光/压缩条内容渲染）"
```

---

### Task 2: 视图接线——`statusLine()` 委托 StatusBar，删除旧动画方法

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java`

- [ ] **Step 1: 加字段（就地 new，无构造依赖）**

在 `printer` 字段（`private final ScrollbackPrinter printer;` 一行）附近，`inputKeys` 之后新增：

```java
    private final StatusBar statusBar = new StatusBar();             // 状态行动画内容（波光/压缩条）渲染
```

- [ ] **Step 2: 改 `statusLine()` 的 3 个「动画」返回支委托 statusBar**

将现有 `statusLine()` 整体替换为（picker/slash/idle/notice 支原样不变，仅 compacting/THINKING/RUNNING_TOOL 三支改为 `richText(statusBar....)`）：

```java
    private Element statusLine() {
        if (pickingModel) return text("↑↓/kj 选择 · 1-9 快选 · Enter 确认 · Esc 取消").style(THINK);
        if (slashMenuActive()) return text("↑↓ 选择 · Tab 补全 · Enter 运行 · Esc 关闭").style(THINK);
        if (state.isCompacting()) return richText(statusBar.compacting(state.compactElapsedNanos(), animTick));   // 压缩指示器优先于普通思考/工具状态
        int q = state.queuedCount();
        String qs = q > 0 ? " · 已排队 " + q + " 条" : "";
        String notice = state.notice();
        if (!notice.isEmpty()) return text(notice + " · Ctrl+C 退出").style(THINK);
        return switch (state.status()) {
            case IDLE -> text("Enter 发送 · /model 切换模型 · Esc 取消 · Ctrl+C 退出 · " + onSubmit.currentModel() + ctxSuffix()).style(HINT);
            case THINKING -> richText(statusBar.shimmer("● 思考中…", qs + " · Esc 取消 · Ctrl+C 退出", THINK, animTick));
            case RUNNING_TOOL -> {
                String s = state.activeToolSummary();
                yield richText(statusBar.shimmer("⏺ 运行 " + state.activeTool() + (s.isEmpty() ? "" : ": " + s) + "…",
                        qs + " · Esc 取消", RUNNING, animTick));
            }
        };
    }
```

- [ ] **Step 3: 删除三个旧动画方法**

删除 `CodeTuiView` 中的 `shimmerStatus(...)`、`shimmerSpans(...)`、`compactingStatus()` 三个方法整体（含其 Javadoc）。它们的逻辑已搬入 `StatusBar`。

- [ ] **Step 4: 删除不再使用的 import**

删除以下两行 import（移除动画方法后视图不再引用 `Span`/`Line`；`Text` 仍用于 `Sink` 与 `printer.preview`，保留）：

```java
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
```

- [ ] **Step 5: 编译**

Run: `mvn -o -q -pl springai-code-tui -am compile`
Expected: BUILD SUCCESS。若报 `Span`/`Line` cannot find symbol，说明仍有残留引用——搜索 `Span`/`Line` 确认全部已随三方法删除。

- [ ] **Step 6: 跑现有离线套件确认零回归**

Run: `mvn -o -q -pl springai-code-tui -am test -Dtest='!CodingAgentSpikeTest'`
Expected: 非 spike 基线 **87** 全绿，0 失败。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java
git commit -m "refactor(code-tui): statusLine 委托 StatusBar 渲染波光/压缩条，删除旧动画方法"
```

---

### Task 3: 新增 `StatusBarTest`（纯函数单测）

**Files:**
- Create: `springai-code-tui/src/test/java/com/example/springai/codetui/ui/StatusBarTest.java`

- [ ] **Step 1: 写测试**

```java
package com.example.springai.codetui.ui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.springai.codetui.ui.Theme.THINK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StatusBar 纯函数：喂 (文本, tick) 断言渲染出的 {@link Text} —— 波光高亮带位置、压缩条往返 + 计时格式。
 * 遍历 {@code text.lines().get(0).spans()} 读回 span 文本 / 样式（同 MarkdownRendererTest 手法）。
 */
class StatusBarTest {

    private final StatusBar bar = new StatusBar();

    /** 取单行结果的 span 列表。 */
    private static List<Span> spansOf(Text t) {
        return t.lines().get(0).spans();
    }

    /** SHIMMER_HI = 亮白加粗。 */
    private static boolean isHighlight(Span s) {
        return s.style().effectiveModifiers().contains(Modifier.BOLD)
                && s.style().fg().map(c -> c.equals(Color.BRIGHT_WHITE)).orElse(false);
    }

    @Test
    void shimmer_atTickZero_highlightsLeadingBand_andAppendsDimSuffix() {
        Text t = bar.shimmer("abcdef", " · x", THINK, 0);   // n=6 → period 12；tick0 → center=0
        assertEquals("abcdef · x", t.rawContent(), "整行文本 = label + suffix");
        List<Span> spans = spansOf(t);
        assertEquals(7, spans.size(), "6 个逐字 label span + 1 个 suffix span");
        assertTrue(isHighlight(spans.get(0)), "|0-0|≤1 高亮");
        assertTrue(isHighlight(spans.get(1)), "|1-0|≤1 高亮");
        assertFalse(isHighlight(spans.get(3)), "|3-0|>1 不高亮");
        assertEquals(" · x", spans.get(6).content(), "尾 span 为 suffix");
        assertFalse(isHighlight(spans.get(6)), "suffix 为 DIM，非高亮");
    }

    @Test
    void shimmer_emptySuffix_hasNoSuffixSpan() {
        Text t = bar.shimmer("ab", "", THINK, 0);
        assertEquals("ab", t.rawContent());
        assertEquals(2, spansOf(t).size(), "空 suffix → 无尾 span");
    }

    @Test
    void shimmer_bandMovesWithTick() {
        Text t = bar.shimmer("abcdef", "", THINK, 8);   // center = (8/2) % 12 = 4
        List<Span> spans = spansOf(t);
        assertTrue(isHighlight(spans.get(3)), "|3-4|≤1 高亮");
        assertTrue(isHighlight(spans.get(4)), "|4-4|≤1 高亮");
        assertTrue(isHighlight(spans.get(5)), "|5-4|≤1 高亮");
        assertFalse(isHighlight(spans.get(0)), "|0-4|>1 不高亮");
    }

    @Test
    void compacting_containsLabel_reason_andBounceBar() {
        Text t = bar.compacting(0L, 0);
        String raw = t.rawContent();
        assertTrue(raw.contains("正在压缩会话历史"), "含压缩 label");
        assertTrue(raw.contains("不可中断"), "含不可中断提示");
        assertTrue(raw.contains("▰") || raw.contains("▱"), "含进度条格");
    }

    @Test
    void compacting_formatsElapsedOverMinute() {
        Text t = bar.compacting(75_000_000_000L, 0);   // 75s → "1m 15s"
        assertTrue(t.rawContent().contains("1m 15s"), "跨分钟计时格式");
    }

    @Test
    void compacting_formatsElapsedUnderMinute() {
        Text t = bar.compacting(5_000_000_000L, 0);    // 5s → "(5s)"
        assertTrue(t.rawContent().contains("(5s)"), "秒级计时格式");
    }
}
```

- [ ] **Step 2: 跑新测试，确认全绿**

Run: `mvn -o -q -pl springai-code-tui -am test -Dtest=StatusBarTest`
Expected: Tests run: 6, Failures: 0, Errors: 0。

- [ ] **Step 3: 跑完整离线套件确认总数**

Run: `mvn -o -q -pl springai-code-tui -am test -Dtest='!CodingAgentSpikeTest'`
Expected: 非 spike 基线 87 + 新增 6 = **93** 全绿，0 失败。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/test/java/com/example/springai/codetui/ui/StatusBarTest.java
git commit -m "test(code-tui): StatusBar 波光带位置 + 压缩条计时/往返 单测"
```

---

### Task 4: pty 实机核对（前后一致）+ 收尾

**Files:** 无（验证 + 打包）

- [ ] **Step 1: 打包（务必重打，避免跑旧 jar）**

Run: `mvn -o -q -pl springai-code-tui -am package -DskipTests`
Expected: 生成 `springai-code-tui/target/springai-code-tui.jar`。

- [ ] **Step 2: pty+pyte 探针核对状态行渲染**

用现有 pty 探针脚本（`DEEPSEEK_API_KEY=test-fake` 起 jar，`fcntl.ioctl` 设 winsize，冷启动约 6–8s）
截屏底部状态行：空闲行应显示「Enter 发送 · /model 切换模型 …」+ 模型名 + 上下文百分比；触发一个回合观察
「● 思考中…」波光扫过（`38;5;…` 高亮随帧移动）与工具行「⏺ 运行 …」。与重构前逐帧对照，须一致（纯搬运）。
若 DeepSeek 无真 key 无法触发真实回合，至少核对空闲行与启动横幅渲染无异常。

- [ ] **Step 3: 最终整体审查 + 完成分支**

按 subagent-driven-development 收尾：派最终 code reviewer 整体审 `StatusBar` + 视图改动是否契合设计与零行为改动；
通过后用 superpowers:finishing-a-development-branch 合并回 main（`--no-ff`，沿用本项目本地累积惯例）。

---

## 备注

- **基线数**：非 spike 离线基线为 87（见 #2 记录：全 88 − 5 live-key `CodingAgentSpikeTest` = 83，#2 新增 4 → 87）。本步新增 6 → 93。
- **animTick 不动**：`drain()` 的 `animTick++` 与 `% 30` ctx 节流保持原样；`animTick` 仍是视图字段。
- **表示层等价**：仅 shimmer/compacting 两支涉及返回类型改动，二者原本即返回 `richText(Text.from(...))`，搬运后逐字节等价；idle/notice/picker/slash 简单支未触及。

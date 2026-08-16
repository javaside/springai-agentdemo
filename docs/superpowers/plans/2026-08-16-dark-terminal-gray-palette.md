# 深色终端灰阶配色 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把界面里 ANSI 亮黑（深色终端下与背景同色）的次要文字换成可控的 256 色灰阶，并加一道防回归的护栏。

**Architecture:** `Theme` 里固化三档灰阶命名常量（250 / 248 / 244），`Theme` 与同包的 `MarkdownRenderer` 一律引用它们；新增反射扫调色板的 `ThemeContrastTest` 断言「无 truecolor、无 ANSI 黑/亮黑前景、中性色前景对比度 ≥ 3:1」；`stream_box_smoke.py` 加一条 pty 实机断言，证明颜色确实走到了终端。

**Tech Stack:** Java 17 / JUnit 5 / tamboui 0.4.0（`dev.tamboui.style.Color`、`Style`）/ Python 3 + pyte（pty 冒烟）

**设计依据：** `docs/superpowers/specs/2026-08-16-dark-terminal-gray-palette-design.md`

---

## 背景速查（实施前必读）

已用探针实测确认的事实，写代码时直接用，别再猜：

| 事实 | 值 |
| --- | --- |
| `Color.DARK_GRAY` 的本体 | ANSI `BRIGHT_BLACK` |
| `Color.DARK_GRAY.toAnsiForeground()` | `"90"`（只有参数，不含 ESC 与 `m`） |
| `Color.BLACK.toAnsiForeground()` | `"30"` |
| `Color.indexed(244).toAnsiForeground()` | `"38;5;244"` |
| `Color.rgb(120,150,200).toAnsiForeground()` | `"38;2;120;150;200"` |
| `Color.DARK_GRAY.toRgb()` | `(85, 85, 85)` |
| `Color.indexed(244).toRgb()` | `(128, 128, 128)` |
| `Color.indexed(248).toRgb()` | `(168, 168, 168)` |
| `Color.indexed(110).toRgb()` | `(135, 175, 215)` |
| `Color.RED.toRgb()` | `(170, 0, 0)`（亮度极低，故对比度规则只卡中性色） |
| `Color.indexed(244) instanceof Color.Rgb` | `false`（只有 `Color.rgb()/hex()` 才是 `Rgb`） |
| `Color.DARK_GRAY.equals(Color.ansi(AnsiColor.BRIGHT_BLACK))` | **`false`** —— 不能用 `equals` 判亮黑，要比 `toAnsiForeground()` |
| 终端上实际发出的形态 | **`ESC[0;90m`**，不是 `ESC[90m` |

最后一条是坑：`b"\x1b[90m"` 子串断言在真实字节流里**恒为 0 次**（实测 v1.14.0 流式 422KB 中 `ESC[90m` 出现 0 次、`ESC[0;90m` 出现 276 次），照那样写就是一条永远绿的假测试。

**验证命令**（来自 `CONTRIBUTING.md`，`-am` 不可省——`springai-code-tui` 依赖同仓库的 `springai-tamboui-inline-patch`）：

```bash
mvn -pl springai-code-tui -am test -Dtest='ThemeContrastTest' -Dsurefire.failIfNoSpecifiedTests=false
```

---

## 文件结构

| 文件 | 职责 | 动作 |
| --- | --- | --- |
| `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ThemeContrastTest.java` | 调色板护栏：无 truecolor / 无 ANSI 黑 / 中性色对比度下限 | 新建 |
| `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/Theme.java` | 灰阶三档常量 + 全部近黑常量改指向 | 修改 |
| `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/MarkdownRenderer.java` | 代码块语言标注 / 引用块 / 左边栏三处颜色 | 修改 |
| `springai-code-tui/src/test/resources/scripts/stream_box_smoke.py` | 加一条「流式期间不得出现 ANSI 黑/亮黑前景」的实机断言 | 修改 |

---

## Task 1: 写下调色板护栏单测（预期红）

**Files:**
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ThemeContrastTest.java`

- [ ] **Step 1: 写失败的测试**

新建 `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ThemeContrastTest.java`，完整内容：

```java
package io.github.javaside.springai.codetui.ui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调色板的深色终端护栏：不许再出现「颜色由终端说了算」或「暗到看不见」的取值。
 *
 * <p><b>为什么需要</b>：{@code Color.DARK_GRAY} 实际是 ANSI 亮黑（SGR 90），而 ANSI 0–15 的取值
 * 由用户终端 profile 决定——同一个 90 在不同配色下从 {@code #333} 到 {@code #666} 不等，代码侧
 * 控制不了，深色窗口里就是「看不见」。同理 {@code Color.rgb(...)} 发的是 {@code 38;2;r;g;b}，
 * 目标终端（Apple Terminal，{@code COLORTERM} 为空）不支持 truecolor，整段被静默忽略。
 *
 * <p><b>为什么对比度只卡中性色</b>：{@code ERROR}/{@code FAIL}/{@code MODE_BYPASS} 用 ANSI 红
 * （{@code (170,0,0)}），相对亮度天然很低，靠<b>色相</b>而不是明度区分。一刀切的亮度阈值会把它们
 * 全部误伤，所以只对 {@code max-min ≤ 16} 的中性色（灰阶）设下限。
 */
class ThemeContrastTest {

    /** 参考底色 #1e1e1e：深色终端的常见背景。纯黑会高估对比度，故取偏亮的一档。 */
    private static final Color.Rgb REFERENCE_BG = new Color.Rgb(30, 30, 30);
    /** 可读下限。3:1 对应灰阶 242（#6c6c6c，实测 3.18:1）；亮黑按 #555 算只有 2.24:1。 */
    private static final double MIN_CONTRAST = 3.0;
    /** 中性色判定：三通道极差不超过这个值即视为灰。 */
    private static final int NEUTRAL_TOLERANCE = 16;

    private static final List<Class<?>> PALETTES =
            List.of(Theme.class, MarkdownRenderer.class, SyntaxHighlighter.class);

    @Test
    @DisplayName("调色板不得使用 truecolor：目标终端 COLORTERM 为空，38;2 会被静默忽略")
    void paletteNeverUsesTrueColor() {
        List<String> bad = new ArrayList<>();
        forEachStyle((owner, style) -> {
            style.fg().filter(c -> c instanceof Color.Rgb).ifPresent(c -> bad.add(owner + " 的前景"));
            style.bg().filter(c -> c instanceof Color.Rgb).ifPresent(c -> bad.add(owner + " 的底色"));
        });
        forEachColor((owner, color) -> {
            if (color instanceof Color.Rgb) bad.add(owner);
        });
        assertTrue(bad.isEmpty(),
                "这些颜色用了 truecolor，在目标终端上不会生效，改用 Color.indexed(...)：" + bad);
    }

    @Test
    @DisplayName("前景不得用 ANSI 黑/亮黑：取值由终端 profile 决定，深色窗口下常与背景同色")
    void foregroundNeverUsesAnsiBlack() {
        List<String> bad = new ArrayList<>();
        forEachStyle((owner, style) -> style.fg().ifPresent(c -> {
            String sgr = c.toAnsiForeground();
            // ⚠ 不能用 equals 判：Color.DARK_GRAY 是 Named 包着 Ansi，
            // 与 Color.ansi(AnsiColor.BRIGHT_BLACK) 实测 equals == false。
            if ("30".equals(sgr) || "90".equals(sgr)) bad.add(owner + "（SGR " + sgr + "）");
        }));
        assertTrue(bad.isEmpty(),
                "这些前景用了 ANSI 黑/亮黑，深色终端下看不见，改用 Theme 的灰阶三档：" + bad);
    }

    @Test
    @DisplayName("中性色前景对其背景的对比度不低于 3:1")
    void neutralForegroundsAreReadable() {
        List<String> bad = new ArrayList<>();
        forEachStyle((owner, style) -> {
            Optional<Color> fg = style.fg();
            if (fg.isEmpty()) return;
            Color.Rgb rgb = fg.get().toRgb();
            if (!isNeutral(rgb)) return;   // 饱和色靠色相区分，不按亮度卡
            Color.Rgb bg = style.bg().map(Color::toRgb).orElse(REFERENCE_BG);
            double ratio = contrast(rgb, bg);
            if (ratio < MIN_CONTRAST) {
                bad.add(String.format("%s 对比度 %.2f:1", owner, ratio));
            }
        });
        assertTrue(bad.isEmpty(),
                "这些中性色前景在深色终端下达不到 " + MIN_CONTRAST + ":1，请提亮：" + bad);
    }

    // ── 反射遍历 ────────────────────────────────────────────────────────
    private static void forEachStyle(BiConsumer<String, Style> visitor) {
        forEachStaticField(Style.class, (owner, value) -> visitor.accept(owner, (Style) value));
    }

    private static void forEachColor(BiConsumer<String, Color> visitor) {
        forEachStaticField(Color.class, (owner, value) -> visitor.accept(owner, (Color) value));
    }

    private static void forEachStaticField(Class<?> type, BiConsumer<String, Object> visitor) {
        for (Class<?> palette : PALETTES) {
            for (Field field : palette.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (!type.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(null);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("读不到 " + palette.getSimpleName() + "." + field.getName(), e);
                }
                if (value == null) continue;
                visitor.accept(palette.getSimpleName() + "." + field.getName(), value);
            }
        }
    }

    // ── WCAG 相对亮度 / 对比度 ───────────────────────────────────────────
    private static boolean isNeutral(Color.Rgb c) {
        int max = Math.max(c.r(), Math.max(c.g(), c.b()));
        int min = Math.min(c.r(), Math.min(c.g(), c.b()));
        return max - min <= NEUTRAL_TOLERANCE;
    }

    private static double contrast(Color.Rgb a, Color.Rgb b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color.Rgb c) {
        return 0.2126 * channel(c.r()) + 0.7152 * channel(c.g()) + 0.0722 * channel(c.b());
    }

    private static double channel(int value) {
        double s = value / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
```

- [ ] **Step 2: 跑测试，确认红且理由正确**

```bash
mvn -pl springai-code-tui -am test -Dtest='ThemeContrastTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望 **3 个用例全红**，且失败清单正好是下面这些（不多不少——多出来说明扫到了不该扫的字段，少了说明反射没遍历到）：

| 用例 | 期望清单 |
| --- | --- |
| `paletteNeverUsesTrueColor` | 只含 `MarkdownRenderer.GUTTER 的前景` |
| `foregroundNeverUsesAnsiBlack` | `Theme.DIM`、`Theme.PICK_DESC`、`Theme.DIFF_NO_CTX`、`Theme.DIFF_TRUNC`、`MarkdownRenderer.DIM`、`MarkdownRenderer.QUOTE`，各带 `（SGR 90）` |
| `neutralForegroundsAreReadable` | 同上 6 个，各报 `对比度 2.24:1`（亮黑的 `toRgb()` = `(85,85,85)`） |

`MarkdownRenderer.GUTTER` 不出现在后两个清单里是对的：`(120,150,200)` 三通道极差 80 > 16，不是中性色。

这三张清单是<b>探针实测</b>出来的，不是估计：按上面三条规则扫当前工作树，共 51 个 `Style` 字段 + 3 个 `Color` 字段，命中的<b>只有</b>上面这些。也就是说本计划改完之后三条规则应当全绿，不会有别的字段被顺带判红——若你跑出了清单以外的项，说明反射范围或规则实现与本计划不一致。

⚠ 本步骤**不提交**：红测试不入库，Task 3 一起提交。

---

## Task 2: Theme 灰阶三档

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/Theme.java`

- [ ] **Step 1: 改类 javadoc 的可读性说明**

把这两行（原文件第 14–15 行）：

```java
 * <p><b>暗色终端可读性</b>：正文/信息行避开近黑的 {@code DARK_GRAY}，改用可读灰白（indexed 248/250）；
 * {@code DARK_GRAY} 仅留给固定区的次要装饰（待办○、diff 上下文行号）。
```

替换为：

```java
 * <p><b>暗色终端可读性</b>：灰色一律走 256 色灰阶三档（{@link #GRAY_TEXT} / {@link #GRAY_INFO} /
 * {@link #GRAY_MUTED}），不用 ANSI 0–15 里的黑与亮黑——那两档的实际取值由用户终端 profile 决定，
 * 深色窗口下常与背景同色。取值下限由 {@code ThemeContrastTest} 守。
```

- [ ] **Step 2: 加灰阶三档常量，并让 DIM / HINT / INFO_LINE 指过去**

把这一段（原文件第 21–27 行）：

```java
    // 配色（层次感）：用户输入=灰色次要，AI 回复=默认亮色（重点）
    static final Style DIM        = Style.create().fg(Color.DARK_GRAY);
    // 占位符/空态提示（输入框空态、状态栏空闲行）：用灰白而非近黑的 DARK_GRAY，暗色终端下更清晰可读
    static final Style HINT       = Style.create().fg(Color.indexed(250));
    // 下沉到 scrollback 的信息行（/context 统计、/help、⚙ 模型、压缩结果等）：同样避开近黑的 DIM，
    // 用可读的灰白（略深于 HINT，作为「内容而非提示」的层次）。DIM 仍留给固定区的次要元素（待办○、状态后缀）。
    static final Style INFO_LINE  = Style.create().fg(Color.indexed(248));
```

替换为：

```java
    // ── 灰阶三档 ───────────────────────────────────────────────────────
    // 取值一律来自 256 色灰阶区（232–255）：各家终端 profile 基本不改这一段，是可控的；
    // ANSI 0–15 则由 profile 说了算（亮黑常与深色背景同色，正是「界面很多字看不见」的根源）。
    // 括号里是对参考底 #1e1e1e 的对比度，下限 3:1（= 灰阶 242），由 ThemeContrastTest 守。
    static final Color GRAY_TEXT  = Color.indexed(250);   // #bcbcbc 8.8:1  提示 / 空态
    static final Color GRAY_INFO  = Color.indexed(248);   // #a8a8a8 7.0:1  信息行 / 引用正文
    static final Color GRAY_MUTED = Color.indexed(244);   // #808080 4.2:1  装饰性最次要

    // 配色（层次感）：用户输入=灰色次要，AI 回复=默认亮色（重点）
    static final Style DIM        = Style.create().fg(GRAY_MUTED);
    // 占位符/空态提示（输入框空态、状态栏空闲行）
    static final Style HINT       = Style.create().fg(GRAY_TEXT);
    // 下沉到 scrollback 的信息行（/context 统计、/help、⚙ 模型、压缩结果等）：略深于 HINT，
    // 作为「内容而非提示」的层次。DIM 更次一档，留给固定区的装饰性元素（待办○、状态行后缀）。
    static final Style INFO_LINE  = Style.create().fg(GRAY_INFO);
```

- [ ] **Step 3: 改 PICK_DESC**

把（原文件第 45 行）：

```java
    static final Style PICK_DESC   = Style.create().fg(Color.DARK_GRAY);                   // 项说明
```

替换为：

```java
    static final Style PICK_DESC   = Style.create().fg(GRAY_MUTED);                        // 项说明
```

- [ ] **Step 4: 改 diff 的两个常量**

把（原文件第 82–83 行）：

```java
    static final Style DIFF_NO_CTX = Style.create().fg(Color.DARK_GRAY);                 // 上下文行号=暗灰
    static final Style DIFF_TRUNC  = Style.create().fg(Color.DARK_GRAY);
```

替换为：

```java
    static final Style DIFF_NO_CTX = Style.create().fg(GRAY_MUTED);                      // 上下文行号=次要灰
    static final Style DIFF_TRUNC  = Style.create().fg(GRAY_MUTED);
```

- [ ] **Step 5: 改 styleFor 里那句已经过时的注释**

把（原文件第 95 行）：

```java
            case INFO -> INFO_LINE;   // 灰白，暗色终端可读（原 DIM=DARK_GRAY 近黑看不清）
```

替换为：

```java
            case INFO -> INFO_LINE;   // 灰白，暗色终端可读（见 GRAY_INFO）
```

- [ ] **Step 6: 确认 Theme 里再无 DARK_GRAY**

```bash
grep -n "DARK_GRAY" springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/Theme.java
```

期望：只剩 `TOOL` 那行行尾的历史注释「原 DARK_GRAY 近黑看不清」（那是对过去的陈述，保留）。不得再有 `fg(Color.DARK_GRAY)`。

- [ ] **Step 7: 跑测试，确认只剩 MarkdownRenderer 的失败项**

```bash
mvn -pl springai-code-tui -am test -Dtest='ThemeContrastTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望仍红，但清单缩小到只剩 `MarkdownRenderer.DIM`、`MarkdownRenderer.QUOTE`（黑/对比度两条）与 `MarkdownRenderer.GUTTER 的前景`（truecolor 一条）。所有 `Theme.*` 项必须消失——没消失说明改漏了。

⚠ 本任务**不提交**：Task 3 一起提交。

---

## Task 3: MarkdownRenderer 换色并提交

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/MarkdownRenderer.java:23,28,29`

- [ ] **Step 1: 三处样式常量改掉**

把（原文件第 23、28、29 行）：

```java
    private static final Style DIM = Style.create().fg(Color.DARK_GRAY);
```

```java
    private static final Style QUOTE = Style.create().fg(Color.DARK_GRAY).italic();
    private static final Style GUTTER = Style.create().fg(Color.rgb(120, 150, 200)); // 代码块左边栏
```

分别替换为：

```java
    private static final Style DIM = Style.create().fg(Theme.GRAY_MUTED);
```

```java
    // 引用块是模型回复的<b>正文内容</b>，不是界面装饰：与信息行同级（GRAY_INFO），
    // 与正文的区分交给斜体与行首标记，不靠压暗。
    private static final Style QUOTE = Style.create().fg(Theme.GRAY_INFO).italic();
    // 代码块左边栏。⚠ 不能用 Color.rgb()：目标终端（Apple Terminal，COLORTERM 为空）不支持
    // truecolor，38;2;r;g;b 被静默忽略、左边栏退回默认前景色。110 = #87afd7，是 256 色区里
    // 最接近原 rgb(120,150,200) 的一档。
    private static final Style GUTTER = Style.create().fg(Color.indexed(110));
```

`Color` 的 import 保留：`HEADER`/`INLINE_CODE`/`GUTTER` 仍在用。

- [ ] **Step 2: 跑测试，确认全绿**

```bash
mvn -pl springai-code-tui -am test -Dtest='ThemeContrastTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：`Tests run: 3, Failures: 0, Errors: 0`。

- [ ] **Step 3: 确认全仓再无 DARK_GRAY 引用与 truecolor**

```bash
grep -rn "fg(Color.DARK_GRAY)\|Color.rgb(\|Color.hex(" springai-code-tui/src/main/java
```

期望：无输出。

- [ ] **Step 4: 跑全模块单测，确认没打破别处**

```bash
mvn -pl springai-code-tui -am test
```

期望：`Tests run: 1513, Failures: 0, Errors: 0`（1510 + 本次 3 条；数字随仓库演进浮动，关键是 Failures/Errors 为 0）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/Theme.java         springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/MarkdownRenderer.java         springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ThemeContrastTest.java
git commit -m "fix(tui): 次要文字改用 256 色灰阶，深色终端下不再看不见"
```

---

## Task 4: pty 实机断言

**Files:**
- Modify: `springai-code-tui/src/test/resources/scripts/stream_box_smoke.py`

- [ ] **Step 1: 加 SGR 解析与计数函数**

在常量区 `CHECKPOINT = ...` 那一行后面加一行：

```python
SGR = re.compile(rb"\x1b\[([0-9;]*)m")
```

并在 `def box_counts(screen):` 之前插入：

```python
def ansi_black_hits(raw):
    """原始字节流里把<b>前景</b>设成 ANSI 黑/亮黑的次数。

    ⚠ 不能拿 ESC[90m 做子串匹配：AnsiCellWriter 发的是<b>合并形态</b> ESC[0;90m，
    子串永远命中不到（实测 v1.14.0 流式 422KB 中 ESC[90m 出现 0 次、ESC[0;90m 出现 276 次），
    照那样写就是一条永远绿的假断言。必须按分号切参数，并跳过 38;5;N 与 38;2;r;g;b 的内层数字，
    否则 indexed(30) 会被误判成 ANSI 黑。
    """
    hits = 0
    for m in SGR.finditer(raw):
        params = m.group(1).split(b";")
        i = 0
        while i < len(params):
            token = params[i]
            if token in (b"38", b"48") and i + 1 < len(params):
                if params[i + 1] == b"5":
                    i += 3
                    continue
                if params[i + 1] == b"2":
                    i += 5
                    continue
            if token in (b"30", b"90"):
                hits += 1
            i += 1
    return hits
```

- [ ] **Step 2: 在 main 里加断言**

在 `print("无重影 OK: ...")` 那一行之后、`text = session.screen_text()` 之前插入：

```python
        black = ansi_black_hits(raw)
        if black:
            rs.die("流式期间 %d 次把前景设成 ANSI 黑/亮黑——ANSI 0–15 由终端 profile 决定，"
                   "深色窗口下常与背景同色；次要文字必须走 256 色灰阶（见 Theme 的三档）" % black,
                   list(session.screen.display))
        print("无亮黑 OK: 流式期间未把前景设成 ANSI 黑/亮黑")
```

忙碌态状态行后缀用的正是 `Theme.DIM`，所以这条断言真的会被执行到——不是摆设。

- [ ] **Step 3: 在脚本头部 docstring 里补上这条**

在 docstring 的编号列表后面（`2)` 那段之后、`<b>为什么单测不够</b>` 之前）加一段：

```
  3. <b>「次要文字在深色窗口里看不见」</b>。ANSI 亮黑（SGR 90）的实际取值由终端 profile 决定，
     深色配色下常与背景同色。单测（ThemeContrastTest）只证调色板的取值，这里证颜色确实按
     256 色灰阶发到了终端。
```

- [ ] **Step 4: 重新构建并跑**

```bash
mvn -q -pl springai-code-tui -am package -DskipTests
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/stream_box_smoke.py
```

期望输出四行 OK 后 `SMOKE PASS`：

```
无重影 OK: 逐游标动作重放，全程只有一条输入框边框
无亮黑 OK: 流式期间未把前景设成 ANSI 黑/亮黑
正文完整 OK: 12 行流式正文全部在屏
静态单框 OK: 回合结束后输入框完整
SMOKE PASS
```

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/test/resources/scripts/stream_box_smoke.py
git commit -m "test(tui): 实机断言流式期间不出现 ANSI 亮黑前景"
```

---

## Task 5: 变异实测（不提交）

两处修复必须各自被<b>不同的</b>断言、为<b>正确的理由</b>判红，且互不遮蔽。

- [ ] **Step 1: 变异一——把 DIM 改回亮黑**

把 `Theme.java` 里 `static final Style DIM        = Style.create().fg(GRAY_MUTED);`
临时改成 `static final Style DIM        = Style.create().fg(Color.DARK_GRAY);`，然后：

```bash
mvn -pl springai-code-tui -am test -Dtest='ThemeContrastTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：`foregroundNeverUsesAnsiBlack` 与 `neutralForegroundsAreReadable` 红，清单只含 `Theme.DIM`。

- [ ] **Step 2: 变异一也要被 pty 抓到**

```bash
mvn -q -pl springai-code-tui -am package -DskipTests
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/stream_box_smoke.py
```

期望：`SMOKE FAIL: 流式期间 N 次把前景设成 ANSI 黑/亮黑…`（N > 0）。**必须是这条判红，不能是"重影"或"正文完整"那条**——否则说明断言挑错了地方。

- [ ] **Step 3: 还原变异一**

```bash
git checkout springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/Theme.java
touch springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/Theme.java
```

`touch` 不可省：还原后文件 mtime 早于 class 文件，Maven 会跳过重编译、继续跑变异过的字节码，表现成假失败。

- [ ] **Step 4: 变异二——把 GUTTER 改回 truecolor**

把 `MarkdownRenderer.java` 里 `private static final Style GUTTER = Style.create().fg(Color.indexed(110));`
临时改回 `private static final Style GUTTER = Style.create().fg(Color.rgb(120, 150, 200));`，然后：

```bash
mvn -pl springai-code-tui -am test -Dtest='ThemeContrastTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：只有 `paletteNeverUsesTrueColor` 红，清单只含 `MarkdownRenderer.GUTTER 的前景`。另两条必须绿——它们红就说明规则互相遮蔽了。

- [ ] **Step 5: 还原变异二并确认恢复全绿**

```bash
git checkout springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/MarkdownRenderer.java
touch springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/MarkdownRenderer.java
mvn -pl springai-code-tui -am test -Dtest='ThemeContrastTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl springai-code-tui -am package -DskipTests
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/stream_box_smoke.py
```

期望：单测 3 条全绿，冒烟 `SMOKE PASS`。

- [ ] **Step 6: 确认工作区干净**

```bash
git status --short
```

期望：无输出（变异全部还原、无残留）。

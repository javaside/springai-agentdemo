# ScrollbackPrinter 抽取 实施计划（CodeTuiView 重构 2/4）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 纯搬运重构，零行为改动。既有离线套件 89/89 是回归安全网，须全程保持全绿。

**Goal:** 把 CodeTuiView 的 scrollback 打印簇抽成 `ScrollbackPrinter`，视图瘦到 ~700 行，并让 markdown/diff 渲染可单测。

**Architecture:** 新增 package-private `ScrollbackPrinter`，持有 `md`+`diff`，经两方法的 `Sink` 接缝输出
（视图匿名类惰性桥接 `runner()`，兼作单测接缝）。`md` 整体移入（finalized + preview 同源）。共享辅助里只有
`wrapSegments` 以 `BiFunction` 注入，`displayWidth`/`INDENT` 各自私有，**不新建工具类**。

**Tech Stack:** Java 21、TamboUI 0.4.0（InlineApp/InlineToolkitRunner）、JUnit 5、Maven（`mvn -o -pl springai-code-tui -am`）。

设计见 `docs/superpowers/specs/2026-07-03-scrollback-printer-extraction-design.md`。

---

## Task 1: 新建 `ScrollbackPrinter`（自包含，先不接线）

**Files:**
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/ScrollbackPrinter.java`

本步只新增文件，视图暂不改。文件编译通过即可（此时未被引用）。

- [ ] **Step 1: 写全 `ScrollbackPrinter.java`**

```java
package com.example.springai.codetui.ui;

import com.example.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;

import static com.example.springai.codetui.ui.Theme.*;

/**
 * scrollback 打印器：把一条 agent/系统输出（欢迎横幅 / 用户回显 / 助手 markdown / 工具 diff）
 * 渲染成带样式的 {@link Text} 并 {@code println} 进 scrollback。从 {@link CodeTuiView} 抽出，使视图只管
 * 布局 + 输入 + 状态行，且让 markdown/diff 渲染可脱离 {@code InlineApp} 单测。
 *
 * <p><b>输出接缝 {@link Sink}</b>：printer 不认识 tamboui 的 {@code InlineToolkitRunner}，只依赖一个两方法的
 * {@code Sink}。既让渲染逻辑可脱离 {@code InlineApp} 单测（测试传记录型 Sink），又解决时序：视图用匿名 Sink
 * 把 {@code runner()} 惰性桥接进来——构造时不解引用，所有 println 都在 {@code onStart} 之后才触发。除
 * {@link #preview} 只用 {@code md} 外，无 println 早于 runner 就绪。故无需 null 守卫。
 *
 * <p><b>{@code md} 归属</b>：markdown 渲染是单一职责，finalized（scrollback 行）与 preview（未完成残行的
 * 实时预览）同源、共享代码围栏状态，故整体归本类；视图 render() 的预览改走 {@link #preview}。
 */
final class ScrollbackPrinter {

    /** 输出接缝：真实=终端 scrollback（视图桥接 runner().println），测试=内存列表。 */
    interface Sink {
        void println(Text line);
        void println(String line);
    }

    private static final String INDENT = "  ";   // 对话内容缩进；工具/计划行自带前缀
    private static final int GUTTER = 4;         // 行号列宽（右对齐到 4 位，够 9999 行）

    private final Sink sink;                                        // 输出接缝（视图惰性桥接 runner）
    private final Path root;                                        // 工作区根（欢迎横幅 cwd + diff 构造）
    private final IntSupplier terminalWidth;                        // 终端列宽
    private final BiFunction<String, Integer, List<String>> wrap;  // 软折行（与视图共用同一实现，注入）
    private final MarkdownRenderer md = new MarkdownRenderer();     // AI 正文 markdown + 代码语法高亮
    private final DiffRenderer diff;                                // edit/write → 带真实行号的 diff 行

    ScrollbackPrinter(Sink sink, Path root,
                      IntSupplier terminalWidth, BiFunction<String, Integer, List<String>> wrap) {
        this.sink = sink;
        this.root = root;
        this.terminalWidth = terminalWidth;
        this.wrap = wrap;
        this.diff = new DiffRenderer(root);
    }

    // ── 公开 API ─────────────────────────────────────────────────────────

    /** 流式当前残行的预览（带缩进）；供视图 render() 直接塞进 richText。只用 md、不碰 runner。 */
    Text preview(String tail) {
        return indented(md.renderPreview(tail));
    }

    /** 圆角欢迎横幅（仿 Claude Code），下沉到 scrollback 顶部。model 由调用方传入（printer 不依赖 SubmitHandler）。 */
    void welcome(String model) {
        Sink r = sink;
        int w = Math.min(Math.max(terminalWidth.getAsInt() - 1, 48), 76);
        String bar = "─".repeat(Math.max(0, w - 2));
        r.println(Text.styled("╭" + bar + "╮", WELCOME_BORDER));
        welcomeLine(w, "✻ ", "Spring AI Code TUI", WELCOME_TITLE);
        welcomeLine(w, "  ", "", WELCOME_BODY);
        welcomeLine(w, "  ", "基于 DeepSeek 的编码智能体 · " + model, WELCOME_BODY);
        welcomeLine(w, "  ", "Enter 发送 · \\+Enter 换行 · /model 切换模型 · Esc 取消 · Ctrl+C 退出", WELCOME_HINT);
        welcomeLine(w, "  ", "", WELCOME_BODY);
        welcomeLine(w, "  ", "cwd: " + root, WELCOME_HINT);
        r.println(Text.styled("╰" + bar + "╯", WELCOME_BORDER));
        r.println("");   // 与后续对话留白
    }

    /** 用户消息：灰底白字块，仿 Claude Code。按终端宽度软折行，每行右侧补白使灰底铺满整行。新回合先重置 md 围栏态。 */
    void userBlock(String text) {
        md.reset();   // 新回合：清 markdown 代码围栏状态（原在 drain 的 USER 分支）
        Sink r = sink;
        int width = terminalWidth.getAsInt();
        int inner = Math.max(1, width - displayWidth(INDENT));
        for (String logical : text.split("\n", -1)) {
            for (String seg : wrap.apply(logical, inner)) {
                int pad = Math.max(0, inner - displayWidth(seg));
                r.println(Text.from(Line.from(
                        Span.styled(INDENT, USER_BLOCK),
                        Span.styled(seg, USER_BLOCK),
                        Span.styled(" ".repeat(pad), USER_BLOCK))));
            }
        }
    }

    /** AI 正文：markdown/语法高亮 + 缩进，下沉 scrollback。 */
    void assistant(String text) {
        sink.println(indented(md.renderFinalized(text)));
    }

    /** 流式完整行：同 assistant。 */
    void streamingLine(String row) {
        sink.println(indented(md.renderFinalized(row)));
    }

    /** 工具开始：edit/write 展开成 diff 块（真实行号、语法高亮 + 增删底色）；其余工具单行摘要。 */
    void toolStart(OutputLine ol) {
        List<DiffRenderer.DiffLine> lines =
                (ol.raw() == null) ? List.of() : diff.render(ol.toolName(), ol.raw());
        if (lines.isEmpty()) {                                      // 非文件写入 / 无法解析：回退单行摘要
            sink.println(Text.styled(ol.text(), TOOL));
            return;
        }
        int width = terminalWidth.getAsInt();
        String lang = langOf(lines);        // 从 header 的路径推断语言
        boolean inBlock = false;            // 跨行块注释状态，按 body 顺序推进
        for (DiffRenderer.DiffLine dl : lines) {
            List<Span> hl = null;
            if (dl.type() != DiffRenderer.Type.HEADER && dl.type() != DiffRenderer.Type.TRUNCATED) {
                SyntaxHighlighter.Result rr = SyntaxHighlighter.highlight(dl.text(), lang, inBlock);
                inBlock = rr.stillInBlockComment();
                hl = rr.spans();
            }
            sink.println(diffLine(dl, hl, width));
        }
    }

    /** drain 的 default 分支：工具/Todo/错误等单色贴左；{@code styleFor} 返回 null（ASSISTANT）则原样。 */
    void line(OutputLine ol) {
        Style st = styleFor(ol.kind());
        if (st == null) sink.println(ol.text());
        else sink.println(Text.styled(ol.text(), st));
    }

    // ── 私有渲染细节（原视图私有方法，逐字搬运） ─────────────────────────

    /** 组一行欢迎框内容：{@code │ + 前缀内容(截断/补白到内宽) + │}。 */
    private void welcomeLine(int w, String prefix, String body, Style contentStyle) {
        int inner = Math.max(1, w - 2);
        String content = " " + prefix + body;                     // 左侧留一空格
        if (displayWidth(content) > inner) {                      // 过长（如深路径）：按显示宽度截断
            content = CharWidth.substringByWidth(content, inner - 1) + "…";
        }
        int pad = Math.max(0, inner - displayWidth(content));
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled("│", WELCOME_BORDER));
        spans.add(Span.styled(content, contentStyle));
        if (pad > 0) spans.add(Span.raw(" ".repeat(pad)));
        spans.add(Span.styled("│", WELCOME_BORDER));
        sink.println(Text.from(Line.from(spans)));
    }

    /** 把一行 DiffLine 渲染成整行 Text；ADD/DEL 底色铺满整行（含行号列），上下文行只高亮不上底色。 */
    private static Text diffLine(DiffRenderer.DiffLine dl, List<Span> hl, int width) {
        return switch (dl.type()) {
            case HEADER -> Text.from(Line.from(Span.raw(INDENT), Span.styled(dl.text(), DIFF_HEADER)));
            case TRUNCATED -> Text.from(Line.from(
                    Span.raw(INDENT), Span.styled(gutter(null) + "  " + dl.text(), DIFF_TRUNC)));
            case CONTEXT -> bodyLine(gutter(dl.newNo() != null ? dl.newNo() : dl.oldNo()), " ",
                    hl, DIFF_NO_CTX, null, width);
            case ADD -> bodyLine(gutter(dl.newNo()), "+", hl, DIFF_NO_ADD, ADD_BG, width);
            case DEL -> bodyLine(gutter(dl.oldNo()), "-", hl, DIFF_NO_DEL, DEL_BG, width);
        };
    }

    /**
     * 组装一行 diff 主体：{@code 行号 + 符号 + 高亮内容}。bg 非 null（ADD/DEL）时左缩进/行号/符号/内容/右侧补白
     * 全部叠加底色，形成从左到右铺满整行的色带；bg 为 null（CONTEXT）时无底色，仅语法高亮。
     */
    private static Text bodyLine(String num, String sign, List<Span> content,
                                 Style numStyle, Color bg, int width) {
        List<Span> spans = new ArrayList<>();
        spans.add(bg == null ? Span.raw(INDENT) : Span.styled(INDENT, Style.create().bg(bg)));
        spans.add(Span.styled(num, numStyle));
        spans.add(Span.styled(" " + sign + " ", numStyle));
        int used = displayWidth(INDENT) + displayWidth(num) + 3;   // 3 = " " + sign + " "
        for (Span s : content) {
            spans.add(bg == null ? s : s.bg(bg));                  // 高亮前景上叠加底色
            used += s.width();
        }
        if (bg != null) {                                          // 右侧补白到终端宽度，让色带铺满整行
            int pad = Math.max(0, width - used);
            if (pad > 0) spans.add(Span.styled(" ".repeat(pad), Style.create().bg(bg)));
        }
        return Text.from(Line.from(spans));
    }

    /** 从 diff 的 header（{@code Update(path)}）提取路径后缀，映射成 SyntaxHighlighter 的语言标识。 */
    private static String langOf(List<DiffRenderer.DiffLine> lines) {
        if (lines.isEmpty()) return "";
        String h = lines.get(0).text();               // 形如 Update(src/.../App.java)
        int lp = h.indexOf('(');
        int dot = h.lastIndexOf('.');
        if (lp < 0 || dot <= lp) return "";
        return h.substring(dot + 1).replace(")", "").trim();
    }

    /** 行号右对齐到 {@link #GUTTER} 列；null（新增/删除的对侧）用空白占位保持列对齐。 */
    private static String gutter(Integer no) {
        String s = (no == null) ? "" : String.valueOf(no);
        return " ".repeat(Math.max(0, GUTTER - s.length())) + s;
    }

    /** 给渲染出的 span 列表加左缩进，组成一行 Text。 */
    private static Text indented(List<Span> spans) {
        List<Span> all = new ArrayList<>(spans.size() + 1);
        all.add(Span.raw(INDENT));
        all.addAll(spans);
        return Text.from(Line.from(all));
    }

    /** 内容的显示宽度（中文占 2 列），用于底色补齐计算。printer 私有，非跨类共享。 */
    private static int displayWidth(String s) {
        return CharWidth.of(s);
    }
}
```

- [ ] **Step 2: 编译，确认新文件独立可编**

Run: `mvn -o -q -pl springai-code-tui -am compile`
Expected: EXIT 0（新类未被引用但语法/类型齐全）。

---

## Task 2: 视图接线 + 删除已搬走的代码

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java`

- [ ] **Step 1: 字段替换**——删 `md`、`diff`，加 `printer`

删除：
```java
    private final MarkdownRenderer md = new MarkdownRenderer();      // AI 正文 markdown + 代码语法高亮
```
```java
    private final DiffRenderer diff;                                 // edit/write → 带真实行号的 diff 行
```
新增（放在 `inputKeys` 字段附近）：
```java
    private final ScrollbackPrinter printer;                        // scrollback 打印（欢迎/用户块/工具 diff/助手正文）
```

- [ ] **Step 2: 构造函数初始化 printer，去掉 diff 初始化**

把构造函数体改为（`sink` 用匿名类把 `runner()` 惰性桥接进来；`wrapSegments` 是 static，用 `CodeTuiView::wrapSegments`）：
```java
    public CodeTuiView(ConversationState state, SubmitHandler onSubmit, Path root) {
        this.state = state;
        this.onSubmit = onSubmit;
        this.root = root;
        ScrollbackPrinter.Sink sink = new ScrollbackPrinter.Sink() {
            @Override public void println(Text t)   { runner().println(t); }
            @Override public void println(String s) { runner().println(s); }
        };
        this.printer = new ScrollbackPrinter(sink, root, this::terminalWidth, CodeTuiView::wrapSegments);
    }
```
（原 `this.diff = new DiffRenderer(root);` 删除。`Text` 已在视图 import 中；`runner()` 只在 println 时解引用，
 而 println 都发生在 `onStart` 之后，故构造时不触发。）

- [ ] **Step 3: `render()` 预览行改走 printer**

原：
```java
                scope(!tail.isEmpty(), richText(indented(md.renderPreview(tail))).ellipsisStart()),
```
改为：
```java
                scope(!tail.isEmpty(), richText(printer.preview(tail)).ellipsisStart()),
```

- [ ] **Step 4: `onStart()` 欢迎横幅改走 printer**

原：
```java
        runner().runOnRenderThread(this::printWelcome);   // 启动欢迎横幅（一次性下沉 scrollback）
```
改为：
```java
        runner().runOnRenderThread(() -> printer.welcome(onSubmit.currentModel()));   // 启动欢迎横幅（一次性下沉 scrollback）
```

- [ ] **Step 5: `drain()` 收敛为纯分发**

把 `drain()` 里的 for-switch 与流式行循环替换为：
```java
        for (OutputLine ol : state.drainPending()) {
            switch (ol.kind()) {
                case USER       -> printer.userBlock(ol.text());   // 灰底白字块，仿 Claude Code
                case ASSISTANT  -> printer.assistant(ol.text());   // AI 正文：markdown/语法高亮 + 缩进
                case TOOL_START -> printer.toolStart(ol);          // edit/write：展开成 diff 块；其余单行摘要
                default         -> printer.line(ol);               // 工具/Todo/错误：单色贴左
            }
        }
        for (String row : state.takeCompleteStreamingLines()) {    // 流式完整行：markdown/语法高亮 + 缩进
            printer.streamingLine(row);
        }
```
（`animTick++` / `refreshCtxStats` 与末尾出队逻辑不动。）

- [ ] **Step 6: 删除已搬走的方法**

删除下列方法（整体移入了 printer）：
`printWelcome`、`welcomeLine`、`printlnUserBlock`、`printlnToolStart`、`diffLine`、`bodyLine`、`langOf`、`gutter`、`indented`。
删除常量 `GUTTER`（仅 gutter 用，已随之走）。

**保留**：`INDENT`（视图 `queuedChildren` 仍用）、`terminalWidth`、`displayWidth`（视图 slashMenu/queued 仍用）、
`wrapSegments`（视图 InputBox/visualRowCount 仍用 + 注入 printer）、`lastLine`（视图 render 仍用）、`styleFor`（经 Theme 静态导入）。

- [ ] **Step 7: 清理 import**——去掉不再用的静态导入 `indented`？（`indented` 原是本类私有方法，非 import，无需处理。）
  确认 `richText` 静态导入仍在（render 预览用）。无新增 import。

- [ ] **Step 8: 编译**

Run: `mvn -o -q -pl springai-code-tui -am compile`
Expected: EXIT 0。若报「找不到符号 xxx」，多半是漏删/漏改某个调用点——按报错定位。

- [ ] **Step 9: 跑离线套件（回归网）**

Run: `mvn -o -q -pl springai-code-tui -am test -Dtest='!CodingAgentSpikeTest'`
Expected: `Tests run: 89, Failures: 0`（`CodingAgentSpikeTest` 需 live key，排除）。

---

## Task 3: 新增 `ScrollbackPrinterTest`（把渲染逻辑焊进测试网）

**Files:**
- Create: `springai-code-tui/src/test/java/com/example/springai/codetui/ui/ScrollbackPrinterTest.java`

记录型 `Sink` 捕获 `println` 的 `Text`（取 `rawContent()`），断言下沉到 scrollback 的**行数与内容结构**。
**注意**：无头单测测不出 InlineDisplay 行尾裁剪 bug（见记忆 `inline-tui-no-bg-highlight-bar`），故断言聚焦
「行数 / INDENT 前缀 / 关键内容（模型名、diff header、回退单行）」，不断言底色 SGR（那要 Span 内部 + pty 实机）。

- [ ] **Step 1: 写测试文件（完整可编）**

```java
package com.example.springai.codetui.ui;

import com.example.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ScrollbackPrinter：喂输入，断言下沉到 scrollback 的行（经 Sink 接缝捕获，不经 InlineDisplay 的 ANSI 裁剪）。 */
class ScrollbackPrinterTest {

    /** 记录型输出接缝：把每次 println 的内容收进 lines（Text 取 rawContent 纯文本）。 */
    private static final class RecordingSink implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(Text line)   { lines.add(line.rawContent()); }
        @Override public void println(String line) { lines.add(line); }
    }

    /** 造一个 printer：固定 80 列，不折行（wrap 返回原行），root=/work。 */
    private static ScrollbackPrinter printerOver(RecordingSink sink) {
        return new ScrollbackPrinter(sink, Path.of("/work"), () -> 80, (s, w) -> List.of(s));
    }

    @Test
    void welcome_printsRoundedBannerWithModelAndCwd() {
        RecordingSink sink = new RecordingSink();
        printerOver(sink).welcome("deepseek-v4-flash");

        // 顶/底边框各 1 + 6 行内容 + 末尾 1 空行 = 9 行
        assertEquals(9, sink.lines.size(), "欢迎横幅应输出 9 行");
        assertTrue(sink.lines.get(0).startsWith("╭"), "首行应为圆角上边框");
        assertTrue(sink.lines.get(7).startsWith("╰"), "倒数第二行应为圆角下边框");
        assertEquals("", sink.lines.get(8), "末行应为留白空行");
        assertTrue(sink.lines.stream().anyMatch(l -> l.contains("deepseek-v4-flash")), "应含所选模型名");
        assertTrue(sink.lines.stream().anyMatch(l -> l.contains("/work")), "应含 cwd 路径");
    }

    @Test
    void userBlock_emitsOneLinePerLogicalLineWithIndentPrefix() {
        RecordingSink sink = new RecordingSink();
        printerOver(sink).userBlock("第一行\n第二行");

        assertEquals(2, sink.lines.size(), "两条逻辑行 → 两行输出");
        assertTrue(sink.lines.get(0).startsWith("  "), "用户块每行以 INDENT 起");
        assertTrue(sink.lines.get(0).contains("第一行"));
        assertTrue(sink.lines.get(1).contains("第二行"));
    }

    @Test
    void toolStart_nonFileWrite_fallsBackToSingleSummaryLine() {
        RecordingSink sink = new RecordingSink();
        // 2 参构造 → toolName/raw 均为 null → 非文件写入，走单行摘要回退
        printerOver(sink).toolStart(new OutputLine("Bash(ls)", OutputLine.Kind.TOOL_START));

        assertEquals(1, sink.lines.size(), "非文件写入 → 单行摘要");
        assertTrue(sink.lines.get(0).contains("Bash(ls)"));
    }

    @Test
    void line_infoKind_printsTextContent() {
        RecordingSink sink = new RecordingSink();
        printerOver(sink).line(new OutputLine("📊 上下文用量", OutputLine.Kind.INFO));

        assertEquals(1, sink.lines.size());
        assertTrue(sink.lines.get(0).contains("上下文用量"));
    }
}
```

- [ ] **Step 2: 跑新测 + 全量**

Run: `mvn -o -q -pl springai-code-tui -am test -Dtest='ScrollbackPrinterTest'`（先单跑）
再: `mvn -o -q -pl springai-code-tui -am test -Dtest='!CodingAgentSpikeTest'`
Expected: 全绿（89 + 4 个新增用例）。

> 若 `Text.rawContent()` 的实际返回与断言不符（如把 span 间不插空格 → 内容拼接方式不同），按实际调整 `contains`
> 断言的期望子串即可——这是接口细节核对，不是设计问题。

---

## Task 4: package + pty 实机核对 + 提交

- [ ] **Step 1: 重新 package（否则用户跑的是旧 jar）**

Run: `mvn -o -q -pl springai-code-tui -am package -Dtest='!CodingAgentSpikeTest'`
Expected: BUILD SUCCESS，产出 `target/springai-code-tui.jar`。

- [ ] **Step 2: pty 实机核对（前后一致）**

用既有 pty+pyte 探针（`fcntl.ioctl TIOCSWINSZ` 设窗口；JVM 冷启动 pump ~6s；`DEEPSEEK_API_KEY=test-fake`）
启动 jar，核对：欢迎横幅 6 行 + 圆角边框、输入框空态光标、`/` 打开菜单暖橙高亮无底色残留——与重构前逐帧一致。
（纯搬运不改渲染字节，故这是「无回归」确认，非新验证。）

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/ScrollbackPrinter.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/ui/ScrollbackPrinterTest.java \
        docs/superpowers/specs/2026-07-03-scrollback-printer-extraction-design.md \
        docs/superpowers/plans/2026-07-03-scrollback-printer-extraction.md
git commit -m "refactor(code-tui): 抽出 ScrollbackPrinter（纯搬运，行为不变）"
```
commit message 正文注明：视图 850→~700；md/diff 移入 printer；wrapSegments 注入、未新建工具类；
89/89 + 新测通过；pty 前后一致。

---

## 自检清单（写完计划回看）

- [x] 每个搬运方法都有明确去处（printer 私有 / 视图保留），无悬空引用。
- [x] 共享辅助（wrapSegments/displayWidth/INDENT）逐一定去向，未新建工具类。
- [x] `md` 归属迁移的时序坑（构造 vs runner 就绪）已用 `Sink` 匿名类惰性桥接 `runner()` 解决，无 null 守卫。
- [x] `Sink` 接缝一举两得：解耦 tamboui + 让 Task 3 用记录型 Sink 写出**无占位符**的具体测试。
- [x] 类型一致：`Sink`/`preview(Text)`/`ScrollbackPrinter(Sink, Path, IntSupplier, BiFunction)` 在 spec、Task 1、Task 2、Task 3 中签名统一。
- [x] 回归网明确：89/89 offline 全程保持；SpikeTest 需 live key 故排除。
- [x] package 步骤在，避免旧 jar 假象。

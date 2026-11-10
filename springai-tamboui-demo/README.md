# springai-tamboui-demo —— TamboUI 入门课程（学习路线第⑤步）

**八课学会用 [TamboUI](https://tamboui.dev)（Java TUI 框架，仿 ratatui）写终端应用**，
最后一课衔接第⑥步 code-tui 的视图。跟完教程，你手里会有一个自己一行行写出来的
备忘录应用（约 80 行）——「学会」由它检验。基于 TamboUI 0.4.0，不需要任何 API key。

## 怎么用这门课

按序读，每课三步，顺序不要跳：

1. **跟做**：在自己的 `MyNotes.java` 上加几行代码（教程给全每一步的代码与「为什么」）；
2. **对照**：跑菜单里对应课的示例，看同一知识点的完成形态；
3. **试一试**：改一处再跑，确认真的会了。

查 API、看组件全景、了解进阶话题 → 一律去文末「参考手册」，别在正文里迷路。

## 准备与运行

必须用**真实终端**（IDE 控制台/管道是 dumb 终端，颜色与交互看不全）：

```bash
mvn -q -pl springai-tamboui-demo -am package -DskipTests
java -jar springai-tamboui-demo/target/springai-tamboui-demo-<version>.jar
```

菜单选课进入（每课独立子进程运行，1–7 课全屏、q 或 Ctrl+C 退出，第 8 课行内、Ctrl+C 退出）。

---

## 第 1 课 · 创建 TUI 应用

**目标**：知道创建一个 TUI 应用有哪几种方法；亲手从零写出第一个能跑的程序。

### 跟做：五步写出 MyNotes（10 行骨架）

在 `src/main/java/io/github/javaside/springai/tamboui/demo/` 下新建 `MyNotes.java`，
跟着五步走（每步都可以直接编译）：

**① 建类**——和写普通 Java 程序一样，只是实现了课程接口：

```java
package io.github.javaside.springai.tamboui.demo;

import io.github.javaside.springai.tamboui.TambouiDemo;

public class MyNotes implements TambouiDemo {
    public int order() { return 9; }                       // 菜单序号（排在八课之后）
    public String title() { return "我的备忘录（跟做）"; }
    public void run() throws Exception {
    }
}
```

**② 创建运行器（接管终端）**——TUI 程序的第一行永远是它：

```java
public void run() throws Exception {
    try (dev.tamboui.tui.TuiRunner runner = dev.tamboui.tui.TuiRunner.create()) {
        // create()：进备用屏幕 + 原始模式——终端从此归程序管
    }
}
```

为什么 try-with-resources：程序接管了终端（raw 模式、备用屏幕），不关掉它，
退出后终端就是「坏的」；`close()` 负责全部复原。

**③ 事件处理**——先只管退出（骨架的两条腿之一）：

```java
        dev.tamboui.tui.EventHandler handler = (event, r) -> {
            if (event instanceof dev.tamboui.terminal.KeyEvent key && key.isQuit()) {
                r.quit();                                   // 默认退出键 q / Ctrl+C
            }
            return false;    // 返回值 = 是否需要重绘；此处画面不变，返回 false
        };
```

**④ 渲染函数**——画一屏（两条腿之二）：

```java
        dev.tamboui.tui.Renderer renderer = frame ->
                frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.from("我的备忘录"),
                        frame.area());
```

**⑤ 启动循环**：

```java
        runner.run(handler, renderer);
```

**跑起来**：在 `TambouiDemoApplication.demoSuppliers()` 的列表第一行加 `MyNotes::new,`，
重新打包后菜单里就会出现「9. 我的备忘录（跟做）」——这就是你自己的第一个 TUI。
（也可在 IDE 里直接给 MyNotes 加个 main 调 `new MyNotes().run()` 运行。）

### 对照

菜单选 **1. 创建 TUI 应用**：屏幕上就是五种创建方式的对照表，而那个程序本身
正是你刚写的「方式②」的实跑。

### 原理：创建一个 TUI 有哪几种方法

两轴（全屏/行内）× 两层次（声明式/事件循环），外加最底层：

| 方式 | 入口 | 适用 |
|---|---|---|
| ① 全屏·声明式 | `extends ToolkitApp` → `run()` | **大多数应用首选**（第 7 课主角） |
| ② 全屏·事件循环 | `TuiRunner.create()` + `run(handler, renderer)` | 直观、控制力强（本课） |
| ③ 行内·声明式 | `extends InlineApp` → `run()` | 聊天式 CLI（第 8 课主角） |
| ④ 行内·事件循环 | `InlineTuiRunner.create(config)` + `run(...)` | 行内 + 自定义事件（片段见参考手册） |
| ⑤ 立即模式 | `Terminal` + `Backend` 全手工 | 最底层，知道即可（片段见参考手册） |

- **全屏**：接管整个终端（备用屏幕），退出复原——工具面板类应用的形态；
- **行内**：终端照常滚动，底部一小块区域原地刷新——聊天/进度类应用的形态。
- 方式①/③的完整最小代码见第 7、8 课跟做（到时候你两版都会写）；
  ④⑤的最小片段在参考手册「创建方式详表」。
- **EventHandler 与 Renderer 是事件循环类的两条腿**：前者回答「发生了什么、要不要重画」，
  后者回答「这一屏长什么样」——后面六课都在丰富这两条腿。

### 试一试

把 `Paragraph.from("我的备忘录")` 换成自己的话，重新打包跑一遍。

---

## 第 2 课 · 渲染模型——画面是什么

**目标**：搞懂三个核心概念——**Buffer（画面本身）、Cell（一格）、Frame（渲染入口）**，
并亲眼看到「框架只把变化的格子发给终端」（diff）。

### 跟做：往 MyNotes 里手写一格 + 打印一帧网格

**① 手写一格**——在 `run()` 里 renderer 赋值之后、`runner.run(...)` 之前不动，
把第 1 课的渲染函数改成两行：

```java
        dev.tamboui.tui.Renderer renderer = frame -> {
            frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.from("我的备忘录"),
                    frame.area());
            // Buffer 现形：网格本体一直在 Frame 手里，可以直接读写一格
            frame.buffer().set(frame.area().width() - 3, 0,
                    new dev.tamboui.buffer.Cell("★", dev.tamboui.style.Style.create()
                            .fg(dev.tamboui.style.Color.YELLOW).bold()));
        };
```

跑一遍：右上角出现一颗黄星——`renderWidget` 是「组件批量画进网格」，`buffer().set`
是「手工写一格」，两者落在**同一张 Buffer** 上。

**② 打印一帧网格**——在 `runner.run(handler, renderer);` 之后（已退出全屏）加：

```java
        System.out.println("刚才那一帧，在 Buffer（字符网格）里的样子：");
        // lastFrame 在渲染函数里存一份快照：private dev.tamboui.buffer.Buffer lastFrame;
        // 渲染函数末尾加：lastFrame = frame.buffer().copy();
        for (String row : FrameDump.dump(lastFrame)) {
            System.out.println("  " + row);
        }
```

（需要两个小改动：类里加字段 `private dev.tamboui.buffer.Buffer lastFrame;`，
渲染函数末尾加 `lastFrame = frame.buffer().copy();`。）

跑一遍退出后，整屏变成了带 `y=行号` 的网格文本——屏幕的本质就是这张
「一格一个字符」的二维数组；`·` 是宽字符（如中文）占的第二个格（续格）。

### 对照

菜单选 **2. 渲染模型**：按**空格**在帧 A ↔ 帧 B 间切换（代码只翻转一个布尔位，
画面整屏重画）；按 q 退出后并排打印两帧网格与 **diff 视图**（■ = 框架实际发给
终端的格子，其余一个字节都不发）——那是框架每帧替你做的事。

### 原理

- **画面由 Buffer 表示**（Cell 的二维数组）；**Frame 不是画面**，是渲染入口
  （`area()` 给可绘区域、`renderWidget(组件, 区域)` 把组件画进网格、`buffer()` 拿网格本体）；
- **立即模式**：渲染函数每帧从零描述整屏，不看上一帧、不留旧状态——「UI 是状态的纯函数」；
- **diff 是框架的事**：它对比前后两帧，只把变化的格子发给终端。

### 试一试

把跟做①的 `set(...)` 改成写到 `(5, 5)`——网格打印里找到那颗星，确认坐标体系（x 列 y 行，从 0 数）。

---

## 第 3 课 · 文本与样式

**目标**：会写带样式的文字——富文本三粒度（Span/Line/Text）、样式链、颜色三种来源、
超宽行为，以及宽度口径（中文占 2 列）。

### 跟做：给 MyNotes 的标题上样式

把渲染函数里的 `Paragraph.from("我的备忘录")` 换成：

```java
            dev.tamboui.text.Text title = dev.tamboui.text.Text.from(
                    dev.tamboui.text.Line.from(
                            dev.tamboui.text.Span.styled("我的备忘录",
                                            dev.tamboui.style.Style.create()
                                                    .fg(dev.tamboui.style.Color.CYAN).bold()),
                            dev.tamboui.text.Span.raw("  "),
                            dev.tamboui.text.Span.styled("今天也要加油",
                                    dev.tamboui.style.Style.create()
                                            .fg(dev.tamboui.style.Color.rgb(70, 130, 180)))));
            frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.builder()
                    .text(title).build(), frame.area());
```

跑一遍：青色加粗主标题 + 蓝色副标题，同一行里两种样式——因为**一行（Line）由多个
带样式的片段（Span）拼成**。

### 对照

菜单选 **3. 文本与样式**：三粒度、颜色三种来源（命名色/256 色/RGB）、对齐、
折行与宽度说明一屏全览。

### 原理

- 三粒度：**Span**（带样式的字符串片段）→ **Line**（一行，多个 Span 拼接，可
  `left()/centered()/right()`）→ **Text**（多行段落；不可变，`append(...)` 返回新实例）；
- **Style 不可变**：`Style.create().fg(...).bold().italic()` 每个点都返回新对象，链式即组合；
- **Color 三种来源**：命名色 `Color.RED`、256 色 `Color.indexed(208)`、真彩 `Color.rgb(r,g,b)`；
- **超宽行为 Overflow**：`WRAP_WORD` 折行 / `CLIP` 截断（**默认**——长文本会被静默裁掉，
  别漏设）/ `ELLIPSIS` 省略号；
- **宽度口径**：`CharWidth.of("中")` = 2——终端对齐按渲染宽度算，不按字符个数；
  第 2 课网格里的「主格+续格(·)」就是它的形态。

### 试一试

把副标题换成 256 色的 `Color.indexed(208)`，再给主标题加 `.italic()`，各跑一遍。

---

## 第 4 课 · 组件画廊

**目标**：认识常用组件并会各画一个——Paragraph（多行文本）、Block（边框容器）、
List+ListState（列表）、Gauge（进度条）。

### 跟做：给 MyNotes 加一个列表

把渲染函数扩成（先手写区域坐标——布局下一课才讲，先「用手摆」）：

```java
            var area = frame.area();
            frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.from("我的备忘录"),
                    new dev.tamboui.layout.Rect(1, 1, area.width() - 2, 3));   // 标题区：手摆
            frame.renderStatefulWidget(
                    dev.tamboui.widgets.list.ListWidget.builder()
                            .items("买牛奶", "写周报", "复习 TamboUI 第 4 课")
                            .highlightSymbol("▶ ")
                            .block(dev.tamboui.widgets.block.Block.builder()
                                    .borders(dev.tamboui.widgets.block.Borders.ALL)
                                    .borderType(dev.tamboui.widgets.block.BorderType.ROUNDED)
                                    .title("待办").build())
                            .build(),
                    new dev.tamboui.layout.Rect(1, 5, area.width() - 2, area.height() - 7),
                    listState);   // 类字段：private final dev.tamboui.widgets.list.ListState listState = new ...;
```

跑一遍：出现圆角边框的「待办」列表，选中行带 ▶ 高亮。注意两个新东西：
`renderStatefulWidget(组件, 区域, 状态)`（比无状态版多传一个状态）和
`Block`（边框容器，常挂在别的组件的 `.block(...)` 上）。

### 对照

菜单选 **4. 组件画廊**：Paragraph / Block / List / Gauge 四块演示区逐个亮相，
每块的画面就是对应几行代码的产物。

### 原理

- 组件分两种：**无状态**——`frame.renderWidget(组件, 区域)` 直接画（Paragraph、Gauge、Block）；
  **有状态**——`frame.renderStatefulWidget(组件, 区域, 状态)`，状态（如 ListState 管选中与
  滚动）在组件外持有、渲染时传入。**状态外置**正是「UI = 状态的纯函数」能成立的原因；
- `Block`：官方称「最基础的容器」——`borders(...)` 哪些边、`borderType(...)` 线型、`title(...)` 标题；
- `Gauge`：`Gauge.builder().ratio(0.62).label(Span).build()`——进度条；
- TamboUI 全部 **26 个组件、8 大类**（容器/文本/选择/数据可视化/动画/输入/工具/Markdown）
  见官方文档（参考手册有链接）——课程演示最常用的四个。

### 试一试

在列表下面再手摆一块区域画 `Gauge`（对照画廊的 ④），显示「今日完成度 30%」。

---

## 第 5 课 · 布局

**目标**：把手摆的坐标升级成**约束切分**——Layout 与 Constraint 是 ratatui 系框架布局的全部语言。

### 跟做：MyNotes 三段切分

把第 4 课手写的两个 `new Rect(...)` 换成：

```java
            var parts = dev.tamboui.layout.Layout.vertical()
                    .constraints(dev.tamboui.layout.Constraint.length(3),      // 标题区：固定 3 行
                            dev.tamboui.layout.Constraint.fill(),              // 列表区：吃掉剩余
                            dev.tamboui.layout.Constraint.length(1))           // 状态行：固定 1 行
                    .split(frame.area());
            // parts 是 List<Rect>：parts.get(0) 标题区、get(1) 列表区、get(2) 状态行
```

标题画进 `parts.get(0)`、列表画进 `parts.get(1)`；再给状态行加一句提示：

```java
            frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.from(
                            "j/k 选择（第 6 课加） · q 退出"),
                    parts.get(2));
```

跑一遍：拉伸终端窗口——区域跟着比例伸缩，不用你算一个坐标。**切分可嵌套**：
拿 `parts.get(1)` 再 `split` 一次就是多栏。

### 对照

菜单选 **5. 布局**：头（固定）/ 中段（60%，再左右切成 List+Gauge）/ 尾（吃剩余）——
正是第 4 课画廊「手摆区域」的约束版。

### 原理

- `Layout.vertical()/horizontal().constraints(...).split(Rect) → List<Rect>`：给一个大区域和
  一组约束，返回切好的子区域列表；
- 约束四种常用：`length(n)` 固定、`percentage(n)` 比例、`fill()` 吃剩余、
  `min/max` 上下限（另有 ratio/fit）；
- **先切区域，再往区域放组件**——这就是「布局」的全部；有状态组件照旧用
  `renderStatefulWidget(组件, 区域, 状态)`。

### 试一试

把列表区约束换成 `percentage(60)`，再在下面加一个 `fill()` 区放 Gauge，观察分配。

---

## 第 6 课 · 事件与交互

**目标**：让应用动起来——事件 → 状态 → 请求重绘 → 下一帧反映，这条链路是 TUI 交互的全部。

### 跟做：给 MyNotes 加 j/k 选择

**① 加状态**（类字段）：

```java
    private int selected;   // 当前选中的待办下标
```

**② handler 里响应 j/k**——替换第 1 课的 handler：

```java
        dev.tamboui.tui.EventHandler handler = (event, r) -> {
            if (event instanceof dev.tamboui.terminal.KeyEvent key && key.isQuit()) {
                r.quit();
                return false;
            }
            if (event instanceof dev.tamboui.terminal.KeyEvent key) {
                if (key.isChar('j')) { selected = Math.min(2, selected + 1); return true; }
                if (key.isChar('k')) { selected = Math.max(0, selected - 1); return true; }
            }
            return false;   // 无关键：状态没变，不请求重绘
        };
```

**③ 渲染时同步列表选中**——渲染函数开头加 `listState.select(selected);`

跑一遍：j/k 上下移动 ▶ 高亮。注意铁律：**事件只改状态，返回 true 请求重绘；
渲染函数永远只看状态**——所以状态逻辑可以抽成纯类离线单测（菜单示例 6 正是这么写的）。

### 对照

菜单选 **6. 事件与交互**：j/k 或方向键移动选中、TickEvent 驱动 Gauge 走动、
底部显示最近按键。

### 原理

- 事件三类：**KeyEvent**（`isChar('j')` 字符判定、`isKey(KeyCode.UP/ENTER/ESCAPE)`、`isQuit()`）；
  **TickEvent**（周期重绘脉冲，自带 `elapsedSeconds()`/`frameCount()`——真实时间动画直接读）；
  **ResizeEvent**（窗口缩放自动触发重绘，渲染经 `frame.area()` 拿新尺寸，通常无需处理）；
- 开 tick：`TuiConfig.builder().tickRate(Duration.ofMillis(100)).build()` 传给
  `TuiRunner.create(config)`；
- **按需重绘纪律**：handler 返回 false 就不重绘——没变化就不浪费终端字节。

### 试一试

加一个键 `x`：把当前选中项文字换成「✓ 已完成」（提示：把 items 换成可变的 `List<String>` 字段）。

---

## 第 7 课 · Toolkit DSL（声明式）

**目标**：同样的应用换一种省事的写法——`render()` 只声明 UI 树，布局/焦点/输入编辑全交给框架。

### 跟做：MyNotes 的 DSL 版

新建 `MyNotesDsl.java`（同样注册进菜单，order 10）：

```java
package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.widgets.input.TextAreaState;

import static dev.tamboui.toolkit.Toolkit.*;

public class MyNotesDsl extends ToolkitApp implements TambouiDemo {
    private final TextAreaState notes = new TextAreaState();
    private int selected;

    public int order() { return 10; }
    public String title() { return "我的备忘录 DSL 版（跟做）"; }
    public void run() throws Exception { super.run(); }          // ToolkitApp 自举

    @Override
    protected TuiConfig configure() {                            // 退出只留 Ctrl+C（要能打字）
        TuiConfig base = super.configure();
        return base.toBuilder().bracketedPaste(true)
                .bindings(base.bindings().toBuilder()
                        .rebind(KeyTrigger.ctrl('c'), Actions.QUIT).build()).build();
    }

    @Override
    protected Element render() {                                 // 只声明这棵树
        return column(
                panel("我的备忘录", textArea(notes)),             // 输入框：零键盘代码
                text("已选中第 " + selected + " 条 · Ctrl+C 退出"));
    }
}
```

跑一遍：同样的全屏应用，但**没有一行自绘、没有一行键盘代码**——布局焦点输入全是
框架的事。对比第 1–6 课的手工版，两版能力等价，写法繁简立现。

### 对照

菜单选 **7. Toolkit DSL**：带输入框、统计面板、行数目标（Tab 切焦点、+/- 调整）的
完整 DSL 应用。

### 原理

- `ToolkitApp` 三件套：`render()` 返回整棵树（声明式）、`configure()` 定制键位、
  `run()` 自举；
- 工厂（`Toolkit.*`）：`text/richText/column/row/panel/spacer`，
  `InlineToolkit.scope(条件, 子元素)` 条件隐藏（面板收起）；
- **自定义按键惯例**：`.id("唯一id").focusable().onKeyEvent(k -> HANDLED/UNHANDLED)`；
  可聚焦元素必须显式 id；多个可聚焦元素 Tab 切焦点（框架不画焦点标识，用
  `runner().focusManager().focusedId()` 自绘 ▶）；
- `TextAreaState`：`insert/text/clear`——配 `textArea(state)` 得到完整输入框（多行/光标/中文）。

### 试一试

给统计面板加一行字数（提示：`notes.text().length()`）。

---

## 第 8 课 · 行内模式——衔接 code-tui

**目标**：换到行内轴——终端照常滚动、底部一小块 live 区原地刷新；理解
「live 区差分重绘 + 定稿内容 println 下沉」双通道。这是 code-tui 主视图的形态。

### 跟做：MyNotes 换行内版

新建 `MyNotesInline.java`（注册，order 11）：

```java
package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Span;
import dev.tamboui.toolkit.InlineToolkit;
import dev.tamboui.toolkit.app.InlineApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.InlineTuiConfig;

import static dev.tamboui.toolkit.Toolkit.*;

public class MyNotesInline extends InlineApp implements TambouiDemo {
    private final dev.tamboui.widgets.input.TextAreaState notes = new dev.tamboui.widgets.input.TextAreaState();
    private boolean justSaved;

    public int order() { return 11; }
    public String title() { return "我的备忘录 行内版（跟做）"; }
    public void run() throws Exception { super.run(); }

    @Override
    protected int height() { return 4; }              // 空态 live 区高度

    @Override
    protected InlineTuiConfig configure(int h) {
        return super.configure(h).toBuilder().bracketedPaste(true).build();
    }

    @Override
    protected void onStart() {
        runner().println("（历史记录留在上面滚动——这就是 scrollback；下面固定的一小块是 live 区）");
    }

    @Override
    protected Element render() {
        return column(
                InlineToolkit.scope(justSaved, text("✓ 已保存").green()),
                panel("记录（Enter 换行，写完 Ctrl+C 退出）", textArea(notes)),
                text("live 区只放还会变的东西；定稿内容 println 下沉，不再重绘"));
    }
}
```

跑一遍：欢迎语「沉」在上方滚动区，输入框固定在底部——**两条通道各司其职**。

### 对照

菜单选 **8. 行内模式**：完整迷你聊天（Enter 发送、假流式残行在 live 区原地刷新、
定稿行逐行下沉）——`CodeTuiView`（code-tui 主视图）的缩影，javadoc 里有逐钩子对照表。

### 原理

- 行内 vs 全屏：行内保留 scrollback（聊天/进度类 CLI 需要），live 区钉在底部；
- `InlineApp` 钩子：`height()` 空态高度、`render()` 声明 live 区、`configure(int)`；
  工具 `runner().println(...)` 下沉定稿内容、`scheduleRepeating(...)` 定时驱动；
- code-tui 在生产上用仓库的 inline-patch 对行内渲染做了工程加固（一句话了解，见
  参考手册「衔接 code-tui」）。

### 试一试

把 onStart 的 println 多打几条，观察它们沉入 scrollback 后不再变化。

---

## 毕业题

不看教程，从零写一个**全屏应用**（任选：番茄钟 / 待办清单 / 菜单系统），要求用到：
布局切分（Layout+Constraint）、至少三个组件（含一个有状态组件）、按键交互（事件改状态）。
写出来了，这门课就毕业了——第⑥步 code-tui 的源码在等你。

---

# 参考手册（正文学完后回来查）

## 每课 API 速查

### 第 1 课 · 创建应用

```java
try (TuiRunner runner = TuiRunner.create()) {      // 进备用屏幕 + 原始模式
    runner.run(handler, renderer);
}
```

| 类 | 角色 | 关键用法 |
|---|---|---|
| `TuiRunner` | 全屏发动机 | `create()` / `run(handler, renderer)` / `quit()`；try-with-resources 复原终端 |
| `EventHandler` | 事件处理 | `(event, runner) -> boolean`：返回是否需要重绘 |
| `Renderer` | 画帧函数 | `frame -> {...}`：整帧重画，不看上一帧 |
| `TuiConfig` | 运行配置 | `builder().tickRate(...)` / `bracketedPaste(true)` 等 → `TuiRunner.create(config)` |

### 第 2 课 · 渲染模型

| 类 | 角色 | 关键用法 |
|---|---|---|
| `Buffer` | **画面本身**（Cell 网格） | `get(x,y)/set(x,y,Cell)`；宽字符占主格+续格 |
| `Cell` | 一格 | `new Cell("★", Style)`；`symbol()/isContinuation()` |
| `Frame` | 渲染入口（不是画面） | `area()` / `renderWidget(组件, 区域)` / `buffer()` |

### 第 3 课 · 文本与样式

| 类 | 关键用法 |
|---|---|
| `Span` | `styled(字, Style)` / `raw(字)` / `.fg(...)` |
| `Line` | `from(Span...)` + `.left()/.centered()/.right()` |
| `Text` | `from(Line...)`；不可变，`append(Line)` 返回新实例 |
| `Style` | `create().fg(Color).bold().italic().underlined()`（不可变链） |
| `Color` | `RED` 等命名色 / `indexed(208)` 256 色 / `rgb(70,130,180)` 真彩 |
| `Overflow` | `WRAP_WORD` 折行 / `CLIP` 截断（默认！）/ `ELLIPSIS` |
| `CharWidth` | `of("中文A")` → 5（渲染宽度口径） |

### 第 4 课 · 组件

| 组件 | 用法 | 有状态？ |
|---|---|---|
| `Paragraph` | `builder().text(...).block(...).centered().overflow(...).build()` 或 `from(String)` | 否 |
| `Block` | `builder().borders(Borders.ALL).borderType(ROUNDED).title("t").build()` | 否 |
| `ListWidget` | `builder().items(String...).highlightSymbol("▶ ").block(...).build()` | **是**（配 `ListState`：`select(i)/selectNext(n)`） |
| `Gauge` | `builder().ratio(0.62).label(Span).build()` | 否 |

渲染入口：无状态 `frame.renderWidget(w, rect)`；有状态 `frame.renderStatefulWidget(w, rect, state)`。
全部 26 组件（8 大类）见[官方 Widgets 参考](https://tamboui.dev/docs/main/widgets.html)。

### 第 5 课 · 布局

```java
List<Rect> parts = Layout.vertical()
        .constraints(Constraint.length(3), Constraint.percentage(60), Constraint.fill())
        .split(frame.area());      // 可嵌套：拿子 Rect 再 split
```

约束：`length(n)` / `percentage(n)` / `fill()` / `min(n)` / `max(n)` / `ratio(a,b)` / `fit()`。

### 第 6 课 · 事件

| 类 | 关键用法 |
|---|---|
| `KeyEvent` | `isChar('j')` / `isKey(KeyCode.UP/ENTER/ESCAPE)` / `isQuit()`（默认 q/Q/Ctrl+C，Esc=cancel） |
| `TickEvent` | `elapsedSeconds()` / `frameCount()`（真实时间动画直接读） |
| `ResizeEvent` | 自动触发重绘；渲染用 `frame.area()` 即可 |

### 第 7 课 · Toolkit DSL

| API | 关键用法 |
|---|---|
| `ToolkitApp` | `render()` 返回 Element / `configure()` / `run()` 自举 |
| 工厂 | `text` `richText` `column` `row` `panel("t",...)` `spacer`；`InlineToolkit.scope(cond, ...)` |
| 按键惯例 | `.id("唯一id").focusable().onKeyEvent(k -> HANDLED)`；Tab 切焦点；`focusManager().focusedId()` |
| `TextAreaState` | `insert/text/clear` + `Toolkit.textArea(state)` |
| `Bindings` | `bindings().toBuilder().rebind(KeyTrigger.ctrl('c'), Actions.QUIT).build()`（整组替换） |

### 第 8 课 · 行内

| API | 关键用法 |
|---|---|
| `InlineApp` | `height()` / `render()` / `configure(int)` / `onStart()`；`runner().println(...)` 下沉 |
| `InlineTuiConfig` | `builder(n)` 初始行数；`.bracketedPaste(true)`；`.noTick()` |
| 定时驱动 | `runner().scheduleRepeating(runnable, Duration)` + `runOnRenderThread(...)` |

## 创建方式详表（④⑤ 最小片段）

```java
// ④ 行内·事件循环
try (InlineTuiRunner runner = InlineTuiRunner.create(
        InlineTuiConfig.builder(4).bracketedPaste(true).build())) {
    runner.run((event, r) -> false,
            frame -> frame.renderWidget(Paragraph.from("hi"), frame.area()));
}

// ⑤ 立即模式：Terminal + Backend 全手工
try (Backend backend = BackendFactory.create()) {
    backend.enableRawMode();
    backend.enterAlternateScreen();
    Terminal<Backend> term = new Terminal<>(backend);
    term.draw(frame -> frame.buffer().setString(0, 0, "hello", Style.EMPTY));
    backend.disableRawMode();
    backend.leaveAlternateScreen();
}
```

**Backend 三种实现**（BOM 0.4.0）：`tamboui-jline3-backend`（课程所用）、
`tamboui-panama-backend`、`tamboui-aesh-backend`。

## 本课程没教的（进阶出口）

更多组件（Table/Chart/Canvas/Tree/Calendar…）、CSS/TCSS 样式、Markup 文本、Markdown 渲染、
Forms 表单、Pilot 测试、GraalVM 原生镜像——[官方文档](https://tamboui.dev/docs/main/index.html)有全解。

## 版本口径

本课程锁 **0.4.0**（根 pom `tamboui-bom` 统一管理）。官方文档对应更新的快照版
（0.7.0-SNAPSHOT），个别 API 有出入——以本仓库 jar 的 javadoc 为准。

## 衔接 code-tui 与 inline-patch

第 8 课的迷你聊天即 `CodeTuiView` 的缩影（`InlineApp` + 双通道 + `scope` 收起面板）。
code-tui 在生产上用仓库内 [springai-tamboui-inline-patch](../springai-tamboui-inline-patch)
对行内渲染与事件解析做了工程加固（差分帧提交、防 pty 写阻塞、粘贴预算等）——
第 8 课 javadoc 的「工程备注」说明了课程（上游原版）与它的差异点。

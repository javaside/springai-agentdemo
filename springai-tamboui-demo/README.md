# springai-tamboui-demo —— TamboUI 入门课程（学习路线第⑤步）

**八课学会用 [TamboUI](https://tamboui.dev)（Java TUI 框架，仿 ratatui）写终端应用**，
最后一课衔接第⑥步 code-tui 的视图。基于 TamboUI 0.4.0，不需要任何 API key。

## 怎么用这门课

每课四步，顺序不要跳：

1. **本课学什么**：先给结论，带着目标往下读；
2. **示例**：课程示例的核心代码（完整源码在 `src/.../demo/` 对应类里，带逐行注释）；
3. **讲解**：这段代码怎么工作、为什么这么写、有什么坑；
4. **运行**：在菜单里跑对应示例，对照屏幕验证理解（末尾附一道轻量自测）。

查 API、看组件全景、进阶话题 → 文末「参考手册」。

## 运行

必须用**真实终端**（IDE 控制台/管道是 dumb 终端，颜色与交互看不全）：

```bash
mvn -q -pl springai-tamboui-demo -am package -DskipTests
java -jar springai-tamboui-demo/target/springai-tamboui-demo-<version>.jar
```

菜单选课进入（每课独立子进程运行；1–7 课全屏、q 或 Ctrl+C 退出，第 8 课行内、Ctrl+C 退出）。

---

## 第 1 课 · 创建 TUI 应用

**本课学什么**：创建一个 TUI 应用有哪几种方法；最小可运行程序长什么样。

### 示例：一个能跑的 TUI 最小程序（方式②，共 4 行核心）

```java
try (TuiRunner runner = TuiRunner.create()) {          // ① 创建运行器：进备用屏幕 + 原始模式
    Renderer renderer = frame ->                      // ③ 渲染：画一屏
            frame.renderWidget(Paragraph.from("你好"), frame.area());
    EventHandler handler = (event, r) -> {            // ② 事件：处理输入
        if (event instanceof KeyEvent key && key.isQuit()) r.quit();
        return false;
    };
    runner.run(handler, renderer);                    // ④ 启动循环（阻塞到退出）
}
```

### 讲解

- **`TuiRunner.create()`** 接管终端（raw 模式 + 备用屏幕）。try-with-resources 不是
  可选风格：不 `close()` 终端就是「坏的」，资源语句保证任何退出路径都复原；
- **`runner.run(handler, renderer)` 的两个参数是两个函数式接口，也是框架与你的代码的分工边界**：
  - **`EventHandler`**——`boolean handle(Event event, TuiRunner runner)`，事件回调：
    TuiRunner 每从终端收到一个事件（按键/粘贴/缩放/定时脉冲）就调用它一次；
    返回 `true` 表示「状态已变化，需要重绘」，返回 `false` 则本次不重绘
    （官方约定：事件处理返回 true 才重绘）；
  - **`Renderer`**——`void render(Frame frame)`，渲染回调：仅在需要重绘时被调用，
    把这一帧的完整内容画进传入的 `Frame`（官方约定为立即模式：每帧全量描述）。
    效率靠**差分（diff）**保证：框架保留上一帧的网格，新帧画完后逐格对比，
    只把内容不同的格子发给终端（一屏 80×24 共 1920 格，改 20 格就只发 20 格的
    指令）——「你写整屏的描述，框架算最小更新」，第 2 课可亲眼看到；

  即：事件循环、终端 I/O、差分是 TuiRunner 的职责，「处理输入」与「描述画面」
  由这两个回调提供——后续课程引入的组件、布局、状态，最终都落在它们的实现里；
- **创建方法一共有五种**（两轴：全屏/行内 × 声明式/事件循环，外加最底层立即模式）：

| 方式 | 入口 | 适用 |
|---|---|---|
| ① 全屏·声明式 | `extends ToolkitApp` → `run()` | **大多数应用首选**（第 7 课） |
| ② 全屏·事件循环 | `TuiRunner.create()` + `run(handler, renderer)` | 直观、控制力强（本课） |
| ③ 行内·声明式 | `extends InlineApp` → `run()` | 聊天式 CLI（第 8 课） |
| ④ 行内·事件循环 | `InlineTuiRunner.create(config)` + `run(...)` | 行内 + 自定义事件（片段见参考手册） |
| ⑤ 立即模式 | `Terminal` + `Backend` 全手工 | 最底层，知道即可（片段见参考手册） |

- 全屏 = 接管整个终端（工具面板类）；行内 = 终端照常滚动、底部一小块原地刷新
  （聊天/进度类）。方式越靠上框架托管越多、代码越少，越靠下控制力越强。

### 运行

菜单选 **1**：屏幕上就是这张五式对照表——而那个程序本身正是方式②的实跑，
`CreateTuiDemo.java` 全文只有 40 行。

**自测**：不看示例，说出五种方式的名字；`EventHandler` 与 `Renderer` 各自的方法签名与职责。

---

## 第 2 课 · 渲染模型——画面是什么

**本课学什么**：三个核心概念——**Buffer（画面本身）、Cell（一格）、Frame（渲染入口）**；
以及框架的差分渲染（diff）。

### 示例：HelloTuiDemo 的两个关键片段

**渲染函数**——画一帧，并直接读写网格：

```java
private void paint(Frame frame) {
    // 组件批量画进网格：Paragraph 画进整个屏幕区域
    frame.renderWidget(Paragraph.builder().text(text).build(), frame.area());

    // Frame.buffer() 返回网格本体（Buffer），支持直接读写任意格——
    // 这里往右上角手写当前帧标记 A/B
    Buffer grid = frame.buffer();
    grid.set(grid.area().width() - 2, 0,
            new Cell(showFrameB ? "B" : "A", Style.create().fg(Color.YELLOW).bold()));
}
```

**事件处理**——空格翻转状态位，请求重绘：

```java
if (event instanceof KeyEvent key && key.isChar(' ')) {
    showFrameB = !showFrameB;   // 只改状态
    return true;                // 状态变了 → 请求重绘（下一帧自然反映新状态）
}
```

### 讲解

- **画面由 Buffer 表示**：Cell 的二维数组，一格一个字符+样式。屏幕第 y 行 = 网格
  第 y 行，一格 = 屏幕一个字符位；宽字符（如中文）占「主格 + 续格」；
- **Frame 不是画面，是渲染入口**：`area()` 给可绘区域、`renderWidget(组件, 区域)`
  把组件画进网格、`buffer()` 拿网格本体直接读写（示例第二段）；
- **立即模式**：渲染函数每帧从零描述整屏——不看上一帧、不留旧状态
  （「UI 是状态的纯函数」）。空格只翻转一个布尔位，画面就有整屏重画的理由；
- **diff 是框架的事**：框架对比前后两帧，只把变化的格子发给终端——
  空格切换后，实际发出的字节只有零星几个（退出后打印的 ■ 图就是它们）。

### 运行

菜单选 **2**：按**空格**在帧 A ↔ 帧B 间切换（注意右上角手写的 A/B 标记跟着变）；
按 q 退出后并排打印**两帧网格**与 **diff 视图**（■ = 框架实际发给终端的格子）。

**自测**：Buffer、Cell、Frame 各用一句话说出是什么；diff 视图里为什么只有几处 ■？

---

## 第 3 课 · 文本与样式

**本课学什么**：写带样式的文字——富文本三粒度（Span/Line/Text）、样式链、
颜色三种来源、超宽行为（Overflow）、宽度口径。

### 示例：TextStyleDemo 的核心构造

```java
// 三粒度：Span(带样式的片段) → Line(一行) → Text(段落)
Line title = Line.from(
        Span.styled("第 3 课 · 文本与样式", Style.create().fg(Color.CYAN).bold()),
        Span.raw("  "),
        Span.styled("副标题", Style.create().fg(Color.rgb(70, 130, 180))));
Text full = TextStyleShowcase.demoText()
        .append(Line.from(Span.raw("")))                       // Text 不可变：append 返回新实例
        .append(Line.from(Span.styled("折行段…", Style.create().fg(Color.GRAY))));
frame.renderWidget(Paragraph.builder().text(full)
        .overflow(Overflow.WRAP_WORD).build(), frame.area());  // 不设 overflow 则默认 CLIP 截断！
```

### 讲解

- **Span → Line → Text**：一个带样式的字符串片段；一行由多个 Span 拼接
  （`left()/centered()/right()` 设对齐）；多行组成段落。想加行就 `append(...)`——
  **Text 不可变**，每个方法返回新实例；
- **Style 不可变链**：`Style.create().fg(...).bold().italic().underlined()`，
  链上每个点都返回新对象——组合即安全；
- **颜色三种来源**：命名色 `Color.RED`、256 色 `Color.indexed(208)`、真彩
  `Color.rgb(70,130,180)`；
- **Overflow 是新手第一坑**：`Paragraph` 默认 **CLIP（截断）**——长文本被静默裁掉；
  要折行必须显式 `.overflow(Overflow.WRAP_WORD)`，另有 `ELLIPSIS` 省略号；
- **宽度口径**：`CharWidth.of("中")` = 2。终端对齐按**渲染宽度**算，不按字符个数——
  第 2 课网格里的「主格+续格(·)」就是它的形态。

### 运行

菜单选 **3**：三粒度、三种颜色、对齐、折行、宽度示例一屏全览。

**自测**：Paragraph 默认超宽行为是什么？中文为什么在网格里占两格？

---

## 第 4 课 · 组件画廊

**本课学什么**：常用组件怎么用——Paragraph、Block（边框容器）、List+ListState（列表）、
Gauge（进度条）；以及无状态/有状态组件的分野。

### 示例：GalleryPainter 的四段核心

```java
// ① Paragraph：多行文本（可挂边框）
frame.renderWidget(Paragraph.builder().text(text)
        .block(Block.builder().borders(Borders.TOP_ONLY).build()).build(), paragraphArea);

// ② Block：最基础的容器——边框+标题，常挂在别的组件的 .block(...) 上
Block block = Block.builder().borders(Borders.ALL)
        .borderType(BorderType.ROUNDED).title("② Block").build();
block.render(area, frame.buffer());          // 也可以单独画

// ③ ListWidget + ListState：列表——有状态组件，状态外部持有
frame.renderStatefulWidget(ListWidget.builder()
        .items("一", "二", "三").highlightSymbol("▶ ")
        .block(...).build(), listArea, listState);   // 注意：多传一个状态

// ④ Gauge：进度条
frame.renderWidget(Gauge.builder().ratio(0.62)
        .label(Span.styled("课程进度 62%", green)).block(...).build(), gaugeArea);
```

### 讲解

- 组件分两种：**无状态**——`renderWidget(组件, 区域)` 直接画（Paragraph/Gauge/Block）；
  **有状态**——`renderStatefulWidget(组件, 区域, 状态)`，如列表的选中/滚动由
  `ListState` 在组件外持有。**状态外置**正是「UI = 状态的纯函数」能成立的原因；
- **Block** 官方称「最基础的容器」：`borders(...)` 哪些边、`borderType(...)` 线型
  （PLAIN/ROUNDED/DOUBLE/THICK…）、`title(...)` 标题；
- TamboUI 全部 **26 个组件、8 大类**（容器/文本/选择/数据可视化/动画/输入/工具/Markdown），
  全景见参考手册的官方链接——课程演示最常用的四个。

### 运行

菜单选 **4**：四块演示区逐个亮相。注意四块区域目前是**手写的 Rect 坐标**——
下一课把手摆升级成约束切分。

**自测**：无状态与有状态组件的渲染调用差在哪？为什么状态要外置？

---

## 第 5 课 · 布局

**本课学什么**：把手摆坐标升级成**约束切分**——Layout 与 Constraint 是
ratatui 系框架布局的全部语言。

### 示例：DashboardLayout 的切分

```java
/** 整屏三段：固定 3 行头 + 60% 中段 + 剩余全给尾。 */
public static List<Rect> split(Rect screen) {
    return Layout.vertical()
            .constraints(Constraint.length(3),      // 固定 3 行
                    Constraint.percentage(60),       // 按比例
                    Constraint.fill())               // 吃掉剩余
            .split(screen);                          // → 切好的子区域列表
}

/** 中段再左右切：List 占 60%，Gauge 吃剩余——切分可嵌套。 */
public static List<Rect> splitMiddle(Rect middle) {
    return Layout.horizontal()
            .constraints(Constraint.percentage(60), Constraint.fill())
            .split(middle);
}
```

### 讲解

- 一句口诀：**先切区域，再往区域放组件**。`Layout.vertical()/horizontal()`
  .`constraints(...)`.`split(大区域)` 返回 `List<Rect>`，每个子区域照常
  `renderWidget` / `renderStatefulWidget`；
- 约束四种常用：`length(n)` 固定、`percentage(n)` 比例、`fill()` 吃剩余、
  `min/max` 上下限（另有 ratio/fit）；
- **嵌套**是布局的威力所在：拿子区域再 `split` 就是多面板——示例里中段先上下切、
  再左右切，两行代码；
- 窗口缩放时框架自动重切重绘——区域跟着比例伸缩，不用你算一个坐标。

### 运行

菜单选 **5**：头（固定）/ 中段（60%，再切成 List+Gauge）/ 尾（吃剩余）——
正是第 4 课画廊「手摆区域」的约束版。拉伸终端窗口看区域自适应。

**自测**：想实现「顶部 3 行、底部 1 行、中间平分两栏」，约束怎么写？

---

## 第 6 课 · 事件与交互

**本课学什么**：让应用动起来——「事件 → 状态 → 请求重绘 → 下一帧反映」这条链路，
是 TUI 交互的全部。

### 示例：EventsDemo 的状态机与接线

```java
// 状态机（纯逻辑，可单测）：事件只改状态，并回答「是否需要重绘」
public boolean onKey(KeyEvent key) {
    if (key.isChar('j') || key.isKey(KeyCode.DOWN)) {
        lessonIndex = Math.min(LESSON_COUNT - 1, lessonIndex + 1);
        lastKey = key.toString();
        return true;     // 状态变了
    }
    if (key.isChar('k') || key.isKey(KeyCode.UP)) { /* 对称，略 */ }
    return false;        // 无关键：状态没变，不请求重绘
}

// handler 接线：事件交给状态机，渲染交给面板
EventHandler handler = (event, r) -> {
    if (event instanceof KeyEvent key) {
        if (key.isQuit()) { r.quit(); return false; }
        boolean changed = state.onKey(key);
        listState.select(state.lessonIndex());   // 状态对象是唯一事实源
        return changed;
    }
    if (event instanceof TickEvent) { state.onTick(); return true; }   // 动画
    return false;
};
```

### 讲解

- 事件三类：**KeyEvent**（`isChar('j')` 字符判定、`isKey(KeyCode.UP/ENTER/ESCAPE)`、
  `isQuit()`——默认 q/Q/Ctrl+C，**Esc 是 cancel 动作、不退出**）；
  **TickEvent**（周期重绘脉冲，自带 `elapsedSeconds()`/`frameCount()`，
  真实时间动画直接读）；**ResizeEvent**（缩放自动重绘，通常无需处理）；
- **铁律：事件只改状态，渲染只看状态**。两者经 handler 的返回值（是否重绘）桥接；
  状态机因此能抽成纯类离线单测（示例正是如此）；
- 开 tick：`TuiConfig.builder().tickRate(Duration.ofMillis(100)).build()` 传给
  `TuiRunner.create(config)`；**按需重绘**（没变返回 false）是省终端字节的基本纪律；
- 中文输入法提示：`isChar('+')` 只匹配半角——全角 ＋ 需自己兼容
  （第 7 课示例的 `isPlus` 就是现成写法）。

### 运行

菜单选 **6**：j/k 或方向键移动选中、Tick 驱动 Gauge 走动、底部显示最近按键。

**自测**：按一次 j 之后屏幕为什么会变？把链路五环（事件→状态→返回值→重绘→新帧）说全。

---

## 第 7 课 · Toolkit DSL（声明式）

**本课学什么**：同样的应用换一种省事的写法——`render()` 只声明 UI 树，
布局/焦点/输入编辑全交给框架（官方推荐的日常主力）。

### 示例：ToolkitDslDemo 的骨架与交互惯例

```java
public final class ToolkitDslDemo extends ToolkitApp {
    private final TextAreaState notes = new TextAreaState();
    private int targetLines = 3;

    @Override
    protected TuiConfig configure() {              // 键位定制：退出只留 Ctrl+C（要能打字）
        TuiConfig base = super.configure();
        return base.toBuilder().bracketedPaste(true)
                .bindings(base.bindings().toBuilder()
                        .rebind(KeyTrigger.ctrl('c'), Actions.QUIT).build()).build();
    }

    @Override
    protected void onStart() { notes.insert("在这里记点什么…"); }

    @Override
    protected Element render() {                   // 只声明这棵树——没有一行自绘
        String focusedId = runner().focusManager().focusedId();
        return column(
                panel("第 7 课 · Toolkit DSL——render() 只声明这棵树",
                        text("布局/焦点/输入编辑都是框架的事")),
                row(
                        panel(FocusMarks.title("笔记（原生 textArea）", "notes-input".equals(focusedId)),
                                textArea(notes).id("notes-input")),      // 输入框：零键盘代码
                        column(
                                panel("统计（每帧现算）", richText(stats)),
                                panel(FocusMarks.title("行数目标", "target".equals(focusedId)),
                                        text("目标: " + targetLines + " 行")).id("target")
                                        .focusable().onKeyEvent(this::onTargetKey))));
    }
}
```

### 讲解

- `ToolkitApp` 三件套：`render()` 返回整棵树（声明式）、`configure()` 定制键位、
  `new Xxx().run()` 自举——不用自己建 runner；
- 工厂（`Toolkit.*`）：`text/richText/column/row/panel/spacer`；
  `InlineToolkit.scope(条件, 子元素)` 条件隐藏（面板收起）；
- **`textArea(state)` 一行得到完整输入框**：多行编辑、光标、中文输入免写键盘代码——
  对比第 6 课手写的 handler，这是声明式的直接收益；
- **自定义按键惯例**：`.id("唯一id").focusable().onKeyEvent(k -> HANDLED/UNHANDLED)`。
  两个坑：可聚焦元素**必须显式 id**（无 id 不进焦点链）；多个可聚焦元素用 **Tab** 切换
  焦点，而**框架不画焦点标识**——示例用 `focusManager().focusedId()` 给标题加 ▶ 自绘；
- `configure` 里 `rebind` 是**整组替换**：`rebind(KeyTrigger.ctrl('c'), Actions.QUIT)`
  之后退出只剩 Ctrl+C（默认的 q/Q 会被输入框吃掉反而误触发）。

### 运行

菜单选 **7**：Tab 切焦点（看 ▶ 跳动）、+/- 调行数目标、输入框打中文，
统计面板实时对照达标。

**自测**：为什么这个应用的退出键只剩 Ctrl+C？两个可聚焦元素怎么互相切换？

---

## 第 8 课 · 行内模式——衔接 code-tui

**本课学什么**：换到行内轴——终端照常滚动、底部一小块 live 区原地刷新；
理解「**live 区差分重绘 + 定稿内容 println 下沉**」双通道。这是 code-tui 主视图的形态。

### 示例：InlineChatDemo 的骨架

```java
public final class InlineChatDemo extends InlineApp {
    private final FakeStreamer streamer;    // 假流式（定时吐字的模型替身）

    @Override
    protected int height() { return 4; }              // 空态 live 区高度（钉在终端底部）

    @Override
    protected InlineTuiConfig configure(int h) {
        return super.configure(h).toBuilder().bracketedPaste(true).build();
    }

    @Override
    protected Element render() {                      // live 区：只放「还会变的东西」
        return column(
                scope(streamer != null && !tail.isEmpty(), richText(残行)),   // 流式残行原地刷新
                scope(streamer != null, text("⟐ 生成中…")),                    // 用完即收起
                inputElement(),                                                  // 输入框
                statusLine());
    }

    private void onStreamTick() {                     // 定时驱动：残行刷新；定稿行下沉
        streamer.advance();
        for (String line : streamer.drainNewlySettled()) {
            runner().println(line);                   // ← 第二条通道：沉入 scrollback，不再重绘
        }
    }
}
```

### 讲解

- **行内 vs 全屏**：行内保留 scrollback（聊天/进度类 CLI 的需要），live 区钉在底部、
  原地刷新；全屏则整个终端都是你的、退出复原；
- **双通道各司其职**：live 区（`render()` 声明）只放还会变的东西——残行、面板、
  输入框、状态行；定稿内容立刻 `runner().println(...)` 沉入 scrollback，从此不再重绘；
- `scope(条件, 子元素)` 隐藏时占位归零 → 「生成中…」面板用完即收起，零额外代码；
- `InlineApp` 钩子：`height()` / `render()` / `configure(int)` / `onStart()`；
  定时驱动用 `runner().scheduleRepeating(...)`，状态改动包进 `runOnRenderThread(...)`；
- **衔接 code-tui**：`CodeTuiView` 就是本课的放大版（javadoc 里有逐钩子对照表）。
  生产上 code-tui 用仓库的 inline-patch 对行内渲染做了工程加固——一句话了解，
  见参考手册「衔接 code-tui」。

### 运行

菜单选 **8**：输入框打字（中文 OK）、Enter 发送——用户行与假流式的定稿行逐行
沉入上方 scrollback，残行在 live 区原地刷新，流完「⟐ 生成中…」收起。

**自测**：为什么聊天记录要走 println 下沉而不是留在 live 区反复重绘？

---

## 毕业自测

八课读完，用三个问题检验（答案都在正文里）：

1. 五种创建方式各自什么时候用？——第 1 课；
2. 按一次按键之后屏幕为什么会变？——第 6 课五环链路；
3. 什么时候选行内模式？双通道怎么分？——第 8 课。

能全答上来，第⑥步 code-tui 的源码就在等你——`CodeTuiView` 是第 8 课的放大版，
`implementation-map.md` 是它的地图。

---
# 参考手册（正文学完后回来查）

## 术语表

| 术语 | 含义 |
|---|---|
| 差分（diff） | 框架逐格对比新旧两帧的 Buffer，只把内容不同的格子发给终端——1920 格的屏改 20 格，就只发 20 格 |
| 立即模式 | 渲染函数每帧从零全量描述整屏、不留旧状态的写法（「UI 是状态的纯函数」）；与「保留组件树的声明式」相对 |
| 备用屏幕 | 终端的一块独立画布（alternate screen），进入后原内容不动、退出即复原——全屏应用的载体 |
| 原始模式（raw mode） | 关闭终端默认的「逐行回显/行缓冲」，按键即刻到达程序——TUI 接管输入的前提 |
| Buffer / Cell | 画面本身（字符网格）/ 网格中的一格（一个字符 + 样式） |
| 续格 | 宽字符（如中文）在网格中占的第二格，符号为空、`isContinuation()` 为真 |
| scrollback | 终端向上滚动可见的历史输出区；行内模式保留它，全屏模式不经过它 |
| live 区 | 行内模式中钉在终端底部、原地刷新的区域（输入框/进行中的输出） |
| 括号粘贴 | 终端把粘贴内容包在 ESC[200~/201~ 标记里整体发送，程序据此区分「粘贴」与「逐键」 |
| 全角/半角 | 同一字符的中文输入法形态（＋）与 ASCII 形态（+）——按键判定两者都要兼容 |

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

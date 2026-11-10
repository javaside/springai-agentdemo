# springai-tamboui-demo

[TamboUI](https://tamboui.dev)（Java TUI 框架，仿 ratatui）的入门课程——学习路线**第⑤步**。
六课递进，跟着做完就能用这个框架写自己的终端应用；最后一课衔接到第⑥步 code-tui 的视图。
基于 **TamboUI 0.4.0**（版本随根 pom `tamboui-bom`），不需要任何 API key。

## 课程表

| 课 | 主题 | 你将学到 | 本课核心认知 |
|---|------|---------|-------------|
| 1 | Hello TUI——渲染模型 | `TuiRunner` 全屏应用、`Frame/renderWidget`、Paragraph；**空格在帧 A↔B 间切换**，退出后并排打印两帧网格 + diff 视图（■=框架实际发送的格子） | 屏幕是字符网格；render 只管整屏描述当前状态；框架做 diff 只发变化的格子 |
| 2 | 文本与样式 | `Span/Line/Text` 三粒度、`Style` 不可变方法链、颜色三种来源（命名/256/RGB）、对齐、Paragraph 折行、宽度口径（ASCII 1 列 / 中文 2 列） | 终端富文本从「带样式的片段」开始；宽度是渲染口径 |
| 3 | 组件与布局 | `Block`（边框/标题）、`ListWidget`（第一个 StatefulWidget）、`Gauge`、`Layout.split` + `Constraint`（length/percentage/fill）、嵌套切分 | 先把屏幕切成区域，再往区域放组件；有状态组件的状态外部持有 |
| 4 | 事件与交互 | `KeyEvent`（j/k 与方向键）、状态机驱动 UI、`TickEvent` 动画、按需重绘 | 事件只改状态，「请求重绘」是状态通往屏幕的唯一桥梁 |
| 5 | Toolkit DSL | `ToolkitApp`、`panel/column/row/text/richText/scope`、原生 `textArea`、键位定制 | 声明式：render() 只声明 UI 树，布局/焦点/输入编辑全交给框架（官方推荐主路径） |
| 6 | 行内模式——衔接 code-tui | `InlineTuiRunner`、live 区 + `println` 下沉 scrollback 双通道、迷你聊天 | 聊天式 CLI 为何用行内；code-tui 的 `CodeTuiView` 是本课的放大版 |

**建议学法**：按序做，每课跑一遍、读一遍源码（每个示例类都带逐行注释）；
第 4 课的状态机、第 6 课的双通道是读 code-tui 源码前最值得吃透的两个模式。
顺带一提：窗口缩放由框架自动触发重绘（渲染函数经 `frame.area()` 拿到新尺寸），不需要自己处理。

## 怎么创建一个 TUI 应用（入口与选型）

**两条轴 × 两个层次 = 四种方式 + 一个底层入口**。轴 = 全屏（备用屏幕接管整个终端）还是
行内（终端照常滚动，底部一小块 live 区——聊天式 CLI 的形态）；层次 = 声明式（只写
`render()`，框架管事件）还是事件循环（自己写 handler）。

| 方式 | 入口 | 代码在哪 | 适用 |
|---|---|---|---|
| ① 全屏·声明式 | `class App extends ToolkitApp` → `new App().run()` | 第 5 课 `ToolkitDslDemo.java` | 大多数应用的首选（官方推荐主路径） |
| ② 全屏·事件循环 | `TuiRunner.create()` + `run(handler, renderer)` | 第 1–4 课（`HelloTuiDemo.java` 等） | 完全控制事件/动画/自绘 |
| ③ 行内·声明式 | `class Chat extends InlineApp`（钩子 `height()/render()/configure(int)`）→ `run()` | 第 6 课 `InlineChatDemo.java` | 聊天式 CLI、保留 scrollback |
| ④ 行内·事件循环 | `InlineTuiRunner.create(InlineTuiConfig)` + `run(handler, renderer)` | 课程无完整示例——③ 的 `InlineApp` 底层正是它（片段见下） | 行内 + 自定义事件处理 |
| ⑤ 立即模式（底层） | `BackendFactory.create()` → `new Terminal<>(backend)` → 手动 raw 模式/备用屏幕 + `terminal.draw(...)` | 课程无示例（片段见下）；第 1 课打印的网格就是它操作的 Buffer | 教学理解、框架内嵌、极端控制 |

④ 与 ⑤ 课程未做成完整示例（主线全屏优先），最小用法片段：

```java
// ④ 行内·事件循环（InlineApp 之下的一层）
try (InlineTuiRunner runner = InlineTuiRunner.create(
        InlineTuiConfig.builder(4).bracketedPaste(true).build())) {   // 4 = live 区初始行数
    runner.run((event, r) -> false,                                    // 事件处理：返回是否重绘
            frame -> frame.renderWidget(Paragraph.from("hi"), frame.area()));   // 画帧
}

// ⑤ 立即模式：Terminal + Backend 全手工（无 Runner、无组件框架）
try (Backend backend = BackendFactory.create()) {    // 默认装配 jline3 后端
    backend.enableRawMode();                         // 一切终端状态自己管
    backend.enterAlternateScreen();
    Terminal<Backend> term = new Terminal<>(backend);
    term.draw(frame -> frame.buffer().setString(0, 0, "hello", Style.EMPTY));  // 直接往网格画
    backend.disableRawMode();
    backend.leaveAlternateScreen();
}
```

**Backend 的三种实现**（BOM 0.4.0 实测管理，按需换依赖即可）：`tamboui-jline3-backend`
（本课程所用，纯 Java、兼容面广）、`tamboui-panama-backend`（Panama/FFM）、
`tamboui-aesh-backend`。

## 每课 API 速查（重要的类怎么用）

读课程源码时对照本节：每课出现的核心类、创建方式与关键方法，全部按 0.4.0 实测签名编写。

### 第 1 课：跑起来一个全屏应用

```java
try (TuiRunner runner = TuiRunner.create()) {      // 进备用屏幕 + 原始模式，接管终端
    runner.run(handler, renderer);                 // 事件循环（阻塞到 quit），handler 管事件、renderer 画帧
}
```

| 类 | 角色 | 关键用法 |
|---|---|---|
| `TuiRunner` | 全屏应用发动机 | `create()` 接管终端；`run(handler, renderer)` 启动循环；`quit()` 退出；try-with-resources 保证终端复原 |
| `EventHandler` | 事件处理 | `(event, runner) -> boolean`：处理事件并返回「是否需要重绘」；退出键里调 `runner.quit()` |
| `Renderer` | 画帧函数 | `frame -> {...}`：把整帧画进 frame，不看上一帧（差分是框架的事） |
| `Frame` | 渲染入口 | `area()` 可绘区域；`renderWidget(组件, 区域)`；`buffer()` 网格本体（Cell 二维数组，可 `get/set` 直接读写格子） |
| `Paragraph` | 文本段组件 | `Paragraph.builder().text(文本).build()`，可选 `.block(边框)` `.centered()` `.overflow(...)` |
| `KeyEvent` | 按键事件 | `isQuit()`（默认 q/Q/Ctrl+C）、`isChar('x')` 字符键判定 |

### 第 2 课：文本与样式

```java
Span s  = Span.styled("片段", Style.create().fg(Color.RED).bold()); // 带样式的字符串片段
Line l  = Line.from(s, Span.raw(" + "), s2).centered();             // 一行 = 多个 Span 拼接
Text t  = Text.from(line1, line2).append(line3);                    // 段落；不可变——append/push 返回新实例
```

| 类 | 角色 | 关键用法 |
|---|---|---|
| `Style` | 不可变样式 | `Style.create().fg(Color).bold().italic().underlined()`——链式调用每个点都返回新对象 |
| `Color` | 颜色 | 命名色 `Color.RED`；256 色 `Color.indexed(208)`；真彩 `Color.rgb(70,130,180)` |
| `CharWidth` | 宽度口径 | `CharWidth.of("中文A")` → 5（中文 2 列、ASCII 1 列） |
| `Overflow` | 超宽处理 | `WRAP_WORD` 折行 / `CLIP` 截断（**默认**，长文本静默裁掉）/ `ELLIPSIS` 省略号 |

### 第 3 课：组件与布局

```java
List<Rect> 三段 = Layout.vertical()
        .constraints(Constraint.length(3), Constraint.percentage(60), Constraint.fill())
        .split(frame.area());                       // 切分可嵌套：拿子 Rect 再 split 就是多面板
frame.renderWidget(paragraph, 三段.get(0));          // 无状态组件
frame.renderStatefulWidget(listWidget, 三段.get(1), listState);   // 有状态组件多传一个状态
```

| 类 | 角色 | 关键用法 |
|---|---|---|
| `Block` | 边框/标题 | `Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED).title("标题").build()`，常挂在组件的 `.block(...)` |
| `ListWidget` + `ListState` | 列表（有状态组件典范） | 组件与状态分家：`ListWidget.builder().items("一","二").highlightSymbol("▶ ").build()`；状态 `new ListState()` 后 `select(i)` / `selectNext(n)`，渲染时传入 |
| `Gauge` | 进度条 | `Gauge.builder().ratio(0.62).label(Span).build()` |
| `Layout` + `Constraint` | 区域切分 | `Layout.vertical()/horizontal().constraints(...).split(Rect) → List<Rect>`；约束 `length(n)` 固定 / `percentage(n)` 比例 / `fill()` 吃剩余（另有 min/max/ratio/fit） |

### 第 4 课：事件与交互

```java
if (event instanceof KeyEvent key && key.isQuit()) { runner.quit(); return false; }
boolean changed = state.onKey(key);   // 事件只改状态（状态机独立成类，可单测）
return changed;                       // 状态变了才返回 true 请求重绘
```

| 类 | 角色 | 关键用法 |
|---|---|---|
| `KeyEvent` | 按键 | `isChar('j')` 字符判定、`isKey(KeyCode.UP/DOWN/ENTER/ESCAPE)`、`toString()` 供显示 |
| `TickEvent` | 周期重绘脉冲 | 自带 `elapsedSeconds()` / `frameCount()`——真实时间动画直接读，不用自己计时 |
| `ResizeEvent` | 窗口缩放 | 框架自动触发重绘，渲染经 `frame.area()` 拿新尺寸，通常无需处理 |
| `TuiConfig` | 运行配置 | `TuiConfig.builder().tickRate(Duration.ofMillis(100)).build()` → `TuiRunner.create(config)` |

### 第 5 课：Toolkit DSL（声明式）

```java
public class MyApp extends ToolkitApp {
    protected TuiConfig configure() { /* 键位/行为定制，可省略 */ }
    protected Element render() {                      // 只声明 UI 树，布局/焦点/输入交给框架
        return column(panel("标题", textArea(state)), text("状态行"));
    }
}
new MyApp().run();                                    // 自举（无需自己建 runner）
```

| API | 角色 | 关键用法 |
|---|---|---|
| `Toolkit.*` 工厂 | 造元素 | `text(...)` `richText(Text)` `column(...)` `row(...)` `panel("标题", ...)` `spacer()`；`InlineToolkit.scope(条件, 子元素)` 条件隐藏（面板收起） |
| `.id().focusable().onKeyEvent(...)` | 自定义按键惯例 | 可聚焦元素**必须显式 `.id("唯一id")`**（无 id 不进焦点链）；handler 返回 `EventResult.HANDLED` 吃掉该键 |
| 焦点 | 谁在接收按键 | 多个可聚焦元素用 Tab 切换；`runner().focusManager().focusedId()` 查当前焦点（框架不画焦点标识，应用自绘 ▶） |
| `TextAreaState` | 输入框编辑模型 | `insert(...)` / `text()` / `clear()`；配 `Toolkit.textArea(state)` 得到完整输入框（光标/多行/中文免写键盘代码） |
| `Bindings` | 键位定制 | `config.bindings().toBuilder().rebind(KeyTrigger.ctrl('c'), Actions.QUIT).build()`——rebind 是整组替换 |

### 第 6 课：行内模式（衔接 code-tui）

```java
public class Chat extends InlineApp {
    protected int height() { return 4; }              // 空态 live 区高度（钉在终端底部）
    protected InlineTuiConfig configure(int h) { ... }
    protected Element render() { ... }                // live 区（还会变的东西）
    // 定稿内容走另一条通道：runner().println("...") 下沉 scrollback，不再重绘
}
```

| API | 角色 | 关键用法 |
|---|---|---|
| `InlineApp` 钩子 | 行内应用骨架 | `height()` / `render()` / `configure(int)` / `onStart()`；工具方法 `runner()` / `println(...)` / `quit()`；`run()` 自举 |
| `InlineTuiConfig` | 行内配置 | `builder(n)` n=初始行数；`.bracketedPaste(true)` 括号粘贴（多行粘贴整体到达，不拆成逐键） |
| 定时驱动 | 流式输出等 | `runner().scheduleRepeating(runnable, Duration)`；状态改动包进 `runOnRenderThread(...)`（上游 UiRunnable 执行后不触发重绘，行内示例因此保留默认 tick） |

## 每课动手自测（读懂 ≠ 会用，各改一处再跑）

| 课 | 动手 |
|---|------|
| 1 | 把帧 B 的 ◆ 挪个位置（改 `paint` 里一行），重跑看 diff 视图里 ■ 怎么变 |
| 2 | 把折行段的 `overflow(WRAP_WORD)` 分别换成 `CLIP` / `ELLIPSIS`，看三种裁断的差异 |
| 3 | 把中段切分 `percentage(60)` 改成 `percentage(30)`，观察 List/Gauge 两栏怎么变 |
| 4 | 给状态机加一个 `g` 键：按下 Gauge 直接跳到 100（模仿 `onKey` 里 j/k 的写法） |
| 5 | 给统计面板再加一行「最长一行的字符数」（提示：`NotesStats` 加字段 + 测试先行） |
| 6 | 把 `REPLY` 换成你自己的多行文本，观察定稿行逐行沉入 scrollback |

## 本课程没教的（进阶出口，官方文档有全解）

课程覆盖入门主线；以下能力未展开，需要时去 [官方文档](https://tamboui.dev/docs/main/index.html) 对应章节：
更多组件（Table/Chart/Canvas/Calendar/Scrollbar…）、CSS/TCSS 样式、Markup（BBCode 风格）文本、
Markdown 渲染、Forms 表单与验证、Pilot 测试框架、GraalVM 原生镜像打包。

## 运行

必须在**真实终端**里运行（IDE 控制台/管道是 dumb 终端，颜色与交互看不全）：

```bash
mvn -q -pl springai-tamboui-demo -am package -DskipTests
java -jar springai-tamboui-demo/target/springai-tamboui-demo-<version>.jar
```

第 1–5 课是全屏应用（q / Esc / Ctrl+C 退出，终端自动复原）；第 6 课是行内聊天，
输入框里打字、Enter 发送、Ctrl+C 退出。

## 与官方 API 层级的对应

TamboUI 官方把 API 分四层，本课程的走法是「从内到外再落地」：

| 官方层级 | 用途 | 课程 |
|---|---|---|
| Immediate Mode（立即模式） | Buffer/Cell 直接控制 | 第 1 课以「Buffer 网格」见识，第 2–4 课的组件都渲染到它 |
| TuiRunner | 全屏事件循环 | 第 1–4 课的骨架 |
| Toolkit DSL | 声明式组件 UI（推荐主路径） | 第 5 课 |
| Inline Display | CLI 行内进度/聊天 | 第 6 课 |

## 衔接 code-tui 与 inline-patch 说明

第 6 课的迷你聊天就是 code-tui 主视图 `CodeTuiView` 的缩影（`InlineApp` + 双通道 + `scope` 收起面板）。
code-tui 在生产上用仓库内的 [springai-tamboui-inline-patch](../springai-tamboui-inline-patch)
对行内渲染与事件解析做了工程加固（差分帧提交、防 pty 写阻塞、粘贴预算等）——
第 6 课 javadoc 里的「工程备注」说明了本课程（上游原版）与它的差异点，感兴趣再去读那个模块。

## 版本口径

本课程锁 **0.4.0**（随根 pom `tamboui-bom` 统一管理）。[官方文档](https://tamboui.dev/docs/main/index.html)
对应更新的快照版（0.7.0-SNAPSHOT），个别 API 有出入——以本仓库 jar 的 javadoc 为准，
官方文档用于理解概念与查阅组件全景。

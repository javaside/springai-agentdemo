# ScrollbackPrinter 抽取设计（CodeTuiView 重构 2/4）

**日期**：2026-07-03
**范围**：`springai-code-tui` · `com.example.springai.codetui.ui.CodeTuiView`
**前序**：重构 1/4「抽出 Theme 配色类」（commit `27187bc`）已完成。

---

## 目标（一句话）

把 CodeTuiView 里「渲染 agent/系统输出并下沉到 scrollback」的一簇方法抽成独立的
`ScrollbackPrinter`，让视图类只管布局 + 输入 + 状态行，且把 markdown/diff 渲染变得**可单测**。

## 为什么做（向好的方向，而非为重构而重构）

当前 CodeTuiView 850 行，混着 5 类职责：布局 render()、输入（InputBox/键路由/历史/斜杠菜单/模型选择器）、
**scrollback 打印**、状态行、上下文统计。其中「scrollback 打印」是一簇**高内聚、与输入/菜单状态零耦合**的方法：

```
printWelcome / welcomeLine / printlnUserBlock / printlnToolStart / diffLine / bodyLine / langOf
```

它们共同的唯一职责是：**把一条 agent/系统输出（欢迎横幅 / 用户回显 / 助手 markdown / 工具 diff）
渲染成带样式的 Text，并 println 进 scrollback**。抽出它带来三个实打实的收益：

1. **视图彻底摆脱 3 个协作者**：`MarkdownRenderer`、`DiffRenderer`、`SyntaxHighlighter` 变成
   printer 内部实现，视图不再 import / 不再知道 markdown+diff 怎么渲染。
2. **可单测**：这簇逻辑（diff 行号、增删底色铺满、gutter 对齐、markdown 高亮）现在因为埋在
   `InlineApp` 里而无法单测。抽成独立类、以「输出 sink」注入后，可喂 `OutputLine` 断言产出的 `Text`。
3. **视图 850 → ~700 行**，聚焦布局 + 输入 + 状态。

这不是搬砖：边界是天然的（该簇不碰 `pickingModel`/`slash*`/`history`/输入态），职责单一，风险低。

## 设计

### 新类 `ScrollbackPrinter`（package-private, final）

```
持有（移入本类）：
  MarkdownRenderer md      —— 「渲染 agent markdown」单一职责，finalized（scrollback）与 preview（实时预览）同源
  DiffRenderer     diff    —— 仅本簇的 printlnToolStart 用到

依赖（构造注入）：
  Sink        sink                        —— 输出接缝（见下）：真实=终端 scrollback，测试=内存列表
  Path        root                        —— 欢迎横幅 cwd 行 + DiffRenderer 构造
  IntSupplier terminalWidth               —— 终端列宽（视图侧 runner().tuiRunner().width()）
  BiFunction<String,Integer,List<String>> wrap  —— 软折行；见「共享辅助」小节

公开 API：
  void       welcome(String model)     —— 原 printWelcome（model 由调用方传入，printer 不依赖 SubmitHandler）
  void       userBlock(String text)    —— 原 printlnUserBlock；内部先 md.reset()（新回合清代码围栏态）
  void       assistant(String text)    —— indented(md.renderFinalized(text)) 后 println
  void       streamingLine(String row) —— 同 assistant，供流式完整行
  void       toolStart(OutputLine ol)  —— 原 printlnToolStart（edit/write 展开 diff，其余单行摘要）
  void       line(OutputLine ol)       —— drain 的 default 分支：按 Theme.styleFor(kind) 上色或原样 println
  Text       preview(String tail)      —— indented(md.renderPreview(tail))，供视图 render() 的流式预览（返回带缩进 Text）

私有：welcomeLine / diffLine / bodyLine / langOf / gutter / indented

嵌套接缝（测试性 + 解耦）：
  interface Sink { void println(Text line); void println(String line); }
```

### 输出接缝 `Sink`：printer 不认识 tamboui runner

printer 不直接持有 `InlineToolkitRunner`，而是一个两方法的 `Sink`（`println(Text)` / `println(String)`）。
理由有二，都指向本次抽取的核心目标：

1. **可单测**（抽取的三大收益之一）：测试传一个记录型 `Sink`（把行收进 `List`），无需 mock tamboui 的
   `InlineToolkitRunner`（很可能是 final/难构造），即可断言 printer 产出的 `Text`。
2. **解耦 + 解决时序坑**：视图用匿名 `Sink` 把 `runner()` 惰性桥接进来——
   `new Sink(){ public void println(Text t){ runner().println(t); } public void println(String s){ runner().println(s); } }`。
   构造函数里就能 new printer；`runner()` 只在 println 时（{@code onStart} 之后）才被解引用，故无「构造时 runner 未就绪」问题、无需 null 守卫。

> 这不是为解耦而解耦：`Sink` 恰是让「markdown/diff 渲染可单测」这一收益落地的最小接缝，两个方法、零额外类文件（嵌套在 printer 内）。

### `md` 归属：移入 printer（不共享引用）

`md` 是有状态对象（代码围栏跨行状态）。它服务两处：finalized 行（scrollback）与 preview（未完成残行的
实时预览）——二者本是**同一个「渲染 agent markdown」职责**，且必须共享同一份围栏状态。故 `md` 整体移入
printer，视图 `render()` 的预览改走 `printer.preview(tail)`，视图不再持有 `md`。

> 线程安全：`render()`（渲染线程绘帧）与 `drain()`（经 `runOnRenderThread`）同在渲染线程，`md` 无并发。

### 构造时机：视图构造函数内建，sink 惰性桥接 runner

printer 在 `CodeTuiView` 构造函数里就 new 出来，`sink` 用匿名类把 `runner()` 惰性桥接进来（见「输出接缝」）。
构造时不解引用 `runner()`；`preview` 只用 `md` 不碰 sink；`welcome`/`userBlock`/… 的 println 只在 `onStart` 之后
（欢迎横幅经 `runOnRenderThread`、drain 经 `scheduleRepeating`）才触发，此时 runner 已就绪。**无需 null 守卫，无时序坑。**

### 共享辅助的处理（关键，避免为重构而制造工具类）

三个低层辅助被 printer 与视图输入/菜单代码**共用**，逐一按最小改动处理，**不新建工具类**：

| 辅助 | 处理 | 理由 |
|---|---|---|
| `displayWidth(String)` | printer 内直接调 `dev.tamboui.text.CharWidth.of(...)` | 它本就是一行别名；代码里已有 ~5 处直接用 CharWidth.of。视图保留自己的私有 `displayWidth`。**不共享。** |
| `INDENT` 常量 | printer 定义自己的 `private static final String INDENT = "  "` | 平凡常量，2 字符。**不共享。** |
| `wrapSegments` | 留在 CodeTuiView（视图 InputBox/visualRowCount 仍需），以 `this::wrapSegments` 注入 printer | 唯一有实质逻辑（~12 行按显示宽度折行）的共享方法。注入一个 `BiFunction` 依赖，**零重复、零新类、printer 不反向依赖视图**。 |

`gutter`/`GUTTER`/`langOf`/`diffLine`/`bodyLine`/`indented` 抽出后**仅 printer 用**，直接作为 printer 私有成员，
不再是视图的东西。`lastLine` 抽出后**仅视图 render() 用**，留在视图。

### 调用点改动（视图侧）

```java
// 字段：删除 md、diff（移入 printer）；新增 printer
private final ScrollbackPrinter printer;

// 构造函数末尾：sink 匿名桥接 runner()（惰性），printer 就地 new
ScrollbackPrinter.Sink sink = new ScrollbackPrinter.Sink() {
    @Override public void println(Text t)   { runner().println(t); }
    @Override public void println(String s) { runner().println(s); }
};
this.printer = new ScrollbackPrinter(sink, root, this::terminalWidth, CodeTuiView::wrapSegments);

// render() 流式预览行（原 121 行）——preview 已返回带缩进 Text，indented 随之移入 printer
scope(!tail.isEmpty(), richText(printer.preview(tail)).ellipsisStart()),

// onStart：欢迎横幅
runner().runOnRenderThread(() -> printer.welcome(onSubmit.currentModel()));

// drain() 收敛为 4 分支纯分发
for (OutputLine ol : state.drainPending()) {
    switch (ol.kind()) {
        case USER      -> printer.userBlock(ol.text());
        case ASSISTANT -> printer.assistant(ol.text());
        case TOOL_START-> printer.toolStart(ol);
        default        -> printer.line(ol);
    }
}
for (String row : state.takeCompleteStreamingLines()) printer.streamingLine(row);
```

> `indented` 决策：它抽出后视图仅剩 `render()` 预览一处用。为让视图彻底不碰渲染细节，**把 `indented`
> 移入 printer**，并让 `preview` 返回**已缩进**的 `Text`（`indented(md.renderPreview(tail))`）。这样视图
> render() 只写 `richText(printer.preview(tail)).ellipsisStart()`，`indented` 成为 printer 私有。

## 行为契约（必须逐字保持）

纯搬运，**零行为改动**。具体不变量：
- 欢迎横幅：宽度 `min(max(width-1,48),76)`、圆角边框、6 行内容、末尾一空行——逐字节不变。
- 用户块：灰底白字、按终端宽软折行、每行右侧补白铺满。
- 工具 diff：真实行号、gutter 右对齐 4 位、ADD/DEL 底色铺满整行、CONTEXT 仅高亮不上底、
  跨行块注释状态按 body 顺序推进、非文件写入回退单行摘要。
- 助手/流式：`indented(md.renderFinalized)`；新回合（USER）先 `md.reset()`。
- `styleFor` 映射（ASSISTANT→默认色…）不变。

## 测试策略

1. **回归**：现有离线套件 89/89 必须继续全绿（含 `MenuRowRenderTest` 等 UI 测试）。
2. **新增 `ScrollbackPrinterTest`**（无头单测，不经 InlineDisplay）：
   - 用记录型 `Sink`（把 `println` 的 `Text`/`String` 收进 `List`）替代真实 runner——`Sink` 接缝正是为此而设，
     无需 mock tamboui。断言 `userBlock`/`welcome`/`toolStart` 产出的行数与关键 span（如 diff ADD 行带 `ADD_BG`
     底色、welcome 圆角框 8 行、非文件写入回退单行）。
   - 注意：这**测不出** InlineDisplay 的行尾裁剪 bug（见记忆 `inline-tui-no-bg-highlight-bar`），那类高亮/底色
     串行问题仍须 pty+pyte 实机验证——但本次是纯搬运、不改渲染字节，故 pty 只需一次「前后一致」核对。
3. **pty 实机核对**：改完 `package` 后跑一次探针，确认欢迎横幅/用户块/工具 diff 渲染与重构前逐帧一致。

## 非目标（本步不做）

- 不动输入/键路由/斜杠菜单/模型选择器/历史（那是 #5–#7，已约定之后再议）。
- 不动状态行 / 上下文统计（#3 StatusBar、#4 ContextUsage，后续步）。
- 不新建 `TuiText`/`TextLayout` 等工具类（已论证为过度拆分）。
- 不改任何渲染字节 / 配色 / 布局。

## 风险与回滚

- 风险低：机械搬运 + 一处 `md` 归属迁移 + 一个注入依赖。最大风险是遗漏某个共享辅助的引用点——已用 grep
  全量核对调用点（见分析）。
- 回滚：单 commit，`git revert` 即可；printer 是新增文件，删除 + 还原视图即恢复。

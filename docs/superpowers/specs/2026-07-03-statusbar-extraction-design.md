# StatusBar 抽取设计（CodeTuiView 重构 3/4）

**日期**：2026-07-03
**范围**：`springai-code-tui` · `com.example.springai.codetui.ui.CodeTuiView`
**前序**：重构 1/4 Theme（`27187bc`）、2/4 ScrollbackPrinter（`357d29c`）已完成。

---

## 目标（一句话）

把状态行里两段「动画型」内容渲染——**波光**（shimmer）与**压缩进度条**——抽成独立的纯函数类
`StatusBar`，让这段此前埋在 `InlineApp` 里、无法单测的**下标算术**变得可单测；视图仍保留状态行的**分发**。

## 为什么这么切（关键：向好的方向，而非为重构而重构）

状态行簇有两半，性质截然不同：

| 半 | 方法 | 性质 | 是否值得抽 |
|---|---|---|---|
| **分发器** | `statusLine()` | 按 `pickingModel`(#6)/`slashMenuActive()`(#5)/`state`/`currentModel`(#6)/`ctxSuffix()`(#4) 挑一条 | **否**——它天然是视图的编排点，读的全是别的关注点的态；抽出只会把耦合塞进一个宽参数表 |
| **动画渲染** | `shimmerStatus` / `shimmerSpans` / `compactingStatus` | 纯 `(文本, tick)` → `Text` 的下标数学（三角波、逐字高亮带） | **是**——零耦合、唯一有实质逻辑、当前测不了 |

因此本次**只抽动画那一半**。这样：

1. **可单测**（与 #2 同一理由）：波光带位置（`center=(tick/2)%period`、`|i-center|≤1` 高亮）、压缩条往返
   （三角波 `center = pos<width ? pos : period-pos`）是真算术，抽成纯函数后可喂 tick 断言。
2. **零耦合、零表示层改动**：`shimmer*`/`compacting*` 本来就返回 `richText(Text.from(Line.from(spans)))`；
   把 `Text.from(...)` 的构造搬进 `StatusBar`、视图改成 `richText(statusBar.xxx(...))`，**逐字节等价**。
   `idle`/`notice`/`picker`/`slash` 那几条 `text(s).style(st)` 简单支**原封不动留在视图**——不碰、无风险。
3. **契合正在成形的架构**：视图 = 分发/编排，抽出类 = 纯内容渲染。#4/#5/#6 之后视图的 `statusLine()`
   仍是把 model/slash/ctx/status 组装起来的那个点，恰当。

> 被否掉的更大方案「抽整条状态行（含分发器）」：会让 `StatusBar` 伸手进 #4/#5/#6 的关注点（需注入
> `ConversationState` + 传 `pickingModel`/`slashActive`/`currentModel`/`ctxSuffix`），接口更宽，且要把
> 所有简单支从 `text().style()` 改成 `Text`（一处表示层改动）。收益（视图多减 ~15 行）不抵这些成本。

## 设计

### 新类 `StatusBar`（package-private, final, 无状态纯函数）

```
无字段、无构造依赖（new StatusBar() 即可）。

Text shimmer(String label, String suffix, Style base, long animTick)
    —— 原 shimmerStatus：label 叠波光 + suffix 暗色静态；返回 Text（视图 richText 包裹）
Text compacting(long elapsedNanos, long animTick)
    —— 原 compactingStatus：计时 label + 往返进度条；返回 Text

私有：List<Span> shimmerSpans(String label, Style base, long animTick)  —— 原 shimmerSpans

依赖：import static Theme.{DIM, SHIMMER_HI, THINK}；dev.tamboui.text.{Line, Span, Text}；dev.tamboui.style.Style
```

不认识 tamboui 的 `Element`/`Toolkit`/`InlineApp`——只产 `dev.tamboui.text.Text`。这是它可离线单测的根据。

### `animTick` 归属：留在视图（不移入）

`animTick` 是 **drain 的帧时钟**：既驱动波光，又用于 `animTick % 30 == 0` 的 ctx 用量刷新节流（#4 territory）。
它是**两个关注点共用**的帧计数，属于 drain 循环。故留在视图，以参数传给 `shimmer`/`compacting`。`StatusBar`
因此保持无状态、纯函数（tick 入参）——比「让 StatusBar 拥有 tick 再 `tick()` 回传给节流」更简单、耦合更少。

### 调用点改动（视图侧）

```java
// 字段（就地 new，无构造依赖）
private final StatusBar statusBar = new StatusBar();

// statusLine()：仅 3 个「动画」返回支改为委托 statusBar，其余（picker/slash/idle/notice）原样不动
if (state.isCompacting()) return richText(statusBar.compacting(state.compactElapsedNanos(), animTick));
// ...
case THINKING -> richText(statusBar.shimmer("● 思考中…", qs + " · Esc 取消 · Ctrl+C 退出", THINK, animTick));
case RUNNING_TOOL -> {
    String s = state.activeToolSummary();
    yield richText(statusBar.shimmer("⏺ 运行 " + state.activeTool() + (s.isEmpty() ? "" : ": " + s) + "…",
            qs + " · Esc 取消", RUNNING, animTick));
}

// 删除 shimmerStatus / shimmerSpans / compactingStatus 三个方法
// 删除 import dev.tamboui.text.Line; 与 import dev.tamboui.text.Span;（移除后视图不再用；Text 仍用）
// drain() 不变：animTick++ 与 % 30 节流保持原样
```

## 行为契约（必须逐字保持，纯搬运零行为改动）

- **波光**：`period = n + 6`；`center = (animTick/2) % period`；`|i-center| ≤ 1` 的字符用 `SHIMMER_HI`，
  其余用 `base`；`suffix` 非空则追加一个 `DIM` span。空 label 返回空 span 列表。
- **压缩条**：计时 `sec≥60 → "Xm Ys"`，否则 `"Ns"`；label `"⟳ 正在压缩会话历史… (elapsed) · 不可中断  "`；
  `width=24`、`period=48`、`pos=(animTick/2)%period`、`center = pos<width ? pos : period-pos`；
  `|i-center| ≤ 1` 亮 `▰`(SHIMMER_HI) 否则 `▱`(THINK)；label span 为 `THINK`。
- **未触及的简单支**（picker/slash/idle/notice）保持 `text(s).style(st)` 逐字节不变。

## 测试策略

1. **回归**：现有离线套件（非 spike 基线 **87**，见 #2）继续全绿。
2. **新增 `StatusBarTest`**（无头纯函数单测，不经 InlineDisplay）：
   - 遍历 `text.lines().get(0).spans()` 读回 `Span.content()` / `Span.style()`（同 `MarkdownRendererTest` 的
     `fg()`/`effectiveModifiers()` 手法）断言：波光在 `tick=0` 高亮前导带、随 tick 移动、空 suffix 无尾 span；
     压缩条含 label/「不可中断」/`▰`/`▱`、计时跨分钟格式化。
   - 纯函数、确定性，无需驱动 `ConversationState`。
3. **pty 实机核对**：`package` 后跑一次探针，确认思考波光 / 压缩条 / 空闲行渲染与重构前逐帧一致
   （纯搬运、不改字节，故只需一次「前后一致」核对）。

## 非目标（本步不做）

- 不动分发器 `statusLine()` 的判定逻辑，不动 picker/slash/idle/notice 支。
- 不动 `animTick` 归属 / `drain()` / ctx 节流（`refreshCtxStats` 是 #4）。
- 不动输入/键路由/斜杠菜单/模型选择器/历史（#5–#7）。
- 不新建工具类，不改任何渲染字节 / 配色 / 布局。

## 风险与回滚

- 风险极低：只搬 3 个方法（其中 2 个本就返回 `richText(Text)`）、删 2 个 import、加 1 字段 + 改 3 个返回支。
  最大风险是遗漏某处仍引用 `Span`/`Line`——编译即暴露。
- 回滚：单 commit，`git revert`；`StatusBar` 是新增文件，删除 + 还原视图即恢复。

# springai-tamboui-demo

[TamboUI](https://tamboui.dev)（Java TUI 库，仿 ratatui）的入门示例——学习路线**第⑤步**，
覆盖第⑥步 code-tui 实际用到的 API 子集。全部基于 **TamboUI 0.4.0 上游原版**（版本随根 pom
`tamboui-bom` 统一管理），不需要任何 API key。

## 四个示例（菜单入口，递进式）

| # | 示例 | 你将学到 |
|---|------|---------|
| 1 | 富文本三粒度 + 宽度口径 | `Span/Line/Text/Style/Color` 与 `CharWidth`——终端对齐的唯一真相源是渲染宽度 |
| 2 | Buffer 立即模式与差分渲染 | `Buffer/Cell/Rect/Frame` + `Block` 边框——你描述整屏，库算最小差量 |
| 3 | 事件循环与按键绑定 | `InlineTuiRunner/InlineTuiConfig` + `KeyEvent/PasteEvent/ResizeEvent` + `KeyTrigger`；粘贴三段式 |
| 4 | Toolkit DSL 迷你聊天 | `InlineApp` + `Toolkit.text/richText/column/scope` + `textArea`——code-tui 视图的 ~150 行缩影 |

示例 1 不进 TUI 模式（stdout 直出）；示例 2、3、4 会接管终端，`q`/`Esc` 或 `Ctrl+C` 退出。

## 运行

必须在**真实终端**里运行（IDE 控制台/管道是 dumb 终端，示例 2–4 效果看不全）：

```bash
mvn -q -pl springai-tamboui-demo -am package -DskipTests
java -jar springai-tamboui-demo/target/springai-tamboui-demo-<version>.jar
```

示例 1 的宽度对照表在 IDE 控制台也能看逻辑，但「跳格」效果依赖终端字体渲染。

## 与 code-tui 的对应表

| 本模块 API | code-tui 里的使用处 |
|---|---|
| `Span/Line/Text/Style/Color` | 全部富文本渲染（最高频 import） |
| `Buffer/Cell/Rect/Frame/Backend` | 差分重绘地基（`Buffer` ⚠ 在 code-tui 的 classpath 上实际是 patch 版） |
| `InlineTuiRunner/InlineTuiConfig` | 行内事件循环（⚠ code-tui 实际用 patch 版，`requestUiUpdate` 等方法只存在于 patch） |
| `KeyEvent/KeyCode/PasteEvent/ResizeEvent` | 按键/粘贴/缩放事件处理 |
| `KeyTrigger/Actions/Bindings` | 键位绑定（Ctrl+C 退出、焦点导航解绑，见 `CodeTuiView.configure`） |
| `InlineApp/InlineToolkitRunner/Element/EventResult` | `CodeTuiView extends InlineApp`，四钩子：`height/render/configure/onStart` |
| `Toolkit.text/richText/column/textArea` + `InlineToolkit.scope` | 声明式 UI 树；`scope` 隐藏=收起面板 |
| `Block/Borders/BorderType` | 圆角边框（面板/输入框） |
| `TextAreaState` | 输入框编辑模型 |
| `CharWidth/TerminalWidth` | 宽度口径（⚠ 两者在 code-tui 的 classpath 上**都是 patch 版**；`TerminalWidth` 上游根本不存在） |

带 ⚠ 的四个符号：本模块测得的行为 ≠ code-tui 实际行为，差异正是 patch 存在的理由（见下节）。

## 为什么存在 springai-tamboui-inline-patch

code-tui 对 TamboUI 0.4.0 打了「同包同名类 shadow」补丁（靠 classpath 顺序优先加载，库本体零改动），本模块的教学刻意**不用** patch、直接展示上游行为——包括它的病：

- **宽度口径**：上游 `CharWidth` 把 VS16（❄️）、keycap（9️⃣）算 1 列（终端实际 2 列）、ZWJ 家族算 2 列
  （多数终端按成员分开渲染）、ZWNJ 算 0 列（部分终端实际 1 列）。示例 1 现场演示这些跳格；
  patch shadow `CharWidth` 并自建 `TerminalWidth`（口径=终端实际渲染宽度）。
- **大段粘贴掐断**：上游 `EventParser` 的括号粘贴 50ms 静默预算太紧，大段粘贴后半段被当逐键解析；
  另有 CR 泄漏（`\r\n` 的 CR 漏成独立按键）。示例 3 的三段式（正常 → 病 → 药方）现场演示；
  patch shadow `EventParser` 提高静默预算并修 CR 泄漏
  （见 `docs/superpowers/specs/2026-10-09-cr-paste-leak-and-vs16-width-design.md`）。
- **事件驱动重绘**：上游 `UiRunnable` 执行后不触发重绘（示例 4 因此保留默认 tick）；code-tui 关掉
  tick、靠 patch 增补的 `requestUiUpdate` 实现「空闲零周期任务、零 ANSI 输出」。

patch 模块本身（`AsyncPtyWriter` 防写阻塞、`InlineDisplay` 渲染记账等）见
[../springai-tamboui-inline-patch](../springai-tamboui-inline-patch)。

## 不教什么

TamboUI 的其余能力本模块不涉及：全屏 `TuiRunner`、chart/table/canvas 等组件、TCSS 样式、
SVG/HTML 导出——code-tui 没用到它们。完整能力见 [官方文档](https://tamboui.dev) 与
[GitHub 仓库](https://github.com/tamboui/tamboui)。

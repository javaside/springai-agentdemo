# springai-tamboui-demo

[TamboUI](https://tamboui.dev)（Java TUI 框架，仿 ratatui）的入门课程——学习路线**第⑤步**。
六课递进，跟着做完就能用这个框架写自己的终端应用；最后一课衔接到第⑥步 code-tui 的视图。
基于 **TamboUI 0.4.0**（版本随根 pom `tamboui-bom`），不需要任何 API key。

## 课程表

| 课 | 主题 | 你将学到 | 本课核心认知 |
|---|------|---------|-------------|
| 1 | Hello TUI——渲染模型 | `TuiRunner` 全屏应用、`Frame/renderWidget`、Paragraph；退出后打印那一帧的 Buffer 网格 | UI 是应用状态的纯函数：每帧全量重画，框架做 diff 只发变化的格子 |
| 2 | 文本与样式 | `Span/Line/Text` 三粒度、`Style` 不可变方法链、颜色三种来源（命名/256/RGB）、对齐、Paragraph 折行、宽度口径（ASCII 1 列 / 中文 2 列） | 终端富文本从「带样式的片段」开始；宽度是渲染口径 |
| 3 | 组件与布局 | `Block`（边框/标题）、`ListWidget`（第一个 StatefulWidget）、`Gauge`、`Layout.split` + `Constraint`（length/percentage/fill）、嵌套切分 | 先把屏幕切成区域，再往区域放组件；有状态组件的状态外部持有 |
| 4 | 事件与交互 | `KeyEvent`（j/k 与方向键）、状态机驱动 UI、`TickEvent` 动画、按需重绘 | 事件只改状态，「请求重绘」是状态通往屏幕的唯一桥梁 |
| 5 | Toolkit DSL | `ToolkitApp`、`panel/column/row/text/richText/scope`、原生 `textArea`、键位定制 | 声明式：render() 只声明 UI 树，布局/焦点/输入编辑全交给框架（官方推荐主路径） |
| 6 | 行内模式——衔接 code-tui | `InlineTuiRunner`、live 区 + `println` 下沉 scrollback 双通道、迷你聊天 | 聊天式 CLI 为何用行内；code-tui 的 `CodeTuiView` 是本课的放大版 |

**建议学法**：按序做，每课跑一遍、读一遍源码（每个示例类都带逐行注释）；
第 4 课的状态机、第 6 课的双通道是读 code-tui 源码前最值得吃透的两个模式。

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

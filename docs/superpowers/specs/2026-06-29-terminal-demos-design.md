# Terminal 交互场景演示 — 设计文档

- 日期：2026-06-29
- 模块：`springai-jline-demo`
- JLine 版本：3.30.13
- 目标：补全 JLine `Terminal` 组件的完整能力演示，以「可上手交互的场景」组织，替换并重构现有 `TerminalDemo.java`。

## 背景与动机

现有 `TerminalDemo.java` 以「逐个方法打印」的方式演示了 Terminal 的部分基础能力（终端信息、彩色输出、光标保存/恢复、原始输入），但：

1. 覆盖不全：缺少信号处理（Ctrl+C / 窗口 resize）、非阻塞读取、Attributes 控制、备用屏、状态栏、鼠标、绝对光标定位等关键能力。
2. 偏抽象：孤立演示单个 API，不直观，尤其与后续目标「构建 AI 智能体交互界面」脱节。

改造方向：**按人机交互场景组织**，每个场景是一个能跑、能玩的小交互，把多个相关 API 揉进真实场景，更直观、更贴近 Agent UI 的实际用法。

## 范围

- 重构（删除）现有 `TerminalDemo.java`，其基础能力并入新的「基础能力」交互场景。
- 新增 1 个启动器 + 5 个交互场景，合起来覆盖 Terminal 的全部已知关键能力。
- 按 JLine 组件分包，为后续组件（LineReader / Completer 等）演示预留对称结构。

不在本期范围（YAGNI）：
- 根包总入口 `JLineDemoLauncher`（聚合多组件）——等组件多了再做。
- 其它 JLine 组件（LineReader、Completer、Highlighter、History、Console&Builtins）的演示。

## 包结构

```
com.example.springai.jline/
├── Demo.java                        ← 共享接口（所有组件场景复用，置于根包）
└── terminal/                        ← 本期：Terminal 组件
    ├── TerminalDemoLauncher.java    ← main 入口（全屏方向键菜单）
    ├── TerminalPlaygroundDemo.java  ← 场景 0：基础能力（交互式）
    ├── InterruptibleStreamDemo.java ← 场景 1：可中断流式输出
    ├── AdaptiveDashboardDemo.java   ← 场景 2：自适应窗口仪表盘
    ├── FullScreenMenuDemo.java      ← 场景 3：全屏菜单选择器
    └── MouseInteractionDemo.java    ← 场景 4：鼠标点选
```

设计要点：
- `Demo` 接口放根包 `jline`，不放 `terminal`，避免将来 `linereader` 包反向依赖 `terminal`。
- 不再嵌套 `scenarios/` 子包；`terminal` 包本身即命名空间，场景类平铺其下。
- `pom.xml` 的 `mainClass` 改为 `com.example.springai.jline.terminal.TerminalDemoLauncher`。

## 公共契约：`Demo` 接口

```java
public interface Demo {
    String name();                 // 菜单显示名
    String description();          // 一行说明
    void run(Terminal terminal) throws IOException;  // 复用 launcher 传入的唯一 Terminal
}
```

## 生命周期与状态约定（关键）

- launcher 创建**唯一的** `Terminal`（`TerminalBuilder.builder().system(true).build()`），传给每个场景；场景**不自行 new Terminal**。
- 场景进入前/退出后，launcher 负责把终端恢复干净：退原始模式、退备用屏、显示光标、清屏。
- 场景内部若进入备用屏 / 原始模式 / 安装信号 handler，**必须在 `finally` 中复原**（恢复 Attributes、退备用屏、显示光标、卸载或恢复信号 handler），保证异常时不弄乱用户终端。
- 信号 handler 在场景结束时恢复为原 handler（`terminal.handle(...)` 返回旧 handler，保存后在 finally 还原）。

## 降级策略（全场景统一）

- `dumb` 终端（IDE 控制台 / 管道 / CI）：菜单退化为「输入数字选择」；交互场景打印「请在真实终端运行」并立即返回。
- 某能力的 capability 不可用（如 `save_cursor`、鼠标、备用屏为 null）：打印一句友好提示并返回菜单，绝不卡死。

## 启动器：`TerminalDemoLauncher`

- 本身即「全屏菜单选择器」场景的实战应用（复用 `FullScreenMenuDemo` 的菜单渲染/导航逻辑）。
- 交互：备用屏 + 隐藏光标 + `↑/↓` 移动高亮 + `Enter` 运行所选场景 + `q` 退出。
- 运行完一个场景后回到菜单；退出时复原终端。
- `dumb` 终端：打印编号列表，读取数字行选择。

## 场景设计

### 场景 0 — `TerminalPlaygroundDemo`（基础能力，交互式）
样式调色台：屏上一段示例文字，按键实时改样式。
- `f` 切前景色、`b` 切背景色（循环 8 色）、`o` 粗体、`u` 下划线、`i` 斜体 → 即时预览。
- 顶部固定面板显示终端信息：类型 / 名称 / 宽×高 / 支持颜色数 / `encoding()`。
- `q` 返回菜单。
- 覆盖：`getType/getName/getWidth/getHeight`、`getNumericCapability(max_colors)`、`encoding()`、`AttributedString` 全样式、非阻塞按键读取。

### 场景 1 — `InterruptibleStreamDemo`（可中断流式输出）
模拟 AI 逐字「流式吐字」，底部提示「Ctrl+C 停止 / 其他键继续」。
- `Signal.INT`（Ctrl+C）→ 不退出进程，优雅停止当前输出并提示。
- `reader().read(100ms)` 非阻塞：一边吐字一边监听按键。
- `getAttributes/setAttributes` 关回显，演示原始 vs 规范模式区别。
- 打断时 `bell` 响一声。
- 覆盖：🔴 `handle(Signal.INT)`、非阻塞 `read(timeout)`、`getAttributes/setAttributes`、`bell`。

### 场景 2 — `AdaptiveDashboardDemo`（自适应窗口仪表盘）
会自己排版的仪表盘（假指标：模型名 / 已用 token / 进度条），拖动改变窗口大小时自动重绘居中。
- `Signal.WINCH` 监听 resize + `getSize()` 重算布局。
- `clear_screen` 清屏 + `cursor_address(row,col)` 绝对定位居中绘制。
- 底部 `Status` 状态栏常驻显示窗口尺寸。
- `q` 退出。
- 覆盖：🔴 `handle(Signal.WINCH)`、`getSize()`、🟡 `clear_screen`、`cursor_address`、`Status` 状态栏。

### 场景 3 — `FullScreenMenuDemo`（全屏菜单选择器）
全屏列表，`↑/↓` 移动高亮、`Enter` 确认、`Esc`/`q` 退出。与 launcher 同源（launcher 复用其逻辑）。
- 备用屏 `enter_ca_mode`/`exit_ca_mode`（退出恢复原终端内容，类 vim）。
- 光标显隐 `cursor_invisible`/`cursor_visible`。
- 方向键转义序列解析（`↑/↓` = `ESC [ A/B`）。
- 覆盖：🔴 备用屏缓冲、原始模式方向键、光标显隐、`getCursorPosition()`。

### 场景 4 — `MouseInteractionDemo`（鼠标点选）
屏上画几个「按钮」，鼠标点击高亮被点中按钮并显示坐标；滚轮上下滚动改变一个数值。
- `trackMouse(MouseTracking.Normal)` + `readMouseEvent()`。
- `output()` 拿底层原始字节流直接写 ANSI（对比 `writer()`）。
- `q` 退出；不支持鼠标的终端降级提示。
- 覆盖：🟡 `trackMouse/readMouseEvent`、🟢 `output()`、鼠标按键/滚轮事件。

## 能力覆盖对照（验收用）

| 能力 | 场景 |
|------|------|
| 终端信息 type/name/width/height/colors、encoding | 0 |
| AttributedString 样式（前景/背景/粗体/斜体/下划线） | 0 |
| 非阻塞读取 read(timeout) | 0, 1 |
| Signal.INT（Ctrl+C 优雅停止） | 1 |
| getAttributes/setAttributes（回显/模式） | 1 |
| bell 响铃 | 1 |
| Signal.WINCH（resize） + getSize() | 2 |
| clear_screen 清屏 | 2 |
| cursor_address 绝对定位 | 2 |
| Status 状态栏 | 2 |
| 备用屏 enter/exit_ca_mode | 3, launcher |
| 原始模式 + 方向键解析 | 3, launcher |
| 光标显隐 cursor_invisible/visible | 3, launcher |
| getCursorPosition() | 3 |
| trackMouse/readMouseEvent | 4 |
| output() 原始字节流 | 4 |
| save/restore_cursor、clr_eol（原 demo 已有，融入相关场景） | 2/3 |

## 测试与验证

- 交互场景以**手动验证**为主（依赖真实 TTY）：在真实终端运行 launcher，逐场景验证玩法与降级。
- 自动化层面：保证 `mvn clean package` 编译通过、`dumb` 终端下运行不抛异常、能进菜单并安全退出。
- 验收标准：能力覆盖对照表逐项可见；任意场景异常或退出后终端状态干净（光标可见、非原始模式、非备用屏）。

## 运行方式

```bash
mvn clean package
cd springai-jline-demo/target
java -jar springai-jline-demo-1.0.0.jar   # 启动 TerminalDemoLauncher
```

## 文档维护

- 更新 `springai-jline-demo/README.md`：替换「当前示例 / TerminalDemo.java」一节为新的 5 场景说明与运行方式；勾选/调整「后续计划」。

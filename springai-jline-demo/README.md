# springai-jline-demo

JLine 3 核心组件使用示例，用于后续构建 AI 智能体的交互界面。

## JLine 核心组件概览

### 1. **Terminal** （终端抽象层）
- 跨平台终端抽象，屏蔽不同操作系统差异
- 提供输入输出、光标控制、屏幕清除等基础能力
- 支持原始模式（raw mode）和规范模式（canonical mode）
- **用途**：所有 JLine 功能的基础，负责与物理终端交互

### 2. **LineReader** （高级输入处理）
- 提供命令行编辑功能（剪切、粘贴、移动光标等）
- 支持多行输入编辑
- 整合 Completer、Highlighter、History 等功能
- **用途**：构建交互式命令行界面的核心组件

### 3. **Completer** （自动补全）
- 提供 Tab 键自动补全功能
- 支持自定义补全逻辑
- 可补全命令、参数、文件路径等
- **用途**：提升用户输入效率和体验

### 4. **Highlighter** （语法高亮）
- 实时对输入内容进行语法着色
- 可自定义高亮规则
- 提升命令可读性
- **用途**：增强视觉反馈，减少输入错误

### 5. **History** （历史记录）
- 记录和管理用户输入历史
- 支持上下键浏览历史命令
- 可持久化到文件
- 支持历史搜索（Ctrl+R）
- **用途**：方便用户重复执行命令

### 6. **AttributedString** （样式文本）
- 支持带颜色、样式的文本输出
- 前景色、背景色、粗体、斜体等
- **用途**：美化终端输出，突出重要信息

### 7. **Console & Builtins** （控制台与内置命令）
- 提供 REPL 风格的交互界面
- 内置常用命令实现（help、history、clear 等）
- **用途**：快速构建功能完整的命令行工具

## 当前示例：Terminal 交互场景

入口：`com.example.springai.jline.terminal.TerminalDemoLauncher`
真实终端中启动后用 ↑/↓ 选择场景、Enter 运行、q 退出；dumb 终端（IDE/管道）退化为编号选择。

| 场景 | 类 | 演示的 Terminal 能力 |
|------|----|----------------------|
| 基础能力调色台 | `TerminalPlaygroundDemo` | 终端信息、encoding、AttributedString 全样式、非阻塞按键 |
| 可中断流式输出 | `InterruptibleStreamDemo` | Signal.INT（Ctrl+C）、非阻塞 read(timeout)、Attributes 回显控制、bell |
| 自适应窗口仪表盘 | `AdaptiveDashboardDemo` | Signal.WINCH、getSize()、clear_screen、cursor_address 绝对定位、Status 状态栏 |
| 全屏菜单选择器 | `FullScreenMenuDemo` | 备用屏、方向键解析、光标显隐、getCursorPosition() |
| 鼠标点选 | `MouseInteractionDemo` | trackMouse/readMouseEvent、output() 原始流、鼠标按键/滚轮 |

纯逻辑（菜单绕回、方向键解析、居中算法）有 JUnit5 单测；交互效果需在真实终端手动体验。

## 运行示例

```bash
# 编译
mvn clean package

# 运行
cd springai-jline-demo/target
java -jar springai-jline-demo-1.0.0.jar
```

## 后续计划

- [x] Terminal 示例：交互场景（基础/流式/仪表盘/菜单/鼠标）
- [ ] LineReader 示例：命令行编辑和多行输入
- [ ] Completer 示例：自动补全实现
- [ ] Highlighter 示例：语法高亮
- [ ] History 示例：历史记录管理
- [ ] 综合示例：构建 AI 智能体交互界面

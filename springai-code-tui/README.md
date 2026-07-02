# springai-code-tui

基于 Spring AI 2.0 的编码智能体 + [TamboUI](https://github.com/quanticc/tambo-ui)（`0.4.0`，纯 Java 原始 API）单栏终端界面的命令行编码助手。模型使用 DeepSeek（`deepseek-v4-flash`）。

> 说明：DeepSeek 旧模型名 `deepseek-chat` / `deepseek-reasoner` 将于 2026-07-24 15:59 UTC 停用（期间被透明路由到 V4-Flash），现役模型为 `deepseek-v4-flash`（非思考）与 `deepseek-v4-pro`（强推理）。

## 模块用途

- 单栏对话式 TUI：对话滚动区（流式 token 内联渲染 + 工具调用活动 + Todo 状态）、输入框、底部状态栏。
- 智能体工具：`FileSystemTools`（read/write/edit）、`ShellTools`（执行 shell 命令）、`GrepTool`、`GlobTool`、`TodoWriteTool`。
- 多轮会话记忆（窗口记忆），把 cwd / git 状态 / 模型名注入系统提示做 grounding。
- Esc 取消当前回合、Ctrl+C 退出。

## ⚠️ 安全声明（请务必阅读）

**本工具不是安全沙箱。** 它给智能体开放了对本机文件系统和 shell 的实质性访问能力，具体边界如下：

- **只有 `FileSystemTools` 受工作区 root 目录沙箱限制**——读写越界路径（绝对路径逃出 root，或 `../` 穿越）会被拒绝。这是底层依赖 `spring-ai-agent-utils` 实测确认的真实、强制的边界。
- **`ShellTools` / `GrepTool` / `GlobTool` 不受任何 root 限制。** 它们原生就没有目录沙箱：
  - `ShellTools.bash(...)` 直接 `new ProcessBuilder(...).start()`，没有设置工作目录约束，模型可以执行任意 shell 命令（包括 `cd /`、绝对路径操作、`rm -rf`、`curl | bash` 等）。
  - `GrepTool` / `GlobTool` 的 `workingDirectory` 只是**默认基准目录**，不是强制边界——只要参数传绝对路径或 `../`，照样能读到/列出 root 之外的任意文件。
- 也就是说：**智能体（在被越权提示注入或自身犯错的情况下）可以读写磁盘上任意它有权限触及的位置、执行任意命令**，不局限于当前工作目录。

这是 v1 已知且被接受的残余风险（设计方案 B：诚实披露 + 启动确认门，而不是技术强制沙箱）。**请不要将本工具的这一版本理解或宣传为"安全隔离"。**

### 使用建议

- **只在可以随意丢弃、且已被版本控制干净纳管的目录中运行本工具**，方便万一出问题时用 `git checkout`/`git clean` 恢复或直接丢弃整个目录。
- **不要在 `$HOME`、系统关键目录，或任何重要仓库的根目录下直接运行。**
- 每次启动都会打印红色警示横幅并要求确认后才会继续（见下方「运行」一节）。

真正的目录级强制沙箱（自写 `SandboxedShellTool` 校验所有工具的路径参数）留作 v1 之后的安全增强，本版本不包含。

## 构建

```bash
mvn -pl springai-code-tui -am package
```

## 运行

```bash
export DEEPSEEK_API_KEY=你的key

# 切到一个可以随意丢弃、且被版本控制干净纳管的目录再运行
cd /path/to/some/disposable/project

java -jar /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui/target/springai-code-tui.jar
```

启动时会先打印红色安全警示横幅（含当前工作区 root 的绝对路径），并要求交互确认「已理解上述风险并确认继续？[y/N]」——输入 `y`/`Y` 才会继续；其他输入（包括直接回车、`n`）都会取消启动。

如果已经了解风险、想跳过每次的交互确认（例如脚本化运行、重复调试），可设置：

```bash
export CODE_TUI_I_UNDERSTAND=1
```

设置后横幅仍会打印，但不再等待交互输入，直接放行。

## 操作键

| 按键 | 行为 |
| --- | --- |
| Enter | 发送输入框中的消息 |
| Esc | 取消当前正在进行的回合（工具调用/模型生成） |
| Ctrl+C | 退出程序 |

## 已知限制

- **无历史回滚**：对话滚动区只保留并显示末尾若干行，不支持向上翻页查看更早的完整历史。
- **宽字符光标对齐**：输入框光标位置按显示宽度（东亚宽字符计 2 列）对齐，但极端的 grapheme 组合（如某些 emoji ZWJ 序列、组合字符）可能出现轻微偏移。
- **工具沙箱不完整**：见上方安全声明——只有文件系统工具受 root 约束，Shell/Grep/Glob 不受限。自写的真沙箱（`SandboxedShellTool` 等，方案 A）列为 v1 之后的增强项，本版本未实现。
- 单会话固定 id，不支持多会话/会话持久化。
- 不含联网检索、子智能体、长期记忆、技能调用、向用户反问等能力（v1 明确排除）。

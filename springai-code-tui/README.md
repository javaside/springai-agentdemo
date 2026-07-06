# springai-code-tui

基于 Spring AI 2.0 的编码智能体 + [TamboUI](https://github.com/quanticc/tambo-ui)（`0.4.0`，纯 Java 原始 API）单栏终端界面的命令行编码助手。**多 provider**：按环境变量激活 DeepSeek / Anthropic / OpenAI，`/model` 可运行时切换。

> 说明：DeepSeek 旧模型名 `deepseek-chat` / `deepseek-reasoner` 将于 2026-07-24 15:59 UTC 停用（期间被透明路由到 V4-Flash），现役模型为 `deepseek-v4-flash`（非思考）与 `deepseek-v4-pro`（强推理）。

## 模块用途

- 单栏对话式 TUI：对话滚动区（流式 token 内联渲染 + 工具调用活动 + 子 agent 嵌套行）、**📋 计划面板**（主 agent 的 todo）、**⟐ 任务面板**（本回合派出的子 agent 状态 ▶/✓/✗ + 当前工具）、输入框、底部状态栏。
- **多 provider**：`CodeTuiApplication` 按环境变量装配 `DeepSeekProvider` / `AnthropicProvider` / `OpenAiProvider`（key 缺失即 unavailable），首个可用者激活；`/model` 在当前 provider 的模型间切换（子 agent 也可用 `provider:model` 跨 provider 路由）。
- 智能体工具：`FileSystemTools`（read/write/edit）、`ShellTools`（执行 shell 命令）、`GrepTool`、`GlobTool`、`TodoWriteTool`、`SmartWebFetchTool`（联网抓取）、`AskUserQuestionTool`（向用户反问、多选拍板）、`SubagentTool`（`Task`，把子任务委派给专门子 agent）、`AutoMemoryTools`（`Memory*` 六件套：跨会话长期记忆的读写/增删/改名，仅主 agent）。
- **子 agent（Task）**：内置 `explore` / `plan` / `bash` / `general-purpose` 四类（`src/main/resources/agents/*.md`），串行前台阻塞执行，内部工具活动带 taskId 内联嵌套显示。
- **技能（Skills）**：`/skills` 查看可用技能清单（模型按需自动调用），`/skill` 为本条消息手动指定技能，`/reload` 重新扫描技能目录——运行中新增/删除 `SKILL.md` 无需重启即对模型与 `/skills` 生效（即便启动时零技能，也能 `/reload` 出第一个新增技能）。
- **上下文管理**：窗口记忆多轮会话，token 用量估算（`/context` 查看），超阈值自动压缩 + `/compact` 手动压缩。把 cwd / git 状态 / 模型名注入系统提示做 grounding。
- **长期记忆（跨会话）**：基于 `spring-ai-agent-utils` 的 `AutoMemoryTools`（Anthropic Claude Code 那套：`MEMORY.md` 索引 + 分型 Markdown 文件 + 两步保存）。记忆落盘 `<项目根>/.codetui/memory/`（**按项目隔离**，已被 `.gitignore`）；agent 会主动记住用户偏好、项目上下文与反馈，并在后续会话（含 `/clear` 开新会话后）读 `MEMORY.md` 召回。仅主 agent 具备，子 agent 不写长期记忆。与会话记忆互补：会话记忆是当前对话的内存态窗口，长期记忆是跨会话的磁盘态精选事实。
- **项目指令（AGENTS.md）**：启动时读取用户级 `~/.codetui/AGENTS.md` + 项目级 `<项目根>/AGENTS.md`（跨工具生态标准，Codex/Cursor/Aider 等通用；项目里已有的 `AGENTS.md` 直接被读到），把团队约定（构建/测试命令、代码风格、架构约定）注入**主 agent 与子 agent** 的系统提示（顺序 user→project，项目级优先级更高）。人手写、提交入库、启动全量注入、**只读**（编辑文件即改约定，改动需重启生效）。这是与 agent 自写的长期记忆正交的一套「instructions」：前者人写团队约定，后者 agent 自记学到的东西。
- Esc 取消当前回合、Ctrl+C 退出。

## ⚠️ 安全声明（请务必阅读）

**本工具不是安全沙箱。** 它给智能体开放了对本机文件系统和 shell 的实质性访问能力，具体边界如下：

- **只有 `FileSystemTools` 受工作区 root 目录沙箱限制**——读写越界路径（绝对路径逃出 root，或 `../` 穿越）会被拒绝。这是底层依赖 `spring-ai-agent-utils` 实测确认的真实、强制的边界。
- **`ShellTools` / `GrepTool` / `GlobTool` 不受任何 root 限制。** 它们原生就没有目录沙箱：
  - `ShellTools.bash(...)` 直接 `new ProcessBuilder(...).start()`，没有设置工作目录约束，模型可以执行任意 shell 命令（包括 `cd /`、绝对路径操作、`rm -rf`、`curl | bash` 等）。
  - `GrepTool` / `GlobTool` 的 `workingDirectory` 只是**默认基准目录**，不是强制边界——只要参数传绝对路径或 `../`，照样能读到/列出 root 之外的任意文件。
- 也就是说：**智能体（在被越权提示注入或自身犯错的情况下）可以读写磁盘上任意它有权限触及的位置、执行任意命令**，不局限于当前工作目录。
- **联网出口无过滤**：`SmartWebFetchTool` 可发起对外 HTTP 请求，无域名白名单/出网限制——被提示注入时可能外泄本地读到的内容。
- **子 agent 同权**：`SubagentTool`（`Task`）派出的子 agent 复用同一套未沙箱工具，其执行同样不受目录约束。

这是 v1 已知且被接受的残余风险（诚实披露，而不是技术强制沙箱）。**请不要将本工具的这一版本理解或宣传为"安全隔离"。**

### 使用建议

- **只在可以随意丢弃、且已被版本控制干净纳管的目录中运行本工具**，方便万一出问题时用 `git checkout`/`git clean` 恢复或直接丢弃整个目录。
- **不要在 `$HOME`、系统关键目录，或任何重要仓库的根目录下直接运行。**

真正的目录级强制沙箱（自写 `SandboxedShellTool` 校验所有工具的路径参数）留作 v1 之后的安全增强，本版本不包含。

## 构建

```bash
mvn -pl springai-code-tui -am package
```

## 运行

```bash
# 至少配置一个 provider 的 key（首个可用者激活；可同时配多个，用 /model 切换）
export DEEPSEEK_API_KEY=你的key          # DeepSeek（默认现役，默认 deepseek-v4-pro，另有 v4-flash 非思考款）
# export ANTHROPIC_API_KEY=你的key       # Anthropic（默认 claude-opus-4-8，另有 fable-5/sonnet-5/haiku-4-5）
# export OPENAI_API_KEY=你的key          # OpenAI（gpt-5.5 等）
# 各 provider 可选自定义 base url：DEEPSEEK_BASE_URL / ANTHROPIC_BASE_URL / OPENAI_BASE_URL

# 切到一个可以随意丢弃、且被版本控制干净纳管的目录再运行
cd /path/to/some/disposable/project

java -jar /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui/target/springai-code-tui.jar

# 恢复上次会话（仿 Claude Code 的 -c）：接着上次的对话/计划继续
java -jar .../springai-code-tui.jar -c            # 或 --continue
```

启动后直接进入 TUI（当前工作区即上面 `cd` 进入的目录）。请自行遵循上方「安全声明」的使用建议。

### 会话持久化与恢复

会话事件持久化在 `<项目根>/.codetui/sessions/<sessionId>.json`（**按项目隔离**，已被 `.gitignore`）。

- **默认启动**：开一个**全新会话**（干净上下文，不读旧历史），生成一个新 session 文件；旧会话文件原封不动。
- **`-c` / `--continue` 启动**：恢复**最近一次**会话（按文件最后修改时间选），并把上次对话**直观回放进界面**（仿 Claude Code `--continue`：重现用户消息、助手正文与工具调用/结果标记，而非只提示「已恢复 N 条」），可接着聊，或用 `/continue` 续跑上次未完成的计划。
- 仓库惰性加载：默认启动不读盘；只有 `-c` 选中的那个会话才被载入。加载时会裁掉上次硬中断残留的悬空工具调用，避免续跑首个请求报错。

### 日志位置

日志**不写进当前工作目录**（免得每个被操作的项目里都冒出一个 `logs/`）：

- **用 `bin/code-tui` 启动**（发布包）：写到**安装目录**下的 `logs/`（与程序同处）；若安装在只读位置，回退到 `~/.codetui/logs/`。
- **直接 `java -jar` 或 `mvn` 运行**：写到 `~/.codetui/logs/`。
- 机制：启动脚本经 `-Dcodetui.log.dir=<dir>` 把目录交给 logback（见 `logback.xml`）；未设置时默认 `~/.codetui/logs`。滚动策略：单文件 10MB、保留 7 天、总量上限 100MB。

## 发布打包（可分发、解压即运行）

打一个自包含发布包（含启动脚本 + 主 jar + 全部运行期依赖），需 JDK 17+：

```bash
mvn -pl springai-code-tui clean package -Pdist
```

产出（`-Pdist` profile 触发，默认 `package` 不打，保持日常构建轻量）：

- `target/springai-code-tui-<version>-dist.tar.gz`
- `target/springai-code-tui-<version>-dist.zip`

解压后目录结构：

```
springai-code-tui-<version>/
├── bin/code-tui         # 启动脚本（sh；自动定位安装目录与 java）
├── bin/code-tui.cmd     # 启动脚本（Windows）
├── springai-code-tui.jar
├── lib/*.jar            # 全部运行期依赖
└── README.md
```

在目标机器上运行（先配好 API key，切到可随意丢弃的项目目录）：

```bash
tar xzf springai-code-tui-<version>-dist.tar.gz
export DEEPSEEK_API_KEY=你的key
cd /path/to/some/disposable/project
/path/to/springai-code-tui-<version>/bin/code-tui
```

> 也可直接 `java -jar /path/to/springai-code-tui-<version>/springai-code-tui.jar`——jar 的
> manifest 里 `Class-Path` 相对自身定位同级 `lib/`，与 cwd 无关。

## 操作键

| 按键 | 行为 |
| --- | --- |
| Enter | 发送输入框中的消息 |
| `\` + Enter | 在输入框内换行（终端无关） |
| ↑ / ↓ | 回溯 / 前进已提交的历史消息（多行时先在行内移动光标） |
| Esc | 取消当前正在进行的回合（工具调用/模型生成） |
| Ctrl+C | 退出程序 |

## 斜杠命令

输入 `/` 会弹出命令补全菜单（↑↓ 选择、Tab 补全、Enter 运行、Esc 关闭）：

| 命令 | 行为 |
| --- | --- |
| `/model` | 打开模型选择器，在当前 provider 的模型间切换 |
| `/compact` | 手动压缩会话历史 |
| `/context` | 查看上下文用量（事件数 / token） |
| `/skill` | 为本条消息指定技能 |
| `/skills` | 查看可用技能清单（模型按需自动调用） |
| `/reload` | 重新扫描技能目录（运行中新增/删除的 `SKILL.md` 生效，无需重启） |
| `/help` | 显示可用命令与快捷键 |
| `/exit` | 退出程序 |

## 已知限制

- **滚动区不可翻页**：对话滚动区依赖终端自身的 scrollback，程序内不支持翻页控件（输入框 ↑↓ 回溯的是「已提交的历史消息」，与滚动区翻页无关；历史仅内存态，退出不保留）。
- **宽字符光标对齐**：输入框光标位置按显示宽度（东亚宽字符计 2 列）对齐，但极端的 grapheme 组合（如某些 emoji ZWJ 序列、组合字符）可能出现轻微偏移。
- **工具沙箱不完整**：见上方安全声明——只有文件系统工具受 root 约束，Shell/Grep/Glob 不受限。自写的真沙箱（`SandboxedShellTool` 等，方案 A）列为 v1 之后的增强项，本版本未实现。
- 单会话固定 id，不支持多会话/会话持久化。
- **子 agent 串行执行**：一次最多 1 个子任务前台阻塞运行，暂不支持并行/后台（`run_in_background`）与 `/tasks` 详情面板（列为后续增强）。
- **长期记忆无「自动整理」**：跨会话长期记忆已具备（见上「长期记忆」），但暂未接入定期 consolidation（自动汇总/去重冗余记忆）触发器；记忆的增删改全由模型按需驱动。

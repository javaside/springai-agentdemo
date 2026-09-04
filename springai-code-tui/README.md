# springai-code-tui

基于 Spring AI 2.0 和 [TamboUI](https://github.com/tamboui/tamboui) 的命令行编码智能体。它可以在终端中读写代码、运行命令、调用子 agent，并支持 DeepSeek、智谱 GLM、通义千问、Anthropic、OpenAI 和 OpenCode Go。

## 快速开始（下载发布包）

**前置条件：** JDK 17+，以及至少一家模型服务的 API Key。

从 [Releases](https://github.com/javaside/springai-agentdemo/releases/latest) 下载发布包：

- macOS / Linux：`springai-code-tui-<version>-dist.tar.gz`
- Windows：`springai-code-tui-<version>-dist.zip`

> **重要：本工具不是安全沙箱。** 请只在可以随意丢弃、且已由 Git 干净纳管的项目中运行。不要在 `$HOME`、系统目录或重要仓库中直接使用。完整说明见[安全声明](#安全声明)。

### macOS / Linux

```bash
# 1. 解压程序（下面这个目录是“安装目录”）
tar xzf springai-code-tui-<version>-dist.tar.gz
cd springai-code-tui-<version>

# 2. 创建配置文件
cp bin/config.env.example bin/config.env
```

编辑 `bin/config.env`，取消任意一家 `*_API_KEY` 的注释并填写真实值。例如：

```dotenv
DEEPSEEK_API_KEY=你的key
```

然后进入 code-tui 将要读写的项目，再通过**安装目录中的绝对路径**启动：

```bash
# 3. 这个目录才是“工作目录”
cd /path/to/disposable-git-project
/path/to/springai-code-tui-<version>/bin/code-tui
```

### Windows

解压 `.zip`，在解压后的安装目录打开 CMD 或 PowerShell：

```bat
rem 1. 创建配置文件
copy bin\config.env.example bin\config.env
```

编辑 `bin\config.env`，取消任意一家 `*_API_KEY` 的注释并填写真实值，然后从待处理的项目目录启动：

```bat
rem 2. 进入“工作目录”，再调用“安装目录”里的启动脚本
cd C:\path\to\disposable-git-project
C:\path\to\springai-code-tui-<version>\bin\code-tui.cmd
```

安装目录只用于存放 code-tui 程序；**启动时所在的工作目录才是智能体要操作的项目**。

### 恢复最近一次会话

默认启动会创建新会话。需要恢复当前项目最近一次会话时，加 `-c` 或 `--continue`：

```bash
/path/to/springai-code-tui-<version>/bin/code-tui -c
```

### 临时使用环境变量

如果只是临时试用，也可以不创建 `bin/config.env`，直接在启动前设置环境变量：

```bash
export DEEPSEEK_API_KEY=你的key
```

启动脚本的配置查找顺序是：`CODETUI_CONFIG` 指定的文件、安装目录下的 `bin/config.env`、`~/.codetui/config.env`。完整配置项及示例均在 `bin/config.env.example` 中。

## 安全声明

**本工具不是安全沙箱。** 它给智能体开放了对本机文件系统和 shell 的实质性访问能力。有副作用的工具调用会先经过权限管理，但请分清它是什么、不是什么：

> **权限层管的是“要不要做这一步”，不是“能做到多远”。**
> 一旦允许，那次调用就会以你的用户权限执行，不受目录边界约束。

使用建议：

- 只在可以随意丢弃、且已由 Git 干净纳管的目录中运行。
- 不要在 `$HOME`、系统关键目录或重要仓库的根目录运行。
- 审批面板会展示调用目标和理由，不要未经检查就允许。
- “跳过权限检查”模式会跳过全部权限检查，只应在完全清楚后果时使用。

完整安全边界见 [docs/guide/security.md](docs/guide/security.md)，权限规则与模式见 [docs/guide/permissions.md](docs/guide/permissions.md)。

## 核心能力

| 类别 | 能力 |
|------|------|
| 模型与 provider | DeepSeek、智谱 GLM、通义千问、Anthropic、OpenAI、OpenCode Go；`/model` 运行时切换，选择按项目记忆在 `.codetui/model.json` |
| 编码与联网工具 | 文件读写、Shell、Grep/Glob、任务计划、网页抓取、博查中文搜索、Brave 英文搜索、向用户提问 |
| 权限与安全 | 有副作用的调用执行前审批；`Shift+Tab` 切换默认、自动接受编辑、计划、跳过权限检查四种模式 |
| 子 agent 与计划 | `Task`、`ParallelTasks`、后台任务、计划面板和任务面板 |
| 会话与记忆 | `-c` 恢复会话、自动/手动上下文压缩、项目级长期记忆、`AGENTS.md` 项目指令 |
| 扩展与多模态 | MCP、Skills、视觉输入、回合中插话 |
| 终端注意提示 | 任务完成或需要你回答/确认时，改写 tab 标题（`⏳ 等待你的输入` / `✓ 已完成`）并响一声铃——切去别的窗口也看得出它在等你（见 [docs/guide/reference.md](docs/guide/reference.md#终端注意提示tab-标题--响铃)） |

DeepSeek 现役内置模型为 `deepseek-v4-flash`（非思考）、`deepseek-v4-pro`（强推理）与
`deepseek-v4-flash-vision-exp`（视觉 · 实验，图最多 384 token/张）；旧模型名 `deepseek-chat` /
`deepseek-reasoner` 已停用。

## 常用操作

- `/model`：切换模型；在列表按 `→` 进入当前高亮模型的思考设置（模式开关 + provider 原生强度/token 预算），按模型独立记忆。
- `/context`：查看上下文占用明细（事件分桶、占用总数、构成占比、缓存命中率）；`/compact`：压缩上下文。
- `/skills`、`/skill`、`/reload`：查看、指定和重载技能。
- `/mcp`：运行期管理 MCP 服务。
- `/tasks`：查看后台任务。
- `/continue`：继续执行恢复会话中未完成的计划。
- `Shift+Tab`：循环切换权限模式。
- `Esc`：取消当前回合。
- `Ctrl+C`：退出。

完整按键和斜杠命令见 [docs/guide/reference.md](docs/guide/reference.md)。

## 配置与数据位置

### 模型与运行配置

推荐直接编辑发布包中的 `bin/config.env`。至少配置一家 provider：

```dotenv
DEEPSEEK_API_KEY=你的key
# ZHIPU_API_KEY=你的key
# DASHSCOPE_API_KEY=你的key
# ANTHROPIC_API_KEY=你的key
# OPENAI_API_KEY=你的key
# OPENCODE_GO_API_KEY=你的key
```

还可以配置各家的 `*_BASE_URL`、`*_MODELS`，以及 LLM 超时、子 agent 并发数、联网搜索 Key、GitHub 提交署名（`CODETUI_CO_AUTHOR`，默认关闭）和 `JAVA_OPTS`。请以 `bin/config.env.example` 中的说明为准，避免在多处维护重复的配置清单。

### 项目数据

code-tui 在工作目录的 `.codetui/` 下保存项目级数据，主要包括：

- `sessions/`：会话事件；
- `memory/`：长期记忆；
- `model.json`：上次选择的模型；
- `thinking.json`：各模型的思考模式与强度（仅对主/子 agent 生效，摘要与网页抽取沿用官方默认）；
- `mcp.json`：项目 MCP 配置；
- `permissions.json`：项目权限规则。

这些数据按项目隔离。`-c` 只恢复最近会话，不恢复上次的权限模式；权限模式每次启动都回到默认档。

### 日志

- 使用 `bin/code-tui` 或 `bin\code-tui.cmd`：默认写入安装目录下的 `logs/`，安装目录只读时回退到 `~/.codetui/logs/`。
- 直接 `java -jar` 或通过 Maven 运行：写入 `~/.codetui/logs/`。
- 日志只写文件，最低级别固定为 `INFO`；单文件 10 MB，保留 7 天，总量上限 100 MB。

## 从源码构建与运行

在仓库根目录执行：

```bash
mvn -pl springai-code-tui -am package
```

至少设置一家 provider 的 API Key，然后从一个安全的工作目录运行构建产物：

```bash
export DEEPSEEK_API_KEY=你的key
cd /path/to/disposable-git-project
java -jar /path/to/springai-agentdemo/springai-code-tui/target/springai-code-tui.jar
```

源码构建同样要求 JDK 17+ 和 Maven 3.9+。

## 制作发布包

维护者在仓库根目录执行：

```bash
mvn -pl springai-code-tui -am clean package -Pdist
```

`-am` 不可省：本模块依赖同仓库的 `springai-tamboui-inline-patch`（不发布到远程仓库），
不带 `-am` 时本地 `~/.m2` 没装过同版本就会依赖解析失败。

产物位于 `springai-code-tui/target/`：

- `springai-code-tui-<version>-dist.tar.gz`
- `springai-code-tui-<version>-dist.zip`

归档包含启动脚本、配置示例、主 JAR、全部运行期依赖、使用指南、`LICENSE` 和 `NOTICE`，解压后无需 Maven 即可运行。

## 使用指南

| 文档 | 内容 |
|------|------|
| [security.md](docs/guide/security.md) | 安全边界、权限层已知限制和使用建议 |
| [permissions.md](docs/guide/permissions.md) | 审批面板、规则 DSL、权限模式和内置底线 |
| [background-agent.md](docs/guide/background-agent.md) | 后台子 agent、结果回收、面板和权限矩阵 |
| [subagent.md](docs/guide/subagent.md) | 子 agent 实现原理：三种执行模式、结果回收与取消语义 |
| [vision.md](docs/guide/vision.md) | 视觉输入、模型支持、限制和图片处理 |
| [mcp.md](docs/guide/mcp.md) | MCP 的 stdio / Streamable HTTP 配置和运行期管理 |
| [skills.md](docs/guide/skills.md) | 技能目录、`SKILL.md` 格式和热加载 |
| [interjection.md](docs/guide/interjection.md) | 回合中插话的用法和消息位置 |
| [reference.md](docs/guide/reference.md) | 按键、斜杠命令和已知限制 |

面向改代码的读者另有一份 [implementation-map.md](docs/implementation-map.md)：按功能列出「入口 → 关键类 → 实现要点」，
以及各处顺序约束和设计取舍的来由。

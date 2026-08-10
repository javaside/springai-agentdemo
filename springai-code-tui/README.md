# springai-code-tui

基于 Spring AI 2.0 的编码智能体 + [TamboUI](https://github.com/quanticc/tambo-ui)（`0.4.0`，纯 Java 原始 API）单栏终端界面的命令行编码助手。**多 provider**：按环境变量激活 DeepSeek / 智谱 GLM / 通义千问 / Anthropic / OpenAI，`/model` 可运行时切换。

> 说明：DeepSeek 旧模型名 `deepseek-chat` / `deepseek-reasoner` 将于 2026-07-24 15:59 UTC 停用（期间被透明路由到 V4-Flash），现役模型为 `deepseek-v4-flash`（非思考）与 `deepseek-v4-pro`（强推理）。

## 模块用途

- 单栏对话式 TUI：对话滚动区（流式 token 内联渲染 + 工具调用活动 + 子 agent 嵌套行）、**📋 计划面板**、**⟐ 任务面板**、**⏱ 后台任务面板**、输入框、底部状态栏。
- **多 provider**：`CodeTuiApplication` 按环境变量装配五家 provider，首个可用者激活；`/model` 切换模型，选中记在 `<项目根>/.codetui/model.json`，下次启动自动恢复。
- 智能体工具：`FileSystemTools`、`ShellTools`、`GrepTool`、`GlobTool`、`TodoWriteTool`、`SmartWebFetchTool`、`BochaWebSearch`、`BraveWebSearch`、`AskUserQuestionTool`、`SubagentTool`（`Task` + `ParallelTasks`）、`AutoMemoryTools`（长期记忆）。
- **权限管理**：有副作用的工具在执行前弹审批面板，`Shift+Tab` 在四档间循环。详见 [docs/guide/permissions.md](docs/guide/permissions.md)。
- **子 agent**（`Task` / `ParallelTasks`）：内置四类 agent，支持 `run_in_background` 后台运行。详见 [docs/guide/background-agent.md](docs/guide/background-agent.md)。
- **技能（Skills）**：`/skills` 查看，`/skill` 手动指定，`/reload` 热加载。详见 [docs/guide/skills.md](docs/guide/skills.md)。
- **MCP（接入外部工具）**：读 `.codetui/mcp.json`，`/mcp` 运行期管理。详见 [docs/guide/mcp.md](docs/guide/mcp.md)。
- **上下文管理**：token 用量估算（`/context`），超阈值自动压缩 + `/compact` 手动压缩。
- **视觉输入**：支持视觉的模型能真正看见图片，图片从不进会话记忆，有硬上限。详见 [docs/guide/vision.md](docs/guide/vision.md)。
- **长期记忆**：基于 `AutoMemoryTools`，落盘 `<项目根>/.codetui/memory/`，按项目隔离。
- **项目指令（AGENTS.md）**：读 `~/.codetui/AGENTS.md` + `<项目根>/AGENTS.md`，注入主+子 agent 系统提示。
- **回合中插话**：回合进行中直接 Enter 把消息插进下一次模型调用，不打断回合。详见 [docs/guide/interjection.md](docs/guide/interjection.md)。
- Esc 取消当前回合，Ctrl+C 退出。操作键与斜杠命令详见 [docs/guide/reference.md](docs/guide/reference.md)。

## ⚠️ 安全声明（请务必阅读）

**本工具不是安全沙箱。** 它给智能体开放了对本机文件系统和 shell 的实质性访问能力。本版本起有了一层**权限管理**，但请务必分清它是什么、不是什么：

> **权限层管的是「要不要做这一步」，不是「能做到多远」。**
> 一旦你按下允许，那次调用照样以**你的用户权限**执行，**不受任何目录边界约束**。

完整安全声明与使用建议见 [docs/guide/security.md](docs/guide/security.md)。

### 使用建议

- **只在可以随意丢弃、且已被版本控制干净纳管的目录中运行本工具。**
- **不要在 `$HOME`、系统关键目录，或任何重要仓库的根目录下直接运行。**
- **别把审批面板当橡皮图章**：面板会显示这次要动的具体目标与理由，值得看一眼再按。
- **「跳过权限检查」档真的跳过全部检查**——只在你完全清楚后果时用。

## 构建

```bash
mvn -pl springai-code-tui -am package
```

## 运行

> 发布包（`-Pdist` 产出的 tar.gz/zip）用户：解压后把 `bin/config.env.example` 复制为 `bin/config.env` 填 key 即可。

```bash
# 至少配置一个 provider 的 key（首个可用者激活；可同时配多个，用 /model 切换）
export DEEPSEEK_API_KEY=你的key          # DeepSeek（默认 deepseek-v4-pro）
# export ZHIPU_API_KEY=你的key           # 智谱 GLM（默认 glm-5.2）
# export DASHSCOPE_API_KEY=你的key       # 通义千问（默认 qwen3.7-max）
# export ANTHROPIC_API_KEY=你的key       # Anthropic（默认 claude-opus-5）
# export OPENAI_API_KEY=你的key          # OpenAI（默认 gpt-5.6-sol）
# 各 provider 可选：*_BASE_URL、*_MODELS（逗号分隔，首项为默认）
# 可选调优：CODETUI_LLM_READ_TIMEOUT_SECONDS（默认 300）
#           CODETUI_SUBAGENT_CONCURRENCY（默认 4）
#           CODETUI_BACKGROUND_CONCURRENCY（后台子 agent 并发，默认 4）
#           CODETUI_TASK_OUTPUT_TIMEOUT_SECONDS（TaskOutput 等待上限，默认 300）

cd /path/to/some/disposable/project
java -jar /path/to/springai-code-tui.jar

# 恢复上次会话（仿 Claude Code 的 -c）：接着上次的对话/计划继续
java -jar .../springai-code-tui.jar -c            # 或 --continue
```

### 会话持久化与恢复

会话事件持久化在 `<项目根>/.codetui/sessions/<sessionId>.json`（按项目隔离，已被 `.gitignore`）。

- **默认启动**：开一个全新会话，不读旧历史。
- **`-c` / `--continue` 启动**：恢复最近一次会话（按 mtime），直观回放进界面，可接着聊或用 `/continue` 续跑未完成的计划。

### 日志位置

- **用 `bin/code-tui` 启动**：写到安装目录下的 `logs/`；若只读位置则回退到 `~/.codetui/logs/`。
- **直接 `java -jar` 或 `mvn` 运行**：写到 `~/.codetui/logs/`。
- 滚动策略：单文件 10MB、保留 7 天、总量上限 100MB。
- **生产日志最低级别固定为 `INFO`**，日志只写文件，不设 CONSOLE appender。

## 发布打包（可分发、解压即运行）

```bash
mvn -pl springai-code-tui clean package -Pdist
```

产出 `target/springai-code-tui-<version>-dist.tar.gz` 和 `.zip`，解压后含启动脚本 + 主 jar + 全部运行期依赖，需 JDK 17+。

## 项目文档（docs/）

| 目录 | 内容 |
|------|------|
| `guide/` | 功能使用指南（各专题文档，见下表） |
| `release-notes/` | 每版发版说明，索引见根目录 [CHANGELOG.md](../CHANGELOG.md) |
| `superpowers/specs/` | 功能设计方案（改动前先写） |
| `superpowers/plans/` | 实施计划与踩坑复盘 |

### 使用指南索引

| 文档 | 内容 |
|------|------|
| [security.md](docs/guide/security.md) | 安全声明完整版：工具边界、权限层已知弱点、使用建议 |
| [permissions.md](docs/guide/permissions.md) | 权限管理：审批面板、判定顺序、rules DSL、权限模式、内置底线 |
| [background-agent.md](docs/guide/background-agent.md) | 后台子 agent：结果回收、面板、权限矩阵、三个刹车 |
| [vision.md](docs/guide/vision.md) | 视觉输入：贴图、模型支持列表、硬上限、图片处理 |
| [mcp.md](docs/guide/mcp.md) | MCP 配置：stdio/HTTP 两种传输、运行期管理 |
| [skills.md](docs/guide/skills.md) | 技能配置：目录结构、SKILL.md 格式、热加载 |
| [interjection.md](docs/guide/interjection.md) | 回合中插话：用法、界面展示、消息插入位置 |
| [reference.md](docs/guide/reference.md) | 操作键、斜杠命令、已知限制 |

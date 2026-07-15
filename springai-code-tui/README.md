# springai-code-tui

基于 Spring AI 2.0 的编码智能体 + [TamboUI](https://github.com/quanticc/tambo-ui)（`0.4.0`，纯 Java 原始 API）单栏终端界面的命令行编码助手。**多 provider**：按环境变量激活 DeepSeek / 智谱 GLM / Anthropic / OpenAI，`/model` 可运行时切换。

> 说明：DeepSeek 旧模型名 `deepseek-chat` / `deepseek-reasoner` 将于 2026-07-24 15:59 UTC 停用（期间被透明路由到 V4-Flash），现役模型为 `deepseek-v4-flash`（非思考）与 `deepseek-v4-pro`（强推理）。

## 模块用途

- 单栏对话式 TUI：对话滚动区（流式 token 内联渲染 + 工具调用活动 + 子 agent 嵌套行）、**📋 计划面板**（主 agent 的 todo）、**⟐ 任务面板**（本回合派出的子 agent 状态 ▶/✓/✗ + 当前工具）、输入框、底部状态栏。
- **多 provider**：`CodeTuiApplication` 按环境变量装配 `DeepSeekProvider` / `ZhipuProvider` / `AnthropicProvider` / `OpenAiProvider`（key 缺失即 unavailable），首个可用者激活；`/model` 在当前 provider 的模型间切换（子 agent 也可用 `provider:model` 跨 provider 路由）。智谱走 OpenAI 兼容通路（复用 `spring-ai-openai`，`ZHIPU_BASE_URL` 默认 `.../api/paas/v4`）。四家统一 read 超时（`CODETUI_LLM_READ_TIMEOUT_SECONDS`，默认 300s）。
- 智能体工具：`FileSystemTools`（read/write/edit）、`ShellTools`（执行 shell 命令）、`GrepTool`、`GlobTool`、`TodoWriteTool`、`SmartWebFetchTool`（联网抓取）、`AskUserQuestionTool`（向用户反问、多选拍板）、`SubagentTool`（`Task` 委派单个子 agent + `ParallelTasks` 并发派多个独立子 agent）、`AutoMemoryTools`（`Memory*` 六件套：跨会话长期记忆的读写/增删/改名，仅主 agent）。
- **子 agent（Task / ParallelTasks）**：内置 `explore` / `plan` / `bash` / `general-purpose` 四类（`src/main/resources/agents/*.md`）。`Task` 委派单个子 agent 前台阻塞执行；`ParallelTasks` 一次并发派多个独立子 agent（有界线程池，`CODETUI_SUBAGENT_CONCURRENCY` 默认 4、范围 [1,32]；失败隔离、按序汇总）。内部工具活动带 taskId 内联嵌套显示。
- **技能（Skills）**：`/skills` 查看可用技能清单（模型按需自动调用），`/skill` 为本条消息手动指定技能，`/reload` 重新扫描技能目录——运行中新增/删除 `SKILL.md` 无需重启即对模型与 `/skills` 生效（即便启动时零技能，也能 `/reload` 出第一个新增技能）。
- **MCP（接入外部工具）**：启动时读 `.codetui/mcp.json`（两层：用户 `~/.codetui/mcp.json` + 项目 `<项目根>/.codetui/mcp.json`，项目级同名覆盖用户级）连接外部 [MCP](https://modelcontextprotocol.io/) server（本期仅 **stdio**，即 `npx`/`uvx` 一类本地子进程 server，如 `chrome-devtools-mcp`、官方 filesystem server），把其工具注入**主 agent 与子 agent**。工具名带 `mcp__<server>__<工具>` 前缀避免撞名。连不上的 server **静默降级**（记 WARN、不崩启动）；退出时**有界清理**子进程（≤2s，绝不拖慢 `/exit`）。配置见下方「MCP 配置」。
- **上下文管理**：窗口记忆多轮会话，token 用量估算（`/context` 查看），超阈值自动压缩 + `/compact` 手动压缩。把 cwd / git 状态 / 模型名注入系统提示做 grounding。
- **长期记忆（跨会话）**：基于 `spring-ai-agent-utils` 的 `AutoMemoryTools`（Anthropic Claude Code 那套：`MEMORY.md` 索引 + 分型 Markdown 文件 + 两步保存）。记忆落盘 `<项目根>/.codetui/memory/`（**按项目隔离**，已被 `.gitignore`）；agent 会主动记住用户偏好、项目上下文与反馈，并在后续会话（含 `/clear` 开新会话后）读 `MEMORY.md` 召回。仅主 agent 具备，子 agent 不写长期记忆。与会话记忆互补：会话记忆是当前对话的内存态窗口，长期记忆是跨会话的磁盘态精选事实。
- **项目指令（AGENTS.md）**：启动时读取用户级 `~/.codetui/AGENTS.md` + 项目级 `<项目根>/AGENTS.md`（跨工具生态标准，Codex/Cursor/Aider 等通用；项目里已有的 `AGENTS.md` 直接被读到），把团队约定（构建/测试命令、代码风格、架构约定）注入**主 agent 与子 agent** 的系统提示（顺序 user→project，项目级优先级更高）。人手写、提交入库、启动全量注入、**只读**（编辑文件即改约定，改动需重启生效）。这是与 agent 自写的长期记忆正交的一套「instructions」：前者人写团队约定，后者 agent 自记学到的东西。
- Esc 取消当前回合、Ctrl+C 退出；输入框支持 readline 式编辑快捷键（Ctrl+A/E、Ctrl/Alt+←→ 按词跳、Ctrl+W 删前词、Ctrl+U/K，见「操作键」）。

## ⚠️ 安全声明（请务必阅读）

**本工具不是安全沙箱。** 它给智能体开放了对本机文件系统和 shell 的实质性访问能力，具体边界如下：

- **所有工具都不受工作区 root 目录限制，没有任何技术强制的边界。** `FileSystemTools` 底层依赖 `spring-ai-agent-utils` *支持*可选的 `allowedDirectory` 沙箱，但本工具**刻意不启用**——因为 `ShellTools`/`GrepTool`/`GlobTool` 本就能越界，单给文件读写工具设边界形同虚设、反而制造「有沙箱」的假象。故全线一致、统一靠自律约束。
- **`FileSystemTools` / `ShellTools` / `GrepTool` / `GlobTool` 均不受 root 限制：**
  - `FileSystemTools` 的 read/write/edit 可直接用绝对路径读写 root 之外的任意文件（未配置 `allowedDirectory`，库对空配置直接放行）。
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

> 发布包（`-Pdist` 产出的 tar.gz/zip）用户：解压后把 `bin/config.env.example` 复制为 `bin/config.env` 填 key 即可，
> `bin/code-tui` 会自动加载，无需手动 export。下面的环境变量方式与 `config.env` 里的键名完全一致、二选一。

```bash
# 至少配置一个 provider 的 key（首个可用者激活；可同时配多个，用 /model 切换）
export DEEPSEEK_API_KEY=你的key          # DeepSeek（默认现役，默认 deepseek-v4-pro，另有 v4-flash 非思考款）
# export ZHIPU_API_KEY=你的key           # 智谱 GLM（默认 glm-5.2，另有 glm-5.1/glm-5-turbo；OpenAI 兼容通路）
# export ANTHROPIC_API_KEY=你的key       # Anthropic（默认 claude-opus-4-8，另有 fable-5/sonnet-5/haiku-4-5）
# export OPENAI_API_KEY=你的key          # OpenAI（默认 gpt-5.6-sol，另有 terra/luna）
# 各 provider 可选自定义 base url：DEEPSEEK_BASE_URL / ZHIPU_BASE_URL / ANTHROPIC_BASE_URL / OPENAI_BASE_URL
# 可选调优：CODETUI_LLM_READ_TIMEOUT_SECONDS（默认 300）、CODETUI_SUBAGENT_CONCURRENCY（默认 4）

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

## MCP 配置（接入外部工具）

在**项目根**放 `.codetui/mcp.json`（或用户级 `~/.codetui/mcp.json`，两者按 server 名合并、项目级优先），列出要连接的 MCP server。启动时自动连接并把其工具交给智能体；**无此文件则不启用 MCP，一切照常**。

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
    },
    "chrome-devtools": {
      "command": "npx",
      "args": ["chrome-devtools-mcp@latest"],
      "env": { "FOO": "bar" },
      "enabled": true,
      "timeoutMs": 20000
    }
  }
}
```

字段：`command`（必填，可执行命令）、`args`（可选，参数数组）、`env`（可选，追加环境变量）、`enabled`（可选，默认 `true`；设 `false` 临时停用该条）、`timeoutMs`（可选，连接/初始化超时，默认 20000）。

- **仅 stdio 传输**：即以子进程方式启动的本地 server（`npx` / `uvx` 一类）。`type` 字段省略即 `"stdio"`；`sse` / `streamable-http` 等远程传输本期不支持（配了会记 WARN 并跳过）。
- **工具命名**：发现的工具以 `mcp__<server>__<工具名>` 注入（如 `mcp__filesystem__read_file`），既避免与内置工具/多 server 间撞名，也便于在工具活动行一眼看出出处。段内非法字符会被归一。
- **优雅降级**：某个 server 连不上（命令不存在、启动失败、超时）只记一条 WARN 并跳过，**不影响其他 server、不崩启动**；`mcp.json` 缺失或 JSON 非法同样视为「未启用 MCP」。
- **可用范围**：MCP 工具对**主 agent 与子 agent**（`Task` / `ParallelTasks`）都可用。
- **生命周期**：启动时一次性连接（无运行期热重连/热管理）；`/exit` 时有界清理子进程（≤2s，绝不拖慢退出，见「已知限制」的残留说明）。
- **前置**：stdio server 多为 Node 包，需本机有 `node` / `npx`（或对应运行时）。
- **安全**：MCP server 是你在 `mcp.json` 里显式声明的外部子进程，拥有该进程自身的权限（如 filesystem server 能读写你授权的目录）。它与下方「安全声明」同理**非沙箱**——只连接你信任的 server，别把敏感信息交给来路不明的 server。

## 技能配置（Skills）

技能（Skill）是一份 Markdown 指令文件（`SKILL.md`），描述「遇到某类任务时该怎么做」。模型会按需自动调用（`/skills` 查看清单、`/skill` 手动指定），把该文件正文注入到当前这条消息，从而在特定场景下给模型专门的方法论/操作规程。

### 从哪里读取（两层目录）

启动时扫描**两个文件系统目录**，每个技能是**一个子目录、里面放一个 `SKILL.md`**：

| 层 | 路径 | 说明 |
|---|---|---|
| 用户级 | `~/.codetui/skills/<技能名>/SKILL.md` | 跨项目复用（`user.home` 下） |
| 项目级 | `<项目根>/.codetui/skills/<技能名>/SKILL.md` | 随仓库版本化，**同名覆盖用户级** |

- **技能名 = 子目录名**；合并顺序为**用户 → 项目**，同名以**项目级**为准（项目可覆盖你的个人版）。
- 目录不存在的层**静默跳过**；某层解析报错只跳过该层，不影响另一层、也不崩启动。
- **无 classpath 内置层**——只有上面这两个磁盘目录（没有随 jar 打包的内置技能）。
- 记忆/会话/MCP 用的也是同一个 `.codetui/` 目录约定（`~/.codetui/` 与 `<项目根>/.codetui/`）。

### SKILL.md 格式

YAML frontmatter（至少 `name` 与 `description`）+ 正文：

```markdown
---
name: systematic-debugging
description: Use when encountering any bug, test failure, or unexpected behavior, before proposing fixes
---

# Systematic Debugging

...方法论/操作步骤正文（会被注入给模型）...
```

- `description` 决定模型「何时该调用」——写清触发场景，别只写标题。
- 正文即注入内容；过长会占用上下文，按需精简。

### 目录布局示例

```
~/.codetui/skills/
├── systematic-debugging/
│   └── SKILL.md
└── chrome-devtools/
    └── SKILL.md            # 例：装官方 chrome-devtools-mcp 的 skill

<项目根>/.codetui/skills/
└── writing-plans/
    └── SKILL.md            # 项目专属，随仓库提交
```

### 生效与热加载

- 运行中新增/删除/修改 `SKILL.md` 后，在 code-tui 里执行 **`/reload`** 重扫两层目录即生效——**无需重启**；即便启动时零技能，也能 `/reload` 出第一个新增技能。
- `/skills` 查看当前可用清单（含来源层标注），`/skill` 为本条消息手动指定一个技能。

### 装第三方技能（如 chrome-devtools-mcp 官方 skill）

第三方技能仓库若采用 `skills/<名>/SKILL.md` 布局（如 [chrome-devtools-mcp](https://github.com/ChromeDevTools/chrome-devtools-mcp/tree/main/skills)），直接把对应子目录拷到上面两层之一即可：

```bash
# 全局对所有项目生效
mkdir -p ~/.codetui/skills
cp -r /path/to/chrome-devtools-mcp/skills/chrome-devtools ~/.codetui/skills/
# 或只对当前项目生效
cp -r /path/to/chrome-devtools-mcp/skills/chrome-devtools <项目根>/.codetui/skills/
```

放好后 `/reload`，`/skills` 即可见。

> 注意：技能是**软引导**（提示模型「该怎么做」），并非硬约束——例如它可引导模型优先用 `take_snapshot`（文本）而非 `take_screenshot`，但**挡不住**模型把大文件/图片读进上下文。真正防止上下文被撑爆需在代码层加防线（工具输出限幅、拒读二进制），技能只降低触发概率。

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
| Ctrl+A / Ctrl+E | 光标跳到行首 / 行尾 |
| Ctrl+← / Alt+← / Alt+B | 光标向左按词跳（中文按单字跳） |
| Ctrl+→ / Alt+→ / Alt+F | 光标向右按词跳（中文按单字跳） |
| Ctrl+W / Alt+Backspace | 删除光标前一个词（空白为界；中文按单字删） |
| Ctrl+U / Ctrl+K | 删到行首 / 删到行尾（以当前逻辑行为界，不跨行） |
| Esc | 取消当前正在进行的回合（工具调用/模型生成） |
| Ctrl+C | 退出程序 |

## 斜杠命令

输入 `/` 会弹出命令补全菜单（↑↓ 选择、Tab 补全、Enter 运行、Esc 关闭）：

| 命令 | 行为 |
| --- | --- |
| `/model` | 打开模型选择器，在当前 provider 的模型间切换 |
| `/compact` | 手动压缩会话历史 |
| `/clear` | 清空当前上下文、开一个全新空会话（旧会话留盘，可 `-c` 恢复）；同时清屏并复位面板 |
| `/context` | 查看上下文用量（事件数 / token） |
| `/skill` | 为本条消息指定技能 |
| `/skills` | 查看可用技能清单（模型按需自动调用） |
| `/reload` | 重新扫描技能目录（运行中新增/删除的 `SKILL.md` 生效，无需重启） |
| `/continue` | 续跑上次未完成的计划 |
| `/help` | 显示可用命令与快捷键 |
| `/exit` | 退出程序 |

## 已知限制

- **滚动区不可翻页**：对话滚动区依赖终端自身的 scrollback，程序内不支持翻页控件（输入框 ↑↓ 回溯的是「已提交的历史消息」，与滚动区翻页无关；历史仅内存态，退出不保留）。
- **宽字符光标对齐**：输入框光标位置按显示宽度（东亚宽字符计 2 列）对齐，但极端的 grapheme 组合（如某些 emoji ZWJ 序列、组合字符）可能出现轻微偏移。
- **部分 Ctrl 组合键不可绑定**：终端把 `Ctrl+A..Z` 发成控制字节 1~26，其中 `Ctrl+H/I/J/M` 与 Backspace/Tab/Enter 字节相同、无法区分，故编辑快捷键避开了这几个字母；`Shift/Alt+Enter` 换行能否生效取决于终端能否区分修饰键（Apple Terminal 等区分不了），可靠换行请用 `\` + Enter。
- **无工具沙箱**：见上方安全声明——所有工具（含文件系统工具）都不受 root 约束。自写的真沙箱（`SandboxedShellTool` 等校验所有工具路径参数）列为 v1 之后的增强项，本版本未实现。
- **无程序内会话选择器**：会话已持久化并按项目隔离（见上「会话持久化与恢复」），但程序内不能浏览/切换历史会话；`-c` 只恢复**最近一次**会话（按 mtime），要挑更早的需手动操作会话文件。
- **子 agent 无后台模式**：`Task` 单个前台阻塞、`ParallelTasks` 一批并发前台执行（有界并发，全部 join 后返回）；暂不支持后台任务（`run_in_background` + 轮询回收）与 `/tasks` 详情面板（列为后续增强）。
- **长期记忆无「自动整理」**：跨会话长期记忆已具备（见上「长期记忆」），但暂未接入定期 consolidation（自动汇总/去重冗余记忆）触发器；记忆的增删改全由模型按需驱动。
- **MCP 仅 stdio、无运行期热管理**：本期只支持 stdio 传输（`sse`/`streamable-http` 远程 server 已预留传输接缝但未实现，配了会跳过）；连接仅在启动时建立一次，运行中改 `mcp.json` 需重启生效（无 `/mcp` 热重连）。工具名未做长度截断与跨 server 碰撞去重——默认 DeepSeek 无 64 字符上限、实测工具名远短于此且碰撞需刻意构造，故本期可接受。**退出清理为「有界优先」**：`/exit` 时关闭子进程硬限 2s，若某 server 恰在 2s 内未优雅关完，进程会被 JVM 退出带走、可能短暂残留由 OS 回收——这是「不卡退出」优先于「保证优雅清理」的有意取舍。

# pi ⇄ springai-code-tui 全面对比与功能借鉴清单

- **对比对象 A**：`springai-code-tui`（本仓库主模块，Java 17 + Spring AI 2.0 + TamboUI）
- **对比对象 B**：`pi`（`.worktrees/pi`，`@earendil-works/pi-coding-agent` v0.82.1，TypeScript/Node 22，MIT）
- **快照时间**：2026-08-04（pi 侧 HEAD `2efa728d`）
- **结论先行**：见文末[「应该增加哪些功能」](#8-结论应该增加哪些功能)

> 本文只做**功能与架构**对比，不比较模型效果。所有条目均以两边仓库当前源码/文档为准；
> code-tui README「已知限制」一节中关于 MCP「仅 stdio」的描述已过期（源码与文档正文均已支持 Streamable HTTP），本文按源码口径。

---

## 1. TL;DR

| | springai-code-tui | pi |
|---|---|---|
| 一句话 | **内建全家桶的编码智能体**，把权限、计划模式、子 agent、MCP、长期记忆全做进核心 | **极小核心 + 可扩展平台**，把工作流全部推给扩展/技能/包 |
| 规模 | 主代码 ~18k 行 / 测试 ~21k 行（单模块） | ~214k 行 TS（7 个 package） |
| 内建工具 | read/write/edit、bash、grep、glob、webFetch、双搜索、TodoWrite、AskUserQuestion、Task/ParallelTasks、Memory×6 | read/write/edit、bash、grep、find、ls（**仅 7 个**） |
| 安全模型 | **执行前审批面板 + 规则 DSL + 内置底线 + 计划模式**（本项目的核心差异化） | **无权限系统**，只有「项目信任」这一层加载守卫，其余靠容器化 |
| 会话 | 事件溯源落盘、`-c` 恢复最近一次 | **会话树**（分支/fork/clone/标签/树内跳转/分支摘要）+ 程序内选择器 |
| 可扩展 | Skills（Markdown）+ MCP | Extensions（TypeScript 插件 API）+ Skills + Prompt 模板 + 主题 + 键位 + **包管理器** |
| 集成 | 仅交互式 TUI | 交互 / `-p` 打印 / `--mode json` / `--mode rpc` / Node SDK 嵌入 |
| provider | 5 家（DeepSeek/智谱/千问/Anthropic/OpenAI） | 30+ 家 + OAuth 订阅登录（Claude Pro、ChatGPT、Copilot） |

**最值得借鉴的三件事**：① 会话树与会话管理 UI；② 工具输出限幅与上下文纪律；③ 交互层的成熟度（`@` 补全、`!` 直跑 shell、思考档位、成本显示、外部编辑器、主题/键位可配）。

**最不该照搬的一件事**：pi 没有权限系统。code-tui 的审批面板 + 内置底线 + 计划模式是我们相对它的**核心优势**，任何"向 pi 看齐"的重构都不能削弱它。

---

## 2. 两者是什么

### 2.1 pi 的仓库结构

pi 是一个 npm monorepo，刻意把「LLM 接入」「agent 运行时」「终端 UI」「CLI 产品」拆成独立可发布的包：

| 包 | 作用 |
|---|---|
| `@earendil-works/pi-ai` | 统一多 provider LLM API（30+ 家）、认证解析、token/成本核算、图片输入、图片生成、跨 provider 交接、OAuth |
| `@earendil-works/pi-agent-core` | agent 运行时：工具调用循环、状态机、事件流 |
| `@earendil-works/pi-coding-agent` | 面向用户的 CLI 产品（交互模式、会话、扩展宿主、包管理） |
| `@earendil-works/pi-tui` | 终端 UI 框架：差分渲染、CSI 2026 同步输出、括号粘贴、**Kitty/iTerm2 内联图片**、自动补全组件 |
| `pi-storage-sqlite-node` | 会话的 SQLite 存储后端（可替换 JSONL） |
| `pi-evals` | 基于 `vitest-evals` 的行为评测 |
| `pi-server` | 实验性服务端 |

这套分层的直接后果是：**pi 的能力可以被别的程序复用**（SDK 嵌入、RPC 驱动、JSON 事件流），而 code-tui 目前是一个不可嵌入的单体 TUI 程序。

### 2.2 设计哲学的正面冲突

pi 官方文档明确写着（`docs/usage.md` 结尾「Design Principles」）：

> It intentionally does not include built-in MCP, sub-agents, permission popups, plan mode, to-dos, or background bash.

而这一行里列举的 **MCP、子 agent、权限弹窗、计划模式、待办**，**恰好全是 code-tui 已经内建并打磨过的东西**。

但要注意 pi 的实际做法不是「不做」，而是「做成示例扩展」——`packages/coding-agent/examples/extensions/` 里躺着：

```
permission-gate.ts      protected-paths.ts     confirm-destructive.ts
plan-mode/              subagent/              todo.ts
git-checkpoint.ts       dirty-repo-guard.ts    sandbox/  gondolin/
```

**所以真正的差别是「一等公民 vs 二等公民」**：
- code-tui：权限/计划/子 agent 是内核语义，判定顺序、失败关闭、内置底线都写死在引擎里，用户拿到即生效；
- pi：同样的能力是可选插件，装了才有、质量随作者，但**任何人都能替换整套策略**。

对我们的启示不是「要不要学 pi 拆插件」，而是：**我们已经赢下了「开箱即用的安全语义」，下一步该补的是「交互成熟度」与「会话工程」，而不是把内建能力拆掉**。

---

## 3. 领域逐项对比

### 3.1 模型与 provider

| 维度 | code-tui | pi |
|---|---|---|
| provider 数量 | 5（DeepSeek / 智谱 / 千问 / Anthropic / OpenAI） | 30+（含 Bedrock、Vertex、Azure、OpenRouter、Groq、Cerebras、xAI、Mistral、Moonshot、MiniMax、ZAI、Together、HF、Fireworks、本地 llama.cpp / Ollama / vLLM / LM Studio…） |
| 激活方式 | 环境变量有 key 即可用，首个可用者激活 | 环境变量 / `auth.json` / `/login` OAuth / 云厂商凭据链 |
| 订阅登录 | ❌ | ✅ `/login`：Claude Pro/Max、ChatGPT Plus/Pro（Codex）、GitHub Copilot |
| 模型清单 | 内置常量 + `*_MODELS` 环境变量覆盖 | **自动生成的模型目录**（上下文窗口、价格、能力位），`pi update --models` 刷新，`--list-models` 查询 |
| 运行时切换 | `/model`（**只在当前 provider 内切**） | `/model` 跨 provider 模糊搜索 + `Ctrl+L`；`Ctrl+P`/`Shift+Ctrl+P` 在「scoped models」间循环；`/scoped-models` 配置循环集合 |
| 思考档位 | ❌ 无任何 reasoning/thinking 控制 | `off/minimal/low/medium/high/xhigh/max`，`Shift+Tab` 循环，**输入框边框颜色即当前档位**，可配 `thinkingBudgets` 每档 token 预算 |
| 思考内容展示 | ❌ | 思考块渲染 + `Ctrl+T` 折叠/展开 + `hideThinkingBlock` |
| 自定义 provider | ❌（需改代码新增 `LlmProvider` 实现） | ✅ 扩展里 `pi.registerProvider()`，含自定义 OAuth 流程（文档 772 行专述） |
| 超时/重试 | 统一 read 超时 `CODETUI_LLM_READ_TIMEOUT_SECONDS`；`RetryingChatModel`（子 agent 专用，桥接流式 + 空流守卫） | `retry.{enabled,maxRetries,baseDelayMs}` + `retry.provider.{timeoutMs,maxRetries,maxRetryDelayMs}`，全部可配；服务端要求的退避超阈值直接失败而非静默等待 |
| 代理 | ❌（靠 JVM 参数） | `httpProxy` 设置项 |
| 传输 | HTTP SSE | `transport: sse / websocket / websocket-cached / auto`，含握手超时与空闲超时 |

> code-tui 在**中文 provider 深度**上有优势（千问兼容模式空串 id 分片的 SSE 归一化、智谱兼容通路都是真机踩出来的），pi 侧对国内网关的适配没有同等针对性。这点不必羡慕。

### 3.2 工具集

| 工具 | code-tui | pi |
|---|---|---|
| 读文件 | ✅ `Read`（含图片→视觉引用） | ✅ `read`（`offset`/`limit` 行窗口、图片自动转 ImageContent、**截断元数据回传**） |
| 写/编辑 | ✅ `Write`/`Edit` | ✅ `write`/`edit`（带 `file-mutation-queue` 串行化、560 行的 diff 渲染） |
| Shell | ✅ `Bash`（`TimeLimitedToolCallback` 限时） | ✅ `bash`（**无默认超时**、可传 `timeout`、流式输出、`killProcessTree`、游离子进程追踪、`shellPath`/`shellCommandPrefix` 可配） |
| 搜索 | ✅ `Grep` + `Glob` | ✅ `grep` + `find` + `ls`（三者均可被 `--tools` 开关） |
| 联网 | ✅ `WebFetch` + 博查（中文）+ Brave（英文） | ❌ 核心无联网工具，靠技能包（`pi-skills` 里的 brave-search）或扩展 |
| 待办 | ✅ `TodoWrite` + 📋 计划面板 | ❌ 核心无，`examples/todo.ts` 提供 |
| 反问用户 | ✅ `AskUserQuestionTool`（多选面板） | ❌ 核心无，`examples/question.ts`/`questionnaire.ts` 提供；扩展可用 `ctx.ui.select/confirm/input` |
| 子 agent | ✅ `Task` + `ParallelTasks`（有界并发、失败隔离、⟐ 任务面板、taskId 内联嵌套） | ❌ 核心无，`examples/subagent/` 提供 |
| 长期记忆 | ✅ `Memory*` 六件套（MEMORY.md 索引 + 分型 md） | ❌ 无 |
| MCP | ✅ stdio + Streamable HTTP，`/mcp` 运行期启停并回写配置 | ❌ 明确不做 |
| 工具开关 | ❌（只能靠权限规则 deny） | ✅ `--tools` 白名单 / `--exclude-tools` 黑名单 / `--no-builtin-tools` / `--no-tools`；扩展可**覆盖内建工具同名实现** |
| 输出限幅 | ❌ **本项目层未设统一策略**（未配置行数/字节上限，也不向用户呈现「被截断」与完整输出落盘路径） | ✅ 统一 `truncate.ts`：默认 2000 行 / 50KB 双限，先到先算，**不产生半行**，回传 `TruncationResult`（总行数/总字节/截断依据），bash 另有 `fullOutputPath` 让模型按需取完整输出 |
| 远程执行 | ❌ | ✅ `BashOperations`/`ReadOperations` 可插拔（`examples/ssh.ts` 把工具跑到远端） |

**这一栏是 code-tui 全面领先、只有一个致命缺口**：工具输出限幅。README 的技能章节自己也承认「真正防止上下文被撑爆需在代码层加防线（工具输出限幅、拒读二进制），技能只降低触发概率」——pi 的 `truncate.ts` 就是那条防线的成品参考。

### 3.3 权限与安全

| 维度 | code-tui | pi |
|---|---|---|
| 执行前审批 | ✅ 五选项面板（一次/本会话/永久/拒绝/拒绝并中断） | ❌ 核心无（示例扩展 `permission-gate.ts` 可做，靠 `tool_call` 事件返回 `{block:true}`） |
| 规则 DSL | ✅ `工具名(模式)`，allow/ask/deny 三档 + 前缀/glob 语义 | ❌ |
| 内置底线 | ✅ allow 盖不住的一层（密钥、shell 启动文件、`rm -rf /`…，穿透 `sudo`/`bash -c` 包装） | ❌ |
| 计划模式 | ✅ 只读放行、写与命令 DENY、`ExitPlanMode` 交付计划 | ❌（`examples/plan-mode/` 扩展） |
| 模式切换 | ✅ `Shift+Tab` 四档循环（含「跳过权限检查」），常驻状态栏 | —（`Shift+Tab` 在 pi 是切思考档位） |
| 分层配置 | ✅ 用户级 + 项目级取并集，**项目层只能收紧不能放宽** | — |
| 项目信任 | ❌ **无**：项目级 `.codetui/permissions.json`、`skills/`、`mcp.json` **clone 下来即被加载** | ✅ 首次进入含项目资源的目录会询问是否信任，决定记入 `~/.pi/agent/trust.json`；未信任前只加载 context 文件与全局扩展；`defaultProjectTrust: ask/always/never`；`-a`/`-na` 单次覆盖 |
| 沙箱 | ❌（诚实声明无沙箱） | ❌（同样诚实声明），但给了三套容器化方案文档：Gondolin 微 VM、Docker、OpenShell |

**这里有一个真实的、可执行的安全缺口值得立刻补：项目信任。**
code-tui 的项目层配置「只能收紧不能放宽」这条设计确实堵住了「clone 一个仓库让 agent 变宽松」，但它没有堵住另外两条：
- 项目级 `.codetui/skills/*/SKILL.md` 会被自动加载并注入模型——**技能是任意提示词注入**，可以指挥模型干任何事；
- 项目级 `.codetui/mcp.json` 会被自动连接——**MCP server 是任意本地子进程**（`command` + `args`）。

也就是说：`git clone` 一个恶意仓库、`cd` 进去、启动 code-tui，就可能起一个任意子进程。pi 的项目信任提示正是为这个场景设计的。**这是本次对比里发现的唯一「安全等级」级别的差距。**

### 3.4 会话管理（pi 的最大优势区）

| 能力 | code-tui | pi |
|---|---|---|
| 落盘 | ✅ `<项目根>/.codetui/sessions/<id>.json`，事件溯源 | ✅ `~/.pi/agent/sessions/`（按 cwd 组织）JSONL，**树结构**；`sessionDir` 可配，可换 SQLite 后端 |
| 恢复最近 | ✅ `-c`（含界面回放） | ✅ `-c` |
| 程序内选择器 | ❌ 明确列为已知限制 | ✅ `/resume` / `pi -r`：搜索、切换排序、只看命名会话、`Ctrl+R` 重命名、`Ctrl+D` 删除（优先走 `trash` CLI） |
| 指定会话 | ❌ 只能手动动文件 | ✅ `--session <path\|部分UUID>` |
| 命名 | ❌ | ✅ `/name`、`--name`，选择器里可检索 |
| 临时会话 | ❌ | ✅ `--no-session` |
| **会话树** | ❌ 线性 | ✅ `/tree`：跳到任意历史节点继续、折叠/展开分支段、`Shift+L` 打标签、5 种过滤模式（默认/隐藏工具/仅用户/仅标签/全部）、双击 Esc 直达 |
| 分叉 | ❌ | ✅ `/fork`（从某条用户消息开新文件）、`/clone`（复制当前活动分支）、`--fork` |
| **分支摘要** | ❌ | ✅ 切换分支时可把「被放弃的那条分支」摘要后挂到新位置，保留上下文 |
| 导出 | ❌ | ✅ `/export` 输出 HTML（含主题着色、工具渲染）或 JSONL；`--export` CLI 子命令 |
| 分享 | ❌ | ✅ `/share` 传私有 GitHub gist 并给可读 HTML 链接 |
| 导入 | ❌ | ✅ `/import <file>` |
| 会话信息 | `/context`（事件数/token/视觉占用） | `/session`（文件、ID、消息数、token、成本） |

会话树是 pi 相对**所有**同类工具（包括 Claude Code）最独特的设计：它把「我想试另一条路」从「开新会话丢掉上下文」变成「在同一个文件里长出一根新枝，还能把旧枝摘要带过来」。

### 3.5 上下文压缩

| 维度 | code-tui | pi |
|---|---|---|
| 触发 | 超阈值自动 + `/compact` | `contextTokens > contextWindow - reserveTokens` 自动 + `/compact [自定义指令]` |
| 可配 | ❌ 硬编码 | ✅ `compaction.{enabled,reserveTokens=16384,keepRecentTokens=20000}`，全局/项目两层 |
| 切点规则 | 回合感知 | 回合边界优先；**单回合超预算时允许「拆回合」**，对回合前缀单独摘要后与历史摘要合并；绝不切在工具结果上 |
| 摘要格式 | 自由文本 | **结构化模板**：Goal / Constraints / Progress(Done·In Progress·Blocked) / Key Decisions / Next Steps / Critical Context + `<read-files>` `<modified-files>` |
| 文件跟踪 | ❌ | ✅ 跨多次压缩**累积**读过/改过的文件清单 |
| 迭代摘要 | — | 把上一次摘要作为上下文喂给下一次，且从**上次保留边界**而非压缩条目开始重摘 |
| 序列化 | — | `serializeConversation()` 把消息转成 `[User]:` / `[Assistant tool calls]:` 文本，**避免模型把它当对话续写**；工具结果统一截到 2000 字符 |
| 缓存 | — | 压缩请求用全新 routing session id 且禁用 prompt-cache 写入（一次性提示不值得占缓存） |
| 可拦截 | ❌ | ✅ 扩展 `session_before_compact` 可取消或自带摘要（可用另一个便宜模型做压缩） |

我们的 `NotifyingCompactionStrategy` + `MediaReferencePreservingCompactionStrategy` 解决的是「通知」和「媒体引用不丢」，**压缩产物本身的质量工程（结构化模板 + 文件跟踪 + 拆回合）没有做**。

### 3.6 可扩展性

| 机制 | code-tui | pi |
|---|---|---|
| 技能 Skills | ✅ 两层目录（`~/.codetui/skills` + 项目），`/skills` `/skill` `/reload` 热加载 | ✅ **五个来源**：`~/.pi/agent/skills`、`~/.agents/skills`、`.pi/skills`、cwd 及祖先目录的 `.agents/skills`、包、settings 数组、`--skill`；遵循 [Agent Skills 标准](https://agentskills.io/specification) |
| 技能调用 | 模型自动 + `/skill` 选择器 | 模型自动 + **`/skill:name 参数`** 强制调用（参数以 `User: <args>` 追加）；`disable-model-invocation` 可设为纯手动；`allowed-tools` 预授权（实验） |
| 复用他家技能 | ❌ 需手动拷贝目录 | ✅ settings 里直接指 `~/.claude/skills`、`~/.codex/skills` |
| 提示词模板 | ❌ | ✅ `prompts/*.md` → `/文件名`，支持 `$1 $2 $@ ${1:-默认} ${@:N:L}`、`argument-hint` 在补全里显示 |
| 插件/扩展 | ❌ | ✅ TypeScript 扩展 API（文档 2961 行）：注册工具/命令/快捷键/CLI flag、订阅 ~40 种事件（含 `tool_call` 拦截、`session_before_compact`、`user_bash`、输入事件）、自定义 TUI 组件与对话框、自定义消息/工具渲染、自定义 footer/状态行/编辑器（可实现 vim 模式）、注册 provider、动态工具集 |
| 主题 | ❌ 硬编码 `Theme.java`（93 行常量） | ✅ JSON 主题（51 个颜色 token + `vars` 复用 + `$schema` 校验），内建 dark/light，**首次启动探测终端背景色自动选**，编辑当前主题文件热重载 |
| 键位 | ❌ 硬编码 | ✅ `keybindings.json` 全量可改（~60 个具名 action，命名空间 id），文档给了 Emacs / Vim 两套示例，`/reload` 生效 |
| 分发 | ❌（拷目录） | ✅ **包管理器**：`pi install npm:@foo/bar` / `git:github.com/u/r@v1` / 本地路径；`pi list` / `pi update --all` / `pi config` 逐项启停；`package.json` 的 `pi` 字段声明 extensions/skills/prompts/themes；有官方 gallery |
| 热重载 | `/reload` 只重扫技能 | `/reload` 重载键位、扩展、技能、提示词模板、主题、context 文件 |

### 3.7 交互层（TUI）

| 能力 | code-tui | pi |
|---|---|---|
| 渲染 | TamboUI `InlineApp`，单栏内联 | 自研 `pi-tui`：三策略差分渲染 + CSI 2026 同步输出（无闪烁）+ 括号粘贴 |
| 滚动 | ❌ 依赖终端 scrollback，程序内不可翻页 | 组件内 `pageUp`/`pageDown`，`/tree` 内有完整导航 |
| 文件引用 | ❌ | ✅ 输入 `@` 模糊搜索项目文件；Tab 补全路径；CLI 也支持 `pi @a.ts @b.ts "..."` |
| 直跑 shell | ❌ | ✅ `!cmd` 执行并把输出**给模型**；`!!cmd` 执行但**不进上下文**（`bashMode` 有专门主题色） |
| 外部编辑器 | ❌ | ✅ `Ctrl+G` 打开 `$VISUAL`/`$EDITOR`/`externalEditor`（`code --wait`） |
| 消息排队 | ✅ 忙时排队，回合结束自动出队（单一语义） | ✅ **两种语义**：`Enter` = steering（当前 assistant 回合的工具跑完就插进去），`Alt+Enter` = follow-up（全部干完再说）；`Alt+Up` 取回队列到输入框；`Escape` 中止并把队列还回编辑器；`steeringMode`/`followUpMode` 可配「一条一条」或「一次全给」 |
| 复制 | ❌ | ✅ `Ctrl+X` 复制最后一条助手消息（`/tree` 里复制选中条目）、`/copy` |
| 剪贴板贴图 | ❌ 明确不做 | ✅ `Ctrl+V`（Windows `Alt+V`）直接贴剪贴板图片 |
| 终端内显示图片 | ❌ 只显示路径 | ✅ Kitty / iTerm2 图形协议内联渲染，`terminal.showImages`、`imageWidthCells` 可配 |
| 工具输出折叠 | 固定摘要行 | `Ctrl+O` 展开/折叠工具输出 |
| 挂起 | ❌ | ✅ `Ctrl+Z` 挂到后台 |
| 编辑快捷键 | readline 子集（`Ctrl+A/E/W/U/K`、`Alt+B/F`、`Ctrl/Alt+←→`） | 更全：外加 kill-ring（`Ctrl+Y` yank / `Alt+Y` yank-pop）、`Ctrl+-` 撤销、`Alt+D` 删后词、`Ctrl+]` 跳字符 |
| 中文/IME | 宽字符按显示宽度对齐 | `Focusable` 接口专为 IME 设计，`showHardwareCursor` 可选 |
| 帮助 | `/help` | `/hotkeys` 全量快捷键 + `/changelog` 版本历史 + `/settings` 交互式设置面板 |
| 启动信息 | 欢迎框 | 启动头（快捷键、已加载 context 文件、模板、技能、扩展），`quietStartup` 可关 |

### 3.8 视觉/图片

这是 code-tui **反而更克制、设计更清晰**的一处，值得记一笔：

| 维度 | code-tui | pi |
|---|---|---|
| 用户贴图 | 输入框写路径 / 拖文件（认魔数不认扩展名，支持反斜杠转义与引号），`Ctrl+X` 撤销误附 | `Ctrl+V` 剪贴板 / 拖入 / `@file` / CLI `@screenshot.png` |
| 是否入会话记忆 | **永不**——落盘只有文本引用块，字节只在出站最后一刻挂当轮的 | 图片进消息，`images.autoResize`（2000×2000）压一压，`images.blockImages` 可全禁 |
| 预算 | **硬上限**：每请求 ≤3 用户图 + ≤1 工具图 + 6k 视觉 token，每回合 ≤12 张·次（≈21.6k token 封顶），`/context` 单列显示 | 无显式视觉预算 |
| 能力判定 | 按模型 id 前缀白名单，不在名单一律判「不支持」并**拦住不发**、保留输入框内容 | 模型目录里带能力位 |
| 格式处理 | PNG/JPEG/GIF 缩放、BMP/TIFF 转码、WebP 原样、HEIC/AVIF 不兑现、>5000 万像素直接拒（只读文件头不解码） | `photon`/worker 线程做 resize，EXIF 方向校正 |
| 终端显示 | ❌ | ✅ |

**「图片从不进会话记忆 + 硬预算 + delivery 字段如实交代结局」这套设计比 pi 更成熟**，pi 那边没有等价的成本封顶。这条不需要改。

### 3.9 运行模式与集成

| 模式 | code-tui | pi |
|---|---|---|
| 交互 TUI | ✅ | ✅ |
| 一次性打印 | ❌ | ✅ `-p "prompt"`，支持管道 stdin 合并、`@文件` |
| 结构化事件流 | ❌ | ✅ `--mode json`，逐行 JSON（agent/turn/message/tool 全生命周期 + 压缩 + 重试事件） |
| 进程集成 | ❌ | ✅ `--mode rpc`：stdin/stdout JSONL 双向协议（1576 行文档），含提示、状态、模型、思考档、队列、压缩、重试、bash、会话、命令，以及扩展 UI 的请求/响应通道 |
| 库嵌入 | ❌ | ✅ Node SDK：`createAgentSession()` / `createAgentSessionRuntime()`，可自带工具、扩展、技能、系统提示、会话管理 |
| 评测 | ❌ | ✅ `pi-evals`，真跑 agent 会话并报告 transcript 与 token |

对本项目的意义：**没有 headless 模式意味着 code-tui 无法进 CI、无法被脚本驱动、无法自己评测自己**。这是「教学示例 → 生产工具」路上迟早要跨的一步。

### 3.10 配置体系

| 维度 | code-tui | pi |
|---|---|---|
| 配置文件 | `permissions.json`、`mcp.json`（各两层） | 统一 `settings.json`（`~/.pi/agent/` + `.pi/`，**嵌套对象深合并**）+ `keybindings.json` + `auth.json` + `trust.json` |
| 程序内改配置 | `/permissions`（只能删规则）、`/mcp`（启停并回写） | `/settings` 交互面板、`pi config` 逐资源启停、`/trust` |
| 系统提示覆盖 | ❌ | ✅ `.pi/SYSTEM.md` 整体替换、`APPEND_SYSTEM.md` 追加、`--system-prompt` / `--append-system-prompt` |
| 项目指令 | ✅ `~/.codetui/AGENTS.md` + `<项目根>/AGENTS.md`（两层，只读，启动注入主+子 agent） | ✅ `~/.pi/agent/AGENTS.md` + **从 cwd 向上遍历所有父目录**的 `AGENTS.md` **或 `CLAUDE.md`**；`--no-context-files` 可关；`/reload` 生效 |
| 会话环境变量 | ❌ | ✅ bash 工具注入 `PI_SESSION_ID` / `PI_SESSION_FILE` / `PI_PROVIDER` / `PI_MODEL` / `PI_REASONING_LEVEL`，每次命令启动时解析（换模型立刻反映） |
| 离线 | ❌ | ✅ `--offline` / `PI_OFFLINE=1` 关掉所有启动期网络动作 |

### 3.11 观测、成本与分发

| 维度 | code-tui | pi |
|---|---|---|
| token 统计 | `/context` 估算（JTokkit）+ 状态栏百分比 | footer 常驻：token、cache 命中、**成本（$）**、上下文占用、模型名；`/session` 汇总；`showCacheMissNotices` 提示缓存大面积失效 |
| 成本 | ❌ 完全没有 | ✅ 依赖模型目录里的价格表实时累加（含摘要生成、工具上报的用量） |
| 日志 | logback 滚动（10MB/7 天/100MB） | `diagnostics.ts` + `timings.ts` |
| 版本检查 | ❌ | ✅ 启动查最新版（可关）、`pi update --self` 自更新、`/changelog` 显示更新内容、Windows 专用自更新路径 |
| 安装 | tar.gz/zip 自包含发布包（需 JDK 17+） | npm 全局 / curl 一键脚本 / **Bun 编译的单文件二进制**（各平台） |
| 供应链 | Maven 常规 | 直接依赖锁定精确版本、`min-release-age=2`、shrinkwrap、生命周期脚本白名单、`npm audit` 定时任务 |
| 工程流程 | spec → plan → TDD（`docs/superpowers/`），测试须带 `-pl` 模块作用域 | biome + tsgo 类型检查 + vitest + 发布前本地安装冒烟 |

---

## 4. code-tui 相对 pi 的独有优势（务必守住）

1. **执行前权限审批 + 规则 DSL + 内置底线**——pi 完全没有，这是唯一级别的差异化。
2. **计划模式**（DENY 语义 + `ExitPlanMode` + 计划审批面板 + 子 agent 专属只读提示）。
3. **子 agent 一等公民**：`Task` / `ParallelTasks` 有界并发、失败隔离、⟐ 任务面板、taskId 内联嵌套渲染。
4. **MCP 内建**：双传输、`/mcp` 运行期启停并回写、`${ENV_VAR}` 插值、优雅降级。
5. **视觉输入的成本纪律**：图片从不进会话记忆 + 每请求/每回合硬上限 + `delivery` 如实交代结局。pi 无等价机制。
6. **跨会话长期记忆**（`Memory*` 六件套，按项目隔离）。
7. **中文生态深度**：博查中文搜索、千问/智谱兼容通路与真机踩坑修复、全中文界面与文档。
8. **文档与设计留痕**：`docs/superpowers/specs|plans` 记录了每个决定的取舍与变异测试结论——pi 侧没有等价的一手材料。

---

## 5. 应当借鉴的功能（分级清单）

每条给出：**是什么 / 为什么值得 / 在我们这怎么落 / 成本 / 风险**。成本按「人日」粗估，S≈1-2、M≈3-5、L≈8-15、XL≈20+。

### P0 —— 高收益、成本可控，建议下一个版本就做

#### P0-1 工具输出限幅（行/字节双限 + 截断如实呈现 + 完整输出落盘）

- **是什么**：给 `Bash`/`Read`/`Grep`/`Glob` 及**全部 MCP 工具**套一层统一限幅装饰器：默认 2000 行 / 50KB，先到先算，绝不产生半行；结果尾部追加 `[已截断：共 N 行 / M KB，完整输出见 .codetui/artifacts/tool-<id>.txt]`。
- **为什么值得**：这是 code-tui 目前**唯一的上下文安全缺口**。一条 `find /` 或一个话痨 MCP 工具就能把上下文打满、触发压缩、丢掉真正重要的历史。README 自己已经承认这条防线该在代码层做。pi 的 `truncate.ts` 是可直接照抄的成品（含 `firstLineExceedsLimit`、`lastLinePartial` 这类边界处理）。
- **怎么落**：新增 `TruncatingToolCallback`，插在装饰链**内层**（权限 → 限时 → 媒体外置 → **限幅** → 真实工具），与 `TimeLimitedToolCallback` 同层注册；`MediaExternalizingCallback` 之后跑，避免把 base64 计进字节数。
- **成本**：M。**风险**：低。注意 diff/patch 类工具结果被截断会让模型误判，需给出「完整输出路径」让它能取回。

#### P0-2 项目信任（trust）提示

- **是什么**：首次在一个含项目级资源（`.codetui/skills/`、`.codetui/mcp.json`、`.codetui/permissions.json`）的目录启动时，弹一次「是否信任此项目」；决定记入 `~/.codetui/trust.json`（按规范化路径，父目录决定可继承）。未信任则**只加载用户级资源**。
- **为什么值得**：目前 `git clone` 一个恶意仓库并在其中启动，`.codetui/mcp.json` 会被直接连接（= 起任意子进程）、`.codetui/skills/` 会被注入模型（= 任意提示词注入）。「项目层只能收紧」这条只护住了权限规则，护不住技能与 MCP。
- **怎么落**：启动序列里插一个 gate（在 `SkillCatalog` / `McpRegistry` / 项目级 `PermissionConfigLoader` 之前），交互式询问；新增 `--trust-project` / `--no-trust-project` 单次覆盖；非交互（未来的 `-p` 模式）默认不信任。
- **成本**：M。**风险**：低，但要注意别把已有用户的现有项目一夜之间全变成「要点一次确认」——首次可给「本目录及其父目录」的选项。

#### P0-3 `@` 文件引用补全 + Tab 路径补全

- **是什么**：输入框里 `@` 触发项目文件模糊搜索，选中后插入路径；Tab 补全已输入的路径前缀。
- **为什么值得**：这是使用频率最高的交互之一。目前用户必须手打完整相对路径，长路径极易打错，错了模型就去 `Glob` 找，白烧一轮。
- **怎么落**：复用现有斜杠命令补全菜单的组件与按键处理；文件索引用 `GlobTool` 同款遍历 + `.gitignore` 过滤，首次扫描后缓存、`/reload` 刷新。
- **成本**：M。**风险**：大仓库首次扫描慢——需异步 + 上限。

#### P0-4 `!cmd` / `!!cmd` 直接执行 shell

- **是什么**：输入框以 `!` 开头 = 不经模型直接跑命令，输出进对话区**并作为上下文交给模型**；`!!` = 跑但**不进上下文**。
- **为什么值得**：极高频。「我先看一眼 `git status` 再决定让你干什么」目前只能切窗口或者浪费一轮 LLM 调用。`!!` 那个变体尤其聪明——它承认「有些命令我只是自己想看」。
- **怎么落**：`CodeTuiView` 提交前分流；复用 `ShellTools` 的执行路径但**跳过权限引擎**（用户亲手输入的命令不需要审批自己）——这点要在 UI 上写明白。
- **成本**：S–M。**风险**：需明确「`!` 命令不走权限层」，避免用户误以为有保护。

#### P0-5 会话选择器 `/resume` + 命名 `/name`

- **是什么**：程序内浏览本项目历史会话（时间/名称/首条消息预览），可搜索、重命名、删除；`--session <id前缀>` 指定启动。
- **为什么值得**：我们**已经**按项目落盘了多槽会话，只差一个 UI——收益/成本比极高。目前 `-c` 只能拿最近一次，更早的等于丢了。
- **怎么落**：`FileSessionRepository` 已有列举能力；复用 `/mcp`、`/permissions` 面板的列表组件；会话名存进会话文件头部事件。
- **成本**：M。**风险**：低。删除建议先移到 `.codetui/sessions/trash/` 而非直接删。

#### P0-6 思考档位（thinking level）

- **是什么**：`off/low/medium/high` 档位控制（各 provider 映射到 `reasoning_effort` / `thinking.budget_tokens` / `enable_thinking`），状态栏显示当前档，一个键循环。
- **为什么值得**：现在完全没有——用 `deepseek-v4-pro` 或 `claude-opus` 时只能吃默认值。「简单任务降档省钱、难题升档」是日常最有效的成本/质量旋钮。
- **怎么落**：`LlmProvider` 增加 `applyThinking(ChatOptions, level)`，各 provider 各自映射（DeepSeek/千问/智谱走兼容层字段，Anthropic 走 thinking budget，OpenAI 走 reasoning effort）；不支持的档位降级并提示。
- **注意**：`Shift+Tab` **已被权限模式占用**，不要照抄 pi 的键位；建议 `Ctrl+R` 或 `/think` 命令。
- **成本**：M。**风险**：各家字段差异大，需逐家真机验证（可参考本项目已有的 provider 真机冒烟测试门控做法）。

#### P0-7 token/成本/缓存常驻显示

- **是什么**：状态栏或专用行常驻显示：本会话累计输入/输出 token、缓存命中、**估算花费**。
- **为什么值得**：agent 最大的隐性成本是「不知道花了多少」。我们已经有 `ContextStats` 与视觉预算的口径工程，补一个价格表就能出成本。
- **怎么落**：`ModelOption` 增加单价字段（内置表 + `*_PRICES` 环境变量覆盖）；从 `ChatResponse.getMetadata().getUsage()` 取真实用量而非估算，累加进会话。
- **成本**：S–M。**风险**：价格会变——显示时标注「估算」，并允许配置覆盖。

#### P0-8 一批「一行配置」级的小补齐

| 项 | 说明 | 成本 |
|---|---|---|
| context 文件向上遍历 + 兼容 `CLAUDE.md` | 现在只读用户级 + 项目根的 `AGENTS.md`；改成从 cwd 向上走到 git root，且同时认 `CLAUDE.md` | S |
| 技能兼容其他 harness 目录 | 允许配置额外技能根（默认加上 `~/.claude/skills`、`~/.agents/skills`、项目 `.agents/skills`），瞬间复用整个生态 | S |
| bash 工具注入会话环境变量 | `CODETUI_SESSION_ID` / `CODETUI_MODEL` / `CODETUI_PROVIDER` 注入子进程，脚本可自省 | S |
| `--tools` / `--exclude-tools` / `--no-tools` | 与权限层互补的**硬开关**：只读审计场景直接不给写工具，比 deny 规则更直接 | S |
| `--system-prompt` / `--append-system-prompt` + `.codetui/SYSTEM.md` | 系统提示可覆盖/追加 | S |
| `--session-dir` / `--no-session` | 临时会话与自定义存储位置 | S |
| `shellCommandPrefix` / `shellPath` | 例如 `shopt -s expand_aliases`，或 Windows 指定 shell | S |
| `httpProxy` 设置 | 国内环境刚需，现在只能靠 JVM 参数 | S |
| `/hotkeys` 与 `/changelog` | `/help` 已有，补一个全量键位表与版本历史 | S |
| 外部编辑器 | 一个键把当前输入丢进 `$EDITOR` 编辑后带回（长提示词刚需） | S–M |

### P1 —— 高价值，但需要一个完整迭代

#### P1-1 会话树（`/tree` + `/fork` + `/clone` + 标签 + 分支摘要）

- **是什么**：会话从线性事件列表升级为**树**（每条 entry 带 `id`/`parentId`，当前位置是活动叶子）。`/tree` 可跳到任意历史点继续（自然长出新枝）；`/fork` 从某条用户消息开新会话文件；`/clone` 复制当前分支；切枝时可把被放弃的分支摘要挂过来。
- **为什么值得**：这是 pi 真正的原创设计，解决的痛点非常真实——「这条路走歪了，我想回到 20 轮前换个方向，但不想丢掉刚才学到的东西」。现在 code-tui 只能 `/clear` 重开。
- **怎么落**：`SessionEvents` / `FileSessionRepository` 的事件模型加 `parentId`，读取时按「活动叶子回溯到根」拼上下文（现有的悬空 tool_calls 清理逻辑可复用）；UI 用现有面板组件做树形选择器。
- **成本**：L。**风险**：**这是数据格式变更**，需要迁移策略（老会话按线性 = 单链树读入）；`ContextStats` / 压缩 / `-c` 回放全部要跟着改。建议先做只读的 `/tree` 浏览 + `/fork`，分支摘要留到后续。

#### P1-2 压缩产物结构化 + 参数可配

- **是什么**：把压缩摘要从自由文本改成结构化模板（Goal / Constraints / Progress / Key Decisions / Next Steps / Critical Context + 读过/改过的文件清单），支持 `reserveTokens`、`keepRecentTokens` 配置，支持 `/compact <自定义关注点>`，支持跨多次压缩累积文件清单，支持「单回合超预算」的拆回合处理。
- **为什么值得**：压缩是长会话质量的生死线。当前只保证「压了」，不保证「压对」。pi 的模板 + 文件跟踪能显著降低「压完之后模型忘了自己改过哪些文件」这类事故。
- **怎么落**：`NotifyingCompactionStrategy` 内替换提示词模板；文件清单从会话里的工具调用参数提取（`Write`/`Edit`/`Read` 的 path）。
- **成本**：M。**风险**：低。注意 memory 里那条「工具循环每迭代重读会话存储」——压缩仍只能在回合间做。

#### P1-3 提示词模板（自定义斜杠命令）

- **是什么**：`~/.codetui/prompts/*.md` 与 `<项目根>/.codetui/prompts/*.md`，文件名即命令名，支持 `$1 $2 $@ ${1:-默认}` 参数与 `argument-hint`。
- **为什么值得**：技能是给**模型**看的「该怎么做」，模板是给**用户**用的「我常说的那句话」。两者不重叠。团队把 `/review`、`/release` 这类流程沉淀成模板后，新人开箱即用。
- **怎么落**：复用 `SkillCatalog` 的两层目录扫描 + frontmatter 解析（`ReloadableSkillTool` 的热加载路径也能复用）；接进现有斜杠命令补全菜单。
- **成本**：M。**风险**：低。

#### P1-4 主题与键位可配置

- **是什么**：`Theme.java` 的常量抽成 JSON 主题（放 `~/.codetui/themes/*.json`），`/theme` 切换；键位抽成 `~/.codetui/keybindings.json`。
- **为什么值得**：主题——浅色终端用户现在基本没法用；键位——README 已经承认「部分 Ctrl 组合不可绑定」且 `Ctrl+X`/`Ctrl+G` 都因全局热键冲突换过键，**可配置正是这类冲突的通用解**（换给谁都可能被抢，那就让用户自己换）。
- **怎么落**：`Theme` 改为实例 + 加载器；按键分发处引入具名 action 表。
- **成本**：M（主题）+ M（键位）。**风险**：键位重构会碰所有交互测试，建议先做主题。

#### P1-5 `-p` 打印模式 + `--mode json`

- **是什么**：`code-tui -p "prompt"` 跑完打印结果退出；`--mode json` 逐行输出结构化事件。
- **为什么值得**：进 CI、被脚本驱动、自动化评测的前提。也是「让这个项目从示例变成工具」的分水岭。
- **怎么落**：`AgentRuntime` 与 `CodeTuiView` 的耦合已经通过 `AgentListener` 解开了一部分——新增一个 headless 的 `AgentListener` 实现即可；权限层在非交互下默认 `deny + 记录`（**绝不默认放行**）。
- **成本**：M–L。**风险**：非交互下的权限语义要想清楚（建议：需要审批 = 拒绝并告诉模型，除非显式 `--dangerously-skip-permissions`）。

#### P1-6 steering / follow-up 双队列语义

- **是什么**：把现有的单一排队拆成两种：「插队引导」（当前回合工具跑完就送进去）与「后续任务」（全部干完再送）；`Alt+↑` 取回队列内容到输入框。
- **为什么值得**：现在只能等回合彻底结束才生效。看到模型跑偏时，最有价值的一句纠正恰恰需要**立刻**送达。
- **成本**：M。**风险**：要保证插入点不撕裂 tool_calls 配对（我们在取消回合那次已经踩过这个坑，逻辑可复用）。

#### P1-7 `/export` HTML

- **是什么**：把会话导出成自包含 HTML（含着色、工具折叠）。
- **为什么值得**：给同事看「agent 干了什么」、给 issue 附证据、给自己回顾。成本低于想象（pi 的实现就是模板 + 主题色注入）。
- **成本**：M。**风险**：低。`/share` 传 gist 建议**不做**（涉及外发，与本项目的安全叙事冲突）。

### P2 —— 战略级，需要单独立项

#### P2-1 扩展机制（插件 API）

- **是什么**：让第三方在不改本仓库代码的前提下注册工具、命令、事件钩子、渲染器。
- **为什么值得**：这是 pi 全部生态的地基。没有它，每个新需求都得改我们的核心代码。
- **在 Java 里怎么落**（三选一，按成本升序）：
  1. **jar SPI**：`~/.codetui/extensions/*.jar` + `ServiceLoader` + 稳定的 `CodeTuiExtension` 接口。最 Java、最省事，但作者得会 Java 且要编译。
  2. **脚本扩展**：内嵌 GraalJS 或 JSR-223，扩展用 JS 写。门槛低、生态近，但引入大依赖、沙箱语义更复杂。
  3. **子进程协议**：扩展是独立进程，走 JSON-RPC（本质是「我们自己实现一个 MCP server 反向接口」）。隔离最好，但延迟与复杂度最高。
- **建议**：**先不做通用扩展，先把 MCP 用足**——我们已经有 MCP 了，它已经覆盖了「第三方提供工具」这个最大用例。真正缺的是「第三方改 UI/流程」，那个需求量级远小于成本。
- **成本**：XL。**风险**：高（一旦发布 API 就要长期兼容）。

#### P2-2 资源包管理器

- **是什么**：`code-tui install git:github.com/u/skills-repo`，统一安装/更新/启停技能、模板、主题。
- **为什么值得**：技能生态要能分发才有生态。目前只能手动 `cp -r`。
- **建议**：等 P1-3（模板）与 P1-4（主题）落地、有东西可装了再做。第一版只支持 git clone + 目录约定即可，不必学 npm。
- **成本**：L。

#### P2-3 OAuth 订阅登录

- **是什么**：`/login` 用 Claude Pro/Max、ChatGPT 订阅额度而非 API key。
- **为什么值得**：对个人用户是巨大的成本差异。
- **风险**：各家 OAuth 流程私有且会变；国内网络可达性成问题。**优先级低于上面所有项**。
- **成本**：L。

#### P2-4 终端内联图片（Kitty / iTerm2）

- **是什么**：模型截的图、生成的图直接在 TUI 里显示。
- **为什么值得**：视觉链路目前是「单向的」——模型看得见图，用户看不见。补上这个才闭环。
- **约束**：TamboUI 未必暴露写原始转义序列的口子（我们已知它连清屏 API 都要反射）；且需 pty 实机验证。
- **成本**：M–L。**风险**：中，终端兼容性碎片化。

#### P2-5 RPC 模式 / SDK 嵌入

在 P1-5（`-p` + json）之后再评估。除非有明确的「被别的程序驱动」需求，否则收益有限。

---

## 6. 明确不建议照搬

| 项 | 理由 |
|---|---|
| **pi 的「核心不内建权限/计划/子 agent/MCP/todo」哲学** | 那是 pi 的定位选择，不是先进性。我们的内建能力是差异化优势，拆成插件只会变弱。 |
| `/share` 上传 gist | 会话内容外发，与本项目的安全叙事直接冲突。要分享就用本地 `/export` 出 HTML，用户自己决定往哪发。 |
| 遥测 / 安装上报 / analytics | 与本项目定位不符，也没有回收数据的基础设施。 |
| npm 供应链那一整套 | Maven 生态的对应做法不同，照搬无意义。 |
| `Shift+Tab` 切思考档位 | 我们这个键已经是权限模式，且权限模式**更该占据这个高频位**。 |
| 无默认超时的 bash | pi 的 bash 没有默认超时。我们有 `TimeLimitedToolCallback`，这点我们是对的。 |

---

## 7. 建议的落地顺序

**第一批（安全与纪律，一个版本）**
P0-1 工具输出限幅 → P0-2 项目信任 → P0-8 里的全部 S 项

> 这一批的共同点：都是「补漏」，不改架构，几乎不碰 UI，风险最低而收益立竿见影。

**第二批（交互成熟度，一个版本）**
P0-3 `@` 补全 → P0-4 `!`/`!!` → P0-5 会话选择器 + 命名 → P0-7 成本显示 → P0-6 思考档位

> 这一批是用户每天都能感知到的体验跃迁，且互相独立、可并行。

**第三批（会话工程，一个版本）**
P1-1 会话树（先只读浏览 + `/fork`）→ P1-2 压缩结构化 → P1-6 双队列

> 会话树是数据格式变更，必须单独一个版本、带迁移与回放验证。

**第四批（可配置化与外部集成）**
P1-3 提示词模板 → P1-4 主题/键位 → P1-5 `-p` + `--mode json` → P1-7 `/export`

**之后再评估**
P2 全部。特别提醒：**在做 P2-1 扩展机制之前，先确认 MCP 没能覆盖的需求到底有多少**——很可能答案是「不多」。

---

## 8. 结论：应该增加哪些功能

按「必须做 / 该做 / 可以做」三档收敛：

### 必须做（补漏，不做就是缺陷）

1. **工具输出限幅** —— 目前唯一的上下文安全缺口，README 自己已承认。
2. **项目信任提示** —— 目前 clone 恶意仓库即可让项目级 `mcp.json` 起任意子进程、项目级技能注入任意提示词。这是本次对比发现的唯一「安全等级」差距。

### 该做（高频体验，做完就是另一个产品）

3. `@` 文件引用补全 + Tab 路径补全
4. `!cmd` / `!!cmd` 直接执行 shell
5. 程序内会话选择器 `/resume` + 会话命名（我们已有多槽落盘，只差 UI）
6. 思考档位控制（注意别占用 `Shift+Tab`）
7. token / 成本 / 缓存常驻显示
8. context 文件向上遍历 + 兼容 `CLAUDE.md`、技能兼容 `~/.claude/skills`、bash 注入会话环境变量、`--tools` 硬开关、系统提示覆盖、`httpProxy`、外部编辑器 —— 这一组全是 1-2 天的小项，合起来的观感提升不亚于一个大功能
9. **会话树 `/tree` + `/fork`** —— pi 最有原创性的设计，也是我们和它差距最大的一处，值得单开一个版本
10. 压缩摘要结构化 + 参数可配
11. 提示词模板（自定义斜杠命令）
12. 主题与键位可配置（键位可配是我们反复被全局热键抢键这个问题的通用解）
13. `-p` 打印模式 + `--mode json` —— 进 CI 与自动化评测的前提

### 可以做（战略性，先别急）

14. steering / follow-up 双队列、`/export` HTML、终端内联图片
15. 扩展机制、包管理器、OAuth 订阅登录、RPC/SDK —— **在此之前先确认 MCP 覆盖不到的需求究竟有多少**

### 一句话

> **code-tui 在「安全语义」和「agent 能力内建度」上已经领先 pi；差距集中在「交互成熟度」与「会话工程」两块。**
> 先用两个小版本补齐输出限幅、项目信任和一批交互小项，再用一个版本做会话树——做完这三步，code-tui 在功能面上就没有明显短板了。


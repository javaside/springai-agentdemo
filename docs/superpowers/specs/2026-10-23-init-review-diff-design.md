# /init、/review、/diff 三命令

> 对标 Codex `/init` `/review` `/diff` 与 Claude Code 同名命令。`/init`、`/review` 是提示词命令
> （`/continue` 同构，零新机制）；`/diff` 是 UI 即时命令（daemon 线程跑 git、专用非 markdown 通道
> 逐字下沉 scrollback）。设计已经一轮 subagent 评审修订（通道重选/并发防护/测试接缝均由评审实证驱动）。

## 目标与范围

- **目标**：
  1. `/init`：让 agent 分析项目并生成/改进项目级 `AGENTS.md`；
  2. `/review`：让 agent 只读审查工作树改动（正确性/安全/测试/可维护性）；
  3. `/diff`：不经模型，直接把 `git diff HEAD`（含未跟踪文件列表）逐字打进 scrollback。
- **非目标**：
  - 不做 `/review` 的参数化（审 PR / 指定 base）——工作树审查是默认场景，参数化后续按需加；
  - 不做 `/diff` 的分页/面板交互（YAGNI，见淘汰备选）；
  - 不改权限层、不改提示词系统装配（`/init` 写 AGENTS.md 走既有 FILE_WRITE 判定）。

## 前提事实（代码证据）

| # | 事实 | 证据 |
|---|---|---|
| 1 | `/continue` 是提示词命令先例：`clearInput` → 组装提示词 → 忙时 `enqueue(prompt, null)` / 闲时 dispatch | `CodeTuiView.java:2588-2606` |
| 2 | `pushInfo` 只发 INFO 行（锁内改 pending、锁外 publish，线程安全；mcp-reload daemon 已在跨线程调用） | `ConversationState.java:282-289`、`CodeTuiView.java:4483-4500` |
| 3 | **ASSISTANT 行走 MarkdownRenderer 且渲染状态跨行/跨批次/跨游标保持**——`#` 渲成标题吞井号、`-/+` 行变列表项吃掉标记、``` 围栏翻转状态机、`|` 触发表格攒块 | `ScrollbackPrinter.java:280-288`、`MarkdownRenderer.java:287/303-310/241-251` |
| 4 | daemon 回推 UI 的防护先例：`/resume` 面板的 `sessionEpoch` 代际计数（打开/Esc/切换/删除各 ++，daemon 捕获 epoch、回写前校验即弃）——main 上 d5ebee34 刚落地，`/diff` 的代际守卫完全模仿该形状 | `CodeTuiView.java`（sessionEpoch/fillSessionDigests/confirmDeleteSession，2026-10-24 合入） |
| 5 | `/clear` 与会话切换（switchToSession）都会清 pending + outputQueue——在飞 daemon 的迟到输出会落进新会话画面 | `ConversationState.java:398`、`CodeTuiView.java:2499/4381-4382` |
| 6 | 全仓生产代码 ProcessBuilder 为零——`/diff` 的 GitSnapshot 是首例（须申报并给测试接缝） | 全仓 grep |
| 7 | `quietGitStatus` 不可复用：它给库方法套 `System.setOut` 黑洞（全局交换，**不能上并发线程**）、只产 status 文本、private static | `AgentTools.java:1358-1366` |
| 8 | AGENTS.md 是装配期一次性烘焙进系统提示——本会话写完**不生效**，重启才注入 | `AgentTools.java:565-566/792`、`ProjectInstructions.java:10-48` |
| 9 | 输出分批：每 UI 批 ≤300 行（`MAX_ROWS_PER_DRAIN`）+ 12ms 时间预算——机制有单测、**12ms 数值未实机标定**（常量注释自述） | `CodeTuiView.java:159/272-275` |
| 10 | 真实 git 的非门控单测先例：@TempDir + `git init` | `AgentToolsTodoReminderEndToEndTest.java:56-59` |
| 11 | 提示词命令测试先例：空闲 dispatch 断言提示词关键词 + 忙时 enqueue 断言队列内容 | `CodeTuiViewContinueDigestTest.java:43-103` |
| 12 | COMMANDS 表现 19 条，新增 3 条与全部既有命令**无前缀包含**（唯一前缀对 /skill⊂/skills 已有排序纪律）；补全菜单 `slashMatches` 按 startsWith 自动适配，零额外接线 | `CodeTuiView.java:384-403/2371-2377` |

## 用户已核准的口径（2026-10-23）

1. `/init`、`/review` 走**纯提示词命令**（agent 用既有工具自己干活），不预采集注入；
2. `/diff` 未跟踪文件**只列名+字节数**，不展开内容；
3. `/diff` 实现为 daemon 线程 + scrollback（方案 A；B「模型转述 diff」与逐字精确目的冲突、C 分页面板对「看一眼」过重，均淘汰）。

## 设计定案

### `/init`、`/review`（CodeTuiView 命令链各一分支 + COMMANDS 两行）

- 提交路径照抄 `/continue` 结构：`clearInput()` → 固定中文提示词 → 忙时 `enqueue` / 闲时 dispatch。
- `/review` **UI 预检**：GitSnapshot 探测非 git 仓库 → 一行 notice 拒绝（不烧回合；提示词驱动 git 必失败只是白花一次模型调用）。
- `/init` 提示词要点（锁定目标与生效语义）：
  - 目标是**项目级 `<root>/AGENTS.md`**，不碰用户级 `~/.codetui/AGENTS.md`；
  - 先读构建文件/模块布局/测试约定与**既有 AGENTS.md（若有）**；已存在则**保留既有事实、改进而非整篇重写**；
  - 内容要求：简洁的 agent 工作指引（构建/测试命令、代码约定、注意事项），只写「从代码推不出来的事实」；
  - 写完**明说「重启 code-tui 后生效」**（事实 #8——不说用户会以为当场挂载）。
- `/review` 提示词要点：先 `git diff HEAD` + `git status`（含未跟踪）看全貌 → 按**正确性/安全/测试/可维护性**四维度找问题、按严重度排序输出 → **只读审查，除非用户明确要求否则不动手修**（DEFAULT 档权限层兜底；ACCEPT_EDITS/BYPASS 档下提示词是唯一防线，PLAN 档天然 DENY 写——此差异记为特性）。
- 忙时入队语义说明：出队时才执行，看到的是**当时最新**的工作树（特性，测试断言注意时机）。

### `/diff`（GitSnapshot 小类 + daemon 线程 + 专用通道）

**`ui.GitSnapshot`（新包级小类，测试接缝即设计目的）**：

```java
/** 一次 git 快照的四元组；error 非 null 时其余字段为空。包级可见：CodeTuiView 与测试直接注入/覆写。 */
record GitSnapshot(String statLine, List<String> body, List<String> untracked, String error) {
    static GitSnapshot capture(Path root, GitCommand git) { ... }
}
@FunctionalInterface interface GitCommand { List<String> run(Path root, String... args) throws IOException; }
```

- 默认 `GitCommand` 用 ProcessBuilder（`git -C root <args>`，timeout 10s，非零退出码把 stderr 首行放进 error）；**测试注入桩**即可测「git 不可用/非仓库/空改动/大 diff」全部分支（事实 #6 的申报落地）。
- 采集：`git diff HEAD`（staged+unstaged 合一）+ `--stat` 尾行 + `git ls-files --others --exclude-standard`（未跟踪列名 + 字节数）。
- 非 git 仓库探测：`git rev-parse --is-inside-work-tree`（不靠 `.git` 目录存在性——worktree 下它是文件）。

**UI 侧（CodeTuiView）**：

- 命令链分支：`clearInput()` → **无忙碌闸门**（只读；同 /tasks 口径——忙时恰是想看改动的时刻）→ 单飞检查（`diffRunning` volatile，在飞则 notice「正在生成 diff」并返回）→ 起 daemon 线程。
- daemon 线程：`GitSnapshot.capture` → **分批回写**（每 ≤200 行一批，走新公共 API），每批前校验代际。
- **代际守卫**：`diffEpoch`（volatile long）；`/clear` 分支与 `switchToSession` 各自 `diffEpoch++`（同 session-digests 弃写模式，事实 #4/#5）；daemon 捕获启动时 epoch，回写前不一致即弃——迟到输出不打进新会话画面。
- **Esc 不取消**（定案取舍）：git 进程亚秒级、diff 回写毫秒级，引入进程取消的复杂度不值得；spec 与 reference.md 如实写明。

**ConversationState 新公共 API（申报的 API 面变更）**：

```java
/** 逐行下沉一批 UI 主动输出（/diff 专用通道）：不受 acceptingTurnId 门控（非模型输出）、不做 markdown 渲染。 */
public void pushRawLines(List<String> lines, OutputLine.Kind kind)
```

- **新 `OutputLine.Kind.DIFF`**：ScrollbackPrinter 走单色 `lineCursor` 分支渲染（不走 MarkdownRenderer——事实 #3 的污染证据是通道重选的直接原因）；diff 的 `+/-` 前缀着色（增绿删红）在 printer 的 DIFF 分支内做，纯字符串前缀判断、不改写内容。

### 输出形状

```
↺ 工作树改动 · <--stat 尾行>          ← INFO
<diff 正文，DIFF 通道逐行>            ← 200 行/批
未跟踪（不在 diff 中）：               ← INFO（仅有未跟踪时）
  name (N bytes)
（工作树干净）                         ← 空改动时唯一一行
```

## 测试策略

| 层 | 要点 |
|---|---|
| `GitSnapshotTest` | @TempDir + 真实 git init（事实 #10 先例）：空改动/有改动（含 staged）/未跟踪/非 git 目录（inject 桩抛错）；`GitCommand` 桩测 timeout 与非零退出的 error 通道 |
| `CodeTuiViewInitReviewTest` | 照 ContinueDigest 先例（事实 #11）：空闲 dispatch 断言提示词关键句（/init 的「项目级」「重启后生效」；/review 的「只读」「四维度」）；忙时 enqueue 断言队列；/review 非 git 预检 notice；斜杠命令进 ↑ 历史 |
| `CodeTuiViewDiffTest` | GitCommand 桩注入：正常输出断言 `pollPending` 的 Kind.DIFF 与行序；代际守卫（触发 /clear 后迟到批次不出现）；单飞（连按两次仅一线程）；空改动/非 git/git 失败三分支 |
| 回归 | ContinueDigestTest、SessionsPanelTest 不动全过；任务尾全量 `mvn -pl springai-code-tui -am test`（已知 flaky CodingAgentSpikeTest.todoTurnIdBinding 按 CONTRIBUTING 单跑复核） |

## 文档同步

reference.md 命令表 3 行 + implementation-map §5 命令表 3 行 + **README 常用操作 3 行**（上批 /resume //fork 先例）。

## 淘汰的备选

- **B（/diff 做成提示词命令）**：每次花 token；diff 经模型转述必失真——与「逐字精确」目的正面冲突。
- **C（/diff 本地面板分页）**：对「看一眼改了什么」过重（YAGNI）。
- **复用 quietGitStatus**：`System.setOut` 是全局交换，放并发 daemon 线程会吞掉同时刻 UI/agent 的合法输出；且只产 status 文本、private static（事实 #7）。
- **diff 正文走 ASSISTANT/markdown 通道**：评审实证 markdown 会改写 diff 内容（吞 `#`、吃 `+/-` 标记、围栏污染状态机）——淘汰于设计评审期。

## 影响面

- 改动：`CodeTuiView`（3 命令分支 + COMMANDS 3 行 + 代际/单飞字段）、`ConversationState`（+pushRawLines、+Kind.DIFF）、`ScrollbackPrinter`（DIFF 渲染分支）、`/clear` 与 `switchToSession` 两处 +1 行 epoch 自增。
- 新增：`ui.GitSnapshot`。
- 文档：reference.md / implementation-map §5 / README。
- 不动：权限层、advisor 链、MCP、会话管理。

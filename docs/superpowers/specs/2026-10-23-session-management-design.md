# 会话管理增强：/resume 面板 + /fork + --resume 启动参数

> 现状只有 `-c`（恢复最近一次会话），选不了、分叉不了、清理不了。对标 Codex `/resume /fork /delete`
> 与 Gemini `/chat list|resume`：运行期会话列表面板（恢复/分叉/删除）、`/fork` 分叉当前会话、
> `--resume <id>` 启动参数（前缀匹配）。全部复用既有积木：惰性仓库、`/clear` 切换序列、
> 本地选择器四件套、MCP 后台连接模式。

## 目标与范围

- **目标**：
  1. `/resume` 面板：列出 `<root>/.codetui/sessions/` 全部会话（时间 + 首条用户消息摘要），
     Enter 恢复、f 分叉选中项、d 删除（两段式确认）、Esc 关闭；
  2. `/fork`：分叉**当前**会话到新会话 id 并切过去，原会话文件不动；
  3. `--resume <id>` 启动参数：按 stem 精确或唯一前缀匹配直接恢复指定会话；
  4. 面板列表摘要后台异步补齐，面板秒开。
- **非目标**：
  - 会话重命名（`/rename`）、归档、导出分享（Gemini `/chat share`）——后续单独立项；
  - 会话内**按消息点**分叉（Codex 双 Esc 编辑上一条并分叉）——属检查点/rewind 那条线；
  - 不做删除的回收站/确认输入 id 级防呆——两段式确认已对齐 `/permissions` 先例；
  - 不改会话文件格式（`PersistedSession` DTO 不动）。

## 前提事实（实测/代码证据）

| # | 事实 | 证据 |
|---|---|---|
| 1 | 会话目录文件量大：本机 `.codetui/sessions/` 有 **192 个**文件，大小 0.4KB～773KB | `ls -lah` 实测（2026-10-23） |
| 2 | 同步解析全部文件做摘要不可行：最大 773KB 文件 × 192 个，按键线程必卡 | 由 #1 推论； Jackson 流式读到首条 USER 即停可把单文件读取量压到前几 KB（首条 user 通常在文件头部，events 按 append 序排列） |
| 3 | `FileSessionRepository.latestSessionId(baseDir)` 已只扫文件名 + mtime，不解析全文 | `FileSessionRepository.java:192-204` |
| 4 | 会话 id 自带 UTC 时间戳：`SessionIds.newId()` = `uuuuMMdd'T'HHmmss` + `-` + UUID 前 6 位，**可按名近似排序** | `SessionIds.java:14-18` |
| 5 | `ConversationState.replayHistory(messages)` 一步完成 scrollback 回放 + todo 面板重建（`HistoryReplay.lastTodoSnapshot`）+ goal 失效提示，运行期恢复可直接复用 | `ConversationState.java:309-331` |
| 6 | `/clear` 的完整切换序列是运行期切会话的模板：`clearInput()` → 闸门 `!state.isBusy()` → `killAllBackgroundTasks` → `clearContext()` → `resetForNewSession()` → `outputQueue.clear()` → `printer.resetMarkdown()` → `lastShownModel=""`（新会话首回合重打 ⚙ 行）→ `pendingSkill=null`（新会话不继承技能挂载）→ 清屏（成功分支内还有 `scrollTail.clear()` + `resetTailDedup()` + `printer.welcome(...)`，防 resize 重放复活旧会话画面） | `CodeTuiView.java:2472-2508` |
| 7 | `clearContext()` 的会话切换共 6 个动作：换 sessionId + 5 项会话级清理（accumulator reset、clearSessionRules、interjections drainForRefill、goalManager.clear、todoReminder.reset） | `CodingAgent.java:1146-1168` |
| 8 | `findEvents` 返回的事件已经过 `SessionEvents.sanitize`（加载即净化）——fork 复制出来的是合法序列 | `FileSessionRepository.java:243` |
| 9 | 会话持久化的是**注入后**文本：首条 user 可能带 `InterjectionText` 包裹、`<skill_instruction>` 前置、`<file_reference>` 块，摘要须剥离才可读 | `implementation-map.md` §7；剥离逻辑在 `ui.HistoryReplay`（包级）与 `agent.interjection.InterjectionText.unwrap`（public） |
| 10 | `--permission-mode` 已有「下一个参数是选项 → 当缺值处理」的解析先例与注释纪律 | `CodeTuiApplication.java:422-434` |
| 11 | `fileFor()` 对 sessionId 做 `replaceAll("[^A-Za-z0-9._-]", "_")`——文件名安全兜底已有 | `FileSessionRepository.java:279-282` |
| 12 | 面板四件套模板成熟（`/tasks`：`pickingTasks` + scope 行 + 键分派 + 状态行；`/permissions` 的 `d` 键两段式删除确认） | `CodeTuiView.java:4061-4159`、`permsPendingDelete` |

## 用户已核准的口径（2026-10-23）

1. **fork 入口两个都要**：`/fork` 分叉当前会话 + `/resume` 面板内 `f` 键分叉选中项，共用一个
   `forkSession(id)` 实现；
2. **`--resume <id>` 启动参数要**，支持前缀匹配；
3. **列表摘要后台补齐**：面板秒开（id+时间），daemon 线程流式解析首条 USER 后刷新；
4. **面板内 `d` 键删除顺手做**，两段式确认对齐 `/permissions`。

## 设计定案

### 组件总览

| 组件 | 位置 | 动作 |
|---|---|---|
| `SessionCatalog` | `agent.session`（新类） | 扫描 + 摘要提取 |
| `UserTextStripper` | `agent.session`（新类） | 从 `HistoryReplay` 提取剥离函数，HistoryReplay 委托保持单一出处 |
| `switchContext(String)` | `CodingAgent` | `clearContext` 重构为其特例 |
| `fork(String)` | `FileSessionRepository` | 复制事件到新 id |
| **SubmitHandler 会话门面** | `agent.seam.SubmitHandler`（现有接口） | 新增 default 方法：`currentSessionId()` / `switchContext(String)` / `sessionExists(String)` / `forkSession(String)` / `deleteSession(String)` / `sessionMessages(String)`。**必需**：`CodeTuiView` 只持有 state/onSubmit/root，恢复/分叉/删除序列引用的仓库与 agent 能力没有其它接线通道（同 `clearContext`/`killAllBackgroundTasks` 走 seam 的既有模式）；default 空实现保证测试桩不破 |
| `/resume` 面板 + `/fork` 命令 | `CodeTuiView` | 四件套 + 切换序列 |
| `--resume` 解析 | `CodeTuiApplication` | 启动参数 + 前缀匹配 |

### `SessionCatalog`（`agent.session`，新类）

无状态工具类，两个入口：

| 方法 | 语义 |
|---|---|
| `List<SessionSummary> scan(Path baseDir)` | **同步、瞬时**：只 `Files.list` 扫文件名 + mtime（复用 `latestSessionId` 的遍历方式，不解析内容）。`SessionSummary` 为 record `(String id, long mtimeMillis, long sizeBytes, String digest)`，`digest` 初始 null。按 mtime 降序（最近在前）。空目录/不存在返回空表，**不抛** |
| `String firstUserDigest(Path file)` | **流式解析**：Jackson `JsonParser` 逐 token 走 `events[]`，读到第一条 `messageType=USER` 的 `content` 即停止并返回（不读完整个文件）；无 USER（空会话/全是 tool）返回 null；损坏 JSON 返回 null（记 DEBUG 日志，**面板只显示「（无法解析）」不报错**）。单行化（`\n`→空格）+ 截断 80 字符 |

digest 生成前先过 `UserTextStripper.stripAll`（见下）——首条 user 可能是
`<file_reference>` 块开头（拖图起手）或 `<skill_instruction>` 前置（技能挂载起手），
不剥离的话摘要全是机器格式。

**淘汰的备选**：摘要顺带数 USER 事件轮数（Codex 列表带轮数）——读到首条即停的流式优化就没了，
每文件全量解析回到 #2 的性能坑；轮数信息由恢复后 `replayHistory` 的 INFO 行
（「已恢复 N 轮对话」）承担，列表不重复。

### `UserTextStripper`（`agent.session`，新类；从 `HistoryReplay` 提取）

把 `HistoryReplay` 的 `stripSkillInstruction` / `stripFileReferences`（含 `summarise` / `field`
两个私有辅助）**搬到** agent 层，`HistoryReplay` 改为委托；`InterjectionText.unwrap` 本就是
public，不改。新增 `stripAll(text)` = unwrap → stripSkillInstruction → stripFileReferences 的
组合，供 catalog 与（未来的）其它摘要场景共用。

理由：单一出处纪律（`InterjectionText` 的 wrap/unwrap 同类先例）；依赖方向必须是 ui → agent，
`agent.session.SessionCatalog` 不能反向依赖 `ui.HistoryReplay`。搬移是纯搬家，行为零变化，
`HistoryReplay` 现有测试（若有）不动即验证。

### `CodingAgent.switchContext(String targetSessionId)`

`clearContext()` 重构为 `switchContext(SessionIds.newId())`，行为完全不变（6 个切换动作
照旧）；新增 public `switchContext(String)` 只是把目标从「新 id」换成「指定 id」。
调用方负责：①仅在空闲时调用（与 `/clear` 同一闸门）；②调用前先验证目标存在。

**为什么 /resume 与 /clear 必须共用同一份切换不变量**：会话切换共 6 个动作（事实 #7），
两处各写一份迟早漂移——漏清任何一项都是一个「以为切干净了、其实旧会话的授权/插话/goal
还在驱动新会话」类 bug。

### `FileSessionRepository.fork(String sourceId)`

```java
/** 分叉：复制 sourceId 的已净化事件到新会话 id，返回新 id；源不存在或事件为空返回 Optional.empty()。 */
public Optional<String> fork(String sourceId)
```

实现：`findEvents(sourceId, 全量 filter)` → 空则 empty → `SessionIds.newId()` →
`save(Session.builder().id(newId)...)` + `replaceEvents(newId, events)` → 返回新 id。
新会话元数据：**复制源会话的 `userId` 与 `metadata`，仅换 id 与 `createdAt`（当前时刻，
列表排序按 mtime 本就与它无关）**。源会话的 `SessionData` 与磁盘文件**不动**。

**淘汰的备选**：直接文件级复制（`Files.copy`）——绕过 store，若源已在内存中被 fork
过的进程继续 append，两边文件会静默分叉；走 repository API 天然与惰性加载、原子写、
内存/磁盘一致性同轨。空会话拒绝 fork（复制空文件无意义，提示即可）。

### `/resume` 面板（`CodeTuiView`）

本地选择器四件套 + 后台摘要，全部对齐既有面板纪律：

| 方面 | 定案 |
|---|---|
| 状态字段 | `pickingSessions` / `sessionIndex` / `sessionPendingDelete`（d 确认态）/ `sessionSummaries`（volatile 快照，后台线程**整体替换**） |
| 闸门 | `/resume` 提交时 `!state.isBusy()` 才开面板；**Enter/f 执行时再复检一次**（见恢复序列步骤 0） |
| 打开 | `SessionCatalog.scan`（同步瞬时）→ 立即显示；同时起 **daemon 线程**逐文件 `firstUserDigest` → 完成后 volatile 整体替换 + VIEW dirty（模式同 MCP `connectInBackground`：不 join、秒开、迟到结果作废检查——面板已关则丢弃刷新） |
| 列表行 | `MM-dd HH:mm · <摘要或「（加载中…）/（无法解析）/（无对话）」>`，时间从 id 前 15 字符解析 UTC → 本地时区；当前会话行首 `★` 且高亮 |
| 键位 | `↑↓` 移动 · `Enter` 恢复 · `f` 分叉选中项 · `d` 删除（两段式）· `Esc` 关闭；面板态键处理器**始终 HANDLED**；空列表防除零（同 `/tasks`） |
| 排序纪律 | `COMMANDS` 表现状非字典序；新增条目只受**前缀包含**约束（`/skill`⊂`/skills` 先例），`/resume` 与 `/reload` 互非前缀、无排序约束 |

**恢复序列**（Enter，严格对齐 `/clear` 完整模板，顺序不可调换）：

```
0. 复检 !state.isBusy()           高危竞态防护：面板「打开」到「按 Enter」之间，goal 自动轮与
                                 排队消息出队都能【无用户按键地】自发启动回合（GoalConcurrency
                                 ScenariosTest 实证）；此时切 sessionId 正是 implementation-map §7
                                 警告的 volatile 竞态。忙则 notice 拒绝、面板保持
1. 验证目标存在                   onSubmit.sessionExists(id) 为 false（文件不存在/损坏）→
                                 提示「会话不存在或已损坏」，到此为止，什么都不切（防半切状态）
2. killAllBackgroundTasks()      排在验证【之后】：验证都没过、会话都没换，凭什么杀任务
                                 （对齐 /clear 注释 CodeTuiView.java:2480 的原则）
3. switchContext(id)             换 sessionId + 5 项会话级清理
4. state.resetForNewSession()    复位面板/排队/提示（同 /clear）
5. outputQueue.clear() + printer.resetMarkdown() + lastShownModel="" + pendingSkill=null
                                 后两项同 /clear：恢复的会话首回合重打 ⚙ 行；
                                 未发送的技能挂载不得注入【别的】会话
6. 清屏（runner 非空时）          同 /clear (B)：ScreenCleaner.clear 成功 → scrollTail.clear() +
                                 resetTailDedup()（防 resize 重放复活旧会话画面）+ printer.welcome(...)
7. replayHistory(sessionMessages(id))  回放 + todo 重建 + goal 失效提示（空历史自然 no-op）。
                                 注：其 INFO 行文案「已恢复上次会话」对任意目标语义不准，
                                 实现期给 replayHistory 加可选 title 参数（默认值不变，调用方零改动）
```

**分叉序列**（面板 `f` 与 `/fork` 共用 `forkSession(id)`）：`onSubmit.forkSession(id)` →
empty 则提示拒绝；成功则走恢复序列 0、2-7（目标 id 是 fork 返回的新 id，天然存在，
跳过第 1 步），`/fork` 命令的源是 `onSubmit.currentSessionId()`。提示行说明
「已分叉自 <原 id 前 8 位>，原会话保留」。

**删除**（`d`，两段式确认同 `/permissions`）：

- 当前会话（★ 行）不可删：`d` 对它只提示「不能删除当前会话」——删掉当前 id 后下一回合
  `SessionMemoryAdvisor` 会按 id 重建空文件，行为诡异且用户不可解；
- 确认后 `repository.delete(id)`；删除结果**如实回报**：deleteFile 吞掉的 IO 失败
  （文件仍在）不能装成功，删除后复查文件存在性，失败则保留列表项并提示。

### `/fork` 斜杠命令

`/fork` → 闸门 `!isBusy()` → `forkSession(onSubmit.currentSessionId())`。帮助文本补一行。

### `--resume <id>` 启动参数（`CodeTuiApplication`）

| 方面 | 定案 |
|---|---|
| 写法 | `--resume abc123` 与 `--resume=abc123` 两种；缺值判定口径：下一个参数**以 `-` 开头**即视为选项、当缺值处理（WARN）——比 `--permission-mode` 先例的 `--` 前缀更宽，否则 `--resume -c` 会把 `-c` 当 id 值、随后才被冲突分支兜住，路径反直觉 |
| 匹配 | 在 `scan(sessionsDir)` 得到的**合法 stem 集合**内做前缀匹配；精确命中优先；用户输入不参与拼路径（安全） |
| 零命中 | `System.err` 报错 + 列出最近 10 个会话（时间 + id）→ 退出码 1。此时**不进 TUI**（意图明确失败，进去也是新会话，更具误导性） |
| 多命中 | 报错 + 列出全部命中项 → 退出码 1（让用户补长前缀，不猜） |
| 与 `-c` 冲突 | 同时给出 → 报错退出（两个都是「恢复」，猜一个比拒绝危险） |
| 成功 | 走既有 `resumed` 路径（`replayHistory`），帮助文本 `-c` 行旁补一句 |

## 测试策略（先红后绿，逐任务）

| 层 | 用例要点 |
|---|---|
| `SessionCatalogTest` | scan：mtime 降序 / 空·不存在目录返回空 / 忽略非 `.json`；firstUserDigest：首条 USER 即停（构造大文件验证不读全量——用超长后续 events + 读取位置断言或时间上限）、无 USER → null、损坏 JSON → null、`<skill_instruction>`/`<file_reference>`/interjection 剥离、80 字符截断、多行折叠 |
| `UserTextStripperTest` | 搬移后三个剥离函数行为与 `HistoryReplay` 原测试一致（纯搬家零行为变化的守护） |
| `CodingAgentSwitchContextTest`（新建类） | `switchContext(id)`：sessionId 换成目标 + 6 个切换动作**全部**发生。注意：现有 `CodingAgentClearContextTest` 只覆盖 6 个动作中的 2 个（sessionId、todoReminder），**不能只镜像它**——须为其余 4 个依赖（accumulator/PermissionEngine/Interjections/GoalManager/TodoStaleReminder）装配可观察实例逐一断言；`clearContext()` 重构后原测试不动即验证 |
| `FileSessionRepositoryTest` | fork：事件逐条相等、源文件字节不动、空会话 → empty、fork 两次得两个不同新 id |
| 启动解析单测 | 精确 / 唯一前缀 / 多命中 / 零命中 / `--resume=x` / 缺值 / 与 `-c` 冲突（解析逻辑提为可单测的纯函数） |
| 面板按键 | 对齐 `pickingTasksForTest`/`feedKeyForTest` 先例：恢复/分叉/删除确认流、当前会话不可删、空列表、闸门拒绝**两档**（提交时忙不开面板；面板开着、按 Enter 时变忙——步骤 0 复检——拒绝且面板保持） |

## 影响面

- 改动文件：`FileSessionRepository`（+fork）、`CodingAgent`（switchContext 重构 + seam 方法落地）、
  `SubmitHandler`（+6 个 default 会话门面方法）、`CodeTuiView`（面板 + `/fork` + `/resume` 命令 +
  `ConversationState.replayHistory` 可选 title 参数）、`CodeTuiApplication`（参数解析 + 帮助）、
  `HistoryReplay`（剥离函数搬移委托）；新增 `SessionCatalog`、`UserTextStripper`。
- 文档：`implementation-map.md` §7 补「会话管理」小节；README 命令表补 `/resume` `/fork` 与 `--resume`。
- 不动：会话文件格式、`SessionRepository` 接口、advisor 链、权限层。

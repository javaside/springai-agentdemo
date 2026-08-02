# code-tui 权限管理（permission mode）设计

> 现状：code-tui **没有任何强制边界**——`AgentToolsSecurityTest` 已把这一现实钉成测试。
> 本期从 0 到 1 引入权限层：执行前拦截工具调用，按「模式 + 规则 + 内置危险检查」判定放行 / 拒绝 / 人工审批。
> 定位是**防模型手滑的护栏**，不是防 prompt injection 的安全边界。

## 目标与范围

- **目标**：危险动作在执行前停下来给人看一眼；常用动作能被规则一次性放行不再打扰；
  提供 `PLAN` 模式让模型先调查再产出计划、经人批准后才动手。
- **非目标**（明确不做，避免范围蔓延）：
  - 不做进程/文件系统沙箱（工具一旦获批仍能写 root 之外——这是刻意保留的现实）
  - 不做 Claude Code 的 `auto` 分类器模式（需要额外 LLM 调用逐动作判定，成本与复杂度都不匹配当前阶段）
  - 不做企业级 managed policy 层
  - 不做 AgentScope 那种可序列化的暂停/恢复协议（code-tui 是单进程 TUI，进程内阻塞握手足够）

## 参考对象对比结论

| 维度 | Claude Code | AgentScope Java | code-tui 取舍 |
|---|---|---|---|
| 模式 | `default`/`acceptEdits`/`plan`/`auto`/`dontAsk`/`bypassPermissions` | `DEFAULT`/`ACCEPT_EDITS`/`EXPLORE`/`BYPASS`/`DONT_ASK` | 取 4 个：`DEFAULT`/`ACCEPT_EDITS`/`PLAN`/`BYPASS`。不做 `auto`（无分类器）、不做 `DONT_ASK`（TUI 无无人值守场景） |
| 决策顺序 | 规则优先，仅 protected paths 提到规则之前 | deny → ask → **工具自审** → allow → 模式 | **取 AgentScope**：内置危险检查排在 allow 之前，不可被规则绕过 |
| 规则表达 | 字符串 DSL `Bash(npm test:*)`，四层 settings.json | `PermissionRule` record，匹配交给工具 `matchRule()` | 取 CC 的 DSL（对用户友好、可手写），两层配置文件 |
| 审批机制 | 进程内同步弹窗阻塞 | agent 暂停返回 `Msg`，`ConfirmResult` 喂回恢复 | 取 CC 的进程内阻塞（复用已验证的 `UserQuestionBridge` 模式） |
| 「别再问」 | session allow 规则 | `suggestedRules` 挂 `ToolUseBlock` | 两者同构，取「会话内」+「永久落盘」双粒度 |
| 底线保护 | 硬编码 protected paths | `ToolDangerousPathConstants` + `@Tool` 注解声明 | 取硬编码清单（本项目工具是第三方对象，加不了注解） |
| 全拒后行为 | 模型收到原因自寻替代 | 默认继续下一轮，需 middleware 才停 | 取 CC：返回拒绝串，回合继续 |

一句话：**Claude Code 是产品化的纵深防御，AgentScope 是框架化的可嵌入权限内核**。
code-tui 取 CC 的交互与规则表达 + AgentScope 的「内置检查不可绕过」决策顺序。

## 前提事实（已核实）

| 事实 | 证据 |
|---|---|
| 工具注册名：`Read` / `Write` / `Edit` / `Bash` / `BashOutput` / `KillShell` / `Grep` / `Glob` | `javap -v` 读 `@Tool(name=...)` |
| 入参名：`Read/Write/Edit` 用 `filePath`；`Bash` 用 `command`；`BashOutput`/`KillShell` 用 `bash_id` | `javap -v` 的 `MethodParameters` |
| 装饰链统一装配点在 `AgentTools.build` 的装饰循环、`buildMemoryTools`、`McpRegistry` 三处 | 源码 |
| `ConversationState.pendingAsk` 是**单个** `volatile` 字段，`drain()` 靠身份比较进模态 | `ConversationState:89`、`CodeTuiView:215` |
| 阻塞握手模式已生产验证：`ArrayBlockingQueue(1)` + `CANCEL` 哨兵 + 首个信号胜出 | `UserQuestionBridge` |
| 回合取消必须走 `cancelTurnFor`（`dispose` + `doOnCancel` 回滚），否则残留悬空 `tool_calls` 下轮 400 | `CodeTuiView:1102` + 记忆 `cancel-tool-turn-leaves-dangling-toolcalls` |
| 两层配置合并 + 降级契约已有成熟范式 | `McpConfigLoader` |
| 子 agent 已有静态工具裁剪 `allowTools`/`denyTools` | `SubagentSpec` / `SubagentRunner.filterTools` |
| 本应用当前零强制边界（刻意不设 `allowedDirectory`） | `AgentToolsSecurityTest` |

## 设计定案

### 1. 拦截点与装饰链位置

```
PermissionCallback( ToolEventCallback( MediaExternalizingCallback( 真实工具 ) ) )
```

**权限拦截器在最外层**。若放在 `ToolEventCallback` 内层，被拒绝的调用会先发 `onToolStarted`
在 TUI 显示成「工具开始运行」再变失败，且审批等待期间状态栏一直显示「工具运行中」。
放最外层后，未获批准的调用根本不产生工具事件，改由独立的权限事件驱动 UI。

`turnId` / `taskId` 从 `ToolContext` 取（与 `ToolEventCallback.extractTurnId` 同源）。
**不能读 ThreadLocal**——此时还没进 `ToolEventCallback`，ThreadLocal 尚未压入。

三处装配点共用同一个 `PermissionEngine` 实例：`AgentTools.build` 装饰循环、
`AgentTools.buildMemoryTools`、`McpRegistry` 的 MCP 工具装饰。

**被拒绝时返回错误字符串，不抛异常**：

```
Permission denied: <原因>。若确有必要，请说明理由或换一种做法。
```

抛异常会顺着 Reactor 流炸掉整个回合，与护栏定位相悖；返回字符串让模型能自寻替代方案。

`Task` / `ParallelTasks` 工具本身不拦（委派动作无害），拦的是子 agent 内部那份
`decoratedList` 里的工具——它们已过同一层装饰，天然继承。

### 2. 决策引擎

```java
public enum PermissionBehavior { ALLOW, DENY, ASK, PASSTHROUGH }

/** 一条规则。scope 决定「允许，且别再问」写到哪：SESSION=内存、PROJECT/USER=落盘文件。 */
public record PermissionRule(String toolName,       // 如 "Bash"；"*" 匹配任意工具
                             String pattern,        // 内容模式，见 §6 语法；null = 匹配该工具全部调用
                             PermissionBehavior behavior,
                             RuleScope scope) {}    // SESSION | PROJECT | USER

public record PermissionDecision(PermissionBehavior behavior, String reason, PermissionRule suggested) {}

public record PermissionRequest(long turnId, String taskId, String toolName,
                                String target,     // 判定目标（路径或命令），面板显示 + 规则匹配
                                String rawInput,   // 原始入参 JSON，面板可展开
                                PermissionRule suggested) {}
```

`PermissionEngine.decide(toolName, inputJson, ctx)` 的判定顺序：

```
1. deny 规则                 ← 最高，任何模式下生效（含 BYPASS）
2. 内置危险检查              ← 不可被 allow 覆盖
3. 工具自审（PermissionAware）← 可选钩子，PASSTHROUGH 则继续
4. ask 规则
5. allow 规则
6. 模式默认
7. 兜底 → ASK（并生成建议规则）
```

每一步是独立纯函数，可单独单测。

第 3 步的 `PermissionAware` 是给**自写工具**（`BochaWebSearchTool`、`TodoWriteToolAdapter`、
`SubagentTool`）的可选扩展点；第三方库工具实现不了它，走第 6 步的登记表。

#### 可变状态归属（避免实现时各自猜）

`PermissionEngine` 是**有状态的单例**，由 `AgentTools.build` 构造一次、三处装配点共用。它持有：

| 状态 | 生命周期 | 谁改 |
|---|---|---|
| 当前 `PermissionMode` | 会话级，`volatile` | UI 的模式切换键、`ExitPlanMode` 批准、启动参数 |
| 落盘规则（user + project 合并结果） | 启动时加载一次；「允许，永久」时追加并回写 | `PermissionConfigLoader` / `PermissionConfigWriter` |
| 会话规则（`RuleScope.SESSION`） | 进程内存，`CopyOnWriteArrayList`；`/clear` 开新会话时清空 | 审批面板选「本会话不再问」 |

模式由 engine 自己持有（而非 UI 持有再传入）——因为 `ExitPlanMode` 的批准动作发生在工具线程，
需要就地改模式；UI 只是另一个改写方。读写都走 `volatile`，无复合操作，不需要锁。

回写落盘规则复用 `McpConfigWriter` 的既有范式（读—改—写整个 JSON，保留未知字段）。

### 3. 工具登记表

| 工具 | 类别 | 判定目标取自 | 默认行为 |
|---|---|---|---|
| `Read` / `Grep` / `Glob` | `READ_ONLY` | `filePath` / `path` | 放行；仅命中危险路径读（如 `~/.ssh/id_rsa`）时 ASK |
| `Write` / `Edit` | `FILE_WRITE` | `filePath` | `ACCEPT_EDITS` 下工作区内放行，否则 ASK |
| `Bash` | `COMMAND` | `command` | 走命令拆分判定 |
| `BashOutput` / `KillShell` | `READ_ONLY` | `bash_id` | 放行（只查/杀自己起的进程） |
| `WebFetch` / `WebSearch` | `NETWORK_READ` | url / query | 放行（只读网络） |
| `TodoWrite` / `Skill` / `AskUserQuestionTool` / `ExitPlanMode` | `INTERNAL` | — | 恒放行（无外部副作用） |
| 记忆工具 | `FILE_WRITE` | 固定在 `.codetui/memory` 内 | 放行 |
| **未登记（含全部 MCP 工具）** | `UNKNOWN` | 整个入参 JSON | **ASK** |

MCP 工具兜底是 ASK 而非放行——保守默认。批一次后可选「永久允许该 MCP 工具」写进规则文件。

**登记表完整性由测试保证**：对着运行时工具集全量比对，存在但未登记的工具直接让测试失败。
比对必须用 `getToolDefinition().name()`，不是 Java 方法名（见记忆 `tool-registered-name-is-annotation-not-method`）。

### 4. 模式

`PermissionMode` 枚举 4 个值：

| 模式 | 行为 |
|---|---|
| `DEFAULT` | 只读放行，其余按规则；无规则则 ASK |
| `ACCEPT_EDITS` | 额外放行工作目录内的 `Write`/`Edit`，以及 `mkdir`/`touch`/`mv`/`cp` 类文件系统命令 |
| `PLAN` | 只读放行；`FILE_WRITE` / 非只读 `COMMAND` / `UNKNOWN` 一律 **DENY**（不是 ASK） |
| `BYPASS` | 全放行，但 deny 规则与内置危险检查仍生效；仅 `--dangerously-skip-permissions` 启动可进 |

切换：`Shift+Tab` 循环 `DEFAULT → ACCEPT_EDITS → PLAN`；`BYPASS` 只在启动开启时才进循环。
另提供 `/permissions` 命令：**期 1 是只读版**（把当前模式与生效规则打进 scrollback，
照 `/skills` 的写法），期 3 升级为可就地**删**规则的交互面板（新增仍走审批面板的「允许，永久」）。

> **技术未知**：`Shift+Tab` 能否被终端区分需 pty 实机验证（同 `Shift+Enter` 的老问题）。
> 若不能区分，回退为 `/permissions` 命令 + `Ctrl+P` 循环。**这是期 0 的唯一任务。**
> 另注意 TamboUI 默认 `quit` 绑定含裸 `q`/`Q`，本项目已重绑为只剩 Ctrl+C，新键位不得破坏该约束
> （见记忆 `tamboui-default-quit-binds-bare-q`）。

### 5. 命令拆分判定

`BashCommandSplitter` 的判定步骤：

1. 按 `&&` `||` `;` `|` 拆段，逐段独立判定
2. 每段取首个词作命令名，查内置只读白名单：
   `ls` `cat` `head` `tail` `grep` `rg` `find` `wc` `pwd` `echo` `which` `file` `stat`
   `git status|diff|log|show|branch|remote -v` `mvn -v` `java -version` 等
3. 白名单命中 → ALLOW；否则匹配 `Bash(前缀:*)` 规则
4. **拆不动就问**：遇命令替换 `$(...)`、反引号、进程替换 `<(...)`、嵌套引号内含分隔符——
   一律直接 ASK，绝不猜
5. 任一段判 ASK/DENY，整条即 ASK/DENY

第 4 条是本节的安全底线：解析器只要不确定就退回人工，宁可烦一次也不放行未知语义。

### 6. 规则文件

`permissions.json`，两层：`~/.codetui/permissions.json`（用户）+ `<root>/.codetui/permissions.json`（项目）。

```json
{
  "defaultMode": "DEFAULT",
  "allow": ["Bash(mvn -pl springai-code-tui test:*)", "Read(*)"],
  "ask":   ["Bash(git push:*)"],
  "deny":  ["Bash(rm -rf /:*)", "Write(/etc/**)"]
}
```

**语法 `工具名(内容模式)`**，三种模式形态，按以下顺序判别：

| 形态 | 含义 | 例 |
|---|---|---|
| `工具名(*)` 或裸 `工具名` | 匹配该工具的**全部**调用 | `Read(*)`、`TodoWrite` |
| `工具名(字面量:*)` | **前缀匹配**：判定目标以 `:` 之前的字面量开头即命中。`:` 之前的内容按原样比较，不再解释任何通配符 | `Bash(mvn -pl springai-code-tui test:*)` 命中 `mvn -pl springai-code-tui test -Dtest=Foo` |
| `工具名(glob)` | **路径 glob**：仅用于 `FILE_WRITE`/`READ_ONLY` 类工具，`*` 匹配单层、`**` 匹配递归 | `Write(/etc/**)`、`Read(src/*.java)` |

判别规则：内容模式以 `:*` 结尾 → 前缀匹配；否则若工具类别是路径类 → glob；否则 → 整串相等。
这样 `Bash(rm -rf /:*)` 明确是「以 `rm -rf /` 开头的命令」，不会被误读成路径 glob。

- 优先级 **deny > ask > allow**
- 两层**合并而非覆盖**——deny 只增不减，项目层不能削弱用户层的禁令
- `defaultMode` 项目层可覆盖用户层
- 降级契约沿用 `McpConfigLoader`：文件缺失 / JSON 非法 / 字段缺失 → 记 WARN、跳过、**绝不抛异常**

### 7. 内置不可绕过检查

排在 allow 规则之前，任何 allow 规则盖不住；**BYPASS 模式下也照样触发**。
命中后强制 **ASK 而非 DENY**——护栏定位，人确认了就该能做。

- **写**这些目录：`.git/` `.ssh/` `.aws/` `.kube/` `.gnupg/`
- **写**这些文件：`.gitconfig` `.gitmodules` `.zshrc` `.bashrc` `.zprofile` `.profile`
  `.npmrc` `.m2/settings.xml` `.gradle/gradle.properties`
- **写** `<root>/.codetui/`（agent 修改自己的权限配置与会话记录）
- **读**这些：`.ssh/id_*` `.aws/credentials` `.gnupg/*`
- 命令：`rm -rf /`、`rm -rf ~`、`rm -rf $VAR`（变量目标无法核实即拒绝猜测）

### 8. 审批面板与模态队列

**本期唯一的既有代码重构**：`ConversationState.pendingAsk` 从单个 `volatile` 字段
改为统一的**模态请求队列** `Deque<ModalRequest>`（`ModalRequest` 是
`AskRequest | PermissionRequest | PlanApprovalRequest` 的密封接口）。

理由：三类模态竞争同一个 UI 焦点，各搞一套必然互相覆盖。
`drain()` 的进入模态逻辑改成「队首非空且不同于 `activeModal` 就进入」。

队列上限 8，超出直接 DENY 并附原因，防失控回合塞爆队列。

面板五个选项：

```
  ⚠ 需要授权：Bash
     mvn -pl springai-code-tui test && git push origin main
     ↑ 第 2 段 `git push origin main` 未获授权

   ❯ 1. 允许一次
     2. 允许，本会话不再问          → 加一条内存 session 规则
     3. 允许，永久                  → 写 <root>/.codetui/permissions.json
     4. 拒绝，让模型换个做法
     5. 拒绝并中断本回合
```

`Write` / `Edit` 复用现成 `DiffRenderer` 在面板里展示改动。

**阻塞握手照抄 `UserQuestionBridge`**：`ArrayBlockingQueue(1)` + `CANCEL` 哨兵 + 首个信号胜出。

**拒绝 ≠ 取消回合**（关键区别）：

- 选 4「拒绝」→ 喂回 DENY，返回拒绝字符串，**回合继续**
- 选 5 或 Esc「中断」→ 走既有 `cancelTurnFor`，`dispose` + `doOnCancel` 回滚会话

状态栏等待授权时显示 `⏸ 等待授权 (Bash) · ↑↓ 选择 · Enter 确认 · Esc 中断`。
`busy()` 必须把「等待审批」计入，否则排队消息会在审批模态期间被错误出队。

### 9. plan 工作流

新增自写工具 `ExitPlanMode`（注册名同名，入参 `plan`: markdown 文本）：

- 非 `PLAN` 模式下调用 → 返回 `当前不在计划模式，无需提交计划。`
- `PLAN` 模式下调用 → 走 `PlanApprovalBridge`（与 `UserQuestionBridge` 同款阻塞桥）

计划正文用现成 `MarkdownRenderer` **渲染进 scrollback**（不挤在面板里），面板只放三个选项：

| 选项 | 效果 | 工具返回给模型 |
|---|---|---|
| 批准，自动接受编辑 | 切 `ACCEPT_EDITS` | `计划已批准，开始执行。` |
| 批准，逐个确认 | 切 `DEFAULT` | `计划已批准，开始执行。` |
| 继续完善计划 | 留在 `PLAN` | `用户希望继续完善计划：<反馈文本>` |

**系统提示注入**：`AgentTools` 模板加 `{PERMISSION_MODE}` 段，`CodingAgent.submit` 每回合按当前模式渲染。
`PLAN` 模式下注入「你处于计划模式，只能读取和探索，不能修改任何文件或执行有副作用的命令；
调查清楚后调用 `ExitPlanMode` 提交计划等待批准」，其余模式为空串。

> 该段**作为 param 值注入**、不拼进模板字符串——正文里的花括号会炸 SpringTemplate
> （见记忆 `agentutils-automemory-integration`、`agents-md-project-instructions`）。

**启动参数**：`--permission-mode plan|default|acceptEdits`，以及 `--dangerously-skip-permissions` 进 `BYPASS`。

### 10. 子 agent 与并发

**继承而非独立**：子 agent 用主会话的模式与规则，`SubagentSpec` 不加字段
（对齐 Claude Code：子 agent frontmatter 的 `permissionMode` 被忽略）。

**与现有工具裁剪正交、顺序明确**：先按 `allowTools`/`denyTools` 裁剪（工具对子 agent 根本不存在），
再走权限判定。

**ParallelTasks 并发审批**：多个子 agent 线程同时判出 ASK → 各自往模态队列 `offer` 一个
`PermissionRequest`，然后各自阻塞在**自己的** `ArrayBlockingQueue(1)` 上。UI 逐个弹。
面板标题带来源：`⚠ 需要授权：Bash（来自子 agent explore）`。

**两个必须显式处理的活性点**（写进类文档，照 `UserQuestionBridge` 的「活性依赖」纪律）：

1. **回合取消时清空队列**：`state.cancelCurrent()` 遍历模态队列，给每个 pending 请求投 `CANCEL` 哨兵。
   漏了这步，被取消回合里的子 agent 线程**永久 park**。
2. **迟到请求直接拒**：`turnId != activeTurnId` 的审批请求不弹面板，直接返回 DENY。

## 测试策略

| 层 | 内容 | 对应的坑 |
|---|---|---|
| 纯函数单测 | `BashCommandSplitter` 拆分与「拆不动就问」；`PermissionEngine` **表驱动**（4 模式 × 7 类别 × 4 规则状态）；规则匹配；配置加载与两层合并 + 降级契约 | 决策顺序易写错且不易察觉 |
| 登记表完整性 | 对运行时工具集全量比对，未登记即失败 | 工具注册名取 `@Tool` 注解非方法名 |
| 拦截器单测 | ALLOW 透传 / DENY **返回串不抛异常** / ASK 阻塞到喂回 | 抛异常会炸掉整个回合 |
| 并发与活性 | 多线程 FIFO；**取消后断言无线程 park**（`CountDownLatch.await(2, SECONDS)` 必须 true）；队列满第 9 个返回 DENY | 漏唤醒 = 永久 park |
| UI 单测 | 模态队列进出、迟到 turnId 过滤、`renderForTest()` **渲染冒烟** | `scope(cond, panelChildren())` 每帧 eager 求值，面板方法首行必须判空 |
| pty 实机冒烟 | 模式循环、审批面板、plan 审批面板 | 须 `ioctl TIOCSWINSZ` + `TERM=xterm-256color`，否则全空白；改完重新 `package` |
| 安全现实回归 | **改写 `AgentToolsSecurityTest`** | 它现在钉「零边界」，本期后须改为钉「引擎层有护栏、工具层仍无沙箱」，否则变成误导后人的谎言 |

**验证命令**：`mvn -pl springai-code-tui test`——**必须模块作用域**，整仓 `-Dtest` 会被 3 个空模块打挂，
且不许用 `failIfNoSpecifiedTests=false` 盖问题（见记忆 `validate-command-must-be-module-scoped`）。

## 分期交付

| 期 | 内容 | 状态 | 说明 |
|---|---|---|---|
| **期 0** | pty 验证 `Shift+Tab` 是否可区分 | ✅ 2026-08-01 | 唯一技术未知，结论决定期 1 的交互设计，必须先做 |
| **期 1** | `PermissionEngine` + 登记表 + `PermissionCallback` + `permissions.json` 两层加载与回写 + `DEFAULT`/`ACCEPT_EDITS`/`BYPASS` 三模式 + 模式切换键 + 只读版 `/permissions` + 模态队列重构 + 审批面板 | ✅ 2026-08-01 | 大头。子 agent 与 MCP 因共用装饰链自动纳管；并发队列与取消唤醒必须这期做对，否则子 agent 一跑就 park |
| **期 2** | `PLAN` 模式 + `ExitPlanMode` + 计划审批面板 + `{PERMISSION_MODE}` 提示段 + 启动参数 | ✅ 2026-08-01 | 三档循环、`--permission-mode`、计划审批面板与 pty 实机冒烟均已落地。实施中改了一处设计：`PLAN` 下内置危险检查给 **DENY 而非 ASK**（否则只有最危险的那批操作能被当场批准，结论倒置），只读的「读密钥」仍是 ASK |
| **期 3** | 补四个判定漏洞（大小写 / 符号链接 / `ACCEPT_EDITS` 命令段不判工作区 / 过宽 root）+ `/permissions` 改可**删**规则的交互面板 | ✅ 2026-08-02 | **重心与本表原定的不同**：原写「可增删规则 + 措辞打磨 + 危险清单扩充」，实际取舍见 `2026-08-02-permission-matching-holes-design.md`——危险清单扩充判为 YAGNI（黑名单没有尽头，逐条加只制造「越来越安全」的错觉）；面板内**新增/编辑**规则不做（需 DSL 校验与错误提示，工作量接近再加半期），新增仍走审批面板 |

期 2 实施中发现并当期补掉的一条（原设计漏考虑）：`PLAN` 下子 agent 与主 agent 共用引擎，
写操作照样被 DENY，但它此前既拿不到 `{PERMISSION_MODE}` 提示段、也没有 `ExitPlanMode`
（该工具只装配给主 agent，不进 `decoratedList`），于是会不知情地反复撞墙。

**已修**（`e0df008`）：给子 agent 注入一段**专属**提示，经 `Supplier` 每次派发现取现算
（`SubagentRunner` 是建一次长期存活的，存值会让 `Shift+Tab` 切档后的提示过期）。

> **刻意不与主 agent 版统一成一份文案**：主 agent 版结尾指向 `ExitPlanMode`，而子 agent
> 的工具集里根本没有它。照抄等于指一条走不通的路——把「不知道为什么被拒」换成
> 「知道了、照做了、还是失败」，比不给提示更糟。子 agent 版说的是另一件事：
> 你在只读调查阶段，把发现报告回主 agent 就是你的交付。

**曾考虑并否决的方案**：`PLAN` 下干脆禁掉 `Task`/`ParallelTasks`。否决理由——派 `explore`
子 agent 做只读调查恰恰是计划模式最该干的事，禁掉等于把这一档最有用的能力一起砍了。

**文档拆分**：本 spec 覆盖全部四期设计，但实施计划只详细展开期 0 + 期 1。
期 1 的模态队列重构结果会影响期 2 的面板设计，现在写期 2 的详细步骤是空中楼阁；期 2、期 3 到时各自出计划。

## 风险与已知取舍

| 风险 | 应对 |
|---|---|
| `Shift+Tab` 终端不可区分 | 期 0 先验证，回退 `/permissions` + `Ctrl+P` |
| 模态队列重构触及已验证的取消回滚路径 | 保持 `cancelTurnFor` 语义不变，只改「谁进模态」的选取逻辑；并发活性有专门测试兜 |
| 审批疲劳导致用户一律选「永久允许」 | 内置危险检查不可被 allow 覆盖，是这种情况下的最后一道底线 |
| 命令拆分器判不准 | 「拆不动就问」，宁烦不漏；未知语义永不放行 |
| MCP 工具默认 ASK 影响体验 | 批一次即可永久放行该工具；这是保守默认的必要代价 |
| 一旦获批，工具仍能写 root 之外 | **刻意保留的现实**，由改写后的 `AgentToolsSecurityTest` 明确钉住，不制造「有沙箱」的假象 |

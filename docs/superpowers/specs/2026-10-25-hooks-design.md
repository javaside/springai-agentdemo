# Hooks 生命周期钩子设计

> 对标 Claude Code hooks：用户不改 Java 就能用 shell 命令扩展行为（保存后 format、
> 编辑后 lint、黑名单拦截、回合结束通知）。挂载点全部现成——工具装饰链、
> `handleComplete`、`assemble`；本设计新增 `agent.hooks` 包（配置/信任/执行引擎）
> 与 `/hooks` 面板。

## 目标与范围

- **目标**：
  1. 四事件：`PreToolUse`（exit 2 = 拦截）、`PostToolUse`（stdout 追加进工具结果）、
     `Stop`（回合结束通知，不阻断）、`SessionStart`（启动通知，不阻断）；
  2. 配置两层：`~/.codetui/hooks.json`（用户层）+ `<root>/.codetui/hooks.json`（项目层），
     同事件 concat、全部执行；
  3. 项目层信任模型：hooks = 可执行任意命令，clone 别人仓库不该自动执行——未信任则
     项目层钩子不运行，`/hooks` 面板查看 + `t` 键信任；
  4. I/O 契约：stdin 喂 JSON payload；Pre exit 2 的 stderr 回给模型；Post 的 stdout
     （截 2000 字）追加进工具结果。
- **非目标**：
  - `UserPromptSubmit` / `PreCompact` / `SubagentStop` 等其余事件——后续版本；
  - `Stop` 钩子的阻断（exit 2 强制模型续跑）——引入自动轮风险，v1 通知型；
  - **MCP 工具的钩子覆盖**——MCP 工具的装饰链在 `McpRegistry.decorate` 另行创建，
    接线需改 `init` 签名族；v1 只覆盖 `AgentTools.build` 装配的内置/技能/任务工具
    （黑名单场景对 MCP 价值低——权限层已对 UNKNOWN 类别兜底 ASK/DENY）；
  - 钩子的热重载（改 hooks.json 需重启；`/reload` 扩展留后续）；
  - Windows shell 兼容特化（`ProcessBuilder` 直接执行 command，sh 语法由用户自管）。

## 前提事实（2026-10-25 核查源码）

| # | 事实 | 证据 |
|---|---|---|
| 1 | 工具装饰链为 `PermissionCallback( ToolEventCallback( MediaExternalizingCallback( 真实工具 ) ) )`，装饰循环在 `AgentTools.build`；Task/ParallelTasks/Skill 走同构链 | `AgentTools.java:551-562,593-604` |
| 2 | `ToolEventCallback` 已有「可选第 3 参」先例（`TodoStaleReminder`，null=停用）；**新增第 4 可选参不影响既有构造点**（`McpRegistry.decorate` 等用 2/3 参构造） | `ToolEventCallback.java:41-49` |
| 3 | 工具在**工具线程同步执行**（`call()` 返回前阻塞）——钩子同线程同步跑即可干预，天然串行 | `ToolEventCallback.java:56-86` |
| 4 | `turnId`/`taskId` 经 `ToolContext` 传入（`ToolEventCallback.extractTurnId/extractTaskId`）——payload 可拿到 | `ToolEventCallback.java:57-58,88-93` |
| 5 | 用户层文件先例：`Path.of(System.getProperty("user.home")).resolve(".codetui")/…`（mcp.json 同款） | `McpConfigLoader.java:68-74` |
| 6 | 坏配置降级先例：文件缺失/JSON 非法/缺字段 → WARN 跳过**绝不抛**（McpConfigLoader 契约） | `McpConfigLoader.java:20-24` |
| 7 | Jackson 3 用法先例：`tools.jackson.databind.ObjectMapper` 静态实例（FileSessionRepository.MAPPER） | `FileSessionRepository.java:51` |
| 8 | 两段式 bind 先例：`AgentTools.wireTodoReminder(rt, agent)` → 包私有 `CodingAgent.bindTodoReminder`（跨包编译不过，须经 agent 包内转接） | `AgentTools.java:1215-1217`、`CodingAgent.java:1313-1315` |
| 9 | `handleComplete` 顺序：`persistInterjection` → `purgeBlankUserEvents` → … → `onTurnComplete` 事件——Stop 钩子插在事件发出前 | `CodingAgent.java:1510-1516` |
| 10 | `assemble(...)` 6 参已在两模式共用（headless 遗产），`AgentTools.build` 现 7 参（registry/root/listener/mcp/permission/retryConfig/goalManager） | headless spec/Task 3、`AgentTools.java:418-420` |
| 11 | 本地选择器四件套成熟（`pickingTasks`：字段+scope+键分派+状态行；始终 HANDLED/判空/防除零） | implementation-map §5 |
| 12 | 内容 hash 先例：`MediaArtifactStore` 用 sha256（`MessageDigest.getInstance("SHA-256")`）做内容寻址——信任文件同法 | `MediaArtifactStore` |

## 用户已核准的口径（2026-10-25）

1. **四事件**（Pre/PostToolUse + Stop + SessionStart）；2. **Pre exit 2 拦截**（stderr 回模型）；
3. **两层 + 项目层信任**；4. **stdin JSON + 定向回传**（Post stdout 截 2000 进结果）。
设计六节（事件语义/配置信任/执行引擎/挂载接线/面板/测试）已逐节确认（2026-10-25）。

## 设计定案

### 组件总览

| 组件 | 位置 | 职责 |
|---|---|---|
| `HookDefinition` | `agent.hooks`（新包） | record：`(String matcher, String command, int timeoutSeconds, HookEvent event, HookSource source)`；matcher 空串=全部工具 |
| `HookEvent` | `agent.hooks` | enum：`PRE_TOOL_USE / POST_TOOL_USE / STOP / SESSION_START`（配置键用官方拼写 `PreToolUse` 等，loader 映射） |
| `HookSource` | `agent.hooks` | enum：`USER / PROJECT` |
| `HookConfigLoader` | `agent.hooks` | 两层加载（Jackson，坏文件 WARN 跳过）；非法正则/非正 timeout → 跳该条 WARN |
| `HookRegistry` | `agent.hooks` | 合并 + 信任判定 + `hooksFor(event, toolName)` 过滤（matcher `Pattern` 预编译缓存）；`trustProjectLayer()` 写信任文件 |
| `HookExecutor` | `agent.hooks` | `execute(HookDefinition, String payloadJson, Path root)` → `HookResult(int exitCode, String stdout, String stderr, boolean timedOut)`：ProcessBuilder、cwd=root、stdin 写 payload、waitFor(timeout) 超时 destroyForcibly、输出捕获上限 64KB |
| `ToolEventCallback` 第 4 可选参 | 既有类 | Pre/Post 挂载（null=停用，既有构造点零改动） |
| `CodingAgent.bindHooks` + `wireHooks` | 既有类/AgentTools | Stop 挂载（两段式 bind，wireTodoReminder 同款） |
| `assemble` | `CodeTuiApplication` | 建 registry（两模式）→ 跑 SessionStart → 传 build 第 8 参 |
| `/hooks` 面板 | `ui.CodeTuiView` | 四件套：列表/信任态/`t` 信任/Esc |

### 配置格式与合并

```json
{
  "hooks": {
    "PreToolUse":  [ { "matcher": "Bash|Write", "command": "./ci/guard.sh", "timeout": 30 } ],
    "PostToolUse": [ { "matcher": "Write|Edit",  "command": "npx prettier --check" } ],
    "Stop":        [ { "command": "notify-send done" } ],
    "SessionStart": [ { "command": "./scripts/env-check.sh" } ]
  }
}
```

- 字段：`command` 必填（缺 → 跳过 WARN）；`matcher` 可省（=全部工具；对 Stop/SessionStart 无意义、忽略）；`timeout` 秒可省，**默认 60 钳 [1,600]**（非法值 WARN 回退默认）。
- 未知事件键 / 未知字段：未知键 WARN 跳过，未知字段忽略（向前兼容）。
- 合并：**同事件 concat，用户层在前、项目层在后，全部按序执行**（无名字可覆盖）。
- `command` 执行方式：`ProcessBuilder("sh", "-c", command)`（用户写 shell 管道自然；Windows 由 sh 不可用时的报错走非阻断路径）。

### 信任模型

- 信任文件：`~/.codetui/hooks-trust.json`（**用户空间**——信任是本机用户的决定，不随仓库走）：
  `{ "projects": { "<项目根绝对路径>": "<sha256(项目层 hooks.json 内容)>" } }`。
- 判定：项目层 hooks.json 存在且内容 hash 与信任记录**一致** → 信任；不一致/无记录 → 未信任。
  **配置任何变更即失信任**（防「信任后被改」——hash 变了自然失配）。
- 未信任行为：项目层钩子**一律不执行**（Pre/Post/Stop/SessionStart 全部）；`assemble` 通告一行
  「⚠ 项目层 hooks.json 未信任，其钩子已跳过——/hooks 查看并信任」；`/hooks` 面板 `t` 键写入
  信任（记当前 hash）后面板内即时生效（registry 重读）。
- 用户层钩子**无需信任**（用户自己写的）。

### 执行语义

| 场景 | 语义 |
|---|---|
| PreToolUse，exit 0 | 放行（stdout 丢弃——不打扰模型） |
| PreToolUse，**exit 2** | **拦截**：不执行工具；返回给模型 `⛔ PreToolUse 钩子拦截（<command 摘要>）：<stderr（截 2000）>`；UI 走 `onToolFinished(…, false)`（⏺→✗ 带理由，诚实呈现） |
| PreToolUse，其它非零/超时/启动失败 | 非阻断：WARN 日志 + **继续执行工具**（钩子坏不该挡住干活——同工具容错白名单老账） |
| PostToolUse，exit 0 且 stdout 非空 | stdout **截 2000 字**以 `\n\n[PostToolUse 钩子输出]\n…` 追加进工具结果（`onToolFinished` 前——UI 与模型都可见） |
| PostToolUse，非零/超时/启动失败 | 非阻断：stderr 进日志 + UI 一行提示；**不追加任何内容**（失败的 lint 不该污染结果） |
| Stop / SessionStart | 通知型：exit 0 忽略输出；非零 stderr 截一行进通告通道（TUI pushInfo / headless stderr）；**不影响流程** |

- **payload（stdin JSON，UTF-8）**：`{"event":"PreToolUse","toolName":"Bash","toolInput":"{…原始入参 JSON 串…}","sessionId":"…","turnId":1,"taskId":null,"root":"/path"}`；PostToolUse 多一个 `"toolOutput":"…（截 8KB）"`；Stop/SessionStart 无 toolName/toolInput。
- 匹配不到可执行文件/命令：非阻断（同上）；**钩子异常绝不 rethrow**（装饰链纪律）。

### 挂载点接线

1. **Pre/Post（ToolEventCallback 第 4 参）**——`call()` 内顺序：

```
onToolStarted → [hooks != null] 逐个跑 Pre(匹配者)：
    任一 exit 2 → onToolFinished(false, 拒绝文本) → return 拒绝文本（不执行工具）
    （非 2 的失败：WARN，继续下一个/继续工具）
→ delegate.call → [hooks != null] 逐个跑 Post(匹配者)：
    stdout 追加进 out（在 onToolFinished 之前——UI 也可见）
→ onToolFinished(true, out)
```

2. **Stop**：`handleComplete` 中 `onTurnComplete(turnId)` 发出前同步执行（registry null 降级；失败只 WARN——**钩子不许弄坏回合收尾**）。
3. **SessionStart**：`assemble` 内建好 registry 后、`AgentTools.build` 前同步跑（通知走 `notice` 通道；超时钳制下最多拖启动 ~60s/钩子——文档写明）。
4. **装配流**：`assemble` 建 `HookRegistry.load(root)`（含信任判定与未信任通告）→ 跑 SessionStart → `AgentTools.build` 加第 8 参 `hooks` → **`AgentTools` 内全部 `ToolEventCallback` 构造点传入**（主装饰循环/Task/ParallelTasks 等——`CodingAgent.java:1310` 注释计 7 处，以代码为准逐一覆盖）→ `AgentTools.wireHooks(runtime, agent)`（`CodingAgent.bindHooks` 包私有，wireTodoReminder 同款）供 Stop。
5. **headless**：同 registry（assemble 共用）；Pre 拒绝文本照常回模型；未信任通告走 stderr（notice 通道天然分流）。

### `/hooks` 面板

- 四件套：`pickingHooks` + scope + 键分派 + 状态行；**无闸门**（只读+信任操作，同 /permissions）。
- 列表：按事件分组的钩子行（`事件 · 命令 · matcher · timeout · 来源(用户/项目) · 项目层未信任标记`）；零钩子说明从哪放文件（同 /tasks 空态）。
- 键位：`↑↓` 移动 · `t` 信任项目层（未信任时；写 hooks-trust.json + registry 重载 + notice 确认）· `Esc` 关闭。
- COMMANDS 表 + help 无涉（无新启动参数）。

## 测试策略（先红后绿）

| 层 | 用例要点 |
|---|---|
| `HookConfigLoaderTest` | 两层 concat 顺序；字段缺省（matcher/timeout）；非法正则跳条 WARN；未知事件键跳过；坏 JSON 整文件跳过不抛；timeout 钳制 |
| `HookRegistryTest` | `hooksFor` 按事件+工具名过滤（matcher 正则命中/不命中/空=全）；信任判定（无记录/一致/不一致）；`trustProjectLayer` 写入后即信任；内容变更即失信任 |
| `HookExecutorTest` | 真进程：`sh -c 'exit 0'`/`exit 2`；stdout 捕获（echo）；stdin payload 透传（`cat` 回读断言）；sleep 超时 kill（timedOut=true，1s timeout 用例）；找不到命令非阻断 |
| `ToolEventCallbackHooksTest` | 集成（stub 脚本落临时目录）：Pre exit 2 → 拒绝文本返回 + delegate **未被调用** + onToolFinished(false)；Pre 失败不挡工具；Post stdout 追加在 onToolFinished 前；hooks=null 全零行为（守护既有构造点） |
| `CodingAgentStopHookTest` | Stop 在 onTurnComplete 前同步跑；registry null 降级；钩子抛异常不影响收尾 |
| 面板测试 | 对齐 `pickingTasksForTest`：列表渲染、未信任标记、t 信任写入与 notice、Esc |
| 真机冒烟（可选，env 门控） | SessionStart 通告 + Pre 拦截一次 Bash |

## 影响面

- 新增：`agent/hooks/` 包 6 个类；改动：`ToolEventCallback`（+第 4 参）、`AgentTools.build`（+第 8 参）+ `wireHooks`、`CodingAgent`（`bindHooks` + handleComplete 一行）、`CodeTuiApplication.assemble`（建 registry/SessionStart/传参）、`CodeTuiView`（/hooks 面板 + COMMANDS 行）。
- 不动：`McpRegistry.decorate`（MCP 工具不挂钩，非目标）、权限层、装饰顺序。
- 文档：implementation-map 速查表 + §8 补「钩子」小节 + 环境变量表无涉；reference.md `/hooks` 行 + hooks.json 格式附录；README 小节；无 config.env 新 env。
- 发布：随下个版本（v1.29.0 或顺延）。

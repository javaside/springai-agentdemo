# MCP 连接改为后台 · 设计

**日期**：2026-08-05
**模块**：`springai-code-tui`
**状态**：已实现（`b7c8650` / `71ba15a`）

> **流程说明**：本次为了赶时间**跳过了「先写 spec 再实现」**，这份文档是实现之后补的。
> 内容与代码一致（逐条对照过），但它记录的是已发生的决定，不是待评审的方案。

## 问题

启动慢。实测（三轮取中位数，pty 起真进程、以 welcome banner 出现为准）：

| 配置 | 启动耗时 |
|---|---|
| 无 MCP（空 `user.home`） | **1.64s** |
| 只 `chrome-devtools`（npx stdio） | 2.46s |
| 只 `context7`（远程 HTTP） | 6.66s（抖动 5.4~9.5s） |
| 两个都开 | **7.25s** |

`McpRegistry.init` 里 `connectEnabledInParallel()` 会 `join` 所有连接 future，而它排在
`CodeTuiApplication` 创建 TUI **之前**。所以不管连几个 server，用户都得对着空屏等它们连完。

远程 HTTP server 是大头，且**快慢取决于网络**——这一项永远不可能优化到「快到可以同步等」。

## 决定

`init` 不再 `join`，立即返回；连接在后台池里跑，完成后写回 entry。

**为什么可以不等**：`CodingAgent.submit` 每回合都重新快照 `mcpRegistry.activeTools()`
（`CodingAgent.java:316`，注释原文「MCP 工具每回合快照注入」）。工具晚到几秒只影响这几秒内
发出的回合，不影响之后任何一回合。这个性质本来就在，不是为本次改动新造的。

**刻意不做**：没有「第一条消息等 MCP 连上」。那等于把阻塞从启动挪到首条消息，白改。

## 三条失效的前提

改之前 `join` 保证了「写回一定发生在任何读之前」，于是原代码可以（且注释明写）
「此时无并发访问，不加锁」。改完这句话全线不成立：

### ① 写回与读真并发

`servers()` / `activeTools()` 是 `synchronized`，而 `connectAndDiscover` 直写 entry。

**修法**：写回抽成 `publishStartupResult`，在 `this` 锁内做；**连接本身留在锁外**
（秒级阻塞，锁住会挡死面板与每回合的工具快照）。这与既有 `enable()` 的
`connectAndPublish` 是同一个形状，照抄。

### ② close 竞态 → 孤儿子进程

`close()` 收集当前 client 并关掉。一个在 close **之后**才连上的 client 不在那份名单里，
若照常写回 entry，就再没有任何人会关它——留下一个孤儿子进程。
`mcp_smoke.py` / `mcp_manage_smoke.py` 各有一条 `No orphaned MCP child process` 断言盯这件事。

**修法**：`volatile boolean closed`。

- `close()` **先置位、再收集**。顺序反过来会留下窗口：那一瞬间连上的 client 既不在名单里、
  又因为 `closed` 尚未置位而被写进了 entry。
- `publishStartupResult` 在锁内查这个位，为真就**当场关掉**刚连上的 client、不写回。
- `close()` 另外 `shutdownNow()` 启动池，否则退出要陪最慢的 server 等满超时。

### ③ 在飞期间被 `/mcp` 禁用 → 迟到写回把禁用复活

用户在连接在飞期间关掉某个 server，迟到的写回会把工具塞回去。

**修法**：同一处锁内复查 `e.enabled`，为假则丢弃并关掉 client。
**刻意不让启动连接去争 `toggleLock`**——那会让 `/mcp` 面板操作卡上好几秒。

## `Status.CONNECTING`

原来 `servers()` 只有三态，`client == null` 一律算 `FAILED`。改成后台后，启动头几秒里
所有 enabled 条目都是 `client == null`，会被显示成「连接失败」——**那是在报一个还没发生的错**。

新增 `CONNECTING`，判定顺序必须排在 `FAILED` 之前。面板显示「连接中…」，展开时显示
「（连接中，工具尚未发现）」而不是「（未连接，无工具信息）」。

枚举加常量会打挂 `CodeTuiView` 里两处穷尽 `switch`——这是**期望行为**，编译器正是该在这里拦住人。

## 界面

- 状态栏 IDLE 分支加后缀 `⟳ MCP 连接中 N`，走 `backgroundStatusSuffix()` 同一条路。
- 「（MCP：已发现 N 个工具。）」那行原来由 `CodeTuiApplication` 同步算出——现在算的时刻在
  TUI 起来之前，只会数到 0。改为 registry 在计数归零时经新增的
  `AgentListener.onMcpReady(serverCount, toolCount)` 回调，由 `ConversationState` 落行。
  **零工具时一个字都不说**：没配 MCP 的用户占多数，给他们看「已发现 0 个工具」纯属噪声。

## 测试

`McpRegistryBackgroundConnectTest`，4 条。

**测试接缝**：`McpSyncClient` 的构造函数是包私有的，造不出假的。加了包私有
`initWithConnector(...)`，把「真的去连一个 server」这一步换掉。这个接缝对准的是**风险所在**
——本次改动的风险全在**写回时机**，不在连接本身。

**不用墙钟做断言**：「init 没有阻塞」靠 `CountDownLatch` 表达——init 返回时连接还卡在闸门上，
这是状态断言。墙钟阈值在慢机器上假红、快机器上假绿。

| 测试 | 钉住什么 |
|---|---|
| `initDoesNotBlockOnConnect` | init 返回时连接仍卡着；此时 status 是 CONNECTING 不是 FAILED |
| `resultsBecomeVisibleAfterConnect` | 连完工具可见、计数归零、`onMcpReady` **恰好一次** |
| `resultArrivingAfterCloseIsDiscarded` | 守卫② |
| `resultForDisabledServerIsDiscarded` | 守卫③ |

**已知边界**：守卫②只断了「没写回」这一半。另一半（把它就地关掉）需要一个真的
`McpSyncClient` 才观测得到，造不出来。孤儿这件事由 pty 冒烟的实机断言兜底。

### 一条自己踩出来的假绿（值得记）

变异验证时把两道守卫**一起**拿掉，结果只红了一条——守卫③那条照样绿。

原因：`disable()` 已经把 `enabled` 置成 false，而迟到写回**不碰 `enabled`**。于是工具虽然被
塞回 `e.tools`，`activeTools()` 仍因 `enabled == false` 全部过滤掉，`status` 也照样是 `DISABLED`
——**原来断的两个量都区分不了这个变异**。

真正会露出来的是 `/mcp` 面板的工具数：一个「已禁用」的 server 显示着 1 个工具。
改断 `servers().get(0).toolCount() == 0` 之后变异立刻转红。

判据还是那一条：**问「这条路和它要防的那条路，可观测输出有没有区别」**，没区别就是断错了量。

## 结果

| | 改前 | 改后 |
|---|---|---|
| 启动（2 个 MCP，真实配置） | 7.25s | **1.66s** |
| 启动（无 MCP 基线） | 1.64s | 1.67s |
| 单测 | 1281 | 1285 |
| pty 冒烟 | 9/9 PASS | 9/9 PASS |

启动耗时已与「完全不配 MCP」无差别——再加多少个远程 server 也不会拖慢启动。

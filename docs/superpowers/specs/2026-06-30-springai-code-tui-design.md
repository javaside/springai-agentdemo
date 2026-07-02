# springai-code-tui 设计方案（v1）

- 日期：2026-06-30
- 状态：待评审
- 作者：zxh + Claude

## 1. 背景与目标

在现有学习仓库 `springai-agentdemo` 下新增模块 **`springai-code-tui`**：一个**以 Spring AI 2.0 为基础的「代码编写智能体」**，用 **TamboUI**（Java TUI 库，仿 Rust ratatui）做单栏对话式终端界面。

**v1 范围只做一件事**：把 `spring-ai-agent-utils` 的「核心编码工具集」接入，配上一个能流式对话、能看到工具活动的单栏 TUI。不做联网搜索、子智能体、长期记忆等更重的能力。

与仓库其它模块保持一致：**纯 Java 21、不依赖 Spring Boot、用 Spring AI 原始 API、DeepSeek 模型、`maven-jar-plugin` + `copy-dependencies` 打可运行 jar**。

## 2. 范围

### v1 包含
- 接入 5 个工具：`FileSystemTools`（read/write/edit）、`ShellTools`、`GrepTool`、`GlobTool`、`TodoWriteTool`。
- 单栏对话式 TUI：对话滚动区（流式 token + 内联工具活动 + Todo 状态）、输入框、底部状态栏。
- 多轮会话记忆（窗口记忆）。
- 用 `AgentEnvironment` 把 cwd / git 状态 / 模型名注入系统提示做 grounding（**确定纳入**）。
- 工作目录边界（方案 B，已定）：**只有 `FileSystemTools` 受 `allowedDirectory(root)` 真沙箱**；`ShellTools`/`GrepTool`/`GlobTool` 原生**不受限**，照用并诚实声明 + 启动确认门（详见 §9）。
- Esc 取消当前回合、Ctrl+C 退出。

### v1 不包含（明确排除）
- 联网（`SmartWebFetchTool` / `BraveWebSearchTool`）、子智能体（`TaskTool`）、长期记忆（`AutoMemoryTools`）、技能（`SkillsTool`）、向用户反问（`AskUserQuestionTool`）。
- 多栏布局、文件树、diff 预览。

## 3. 关键技术结论（已对文档/源码核实）

> 原则：不臆测 API。以下均已对 Spring AI 2.0 升级说明、`spring-ai-client-chat-2.0.0` / `spring-ai-model-2.0.0` / `spring-ai-agent-utils-0.10.0` 的 jar 与 javap 核实。

1. **流式是非阻塞的。** `chatClient.prompt()...stream().content()` 返回 Project Reactor 的 `Flux<String>`，cold、订阅前不执行、`.subscribe()` 立即返回，token 在 Reactor 调度线程异步到达。**因此不需要为「避免阻塞渲染」单开 worker 线程**；`.call()` 才是同步阻塞，v1 不用它跑主循环。
   - 来源：官方 ChatClient 文档 “stream() return values”。
2. **Spring AI 2.0 自动注册 `ToolCallingAdvisor`。** 绝不能再手动 `.advisors(ToolCallingAdvisor...)`，否则会重复。工具自动执行默认开启（`spring.ai.chat.client.tool-calling.enabled=true`）。
3. **工具注册用 `defaultTools(Object...)`。** 它直接接受 `ToolCallback`、`ToolCallbackProvider`、`@Tool` 注解的 POJO。`defaultToolCallbacks(...)` 已被标记 deprecated，不用。
4. **会话记忆：`PromptChatMemoryAdvisor` 已删除，改用 `MessageChatMemoryAdvisor`。** 且 **conversationId 现在必填**：memory advisor 的 builder **没有 `conversationId()` 方法**，必须按请求传：
   `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))`。`ChatMemory.DEFAULT_CONVERSATION_ID` 已删除。
5. **agent-utils 工具的真实 API（javap 确认）：**
   - `FileSystemTools.builder().allowedDirectory(Path).build()` —— 自带工作目录沙箱；`@Tool` 方法 `read/write/edit`。
   - `ShellTools.builder().build()`。
   - `GrepTool.builder().workingDirectory(Path).build()`、`GlobTool.builder().workingDirectory(Path).build()`（设为 root 让默认目录对齐；非沙箱，绝对路径/`../` 仍可越界）。
   - `TodoWriteTool.builder().todoEventHandler(TodoWriteTool.TodoEventHandler).build()` —— 自带 Todo 事件回调。
   - `AgentEnvironment` 在 `org.springaicommunity.agent.utils`。
6. **工具活动可见性**：这些工具（除 Todo 外）无内建事件。用仓库已验证的「**装饰 `ToolCallback`**」写法（参考 `springai-agent-demo` 的 `LoggingSkillCallback`）：用 `ToolCallbacks.from(pojo)`（在 `spring-ai-model`）把 @Tool POJO 转成 `ToolCallback[]`，每个包一层「执行前/后发事件」的装饰器，再 `defaultTools(...)` 注册。装饰器在工具**真正执行时**触发，对 `call`/`stream` 都可靠。Todo 用其自带 `todoEventHandler`。
7. **TamboUI 坐标已实测钉死**（通过 Maven resolver 从 `central` 下载 jar + `javap` 确认；注：Maven Central **search API** 查 `g:"dev.tamboui"` 返回 0，但 resolver 能正常解析，以 resolver 为准）：group `dev.tamboui`，`tamboui-bom` 存在，版本 `0.2.0 / 0.3.0 / 0.4.0`，**用最新 `0.4.0`**。`TuiRunner` / `TuiConfig` / `TickEvent` 都在 **`tamboui-tui`**，包 `dev.tamboui.tui`（`TickEvent` 在 `dev.tamboui.tui.event`）。`TuiConfig` 实测有 `tickRate()` / `ticksEnabled()` / `scheduler()`。
8. **UI 用中层 `TuiRunner`，不用高层 Toolkit DSL**（详见下「§3.1 选型对比」）。核心理由：流式对话需要**后台线程驱动的周期重绘**，而该机制（`TuiConfig.tickRate` + `TickEvent`）**只有 TuiRunner 有文档依据**；Toolkit DSL 文档完全未提 tick / 后台重绘。
9. **跨线程刷新有官方入口（实测）**：`TuiRunner` 暴露 `runLater(Runnable)` 与 `runOnRenderThread(Runnable)` —— 后台 Reactor 线程可把状态更新**编排回渲染线程**，是线程安全的。于是刷新有「tick 周期重绘 + runLater 主动投递」双机制，旧的"跨线程触发重绘"风险彻底消除。

### 3.1 选型对比：TuiRunner vs Toolkit DSL（已对 TamboUI 文档核实）

TamboUI 分三层：低层 immediate widgets / 中层 **TuiRunner**（托管事件循环）/ 高层 **Toolkit DSL**（`ToolkitApp` 声明式）。本项目的成败判据是「后台 Reactor 线程的流式 token → 界面周期性自动重绘」（无按键也要刷新）。

| 维度 | TuiRunner（选用） | Toolkit DSL（不选） |
|---|---|---|
| 后台/流式刷新 | ✅ `TuiConfig.builder().tickRate(Duration.ofMillis(16))` + 处理 `TickEvent` 周期重绘（官方为 animations/updates 设计）；后台写共享状态、tick 循环每帧取最新状态重绘 | ❓ 文档未提 tick/animation/后台重绘/`requestRender`；面向事件驱动 |
| 事件循环掌控 | ✅ 显式两回调（event handler / render）；Esc 取消、Ctrl+C 退出、返回 true 触发重绘都可控 | ⚠️ 循环隐藏，掌控度低 |
| 单栏布局成本 | ⚠️ 需手画 widget + 布局 + 输入缓冲/光标 | ✅ 声明式 + 自动焦点 + CSS，样板少 |

渲染模型两者一致：immediate-mode 全帧重绘 + buffer diff，**频繁 tick 重绘很便宜**（只写变化 cell），60fps tick 不是负担。让出的「声明式布局/CSS/自动焦点」对单栏界面（一个输入框 + 一个滚动区 + 状态栏）影响很小，代价可接受。
**保留**：`ToolkitApp` 内部也可能跑在支持 tick 的 runner 上（无文档佐证）；里程碑 1 验证 TamboUI 时顺手确认，若证实 DSL 也能 tick 刷新，后续可把视图层升级成 DSL；v1 先用有据可依的 TuiRunner。

## 4. 模块结构与依赖

### 4.1 加入父 pom
- 在根 `pom.xml` 的 `<modules>` 增加 `springai-code-tui`。
- 在根 `pom.xml` 的 `<properties>` 增加 `<tamboui.version>0.4.0</tamboui.version>`。
- 在根 `pom.xml` 的 `<dependencyManagement>` 导入 `dev.tamboui:tamboui-bom`（已实测存在；统一 TamboUI 版本，与现有 BOM 风格一致）。

### 4.2 模块 pom 依赖
```
org.springframework.ai:spring-ai-deepseek         （模型）
org.springframework.ai:spring-ai-client-chat      （ChatClient / 记忆 / advisor；传递带入 spring-ai-model、reactor-core）
org.springaicommunity:spring-ai-agent-utils:0.10.0（5 个工具 + AgentEnvironment）
dev.tamboui:tamboui-tui                            （中层 TuiRunner / TuiConfig / EventHandler；包 dev.tamboui.tui）
dev.tamboui:tamboui-widgets                        （Paragraph/Block/List 等部件，手画单栏用）
dev.tamboui:tamboui-jline3-backend                 （必需的一个后端；tamboui-core 传递带入）
ch.qos.logback:logback-classic                     （日志写文件，不污染 TUI）
org.junit.jupiter:junit-jupiter (test)
```
> 流式需要 reactive 栈在 classpath，`reactor-core` 由 `spring-ai-client-chat` 传递带入，实现时确认即可。
> TamboUI 模块归属已实测：`TuiRunner`/`TuiConfig`/`TickEvent` 在 `tamboui-tui`。用 `tamboui-bom` 统一版本，部件按需增删 artifact。

### 4.3 打包
照搬 `springai-agent-demo` 的 `maven-jar-plugin`（写 Main-Class + `lib/` classpath）+ `maven-dependency-plugin`（copy-dependencies 到 `target/lib`）。`finalName=springai-code-tui`。

## 5. 包结构与类清单

包根：`com.example.springai.codetui`

| 类 | 职责 | 依赖 TUI? |
|---|---|---|
| `CodeTuiApplication` | main 入口：读 `DEEPSEEK_API_KEY` → **安全警示横幅 + 启动确认门**（`CODE_TUI_I_UNDERSTAND=1` 或交互 y/N，未放行则退出）→ 建 `DeepSeekChatModel`/`ChatClient` → 组装 `CodingAgent` → 启动 TUI | 否（仅启动） |
| `agent/CodingAgent` | 核心。持有装好 5 工具 + 记忆的 `ChatClient` 与 **`AtomicLong activeTurnId`**；`submit(userMsg)` 自增 turnId、经 `toolContext` 下传、订阅 `Flux<ChatClientResponse>`、`handleChunk/handleError/handleComplete` 解析后经 `AgentListener` 回调；返回 `Disposable` 供取消 | **否** |
| `agent/AgentListener` | UI 接缝接口，**只用纯 Java 类型**（不含 Spring AI 类型）：`onUserMessage / onAssistantToken / onToolStarted / onToolFinished / onTodoUpdated / onTurnComplete / onError`，**每个方法都带 `turnId`** 供过滤迟到事件。解析 `ChatClientResponse`→文本/metadata 发生在 `CodingAgent` 内部的 `handleChunk`，不暴露给本接口 | 否 |
| `agent/ToolEventCallback` | `ToolCallback` 装饰器：`call(input, ToolContext)` 里从 `toolContext` 取 `turnId`，执行前后调 `AgentListener.onToolStarted/Finished(turnId,...)`（仿 `LoggingSkillCallback`） | 否 |
| `agent/AgentTools` | 工厂：用各 builder 造 5 个工具（Grep/Glob 设 `workingDirectory(root)`、Todo 用闭包读 `activeTurnId`）、`ToolCallbacks.from` 转换、装饰、组装系统提示（含 `AgentEnvironment`）。与 `CodingAgent` 共享同一个 `AtomicLong activeTurnId` | 否 |
| `ui/ConversationState` | 线程安全共享状态：消息列表、流式助手缓冲、运行状态(idle/thinking/running-tool)、输入缓冲、todo 快照 | 否 |
| `ui/CodeTuiView` | TamboUI **TuiRunner**：`TuiConfig.tickRate` 周期重绘，render 回调画对话区/输入框/状态栏，event 回调处理按键（输入/Enter/Esc/Ctrl+C）；只读 `ConversationState` | **是** |

**边界要点**：`CodingAgent` 及其下全部不 import 任何 TamboUI 类、也不依赖 `ConversationState` → 可像现有 demo 一样 headless 跑、JUnit 测。`turnId` 的生成权在 `CodingAgent`（`AtomicLong`），单向经 `AgentListener` 流向 UI。TUI 是薄视图，只读 `ConversationState`。`AgentListener` 是两者唯一接缝（纯 Java 类型）。

## 6. 运行时架构与线程模型

```
[TuiRunner 线程(单线程: event + render)]      [Reactor 调度线程(订阅后)]
   |  tickRate 每帧读 ConversationState 重绘     |  Flux<String> token → onAssistantToken
   |  event 回调: 输入/Enter/Esc/Ctrl+C          |  工具执行 → 装饰器 → onToolStarted/Finished
   |                                            |  TodoWriteTool → todoEventHandler → onTodoUpdated
   |  Enter: agent.submit(text) ────────────────┘  (subscribe 立即返回 Disposable)
   |  Esc:   disposable.dispose()               所有回调 → 写 ConversationState
```

- **写状态**在 Reactor 线程，**读状态**在 TuiRunner 线程（event 与 render 同一线程）→ `ConversationState` 必须线程安全（`synchronized` 或并发结构 + volatile 状态位）。
- **重绘机制（双保险，已实测）**：① `TuiConfig.builder().tickRate(Duration.ofMillis(~33))` 让 TuiRunner ~30fps 周期重绘（buffer diff 使其廉价）；② 后台回调里用 `runner.runLater(() -> state.append(...))` 把更新编排回渲染线程，既线程安全又即时。v1 先用 ① 的轮询(最简)，需要更跟手再叠加 ②。
- **取消语义（分两层，措辞要诚实）**：
  - **UI 层取消（v1 保证）**：`dispose()` + 按 turnId 过滤 → 界面立即停止追加 token、状态回 idle。这是验收硬指标。
  - **后端真实终止（不保证）**：`Disposable.dispose()` **不一定**真正中止底层 HTTP 请求，更**不会**杀掉已在执行的 shell 命令（外部进程）。这层能力放到里程碑 2 spike 判定，不写进 v1 必达验收。

## 7. 关键流程（提交一次对话）

1. 用户在输入框敲字（`CodeTuiView` 维护输入缓冲）。
2. 按 Enter：把用户消息追加进 `ConversationState`，调 `codingAgent.submit(text)`。
3. `CodingAgent.submit`：
   ```
   long turnId = activeTurnId.incrementAndGet();   // turnId 由 CodingAgent 自己的 AtomicLong 生成（不碰 UI 状态）
   Disposable d = chatClient.prompt()
       .user(text)
       .toolContext(Map.of("turnId", turnId))      // 让工具装饰器在执行时能拿到当前 turnId（2.0：tools 用 toolContext 传上下文）
       .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId)) // 2.0 必填
       .stream().chatClientResponse()                                 // Flux<ChatClientResponse>（比 content() 信息更全）
       .doOnNext(resp -> handleChunk(resp, turnId))  // 内部解析（见下）
       .doOnError(err -> handleError(err, turnId))
       .doOnComplete(() -> handleComplete(turnId))
       .subscribe();
   // 工具在内部自动执行；装饰器从 ToolContext 取 turnId、todoEventHandler 读 activeTurnId.get() 发事件
   ```
   `submit` 立即返回 `Disposable`（UI 存起来供 Esc 取消，取消不需要 turnId）。turnId 的生成权在 `CodingAgent`，UI 只从带 turnId 的事件里得知——**不反向依赖 `ConversationState`**。
   > **`onChunk` 是 `CodingAgent` 的内部方法，不在 `AgentListener` 上**——刻意让 Spring AI 的 `ChatClientResponse` 类型**不泄漏到 UI 接缝**。`handleChunk(ChatClientResponse, turnId)` 在 agent 层解析：抽取文本增量与 metadata，按 turnId 过滤迟到事件，再调用 `AgentListener` 的细粒度方法（`onAssistantToken(text, turnId)` 等）。`AgentListener` 全部方法都带 `turnId`。
   > **为什么用 `chatClientResponse()` 而非 `content()`**：`content()` 只给纯文本增量，看不到工具循环里的 assistant/tool message 边界、最终响应对象、usage/error。`chatClientResponse()` 给完整 `ChatClientResponse`（含 `ChatResponse` + 执行上下文）。文本增量从 `resp.chatResponse().getResult().getOutput().getText()` 取（实现时核实）。**流式下工具循环的消息边界/记忆落库语义仍需 spike 验证**（见 §12 里程碑 2）。
4. token/工具/Todo 事件经 `AgentListener` 写入 `ConversationState`；写入前按「当前回合标识」过滤迟到事件；渲染线程下一帧绘出。

## 8. 工具装配与系统提示

```
Path root = Path.of(System.getProperty("user.dir"));   // workspace root（或启动参数指定）
AtomicLong activeTurnId = ...;                          // 与 CodingAgent 共享的同一个实例

var fs   = FileSystemTools.builder().allowedDirectory(root).build();
var sh   = ShellTools.builder().build();
var grep = GrepTool.builder().workingDirectory(root).build();   // 非沙箱，但默认目录对齐 root（绝对路径仍可越界，见 §9）
var glob = GlobTool.builder().workingDirectory(root).build();
// Todo 事件需要 turnId，但 handler 只收 Todos → 用闭包读 activeTurnId.get() 包一层（不能直接传 listener::onTodoUpdated）
var todo = TodoWriteTool.builder()
        .todoEventHandler(todos -> listener.onTodoUpdated(activeTurnId.get(), todos))
        .build();

ToolCallback[] raw = ToolCallbacks.from(fs, sh, grep, glob, todo);
// ToolEventCallback 装饰：call(input, ToolContext) 里从 toolContext 取 "turnId"，发 onToolStarted/Finished(turnId, ...)
ToolCallback[] decorated = wrapEach(raw, listener);

ChatClient client = ChatClient.builder(deepSeekModel)
    .defaultSystem(s -> s.text(SYSTEM_TEMPLATE)        // 带占位符的模板（含 AgentEnvironment 四个键）
        .param(AgentEnvironment.ENVIRONMENT_INFO_KEY,          AgentEnvironment.info())      // 静态方法
        .param(AgentEnvironment.GIT_STATUS_KEY,               AgentEnvironment.gitStatus()) // 静态方法
        .param(AgentEnvironment.AGENT_MODEL_KEY,              "deepseek-chat")
        .param(AgentEnvironment.AGENT_MODEL_KNOWLEDGE_CUTOFF_KEY, MODEL_CUTOFF))
    .defaultTools((Object[]) decorated)
    .defaultAdvisors(MessageChatMemoryAdvisor.builder(
            MessageWindowChatMemory.builder().build()).build())
    .build();
// 不手动加 ToolCallingAdvisor —— 2.0 自动注册
```

**`AgentEnvironment`（javap 确认的真实 API）**：静态方法 `info()`（环境信息）、`gitStatus()`（git 状态）+ 四个模板占位键 `ENVIRONMENT_INFO_KEY / GIT_STATUS_KEY / AGENT_MODEL_KEY / AGENT_MODEL_KNOWLEDGE_CUTOFF_KEY`。无 builder、无实例状态——就是「把这几段 grounding 文本填进系统提示模板」。模型名/知识截止由我们按 DeepSeek 手填。

**系统提示模板（中文，要点）**：定位为代码编写智能体；先用 Grep/Glob/读文件理解再改；多步任务先用 TodoWrite 列计划；改完用 Shell 验证；**默认围绕 workspace root 工作——注意：仅 `FileSystemTools` 有强制边界，`Shell/Grep/Glob` 无技术限制，操作前自我约束在 root 内**（不能承诺"限定"，与 §9 一致）。模板里嵌上述四个占位符承接 `AgentEnvironment` 的 cwd/git/模型 grounding。

## 9. 安全（按 0.10.0 实测能力重写）

**实测结论（不是臆测）**：`spring-ai-agent-utils:0.10.0` 里**只有 `FileSystemTools.allowedDirectory(root)` 是真沙箱**。
- `ShellTools.bash(...)` 字节码：`new ProcessBuilder([/bin/bash,-c,cmd]).start()`，**无 `.directory()`**，builder 也无 cwd 选项 → 命令在 JVM cwd 跑，且模型可 `cd /`、用绝对路径、`rm -rf`、`curl|bash` 等任意越界/破坏。
- `GrepTool/GlobTool.workingDirectory(root)` 只是**默认**目录；search/path 参数传绝对路径或 `../` 仍可越界读盘。

因此原 §2「文件/命令限定在 workspace root 内」对 Shell/Grep/Glob **不成立**，已据下方决策修正。

**v1 处置：采用方案 B（已拍板）——照用 agent-utils 原工具 + 诚实降级声明 + 启动确认门。**
- **诚实声明**：README 与启动横幅都写明「仅 `FileSystemTools` 受 root 沙箱；**`ShellTools`/`GrepTool`/`GlobTool` 不受限**，智能体可执行任意命令、读盘任意位置」。不把 v1 宣传成安全。
- **启动确认门**：进程启动先打印**红色警示横幅**（含当前 workspace root 绝对路径），并要求显式放行才继续——二选一即可：环境变量 `CODE_TUI_I_UNDERSTAND=1`，或交互式 `y/N` 确认（TUI 起来前的普通 stdin）。未放行则退出。
- **使用约束（写进文档）**：强烈建议只在**一次性 / 可丢弃 / 受版本控制干净**的工作目录里运行；不要在 `$HOME` 或重要仓库根直接跑。
- A 方案（自写 `SandboxedShellTool` + 路径校验）记为 **v1 之后的安全增强**，本版不做。

日志（logback）全部写文件，避免破坏 TUI 终端。

## 10. 构建 / 运行
- 构建：`mvn -pl springai-code-tui -am package`
- 运行：`export DEEPSEEK_API_KEY=...`；建议 `cd` 到一个**可丢弃的工作目录**；`java -jar .../springai-code-tui.jar`
- 启动后会显示安全警示横幅并要求放行（`CODE_TUI_I_UNDERSTAND=1` 跳过交互，或回答 `y`）。
- 工作目录即 workspace root（智能体在此读写代码、跑命令；Shell/Grep/Glob 不受限，见 §9）。

## 11. 测试（核心风险在线程/取消/工具事件/沙箱，重点补强）
- **`ConversationState` 并发/快照**：多线程写 + 渲染线程读，断言无 `ConcurrentModificationException`、读到一致快照。
- **取消语义**：`dispose()` 后到达的迟到 token 必须被按回合标识丢弃，断言取消后 state 不再增长。
- **工具装饰器异常**：被装饰工具抛异常时，`onToolFinished`/`onError` 仍被调用、不吞错、不卡死。
- **路径越界（方案 B）**：断言 `FileSystemTools` 写/读越界被 `allowedDirectory` 拦；并用一个**显式记录性测试**钉住「Shell/Grep/Glob 不受限」这一事实（防止后人误以为安全）。另测启动确认门：未放行(无 `CODE_TUI_I_UNDERSTAND` 且未交互确认)时进程拒绝启动。
- **`CodingAgent` / `AgentTools` headless**：工具装配、`AgentListener` 事件、conversationId 透传。可用假的/录制的 ChatModel。
- **流式 + 工具 + 记忆 spike（里程碑 2 硬验收）**：见 §12。
- TUI：TamboUI 的 Pilot 测试（可选，v1 以手动验证为主）。

## 12. 里程碑（增量交付）
1. **骨架**：模块 + pom + BOM；用 `TuiRunner` + `TuiConfig.tickRate` 跑通 `Hello TamboUI` 单栏，能输入、Ctrl+C 退出；**顺带确认 TamboUI 模块拆分与 tick/重绘 API 的真实包名**。
2. **agent 核心 + 流式语义 spike（硬验收）**：`CodingAgent` headless 跑通（工具 + 记忆 + 流式订阅 + 事件）。**spike 必须实测确认**：流式 `chatClientResponse()` 下工具循环中 assistant/tool 消息边界是否可观察；多轮后记忆里是否正确含 user/assistant（工具中间消息是否入库）；`dispose()` 取消能否停止后端调用——**实测并记录结论；即便后端不能停，UI 层按 turnId 过滤"取消后不再追加"也必须通过**（与 §6 两层取消一致）；error 是否经 `doOnError` 抵达。参考 `springai-agent-demo/.../ToolMemoryAdvisorDemo.java` 关于 advisor 顺序影响中间消息入库的现象。JUnit 绿。
3. **接 TUI**：流式 token 内联渲染 + 工具活动 + Todo 显示 + Esc 取消。
4. **打磨 + 安全门**：自动滚到底、状态栏、错误展示、日志改写到文件、系统提示 grounding；**启动确认门 + 安全警示横幅 + README 诚实声明**（方案 B）。

## 13. 风险与未知
- **（高·已成立·已接受）`spring-ai-agent-utils:0.10.0` 工具沙箱能力弱**（实测）：只有 `FileSystemTools` 是真沙箱；`ShellTools` 字节码 `ProcessBuilder` **无 `.directory()`**、零目录约束；`GrepTool/GlobTool.workingDirectory` 只是默认目录，绝对路径/`../` 可越界。**处置（方案 B）**：照用 + 诚实声明 + 启动确认门 + 建议在可丢弃目录运行（§9）。残余风险已知并接受。
- **（高）流式 + 工具循环 + 记忆语义未实测** —— 见 §12 里程碑 2 硬验收 spike；用 `chatClientResponse()` 取全信息，未通过前不进入里程碑 3。
- **（中）TamboUI 0.4.0 为实验期版本，API 可能变** —— 坐标/包名已实测钉死（§3.7-3.9）；锁定 0.4.0、用 `tamboui-bom`，UI 集中在 `CodeTuiView` 一处便于替换。
- **（低）reactor-core 是否随 client-chat 传递带入** —— 里程碑 1 build 时确认。
- ~~（原·高）跨线程触发重绘~~ —— 已实测消解：`tickRate` + `runLater`/`runOnRenderThread` 双机制。

## 14. 验收标准（v1 Done 的定义）
1. 单栏 TUI 启动，可连续多轮对话，助手回复**流式**逐字出现。
2. 让它「读某文件→改一处→用 shell 验证」，对话区能看到对应**工具活动**与最终结果，文件确被修改（限 workspace root 内）。
3. 多步任务时 Todo 列表在界面可见且随进度更新。
4. Esc **UI 层**中断：取消后界面不再追加 token、状态回 idle（后端调用是否真停由 spike 评估，不在本验收）；Ctrl+C 干净退出；全程无日志刷屏破坏界面。
5. 启动确认门生效：未放行时拒绝启动；横幅如实声明 Shell/Grep/Glob 不受限。
6. §11 的并发/取消/装饰器异常/越界四类测试 + 里程碑 2 流式 spike 全绿。

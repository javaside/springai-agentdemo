# Spring AI 2.1 Session Memory 教学示例（设计稿）

- 日期：2026-11-25
- 分支：`feature/session-memory-demo`
- 状态：已批准（2026-11-25，五项本人裁定一并放行；另拍板 agent-demo 的测试进 CI）
- 决策记录：**模块落位**（主体进 `springai-agent-demo`，另在 `springai-boot-demo` 加一个 JDBC 落库切片）、
  **与旧示例的关系**（新增并插入核心区，`ChatMemory` 旧示例保留并标注弃用）、**覆盖范围**（基础 / 压缩 /
  召回 / 落库四块）、**agent-demo 的测试进 CI**——四项由用户在对话中拍板。菜单编号、示例拆分粒度、压缩策略的
  取舍、落库形态、测试策略为本人裁定（§4 逐项标注），已随本稿一并放行。

## 1. 意图与动机

本仓库 v1.31.0 已把 Spring AI 升到 2.1.0，code-tui 的会话记忆也已迁到官方 session 模块。但教学路线第②步
（`springai-agent-demo`）的「记忆」仍只教 `ChatMemory`——它在 2.1.0 已被标记为「3.0 移除」的弃用 API，
官方继任者是 Session Memory（追加式事件日志 + 视图 + 压缩 + 召回）。根 README 写着「对话记忆（第②步）→
code-tui 里升级为事件溯源会话 + 自动压缩」，中间缺的就是这一环：读者要到第⑥步才第一次遇到事件日志、
压缩边界这些概念。

本设计在第②步补三个核心示例（会话记忆 / 会话压缩 / 会话召回），在第③步补一个 JDBC 落库示例，让读者：

1. 知道「记忆」在 2.1 的现行写法，以及它比 `MessageWindowChatMemory` 多了什么；
2. 到第⑥步时，code-tui 的文件仓库与压缩策略是「放大版」而不是黑盒；
3. 仍能看懂旧代码里的 `ChatMemory`（旧示例保留，标注弃用并指向新写法）。

## 2. 已核实的事实依据

以下均为 2026-11-25 在本机对 **2.1.0 GA** 的 jar / sources jar / 实跑核对，不是推测。可复跑的取证程序与
输出见[附录 A](#附录-a-取证程序与实测输出)。

### 2.1 弃用与继任

- `javap -v` 本机 `spring-ai-model-2.1.0.jar`：`ChatMemory`、`MessageWindowChatMemory`、
  `ChatMemoryRepository` 均带 `java.lang.Deprecated(since="2.1.0", forRemoval=true)`；
  `spring-ai-client-chat-2.1.0.jar` 里的 `MessageChatMemoryAdvisor` 同样。现有示例 2、4 用的正是这几个类。
- 官方升级页 [Chat Memory to Session Memory](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/upgrading/session-memory.html)
  给出的映射：
  - `MessageChatMemoryAdvisor.builder(chatMemory)` → `SessionAdvisors.builder(sessionService)`；
  - `ChatMemory.CONVERSATION_ID` → 同一个 context key（或 `SessionAdvisors.session(id)`）；
  - `MessageWindowChatMemory` / `maxMessages` → advisor 上的窗口 `EventFilter.lastN`，或压缩；
  - `ChatMemoryRepository` / `InMemoryChatMemoryRepository` → `SessionRepository` / `InMemorySessionRepository`；
  - JDBC：`JdbcChatMemoryRepository` + 旧 starter → `JdbcSessionRepository` + `spring-ai-starter-model-session-jdbc`。
- **官方文档链接只有 `2.1-SNAPSHOT` 路径可用**（`curl` 实测）：
  `reference/upgrading/session-memory.html`、`reference/memory/session/index.html`、
  `reference/2.1.0/memory/session/index.html`、`reference/integrations/stores/session-jdbc.html` 均 404；
  `reference/2.1-SNAPSHOT/` 下的 `upgrading/session-memory`、`memory/session/{index,compaction,recall}`、
  `integrations/stores/session-jdbc`、`tutorials/sessions` 六页均 200（官方 GA 博客给的也是这组链接）。
  README 外链一律用 `2.1-SNAPSHOT`。
- 网上的迁移文章（如 dev.to 那篇）按**社区版** API 写。社区版类名在官方 2.1.0 jar 里不存在
  （`unzip -l` 核对）：官方 `spring-ai-session` 的压缩策略只有 `WindowCompactionStrategy`，没有
  `SlidingWindowCompactionStrategy`；`SessionMemoryAdvisor.Builder` 只有 `order` / `scheduler` /
  `eventFilter` / `messageFilter`，没有 `compactionTrigger`——压缩配置在 `SessionAdvisors.Builder.compaction(trigger, strategy)`。

### 2.2 依赖与自动装配

- `spring-ai-session` 的 pom 依赖只有 `spring-ai-model` + `tools.jackson.core:jackson-databind`；
  `spring-ai-session-advisor` 依赖 `spring-ai-session` + `spring-ai-client-chat` +
  `io.micrometer:context-propagation`。**都不带 Spring Boot**，可以进「纯 Java」的 agent-demo；
  code-tui 用的就是这两个坐标（版本走 `spring-ai-bom`）。
- `spring-ai-bom:2.1.0` 管理的 session 相关 artifact（grep BOM pom）：`spring-ai-session`、
  `spring-ai-session-advisor`、`spring-ai-session-jdbc`、`spring-ai-autoconfigure-model-session`、
  `spring-ai-autoconfigure-model-session-jdbc`、`spring-ai-starter-model-session-jdbc`。
- `spring-ai-starter-model-session-jdbc:2.1.0`（Maven Central 上的 pom）依赖：`spring-boot-starter-jdbc:4.2.0`、
  上述两个 autoconfigure 模块、`spring-ai-session-jdbc`、`spring-ai-session-advisor`。
  **不含数据库驱动**，H2 要自己加（版本由 `spring-boot-dependencies` 管）。
- 自动装配（GA sources jar）：
  - `JdbcSessionRepositoryAutoConfiguration`：classpath 上有 `JdbcSessionRepository` / `DataSource` /
    `JdbcTemplate` / `DatabaseInitializationProperties`，且容器里有唯一的 `DataSource` 时，装配
    `JdbcSessionRepository`（按 `DataSource` 探测方言）；应用自己声明了 `SessionRepository` 则让位。
    建表脚本由 `spring.ai.session.repository.jdbc.initialize-schema` 控制。
  - `SessionServiceAutoConfiguration`：在唯一的 `SessionRepository` 之上装配 `DefaultSessionService`；
    属性前缀 `spring.ai.session`（`time-to-live` 默认 60 天，`allow-system-messages` 默认 false）；
    应用自己声明 `SessionService` 则让位。
- `schema-h2.sql` 三张表：`AI_SESSION`（会话元数据、`event_version`）、`AI_SESSION_INDEX`（被索引的元数据）、
  `AI_SESSION_EVENT`（日志：`seq` / `id` / `session_id` / `timestamp` / `event_type` / `message_type` /
  `message_content` / `payload` / `synthetic`）。脚本全部是 `CREATE TABLE IF NOT EXISTS`。

### 2.3 advisor 的默认位置

| advisor | 默认 order | 相对工具循环 |
|---|---|---|
| `SessionTurnAdvisor` | `HIGHEST_PRECEDENCE + 200` | 外层，包住整个回合；回合结束后做压缩 |
| `ToolCallingAdvisor` | `HIGHEST_PRECEDENCE + 300` | 工具循环本身 |
| `SessionMemoryAdvisor` | `HIGHEST_PRECEDENCE + 1000` | 内层，每一轮模型调用都经过 |

`ChatClient` 自动注册 `ToolCallingAdvisor` 时，只要链里有 order 更大的 `MemoryAdvisor`（`SessionMemoryAdvisor`
就是），就关掉工具循环自带的历史，改由记忆 advisor 每一轮从会话重建 prompt
（`DefaultChatClient.autoRegisterToolCallingAdvisor`，sources 第 1271–1292 行）。这与 `MessageChatMemoryAdvisor`
的默认位置（工具循环**外层**）正好相反——现有示例 4 的结论「记忆默认只记提问与最终回答」对 Session 不成立。

### 2.4 实跑行为

数字均来自[附录 A](#附录-a-取证程序与实测输出)的取证程序（脚本化模型，不联网）。

**5 轮纯问答**，`TurnCountTrigger(3)` + `WindowCompactionStrategy.lastTurns(2)`：

| 轮次 | 日志 | 视图 | 模型本轮收到的消息数 |
|---|---|---|---|
| 1 | 2 | 2 | 1 |
| 2 | 4 | 4 | 3 |
| 3 | 6 | 6 | 5 |
| 4 | **9** | **4** | 7 |
| 5 | 11 | 6 | **5** |

第 4 轮后触发压缩（条件是 `currentTurnCount > 3`，所以第 3 轮不触发），日志追加 1 条 `COMPACTION` 事件，
前 8 条原样不动；第 5 轮模型只收到 5 条（视图 4 条 + 新提问）——**模型看到的是视图，不是日志**。

**带工具调用的一轮**：日志按 `user → assistant（请求调用）→ tool（结果）→ assistant（最终回答）` 记 4 条；
工具结果一定入日志，不受 `MessageFilter` 影响。`TurnCountTrigger(1)` + `lastTurns(1)` 下，第 2 轮
（工具轮）压缩后视图恰好是这 4 条，第 1 轮的 2 条离开视图——**压缩按整回合切，工具调用不会被拆开**。

**召回**：压缩覆盖第 1 轮后提问，模型调用 `conversation_search`，工具从**整份日志**里找回了已离开视图的
那一轮；召回回合结束后视图只多了它自己的 4 条，旧事件没有回到视图，进上下文的只是搜索带回的一段摘录。
`InMemorySessionRepository` 不覆盖 `searchEvents`，走 `SessionRepository` 的默认实现（读日志后在内存里
匹配），所以内存仓库也能演示召回。`SessionRecallTools` 从工具上下文里取会话 id（key 与 advisor 的相同），
`SessionAdvisors.builder(..).sessionIdInToolContext(true)` 可以让 advisor 自动放进去。

**带摘要的压缩**（`.summarizer(ChatModelSummarizer.builder(chatModel).build())`）：压缩后日志追加
`COMPACTION` 边界 + 2 条 `synthetic=true` 的事件（一条 user「Summarize the conversation we had so far.」
是回合起点，一条 assistant 是摘要正文），视图 = 摘要回合 + 保留回合。摘要模型收到的是 `[system, user]` 两条消息。

**`lastN` 窗口**（不压缩，`SessionAdvisors.builder(sessions).memory(m -> m.eventFilter(EventFilter.active()
.toBuilder().lastN(4).build()))`）：5 轮后日志 10 条、视图 10 条（没有边界），但第 4、5 轮模型各只收到 5 条
（窗口里的 4 条历史 + 新提问）。窗口落在回合中间时会向前扩到回合起点（`EventFilter` javadoc：「extended back
to the start of the turn the window lands in」）：直接读活跃视图，`lastN(4)` 与 `lastN(3)` 返回的都是日志的
第 7–10 条。这是 `maxMessages` 的替代物，窗口按事件数计，但切口始终对齐回合。

### 2.5 取证时实测踩到的四个坑

都会在写测试或示例时撞上，现象不直观，取证程序末尾逐一复现了。

1. **桩 `ChatModel` 必须让 `getOptions()` 返回 `ToolCallingChatOptions`**。`ChatClient` 是从
   `chatModel.getOptions().mutate()` 起手装工具的（`DefaultChatClientUtils` 第 103–108 行）；返回普通
   `ChatOptions` 时 `.tools(...)` 被静默丢弃。现象：模型请求了工具调用，工具循环却根本没启动，回合带着
   一个没有结果的工具调用结束（实测：工具未执行、模型只被调用 1 次）。`getDefaultOptions()` 已弃用，不是入口。
2. **`CallResponseSpec` 的每个取值方法都会重走一遍 advisor 链，而链是有状态的**（`DefaultChatClient`
   第 665–678 行：`chatClientResponse()` / `chatResponse()` / `content()` 各自重新执行）。同一个 spec 上取两次，
   轻则模型被重复调用、已弹出的 advisor 不再生效（实测：模型被调用 2 次），重则抛
   `IllegalStateException: No CallAdvisors available to execute`（实测：带工具循环的回合）。只取一次，存成变量。
3. **模型请求了没注册的工具会抛异常**：`ToolCallingAdvisor` 现在总是自动注册，哪怕本次调用没传
   `.tools(...)`，所以 `IllegalStateException: No ToolCallback found for tool name: …` 会直接冒到调用方。
4. **对不带消息的事件调 `getMessage()` / `getMessageType()` 会抛异常**：压缩边界是 `COMPACTION` 事件、不带消息，
   `SessionEvent.getMessageType()` 直接调 `getMessage()`，后者抛
   `IllegalStateException: Event '…' is a COMPACTION event and carries no message`（sources 第 120–155 行）。
   打印日志时必须先判 `hasMessage()`。

## 3. 目标 / 非目标 / 反目标

**目标**

1. `springai-agent-demo` 新增三个核心示例，排在「对话记忆」之后：
   - 示例 3 会话记忆——会话是追加式事件日志，模型看到的是日志的视图；
   - 示例 4 会话压缩：日志与视图——压缩只追加一条边界、不改写日志，且按整回合切；
   - 示例 5 会话召回——被压缩掉的内容仍在日志里，模型可以自己搜回来。
2. 现有示例 2（对话记忆）、4（Advisor 顺序）保留可跑；标题与 README 标注「`ChatMemory` 自 2.1.0 已弃用、
   3.0 移除」并指向示例 3；原示例 3–7 顺延为 6–10。
3. `springai-boot-demo` 新增「会话落库」示例：`spring-ai-starter-model-session-jdbc` + 文件型 H2，重启后
   会话还在；「自动配置揭秘」示例增加 `DataSource` / `SessionRepository` / `SessionService` 三行。
4. 文档同步：agent-demo README、boot-demo README、根 README、`AGENTS.md` 模块表。
5. 行为有不需要 API key 的单测钉住（脚本化模型）：日志/视图条数、边界不改写日志、工具回合不被拆开、
   召回能找回被覆盖的事件。
6. CI 增加 `mvn -B -pl springai-agent-demo -am test`（JDK 17 / 21 矩阵都跑）。

**非目标**

- 不讲 Session 的扩展点与未选中的能力：自定义 `CompactionTrigger` / `CompactionStrategy`、分支隔离
  （`EventFilter.forBranch`）、工具确认暂停后的续跑、TTL 与 `deleteExpiredSessions`、JDBC 以外的仓库、
  `asyncCompaction`——README 指向官方文档。
- 不迁移、不删除旧的 `ChatMemory` 示例（用户已明确）。
- 不改 `springai-code-tui`，不改根 pom 的版本号，不做发版动作（release notes / CHANGELOG / 版本 bump 另计）。
- boot-demo 不引入测试（理由见 §7）。

**反目标（做了即失败）**

- 把示例写成 API 罗列——每个示例必须有一句跑完记得住的认知，只教本示例用到的 API。
- agent-demo 的示例依赖 Spring Boot，或把社区版的类名/写法混进来（§2.1）。
- README 里的「实跑输出」没真跑过就写——没跑之前标「待补」，不臆造。
- 借讲 Session 把旧示例贬成「错误写法」：`ChatMemory` 是被取代，不是 bug。

## 4. 方案取舍

**模块落位**（用户拍板）：主体进 `springai-agent-demo`，JDBC 切片进 `springai-boot-demo`。被淘汰：

- `springai-core-demo`（①）——它的公式是「文本进、文本出」与 RAG，记忆不在其中；
- 新开一个模块——「记忆」主线被割裂，且第②步本就是「工具 + 记忆 + 循环」；
- 只放 boot-demo——会话的核心概念不该绑在自动装配上，且 starter 只覆盖 JDBC 一种仓库。

**与旧示例的关系**（用户拍板）：新增并插入核心区，旧示例保留并标注弃用。被淘汰：

- 记忆主线迁移（改写示例 2、4，`ChatMemory` 缩成 README 一节）——课程只教现行 API 最干净，但要重做两个
  示例及其 README 实跑输出，且推倒了已认可的结构；
- 只在末尾追加（示例 8）——改动最小，但原生核心概念排在三个第三方示例之后，与 README「核心 / 进阶」的
  分组矛盾。

**覆盖范围**（用户拍板）：基础、压缩、召回、落库四块全做。

**示例拆成三个菜单项而非一个**（本人裁定）：每个示例一个核心认知，对齐第②步「一项一个知识点」的体例；
基础示例不碰压缩，压缩示例不碰召回，读者可以只跑到自己关心的一步。单示例分幕输出被淘汰：三段认知挤在
一次输出里，读不完。

**菜单编号：新示例占 3 / 4 / 5**（本人裁定）：记忆这条线连着学完（旧 → 新 → 压缩 → 召回），再进
「多步 Agent」综合题。代价是原示例 3–7 顺延，根 README 的「建议先跑一遍示例 3「多步 Agent」」要改成
示例 6（§8 已列）。

**压缩示例只做窗口策略；摘要压缩与 `lastN` 窗口放 README**（本人裁定）：窗口压缩最能一眼看出
「日志 vs 视图」（被覆盖的事件直接离开视图，没有替身）；摘要多一次模型调用、视图里多出两条 synthetic
事件，放在正文会干扰主认知。README 的「延伸」小节给 `.summarizer(..)` 与 `EventFilter.lastN` 两段代码，
输出取自单测（脚本化模型），并注明来源。

**落库用文件型 H2，放在 `~/.springai-boot-demo/`**（本人裁定）：要演示「重启后还在」，数据必须活过进程，
内存 H2 做不到。放用户目录而不是模块目录下：工作目录随启动方式变化（`mvn spring-boot:run` 与在仓库根
`java -jar` 的当前目录不同），相对路径会落成两个库；放用户目录还免去改 `.gitignore`，并与 code-tui 把
会话放在 `~/.codetui/sessions/` 的做法同构。被淘汰：进程内再建一个 `SessionService` 实例读同一个库
（证明不了重启）。

**测试只加在 agent-demo**（本人裁定）：行为断言用脚本化 `ChatModel`，不需要 key、结果确定。boot-demo
历来无测试，要测自动装配得引入 `spring-boot-starter-test` 加模型桩，代价高于一个教学切片的收益；
它以真机实跑输出为证（首次运行 / 再次运行两份）。

**agent-demo 的测试进 CI**（用户拍板）：`.github/workflows/ci.yml` 现状只跑 `springai-code-tui` 与
`springai-tamboui-demo` 的测试，其余模块 `-DskipTests package`——agent-demo 的测试（含现有的
`TodoWriteToolDemoTest`）从未在 CI 上执行过。本次在「测试 springai-tamboui-demo」之后加一步
`mvn -B -pl springai-agent-demo -am test`。被淘汰的「不进 CI」会让新测试只靠本地命令保证，腐化无处发现
（本仓库有过冒烟脚本腐化多个版本无人察觉的先例）。

## 5. 示例设计（springai-agent-demo）

### 5.1 依赖与接线

pom 只加两项（版本走 BOM）：`spring-ai-session`、`spring-ai-session-advisor`。

三个示例各自在 `run()` 里建自己的 `SessionService`（互不共享状态），接线是同一段：

```java
SessionService sessions = DefaultSessionService.builder()
        .sessionRepository(InMemorySessionRepository.builder().build())   // 内存仓库：进程退出即丢（落库见 boot-demo）
        .build();
List<Advisor> sessionAdvisors = SessionAdvisors.builder(sessions).build();   // 回合 advisor + 记忆 advisor，各在默认 order
// 每次调用：
//   .advisors(sessionAdvisors).advisors(SessionAdvisors.session(sessionId))
```

- 会话 id 的 context key 与 `ChatMemory.CONVERSATION_ID` 是同一个（`chat_memory_conversation_id`），示例 2 的
  两轮场景可以原样搬过来对照。
- advisors 一律按请求传入，不挂到共享 `ChatClient` 的 `defaultAdvisors` 上：`SessionMemoryAdvisor` 要求每个
  请求都带会话 id，漏了直接抛 `IllegalStateException`，挂成默认会让其它示例全部失败。
- 共享 `ChatClient` 上的 `defaultSystem(..)` 与会话 advisor 兼容（取证程序带 system 提示词跑过，模型每轮都
  收到 `[system, …]`），系统提示词不会被存进会话。
- 三个示例都通过构造器接收共享的 `ChatClient`（与现有示例一致），在 `AgentDemoApplication` 的 `List.of(...)`
  里按新顺序登记。

### 5.2 示例 3：会话记忆（`SessionMemoryDemo`，order 3）

- 标题：`会话记忆（Session Memory：事件日志）`
- 认知：**会话 = 追加式事件日志；记忆 advisor 每次请求把日志的视图拼进 prompt，再把新的问与答追加进去。**
- 做法：与示例 2 同一个两轮场景（「我最喜欢的编程语言是 Java，请记住」→「我刚才说是什么？」），
  `sessionId = "user-1"`。第 1 轮答完后、第 2 轮发出前，用 `sessions.getEvents(sessionId, EventFilter.active())`
  打印「第 2 轮请求会带上的历史」——记忆 advisor 读的正是这份活跃视图；两轮结束后打印整份事件日志。
- 输出形态（措辞与排版留给实施者，以下是必须出现的信息）：

  ```
  第1轮 问：…                          答：…
  第 2 轮请求会带上这段历史（2 条）：
    user       我最喜欢的编程语言是 Java，请记住。
    assistant  好的，已记住…
  第2轮 问：…                          答：…

  会话 user-1 的事件日志（4 条，只追加、不修改）：
    #1  user       a3f1c2d8  10:12:33.120  我最喜欢的编程语言是 Java，请记住。
    #2  assistant  77b0e91f  10:12:34.502  好的，已记住…
    #3  user       9c4e2b07  10:12:35.010  我刚才说我最喜欢的编程语言是什么？
    #4  assistant  5d1a83c4  10:12:36.388  是 Java。
  每条事件自带：id（UUID）、所属会话、时间戳、事件类型（MESSAGE）、消息类型（user / assistant / tool）
  ```

- 与示例 2 的对照写进类注释和 README：`ChatMemory` 是「每个会话一个 `List<Message>`，窗口一淘汰就没了」；
  `Session` 是「每个会话一个 `List<SessionEvent>`，每条带 id / 时间 / 类型，只会追加」。
- 可测的纯逻辑：`describeEvent(int index, SessionEvent event)`（渲染日志的一行）。

### 5.3 示例 4：会话压缩（`SessionCompactionDemo`，order 4）

- 标题：`会话压缩：日志与视图`
- 认知：**压缩不改写日志，只追加一条边界；此后模型看到的是边界之后的视图，且边界永远落在回合之间。**
- 配置：`SessionAdvisors.builder(sessions).compaction(new TurnCountTrigger(3),
  WindowCompactionStrategy.builder().lastTurns(2).build())`——「视图超过 3 个回合就压缩，只留最近 2 个回合」。
  触发条件与保留窗口都按**回合**数计（一个回合 = 一条用户消息及其后的全部内容，含工具调用）。
- 第一幕（会话 `demo-compaction`，5 轮短问答）：每轮结束打印「日志 X 条 / 视图 Y 条」；结束后把日志整份打出来，
  逐条标出是否还在视图里，边界那一行注明它覆盖了哪几条：

  ```
  轮次   日志   视图
    1      2      2
    2      4      4
    3      6      6
    4      9      4    ← 第 4 轮后触发压缩：日志多 1 条边界，视图缩回最近 2 个回合
    5     11      6    ← 第 5 轮发出时，模型拿到的是视图里的 4 条历史 + 新提问，不是日志的 11 条

  事件日志（11 条，只增不减）                         是否在视图里
    #1   user       第1句                           否（已被第 4 轮后的压缩覆盖，仍可召回，见示例 5）
    #2   assistant  回声：第1句                     否
    …
    #9   COMPACTION 压缩边界：覆盖 #1–#4，保留 #5–#8    （边界本身不属于视图）
    #10  user       第5句                           是
    #11  assistant  回声：第5句                     是
  ```

  条数来自 §2.4 的实测；真实模型的回答文字每次不同，条数不变。
- 第二幕（另一个会话 `demo-tool-turn`，`TurnCountTrigger(1)` + `lastTurns(1)`）：第 1 轮打招呼，第 2 轮问
  「北京天气怎么样？」并注册现有的 `WeatherTools`。压缩后视图恰好是整个工具回合，打印这 4 条并点明
  「压缩按整回合切，不会让模型看到一个没有结果的工具调用」。工具调用与工具结果的展开写法同示例 7 的
  `describe(Message)`（那是私有方法，这里照着写一份：工具名、参数、返回值）。
- 可测的纯逻辑（`static`、包内可见，`run()` 只负责打印）：
  - `playPlainTurns(ChatClient, SessionService, String sessionId, int turns)` → 每轮结束后的（日志条数，视图条数）；
  - `playToolTurns(ChatClient, SessionService, String sessionId)` → 两轮后的（日志事件，视图事件）；
  - `renderLog(List<SessionEvent> log, List<SessionEvent> view)` → 上面那段带「是否在视图里」的文本。

### 5.4 示例 5：会话召回（`SessionRecallDemo`，order 5）

- 标题：`会话召回：找回被压缩的内容`
- 认知：**压缩拿走的是「模型看到的」，不是「存下来的」；模型可以用 `conversation_search` 工具自己把旧内容搜回来。**
- 配置：与示例 4 第一幕相同的压缩配置，再加 `.sessionIdInToolContext(true)`（advisor 自动把会话 id 放进
  工具上下文，调用方不必每次手写 `toolContext`）；工具 `SessionRecallTools.builder(sessions).build()`。
- 流程（会话 `demo-recall`）：
  1. 第 1 轮「请记住：我的项目暗号是『海棠』。」第 2–4 轮闲聊 → 第 4 轮后压缩，暗号那一轮离开视图；
     打印此时的日志 / 视图条数（按 §2.4 实测应为 9 / 4）；
  2. 第 5 轮「我最早告诉你的项目暗号是什么？（上下文里没有的话，用会话搜索工具查）」→ 模型调用
     `conversation_search` → 答出「海棠」；
  3. 结尾再打印一次日志 / 视图条数（实测为 13 / 8）并点明：旧事件没有回到视图，进上下文的只是搜索
     带回的一段摘录。条数一律现算，不写死；真实模型若多调一次工具，数字会变，以真机输出为准。
- 观察手段：沿用示例 8–10 的做法——示例自带一个私有的 `RoundLoggingAdvisor`
  （order = `ToolCallingAdvisor.DEFAULT_ORDER + 100`，位于工具循环内、记忆 advisor 外），按轮次打印
  模型请求了哪个工具、工具返回了什么（返回的 JSON 截断显示）。
- 提问里显式引导「用会话搜索工具查」是本人裁定：真机验证若发现不引导模型也能稳定调用，可以去掉（§9）。
- 可测的纯逻辑：`seedAndRecall(ChatClient, SessionService, String sessionId)` → 召回前后的
  （日志事件，视图事件，最终回答）。

### 5.5 旧示例的标注与新顺序

| 新序 | 示例类 | `order()` | 标题 |
|---|---|---|---|
| 1 | `ToolCallingDemo` | 1 | 不变 |
| 2 | `ChatMemoryDemo` | 2 | `对话记忆（ChatMemory，2.1 起已弃用）` |
| 3 | `SessionMemoryDemo` | 3 | `会话记忆（Session Memory：事件日志）` |
| 4 | `SessionCompactionDemo` | 4 | `会话压缩：日志与视图` |
| 5 | `SessionRecallDemo` | 5 | `会话召回：找回被压缩的内容` |
| 6 | `MultiStepAgentDemo` | 3 → 6 | 不变 |
| 7 | `ToolMemoryAdvisorDemo` | 4 → 7 | `Advisor 顺序（ChatMemory，2.1 起已弃用）` |
| 8 | `ToolSearchDemo` | 5 → 8 | 不变 |
| 9 | `SkillToolDemo` | 6 → 9 | 不变 |
| 10 | `TodoWriteToolDemo` | 7 → 10 | 不变 |

- `ChatMemoryDemo` 的类注释补一段：为什么弃用、继任者是谁、本示例为什么还留着（读旧代码；与示例 3 对照）。
  示例的运行代码不改。
- `ToolMemoryAdvisorDemo` 的类注释补一句：**这个结论只对 `ChatMemory` 成立**——`SessionMemoryAdvisor`
  默认在工具循环内侧（§2.3），会话日志一定会记下工具调用与结果，「模型看到什么」改由视图
  （`EventFilter` / 压缩）控制，见示例 4。示例的运行代码不改。
- 顺延的五个示例（`MultiStepAgentDemo`、`ToolMemoryAdvisorDemo`、`ToolSearchDemo`、`SkillToolDemo`、
  `TodoWriteToolDemo`）只改 `order()` 与类注释首行的「示例 N」自述，运行代码不动。
- `TodoWriteToolDemo` 里也用了 `MessageChatMemoryAdvisor`（让工具循环的每一轮共享同一段上下文），本次不动：
  它的主题是 TodoWrite，不在记忆主线上，加标注会分散注意力。

## 6. 示例设计（springai-boot-demo）

- 新类 `SessionJdbcDemo`（`@Component`，`order()` 返回 3——现有三个示例是 0 / 1 / 2，菜单里排第 4 项），
  标题 `会话落库（JDBC starter + H2，重启不丢）`。
- pom 加 `spring-ai-starter-model-session-jdbc`（版本走 BOM）与 `com.h2database:h2`（runtime，版本走
  `spring-boot-dependencies`）。
- `application.properties` 加：

  ```properties
  # ===== 会话落库：H2 文件库放在用户目录下，重启进程仍在；删掉该目录即重置 =====
  spring.datasource.url=jdbc:h2:file:${user.home}/.springai-boot-demo/sessions;DB_CLOSE_ON_EXIT=FALSE
  spring.datasource.driver-class-name=org.h2.Driver
  spring.ai.session.repository.jdbc.initialize-schema=always
  ```

  `initialize-schema=always` 是保险：Boot 的建表模式默认只对内嵌库自动执行，文件型 H2 算不算内嵌没有实测
  （§9）；脚本是 `CREATE TABLE IF NOT EXISTS`，设成 `always` 没有副作用。
- 流程（会话 id 固定 `boot-demo-user`）：
  1. 开场打印数据库位置与该会话已有多少条事件（0 条 = 首次运行）；
  2. 第一问：「我之前告诉过你我的工号吗？不知道就直说。」——首次运行答「不知道」，再次运行答得出「A1024」；
  3. 第二问：「请记住，我的工号是 A1024。」第三问：「我的工号是多少？」
  4. 用 `JdbcTemplate` 把 `AI_SESSION_EVENT` 里该会话的行打出来（`seq` / `event_type` / `message_type` /
     正文前 20 字）——让读者看见「日志就是表里的行」；
  5. 结尾提示：退出程序再运行本示例，第一问就能答对。
- 注入的三个 Bean 都来自 starter：`ChatClient`（原有）、`SessionService`（自动配置在 `JdbcSessionRepository`
  之上）、`JdbcTemplate`。类注释点明对比：agent-demo 里 `DefaultSessionService.builder().sessionRepository(..)`
  两行手动接线，这里一个构造器参数都不用管，仓库还从内存换成了数据库。
- `SessionAdvisors` 在构造器里建一次复用，仍按请求传入，不挂到共享 `ChatClient` 的默认 advisors 上
  （原因同 §5.1）。
- `AutoConfigInspectDemo` 增加 `DataSource`、`SessionRepository`、`SessionService` 三行（配置方式写
  「starter 自动配置（spring-ai-starter-model-session-jdbc）」）；「除了 ChatClient 是我们手写的」这句结论不变。

## 7. 测试策略

agent-demo 新增测试（与现有 `TodoWriteToolDemoTest` 同目录），测试源码里放一个 `ScriptedChatModel`：
不联网，按「prompt 最后一条消息」决定回什么（工具结果 → 最终回答；特定提问 → 请求工具调用；其余 → 回声）。
`getOptions()` 返回 `ToolCallingChatOptions.builder().build()`，并在注释里写明 §2.5 坑 1。桩模型与取证程序
里的那份同构，取证程序可以直接当参考实现。

先写测试、看到红、再实现。用例与断言：

| 用例 | 钉住的行为 | 关键断言 |
|---|---|---|
| `plainTurnsLogGrowsWhileViewShrinksAfterCompaction` | 示例 4 第一幕的条数表 | 5 轮的（日志, 视图）依次为 (2,2)(4,4)(6,6)(9,4)(11,6) |
| `compactionAppendsBoundaryWithoutRewritingLog` | 日志不被改写 | 第 4 轮后前 8 条事件的 id 与文本与第 3 轮后逐一相同；第 9 条类型为 `COMPACTION`、不带消息；视图的 id 依次是第 5–8 条 |
| `toolTurnIsLoggedWholeAndNeverSplitByCompaction` | 工具回合整条入日志、不被拆 | 一轮工具对话日志 4 条，类型依次 user / assistant（带 toolCalls）/ tool / assistant；压缩后视图恰为这 4 条 |
| `recallFindsEventsOutsideTheView` | 召回覆盖整份日志 | `searchEvents(keyword)` 命中已离开视图的 user 事件；召回回合后视图不含该事件、日志含；最终回答含暗号 |
| `summaryCompactionKeepsSyntheticSummaryTurnInView` | README「延伸」小节的依据 | 边界后 2 条 `isSynthetic()`，第一条 `isTurnStart()`；视图 4 条 |
| `lastNWindowBoundsThePromptWhileTheLogKeepsEverything` | README「延伸」另一段的依据 | 5 轮后日志 10 条、无 `COMPACTION` 事件；第 4、5 轮模型各收到 5 条消息；`lastN(3)` 与 `lastN(4)` 返回同一批事件 |
| `describeEventShowsIndexRoleTimeAndText` | 渲染 | 精确字符串；`COMPACTION` 事件不显示正文 |

条数与 id 都断言具体值，不用 `>=`；「id 与文本逐一相同」用来杀死「压缩其实改写了日志」这类反向改动。
变异测试按 `CONTRIBUTING.md`：改坏实现确认变红，再改回来，工作树干净。

boot-demo 不加测试（§4）。它的证据是两份真机输出：首次运行（已有 0 条、第一问答「不知道」）与再次运行
（已有 N 条、第一问答出 A1024），贴进 README 与本设计的「实施记录」。

## 8. 文档同步清单

| 文件 | 改动 |
|---|---|
| `springai-agent-demo/README.md` | 示例清单表改为 10 行；新增「示例 3 / 4 / 5 详解」（含真机输出）；示例 2 一行加弃用标注；原「示例 4 详解」改为「示例 7 详解」并注明结论仅对 `ChatMemory` 成立；原「示例 5 / 6 / 7 详解」改为 8 / 9 / 10，正文里的「菜单 6 / 7」「同示例 5」同步；新增「延伸：带摘要的压缩与 lastN 窗口」小节 |
| `springai-boot-demo/README.md` | 示例清单表加第 4 行；新增「会话落库」一节（依赖、properties、两份运行输出、如何重置）；「核心看点」对照表提一句会话仓库 |
| `README.md`（根） | 第②步「核心」表加 3 行并改号，「进阶」表改为 8–10；「建议先跑一遍示例 3「多步 Agent」」改为示例 6；第③步表加一行；第⑥步桥接表里「对话记忆（第 2 步）」一行改为「对话记忆 / 会话记忆（第 2 步）→ code-tui 换成文件仓库 + 按 token 预算触发的摘要压缩」 |
| `AGENTS.md` | 模块表里 `springai-agent-demo` 一行补「会话记忆」，`springai-boot-demo` 一行补「会话落库」 |
| `.github/workflows/ci.yml` | 「测试 springai-tamboui-demo」之后加一步「测试 springai-agent-demo」：`mvn -B -pl springai-agent-demo -am test`（沿用前两步的注释口径：必须带 `-pl`、必须带 `-am`）；失败时上传的 surefire 报告路径补上 agent-demo |

- 文档里的命令按「可直接复制运行」写并逐条实测；文内链接一律相对路径，外链用 §2.1 核实过的 `2.1-SNAPSHOT` 地址。
- 改号后全仓 grep `示例 ?[0-9]+`、`菜单 ?[0-9]+` 复核（`docs/superpowers/` 下的历史文档按当时编号书写，不改）。
- `CHANGELOG.md` 与 release notes 不在本分支动。

## 9. 风险与实施时必须真机确认的事项

1. **召回示例的模型不确定性**：DeepSeek 可能不调工具直接答「不知道」。已用提问引导兜底；若真机仍不稳定，
   备选是在系统提示里加一句，或把暗号换成更具体的词。README 的输出必须是真机跑出来的。
2. **文件型 H2 的自动建表与 `SessionService` Bean**：自动装配条件只是读了源码，没启动过。实施第一步先只加
   依赖与 properties、启动一次，确认 `JdbcSessionRepository` / `SessionService` 两个 Bean 在、表建出来了，再写示例。
3. **真机输出需要 API key**：`DEEPSEEK_API_KEY`（从 `~/.secrets` 引入环境变量，不落盘、不打印）。key 不可用时，
   agent-demo 三个示例的 README 输出先标「待补」，以单测输出作临时证据，不臆造。
4. **`spring-boot-starter-jdbc` 的副作用**：boot-demo 多出连接池（Hikari）与事务管理器，启动略慢；
   `spring.main.web-application-type=none` 不受影响。H2 文件库同一时间只允许一个进程打开，属预期。
5. **改号触点多**：§8 已列；漏改的表现是 README 里的编号与菜单对不上。
6. **CI 的 JDK 17 一侧没跑过 agent-demo 的测试**：本机 JDK 21 实测现有 `TodoWriteToolDemoTest` 6/6 通过
   （2026-11-25），JDK 17 一侧未实测。加进 CI 后若红，先修再继续。

## 10. 留给实施者的决定

- 示例输出的具体措辞、对齐宽度、是否使用少量符号（跟周围示例的风格）。
- 测试类的拆分（一个类或按示例拆三个）与桩模型的命名。
- README 各节的篇幅与顺序。
- 提交粒度。建议：每个示例与其测试一个提交；旧示例改号与标注一个提交；boot-demo 一个提交；文档一个提交。

## 附录 A 取证程序与实测输出

程序：[`dev/session-memory-evidence/SessionMemoryEvidence.java`](../../../dev/session-memory-evidence/SessionMemoryEvidence.java)。
不属于任何模块的构建；复跑命令写在类注释里。用脚本化模型，不联网、不需要 API key。

2026-11-25 实测输出节选（完整输出 128 行；`✓ 在视图 / 已离开视图` 是程序按 `EventFilter.active()` 现算的）：

**① 第 4、5 轮**——压缩后日志只多 1 条 `COMPACTION`，第 5 轮模型只收到 5 条：

```
── 第 4 轮结束后：日志 9 条 / 视图 4 条 / 模型本轮收到 7 条 [user, assistant, user, assistant, user, assistant, user]
   [ 1] MESSAGE    user      turnStart=true  synthetic=false   已离开视图      第1句
   [ 2] MESSAGE    assistant turnStart=false synthetic=false   已离开视图      回声：第1句
   [ 3] MESSAGE    user      turnStart=true  synthetic=false   已离开视图      第2句
   [ 4] MESSAGE    assistant turnStart=false synthetic=false   已离开视图      回声：第2句
   [ 5] MESSAGE    user      turnStart=true  synthetic=false ✓ 在视图        第3句
   [ 6] MESSAGE    assistant turnStart=false synthetic=false ✓ 在视图        回声：第3句
   [ 7] MESSAGE    user      turnStart=true  synthetic=false ✓ 在视图        第4句
   [ 8] MESSAGE    assistant turnStart=false synthetic=false ✓ 在视图        回声：第4句
   [ 9] COMPACTION (无消息)     turnStart=false synthetic=false   已离开视图      -
── 第 5 轮结束后：日志 11 条 / 视图 6 条 / 模型本轮收到 5 条 [user, assistant, user, assistant, user]
```

**② 工具回合**——4 条事件整回合留在视图里：

```
── 第 2 轮结束后（已压缩）：日志 7 条 / 视图 4 条 / 模型本轮收到 5 条 [user, assistant, user, assistant, tool]
   [ 1] MESSAGE    user      turnStart=true  synthetic=false   已离开视图      你好
   [ 2] MESSAGE    assistant turnStart=false synthetic=false   已离开视图      你好！
   [ 3] MESSAGE    user      turnStart=true  synthetic=false ✓ 在视图        北京天气怎么样？
   [ 4] MESSAGE    assistant turnStart=false synthetic=false ✓ 在视图        🔧[getWeather{"arg0":"北京"}]
   [ 5] MESSAGE    tool      turnStart=false synthetic=false ✓ 在视图        ↩ "北京：晴，26℃，微风"
   [ 6] MESSAGE    assistant turnStart=false synthetic=false ✓ 在视图        北京今天晴。
   [ 7] COMPACTION (无消息)     turnStart=false synthetic=false   已离开视图      -
```

**③ 召回**——`searchEvents` 命中已离开视图的事件；召回回合后它们仍未回到视图：

```
  searchEvents(query=暗号) 命中: [user:第1句，暗号是「海棠」, assistant:回声：第1句，暗号是「海棠」]
  最终回答: 你最早说的是：{"results":[{"timestamp":"2026-11-25T…
  模型每轮收到的消息类型: [user]
  模型每轮收到的消息类型: [user, assistant, user]
  模型每轮收到的消息类型: [user, assistant, user, assistant, user]
  模型每轮收到的消息类型: [user, assistant, user, assistant, user, assistant, user]
  模型每轮收到的消息类型: [user, assistant, user, assistant, user]
  模型每轮收到的消息类型: [user, assistant, user, assistant, user, assistant, tool]
── 召回那一轮结束后：日志 13 条 / 视图 8 条 / 模型本轮收到 7 条 [user, assistant, user, assistant, user, assistant, tool]
   [ 1] MESSAGE    user      turnStart=true  synthetic=false   已离开视图      第1句，暗号是「海棠」
   [ 9] COMPACTION (无消息)     turnStart=false synthetic=false   已离开视图      -
   [10] MESSAGE    user      turnStart=true  synthetic=false ✓ 在视图        我最早说的暗号是什么？
   [11] MESSAGE    assistant turnStart=false synthetic=false ✓ 在视图        🔧[conversation_search{"query":"暗号"}]
   [12] MESSAGE    tool      turnStart=false synthetic=false ✓ 在视图        ↩ {"results":[{"timestamp":"2026-11-25T03:58:4…
   [13] MESSAGE    assistant turnStart=false synthetic=false ✓ 在视图        你最早说的是：{"results":[{"timestamp":"2026-11-25T…
```

**④ 带摘要的压缩**——边界后追加两条 `synthetic=true` 的事件：

```
── 第 3 轮结束后：日志 9 条 / 视图 4 条 / 模型本轮收到 5 条 [user, assistant, user, assistant, user]
   [ 7] COMPACTION (无消息)     turnStart=false synthetic=false   已离开视图      -
   [ 8] MESSAGE    user      turnStart=true  synthetic=true  ✓ 在视图        Summarize the conversation we had so far.
   [ 9] MESSAGE    assistant turnStart=false synthetic=true  ✓ 在视图        【摘要】用户说了第1句和第2句。
  摘要模型收到的消息类型: [[system, user]]
  压缩后视图 = 日志的第 [8, 9, 5, 6] 条（摘要回合在前，保留回合在后）
```

**⑤ `lastN` 窗口**——不压缩，日志 10 条全在；第 4、5 轮模型各只收到 5 条：

```
===== ⑤ lastN 窗口（不做压缩，记忆 advisor 只读活跃视图里最近的 4 条） =====
  第 1 轮：模型收到 1 条，轮后日志 2 条 / 视图 2 条
  第 2 轮：模型收到 3 条，轮后日志 4 条 / 视图 4 条
  第 3 轮：模型收到 5 条，轮后日志 6 条 / 视图 6 条
  第 4 轮：模型收到 5 条，轮后日志 8 条 / 视图 8 条
  第 5 轮：模型收到 5 条，轮后日志 10 条 / 视图 10 条
  直接读活跃视图 lastN(4)：日志里的第 [7, 8, 9, 10] 条
  直接读活跃视图 lastN(3)：日志里的第 [7, 8, 9, 10] 条（落在回合中间，向前扩到回合起点）
```

**坑位**：

```
  [坑1] 桩模型 getOptions() 返回普通 ChatOptions：工具是否执行 = false，模型被调用次数 = 1（正常应为 2）
  [坑2] 同一个 CallResponseSpec 取第二次：未报错，但模型被调用次数 = 2（应为 1）
  [坑2b] 带工具循环的回合重取 → java.lang.IllegalStateException: No CallAdvisors available to execute
  [坑3] 请求未注册的工具 → java.lang.IllegalStateException: No ToolCallback found for tool name: getWeather
  [坑4] 对 COMPACTION 事件调 getMessageType() → java.lang.IllegalStateException: Event 'boundary' is a COMPACTION event and carries no message
```

## 实施记录

（实施后回填：真机输出、与设计的出入、§9 各项的验证结果。）

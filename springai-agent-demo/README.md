# springai-agent-demo —— Spring AI 智能体能力（原始 API · 纯 Java）

**不依赖 Spring Boot**，用 Spring AI 原始库演示智能体能力。`ChatClient` 等对象同样在
`AgentDemoApplication.main` 里手动创建。

## 运行

```bash
export DEEPSEEK_API_KEY=你的key
mvn -pl springai-agent-demo -am package
java -jar springai-agent-demo/target/springai-agent-demo.jar
```

## 示例清单

| 菜单项 | 示例 | 关键类 | 你将学到 |
|------|------|--------|---------|
| 1 | 工具调用 | `ToolCallingDemo` | 用 `@Tool` 定义方法、`tools(...)` 注册，让模型按需调用 Java 代码 |
| 2 | 对话记忆（ChatMemory，已弃用） | `ChatMemoryDemo` | 用 `MessageWindowChatMemory` + `MessageChatMemoryAdvisor` 实现多轮记忆；留着是为了读旧代码、与示例 3 对照 |
| 3 | 会话记忆 | `SessionMemoryDemo` | 会话是一份只追加的事件日志，模型看到的是日志的视图（ChatMemory 的继任者） |
| 4 | 会话压缩 | `SessionCompactionDemo` | 压缩只往日志追加一条边界、不改写日志；边界永远落在回合之间 |
| 5 | 会话召回 | `SessionRecallDemo` | 被压缩的内容仍在日志里，模型用 `conversation_search` 工具自己搜回来 |
| 6 | 多步 Agent | `MultiStepAgentDemo` | 模型自动规划并连续调用多个工具完成一个任务 |
| 7 | Advisor 顺序（ChatMemory，已弃用） | `ToolMemoryAdvisorDemo` | `ToolCallingAdvisor` 配合 `MessageChatMemoryAdvisor`，演示两者**顺序**决定工具调用的中间消息是否进入记忆（只对 ChatMemory 成立） |
| 8 | 工具搜索 | `ToolSearchDemo` | `ToolSearchToolCallingAdvisor` 实现“按需发现工具”，工具很多时省 token |
| 9 | Skill 技能 | `SkillToolDemo` | 第三方 `spring-ai-agent-utils` 的 `SkillsTool`：把“可复用的领域指令”按需注入对话 |
| 10 | TodoWrite 任务清单 | `TodoWriteToolDemo` | 第三方 `spring-ai-agent-utils` 的 `TodoWriteTool`：让 Agent 显式维护任务列表并实时展示进度 |

> Spring AI 2.1.0 起 `ChatMemory`、`MessageWindowChatMemory`、`MessageChatMemoryAdvisor` 都已标弃用（3.0 移除），
> 官方继任者是**会话记忆（Session Memory）**。示例 2、7 保留旧写法是为了对照，新项目请用示例 3–5 的写法。
> 会话示例只多两个依赖：`spring-ai-session` 与 `spring-ai-session-advisor`（都不依赖 Spring Boot，版本走 `spring-ai-bom`）。

## 示例 3 详解：会话记忆（Session Memory）

**是什么**：一个会话（sessionId）对应一份**只追加的事件日志**（`SessionEvent`，每条带 id / 时间戳 / 事件类型 /
消息类型），模型看到的是这份日志的「视图」。**为什么存在**：`ChatMemory` 是「每个会话一个 `List<Message>`，
窗口一淘汰就没了」；事件日志不丢历史，才能在其上做压缩（示例 4）和召回（示例 5）。
**什么时候用**：需要多轮记忆的新项目。

接线两步：建会话服务（这里用内存仓库，进程退出即丢；落库见 `springai-boot-demo` 的「会话落库」示例），
再按请求带上会话 advisors 与会话 id：

```java
SessionService sessions = DefaultSessionService.builder()
        .sessionRepository(InMemorySessionRepository.builder().build())
        .build();
List<Advisor> sessionAdvisors = SessionAdvisors.builder(sessions).build();   // 回合 advisor + 记忆 advisor

chatClient.prompt()
        .user("我最喜欢的编程语言是 Java，请记住。")
        .advisors(sessionAdvisors)
        .advisors(SessionAdvisors.session(sessionId))   // 这次请求用哪个会话
        .call()
        .content();
```

和示例 2 是同一个两轮场景，跑出来是这样（真实运行片段；id 与时间每次不同）：

```
第1轮 问：我最喜欢的编程语言是 Java，请记住。
第1轮 答：好的，我记住了：你最喜欢的编程语言是 **Java**。以后交流时我会记着这一点。

第 2 轮请求会带上这段历史（2 条，模型看到的就是它们）：
  #1  user       8e2db4a5  12:07:31.047  我最喜欢的编程语言是 Java，请记住。
  #2  assistant  559fdebd  12:07:32.142  好的，我记住了：你最喜欢的编程语言是 **Java**。以后交流时我会记着这一点。

第2轮 问：我刚才说我最喜欢的编程语言是什么？
第2轮 答：你刚才说你最喜欢的编程语言是 **Java**。

会话 user-1 的事件日志（4 条，只追加、不修改）：
  #1  user       8e2db4a5  12:07:31.047  我最喜欢的编程语言是 Java，请记住。
  #2  assistant  559fdebd  12:07:32.142  好的，我记住了：你最喜欢的编程语言是 **Java**。以后交流时我会记着这一点。
  #3  user       2d10dd85  12:07:32.143  我刚才说我最喜欢的编程语言是什么？
  #4  assistant  8eae0833  12:07:33.022  你刚才说你最喜欢的编程语言是 **Java**。
每条事件自带：id（UUID，上面显示前 8 位）、所属会话、时间戳、事件类型（MESSAGE）、消息类型（user / assistant / tool）
```

**实现要点：**
- `SessionAdvisors.builder(sessions).build()` 一次建好两个 advisor：`SessionTurnAdvisor` 包住整个回合
  （回合结束后做压缩，本示例先不配），`SessionMemoryAdvisor` 每一轮把视图拼进 prompt，再把新的问与答追加进日志。
- 会话 id 的 context key 与 `ChatMemory.CONVERSATION_ID` 是同一个（`chat_memory_conversation_id`），
  所以示例 2 的两轮场景可以原样搬过来对照。
- 会话 advisors 一律**按请求传入**，不挂到共享 `ChatClient` 的 `defaultAdvisors` 上：会话记忆 advisor 要求
  每个请求都带会话 id，漏了直接抛 `IllegalStateException`，挂成默认会让其它示例全部失败。
- 参考官方文档：[Session API](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/memory/session/index.html)、
  [Chat Memory to Session Memory（迁移对照）](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/upgrading/session-memory.html)。

## 示例 4 详解：会话压缩——日志与视图

**是什么**：一轮结束后，如果视图里的回合数超过阈值，就往日志末尾**追加一条 `COMPACTION` 边界事件**，
记下从这一刻起视图由哪些事件组成。**为什么存在**：日志只增不减，而模型的上下文窗口有限；删旧消息会丢历史，
追加边界既缩小了视图，又完全不改日志。**什么时候用**：长对话、Agent 的长任务。

```java
List<Advisor> advisors = SessionAdvisors.builder(sessions)
        .compaction(new TurnCountTrigger(3),                              // 视图超过 3 个回合就压缩
                WindowCompactionStrategy.builder().lastTurns(2).build())  // 只留最近 2 个回合
        .build();
```

触发条件与保留窗口都按**回合**数计（一个回合 = 一条用户消息及其后的全部内容，含工具调用）。
第一幕连聊 5 轮、每轮请它记住一件事：

```
轮次   日志   视图
  1      2      2
  2      4      4
  3      6      6
  4      9      4    ← 第 4 轮后触发压缩：日志多 1 条边界，视图缩回最近 2 个回合
  5     11      6    ← 第 5 轮发出时，模型拿到的是视图里的 4 条历史 + 新提问，不是日志的 9 条

事件日志（11 条，只增不减）——✓ 还在视图里，✗ 已被压缩覆盖：
  ✗ #1  user       c7bcc3f3  12:07:33.861  请记住：我住在杭州。  ← 已被压缩覆盖：仍在日志里，可召回（见示例 5）
  ✗ #2  assistant  03a23cee  12:07:35.495  好的，我记住了：你住在杭州。 需要说明的是，我只能在**当前这次对话**中记住这个信息——如果你之后开启新的对话，我是不…  ← 已被压缩覆盖：仍在日志里，可召回（见示例 5）
  ✗ #3  user       664b2368  12:07:35.496  请记住：我养了一只猫，叫团子。  ← 已被压缩覆盖：仍在日志里，可召回（见示例 5）
  ✗ #4  assistant  cbb7a423  12:07:37.416  好的，记住了：你养了一只猫，叫**团子**。 现在这条对话里我有两个关于你的信息： - 你住在**杭州** - 你养了一…  ← 已被压缩覆盖：仍在日志里，可召回（见示例 5）
  ✓ #5  user       c5ce6817  12:07:37.416  请记住：我周末喜欢爬山。
  ✓ #6  assistant  1dc1dc99  12:07:39.668  好的，记住了：你周末喜欢**爬山**。 现在这条对话里关于你的信息有三条： - 住在**杭州** - 养了一只猫，叫**…
  ✓ #7  user       26451b8c  12:07:39.669  请记住：我最近在学 Rust。
  ✓ #8  assistant  431e25c7  12:07:41.450  好的，记住了：你最近在学 **Rust**。 现在这条对话里关于你的信息有这四条： - 住在**杭州** - 养了一只猫…
  ✗ #9  COMPACTION  47306105  12:07:41.453  ← 压缩边界：只追加这一条，上面任何一条都没被改写
  ✓ #10  user       c0098690  12:07:41.454  请记住：我最喜欢的编程语言是 Java。
  ✓ #11  assistant  83aad59f  12:07:44.652  好的，记住了：你最喜欢的编程语言是 **Java**。 当前对话里关于你的信息现在有五条： - 住在**杭州** - 养…
```

第 3 轮后不压缩，因为触发条件是「回合数**大于** 3」。日志从 6 条变 9 条：多出来的 3 条里，2 条是第 4 轮的
一问一答，1 条是边界；前 6 条原样不动。

第二幕换一段会话，第 2 轮调用工具，配置改成「超过 1 个回合就压缩、只留最近 1 个回合」（节选）：

```
  ✗ #1  user       43b3c4ee  12:07:44.727  你好  ← 已被压缩覆盖：仍在日志里，可召回（见示例 5）
  ✓ #3  user       548c1a35  12:07:45.508  北京天气怎么样？
  ✓ #4  assistant  bd01d74c  12:07:46.018  🔧 getWeather({"arg0": "北京"})
  ✓ #5  tool       9f7a1b51  12:07:46.022  "北京：晴，26℃，微风"
  ✓ #6  assistant  8e85590e  12:07:47.040  北京现在天气 **晴**，气温约 **26℃**，风力为微风，整体比较舒适，适合外出活动。…
  ✗ #7  COMPACTION  9f0ec923  12:07:47.041  ← 压缩边界：只追加这一条，上面任何一条都没被改写
压缩后的视图恰好是整个工具回合（4 条）：压缩按整回合切，不会让模型看到一个没有结果的工具调用。
```

**实现要点：**
- 工具调用和工具结果**一定**入日志（不受过滤影响）。所以带工具的一轮在日志里是 4 条：
  `user → assistant（🔧 请求调用）→ tool（结果）→ assistant（最终回答）`。
- 这和示例 7 的结论正好相反：`MessageChatMemoryAdvisor` 默认在工具循环**外层**，只记提问与最终回答；
  `SessionMemoryAdvisor` 默认在工具循环**内侧**，每一轮都经过它。「模型看到什么」改由视图控制，不再靠 advisor 的先后。
- 入参里的 `arg0` 就是 `WeatherTools.getWeather` 的 `city` 形参：本模块编译没开 `-parameters`，形参在工具
  schema 里叫 `arg0`，模型照 schema 传参。
- 条数由单测钉住（脚本化模型，不需要 API key）：`SessionCompactionDemoTest`。
- 参考官方文档：[Compaction](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/memory/session/compaction.html)。

## 示例 5 详解：会话召回——找回被压缩的内容

**是什么**：Spring AI 自带的会话搜索工具 `SessionRecallTools`（工具名 `conversation_search`），模型按关键词
搜**整份日志**。**为什么存在**：压缩拿走的是「模型看到的」，不是「存下来的」；被覆盖的内容仍在日志里，没理由
找不回来。**什么时候用**：对话已经压缩过，而用户问起了更早的事。

```java
List<Advisor> advisors = SessionAdvisors.builder(sessions)
        .compaction(new TurnCountTrigger(3), WindowCompactionStrategy.builder().lastTurns(2).build())
        .sessionIdInToolContext(true)     // advisor 把会话 id 放进工具上下文，调用方不必每次手写 toolContext
        .build();

chatClient.prompt()
        .user("我最早告诉你的项目暗号是什么？")
        .tools(SessionRecallTools.builder(sessions).build())
        .advisors(advisors)
        .advisors(SessionAdvisors.session(sessionId))
        .call()
        .content();
```

前 4 轮：第 1 轮记住暗号，其余闲聊；第 4 轮后触发压缩，暗号那一轮（日志第 1、2 条）离开视图。第 5 轮问暗号：

```
  ┌─ 第 1 轮 ──────────────
  │ ↗ 本轮模型可用工具: conversation_search
  │ ↘ 模型决定: 请求调用工具 → conversation_search({"query": "暗号,项目暗号,暗语", "match": "any"})
  └────────────────────────
  ┌─ 第 2 轮 ──────────────
  │ ↗ 本轮模型可用工具: conversation_search
  │ ↩ 工具返回: {"results":[{"timestamp":"2026-11-25T04:07:47.888351Z","type":"user","text":"请记住：我的项目暗号是『海棠』。"},{"timestamp":"2026-11-25T04:07:49.125219Z","type":"assistant","t…
  │ ↘ 模型决定: 直接回答 → 你最早告诉我的项目暗号是 **『海棠』**。 这条记录在本次会话最开头：你说“请记住：我的项目暗号是…
  └────────────────────────

第 5 轮 答：你最早告诉我的项目暗号是 **『海棠』**。

第 4 轮压缩后：日志 9 条 / 视图 4 条——暗号那一轮已不在视图里。
召回之后：日志 13 条 / 视图 8 条。旧事件没有回到视图：进上下文的只是搜索带回的一段摘录，暗号那一轮仍然只在日志里。
```

工具返回的是一段 JSON 摘录，不是把旧事件塞回上下文：召回之后视图只多了这一轮自己的 4 条
（问、请求调用、工具结果、答）。

**实现要点：**
- `SessionRecallTools` 从**工具上下文**里取会话 id。`sessionIdInToolContext(true)` 让 advisor 自动放进去；
  不开的话调用方得每次手写 `.toolContext(Map.of(SessionRecallTools.SESSION_ID_CONTEXT_KEY, sessionId))`，
  漏了工具循环会直接报错（`ToolContext is required by the method as an argument`）。
- 提问里带了「用会话搜索工具查」的引导，让真机演示稳定；不引导时模型可能直接答「不知道」。
- 观察手段同示例 8、9：示例自带的 `RoundLoggingAdvisor` 按轮次打印。工具循环关掉了自带历史，
  下一轮请求里只带工具结果，所以「工具返回」那一行就是 `conversation_search` 原样返回的 JSON。
- 参考官方文档：[Recall](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/memory/session/recall.html)。

## 延伸：带摘要的压缩与 lastN 窗口

两段都不进示例正文，免得冲淡主认知。下面的数字取自单测 `SessionMemoryExtrasTest`（脚本化模型，不需要 API key）。

**给压缩配一个摘要器**：被覆盖的回合不是凭空消失，而是由模型浓缩成一条摘要留在视图里——code-tui 的自动压缩
就是这么做的。

```java
List<Advisor> advisors = SessionAdvisors.builder(sessions)
        .compaction(new TurnCountTrigger(2), WindowCompactionStrategy.builder().lastTurns(1)
                .summarizer(ChatModelSummarizer.builder(chatModel).build()).build())
        .build();
```

3 轮之后：日志 9 条 = 6 条对话 + 1 条边界 + 2 条 `synthetic` 事件（一条是摘要用的提示词，一条是摘要正文）；
视图 4 条 = 摘要回合 + 保留的那个回合（摘要回合在前）。摘要多一次模型调用。

**不开压缩，只让模型看到最近 N 条**：`EventFilter.lastN` 是 `maxMessages` 的替代物——窗口按事件数计，
但切口对齐回合。

```java
List<Advisor> advisors = SessionAdvisors.builder(sessions)
        .memory(memory -> memory.eventFilter(EventFilter.active().toBuilder().lastN(4).build()))
        .build();
```

5 轮之后：日志 10 条、没有任何边界；第 4、5 轮模型各只拿到 5 条消息（窗口里的 4 条历史 + 新提问）。
窗口落在回合中间时会向前扩到回合起点：直接读活跃视图，`lastN(3)` 与 `lastN(4)` 返回的是同一批事件。

## 示例 7 详解：Advisor 顺序（ChatMemory，已弃用）

> **已弃用**：`MessageChatMemoryAdvisor` 在 2.1.0 已标弃用（3.0 移除）。而且下面的结论**只对 ChatMemory 成立**——
> 它的继任者 `SessionMemoryAdvisor` 默认就在工具循环内侧，会话日志一定会记下工具调用与工具结果，
> 「模型看到什么」改由视图控制，见示例 4。本节保留供读旧代码。

Spring AI **2.0 已移除 ChatModel 内部的工具执行**（1.x 时工具循环在 ChatModel 内部完成），
现在工具调用统一由 `ToolCallingAdvisor` 在 **advisor 链**里完成——只要用 `.tools(...)` 注册了工具，
`ChatClient` 就会自动注册一个 `ToolCallingAdvisor`（最多一个）。既然工具循环在 advisor 链里，
记忆 advisor 能否“看见”每一轮工具调用，就取决于两者的 `getOrder()`（**order 越小越靠外层**）：

| 顺序 | 记忆位置 | 工具调用中间消息是否入记忆 | 适用场景 |
|------|---------|--------------------------|---------|
| 记忆 order < 工具 order（**默认**） | 外层，包住整个工具循环 | **否**，只记“提问 + 最终回答” | 想要干净精简的对话历史 |
| 记忆 order > 工具 order | 内层，处于工具循环之内 | **是**，连“请求调用工具/工具返回”都记下 | 想持久化完整工具轨迹（审计/调试/复盘） |

运行该示例会用同一个问题分别跑两种顺序，并打印各自记忆里实际存了几条消息——
默认顺序存 2 条，反转顺序存 6 条，一跑便知。参考官方文档
[Recursive Advisors / ToolCallingAdvisor](https://docs.spring.io/spring-ai/reference/api/advisors-recursive.html#toolcallingadvisor)。

## 示例 8 详解：工具搜索 / 动态工具发现（ToolSearchToolCallingAdvisor）

工具一多，传统做法把**所有**工具定义一次性发给模型，既费 token 又容易选错。
`ToolSearchToolCallingAdvisor` 改为「按需发现（progressive tool disclosure）」：

1. 启动时把你注册的全部工具建立**索引**，但不直接暴露给模型；
2. 只给模型一个内置元工具 `toolSearchTool`，让它用自然语言**搜索**所需工具；
3. 把搜到的工具**注入**后，模型再真正调用。

示例注册了 9 个工具（请假/会议室/快递/翻译/乘法/时间/天气…），只问一个“年假”问题。
为了把**完整交互过程**看清楚，示例自带一个小型 `RoundLoggingAdvisor`，按“轮次”整齐打印
（用 `System.out`，不走日志框架，所以没有 `DEBUG ... -` 前缀、不会与其它输出错乱）。真实运行片段：

```
📇 已为本次会话建立工具索引，共 9 个工具（但不会全部发给模型）
  ┌─ 第 1 轮 ──────────────
  │ ↗ 本轮模型可用工具: toolSearchTool                       ← 一开始只给“搜索工具”
  │ ↘ 模型决定: 请求调用工具 → toolSearchTool({"arg0":"查询员工年假余额 张三", ...})
  └────────────────────────
  │ 🔎 模型搜索工具: query="查询员工年假余额 张三" → 命中 []   ← regex 没匹配上
  ┌─ 第 2 轮 ──────────────
  │ ↗ 本轮模型可用工具: toolSearchTool
  │ ↘ 模型决定: 请求调用工具 → toolSearchTool({"arg0":"年假 查询 员工 假期余额", ...})  ← 模型自己换词重试
  └────────────────────────
  │ 🔎 模型搜索工具: query="年假 查询 员工 假期余额" → 命中 [queryAnnualLeave, ...]
  ┌─ 第 3 轮 ──────────────
  │ ↗ 本轮模型可用工具: queryAnnualLeave, checkMeetingRoom, ...  ← 命中的工具被注入进来
  │ ↘ 模型决定: 请求调用工具 → queryAnnualLeave({"arg0":"张三"})
  └────────────────────────
  ┌─ 第 4 轮 ──────────────
  │ ↘ 模型决定: 直接回答 → 张三当前剩余年假为 7 天。
  └────────────────────────
```

可见第 1 轮模型手里**只有** `toolSearchTool`（其余 8 个工具没发给它），搜索命中后工具才被注入——
这正是“按需发现”省 token 的原理。（顺带还能看到：regex 索引第一次没命中时，模型会**自己换关键词重试**，
这也是为什么生产环境常改用 **vector 语义索引**，对自然语言更鲁棒。）

**实现要点：**
- 依赖：`spring-ai-tool-search-advisor`（提供 advisor）+ `spring-ai-tool-search-tool`（提供 `ToolIndex`）。
- 索引实现三选一：**regex**（本示例用，关键词匹配，零额外依赖）、**lucene**、**vector**（语义检索，需向量模型）。
- `LoggingToolIndex` 装饰器包住 `RegexToolIndex`，打印每次工具搜索（🔎 行）。
- `RoundLoggingAdvisor` 是示例自带的一个简单 `CallAdvisor`，放在工具搜索 advisor 的**内层**（order 更大）
  才能拦截到循环里每一轮；用 `System.out` 打印，输出整齐、无日志前缀。
- 参考官方文档
  [Tool Calling / Tool Search Tool](https://docs.spring.io/spring-ai/reference/api/tools/tool-search-tool.html)。

## 示例 9 详解：Skill 技能（SkillsTool）

> 依赖第三方社区库 `org.springaicommunity:spring-ai-agent-utils`（针对 Spring AI 2.0），
> 官方文档：<https://spring-ai-community.github.io/spring-ai-agent-utils/v0.13.0/tools/SkillsTool>。

**什么是 Skill？** 一个含 `SKILL.md` 的文件夹。`SKILL.md` = 「YAML frontmatter（`name` + `description`）」+「正文指令」，
是一份**可复用的“操作说明书 / 领域知识模块”**。本示例的 `skills/git-commit-message/SKILL.md` 封装了
“如何按 Conventional Commits 规范写 git 提交信息”的完整规则。

**和普通 `@Tool` 工具的区别：** 普通工具执行 Java 代码返回数据；Skill 工具本质是**按需把一大段指令/提示词注入对话**。
模型平时只看到技能的简短 `name + description`（很省 token）；当它判断某技能有用时，调用名为 `Skill` 的工具、
**只传技能名**，工具便返回该 `SKILL.md` 的**完整正文**；模型读到详细指令后再产出结果——一种“渐进式披露
（progressive disclosure）”的提示词工程。

运行该示例（菜单 9），完整执行过程清晰可见：

```
🧰 已加载技能工具: Skill（模型一开始只能看到各技能的【名字+描述】，看不到正文，很省 token）

问：帮我给这次改动写一条规范的 git 提交信息：给登录接口加了图形验证码……

  ┌─ 第 1 轮 ──────────────
  │ ↗ 本轮模型可用工具: Skill
  │ ↘ 模型决定: 请求调用工具 → Skill({"command": "git-commit-message"})   ← 只凭描述就决定调用该技能
  └────────────────────────
  │ 🛠 技能工具被调用，入参: {"command": "git-commit-message"}
  │ 📜 技能返回内容（SKILL.md 正文，节选）:                                  ← 此刻才注入详细指令
  │     Base directory for this skill: .../skills/git-commit-message
  │     # 规范化 Git 提交信息  ...（Conventional Commits 规则全文）
  ┌─ 第 2 轮 ──────────────
  │ ↘ 模型决定: 直接回答 → feat(login): 登录接口增加图形验证码 ...          ← 读完指令后按规范作答
  └────────────────────────
```

**实现要点：**
- 依赖：`org.springaicommunity:spring-ai-agent-utils:0.13.0`。
- `SkillsTool.builder().addSkillsDirectory("…/skills").build()` 返回一个 `ToolCallback`（工具名固定为 `Skill`）。
  它用 `Files.walk` 在**文件系统**上递归查找 `SKILL.md`，所以技能目录要在运行时真实存在于磁盘
  （示例的 `resolveSkillsDir()` 会依次尝试仓库根目录 / 模块目录等候选路径）。
- `LoggingSkillCallback` 装饰器包住该 `ToolCallback`，打印技能的**入参**与**返回正文**。
- `RoundLoggingAdvisor`（同示例 8）按轮次打印交互，放在工具循环**内层**（order 更大）才能拦到每一轮。

## 示例 10 详解：TodoWrite 任务清单（TodoWriteTool）

> 依赖第三方社区库 `org.springaicommunity:spring-ai-agent-utils`（针对 Spring AI 2.0），
> 官方文档：<https://spring-ai-community.github.io/spring-ai-agent-utils/latest-snapshot/tools/TodoWriteTool/>。

`TodoWriteTool` 是给 Agent 自己用的“任务进度板”，不是业务系统里的待办事项存储。当用户给出一个复杂目标时，
模型可以先调用名为 `TodoWrite` 的工具，把任务拆成结构化清单；执行过程中再不断更新状态，让人能看见
Agent 当前在做哪一步、完成了多少。

Todo 项只有 3 种状态：

| 状态 | 含义 |
|------|------|
| `pending` | 还没开始 |
| `in_progress` | 正在处理 |
| `completed` | 已完成 |

工具会校验每次提交的清单：任务内容不能为空，`activeForm` 不能为空，状态必须合法，并且**同一时间最多只能有一个
`in_progress`**。这能防止模型把多个步骤同时标成“正在做”，让进度展示更可信。

运行该示例（菜单 10）时，在真实终端里会看到一个固定刷新的控制台面板。模型第一次确认任务后，
任务行会稳定显示；后续 TodoWrite 更新只改变状态符号和进度数字，不再每次追加整段日志把屏幕顶走：

```
TodoWrite 任务进度
任务: 请帮我组织一个小型开发计划：为在线商城增加优惠券功能……
进度: 0/4 (0%)

任务清单
  [>] 拆解优惠券功能需求
  [ ] 设计数据模型和接口
  [ ] 编写测试点
  [ ] 整理发布注意事项

模型交互
  当前轮次: 1
  可用工具: TodoWrite
  状态: 已收到模型响应
  大模型返回: (无文本，见工具调用请求)
  工具调用请求: TodoWrite({"todos":[...]})
  最近工具: TodoWrite 返回: Todos have been modified successfully...
```

下一次刷新时仍是同一个面板，只改状态：

```
TodoWrite 任务进度
任务: 请帮我组织一个小型开发计划：为在线商城增加优惠券功能……
进度: 1/4 (25%)

任务清单
  [✓] 拆解优惠券功能需求
  [>] 设计数据模型和接口
  [ ] 编写测试点
  [ ] 整理发布注意事项

模型交互
  当前轮次: 2
  可用工具: TodoWrite
  状态: 已收到模型响应
  大模型返回: 我会继续更新任务状态。
  工具调用请求: TodoWrite({"todos":[...]})
  最近工具: TodoWrite 入参: {"todos":[...]}
```

**实现要点：**
- 依赖 `org.springaicommunity:spring-ai-agent-utils:0.13.0`（与仓库统一，父 pom 管理）。
- 控制台动画使用 `org.jline:jline-terminal` 的 `Display`。它会保留上一帧和下一帧，利用光标移动、
  局部清行等终端能力增量刷新，不再手写 `clear screen` 整屏重画；非交互环境会退回普通文本输出。
- `TodoWriteTool.builder().todoEventHandler(...).build()` 创建一个普通工具对象，像 `@Tool` 工具一样传给
  `.tools(todoWriteTool)`。
- `todoEventHandler` 会在模型每次调用 `TodoWrite` 后收到完整任务清单；本示例把它交给 `ConsoleDashboard`
  原地刷新状态，上游 Spring Boot 示例则可以把它发布成事件、推给 WebSocket 或 UI。
- `RoundLoggingAdvisor` 更新面板里的当前轮次、可用工具、大模型返回文本和工具调用请求；`LoggingTodoWriteTool`
  包住真正的 TodoWrite callback，更新“最近工具”摘要。两者都不再不停追加日志。
- 示例显式注册 `ToolCallingAdvisor`，并搭配 `MessageChatMemoryAdvisor`，让工具循环中的多轮 Todo 更新
  能持续使用同一段上下文。

## 工具是怎么定义的

见 `tools/` 目录：在普通类的方法上加 `@Tool(description=...)`、参数加 `@ToolParam(description=...)`，
description 就是给模型看的“说明书”，模型据此决定**何时调用、传什么参数**。注意：工具机制不依赖 Spring 容器，
普通对象 `new` 出来传给 `.tools(...)` 即可。

- `DateTimeTools`：获取当前时间、计算日期差
- `WeatherTools`：查询天气（演示用，返回假数据）
- `OfficeTools`：一组数量较多的办公类工具（请假/会议室/快递/翻译/乘法），用于示例 8 的工具搜索

## MCP 去哪了？

MCP 客户端配置较重（需外部 Server），用 Spring Boot starter 几行配置就能搞定，因此**迁到了
`springai-boot-demo`**，在那里演示更合适。

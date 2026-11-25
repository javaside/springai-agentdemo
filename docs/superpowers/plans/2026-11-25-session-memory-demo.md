# Session Memory 教学示例 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `springai-agent-demo` 新增会话记忆 / 会话压缩 / 会话召回三个核心示例（菜单 3 / 4 / 5），在
`springai-boot-demo` 新增会话落库示例，旧 `ChatMemory` 示例保留并标注弃用，agent-demo 的测试进 CI，文档同步。

**Architecture:** agent-demo 保持纯 Java：`DefaultSessionService` + `InMemorySessionRepository` 手动接线，会话
advisors 一律按请求传入。每个示例把「跑对话、读日志与视图」抽成 `static` 包内方法，`run()` 只负责打印；
行为由测试源码里的脚本化 `ChatModel`（不联网）钉住。boot-demo 由 starter 自动装配 `SessionService`，用
文件型 H2 演示重启不丢，不加测试，以真机输出为证。

**Tech Stack:** Java 17、Maven、Spring AI 2.1.0（`spring-ai-session` / `spring-ai-session-advisor` /
`spring-ai-starter-model-session-jdbc`，版本走 `spring-ai-bom`）、Spring Boot 4.2.0、H2、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-11-25-session-memory-demo-design.md`（已批准；执行者两份都读）

## Global Constraints

- Java 17 语言级别；注释、输出、文档、提交信息全中文；提交前缀 `feat:` / `test:` / `docs:` 等；一次提交只做一件事，功能与测试同提交。
- 分支 `feature/session-memory-demo`，不直接提交 main。
- agent-demo 新增依赖仅 `spring-ai-session`、`spring-ai-session-advisor`（BOM 管版本，pom 不写版本号）；**禁止**引入任何 Spring Boot 依赖。
- boot-demo 新增依赖仅 `spring-ai-starter-model-session-jdbc`（compile）与 `com.h2database:h2`（runtime），都不写版本号。
- 测试命令：模块级 `mvn -pl springai-agent-demo -am test`；单类
  `mvn -pl springai-agent-demo -am test -Dtest='XxxTest' -Dsurefire.failIfNoSpecifiedTests=false`。禁止整仓 `mvn test`。
- Session API 以本机 `~/.m2` 的 2.1.0 sources jar 为准（`unzip` 到 /tmp 可读），不得臆造方法名；
  `dev/session-memory-evidence/SessionMemoryEvidence.java` 是可运行的参考实现。社区版类名
  （`SlidingWindowCompactionStrategy` 等）在官方 jar 里不存在。
- 四个坑（spec §2.5）：桩 `ChatModel.getOptions()` 必须返回 `ToolCallingChatOptions`；`CallResponseSpec` 只取一次值；
  模型请求未注册的工具会抛异常；对不带消息的事件（`COMPACTION` 边界）调 `getMessage()` / `getMessageType()`
  会抛 `IllegalStateException`，必须先判 `hasMessage()`。
- 会话 advisors 按请求传入，不挂到共享 `ChatClient` 的 `defaultAdvisors`（缺会话 id 会抛 `IllegalStateException`）。
- 文档内链接相对路径；官方文档外链一律用 `https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/...`（稳定版路径 404）。
- 真机输出需要 `DEEPSEEK_API_KEY`：`source ~/.secrets` 引入环境变量，key 不落盘、不打印、不进文档。key 不可用时，
  README 对应输出标「（待补：需 DEEPSEEK_API_KEY）」，不臆造。
- 不动 `CHANGELOG.md`、release notes、版本号、`springai-code-tui`。

## Review Focus

1. **示例在同一个 `CallResponseSpec` 上取两次值**（重复调模型，或抛 `No CallAdvisors available to execute`）
   → 每个示例的测试断言脚本化模型被调用的次数与每轮消息数：`promptSizes()`（Task 1–3）。
2. **会话 advisors 被误挂成共享 `ChatClient` 的默认 advisors**（其它示例会因缺会话 id 全部失败）
   → `AgentDemoApplicationTest.sharedChatClientNeedsNoSessionId`（Task 5）。
3. **召回那一问模型不调工具、直接答「不知道」** → Task 3 的真机验证步骤（提问里带引导，输出必须真机跑出来）。
4. **文件型 H2 没自动建表 / `SessionService` Bean 不在** → Task 6 先探测（只加依赖与配置，看菜单 1 的输出），再写示例。
5. **改号漏改**（README 编号与菜单对不上）→ `AgentDemoApplicationTest.demosAreNumberedOneToTenInMenuOrder`（Task 5）
   + Task 9 的编号复核步骤。

## File Structure

```
springai-agent-demo/
├── pom.xml                                              # Task 1
└── src/
    ├── main/java/io/github/javaside/springai/agent/
    │   ├── AgentDemoApplication.java                    # Task 1 登记；Task 5 抽 buildChatClient()/demos()
    │   └── demo/
    │       ├── SessionDemoSupport.java                  # Task 1 三个示例共用的接线
    │       ├── SessionMemoryDemo.java                   # Task 1 示例 3
    │       ├── SessionCompactionDemo.java               # Task 2 示例 4
    │       ├── SessionRecallDemo.java                   # Task 3 示例 5
    │       ├── ChatMemoryDemo.java                      # Task 5 标题 / 注释
    │       ├── ToolMemoryAdvisorDemo.java               # Task 5 标题 / 注释 / order
    │       ├── MultiStepAgentDemo.java                  # Task 5 order / 注释
    │       ├── ToolSearchDemo.java                      # Task 5 order / 注释
    │       ├── SkillToolDemo.java                       # Task 5 order / 注释
    │       └── TodoWriteToolDemo.java                   # Task 5 order / 注释
    └── test/java/io/github/javaside/springai/agent/
        ├── AgentDemoApplicationTest.java                # Task 5
        └── demo/
            ├── ScriptedChatModel.java                   # Task 1 测试桩
            ├── SessionMemoryDemoTest.java               # Task 1
            ├── SessionCompactionDemoTest.java           # Task 2
            ├── SessionRecallDemoTest.java               # Task 3
            └── SessionMemoryExtrasTest.java             # Task 4
springai-boot-demo/
├── pom.xml                                              # Task 6
└── src/main/
    ├── java/io/github/javaside/springai/boot/demo/
    │   ├── AutoConfigInspectDemo.java                   # Task 6
    │   └── SessionJdbcDemo.java                         # Task 7
    └── resources/application.properties                 # Task 6
.github/workflows/ci.yml                                 # Task 8
springai-agent-demo/README.md、springai-boot-demo/README.md、README.md、AGENTS.md   # Task 9
docs/superpowers/specs/2026-11-25-session-memory-demo-design.md                    # Task 10 回填实施记录
```

---

### Task 1: agent-demo 接入 Session 依赖，新增示例 3「会话记忆」

**Files:**
- Modify: `springai-agent-demo/pom.xml`（`spring-ai-client-chat` 依赖之后）
- Create: `springai-agent-demo/src/test/java/io/github/javaside/springai/agent/demo/ScriptedChatModel.java`
- Create: `springai-agent-demo/src/test/java/io/github/javaside/springai/agent/demo/SessionMemoryDemoTest.java`
- Create: `springai-agent-demo/src/main/java/io/github/javaside/springai/agent/demo/SessionDemoSupport.java`
- Create: `springai-agent-demo/src/main/java/io/github/javaside/springai/agent/demo/SessionMemoryDemo.java`
- Modify: `springai-agent-demo/src/main/java/io/github/javaside/springai/agent/AgentDemoApplication.java`

**Interfaces:**
- Produces（后续任务都依赖）：
  - `ScriptedChatModel`（测试桩，**`public`**，实现 `ChatModel`——Task 5 的测试在上一级包里也要用它，构造器与静态方法
    都得是 public）：
    构造 `ScriptedChatModel(Function<Prompt, ChatResponse> script)`；`List<Integer> promptSizes()`（每次被调用时收到的
    消息条数，按调用先后）；静态 `ChatResponse text(String content)`、`ChatResponse toolCall(String name, String arguments)`、
    `String lastUserText(Prompt)`、`boolean isToolResult(Prompt)`、`String firstToolResult(Prompt)`。
    `getOptions()` 返回 `ToolCallingChatOptions.builder().build()`，注释写明原因（spec §2.5 坑 1）。
  - `SessionDemoSupport`（包内可见的工具类）：
    `static SessionService newSessions()`；`static String ask(ChatClient chatClient, List<Advisor> advisors,
    String sessionId, String userText, Object... tools)`——带会话 id 发一轮请求并返回回答文本，`tools` 非空时才调 `.tools(tools)`。
  - `SessionMemoryDemo`：`title()` = `会话记忆（Session Memory：事件日志）`，`order()` = 3；
    嵌套 `record TwoTurnRun(String firstAnswer, String secondAnswer, List<SessionEvent> historyBeforeSecondTurn,
    List<SessionEvent> log)`；
    `static TwoTurnRun runTwoTurns(ChatClient chatClient, SessionService sessions, String sessionId)`；
    `static String describeEvent(int index, SessionEvent event, ZoneId zone)`。

- [ ] **Step 1: 在 pom 加两个依赖**

在 `spring-ai-client-chat` 之后加 `spring-ai-session` 与 `spring-ai-session-advisor`（`groupId` 均为
`org.springframework.ai`，不写版本号），注释写明「会话记忆：事件日志在 spring-ai-session，SessionAdvisors 在
spring-ai-session-advisor；两者都不依赖 Spring Boot」。

- [ ] **Step 2: 写测试桩 `ScriptedChatModel` 与失败的测试**

`ScriptedChatModel` 按上面 Interfaces 实现（`call()` 记录 `prompt.getInstructions().size()` 后执行脚本；
`stream()` 返回 `Flux.just(call(prompt))`；`toolCall()` 用一个静态 `AtomicInteger` 生成不重复的工具调用 id，
并带 `ChatGenerationMetadata.builder().finishReason("tool_calls").build()`）。

```java
class SessionMemoryDemoTest {

    @Test
    void twoTurnsAppendFourEventsAndSecondPromptCarriesTheHistory() {
        ScriptedChatModel model = new ScriptedChatModel(
                p -> ScriptedChatModel.text("回声：" + ScriptedChatModel.lastUserText(p)));

        SessionMemoryDemo.TwoTurnRun run = SessionMemoryDemo.runTwoTurns(
                ChatClient.builder(model).build(), SessionDemoSupport.newSessions(), "user-1");

        assertEquals(List.of(MessageType.USER, MessageType.ASSISTANT),
                run.historyBeforeSecondTurn().stream().map(SessionEvent::getMessageType).toList());
        assertEquals(List.of(MessageType.USER, MessageType.ASSISTANT, MessageType.USER, MessageType.ASSISTANT),
                run.log().stream().map(SessionEvent::getMessageType).toList());
        assertTrue(run.log().stream().allMatch(e -> e.getType().equals(EventType.MESSAGE)));
        assertEquals(List.of(1, 3), model.promptSizes());
    }

    @Test
    void describeEventShowsIndexRoleIdTimeAndText() {
        SessionEvent user = SessionEvent.builder().id("3f2a9c41").sessionId("user-1")
                .timestamp(Instant.parse("2026-11-25T02:12:33.120Z"))
                .message(UserMessage.builder().text("我最喜欢的编程语言是 Java，请记住。").build()).build();
        SessionEvent boundary = SessionEvent.builder().id("3f2a9c41").sessionId("user-1")
                .timestamp(Instant.parse("2026-11-25T02:12:40.000Z")).type(EventType.COMPACTION).payload(Map.of()).build();

        assertEquals("#1  user       3f2a9c41  10:12:33.120  我最喜欢的编程语言是 Java，请记住。",
                SessionMemoryDemo.describeEvent(1, user, ZoneId.of("Asia/Shanghai")));
        assertEquals("#9  COMPACTION  3f2a9c41  10:12:40.000",
                SessionMemoryDemo.describeEvent(9, boundary, ZoneId.of("Asia/Shanghai")));
    }
}
```

- [ ] **Step 3: 运行测试，确认是红的**

Run: `mvn -pl springai-agent-demo -am test -Dtest='SessionMemoryDemoTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 测试编译失败，`找不到符号: 类 SessionMemoryDemo` / `SessionDemoSupport`。

- [ ] **Step 4: 实现 `SessionDemoSupport` 与 `SessionMemoryDemo`**

- `SessionDemoSupport.ask()`：`chatClient.prompt().user(userText).advisors(advisors).advisors(SessionAdvisors.session(sessionId))`，
  `tools.length > 0` 时再 `.tools(tools)`；**只调一次 `.call().content()` 并把结果存成变量返回**。
- `SessionMemoryDemo.runTwoTurns()`：自己建 `SessionAdvisors.builder(sessions).build()`；第一问
  「我最喜欢的编程语言是 Java，请记住。」，然后读 `sessions.getEvents(sessionId, EventFilter.active())` 作为
  `historyBeforeSecondTurn`；第二问「我刚才说我最喜欢的编程语言是什么？」；`log` 读 `EventFilter.all()`。
- `SessionMemoryDemo.describeEvent()`：**先判 `hasMessage()`**——不带消息的事件（如 `COMPACTION` 边界）角色一栏
  显示事件类型名、不带正文（对它调 `getMessageType()` 会抛异常）。带消息的事件角色显示 `user` / `assistant` / `tool`，
  正文取 `getText()`。时间按 `zone` 格式化为 `HH:mm:ss.SSS`；行格式为 `#序号  角色  id前8位  时间  正文`
  （不带正文的事件省略最后一段），具体以 Step 2 的两条期望字符串为准。
- `run()`：按 spec §5.2 的输出形态打印（问与答、第 2 轮会带上的历史、整份事件日志、结尾一句「每条事件自带…」），
  时间用 `ZoneId.systemDefault()`；类注释写明认知一句话与和示例 2 的对照（spec §5.2）。
- `AgentDemoApplication`：在 `new ChatMemoryDemo(chatClient)` 之后登记 `new SessionMemoryDemo(chatClient)`。

- [ ] **Step 5: 运行测试，确认是绿的**

Run: `mvn -pl springai-agent-demo -am test -Dtest='SessionMemoryDemoTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: 变异测试**

把 `runTwoTurns()` 里第二问之前的 `historyBeforeSecondTurn` 读取挪到第二问之后 → 重跑，Expected: FAIL
（历史变成 4 条）。改回，再跑一次确认绿。

- [ ] **Step 7: 提交**

```bash
git add springai-agent-demo/pom.xml springai-agent-demo/src
git commit -m "feat: agent-demo 接入 Session 依赖并新增示例 3 会话记忆（事件日志）"
```

### Task 2: 示例 4「会话压缩：日志与视图」

**Files:**
- Create: `springai-agent-demo/src/main/java/io/github/javaside/springai/agent/demo/SessionCompactionDemo.java`
- Test: `springai-agent-demo/src/test/java/io/github/javaside/springai/agent/demo/SessionCompactionDemoTest.java`
- Modify: `springai-agent-demo/src/main/java/io/github/javaside/springai/agent/AgentDemoApplication.java`（登记）

**Interfaces:**
- Consumes: `SessionDemoSupport.newSessions()` / `ask(..)`、`SessionMemoryDemo.describeEvent(int, SessionEvent, ZoneId)`。
- Produces:
  - `SessionCompactionDemo`：`title()` = `会话压缩：日志与视图`，`order()` = 4；
  - `static List<Advisor> compactingAdvisors(SessionService sessions, int maxTurns, int keepTurns)`——
    「视图超过 `maxTurns` 个回合就压缩，只留最近 `keepTurns` 个回合」的会话 advisors；
  - 嵌套 `record TurnStat(int logSize, int viewSize)`；
  - `static List<TurnStat> playPlainTurns(ChatClient chatClient, SessionService sessions, String sessionId, int turns)`——
    用 `compactingAdvisors(sessions, 3, 2)`，第 i 轮问「第i句」，每轮结束记一条 `TurnStat`；
  - 嵌套 `record ToolTurnRun(List<SessionEvent> log, List<SessionEvent> view)`；
  - `static ToolTurnRun playToolTurns(ChatClient chatClient, SessionService sessions, String sessionId)`——
    用 `compactingAdvisors(sessions, 1, 1)`，两轮都注册 `new WeatherTools()`：第一轮「你好」，第二轮「北京天气怎么样？」；
  - `static List<String> renderLog(List<SessionEvent> log, List<SessionEvent> view, ZoneId zone)`——每条事件一行。

- [ ] **Step 1: 写失败的测试**

```java
class SessionCompactionDemoTest {

    static final String SID = "demo-test";

    static ScriptedChatModel echoModel() {
        return new ScriptedChatModel(p -> ScriptedChatModel.text("回声：" + ScriptedChatModel.lastUserText(p)));
    }

    /** 只有问到天气才请求调用工具；拿到工具结果后给最终回答。 */
    static ScriptedChatModel weatherModel() {
        return new ScriptedChatModel(p -> ScriptedChatModel.isToolResult(p)
                ? ScriptedChatModel.text("北京今天晴。")
                : ScriptedChatModel.lastUserText(p).contains("天气")
                        ? ScriptedChatModel.toolCall("getWeather", "{\"city\":\"北京\"}")
                        : ScriptedChatModel.text("你好！"));
    }

    @Test
    void plainTurnsLogGrowsWhileViewShrinksAfterCompaction() {
        ScriptedChatModel model = echoModel();

        List<SessionCompactionDemo.TurnStat> stats = SessionCompactionDemo.playPlainTurns(
                ChatClient.builder(model).build(), SessionDemoSupport.newSessions(), SID, 5);

        assertEquals(List.of(new SessionCompactionDemo.TurnStat(2, 2), new SessionCompactionDemo.TurnStat(4, 4),
                new SessionCompactionDemo.TurnStat(6, 6), new SessionCompactionDemo.TurnStat(9, 4),
                new SessionCompactionDemo.TurnStat(11, 6)), stats);
        assertEquals(List.of(1, 3, 5, 7, 5), model.promptSizes());
    }

    @Test
    void compactionAppendsBoundaryWithoutRewritingLog() {
        ScriptedChatModel model = echoModel();
        ChatClient client = ChatClient.builder(model).build();
        SessionService sessions = SessionDemoSupport.newSessions();
        List<Advisor> advisors = SessionCompactionDemo.compactingAdvisors(sessions, 3, 2);
        for (int i = 1; i <= 3; i++) {
            SessionDemoSupport.ask(client, advisors, SID, "第" + i + "句");
        }
        List<SessionEvent> before = sessions.getEvents(SID, EventFilter.all());

        SessionDemoSupport.ask(client, advisors, SID, "第4句");

        List<SessionEvent> after = sessions.getEvents(SID, EventFilter.all());
        assertEquals(9, after.size());
        assertEquals(snapshot(before), snapshot(after.subList(0, 8)));
        assertEquals(EventType.COMPACTION, after.get(8).getType());
        assertFalse(after.get(8).hasMessage());
        assertEquals(ids(after.subList(4, 8)), ids(sessions.getEvents(SID, EventFilter.active())));
    }

    @Test
    void toolTurnIsLoggedWholeAndNeverSplitByCompaction() {
        ScriptedChatModel model = weatherModel();

        SessionCompactionDemo.ToolTurnRun run = SessionCompactionDemo.playToolTurns(
                ChatClient.builder(model).build(), SessionDemoSupport.newSessions(), SID);

        assertEquals(List.of("USER", "ASSISTANT", "USER", "ASSISTANT", "TOOL", "ASSISTANT", "COMPACTION"),
                run.log().stream().map(e -> e.hasMessage() ? e.getMessageType().name() : e.getType().name()).toList());
        assertTrue(run.log().get(3).hasToolCalls());
        assertEquals(ids(run.log().subList(2, 6)), ids(run.view()));
        assertEquals(List.of(1, 3, 5), model.promptSizes());
    }

    @Test
    void renderLogMarksWhichEventsAreStillInTheView() {
        SessionCompactionDemo.ToolTurnRun run = SessionCompactionDemo.playToolTurns(
                ChatClient.builder(weatherModel()).build(), SessionDemoSupport.newSessions(), SID);

        List<String> lines = SessionCompactionDemo.renderLog(run.log(), run.view(), ZoneId.of("Asia/Shanghai"));

        assertEquals(List.of("  ✗ #1", "  ✗ #2", "  ✓ #3", "  ✓ #4", "  ✓ #5", "  ✓ #6", "  ✗ #7"),
                lines.stream().map(l -> l.substring(0, 6)).toList());
        assertTrue(lines.get(3).contains("🔧 getWeather({\"city\":\"北京\"})"));
        assertTrue(lines.get(4).contains("北京：晴，26℃，微风"));
        assertTrue(lines.get(6).contains("压缩边界"));
    }

    static List<String> snapshot(List<SessionEvent> events) {
        return events.stream().map(e -> e.getId() + "|" + e.getMessage().getText()).toList();
    }

    static List<String> ids(List<SessionEvent> events) {
        return events.stream().map(SessionEvent::getId).toList();
    }
}
```

- [ ] **Step 2: 运行测试，确认是红的**

Run: `mvn -pl springai-agent-demo -am test -Dtest='SessionCompactionDemoTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 测试编译失败，`找不到符号: 类 SessionCompactionDemo`。

- [ ] **Step 3: 实现 `SessionCompactionDemo`**

- `renderLog()` 每行：两个空格 + （在视图里 `✓` / 不在 `✗`）+ 空格 + `SessionMemoryDemo.describeEvent(序号, 事件, zone)`；
  `COMPACTION` 那一行在末尾追加「← 压缩边界：只追加这一条，上面任何一条都没被改写」。
- 给 `SessionMemoryDemo.describeEvent()` 补两个正文分支（Task 1 没覆盖到的）：带工具调用的 assistant 事件显示
  `🔧 工具名(参数)`；`tool` 事件显示第一条工具返回（超过 40 字截断）。
- `run()`：第一幕 `playPlainTurns(.., 5)`，按 spec §5.3 打印条数表与 `renderLog`；第二幕 `playToolTurns`，
  打印压缩后的视图 4 条与「压缩按整回合切，不会让模型看到一个没有结果的工具调用」。类注释写明认知一句话。
- `AgentDemoApplication`：登记 `new SessionCompactionDemo(chatClient)`，排在 `SessionMemoryDemo` 之后。

- [ ] **Step 4: 运行测试，确认是绿的**

Run: `mvn -pl springai-agent-demo -am test -Dtest='SessionCompactionDemoTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: 变异测试**

把 `compactingAdvisors()` 里的 `new TurnCountTrigger(maxTurns)` 临时改成 `new TurnCountTrigger(maxTurns + 1)`
→ 重跑，Expected: FAIL（第 4 轮后不再压缩）。改回，再跑确认绿。

- [ ] **Step 6: 提交**

```bash
git add springai-agent-demo/src
git commit -m "feat: agent-demo 新增示例 4 会话压缩（日志与视图）"
```

### Task 3: 示例 5「会话召回：找回被压缩的内容」

**Files:**
- Create: `springai-agent-demo/src/main/java/io/github/javaside/springai/agent/demo/SessionRecallDemo.java`
- Test: `springai-agent-demo/src/test/java/io/github/javaside/springai/agent/demo/SessionRecallDemoTest.java`
- Modify: `springai-agent-demo/src/main/java/io/github/javaside/springai/agent/AgentDemoApplication.java`（登记）

**Interfaces:**
- Consumes: `SessionDemoSupport.newSessions()` / `ask(..)`、`SessionCompactionDemo.renderLog`（可选）。
- Produces:
  - `SessionRecallDemo`：`title()` = `会话召回：找回被压缩的内容`，`order()` = 5；
  - `static final String SECRET_KEYWORD = "海棠"`；
  - `static List<Advisor> recallAdvisors(SessionService sessions)`——压缩（3, 2）+ `sessionIdInToolContext(true)`；
  - 嵌套 `record RecallRun(int logSizeBefore, int viewSizeBefore, String answer, List<SessionEvent> logAfter,
    List<SessionEvent> viewAfter)`；
  - `static RecallRun seedAndRecall(ChatClient chatClient, SessionService sessions, String sessionId,
    List<Advisor> extraAdvisors)`——`extraAdvisors` 原样并入 advisor 链（`run()` 传轮次日志，测试传 `List.of()`）。

- [ ] **Step 1: 写失败的测试**

```java
class SessionRecallDemoTest {

    @Test
    void recallFindsEventsOutsideTheView() {
        ScriptedChatModel model = new ScriptedChatModel(p -> {
            if (ScriptedChatModel.isToolResult(p)) {
                return ScriptedChatModel.text("你最早说的是：" + ScriptedChatModel.firstToolResult(p));
            }
            return ScriptedChatModel.lastUserText(p).contains("暗号是什么")
                    ? ScriptedChatModel.toolCall("conversation_search", "{\"query\":\"海棠\"}")
                    : ScriptedChatModel.text("回声：" + ScriptedChatModel.lastUserText(p));
        });
        SessionService sessions = SessionDemoSupport.newSessions();

        SessionRecallDemo.RecallRun run = SessionRecallDemo.seedAndRecall(
                ChatClient.builder(model).build(), sessions, "s", List.of());

        assertEquals(9, run.logSizeBefore());
        assertEquals(4, run.viewSizeBefore());
        List<SessionEvent> hits = sessions.searchEvents("s", EventQuery.keyword("海棠"));
        assertEquals(MessageType.USER, hits.get(0).getMessageType());
        assertTrue(hits.get(0).getMessage().getText().contains("海棠"));
        assertFalse(ids(run.viewAfter()).contains(hits.get(0).getId()));   // 旧事件没有回到视图
        assertTrue(ids(run.logAfter()).contains(hits.get(0).getId()));     // 但一直在日志里
        assertTrue(run.answer().contains("海棠"));
        assertEquals(13, run.logAfter().size());
        assertEquals(8, run.viewAfter().size());
        assertEquals(List.of(1, 3, 5, 7, 5, 7), model.promptSizes());
    }

    static List<String> ids(List<SessionEvent> events) {
        return events.stream().map(SessionEvent::getId).toList();
    }
}
```

- [ ] **Step 2: 运行测试，确认是红的**

Run: `mvn -pl springai-agent-demo -am test -Dtest='SessionRecallDemoTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 测试编译失败，`找不到符号: 类 SessionRecallDemo`。

- [ ] **Step 3: 实现 `SessionRecallDemo`**

- `seedAndRecall()`：用 `recallAdvisors(sessions)`。前 4 轮不注册工具：第 1 轮「请记住：我的项目暗号是『海棠』。」，
  第 2–4 轮闲聊；记下 `logSizeBefore` / `viewSizeBefore`。第 5 轮注册 `SessionRecallTools.builder(sessions).build()`，
  问「我最早告诉你的项目暗号是什么？（上下文里没有的话，用会话搜索工具查）」，得到 `answer`；再读日志与视图。
- 私有嵌套 `RoundLoggingAdvisor`（order = `ToolCallingAdvisor.DEFAULT_ORDER + 100`），写法照
  `SkillToolDemo.RoundLoggingAdvisor`（第 173–219 行）：每轮打印可用工具与模型的决定；**多打印一行工具返回**——
  一轮开始时若请求的最后一条消息是 `ToolResponseMessage`，打印其 `responseData()`（截断 120 字）。
  工具循环关掉了自带历史，下一轮请求里只带工具结果，所以在这里能原样看到 `conversation_search` 返回的 JSON。
- `run()`：`seedAndRecall(.., List.of(roundLogger))`，按 spec §5.4 打印压缩前后的日志 / 视图条数与结论。类注释写明认知一句话。
- `AgentDemoApplication`：登记 `new SessionRecallDemo(chatClient)`，排在 `SessionCompactionDemo` 之后。

- [ ] **Step 4: 运行测试，确认是绿的**

Run: `mvn -pl springai-agent-demo -am test -Dtest='SessionRecallDemoTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 5: 变异测试**

把 `recallAdvisors()` 里的 `.sessionIdInToolContext(true)` 临时删掉 → 重跑，Expected: FAIL（工具拿不到会话 id，
搜索返回「no session id was provided」，回答里没有「海棠」）。改回，再跑确认绿。

- [ ] **Step 6: 真机验证（Review Focus 3）**

先在本终端 `source ~/.secrets`（不要 echo 任何 key），然后：

```bash
mvn -q -pl springai-agent-demo -am package -DskipTests
printf '5\n0\n' | java -jar springai-agent-demo/target/springai-agent-demo.jar | tee /tmp/agent-demo-5.txt
```

Expected: 输出里出现 `conversation_search` 的调用与返回，最终回答含「海棠」；结尾的条数是日志 13 / 视图 8
（模型若多搜一次，条数会变，以实际输出为准，但「旧事件不回视图」必须成立）。若模型没调工具直接答「不知道」：
在 `AgentDemoApplication` 的系统提示里加一句「需要回忆之前的对话时，用会话搜索工具」，再跑；仍不稳定则在
`run()` 里把第 1 轮的暗号换成更具体的词。最终采用的提示词原样进 README。

- [ ] **Step 6.1: 提交**

```bash
git add springai-agent-demo/src
git commit -m "feat: agent-demo 新增示例 5 会话召回（找回被压缩的内容）"
```

### Task 4: 钉住 README「延伸」小节的两段行为（带摘要的压缩、lastN 窗口）

这两条是对库行为的刻画测试，本仓库没有对应的实现可红；断言是否有效靠 Step 3 的变异来确认。

**Files:**
- Create: `springai-agent-demo/src/test/java/io/github/javaside/springai/agent/demo/SessionMemoryExtrasTest.java`

**Interfaces:**
- Consumes: `SessionDemoSupport.newSessions()` / `ask(..)`、`ScriptedChatModel`。
- Produces: 无（纯测试）。

- [ ] **Step 1: 写测试**

```java
class SessionMemoryExtrasTest {

    static final String SID = "extras";

    @Test
    void summaryCompactionKeepsSyntheticSummaryTurnInView() {
        ScriptedChatModel summarizer = new ScriptedChatModel(p -> ScriptedChatModel.text("【摘要】前两轮。"));
        SessionService sessions = SessionDemoSupport.newSessions();
        List<Advisor> advisors = SessionAdvisors.builder(sessions)
                .compaction(new TurnCountTrigger(2), WindowCompactionStrategy.builder().lastTurns(1)
                        .summarizer(ChatModelSummarizer.builder(summarizer).build()).build())
                .build();
        ScriptedChatModel model = new ScriptedChatModel(p -> ScriptedChatModel.text("回声：" + ScriptedChatModel.lastUserText(p)));
        ChatClient client = ChatClient.builder(model).build();
        for (int i = 1; i <= 3; i++) {
            SessionDemoSupport.ask(client, advisors, SID, "第" + i + "句");
        }

        List<SessionEvent> log = sessions.getEvents(SID, EventFilter.all());
        assertEquals(9, log.size());
        assertEquals(EventType.COMPACTION, log.get(6).getType());
        assertTrue(log.get(7).isSynthetic() && log.get(8).isSynthetic());
        assertTrue(log.get(7).isTurnStart());
        assertEquals(MessageType.USER, log.get(7).getMessageType());
        assertEquals(MessageType.ASSISTANT, log.get(8).getMessageType());
        assertEquals("【摘要】前两轮。", log.get(8).getMessage().getText());
        assertEquals(List.of(log.get(7).getId(), log.get(8).getId(), log.get(4).getId(), log.get(5).getId()),
                ids(sessions.getEvents(SID, EventFilter.active())));   // 摘要回合在前，保留回合在后
        assertEquals(List.of(1, 3, 5), model.promptSizes());
        assertEquals(List.of(2), summarizer.promptSizes());
    }

    @Test
    void lastNWindowBoundsThePromptWhileTheLogKeepsEverything() {
        SessionService sessions = SessionDemoSupport.newSessions();
        List<Advisor> advisors = SessionAdvisors.builder(sessions)
                .memory(memory -> memory.eventFilter(EventFilter.active().toBuilder().lastN(4).build()))
                .build();
        ScriptedChatModel model = new ScriptedChatModel(p -> ScriptedChatModel.text("回声：" + ScriptedChatModel.lastUserText(p)));
        ChatClient client = ChatClient.builder(model).build();
        for (int i = 1; i <= 5; i++) {
            SessionDemoSupport.ask(client, advisors, SID, "第" + i + "句");
        }

        List<SessionEvent> log = sessions.getEvents(SID, EventFilter.all());
        assertEquals(10, log.size());
        assertTrue(log.stream().noneMatch(e -> e.getType().equals(EventType.COMPACTION)));
        assertEquals(List.of(1, 3, 5, 5, 5), model.promptSizes());
        List<String> last3 = ids(sessions.getEvents(SID, EventFilter.active().toBuilder().lastN(3).build()));
        assertEquals(ids(sessions.getEvents(SID, EventFilter.active().toBuilder().lastN(4).build())), last3);
        assertEquals(ids(log.subList(6, 10)), last3);   // 落在回合中间时向前扩到回合起点
    }

    static List<String> ids(List<SessionEvent> events) {
        return events.stream().map(SessionEvent::getId).toList();
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn -pl springai-agent-demo -am test -Dtest='SessionMemoryExtrasTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 3: 变异确认断言有效（两处都做，做完改回）**

1. 第二个用例把 `lastN(4)` 改成 `lastN(6)` → Expected: FAIL（`promptSizes` 第 4 轮变成 7）。
2. 第一个用例把 `.lastTurns(1)` 改成 `.lastTurns(2)` → Expected: FAIL（视图里多保留一个回合）。

改回后重跑，Expected: `Tests run: 2, Failures: 0, Errors: 0`；`git status` 只有新增的测试文件。

- [ ] **Step 4: 提交**

```bash
git add springai-agent-demo/src/test
git commit -m "test: 钉住带摘要压缩与 lastN 窗口的行为（README 延伸小节的依据）"
```

### Task 5: 旧 `ChatMemory` 示例标注弃用，菜单改号为 1–10

**Files:**
- Modify: `springai-agent-demo/src/main/java/io/github/javaside/springai/agent/AgentDemoApplication.java`
- Modify: `springai-agent-demo/src/main/java/io/github/javaside/springai/agent/demo/ChatMemoryDemo.java`（标题、类注释）
- Modify: `.../demo/ToolMemoryAdvisorDemo.java`（标题、`order()`、类注释）
- Modify: `.../demo/MultiStepAgentDemo.java`、`ToolSearchDemo.java`、`SkillToolDemo.java`、`TodoWriteToolDemo.java`（`order()`、类注释首行）
- Test: `springai-agent-demo/src/test/java/io/github/javaside/springai/agent/AgentDemoApplicationTest.java`

**Interfaces:**
- Produces（`AgentDemoApplication` 的两个静态方法，`main` 改为调用它们）：
  - `static ChatClient buildChatClient(ChatModel chatModel)`——`ChatClient.builder(chatModel).defaultSystem(现有系统提示原文).build()`；
  - `static List<Demo> demos(ChatClient chatClient)`——按菜单顺序返回全部 10 个示例。

- [ ] **Step 1: 写失败的测试**

```java
class AgentDemoApplicationTest {

    static ChatClient anyClient() {
        return AgentDemoApplication.buildChatClient(new ScriptedChatModel(p -> ScriptedChatModel.text("好")));
    }

    @Test
    void demosAreNumberedOneToTenInMenuOrder() {
        List<Demo> demos = AgentDemoApplication.demos(anyClient());

        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), demos.stream().map(Demo::order).toList());
        assertEquals(List.of("ToolCallingDemo", "ChatMemoryDemo", "SessionMemoryDemo", "SessionCompactionDemo",
                "SessionRecallDemo", "MultiStepAgentDemo", "ToolMemoryAdvisorDemo", "ToolSearchDemo", "SkillToolDemo",
                "TodoWriteToolDemo"), demos.stream().map(d -> d.getClass().getSimpleName()).toList());
    }

    @Test
    void chatMemoryDemosAreKeptButMarkedDeprecated() {
        List<Demo> demos = AgentDemoApplication.demos(anyClient());

        assertEquals("对话记忆（ChatMemory，2.1 起已弃用）", demos.get(1).title());
        assertEquals("Advisor 顺序（ChatMemory，2.1 起已弃用）", demos.get(6).title());
    }

    @Test
    void sharedChatClientNeedsNoSessionId() {
        // 会话 advisors 只能按请求传入：共享 ChatClient 不带会话 id 也必须能正常对话
        assertEquals("好", anyClient().prompt().user("你好").call().content());
    }
}
```

- [ ] **Step 2: 运行测试，确认是红的**

Run: `mvn -pl springai-agent-demo -am test -Dtest='AgentDemoApplicationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 测试编译失败，`找不到符号: 方法 buildChatClient` / `demos`。

- [ ] **Step 3: 抽方法并改号、标注**

- `AgentDemoApplication.main`：把「建 `ChatClient`」与「组装 `List<Demo>`」分别换成 `buildChatClient(chatModel)`、`demos(chatClient)`；
  两个静态方法里的系统提示原文与示例顺序原样搬过去（顺序同 Step 1 的类名清单）。
- `ChatMemoryDemo`：`title()` 改为 `对话记忆（ChatMemory，2.1 起已弃用）`；类注释补一段（spec §5.5）：`ChatMemory` 在 2.1.0
  被 `@Deprecated(forRemoval = true)`、继任者是会话记忆（示例 3）、本示例留着是为了读旧代码并与示例 3 对照。
- `ToolMemoryAdvisorDemo`：`title()` 改为 `Advisor 顺序（ChatMemory，2.1 起已弃用）`，`order()` 返回 7；类注释首行改「示例 7」，
  并补一句：这个结论只对 `ChatMemory` 成立——`SessionMemoryAdvisor` 默认在工具循环内侧，会话日志一定记下工具调用与结果，
  模型看到什么由视图决定，见示例 4。
- 其余顺延（只改 `order()` 与类注释首行的「示例 N」）：`MultiStepAgentDemo` → 6 / 示例 6，`ToolSearchDemo` → 8 / 示例 8，
  `SkillToolDemo` → 9 / 示例 9，`TodoWriteToolDemo` → 10 / 示例 10。
- 五个旧示例的运行代码一行不动。

- [ ] **Step 4: 运行测试，确认是绿的**

Run: `mvn -pl springai-agent-demo -am test`
Expected: `Tests run: 18, Failures: 0, Errors: 0`（原有 6 + Task 1–5 新增 12）

- [ ] **Step 5: 提交**

```bash
git add springai-agent-demo/src
git commit -m "feat: 旧 ChatMemory 示例标注弃用并让位给会话记忆示例（菜单改号为 1–10）"
```

### Task 6: boot-demo 接入会话 JDBC starter，先探测自动装配（Review Focus 4）

只加依赖、配置和三行「自动配置揭秘」，**先确认 Bean 与建表都生效，再写示例**（spec §9 第 2 项）。

**Files:**
- Modify: `springai-boot-demo/pom.xml`
- Modify: `springai-boot-demo/src/main/resources/application.properties`
- Modify: `springai-boot-demo/src/main/java/io/github/javaside/springai/boot/demo/AutoConfigInspectDemo.java`

**Interfaces:**
- Produces（Task 7 依赖）：容器里可注入的 `org.springframework.ai.session.SessionService`（starter 自动配置在
  `JdbcSessionRepository` 之上）与 `org.springframework.jdbc.core.JdbcTemplate`。

- [ ] **Step 1: pom 加两项依赖**

MCP starter 之后加 `org.springframework.ai:spring-ai-starter-model-session-jdbc`（compile）与
`com.h2database:h2`（`<scope>runtime</scope>`），都不写版本号。注释写明：starter 带 `spring-boot-starter-jdbc`
与自动配置，但**不带数据库驱动**，H2 要自己加。

- [ ] **Step 2: `application.properties` 加三行**

```properties
# ===== 会话落库：H2 文件库放在用户目录下，重启进程仍在；删掉该目录即重置 =====
spring.datasource.url=jdbc:h2:file:${user.home}/.springai-boot-demo/sessions;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driver-class-name=org.h2.Driver
spring.ai.session.repository.jdbc.initialize-schema=always
```

- [ ] **Step 3: `AutoConfigInspectDemo` 补三行**

在 `inspect("ChatClient", ...)` 之后加（配置方式一栏的文字照写）：

```java
        inspect("DataSource", javax.sql.DataSource.class,
                "starter 自动配置（spring-boot-starter-jdbc，由会话 JDBC starter 带入）");
        inspect("SessionRepository", org.springframework.ai.session.SessionRepository.class,
                "starter 自动配置（spring-ai-starter-model-session-jdbc）");
        inspect("SessionService", org.springframework.ai.session.SessionService.class,
                "starter 自动配置（spring-ai-autoconfigure-model-session）");
```

- [ ] **Step 4: 构建并探测**

```bash
source ~/.secrets   # 不要 echo 任何 key；菜单 1 不调模型
rm -rf ~/.springai-boot-demo   # 本示例专属的数据目录，保证从空库开始
mvn -q -pl springai-boot-demo -am package -DskipTests
printf '1\n0\n' | java -jar springai-boot-demo/target/springai-boot-demo-1.32.0.jar | tee /tmp/boot-inspect.txt
ls ~/.springai-boot-demo
```

Expected：输出里出现
`SessionRepository … 实现类 = org.springframework.ai.session.jdbc.JdbcSessionRepository`、
`SessionService … 实现类 = org.springframework.ai.session.DefaultSessionService`、
`DataSource … 实现类 = com.zaxxer.hikari.HikariDataSource`；目录下有 `sessions.mv.db`。
若 `SessionRepository` 显示「未找到」：自动装配没生效。用 `mvn -pl springai-boot-demo dependency:tree`
确认 session 相关坐标都在，再给 jar 加 `--debug` 看条件评估报告（`Conditions and Report Evaluation`），
把原因记进 spec 的「实施记录」。没查清之前不要进 Task 7。

- [ ] **Step 5: 提交**

```bash
git add springai-boot-demo/pom.xml springai-boot-demo/src
git commit -m "feat: boot-demo 接入会话 JDBC starter（自动配置揭秘补三个 Bean）"
```

### Task 7: boot-demo 新增示例「会话落库」

**Files:**
- Create: `springai-boot-demo/src/main/java/io/github/javaside/springai/boot/demo/SessionJdbcDemo.java`

**Interfaces:**
- Consumes: Task 6 的 `SessionService` / `JdbcTemplate` Bean。
- Produces: `@Component public class SessionJdbcDemo implements Demo`——构造器
  `(ChatClient chatClient, SessionService sessionService, JdbcTemplate jdbcTemplate,
  @Value("${spring.datasource.url}") String jdbcUrl)`；`title()` = `会话落库（JDBC starter + H2，重启不丢）`；
  `order()` = 3（菜单第 4 项）；`static final String SESSION_ID = "boot-demo-user"`。

- [ ] **Step 1: 实现 `SessionJdbcDemo`**

- 无测试（spec §4、§7：boot-demo 不引入测试），证据是 Step 3 的两份真机输出。
- 构造器里建一次 `this.sessionAdvisors = SessionAdvisors.builder(sessionService).build()`，**按请求传入**
  （`.advisors(sessionAdvisors).advisors(SessionAdvisors.session(SESSION_ID))`），不挂到共享 `ChatClient` 的默认 advisors 上。
- `run()` 按 spec §6 的五步：
  1. 打印数据库位置（`jdbcUrl`）与该会话已有的事件数：
     `SELECT COUNT(*) FROM AI_SESSION_EVENT WHERE session_id = ?`；
  2. 第一问「我之前告诉过你我的工号吗？不知道就直说。」；
  3. 第二问「请记住，我的工号是 A1024。」，第三问「我的工号是多少？」；
  4. 用 `SELECT seq, event_type, message_type, message_content FROM AI_SESSION_EVENT WHERE session_id = ? ORDER BY seq`
     逐行打印（正文在 Java 里截到 20 字，不用 SQL 的 `LEFT`，换数据库不用改）；
  5. 结尾提示「退出程序再运行本示例，第一问就能答对；删掉 ~/.springai-boot-demo 即重置」。
- 类注释写明对比：agent-demo 里两行手动接线（`DefaultSessionService.builder().sessionRepository(InMemorySessionRepository…)`），
  这里一个构造器参数都不用管，仓库还从内存换成了数据库。

- [ ] **Step 2: 构建**

Run: `mvn -q -pl springai-boot-demo -am package -DskipTests`
Expected: 无输出（`-q`），退出码 0；不要接管道，退出码要单独取。

- [ ] **Step 3: 真机跑两遍（首次 / 再次）**

```bash
source ~/.secrets   # 不要 echo 任何 key
rm -rf ~/.springai-boot-demo
printf '4\n0\n' | java -jar springai-boot-demo/target/springai-boot-demo-1.32.0.jar | tee /tmp/boot-session-run1.txt
printf '4\n0\n' | java -jar springai-boot-demo/target/springai-boot-demo-1.32.0.jar | tee /tmp/boot-session-run2.txt
```

Expected：第一遍「已有 0 条」，第一问答「不知道」；第二遍「已有 N 条」（N > 0），第一问答出「A1024」。
两份输出留给 Task 9 贴 README。

- [ ] **Step 4: 提交**

```bash
git add springai-boot-demo/src
git commit -m "feat: boot-demo 新增会话落库示例（JDBC starter + H2，重启不丢）"
```

### Task 8: agent-demo 的测试纳入 CI

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: 加测试步骤与报告路径**

在「测试 springai-tamboui-demo」之后加：

```yaml
        - name: 测试 springai-agent-demo
          run: mvn -B -pl springai-agent-demo -am test
```

失败时上传的 surefire 报告路径改成两行：

```yaml
            path: |
              springai-code-tui/target/surefire-reports/
              springai-agent-demo/target/surefire-reports/
```

`run:` 里只出现 workflow 自身定义的值（文件头的安全说明），不要引用 `github.event.*`。

- [ ] **Step 2: 本地验证同一条命令**

Run: `mvn -B -pl springai-agent-demo -am test`
Expected: `BUILD SUCCESS`，`Tests run: 18, Failures: 0, Errors: 0`；`git diff .github/workflows/ci.yml` 只有新增行。

- [ ] **Step 3: 提交**

```bash
git add .github/workflows/ci.yml
git commit -m "test: agent-demo 的测试纳入 CI"
```

分支推送后看一眼 CI 两个矩阵（JDK 17 / 21）的结果：JDK 17 一侧此前没跑过 agent-demo 的测试（spec §9 第 6 项），
红了先修再继续。

### Task 9: 文档同步（含真机输出）

**Files:**
- Modify: `springai-agent-demo/README.md`
- Modify: `springai-boot-demo/README.md`
- Modify: `README.md`（根）
- Modify: `AGENTS.md`

- [ ] **Step 1: 取 agent-demo 三个示例的真机输出**

```bash
source ~/.secrets   # 不要 echo 任何 key
mvn -q -pl springai-agent-demo -am package -DskipTests
printf '3\n0\n' | java -jar springai-agent-demo/target/springai-agent-demo.jar | tee /tmp/agent-demo-3.txt
printf '4\n0\n' | java -jar springai-agent-demo/target/springai-agent-demo.jar | tee /tmp/agent-demo-4.txt
printf '5\n0\n' | java -jar springai-agent-demo/target/springai-agent-demo.jar | tee /tmp/agent-demo-5.txt
```

Expected：三份输出分别体现「4 条事件日志」「日志 9 / 视图 4 → 11 / 6」「`conversation_search` 找回暗号」。
数字对不上就先查代码，不要改文档去迁就。key 不可用时对应段落写「（待补：需 DEEPSEEK_API_KEY）」。

- [ ] **Step 2: `springai-agent-demo/README.md`**

按 spec §8 第一行逐项改：示例清单表 10 行；新增「示例 3 / 4 / 5 详解」（贴 Step 1 的输出，删掉与认知无关的噪声行）；
示例 2 一行加弃用标注；原「示例 4 详解」改「示例 7 详解」并注明结论仅对 `ChatMemory` 成立；原「示例 5 / 6 / 7 详解」
改 8 / 9 / 10，正文里的「菜单 6 / 7」「同示例 5」同步；新增「延伸：带摘要的压缩与 lastN 窗口」——两段代码
（spec §2.4 的两处配置）+ 数字取自 `SessionMemoryExtrasTest` 两个用例的断言，并注明出处是用例名。
外链用 `2.1-SNAPSHOT` 地址：`memory/session/index`、`memory/session/compaction`、`memory/session/recall`、
`upgrading/session-memory`（都在 `https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/` 下）。

- [ ] **Step 3: `springai-boot-demo/README.md`**

示例清单表加第 4 行；新增「会话落库」一节：依赖、三行 properties、Task 7 的两份输出（首次 / 再次）、如何重置；
「核心看点」对照表提一句会话仓库（手动 `DefaultSessionService.builder()` vs 注入即用的 `SessionService`）。
外链 `https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/integrations/stores/session-jdbc.html`。

- [ ] **Step 4: 根 `README.md` 与 `AGENTS.md`**

按 spec §8 第 3、4 行改（第②步核心表 1–7、进阶表 8–10；「建议先跑一遍示例 3」改示例 6；第③步表加一行；
第⑥步桥接表那一行；`AGENTS.md` 模块表两行）。

- [ ] **Step 5: 复核编号与命令**

用 Grep 工具搜正则 `示例 ?[0-9]+|菜单 ?[0-9]+`，范围：`README.md`、`AGENTS.md`、`springai-agent-demo/README.md`、
`springai-boot-demo/README.md`（type=md）与 `springai-agent-demo/src/main/java`（type=java）。
Expected：agent-demo 相关的编号与新菜单一致（1–10），没有残留的旧编号（`ChatMemoryDemo` 注释里「示例 2」不变）。

README 里新写或改动过的命令逐条原样执行一遍（文档命令必须可直接复制运行）。

- [ ] **Step 6: 提交**

```bash
git add README.md AGENTS.md springai-agent-demo/README.md springai-boot-demo/README.md
git commit -m "docs: README 同步 Session Memory 示例（含真机输出）"
```

### Task 10: 回填实施记录并做全量收尾验证

**Files:**
- Modify: `docs/superpowers/specs/2026-11-25-session-memory-demo-design.md`（「实施记录」一节）

- [ ] **Step 1: 回填实施记录**

写三件事：真机输出的要点（agent-demo 三个示例、boot-demo 两遍）；与设计的出入（召回提示词最终怎么写、
条数是否与预期一致、Task 6 探测结果）；spec §9 六项的验证结果。

- [ ] **Step 2: 全量收尾验证**

```bash
mvn -DskipTests package
mvn -pl springai-agent-demo -am test
git status --short
```

Expected：第一条 `BUILD SUCCESS`（对齐 CI 的「编译其余模块」）；第二条 `Tests run: 18, Failures: 0, Errors: 0`；
第三条在提交后无输出。`mvn` 的退出码单独取，不要接在管道后面。

- [ ] **Step 3: 提交**

```bash
git add docs/superpowers/specs/2026-11-25-session-memory-demo-design.md
git commit -m "docs: Session Memory 教学示例设计稿回填实施记录"
```

是否 `--no-ff` 合并回 main、是否发版，由用户在分支评审后决定，不在本计划内。

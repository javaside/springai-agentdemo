# springai-code-tui Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `springai-agentdemo` 下新增模块 `springai-code-tui`——以 Spring AI 2.0 原始 API 为基础的「代码编写智能体」，用 TamboUI 做单栏对话式 TUI，v1 只接入 `spring-ai-agent-utils:0.10.0` 的 5 个核心编码工具。

**Architecture:** `CodingAgent`（核心，零 TUI/状态依赖，持 `ChatClient` + `AtomicLong activeTurnId`）── `AgentListener`（唯一接缝，纯 Java 类型，每方法带 `turnId`）── `CodeTuiView`（TamboUI TuiRunner 薄视图，只读 `ConversationState`）。turnId 生成权在 `CodingAgent`，经 `toolContext` 下传给工具装饰器、经闭包读给 Todo，单向流向 UI。写状态在 Reactor 线程、读状态在 TuiRunner 线程 → `ConversationState` 线程安全。

**Tech Stack:** 纯 Java 21、不依赖 Spring Boot、Spring AI 2.0 原始 API、DeepSeek 模型、TamboUI 0.4.0（`dev.tamboui`）、Project Reactor（随 client-chat 传递带入）、JUnit 5、Maven（`maven-jar-plugin` + `copy-dependencies` 打可运行 jar）。

参考 spec：`docs/superpowers/specs/2026-06-30-springai-code-tui-design.md`（本计划所有 §引用均指该 spec）

> **执行铁���（来自 spec 与用户反复强调）：不臆测 API。** 凡本计划标注「⚠️ 实现时用 `javap` 核实」的位置，必须先对已解析的 jar 跑 `javap` 确认真实签名再写代码；核实结果与本计划不符时，以字节码为准并在提交信息里记一句。TamboUI 的 widget 绘制 API 与 `ChatClientResponse` 文本抽取是两处仅部分核实的点，分别在 Task 3、Task 8 里作为**第一步**强制核实。

---

## 文件结构

```
springai-agentdemo/
├── pom.xml                                        ← 改：<modules> 加模块、<properties> 加 tamboui.version、<dependencyManagement> 导入 tamboui-bom
└── springai-code-tui/
    ├── pom.xml                                    ← 新增：模块 pom（依赖 + 打包）
    └── src/
        ├── main/
        │   ├── java/com/example/springai/codetui/
        │   │   ├── CodeTuiApplication.java        ← main：安全门 → 建模型/Agent → 启动 TUI
        │   │   ├── agent/
        │   │   │   ├── AgentListener.java         ← 接缝接口（纯 Java 类型，方法带 turnId）
        │   │   │   ├── ToolEventCallback.java     ← ToolCallback 装饰器（从 ToolContext 取 turnId）
        │   │   │   ├── AgentTools.java            ← 工厂：造 5 工具 + 装饰 + 系统提示（含 AgentEnvironment）
        │   │   │   └── CodingAgent.java           ← 核心：submit/handleChunk/handleError/handleComplete
        │   │   └── ui/
        │   │       ├── ConversationState.java     ← 线程安全共享状态
        │   │       └── CodeTuiView.java           ← TamboUI TuiRunner 视图
        │   └── resources/
        │       └── logback.xml                    ← 日志写文件，不污染 TUI
        └── test/java/com/example/springai/codetui/
            ├── ui/ConversationStateTest.java
            ├── agent/AgentListenerCancelTest.java
            ├── agent/ToolEventCallbackTest.java
            ├── agent/AgentToolsSecurityTest.java
            └── CodeTuiApplicationGateTest.java
```

---

## Task 1: 父 pom 接入模块 + TamboUI BOM

**Files:**
- Modify: `pom.xml`（根）

- [ ] **Step 1: `<modules>` 末尾追加模块**

在 `pom.xml` 的 `<modules>` 内 `springai-jline-demo` 之后加：

```xml
        <module>springai-code-tui</module>
```

- [ ] **Step 2: `<properties>` 追加 TamboUI 版本**

在根 `<properties>` 内（`jline.version` 附近）加：

```xml
        <!-- TamboUI（Java TUI，仿 ratatui）；group dev.tamboui，实测有 tamboui-bom -->
        <tamboui.version>0.4.0</tamboui.version>
```

> 注：`spring-ai-agent-utils.version`（0.10.0）已在根 properties，无需再加。

- [ ] **Step 3: `<dependencyManagement>` 导入 tamboui-bom**

在根 `<dependencyManagement><dependencies>` 里、`spring-boot-dependencies` 之后加：

```xml
            <!-- TamboUI 版本统一管理（实测：dev.tamboui:tamboui-bom 存在，版本 0.2.0/0.3.0/0.4.0） -->
            <dependency>
                <groupId>dev.tamboui</groupId>
                <artifactId>tamboui-bom</artifactId>
                <version>${tamboui.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
```

- [ ] **Step 4: 验证父 pom 仍可解析（模块尚不存在，用 `validate` 只校验 pom 本身）**

```bash
mvn -q -N validate
```

预期：`BUILD SUCCESS`（`-N` 不递归子模块，此时 `springai-code-tui/pom.xml` 还没建）。若报 tamboui-bom 无法下载，先单独验证解析：`mvn -q dependency:get -Dartifact=dev.tamboui:tamboui-bom:0.4.0:pom`。

- [ ] **Step 5: 提交**

```bash
git add pom.xml && git commit -m "build(code-tui): 父 pom 接入 springai-code-tui 模块与 tamboui-bom"
```

---

## Task 2: 模块 pom + logback + 占位 main（能 build 出 jar）

**Files:**
- Create: `springai-code-tui/pom.xml`
- Create: `springai-code-tui/src/main/resources/logback.xml`
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/CodeTuiApplication.java`（占位 main，仅打印一行）

- [ ] **Step 1: 建模块 pom**（照搬 `springai-agent-demo` 打包结构；依赖见 spec §4.2）

`springai-code-tui/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example.springai</groupId>
        <artifactId>springai-agentdemo</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>springai-code-tui</artifactId>
    <name>springai-code-tui</name>
    <description>基于 Spring AI 2.0 的代码编写智能体，TamboUI 单栏 TUI（纯 Java，原始 API）</description>

    <dependencies>
        <!-- 模型 -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-deepseek</artifactId>
        </dependency>
        <!-- ChatClient / 记忆 / advisor；传递带入 spring-ai-model、reactor-core -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-client-chat</artifactId>
        </dependency>
        <!-- 5 个工具 + AgentEnvironment -->
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-agent-utils</artifactId>
            <version>${spring-ai-agent-utils.version}</version>
        </dependency>

        <!-- TamboUI：中层 TuiRunner/TuiConfig 在 tamboui-tui；部件在 tamboui-widgets；后端 jline3 -->
        <dependency>
            <groupId>dev.tamboui</groupId>
            <artifactId>tamboui-tui</artifactId>
        </dependency>
        <dependency>
            <groupId>dev.tamboui</groupId>
            <artifactId>tamboui-widgets</artifactId>
        </dependency>
        <dependency>
            <groupId>dev.tamboui</groupId>
            <artifactId>tamboui-jline3-backend</artifactId>
        </dependency>

        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>${project.artifactId}</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <executions>
                    <execution>
                        <id>copy-deps</id>
                        <phase>package</phase>
                        <goals><goal>copy-dependencies</goal></goals>
                        <configuration>
                            <outputDirectory>${project.build.directory}/lib</outputDirectory>
                            <includeScope>runtime</includeScope>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>com.example.springai.codetui.CodeTuiApplication</mainClass>
                            <addClasspath>true</addClasspath>
                            <classpathPrefix>lib/</classpathPrefix>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.3</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: logback 写文件**（不污染 TUI；参考 agent-demo 若有则对齐）

`springai-code-tui/src/main/resources/logback.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.FileAppender">
        <file>${user.dir}/springai-code-tui.log</file>
        <append>false</append>
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <!-- 关键：不要 CONSOLE appender，任何 stdout/stderr 日志都会撕裂 TUI 画面 -->
    <root level="INFO">
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

- [ ] **Step 3: 占位 main**（下一 Task 才写真 TUI；先证明打包链路通）

`.../codetui/CodeTuiApplication.java`：

```java
package com.example.springai.codetui;

public class CodeTuiApplication {
    public static void main(String[] args) {
        System.out.println("springai-code-tui skeleton OK");
    }
}
```

- [ ] **Step 4: build 并确认依赖解析 + reactor-core 传递带入**（spec §4.2 遗留确认项）

```bash
mvn -q -pl springai-code-tui -am package
ls springai-code-tui/target/lib/ | grep -i reactor-core
ls springai-code-tui/target/lib/ | grep -i tamboui
```

预期：`BUILD SUCCESS`；`reactor-core-*.jar` 存在（证实随 client-chat 带入，流式 reactive 栈就绪）；`tamboui-*.jar` 存在。

- [ ] **Step 5: 跑占位 jar**

```bash
java -jar springai-code-tui/target/springai-code-tui.jar
```

预期输出：`springai-code-tui skeleton OK`

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/pom.xml springai-code-tui/src/main/resources/logback.xml springai-code-tui/src/main/java/com/example/springai/codetui/CodeTuiApplication.java
git commit -m "build(code-tui): 模块 pom + logback + 占位 main，打包链路跑通"
```

---

## Task 3（里程碑 1）: TamboUI 骨架——单栏 Hello，输入回显，Ctrl+C 退出

> **本 Task 的第一步是核实 TamboUI 的 widget 绘制 API**（spec §13「中」风险；javap 此前只稳钉了循环 API：`TuiRunner.create/run(EventHandler,Renderer)`、`TuiConfig.builder().tickRate(Duration)`、`KeyEvent.isKey(KeyCode.ENTER)/isCtrlC()/isChar(char)`、`Frame.renderWidget(Widget,Rect)/area()/setCursorPosition`、`EventHandler.handle(Event,TuiRunner):boolean`、`Renderer.render(Frame)`）。绘制部件（Paragraph/Block/List）的确切类名/构造方式仅部分核实，**先核实再写渲染代码**。

**Files:**
- Modify: `.../codetui/CodeTuiApplication.java`（改为真启动 TUI）
- Create: `.../codetui/ui/CodeTuiView.java`（先只做 Hello + 输入回显，不接 agent）
- Create: `.../codetui/ui/ConversationState.java`（先最小：消息列表 + 输入缓冲）

- [ ] **Step 1: ⚠️ javap 核实 widget API**（在 `target/lib` 或本地仓库对 tamboui jar 执行）

```bash
JAR=$(ls springai-code-tui/target/lib/tamboui-widgets-*.jar)
for c in $(jar tf "$JAR" | grep -E 'Paragraph|Block|Text|Line|List' | grep '\.class$' | sed 's#/#.#g;s#\.class$##'); do echo "== $c =="; javap -cp "$JAR" "$c" 2>/dev/null | head -20; done 2>&1 | head -120
# 同法核实 dev.tamboui.tui.TuiRunner / TuiConfig / Frame / EventHandler / Renderer 与 KeyEvent/KeyCode（在 tamboui-tui / tamboui-core jar）
```

记录：`Paragraph` 的构造/文本设置方式、`Block`（边框/标题）、`Frame.renderWidget` 接受的 `Widget` 与 `Rect` 类型全名、`Rect` 如何按区域切分（对话区 / 输入行 / 状态栏）。**下面的渲染代码按核实结果落笔**，若 API 名不同则替换（保持结构：三段式布局 + 光标）。

- [ ] **Step 2: `ConversationState` 最小版**（本 Task 只用到消息 + 输入缓冲；完整线程安全版在 Task 5 补测补功能）

```java
package com.example.springai.codetui.ui;

import java.util.ArrayList;
import java.util.List;

/** 线程安全共享状态（本 Task 仅最小可用；Task 5 补齐流式缓冲/运行态/todo 并加并发测试）。 */
public final class ConversationState {
    public enum Status { IDLE, THINKING, RUNNING_TOOL }

    private final List<String> transcript = new ArrayList<>();
    private final StringBuilder input = new StringBuilder();

    public synchronized void appendLine(String line) { transcript.add(line); }
    public synchronized List<String> transcriptSnapshot() { return List.copyOf(transcript); }

    public synchronized void typeChar(char c) { input.append(c); }
    public synchronized void backspace() { if (input.length() > 0) input.deleteCharAt(input.length() - 1); }
    public synchronized String takeInput() { String s = input.toString(); input.setLength(0); return s; }
    public synchronized String currentInput() { return input.toString(); }
}
```

- [ ] **Step 3: `CodeTuiView` 骨架**（TuiRunner + tickRate + 三段布局 + 按键；不接 agent，Enter 先把输入回显到对话区）

按 Step 1 核实的真实 API 写。结构固定：

```java
package com.example.springai.codetui.ui;

// import dev.tamboui.tui.*;  // 具体类名以 Step 1 javap 为准
import java.time.Duration;
import java.util.function.Consumer;

/**
 * TamboUI 单栏视图：TuiConfig.tickRate 周期重绘；render 画「对话区 / 输入行 / 状态栏」；
 * event 处理 输入字符 / Backspace / Enter / Ctrl+C。本 Task 不接 agent：Enter 时把输入回显。
 */
public final class CodeTuiView {
    private final ConversationState state;
    private final Consumer<String> onSubmit;   // Task 6 接 agent.submit；本 Task 传「回显」闭包

    public CodeTuiView(ConversationState state, Consumer<String> onSubmit) {
        this.state = state;
        this.onSubmit = onSubmit;
    }

    public void run() {
        // TuiConfig config = TuiConfig.builder().tickRate(Duration.ofMillis(33)).build(); // ~30fps
        // TuiRunner.create(config).run(this::handleEvent, this::render);
        //   handleEvent(Event, TuiRunner): boolean —— 见下
        //   render(Frame): void —— 见下
    }

    // boolean handleEvent(Event e, TuiRunner runner):
    //   若 KeyEvent:
    //     isCtrlC()          -> runner.stop()/退出；return true
    //     isKey(ENTER)       -> String text = state.takeInput();
    //                           if (!text.isBlank()) { state.appendLine("你> " + text); onSubmit.accept(text); }
    //                           return true
    //     isKey(BACKSPACE)   -> state.backspace(); return true
    //     isChar(c)          -> state.typeChar(c); return true
    //   其它 return false（TickEvent 由 tickRate 触发重绘，不需在此处理）

    // void render(Frame f):
    //   Rect area = f.area();
    //   把 area 竖切三块：transcript 区（占大部分）/ 输入行（1 行，前缀 "> "）/ 状态栏（1 行）
    //   transcript：state.transcriptSnapshot() 末 N 行填进 Paragraph/Block（带边框），renderWidget 到对话区
    //   输入行：渲染 "> " + state.currentInput()，f.setCursorPosition 放到输入末尾
    //   状态栏：静态提示 "Enter 发送 · Ctrl+C 退出"
}
```

- [ ] **Step 4: main 启动 TUI（回显模式，暂不建模型）**

```java
package com.example.springai.codetui;

import com.example.springai.codetui.ui.CodeTuiView;
import com.example.springai.codetui.ui.ConversationState;

public class CodeTuiApplication {
    public static void main(String[] args) {
        ConversationState state = new ConversationState();
        // 本 Task：onSubmit 只回显，证明输入/渲染/退出闭环
        CodeTuiView view = new CodeTuiView(state, text -> state.appendLine("（回显）AI> " + text));
        view.run();
    }
}
```

- [ ] **Step 5: 手动验证**（TUI 无法自动断言；spec §11 明确 TUI 以手动验证为主）

```bash
mvn -q -pl springai-code-tui -am package
java -jar springai-code-tui/target/springai-code-tui.jar
```

预期：进入单栏界面；能看到带边框的对话区、底部输入行（光标跟随）、状态栏；打字有回显、Backspace 生效、Enter 后「你> …」与「（回显）AI> …」出现在对话区；`Ctrl+C` 干净退出、终端状态复原；`springai-code-tui.log` 有日志、屏幕无日志刷屏。逐条对不上则回到对应 render/event 分支修，勿继续。

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/
git commit -m "feat(code-tui): 里程碑1 TamboUI 单栏骨架（tickRate 重绘/输入回显/Ctrl+C 退出）"
```

---

## Task 4（里程碑 2·TDD）: `AgentListener` 接缝 + `ConversationState` 完整化（含并发/取消测试）

> 里程碑 2 全部 headless，可 JUnit 断言。先定接缝与状态，再造工具、装 Agent。**先写测试，再写实现。**

**Files:**
- Create: `.../codetui/agent/AgentListener.java`
- Modify: `.../codetui/ui/ConversationState.java`（补流式缓冲、运行态、todo 快照、按 turnId 过滤）
- Create: `.../test/.../ui/ConversationStateTest.java`
- Create: `.../test/.../agent/AgentListenerCancelTest.java`

- [ ] **Step 1: 定 `AgentListener`**（纯 Java 类型，每方法带 turnId；spec §5。`onTurnStarted` 在 submit 顶部**同步**调用，让状态无竞态地记录「当前接受的 turnId」，从根上消除取消过滤竞态）

```java
package com.example.springai.codetui.agent;

import java.util.List;

/**
 * CodingAgent → UI 的唯一接缝。只用纯 Java 类型（不泄漏任何 Spring AI 类型）。
 * 每个方法都带 turnId，供 UI 过滤「已取消回合」的迟到事件。
 */
public interface AgentListener {
    void onTurnStarted(long turnId);                 // submit 顶部同步调用
    void onUserMessage(long turnId, String text);
    void onAssistantToken(long turnId, String token);
    void onToolStarted(long turnId, String toolName, String input);
    void onToolFinished(long turnId, String toolName, String output, boolean ok);
    void onTodoUpdated(long turnId, List<String> todoLines);   // Todos 转成可显示的行
    void onTurnComplete(long turnId);
    void onError(long turnId, Throwable error);
}
```

- [ ] **Step 2: 先写 `ConversationStateTest`**（并发 + 快照 + 取消过滤；spec §11）

要点断言：
1. **并发无异常/一致快照**：起 N 个线程狂写 `onAssistantToken(turn, ...)`，主线程反复 `transcriptSnapshot()`，全程无 `ConcurrentModificationException`，最终 token 数 == 写入数。
2. **取消过滤**：`onTurnStarted(1)` 后追加若干 token；`cancelCurrent()`（或 `onTurnStarted(2)` 切走）后，再来 `onAssistantToken(1, ...)` 的迟到 token 被丢弃——断言取消后 transcript 不再增长。
3. **运行态**：`onToolStarted` → 状态 `RUNNING_TOOL`；`onTurnComplete` → `IDLE`。

`ConversationState` 需实现 `AgentListener`（视图读、Agent 写）。补充字段：`volatile long acceptingTurnId`、流式助手行缓冲、`volatile Status`、`List<String> todo`。所有跨线程读写 `synchronized`/`volatile`。迟到过滤：任何带 turnId 的写入前先 `if (turnId != acceptingTurnId) return;`。`onTurnStarted(t)` 里 `acceptingTurnId = t`。UI 层「Esc 取消」调 `cancelCurrent()` → 把 `acceptingTurnId` 置为一个不会再被匹配的哨兵（如 `-1`）并将状态回 `IDLE`。

- [ ] **Step 3: 实现 `ConversationState`（implements AgentListener）让测试全绿**

```bash
mvn -q -pl springai-code-tui test -Dtest=ConversationStateTest
```

预期：`BUILD SUCCESS`，测试全绿。

- [ ] **Step 4: `AgentListenerCancelTest`**（把「取消后过滤」作为独立回归钉死：验证语义与 spec §6「UI 层取消」一致）

断言：模拟 turn=1 进行中 → `cancelCurrent()` → turn=1 的 `onAssistantToken/onToolFinished/onTurnComplete` 全部被忽略；新 `onTurnStarted(2)` 后 turn=2 的事件正常记录。

```bash
mvn -q -pl springai-code-tui test -Dtest=ConversationStateTest,AgentListenerCancelTest
```

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentListener.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/test/
git commit -m "feat(code-tui): AgentListener 接缝 + 线程安全 ConversationState（并发/取消过滤 TDD 绿）"
```

---

## Task 5（里程碑 2·TDD）: `ToolEventCallback` 装饰器（从 ToolContext 取 turnId）

**Files:**
- Create: `.../codetui/agent/ToolEventCallback.java`
- Create: `.../test/.../agent/ToolEventCallbackTest.java`

- [ ] **Step 1: 先写 `ToolEventCallbackTest`**（spec §11「工具装饰器异常」）

用一个假的 `ToolCallback` delegate（`getToolDefinition()` 返回定名、`call` 可正常返回或抛异常），断言：
1. 正常路径：`call(input, toolContext{turnId=7})` → 先 `onToolStarted(7, name, input)`、后 `onToolFinished(7, name, output, true)`，返回值透传。
2. 异常路径：delegate 抛异常 → `onToolFinished(7, name, <消息>, false)` 被调用、异常继续上抛（不吞错），listener 不卡死。
3. turnId 缺失兜底：`toolContext` 无 `turnId` 时用约定默认（如 `-1`），不 NPE。

- [ ] **Step 2: 实现 `ToolEventCallback`**（仿 `LoggingSkillCallback`：`implements ToolCallback`，代理 `getToolDefinition()`/`call(String)`/`call(String,ToolContext)`）

```java
package com.example.springai.codetui.agent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** ToolCallback 装饰器：执行前后经 AgentListener 发工具事件；turnId 从 ToolContext 取。 */
public final class ToolEventCallback implements ToolCallback {
    static final String TURN_ID_KEY = "turnId";

    private final ToolCallback delegate;
    private final AgentListener listener;

    public ToolEventCallback(ToolCallback delegate, AgentListener listener) {
        this.delegate = delegate;
        this.listener = listener;
    }

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }

    @Override public String call(String toolInput) { return call(toolInput, null); }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long turnId = extractTurnId(toolContext);
        String name = delegate.getToolDefinition().name();
        listener.onToolStarted(turnId, name, toolInput);
        try {
            String out = (toolContext == null) ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            listener.onToolFinished(turnId, name, out, true);
            return out;
        } catch (RuntimeException ex) {
            listener.onToolFinished(turnId, name, String.valueOf(ex.getMessage()), false);
            throw ex;
        }
    }

    private static long extractTurnId(ToolContext ctx) {
        if (ctx == null) return -1L;
        Object v = ctx.getContext().get(TURN_ID_KEY);   // ⚠️ 实现时 javap 核实 ToolContext 取值 API（getContext():Map）
        return (v instanceof Long l) ? l : -1L;
    }
}
```

> ⚠️ 核实点：`ToolContext` 的取值 API（预期 `getContext()` 返回 `Map<String,Object>`）与 `ToolDefinition.name()`、`ToolCallback` 所在包（`org.springframework.ai.tool.*`）。以 `javap` 为准。

- [ ] **Step 3: 测试绿 + 提交**

```bash
mvn -q -pl springai-code-tui test -Dtest=ToolEventCallbackTest
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/ToolEventCallback.java springai-code-tui/src/test/java/com/example/springai/codetui/agent/ToolEventCallbackTest.java
git commit -m "feat(code-tui): ToolEventCallback 装饰器（turnId from ToolContext，异常仍发 finished）TDD 绿"
```

---

## Task 6（里程碑 2·TDD）: `AgentTools` 工厂 + 安全边界记录性测试

**Files:**
- Create: `.../codetui/agent/AgentTools.java`
- Create: `.../test/.../agent/AgentToolsSecurityTest.java`

- [ ] **Step 1: ⚠️ javap 核实 5 工具 + AgentEnvironment 的真实 API**（spec §3.5/§8/§13 已钉大部分；实现前再确认一遍构造与 `@Tool` 方法名、`ToolCallbacks.from` 所在包、`AgentEnvironment` 四个 KEY 常量名）

```bash
JAR=$(ls springai-code-tui/target/lib/spring-ai-agent-utils-*.jar)
for c in FileSystemTools ShellTools GrepTool GlobTool TodoWriteTool AgentEnvironment; do \
  fq=$(jar tf "$JAR" | grep -E "/$c\.class$" | sed 's#/#.#g;s#\.class$##'); \
  echo "== $fq =="; javap -cp "$JAR" "$fq" 2>/dev/null; done | head -160
# ToolCallbacks.from 在 spring-ai-model
```

- [ ] **Step 2: 先写 `AgentToolsSecurityTest`**（spec §11 路径越界；诚实钉住方案 B 现实）

1. **FileSystemTools 真沙箱**：以临时目录为 `allowedDirectory(root)` 建工具，直接调用其写/读方法访问 `root/../escape.txt` 或绝对路径 `/tmp/x` → 断言被拒（抛异常或返回错误），`root` 内正常路径可读写。
2. **记录性测试（防后人误以为安全）**：显式断言并注释「ShellTools/GrepTool/GlobTool 在 0.10.0 无路径沙箱」——例如构造 `GrepTool.builder().workingDirectory(root)` 后用绝对路径/`../` 参数仍能触达 root 外（若不便真跑越界，则以 `@DisplayName("记录：Shell/Grep/Glob 不受 root 限制——方案 B 已知残余风险")` 的断言钉住这一事实，并在注释引用 spec §9/§13）。

> 这条测试的价值是**固化认知**，不是证明安全。命名与注释必须让人一眼看到「这是已知不安全项」。

- [ ] **Step 3: 实现 `AgentTools`**（spec §8 装配 + §8 系统提示模板 + AgentEnvironment）

职责：入参 `DeepSeekChatModel model, Path root, AgentListener listener, AtomicLong activeTurnId, String sessionId`；产出装好工具/记忆/系统提示的 `ChatClient`。要点：

```java
// 造 5 工具（Grep/Glob 设 workingDirectory(root)；Todo 用闭包读 activeTurnId.get()）
var fs   = FileSystemTools.builder().allowedDirectory(root).build();
var sh   = ShellTools.builder().build();
var grep = GrepTool.builder().workingDirectory(root).build();
var glob = GlobTool.builder().workingDirectory(root).build();
var todo = TodoWriteTool.builder()
        .todoEventHandler(todos -> listener.onTodoUpdated(activeTurnId.get(), toLines(todos)))
        .build();

ToolCallback[] raw = ToolCallbacks.from(fs, sh, grep, glob, todo);      // spring-ai-model
ToolCallback[] decorated = new ToolCallback[raw.length];
for (int i = 0; i < raw.length; i++) decorated[i] = new ToolEventCallback(raw[i], listener);

ChatClient client = ChatClient.builder(model)
    .defaultSystem(s -> s.text(SYSTEM_TEMPLATE)
        .param(AgentEnvironment.ENVIRONMENT_INFO_KEY,             AgentEnvironment.info())
        .param(AgentEnvironment.GIT_STATUS_KEY,                   AgentEnvironment.gitStatus())
        .param(AgentEnvironment.AGENT_MODEL_KEY,                  "deepseek-chat")
        .param(AgentEnvironment.AGENT_MODEL_KNOWLEDGE_CUTOFF_KEY, MODEL_CUTOFF))
    .defaultTools((Object[]) decorated)
    .defaultAdvisors(MessageChatMemoryAdvisor.builder(
            MessageWindowChatMemory.builder().build()).build())
    .build();
// 不手动加 ToolCallingAdvisor（2.0 自动注册）；conversationId 每请求传，在 CodingAgent.submit 里
```

系统提示模板（spec §8）：定位代码编写智能体；先 Grep/Glob/读文件理解再改；多步先 TodoWrite 列计划；改完 Shell 验证；**诚实说明仅 FileSystemTools 有强制边界，Shell/Grep/Glob 无技术限制，操作前自我约束在 root 内**（不承诺「限定」）；嵌入 AgentEnvironment 四占位符。

> ⚠️ `MODEL_CUTOFF` 按 DeepSeek 手填常量字符串。`toLines(Todos)`：把 TodoWriteTool 的 Todo 列表转成可显示行（字段名以 javap 为准）。

- [ ] **Step 4: 测试绿 + 提交**

```bash
mvn -q -pl springai-code-tui test -Dtest=AgentToolsSecurityTest
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentTools.java springai-code-tui/src/test/java/com/example/springai/codetui/agent/AgentToolsSecurityTest.java
git commit -m "feat(code-tui): AgentTools 工厂（5 工具+装饰+记忆+AgentEnvironment 系统提示）与安全边界记录性测试"
```

---

## Task 7（里程碑 2）: `CodingAgent` 核心 + 流式语义 spike（硬验收）

> spec §12 里程碑 2 硬验收：**流式 + 工具 + 记忆 + 取消**必须实测。此 Task 需真实 DEEPSEEK_API_KEY，属集成 spike，不进 CI（用系统属性/环境变量守卫，缺 key 时跳过）。

**Files:**
- Create: `.../codetui/agent/CodingAgent.java`
- Create: `.../test/.../agent/CodingAgentSpikeIT.java`（集成 spike，`@EnabledIfEnvironmentVariable(named="DEEPSEEK_API_KEY", ...)`）

- [ ] **Step 1: 实现 `CodingAgent`**（spec §7 submit 伪码）

```java
public final class CodingAgent {
    private final ChatClient chatClient;
    private final AgentListener listener;
    private final String sessionId;
    private final AtomicLong activeTurnId;   // 与 AgentTools 共享同一实例

    /** 返回 Disposable 供 UI 存起来给 Esc 取消。 */
    public Disposable submit(String text) {
        long turnId = activeTurnId.incrementAndGet();
        listener.onTurnStarted(turnId);        // 同步：先锁定 acceptingTurnId，消除取消竞态
        listener.onUserMessage(turnId, text);
        return chatClient.prompt()
            .user(text)
            .toolContext(Map.of("turnId", turnId))
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))   // 2.0 必填
            .stream().chatClientResponse()                                   // Flux<ChatClientResponse>
            .doOnNext(resp -> handleChunk(resp, turnId))
            .doOnError(err -> handleError(err, turnId))
            .doOnComplete(() -> handleComplete(turnId))
            .subscribe();
    }

    // handleChunk: 从 resp 抽文本增量 → listener.onAssistantToken(turnId, delta)（⚠️ 抽取 API 见 Step 2）
    // handleError: listener.onError(turnId, err)
    // handleComplete: listener.onTurnComplete(turnId)
}
```

> `onChunk` 系列是 `CodingAgent` 内部方法，**不在 `AgentListener` 上**——`ChatClientResponse` 类型不泄漏给 UI（spec §7）。

- [ ] **Step 2: ⚠️ spike 实测确认 4 件事并记录**（spec §12 里程碑 2 清单）——写进 `CodingAgentSpikeIT`：
  1. **文本抽取**：`chatClientResponse()` 下从 `resp.chatResponse().getResult().getOutput().getText()`（以 javap/调试为准）能取到流式增量。
  2. **工具循环可观察**：让它「读一个临时文件」，断言收到 `onToolStarted/onToolFinished`（经装饰器）。
  3. **多轮记忆**：第 2 轮引用第 1 轮内容，断言模型答复体现记忆（同 sessionId）；观察工具中间消息是否入库（参考 `springai-agent-demo/.../ToolMemoryAdvisorDemo.java` 关于 advisor 顺序的现象），把结论记进测试注释。
  4. **取消**：submit 后立刻 `dispose()`，断言 **UI 层**「取消后不再追加 token」通过（硬指标）；后端是否真停**观察并记录**（不作硬验收，与 spec §6 两层取消一致）。

- [ ] **Step 3: 跑 spike（有 key 时）**

```bash
export DEEPSEEK_API_KEY=...   # 用户自备
mvn -q -pl springai-code-tui test -Dtest=CodingAgentSpikeIT
```

预期：4 项断言/记录通过。**未通过不得进入里程碑 3**（spec §13）。把关键结论（尤其记忆入库语义、后端能否停）追加到 spec §12 或本计划末尾「Spike 结论」。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/CodingAgent.java springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentSpikeIT.java
git commit -m "feat(code-tui): CodingAgent 核心 + 流式/工具/记忆/取消 spike（里程碑2 硬验收）"
```

---

## Task 8（里程碑 3）: 接 TUI——流式 token 内联 + 工具活动 + Todo + Esc 取消

**Files:**
- Modify: `.../codetui/ui/CodeTuiView.java`（渲染 transcript 含 token/工具/todo；Esc 调 dispose）
- Modify: `.../codetui/ui/ConversationState.java`（若需，渲染友好的组织：流式行合并、工具活动行、todo 区）
- Modify: `.../codetui/CodeTuiApplication.java`（建模型 + CodingAgent + 共享 AtomicLong，wire 到 view）

- [ ] **Step 1: main 组装真链路**（模型 bootstrap 照搬 `AgentDemoApplication`）

```java
// 读 DEEPSEEK_API_KEY（缺则提示）→ DeepSeekApi/DeepSeekChatModel（deepseek-chat）
ConversationState state = new ConversationState();      // implements AgentListener
AtomicLong activeTurnId = new AtomicLong();
Path root = Path.of(System.getProperty("user.dir"));
String sessionId = "code-tui-session";                  // v1 单会话，固定 id 即可（conversationId 每请求传给 memory advisor）
ChatClient client = AgentTools.build(model, root, state, activeTurnId, sessionId);
CodingAgent agent = new CodingAgent(client, state, sessionId, activeTurnId);
CodeTuiView view = new CodeTuiView(state, agent);       // view Enter→agent.submit；Esc→dispose
view.run();
```

- [ ] **Step 2: `CodeTuiView` 接 agent**：Enter → `Disposable d = agent.submit(text)`（存最近一个）；Esc → `if (d != null) d.dispose(); state.cancelCurrent();`（UI 层取消，spec §6）。render 里：transcript 快照渲染用户行、助手流式行（随 token 增长）、工具活动行（`🛠 name … ✓/✗`）、Todo 区（若非空）。

- [ ] **Step 3: 手动验证**（spec §14 验收 1-4）

```bash
export DEEPSEEK_API_KEY=...
cd /tmp && mkdir -p code-tui-play && cd code-tui-play   # 可丢弃目录
java -jar <abs>/springai-code-tui/target/springai-code-tui.jar
```

逐条对 spec §14：①多轮、助手**流式逐字**；②「读文件→改一处→shell 验证」看到**工具活动**且文件真被改（root 内）；③多步任务 **Todo 可见**并更新；④**Esc** 后不再追加 token、状态回 idle，Ctrl+C 干净退出、无日志刷屏。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/
git commit -m "feat(code-tui): 里程碑3 接 TUI（流式内联/工具活动/Todo 显示/Esc 取消）"
```

---

## Task 9（里程碑 4）: 启动安全门 + 横幅 + README（方案 B）

**Files:**
- Modify: `.../codetui/CodeTuiApplication.java`（TUI 起来前的安全门）
- Create: `.../test/.../CodeTuiApplicationGateTest.java`
- Create: `springai-code-tui/README.md`

- [ ] **Step 1: 抽可测的门控逻辑**（把决策抽成纯函数便于 JUnit，避免测真 stdin/TUI）

```java
/** 返回是否放行。env=CODE_TUI_I_UNDERSTAND 值、interactiveAnswer=交互输入（null 表示非交互）。 */
static boolean isCleared(String envUnderstand, String interactiveAnswer) {
    if ("1".equals(envUnderstand)) return true;
    return interactiveAnswer != null && interactiveAnswer.trim().equalsIgnoreCase("y");
}
```

- [ ] **Step 2: 先写 `CodeTuiApplicationGateTest`**（spec §11 启动门）

断言：`isCleared("1", null)` == true；`isCleared(null, "y")`/`("0","y")` == true；`isCleared(null, null)`/`(null,"n")`/`(null,"")` == false。

- [ ] **Step 3: 实现安全门 + 红色警示横幅**（spec §9）

main 开头（建模型前）：打印红色横幅——含 workspace root 绝对路径 + 「仅 FileSystemTools 受 root 沙箱；ShellTools/GrepTool/GlobTool **不受限**，智能体可执行任意命令、读盘任意位置」；读 `System.getenv("CODE_TUI_I_UNDERSTAND")`，若非 `1` 则普通 stdin 交互 `继续？[y/N]`；`isCleared(...)` 为 false 则打印「已取消」并 `System.exit(0)`。放行后再进模型/TUI。横幅用 ANSI 红（`[31m…[0m`），在 TUI 启动前打印不影响后续画面。

- [ ] **Step 4: README**（spec §9/§10 诚实声明）：模块用途；**安全声明**（Shell/Grep/Glob 不受限，只在可丢弃/干净版本控制目录运行，勿在 $HOME 或重要仓库根跑）；构建 `mvn -pl springai-code-tui -am package`；运行（设 key、cd 到可丢弃目录、`CODE_TUI_I_UNDERSTAND=1` 跳过交互）；操作键（Enter/Esc/Ctrl+C）；A 方案（SandboxedShellTool）记为 v1 之后增强。

- [ ] **Step 5: 测试绿 + 手动验证门 + 提交**

```bash
mvn -q -pl springai-code-tui test -Dtest=CodeTuiApplicationGateTest
# 手动：不设 env 直接回车/输 n → 拒绝启动；CODE_TUI_I_UNDERSTAND=1 → 直接进入
git add springai-code-tui/src/main/java/com/example/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/CodeTuiApplicationGateTest.java \
        springai-code-tui/README.md
git commit -m "feat(code-tui): 里程碑4 启动安全门 + 警示横幅 + README 诚实声明（方案 B）"
```

---

## Task 10: 收尾——全量测试 + 自检 + 验收对表

- [ ] **Step 1: 全模块 build + 全测试**

```bash
mvn -q -pl springai-code-tui -am package
mvn -q -pl springai-code-tui test    # 单测全绿（spike IT 缺 key 时自动跳过）
```

预期：`BUILD SUCCESS`；`ConversationStateTest`/`AgentListenerCancelTest`/`ToolEventCallbackTest`/`AgentToolsSecurityTest`/`CodeTuiApplicationGateTest` 全绿。

- [ ] **Step 2: 自检**（占位符扫描 + 类型一致 + spec 覆盖）
  - `grep -rn "TODO\|FIXME\|以 javap 为准\|见 Step" springai-code-tui/src` → 确认所有 ⚠️ 核实点已落实、无残留占位。
  - 接缝纯净：`grep -rn "tamboui\|dev\.tamboui" springai-code-tui/src/main/java/com/example/springai/codetui/agent` → 应为空（agent 包不 import TUI）。
  - `grep -rn "org.springframework.ai" springai-code-tui/src/main/java/com/example/springai/codetui/ui` → 应为空（UI 不 import Spring AI 类型，接缝只走 AgentListener）。
  - 逐条对 spec §14 验收 1-6 打勾。

- [ ] **Step 3: 最终提交**

```bash
git add -A && git commit -m "chore(code-tui): v1 收尾——全测试绿 + 自检 + 验收对表"
```

---

## 验收标准（对齐 spec §14）
1. 单栏 TUI 多轮对话，助手**流式**逐字出现。
2. 「读文件→改一处→shell 验证」：对话区见**工具活动**与结果，文件确被改（root 内）。
3. 多步任务 Todo 界面可见且随进度更新。
4. Esc **UI 层**中断（不再追加 token、回 idle）；Ctrl+C 干净退出；无日志刷屏。
5. 启动门生效：未放行拒绝启动；横幅如实声明 Shell/Grep/Glob 不受限。
6. 并发/取消/装饰器异常/越界四类测试 + 里程碑 2 流式 spike 全绿。

## 关键风险提示（执行时留意）
- **里程碑 2 spike 是闸门**（Task 7）：流式+工具+记忆+取消未实测通过，**不进** Task 8。
- **不臆测 API**：Task 3/5/6/7 的 ⚠️ 点必须先 `javap` 核实（TamboUI widget 绘制、ToolContext 取值、5 工具签名、ChatClientResponse 文本抽取）。
- **安全是方案 B（诚实降级）**，不是真安全：Shell/Grep/Glob 不受限须在横幅/README/记录性测试三处钉死。
```
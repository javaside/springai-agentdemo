# springai-code-tui Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `springai-agentdemo` 下新增模块 `springai-code-tui`——以 Spring AI 2.0 原始 API 为基础的「代码编写智能体」，用 TamboUI 做单栏对话式 TUI，v1 只接入 `spring-ai-agent-utils:0.10.0` 的 5 个核心编码工具。

**Architecture:** `CodingAgent`（核心，零 TUI/状态依赖，持 `ChatClient` + `AtomicLong activeTurnId`，implements `SubmitHandler`）── `AgentListener`（唯一接缝，纯 Java 类型，每方法带 `turnId`）── `CodeTuiView`（TamboUI TuiRunner 薄视图，只读 `ConversationState`，通过 `SubmitHandler` 提交并持有返回的 `Disposable`）。turnId 生成权在 `CodingAgent`，经 `toolContext` 下传给工具装饰器（Todo 亦经装饰器的 `ThreadLocal` 取到「执行它的那个回合」的 turnId，不读实时 `activeTurnId`），单向流向 UI。写状态在 Reactor 线程、读状态在 TuiRunner 线程 → `ConversationState` 线程安全。

**v1 单飞约束（关键，回应评审）：一次只允许一个回合在飞。** 强制单飞：**非 `IDLE` 状态下 Enter 被忽略并在状态栏提示「上一回合进行中，Esc 取消后再输入」**——不做「提交前自动取消上一回合」（留给 v2）。这样 `CodeTuiView` 只需持有单个 `Disposable`、Esc 取消当前回合即可。单飞是 UX 与「单个 Disposable」的简化，**不再是 Todo 正确性的前提**（见下）。判据数据源是 `ConversationState` 的状态位（headless 可测）。

**Todo turnId 靠 toolContext 绑定、不靠单飞（关键修订，回应二次评审）：** 早先设计让 Todo 闭包读实时 `activeTurnId.get()`，这在「Esc 取消后立刻重新提交」时会串轮——`dispose()` 不保证真停后端（§6），旧回合被孤儿化的工具线程仍可能触发 `todoEventHandler`，而此时 `activeTurnId` 已前进到新回合，旧 Todo 遂被误标成新 turnId 污染对话；且 `dispose()` 会退订，靠等终态回调「解锁」也不可靠（`doOnComplete/doOnError` 在取消时不触发）。**修正：Todo 事件的 turnId 与其它工具事件同源，从 `toolContext` 取**——`ToolEventCallback` 在调用被装饰工具期间用 `ThreadLocal<Long>` 记下当前 turnId（来自 `ToolContext`），`TodoWriteTool` 的 `todoEventHandler` 在**同线程同步**触发时读该 ThreadLocal。于是 Todo 永远带着「真正执行它的那个回合」的 turnId，被 `acceptingTurnId` 过滤器正确丢弃——**即便后端不停、即便回合并发也不串**。（⚠️ 前提：`todoEventHandler` 是在工具 `call` 内**同步**触发的；Task 6/7 spike 顺手确认这一点，若为异步派发则该 ThreadLocal 失效、需回退到 per-turn handler 方案。）

**视图不落 transcript（关键，回应评审）：** `CodeTuiView` 的 Enter 只做「`submit(text)` + 清空输入缓冲」，**绝不直接把用户行写进 transcript**；用户行统一由 `AgentListener.onUserMessage` 落库。这样从骨架期（Task 3 用回显 `SubmitHandler` 桩，桩自己落 transcript）到接真 agent（Task 8）视图代码零改动、绝不重复显示。

**Tech Stack:** 纯 Java 21、不依赖 Spring Boot、Spring AI 2.0 原始 API、DeepSeek 模型、TamboUI 0.4.0（`dev.tamboui`）、Project Reactor（随 client-chat 传递带入）、JUnit 5、Maven（`maven-jar-plugin` + `copy-dependencies` 打可运行 jar）。

参考 spec：`docs/superpowers/specs/2026-06-30-springai-code-tui-design.md`（本计划所有 §引用均指该 spec）

> **执行铁律（来自 spec 与用户反复强调）：不臆测 API。** 凡本计划标注「⚠️ 实现时用 `javap` 核实」的位置，必须先对已解析的 jar 跑 `javap` 确认真实签名再写代码；核实结果与本计划不符时，以字节码为准并在提交信息里记一句。TamboUI 的 widget 绘制 API 与 `ChatClientResponse` 文本抽取是两处仅部分核实的点，分别在 Task 3、Task 8 里作为**第一步**强制核实。

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
        │   │   │   ├── SubmitHandler.java         ← 提交接缝：Disposable submit(String)（CodingAgent 实现，View 依赖）
        │   │   │   ├── ToolEventCallback.java     ← ToolCallback 装饰器（从 ToolContext 取 turnId）
        │   │   │   ├── AgentTools.java            ← 工厂：造 5 工具 + 装饰 + 系统提示（含 AgentEnvironment）
        │   │   │   └── CodingAgent.java           ← 核心：submit/handleChunk/handleError/handleComplete，implements SubmitHandler
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

- [ ] **Step 4: 同一提交内建模块 pom（避免「根 pom 声明了模块但目录不存在」的破损中间态——评审指出的问题）**

> 根 pom 一旦把 `springai-code-tui` 列进 `<modules>`，任何普通聚合构建（`mvn install` 等）都会因模块缺失而失败。因此**必须在同一步/同一提交里就把模块 pom 建好**，聚合构建才始终可用。照搬 `springai-agent-demo` 打包结构；依赖见 spec §4.2。

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

- [ ] **Step 5: 聚合构建可用（模块已存在，验证根+模块 pom 都能解析）**

```bash
mvn -q -pl springai-code-tui -am validate
```

预期：`BUILD SUCCESS`。若报 tamboui-bom 无法下载，先单独验证：`mvn -q dependency:get -Dartifact=dev.tamboui:tamboui-bom:0.4.0:pom`。

- [ ] **Step 6: 提交（根 pom + 模块 pom 一并提交，无破损中间态）**

```bash
git add pom.xml springai-code-tui/pom.xml
git commit -m "build(code-tui): 父 pom 接入模块 + 模块 pom（tamboui-bom，聚合构建可用）"
```

---

## Task 2: logback + 占位 main（打包链路跑通）

**Files:**
- Create: `springai-code-tui/src/main/resources/logback.xml`
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/CodeTuiApplication.java`（占位 main，仅打印一行）

- [ ] **Step 1: logback 写文件**（不污染 TUI；参考 agent-demo 若有则对齐）

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

- [ ] **Step 2: 占位 main**（下一 Task 才写真 TUI；先证明打包链路通）

`.../codetui/CodeTuiApplication.java`：

```java
package com.example.springai.codetui;

public class CodeTuiApplication {
    public static void main(String[] args) {
        System.out.println("springai-code-tui skeleton OK");
    }
}
```

- [ ] **Step 3: build 并确认依赖解析 + reactor-core 传递带入**（spec §4.2 遗留确认项）

```bash
mvn -q -pl springai-code-tui -am package
ls springai-code-tui/target/lib/ | grep -i reactor-core
ls springai-code-tui/target/lib/ | grep -i tamboui
```

预期：`BUILD SUCCESS`；`reactor-core-*.jar` 存在（证实随 client-chat 带入，流式 reactive 栈就绪）；`tamboui-*.jar` 存在。

- [ ] **Step 4: 跑占位 jar**

```bash
java -jar springai-code-tui/target/springai-code-tui.jar
```

预期输出：`springai-code-tui skeleton OK`

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/resources/logback.xml springai-code-tui/src/main/java/com/example/springai/codetui/CodeTuiApplication.java
git commit -m "build(code-tui): logback + 占位 main，打包链路跑通"
```

---

## Task 3（里程碑 1）: TamboUI 骨架——单栏，输入回显，Esc/Ctrl+C，`SubmitHandler` 接缝定型

> **本会话已用 `javap` 对 `tamboui-*:0.4.0` 钉死下列真实签名，下面代码据此落笔（不是伪代码）：**
> - `TuiConfig.builder().tickRate(Duration).build()`；`TuiRunner.create(TuiConfig)`（`AutoCloseable`）→ `run(EventHandler, Renderer)`；`runner.quit()`（**不是 `stop()`**）、`runLater(Runnable)`、`runOnRenderThread(Runnable)`。
> - `EventHandler.handle(Event, TuiRunner):boolean`；`Renderer.render(Frame):void`。
> - `KeyEvent`（`implements Event`）：`isCtrlC()`、`isConfirm()`(Enter)、`isCancel()`(Esc)、`isDeleteBackward()`(Backspace)、`code():KeyCode`、`character():char`；`KeyCode.CHAR` 表示可打印字符键。
> - `Frame.area():Rect`、`renderWidget(dev.tamboui.widget.Widget, dev.tamboui.layout.Rect)`、`setCursorPosition(int,int)`。
> - `Rect(int x,int y,int w,int h)` + `x()/y()/width()/height()`；`Paragraph.from(String):Paragraph`（`Block.bordered()` 边框留作打磨）。
> - **唯一遗留待确认**（Step 1 一条命令核实）：`Paragraph implements dev.tamboui.widget.Widget`（供 `renderWidget`）。若否，改用 `Paragraph.builder()....build()` 的产物类型。

**Files:**
- Create: `.../codetui/agent/SubmitHandler.java`（提交接缝，View 与 Agent 共用）
- Modify: `.../codetui/CodeTuiApplication.java`（改为真启动 TUI，回显桩）
- Create: `.../codetui/ui/CodeTuiView.java`（**最终形态**：接 `SubmitHandler`，不落 transcript，含单飞 guard 与 Esc）
- Create: `.../codetui/ui/ConversationState.java`（最小版：transcript + 输入缓冲 + 状态位；Task 4 补流式/todo/并发测试）

- [ ] **Step 1: ⚠️ 确认 `Paragraph` 是 `Widget`（仅此一条遗留核实）**

```bash
JAR=$(ls springai-code-tui/target/lib/tamboui-widgets-*.jar)
javap -cp "$JAR" dev.tamboui.widgets.paragraph.Paragraph 2>/dev/null | head -3
```

预期首行含 `implements ... dev.tamboui.widget.Widget`。若不是，把下方 `renderWidget(Paragraph.from(s), rect)` 换成 build 出的 Widget 实例（结构不变）。

- [ ] **Step 2: `SubmitHandler` 接缝**（回应评审②：View 需要拿回 `Disposable`，故不是 `Consumer<String>`）

```java
package com.example.springai.codetui.agent;

import reactor.core.Disposable;

/** 提交一次对话，返回可取消句柄。CodingAgent 实现它；骨架期用回显桩实现（返回 null）。 */
@FunctionalInterface
public interface SubmitHandler {
    Disposable submit(String text);
}
```

- [ ] **Step 3: `ConversationState` 最小版**（含状态位以支撑单飞 guard；完整线程安全/流式/todo 版在 Task 4）

```java
package com.example.springai.codetui.ui;

import java.util.ArrayList;
import java.util.List;

/** 线程安全共享状态（本 Task 最小可用；Task 4 补流式缓冲/todo/turnId 过滤并加并发测试）。 */
public final class ConversationState {
    public enum Status { IDLE, THINKING, RUNNING_TOOL }

    private final List<String> transcript = new ArrayList<>();
    private final StringBuilder input = new StringBuilder();
    private volatile Status status = Status.IDLE;

    public synchronized void appendLine(String line) { transcript.add(line); }
    public synchronized List<String> transcriptSnapshot() { return List.copyOf(transcript); }

    public synchronized void typeChar(char c) { input.append(c); }
    public synchronized void backspace() { if (input.length() > 0) input.deleteCharAt(input.length() - 1); }
    public synchronized String takeInput() { String s = input.toString(); input.setLength(0); return s; }
    public synchronized String currentInput() { return input.toString(); }

    public boolean isIdle() { return status == Status.IDLE; }
    public Status status() { return status; }
    /** Esc 取消当前回合：状态回 IDLE（Task 4 会叠加 turnId 过滤）。 */
    public void cancelCurrent() { this.status = Status.IDLE; }
}
```

- [ ] **Step 4: `CodeTuiView` 最终形态**（用本会话已钉死的 API；**View 只提交不落 transcript**；单飞 guard；Esc 取消当前回合）

```java
package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.SubmitHandler;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.List;

/**
 * TamboUI 单栏视图。tickRate 周期重绘；render 画「对话区/输入行/状态栏」；
 * event 处理 字符/Backspace/Enter/Esc/Ctrl+C。
 * 关键：Enter 只 submit + 清输入，绝不直接写 transcript（用户行由 onUserMessage 统一落）。
 */
public final class CodeTuiView implements EventHandler, Renderer {
    private final ConversationState state;
    private final SubmitHandler onSubmit;
    private Disposable current;   // 单飞：任一时刻至多一个活跃回合

    public CodeTuiView(ConversationState state, SubmitHandler onSubmit) {
        this.state = state;
        this.onSubmit = onSubmit;
    }

    public void run() throws Exception {
        TuiConfig cfg = TuiConfig.builder().tickRate(Duration.ofMillis(33)).build();  // ~30fps
        try (TuiRunner runner = TuiRunner.create(cfg)) {
            runner.run(this, this);
        }
    }

    @Override
    public boolean handle(Event e, TuiRunner runner) {
        if (e instanceof TickEvent) return true;  // 周期重绘：run 循环仅在 handle 返回 true 时 render（见文末⚠️修正）
        if (!(e instanceof KeyEvent k)) return false;
        if (k.isCtrlC()) { runner.quit(); return true; }
        if (k.isCancel()) {                       // Esc：UI 层取消当前回合
            if (current != null) { current.dispose(); current = null; }
            state.cancelCurrent();
            return true;
        }
        if (k.isConfirm()) {                      // Enter
            if (!state.isIdle()) return true;     // 单飞：上一回合进行中，忽略输入（状态栏已提示）
            String text = state.takeInput();
            if (!text.isBlank()) current = onSubmit.submit(text);   // 不落 transcript
            return true;
        }
        if (k.isDeleteBackward()) { state.backspace(); return true; }
        // 可打印输入：用 codePoint 支持 CJK/非 ASCII，不再仅认 KeyCode.CHAR（否则中文被漏掉）
        int cp = k.codePoint();
        if (cp > 0 && !Character.isISOControl(cp) && !k.hasCtrl() && !k.hasAlt()) {
            state.typeString(new String(Character.toChars(cp)));
            return true;
        }
        return false;
    }
    // ⚠️ 关键修正（里程碑1 实测，推翻原假设）：TuiRunner.run() 循环【仅在 handle 返回 true 时 render】，
    //    tickRate 只按帧率把 TickEvent 投进事件队列、并不自动重绘。故 handle 首行必须：
    //        if (e instanceof TickEvent) return true;   // 否则后台线程写状态永不刷出——被动重绘闸门失败
    //    另：光标列用 CharWidth.of(prompt)（显示宽度、CJK 双宽），非 prompt.length()。

    @Override
    public void render(Frame f) {
        Rect a = f.area();
        int h = a.height();
        Rect body    = new Rect(a.x(), a.y(),        a.width(), Math.max(0, h - 2));
        Rect inputR  = new Rect(a.x(), a.y() + h - 2, a.width(), 1);
        Rect statusR = new Rect(a.x(), a.y() + h - 1, a.width(), 1);

        List<String> all = state.transcriptSnapshot();
        String shown = String.join("\n", tail(all, body.height()));
        f.renderWidget(Paragraph.from(shown), body);

        String prompt = "> " + state.currentInput();
        f.renderWidget(Paragraph.from(prompt), inputR);

        String hint = state.isIdle()
                ? "Enter 发送 · Esc 取消 · Ctrl+C 退出"
                : "上一回合进行中，Esc 取消后再输入 · Ctrl+C 退出";
        f.renderWidget(Paragraph.from(hint), statusR);

        f.setCursorPosition(inputR.x() + prompt.length(), inputR.y());  // 宽字符光标偏移暂简化，打磨阶段再校
    }

    private static List<String> tail(List<String> lines, int n) {
        if (n <= 0 || lines.size() <= n) return lines;
        return lines.subList(lines.size() - n, lines.size());
    }
}
```

- [ ] **Step 5: main 启动 TUI（回显桩：桩自己落 transcript，返回 null Disposable）**

```java
package com.example.springai.codetui;

import com.example.springai.codetui.ui.CodeTuiView;
import com.example.springai.codetui.ui.ConversationState;

public class CodeTuiApplication {
    public static void main(String[] args) throws Exception {
        ConversationState state = new ConversationState();
        // 骨架桩：SubmitHandler 由桩落 transcript（真 agent 时改由 onUserMessage 落，View 代码不变）
        CodeTuiView view = new CodeTuiView(state, text -> {
            state.appendLine("你> " + text);
            state.appendLine("（回显）AI> " + text);
            return null;   // 骨架无真回合
        });
        // 【被动重绘自检——里程碑1 地基验证，Task 8 前删除】后台线程隔 1s 追加一行，
        // 不碰键盘该行也应出现 → 证明 tickRate 周期重绘对「后台线程写状态」生效
        // （整个流式 UI 都押在这条行为上；此前假设仅由 javap 核了签名、未验运行时行为）。
        Thread probe = new Thread(() -> {
            for (int i = 1; i <= 5; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                state.appendLine("[被动重绘自检] tick #" + i + "（无按键即出现即通过）");
            }
        }, "passive-repaint-probe");
        probe.setDaemon(true);
        probe.start();
        view.run();
    }
}
```

- [ ] **Step 6: 手动验证**（TUI 无法自动断言；spec §11 明确 TUI 以手动验证为主）

```bash
mvn -q -pl springai-code-tui -am package
java -jar springai-code-tui/target/springai-code-tui.jar
```

预期：进入单栏界面（对话区/输入行/状态栏）；打字有回显、Backspace 生效、Enter 后「你> …」「（回显）AI> …」出现在对话区、输入行清空；`Esc` 与 `Ctrl+C` 均不崩（Ctrl+C 干净退出、终端复原）；`springai-code-tui.log` 有日志、屏幕无日志刷屏。逐条对不上则回对应分支修，勿继续。

**⚠️ 里程碑1 地基硬验收（不可跳过）：`[被动重绘自检] tick #1..5` 必须在「完全不碰键盘」的情况下每秒自动出现一行。** 这验证的是整个流式 UI 的地基——「后台线程写状态 → tick 周期重绘自动刷出」——`javap` 只能核签名、核不了这个运行时行为。若**只有按键时才刷新、不按键就不动**，说明 tickRate 重绘的假设不成立：**停在此处**，改用 spec §3.9 的 `runner.runLater(...)`/`runOnRenderThread(...)` 主动投递重绘，验证通过后再继续——**绝不能带着这个未验假设进 Task 4~8**（否则到 Task 8 接真 agent 才发现流式不刷，代价最大）。自检通过后，把 `passive-repaint-probe` 那段从 main 删除。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/
git commit -m "feat(code-tui): 里程碑1 TamboUI 单栏骨架（真实 API/SubmitHandler 接缝/单飞 guard/Esc/Ctrl+C）"
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
4. **单飞判据（回应评审①）**：初始 `isIdle()==true`；`onTurnStarted(1)` 后 `isIdle()==false`（View 据此在 Enter 时忽略新输入）；`onTurnComplete(1)`/`onError(1,..)`/`cancelCurrent()` 任一后 `isIdle()==true` 恢复可提交。这是 v1「一次只允许一个回合在飞」的数据源，配合 `CodeTuiView` 的 Enter guard 与单个 `Disposable`。（注：Todo 不串轮已由 Task 5 的 `ToolEventCallback` ThreadLocal 从根上保证，**不依赖**本判据——见总纲「Todo turnId 靠 toolContext 绑定」。）

`ConversationState`（在 Task 3 已建最小版）本 Task**扩充**为实现 `AgentListener`（视图读、Agent 写），**保留** Task 3 的 `isIdle()/status()/cancelCurrent()/transcript/输入缓冲`。新增字段：`volatile long acceptingTurnId`、流式助手行缓冲、`List<String> todo`。所有跨线程读写 `synchronized`/`volatile`。迟到过滤：任何带 turnId 的写入前先 `if (turnId != acceptingTurnId) return;`。状态机：`onTurnStarted(t)` → `acceptingTurnId=t; status=THINKING`；`onToolStarted` → `RUNNING_TOOL`；`onToolFinished` → `THINKING`；`onTurnComplete`/`onError` → `IDLE`。`cancelCurrent()`（Esc）→ `acceptingTurnId=-1`（不再匹配任何回合）且 `status=IDLE`。

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
4. **ThreadLocal 绑定（回应二次评审：Todo 不串轮的根**）：delegate 的 `call` 内部读 `ToolEventCallback.currentTurnId()` 应等于本次 `toolContext` 的 turnId（如 7）；`call` 返回后 `currentTurnId()` 恢复为外层值（嵌套安全）。这条钉住「Todo 事件从 toolContext 取 turnId、与 token/工具事件同源」。

- [ ] **Step 2: 实现 `ToolEventCallback`**（仿 `LoggingSkillCallback`：`implements ToolCallback`，代理 `getToolDefinition()`/`call(String)`/`call(String,ToolContext)`）

```java
package com.example.springai.codetui.agent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** ToolCallback 装饰器：执行前后经 AgentListener 发工具事件；turnId 从 ToolContext 取。 */
public final class ToolEventCallback implements ToolCallback {
    static final String TURN_ID_KEY = "turnId";

    /** TodoWriteTool 的 todoEventHandler 只收 Todos、拿不到 turnId；用 ThreadLocal 把「正在执行的工具」的
     *  turnId 传给同线程同步触发的 handler，从而 Todo 与其它工具事件同源（不读实时 activeTurnId，取消后不串轮）。 */
    private static final ThreadLocal<Long> CURRENT_TURN = ThreadLocal.withInitial(() -> -1L);
    public static long currentTurnId() { return CURRENT_TURN.get(); }

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
        Long prev = CURRENT_TURN.get();
        CURRENT_TURN.set(turnId);   // 供 TodoWriteTool.todoEventHandler 同线程读取真正执行回合的 turnId
        try {
            String out = (toolContext == null) ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            listener.onToolFinished(turnId, name, out, true);
            return out;
        } catch (RuntimeException ex) {
            listener.onToolFinished(turnId, name, String.valueOf(ex.getMessage()), false);
            throw ex;
        } finally {
            CURRENT_TURN.set(prev);
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

职责：入参 `DeepSeekChatModel model, Path root, AgentListener listener, String sessionId`；产出装好工具/记忆/系统提示的 `ChatClient`。（**修订**：不再需要 `AtomicLong activeTurnId` 入参——Todo turnId 改由 `ToolEventCallback.currentTurnId()` 提供；`activeTurnId` 仅 `CodingAgent` 自持用于生成 id。）要点：

```java
// 造 5 工具（Grep/Glob 设 workingDirectory(root)；Todo 的 turnId 走 ToolEventCallback 的 ThreadLocal，见下 todoEventHandler）
var fs   = FileSystemTools.builder().allowedDirectory(root).build();
var sh   = ShellTools.builder().build();
var grep = GrepTool.builder().workingDirectory(root).build();
var glob = GlobTool.builder().workingDirectory(root).build();
var todo = TodoWriteTool.builder()
        // turnId 不读 activeTurnId.get()（取消后会串轮），改读 ToolEventCallback 的 ThreadLocal——
        // handler 在被装饰工具的 call 内同步触发，故拿到真正执行回合的 turnId
        .todoEventHandler(todos -> listener.onTodoUpdated(ToolEventCallback.currentTurnId(), toLines(todos)))
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
- Create: `.../codetui/agent/CodingAgent.java`（`implements SubmitHandler`）
- Create: `.../test/.../agent/CodingAgentSpikeTest.java`（命名用 `*Test` 而非 `*IT`——回应评审⑦：`*IT` 走 failsafe，`mvn test` 默认不跑；用 `*Test` + `@EnabledIfEnvironmentVariable(named="DEEPSEEK_API_KEY", matches=".+")`，则**有 key 自动跑、无 key 自动跳过**，不需 `-Dtest=` 也能纳入 `mvn test`）

- [ ] **Step 1: 实现 `CodingAgent`**（spec §7 submit 伪码；`implements SubmitHandler` 让 View 直接依赖它）

```java
public final class CodingAgent implements SubmitHandler {
    private final ChatClient chatClient;
    private final AgentListener listener;
    private final String sessionId;
    private final AtomicLong activeTurnId;   // CodingAgent 自持，仅用于生成 turnId（AgentTools 不再依赖）

    /** 返回 Disposable 供 UI 存起来给 Esc 取消。 */
    @Override
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

- [ ] **Step 2: ⚠️ spike 实测确认 4 件事并记录**（spec §12 里程碑 2 清单）——写进 `CodingAgentSpikeTest`：
  1. **文本抽取**：`chatClientResponse()` 下从 `resp.chatResponse().getResult().getOutput().getText()`（以 javap/调试为准）能取到流式增量。
  2. **工具循环可观察**：让它「读一个临时文件」，断言收到 `onToolStarted/onToolFinished`（经装饰器）。
  3. **多轮记忆**：第 2 轮引用第 1 轮内容，断言模型答复体现记忆（同 sessionId）；观察工具中间消息是否入库（参考 `springai-agent-demo/.../ToolMemoryAdvisorDemo.java` 关于 advisor 顺序的现象），把结论记进测试注释。
  4. **取消**：submit 后立刻 `dispose()`，断言 **UI 层**「取消后不再追加 token」通过（硬指标）；后端是否真停**观察并记录**（不作硬验收，与 spec §6 两层取消一致）。
  5. **Todo turnId 正确（回应二次评审）**：让它列一个多步计划以强制触发 `TodoWrite`，断言 `onTodoUpdated(turnId, ...)` 的 turnId **等于当前回合且非 `-1`**——这实测钉死「`todoEventHandler` 同线程同步触发、`ToolEventCallback` 的 ThreadLocal 生效」（[计划:11] 遗留的唯一假设）；再 `dispose()` 取消后提交新回合，断言旧回合迟到的 Todo 被 `acceptingTurnId` 过滤、不污染新回合。**若观察到 turnId 为 `-1` 或串到新回合**，说明 handler 是异步派发、ThreadLocal 失效 → 回退 per-turn handler（见总纲「Todo turnId 靠 toolContext 绑定」修订），修好再进里程碑 3。

- [ ] **Step 3: 跑 spike（有 key 时）**

```bash
export DEEPSEEK_API_KEY=...   # 用户自备
mvn -q -pl springai-code-tui test -Dtest=CodingAgentSpikeTest
```

预期：4 项断言/记录通过。**未通过不得进入里程碑 3**（spec §13）。把关键结论（尤其记忆入库语义、后端能否停）追加到 spec §12 或本计划末尾「Spike 结论」。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/CodingAgent.java springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentSpikeTest.java
git commit -m "feat(code-tui): CodingAgent 核心 + 流式/工具/记忆/取消 spike（里程碑2 硬验收）"
```

---

## Task 8（里程碑 3）: 接 TUI——流式 token 内联 + 工具活动 + Todo + Esc 取消

> **`CodeTuiView` 在 Task 3 已是最终形态**（`SubmitHandler` 接缝、单飞 guard、Esc 取消/`dispose()`、不落 transcript 都已就位）。本 Task**不改 View 的事件处理**，只做两件事：①把回显桩换成真 `CodingAgent`；②让 `ConversationState` 把 token/工具/todo 组织成可读的 transcript 行。

**Files:**
- Modify: `.../codetui/CodeTuiApplication.java`（建模型 + CodingAgent + 共享 AtomicLong，wire 到 view，替换回显桩）
- Modify: `.../codetui/ui/ConversationState.java`（`AgentListener` 各回调格式化为 transcript 行：助手流式行随 token 增长、工具活动行 `🛠 name … ✓/✗`、todo 区）

- [ ] **Step 1: main 组装真链路**（模型 bootstrap 照搬 `AgentDemoApplication`；`CodeTuiView` 构造签名与 Task 3 一致，仅第二参从回显桩换成 `agent`）

```java
// 读 DEEPSEEK_API_KEY（缺则提示）→ DeepSeekApi/DeepSeekChatModel（deepseek-chat）
ConversationState state = new ConversationState();      // implements AgentListener
AtomicLong activeTurnId = new AtomicLong();              // 仅交给 CodingAgent 生成 id；AgentTools 不再需要
Path root = Path.of(System.getProperty("user.dir"));
String sessionId = "code-tui-session";                  // v1 单会话，固定 id 即可（conversationId 每请求传给 memory advisor）
ChatClient client = AgentTools.build(model, root, state, sessionId);           // 无 activeTurnId 参数（Todo turnId 走 ThreadLocal）
CodingAgent agent = new CodingAgent(client, state, sessionId, activeTurnId);  // implements SubmitHandler
CodeTuiView view = new CodeTuiView(state, agent);       // 与 Task 3 同签名：Enter→agent.submit（返回 Disposable）
view.run();
```

- [ ] **Step 2: `ConversationState` 回调格式化**（View 不变；用户行由 `onUserMessage` 落——与骨架桩行为等价，**不会重复显示**）：`onUserMessage`→「你> …」；`onAssistantToken`→追加/续写当前助手行；`onToolStarted/onToolFinished`→工具活动行 `🛠 name … ✓/✗`；`onTodoUpdated`→刷新 todo 区。全部先过 turnId 过滤（Task 4 已实现）。

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
mvn -q -pl springai-code-tui test    # 单测全绿；CodingAgentSpikeTest 因 @EnabledIfEnvironmentVariable 在无 DEEPSEEK_API_KEY 时自动跳过（不是失败）
```

预期：`BUILD SUCCESS`；`ConversationStateTest`/`AgentListenerCancelTest`/`ToolEventCallbackTest`/`AgentToolsSecurityTest`/`CodeTuiApplicationGateTest` 全绿；`CodingAgentSpikeTest` 有 key 则跑、无 key 则 skipped。

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
- **里程碑 1 有闸门**（Task 3 Step 6）：`[被动重绘自检]` 不按键也每秒自动刷出——这是流式 UI 的运行时地基，`javap` 核不了；不过则停在里程碑1 换 `runLater`/`runOnRenderThread`，**不带未验假设进 Task 4~8**。
- **里程碑 2 spike 是闸门**（Task 7）：流式+工具+记忆+取消未实测通过，**不进** Task 8。
- **Todo turnId 不串轮靠 ThreadLocal**（Task 5，二次评审修订）：Todo 事件的 turnId 从 `toolContext` 经 `ToolEventCallback` 的 `ThreadLocal` 取，与 token/工具事件同源；单飞只为 UX，不再是 Todo 正确性前提。⚠️ 唯一残余假设：`TodoWriteTool.todoEventHandler` 在工具 `call` 内**同步**触发——Task 6/7 spike 确认，若异步则回退 per-turn handler。
- **不臆测 API**：TamboUI 循环/按键/Frame/Rect/Paragraph 签名本会话已 `javap` 钉死（见 Task 3 抬头）。**本次评审已额外核实为绿、可不再当风险**：`Paragraph implements dev.tamboui.widget.Widget` ✓、`ToolContext.getContext():Map<String,Object>` ✓。剩余仍需 `javap` 核实——Task 6（5 工具 + `AgentEnvironment` 签名、`ToolCallbacks.from` 位置）、Task 7（`ChatClientResponse` 文本抽取）。
- **安全是方案 B（诚实降级）**，不是真安全：Shell/Grep/Glob 不受限须在横幅/README/记录性测试三处钉死。
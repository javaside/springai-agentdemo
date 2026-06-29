# Terminal 交互场景演示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用「可上手交互的场景」重构 `springai-jline-demo` 的 Terminal 演示，覆盖 JLine `Terminal` 组件的全部关键能力。

**Architecture:** 一个全屏菜单启动器（`TerminalDemoLauncher`）持有唯一的 `Terminal`，按 `Demo` 接口调度 5 个交互场景。纯逻辑（菜单绕回、方向键解析、居中算法）抽成可单测的小类走 TDD；渲染/IO 场景以编译 + dumb 冒烟 + 手动验证为准。按 JLine 组件分包（`terminal` 包），为后续组件演示预留对称结构。

**Tech Stack:** Java 21、JLine 3.30.13、JUnit 5（纯逻辑单测）、Maven。

参考 spec：`docs/superpowers/specs/2026-06-29-terminal-demos-design.md`

---

## 文件结构

```
springai-jline-demo/
├── pom.xml                                  ← 改 mainClass、加 JUnit5 + surefire
└── src/
    ├── main/java/com/example/springai/jline/
    │   ├── Demo.java                         ← 新增：共享场景接口（根包）
    │   └── terminal/
    │       ├── MenuModel.java                ← 新增：纯逻辑（菜单选中索引绕回）
    │       ├── KeyDecoder.java               ← 新增：纯逻辑（方向键/转义解析）
    │       ├── DashboardLayout.java          ← 新增：纯逻辑（居中算法）
    │       ├── Terminals.java                ← 新增：IO 辅助（dumb 判断、状态复原、capability 守卫）
    │       ├── FullScreenMenuDemo.java       ← 新增：场景 3（菜单选择器，launcher 复用其渲染）
    │       ├── TerminalDemoLauncher.java     ← 新增：main 入口
    │       ├── TerminalPlaygroundDemo.java   ← 新增：场景 0（基础能力，交互式）
    │       ├── InterruptibleStreamDemo.java  ← 新增：场景 1（可中断流式输出）
    │       ├── AdaptiveDashboardDemo.java    ← 新增：场景 2（自适应仪表盘）
    │       └── MouseInteractionDemo.java     ← 新增：场景 4（鼠标点选）
    └── test/java/com/example/springai/jline/terminal/
        ├── MenuModelTest.java
        ├── KeyDecoderTest.java
        └── DashboardLayoutTest.java
```

删除：`src/main/java/com/example/springai/jline/TerminalDemo.java`

---

## Task 1: 模块构建配置（JUnit5 + surefire + mainClass）并删除旧 Demo

**Files:**
- Modify: `springai-jline-demo/pom.xml`
- Delete: `springai-jline-demo/src/main/java/com/example/springai/jline/TerminalDemo.java`

- [ ] **Step 1: 删除旧 TerminalDemo.java**

```bash
git rm springai-jline-demo/src/main/java/com/example/springai/jline/TerminalDemo.java
```

- [ ] **Step 2: 在 `<dependencies>` 中追加 JUnit5 测试依赖**

在 `springai-jline-demo/pom.xml` 的 `</dependencies>` 之前插入（版本由父 pom 的 spring-boot-dependencies BOM 统一管理，无需写 version）：

```xml
        <!-- 纯逻辑单元测试 -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: 在 `<plugins>` 中追加 surefire 插件（运行 JUnit5）**

在 `springai-jline-demo/pom.xml` 的 `maven-jar-plugin` 配置块之前插入：

```xml
            <!-- 运行 JUnit5 测试 -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
            </plugin>
```

- [ ] **Step 4: 修改 jar 的 mainClass 指向新启动器**

将 `springai-jline-demo/pom.xml` 中：

```xml
                            <mainClass>com.example.springai.jline.TerminalDemo</mainClass>
```

改为：

```xml
                            <mainClass>com.example.springai.jline.terminal.TerminalDemoLauncher</mainClass>
```

- [ ] **Step 5: 验证模块仍能解析（此刻无源文件，应成功）**

Run: `mvn -q -pl springai-jline-demo clean compile`
Expected: BUILD SUCCESS（模块当前无源码，编译通过）

- [ ] **Step 6: Commit**

```bash
git add springai-jline-demo/pom.xml
git commit -m "build(jline): 切换 mainClass 到 launcher，加 JUnit5/surefire，移除旧 TerminalDemo"
```

---

## Task 2: `Demo` 共享接口

**Files:**
- Create: `springai-jline-demo/src/main/java/com/example/springai/jline/Demo.java`

- [ ] **Step 1: 写接口**

```java
package com.example.springai.jline;

import org.jline.terminal.Terminal;

import java.io.IOException;

/**
 * 所有 JLine 组件演示场景的共享契约。
 * 放在根包，便于后续 linereader / completer 等组件包复用，避免反向依赖 terminal 包。
 */
public interface Demo {

    /** 菜单中显示的名称。 */
    String name();

    /** 一行说明，菜单高亮时展示。 */
    String description();

    /**
     * 运行场景。使用 launcher 传入的唯一 Terminal，禁止自行创建 Terminal。
     * 实现必须在 finally 中复原所有终端状态（原始模式 / 备用屏 / 光标 / 信号 handler）。
     */
    void run(Terminal terminal) throws IOException;
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q -pl springai-jline-demo compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add springai-jline-demo/src/main/java/com/example/springai/jline/Demo.java
git commit -m "feat(jline): 新增 Demo 共享场景接口"
```

---

## Task 3: `MenuModel` 纯逻辑（TDD）

**Files:**
- Create: `springai-jline-demo/src/main/java/com/example/springai/jline/terminal/MenuModel.java`
- Test: `springai-jline-demo/src/test/java/com/example/springai/jline/terminal/MenuModelTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.example.springai.jline.terminal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuModelTest {

    @Test
    void downAdvancesSelection() {
        MenuModel m = new MenuModel(List.of("a", "b", "c"));
        assertEquals(0, m.selectedIndex());
        m.down();
        assertEquals(1, m.selectedIndex());
    }

    @Test
    void downWrapsToTop() {
        MenuModel m = new MenuModel(List.of("a", "b", "c"));
        m.down();
        m.down();
        m.down(); // 从最后一项再下 -> 回到 0
        assertEquals(0, m.selectedIndex());
    }

    @Test
    void upWrapsToBottom() {
        MenuModel m = new MenuModel(List.of("a", "b", "c"));
        m.up(); // 从 0 向上 -> 回到最后一项
        assertEquals(2, m.selectedIndex());
    }

    @Test
    void exposesItemsAndSelectedLabel() {
        MenuModel m = new MenuModel(List.of("a", "b"));
        assertEquals(List.of("a", "b"), m.items());
        m.down();
        assertEquals("b", m.selectedLabel());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl springai-jline-demo test -Dtest=MenuModelTest`
Expected: 编译失败（`MenuModel` 不存在）

- [ ] **Step 3: 写最小实现**

```java
package com.example.springai.jline.terminal;

import java.util.List;

/**
 * 纯逻辑：菜单选中索引管理，上下移动循环绕回。无任何 IO，可单测。
 */
public final class MenuModel {

    private final List<String> items;
    private int selected;

    public MenuModel(List<String> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items 不能为空");
        }
        this.items = List.copyOf(items);
        this.selected = 0;
    }

    public List<String> items() {
        return items;
    }

    public int selectedIndex() {
        return selected;
    }

    public String selectedLabel() {
        return items.get(selected);
    }

    public int size() {
        return items.size();
    }

    public void down() {
        selected = (selected + 1) % items.size();
    }

    public void up() {
        selected = (selected - 1 + items.size()) % items.size();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl springai-jline-demo test -Dtest=MenuModelTest`
Expected: Tests run: 4, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add springai-jline-demo/src/main/java/com/example/springai/jline/terminal/MenuModel.java \
        springai-jline-demo/src/test/java/com/example/springai/jline/terminal/MenuModelTest.java
git commit -m "feat(jline): MenuModel 菜单索引绕回逻辑 + 单测"
```

---

## Task 4: `KeyDecoder` 纯逻辑（TDD）

**Files:**
- Create: `springai-jline-demo/src/main/java/com/example/springai/jline/terminal/KeyDecoder.java`
- Test: `springai-jline-demo/src/test/java/com/example/springai/jline/terminal/KeyDecoderTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.example.springai.jline.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyDecoderTest {

    @Test
    void csiFinalAIsUp() {
        assertEquals(KeyDecoder.Arrow.UP, KeyDecoder.arrowFromCsiFinal('A'));
    }

    @Test
    void csiFinalBIsDown() {
        assertEquals(KeyDecoder.Arrow.DOWN, KeyDecoder.arrowFromCsiFinal('B'));
    }

    @Test
    void csiFinalCIsRight() {
        assertEquals(KeyDecoder.Arrow.RIGHT, KeyDecoder.arrowFromCsiFinal('C'));
    }

    @Test
    void csiFinalDIsLeft() {
        assertEquals(KeyDecoder.Arrow.LEFT, KeyDecoder.arrowFromCsiFinal('D'));
    }

    @Test
    void unknownFinalIsNone() {
        assertEquals(KeyDecoder.Arrow.NONE, KeyDecoder.arrowFromCsiFinal('Z'));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl springai-jline-demo test -Dtest=KeyDecoderTest`
Expected: 编译失败（`KeyDecoder` 不存在）

- [ ] **Step 3: 写最小实现**

```java
package com.example.springai.jline.terminal;

/**
 * 纯逻辑：把方向键转义序列的「终结字节」映射为方向枚举。
 * 终端方向键序列形如 ESC '[' 'A'(上)/'B'(下)/'C'(右)/'D'(左)。
 * 本类只负责终结字节 -> 方向的映射，便于单测；读取转义序列的 IO 在场景里完成。
 */
public final class KeyDecoder {

    public enum Arrow { UP, DOWN, LEFT, RIGHT, NONE }

    private KeyDecoder() {
    }

    public static Arrow arrowFromCsiFinal(char finalByte) {
        return switch (finalByte) {
            case 'A' -> Arrow.UP;
            case 'B' -> Arrow.DOWN;
            case 'C' -> Arrow.RIGHT;
            case 'D' -> Arrow.LEFT;
            default -> Arrow.NONE;
        };
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl springai-jline-demo test -Dtest=KeyDecoderTest`
Expected: Tests run: 5, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add springai-jline-demo/src/main/java/com/example/springai/jline/terminal/KeyDecoder.java \
        springai-jline-demo/src/test/java/com/example/springai/jline/terminal/KeyDecoderTest.java
git commit -m "feat(jline): KeyDecoder 方向键转义解析 + 单测"
```

---

## Task 5: `DashboardLayout` 纯逻辑（TDD）

**Files:**
- Create: `springai-jline-demo/src/main/java/com/example/springai/jline/terminal/DashboardLayout.java`
- Test: `springai-jline-demo/src/test/java/com/example/springai/jline/terminal/DashboardLayoutTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.example.springai.jline.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardLayoutTest {

    @Test
    void centersContentWithinLargerSpace() {
        assertEquals(30, DashboardLayout.centerStart(80, 20));
    }

    @Test
    void clampsToZeroWhenContentTooLarge() {
        assertEquals(0, DashboardLayout.centerStart(10, 20));
    }

    @Test
    void barWidthScalesWithProgress() {
        // 进度 50%、可用 10 格 -> 填充 5 格
        assertEquals(5, DashboardLayout.filledCells(10, 0.5));
    }

    @Test
    void barWidthClampsToRange() {
        assertEquals(0, DashboardLayout.filledCells(10, -1.0));
        assertEquals(10, DashboardLayout.filledCells(10, 2.0));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl springai-jline-demo test -Dtest=DashboardLayoutTest`
Expected: 编译失败（`DashboardLayout` 不存在）

- [ ] **Step 3: 写最小实现**

```java
package com.example.springai.jline.terminal;

/**
 * 纯逻辑：仪表盘居中与进度条计算。无 IO，可单测。
 */
public final class DashboardLayout {

    private DashboardLayout() {
    }

    /** 在 total 宽（或高）内居中放置 content 宽（或高）的起始坐标，最小 0。 */
    public static int centerStart(int total, int content) {
        return Math.max(0, (total - content) / 2);
    }

    /** 进度条填充格数：progress 取 [0,1]，越界自动夹取。 */
    public static int filledCells(int width, double progress) {
        double p = Math.max(0.0, Math.min(1.0, progress));
        return (int) Math.round(width * p);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl springai-jline-demo test -Dtest=DashboardLayoutTest`
Expected: Tests run: 4, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add springai-jline-demo/src/main/java/com/example/springai/jline/terminal/DashboardLayout.java \
        springai-jline-demo/src/test/java/com/example/springai/jline/terminal/DashboardLayoutTest.java
git commit -m "feat(jline): DashboardLayout 居中/进度条计算 + 单测"
```

---

## Task 6: `Terminals` IO 辅助类

**Files:**
- Create: `springai-jline-demo/src/main/java/com/example/springai/jline/terminal/Terminals.java`

- [ ] **Step 1: 写实现**

```java
package com.example.springai.jline.terminal;

import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;

/**
 * Terminal 相关的 IO 辅助：dumb 判断、capability 守卫、状态复原。
 * 所有场景退出时都应调用 restore()，保证不把用户终端遗留在异常状态。
 */
public final class Terminals {

    private Terminals() {
    }

    /** 是否为 dumb 终端（IDE 控制台 / 管道 / CI），交互能力受限。 */
    public static boolean isDumb(Terminal terminal) {
        String type = terminal.getType();
        return type == null || "dumb".equals(type);
    }

    /** 该 capability 是否可用（字符串型）。 */
    public static boolean hasCapability(Terminal terminal, Capability cap) {
        return terminal.getStringCapability(cap) != null;
    }

    /** 把终端复原到干净状态：退备用屏、显示光标、移到左上、清屏。 */
    public static void restore(Terminal terminal) {
        if (isDumb(terminal)) {
            return;
        }
        terminal.puts(Capability.exit_ca_mode);
        terminal.puts(Capability.cursor_visible);
        terminal.puts(Capability.cursor_home);
        terminal.puts(Capability.clear_screen);
        terminal.flush();
    }

    /** 在真实终端上清屏并把光标移到左上。 */
    public static void clear(Terminal terminal) {
        terminal.puts(Capability.clear_screen);
        terminal.puts(Capability.cursor_home);
        terminal.flush();
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q -pl springai-jline-demo compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add springai-jline-demo/src/main/java/com/example/springai/jline/terminal/Terminals.java
git commit -m "feat(jline): Terminals 辅助（dumb 判断/capability 守卫/状态复原）"
```

---

## Task 7: `FullScreenMenuDemo` 场景 3（菜单选择器）

说明：本类提供可复用的全屏菜单渲染与导航，launcher 直接调用它的静态 `select(...)`。验证以编译为主，交互手动验证。

**Files:**
- Create: `springai-jline-demo/src/main/java/com/example/springai/jline/terminal/FullScreenMenuDemo.java`

- [ ] **Step 1: 写实现**

```java
package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Cursor;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.List;

/**
 * 场景 3：全屏菜单选择器。
 * 演示：备用屏（enter/exit_ca_mode）、光标显隐、原始模式 + 方向键解析、getCursorPosition()。
 * 既是独立场景，也被 TerminalDemoLauncher 复用（select 方法）。
 */
public final class FullScreenMenuDemo implements Demo {

    @Override
    public String name() {
        return "全屏菜单选择器";
    }

    @Override
    public String description() {
        return "备用屏 + 方向键导航 + 光标显隐 + 光标位置查询";
    }

    @Override
    public void run(Terminal terminal) throws IOException {
        if (Terminals.isDumb(terminal)) {
            terminal.writer().println("[dumb 终端] 该场景需真实终端，跳过。");
            terminal.writer().flush();
            return;
        }
        List<String> items = List.of("苹果", "香蕉", "樱桃", "返回");
        int chosen = select(terminal, "请选择一项（↑/↓ 移动，Enter 确认，q 退出）", items);
        Terminals.clear(terminal);
        if (chosen < 0) {
            terminal.writer().println("已取消选择。");
        } else {
            terminal.writer().println("你选择了：" + items.get(chosen));
            // 演示 getCursorPosition()：打印当前光标坐标
            Cursor cursor = terminal.getCursorPosition(null);
            if (cursor != null) {
                terminal.writer().printf("当前光标位置: x=%d, y=%d%n", cursor.getX(), cursor.getY());
            }
        }
        terminal.writer().println("（按回车返回菜单）");
        terminal.writer().flush();
        terminal.reader().read();
    }

    /**
     * 在备用屏上渲染一个可上下选择的列表，返回选中下标；q/Esc 返回 -1。
     * 该方法自管理原始模式与备用屏，退出时复原。
     */
    public static int select(Terminal terminal, String title, List<String> items) throws IOException {
        MenuModel model = new MenuModel(items);
        Attributes prev = terminal.enterRawMode();
        terminal.puts(Capability.enter_ca_mode);
        terminal.puts(Capability.cursor_invisible);
        terminal.flush();
        try {
            NonBlockingReader reader = terminal.reader();
            while (true) {
                render(terminal, title, model);
                int c = reader.read();
                if (c == 'q' || c == 27 && reader.peek(1) < 0) { // q 或单独的 Esc
                    return -1;
                }
                if (c == '\r' || c == '\n') {
                    return model.selectedIndex();
                }
                if (c == 27) { // ESC，尝试读取 '[' 与终结字节
                    int bracket = reader.read();
                    if (bracket == '[') {
                        int finalByte = reader.read();
                        switch (KeyDecoder.arrowFromCsiFinal((char) finalByte)) {
                            case UP -> model.up();
                            case DOWN -> model.down();
                            default -> { /* 忽略 */ }
                        }
                    }
                }
            }
        } finally {
            terminal.puts(Capability.cursor_visible);
            terminal.puts(Capability.exit_ca_mode);
            terminal.setAttributes(prev);
            terminal.flush();
        }
    }

    private static void render(Terminal terminal, String title, MenuModel model) {
        terminal.puts(Capability.clear_screen);
        terminal.puts(Capability.cursor_home);
        terminal.writer().println(title);
        terminal.writer().println();
        List<String> items = model.items();
        for (int i = 0; i < items.size(); i++) {
            AttributedStringBuilder b = new AttributedStringBuilder();
            if (i == model.selectedIndex()) {
                b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.BLACK).background(AttributedStyle.CYAN))
                        .append("  ▶ ").append(items.get(i)).append("  ");
            } else {
                b.style(AttributedStyle.DEFAULT).append("    ").append(items.get(i));
            }
            terminal.writer().println(b.toAnsi(terminal));
        }
        terminal.flush();
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q -pl springai-jline-demo compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add springai-jline-demo/src/main/java/com/example/springai/jline/terminal/FullScreenMenuDemo.java
git commit -m "feat(jline): 场景3 FullScreenMenuDemo 全屏菜单选择器"
```

---

## Task 8: `TerminalDemoLauncher` 主入口

**Files:**
- Create: `springai-jline-demo/src/main/java/com/example/springai/jline/terminal/TerminalDemoLauncher.java`

注意：场景 0/1/2/4 在后续 Task 才创建。本 Task 先只注册 `FullScreenMenuDemo`，编译可过；Task 9-12 各自把对应场景加入注册列表。

- [ ] **Step 1: 写实现**

```java
package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Terminal 组件演示入口。持有唯一的 Terminal，按 Demo 接口调度各交互场景。
 * 真实终端：全屏方向键菜单（复用 FullScreenMenuDemo）。
 * dumb 终端：退化为编号列表 + 读行选择。
 */
public final class TerminalDemoLauncher {

    public static void main(String[] args) {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            List<Demo> demos = registerDemos();
            if (Terminals.isDumb(terminal)) {
                runDumb(terminal, demos);
            } else {
                runInteractive(terminal, demos);
            }
        } catch (IOException e) {
            System.err.println("终端初始化失败: " + e.getMessage());
        }
    }

    private static List<Demo> registerDemos() {
        List<Demo> demos = new ArrayList<>();
        demos.add(new TerminalPlaygroundDemo());
        demos.add(new InterruptibleStreamDemo());
        demos.add(new AdaptiveDashboardDemo());
        demos.add(new FullScreenMenuDemo());
        demos.add(new MouseInteractionDemo());
        return demos;
    }

    private static void runInteractive(Terminal terminal, List<Demo> demos) throws IOException {
        List<String> labels = new ArrayList<>();
        for (Demo d : demos) {
            labels.add(d.name() + " — " + d.description());
        }
        labels.add("退出");
        while (true) {
            int choice = FullScreenMenuDemo.select(terminal,
                    "JLine Terminal 演示（↑/↓ 选择，Enter 运行，q 退出）", labels);
            if (choice < 0 || choice == demos.size()) {
                Terminals.restore(terminal);
                terminal.writer().println("再见！");
                terminal.writer().flush();
                return;
            }
            Terminals.restore(terminal);
            try {
                demos.get(choice).run(terminal);
            } catch (RuntimeException | IOException e) {
                terminal.writer().println("场景运行异常: " + e.getMessage());
                terminal.writer().flush();
            } finally {
                Terminals.restore(terminal);
            }
        }
    }

    private static void runDumb(Terminal terminal, List<Demo> demos) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            terminal.writer().println("\n=== JLine Terminal 演示（dumb 模式）===");
            for (int i = 0; i < demos.size(); i++) {
                terminal.writer().printf("  %d) %s — %s%n", i + 1, demos.get(i).name(), demos.get(i).description());
            }
            terminal.writer().println("  q) 退出");
            terminal.writer().print("请输入编号: ");
            terminal.writer().flush();
            String line = in.readLine();
            if (line == null || line.trim().equalsIgnoreCase("q")) {
                terminal.writer().println("再见！");
                terminal.writer().flush();
                return;
            }
            try {
                int idx = Integer.parseInt(line.trim()) - 1;
                if (idx >= 0 && idx < demos.size()) {
                    demos.get(idx).run(terminal);
                } else {
                    terminal.writer().println("编号超出范围。");
                }
            } catch (NumberFormatException e) {
                terminal.writer().println("无效输入。");
            }
            terminal.writer().flush();
        }
    }
}
```

- [ ] **Step 2: 编译验证（此刻场景 0/1/2/4 未创建，预期编译失败）**

Run: `mvn -q -pl springai-jline-demo compile`
Expected: 编译失败，提示 `TerminalPlaygroundDemo` / `InterruptibleStreamDemo` / `AdaptiveDashboardDemo` / `MouseInteractionDemo` 找不到符号。

> 说明：这是预期的中间状态。Task 9-12 创建这些类后编译即通过；本 Task 暂不单独 commit，与 Task 9 一起验证。若希望本 Task 可独立编译，可临时把 `registerDemos()` 里未创建的场景注释掉，待对应 Task 完成后解开——但推荐按顺序连做 Task 8→12 后统一编译，避免来回改动。

- [ ] **Step 3: 暂存（不提交，等 Task 12 全绿后统一提交 launcher 与场景）**

```bash
git add springai-jline-demo/src/main/java/com/example/springai/jline/terminal/TerminalDemoLauncher.java
```

---

## Task 9: `TerminalPlaygroundDemo` 场景 0（基础能力，交互式）

**Files:**
- Create: `springai-jline-demo/src/main/java/com/example/springai/jline/terminal/TerminalPlaygroundDemo.java`

- [ ] **Step 1: 写实现**

```java
package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

/**
 * 场景 0：基础能力（交互式样式调色台）。
 * 演示：终端信息 type/name/width/height/colors、encoding()、AttributedString 全样式、非阻塞按键。
 * 按键：f 切前景色，b 切背景色，o 粗体，u 下划线，i 斜体，q 返回。
 */
public final class TerminalPlaygroundDemo implements Demo {

    private static final int[] COLORS = {
            AttributedStyle.BLACK, AttributedStyle.RED, AttributedStyle.GREEN, AttributedStyle.YELLOW,
            AttributedStyle.BLUE, AttributedStyle.MAGENTA, AttributedStyle.CYAN, AttributedStyle.WHITE
    };
    private static final String[] COLOR_NAMES = {"黑", "红", "绿", "黄", "蓝", "品红", "青", "白"};

    @Override
    public String name() {
        return "基础能力调色台";
    }

    @Override
    public String description() {
        return "终端信息 + 实时样式切换（f/b/o/u/i）";
    }

    @Override
    public void run(Terminal terminal) throws java.io.IOException {
        if (Terminals.isDumb(terminal)) {
            printInfo(terminal);
            terminal.writer().println("[dumb 终端] 交互式调色跳过，仅打印终端信息。");
            terminal.writer().flush();
            return;
        }
        Attributes prev = terminal.enterRawMode();
        int fg = 1, bg = -1;
        boolean bold = false, underline = false, italic = false;
        try {
            NonBlockingReader reader = terminal.reader();
            while (true) {
                renderFrame(terminal, fg, bg, bold, underline, italic);
                int c = reader.read();
                switch (c) {
                    case 'q', 'Q' -> { return; }
                    case 'f' -> fg = (fg + 1) % COLORS.length;
                    // bg 取值 [-1, 7]：-1 表示无背景，按 (COLORS.length+1) 循环
                    case 'b' -> bg = ((bg + 2) % (COLORS.length + 1)) - 1;
                    case 'o' -> bold = !bold;
                    case 'u' -> underline = !underline;
                    case 'i' -> italic = !italic;
                    default -> { /* 忽略 */ }
                }
            }
        } finally {
            terminal.setAttributes(prev);
            Terminals.clear(terminal);
        }
    }

    private void renderFrame(Terminal terminal, int fg, int bg, boolean bold, boolean underline, boolean italic) {
        Terminals.clear(terminal);
        printInfo(terminal);
        terminal.writer().println();

        AttributedStyle style = AttributedStyle.DEFAULT.foreground(COLORS[fg]);
        if (bg >= 0) {
            style = style.background(COLORS[bg]);
        }
        if (bold) {
            style = style.bold();
        }
        if (underline) {
            style = style.underline();
        }
        if (italic) {
            style = style.italic();
        }
        AttributedStringBuilder sample = new AttributedStringBuilder();
        sample.style(style).append("  示例文本 Sample 12345 你好，世界  ");
        terminal.writer().println(sample.toAnsi(terminal));
        terminal.writer().println();

        terminal.writer().printf("前景色[f]=%s  背景色[b]=%s  粗体[o]=%s  下划线[u]=%s  斜体[i]=%s%n",
                COLOR_NAMES[fg], bg < 0 ? "无" : COLOR_NAMES[bg], onOff(bold), onOff(underline), onOff(italic));
        terminal.writer().println("按 f/b/o/u/i 切换样式，q 返回菜单");
        terminal.flush();
    }

    private static String onOff(boolean v) {
        return v ? "开" : "关";
    }

    private void printInfo(Terminal terminal) {
        terminal.writer().println("=== 终端基本信息 ===");
        terminal.writer().println("类型: " + terminal.getType());
        terminal.writer().println("名称: " + terminal.getName());
        terminal.writer().println("尺寸: " + terminal.getWidth() + " x " + terminal.getHeight());
        Integer maxColors = terminal.getNumericCapability(Capability.max_colors);
        terminal.writer().println("颜色数: " + (maxColors != null ? maxColors : "不支持"));
        terminal.writer().println("编码: " + terminal.encoding());
        terminal.flush();
    }
}
```

- [ ] **Step 2: 编译验证（仍缺场景 1/2/4，预期编译失败）**

Run: `mvn -q -pl springai-jline-demo compile`
Expected: 编译失败，缺 `InterruptibleStreamDemo` / `AdaptiveDashboardDemo` / `MouseInteractionDemo`。继续 Task 10-12。

- [ ] **Step 3: 暂存**

```bash
git add springai-jline-demo/src/main/java/com/example/springai/jline/terminal/TerminalPlaygroundDemo.java
```

---

## Task 10: `InterruptibleStreamDemo` 场景 1（可中断流式输出）

**Files:**
- Create: `springai-jline-demo/src/main/java/com/example/springai/jline/terminal/InterruptibleStreamDemo.java`

- [ ] **Step 1: 写实现**

```java
package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 场景 1：可中断的流式输出。
 * 演示：Signal.INT（Ctrl+C 优雅停止）、非阻塞 read(timeout)、getAttributes/setAttributes、bell。
 */
public final class InterruptibleStreamDemo implements Demo {

    private static final String TEXT =
            "JLine 让 Java 程序拥有现代终端交互能力：彩色输出、行编辑、自动补全、历史记录。"
            + "这段文字正在被逐字“流式”打印，模拟大模型的输出。你可以随时按 Ctrl+C 优雅地停止，"
            + "而不会杀掉整个进程——这正是构建可中断 AI 智能体界面的关键能力。";

    @Override
    public String name() {
        return "可中断流式输出";
    }

    @Override
    public String description() {
        return "Ctrl+C 优雅停止 + 非阻塞读取 + 回显控制 + 响铃";
    }

    @Override
    public void run(Terminal terminal) throws java.io.IOException {
        if (Terminals.isDumb(terminal)) {
            terminal.writer().println("[dumb 终端] 信号/非阻塞读取需真实终端，跳过。");
            terminal.writer().flush();
            return;
        }
        terminal.writer().println("=== 可中断流式输出 ===");
        terminal.writer().println("开始流式输出，按 Ctrl+C 停止，或按任意键提前结束。\n");
        terminal.writer().flush();

        // 关回显，演示 Attributes 控制（规范模式下默认回显，这里关闭）
        Attributes prev = terminal.getAttributes();
        Attributes raw = new Attributes(prev);
        raw.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        raw.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        terminal.setAttributes(raw);

        AtomicBoolean stopped = new AtomicBoolean(false);
        Terminal.SignalHandler oldInt = terminal.handle(Terminal.Signal.INT, sig -> stopped.set(true));
        try {
            NonBlockingReader reader = terminal.reader();
            for (int i = 0; i < TEXT.length(); i++) {
                if (stopped.get()) {
                    terminal.puts(Capability.bell); // 响铃提示被打断
                    terminal.writer().println("\n\n[已被 Ctrl+C 中断]");
                    break;
                }
                int key = reader.read(20L); // 非阻塞：最多等 20ms
                if (key != NonBlockingReader.READ_EXPIRED && key != -1) {
                    terminal.writer().println("\n\n[已被按键提前结束]");
                    break;
                }
                terminal.writer().print(TEXT.charAt(i));
                terminal.writer().flush();
                sleep(60);
            }
            if (!stopped.get()) {
                terminal.writer().println("\n\n[输出完成]");
            }
            terminal.writer().println("（按回车返回菜单）");
            terminal.writer().flush();
            reader.read();
        } finally {
            terminal.handle(Terminal.Signal.INT, oldInt);
            terminal.setAttributes(prev);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 2: 编译验证（仍缺场景 2/4）**

Run: `mvn -q -pl springai-jline-demo compile`
Expected: 编译失败，缺 `AdaptiveDashboardDemo` / `MouseInteractionDemo`。

- [ ] **Step 3: 暂存**

```bash
git add springai-jline-demo/src/main/java/com/example/springai/jline/terminal/InterruptibleStreamDemo.java
```

---

## Task 11: `AdaptiveDashboardDemo` 场景 2（自适应窗口仪表盘）

**Files:**
- Create: `springai-jline-demo/src/main/java/com/example/springai/jline/terminal/AdaptiveDashboardDemo.java`

- [ ] **Step 1: 写实现**

```java
package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.Status;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 场景 2：自适应窗口仪表盘。
 * 演示：Signal.WINCH（resize）+ getSize()、clear_screen、cursor_address 绝对定位、Status 状态栏。
 * 拖动改变窗口大小时自动重绘居中；q 退出。
 */
public final class AdaptiveDashboardDemo implements Demo {

    @Override
    public String name() {
        return "自适应窗口仪表盘";
    }

    @Override
    public String description() {
        return "WINCH 监听 + 绝对定位居中 + 状态栏";
    }

    @Override
    public void run(Terminal terminal) throws java.io.IOException {
        if (Terminals.isDumb(terminal)) {
            terminal.writer().println("[dumb 终端] resize/绝对定位需真实终端，跳过。");
            terminal.writer().flush();
            return;
        }
        Attributes prev = terminal.enterRawMode();
        AtomicBoolean dirty = new AtomicBoolean(true);
        Terminal.SignalHandler oldWinch = terminal.handle(Terminal.Signal.WINCH, sig -> dirty.set(true));
        Status status = Status.getStatus(terminal, false);
        try {
            NonBlockingReader reader = terminal.reader();
            int tokens = 0;
            while (true) {
                if (dirty.getAndSet(false)) {
                    draw(terminal, status, tokens);
                }
                int c = reader.read(200L);
                if (c == 'q' || c == 'Q') {
                    return;
                }
                tokens += 7; // 模拟 token 增长
                dirty.set(true);
            }
        } finally {
            if (status != null) {
                status.update(List.of()); // 清空状态栏
            }
            terminal.handle(Terminal.Signal.WINCH, oldWinch);
            terminal.setAttributes(prev);
            Terminals.clear(terminal);
        }
    }

    private void draw(Terminal terminal, Status status, int tokens) {
        Size size = terminal.getSize();
        int cols = size.getColumns();
        int rows = size.getRows();
        terminal.puts(Capability.clear_screen);

        List<String> lines = List.of(
                "┌─────────── AI 智能体仪表盘 ───────────┐",
                "│ 模型:   claude-opus-4-8                │",
                String.format("│ 已用 token: %-6d                    │", tokens),
                "│ 进度:   " + bar(20, (tokens % 200) / 200.0) + " │",
                "└──────────────────────────────────────┘");
        int boxWidth = lines.get(0).length();
        int top = DashboardLayout.centerStart(rows, lines.size());
        int left = DashboardLayout.centerStart(cols, boxWidth);
        for (int i = 0; i < lines.size(); i++) {
            terminal.puts(Capability.cursor_address, top + i, left);
            AttributedStringBuilder b = new AttributedStringBuilder();
            b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)).append(lines.get(i));
            terminal.writer().print(b.toAnsi(terminal));
        }
        terminal.flush();

        if (status != null) {
            AttributedString bar = new AttributedStringBuilder()
                    .style(AttributedStyle.DEFAULT.background(AttributedStyle.BLUE).foreground(AttributedStyle.WHITE))
                    .append(String.format(" 窗口: %d x %d  |  按 q 退出 ", cols, rows))
                    .toAttributedString();
            status.update(List.of(bar));
        }
    }

    private static String bar(int width, double progress) {
        int filled = DashboardLayout.filledCells(width, progress);
        return "█".repeat(filled) + "░".repeat(width - filled);
    }
}
```

- [ ] **Step 2: 编译验证（仍缺场景 4）**

Run: `mvn -q -pl springai-jline-demo compile`
Expected: 编译失败，缺 `MouseInteractionDemo`。

- [ ] **Step 3: 暂存**

```bash
git add springai-jline-demo/src/main/java/com/example/springai/jline/terminal/AdaptiveDashboardDemo.java
```

---

## Task 12: `MouseInteractionDemo` 场景 4（鼠标点选）

**Files:**
- Create: `springai-jline-demo/src/main/java/com/example/springai/jline/terminal/MouseInteractionDemo.java`

- [ ] **Step 1: 写实现**

```java
package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;

import java.nio.charset.StandardCharsets;

import static org.jline.keymap.KeyMap.key;

/**
 * 场景 4：鼠标点选。
 * 演示：trackMouse/readMouseEvent、output() 原始字节流、鼠标按键/滚轮事件。
 * 鼠标点击显示坐标与事件类型；滚轮上下改变计数；q 退出。
 */
public final class MouseInteractionDemo implements Demo {

    @Override
    public String name() {
        return "鼠标点选";
    }

    @Override
    public String description() {
        return "鼠标点击/滚轮事件 + 原始字节流输出";
    }

    @Override
    public void run(Terminal terminal) throws java.io.IOException {
        if (Terminals.isDumb(terminal) || !terminal.hasMouseSupport()) {
            terminal.writer().println("[当前终端不支持鼠标] 跳过该场景。");
            terminal.writer().flush();
            return;
        }
        Attributes prev = terminal.enterRawMode();
        terminal.trackMouse(Terminal.MouseTracking.Normal);
        try {
            // 演示 output()：直接向底层字节流写一行 ANSI（绿色），对比 writer()
            byte[] banner = "[32m=== 鼠标点选演示：点击或滚动，按 q 退出 ===[0m\r\n"
                    .getBytes(StandardCharsets.UTF_8);
            terminal.output().write(banner);
            terminal.output().flush();

            BindingReader bindingReader = new BindingReader(terminal.reader());
            KeyMap<String> keyMap = new KeyMap<>();
            keyMap.bind("quit", "q");
            keyMap.bind("mouse", key(terminal, Capability.key_mouse));

            int wheelCounter = 0;
            while (true) {
                String op = bindingReader.readBinding(keyMap);
                if ("quit".equals(op)) {
                    return;
                }
                if ("mouse".equals(op)) {
                    MouseEvent event = terminal.readMouseEvent();
                    if (event.getType() == MouseEvent.Type.Wheel) {
                        wheelCounter += event.getButton() == MouseEvent.Button.WheelUp ? 1 : -1;
                        terminal.writer().printf("滚轮: %s  计数=%d%n",
                                event.getButton(), wheelCounter);
                    } else {
                        terminal.writer().printf("鼠标 %s 按钮=%s  位置=(%d,%d)%n",
                                event.getType(), event.getButton(), event.getX(), event.getY());
                    }
                    terminal.writer().flush();
                }
            }
        } finally {
            terminal.trackMouse(Terminal.MouseTracking.Off);
            terminal.setAttributes(prev);
            Terminals.clear(terminal);
        }
    }
}
```

- [ ] **Step 2: 全量编译验证（5 个场景齐备，应通过）**

Run: `mvn -q -pl springai-jline-demo compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 跑全部单测**

Run: `mvn -q -pl springai-jline-demo test`
Expected: Tests run: 13, Failures: 0, Errors: 0（MenuModel 4 + KeyDecoder 5 + DashboardLayout 4）

- [ ] **Step 4: dumb 终端冒烟（管道触发 dumb 分支，输入 q 退出，不应抛异常）**

Run: `mvn -q -pl springai-jline-demo package -DskipTests && printf 'q\n' | java -jar springai-jline-demo/target/springai-jline-demo-1.0.0.jar`
Expected: 打印「dumb 模式」编号菜单与「再见！」，进程正常退出（exit 0），无堆栈异常。

- [ ] **Step 5: Commit（统一提交 launcher + 全部场景）**

```bash
git add springai-jline-demo/src/main/java/com/example/springai/jline/terminal/MouseInteractionDemo.java \
        springai-jline-demo/src/main/java/com/example/springai/jline/terminal/TerminalDemoLauncher.java \
        springai-jline-demo/src/main/java/com/example/springai/jline/terminal/TerminalPlaygroundDemo.java \
        springai-jline-demo/src/main/java/com/example/springai/jline/terminal/InterruptibleStreamDemo.java \
        springai-jline-demo/src/main/java/com/example/springai/jline/terminal/AdaptiveDashboardDemo.java
git commit -m "feat(jline): launcher + 5 个 Terminal 交互场景（基础/流式/仪表盘/菜单/鼠标）"
```

---

## Task 13: 更新 README

**Files:**
- Modify: `springai-jline-demo/README.md`

- [ ] **Step 1: 替换「当前示例」与「运行示例」「后续计划」三节**

把 `## 当前示例` 整节（从 `## 当前示例` 到 `## 后续计划` 之前）替换为：

```markdown
## 当前示例：Terminal 交互场景

入口：`com.example.springai.jline.terminal.TerminalDemoLauncher`
真实终端中启动后用 ↑/↓ 选择场景、Enter 运行、q 退出；dumb 终端（IDE/管道）退化为编号选择。

| 场景 | 类 | 演示的 Terminal 能力 |
|------|----|----------------------|
| 基础能力调色台 | `TerminalPlaygroundDemo` | 终端信息、encoding、AttributedString 全样式、非阻塞按键 |
| 可中断流式输出 | `InterruptibleStreamDemo` | Signal.INT（Ctrl+C）、非阻塞 read(timeout)、Attributes 回显控制、bell |
| 自适应窗口仪表盘 | `AdaptiveDashboardDemo` | Signal.WINCH、getSize()、clear_screen、cursor_address 绝对定位、Status 状态栏 |
| 全屏菜单选择器 | `FullScreenMenuDemo` | 备用屏、方向键解析、光标显隐、getCursorPosition() |
| 鼠标点选 | `MouseInteractionDemo` | trackMouse/readMouseEvent、output() 原始流、鼠标按键/滚轮 |

纯逻辑（菜单绕回、方向键解析、居中算法）有 JUnit5 单测；交互效果需在真实终端手动体验。
```

- [ ] **Step 2: 更新「后续计划」节,去掉已完成的 Terminal 项**

把 `## 后续计划` 节内容替换为：

```markdown
## 后续计划

- [x] Terminal 示例：交互场景（基础/流式/仪表盘/菜单/鼠标）
- [ ] LineReader 示例：命令行编辑和多行输入
- [ ] Completer 示例：自动补全实现
- [ ] Highlighter 示例：语法高亮
- [ ] History 示例：历史记录管理
- [ ] 综合示例：构建 AI 智能体交互界面
```

- [ ] **Step 3: Commit**

```bash
git add springai-jline-demo/README.md
git commit -m "docs(jline): README 更新为 Terminal 交互场景说明"
```

---

## Task 14: 手动验证（真实终端）

> 自动化只能保证编译/单测/dumb 冒烟。交互效果必须在真实终端逐项确认。

- [ ] **Step 1: 打包并在真实终端启动**

Run（在真实终端，非 IDE 控制台）: `java -jar springai-jline-demo/target/springai-jline-demo-1.0.0.jar`
Expected: 全屏菜单出现，↑/↓ 高亮移动，Enter 进入场景。

- [ ] **Step 2: 逐场景验证能力覆盖对照表**

对照 spec 的「能力覆盖对照」表逐项确认：
- 调色台：f/b/o/u/i 实时改样式；顶部显示终端信息与 encoding。
- 流式输出：Ctrl+C 能优雅停止（响铃 + 提示），不杀进程；任意键提前结束。
- 仪表盘：拖动改变终端窗口大小，仪表盘自动居中重绘；底部状态栏显示实时尺寸；q 退出。
- 菜单：备用屏生效（退出后恢复原终端内容）；方向键导航；退出打印光标坐标。
- 鼠标：鼠标点击显示坐标与事件类型；滚轮改变计数；不支持鼠标时给降级提示。

- [ ] **Step 3: 终端状态复原检查**

每个场景退出后：光标可见、能正常输入、未停留在备用屏。退出 launcher 后终端干净。

- [ ] **Step 4: 收尾提交（如手动验证中有微调）**

```bash
git add -A && git commit -m "fix(jline): 手动验证后的交互细节微调"
```

---

## 自检结论（写计划时已核对）

- **spec 覆盖**：5 场景 + launcher 覆盖 spec「能力覆盖对照」全部条目；基础信息/彩色输出并入场景 0；save/restore_cursor、clr_eol 的等价能力由清屏 + 绝对定位在场景 2/3 体现。
- **占位符**：无 TBD/TODO；每个代码步骤均给出完整代码。
- **类型一致性**：`Demo` 接口签名（name/description/run）在所有场景一致；`MenuModel`、`KeyDecoder.Arrow`、`DashboardLayout.centerStart/filledCells`、`Terminals.isDumb/restore/clear` 在调用处与定义处一致；`FullScreenMenuDemo.select(Terminal,String,List<String>)` 在 launcher 调用处签名一致。
- **构建顺序**：Task 8-12 存在预期的中间编译失败（launcher 先引用未创建的场景），已显式说明并在 Task 12 统一编译/提交；其余 Task 各自可独立编译。
```

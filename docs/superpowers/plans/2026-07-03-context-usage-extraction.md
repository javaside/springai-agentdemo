# ContextUsage 抽取 Implementation Plan（CodeTuiView 重构 4/4）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把「上下文用量的追踪与报告」簇（`/context` 报告、状态栏后缀、节流缓存刷新、百分比助手 + 缓存字段）抽成零 UI 依赖的纯 Java 类 `ContextUsage`，使其可离线单测；`animTick` 节流节拍、状态行分发器其余部分留在视图。

**Architecture:** `ContextUsage` 有状态（volatile `cached` 快照）但不碰 tamboui，经 `Supplier<ContextStats>`（现算源）入、`Consumer<String>`（scrollback 灰行）出。视图删掉字段 `ctxStats` + 4 个方法，在构造函数体内（onSubmit/state 赋值后）new 出 `ctxUsage`，3 处调用点改为委托。纯搬运、零行为改动。

**Tech Stack:** Java 21、`ContextStats`（纯 Java record）、`java.util.function.{Supplier,Consumer}`、JUnit 5、Maven（`mvn -o -q -pl springai-code-tui -am ...`）。**新类与其测试均不 import 任何 tamboui 类型。**

参考设计：`docs/superpowers/specs/2026-07-03-context-usage-extraction-design.md`

---

### Task 1: 新建 `ContextUsage` 纯 Java 类（搬入 4 个成员）

**Files:**
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/ContextUsage.java`

- [ ] **Step 1: 写出完整源文件**

`report`/`pct`/`refresh`/`suffix` 系从 `CodeTuiView` 原样搬入：`onSubmit.contextStats()` → `source.get()`；`state.pushInfo(...)` → `sink.accept(...)`；`ctxStats` 字段 → `cached`。逐字如下：

```java
package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.ContextStats;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 会话<b>上下文用量</b>的追踪与报告：{@code /context} 多行报告 + 状态栏 {@code " · 上下文 N%"} 后缀。
 * 有状态（{@link #cached} 节流缓存快照）但<b>不认识 tamboui</b>——经 {@code Supplier<ContextStats>} 现算源入、
 * {@code Consumer<String>} 灰色信息行出，故可脱离 {@link dev.tamboui.toolkit.app.InlineApp} 离线单测。
 *
 * <p><b>为什么抽出</b>：报告的多分支 {@code String.format} / 百分比舍入 / 缓存刷新容错，此前埋在
 * {@link CodeTuiView} 里无法单测。抽成纯 Java 类后可喂 {@link ContextStats} 断言每一行文案与分桶。
 *
 * <p><b>现算 vs 缓存</b>：{@link #report()} 读<em>实时</em> {@code source}（报告要最新）；
 * {@link #suffix()} 读<em>缓存</em> {@code cached}（状态栏每帧读，绝不每帧重算——重算要遍历全部消息 + 估算 token）。
 * {@link #refresh()} 由视图 drain 每 ~1s 调一次，把最新快照存进缓存。
 */
final class ContextUsage {

    private final Supplier<ContextStats> source;   // 现算：读一遍当前会话（估算 token）
    private final Consumer<String> sink;           // 输出：灰色信息行下沉 scrollback
    private volatile ContextStats cached = ContextStats.empty();   // 状态栏节流缓存：refresh 写、suffix 读

    ContextUsage(Supplier<ContextStats> source, Consumer<String> sink) {
        this.source = source;
        this.sink = sink;
    }

    /**
     * /context：把当前会话上下文用量（事件数分桶 + 估算 token + 距自动压缩阈值）打进 scrollback（灰色信息行）。
     * 只读快照，任何时刻都可查；尚无对话时各项为 0，明确提示「尚无对话历史」。
     */
    void report() {
        ContextStats s = source.get();
        if (s == null) s = ContextStats.empty();
        sink.accept("📊 上下文用量");
        if (s.events() == 0) {
            sink.accept("  （尚无对话历史）");
            return;
        }
        sink.accept(String.format("  事件数：%,d 条（用户 %,d · 助手 %,d · 工具 %,d%s）",
                s.events(), s.userEvents(), s.assistantEvents(), s.toolEvents(),
                s.otherEvents() > 0 ? " · 其他 " + s.otherEvents() : ""));
        if (s.contextWindow() > 0) {
            sink.accept(String.format("  估算 token：%,d / %,d（占窗口 %s）",
                    s.estimatedTokens(), s.contextWindow(), pct(s.estimatedTokens(), s.contextWindow())));
        } else {
            sink.accept(String.format("  估算 token：%,d", s.estimatedTokens()));
        }
        if (s.tokenThreshold() > 0) {
            sink.accept(String.format("  自动压缩：达 %,d token 触发（当前 %s）· 保留最近 %,d 条",
                    s.tokenThreshold(), pct(s.estimatedTokens(), s.tokenThreshold()), s.autoKeepEvents()));
        }
        if (s.manualKeepEvents() > 0) {
            sink.accept(String.format("  手动 /compact：立即压缩，保留最近 %,d 条（更激进）", s.manualKeepEvents()));
        }
    }

    /**
     * 重算状态栏用的上下文用量快照（视图 drain 里节流调用，绝不每帧）。用量是辅助信息：
     * 估算失败绝不能拖垮主 UI，异常时静默保留旧值。
     */
    void refresh() {
        try {
            ContextStats s = source.get();
            if (s != null) cached = s;
        } catch (RuntimeException ignore) {
            // 尽力而为：保留上一次快照，不影响状态栏其余内容
        }
    }

    /** 状态栏上下文用量后缀（如 {@code " · 上下文 3%"}，占窗口比例）；尚无对话/窗口未知时返回空串。 */
    String suffix() {
        ContextStats s = cached;
        if (s == null || s.events() == 0 || s.contextWindow() <= 0) return "";
        return " · 上下文 " + pct(s.estimatedTokens(), s.contextWindow());
    }

    /** 占比（part/whole）取整成百分号字符串；whole<=0 视为 0%。 */
    private static String pct(long part, long whole) {
        if (whole <= 0) return "0%";
        return Math.round(part * 100.0 / whole) + "%";
    }
}
```

- [ ] **Step 2: 编译（此时 ContextUsage 未被引用，仅验证自身可编译）**

Run: `mvn -o -q -pl springai-code-tui -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/ContextUsage.java
git commit -m "refactor(code-tui): 新增 ContextUsage 纯 Java 类（上下文用量追踪/报告）"
```

---

### Task 2: 视图接线——委托 ContextUsage，删除旧成员

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java`

- [ ] **Step 1: 删字段 `ctxStats`，加字段 `ctxUsage`（声明处不初始化）**

删除这一行（当前 line 86 附近）：
```java
    private volatile ContextStats ctxStats = ContextStats.empty();   // 状态栏上下文用量快照：drain 里节流刷新，render 只读缓存（重算要遍历全部消息 + 估算 token，绝不每帧做）
```
在原地或 `printer` 字段附近加（**只声明，不初始化**——见 Step 2 的构造时序）：
```java
    private final ContextUsage ctxUsage;                             // 上下文用量追踪/报告（/context 报告 + 状态栏后缀）
```

- [ ] **Step 2: 构造函数体内 new（⚠ 必须在 `this.onSubmit`/`this.state` 赋值之后）**

在构造函数里，`this.printer = new ScrollbackPrinter(...);` 那一行之后，追加：
```java
        this.ctxUsage = new ContextUsage(onSubmit::contextStats, state::pushInfo);
```
> 关键：方法引用 `onSubmit::contextStats` / `state::pushInfo` 绑定局部参数 `onSubmit`/`state`（此处已是入参，非 null）。**不可**写成字段初始化器——那会在构造函数体执行前对尚为 null 的实例字段求值 → NPE。放构造函数体内、与 `printer` 并列即安全。

- [ ] **Step 3: 改 drain() 的节流调用**

把（当前 line 140）：
```java
        if (animTick % 30 == 0) refreshCtxStats();             // ~1s 刷一次状态栏上下文用量（节流：重算需遍历全部消息 + 估算 token）
```
改为：
```java
        if (animTick % 30 == 0) ctxUsage.refresh();            // ~1s 刷一次状态栏上下文用量（节流：重算需遍历全部消息 + 估算 token）
```

- [ ] **Step 4: 改 /context 分支调用**

把 `submitInput()` 里 `/context` 分支的（当前 line 450）：
```java
            printContext();
```
改为：
```java
            ctxUsage.report();
```

- [ ] **Step 5: 改状态行 IDLE 支的后缀调用**

把 `statusLine()` 的 IDLE 支（当前 line 652）里的 `ctxSuffix()` 换成 `ctxUsage.suffix()`：
```java
            case IDLE -> text("Enter 发送 · /model 切换模型 · Esc 取消 · Ctrl+C 退出 · " + onSubmit.currentModel() + ctxUsage.suffix()).style(HINT);
```

- [ ] **Step 6: 删除四个旧方法**

删除 `CodeTuiView` 中的 `printContext()`、`pct(long,long)`、`refreshCtxStats()`、`ctxSuffix()` 四个方法整体（含各自 Javadoc）。逻辑已搬入 `ContextUsage`。

- [ ] **Step 7: 删除不再使用的 import**

删除（移除四方法 + `ctxStats` 字段后视图不再直接引用 `ContextStats`）：
```java
import com.example.springai.codetui.agent.ContextStats;
```

- [ ] **Step 8: 编译**

Run: `mvn -o -q -pl springai-code-tui -am compile`
Expected: BUILD SUCCESS。若报 `ContextStats cannot find symbol`，说明仍有残留引用——搜索 `ContextStats` / `ctxStats` / `pct(` / `refreshCtxStats` / `ctxSuffix` / `printContext` 确认全部已删或改。

- [ ] **Step 9: 跑现有离线套件确认零回归**

Run: `mvn -o -q -pl springai-code-tui -am test -Dtest='!CodingAgentSpikeTest'`
Expected: #3 后基线 **94** 全绿，0 失败。

- [ ] **Step 10: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java
git commit -m "refactor(code-tui): 上下文用量委托 ContextUsage，删除旧字段与方法"
```

---

### Task 3: 新增 `ContextUsageTest`（纯 Java 单测，无 tamboui）

**Files:**
- Create: `springai-code-tui/src/test/java/com/example/springai/codetui/ui/ContextUsageTest.java`

- [ ] **Step 1: 写测试**

```java
package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.ContextStats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextUsage 纯 Java 单测：喂 {@link ContextStats}，断言 /context 报告每行文案分支、状态栏后缀、
 * 缓存刷新容错。全程无 tamboui 类型（本类是零 UI 依赖的接缝）。
 */
class ContextUsageTest {

    /** 收集 sink 输出的每一行。 */
    private static final class RecordingSink implements java.util.function.Consumer<String> {
        final List<String> lines = new ArrayList<>();
        @Override public void accept(String line) { lines.add(line); }
    }

    /** 造一份满快照：events=100（用户40/助手50/工具8/其他2）、est=30000、window=100000、
     *  threshold=60000、autoKeep=20、manualKeep=10。 */
    private static ContextStats full() {
        return new ContextStats(100, 40, 50, 8, 2, 30_000L, 60_000L, 100_000L, 20, 10);
    }

    @Test
    void report_emptySnapshot_printsTitleAndNoHistory() {
        RecordingSink sink = new RecordingSink();
        new ContextUsage(ContextStats::empty, sink).report();

        assertEquals(2, sink.lines.size(), "空快照 → 标题 + 尚无对话历史 两行");
        assertTrue(sink.lines.get(0).contains("上下文用量"));
        assertTrue(sink.lines.get(1).contains("尚无对话历史"));
    }

    @Test
    void report_nullSource_treatedAsEmpty() {
        RecordingSink sink = new RecordingSink();
        Supplier<ContextStats> nullSource = () -> null;
        new ContextUsage(nullSource, sink).report();

        assertEquals(2, sink.lines.size(), "source 返回 null 当空快照处理");
        assertTrue(sink.lines.get(1).contains("尚无对话历史"));
    }

    @Test
    void report_fullSnapshot_printsAllBranchesWithRoundedPercents() {
        RecordingSink sink = new RecordingSink();
        new ContextUsage(ContextUsageTest::full, sink).report();

        // 标题 + 事件桶 + token/占窗口 + 自动压缩 + 手动 = 5 行
        assertEquals(5, sink.lines.size(), "满快照 → 5 行报告");
        assertTrue(sink.lines.get(0).contains("上下文用量"));
        String bucket = sink.lines.get(1);
        assertTrue(bucket.contains("用户 40") && bucket.contains("助手 50") && bucket.contains("工具 8"), "事件分桶");
        assertTrue(bucket.contains("其他 2"), "otherEvents>0 才带其他");
        assertTrue(sink.lines.get(2).contains("占窗口 30%"), "30000/100000 = 30%");
        assertTrue(sink.lines.get(3).contains("当前 50%"), "自动压缩当前 30000/60000 = 50%");
        assertTrue(sink.lines.get(3).contains("保留最近 20 条"));
        assertTrue(sink.lines.get(4).contains("保留最近 10 条"), "手动行");
    }

    @Test
    void report_noWindow_omitsWindowPercentLine() {
        RecordingSink sink = new RecordingSink();
        // window=0, threshold=0, manualKeep=0 → 只标题 + 事件桶 + 无占窗口的 token 行
        ContextStats s = new ContextStats(5, 3, 2, 0, 0, 1_234L, 0L, 0L, 0, 0);
        new ContextUsage(() -> s, sink).report();

        assertEquals(3, sink.lines.size(), "无窗口/阈值/手动 → 3 行");
        assertFalse(sink.lines.get(1).contains("其他"), "otherEvents=0 不带其他");
        assertTrue(sink.lines.get(2).contains("估算 token") && !sink.lines.get(2).contains("占窗口"),
                "窗口=0 → 只打估算 token，无占窗口");
    }

    @Test
    void suffix_beforeRefresh_isEmpty() {
        ContextUsage cu = new ContextUsage(ContextUsageTest::full, new RecordingSink());
        assertEquals("", cu.suffix(), "未 refresh，cached 为 empty → 空后缀");
    }

    @Test
    void suffix_afterRefresh_showsContextPercent() {
        ContextUsage cu = new ContextUsage(ContextUsageTest::full, new RecordingSink());
        cu.refresh();
        assertEquals(" · 上下文 30%", cu.suffix(), "refresh 后读缓存：30000/100000 = 30%");
    }

    @Test
    void suffix_windowZero_isEmpty() {
        ContextStats noWindow = new ContextStats(5, 3, 2, 0, 0, 1_234L, 0L, 0L, 0, 0);
        ContextUsage cu = new ContextUsage(() -> noWindow, new RecordingSink());
        cu.refresh();
        assertEquals("", cu.suffix(), "窗口=0 → 空后缀");
    }

    @Test
    void refresh_sourceThrows_keepsPreviousCache() {
        AtomicReference<Supplier<ContextStats>> ref = new AtomicReference<>(ContextUsageTest::full);
        ContextUsage cu = new ContextUsage(() -> ref.get().get(), new RecordingSink());
        cu.refresh();                                   // 缓存 = full → 后缀 30%
        assertEquals(" · 上下文 30%", cu.suffix());

        ref.set(() -> { throw new RuntimeException("boom"); });
        cu.refresh();                                   // 抛异常，静默保留旧值
        assertEquals(" · 上下文 30%", cu.suffix(), "source 抛异常 → 保留上次缓存");
    }
}
```

- [ ] **Step 2: 跑新测试，确认全绿**

Run: `mvn -o -q -pl springai-code-tui -am test -Dtest=ContextUsageTest`
Expected: Tests run: 8, Failures: 0, Errors: 0。
- 若某断言失败（数值/文案对不上），STOP 并报告，不要改断言去迁就（计划作者已算过，真失败是重要信号）。
- 若编译失败（如 `ContextStats` 构造参数顺序/个数不符），对照 `springai-code-tui/src/main/java/com/example/springai/codetui/agent/ContextStats.java` 的 record 头修正**测试的构造调用**（10 个参数：`events, userEvents, assistantEvents, toolEvents, otherEvents, estimatedTokens(long), tokenThreshold(long), contextWindow(long), autoKeepEvents, manualKeepEvents`），并报告改动。

- [ ] **Step 3: 跑完整离线套件确认总数**

Run: `mvn -o -q -pl springai-code-tui -am test -Dtest='!CodingAgentSpikeTest'`
Expected: 基线 94 + 新增 8 = **102** 全绿，0 失败。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/test/java/com/example/springai/codetui/ui/ContextUsageTest.java
git commit -m "test(code-tui): ContextUsage 报告分支/占比舍入/缓存容错 单测"
```

---

### Task 4: pty 实机核对 + 收尾（收官 1–4 批）

**Files:** 无（验证 + 打包）

- [ ] **Step 1: 打包（务必重打，避免跑旧 jar）**

Run: `mvn -o -q -pl springai-code-tui -am package -DskipTests`
Expected: 生成 `springai-code-tui/target/springai-code-tui.jar`（依赖在 `target/lib/`）。

- [ ] **Step 2: pty+pyte 探针核对 /context 报告 + 状态栏后缀**

用 pty 探针（`DEEPSEEK_API_KEY=test-fake` 起 jar，`fcntl.ioctl` 设 winsize，冷启动约 6–8s）：键入 `/context` + Enter，
scrollback 应打印「📊 上下文用量」+「（尚无对话历史）」（无真会话时）；空闲状态行应含模型名（无对话时无「· 上下文」后缀，
符合 `suffix()` 空串契约）。与重构前对照须一致（纯搬运）。核对无 Java 异常栈。

- [ ] **Step 3: 最终整体审查 + 完成分支**

按 subagent-driven-development 收尾：派最终 reviewer 整体审 `ContextUsage` + 视图改动是否契合设计与零行为改动
（重点复核构造时序坑：`ctxUsage` 在构造函数体内 new、且在 onSubmit/state 赋值之后）；
通过后用 superpowers:finishing-a-development-branch 合并回 main（`--no-ff`，沿用本地累积惯例）。这也收官 1–4 批。

---

## 备注

- **基线数**：#3 后非 spike 离线基线为 94。本步新增 8 → 102。
- **构造时序**：`ctxUsage` 字段声明处不初始化，只在构造函数体内 `new`（onSubmit/state 已赋值后）——与 `printer` 同款。这是本步唯一的非平凡陷阱。
- **现算 vs 缓存语义保持**：`report()` 走实时 `source`，`suffix()` 走 `cached`（`refresh()` 填）——与重构前 `printContext` 用 `onSubmit.contextStats()`、`ctxSuffix` 用 `ctxStats` 字段完全一致。
- **测试避 locale 脆弱性**：断言集中在 pct 输出（`30%`/`50%`）与文案子串，不硬断言 `%,d` 的千分位分组（不同 locale 分隔符可能不同）。

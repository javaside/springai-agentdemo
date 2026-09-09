# Code TUI drain 批时间预算跨段共享修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `PhysicalOutputQueue.drain()` 的「前 2 行免时间检查」豁免按整批（跨多次 `drainQueuedOutput` 调用）累计判断，而不是每次调用各自重新豁免一次，从而不再允许 diff LCS 展开与 markdown 表格排版这两处已知的预算例外在同一批里叠加。

**Architecture:** `drain()` 新增 `alreadyWrittenThisBatch` 形参，豁免条件从 `written >= 2` 改为 `alreadyWrittenThisBatch + written >= 2`；唯一调用点 `CodeTuiView.drainQueuedOutput()` 把已经在维护的 `batchRowsUsed` 字段传进去。不改任何既有常量上限，不改算法本身。

**Tech Stack:** Java 21，JUnit 5，Maven（`mvn test -pl springai-code-tui`）。

**规格文档：** `docs/superpowers/specs/2026-09-09-code-tui-drain-budget-exemption-fix-design.md`

---

### Task 1: `PhysicalOutputQueue.drain()` 加 `alreadyWrittenThisBatch` 参数

**Files:**
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/output/PhysicalOutputQueueTest.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/output/PhysicalOutputQueue.java:118-132`（`drain()` 方法 + 其 Javadoc）以及类头 Javadoc 里「⚠ markdown 表格块的攒块也游离于两个预算之外」那一段
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java:516-520`（`drainQueuedOutput()`）

**背景（写代码前需要知道）：** `drain()` 目前是全仓唯一被调用的地方是 `CodeTuiView.java:519`。改它的签名会立刻让 `CodeTuiView.java` 编译失败，所以下面步骤 2 和步骤 4 会看到**两次不同原因**的编译错误——这是预期的，不是失败的计划。

- [ ] **Step 1: 写失败测试（新文件，调用还不存在的 4 参数签名）**

创建 `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/output/PhysicalOutputQueueTest.java`：

```java
package io.github.javaside.springai.codetui.ui.output;

import dev.tamboui.text.Text;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PhysicalOutputQueue.drain() 的 alreadyWrittenThisBatch 豁免语义（2026-09-09 修复）：
 * 「前 2 行免时间检查」必须按整批累计判断，不是按每次 drain() 调用各自的局部计数判断。
 * 见 docs/superpowers/specs/2026-09-09-code-tui-drain-budget-exemption-fix-design.md。
 */
class PhysicalOutputQueueTest {

    /** 记录型出口：本测试全部走纯文本分支。 */
    private static final class RecordingSink implements PhysicalOutputQueue.PhysicalSink {
        final List<String> lines = new ArrayList<>();
        @Override public void printlnPlain(String line, Object raw) { lines.add(line); }
        @Override public void printlnStyled(Text line, Object raw) {
            throw new UnsupportedOperationException("本测试不涉及带样式分支");
        }
    }

    /** 产出固定条数纯文本行（"line0".."line{count-1}"）的游标，每次 next() 摊还 O(1)。 */
    private static OutputCursor linesCursor(int count) {
        return new OutputCursor() {
            private int at = 0;
            @Override public boolean hasNext() { return at < count; }
            @Override public PhysicalOutputQueue.PhysicalLine next() {
                if (at >= count) return null;
                return PhysicalOutputQueue.PhysicalLine.plain("line" + (at++));
            }
        };
    }

    private static PhysicalOutputQueue queueWith(OutputCursor cursor) {
        PhysicalOutputQueue q = new PhysicalOutputQueue(rows -> {
            throw new UnsupportedOperationException("本测试不使用 streaming lines 入口");
        });
        q.enqueue(v -> cursor);
        return q;
    }

    private static long expiredDeadline() {
        return System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(1);
    }

    @Test
    void firstCallInBatch_stillGetsTwoLineGrace_evenPastDeadline() {
        // alreadyWrittenThisBatch=0：批的第一次调用，deadline 已过期，仍应至少写 2 行——
        // 这是「forward progress」保证的本意（慢机器不能卡在 0 行），修复后必须保留。
        PhysicalOutputQueue q = queueWith(linesCursor(5));
        RecordingSink sink = new RecordingSink();

        PhysicalOutputQueue.BatchResult result = q.drain(300, expiredDeadline(), 0, sink);

        assertEquals(2, result.rowsWritten(), "批的第一段即使 deadline 已过期也该写够 2 行");
        assertTrue(result.timeExhausted());
        assertEquals(List.of("line0", "line1"), sink.lines);
    }

    @Test
    void laterCallInBatch_getsNoGrace_evenWithJustOnePriorRow() {
        // alreadyWrittenThisBatch=1：批内更早的 drain() 调用已经写过 1 行（离门槛只差 1），
        // 这次调用（模拟 CodeTuiView 一批里的第二/第三段）不该重新获得「前 2 行免检」——
        // 这是本次要修的缺口：修复前这里的 rowsWritten 会是 2（每次调用各自重新豁免）。
        PhysicalOutputQueue q = queueWith(linesCursor(5));
        RecordingSink sink = new RecordingSink();

        PhysicalOutputQueue.BatchResult result = q.drain(300, expiredDeadline(), 1, sink);

        assertEquals(1, result.rowsWritten(),
                "批内已写 1 行时，本次调用只该再写 1 行就停——不是重新豁免 2 行");
        assertTrue(result.timeExhausted());
        assertEquals(List.of("line0"), sink.lines);
    }

    @Test
    void deadlineNotYetExpired_writesUpToRowBudget_regardlessOfAlreadyWritten() {
        // deadline 远未到时，alreadyWrittenThisBatch 不该提前掐断——只受行数预算约束。
        PhysicalOutputQueue q = queueWith(linesCursor(5));
        RecordingSink sink = new RecordingSink();
        long farFutureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        PhysicalOutputQueue.BatchResult result = q.drain(300, farFutureDeadline, 2, sink);

        assertEquals(5, result.rowsWritten(), "deadline 远未到时应该写完游标里的全部内容");
        assertFalse(result.timeExhausted());
        assertFalse(result.remaining());
    }

    @Test
    void twoSequentialCallsSharingCallerTrackedTotal_mirrorsCodeTuiViewUsage() {
        // 端到端风格：模拟 CodeTuiView 一批里连续两次 drainQueuedOutput 调用，调用方用
        // 累计值（对应 CodeTuiView.batchRowsUsed 字段）喂给下一次调用——第二次不该重新豁免。
        long deadline = expiredDeadline();

        PhysicalOutputQueue firstSegment = queueWith(linesCursor(5));
        RecordingSink sink = new RecordingSink();
        PhysicalOutputQueue.BatchResult first = firstSegment.drain(300, deadline, 0, sink);
        assertEquals(2, first.rowsWritten());

        int batchRowsUsed = first.rowsWritten();   // CodeTuiView 的既有累计字段语义
        PhysicalOutputQueue secondSegment = queueWith(linesCursor(5));
        PhysicalOutputQueue.BatchResult second =
                secondSegment.drain(300, deadline, batchRowsUsed, sink);

        assertEquals(1, second.rowsWritten(),
                "第二段不该独立于第一段重新获得 2 行豁免——这是本次修复要钉住的缺口");
    }
}
```

- [ ] **Step 2: 跑测试，确认因签名不存在而编译失败**

Run: `mvn test -pl springai-code-tui -Dtest=PhysicalOutputQueueTest`
Expected: 编译错误，形如 `method drain in class PhysicalOutputQueue cannot be applied to given types; required: int,long,PhysicalSink found: int,long,int,PhysicalSink`（现有 `drain()` 只接受 3 个参数）。

- [ ] **Step 3: 修改 `PhysicalOutputQueue.drain()` 签名与逻辑**

在 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/output/PhysicalOutputQueue.java` 里，把：

```java
    /**
     * 消费一批：从队头游标（或下一个未展开项）逐行写出，行数达 {@code maxPhysicalRows} 或
     * 时间预算耗尽即停。在取第 {@code maxPhysicalRows + 1} 行之前停下（永不超发一行）。
     *
     * @param maxPhysicalRows 本批物理行硬上限（&gt;0）
     * @param deadlineNanos   <b>绝对</b>截止时刻（nanoTime 域）；≤0 视为不限时。
     *                        同一 UI 批的多个 drain 段应传同一个值（批内共享预算）。
     * @param sink            物理行出口（两条分支对应 {@link PhysicalLine} 的两种形态）
     * @return 批次结果
     */
    public BatchResult drain(int maxPhysicalRows, long deadlineNanos, PhysicalSink sink) {
        int written = 0;
        boolean timeExhausted = false;
        while (written < maxPhysicalRows) {
            OutputCursor cursor = ensureActive();
            if (cursor == null) break;                       // 队列空：自然收尾
            PhysicalLine line = cursor.next();               // 只物化一段（摊还 O(当前逻辑行)）
            if (line == null) {                              // 游标耗尽（或 hasNext 假阳性）：移除、换下一个
                dropActive();
                continue;
            }
            if (line.styled() != null) sink.printlnStyled(line.styled(), line.raw());
            else sink.printlnPlain(line.plain() == null ? "" : line.plain(), line.raw());
            written++;
            // 时间检查从第二行起：首行前的工厂成本（如 diff 的读文件+LCS，O(一个工具入参) 的一次性
            // 工作，见类注释的如实声明）不挤占行吞吐预算——否则慢机器上首行后立即停，一批只出 1 行。
            if (written >= 2 && deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
                timeExhausted = true;                        // 行间时间检查（设计 §9.1）
                break;
            }
        }
        return new BatchResult(written, !isEmpty(), timeExhausted);
    }
```

改成：

```java
    /**
     * 消费一批：从队头游标（或下一个未展开项）逐行写出，行数达 {@code maxPhysicalRows} 或
     * 时间预算耗尽即停。在取第 {@code maxPhysicalRows + 1} 行之前停下（永不超发一行）。
     *
     * @param maxPhysicalRows 本批物理行硬上限（&gt;0）
     * @param deadlineNanos   <b>绝对</b>截止时刻（nanoTime 域）；≤0 视为不限时。
     *                        同一 UI 批的多个 drain 段应传同一个值（批内共享预算）。
     * @param alreadyWrittenThisBatch 调用方在<b>同一 UI 批</b>内、在本次调用之前，已经通过
     *                        其它 {@code drain()} 调用写出的物理行总数（2026-09-09 修复）。
     *                        「前 2 行免时间检查」的豁免按 {@code alreadyWrittenThisBatch + 本次
     *                        写出行数} 判断，不是按本次调用的局部计数——否则一批里连续多次
     *                        调用 {@code drain()}（主段/计划段/强制表格 flush 段）会各自
     *                        重新豁免一次，diff LCS 展开与表格排版这两处已知的预算例外
     *                        （见类注释）就能跨段叠加。传 0 等价于「批的第一段」。
     * @param sink            物理行出口（两条分支对应 {@link PhysicalLine} 的两种形态）
     * @return 批次结果
     */
    public BatchResult drain(int maxPhysicalRows, long deadlineNanos,
            int alreadyWrittenThisBatch, PhysicalSink sink) {
        int written = 0;
        boolean timeExhausted = false;
        while (written < maxPhysicalRows) {
            OutputCursor cursor = ensureActive();
            if (cursor == null) break;                       // 队列空：自然收尾
            PhysicalLine line = cursor.next();               // 只物化一段（摊还 O(当前逻辑行)）
            if (line == null) {                              // 游标耗尽（或 hasNext 假阳性）：移除、换下一个
                dropActive();
                continue;
            }
            if (line.styled() != null) sink.printlnStyled(line.styled(), line.raw());
            else sink.printlnPlain(line.plain() == null ? "" : line.plain(), line.raw());
            written++;
            // 时间检查从整批第 2 行起：首行前的工厂成本（如 diff 的读文件+LCS，O(一个工具入参)
            // 的一次性工作，见类注释的如实声明）不挤占行吞吐预算——否则慢机器上首行后立即停，
            // 一批只出 1 行。「整批」= alreadyWrittenThisBatch + written（2026-09-09 修复：此前
            // 只看本次调用的局部 written，同一批里多次调用会各自重新豁免一次）。
            if (alreadyWrittenThisBatch + written >= 2
                    && deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
                timeExhausted = true;                        // 行间时间检查（设计 §9.1）
                break;
            }
        }
        return new BatchResult(written, !isEmpty(), timeExhausted);
    }
```

同时在类头 Javadoc 里，把这一段（「⚠ markdown 表格块的攒块也游离于两个预算之外」那一段中的一句）：

```
 * 缓冲期间<b>不写行</b>，而时间检查从第 2 行起才生效——所以一批可以喂完 600 条 pending +
 * 300 条流式行、完全不受 12ms 约束。量级由渲染器输入上限（200 行 / 64 K 原文字符，越限降级成逐行
 * 原样输出）与排版产出上限（600 物理行，越限整块退回原样）双向封顶，可接受。
```

改成：

```
 * 缓冲期间<b>不写行</b>，而时间检查从<b>整批</b>第 2 行起才生效（{@code alreadyWrittenThisBatch}，
 * 2026-09-09 修复：此前按每次 {@code drain()} 调用各自的局部计数判断，同一批里多次调用会各自
 * 重新豁免一次——现在改为调用方传入的整批累计值，豁免只在批的第一次真正开始消费内容时生效
 * 一次）。单个表格块本身仍可能在这一次豁免内把 200 行 / 64K 字符排版完、不受 12ms 约束，量级
 * 由渲染器输入上限（200 行 / 64 K 原文字符，越限降级成逐行原样输出）与排版产出上限（600 物理行，
 * 越限整块退回原样）双向封顶，可接受。
```

- [ ] **Step 4: 再跑测试，确认这次是 `CodeTuiView.java` 编译失败**

Run: `mvn test -pl springai-code-tui -Dtest=PhysicalOutputQueueTest`
Expected: 编译错误，这次报在 `CodeTuiView.java:519`——它还在用旧的 3 参数调用
`outputQueue.drain(budget, batchDeadlineNanos, queueSink)`，与刚改的新签名不匹配。
这是预期的：本模块只要有一处 `src/main` 编译失败，全部测试都跑不起来。

- [ ] **Step 5: 修 `CodeTuiView.drainQueuedOutput()` 调用点**

在 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java` 里，把：

```java
    private int drainQueuedOutput(int budget) {
        if (budget <= 0 || outputQueue.isEmpty()) return 0;
        drainDeadlinesObserved.add(batchDeadlineNanos);   // 测试观测点：所有段必须是同一个绝对时刻
        return outputQueue.drain(budget, batchDeadlineNanos, queueSink).rowsWritten();
    }
```

改成：

```java
    private int drainQueuedOutput(int budget) {
        if (budget <= 0 || outputQueue.isEmpty()) return 0;
        drainDeadlinesObserved.add(batchDeadlineNanos);   // 测试观测点：所有段必须是同一个绝对时刻
        return outputQueue.drain(budget, batchDeadlineNanos, batchRowsUsed, queueSink).rowsWritten();
    }
```

（`batchRowsUsed` 字段本身不需要新增或改动——它已经在 `processUpdatesInsideBatch` 开头被置 0
并在三个调用点被正确累加，这里只是多传一份给 `drain()` 用于豁免判断。）

- [ ] **Step 6: 再跑测试，确认全部通过**

Run: `mvn test -pl springai-code-tui -Dtest=PhysicalOutputQueueTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 7: 跑全模块测试，确认没有回归**

Run: `mvn test -pl springai-code-tui`
Expected: `BUILD SUCCESS`，全部测试通过（改动前的基线是全绿，这里应该维持全绿）。

- [ ] **Step 8: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/output/PhysicalOutputQueue.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/output/PhysicalOutputQueueTest.java
git commit -m "fix(code-tui): drain() 时间预算豁免改按整批累计，不再按每次调用各自重新豁免"
```

### Task 2: `DrainBurstCapTest.java` 端到端集成覆盖

**Files:**
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/DrainBurstCapTest.java`（新增一个测试方法，复用文件已有的 `view()`/`quote()` helper）

**这个任务的定位（务必先读，别按 Task 1 的红绿节奏来）：** Task 1 的 `PhysicalOutputQueueTest`
已经用可控的假游标 + 显式过期 deadline，**确定性地**证明了修复的核心逻辑（不依赖真实墙钟）——
那是本次修复唯一需要「改前红、改后绿」的证据。

本任务写的是**端到端集成测试**，用真实 `DiffRenderer`（LCS_MAX 量级的大 diff）+ 真实
`MarkdownRenderer`（未闭合的大表格）跑一遍完整的 `CodeTuiView` 批处理管线。前期验证（写这份
计划之前用临时代码实测过，未提交）发现：diff 展开成本、表格排版成本、pending 转入上限
（`MAX_PENDING_INTAKE_PER_TICK=600`）、streaming 完整行的批内游标（`MdLineCursor` 内部会
连续吃掉多条不产出的表格行才返回）之间的真实交互比预想的更精细——**想在真实内容上精确命中
「同一批里两处例外恰好叠加」的那个时间窗口并不可靠**（JIT/类加载预热本身就有数毫秒到十几毫秒
的抖动，会让这类精确窗口测试变成 flaky test）。

所以本任务的测试**不要求（也不应该）在改动前失败**——它的价值是集成层面的内容完整性 +
粗粒度性能兜底，不是精确复现 Task 1 已经钉住的那个 bug。写完之后正常跑一遍确认通过即可，
不需要（也不会）观察到红灯。

- [ ] **Step 1: 在 `DrainBurstCapTest.java` 里加这个测试方法**

在文件末尾（最后一个 `}` 之前，即现有最后一个 `@Test` 方法之后）插入：

```java
    @Test
    @DisplayName("大 diff + 未闭合大表格同现一次回复：内容不丢，且没有单批耗时异常（集成兜底）")
    void expensiveDiffAndUnclosedTable_contentIntactAndNoSingleBatchBlowsUp(@TempDir Path root)
            throws Exception {
        // segment ① 的真实成本来源：LCS_MAX(=800) 量级、内容互不相同的 Edit diff。
        Path file = root.resolve("Big.java");
        int n = 800;
        StringBuilder oldFile = new StringBuilder();
        StringBuilder oldString = new StringBuilder();
        StringBuilder newString = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String oldLine = "old_line_" + i + "_unique_content_padding_xyz";
            if (i > 0) { oldFile.append('\n'); oldString.append('\n'); newString.append('\n'); }
            oldFile.append(oldLine);
            oldString.append(oldLine);
            newString.append("new_line_").append(i).append("_totally_different_qrs");
        }
        Files.writeString(file, oldFile.toString());
        String editJson = "{\"filePath\":" + quote(file.toString())
                + ",\"old_string\":" + quote(oldString.toString())
                + ",\"new_string\":" + quote(newString.toString()) + "}";

        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        state.onToolStarted(1L, "Edit", editJson);

        // segment ③（强制表格 flush）的触发条件：回复以未闭合表格结尾（无收尾空行），回合结束。
        StringBuilder table = new StringBuilder();
        table.append("| 方案名称 | 说明 | 优点 | 缺点 | 指数 | 备注 |\n");
        table.append("|---|---|---|---|---|---|\n");
        for (int i = 0; i < 198; i++) {
            table.append("| 方案").append(i).append(" | 说明文字说明文字 | 优点简述 | 缺点简述 | ")
                    .append(i % 5).append(" | 备注信息备注信息 |");
            if (i < 197) table.append('\n');
        }
        state.onAssistantToken(1L, table.toString());
        state.onTurnComplete(1L);

        long maxSingleBatchMs = 0;
        for (int batch = 0; batch < 30; batch++) {
            int before = sink.lines.size();
            long start = System.nanoTime();
            v.tickForTest();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            maxSingleBatchMs = Math.max(maxSingleBatchMs, elapsedMs);
            if (sink.lines.size() == before) break;   // 一批没写任何新行 ⇒ 存量已清空
        }

        // 粗粒度兜底：不追求精确复现 Task 1 已经钉住的 bug，只防「多个例外无限叠加」这类
        // 严重回归——那种情况耗时量级是几百毫秒起，不是这里测的十几毫秒。
        assertTrue(maxSingleBatchMs < 200,
                "单批耗时 " + maxSingleBatchMs + "ms 过长，像是多个预算例外在同一批里无限叠加");
        assertTrue(sink.lines.stream().anyMatch(l -> l.contains("Edit(Big.java)")),
                "diff 头应该最终出现在输出里，内容不能丢");
        assertTrue(sink.lines.stream().anyMatch(l -> l.contains("方案197")),
                "表格最后一行数据应该最终出现在输出里，内容不能丢");
    }
```

- [ ] **Step 2: 跑这个测试，确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=DrainBurstCapTest#expensiveDiffAndUnclosedTable_contentIntactAndNoSingleBatchBlowsUp`
Expected: `Tests run: 1, Failures: 0, Errors: 0`（如前面说明，这里预期就是绿——不是红绿钉子）。

- [ ] **Step 3: 跑整个文件 + 全模块，确认没有回归**

Run: `mvn test -pl springai-code-tui -Dtest=DrainBurstCapTest`
Expected: 全绿（文件里原有用例不受影响）。

Run: `mvn test -pl springai-code-tui`
Expected: `BUILD SUCCESS`，全部测试通过。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/DrainBurstCapTest.java
git commit -m "test(code-tui): DrainBurstCapTest 补大 diff + 未闭合大表格同批集成覆盖"
```

---

## 验收

两个 task 完成后应满足设计文档（`docs/superpowers/specs/2026-09-09-code-tui-drain-budget-exemption-fix-design.md`）第 6 节列出的全部 6 条验收标准。两个 task 都不涉及 `springai-tamboui-inline-patch` 模块，不需要重跑该模块测试。

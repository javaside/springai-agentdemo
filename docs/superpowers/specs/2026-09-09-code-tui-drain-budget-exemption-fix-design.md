# Code TUI drain 批时间预算跨段共享修复设计

## 1. 背景

用户报告：`code-tui` 界面在大模型输出文字到界面时，输入框打字仍会卡死，且这个问题「反复修改很多次了」。

2026-09-03 `497c4c65` 曾对同一症状做过一次彻底修复（`AsyncPtyWriter`：把 pty 的 `write(2)`
剥离到独立守护线程，根因是内核 pty 写缓冲仅 1-2KiB、终端读端停摆时 write 无界同步阻塞），
经 5 轮 subagent 审核、67+1831 测试全绿，规格见
`docs/superpowers/specs/2026-09-02-code-tui-async-pty-writer-design.md`。本次排查首先重跑了
该修复的三个回归测试（`InlineTuiRunnerEventDrivenTest`/`AsyncPtyWriterTest`/
`InlineDisplayAsyncWriterTest`，含专门钉「pty 写卡死期间按键 1s 内被处理」的症状测试），33/33
仍然全绿——**pty 写阻塞这条根因没有回归**，需要找一个不同的机制。

继续往上追，发现 `PhysicalOutputQueue`（渲染批的「300 物理行 / 12ms」双预算出口，2026-09-03
修复的配套设施）自己的 Javadoc 老实声明了两处例外，均发生在拿到第一行输出之前、时间检查
（`written>=2` 才生效）根本够不到的地方：

1. **diff 展开成本**（`DiffRenderer`，`LCS_MAX=800`）：cursor 工厂调用时做一次性的 LCS 差分计算。
2. **markdown 表格排版成本**（`MarkdownRenderer.feed`/`flush` 里的 `renderBufferedBlock`，
   `MAX_BUFFERED_ROWS=200`/`MAX_BUFFERED_CHARS=64K`）：整块表格缓冲到块结束才一次排版
   （列宽计算 + 逐格折行），这个排版发生在某次 `next()`/`feed()` 调用内部。

这两处例外建于 2026-09（表格渲染功能整个建在 async-pty-writer 修复**之后**），是「已知可接受」的
设计取舍，但从未被实测量化。本次排查用临时测量代码（未提交，验证后已删除）在各自上限下测了
真实成本：diff LCS（800×800 行，内容互不相同的最坏情况）约 16.5ms；表格排版（200 行，窄终端/
多列两种压力场景）约 13ms。两者单独看都不到 20ms，不是那种一下卡几百毫秒的量级——不足以单独
解释「卡死」，需要继续找叠加机制（见「根因」一节）。

排查过程中一并确认排除的可能性：
- **事件队列没有优先级饿死问题**：`InlineTuiRunner.eventQueue` 是单个 `LinkedBlockingQueue`，
  按键事件与 UI 更新批共用同一个 FIFO 队列，先到先处理，不存在按键被系统性插队的机制。
- **不是正则回溯**：`MarkdownRenderer`/`MarkdownTable`/`DiffRenderer`/`ScrollbackPrinter`
  全部是手写状态机/字符扫描，未使用任何 `Pattern`/`Matcher`/`replaceAll`/`matches`。

## 2. 根因

`PhysicalOutputQueue.drain(int maxPhysicalRows, long deadlineNanos, PhysicalSink sink)`
（`springai-code-tui/.../ui/output/PhysicalOutputQueue.java:128`）内部：

```java
public BatchResult drain(int maxPhysicalRows, long deadlineNanos, PhysicalSink sink) {
    int written = 0;   // ← 每次调用从 0 计的局部变量
    ...
    while (written < maxPhysicalRows) {
        ...
        written++;
        if (written >= 2 && deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
            timeExhausted = true;
            break;
        }
    }
    ...
}
```

`written` 是**每次调用 `drain()` 都从 0 重新计**的局部变量。而 `CodeTuiView` 一批
（一次 `processUpdatesInsideBatch` + `computeFollowUpFlags`）最多调 3 次
`drainQueuedOutput`（薄封装，直接转发到 `outputQueue.drain(...)`，见
`CodeTuiView.java:516-520`）：

1. `CodeTuiView.java:916` —主输出段，无条件调用；
2. `CodeTuiView.java:957` — 检测到新 `PlanRequest` 模态时，计划正文段；
3. `CodeTuiView.java:1033`（在 `computeFollowUpFlags` 内）— 回合结束/UI 为用户暂停且
   `printer.hasBufferedTable()` 时，强制表格 flush 段。

三次调用共享同一个 `batchDeadlineNanos`（`processUpdatesInsideBatch` 开头算一次，
fix round I-3 已修过「deadline 不能每段各自新起窗口」），也共享同一个跨调用累计的
`batchRowsUsed` 字段（用于 300 行的**行数**预算）——但**没有把 `batchRowsUsed` 告诉
`drain()`**，导致「前 2 行免时间检查」这个门槛按**每次调用各自的局部 `written`** 判断，
而不是按整批已写行数判断。结果：只要某一段恰好是这一批第一次调用 `drain()`（哪怕之前的
段已经写出去几十上百行），它就会重新拿到一次「前 2 行免检」的豁免——它的 setup 成本
（diff LCS 或表格排版）就又能不受时间预算约束地全额执行一次。

最容易触发的场景：主段（①）处理正文时恰好把 12ms 预算耗尽，此时表格没有自然收尾
（模型最后一行就是表格行，没有跟着一行空行/非表格行），回合在同一批里结束——③ 的
强制 flush 段紧接着触发，重新拿到一次豁免，把表格排版的 ~13ms 叠加在①已经花掉的
预算之上。这与用户描述的「反复」相符：code-tui 是编码 agent，diff 展示（①段）和
表格（可能触发③段）在真实使用中都是常见内容，不是边缘情况。

## 3. 已确认目标 / 非目标

**目标：**

1. 「前几行免时间检查」的豁免门槛必须按**整批已写行数**判断，不是按每次 `drain()`
   调用各自的局部计数判断。
2. 不改变现有的「forward progress」保证：一批里第一次真正开始消费内容时，仍然至少
   要能写出东西（慢机器不能因为一上来就撞上 deadline 而卡在 0 行——这是原设计
   `written>=2`（而非 `written>=1`）豁免的本意，必须保留）。
3. 现有全部测试保持绿灯；新增测试直接钉住本次要修的缺口（多段各自豁免）。

**非目标（用户已确认本次只修账目 bug，不做更大改动）：**

- 不改 `MAX_ROWS_PER_DRAIN`(300) / `MAX_DRAIN_NANOS`(12ms) / `MAX_BUFFERED_ROWS`(200) /
  `MAX_BUFFERED_CHARS`(64K) / `LCS_MAX`(800) 这些既有上限数值——实测单条目最坏成本
  （13~17ms）在这些上限下并不夸张，问题是叠加、不是上限本身。
- 不把表格排版或 diff LCS 改成可中断/增量算法，也不搬到后台线程——工程量大、风险高，
  根据实测数据不需要走到这一步。
- 不改 `AsyncPtyWriter`/pty 写路径本身（已验证健康，回归测试仍 33/33 绿，不在本次范围）。

## 4. 修复设计

两处改动，仅涉及 2 个生产代码文件。全仓 `.drain(` 只有一个调用点
（`CodeTuiView.java:519`），没有直接的 `PhysicalOutputQueueTest`，改签名不产生额外的
调用点连锁修改。

### 4.1 `PhysicalOutputQueue.drain()` 新增一个参数

文件：`springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/output/PhysicalOutputQueue.java:128`

修改前：

```java
public BatchResult drain(int maxPhysicalRows, long deadlineNanos, PhysicalSink sink) {
    int written = 0;
    boolean timeExhausted = false;
    while (written < maxPhysicalRows) {
        OutputCursor cursor = ensureActive();
        if (cursor == null) break;
        PhysicalLine line = cursor.next();
        if (line == null) {
            dropActive();
            continue;
        }
        if (line.styled() != null) sink.printlnStyled(line.styled(), line.raw());
        else sink.printlnPlain(line.plain() == null ? "" : line.plain(), line.raw());
        written++;
        if (written >= 2 && deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
            timeExhausted = true;
            break;
        }
    }
    return new BatchResult(written, !isEmpty(), timeExhausted);
}
```

修改后（唯一变化：新增形参 `alreadyWrittenThisBatch`，豁免门槛从 `written >= 2` 改成
`alreadyWrittenThisBatch + written >= 2`）：

```java
public BatchResult drain(int maxPhysicalRows, long deadlineNanos,
        int alreadyWrittenThisBatch, PhysicalSink sink) {
    int written = 0;
    boolean timeExhausted = false;
    while (written < maxPhysicalRows) {
        OutputCursor cursor = ensureActive();
        if (cursor == null) break;
        PhysicalLine line = cursor.next();
        if (line == null) {
            dropActive();
            continue;
        }
        if (line.styled() != null) sink.printlnStyled(line.styled(), line.raw());
        else sink.printlnPlain(line.plain() == null ? "" : line.plain(), line.raw());
        written++;
        if (alreadyWrittenThisBatch + written >= 2
                && deadlineNanos > 0 && System.nanoTime() >= deadlineNanos) {
            timeExhausted = true;
            break;
        }
    }
    return new BatchResult(written, !isEmpty(), timeExhausted);
}
```

`alreadyWrittenThisBatch` 语义：调用方在**同一个 UI 批**内、在此次 `drain()` 调用之前，
已经通过其它 `drain()` 调用写出的物理行总数。传 0 等价于旧行为（批的第一段调用）。

同时更新类 Javadoc 中「⚠ markdown 表格块的攒块也游离于两个预算之外」那段的表述——
需要补一句：该例外原先按「每次 `drain()` 调用」计算，现在按「整批」计算，仍然保留
「首次触达」豁免、但不再可被多段调用重复触发。（具体行内注释措辞由实现者在编码时
按现有风格调整，无需在此固定死文字。）

### 4.2 `CodeTuiView.drainQueuedOutput()` 传入批内已写行数

文件：`springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java:516-520`

修改前：

```java
private int drainQueuedOutput(int budget) {
    if (budget <= 0 || outputQueue.isEmpty()) return 0;
    drainDeadlinesObserved.add(batchDeadlineNanos);   // 测试观测点：所有段必须是同一个绝对时刻
    return outputQueue.drain(budget, batchDeadlineNanos, queueSink).rowsWritten();
}
```

修改后（唯一变化：把已经在维护的 `batchRowsUsed` 字段传给 `drain()`）：

```java
private int drainQueuedOutput(int budget) {
    if (budget <= 0 || outputQueue.isEmpty()) return 0;
    drainDeadlinesObserved.add(batchDeadlineNanos);   // 测试观测点：所有段必须是同一个绝对时刻
    return outputQueue.drain(budget, batchDeadlineNanos, batchRowsUsed, queueSink).rowsWritten();
}
```

`batchRowsUsed` 字段本身**不需要新增或改动**——它已经在 `processUpdatesInsideBatch`
开头被置 0（`CodeTuiView.java:891`），并在三个调用点（916/957/1033）被正确累加。这里
只是把这个「调用 `drainQueuedOutput` 时刻、批内已经写了多少行」的既有信息，多传一份
给 `drain()` 用于豁免判断——调用方完全不用变。

### 4.3 三个调用点在修复后的行为

| 调用点 | 触发条件 | 修复前 | 修复后 |
|---|---|---|---|
| ① L916 主段 | 每批必调 | `batchRowsUsed` 恒为 0，豁免按局部 `written` | 传 0，行为不变（批的第一段，豁免语义与今天相同） |
| ② L957 计划段 | 检测到新 `PlanRequest` | 局部 `written` 从 0 重新计，重新豁免一次 | 传入①已写的 `batchRowsUsed`；若①已写≥2行，②不再豁免 |
| ③ L1033 强制表格 flush 段 | 回合结束/UI 暂停且有缓冲表格 | 局部 `written` 从 0 重新计，重新豁免一次 | 传入①（+②）已写的 `batchRowsUsed`；若之前已写≥2行，③不再豁免 |

②③ 只有在**它们是这一批唯一发生工作的段**（即 `batchRowsUsed` 传入时仍为 0）时才继续
享有豁免——这正是「forward progress」保证要保留的情形（比如回合以纯表格结尾、①段
完全没有其它内容可写），修复后依然成立。

## 5. 测试计划

### 5.1 新建 `PhysicalOutputQueueTest.java`（单元级，直接测 `drain()`）

全仓目前没有直接测试这个类的文件——`DrainBurstCapTest` 是经 `CodeTuiView` 的端到端测试。
新文件补两类用例：

- **回归前会红的核心用例**：构造两个各自「设置成本可观测」的 entry（用一个自定义
  `OutputCursor`，`next()` 首次调用里 `Thread.sleep` 或自旋消耗掉足够时间来模拟 setup
  成本，确保测试不依赖真实 LCS/表格实现、不 flaky）。第一次 `drain(..., alreadyWrittenThisBatch=0, ...)`
  写够 2 行触发 `timeExhausted`；第二次 `drain(..., alreadyWrittenThisBatch=<第一次的 rowsWritten>, ...)`
  用同一个 deadline（已过期）调用——断言第二次**在第一行就停**（`rowsWritten<=1`
  或 `timeExhausted` 在极早触发），证明豁免没有被第二次调用重新赋予。
- **保留的「forward progress」用例**：`alreadyWrittenThisBatch=0` 且 deadline 已过期时，
  单次 `drain()` 调用仍必须至少写出内容（不能卡在 0 行）——钉住豁免语义本身没被误删。
- 覆盖 `alreadyWrittenThisBatch` 传参为 1（不足 2）与 2+（已达门槛）两档边界。

### 5.2 `DrainBurstCapTest.java` 新增一条端到端用例

按该文件现有风格（`CodeTuiView` + `RecordingSink`，`state.onXxx(...)` 驱动 + `v.tickForTest()`）：

- 构造一批：先给 `state` 灌入若干正文行使主段（①）自然耗掉大部分 12ms 预算，再让
  同一批的回合以「表格行结尾、无收尾空行」结束，触发③强制 flush 段。
- 断言：`sink.lines` 中来自③段的物理行数被截断在很小的数量（而不是③段独立拿到一整份
  12ms/300行 的预算全额跑完），即③没有获得独立于①的豁免。
- 由于真实 diff/表格在 13~17ms 量级、单元测试里用真实 `MarkdownTable`/`DiffRenderer`
  跑一次是可接受的（不需要 mock 出人为延迟），构造接近 `MAX_BUFFERED_ROWS`/`LCS_MAX`
  上限的内容即可稳定复现「setup 成本 > 12ms」这个前提条件。

### 5.3 现有测试

`drainDeadlinesObserved` 这个既有测试观测点（钉「三段必须共用同一个 deadline」）不受
影响，继续保留。跑 `mvn test -pl springai-code-tui` 确认全绿（改动前基线与改动后都要
过一遍）。

## 6. 验收标准

1. `PhysicalOutputQueue.drain()` 新增 `alreadyWrittenThisBatch` 参数，豁免判断改为
   `alreadyWrittenThisBatch + written >= 2`；`CodeTuiView.drainQueuedOutput()` 传入
   `batchRowsUsed`。
2. 新增 `PhysicalOutputQueueTest.java`：证明「第二次 `drain()` 调用在批内已写行数
   已达门槛时不再重新豁免」，且证明「批的第一次调用仍保留豁免（forward progress）」。
3. `DrainBurstCapTest.java` 新增端到端用例：主段耗掉预算 + 同批触发强制表格 flush 段，
   断言 flush 段没有独立于主段的完整 12ms/300 行豁免。
4. `mvn test -pl springai-code-tui` 改动前后均全绿。
5. 不涉及 `springai-tamboui-inline-patch` 模块（AsyncPtyWriter 路径不在本次改动范围内），
   不需要重跑该模块测试，但本次排查已确认其现状健康（33/33）。
6. 未改动任何既有常量上限（300/12ms/200/64K/800）。

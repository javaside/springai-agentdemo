# Task 5 实施报告

## 状态

DONE

## 修改文件

- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/output/OutputCursor.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/output/PhysicalOutputQueue.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ScrollbackPrinter.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`
  （brief 列了 DiffRenderer「Modify as needed for incremental diff」——实现中发现 DiffRenderer 的
  `List<DiffLine>` 结果本身就是惰性逐行消费的完美载体：header/hunk/行样式与真实行号都在每条
  `DiffLine` record 里，无需改 DiffRenderer；增量性由 `toolStartCursor` 在其结果之上做惰性迭代
  + 逐行高亮推进实现。故 DiffRenderer 零改动，最小偏差。）
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/DrainBurstCapTest.java`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/StrictOutputFairnessTest.java`

提交：`a9df35a refactor(code-tui): strictly batch physical terminal output`（6 files, +781/−89）

## 实现摘要

### 核心抽象：`OutputCursor`（逻辑输出 → 可续消费物理行）

接口按 brief：`hasNext()` / `next()`（返回 `PhysicalOutputQueue.PhysicalLine`——brief 里
`PhysicalLine` 定义在 `PhysicalOutputQueue` 内，与其 record 嵌套布局一致，故 cursor 接口引用它）。

staging 有界性的实现方式（见「staging 有界性证明」节）：**每次 `next()` 只渲染一条逻辑行**，
其折行段（≤ 该逻辑行的物理段数）缓存在游标内部的 `ArrayDeque`，消费完再渲染下一条。

### `PhysicalOutputQueue`（严格批次）

- `enqueue(Function<Void, OutputCursor>)`：cursor 工厂**惰性调用**——drain 轮到该 entry 才展开
  （diff 的读文件 + LCS 只做一次，且不在入队时做：堆在队列里的 entry 零渲染开销）。
- `enqueueStreamingLines(List<String>)`：只存逻辑行引用（这些 String 本就完整存在于
  `ConversationState.streaming`），渲染留给 drain，一批 300 行只渲染 300 行。
- `drain(maxPhysicalRows, maxNanos, sink)`：**逐行预算**——`while (written < maxPhysicalRows)`
  循环体每次恰好取一行、写一行；即在第 `maxPhysicalRows+1` 行被取到**之前**停住（硬上限，
  无 slack）。行间检查 `System.nanoTime() >= deadline`（时间预算），耗尽置
  `timeBudgetExhausted` 并立即返回。时间检查从第 2 行起（`written >= 2`）：首行前的工厂成本
  （如 diff 的 O(一个工具入参) 一次性工作）不挤占行吞吐预算——否则慢机器上首批只出 1 行。
- **活跃 cursor 在队头**：`ensureActive()` 只展开队头 entry，`dropActive()` 耗尽才移除。
- `clear()`：语义性丢弃（/clear 用）。

### `ScrollbackPrinter`：cursor 工厂（保留渲染状态与样式）

- `userBlockCursor(text)`：灰底白字块逐逻辑行折行 + 右补白铺满底色；`md.reset()` 在**工厂调用时**
  发生（drain 轮到这条 USER 输出时），与旧「drain 的 USER 分支」时机语义一致。
- `assistantCursor(text)` / `streamingLinesCursor(rows)`：一条逻辑行 → `md.renderFinalized`
  （推进围栏/围栏内语言/跨行块注释状态）→ `TextWrap` 折行 → 悬挂缩进物理段。
  **状态跨批次保持**：`md` 是 printer 级单例，游标每次渲染下一条逻辑行时从上一条结束时的状态
  继续——把渲染拆进游标而不复制状态，正是为了保证这一点。
- `toolStartCursor(ol)`：diff 展开（读文件 + LCS + 真实行号）在工厂调用时做一次；之后逐行
  （逐行语法高亮 + 跨行块注释推进 `inBlock` 存于游标）惰性产出；每条 diff 行按终端宽折行
  （ADD/DEL 底色带铺满每段）；非文件写入/无法解析 → 单行摘要回退（同样折行）。
- `lineCursor(ol)`：单色行（TOOL_OK/TODO/ERROR/INFO/SUBAGENT_*），超宽按终端宽折行、每段沿用
  同一样式（承接旧实现视图出口的兜底折行——折行移进游标后由它接手）。
- 旧一次性方法（`userBlock`/`toolStart`/`line`）改为「跑完对应游标」的薄壳
  （`run(cursor)`），欢迎横幅等小输出与既有 `ScrollbackPrinterTest` 路径不变。

### `CodeTuiView` 接线（不动驱动方式，66ms 周期仍在，Task 7 才删）

- `rowsThisFrame` 软上限字段删除，换 `PhysicalOutputQueue outputQueue` +
  `MAX_DRAIN_NANOS = 12ms` 时间预算 + `batchRowsUsed`（批内跨段共享 300 行预算）。
- `drainInsideBatch` 输出段：
  1. `state.pollPending()` 全部经 `enqueueOutputLine` 入队（kind → cursor 工厂的 switch 与旧
     drain 逐字对应）；
  2. 队列为空时才 `takeCompleteStreamingLines(300)` 入队（时序上更早的未打完输出不被新流式行
     插队）；
  3. `drainQueuedOutput(300)` 严格消费。
- 计划正文（`printPlan`）：逐逻辑行入队；模态侦测分支内紧跟一次
  `drainQueuedOutput(300 - batchRowsUsed)`——保持「侦测到计划的本 tick 就开始下沉正文」的旧
  语义（`CodeTuiViewPlanTest.planBodyGoesToScrollbackOneLinePerPhysicalLine` 钉着），同时与本批
  前段共用 300 行预算，单批上限仍是硬上限；超出的行留队列等下一批。
- `/clear`：`resetForNewSession()` 之后 `outputQueue.clear()`——严格分批后大输出可能还压在队列
  里没打完，与 pending 同语义一并丢弃（不留旧会话内容漏进新屏）。
- **折行移进 cursor**：构造器里的 `recording` sink 不再做出口折行/计数，只做留底
  （`scrollTail`）；游标出口产出的每条物理行本就 ≤ 终端宽。resize 重放（`replayAfterResize`）
  仍按当时宽度重新折 `scrollTail` 里的 Text——「留底存折行前内容、重放重新折」语义保留
  （存的是 Text，宽度信息无损）。
- **InlineRenderBatch 每批一次不变**：`drain()` 的 try-with-resources 结构原样保留，批次粒度
  未变（每个 66ms drain 一次 open/close），没有每行一批。

### staging 有界性证明（单个超大项的内存/CPU 如何被限制）

设一条逻辑输出展开后共 P 个物理行（P 可为几万）：

1. **物化上界与 P 无关**：任一时刻，队列 + 活跃游标物化的物理行 ≤
   「一条逻辑行折行后的段数」。`BlockCursor.next()` 的循环体是
   `while (pending.isEmpty() && at < logicals.length) renderInto(logicals[at++], pending)`——
   `pending` 非空即停，即每次最多渲染**一条**逻辑行；该逻辑行本身已完整存在于内存
   （`OutputLine.text` / streaming String），其折行展开与它同阶（O(len/width) 个段、每段
   O(width) 字符）。渲染器常驻状态为 O(1)（md 三字段 / diff 的 `at`+`inBlock`）。
2. **队列不物化**：pending 项只存 lambda（捕获 OutputLine 引用，O(1)）；流式项只存 String 引用
   列表（且同时最多一个流式 entry——只有队列空时才取新一批）；diff entry 的 `List<DiffLine>`
   在工厂调用时构建一次——其规模受 DiffRenderer 既有上限约束（LCS_MAX=800、BODY_CAP=80），
   是 O(一个工具入参) 而非 O(P)。
3. **CPU 按批切片**：`drain` 每写一行检查一次 nanoTime 预算（12ms）。单行渲染是一段有界工作
   （一条逻辑行 + O(1) 状态推进），预算粒度即足够细；时间耗尽立即返回事件循环，批间按键/
   粘贴/resize 可被处理。唯一的批前一次性成本（diff 展开）受 LCS_MAX/BODY_CAP 上限约束，
   且每条 diff 输出只发生一次（不随批数放大）。

即：**把「O(P) 的整段展开 + 整段 println」改为「每批 O(300) 行的流式产出」，内存与单批 CPU
都与 P 解耦**——这正是旧实现「PTY 限流修好了、UI 线程仍在 staging 被独占」的对症解。

## TDD 过程

### RED（先写测试、确认失败）

加强 `DrainBurstCapTest`：删 `SLACK=200`、全部断言 `<= 300`；新增
`singleHugeAssistantOutput_isStrictlyCapped`（单条 ASSISTANT 20000 行）、
`singleHugeDiff_isStrictlyCapped`（单 TOOL_START diff 折行 ~482 物理行）、
`singleNoNewlineHugeLine_isStrictlyCapped`（60k 列无换行单行 → ~770 物理行）、多批完整性断言
（`drainAll` 后逐行比对顺序、末行 TRUNCATED 概括）；新建 `StrictOutputFairnessTest`
（fake 事件队列：OutputBatch / KeyPress 交错）。

红灯确认（旧实现）：

```
DrainBurstCapTest.singleHugeDiff_isStrictlyCapped: 实际 482 > 300
DrainBurstCapTest.singleNoNewlineHugeLine_isStrictlyCapped: 实际 770 > 300
StrictOutputFairnessTest.keyPressBetweenBatches_...: 前置失败（一次 OutputBatch 后 1000/1000 全部下沉——
                                                     单条原子输出把整个 drain 占满，按键无批间空隙）
```

（其余既有用例在旧实现下本就 ≤300+slack，红灯集中在新增的「单个大输出」用例与公平性场景——
正是 brief 指出的缺陷面。）

### GREEN 过程中的两个真实 bug（都被新测试当场抓住）

1. **diff 游标段丢失**：`next()` 先渲染新 DiffLine 再 `pollFirst`，导致最后一条 diff 行的折行段
   尚在缓冲时 `at >= lines.size()` 分支已返回 null → `PhysicalOutputQueue.dropActive()` 把整个
   entry 当耗尽丢弃（大 diff 只打了一半、无 TRUNCATED 行）。修法：先排空缓冲段再渲染下一条。
   （`singleHugeDiff_isStrictlyCapped` 的「末行应为截断概括」断言抓住。）
2. **时间预算饿死首批**：首行前的 diff 工厂成本计入预算后，首批只出 1–2 行（12ms 被一次性
   展开吃掉），极端场景排空速度骤降。修法：时间检查从第 2 行起。
   （`StrictOutputFairnessTest` 排空容量断言抓住。）

### GREEN（最终测试结果）

命令（brief Step 2/Step 4 原样）：

```bash
mvn -pl springai-code-tui -am \
  -Dtest=DrainBurstCapTest,StrictOutputFairnessTest,ScrollbackPrinterTest,DiffRendererTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

```
Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0 -- ScrollbackPrinterTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- StrictOutputFairnessTest
Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- DiffRendererTest
Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- DrainBurstCapTest
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

全模块回归（设计 §15.6）：

```bash
mvn -pl springai-code-tui -am test
```

```
Tests run: 1749, Failures: 0, Errors: 0, Skipped: 10   （10 个为既有 skip，与本任务无关）
springai-tamboui-inline-patch ... SUCCESS
springai-code-tui .............. SUCCESS   [01:05 min]
BUILD SUCCESS
```

（过程中全量曾出现 1 例失败：`CodeTuiViewPlanTest.planBodyGoesToScrollback...`——计划正文入队后
  未在本 tick 消费。已按「与旧语义一致」修复：模态侦测分支内共享批预算立即消费。修复后全绿。）

## commit

`a9df35a`（worktree `refactor/event-driven-ui`，基于 `a5891be`）

## 自审

- brief 的接口签名对照：`OutputCursor.hasNext/next`、`PhysicalOutputQueue` 的
  `PhysicalLine(String plain, Text styled)` + 两个静态工厂、`BatchResult(int rowsWritten, boolean
  remaining, boolean timeBudgetExhausted)`、`enqueue`/`enqueueStreamingLines`/`drain(int,long)`/
  `isEmpty`/`clear` 全部按 brief 落地；`enqueue` 的参数从
  `ConversationState.OutputLine` 最小偏差为 `Function<Void, OutputCursor>`（工厂），因为
  「printer 产 cursor 工厂、保留渲染状态」正是 brief 第 35 行的要求——把 kind→工厂的映射放在
  View（原 drain 的 switch 位置），printer 保持纯渲染职责。`drain` 增加 `PhysicalSink` 出口参数
  （brief 未列）：这是让「留底 + 终端写」成为唯一出口所必需的最小偏差，避免队列绕过 scrollTail。
- 不删/弱化既有断言：`DrainBurstCapTest` 原有用例全部保留（只把 `CAP+SLACK` 收紧为 `CAP`，
  这正是本任务目标）；`ScrollbackPrinterTest`/`DiffRendererTest`/全部 `CodeTuiView*Test`
  1749 例零改动通过。
- 驱动方式未动：66ms `scheduleRepeating` 与 `tickForTest` 原样；Task 7 才删周期、接
  continuation 语义（本任务后 continuation 只需「批后若 `!outputQueue.isEmpty()` 安排下一批」，
  队列已把可续消费准备好）。
- InlineRenderBatch 每批一次 ✓（结构未动）；活跃 cursor 队头 ✓；流式批量取（不逐行 enqueue）✓。
- 线程纪律：`PhysicalOutputQueue` 只在渲染线程使用，与 drain 既有纪律一致，无新增共享状态。

## concerns

1. **时间预算常量（12ms）未经实测标定**：单批最坏 ≈ 300 行渲染 + println。正常行渲染 ~µs 级，
   300 行 << 12ms，预算几乎总由行数先到；它主要防「单行渲染异常贵」（超长行 md + 高亮）与
   慢机器。建议真实 Terminal.app 验收（§16）时顺带观察输出期间的输入延迟。
2. **批间公平性测试的判别力**：`keyPressBetweenBatches` 在旧实现下失败依赖「单条输出原子展开
   超过一批」这一事实（旧实现里多批小输出之间按键本也能插进——它同样按 300 行收手）。真正的
   判别用例是 `singleNoNewlineHugeLine`/`singleHugeDiff` 这类单条超限场景；公平性测试更多是
   把「批间可插入」钉成回归契约。已在测试注释里写明场景设计意图。
3. **pending 全量入队的队列长度**：每 tick 把 state.pending 全部转为 entry（不按预算截断）。
   entry 是 O(1) lambda，但极端积压下（如 5000 条 INFO 未消费）队列有 5000 个 entry——内存
   可忽略，但 Task 7 做 continuation 时应保持「entry 只在队头展开」的惰性，不要为了 throughput
   改成预展开。
4. **welcome() 仍走一次性 println**（不经过队列）：11 行、一次性，风险可忽略；但严格意义上它
   不受 300 行预算约束。若 Task 7 要求「所有输出过队列」，只需把 welcome 的行序列包成 cursor
   入队即可。
5. **PTY 冒烟（§16.1）与真实 Terminal.app（§16.2）未在本任务执行**：代码层回归已全绿；终端层
   验收按设计留给后续任务，本报告不声称已验证终端崩溃根治。

---

# Task 5 fix round 实施报告（审查 I-1 / I-2 / I-3）

## 状态

DONE（提交 `a66b13e`，9 files，+999/−132）

## 修改文件

- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/SegmentedWrap.java`（可续折行）
- Modify: `ScrollbackPrinter.java`（游标重写为段级推进 + raw 原文；移除死的 wrap 注入接缝）
- Modify: `output/PhysicalOutputQueue.java`（PhysicalLine.raw、PhysicalSink raw 出口、绝对 deadline、工厂成本契约）
- Modify: `output/OutputCursor.java`（契约改为段级有界 + 如实标注例外）
- Modify: `CodeTuiView.java`（留底记原文 + 引用去重；per-tick 共享 deadline；pending 有界转入）
- Create 测试: `SegmentedWrapTest`、`ScrollTailRecordingTest`、`DrainBudgetSharingTest`
- Modify 测试: `ScrollbackPrinterTest`（+2 例：lineCursor/assistantCursor 的段级推进与 raw）

## I-1：单条超长无换行逻辑行的「整行段一次物化」突刺

**方案选择：a)（可续折行）——String 与 Text 两条路径都做，不只 String。**

理由：审查给的方向 a 是「彻底消除整行物化」，b 是「如实声明上界」。选 a 的决定性原因是
方案 b 的「按物理行数设内部上界再分段惰性推进」<b>本身就是方案 a 的实现</b>——一旦要写
「超过阈值时按宽度分段推进」，段推进器就已经存在了，阈值只是多余的分支。而「Text 渐进折行
实现代价过高」的前提不成立：TextWrap 的折行本来就是逐 span、逐字符推进的循环，把它从
「跑完整行返回 List」改成「保存推进状态、每次吐一段」是机械重构（当前 span 引用 + 剩余
内容 + 后续 span 下标 + 当前行已用宽度，O(1) 状态），不需要触碰渲染语义。

实现：

- **`SegmentedWrap`**（新）：两个段推进器。
  - `Plain(source, width)`：纯字符串，`nextSegment()` 用 `CharWidth.substringByWidth` 逐段截取
    （与 `CodeTuiView.wrapSegments` 同一循环），状态只有剩余字符串引用；
  - `Styled(spans, width)`：span 行，逐 span 推进、样式跨拆分点保留（与 `TextWrap.wrapLine`
    同一算法），状态 O(1)。
  - **一致性由 `SegmentedWrapTest` 钉死**：同一输入下逐段产出序列与 `TextWrap.wrap` /
    `wrapSegments` <b>逐一相等</b>（含空行、宽字符、跨 span 拆分、60k 长行；宽度 1..5000 扫过）。
    两处分家 = 「打出去的行」与「留底重放的行」对不上，测试会当场红。
- **`ScrollbackPrinter` 游标重写**：`BlockCursor`（整行段缓冲）→ `PlainLineCursor`（String 路径：
  userBlock/lineCursor）+ `MdLineCursor`（markdown 路径：assistant/流式）+ diff 游标内联段推进。
  每次 `next()` 恰好产出<b>一个</b>物理段。staging 上界从「一条逻辑行的全部段」降到
  「正在产出的那一个段」——与逻辑行长度、整条输出总行数都无关。
- **`OutputCursor` 契约更新**：staging 上界改为「当前正在产出的一个物理段 + O(1) 推进状态」，
  并<b>如实标注已知例外</b>——cursor 工厂的一次性成本（diff 读文件+LCS，O(一个工具入参)，
  受 LCS_MAX/BODY_CAP 封顶）无法按段切片、发生在第一段之前、时间预算之外；每条 diff 输出
  只付一次（`PhysicalOutputQueue` 类注释同款声明）。

回归钉：`ScrollbackPrinterTest.lineCursor_overlongLine_producesOneSegmentPerNext_andCarriesRawLine`
（60k INFO：第一次 next() 立即返回首段、751 段拼回一字不差、每段 ≤80）；
`assistantCursor_overlongLine_producesSegmentsWithPreWrapRawText`（Text 路径同理 + raw 宽度=整行宽）。

## I-2：scrollTail 留底语义恢复「存折行前内容」

**方案选择：恢复原文留底（优先项），不是声明有损。**

实现：

- `PhysicalLine` 增加 `raw` 字段（该段所属<b>逻辑行折行前</b>的原文；同一条逻辑行的所有段共享
  同一引用；自包含行如欢迎横幅为 null）+ `PhysicalLine.of(plain, styled, raw)`；
- `PhysicalSink` 两个出口方法都透传 raw；
- 各 cursor 在逻辑行开始时构造一次 raw：lineCursor=带样式整行 Text（样式保真）、
  userBlock=整行用户块（缩进+底色+补白）、assistant/流式=整行渲染（含缩进）、
  diff=整条 diff 行渲染（行号列+底色）、diff 回退=带样式摘要行；
- `CodeTuiView.queueSink`：`record(raw != null ? raw : line)`——留底记原文；
- **`record` 按 raw 引用去重**（`==`，非 equals）：一条 60k 长行的 751 个段只占 1 条留底
  （不去重的话配额照样被 751 条重复原文吃穿——这是实现时当场发现、`ScrollTailRecordingTest`
  第一轮红灯抓出来的）；/clear 清留底时一并复位去重指针。

语义恢复验证（`ScrollTailRecordingTest`，从视图外部反射读留底）：
- 60k 无换行 INFO：留底 ≤3 条（逻辑行 1 条 + 回合边界空行），非 ~751 条；
- 40 条短 INFO 占 40 条留底配额（非被折行段稀释）；
- 变宽重放回流：5000 列下 60k 长行重折成 ~13 段（非恒 751 段永不合并）；重放到 100_000 列时
  整条逻辑行回流成 1 个物理行——「内容无损、重排无损」两个维度都钉住。

顺带修正：printer 构造器的 `wrap` 注入参数移除（生产两处调用本就都传同一个静态实现，
接缝已死；移除后 `ScrollbackPrinterTest` 的注入点同步简化，折行语义由 printer 内部保证）。

## I-3：单 tick 预算共享 + pending 有界转入

- **共享 deadline**：`drainInsideBatch` 开头计算一次 `batchDeadlineNanos = nanoTime()+12ms`，
  输出段与计划正文段的 `drainQueuedOutput` 都传这同一个<b>绝对</b>时刻；
  `PhysicalOutputQueue.drain(maxRows, deadlineNanos, sink)` 改收绝对 deadline（≤0=不限时）。
  单 tick 最坏从 2×12ms 回到 1×12ms。
- **pending 有界转入**：`MAX_PENDING_INTAKE_PER_TICK = 600`（≈2× 物理行预算），超出留在
  `state.pending` 等后续 tick——顺序不变（pending 本身有序、队列 FIFO）、不丢内容
  （消费完队头自然轮到）。20 000 条积压不再一个 tick 里做 20 000 次 pollPending 的同步循环。
- **12ms 常量**：`MAX_DRAIN_NANOS` javadoc 明确标注「<b>未做实测标定</b>，待 Terminal.app 实机
  验收（§16）以输出期间按键延迟回标」。
- **工厂成本契约**：见 I-1 的如实声明（无法分段的一次性成本，写进 `PhysicalOutputQueue` 与
  `OutputCursor` 的契约注释，不藏）。

回归钉：`DrainBudgetSharingTest`（4 例）——计划模态 tick 内两段的 deadline 必须是同一绝对
时刻（通过 `drainDeadlinesObservedForTest` 观测点断言）；普通 tick 只有一段（基线）；
20 000 条 pending 单 tick 转入 ≤600 且最终一行不丢、顺序不乱（5_000 tick 内排空）；
50 条正常体量不被节流。

## 测试

目标套件（brief Step 4 命令 + 三个新测试类）：

```bash
mvn -pl springai-code-tui -am \
  -Dtest=DrainBurstCapTest,StrictOutputFairnessTest,ScrollbackPrinterTest,DiffRendererTest,SegmentedWrapTest,ScrollTailRecordingTest,DrainBudgetSharingTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

```
Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- ScrollbackPrinterTest（+2 fix round 例）
Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- DrainBudgetSharingTest（新）
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- ScrollTailRecordingTest（新）
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- StrictOutputFairnessTest
Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0 -- SegmentedWrapTest（新）
Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- DiffRendererTest
Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- DrainBurstCapTest
Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

RED 确认（新测试先于对应实现运行）：
- `ScrollTailRecordingTest` 两例红：留底 400 条（=折后段，I-2 缺陷的真实形态）；
- `SegmentedWrap`/一致性、共享 deadline 钉在实现落地后全绿（I-1/I-3 的行为面与实现同轮落地，
  钉住防回归）。

全模块回归（设计 §15.6）：

```bash
mvn -pl springai-code-tui -am test
```

```
Tests run: 1764, Failures: 0, Errors: 0, Skipped: 10（既有 skip，与本任务无关）
springai-agentdemo ... SUCCESS / springai-tamboui-inline-patch ... SUCCESS / springai-code-tui ... SUCCESS [01:07 min]
BUILD SUCCESS
```

（Task 5 首轮为 1749 例；+15 = 本轮新增测试。既有断言零弱化：`DrainBurstCapTest` 8 例、
`ScrollbackPrinterTest` 原 7 例、`DiffRendererTest` 9 例、`StrictOutputFairnessTest` 3 例
全部原样通过。）

## commit

`a66b13e`（worktree `refactor/event-driven-ui`，基于 `f29a40c`）

## fix round concerns

1. **diff 工厂成本仍在预算外（如实声明，未消除）**：读文件 + LCS 是 O(一个工具入参) 的一次性
   工作，受 DiffRenderer 的 LCS_MAX=800/BODY_CAP=80 封顶、每条 diff 输出只付一次；但它在第一段
   之前发生，无法按行切片。彻底消除需把 diff 展开本身做成增量游标（LCS 的前缀性质可用），量级
   明显更大，未在本轮做。已写进 `PhysicalOutputQueue`/`OutputCursor` 契约注释与上方 I-1 节。
2. **`Styled` 与 `TextWrap` 的一致性靠测试钉而非共享实现**：两处是同一算法的两份代码（一份
   一次性、一份增量）。`SegmentedWrapTest` 用 60+ 组输入×7 档宽度逐一比对；后续若改
   `TextWrap` 的折行规则（如引入断词），记得同步 `SegmentedWrap`（或届时把 TextWrap 本身
   增量化、删掉一份）。
3. **留底 raw 的内存形态**：一条逻辑行占一条 raw（原文引用或整行 Text）。assistant 的 raw 是
   新建的 Text（`indented(renderFinalized(...))`），与打出去的段内容同源、不额外复制大字符串；
   400 条配额下总内存 ≤400 行原文，与旧行为同阶。
4. **`lastRecordedRaw` 去重只看相邻**：同一 raw 的段在留底里必然相邻（游标按序产出），相邻判等
   足够；跨逻辑行穿插的场景不存在（一条逻辑行的段连续产出）。
5. **12ms 仍未标定**（I-3 只是把「单 tick 最坏 2×」修成 1×，量值本身待实机回标，见常量注释）。

---

# Task 5 fix round 2 实施报告（复审 N-1 + Minor）

## 状态

DONE（提交 `530e1fc`，3 files，+166/−21）

## 对上一轮限流中断残留工作的取舍

worktree 里遗留三个文件的未提交改动（ScrollbackPrinter / CodeTuiView / ScrollbackPrinterTest），
通读后的取舍：

- **核心修复保留并完善**：`MdLineCursor` 改为「未缩进 `renderFinalized` spans 按 `innerWidth()`
  折行 + 每段前置 `Span.raw(INDENT)`；raw 仍存 `indented(...)` 整行」——与复审要求 1 完全一致，
  逻辑推演（`SegmentedWrap.Styled` 空行补空段语义、raw 引用共享、样式保留）与实现全部核对无误。
  javadoc 声明「与一次性方法逐字同源」成立（等价性测试钉住），予以保留。
- **重写 ①（残留测试的 `.repeat(3)` 优先级 bug）**：
  `assistantCursor_manySegmentChineseBody_everySegmentIndented` 里
  `"A" + "B".repeat(3)` 只重复了后半句（整段 ~220 列，inner 78 下仅折 3 段），`>= 5` 段前置
  断言必然假红；注释「~126 列 ×3」暴露原意是整句 ×3。改为整句括号后 repeat（64 CJK 字符 =
  128 列 ×3 = 384 列 → 5 段）。
- **重写 ②（既有断言按缺陷语义同步）**：
  `assistantCursor_overlongLine_producesSegmentsWithPreWrapRawText` 的 `<= 78` 段宽断言是
  fix round 1 按「先缩进后折行」写的——段宽上限=inner 正是 N-1 列为缺陷的项之一（正确上限 =
  终端宽 80 = 缩进 2 + 内容 ≤78）。改为 `<= 80` 并注明缘由；同用例补上 s2 续段
  `startsWith("  ")` 断言（加强）。其 `contains` 拼回断言在正确语义下必假（wrap-then-indent
  后拆分点移到内容流上 18y/42y，直接拼物理段把第二段缩进夹进 y 流）——改为**逐段去缩进后精确
  相等**（比原 contains 更强）。
- **Minor 修正保留**：`batchDeadlineNanos` javadoc 陈旧的「Long.MAX_VALUE=测试态无预算」改为
  与 `PhysicalOutputQueue.drain` 实际契约一致（绝对 nanoTime 域；≤0 视为不限时；生产无
  Long.MAX_VALUE 用法，grep 复核）。
- javadoc 中「每段宽度上限变成 inner(78)+缩进语义混乱」语病修顺（②段宽上限从终端宽 80 错位成
  inner 78，折行预算里混入排版缩进）。

## N-1 修复摘要（修复要求 1–3）

`MdLineCursor.next()`（原 ~:436-440 的「先 `indented()` 后折行」）改为：

```java
List<Span> rendered = md.renderFinalized(logicals[at++]);   // 状态推进在渲染时发生（不变）
raw = indented(rendered);                                  // 留底原文（折行前整行，含缩进；I-2 语义不变）
segs = SegmentedWrap.styled(rendered, innerWidth());        // 折行源 = 未缩进渲染
...
indentedSeg.add(Span.raw(INDENT)); indentedSeg.addAll(piece);   // 每一段（含续段）前置缩进
```

- **续段缩进**：缩进不再吃折行预算，折出几段每段都带 `"  "` 前缀——悬挂缩进语义恢复，与旧
  `printWrapped`（wrap-then-indent）逐字一致；留底重放（raw 存整行、重放重折）同步恢复。
- **段宽上限**：inner(78)+缩进(2)=80=终端宽，不再是 inner(78)。
- **修复要求 3（javadoc）**：`MdLineCursor` 类注释写明「折行源是未缩进的渲染结果 + 每段前置
  INDENT + 与 printWrapped 逐字一致（由 ScrollbackPrinterTest 钉住）」；cursor 工厂区注释与
  `printWrapped` javadoc 同步。「逐字同源」声明现在真实成立。

## 回归钉（修复要求 2：每一段、不只首段）

`ScrollbackPrinterTest` 新增 4 例 + 既有 1 例加强（13 例全绿）：

- `assistantCursor_everyWrappedSegmentCarriesHangingIndent_withinTerminalWidth`：
  ~108 列中文正文（生产主路径形态）折 2 段——**每段** `startsWith("  ")` + 每段宽 ≤80 +
  去缩进拼回精确等于原文；
- `assistantCursor_manySegmentChineseBody_everySegmentIndented`：384 列中文正文折 5 段，
  「每一段」断言从 2 段扩到多段；
- `assistantCursor_mixedWidthAndStreamingSegmentsKeepHangingIndent`：中英混合 +
  `**bold**` 跨样式拆分 + 窄终端（≥3 段）；流式完整行（同一 `MdLineCursor`）钉 200 列 = 3 段、
  每段带缩进；
- `assistantCursor_wrapThenIndent_matchesOneShotPrintWrappedExactly`：同一输入下 cursor 与
  一次性 `assistant()`（printWrapped）物理行序列**逐一相等**（短行/宽窄混排/长中文/空行 4 组）——
  「逐字同源」的直接钉子；
- 既有 `assistantCursor_overlongLine_producesSegmentsWithPreWrapRawText` 加强：s2 续段
  `startsWith("  ")` + `<= 80`（原 `<= 78` 按缺陷语义写）+ 去缩进精确拼回（原 contains 升级）。

**RED 确认**（主实现 stash 回旧版、保留新测试）：5 例红，失败信息即 N-1 症状——
`第 1 段（含折行续段）必须带悬挂缩进，实际：，不能从第零列开始。`、
`实际：yyyyyy…（顶格续段）`；等价性用例的 diff 直接展示两路径分家
（一次性 `  yyy…` 带缩进 vs 旧 cursor `yyy…` 顶格）。恢复修复实现后全绿。

## 测试命令与结果

目标套件（brief 指定 7 类）：

```bash
mvn -pl springai-code-tui -am \
  -Dtest=DrainBurstCapTest,StrictOutputFairnessTest,ScrollbackPrinterTest,DiffRendererTest,SegmentedWrapTest,ScrollTailRecordingTest,DrainBudgetSharingTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

```
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- ScrollbackPrinterTest（+4 新例，1 例加强）
Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- DrainBurstCapTest
Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0 -- SegmentedWrapTest
Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- DiffRendererTest
Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- DrainBudgetSharingTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- ScrollTailRecordingTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- StrictOutputFairnessTest
Tests run: 47, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

全模块回归（设计 §15.6）：`mvn -pl springai-code-tui -am test`

```
Tests run: 1768, Failures: 0, Errors: 0, Skipped: 10（既有 skip，与本任务无关）
springai-agentdemo SUCCESS / springai-tamboui-inline-patch SUCCESS / springai-code-tui SUCCESS [01:05 min]
BUILD SUCCESS
```

（上轮 1764 + 净新增 4 = 1768 ✓；既有断言零弱化——`122` raw 宽度、`assertSame` 引用共享、
751 段完整性等全部原样通过，两处改动均为加强。）

## commit

`530e1fc`（worktree `refactor/event-driven-ui`，基于 `9e909d8`）

## fix round 2 concerns

1. **`deindent` 辅助假定段前缀恰为 2 空格**：assistant 物理段的 INDENT 是 printer 私有常量
  `"  "`，测试无法引用（包私有可反射，但直接字面量更直白）；若日后 INDENT 变更需同步测试。
   测试正文均不含连续空格，`replace("  ", "")` 拼回不会被正文空格干扰。
2. **`printWrapped` 与 `MdLineCursor` 仍是两份实现**（一份 `TextWrap` 一次性、一份
   `SegmentedWrap.Styled` 增量）——与上轮 concern 2 同源；本轮新增的等价性钉子
   （4 组输入逐一相等）把分家窗口从「折行算法层」缩到「缩进时机 + 折行源层」，若后续改
   `printWrapped` 的缩进语义该测试会当场红。
3. 上轮 concern 1/3/5（diff 工厂成本预算外、raw 内存形态、12ms 未标定）本轮未触碰，仍然成立。



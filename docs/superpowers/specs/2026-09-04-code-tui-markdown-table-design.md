# code-tui Markdown 表格渲染 设计

日期：2026-09-04
状态：**v2**（第 1 轮 subagent 评审的全部结论已改入，见文末评审记录；计划做 3 轮）
参考实现：Claude Code 的表格渲染（表头加粗 + 一条分隔线，不画竖线）

## 0. 摘要

code-tui 的 markdown 渲染器**没有表格分支**，表格行原样打印。因此模型吐出的表格在终端里
是否对齐，完全取决于模型有没有把每个格子补到等宽——而它做不到：GFM 不要求竖线对齐，
且模型按**字符数**补齐、终端按**显示宽度**排版（CJK 占 2 列）。结果是用户实报的
「表格显示就乱了」。

本设计给渲染器补上**表格块渲染**：攒够整块后按显示宽度算列宽、重排成轻量表
（表头加粗 + 一条 `─` 分隔线 + 空格对齐，不画竖线），超宽时削列 + 格内折行、不丢字。
核心约束是 scrollback **只能追加、印出去改不了**，所以列宽必须在打印前算完，
表格块只能先缓冲、块结束才输出——本设计的主要复杂度都在「什么时候算块结束」。

## 1. 背景：现状与实测证据

### 1.1 现状：整个模块没有表格处理

`MarkdownRenderer`（`springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/MarkdownRenderer.java`）
只有五个分支：代码围栏、标题、引用、列表、内联样式。表格行掉到最后的
`renderInline()`（同文件 `:145`）被原样打印。全模块 main + test 内 grep
`startsWith("|")` / `isTable` / 「表格」均零命中——不是实现有 bug，是这个功能不存在。

生产渲染路径：`ASSISTANT` 逻辑行 → `ScrollbackPrinter.assistantCursor`（`:266`）
或流式路径 `streamingLinesCursor`（`:271`）→ 每条逻辑行 `md.renderFinalized` →
`SegmentedWrap.styled(..., innerWidth())` 按显示宽度折行 → 每段前置 2 空格缩进 →
一段一个 `println`（一个 `println` = 一个物理行）。折行本身按显示宽度算、中文按 2 列，
是**正确**的（`TextWrap` / `SegmentedWrap`）。

### 1.2 实测（探针走真实渲染路径）

探针表原文（4 行，显示宽度 31 / 31 / 86 / 84）：

```
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| codetui.syncOutput | String | auto | 控制是否使用终端同步输出扩展，取值 never/auto |
| codetui.hardwareCursor | String | auto | 控制硬件光标可见性，IME 路径需要 always |
```

喂给 `assistantCursor` 并手动消费 `PhysicalLine`，落到 scrollback 的物理行：

| 终端宽 | 产出 | 各行显示宽度（含 2 空格缩进） | 结果 |
|--------|------|------------------------------|------|
| 120 列 | 4 行 | 33 / 33 / 88 / 86 | 表头与分隔行 33 列、数据行 88 列，竖线上下不可能对齐 |
| 80 列 | 6 行 | 33 / 33 / 80 / 10 / 80 / 8 | 数据行按显示宽度硬折，切在格子中间：`… 取值 neve` + 续行 `r/auto \|` |

用户截图的旁证：纯 ASCII 表（模型恰好补齐了）完全对齐、box-drawing 表（宽度自洽）
正常，只有中英混排 + 长数据行的表乱——三者共同证明渲染器只是照抄，宽度计算无 bug。

### 1.3 目标 / 非目标

**目标**

- 模型吐出的 markdown 表格在终端里列对齐，中英混排也对齐（按显示宽度，CJK 2 列）
- 超过终端宽度时不切在格子中间、不丢字
- 流式、`-c` 回放、一次性三条路径产出一致
- 不破坏既有纪律：一个 `println` = 一个物理行；drain 分批预算不被单块输出独占

**非目标**

- 无外侧竖线的 GFM 写法（`a | b`）——原样输出，不识别（见 3.3 的边界理由）
- 单元格内 `<br>` / 块级内容（列表、代码块）
- 两张表格中间没有空行时会被并成一张（第二张的表头/分隔行降级成数据行）——与 GFM 一致，不特殊处理
- resize 后重新排版（见第 4 节已知局限）
- 表格之外的 markdown 行为改动

## 2. 候选方案与取舍

| 方案 | 做法 | 取舍 | 裁决 |
|------|------|------|------|
| A. 攒整块再输出 | 表头+分隔行到位就开始缓冲，块结束一次性排好输出 | 列宽按真实内容算，最优且不丢字；代价是表格「憋」到整块收完才出现（实测通常 < 1s），且需要五条 flush 触发点 + 一条豁免（§3.4） | **采纳** |
| B. 边流边排，列宽拍死 | 表头到位就把终端宽按列平分，后续行流到就按该宽度填、格内折行 | 不憋、不需要 flush 钩子；但列宽与内容无关——`String`/`auto` 这种短列白占 20+ 列，长列反被挤得频繁折行 | 否 |
| C. 只修超宽 | 不做对齐，只让折行表格感知（不在格子中间切） | 改动最小、零架构风险；但 1.2 里「表头 31 列 / 数据行 86 列」的主要症状没治 | 否 |

样式在 A 之下另有三选（轻量表 / box-drawing 网格 / 对齐的管线表），解析与列宽计算
完全共用，只是输出长相不同。选**轻量表**：4 列时只多吃 6 列宽（另两种多吃 13 列），
窄终端 + 中文内容下最不容易触发削列，且与本项目「仿 Claude Code」的既有风格一致。

## 3. 详细设计

### 3.1 输出规格（轻量表）

**宽度口径（先定死，后面全按它算）**：`inner` = 终端宽 − 缩进宽，即现有
`ScrollbackPrinter.innerWidth()`（终端宽 − 2）。排版接口收的 `width` 参数**就是 inner**，
调用方传 `innerWidth()`；`MarkdownTable` **自己不加缩进**——缩进是 `MdLineCursor` 对每一段
前置的（`ScrollbackPrinter.java:456-459`），排版里再加一层就是 4 空格。
**一次 flush 只读一次** `terminalWidth.getAsInt()`：`feed` 与 `flush` 各读一次，
会在 resize 瞬间拿到两个宽度、排出半张错位的表。

**排版规则**

- **列宽** = 该列所有格子（含表头）显示宽度的最大值，中文按 2 列；整列全空时按最小宽度 4
- **列间**固定 2 空格；不画竖线；**行尾不补白**（补白不可见，只会撑宽）
- **表格总宽** = `Σ列宽 + 2 × (列数 − 1)`；单列时退化为列宽本身
- **表头**加粗；表头下一条 `─` 分隔线长度 = **表格总宽**（不是 inner、不是表头行的实际宽度）
- **对齐**：分隔行的 `:---` / `:--:` / `---:` 认左 / 中 / 右；无冒号默认左
- **内联样式**：格子内的 `**粗**` / `` `代码` `` 照常渲染；列宽按 **spans 内容拼接后**测量，
  不是逐 span 相加——`CharWidth` 对 ZWJ 序列 / 组合字符按整簇算，拆开相加结果不同

**硬不变量**：排出来的每一行（未加缩进）显示宽度 **≤ inner**。`MdLineCursor` 仍会把每行
过一遍 `SegmentedWrap.styled(rendered, innerWidth())`（`ScrollbackPrinter.java:452`），
破了这条就被二次折行撕成两段、续段还再加一层缩进——「大部分行齐、个别行裂开」是最难看的形态。
测试必须按「产出物理段数 == 表格逻辑行数」钉（二次折行是 no-op），只断言「每行 ≤ 终端宽」
抓不到这个缺陷（假绿）。

**超宽处理**：表格总宽 > inner 时：

1. 反复削当前最宽的列（每次削 1 列）直到装下；**并列最宽时削索引小的那列**——确定性优先于对称，
   否则测试期望值无法写
2. 每列最小宽度 **4**（容得下 2 个 CJK 字符或 4 个 ASCII 字符）。表头所在列同样可被削到 4，
   表头文字按下面的规则折行；`─` 分隔线仍是一条，跟在表头的**最后一段**下面
3. 格子内容按列宽折成多行：**优先在空格处断**，单个词比列宽还长才按显示宽度硬切
   （不切半个宽字符）。CJK 无空格的格子必然落到硬切分支——设计如此，不是遗漏
4. 连每列 4 列都装不下（`4×列数 + 2×(列数−1) > inner`）→ **整块退回原样输出**，诚实降级

⚠ 格内折行是本仓的**第三套**折行语义（`SegmentedWrap` / `TextWrap` /
`CodeTuiView.wrapSegments` 全是按显示宽度硬切、没有空格感知）。`SegmentedWrap` 的 javadoc
专门警告过「两处实现分家 = 打出去的行与留底重放的行对不上」，所以格内折行必须复用同一套
宽度原语（`CharWidth`），并加一条交叉单测：同一输入在「无空格可断」时两者产出逐字相等。

80 列终端（inner = 78）下，§1.2 那张探针表排完是这样。列宽 22 / 6 / 6 / 38——「说明」列
自然宽 45，削到 38 才让总宽落到 78；`─` 是 **78** 个（= 表格总宽，加缩进后 80 = 终端宽，
正好压线不触发二次折行）。各行含缩进的显示宽度已逐行核过：46 / 80 / 76 / 52 / 74 / 48。

```
  参数                    类型    默认值  说明
  ──────────────────────────────────────────────────────────────────────────────
  codetui.syncOutput      String  auto    控制是否使用终端同步输出扩展，取值
                                          never/auto
  codetui.hardwareCursor  String  auto    控制硬件光标可见性，IME 路径需要
                                          always
```

### 3.2 `MarkdownTable`（新增，纯函数无状态）

`ui` 包新增一个类，只做解析 + 排版，不碰 IO 与状态，可独立单测：

- `static boolean looksLikeRow(String line)`——`stripLeading` 后以 `|` 开头
- `static boolean isSeparator(String line)`——单元格全由 `-` / `:` / 空格组成且至少一个 `-`
- `static List<Alignment> alignments(String separatorLine)`
- `static List<List<Span>> render(List<String> block, int inner)`——块原文 → 排好的若干行 spans

**契约：对任意输入不抛异常**（与 `MarkdownRenderer` / `DiffRenderer` 同）。理由不是洁癖：
`MdLineCursor.next()` 的 catch 返回 `null`（`ScrollbackPrinter.java:461-463`），而 `null` 在
队列语义里是「游标耗尽」→ `dropActive()`，所以一次异常会丢掉**整块 + 同游标里剩余最多
300 条逻辑行**。flush 内部再自兜一层：`render` 万一抛了，就把该块原样输出。

解析细节：

- 按**未转义**的 `|` 切分；`\|` 还原成字面 `|` 且不作分隔符
- 首尾的空单元格（行首行尾竖线产生的）丢弃
- 单元格**少于**表头 → 补空
- 单元格**多于**表头 → **多出来的并入最后一列**（用 ` | ` 拼回去），**不丢**。
  GFM 的默认行为是丢弃，但 GFM 是文档渲染器、本项目是终端渲染器，
  丢内容与 §1.3「不丢字」和 §5.3「拼回去一个字不丢」直接冲突。
  典型触发：格子里有带管道的行内代码，如 `` `ps aux | grep java` ``
- 「表头 + 分隔行、零数据行」是合法块（GFM 认），排成表头 + 一条 `─`
- 每个格子先过内联解析拿 spans，列宽按 spans 内容拼接后测量（见 §3.1）

**前置改动（可见性）**：`MarkdownRenderer.BOLD`（`:25`）、`DIM`（`:23`）、`renderInline`（`:149`）
和 `ScrollbackPrinter.INDENT`（`:51`）目前**都是 private**，同包新类也访问不到。
`renderInline` 不读实例字段，可直接提成 package-private static。这是实施计划的第 0 步，
不是「顺手就能复用」。

### 3.3 `MarkdownRenderer` 块状态机

在既有渲染器上加四个成员方法：

- `List<List<Span>> feed(String line, int inner)`——吃一条逻辑行，吐 0..N 条渲染好的行
- `List<List<Span>> flush(int inner)`——把缓冲排出来；空缓冲返回空列表（幂等）
- `boolean hasBuffered()`——缓冲里是否压着东西（候选态也算，见下）。`ScrollbackPrinter` 以
  `hasBufferedTable()` 转发给视图，视图不直接碰渲染器
- `reset()`（已有）**语义扩展为「连缓冲一起丢」**——它在 `userBlockCursor` 工厂里
  （`ScrollbackPrinter.java:232`，drain 时刻）调用；不丢的话上一回合的残表会在下一回合中途复活

`renderFinalized` / `renderPreview` 保持原样当**非表格行的原语**（现有 `MarkdownRendererTest`
不动）。但 `renderFinalized` 继续 public 就是绕过状态机的后门——加注释：**新调用点一律走 `feed`**，
生产路径只有 `feed` 与 `MdLineCursor` 可以碰它。一次性入口 `assistant(String)` /
`streamingLine(String)`（当前只有测试在用）改成 feed + 收尾 flush，与游标路径同源。

状态机放在 `MarkdownRenderer` 里而不是外面，理由是**围栏内的 `|` 不能当表格**，而围栏开合状态
只有它有。表格块状态与围栏状态同属「块上下文」，放一起才不会撕裂。宽度作为参数传入（不存字段），
排版本身无状态。

四个状态、`feed` 的转移（**降级**是正式状态，不是补丁）：

| 当前状态 | 输入行 | 动作 | 返回行数 |
|----------|--------|------|----------|
| 空闲 | 围栏内 / 非 `\|` 开头 | `renderFinalized` | 1 |
| 空闲 | `\|` 开头 | 押住当候选表头 | 0 |
| 候选 | 是分隔行 | 进块（候选行 + 分隔行入缓冲） | 0 |
| 候选 | 不是分隔行 | 吐候选行 → 回空闲 → **把当前行重新投喂一遍状态机** | 1 + 重投喂结果 |
| 块内 | `\|` 开头，未越上限 | 入缓冲 | 0 |
| 块内 | `\|` 开头，越上限（§3.5） | 已攒行**原样**吐出 → 转降级 | N |
| 块内 | 其它（含空行） | **对齐排出整块** → 回空闲 → 渲染当前行 | N+1 |
| 降级 | `\|` 开头 | 该行原样吐出 | 1 |
| 降级 | 其它 | 回空闲 → 渲染当前行 | 1 |

`flush(inner)` 按状态：空闲 → 空；**候选 → 该行按 `renderFinalized` 输出**（一句「`|` 表示管道」
不能被印成加粗表头 + 通栏 `─`）；块内 → 对齐排出；降级 → 回空闲、返回空。
`hasBuffered()` 在候选态**必须为 true**，否则那一行永久消失。

**「当前行重新投喂」不是可选项**。少了它，下面这段的整张表会被拆成原样输出——
第 1 行进候选，第 2 行（真表头）因「不是分隔行」被连带原样吐出，第 3 行（分隔行）
又被当成新候选：

```
| 开头的一句正文
| 参数 | 说明 |
|------|------|
| a    | b    |
```

重投喂的递归深度**恒为 1**：被重投喂的行只会落到「空闲」的两条分支（要么渲染、要么成为新候选），
不可能再次触发重投喂。实现上写成「循环一次」而不是真递归，避免有人以后改出无界递归。

**一行 lookahead 的代价**：正文里行首带竖线的句子会被延迟一行显示。只影响行首是 `|`
的行，且由 3.4 的兜底 flush 收口，不会永久卡住。这也是「只认行首竖线」的取舍来源——
若放宽到 GFM 的 `a | b`，任何含竖线的正文句子（如 `ps aux | grep java` 的说明文字）
都会被押住一行，误伤面大得多。

### 3.4 flush 触发点（本设计的主要风险面）

scrollback 只能追加，**五条**都必须有，漏一条就是内容消失或顺序错乱。判据统一成一句话：

> **模型流水线上的行意味着正文块已结束**——流里文本与工具调用是串行的，工具行之后模型不会
> 接着补同一张表的下半截。**UI 异步注入的通知行则可能落在正文块中间**，所以它们不 flush。

1. **块内来了非表格行**（含空行）→ 对齐排出，再吐这行。§3.3 状态机内部，天然满足。
2. **模型流水线上的行入队前**：`CodeTuiView.enqueueOutputLine`（`:516`）的 `USER` /
   `TOOL_START` 分支，以及 `default` 分支里的 `TOOL_OK` / `TOOL_FAIL` / `TODO` /
   `SUBAGENT_*` → 前置一个 flush cursor（对齐）。
   **必须走队列、不能直接 println**：`enqueueOutputLine` 执行在**入队**时刻，此刻它前面那些
   ASSISTANT 行还压在队列里没 drain，直接打会让表格插到**自己前面的正文行上面**去
   （方向是往前插，不是掉到后面）。
3. **豁免：`INFO` / `ERROR` 不 flush、不降级**，通知行照常打、允许它**越过**还在缓冲里的表格。
   这两类是 UI 异步注入的（`/context` 回合中可执行、MCP 就绪回调、`⏱ 后台任务已启动`、
   模态队满 ERROR），它们相对正文的位置本来就不确定；反过来若在这里 flush，就会出现
   「按只看过两行算出的列宽排好的半张表 + 原样的下半张」——两种排版拼接，比现状更难看。
   turn 级的 `onError`（`ConversationState.java:1002`）由第 4 条兜住，不靠这里。
4. **回合结束或 UI 为用户暂停**：条件 `(state.isIdle() || state.hasModal()) &&
   printer.hasBufferedTable()` → 入队 flush（对齐）。两处要点：
   - **检查点必须在本批主 drain 之后**（`drainQueuedOutput`，`CodeTuiView.java:881`），
     紧跟一次 `drainQueuedOutput(剩余预算)` 把它在**同一批**里打完（照 `:922` 计划正文那段的写法）。
     放在 `:875` 取流式行那一带（drain 之前）时，最后一批的时序是「pending 刚把表格尾行转入
     → 队列非空 → 闸门 false → drain 把它喂进缓冲」，而批尾 `outputRemaining` 三项全 false
     （`:977-1023`）、IDLE 下也没有动画帧（`:1049`），**不再排下一批**。实测过：表格要么靠
     `ctxUsageController.markDirty()` 的 500ms 防抖偶然救回（晚半秒），要么一直不出、
     直到用户按键。
   - `hasModal()`（`ConversationState.java:498`）这一半是给**权限 / 问询**用的：
     `PermissionCallback` 是最外层装饰器（见其类注释），审批请求早于 `onToolStarted`
     → 早于 `flushStreaming()`，面板弹出时表格还压在缓冲里，而此刻 `status` 不是 IDLE。
     模态期间批次不断（66ms 动画帧），所以这一条能收口。
5. **整篇 ASSISTANT 文档灌完时**：`printPlan`（`CodeTuiView.java:3232-3237`）逐行
   `enqueue(assistantCursor(line))`，**绕过 `enqueueOutputLine`**，第 2 条一条都不经过；
   而 `onPlanSubmitted`（`ConversationState.java:1087`）不改状态，此刻是 `RUNNING_TOOL`。
   计划正文以表格结尾（很常见）时，用户要在**看不见那张表**的情况下批准计划。
   收尾补一条 flush cursor，并把规则一般化：**任何往队列灌整篇 ASSISTANT 文档的地方，
   收尾必须 flush**（目前只有这一处）。
6. **`/clear`**（`CodeTuiView.java:2161` 一带）→ 丢缓冲。必须**同步**做在那一行旁边，
   不能塞进后面的 `runOnRenderThread` lambda——那段只在 runner 非空时跑，测试态走不到。

**不变量（写进测试）**：任何一批结束时，不允许
`outputRemaining == false && printer.hasBufferedTable() == true`。

**刻意拒绝的解法**：把 `hasBufferedTable()` 直接并进 `computeFollowUpFlags` 的
`outputRemaining` / `localWorkRemaining`。回合进行中缓冲长期非空，会让 ZERO 延迟的
continuation 与每圈 render 形成双线程满载空转——`CodeTuiView.java:1014-1021` 已经为这个坑
留过记录。pty 背压那条路不需要它：饱和时本条不产出，而 `ptyBackpressured()` 自己维持
`remaining`（`:984`），背压解除后的批次会接着做。

### 3.5 缓冲上限与降级

判定单位与时机说死：**每次往缓冲里塞行之后**判，按**原文**算——行数 > 200 或
原文字符数 > 64 K（不含样式，`String.length()` 近似即可）。触发后按 §3.3 的「块内 + 越上限」
转移：已攒行**原样**吐出、转降级态，该块剩余 `|` 行原样输出直到块结束。目的有两个：
内存有界，以及「憋」的时长有界（几千行的表格不该让界面停半秒以上）。

降级行仍带正常的 2 空格缩进（缩进是 `MdLineCursor` 加的，与降级无关）。

### 3.6 游标接线：`next()` 必须内部循环（会丢整批内容的雷）

**`PhysicalOutputQueue.drain` 从不调用 `hasNext()`**，它只认 `next()` 返回 `null` 作为耗尽信号，
拿到 null 就立刻 `dropActive()` 换下一项（`PhysicalOutputQueue.java:121-127`）；
`ScrollbackPrinter.run` 同样是 `break`（`:475-479`）。所以：

> **`feed` 返回空列表 ≠ 游标耗尽。** `MdLineCursor.next()` 必须
> `while (待吐队列为空 && 还有未消费的逻辑行) { 继续 feed }`，只有逻辑行**真正耗尽**时才返回 null。

照原稿「挂个待吐队列 + 修正 `hasNext()`」的字面写法实现，一批流式行里只要**第一条**是 `|` 开头，
`next()` 就返回 null → 整个游标被丢弃 → 后面最多 299 条逻辑行**永久消失**（既不在 scrollback
也不在留底）。流式一批上限是 `MAX_ROWS_PER_DRAIN`（300 条逻辑行，`CodeTuiView.java:876`）。
`hasNext()` 当然也要把待吐队列算进去，但那不是雷所在。

**留底原文**：每个物理行的 `PhysicalLine.raw` 必须是**各自独立的 Text 实例**——`record()`
按 `==` 去重（`CodeTuiView.java:1370-1377`），若实现复用同一个对象（比如缓存那条 `─` 分隔线、
或两行内容相同就复用），相邻那条会从 resize 留底里掉一条。

**如实声明（要写进代码 javadoc，不只是 spec）**：`OutputCursor` 的 staging 契约
（`ui/output/OutputCursor.java:12-30`）明确禁止「借机展开整条输出」，允许的上界是「当前正在
产出的那一个物理段」，唯一已声明的例外是 diff 游标的**工厂一次性成本**——而那发生在首段之前、
在时间预算之外（`PhysicalOutputQueue.java:27-31`）。表格与它**不是同一个模式**：排版发生在
**批中途某个 `next()` 内部**，缓冲**跨游标、跨批、跨 OutputLine 存活**且归属渲染器
（契约里说的「状态跨调用保持」指 O(1) 的围栏态，不含内容缓冲）。因此要在 `OutputCursor`、
`PhysicalOutputQueue`、`ScrollbackPrinter.md` 三处 javadoc 补第二条例外，量级由 §3.5 的
200 行 / 64 K 封顶。顺带一条如实数据：`drain` 的时间预算检查要 `written >= 2` 才生效
（`:133`），缓冲期间不写行，所以一批可能连喂几百条逻辑行才写出第一行。

### 3.7 流式期间的预览行

`renderPreview(tail)` 显示的是「在建残行」，属于活动区不是 scrollback。表格缓冲期间它继续
原样显示当前半截行。观感是：缓冲期间屏幕上只有当前这一行在动、上面不累积，整块收完后
表格一次性落下。这是「攒整块」的必然表现，已确认接受。同一行不会既进缓冲又在预览里出现
（预览是残行、`feed` 吃的是定稿行，两者互斥）。

## 4. 已知局限（诚实声明）

- **resize 后不重排**：留底原文（`PhysicalLine.raw`）是排好的整行 Text，resize 重放只会
  按新宽度**重折**、不会重新算列宽。窗口缩窄后滚回去看历史表格，列会错位。今天的行为
  也是错位（本来就没对齐），不算回归；重排需要把「块」而不是「行」作为留底单位，属另一件事。
- **宽度 oracle 本身是近似**：`CharWidth` 把 VS16 文字型 emoji（如 `✔️` = U+2714 U+FE0F）
  算成 1 列，而多数终端画 2 列 → 含这类字符的那一行整体右移一列。这不是本设计能修的
  （是宽度 oracle 的问题，全项目共用），但表格是**最容易暴露**它的地方，故记在此。
  pty 冒烟里放一个 emoji 格子能看到；注意 pyte 用的是另一套 wcwidth，两边不一致时
  先判断哪一边跟 Terminal.app 一致，别拿 pyte 当真相。
  `─`（U+2500，EAW=Ambiguous）在 CJK 字体下有算 2 列的风险，但欢迎横幅长期依赖它、
  用户截图里 box-drawing 表也正常，判定为低风险。
- **无外侧竖线的表**原样输出，见 1.3 非目标。
- **正常退出也会丢缓冲**：表格还在缓冲里时 Ctrl+C / `/exit`，那几行不会进 scrollback。
  补在 `onStop` 里来不及——终端恢复早在 `InlineTuiRunner.close()` 完成，之后 println 已无意义
  （`CodeTuiView.java:641-646` 有如实声明）。会话落盘不受影响，`-c` 能看到。
  要么在 `shutdownAndQuit` 之前补一次同步 flush，要么接受这条；**本设计选接受**——
  退出前那一瞬的半张表价值极低，而在关停路径上加输出是已被记录过的坑。

## 5. 测试策略

TDD，先红后绿。**每一条 flush 触发点、每一条状态转移都要有对应用例**——本设计的失效模式是
静默丢内容，不是崩溃，所以「跑起来看着对」不算验证。

### 5.1 `MarkdownTableTest`（纯单测）

列宽 / 三种对齐冒号 / CJK 双宽 / `\|` 转义 / 单元格少于表头补空 / **多于表头并入最后一列
（内容守恒）** / 内联标记不计入宽度 / spans 拼接后测量（放一个 ZWJ 或组合字符用例）/
超宽削列（含并列最宽削索引小的那列）/ 格内按空格折行 / 单词超列宽硬切且不切半个宽字符 /
CJK 无空格落硬切 / 整列全空按最小宽 4 / 单列表 / 表头比数据长且被削 / 列数太多退回原样 /
**`render` 对畸形输入不抛**（负宽度、只有分隔行、空块、全是 `|`）。
另加一条**交叉单测**：无空格可断时，格内折行与 `SegmentedWrap` 产出逐字相等。

### 5.2 `MarkdownRendererTableTest`

§3.3 转移表逐格钉住，重点这几条容易漏的：

- 候选 + 非分隔行 → 候选行先出 + **当前行重新投喂**（用那个「`|` 开头正文 + 真表格」的例子，
  断言表格**被识别**，不是四行原样）
- 候选-only 时 `flush` → 该行按普通行输出；且此时 `hasBuffered()` 为 true
- 围栏内的 `|` 行不识别；表格紧跟围栏、空行分隔的两张表
- 越上限 → 已攒行原样 + 转降级 + 剩余 `|` 行原样
- `reset()` 丢缓冲（上一回合残表不复活）
- `flush` 空缓冲幂等

### 5.3 `ScrollbackPrinterTest` 增补

走真实 `assistantCursor` 与 `streamingLinesCursor`（**不要用一次性的 `assistant(String)`**——
它不按 `\n` 拆，多行文本会变成一条带内嵌换行的 Line，产出与生产路径不同；这个坑在本设计的
探针阶段已踩过一次）。120 列与 80 列各断言：

- **产出的物理段数 == 表格逻辑行数**（等价于「二次折行是 no-op」）。只断言「每行 ≤ 终端宽」
  会在「排出的行比 inner 宽 1 列」时假绿——那种输入照样 ≤ 终端宽，但会被
  `SegmentedWrap` 撕成两段
- 各行列起点的显示偏移一致
- 内容拼回去一个字不丢
- **一批 300 条流式行、第一条是 `|` 开头**：断言 300 条逻辑行全部落地（钉 §3.6 那条雷）
- 每行 `PhysicalLine.raw` 是不同实例

### 5.4 视图级（照 `CodeTuiViewEventWiringTest` 的写法）

- **不变量**：任一批结束时不允许 `outputRemaining == false && hasBufferedTable() == true`
- 回合以表格结尾：断言 `UpdateResult` / `hasContinuationScheduledForTest()`
  （参照 `CodeTuiViewEventWiringTest.java:648-655`、`:673`）。**不能**用
  `processUpdatesForTest` 连跑几批再断言表格出现——生产只有 `outputRemaining == true`
  才会有下一批，那样写是假绿
- `TOOL_START` 插在表格中间 → 表格先出、工具行后出（顺序不能反）
- `INFO`（`/context` 那种）插在表格中间 → **通知行先出、表格继续攒**、之后整块对齐落地
  （断言不出现「半张对齐 + 半张原样」）
- 模态（权限 / 问询）弹出时缓冲排空
- 计划正文以表格结尾 → 批准前表格已在屏幕上
- `onError` / Esc 取消 → 缓冲排空
- `/clear` → 丢缓冲（测试态 runner 为空也要成立）

### 5.5 pty 冒烟（改渲染必实机验证）

⚠ **`-c` 回放是最安全的一条路，单靠它验不出触发点 4/5**：`replayHistory` 末尾固定补一条
INFO 分割线（`ConversationState.java:303-304`），天然把缓冲顶出来。所以冒烟必须造
**实时回合以表格结尾**的场景（或让回放的最后一条就是表格并去掉那条尾行），在真实屏幕上断言
各行列起点一致、没有裂行、表格**在回合结束后自己出现**（不靠按键）。
放一个 emoji 格子看宽度 oracle 的偏差（见 §4）。骨架复用 `resize_smoke.py` 的 `PtySession`
（`TIOCSWINSZ` + `TERM=xterm-256color`，否则读到全空白）。

### 5.6 变异纪律

把 §3.4 第 4 条（回合结束 flush）注掉 → 必须有测试变红。同样对第 2 条、第 5 条、
§3.6 的 `next()` 内部循环各做一次。按原稿的测试写法这几条**很可能全绿**，这是加这一节的理由。

验证命令按模块作用域跑：`mvn test -pl springai-code-tui -Dtest=...`（整仓 `-Dtest` 会被
空模块打挂）。

## 6. 风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| `next()` 没做内部循环 | 一批最多 300 条逻辑行静默消失 | §3.6 明写；5.3 用「首行是 `\|` 的 300 行批」钉 |
| flush 触发点漏一条 | 表格永不显示 / 顺序错乱 | §3.4 五条各有用例；§5.6 变异纪律逐条注掉验红 |
| 排出的行比 inner 宽 1 列 | 个别行被二次折行撕开，最难看的形态 | §3.1 硬不变量；5.3 断言段数相等而非「≤ 终端宽」 |
| 列宽算错（内联标记 / CJK / 组合字符） | 又是一版错位 | 宽度一律走 spans 拼接后测量；单测钉双宽与 ZWJ |
| `render` 抛异常 | 丢整块 + 同游标剩余逻辑行 | 「对任意输入不抛」契约 + flush 内自兜降级 |
| 大表格独占一批输出 | UI 卡顿 | 200 行 / 64 K 上限降级；如实声明游离于时间预算之外 |
| 留底 Text 复用同一实例 | resize 重放掉行 | §3.6 明写「每行独立实例」 |

## 7. 实施切分（供 writing-plans 展开）

0. **可见性前置**：`MarkdownRenderer.BOLD` / `DIM` / `renderInline` 与
   `ScrollbackPrinter.INDENT` 改 package-private（`renderInline` 提成 static）
1. `MarkdownTable`（解析 + 列宽 + 削列 + 格内折行 + 不抛契约）+ §5.1 单测——纯函数，可独立完成
2. `MarkdownRenderer.feed/flush/hasBuffered` 状态机（含降级态、重投喂、`reset` 丢缓冲）+ §5.2 单测
3. `MdLineCursor`：待吐队列 + **`next()` 内部循环** + `hasNext()` 修正 + 独立 `raw` 实例
   + `tableFlushCursor()` + §5.3 增补测试；同步补 `OutputCursor` / `PhysicalOutputQueue` /
   `ScrollbackPrinter.md` 三处 javadoc 的第二条例外声明
4. 视图接线：`enqueueOutputLine` 分 kind 前置 flush（INFO/ERROR 豁免）、主 drain 之后的
   兜底 flush + 补一次 drain、`printPlan` 收尾 flush、`/clear` 同步丢缓冲 + §5.4 视图级测试
5. pty 冒烟（实时回合以表格结尾）+ §5.6 变异纪律 + 全量回归（`mvn test -pl springai-code-tui`）

## 评审记录

### 第 1 轮（2026-09-04，两个 subagent 并行）

**事实核对**（核对每条 `文件:行号` 与对现有实现的断言，含两个临时 JUnit 探针）：
回合结束 flush 的闸门在「回复以表格结尾」这个它专门要治的场景下永不成立（探针实测：
那一批 `pendingEmpty=false`，批尾 `outputRemaining=false` 不再排批）；模态期间 `IDLE`
条件恰好排除了「UI 为用户暂停」的时刻；`printPlan` 绕过 `enqueueOutputLine`；
`BOLD`/`DIM`/`renderInline`/`INDENT` 全是 private；第 2 条触发点的理由方向说反；
80 列样例与 §1.2 那张表不是同一张、分隔线长度对不上；`width` 是终端宽还是内宽未定义、
与缩进各会叠一层；`toolStartCursor` 的先例不成立（工厂时刻 vs 批中途）。**已全部改入。**

**设计漏洞**：`next()` 返回 null 被当耗尽 → 整批逻辑行静默消失（与事实核对独立撞车）；
out-of-band 行打断块会拼出「半张对齐 + 半张原样」→ 改为 INFO/ERROR 豁免不 flush；
单元格「多的丢」与「不丢字」矛盾 → 改为并入最后一列；状态机缺「当前行重新投喂」→ 真表格
会被前一行 `|` 正文吃掉；候选-only flush 语义未定义；「表格行宽 ≤ inner」是隐含硬不变量
且原断言假绿；格内按空格折行是仓里第三套折行语义；`render` 抛异常会丢整块 + 剩余行；
`raw` 复用实例会让 resize 掉行；`-c` 回放冒烟对触发点 4/5 免疫；`md.reset()` 对缓冲语义未写；
`/clear` 丢缓冲不能放进 `runOnRenderThread`。**已全部改入。**

未采纳一条：把 `hasBufferedTable()` 并进 `outputRemaining`（会双线程满载空转，
理由写进 §3.4 末尾）。


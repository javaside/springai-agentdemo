# ContextUsage 抽取设计（CodeTuiView 重构 4/4）

**日期**：2026-07-03
**范围**：`springai-code-tui` · `com.example.springai.codetui.ui.CodeTuiView`
**前序**：重构 1/4 Theme（`27187bc`）、2/4 ScrollbackPrinter（`357d29c`）、3/4 StatusBar（`dcce0db`）已完成。本步收尾 1–4 批。

---

## 目标（一句话）

把「上下文用量的追踪与报告」这一簇——`/context` 报告、状态栏后缀、节流缓存刷新、百分比助手 + 缓存字段
——抽成**零 UI 依赖的纯 Java 类** `ContextUsage`，让此前埋在 `InlineApp` 里测不了的 `String.format` 分支 /
百分比舍入 / 缓存容错变得可离线单测。

## 为什么这么切（向好的方向）

这簇是一个**完整且内聚的职责**：

| 成员 | 职责 | 归属检验 |
|---|---|---|
| 字段 `ctxStats`（volatile 缓存快照） | 状态栏节流缓存 | 正是 `refresh`(写)/`suffix`(读) 那份状态——像 #2 的 `md` |
| `refreshCtxStats()` | drain 里 ~1s 现算一份存缓存，异常静默保留旧值 | 写缓存 |
| `ctxSuffix()` | 状态栏后缀 `" · 上下文 N%"`（读缓存） | 读缓存 |
| `printContext()` | `/context` 现算并打印多行报告 | 读实时 source |
| `pct()`（private static） | 百分比舍入 | 只被 `printContext`+`ctxSuffix` 用——移走无孤儿 |

**和前三次的差异**：#2 依赖 tamboui（要 Sink 接缝）、#3 是无状态纯函数；**#4 拥有真实状态（`ctxStats` 缓存）
但完全不碰 tamboui**——只依赖 `ContextStats`（纯 Java record）+ `onSubmit.contextStats()`。这是最干净的一次：
抽出后**单测无需任何 tamboui 类型**。

一个类装下正好。拆成两个（如「缓存」与「报告」分开）要重复注入 `source` 依赖 + 复制 `pct`——过度碎片化，
#3 已拒过同类诱惑（不建共享工具类）。

## 设计

### 新类 `ContextUsage`（package-private, final，有状态）

```
字段：
    private final Supplier<ContextStats> source;      // 现算：读一遍当前会话（= onSubmit::contextStats）
    private final Consumer<String> sink;              // 输出：灰色信息行下沉 scrollback（= state::pushInfo）
    private volatile ContextStats cached = ContextStats.empty();   // 状态栏节流缓存

构造：ContextUsage(Supplier<ContextStats> source, Consumer<String> sink)

    void refresh()          —— 原 refreshCtxStats：现算存缓存，RuntimeException 静默保留旧值
    String suffix()         —— 原 ctxSuffix：读 cached，尚无对话/窗口未知返回 ""
    void report()           —— 原 printContext：读实时 source，多行报告经 sink 下沉
    private static String pct(long part, long whole)  —— 原 pct
```

依赖：`com.example.springai.codetui.agent.ContextStats` + `java.util.function.{Supplier,Consumer}`。**不 import 任何
tamboui 类型**——这是它可离线单测的根据。

### 接缝设计（同 ScrollbackPrinter 的 DI 风格）

- **入**：`Supplier<ContextStats> source`——`report()` 读实时、`suffix()`/`refresh()` 走缓存，**保持现有语义差异**
  （报告要最新，状态栏读节流缓存避免每帧重算）。
- **出**：`Consumer<String> sink`——`report()` 逐行 `sink.accept(...)`，视图注入 `state::pushInfo`。

### ⚠ 构造时序坑（必须在构造函数体内构造，不能字段初始化器）

`new ContextUsage(onSubmit::contextStats, state::pushInfo)` 里的方法引用绑定的是 `this.onSubmit`/`this.state`。
若写成字段初始化器，此刻两者尚为 null → 立即 NPE。**必须放在构造函数体内、`this.onSubmit=...` 赋值之后**——
和现有 `printer` 字段完全一致（`printer` 也在构造函数体内 new）。

### 调用点改动（视图侧）

```java
// 字段（构造函数体内 new，声明处不初始化）
private final ContextUsage ctxUsage;

// 构造函数体内（onSubmit/state 已赋值后）：
this.ctxUsage = new ContextUsage(onSubmit::contextStats, state::pushInfo);

// drain()：if (animTick % 30 == 0) ctxUsage.refresh();
// submitInput() 的 /context 分支：ctxUsage.report();
// statusLine() IDLE 支：... + onSubmit.currentModel() + ctxUsage.suffix()).style(HINT);

// 删除字段 ctxStats；删除方法 printContext / pct / refreshCtxStats / ctxSuffix
// 删除 import com.example.springai.codetui.agent.ContextStats;（移除后视图不再直接引用）
```

## 行为契约（必须逐字保持，纯搬运零行为改动）

- **`report()`**：`source` 返回 null 视为 `ContextStats.empty()`；先打 `"📊 上下文用量"`；`events()==0` 则打
  `"  （尚无对话历史）"` 并 return；否则依次：事件数分桶行（`otherEvents()>0` 才带「· 其他 N」）、token 行
  （`contextWindow()>0` 带「/ 窗口（占窗口 N%）」否则只「估算 token：N」）、`tokenThreshold()>0` 才打自动压缩行、
  `manualKeepEvents()>0` 才打手动行。所有 `%,d`/文案逐字不变。
- **`suffix()`**：读 `cached`；`null || events()==0 || contextWindow()<=0` → `""`；否则 `" · 上下文 " + pct(est, window)`。
- **`refresh()`**：`source.get()` 非 null 才写 `cached`；`RuntimeException` 吞掉（保留旧值）。
- **`pct(part, whole)`**：`whole<=0 → "0%"`；否则 `Math.round(part*100.0/whole) + "%"`。

## 测试策略

1. **回归**：现有离线套件（#3 后基线 **94**）继续全绿。
2. **新增 `ContextUsageTest`**（纯 Java 单测，无任何 tamboui）：
   - 用 `RecordingSink`（`List<String>` 收 `Consumer<String>` 的行）+ 可控 `Supplier<ContextStats>`。
   - `report` 空快照 → 2 行（标题 + 「尚无对话历史」）；满快照 → 校验事件桶/占窗口 %/自动压缩当前 %/手动行的分支
     与舍入（断言 `"占窗口 3%"`、`"当前 30%"` 等 pct 输出，**避开 `%,d` 分组数字的 locale 脆弱性**）。
   - `report` source 返回 null → 当空快照处理。
   - `suffix`：先 `refresh()` 填缓存再读——验 `refresh→cached→suffix` 流；无 events / 窗口=0 → `""`；正常 → `"· 上下文 3%"`。
   - `refresh` 容错：`source` 抛 `RuntimeException` → `cached` 不变（`suffix()` 仍返回上次值）。
3. **pty 实机核对**：`package` 后跑探针，`/context` 打印报告、空闲状态行「· 上下文 N%」后缀渲染与重构前一致。

## 非目标（本步不做）

- 不改 `ContextStats` record、不改 `SubmitHandler.contextStats()`。
- 不动 `animTick` / drain 的节流节拍（`% 30`）——只把被调方法换成 `ctxUsage.refresh()`。
- 不动状态行分发器其余支（#3 已抽动画，本步只替换 IDLE 支里的 `ctxSuffix()` 调用）。
- 不新建工具类、不改渲染字节 / 配色 / 布局。

## 风险与回滚

- 风险低：搬 4 个方法 + 1 字段、删 1 import、加 1 字段 + 构造行 + 改 3 处调用点。最大坑是构造时序（已在上文标红：
  必须构造函数体内 new）。编译 + `report`/`suffix` 单测即可暴露多数问题。
- 回滚：单 commit `git revert`；`ContextUsage` 为新增文件，删除 + 还原视图即恢复。

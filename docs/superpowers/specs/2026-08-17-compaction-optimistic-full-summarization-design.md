# 上下文压缩：乐观全量摘要设计

> v2（2026-08-18）：吸收设计评审
> `2026-08-17-compaction-optimistic-full-summarization-design-review.md` 的全部 P0/P1/P2 意见——
> 补 knownBad 短路闭环、拆分探测/正式调用并重算效果表、定义实施接口（构造器/快照/状态共享）、
> 输出预算与 maxTokens 修正、数字解析负例、测试基线分类、可观测性。

## 背景

会话压缩当前采用悲观预分片：`BoundedSummarizationCompactionStrategy` 先把归档历史按固定预算
（`max(4k, min(64k, W/8) − 4k)`）切成多个 chunk，再**串行**逐块调用摘要模型，每块都是大输入的
阻塞 `.call()`。摘要模型走 `DynamicAuxChatModel`，委托的是当前激活的主对话模型。

延迟账（1M 窗口档）：触发线 0.70W = 700k，归档 ≈ 433k，chunk = 60k（被 `MAX_SUMMARY_CHUNK_TOKENS`
= 64k 封顶）→ **约 8 次串行大请求**；合并摘要超预算时再压缩循环最多再加 4 轮。压缩又发生在
`PreflightCompactionAdvisor` 的请求关键路径上（`chain.nextStream` 之前同步执行），用户下一条
消息要等整个压缩完成。实测分钟级。

核心矛盾：摘要模型与主模型是同一个，拥有整个窗口容量，却按 W/8（还被 64k 封顶）喂——
在 1M 配置正确的场景下，本可**一次全量**完成。

另有一个现实约束：窗口大小来自内置表 + `CODETUI_CONTEXT_WINDOWS` 环境变量覆盖
（`ModelContextWindows`），**用户可能配错**（配 1M、实际 400k 甚至更小），聚合网关
（如 opencode-go）尤甚。算法必须能在配置虚高时自我校准。且许多网关报超限时
**不返回真实窗口数字**，校准不能依赖错误消息里带数字。

## 目标

- 窗口配置正确时：归档历史一次全量摘要，1 次模型调用（现状 ~8 次）。
- 窗口配置虚高时：用真实失败反馈快速校准，单次压缩总调用数有**全局硬上限**；不依赖错误消息包含数字。
- 校准结果跨压缩记忆（进程内）：第一次付少量试错成本，之后零试错（`knownBad` 短路保证成立）。
- 非「超限」类错误（网络、限流、鉴权）不触发预算调整，行为与现状一致（落 `localDigest`）。

## 非目标

- 不改变触发线（0.70W）、目标预算（`max(8k, 0.55W − MEDIA_MANIFEST_TOKEN_RESERVE)`，1M 档
  即 534k）、保留后缀逻辑（`newestSuffixStart`）。
- 不改变 `MediaReferencePreservingCompactionStrategy` / `NotifyingCompactionStrategy` 装饰链语义。
- 不做 chunk 间并行（块数已显著下降，收益不抵复杂度；留作二期）。
- 校准状态不持久化（进程重启后重新学习）。失败请求的成本假设见「待核实事项」。
- 不解析 OpenCode Go 等网关的私有错误格式。
- 不做 knownGood/knownBad 区间主动探测（爬回真实容量的中点试探，留作二期优化）。

## 方案

### 主算法：乐观全量 + 区间学习（含 knownBad 短路）

`BoundedSummarizationCompactionStrategy.compact()` 中归档部分（`chunks()` + 串行循环）替换为：

```text
text = format(archived)                  # 现有格式化逻辑不变
E = estimate(text)
sel  = providerRegistry.activeRequestSelection()   # 一次快照：key 与窗口同源（见「快照」）
key = sel.provider().id() + ":" + sel.modelId()
interval = calibration.get(key)          # CalibrationState，见下
fullBudget = configuredWindow(sel) − (SUMMARY_PROMPT_TOKEN_RESERVE + OUTPUT_RESERVE)
calls = remainingCallBudget              # 全局调用上限，见「全局调用上限」

# 1. 已证明安全：直接全量（1 次调用）
if E ≤ interval.knownGood:
    return summarizeFull(text)

# 2. 已证明会失败：绝不重发注定失败的全量，直接按安全预算切块
if interval.knownBad != null and E ≥ interval.knownBad:
    budget = max(interval.knownGood, SAFE_FALLBACK_BUDGET)
    return summarizeChunked(text, budget)

# 3. 区间中间态（knownGood < E < knownBad）或首次：允许乐观全量
#    理由：失败便宜（秒级被拒），不试就永远学不到更大容量；失败则收紧 knownBad
if E ≤ fullBudget:
    try: return summarizeFull(text)      # 成功 → knownGood = max(knownGood, E)
    except 超限 as ex:
        interval.updateBad(E)            # knownBad = min(knownBad, E)
        budget = parseWindowNumber(ex) ?? halve(E, interval)
        # 解析出数字：budget = 数字 − 预留，一步到位
        # 无数字：budget = clamp(min(E/2, knownBad−1), lower=knownGood, upper=fullBudget)
        #         —— 不会把预算减到已验证安全水平之下

# 4. 探测与正式分离（无数字路径专用）
#    探测请求：单次、输入取 text 的前 budget tokens（不是完整切块）。
#    探测成功 → 该次结果不作为摘要复用，立即用同 budget 走正式切块循环（探测只学容量）。
#    探测失败 → 继续减半（受安全阀约束），深度 ≤ MAX_HALVING_DEPTH
#    带数字路径不需要探测：数字本身就是官方容量，直接进第 5 步

# 5. 正式切块
if ⌈E / budget⌉ > MAX_CHUNKS:           # 安全阀，见下
    return localDigest(text)
return summarizeChunked(text, budget)    # 全部块成功才更新 knownGood = max(knownGood, budget)
```

保留后缀、触发判断、摘要事件合成（synthetic event）、saved 计算等外层逻辑全部不动。

### 全局调用上限（remainingCallBudget）

单次压缩内所有模型调用（全量尝试、探测、切块、合并摘要再压缩）共享一个递减计数器，
归零即 `localDigest`。常量 `MAX_TOTAL_CALLS = 20`，推导：

- 1 次乐观全量 + 4 次探测（减半深度上限）+ 8 次切块（块数上限）= 13，为合并再压缩
  （≤4 轮 × ≤2 块）留 7 次余量，总 20。

现状算法无全局上限（8 块 × 再压缩 4 轮最多 ~40 次调用），新算法把它钉死在 20。

### 安全阀（防极端错配爆炸）

三重限制（与全局上限独立生效），任一触发即落 `localDigest`（本地纯文本兜底，行为同现状）：

1. budget < 16k 仍失败 → 模型窗口小到无法做摘要（**严格小于**：budget = 16k 允许最后尝试一次）
2. 按 budget 切出的总块数 > 8 → 等效回到现状的慢，不如本地摘要
3. 单次压缩内减半深度 ≤ 4（第 4 次减半允许尝试，仍失败才兜底）

边界语义统一为「触线前允许最后一次尝试」：`budget < 16k`、`块数 > 8`、`深度 ≤ 4`。

### 错误分类与数字解析

新增小分类器：遍历异常 **cause chain**（Spring AI 通常把服务端 4xx 包在通用 RuntimeException 里），
任一层消息匹配「超限」特征串（`context_length_exceeded`、`prompt is too long`、
`maximum context length`、`input too long` 等常见变体，大小写不敏感）才算超限；
其余 RuntimeException 一律按现状落 `localDigest` 本地兜底，**绝不拿网络错误调预算**。

真实窗口数字解析锚定窗口值，不是「找第一个数字」：

- OpenAI：`maximum context length is 163857 tokens` → 锚 `maximum context length is` 之后的数
- Anthropic：`195300 tokens > 200000 maximum` → 锚 `>` 与 `maximum` 之间的数（不是 195300）
- DeepSeek：`Maximum context length is 65536` → 同 OpenAI 锚法
- 中文变体（智谱/千问等）：锚「最长」「最大上下文」等关键词后的数字

解析结果 clamp：`budget = clamp(数字 − 预留, lower=knownGood, upper=fullBudget)`；
解析出 `≤ 预留` 的非法值视为解析失败，走减半。

### 校准状态（CalibrationState）

独立小类，按 `provider:model` 维度记一对值：

- `knownGood`：已验证能成功的最大输入量（下界，初始 SAFE_FALLBACK_BUDGET = 32k——
  任何现代模型窗口都装得下的保守起点，且高于安全阀下限 16k）
- `knownBad`：已验证会失败的最小输入量（上界，初始 null）

并发与单调性：

- 每个值对用**不可变 record + `AtomicReference`**（或 `ConcurrentHashMap.compute`）原子更新，
  杜绝读到 knownGood > knownBad 的撕裂区间
- 更新单调：`knownGood = max(knownGood, 新值)`、`knownBad = min(knownBad, 新值)`（null 视为 +∞）
- `knownGood` 记录的是**用户文本估计量**（estimate(text) 或切块 budget），不含 system prompt
  与消息封装——比对时同口径（E 也是文本估计量），自洽

注入与共享：`AgentTools.build()` 创建**一个** `CalibrationState` 实例，auto/manual 两条策略
实例共享（校准是模型的属性，不是策略实例的属性）。提供 `reset()` 供测试隔离。

### 快照一致性（顺带修 P2-8 现存 bug）

校准 key 与窗口预算必须出自**同一次**快照。现有 `ProviderRegistry.activeRequestSelection()`
（`synchronized`，返回 `provider + modelId + config + options`）已满足，直接使用；
`AgentTools.contextWindow(registry)` 的两次读取改为由快照派生。

顺带修 `DynamicAuxChatModel` 的同款问题：其 javadoc 声称「单次读取」，实际
`registry.active()` 与 `registry.activeModelId()` 是两次独立 synchronized 调用，存在交错窗口。
改为一次 `activeRequestSelection()` 派生两者。这是**现存 bug**，与本设计解耦但同批修复。

### 输出预算与 maxTokens（修订原「顺带修正」）

现状：`AnthropicProvider.MAX_TOKENS = 8192` 硬编码在默认 options 里；`DynamicAuxChatModel`
每调用用 `active.options()` **整体替换** prompt options——prompt 上设置的 maxTokens 会被丢弃。
因此加大摘要输出上限不能只改 prompt 调用点。

修订：

1. `DynamicAuxChatModel` 支持合并式 options 覆盖：prompt 自带非空 options 字段
   （如 maxTokens）覆盖 provider 基础 options 的同名字段，其余保持 provider 默认。
   守卫测试 `AuxClientNotVisionWrappedTest` 的「裸 DynamicAuxChatModel」断言同步修订
   （仍是同一个类，只是内部多了 merge 逻辑）。
2. 输出预算 N 与预留对齐：`OUTPUT_RESERVE = 8k`，摘要 system prompt 写「输出不超过 8000 tokens」，
   同时摘要路径显式设 `maxTokens = 8192`（与 Anthropic 现值一致，其他 provider 也统一到 8192，
   作为 API 层硬约束兜底——模型不保证遵守自然语言 token 约束）。
3. 「1 次调用」的准确表述：**1 次全量输入**；输出落在 8k 预留内即成立。若模型输出超出
   summaryBudget，退化路径不变（再压缩循环 → `localDigest`），调用数计入全局上限。

### 可观测性

slf4j 记录（文件日志，不进 TUI stdout）：每次压缩的调用总数、走的分支（全量/切块/兜底）、
校准尝试次数、knownGood/knownBad 变化、安全阀/全局上限触发原因。

## 接口变更

`BoundedSummarizationCompactionStrategy` 新增构造器（旧的 4 参构造器**保留**，语义变为
「无校准能力的悲观降级模式」，供现有测试与不需要校准的场景使用）：

```java
BoundedSummarizationCompactionStrategy(LongSupplier targetTokens,
                                       LongSupplier chunkTokens,      // 现改为校准后切块预算的上限
                                       ToIntFunction<String> estimator,
                                       Function<String, String> summarizer,
                                       Supplier<ProviderRegistry.RequestSelection> selection,  // 快照源
                                       LongSupplier configuredWindow, // 由快照派生的窗口
                                       CalibrationState calibration)  // auto/manual 共享
```

`AgentTools` 装配处创建单一 `CalibrationState`，两条策略共享；`MAX_SUMMARY_CHUNK_TOKENS`
从「绝对上限」语义改为「未知窗口的保守兜底」。

## 预期效果

| 场景（归档 433k） | 现状 | 新方案 |
|---|---|---|
| 配 1M、实际 1M | ~8 次串行 | **1 次全量输入**（输出 ≤8k 内成立） |
| 配 1M、实际 400k，错误带数字 | ~8 次 | 1 次失败 + ~2 次切块（总 ~3 次） |
| 配 1M、实际 400k，错误无数字 | ~8 次 | 1 次失败 + 1~4 次探测 + 2~3 次切块（总 4~8 次） |
| 第二次及以后压缩 | ~8 次 | knownBad 短路 → 直接按 knownGood 切块（2~3 次），零试错 |
| 错配触发安全阀（budget 跌破 16k 或块数 > 8） | ~8 次慢 | localDigest 本地兜底 |

全场景硬上限：单次压缩 ≤ 20 次模型调用（现状最坏 ~40 次且无上限保障）。

预期质量红利（假设，待实施后小型评估验证）：单次全量摘要无跨块割裂，可能保留更好的跨块关联；
但长上下文全量摘要也可能出现 lost-in-middle、细节遗漏或输出过长——不是必然更好。

## 测试

**保留原样**（行为不变，须全绿）：`AgentToolsCompactionWiringTest`、
`NotifyingCompactionStrategyTest`、`MediaReferencePreservingCompactionStrategyTest`、
`CodingAgentCompactTest`、`ContextStatsTest` 中除下述两条外的用例（走旧构造器的
`oversizedNewestTurnIsSummarizedInsteadOfKeptAboveTarget`、`nonShrinkingSummariesFallBackToBoundedLocalDigest`、
`summarizerFailureFallsBackLocallyInsteadOfBlockingConversation` 等）。

**随新语义调整**（悲观分片断言改为乐观语义断言）：

- `boundedStrategyNeverSendsMoreThanChunkBudgetToSummarizer`：新构造器下改为断言
  「knownGood 命中时 1 次全量调用；切块模式下每块 ≤ 校准 budget」。
- `boundedStrategyCanRescueSingleOversizedEvent`：改为「单条巨型事件走全量或按校准 budget 切块，
  不 no-op」。

**新增**（fake summarizer 可注入失败/成功序列）：

1. 全量成功：单次调用、无切块、knownGood 更新为 E。
2. 首次失败（超限、带数字）→ 按数字切块重试成功、knownGood/knownBad 更新、数字锚定正确。
3. 首次失败（超限、无数字）→ 探测减半、探测结果不复用、正式切块成功、knownGood 更新。
4. knownBad 短路：已知 knownBad 时，第二次相同归档**绝不发全量请求**。
5. 区间中间态（knownGood < E < knownBad）：发全量；失败收紧 knownBad。
6. 非超限异常 → 不调预算、直接 localDigest。
7. 数字解析负例：双数字（锚定窗口值而非请求量）、无数字、带逗号、cause chain 包装、
   解析后 ≤ 预留的非法值。
8. 安全阀三重边界（< 16k / 块数 > 8 / 深度 4）+ 全局 20 次上限 → localDigest。
9. 多块局部失败：第二块超限/网络错误 → 整体 localDigest（不复用部分摘要、不重试单块——
   语义从简，防止复杂化；knownGood 仍按已成功块的最大 estimate 更新）。
10. 并发校准：同一 key 并发压缩，区间单调、无 knownGood > knownBad。
11. auto/manual 共享：auto 学到校准值后，manual 首次压缩零试错。
12. 状态重置：`CalibrationState.reset()` 后回到初始区间。
13. 快照一致性：模拟 `/model` 切换与压缩并发，校准 key 与请求模型同源。

## 实施范围

- 改：`BoundedSummarizationCompactionStrategy`（主算法、区间学习、安全阀、全局上限、
  新构造器）、`AgentTools`（装配：共享 CalibrationState、prompt 文案与 maxTokens、
  `MAX_SUMMARY_CHUNK_TOKENS` 语义）、`DynamicAuxChatModel`（options 合并 + 一次快照）。
- 新增：`CalibrationState`（区间存储 + 原子更新 + reset）、超限错误分类器 + 数字解析
  （独立小类，放同包）。
- 修订：`AuxClientNotVisionWrappedTest`（merge 后仍为裸类断言）、`ContextStatsTest` 两条
  悲观分片用例。
- 不动：触发/目标预算常量、`PreflightCompactionAdvisor`、装饰链。

## 待核实事项（实施前）

- 失败请求的成本假设（秒级被拒、不计费）**未经验证**：逐 provider 核实 OpenAI/Anthropic/
  DeepSeek/Zhipu/Qwen/opencode-go 对超限请求的拒绝延迟与计费策略。若存在计费，20 次上限
  即成本上限，需评估可接受性。
- 各 provider 摘要路径在 options 合并后的实际 maxTokens 行为（尤其 OpenAI 系忽略 maxTokens
  与 reasoning 模型输出计费）。

# 上下文压缩：乐观全量摘要设计

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
- 窗口配置虚高时：用真实失败反馈快速校准，单次压缩总调用数有硬上限；不依赖错误消息包含数字。
- 校准结果跨压缩记忆（进程内）：第一次付少量试错成本，之后零试错。
- 非「超限」类错误（网络、限流、鉴权）不触发预算调整，行为与现状一致（落 `localDigest`）。

## 非目标

- 不改变触发线（0.70W）、目标预算（0.55W）、保留后缀逻辑（`newestSuffixStart`）。
- 不改变 `MediaReferencePreservingCompactionStrategy` / `NotifyingCompactionStrategy` 装饰链语义。
- 不做 chunk 间并行（块数已降到 2~3，收益不抵复杂度；留作二期）。
- 校准状态不持久化（进程重启后重新学习；失败请求秒级且不计费，成本可忽略）。
- 不解析 OpenCode Go 等网关的私有错误格式。
- 不做 knownGood/knownBad 区间主动探测（爬回真实容量的中点试探，留作二期优化）。

## 方案

### 主算法：乐观全量 + 区间学习

`BoundedSummarizationCompactionStrategy.compact()` 中归档部分（`chunks()` + 串行循环）替换为：

```
text = format(archived)                       # 现有格式化逻辑不变
budget:
  1. estimate(text) ≤ knownGood[provider:model] → 直接全量摘要，1 次调用
  2. 否则按 全量预算 = 配置窗口 − (SUMMARY_PROMPT_TOKEN_RESERVE + 输出预留 8k) 尝试
     成功 → knownGood 更新为 estimate(text)，收工
  3. 失败且错误分类为「超限」→
       a. 尝试解析错误消息中的真实窗口数字（有 → budget = 数字 − 预留，一步校准）
       b. 无数字 → budget = estimate(text) / 2，减半重试
     成功的减半请求其结果即为有效摘要，knownGood 更新
  4. 再失败 → 继续减半，受安全阀约束（见下）
  5. 切块循环：budget 定了以后，块数 = ⌈estimate(text) / budget⌉（>1 时为多次串行调用，与现状同构）
```

保留后缀、触发判断、摘要事件合成（synthetic event）、saved 计算等外层逻辑全部不动。

### 错误分类

新增小分类器：异常消息匹配「超限」特征串（`context_length_exceeded`、`prompt is too long`、
`maximum context length`、`input too long` 等常见变体，大小写不敏感）才算超限；
其余 RuntimeException 一律按现状落 `localDigest` 本地兜底，**绝不拿网络错误调预算**。

真实窗口数字解析：OpenAI 风格 `maximum context length is 163857 tokens`、Anthropic 风格
`195300 tokens > 200000 maximum`、DeepSeek 风格 `Maximum context length is 65536`。解析出即用；
解析不出走减半。数字解析只是加速器，不是依赖。

### 区间学习状态

按 `provider:model` 维度记两个值（`ConcurrentHashMap`，volatile 读写）：

- `knownGood`：已验证能成功的最大输入量（下界）
- `knownBad`：已验证会失败的最小输入量（上界）

用途：下次压缩直接从 `knownGood` 起步（步骤 1），减半时以 `knownBad` 为约束（budget 不越过它）。
切模型不串味（不同 key 独立）。

### 安全阀（防极端错配爆炸）

三重限制，任一触发即落 `localDigest`（本地纯文本兜底，行为同现状）：

1. budget < 16k 仍失败 → 模型窗口小到无法做摘要
2. 按 budget 切出的总块数 > 8 → 等效回到现状的慢，不如本地摘要
3. 单次压缩内减半深度 ≤ 4

### 顺带修正（低风险，一并做）

1. **摘要 system prompt 加输出预算约束**（如「输出不超过 N tokens」）：
   降低合并摘要超预算的概率，基本废掉 4 轮再压缩循环的触发条件。
2. **核实摘要路径 maxTokens**：摘要请求 options 来自 `active.options()` 默认值，
   Anthropic 家必填 maxTokens 若默认给得小，全量摘要输出会被截断——实施时逐 provider 核实，
   必要时对摘要路径显式给大 maxTokens。

## 预期效果

| 场景（归档 433k） | 现状 | 新方案 |
|---|---|---|
| 配 1M、实际 1M | ~8 次串行 | **1 次** |
| 配 1M、实际 400k，错误带数字 | ~8 次 | 1 次秒级失败 + ~2 次 |
| 配 1M、实际 400k，错误无数字 | ~8 次 | 1 次失败 + 减半成功（共 2 次） |
| 第二次及以后压缩 | ~8 次 | 直接按 knownGood 切（1~2 次），零试错 |
| 错配触发安全阀（实际窗口小到 budget 跌破 16k，或块数 > 8） | ~8 次慢 | localDigest 本地兜底 |

质量红利：单次全量摘要无跨块割裂，比多块拼接的摘要保留更好的跨块关联（前一块的决定影响
后一块的语境）。

## 测试

现有测试基线（须全绿）：`ContextStatsTest`、`AgentToolsCompactionWiringTest`、
`NotifyingCompactionStrategyTest`、`MediaReferencePreservingCompactionStrategyTest`、
`CodingAgentCompactTest`。

新增测试（针对策略核心，fake summarizer 可注入失败/成功序列）：

1. 全量成功：单次调用、无切块、knownGood 更新。
2. 首次失败（超限、带数字）→ 按数字切块重试成功、knownGood/knownBad 更新。
3. 首次失败（超限、无数字）→ 减半成功、knownGood 更新。
4. 连续失败至安全阀（块数上限 / 最小块 / 深度上限）→ localDigest 兜底。
5. 非「超限」异常（如网络错误消息）→ 不调预算、直接 localDigest。
6. knownGood 命中路径：第二次压缩零试错。
7. 不同 provider:model key 状态隔离。

## 实施范围

- 改：`BoundedSummarizationCompactionStrategy`（主算法、区间学习、安全阀）、
  `AgentTools`（摘要 prompt 文案、chunk 预算语义调整：`MAX_SUMMARY_CHUNK_TOKENS` 从绝对上限
  改为未知窗口兜底）。
- 新增：超限错误分类器 + 数字解析（可作策略类的静态工具或独立小类，放同包）。
- 不动：触发/目标预算常量、`PreflightCompactionAdvisor`、`DynamicAuxChatModel`、装饰链。

# 设计评审：上下文压缩——乐观全量摘要设计

- 评审对象：`docs/superpowers/specs/2026-08-17-compaction-optimistic-full-summarization-design.md`
- 评审日期：2026-08-18
- 评审结论：**需要修改后再实施**。核心思路成立，但区间学习状态没有在算法中形成闭环，且关键实施接口未定义。
- 代码基线：
  - `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BoundedSummarizationCompactionStrategy.java`
  - `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
  - `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DynamicAuxChatModel.java`
  - `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AnthropicProvider.java`
  - `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PreflightCompactionAdvisor.java`
  - `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ContextStatsTest.java`
  - `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AuxClientNotVisionWrappedTest.java`

## 一、评审结论摘要

文档对现状的还原基本准确，核心直觉——先用整个窗口做全量摘要、失败后用真实反馈校准、非超限错误不动预算——是合理的。但当前版本存在三个 P0 问题：

1. `knownBad` 只被记录、没有被算法使用，导致“第二次压缩零试错”无法成立。
2. “无数字减半”分支的调用次数与“其结果即为有效摘要”互相矛盾，预期效果表数字需要重算。
3. 实施接口与现有代码对不上：策略类没有配置窗口、`provider:model` key、错误分类器和校准状态的入口；auto/manual 两条策略实例的状态共享也未定义。

此外还有状态并发、多块局部失败、输出预算、`DynamicAuxChatModel` 改造边界、数字解析选错数、现有测试与新不变量冲突等问题。以下按严重程度列出。

---

## 二、P0：必须先解决的问题

### P0-1 `knownBad` 没有闭环，“第二次零试错”不成立

设计文档第 70–77 行声明记录 `knownGood/knownBad`，但第 46–55 行的主算法只做了：

- 第 1 步：`estimate(text) <= knownGood` 才走全量；
- 第 2 步：否则**无条件再次按全量预算尝试**。

`knownBad` 只被描述为“减半时约束”，并没有用于跳过已经知道会失败的全量请求。

以文档自己的 433k 归档场景为例：

1. 第一次压缩：433k 全量请求失败，`knownBad` 记 433k；随后按 216k 切块成功，`knownGood` 记 216k。
2. 第二次压缩：`estimate(text) = 433k > knownGood`，按伪代码会**再次发送 433k 全量请求并再次失败**。

这与以下内容直接冲突：

- 目标中的“之后零试错”；
- 测试 6“knownGood 命中路径：第二次压缩零试错”；
- 预期效果表第 103 行“第二次及以后压缩：直接按 knownGood 切（1~2 次），零试错”。

**必须补充的分支**：

- `estimate(text) >= knownBad`：跳过全量尝试，直接按 `knownGood`（或安全预算）切块；
- `knownGood < estimate(text) < knownBad`：明确是允许尝试全文，还是保守地按 `knownGood` 切块。

否则“区间学习”只学不用，设计核心闭环缺失。

### P0-2 “无数字减半”分支的调用次数和有效性自相矛盾

设计文档第 52–53 行写：

> 无数字 → `budget = estimate(text) / 2`，减半重试；成功的减半请求其结果即为有效摘要。

但当 `text ≈ 433k`、`budget ≈ 216k` 时，**单次请求装不下全文**。若按第 55 行切块，需要 `ceil(433k / 216k)` 约 2~3 次调用。因此只有两种可能：

- “减半重试”是一次只覆盖部分历史、预算为 `estimate(text)/2` 的探测请求——那它不能作为全量归档的有效摘要；
- “减半重试”是一次完整的切块运行——那总调用数不是 2。

预期效果表第 102 行：

| 场景 | 原表 | 问题 |
|---|---|---|
| 配 1M、实际 400k，错误无数字 | 1 次失败 + 减半成功（共 2 次） | 不可能覆盖全部归档 |

按算法自身的切块规则，该行应修正为约 **1 次失败 + 2~3 次切块调用（总 3~4 次）**。

同时需要定义：

- “探测请求”和“正式摘要请求”的关系；
- 探测结果是否可复用为第一块摘要，避免重复调用；
- 减半后重试时，`budget < estimate(text)` 的正式运行到底是完整切块循环，还是逐块试探。

### P0-3 实施接口与现有代码对不上

`BoundedSummarizationCompactionStrategy` 当前构造器只有（源码 32–45 行）：

```java
BoundedSummarizationCompactionStrategy(long targetTokens, long chunkTokens,
                                       ToIntFunction<String> estimator,
                                       Function<String, String> summarizer)
```

而设计要求的全量预算、`provider:model` 校准 key、错误分类器、`knownGood/knownBad` 状态，在现有类型里都没有入口。文档实施范围只说“改 `BoundedSummarizationCompactionStrategy`”，但没有定义新构造器或依赖注入方式。

另外，`AgentTools` 中 auto 和 manual 是两个独立策略实例（`AgentTools` 506、517 行）。如果校准状态是实例字段：

- auto 压缩学到的 `knownGood/knownBad`，手动 `/compact` 拿不到；
- 同一进程内“第一次付少量试错成本，之后零试错”只对同一条策略实例成立。

如果状态是静态或共享 store，又必须说明 auto/manual 如何共享、如何测试隔离。

**建议在设计文档中至少补齐**：

- 新构造器参数：`contextWindow supplier`、校准 key supplier、错误分类器、可注入的 `CalibrationState`；
- key 的来源与快照方式（建议通过 `ProviderRegistry.activeRequestSelection()` 一次性取 `provider:model`，避免跨调用读两次）；
- auto/manual 两个实例共享同一个 `CalibrationState` 的装配方式；
- 测试 reset 机制。

---

## 三、P1：设计缺口

### P1-1 `knownGood/knownBad` 的更新语义和并发模型不完整

第 53 行只写“knownGood 更新”，没有定义更新值：

- 全量成功：记 `estimate(text)` 看起来合理，但需明确它只代表“用户文本估计量”，不含 system prompt、消息封装和输出预留。
- 带数字校准成功：记解析出的 `budget`、最大成功 chunk 的 estimate、还是二者的较小值？
- 多次减半后成功：记最后一次 budget，还是所有成功 chunk 的最大 estimate？

“ConcurrentHashMap + volatile 读写”也不足以表达这两个值的更新：

- `knownGood` 和 `knownBad` 必须作为一对值原子读写，否则并发压缩可能读到不一致区间；
- 更新必须是单调的：`knownGood = max(knownGood, newGood)`，`knownBad = min(knownBad, newBad)`；
- 需要防止出现 `knownGood > knownBad`。

建议使用不可变 pair + `AtomicReference` 或 `ConcurrentHashMap.compute`，并写清并发冲突时的合并规则。

### P1-2 多块重试的局部失败没有定义

全量失败后进入切块循环（2~8 块）时，如果第一块成功、第二块超限或网络错误：

- 是整体落 `localDigest`，还是降低预算后只重试失败块？
- 已成功的摘要是否复用？
- 哪些值更新进 `knownGood/knownBad`？

现状是任一 `RuntimeException` 直接本地兜底（`BoundedSummarizationCompactionStrategy` 72–81 行），但新算法既然要“边失败边学习”，必须定义部分失败语义。否则实现者会自行发挥，测试 4 也无法固定行为。

### P1-3 “单次压缩总调用数有硬上限”没有数字，且未覆盖合并摘要循环

目标声称有硬上限，但设计只给了三个局部限制：块数 > 8、最小块 16k、减半深度 ≤ 4。如果每次减半后都重跑一次完整切块循环，最坏情况下校准阶段的调用数可能是 `1 + 4×8` 量级；再叠加现状保留的合并摘要再压缩循环（最多 4 轮，每轮可多块，`BoundedSummarizationCompactionStrategy` 87–105 行），**全局上限仍然没有定义**。

建议引入一个显式的 `remainingCallBudget` 计数器，覆盖：

1. 首次全量尝试；
2. 减半/数字校准的所有探测或切块调用；
3. 合并摘要的再压缩调用。

任何调用前递减，归零即 `localDigest`。设计文档应给出这个常量的具体值和推导。

### P1-4 输出预算 N 未定义，“1 次调用”并不保证成立

全量输入成功，不代表输出满足约束。现有逻辑在 `estimate(merged) > chunkBudget` 时仍会进入最多 4 轮再压缩，最终超 `summaryBudget` 还会 `localDigest`（`BoundedSummarizationCompactionStrategy` 87–105 行）。

“顺带修正 1”只写“输出不超过 N tokens”，但没有定义 N：

- N 取多少？
- N 与 `chunkBudget`、`summaryBudget`、8k 输出预留是什么关系？
- 如果 N 大于全量预算公式里的“输出预留 8k”，则全量预算公式本身就需要改；否则模型按 N 输出时可能突破窗口。
- 模型不保证遵守自然语言 token 约束，API `maxTokens` 才是硬约束。

预期效果表第 100 行“1 次”应写成“1 次全量输入；输出在 N/maxTokens 约束下成立”，并说明输出超限时的退化路径。

### P1-5 “顺带修正 2”与“不动 DynamicAuxChatModel”冲突

`DynamicAuxChatModel` 每次调用会用 `active.options(...)` **整体替换 prompt options**：

```java
private Prompt withActiveOptions(Prompt prompt, LlmProvider active) {
    return new Prompt(prompt.getInstructions(), active.options(registry.activeModelId()));
}
```

（`DynamicAuxChatModel` 48–49 行）

因此，想在摘要调用上设置更大的 `maxTokens`，不能只在 `auxClient.prompt().options(...)` 上做；它会被丢弃。而 `AuxClientNotVisionWrappedTest` 又断言 `AgentTools.auxChatModel()` 返回的**恰好是裸 `DynamicAuxChatModel`**（测试 63–67 行），不能随意包装。

可选的落地路径只有：

1. 修改 `DynamicAuxChatModel`，允许摘要路径传入并合并 options 覆盖（需同步修订“不动”的范围声明）；
2. 摘要路径不走 `auxClient`，新增专门的摘要 ChatModel/options 装配（实施范围未写，且需要更新守卫测试）。

当前代码里 `AnthropicProvider.MAX_TOKENS = 8192`（`AnthropicProvider` 26、97–102 行），摘要输出实际上已被硬限制在 8192。如果设计最终取 `N <= 8192`，Anthropic 路径可能无需加大；但其他 provider 仍需要显式 `maxTokens`，不能只靠 prompt 文案。

### P1-6 数字解析器会选错数字

第 66–68 行举的 Anthropic 例子 `195300 tokens > 200000 maximum` 包含两个数字：请求量和窗口上限。若解析规则是“找第一个数字”，会解析成 195300。OpenAI、DeepSeek 的完整错误通常也同时包含“当前 tokens”和“最大 tokens”。

解析器必须：

- 锚定“maximum context length is …”或 `> … maximum` 中的窗口数字；
- 增加两个数字、无数字、数字带逗号、负数、减预留后小于最小预算等负例；
- 遍历异常 cause chain 再匹配（Spring AI 通常把服务端错误包在通用 RuntimeException 里）；
- 解析结果做 clamp，避免 `数字 − 预留 <= 0` 时进入非法预算。

另外建议补中文错误消息变体（Zhipu/Qwen 等）。

### P1-7 现有测试基线与新不变量冲突

`ContextStatsTest` 中多个测试直接断言“悲观预分片”行为：

- `boundedStrategyNeverSendsMoreThanChunkBudgetToSummarizer`（测试 102–125 行）断言每次摘要输入 `<= chunkBudget` 且调用次数 `> 1`；
- `boundedStrategyCanRescueSingleOversizedEvent`（测试 177–195 行）断言单条巨型事件被拆成多个 `<= 200` 的请求。

乐观全量算法直接推翻这些断言。因此“现有测试基线（须全绿）”中的 `ContextStatsTest` 不可能原样全绿，除非：

- 旧构造器保留悲观模式，测试继续走旧路径；或
- 明确将这两个测试标记为“随新语义调整”。

文档需要区分“兼容保留的旧测试”和“随语义修改的旧测试”，并给出新断言。

---

## 四、P2：小问题与措辞

1. **目标预算数字不精确**：文档第 31 行写 0.55W，实际是 `max(8k, 0.55W − MEDIA_MANIFEST_TOKEN_RESERVE)`；1M 窗口时为 534k，不是 550k。
2. **“失败请求秒级且不计费”是未验证假设**：不同 provider/网关对超限请求的拒绝延迟和计费策略不同。建议写“需在实施前逐 provider 核实”，或设计成即使计费也可接受的成本上限。
3. **质量红利是假设不是结论**：第 106 行“单次全量摘要无跨块割裂”并不必然推出质量更高；长上下文全量摘要也可能出现 lost-in-middle、细节遗漏或摘要输出过长。建议改为“预期质量红利”，并加一个小型质量评估。
4. **“块数已降到 2~3”只覆盖特定场景**：仅在“配置 1M、实际 400k 且校准成功”时成立；无数字减半可能是 3 块，更小窗口可能是 3~8 块。
5. **`estimate(text) > 全量预算` 分支缺失**：当归档量超过“配置窗口 − 预留”时，主算法第 2 步没有 fallback。手动 `/compact` 的保留窗口更小，归档量更容易接近或超过全量预算，需写清直接按全量预算切块。
6. **安全阀边界未定义**：`budget < 16k` 还是 `<= 16k`；第 4 次减半是允许尝试还是直接兜底；解析出的数字被 clamp 后应该尝试还是直接兜底。
7. **缺可观测性**：建议记录校准尝试次数、`knownGood/knownBad` 变化、安全阀触发原因、全量失败后实际走了哪条分支。
8. **`provider:model` 读取存在快照风险**：`DynamicAuxChatModel` 注释声称单次读取，但实际先 `registry.active()`，随后又调 `registry.activeModelId()`（源码 37、49 行）；`AgentTools.contextWindow` 也是两次读取。校准 key 和窗口预算必须来自同一次快照，否则 `/model` 切换交错时会记错 key 或配错预算。

---

## 五、建议修改后的算法骨架

以下不是最终实现，只用于把评审中要求回答的问题固定下来：

```text
text = format(archived)
E = estimate(text)
key = snapshot(provider:model)          # 一次快照，同时拿窗口和模型
interval = state.get(key)
fullBudget = configuredWindow - promptReserve - outputReserve

# 1. 已证明安全：直接全量
if E <= interval.knownGood:
    return summarizeFull(text)

# 2. 已证明会失败：不要再试全量，直接按 knownGood 切
if interval.knownBad != null and E >= interval.knownBad:
    return summarizeChunked(text, budget = interval.knownGood or conservativeFallback)

# 3. 无 knownBad 且配置预算装得下：乐观全量一次
if E <= fullBudget:
    try:
        return summarizeFull(text)       # 成功 → knownGood = max(knownGood, E)
    except limitError as ex:
        state.updateKnownBad(key, E)
        budget = parseWindow(ex) or halveBudget(...)
        # 明确：下面是探测还是正式切块？结果是否复用？

# 4. 有预算后正式切块
if chunkCount(E, budget) > MAX_CHUNKS:   # MAX_CHUNKS = 8
    return localDigest(...)
return summarizeChunked(text, budget)     # 全部成功才更新 knownGood
```

文档还必须回答：

- `summarizeChunked` 中某一块失败时，是整体兜底、重试失败块，还是降预算全量重跑；
- `knownGood` 的更新值：`budget`、最大成功 chunk estimate，还是 `min(budget, maxChunk)`；
- 探测/校准调用与正式摘要调用是否共用一个 `remainingCallBudget`；
- 输出超过 `chunkBudget` 后的合并再压缩调用如何计入总上限。

---

## 六、预期效果表建议修正

| 场景（归档 433k） | 原表 | 建议修正 |
|---|---|---|
| 配 1M、实际 1M | 1 次 | 1 次全量输入；输出需在 N/maxTokens 约束内 |
| 配 1M、实际 400k，错误带数字 | 1 次秒级失败 + ~2 次 | 1 次失败 + ~2 次切块（总 3 次），成立 |
| 配 1M、实际 400k，错误无数字 | 1 次失败 + 减半成功（共 2 次） | 1 次失败 + 2~3 次切块（总 3~4 次） |
| 第二次及以后压缩 | 直接按 knownGood 切，零试错 | 只有补上 `knownBad` 短路分支后才成立 |
| 触发安全阀 | localDigest | 成立，但需定义安全阀边界和全局调用上限 |

---

## 七、测试计划补充

除原文档测试 1–7 外，建议增加：

1. `knownBad` 短路：已知 `knownBad` 时，第二次相同归档绝不发全量请求。
2. 区间中间态：`knownGood < estimate(text) < knownBad` 时的确定行为。
3. 数字解析负例：两个数字、无数字、带逗号、异常 cause chain、解析后预算非法。
4. 部分失败：多块运行中第二块超限/网络错误，断言 summary 复用、状态更新和兜底行为。
5. 输出超限：全量成功后 `merged > chunkBudget` 或 `> summaryBudget`，验证再压缩/`localDigest` 路径。
6. 并发校准：同一 key 并发压缩，验证区间单调且不出现 `knownGood > knownBad`。
7. auto/manual 状态共享：auto 学习后，manual 首次压缩零试错。
8. 状态重置：测试间清理校准状态的机制。
9. 快照一致性：`/model` 切换与压缩并发时，校准 key 与请求模型同源。

同时把 `ContextStatsTest` 中两个悲观分片断言明确归入“随新语义调整”。

---

## 八、验收清单

- [ ] 主算法伪代码加入 `knownBad` 短路分支。
- [ ] 无数字减半分支的探测/正式调用关系写清，并重算调用次数。
- [ ] `BoundedSummarizationCompactionStrategy` 新构造器、key snapshot、`CalibrationState` 生命周期与 auto/manual 共享方式定义完成。
- [ ] `knownGood/knownBad` 的更新公式、并发合并规则、测试 reset 机制定义完成。
- [ ] 单次压缩的全局调用上限给出具体数字，并覆盖合并再压缩循环。
- [ ] 输出预算 N、`maxTokens`、8k 输出预留三者关系写清；`DynamicAuxChatModel` 的修改边界修订。
- [ ] 数字解析规则覆盖双数字/无数字/非法数字，并锚定窗口数字。
- [ ] 预期效果表按新算法重新计算。
- [ ] 测试清单区分“保留旧测试”“调整旧测试”“新增测试”。
- [ ] 失败请求的计费/延迟假设标注为待核实，或改为可接受成本上限。

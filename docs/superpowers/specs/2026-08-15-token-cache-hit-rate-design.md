# Token 缓存命中率统计设计

**日期**：2026-08-15
**范围**：`springai-code-tui` · `io.github.javaside.springai.codetui.agent`
**参考**：`deepseek-harness`（`packages/llm/token-meter` + `packages/client/ui-conversation/.../StatsLine.tsx`）的缓存命中率实现。

---

## 目标（一句话）

统计**当前会话累计**的 token 缓存命中率，在 `/context` 报告与状态栏两处展示，覆盖全部 6 家 provider
（DeepSeek / OpenAI / Anthropic / Qwen / Zhipu / OpenCode Go），复用 Spring AI 2.0 已标准化的 `Usage` 缓存字段。

## 参考实现（deepseek-harness）怎么做的

三层结构，本项目对齐其「口径」与「单点采集」两点：

1. **统一口径**：`TokenUsage` 用不相交桶 `inputTokens`（未命中缓存的输入）/ `outputTokens` /
   `cacheReadTokens`（读缓存）/ `cacheWriteTokens`（写缓存）。
2. **provider 适配器**把各家 wire usage 翻译成该口径——DeepSeek 的 `prompt_tokens` 已含缓存命中，故
   `cacheRead = prompt_tokens_details.cached_tokens`、`inputTokens = prompt_tokens - cacheRead`。
3. **会话级投影累加 + 显示**：按 turn/step **去重累加**（同一步用最后一次替换，避免流式分片重复计数），
   命中率 = `cacheRead / (uncachedInput + cacheRead + cacheWrite)`，分母即**总计费输入 token**。

本项目无需自己翻译 wire usage：**Spring AI 2.0.0 已把该口径标准化**，`Usage` 接口原生带
`getCacheReadInputTokens()` / `getCacheWriteInputTokens()`。命中率公式可化简为
`cacheRead / getPromptTokens()`（各家的 `prompt_tokens`/`input_tokens` 均已含缓存，分母即总计费输入）。

## 为什么这么切

所有模型调用最终都经过 `LlmProvider.chatModel()`，是天然单一采集点：

| 调用路径 | 装配点 | 底层模型来源 |
|---|---|---|
| 主 agent | `AgentTools.build` 的 clients 循环（`InterjectingChatModel.wrap(visionModel, ...)`） | `provider.chatModel()` |
| 子 agent（Task/ParallelTasks） | `SubagentRunner`（`RetryingChatModel.wrap(selection.provider().chatModel())`） | `provider.chatModel()` |
| 摘要 / 网页抽取（aux） | `DynamicAuxChatModel`（`registry.active().chatModel()`） | `provider.chatModel()` |

在 `chatModel()` 外包一层采集装饰器，一处接线即可覆盖三条路径，且与项目既有的
`VisionMaterializingChatModel` / `InterjectingChatModel` / `RetryingChatModel` / `DeepSeekThinkingChatModel`
装饰器模式同构，可离线单测。

## 设计

### 新组件 1：`CacheUsageExtractor`（纯函数，无状态）

入参 Spring AI `Usage`，出参 `(cacheRead, cacheWrite)` 两个 `long`（缺省为 0）。

- 优先读 `usage.getCacheReadInputTokens()` / `usage.getCacheWriteInputTokens()`——OpenAI / Qwen / Zhipu
  （`OpenAiChatModel` 已填 `prompt_tokens_details.cached_tokens`）与 Anthropic（已填
  `cache_read_input_tokens` / `cache_creation_input_tokens`）直接可用。
- 二者为 null 时兜底 `usage.getNativeUsage()`：
  - 若为 `org.springframework.ai.deepseek.api.DeepSeekApi.Usage`，取 `promptTokensDetails().cachedTokens()` 作 `cacheRead`
    （DeepSeek 的 `DeepSeekChatModel.getDefaultUsage()` 未填缓存字段，但原生 usage 里带着数据）。
  - 否则保持 0。
- `usage == null` / `EmptyUsage` / `promptTokensDetails() == null` 一律按 0，**绝不抛**。

### 新组件 2：`TokenUsageAccumulator`（线程安全，会话级，有状态）

纯 Java 累加器（不泄漏 Spring AI 类型，遵守 CodingAgent→UI 接缝纪律），字段：

```
private final LongAdder promptTokens;     // 计费输入（含缓存）
private final LongAdder completionTokens; // 输出
private final LongAdder cacheReadTokens;  // 读缓存
private final LongAdder cacheWriteTokens; // 写缓存
```

方法：

- `void record(Usage usage)`：`promptTokens`/`completionTokens` 直接读 `usage.getPromptTokens()`/`getCompletionTokens()`
  （null 视为 0），`cacheRead`/`cacheWrite` 由 `CacheUsageExtractor` 拆出，四桶原子累加。
- `Snapshot snapshot()`：不可变快照（4 个 `long`）。
- `void reset()`：清零（`/clear` 换新会话时调用）。

`Snapshot` 为纯 Java record，另带 `long billedInputTokens()`（= promptTokens，因 promptTokens 已含缓存）与
`Integer cacheHitPercent()`（`cacheRead / promptTokens × 100` 四舍五入；`promptTokens == 0` 返回 null）。

### 新组件 3：`UsageRecordingChatModel`（`ChatModel` 装饰器）

- 构造：`UsageRecordingChatModel(ChatModel delegate, TokenUsageAccumulator accumulator)`。
- `.call(prompt)`：`delegate.call()` 拿到 `ChatResponse` 后 `accumulator.record(metadata.getUsage())` 一次再返回；
  `getUsage()` 为 null 则跳过。
- `.stream(prompt)`：`delegate.stream()` 上用 `doOnNext` 记住**最新** usage，`doFinally` 里 `record` **一次**
  （成功 / 报错 / 取消统一收口）。因 Spring AI 流式的每个 chunk 都带**累计** usage，最后一个 chunk 即完整值，
  只在流结束时提交一次即可杜绝重复计数。
- 其余 `ChatModel` 默认方法原样委托。

### 新组件 4：`UsageRecordingProvider`（`LlmProvider` 装饰器，采集端单点）

- 构造：`UsageRecordingProvider(LlmProvider inner, TokenUsageAccumulator accumulator)`。
- 除 `chatModel()` 外全部委托 `inner`；`chatModel()` 返回 `UsageRecordingChatModel.wrap(inner.chatModel(), accumulator)`
  （缓存该包装结果，因 `chatModel()` 契约是幂等单例）。

### 接线①（采集端）

在 `CodeTuiApplication.createProviderRegistry(...)`（`ProviderRegistry` 的**唯一构造点**）创建**一个**
`TokenUsageAccumulator`，并对每家 provider 包一层 `UsageRecordingProvider`，再放进 `new ProviderRegistry(...)`。

**为什么必须在 provider 级而非 `AgentTools.build` 的 clients 循环里包**：主 agent / 子 agent / aux 三条路径
各自独立调用 `registry.*().chatModel()`——主 agent 走 `AgentTools.build` 循环（`VisionMaterializingChatModel.wrap(provider.chatModel(), …)`）、
子 agent 走 `SubagentRunner`（`RetryingChatModel.wrap(selection.provider().chatModel())`）、aux 走
`DynamicAuxChatModel`（`registry.active().chatModel()`）。`ProviderRegistry` 构造后是不可变的，只在构造前包
provider 才能让这三条路径拿到**同一个**被装饰模型、共享同一累加器；在 `AgentTools.build` 循环里包只能覆盖主 agent，
会漏掉子 agent 与摘要。

累加器经 `createProviderRegistry` 的新入参由 `main` 创建并传入（现有 2 参重载保留、委托一个丢弃型累加器，
测试/旧调用点不受影响），随后 `main` 把它作为新构造参数交给 `CodingAgent`。

### 接线②（展示端）

- `CodingAgent` 持累加器引用：
  - `contextStats()` 把 `snapshot()` 的三个新字段（`cacheReadTokens` / `billedInputTokens` / `cacheHitPercent`）
    填进扩展后的 `ContextStats`；
  - `clearContext()` 同时 `accumulator.reset()`。
- `ContextStats` record 追加 3 个只读字段：`long cacheReadTokens`、`long billedInputTokens`、
  `Integer cacheHitPercent`（可空）。`empty()` 同步补 0 / null。
- `ContextUsage`：
  - `report()` 在「估算 token」行之后追加一行，仅当 `cacheHitPercent() != null`：
    `"  缓存命中率：X%（命中 A / 计费输入 B token）"`（`%,d` 格式化 A/B）。
  - `suffix()` 追加 `" · 缓存命中 X%"`，仅当 `cacheHitPercent() != null`。

## 行为契约

- **命中率** = `cacheReadTokens / promptTokens × 100`，`Math.round` 取整；`promptTokens == 0`（无计费输入）→ 无数据，
  `/context` 不打印该行、状态栏不加后缀。
- **会话累计口径**：跨 `/model` 切换、跨主/子/摘要调用全部混入同一总数，与 deepseek-harness 的 session 级一致。
- **`/clear` 清零**：换新会话后命中率从 0 重新累计（与旧会话事件文件保留的语义互不干扰）。
- **失败静默降级**：抽取 / 累加任何一步遇 null / 异常都不得影响主链路（display 侧只读快照，无副作用）。
- **流式只记一次**：`.stream()` 只在 `doFinally` 提交一次，绝不按 chunk 重复累加。

## 测试策略

1. **`CacheUsageExtractorTest`**（纯 Java）：OpenAI 直取（`getCacheReadInputTokens` 已填）、DeepSeek 兜底
   （`getNativeUsage` → `DeepSeekApi.Usage`，断言 `cachedTokens` 被取到）、空 usage / `EmptyUsage` 全 0、
   nativeUsage 非 DeepSeek 类型时全 0。
2. **`TokenUsageAccumulatorTest`**：多次 `record` 累加正确、`snapshot()` 不可变、`cacheHitPercent` 舍入
   （含分母 0 → null）、`reset()` 清零、并发 `record` 线程安全（简单并发压测）。
3. **`UsageRecordingChatModelTest`**：`.call` 记一次；`.stream` 多 chunk（逐个累计 usage）只记**最后**一次；
   流报错 / 取消时仍提交（`doFinally`）。
4. **`ContextUsageTest` 扩展**：喂含缓存字段的 `ContextStats`，断言 `/context` 新行文案与「有数据才显示」分支；
   无数据快照不打印、状态栏 `suffix()` 不追加。

## 非目标（本步不做）

- 不做按 provider 分组的命中率（YAGNI，需要再议）。
- 不改压缩/摘要/视觉等既有行为；不动 `Usage` 的 wire 翻译（Spring AI 已做）。
- 不引入 Micrometer / 外部指标系统；不落盘历史命中率（只进程内会话级）。
- 不改状态行其余后缀、不改 `ContextStats` 既有字段语义（只新增字段）。

## 风险与回滚

- 风险低：新增 4 个类（含 1 个 `Snapshot` record）+ 3 个字段 + 2 处接线 + 少量测试；唯一易错点是流式「只记一次」
  （`doFinally` 提交，已有专项测试覆盖）与 DeepSeek 兜底（`getNativeUsage` 强转前判类型）。
- 回滚：单 commit `git revert`；新增类删除 + 还原 `ContextStats`/`CodingAgent`/`ContextUsage`/`CodeTuiApplication` 即恢复。

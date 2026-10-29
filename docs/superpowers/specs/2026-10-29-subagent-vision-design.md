# 子 agent 视觉接线（subagent-vision）设计

日期：2026-10-29 · 分支：`feature/subagent-vision` · 状态：待用户审阅

## 1. 问题

委派子 agent（Task/ParallelTasks/后台）截图或读图时，无论指定哪个模型（含视觉白名单内的
`glm-5.3-flash`、`deepseek-flash`），引用块一律写
`current model has no image input; re-reading will not show it`，子模型永远看不见图。

根因是**两处接线缺口**（均已在会话中实测核实，非推测）：

| # | 半边 | 主 agent 现状 | 子 agent 现状 |
| --- | --- | --- | --- |
| 1 | 入站：能力快照冻结进 ToolContext，决定引用块写 `not_in_view` 还是 `reference_only` | `CodingAgent.submit` 冻结 `provider.capabilities(modelId)` 进 `MediaExternalizingCallback.CAPABILITIES_KEY`（CodingAgent.java L595 附近） | `SubagentRunner.execute` 的 toolContext 只有 turnId/taskId → `capsOf` 兜底 `TEXT_ONLY` → 恒写 `reference_only` + 拒看文案 |
| 2 | 出站：把引用兑现成真 `Media` 字节 | `VisionMaterializingChatModel` 包在每 provider 主链 ChatClient 外（AgentTools.java L785，「视觉兑现的唯一接线点」） | 子链 = `RetryingChatModel.wrap(provider.chatModel())`，无 Vision 层，引用永不物化 |

`git log -S "VisionMaterializingChatModel" -- SubagentRunner.java` 为空：子链从未接过视觉层。

## 2. 目标

- 子 agent 按其**实际派发解析到的 provider+model** 获得与主 agent 同语义的视觉链路：
  工具结果里的图片引用被如实标注（视觉模型 `not_in_view`、文本模型 `reference_only`），
  且视觉模型的出站请求把当轮引用兑现成真 `Media`。
- 前台串行、ParallelTasks 并行、后台派发三条路行为一致（三者共用 `execute()`）。
- 额度与主 agent 同一 `VisionBudget` 实例、按 turnKey 分桶（并发子 agent 互不冲额度）。

## 3. 非目标

- 不给子链挂 `InterjectingChatModel`（插话是主 agent 特性）。
- `/context` 视觉统计不纳入子 agent：`CodingAgent.snapshotOf` 只读主链 visionModels；
  子 agent 每次派发新建装饰器实例，无长期快照可言。
- 不改任何提示词文案：引用块自身已自描述（`not_in_view` ↔ Read 可见），
  `ARTIFACT_GUIDANCE`（报告里写 artifact 路径）保持不变。
- 不新增环境开关：`CODETUI_VISION=off` 已内建于 `VisionModels.supportsImage` /
  各 provider `capabilities()`，对子链天然生效。
- 会话存储、附件链路（入口 A/C）、aux 摘要链路一概不动。

## 4. 已核准的事实依据（全部有出处）

1. `VisionMaterializingChatModel` javadoc（L22-26）明写设计意图：
   「**call 与 stream 都要走兑现**：主 agent 走 stream，**子 agent 走 call**（经 RetryingChatModel
   桥接到流式）。只改一条等于子 agent 没有视觉」「模型 id 取自出站 Prompt 的 options……
   子 agent 可能跑在另一家 provider 上，这样判定天然正确」。其 `call()` 已实现兑现 +
   400 拒图降级（L88-100）——该分支此前只有子 agent 场景可触达，装饰器侧**零改动**。
2. `RetryingChatModel.call()` 桥接 `delegate.stream()` 并聚合（L124/L171），重试发生在
   桥内——Vision 放其外层时，重试复用同一已物化 Prompt（与主链「RetryStream 在 Vision 内」
   的 L1 论证同构，AgentTools 装配段注释）。
3. `RequestSelection(LlmProvider provider, String modelId, ThinkingConfig, ChatOptions)`
   已携带 modelId（ProviderRegistry.java L203）。
4. `VisionBudget` 按 turnKey 分桶的注释原话：「ChatModel 实例被主 agent 与所有子 agent 共用，
   并发子 agent 若共用一个计数器会互相冲掉对方的额度」——共享单实例 + 分桶正是原设计。
5. 装配点（AgentTools L566 建 `visionBudget`、L593 构造 `SubagentRunner`）两个值均在作用域内。
6. 子 agent 一次 `execute()` 内工具循环复用同一 ChatClient → 同一装饰器实例 →
   materializer 高水位/拒绝集/sha 去重跨迭代连续，turnKey 稳定（锚点 = 子任务 user prompt）。
7. 六家 provider 的 `capabilities(modelId)` 均覆写为 `VisionModels.supportsImage`（OpencodeGo
   例外：官方名单 && enabled）——冻结口径与主 agent 完全同源。

## 5. 设计

### 5.1 SubagentRunner

- 构造函数追加两个可空参数 `Path root`、`VisionBudget visionBudget`（沿既有重载链，
  旧签名委托新签名传 null = 完全不接线，既有测试零影响）。
- `execute()` 内（resolveSelection 之后），**两半同一闸门**（root 与 visionBudget 均非 null 才动，
  任一为 null → 维持现状完全旧行为——只冻结 caps 而出站不物化会造出「not_in_view 但永远看不见」
  的白 Read 假话，两半必须一起开，见备选 C）：
  1. **caps 冻结**：`selection.provider().capabilities(selection.modelId())` 写入
     toolContext 的 `MediaExternalizingCallback.CAPABILITIES_KEY`（新建 HashMap 合并原
     turnId/taskId 各键，原 Map.of 不可变）。
  2. **出站接线**：

```
ChatModel base   = RetryingChatModel.wrap(selection.provider().chatModel(), quotaHook);
ChatModel vision = VisionMaterializingChatModel.wrap(base, root,
        modelId -> selection.provider().capabilities(modelId).supportsImageInput(),
        visionBudget,
        ImageProfile.forProvider(selection.provider().id()));
```

  能力谓词按 provider 注入（与主链同法，避免兼容网关同名模型串用全局能力）；
  ImageProfile 按 provider 取口径（统一 Anthropic 口径会让 DeepSeek token 高估 3.6 倍）。
  root/budget 任一为 null → 维持现状裸链（等价行为退出门）。

- 每次派发新建 Vision 包装实例：materializer 的拒绝集/高水位是**会话态**，跨派发共享会把
  上一派发的 400 拒绝带进下一派发（那张图永不再看）；预算是**进程态**，走共享实例。

### 5.2 AgentTools 装配

L593 构造点传入 `root` 与 L566 的 `visionBudget`（与 `MediaExternalizingCallback`
共享同一实例——出站扣的额度工具侧才看得见，这是该实例存在的全部理由）。

### 5.3 链序与不变量

```
子链：Vision(Retrying(Usage(IdleTimeout(raw))))     ← 新
主链：Interjecting(Vision(RetryStream(Usage(IdleTimeout(raw)))))  ← 既有，对照
```

- Vision 最外：`call()` 入口兑现一次，Retrying 的瞬态重试复用已物化 Prompt；
  400 拒图由 Vision.call 的降级分支处理（400 非瞬态，Retrying 直接放行）。
- 子 agent 无 SessionMemory advisor，「兑现点必须在会话记忆之后」自动成立
  （装饰器在 advisor 链下游，出站即兑现，不落存储）。
- 后台派发（turnId=-1）同走 `execute()`，行为一致。

## 6. 被淘汰的备选

- **Vision 包在 Retrying 内**（Retrying(Vision(raw))）：与 javadoc「子 agent 走 call」矛盾
  （该序下子 agent 实际走 Vision.stream()），且每次瞬态重试重跑物化、预算重复扣；淘汰。
- **每 provider 预建 Vision 实例挂 registry**：RequestSelection 按次解析；且实例含会话态
  （拒绝集/高水位），跨派发共享语义错误（见 5.1）；淘汰。
- **只修 caps 不接出站**：引用块会写 `not_in_view`（「Read 一次就能看」）而出站不物化——
  精确复现 2026-08 修掉的「贴了图说看不到、模型白 Read」假话事故；两半必须同提交；淘汰。
- **新增子 agent 视觉开关（env）**：`CODETUI_VISION` 已覆盖，多一个开关只会制造第二真相源；淘汰。

## 7. 测试策略（详见 plans/2026-10-29-subagent-vision.md，全部先红后绿）

1. **caps 冻结**：stub provider 覆写 `capabilities` + 捕获 toolContext 的假工具；断言
   视觉模型派发 → key 值 `supportsImageInput()==true`；文本模型 → false；旧构造（未接线）→ 无 key。
2. **出站兑现**：派发 prompt 内嵌 `FileReference.render` 引用块（指向临时 root 下真实 PNG）；
   视觉模型 → 捕获到的出站 Prompt 含真 `Media`；文本模型 → 无 `Media` 且引用块 delivery 仍
   `reference_only`（不许被改写成 delivered）。
3. **预算共享**：外部注入的 `VisionBudget` 实例在子 agent 兑现后对应桶被扣减
   （工具侧「额度已尽」口径与出站一致）。
4. **回归**：既有 subagent 全家（串行/并行/后台/思考配置/MCP 工具）+ `AuxClientNotRetryWrappedTest`
   等守卫不动；`mvn -pl springai-code-tui -am test` 全量绿。

## 8. 文档

- `implementation-map.md` §17.2「子 agent 经 RetryingChatModel 桥到 stream」一段从愿景变实装，
  补一句装配位置与「每次派发新建实例、预算共享」的取舍。
- 发版说明/CHANGELOG 留到发版流程（本仓库惯例：合并 ≠ 发版）。

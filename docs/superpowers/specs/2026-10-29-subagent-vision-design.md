# 子 agent 视觉接线（subagent-vision）设计

日期：2026-10-29 · 分支：`feature/subagent-vision` · 状态：待用户审阅（v3）

## 0. 修订记录

**v1 → v2**：经独立审核，补上阻断项 B1（turnKey 跨派发碰撞）、B2（toolContext 漏拷
`backgroundTaskId`）、I2（`lastTurnKey` 是进程全局）、I5/I6（生产装配守卫、接缝收敛）。

**v2 → v3**：把 v2 的「scope 化 turnKey + release 生命周期」整个换成**预算实例进 ToolContext**。
原因：v2 要动 `VisionBudget`（+4 个 API、调常量）、`VisionMaterializer`（+scope 字段、turnKey 前缀）、
`VisionMaterializingChatModel`（+wrap 重载）、`MediaExternalizingCallback`（scope 解析），四处联动；
而 v3 让**每次派发自带一个 `VisionBudget` 实例**并通过 ToolContext 传给工具侧，同样的三个问题
（跨派发累计、并发串桶、工具侧读错桶）**同时消失**，且上述三个类**零改动**。
审核的备选 (b) 已有此意，但缺了「工具侧怎么拿到它」那一半——补上即可。

## 1. 问题

委派子 agent（Task / ParallelTasks / 后台）截图或读图时，引用块一律写
`current model has no image input; re-reading will not show it`，子模型永远看不见图——
即使指定视觉白名单内的 `glm-5.3-flash` / `deepseek-flash`（`VisionModels.VISION_PREFIXES`）。

两处接线缺口（均已在代码中核实）：

| # | 半边 | 主 agent | 子 agent |
| --- | --- | --- | --- |
| 1 | 入站：能力快照决定引用块写 `not_in_view` 还是 `reference_only` | `CodingAgent.submit` 把 `provider.capabilities(modelId)` 冻结进 ToolContext（CAPABILITIES_KEY） | `execute()` 的 toolContext 只有 turnId/taskId → `capsOf` 兜底 `TEXT_ONLY` → 恒写 `reference_only` + 拒看文案 |
| 2 | 出站：引用兑现成真 `Media` 字节 | `VisionMaterializingChatModel` 包在每 provider 主链（AgentTools L785） | 子链 = `RetryingChatModel.wrap(provider.chatModel())`，无 Vision 层 |

`git log -S "VisionMaterializingChatModel" -- SubagentRunner.java` 为空：从未接过。

## 2. 目标

1. 子 agent 按**实际解析到的 provider+model** 获得与主 agent 同语义的视觉链路：引用块如实标注
   （视觉模型 `not_in_view`、文本模型 `reference_only`），视觉模型的出站请求把当轮引用兑现成真 `Media`。
2. **额度按「派发」隔离**：同一 prompt 重跑、并行多派发、后台派发互不累计，派发结束天然回收。
3. 工具侧「额度已尽」的判断落到**发起该次工具调用的那个 agent**，而非进程里最后一次模型请求。
4. 前台串行 / ParallelTasks / 后台三条路一致（共用 `execute()`）。

## 3. 非目标

- 不给子链挂 `InterjectingChatModel`（插话是主 agent 特性）。
- **`/context` 视觉统计不含子 agent**（`CodingAgent.snapshotOf` 只读主链 visionModels）。
  **已知盲区**：一次 `ParallelTasks` 派 4 个子 agent、各 12 张图 = 48 次投递在 `/context` 完全不可见，
  而子 agent 恰恰是截图大户。范围上合理延后，但**必须记为 follow-up**，不能以「无长期快照」当理由。
- **不扩宽 400 拒图降级到 Anthropic/Qwen SDK 类型**：`isImageRejected400` 硬判
  `com.openai.errors.BadRequestException`，故降级只对 OpenAI-SDK 系（OpenAI/DeepSeek/智谱/通义/
  opencode-go）成立；`claude-*` 原生路径遇图 400 仍死回合。既有缺陷，本次不动。
  **注意：该降级分支此前无任何生产路径触达，本次接线是它第一次进入生产热路径。**
- **MCP 工具的媒体外置仍无 budget**（`McpRegistry` 四参构造，budget=null）。v3 的 ctx 取预算
  **不覆盖**它（MCP 装饰器没收到含 budget 的 ctx？——它能收到 ctx，但构造为 null 时仍走
  `turnExhausted()==false`）。列为 follow-up，见 §9。
- 不改任何提示词文案（残留见 §9）。
- 不新增环境开关：`CODETUI_VISION=off` 已内建于 `VisionModels.supportsImage` 与各 provider
  `capabilities()`，对子链天然生效。

## 4. 已核准的事实依据

1. `VisionMaterializingChatModel` javadoc L22-26 明写意图：「call 与 stream 都要走兑现：主 agent 走
   stream，**子 agent 走 call**（经 RetryingChatModel 桥接到流式）」「模型 id 取自出站 Prompt 的
   options——子 agent 可能跑在另一家 provider 上，这样判定天然正确」。`call()`（L88-100）已实现
   兑现 + 400 降级，装饰器侧零改动。**但该 `call()` 分支此前无生产路径触达**（主链走 stream；
   aux 有 `AuxClientNotVisionWrappedTest` 守卫；goal 评估器不包 Vision），仅单测覆盖。
2. `RetryingChatModel.call()` 桥接 `delegate.stream()` 并聚合（L124/L171），重试循环在其内部
   （L120-165）⇒ Vision 放外层时**一次请求只物化一次**，重试复用同一已物化 Prompt。
3. `RequestSelection(LlmProvider provider, String modelId, ThinkingConfig, ChatOptions)`
   （ProviderRegistry L203）已携带 provider 与 modelId。
4. **`VisionBudget` 没有任何进程级上限**：`MAX_REQUEST_TOKENS`/`MAX_*_IMAGES` 是每请求、
   `MAX_*_TURN_DELIVERIES` 是每 turnKey。共享实例的唯一理由是**被迫**——`MediaExternalizingCallback`
   装配期建一次、持有固定 budget 引用，出站必须用同一实例，工具侧才看得见扣减。
   **v3 用「预算随 ToolContext 走」解除这个绑定**，于是每派发可自带实例。
5. `VisionMaterializer` 的 turnKey = `hashCode(锚点文本) + ":" + 锚点下标`；子 agent 锚点**恒为 1**、
   文本即派发 prompt ⇒ 同一 prompt 重跑会命中同一个桶，而 `VisionBudget` 的桶只在表满 8 个时
   整体 `clear()`、从不按派发回收（累计 12 张后**永久** `turn_budget_exhausted`，而文案还劝它
   「start a new turn」——子 agent 无法开新回合）。**这是 v1 的阻断缺陷，v3 用实例隔离根除**：
   新实例的 `perTurn` 表是空的。
6. 装配点：`AgentTools` 的 build 形参 `root`、L566 建 `visionBudget`、L593 构造 `SubagentRunner`；
   `AgentRuntime.subagentRunner()` 已暴露（端到端守卫可用）。
7. 一次 `execute()` 内建一个 ChatClient、只发一条 user（L313-327）⇒ 同一派发内工具循环复用同一
   装饰器实例、锚点恒为 1、turnKey 稳定；**每派发新建 Vision 实例 = 每派发一个独立 materializer
   （拒绝集/高水位/快照都是实例态）**。
8. 六家 provider：**五家**（OpenAi/Anthropic/DeepSeek/Zhipu/Qwen）的 `capabilities()` 返回
   `VisionModels.supportsImage(modelId)`；**OpencodeGo 独立口径**（自己的白名单 &&
   `VisionModels.enabled()`）；registry 里持有的是两个委托 wrapper
   （`UsageRecordingProvider`/`StreamIdleTimeoutProvider`），capabilities 透传。
9. `ToolContext` 已被用来携带对象（`CAPABILITIES_KEY` 塞 `ModelCapabilities`），塞 `VisionBudget`
   是同一既有模式，不引入新的机制类。

## 5. 设计

### 5.1 核心机制：预算随 ToolContext 走

```
主 agent：  编码时建一个 VisionBudget（装配期）→ 装饰器持有它
            工具侧（MediaExternalizingCallback）构造注入同一实例（既有行为，不动）

子 agent：  每次派发 new 一个 VisionBudget
            ├─ 交给 decorate(...) 建该派发的 VisionMaterializingChatModel
            └─ 塞进该派发的 ToolContext（BUDGET_KEY）
            工具侧优先从 ToolContext 取 budget，取不到才回退构造注入
```

三个问题因此同时消失：

| 问题 | 为什么消失 |
| --- | --- |
| 跨派发累计（B1） | 新 `VisionBudget` 的 `perTurn` 表为空；派发结束实例被 GC，无需 release |
| 并发串桶（I2 的 `lastTurnKey`） | 每个子 agent 的 budget 只被自己使用，`lastTurnKey` 恒指向自己的桶 |
| 工具侧读错桶（I2） | 工具从 ctx 拿到「我这一次派发的 budget」，不再查进程全局 |
| 表满 8 个整体清空 | 主 budget 仍只有主 agent 的回合（既有行为不变）；子 budget 各自最多 1 个 key |

**主 agent 路径零改动**：它不塞 `BUDGET_KEY`，工具侧回退到构造注入的实例，行为与今天完全一致。

额度口径也随之更合理：子 agent 的 12 张工具图额度与主 agent 的互不侵占（此前若共享，一轮
`ParallelTasks` 会把主 agent 的额度吃光）。

### 5.2 SubagentRunner

- 构造函数追加**一个**可空参数 `SubagentVision vision`（沿既有重载链，旧签名传 null =
  完全不接线，既有测试零影响）。不用两个裸参数：root 与 budget 必须同生共死（见备选 D）。
- `execute()` 内（resolveSelection 之后）：

```java
// 每次派发自带预算；null 时保持旧行为
VisionBudget dispatchBudget = vision == null ? null : new VisionBudget();

// (a) caps + 预算冻结进 ToolContext：整表复制，绝不列举键
//     （漏掉 BACKGROUND_TASK_ID_KEY 会让后台审批静默变成阻塞询问，见 B2）
Map<String, Object> ctx = new HashMap<>(toolContext);
if (vision != null) {
    ctx.put(MediaExternalizingCallback.CAPABILITIES_KEY,
            selection.provider().capabilities(selection.modelId()));
    ctx.put(MediaExternalizingCallback.BUDGET_KEY, dispatchBudget);
}

// (b) 出站接线
ChatModel base  = RetryingChatModel.wrap(selection.provider().chatModel(), quotaHook);
ChatModel model = vision == null ? base : vision.decorate(base, selection, dispatchBudget);
```

- (a)(b) 由**同一个 `vision != null`** 控制，结构上不可半开（只冻结 caps 不接出站会造出
  「not_in_view 但永远看不见」的白 Read 假话）。
- 不需要 release 钩子：预算实例的生命周期 = 一次派发。

### 5.3 接缝：`SubagentVision`（subagent 包，新类型）

```java
/** 装配层提供的视觉接缝。null 实例 = 不接线（完全旧行为）。 */
public interface SubagentVision {
    /** 给一次派发的 ChatModel 挂上视觉兑现；budget 由调用方持有并随 ToolContext 下发。 */
    ChatModel decorate(ChatModel base, ProviderRegistry.RequestSelection selection, VisionBudget budget);
}
```

- 「视觉怎么接」（root、ImageProfile、能力谓词）的知识留在装配点一处，执行器只负责调用与建预算。
- `SubagentRunner` 因此只新增两个 media 依赖：`VisionBudget` 与 `MediaExternalizingCallback` 的两个 key
  常量（`subagent → media` 是既有单向依赖）。

### 5.4 MediaExternalizingCallback

```java
public static final String BUDGET_KEY = "visionBudget";

/** 预算来源：本次工具调用的 ToolContext 优先（子 agent 各带各的），否则回退构造注入（主 agent）。 */
private VisionBudget budgetOf(ToolContext ctx) {
    if (ctx != null && ctx.getContext().get(BUDGET_KEY) instanceof VisionBudget b) return b;
    return visionBudget;   // 可空：null 时 turnExhausted() 恒 false（既有语义）
}
```

`externalize(...)` 把预算来源接进去（`turnExhausted()` 改为按本次调用的 budget 判断）。
现有四参/五参构造与主 agent 路径不变。

### 5.5 AgentTools 装配

L593 构造点传一个 `SubagentVision` 实现：

```java
(base, sel, budget) -> VisionMaterializingChatModel.wrap(
        base, root,
        id -> sel.provider().capabilities(id).supportsImageInput(),  // 按 provider 注入，勿用全局 VisionModels
        budget,                                                       // 该派发自有实例
        ImageProfile.forProvider(sel.provider().id()))                // 各家 token 口径不同
```

### 5.6 链序与不变量

```
子链：Vision(Retrying(Usage(IdleTimeout(raw))))                   ← 新增
主链：Interjecting(Vision(RetryStream(Usage(IdleTimeout(raw)))))  ← 既有对照
```

- Vision 最外：`call()` 一次物化，Retrying 的瞬态重试复用已物化 Prompt；400 非瞬态被 Retrying
  直接放行 → 由 Vision.call 的降级分支处理（仅 OpenAI SDK 系，见非目标）。
- 子 agent 无 SessionMemory advisor → 「兑现点在会话记忆之后」自动成立（装饰器在 advisor 链下游）。
- 每派发新建 Vision 实例的代价：同一张被服务端拒过的图，**每次派发都会重新上传并再吃一个 400**
  （主链是会话级拒绝集，子 agent 失忆）。可接受，仅提示「别指望跨派发省钱」。

## 6. 被淘汰的备选

- **A. Vision 包在 Retrying 内**：与 javadoc「子 agent 走 call」矛盾（该序下子 agent 实际走
  `Vision.stream()`），且每次瞬态重试重跑物化、预算重复扣。淘汰。
- **B. 每 provider 预建 Vision 实例挂 registry**：RequestSelection 按次解析；实例含会话态
  （拒绝集/高水位），跨派发共享语义错误。淘汰。
- **C. 只修 caps 不接出站**：引用块写 `not_in_view`（「Read 一次就能看」）而出站不物化——
  精确复现 2026-08 修掉的「贴了图说看不到」假话事故。两半必须同时开。淘汰。
- **D. 两个裸参数（root + budget）**：可半开——传 root 忘 budget 就是备选 C 的病，且无断言会响；
  参数表还膨胀到 10。淘汰，改用一个接缝。
- **E. 共享主 agent 的 budget + scope 化 turnKey（v2 方案）**：正确但改动面大（四个类联动），
  且仍需一个 release 生命周期来回收桶——比 v3 多两个失败面。淘汰。
- **F. 让工具侧自己去 registry 解析 scope**：工具拿不到「我属于哪次派发」的唯一标识
  （`taskId` 在后台路径与 registry 的 taskId 是两份），不如直接下发 budget 对象。淘汰。
- **G. 新开关（env）控制子 agent 视觉**：`CODETUI_VISION` 已覆盖，多一个开关 = 第二真相源。淘汰。

## 7. 测试策略（全部先红后绿）

**T1 caps + 预算冻结进 ToolContext**
- stub provider 覆写 `capabilities()`；假工具捕获收到的 `ToolContext`。
- 断言：视觉派发 → `CAPABILITIES_KEY.supportsImageInput()==true`；文本派发 → false；
  `BUDGET_KEY` 是一个**非主实例**的 `VisionBudget`；未接线（`vision=null`）→ 两个 key 都不存在。
- **后台派发用例必须断言 `BACKGROUND_TASK_ID_KEY` 仍在 ctx 里**（B2 回归，也是杀「列举键」变异的
  直接手段）。

**T2 出站兑现**
- stub 的 `options(modelId)` 必须是 `ToolCallingChatOptions.builder().model(modelId).build()`
  （否则 `materialize` 读不到 model、ToolCallingAdvisor 也跳过）。
- 引用块用 `FileReferenceParser` 可解析的格式（`kind: image` + `id` + `mime_type` + `path`，
  path 相对 root），PNG 60×40，无需 sha 校验。
- 视觉用例：出站 Prompt 含真 `Media`。
- **文本用例必须用「全局白名单内但 provider 判不支持」的 id**（如 `gpt-5.6-sol` + stub
  `capabilities()` 返回 false），否则把谓词错写成 `VisionModels::supportsImage` 这个变异杀不死。

**T3 派发级隔离（B1 回归，v1 设计下必红）**
- 同一 prompt 连续 `run()` 两次 → 第二次仍能兑现（v1 共享 budget 时第二次必因桶已满/残留而失败）。
- 并行两个派发，各自 `VisionBudget` 实例不同 → 一个耗尽不影响另一个。

**T4 工具侧预算归属（I2 回归）**
- `MediaExternalizingCallback` 单测：ctx 带 budget A → 读 A 的额度；ctx 无 budget → 回退构造注入；
  构造注入为 null（MCP 形态）→ 恒 false。
- 反向断言：ctx 里塞一个已耗尽的 budget，而构造注入的实例未耗尽 → 必须报「已耗尽」
  （证明 ctx 真的优先）。

**T5 生产装配守卫**
- `AgentTools.build(...)` → `rt.subagentRunner()` → `run(visionSpec, "看这个\n"+ref, ...)` →
  断言 spy provider 底层 `stream()` 收到的 Prompt 含 `Media`。
- 钉住 root 传递、predicate 注入、链序三件事——单测全绿而 AgentTools 少传参数的情况只有它能抓
  （同 `AuxClientNotVisionWrappedTest` 的理由）。

**T6 回归**：既有 subagent 全家（串行/并行/后台/思考/MCP）+ `AuxClientNotVisionWrappedTest`
+ `AuxClientNotRetryWrappedTest` + `AgentToolsRetryWiringTest`（只断言主链，不含子链）。

## 8. 文档

- `implementation-map.md` §17.2：把「子 agent 经 RetryingChatModel 桥到 stream」一段从愿景改为
  实装，写明装配位置、预算随 ToolContext 下发、每派发新建实例的取舍。
- `VisionMaterializer` 的 `accumulate` javadoc（L130-133）「代价是并发子 agent 的回合会覆盖主
  agent 的账」在每派发新实例后**不再成立**，顺手订正。
- 发版说明/CHANGELOG 留到发版流程。

## 9. 已知残留（刻意不在本次修）

1. `turn_budget_exhausted` 的文案「start a new turn to view it」对子 agent 不可执行（子 agent
   没有回合边界）。实例隔离后每次派发都有独立 12 张额度，实际很少撞到；仍属残留，follow-up
   改成不承诺具体动作的措辞。
2. `/context` 不含子 agent 视觉统计（§3 已知盲区）。
3. MCP 媒体外置的 `MediaExternalizingCallback` 构造 budget=null；v3 的 ctx 回退链**已为它留了
   通路**（只要 MCP 装饰时的 ctx 里有 `BUDGET_KEY` 就能生效），但主 agent 目前不塞该 key，
   故 MCP 路径的额度提示仍不准——follow-up：主 agent 也把 budget 塞进 ctx，即可一并修好。
4. Anthropic/Qwen 原生 SDK 的图片 400 不触发降级（§3）。

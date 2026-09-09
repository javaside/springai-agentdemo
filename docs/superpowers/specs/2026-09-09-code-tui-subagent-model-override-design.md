# code-tui 子 agent 委派模型选择设计

## 背景

`SubagentSpec` 已有 `model` 字段（来自 `agents/*.md` frontmatter），`SubagentRunner.resolveSelection` 会按 spec 解析请求模型，但只在装配子 agent 定义时静态生效一次，主 agent 在运行时没有任何手段指定。且 `spec.model()` 的文档写的是"支持 `provider:model`"，但 `SubagentRunner.resolveSelection` 的现有实现会把 provider 前缀截掉、只留 modelId 丢给 `ProviderRegistry.requestSelection(String)`，而后者在 modelId 不属于当前激活 provider 时**仍然**返回"激活 provider + 该 modelId"的组合（两个分支做同一件事，注释写的是"Keep the established v1 behavior"），实际上从来没有真正跨过 provider，也不会因为拼错而报错——文档承诺的行为和实际实现对不上。

今天全仓也没有任何代码会加载用户自定义 agent 文件（`SubagentLoader.loadBuiltins` 只读 4 个 classpath 内置 md），因此"预先为每个模型写一份几乎相同的 agent 定义文件"这条路今天走不通，也不允许主 agent 在运行时临时决定要对比哪些模型。

## 目标

1. 主 agent 调用 `Task` / `ParallelTasks` 时可按次指定某个子任务用哪个模型（`provider:modelId`），不依赖预先配置的 agent 文件。
2. 支持真正跨 provider（如 `openai:gpt-4o` → `deepseek:deepseek-chat`），不只是同一家换型号。
3. 顺手修复 `spec.model` 静态字段的跨 provider 解析——两条路径共享同一套解析代码，一次改到位。
4. 未知/不可用的模型请求要清楚报错，绝不能静默退化成别的模型。
5. UI（scrollback + 任务面板）显示每次子 agent 委派实际使用的模型。

## 非目标

- 不加载用户自定义 agent 文件（`.codetui/agents/`）——更大的独立功能，不是本次前提；将来做了这功能，`model` 覆盖机制对自定义 agent 同样自动生效，无需再改。
- 不加 `ParallelTasks` 批量级别的统一 `model`（覆盖全部子任务）——每个子任务已能各自指定，重复度不高，YAGNI。
- 不做模型清单运行期热更新——同现有 subagent 类型 roster 一样，装配期生成一次快照；provider 是否 available 只由启动时 API key 决定，不会运行期增减。
- 不改 `/model` 交互式选择器。
- 不碰 `ThinkingConfig`——换模型后自然按该模型自己已保存的思考配置走，逻辑不用动。
- 不支持子 agent 失败后换模型重跑（resume 到另一个模型）——`Task` 工具本就不暴露 resume，不在本次范围。

## 已确认的产品决策

1. 委派方式：新增按次动态参数（`Task`/`ParallelTasks` 的 `model` 字段），同时顺带修复静态 `spec.model` 字段的跨 provider 解析——两条路径共享同一套解析代码。
2. 参数格式：单字符串 `"provider:modelId"`，复用 `SubagentSpec.model` 已有的文档约定；不做 provider/model 两个独立字段（那样并不能真正消除裸 modelId 的歧义，只是多一个字段）。

## 方案比较

### 方案 A：按次动态参数 + 顺带修复静态路径（采用）

`SubagentCall` 新增可选 `model` 字段；路由层用它派生一个 `spec.withModel(...)`，其余全部复用现有 `SubagentRunner`/`ProviderRegistry` 解析链路。改动集中在 `ProviderRegistry` 的两个 `requestSelection` 方法、`SubagentSpec`、`SubagentTool` 路由函数，`SubagentRunner`/`Dispatcher`/`BackgroundDispatcher` 的方法签名不用动。

### 方案 B：只加动态参数，不修静态路径

省掉 `ProviderRegistry.requestSelection(providerId, modelId)`，静态 `spec.model` 继续沿用会丢 provider 前缀的旧实现。改动更小，但会让"动态覆盖能跨 provider、静态配置不能"这种不一致长期留在代码里，且两条路径本就该共享同一段解析代码，分开维护没有实际收益。不采用。

### 方案 C：只做静态配置

不加动态参数。要跑多模型评估，得先实现"加载用户自定义 agent 文件"（目前完全不存在），再手写 N 份近似重复的 agent 定义文件各指定一个 model。改动面远大于方案 A，且主 agent 无法在运行时临时决定要对比哪些模型——不满足目标 1。不采用。

## 接口改动

### `ProviderRegistry`

新增精确跨 provider 路由，未命中即抛错；同时收紧裸 modelId 版本（现状两个分支是等价死代码，从不真正校验，永远拿激活 provider 硬凑）：

```java
public synchronized RequestSelection requestSelection(String providerId, String modelId) {
    ModelOwner owner = ownerOf(providerId, modelId);   // 复用已有 private 方法
    if (owner == null) {
        throw new IllegalArgumentException("未知或不可用模型: " + providerId + ":" + modelId);
    }
    return selection(owner.provider(), owner.model().id());
}

public synchronized RequestSelection requestSelection(String modelId) {
    ModelOwner owner = ownerOf(modelId);   // 跨 provider 找第一个持有者（既有 private 方法）
    if (owner == null) {
        throw new IllegalArgumentException("未知或不可用模型: " + modelId);
    }
    return selection(owner.provider(), owner.model().id());
}
```

### `SubagentSpec`

新增 `withModel`，供路由层派生带覆盖值的 spec（record 的 wither）：

```java
public SubagentSpec withModel(String model) {
    return new SubagentSpec(name, description, systemPrompt, allowTools, denyTools, model, skills);
}
```

### `SubagentRunner.resolveSelection`

正确拆分 `provider:model`，不再丢弃前缀：

```java
private ProviderRegistry.RequestSelection resolveSelection(SubagentSpec spec) {
    if (spec.model() == null || spec.model().isBlank()) {
        return registry.activeRequestSelection();
    }
    String m = spec.model();
    int colon = m.indexOf(':');
    return colon < 0
            ? registry.requestSelection(m)
            : registry.requestSelection(m.substring(0, colon), m.substring(colon + 1));
}
```

同时补一个不会抛异常的"请求标签"方法，供 `run()` 在真正解析（可能失败）之前就能报给 UI：

```java
private String requestedModelLabel(SubagentSpec spec) {
    return (spec.model() == null || spec.model().isBlank())
            ? registry.active().id() + ":" + registry.activeModelId()
            : spec.model();   // 原样展示请求值，即便随后解析失败
}
```

`run()` 顶部改调：
```java
listener.onSubagentStarted(parentTurnId, taskId, spec.name(), description, requestedModelLabel(spec));
```

### `SubagentTool`

`SubagentCall` 新增可选字段：

```java
@ToolParam(required = false, description = "Optional model override for this dispatch, as "
    + "'provider:modelId' (see roster below). Omit to use this subagent's default or the "
    + "currently active model.") String model
```

配一个 null-safe 访问器（照抄 `background()` 的既有写法）：

```java
public String modelOverride() {
    return (model == null || model.isBlank()) ? null : model.trim();
}
```

路由函数（`function`/`batchFunction` 共用同一段逻辑）：解析出 `spec` 后，若 `callArgs.modelOverride()` 非空就 `spec = spec.withModel(override)` 再派给 dispatcher。`Dispatcher` / `BackgroundDispatcher` / `SubagentRunner` 的方法签名**不用改**——它们本来就是"吃一个 spec"，覆盖值随 spec 一起带过去。`ParallelCall.tasks()` 本身是 `List<SubagentCall>`，`ParallelTasks` 自动获得逐条独立指定模型的能力，不用单独加字段。

### 工具描述（模型 roster）

`SubagentTool.create` / `createParallel` 新增入参 `List<ProviderModel> models`；`AgentTools` 装配时传 `registry.allModels()`（已按 `available()` 过滤，行为与 `/model` 一致）。格式化成一份精简 roster 拼进 `DESCRIPTION_TEMPLATE` / `PARALLEL_DESCRIPTION_TEMPLATE`：

```
Available models (optional `model` override, format "provider:modelId"):
- openai:gpt-4o — GPT-4o
- deepseek:deepseek-chat — DeepSeek Chat
- anthropic:claude-sonnet-5 — Claude Sonnet 5

To compare how different models handle the same task, dispatch it via ParallelTasks with
one subtask per model (same prompt, different `model`), then compare the results.
```

### `AgentListener`

仿照 `onSubagentFinished`/`onToolStarted` 已有的"多参数默认委托"惯例，新增一个带 `modelLabel` 的重载，默认委托旧版本（不破坏其它实现方）：

```java
default void onSubagentStarted(long turnId, String taskId, String agentName, String description, String modelLabel) {
    onSubagentStarted(turnId, taskId, agentName, description);
}
```

## 数据流

```
主 agent 调 Task/ParallelTasks，SubagentCall.model = "deepseek:deepseek-chat"
  → 路由函数：spec = specs.get(subagent_type).withModel("deepseek:deepseek-chat")
  → dispatcher.dispatch(spec, ...) / background.dispatch(spec, ...)
  → SubagentRunner.run: requestedModelLabel(spec) → onSubagentStarted(..., "deepseek:deepseek-chat")
  → execute(spec, ...) → resolveSelection(spec) → registry.requestSelection("deepseek", "deepseek-chat")
  → 成功：拿到该 provider 的 ChatModel + options，正常跑
    失败：IllegalArgumentException 被 run() 现有 catch 包成
          SubagentFailedException("未知或不可用模型: ...") → onSubagentFinished(ok=false)
```

不新建任何新的异常类型或分支——完全复用今天 `SubagentFailedException` 的既有 catch/describe/report 流程。`ParallelTasks` 的单条失败隔离也是这条路径自动获得的：`runAll` 已经把每个子任务的 `RuntimeException` 转成 `"失败：" + message` 文本，不影响其它子任务。

## 错误处理

1. 未知 `subagent_type`：不变，路由层直接抛错（不进 dispatcher）。
2. 未知/不可用 `model`（provider 没配 key、或 provider/modelId 拼错）：不做提前校验，走 `resolveSelection` 在 `execute()` 内自然抛出 `IllegalArgumentException`，被现有 `run()` 的 catch 包成清楚的失败文本回给模型。好处：任务面板会先显示 `▸ Task(...) [deepseek:bogus-model]` 再显示失败原因，比"工具调用直接被拒绝"更能定位是哪次委派、指定了什么值出的错。
3. 裸 `modelId`（不带 provider 前缀）：保留兼容，跨 provider 搜索第一个持有者；roster 里只给带前缀的全称，引导主 agent 优先用全称，避免同名模型（不同 provider 可能撞名，参见 `sameModelIdAcrossProvidersKeepsThinkingSeparate` 测试）选错家。
4. 覆盖优先级：`SubagentCall.model`（按次动态）> `spec.model`（静态 frontmatter）> 当前激活模型。`withModel` 直接覆盖，语义上不存在歧义。

## UI 展示

- scrollback：`▸ Task(explore) 审查 README` 后追加模型标签，如 `▸ Task(explore) 审查 README  · deepseek:deepseek-chat`。
- 任务面板：`ConversationState.Subtask` 新增 `model` 字段，随 `taskId`/`agentName`/`description` 一起存，面板渲染追加展示。
- `ConversationState` 新增 5 参 `onSubagentStarted` 覆写承担实际展示逻辑；已有 4 参覆写改为委托 5 参（空标签兜底），镜像 `onSubagentFinished` 现有的委托写法。

## 测试计划

- `ProviderRegistry`：`requestSelection(providerId, modelId)` 精确命中/未知抛错；`requestSelection(modelId)` 跨 provider 找到第一个持有者/彻底未知抛错。
- `SubagentRunner`（沿用 `SubagentRunnerThinkingTest` 的双 provider fake 手法）：`spec.model = "providerB:xxx"` 真正路由到 providerB，而不是像今天这样悄悄丢前缀落到激活 provider。
- `SubagentTool`：`SubagentCall.model` 非空时，派给 dispatcher 的 spec 确实带上了覆盖值（前台、后台各一条）；`ParallelCall` 里不同子任务各自独立覆盖、互不影响。
- `ConversationState`：新 `onSubagentStarted` 5 参版本产出的 scrollback 行与 `Subtask.model` 符合预期。
- 全部跑在模块作用域：`mvn test -pl springai-code-tui`。

## 验收标准

1. 同一份 prompt 通过一次 `ParallelTasks` 派给 3 个不同 `model`（含跨 provider），互不干扰，结果各自独立返回。
2. 指定一个不存在/未配置 key 的 provider:model，得到清楚的失败文本，不会静默改跑别的模型。
3. 不传 `model` 时行为与今天完全一致（激活模型，或 spec 自带的静态 model）。
4. scrollback 与任务面板都能看到每次委派实际用的模型。
5. `mvn test -pl springai-code-tui` 全绿。

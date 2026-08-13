# code-tui 模型思考配置实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 code-tui 的每个 provider/model 增加独立的思考模式与原生强度配置，并通过 `/model` 二级面板配置、持久化到工作区，对主 agent 和子 agent 生效。

**Architecture:** 新增纯领域类型 `ThinkingConfig`/`ThinkingCapabilities` 与机器写入的 `ThinkingConfigStore`。`ProviderRegistry` 持有唯一 store，并提供原子 provider/model/config 快照；五家 `LlmProvider` 负责把统一配置映射为原生请求参数。UI 只读取能力视图和保存配置，不认识 OpenAI、Anthropic、Qwen、智谱或 DeepSeek 的具体 JSON 字段。

**Tech Stack:** Java 25、Spring AI 2.0、Jackson 3（配置文件）、Spring Web HTTP/SSE 客户端、TamboUI 0.4、JUnit 5、Maven。

## Global Constraints

- `DEFAULT` 必须不发送任何新增思考参数，保持上线前请求行为。
- 配置键必须是 provider 与 modelId 两层索引，不能只按 modelId 持久化。
- 强度保留 provider 原生形态，不增加统一低/中/高映射。
- 主 agent 与子 agent读取配置；`DynamicAuxChatModel`、会话摘要和 SmartWebFetch 始终使用 `DEFAULT`。
- 在飞请求使用提交时不可变快照，不能在流式线程中回读可变 store，也不能使用 ThreadLocal 传 DeepSeek 配置。
- 内置模型只展示文档可确认的能力；无法确认时减少选项，不推测。
- 自定义模型按 provider 通用能力开放；远端拒绝时保留配置并报告原始错误。
- DeepSeek 必须保留 Spring AI 原生响应、SSE 工具分片合并和 `reasoning_content` 回传逻辑。
- 不复制整份 Spring AI `DeepSeekChatModel`，不迁移到其他 provider 通路。
- 所有文件写入遵守同目录临时文件加原子替换；失败不抛、不破坏旧文件。
- 实施前用 `using-git-worktrees` 创建隔离 worktree；每个任务先写失败测试，再写最小实现。

---

## File Map

### 新增生产文件

- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingMode.java`：三态模式。
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingStrengthKind.java`：无强度、枚举 effort、token budget。
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingConfig.java`：不可变配置及状态不变量。
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingCapabilities.java`：模型能力、校验与 UI 顺序。
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ModelThinkingSettings.java`：UI 所需的 provider/model/config/capabilities 扁平视图。
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingConfigStore.java`：内存真相源、JSON 读写与原子替换。
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingChatOptions.java`：携带 DeepSeek 配置的原生 options 子类/包装类型。
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingBodyCodec.java`：给固定 delegate 的同步/流式 JSON 请求体加入字段。
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingChatModel.java`：按不可变配置选择缓存 delegate。

### 修改生产文件

- `agent/LlmProvider.java`：新增能力与带配置 options 接口，保留默认兼容入口。
- 五个 `*Provider.java`：能力矩阵与原生映射。
- `agent/ProviderRegistry.java`：持有 store、定位模型 owner、原子请求快照、设置查询与保存。
- `agent/CodingAgent.java`：主回合快照及 UI 门面。
- `agent/SubagentRunner.java`：默认/显式模型配置快照。
- `agent/SubmitHandler.java`：思考设置查询与保存门面。
- `CodeTuiApplication.java`：从工作区加载 store 并注入 registry。
- `ui/CodeTuiView.java`：模型摘要、二级面板、草稿和预算输入状态机。
- `README.md`：补充 `/model` 思考设置说明及持久化位置。

### 新增或扩展测试

- `agent/thinking/ThinkingConfigTest.java`
- `agent/thinking/ThinkingConfigStoreTest.java`
- `agent/ProviderThinkingOptionsTest.java`
- `agent/DeepSeekThinkingBodyCodecTest.java`
- `agent/DeepSeekThinkingChatModelTest.java`
- `agent/ProviderRegistryThinkingTest.java`
- `agent/CodingAgentThinkingTest.java`
- `agent/SubagentRunnerThinkingTest.java`
- `agent/DynamicAuxChatModelTest.java`
- `ui/CodeTuiViewThinkingSettingsTest.java`

---

### Task 1: 思考配置领域类型与 Provider 兼容接口

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingMode.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingStrengthKind.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingConfig.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingCapabilities.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ModelThinkingSettings.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/LlmProvider.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingConfigTest.java`

**Interfaces:**
- Produces: `ThinkingConfig.defaults()`, `enabledEffort(String)`, `enabledBudget(int)`, `enabledWithoutStrength()`, `disabled()`.
- Produces: `ThinkingCapabilities.validate(ThinkingConfig)` and `summary(ThinkingConfig)`.
- Produces: `LlmProvider.options(String, ThinkingConfig)`; existing `options(String)` delegates with `DEFAULT`.

- [ ] **Step 1: Write failing domain tests**

```java
@Test void defaultAndDisabledCannotCarryStrength() {
    assertThrows(IllegalArgumentException.class,
            () -> new ThinkingConfig(ThinkingMode.DEFAULT, "high", null));
    assertThrows(IllegalArgumentException.class,
            () -> new ThinkingConfig(ThinkingMode.DISABLED, null, 1024));
}

@Test void effortCapabilitiesRejectBudgetAndUnknownEffort() {
    ThinkingCapabilities caps = ThinkingCapabilities.effort(true, List.of("low", "high"));
    assertDoesNotThrow(() -> caps.validate(ThinkingConfig.enabledEffort("high")));
    assertThrows(IllegalArgumentException.class,
            () -> caps.validate(ThinkingConfig.enabledEffort("medium")));
    assertThrows(IllegalArgumentException.class,
            () -> caps.validate(ThinkingConfig.enabledBudget(1024)));
}

@Test void budgetRequiresPositiveInteger() {
    ThinkingCapabilities caps = ThinkingCapabilities.tokenBudget(true, 1, null);
    assertThrows(IllegalArgumentException.class,
            () -> caps.validate(ThinkingConfig.enabledBudget(0)));
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn -pl springai-code-tui -Dtest=ThinkingConfigTest test`

Expected: compilation failure because the `thinking` package does not exist.

- [ ] **Step 3: Implement exact domain records and enums**

```java
public enum ThinkingMode { DEFAULT, ENABLED, DISABLED }
public enum ThinkingStrengthKind { NONE, EFFORT, TOKEN_BUDGET }

public record ThinkingConfig(ThinkingMode mode, String effort, Integer thinkingBudget) {
    public ThinkingConfig {
        Objects.requireNonNull(mode, "mode");
        if (mode != ThinkingMode.ENABLED && (effort != null || thinkingBudget != null))
            throw new IllegalArgumentException("只有 ENABLED 可携带思考强度");
        if (effort != null && thinkingBudget != null)
            throw new IllegalArgumentException("effort 与 thinkingBudget 不能同时设置");
        if (effort != null && effort.isBlank())
            throw new IllegalArgumentException("effort 不能为空");
    }
    public static ThinkingConfig defaults() { return new ThinkingConfig(ThinkingMode.DEFAULT, null, null); }
    public static ThinkingConfig disabled() { return new ThinkingConfig(ThinkingMode.DISABLED, null, null); }
    public static ThinkingConfig enabledWithoutStrength() { return new ThinkingConfig(ThinkingMode.ENABLED, null, null); }
    public static ThinkingConfig enabledEffort(String value) { return new ThinkingConfig(ThinkingMode.ENABLED, value, null); }
    public static ThinkingConfig enabledBudget(int value) { return new ThinkingConfig(ThinkingMode.ENABLED, null, value); }
}
```

`ThinkingCapabilities` 使用静态工厂 `unsupported()`、`toggle(boolean supportsDisable)`、`effort(boolean, List<String>)`、`tokenBudget(boolean, int, Integer)`；构造器复制 effort 列表并检查范围。`ModelThinkingSettings` 字段固定为 `providerId/modelId/label/config/capabilities`。

- [ ] **Step 4: Add backward-compatible provider defaults**

```java
default ThinkingCapabilities thinkingCapabilities(String modelId) {
    return ThinkingCapabilities.unsupported();
}

default ChatOptions options(String modelId, ThinkingConfig config) {
    thinkingCapabilities(modelId).validate(config);
    if (config.mode() != ThinkingMode.DEFAULT) {
        throw new IllegalArgumentException(id() + "/" + modelId + " 不支持思考配置");
    }
    return options(modelId);
}
```

Keep existing provider implementations compiling unchanged.

- [ ] **Step 5: Run focused and provider regression tests**

Run: `mvn -pl springai-code-tui -Dtest=ThinkingConfigTest,LlmProviderTest,ProviderCapabilitiesTest test`

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/LlmProvider.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingConfigTest.java
git commit -m "feat(tui): add model thinking domain types"
```

### Task 2: 工作区配置 Store 与原子持久化

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingConfigStore.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingConfigStoreTest.java`

**Interfaces:**
- Produces: `ThinkingConfigStore.inMemory()` and `ThinkingConfigStore.load(Path root)`.
- Produces: `get(providerId, modelId)`, `put(providerId, modelId, config)`, `snapshot()`, `save()`.
- `snapshot()` returns an immutable `Map<String, Map<String, ThinkingConfig>>` for registry startup validation.
- `put` updates memory only; `save` returns boolean and never throws. `DEFAULT` removes the model entry.

- [ ] **Step 1: Write failing persistence tests**

Cover exact JSON round-trip, same model ID under two providers, `DEFAULT` deletion, malformed JSON warning/fallback, unknown fields, write failure, and no `.tmp` residue.

```java
@Test void providerAndModelAreIndependent(@TempDir Path root) {
    ThinkingConfigStore store = ThinkingConfigStore.load(root);
    store.put("openai", "same", ThinkingConfig.enabledEffort("high"));
    store.put("qwen", "same", ThinkingConfig.enabledBudget(4096));
    assertTrue(store.save());
    ThinkingConfigStore restored = ThinkingConfigStore.load(root);
    assertEquals("high", restored.get("openai", "same").effort());
    assertEquals(4096, restored.get("qwen", "same").thinkingBudget());
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -pl springai-code-tui -Dtest=ThinkingConfigStoreTest test`

Expected: compilation failure for missing `ThinkingConfigStore`.

- [ ] **Step 3: Implement strict load semantics**

Use Jackson 3 `JsonMapper` with `STRICT_DUPLICATE_DETECTION`. Parse fixed shape `version -> providers -> providerId -> modelId -> {mode, effort, thinkingBudget}`. Missing file returns empty without logging. Bad top-level shape, duplicate keys, invalid mode, or contradictory strength logs one WARN and returns an empty store.

- [ ] **Step 4: Implement atomic save**

Write the complete machine-owned document to `thinking.json.<uuid>.tmp`, then `ATOMIC_MOVE`, falling back to `REPLACE_EXISTING`. On any failure delete the temp file, log WARN, return `false`, and leave memory unchanged.

```java
public synchronized void put(String providerId, String modelId, ThinkingConfig config) {
    if (config.mode() == ThinkingMode.DEFAULT) remove(providerId, modelId);
    else configs.computeIfAbsent(providerId, ignored -> new LinkedHashMap<>()).put(modelId, config);
}
```

- [ ] **Step 5: Run persistence tests**

Run: `mvn -pl springai-code-tui -Dtest=ThinkingConfigStoreTest,ModelPreferenceTest test`

Expected: all tests pass; existing model preference behavior is unchanged.

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingConfigStore.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/thinking/ThinkingConfigStoreTest.java
git commit -m "feat(tui): persist per-model thinking settings"
```

### Task 3: OpenAI、Anthropic、Qwen 与智谱映射

**Files:**
- Modify: `agent/OpenAiProvider.java`
- Modify: `agent/AnthropicProvider.java`
- Modify: `agent/QwenProvider.java`
- Modify: `agent/ZhipuProvider.java`
- Test: `agent/ProviderThinkingOptionsTest.java`

**Interfaces:**
- Consumes: `ThinkingConfig` and `ThinkingCapabilities` from Task 1.
- Produces: model-aware capabilities and native `ChatOptions` mappings for four providers.

- [ ] **Step 1: Write failing mapping tests**

```java
@Test void qwenMapsToggleAndBudgetIntoExtraBody() {
    OpenAiChatOptions o = (OpenAiChatOptions) new QwenProvider("k")
            .options("qwen3.7-max", ThinkingConfig.enabledBudget(32768));
    assertEquals(true, o.getExtraBody().get("enable_thinking"));
    assertEquals(32768, o.getExtraBody().get("thinking_budget"));
}

@Test void zhipuOnlyGlm52CarriesEffort() {
    OpenAiChatOptions o = (OpenAiChatOptions) new ZhipuProvider("k")
            .options("glm-5.2", ThinkingConfig.enabledEffort("max"));
    assertEquals(Map.of("type", "enabled"), o.getExtraBody().get("thinking"));
    assertEquals("max", o.getReasoningEffort());
    assertThrows(IllegalArgumentException.class, () -> new ZhipuProvider("k")
            .options("glm-5.1", ThinkingConfig.enabledEffort("max")));
}
```

Also assert every provider's `DEFAULT` options has no new fields; Fable 5 rejects `DISABLED`; Qwen Coder Next rejects budget but accepts toggle-only; invalid effort fails before request.

- [ ] **Step 2: Run the mapping test and verify RED**

Run: `mvn -pl springai-code-tui -Dtest=ProviderThinkingOptionsTest test`

Expected: failures because providers still inherit unsupported defaults.

- [ ] **Step 3: Implement OpenAI and Anthropic mapping**

OpenAI sets `.reasoningEffort(config.mode() == DISABLED ? "none" : config.effort())` only for non-default configs. Anthropic starts from model + `MAX_TOKENS`, adds `thinkingAdaptive()` for enabled, `thinkingDisabled()` only when capabilities allow it, and `.effort(OutputConfig.Effort.of(config.effort()))` when effort exists.

- [ ] **Step 4: Implement Qwen and Zhipu mapping**

Qwen builds an ordered `extraBody`; enabled/disabled writes `enable_thinking`, and budget is included only when present. Zhipu writes nested `thinking.type`; GLM-5.2 effort uses `reasoningEffort`, while GLM-5.1/Turbo capabilities expose toggle-only.

- [ ] **Step 5: Run provider tests**

Run: `mvn -pl springai-code-tui -Dtest=ProviderThinkingOptionsTest,LlmProviderTest,ProviderModelsEnvTest test`

Expected: all pass, including custom-model provider fallback capabilities.

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/{OpenAiProvider,AnthropicProvider,QwenProvider,ZhipuProvider}.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ProviderThinkingOptionsTest.java
git commit -m "feat(tui): map thinking settings for four providers"
```

### Task 4: DeepSeek 固定配置请求装饰与路由模型

**Files:**
- Create: `agent/DeepSeekThinkingChatOptions.java`
- Create: `agent/DeepSeekThinkingBodyCodec.java`
- Create: `agent/DeepSeekThinkingChatModel.java`
- Modify: `agent/DeepSeekProvider.java`
- Test: `agent/DeepSeekThinkingBodyCodecTest.java`
- Test: `agent/DeepSeekThinkingChatModelTest.java`

**Interfaces:**
- Produces: options carrying model plus immutable `ThinkingConfig`.
- Produces: request-body transformation `byte[] decorate(byte[], ThinkingConfig)`.
- Produces: routing ChatModel that caches one native delegate per config key.

- [ ] **Step 1: Write failing pure JSON codec tests**

Assert `DEFAULT` returns byte-for-byte unchanged JSON, enabled adds nested `thinking` plus effort, disabled adds only `thinking`, existing messages/tools/reasoning content remain structurally identical, and input bytes are never mutated.

```java
@Test void enabledAddsOnlyTopLevelThinkingFields() {
    byte[] out = codec.decorate(BASE_REQUEST, ThinkingConfig.enabledEffort("max"));
    JsonNode root = mapper.readTree(out);
    assertEquals("enabled", root.path("thinking").path("type").stringValue());
    assertEquals("max", root.path("reasoning_effort").stringValue());
    assertEquals(2, root.path("messages").size());
}
```

- [ ] **Step 2: Run codec test and verify RED**

Run: `mvn -pl springai-code-tui -Dtest=DeepSeekThinkingBodyCodecTest test`

Expected: missing codec class.

- [ ] **Step 3: Implement codec and HTTP integration**

`DeepSeekThinkingBodyCodec` uses a Jackson tree to add only top-level fields. Integrate it at two fixed-config points used when constructing each delegate:

- Blocking: add a `ClientHttpRequestInterceptor` to the cloned `RestClient.Builder`; transform the serialized `byte[]` before `execution.execute(request, body)`.
- Streaming: register an encoder wrapper on the cloned `WebClient.Builder`; delegate normal `DeepSeekApi.ChatCompletionRequest` encoding to Spring's configured JSON encoder, transform the resulting `DataBuffer`, and return a new wrapped buffer. Scope the encoder to `DeepSeekApi.ChatCompletionRequest` and JSON media types so SSE response decoding is untouched.

Do not read a global/thread-local config in either hook.

- [ ] **Step 4: Write failing routing-model tests**

Use fake delegate factory `Function<ThinkingConfig, ChatModel>` and capture calls. Verify call and stream choose the matching delegate, repeated config reuses it, and default uses the undecorated delegate.

- [ ] **Step 5: Implement routing ChatModel and provider wiring**

`DeepSeekThinkingChatOptions` extends `DeepSeekChatOptions` through its protected constructor, copies every native field, adds final `ThinkingConfig thinkingConfig`, and overrides `mutate()` with a project builder that preserves both native fields and thinking config. This is required because ChatClient tool merging calls `mutate()` before the router sees the prompt.

`DeepSeekThinkingChatModel` accepts a delegate factory and `ConcurrentHashMap<ThinkingConfig, ChatModel>`. For `DeepSeekThinkingChatOptions` it extracts the immutable config once per call/stream; for a plain `DeepSeekChatOptions` it uses `ThinkingConfig.defaults()` so auxiliary and legacy callers remain compatible. It rebuilds a plain `DeepSeekChatOptions` containing every native field before passing the prompt to the Spring AI delegate, and forwards `getDefaultOptions()`. The native delegate therefore still receives the exact type it casts to, while project-only state never reaches Spring AI serialization.

`DeepSeekProvider.options(model, config)` validates capabilities (`low/high/max`) and returns `DeepSeekThinkingChatOptions`; `chatModel()` returns the router whose factory creates native delegates with current timeout/base/key and fixed codec hooks.

- [ ] **Step 6: Verify real serialized sync and stream requests without network**

Point provider base URL at local JDK `HttpServer`, issue one blocking and one streaming request, capture request JSON, return minimal valid DeepSeek JSON/SSE. Assert both bodies contain fields and Spring AI still parses the response. Include a tool-call fixture whose assistant follow-up retains `reasoning_content`.

Run: `mvn -pl springai-code-tui -Dtest=DeepSeekThinkingBodyCodecTest,DeepSeekThinkingChatModelTest,LlmProviderTest test`

Expected: all pass; no external API calls.

- [ ] **Step 7: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekThinking*.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekProvider.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/DeepSeekThinking*.java
git commit -m "feat(tui): support DeepSeek thinking request fields"
```

### Task 5: Registry 真相源与原子请求快照

**Files:**
- Modify: `agent/ProviderRegistry.java`
- Test: `agent/ProviderRegistryThinkingTest.java`

**Interfaces:**
- Consumes: store and provider mappings.
- Produces: `RequestSelection(provider, modelId, config, options)` immutable record.
- Produces: `thinkingSettings(modelId)` and `updateThinking(modelId, config)`.

- [ ] **Step 1: Write failing registry tests**

Cover cross-provider same model ID using existing first-provider selection semantics, active snapshot consistency, invalid saved config falling back to default without deleting record, constructor-time warning for an incompatible saved setting of an available provider/model, no warning for a temporarily unavailable provider/model record, and save failure returning false while memory remains updated.

- [ ] **Step 2: Run and verify RED**

Run: `mvn -pl springai-code-tui -Dtest=ProviderRegistryThinkingTest test`

Expected: missing constructors/methods.

- [ ] **Step 3: Add store-aware constructors and owner lookup**

Keep `ProviderRegistry(List<LlmProvider>)` delegating to `ThinkingConfigStore.inMemory()`. Add `ProviderRegistry(List<LlmProvider>, ThinkingConfigStore)`. Centralize first matching available provider/model in one private `ownerOf(modelId)` method so `/model`, settings and selection cannot disagree. During construction, walk `store.snapshot()` and validate only entries whose provider is available and whose model appears in that provider's current `models()` list; log one warning per incompatible entry, keep it on disk, and skip entries for unavailable/absent models without warning.

- [ ] **Step 4: Add synchronized request snapshot**

```java
public synchronized RequestSelection activeRequestSelection() {
    LlmProvider provider = active;
    String modelId = activeModelId;
    ThinkingConfig config = effectiveConfig(provider, modelId);
    return new RequestSelection(provider, modelId, config,
            provider.options(modelId, config));
}
```

Synchronize `select` on the same monitor. `effectiveConfig` validates saved config; on incompatibility logs a precise warning and returns `DEFAULT` without mutating store.

- [ ] **Step 5: Add settings/save APIs**

`thinkingSettings(modelId)` returns provider ID, model label, raw saved config and capabilities. `updateThinking` validates first, updates memory, calls store.save, and returns save success. Unknown model throws an argument error; UI converts it to notice.

- [ ] **Step 6: Run tests**

Run: `mvn -pl springai-code-tui -Dtest=ProviderRegistryThinkingTest,ProviderRegistryTest test`

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ProviderRegistry.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ProviderRegistryThinkingTest.java
git commit -m "feat(tui): snapshot model thinking configuration"
```

### Task 6: 主 Agent、子 Agent 与辅助调用边界

**Files:**
- Modify: `agent/CodingAgent.java`
- Modify: `agent/SubagentRunner.java`
- Modify: `agent/DynamicAuxChatModel.java` comments only if needed
- Test: `agent/CodingAgentThinkingTest.java`
- Test: `agent/SubagentRunnerThinkingTest.java`
- Modify test: `agent/DynamicAuxChatModelTest.java`

**Interfaces:**
- Consumes: `ProviderRegistry.activeRequestSelection()`.
- Produces: configured main/subagent requests while auxiliary requests remain default.

- [ ] **Step 1: Write failing main-agent snapshot test**

Use a capturing fake provider and ChatClient. Configure `high`, submit a turn, then change store before the captured stream is consumed. Assert prompt options still contain `high` and provider/model/options came from one `RequestSelection`.

- [ ] **Step 2: Update CodingAgent submit path**

Replace separate `registry.active()`/`activeModelId()`/`active.options()` reads with one `RequestSelection`. Use its provider for client lookup, its model for grounding, and its prebuilt options for `.options(...)`.

- [ ] **Step 3: Write failing subagent tests**

Assert blank `spec.model` uses active model config; explicit `spec.model` uses that model's config; provider/model are snapshotted before ChatClient construction; background and foreground share the same `execute` path.

- [ ] **Step 4: Update SubagentRunner**

Resolve one immutable selection before building ChatClient. For explicit model, add registry method `requestSelection(String modelId)` using the active provider's current v1 routing semantics. Build client from selection.provider().chatModel() and options from the same selection.

- [ ] **Step 5: Pin auxiliary default behavior**

Extend `DynamicAuxChatModelTest` so store contains a non-default setting but captured options come from `active.options(modelId)` compatibility method and contain no thinking fields. Do not inject store into `DynamicAuxChatModel`.

- [ ] **Step 6: Run call-chain tests**

Run: `mvn -pl springai-code-tui -Dtest=CodingAgentThinkingTest,SubagentRunnerThinkingTest,DynamicAuxChatModelTest,CodingAgentModelSwitchTest,SubagentRunnerOkTest test`

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/{CodingAgent,SubagentRunner,DynamicAuxChatModel}.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/{CodingAgentThinkingTest,SubagentRunnerThinkingTest,DynamicAuxChatModelTest}.java
git commit -m "feat(tui): apply thinking settings to agent calls"
```

### Task 7: 应用装配与 SubmitHandler 门面

**Files:**
- Modify: `agent/SubmitHandler.java`
- Modify: `agent/CodingAgent.java`
- Modify: `CodeTuiApplication.java`
- Test: `agent/CodingAgentModelSwitchTest.java`
- Create: `CodeTuiApplicationThinkingConfigTest.java`

**Interfaces:**
- Produces UI methods `thinkingSettings(String)` and `saveThinkingSettings(String, ThinkingConfig)`.
- Production registry receives `ThinkingConfigStore.load(root)` before `AgentTools.build`.

- [ ] **Step 1: Write failing facade and startup tests**

`CodingAgentModelSwitchTest` should query settings for a non-active model and save it without changing current model. Startup test creates `thinking.json`, builds the registry through a package-private application factory, and verifies restored config is visible.

- [ ] **Step 2: Add SubmitHandler defaults**

```java
default ModelThinkingSettings thinkingSettings(String modelId) { return null; }
default boolean saveThinkingSettings(String modelId, ThinkingConfig config) { return false; }
```

`CodingAgent` delegates both to registry; old single-client tests retain no-op defaults.

- [ ] **Step 3: Extract provider registry construction in application**

Add package-private `createProviderRegistry(Path root, Map<String,String> env)` used by `main` and tests. It constructs providers exactly as today and passes `ThinkingConfigStore.load(root)` to registry. Keep model restore after registry construction and before `AgentTools.build`.

- [ ] **Step 4: Run wiring tests**

Run: `mvn -pl springai-code-tui -Dtest=CodingAgentModelSwitchTest,CodeTuiApplicationThinkingConfigTest,CodeTuiApplicationModelRestoreTest test`

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/{CodeTuiApplication.java,agent/SubmitHandler.java,agent/CodingAgent.java} \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/{CodeTuiApplicationThinkingConfigTest.java,agent/CodingAgentModelSwitchTest.java}
git commit -m "feat(tui): wire thinking settings into application"
```

### Task 8: `/model` 二级思考设置面板

**Files:**
- Modify: `ui/CodeTuiView.java`
- Create: `ui/CodeTuiViewThinkingSettingsTest.java`
- Modify: `ui/CodeTuiViewModelMemoryTest.java` only if helper extraction is required

**Interfaces:**
- Consumes: SubmitHandler settings/save methods.
- Produces: model list summary and secondary keyboard state machine.

- [ ] **Step 1: Write failing UI state tests**

Use a fake handler with effort, budget, toggle-only and unsupported models. Cover:

- list renders `默认/关闭/high/32768 tokens/开启/不可配置` summaries;
- Right arrow opens settings for highlighted non-active model without switching it;
- unsupported model shows notice and stays in list;
- effort cycles with Left/Right;
- mode changes disable/enable strength row;
- budget Enter enters numeric edit, rejects `0` and non-digits, accepts positive integer;
- panel Enter saves and returns to model list;
- Esc discards draft;
- save false emits “仅本次运行生效” while handler memory remains changed.

- [ ] **Step 2: Run and verify RED**

Run: `mvn -pl springai-code-tui -Dtest=CodeTuiViewThinkingSettingsTest test`

Expected: assertions fail because `/model` has no secondary state.

- [ ] **Step 3: Add explicit UI state fields**

Add `configuringThinking`, `thinkingRow`, `thinkingDraft`, `thinkingTarget`, `editingBudget`, and a dedicated text buffer. Render either `modelPickerChildren()` or `thinkingSettingsChildren()`, never both. Route keys to settings before model-picker keys.

- [ ] **Step 4: Add model-list summary and Right-arrow entry**

Change title and status hint to include `→ 思考设置`. Resolve settings for each row once per render and append summary. Right arrow loads a detached draft; it must not call `selectModel`.

- [ ] **Step 5: Implement effort/toggle state machine**

Mode order is `DEFAULT -> ENABLED -> DISABLED` with `DISABLED` omitted when unsupported. Entering enabled initializes strength to the first capability effort if absent; returning to default/disabled clears strength. Left/Right wraps only through capability-provided values.

- [ ] **Step 6: Implement budget editor and save semantics**

Numeric mode accepts digits, Backspace, Enter and Esc only. Validate via `ThinkingCapabilities.validate`. Panel save calls handler once; success and failure both return to model list because memory has changed, but failure pushes `⚠ 没能记住这个思考设置（仅本次运行生效）`.

- [ ] **Step 7: Run UI and regression tests**

Run: `mvn -pl springai-code-tui -Dtest=CodeTuiViewThinkingSettingsTest,CodeTuiViewModelMemoryTest,CodeTuiViewEditShortcutTest,AttachmentInjectionTest test`

Expected: pass; existing Enter model-switch behavior and model memory remain unchanged.

- [ ] **Step 8: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewThinkingSettingsTest.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewModelMemoryTest.java
git commit -m "feat(tui): add model thinking settings panel"
```

### Task 9: 文档、全量验证与交互验收

**Files:**
- Modify: `springai-code-tui/README.md`
- Modify tests only if verification finds a real defect; do not weaken assertions.

**Interfaces:**
- Produces: user-facing usage instructions and release-ready verified feature.

- [ ] **Step 1: Update README with exact interaction**

Document `/model`, Right arrow secondary settings, provider-native effort/token budget, `DEFAULT` meaning, persistence at `.codetui/thinking.json`, and main/subagent-only scope. Do not promise that every custom gateway supports the fields.

- [ ] **Step 2: Run focused feature suite**

Run:

```bash
mvn -pl springai-code-tui -Dtest='*Thinking*,ProviderRegistryTest,LlmProviderTest,DynamicAuxChatModelTest,CodeTuiViewModelMemoryTest' test
```

Expected: BUILD SUCCESS, zero failures/errors.

- [ ] **Step 3: Run the entire module test suite**

Run: `mvn -pl springai-code-tui test`

Expected: BUILD SUCCESS.

- [ ] **Step 4: Package the module**

Run: `mvn -pl springai-code-tui -am package -DskipTests`

Expected: reactor BUILD SUCCESS and `springai-code-tui/target/springai-code-tui.jar` exists.

- [ ] **Step 5: Perform manual TUI smoke test**

Start with at least two configured providers and a temporary working project. Verify:

1. `/model` summaries render without horizontal corruption.
2. Right arrow opens settings without switching model.
3. Effort and Qwen budget survive exit/restart.
4. Switching model and switching back restores each model's independent setting.
5. A prompt reaches the selected provider successfully in `DEFAULT` and one explicit setting.
6. A subagent request uses the same model setting.
7. `.codetui/thinking.json` contains provider/model nested keys and no API credentials.

- [ ] **Step 6: Inspect final diff and repository state**

Run: `git diff --check && git status --short && git log --oneline -12`

Expected: no whitespace errors; only intended README/code/test changes are present.

- [ ] **Step 7: Commit documentation or final verification fixes**

```bash
git add springai-code-tui/README.md springai-code-tui/src/main springai-code-tui/src/test
git commit -m "docs(tui): document model thinking settings"
```

If no files changed after Task 8 except README, commit only README. Never create an empty commit.

---

## Final Review Checklist

- [ ] Every spec goal maps to a task and passing test.
- [ ] `DEFAULT` produces no new provider request fields in all five providers.
- [ ] Fable 5 cannot select disabled; GLM-5.1/Turbo cannot select effort; Qwen Coder Next cannot select budget.
- [ ] Custom models use provider fallback capabilities and preserve remote API errors.
- [ ] Main/subagent paths use immutable snapshots; auxiliary calls remain default.
- [ ] DeepSeek sync and stream request JSON both contain fields without replacing response logic.
- [ ] No ThreadLocal or mutable global is used to carry request configuration.
- [ ] Bad config files and write failures do not stop startup or destroy prior files.
- [ ] `/model` Enter behavior and existing `model.json` preference remain intact.
- [ ] Full tests and package build pass.

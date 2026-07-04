# 多 Provider 支持 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `springai-code-tui` 的主 agent 支持 anthropic / openai / deepseek 三家大模型可切换，`/model` 跨 provider 选择，切到哪家主 agent 就跑在那家上。

**Architecture:** 引入 `LlmProvider` 抽象（每家一个实现，只有配了 API key 的 `available()`）+ `ProviderRegistry`（持有全部 provider、记录激活 provider 与激活模型）。`AgentTools.build` 为每个可用 provider 各建一个 `ChatClient`（共享同一套装饰工具 + 会话记忆 advisor + 系统模板），`CodingAgent.submit` 按激活 provider 选对应 ChatClient 并用该 provider 的 options 覆盖模型。会话历史是 provider 中立的 `Message` 事件，切家无缝延续。

**Tech Stack:** Java 21，Spring AI 2.0（`spring-ai-deepseek` / `spring-ai-anthropic` / `spring-ai-openai`），Maven，JUnit 5。

**上下文（实现者须知）：**
- 这是**两份计划中的第一份**（多 provider 是地基）。第二份「Subagent 功能」依赖本计划产出的 `LlmProvider` / `ProviderRegistry`。
- 设计文档：`docs/superpowers/specs/2026-07-04-subagent-and-multi-provider-design.md`（§4.1 / §4.2 / §8）。
- 关键 API（已用 javap 核实）：
  - 三家 ChatModel 都 `implements org.springframework.ai.chat.model.ChatModel`，`ChatClient.builder(ChatModel)` 通吃。
  - **DeepSeek**：`DeepSeekApi.builder().apiKey(k).baseUrl(u).build()` → `DeepSeekChatModel.builder().deepSeekApi(api).options(DeepSeekChatOptions.builder().model(m).build()).build()`。
  - **Anthropic**（已实测，网络无关）：`AnthropicChatModel.builder().options(AnthropicChatOptions.builder().apiKey(k).model(com.anthropic.models.messages.Model.of(m)).maxTokens(8192).build()).build()`。**不用** 供应商 okhttp wrapper——`spring-ai-anthropic:2.0.0` 只带 `anthropic-java-core`，把 apiKey 设到 options 上、`build()` 会自行派生底层 client（`SpringAiAnthropicHttpClient`）。`model()` 收 typed enum，用静态 `Model.of(String)`；`max_tokens` 必填。
  - **OpenAI**（已实测，网络无关）：`OpenAiChatModel.builder().options(OpenAiChatOptions.builder().apiKey(k).model(m).build()).build()`。同理**不用** `OpenAIOkHttpClient`（不在 classpath）——apiKey 设到 options 上、`build()` 自行派生 client。`OpenAiChatOptions.builder().model(String)` + `apiKey(String)` 均存在。
  - 每请求模型覆盖：各 provider 返回自己 native 的 `ChatOptions`（`DeepSeekChatOptions`/`OpenAiChatOptions` 用 `model(String)`；`AnthropicChatOptions` 用 `model(Model.of(id)).maxTokens(...)`）。`ChatClient.prompt().options(ChatOptions)` 接受任意 `ChatOptions` 子类型。
- 现状：`CodeTuiApplication` 造 `DeepSeekChatModel` → `AgentTools.build(model, root, listener)` 返回 `AgentRuntime(client, ...)` → `new CodingAgent(client, ...)`。`CodingAgent` 里 `MODELS` 是静态 DeepSeek 两项，`submit` 用 `DeepSeekChatOptions.builder().model(model)` 覆盖模型名，`/model` 经 `models()/currentModel()/selectModel()` 走 `SubmitHandler`。

**验证纪律（本仓库既有约定）：**
- 跑测试时**排除** `CodingAgentSpikeTest`（联网易抖）。命令统一用：
  `mvn -q -pl springai-code-tui test -Dtest='!CodingAgentSpikeTest'`
- 不 push；提交信息结尾附 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。

---

## File Structure

**新建：**
- `springai-code-tui/src/main/java/com/example/springai/codetui/agent/LlmProvider.java` — provider 抽象接口。
- `.../agent/DeepSeekProvider.java` — DeepSeek 实现。
- `.../agent/AnthropicProvider.java` — Anthropic 实现。
- `.../agent/OpenAiProvider.java` — OpenAI 实现。
- `.../agent/ProviderRegistry.java` — 持有全部 provider + 激活状态。
- `.../test/.../agent/ProviderRegistryTest.java` — registry 单测。
- `.../test/.../agent/LlmProviderTest.java` — provider 装配/options 单测（假 key，不发网络）。

**修改：**
- `springai-code-tui/pom.xml` — 加 `spring-ai-anthropic` / `spring-ai-openai` 依赖。
- `.../agent/AgentTools.java` — `build` 收 `ProviderRegistry`，为每个可用 provider 建 ChatClient；`AgentRuntime` 携带 `Map<String,ChatClient> clients` + 便捷 `client()`。
- `.../agent/CodingAgent.java` — 新增「多 provider 生产构造」（持 `ProviderRegistry` + `Map<String,ChatClient>`）；`submit`/`models`/`currentModel`/`selectModel` 走 registry；保留旧单-client 构造给测试。
- `.../CodeTuiApplication.java` — 从环境变量建三个 provider + registry，传给 `AgentTools.build`。
- `.../test/.../agent/AgentRuntimeTest.java` — 适配 `build(registry, ...)` 新签名。

**不改：** `ModelOption`（保持 3 字段 `id/label/desc`；provider 归属由 registry 按 modelId 反查，模型 id 三家全局唯一）、`SubmitHandler`、UI 层 `CodeTuiView`（仍只用 `models()/currentModel()/selectModel()`）。

---

## Task 1: 加 Anthropic / OpenAI 依赖

**Files:**
- Modify: `springai-code-tui/pom.xml:17-35`（`<dependencies>` 顶部，DeepSeek 依赖旁）

- [ ] **Step 1: 加两条依赖**

在 `springai-code-tui/pom.xml` 里 `spring-ai-deepseek` 依赖之后、`spring-ai-client-chat` 之前插入：

```xml
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-anthropic</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-openai</artifactId>
        </dependency>
```

（版本由父 `pom.xml` 的 `spring-ai-bom` 2.0.0 统一管理，此处不写版本。）

- [ ] **Step 2: 拉依赖、确认可解析**

Run: `mvn -q -pl springai-code-tui dependency:resolve -DincludeScope=compile | grep -iE "spring-ai-anthropic|spring-ai-openai" | head`
Expected: 两个 artifact 各出现一行（2.0.0），无解析错误。

- [ ] **Step 3: 编译现有代码不受影响**

Run: `mvn -q -pl springai-code-tui test-compile`
Expected: BUILD SUCCESS（尚未改任何 java）。

- [ ] **Step 4: Commit**

```bash
git add springai-code-tui/pom.xml
git commit -m "build(code-tui): 加 spring-ai-anthropic / spring-ai-openai 依赖

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `LlmProvider` 接口

**Files:**
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/LlmProvider.java`

- [ ] **Step 1: 写接口**

```java
package com.example.springai.codetui.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

/**
 * 一家大模型 provider 的抽象。主 agent 与子 agent 共用。
 *
 * <p>只有配了对应 API key 的 provider 才 {@link #available()}——不可用的不出现在 {@code /model}、不阻断启动。
 * {@link #chatModel()} 返回框架的 {@link ChatModel} 接口（三家实现都实现它），供 {@code ChatClient.builder} 通用装配。
 * {@link #options(String)} 返回该家 native 的每请求 {@link ChatOptions}（只覆盖模型；其余走 chatModel 的默认 options）。
 */
public interface LlmProvider {

    /** provider 稳定 id：{@code "deepseek"} | {@code "anthropic"} | {@code "openai"}。 */
    String id();

    /** 对应 API key 是否已配置。false 则不装配 ChatModel、不出现在 /model。 */
    boolean available();

    /**
     * 该家的 {@link ChatModel}（带 key/base、默认 options）。仅在 {@link #available()} 为 true 时可调用；
     * 不可用时调用抛 {@link IllegalStateException}（装配期不应触碰不可用 provider）。
     */
    ChatModel chatModel();

    /** 每请求覆盖模型用的该家 native {@link ChatOptions}（只设 model，Anthropic 另附必填 maxTokens）。 */
    ChatOptions options(String modelId);

    /** 该家可选模型（供 /model 展示与选择）。 */
    List<ModelOption> models();

    /** 该家默认模型 id（激活该家时的初始模型）。 */
    String defaultModel();
}
```

- [ ] **Step 2: 编译**

Run: `mvn -q -pl springai-code-tui test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/LlmProvider.java
git commit -m "feat(code-tui): 加 LlmProvider provider 抽象接口

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `DeepSeekProvider`

**Files:**
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/DeepSeekProvider.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/LlmProviderTest.java`

- [ ] **Step 1: 写失败测试**

创建 `LlmProviderTest.java`：

```java
package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 各 LlmProvider 的装配/可用性/options 单测：全部用假 key，不发任何网络请求。 */
class LlmProviderTest {

    @Test
    void deepseek_withKey_isAvailableAndBuildsModel() {
        DeepSeekProvider p = new DeepSeekProvider("fake-key");
        assertEquals("deepseek", p.id());
        assertTrue(p.available());
        assertEquals("deepseek-v4-flash", p.defaultModel());
        assertFalse(p.models().isEmpty());
        // 装配不发网络：chatModel() 能造出来
        assertTrue(p.chatModel() != null);
        // options 覆盖模型
        assertEquals("deepseek-v4-pro", p.options("deepseek-v4-pro").getModel());
    }

    @Test
    void deepseek_withoutKey_isUnavailable() {
        DeepSeekProvider p = new DeepSeekProvider("  ");
        assertFalse(p.available());
        assertThrows(IllegalStateException.class, p::chatModel);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=LlmProviderTest`
Expected: 编译失败（`DeepSeekProvider` 不存在）。

- [ ] **Step 3: 写实现**

```java
package com.example.springai.codetui.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import java.util.List;

/**
 * DeepSeek provider（现役、默认激活）。key 缺失即 unavailable。
 * 模型名 deepseek-v4-flash（V4 现役；旧 deepseek-chat/reasoner 2026-07-24 停用）。
 */
public final class DeepSeekProvider implements LlmProvider {

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("deepseek-v4-flash", "deepseek-v4-flash", "非思考 · 快 · 便宜"),
            new ModelOption("deepseek-v4-pro",   "deepseek-v4-pro",   "强推理 · 1.6T · 更慢更贵"));

    private final String apiKey;
    private volatile ChatModel chatModel;   // 懒建，单例

    public DeepSeekProvider(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override public String id() { return "deepseek"; }

    @Override public boolean available() { return !apiKey.isBlank(); }

    @Override
    public ChatModel chatModel() {
        if (!available()) {
            throw new IllegalStateException("DeepSeek 不可用：未配置 DEEPSEEK_API_KEY");
        }
        ChatModel m = chatModel;
        if (m == null) {
            DeepSeekApi api = DeepSeekApi.builder().apiKey(apiKey).baseUrl(BASE_URL).build();
            m = DeepSeekChatModel.builder()
                    .deepSeekApi(api)
                    .options(DeepSeekChatOptions.builder().model(DEFAULT_MODEL).build())
                    .build();
            chatModel = m;
        }
        return m;
    }

    @Override
    public ChatOptions options(String modelId) {
        return DeepSeekChatOptions.builder().model(modelId).build();
    }

    @Override public List<ModelOption> models() { return MODELS; }

    @Override public String defaultModel() { return DEFAULT_MODEL; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=LlmProviderTest`
Expected: PASS（2 tests）。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/DeepSeekProvider.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/LlmProviderTest.java
git commit -m "feat(code-tui): DeepSeekProvider（默认 provider）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `AnthropicProvider`

**Files:**
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/AnthropicProvider.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/LlmProviderTest.java`（追加）

- [ ] **Step 1: 追加失败测试**

在 `LlmProviderTest` 里追加：

```java
    @Test
    void anthropic_withKey_availableAndOptionsCarryModelAndMaxTokens() {
        AnthropicProvider p = new AnthropicProvider("fake-key");
        assertEquals("anthropic", p.id());
        assertTrue(p.available());
        assertEquals("claude-sonnet-4-5", p.defaultModel());
        // options 覆盖模型：portable getModel() 读回模型字符串
        org.springframework.ai.anthropic.AnthropicChatOptions opts =
                (org.springframework.ai.anthropic.AnthropicChatOptions) p.options("claude-opus-4-5");
        assertEquals("claude-opus-4-5", opts.getModel());
        // Anthropic 必填 max_tokens 已带上
        assertEquals(8192, opts.getMaxTokens());
    }

    @Test
    void anthropic_withoutKey_isUnavailable() {
        AnthropicProvider p = new AnthropicProvider(null);
        assertFalse(p.available());
        assertThrows(IllegalStateException.class, p::chatModel);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=LlmProviderTest`
Expected: 编译失败（`AnthropicProvider` 不存在）。

- [ ] **Step 3: 写实现**

```java
package com.example.springai.codetui.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Model;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

/**
 * Anthropic provider（Spring AI 2.0 spring-ai-anthropic）。key 缺失即 unavailable。
 *
 * <p>注意：Anthropic 的 {@code model()} 收 typed 枚举 {@link Model}，用静态 {@code Model.of(String)}；
 * 且 {@code max_tokens} 为必填，故默认 options 与每请求 options 都显式带 {@link #MAX_TOKENS}。
 */
public final class AnthropicProvider implements LlmProvider {

    private static final String DEFAULT_MODEL = "claude-sonnet-4-5";
    private static final int MAX_TOKENS = 8192;   // Anthropic 必填；可调
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("claude-sonnet-4-5", "claude-sonnet-4-5", "均衡 · 日常编码"),
            new ModelOption("claude-opus-4-5",   "claude-opus-4-5",   "最强推理 · 更贵"),
            new ModelOption("claude-haiku-4-5",  "claude-haiku-4-5",  "快 · 便宜"));

    private final String apiKey;
    private volatile ChatModel chatModel;

    public AnthropicProvider(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override public String id() { return "anthropic"; }

    @Override public boolean available() { return !apiKey.isBlank(); }

    @Override
    public ChatModel chatModel() {
        if (!available()) {
            throw new IllegalStateException("Anthropic 不可用：未配置 ANTHROPIC_API_KEY");
        }
        ChatModel m = chatModel;
        if (m == null) {
            AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            m = AnthropicChatModel.builder()
                    .anthropicClient(client)
                    .options(AnthropicChatOptions.builder()
                            .model(Model.of(DEFAULT_MODEL))
                            .maxTokens(MAX_TOKENS)
                            .build())
                    .build();
            chatModel = m;
        }
        return m;
    }

    @Override
    public ChatOptions options(String modelId) {
        return AnthropicChatOptions.builder()
                .model(Model.of(modelId))
                .maxTokens(MAX_TOKENS)
                .build();
    }

    @Override public List<ModelOption> models() { return MODELS; }

    @Override public String defaultModel() { return DEFAULT_MODEL; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=LlmProviderTest`
Expected: PASS（4 tests）。
（若 `opts.getMaxTokens()` 返回类型不是 `int`/装箱不匹配，改断言为 `assertEquals(Integer.valueOf(8192), opts.getMaxTokens())`。）

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/AnthropicProvider.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/LlmProviderTest.java
git commit -m "feat(code-tui): AnthropicProvider

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `OpenAiProvider`

**Files:**
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/OpenAiProvider.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/LlmProviderTest.java`（追加）

- [ ] **Step 1: 追加失败测试**

```java
    @Test
    void openai_withKey_availableAndOptionsCarryModel() {
        OpenAiProvider p = new OpenAiProvider("fake-key");
        assertEquals("openai", p.id());
        assertTrue(p.available());
        assertEquals("gpt-4o", p.defaultModel());
        assertTrue(p.chatModel() != null);   // 实测网络无关：build() 从 options.apiKey 派生 client
        assertEquals("gpt-4o-mini", p.options("gpt-4o-mini").getModel());
    }

    @Test
    void openai_withoutKey_isUnavailable() {
        OpenAiProvider p = new OpenAiProvider("");
        assertFalse(p.available());
        assertThrows(IllegalStateException.class, p::chatModel);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=LlmProviderTest`
Expected: 编译失败（`OpenAiProvider` 不存在）。

- [ ] **Step 3: 写实现**

```java
package com.example.springai.codetui.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

/**
 * OpenAI provider（Spring AI 2.0 spring-ai-openai）。key 缺失即 unavailable。
 *
 * <p>构建（已实测网络无关）：把 apiKey 设到 {@link OpenAiChatOptions} 上，{@code OpenAiChatModel.build()}
 * 自行派生底层 client——**不用** 供应商 {@code OpenAIOkHttpClient}（不在 classpath，spring-ai-openai 只带
 * {@code openai-java-core}）。
 */
public final class OpenAiProvider implements LlmProvider {

    private static final String DEFAULT_MODEL = "gpt-4o";
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("gpt-4o",      "gpt-4o",      "均衡 · 日常编码"),
            new ModelOption("gpt-4o-mini", "gpt-4o-mini", "快 · 便宜"));

    private final String apiKey;
    private volatile ChatModel chatModel;

    public OpenAiProvider(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override public String id() { return "openai"; }

    @Override public boolean available() { return !apiKey.isBlank(); }

    @Override
    public ChatModel chatModel() {
        if (!available()) {
            throw new IllegalStateException("OpenAI 不可用：未配置 OPENAI_API_KEY");
        }
        ChatModel m = chatModel;
        if (m == null) {
            m = OpenAiChatModel.builder()
                    .options(OpenAiChatOptions.builder().apiKey(apiKey).model(DEFAULT_MODEL).build())
                    .build();
            chatModel = m;
        }
        return m;
    }

    @Override
    public ChatOptions options(String modelId) {
        return OpenAiChatOptions.builder().model(modelId).build();
    }

    @Override public List<ModelOption> models() { return MODELS; }

    @Override public String defaultModel() { return DEFAULT_MODEL; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=LlmProviderTest`
Expected: PASS（6 tests）。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/OpenAiProvider.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/LlmProviderTest.java
git commit -m "feat(code-tui): OpenAiProvider

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `ProviderRegistry`

**Files:**
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/ProviderRegistry.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/ProviderRegistryTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRegistryTest {

    /** 只有可用的 provider 进入 allModels，激活默认走第一个可用 provider 的默认模型。 */
    @Test
    void aggregatesOnlyAvailableProviders() {
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"),       // available
                new AnthropicProvider("k"),      // available
                new OpenAiProvider("")));        // 不可用
        assertEquals("deepseek", reg.active().id());
        assertEquals("deepseek-v4-flash", reg.activeModelId());
        List<String> ids = reg.allModels().stream().map(ModelOption::id).toList();
        assertTrue(ids.contains("deepseek-v4-flash"));
        assertTrue(ids.contains("claude-sonnet-4-5"));
        assertFalse(ids.contains("gpt-4o"));   // openai 不可用，不列出
    }

    /** select 一个跨家模型：激活 provider 与激活模型一起切换。 */
    @Test
    void selectCrossProviderModelSwitchesActive() {
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"), new AnthropicProvider("k")));
        reg.select("claude-opus-4-5");
        assertEquals("anthropic", reg.active().id());
        assertEquals("claude-opus-4-5", reg.activeModelId());
        // activeChatOptions 用激活 provider 的 native options 覆盖模型
        assertEquals("claude-opus-4-5", reg.activeChatOptions().getModel());
    }

    /** select 未知模型：静默忽略（保持原激活），不抛。 */
    @Test
    void selectUnknownModelIsIgnored() {
        ProviderRegistry reg = new ProviderRegistry(List.of(new DeepSeekProvider("k")));
        reg.select("no-such-model");
        assertEquals("deepseek-v4-flash", reg.activeModelId());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=ProviderRegistryTest`
Expected: 编译失败（`ProviderRegistry` 不存在）。

- [ ] **Step 3: 写实现**

```java
package com.example.springai.codetui.agent;

import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * 持有全部 {@link LlmProvider}，记录「当前激活 provider + 激活模型」。
 *
 * <p>初始激活 = 第一个 {@link LlmProvider#available() 可用} 的 provider（构造入参顺序即偏好序，
 * 通常 deepseek 打头），激活模型 = 该 provider 的 {@link LlmProvider#defaultModel()}。
 * {@code /model} 经 {@link #allModels()} 跨家列出可用模型，选中经 {@link #select(String)} 同时切换 provider 与模型。
 */
public final class ProviderRegistry {

    private final List<LlmProvider> providers;   // 全部（含不可用），按偏好序
    private volatile LlmProvider active;
    private volatile String activeModelId;

    public ProviderRegistry(List<LlmProvider> providers) {
        this.providers = List.copyOf(providers);
        LlmProvider first = null;
        for (LlmProvider p : this.providers) {
            if (p.available()) { first = p; break; }
        }
        if (first == null) {
            throw new IllegalStateException("没有任何可用的 LlmProvider（至少需配置一家的 API key）");
        }
        this.active = first;
        this.activeModelId = first.defaultModel();
    }

    public LlmProvider active() { return active; }

    public String activeModelId() { return activeModelId; }

    /** 激活 provider 对该模型的每请求 options（供 CodingAgent.submit 覆盖模型）。 */
    public ChatOptions activeChatOptions() { return active.options(activeModelId); }

    /** 全部可用 provider 的模型聚合（供 /model 展示）。不可用 provider 的模型不列。 */
    public List<ModelOption> allModels() {
        List<ModelOption> all = new ArrayList<>();
        for (LlmProvider p : providers) {
            if (p.available()) {
                all.addAll(p.models());
            }
        }
        return all;
    }

    /**
     * 选中一个模型：在可用 provider 中找拥有该 modelId 的那家，切换激活 provider 与激活模型。
     * modelId 三家全局唯一；找不到（未知/属于不可用家）则静默忽略、保持原激活。
     */
    public void select(String modelId) {
        for (LlmProvider p : providers) {
            if (!p.available()) {
                continue;
            }
            for (ModelOption m : p.models()) {
                if (m.id().equals(modelId)) {
                    this.active = p;
                    this.activeModelId = modelId;
                    return;
                }
            }
        }
        // 未知模型：忽略
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=ProviderRegistryTest`
Expected: PASS（3 tests）。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/ProviderRegistry.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/ProviderRegistryTest.java
git commit -m "feat(code-tui): ProviderRegistry（激活 provider + 跨家 /model 选择）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: `AgentTools.build` 收 `ProviderRegistry`，每可用 provider 各建一个 ChatClient

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentTools.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/AgentRuntimeTest.java`（适配签名）

**背景**：主 ChatClient 绑定一个 `ChatModel`，跨家切换需切换 ChatModel。故为**每个可用 provider 各建一个 ChatClient**，共享同一套装饰工具 + 会话记忆 advisor + 系统模板；`CodingAgent.submit` 按激活 provider 选对应 ChatClient。`auxClient`（SmartWebFetch 抽取 + 压缩摘要）用**激活（默认）provider** 的 chatModel（provider 无关的内部 LLM 调用，绑定在装配期的默认家即可）。

- [ ] **Step 1: 改 `AgentRuntime` 记录，携带 `Map<String,ChatClient> clients` + 便捷 `client()`**

把 `AgentTools.java:253-259` 的 `AgentRuntime` 记录改为（新增 `clients` 字段，`client()` 变便捷方法）：

```java
    public record AgentRuntime(java.util.Map<String, ChatClient> clients,
                               String activeProviderId,
                               SessionService sessionService,
                               SessionRepository sessionRepository,
                               CompactionStrategy manualStrategy,
                               TokenCountEstimator tokenCountEstimator,
                               List<SkillInfo> skills,
                               ToolCallback skillTool) {

        /** 便捷：激活 provider 的 ChatClient（单-provider 用法与旧代码兼容）。 */
        public ChatClient client() { return clients.get(activeProviderId); }
    }
```

（在文件顶部已 `import java.util.List;`；`Map` 用全限定名或另加 `import java.util.Map;`。这里用全限定名避免多改 import。）

- [ ] **Step 2: 改 `build` 签名与实现**

把 `AgentTools.java:147` 的方法签名与方法体改为收 `ProviderRegistry`，并在末尾为每个可用 provider 建 ChatClient。

签名（`AgentTools.java:147`）：

```java
    public static AgentRuntime build(ProviderRegistry registry, Path root, AgentListener listener) {
```

方法体内 `ChatClient auxClient = ChatClient.builder(model).build();`（约 160 行）改为用激活 provider：

```java
        // 「裸」ChatClient：复用给 SmartWebFetch 抽取 + 会话摘要。绑定激活（默认）provider 的模型。
        org.springframework.ai.chat.model.ChatModel activeModel = registry.active().chatModel();
        ChatClient auxClient = ChatClient.builder(activeModel).build();
```

原先构建主 `client` 的段落（`AgentTools.java:229-236`，`ChatClient client = ChatClient.builder(model)...build();`）替换为「为每个可用 provider 建一个 ChatClient」：

```java
        // 为每个可用 provider 各建一个 ChatClient：共享同一套装饰工具 + 会话记忆 advisor + 系统模板，
        // 仅底层 ChatModel 不同。CodingAgent.submit 按激活 provider 选对应 ChatClient 实现跨家切换。
        java.util.Map<String, ChatClient> clients = new java.util.LinkedHashMap<>();
        for (LlmProvider provider : registry.allProviders()) {
            if (!provider.available()) {
                continue;
            }
            ChatClient c = ChatClient.builder(provider.chatModel())
                    .defaultSystem(s -> s.text(SYSTEM_TEMPLATE)
                            .param(AgentEnvironment.ENVIRONMENT_INFO_KEY, AgentEnvironment.info())
                            .param(AgentEnvironment.GIT_STATUS_KEY, AgentEnvironment.gitStatus())
                            .param(AgentEnvironment.AGENT_MODEL_KEY, registry.activeModelId()))
                    .defaultTools((Object[]) decorated)
                    .defaultAdvisors(memoryAdvisor)
                    .build();
            clients.put(provider.id(), c);
        }

        return new AgentRuntime(clients, registry.active().id(), sessionService, sessionRepository,
                manualStrategy, tokenCountEstimator, skills.skills(), decoratedSkillTool);
```

删除原来的 `MODEL_NAME` 用法处（`.param(AgentEnvironment.AGENT_MODEL_KEY, MODEL_NAME)` 已被上面 `registry.activeModelId()` 取代）。`MODEL_NAME` 常量（`AgentTools.java:75`）可删除（不再引用）。

- [ ] **Step 3: 给 `ProviderRegistry` 加 `allProviders()`**

`build` 需要遍历全部 provider。在 `ProviderRegistry` 加：

```java
    /** 全部 provider（含不可用），按偏好序。装配期遍历建 ChatClient 用。 */
    public List<LlmProvider> allProviders() { return providers; }
```

- [ ] **Step 4: 改 `DeepSeekChatModel` import**

`AgentTools.java:13` 的 `import org.springframework.ai.deepseek.DeepSeekChatModel;` 删除（不再直接用）。

- [ ] **Step 5: 适配 `AgentRuntimeTest`**

`AgentRuntimeTest.java:21-30` 的 `dummyModel()` + `AgentTools.build(dummyModel(), root, ...)` 改为用 registry：

把 `dummyModel()` 方法整体替换为：

```java
    private static ProviderRegistry dummyRegistry() {
        return new ProviderRegistry(java.util.List.of(new DeepSeekProvider("fake-key")));
    }
```

并把三处 `AgentTools.build(dummyModel(), root, new ConversationState())`（`AgentRuntimeTest.java:30,51,69`）改为 `AgentTools.build(dummyRegistry(), root, new ConversationState())`。删除不再需要的 `DeepSeekChatModel` / `DeepSeekApi` import（若测试里断言 `rt.client()` 非空，保持——便捷方法仍在）。

- [ ] **Step 6: 编译 + 跑受影响测试**

Run: `mvn -q -pl springai-code-tui test -Dtest='AgentRuntimeTest'`
Expected: PASS（build 装配不发网络，假 key 造出 AgentRuntime，`clients` 含 `"deepseek"` 键，`client()` 非空）。

- [ ] **Step 7: Commit**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/agent/ProviderRegistry.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/AgentRuntimeTest.java
git commit -m "refactor(code-tui): AgentTools.build 收 ProviderRegistry，按 provider 建多 ChatClient

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: `CodingAgent` 走 registry（submit 选 client + options；/model 跨家）

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/CodingAgent.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentModelSwitchTest.java`（新建）

**设计**：新增两字段 `ProviderRegistry registry`（可空）+ `Map<String,ChatClient> clientsByProvider`（可空）与一个「多 provider 生产构造」。`submit`/`models`/`currentModel`/`selectModel` 在 `registry != null` 时走 registry，否则回退旧 DeepSeek 单-client 路径（保住现有测试的单-client 构造）。

- [ ] **Step 1: 写失败测试（registry 路径下的 /model 语义）**

```java
package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** registry 注入后，/model 的三个 SubmitHandler 方法跨家生效。 */
class CodingAgentModelSwitchTest {

    private CodingAgent agentWithRegistry() {
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"), new AnthropicProvider("k")));
        // 生产构造：clientsByProvider 用空 map 占位即可（本测试不调 submit，只验证 /model 语义）
        return new CodingAgent(reg, Map.of(), new StubListener(), "s", new AtomicLong(),
                null, null, null, List.of(), null, null);
    }

    @Test
    void modelsAggregateAcrossProviders() {
        List<String> ids = agentWithRegistry().models().stream().map(ModelOption::id).toList();
        assertTrue(ids.contains("deepseek-v4-flash"));
        assertTrue(ids.contains("claude-sonnet-4-5"));
    }

    @Test
    void selectModelSwitchesProviderAndCurrentModel() {
        CodingAgent a = agentWithRegistry();
        assertEquals("deepseek-v4-flash", a.currentModel());
        a.selectModel("claude-opus-4-5");
        assertEquals("claude-opus-4-5", a.currentModel());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=CodingAgentModelSwitchTest`
Expected: 编译失败（无 `CodingAgent(ProviderRegistry, Map, ...)` 构造）。

- [ ] **Step 3: 加字段 + 生产构造**

在 `CodingAgent.java` 字段区（`:65` `model` 字段附近）加：

```java
    private final ProviderRegistry registry;                       // 可空：多 provider 路径；null 走旧单-client 路径
    private final java.util.Map<String, ChatClient> clientsByProvider;   // 可空：按 provider id 取 ChatClient
```

把现有主构造（`CodingAgent.java:89-103`，11 参数那个）里对这两字段赋值补上 `this.registry = null; this.clientsByProvider = null;`（旧路径默认无 registry）。

新增「多 provider 生产构造」（放在类中构造区）：

```java
    /**
     * 多 provider 生产构造：registry 决定激活 provider 与模型，clientsByProvider 提供各家 ChatClient。
     * submit 按激活 provider 选 ChatClient + 用该家 options 覆盖模型；/model 走 registry 跨家。
     */
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository) {
        this.chatClient = null;
        this.registry = registry;
        this.clientsByProvider = clientsByProvider;
        this.listener = listener;
        this.sessionId = sessionId;
        this.activeTurnId = activeTurnId;
        this.sessionService = sessionService;
        this.manualStrategy = manualStrategy;
        this.tokenCountEstimator = tokenCountEstimator;
        this.skills = List.copyOf(skills);
        this.skillTool = skillTool;
        this.sessionRepository = sessionRepository;
    }
```

- [ ] **Step 4: `models`/`currentModel`/`selectModel` 走 registry**

替换 `CodingAgent.java:189-200` 三个方法：

```java
    @Override
    public List<ModelOption> models() {
        return registry != null ? registry.allModels() : MODELS;
    }

    @Override
    public String currentModel() {
        return registry != null ? registry.activeModelId() : model;
    }

    @Override
    public void selectModel(String id) {
        if (registry != null) {
            registry.select(id);
            return;
        }
        for (ModelOption m : MODELS) {
            if (m.id().equals(id)) { this.model = id; return; }
        }
    }
```

- [ ] **Step 5: `submit` 按激活 provider 选 client + options**

在 `submit`（`CodingAgent.java:110-142`）里，把 `chatClient.prompt()` 与 `.options(DeepSeekChatOptions.builder().model(model))`、`.system(...AGENT_MODEL_KEY, model)` 三处改为 registry 感知。

在方法体 `long turnId = ...` 之后、`return chatClient.prompt()` 之前，插入选路：

```java
        ChatClient client = (registry != null)
                ? clientsByProvider.get(registry.active().id())
                : chatClient;
        org.springframework.ai.chat.prompt.ChatOptions perRequestOptions = (registry != null)
                ? registry.activeChatOptions()
                : DeepSeekChatOptions.builder().model(model).build();
        String modelGrounding = currentModel();
```

把 `return chatClient.prompt()` 改为 `return client.prompt()`；把 `.options(DeepSeekChatOptions.builder().model(model))` 改为 `.options(perRequestOptions)`；把 `.system(s -> s.param(AgentEnvironment.AGENT_MODEL_KEY, model))` 改为 `.system(s -> s.param(AgentEnvironment.AGENT_MODEL_KEY, modelGrounding))`。

（`DeepSeekChatOptions` import 保留——旧路径仍用。）

- [ ] **Step 6: 跑新测试 + 全量回归**

Run: `mvn -q -pl springai-code-tui test -Dtest=CodingAgentModelSwitchTest`
Expected: PASS（2 tests）。

Run: `mvn -q -pl springai-code-tui test -Dtest='!CodingAgentSpikeTest'`
Expected: 全绿（旧单-client 构造的测试仍走回退路径不受影响）。

- [ ] **Step 7: Commit**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentModelSwitchTest.java
git commit -m "feat(code-tui): CodingAgent 走 ProviderRegistry（submit 选 client+options，/model 跨家）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: `CodeTuiApplication` 装配三 provider + registry

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/CodeTuiApplication.java`

- [ ] **Step 1: 重写 main 的装配段**

把 `CodeTuiApplication.java:20-53`（从读 key 到 `new CodingAgent(...)`）替换为：

```java
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        // 三家 provider：谁配了 key 谁 available。至少需一家可用（通常 DeepSeek）。
        ProviderRegistry registry = new ProviderRegistry(java.util.List.of(
                new DeepSeekProvider(System.getenv("DEEPSEEK_API_KEY")),
                new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")),
                new OpenAiProvider(System.getenv("OPENAI_API_KEY"))));

        ConversationState state = new ConversationState();       // implements AgentListener
        AtomicLong activeTurnId = new AtomicLong();
        String sessionId = "code-tui-session";                   // v1 单会话固定 id

        AgentTools.AgentRuntime runtime = AgentTools.build(registry, root, state);
        CodingAgent agent = new CodingAgent(registry, runtime.clients(), state, sessionId, activeTurnId,
                runtime.sessionService(), runtime.manualStrategy(), runtime.tokenCountEstimator(),
                runtime.skills(), runtime.skillTool(), runtime.sessionRepository());
        CodeTuiView view = new CodeTuiView(state, agent, root);
        view.run();
    }
```

`ProviderRegistry` 构造在无任何 key 时抛 `IllegalStateException`——用 try/catch 给出友好提示。在 `AgentTools.build` 之前包一层：把 `ProviderRegistry registry = new ProviderRegistry(...);` 放进 try：

```java
        ProviderRegistry registry;
        try {
            registry = new ProviderRegistry(java.util.List.of(
                    new DeepSeekProvider(System.getenv("DEEPSEEK_API_KEY")),
                    new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")),
                    new OpenAiProvider(System.getenv("OPENAI_API_KEY"))));
        } catch (IllegalStateException e) {
            System.out.println("⚠️  未检测到任何可用大模型 key。请至少配置一个：" +
                    "DEEPSEEK_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY，再运行。");
            return;
        }
```

- [ ] **Step 2: 清理无用 import**

删除 `CodeTuiApplication.java` 顶部不再用的 import：`DeepSeekChatModel`、`DeepSeekChatOptions`、`DeepSeekApi`、`ChatClient`（若不再引用）。保留 `AgentTools`/`CodingAgent`/`CodeTuiView`/`ConversationState`。加 `import com.example.springai.codetui.agent.ProviderRegistry;`、`DeepSeekProvider`、`AnthropicProvider`、`OpenAiProvider`。

- [ ] **Step 3: 编译**

Run: `mvn -q -pl springai-code-tui test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/CodeTuiApplication.java
git commit -m "feat(code-tui): 入口装配三 provider + ProviderRegistry

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: 全量回归 + 打包冒烟

**Files:** 无（验证）

- [ ] **Step 1: 全量测试（排除联网 spike）**

Run: `mvn -q -pl springai-code-tui test -Dtest='!CodingAgentSpikeTest'`
Expected: 全绿。

- [ ] **Step 2: 打包（含 copy-dependencies，确认新依赖进 lib/）**

Run: `mvn -q -pl springai-code-tui -am package -DskipTests -Dtest='!CodingAgentSpikeTest'`
Expected: BUILD SUCCESS；`springai-code-tui/target/lib/` 下出现 `spring-ai-anthropic-*.jar`、`spring-ai-openai-*.jar`、`anthropic-java-*.jar`、`openai-java-*.jar`。

Run: `ls springai-code-tui/target/lib/ | grep -iE "anthropic|openai" | head`
Expected: 有输出。

- [ ] **Step 3: 手动冒烟（可选，需真实 key）**

只配 `DEEPSEEK_API_KEY` 启动 → `/model` 只列 deepseek 两项；加配 `ANTHROPIC_API_KEY` 启动 → `/model` 多出 claude 三项，选中后状态栏模型名变化、后续回合走 Anthropic。
（无 key 环境跳过此步；CI/自动化不做。）

- [ ] **Step 4: Commit（若前面有未提交的收尾）**

```bash
git add -A
git commit -m "test(code-tui): 多 provider 全量回归 + 打包冒烟通过

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" || echo "nothing to commit"
```

---

## Self-Review（作者已核对）

**1. Spec 覆盖**（对应 spec §4.1 / §4.2 / §8）：
- §4.1 `LlmProvider` 六方法 + 三实现 + `ProviderRegistry`（available 过滤 / activate / allModels 聚合）→ Task 2–6。✅
- §4.2 `AgentTools.build` 收 provider、`submit` 用 provider.options、`/model` 跨家、会话历史中立 → Task 7–8（多 ChatClient 共享 sessionService/advisor 即中立性）。✅
- §8 pom 加 anthropic/openai、缺 key 不阻断启动 → Task 1、Task 9（try/catch 友好提示）。✅

**2. Placeholder 扫描**：无 TODO/TBD；每个代码步骤给出完整代码或精确替换位置。Anthropic `getMaxTokens()` 装箱不确定处已给出备选断言（Task 4 Step 4）。

**3. 类型一致性**：`ProviderRegistry` 方法名全程一致（`active()`/`activeModelId()`/`activeChatOptions()`/`allModels()`/`allProviders()`/`select(String)`）；`AgentRuntime.clients()` 与 `CodeTuiApplication` 里 `runtime.clients()`、`CodingAgent` 生产构造入参一致；`LlmProvider.options()` 返回 `ChatOptions`，`submit` 以 `ChatOptions` 接收。

**4. 已知风险 / 留给实现者验证**：
- Anthropic/OpenAI 每请求传各自 native `ChatOptions`（非 portable），与 `ChatClient.prompt().options(ChatOptions)` 的合并语义在真实调用时验证（无 key 时仅装配层已测）。
- `auxClient`（webfetch/摘要）绑定装配期激活 provider；切家后 aux 仍用原家——本版接受（provider 无关的内部调用）。若日后要 aux 跟随切换，另开任务。

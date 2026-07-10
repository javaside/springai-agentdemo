# 统一 LLM 超时（修复流式 callTimeout 误用）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 OpenAI/智谱/Anthropic 三家 provider（共享 spring-ai 的 callTimeout 流式 bug）注入一套「适配流式」的超时——可配 read（默认 300s）、固定 connect 30s、**禁用 callTimeout**——取代默认 read 60s + callTimeout 10min 导致的 `Stream failed`。

**Architecture:** 新增纯配置类 `LlmTimeouts`（读环境变量 `CODETUI_LLM_READ_TIMEOUT_SECONDS`，暴露 read/connect Duration）；新增共享 helper 为 OpenAI-SDK 家族构造 `httpClientBuilderCustomizer`（内含 `request=Duration.ZERO` 的 Timeout）；OpenAiProvider/ZhipuProvider 复用该 helper，AnthropicProvider 用同型 anthropic-SDK customizer。DeepSeek 不改。

**Tech Stack:** Java 17；spring-ai-openai / spring-ai-anthropic 2.0 的 `httpClientBuilderCustomizer`；`com.openai.core.Timeout` / `com.anthropic.core.Timeout`（均有 `builder().connect/read/write/request(Duration).build()`）；JUnit 5。

**Spec:** `docs/superpowers/specs/2026-07-10-unified-llm-timeout-design.md`
**分支:** `fix/llm-stream-timeout`（已含四家 provider）
**验证命令基线:** `mvn -pl springai-code-tui test`

---

## 关键 API（规划期已从字节码坐实，实现时直接用）

- `OpenAiChatModel.Builder.httpClientBuilderCustomizer(OpenAiHttpClientBuilderCustomizer)` — 单个 customizer。
- `OpenAiHttpClientBuilderCustomizer` 是函数式接口：`void customize(SpringAiOpenAiHttpClient.Builder b)`。
- `SpringAiOpenAiHttpClient.Builder.timeout(com.openai.core.Timeout)`。
- `com.openai.core.Timeout.builder().connect(Duration).read(Duration).write(Duration).request(Duration).build()`。
  `request(Duration.ZERO)` → OkHttp `callTimeout(0)` = 禁用总时长超时。
- Anthropic 对应：`AnthropicChatModel.Builder.httpClientBuilderCustomizer(AnthropicHttpClientBuilderCustomizer)`；
  `AnthropicHttpClientBuilderCustomizer.customize(SpringAiAnthropicHttpClient.Builder)`；
  `SpringAiAnthropicHttpClient.Builder.timeout(com.anthropic.core.Timeout)`；`com.anthropic.core.Timeout.builder()...`。

---

## File Structure

- **Create** `.../agent/LlmTimeouts.java` — 纯配置：环境变量 → `readTimeout()`/`connectTimeout()`（Duration）。
- **Create** `.../agent/OpenAiTimeoutCustomizer.java` — 静态工厂：`OpenAiHttpClientBuilderCustomizer of(LlmTimeouts)`，构造 `com.openai.core.Timeout`（request=ZERO）。OpenAiProvider + ZhipuProvider 共用。
- **Modify** `.../agent/OpenAiProvider.java` — `chatModel()` 加 `.httpClientBuilderCustomizer(OpenAiTimeoutCustomizer.of(TIMEOUTS))`。
- **Modify** `.../agent/ZhipuProvider.java` — 同上。
- **Modify** `.../agent/AnthropicProvider.java` — `chatModel()` 加 anthropic 版 customizer（内联，唯一使用者）。
- **Create tests** `LlmTimeoutsTest.java`（纯配置全覆盖）；在 `LlmProviderTest.java` 复用现有「装配不抛异常」断言即可覆盖三家接线（已有 customBaseUrl/blankBaseUrl 测试会走新 customizer 路径）。
- **不改** `DeepSeekProvider.java`。

---

## Task 1: LlmTimeouts 配置类（纯函数，TDD 全覆盖）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/LlmTimeouts.java`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/LlmTimeoutsTest.java`

- [ ] **Step 1: 写失败测试**

Create `LlmTimeoutsTest.java`:

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** LlmTimeouts 配置解析：默认/非法/钳制边界。纯函数，注入取值函数避免依赖真实环境变量。 */
class LlmTimeoutsTest {

    @Test
    void default_whenUnset_is300sRead_and30sConnect() {
        LlmTimeouts t = LlmTimeouts.from(name -> null);   // 环境变量缺失
        assertEquals(Duration.ofSeconds(300), t.readTimeout());
        assertEquals(Duration.ofSeconds(30), t.connectTimeout());
    }

    @Test
    void blankOrGarbage_fallsBackTo300() {
        assertEquals(Duration.ofSeconds(300), LlmTimeouts.from(n -> "  ").readTimeout());
        assertEquals(Duration.ofSeconds(300), LlmTimeouts.from(n -> "abc").readTimeout());
    }

    @Test
    void validValue_isUsed() {
        assertEquals(Duration.ofSeconds(90), LlmTimeouts.from(n -> "90").readTimeout());
    }

    @Test
    void clampsToRange_10_to_3600() {
        assertEquals(Duration.ofSeconds(10), LlmTimeouts.from(n -> "1").readTimeout());     // 下限
        assertEquals(Duration.ofSeconds(3600), LlmTimeouts.from(n -> "99999").readTimeout()); // 上限
    }

    @Test
    void connectTimeout_isAlwaysFixed30s_regardlessOfRead() {
        assertEquals(Duration.ofSeconds(30), LlmTimeouts.from(n -> "1200").connectTimeout());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -pl springai-code-tui test -Dtest='LlmTimeoutsTest'`
Expected: 编译失败 — `LlmTimeouts` / `from` 未定义。

- [ ] **Step 3: 实现 LlmTimeouts**

Create `LlmTimeouts.java`:

```java
package io.github.javaside.springai.codetui.agent;

import java.time.Duration;
import java.util.function.Function;

/**
 * LLM 请求超时配置（适配流式）。单一职责：读环境变量 {@code CODETUI_LLM_READ_TIMEOUT_SECONDS}，
 * 暴露 read/connect 两个 {@link Duration}。callTimeout（总时长）由各 provider 的接线固定禁用（request=0），
 * 不在此暴露——见设计文档「根因」。
 *
 * <p>read 默认 300s（取代 SDK 默认 60s 过短之祸）；钳制到 [10, 3600]。connect 固定 30s。
 */
public final class LlmTimeouts {

    /** 环境变量名：模型两个数据块之间的最大间隔（秒）。 */
    public static final String READ_TIMEOUT_ENV = "CODETUI_LLM_READ_TIMEOUT_SECONDS";

    private static final int DEFAULT_READ_SECONDS = 300;
    private static final int MIN_READ_SECONDS = 10;
    private static final int MAX_READ_SECONDS = 3600;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final Duration readTimeout;

    private LlmTimeouts(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /** 从真实环境变量构建。 */
    public static LlmTimeouts fromEnv() {
        return from(System::getenv);
    }

    /** 注入取值函数构建（便于单测，不依赖真实环境）。 */
    public static LlmTimeouts from(Function<String, String> env) {
        return new LlmTimeouts(Duration.ofSeconds(resolveReadSeconds(env.apply(READ_TIMEOUT_ENV))));
    }

    private static int resolveReadSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_READ_SECONDS;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return Math.max(MIN_READ_SECONDS, Math.min(MAX_READ_SECONDS, v));
        } catch (NumberFormatException e) {
            return DEFAULT_READ_SECONDS;
        }
    }

    public Duration readTimeout() { return readTimeout; }

    public Duration connectTimeout() { return CONNECT_TIMEOUT; }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -pl springai-code-tui test -Dtest='LlmTimeoutsTest'`
Expected: 5 tests PASS。

- [ ] **Step 5: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/LlmTimeouts.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/LlmTimeoutsTest.java
git commit -m "feat(timeout): LlmTimeouts 超时配置解析（read 默认 300s，钳制 [10,3600]）"
```

---

## Task 2: OpenAI-SDK 家族的超时 customizer + OpenAI/智谱接线

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/OpenAiTimeoutCustomizer.java`
- Modify: `.../agent/OpenAiProvider.java`
- Modify: `.../agent/ZhipuProvider.java`
- Modify (test): `.../agent/LlmProviderTest.java`

- [ ] **Step 1: 实现共享 customizer 工厂**

Create `OpenAiTimeoutCustomizer.java`:

```java
package io.github.javaside.springai.codetui.agent;

import com.openai.core.Timeout;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

/**
 * 为 OpenAI-SDK 家族（OpenAI / 智谱，共用 spring-ai-openai）构造超时 customizer。
 *
 * <p>核心修复：把 SDK Timeout 的 {@code request} 段设为 {@link java.time.Duration#ZERO} —— spring-ai 会把它映射到
 * OkHttp {@code callTimeout}，ZERO=禁用「整个调用总时长超时」（流式响应不能被总时长砍断）。read=配置值
 * （取代默认 60s 过短）、connect=固定 30s。write 复用 read（写请求体一般很快，给足即可）。
 */
final class OpenAiTimeoutCustomizer {

    private OpenAiTimeoutCustomizer() {}

    static OpenAiHttpClientBuilderCustomizer of(LlmTimeouts timeouts) {
        Timeout t = Timeout.builder()
                .connect(timeouts.connectTimeout())
                .read(timeouts.readTimeout())
                .write(timeouts.readTimeout())
                .request(java.time.Duration.ZERO)   // 禁用 callTimeout
                .build();
        return builder -> builder.timeout(t);
    }
}
```

- [ ] **Step 2: OpenAiProvider 接入**

在 `OpenAiProvider.java` 顶部加字段（类体，与其它 static final 常量并列）：
```java
    private static final LlmTimeouts TIMEOUTS = LlmTimeouts.fromEnv();
```
在 `chatModel()` 里，把
```java
            m = OpenAiChatModel.builder()
                    .options(opts.build())
                    .build();
```
改为
```java
            m = OpenAiChatModel.builder()
                    .options(opts.build())
                    .httpClientBuilderCustomizer(OpenAiTimeoutCustomizer.of(TIMEOUTS))
                    .build();
```
> 注意：`OpenAiProvider` 现有代码里 opts 是 builder，末尾 `.build()`。按实际变量名匹配（可能是 `opts.build()` 或已 build 的 `opts`）。

- [ ] **Step 3: ZhipuProvider 接入**

同理，在 `ZhipuProvider.java` 加 `private static final LlmTimeouts TIMEOUTS = LlmTimeouts.fromEnv();`，并把
```java
            m = OpenAiChatModel.builder()
                    .options(opts)
                    .build();
```
改为
```java
            m = OpenAiChatModel.builder()
                    .options(opts)
                    .httpClientBuilderCustomizer(OpenAiTimeoutCustomizer.of(TIMEOUTS))
                    .build();
```

- [ ] **Step 4: 复用现有装配测试验证不抛异常**

`LlmProviderTest` 已有 `openai_withKey_availableAndOptionsCarryModel`、`customBaseUrl_isAcceptedAndModelStillBuilds`、`blankBaseUrl_fallsBackToDefaultAndStillBuilds`、以及 zhipu 的装配测试——它们都调 `chatModel()`，现在会走新 customizer 路径。运行确认仍网络无关地建出 model、不抛异常：

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -pl springai-code-tui test -Dtest='LlmProviderTest'`
Expected: PASS（含 openai/zhipu 装配）。

- [ ] **Step 5: 编译并跑相关测试**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -pl springai-code-tui test -Dtest='LlmTimeoutsTest,LlmProviderTest'`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/OpenAiTimeoutCustomizer.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/OpenAiProvider.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ZhipuProvider.java
git commit -m "fix(timeout): OpenAI/智谱注入超时 customizer（禁用 callTimeout，read 可配）"
```

---

## Task 3: Anthropic 接线（同型 anthropic-SDK customizer）

**Files:**
- Modify: `.../agent/AnthropicProvider.java`

- [ ] **Step 1: 确认 anthropic Timeout builder 形状**

Run:
```bash
cd /tmp && rm -rf ac && mkdir ac && cd ac && \
unzip -oq /Users/zxh/.m2/repository/com/anthropic/anthropic-java-core/2.43.0/anthropic-java-core-2.43.0.jar && \
javap -p 'com/anthropic/core/Timeout$Builder.class' | grep -iE "connect|read|write|request|build"
```
Expected: 看到 `connect(Duration)/read(Duration)/write(Duration)/request(Duration)/build()`（与 OpenAI 同型）。若签名不同，据实调整下一步代码。

- [ ] **Step 2: AnthropicProvider 接入 customizer**

在 `AnthropicProvider.java` 加 `import java.time.Duration;`（若无）与字段：
```java
    private static final LlmTimeouts TIMEOUTS = LlmTimeouts.fromEnv();
```
在 `chatModel()` 里，`AnthropicChatModel.builder()...build()` 链上加 customizer。定位现有：
```java
            m = AnthropicChatModel.builder()
                    .options(opts.build())
                    .build();
```
改为：
```java
            m = AnthropicChatModel.builder()
                    .options(opts.build())
                    .httpClientBuilderCustomizer(b -> b.timeout(
                            com.anthropic.core.Timeout.builder()
                                    .connect(TIMEOUTS.connectTimeout())
                                    .read(TIMEOUTS.readTimeout())
                                    .write(TIMEOUTS.readTimeout())
                                    .request(Duration.ZERO)   // 禁用 callTimeout（同 OpenAI-SDK 家族的流式 bug）
                                    .build()))
                    .build();
```
> 按 `AnthropicProvider` 实际的 opts 变量名匹配。

- [ ] **Step 3: 复用现有装配测试验证**

`LlmProviderTest` 的 `anthropic_withKey_*` / `customBaseUrl` / `blankBaseUrl` 会走新路径。运行：

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -pl springai-code-tui test -Dtest='LlmProviderTest'`
Expected: PASS（含 anthropic 装配，网络无关不抛异常）。

- [ ] **Step 4: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AnthropicProvider.java
git commit -m "fix(timeout): Anthropic 注入超时 customizer（禁用 callTimeout，read 可配）"
```

---

## Task 4: 全量验证 + 实机烟测指引

**Files:** 无（仅验证）

- [ ] **Step 1: 全量模块测试**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -pl springai-code-tui test`
Expected: BUILD SUCCESS，全部通过（既有 259 + 新增 LlmTimeoutsTest 5）。

- [ ] **Step 2: 打包冒烟（确认可启动、无装配异常）**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -pl springai-code-tui -Pdist package -DskipTests`
Expected: 产出 dist 包，无错误。

- [ ] **Step 3: 生成给用户的实机烟测指引（写入本步骤输出，不改代码）**

产出一段说明交用户执行（这是唯一能真正验证「超时类型改对」的手段）：
- 设 `CODETUI_LLM_READ_TIMEOUT_SECONDS=5` + 用之前会 Stream failed 的 OpenAI/智谱 relay，发一条消息。
- 预期：约 **5 秒**内干净失败（不再是 60s 主凶被砍或 10min 卡死），UI 正常回 IDLE、下一轮可继续。
- 再把环境变量去掉（回默认 300s）确认正常对话不被误杀。

- [ ] **Step 4: Commit（如有 dist 相关无代码变更则跳过；本任务通常无提交）**

无代码改动则不提交。

---

## Self-Review

**1. Spec coverage（逐项对照 spec）:**
- 单一配置入口 `CODETUI_LLM_READ_TIMEOUT_SECONDS`、默认 300、钳制 [10,3600] → Task 1 `LlmTimeouts` + 测试。✅
- read 取代 60s、connect 30s、**禁用 callTimeout(request=ZERO)** → Task 2 `OpenAiTimeoutCustomizer` + Task 3 Anthropic。✅
- OpenAI/智谱共享 helper → Task 2。✅
- Anthropic 用 customizer + request=ZERO（非 options.timeout）→ Task 3。✅
- DeepSeek 保持现状不改 → 明确不在任何 Task 触碰。✅
- 作用主 agent(stream) 与子 agent(call)：同一 ChatModel 派生的 client 覆盖两条路径 → 由接线位置（chatModel()）保证。✅
- 测试策略：LlmTimeouts 纯函数全覆盖 + 复用装配不抛异常测试 + 实机烟测 → Task 1/2/3/4。✅

**2. Placeholder scan:** 无 TBD/TODO。所有代码块完整；Task 3 Step 1 是「确认 anthropic Timeout builder 形状」的真实校验步骤（含命令与预期），非占位。

**3. Type consistency:**
- `LlmTimeouts.from(Function<String,String>)` / `fromEnv()` / `readTimeout()` / `connectTimeout()` — Task 1 定义，Task 2/3 一致引用。
- `OpenAiTimeoutCustomizer.of(LlmTimeouts)` → `OpenAiHttpClientBuilderCustomizer` — Task 2 定义与使用一致。
- `com.openai.core.Timeout` / `com.anthropic.core.Timeout` 的 `builder().connect/read/write/request(Duration).build()` — 两家同型（Task 3 Step 1 先校验 anthropic 侧）。
- `request(Duration.ZERO)` 禁用 callTimeout — 两处一致。

无不一致。

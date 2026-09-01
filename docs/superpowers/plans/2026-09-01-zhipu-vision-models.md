# 智谱视觉模型接入 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让智谱的视觉模型（内置 `glm-5.3-flash`，自配 `glm-4.6v` / `glm-4.5v` / `glm-5v` 系）在 code-tui 里真正收到图片——补名单、补内置清单、补上下文窗口、真机冒烟、更新文档。

**Architecture:** 智谱 provider 走 spring-ai-openai 兼容通路，图片由既有 `VisionMaterializingChatModel → Media → UserMessage` 链路兑现为 OpenAI `image_url`（base64 data URL）格式——智谱 v4 API 原生接受该格式，**不需要任何 HTTP 改写**（与 DeepSeek 的本质区别：spring-ai-openai 正确序列化 Media，spring-ai-deepseek 会丢弃）。改动全部是元数据（名单/清单/窗口表）+ 验证探针 + 文档。

**Tech Stack:** Java 17（`maven.compiler.release=17`，无类型模式 switch、无 record pattern）、Spring AI 2.0（spring-ai-openai 通路，现有）、JUnit 5（`org.junit.jupiter.api.Assertions`，期望在前）。

**Spec:** 本计划「背景事实」章节（自含，无独立 spec 文档）。

---

## 背景事实（依据，执行者必读）

2026-09-01 调查核实的官方事实，所有任务从这里出发：

| 模型 | API id | 发布 | 上下文 | 工具调用 | 图片格式 | 备注 |
|---|---|---|---|---|---|---|
| GLM-4.5V | `glm-4.5v` | 2025-08 | 64K | 未提及 | `image_url`（URL/base64） | 旧线，仅自配 |
| GLM-4.6V / Flash | `glm-4.6v` / `glm-4.6v-flash` | 2025-12 | 128K | **原生 Function Call** | 同上 | Flash 免费；¥1/M 入 |
| GLM-5V-Turbo | 前缀 `glm-5v`（确切 API id 未核实） | 2026-04 | 200K | 支持 | — | 多模态 Coding 基座 |
| **GLM-5.3-Flash** | **`glm-5.3-flash`** | **2026-08-26** | **1M**（出 128K） | **支持（流式建议 `tool_stream: true`，本工具不发该参数）** | `image_url`（URL/base64 data URL） | GLM-5 系首个**原生多模态**；思考不可关、effort low/high/max 与 glm-5.3 一致；价格为 glm-5.3 的 1/10 |

关键结论：

1. **现状缺口**：`VisionModels.VISION_PREFIXES` 只有 `glm-4v/glm-4.1v/glm-4.5v`；`ZhipuProvider.MODELS` 四个内置模型（glm-5.3/5.2/5.1/5-turbo）无一命中名单——智谱用户当前完全用不上视觉。
2. **通路免改**：`ZhipuProvider` 复用 `OpenAiChatModel`，`Media` 会被序列化为 `image_url` base64——与智谱 v4 格式天然对齐。
3. **两个风险点，靠 Task 4 真机探针回答**：
   - 智谱文档建议流式 + 工具同开 `tool_stream: true`，spring-ai-openai 不发该参数——普通 `stream + tools` 是否正常吐 `tool_calls` 须实测（先例：千问 SSE 分片坑，`QwenSseNormalizingHttpClient`）；
   - base64 data URL 经 `Media` 序列化后智谱是否照单全收（先例：DeepSeek 需要 HTTP 改写，智谱预期不需要——探针证伪即停）。
4. **决策**：内置清单加 `glm-5.3-flash` 但**保持 `glm-5.3` 为默认**（首位不动）——已存偏好不受影响，新项目默认不悄悄换模型；`glm-4.6v`/`glm-5v` 系不进内置清单（`glm-5v` 确切 id 未核实），用户经 `ZHIPU_MODELS` 自配。

来源：[GLM-5.3-Flash 官方文档](https://docs.bigmodel.cn/cn/guide/models/vlm/glm-5.3-flash)、[GLM-4.6V 官方文档](https://docs.bigmodel.cn/cn/guide/models/vlm/glm-4.6v)、[GLM-4.5V 官方文档](https://docs.bigmodel.cn/cn/guide/models/vlm/glm-4.5v)。

---

## 全局纪律（每个任务都适用）

- **验证命令模块作用域**：`mvn test -pl springai-code-tui -Dtest=XxxTest`。不加
  `-DfailIfNoSpecifiedTests`（单模块 + 测试确实存在于该模块时不需要）。
- **Java 17**：不写类型模式 switch、不写 record pattern；`instanceof` 用传统写法。
- **断言用 JUnit `org.junit.jupiter.api.Assertions`**（本模块无 assertj）：
  `assertEquals(期望, 实际)`（**期望在前**）、`assertTrue/assertFalse(x, "消息")`。
- **提交信息中文**：`feat(vision): …` / `feat(llm): …` / `test(vision): …` / `docs(vision): …`。
- **每个任务结束前跑该任务涉及的测试类，绿了才提交。**
- **真机测试不落明文 key**：从 `~/.secrets` source 环境变量（`source ~/.secrets && CODETUI_LIVE_TESTS=1 …`）。

---

## 文件结构

**修改**（无新建主代码文件——这是元数据接入，不是新通路）：

| 文件 | 改动 |
|---|---|
| `agent/media/VisionModels.java:23-28` | `VISION_PREFIXES` 加 `glm-4.6v`、`glm-5.3-flash`、`glm-5v` |
| `agent/llm/ZhipuProvider.java:33-37, 94-103` | `MODELS` 加 `glm-5.3-flash`（第二位）；`thinkingCapabilities` 加 flash 分支 |
| `agent/compaction/ModelContextWindows.java:14-25` | `BUILT_INS` 加 3 条智谱视觉窗口 |

**新建测试**（`springai-code-tui/src/test/java/io/github/javaside/springai/codetui/`）：

| 测试 | 覆盖 |
|---|---|
| `agent/media/VisionModelsTest.java`（改） | 新前缀命中；纯文本 glm-5.x 不命中（防 `glm-5.3` 被 `glm-5.3-flash` 前缀误伤） |
| `agent/llm/ZhipuProviderVisionTest.java`（新） | 内置清单、默认不变、capabilities 按模型、thinking 分支、options 带 effort |
| `agent/compaction/ModelContextWindowsTest.java`（新） | 三条窗口命中；未收录 glm-4v 落兜底 |
| `agent/llm/ZhipuVisionSmokeTest.java`（新） | 真机探针（双 gate，默认跳过）：纯图、流式+工具、图+工具复合 |

**文档**：`docs/guide/vision.md`（名单表格、内置清单注记、验证范围）。

---

### Task 1: VisionModels 前缀名单

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionModels.java:23-28`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionModelsTest.java`

**Interfaces:**
- Consumes: `VisionModels.supportsImage(String)` / `enabledFor(String)`（现有，签名不变）。
- Produces: 无新接口——只扩 `VISION_PREFIXES` 常量；Task 2 的 `capabilities()` 经它自动生效。

- [ ] **Step 1: 写失败测试**

在 `VisionModelsTest` 追加两个用例（类已存在，含 `assertTrue/assertFalse` 静态导入）：

```java
    /** 智谱视觉线 2025-12 起的新前缀（glm-4.6v / glm-5.3-flash / glm-5v），2026-09-01 接入。 */
    @Test
    void zhipuNewVisionLineIsSupported() {
        assertTrue(VisionModels.supportsImage("glm-4.6v"), "glm-4.6v");
        assertTrue(VisionModels.supportsImage("glm-4.6v-flash"), "glm-4.6v-flash（免费档）");
        assertTrue(VisionModels.supportsImage("glm-5.3-flash"), "glm-5.3-flash（GLM-5 首个原生多模态）");
        assertTrue(VisionModels.supportsImage("glm-5v-turbo"), "glm-5v 系（GLM-5V-Turbo，确切 id 未核实但前缀安全）");
    }

    /** 关键防误伤：glm-5.3-flash 是视觉模型不代表 glm-5.3 是——前缀必须整体匹配到 flash。 */
    @Test
    void zhipuTextFlagshipsAreNotSupported() {
        assertFalse(VisionModels.supportsImage("glm-5.3"), "glm-5.3 纯文本旗舰");
        assertFalse(VisionModels.supportsImage("glm-5.2"), "glm-5.2");
        assertFalse(VisionModels.supportsImage("glm-5.1"), "glm-5.1");
        assertFalse(VisionModels.supportsImage("glm-5-turbo"), "glm-5-turbo");
        assertFalse(VisionModels.supportsImage("glm-5"), "裸 glm-5 不是 glm-5v");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=VisionModelsTest`
Expected: `zhipuNewVisionLineIsSupported` FAIL（四个 assertTrue 全不命中）；`zhipuTextFlagshipsAreNotSupported` PASS（现状即不命中，它防的是将来回归）。

- [ ] **Step 3: 最小实现**

`VisionModels.java` 的 `VISION_PREFIXES` 改为：

```java
    /** 前缀名单（小写比较）。加新模型时只动这里。 */
    private static final List<String> VISION_PREFIXES = List.of(
            "gpt-5.", "gpt-4o", "o4-",
            "claude-",
            "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen3-vl",
            "glm-4v", "glm-4.1v", "glm-4.5v",
            "glm-4.6v",                        // ★ 2025-12 上线，首个原生 Function Call 的视觉线
            "glm-5.3-flash",                   // ★ 2026-08-26 上线，GLM-5 系首个原生多模态（注意：glm-5.3 本身仍是纯文本）
            "glm-5v",                          // ★ GLM-5V 系（2026-04 GLM-5V-Turbo，多模态 Coding 基座）
            "deepseek-v4-flash-vision"         // ★ DeepSeek 视觉实验模型（2026-08-21 上线）
    );
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=VisionModelsTest`
Expected: 全部 PASS（含既有的 4 个用例不回归）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionModels.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionModelsTest.java
git commit -m "feat(vision): 智谱视觉名单补 glm-4.6v / glm-5.3-flash / glm-5v 前缀"
```

---

### Task 2: ZhipuProvider 内置清单 + thinking 分支

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/ZhipuProvider.java:31-37, 94-103`
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/ZhipuProviderVisionTest.java`

**Interfaces:**
- Consumes: `ModelOption(String id, String label, String desc)` record、`ThinkingCapabilities.effort(boolean, List<String>)`、`ThinkingConfig.enabledEffort(String)`、`OpenAiChatOptions.getReasoningEffort()`（均现有）。
- Produces: `ZhipuProvider.models()` 含 `glm-5.3-flash`（Task 5 文档、用户 `/model` 面板依赖）；`thinkingCapabilities("glm-5.3-flash")` 返回不可关闭三档。

- [ ] **Step 1: 写失败测试**

新建 `ZhipuProviderVisionTest.java`：

```java
package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智谱视觉接入的 provider 侧元数据：内置清单、按模型判定的视觉能力、glm-5.3-flash 的
 * thinking 限制（官方文档：与 glm-5.3 一致——仅支持开启、effort low/high/max）。
 */
class ZhipuProviderVisionTest {

    @Test
    void builtinListIncludesNativeMultimodalFlashButDefaultStaysFlagship() {
        ZhipuProvider p = new ZhipuProvider("k");
        assertTrue(p.models().stream().anyMatch(m -> "glm-5.3-flash".equals(m.id())),
                "内置清单应含 glm-5.3-flash（2026-08-26 原生多模态）");
        assertEquals("glm-5.3", p.defaultModel(),
                "默认仍是旗舰 glm-5.3——加模型不悄悄改默认，已存偏好与新项目都不受影响");
    }

    /** 视觉按模型判定：flash 命中，纯文本旗舰不命中；自配的 glm-4.6v 也命中。 */
    @Test
    void visionCapabilityIsPerModel() {
        ZhipuProvider p = new ZhipuProvider("k");
        assertTrue(p.capabilities("glm-5.3-flash").supportsImageInput(), "glm-5.3-flash 原生多模态");
        assertTrue(p.capabilities("glm-4.6v").supportsImageInput(), "用户经 ZHIPU_MODELS 自配的 glm-4.6v");
        assertFalse(p.capabilities("glm-5.3").supportsImageInput(), "glm-5.3 纯文本");
        assertFalse(p.capabilities("glm-5.2").supportsImageInput(), "glm-5.2 纯文本");
    }

    /** 官方文档：glm-5.3-flash 文本参数与 glm-5.3 一致——思考不可关、effort low/high/max。 */
    @Test
    void flashThinkingMirrorsGlm53() {
        ZhipuProvider p = new ZhipuProvider("k");
        assertFalse(p.thinkingCapabilities("glm-5.3-flash").supportsDisable(), "思考不可关闭");
        assertEquals(List.of("low", "high", "max"), p.thinkingCapabilities("glm-5.3-flash").effortValues());
        assertThrows(IllegalArgumentException.class,
                () -> p.options("glm-5.3-flash", ThinkingConfig.disabled()));
    }

    @Test
    void flashOptionsCarryEffort() {
        ZhipuProvider p = new ZhipuProvider("k");
        OpenAiChatOptions opts = (OpenAiChatOptions) p.options("glm-5.3-flash",
                ThinkingConfig.enabledEffort("max"));
        assertEquals("max", opts.getReasoningEffort());
        assertEquals("glm-5.3-flash", opts.getModel());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=ZhipuProviderVisionTest`
Expected: `builtinListIncludesNativeMultimodalFlashButDefaultStaysFlagship` FAIL（清单无 flash）；`flashThinkingMirrorsGlm53` FAIL（走 `toggle(true)` 兜底，`supportsDisable` 为 true）；`visionCapabilityIsPerModel` FAIL（前缀未生效——若 Task 1 已合入则 flash/glm-4.6v 两断言 PASS，glm-5.3 断言 PASS，仅剩依赖 Task 1 的部分注意顺序）；`flashOptionsCarryEffort` 可能 PASS（`options` 走通用路径）。

- [ ] **Step 3: 最小实现**

`ZhipuProvider.java` 两处改动。

（a）`MODELS` 清单与注释（`glm-5.3-flash` 插第二位，**首位不动**）：

```java
    // 2026 在售 GLM-5 系：glm-5.3 旗舰（2026-08-14 发布，同 5.2 基座、后训练强化，编码 +50%）
    // / glm-5.3-flash 原生多模态（2026-08-26 发布，视觉+编码，1M 上下文，价 1/10，性能超 glm-5.2）
    // / glm-5.2 上代旗舰 / glm-5.1 长任务 / glm-5-turbo 快档。
    // 视觉线 glm-4.6v / glm-5v 系不进内置清单（glm-5v 确切 API id 未核实），经 ZHIPU_MODELS 自配。
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("glm-5.3",       "glm-5.3",       "旗舰 · Agentic 编码/长上下文"),
            new ModelOption("glm-5.3-flash", "glm-5.3-flash", "原生多模态 · 视觉+编码 · 1M 上下文 · 1/10 价"),
            new ModelOption("glm-5.2",       "glm-5.2",       "上代旗舰 · Agentic 编码"),
            new ModelOption("glm-5.1",       "glm-5.1",       "长任务 · 自规划"),
            new ModelOption("glm-5-turbo",   "glm-5-turbo",   "快 · 便宜"));
```

（b）`thinkingCapabilities` 加 flash 分支（放在 glm-5.3 分支旁）：

```java
    @Override
    public ThinkingCapabilities thinkingCapabilities(String modelId) {
        // glm-5.3（官方文档）：仅支持开启思考、不可禁用；reasoning_effort 取 low/high/max（默认 max）。
        if ("glm-5.3".equals(modelId)) {
            return ThinkingCapabilities.effort(false, List.of("low", "high", "max"));
        }
        // glm-5.3-flash（官方文档）：文本参数与 glm-5.3 一致——thinking.type 仅支持 enabled，effort 同三档。
        if ("glm-5.3-flash".equals(modelId)) {
            return ThinkingCapabilities.effort(false, List.of("low", "high", "max"));
        }
        if ("glm-5.2".equals(modelId)) {
            return ThinkingCapabilities.effort(true, List.of("high", "max"));
        }
        return ThinkingCapabilities.toggle(true);
    }
```

- [ ] **Step 4: 跑测试确认通过（含既有测试不回归）**

Run: `mvn test -pl springai-code-tui -Dtest='ZhipuProviderVisionTest,LlmProviderTest,ProviderModelsEnvTest,ProviderThinkingOptionsTest,ProviderCapabilitiesTest,ProviderRegistryThinkingTest'`
Expected: 全部 PASS。特别核对：`LlmProviderTest.zhipu_withKey_availableAndOptionsCarryModel` 仍断言默认 `glm-5.3`（首位没动，不应破）；`ProviderModelsEnvTest.zhipu_noEnv_builtInDefault` 同理。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/ZhipuProvider.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/ZhipuProviderVisionTest.java
git commit -m "feat(llm): 智谱内置清单加 glm-5.3-flash（原生多模态）并补 thinking 限制"
```

---

### Task 3: ModelContextWindows 内置窗口

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/compaction/ModelContextWindows.java:14-25`
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/compaction/ModelContextWindowsTest.java`

**Interfaces:**
- Consumes: `ModelContextWindows.parse(String, long)`（包私有静态）、`resolve(String providerId, String modelId)`（public）、`DEFAULT_UNKNOWN_WINDOW`（包私有常量，128_000L）。
- Produces: 无新接口——`BUILT_INS` 扩 3 条，压缩阈值/`/context` 面板经 `resolve` 自动取对。

**事实口径**（写进注释与测试）：`glm-5.3-flash` = 1M（官方文档）、`glm-4.6v` = 128K（官方文档）、`glm-4.5v` = 64K（官方文档）。`glm-4v` / `glm-4.1v` 的窗口**未核实**，不进表——落保守兜底 128k，用户可用 `CODETUI_CONTEXT_WINDOWS` 覆盖。

- [ ] **Step 1: 写失败测试**

新建 `ModelContextWindowsTest.java`（与被测类同包，吃到包私有可见性）：

```java
package io.github.javaside.springai.codetui.agent.compaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 智谱视觉模型的内置窗口（2026-09-01 官方文档核实）：flash 1M / 4.6v 128K / 4.5v 64K。 */
class ModelContextWindowsTest {

    private final ModelContextWindows windows =
            ModelContextWindows.parse(null, ModelContextWindows.DEFAULT_UNKNOWN_WINDOW);

    @Test
    void zhipuVisionWindowsAreBuiltIn() {
        assertEquals(1_000_000L, windows.resolve("zhipu", "glm-5.3-flash"));
        assertEquals(128_000L, windows.resolve("zhipu", "glm-4.6v"));
        assertEquals(64_000L, windows.resolve("zhipu", "glm-4.5v"));
    }

    @Test
    void unverifiedZhipuVisionModelsFallBackConservatively() {
        // glm-4v / glm-4.1v 窗口未核实 → 不进表，落保守兜底 128k（可用 CODETUI_CONTEXT_WINDOWS 覆盖）。
        assertEquals(ModelContextWindows.DEFAULT_UNKNOWN_WINDOW, windows.resolve("zhipu", "glm-4v-plus"));
        assertEquals(ModelContextWindows.DEFAULT_UNKNOWN_WINDOW, windows.resolve("zhipu", "glm-4.1v-thinking"));
    }

    @Test
    void textFlagshipEntryUnaffected() {
        assertEquals(1_000_000L, windows.resolve("zhipu", "glm-5.3"), "既有条目不因新增而漂移");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=ModelContextWindowsTest`
Expected: `zhipuVisionWindowsAreBuiltIn` FAIL（三条都落兜底 128_000；其中 glm-4.6v 的期望值恰好也是 128_000 会碰巧过，另两条必失败——失败断言数 ≥2 即符合预期）。其余两个用例 PASS。

- [ ] **Step 3: 最小实现**

`BUILT_INS` 在 `zhipu:glm-5.3` 条目后追加三行：

```java
            Map.entry("zhipu:glm-5.3", 1_000_000L),
            // 智谱视觉线（2026-09-01 官方文档核实）：flash 1M / 4.6v 128K / 4.5v 64K；
            // glm-4v / glm-4.1v 窗口未核实，不进表——落保守兜底，用户可经 CODETUI_CONTEXT_WINDOWS 覆盖。
            Map.entry("zhipu:glm-5.3-flash", 1_000_000L),
            Map.entry("zhipu:glm-4.6v", 128_000L),
            Map.entry("zhipu:glm-4.5v", 64_000L),
```

注意：`Map.ofEntries` 上限 10 个键值对，追加后共 14 条——**必须**把 `Map.ofEntries(...)` 换成 `Map.entry` 列表 + `Map.of(...)` 不行，直接改用静态初始化：

```java
    private static final Map<String, Long> BUILT_INS = java.util.Map.ofEntries(
            Map.entry("openai:gpt-5.6-sol", 1_050_000L),
            Map.entry("openai:gpt-5.6-terra", 1_050_000L),
            Map.entry("openai:gpt-5.6-luna", 1_050_000L),
            Map.entry("deepseek:deepseek-v4-pro", 1_000_000L),
            Map.entry("deepseek:deepseek-v4-flash", 1_000_000L),
            Map.entry("deepseek:deepseek-v4-flash-vision-exp", 1_000_000L),
            Map.entry("zhipu:glm-5.3", 1_000_000L),
            Map.entry("zhipu:glm-5.3-flash", 1_000_000L),
            Map.entry("zhipu:glm-4.6v", 128_000L),
            Map.entry("zhipu:glm-4.5v", 64_000L),
            Map.entry("anthropic:claude-opus-5", 1_000_000L),
            Map.entry("anthropic:claude-fable-5", 1_000_000L),
            Map.entry("anthropic:claude-sonnet-5", 1_000_000L),
            Map.entry("anthropic:claude-haiku-4-5", 200_000L));
```

（更正：`Map.ofEntries` 无 10 对上限——那是 `Map.of` 的限制。上表照抄即可，无需换初始化方式；若编译器对 14 个 `Map.entry` 无异议就保持 `Map.ofEntries`。）

- [ ] **Step 4: 跑测试确认通过（含同包既有测试）**

Run: `mvn test -pl springai-code-tui -Dtest='ModelContextWindowsTest,ContextStatsTest'`
Expected: 全部 PASS（`ContextStatsTest` 是既有 `ModelContextWindows` 消费方，防回归）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/compaction/ModelContextWindows.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/compaction/ModelContextWindowsTest.java
git commit -m "feat(compaction): 智谱视觉模型内置上下文窗口（flash 1M / 4.6v 128K / 4.5v 64K）"
```

---

### Task 4: 真机冒烟探针 ZhipuVisionSmokeTest

**Files:**
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/ZhipuVisionSmokeTest.java`

**Interfaces:**
- Consumes: `ZhipuProvider(String apiKey).chatModel()` / `options(String)`（Task 2 后内置清单含 flash，但探针直接传 id，不依赖 Task 2）、`Media.builder().mimeType(...).data(byte[]).build()`、`UserMessage.builder().text(...).media(...).build()`。
- Produces: 无——纯验证件，回答「背景事实」里的两个风险点，Task 5 文档措辞依它的结果定。

**门控**（同 `DeepSeekVisionSmokeTest` 模式）：`CODETUI_LIVE_TESTS=1` **且** `ZHIPU_API_KEY` 非空才跑——联网、花钱，默认跳过。

- [ ] **Step 1: 写探针**

```java
package io.github.javaside.springai.codetui.agent.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.MimeTypeUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智谱视觉真机探针：验证 spring-ai-openai 通路对智谱 v4 API 的两个未证实假设——
 * (1) base64 data URL 形态的 image_url 智谱照单全收（DeepSeek 需要 HTTP 改写，智谱预期免改）；
 * (2) 流式 + 工具调用不依赖智谱文档建议的 tool_stream 参数也能正常吐 tool_calls。
 *
 * <p><b>门控</b>：联网 + 花钱，双 gate 默认跳过（同 DeepSeekVisionSmokeTest 模式）：
 * {@code source ~/.secrets && CODETUI_LIVE_TESTS=1 mvn test -pl springai-code-tui -Dtest=ZhipuVisionSmokeTest}
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_LIVE_TESTS", matches = "1")
@EnabledIfEnvironmentVariable(named = "ZHIPU_API_KEY", matches = ".+")
class ZhipuVisionSmokeTest {

    private static final String MODEL = "glm-5.3-flash";

    /** 假设 (1)：纯红图 → 模型答「红」。走生产同款 ChatModel.stream 路径。 */
    @Test
    void visionModelSeesInlineRedImage() {
        ChatModel model = new ZhipuProvider(System.getenv("ZHIPU_API_KEY")).chatModel();
        UserMessage msg = UserMessage.builder()
                .text("这张纯色图片是什么颜色？只答颜色名，不解释。")
                .media(List.of(Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType("image/png"))
                        .data(solidColorPng(Color.RED)).build()))
                .build();
        String text = join(model.stream(new Prompt(msg,
                        new ZhipuProvider("k").options(MODEL)))
                .collectList().block(Duration.ofMinutes(3)));
        System.out.println("智谱视觉回答: " + text);
        assertTrue(text != null && !text.isBlank(), "应返回非空回答");
        String t = text.toLowerCase();
        assertTrue(t.contains("红") || t.contains("red"), "应答出颜色（红/red），实际: " + text);
    }

    /** 假设 (2)：流式 + 工具调用（无图）——tool_stream 缺失时 tool_calls 是否照常工作。 */
    @Test
    void streamingToolCallWorks() {
        ZhipuProvider p = new ZhipuProvider(System.getenv("ZHIPU_API_KEY"));
        WeatherTool tool = new WeatherTool();
        ChatClient client = ChatClient.builder(p.chatModel()).defaultTools(tool).build();
        List<String> chunks = client.prompt()
                .user("调用工具查询北京天气，然后告诉我结果")
                .options(p.options(MODEL).mutate())
                .stream().content()
                .collectList()
                .block(Duration.ofMinutes(3));
        assertTrue(tool.invoked.get(), "流式 tool_calls 应正常触发工具（不依赖 tool_stream 参数）");
        assertFalse(String.join("", chunks).isBlank(), "工具结果回填后应有非空流式回答");
    }

    /** 生产形态复合：一张图 + 一次工具调用在同一条流里——agent 带视觉的真实请求形状。 */
    @Test
    void imageAndToolInOneStream() {
        ZhipuProvider p = new ZhipuProvider(System.getenv("ZHIPU_API_KEY"));
        WeatherTool tool = new WeatherTool();
        ChatClient client = ChatClient.builder(p.chatModel()).defaultTools(tool).build();
        UserMessage msg = UserMessage.builder()
                .text("先调用工具查询北京天气，再告诉我图片是什么颜色。两件事都做。")
                .media(List.of(Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType("image/png"))
                        .data(solidColorPng(Color.BLUE)).build()))
                .build();
        List<String> chunks = client.prompt()
                .messages(List.of(msg))
                .options(p.options(MODEL).mutate())
                .stream().content()
                .collectList()
                .block(Duration.ofMinutes(3));
        String text = String.join("", chunks);
        System.out.println("智谱图+工具复合回答: " + text);
        assertTrue(tool.invoked.get(), "复合请求里工具仍应被触发");
        String t = text.toLowerCase();
        assertTrue(t.contains("蓝") || t.contains("blue"), "复合请求里图仍应被看见（蓝/blue），实际: " + text);
    }

    static class WeatherTool {
        final AtomicBoolean invoked = new AtomicBoolean();

        @Tool(description = "查询指定城市的当前天气")
        String getWeather(String city) {
            invoked.set(true);
            return city + "：晴，25℃";
        }
    }

    private static String join(List<ChatResponse> responses) {
        return responses.stream()
                .map(r -> r.getResult() != null && r.getResult().getOutput() != null
                        ? r.getResult().getOutput().getText() : "")
                .filter(s -> s != null)
                .collect(java.util.stream.Collectors.joining());
    }

    private static byte[] solidColorPng(Color c) throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }
}
```

（注意：`visionModelSeesInlineRedImage` 里为省一行 new 了两个 provider——写时直接改成开头 `ZhipuProvider p = new ZhipuProvider(System.getenv("ZHIPU_API_KEY"));` 一处构造、`p.options(MODEL)` 复用，别照抄这段坏味道。）

- [ ] **Step 2: 默认跳过验证（无 key 环境下不炸）**

Run: `mvn test -pl springai-code-tui -Dtest=ZhipuVisionSmokeTest`
Expected: 编译通过，测试 **SKIPPED**（双 gate 未开）。这一步验证的是「探针不会在 CI/无 key 机器上误跑」。

- [ ] **Step 3: 真机跑（有 key 时）**

Run: `source ~/.secrets && CODETUI_LIVE_TESTS=1 mvn test -pl springai-code-tui -Dtest=ZhipuVisionSmokeTest`
Expected: 三个用例 PASS。**结果记入执行报告**（Task 5 文档措辞依赖）：
- 全绿 → Task 5 写「已真机验证」；
- 假设 (1) 失败（图没送达/400）→ **停止后续任务**，回报现象——那意味着智谱需要 DeepSeek 式 HTTP 改写，本计划的前提被推翻，须重新规划；
- 假设 (2) 失败（工具不触发）→ Task 5 照实写「流式工具调用未通过，待查」，并回报——可能是又一个 SSE 归一化坑（先例 `QwenSseNormalizingHttpClient`）。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/ZhipuVisionSmokeTest.java
git commit -m "test(vision): 智谱视觉真机冒烟探针（纯图 / 流式工具 / 图+工具复合，双 gate）"
```

---

### Task 5: 文档更新 vision.md

**Files:**
- Modify: `springai-code-tui/docs/guide/vision.md:55-64, 130-142`

**Interfaces:**
- Consumes: Task 1–4 的最终状态（清单、前缀、窗口、探针结果）。
- Produces: 无——纯文档。

- [ ] **Step 1: 更新「哪些模型能看见」表格（约 55-64 行）**

表格智谱行改为：

```markdown
| 智谱 | `glm-4v`、`glm-4.1v`、`glm-4.5v`、`glm-4.6v`、`glm-5.3-flash`、`glm-5v` |
```

其下的内置清单注记（原「没有一个命中名单」段）改为：

```markdown
> **内置清单里能直接用上视觉的：OpenAI、Anthropic、DeepSeek 与智谱四家**（`gpt-5.6-*` / `gpt-5.5` /
> `gpt-5.4`、`claude-*`、`deepseek-v4-flash-vision-exp`、`glm-5.3-flash`——最后这个是 2026-08-26 上线的
> GLM-5 系首个原生多模态模型，1M 上下文，价格为 glm-5.3 的 1/10）。
> 千问的内置清单（`qwen3.7-max`/`qwen3.6-flash`/`qwen3-coder-next`）仍无视觉模型——
> 要在千问用视觉，得自己用 `DASHSCOPE_MODELS` 配一个 `-vl` 系 id；智谱的 `glm-4.6v`（2025-12 上线，
> 首个原生 Function Call 的视觉线，Flash 档免费）同理走 `ZHIPU_MODELS`。
```

- [ ] **Step 2: 更新「⚠️ 验证范围」段（约 136-142 行）**

按 Task 4 Step 3 的实测结果三选一改写：

**全绿时**：

```markdown
**智谱**：`glm-5.3-flash` 的内联 base64 通道、流式工具调用、图+工具复合请求均已真机验证
（`ZhipuVisionSmokeTest`，纯红/纯蓝图 → 颜色答对、工具触发）——spring-ai-openai 通路对智谱 v4
无需任何 HTTP 改写（与 DeepSeek 的区别：DeepSeek 的 SDK 丢 Media，必须走改写层）。
```

**未跑真机（无 key）时**：

```markdown
**智谱**：`glm-5.3-flash` 已进内置清单与前缀名单，但**未真机验证**——探针已备好
（`ZhipuVisionSmokeTest`，双 gate），配 `ZHIPU_API_KEY` 后
`source ~/.secrets && CODETUI_LIVE_TESTS=1 mvn test -pl springai-code-tui -Dtest=ZhipuVisionSmokeTest`
即可补验。第一次真用前请把智谱仍视为未验证。
```

**部分失败时**：照实写失败的那个假设与现象，不粉饰。

原段里「Anthropic / 千问 / 智谱三家，完全没有验证过」一句同步改为排除智谱（或按实况保留千问/Anthropic）。

- [ ] **Step 3: 全模块回归 + 提交**

Run: `mvn test -pl springai-code-tui`
Expected: 全绿（对照仓库基线，Task 1–4 的全部改动不回归任何既有测试）。

```bash
git add springai-code-tui/docs/guide/vision.md
git commit -m "docs(vision): 智谱视觉名单/内置清单/验证范围更新（glm-5.3-flash 接入）"
```

---

## Self-Review 记录

- **覆盖核对**：上轮对话拍板的 5 项改动 → 前缀（Task 1）、内置清单（Task 2）、窗口（Task 3）、thinking（Task 2）、文档（Task 5）；两个风险点 → Task 4 三个探针用例分别回答。无缺口。
- **占位符扫描**：无 TBD/TODO；Task 4 Step 1 代码里故意保留了一处坏味道并在括号里点名修正方式——这是给执行者的提示不是占位。
- **类型一致性**：`ThinkingCapabilities.effort(boolean, List<String>)`、`ModelOption` 三参构造、`options(String)` 返回 `ChatOptions`（有 `mutate()`，与 `QwenRealStreamingToolCallSmokeTest` 同款用法）均与现有代码核对过；`ModelContextWindows.parse/DEFAULT_UNKNOWN_WINDOW` 包私有，测试同包可达。
- **已知不确定点（有意保留，非占位）**：`glm-5v` 确切 API id 未核实——只进前缀名单不进内置清单，最坏情况是用户自配了不存在的 id 得到可见 404；`Map.ofEntries` 无 10 对上限说明已内嵌 Task 3 Step 3。

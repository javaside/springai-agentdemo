# 可配置模型清单（`*_MODELS` 环境变量）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** code-tui 5 家 provider 的模型清单可通过 `*_MODELS` 环境变量配置（逗号分隔，第一项为默认模型），未设置回退代码内置清单，行为零变化。

**Architecture:** 新增公共解析助手 `ModelListEnv.parse(env, fallback)`；5 个 Provider 各加一个 3 参构造器（`apiKey, baseUrl, modelsEnv`），构造时解析定型为实例字段，`models()`/`defaultModel()` 改走实例字段（默认模型 = 列表第一项）；`CodeTuiApplication` 装配处照 `*_BASE_URL` 模式传 `System.getenv("XXX_MODELS")`。

**Tech Stack:** Java 21、JUnit 5（`org.junit.jupiter.api.Assertions.assertEquals` 风格）、Maven 多模块（验证命令必须 `-pl springai-code-tui` 模块作用域）。

**Spec:** `docs/superpowers/specs/2026-07-16-configurable-model-lists-design.md`

**关键约定（读我）：**

- 环境变量：`DEEPSEEK_MODELS` / `ZHIPU_MODELS` / `DASHSCOPE_MODELS`（千问）/ `ANTHROPIC_MODELS` / `OPENAI_MODELS`。
- 值为逗号分隔模型 id；每项 trim，空项忽略；**第一项即默认模型**。未设置/空白/无有效项 → 内置清单。
- 配置来的模型 `ModelOption` 为 `(id, id, "")`——label 显示 id 本身、描述留空。
- 内置清单须满足「第一项 = 默认模型」的新约定：**只有 DeepSeek 需要调整顺序**（现状 flash 在前、默认却是 pro），其余 4 家已满足。调整后各家的 `DEFAULT_MODEL` 常量删除，`defaultModel()` 返回 `models.get(0).id()`。
- 各 Provider `chatModel()` 里构建默认 options 用的 `DEFAULT_MODEL` 一并改为 `defaultModel()`（Anthropic 是 `Model.of(defaultModel())`）。
- Provider 构造不校验模型 id 有效性、不抛异常（畸形输入最多退化为内置清单）。
- 测试全部网络无关：Provider 构造与 `models()`/`defaultModel()` 不触发懒建的 `chatModel()`。

---

### Task 1: `ModelListEnv` 解析助手（TDD）

**Files:**
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ModelListEnvTest.java`（新建）
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ModelListEnv.java`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** ModelListEnv：*_MODELS 环境变量解析（逗号分隔，第一项为默认模型），畸形输入一律回退内置清单。 */
class ModelListEnvTest {

    private static final List<ModelOption> FALLBACK = List.of(
            new ModelOption("built-in-default", "built-in-default", "内置默认"),
            new ModelOption("built-in-alt",     "built-in-alt",     "内置备选"));

    @Test
    void nullOrBlank_returnsFallback() {
        assertSame(FALLBACK, ModelListEnv.parse(null, FALLBACK));
        assertSame(FALLBACK, ModelListEnv.parse("", FALLBACK));
        assertSame(FALLBACK, ModelListEnv.parse("   ", FALLBACK));
    }

    @Test
    void onlyCommasOrSpaces_returnsFallback() {
        assertSame(FALLBACK, ModelListEnv.parse(",,", FALLBACK));
        assertSame(FALLBACK, ModelListEnv.parse(" , , ", FALLBACK));
    }

    @Test
    void singleModel_parsed_labelIsId_descEmpty() {
        List<ModelOption> out = ModelListEnv.parse("my-model", FALLBACK);
        assertEquals(1, out.size());
        assertEquals(new ModelOption("my-model", "my-model", ""), out.get(0));
    }

    @Test
    void multiModels_trimmed_emptyItemsSkipped_orderKept() {
        List<ModelOption> out = ModelListEnv.parse(" m-pro , m-flash ,, m-lite ", FALLBACK);
        assertEquals(List.of("m-pro", "m-flash", "m-lite"),
                out.stream().map(ModelOption::id).toList());
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败（ModelListEnv 不存在）**

Run: `mvn test -pl springai-code-tui -Dtest=ModelListEnvTest`
Expected: BUILD FAILURE，编译错误 `cannot find symbol: ModelListEnv`

- [ ] **Step 3: 写最小实现**

```java
package io.github.javaside.springai.codetui.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 {@code *_MODELS} 环境变量（逗号分隔模型 id，<b>第一项即默认模型</b>）。
 *
 * <p>未设置、空白或解析后无有效项 → 原样返回内置回退清单，行为与不配置完全一致。
 * 配置来的每个 id 映射为 {@code ModelOption(id, id, "")}（label 显示 id、描述留空）。
 * 不校验 id 有效性——真正校验发生在对话请求时由服务端报错，与内置清单同一策略。
 */
final class ModelListEnv {

    private ModelListEnv() {}

    static List<ModelOption> parse(String env, List<ModelOption> fallback) {
        if (env == null || env.isBlank()) {
            return fallback;
        }
        List<ModelOption> out = new ArrayList<>();
        for (String part : env.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                out.add(new ModelOption(id, id, ""));
            }
        }
        return out.isEmpty() ? fallback : List.copyOf(out);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=ModelListEnvTest`
Expected: PASS（4 tests）

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ModelListEnv.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ModelListEnvTest.java
git commit -m "feat(code-tui): ModelListEnv 解析 *_MODELS 环境变量（逗号分隔，首项为默认）"
```

---

### Task 2: DeepSeekProvider 接入（含内置清单调序，TDD）

**Files:**
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ProviderModelsEnvTest.java`（新建）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekProvider.java`

- [ ] **Step 1: 写失败测试**

新建 `ProviderModelsEnvTest.java`（本 Task 只写 DeepSeek 两个用例，Task 3 再追加其余 4 家）：

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 各 Provider 的 *_MODELS 环境变量接入：配置来的清单生效且首项为默认；不配置回退内置清单。 */
class ProviderModelsEnvTest {

    @Test
    void deepseek_modelsEnv_overridesList_firstIsDefault() {
        DeepSeekProvider p = new DeepSeekProvider("key", null, "m-a, m-b");
        assertEquals(java.util.List.of("m-a", "m-b"),
                p.models().stream().map(ModelOption::id).toList());
        assertEquals("m-a", p.defaultModel());
    }

    @Test
    void deepseek_noEnv_builtInList_defaultIsFirst() {
        DeepSeekProvider p = new DeepSeekProvider("key", null, null);
        assertEquals("deepseek-v4-pro", p.defaultModel());
        assertEquals("deepseek-v4-pro", p.models().get(0).id());   // 内置清单首项 = 默认（本 Task 调序）
        assertEquals(2, p.models().size());
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败（3 参构造器不存在）**

Run: `mvn test -pl springai-code-tui -Dtest=ProviderModelsEnvTest`
Expected: BUILD FAILURE，编译错误（no suitable constructor）

- [ ] **Step 3: 改 DeepSeekProvider**

对 `DeepSeekProvider.java` 做 4 处修改：

① 删除 `DEFAULT_MODEL` 常量、内置清单调序（pro 提到第一位，首项即默认）——替换第 19-22 行：

```java
    // 首项即默认模型（*_MODELS 未配置时的回退清单，约定第一项为默认）。
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("deepseek-v4-pro",   "deepseek-v4-pro",   "强推理 · 1.6T · 更慢更贵"),
            new ModelOption("deepseek-v4-flash", "deepseek-v4-flash", "非思考 · 快 · 便宜"));
```

② 加实例字段（放在 `private final String baseUrl;` 之后）：

```java
    private final List<ModelOption> models;   // DEEPSEEK_MODELS 解析结果；未配置=内置 MODELS
```

③ 构造器改为三层委托——替换现有两个构造器：

```java
    public DeepSeekProvider(String apiKey) {
        this(apiKey, null);
    }

    public DeepSeekProvider(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, null);
    }

    public DeepSeekProvider(String apiKey, String baseUrl, String modelsEnv) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        this.models = ModelListEnv.parse(modelsEnv, MODELS);
    }
```

④ `models()` / `defaultModel()` 走实例字段，`chatModel()` 里默认 options 改用 `defaultModel()`：

```java
    @Override public List<ModelOption> models() { return models; }

    @Override public String defaultModel() { return models.get(0).id(); }
```

`chatModel()` 内（原第 76 行）：

```java
            m = DeepSeekChatModel.builder()
                    .deepSeekApi(api)
                    .options(DeepSeekChatOptions.builder().model(defaultModel()).build())
                    .build();
```

类 javadoc 第 14 行的「默认模型 deepseek-v4-pro…」描述保持不变（仍是内置默认）。

- [ ] **Step 4: 跑测试确认通过（含既有回归）**

Run: `mvn test -pl springai-code-tui -Dtest='ProviderModelsEnvTest,LlmProviderTest,ProviderRegistryTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekProvider.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ProviderModelsEnvTest.java
git commit -m "feat(code-tui): DeepSeek 支持 DEEPSEEK_MODELS 配置模型清单（首项为默认，内置清单调序）"
```

---

### Task 3: 其余 4 家 Provider 接入（TDD）

**Files:**
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ProviderModelsEnvTest.java`（追加用例）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ZhipuProvider.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/QwenProvider.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AnthropicProvider.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/OpenAiProvider.java`

4 家的内置清单首项本来就是默认模型，**不需要调序**；改法与 Task 2 的 ②③④ 完全同构。

- [ ] **Step 1: 追加失败测试**

在 `ProviderModelsEnvTest.java` 追加：

```java
    @Test
    void zhipu_modelsEnv_overridesList_firstIsDefault() {
        ZhipuProvider p = new ZhipuProvider("key", null, "glm-x, glm-y");
        assertEquals("glm-x", p.defaultModel());
        assertEquals(2, p.models().size());
    }

    @Test
    void zhipu_noEnv_builtInDefault() {
        assertEquals("glm-5.2", new ZhipuProvider("key", null, null).defaultModel());
    }

    @Test
    void qwen_modelsEnv_overridesList_firstIsDefault() {
        QwenProvider p = new QwenProvider("key", null, "qwen-x");
        assertEquals("qwen-x", p.defaultModel());
        assertEquals(1, p.models().size());
    }

    @Test
    void qwen_noEnv_builtInDefault() {
        assertEquals("qwen3.7-max", new QwenProvider("key", null, null).defaultModel());
    }

    @Test
    void anthropic_modelsEnv_overridesList_firstIsDefault() {
        AnthropicProvider p = new AnthropicProvider("key", null, "claude-x, claude-y");
        assertEquals("claude-x", p.defaultModel());
    }

    @Test
    void anthropic_noEnv_builtInDefault() {
        assertEquals("claude-opus-4-8", new AnthropicProvider("key", null, null).defaultModel());
    }

    @Test
    void openai_modelsEnv_overridesList_firstIsDefault() {
        OpenAiProvider p = new OpenAiProvider("key", null, "gpt-x");
        assertEquals("gpt-x", p.defaultModel());
    }

    @Test
    void openai_noEnv_builtInDefault() {
        assertEquals("gpt-5.6-sol", new OpenAiProvider("key", null, null).defaultModel());
    }
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `mvn test -pl springai-code-tui -Dtest=ProviderModelsEnvTest`
Expected: BUILD FAILURE（4 家均无 3 参构造器）

- [ ] **Step 3: 改 4 个 Provider（每家同构 4 处）**

每家的修改模式（以下逐家给出差异点，未列出的部分照抄 Task 2 模式）：

**ZhipuProvider**（默认模型 `glm-5.2`，清单已首项即默认）：
- 删第 26 行 `DEFAULT_MODEL` 常量；MODELS 保持原顺序不动。
- 加字段：`private final List<ModelOption> models;   // ZHIPU_MODELS 解析结果；未配置=内置 MODELS`
- 构造器：

```java
    public ZhipuProvider(String apiKey) {
        this(apiKey, null);
    }

    public ZhipuProvider(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, null);
    }

    public ZhipuProvider(String apiKey, String baseUrl, String modelsEnv) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        this.models = ModelListEnv.parse(modelsEnv, MODELS);
    }
```

- `chatModel()` 原第 69 行：`.options(OpenAiChatOptions.builder().model(defaultModel()).build())`
- 末尾两方法：

```java
    @Override public List<ModelOption> models() { return models; }

    @Override public String defaultModel() { return models.get(0).id(); }
```

**QwenProvider**（默认 `qwen3.7-max`）：同 Zhipu 模式。删第 26 行 `DEFAULT_MODEL`；字段注释写 `// DASHSCOPE_MODELS 解析结果；未配置=内置 MODELS`；3 参构造器同构；`chatModel()` 原第 77 行改 `.model(defaultModel())`；`models()`/`defaultModel()` 同上。

**AnthropicProvider**（默认 `claude-opus-4-8`）：删第 21 行 `DEFAULT_MODEL`；字段注释写 `// ANTHROPIC_MODELS 解析结果；未配置=内置 MODELS`；3 参构造器（注意 baseUrl 空值语义与另几家不同，保持原样）：

```java
    public AnthropicProvider(String apiKey, String baseUrl, String modelsEnv) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "" : baseUrl.trim();
        this.models = ModelListEnv.parse(modelsEnv, MODELS);
    }
```

- `chatModel()` 原第 71 行：`.model(Model.of(defaultModel()))`；`models()`/`defaultModel()` 同上。

**OpenAiProvider**（默认 `gpt-5.6-sol`）：删第 18 行 `DEFAULT_MODEL`；字段注释写 `// OPENAI_MODELS 解析结果；未配置=内置 MODELS`；3 参构造器与 Anthropic 同构（baseUrl 空→`""`）；`chatModel()` 原第 66 行改 `.model(defaultModel())`；`models()`/`defaultModel()` 同上。

- [ ] **Step 4: 跑测试确认通过（含既有回归）**

Run: `mvn test -pl springai-code-tui -Dtest='ProviderModelsEnvTest,LlmProviderTest,ProviderRegistryTest'`
Expected: PASS（ProviderModelsEnvTest 共 10 tests）

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ZhipuProvider.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/QwenProvider.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AnthropicProvider.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/OpenAiProvider.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ProviderModelsEnvTest.java
git commit -m "feat(code-tui): 智谱/千问/Anthropic/OpenAI 支持 *_MODELS 配置模型清单"
```

---

### Task 4: CodeTuiApplication 装配接线

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java:33-42`

- [ ] **Step 1: 改装配代码**

替换第 33-42 行（注释补一句 `*_MODELS` 说明，5 个 provider 各加第三参）：

```java
        // 多家 provider：谁配了 key 谁 available。至少需一家可用（通常 DeepSeek）。
        // base-url 可选：配了 *_BASE_URL 就覆盖，否则用各家内置默认（便于走代理/私有网关）。
        // 模型清单可选：配了 *_MODELS（逗号分隔，首项为默认模型）就覆盖，否则用各家内置清单。
        ProviderRegistry registry;
        try {
            registry = new ProviderRegistry(java.util.List.of(
                    new DeepSeekProvider(System.getenv("DEEPSEEK_API_KEY"), System.getenv("DEEPSEEK_BASE_URL"), System.getenv("DEEPSEEK_MODELS")),
                    new ZhipuProvider(System.getenv("ZHIPU_API_KEY"), System.getenv("ZHIPU_BASE_URL"), System.getenv("ZHIPU_MODELS")),
                    new QwenProvider(System.getenv("DASHSCOPE_API_KEY"), System.getenv("DASHSCOPE_BASE_URL"), System.getenv("DASHSCOPE_MODELS")),
                    new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY"), System.getenv("ANTHROPIC_BASE_URL"), System.getenv("ANTHROPIC_MODELS")),
                    new OpenAiProvider(System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_BASE_URL"), System.getenv("OPENAI_MODELS"))));
```

（`catch` 块及以后不动。）

- [ ] **Step 2: 编译 + 模块全量测试**

Run: `mvn test -pl springai-code-tui`
Expected: BUILD SUCCESS，无失败测试

- [ ] **Step 3: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java
git commit -m "feat(code-tui): 装配点接线 5 个 *_MODELS 环境变量"
```

---

### Task 5: 文档同步

**Files:**
- Modify: `springai-code-tui/README.md`（运行段环境变量说明，约第 53-61 行）
- Modify: `springai-code-tui/src/package/bin/config.env.example`（base-url 段之后加一段）
- Modify: `README.md`（根 README 对话模型 bullet，约第 14 行）

- [ ] **Step 1: 模块 README**

在 `springai-code-tui/README.md` 运行段代码块内，找到这一行：

```
# 各 provider 可选自定义 base url：DEEPSEEK_BASE_URL / ZHIPU_BASE_URL / DASHSCOPE_BASE_URL / ANTHROPIC_BASE_URL / OPENAI_BASE_URL
```

在其后插入：

```
# 各 provider 可选自定义模型清单（逗号分隔，首项为默认模型；不配则用内置清单）：
#   DEEPSEEK_MODELS / ZHIPU_MODELS / DASHSCOPE_MODELS / ANTHROPIC_MODELS / OPENAI_MODELS
#   例：export DEEPSEEK_MODELS=deepseek-v4-pro,deepseek-v4-flash
```

- [ ] **Step 2: config.env.example**

在 `springai-code-tui/src/package/bin/config.env.example` 的 base-url 段（`#OPENAI_BASE_URL=...` 行）之后、「可选：调优」段之前插入：

```
# ────────────── 可选：自定义模型清单 ──────────────
# 逗号分隔的模型 id，第一项为该家的默认模型。不配就用内置清单。
#DEEPSEEK_MODELS=deepseek-v4-pro,deepseek-v4-flash
#ZHIPU_MODELS=glm-5.2,glm-5.1,glm-5-turbo
#DASHSCOPE_MODELS=qwen3.7-max,qwen3.7-plus,qwen3.6-flash,qwen3-coder-next
#ANTHROPIC_MODELS=claude-opus-4-8,claude-fable-5,claude-sonnet-5,claude-haiku-4-5
#OPENAI_MODELS=gpt-5.6-sol,gpt-5.6-terra,gpt-5.6-luna,gpt-5.5,gpt-5.4
```

- [ ] **Step 3: 根 README**

根 `README.md` 找到这一行：

```
- **对话模型**：[DeepSeek](https://platform.deepseek.com/)（国内可直连、价格低）；`springai-code-tui` 额外支持 智谱 GLM / [通义千问](https://bailian.console.aliyun.com/)（百炼）/ Anthropic / OpenAI
```

在行尾追加：`（各家模型清单可经 *_MODELS 环境变量配置，首项为默认模型）`

- [ ] **Step 4: Commit**

```bash
git add springai-code-tui/README.md springai-code-tui/src/package/bin/config.env.example README.md
git commit -m "docs(code-tui): *_MODELS 模型清单环境变量说明（README + config.env.example）"
```

---

### Task 6: 收尾验证

- [ ] **Step 1: 模块全量测试**

Run: `mvn test -pl springai-code-tui`
Expected: BUILD SUCCESS，0 failures（注意：**不要**跑整仓 `mvn test`，会被 3 个空模块打挂）

- [ ] **Step 2: 真机冒烟（可选但推荐）**

配置一个环境变量验证端到端生效（key 从 `~/.secrets` 取，不发真实请求也可验证——启动横幅/`/model` 列表即可见）：

```bash
mvn -pl springai-code-tui -am package -DskipTests -q
cd /tmp && mkdir -p codetui-smoke && cd codetui-smoke
DEEPSEEK_MODELS="deepseek-v4-flash,deepseek-v4-pro" \
  java -jar /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui/target/springai-code-tui.jar
```

进入 TUI 后输入 `/model`：应看到 deepseek 家只列 `deepseek-v4-flash`、`deepseek-v4-pro` 两项且当前激活为 `deepseek-v4-flash`（清单首项）。`Ctrl+C` 退出。

Expected: `/model` 列表与配置一致，默认模型 = 首项。

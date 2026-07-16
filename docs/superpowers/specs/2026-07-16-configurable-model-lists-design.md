# 可配置模型清单（`*_MODELS` 环境变量）设计

日期：2026-07-16
模块：springai-code-tui
状态：已确认

## 背景与目标

code-tui 目前支持 5 家 provider（DeepSeek、智谱 GLM、通义千问、Anthropic、OpenAI），每家的模型清单和默认模型硬编码在各 Provider 类的 static 常量里（如 `DeepSeekProvider.MODELS` / `DEFAULT_MODEL`）。厂商上新/下线模型时必须改代码重新打包。

目标：每家 provider 增加一个模型清单环境变量，配置方式与既有 `*_BASE_URL` 完全一致。不配置则回退到代码内置清单，行为零变化。

## 配置格式

| Provider | 环境变量 | 示例 |
|---|---|---|
| DeepSeek | `DEEPSEEK_MODELS` | `deepseek-v4-pro,deepseek-v4-flash` |
| 智谱 GLM | `ZHIPU_MODELS` | `glm-5.2,glm-5-turbo` |
| 通义千问 | `DASHSCOPE_MODELS` | `qwen3.7-max,qwen3-coder-next` |
| Anthropic | `ANTHROPIC_MODELS` | `claude-opus-4-8,claude-sonnet-5` |
| OpenAI | `OPENAI_MODELS` | `gpt-5.6-sol,gpt-5.5` |

规则：

- 值为逗号分隔的模型 id 列表；**第一个即该家的默认模型**。
- 每项 trim 前后空白；空项（连续逗号、纯空白）忽略。
- 未设置、为空串或解析后无有效项 → 使用代码内置的 `MODELS` / `DEFAULT_MODEL`，与现状完全一致。
- 环境变量命名前缀与各家既有 `*_API_KEY` / `*_BASE_URL` 前缀对齐（千问用 `DASHSCOPE_` 前缀）。

## 架构与组件

### 1. 公共解析助手（新增）

在 `agent` 包新增小工具类（如 `ModelListEnv`），提供一个静态方法：

```java
static List<ModelOption> parse(String env, List<ModelOption> fallback)
```

- `env` 为空白或解析后无有效项 → 返回 `fallback`。
- 否则按逗号拆分、trim、滤空，每个 id 映射为 `new ModelOption(id, id, "")`（label 显示为 id 本身，描述留空）。
- 默认模型即返回列表第一项，无需单独方法（Provider 的 `defaultModel()` 取 `models().get(0).id()` 或等价逻辑）。

### 2. Provider 接入（改动 5 个类 + 装配点）

照 base URL 的既有模式：

- 5 个 Provider 构造器各加一个 `String modelsEnv` 参数（紧跟 `baseUrl` 之后），构造时调 `ModelListEnv.parse(modelsEnv, MODELS)` 存为实例字段。
- `models()` 返回该实例字段；`defaultModel()` 返回列表第一项的 id（内置清单需保证第一项即原 `DEFAULT_MODEL`，必要时调整内置 List 顺序使其成立，`DEFAULT_MODEL` 常量随之可移除或保留为列表首项引用）。
- `CodeTuiApplication` 装配处传 `System.getenv("DEEPSEEK_MODELS")` 等，与 `*_BASE_URL` 并列。

### 3. 数据流

启动 → `CodeTuiApplication` 读 5 个 `*_MODELS` 环境变量 → Provider 构造时解析并定型模型清单 → `ProviderRegistry.allModels()` / `/model` 选择器 / `defaultModel()` 全部自然生效，无需改动 registry 和 UI。

## 错误处理

- 配置的模型 id 不做有效性校验（与现状一致——内置清单也可能过期；真正校验发生在对话请求时由服务端报错）。
- 解析不抛异常：任何畸形输入最多退化为回退内置清单。
- 模型能力（`LlmProvider.capabilities(String)`）目前所有 provider 均用接口默认值 `TEXT_ONLY`，与模型 id 无关，配置来的新模型 id 行为一致，无需处理。

## 测试

- `ModelListEnv` 单测：null / 空串 / 纯空白 / 单个模型 / 多个模型带空格 / 连续逗号 → 验证返回清单与回退行为。
- 任选一家 Provider 的单测：传入 `modelsEnv` 后 `models()` 返回配置清单、`defaultModel()` 为第一项；传 null 时与原行为一致。
- 验证命令模块作用域：`mvn test -pl springai-code-tui`。

## 文档

- `springai-code-tui/README.md`：环境变量表补 5 个 `*_MODELS`，说明"逗号分隔、第一个为默认、不设则用内置清单"。
- 根 `README.md`：provider 配置段落补一句 `*_MODELS` 说明。

## 明确不做（YAGNI）

- 不引入 models.json 等配置文件。
- 不支持配置模型 label/描述、能力标记、base URL 迁移。
- 不改变 provider 启用/优先级机制（仍由 API key 是否配置 + 装配顺序决定）。

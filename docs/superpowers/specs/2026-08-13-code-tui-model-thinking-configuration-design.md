# code-tui 模型思考模式与强度配置设计

## 背景

code-tui 当前通过 `LlmProvider` 统一接入 DeepSeek、智谱、Qwen、Anthropic 和 OpenAI，`ProviderRegistry` 维护当前 provider 与模型，`/model` 负责跨 provider 选择模型。每次主 agent 请求由 provider 生成原生 `ChatOptions`；子 agent 同样通过 provider 解析 options。

现有抽象只覆盖模型 ID，没有表达思考模式与强度。各家 API 也不存在可直接共用的一组参数：OpenAI 和部分智谱、DeepSeek 模型使用离散 effort；Anthropic 新模型使用 adaptive thinking 与 effort，旧模型使用 token budget；Qwen 使用 `enable_thinking` 与 `thinking_budget`；智谱还支持 `thinking.type`；Spring AI 2.0 的 DeepSeek options 暂未暴露这些新字段。

因此，本功能不能把一个全局 `reasoning_effort` 直接传给所有模型，而要统一用户意图、保留 provider 原生配置形态，并由各 provider 完成请求映射。

## 目标

1. `/model` 中可查看每个模型当前的思考状态，并进入该模型的二级设置面板。
2. 每个 `provider + modelId` 独立保存思考模式与原生强度，切换回来时恢复。
3. 未配置的模型不发送思考覆盖参数，沿用提供商默认行为。
4. 根据模型能力展示开关、effort 枚举或 token budget，不展示明确不支持的选项。
5. 内置五家 provider 均有明确映射；自定义模型可按 provider 通用能力配置。
6. 配置作用于主 agent 和子 agent，不影响摘要、网页抽取等内部辅助调用。
7. 在飞回合使用提交时快照，不因用户随后切换模型或设置而改变。

## 非目标

- 不统一或展示模型的思维链正文。
- 不把不同 provider 的原生强度强行换算为统一“低/中/高”。
- 不探测远端模型能力，也不在启动时发送试探请求。
- 不为自定义模型新增复杂的能力声明文件或环境变量。
- 不迁移 OpenAI Chat Completions 到 Responses API。
- 不改变摘要、SmartWebFetch 等内部辅助调用的成本与延迟策略。

## 已确认的产品决策

1. 配置按 `provider + modelId` 独立持久化。
2. 设置入口集成到 `/model`，采用二级面板方案。
3. 从未配置时沿用 provider 默认，不显式覆盖。
4. 自定义模型按 provider 通用能力开放设置；API 不支持时保留配置并报告错误。
5. 强度展示 provider 原生形态：effort 枚举或 token budget 数值。
6. 配置只作用于主 agent 与子 agent。
7. 设置面板使用草稿，`Enter` 校验并保存，`Esc` 放弃。

## 方案比较

### 方案 A：统一能力层，由 provider 映射原生参数（采用）

增加 provider 无关的配置与能力模型，UI 只消费能力描述；各 provider 将配置映射到自己的 `ChatOptions` 或请求扩展。边界清晰，可保留现有原生 ChatModel、流式和工具调用行为。

### 方案 B：全部迁移到 OpenAI 兼容接口

五家尽量复用 `OpenAiChatOptions.extraBody`。表面统一，但 Anthropic 与 DeepSeek 现有原生实现已经承担流式、工具调用和 `reasoning_content` 处理，迁移会扩大回归面。

### 方案 C：UI 直接处理五家参数

开发路径短，但 provider 细节泄漏到界面与持久化层。增加模型、供应商或新参数时需要同步修改 UI 状态机，不采用。

## 领域模型

### ThinkingConfig

每个模型保存一个 `ThinkingConfig`：

```text
mode: DEFAULT | ENABLED | DISABLED
strength: null | EffortValue | TokenBudgetValue
```

- `DEFAULT`：不发送任何思考参数；`strength` 必须为空。
- `DISABLED`：显式关闭；`strength` 必须为空。
- `ENABLED`：显式开启；模型支持强度时携带已校验值，仅支持开关时为空。

`DEFAULT` 不是 `DISABLED`。前者跟随官方默认随模型演进，后者表达用户明确要求关闭。

### ThinkingCapabilities

`LlmProvider.thinkingCapabilities(modelId)` 返回 UI 与校验共用的能力描述：

```text
configurable
supportsDisable
strengthKind: NONE | EFFORT | TOKEN_BUDGET
effortValues: ordered list
budgetRange: optional min/max/default hint
```

能力按模型判定。内置模型使用下述能力矩阵；无法识别的自定义模型使用 provider 的保守通用能力。能力描述只决定可配置项，不伪装成远端兼容性探测。

### 当前内置模型能力矩阵

| Provider | 内置模型 | 模式 | 原生强度 |
|---|---|---|---|
| OpenAI | `gpt-5.6-sol`、`gpt-5.6-terra`、`gpt-5.6-luna` | 默认、开启、关闭 | 仅列该模型官方模型页明确支持的 effort；共同基线为 `low/medium/high` |
| OpenAI | `gpt-5.5`、`gpt-5.4` | 默认、开启、关闭 | 仅列各自官方模型页明确支持的 effort；无法核实时降级为仅模式开关 |
| Anthropic | `claude-opus-5`、`claude-sonnet-5` | 默认、开启、关闭 | adaptive effort，使用 SDK `OutputConfig.Effort` 与模型文档支持值的交集 |
| Anthropic | `claude-fable-5` | 默认、开启，不允许关闭 | adaptive effort；thinking 始终开启 |
| Anthropic | `claude-haiku-4-5`、`claude-opus-4-8` | 默认、开启、关闭 | 当前内置旧模型不在本期暴露手工 token budget；只在文档明确支持时展示 effort |
| Qwen | `qwen3.7-max`、`qwen3.7-plus`、`qwen3.6-flash` | 默认、开启、关闭 | token budget；正整数，只有官方模型卡可核实时才增加本地上限 |
| Qwen | `qwen3-coder-next` | 默认、开启、关闭 | 官方文档未确认预算支持，本期只展示模式，不发送 `thinking_budget` |
| 智谱 | `glm-5.2` | 默认、开启、关闭 | 只展示有效档 `high/max`；不展示会被映射的 `low/medium/xhigh`，`none/minimal` 由关闭模式表达 |
| 智谱 | `glm-5.1`、`glm-5-turbo` | 默认、开启、关闭 | 无强度配置 |
| DeepSeek | `deepseek-v4-pro`、`deepseek-v4-flash` | 默认、开启、关闭 | `low/high/max` |

模型清单可由 `*_MODELS` 覆盖，因此能力判定必须按 ID 进行。内置 ID 的参数值随官方模型页更新时由测试同步更新；不能核实的值宁可不展示，不能推测。未知自定义模型的 provider 通用能力为：OpenAI 提供模式与 `low/medium/high`，Anthropic 提供 adaptive 模式与 SDK effort 枚举但允许远端拒绝，Qwen 提供模式与正整数预算，智谱和 DeepSeek 提供模式且 DeepSeek 提供 `low/high/max`。远端拒绝仍遵循本设计的显式报错规则。

### ThinkingConfigStore

进程内唯一 store，职责为：

- 按 `providerId + modelId` 查询和更新配置；
- 缺省返回 `DEFAULT`；
- 加载和原子写入工作区 `.codetui/thinking.json`；
- 保存前用 provider 能力校验；
- 为一次请求提供不可变快照。

store 不依赖 TUI，也不生成 `ChatOptions`。

## Provider 接口

`LlmProvider` 增加以下职责：

```text
ThinkingCapabilities thinkingCapabilities(String modelId)
ChatOptions options(String modelId, ThinkingConfig config)
```

现有 `options(String modelId)` 保留为默认配置的兼容入口，等价 `options(modelId, ThinkingConfig.DEFAULT)`，避免一次性破坏旧测试和内部辅助调用。

provider 映射必须满足：

1. `DEFAULT` 仅设置原有必填字段和 model，不附加思考参数。
2. `DISABLED` 只在能力允许时映射显式关闭。
3. `ENABLED` 只接受能力列出的 effort 或合法预算。
4. 非法配置在发请求前抛出可读的参数错误，不静默取最近档位。

## 五家参数映射

### OpenAI

- 使用 `OpenAiChatOptions.reasoningEffort(...)`。
- `ENABLED` 映射到模型能力矩阵列出的原生 effort。
- 支持关闭的模型把 `DISABLED` 映射为 `none`。
- `DEFAULT` 不设置 reasoning effort。
- 不迁移 Responses API；当前主链仍使用 Chat Completions。

### Anthropic

- 当前内置新模型使用 `thinkingAdaptive()` 开启；仅能力声明允许关闭的模型使用 `thinkingDisabled()`，Fable 5 不提供关闭项。
- effort 使用 `AnthropicChatOptions.effort(OutputConfig.Effort)`。
- 新模型能力矩阵只展示其实际支持的 effort。
- 本期不暴露旧式 manual extended-thinking 的 token budget；这类内置模型仅在官方明确支持 adaptive effort 时展示强度，否则只提供模式开关。
- `DEFAULT` 仅保留 Anthropic 必填的 `maxTokens` 和 model。

### Qwen

- 继续复用 `OpenAiChatModel`。
- 通过 `OpenAiChatOptions.extraBody` 写入顶层扩展：
  - `enable_thinking: true|false`
  - `thinking_budget: integer`，仅在开启且用户设置预算时发送。
- UI 展示整数 token budget，不转换成项目自定义档位。
- 内置模型维护可关闭性；预算必须为正整数，只有官方模型卡可核实时才做本地上限校验，否则依赖远端最终校验。

### 智谱

- 继续复用 `OpenAiChatModel`。
- 通过 `extraBody` 写入 `thinking: {type: enabled|disabled}`。
- 仅官方明确支持 effort 的内置模型展示并发送 `reasoning_effort`；其他模型只提供开关。
- `DEFAULT` 不发送 `thinking` 或 effort。

### DeepSeek

- 保留 `DeepSeekChatModel`、现有 HTTP 栈、流式响应解析及 `reasoning_content` 工具回传行为。
- 请求增加：
  - `thinking: {type: enabled|disabled}`
  - `reasoning_effort: <原生值>`
- Spring AI 2.0 的 `DeepSeekChatOptions` 和封闭请求 record 未暴露这些字段。不得复制整份 Spring AI `DeepSeekChatModel`，也不得用 ThreadLocal 把配置传给流式 HTTP 线程。
- `DeepSeekProvider` 返回一个轻量路由 ChatModel：它从项目内的 `DeepSeekThinkingChatOptions` 读取不可变配置，按配置键选择缓存的原生 `DeepSeekChatModel` delegate。每个 delegate 使用固定配置的同步/流式 JSON 请求装饰器，在请求体顶层加入 `thinking` 与 `reasoning_effort`；`DEFAULT` delegate 继续使用当前未装饰通路。
- JSON 装饰器只改变出站请求对象，不解析或重组响应。响应、SSE 合并、工具调用与 `reasoning_content` 回传仍全部由 Spring AI 原生 `DeepSeekChatModel` 处理。
- DeepSeek 内置模型分别维护实际 effort 列表与映射；不在 UI 中展示会被远端强制映射成另一档的冗余选项。
- 必须保留多轮工具调用时完整回传 `reasoning_content` 的现有语义。

## UI 设计

### 模型列表

`/model` 标题改为：

```text
选择模型（↑↓ 选择 · Enter 切换 · → 思考设置 · Esc 取消）
```

每个模型行尾显示简短摘要：

- `思考: 默认`
- `思考: 关闭`
- `思考: high`
- `思考: 32768 tokens`
- `思考: 开启`，仅支持开关时
- `思考: 不可配置`

`Enter` 仍只切换模型并保留现有模型持久化行为。`→` 针对高亮模型进入思考设置，不要求先把它切成当前模型。不可配置时留在列表并给出 notice。

### 二级设置面板

面板标题为 `<model label> · 思考设置`。

- `↑↓`：在模式和强度行之间移动。
- `←→`：循环模式或 effort 枚举。
- 数值预算行按 `Enter` 进入文本编辑，再按 `Enter` 确认数值。
- 面板级 `Enter`：校验整个草稿并保存。
- `Esc`：放弃草稿并返回模型列表。

状态联动：

- `DEFAULT`：强度显示“官方默认”，不可编辑。
- `DISABLED`：强度显示“—”，不可编辑。
- `ENABLED + EFFORT`：显示 provider 原生枚举。
- `ENABLED + TOKEN_BUDGET`：显示整数与合法范围。
- `ENABLED + NONE`：只显示开关，不显示无意义强度行。

保存成功后返回模型列表，摘要立即更新。写盘失败时内存配置仍生效，并向 scrollback 输出“仅本次运行生效”的明确提示。

## 持久化

文件：`<root>/.codetui/thinking.json`。

固定结构：

```json
{
  "version": 1,
  "providers": {
    "openai": {
      "gpt-5.6-sol": {
        "mode": "ENABLED",
        "effort": "high"
      }
    },
    "qwen": {
      "qwen3.7-max": {
        "mode": "ENABLED",
        "thinkingBudget": 32768
      }
    },
    "zhipu": {
      "glm-5.2": {
        "mode": "DISABLED"
      }
    }
  }
}
```

规则：

1. JSON 按 provider、modelId 两层索引，避免跨 provider 重名串配置，也不要求 modelId 避开分隔符。
2. `DEFAULT` 不必落盘；保存为默认时删除该模型条目。
3. 未知字段忽略，便于向前兼容。
4. 文件缺失视为空配置。
5. 文件损坏时记录 WARN、整份回退为空配置，不阻断启动。
6. 写入采用同目录临时文件加原子替换，与现有 `ModelPreference` 的可靠性纪律一致。
7. 已保存但当前模型能力不再接受的配置不发送；启动时记录警告并按 `DEFAULT` 运行，原记录保留，避免临时模型清单变化替用户删除偏好。

## 请求数据流

### 主 agent

```text
用户提交
  → 快照 active provider + active model
  → store 查询该 provider/model 的 ThinkingConfig
  → provider.options(model, config)
  → ChatClient 请求
```

三个值必须在同一提交路径内快照。正在执行的回合不读取后续 UI 改动。

### 子 agent

- 未指定模型：使用当前激活 provider/model 及其配置快照。
- `spec.model` 指定模型：使用子 agent 实际请求的模型 ID 查询配置。
- 当前 v1 的 `provider:model` 路由限制保持不变，不借本功能扩展跨 provider 路由。

### 内部辅助调用

`DynamicAuxChatModel` 继续调用兼容入口 `options(modelId)`，即 `DEFAULT`。会话摘要和网页抽取不读取 store，避免用户为主 agent 开启高强度思考后同步放大隐藏调用成本。

## 错误处理

1. UI 输入非法预算或能力不支持的 effort：保存前拒绝并在面板中提示，不修改内存与磁盘。
2. 持久化失败：内存生效，scrollback 明示仅本次运行有效。
3. 远端 API 拒绝自定义模型参数：保留配置并显示原始请求错误，不自动关闭或改回默认。
4. provider 映射异常：在请求发出前失败，错误包含 provider、model 和非法配置值，但不输出 API key。
5. 配置文件损坏：WARN 后按空配置启动，不中断 TUI。
6. 模型能力随版本变化导致旧配置失效：本次按 `DEFAULT`，提示或记录具体失效原因，不删除记录。

## 测试

### 领域与持久化

- `ThinkingConfig` 状态不变量与能力校验。
- `provider + modelId` 隔离，覆盖跨家同名模型。
- JSON 往返、默认条目删除、未知字段、坏文件降级。
- 临时文件原子替换和写入失败语义。
- 旧配置与新能力不兼容时按默认运行且不删除记录。

### Provider 映射

- OpenAI：默认不带 effort，开启带原生 effort，关闭映射 `none`。
- Anthropic：adaptive、disabled、不可关闭模型和 effort 能力交集。
- Qwen：`enable_thinking`、`thinking_budget` 的 `extraBody` 结构。
- 智谱：`thinking.type` 及仅特定模型发送 effort。
- DeepSeek：同步和流式请求 JSON 均包含配置字段；默认配置时字段不存在。

### 调用链

- 主 agent 请求使用提交时 provider/model/config 同源快照。
- 子 agent 默认模型和显式模型读取正确配置。
- `DynamicAuxChatModel`、摘要和网页抽取保持 `DEFAULT`。
- 配置在回合进行中修改不影响该回合。
- DeepSeek 工具调用的 `reasoning_content` 回传行为无回归。

### UI

- `/model` 行摘要覆盖默认、关闭、effort、预算、仅开启和不可配置。
- `→` 对高亮但未激活模型也能进入设置。
- effort 循环、模式联动、预算编辑与边界校验。
- `Esc` 放弃草稿，`Enter` 原子保存。
- 持久化失败提示且内存仍生效。
- 不支持模型不进入空面板。

## 验收标准

1. 五家内置模型在 `/model` 中显示与能力一致的思考摘要。
2. 用户可为不同 provider/model 保存不同设置，重启后恢复。
3. `DEFAULT` 请求与功能上线前的请求字段一致，不新增思考覆盖。
4. 主 agent 与子 agent 使用对应配置，辅助调用不受影响。
5. Qwen 展示和保存原生 token budget，不出现虚构档位。
6. 不支持的选项不会出现在内置模型面板中；自定义模型被远端拒绝时错误可见且设置不丢失。
7. DeepSeek 同步、流式和工具调用均保留现有行为，并正确发送新字段。
8. 全部单元测试和模块构建通过。

## 外部依据

- OpenAI Reasoning models：`https://platform.openai.com/docs/guides/reasoning`
- Anthropic Extended thinking：`https://docs.anthropic.com/en/docs/build-with-claude/extended-thinking`
- 阿里云百炼深度思考：`https://help.aliyun.com/zh/model-studio/deep-thinking`
- 智谱对话补全参数：`https://docs.bigmodel.cn/api-reference/模型-api/对话补全异步`
- DeepSeek Thinking Mode：`https://api-docs.deepseek.com/guides/thinking_mode/`

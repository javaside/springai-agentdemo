# Subagent 功能 + 多 Provider 可切换 — 设计文档

日期：2026-07-04
模块：`springai-code-tui`
状态：待评审

## 1. 目标与动机

给 code-tui（终端里的类 Claude Code 编码 agent）加入 **subagent（子 agent）** 能力，并把底层从「写死 DeepSeek」升级为 **anthropic / openai / deepseek 三家可切换**。切到哪家，主 agent 与子 agent 就都跑在那家上。

子 agent 沿用 Claude Code 的心智模型：主 agent 可以把「探索代码库 / 设计实现方案 / 跑命令 / 多步研究」这类子任务，委派给带独立上下文、独立系统提示、受限工具集的专职子 agent，避免污染主对话上下文。

复用 `spring-ai-agent-utils` 0.10.0 的 Subagent Framework（SPI + TaskTool），但**不使用它内置的 4 个 Claude 子 agent**（它们写死 "You are an agent for Claude Code, Anthropic's official CLI"，绑定 Claude Code 身份、绑定 Claude Code 的工具名，属于**不通用**），也**不使用** `ClaudeSubagentType` 便捷构建器（工具处理有硬伤，见 §2.3）。

我们**参照内置 4 个的职责，重写我们自己的一套通用子 agent**（general-purpose / explore / plan / bash），随本仓库分发。这样身份中性、不绑定任何一家模型：**无论切到 DeepSeek / OpenAI / Anthropic，同一套子 agent 都能用。**

### 第一版范围（本 spec）

- 多 provider 抽象 + `/model` 跨 provider 切换。
- 子 agent 前台**串行**执行，内部工具活动**内联嵌套可见**（仿 Claude Code）。
- **自带 4 个通用子 agent**（参照内置重写，身份中性，工具名与本项目对齐），随仓库 `resources/agents/*.md` 分发。
- **不做** `run_in_background`（后台并发）——executor 遇到该参数降级为前台串行执行。并发与 `/tasks` 详情面板列为明确的第二版。

## 2. 关键调研结论（决定设计的事实）

来自对 0.10.0 源码 + 文档的核实：

1. **框架 SPI 是 provider 中立的**。`SubagentExecutor.execute(TaskCall, SubagentDefinition)` 只返回 `String`；`ClaudeSubagentExecutor` 内部只认 `Map<String, ChatClient.Builder>` + `ChatClient`，对底层是 Claude 还是 DeepSeek 一无所知。「Claude」在框架里指的是**定义文件格式**（markdown + YAML frontmatter），不是 LLM provider。多 provider 路由（`provider:model`）是框架原生能力。→ **SPI 层适合承载 DeepSeek 与多 provider。**

2. **内置执行是纯阻塞** `.call().content()`（`ClaudeSubagentExecutor.execute`）——子 agent 内部的工具调用、思考对外部全程不可见，只返回最终字符串。→ **要「子 agent 可见」必须自写 executor 用 `.stream()`。**

3. **`ClaudeSubagentType` 便捷构建器有硬伤**：内部 `FileSystemTools.builder().build()` **不带 `allowedDirectory`**（逃出项目根边界），且**没有 builder 方法传入自定义工具**。→ **不用这个构建器；直接持有自己的工具列表喂给自写 executor。**

4. **内置 4 个 .md 不通用、且在 jar 内不可原地改**：它们写死 "You are an agent for Claude Code, Anthropic's official CLI"，`tools` 字段用 Claude Code 工具名（`Read`/`WebFetch`/`Bash`/`TaskCreate`…），部分带 `model: default`（`ClaudeSubagentDefinition.getModel()` 对 `default` 返回字面量 `"default"` 会翻车）。这些文件在依赖 jar 里，**无法原地修改**。→ **不用内置的；参照其职责重写我们自己的通用 .md，把身份改中性、`tools` 写本项目真实工具名、不写 `model`（跟随激活 provider）。**

5. **同一响应内多个工具调用是串行执行**：`DefaultToolCallingManager.executeToolCall` 用 `for` 循环顺序跑（同线程）。→ **前台即使模型一次请求多个 `Task()`，也是串行，同时只有一个子 agent 活动，无并发渲染难题。并发只来自 `run_in_background`（第一版不做）。**

6. **`kind=="CLAUDE"` 会强制自动加载 jar 内置原版**：`TaskTool.build()` 里只要任一注册的 `SubagentType.kind()` == `"CLAUDE"`，就无条件塞入 4 个 classpath 内置 Claude 子 agent（`TaskTool` 第 233–242 行，从 jar 内 `agent/*_SUBAGENT.md` 加载）。若我们也用 CLAUDE + 同名 .md，会与内置原版**撞车**（`TaskFunction` 用 `toMap(getName)` 建索引，同名抛异常），且把没改的 "Claude Code" 内容又带回来。→ **我们的子 agent 体系用独立 kind `"LOCAL"`，彻底关掉这个自动加载，只用我们自己的 4 个通用 .md。**

## 3. 架构

分两层，之间靠一个新的 `LlmProvider` 抽象解耦。

```
┌─ ProviderRegistry ── 持有 3 个 LlmProvider，记录「当前激活」 ───────────────┐
│                                                                            │
│  主 Agent 层 (AgentTools + CodingAgent)                                    │
│    · ChatClient 用 激活provider.chatModel() 构建；工具/SessionMemory 照旧    │
│    · submit() 用 激活provider.options(model) 覆盖，不再写死 DeepSeekOptions  │
│    · 会话历史是 provider 中立的 Message 事件 → 切家后新家看到完整历史        │
│                                                                            │
│  Subagent 层 (框架 TaskTool + 自写 kind="LOCAL" 三件套)                     │
│    · 自带 4 个通用 .md（resources/agents/*.md）+ 自写 resolver/definition    │
│    · 一个 provider 中立 executor，消费「激活 provider 的 ChatClient.Builder  │
│      + options + 带 root 边界 & 带 ToolEventCallback 的工具」               │
│    · 前台串行 .stream() 跑子 agent → 用嵌套 taskId 把工具活动上报 TUI       │
│    · kind="LOCAL" → 不触发框架对 jar 内置原版的自动加载，只用我们自己的      │
└────────────────────────────────────────────────────────────────────────────┘
```

## 4. 组件设计

### 4.1 `LlmProvider` 抽象（新增，主 agent 与子 agent 共用）

把三家的差异收进一个接口：

```java
interface LlmProvider {
    String id();                          // "anthropic" | "openai" | "deepseek"
    boolean available();                  // 对应 API key 是否配置（无 key 则不出现在 /model）
    ChatModel chatModel();                // 各家 ChatModel（带 key/base）
    ChatOptions options(String modelId);  // 每请求覆盖模型用的 provider 专属 options
    List<ModelOption> models();           // 该家可选模型（/model 展示）
    String defaultModel();
}
```

三个实现：
- `DeepSeekProvider`（现有依赖 `spring-ai-deepseek`）——现役，默认激活。
- `AnthropicProvider`（新增依赖 `spring-ai-anthropic`）。
- `OpenAiProvider`（新增依赖 `spring-ai-openai`）。

**只有配了对应 API key 的 provider 才 `available()`**，`/model` 只列可用的家。

`ProviderRegistry`：持有全部 provider，记录「当前激活 id」，提供 `active()`、`activate(id)`、`allModels()`（跨家聚合、每项带 provider 标签）。

### 4.2 主 agent 层改造

**`AgentTools.build(...)`**：签名从收 `DeepSeekChatModel` 改为收 `LlmProvider`（或 `ProviderRegistry`）。`ChatClient.builder(provider.chatModel())` 构建；工具装饰、SessionMemory advisor、系统提示模板等**全部照旧**（provider 无关）。系统提示里的 `{AGENT_MODEL}` grounding 改为反映激活 provider + 模型。

**`CodingAgent.submit(...)`**：`.options(DeepSeekChatOptions...)` 改为 `.options(activeProvider.options(model))`。`MODELS` 常量下沉到各 provider。`/model` 语义升级：跨 provider 列出所有可用模型，选中即 `registry.activate(providerId)` + 设定 model，主 agent 与子 agent 同时切换。

**会话历史中立性**：spring-ai-session 存的是 `Message` 事件（provider 中立），切家后新 ChatClient 读到的是同一份历史，无缝延续。（注意：不同家的 system prompt / 工具描述会重新注入，属正常。）

### 4.3 Subagent 层（kind="LOCAL" 三件套 + 自带 .md）

我们**不复用**框架的 `ClaudeSubagentResolver` / `ClaudeSubagentDefinition`（它们把 kind 写死为 `"CLAUDE"`，会触发 §2.6 的自动加载撞车）。改为自写一套极薄的 `LOCAL` 三件套，全部复用框架的 `MarkdownParser` 解析 frontmatter。

#### `LocalSubagentDefinition implements SubagentDefinition`（自写，约几十行）

- `getKind()` → **`"LOCAL"`**（常量）。
- `getName()/getDescription()/tools()/disallowedTools()/skills()` → 从 frontmatter 取（复用 `MarkdownParser`，逗号分隔切分，与内置 `ClaudeSubagentDefinition` 同语义）。
- `getModel()` → frontmatter 里**不写 `model`**，返回 `null`（跟随激活 provider）；若显式写 `provider:model` 则原样返回供路由。**从根上绕开了内置的 `model:default` 字面量 bug。**
- `getContent()` → 子 agent 的系统提示正文（我们重写的中性内容）。

#### `LocalSubagentResolver implements SubagentResolver`（自写，约十几行）

- `canResolve(ref)` → `ref.kind().equals("LOCAL")`。
- `resolve(ref)` → 读取 `ref.uri()` 指向的 classpath 资源（`classpath:/agents/*.md`）→ `new MarkdownParser(markdown)` → `new LocalSubagentDefinition(ref, parser.getFrontMatter(), parser.getContent())`。

#### `ChatClientSubagentExecutor implements SubagentExecutor`（自写，核心）

- `getKind()` → **`"LOCAL"`**。
- 构造依赖：`ProviderRegistry`（取激活 provider 建 ChatClient.Builder + options）、**我们自己的、带 root 边界 + 带 ToolEventCallback 的工具列表**、`AgentListener`、skills 目录。
- `execute(TaskCall, SubagentDefinition)`：
  1. 分配一个 `taskId`，向 listener 发 `onSubagentStarted(parentTurnId, taskId, agentName, description)`。
  2. 用激活 provider 的 builder 建子 agent 专用 ChatClient：注入按 definition 的 `tools`/`disallowedTools` 过滤后的工具（**因为 .md 的 `tools` 字段已写本项目真实工具名，直接按名过滤，无需别名映射表**），system = 子 agent 的 prompt（+ 预加载 skills），挂 `ToolCallAdvisor`。**不挂** SessionMemory advisor（子 agent 上下文独立）。
  3. **model 解析**：definition 的 `model` 为 `null`/空 → 用激活 provider 默认模型；`provider:model` → 路由到指定 provider（若该家不可用则回退激活 provider）。（内置的 `model:default` 字面量 bug 在我们的 .md 里根本不存在，因为我们不写 `model`。）
  4. `.stream()` 执行；子 agent 内部工具经**同一套 `ToolEventCallback`**、但带 `taskId` 上报 → TUI 内联嵌套显示。
  5. 完成后 `onSubagentFinished(parentTurnId, taskId, 最终文本)`，返回最终文本给框架（作为 Task 工具的返回值回灌主 agent）。
- **`run_in_background`（第一版）**：忽略该参数，一律前台串行执行（不接 TaskRepository）。

#### `TaskTool` 装配

```java
SubagentType localType = new SubagentType(
        new LocalSubagentResolver(),         // 自写，认 kind=="LOCAL"
        new ChatClientSubagentExecutor(...)  // 自写 executor，getKind()=="LOCAL"
);

// 我们自己的 4 个通用子 agent 的引用（classpath 资源，kind=LOCAL）
List<SubagentReference> refs = List.of(
    new SubagentReference("classpath:/agents/general-purpose.md", "LOCAL", Map.of()),
    new SubagentReference("classpath:/agents/explore.md",         "LOCAL", Map.of()),
    new SubagentReference("classpath:/agents/plan.md",            "LOCAL", Map.of()),
    new SubagentReference("classpath:/agents/bash.md",            "LOCAL", Map.of())
);

ToolCallback taskTool = TaskTool.builder()
    .subagentTypes(localType)
    .subagentReferences(refs)     // 显式提供；无 CLAUDE 类型 ⇒ 框架不自动加载 jar 内置原版
    // 可选：项目层自定义 .md（<root>/.codetui/agents/*.md），无则不加
    .build();
```

`taskTool` 作为一个普通 `ToolCallback` 加入主 agent 工具集，同样用 `ToolEventCallback` 装饰（这样「Task 这次委派」本身在主流也显示为一行）。

### 4.4 子 agent 定义：参照内置重写四个通用版

**自带 4 个 .md，放 `springai-code-tui/src/main/resources/agents/`，随仓库分发。** 参照内置四个的职责重写，身份改中性、工具名与本项目对齐、不写 `model`：

| 文件 / name | 只读? | 职责（参照内置） | 相对内置的改动 |
|-------------|-------|------------------|----------------|
| `general-purpose.md` | 可写 | 复杂研究 + 多步执行，全工具 | 身份中性化；`tools: *`（继承全部本项目工具） |
| `explore.md` | 只读 | 快速探索代码库，找文件/搜代码/答疑，保留 quick/medium/thorough 档次 | 身份中性化；保留强只读约束；无 `tools`（靠 prompt 约束只读，或显式列只读工具） |
| `plan.md` | 只读 | 软件架构师，出实现方案，保留输出格式约束 | 身份中性化；`tools` 改为本项目真实只读工具名 |
| `bash.md` | 可写(仅命令) | 命令执行专家，git/构建/测试，保留 git 安全约束 | 身份中性化；`tools: <本项目 shell 工具真实名>`；不写 `model` |

**重写策略（内容层面）**：
1. **身份中性化** —— 把 "You are an agent/file search specialist **for Claude Code, Anthropic's official CLI for Claude**" 一律改为中性表述（如 "You are a codebase exploration specialist working inside a terminal coding agent"），去掉一切 Claude Code / Anthropic CLI 专属身份，使之**不绑定任何一家模型或产品**。
2. **`tools` 用本项目真实工具名** —— 以 `getToolDefinition().name()` 核定的注册名书写（如 `read`/`webFetch`/`bash`/`Skill`…），executor 直接按名过滤，**无需别名映射表**。
3. **不写 `model`** —— 让子 agent 跟随激活 provider 的默认模型；需要固定某家时才写 `provider:model`。
4. **保留精华** —— Explore 的强只读约束、Plan 的输出格式、Bash 的 git 安全条款、thoroughness 档次等原样保留（这些与身份无关、是有价值的行为约束）。

> 语言：子 agent prompt **默认保持英文**（贴近内置、便于跨模型稳定）。如需与主 agent 中文风格统一，可在重写时一并中文化——留待评审时按需决定，不阻塞设计。

**只读保证**：explore/plan 的 prompt 本身已含强只读约束（"CRITICAL: READ-ONLY MODE"、"do NOT modify any files"）。叠加已知边界（沿用主 agent 诚实声明）：只有 FileSystemTools 有强制目录边界；Shell/Grep/Glob 技术上不受强制约束，靠 prompt 自律。

**未来自定义**：若日后想加项目专属子 agent，放 `<root>/.codetui/agents/*.md`，构造 kind=LOCAL 的 `SubagentReference` 追加即可（第一版不做，缝已留好）。

## 5. 事件模型（`AgentListener` 扩展）

现有方法都带 `turnId`。子 agent 引入 `taskId` 维度：

新增：
```java
void onSubagentStarted(long turnId, String taskId, String agentName, String description);
void onSubagentFinished(long turnId, String taskId, String finalText);
```

工具事件复用现有 `onToolStarted/onToolFinished`，但需能携带「所属 taskId（可空）」以便 TUI 决定缩进层级。方案：给这两个方法**加一个可空 `taskId` 维度**（或新增带 taskId 的重载），保持 turnId 语义不变。`ToolEventCallback` 从 ToolContext 读 `taskId`（executor 给子 agent 工具的 ToolContext 里放 taskId），无 taskId 即主流工具。

**turnId 归属不变**：子 agent 工具事件的 turnId = 发起 Task 的那个回合 turnId（迟到过滤、取消语义与主流一致）。

## 6. TUI 渲染（`CodeTuiView` / ScrollbackPrinter）

前台串行，同时只有一个子 agent 活动，渲染为内联嵌套（缩进）：

```
▸ Task(explore) 分析认证模块         ← onSubagentStarted
    ⎿ Grep "authenticate"  3 matches   ← 子 agent 内部工具，taskId 非空 → 缩进
    ⎿ read AuthService.java
    ⎿ Glob **/*Security*.java
  ✓ 认证走 JWT，入口在 …            ← onSubagentFinished（最终结论）
```

- 有 `taskId` 的工具事件 → 缩进一级挂在当前 Task 块下。
- 复用现有 `ScrollbackPrinter` / 缩进渲染；不引入新的浮层（`/tasks` 面板留第二版）。
- **注意 memory 规则**：内联 TUI 高亮不能用背景色条（会串到下一项），需纯前景高亮；改完必须 pty+pyte 实机验证并重新 package。

## 7. 取消语义

Esc 取消当前回合时，前台子 agent 的 `.stream()` 订阅随主链 dispose 一并取消（子 agent 是主回合同步链的一部分）。会话回滚（`rollbackTo`）沿用现有机制。第一版无后台任务，无游离子 agent。

## 8. 依赖变更（pom）

`springai-code-tui/pom.xml` 新增：
- `spring-ai-anthropic`（Spring AI 2.0 提供）
- `spring-ai-openai`（Spring AI 2.0 提供）

（`spring-ai-agent-utils` 0.10.0、`spring-ai-deepseek` 已有。）

API key 经配置/环境变量注入；缺 key 的 provider `available()==false`，不出现在 `/model`，不阻断启动。

## 9. 测试策略

- `LlmProvider` / `ProviderRegistry`：单测 available 过滤、activate、allModels 聚合。
- `LocalSubagentResolver` / `LocalSubagentDefinition`：单测 canResolve 只认 LOCAL、frontmatter 解析（name/description/tools/disallowedTools）、`getModel()` 无 model 时返回 null、`provider:model` 原样返回。
- `ChatClientSubagentExecutor`：用桩 ChatClient 验证——`getKind()=="LOCAL"`、按 tools/disallowedTools 过滤本项目工具（真实名直接过滤、不误伤）、model 空→默认模型、`provider:model` 路由与回退、子 agent 工具事件带 taskId 上报、run_in_background 被降级为前台。
- 装配：验证 TaskTool 只注册了我们自己的 4 个通用 agent（general-purpose/explore/plan/bash 可解析可执行）、**未**自动加载 jar 内置 Claude 原版（无同名撞车）、走的是我们的 executor。
- .md 内容校验：断言 4 个 .md 均不含 "Claude Code" / "Anthropic's official CLI" 字样（防身份中性化回退）。
- 事件/渲染：render 冒烟单测覆盖 onSubagentStarted/Finished + 带 taskId 工具事件的缩进；**pty+pyte 实机冒烟**（设窗口大小 + TERM=xterm-256color）验证嵌套显示不串色。
- **排除** 网络易抖的 `CodingAgentSpikeTest`。

## 10. 明确不做（第一版）

- `run_in_background` 后台并发执行。
- `/tasks` 任务详情面板与「选择查看某个子 agent」。
- `TaskOutputTool`（后台结果检索）。
- subagent `resume`（复用上次上下文）。
- 子 agent 的 skills 预加载缓存优化（框架有 TODO，非本版目标）。

## 11. 落地顺序（供后续 writing-plans 细化）

1. `LlmProvider` + 三实现 + `ProviderRegistry`（先不接 UI，纯装配）。
2. 主 agent 层改造：`AgentTools.build` 收 provider、`CodingAgent.submit` 用 provider.options；`/model` 跨家切换。
3. 自带 4 个通用 `.md`（参照内置重写：身份中性、真实工具名、无 model）+ `LocalSubagentDefinition` / `LocalSubagentResolver` / `ChatClientSubagentExecutor`（kind=LOCAL）+ TaskTool 装配（显式提供 refs，不触发 jar 内置自动加载）。
4. `AgentListener` 扩展 + `ToolEventCallback` 带 taskId + `CodeTuiView` 嵌套渲染。
5. 测试 + pty 冒烟 + 重新 package。

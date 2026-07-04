# Subagent 功能 + 多 Provider 可切换 — 设计文档

日期：2026-07-04
模块：`springai-code-tui`
状态：待评审

## 1. 目标与动机

给 code-tui（终端里的类 Claude Code 编码 agent）加入 **subagent（子 agent）** 能力，并把底层从「写死 DeepSeek」升级为 **anthropic / openai / deepseek 三家可切换**。切到哪家，主 agent 与子 agent 就都跑在那家上。

子 agent 沿用 Claude Code 的心智模型：主 agent 可以把「探索代码库 / 设计实现方案 / 跑命令 / 多步研究」这类子任务，委派给带独立上下文、独立系统提示、受限工具集的专职子 agent，避免污染主对话上下文。

**架构决策：自写一套 SubagentTool，不依赖 `spring-ai-agent-utils` 的 `TaskTool`。** 参照该库 `org.springaicommunity.agent.tools.task` 包（`TaskTool` / `TaskOutputTool` / `BackgroundTask` / `ClaudeSubagentExecutor`）的设计，但不引用它们（理由见 §2）。同时**自带 4 个通用子 agent**（general-purpose / explore / plan / bash），身份中性、不绑定任何一家模型，随本仓库分发 —— 切到 DeepSeek / OpenAI / Anthropic 都是同一套。

### 第一版范围（本 spec）

- 多 provider 抽象 + `/model` 跨 provider 切换。
- 子 agent 前台**串行**执行，内部工具活动**内联嵌套可见**（仿 Claude Code）。
- 自写 `SubagentTool`（工具名 `Task`），自带 4 个通用子 agent（`resources/agents/*.md`）。
- **不做** `run_in_background`（后台并发）——工具的输入 schema 里第一版**不暴露**该参数。并发、`/tasks` 面板、`TaskOutput` 等价工具列为明确的第二版（§12 给出自写设计草图，确保缝已留好）。

## 2. 关键调研结论（决定「自写」的事实）

来自对 0.10.0 `org.springaicommunity.agent.tools.task` 整包源码 + common SPI 的逐类核实：

1. **框架 Task 机制拆开看，残值很低**。`TaskTool.TaskFunction` 只做两件事：按 `subagent_type` 名找 definition、按 `definition.getKind()` 找 executor，然后 `executor.execute(taskCall, subagent)`。**真正的执行逻辑 100% 在 executor 里**——而 executor 我们本来就要自写（见下）。框架 `TaskTool` 额外只提供：路由（~3 行）、一段工具描述 prose（Apache 授权，复制一次即可）、`run_in_background` 的后台 repository。

2. **默认 executor 是纯阻塞且 provider 有偏**。`ClaudeSubagentExecutor.execute` 用 `.call().content()`（子 agent 内部活动全程不可见），且内含 `MODEL_NAME_MAPPER` 把 `opus→claude-opus-4-64k` 等 **Anthropic 模型名写死**。→ 要「子 agent 可见」+「provider 中立」，executor 必须自写（`.stream()` + 经 `LlmProvider` 做模型解析）。

3. **框架后台 repository 形状不对**。`DefaultTaskRepository` = cached daemon 线程池 + `CompletableFuture.supplyAsync`，`BackgroundTask` 包一个裸 `CompletableFuture<String>`。**无事件、无 taskId、无 reactor 集成、无 Esc 取消挂钩**。我们 v2 要的 `/tasks` 面板 + 内联实时渲染 + Esc 取消，需要后台任务能往 `AgentListener` 发带 taskId 的事件、挂在 reactor 生命周期上 —— 这个「白拿的后台」形状不对，v2 反正要换。

4. **固定的 `TaskCall` schema 会诱导模型**。框架工具的输入 record 固定 6 字段 `(description, prompt, subagent_type, model, resume, run_in_background)`。套框架就得整份暴露给模型，包括 v1 不支持的 `run_in_background`/`resume`。自写工具 → 自己的精简 schema，v1 只留 `description`/`prompt`/`subagent_type`，不诱导模型用未实现的功能。

5. **`ClaudeSubagentType` 便捷构建器有硬伤**（若走框架路径的话）：内部 `FileSystemTools.builder().build()` **不带 `allowedDirectory`**（逃出项目根边界），且**没有 builder 方法传入自定义工具**、无事件装饰。→ 无论如何都不能用它；工具必须我们自己持有（带 root 边界 + `ToolEventCallback`）。

6. **内置 4 个 .md 不通用、在 jar 内不可原地改**：写死 "You are an agent for Claude Code, Anthropic's official CLI"，`tools` 用 Claude Code 工具名，部分带 `model: default`（会翻车）。→ 参照其职责**重写我们自己的通用 .md**（§4.4）。

7. **同一响应内多个工具调用是串行执行**：`DefaultToolCallingManager.executeToolCall` 用 `for` 循环顺序跑（同线程）。→ 前台即使模型一次请求多个 `Task()`，也是串行，同时只有一个子 agent 活动，无并发渲染难题。并发只来自 v2 的后台执行。

**可复用（非依赖）的东西**：框架的 `MarkdownParser`（解析 frontmatter，主工具已在用 agent-utils）、`DefaultResourceLoader` 读 `classpath:`/`file:` 资源的配方（`ClaudeSubagentResolver.resolve` 里的写法）、`TaskTool` 的描述 prose（复制并裁掉 background/resume/parallel 段）、`BackgroundTask` 的 `CompletableFuture` 模式（v2 参照）。

## 3. 架构

分两层，之间靠一个新的 `LlmProvider` 抽象解耦；子 agent 层完全自写。

```
┌─ ProviderRegistry ── 持有 3 个 LlmProvider，记录「当前激活」 ───────────────┐
│                                                                            │
│  主 Agent 层 (AgentTools + CodingAgent)                                    │
│    · ChatClient 用 激活provider.chatModel() 构建；工具/SessionMemory 照旧    │
│    · submit() 用 激活provider.options(model) 覆盖，不再写死 DeepSeekOptions  │
│    · 会话历史是 provider 中立的 Message 事件 → 切家后新家看到完整历史        │
│                                                                            │
│  Subagent 层 (全自写，不依赖框架 TaskTool)                                  │
│    · SubagentTool ── FunctionToolCallback("Task")，自己的精简输入 schema     │
│    · SubagentLoader ── 读 resources/agents/*.md → List<SubagentSpec>        │
│    · SubagentRunner ── provider 中立执行器：过滤工具 → .stream()             │
│         → 带 taskId 把子 agent 内部工具活动上报 AgentListener → TUI 嵌套     │
│    · v1 前台串行；v2 换自写 SubagentTaskManager（事件+reactor 集成，§12）    │
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

**`AgentTools.build(...)`**：签名从收 `DeepSeekChatModel` 改为收 `LlmProvider`（或 `ProviderRegistry`）。`ChatClient.builder(provider.chatModel())` 构建；工具装饰、SessionMemory advisor、系统提示模板等**全部照旧**（provider 无关）。系统提示里的 `{AGENT_MODEL}` grounding 改为反映激活 provider + 模型。**新增**：把自写的 `SubagentTool`（§4.3）作为一个普通 `ToolCallback` 加入主 agent 工具集，同样用 `ToolEventCallback` 装饰（「Task 这次委派」在主流显示为一行）。

**`CodingAgent.submit(...)`**：`.options(DeepSeekChatOptions...)` 改为 `.options(activeProvider.options(model))`。`MODELS` 常量下沉到各 provider。`/model` 语义升级：跨 provider 列出所有可用模型，选中即 `registry.activate(providerId)` + 设定 model，主 agent 与子 agent 同时切换。

**会话历史中立性**：spring-ai-session 存的是 `Message` 事件（provider 中立），切家后新 ChatClient 读到的是同一份历史，无缝延续。

### 4.3 Subagent 层（全自写）

四个自写组件，`kind` 概念不存在（不需要躲框架的自动注册）。

#### `SubagentSpec`（自写数据类型）

一个子 agent 的解析结果：
```java
record SubagentSpec(
    String name,              // frontmatter name，也是 subagent_type 的取值
    String description,       // frontmatter description，进工具描述清单
    String systemPrompt,      // markdown 正文（我们重写的中性内容）
    List<String> allowTools,  // frontmatter tools（本项目真实工具名；空=全部）
    List<String> denyTools,   // frontmatter disallowedTools
    String model,             // frontmatter model（可空；空=跟随激活 provider）
    List<String> skills       // frontmatter skills（可空）
) {}
```
不写 `model` → `model==null` → 跟随激活 provider。**从根上绕开内置的 `model:default` 字面量 bug。**

#### `SubagentLoader`（自写，参照 `ClaudeSubagentResolver`/`ClaudeSubagentReferences`）

- 用 `DefaultResourceLoader` 读 `classpath:/agents/*.md`（内置四个），逐个 `new MarkdownParser(md)` 拿 frontMatter + content → `SubagentSpec`。
- 预留：再扫 `<root>/.codetui/agents/*.md`（项目自定义，第一版不启用），同名则项目层覆盖内置。
- 返回 `Map<name, SubagentSpec>`，SubagentTool 直接用它路由。

#### `SubagentRunner`（自写，核心；参照 `ClaudeSubagentExecutor` 但流式 + provider 中立）

- 构造依赖：`ProviderRegistry`、**我们自己的、带 root 边界 + 带 ToolEventCallback 的工具列表**、`AgentListener`、skills 目录。
- `String run(SubagentSpec spec, String prompt, long parentTurnId)`：
  1. 分配 `taskId`，发 `onSubagentStarted(parentTurnId, taskId, spec.name(), description)`。
  2. 取激活 provider 的 `ChatClient.Builder`，按 `spec.allowTools()`/`denyTools()` 过滤工具（**按 `getToolDefinition().name()` 精确匹配；因 .md 里已写本项目真实工具名，无需别名映射表**），system = `spec.systemPrompt()`（+ 预加载 skills），挂 `ToolCallAdvisor`。**不挂** SessionMemory advisor（子 agent 上下文独立）。
  3. **model 解析**：`spec.model()` 为空 → 激活 provider 默认模型；`provider:model` → 路由指定 provider（不可用则回退激活 provider）。经 `LlmProvider.options()`，**不用**框架那张 Anthropic 写死的 `MODEL_NAME_MAPPER`。
  4. `.stream()` 执行；子 agent 内部工具经**同一套 `ToolEventCallback`**、带 `taskId` 上报 → TUI 内联嵌套显示。
  5. 完成后发 `onSubagentFinished(parentTurnId, taskId, 最终文本)`，返回最终文本。

#### `SubagentTool`（自写，工具名 `Task`）

- 精简输入 record（v1 不含 `run_in_background`/`resume`）：
  ```java
  record SubagentCall(
      @ToolParam(description="3-5 word summary") String description,
      @ToolParam(description="detailed autonomous task prompt") String prompt,
      @ToolParam(description="which agent type") String subagent_type) {}
  ```
- `FunctionToolCallback.builder("Task", fn).inputType(SubagentCall.class).description(desc).build()`，其中：
  - `fn`：`subagent_type` 找 `SubagentSpec`（找不到抛清晰错误），调 `runner.run(spec, prompt, currentTurnId)` 返回结果。currentTurnId 经 ToolContext/ThreadLocal 取（复用现有 `ToolEventCallback` 的 turnId 机制）。
  - `desc`：复制框架 `TASK_DESCRIPTION_TEMPLATE`、**裁掉 background/resume/parallel 段**，`%s` 用四个 spec 的 `name: description`（自己拼，不依赖 `toSubagentRegistrations()`）填充。
- v2：给 `SubagentCall` 加回 `run_in_background`，`fn` 分流到 `SubagentTaskManager`（§12）。

### 4.4 子 agent 定义：自带四个通用版（`resources/agents/*.md`）

参照内置四个的职责**重写我们自己的通用 .md**，随仓库分发。身份中性、`tools` 写本项目真实工具名、不写 `model`：

| 文件 / name | 只读? | 职责（参照内置） | 相对内置的改动 |
|-------------|-------|------------------|----------------|
| `general-purpose.md` | 可写 | 复杂研究 + 多步执行，全工具 | 身份中性化；无 `tools`（继承全部本项目工具） |
| `explore.md` | 只读 | 快速探索代码库，找文件/搜代码/答疑，保留 quick/medium/thorough 档次 | 身份中性化；保留强只读约束；`tools` 显式只列只读工具 |
| `plan.md` | 只读 | 软件架构师，出实现方案，保留输出格式约束 | 身份中性化；`tools` 改为本项目真实只读工具名 |
| `bash.md` | 可写(仅命令) | 命令执行专家，git/构建/测试，保留 git 安全约束 | 身份中性化；`tools` 写本项目 shell 工具真实名；不写 `model` |

**重写策略**：
1. **身份中性化** —— 把 "You are an agent … **for Claude Code, Anthropic's official CLI for Claude**" 一律改成不绑定任何产品/模型的表述（如 "You are a codebase exploration specialist working inside a terminal coding agent"）。
2. **`tools` 用本项目真实工具名** —— 以 `getToolDefinition().name()` 核定的注册名书写；runner 直接按名过滤。
3. **不写 `model`** —— 跟随激活 provider；需固定某家时才写 `provider:model`。
4. **保留精华** —— explore 只读约束、plan 输出格式、bash 的 git 安全条款、thoroughness 档次原样保留。

> 语言：子 agent prompt **默认英文**（贴近内置、跨模型稳）。如需与主 agent 中文风格统一，可在重写时一并中文化 —— 留待评审时按需决定，不阻塞设计。

**只读保证**：explore/plan 的 prompt 本身已含强只读约束，且 `tools` 只列只读工具。叠加已知边界（沿用主 agent 诚实声明）：只有 FileSystemTools 有强制目录边界；Shell/Grep/Glob 技术上不受强制约束，靠 prompt + tools 白名单双保险。

## 5. 事件模型（`AgentListener` 扩展）

现有方法都带 `turnId`。子 agent 引入 `taskId` 维度：

新增：
```java
void onSubagentStarted(long turnId, String taskId, String agentName, String description);
void onSubagentFinished(long turnId, String taskId, String finalText);
```

工具事件复用现有 `onToolStarted/onToolFinished`，但需能携带「所属 taskId（可空）」以便 TUI 决定缩进层级。方案：给这两个方法**加一个可空 `taskId` 维度**（或新增带 taskId 的重载），保持 turnId 语义不变。`ToolEventCallback` 从 ToolContext 读 `taskId`（runner 给子 agent 工具的 ToolContext 里放 taskId），无 taskId 即主流工具。

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

（`spring-ai-agent-utils` 0.10.0、`spring-ai-deepseek` 已有；仍用 agent-utils 的工具类 + `MarkdownParser`，只是**不用**其 `TaskTool`。）

API key 经配置/环境变量注入；缺 key 的 provider `available()==false`，不出现在 `/model`，不阻断启动。

## 9. 测试策略

- `LlmProvider` / `ProviderRegistry`：单测 available 过滤、activate、allModels 聚合。
- `SubagentLoader`：单测四个 .md 可解析（name/description/tools/model/skills）、`model` 缺省为 null、classpath 加载成功、（预留）项目层覆盖同名。
- `SubagentRunner`：用桩 ChatClient 验证——按 allow/deny 精确名过滤工具（不误伤）、model 空→默认模型、`provider:model` 路由与回退、子 agent 工具事件带 taskId 上报、最终文本返回。
- `SubagentTool`：验证 `Task` 工具 schema 只含 3 字段（无 run_in_background/resume）、未知 subagent_type 抛清晰错误、描述含四个 agent 清单。
- .md 内容校验：断言四个 .md 均不含 "Claude Code" / "Anthropic's official CLI" 字样（防身份中性化回退）。
- 事件/渲染：render 冒烟单测覆盖 onSubagentStarted/Finished + 带 taskId 工具事件的缩进；**pty+pyte 实机冒烟**（设窗口大小 + TERM=xterm-256color）验证嵌套显示不串色。
- **排除** 网络易抖的 `CodingAgentSpikeTest`。

## 10. 明确不做（第一版）

- `run_in_background` 后台并发执行（schema 里不暴露）。
- `/tasks` 任务详情面板与「选择查看某个子 agent」。
- `TaskOutput` 等价工具（后台结果检索）。
- subagent `resume`（复用上次上下文）。
- 项目层 `<root>/.codetui/agents/*.md` 自定义（loader 留缝，暂不启用）。
- 子 agent 的 skills 预加载缓存优化。

## 11. 落地顺序（供后续 writing-plans 细化）

1. `LlmProvider` + 三实现 + `ProviderRegistry`（先不接 UI，纯装配）。
2. 主 agent 层改造：`AgentTools.build` 收 provider、`CodingAgent.submit` 用 provider.options；`/model` 跨家切换。
3. 自带四个通用 `.md` + `SubagentSpec` / `SubagentLoader` / `SubagentRunner` / `SubagentTool`（工具名 `Task`），装进主 agent 工具集。
4. `AgentListener` 扩展 + `ToolEventCallback` 带 taskId + `CodeTuiView` 嵌套渲染。
5. 测试 + pty 冒烟 + 重新 package。

## 12. 第二版后台执行设计草图（留缝，非本版实现）

确保第一版不挖坑：
- `SubagentCall` 加回 `run_in_background`；`SubagentTool` 的 `fn` 见 true 则走 `SubagentTaskManager`。
- **自写 `SubagentTaskManager`**（不用框架 `DefaultTaskRepository`）：持有 `Map<taskId, RunningSubagent>`；后台执行仍走 `SubagentRunner.run`，但在**独立订阅**上跑，事件照发 `AgentListener`（带 taskId），从而 `/tasks` 面板能实时渲染、Esc 能定向取消某个后台任务。
- **自写 `TaskOutput` 等价工具**：按 taskId 查 `SubagentTaskManager`，可阻塞等待或即时返回状态（参照框架 `TaskOutputTool` 的 block/timeout 语义）。
- `/tasks` slash 命令 + 面板：列所有后台子 agent、状态、可进入查看某个的内部活动流。

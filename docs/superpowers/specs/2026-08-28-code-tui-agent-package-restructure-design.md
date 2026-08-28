# code-tui：agent 包按功能重构（设计）

日期：2026-08-28
状态：待实施

## 背景与目标

`springai-code-tui` 的 `io.github.javaside.springai.codetui.agent` 顶层堆了 **88 个类**，
从 provider 接入、MCP、子 agent、会话持久化、压缩策略、UI 接缝到工具装饰器全挤在一个平面上。
已有 4 个子包（`media` 28、`permission` 15、`background` 7、`thinking` 6）证明分包这条路走得通，
只是顶层一直没跟着分。

**目标**：把顶层 88 个类按功能拆进 10 个子包，顶层只留装配层。
**非目标**：不改任何运行时行为，不动 4 个既有子包，不重命名类，不拆分/合并类。
本次是纯粹的位置调整 + 必要的可见性放宽。

## 现状约束（调研结论）

三条硬约束决定了方案边界：

1. **`ModalRequest` 是 sealed interface**，permits `AskRequest`、`PermissionRequest`、`PlanRequest`。
   本项目无 `module-info`（unnamed module），permitted 子类型**必须与接口同包**。
   这解释了 `PermissionRequest` 今天为何不在 `agent/permission/`——重构后这个理由不变。
2. **顶层有 20 个包私有类型**，拆包后其中 10 个会跨包被引用，必须放宽可见性。
   另 10 个的调用方与它同包，继续保持包私有。
3. **测试侧 121 个文件在 `test/agent/` 顶层**，多个测试依赖包私有类型。
   测试按主类同步镜像移动后，绝大多数同包访问关系保持不变。
   仅一处纯由测试落点导致的可见性放宽：`StreamIdleTimeoutChatModel`（llm）
   唯一的跨包引用方是 `CodingAgentSubmitErrorTest`（落在 root）。

外部引用面：顶层 37 个类被 `agent` 包外引用，涉及 `ui/` 与 `CodeTuiApplication` 下 **45 个文件**
（含 main 与 test）；另有 3 个既有子包内的文件也引用了顶层类
（`background/BackgroundNotifier`、`media/LiveVisionEndToEndProbe`、`media/LiveVisionSequenceProbe`）。
`SubmitHandler` 被 34 个包外文件引用，是引用面最广的一个。这些引用全部只需改 import。

## 包划分

| 包 | 类数 | 职责 |
|---|---|---|
| `agent`（根） | 2 | `CodingAgent`、`AgentTools`——装配层，依赖所有子包 |
| `agent.llm` | 26 | provider 接入与 ChatModel 装饰 |
| `agent.mcp` | 7 | MCP 客户端、配置、传输 |
| `agent.subagent` | 5 | 子 agent 定义、装载、执行 |
| `agent.skill` | 3 | 技能目录与技能工具 |
| `agent.session` | 7 | 会话持久化与 token 记账 |
| `agent.compaction` | 7 | 上下文压缩策略与触发 |
| `agent.seam` | 17 | CodingAgent ↔ UI 的纯 Java 接缝 |
| `agent.tools` | 8 | ToolCallback 装饰链与内置工具 |
| `agent.prompt` | 3 | 系统提示片段加载 |
| `agent.interjection` | 3 | 回合中插话 |

合计 88，已核对与顶层实际文件集**完全一致**（无遗漏、无重复、无凭空新增）。

### 各包成员

**`agent`（根，2）**
`CodingAgent`、`AgentTools`

**`agent.llm`（26）**
`LlmProvider`、`ProviderRegistry`、`ProviderModel`、`ModelOption`、`ModelPreference`、`ModelListEnv`、
`AnthropicProvider`、`DeepSeekProvider`、`OpenAiProvider`、`OpencodeGoProvider`、`QwenProvider`、`ZhipuProvider`、
`DeepSeekThinkingBodyCodec`、`DeepSeekThinkingChatModel`、`DeepSeekThinkingChatOptions`、
`DeepSeekThinkingClientHttpConnector`、`QwenSseNormalizingHttpClient`、
`LlmTimeouts`、`OpenAiTimeouts`、`StreamIdleTimeoutProvider`、`StreamIdleTimeoutChatModel`、
`RetryingChatModel`、`UsageRecordingChatModel`、`UsageRecordingProvider`、`DynamicAuxChatModel`、
`SessionIdStreamGuardAdvisor`

26 个仍偏大，但内部全是「接入一家 provider」这一件事：6 家 provider 实现、
各自的专属编解码（DeepSeek thinking、Qwen SSE 归一）、超时口径、以及 5 个 ChatModel 装饰器。
再往下拆会把 provider 和它的专属编解码分开，反而更难读。

**`agent.mcp`（7）**
`McpRegistry`、`McpClientManager`、`McpConfigLoader`、`McpConfigWriter`、`McpTransportFactory`、
`McpServerConfig`、`EnvInterpolator`

`EnvInterpolator` 是通用的 `${VAR}` 插值工具，但唯一调用方是 `McpConfigLoader`
（给 `mcp.json` 的 headers 插值，让 token 留在环境变量里），故随调用方进 `mcp`。
将来若有第二个包要用它，再提到更中立的位置。

**`agent.subagent`（5）**
`SubagentRunner`、`SubagentLoader`、`SubagentSpec`、`SubagentTool`、`SubagentFailedException`

**`agent.skill`（3）**
`SkillCatalog`、`SkillInfo`、`ReloadableSkillTool`

**`agent.session`（7）**
`FileSessionRepository`、`SessionEvents`、`SessionIds`、`SessionTokenEstimator`、
`ContextStats`、`TokenUsageAccumulator`、`CacheUsageExtractor`

**`agent.compaction`（7）**
`BoundedSummarizationCompactionStrategy`、`NotifyingCompactionStrategy`、`PreflightCompactionAdvisor`、
`CompleteTokenCountTrigger`、`CalibrationState`、`SummarizerOverflow`、`ModelContextWindows`

**`agent.seam`（17）**
`AgentListener`、`SubmitHandler`、
`ModalRequest`、`AskRequest`、`AskResponder`、`PermissionRequest`、`PermissionResponder`、`PermissionOutcome`、
`PlanRequest`、`PlanResponder`、`PlanOutcome`、`QuestionSpec`、`OptionSpec`、
`UserQuestionBridge`、`PlanApprovalBridge`、`QuestionCancelledException`、`PermissionCancelledException`

**`agent.tools`（8）**
`PermissionCallback`、`ToolEventCallback`、`TimeLimitedToolCallback`、`RenamedToolCallback`、
`BochaWebSearchTool`、`TodoWriteToolAdapter`、`ResilientToolCallingManager`、
`ResilientToolExecutionExceptionProcessor`

**`agent.prompt`（3）**
`MemoryPrompt`、`ProjectInstructions`、`PermissionModePrompt`

**`agent.interjection`（3）**
`Interjections`、`InterjectionText`、`InterjectingChatModel`

## 关键取舍

**`PermissionRequest` 进 `seam` 而非 `permission`**——`ModalRequest` 的 sealed 约束强制同包。
这不是妥协，而是把今天「因为 sealed 所以留在顶层」的隐式理由，变成「明确属于 UI 接缝」的显式归属：
它本来就是一次需要抢占 UI 焦点的模态请求，与规则引擎无关。
`PermissionRequest` 只 import `permission.PermissionRule` 一个类型，方向是 seam → permission，无环。

**`PermissionCallback` 进 `tools` 而非 `permission`**——它是 ToolCallback 装饰链的最外层，
与 `ToolEventCallback`、`TimeLimitedToolCallback` 同类。放 `permission` 会让规则引擎包
反向依赖 `seam`（它要用 `PermissionRequest`/`PermissionResponder` 发起审批），
而 `permission` 现在是干净的纯规则包，不该被拖进 UI 接缝。

**`session` 与 `compaction` 分开**——两者都碰 token 估算，看似该合并。
但 `session` 是「持久化 + 记账」（`FileSessionRepository` 落盘、`TokenUsageAccumulator` 累加），
`compaction` 是「超限时怎么裁」（策略、触发器、校准）。
职责不同、变更节奏不同（压缩策略近月改过多轮，会话仓库稳定），分开更利于隔离改动。
`compaction` 依赖 `session.SessionTokenEstimator`，方向单一，无环。

## 可见性放宽

拆包后 **10 个包私有类型需升 `public`**（已剔除注释与字符串里的假匹配后精确核算）：

| 类 | 所属包 | 跨包引用方 |
|---|---|---|
| `BoundedSummarizationCompactionStrategy` | compaction | `AgentTools` |
| `CalibrationState` | compaction | `AgentTools` |
| `ModelContextWindows` | compaction | `AgentTools` |
| `PreflightCompactionAdvisor` | compaction | `AgentTools` |
| `SessionIdStreamGuardAdvisor` | llm | `AgentTools` |
| `InterjectingChatModel` | interjection | `AgentTools`、`CodingAgent`、`InterjectionHistoryTest` |
| `RetryingChatModel` | llm | `SubagentRunner` |
| `SessionEvents` | session | `CodingAgent`、`CodingAgentTrimTest` |
| `SessionTokenEstimator` | session | `CodingAgent`、`BoundedSummarizationCompactionStrategy`、`CompleteTokenCountTrigger`、`BoundedSummarizationCalibrationTest`、`ContextStatsTest` |
| `StreamIdleTimeoutChatModel` | llm | `CodingAgentSubmitErrorTest` |

每个升 public 的类，在类注释加一行：
`<p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。`

**另 10 个继续保持包私有**（调用方与它同包）：
`CompleteTokenCountTrigger`、`SummarizerOverflow`（→ compaction 内部）、
`DeepSeekThinkingBodyCodec`、`DeepSeekThinkingChatModel`、`DeepSeekThinkingChatOptions`、
`DeepSeekThinkingClientHttpConnector`、`QwenSseNormalizingHttpClient`、`OpenAiTimeouts`、`ModelListEnv`（→ llm 内部）、
`McpConfigWriter`（→ mcp 内部）。

`ModelListEnv` 值得单独说明：粗看 `AgentTools` 引用了它，实际只出现在一句注释里
（"与 `ModelListEnv.parse` 一样把 env 值作为参数传入"），故保持包私有。
`RetryingChatModel` 反之：注释里被 `AgentTools`、`InterjectingChatModel` 提及但并未使用，
真实引用只有 `SubagentRunner`。

## 测试迁移

121 个顶层测试文件按被测主类镜像移动，落点分布：
root 32、llm 29、tools 12、subagent 11、mcp 9、seam 9、compaction 6、session 5、interjection 3、prompt 3、skill 2。
（4 个既有子包下的 54 个测试文件原地不动。）

多数测试按类名前缀即可判定归属。以下 28 个名字不直接对应某个主类，按引用密度判定，
其中值得记录的几处：

- `ContextStatsTest` → **compaction**（不是 session）。它 245 行 17 个方法里，
  只有 2 个测 `ContextStats` 本身、2 个测 `SessionTokenEstimator`，其余 13 个测
  `ModelContextWindows`、`CompleteTokenCountTrigger`、`BoundedSummarizationCompactionStrategy`。
  文件名有误导性，但本次不改名（非目标），按实际内容归包。
- `BoundedSummarizationCalibrationTest` → compaction（引用密度 32:2）
- `PermissionWiringTest`、`AgentRuntimeTest`、`AgentMemoryToolsTest`、`AuxClientNotVisionWrappedTest`、
  `RuntimeToolSet` → root（测的是 `AgentTools` 整体装配）
- `ExitPlanModeToolTest`、`StubListener` → seam
- `McpWiringTestSupport` → llm（它造的是假 `ProviderRegistry`，与 MCP 无关，名字同样有误导性）
- `MidTurnInjectionTest` → interjection；`InterjectionHistoryTest` → root（引用 `CodingAgent` 为主）
- `QwenChunkMergerHypothesisTest`、`ToolNameProbeTest`、`PermissionTestSupport` 不引用任何被移动的主类，
  按语义分别归 llm、root、tools

6 个共享测试辅助类按被引用面归包，跨包引用的升 public：
`StubListener`（seam，被 22 个测试引用，须 public）、
`AgentListenerAdapter`（seam，仅被 seam 内两个测试引用，保持包私有）、
`RuntimeToolSet`（root，被 `permission/ToolRegistryCompletenessTest` 引用，须 public）、
`McpWiringTestSupport`（llm）、`PermissionTestSupport`（tools，仅被 tools 内测试引用，保持包私有）、
`CodingAgentBackgroundTestSupport`（root，已是 public，被 `ui/` 测试引用）。

121 个测试文件逐个的落点清单，随实施计划给出（每步只列该步涉及的那几个）。

## 实施顺序

按「先叶子后核心」分 10 步，每步一次提交，每步跑：

```bash
mvn -pl springai-code-tui -am test
```

全绿才提交下一步。叶子包先动，是因为出错影响面最小，某步红了可以单独回退不牵连后续。

1. `skill`（3）
2. `prompt`（3）
3. `mcp`（7）
4. `interjection`（3）
5. `session`（7）
6. `compaction`（7）
7. `subagent`（5）
8. `llm`（26）
9. `seam`（17）
10. `tools`（8）

收尾核对：顶层只剩 `CodingAgent`、`AgentTools` 两个文件。

每步的机械动作固定为四件：移动 main 文件并改 `package` 行 → 移动对应测试文件并改 `package` 行 →
给所有引用方补 import（含 `ui/`、`CodeTuiApplication`、4 个既有子包）→ 需要时升 public 并加注释。

## 验证

- 每步 `mvn -pl springai-code-tui -am test` 全绿（包含 4 个既有子包的 54 个测试）
- 真机冒烟测试无 key 时自动跳过，不算失败（沿用 `@EnabledIfEnvironmentVariable` 门控）
- 已知 flaky：`CodingAgentSpikeTest.todoTurnIdBinding` 走真实 DeepSeek、单回合 60s 上限，
  偶发超时。撞上时单跑确认，不要误判为重构改坏
- 末步额外确认：`git diff --stat` 里**没有任何 `.java` 的正文改动**，
  只有 `package` 行、`import` 行、可见性修饰符与新增的类注释行

## 影响范围

- 88 个 main 文件 + 121 个 test 文件改 `package` 行
- 45 个 agent 包外文件（`ui/`、`CodeTuiApplication`）+ 3 个既有子包内文件补 import
- 10 个类升 `public`，2 个测试辅助类升 `public`
- 无行为改动、无 API 语义改动、无依赖变化

## 文档处理

44 个 docs / CHANGELOG 文件里写了 `codetui.agent.xxx` 的类路径，重构后这些路径全部失效。
**不改这些历史文档**：它们描述的是当时的代码状态，改了反而失真（一份 2026-07 的 plan 说
"在 `agent/McpRegistry.java` 加方法"，把路径改成今天的位置会让读者以为当时就是这个结构）。
仅在 CHANGELOG 新增一条说明本次调包，作为历史文档与当前结构之间的索引。

# MCP 客户端接入 code-tui — 设计文档

- 日期：2026-07-12
- 模块：`springai-code-tui`
- 状态：已与用户对齐，待复审

## 1. 目标与背景

让 code-tui 能把外部 MCP（Model Context Protocol）server 暴露的工具「即插即用」地交给编码 Agent 调用（例如 `chrome-devtools-mcp`、官方 filesystem server）。

本项目基于 **Spring AI 2.0 但不依赖 spring-boot**，因此用不了 `spring-ai-starter-mcp-client` 那套自动配置（boot-demo 模块用的正是它）。需要在无 Spring 容器的前提下自行装配 MCP 客户端。

## 2. 关键前置结论（源码核实）

对 `spring-ai-mcp` 2.0.0 源码通读后的判断：

- **复用 `SyncMcpToolCallback`（适配器），不复用 `SyncMcpToolCallbackProvider`（当管理层）。**
  - `SyncMcpToolCallback implements ToolCallback`，同步 `call()` 内部 `mcpClient.callTool()` 直接返回 JSON 串，与本项目 100% 同步的工具路径零阻抗；schema/命名/「对模型报前缀名、对 server 发原始名」等易错细节它已处理好，值得复用。
  - `SyncMcpToolCallbackProvider.getToolCallbacks()` 用 `mcpClients.stream().flatMap(c -> c.listTools())`，**任一 server 的 `listTools()` 阻塞失败会带崩全部发现**（无 per-client 兜底），违反「连不上的 server 静默降级」；且它不管 client 生命周期、并假设活在 Spring 容器里（`implements ApplicationListener<McpToolsChangedEvent>`，本项目无 context 永不触发）。故不采纳它作管理层。
- **排除 Async 家族。** `AsyncMcpToolCallback.call()` 内部照样 `.block()`，在同步路径上无收益，只白拖 reactor 管道，严格更差。
- **单 `McpSyncClient` 对并行子 agent 并发安全。** `StdioClientTransport` 出站走单一 `outboundSink` → 单线程 scheduler → `synchronized(os)` 写，多线程 `sendMessage` 被串行化，响应靠 JSON-RPC id 多路复用。因此每个 server 共享一个 client 即可，无需连接池 / 每子 agent 建连。

## 3. 设计决策（已与用户确认）

| 决策 | 选择 | 理由 |
|---|---|---|
| 依赖策略 | 引 `spring-ai-mcp`，只用 `SyncMcpToolCallback`，自写 `McpClientManager` | 白嫖 battle-tested 适配层，自己扛生命周期/失败隔离/退出清理 |
| 传输方式 | 仅 stdio | 覆盖 npx/uvx 本地 server（90% 场景），生命周期最简 |
| 可用范围 | 主 + 子 agent 都可用 | 共享单 client，已验证并发安全 |
| 配置/生命周期 | 启动静态加载 + 优雅降级 + 退出清理；无运行期热管理 | YAGNI；会话内工具很少变 |
| 传输可扩展性 | **本期只实现 stdio，但传输层抽象成接缝**，SSE/Streamable 可零架构改动接入 | 见 §2 补充结论：三种传输同产出 `McpClientTransport`，且 HTTP 系已在 mcp-core、无新依赖 |

### 2bis. 传输可扩展性的源码依据（补充）

- `McpClient.sync(McpClientTransport)` 只吃抽象 `McpClientTransport`；stdio/SSE/Streamable 都是它的实现。故**连接、发现、调用、关闭全部与具体传输无关**，唯一分型点是「配置 → 构造哪种 transport」。
- **SSE 与 Streamable HTTP 的客户端传输已内置于 `mcp-core`**：`HttpClientSseClientTransport`、`HttpClientStreamableHttpTransport`，均基于 JDK `java.net.http.HttpClient`，**不需要 webflux/reactive 栈，也不引入新依赖**。
- 「单 client 对并行子 agent 并发安全」的结论对 HTTP 传输同样成立（甚至更自然：每请求独立 HTTP 往返，靠 JSON-RPC id 关联）。

## 4. 配置格式与来源

两层惯例（沿用 skills）：`~/.codetui/mcp.json`（用户级）+ `<root>/.codetui/mcp.json`（项目级），按 server 名合并，**项目级覆盖用户级同名项**。

schema 对齐 Claude Code 的 `mcpServers`，并**显式带 `type` 判别字段**（省略时默认 `"stdio"`，使当前配置保持简洁；将来 SSE/Streamable 靠它分型）：

```json
{
  "mcpServers": {
    "chrome-devtools": {
      "type": "stdio",
      "command": "npx",
      "args": ["chrome-devtools-mcp@latest"],
      "env": { "FOO": "bar" },
      "enabled": true
    }
  }
}
```

- **本期实现**：`type` 省略或 `"stdio"`。字段：`command`（必填）、`args`（可选，默认空）、`env`（可选）、`enabled`（可选，默认 true）、`timeoutMs`（可选，默认 20000，对齐 SDK request/init 默认）。
- **为扩展预留**（本期只解析+占位、不实现连接）：`type` 为 `"sse"` / `"streamable-http"` 时字段为 `url`（必填）、`headers`（可选，鉴权/自定义头）、`timeoutMs`；解析成对应 config 变体，但工厂遇到未实现分支时**记 WARN「该传输暂未支持」并跳过**（降级，不崩）。
- 文件缺失 / JSON 非法 / 单条缺必填字段 / 未知 `type` → 视为空或跳过该条，记一条 WARN 到日志文件，**绝不抛异常**（照 `SkillCatalog` 降级风格）。

## 5. 组件划分

各司一职、可独立测试：

| 组件 | 职责 | 依赖 |
|---|---|---|
| `McpServerConfig`（sealed 接口） | 单个 server 的不可变配置，按传输分型：`StdioServerConfig`（本期）+ 预留 `SseServerConfig` / `StreamableHttpServerConfig`；公共字段 name/enabled/timeoutMs | 无 |
| `McpConfigLoader` | 读两层文件 → 合并 → `List<McpServerConfig>`；按 `type` 反序列化到对应变体；缺失/非法降级为空 | Jackson（项目已用） |
| `McpTransportFactory` | **传输接缝**：`McpServerConfig → McpClientTransport`；本期只实现 stdio 分支，未实现分支 WARN + 返回空（降级） | mcp-core transports |
| `McpClientManager` | 生命周期核心：`connectAll()` / `toolCallbacks()` / `close()`；**只与抽象 `McpClientTransport` / `McpSyncClient` 打交道，传输无关** | mcp-core + `SyncMcpToolCallback` + `McpTransportFactory` |

**扩展点小结**：新增一种传输 = ①加一个 `McpServerConfig` 变体 + loader 反序列化分支；②在 `McpTransportFactory` 加一个分支（用 mcp-core 现成的 `HttpClientSseClientTransport` / `HttpClientStreamableHttpTransport`）。`McpClientManager`、发现、装饰、生命周期、退出清理**全部零改动**，且**无新依赖**。

### `McpClientManager` 内部

- **连接（`connectAll`）**：每个 server 先经 `McpTransportFactory` 拿到 `McpClientTransport`（stdio 分支即 `StdioClientTransport(ServerParameters)`；工厂返回空表示该传输未实现/构造失败 → 跳过），再用小线程池**并行**
  `McpClient.sync(transport).requestTimeout(t).initializationTimeout(t).build()` → `initialize()`。
  每个 server 各自 try/catch + 超时；失败只记一行日志并跳过（发现「一个坏全崩」问题靠此隔离，不用 Provider 的聚合 flatMap）。总启动延迟 ≈ 单个 init 超时，而非累加。
- **发现（`toolCallbacks`）**：对每个已连 client `listTools()`（同样逐 server guard），对每个 tool 造
  `SyncMcpToolCallback.builder().mcpClient(c).tool(t).prefixedToolName("mcp__<server>__<tool>").build()`。
  前缀 `mcp__<server>__` 避免与内置工具及多 server 间重名，并让 TUI 一眼看出出处。返回**未装饰**的 `List<ToolCallback>`。
- **关闭（`close`）**：逐个 `closeGracefully()`（带总超时兜底 2s），吞异常；dispose 内部线程池。

## 6. 接入现有装配（数据流）

关键约束：`AgentTools.build` 有明文不变量「仅装配、不发网络请求」。故连接+发现放在 build **之外**，在 TUI 接管终端**之前**跑完（与 `quietGitStatus` 同阶段），配「正在连接 MCP…」启动提示。

```
CodeTuiApplication 启动
  ├─ McpConfigLoader.load(root)   → List<McpServerConfig>
  ├─ McpClientManager.connectAll() → 建连接（并行/超时/降级）   ← 唯一阻塞 IPC
  ├─ manager.toolCallbacks()       → List<ToolCallback>（未装饰）
  └─ AgentTools.build(registry, root, listener, mcpTools)  ← build 新增入参
        ├─ 每个 mcpTool 包 ToolEventCallback（MCP 调用在 TUI 显示为一行工具活动）
        └─ 并入 decorated[]（= decoratedList）
              ├─ 主 agent 得到（在 toolsWithTask 里）
              └─ 子 agent 也得到（SubagentRunner 复用 decoratedList）  ← 范围=主+子
```

- 范围为「主+子」，故 MCP 工具进**共享的 `decorated` 列表**（不像长期记忆工具那样单独隔离给主 agent）。
- child 进程 stderr 由 SDK 送 errorSink → 日志文件，不落 stdout（日志已定向到文件，不撕裂 TUI）。

## 7. 退出清理（重点，有前科）

前科：`/exit` 曾被非 daemon 线程卡 ~60s。stdio = fork 子进程，必须清，但**绝不让清理反过来引入卡顿**：

- 在现有强制 `System.exit` **之前**插一步 `manager.close()`，但带**硬超时**（总 2s）：`closeGracefully` 超时就不等，直接进 `System.exit`——`System.exit` 会带走所有子进程与非 daemon 线程。
- 连接线程池与 SDK outbound scheduler 设为 daemon / 在 `close` 里 dispose。

## 8. 错误处理汇总

| 情形 | 处理 |
|---|---|
| `mcp.json` 缺失/非法 | 空、WARN、继续 |
| 单 server 连接/init 失败或超时 | 跳过该 server，记一行日志，其余照常 |
| 单 server `listTools` 失败 | 跳过该 server 工具，其余照常 |
| 工具调用抛错 | `SyncMcpToolCallback` 抛 `ToolExecutionException` → 经 `ToolEventCallback` 与既有工具框架一致地报失败事件 |
| 退出时 `closeGracefully` 超时 | 2s 后放弃等待，直接 `System.exit` |

## 9. 测试策略（遵守「测试须模块作用域」）

- **纯单元**：`McpConfigLoader` 解析 + 两层合并 + 缺失/非法/缺 command 降级。
- **命名/隔离单元**：`McpClientManager` 前缀生成 + 「一个 server 发现失败不影响其他」隔离逻辑（用假 client/桩驱动）。
- **集成（带 flag，模块作用域）**：起真实轻量 stdio echo/mock MCP server（照既有 pty 冒烟脚本套路，放 `src/test/resources/scripts/`），验证 连接 → 发现 → 调用一次 → `close` 不卡。
- **手动 pty 实机**：接真 `chrome-devtools-mcp` 或 filesystem server，确认工具出现、调用成功、`/exit` 秒退。
- 所有 mvn 命令用 `-pl springai-code-tui`。

## 10. 依赖改动

`springai-code-tui/pom.xml` 增 `org.springframework.ai:spring-ai-mcp`（走 spring-ai-bom 2.0.0，传递带入 `io.modelcontextprotocol.sdk:mcp-core`）。构建后核对 `mvn -pl springai-code-tui dependency:tree` 确认版本与传递依赖。

**扩展无新依赖**：SSE / Streamable HTTP 客户端传输（`HttpClientSseClientTransport` / `HttpClientStreamableHttpTransport`）已内置于 `mcp-core`、基于 JDK `HttpClient`，将来实现时不需再加任何依赖。

## 11. 影响的现有文件（预估）

- 新增：`agent/McpServerConfig.java`（sealed + `StdioServerConfig`）、`agent/McpConfigLoader.java`、`agent/McpTransportFactory.java`、`agent/McpClientManager.java`
- 改：`agent/AgentTools.java`（`build` 增 `List<ToolCallback> mcpTools` 入参、并入 decorated）
- 改：`CodeTuiApplication.java`（启动装配 manager、退出前 `close`）
- 改：`springai-code-tui/pom.xml`（加依赖）
- 新增测试 + `src/test/resources/scripts/` 下 mock server 脚本

## 12. 明确不做（YAGNI 边界）

- **本期不实现** SSE / streamable-HTTP 的连接逻辑（仅 stdio 真正落地）。但传输层已抽象成 `McpTransportFactory` 接缝、config 已按 `type` 分型、依赖已就位——将来接入是「加分支」而非「改架构」（见 §5 扩展点小结）。
- 不做运行期 `/mcp`、`/reload` 热管理与 tools/list_changed 动态刷新。
- 不复用 `SyncMcpToolCallbackProvider` 聚合、不引 Async 家族。
- 不给每个子 agent 建独立连接（共享单 client 已并发安全，跨传输均成立）。

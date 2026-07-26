# MCP 第二种传输：Streamable HTTP 接入 code-tui

> 当前 `McpServerConfig` 是 `sealed ... permits StdioServerConfig`，只能连本地子进程 server。
> 本期加 Streamable HTTP，使 code-tui 能连远程 MCP server；鉴权头支持 `${ENV_VAR}` 插值。

## 目标与范围

- **目标**：`mcp.json` 里写 `"type": "http"` + `url` + 可选 `headers`，即可连接远程 MCP server，
  其工具照常注入主 agent 与子 agent、照常受 `/mcp` 面板启停管理。
- **非目标**：
  - 不做 SSE 传输（旧标准、官方已 deprecated；仍保留「暂未支持」的 WARN 分支）
  - 不做 OAuth 授权流（SDK 有 `authorizationErrorHandler` 钩子，但完整 OAuth 属独立项目）
  - 不改 `McpClientManager` / `McpRegistry` / `/mcp` 面板——它们对传输类型无感

## 前提事实（已实测核准）

| 事实 | 证据 |
|---|---|
| `mcp-core 2.0.0` 自带 `HttpClientStreamableHttpTransport`，基于 JDK HttpClient | `unzip -l` 列出该类；**无需新增依赖** |
| 静态入口 `builder(String baseUri)`，另有 `endpoint(String)` / `connectTimeout(Duration)` / `httpRequestCustomizer(...)` | `javap` |
| 默认 endpoint 是 `/mcp` | 字节码常量 `DEFAULT_ENDPOINT` = `/mcp` |
| 鉴权头经 `McpSyncHttpClientRequestCustomizer.customize(HttpRequest.Builder, String method, URI, String body, McpTransportContext)` 注入 | `javap` |
| 扩展点早已留好：`McpTransportFactory.create` 是唯一分型点 | 该类 javadoc 明写「加新传输只需在此加分支，`McpClientManager` 与其余流程零改动」 |
| 现有 loader 硬编码 `if (!"stdio".equals(type)) 记 WARN 跳过` | `McpConfigLoader.parseEntry` |
| 测试目标 `https://mcp.context7.com/mcp` 可用、**无需鉴权** | `curl` → 200，`protocolVersion 2025-06-18`、`mcp-session-id` 响应头、`serverInfo: Context7 v3.2.5` |
| Context7 在 `initialize` 阶段**不校验 API key** | 故意传错误 key 仍回 200 + 完整 serverInfo |

## 设计定案

### 配置形状

```json
{
  "mcpServers": {
    "context7": {
      "type": "http",
      "url": "https://mcp.context7.com/mcp",
      "headers": { "Authorization": "Bearer ${CONTEXT7_API_KEY}" },
      "timeoutMs": 30000
    }
  }
}
```

`type` 接受 **`"http"` 与 `"streamable-http"` 两种拼写**：前者对齐 Claude Code 生态的写法，后者是规范全称。
`"sse"` 继续跳过，但 WARN 文案说明是「暂未支持」而非「未知类型」。

**不做「有 url 就推断为 http」的隐式推断**：现有语义是 `type` 缺省即 stdio，加隐式推断会让写错的
stdio 配置被误判成 http，报错方向全偏。

新增 sealed 变体：

```java
record HttpServerConfig(String name, boolean enabled, Duration timeoutMs,
                        String url, Map<String, String> headers) implements McpServerConfig
```

`timeoutMs` 复用既有字段（喂给 `connectTimeout`），不新增配置项。

### URL 拆分（correctness 关键）

SDK 是 `builder(baseUri)` + `endpoint(path)`，默认 endpoint `/mcp`。用户写完整 URL，我们拆：

| 用户写的 url | baseUri | endpoint |
|---|---|---|
| `https://h/mcp` | `https://h` | `/mcp` |
| `https://h/api/v1/mcp` | `https://h` | `/api/v1/mcp` |
| `https://h` 或 `https://h/` | `https://h` | 不调用，用 SDK 默认 `/mcp` |
| `https://h/mcp?x=1` | `https://h` | `/mcp?x=1` |

**不拆会错**：把整个 `https://h/mcp` 当 baseUri 传进去，SDK 再拼默认 `/mcp`，实际打到 `/mcp/mcp` → 404。

### 鉴权头与 `${ENV_VAR}` 插值

只对 headers 的**值**插值，key 不插；一个值里可以有多个 `${}`。

| 情形 | 行为 |
|---|---|
| 变量存在 | 替换 |
| 变量不存在 | **WARN + 跳过整个 server** |
| 值里没有 `${}` | 原样透传（字面值照常可用） |

「变量不存在就跳过」的理由：带着字面量 `${CONTEXT7_API_KEY}` 去请求只会拿到一个看不懂的
401/403，而启动时一句「server 'context7' 的 header Authorization 引用了未定义的环境变量
CONTEXT7_API_KEY，跳过」直接指到根因。这与既有「缺 command 就跳过」的降级契约同级。

插值在 **loader（解析期）** 而非 factory（连接期）：跳过属于「配置不合法」，与既有缺字段跳过一致。

**env 读取要可注入**：插值函数收一个 `Function<String, String>` 解析器，生产传 `System::getenv`、
测试传假 map（照 `ModelListEnv.parse` 把 env 值作参数传入的既有做法，测试才不依赖真实环境变量）。

### 传输构造

```java
if (config instanceof McpServerConfig.HttpServerConfig http) {
    URI uri = URI.create(http.url());
    var builder = HttpClientStreamableHttpTransport
            .builder(scheme + "://" + authority)
            .connectTimeout(http.timeoutMs())
            .jsonMapper(McpJsonDefaults.getMapper());
    if (path 非空且非 "/") builder.endpoint(path + 可选 query);
    if (!http.headers().isEmpty()) {
        builder.httpRequestCustomizer((requestBuilder, method, u, body, ctx) ->
                http.headers().forEach(requestBuilder::header));
    }
    return Optional.of(builder.build());
}
```

### 错误处理

**零新机制**，全部沿用既有降级契约（WARN + 跳过 / 失败态，绝不抛）：

| 情形 | 行为 |
|---|---|
| 缺 `url` | WARN 跳过（同 stdio 缺 command） |
| URL 非法 | WARN 跳过。**判定不能只靠 `URI.create` 抛异常**——`URI.create("foo")` 并不抛，它返回 scheme 与 authority 均为 null 的相对 URI，拿去构造 transport 会在连接期才炸出难懂的错。必须显式校验 scheme 非空且为 `http`/`https`、authority 非空 |
| headers 引用未定义变量 | WARN 跳过 |
| `type: "sse"` | WARN「暂未支持」跳过 |
| 连不上 / 超时 / 握手失败 | 既有路径：`McpClientManager` 记 WARN、进「连接失败」态，`/mcp` 面板可见可重试 |

## 测试策略

与 Brave 那次相反，这次**绝大部分可离线测**。

### 离线单测

| 测什么 | 要点 |
|---|---|
| loader 解析 http 条目 | `"http"` 与 `"streamable-http"` 两种拼写都认 |
| 缺 url / `type:"sse"` | 各自 WARN 跳过，不抛 |
| URL 非法 | 三种都要覆盖：`"foo"`（相对 URI，`URI.create` 不抛）、`"ftp://h/x"`（scheme 不对）、`"http://"`（无 authority） |
| 插值 | 变量存在→替换；缺失→跳过整个 server；无 `${}` →原样；一个值多个 `${}` |
| URL 拆分 | 上表四种形状逐个断言（这是最容易写错、也最难在真机上发现的一处） |
| **鉴权头真的落到请求上** | 直接构造我们的 customizer，调 `customize(HttpRequest.newBuilder(uri), "POST", uri, null, ctx)`，断言 built request 的 headers 里有该项 |
| factory 构造 | http 配置能得到非空 transport（不发网络） |

### 端到端冒烟

门控：`CODETUI_MCP_SMOKE_URL` 环境变量——设了才跑并连它指定的地址，不设则 skip
（Context7 无需 key，没有天然可绑的 key 变量；用专门的开关也便于指向别的 server）。

内容：连接 → `initialize` 握手 → 拉取 tools 列表非空。

> **这条冒烟不验证鉴权。** 实测 Context7 在 `initialize` 阶段不校验 API key（故意传错误 key 仍回 200
> 与完整 serverInfo），所以即使 headers 全部丢失它照样绿。鉴权头是否真的发出去，由上面那条离线
> customizer 单测负责。**不要**因为冒烟绿了就认为鉴权路径被覆盖。

验证命令：`mvn -pl springai-code-tui test`（必须模块作用域）。

## 文档改动

| 位置 | 改什么 |
|---|---|
| `README.md:14`（MCP 简介） | 「本期仅 **stdio**」改为 stdio + Streamable HTTP 两种 |
| `README.md` MCP 配置章节 | 加 http 类型的配置示例、`${ENV_VAR}` 插值说明、以及「变量缺失即跳过」的行为 |
| `README.md` 安全披露 | 远程 MCP 意味着**工具调用的入参会发往第三方服务端**，与本地 stdio server 性质不同 |
| `McpConfigLoader` 类 javadoc | 「仅 stdio 传输本期落地」这句已失效 |
| `McpTransportFactory` 类 javadoc | 「将来接入 SSE / Streamable HTTP」改为「SSE 仍未实现」 |

## 参考

- 测试目标：[Context7](https://context7.com/)（Upstash 项目），MCP 端点 `https://mcp.context7.com/mcp`
- SDK：`io.modelcontextprotocol.sdk:mcp-core:2.0.0`
- 前序设计：MCP 首次接入见 `McpTransportFactory` 类 javadoc 所指的「设计文档 §5 扩展点」

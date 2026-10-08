# 第三家搜索后端（智谱 BigModel）接入 code-tui

> 在已有的博查（Bocha）、Brave 两家搜索工具旁，再接智谱开放平台的 Web Search API，三家共存。
> 形状照 `BochaWebSearchTool` 先例自研 REST 工具（不引官方 SDK）；系统提示指引段从四态硬编码
> 重构为按注册状态动态拼装（三家已是八态，硬编码不可维护，用户已核准）。

## 目标与范围

- **目标**：配了 `ZHIPU_API_KEY` 后，模型多一个 `ZhipuWebSearch` 工具；与博查同为国内向搜索源
  （查询词不出境），互为冗余——本次接入的直接动因之一就是实测中博查、智谱两家的搜索额度
  先后耗尽，单一国内源没有兜底。
- **非目标**：
  - 不做 `SearchProvider` 抽象层（三家共存而非分派，前两家 spec 已两次否决，理由未变）。
  - 不做故障转移 / 自动重试（与既有「不重试」决策冲突：失败绝大多数是计费/限流问题，重试烧钱）。
  - 不接智谱 Chat API 的 `tools=[{type:"web_search"}]` 内建联网——只对特定 provider 生效、
    搜索过程对 TUI 不可见（博查 spec 已否决过同类方案，理由未变）。
  - 不走智谱 MCP broker（SSE 外挂）——描述由 server 决定改不动，且引入 MCP 依赖。
  - 不改子 agent frontmatter（`general-purpose` 经既有 `disallowedTools` 机制自动获得）。

## 前提事实

### 实测（2026-10-08，账户余额不足，故只拿到错误路径）

| 事实 | 证据 |
|---|---|
| 端点 `POST https://open.bigmodel.cn/api/paas/v4/web_search` 国内直连可达 | curl 各次 0.22s 内响应 |
| 错误响应统一形状 `{"error":{"code":"...","message":"..."}}` | 实测 3 档，见下 |
| 缺 `search_engine` → `{"error":{"code":"1214","message":"search_engine:The search_engine cannot both be empty."}}` | curl 实测；**参数校验先于余额校验**，故余额不足也能核准参数语义 |
| 无 Authorization 头 → `{"error":{"code":"1001","message":"Header中未收到Authorization参数，无法进行身份验证。"}}` | curl 实测 |
| 余额不足 → **HTTP 429** + `{"error":{"code":"1113","message":"余额不足或无可用资源包,请充值。"}}` | `curl -w 'HTTP %{http_code}'` 实测。注意智谱把余额不足映射为 429 而非 403（博查是 403），错误转译不能只认状态码，必须带出 body 原文 |
| 200 成功响应形状 | **未实测**（余额不足），以官方 API 参考为准，见下节；真机冒烟待账户充值后验证 |

### 官方文档（[Web Search API 参考](https://docs.bigmodel.cn/api-reference/%E5%B7%A5%E5%85%B7-api/%E7%BD%91%E7%BB%9C%E6%90%9C%E7%B4%A2)，未逐条实测）

| 项 | 值 |
|---|---|
| 鉴权 | `Authorization: Bearer <key>`（与智谱 LLM 同一个 key） |
| `search_engine` | **必填**枚举：`search_std`（基础版 0.01 元/次）/ `search_pro`（高阶版 0.03）/ `search_pro_sogou`（0.05）/ `search_pro_quark`（0.05）。sogou 档 `count` 仅可 10/20/30/40/50 |
| `search_query` | 必填，建议 ≤70 字符 |
| `count` | 1–50，默认 10。**坑：同时指定 `search_domain_filter` 与 `search_recency_filter` 时 count 不生效** |
| `search_domain_filter` | string，白名单域名；文档仅单域名示例（`www.sohu.com`），**多域名分隔符未写明** |
| `search_recency_filter` | `oneDay`/`oneWeek`/`oneMonth`/`oneYear`/`noLimit`（默认），**值域与博查 freshness 完全一致** |
| `content_size` | `medium`（摘要）/ `high`（详尽），默认 medium |
| 响应 | `search_result[]`：`title`/`link`/`content`(摘要)/`media`(来源名)/`icon`/`publish_date`/`refer`；另有 `search_intent[]`（`intent`/`keywords`/`query`，仅 `search_intent:true` 时有内容） |

### 多域名分隔符（设计输入，未定）

实现按**逗号连接**（`a.com,b.com`，HTTP 参数最常见约定），代码注释标注「未实测，冒烟验证后回填」。
真机冒烟含双域名用例，充值后跑通即回填此节。

## 设计定案

### 组件：`ZhipuWebSearchTool`（`agent/tools` 包，与博查同包）

职责单一：HTTP 调智谱 → 解析 → 渲染 Markdown。形状照 `BochaWebSearchTool`：私有构造 +
`builder(apiKey)`，`.resultCount(n)` / `.searchEngine(s)` / `.baseUrl(url)`（baseUrl 仅测试指向
本地 stub server，不暴露 env）。

淘汰的备选：

- **官方 Java SDK（`ai.z.openapi:zai-sdk`）**：为一个 POST 引整包 SDK + 传递依赖；错误转译、
  超时、代理、stub 测试全部失控。博查先例（手写 RestClient）已验证这条路可控。
- **MCP broker（`/api/mcp-broker/proxy/web-search/mcp`）**：工具描述由 server 决定，没法写
  「先搜索再 webFetch」的协同指引（博查 spec 否决 MCP 的理由，未变）。

| 决策点 | 定案 |
|---|---|
| HTTP 客户端 | `RestClient` + `JdkClientHttpRequestFactory`，connect 10s / read 20s + `ProxySelector.getDefault()`（与博查同款：规避 HttpURLConnection 流式请求体 + 401 丢 error stream 的坑） |
| 超时来源 | 不接 `LlmTimeouts`（LLM 语义超时不适用于一次性 REST，博查 spec 已定案） |
| 注册名 | `@Tool(name = "ZhipuWebSearch")`，对称命名 |
| 错误处理 | 状态码 + body 原文透传（见「错误处理」节） |

### 暴露给模型的参数

| 参数 | 类型 | 映射到智谱 |
|---|---|---|
| `query` | `String` 必填 | `search_query` |
| `freshness` | `String` 可选 | `search_recency_filter`，默认 `noLimit`，不做本地校验原样透传（照博查 freshness 哲学：枚举可能随 API 演进，白名单只会造成假失败） |
| `include` | `List<String>` 可选 | `search_domain_filter`，逗号连接 |

不暴露给模型（计费/调优项，模型无额度概念）：`count`、`search_engine`、`content_size`（恒
medium，需要细节走 webFetch，与博查「summary 换 snippet」同级的定位决策）。

工具描述必须写进的两条 API 坑（否则模型必然踩）：

1. `include` 与 `freshness` **同时传时 count 失效**——描述建议「一般别同时传」。
2. freshness 与博查同款反直觉建议：默认 `noLimit` 最好，硬指时间范围容易搜空。

### 返回给模型的文本

`search_result[]` 逐条渲染，格式与博查输出对齐（模型已习惯这种形状）：

```
搜索「Spring AI 2.0 工具调用」找到 8 条结果：

1. Tool Calling :: Spring AI Reference — docs.spring.io · 2026-03-12
   https://docs.spring.io/spring-ai/reference/api/tools.html
   Spring AI 2.0 中工具调用由 ToolCallingAdvisor 自动注册……
```

字段映射：`title`→标题、`link`→URL、`media`→来源名、`publish_date`→日期（截到天）、
`content`→摘要。降级：任一字段缺失省略该段，不留空占位（照博查）。

### env

| env | 语义 |
|---|---|
| `ZHIPU_API_KEY` | **复用**（与 GLM 模型同一个 key）。非空即注册工具；为空则工具不存在、指引段无智谱条目。用户已核准：与 Bocha/Brave「配 key 即启用」语义一致 |
| `ZHIPU_SEARCH_COUNT` | 默认 **8**（对齐博查），钳 `[1, 50]`；缺失/非法回退默认（照 `resolveBraveResultCount` 形状） |
| `ZHIPU_SEARCH_ENGINE` | 默认 `search_std`（最便宜档）；**本地白名单**四枚举值，非法/缺失回退 std。与博查 freshness「透传」哲学不同——理由：freshness 写错只是搜索失败，引擎写错可能默默选错计费档（5 倍价差），计费项必须本地拦 |

### 系统提示指引段：重构为动态拼装（用户已核准）

现状 `webSearchGuide(boolean bocha, boolean brave)` 四态硬编码；加智谱变八态，硬编码 +
八份测试不可维护。重构为**条目拼装**：

- 签名 `webSearchGuide(boolean bocha, boolean zhipu, boolean brave)`（三布尔保持调用方可读；
  内部不是 if 八分支，而是「条目列表」驱动，第四家只需加一个条目方法）。
- 结构：总起句（需外部信息先搜索，仅任一家注册时出现）→ 各家一行条目（按 博查 → 智谱 →
  Brave 固定顺序，未注册不出现）→ 分工句（≥2 家时出现：中文/国内走博查或智谱、英文走 Brave）→
  公共尾（webFetch 分工 + Sources 要求）。
- 硬约束不变：正文**无花括号**（param 值注入，ST 渲染会炸）；零家注册返回空串。
- **分工归属原则**：跨工具分工只写在指引段（按注册状态渲染，绝不提不存在的工具）；工具自身
  描述只写自家能力与用法。既有 Brave 描述里「中文请改用 BochaWebSearch」在博查未注册时会指向
  不存在的工具，属既有债务，本次不扩大（智谱描述不写跨家指引）、不顺手修（改 Brave 描述会牵动
  其描述断言测试，混入本次提交违背「一次提交做一件事」）。

### 在 AgentTools 里的落位

```
ZHIPU_API_KEY 非空 ──→ createZhipuWebSearchTool(key, countEnv, engineEnv)
                          ↓ ZhipuWebSearchTool（@Tool 对象 → 进 rawTools，与博查同路）
                        既有装饰链 MediaExternalizing + ToolEventCallback
                          ↓
                        decorated[] ──→ 主 agent + 子 agent（general-purpose 自动获得）
ZHIPU_API_KEY 为空 ──→ 不注册，指引段无智谱条目
```

`resolveZhipuResultCount` / `resolveZhipuSearchEngine` 放 `AgentTools`（照
`resolveBraveResultCount` 形状），env 读取在 `build`，解析语义纯函数可测。

### 权限登记

`ToolRegistry` 加一行：`ZhipuWebSearch → NETWORK_READ / "query"`（与两家同款）。
`ToolRegistryCompletenessTest` 对运行时工具集全量比对，漏登记会直接红。

## 错误处理

| 情形 | 行为 |
|---|---|
| 无 `ZHIPU_API_KEY` | 不注册工具，指引段无智谱条目 |
| 空 query | 直接返回提示文本，**不发请求**（省额度，照博查） |
| 0 结果 | 返回可读文本「没搜到，建议换关键词或去掉时间限制」，`ok=true`，不是错误 |
| HTTP 错误（含 429 余额不足） | 抛 `IllegalStateException`，消息带状态码 + body 原文（截断预览）。**不解析 error.code 做分支**——智谱错误码无公开完整表，透传原文比猜语义可靠；实测 429+1113（余额）、1214（参数）、1001（鉴权）三档原文已足够人读 |
| 连不上 / 超时 | 抛，消息区分「连不上」与「响应无法解析」（照博查：疑似代理拦截页单独一档） |
| 响应形状不对（缺 `search_result`） | 抛，消息带响应前若干字符 |

不做：**不重试**（计费/限流问题重试只烧钱）、**不塌错误**（状态码 + 原文必须透传）。

## 测试策略

**离线单测（默认跑，无需 key）**，本地 stub HTTP server（照 `BochaWebSearchToolTest` 模式）：

1. 解析正确性——输出含标题 / URL / 摘要 / 来源名 / 日期
2. 字段缺失降级——`media`/`publish_date` 缺失不留空段
3. `ZHIPU_SEARCH_COUNT` 钳制——非法回退 8，越界钳 `[1, 50]`
4. `ZHIPU_SEARCH_ENGINE` 白名单——非法回退 `search_std`，四枚举值全过
5. `include` 拼接——`List` → `a.com,b.com`（逗号，标注未实测）
6. 空 query 短路——stub server 断言收到**零**请求
7. 0 结果 → 返回文本含提示且**不抛**
8. 4xx/429 → 抛异常且消息含状态码与 body 原文
9. 注册门控——有/无 key 时 `AgentTools` 工具列表含/不含 `ZhipuWebSearch`
10. 注册名断言 = `ZhipuWebSearch`（子 agent allow/deny 按注册名精确匹配，写错静默失效）
11. 指引段——零家空串；仅智谱时含 `ZhipuWebSearch` 且不含另两家；三家时含分工句；所有组合无花括号

**webSearchGuide 既有测试改造**：四态断言改为「关键内容存在性」断言（工具名出现与否、
分工关键词、Sources、webFetch），从「全文等于某硬编码串」改为结构断言，后续加家不再翻倍。

**真机冒烟**：`@EnabledIfEnvironmentVariable(named = "ZHIPU_API_KEY")`，含双域名用例（回填
分隔符）。**余额不足特判**：当前账户 1113 余额不足会让冒烟红而非 skip——门控只认变量存在。
用 JUnit `Assumptions` 对「余额不足」错误显式跳过（断言消息写明原因），环境态不是代码错。

**验证命令**：`mvn -pl springai-code-tui -am test`（必须带 `-am` 与模块作用域，整仓 `mvn test`
被空 demo 模块打挂）；单类追加 `-Dsurefire.failIfNoSpecifiedTests=false`。

## 文档改动

| 位置 | 改什么 |
|---|---|
| `src/package/bin/config.env.example` | 搜索段落改「三家」：加 `ZHIPU_SEARCH_COUNT` / `ZHIPU_SEARCH_ENGINE`；`ZHIPU_API_KEY` 在大模型段落已有，搜索段注明「复用」 |
| `README.md` 工具清单 | 加 `ZhipuWebSearch`（需 `ZHIPU_API_KEY`，按次计费 0.01–0.05 元） |
| `README.md` 安全披露 | 第三条对外出网通道：查询词发给智谱（国内服务，不出境，与博查同性质、与 Brave 不同）；**按次计费**是新维度——Brave 的免费档思维不适用于智谱（博查同样按量计费） |
| `docs/implementation-map.md` | 搜索工具一节加智谱 |
| `docs/guide/permissions.md` / `docs/guide/security.md` | 工具清单与出网通道补智谱 |
| `AgentTools` 类 javadoc / 行内注释 | 工具计数与条件注册说明补智谱 |

## 已知取舍

1. **200 响应形状未实测**（账户余额不足）：解析按官方文档示例实现，stub 单测即按文档样例
   造数据；充值后跑冒烟回填「前提事实」，若实际形状有出入在此更正。风险敞口：文档示例
   缺字段（如 `publish_date` 的真实格式未知，代码按宽松字符串处理）。
2. **多域名分隔符按逗号实现**：未实测依据，冒烟双域名用例验证后回填；若智谱只支持单域名，
   降级为「取第一个域名」并在此记录。
3. **`content_size` 恒 medium 不给 env**：high 档信息量大但占上下文，需要细节本就该走
   webFetch 抓原文（工具分工如此设计）。真有需求再加 env，十分钟的事。

## 参考

- [智谱 Web Search API 参考](https://docs.bigmodel.cn/api-reference/%E5%B7%A5%E5%85%B7-api/%E7%BD%91%E7%BB%9C%E6%90%9C%E7%B4%A2)
- [智谱联网搜索指南](https://docs.bigmodel.cn/cn/guide/tools/web-search)
- 前序设计：`2026-07-26-web-search-design.md`（博查）、`2026-07-26-brave-search-design.md`（Brave）

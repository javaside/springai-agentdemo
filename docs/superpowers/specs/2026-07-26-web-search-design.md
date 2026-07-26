# 网络搜索（WebSearch）接入 code-tui

> 给 code-tui 增加联网搜索能力：新增一个 `BochaWebSearchTool`（博查 Web Search API），
> 按 `BOCHA_API_KEY` 门控注册进既有工具链。补上「找 URL」这一步——抓正文那半边（`webFetch`）已经有了。

## 目标与范围

- **目标**：配了 `BOCHA_API_KEY` 后，模型能自主调用 `WebSearch` 工具搜索互联网，拿到标题 / URL /
  长摘要 / 站点 / 发布时间；需要原文细节时再用已有的 `webFetch` 抓那个 URL。
- **场景**：用户用自然语言说「搜一下 xxx」或模型自行判断需要项目外的最新信息（库用法、报错含义、
  新版本变更等），由模型决定调不调。
- **非目标**：
  - 不做 `/search` 斜杠命令，不做运行期开关（用户已确认「只要工具」）。
  - 不做多搜索后端抽象（`SearchProvider` 注册表）——只有博查一家消费者。
  - 不改四个内置子 agent 的 frontmatter（见「已知取舍」）。
  - 不复用 provider 内建联网（千问 `enable_search` / 智谱 `web_search`）——只对特定 provider
    生效，切到 DeepSeek/Anthropic 就没有，且搜索过程不是工具调用、对 TUI 不可见。

## 选型：独立 `@Tool` 类 + env 门控（方案 A）

新写一个 `@Tool` 类，形状照库里的 `BraveWebSearchTool`（builder 持 key、方法返回格式化文本），
注册进 `AgentTools` 既有装饰链。完全长在现有骨架上：工具活动经 `ToolEventCallback` 自动显示成
一行、子 agent 白拿、门控语义与 provider 的 `available()` 一致。

淘汰的备选：

- **复用库里现成的 `BraveWebSearchTool`**：`api.search.brave.com` 国内直连大概率不通，库里没有
  代理配置口子；且其 `@Tool` 描述写死了「Claude」和「Web search is only available in the US」，
  两条都不适用（描述可用现有 `describedAs()` 换掉，但网络可达性无解）。
- **抽象出 `SearchProvider` 注册表**（对齐 `LlmProvider` / `ProviderRegistry`）：抽象层现在没有
  第二个消费者。真要加第二家时，从方案 A 提取抽象是十分钟的事。YAGNI。
- **走 MCP 外挂博查 server**：MCP 工具的描述由 server 决定、改不动，没法在描述里写「先 WebSearch
  再 webFetch」这条与本项目 `webFetch` 的协同规则；且 `McpServerConfig` 目前 sealed 只允许
  stdio，要依赖 node 环境。

## 博查 API 事实（实现依据）

| 项 | 值 |
|---|---|
| 端点 | `POST https://api.bochaai.com/v1/web-search` |
| 鉴权 | `Authorization: Bearer <key>` + `Content-Type: application/json` |
| 入参 | `query`(必填) · `freshness`(默认 `noLimit`，可 `oneDay`/`oneWeek`/`oneMonth`/`oneYear` 或 `YYYY-MM-DD..YYYY-MM-DD`) · `summary`(bool，默认 false) · `count`(1–50，默认 10) · `include`(域名白名单，`\|` 或 `,` 分隔，≤100) · `page` |
| 响应 | `_type: "SearchResponse"` → `webPages.value[]`：`name`(标题) `url` `snippet`(短片段，恒有) `summary`(长摘要，仅 `summary:true` 返回) `siteName` `siteIcon` `datePublished`(ISO 8601 带时区，部分为空) |

官方一条反直觉建议：**推荐 `freshness` 用 `noLimit`**，其算法会自动改写时间范围；硬指区间反而
容易出现「范围内没有相关网页」而搜空。这条要写进工具描述，否则模型会习惯性乱加时间限制。

## 设计定案

### 组件

`BochaWebSearchTool`（`io.github.javaside.springai.codetui.agent` 包，单类不另开子包）。
职责单一：HTTP 调博查 → 解析 → 格式化 Markdown。不碰 LLM、不碰文件系统。

| 决策点 | 定案 |
|---|---|
| 注册名 | `@Tool(name = "WebSearch")` |
| 构造 | 私有构造 + `builder(apiKey)`，链式 `.resultCount(n)` / `.baseUrl(url)` |
| `baseUrl` | 只给 builder，**不给 env**——唯一用途是测试打本地 stub server |
| 门控 | `BOCHA_API_KEY` 非空才注册；为空则工具不存在、系统提示指引段为空串 |
| HTTP 客户端 | Spring `RestClient`，类内常量超时 connect 10s / read 20s |
| 超时来源 | **不接 `LlmTimeouts`**——那套是 LLM 语义（read 默认 300s，等的是流式块间隔）；搜索是一次性 REST 调用，超 20s 就该失败，套用等于挂死 |
| `summary` | 恒为 `true` |
| 重试 | 不做（见「错误处理」） |

### 暴露给模型的参数

`-parameters` 本模块已开（`springai-code-tui/pom.xml:109`），参数名可用。

| 参数 | 类型 | 说明 |
|---|---|---|
| `query` | `String`，必填 | 搜索词 |
| `freshness` | `String`，可选 | 默认 `noLimit`。工具描述明写：仅在明确需要时效性时才传，一般调研别动 |
| `include` | `List<String>`，可选 | 域名白名单，内部拼成 `a.com\|b.com`（≤100 个） |

`count` **不暴露给模型**——模型不知道用户的搜索额度余额，乱开 `count` 直接烧钱。改由
`BOCHA_SEARCH_COUNT` env 控制，默认 8，钳制到 `[1, 50]`（照 `AgentTools.resolveSubagentConcurrency`
的既有写法：非法/缺失回退默认，越界钳制）。`page` 不暴露。

两处入参边界（避免实现时二次拍脑袋）：

- `include` 超过 100 个域名时**截断取前 100**，不报错——这是博查侧的上限，截断比让整次搜索失败合理。
- `freshness` **不做本地校验**，原样透传给博查。枚举值可能随其 API 演进，本地白名单只会造成
  「API 支持而工具拒收」的假失败；非法值由博查自己返回错误，走既有 4xx 路径。

### 返回给模型的文本

`webPages.value[]` 逐条渲染：

```
搜索「Spring AI 2.0 工具调用」找到 8 条结果：

1. Tool Calling :: Spring AI Reference — docs.spring.io · 2026-03-12
   https://docs.spring.io/spring-ai/reference/api/tools.html
   Spring AI 2.0 中工具调用由 ToolCallingAdvisor 自动注册……

2. ...
```

降级规则：`summary` 缺失退回 `snippet`；`datePublished` 缺失则省掉该段，不留空占位。

### 在 AgentTools 里的落位

改动全部集中在 `AgentTools.build` 一个方法内：

```
BOCHA_API_KEY 非空 ──→ 建 tool ──→ 追加进 all 列表（webFetch 旁边）
                                    ↓
                          既有装饰链 MediaExternalizing + ToolEventCallback
                                    ↓
                          decorated[] ──┬─→ 主 agent toolsWithTask
                                        └─→ SubagentRunner 的 decoratedList
BOCHA_API_KEY 为空 ──→ 不注册，系统提示指引段渲染为空串
```

### 单次调用数据流

```
模型 tool_call
  → ToolEventCallback.call(turnId)   → onToolStarted → TUI 显示一行 "WebSearch(...)"
  → MediaExternalizingCallback       → 透传（纯文本结果，不触发媒体外置）
  → BochaWebSearchTool.webSearch     → POST api.bochaai.com → 解析 → Markdown
  → onToolFinished(ok=true)          → tool_result 入会话事件
```

### 系统提示改动

`SYSTEM_TEMPLATE` 增加 `{WEB_SEARCH_GUIDE}` 占位符，照 `AUTO_MEMORY` / `PROJECT_INSTRUCTIONS`
的既有做法**作 param 值注入**（不是字符串拼模板——正文里带花括号会炸 ST 渲染）。无 key 时注入
空串，模型看不到任何搜索相关指引。

段落内容三条：
1. 需要项目外的信息时，先用 `WebSearch` 拿标题 / URL / 摘要；需要原文细节再拿 URL 走 `webFetch`。
2. `freshness` 一般别动，只在明确要最新消息时才传。
3. 引用了搜索结果，就在回答末尾列出 Sources（markdown 链接）。

原有那条 `webFetch` 说明保持不动，条件逻辑只集中在新增的注入段落里。

### 会话记忆

搜索结果是纯文本 tool 结果，按现行策略（**文本正文永不外置**，只清媒体/二进制字节）永久留在
会话里，由 400k 阈值的滚动摘要正常吸收。**不给它加任何外置逻辑**——这条明写，因为搜索结果看起来
像「大块外部文本」，很容易被后来者误加外置。

## 错误处理

| 情形 | 行为 |
|---|---|
| 无 `BOCHA_API_KEY` | 不注册工具 + 指引段为空串。模型不知道有这回事，不会去调不存在的工具 |
| 空 query | 直接返回提示文本，**不发请求**（省额度） |
| 0 结果 | 返回可读文本「没搜到，建议换关键词或去掉 freshness」，`ok=true`。正常结果，不是错误 |
| 4xx（401 key 无效 / 403 余额不足 / 429 限流） | 抛 `IllegalStateException`，消息**带状态码和博查返回的 error 原文** → `ToolEventCallback` 标 `ok=false` → TUI 红行 |
| 5xx / 连接失败 / 超时 | 同样抛，消息区分「连不上」与「服务端错误」 |
| 响应形状不对（非 `SearchResponse` / 缺 `webPages`） | 抛，消息带响应前若干字符便于排查 |

两条明确的**不做**：

- **不塌错误**：状态码和博查返回的 error 原文必须透传，不能统一成「搜索失败」。踩过的坑：
  错误被塌成网络失败后根本无法定位是 key 无效还是余额不足。
- **不重试**：失败绝大多数是 key / 额度 / 限流问题，重试只烧额度并拖长回合；模型自己会换词再试。

## 已知取舍

**子 agent 只有 `general-purpose` 拿得到 WebSearch。** 子 agent 按 `spec.allowTools()`
**注册名精确匹配**过滤（`SubagentRunner.java:283`）。现有 frontmatter：

| 子 agent | frontmatter | 是否拿到 WebSearch |
|---|---|---|
| `explore` | `tools: Read, Grep, Glob` | 否 |
| `plan` | `tools: Read, Grep, Glob` | 否 |
| `bash` | `tools: Bash, BashOutput, KillShell` | 否 |
| `general-purpose` | `disallowedTools: AskUserQuestionTool` | **是** |

本期不动这四个文件。`plan` 做方案时查外部文档确有价值，但它现在连 `webFetch` 都没有，单开
WebSearch 会造出「搜得到 URL 却读不了正文」的残缺组合；要开就得连 `webFetch` 一起开，那是独立
的一次子 agent 能力调整，不属于本期范围。

## 测试策略

**离线单测（默认跑，无需 key）**，用本地 stub HTTP server 喂样例 JSON：

1. 解析正确性 —— 输出含标题 / URL / 摘要 / 站点 / 时间
2. `summary` 缺失退回 `snippet`
3. `datePublished` 缺失不留空段
4. `count` 钳制 —— 非法值回退 8，越界钳到 `[1, 50]`
5. `include` 拼接 —— `List` → `a.com|b.com`
6. 空 query 短路 —— stub server 断言收到**零**请求
7. 0 结果 → 返回文本含提示且**不抛**
8. 4xx / 5xx → 抛异常且消息含状态码
9. **注册门控** —— 有 / 无 key 时 `AgentTools` 的工具名列表含 / 不含 `WebSearch`
10. **注册名断言 = `WebSearch`** —— 单独立一个用例。踩过的坑：子 agent 的 allow/deny 按注册名
    精确匹配，而注册名取 `@Tool` 注解而非方法名，写错了过滤会静默失效
11. 无 key 时系统提示的指引段为空串

**env 怎么进测试**：`System.getenv` 改不了，照 `ModelListEnv.parse(modelsEnv, ...)` 的既有做法
——`AgentTools` 负责读 env，工具 builder 只收显式值，测试直接调 builder；注册门控那条走包级测试
钩子（照现有 `AgentTools.buildMemoryTools` / `askToolNamesForTest` 的写法）。

**真实 API 冒烟**：一个 `@EnabledIfEnvironmentVariable(named = "BOCHA_API_KEY")` 的用例，真发
一次搜索、断言至少一条结果带 URL。有 key 自动跑、无 key 优雅跳过，与 `CodingAgentSpikeTest`
同一套门控模式。

**验证命令**：`mvn -pl springai-code-tui test`。必须模块作用域——整仓 `mvn test` 会被几个空模块
打挂，且别用 `failIfNoSpecifiedTests=false` 盖问题。

## 文档改动

新增两个 env（`BOCHA_API_KEY` 决定是否启用、`BOCHA_SEARCH_COUNT` 可选默认 8），需同步五处：

| 位置 | 改什么 |
|---|---|
| `src/package/bin/config.env.example` | 加两个 env 及说明（无 key 即不启用搜索） |
| `README.md:11`（智能体工具清单） | 工具列表加 `WebSearch`（博查，需 `BOCHA_API_KEY`） |
| `README.md:30`（安全披露·联网出口无过滤） | 现文只提 `SmartWebFetchTool`。新增 `WebSearch` 是**第二条对外出网通道**，且会把用户的查询词发给第三方搜索服务——必须一并披露，不能让这节的风险清单失真 |
| `AgentTools.java:41`（类 javadoc） | 「6 个社区工具（FileSystem/Shell/Grep/Glob/TodoWrite/SmartWebFetch）」计数与枚举会失效 |
| `AgentTools.java:206`（行内注释） | 「7 个 @Tool 对象转 ToolCallback」计数会失效；且 WebSearch 是**条件注册**，注释需说明该计数随 key 变化 |

## 参考

- [博查 AI 开放平台](https://open.bochaai.com/)
- [博查 Web Search API 参数与响应字段](https://blog.csdn.net/Alexinyu/article/details/146242079)
- [博查 API References（Zenlayer 文档镜像）](https://docs.console.zenlayer.com/api/cn/compute/mcpg/web-search/bochaai)

# 第二家搜索后端（Brave）接入 code-tui

> 在已有的博查（Bocha）搜索工具旁，再接一个 Brave 搜索工具，两家**共存**、由模型按内容语言自选。
> Brave 侧**直接复用** `spring-ai-agent-utils` 里现成的 `BraveWebSearchTool`，不重写解析；
> 用装饰器解决它的两个硬缺陷（注册名撞名、无超时）。

## 目标与范围

- **目标**：配了 `BRAVE_API_KEY` 后，模型多一个 `BraveWebSearch` 工具；中文内容走博查、英文技术文档走 Brave。
- **非目标**：
  - 不做 `SearchProvider` 抽象层（两家共存而非二选一，没有分派需求）
  - 不做故障转移（与既有的「不重试」决策冲突）
  - 不重写 Brave 的响应解析（库里已有）
  - 不改 `webFetch` 与子 agent frontmatter

## 前提事实（已实测核准）

| 事实 | 证据 | 影响 |
|---|---|---|
| 库版 `BraveWebSearchTool` 注册名是 `@Tool(name = "WebSearch")` | `javap -v` | **与现有博查工具撞名**，必须改名，否则工具分发与子 agent 的 allow/deny（按注册名精确匹配）全部错乱 |
| 库版 Builder 只有 `resultCount` | `javap` | 无 `baseUrl` → **离线单测做不了**；无超时配置 |
| 库版构造器是 `RestClient.builder().baseUrl(常量).defaultHeader(...)×3.build()` | `javap -c` 字节码 | **没有 requestFactory、没有超时**。Spring 默认走 Apache HttpClient，响应超时为 null 即无限等 |
| `api.search.brave.com` 国内直连**可达** | `curl` → `HTTP 422 · 1.59s`（422 因未带 key，说明请求到达服务端） | 不需要代理。**这条推翻了博查 spec 里「国内直连大概率不通」的说法**——那是未验证的猜测，需一并更正 |
| 库版域名过滤是**客户端**做的 | `applyDomainFiltering` 在 `parseResults` 之后 | `allowedDomains` 不省配额，反而白白消耗结果条数。必须在工具描述里告诉模型改用 `site:` 运算符 |
| 两家入参与响应形状都不同 | Brave：`query/allowedDomains/blockedDomains` → `web.results[]`(title/url/description)；博查：`query/freshness/include` → `webPages.value[]`(name/url/snippet/summary/siteName/datePublished) | 工具签名无法统一，这也是选「共存」而非「抽象层」的实际理由 |

## 设计定案

### 新增组件

**`RenamedToolCallback`**（`agent` 包）——把现有 `AgentTools.DescribedToolCallback`（只换描述）扩成同时能换
**注册名**与描述，`null` 表示保持原值。原 `describedAs()` 的调用点（TodoWrite）改为走新类，行为不变。

**`TimeLimitedToolCallback`**（`agent` 包）——通用超时装饰器：daemon 线程池 submit + `Future.get(timeout)`，
超时抛可读 `IllegalStateException`。

> **已知代价，非疏漏**：底层 Apache HttpClient 的阻塞 read 不保证响应中断，`cancel(true)` 之后那个工作线程
> 可能滞留到请求自行结束。用 **daemon** 线程保证它不阻止 JVM 退出。这是「库版不给超时口子」逼出来的次优解，
> 比「无限等」好，但不如博查那边在 HTTP 层设超时干净。

超时值为**类内常量 20s**，不给 env（与博查的 10s/20s 同为常量，保持一致）。

**只套在 Brave 上，不套博查**：博查工具已在 HTTP 层设了 connect 10s / read 20s，再包一层只会让同一个失败
出现两种措辞、且难判断到底是哪一层先触发。

### 装饰链

Brave 比博查多两层。它已是 `ToolCallback`（非 `@Tool` 对象），故走 `all.add(..)` 而不进 `rawTools`：

```
库版 BraveWebSearchTool
  → ToolCallbacks.from           得到名为 WebSearch 的 callback
  → RenamedToolCallback          改名 BraveWebSearch + 换中文描述
  → TimeLimitedToolCallback      20s 总超时
  → MediaExternalizingCallback   既有
  → ToolEventCallback            既有，TUI 显示一行工具活动
```

### 博查工具改名

注册名 `WebSearch` → `BochaWebSearch`。对称命名，避免模型把某一家当成「默认搜索」。
连带改动：`@Tool` 注解、3 处测试断言、系统提示指引段里的工具名。

### 模型如何在两家之间选

共存方案的成败全在工具描述。两段描述**互相点名**：

- `BochaWebSearch`：中文内容、国内站点、中文技术社区优先；返回长摘要，常常一次就够。
- `BraveWebSearch`：英文技术文档、GitHub issue、英文新闻优先；只返回短描述，要正文再走 `webFetch`。
  额外写明：**别用 `allowedDomains` 限定域名**（客户端过滤，白烧配额），改把 `site:xxx` 写进 query。

### 系统提示指引

`webSearchGuide(boolean)` 改为 `webSearchGuide(boolean bocha, boolean brave)`，四态渲染：

| 博查 | Brave | 指引内容 |
|---|---|---|
| ✗ | ✗ | 空串（模型看不到任何搜索相关字样） |
| ✓ | ✗ | 只讲博查（与现状一致） |
| ✗ | ✓ | 只讲 Brave |
| ✓ | ✓ | 讲两家分工 |

### env

| env | 作用 |
|---|---|
| `BRAVE_API_KEY` | 配了才注册 Brave |
| `BRAVE_SEARCH_COUNT` | 条数，默认 **5**，钳 `[1, 20]`。比博查的 8 更保守——Brave 免费档 2000 次/月，更该省 |

### 错误处理

**不新增机制**。库版抛什么就抛什么，由 `ToolEventCallback` 标 `ok=false` 显红。

这与博查那套「状态码 + 原文透传」**不一致**——库版做不到，我们也够不着它的 `onStatus`。
这是选用库版换来的代价，明写在此。唯一新增的失败类型是超时。

## 能力退化（必须知道）

Brave 的响应解析、域名过滤、错误处理全在库里，且 `baseUrl` 硬编码 → **无法离线单测**。
所以 Brave 这半边的实际正确性**只能靠真机冒烟保证**，而博查那半边有 26 个离线用例。

我们能离线测的只有自己写的外壳：改名、超时、门控、指引段。

## 测试策略

| 测什么 | 怎么测 |
|---|---|
| `RenamedToolCallback` | 假 callback → 断言名与描述被替换、`call` 透传、`inputSchema` 不变、`null` 表示保持原值 |
| `TimeLimitedToolCallback` | 假慢 callback（sleep 200ms）+ 50ms 超时 → 抛超时异常且消息可读；快 callback → 正常返回原值 |
| Brave 门控 | 无 `BRAVE_API_KEY` → 不注册；有则注册且注册名为 `BraveWebSearch` |
| `BRAVE_SEARCH_COUNT` | 缺失/非法回退 5，越界钳到 `[1, 20]` |
| 博查改名 | 现有注册名断言改为 `BochaWebSearch` |
| 指引段 | 四态各一个用例 |
| Brave 真机 | `@EnabledIfEnvironmentVariable(named = "BRAVE_API_KEY")` 冒烟。**当前 `~/.secrets` 里没有这个 key，会 skip** |

验证命令：`mvn -pl springai-code-tui test`（必须模块作用域）。

## 文档改动

| 位置 | 改什么 |
|---|---|
| `README.md` 工具清单 | 加 `BraveWebSearch`；博查那条的工具名改为 `BochaWebSearch` |
| `README.md` 安全披露 | **加实质内容**：Brave 是美国公司，查询词发给它意味着**数据出境**——这与博查（国内、内容合规过滤）不是一回事，用户应当知情 |
| `config.env.example` | 加 `BRAVE_API_KEY` / `BRAVE_SEARCH_COUNT` |
| `2026-07-26-web-search-design.md` | 更正「`api.search.brave.com` 国内直连大概率不通」——实测可达（`HTTP 422 · 1.59s`）。当时用它做淘汰理由之一，该理由不成立；另一条理由（描述写死 Claude / US-only）仍成立 |

## 参考

- [Brave Search API](https://brave.com/search/api/)
- 库版实现：`org.springaicommunity.agent.tools.BraveWebSearchTool`（`spring-ai-agent-utils 0.6.0`）
- 前序设计：`docs/superpowers/specs/2026-07-26-web-search-design.md`

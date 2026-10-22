# 第四家搜索后端（DeepSeek Web Search）接入 code-tui

> 在已有的博查（Bocha）、Brave、智谱三家搜索工具旁，再接 DeepSeek 的 Search API，四家共存。
> 形状照 `ZhipuWebSearchTool` 先例自研 REST 工具；`webSearchGuide` 从三布尔扩到四布尔。
> DeepSeek 与智谱同病：key 与 LLM provider 共用（`DEEPSEEK_API_KEY`），搜索按次计费。

## 目标与范围

- **目标**：配了 `DEEPSEEK_API_KEY`（且未显式关）后，模型多一个 `DeepSeekWebSearch` 工具；
  与博查/智谱同为国内向搜索源（查询词不出境），互为冗余。
- **非目标**：
  - 不接 DeepSeek Fetch API（`/v1/tools/fetch`，抓单页转 Markdown）——code-tui 已有
    `webFetch` 覆盖该场景且不按次计费，接入只添冗余与计费通道（用户已核准）。
  - 不做 `SearchProvider` 抽象层（四家共存而非分派，前三家 spec 已三次否决，理由未变）。
  - 不做故障转移 / 自动重试（与既有「不重试」决策一致：失败多为计费/限流，重试烧钱）。
  - 不改子 agent frontmatter（`general-purpose` 经既有 `disallowedTools` 机制自动获得）。

## 前提事实

### 官方文档（https://api-docs.deepseek.com/zh-cn/guides/search，2026-10-22 抓取）

| 项 | 值 | 证据 |
|---|---|---|
| Search 端点 | `POST https://api.deepseek.com/v1/tools/search`，鉴权 `Authorization: Bearer <DeepSeek API Key>`（与 LLM 同一把 key） | 文档端点表 |
| 请求体 | `query`（必填，1–128 Unicode 字符）、`count`（1–50，默认 10）、`ori_question`/`localization`/`need_content` 可选；未知字段忽略 | 文档参数表 |
| 搜索语法 | 可组合写进 query：`site:`、`-site:`、`-"..."`、`-关键词`、`"精确短语"`、`lang:zh,en`、`after:2025-01-01`、`before:2026` | 文档「搜索语法」节 |
| 200 响应 | `results[]`（无结果时空数组），每项：`rerank_score`（0–3，≥1.5 优质）、`title`、`url`、`snippet`（约 100–200 字符，前端展示）、`summary`（约 600–800 字符，适合模型阅读）、`content`（`need_content=false` 时恒空串）、`site_name`/`site_icon`（可 null）、`date`（RFC 3339 UTC，可 null）、`authority`（0–4，-1 未知） | 文档响应结构表 |
| 计费 | **每 1,000 次成功请求 5 元**（Search 与 Fetch 独立计价）；仅 HTTP 200 且产生结果才计费（`results` 非空），失败/零结果/超时不计费；`count` 不影响单价 | 文档「计费」节 |
| 限速 | Search 账号级 QPS 100（与模型并发独立） | 文档「限速」表 |
| 错误 | OpenAI 兼容格式（`error.message`，`param` 恒 null）；402=余额不足、401=key 无效、429=限速、408/5xx=可重试、其他 4xx 勿原样重试 | 文档「错误」节 |
| 与 chat completions 的关系 | **独立 REST API**，非 `web_search_options` 之类开关；无 messages/stream 概念，不经过模型 | 文档开头说明 |

### 与三家先例的关键差异（决定实现形状）

| 维度 | 博查/智谱 | DeepSeek |
|---|---|---|
| 时间过滤 | 独立参数 `freshness`（oneDay/oneWeek/...） | 无独立参数，`after:`/`before:` 搜索语法写进 query |
| 域名过滤 | 独立参数 `include` | 无独立参数，`site:`/`-site:` 搜索语法写进 query |
| key 归属 | 博查/Brave 独立搜索 key；智谱与 LLM 共用 | 与 LLM 共用（同智谱，需要同款显式关开关） |
| 摘要 | summary 可选/计费差异 | `summary` 默认返回（600–800 字符），`need_content` 默认 false |

## 用户已核准的口径（2026-10-22）

1. **默认开 + 0 显式关**：`DEEPSEEK_API_KEY` 非空且 `DEEPSEEK_SEARCH_ENABLED`（trim 后）≠ `"0"`
   即注册——与智谱 `zhipuSearchWanted` 同构。0.005 元/次比智谱 std 档便宜一半。
2. **只接 Search 不接 Fetch**：webFetch 已覆盖抓取场景。
3. **工具只留 `query` 一个参数**：过滤全靠搜索语法写进 query（与 API 形状一致，无翻译层）；
   工具描述里教语法。

## 设计定案

### 组件：`DeepSeekWebSearchTool`（`agent/tools` 包）

职责单一：HTTP 调 DeepSeek → 解析 → 渲染 Markdown。形状照 `ZhipuWebSearchTool`：私有构造 +
`builder(apiKey)`，`.resultCount(n)` / `.baseUrl(url)`（baseUrl 仅测试指向本地 stub，不暴露 env）。

| 决策点 | 定案 |
|---|---|
| HTTP 客户端 | `RestClient` + `JdkClientHttpRequestFactory`，connect 10s / read 20s + `ProxySelector.getDefault()`（三家同款：规避 HttpURLConnection 流式请求体 + 401 丢 error stream 的坑） |
| 注册名 | `@Tool(name = "DeepSeekWebSearch")`，对称命名 |
| 请求体 | 恒 `{"query": ..., "count": resultCount}`。`need_content` 不发（默认 false，`summary` 已是给模型读的 600–800 字符，正文走 webFetch，与博查「summary 换 snippet」同级定位决策）；`ori_question`/`localization` 不发（工具无用户原始问题的可靠来源，query 本身已含意图） |
| 暴露参数 | 仅 `query`（String 必填）。描述写明支持的搜索语法：`site:`/`-site:`/`"精确短语"`/`-排除词`/`lang:zh,en`/`after:2025-01-01`/`before:2026`，以及「一般别加时间过滤，硬指区间容易搜空」（博查 freshness 同款教训） |
| 返回渲染 | `results[]` 逐条：`title` → 标题、`url` → 网址、`site_name` → 来源名、`date` → 日期（RFC 3339 截到天）、`summary` → 摘要（空则回退 `snippet`）。格式与博查/智谱输出对齐（`N. 标题 — 来源 · 日期\n   url\n   摘要`）；`rerank_score`/`authority` 不渲染（模型自己会按 URL/来源判断质量，渲染只添 token 噪音）；任一字段缺失省略该段不留空占位 |

淘汰的备选：freshness/include 翻译层（把 `oneDay` 译成 `after:<今天-1天>`）——枚举到日期串的
映射有臆测成分（时区口径未知），且与「过滤靠语法」的 API 设计对着干，多一层翻译就多一处
猜错没人发现的可能。

### env

| env | 语义 |
|---|---|
| `DEEPSEEK_API_KEY` | **复用**（与 DeepSeek 模型同一把 key，config.env.example 大模型段落已有）。非空且开关非 0 即注册 |
| `DEEPSEEK_SEARCH_ENABLED` | 显式关开关：trim 后 = `"0"` 则不注册（只认 0，其他值视为开——回退语义与 `zhipuSearchWanted` 一致）。适用于只用 DeepSeek 模型、不想让搜索按次计费的用户；关闭不影响模型本身 |
| `DEEPSEEK_SEARCH_COUNT` | 默认 **8**（对齐博查/智谱），钳 `[1, 50]`；缺失/非法回退默认（照 `resolveResultCount` 系形状）。注意：count 不影响单价（按次计费），只影响上下文占用 |

### 系统提示指引段：三布尔扩四布尔

`webSearchGuide(boolean bocha, boolean zhipu, boolean deepseek, boolean brave)`（2^4 = 16 态，
仍走条目拼装，无 if 硬编码）。改动点：

- `domesticNames(bocha, zhipu)` → `domesticNames(bocha, zhipu, deepseek)`：DeepSeek 是国内服务
  （api.deepseek.com，查询词不出境），归入国内源组，斜杠连接。
- DeepSeek 专属坑条目：「DeepSeekWebSearch 的时间/域名过滤没有独立参数，用搜索语法
  （`site:` / `after:` 等）直接写进搜索词」。
- 既有「freshness 一般不要传」条目只覆盖博查/智谱（DeepSeek 无 freshness 参数，不适用）。
- 硬约束不变：正文**无花括号**；零家注册返回空串；绝不提未注册的工具。

### 在 AgentTools 里的落位

```
DEEPSEEK_API_KEY 非空 且 ENABLED≠0
    → deepSeekSearchWanted(key, enabledEnv)（照 zhipuSearchWanted，同款 javadoc 讲清「为何有开关」）
    → createDeepSeekWebSearchTool(key, countEnv)（key 空返回 null）
    → DeepSeekWebSearchTool（@Tool 对象 → 进 rawTools，与博查/智谱同路）→ 既有装饰链
    → 主 agent + 子 agent（general-purpose 自动获得）
```

`zhipuSearchWanted` 的 javadoc「为何只有智谱有这个开关」措辞需同步改（DeepSeek 加入后不再
「只有」——key 共用 + 按次计费的同构理由）。

### 权限登记

`ToolRegistry` 加一行：`DeepSeekWebSearch → NETWORK_READ / "query"`（与三家同款）。
`RuntimeToolSet.byRegisteredName` 用工厂假 key 补第四个（无 key 的 CI 机器上枚举一致），
`ToolRegistryCompletenessTest` 全量比对兜底。

## 错误处理

| 情形 | 行为 |
|---|---|
| 无 key / 开关=0 | 不注册工具，指引段无 DeepSeek 条目 |
| 空 query | 直接返回提示文本，**不发请求**（虽然零结果不计费，但空查询必拿 400，省一个来回） |
| 0 结果 | 返回可读文本「没搜到，建议换关键词」，`ok=true` 不是错误（且**不计费**） |
| HTTP 错误（含 402 余额不足） | 抛 `IllegalStateException`，消息带状态码 + body 原文（截断预览）。OpenAI 兼容错误形状 `error.message` 本身可读，不做 code 分支 |
| 连不上 / 超时 / 非 JSON | 照三家同款三档措辞：「连不上」/「响应无法解析（疑似代理拦截页）」/ 其余 |

不做：**不重试**（计费/限流问题重试只烧钱）、**不塌错误**（状态码 + 原文必须透传）。

## 测试策略

**离线单测（默认跑，无需 key）**，本地 stub HTTP server（照 `ZhipuWebSearchToolTest` 模式，
stub 路径 `/v1/tools/search`，样例响应用文档响应结构表造）：

1. 渲染正确性——标题/URL/来源名/日期（截天）/summary 摘要
2. summary 缺失回退 snippet
3. `date` 缺失降级——不留悬空 ` · `
4. 请求体只含 query+count，`need_content`/`ori_question`/`localization` 不出现
5. count 达到请求体；builder 钳制；`resolveResultCount` 回退/钳制
6. Bearer 鉴权头
7. 空/null query 短路——stub 收到零请求
8. 0 结果 → 文本提示不抛
9. 402/429/5xx → 抛且消息含状态码与原文（自定义前缀「DeepSeek 搜索失败」）
10. 响应缺 `results` 字段 / 脏元素 / 空响应体 → 可读异常（照智谱三件套）
11. 注册名断言 = `DeepSeekWebSearch`；`deepSeekSearchWanted` 开关语义（0/其他/空白/null）
12. 指引段——16 态无花括号；仅 DeepSeek 时点名且不提三家；四家时含分工句与专属坑条目

**真机冒烟**：双门控 `CODETUI_LIVE_TESTS=1` + `DEEPSEEK_API_KEY`（照
`BochaWebSearchSmokeTest` 形状）。**本次可直接跑通**（用户 key 有余额，一次成功搜索
0.005 元），跑完把 200 响应真实字段形状回填本 spec「已知取舍」——比智谱接入时
（余额不足只能 assume 跳过）条件好。含一个搜索语法用例（`site:` 限定）验证语法被真机接受。

**验证命令**：`mvn -pl springai-code-tui -am test`；单类加 `-Dsurefire.failIfNoSpecifiedTests=false`。

## 文档改动

| 位置 | 改什么 |
|---|---|
| `src/package/bin/config.env.example` | 搜索段「三家可共存」→「四家」；DeepSeek 块（key 复用说明、ENABLED 开关、COUNT）；注意 `.example` 后缀易被 grep 漏扫，全文搜 `ZHIPU_SEARCH` 对齐补 `DEEPSEEK_SEARCH` |
| `README.md` | 工具清单加 `DeepSeekWebSearch`；安全披露：第四条出网通道（国内服务，不出境，同博查/智谱性质）、按次计费 5 元/千次 |
| `docs/implementation-map.md` | 搜索工具一节扩四家 |
| `docs/guide/permissions.md` / `docs/guide/security.md` | 工具清单与出网通道补 DeepSeek |

## 已知取舍

1. **200 响应形状已真机实测**（2026-10-22，`count=2` 直连探针）：与文档响应结构表完全一致——
   `results[]` 每项含 `title`/`url`/`site_name`/`site_icon`/`authority`/`rerank_score`/`date`
   （RFC 3339 UTC，如 `2025-05-19T16:00:00+00:00`）/`summary`/`snippet`/`content`（`need_content`
   未发时恒空串）。`render` 字段映射无需修正；冒烟两用例（普通搜索 + `site:` 语法）真机通过。
2. **summary 实测远超文档宣称长度**：文档称 600–800 字符，实测单条可达 2500+（内嵌 markdown
   标题）。默认 8 条若全文渲染，一次搜索可吃掉上万 token 上下文——`render` 加了
   `MAX_SUMMARY_CHARS = 800` 单条截断（摘要职责是让模型挑 URL，不是搬运正文；正文走 webFetch）。
3. **`rerank_score`/`authority` 不渲染**：质量信号模型可从 URL/来源自行推断，渲染纯属 token
   噪音。若真机发现排序差导致模型选错源，再考虑渲染 `authority`。
4. **`need_content` 恒不发**：正文 8 条 × 全文必撑爆上下文；需要细节走 webFetch 是全工具链
   既有分工。真有需求再加 env，十分钟的事。
5. **query 长度 1–128 不本地校验**：超长由服务端 400 拒（不计费），错误原文透传即可排查；
   本地截断反而可能把模型想搜的词截没。

## 参考

- [DeepSeek 网页搜索与抓取 API 指南](https://api-docs.deepseek.com/zh-cn/guides/search)
- 前序设计：`2026-07-26-web-search-design.md`（博查）、`2026-07-26-brave-search-design.md`
  （Brave）、`2026-10-08-zhipu-web-search-design.md`（智谱，本次最直接的模板）

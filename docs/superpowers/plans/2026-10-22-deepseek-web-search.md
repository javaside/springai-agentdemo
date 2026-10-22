# DeepSeek 联网搜索（DeepSeekWebSearch）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 code-tui 接第四家搜索后端——DeepSeek Web Search API（`POST /v1/tools/search`），与博查/Brave/智谱四家共存，按 `DEEPSEEK_API_KEY` + `DEEPSEEK_SEARCH_ENABLED` 门控注册。

**Architecture:** 自研 `DeepSeekWebSearchTool`（`RestClient` + JDK HttpClient，形状照 `ZhipuWebSearchTool` 先例；工具只留 `query` 一个参数，过滤靠 DeepSeek 搜索语法写进搜索词）；`AgentTools.build` 门控注册进既有装饰链；`webSearchGuide` 从三布尔扩到四布尔（条目拼装不变）。

**Tech Stack:** Java 17（语言级锁 17，禁用更高版本语法）、Spring AI 2.0 `@Tool` 注解、JUnit 5 + JDK 内置 HttpServer 做 stub。

**Spec:** `docs/superpowers/specs/2026-10-22-deepseek-web-search-design.md`（含 API 事实表、用户已核准口径与已知取舍，执行者必读）。

## Global Constraints

- 测试命令必须模块作用域 + `-am`：`mvn -pl springai-code-tui -am test`；单类跑加 `-Dsurefire.failIfNoSpecifiedTests=false`。整仓 `mvn test` 禁用。
- 语言级 17（`maven.compiler.release=17`）。
- 注释写「为什么」不写「做了什么」；中文注释、中文提交正文。
- `webSearchGuide` 正文**不得含花括号**（StringTemplate param 值注入会炸）。
- 错误消息**不塌**：状态码 + 响应原文必须透传；**不重试**。
- 不在测试/文档里硬编码任何真实 API key；真机测试 key 从环境变量取（`source ~/.secrets`）。
- 提交信息 `type: 说明` 前缀，一次提交只做一件事，功能与测试同提交。
- 分支：`feature/deepseek-web-search`，不直接提交 main。

---

### Task 1: `DeepSeekWebSearchTool` 工具类 + 离线单测

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/tools/DeepSeekWebSearchTool.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/tools/DeepSeekWebSearchToolTest.java`

**Interfaces:**
- Consumes: 无（纯新增；实现模板 = 同包 `ZhipuWebSearchTool.java` 全文）。
- Produces: `public static DeepSeekWebSearchTool.Builder builder(String apiKey)`；`Builder.baseUrl(String)/resultCount(int)/build()`；`public String webSearch(String query)`（**单参数**）；`public static int resolveResultCount(String raw)`（默认 8，钳 [1,50]）。注册名 `DeepSeekWebSearch`。

- [ ] **Step 1: 写失败测试**

`DeepSeekWebSearchToolTest.java`：照 `ZhipuWebSearchToolTest` 的 StubServer 模式（JDK HttpServer、记录请求数/请求体/鉴权头），stub 路径 `/v1/tools/search`。样例响应按文档响应结构表造：

```java
// 两条结果：第一条字段齐全（含 rerank_score/authority 等不渲染字段）；第二条缺 date 且缺 summary（回退 snippet）
private static final String TWO_RESULTS = """
        {"results":[
          {"rerank_score":2.4,"title":"标题一","url":"https://a.com/1",
           "snippet":"短摘要一","summary":"长摘要一（600-800字符档）","content":"",
           "site_name":"来源一","site_icon":"https://a.com/icon.png",
           "date":"2026-06-01T00:00:00+00:00","authority":3},
          {"rerank_score":1.8,"title":"标题二","url":"https://b.com/2",
           "snippet":"短摘要二","summary":"","content":"","site_name":"来源二",
           "site_icon":null,"date":null,"authority":-1}
        ]}
        """;
```

用例清单（断言要点，全部照智谱先例形状）：
1. `rendersTitleUrlSiteSummaryAndShortDate`：含「找到 2 条结果」「标题一」「https://a.com/1」「长摘要一」「标题一 — 来源一 · 2026-06-01」；**不含**「短摘要一」（有 summary 时不回退）与「rerank_score」「authority」（质量分不渲染）。
2. `fallsBackToSnippetWhenSummaryMissing`：第二条渲染「短摘要二」。
3. `omitsMetaWhenDateMissing`：「标题二 — 来源二\n」无悬空 ` · `。
4. `sendsOnlyQueryAndCount`：请求体含 `"query":"..."` 与 `"count":8`，**不含** `need_content`/`ori_question`/`localization`。
5. `resultCountReachesRequestBody` / `builderClampsResultCount`：count=20 达到请求体；builder 999 钳 50。
6. `sendsBearerAuthorizationHeader`。
7. `blankQueryShortCircuitsWithoutRequest` / `nullQueryShortCircuitsWithoutRequest`：stub 零请求，返回「搜索词为空」。
8. `zeroResultsReturnsActionableTextWithoutThrowing`（`{"results":[]}`）：「没搜到」+ 建议，不抛。注意提示语不提 freshness（DeepSeek 无此参数），改提「调整搜索语法」。
9. `paymentRequiredErrorCarriesStatusCodeAndBody`（HTTP 402 + OpenAI 兼容形状 `{"error":{"message":"Insufficient Balance","type":"payment_required","param":null,"code":"insufficient_balance"}}`）：消息含「402」与「Insufficient Balance」。
10. `serverErrorCarriesStatusCode`（503）：含「DeepSeek 搜索失败：HTTP 503」、不含「连不上」。
11. `truncatesOverlongErrorBody` / `unreachableHostReportsConnectionFailure` / `nonJsonResponseIsNotReportedAsConnectionFailure` / `malformedResponseThrowsWithPreview`（缺 `results` 字段）/ `nonObjectResultElementsThrowReadableError` / `keepsUsableResultsWhenArrayHasJunkElements` / `emptyBodyThrowsInsteadOfNpe`：照智谱三件套。
12. `resolveResultCountFallsBackAndClamps`（纯函数：null/空白/abc→8，0→1，999→50，" 20 "→20）。

- [ ] **Step 2: 跑测试确认红**

```bash
mvn -pl springai-code-tui -am test -Dtest='DeepSeekWebSearchToolTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：编译错误 `cannot find symbol: class DeepSeekWebSearchTool`。

- [ ] **Step 3: 写实现**

以 `ZhipuWebSearchTool.java` 为模板逐段改，差异点（其余错误转译/preview/str/shortDate/Builder 形状全部同构保留）：

- `DEFAULT_BASE_URL = "https://api.deepseek.com"`；`SEARCH_PATH = "/v1/tools/search"`。
- 去掉 `searchEngine`/`SEARCH_ENGINES`/`joinInclude`（无此参数）；`webSearch(String query)` 单参数。
- 类 javadoc 讲清三点：为何单 query 参数（DeepSeek 过滤全靠搜索语法，翻译层有臆测成分）、为何不渲染 rerank_score/authority（token 噪音）、key 与 LLM 共用按次计费（5 元/千次成功请求，零结果不计费）。
- `@Tool` 描述（教搜索语法，这是模型唯一能学到语法的地方）：

```java
@Tool(name = "DeepSeekWebSearch", description = """
        用 DeepSeek 搜索引擎搜索互联网，返回网页标题、网址、摘要（600-800 字符）、站点名与发布时间。

        用法：
        - 中英文内容都可以搜；需要项目之外最新信息（库用法、报错含义、版本变更、新闻）时用它。
        - 时间、域名、语言过滤没有独立参数，用搜索语法直接写进搜索词：site:github.com（限定域名）、
          -site:zhihu.com（排除域名）、"精确短语"、-排除词、lang:zh,en（语言）、after:2025-01-01 /
          before:2026（时间范围）。可组合多个。
        - 时间过滤一般别加：默认不限时间效果最好，硬指区间反而容易搜空。
        - 它只返回摘要。需要网页原文细节时，把结果里的网址交给 webFetch 工具去抓取。
        - 引用了搜索结果，请在回答末尾用 markdown 链接列出实际参考的网址（Sources）。
        """)
```

- 请求体：`{"query": query.trim(), "count": resultCount}`，仅此两项。
- `extractResults`：认 `results` 字段（数组），异常文案「响应缺少 results 字段」。
- `render`：`title`/`url`/`site_name`/`date`（截天）/`summary`（空回退 `snippet`）。
- 零结果提示：`没搜到「...」的相关结果。建议换一组关键词或同义词，或去掉搜索词里的 site:/after: 等限定语法再试一次。`
- `resolveResultCount` javadoc 引 `DEEPSEEK_SEARCH_COUNT`。

- [ ] **Step 4: 跑测试确认全绿**（同 Step 2 命令，预期全部 PASS）

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/tools/DeepSeekWebSearchTool.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/tools/DeepSeekWebSearchToolTest.java
git commit -m "feat: DeepSeekWebSearchTool DeepSeek 联网搜索工具（离线 stub 单测全覆盖）"
```

---

### Task 2: `webSearchGuide` 三布尔扩四布尔

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（`webSearchGuide`/`divisionLine`/`domesticNames`，约 906–968 行）
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java`

**Interfaces:**
- Consumes: 无前置（独立于 Task 1，调用点暂以 `deepseek=false` 过渡）。
- Produces: `static String webSearchGuide(boolean bocha, boolean zhipu, boolean deepseek, boolean brave)`；`static boolean deepSeekSearchWanted(...)` 留给 Task 3。

- [ ] **Step 1: 改造测试（先红）**

`AgentToolsWebSearchWiringTest` 里 `webSearchGuide` 系列用例全部改四参：
- 既有 `guideIsEmptyWhenNoToolRegistered` / `guideCoversBochaOnly` / `guideCoversZhipuOnly` / `guideCoversBraveOnly` / `guideExplainsBochaZhipuRedundancy` / `guideExplainsDivisionWhenAllRegistered`：插入第四参 `false`（全组合位序：bocha, zhipu, deepseek, brave）。
- 新增 `guideCoversDeepSeekOnly`：点名 `DeepSeekWebSearch`、不提三家、含「搜索语法」条目（断言 `guide.contains("site:")` 或「搜索语法」字样）与 webFetch/Sources。
- 新增 `guideExplainsDivisionWhenAllFourRegistered`：四家全开含「中文」「英文」分工句与 DeepSeek 语法条目。
- `noGuideVariantContainsTemplateBraces`：三层循环扩成四层（16 态）。
- `AgentTools.build` 调用点过渡：`webSearchGuide(webSearch != null, zhipuWebSearch != null, false, braveWebSearch != null)`。

- [ ] **Step 2: 跑测试确认红**（`-Dtest='AgentToolsWebSearchWiringTest'`，预期编译错误：四参方法不存在）

- [ ] **Step 3: 实现**

- `webSearchGuide` 签名扩 `boolean deepseek`（javadoc「三家已是 2^3=8 态」改「四家 2^4=16 态，条目拼装」）。
- `domesticNames(bocha, zhipu)` → `domesticNames(bocha, zhipu, deepseek)`：用 `StringJoiner` 或列表拼「BochaWebSearch / ZhipuWebSearch / DeepSeekWebSearch」，按注册斜杠连接（三家以下组合各措辞不再 if 硬编码——改为「国内源名列表 + 一句共通措辞」，见下条）。
- `divisionLine` 措辞：国内源 ≥2 家时「多家互为冗余：一家搜不到或额度耗尽时换另一家再试」从两家特判改为 `domesticCount >= 2` 通用句（若已注册国内源与 Brave 并存则保留分工句式）。
- 专属坑段加条目（freshness 条目保持只看 bocha||zhipu）：

```java
if (deepseek) {
    sb.append("  DeepSeekWebSearch 的时间/域名过滤没有独立参数，用搜索语法（site:、after: 等）直接写进搜索词。\n");
}
```

- [ ] **Step 4: 跑测试确认全绿**

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java
git commit -m "refactor: webSearchGuide 三家条目拼装扩为四家（2^4=16 态）"
```

---

### Task 3: `AgentTools` 接线（开关 + 工厂 + 注册）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（`build` 注册段约 493–510 行、`zhipuSearchWanted` 约 1015 行、类 javadoc 工具计数）
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java`（追加接线用例）

**Interfaces:**
- Consumes: Task 1 的 `DeepSeekWebSearchTool.builder/resolveResultCount`；Task 2 的四布尔 `webSearchGuide`。
- Produces: `public static DeepSeekWebSearchTool createDeepSeekWebSearchTool(String apiKey, String countEnv)`（key 空返回 null）；`static boolean deepSeekSearchWanted(String apiKey, String enabledEnv)`；`build` 按 `DEEPSEEK_API_KEY`/`DEEPSEEK_SEARCH_ENABLED`/`DEEPSEEK_SEARCH_COUNT` 门控注册。

- [ ] **Step 1: 追加失败测试**

```java
@Test
void deepSeekSearchWantedOnlyZeroDisables() {
    assertFalse(AgentTools.deepSeekSearchWanted(null, null), "无 key 不注册");
    assertFalse(AgentTools.deepSeekSearchWanted("  ", "1"), "空白 key 不注册");
    assertFalse(AgentTools.deepSeekSearchWanted("sk-x", "0"), "ENABLED=0 显式关");
    assertTrue(AgentTools.deepSeekSearchWanted("sk-x", null), "有 key 默认开");
    assertTrue(AgentTools.deepSeekSearchWanted("sk-x", "false"), "只认 0：其他值视为开");
    assertTrue(AgentTools.deepSeekSearchWanted("sk-x", " 0 "), "两侧空白 trim 后仍关");
}

@Test
void noDeepSeekKey_noDeepSeekTool() {
    assertNull(AgentTools.createDeepSeekWebSearchTool(null, null));
    assertNull(AgentTools.createDeepSeekWebSearchTool("   ", null));
}

/** 注册名取 @Tool 注解；子 agent allow/deny 按注册名精确匹配，写错静默失效。 */
@Test
void deepSeekToolRegisteredNameIsDeepSeekWebSearch() {
    DeepSeekWebSearchTool tool = AgentTools.createDeepSeekWebSearchTool("fake-key", null);
    assertNotNull(tool);
    List<String> names = Arrays.stream(ToolCallbacks.from(tool))
            .map(c -> c.getToolDefinition().name()).toList();
    assertEquals(List.of("DeepSeekWebSearch"), names, "实际=" + names);
}

@Test
void deepSeekCountFromEnvReachesConstruction() {
    assertNotNull(AgentTools.createDeepSeekWebSearchTool("fake-key", "20"));
}
```

顶部补 import `DeepSeekWebSearchTool`。

- [ ] **Step 2: 跑测试确认红**（编译错误：两方法不存在）

- [ ] **Step 3: 实现（四处）**

3a. `build` 内智谱注册段之后追加：

```java
// DeepSeek 搜索（第四家，国内向）：key 复用 DEEPSEEK_API_KEY，配了且未显式关（DEEPSEEK_SEARCH_ENABLED=0）
// 才注册——key 存在性表达不了「只要模型、不要按次计费的搜索」（同智谱 zhipuSearchWanted 的理由）。
DeepSeekWebSearchTool deepSeekWebSearch = deepSeekSearchWanted(
        System.getenv("DEEPSEEK_API_KEY"), System.getenv("DEEPSEEK_SEARCH_ENABLED"))
        ? createDeepSeekWebSearchTool(
                System.getenv("DEEPSEEK_API_KEY"), System.getenv("DEEPSEEK_SEARCH_COUNT"))
        : null;
```

`rawTools` 段 `if (zhipuWebSearch != null)` 后追加 `if (deepSeekWebSearch != null) { rawTools.add(deepSeekWebSearch); }`；
Task 2 的过渡 `false` 换 `deepSeekWebSearch != null`。

3b. `createZhipuWebSearchTool` 之后新增工厂（javadoc 同构：env 读取在此、解析语义在 `DeepSeekWebSearchTool#resolveResultCount`）。

3c. `zhipuSearchWanted` 之后新增 `deepSeekSearchWanted`（逻辑与 zhipu 同构拷贝），**并同步改 `zhipuSearchWanted` 的 javadoc**：「为何只有智谱有这个开关」→「为何智谱与 DeepSeek 有这个开关」（两家 key 均与 LLM provider 共用 + 搜索按次计费）。

3d. 类 javadoc / 行内注释的「三家」「四个搜索」计数措辞同步。

- [ ] **Step 4: 跑测试确认全绿**

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java
git commit -m "feat: AgentTools 按 DEEPSEEK_API_KEY 门控注册 DeepSeek 搜索工具（ENABLED=0 可显式关）"
```

---

### Task 4: 权限登记 + 运行时工具集补齐

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/ToolRegistry.java:54`（`ZhipuWebSearch` 行后）
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/RuntimeToolSet.java`（`byRegisteredName` 智谱段后 + javadoc「三个 env 门控」→「四个」）

- [ ] **Step 1: 先跑完整性测试看它红**

```bash
mvn -pl springai-code-tui -am test -Dtest='ToolRegistryCompletenessTest' -Dsurefire.failIfNoSpecifiedTests=false
```

若 CI 机器无 `DEEPSEEK_API_KEY` 此刻可能仍绿（工具不在枚举也不在登记表）——先做 Step 2 的 RuntimeToolSet 补齐再单独跑，必红（枚举有、登记表无）。

- [ ] **Step 2: 登记与补齐**

`ToolRegistry` 加 `put("DeepSeekWebSearch", ToolCategory.NETWORK_READ, "query", false);`
`RuntimeToolSet.byRegisteredName` 智谱段后追加：

```java
DeepSeekWebSearchTool deepseek = AgentTools.createDeepSeekWebSearchTool("fake-key", null);
if (deepseek != null) {
    all.add(ToolCallbacks.from(deepseek)[0]);
}
```

- [ ] **Step 3: 跑完整性测试确认绿**

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/ToolRegistry.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/RuntimeToolSet.java
git commit -m "feat: DeepSeekWebSearch 登记权限表并补齐运行时工具集枚举"
```

---

### Task 5: 真机冒烟测试（跑通并回填 spec）

**Files:**
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/tools/DeepSeekWebSearchSmokeTest.java`

- [ ] **Step 1: 写测试类**（照 `BochaWebSearchSmokeTest` 双门控：`CODETUI_LIVE_TESTS=1` + `DEEPSEEK_API_KEY`；成功搜索一次 0.005 元，零结果不计费）

用例两个：
1. `realSearchReturnsResultsWithUrls`：搜「Spring AI 框架」count=3，断言含「找到」与「http」，打印全文供人工核对字段形状。
2. `searchSyntaxSiteAcceptedByRealApi`：搜「Spring AI reference site:docs.spring.io」，调用成功即过（找到或没搜到都算成功——验证语法被接受而非 400）。

- [ ] **Step 2: 默认环境跑一遍确认优雅跳过**

- [ ] **Step 3: 真机跑（key 从 `~/.secrets` 引入环境变量，不落盘不打印）**

```bash
source ~/.secrets && CODETUI_LIVE_TESTS=1 mvn -pl springai-code-tui -am test -Dtest='DeepSeekWebSearchSmokeTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：PASS。把 200 响应真实字段形状回填 spec「已知取舍」第 1 条（字段名/可空性/date 格式；若与文档表有出入，修 `render` 映射并补单测用例）。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/tools/DeepSeekWebSearchSmokeTest.java
git commit -m "test: DeepSeek 搜索真机冒烟（双门控，回填 200 响应实测形状）"
```

---

### Task 6: 文档同步

**Files:**
- Modify: `springai-code-tui/src/package/bin/config.env.example`（搜索段，`ZHIPU_SEARCH_ENGINE` 块后）
- Modify: `springai-code-tui/README.md`（工具清单 + 安全披露）
- Modify: `springai-code-tui/docs/implementation-map.md`（搜索工具一节）
- Modify: `springai-code-tui/docs/guide/permissions.md`、`springai-code-tui/docs/guide/security.md`

- [ ] **Step 1: config.env.example**：「三家可共存」→「四家可共存」，智谱块后追加：

```
#
# DeepSeek（国内向，返回 600-800 字符长摘要）。与博查/智谱互为冗余；按次计费（5 元/千次成功请求，
# 零结果与失败不计费）。
# 注意：key 复用 DEEPSEEK_API_KEY（上面大模型段落那把）——配了它搜索工具即启用。
# 显式关闭开关：设 0 则配了 key 也不注册内置搜索工具（只认 0，其他值视为开）。适用于
# 只用 DeepSeek 模型、不想让搜索按次计费的场景；关闭不影响模型本身。
#DEEPSEEK_SEARCH_ENABLED=1
# 单次搜索返回条数，范围 [1, 50]，默认 8。count 不影响单价（按请求次数计费），只影响上下文占用。
#DEEPSEEK_SEARCH_COUNT=8
```

末尾「三家按注册状态与内容语言自选」改「四家」。
（记忆教训：`.example` 文件无测试钉守、grep 漏扫——全文搜 `ZHIPU_SEARCH` 逐处对齐。）

- [ ] **Step 2: README**：工具清单加 `DeepSeekWebSearch`（需 `DEEPSEEK_API_KEY`，按次计费 5 元/千次）；安全披露补第四条出网通道：查询词发给 DeepSeek（国内服务，不出境，同博查/智谱性质）。

- [ ] **Step 3: implementation-map / permissions / security**：搜索工具条目扩四家；permissions 工具类别表与 security 出网通道清单补 `DeepSeekWebSearch`。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/package/bin/config.env.example springai-code-tui/README.md \
        springai-code-tui/docs/implementation-map.md springai-code-tui/docs/guide/permissions.md \
        springai-code-tui/docs/guide/security.md
git commit -m "docs: DeepSeek 联网搜索接入文档（env/工具清单/安全披露/权限表）"
```

---

### Task 7: 全量验证 + 合并

- [ ] **Step 1: 全量测试**：`mvn -pl springai-code-tui -am test`（`CodingAgentSpikeTest.todoTurnIdBinding` 为已知 flaky，红了先单跑复核）。
- [ ] **Step 2: 编译对齐 CI**：`mvn -DskipTests package`。
- [ ] **Step 3: 变异测试抽查**：把 `render` 里 `summary` 字段改读 `"summery"`（不存在）→ `DeepSeekWebSearchToolTest` 应红 → 改回复跑全绿 → `git status` 确认工作树干净。
- [ ] **Step 4: 合并（问过用户后执行）**：`git checkout main && git merge --no-ff feature/deepseek-web-search`，正文概述四家共存格局与 200 形状回填结论。

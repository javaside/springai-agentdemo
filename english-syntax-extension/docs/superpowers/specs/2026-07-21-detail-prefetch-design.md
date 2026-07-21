# 成分详解预加载(detail prefetch,按句合批)设计

日期:2026-07-21(修订:预载由逐成分改为按句合批)
状态:已确认
前置:2026-07-20 缓存导入导出与纯缓存查看(详解预载写入的缓存经由该功能导出/导入共享)

## 背景与目标

现状:句子成分的详解(detail)只在用户点击该成分时才请求模型生成,一次点击一个成分一次调用。用户希望提供配置项,开启后详解随核心解析自动加载,免去逐个点击;配合已有的缓存导出/导入,多人可以分工把网页提前"翻译"完整(核心 + 全部详解)再共享缓存文件。**性能与 token 成本是首要约束**:逐成分预载一篇 50 句文章要 ~250 次调用,输入里反复重发"句子+核心分析+指令",太慢也太贵。

目标:

- 选项页新增全局开关「预载成分详解」,默认关。
- 开启后**整页全量**预载,粒度为**按句合批**:每句核心分析就绪后,发**一次**请求让模型生成该句**全部缺失成分**的详解(50 句 ≈ 50 次调用而非 250 次,输入 token 省 70-80%)。
- **存储格式零改动**:模型返回按成分拆开,逐个写入现有 detail 缓存键(规范化句文本 + schema 版本 + focus 区间的哈希);缓存结构、导出/导入文件格式、点击读取路径全部不变。
- 预载进度对用户可见(右下角进度 pill + popup),导出时机一目了然。
- 用户点击体验不回退:点击详解优先级永远压过后台预载。

非目标与否决项:

- **整页单次请求**:250 个详解 ≈ 7.5 万-12 万输出 token,超出主流模型单次输出上限(约 8k-16k)一个数量级,且长输出 JSON 截断率高、一崩整页重来。否决。
- **多句合批详解**(如 6 句 20-40 成分一发):输出顶到上限,不稳。否决,一句一发是输出预算内的最大合批。
- 不做"仅视口内预载"档位;不改会话完成判定(`isSessionComplete` 仍只看核心);纯缓存模式(无 profile)不预载。

## 关键事实(代码现状)

- 核心解析本就按块合批:`analyzeCore` 把未命中缓存的句子合成一次模型调用(一批上限 6 句),50 句文章冷启动 ≈ 8-12 次核心调用。
- 点击成分发出的 focus 就是 `core.components[i]` 自身的 `{startToken, endToken}`;content 侧 `SentenceRecord.core.components` 在句子就绪后即含全部成分——无需点击即可构造该句全部详解目标。
- 详解缓存键 = 规范化句文本 + schema 版本 + focus 区间(不含 profile/模型),与点击路径一致:预载写入的缓存点击必命中,且随导出文件(core+detail store)自动共享。
- 调度器为共享队列 + 优先级:`user-retry(0) > detail-click(1) > visible-core(2) > prefetch-core(3)`;`cancelDocument` 按文档清队列并中止在飞批次。
- content script 不能直接读 `chrome.storage`(`TRUSTED_CONTEXTS`),设置须经 SW 传递。

## 1. 开关与配置链路

- `ConfigRepository` 新增 key **`prefetchDetail.v1`**(boolean,默认 `false`),`getPrefetchDetail()` / `setPrefetchDetail()`,先例照 `cacheLimitMb.v1`。
- 选项页缓存区旁新增「预载成分详解」checkbox,提示:开启后每句解析完成即自动生成全部成分详解并入缓存(可随导出分享),token 消耗数倍于仅核心解析(以输出为主)。
- SW 处理 `START_SESSION` 时读一次设置,将 `prefetchDetail: boolean` 附在发给页面的 START_SESSION 命令上——**会话开始时快照**,中途改设置于下次「开始学习」生效。
- 无 profile(纯缓存模式)时 SW 强制下发 `false`。

## 2. 预载执行机制(按句合批)

### 协议

- 新增请求消息 **`PREFETCH_SENTENCE_DETAILS`**:`{ sentence: SentenceInput, core: CoreAnalysis }`(无 focus——目标是整句全部成分)。
- 新增响应消息 **`SENTENCE_DETAILS_RESULT`**:`{ succeeded: number, failed: number }`——只回计数;content 不渲染预载结果,详解正文只进缓存。
- 点击链路的 `ANALYZE_DETAIL`(单成分)完全不动。

### service 层新方法 `analyzeSentenceDetails`

1. 逐成分查 detail 缓存(键与点击路径同一构造),**只把缺失成分**放进 prompt;全部命中则零模型调用直接返回计数。
2. 新增整句详解 prompt(`buildSentenceDetailsPrompt(sentence, core, missingFocuses)`)与 schema(`SENTENCE_DETAILS_SCHEMA`:数组,每项含 focus 区间 + 与现有单成分详解同构的正文字段)。
3. 按成分逐项校验(复用现有单成分校验逻辑);不合格/缺失的成分**一次 repair 补拉**(只含这些成分);仍失败的计入 failed,留给点击兜底。
4. 合格项逐个 `putDetail` 写入**现有缓存键**——存储格式、导出文件、点击读取零改动。
5. 调度参数:优先级新档 **`prefetch-detail`(4,最低)**,sentenceCount=1,cacheKey 取"核心键+missing 区间集"派生,同句重复预载由调度器去重吸收。

### 调度器

- 仅追加优先级档 `prefetch-detail: 4`。可见句核心解析与用户点击永远压过后台预载。
- 原逐成分方案所需的"去重命中时提升排队条目优先级"**不再需要**:点击(单成分键)与预载(整句键)去重键不同,点击永远独立高优先级插队。极端情况下同一成分可能被点击与在飞的整句预载各生成一次,概率低、代价一次调用,可接受。

### content 侧新模块 `src/content/detail-prefetcher.ts`

- 单一职责:接收「句子就绪」通知(附 sentence + core)→ 按句入队 → 以**有界并发(2)**经注入的 `send` 函数发 `PREFETCH_SENTENCE_DETAILS` → 依响应计数,不渲染面板、不碰 `detailVersions`。
- session-controller 在句子转入 `ready` 相位处喂入(无论结果来自模型还是缓存命中)。缓存已有的成分在 SW 侧被剔除出 prompt——导入半成品缓存后开预载即**天然增量补缺**。
- 生命周期:
  - 暂停:停发新句;被 `cancelDocument` 打断的在飞句回滚为待发。
  - 恢复:续跑。
  - 停止:清空(SW 侧队列由既有 `cancelDocument` 顺带取消);已写缓存的部分保留,下次会话增量续传。
  - 块失效(stale):丢弃该句待发项;句子重新就绪后重新入队。

## 3. 进度可见性

- `SessionStatus` 加三个可选字段(仅预载开启时出现;关闭时序列化结果与现状完全一致):
  - `detailTotal?: number` — 已就绪句子的成分总数(随句子就绪增长);
  - `detailReady?: number` — 已确认入缓存的成分数(含预载前已命中缓存的);
  - `detailFailed?: number` — repair 后仍失败的成分数。
- 计数流:句子就绪时 `detailTotal += components.length`;`SENTENCE_DETAILS_RESULT` 返回后 `detailReady += succeeded`、`detailFailed += failed`。
- 同步校验器(照 `skipped` 先例):SW `isStatus`、content-script `isSessionStatus`。
- 进度 pill:核心阶段文案不变;核心完成后预载未完则显示「详解预载中 X/Y」+ spinner(X = ready+failed,Y = total);全部结束显示「✓ 解析完成」,若 `detailFailed > 0` 则为「✓ 解析完成(N 个详解失败)」。
- popup:主按钮状态机不变;运行中预载未完时副线显示「详解预载中 X/Y」。
- `isSessionComplete` 不变(只看核心)。

## 4. 错误处理

- 整句预载调用失败(超时/网络/校验全崩):该句全部缺失成分计入 `detailFailed`,页面不渲染错误;点击任一成分照常现场请求(天然重试)。调度器内建可重试错误 ×2 重试、429 按 retry-after 退避,照旧。
- 部分成分不合格:一次 repair 只补这些成分;仍失败的计 failed。
- 会话停止/标签页关闭:预载随 `cancelDocument` 静默终止,不产生用户可见错误。

## 5. 测试

- 单测:
  - `detail-prefetcher.test.ts`(新):按句入队、有界并发、暂停回滚、恢复续跑、stale 丢弃、计数上报。
  - analysis-service:`analyzeSentenceDetails` 全命中零调用、部分缺失只发缺失、逐项校验+repair 子集、写入键与点击路径一致(用点击路径读回断言)。
  - prompt/schema:整句详解 prompt 含且只含缺失成分;schema 校验。
  - SW:`PREFETCH_SENTENCE_DETAILS` 路由与优先级、无 profile 下发 `prefetchDetail: false`、`isStatus` 接受新字段。
  - config-repository:新 key 读写与默认值。
  - options:开关渲染与持久化。
  - session-controller:就绪触发喂入 prefetcher、状态含 detail 计数。
  - popup / pill:预载进行中与完成(含失败数)的文案分支。
- E2E 一条主链路:开启预载 → 开始会话 → 等 pill「✓」→ fake model 的整句详解调用数 == 含成分句子数 → 点击任一成分 → 面板渲染且模型调用数**零增长** → 导出文件 `detail` 条目数 == 成分总数。
- 真机验收(gitignored,不提交):浏览器 A 开预载用真实 DeepSeek 分析短文并导出;全新浏览器 B 不配模型导入后点击成分——详解面板从缓存渲染、fetch 探针计数为 0(完整验证多人协作预翻译场景)。

## 取舍记录

- **按句合批而非整页单发**:输出 token 上限(约 8k)决定一句(3-8 成分 ≈ 1.5-2.5k 输出)是稳定合批上限;整页单发超限一个数量级且失败重试成本爆炸。
- **按句合批而非逐成分**:调用数 250→50、输入 token 省 70-80%;代价是新增整句 prompt/schema 与逐项校验,点击与预载不共享去重键(极端下单成分重复一次调用,可接受)。
- 编排放 content 侧(方案 A)而非 SW 侧:成分数据、会话状态、暂停语义、进度上报链路全在 content,SW 保持无会话状态。
- 否决「导出前批量补齐」:缓存只存哈希键,拿不回原句 tokens,无法构造 prompt;也不满足「随成分一起加载」的诉求。
- 失败成分不自动补偿重试:点击即重试入口,保持简单;pill 完成文案带失败数,导出前可感知缓存完整度。
- 原逐成分方案中的"调度器去重优先级提升"随合批一并移除——不再存在键冲突场景。

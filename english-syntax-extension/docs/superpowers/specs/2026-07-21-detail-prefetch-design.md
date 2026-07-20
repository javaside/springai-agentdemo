# 成分详解预加载(detail prefetch)设计

日期:2026-07-21
状态:已确认
前置:2026-07-20 缓存导入导出与纯缓存查看(详解预载写入的缓存经由该功能导出/导入共享)

## 背景与目标

现状:句子成分的详解(detail)只在用户点击该成分时才请求模型生成。用户希望提供一个配置项,开启后详解随核心解析自动加载,免去逐个点击;配合已有的缓存导出/导入,多人可以分工把网页提前"翻译"完整(核心 + 全部详解)再共享缓存文件。

目标:

- 选项页新增全局开关「预载成分详解」,默认关(避免普通用户无感知地多花 3-8 倍 token)。
- 开启后,**整页全量**预载:会话内每句核心分析就绪,立即把该句全部成分的详解以最低优先级排入现有调度队列。
- 预载进度对用户可见(右下角进度 pill + popup),导出时机一目了然。
- 用户点击体验不回退:点击详解永远压过后台预载。

非目标:

- 不做"仅视口内预载"档位(YAGNI,一档全量)。
- 不改会话完成判定(`isSessionComplete` 仍只看核心解析;「恢复网页原文」不被详解拖住)。
- 纯缓存模式(无 profile)不预载——没有模型可调;点击走既有 `lookupDetail`。

## 关键事实(代码现状)

- 点击成分发出的 focus 就是 `core.components[i]` 自身的 `{startToken, endToken}`;content 侧 `SentenceRecord.core.components` 在句子就绪后即含全部成分——**无需点击即可构造全部详解请求**。
- 详解缓存键 = 规范化句文本 + schema 版本 + focus 区间(不含 profile/模型),与点击路径完全一致:预载写入的缓存点击必命中,且随导出文件(core+detail store)自动共享。
- 调度器为共享队列 + 优先级:`user-retry(0) > detail-click(1) > visible-core(2) > prefetch-core(3)`;按 `documentId+cacheKey` 去重复用在飞 promise;`cancelDocument` 按文档清队列并中止在飞批次。
- content script 不能直接读 `chrome.storage`(`TRUSTED_CONTEXTS`),设置须经 SW 传递。

## 1. 开关与配置链路

- `ConfigRepository` 新增 key **`prefetchDetail.v1`**(boolean,默认 `false`),`getPrefetchDetail()` / `setPrefetchDetail()`,先例照 `cacheLimitMb.v1`。
- 选项页缓存区旁新增「预载成分详解」checkbox,提示:开启后每句解析完成即自动加载全部成分详解,token 消耗约为仅核心解析的 3-8 倍;详解进入缓存,可随导出文件分享。
- SW 处理 `START_SESSION` 时读一次设置,将 `prefetchDetail: boolean` 附在发给页面的 START_SESSION 命令上——**会话开始时快照**,中途改设置于下次「开始学习」生效。
- 无 profile(纯缓存模式)时 SW 强制下发 `false`。

## 2. 预载执行机制

### 协议

- `ANALYZE_DETAIL` 请求加可选字段 `prefetch?: true`。SW 据此以 **`prefetch-detail`** 优先级调用 `analysisService.analyzeDetail`(缓存查询、校验、repair 重试、写回全部复用)。响应仍为 `DETAIL_RESULT` / `ERROR`。

### 调度器

- 优先级表追加最低档 **`prefetch-detail: 4`**。可见句核心解析永远压过后台详解预载。
- **去重优先级提升**:`schedule()` 命中重复(`documentId+cacheKey`)且新请求优先级更高(数值更小)时,就地提升**排队中**条目的优先级;已在飞的不受影响。防止用户点击挂在最低优先级的预载条目后面。

### content 侧新模块 `src/content/detail-prefetcher.ts`

- 单一职责:接收「句子就绪」通知 → 把该句 `core.components` 逐成分入队 → 以**有界并发(4)**经注入的 `send` 函数发 `ANALYZE_DETAIL(prefetch: true)` → 只计数,不渲染面板、不碰 `detailVersions`。
- session-controller 在句子转入 `ready` 相位处喂入(无论结果来自模型还是缓存命中)。缓存已有的详解在 SW 侧直接命中、零模型调用——导入半成品缓存后开预载即**天然增量补缺**。
- 生命周期:
  - 暂停:停发新请求;被 `cancelDocument` 打断的在飞项回滚为待发。
  - 恢复:续跑。
  - 停止:清空(SW 侧队列由既有 `cancelDocument` 顺带取消);已写缓存的部分保留,下次会话增量续传。
  - 块失效(stale):丢弃该句待发项;句子重新就绪后重新入队。
- 点击与预载并存:点击仍走 `detail-click` 高优先级 + 面板 loading;同成分预载在飞时调度器去重共享同一响应,不重复调模型。

## 3. 进度可见性

- `SessionStatus` 加三个可选字段(仅预载开启时出现;关闭时序列化结果与现状完全一致):
  - `detailTotal?: number` — 已就绪句子的成分总数(随句子就绪增长);
  - `detailReady?: number` — 预载成功(含缓存命中)的成分数;
  - `detailFailed?: number` — 重试用尽仍失败的成分数。
- 同步校验器(照 `skipped` 先例):SW `isStatus`、content-script `isSessionStatus`。
- 进度 pill:核心阶段文案不变;核心完成后预载未完则显示「详解预载中 X/Y」+ spinner(X = ready+failed,Y = total);全部结束显示「✓ 解析完成」,若 `detailFailed > 0` 则为「✓ 解析完成(N 个详解失败)」。
- popup:主按钮状态机不变;运行中预载未完时副线显示「详解预载中 X/Y」。
- `isSessionComplete` 不变(只看核心)。

## 4. 错误处理

- 单成分预载失败:只计入 `detailFailed`,页面不渲染错误;点击该成分时照常现场请求(天然重试)。调度器内建可重试错误 ×2 重试、429 按 retry-after 退避,照旧。
- 会话停止/标签页关闭:预载随 `cancelDocument` 静默终止,不产生用户可见错误。

## 5. 测试

- 单测:
  - `detail-prefetcher.test.ts`(新):入队、有界并发、暂停回滚、恢复续跑、stale 丢弃、计数上报。
  - 调度器:去重命中时优先级提升(排队条目生效、在飞条目不动)。
  - SW:`prefetch` 标志 → `prefetch-detail` 优先级;无 profile 下发 `prefetchDetail: false`;`isStatus` 接受新字段。
  - config-repository:新 key 读写与默认值。
  - options:开关渲染与持久化。
  - session-controller:就绪触发喂入 prefetcher、状态含 detail 计数。
  - popup / pill:预载进行中与完成(含失败数)的文案分支。
- E2E 一条主链路:开启预载 → 开始会话 → 等 pill「✓」→ fake model 的 detail 调用数 == 成分总数 → 点击任一成分 → 面板渲染且 detail 调用数零增长 → 导出文件 `detail` 条目数 > 0。
- 真机验收(gitignored,不提交):浏览器 A 开预载用真实 DeepSeek 分析短文并导出;全新浏览器 B 不配模型导入后点击成分——详解面板从缓存渲染、fetch 探针计数为 0(完整验证多人协作预翻译场景)。

## 取舍记录

- 编排放 content 侧(方案 A)而非 SW 侧:成分数据、会话状态、暂停语义、进度上报链路全在 content,SW 保持无会话状态;代价是每页多发 150-400 条 runtime 消息,由有界并发(4)控制在可忽略水平。
- 否决「导出前批量补齐」:缓存只存哈希键,拿不回原句 tokens,无法构造 detail prompt;也不满足「随成分一起加载」的诉求。
- 失败成分不自动补偿重试:点击即重试入口,保持简单;pill 完成文案带失败数,导出前可感知缓存完整度。

# 缓存键统一化设计(Cache Key Unification)

日期:2026-07-19
分支:codex/english-syntax-extension-next(不合并主干)
状态:用户已确认设计,待实现

## 背景与目标

现状缓存键 = 句子 + profile.id + 服务商域名 + 模型名 + 提示词版本 + schema 版本。
后四项任一变化(升提示词版本、换模型/服务商、删建 profile)都会作废全部缓存,
用户重开旧页面被迫重新调模型,费时费钱。而缓存里存的是我们自己 schema 的
结构化 JSON,与模型和提示词无关——格式本来就是统一的。

**目标**:缓存只跟「哪句话的分析结果」绑定。升提示词版本、换模型、增删改
profile 一律不失效;想要新效果用「重新解析」手动刷新。唯一保留的全量失效
开关是 `CORE_SCHEMA_VERSION`(存储格式真正不兼容时才升,预期极少)。

**已实测的前提**:IndexedDB 缓存持久、50 MB LRU、重开页面 0.0s 命中
(.superpowers/acceptance/verify-cache-revisit.mjs)。容量与持久性无问题,
本设计只改键的构成与刷新通道。

## 关键发现(设计动因之一)

「重新解析」(REANALYZE_VISIBLE)目前**不绕过缓存**:content 侧
`invalidateBlock` 只重置页面状态,后续 ANALYZE_CORE 仍先查缓存。今天它显得
有效只是因为换 profile 会换键。键统一后若不补旁路,重新解析将永远命中旧
缓存,失去唯一的手动刷新手段。因此旁路是本设计的必要组成。

## 设计

### 1. 缓存键构成(src/background/analysis-service.ts / analysis-cache.ts)

| store      | 键的组成(SHA-256 前的身份数组)                                                       |
| ---------- | ------------------------------------------------------------------------------------ |
| core       | `["core", 句子归一化文本, CORE_SCHEMA_VERSION]`                                      |
| detail     | `["detail", 句子归一化文本, focus.startToken, focus.endToken, CORE_SCHEMA_VERSION]`  |
| correction | `["correction", 句子归一化文本, 页面URL, 句子实例ID, 反馈原文, CORE_SCHEMA_VERSION]` |

移出:`profile.id`、`providerOrigin`、`model`、`CORE_PROMPT_VERSION`、
`DETAIL_PROMPT_VERSION`。提示词版本常量本身保留(仍用于提示词构建处的
语义标识),只是不再参与键。`normalizedSentenceIdentity` 不再拼 profile.id,
只做空白归一化(trim + 连续空白折叠为单空格),与现有归一化规则一致。

### 2. 强制刷新通道(bypassCache)

- `ANALYZE_CORE` 请求消息新增可选字段 `bypassCache?: true`(协议版本不变,
  旧消息不带该字段行为不变)。
- popup「重新解析」→ REANALYZE_VISIBLE → content `reanalyzeVisible()` 重新
  入队的 ANALYZE_CORE 请求带 `bypassCache: true`。
- `analyzeCore` 收到 bypass 时跳过 `cache.get`,直接调模型;校验通过后照常
  `putCore` 覆盖旧条目。修复(repair)与失败路径不变。
- 详解与纠错不加旁路:核心重析后成分 focus 区间变化会使详解键自然失效;
  区间未变说明拆解未变,旧详解仍正确。纠错键含反馈原文,每次反馈即新键。

### 3. 读时兜底(不变)

`validateCachedCore` 继续在读取时校验缓存值,不合当前 schema 即视为未命中、
逐句重新请求。渲染层渐进兼容(缺 translation 两行式、英文角色兜底色)不动。
存储格式真正不兼容时升 `CORE_SCHEMA_VERSION`,这是唯一的全量失效开关。

### 4. 一次性迁移

IndexedDB `DATABASE_VERSION` 1 → 2,`upgradeneeded` 回调里对已存在的三个
store 执行清空(新装用户走原有 createObjectStore 分支)。旧键条目在新键下
永远查不到,留着只挤占 LRU 空间,清掉即"最后失效一次"。

### 5. 附带清理

- `CacheRecord.profileId` 字段保留(统计/排查用,不参与键与查询)。
- `AnalysisCache.clearByProfile` 无生产调用方,删除(连同其单测)。
- 选项页「清空全部缓存」(CLEAR_CACHE)语义不变。

### 6. 测试与验收

单测:

- 键构成:换 profile.id/模型/服务商/提示词版本 → 键不变;换 schema 版本或
  句子文本/focus → 键变。
- analyzeCore bypassCache:跳过读、模型结果覆盖写回;不带标记时行为不变。
- 迁移:版本 2 打开旧库后三个 store 为空(fake-indexeddb 可覆盖)。

E2E(假模型服务器):重新解析触发第二次模型请求且页面更新;不重新解析时
二次开页零请求(命中缓存)。

真机验收(真实 DeepSeek,harness 模式,key 从环境变量读、日志脱敏):

1. 分析页面 → 删除并重建同配置 profile → 重开页面命中缓存(≈0s);
2. 换模型(如 deepseek-chat ↔ deepseek-v4-flash)→ 重开页面命中缓存;
3. 点「重新解析」→ 观察到真实二次请求且渲染刷新。

## 明确的取舍

提示词/模型的改进**不自动追溯**已缓存句子;用户用「重新解析」按需刷新。
这是用户明确选择的稳定优先策略(2026-07-19 对话确认)。

# 缓存导入导出与纯缓存查看设计(Cache Import/Export & Cache-Only Viewing)

日期:2026-07-20
分支:codex/english-syntax-extension-next(不合并主干)
状态:用户已确认设计,待实现

## 背景与目标

翻译(模型分析)慢且费钱。缓存键已统一为「句子归一化文本 + CORE_SCHEMA_VERSION
(+focus/纠错上下文)」(见 2026-07-19-cache-key-unification-design.md),同一句话
的分析结果与人、profile、模型无关——天然可共享。

**目标**:

1. 缓存可导出为文件、导入时与本地合并,多人各自生成缓存后互相导入,减少重复调模型;
2. popup 新增「查看缓存」入口:未配置模型的用户也能纯缓存查看已覆盖页面
   (「别人导出 → 我导入 → 无 key 直接看」的闭环)。

**已确认的决策**:

- 合并策略:**本地优先**——键相同跳过,只补本地没有的;重复导入幂等、无副作用。
- 导出范围:**core + detail**。correction 键含页面 URL+句子实例 ID+反馈原文,
  跨人几乎不可能命中,不导出。
- 架构:**选项页直连 IndexedDB**(方案 A)。选项页与 service worker 同源,
  复用现有 `AnalysisCache` 模块打开同一个库;大文件不经消息通道
  (走 `chrome.runtime.sendMessage` 序列化几十 MB 不可控),零协议改动。
- 「查看缓存」入口:**仅未配置模型时显示**。配置了模型的用户点普通「开始」
  本来就先查缓存,命中秒出,无需独立入口。

## 1. 文件格式

明文 JSON 单文件,下载名 `english-syntax-cache-YYYYMMDD.json`:

```json
{
  "format": "english-syntax-cache",
  "formatVersion": 1,
  "schemaVersion": 1,
  "exportedAt": "2026-07-20T10:00:00.000Z",
  "core": [{ "key": "<sha-256 hex>", "value": { "...": "核心拆解结果" } }],
  "detail": [{ "key": "<sha-256 hex>", "value": { "...": "成分详解结果" } }]
}
```

- 只导 `key` + `value`,不导 `profileId` / `lastAccessedAt`(无共享价值,少暴露本地信息)。
- `schemaVersion` 写头部;导入时与当前 `CORE_SCHEMA_VERSION` 不一致**整体拒绝并提示**
  (旧版本条目在新键下永远查不到,静默跳过不如明说)。
- 明文便于检查调试;暂不引入 gzip(YAGNI,需要再加)。

## 2. 导出/导入流程(选项页直连库)

新模块 `src/options/cache-transfer.ts`(纯逻辑,便于单测):

- **导出** `exportCache(cache)`:读全部 core+detail 记录,生成上述 JSON 对象。
  选项页把它序列化为 Blob 触发下载。
- **导入** `importCache(cache, file 内容)`:
  1. 校验头部:`format`/`formatVersion`/`schemaVersion` 任一不符 → 整体拒绝,返回错误;
  2. 逐条处理:键非 64 位十六进制或 value 结构校验不过 → 计入「无效丢弃」;
     本地已有该键 → 计入「已有跳过」;其余走现有 `putCore`/`putDetail` 写入,
     `profileId` 记为 `"imported"`,时间戳按正常写入路径打,计入「新增」;
  3. 写完触发现有 LRU 限额(超 50MB 按最久未访问淘汰),返回三分类计数。
- **多人合并**:无需专门工具——依次导入多个文件即可,本地优先跳过保证幂等。
- **并发安全**:选项页与 SW 同源同库同 `DATABASE_VERSION`,IndexedDB 事务自带
  并发保护,无需协调。

## 3. UI 与错误处理(选项页)

「分析缓存」区(现有统计+上限+清空按钮旁)加两个按钮:**导出缓存**、**导入缓存**
(file input,accept `.json`)。

- 导入完成显示结果行:「新增 X 条,已有跳过 Y 条,无效丢弃 Z 条」,并刷新缓存统计。
- 错误(文件非法 JSON、格式头不符、schema 版本不匹配、写库失败)落同一状态行,
  中文提示,不弹窗。
- 安全兜底:即使畸形 value 混进库,现有读时校验(`validateCachedCore`)也会把它
  当未命中降级,渲染层永不接触坏数据。

## 4. 纯缓存查看模式(无模型配置可用)

现状:`START_SESSION` 不要求 profile;真正卡住的是 SW 里 `ANALYZE_CORE`/
`ANALYZE_DETAIL` 入口的 `CONFIG_MISSING` 检查。

**入口(popup)**:未配置模型且页面受支持时,主按钮从「去配置模型」改为
**「查看缓存」**(发送普通 START_SESSION),副线保留「尚未配置模型」提示并附
「去配置」入口;其余状态逻辑不变。

**Service worker**:`ANALYZE_CORE` / `ANALYZE_DETAIL` 在 `profileFor` 返回空时
改走纯缓存查找,不再返回 `CONFIG_MISSING`:

- core:只查缓存,命中的句子照常返回;未命中的句子不返回、不算失败——内容脚本
  让它们保持原文样式(与未分析一致),不标红、不出重试。
- detail:命中返回;未命中返回新错误码 `NO_CACHE`,详解面板显示
  「该成分暂无缓存详解,配置模型后可获取」。
- 纠错(反馈)无配置时维持现状(必须调模型,仍返回 `CONFIG_MISSING`)。
- 实现:`CachedAnalysisService` 加独立的 `lookupCore`/`lookupDetail` 纯缓存方法,
  不进请求调度器、不需要 profile;返回前跳过脱敏步骤(无密钥可脱)。

**状态与进度**:`SessionStatus` 加可选 `skipped` 计数(未命中句数)。纯缓存会话
popup 进度文案显示「缓存命中 X/Y 句」;`ready + skipped = discovered` 视为会话
完成。配置了模型的正常会话不产生 skipped,行为完全不变。

## 5. 测试与验收

**单测**(fake-indexeddb):

- 导出:格式正确、含 core+detail、不含 correction;
- 导入:三分类计数正确(新增/已有跳过/无效丢弃);schemaVersion 不匹配整体拒绝;
  二次导入全跳过(幂等);
- lookup:`lookupCore`/`lookupDetail` 命中/未命中;SW 无 profile 分支
  (core 部分命中、detail `NO_CACHE`、纠错仍 `CONFIG_MISSING`)。

**E2E**(假模型服务器):

1. 页面分析 → 导出 → 清空缓存 → 导入 → 重开页面零模型请求;
2. 无 profile + 预置缓存 → popup 显示「查看缓存」→ 页面渲染且零模型请求;
   未命中句保持原文。

**真机验收**(可选,复用现有 harness,key 从环境变量读、日志脱敏):
在一个浏览器 profile 生成缓存并导出,换全新 profile **不配置模型**导入,
点「查看缓存」0 秒渲染、fetch 探针计数为零。

## 明确的取舍

- 导入的 value 信任边界:结构校验(导入时)+ 读时校验(渲染前)双层兜底,
  不做来源签名/防篡改(共享场景是熟人互传,YAGNI)。
- 纯缓存模式不提供「部分命中后补调模型」——配置模型后走正常流程即可。

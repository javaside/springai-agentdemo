# 复合句处理增强设计(并列分句建模 + 详解面板可视化)

日期:2026-07-17
状态:已确认(用户逐节认可)
背景:核心标注是单层扁平结构——主从复合句的从句作为整体成分标注(5 类从句角色),内部结构只在点击详解里以纯文字展示;并列句无建模,被摊平成「主语/谓语/连词?/主语/谓语」的重复序列,无分句边界。用户反馈「语句成分划分比较简单」。方向经浏览器 mockup 确认:A(详解面板可视化,轻)+ C(并列分句建模,中);不做 B(正文两层嵌套,视觉密度与「外观自然」冲突)。

## 1. 数据模型(C:并列分句建模)

- `GrammarRole`(src/shared/grammar.ts)增加两个成员:
  - `COORDINATE_CLAUSE = "COORDINATE_CLAUSE"`,标签「并列分句」,颜色 `#0d9488`(青绿);
  - `CONJUNCTION = "CONJUNCTION"`,标签「并列连词」,颜色 `#6b7280`(灰,与其他中性成分同色)。
- `GRAMMAR_LABELS` 与 `ROLE_COLORS`(src/content/learning-block.ts)同步补全——两者都是 `Readonly<Record<GrammarRole, string>>`,漏写编译不过。
- 校验器(src/language/analysis-validator.ts:20)的角色集合从枚举 `Object.values` 派生,新角色自动被接受,无需改校验逻辑。
- **不升 `CORE_SCHEMA_VERSION`**(components 数据形状不变),**升 `CORE_PROMPT_VERSION` 1→2**(src/shared/versions.ts):缓存键含 promptVersion(src/background/analysis-cache.ts),旧核心缓存自动 miss、句子按需重新分析。代价:用户已看过的句子会各多一次模型调用;详解缓存不受影响。

## 2. 提示词(C)

`buildCorePrompt`(src/background/prompts.ts)增加复合句规则(角色枚举列表本就从 `Object.values(GrammarRole)` 生成,自动含新角色):

- 并列句(两个及以上可独立成句的分句由并列连词/分号连接):每个分句整体标 `COORDINATE_CLAUSE`,translation 为该分句的完整中文翻译;并列连词(and/but/or/so 等,含逗号+连词组合中的连词)单独标 `CONJUNCTION`。
- 主从复合句维持现状:从句整体标 5 类从句角色之一,不拆内部。
- 简单句不受影响:禁止把单一主谓结构包装成 `COORDINATE_CLAUSE`。

渲染期编号:同一句内出现 ≥2 个 `COORDINATE_CLAUSE` 时,正文成分名显示「并列分句①」「并列分句②」…(learning-block 渲染时按出现顺序计数;编号不进数据模型、不进缓存)。

## 3. 详解面板可视化(A)

点击任意成分(并列分句、从句、普通成分通用),详解面板在现有内容之上新增**标注区**,渲染 `DetailAnalysis.structures[]`(数据已含 `startToken/endToken/role/explanation`,协议不动):

- 每个 structure 渲染为两行式内联块:上行「序号+成分名」小字,下行英文原文(按 token 区间从句子 Token 表取词,含前导空格规则,首 token 去前导空格与正文一致),英文带 1.5px 彩色下划线,风格与正文标注一致;
- 标注区下方按同一序号(①②…)逐条列出各 structure 的 `explanation`;
- 原有「语法点」(grammarPoints)与「整体讲解」(explanation)区块保留在解释列表之下,顺序:标注区 → 逐条解释 → 语法点 → 整体讲解;
- 颜色:`role` 是模型自由文本(如「引导词」「系动词」),按中文名精确匹配 `GRAMMAR_LABELS` 的值反查 `ROLE_COLORS`;匹配不到统一用灰 `#6b7280`;
- 容错:
  - structure 的 token 区间越界、start>end、或落在句子 Token 表之外:跳过该条的标注块,但其解释仍按序号出现在解释列表(序号连续,标注区少一块不错位——序号以列表为准,标注块带同一序号);
  - `structures` 为空数组:不渲染标注区与解释列表,面板退回现状(语法点+整体讲解);
  - 详解请求/缓存/开合切换/还原逻辑全部不动(上一轮已修复的单面板、toggle、零残留语义保持)。

## 4. 测试与验收

单测(vitest + happy-dom):

- grammar:新角色在 `GRAMMAR_LABELS`/`ROLE_COLORS` 中存在且色值正确;校验器接受 `COORDINATE_CLAUSE`/`CONJUNCTION`;
- prompts:`buildCorePrompt` 输出含两个新角色名与并列句规则文案;
- versions/cache:`CORE_PROMPT_VERSION === 2`;promptVersion 变化导致缓存 key 不同(cache miss);
- learning-block:多并列分句渲染期编号①②;详解标注区——区间→英文文本还原、序号与解释对应、中文角色名反查颜色、未知角色灰色、越界条目跳标注保解释、空 structures 退回现状;
- 既有回归:单面板/toggle/还原零残留测试全部保持绿。

E2E(Playwright + fake server,tests/e2e/extension.spec.ts):

- fake server 对指定句子返回含 2 个 `COORDINATE_CLAUSE` + 1 个 `CONJUNCTION` 的核心分析:断言正文出现「并列分句①/②」「并列连词」标注与配色;
- 点击并列分句 → fake 详解返回含 3 个 structures:断言面板出现标注区(两行式、下划线)与 ①②③ 对应解释,语法点/整体讲解仍在;再点收起、STOP 还原零残留不回归。

验收:真实 DeepSeek key + headed 浏览器,找含并列句与主从复合句的英文页面,截图核对正文分句标注与详解面板标注区观感(浅色+深色页面)。

## 不做的事(YAGNI)

- 不做正文两层嵌套标注(B 方向);
- 不做详解面板标注区的再点击下钻(面板内标注纯展示);
- 不改 5 类从句角色的定义与正文渲染;
- 不改详解协议(`DetailStructure` 结构)与详解缓存;
- 不做并列分句颜色的用户自定义。

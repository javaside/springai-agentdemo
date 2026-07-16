# 扩展 UX 精简设计(popup / 标注样式 / 进度反馈)

日期:2026-07-16
状态:已确认(用户逐节认可)
背景:真机使用反馈——popup 竖长难看难操作;正文成分卡片下划线过粗、灰底破坏原页面观感;解析进行中页面无反馈,用户不知道是否在处理、容易重复点「开始学习」。目标:简单好用、外观自然。

## 1. 极简 popup

popup 只保留三个元素,自上而下:

1. **标题行**:左侧「英语句法伴读」,右侧 ⚙︎ 齿轮按钮(`chrome.runtime.openOptionsPage()`)。
2. **全宽主按钮**(唯一动作),按会话状态变形:
   - `stopped`(未开始):`开始学习` → 点击发 START_SESSION;
   - `running`:`解析中… n/m(点击暂停)`,n = ready+failed,m = discovered → 点击发 PAUSE_SESSION;
   - `paused`:`继续学习` → 点击发 START_SESSION(恢复);
   - 全部完成(running 且 n=m 且 m>0,或后台判定完成):`恢复网页原文` → 点击发 STOP_SESSION;
   - 未配置模型:`去配置模型` → 打开设置页;
   - 不支持的页面(chrome:// 等):按钮禁用,小字行说明原因。
3. **小字行**:当前启用模型 `名称 · model`;操作失败时此行显示错误一句话。

**删除**:模型下拉(切换模型去设置页做)、4 个指标卡(被按钮上的 n/m 取代)、「重新解析可视区域」按钮(只删 popup 入口,REANALYZE_VISIBLE 协议与 content 端处理保留;滚动本来就自动解析新内容)、「停止并恢复网页」独立按钮(并入主按钮完成态)、脚注说明。

**设置页补充**:已保存配置列表增加「启用」操作(radio 或按钮),调用现有 `ConfigRepository.setActiveProfile`;当前启用项有可见标记。这是模型切换从 popup 挪走后的唯一入口。

**Bug 修复**:popup.css 的 `@media (max-width: 320px), (min-resolution: 1.8dppx)` 会在 Retina 屏上把 body 宽度改成 100%,导致弹框塌成细竖条——删除 `min-resolution` 条件(这就是「一条竖的弹框」的直接原因之一)。

防重复:running 状态下主按钮语义是「暂停」,不存在重复 START;按钮在请求 in-flight 时禁用。

## 2. 彩色细下划线标注(正文)

- 成分去掉灰底、圆角、内边距,回归纯文本流;字体/字号/颜色继承原网页。
- 下划线:`1.5px solid`,按成分类型固定色,约 60% 不透明度;成分名(小字)同色;译文中性色(继承 + 透明度)。
- 色表(GRAMMAR_LABELS 的 role 全集按此映射,未列出的 role 用灰):
  - 主语 `#2563eb` 蓝;谓语 `#dc2626` 红;宾语 `#059669` 绿;状语 `#d97706` 橙;定语 `#7c3aed` 紫;补语/表语 `#0891b2` 青;其他(同位语、插入语、独立成分等)`#6b7280` 灰。
- 成分间距 `column-gap: 0.5em`(行间 row-gap 维持现状);成分内首 token 去前导空格的逻辑保留。
- 三层结构(成分名/英文/译文)与点击成分看详解的交互不变;hover 反馈改为轻微透明度变化(无底色)。
- 深浅色页面通用:直接用上述色值+透明度,不再用 currentColor 混色底。

## 3. 右下角进度胶囊(页面内)

- content script 注入固定定位胶囊(自有 shadow DOM 自定义元素,`position: fixed; right/bottom ≈ 16px`,z-index 取大值),不污染宿主页面样式。
- 状态机:
  - 解析中:`⟳ 句法解析中 n/m`(spinner 动画;`prefers-reduced-motion` 时不转);
  - 暂停:`⏸ 已暂停`;
  - 完成(n=m):`✓ 解析完成`,失败>0 则 `✓ 完成,k 句失败`;2.5s 后淡出移除;
  - STOP_SESSION / 恢复原文:立即移除;
  - 滚动触发新句子进入队列(m 增大):胶囊重新出现。
- 数据源:SessionController 本来就运行在 content script 内(`ContentScriptRouter` 创建,`onStatus` 回调已存在,现用于向 SW 转发状态)。胶囊直接订阅同一个 `onStatus` 回调,零新增消息协议。
- 胶囊纯展示,不可点击(YAGNI)。

## 4. 错误处理

- popup 操作失败:小字行显示一句话错误(沿用现有 notice 语义)。
- 解析失败句:正文内现有「失败+重试」UI 不变;胶囊只汇总失败计数。
- 胶囊对 SESSION_STATUS 缺失/乱序保持幂等:以最新收到的计数为准。

## 5. 测试与验收

- 单测(vitest + happy-dom):
  - popup:五种按钮形态文案与动作映射;in-flight 禁用;未配置/不支持页面分支;
  - 胶囊:出现/计数更新/完成淡出/暂停/停止移除;
  - 标注:role→颜色类映射;无底色断言(不再有 background);
  - 设置页:「启用」操作调用 setActiveProfile、当前启用项标记。
- E2E(Playwright + fake server):开始→胶囊出现并计数→完成→淡出;popup 按钮三态流转;设置页切换启用配置后新会话用新配置。
- 验收:真实 DeepSeek key + headed 浏览器截图核对观感(浅色+深色页面各一)。

## 不做的事(YAGNI)

- 不做胶囊点击暂停/展开详情;
- 不做每段落级处理指示(问题 3 用户选了 A 方案);
- 不做下划线颜色自定义设置;
- 本轮不动详解面板(detail)的样式。

# 设计 spec：emoji 组合序列宽度口径修正（keycap / VS16）

- 日期：2026-10-09
- 状态：已核准（用户拍板：内容一字不动，只修宽度建模）
- 关联事件：2026-10-09 08:50 Terminal.app 崩溃（见「背景」）

## 背景（实测事实与证据）

用户在 xibaojun 项目粘贴文案 `🍺9月7日周一赛事🍺⚽足球赛事9️⃣场⚽🏀篮球赛事8️⃣场🏀⏰晚上 22:00点⏰ `
到输入框后「输入框乱了，再打字终端就崩溃」。

1. **崩溃主体是 Terminal.app 不是 JVM**：`~/Library/Logs/DiagnosticReports/Terminal-2026-10-09-085041.ips`
   —— `EXC_BAD_ACCESS / SIGBUS`，主线程 `_platform_memmove` 越界写（目标块紧贴 MALLOC_SMALL 区尾
   `b88c00000-b89000000`，直撞 commpage 保留页）。code-tui 进程随 pty 消失被 SIGHUP 带走。
2. **文案宽度实测（Python 复刻 CharWidth 逐码点求和 vs wcwidth）**：
   CharWidth 累计 **65 列**，wcwidth（终端口径）**67 列**，差 2 列全部来自 `9️⃣` 与 `8️⃣`
   （`U+0039/U+0038 + U+FE0F + U+20E3`，即 keycap 序列）。
3. **wcwidth 事实标准实测**：`wcwidth('⚠')=1`、`wcswidth('⚠️')=2` —— VS16 变体选择符把
   text-presentation 符号变成 2 列 emoji 呈现；macOS Terminal.app 与此一致。
4. **四层链路全漏**（源码证据，tamboui 0.4.0）：
   - `CharWidth.of(codePoint)`：U+FE0F 与 U+20E3 均在零宽表 → 逐码点算，keycap 序列按 1 列；
   - `Buffer.setString`：零宽码点走 combining 分支拼进 base cell（symbol="9️⃣" 但 cell 占 1 列），
     C1（U+0080–9F）不替换（只兜 C0+DEL）→ 原样写终端被当控制码执行；
   - `CharWidth.substringByWidth`：有 ZWJ 序列保护，**无** keycap/VS16 保护 → 折行可切半序列；
   - `CharWidth.of(String)` 前缀宽度（输入框光标列）同上逐码点。
5. **ZWJ 序列无需修**：`CharWidth.of(String)` 对 `👨ZWJ👩ZWJ👧` 算 2（ZWJ 及其后码点宽度全跳），
   与终端单 glyph 2 列一致；`Buffer.setString` 的 ZWJ 分支把后续码点拼进 base cell，布局一致。
6. **patch 模块 shadow 机制成熟**：`springai-tamboui-inline-patch` 已 shadow
   `InlineTuiRunner` / `InlineViewport` / `EventParser` / `InlineDisplay` 等，运行期靠
   code-tui 依赖序（patch jar 排在 tamboui-core/tui 之前）优先加载，无需额外接线。
7. **VS16 变宽 base 清单**（BMP，wcwidth 实测 `w(cp)==1 && w(cp+FE0F)==2`，71 区间 118 字符，
   对齐 Unicode emoji-data「Emoji=Yes & Emoji_Presentation=No」）：
   `0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x2194-0x2199, 0x21A9-0x21AA, 0x2328,
   0x23CF, 0x23ED-0x23EF, 0x23F1-0x23F2, 0x23F8-0x23FA, 0x24C2, 0x25AA-0x25AB, 0x25B6,
   0x25C0, 0x25FB-0x25FC, 0x2600-0x2604, 0x260E, 0x2611, 0x2618, 0x261D, 0x2620,
   0x2622-0x2623, 0x2626, 0x262A, 0x262E-0x262F, 0x2638-0x263A, 0x2640, 0x2642,
   0x265F-0x2660, 0x2663, 0x2665-0x2666, 0x2668, 0x267B, 0x267E, 0x2692, 0x2694-0x2697,
   0x2699, 0x269B-0x269C, 0x26A0, 0x26A7, 0x26B0-0x26B1, 0x26C8, 0x26CF, 0x26D1, 0x26D3,
   0x26E9, 0x26F0-0x26F1, 0x26F4, 0x26F7-0x26F9, 0x2702, 0x2708-0x2709, 0x270C-0x270D,
   0x270F, 0x2712, 0x2714, 0x2716, 0x271D, 0x2721, 0x2733-0x2734, 0x2744, 0x2747,
   0x2763-0x2764, 0x27A1, 0x2934-0x2935`。
   （wcwidth 原始清单还含 `0x23, 0x2A, 0x30-39, 0x2B05-2B07`：前三段是无 emoji 呈现的
   keycap base，只在 `+FE0F?+20E3` 组合里变宽，归 keycap 规则；0x2B05-2B07 在
   CharWidth 里已是 2 宽，无需特判。）

## 目标

1. **内容一字不动**：输入框存储、发给模型的正文、剪贴板语义完全不变（用户否决了改写内容的方案）。
2. **宽度口径与终端一致**：keycap 与「文本符号+VS16」组合按 2 列建模，输入框渲染、光标定位、
   折行、所有 live 区组件不再错位。
3. **C1 显示兜底**：U+0080–9F 在 cell 层替换为空格显示（同现有 C0/DEL 处理），避免终端把
   C1 当控制码执行；存储与发送的原文不变。
4. 折行不切半组合序列（`substringByWidth` 序列感知）。

## 非目标

- 不修 Terminal.app 自身 bug（SIGBUS 是它的绘制缺陷，我们只消除触发面）。
- 不改 `TextAreaState` 存储与提交链路。
- 不动 println scrollback 路径的排版权（终端自己排，只换它使用的截断原语口径）。
- 不处理 ZWJ 序列（已正确，见背景 5）。
- 不追求补充平面 text-presentation 符号 + VS16 的完备清单（用户实际场景是 BMP 符号；
  未列出的组合沿用逐码点口径，错位风险接受）。

## 设计

### 1. 新类 `dev.tamboui.text.TerminalWidth`（patch 模块，纯新增）

序列感知宽度工具，逐码点语义与 `CharWidth` 完全一致，仅两类组合按终端实际建模：

- **keycap**：`[0-9#*] + FE0F? + 20E3` → 2 列（UTS#51 keycap 序列）；
- **VS16 变宽**：base 为上述 68 区间清单内的 1 宽符号，后跟 FE0F → 2 列。

API（与 CharWidth 同形，便于替换）：
- `int of(String s)`：序列感知总宽；
- `int ofPrefix(String s, int endIndex)`：`s[0,endIndex)` 的显示宽（光标列用；切点落在组合
  序列中间时，含 base 不含 FE0F/20E3 按 base 宽，含全序列按 2）；
- `String substringByWidth(String s, int maxWidth)`：截取不切半组合序列——预算放不下整个
  序列时整个序列留给下一段（同现有 ZWJ 保护语义）。

### 2. shadow `dev.tamboui.buffer.Buffer`（patch 模块，整类复制最小 diff）

复制 0.4.0 全类，仅改 `setString` 与 `withLines`：

- `setString` 主循环前瞻识别 keycap / VS16 组合 → 照抄 Regional Indicator flag 模式：
  base cell（symbol 含完整序列码点）+ `Cell.CONTINUATION`，col 前进 2；右边缘放不下整个
  2 列组合时替换空格（同现有 wide-char 行为）；
- symbol 替换条件从 `codePoint < 0x20 || codePoint == 0x7F` 扩到含
  `0x80 <= codePoint <= 0x9F`（C1 显示兜底）；
- `withLines` 的总宽计算换 `TerminalWidth.of`。

### 3. code-tui 消费点换口径（`CodeTuiView`）

纯函数替换，行为语义不变、宽度口径变准：
- `displayWidth`（1592 行）改委托 `TerminalWidth.of`；
- `wrapSegments`（1759 行）换 `TerminalWidth.substringByWidth`；
- 输入框光标列（1728 行）换 `TerminalWidth.ofPrefix`；
- 截断+省略号（3893、4190 行）、todo 面板折行原语（3617 行）、命令名对齐（4280 行）、
  面板行截断（4398、4478 行）、状态行（4540、4591 行）——凡 `CharWidth.substringByWidth`
  / `CharWidth.of` 参与显示排版处统一替换。

### 4. 测试与验证

- `TerminalWidthTest`（patch 模块）：keycap=2、VS16 组合=2、裸符号=1、原生 emoji=2、CJK=2、
  ofPrefix 切点语义、substringByWidth 不切半；
- `BufferGraphemeCellTest`（patch 模块）：keycap 布成 base+continuation、symbol 含三码点、
  返回列 +2、右边缘放不下换空格、C1 cell 为空格；
- code-tui `SegmentedWrapTest` 补 keycap 折行用例；
- 全量 `mvn -pl springai-code-tui -am test`；
- pty 冒烟：用户原文案渲染截图比对（沿用 `src/test/resources/scripts/` 冒烟基建）。

## 淘汰的备选

| 备选 | 淘汰理由 |
| --- | --- |
| 粘贴归一改写内容（9️⃣→9） | 用户否决：「输入什么就要什么」 |
| shadow `CharWidth` 把 FE0F 改 1 宽 | 单码点 API 表达不了序列语义；FE0F 会独立成 cell，差分输出把它与 base 拆开，孤立 VS16 破坏「每个 cell 独立可渲染」假设，比现状更糟 |
| 只改 InputBox 不 shadow Buffer | 输入框内部对了，但 Buffer cell 布局仍 1 列，终端渲染 2 列，屏幕照样错位 |
| 等 tamboui 上游修 | 止血需求在前；上游 0.4.0 已发布且无 timeline；patch 模块本就是干这个的 |

## 风险

- shadow `Buffer` 与 tamboui 0.4.0 强绑定：升级库须 diff 核对（pom 注释已写明该纪律，
  `Buffer` 文件头加同款注释）。
- VS16 变宽清单来自 wcwidth 15.x 数据，与 Terminal.app（CoreText）个别字符可能仍有分歧；
  未列出组合回落逐码点口径，不会更糟。

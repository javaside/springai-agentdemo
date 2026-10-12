# 抖音 ZWNJ 粘贴输入框错位 —— Cf 格式字符宽度口径修复（设计）

日期：2026-10-11　分支：`fix/zwnj-format-width`

## 事故

用户向 code-tui 输入框粘贴抖音文案（复制文本散布 7 个 U+200C ZWNJ，行尾连排两个）：

> 《We Never (Remix) (若如初见)》‌，由 ‌Hi Noise‌ 和 ‌秋成‌ 合作，是抖音上“Vibe知识大赏”常用的氛围感BGM。‌‌

症状：输入框显示错乱，行尾「最后一句重复」。未提交发送（日志与会话 JSON 无此文本，取自用户原话重建）。

## 根因（实测证据链）

`CharWidth`（tamboui-core 0.4.0）把 Cf 格式字符（U+200B–200F 等）判 0 列；
Terminal.app（Grass profile，用户默认）TTBuffer 记账实测**每个 1 列**。
事故原文 7 个 ZWNJ：模型 98 列 vs 终端 105 列，差分渲染游标逐列落后 7 列，
重绘时新内容写偏 → 错乱 + 行尾重复。与 2026-10-09 VS16 事故同构、方向相反。

### DSR 探针实测（2026-10-11，方法同 /tmp/termwidth_probe.py）

探针脚本 `/tmp/zwnj_probe_width.py`、`/tmp/zwnj_probe_width2.py`
（raw 模式写 /dev/tty + `CSI 6n` 读光标列；AppleScript `do script` 驱动
Terminal.app 新窗口，默认 profile 即用户 Grass）。结果 JSON：
`/tmp/zwnj_probe_result.json`、`/tmp/zwnj_probe_result2.json`。

| 测试串 | 模型（改前） | 终端 DSR | 判定 |
|---|---|---|---|
| `A` | 1 | 1 | 基线 ✓ |
| `》` | 2 | 2 | 基线 ✓ |
| `a`+U+0301（组合附标） | 1 | 1 | Mn 类维持零宽 ✓ |
| U+FE0F（VS16，单独） | 0 | 0 | 上轮口径复验 ✓ |
| U+200B / U+200C / U+200E / U+2060 / U+2062 / U+FEFF / U+00AD（各单独） | 0 | **各 1** | 修复点 |
| `》`+U+200C | 2 | **3** | 修复点（事故形态） |
| U+200C ×2 | 0 | **2** | 修复点（事故行尾形态） |
| `》`+U+200C+`，`+`由` | 6 | **7** | 修复点（DSR 直接实测） |
| `a`+U+200D+`b` | 1（ZWJ 粘合） | **3** | ZWJ 粘合是虚构 |
| `👨`+U+200D+`👩`（家族序列） | 2（粘合单 glyph） | **5**（2+1+2） | 同上，Terminal.app 不做字形合并记账 |
| 用户全文（76 码点，7×U+200C） | 98 | 全文 105（逐字符 DSR 之和；窗口折行 delta=26 与之一致） | 事故定量 |

结论：Terminal.app 对「组合附标 / VS16」记 0 列，对**其余 Cf 格式字符
与独立 ZWJ 一律记 1 列**（TTBuffer 逐码点布格，不做 ZWJ 字形合并）。

## 目标

模型宽度口径对齐 Terminal.app 实测：

1. U+200B/200C/200E/200F、U+2060–2064、U+FEFF、U+00AD → **1 列**（独立 cell）。
2. U+200D（ZWJ）→ **1 列**，取消家族序列粘合（`👨‍👩` = 2+1+2 = 5）。
3. 维持不变：组合附标（Mn/Me）、VS16（FE0F）零宽；keycap 2 列；旗帜对 2 列。
4. 三处量宽点（Buffer 布格、InlineDisplay 游标、排版折行）+ 上游 widgets
   （TextInput 光标/滚动算术直用 CharWidth）**全部同源**。

## 方案：shadow `CharWidth` 进 patch 模块

- `CharWidth` 被 tamboui-widgets/toolkit 直接引用（TextInput、Paragraph、
  TextElement 等），只改 patch 自有类修不全（输入框自身光标算术仍用错表）。
- fork 上游 496 行源文件进 `springai-tamboui-inline-patch`，仅改零宽表与
  ZWJ 分支；运行期由 MANIFEST Class-Path 顺序保证 patch jar 先于
  tamboui-core（与既有 Buffer/InlineDisplay shadow 同机制，已核验顺序）。
- `TerminalWidth`：删 cluster() 的 ZWJ 显式粘合分支（表改后 ZWJ 宽 1，
  自然独立成 cluster）。
- `Buffer`（shadow）：删 `appendToLast`/ZWJ 拼格机制（表改后 ZWJ 走常规
  1 列布格路径，机制成死代码且口径冲突）。

## 被淘汰的备选

- **只改 TerminalWidth + Buffer**：上游 TextInput 等仍按 0 列算光标列/横向
  滚动，含 ZWNJ 文本编辑时光标偏移——修复不彻底。
- **显示层剥离/替换 ZWNJ**：能把宽度归零但终端照样记 1 列，仍错位；且
  引入「显示与 buffer 内容不一致」的新口径问题（显示层替换仅控制字符沿用）。

## 非目标 / 遗留

- U+2028–U+202F（行/段分隔、定向格式）与 U+2066–206F（定向隔离）未实测，
  维持 0 列；同探针可后续补测后同法修正。
- 旗帜对（区域指示符）2 列口径未做 DSR 实测（沿用既有假设），遗留核对。
- 肤色修饰符（U+1F3FB–FF）、标签区未实测，维持现状。
- ZWNJ 列的字形（空白或豆腐块）由 Terminal.app 字体回退决定，属终端自身
  行为（裸 `cat` 同现），不在应用层处理（2026-10-09 keycap 重叠同结论）。
- 其他终端（iTerm2 等）若真做 ZWJ 字形合并，家族序列将显示为带空隙的
  独立 emoji——本仓库口径以 Terminal.app 实测为准（既有决策惯例）。

## 升级核对

tamboui 升级时：diff 上游 `CharWidth.java` 与本 shadow（文件头有差异清单），
重点核对零宽表与 `of(String)`/`substringByWidth` 的序列分支。

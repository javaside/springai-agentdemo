# 抖音 ZWNJ 宽度口径修复 —— 实施计划

日期：2026-10-11　分支：`fix/zwnj-format-width`　设计：
[specs/2026-10-11-zwnj-format-width-design.md](../specs/2026-10-11-zwnj-format-width-design.md)

每步：先写失败测试 → 红 → 最小实现 → 绿 → 提交（分支上）。

## T1 新增 `CharWidthShadowTest`（patch 模块）

- 表值：`of(0x200B/200C/200D/200E/200F/2060/2062/FEFF/00AD) == 1`；
  `of(0xFE0F) == 0`、`of(0x0301) == 0`（维持项反改可杀）。
- 去粘合：`of("👨‍👩") == 5`、`of("a\u200Db") == 3`。
- 一致性：`of(substringByWidth("👨‍👩abc", 3)) == 3`（截取与总量同口径）。
- 事故文本：`of(全文) == 105`（98 + 7×ZWNJ）。
- 红：当前解析到 tamboui-core 的 CharWidth（0 列/粘合 2 列）。

## T2 `TerminalWidthTest` 增补 + 旧口径更新

- 增：ZWNJ 独立 1 列；`》\u200C` = 3；`》\u200C，由` = 7（DSR 直接实测值）；
  家族 5/8 列；`a\u200Db` = 3；事故全文 104。
- 改旧：`👨‍👩‍👧` 2→8；`\u200B` 0→1。
- ofPrefix：`》\u200C，` 切点逐列；substringByWidth：ZWNJ 不再并入 base cluster。

## T3 `BufferGraphemeCellTest` 增补 + 旧口径更新

- 增：`》\u200C，` 布格 → `》`(0,1) `\u200C`(2 独立 cell) `，`(3,4)；
  行首 ZWNJ 独立成格。
- 改旧：`zwjSequenceJoinsIntoBaseCell` → 家族逐部件独立布格
  （`👨`(0,1) `‍`(2) `👩`(3,4) + `x`(5)，end=6）。

## T4 实现

1. fork `CharWidth.java` 进 patch（源：tamboui-core 0.4.0 sources jar）：
   零宽表删 `{00AD}`、`{200B,200F}`、`{2060,2064}`、`{FEFF}`；
   `of(String)` 删 ZWJ 跳宽分支；`substringByWidth`/`measureZwjSequence`
   删 ZWJ 原子性机制（表改后口径冲突：按码点身份 fire 会漏记 ZWJ 及其后
   部件宽度）；文件头加 shadow 差异清单。
2. `TerminalWidth`：删 `CP_ZWJ` 粘合分支；类注释/方法注释同步。
3. `Buffer`（shadow）：删 `appendToLast` 机制与注释；头差异清单加条目。
4. patch pom `<description>` 提及 CharWidth shadow。

## T5 回归

- `mvn -pl springai-code-tui -am test` 全绿（含六类宽度回归 79 条）。
- 真机验证（用户侧）：粘贴事故原文，输入框不错乱、行尾不重复。

## T6 提交与记录

- 分支提交（feat/fix 单提交：shadow + 测试 + 文档同仓）。
- memory 事故记录更新（本事故并入宽度口径事故链）。

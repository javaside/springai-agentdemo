# 实施计划：emoji 组合序列宽度口径修正

- 日期：2026-10-09
- spec：[2026-10-09-emoji-sequence-width-design.md](../specs/2026-10-09-emoji-sequence-width-design.md)
- 分支：`feature/emoji-sequence-width`
- 每步纪律：先写失败测试 → 确认红 → 最小实现 → 绿 → 提交（功能与测试同一提交）。

## Task 1：`TerminalWidth` 序列感知宽度工具（patch 模块）

1. 写 `TerminalWidthTest`（先红）：
   - `of("9️⃣")==2`、`of("8️⃣")==2`（keycap，含与不含 FE0F 两种拼法各测）；
   - `of("⚠️")==2`、`of("⚠")==1`、`of("❤️")==2`（VS16 组合）；
   - `of("🍺")==2`、`of("中")==2`、`of("a")==1`（回归口径不变）；
   - 用户原文案全串 `of(...)` 与逐段 wcwidth 口径一致（65→67 的两处全对上）；
   - `ofPrefix`：切点在 base 之后/序列末尾之后的语义；
   - `substringByWidth("…9️⃣…", 预算恰好放不下序列)`：序列整体留下段，不切半。
2. 实现 `dev.tamboui.text.TerminalWidth`（新增类，非 shadow）：
   - 内置 68 区间 VS16 变宽表 + keycap 规则；
   - `of` / `ofPrefix` / `substringByWidth` 三 API，其余语义照抄 `CharWidth`（含 ZWJ 跳宽）。
3. 绿后提交：`feat: TerminalWidth 序列感知宽度（keycap/VS16 组合按 2 列）`。

## Task 2：shadow `Buffer`（patch 模块）

1. 从 tamboui-core 0.4.0 sources 复制 `Buffer.java` 进 patch 模块（文件头注明 shadow 来源
   与升级核对纪律）；先写 `BufferGraphemeCellTest`（红）：
   - `setString("9️⃣")` → base cell symbol 含 3 码点 + 下一格是 CONTINUATION，返回列 = x+2；
   - `setString("⚠️")` 同上；
   - 组合跨右边缘（x = right-1）→ 该格替换空格，不越界；
   - `setString("\u009B...")` → cell symbol 为空格（C1 显示兜底），列前进 1；
   - `setString("中")` 原行为回归（2 列 + continuation）。
2. 改 `setString`：主循环前瞻 keycap/VS16 组合 → flag 模式（组合 cell + CONTINUATION，col+2，
   行尾放不下换空格）；C1 并入 symbol 替换条件；`withLines` 换 `TerminalWidth.of`。
3. 结构钉：沿用 patch 模块既有测试风格，若 tamboui-core 行为有未复刻漂移，diff 核对段留注释。
4. 绿后提交：`feat: shadow Buffer——keycap/VS16 组合 2 列布格 + C1 显示兜底`。

## Task 3：code-tui 消费点换口径

1. `SegmentedWrapTest` 补用例（红）：
   - `wrapSegmentsForTest("🍺⚽足球赛事9️⃣场", 8)` 之类：任何段都不以孤立 `9` 结尾且下段以
     `FE0F/20E3` 开头（不切半）；
   - 光标列：`cursorColForTest` 场景不便直测，改测 `displayWidth` 委托（`"9️⃣"` 全宽 2）。
2. `CodeTuiView` 替换：`displayWidth` / `wrapSegments` / 输入框光标列 / 截断省略号 /
   todo 折行 / 面板截断 / 状态行——全部从 `CharWidth.*` 换 `TerminalWidth.*`（同形 API，
   import 级替换为主）。
3. 绿后提交：`refactor: 显示排版宽度统一换 TerminalWidth 序列口径`。

## Task 4：验证与收尾

1. 全量 `mvn -pl springai-code-tui -am test`（含 patch 模块测试）。
2. 变异自检（至少 2 条）：把 TerminalWidth 的 keycap 规则改坏 → 对应测试红；改回 → 绿，
   工作树干净。
3. pty 冒烟：新增 `src/test/resources/scripts/keycap_smoke.py`（沿用 table_render_smoke 模式），
   粘贴用户原文案 → 截图核对输入框单行无错位、光标位置正确；无 keycap 回归用例（普通中文行）。
4. 提交冒烟脚本：`test: keycap 宽度 pty 冒烟脚本`。
5. `--no-ff` 合并回 main，merge commit 注明事件与 .ips 编号。

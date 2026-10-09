# CR 粘贴漏修 + VS16 宽度口径回调 — 实施计划（2026-10-09）

> 设计依据：[2026-10-09-cr-paste-leak-and-vs16-width-design.md](../specs/2026-10-09-cr-paste-leak-and-vs16-width-design.md)
> 分支：`fix/cr-paste-leak-and-vs16-width`。每步「先写失败的测试 → 红 → 最小实现 → 绿 → 提交」。

## 任务 A：粘贴普通路径补用规范化产物

- A1【红】`CodeTuiViewPasteTest`（或既有输入框测试类）新增：构造含 `\r\n` 与裸 `\r`
  的 PasteEvent 走 `handlePasteEvent` 普通路径（非图片、不超折叠阈值），
  断言 `inputState` 各逻辑行内容**不含 `\r`** 且行数正确（`\r\n` 与 `\r` 均按换行计）。
- A2【绿】`handlePasteEvent` 普通分支 `inputKeys.handlePasteEvent(event)` 改传
  `new PasteEvent(pasted)`。一处改动，注释说明三路同源。
- A3 提交 `fix: 粘贴普通路径漏用 normalizePasteText——裸 CR 落进输入框与正文`。

## 任务 B：TextWrap 打印层控制字符兜底

- B1【红】`TextWrapTest`（新建或既有）断言：`wrap` 含 `\r` / `\u0007` / `\u009b` 的 Text
  产物行内这些字符全部变空格；宽度与替换后一致；正常文本行为不变。
- B2【绿】`TextWrap.wrapLine` 对每个 span content 先做控制字符→空格替换
  （U+0000–001F、U+007F、U+0080–009F；行内不会合法出现 `\n`，无需豁免）。
- B3 提交 `fix: TextWrap 打印层把行内控制字符换空格——防 CR 回拉光标覆盖 scrollback`。

## 任务 C：VS16 组合宽度回调 1 列

- C1【红】`TerminalWidthTest`：⚠️/❤️/⏱️（base+FE0F）期望 `of()==1`、
  `substringByWidth` 不再把组合按 2 列预算让位（现行断言为 2，先改断言看到红）；
  keycap 2 列断言保持。`BufferGraphemeCellTest`：⚠️ 布格 1 格、无 CONTINUATION；
  keycap 2 格保持。
- C2【绿】`TerminalWidth` 删 `VS16_WIDEN_RANGES`/`isVs16Widened` 与 cluster VS16 分支
  （VS16 回归零宽后缀，cluster 原子性保留）；`Buffer.combiningSequenceLength` 删 VS16
  分支；两处类注释记录 DSR 实测依据。
- C3 全量 `mvn -pl springai-code-tui -am test`（含 patch 模块测试）。
- C4 提交 `fix: 文本符号+VS16 宽度按 Terminal.app 实测回调 1 列（wcwidth 口径与实机分歧）`。

## 任务 D：冒烟与真机验证

- D1 pyte 冒烟扩展：`keycap_smoke.py` 粘贴样本追加真实事故原文（含 `\r` 版本，
  从事故会话逐字节还原），断言输入框多行且 raw 输出流打印行不含 `\r`；
  VS16 旁证段口径同步（不判红，仅观测）。
- D2 重跑 `keycap_smoke.py` + `paste_bulk_smoke.py`。
- D3 Terminal.app 真机（用户手测）：粘贴事故原文 → 输入框多行不乱 → 打字不卡 →
  Enter 后 scrollback 一行不少、无覆盖重复。
- D4 提交 `test: 事故原文（含裸 CR）纳入 keycap/VS16 冒烟`，随后 `--no-ff` 合并。

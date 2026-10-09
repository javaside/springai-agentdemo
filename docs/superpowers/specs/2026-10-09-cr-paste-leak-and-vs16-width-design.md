# CR 粘贴漏修 + VS16 宽度口径回调 — 设计（2026-10-09）

## 背景

上一轮 emoji-sequence-width（merge c8c77673）修掉 keycap 1列vs2列错位与 Terminal.app SIGBUS 后，
用户在 Terminal.app（Grass profile，142×36）实测仍报「输入框乱、打字打多了卡死、发送不了文字还重复」。
本轮沿「用户实际输入的原文 → 各层行为 → 终端实测」取证，定位到**两个互相独立的缺陷**。

## 目标

1. 粘贴路径的 CR 规范化真正生效：`\r\n` / 裸 `\r` 一律折成 `\n` 后再进编辑器
   （`normalizePasteText` 已存在，普通粘贴路径漏用）。
2. 打印层（TextWrap）对行内残留控制字符兜底，防止任何来源（存量会话回放、历史回溯、
   非粘贴入口）的 `\r` 到达终端执行 CR 破坏 scrollback。
3. 「文本符号 + VS16」（⚠️ ❤️ ⏱️ …）显示宽度按 Terminal.app 实测从 2 列回调为 1 列
   （keycap 保持 2 列不动）。

## 非目标

- 不做按终端类型（TERM_PROGRAM）的多口径宽度表——单一真相源 Terminal.app，等真有
  第二终端用户实证错位再议。
- 不修 Terminal.app 自身的渲染缺陷（TTBuffer/SIGBUS 是宿主 bug，我们只保证不出错位输入）。
- 不改 keycap 2 列口径（实测一致，正确）。
- 不在 EventParser（patch shadow）层做 CR 规范化——CR 的语义（换行还是丢弃）是应用层决策，
  shadow 面积按最小 diff 纪律不加码。

## 已核准的事实依据

1. **用户实际输入含裸 `\r`**：会话文件 `.codetui/sessions/20261009T020944-3b15f7.json` 首条
   USER 事件 content 逐字节为
   `🍺9月7日周一赛事🍺\r⚽足球赛事9️⃣场⚽\r🏀篮球赛事8️⃣场🏀\r⏰晚上 22:00点⏰ 都觉得…`——
   小节间是纯 CR（微信 macOS 客户端复制多行文本的已知行尾风格）。
2. **TextAreaState 只认 `\n`**：tamboui-widgets 0.4.0 `TextAreaState.insert(char)` 源码，
   `'\n'` 走 insertNewline，其余字符（含 `\r`）原样插进行内。
3. **规范化只做了一半**：`CodeTuiView.InputBox.handlePasteEvent` 中
   `String pasted = normalizePasteText(event.text())` 的产物只被图片占位符与
   [TEXTn] 折叠两个分支消费；普通路径 `inputKeys.handlePasteEvent(event)` 传入的
   仍是**原始 event**。`normalizePasteText` 由 f693993a 引入，普通路径自始漏用。
   （证据：上条会话 content 中 `\r` 原样落盘 = 编辑器收到了原始文本。）
4. **Terminal.app 宽度实测**（DSR `CSI 6n` 光标位置法，默认 profile = 用户 Grass，
   2026-10-09，脚本 `/tmp/termwidth_probe.py`）：

   | 单元 | Terminal.app 实测 | 现行 TerminalWidth |
   |---|---|---|
   | `a` / `22:00` | 1 / 5 | 1 / 5 |
   | CJK `月` | 2 | 2 |
   | 🍺 ⚽ 🏀 ⏰ | 2 | 2 |
   | `9️⃣` `8️⃣`（keycap，含/不含 FE0F） | 2 | 2 |
   | **⚠️ ❤️ ⏱️**（文本符号+VS16） | **1** | **2** ← 反向错位 |

   上一轮把「文本符号+VS16 → 2 列」的依据是 wcwidth 15.x（`w(cp)=1 且 w(cp+FE0F)=2`）；
   Terminal.app/CoreText 不跟 wcwidth 走，实测 1 列。该规则对 Terminal.app 是错的。
5. **pyte 复现边界**：pyte 下粘贴含 `\r` 原文 + 180 字打字 + Enter 全流程不炸
   （渲染布格层把行内 `\r` 按 C0 替换为空格，首帧无害）；「发送后 scrollback 被 CR 覆盖」
   的形态发生在打印路径（TextWrap → InlineDisplay.println），pyte 脚本此前未覆盖。

## 方案

### 任务 A：粘贴普通路径补用规范化产物

`handlePasteEvent` 普通分支改为 `inputKeys.handlePasteEvent(new PasteEvent(pasted))`——
与图片/折叠两分支同源，三路全部消费规范化后的文本。

### 任务 B：TextWrap 打印层控制字符兜底

`TextWrap.wrap` 逐 span 处理前，把内容中 U+0000–U+001F（保留 `\n`？不——TextWrap 的
输入已是按 `\n` 拆好的 Line，行内不会合法出现 `\n`）与 U+007F、U+0080–U+009F 替换为空格。
选空格不选删除：宽度 1 列可预期、不粘连两侧词语。与 shadow Buffer.setString 的
C0/C1/DEL 替换同一纪律（那是渲染 Buffer 层，这是打印层，两条出口互为纵深）。

### 任务 C：VS16 组合宽度回调 1 列

- `TerminalWidth`：删除「文本符号+VS16 → 2 列」规则（`VS16_WIDEN_RANGES` /
  `isVs16Widened` 及 cluster 中对应分支）。VS16 回归纯零宽后缀：cluster 仍以
  「base+VS16」为原子（不切半），宽度记 base 宽（文本符号 = 1）。
- shadow `Buffer.combiningSequenceLength`：删除 VS16 分支（keycap 分支保留）。
- 类注释更新依据：wcwidth 与 Terminal.app 的分歧 + 本轮 DSR 实测表。
- 测试反转：`TerminalWidthTest` / `BufferGraphemeCellTest` 中 ⚠️类 2 列断言改为 1 列；
  keycap 断言全部保持。

## 被淘汰的备选

- **EventParser 层规范化 CR**（所有 tamboui 消费方受益）：shadow 面积扩大违反最小 diff
  纪律；且 CR 语义属应用决策。改由消费方（code-tui）负责。
- **运行时终端探测双口径**（TERM_PROGRAM 分支选 1/2 列）：复杂度高、无第二终端实证需求，
  首版不建；记录在本 spec 备检索。
- **在 InlineDisplay.println 处兜底**（比 TextWrap 更底层）：InlineDisplay 属 patch shadow，
  改它同样扩 shadow 面；TextWrap 是 code-tui 自有且已是「所有 println 出口的必经之路」。
- **存储层清洗存量会话**（改历史 JSON）：销毁用户数据，方向错误；靠打印层兜底覆盖回放路径。

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

## 追加：整个终端卡死的真根因（同日二轮修复，分支 fix/keycap-diff-cursor-width）

第一轮修完（CR 规范化 + VS16 回调）后用户复测：**输入框按 4 行正确渲染了**（CR 修复生效），
但仍有「重复行」且 **Terminal.app 整个应用卡死**（所有窗口不动，关掉该窗口才恢复）。
用户关键描述：**「把整个终端卡死……只能把开始的窗口关了，其他窗口才能动」**——这是
Terminal.app 宿主侧卡死，jstack 抓不到（进程停在 pollEvent，JVM 侧完全正常）。

### 根因（字节级实证，pipe-pane 抓 app 原始输出）

差分渲染循环用 `CharWidth.of(cell.symbol())` 推进游标模型
（`InlineDisplay.appendFramePatch` / `appendRowOverwrite`）。cell symbol 可以是
**keycap 组合**（`"9️⃣"` = U+0039+FE0F+20E3）：

| 量具 | keycap | 终端实际 |
| --- | --- | --- |
| `CharWidth.of("9️⃣")` | **1** | 2 |
| `TerminalWidth.of("9️⃣")` | 2 | 2 |

→ 模型以为光标还差 1 列，在下一个 cell 前补发 `ESC[1C`；终端实际共前进 3 列，
**每个 keycap 溢出 1 列**。行宽超出终端宽度后触发 Terminal.app 的 TTBuffer 越界
（与 08:50 SIGBUS 同族缺陷），主线程自旋 → 整个终端无响应。

实测字节（修复前 → 修复后）：

```
before: 9\xef\xb8\x8f\xe2\x83\xa3 \x1b[1C \xe5\x9c\xba(场) ...   ← 补发的 ESC[1C
after : 9\xef\xb8\x8f\xe2\x83\xa3 \xe5\x9c\xba(场) ...           ← 紧邻，无补发
```

屏幕上的可见形态就是「`9️⃣ 场` 中间多一个空格」——用户两次截图里都有，早先被误读成
「pyte 的旁证」。

### 为什么之前测不出来

- pyte 与 tmux **容忍**这 1 列漂移（各自的缓冲实现不同），屏幕看不出、也不崩；
  只有 Terminal.app 的 TTBuffer 会越界卡死。
- 早先冒烟里的「屏上有『9 场』」旁证**恰恰是靠 bug 的 ESC[1C 让 pyte 的 1 列模型
  蒙对**——等于在给 bug 背书。已删除该旁证，keycap 行的屏幕级断言一律改字节级。

### 修法

`InlineDisplay` 三处 cell 量宽改 `TerminalWidth`（与 Buffer 布格、输入框折行同源）：
`appendFramePatch`(577) / `appendRowOverwrite`(619) / `findLastContentPosition`(835)。
回归测试 `InlineDisplayDiffTest#keycapDoesNotEmitSpuriousCursorAdvance`（改回 CharWidth 即红）。

### 教训

**「Buffer 布格」与「渲染游标记账」是两处独立量宽点，必须同源。** 第一轮只修了前者，
反而把「少 1 列」变成「多 1 列」——症状相似、根因不同。凡涉及「一个 glyph 占几列」的
新代码，都要核对这三处（Buffer.setString 布格 / InlineDisplay 游标 / 排版折行）。

## 追加二：keycap 字形重叠 → 输入框显示层降级（merge 93bbe8e9）

卡死修好后用户复测：**能打字了**，但「输入框里 9场、8场重叠」。

### 定性：不是布局 bug，是终端字体缺字形

三条证据：

1. **裸 `cat` 同一文本同样重叠**（用户实测，完全不经过 code-tui）——决定性对照。
2. **像素测量**（PIL 扫描用户截图）：`足球赛事9️⃣场` 与 `足球赛事X场` **同为 252 px
   = 11 列**（基准 ≈23 px/列）。`X` 是 1 列 → Terminal.app 给 keycap 的**推进宽度只有
   1 列**，却画了一个更宽的回退方块，压住后面的 `场`。
3. **字体**：Grass profile 用 `SFMono-Bold`（同机其它 profile 也都是 SF Mono / Monaco /
   Courier 等纯 ASCII 等宽字体），**都没有 U+20E3 字形** → 只能回退。故换 profile 无用。

（注：早先 DSR 探针在默认窗口测得 keycap 推进 2 列，与像素测量矛盾——DSR 报的是
**缓冲区记账列**，不是**视觉推进量**；此类字形的视觉行为只能靠像素/真机观测。）

### 方案：显示层降级（用户选定）

`CodeTuiView.displaySafeInput`：绘制输入框那一帧时把 keycap 序列渲染成
「数字/符号 + 空格」。**宽度守恒是硬约束**——替换物仍是 2 列，与 `TerminalWidth` 的
keycap 口径严格等宽，故折行与光标列不受影响（布局仍按原串算）；`inputState` 与发给
模型的正文**一字不改**。

默认仅 `Apple_Terminal` 启用（`keycapFallbackEnabled`，同 `codetui.hardwareCursor` 的
auto/always/never 写法）：iTerm2 / tmux / pyte 实测渲染 keycap 正常，对它们降级是白丢信息。

### 被淘汰的备选

- **改字体**：所有内置 profile 的字体都没有 keycap 字形，换 profile 不解决问题。
- **运行时探测推进宽度**：DSR 报的是记账列而非视觉推进量（见上），探测不可靠。
- **改宽度模型为 1**：会让「模型 < 实际推进」的 profile 出现**过度推进** → 行溢出 →
  正是卡死的成因；保持 2 是偏保守的安全侧（欠推进只导致视觉偏左，不会越界）。

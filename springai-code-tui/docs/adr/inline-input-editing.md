# 行内输入框的自绘与键处理（含 readline 编辑快捷键）

> 适用版本：TamboUI 0.4.0（tamboui-toolkit / tamboui-tui / tamboui-jline3-backend）。
> 实现结论以 0.4.0 jar 的 `javap` 反编译 + pty 实机冒烟为准；涉及按键路由的改动**必须 pty 实测**（单测喂的合成 KeyEvent 到不了 EventParser / JLine 后端层）。

## 1. 一句话总览

输入框是**自绘的 `InputBox` 元素**（唯一焦点目标）而非直接用 TamboUI 的 `textArea` 元素；编辑能力靠一个**从不渲染的影子 `textArea`**（`inputKeys`）复用完整键处理。在此结构上叠加了 readline 式编辑快捷键（Ctrl+A/E、按词跳转/删除、Ctrl+U/K），解决长文本内光标移动低效的问题。

## 2. 为什么不直接用 textArea 元素

- `TextAreaElement.isFocusable()` 恒为 true，未显式给 id 时以自增 id **自注册进焦点链并抢占焦点**；
- 事件路由器对焦点元素**先调其内建 `handleKeyEvent`**——它把 Enter 当换行插入并返回 HANDLED，外层挂的「Enter=发送」逻辑根本轮不到。

**做法**：自绘 `InputBox` 亲自持焦点（固定 id + focusable），按键第一手先给 `onInputKey` 拦 Ctrl+C / Esc / Enter；其余编辑键（退格/方向/Home/End/字符/中文…）转交给**从不渲染**的 `inputKeys = textArea(inputState)`——因不渲染故不自注册、不抢焦点，但可复用其完整键处理。渲染走底层 `TextArea` 控件 + 手动软折行，并补硬件光标供中文 IME 定位。

关联坑（详见项目记忆，此处备忘）：

- 默认键位绑定 `quit` 含裸 `q`/`Q`——输入框是唯一焦点，含 q 的输入/粘贴会被误判退出；已在 `configure()` 整组重绑为仅 Ctrl+C，且 `onInputKey` 不用 `isQuit()`。
- 空态不画框内占位符：中文 IME 拼字（候选未上屏）时 `inputState` 仍为空，占位符会与拼音并存。
- 只 `setCursorPosition` 时硬件光标常被行内 runner 隐藏，须画反显块补「可见光标」。

## 3. readline 编辑快捷键（2026-07 增强）

长文本内光标只能 ←→ 一格格挪、体验差。评估过三档方案：

| 方案 | 取舍 | 结论 |
|---|---|---|
| A. readline 快捷键 | 改动小、shell 肌肉记忆一致 | **本次落地** |
| B. 呼出 `$EDITOR`（bash 的 Ctrl+X Ctrl+E） | 需挂起/恢复 TUI；0.4.0 无公开 suspend API（`InlineToolkitRunner` 反编译核实），要反射进私有 Backend | 留作后续增强 |
| C. vi 模态编辑 | 完整模态状态机，工作量最大 | 不做 |

### 键表

| 按键 | 动作 |
|---|---|
| Ctrl+A / Ctrl+E | 行首 / 行尾 |
| Ctrl+← / Alt+← / Alt+B | 上一词词首 |
| Ctrl+→ / Alt+→ / Alt+F | 下一词词尾 |
| Ctrl+W / Alt+Backspace | 删前一词 |
| Ctrl+U / Ctrl+K | 删到行首 / 行尾（以逻辑行为界，不跨行） |

### 设计决策

1. **控制字节歧义决定了可用字母**：终端把 Ctrl+A..Z 发成字节 1~26，EventParser 把它们映射为 CTRL+字母 KeyEvent——但 `Ctrl+H/I/J/M` 与 Backspace/Tab/Enter **字节相同**、已被映射为独立 KeyCode，无法区分，故键表避开这几个字母。
2. **移动与删除的词边界刻意不同**：
   - 移动（`prevWordStart` / `nextWordEnd`）：字母数字下划线连成一词——`foo.bar` 里能停在 `.` 两侧，细粒度定位；
   - 删除（`prevWordStartForDelete`）：readline unix-word-rubout 语义，**空白为界**——`git commit -m` 一记 Ctrl+W 把 `-m` 整个删掉，与 shell 手感一致。
3. **CJK 按单字跳/删**：中文无空格分词，整段吞会让 Ctrl+W 退化成「清空整行」、太危险；按单字（`Character.UnicodeScript` 判 HAN/HIRAGANA/KATAKANA/HANGUL）与主流终端/IDE 一致。
4. **组合既有单步原语实现**：`TextAreaState`（0.4.0）只有单步 move/delete API、无按词操作，按词动作用 `while (cursorCol > target) moveCursorLeft()` 组合实现——不碰私有字段、不依赖反射。
5. **Alt+Backspace 认两种形态**：ESC+DEL 可能被解析成 `ALT+char(127)` 而非 `ALT+BACKSPACE`，两者都拦。
6. **HANDLED 分支要补状态复位**：`onInputKey` 尾部的公共复位（补全菜单 / 历史回溯指针）对提前 return 的快捷键分支不生效，须在快捷键命中处显式复位——否则按词跳完再按 ↑ 会接着翻历史。

### 验证方法（分层）

- **词边界纯函数单测**：`prevWordStart` / `nextWordEnd` / `prevWordStartForDelete` 静态方法直接断言（ASCII 词、CJK 单字、混排、标点）；
- **端到端合成键单测**：`feedKeyForTest` 喂 CTRL/ALT 修饰的 KeyEvent，断言文本与光标落点（含「裸字母 a/e/w/u/k 照常上屏」回归）；
- **pty 实机冒烟**（`src/test/resources/scripts/edit_shortcut_smoke.py`）：真实 JLine 后端下发原始控制字节（`\x01`、`\x17`、`\x1bb`…），pyte 渲染屏幕断言——这是唯一能证明「控制字节真的被解析成 CTRL/ALT KeyEvent」的层（合成事件绕过了 EventParser）。

## 4. 已知限制

- `Ctrl+H/I/J/M` 不可绑定（见上）；
- Shift/Alt+Enter 换行取决于终端能否区分修饰键（Apple Terminal 不行），可靠换行用 `\` + Enter（终端无关）；
- 极端 grapheme（emoji ZWJ 序列）光标列可能轻微偏移（渲染按显示宽度、光标按 grapheme cluster，二者对齐尽力而为）。

## 5. 提交记录

- （本次）feat(code-tui)：输入框 readline 式编辑快捷键 + pty 冒烟

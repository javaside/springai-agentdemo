# 深色终端灰阶配色设计

**日期**：2026-08-16
**范围**：`springai-code-tui` 调色板（`Theme` / `MarkdownRenderer`）

## 问题

界面多处次要文字在深色终端窗口下看不见。根因只有一个：这些样式用的是 `Color.DARK_GRAY`，而它在 tamboui 里映射的是 **ANSI 亮黑（SGR 90）**。

ANSI 0–15 的实际颜色由**用户终端配置文件**决定，同一个 `90` 在不同 profile 下从 `#333` 到 `#666` 不等，代码侧无法控制。按相对亮度估算，亮黑对深色底（参考 `#1e1e1e`）的对比度约 2.4:1，profile 更暗时更低，达不到 3:1 的可读下限。

受影响的位置（全部同一根因）：

| 位置 | 常量 |
| --- | --- |
| 状态行后缀（`· Esc 取消`、`· 缓存命中`、`· 已排队 N 条`） | `Theme.DIM` |
| 面板次要说明：权限面板「↑ 原因」「↳ 记规则」、待办 ○ 行、技能分页、KILLED 行、折叠提示（13 处） | `Theme.DIM` |
| `/model` `/skill` `/mcp` 每项的描述 | `Theme.PICK_DESC` |
| diff 上下文行号、截断标记 | `Theme.DIFF_NO_CTX` / `DIFF_TRUNC` |
| 代码块顶部语言标注 | `MarkdownRenderer.DIM` |
| 模型回复里的引用块 | `MarkdownRenderer.QUOTE` |

同类问题另有一处：`MarkdownRenderer.GUTTER`（代码块左边栏）用 `Color.rgb(120, 150, 200)`。目标终端 `COLORTERM` 为空、不支持 truecolor，`38;2;r;g;b` 被直接忽略，左边栏实际退回默认前景色。`Theme` 中已有针对底色的同款告诫，前景侧漏掉了。

本设计只面向深色终端。现有配色本就假定深色（`HINT` = 250、`INFO_LINE` = 248 都是浅灰，白底上同样不可读），不引入主题机制、不做浅色支持。

## 设计

### 灰阶三档

在 `Theme` 中把灰阶固化成三个命名常量，取值一律来自 256 色灰阶区（232–255）——各家终端 profile 基本不改这一段，是可控的：

```java
static final Color GRAY_TEXT  = Color.indexed(250);  // #bcbcbc 8.8:1  提示 / 空态
static final Color GRAY_INFO  = Color.indexed(248);  // #a8a8a8 7.0:1  信息行 / 引用正文
static final Color GRAY_MUTED = Color.indexed(244);  // #808080 4.2:1  装饰性最次要
```

对比度按参考底 `#1e1e1e` 计算，下限 3:1（对应灰阶 242 / `#6c6c6c`，3.2:1）。`GRAY_MUTED` 取 244 而非贴着下限，是为了在偏亮的深色 profile（如 `#262626` 以上）仍留余量。

三档之间保持可辨的明度间距，层次不靠"暗到看不见"表达。`MarkdownRenderer` 与 `Theme` 同包，直接复用这三个常量，不再各自定义灰色。

### 改动清单

| 常量 | 现在 | 改为 |
| --- | --- | --- |
| `Theme.DIM` | `DARK_GRAY`（亮黑） | `GRAY_MUTED` |
| `Theme.PICK_DESC` | `DARK_GRAY` | `GRAY_MUTED` |
| `Theme.DIFF_NO_CTX` | `DARK_GRAY` | `GRAY_MUTED` |
| `Theme.DIFF_TRUNC` | `DARK_GRAY` | `GRAY_MUTED` |
| `MarkdownRenderer.DIM` | `DARK_GRAY` | `GRAY_MUTED` |
| `MarkdownRenderer.QUOTE` | `DARK_GRAY` + 斜体 | `GRAY_INFO` + 斜体 |
| `MarkdownRenderer.GUTTER` | `rgb(120, 150, 200)` | `Color.indexed(110)` |
| `Theme.HINT` | `indexed(250)` | `GRAY_TEXT`（值不变） |
| `Theme.INFO_LINE` | `indexed(248)` | `GRAY_INFO`（值不变） |

改完后 `Color.DARK_GRAY` 在 `springai-code-tui` 中不再有任何引用。`Theme` 的类 javadoc 现在写着「`DARK_GRAY` 仅留给固定区的次要装饰（待办○、diff 上下文行号）」，改完即不成立，须一并改写为三档灰阶的说明；`TOOL`、`INFO_LINE` 等处提到"原 DARK_GRAY 近黑看不清"的行内注释同样需要更新，否则代码里留着自相矛盾的说明。

### 引用块归为正文

`QUOTE` 提到 `GRAY_INFO`（248）而不是 `GRAY_MUTED`（244），因为引用块是**模型回复的正文内容**，不是界面装饰——它与信息行同级。与正文默认色的区分交给已有的斜体与行首标记，不靠压暗。

### 代码块左边栏

`GUTTER` 换成 `Color.indexed(110)`（`#87afd7`），是 256 色区中最接近原 `rgb(120, 150, 200)`（`#7896c8`）的一档，色相同为蓝、略亮。换成 indexed 后这段颜色在目标终端上才真正生效。

## 测试

### `ThemeContrastTest`（新增单测）

反射遍历 `Theme`、`MarkdownRenderer`、`SyntaxHighlighter` 的全部 `static Style` 字段（含 private，同包 `setAccessible`），逐条断言：

1. 前景与底色都不得是 truecolor（`Color$Rgb`）——目标终端不认 `38;2;`，颜色会被静默忽略。
2. 前景不得是 ANSI `BLACK` 或 `BRIGHT_BLACK`——实际取值由终端 profile 决定，代码控制不了。
3. **中性色**前景对其实际背景的对比度 ≥ 3:1；样式自带 `bg` 时对该 `bg` 计算，否则对参考底 `#1e1e1e` 计算。中性色的判定是 `max(r,g,b) - min(r,g,b) ≤ 16`。

第 3 条刻意只卡中性色：`ERROR`、`FAIL`、`MODE_BYPASS` 用 ANSI 红，靠**色相**而非明度区分，按亮度阈值算会误伤。

### `stream_box_smoke.py`（扩充实机断言）

流式期间捕获的原始字节流中**不得出现裸 `ESC[90m`**（亮黑前景）。忙碌态状态行后缀正是 `DIM`，这条断言因此真能拦住回归：单测只证调色板的取值，这条证颜色确实走到了终端。

### 变异实测

两处修复各自单独回退、确认对应断言为正确的理由判红，且互不遮蔽：

1. `DIM` 改回 `DARK_GRAY` → `ThemeContrastTest` 规则 2 与 pty 的 `ESC[90m` 断言判红。
2. `GUTTER` 改回 `rgb(...)` → `ThemeContrastTest` 规则 1 判红。

## 不做

- 不引入主题机制，不支持浅色背景。
- 不动 `WELCOME_HINT`（245）、`SyntaxHighlighter.COMMENT`（243）及语法高亮其余颜色——均已在可读区间，改动它们与本次目标无关。
- 不动任何底色（`USER_BG` 238、`QUEUED` / `INTERJECT` 236、`ADD_BG` 22、`DEL_BG` 52）。
- 不重排 `WELCOME_*` 系列的灰度层次。


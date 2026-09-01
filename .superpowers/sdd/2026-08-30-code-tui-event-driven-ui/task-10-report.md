# Task 10 报告：文档对齐、陈旧注释清理与全量回归

**状态：PASS（Terminal.app 人工验收未执行，待用户执行——见下文清单；不声称终端崩溃已根治验证）**

- Worktree: `/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui`
- Commit: `2e98b9ce` `docs(code-tui): document event-driven UI flow and purge stale polling comments`
- 变更性质：**纯文档/注释**，`git diff` 验证无任何 Java 逻辑改动（唯一接近代码的三处是行尾注释措辞更新）。

---

## 1. 文档改动清单（Step 1）

### `springai-code-tui/docs/implementation-map.md`（核心改动）

| 章节 | 改动 |
| --- | --- |
| §4「渲染模型」 | **整段重写**。删除「`render()` 每帧重建」「节流参数 `tickRate` 100ms、drain 66ms、每帧最多 300 行」的现行架构描述，替换为：按请求重绘、空闲零周期任务/零 ANSI 输出（并注明 PTY 冒烟有 idle 零字节断言钉住）、每 UI 批 300 物理行 + 12ms 双预算等现行参数。新增 **「事件驱动链路」** 小节，按 brief 要求的链路逐行成文：`Agent/source mutation -> durable state/queue -> lock-free dirty notify -> UiUpdateCoordinator coalescing -> InlineTuiRunner wake -> bounded processUpdates batch -> one-shot continuation/render`，附四类一次性任务（continuation/preview/resize/animation）每类至多一个在飞 generation、`publishLocalViewChange` 本地状态主动发布等要点。恢复被误删的 `InlineRenderBatch` 行（改写为「一个 UI 批的 println 合成一次提交」）。 |
| §3「流式回 UI」 | `渲染线程 drain()` → `锁外 dirty 通知（UiUpdateCoordinator 合并）→ UI 批 processUpdates`。 |
| §5 面板抽象 | 「UI 在 `drain` 里 `peekModal()`」→「UI 在每个 UI 批（`processUpdates`）里 `peekModal()`」。 |
| §6.1 `/context` | 「`refresh()` 由 drain 每 30 帧调」→「由 `ContextUsageRefreshController` 按需触发（事件标脏 + 500ms 防抖 + 单飞）」。 |
| §13 后台任务 | 「每帧（drain 66ms）调」→「每个空闲 UI 批里调」；「下一帧重试」→「下一批重试」；刹车额度「每帧一次的空转」→「空转批」。 |
| §14 插话 | 「每帧清空队列」→「把队列在 render 里清空」；「drain 空闲分支」→「UI 批空闲分支」。 |
| §19 注意提示 | 「在 drain 里调用」→「在 UI 批里调用」；状态机理由改为「UI 批与按键事件可能连续到达」；`userCancelledSinceLastTick` 的「drain 消费后复位」→「UI 批消费后复位」。 |
| 历史事实保留 | release notes（`docs/release-notes/*`）**一字未动**；map 内「旧的 `ResizeSettle(4)` 已随每帧 tick 删除」「两个常驻周期已删除」等历史叙述按 brief 保留（明确标为已删除的历史）。 |

### `springai-code-tui/docs/guide/subagent.md` §6.2

「跑在 TUI 的 drain 循环，每 66ms 一拍」→「跑在 TUI 的 UI 更新批里，事件驱动、空闲时零周期任务」；「每拍检查」→「每个空闲 UI 批检查」。

### `springai-code-tui/pom.xml`

依赖注释里「按 preferredSize 每帧增减高度」→「在每次重绘增减高度」。

## 2. 陈旧注释清理分类表（Step 2）

Grep 模式：`tickRate|66ms|100ms|每帧|scheduleRepeating|drain 每|animTick % 30`（另扫 `ResizeSettle`、`每 33ms`）。

### A. 已清理（stale current assumption → 现行语义）

| 文件 | 原表述 → 新表述 |
| --- | --- |
| `ui/CodeTuiView.java`（类 javadoc） | 「每帧都按 preferredSize … 每个 tick 都重绘」→「每次重绘都按 preferredSize…；事件驱动后没有常驻 tick：重绘只在 requestUiUpdate/requestRender/按键/resize 时发生」 |
| `CodeTuiView` `MAX_ROWS_PER_DRAIN` | 「每帧 drain / 留到下一帧 / 一帧几千行 / 每帧 300 行」→「单个 UI 批 drain / 下一批 / 一批几千行 / 每批 300 行」 |
| `CodeTuiView` `MAX_DRAIN_NANOS` | 「单个 drain tick / 66ms tick 内留足余量」→「单个 UI 批 / 输出 continuation 批间隔内留足余量」 |
| `CodeTuiView` `MAX_PENDING_INTAKE_PER_TICK` | 「单个 tick / 一个 tick 转完」→「单个 UI 批 / 一批转完」 |
| `CodeTuiView` `PREVIEW_THROTTLE_NANOS` | 「残行每帧都在变，若每帧都重画」→「残行连续变化，若每次重绘都重画」 |
| `CodeTuiView` `bgPending`/`bgProbedWhileBraked` | 「不每帧取 / 每 33ms 同步文件写 / 每帧重探」→「不每批取 / 每批同步文件写 / 每批重探」 |
| `CodeTuiView` `animTick` 字段/测试观测 | 「每个动画帧批自增 ~66ms」「每帧一批自增」→「每个动画帧批自增，忙态 ~66ms 一批」「每个动画帧批自增一次」 |
| `CodeTuiView` `userCancelledSinceLastTick` | 「上一帧到本帧 / drain 消费」→「上一批到本批 / UI 批消费」 |
| `CodeTuiView` render() | 「每帧构造 UI + {@link #drain}（方法已不存在）」→「构造一帧 UI 树（仅在被请求的重绘时调用）+ {@link #processUpdates}」；render 内「渲染一帧就把队列清空」→「一次 render 就把队列清空」 |
| `CodeTuiView` `attachCache*` | 「render 每帧都跑（TamboUI 逐帧重绘）/ 每帧开销」→「render 每次重绘都跑 / 每次重绘的开销」 |
| `CodeTuiView` 构造器接线注释 | 「render 每帧活读真相源」→「render 每次重绘活读真相源」 |
| `CodeTuiView` submitInput | 「{@link #drain} 自动出队」（死链接）→「UI 批自动出队」 |
| `CodeTuiView` deliverBackgroundResults | 「下一帧会再试 / 每 33ms 重投 / 这个方法每 33ms 被调一次」→「下一批会再试 / 每批重投 / 每个空闲批一次同步文件写」 |
| `CodeTuiView` scope 判空注释 ×7 | 「scope 每帧 eager 求值…每帧崩渲染线程」→「scope 每次 render eager 求值…渲染线程崩」（thinkingSettings/mcp/ask/permission/plan/perms/tasks 七处 + 面板 javadoc 两处） |
| `CodeTuiView` 其它 | skillPicker「运行器每帧按 preferredSize」→「每次重绘」；resize 记账「此后每帧重画从偏低处开始」→「此后每次重画」；backgroundStatusSuffix「每帧烧掉一次额度」→「每次状态行重绘烧掉一次额度」；renderForTest「渲染线程每帧调用」→「被请求时调用」 |
| `ui/AttentionTracker.java` | 「CodeTuiView.drain 每 33ms 跑一次…每帧重复响铃」→「UI 批与按键/Agent 事件之间没有同步关系（一个回合里可能连着跑好几批）…相邻批里重复响铃」；「drain 一拍 / 标题写回留在渲染线程（drain）」→「一个 UI 批 / （UI 批）」 |
| `ui/StatusBar.java` | 「每 2 帧(~66ms)前进一格」→「每 2 个动画帧(~132ms)前进一格」（animTick 现由 66ms 一次性动画帧驱动，每批自增一次，2 帧即 ~132ms——数值同步修正） |
| `ui/ContextUsage.java` | 「状态栏每帧读，绝不每帧重算」→「每次重绘都读，绝不重算」；refresh「绝不每帧」→「绝不在渲染路径同步重算」 |
| `ui/ConversationState.java` | 「渲染线程每帧都要…O(百万) 的每帧字符串操作」→「每次重绘都要…的字符串操作」；takeCompleteStreamingLines「留在缓冲区等下一帧 / 每帧 pty 写入限速 / 一帧灌几千行」→「等下一批 / 每批 / 一批灌几千行」 |
| `agent/background/BackgroundNotifier.java` | 「drain 每 33ms 调一次」「只在渲染线程（drain）调用」→「每个空闲 UI 批都调一次」「只在渲染线程（UI 批）调用」 |
| `agent/CodingAgent.java` / `agent/seam/SubmitHandler.java` / `agent/interjection/Interjections.java` | 「面板每帧读取 / 每渲染一帧就把队列清空」→「面板每次 render 读取 / 每次 render 都把队列清空一次」 |
| `tamboui…/InlineViewport.java` | 「经反射每帧一次」→「经反射每次 draw 一次」 |
| `tamboui…/InlineDisplay.java` | 「换算成时长要乘调用方的 tickRate…code-tui 现在跑 100ms/帧（见其 configure 的降频注释）」→「乘调用方的实际帧间隔…code-tui 事件驱动后没有全局 tick，重申帧由 runner 的 IME follow-up 一次性任务按 ~100ms/帧补排（见 IME_FOLLOW_UP_DELAY_MS）」——修正了指向已不存在机制的引用 |

### B. 保留：历史叙述（明确标注为已删除/被取代，符合 brief「preserve historical facts」）

`CodeTuiView` 198（ResizeSettle 已删）、448/455/470/472/611-615/755/790/919-920/933/960（「不再有常驻 tick / 66ms drain」「旧 tickRate(100ms)…正是本次重构删除的」「旧世界 66ms tick 会自动重试」「取代旧的 animTick % 30 周期刷」「与旧 drain 周期（66ms）同值」等）；`ContextUsageRefreshController` 19（「取代旧『视图 drain 每 ~1s 无条件刷一次』」）；`InlineTuiRunner` 65-67（「code-tui 旧帧长 100ms…沿用原视觉节拍的量级」）；全部 release notes；`docs/superpowers/plans|specs`（历史计划/设计文档）。

### C. 保留：仍然真实的现行机制

- `UiUpdateCoordinator` 246「每帧到期 publish VIEW」——描述现存的一次性动画帧任务（忙态 66ms）到期行为，真实；
- `CodeTuiView` 320/790/919-920/960 中「忙态 ~66ms 一批」——现存 `ANIMATION_FRAME_DELAY=66ms` 的一次性帧任务，真实；
- `InlineTuiRunner` 111/132-133/560/568 的 `config.tickRate()`——上游 TamboUI 配置 API（库本身仍支持 tick，code-tui 用 `noTick()` 关掉），非陈旧；
- `InlineDisplay` 55/278「窗口内每帧…重申帧逐帧消耗」——指 IME 光标带修复窗口的 8 个真实补排帧，真实；
- 测试 fixture（`src/test/**`，如 `DrainBurstCapTest`、smoke 脚本中的历史说明）：测试代码，brief 范围只要求清理 main 代码注释，未动。

## 3. 全量 Maven 验证（Step 3）

| 命令 | 结果 |
| --- | --- |
| `mvn -pl springai-code-tui -am test` | **BUILD SUCCESS**。`Tests run: 1811, Failures: 0, Errors: 0, Skipped: 10`（skip 为既有 @Disabled）。reactor：agentdemo SUCCESS / springai-tamboui-inline-patch SUCCESS (6.5s) / springai-code-tui SUCCESS (1:12)。 |
| `mvn -pl springai-code-tui -am clean package` | **BUILD SUCCESS**。同 1811 测试全绿；jar 产出于 `springai-code-tui/target/springai-code-tui.jar`。 |

### MANIFEST / Class-Path 检查证据

`unzip -p springai-code-tui/target/springai-code-tui.jar META-INF/MANIFEST.MF` 解析结果：

```
inline-patch pos: 3004  springai-tamboui-inline-patch-1.18.3.jar   ← lib/ 目录段
official tui pos: 3074  tamboui-tui-0.4.0.jar
ORDER OK (patch before official): True
```

Class-Path 相邻片段：`…lib/mcp-core-2.0.0.jar lib/springai-tamboui-inline-patch-1.18.3.jar lib/tamboui-core-0.4.0.jar lib/tamboui-tui-0.4.0.jar…` —— shadow 类（InlineTuiRunner/InlineViewport/InlineDisplay）先于官方 `tamboui-tui` 加载，顺序正确。

## 4. PTY 冒烟（Step 4，clean package 后重跑）

前置：`mvn -q -pl springai-tamboui-inline-patch -am install -DskipTests` + `mvn -q -pl springai-code-tui -am package -DskipTests` + `dependency:build-classpath`（README 规定命令）。全部 11 个本地/无网络脚本 exit 0 + `SMOKE PASS`：

| # | 脚本 | 结果 | 关键证据（各脚本末行/关键行） |
| --- | --- | --- | --- |
| 1 | `event_driven_fairness_smoke.py` | PASS | `INPUT OK: 9 ordered edits, final='abXcZ', max input latency 0.116s (threshold 0.750s)`；`OUTPUT OK: first=FAIR-00001 … last=FAIR-05000 … total=5000`；`IDLE OK: raw accumulator cleared; 2.2s produced 0 terminal bytes` |
| 2 | `render_diff_smoke.py` | PASS | `REPAIR-DRAIN OK: borders intact after clearing input, silence restored` |
| 3 | `stream_box_smoke.py` | PASS | `静态单框 OK: 回合结束后输入框完整` |
| 4 | `resize_smoke.py` | PASS | 真 SIGWINCH + generation settle `ESC[3J` 重放通过 |
| 5 | `permission_smoke.py` | PASS | `/exit OK: 10s 内退出，无线程卡死` |
| 6 | `interjection_smoke.py` | PASS | 桩收到 3 次请求顺序正确（插话送达/补历史） |
| 7 | `attachment_smoke.py` | PASS | `SMOKE PASS (5 断言)` |
| 8 | `clear_smoke.py` | PASS | `/help` → `/clear` 清屏恢复横幅 |
| 9 | `background_smoke.py` | PASS | 后台派发/自动续回合/终止序列完整 |
| 10 | `attention_smoke.py` | PASS | `Restore OK (default title written back)`（DONE 标题/BEL/恢复） |
| 11 | `edit_shortcut_smoke.py` | PASS | `Alt+B OK ('foo Xbar' rendered)`（Ctrl+W/U/A/K、Alt+B） |

idle 零字节与有界输入延迟两个专项均含在上表 #1（0 bytes / max latency 0.116s）。

## 5. Terminal.app 人工验收（Step 5）

**Terminal.app manual validation not performed，待用户执行。** 本任务为终端环境人工操作，无法由实施代理完成；依据 brief 要求，**不声称 Terminal.app 崩溃已根治或已被验证**。自动 PTY 只证明事件调度、ANSI 坐标与输入公平性，不证明 Cocoa IME 或 Terminal.app 内部稳定性（设计 §16.1 明示此边界）。

### 用户人工验收步骤清单（照做即可）

**准备**

1. 构建：仓库根目录执行 `mvn -q -pl springai-tamboui-inline-patch -am install -DskipTests && mvn -q -pl springai-code-tui -am package -DskipTests && mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt`；
2. 在 macOS **Terminal.app**（非 iTM/tmux——验收对象就是 Terminal.app 自身）开两个独立窗口；
3. 每窗口运行：`java -cp springai-code-tui/target/classes:$(cat springai-code-tui/target/cp.txt) io.github.javaside.springai.codetui.CodeTuiApplication`（任一窗口配一个 key 即可，无 key 会提示退出——届时补 `DEEPSEEK_API_KEY` 等任一环境变量）。

**步骤（两窗口同步进行，建议录屏 + 记时）**

1. **双窗口持续输出**：两窗口各发一条会持续大输出的请求（例如「输出 3000 行编号文本，每行 `LINE-<n>-<随机填充>`」）；输出期间两窗口都保持滚动；
2. **输出中英文连续输入**：在输出进行时连续输入英文（含快速 Backspace 连删、←/→ 光标移动、Home/End）；感受并记录按键到画面出现的延迟；
3. **中文 IME**：切到中文输入法（拼音），在输出仍在滚动的窗口反复：预编辑（打拼音不上屏）→ 方向键/数字键换候选 → Esc 取消预编辑 → 选词上屏；每个动作重复 ≥5 轮，观察拼音浮窗是否紧跟光标、上屏后边框/光标是否错位；
4. **持续时长与规模**：记录总运行时长（建议 ≥5 分钟连续输出+输入）、窗口数（2）、输出规模（行数/字节量级）；
5. **观察项**（逐项记录）：
   - 输入延迟：输出期间按键到回显的体感延迟（自动 PTY 实测 max 0.116s，人工应无感卡顿）；
   - 边框/光标位移：输入框圆角边框是否破损、光标是否跳到错误行/列（尤其 IME 上屏后）；
   - 画面撕裂/残迹：scrollback 与 live 区交界是否出现错行、残字；
   - Terminal.app 是否崩溃关闭（窗口整个消失/重启对话）；
   - 空闲表现：两窗口输出全部结束后静置 ≥10s，画面应完全静止（事件驱动下无周期重绘）；
6. **若 Terminal.app 崩溃**：保存 crash report（系统会弹「Terminal 已意外退出」→ 查看/导出，或 `~/Library/Logs/DiagnosticReports/Terminal-*.ips`），并检查栈中是否出现：GCD kevent 相关帧、`setMarkedText:`、`selectedRange`、`NSTextInputContext`、`IMKInputSession`——这些是历史崩溃的特征路径；把 report 一并反馈。

**结果记录模板**：`时长 / 窗口数 / 输出规模 / 输入延迟 / 位移观察 / 撕裂观察 / 崩溃(有无+report) / 空闲静止(是/否)`。

## 6. 最终核验记录（Step 7）

- `git status --short` → **空**（无意外未提交文件）；
- `git log --oneline -15` 首行：`2e98b9ce docs(code-tui): document event-driven UI flow and purge stale polling comments`，其后为 task-9 及更早的每任务聚焦提交（`24dd76c5`、`bc47c60b`、`ab27054b`、`ae12e947`、`88147b26`、`6c36fbbd`、`457ba24a`、`3ee16f06`、`6d8edac0`、`9317a291`、`509054a3`、`68d47970`、`144d4b52`、`10bc2c90`）。

## 7. 自审

- 提交前用 `git diff -U0 -- '*.java' | grep -vE '^\s*(\*|//|/\*)'` 逐行核验：**Java 变更全部是注释**（含 3 处行尾注释措辞），无逻辑 diff；
- MANIFEST 顺序检查首轮脚本因搜索串漏了版本号误报 False，用带版本 artifact 名复核后确认为 True——已修正检查方法并保留两条证据；
- 清理只动了 main 代码与两份用户可见文档/一个 pom 注释；测试 fixture、历史计划/设计文档、release notes 未动；
- 「每帧」在 main 代码中余下的每一处都逐条分类（B 历史 / C 现行真实机制），没有以「批量替换」方式误伤语义（例如 StatusBar 的 66ms→132ms 是随语义同步修正数值，不是机械替换）；
- 报告如实区分「自动 PTY 已验证」与「Terminal.app 未验证」，未越界表述。

## 8. Concerns

1. **Terminal.app 人工验收是本设计完成标准（§17 第 10 条）的未闭环项**，需用户按 §5 清单执行并回报；在此之前不能宣称终端崩溃问题根治。
2. `MAX_DRAIN_NANOS=12ms` 的取值依据注释仍标注「未做实测标定」，待 Terminal.app 实机验收时以「输出期间按键延迟」回标（该标注按 brief 保留为待办，非遗漏）。
3. `implementation-map.md` §4 事件驱动链路图为文字对齐版（brief Step 1 的 ASCII 链路逐行映射），如后续需要可在 README 或 guide 层再加一份面向使用者的简化说明——本期未做，避免范围膨胀。
4. PTY 脚本 11 项为 brief 指定集合；README 里另有 `memory`/`model_memory`（本地）与 `mcp`/`mcp_manage`（需 npx、可能联网）未在本轮重跑，前者非 brief 要求、后者明确排除在无网络集合外。

---

# Fix Round 1（M-1）：已删除 drain()/全局帧机制的「现行表述」残留注释清理

**状态：PASS**（纯注释/javadoc 改动，代码零变更自证 + 编译通过）

- Worktree: `/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui`
- Commit: `5618f57b` `docs(code-tui): purge stale drain/global-frame comments (fix round M-1)`（8 files, +35/−35，全部为注释行）
- 审查发现：上一轮清理漏掉了仍把已删除的 drain()/全局帧当「现行机制」描述的残留注释（含 1 处悬空 javadoc 链接）。

## 修正清单（每处一行：文件:行(旧) 旧 → 新）

### 审查标记的 21 处

| 位置 | 旧 → 新 |
| --- | --- |
| `CodeTuiView.java:158` | 「只在渲染线程（drain / 按键事件线程）读写」→「只在 UI 线程（更新批 / 按键事件）读写」 |
| `CodeTuiView.java:182` | 「只在渲染线程（drain）读写」→「只在 UI 线程（更新批）读写」 |
| `CodeTuiView.java:218` | 「只在渲染线程读写（sink 打印与重放都在 drain/渲染线程）」→「只在 UI 线程读写（sink 打印在输出批、resize 重放在 UI 线程的一次性任务里）」 |
| `CodeTuiView.java:225` | 「{@link #drainInsideBatch}」（悬空链接，方法已不存在）→「{@link #processUpdatesInsideBatch}」（现行方法，787 行处存在，编译验证） |
| `CodeTuiView.java:365` | 「drain 里的计划正文下沉」→「UI 批里的计划正文下沉」 |
| `CodeTuiView.java:525` | 「运行器逐帧跟随」→「运行器每次重绘跟随」（与 pom.xml 口径一致） |
| `CodeTuiView.java:562` | 「drain 排在 pollQueued 之前」→「消费插话的段排在 pollQueued 之前」 |
| `CodeTuiView.java:1006` | 「判定与提交在同一帧内完成：drain 跑在渲染线程（单线程）」→「判定与提交在同一 UI 批内完成：批跑在 UI 线程（单线程）」 |
| `CodeTuiView.java:1680` | 「下一拍 drain 恢复默认标题」→「下一个 UI 批恢复默认标题」 |
| `CodeTuiView.java:2069` | 「pushInfo 经 drain 下沉 scrollback」→「pushInfo 经输出批下沉 scrollback」 |
| `CodeTuiView.java:2756` | 「见 drain 的降级」→「见 UI 批的降级」 |
| `CodeTuiView.java:2873` | 「避免 drain 再次进入」→「避免 UI 批再次进入」 |
| `CodeTuiView.java:2875` | 「drain 会反复重入」→「UI 批会反复重入」 |
| `CodeTuiView.java:3110` | 「外部取消后 drain 的『队首已不是它』分支」→「外部取消后 UI 批的『队首已不是它』分支」 |
| `CodeTuiView.java:3890` | 「complete 行由 drain 下沉 scrollback」→「complete 行由输出批下沉 scrollback」 |
| `AttentionTracker.java:84` | 「在<b>下一拍 drain</b> 恢复默认标题」→「在<b>下一个 UI 批</b> 恢复默认标题」 |
| `TerminalAttention.java:24` | 「渲染线程（drain 内，两帧之间）」→「渲染线程（UI 批内，两帧之间）」 |
| `OutputCursor.java:29` | 「绝不把异常抛进 drain 循环」→「绝不把异常抛进 UI 更新批」 |
| `ScrollbackPrinter.java:216` | 「drain 的 default 分支」→「输出段的 default 分支」 |
| `HistoryReplay.java:17` | 「喂进正常的 scrollback drain 通道回放」→「喂进正常的 scrollback 输出队列通道回放」 |
| `ConversationState.java:35` | 「写在 Reactor 线程、读/drain 在渲染线程」→「写在 Reactor 线程、读/输出批消费在 UI 线程」 |

### 清理验证中发现的同类残留（同属 M-1，一并修正）

| 位置 | 旧 → 新 |
| --- | --- |
| `CodeTuiView.java:490` | 「drainInsideBatch 内跨段共享…预算」→「processUpdatesInsideBatch 内跨段共享…预算」（悬空方法名） |
| `CodeTuiView.java:494/496` | 「本 tick 的共享 drain deadline…在 drainInsideBatch 开头计算一次，本 tick 的所有输出段共用」→「本批的共享 drain deadline…在 processUpdatesInsideBatch 开头计算一次，本批的所有输出段共用」（悬空方法名 + 全局 tick 词汇） |
| `CodeTuiView.java:501` | 「+ 本 tick 的共享时间预算」→「+ 本批的共享时间预算」 |
| `CodeTuiView.java:1227` | 「断言 drain 接线的边沿落点」→「断言 UI 批接线的边沿落点」 |
| `CodeTuiView.java:1234` | 「测试里没有 drain 循环」→「测试里没有 UI 批循环」 |
| `ConversationState.java:292` | 「走正常 drain 通道下沉」→「走正常输出队列通道下沉」 |
| `ConversationState.java:512-515` | 「估本帧写了多少…等下一帧…一帧灌几百 KB」→「估本批写了多少…等下一批…一批灌几百 KB」 |
| `PhysicalOutputQueue.java:30` | 「单 tick 最坏会超出预算这一笔」→「单批最坏会超出预算这一笔」 |
| `PhysicalOutputQueue.java:38-39` | 「同一 UI tick 的多个 drain 段之间共享…单 tick 最坏 2×预算」→「同一 UI 批的多个 drain 段之间共享…单批最坏 2×预算」（「drain 段」为现行合法提法：指 `drain()` 方法调用段） |
| `PhysicalOutputQueue.java:113` | 「同一 UI tick 的多个 drain 段应传同一个值（per-tick 共享预算）」→「同一 UI 批的多个 drain 段应传同一个值（批内共享预算）」 |

## 保留不动（红线核查）

- **真实标识符**：`MAX_ROWS_PER_DRAIN`、`MAX_DRAIN_NANOS`、`drainQueuedOutput`、`PhysicalOutputQueue.drain`（方法本体及其 `{@link #drain}`/「drain 段」等合法提法）、`drainPending`、`drainForInjection`、`drainForRefill`、`drainingSubagentsHint`、`drainDeadlinesObserved` 等全部未动；
- **历史叙述（明确标注旧称/已删除）**：`CodeTuiView:448/472/612/718`（「不再有常驻 tick / 66ms drain」「旧 drain 周期（66ms）同值」「旧 tickRate… 本次重构删除」「严格保持旧 drainInsideBatch 的顺序」——「旧」标注明确，按红线保留）、`ContextUsageRefreshController:19`（「取代旧『视图 drain…』」）、`OutputCursor:6`（「旧 drain 的限速单位」）、`BackgroundTaskRegistry:35/113`（「删 drain 轮询后」——叙述的是删除事实）、release notes、`docs/superpowers/plans|specs` 全部未动；
- **现行真实机制**：动词性「drain 队列/drain 轮到它时」（指现行 `PhysicalOutputQueue.drain()` 调用）、「一批 300 行」「每批」等批语义均为现行真实机制，保留。

## 自证方式

1. **代码零变更**：python 状态机剥离注释（`//` 行注释、`/* */` 块注释；字符串/字符字面量内容置空以防注释标记误判）后逐字节对比 `git show HEAD:<file>` 与工作区文件 —— **8 个文件全部「code identical」**，剥离器输出 `RESULT: ALL FILES: code (non-comment) content byte-identical to HEAD`；
2. **diff 人工核对**：`git diff -U0` 过滤 `^\s*(\*|//|/\*)` 后的全部 35 对 -/+ 行逐条目检，无一含代码 token；
3. **编译**：`mvn -pl springai-code-tui -am -DskipTests package` → **exit 0**，`springai-code-tui/target/springai-code-tui.jar` 正常产出（证明 `{@link #processUpdatesInsideBatch}` 链接目标存在，无悬空引用）；
4. 提交聚焦：commit 只含上述 8 个 java 文件，`git status` 干净。

---

# Fix Round 2（F-1）：ctxUsageForTest javadoc 中「animTick 永不推进」残留 + 「两帧之间」措辞

**状态：PASS**（纯注释改动 3 行，代码零变更自证 + 编译通过）

- Worktree: `/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui`
- 审查发现：① `CodeTuiView.ctxUsageForTest()` 的 javadoc 后半句「animTick 永不推进」是已删耦合的残留——现行 ctxUsage 刷新触发点是 OUTPUT|CONTROL 脏位 → `ctxUsageController.markDirty()`（防抖/单飞调度，见 `ContextUsageRefreshController`），与 animTick 无关；且测试 `animation_framesAdvanceAnimTick` 能推进 animTick，原句双重失实；② 顺手项：两处「两帧之间」在无全局帧的事件驱动模型下措辞不准。

## 修正清单（3 处）

| 位置 | 旧 → 新 | 理由 |
| --- | --- | --- |
| `CodeTuiView.java:1234` | 「测试专用：直接驱动上下文用量刷新（测试里没有 UI 批循环，animTick 永不推进）。」→「测试专用：直接驱动上下文用量刷新（测试里没有事件循环，refresh 经 markDirty 防抖异步调度，测试需要同步结果）。」 | 唯一修复项 F-1。现行机制：调用方（CacheHitStatusBarWidthTest 等 3 个测试）直接调 `ctxUsageForTest().refresh()` 取同步结果，绕开的正是 markDirty 的防抖/单飞异步调度（500ms 防抖 + executor 提交），而非已删除的 animTick 周期触发（那已在 Task 6/7 被控制器取代）。 |
| `AttentionTracker.java:85` | 「标题写回必须留在渲染线程（两帧之间）」→「（两次绘制之间）」 | 事件驱动后没有全局帧；「帧」仅在忙态动画帧补排语境存在，此处指的是任意两次绘制之间的间隙。 |
| `TerminalAttention.java:24` | 「所有调用都发生在渲染线程（UI 批内，两帧之间）」→「（UI 批内，两次绘制之间）」 | 同上，顺手纯注释项。 |

## 自证方式

1. **代码零变更**：`git diff` 共 3 对 -/+ 行，逐行目检全部为 javadoc/块注释行内的措辞替换，无任何代码 token 变更；
2. **编译**：`mvn -pl springai-code-tui -am -DskipTests package -q` → **exit 0**；
3. 提交聚焦：commit 只含上述 3 个 java 文件与本报告（报告入库与前两轮先例一致，需 `-f`）。

## Concerns（本轮）

1. 无新增；Terminal.app 人工验收仍未闭环（沿用 §8 第 1 条）。



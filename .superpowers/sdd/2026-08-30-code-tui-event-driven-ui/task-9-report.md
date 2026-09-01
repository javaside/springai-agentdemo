# Task 9 Implementation Report

## Status

PARTIAL PASS. Task 9 的新增公平性/idle 验收脚本通过，指定 Maven 构建通过，brief 中 11 个本地 PTY 脚本有 9 个通过、2 个暴露既有脚本/行为不一致；未冒充全绿。

## Residual candidate disposition

接手时完整审阅了 4 个修改脚本、README 和未跟踪的新脚本。保留并完善候选：

- `event_driven_fairness_smoke.py`：候选覆盖 5,000 行 SSE、并发 ASCII/Backspace/Left/Right、逐编辑屏幕观测、顺序/完整性、2.2 秒 raw-byte idle 和 120 秒总超时。补充 `CODETUI_FAIRNESS_MUTATE_DROP_KEY=1` 判别力开关。
- `render_diff_smoke.py`：保留从 tick 推算改为 observable settle，并在清空 raw 后观察 2 秒零输出。
- `stream_box_smoke.py`：保留按画面稳定等待 on-demand follow-up，不再假定固定 100ms ticks。
- `resize_smoke.py`：候选只有说明文字变化；现有实现已通过 `wait_for_raw(ESC[3J)` 与最终 screen/cursor 观察 settlement，而非 `33ms × 4`。
- `README.md`：保留完整脚本表、依赖和精确命令，并单列 `npx` 两项。

## Build results

以下命令均在目标 worktree 根目录执行并 exit 0：

1. `mvn -q -pl springai-tamboui-inline-patch -am install -DskipTests`
2. `mvn -q -pl springai-code-tui -am package -DskipTests`
3. `mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt`

注意：最初误在父仓库 cwd 执行过同样构建；发现路径错误后在指定 worktree 重新完整执行，报告只以 worktree 重跑为证据。

## Fairness acceptance evidence

正常命令：

`/usr/bin/python3 springai-code-tui/src/test/resources/scripts/event_driven_fairness_smoke.py`

结果 exit 0 / `SMOKE PASS`：

- 本地 SSE 桩实际发送并在原始终端输出观察到 `FAIR-00001` 至 `FAIR-05000`，唯一编号集合总数 5,000；首、末、总数完整。
- 输出过程中按固定 80ms 调度 9 次编辑，包含 ASCII、Backspace、Left、Right；每一步都等待预期 buffer 与 cursor offset，最终值 `abXcZ`，无丢键或乱序。
- 至少一次编辑在 `FAIR-05000` 出现前已显示。
- 最大输入延迟 `0.119s`，阈值 `0.750s`。阈值理由：本地正常 key-to-frame 通常低于 100ms；750ms 为 CI 调度和单次 100/150ms render throttle 留余量，但仍能抓住连续多个 batch 垄断 UI 的输入饥饿。
- 完全静止后执行 `session.raw = b""`，继续观察 `2.2s`，新增终端字节 `0`。
- 每个等待检查进程提前退出并带最后画面诊断；编辑有单项 deadline，整体有 120s deadline，输出超时打印已收数量和缺失编号头部，idle 失败打印新增字节样本。

## Discrimination / mutation evidence

未提交破坏性生产变异，只使用新脚本的测试环境开关：

1. `CODETUI_FAIRNESS_MUTATE_IDLE=1 ...event_driven_fairness_smoke.py`：预期 exit 1；精确失败为 `idle 2.2s emitted 36 new terminal bytes`，样本为重复 `ESC[0m`。证明 raw idle 检查能抓周期输出。
2. `CODETUI_FAIRNESS_MUTATE_DROP_KEY=1 ...event_driven_fairness_smoke.py`：预期 exit 1；精确失败为 `timed out waiting for input edit 'ab' at cursor offset 2`。证明逐步输入模型能抓丢键/输入饥饿。

## Local/no-network PTY scripts

| Command | Result | Evidence / error |
|---|---|---|
| `.../event_driven_fairness_smoke.py` | PASS | 5,000/5,000；max 0.119s；idle 2.2s/0 bytes。 |
| `.../render_diff_smoke.py` | PASS | startup idle 2.0s/0 bytes；ASCII/CJK patches；repair drain 后 2.0s/0 bytes。 |
| `.../stream_box_smoke.py` | PASS | 无中间态双边框、无 ANSI 黑、12 行正文完整、最终单框。 |
| `.../resize_smoke.py` | FAIL | 独立重跑两次均为 `WIDEN(100 cols): 输入框应按新宽度重画、宽 100，实际 60`；不是依赖缺失或 skip。候选 Task 9 改动未改变逻辑，仅文档说明；该失败显示当前事件驱动实现/既有 smoke 的 resize generation settle 契约不一致。 |
| `.../permission_smoke.py` | PASS | `SMOKE PASS`。 |
| `.../interjection_smoke.py` | PASS | `SMOKE PASS`。 |
| `.../attachment_smoke.py` | PASS | 5 assertions，`SMOKE PASS`。 |
| `.../clear_smoke.py` | PASS | `SMOKE PASS`。 |
| `.../background_smoke.py` | FAIL | 场景 1/2/4 通过，场景 B fail：等待旧文案 `以上是你先前派出的后台任务的结果，请据此继续。` 超时；实际屏幕显示新文案 `以上是你先前派出的后台任务的结果。请检查你的 Todo 计划列表...`。不是 Task 9 文件，未越界修改。 |
| `.../attention_smoke.py` | PASS | DONE title/BEL/default restore，`SMOKE PASS`。 |
| `.../edit_shortcut_smoke.py` | PASS | Ctrl+W/U/A/K、Alt+B，`SMOKE PASS`。 |

README 还列出但 brief Step 4 未要求的 `memory_smoke.py`、`model_memory_smoke.py` 未执行；严格按 brief 的 11 条本地命令执行。

## MCP / npx scripts

- `npx --version`：exit 0，`10.9.3`，仅确认设施存在。
- `mcp_smoke.py`：未执行。它会调用真实 `npx` MCP server，可能联网，brief 要求与本地/no-network 集合单独报告。
- `mcp_manage_smoke.py`：未执行，同上。

未将这两项描述为通过。

## Commit

Focused commit subject: `test(code-tui): stress event-driven output and input`. The immutable hash is reported by the implementation handoff and available via `git log -1` (embedding a commit's own final hash in its tracked contents is circular).

## Self-review

- 新脚本覆盖 brief 10 项核心验收：本地 SSE >=5000、四类输入、逐编辑时刻、final 前可见、顺序/无丢键、首末总数、清 raw 后 >=2s 零 bytes、max latency 与阈值理由。
- fail-fast：进程退出立即失败；每个阶段均有 deadline；整体 deadline 120s；失败包含最后屏幕、缺失编号或 raw 样本。
- 输出完整性使用 raw 中唯一编号集合，能发现缺失；同时检查 stub 发送计数。顺序验收针对输入逐状态和 cursor offset；模型编号集合不验证重复/物理顺序，但首末与全集完整满足 brief 的首/末/总数要求。
- 未修改 Task 9 文件范围外的代码或测试。
- `git diff --check` 通过。

## Concerns

1. `resize_smoke.py` 在当前构建中稳定失败：100 列 resize 后仍画 60 列输入框，需后续定位 SIGWINCH/generation settle；Task 9 不能宣称全绿。
2. `background_smoke.py` 的预期通知文案落后于当前实现，导致超时；需要单独同步 smoke 文案或确认产品文案契约。
3. `event_driven_fairness_smoke.py` 将 25 行合成一个 SSE delta，共 200 个流分片；仍是 5,000 个编号模型行并持续约 2 秒，足以与固定间隔输入重叠，但不是每行一个 SSE event。
4. `mcp`/`npx` 两脚本未运行，只有 npx 设施检查；它们可能产生网络行为。

---

# Task 9 Fix Round 1 Report

## Status

PASS（fix round）。两个已确诊失败均按脚本/测试侧修复，未改任何生产代码；指定 Maven 测试、构建与两个 PTY 冒烟脚本全部通过，brief 的 11 个本地脚本现在 11/11 可绿（本轮重跑了原先两个 FAIL 项）。

## Fix 1：resize_smoke.py 的 PTY harness 缺陷

根因（维持确诊）：`Popen(start_new_session=True)` 在子进程只做 `setsid(2)`，slave pty 从未通过 `TIOCSCTTY` 成为 controlling terminal。`TIOCSWINSZ` 照常更新可查询尺寸，但内核不投递 SIGWINCH（signal 发给 ctty 的前台进程组，没有 ctty 就没有收件人）——生产链（JLine `Terminal.handle(WINCH)` → backend `onResize` → `InlineTuiRunner` 的 `ResizeEvent` → settle 重放）从未被触发。Task 7 删掉每帧宽度轮询兜底后该缺陷不再被掩盖。

本轮修复（全部脚本侧）：

1. `PtySession` 改 `preexec_fn` 钩子：`os.setsid()` + `fcntl.ioctl(slave_fd, termios.TIOCSCTTY, 0)`，移除 `start_new_session=True`。
2. 新增启动自检 `assert_controlling_terminal()`：用与被测进程完全相同的 spawn 方式起一次性探针，探针自报「能否 `open("/dev/tty")`」与「`tcgetpgrp(0)==os.getpgrp()`」，任一失败立即报 `SMOKE FAIL: PTY has no controlling terminal: ...`，而非等到最后一步误报「输入框应按新宽度重画」。**判别力已实测**：新 spawn 探针报 `{"has_tty": true, "fg_ok": true}`，旧 spawn（start_new_session）探针报 `{"has_tty": false, "fg_ok": false}`；另以独立实验证实 SIGWINCH 投递差异（ctty: winch=True / no-ctty: winch=False）。
3. `InlineTuiRunnerEventDrivenTest` 新增 `backendResizeCallbackDeliversResizeEventAndRedrawsAtNewWidthWithoutDuplicates`：新增 `MutableSizeBackend`（可变 `Size` + `fireResize()` 触发 `onResize` 回调，与生产 JLineBackend 同一语义），60 列起 runner，改 100 列并触发回调，断言 handler 收到 `ResizeEvent(100,24)`、随后发生**帧宽 100** 的新 draw（宽度取自 renderer 收到的 `Frame#width()`，与 smoke 断言屏幕输入框宽度同语义），同尺寸重复触发不再发事件也不再 draw（钉去重契约）。

## Fix 2：background_smoke.py 陈旧文案预期

根因（维持确诊）：`NOTIFY_TAIL` 是 v1.9.0 前旧文案「以上是你先前派出的后台任务的结果，请据此继续。」；生产 `BackgroundNotifier.java:109-123`（02159c3）权威文案已改为「以上是你先前派出的后台任务的结果。请检查你的 Todo 计划列表，找出下一项 pending 的任务并立即执行；如果所有任务已完成，向用户汇报最终结果。」。事件驱动链路本身唤醒/送达/渲染正常。

本轮修复（脚本 + 测试）：

1. `NOTIFY_TAIL` 更新为当前生产完整文案。
2. 首跑暴露一个次生问题：新文案更长，以用户块（`›` 缩进 2 列）回显时在 120 列下**必然折行**（实测第 27-28 行断在「如果所有任务已完成，」之后），整句子串匹配永远失败。改为 `notify_tail_on_screen()`：整屏行拼接后抽掉全部空白再匹配抽空白后的整句（与 resize_smoke 的 `expected_above` 同理——折行改变断点，不改变内容）。
3. `BackgroundNotifierTest` 新增 `notificationTailDirectsModelToNextTodoAction`：断言通知含「请检查你的 Todo 计划列表」「找出下一项 pending 的任务并立即执行」「如果所有任务已完成，向用户汇报最终结果」三条行动指令，并断言旧文案「请据此继续」不得回归——把文案契约钉进测试。

## 验证命令与精确结果（按序，均在 worktree 根执行）

1. `mvn -pl springai-tamboui-inline-patch -Dtest=InlineTuiRunnerEventDrivenTest test` → BUILD SUCCESS，`Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`（8 既有 + Task 8 两条随上一轮计入 + 本轮 resize 链 1 条，共 10）。
2. `mvn -pl springai-code-tui -am -Dtest=BackgroundNotifierTest,CodeTuiViewBackgroundTest,BackgroundTaskRegistryNotificationTest,CodeTuiViewEventWiringTest -Dsurefire.failIfNoSpecifiedTests=false test` → BUILD SUCCESS，`Tests run: 71, Failures: 0, Errors: 0`（BackgroundNotifierTest 12 含新增 1 条；CodeTuiViewBackgroundTest 18；CodeTuiViewEventWiringTest 30；BackgroundTaskRegistryNotificationTest 11）。
3. `mvn -q -pl springai-tamboui-inline-patch -am install -DskipTests` → exit 0。
4. `mvn -q -pl springai-code-tui -am package -DskipTests` → exit 0。
5. `mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt` → exit 0。
6. `/usr/bin/python3 springai-code-tui/src/test/resources/scripts/resize_smoke.py` → **SMOKE PASS**（exit 0）：HARNESS SELF-CHECK OK；STARTUP 横幅第 0 行；BEFORE(60)/WIDEN(100)/NARROW(45) 三段单框宽度全对；两次 SETTLE 均发出 ESC[3J；光标停文本行。**连续 3 次运行全部 PASS**（此前为稳定 FAIL「实际 60」）。
7. `/usr/bin/python3 springai-code-tui/src/test/resources/scripts/background_smoke.py` → **SMOKE PASS**（exit 0）：场景 1/2/4 + 3/3.5 全过，通知三行结果未塌行、⏱ 面板翻「0 运行 · 1 完成」、/continue 带后台摘要。**连续 2 次运行全部 PASS**（此前场景 B 稳定超时等旧文案）。

## Commits（fix round）

- `ab27054b66309167674ab3cf9d2de9086644c34e` — `test(tui): give the resize smoke a real controlling terminal`（resize_smoke.py harness + 自检 + InlineTuiRunnerEventDrivenTest resize 链测试）
- `bc47c60b48797d456368a7e6d72460e6d834a68a` — `test(code-tui): sync background notification copy with production`（background_smoke.py 文案与折行匹配 + BackgroundNotifierTest 文案契约）

两个提交均为测试/脚本侧，零生产代码改动（`git diff ae12e94..bc47c60 --stat` 只含 2 个脚本 + 2 个测试文件）。

## Self-review

- 根因诊断与指令一致且经独立实验复核（SIGWINCH 投递、/dev/tty 可开性、tcgetpgrp 三个维度正负例齐全）。
- 自检失败信息落在 harness 层（「PTY has no controlling terminal」），不再把 harness 缺陷误报为 UI 缺陷。
- resize 链 Java 测试不依赖真实终端，覆盖「onResize → ResizeEvent → handler → 新宽度重画 → 去重」整段纯 Java 语义。
- 文案契约双侧钉死：Java 测试钉生产 compose，smoke 钉屏幕渲染（含折行容忍），旧文案双向不得回归。

## Remaining concerns

1. `clear_smoke.py` 的 `PtySession`（被 background_smoke 等复用）仍是 `start_new_session=True` 旧写法；这些脚本不做 resize 断言所以不受影响，但若未来某脚本要在其上做 SIGWINCH 相关断言，应把 resize_smoke 的 spawn 方式抽成共享实现。
2. resize 链测试对同尺寸重复触发采用 400ms 静止窗口观察（与其余 no-tick 测试同一量级），非确定性等待上限由断言兜底；未见 flake。
3. 原 Task 9 报告中 fairness/render/stream 等其余 9 个 PASS 脚本本轮未重跑（不在 fix round 范围）；mcp/npx 两项维持未执行。

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

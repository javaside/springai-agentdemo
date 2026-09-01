# 全分支终审（final review）修复报告

**状态：PASS（3 项发现全部修复；Terminal.app 人工验收仍未执行，不声称终端崩溃已验证）**

- Worktree: `/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui`
- 输入：终审报告三发现 I-1 / I-2 / M-4（prompt 列出；task-10-report.md 未含终审正文，以 prompt 为准）。
- 变更性质：1 个 Java 可见性/javadoc 改动（零行为变更）、1 个纯报告文档改动、2 个测试脚本健壮性/死常量改动。

---

## I-1 死构造与账本裁决矛盾 → 已修复（降 package-private + javadoc 裁定同步）

**发现**：`UiUpdateCoordinator.java:129-133` 的公有构造 `UiUpdateCoordinator(InlineTuiRunner, ...)` 全库零调用者——Task 7 生产实际走 Consumer 接缝构造（`CodeTuiView.java:419`，构造期 `runner()==null` 必须惰性桥接），偏离账本 Task 3 note「Task 7 接线应使用 InlineTuiRunner 版本」的裁定；且该死构造与 Task 3 M-7（接缝构造应为 package-private）互相纠缠，账本与代码各说各话。

**修复**（`springai-code-tui/src/main/java/.../ui/update/UiUpdateCoordinator.java:125-160`）：

1. **`InlineTuiRunner` 版构造 `public` → package-private**，javadoc 整体重写为「预留」定位：明确生产经 Consumer 接缝惰性桥接（CodeTuiView 构造期 runner 为 null）、当前全库无调用者、供未来直接持有 runner 的装配与同包测试使用、与接缝构造行为一致（直接委托）。不删除的理由：它是「runner 直连」这一自然装配形态的最小表达，删掉后未来要用时得重写同一委托；缩窄到包内已消除「装配层误用」与「死 public API」两个问题，保留成本为零。
2. **M-7 同步裁决：Consumer 构造维持 public**。核实调用方：`CodeTuiView`（`ui` 包，本类在 `ui.update` 子包，**跨包**）是生产唯一装配点——按 Task 3 note 原裁定降 package-private 会直接编译失败。`UiUpdateCoordinatorTest` 虽在同包可测 package-private，但生产跨包调用方存在，故 M-7 结论为「保持 public」，并已在 Consumer 构造 javadoc 写明：它是 Task 7 起的生产构造入口 + 跨包调用方 CodeTuiView + 惰性解析 runner 的接缝语义。Task 3 note 的「应使用 InlineTuiRunner 版本」裁定与 Task 7 实现的矛盾在两处 javadoc 中均有交代（偏离合理）。

**行为影响：零**——构造器本体一行未动，仅可见性修饰符与 javadoc；`mvn -pl springai-code-tui -am -DskipTests compile` 通过（跨包无任何调用该构造的代码，缩窄不破编译）。

## I-2 Terminal.app 验收清单缺 resize 拖拽步骤 → 已补

**发现**：task-10-report.md §5 人工验收清单缺「resize 拖拽终值重排」——Task 7 删除宽度轮询兜底后这是**唯一无自动覆盖的残余风险**（resize_smoke.py 只做单次定步 TIOCSWINSZ，模拟不了连续拖拽的多次 SIGWINCH 连发）。

**修复**：清单插入为步骤 4（原 4-6 顺延为 5-7）：拖拽窗口宽度多次（宽↔窄快速连拖后停住），确认停稳约 150ms（132ms `RESIZE_SETTLE_DELAY` settle 窗口）后界面按**最终**宽度重排——输入框边框贴合新宽度、scrollback 干净重放无残影/错位；并写明「若终端卡在旧宽度即为该风险复现信号」与该步骤为何不在自动覆盖内（PTY 冒烟只覆盖单次 resize）。纯报告文档改动，入库带 `-f`（`.superpowers/` 在 .gitignore，与历轮先例一致）。

## M-4 探针 Popen fd 泄漏 + 顺手死常量 → 已修复

**发现**：`resize_smoke.py` `assert_controlling_terminal` 的 `subprocess.Popen` 在 try 块外——Popen 抛异常（如 preexec_fn 失败）则 master/slave fd 泄漏，且 finally 里 `proc.kill()` 引用未定义的 `proc`（NameError 掩盖原始异常）。

**修复**（`resize_smoke.py:381-427`）：

- `proc = None`、`buf = b""` 先行声明，Popen 移入 try；
- slave_fd 在 Popen 成功后关闭并置 `None`（父进程不留副本）；失败路径 finally 统一清理：`proc is not None` 才 kill、`slave_fd is not None` 才 close（Popen 失败时父进程仍持有 slave，必须关）、master_fd 无条件 close；
- 顶部加注释块说明 fd 生命周期契约。成功路径行为与旧版完全一致（验证：resize_smoke.py 全链 PASS）。

**顺手**：`background_smoke.py:98` 死常量 `NOTIFY_TAIL_PARTS` 删除（全库零引用；真正生效的是 `_squeeze(NOTIFY_TAIL)` 跨行拼接比较，其解释注释保留）。验证：background_smoke.py PASS。

---

## 验证记录

| 命令 | 结果 |
| --- | --- |
| `mvn -pl springai-code-tui -am -Dtest=UiUpdateCoordinatorTest,CodeTuiViewEventWiringTest -Dsurefire.failIfNoSpecifiedTests=false test` | **BUILD SUCCESS**，`Tests run: 45, Failures: 0, Errors: 0, Skipped: 0`（15 + 30） |
| `mvn -pl springai-code-tui -am test`（全模块） | 首跑出现 **1 次偶发失败**：`CodeTuiViewEventWiringTest.continuation_soleSchedulerIsCoordinatorRunBatch`（line 501 `hasPendingContinuation` 瞬时为 false）。**与本修复无关的既有 flake**：随后同一改动下连跑 **3 次全绿**（`Tests run: 1811, Failures: 0, Errors: 0, Skipped: 10`），且改动前干净 HEAD 上定向 5 连跑全绿 + 改动后定向 3 连跑全绿。本修复只动构造器可见性/javadoc（构造器本体零字节变更），无任何能影响调度时序的路径。根因分析见 Concerns 1。 |
| `mvn -q -pl springai-tamboui-inline-patch -am install -DskipTests && mvn -q -pl springai-code-tui -am package -DskipTests && dependency:build-classpath` | **OK** |
| `/usr/bin/python3 .../resize_smoke.py` | **SMOKE PASS**（exit 0；真 SIGWINCH + generation settle ESC[3J 重放、探针自检 HARNESS SELF-CHECK OK——探针走的就是修复后的 fd 生命周期路径） |
| `/usr/bin/python3 .../background_smoke.py` | **SMOKE PASS**（exit 0；三场景 + /exit 有界退出，死常量删除无影响） |
| `python3 -m py_compile` 两脚本 | **SYNTAX OK** |

## 提交

- 单一聚焦提交（见下方 hash）：`UiUpdateCoordinator.java`（I-1）+ `resize_smoke.py` / `background_smoke.py`（M-4）+ `task-10-report.md`（I-2）+ 本报告（`-f`，先例一致）。

## Concerns

1. **既有 flake（非本次引入，建议后续单独修）**：`continuation_soleSchedulerIsCoordinatorRunBatch` 的首断言「`runPendingUiUpdatesForTest()` 后立刻 `hasPendingContinuation()`」存在固有竞态——continuation 以 `Duration.ZERO` 排进单线程 `ScheduledExecutorService`，后台线程可能在断言前就把 future 跑完（`isDone()=true` → `hasPendingContinuation()=false`），全模块运行（调度器线程更热）时概率放大。修法示例：断言改为「排空前任意时刻观察到过 pending，或已开始排空」，或给测试 coordinator 传入受控同步 scheduler。本次未动（超终审范围）。
2. Terminal.app 人工验收仍未闭环（沿用 task-10 §8-1）；I-2 补的 resize 拖拽步骤也在其中，需用户执行。
3. I-1 保留（而非删除）InlineTuiRunner 版构造是低风险判断：package-private + 「预留」javadoc 已把误用面收到包内；若后续账本裁决倾向零死代码，删掉它同样不破任何测试（全库零调用者）。

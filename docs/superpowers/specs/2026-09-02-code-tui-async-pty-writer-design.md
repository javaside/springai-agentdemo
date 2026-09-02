# Code TUI pty 异步写与输出背压设计（「输出时打字卡死」根治）

## 1. 背景

「模型输出文字时用户在输入框打字卡死」在 code-tui 历史上已修复过至少四轮（每帧
PTY 限速、限速按物理行计、事件驱动 UI 重构、事件循环异常防护），但始终复发。事件
驱动重构后调用链已相当规整：任何输出都经 `PhysicalOutputQueue` 双预算（300 物理
行 / 12ms）有界产出，渲染线程（TamboUI 事件循环）按批消费——为什么还会卡，需要
重新找根因，而不是再打一层症状补丁。

## 2. 根因（已实证，非推测）

**渲染线程同步写 pty，而 pty 的内核写缓冲极小、且写阻塞无上界。**

实测证据（macOS 本机 python pty 实验，两轮独立复现）：

- pty 内核写缓冲仅 **~1-2 KiB**（读端不消费时，写满即 EWOULDBLOCK）；
- 读端停摆时，阻塞式 `write(2)` **无限期挂起**（实验中 120s / 3s 均未返回）；
- 读端 50ms 消费一次（模拟终端渲染节流）时，有效吞吐仅 **~18-20 KiB/s**。

调用链定位：`InlineTuiRunner.processUiWake`（渲染线程）→ `InlineDisplay.submit` →
`backend.writeRaw(整批字符串)` + `flush()`。一批 ≤300 物理行的中文 scrollback 是一个
**30-150KB 的单次同步写**。终端读端停摆的典型场景——中文 IME 预编辑合成期终端主
线程忙、终端渲染积压、Terminal.app 自身卡顿——恰好与「模型正在输出」同现。

渲染线程睡死在 `write(2)` 里 → `eventQueue` 里的 KeyEvent 全部排队 → 用户感知
「输出时打字卡死」。为什么前四轮修复无效：它们约束的是**每批生成多少字节**
（预算检查都发生在写之前），而最后一跳的 pty 写本身是无界同步阻塞。Claude Code
不卡的根本差异：Node.js 的 stdout 写是异步的（libuv 用户态缓冲），事件循环永不被
write 阻塞。

## 3. 已确认目标

1. **不变量**：渲染线程永不阻塞在 pty 写上——所有终端字节经异步队列写出。
2. 事件驱动重构的语义保持：静止界面零周期任务、零 ANSI 输出（不引入任何形式的
   常驻 tick）。
3. 协议流完整性：写出顺序严格等于提交顺序；内容不丢（除语义性丢弃点，见 §6）。
4. 内存有界：任何积压场景下，在飞 + 延迟总量有明确上界。
5. 关停有界：终端卡死时退出路径仍可完成（SIGTERM 可杀死、不挂死）。
6. 设备死亡（pty 对端消失）不再静默流失内容，用户得到提示并有序退出。
7. 现有全部功能与用户可见语义保持不变；现有测试全绿。

## 4. 非目标

- 不改变事件驱动 UI 的合并/调度模型（UiUpdateCoordinator 的 dirty-bit 协议不动，
  仅新增 continuation 延迟分量）；
- 不改 `ConversationState` / `PhysicalOutputQueue` 的产出预算（300 行 / 12ms 保留，
  它们管「生成多少」，本设计管「怎么写出去」）；
- 不替换 JLine 后端或引入第三方异步 IO 库；
- 不处理 tmux/多路复用器自身的问题（只保证我们发出的字节序正确）。

## 5. 架构：三层设计

```
渲染线程                          pty-writer（守护线程）        内核 pty → 终端
────────                          ────────────────────        ─────────────
CodeTuiView 背压闸 ──(饱和即停产出)──┐
PhysicalOutputQueue (300行/12ms) │
   ↓ drain                       │
InlineDisplay.submit ────────────┼─▶ [有界队列 1MiB/2MiB] ──▶ write(2)+flush
   ├─ 接受 → 入队                 │      字节在 write 返回后释放
   └─ 拒收 → deferredBatches ─────┘      排空 → (武装后单发)onDrained
        (FIFO≤4,保序合并)                    → requestRender → 重投
```

### 5.1 库层：AsyncPtyWriter（springai-tamboui-inline-patch）

单守护线程消费 `ArrayBlockingQueue<Chunk>`（Chunk = 数据或携带自身 latch 的
FLUSH 标记）。核心机制：

- **两级字节预算**：软预算 1MiB（大批输出撞线即拒）、硬预算 2MiB（小帧——live 区
  差分、打字回显、OSC/BEL——豁免软预算只撞硬预算）。预算按 UTF-16 char 计
  （CJK 实占 ~3×，方向保守）。
- **字节在 `writeRaw` 返回后才释放**（drainLoop 的 finally）：写线程卡在 write 上
  时预算保持占用——背压对「慢设备」真实生效（在 dequeue 时释放等于没限）。
- **队空豁免（前进性）**：`bytesQueued == 0` 时任意单批放行。否则「大于软预算的
  批」（延迟批合并产物、超大单批）会 `0 + cost > budget` 恒真、永远无法入队——
  恢复后死锁。豁免有界性：队空才放行 ⇒ 任一时刻至多一个超批在飞。
- **武装后单发的排空唤醒**：`armWakeup()` 置位（渲染线程批被拒时），写线程把队列
  消化到空的边沿 CAS 消费一次并回调 `drainListener`（runner 注入
  `this::requestRender`——只做唤醒，不碰任何 UI 状态）。未武装时排空零回调：
  事件驱动删除的常驻 tick 不以任何形式回来。
- **错误探针**：JLine 的 `writeRaw` 走 PrintWriter——**吞掉 IOException 只置
  checkError 标志**，写线程永远等不到异常。runner 装配时反射取
  `JLineBackend.writer` 注入 `() -> printWriter.checkError()`，每次 drain 后探测，
  为真即 `markDead`（反射失败降级为无探针）。
- **markDead 语义**：`dead` 置位、队列清空、`bytesQueued` 归零、当前 flush latch
  countdown；此后一切提交 no-op；`isSaturated()` 排除 dead（恒 false）——否则上层
  背压闸永久关死（freeze-forever）。
- **flush 携带自己的 latch**：每次 `flush()` 生成新 latch 存入 FLUSH 标记，写线程
  消费到标记时倒数它自带的 latch——并发 flush 各等各的，无错配。

### 5.2 库层：InlineDisplay 延迟批与屏障

- **延迟批队列 `deferredBatches`**：submit 被拒的整批 payload 进 FIFO（≤4 条，超限
  最老两条**保序拼接合并**——每个批是自包含定位协议，批首 home 定位按「前批已
  落盘」计算，FIFO 拼接后的字节流与逐批顺序写出逐字节相同）。**绝不在渲染线程
  同步直写兜底**（首版缺陷：直写把卡死的 write 原样引回渲染线程）。所有访问收进
  `deferredLock`（正常只在渲染线程；关停窗口 shutdown-hook 与 close 线程并发防护）。
- **重投链**：submit 投新批前先 `retryDeferred`（仍拒即停，保序）；写线程排空 →
  `onDrained` → `requestRender` → 下一帧 submit/retryDeferred 接续。
- **清屏屏障 `clearQueuedOutputAndBarrier(timeout)`**：真清屏（/clear、resize 重放）
  前必须过屏障——清屏字节直写立即落屏，若 writer 队列还积着更早提交的旧批，实际
  字节序变成「清屏 → 旧内容复活」且 ESC[3J 无法重发；在飞批的光标定位假设与已归零
  记账失配，整屏错位不自愈。屏障动作：语义性丢弃延迟批（/clear 本身就是对这段内容
  的否定；resize 重放有 scrollTail 留底兜底）+ 有界等 writer 排空；不成立返回
  false，调用方降级（打印分割线）。
- **release 有界直写**：关停恢复序列（光标重现/复位）在 writer 已关后产生，直写
  会在 pty-writer 持 PrintWriter 锁卡死时挂死退出线程（SIGTERM 杀不死进程）。改为
  一次性守护线程写 + `join(500)` 超时放弃——「可退出」优先于「退出时的完整性」。

### 5.3 应用层：CodeTuiView 背压闸与死亡检测

- **背压闸**：`ptyBackpressured()`（直调 `runner.tuiRunner().isPtyWriteSaturated()`，
  **无反射**——patch 模块是 compile 依赖）为真时，`processUpdatesInsideBatch` 本批
  不取 pending / 流式完整行（留在 state 真相源），等排空唤醒批接续。否则渲染线程
  会无限往 display 延迟队列攒批，内存无界。
- **continuation 退避**：`outputRemaining` 的唯一成因是 pty 饱和（本地
  queue/pending/streaming 全空）时，continuation 用 30ms 退避而非 ZERO——ZERO 会与
  每圈一次 render 形成双线程满载空转（终审发现）；排空唤醒保证链不断。真实存量仍
  ZERO 接续。
- **设备死亡检测**：批尾发现 `isPtyWriterDead()` → 一次性置 notice
  「终端写入失败，即将退出」+ 1s 后 `shutdownAndQuit`（会话事件已原子落盘，退出是
  对「终端没了」最诚实的行为；`isDead` 只由 IOException/checkError 置位，卡死不置
  位，无误杀）。
- **TerminalAttention 改走队列**：OSC/BEL 与内容共享同一个 PrintWriter——
  pty-writer 卡死时持其内部锁，直写几十字节也会冻死渲染线程。改经
  `submitPtyControlSequence`（小帧豁免软预算、保序、被拒静默丢弃——提示是锦上
  添花）。

## 6. 关键决策记录（含被否决的替代方案）

| 决策 | 理由 | 被否决的替代 |
|---|---|---|
| 有界队列 + 守护线程 | 与 Node/libuv 同构，对的选择 | **无界队列**：打字回显排在 MB 级积压后，卡死变分钟级回显延迟 |
| 整批异步 + 延迟批 | 协议流保序的最小单元是批 | **只异步 flush**：JLine 缓冲仅 8KB，150KB 批在 print 内部就同步穿透 |
| 延迟批保序合并 | 绝不丢协议流，也绝不直写兜底 | **直写最老批**（首版）：把卡死 write 引回渲染线程 |
| 清屏屏障 + 降级 | 清屏越过积压会复活旧内容 | **无条件直写清屏**：乱序 + 记账错位不自愈 |
| checkError 探针 | PrintWriter 吞 IOException | 只依赖 IOException：死终端静默流失 |
| 反射 → 公开 API | 跨包 `getMethod` 拿不到包私有方法（B1：闸从未生效） | 反射链（首版）：NoSuchMethodException 被吞、闸恒开 |

## 7. 内存与延迟上界

- writer 在飞：`≤ 2B + M`（B=1MiB 软预算，M=单批上限；条目另受 4096 上限）。
- display 延迟批：条目 ≤4；字节总量由背压闸约束（闸在 `bytesQueued ≥ B` 即关，
  增长 ≤ 一个批周期的产出）。总内存 ≈ `3B + 2M + ε`。
- 关停路径总耗时 ≤ ~4s（flushPendingForClose ≤2s + writer.close ≤1s +
  release join 500ms），全部有界。
- 已知代价（接受）：终端长时间停摆时小帧回显最坏延迟分钟级（排队 ~2MiB 后）；
  BEL/OSC 提示同样迟到——保序是唯一安全解，迟到好于渲染线程冻死。

## 8. 测试策略

- **单元**（AsyncPtyWriterTest，14 例）：卡死 backend 下提交不阻塞、两级预算、
  队空豁免前进性、顺序与 flush 落盘、并发 flush 各等各的、武装后单发（未武装零
  回调）、设备死亡族（IOException/checkError 探针/dead 后 isSaturated 恒
  false/死亡后 no-op）、生命周期。
- **display 集成**（InlineDisplayAsyncWriterTest，6 例）：渲染线程全路径不阻塞、
  延迟批完整/保序/恢复清空、清屏屏障两分支、release 有界。
- **runner 接线 + 症状钉**（InlineTuiRunnerEventDrivenTest#
  keyEventsProcessedWhilePtyWriteIsStuck）：pty 写卡死期间按键 1s 内被事件循环处
  理且大块输出确实进延迟批——删掉 `useAsyncWriter` 接线该测试即红。
- **应用层**（OutputBackpressureGateTest，5 例）：饱和门控/正常不受影响/饱和续
  链/恢复接续/死亡后闸打开（防 freeze-forever）。
- **结构钉**：TerminalAttentionTest 钉 `submitPtyControlSequence` 存在（兼作
  classpath 顺序错配的金丝雀）；ScreenCleanerTest 断言与 shadow 实现匹配。
- 既有 pty 实机冒烟（事件驱动公平性冒烟）继续生效；「读端停摆」实机场景尚无自动
  化，列入 §10 后续工作。

## 9. 审核循环记录（5 轮）

| 轮 | 主题 | 关键发现（均已修复） |
|---|---|---|
| 1 | 根因与方案 | 根因实证成立（独立复现 pty 实验）；发现 TerminalAttention/ScreenCleaner 直写破不变量（P1/M-3）、清屏乱序（P2）、死条件子句 |
| 2 | 并发正确性 | B1 反射链失效（闸从未生效）、M1 markDead 字节不清零、M2 release 恢复批被吞、M3 deferredBatches 并发、m1-m6 |
| 3 | 生命周期 | B-1 退出挂死（SIGTERM 杀不死）、M-1 清屏乱序细化、M-2 PrintWriter 吞异常、m-1 wait 无人 notify、m-2 flush latch 错配 |
| 4 | 测试覆盖 | 11 个缺口（P0：接线/按键存活、死亡族；P1：豁免/屏障/release；P2：并发 flush/单发/死闸）→ 全部补齐 |
| 5 | 终审验收 | **结论「合入」**；唯一 major（背压期 ZERO 空转）+ 2 minor → 已修（30ms 退避 + LinkageError catch） |

最终状态：springai-tamboui-inline-patch **67/67**、springai-code-tui **1831/1831**
全绿。

## 10. 后续工作（非本期）

- pty 实机验收：真实 Terminal.app + 中文 IME 下「输出高峰 + 拼字」场景的长测；
  读端停摆冒烟（stalled_terminal_smoke）。
- 屏障降级分支的外观优化（旧内容出现在分割线后时的提示行）。
- 大延迟批的写侧分片（>1MiB 单批现在靠队空豁免整体过，可拆分为更平滑的排出）。

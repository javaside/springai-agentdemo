# code-tui「输出中打字整屏冻死」根因定位与修复方案

> 日期：2026-10-03
> 涉及进程：`springai-code-tui.jar` v1.25.0（PID 40536）+ Apple Terminal.app 2.15 (470.2)，macOS 26.5.2
> 结论分级：**【已证】**= 有原始输出支撑；**【推断】**= 机制解释，有间接证据；**【未证】**= 待验，不得当结论用

## 1. 症状

> 界面在输出信息，我在输入框打字，整个终端就卡在不动了，界面也没继续输出了，输入框我也打不了字了。

现象要点：**整个终端**（不只是应用）停更、键盘无回显；反复出现；中文拼音输入法下高发；
必须重启终端才能恢复（历史口径）。

## 2. 根因【已证】

**卡住的是 Apple Terminal.app 自己：它的行缓冲（`TTBuffer`）陷入了一个持续 16 分钟的
越界异常死循环，终端因而停止更新画面、也不再接受键盘输入。**

原始证据（系统日志，`log show`）：

```
2026-10-03 02:32:25.709 E  Terminal[1970:7206] [com.apple.AppKit:General]
        TTBufferRemoveObjectsInRange(8405dda0, {150, 5}): Range is out of bounds
2026-10-03 02:32:25.712 E  ... TTBufferRemoveObjectsInRange(8405dda0, {66, 89}): Range is out of bounds
        （此后同一异常重复出现，直至 02:48:14）
```

异常栈（同一条日志的完整堆栈）——**抛在 Terminal 自己的主线程上**：

```
0  CoreFoundation   __exceptionPreprocess
1  libobjc.A.dylib  objc_exception_throw
2  CoreFoundation   +[NSException exceptionWithName:reason:userInfo:]
3  Terminal         Terminal + 77704          ← 抛异常的是 Terminal 自身代码
4  Terminal         Terminal + 166340
5  Terminal         Terminal + 397656
7  CoreFoundation   __CFNOTIFICATIONCENTER_IS_CALLING_OUT_TO_AN_OBSERVER__
11 Foundation       -[NSNotificationCenter postNotificationName:object:userInfo:]
12 Terminal         Terminal + 126912
14 Terminal         Terminal + 113204
15 Foundation       __NSThreadPerformPerform + 264
16 CoreFoundation   __CFRUNLOOP_IS_CALLING_OUT_TO_A_SOURCE0_PERFORM_FUNCTION__
21 HIToolbox        RunCurrentEventLoopInMode
29 AppKit           NSApplicationMain
30 dyld             start
```

统计与时间跨度：

| 指标 | 值 |
| --- | --- |
| 异常总数（近 3 天） | **8009** |
| 时间跨度 | **02:32:25 → 02:48:14**（连续 16 分钟，5–48 次/秒） |
| 其它时段 | **0**（10-01、10-02 全天为 0） |
| 线程 | 恒为 `Terminal[1970:7206]`，且栈在 `NSApplicationMain` 主运行循环上 |

时间相关性【已证】：用户在本会话报告「又卡死了」的时刻（首次 `ps` 采样 02:34）**落在该风暴期内**；
用户随后报告「又可以打字了」，风暴已于 **02:48:14** 停止。两者同窗。

**为什么这就是「卡住」**：异常被 Terminal 自己捕获（所以**不产生 `.ips` 崩溃报告**），
但缓冲维护操作每次都失败 → 终端内部状态不一致、画面不再推进、输入路径不再响应。
即：**终端没崩、Java 也没崩，是终端的缓冲记账坏了。**

【推断】越界范围 `{66, 89}` 与 `{150, 5}` 的末端都恰为 **155**，而该窗口宽 **99 列**——
像是「按更宽的列数去删除行内字符范围，但该行实际没有那么多列」，即缓冲的列宽记账与内容不一致。

## 3. 为什么以前多轮排查全部漏掉了它

| 以往排查 | 为什么没找到 |
| --- | --- |
| 只查 `~/Library/Logs/DiagnosticReports/*.ips` | **这条故障不产生 .ips**（异常被捕获）。只能从 `log show` 看到 |
| 反复盯着 code-tui 进程（jstack / 堆转储 / 渲染线程） | 应用侧本来就健康；问题在**终端**进程里 |
| 归因 Terminal 段错误（2026-09-30） | 那是**真崩溃**（SIGSEGV，有 .ips）；这次是**异常循环**（无 .ips）。同一症状、不同机制 |
| 归因 pty 写阻塞 / drain 预算 | 那些都在应用侧，与 Terminal 内部缓冲无关 |

**方法论教训**：症状说「整个终端」卡住时，取证对象应当包括**终端进程自己的日志**，
而不只是应用进程；且 `.ips` 只覆盖「崩溃」，不覆盖「异常循环」与「挂起」。

## 4. 已证伪的假设（含我自己提过的一个）

| 假设 | 状态 | 证伪依据 |
| --- | --- | --- |
| code-tui 初始化 AWT → 注册为 GUI 应用 → 抢走键盘焦点 | **证伪** | 该进程确为 `type="Foreground"`，但同期用户插话仍成功送达（日志 02:48:18 / 02:50:39 `InterjectingChatModel - 插话随本次调用送达`），说明输入通路是通的 |
| 「进程空闲 = 饿死」 | **推翻** | 无人操作的空闲 TUI 本就该全线程 parked、CPU 近零，那是正常态 |
| IME 光标更新风暴（`TUINSCursorUIController`）是触发因子 | **不成立** | 风暴期 990 次/16 分钟 vs 平静期 759 次/5 分钟——它是**慢性**现象，不区分故障与正常 |
| 内存压力（Jetsam） | **排除** | `JetsamEvent-2026-10-03-025148.ips` 中无任何进程被杀（全部 `states=['active']`，`free≈13.8GB`） |
| 我们的转义序列模式单独即可触发 | **未复现** | 两条受控复现均未触发（见 §5） |
| Java 死锁 / pty 写阻塞 / 渲染线程卡死 | **排除** | `jstack` 0 个 BLOCKED；`pty-writer` 空闲在队列 poll，不在 write |

## 5. 触发条件：已做到的与未做到的

**已排除**（受控复现，均为负结果）：

1. `python3 /tmp/tbuffer-repro.py` —— 精确复刻 `InlineDisplay` 的 SCROLL_APPEND 字节模式
   （每批 `down(H-1-lastY)` + CRLF + `up(H)` + `EL` + msg + `down(H)`，加每批整块 live 区重写），
   400 批 @50 批/秒，在新 Terminal 窗口运行 → `TTBuffer` 计数 **8009 → 8009（未触发）**。
2. `python3 /tmp/tbuffer-repro2.py` —— 同上但正文/行内容加长为 **155 字符**（> 99 列，强制折行），
   300 批 → **未触发**。

**因此**：我们的字节模式是**触发语境**（故障只在 code-tui 流式输出期间出现），
但**单独不足以**触发；还需要一个未识别的成分（最可能是「输出 + 中文输入法活跃 + 用户打字」的组合）。

**未做到的**：定位「什么把 `TTBuffer` 推入该状态」。当前证据只能锁定
「终端进入该状态 = 用户看到的现象」，不能锁定推手。

## 6. 修复方案

### 6.1 观测：让下次冻结可诊断【本轮已交付】

`dev/terminal-stress/capture-freeze.sh` 已增加 Terminal 侧信号采集（原来的版本只抓应用侧，
正是这次漏检的根源）：

- 新增产物 `terminal-log-10m.txt`：近 10 分钟 Terminal 的 `TTBuffer` 越界与 IME 光标更新日志
- 摘要新增两行计数：

```
--- Terminal 侧异常（TTBuffer 越界=终端卡死的关键信号；被捕获故无 .ips）---
TTBuffer 越界次数(近10分钟): 0
IME 光标更新次数(近10分钟): 1339
```

（以上为脚本实跑输出，`脚本 exit=0`；`TTBuffer=0` 是因为风暴已停。）

### 6.2 恢复：把「必须杀终端」变成「一键复原」

需要一个 **强制重建终端行缓冲** 的动作。候选：窗口尺寸变更（触发 reflow）、切换 tab、`Cmd+K` 清屏。

【推断，待验证】观察到风暴在 `02:48:14` 停止，而我在 `02:46–02:50` 之间对该窗口做过
`activate` + 改尺寸（+30px）——**时序接近但未证实因果**。计划：写
`dev/terminal-stress/repair-terminal.sh`（对目标窗口做一次 ±1 行 resize 再复原），
并用 §6.3 的验收方法在真机确认「resize 是否可清」。**在验证前不得写成结论。**

### 6.3 预防：降低我们对终端行缓冲的改写压力

这是唯一能真正了结此事的方向。当前 code-tui 每输出一批就重写整个 live 区，且每条消息
都伴随大跨度光标移动与 `EL`，合计每秒数千次**行缓冲改写操作**——而 `TTBufferRemoveObjectsInRange`
（从行内删除字符范围）正是被「擦除 / 折行-解折行 / 滚动」这些操作驱动的。
因此按收益排序：

1. **删掉「每条消息必发 `ESC[K`」**。`EL` 的语义就是「删除行内字符」，与崩溃中的
   `RemoveObjectsInRange` 直接同族。改为只在行内容变短时补擦。
   【推断】——需用 A/B + `TTBuffer` 计数验收。
2. **live 区按差异重画，而非每批整块重写**（现在 `appendLiveArea` 每次都写满 `currentHeight` 行）。
3. **用绝对定位（CUP）替代大跨度相对移动**，减少每条消息引发的光标事件数。
4. **给输出加逃逸序列速率上限**（与既有「300 行 / 12ms」预算并列），把每秒行缓冲改写压到安全量级。
5. **终端特化**：`TERM_PROGRAM=Apple_Terminal` 时启用上述 1–4 的保守档（其它终端保持现状）。

**每条都必须用真机 A/B 验收**：左侧跑改动版、右侧跑现状，对比系统日志里的
`TTBuffer` 计数——现有 `dev/terminal-stress/scroll-verify.py`（VT100 仿真器）只能验屏幕内容，
验不了 Terminal 内部状态，**不足以作为本故障的验收工具**。

### 6.4 明确出栈（无因果证据，不动）

`AsyncPtyWriter`、`PhysicalOutputQueue` 的 drain 预算、光标带修复（`cursorBandRepairFramesLeft`）、
纯滚动模式（`SCROLL_APPEND`）——本次证据均未指向它们。

## 7. 验收标准

1. **观测**：`dev/terminal-stress/capture-freeze.sh <pid>` 在冻结瞬间能产出含
   `TTBuffer 越界次数 > 0` 的摘要。
2. **复现**：找到能稳定触发 `TTBuffer` 越界的操作序列（这是后续一切的前提，目前尚未达到）。
3. **修复**：完成 §6.3 的改动后，用同一操作序列跑，`TTBuffer` 计数为 **0**，且屏幕内容与
   现有 `scroll-verify.py` 断言一致（无残影、无 `ESC[1L`）。
4. **回归**：`mvn -pl springai-code-tui -am test` 全绿。

## 8. 本轮审计证据（命令与退出码）

| 命令 | 退出码 | 结论 |
| --- | --- | --- |
| `ls -lat /Library/Logs/DiagnosticReports/` | 0 | 发现 `JetsamEvent-2026-10-03-025148.ips`（此前只查过用户目录、只查 `.ips`） |
| `log show --predicate 'process == "Terminal" AND eventMessage CONTAINS "TTBuffer"'` | 0 | **8009 次**，全部在 10-03 02 时段；10-01/10-02 为 0 |
| 同上（取首末条） | 0 | 02:32:25.709 → 02:48:14.381 |
| `log show` 取完整异常栈 | 0 | 抛点在 `Terminal + 77704`，`NSApplicationMain` 主循环，经 `__NSThreadPerformPerform` |
| `log show ... CONTAINS "scheduleUpdateCursorLocation"` | 0 | 20729 次/3 天，峰值 83/s；风暴期 990 vs 平静期 759 → **不构成区分度** |
| JetsamEvent 解析（python json） | 0 | 无进程被杀；`free≈13.8GB` → 排除内存压力 |
| `python3 /tmp/tbuffer-repro.py`（新窗口，400 批） | 0 | `TTBuffer` 8009 → 8009，**未复现** |
| `python3 /tmp/tbuffer-repro2.py`（155 字符超宽行，300 批） | 0 | 同样**未复现** |
| `log show --last 5m ... TTBuffer` | 0 | 0 → 风暴确已停止 |
| `dev/terminal-stress/capture-freeze.sh` 修补后实跑 | 0 | 新摘要段输出正常 |

## 9. 相关文档

- [2026-09-02-code-tui-async-pty-writer-design.md](2026-09-02-code-tui-async-pty-writer-design.md) —— pty 写阻塞修复（本次已排除）
- [2026-09-09-code-tui-drain-budget-exemption-fix-design.md](2026-09-09-code-tui-drain-budget-exemption-fix-design.md) —— 同症状的另一次排查（量级不匹配）
- [2026-08-21-deepseek-vision-design.md](2026-08-21-deepseek-vision-design.md) —— `ImagePreparer` 引入处（§4 被证伪假设所涉代码）

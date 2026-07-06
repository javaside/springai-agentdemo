# `/clear` —— 清空当前会话上下文（开新会话）

- 状态：设计已确认，待写实现计划
- 日期：2026-07-06
- 模块：`springai-code-tui`

## 背景与目标

`springai-code-tui` 是一个 Claude Code 式命令行编码智能体。长会话中上下文会累积、吃掉 token 预算，用户需要一个「一键从干净上下文重新开始」的命令 —— 即本特性 `/clear`。

现有相邻命令：`/compact`（手动压缩历史，保留摘要）。`/clear` 与之不同：不是压缩，而是**彻底开一个新会话**，让模型忘掉全部历史。

## 决策总览（已与用户逐项确认）

| 维度 | 决定 | 理由 |
|---|---|---|
| 语义 | **开新会话**：生成新 `sessionId`，旧会话原样留盘、可 `-c` 找回 | 与「每次启动默认开新会话」的现有架构自洽；旧对话不丢，代价最低。类比 Claude Code `/clear` |
| 视觉 | **真清屏**（反射进私有 backend 调 `clear()` + `ESC[3J`）+ 重置面板；反射失败降级为分割线 | 用户明确要「清屏 + 重置面板」，接受其风险 |
| 忙时 | **拒绝**（回合中 / 压缩中 / 有排队 → 提示「忙碌中，无法清空」） | 与 `/compact` 同一守卫；避免换 id 时旧回合 reactive 订阅仍在写，产生竞态 / 写错 session |
| 命令名 | `/clear` | 与 Claude Code 一致，用户最熟悉 |
| 确认 | 无，直接执行 | 旧会话可 `-c` 找回，代价低；符合 `/compact` 与 Claude Code `/clear` 惯例 |

## 关键技术发现（决定设计形态）

1. **`sessionId` 是请求期实时读取的**：`CodingAgent.submit` 在组装每个回合时，把 `sessionId` 作为
   `SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY` 的 advisor param 传入，`SessionMemoryAdvisor` 据此解析 / 自动创建会话。
   ⇒ 只要把该字段改成可变（`volatile`）并原地换新 id，下一个回合就用新会话，无需重建 `CodingAgent`。

2. **TamboUI 0.4.0 无公开清屏 API**：`InlineToolkitRunner` / `InlineTuiRunner` 只暴露 `println`（追加 scrollback、向上滚动）
   与底部 pinned 组件区（`InlineDisplay`）。真正的 `Backend.clear()` 存在，但 `Backend` 是 `InlineTuiRunner` 的**私有字段**，不可公开访问。
   ⇒ 真清屏只能反射进私有 backend，或写原生 ANSI；两者都会与 `InlineDisplay` 的相对光标记账冲突（项目历史中「光标漂移 / 面板消失」类 bug 的根源）。

3. **`InlineDisplay` 只有两个可变记账字段**：`lastCursorY`（上次留下光标的行）与 `currentHeight`（当前占用的终端行数）。
   外部清屏 + 光标归位后，这两个字段变陈旧，下一帧 `render()` / `println()` 会漂移。
   ⇒ 安全真清屏 = 清屏 + 光标 home + **把这两个 int 反射置 0**，使显示区表现得如同刚启动。

## 架构与数据流

`/clear` 由两条**解耦**的机制组成，B 失败不影响 A：

**(A) 换会话（上下文）** —— `CodingAgent.sessionId` 由 `final` 改为 `volatile`，`/clear` 时生成新 id 原地换上。
下一个回合的 advisor 用新 id 自动创建空会话。旧会话文件在盘上不动（惰性写盘：空的新会话在首次发消息前不落盘），
`-c` 仍能恢复旧的那次。

**(B) 清屏 + 重置面板（视觉）** —— 反射拿到 `runner().tuiRunner()` 里的私有 `Backend`，调 `backend.clear()`
再 `writeRaw("\033[3J\033[H")` 抹掉回滚缓冲；随后把 `InlineDisplay` 的 `lastCursorY`、`currentHeight` 归零。
全部在 `runOnRenderThread` 中执行（两帧之间，不与绘制中途竞争）。清完重印欢迎横幅，观感等同全新启动。

```
用户输入 /clear
  → CodeTuiView.submitInput() 识别命令
  → 若 !state.isIdle() → setNotice("忙碌中，无法清空")，return
  → onSubmit.clearContext()               // (A) CodingAgent 换 volatile sessionId
  → state.resetForNewSession()            // 重置 todo/子任务/pending/notice/queued
  → runOnRenderThread:
        ok = ScreenCleaner.clear(runner())   // (B) 真清屏 + 重置 InlineDisplay 记账
        ok ? printer.welcome(...)            // 重印欢迎横幅
           : printer.println("─── 新会话（上下文已清空）───")   // 反射失败降级
  → lastShownModel = ""                    // 新会话首个回合重新打「⚙ 使用模型 X」
  → 下一个回合用新 sessionId，空上下文
```

## 组件改动清单（6 处，聚焦、无牵连重构）

1. **`SubmitHandler`（接口）** —— 新增默认方法：
   ```java
   /** /clear：切到一个全新空会话（旧会话留盘可 -c 恢复）。默认空实现，便于桩省略。 */
   default void clearContext() { }
   ```

2. **`CodingAgent`**
   - `private final String sessionId` → `private volatile String sessionId`（构造与读取处不变）。
   - `newSessionId()` 生成逻辑**下沉到共享处**（新 `SessionIds` 小工具类，或 `CodingAgent` 的 static），
     与 `CodeTuiApplication` 复用，避免复制。
   - 实现 `clearContext()`：`this.sessionId = SessionIds.newId();`（volatile 写，一行）；不动 `sessionService`、不删旧文件。

3. **`ui/ScreenCleaner`（新增小类）** —— 隔离全部反射与 ANSI，单一职责、可单测：
   - `boolean clear(InlineToolkitRunner runner)`：反射取 `InlineTuiRunner.backend` → `backend.clear()` +
     `writeRaw("\033[3J\033[H")`；反射取 `InlineDisplay` 的 `lastCursorY` / `currentHeight` 置 0。
   - 任何 `ReflectiveOperationException` / `IOException` → 返回 `false`（不抛），调用方降级为分割线。

4. **`ConversationState`** —— 新增 `resetForNewSession()`：清 `todo`、`subtasks`、`pending`、`queued`、`notice`
   （复用已有各 `clear`），语义等同「回到刚启动」。

5. **`CodeTuiView`**
   - `COMMANDS` 加 `new SlashCommand("/clear", "清空上下文，开新会话")`。
   - `submitInput()` 加分支：`isIdle` 守卫 → `onSubmit.clearContext()` → `state.resetForNewSession()` →
     `runOnRenderThread` 做清屏 / 降级 → `lastShownModel = ""` → 触发一次 `ctxUsage` 刷新（新会话算出为 0）。

6. **`/help` 文案** —— 补一行 `/clear` 说明。

## 错误处理与边界

- **反射失效（库升级等）**：`ScreenCleaner.clear()` 吞异常返回 `false` → 降级打印分割线。**上下文照样已换新会话**
  （A 与 B 解耦），最坏情况只是屏幕没清干净，功能不残。
- **忙时**：`!state.isIdle()` 直接拒绝并 `setNotice`，与 `/compact` 同一守卫，杜绝换 id 时旧回合订阅仍在写。
- **空会话不落盘**：`/clear` 后立即退出，新空会话无文件，`-c` 仍恢复旧会话——无数据丢失。
- **`/clear` 后 `-c` 语义**：旧会话仍是 `latestSessionId`（按 mtime），直到新会话首次发消息才易主。一致、无意外。

## 测试策略（TDD）

1. **`CodingAgent.clearContext()` 单测**：调用后 `sessionId` 变化且 ≠ 旧值；旧 session 的 events 仍在
   `sessionService`（未删）；新 id 的 events 为空。
2. **`ConversationState.resetForNewSession()` 单测**：预置 todo/subtasks/pending/queued/notice，调用后全空。
3. **`ScreenCleaner` 单测**：对当前 TamboUI 0.4.0 结构做保护性断言（反射目标字段 `backend` / `lastCursorY` /
   `currentHeight` 存在，库升级即红灯）；构造反射失败场景验证返回 `false` 不抛。
4. **命令分发单测**：忙时 `/clear` 被拒绝并置 notice、不换会话；空闲时触发 `clearContext()` + `resetForNewSession()`。
5. **pty 冒烟实机验证（必做，非可选）**：真终端发若干消息 → `/clear` → 断言屏幕已清、输入框与光标无漂移、
   欢迎横幅重现、随后新回合正常。按项目规矩：`pty.fork` 设窗口大小 + `TERM=xterm-256color`，改完**重新 package** 再验。
   这是本特性唯一高风险点，验证不过就回退到分割线版本。
6. **模块作用域验证**：`mvn -pl springai-code-tui test`（不整仓跑，避开空模块打挂）。

## 验证命令基线

- `mvn -pl springai-code-tui test`
- 一次 pty 冒烟脚本（真清屏路径的光标 / 横幅断言）

## YAGNI / 明确不做

- 不做二次确认对话框。
- 不删旧会话文件（保留可 `-c` 恢复）。
- 不做「清空但保留摘要」——那是 `/compact` 的职责。
- 不引入全屏 / alt-screen 模式来简化清屏（会颠覆现有内联 scrollback 架构）。

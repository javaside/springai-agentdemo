# 子 agent TodoWrite 可见性与恢复时面板重建 — 设计

## 背景

用户在一个真实的长期会话（`english-syntax-extension` 项目，`.codetui/sessions/20260905T150547-d34ca3.json`，跨多个自然日、用 `-c` 反复恢复）里观察到：子 agent 会频繁调用 TodoWrite，主 agent 却好像完全没用——"我感觉有问题"。

排查方式：直接读该会话持久化的 JSON（`events` 数组，`SessionEvent` 与实际发给模型的消息一一对应），统计全部 9 次顶层 TodoWrite 调用的时间戳与上下文，并对照 `SubagentRunner`/`ConversationState`/`AgentTools` 源码走查了 TodoWrite 从子 agent 内部调用到 UI 展示的完整链路。结论：**不是路由/派发层面的 bug**，是两处已有设计的叠加效果——但其中一处（面板不随 `-c` 恢复重建）确实值得修，遂有本设计。

## 根因（三点叠加，均已用代码证实）

1. **子 agent 本来就能调 TodoWrite，且内容会被有意丢弃。** `SubagentRunner` 把「已被 `ToolEventCallback` 装饰的主 agent 工具列表」原样传给子 agent（`SubagentRunner.java:69` 注释），`general-purpose`（本次 19 次派发全部用的类型）只 `disallowedTools: AskUserQuestionTool`，未禁 TodoWrite。`AgentTools.java:380-386` 装配 TodoWrite 时把 `taskId`（子 agent 派发 id，`null`=主 agent）一并传给 `listener.onTodoUpdated`；`ConversationState.java:922-925` 按此分流：
   ```java
   // 分流：taskId==null 是主 agent（控制器）的 todo → todo 面板；
   // taskId!=null 是子 agent 内部 todo → 丢弃（不进任何面板，仅 scrollback 有其工具活动行）。
   ```
   子 agent 每次 TodoWrite 只在 scrollback 留一行面包屑（`ConversationState.java:882`：`"    ⎿ " + toolName + 摘要`），完整清单内容从不落地到任何面板——这是**故意的**设计（避免多个并发子 agent 的内部清单把面板刷成噪音），不是误用。

2. **主 agent 自己的 todo 面板不随 `-c` 恢复重建。** `ConversationState.replayHistory`（`ConversationState.java:303-316`）只用 `HistoryReplay.toReplayLines` 重放 scrollback 文本，从未碰 `this.todo` 字段。恢复那一刻面板必然是空的，直到主 agent 在新会话里**再次**调用 TodoWrite 才会重新出现内容。

3. **本次会话里，恢复后主 agent 确实没再调过 TodoWrite。** 主 agent 最后一次 TodoWrite 在时间戳 `2026-09-09T11:24:44`（标记"Task 4：生成 baseline"为 in_progress）。用户在 `14:37:45` 输入"继续执行上一批未完成的计划。请先回顾你的 todo 列表…"后，主 agent 的响应用的是 `Read/Grep/Bash/ListTasks`（读 `.superpowers/sdd/.../task-N-report.md` 与 git 状态重建进度认知），**没有调 TodoWrite**——因为面板已空、TodoWrite 本身也没有"读"的模式；此后到会话末尾都没有再调。它的判断是对的（Task1-3 已完成，Task4 在修复轮1），只是没有通过用户期待的机制。

三点叠加的结果：底层计划状态没丢（在 report.md 和 git 里），丢的只是 UI 上 todo 面板的展示——但对盯着屏幕的人来说，观感就是"子 agent 一直在用 TodoWrite，主 agent 好像完全不用"。

## 变更一：`general-purpose` 默认拒绝 TodoWrite

**动机**：子 agent 调 TodoWrite 的完整内容既然注定被丢弃（见根因 1），继续默认放行只是让子 agent 白白多花一次工具往返；scrollback 里的一行面包屑本身不依赖 TodoWrite 这个工具本身存在，去掉它子 agent 的活动可见性不受影响。

**范围**：只改 `springai-code-tui/src/main/resources/agents/general-purpose.md` 的 frontmatter。这是内置四个子 agent（`general-purpose`/`explore`/`plan`/`bash`）里**唯一**默认能拿到 TodoWrite 的——另外三个用白名单式 `tools:`（如 `Read, Grep, Glob`），TodoWrite 从未在列，天然已排除。

**改法**：
```diff
- disallowedTools: AskUserQuestionTool
+ disallowedTools: AskUserQuestionTool, TodoWrite
```
`SubagentLoader.java:78` 已按逗号 split+trim 解析该字段，语法上没有额外工作。

**不做的事**：不在 `SubagentRunner.filterTools` 层面做全局硬编码排除。用户自定义的 `agents/*.md` 不受本变更影响——那是用户自己的 frontmatter，自己决定要不要给 TodoWrite。若未来需要「任何子 agent 都拿不到，除非显式 allow」的硬限制，是另一个范围更大的改动，本设计不做。

**已有测试的影响**：`SubagentDefinitionsTest.java` 的 `REAL_TOOL_NAMES` 已含 `"TodoWrite"`；`generalPurposeDeniesAskTool` 只断言 `denyTools()` 包含 `AskUserQuestionTool`，不断言排他性——两个既有测试都不会因本变更破坏。

## 变更二：`-c` 恢复时重建 todo 面板

**动机**：让面板展示与实际计划状态一致——恢复会话后，面板应该显示"上次退出时主 agent 的 todo 长什么样"，而不是空白直到主 agent 恰好再调一次 TodoWrite（见根因 2、3）。

**组件与数据流**：

1. `HistoryReplay`（`ui` 包，包内可见）新增 `lastTodoSnapshot(List<Message> messages) -> List<String>`：扫描 `messages`，找**最后一个** `AssistantMessage` 里 `name()=="TodoWrite"` 的 `ToolCall`，取其 `arguments()`（JSON 字符串）。

2. 反序列化直接复用现成类型，不手写解析：`TodoWriteToolAdapter.java` 已证实持久化的 JSON 形状是 `{"todos":[...]}`，与库类型 `Todos(List<TodoItem> todos)` 字段名天然对上，`objectMapper.readValue(argsJson, Todos.class)` 即可。找不到任何 TodoWrite 调用，或反序列化失败（防御性处理，容忍老版本残留数据），一律返回空列表——不抛异常、不让恢复流程崩掉。

3. 格式化复用 `AgentTools.toLines(Todos)`（现为包内 `static`，本变更升为 `public`，按本代码库既有惯例加注释"升 public 仅为跨包装配，勿在 agent 包外依赖"）——与实时面板的行格式（状态 marker + 内容）完全一致，不新写一份、不会跑偏。

4. `ConversationState.replayHistory(List<Message> messages)`（`ConversationState.java:303`）内部在原有 `synchronized` 块里追加：计算 `HistoryReplay.lastTodoSnapshot(messages)`，`todo.clear(); todo.addAll(...)`，复用已有的 `changed(UiDirty.OUTPUT | UiDirty.VIEW)`。**方法签名不变，`CodeTuiApplication.java:137` 的调用方也不用改。**

**为什么不复用现有的 `onTodoUpdated` 实时事件路径**：那条路径有 `turnId != acceptingTurnId` 门卫，专门用来过滤"已取消回合的迟到事件"；恢复时重建面板是一次性的启动期初始化，语义上不是"实时事件"，硬套这条路径要么削弱门卫、要么发明一个假 turnId 去骗过它，两种都是把两种不同语义的操作强行焊在一起。新写一个直接赋值的路径更干净。

## 测试计划

- `SubagentDefinitionsTest.java`：新增一条断言（同 `generalPurposeDeniesAskTool` 风格），钉住 `general-purpose` 的 `denyTools()` 包含 `"TodoWrite"`。
- `HistoryReplayTest.java`（已存在）：新增
  - 历史里有两次 TodoWrite（内容不同）→ `lastTodoSnapshot` 返回**最后一次**的行，格式与 `AgentTools.toLines` 一致；
  - 历史里没有 TodoWrite → 返回空列表；
  - 历史里有格式异常的 TodoWrite 参数（防御性回归）→ 返回空列表，不抛异常。
- `ConversationStateTest`：新增「`replayHistory` 之后 `todoSnapshot()` 反映历史最后一次 TodoWrite 内容」的端到端回归；以及「历史无 TodoWrite 时 `todoSnapshot()` 保持空」的对照用例。

## 范围之外

- 子 agent 内部 todo 清单本身的可见性（例如在子任务面板展开显示其当前步骤/总步骤）——现状的一行面包屑（`⎿ TodoWrite ...`）保持不变，本设计不新增展示位置。
- 对自定义子 agent（用户自己的 `agents/*.md`）强制禁用 TodoWrite——见变更一「不做的事」。
- 会话持久化格式本身的任何改动——两处变更都只读现有持久化数据，不改 `SessionEvent`/JSON 结构。

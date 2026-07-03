# 压缩指示器 + 手动 `/compact` 命令 — 设计文档

日期：2026-07-02
模块：`springai-code-tui`
状态：已评审通过，待编写实现计划

---

## 1. 背景与目标

会话记忆已从滑动窗口迁移到 `spring-ai-session`（内存版），压缩由
`SessionMemoryAdvisor` 在 `after()` 阶段触发：当 `TokenCountTrigger` 命中
阈值（400k tokens）时，调用 `RecursiveSummarizationCompactionStrategy`
（保留最近 120 个 event）做一次总结式压缩。

存在两个问题：

1. **压缩完全静默**——它是一次额外的、耗时可达数分钟的总结 LLM 调用，
   但界面上没有任何提示，用户会以为程序卡住。
2. **无法手动触发**——用户想在自己认为合适的时机主动压缩上下文（参考
   Claude Code 的 `/compact`），目前没有入口。

本设计新增：
- **压缩 UI 指示器**：自动压缩发生时，界面显示实时状态与完成结果。
- **手动 `/compact` 命令**：强制立即压缩，采用更激进的保留窗口。

---

## 2. 关键决策（已确认）

| 决策点 | 结论 | 理由 |
| --- | --- | --- |
| `/compact` 语义 | **强制立即、激进总结** | 用户要真正能按需缩小上下文，而非等阈值 |
| 手动保留窗口 | `MANUAL_MAX_EVENTS_TO_KEEP = 20` | 比自动的 120 激进得多；可调常量 |
| 进度条形态 | **不确定型动画条 + 真实计时** | 库无进度回调，百分比只能造假，拒绝造假 |
| 完成信息落点 | **写入 transcript**（一行系统消息） | 压缩是重要历史事件，值得留痕 |
| 可中断性 | v1 **不可中断** | 库的 `compact()` 调用不可取消 |

### 库无进度信号（重要约束）

`RecursiveSummarizationCompactionStrategy` 只暴露 `onSummarizationFailure`
失败钩子，**没有进度回调、没有步骤计数**。压缩就是一次不透明的总结 LLM
调用。因此 Claude Code 截图里的 `85%` 我们无法真实驱动——只做**不确定型
动画条 + 真实经过时间计时器**（如 `⟳ Compacting conversation… (2m 51s)`）。

---

## 3. 已核实的库 API（javap 验证，非文档）

来自 `spring-ai-session-management-0.5.0.jar`：

```java
// 函数式接口 —— 都可用 lambda / 装饰器实现
interface CompactionTrigger  { boolean shouldCompact(CompactionRequest req); }
interface CompactionStrategy  { CompactionResult compact(CompactionRequest req); }

// SessionService 直接暴露手动压缩入口
CompactionResult SessionService.compact(String sessionId,
                                        CompactionTrigger trigger,
                                        CompactionStrategy strategy);

// 结果 record —— 提供完成所需的两个数字
record CompactionResult(List<SessionEvent> compactedEvents,
                        List<SessionEvent> archivedEvents,
                        int tokensEstimatedSaved) {
    int eventsRemoved();        // 被归档移除的 event 数
    int tokensEstimatedSaved(); // 估算节省 token
}
```

要点：
- `CompactionTrigger` 是函数式接口 → 强制触发就是 `req -> true`。
- `CompactionStrategy` 是函数式接口 → 通知装饰器只需包一层。
- `CompactionResult.eventsRemoved()` / `tokensEstimatedSaved()` 正好是完成
  行需要展示的数字。
- 压缩是**破坏性**的（`replaceEvents` 覆盖，`archivedEvents` 不落库）——本设计
  不改变这一点，仅在其之上增加可观测性。

---

## 4. 组件设计

所有跨 CodingAgent→UI 的通信只经 `AgentListener`，**不泄漏任何 Spring AI 类型**
（项目既有纪律）。

### 4.1 `AgentListener` 新增三个方法

```java
void onCompactionStarted(String reason);              // "auto" | "manual"
void onCompactionFinished(int eventsRemoved, int tokensSaved);
void onCompactionFailed(String message);
```

### 4.2 `NotifyingCompactionStrategy implements CompactionStrategy`（新增装饰器）

持有：`delegate`（真实策略）、`listener`、`reason` 字符串。

```java
public CompactionResult compact(CompactionRequest req) {
    listener.onCompactionStarted(reason);
    try {
        CompactionResult r = delegate.compact(req);
        listener.onCompactionFinished(r.eventsRemoved(), r.tokensEstimatedSaved());
        return r;
    } catch (RuntimeException e) {
        listener.onCompactionFailed(String.valueOf(e.getMessage()));
        throw e;
    }
}
```

同一装饰器**同时驱动自动与手动**两条路径——自动路径的策略与手动路径的策略
各包一层（reason 分别为 `"auto"` / `"manual"`）。

### 4.3 `AgentTools.build()` 改动

- 构建两个被装饰的策略：
  - auto：`RecursiveSummarization(keep=120)` → 装饰 reason=`"auto"`，交给
    `SessionMemoryAdvisor`。
  - manual：`RecursiveSummarization(keep=MANUAL_MAX_EVENTS_TO_KEEP=20,
    overlap=3)` → 装饰 reason=`"manual"`。
- 不再只返回裸 `ChatClient`，改为返回小 record：
  ```java
  record AgentRuntime(ChatClient client,
                      SessionService sessionService,
                      CompactionStrategy manualStrategy) {}
  ```

### 4.4 `CodingAgent` 改动

- 构造函数接收 `sessionService` 与 `manualStrategy`。
- 新增 `void compact()`：
  1. 若有活跃 turn → 经 listener 发一条系统提示
     `Cannot compact while a turn is running`，直接返回。
  2. 否则在**后台线程**执行
     `sessionService.compact(sessionId, req -> true, manualStrategy)`。
  3. 通知事件经既有 `AgentListener` 流出（started/finished/failed）。

### 4.5 `SubmitHandler` 新增

```java
default void compact() {}
```
由 `CodingAgent` 实现。

### 4.6 `ConversationState`（实现 AgentListener）

- 持有瞬态压缩状态 `{active, reason, startedAtNanos}`：started 置位，
  finished/failed 清除。
- 完成/失败/无可压缩 各追加一行系统消息到 transcript。

### 4.7 `CodeTuiView`

- `COMMANDS` 列表注册 `/compact`；派发处调用 `handler.compact()`。
- 渲染瞬态状态行 + 不确定型动画条，动画由既有渲染 tick 驱动，经过时间由
  `startedAtNanos` 计算。

---

## 5. 数据流

**自动：** advisor 在 `after()` 命中 trigger → 调用被装饰的 auto 策略 →
装饰器发 started/finished → ConversationState → CodeTuiView 显示
`⟳ Compacting…` 然后 `✓ Compacted: N events, ~T tokens saved`。

**手动：** 用户输入 `/compact` → `handler.compact()` → CodingAgent 闸门检查 →
后台 `sessionService.compact(id, ()->true, manualStrategy)` → 同一装饰器 →
同一 UI 路径。

---

## 6. UI 呈现

- 回显 `/compact` 命令行。
- 实时瞬态：`⟳ Compacting conversation… (Nm Ns)` + 移动的不确定型进度条。
- 完成：清除瞬态，transcript 追加：
  - 正常：`✓ Compacted: N events → summary (~T tokens saved)`
  - 无可压缩（`eventsRemoved()==0`）：`• Nothing to compact`
  - 失败：`✗ Compaction failed: …`

---

## 7. 并发与边界情况

- 手动压缩闸门限定「无活跃 turn」——避免与 advisor 自身压缩的乐观锁
  version 冲突。
- v1 不可中断：`esc` 不会取消进行中的压缩（库调用不可取消），UI 文案需说明。
- 后台线程执行，数分钟的总结 LLM 调用绝不阻塞 UI。
- 事件数 < 手动保留窗口时策略自然 no-op → 走 `• Nothing to compact` 分支。

---

## 8. 测试

- 单元：`NotifyingCompactionStrategy` 用假 delegate + 捕获型 listener，验证
  started→finished（计数正确）与 started→failed（delegate 抛异常）。
- 单元：`CodingAgent.compact()` 在有活跃 turn 时 no-op 且发系统提示（假
  SessionService）。
- 手动冒烟：启动 TUI，`/compact`，观察指示器 + 完成行。

---

## 9. 影响文件清单

| 文件 | 改动 |
| --- | --- |
| `agent/AgentListener.java` | +3 方法 |
| `agent/NotifyingCompactionStrategy.java` | 新增装饰器 |
| `agent/AgentTools.java` | 双策略 + 返回 `AgentRuntime` record |
| `agent/CodingAgent.java` | 注入依赖 + `compact()` + 活跃 turn 闸门 |
| `agent/SubmitHandler.java` | +`compact()` 默认方法 |
| `agent/ConversationState.java` | 瞬态压缩状态 + 系统消息 |
| `ui/CodeTuiView.java` | `/compact` 注册/派发 + 状态行渲染 |
| `CodeTuiApplication.java` | 适配 `AgentRuntime` 返回值 |

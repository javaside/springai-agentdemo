# AskUserQuestion 功能设计（code-tui）

日期：2026-07-03
状态：设计待评审
关联：`spring-ai-agent-utils:0.10.0` 的 `AskUserQuestionTool`，文档
<https://spring-ai-community.github.io/spring-ai-agent-utils/v0.10.0/tools/AskUserQuestionTool/>

---

## 1. 目标

给 code-tui 接入 `AskUserQuestionTool`：让模型在执行过程中**反问用户**（澄清需求、在若干实现方案里让用户拍板），
用户在终端里用一个内联的**问题面板**选择答案，模型据答案继续本回合。

对齐 Claude Code 的 AskUserQuestion 体验：单选 / 多选 / 「Other」自定义文本，一次 1–4 个问题。

## 0. 已确认的决策（brainstorming 定稿）

| 决策 | 选定 | 理由 |
|---|---|---|
| **范围** | 全做：单选 + 多选 + 「其他」自定义文本 + 一次 1–4 问（顺序问询） | 与 Claude Code 体验一致；工具契约本就允许任意组合，模型可能用到 |
| **握手机制** | 方案 A：`ArrayBlockingQueue(1)` 一次性队列 | `offer` 永不因「无人等待」失败，无竞态窗口、无锁；取消经 `offer(CANCEL)` 唤醒。比 `SynchronousQueue`/`Exchanger`/`CompletableFuture` 都简单 |
| **Esc 语义** | 取消整个回合 | 复用现有 `doOnCancel` 回滚会话、清悬空 tool_calls（不 400）；与其它选择器 Esc 一致 |
| **多选交互** | 空格勾选 `[x]/[ ]` + Enter 确认（至少勾一项） | 与主流 TUI / Claude Code 多选一致；数字键仅作「快速移动高亮」不隐式确认 |

## 2. 工具契约回顾（读源码 + 文档得出）

`AskUserQuestionTool` 是一个 `@Tool(name = "AskUserQuestionTool")` 对象，靠 builder 装配一个 **`QuestionHandler`**：

```java
@FunctionalInterface interface QuestionHandler {
    Map<String, String> handle(List<Question> questions);   // 键=question 文本，值=选中的 label（多选逗号分隔 / 自由文本）
}
```

- `handle(...)` 在**工具调用线程上同步执行**，并且必须**阻塞直到用户给出答案**才返回。
- `Question` = `question`(文本, 非空) + `header`(≤12 字 chip 标签) + `options`(2–4 个 `Option{label, description}`) + `multiSelect`。
- 默认 `answersValidation=true`：返回的 map 必须给每个 question 都有非 null 答案，否则抛 `InvalidUserAnswerException`
  （该异常应上抛给用户、非模型）。
- 仅限**主 Agent**，子 Agent（Task 工具）不可用——本项目未接 Task 工具，天然不受影响。

**关键结论**：这不是「即调即返」的工具，而是一个把**工具线程**挂起、等**UI 线程**喂答案的**跨线程握手**。
这是整个设计的核心难点，其余都是外围。

## 3. 现有架构里的接缝（约束）

- `AgentListener`（`CodingAgent → UI` 唯一接缝）**不得泄漏任何 Spring AI 类型**——`Question` 是 Spring AI 类型，
  必须先翻译成纯 Java 记录（照 `SkillInfo` 镜像 `SkillsTool.Skill` 的先例）。
- `ConversationState implements AgentListener`，是接缝落地端；`CodeTuiView` 每 tick 从它读交互状态
  （todos / queued / streaming / status / pickingModel / pickingSkill…），据此渲染面板并路由按键。
- 工具经 `ToolEventCallback` 装饰后注册；装饰器在 `call` 前把 `turnId` 写入 `ThreadLocal`（`currentTurnId()`），
  供同线程同步触发的 handler 读取（`TodoWriteTool.todoEventHandler` 就是这么拿 turnId 的）。
- 取消（Esc→`dispose`）时 `CodingAgent.submit` 已有 `doOnCancel(rollbackTo(mark))`：把会话回滚到回合前快照，
  清掉悬空的 `assistant(tool_calls)`（否则下轮 400）。**本功能直接复用这套回滚**。

## 4. 核心设计：跨线程握手桥 `UserQuestionBridge`

新增一个纯 Java 桥对象（`agent` 包），实现 `QuestionHandler`，在装配期由 `AgentTools` 建好并交给
`AskUserQuestionTool.builder().questionHandler(bridge)`。它是工具线程与 UI 线程之间唯一的同步点。

```
工具线程（Reactor）                        渲染线程（TUI）
─────────────────                         ────────────────
handle(questions)
  ├─ 翻译 Question → QuestionSpec（纯 Java）
  ├─ 读 ToolEventCallback.currentTurnId()
  ├─ new AskRequest(turnId, specs, responder)
  ├─ listener.onQuestionAsked(turnId, req) ──►  ConversationState 存 pendingAsk
  └─ handoff.take()   ← 阻塞在这里            ◄─  CodeTuiView 每 tick 读 pendingAsk → 弹问题面板
        ▲                                          用户 ↑↓/空格/Enter 选择、"Other" 输入文本
        │                                          全部答完 → req.answer(map)
        └──────────── 被唤醒，返回 map ◄──────────────┘  （或 Esc → req.cancel()）
```

**握手用一次性队列**（每次 `handle` 新建，天然无并发问题）：

```java
final class UserQuestionBridge implements AskUserQuestionTool.QuestionHandler {
    private final AgentListener listener;
    public Map<String,String> handle(List<Question> questions) {
        long turnId = ToolEventCallback.currentTurnId();
        List<QuestionSpec> specs = translate(questions);            // 纯 Java，剥离 Spring AI 类型
        var handoff = new java.util.concurrent.ArrayBlockingQueue<Object>(1);
        AskRequest req = new AskRequest(turnId, specs,
                map  -> handoff.offer(map),        // UI：提交答案
                ()   -> handoff.offer(CANCEL));    // UI：取消
        listener.onQuestionAsked(turnId, req);
        Object r;
        try { r = handoff.take(); }                                // 阻塞工具线程
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new QuestionCancelledException(); }
        if (r == CANCEL) throw new QuestionCancelledException();    // 取消：抛出，随 dispose 一起被丢弃
        return (Map<String,String>) r;
    }
}
```

用 `ArrayBlockingQueue(1)` 而非 `SynchronousQueue`：`offer` 永不因「无人等待」失败，规避
「存 req 后、`take` 前 UI 恰好答完」的理论竞态。

### 4.1 纯 Java 数据模型（`agent` 包）

```java
public record QuestionSpec(String question, String header, List<OptionSpec> options, boolean multiSelect) {}
public record OptionSpec(String label, String description) {}

// UI ← 桥 的一次交互句柄；responder 回调是纯 Java，不泄漏 Spring AI 类型
public interface AskResponder { void answer(Map<String,String> answers); void cancel(); }
public record AskRequest(long turnId, List<QuestionSpec> questions, AskResponder responder) {}
```

`AgentListener` 增一个方法（保持全纯 Java）：

```java
void onQuestionAsked(long turnId, AskRequest request);
```

`ConversationState` 落地：`volatile AskRequest pendingAsk`；`onQuestionAsked` 里做迟到过滤
（`turnId == acceptingTurnId` 才存）后置入；新增 `pendingAsk()` 读取、`clearPendingAsk()` 清除。

## 5. UI 流程（`CodeTuiView`）

新增模态状态 `askActive` + 一组本地作答状态，模式与既有 `/model`、`/skill` 选择器完全一致：

- **触发**：`drain()`（或 render 顶部）检测 `state.pendingAsk() != null && !askActive` → 进入作答模式，
  初始化到第一个问题、清空选择。
- **渲染**：`render()` 里加一条 `scope(askActive, askChildren())`，钉在输入框上方。面板显示：
  `header` chip + 完整问题文本 + 逐项选项（`❯` 高亮、多选用 `[x]/[ ]`、末尾附一条 `✎ 其他（自定义输入）`）+
  进度 `(第 i/n 问)`。样式复用 `Theme.PICK_TITLE/PICK_SEL/PICK_ITEM/PICK_DESC`（**纯前景高亮，不用底色条**——
  见既有 `slashMenuChildren` 注释，底色会串行）。
- **按键**（`onAskKey`，在 `onInputKey` 顶部像 `pickingModel` 一样拦截、吞掉一切文本编辑）：
  - `↑↓/kj` 移动高亮；`1–9` 仅移动高亮到第 n 项（**不隐式确认**——单选也需再按 `Enter`，与多选语义统一，避免「单选数字=选定、多选数字=切换」的不一致）；
  - 单选：`Enter` 选中当前高亮项并进入下一问；
  - 多选：`空格` 切换当前高亮项的勾选（`[x]/[ ]`），`Enter` 确认本问进入下一问（至少勾一项才放行，否则提示「至少选择一项」）；
  - 选中「其他」→ 切到**自由文本子模式**：复用一个 `TextAreaState` 单行输入，`Enter` 确认该文本为答案；
  - 最后一问答完 → 组装 `Map<question文本, 答案>` → `request.responder().answer(map)` → 清 `askActive` + `state.clearPendingAsk()`。
- **取消**：`Esc` → `request.responder().cancel()`（唤醒工具线程抛 `QuestionCancelledException`）+ 走既有回合取消
  （`current.dispose()` → `doOnCancel` 回滚会话，清掉本回合悬空的 `assistant(tool_calls)`）+ 清 `askActive`。

**顺序问询**保证答案完整性：一次只答一个问题、答完才进下一个，最终 map 必然覆盖全部 question →
`answersValidation` 永不触发 `InvalidUserAnswerException`，无需配置 Spring 的 `throw-exception-on-error`
（本项目手动装配、非 Spring Boot，本就没有该属性入口）。

## 6. 装配（`AgentTools.build`）

```java
UserQuestionBridge askBridge = new UserQuestionBridge(listener);
AskUserQuestionTool askTool = AskUserQuestionTool.builder()
        .questionHandler(askBridge)
        .answersValidation(true)
        .build();
// 与其它 @Tool 一起转 ToolCallback、统一用 ToolEventCallback 装饰（"⏺ AskUserQuestionTool" 一行工具活动可见），
// 再进 defaultTools。装饰器设置 CURRENT_TURN，使 bridge.handle() 能读到 currentTurnId()。
```

`AskUserQuestionTool` 装饰后：`onToolStarted` 先打一行工具活动、状态转 `RUNNING_TOOL`，问题面板随即弹出；
用户答完 handler 返回 → `onToolFinished` 收尾 → 流继续。无需给 `AgentRuntime` 增字段（桥只依赖 `listener`，自足）。

系统提示追加一句引导：「当需求含糊、或需要用户在多个实现方案间拍板时，用 AskUserQuestionTool 提问，别自行臆测。」

## 7. 取消 / 边界 / 竞态

| 场景 | 处理 |
|---|---|
| 问题挂起时按 Esc | `responder.cancel()` 唤醒工具线程抛 `QuestionCancelledException`；dispose + `doOnCancel` 回滚会话（复用现有机制），清掉悬空 tool_calls，无 400 |
| 迟到的 `onQuestionAsked`（回合已被取消） | `ConversationState` 按 `turnId==acceptingTurnId` 过滤丢弃；桥侧因队列无人 `answer`，靠 cancel 路径唤醒 |
| 存 req 后、take 前 UI 抢先 answer | `ArrayBlockingQueue(1).offer` 恒成功，`take` 随后取到，无丢失 |
| 作答期间用户还想排队新消息 | `askActive` 吞掉输入编辑；作答本质是回合的一部分，符合「忙碌态」语义 |
| 多选一项都不勾就 Enter | 拦下并提示「至少选择一项」，不放行 |
| 模型一次问 >4 问 | 工具层 `validateQuestions` 已拒（1–4）；UI 只需按返回的 specs 顺序问 |
| 子 Agent 调用 | 本项目无 Task 工具，N/A（文档已声明子 Agent 不支持） |

## 8. 分阶段任务（TDD，subagent 驱动）

1. **纯 Java 模型 + 接缝**：`QuestionSpec`/`OptionSpec`/`AskResponder`/`AskRequest`；`AgentListener.onQuestionAsked`；
   `ConversationState` 的 `pendingAsk` 存取 + 迟到过滤。单测：迟到回合被丢弃、正常回合被存。
2. **桥 `UserQuestionBridge`**：`handle` 翻译 + 阻塞握手 + 取消抛异常。单测（不碰真实 LLM）：另起线程调 `handle`，
   主线程 `answer(map)` → 返回该 map；`cancel()` → 抛 `QuestionCancelledException`；翻译正确剥离 Spring AI 类型。
3. **装配接线**：`AgentTools.build` 建桥、建 `AskUserQuestionTool`、装饰、进 `defaultTools`；补一句系统提示。
   单测：`ChatClient` 组装不报错；工具出现在回调集合里。
4. **UI 作答面板**：`askActive` 状态机、`onAskKey`、`askChildren`、单选/多选、`Esc` 取消联动回合取消。
   单测（`CodeTuiViewBindingsTest` 风格，纯状态断言）：单选 Enter 推进、多选空格切换、Esc 触发 cancel。
5. **「Other」自由文本子模式**（可作为 4 的增量）：`TextAreaState` 单行输入 → 确认为答案。
6. **pty+pyte 实机冒烟**（记忆铁律：内联 TUI 改动必须真机验证）：模型真发一次 AskUserQuestion（或桩驱动），
   验证面板弹出、选择、答案回填、Esc 取消不 400、改完**重新 package**。

## 9. 不做（明确排除）

- 不做「一屏同时展示全部问题」——顺序问询更简单且保证答案完整、无需处理跨问校验。
- 不配置 `spring.ai.tools.throw-exception-on-error`（顺序问询保证完整答案，validation 不触发；且非 Spring Boot）。
- 不支持子 Agent 提问（无 Task 工具）。
- 不持久化「问答历史」到会话展示层之外——问答已随 tool_call/tool_result 进会话记忆，足够。
